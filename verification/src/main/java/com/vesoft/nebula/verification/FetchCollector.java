package com.vesoft.nebula.verification;

import com.alibaba.fastjson.JSON;
import com.vesoft.nebula.Edge;
import com.vesoft.nebula.NullType;
import com.vesoft.nebula.Row;
import com.vesoft.nebula.Tag;
import com.vesoft.nebula.Value;
import com.vesoft.nebula.Vertex;
import com.vesoft.nebula.client.graph.NebulaPoolConfig;
import com.vesoft.nebula.client.graph.data.HostAddress;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.net.NebulaPool;
import com.vesoft.nebula.client.graph.net.Session;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Read-only, graphd-only capture of explicitly listed vertices and edges. */
public final class FetchCollector {
    private FetchCollector() {
    }

    public static SnapshotManifest capture(PlanBundle.Plan plan, ConnectionSettings config,
                                           String space, Path out) throws Exception {
        require(plan != null && plan.vertices != null && plan.edges != null, "Missing plan");
        Identifiers.quote(space);
        require(config != null && config.timeoutMs > 0, "A positive graph timeout is required");
        SnapshotManifest manifest = new SnapshotManifest();
        manifest.planId = plan.planId;
        manifest.planDigest = plan.digest;
        manifest.requestedCount = plan.vertices.size() + (long) plan.edges.size();
        require(manifest.requestedCount > 0 && plan.keys().size() == manifest.requestedCount,
                "Plan must contain unique, nonempty requested keys");
        manifest.requestedKeysSha256 = SnapshotFiles.keyDigest(plan.keys());
        manifest.vidType = normalizeType(plan.vidType);
        manifest.space = space;
        manifest.capturedAt = Instant.now().toString();
        manifest.status = "INCOMPLETE";

        try (Graph graph = new Graph(config)) {
            require(manifest.vidType.equals(spaceVidType(graph, space)),
                    "Space VID type differs from plan");
            graph.execute("USE " + Identifiers.quote(space));
            if (manifest.vidType.startsWith("FIXED_STRING(")) {
                verifyOctalBytes(graph);
            }
            Baseline baseline = readBaseline(graph, plan);
            try (BufferedWriter writer = SnapshotFiles.begin(out)) {
                for (String vid : plan.vertices) {
                    CaptureRecord record = vertexRecord(graph, baseline, manifest, plan, vid);
                    SnapshotFiles.writeRecord(writer, record);
                    count(manifest, record);
                }
                for (PlanBundle.EdgeKey edge : plan.edges) {
                    CaptureRecord record = edgeRecord(graph, baseline, manifest, plan, edge);
                    SnapshotFiles.writeRecord(writer, record);
                    count(manifest, record);
                }
            }
            // Only schema definitions used by listed objects are part of the captured snapshot.
            for (Map.Entry<String, String> entry : manifest.schemas.entrySet()) {
                String key = entry.getKey();
                int separator = key.indexOf('/');
                String kind = key.substring(0, separator);
                String name = Identifiers.decodeName(key.substring(separator + 1));
                try {
                    SchemaDefinition current = describe(graph, kind, name);
                    if (!entry.getValue().equals(current.canonical())) {
                        manifest.errors.add("Schema changed during capture: " + key);
                    }
                } catch (Exception failure) {
                    manifest.errors.add("Unable to recheck schema " + key + ": " + error(failure));
                }
            }
            try {
                if (!manifest.vidType.equals(spaceVidType(graph, space))) {
                    manifest.errors.add("Space VID type changed during capture");
                }
            } catch (Exception failure) {
                manifest.errors.add("Unable to recheck space: " + error(failure));
            }
        }
        if (manifest.missingCount == 0 && manifest.errorCount == 0 && manifest.errors.isEmpty()) {
            manifest.status = "COMPLETE";
        }
        SnapshotFiles.finish(out, manifest);
        return manifest;
    }

    private static CaptureRecord vertexRecord(Graph graph, Baseline baseline,
                                                SnapshotManifest manifest, PlanBundle.Plan plan,
                                                String vidCell) {
        CaptureRecord record = new CaptureRecord();
        record.kind = "VERTEX";
        record.key = PlanBundle.vertexKey(vidCell);
        try {
            Value expectedVid = Identifiers.decodeVid(vidCell, plan.vidType);
            ResultSet result = graph.execute("FETCH PROP ON * "
                    + Identifiers.vidLiteral(expectedVid) + " YIELD vertex AS v");
            Value value = objectResult(result, Value.VVAL);
            if (value == null) {
                record.status = "MISSING";
                record.error = "FETCH returned no vertex record";
                return record;
            }
            Vertex vertex = value.getVVal();
            require(sameIdentifier(expectedVid, vertex.vid),
                    "Returned vertex VID differs from requested VID");
            require(vertex.tags != null, "Missing native Tag membership list");
            if (vertex.tags.isEmpty()) {
                record.status = "MISSING";
                record.error = "FETCH returned no actual Tag membership for the requested VID";
                return record;
            }
            validateVertex(vertex, expectedVid, baseline.schemas, baseline.failures);
            for (Tag tag : vertex.tags) {
                addSchema(manifest, baseline, "TAG", Identifiers.nameCell(tag.name));
            }
            record.fields = NativeValueCodec.fields(value);
            record.status = "OK";
        } catch (Exception failure) {
            record.status = "ERROR";
            record.error = error(failure);
        }
        return record;
    }

