package com.vesoft.nebula.verification;

import com.vesoft.nebula.DataSet;
import com.vesoft.nebula.Edge;
import com.vesoft.nebula.ErrorCode;
import com.vesoft.nebula.NullType;
import com.vesoft.nebula.Row;
import com.vesoft.nebula.Tag;
import com.vesoft.nebula.Value;
import com.vesoft.nebula.Vertex;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.graph.ExecutionResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/** Graph result validation, independent of any database and of the migration module. */
public class FetchCollectorTest {
    private static final Value VID = Value.sVal(new byte[] {'v', (byte) 255});

    @Test
    public void emptyTagHasExplicitMembershipEvenWithoutProperties() {
        Vertex withMarker = vertex("person", "marker");
        Vertex withoutMarker = vertex("person");
        Map<String, FetchCollector.SchemaDefinition> schemas = schemas("person", "marker");
        FetchCollector.validateVertex(withMarker, VID, schemas, Collections.emptyMap());
        FetchCollector.validateVertex(withoutMarker, VID, schemas, Collections.emptyMap());
        Assert.assertNotEquals(NativeValueCodec.fields(Value.vVal(withMarker)),
                NativeValueCodec.fields(Value.vVal(withoutMarker)));
        Assert.assertEquals("present", NativeValueCodec.fields(Value.vVal(withMarker))
                .get("tag/bWFya2Vy"));
    }

