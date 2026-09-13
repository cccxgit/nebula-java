import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.vesoft.nebula.NullType;
import com.vesoft.nebula.Row;
import com.vesoft.nebula.Value;
import com.vesoft.nebula.client.graph.NebulaPoolConfig;
import com.vesoft.nebula.client.graph.data.HostAddress;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.net.NebulaPool;
import com.vesoft.nebula.client.graph.net.Session;
import com.vesoft.nebula.verification.Identifiers;
import com.vesoft.nebula.verification.NativeValueCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Independent, read-only fixture audit, compiled against the verification shaded JAR.
 * Uses only USE, SHOW, DESCRIBE, YIELD and FETCH; never executes the fixture INSERT text.
 * Expected scalar expressions are evaluated independently of the stored records.
 */
public final class FixtureSourceOracle {
    private final Session session;
    private final JSONObject oracle;
    private final Map<String, SortedMap<String, String>> groups = new HashMap<>();
    private final Map<String, Value[]> expressionCache = new HashMap<>();
    private final MessageDigest digest = MessageDigest.getInstance("SHA-256");

    private FixtureSourceOracle(Session session, JSONObject oracle) throws Exception {
        this.session = session;
        this.oracle = oracle;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            require(i + 1 < args.length && args[i].startsWith("--"), "Use --name value arguments");
            require(options.put(args[i].substring(2), args[i + 1]) == null, "Duplicate option");
        }
        for (String key : options.keySet()) {
            require(Arrays.asList("oracle", "out", "host", "port", "user", "space", "password-env").contains(key),
                    "Unknown option: " + key);
        }
        require(options.containsKey("oracle") && options.containsKey("out"), "--oracle and --out are required");
        Path input = Paths.get(options.get("oracle")), output = Paths.get(options.get("out"));
        require(!Files.exists(output), "Oracle output already exists: " + output);
        byte[] inputBytes = Files.readAllBytes(input);
        JSONObject oracle = JSON.parseObject(new String(inputBytes, StandardCharsets.UTF_8));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("id", oracle.getString("id"));
        report.put("startedAt", Instant.now().toString());
        report.put("oracleSha256", hex(MessageDigest.getInstance("SHA-256").digest(inputBytes)));
        report.put("passed", false);
        report.put("status", "FAILED");
        NebulaPool pool = new NebulaPool();
        Session session = null;
        int exit = 1;
        try {
            String host = options.getOrDefault("host", "127.0.0.1");
            int port = Integer.parseInt(options.getOrDefault("port", "9669"));
            String password = System.getenv().getOrDefault(options.getOrDefault("password-env", "NEBULA_PASSWORD"), "nebula");
            NebulaPoolConfig config = new NebulaPoolConfig();
            config.setMaxConnSize(1);
            config.setTimeout(60000);
            require(pool.init(Collections.singletonList(new HostAddress(host, port)), config), "Pool initialization failed");
            session = pool.getSession(options.getOrDefault("user", "root"), password, false);
            new FixtureSourceOracle(session, oracle).validate(
                    options.getOrDefault("space", oracle.getString("sourceSpace")), report);
            report.put("passed", true);
            report.put("status", "PASSED");
            exit = 0;
        } catch (Exception failure) {
            report.put("failure", failure.toString());
        } finally {
            if (session != null) session.release();
            pool.close();
            report.put("finishedAt", Instant.now().toString());
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(output, JSON.toJSONString(report, SerializerFeature.PrettyFormat)
                    .getBytes(StandardCharsets.UTF_8));
        }
        System.out.println(JSON.toJSONString(report));
        System.exit(exit);
    }

    private void validate(String space, Map<String, Object> report) throws Exception {
        require(oracle.getIntValue("formatVersion") == 1, "Unknown oracle format");
        execute("USE " + Identifiers.quote(space));
        spaceSchema(space);
        schemas();
        JSONObject expressions = oracle.getJSONObject("expressionGroups");
        for (String key : expressions.keySet()) {
            groups.put(key, evaluate(expressions.getJSONArray(key)));
        }
        JSONArray records = oracle.getJSONArray("records");
        JSONObject expected = oracle.getJSONObject("expected");
        require(records.size() == expected.getIntValue("vertices") + expected.getIntValue("edges"),
                "Oracle record count differs from fixture expectation");
        Set<String> keys = new HashSet<>();
        SortedMap<String, Integer> variants = new TreeMap<>();
        long vertices = 0, edges = 0;
        List<Map<String, Object>> evidence = new ArrayList<>();
        report.put("records", evidence);
        report.put("space", space);
        report.put("fixtureFileHashes", oracle.get("fixtureFileHashes"));
        for (Object item : records) {
            JSONObject record = (JSONObject) item;
            SortedMap<String, String> wanted = new TreeMap<>();
            String query, key;
            if ("VERTEX".equals(record.getString("kind"))) {
                Value vid = vid(record.getString("vid"));
                key = "VERTEX/" + record.getString("vid");
                wanted.put("$vid", NativeValueCodec.encode(vid));
                Set<String> tags = new HashSet<>();
                for (Object tagItem : record.getJSONArray("tags")) {
                    JSONObject tag = (JSONObject) tagItem;
                    String prefix = "tag/" + name(tag.getString("name"));
                    require(tags.add(prefix), "Duplicate expected Tag");
                    wanted.put(prefix, "present");
                    properties(wanted, prefix + "/prop/", tag.getString("values"));
                }
                query = "FETCH PROP ON * " + Identifiers.vidLiteral(vid) + " YIELD vertex AS v";
                vertices++;
                String variant = record.getString("variant");
                variants.put(variant, variants.getOrDefault(variant, 0) + 1);
            } else {
                require("EDGE".equals(record.getString("kind")), "Unknown oracle record kind");
                Value src = vid(record.getString("src")), dst = vid(record.getString("dst"));
                Value rank = Identifiers.decodeRank(record.getString("rank"));
                String edgeName = record.getString("name");
                key = "EDGE/" + JSON.toJSONString(Arrays.asList(record.getString("src"),
                        record.getString("dst"), record.getString("rank"), name(edgeName)));
                wanted.put("$src", NativeValueCodec.encode(src));
                wanted.put("$dst", NativeValueCodec.encode(dst));
                wanted.put("$rank", NativeValueCodec.encode(rank));
                wanted.put("$name", NativeValueCodec.encode(Value.sVal(edgeName.getBytes(StandardCharsets.UTF_8))));
                properties(wanted, "prop/", record.getString("values"));
                query = "FETCH PROP ON " + Identifiers.quote(edgeName) + " " + Identifiers.vidLiteral(src)
                        + "->" + Identifiers.vidLiteral(dst) + "@" + Identifiers.rankLiteral(rank) + " YIELD edge AS e";
                edges++;
            }
            require(keys.add(key), "Duplicate expected complete object key: " + key);
            List<Row> rows = execute(query).getRows();
            require(rows != null && rows.size() == 1 && rows.get(0).values.size() == 1,
                    "FETCH did not return exactly one object: " + key);
            SortedMap<String, String> actual = NativeValueCodec.fields(rows.get(0).values.get(0));
            require(wanted.keySet().equals(actual.keySet()),
                    "Object/Tag/property field set differs: " + key + "; expected=" + wanted.keySet() + "; actual=" + actual.keySet());
            for (String field : wanted.keySet()) {
                require(wanted.get(field).equals(actual.get(field)), "Fixture value differs: " + key + "; field=" + field
                        + "; expected=" + shortValue(wanted.get(field)) + "; actual=" + shortValue(actual.get(field)));
            }
            byte[] encoded = JSON.toJSONString(wanted).getBytes(StandardCharsets.UTF_8);
            digest.update(key.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(encoded);
            digest.update((byte) '\n');
            Map<String, Object> proof = new LinkedHashMap<>();
            proof.put("key", key);
            proof.put("variant", record.getString("variant"));
            proof.put("fieldCount", wanted.size());
            proof.put("fieldsSha256", hex(MessageDigest.getInstance("SHA-256").digest(encoded)));
            evidence.add(proof);
            report.put("validatedRows", evidence.size());
        }
        require(vertices == expected.getLongValue("vertices") && edges == expected.getLongValue("edges"),
                "Validated point/edge counts differ from fixture");
        require(JSON.toJSONString(variants).equals(JSON.toJSONString(new TreeMap<>(oracle.getJSONObject("variantCounts")))),
                "Validated variant allocation differs from fixture");
        schemas();
        spaceSchema(space);
        report.put("validatedVertices", vertices);
        report.put("validatedEdges", edges);
        report.put("variantCounts", variants);
        report.put("expectedAndActualFieldsSha256", hex(digest.digest()));
        report.put("expressionGroups", groups.size());
        report.put("schemaMatched", true);
        report.put("comparison", oracle.getString("comparison"));
    }

    private SortedMap<String, String> evaluate(JSONArray properties) throws Exception {
        SortedMap<String, String> values = new TreeMap<>();
        if (properties.isEmpty()) return values;
        StringBuilder query = new StringBuilder("YIELD ");
        for (int i = 0; i < properties.size(); i++) {
            if (i > 0) query.append(',');
            query.append(properties.getJSONObject(i).getString("expression")).append(" AS p").append(i);
        }
        String text = query.toString();
        Value[] evaluated = expressionCache.get(text);
        if (evaluated == null) {
            List<Row> rows = execute(text).getRows();
            require(rows != null && rows.size() == 1 && rows.get(0).values.size() == properties.size(), "Unexpected YIELD result shape");
            evaluated = rows.get(0).values.toArray(new Value[0]);
            expressionCache.put(text, evaluated);
        }
        for (int i = 0; i < evaluated.length; i++) {
            JSONObject property = properties.getJSONObject(i);
            Value value = evaluated[i];
            String expression = property.getString("expression"), type = property.getString("type").toUpperCase(Locale.ROOT);
            if (value.getSetField() == Value.NVAL) {
                require("NULL".equals(expression) && value.getNVal() == NullType.__NULL__,
                        "Non-NULL fixture expression produced NULL/error NULL: " + expression);
            } else {
                int expected = expectedType(type);
                require(value.getSetField() == expected, "Expression native type differs from schema: " + expression + "; " + type);
                if ("FLOAT".equals(type)) value = Value.fVal((double) (float) value.getFVal());
            }
            require(values.put(name(property.getString("name")), NativeValueCodec.encode(value)) == null,
                    "Duplicate expected property");
        }
        return values;
    }

    private void schemas() throws Exception {
        Map<String, Set<String>> names = new HashMap<>();
        names.put("TAG", new HashSet<>());
        names.put("EDGE", new HashSet<>());
        for (Object item : oracle.getJSONArray("schemas")) {
            JSONObject schema = (JSONObject) item;
            String kind = schema.getString("kind"), schemaName = schema.getString("name");
            require(names.containsKey(kind) && names.get(kind).add(schemaName), "Duplicate/unknown expected schema");
            List<Row> rows = execute("DESCRIBE " + kind + " " + Identifiers.quote(schemaName)).getRows();
            JSONArray columns = schema.getJSONArray("columns");
            require(rows != null && rows.size() == columns.size(), "Source schema column count differs: " + schemaName);
            for (int i = 0; i < columns.size(); i++) {
                JSONObject expected = columns.getJSONObject(i);
                List<Value> actual = rows.get(i).values;
                require(actual.size() >= 3 && utf8(actual.get(0)).equals(expected.getString("name"))
                        && normalizeType(utf8(actual.get(1))).equals(normalizeType(expected.getString("type")))
                        && utf8(actual.get(2)).equals(expected.getBooleanValue("nullable") ? "YES" : "NO"),
                        "Source schema definition differs: " + kind + "/" + schemaName + "/" + expected.getString("name"));
            }
        }
        for (String kind : names.keySet()) {
            Set<String> actual = new HashSet<>();
            for (Row row : execute("SHOW " + kind + "S").getRows()) require(actual.add(utf8(row.values.get(0))), "Duplicate schema name");
            require(actual.equals(names.get(kind)), "Source schema name set differs: " + kind);
        }
    }

    private void spaceSchema(String space) throws Exception {
        ResultSet result = execute("DESCRIBE SPACE " + Identifiers.quote(space));
        List<Row> rows = result.getRows();
        require(rows != null && rows.size() == 1, "Expected exactly one source space definition");
        int vidColumn = -1;
        List<String> columns = result.keys();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).replace("_", "").replace(" ", "").equalsIgnoreCase("vidtype")) vidColumn = i;
        }
        require(vidColumn >= 0 && normalizeType(utf8(rows.get(0).values.get(vidColumn)))
                .equals(normalizeType(oracle.getString("vidType"))), "Source space VID schema differs from fixture");
    }

    private void properties(SortedMap<String, String> fields, String prefix, String group) {
        if (group == null) return;
        require(groups.containsKey(group), "Unknown expression group");
        for (Map.Entry<String, String> property : groups.get(group).entrySet())
            require(fields.put(prefix + property.getKey(), property.getValue()) == null, "Duplicate expected field");
    }

    private ResultSet execute(String query) throws Exception {
        // Even a modified oracle cannot add a second statement via its YIELD expressions.
        require(!query.contains(";") && !query.contains("\n") && !query.contains("\r"), "Oracle query must be one statement");
        require(query.startsWith("USE ") || query.startsWith("DESCRIBE ") || query.startsWith("SHOW ")
                || query.startsWith("YIELD ") || query.startsWith("FETCH PROP "), "Oracle is read-only");
        ResultSet result = session.execute(query);
        require(result != null && result.isSucceeded(), "Read-only nGQL failed: "
                + (result == null ? "missing response" : result.getErrorMessage()));
        return result;
    }

    private Value vid(String cell) { return Identifiers.decodeVid(cell, oracle.getString("vidType")); }
    private static String name(String text) { return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
    private static String utf8(Value value) { return new String(value.getSVal(), StandardCharsets.UTF_8); }
    private static String normalizeType(String type) { return type.replace(" ", "").toUpperCase(Locale.ROOT).replace("INT64", "INT"); }
    private static String shortValue(String value) { return value.length() < 250 ? value : value.substring(0, 250) + "... (encodedLength=" + value.length() + ")"; }
    private static int expectedType(String type) {
        if (type.startsWith("INT") || type.equals("TIMESTAMP")) return Value.IVAL;
        if (type.equals("FLOAT") || type.equals("DOUBLE")) return Value.FVAL;
        if (type.equals("STRING") || type.startsWith("FIXED_STRING(")) return Value.SVAL;
        if (type.startsWith("GEOGRAPHY")) return Value.GGVAL;
        switch (type) {
            case "BOOL": return Value.BVAL;
            case "DATE": return Value.DVAL;
            case "TIME": return Value.TVAL;
            case "DATETIME": return Value.DTVAL;
            case "DURATION": return Value.DUVAL;
            default: throw new IllegalArgumentException("Unknown oracle type: " + type);
        }
    }
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) text.append(String.format(Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
