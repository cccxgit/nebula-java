package com.vesoft.nebula.migration;

import com.alibaba.fastjson.JSON;
import com.facebook.thrift.TDeserializer;
import com.facebook.thrift.TSerializer;
import com.facebook.thrift.protocol.TBinaryProtocol;
import com.facebook.thrift.protocol.TType;
import com.facebook.thrift.transport.TMemoryBuffer;
import com.vesoft.nebula.Coordinate;
import com.vesoft.nebula.Geography;
import com.vesoft.nebula.LineString;
import com.vesoft.nebula.Point;
import com.vesoft.nebula.Polygon;
import com.vesoft.nebula.PropertyType;
import com.vesoft.nebula.Value;
import com.vesoft.nebula.meta.ColumnTypeDef;
import com.vesoft.nebula.meta.ColumnDef;
import com.vesoft.nebula.meta.CreateTagReq;
import com.vesoft.nebula.meta.CreateEdgeReq;
import com.vesoft.nebula.meta.GeoShape;
import com.vesoft.nebula.meta.Schema;
import com.vesoft.nebula.meta.SchemaProp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
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
    public void preservesMetadataGeographyShapeConstraints() {
        Assert.assertEquals("GEOGRAPHY", MigrationEngine.schemaType(
                new ColumnTypeDef(PropertyType.GEOGRAPHY)));
        Assert.assertEquals("GEOGRAPHY", MigrationEngine.schemaType(
                new ColumnTypeDef(PropertyType.GEOGRAPHY).setGeo_shape(GeoShape.ANY)));
        for (GeoShape shape : Arrays.asList(GeoShape.POINT, GeoShape.LINESTRING, GeoShape.POLYGON)) {
            Assert.assertEquals("GEOGRAPHY(" + shape.name() + ")", MigrationEngine.schemaType(
                    new ColumnTypeDef(PropertyType.GEOGRAPHY).setGeo_shape(shape)));
        }
    }

    @Test
    public void acceptsAllGeographyShapesInUnrestrictedAndConstrainedBundles() throws Exception {
        Value[] values = geographyValues();
        String[] types = {"GEOGRAPHY(POINT)", "GEOGRAPHY(LINESTRING)", "GEOGRAPHY(POLYGON)"};
        for (int i = 0; i < values.length; i++) {
            for (String type : Arrays.asList("GEOGRAPHY", types[i])) {
                String cell = ValueCodec.encode(values[i], type);
                Path dir = geographyBundle("_vid,p0\nV:MQ==," + cell + "\n", type, 1);
                SchemaManifest.Table table = MigrationEngine.inspect(dir).tables.get(0);
                Assert.assertEquals(type, table.columns.get(0).type);
                Assert.assertEquals(1, table.rowCount);
            }
        }
    }

    @Test
    public void acceptsNullableAndEmptyGeographySchemas() throws Exception {
        for (String type : Arrays.asList("GEOGRAPHY", "GEOGRAPHY(POINT)",
                "GEOGRAPHY(LINESTRING)", "GEOGRAPHY(POLYGON)")) {
            Assert.assertEquals(type, MigrationEngine.inspect(geographyBundle(
                    "_vid,p0\nV:MQ==,N\n", type, 1)).tables.get(0).columns.get(0).type);
            Assert.assertEquals(0, MigrationEngine.inspect(geographyBundle(
                    "_vid,p0\n", type, 0)).tables.get(0).rowCount);
        }
    }

    @Test
    public void acceptsGeographyEdgeWithCompleteIdentity() throws Exception {
        String cell = ValueCodec.encode(geographyValues()[2], "GEOGRAPHY(POLYGON)");
        Path dir = geographyBundle("_src,_dst,_rank,p0\nV:MQ==,V:Mg==,V:LTE=,"
                + cell + "\n", "GEOGRAPHY(POLYGON)", 1);
        SchemaManifest manifest = readManifest(dir);
        manifest.tables.get(0).kind = "EDGE";
        manifest.tables.get(0).createStatement = "CREATE EDGE `sample`(`value` GEOGRAPHY(POLYGON))";
        saveManifest(dir, manifest);
        Assert.assertEquals(1, MigrationEngine.inspect(dir).tables.get(0).rowCount);
    }

    @Test
    public void rejectsGeographyShapeMismatchBeforeConnecting() throws Exception {
        String point = ValueCodec.encode(geographyValues()[0], "GEOGRAPHY");
        rejectsBeforeConnection(geographyBundle("_vid,p0\nV:MQ==," + point + "\n",
                "GEOGRAPHY(LINESTRING)", 1), "shape");
    }

    @Test
    public void rejectsUnknownGeographyConstraintBeforeConnecting() throws Exception {
        rejectsBeforeConnection(geographyBundle("_vid,p0\n", "GEOGRAPHY(MULTIPOINT)", 0),
                "Unsupported schema type");
    }

    @Test
    public void rejectsNonFiniteGeographyCoordinatesBeforeConnecting() throws Exception {
        for (String bits : Arrays.asList("7ff8000000000001", "7ff0000000000000", "fff0000000000000")) {
            String coordinate = "[\"" + bits + "\",\"0000000000000000\"]";
            for (String payload : Arrays.asList("[\"point\"," + coordinate + "]",
                    "[\"linestring\",[" + coordinate + "]]",
                    "[\"polygon\",[[" + coordinate + "]]]")) {
                String cell = "V:" + java.util.Base64.getEncoder().encodeToString(
                        payload.getBytes(StandardCharsets.US_ASCII));
                rejectsBeforeConnection(geographyBundle("_vid,p0\nV:MQ==," + cell + "\n",
                        "GEOGRAPHY", 1), "finite");
            }
        }
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
    public void rejectsInfiniteFloatBeforeConnection() throws Exception {
        for (String bits : Arrays.asList("7ff0000000000000", "fff0000000000000")) {
            rejectsBeforeConnection(floatingBundle("FLOAT", bits), "FLOAT infinity");
        }
    }

    @Test
    public void acceptsCanonicalFloatNaNAndAllReplayableDoubleNonFiniteValues() throws Exception {
        Assert.assertEquals(1, MigrationEngine.inspect(
                floatingBundle("FLOAT", "7ff8000000000000")).tables.get(0).rowCount);
        for (String bits : Arrays.asList("7ff8000000000000", "7ff0000000000000",
                "fff0000000000000")) {
            Assert.assertEquals(1, MigrationEngine.inspect(
                    floatingBundle("DOUBLE", bits)).tables.get(0).rowCount);
        }
        rejectsBeforeConnection(floatingBundle("FLOAT", "7ff8000000000001"),
                "Non-canonical NaN");
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

    @Test
    public void prefersStandardPolygonWireWhenServerPreservesIt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Assert.assertFalse(MigrationEngine.detectLegacyPolygonWire(parameter -> {
            calls.incrementAndGet();
            Assert.assertEquals(TType.LIST, polygonOuterElementType(parameter));
            return polygonWireRoundTrip(parameter);
        }));
        Assert.assertEquals(1, calls.get());
    }

    @Test
    public void detectsOlderServerThatSkipsCorrectlyTypedPolygonRings() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Assert.assertTrue(MigrationEngine.detectLegacyPolygonWire(parameter -> {
            calls.incrementAndGet();
            // NebulaGraph 3.6's Polygon reader expects STRUCT at the outer list header.
            // An unexpected LIST header is skipped, yielding an empty polygon.
            if (polygonOuterElementType(parameter) == TType.LIST) {
                return Value.ggVal(Geography.pgVal(new Polygon(Collections.emptyList())));
            }
            Assert.assertEquals(TType.STRUCT, polygonOuterElementType(parameter));
            Value actual = polygonWireRoundTrip(parameter);
            Assert.assertEquals(2, actual.getGgVal().getPgVal().coordListList.size());
            Assert.assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(
                    actual.getGgVal().getPgVal().coordListList.get(0).get(0).x));
            return actual;
        }));
        Assert.assertEquals(2, calls.get());
    }

    @Test
    public void refusesBothPolygonFormatsWhenBothLoseRings() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try {
            MigrationEngine.detectLegacyPolygonWire(parameter -> {
                calls.incrementAndGet();
                return Value.ggVal(Geography.pgVal(new Polygon(Collections.emptyList())));
            });
            Assert.fail("A server that loses rings must not receive imports");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("standard or legacy"));
            Assert.assertEquals(1, expected.getSuppressed().length);
        }
        Assert.assertEquals(2, calls.get());
    }

    @Test
    public void rejectsPolygonProbeThatChangesOneCoordinateBit() throws Exception {
        try {
            MigrationEngine.detectLegacyPolygonWire(parameter -> {
                Value actual = polygonWireRoundTrip(parameter);
                // Preserve all rings and numeric equality but lose the sign of zero.
                actual.getGgVal().getPgVal().coordListList.get(0).get(0).x = 0.0d;
                return actual;
            });
            Assert.fail("Negative zero must survive the probe exactly");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getCause().getMessage().contains("coordinate bits"));
        }
    }

    @Test
    public void rejectsPolygonProbeThatReordersRings() throws Exception {
        try {
            MigrationEngine.detectLegacyPolygonWire(parameter -> {
                Value actual = polygonWireRoundTrip(parameter);
                Collections.reverse(actual.getGgVal().getPgVal().coordListList);
                return actual;
            });
            Assert.fail("Ring order must survive the probe exactly");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getCause().getMessage().contains("rings"));
        }
    }

    @Test
    public void refusesPolygonImportWhenBothProbeQueriesFail() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try {
            MigrationEngine.detectLegacyPolygonWire(parameter -> {
                calls.incrementAndGet();
                throw new java.io.IOException("RPC rejected polygon");
            });
            Assert.fail("Failed capability probes cannot silently enable imports");
        } catch (IllegalStateException expected) {
            Assert.assertEquals("RPC rejected polygon", expected.getCause().getMessage());
            Assert.assertEquals("RPC rejected polygon", expected.getSuppressed()[0].getMessage());
        }
        Assert.assertEquals(2, calls.get());
    }

    @Test
    public void legacyPolygonHeaderPreservesCoordinatesWithoutChangingOriginal() throws Exception {
        Value original = geographyValues()[2];
        String encoded = ValueCodec.encode(original, "GEOGRAPHY(POLYGON)");
        Assert.assertSame(original, MigrationEngine.polygonParameter(original, false));
        Value legacy = MigrationEngine.polygonParameter(original, true);
        Assert.assertNotSame(original, legacy);
        Assert.assertEquals(TType.LIST, polygonOuterElementType(original));
        Assert.assertEquals(TType.STRUCT, polygonOuterElementType(legacy));
        Assert.assertEquals(encoded, ValueCodec.encode(polygonWireRoundTrip(legacy),
                "GEOGRAPHY(POLYGON)"));
        Assert.assertEquals(encoded, ValueCodec.encode(original, "GEOGRAPHY(POLYGON)"));
    }

    @Test
    public void polygonWorkaroundLeavesOtherNativeValuesUnchanged() {
        for (Value value : Arrays.asList(geographyValues()[0], geographyValues()[1],
                Value.sVal(new byte[] {0, (byte) 255}), Value.iVal(Long.MIN_VALUE),
                Value.fVal(-0.0d), Value.nVal(com.vesoft.nebula.NullType.__NULL__))) {
            Assert.assertSame(value, MigrationEngine.polygonParameter(value, true));
        }
    }

    @Test
    public void nativeSchemaPreservesDefaultExpressionBytesAndAllSchemaProperties() throws Exception {
        Schema source = defaultSchema();
        Path dir = nativeSchemaBundle(source);
        SchemaManifest.Table table = MigrationEngine.inspect(dir).tables.get(0);
        Schema restored = MigrationEngine.nativeSchema(table);
        Assert.assertArrayEquals(source.columns.get(0).default_value, restored.columns.get(0).default_value);
        Assert.assertArrayEquals(source.columns.get(1).default_value, restored.columns.get(1).default_value);
        Assert.assertArrayEquals(source.columns.get(0).comment, restored.columns.get(0).comment);
        Assert.assertFalse(restored.columns.get(0).nullable);
        Assert.assertArrayEquals(source.schema_prop.comment, restored.schema_prop.comment);
        Assert.assertArrayEquals(source.schema_prop.ttl_col, restored.schema_prop.ttl_col);
        Assert.assertTrue(restored.schema_prop.isSetTtl_duration());
        Assert.assertEquals(0, restored.schema_prop.ttl_duration);

        // Exercise both actual RPC request types: the binary expressions do not pass through nGQL.
        CreateTagReq tag = new CreateTagReq(7, new byte[] {'t'}, restored, false);
        CreateTagReq receivedTag = new CreateTagReq();
        new TDeserializer(new TBinaryProtocol.Factory()).deserialize(receivedTag,
                new TSerializer(new TBinaryProtocol.Factory()).serialize(tag));
        Assert.assertEquals(table.nativeSchemaBase64, MigrationEngine.encodeNativeSchema(receivedTag.schema));
        CreateEdgeReq edge = new CreateEdgeReq(7, new byte[] {'e'}, restored, false);
        CreateEdgeReq receivedEdge = new CreateEdgeReq();
        new TDeserializer(new TBinaryProtocol.Factory()).deserialize(receivedEdge,
                new TSerializer(new TBinaryProtocol.Factory()).serialize(edge));
        Assert.assertEquals(table.nativeSchemaBase64, MigrationEngine.encodeNativeSchema(receivedEdge.schema));
        Assert.assertFalse(receivedTag.if_not_exists);
        Assert.assertFalse(receivedEdge.if_not_exists);
    }

    @Test
    public void nativeSchemaSupportsDefaultsEvenWhenShowCreateIsNotExecutable() throws Exception {
        Path dir = nativeSchemaBundle(defaultSchema());
        SchemaManifest manifest = readManifest(dir);
        manifest.tables.get(0).createStatement = "CREATE TAG `sample` (`text` STRING NOT NULL DEFAULT "
                + "\"default,'\"\\\r\n😀\", `date` DATE NOT NULL DEFAULT 2024-02-29)";
        saveManifest(dir, manifest);
        SchemaManifest.Table inspected = MigrationEngine.inspect(dir).tables.get(0);
        Assert.assertArrayEquals(java.util.Base64.getDecoder().decode("AFgRZGVmYXVsdCwnIlwNCvCfmIAA"),
                MigrationEngine.nativeSchema(inspected).columns.get(0).default_value);
        Assert.assertArrayEquals(java.util.Base64.getDecoder().decode("AGwU0B8TAhMdAAA="),
                MigrationEngine.nativeSchema(inspected).columns.get(1).default_value);
    }

    @Test
    public void nativeSchemaPreservesGeographyConstraintsAndFixedStringCapacity() throws Exception {
        java.util.List<ColumnDef> columns = new java.util.ArrayList<>();
        int number = 0;
        for (GeoShape shape : Arrays.asList(GeoShape.ANY, GeoShape.POINT, GeoShape.LINESTRING, GeoShape.POLYGON)) {
            columns.add(new ColumnDef(("g" + number++).getBytes(StandardCharsets.UTF_8),
                    new ColumnTypeDef(PropertyType.GEOGRAPHY).setGeo_shape(shape), null, true, null));
        }
        columns.add(new ColumnDef(new byte[] {'s'},
                new ColumnTypeDef(PropertyType.FIXED_STRING).setType_length((short) 128), null, true, null));
        Schema original = new Schema(columns, new SchemaProp().setTtl_duration(0));
        SchemaManifest.Table table = MigrationEngine.inspect(nativeSchemaBundle(original)).tables.get(0);
        Schema actual = MigrationEngine.nativeSchema(table);
        Assert.assertEquals(MigrationEngine.encodeNativeSchema(original), MigrationEngine.encodeNativeSchema(actual));
        Assert.assertEquals(GeoShape.POLYGON, actual.columns.get(3).type.geo_shape);
        Assert.assertEquals(128, actual.columns.get(4).type.type_length);
    }

    @Test
    public void rejectsNativeSchemaThatDisagreesWithReadableColumnInventory() throws Exception {
        for (String mismatch : Arrays.asList("default", "nullable", "type", "comment", "name")) {
            Path dir = nativeSchemaBundle(defaultSchema());
            SchemaManifest manifest = readManifest(dir);
            SchemaManifest.Column column = manifest.tables.get(0).columns.get(0);
            switch (mismatch) {
                case "default": column.defaultExpressionBase64 = "AA=="; break;
                case "nullable": column.nullable = true; break;
                case "type": column.type = "INT64"; break;
                case "comment": column.commentBase64 = "AA=="; break;
                default: column.name = "another";
            }
            saveManifest(dir, manifest);
            rejectsBeforeConnection(dir, "Native Schema columns/default/comment differ");
        }
    }

    @Test
    public void rejectsTruncatedAndTrailingNativeSchemaPayloadsBeforeConnecting() throws Exception {
        for (boolean truncated : Arrays.asList(true, false)) {
            Path dir = nativeSchemaBundle(defaultSchema());
            SchemaManifest manifest = readManifest(dir);
            SchemaManifest.Table table = manifest.tables.get(0);
            byte[] bytes = java.util.Base64.getDecoder().decode(table.nativeSchemaBase64);
            table.nativeSchemaBase64 = java.util.Base64.getEncoder().encodeToString(
                    Arrays.copyOf(bytes, truncated ? bytes.length / 2 : bytes.length + 1));
            saveManifest(dir, manifest);
            rejectsBeforeConnection(dir, truncated ? "Invalid native Schema" : "Non-canonical native Schema");
        }
    }

    @Test
    public void rejectsActiveTtlInNativeSchemaBeforeConnecting() throws Exception {
        Schema schema = defaultSchema();
        schema.schema_prop.setTtl_duration(60);
        rejectsBeforeConnection(nativeSchemaBundle(schema), "Enabled TTL in native Schema");
    }

    @Test
    public void legacyDefaultBundleRequiresNewNativeSchemaExportBeforeConnecting() throws Exception {
        Path dir = nativeSchemaBundle(defaultSchema());
        SchemaManifest manifest = readManifest(dir);
        manifest.tables.get(0).nativeSchemaBase64 = null;
        saveManifest(dir, manifest);
        rejectsBeforeConnection(dir, "Legacy bundle with DEFAULT requires native Schema");
    }

    @Test
    public void nativeSchemaSupportsEmptyTagAndDetectsChangedTableComment() throws Exception {
        Schema schema = new Schema(Collections.emptyList(),
                new SchemaProp().setTtl_duration(0).setComment(new byte[] {'"', '\r', '\n', 0, (byte) 255}));
        Path dir = nativeSchemaBundle(schema);
        SchemaManifest original = MigrationEngine.inspect(dir);
        Assert.assertEquals(0, MigrationEngine.nativeSchema(original.tables.get(0)).columns.size());
        SchemaManifest changed = readManifest(dir);
        schema.schema_prop.comment = new byte[] {'x'};
        changed.tables.get(0).nativeSchemaBase64 = MigrationEngine.encodeNativeSchema(schema);
        try {
            // All display/column metadata is identical: complete SchemaProp must still be compared.
            MigrationEngine.assertSchemasEqual(original, changed);
            Assert.fail("A changed table comment must not compare as the same native Schema");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("Native Schema/default/comment differs"));
        }
    }

    @Test
    public void spaceCommentPreservesAllBytesIncludingNulWithoutVidRestrictions() {
        byte[] bytes = new byte[256];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        String literal = MigrationEngine.byteStringLiteral(bytes);
        Assert.assertEquals(1026, literal.length());
        Assert.assertEquals('"', literal.charAt(0));
        Assert.assertEquals('"', literal.charAt(literal.length() - 1));
        for (int i = 0; i < bytes.length; i++) {
            Assert.assertEquals('\\', literal.charAt(1 + i * 4));
            Assert.assertEquals(i, Integer.parseInt(literal.substring(2 + i * 4, 5 + i * 4), 8));
        }
        Assert.assertEquals(" COMMENT = " + literal, MigrationEngine.spaceCommentClause(
                java.util.Base64.getEncoder().encodeToString(bytes)));
        Assert.assertEquals("\"\\000\\042\\134\\015\\012\\377\"",
                MigrationEngine.byteStringLiteral(new byte[] {0, '"', '\\', '\r', '\n', (byte) 255}));
    }

    @Test
    public void spaceCommentDistinguishesAbsentEmptyAndNul() throws Exception {
        Assert.assertEquals("", MigrationEngine.spaceCommentClause(null));
        Assert.assertEquals(" COMMENT = \"\"", MigrationEngine.spaceCommentClause(""));
        Assert.assertEquals(" COMMENT = \"\\000\"", MigrationEngine.spaceCommentClause("AA=="));
        for (String comment : Arrays.asList("", "AA==", "/w==")) {
            Path dir = bundle("_vid,p0\n", true, 0);
            SchemaManifest expected = readManifest(dir);
            SchemaManifest actual = readManifest(dir);
            actual.spaceCommentBase64 = comment;
            try {
                MigrationEngine.assertSchemasEqual(expected, actual);
                Assert.fail("Absent COMMENT must differ from present COMMENT");
            } catch (IllegalStateException failure) {
                Assert.assertTrue(failure.getMessage().contains("Space COMMENT bytes or presence differs"));
            }
        }
    }

    @Test
    public void rejectsInvalidSpaceCommentBase64BeforeConnecting() throws Exception {
        for (String malformed : Arrays.asList("YQ", "YR==", "?")) {
            Path dir = bundle("_vid,p0\n", true, 0);
            SchemaManifest manifest = readManifest(dir);
            manifest.spaceCommentBase64 = malformed;
            saveManifest(dir, manifest);
            rejectsBeforeConnection(dir, "space COMMENT Base64");
        }
    }

    @Test
    public void legacySpaceCommentDisplayMustBeReexportedBeforeConnecting() throws Exception {
        Path dir = bundle("_vid,p0\n", true, 0);
        SchemaManifest manifest = readManifest(dir);
        manifest.showCreateSpace = "CREATE SPACE `source` (...) comment = 'truncated'";
        saveManifest(dir, manifest);
        rejectsBeforeConnection(dir, "Legacy space COMMENT requires raw metadata");
    }

    @Test
    public void nativeSpaceCommentBundleKeepsBinaryMetadata() throws Exception {
        Path dir = nativeSchemaBundle(new Schema(Collections.emptyList(), new SchemaProp()));
        SchemaManifest manifest = readManifest(dir);
        manifest.vidType = "INT64";
        manifest.spaceCommentBase64 = "ACJcDQqA/w==";
        manifest.showCreateSpace = "CREATE SPACE `source` (...) comment = ''";
        saveManifest(dir, manifest);
        Assert.assertEquals("ACJcDQqA/w==", MigrationEngine.inspect(dir).spaceCommentBase64);
        Assert.assertEquals(" COMMENT = \"\\000\\042\\134\\015\\012\\200\\377\"",
                MigrationEngine.spaceCommentClause(manifest.spaceCommentBase64));
    }

    private static Schema defaultSchema() {
        byte[] comment = new byte[] {'"', '\\', '\r', '\n', 0, (byte) 255};
        ColumnDef text = new ColumnDef("text".getBytes(StandardCharsets.UTF_8),
                new ColumnTypeDef(PropertyType.STRING),
                java.util.Base64.getDecoder().decode("AFgRZGVmYXVsdCwnIlwNCvCfmIAA"), false, comment);
        ColumnDef date = new ColumnDef("date".getBytes(StandardCharsets.UTF_8),
                new ColumnTypeDef(PropertyType.DATE),
                java.util.Base64.getDecoder().decode("AGwU0B8TAhMdAAA="), false, null);
        SchemaProp properties = new SchemaProp().setTtl_duration(0)
                .setTtl_col("date".getBytes(StandardCharsets.UTF_8)).setComment(comment);
        return new Schema(Arrays.asList(text, date), properties);
    }

    private Path nativeSchemaBundle(Schema schema) throws Exception {
        StringBuilder header = new StringBuilder("_vid");
        for (int i = 0; i < schema.columns.size(); i++) {
            header.append(",p").append(i);
        }
        Path dir = bundle(header.append('\n').toString(), true, 0);
        SchemaManifest manifest = readManifest(dir);
        SchemaManifest.Table table = manifest.tables.get(0);
        table.nativeSchemaBase64 = MigrationEngine.encodeNativeSchema(schema);
        table.columns.clear();
        for (ColumnDef column : schema.columns) {
            SchemaManifest.Column property = new SchemaManifest.Column();
            property.name = new String(column.name, StandardCharsets.UTF_8);
            property.type = MigrationEngine.schemaType(column.type);
            property.nullable = column.nullable;
            property.defaultExpressionBase64 = column.default_value == null ? null
                    : java.util.Base64.getEncoder().encodeToString(column.default_value);
            property.commentBase64 = column.comment == null ? null
                    : java.util.Base64.getEncoder().encodeToString(column.comment);
            table.columns.add(property);
        }
        saveManifest(dir, manifest);
        return dir;
    }

    private static byte polygonOuterElementType(Value value) throws Exception {
        TMemoryBuffer buffer = new TMemoryBuffer(1024);
        value.getGgVal().getPgVal().write(new TBinaryProtocol(buffer));
        TBinaryProtocol reader = new TBinaryProtocol(buffer);
        reader.readStructBegin();
        Assert.assertEquals(TType.LIST, reader.readFieldBegin().type);
        return reader.readListBegin().elemType;
    }

    private static Value polygonWireRoundTrip(Value value) throws Exception {
        TMemoryBuffer buffer = new TMemoryBuffer(1024);
        value.write(new TBinaryProtocol(buffer));
        Value decoded = new Value();
        decoded.read(new TBinaryProtocol(buffer));
        return decoded;
    }

    private static Value[] geographyValues() {
        Coordinate first = new Coordinate(-0.0d, 0.1d);
        Coordinate second = new Coordinate(1.0d, 1.0d);
        Coordinate third = new Coordinate(1.0d, 0.0d);
        return new Value[] {
            Value.ggVal(Geography.ptVal(new Point(first))),
            Value.ggVal(Geography.lsVal(new LineString(Arrays.asList(first, second)))),
            Value.ggVal(Geography.pgVal(new Polygon(Collections.singletonList(
                    Arrays.asList(first, second, third, first)))))
        };
    }

    private Path geographyBundle(String csv, String type, long count) throws Exception {
        Path dir = bundle(csv, true, count);
        SchemaManifest manifest = readManifest(dir);
        manifest.tables.get(0).columns.get(0).type = type;
        manifest.tables.get(0).createStatement = "CREATE TAG `sample`(`value` " + type + ")";
        saveManifest(dir, manifest);
        return dir;
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
