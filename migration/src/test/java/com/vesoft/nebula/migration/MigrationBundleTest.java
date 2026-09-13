package com.vesoft.nebula.migration;

import com.alibaba.fastjson.JSON;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Corrupt or ambiguous exports must be rejected before any connection or target write. */
public class MigrationBundleTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void acceptsNullEmptyAndArbitraryBinaryString() throws Exception {
        Path dir = bundle("_vid,p0\nV:MQ==,N\nV:Mg==,V:\nV:Mw==,V:AP/AgA==\n", true, 3);
        Assert.assertEquals(3, MigrationEngine.inspect(dir).tables.get(0).rowCount);
    }

    @Test
    public void rejectsChangedCsvBytesEvenWhenStillValidCsv() throws Exception {
        Path dir = bundle("_vid,p0\nV:MQ==,V:YQ==\n", true, 1);
        Files.write(dir.resolve("0000_tag.csv"), "_vid,p0\nV:MQ==,V:Yg==\n"
                .getBytes(StandardCharsets.US_ASCII));
        rejectsBeforeConnection(dir, "SHA-256");
    }

    @Test
    public void rejectsDuplicateCompleteKey() throws Exception {
        Path dir = bundle("_vid,p0\nV:MQ==,V:YQ==\nV:MQ==,V:Yg==\n", true, 2);
        rejectsBeforeConnection(dir, "Duplicate complete key");
    }

    @Test
    public void rejectsWrongColumnCount() throws Exception {
        rejectsBeforeConnection(bundle("_vid,p0\nV:MQ==\n", true, 1), "field count");
    }

    @Test
    public void rejectsBlankCellInsteadOfNull() throws Exception {
        rejectsBeforeConnection(bundle("_vid,p0\nV:MQ==,\n", true, 1), "Cell must be");
    }

    @Test
    public void rejectsNullIdentifier() throws Exception {
        rejectsBeforeConnection(bundle("_vid,p0\nN,V:YQ==\n", true, 1), "NULL graph");
    }

    @Test
    public void rejectsNullInNotNullProperty() throws Exception {
        rejectsBeforeConnection(bundle("_vid,p0\nV:MQ==,N\n", false, 1), "NOT NULL");
    }

    @Test
    public void rejectsNonCanonicalBase64() throws Exception {
        rejectsBeforeConnection(bundle("_vid,p0\nV:MQ==,V:YQ\n", true, 1), "Non-canonical");
    }

    @Test
    public void rejectsInventoryCountMismatch() throws Exception {
        rejectsBeforeConnection(bundle("_vid,p0\nV:MQ==,V:YQ==\n", true, 2), "row count");
    }

    @Test
    public void rejectsWrongHeader() throws Exception {
        rejectsBeforeConnection(bundle("_vid,other\nV:MQ==,V:YQ==\n", true, 1), "header");
    }

    @Test
    public void supportsZeroPropertyAndEmptyTagFiles() throws Exception {
        Path dir = bundle("_vid\nV:MQ==\n", true, 1);
        SchemaManifest manifest = readManifest(dir);
        manifest.tables.get(0).columns.clear();
        manifest.tables.get(0).createStatement = "CREATE TAG `sample`()";
        saveManifest(dir, manifest);
        Assert.assertEquals(1, MigrationEngine.inspect(dir).tables.get(0).rowCount);

        Path empty = bundle("_vid,p0\n", true, 0);
        Assert.assertEquals(0, MigrationEngine.inspect(empty).tables.get(0).rowCount);
    }

    @Test
    public void rejectsGeographyBeforeConnection() throws Exception {
        Path dir = bundle("_vid,p0\n", true, 0);
        SchemaManifest manifest = readManifest(dir);
        manifest.tables.get(0).columns.get(0).type = "GEOGRAPHY";
        saveManifest(dir, manifest);
        rejectsBeforeConnection(dir, "Unsupported schema type");
    }

    @Test
    public void rejectsNaNPayloadThatThriftWouldCanonicalize() throws Exception {
        Path dir = floatingBundle("DOUBLE", "fff8000000000001");
        rejectsBeforeConnection(dir, "Non-canonical NaN");
    }

    @Test
    public void rejectsFiniteFloatPayloadThatWouldLosePrecision() throws Exception {
        // This is double 0.1, not the double obtained by promoting float 0.1f.
        rejectsBeforeConnection(floatingBundle("FLOAT", "3fb999999999999a"),
                "not an exact float32");
    }

    @Test
    public void rejectsNonFiniteFloatBeforeConnection() throws Exception {
        rejectsBeforeConnection(floatingBundle("FLOAT", "7ff0000000000000"),
                "Non-finite FLOAT");
    }

    @Test
    public void acceptsPromotedFloatAndNegativeZero() throws Exception {
        Assert.assertEquals(1, MigrationEngine.inspect(
                floatingBundle("FLOAT", "3fb99999a0000000")).tables.get(0).rowCount);
        Assert.assertEquals(1, MigrationEngine.inspect(
                floatingBundle("FLOAT", "8000000000000000")).tables.get(0).rowCount);
    }

    @Test
    public void serializesVidAsBytePreservingOctalLiterals() {
        byte[] bytes = new byte[] {'"', '\\', '\r', '\n', 'A', '1', (byte) 255, (byte) 128};
        Assert.assertEquals("\"\\042\\134\\015\\012\\101\\061\\377\\200\"",
                MigrationEngine.vidLiteral(com.vesoft.nebula.Value.sVal(bytes)));
        Assert.assertEquals("toInteger(\"-9223372036854775808\")",
                MigrationEngine.vidLiteral(com.vesoft.nebula.Value.iVal(Long.MIN_VALUE)));
        Assert.assertEquals("9223372036854775807",
                MigrationEngine.rankLiteral(com.vesoft.nebula.Value.iVal(Long.MAX_VALUE)));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsNulVidBeforeLiteralConstruction() {
        MigrationEngine.vidLiteral(com.vesoft.nebula.Value.sVal(new byte[] {'u', 0, 'a'}));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsUnrepresentableMinimumRank() {
        MigrationEngine.rankLiteral(com.vesoft.nebula.Value.iVal(Long.MIN_VALUE));
    }

    @Test
    public void rejectsMinimumRankBundleBeforeConnecting() throws Exception {
        Path dir = bundle("_src,_dst,_rank,p0\nV:MQ==,V:Mg==,"
                + "V:LTkyMjMzNzIwMzY4NTQ3NzU4MDg=,V:YQ==\n", true, 1);
        SchemaManifest manifest = readManifest(dir);
        SchemaManifest.Table table = manifest.tables.get(0);
        table.kind = "EDGE";
        table.createStatement = "CREATE EDGE `sample`(`value` STRING)";
        saveManifest(dir, manifest);
        rejectsBeforeConnection(dir, "rank Long.MIN_VALUE");
    }

    private Path floatingBundle(String type, String bits) throws Exception {
        String encoded = java.util.Base64.getEncoder().encodeToString(
                bits.getBytes(StandardCharsets.US_ASCII));
        Path dir = bundle("_vid,p0\nV:MQ==,V:" + encoded + "\n", true, 1);
        SchemaManifest manifest = readManifest(dir);
        manifest.tables.get(0).columns.get(0).type = type;
        manifest.tables.get(0).createStatement = "CREATE TAG `sample`(`value` " + type + ")";
        saveManifest(dir, manifest);
        return dir;
    }

    private Path bundle(String csv, boolean nullable, long count) throws Exception {
        Path dir = temporary.newFolder().toPath();
        SchemaManifest manifest = new SchemaManifest();
        manifest.sourceSpace = "source";
        manifest.vidType = "FIXED_STRING(32)";
        manifest.partitionNum = 1;
        manifest.replicaFactor = 1;
        manifest.charset = "utf8";
        manifest.collation = "utf8_bin";
        SchemaManifest.Table table = new SchemaManifest.Table();
        table.kind = "TAG";
        table.name = "sample";
        table.file = "0000_tag.csv";
        table.createStatement = "CREATE TAG `sample`(`value` string)";
        table.rowCount = count;
        SchemaManifest.Column column = new SchemaManifest.Column();
        column.name = "value";
        column.type = "STRING";
        column.nullable = nullable;
        table.columns.add(column);
        manifest.tables.add(table);
        byte[] bytes = csv.getBytes(StandardCharsets.US_ASCII);
        Files.write(dir.resolve(table.file), bytes);
        StringBuilder hash = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        table.sha256 = hash.toString();
        saveManifest(dir, manifest);
        return dir;
    }

    private SchemaManifest readManifest(Path dir) throws Exception {
        return JSON.parseObject(new String(Files.readAllBytes(dir.resolve("manifest.json")),
                StandardCharsets.UTF_8), SchemaManifest.class);
    }

    private void saveManifest(Path dir, SchemaManifest manifest) throws Exception {
        Files.write(dir.resolve("manifest.json"), JSON.toJSONBytes(manifest));
    }

    private void rejectsBeforeConnection(Path dir, String expected) throws Exception {
        ConnectionConfig unreachable = new ConnectionConfig("127.0.0.1", 1, 1, "test", "test");
        try (MigrationEngine engine = new MigrationEngine(unreachable, unreachable, 1)) {
            try {
                engine.importSpace(dir, "target");
                Assert.fail("Expected invalid bundle rejection");
            } catch (Exception failure) {
                StringBuilder chain = new StringBuilder();
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    chain.append(cause.getMessage()).append('\n');
                }
                Assert.assertTrue(chain.toString(), chain.toString().contains(expected));
            }
        }
    }
}
