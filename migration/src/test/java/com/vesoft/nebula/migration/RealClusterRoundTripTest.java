package com.vesoft.nebula.migration;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.vesoft.nebula.Value;
import com.vesoft.nebula.client.graph.data.ResultSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

/** Opt-in integration acceptance. Creates uniquely named spaces and leaves them for inspection. */
public class RealClusterRoundTripTest {
    @Test(timeout = 1200000)
    public void sourceScanCsvTargetRoundTrip() throws Exception {
        Assume.assumeTrue("Set -Dnebula.acceptance=true to use a real server",
            Boolean.getBoolean("nebula.acceptance"));
        String runId = Long.toString(System.currentTimeMillis(), 36);
        Path reportDirectory = Paths.get("target", "acceptance", runId).toAbsolutePath();
        Files.createDirectories(reportDirectory);
        ConnectionConfig config = new ConnectionConfig(System.getProperty("nebula.host", "127.0.0.1"),
            Integer.getInteger("nebula.graphPort", 9669), Integer.getInteger("nebula.metaPort", 9559),
            System.getProperty("nebula.user", "root"), System.getProperty("nebula.password", "nebula"));
        config.schemaWaitMillis = Long.getLong("nebula.schemaWaitMillis", 20000L);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("startedAt", Instant.now().toString());
        report.put("server", config.host + ":" + config.graphPort);
        report.put("serverBuild", System.getProperty("nebula.serverBuild", "see SHOW HOSTS/build binary"));
        report.put("status", "RUNNING");
        List<Map<String, Object>> runs = new ArrayList<>();
        report.put("runs", runs);
        Path reportFile = reportDirectory.resolve("acceptance-report.json");
        try {
            for (boolean integerVid : new boolean[] {false, true}) {
                String suffix = integerVid ? "i" : "s";
                String source = "mig_" + runId + "_" + suffix + "_a";
                String target = "mig_" + runId + "_" + suffix + "_b";
                String vidType = integerVid ? "INT64" : "FIXED_STRING(128)";
                Map<String, Object> run = new LinkedHashMap<>();
                runs.add(run);
                run.put("sourceSpace", source);
                run.put("targetSpace", target);
                run.put("vidType", vidType);
                run.put("status", "CREATING_SOURCE");
                save(reportFile, report);
                System.out.println("ACCEPTANCE create " + source + " -> " + target + " " + vidType);
                AcceptanceSamples.Fixture fixture;
                try (MigrationEngine.GraphConnection graph = MigrationEngine.openGraph(config)) {
                    graph.execute("CREATE SPACE " + MigrationEngine.quoteIdentifier(source)
                        + "(partition_num=3,replica_factor=1,vid_type=" + vidType + ")");
                    Thread.sleep(config.schemaWaitMillis);
                    fixture = AcceptanceSamples.populate(graph.session, source, integerVid,
                        config.schemaWaitMillis);
                    verifyRankBoundary(graph, fixture, run);
                    run.put("namedCases", fixture.cases);
                    run.put("caseCount", fixture.cases.size());
                    run.put("expectedRecords", fixture.records.size());
                    System.out.println("ACCEPTANCE source inserted " + fixture.records.size() + " records");
                    long compared = AcceptanceSamples.verifyExpected(graph.session, source, fixture);
                    run.put("sourceNativePropertyComparisons", compared);
                    run.put("status", "SOURCE_NATIVE_VALUES_PASSED");
                    save(reportFile, report);
                    System.out.println("ACCEPTANCE source native values passed: " + compared);
                }
                Path bundle = reportDirectory.resolve("bundle_" + suffix);
                int limit = integerVid ? 11 : 7;
                try (MigrationEngine engine = new MigrationEngine(config, config, limit)) {
                    VerificationReport verified = engine.migrate(source, target, bundle);
                    Assert.assertTrue(verified.matched);
                    Assert.assertTrue(verified.sourceRechecked);
                    Assert.assertEquals(fixture.records.size(), verified.tagRows + verified.edgeRows);
                    run.put("scanLimit", limit);
                    run.put("migrationVerification", verified);
                    run.put("bundle", bundle.toString());
                    try (MigrationEngine.GraphConnection graph = MigrationEngine.openGraph(config)) {
                        long compared = AcceptanceSamples.verifyExpected(graph.session, target, fixture);
                        run.put("targetNativePropertyComparisons", compared);
                    }
                    // Different page sizes must produce exactly the same complete key/value set.
                    List<Integer> pageSizes = new ArrayList<>();
                    for (int pageSize : new int[] {1, 13, 1000}) {
                        try (MigrationEngine other = new MigrationEngine(config, config, pageSize)) {
                            Assert.assertTrue(other.verifySourceAndTarget(bundle, target).matched);
                        }
                        pageSizes.add(pageSize);
                    }
                    run.put("additionalVerifiedPageSizes", pageSizes);
                    testTamperRejection(engine, bundle, target, fixture, config, run);
                    run.put("status", "PASSED");
                    save(reportFile, report);
                    System.out.println("ACCEPTANCE PASSED " + source + " -> " + target);
                }
            }
            report.put("status", "PASSED");
            report.put("finishedAt", Instant.now().toString());
            save(reportFile, report);
            System.out.println("ACCEPTANCE_REPORT=" + reportFile);
        } catch (Throwable failure) {
            report.put("status", "FAILED");
            report.put("error", failure.toString());
            report.put("finishedAt", Instant.now().toString());
            save(reportFile, report);
            throw failure;
        }
    }