    private static CaptureRecord edgeRecord(Graph graph, Baseline baseline,
                                              SnapshotManifest manifest, PlanBundle.Plan plan,
                                              PlanBundle.EdgeKey wanted) {
        CaptureRecord record = new CaptureRecord();
        record.kind = "EDGE";
        record.key = PlanBundle.edgeKey(wanted);
        try {
            String edgeName = Identifiers.decodeName(wanted.edge);
            Value src = Identifiers.decodeVid(wanted.src, plan.vidType);
            Value dst = Identifiers.decodeVid(wanted.dst, plan.vidType);
            Value rank = Identifiers.decodeRank(wanted.rank);
            SchemaDefinition schema = schema(baseline.schemas, baseline.failures,
                    schemaKey("EDGE", wanted.edge));
            addSchema(manifest, baseline, "EDGE", wanted.edge);
            ResultSet result = graph.execute("FETCH PROP ON " + Identifiers.quote(edgeName) + " "
                    + Identifiers.vidLiteral(src) + "->" + Identifiers.vidLiteral(dst)
                    + "@" + Identifiers.rankLiteral(rank) + " YIELD edge AS e");
            Value value = objectResult(result, Value.EVAL);
            if (value == null) {
                record.status = "MISSING";
                record.error = "FETCH returned no edge record";
                return record;
            }
            validateEdge(value.getEVal(), src, wanted.edge, rank.getIVal(), dst, schema);
            record.fields = NativeValueCodec.fields(value);
            record.status = "OK";
        } catch (Exception failure) {
            record.status = "ERROR";
            record.error = error(failure);
        }
        return record;
    }

    /** Validate the complete FETCH result; errors or unexpected scalar outputs are never missing. */
    static Value objectResult(ResultSet result, int expectedKind) {
        require(result != null && result.isSucceeded(), "FETCH failed: "
                + (result == null ? "missing response" : result.getErrorMessage()));
        if (result.isEmpty()) {
            return null;
        }
        List<Row> rows = result.getRows();
        require(rows.size() == 1 && rows.get(0).values != null && rows.get(0).values.size() == 1,
                "FETCH must return exactly one row and one native object");
        Value value = rows.get(0).values.get(0);
        require(value != null && value.getSetField() == expectedKind && value.getFieldValue() != null,
                "FETCH returned the wrong native Value type");
        return value;
    }

    static void validateVertex(Vertex vertex, Value expectedVid,
                               Map<String, SchemaDefinition> schemas, Map<String, String> failures) {
        require(vertex != null && sameIdentifier(expectedVid, vertex.vid),
                "Returned vertex VID differs from requested VID");
        require(vertex.tags != null && !vertex.tags.isEmpty(),
                "Returned vertex has no actual Tag membership");
        Set<String> tags = new HashSet<>();
        for (Tag tag : vertex.tags) {
            require(tag != null && tag.name != null, "Missing returned Tag name");
            String name = Identifiers.nameCell(tag.name);
            require(tags.add(name), "Duplicate returned Tag membership");
            validateProperties(tag.props, schema(schemas, failures, schemaKey("TAG", name)));
        }
    }

    static void validateEdge(Edge edge, Value src, String nameCell, long rank, Value dst,
                             SchemaDefinition schema) {
        require(edge != null && edge.isSetRanking()
                        && sameIdentifier(src, edge.src) && sameIdentifier(dst, edge.dst)
                        && edge.ranking == rank && edge.name != null
                        && nameCell.equals(Identifiers.nameCell(edge.name)),
                "Returned edge key differs from requested src/name/rank/dst");
        // Internal edge type IDs differ across spaces and deliberately do not participate.
        validateProperties(edge.props, schema);
    }

    private static boolean sameIdentifier(Value wanted, Value actual) {
        return wanted != null && actual != null && wanted.getSetField() == actual.getSetField()
                && Identifiers.cell(wanted).equals(Identifiers.cell(actual));
    }

