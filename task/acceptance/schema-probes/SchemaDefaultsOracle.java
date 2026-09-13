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

/** Read-only validation of the isolated default-value stress sources, including dynamic defaults. */
public final class SchemaDefaultsOracle {
    public static void main(String[] args) throws Exception {
        require(args.length == 4, "<space> <scenarios.json> <case-id> <out.json>");
        Path output = Paths.get(args[3]);
        require(!Files.exists(output), "Output exists");
        JSONObject fixture = null;
        for (Object item : JSON.parseObject(text(Paths.get(args[1]))).getJSONArray("cases")) {
            JSONObject candidate = (JSONObject) item;
            if (args[2].equals(candidate.getString("id"))) fixture = candidate;
        }
        require(fixture != null, "Unknown fixture");
        Path metadataFile = output.resolveSibling(output.getFileName() + ".metadata.json");
        SchemaMetadataProbe.main(new String[]{args[0], args[1], args[2], metadataFile.toString()});
        JSONObject metadata = JSON.parseObject(text(metadataFile));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("space", args[0]); report.put("caseId", args[2]); report.put("startedAt", Instant.now().toString());
        report.put("passed", false); report.put("status", "FAILED");
        report.put("metadataReportSha256", hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(metadataFile))));
        NebulaPool pool = new NebulaPool();
        Session session = null;
        int exit = 1;
        try {
            NebulaPoolConfig config = new NebulaPoolConfig(); config.setMaxConnSize(1); config.setTimeout(60000);
            require(pool.init(Collections.singletonList(new HostAddress("127.0.0.1", 9669)), config), "Pool initialization failed");
            session = pool.getSession("root", System.getenv().getOrDefault("NEBULA_PASSWORD", "nebula"), false);
            execute(session, "USE " + Identifiers.quote(args[0]));
            JSONArray columns = fixture.getJSONArray("columns");
            Map<String, String> staticFields = new TreeMap<>();
            Map<String, String> expressionValues = new HashMap<>();
            Set<String> dynamicNames = new HashSet<>();
            for (Object item : columns) {
                JSONObject property = (JSONObject) item;
                String name = property.getString("name"), expression = property.getString("expression");
                if (property.getBooleanValue("dynamic")) {
                    dynamicNames.add(name); continue;
                }
                Value computed = only(execute(session, "YIELD " + expression + " AS value"));
                if (computed.getSetField() == Value.NVAL) require("NULL".equals(expression)
                        && computed.getNVal() == NullType.__NULL__, "Unexpected NULL expression");
                expressionValues.put(name, NativeValueCodec.encode(computed));
                if ("FLOAT".equals(property.getString("type")) && computed.getSetField() == Value.FVAL)
                    computed = Value.fVal((double) (float) computed.getFVal());
                staticFields.put(name, NativeValueCodec.encode(computed));
            }
            // The metadata default itself must equal the intended original static expression.
            for (Object tableItem : metadata.getJSONArray("tables")) {
                JSONObject table = (JSONObject) tableItem;
                for (Object columnItem : table.getJSONArray("columns")) {
                    JSONObject column = (JSONObject) columnItem;
                    String name = column.getString("name");
                    if (!dynamicNames.contains(name)) require(expressionValues.get(name).equals(column.getString("constantNativeValue")),
                            "Static default expression bytes decode to a different value: " + name);
                }
                List<Row> described = execute(session, "DESCRIBE " + table.getString("kind") + " "
                        + Identifiers.quote(table.getString("name"))).getRows();
                require(described.size() == columns.size(), "Wrong described column count");
                for (int i = 0; i < columns.size(); i++) {
                    JSONObject expected = columns.getJSONObject(i);
                    List<Value> actual = described.get(i).values;
                    require(utf8(actual.get(0)).equals(expected.getString("name"))
                            && type(utf8(actual.get(1))).equals(type(expected.getString("type"))), "Column name/type differs");
                    if (expected.getBooleanValue("dynamic")) require(actual.get(3).getSetField() == Value.SVAL
                            && utf8(actual.get(3)).equals(expected.getString("expression")),
                            "Dynamic default expression differs or was evaluated into a constant: " + expected.getString("name"));
                }
            }
            List<Map<String, Object>> records = new ArrayList<>(); report.put("records", records);
            Map<String, Set<String>> dynamicValues = new TreeMap<>();
            for (String name : dynamicNames) dynamicValues.put(name, new HashSet<>());
            int rows = fixture.getIntValue("rows");
            for (int i = 0; i < rows; i++) {
                Value src = vid(fixture, i), dst = vid(fixture, (i + 1) % rows);
                for (boolean edge : new boolean[]{false, true}) {
                    String query = edge ? "FETCH PROP ON `defaults_edge` " + Identifiers.vidLiteral(src) + "->"
                            + Identifiers.vidLiteral(dst) + "@" + (i - 500) + " YIELD edge AS e"
                            : "FETCH PROP ON * " + Identifiers.vidLiteral(src) + " YIELD vertex AS v";
                    SortedMap<String, String> actual = NativeValueCodec.fields(only(execute(session, query)));
                    SortedMap<String, String> expected = new TreeMap<>();
                    String prefix = edge ? "prop/" : "tag/" + base64("defaults_tag") + "/prop/";
                    if (edge) {
                        expected.put("$src", NativeValueCodec.encode(src)); expected.put("$dst", NativeValueCodec.encode(dst));
                        expected.put("$rank", NativeValueCodec.encode(Value.iVal(i - 500)));
                        expected.put("$name", NativeValueCodec.encode(Value.sVal("defaults_edge".getBytes(StandardCharsets.UTF_8))));
                    } else {
                        expected.put("$vid", NativeValueCodec.encode(src)); expected.put("tag/" + base64("defaults_tag"), "present");
                    }
                    for (Map.Entry<String, String> property : staticFields.entrySet()) expected.put(prefix + base64(property.getKey()), property.getValue());
                    for (String name : dynamicNames) {
                        String field = prefix + base64(name), encoded = actual.get(field);
                        require(encoded != null, "Missing dynamic property: " + name);
                        JSONArray value = JSON.parseArray(encoded);
                        if ("dynamic_text".equals(name)) {
                            require(value.size() == 2 && "string".equals(value.getString(0)), "Wrong dynamic STRING type");
                            Long.parseLong(new String(Base64.getDecoder().decode(value.getString(1)), StandardCharsets.US_ASCII));
                        } else {
                            require(value.size() == 2 && "int".equals(value.getString(0)), "Wrong dynamic integer type");
                            long number = Long.parseLong(value.getString(1));
                            if ("dynamic_ts".equals(name)) require(number > 0 && number <= 9223372036L, "Invalid dynamic timestamp");
                        }
                        expected.put(field, encoded); dynamicValues.get(name).add(encoded);
                    }
                    require(expected.equals(actual), "Stored static defaults/identity/Tag set differ: " + query);
                    Map<String, Object> proof = new LinkedHashMap<>(); proof.put("index", i); proof.put("kind", edge ? "EDGE" : "VERTEX");
                    proof.put("srcOrVid", Identifiers.cell(src)); proof.put("staticDefaultProperties", staticFields.size());
                    proof.put("dynamicPropertiesTypeChecked", dynamicNames.size());
                    proof.put("fieldsSha256", hex(MessageDigest.getInstance("SHA-256").digest(JSON.toJSONString(actual).getBytes(StandardCharsets.UTF_8))));
                    records.add(proof); report.put("validatedRows", records.size());
                }
            }
            Map<String, Integer> distinct = new TreeMap<>();
            for (String name : dynamicNames) {
                distinct.put(name, dynamicValues.get(name).size());
                if (!"dynamic_ts".equals(name)) require(dynamicValues.get(name).size() > 1, "Dynamic random default did not produce varying values");
            }
            report.put("distinctDynamicValues", distinct); report.put("staticPropertiesPerRecord", staticFields.size());
            report.put("dynamicPropertiesPerRecord", dynamicNames.size()); report.put("validatedVertices", rows); report.put("validatedEdges", rows);
            report.put("schemaAndCommentsMatched", true); report.put("dynamicOriginalExpressionsRetained", true);
            report.put("passed", true); report.put("status", "PASSED"); exit = 0;
        } catch (Exception failure) { report.put("failure", failure.toString()); }
        finally {
            if (session != null) session.release(); pool.close(); report.put("finishedAt", Instant.now().toString());
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.write(output, JSON.toJSONString(report, SerializerFeature.PrettyFormat).getBytes(StandardCharsets.UTF_8));
        }
        System.out.println("Schema defaults oracle " + report.get("status") + ": " + args[0]
                + "; validatedRows=" + report.get("validatedRows") + "; failure=" + report.get("failure"));
        System.exit(exit);
    }
    private static ResultSet execute(Session session, String query) throws Exception {
        require(!query.contains(";") && !query.contains("\n") && !query.contains("\r"), "One read-only query required");
        ResultSet result = session.execute(query); require(result.isSucceeded(), result.getErrorMessage()); return result;
    }
    private static Value only(ResultSet result) {
        require(result.getRows() != null && result.getRows().size() == 1 && result.getRows().get(0).values.size() == 1,
                "Expected one native value"); return result.getRows().get(0).values.get(0);
    }
    private static Value vid(JSONObject fixture, int index) {
        return "INT64".equals(fixture.getString("vidType")) ? Value.iVal(index + 100)
                : Value.sVal(String.format(Locale.ROOT, "schema_%04d", index).getBytes(StandardCharsets.US_ASCII));
    }
    private static String text(Path path) throws Exception { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
    private static String utf8(Value value) { return new String(value.getSVal(), StandardCharsets.UTF_8); }
    private static String type(String value) { return value.replace(" ", "").toUpperCase(Locale.ROOT).replace("INT64", "INT"); }
    private static String base64(String text) { return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(); for (byte item : bytes) value.append(String.format(Locale.ROOT, "%02x", item & 255)); return value.toString();
    }
    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
