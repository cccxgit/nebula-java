package com.vesoft.nebula.verification;

import com.vesoft.nebula.Tag;
import com.vesoft.nebula.Value;
import com.vesoft.nebula.Vertex;
import com.vesoft.nebula.client.graph.NebulaPoolConfig;
import com.vesoft.nebula.client.graph.data.HostAddress;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.net.NebulaPool;
import com.vesoft.nebula.client.graph.net.Session;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

/** Opt-in acceptance against an existing fixture bundle and a disposable imported target space. */
public class VerifierRealClusterTest {
    @Test(timeout = 1200000)
    public void independentFetchSnapshotsDetectTargetChanges() throws Exception {
        Assume.assumeTrue("Set -Dnebula.verifier.acceptance=true to use a real cluster",
                Boolean.getBoolean("nebula.verifier.acceptance"));
        Path bundle = Paths.get(required("nebula.verifier.bundle")).toAbsolutePath();
        String source = required("nebula.verifier.source");
        String target = required("nebula.verifier.target");
        Assert.assertNotEquals("Acceptance mutations require a separate disposable target space",
                source, target);
        String runId = Long.toString(System.currentTimeMillis(), 36);
        Path output = Paths.get("target", "acceptance", "verifier-" + runId).toAbsolutePath();
        Files.createDirectories(output);
        List<String> report = new ArrayList<>();
        report.add("startedAt=" + Instant.now());
        report.add("sourceSpace=" + source);
        report.add("targetSpace=" + target);
        report.add("bundle=" + bundle);
        String host = System.getProperty("nebula.verifier.host", "127.0.0.1");
        int port = Integer.getInteger("nebula.verifier.port", 9669);
        String user = System.getProperty("nebula.verifier.user", "root");
        String password = System.getProperty("nebula.verifier.password", "nebula");
        ConnectionSettings settings = new ConnectionSettings(host, port, user, password);
        settings.timeoutMs = 60000;
        try {
            Path planDirectory = output.resolve("plan");
            PlanBundle.Plan prepared = PlanBundle.prepare(bundle, planDirectory);
            PlanBundle.Plan plan = PlanBundle.load(planDirectory);
            Assert.assertEquals(prepared.planId, plan.planId);
            Assert.assertEquals(prepared.digest, plan.digest);
            Assert.assertFalse("The fixture must contain vertices", plan.vertices.isEmpty());
            Assert.assertFalse("The fixture must contain edges", plan.edges.isEmpty());
            report.add("planId=" + plan.planId);
            report.add("planDigest=" + plan.digest);
            report.add("plannedVertices=" + plan.vertices.size());
            report.add("plannedEdges=" + plan.edges.size());
            Path sourceCapture = capture(plan, settings, source, output, "source");
            Path targetCapture = capture(plan, settings, target, output, "target-baseline");
            assertCompleteOk(plan, sourceCapture);
            assertCompleteOk(plan, targetCapture);
            ComparisonReport baseline = OfflineComparator.compare(sourceCapture, targetCapture);
            record(report, "baseline", baseline);
            Assert.assertTrue("Baseline native FETCH snapshots must match", baseline.matched);
            Assert.assertEquals(0L, baseline.differentRecords);
            Assert.assertEquals(0L, baseline.errorRecords);
            Assert.assertEquals((long) plan.vertices.size() + plan.edges.size(),
                    baseline.comparedRecords);
            save(output, report);

            try (TargetConnection graph = new TargetConnection(host, port, user, password, target)) {
                Value baselineVid = findBaselineVertex(graph, plan);
                testOneBitDoubleChange(graph, plan, settings, target, baselineVid,
                        sourceCapture, output, report);
                testMissingEmptyTag(graph, plan, settings, target, baselineVid,
                        sourceCapture, output, report);
                testAdditionalEmptyTag(graph, plan, settings, target, baselineVid,
                        sourceCapture, output, report, runId);
                testMissingEmptyEdge(graph, plan, settings, target, baselineVid,
                        sourceCapture, output, report);
            }

            // Matching failures must never be accepted as matching data.
            String absentSpace = "verify_absent_" + runId;
            Path failedFirst = captureExpectedFailure(plan, settings, absentSpace, output,
                    "error-first", report);
            Path failedSecond = captureExpectedFailure(plan, settings, absentSpace, output,
                    "error-second", report);
            if (failedFirst != null && failedSecond != null) {
                ComparisonReport failed = OfflineComparator.compare(failedFirst, failedSecond);
                record(report, "identical-query-errors", failed);
                Assert.assertFalse("Two failed collections cannot pass verification", failed.matched);
                Assert.assertTrue("Failed FETCH records must be counted", failed.errorRecords > 0);
            }

            Path restored = capture(plan, settings, target, output, "target-restored");
            assertCompleteOk(plan, restored);
            ComparisonReport restoredReport = OfflineComparator.compare(sourceCapture, restored);
            record(report, "restored", restoredReport);
            Assert.assertTrue("Every target mutation must have been restored", restoredReport.matched);
            report.add("status=PASSED");
        } catch (Throwable failure) {
            report.add("status=FAILED");
            report.add("failure=" + failure);
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(
                    output.resolve("failure.txt"), StandardCharsets.UTF_8))) {
                failure.printStackTrace(writer);
            }
            throw failure;
        } finally {
            report.add("finishedAt=" + Instant.now());
            save(output, report);
            System.out.println("VERIFIER_ACCEPTANCE_REPORT=" + output.resolve("acceptance-report.txt"));
        }
    }

    private static void testOneBitDoubleChange(TargetConnection graph, PlanBundle.Plan plan,
                                               ConnectionSettings settings, String target, Value vid,
                                               Path source, Path output, List<String> report)
            throws Exception {
        String key = Identifiers.vidLiteral(vid);
        ResultSet before = graph.execute("FETCH PROP ON all_types " + key
                + " YIELD all_types.f64 AS original");
        Assert.assertEquals(1, before.rowsSize());
        Value original = before.getRows().get(0).getValues().get(0).deepCopy();
        Assert.assertEquals(Value.FVAL, original.getSetField());
        Assert.assertEquals(Double.doubleToRawLongBits(0.1),
                Double.doubleToRawLongBits(original.getFVal()));
        String update = "UPDATE VERTEX ON all_types " + key + " SET f64=$changed";
        try {
            graph.execute(update, Collections.<String, Object>singletonMap(
                    "changed", Value.fVal(Math.nextUp(0.1))));
            Path changed = capture(plan, settings, target, output, "target-double-bit-change");
            assertCompleteOk(plan, changed);
            assertDifferent(source, changed, "double-one-bit-change", report);
        } finally {
            graph.execute(update, Collections.<String, Object>singletonMap("changed", original));
        }
        save(output, report);
    }

    private static void testMissingEmptyTag(TargetConnection graph, PlanBundle.Plan plan,
                                           ConnectionSettings settings, String target, Value vid,
                                           Path source, Path output, List<String> report)
            throws Exception {
        String key = Identifiers.vidLiteral(vid);
        Tag original = tag(fetchVertex(graph, vid), "bare_tag");
        Assert.assertNotNull("The fixture must include its zero-property tag", original);
        Assert.assertTrue(original.getProps().isEmpty());
        try {
            graph.execute("DELETE TAG bare_tag FROM " + key);
            Assert.assertNull("The empty tag must actually be removed",
                    tag(fetchVertex(graph, vid), "bare_tag"));
            // FETCH on a missing tag may still return the existing vertex ID. A row count
            // check alone therefore cannot establish the presence of this empty tag.
            Path changed = capture(plan, settings, target, output, "target-missing-empty-tag");
            assertCompleteOk(plan, changed);
            assertDifferent(source, changed, "missing-zero-property-tag", report);
        } finally {
            graph.execute("INSERT VERTEX bare_tag() VALUES " + key + ":()");
        }
        save(output, report);
    }

    private static void testAdditionalEmptyTag(TargetConnection graph, PlanBundle.Plan plan,
                                             ConnectionSettings settings, String target, Value vid,
                                             Path source, Path output, List<String> report,
                                             String runId) throws Exception {
        String name = "verify_extra_" + runId;
        String quoted = Identifiers.quote(name);
        String key = Identifiers.vidLiteral(vid);
        try {
            graph.execute("CREATE TAG " + quoted + "()");
            Thread.sleep(Long.getLong("nebula.verifier.schemaWaitMillis", 20000L));
            graph.execute("INSERT VERTEX " + quoted + "() VALUES " + key + ":()");
            Tag added = tag(fetchVertex(graph, vid), name);
            Assert.assertNotNull("The additional tag must be visible before collection", added);
            Assert.assertTrue(added.getProps().isEmpty());
            Path changed = capture(plan, settings, target, output, "target-extra-empty-tag");
            assertCompleteOk(plan, changed);
            assertDifferent(source, changed, "additional-zero-property-tag", report);
        } finally {
            try {
                graph.execute("DELETE TAG " + quoted + " FROM " + key);
            } finally {
                graph.execute("DROP TAG IF EXISTS " + quoted);
            }
        }
        save(output, report);
    }

    private static void testMissingEmptyEdge(TargetConnection graph, PlanBundle.Plan plan,
                                            ConnectionSettings settings, String target, Value vid,
                                            Path source, Path output, List<String> report)
            throws Exception {
        String key = Identifiers.vidLiteral(vid);
        String edgeKey = key + "->" + key + "@" + Identifiers.rankLiteral(Value.iVal(-7));
        ResultSet before = graph.execute("FETCH PROP ON bare_edge " + edgeKey + " YIELD edge AS e");
        Assert.assertEquals("The fixture must contain the zero-property self-edge", 1, before.rowsSize());
        Value edge = before.getRows().get(0).getValues().get(0);
        Assert.assertEquals(Value.EVAL, edge.getSetField());
        Assert.assertTrue(edge.getEVal().getProps().isEmpty());
        try {
            graph.execute("DELETE EDGE bare_edge " + edgeKey);
            Path changed = capture(plan, settings, target, output, "target-missing-empty-edge");
            boolean missing = false;
            for (CaptureRecord record : SnapshotFiles.load(changed).records.values()) {
                missing |= "MISSING".equals(record.status);
            }
            Assert.assertTrue("A missing listed edge must produce a MISSING record", missing);
            ComparisonReport result = OfflineComparator.compare(source, changed);
            record(report, "missing-zero-property-edge", result);
            Assert.assertFalse("A missing listed edge cannot pass verification", result.matched);
        } finally {
            graph.execute("INSERT EDGE bare_edge() VALUES " + edgeKey + ":()");
        }
        save(output, report);
    }

    private static Value findBaselineVertex(TargetConnection graph, PlanBundle.Plan plan)
            throws Exception {
        for (String cell : plan.vertices) {
            Value vid = Identifiers.decodeVid(cell, plan.vidType);
            Vertex vertex = fetchVertex(graph, vid);
            if (tag(vertex, "bare_tag") != null) {
                Assert.assertNotNull("The baseline must have all_types", tag(vertex, "all_types"));
                return vid;
            }
        }
        throw new AssertionError("This acceptance test requires the all-types fixture's bare_tag vertex");
    }

    private static Vertex fetchVertex(TargetConnection graph, Value vid) throws Exception {
        ResultSet result = graph.execute("FETCH PROP ON * " + Identifiers.vidLiteral(vid)
                + " YIELD vertex AS v");
        Assert.assertEquals("Expected the fixture vertex to exist", 1, result.rowsSize());
        Value value = result.getRows().get(0).getValues().get(0);
        Assert.assertEquals(Value.VVAL, value.getSetField());
        return value.getVVal();
    }

    private static Tag tag(Vertex vertex, String expected) {
        byte[] name = expected.getBytes(StandardCharsets.UTF_8);
        for (Tag tag : vertex.getTags()) {
            if (Arrays.equals(name, tag.getName())) {
                return tag;
            }
        }
        return null;
    }

    private static Path capture(PlanBundle.Plan plan, ConnectionSettings settings, String space,
                                Path output, String name) throws Exception {
        Path directory = output.resolve(name);
        FetchCollector.capture(plan, settings, space, directory);
        return directory;
    }

    private static void assertCompleteOk(PlanBundle.Plan plan, Path directory) throws Exception {
        SnapshotFiles.Snapshot snapshot = SnapshotFiles.load(directory);
        Assert.assertEquals("Every planned key must have a capture record",
                plan.vertices.size() + plan.edges.size(), snapshot.records.size());
        for (CaptureRecord record : snapshot.records.values()) {
            Assert.assertEquals("Capture failed at " + record.key + ": " + record.error,
                    "OK", record.status);
            Assert.assertNotNull("An OK record must have canonical fields", record.fields);
            Assert.assertFalse("An OK record cannot be empty", record.fields.isEmpty());
        }
    }

    private static void assertAllError(Path directory) throws Exception {
        SnapshotFiles.Snapshot snapshot = SnapshotFiles.load(directory);
        Assert.assertFalse("Error captures must retain the planned key records", snapshot.records.isEmpty());
        for (CaptureRecord record : snapshot.records.values()) {
            Assert.assertEquals("A missing space must not produce an OK or empty record",
                    "ERROR", record.status);
        }
    }

    private static Path captureExpectedFailure(PlanBundle.Plan plan, ConnectionSettings settings,
                                              String space, Path output, String name,
                                              List<String> report) throws Exception {
        Path directory;
        try {
            directory = capture(plan, settings, space, output, name);
        } catch (Exception expected) {
            report.add(name + ": collection rejected missing space ("
                    + expected.getClass().getSimpleName() + ")");
            return null;
        }
        assertAllError(directory);
        report.add(name + ": all planned records captured as ERROR");
        return directory;
    }

    private static void assertDifferent(Path source, Path changed, String name, List<String> report)
            throws Exception {
        ComparisonReport result = OfflineComparator.compare(source, changed);
        record(report, name, result);
        Assert.assertFalse(name + " must not pass verification", result.matched);
        Assert.assertEquals("DIFFERENT", result.status);
        Assert.assertTrue(name + " must identify a changed record", result.differentRecords > 0);
        Assert.assertEquals(name + " must be a value difference, not a query failure", 0L, result.errorRecords);
    }

    private static void record(List<String> report, String name, ComparisonReport result) {
        report.add(name + ": status=" + result.status + ", matched=" + result.matched
                + ", comparedRecords=" + result.comparedRecords
                + ", differentRecords=" + result.differentRecords
                + ", errorRecords=" + result.errorRecords);
    }

    private static void save(Path output, List<String> report) throws Exception {
        Files.write(output.resolve("acceptance-report.txt"), report, StandardCharsets.UTF_8);
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        Assert.assertTrue("Required system property: " + property, value != null && !value.trim().isEmpty());
        return value;
    }

    private static final class TargetConnection implements AutoCloseable {
        private final NebulaPool pool = new NebulaPool();
        private Session session;

        private TargetConnection(String host, int port, String user, String password, String space)
                throws Exception {
            try {
                NebulaPoolConfig config = new NebulaPoolConfig();
                config.setTimeout(60000);
                Assert.assertTrue("Cannot initialize the target graph connection",
                        pool.init(Collections.singletonList(new HostAddress(host, port)), config));
                session = pool.getSession(user, password, false);
                execute("USE " + Identifiers.quote(space));
            } catch (Throwable failure) {
                close();
                throw failure;
            }
        }

        private ResultSet execute(String statement) throws Exception {
            ResultSet result = session.execute(statement);
            Assert.assertTrue("Target statement failed: " + result.getErrorMessage(), result.isSucceeded());
            return result;
        }

        private ResultSet execute(String statement, Map<String, Object> parameters) throws Exception {
            ResultSet result = session.executeWithParameter(statement, new LinkedHashMap<>(parameters));
            Assert.assertTrue("Target statement failed: " + result.getErrorMessage(), result.isSucceeded());
            return result;
        }

        @Override
        public void close() {
            try {
                if (session != null) {
                    session.release();
                }
            } finally {
                pool.close();
            }
        }
    }
}
