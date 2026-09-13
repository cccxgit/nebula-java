package com.vesoft.nebula.verification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.alibaba.fastjson.JSON;
import com.vesoft.nebula.Edge;
import com.vesoft.nebula.Tag;
import com.vesoft.nebula.Value;
import com.vesoft.nebula.Vertex;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class OfflineComparatorTest {
    private static final String PLAN_ID = "round-trip-batch-1";
    private static final String PLAN_DIGEST = String.join("", Collections.nCopies(64, "a"));

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void matchingCapturesIgnoreSpaceTimeRecordOrderAndInternalEdgeType() throws Exception {
        Fixture source = capture(m -> { m.space = "space_a"; m.capturedAt = "2026-01-01T00:00:00Z"; },
                vertex(1), edge(1), vertex(2));
        Fixture target = capture(m -> { m.space = "space_b"; m.capturedAt = "2026-01-02T00:00:00Z"; },
                vertex(2), vertex(1), edge(999));
        ComparisonReport report = OfflineComparator.compare(source.directory, target.directory);
        assertEquals("MATCH", report.status);
        assertTrue(report.matched);
        assertEquals(3, report.comparedRecords);
        assertEquals(0, report.differentRecords);
        assertEquals(0, report.errorRecords);
        assertEquals(0, report.differenceFields);
        assertEquals("space_a", report.sourceSpace);
        assertEquals("space_b", report.targetSpace);
        assertEquals("CONTENTS_OF_LISTED_OBJECTS_ONLY", report.scope);
        assertEquals(3, SnapshotFiles.load(source.directory).records.size());
    }

    @Test
    public void floatingPointSignedZeroDiffersEvenThoughNumericallyEqual() throws Exception {
        CaptureRecord left = vertex(1);
        CaptureRecord right = vertex(1);
        left.fields.put("tag/cGVyc29u/prop/Zg==", NativeValueCodec.encode(Value.fVal(-0.0)));
        right.fields.put("tag/cGVyc29u/prop/Zg==", NativeValueCodec.encode(Value.fVal(0.0)));
        ComparisonReport report = compare(left, right);
        assertEquals("DIFFERENT", report.status);
        assertFalse(report.matched);
        assertEquals(1, report.comparedRecords);
        assertEquals(1, report.differentRecords);
        assertEquals(1, report.differenceFields);
        assertEquals("FIELD_DIFFERS", report.differences.get(0).reason);
        assertTrue(report.differences.get(0).sourceValue.contains("8000000000000000"));
        assertTrue(report.differences.get(0).targetValue.contains("0000000000000000"));
    }

    @Test
    public void aSingleFloatingPointBitDifferenceIsReported() throws Exception {
        CaptureRecord left = vertex(1);
        CaptureRecord right = vertex(1);
        left.fields.put("tag/cGVyc29u/prop/Zg==", NativeValueCodec.encode(Value.fVal(1.0)));
        right.fields.put("tag/cGVyc29u/prop/Zg==", NativeValueCodec.encode(Value.fVal(Math.nextUp(1.0))));
        ComparisonReport report = compare(left, right);
        assertEquals("DIFFERENT", report.status);
        assertEquals(1, report.differenceFields);
        assertFalse(report.matched);
    }

    @Test
    public void schemaDifferencesAreReportedEvenWhenAllValuesMatch() throws Exception {
        Fixture source = capture(m -> m.schemas.put("TAG/person", "FLOAT"), vertex(1));
        Fixture target = capture(m -> m.schemas.put("TAG/person", "DOUBLE"), vertex(1));
        ComparisonReport report = OfflineComparator.compare(source.directory, target.directory);
        assertEquals("DIFFERENT", report.status);
        assertEquals(1, report.comparedRecords);
        assertEquals(0, report.differentRecords);
        assertEquals(1, report.differenceFields);
        assertEquals("SCHEMA_DIFFERS", report.differences.get(0).reason);
        assertEquals("FLOAT", report.differences.get(0).sourceValue);
        assertEquals("DOUBLE", report.differences.get(0).targetValue);
    }

    @Test
    public void missingSchemaIsDistinctFromAnEmptySchema() throws Exception {
        Fixture source = capture(m -> m.schemas.put("TAG/empty", "[]"), vertex(1));
        Fixture target = capture(m -> { }, vertex(1));
        ComparisonReport report = OfflineComparator.compare(source.directory, target.directory);
        assertEquals("DIFFERENT", report.status);
        assertEquals("SCHEMA_DIFFERS", report.differences.get(0).reason);
        assertNull(report.differences.get(0).targetValue);
    }

    @Test
    public void anEmptyTagMissingOnOneSideIsADataDifference() throws Exception {
        CaptureRecord left = vertex(1);
        CaptureRecord right = vertex(1);
        left.fields.put("tag/ZW1wdHk=", "present");
        ComparisonReport report = compare(left, right);
        assertEquals("DIFFERENT", report.status);
        assertEquals(1, report.differentRecords);
        assertEquals("tag/ZW1wdHk=", report.differences.get(0).field);
        assertEquals("present", report.differences.get(0).sourceValue);
        assertNull(report.differences.get(0).targetValue);
    }

    @Test
    public void aTagNameWhoseBase64ContainsSlashIsStillAnActualEmptyTag() throws Exception {
        Tag tag = new Tag("丿".getBytes(StandardCharsets.UTF_8), Collections.emptyMap());
        CaptureRecord record = vertex(1);
        record.fields = NativeValueCodec.fields(Value.vVal(new Vertex(Value.iVal(1), Collections.singletonList(tag))));
        assertEquals("present", record.fields.get("tag/5Li/"));
        ComparisonReport report = compare(record, record);
        assertEquals("MATCH", report.status);
        assertTrue(report.matched);
    }

    @Test
    public void identicalErrorsOnBothSidesNeverBecomeMatch() throws Exception {
        CaptureRecord left = failure(1, "ERROR", "storage timeout");
        CaptureRecord right = failure(1, "ERROR", "storage timeout");
        ComparisonReport report = compare(left, right);
        assertEquals("ERROR", report.status);
        assertFalse(report.matched);
        assertEquals(0, report.comparedRecords);
        assertEquals(1, report.errorRecords);
        assertEquals(1, report.differenceFields);
        assertEquals("OBJECT_NOT_SUCCESSFULLY_CAPTURED", report.differences.get(0).reason);
    }

    @Test
    public void missingObjectOnEitherOrBothSidesPreventsMatch() throws Exception {
        for (boolean missingSource : new boolean[]{false, true}) {
            CaptureRecord left = missingSource ? failure(1, "MISSING", "no vertex") : vertex(1);
            CaptureRecord right = failure(1, "MISSING", "no vertex");
            ComparisonReport report = compare(left, right);
            assertEquals("ERROR", report.status);
            assertFalse(report.matched);
            assertEquals(1, report.errorRecords);
            assertEquals(0, report.comparedRecords);
        }
    }

    @Test
    public void globalCaptureErrorsPreventMatchEvenWithIdenticalSuccessfulRecords() throws Exception {
        Fixture source = capture(m -> m.errors.add("Failed to retrieve complete schema"), vertex(1));
        Fixture target = capture(m -> { }, vertex(1));
        ComparisonReport report = OfflineComparator.compare(source.directory, target.directory);
        assertEquals("ERROR", report.status);
        assertFalse(report.matched);
        assertEquals(1, report.comparedRecords);
        assertEquals("CAPTURE_ERROR", report.differences.get(0).reason);
    }

    @Test
    public void changedCaptureBytesFailIntegrityChecksBeforeComparison() throws Exception {
        Fixture source = capture(m -> { }, vertex(1));
        Fixture target = capture(m -> { }, vertex(1));
        Files.write(target.directory.resolve("records.jsonl"), "\n".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);
        reject(() -> OfflineComparator.compare(source.directory, target.directory), "SHA-256 mismatch");
    }

    @Test
    public void missingPhysicalRecordIsRejectedEvenAfterRehashing() throws Exception {
        Fixture source = capture(m -> { }, vertex(1), vertex(2));
        Fixture target = capture(m -> { }, vertex(1), vertex(2));
        Path records = target.directory.resolve("records.jsonl");
        List<String> lines = Files.readAllLines(records, StandardCharsets.UTF_8);
        Files.write(records, Collections.singletonList(lines.get(0)), StandardCharsets.UTF_8);
        target.manifest.recordsSha256 = FileSupport.sha256(records);
        rewriteManifest(target);
        reject(() -> OfflineComparator.compare(source.directory, target.directory), "every requested object");
    }

    @Test
    public void differentRequestedKeySetsAreRejectedEvenWithEqualCounts() throws Exception {
        Fixture source = capture(m -> { }, vertex(1));
        Fixture target = capture(m -> { }, vertex(2));
        reject(() -> OfflineComparator.compare(source.directory, target.directory), "same requested keys");
    }

    @Test
    public void differentPlanIdsDigestsAndVidTypesCannotBeCompared() throws Exception {
        Fixture source = capture(m -> { }, vertex(1));
        Fixture differentId = capture(m -> m.planId = "another-batch", vertex(1));
        reject(() -> OfflineComparator.compare(source.directory, differentId.directory), "different plans");
        Fixture differentDigest = capture(m -> m.planDigest = String.join("", Collections.nCopies(64, "b")),
                vertex(1));
        reject(() -> OfflineComparator.compare(source.directory, differentDigest.directory), "different plans");
        Fixture differentType = capture(m -> m.vidType = "FIXED_STRING(32)", vertex(1));
        reject(() -> OfflineComparator.compare(source.directory, differentType.directory), "VID types differ");
    }

    @Test
    public void duplicateCapturedIdsAndUnfinishedCaptureAreRejected() throws Exception {
        Path duplicate = temporary.newFolder().toPath();
        try (BufferedWriter writer = SnapshotFiles.begin(duplicate)) {
            SnapshotFiles.writeRecord(writer, vertex(1));
            SnapshotFiles.writeRecord(writer, vertex(1));
        }
        reject(() -> SnapshotFiles.finish(duplicate, new SnapshotManifest()), "Duplicate requested object");
        Path unfinished = temporary.newFolder().toPath();
        try (BufferedWriter writer = SnapshotFiles.begin(unfinished)) {
            SnapshotFiles.writeRecord(writer, vertex(1));
        }
        reject(() -> SnapshotFiles.load(unfinished), "Missing file");
    }

    @Test
    public void falseCompletionCountersAndRequestedKeyDigestAreRejected() throws Exception {
        Fixture complete = capture(m -> { }, vertex(1));
        complete.manifest.okCount = 0;
        rewriteManifest(complete);
        reject(() -> SnapshotFiles.load(complete.directory), "outcome counters mismatch");

        Fixture incomplete = capture(m -> { }, failure(1, "ERROR", "read failed"));
        incomplete.manifest.status = "COMPLETE";
        rewriteManifest(incomplete);
        reject(() -> SnapshotFiles.load(incomplete.directory), "completion status contradicts");

        Fixture wrongKeys = capture(m -> { }, vertex(1));
        wrongKeys.manifest.requestedKeysSha256 = SnapshotFiles.keyDigest(Collections.singleton("vertex|V:Mg=="));
        rewriteManifest(wrongKeys);
        reject(() -> SnapshotFiles.load(wrongKeys.directory), "requested-key set mismatch");
    }

    @Test
    public void rehashedBlankRecordsAndMissingNativeIdentityAreRejected() throws Exception {
        Fixture blank = capture(m -> { }, vertex(1));
        Path records = blank.directory.resolve("records.jsonl");
        Files.write(records, "\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        blank.manifest.recordsSha256 = FileSupport.sha256(records);
        rewriteManifest(blank);
        reject(() -> SnapshotFiles.load(blank.directory), "Blank capture record");
        CaptureRecord missingIdentity = vertex(1);
        missingIdentity.fields.remove("$vid");
        reject(() -> capture(m -> { }, missingIdentity), "missing native VID");
        CaptureRecord missingTag = vertex(1);
        missingTag.fields.remove("tag/cGVyc29u");
        reject(() -> capture(m -> { }, missingTag), "no actual Tag");
    }

    @Test
    public void differenceDetailLimitNeverChangesTheMismatchOutcome() throws Exception {
        CaptureRecord source = vertex(1);
        CaptureRecord target = vertex(1);
        for (int i = 0; i < 1005; i++) {
            String field = "tag/cGVyc29u/prop/" + Identifiers.nameCell("p" + i).substring(2);
            source.fields.put(field, NativeValueCodec.encode(Value.iVal(i)));
            target.fields.put(field, NativeValueCodec.encode(Value.iVal(i + 1)));
        }
        ComparisonReport report = compare(source, target);
        assertEquals("DIFFERENT", report.status);
        assertFalse(report.matched);
        assertEquals(1005, report.differenceFields);
        assertEquals(1000, report.differences.size());
        assertEquals(1, report.differentRecords);
        assertTrue(report.detailsTruncated);
    }

    private ComparisonReport compare(CaptureRecord source, CaptureRecord target) throws Exception {
        Fixture left = capture(m -> { }, source);
        Fixture right = capture(m -> { }, target);
        return OfflineComparator.compare(left.directory, right.directory);
    }

    private Fixture capture(Consumer<SnapshotManifest> customize, CaptureRecord... records) throws Exception {
        Fixture fixture = new Fixture();
        fixture.directory = temporary.newFolder().toPath();
        fixture.manifest = new SnapshotManifest();
        SnapshotManifest manifest = fixture.manifest;
        manifest.planId = PLAN_ID;
        manifest.planDigest = PLAN_DIGEST;
        manifest.vidType = "INT64";
        manifest.space = "test_space";
        manifest.capturedAt = "2026-01-01T00:00:00Z";
        manifest.schemas.put("TAG/person", "same-schema");
        manifest.requestedCount = records.length;
        manifest.recordCount = records.length;
        TreeSet<String> keys = new TreeSet<>();
        for (CaptureRecord record : records) {
            keys.add(record.key);
            if ("OK".equals(record.status)) { manifest.okCount++; }
            else if ("MISSING".equals(record.status)) { manifest.missingCount++; }
            else { manifest.errorCount++; }
        }
        manifest.requestedKeysSha256 = SnapshotFiles.keyDigest(keys);
        customize.accept(manifest);
        manifest.status = manifest.missingCount == 0 && manifest.errorCount == 0 && manifest.errors.isEmpty()
                ? "COMPLETE" : "INCOMPLETE";
        try (BufferedWriter writer = SnapshotFiles.begin(fixture.directory)) {
            for (CaptureRecord record : records) {
                SnapshotFiles.writeRecord(writer, record);
            }
        }
        SnapshotFiles.finish(fixture.directory, manifest);
        return fixture;
    }

    private static CaptureRecord vertex(long id) {
        CaptureRecord record = new CaptureRecord();
        record.key = PlanBundle.vertexKey(Identifiers.cell(Value.iVal(id)));
        record.kind = "VERTEX";
        record.status = "OK";
        Tag tag = new Tag("person".getBytes(StandardCharsets.UTF_8), Collections.emptyMap());
        record.fields = NativeValueCodec.fields(Value.vVal(new Vertex(Value.iVal(id), Collections.singletonList(tag))));
        return record;
    }

    private static CaptureRecord edge(int type) {
        PlanBundle.EdgeKey key = new PlanBundle.EdgeKey();
        key.src = Identifiers.cell(Value.iVal(1));
        key.dst = Identifiers.cell(Value.iVal(2));
        key.rank = Identifiers.cell(Value.iVal(7));
        key.edge = Identifiers.nameCell("knows");
        CaptureRecord record = new CaptureRecord();
        record.key = PlanBundle.edgeKey(key);
        record.kind = "EDGE";
        record.status = "OK";
        record.fields = NativeValueCodec.fields(Value.eVal(new Edge(Value.iVal(1), Value.iVal(2), type,
                "knows".getBytes(StandardCharsets.UTF_8), 7, Collections.emptyMap())));
        return record;
    }

    private static CaptureRecord failure(long id, String status, String error) {
        CaptureRecord record = vertex(id);
        record.status = status;
        record.error = error;
        record.fields.clear();
        return record;
    }

    private static void rewriteManifest(Fixture fixture) throws Exception {
        Files.write(fixture.directory.resolve("manifest.json"),
                JSON.toJSONString(fixture.manifest).getBytes(StandardCharsets.UTF_8));
    }

    private static void reject(Action action, String message) throws Exception {
        try {
            action.run();
            fail("Accepted an incomplete, mismatched, or corrupted capture");
        } catch (Exception expected) {
            assertTrue("Unexpected rejection: " + expected, expected.getMessage().contains(message));
        }
    }

    private static final class Fixture {
        private Path directory;
        private SnapshotManifest manifest;
    }

    private interface Action {
        void run() throws Exception;
    }
}