    static void validateProperties(Map<byte[], Value> properties, SchemaDefinition schema) {
        require(properties != null, "Missing native property map");
        Set<String> names = new HashSet<>();
        for (Map.Entry<byte[], Value> property : properties.entrySet()) {
            String name = Identifiers.nameCell(property.getKey());
            require(names.add(name), "Duplicate native property name");
            PropertyDefinition definition = schema.properties.get(name);
            require(definition != null, "Returned property is absent from schema");
            validateProperty(property.getValue(), definition);
        }
        require(names.equals(schema.properties.keySet()),
                "Returned property names differ from complete schema");
    }

    private static void validateProperty(Value value, PropertyDefinition definition) {
        String type = definition.type;
        int expected;
        switch (type) {
            case "BOOL": expected = Value.BVAL; break;
            case "INT8":
            case "INT16":
            case "INT32":
            case "INT64":
            case "TIMESTAMP": expected = Value.IVAL; break;
            case "FLOAT":
            case "DOUBLE": expected = Value.FVAL; break;
            case "DATE": expected = Value.DVAL; break;
            case "TIME": expected = Value.TVAL; break;
            case "DATETIME": expected = Value.DTVAL; break;
            case "DURATION": expected = Value.DUVAL; break;
            case "STRING": expected = Value.SVAL; break;
            default:
                require(type.matches("FIXED_STRING\\([1-9][0-9]*\\)"),
                        "Unsupported actual property schema type: " + type);
                expected = Value.SVAL;
        }
        require(value != null && value.getFieldValue() != null, "Missing native property value");
        if (value.getSetField() == Value.NVAL) {
            require(value.getNVal() == NullType.__NULL__ && definition.nullable,
                    "Error NULL or NULL in a NOT NULL property");
        } else {
            require(value.getSetField() == expected, "Native property type differs from schema " + type);
        }
    }

    private static Baseline readBaseline(Graph graph, PlanBundle.Plan plan) throws Exception {
        Baseline baseline = new Baseline();
        ResultSet tags = graph.execute("SHOW TAGS");
        if (!tags.isEmpty()) {
            int nameColumn = column(tags, "name");
            for (Row row : tags.getRows()) {
                String nameCell = Identifiers.nameCell(stringBytes(row.values.get(nameColumn)));
                loadSchema(graph, baseline, "TAG", nameCell);
            }
        }
        for (PlanBundle.EdgeKey edge : plan.edges) {
            String key = schemaKey("EDGE", edge.edge);
            if (!baseline.schemas.containsKey(key) && !baseline.failures.containsKey(key)) {
                loadSchema(graph, baseline, "EDGE", edge.edge);
            }
        }
        return baseline;
    }

    private static void loadSchema(Graph graph, Baseline baseline, String kind, String nameCell) {
        String key = schemaKey(kind, nameCell);
        try {
            baseline.schemas.put(key, describe(graph, kind, Identifiers.decodeName(nameCell)));
        } catch (Exception failure) {
            // An unrelated Tag must not make capture fail. Report only if a listed object uses it.
            baseline.failures.put(key, error(failure));
        }
    }

    private static SchemaDefinition describe(Graph graph, String kind, String name) throws Exception {
        return readSchema(graph.execute("DESCRIBE " + kind + " " + Identifiers.quote(name)));
    }

    static SchemaDefinition readSchema(ResultSet result) {
        require(result != null && result.isSucceeded(), "DESCRIBE failed");
        SchemaDefinition definition = new SchemaDefinition();
        int field = column(result, "field");
        int type = column(result, "type");
        int nullable = column(result, "null");
        if (!result.isEmpty()) {
            for (Row row : result.getRows()) {
                String name = Identifiers.nameCell(stringBytes(row.values.get(field)));
                String valueType = normalizeType(text(row.values.get(type)));
                Value nullability = row.values.get(nullable);
                boolean permitsNull;
                if (nullability.getSetField() == Value.BVAL) {
                    permitsNull = nullability.isBVal();
                } else {
                    String label = text(nullability).toUpperCase(Locale.ROOT);
                    require("YES".equals(label) || "NO".equals(label), "Unexpected nullable flag");
                    permitsNull = "YES".equals(label);
                }
                require(definition.properties.put(name,
                        new PropertyDefinition(valueType, permitsNull)) == null,
                        "Duplicate DESCRIBE property name");
            }
        }
        return definition;
    }

    private static String spaceVidType(Graph graph, String space) throws Exception {
        ResultSet result = graph.execute("DESCRIBE SPACE " + Identifiers.quote(space));
        require(!result.isEmpty() && result.getRows().size() == 1,
                "DESCRIBE SPACE did not return exactly one space");
        String type = normalizeType(text(result.getRows().get(0).values.get(column(result, "vidtype"))));
        require(type.equals("INT64") || type.matches("FIXED_STRING\\([1-9][0-9]*\\)"),
                "Unsupported space VID type: " + type);
        return type;
    }

