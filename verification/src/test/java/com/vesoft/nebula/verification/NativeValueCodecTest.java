package com.vesoft.nebula.verification;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.vesoft.nebula.Coordinate;
import com.vesoft.nebula.Date;
import com.vesoft.nebula.DateTime;
import com.vesoft.nebula.Duration;
import com.vesoft.nebula.Edge;
import com.vesoft.nebula.Geography;
import com.vesoft.nebula.LineString;
import com.vesoft.nebula.NullType;
import com.vesoft.nebula.Point;
import com.vesoft.nebula.Polygon;
import com.vesoft.nebula.Tag;
import com.vesoft.nebula.Time;
import com.vesoft.nebula.Value;
import com.vesoft.nebula.Vertex;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SortedMap;
import org.junit.Test;

public class NativeValueCodecTest {
    @Test
    public void scalarTypesStayDistinctAndInt64NeverPassesThroughDouble() {
        assertEquals("[\"null\"]", NativeValueCodec.encode(Value.nVal(NullType.__NULL__)));
        assertEquals("[\"bool\",true]", NativeValueCodec.encode(Value.bVal(true)));
        assertEquals("[\"bool\",false]", NativeValueCodec.encode(Value.bVal(false)));
        for (long value : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, 9007199254740993L, -1, 0, 1}) {
            JSONArray payload = JSON.parseArray(NativeValueCodec.encode(Value.iVal(value)));
            assertEquals("int", payload.getString(0));
            assertTrue(payload.get(1) instanceof String);
            assertEquals(value, Long.parseLong(payload.getString(1)));
        }
        assertNotEquals(NativeValueCodec.encode(Value.iVal(1)), NativeValueCodec.encode(bytes("1")));
        assertNotEquals(NativeValueCodec.encode(Value.iVal(1)), NativeValueCodec.encode(Value.fVal(1)));
    }

    @Test
    public void floatingPointExtremaSubnormalsAndSignedZeroHaveExactPayloads() {
        assertEquals("[\"float\",\"8000000000000000\"]", NativeValueCodec.encode(Value.fVal(-0.0)));
        assertEquals("[\"float\",\"0000000000000000\"]", NativeValueCodec.encode(Value.fVal(0.0)));
        assertEquals("[\"float\",\"0000000000000001\"]",
                NativeValueCodec.encode(Value.fVal(Double.MIN_VALUE)));
        assertEquals("[\"float\",\"7fefffffffffffff\"]",
                NativeValueCodec.encode(Value.fVal(Double.MAX_VALUE)));
        for (double value : new double[]{-Double.MIN_VALUE, Double.MIN_NORMAL, -Double.MAX_VALUE,
                Float.MAX_VALUE, Float.MIN_VALUE, Float.MIN_NORMAL, -Float.MAX_VALUE, 0.1, 0.1f}) {
            assertRawBits(value);
        }
        assertNotEquals(NativeValueCodec.encode(Value.fVal(-0.0)), NativeValueCodec.encode(Value.fVal(0.0)));
    }

    @Test
    public void randomDoubleBitsAndNanPayloadsRemainExact() {
        Random random = new Random(772021);
        for (int i = 0; i < 8192; i++) {
            assertRawBits(Double.longBitsToDouble(random.nextLong()));
        }
        for (long bits : new long[]{0x7ff0000000000000L, 0xfff0000000000000L,
                0x7ff8000000000000L, 0x7ff8000000000001L, 0xfff80000000000ffL}) {
            assertRawBits(Double.longBitsToDouble(bits));
        }
        assertNotEquals(NativeValueCodec.encode(Value.fVal(Double.longBitsToDouble(0x7ff8000000000000L))),
                NativeValueCodec.encode(Value.fVal(Double.longBitsToDouble(0x7ff8000000000001L))));
    }

    @Test
    public void emptyNullNulUnicodeAndCombiningSequencesRemainDistinct() {
        assertEquals("[\"string\",\"\"]", NativeValueCodec.encode(bytes("")));
        assertEquals("[\"string\",\"YWJjAGRlZg==\"]", NativeValueCodec.encode(bytes("abc\u0000def")));
        String[] samples = {"", "N", "NULL", "V:", "'\"\\", "\r\n\r\n\t", "你好😀",
                "e\u0301", "é", " a,b \u0000 \n", "\\000"};
        for (String sample : samples) {
            assertBinary(sample.getBytes(StandardCharsets.UTF_8));
        }
        assertNotEquals(NativeValueCodec.encode(bytes("e\u0301")), NativeValueCodec.encode(bytes("é")));
        assertNotEquals(NativeValueCodec.encode(bytes("")), NativeValueCodec.encode(Value.nVal(NullType.__NULL__)));
    }

    @Test
    public void allBytesInvalidUtf8AndOneMiBPayloadStayBinary() {
        byte[] all = new byte[256];
        for (int i = 0; i < all.length; i++) {
            all[i] = (byte) i;
        }
        assertBinary(all);
        assertBinary(new byte[]{(byte) 0xff, (byte) 0xfe, (byte) 0xc0, (byte) 0xaf, 0,
                (byte) 0xed, (byte) 0xa0, (byte) 0x80, (byte) 0xf4, (byte) 0x90});
        byte[] large = new byte[1024 * 1024 + 1];
        new Random(6001).nextBytes(large);
        assertBinary(large);
    }

    @Test
    public void dateTimeAndDurationEncodeOriginalFieldsWithoutNormalization() {
        assertEquals("[\"date\",\"-32768\",\"1\",\"1\"]", NativeValueCodec.encode(
                Value.dVal(new Date(Short.MIN_VALUE, (byte) 1, (byte) 1))));
        assertEquals("[\"date\",\"2024\",\"2\",\"29\"]", NativeValueCodec.encode(
                Value.dVal(new Date((short) 2024, (byte) 2, (byte) 29))));
        assertEquals("[\"time\",\"23\",\"59\",\"59\",\"999999\"]", NativeValueCodec.encode(
                Value.tVal(new Time((byte) 23, (byte) 59, (byte) 59, 999999))));
        assertEquals("[\"datetime\",\"32767\",\"12\",\"31\",\"13\",\"14\",\"15\",\"1\"]",
                NativeValueCodec.encode(Value.dtVal(new DateTime(Short.MAX_VALUE, (byte) 12, (byte) 31,
                        (byte) 13, (byte) 14, (byte) 15, 1))));
        assertEquals("[\"duration\",\"-3\",\"1\",\"-2\"]",
                NativeValueCodec.encode(Value.duVal(new Duration(1, -2, -3))));
        assertEquals("[\"duration\",\"2147483647\",\"9223372036854775807\",\"2147483647\"]",
                NativeValueCodec.encode(Value.duVal(
                        new Duration(Long.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE))));
        assertEquals("[\"duration\",\"-2147483648\",\"-9223372036854775808\",\"-2147483648\"]",
                NativeValueCodec.encode(Value.duVal(
                        new Duration(Long.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE))));
        assertNotEquals(NativeValueCodec.encode(Value.duVal(new Duration(1, 0, 0))),
                NativeValueCodec.encode(Value.duVal(new Duration(0, 1000000, 0))));
    }

    @Test
    public void errorsMissingValuesGeographyAndCompositeScalarsFailClosed() {
        for (NullType type : NullType.values()) {
            if (type != NullType.__NULL__) {
                reject(() -> NativeValueCodec.encode(Value.nVal(type)));
            }
        }
        reject(() -> NativeValueCodec.encode(null));
        reject(() -> NativeValueCodec.encode(new Value()));
        reject(() -> NativeValueCodec.encode(Value.ggVal(new Geography())));
        reject(() -> NativeValueCodec.encode(vertex()));
        reject(() -> NativeValueCodec.fields(Value.iVal(1)));
        reject(() -> NativeValueCodec.fields(Value.nVal(NullType.__NULL__)));
    }

    @Test
    public void emptyTagIsDifferentFromMissingTagAndNullPropertyIsDifferentFromMissingProperty() {
        SortedMap<String, String> missing = NativeValueCodec.fields(vertex());
        SortedMap<String, String> empty = NativeValueCodec.fields(vertex(tag("person")));
        SortedMap<String, String> hasNull = NativeValueCodec.fields(vertex(
                tag("person", "name", Value.nVal(NullType.__NULL__))));
        assertEquals(1, missing.size());
        assertEquals(2, empty.size());
        assertEquals("present", empty.get("tag/cGVyc29u"));
        assertEquals("[\"null\"]", hasNull.get("tag/cGVyc29u/prop/bmFtZQ=="));
        assertNotEquals(missing, empty);
        assertNotEquals(empty, hasNull);
    }

    @Test
    public void vertexTagAndPropertyOrderDoesNotAffectFields() {
        Tag first = tag("a", "z", Value.iVal(Long.MAX_VALUE), "a", bytes("x"));
        Tag reordered = tag("a", "a", bytes("x"), "z", Value.iVal(Long.MAX_VALUE));
        Tag second = tag("b", "c", Value.fVal(-0.0));
        SortedMap<String, String> left = NativeValueCodec.fields(vertex(first, second));
        SortedMap<String, String> right = NativeValueCodec.fields(vertex(second, reordered));
        assertEquals(left, right);
        List<String> keys = new ArrayList<>(left.keySet());
        List<String> sorted = new ArrayList<>(keys);
        Collections.sort(sorted);
        assertEquals(sorted, keys);
        assertEquals(NativeValueCodec.encode(bytes("id")), left.get("$vid"));
    }

    @Test
    public void duplicateTagAndPropertyNamesAreDetectedByBytesNotArrayIdentity() {
        reject(() -> NativeValueCodec.fields(vertex(tag("same"), tag("same"))));
        Tag duplicate = tag("person", "p", Value.iVal(1), "p", Value.iVal(1));
        assertEquals(2, duplicate.props.size());
        reject(() -> NativeValueCodec.fields(vertex(duplicate)));
        Map<byte[], Value> duplicateRaw = new LinkedHashMap<>();
        duplicateRaw.put(new byte[]{(byte) 0xff}, bytes("one"));
        duplicateRaw.put(new byte[]{(byte) 0xff}, bytes("two"));
        reject(() -> NativeValueCodec.fields(Value.eVal(edge(1, duplicateRaw))));
        reject(() -> NativeValueCodec.fields(vertex(
                new Tag(new byte[]{(byte) 0xff}, Collections.emptyMap()),
                new Tag(new byte[]{(byte) 0xff}, Collections.emptyMap()))));
    }

    @Test
    public void rawNamesAreNotNormalizedOrReplacedByUtf8Decoding() {
        Tag tag = new Tag(new byte[]{(byte) 0xff}, new LinkedHashMap<>());
        tag.props.put(new byte[]{(byte) 0xfe}, Value.iVal(1));
        tag.props.put(new byte[]{(byte) 0xff}, Value.iVal(2));
        SortedMap<String, String> fields = NativeValueCodec.fields(vertex(tag));
        assertEquals("present", fields.get("tag//w=="));
        assertEquals(NativeValueCodec.encode(Value.iVal(1)), fields.get("tag//w==/prop//g=="));
        assertEquals(NativeValueCodec.encode(Value.iVal(2)), fields.get("tag//w==/prop//w=="));
    }

    @Test
    public void edgeInternalTypeIdDoesNotAffectLogicalFields() {
        Edge left = edge(1, tag("unused", "b", Value.iVal(2), "a", bytes("x")).props);
        Edge right = edge(981, tag("unused", "a", bytes("x"), "b", Value.iVal(2)).props);
        SortedMap<String, String> fields = NativeValueCodec.fields(Value.eVal(left));
        assertEquals(fields, NativeValueCodec.fields(Value.eVal(right)));
        assertFalse(fields.containsKey("$type"));
        assertEquals(NativeValueCodec.encode(bytes("likes")), fields.get("$name"));
        assertEquals(NativeValueCodec.encode(Value.iVal(Long.MAX_VALUE)), fields.get("$rank"));
        assertEquals(NativeValueCodec.encode(bytes("src")), fields.get("$src"));
        assertEquals(NativeValueCodec.encode(bytes("dst")), fields.get("$dst"));
        right.setRanking(0);
        assertNotEquals(fields, NativeValueCodec.fields(Value.eVal(right)));
        right.setRanking(Long.MAX_VALUE);
        right.setName(new byte[]{(byte) 0xff});
        assertNotEquals(fields, NativeValueCodec.fields(Value.eVal(right)));
    }

    @Test
    public void incompleteGraphValuesNeverBecomeEmptyGraphs() {
        reject(() -> NativeValueCodec.fields(Value.vVal(new Vertex(bytes("v"), null))));
        reject(() -> NativeValueCodec.fields(vertex(new Tag(raw("t"), null))));
        reject(() -> NativeValueCodec.fields(vertex(new Tag(null, Collections.emptyMap()))));
        reject(() -> NativeValueCodec.fields(Value.vVal(new Vertex(Value.bVal(true), Collections.emptyList()))));
        Edge missing = edge(1, Collections.emptyMap());
        missing.unsetRanking();
        reject(() -> NativeValueCodec.fields(Value.eVal(missing)));
    }

    @Test
    public void geographyPointUsesShapeAndCoordinateBitsAsIndependentNativeFields() {
        Value point = Value.ggVal(Geography.ptVal(new Point(new Coordinate(1, 2))));
        assertEquals("[\"geography\",\"point\",[\"3ff0000000000000\",\"4000000000000000\"]]",
                NativeValueCodec.encode(point));
        SortedMap<String, String> fields = NativeValueCodec.fields(vertex(tag("place", "location", point)));
        assertEquals(NativeValueCodec.encode(point), fields.get("tag/cGxhY2U=/prop/bG9jYXRpb24="));
    }

    @Test
    public void geographyDistinguishesSignedZeroAndOneBitCoordinateChanges() {
        Value positive = Value.ggVal(Geography.ptVal(new Point(new Coordinate(0.0, 1.0))));
        Value negative = Value.ggVal(Geography.ptVal(new Point(new Coordinate(-0.0, 1.0))));
        Value next = Value.ggVal(Geography.ptVal(new Point(new Coordinate(0.0, Math.nextUp(1.0)))));
        assertNotEquals(NativeValueCodec.encode(positive), NativeValueCodec.encode(negative));
        assertNotEquals(NativeValueCodec.encode(positive), NativeValueCodec.encode(next));
        assertTrue(NativeValueCodec.encode(negative).contains("8000000000000000"));
        assertTrue(NativeValueCodec.encode(next).contains("3ff0000000000001"));
    }

    @Test
    public void geographyPreservesLinePointOrderAndPolygonRingStructure() {
        Coordinate a = new Coordinate(0, 0);
        Coordinate b = new Coordinate(2, 0);
        Coordinate c = new Coordinate(0, 2);
        List<Coordinate> exterior = Arrays.asList(a, b, b, c, a);
        List<Coordinate> hole = Arrays.asList(new Coordinate(0.1, 0.1), new Coordinate(0.1, 0.2),
                new Coordinate(0.2, 0.1), new Coordinate(0.1, 0.1));
        Value line = Value.ggVal(Geography.lsVal(new LineString(exterior)));
        Value reverse = Value.ggVal(Geography.lsVal(new LineString(Arrays.asList(a, c, b, b, a))));
        assertNotEquals(NativeValueCodec.encode(line), NativeValueCodec.encode(reverse));
        JSONArray linePayload = JSON.parseArray(NativeValueCodec.encode(line));
        assertEquals("linestring", linePayload.getString(1));
        assertEquals(5, linePayload.getJSONArray(2).size());
        Value polygon = Value.ggVal(Geography.pgVal(new Polygon(Arrays.asList(exterior, hole))));
        Value ringsSwapped = Value.ggVal(Geography.pgVal(new Polygon(Arrays.asList(hole, exterior))));
        JSONArray polygonPayload = JSON.parseArray(NativeValueCodec.encode(polygon));
        assertEquals("polygon", polygonPayload.getString(1));
        assertEquals(2, polygonPayload.getJSONArray(2).size());
        assertEquals(5, polygonPayload.getJSONArray(2).getJSONArray(0).size());
        assertEquals(4, polygonPayload.getJSONArray(2).getJSONArray(1).size());
        assertNotEquals(NativeValueCodec.encode(polygon), NativeValueCodec.encode(ringsSwapped));
        assertNotEquals(NativeValueCodec.encode(polygon), NativeValueCodec.encode(line));
    }

    @Test
    public void oneThousandGeographyCoordinatesKeepExactFiniteBits() {
        Random random = new Random(0x6372656fL);
        for (int i = 0; i < 1000; i++) {
            double x = random.nextDouble() * 360 - 180;
            double y = random.nextDouble() * 180 - 90;
            Value value = Value.ggVal(Geography.ptVal(new Point(new Coordinate(x, y))));
            JSONArray pair = JSON.parseArray(NativeValueCodec.encode(value)).getJSONArray(2);
            assertEquals(Double.doubleToRawLongBits(x), Long.parseUnsignedLong(pair.getString(0), 16));
            assertEquals(Double.doubleToRawLongBits(y), Long.parseUnsignedLong(pair.getString(1), 16));
        }
    }

    @Test
    public void geographyRejectsNonFiniteCoordinatesAndMissingNativeComponents() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            reject(() -> NativeValueCodec.encode(Value.ggVal(Geography.ptVal(new Point(new Coordinate(invalid, 0))))));
            reject(() -> NativeValueCodec.encode(Value.ggVal(Geography.ptVal(new Point(new Coordinate(0, invalid))))));
        }
        reject(() -> NativeValueCodec.encode(Value.ggVal(new Geography())));
        reject(() -> NativeValueCodec.encode(Value.ggVal(Geography.ptVal(new Point()))));
        reject(() -> NativeValueCodec.encode(Value.ggVal(Geography.ptVal(new Point(new Coordinate())))));
        reject(() -> NativeValueCodec.encode(Value.ggVal(Geography.lsVal(new LineString()))));
        reject(() -> NativeValueCodec.encode(Value.ggVal(Geography.pgVal(new Polygon()))));
        reject(() -> NativeValueCodec.encode(Value.ggVal(Geography.pgVal(
                new Polygon(Collections.singletonList(null))))));
    }

    @Test
    public void geographyEmptyListsRemainStructurallyDistinct() {
        Value line = Value.ggVal(Geography.lsVal(new LineString(Collections.emptyList())));
        Value polygon = Value.ggVal(Geography.pgVal(new Polygon(Collections.emptyList())));
        Value ring = Value.ggVal(Geography.pgVal(new Polygon(Collections.singletonList(Collections.emptyList()))));
        assertEquals("[\"geography\",\"linestring\",[]]", NativeValueCodec.encode(line));
        assertEquals("[\"geography\",\"polygon\",[]]", NativeValueCodec.encode(polygon));
        assertEquals("[\"geography\",\"polygon\",[[]]]", NativeValueCodec.encode(ring));
    }

    private static void assertRawBits(double value) {
        JSONArray encoded = JSON.parseArray(NativeValueCodec.encode(Value.fVal(value)));
        assertEquals("float", encoded.getString(0));
        String hex = encoded.getString(1);
        assertTrue(hex.matches("[0-9a-f]{16}"));
        assertEquals(Double.doubleToRawLongBits(value), Long.parseUnsignedLong(hex, 16));
    }

    private static void assertBinary(byte[] bytes) {
        String text = NativeValueCodec.encode(Value.sVal(bytes));
        for (int i = 0; i < text.length(); i++) {
            assertTrue(text.charAt(i) < 128);
        }
        JSONArray encoded = JSON.parseArray(text);
        assertEquals("string", encoded.getString(0));
        assertArrayEquals(bytes, Base64.getDecoder().decode(encoded.getString(1)));
        assertFalse(text.contains("\n"));
        assertFalse(text.contains("\r"));
    }

    private static Edge edge(int internalType, Map<byte[], Value> props) {
        return new Edge(bytes("src"), bytes("dst"), internalType, raw("likes"), Long.MAX_VALUE, props);
    }

    private static Value vertex(Tag... tags) {
        return Value.vVal(new Vertex(bytes("id"), Arrays.asList(tags)));
    }

    private static Tag tag(String name, Object... entries) {
        Map<byte[], Value> props = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            props.put(raw((String) entries[i]), (Value) entries[i + 1]);
        }
        return new Tag(raw(name), props);
    }

    private static byte[] raw(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Value bytes(String text) {
        return Value.sVal(raw(text));
    }

    private static void reject(Runnable action) {
        try {
            action.run();
            fail("Accepted unsupported or incomplete native data");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().length() > 0);
        }
    }
}