    @Test(expected = IllegalStateException.class)
    public void returnedVidAloneCannotProveVertexOrTagExistence() {
        // A scalar FETCH id(vertex) can return the requested VID despite an absent Tag.
        FetchCollector.objectResult(result(new String[] {"v"},
                Collections.singletonList(new Row(Collections.singletonList(VID)))), Value.VVAL);
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsVertexWithNoActualTagMembership() {
        FetchCollector.validateVertex(vertex(), VID, Collections.emptyMap(), Collections.emptyMap());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsWrongReturnedRawVid() {
        Vertex returned = vertex("person");
        returned.vid = Value.sVal(new byte[] {'v', (byte) 254});
        FetchCollector.validateVertex(returned, VID, schemas("person"), Collections.emptyMap());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsDuplicateActualTagMembership() {
        FetchCollector.validateVertex(vertex("person", "person"), VID, schemas("person"),
                Collections.emptyMap());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsReturnedTagAbsentFromInitialMetadata() {
        FetchCollector.validateVertex(vertex("unexpected"), VID, schemas("person"),
                Collections.emptyMap());
    }

    @Test(expected = IllegalStateException.class)
    public void nullablePropertyMustStillExistInReturnedPropertyMap() {
        FetchCollector.validateProperties(Collections.emptyMap(), oneProperty("STRING", true));
    }

    @Test
    public void distinguishesNullFromEmptyStringInCompleteNativeProperties() {
        FetchCollector.SchemaDefinition schema = oneProperty("STRING", true);
        Map<byte[], Value> empty = property(Value.sVal(new byte[0]));
        Map<byte[], Value> nullable = property(Value.nVal(NullType.__NULL__));
        FetchCollector.validateProperties(empty, schema);
        FetchCollector.validateProperties(nullable, schema);
        Assert.assertNotEquals(NativeValueCodec.encode(empty.values().iterator().next()),
                NativeValueCodec.encode(nullable.values().iterator().next()));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsNativeTypeDifferentFromSchema() {
        FetchCollector.validateProperties(property(Value.iVal(1)), oneProperty("STRING", true));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsNullInRequiredProperty() {
        FetchCollector.validateProperties(property(Value.nVal(NullType.__NULL__)),
                oneProperty("STRING", false));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsUnsupportedActuallyUsedSchemaEvenWhenValueIsNull() {
        FetchCollector.validateProperties(property(Value.nVal(NullType.__NULL__)),
                oneProperty("GEOGRAPHY", true));
    }

    @Test
    public void schemaRowsAreCanonicalAndIndependentOfMetadataOrdering() {
        List<Row> rows = Arrays.asList(schemaRow("b", "INT", "YES"),
                schemaRow("a", "fixed_string(10)", "NO"));
        FetchCollector.SchemaDefinition first = FetchCollector.readSchema(schemaResult(rows));
        List<Row> reversed = new ArrayList<>(rows);
        Collections.reverse(reversed);
        FetchCollector.SchemaDefinition second = FetchCollector.readSchema(schemaResult(reversed));
        Assert.assertEquals(first.canonical(), second.canonical());
        Assert.assertTrue(first.canonical().contains("INT64"));
        Assert.assertTrue(first.canonical().contains("FIXED_STRING(10)"));
    }

    @Test
    public void emptyAndUnrelatedUnsupportedSchemasCanBeReadAsMetadata() {
        Assert.assertEquals("{}", FetchCollector.readSchema(schemaResult(Collections.emptyList()))
                .canonical());
        Assert.assertTrue(FetchCollector.readSchema(schemaResult(Collections.singletonList(
                schemaRow("geo", "geography(point)", "YES")))).canonical().contains("GEOGRAPHY"));
    }

    @Test
    public void emptySuccessfulFetchIsMissingRatherThanAnEmptyNativeObject() {
        Assert.assertNull(FetchCollector.objectResult(result(new String[] {"v"},
                Collections.emptyList()), Value.VVAL));
    }

    @Test(expected = IllegalStateException.class)
    public void multipleRowsCannotPassAsOneRequestedObject() {
        Row row = new Row(Collections.singletonList(Value.vVal(vertex("person"))));
        FetchCollector.objectResult(result(new String[] {"v"}, Arrays.asList(row, row)), Value.VVAL);
    }

    @Test(expected = IllegalStateException.class)
    public void failedQueryIsNeverMissing() {
        ExecutionResponse response = new ExecutionResponse(ErrorCode.E_SYNTAX_ERROR, 0);
        response.setError_msg(bytes("syntax failed"));
        FetchCollector.objectResult(new ResultSet(response, 0), Value.VVAL);
    }

    @Test
    public void internalEdgeTypeIdsAreNotLogicalIdentity() {
        Edge first = edge(10);
        Edge second = edge(999);
        FetchCollector.validateEdge(first, VID, Identifiers.nameCell("knows"), -7, VID,
                new FetchCollector.SchemaDefinition());
        FetchCollector.validateEdge(second, VID, Identifiers.nameCell("knows"), -7, VID,
                new FetchCollector.SchemaDefinition());
        Assert.assertEquals(NativeValueCodec.fields(Value.eVal(first)),
                NativeValueCodec.fields(Value.eVal(second)));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsDifferentReturnedEdgeRank() {
        FetchCollector.validateEdge(edge(10), VID, Identifiers.nameCell("knows"), -8, VID,
                new FetchCollector.SchemaDefinition());
    }

    private static Vertex vertex(String... tagNames) {
        List<Tag> tags = new ArrayList<>();
        for (String name : tagNames) {
            tags.add(new Tag(bytes(name), Collections.emptyMap()));
        }
        return new Vertex(VID, tags);
    }

    private static Edge edge(int type) {
        return new Edge(VID, VID, type, bytes("knows"), -7, Collections.emptyMap());
    }

    private static Map<String, FetchCollector.SchemaDefinition> schemas(String... names) {
        Map<String, FetchCollector.SchemaDefinition> schemas = new HashMap<>();
        for (String name : names) {
            schemas.put("TAG/" + Identifiers.nameCell(name), new FetchCollector.SchemaDefinition());
        }
        return schemas;
    }

    private static FetchCollector.SchemaDefinition oneProperty(String type, boolean nullable) {
        FetchCollector.SchemaDefinition schema = new FetchCollector.SchemaDefinition();
        schema.properties.put(Identifiers.nameCell("p"),
                new FetchCollector.PropertyDefinition(type, nullable));
        return schema;
    }

    private static Map<byte[], Value> property(Value value) {
        return Collections.singletonMap(bytes("p"), value);
    }

    private static Row schemaRow(String name, String type, String nullable) {
        return new Row(Arrays.asList(Value.sVal(bytes(name)), Value.sVal(bytes(type)),
                Value.sVal(bytes(nullable))));
    }

    private static ResultSet schemaResult(List<Row> rows) {
        return result(new String[] {"Field", "Type", "Null"}, rows);
    }

    private static ResultSet result(String[] names, List<Row> rows) {
        List<byte[]> columns = new ArrayList<>();
        for (String name : names) {
            columns.add(bytes(name));
        }
        return new ResultSet(new ExecutionResponse(ErrorCode.SUCCEEDED, 0)
                .setData(new DataSet(columns, rows)), 0);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