    private static void verifyOctalBytes(Graph graph) throws Exception {
        byte[] expected = new byte[255];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) (i + 1);
        }
        ResultSet result = graph.execute("YIELD " + Identifiers.vidLiteral(Value.sVal(expected))
                + " AS probe");
        Value actual = objectResult(result, Value.SVAL);
        require(actual != null && Arrays.equals(expected, actual.getSVal()),
                "Exact string VID queries require graphd disable_octal_escape_char=false");
    }

    private static void addSchema(SnapshotManifest manifest, Baseline baseline,
                                  String kind, String nameCell) {
        String key = schemaKey(kind, nameCell);
        manifest.schemas.put(key, schema(baseline.schemas, baseline.failures, key).canonical());
    }

    private static SchemaDefinition schema(Map<String, SchemaDefinition> schemas,
                                            Map<String, String> failures, String key) {
        require(!failures.containsKey(key), "Unable to read relevant schema " + key + ": "
                + failures.get(key));
        SchemaDefinition result = schemas.get(key);
        require(result != null, "Returned Tag was absent from the initial schema inventory: " + key);
        return result;
    }

    private static String schemaKey(String kind, String nameCell) {
        return kind + "/" + nameCell;
    }

    private static int column(ResultSet result, String expected) {
        List<String> names = result.keys();
        for (int i = 0; i < names.size(); i++) {
            String normalized = names.get(i).replace("_", "").replace(" ", "")
                    .toLowerCase(Locale.ROOT);
            if (normalized.equals(expected) || expected.equals("null") && normalized.equals("nullable")) {
                return i;
            }
        }
        throw new IllegalStateException("Missing metadata result column: " + expected);
    }

    private static String normalizeType(String type) {
        require(type != null, "Missing schema type");
        String normalized = type.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return normalized.equals("INT") ? "INT64" : normalized;
    }

    private static String text(Value value) {
        return Identifiers.decodeName(Identifiers.nameCell(stringBytes(value)));
    }

    private static byte[] stringBytes(Value value) {
        require(value != null && value.getSetField() == Value.SVAL && value.getSVal() != null,
                "Expected raw metadata string");
        return value.getSVal();
    }

    private static void count(SnapshotManifest manifest, CaptureRecord record) {
        manifest.recordCount++;
        if ("OK".equals(record.status)) {
            manifest.okCount++;
        } else if ("MISSING".equals(record.status)) {
            manifest.missingCount++;
        } else {
            manifest.errorCount++;
        }
    }

    private static String error(Exception exception) {
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return message.length() <= 4096 ? message : message.substring(0, 4096);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    static final class SchemaDefinition {
        final Map<String, PropertyDefinition> properties = new TreeMap<>();

        String canonical() {
            Map<String, Map<String, Object>> canonical = new TreeMap<>();
            for (Map.Entry<String, PropertyDefinition> entry : properties.entrySet()) {
                Map<String, Object> attributes = new TreeMap<>();
                attributes.put("nullable", entry.getValue().nullable);
                attributes.put("type", entry.getValue().type);
                canonical.put(entry.getKey(), attributes);
            }
            return JSON.toJSONString(canonical);
        }
    }

    static final class PropertyDefinition {
        final String type;
        final boolean nullable;

        PropertyDefinition(String type, boolean nullable) {
            this.type = normalizeType(type);
            this.nullable = nullable;
        }
    }

    private static final class Baseline {
        final Map<String, SchemaDefinition> schemas = new TreeMap<>();
        final Map<String, String> failures = new HashMap<>();
    }

    private static final class Graph implements AutoCloseable {
        final NebulaPool pool = new NebulaPool();
        final Session session;

        Graph(ConnectionSettings config) throws Exception {
            NebulaPoolConfig settings = new NebulaPoolConfig();
            settings.setMaxConnSize(1);
            settings.setTimeout(config.timeoutMs);
            try {
                require(pool.init(Collections.singletonList(new HostAddress(config.host,
                        config.graphPort)), settings), "Unable to initialize graph connection");
                session = pool.getSession(config.user, config.password, false);
            } catch (Exception failure) {
                pool.close();
                throw failure;
            }
        }

        ResultSet execute(String query) throws Exception {
            ResultSet result = session.execute(query);
            require(result != null && result.isSucceeded(), "nGQL failed: "
                    + (result == null ? "missing response" : result.getErrorMessage()));
            return result;
        }

        @Override
        public void close() {
            try {
                session.release();
            } finally {
                pool.close();
            }
        }
    }
}