    private static void verifyRankBoundary(MigrationEngine.GraphConnection graph,
                                            AcceptanceSamples.Fixture fixture,
                                            Map<String, Object> run) throws Exception {
        String vid = MigrationEngine.vidLiteral(fixture.records.get(0).keys.get(0));
        ResultSet result = graph.session.execute("EXPLAIN INSERT EDGE bare_edge() VALUES "
            + vid + "->" + vid + "@-9223372036854775808:()");
        Assert.assertFalse("The local 3.6 parser's unsupported rank boundary changed",
            result.isSucceeded());
        Assert.assertTrue(result.getErrorMessage(), result.getErrorMessage().contains("Out of range"));
        run.put("minimumRankServerRejection", result.getErrorMessage());
        run.put("testedWritableRankMin", Long.MIN_VALUE + 1);
        run.put("testedWritableRankMax", Long.MAX_VALUE);
        try {
            MigrationEngine.rankLiteral(Value.iVal(Long.MIN_VALUE));
            Assert.fail("The migration tool must reject the unrepresentable rank before INSERT");
        } catch (IllegalStateException expected) {
            run.put("minimumRankPreflightRejected", true);
        }
    }

    private static void testTamperRejection(MigrationEngine engine, Path bundle, String target,
                                            AcceptanceSamples.Fixture fixture, ConnectionConfig config,
                                            Map<String, Object> run) throws Exception {
        SchemaManifest manifest = MigrationEngine.inspect(bundle);
        Path csv = bundle.resolve(manifest.tables.get(0).file);
        byte[] original = Files.readAllBytes(csv);
        Files.write(csv, "corrupt\n".getBytes(StandardCharsets.US_ASCII));
        try {
            MigrationEngine.inspect(bundle);
            Assert.fail("Corrupted CSV must be rejected");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("SHA-256"));
        } finally {
            Files.write(csv, original);
        }
        run.put("corruptedCsvRejected", true);
        try {
            engine.importSpace(bundle, target);
            Assert.fail("Existing target space must be rejected");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("already exists"));
        }
        run.put("existingTargetRejected", true);
        AcceptanceSamples.Expected record = fixture.records.get(0);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", record.keys.get(0));
        // One-bit change is below nGQL's equality tolerance but must fail exact verification.
        params.put("changed", Value.fVal(Math.nextUp(record.values.get(6).getFVal())));
        try (MigrationEngine.GraphConnection graph = MigrationEngine.openGraph(config)) {
            graph.execute("USE " + MigrationEngine.quoteIdentifier(target));
            graph.execute("UPDATE VERTEX ON all_types " + MigrationEngine.vidLiteral(record.keys.get(0))
                + " SET f64=$changed", params);
            try {
                engine.verify(bundle, target);
                Assert.fail("A single changed floating-point bit must fail verification");
            } catch (IllegalStateException expected) {
                Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("property value differs"));
            } finally {
                params.put("changed", record.values.get(6));
                graph.execute("UPDATE VERTEX ON all_types " + MigrationEngine.vidLiteral(record.keys.get(0))
                    + " SET f64=$changed", params);
            }
        }
        Assert.assertTrue(engine.verifySourceAndTarget(bundle, target).matched);
        run.put("oneBitTargetChangeDetectedAndRestored", true);
    }

    private static void save(Path file, Object value) throws Exception {
        Files.write(file, JSON.toJSONString(value, SerializerFeature.PrettyFormat)
            .getBytes(StandardCharsets.UTF_8));
    }
}
