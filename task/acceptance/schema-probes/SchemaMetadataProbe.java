import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.facebook.thrift.TSerializer;
import com.facebook.thrift.protocol.TBinaryProtocol;
import com.facebook.thrift.protocol.TCompactProtocol;
import com.facebook.thrift.transport.TMemoryInputTransport;
import com.vesoft.nebula.Value;
import com.vesoft.nebula.client.graph.data.HostAddress;
import com.vesoft.nebula.client.meta.MetaClient;
import com.vesoft.nebula.meta.ColumnDef;
import com.vesoft.nebula.meta.EdgeItem;
import com.vesoft.nebula.meta.Schema;
import com.vesoft.nebula.meta.TagItem;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only raw metadata probe. Constant decode here is diagnostic, not an import fallback. */
public final class SchemaMetadataProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("<space> <scenarios.json> <case-id> <out.json>");
        Path output = Paths.get(args[3]);
        if (Files.exists(output)) throw new IllegalStateException("Output exists");
        JSONObject fixture = null;
        for (Object item : JSON.parseObject(new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8)).getJSONArray("cases")) {
            JSONObject value = (JSONObject) item;
            if (args[2].equals(value.getString("id"))) fixture = value;
        }
        if (fixture == null) throw new IllegalArgumentException("Unknown fixture");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("space", args[0]); report.put("caseId", args[2]); report.put("startedAt", Instant.now().toString());
        MetaClient meta = new MetaClient(Collections.singletonList(new HostAddress("127.0.0.1", 9559)), 60000, 0, 0);
        try {
            meta.connect();
            byte[] spaceComment = meta.getSpace(args[0]).properties.comment;
            require(Arrays.equals(spaceComment,
                    Base64.getDecoder().decode(fixture.getString("spaceCommentBase64"))), "Space comment bytes differ");
            report.put("spaceCommentBase64", spaceComment == null ? null : Base64.getEncoder().encodeToString(spaceComment));
            List<Map<String, Object>> tables = new ArrayList<>();
            for (TagItem tag : meta.getTags(args[0])) tables.add(check("TAG", tag.tag_name, tag.schema, fixture));
            for (EdgeItem edge : meta.getEdges(args[0])) tables.add(check("EDGE", edge.edge_name, edge.schema, fixture));
            require(tables.size() == 2, "Expected exactly one Tag and Edge schema");
            report.put("tables", tables); report.put("matchedExpected", true);
        } finally { meta.close(); }
        report.put("finishedAt", Instant.now().toString());
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.write(output, JSON.toJSONString(report, SerializerFeature.PrettyFormat).getBytes(StandardCharsets.UTF_8));
        System.out.println("Raw Schema/default/comment metadata matched fixture: " + args[0]);
    }

    private static Map<String, Object> check(String kind, byte[] name, Schema schema, JSONObject fixture) throws Exception {
        JSONArray columns = fixture.getJSONArray("columns");
        require(schema.columns.size() == columns.size(), "Column count differs");
        require(Arrays.equals(schema.schema_prop.comment, Base64.getDecoder().decode(fixture.getString("tableCommentBase64"))), "Table comment bytes differ");
        List<Map<String, Object>> values = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            JSONObject expected = columns.getJSONObject(i);
            ColumnDef actual = schema.columns.get(i);
            require(Arrays.equals(actual.name, expected.getString("name").getBytes(StandardCharsets.UTF_8)), "Column name differs");
            String comment = expected.getString("commentBase64");
            require(Arrays.equals(actual.comment, comment == null ? null : Base64.getDecoder().decode(comment)), "Column comment bytes differ");
            require(actual.nullable == expected.getBooleanValue("nullable"), "Nullable differs");
            byte[] encoded = actual.default_value;
            require(encoded != null && encoded.length > 1, "Missing encoded default");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", expected.getString("name")); result.put("type", expected.getString("type"));
            result.put("expression", expected.getString("expression")); result.put("expressionKindByte", encoded[0] & 255);
            result.put("defaultExpressionBase64", Base64.getEncoder().encodeToString(encoded));
            result.put("commentBase64", comment);
            if (expected.getBooleanValue("dynamic")) {
                require(encoded[0] != 0, "Dynamic default was incorrectly replaced with a constant");
                result.put("retainsNonConstantExpression", true);
            } else {
                require(encoded[0] == 0, "Expected source constant folding for this fixture expression");
                TMemoryInputTransport input = new TMemoryInputTransport(encoded, 1, encoded.length - 1);
                Value nativeValue = new Value();
                nativeValue.read(new TCompactProtocol(input));
                require(input.getBytesRemainingInBuffer() == 0, "Trailing encoded constant bytes");
                result.put("constantNativeValue", NativeValueCodec.encode(nativeValue));
                if (expected.containsKey("expectedStringBase64")) {
                    require(nativeValue.getSetField() == Value.SVAL && Arrays.equals(nativeValue.getSVal(),
                            Base64.getDecoder().decode(expected.getString("expectedStringBase64"))), "Default string bytes differ");
                }
            }
            values.add(result);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", kind); result.put("name", new String(name, StandardCharsets.UTF_8));
        byte[] nativeSchema = new TSerializer(new TBinaryProtocol.Factory()).serialize(schema);
        result.put("nativeSchemaBase64", Base64.getEncoder().encodeToString(nativeSchema));
        result.put("nativeSchemaSha256Base64", Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(nativeSchema)));
        result.put("columns", values); return result;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
