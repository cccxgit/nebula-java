package com.vesoft.nebula.verification;

import com.vesoft.nebula.Value;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class VerifierCliTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void helpDoesNotNeedAConnection() {
        Assert.assertEquals(0, VerifierCli.run(new String[]{"--help"}));
    }

    @Test public void rejectsUnknownAndDuplicateArguments() {
        Assert.assertEquals(1, VerifierCli.run(new String[]{"compare", "--soruce", "bad"}));
        Assert.assertEquals(1, VerifierCli.run(new String[]{"compare", "--source", "a", "--source", "b"}));
        Assert.assertEquals(1, VerifierCli.run(new String[]{"capture", "--plan"}));
    }

    @Test public void preparesPortableIdentifiersOffline() throws Exception {
        Path vertices = temp.getRoot().toPath().resolve("v.csv");
        Path edges = temp.getRoot().toPath().resolve("e.csv");
        Files.write(vertices, "vid\nV:MQ==\n".getBytes(StandardCharsets.US_ASCII));
        Files.write(edges, "src,edge,rank,dst\n".getBytes(StandardCharsets.US_ASCII));
        Path plan = temp.getRoot().toPath().resolve("plan");
        Assert.assertEquals(0, VerifierCli.run(new String[]{"prepare-ids", "--vertices", vertices.toString(),
                "--edges", edges.toString(), "--vid-type", "INT64", "--out", plan.toString()}));
        Assert.assertEquals(Collections.singletonList("V:MQ=="), PlanBundle.load(plan).vertices);
        Assert.assertEquals(1, VerifierCli.run(new String[]{"capture", "--plan", plan.toString(),
                "--space", "unused", "--out", temp.getRoot().toPath().resolve("none").toString(),
                "--password-env", "NEBULA_VERIFIER_TEST_ABSENT_PASSWORD_7FC0D42C"}));
    }

    @Test public void matchAndDifferenceHaveDistinctExitCodes() throws Exception {
        Path a = snapshot("a", "one");
        Path b = snapshot("b", "one");
        Path c = snapshot("c", "two");
        Path matched = temp.getRoot().toPath().resolve("matched");
        Path different = temp.getRoot().toPath().resolve("different");
        Assert.assertEquals(0, compare(a, b, matched));
        Assert.assertTrue(FileSupport.readJson(matched.resolve("comparison-report.json"), ComparisonReport.class).matched);
        Assert.assertEquals(2, compare(a, c, different));
        Assert.assertEquals("DIFFERENT", FileSupport.readJson(different.resolve("comparison-report.json"),
                ComparisonReport.class).status);
    }

    @Test public void damagedCaptureProducesErrorReport() throws Exception {
        Path a = snapshot("a", "one");
        Path b = snapshot("b", "one");
        Files.write(b.resolve("records.jsonl"), "corrupted\n".getBytes(StandardCharsets.US_ASCII));
        Path report = temp.getRoot().toPath().resolve("error");
        Assert.assertEquals(1, compare(a, b, report));
        ComparisonReport outcome = FileSupport.readJson(report.resolve("comparison-report.json"), ComparisonReport.class);
        Assert.assertEquals("ERROR", outcome.status);
        Assert.assertFalse(outcome.matched);
    }

    @Test public void neverOverwritesExistingReport() throws Exception {
        Path a = snapshot("a", "one");
        Path b = snapshot("b", "one");
        Path report = temp.getRoot().toPath().resolve("report");
        Assert.assertEquals(0, compare(a, b, report));
        byte[] original = Files.readAllBytes(report.resolve("comparison-report.json"));
        Assert.assertEquals(1, compare(a, b, report));
        Assert.assertArrayEquals(original, Files.readAllBytes(report.resolve("comparison-report.json")));
    }

    private int compare(Path source, Path target, Path report) {
        return VerifierCli.run(new String[]{"compare", "--source", source.toString(), "--target", target.toString(),
                "--out", report.toString()});
    }

    private Path snapshot(String name, String value) throws Exception {
        Path out = temp.getRoot().toPath().resolve(name);
        CaptureRecord record = new CaptureRecord();
        record.key = "vertex|V:MQ==";
        record.kind = "VERTEX";
        record.status = "OK";
        record.fields.put("$vid", NativeValueCodec.encode(Value.iVal(1)));
        record.fields.put("tag/dA==", "present");
        record.fields.put("tag/dA==/prop/cA==", NativeValueCodec.encode(Value.sVal(value.getBytes(StandardCharsets.UTF_8))));
        try (BufferedWriter writer = SnapshotFiles.begin(out)) {
            SnapshotFiles.writeRecord(writer, record);
        }
        SnapshotManifest manifest = new SnapshotManifest();
        manifest.planId = "same-plan";
        manifest.planDigest = FileSupport.sha256("same-plan");
        manifest.vidType = "INT64";
        manifest.space = name;
        manifest.capturedAt = "2026-09-13T00:00:00Z";
        manifest.status = "COMPLETE";
        manifest.requestedCount = manifest.recordCount = manifest.okCount = 1;
        manifest.requestedKeysSha256 = SnapshotFiles.keyDigest(Collections.singleton(record.key));
        manifest.schemas.put("TAG/V:dA==", "{\"V:cA==\":{\"nullable\":true,\"type\":\"STRING\"}}");
        SnapshotFiles.finish(out, manifest);
        return out;
    }
}
