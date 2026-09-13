package com.vesoft.nebula.verification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.vesoft.nebula.Value;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PlanBundleTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void standaloneInt64InventoryPreparesAndLoadsRealCsvFormat() throws Exception {
        Path input = directory();
        String minimum = Identifiers.cell(Value.iVal(Long.MIN_VALUE));
        String maximum = Identifiers.cell(Value.iVal(Long.MAX_VALUE));
        String rank = Identifiers.cell(Value.iVal(Long.MAX_VALUE));
        String edge = Identifiers.nameCell("likes");
        write(input.resolve("vertices.csv"), "vid\n" + maximum + "\n" + minimum + "\n");
        write(input.resolve("edges.csv"), "src,edge,rank,dst\n"
                + minimum + "," + edge + "," + rank + "," + maximum + "\n");
        Path output = directory();
        PlanBundle.Plan prepared = PlanBundle.prepareIds(input.resolve("vertices.csv"),
                input.resolve("edges.csv"), " int ", output);
        PlanBundle.Plan loaded = PlanBundle.load(output);
        assertEquals("INT64", loaded.vidType);
        assertEquals(prepared.planId, loaded.planId);
        assertEquals(prepared.digest, loaded.digest);
        assertEquals(prepared.keys(), loaded.keys());
        assertEquals(2, loaded.vertices.size());
        assertEquals(1, loaded.edges.size());
        assertEquals(3, loaded.keys().size());
        assertEquals(Long.MIN_VALUE, Identifiers.decodeVid(loaded.edges.get(0).src, loaded.vidType).getIVal());
        assertEquals(Long.MAX_VALUE, Identifiers.decodeRank(loaded.edges.get(0).rank).getIVal());
        assertEquals("vid", Files.readAllLines(output.resolve("vertices.csv")).get(0));
        assertEquals("src,edge,rank,dst", Files.readAllLines(output.resolve("edges.csv")).get(0));
        PlanBundle.Manifest manifest = FileSupport.readJson(output.resolve("plan.json"), PlanBundle.Manifest.class);
        assertEquals(FileSupport.sha256(output.resolve("vertices.csv")), manifest.verticesSha256);
        assertEquals(FileSupport.sha256(output.resolve("edges.csv")), manifest.edgesSha256);
    }

    @Test
    public void stringInventoryKeepsEmptyAndInvalidUtf8IdentifiersExact() throws Exception {
        String binary = Identifiers.cell(Value.sVal(new byte[]{(byte) 0xff, (byte) 0xc0, (byte) 0xaf}));
        Path output = prepare("fixed_string(3)", "vid\nV:\n" + binary + "\n", "src,edge,rank,dst\n");
        PlanBundle.Plan plan = PlanBundle.load(output);
        assertEquals("FIXED_STRING(3)", plan.vidType);
        assertEquals(Arrays.asList("V:", binary), plan.vertices);
    }

    @Test
    public void migrationInventoryDeduplicatesMultiTagVerticesAndIncludesEdgeEndpoints() throws Exception {
        Path export = exportFixture();
        Path output = directory();
        PlanBundle.Plan plan = PlanBundle.prepare(export, output);
        assertEquals(2, plan.vertices.size());
        assertEquals(1, plan.edges.size());
        assertTrue(plan.vertices.contains(stringCell("alice")));
        assertTrue(plan.vertices.contains(stringCell("bob")));
        assertEquals("knows", Identifiers.decodeName(plan.edges.get(0).edge));
        assertEquals(7, Identifiers.decodeRank(plan.edges.get(0).rank).getIVal());
        PlanBundle.Manifest manifest = FileSupport.readJson(output.resolve("plan.json"), PlanBundle.Manifest.class);
        assertEquals(FileSupport.sha256(export.resolve("manifest.json")), manifest.sourceExportManifestSha256);
        assertEquals(plan.keys(), PlanBundle.load(output).keys());
    }

    @Test
    public void planLoadRejectsChangedVertexOrEdgeCsv() throws Exception {
        for (String filename : Arrays.asList("vertices.csv", "edges.csv")) {
            Path output = basicPlan();
            Files.write(output.resolve(filename), "\n".getBytes(StandardCharsets.US_ASCII),
                    StandardOpenOption.APPEND);
            reject(() -> PlanBundle.load(output), "SHA-256 mismatch");
        }
    }

    @Test
    public void planIdentityDigestAndRecordCountCannotBeSilentlyChanged() throws Exception {
        Path changedId = basicPlan();
        JSONObject id = json(changedId.resolve("plan.json"));
        id.put("planId", "different-batch");
        writeJson(changedId.resolve("plan.json"), id);
        reject(() -> PlanBundle.load(changedId), "digest mismatch");

        Path changedCount = basicPlan();
        JSONObject count = json(changedCount.resolve("plan.json"));
        count.put("vertexCount", 2);
        writeJson(changedCount.resolve("plan.json"), count);
        reject(() -> PlanBundle.load(changedCount), "record count mismatch");

        Path changedVersion = basicPlan();
        JSONObject version = json(changedVersion.resolve("plan.json"));
        version.put("formatVersion", 2);
        writeJson(changedVersion.resolve("plan.json"), version);
        reject(() -> PlanBundle.load(changedVersion), "Unsupported plan version");
    }

    @Test
    public void duplicateStandaloneVertexAndEdgeIdsAreRejected() throws Exception {
        reject(() -> prepare("int64", "vid\nV:MQ==\nV:MQ==\n", "src,edge,rank,dst\n"),
                "Duplicate vertex");
        String row = "V:MQ==,V:a25vd3M=,V:MA==,V:Mg==\n";
        reject(() -> prepare("int64", "vid\nV:MQ==\n", "src,edge,rank,dst\n" + row + row),
                "Duplicate edge");
    }

    @Test
    public void identifierHeadersNullsFieldCountsAndRankMinimumAreRejected() throws Exception {
        reject(() -> prepare("int64", "_vid\nV:MQ==\n", "src,edge,rank,dst\n"), "header");
        reject(() -> prepare("int64", "vid\nV:MQ==\n", "src,dst,rank,edge\n"), "header");
        reject(() -> prepare("int64", "vid\nN\n", "src,edge,rank,dst\n"), "requires V:");
        reject(() -> prepare("int64", "vid\nV:MQ==\n", "src,edge,rank,dst\nV:MQ==,V:Mg==\n"),
                "field count");
        String rankMinimum = Identifiers.cell(Value.iVal(Long.MIN_VALUE));
        reject(() -> prepare("int64", "vid\nV:MQ==\n", "src,edge,rank,dst\n"
                + "V:MQ==,V:a25vd3M=," + rankMinimum + ",V:Mg==\n"), "Long.MIN_VALUE");
    }

    @Test
    public void emptyInventoryAndNonemptyOutputAreRejectedWithoutOverwriting() throws Exception {
        reject(() -> prepare("int64", "vid\n", "src,edge,rank,dst\n"), "at least one");
        Path input = directory();
        write(input.resolve("v.csv"), "vid\nV:MQ==\n");
        write(input.resolve("e.csv"), "src,edge,rank,dst\n");
        Path output = directory();
        write(output.resolve("keep.txt"), "preserve");
        reject(() -> PlanBundle.prepareIds(input.resolve("v.csv"), input.resolve("e.csv"),
                "int64", output), "must be empty");
        assertEquals("preserve", new String(Files.readAllBytes(output.resolve("keep.txt")), StandardCharsets.UTF_8));
        assertFalse(Files.exists(output.resolve("plan.json")));
    }

    @Test
    public void migrationCsvChecksumAndManifestRowCountAreChecked() throws Exception {
        Path corrupted = exportFixture();
        Files.write(corrupted.resolve("0_tag.csv"), "\n".getBytes(StandardCharsets.US_ASCII),
                StandardOpenOption.APPEND);
        reject(() -> PlanBundle.prepare(corrupted, directory()), "SHA-256 mismatch");

        Path wrongCount = exportFixture();
        JSONObject manifest = json(wrongCount.resolve("manifest.json"));
        manifest.getJSONArray("tables").getJSONObject(0).put("rowCount", 2);
        writeJson(wrongCount.resolve("manifest.json"), manifest);
        reject(() -> PlanBundle.prepare(wrongCount, directory()), "row count differs");
    }

    @Test
    public void migrationDuplicateRowsAreRejectedEvenWithUpdatedChecksumAndCount() throws Exception {
        Path export = exportFixture();
        Path file = export.resolve("0_tag.csv");
        Files.write(file, (stringCell("alice") + "\n").getBytes(StandardCharsets.US_ASCII),
                StandardOpenOption.APPEND);
        JSONObject manifest = json(export.resolve("manifest.json"));
        JSONObject table = manifest.getJSONArray("tables").getJSONObject(0);
        table.put("rowCount", 2);
        table.put("sha256", FileSupport.sha256(file));
        writeJson(export.resolve("manifest.json"), manifest);
        reject(() -> PlanBundle.prepare(export, directory()), "Duplicate Tag record");
    }

    @Test
    public void migrationManifestRejectsUnsafeFilesDuplicateTablesAndUnknownEncoding() throws Exception {
        Path unsafe = exportFixture();
        JSONObject manifest = json(unsafe.resolve("manifest.json"));
        manifest.getJSONArray("tables").getJSONObject(0).put("file", "../outside.csv");
        writeJson(unsafe.resolve("manifest.json"), manifest);
        reject(() -> PlanBundle.prepare(unsafe, directory()), "migration filename");

        Path duplicate = exportFixture();
        JSONObject dup = json(duplicate.resolve("manifest.json"));
        JSONArray tables = dup.getJSONArray("tables");
        tables.getJSONObject(1).put("name", tables.getJSONObject(0).getString("name"));
        writeJson(duplicate.resolve("manifest.json"), dup);
        reject(() -> PlanBundle.prepare(duplicate, directory()), "Duplicate migration table");

        Path encoding = exportFixture();
        JSONObject unsupported = json(encoding.resolve("manifest.json"));
        unsupported.put("valueEncoding", "plain-text");
        writeJson(encoding.resolve("manifest.json"), unsupported);
        reject(() -> PlanBundle.prepare(encoding, directory()), "Unsupported migration value encoding");
    }

    private Path basicPlan() throws Exception {
        return prepare("int64", "vid\nV:MQ==\n", "src,edge,rank,dst\n");
    }

    private Path prepare(String type, String vertices, String edges) throws Exception {
        Path input = directory();
        write(input.resolve("v.csv"), vertices);
        write(input.resolve("e.csv"), edges);
        Path output = directory();
        PlanBundle.Plan plan = PlanBundle.prepareIds(input.resolve("v.csv"), input.resolve("e.csv"), type, output);
        assertNotNull(plan.planId);
        return output;
    }

    private Path exportFixture() throws Exception {
        Path export = directory();
        String alice = stringCell("alice");
        String bob = stringCell("bob");
        write(export.resolve("0_tag.csv"), "_vid\n" + alice + "\n");
        write(export.resolve("1_tag.csv"), "_vid,p0\n" + alice + ",N\n");
        write(export.resolve("2_edge.csv"), "_src,_dst,_rank\n" + alice + "," + bob + ",V:Nw==\n");
        JSONObject manifest = new JSONObject(true);
        manifest.put("formatVersion", 1);
        manifest.put("valueEncoding", "N-or-V:base64;float=raw-f64-hex;temporal=integer-array");
        manifest.put("vidType", "FIXED_STRING(32)");
        JSONArray tables = new JSONArray();
        tables.add(table(export, "TAG", "empty_tag", "0_tag.csv", 0));
        tables.add(table(export, "TAG", "person", "1_tag.csv", 1));
        tables.add(table(export, "EDGE", "knows", "2_edge.csv", 0));
        manifest.put("tables", tables);
        writeJson(export.resolve("manifest.json"), manifest);
        return export;
    }

    private static JSONObject table(Path directory, String kind, String name, String file, int count)
            throws Exception {
        JSONObject table = new JSONObject(true);
        table.put("kind", kind);
        table.put("name", name);
        table.put("file", file);
        table.put("rowCount", 1);
        table.put("sha256", FileSupport.sha256(directory.resolve(file)));
        JSONArray columns = new JSONArray();
        for (int i = 0; i < count; i++) {
            columns.add(Collections.singletonMap("name", "p" + i));
        }
        table.put("columns", columns);
        return table;
    }

    private Path directory() throws Exception {
        return temporary.newFolder().toPath();
    }

    private static String stringCell(String text) {
        return Identifiers.cell(Value.sVal(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static JSONObject json(Path file) throws Exception {
        return JSON.parseObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    private static void writeJson(Path file, Object value) throws Exception {
        write(file, JSON.toJSONString(value));
    }

    private static void write(Path file, String text) throws Exception {
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void reject(Action action, String message) throws Exception {
        try {
            action.run();
            fail("Accepted an invalid or corrupted plan");
        } catch (Exception expected) {
            assertTrue("Unexpected rejection: " + expected, expected.getMessage().contains(message));
        }
    }

    private interface Action {
        void run() throws Exception;
    }
}
