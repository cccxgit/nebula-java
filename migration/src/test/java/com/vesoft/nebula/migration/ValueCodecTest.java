package com.vesoft.nebula.migration;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.thrift.protocol.TBinaryProtocol;
import com.facebook.thrift.protocol.TCompactProtocol;
import com.facebook.thrift.protocol.TProtocol;
import com.facebook.thrift.transport.TMemoryBuffer;
import com.vesoft.nebula.Coordinate;
import com.vesoft.nebula.Date;
import com.vesoft.nebula.DateTime;
import com.vesoft.nebula.Duration;
import com.vesoft.nebula.Geography;
import com.vesoft.nebula.LineString;
import com.vesoft.nebula.NullType;
import com.vesoft.nebula.Point;
import com.vesoft.nebula.Polygon;
import com.vesoft.nebula.Time;
import com.vesoft.nebula.Value;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class ValueCodecTest {
    private static final String[] TYPES = {"bool", "int8", "int16", "int32", "int64",
            "int", "float", "double", "string", "fixed_string(32)", "date", "time",
            "datetime", "timestamp", "duration", "geography", "geography(point)",
            "geography(linestring)", "geography(polygon)"};

    @Test
    public void normalNullRoundTripsForEverySupportedType() {
        for (String type : TYPES) {
            assertEquals("N", ValueCodec.encode(Value.nVal(NullType.__NULL__), type));
            assertEquals(NullType.__NULL__, ValueCodec.decode("N", type).getNVal());
        }
    }

    @Test
    public void booleanHasFixedCanonicalRepresentation() {
        assertEquals("V:dHJ1ZQ==", ValueCodec.encode(Value.bVal(true), "bool"));
        assertEquals("V:ZmFsc2U=", ValueCodec.encode(Value.bVal(false), "bool"));
        assertTrue(roundTrip(Value.bVal(true), "bool").isBVal());
        assertFalse(roundTrip(Value.bVal(false), "bool").isBVal());
    }

    @Test
    public void integersRoundTripAtEverySchemaBoundary() {
        String[] types = {"int8", "int16", "int32", "int64", "int", "timestamp"};
        long[] minimums = {Byte.MIN_VALUE, Short.MIN_VALUE, Integer.MIN_VALUE,
                Long.MIN_VALUE, Long.MIN_VALUE, 0};
        long[] maximums = {Byte.MAX_VALUE, Short.MAX_VALUE, Integer.MAX_VALUE,
                Long.MAX_VALUE, Long.MAX_VALUE, 9223372036L};
        for (int i = 0; i < types.length; i++) {
            for (long value : new long[]{minimums[i], maximums[i], 0, 1}) {
                assertEquals(value, roundTrip(Value.iVal(value), types[i]).getIVal());
            }
        }
        assertEquals("V:OTIyMzM3MjAzNjg1NDc3NTgwNw==",
                ValueCodec.encode(Value.iVal(Long.MAX_VALUE), "int64"));
        assertEquals("V:LTkyMjMzNzIwMzY4NTQ3NzU4MDg=",
                ValueCodec.encode(Value.iVal(Long.MIN_VALUE), "int64"));
        // This integer cannot survive an intermediate conversion through double.
        assertEquals(9007199254740993L,
                roundTrip(Value.iVal(9007199254740993L), "int64").getIVal());
    }

    @Test
    public void integerOverflowIsRejectedOnBothExportAndImport() {
        badInteger("int8", -129);
        badInteger("int8", 128);
        badInteger("int16", -32769);
        badInteger("int16", 32768);
        badInteger("int32", -2147483649L);
        badInteger("int32", 2147483648L);
        badInteger("timestamp", -1);
        badInteger("timestamp", 9223372037L);
        rejectCell(payload("9223372036854775808"), "int64");
        rejectCell(payload("-9223372036854775809"), "int64");
    }

    @Test
    public void integersRejectNonCanonicalAndNonIntegralForms() {
        for (String bad : new String[]{"", "+1", "01", "-0", "-01", "1.0", "1e3",
                " 1", "1 ", "1\n", "NaN", "Infinity", "１２"}) {
            rejectCell(payload(bad), "int64");
        }
    }

    @Test
    public void floatingPointExamplesMatchTheSpecifiedCsv() {
        assertEquals("V:M2ZiOTk5OTlhMDAwMDAwMA==",
                ValueCodec.encode(Value.fVal((double) 0.1f), "float"));
        assertEquals("V:M2ZiOTk5OTk5OTk5OTk5YQ==",
                ValueCodec.encode(Value.fVal(0.1), "double"));
        assertEquals("V:ODAwMDAwMDAwMDAwMDAwMA==",
                ValueCodec.encode(Value.fVal(-0.0), "double"));
        assertEquals("V:MDAwMDAwMDAwMDAwMDAwMA==",
                ValueCodec.encode(Value.fVal(0.0), "double"));
    }

    @Test
    public void finiteExtremaSubnormalsAndSignedZeroKeepTheirBits() {
        double[] values = {0.0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE,
                Double.MIN_NORMAL, -Double.MIN_NORMAL, Double.MAX_VALUE, -Double.MAX_VALUE,
                Float.MIN_VALUE, -Float.MIN_VALUE, Float.MIN_NORMAL, -Float.MIN_NORMAL,
                Float.MAX_VALUE, -Float.MAX_VALUE, 0.1f, 0.1, Math.nextUp(1.0), Math.nextDown(1.0)};
        for (String type : new String[]{"float", "double"}) {
            for (double value : values) {
                assertBitsEqual(value, roundTrip(Value.fVal(value), type).getFVal());
            }
        }
        assertNotEquals(ValueCodec.encode(Value.fVal(0.0), "double"),
                ValueCodec.encode(Value.fVal(-0.0), "double"));
    }

    @Test
    public void nonFiniteAndNanPayloadsAreExpressibleWithoutClaimingServerSupport() {
        for (long bits : new long[]{0x7ff0000000000000L, 0xfff0000000000000L,
                0x7ff8000000000000L, 0x7ff8000000000001L, 0xfff8000000000042L}) {
            double value = Double.longBitsToDouble(bits);
            assertEquals(bits, Double.doubleToRawLongBits(roundTrip(Value.fVal(value),
                    "double").getFVal()));
        }
    }

    @Test
    public void canonicalScalarValuesSurviveCsvAndNativeThriftTransport() throws Exception {
        // Canonical NaN is also stable when a FLOAT is stored as float32 and read as double.
        double canonicalNaN = Double.longBitsToDouble(0x7ff8000000000000L);
        assertBitsEqual(canonicalNaN, (double) (float) canonicalNaN);
        List<Double> values = new ArrayList<>(Arrays.asList(0.0, -0.0, Double.MIN_VALUE,
                -Double.MIN_VALUE, Double.MIN_NORMAL, Double.MAX_VALUE, -Double.MAX_VALUE,
                (double) Float.MIN_VALUE, (double) Float.MAX_VALUE, canonicalNaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY));
        Random random = new Random(0x7363616c6172L);
        while (values.size() < 1000) {
            double value = Double.longBitsToDouble(random.nextLong());
            if (!Double.isNaN(value)) {
                values.add(value);
            }
        }
        for (boolean compact : new boolean[]{false, true}) {
            for (double value : values) {
                Value decoded = roundTrip(Value.fVal(value), "double");
                Value transported = nativeProtocolRoundTrip(decoded, compact);
                assertEquals(Value.FVAL, transported.getSetField());
                assertBitsEqual(value, transported.getFVal());
            }
        }
        // This only establishes transport support. FLOAT +/-Infinity is rejected by storage.
    }

    @Test
    public void nonCanonicalNanBitsSurviveCsvButAreCanonicalizedByBundledThrift() throws Exception {
        for (boolean compact : new boolean[]{false, true}) {
            for (int i = 1; i <= 1000; i++) {
                long bits = ((i & 1) == 0 ? 0x7ff8000000000000L : 0xfff8000000000000L) | i;
                Value decoded = roundTrip(Value.fVal(Double.longBitsToDouble(bits)), "double");
                assertEquals(bits, Double.doubleToRawLongBits(decoded.getFVal()));
                Value transported = nativeProtocolRoundTrip(decoded, compact);
                assertEquals(0x7ff8000000000000L, Double.doubleToRawLongBits(transported.getFVal()));
            }
        }
    }

    @Test
    public void randomFloatingPointBitsRoundTripWithoutDecimalConversion() {
        Random random = new Random(0x4e6562756c61L);
        for (int i = 0; i < 8192; i++) {
            double value = Double.longBitsToDouble(random.nextLong());
            assertBitsEqual(value, roundTrip(Value.fVal(value), "double").getFVal());
        }
        for (int i = 0; i < 8192; i++) {
            double value = (double) Float.intBitsToFloat(random.nextInt());
            assertBitsEqual(value, roundTrip(Value.fVal(value), "float").getFVal());
        }
    }

    @Test
    public void floatingPointPayloadRejectsDecimalUppercaseAndWrongLength() {
        for (String bad : new String[]{"0.1", "NaN", "Infinity", "0", "800000000000000",
                "80000000000000000", "3FB999999999999A", "0x3fb999999999999a",
                " 3fb999999999999a", "gggggggggggggggg"}) {
            rejectCell(payload(bad), "double");
        }
    }

    @Test
    public void nullEmptyAndMarkerLikeStringsRemainDistinct() {
        assertEquals("N", ValueCodec.encode(Value.nVal(NullType.__NULL__), "string"));
        assertEquals("V:", ValueCodec.encode(bytes(""), "string"));
        assertEquals("V:Tg==", ValueCodec.encode(bytes("N"), "string"));
        assertEquals("V:TlVMTA==", ValueCodec.encode(bytes("NULL"), "string"));
        for (String text : new String[]{"", "N", "NULL", "V:", "V:Tg==", " null "}) {
            assertArrayEquals(text.getBytes(StandardCharsets.UTF_8),
                    roundTrip(bytes(text), "string").getSVal());
        }
    }

    @Test
    public void stringSpecialCharactersAndUnicodeAreByteExact() {
        String[] texts = {"a,b", "a\"b", "a'b", "a\\b", "a\rb", "a\nb", "a\r\nb",
                "a\tb", "abc\u0000def", "abc\\000def", "你好", "😀", "e\u0301", "é",
                " \t leading and trailing \r\n", "你好,\"A\"\\B\r\nC\tD\u0000😀"};
        for (String text : texts) {
            String cell = ValueCodec.encode(bytes(text), "string");
            assertArrayEquals(text.getBytes(StandardCharsets.UTF_8),
                    ValueCodec.decode(cell, "string").getSVal());
            assertFalse(cell.contains(","));
            assertFalse(cell.contains("\""));
            assertFalse(cell.contains("\r"));
            assertFalse(cell.contains("\n"));
        }
        assertEquals("V:YWJjAGRlZg==", ValueCodec.encode(bytes("abc\u0000def"), "string"));
        assertNotEquals(ValueCodec.encode(bytes("e\u0301"), "string"),
                ValueCodec.encode(bytes("é"), "string"));
    }

    @Test
    public void binaryStringPreservesEveryPossibleByte() {
        byte[] allBytes = new byte[256];
        for (int i = 0; i < allBytes.length; i++) {
            allBytes[i] = (byte) i;
        }
        assertArrayEquals(allBytes, roundTrip(Value.sVal(allBytes), "string").getSVal());
    }

    @Test
    public void invalidUtf8IsNeverDecodedAsText() {
        byte[][] samples = {{(byte) 0x80}, {(byte) 0xc0, (byte) 0xaf},
                {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
                {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80},
                {(byte) 0xff, (byte) 0xfe, 0, (byte) 0xc3, 0x28}};
        for (byte[] sample : samples) {
            assertArrayEquals(sample, roundTrip(Value.sVal(sample), "string").getSVal());
        }
    }

    @Test
    public void multiMegabyteStringsHaveNoLineWrappingOrTruncation() {
        byte[] data = new byte[2 * 1024 * 1024 + 7];
        new Random(73).nextBytes(data);
        String cell = ValueCodec.encode(Value.sVal(data), "string");
        assertEquals(2 + 4 * ((data.length + 2) / 3), cell.length());
        assertFalse(cell.contains("\n"));
        assertFalse(cell.contains("\r"));
        assertArrayEquals(data, ValueCodec.decode(cell, "string").getSVal());
    }

    @Test
    public void fixedStringChecksBytesAndNeverPadsOrTruncates() {
        assertArrayEquals(new byte[0], roundTrip(bytes(""), "fixed_string(1)").getSVal());
        assertArrayEquals("你好".getBytes(StandardCharsets.UTF_8),
                roundTrip(bytes("你好"), "fixed_string(6)").getSVal());
        rejectEncode(bytes("你好"), "fixed_string(5)");
        rejectCell(ValueCodec.encode(bytes("你好"), "string"), "fixed_string(5)");
        rejectEncode(bytes("abc\u0000def"), "fixed_string(10)");
        rejectCell(ValueCodec.encode(bytes("abc\u0000def"), "string"), "fixed_string(10)");
        byte[] invalidUtf8 = {(byte) 0xff, (byte) 0xfe};
        assertArrayEquals(invalidUtf8,
                roundTrip(Value.sVal(invalidUtf8), "fixed_string(2)").getSVal());
    }

    @Test
    public void dateSupportsNegativeYearsLeapDaysAndFullYearRange() {
        Date[] dates = {new Date((short) 2024, (byte) 2, (byte) 29),
                new Date((short) 0, (byte) 2, (byte) 29),
                new Date((short) -4, (byte) 2, (byte) 29),
                new Date(Short.MIN_VALUE, (byte) 1, (byte) 1),
                new Date(Short.MAX_VALUE, (byte) 12, (byte) 31)};
        for (Date date : dates) {
            assertEquals(date, roundTrip(Value.dVal(date), "date").getDVal());
        }
        assertEquals("V:WzIwMjQsMiwyOV0=", ValueCodec.encode(Value.dVal(dates[0]), "date"));
    }

    @Test
    public void timePreservesMicrosecondsWithoutTimezoneConversion() {
        for (int micros : new int[]{0, 1, 999, 1000, 123456, 999999}) {
            Time time = new Time((byte) 23, (byte) 59, (byte) 59, micros);
            assertEquals(time, roundTrip(Value.tVal(time), "time").getTVal());
        }
        Time midnight = new Time((byte) 0, (byte) 0, (byte) 0, 0);
        assertEquals(midnight, roundTrip(Value.tVal(midnight), "time").getTVal());
        assertEquals("V:WzEzLDE0LDE1LDEyMzQ1Nl0=", ValueCodec.encode(
                Value.tVal(new Time((byte) 13, (byte) 14, (byte) 15, 123456)), "time"));
    }

    @Test
    public void dateTimePreservesAllSevenFieldsIncludingYearBoundaries() {
        DateTime[] values = {new DateTime((short) 2024, (byte) 2, (byte) 29,
                (byte) 13, (byte) 14, (byte) 15, 123456),
                new DateTime(Short.MIN_VALUE, (byte) 1, (byte) 1,
                        (byte) 0, (byte) 0, (byte) 0, 1),
                new DateTime(Short.MAX_VALUE, (byte) 12, (byte) 31,
                        (byte) 23, (byte) 59, (byte) 59, 999999)};
        for (DateTime value : values) {
            assertEquals(value, roundTrip(Value.dtVal(value), "datetime").getDtVal());
        }
        assertEquals("V:WzIwMjQsMiwyOSwxMywxNCwxNSwxMjM0NTZd",
                ValueCodec.encode(Value.dtVal(values[0]), "datetime"));
    }

    @Test
    public void durationPreservesSignedFieldsAndDoesNotNormalize() {
        Duration[] values = {new Duration(259204, 500006, 2), new Duration(-1, 2, -3),
                new Duration(1, -2, 3), new Duration(0, 1000000, 0),
                new Duration(Long.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE),
                new Duration(Long.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)};
        for (Duration value : values) {
            assertEquals(value, roundTrip(Value.duVal(value), "duration").getDuVal());
        }
        assertEquals("V:WzIsMjU5MjA0LDUwMDAwNl0=",
                ValueCodec.encode(Value.duVal(values[0]), "duration"));
        rejectCell(payload("[2147483648,0,0]"), "duration");
        rejectCell(payload("[0,0,-2147483649]"), "duration");
    }

    @Test
    public void temporalPayloadRejectsBadDatesOverflowAndMalformedArrays() {
        for (String bad : new String[]{"[2023,2,29]", "[1900,2,29]", "[2024,0,1]",
                "[2024,13,1]", "[2024,1,0]", "[2024,4,31]", "[32768,1,1]",
                "[-32769,1,1]", "[2024,2]", "[2024,2,29,0]", "[2024,2,29,]",
                "[2024, 2,29]", "[2024,2.0,29]", "[2024,02,29]", "[2024,null,29]",
                "[2024,2,29] ", "{\"year\":2024}", "[]", "[2024,2,29]junk"}) {
            rejectCell(payload(bad), "date");
        }
        for (String bad : new String[]{"[24,0,0,0]", "[-1,0,0,0]", "[0,60,0,0]",
                "[0,0,60,0]", "[0,0,0,-1]", "[0,0,0,1000000]"}) {
            rejectCell(payload(bad), "time");
        }
        rejectEncode(Value.dVal(new Date((short) 2023, (byte) 2, (byte) 29)), "date");
        rejectEncode(Value.tVal(new Time((byte) 24, (byte) 0, (byte) 0, 0)), "time");
        rejectCell(payload("[2024,1,1,23,59,59,1000000]"), "datetime");
    }

    @Test
    public void databaseErrorNullsAndMissingValuesFailClosed() {
        for (NullType nullType : NullType.values()) {
            if (nullType != NullType.__NULL__) {
                rejectEncode(Value.nVal(nullType), "string");
            }
        }
        rejectEncode(null, "string");
        rejectEncode(new Value(), "string");
    }

    @Test
    public void nativeUnionTypeMustMatchSchemaWithoutImplicitCoercions() {
        rejectEncode(Value.iVal(1), "bool");
        rejectEncode(Value.bVal(true), "int64");
        rejectEncode(Value.iVal(1), "double");
        rejectEncode(Value.fVal(1), "int64");
        rejectEncode(bytes("1"), "int64");
        rejectEncode(Value.iVal(1), "string");
        rejectEncode(Value.iVal(0), "date");
        rejectEncode(bytes("12:00:00"), "time");
        rejectEncode(bytes("2024-01-01T00:00:00"), "datetime");
        rejectEncode(Value.iVal(0), "duration");
    }

    @Test
    public void cellsRequireCanonicalBase64AndExplicitMarkers() {
        for (String bad : new String[]{null, "", "NULL", "n", " N", "N ", "N:",
                "N:YWJj", "V", "v:YWJj", "V:YQ", "V:YQ=", "V:YQ===", "V:YR==",
                "V:YWJ=", "V:YQ==\n", "V:Y Q==", "V:-w==", "V:_w==", "V:====",
                "V:!@#$", "V:YQ==junk"}) {
            rejectCell(bad, "string");
        }
        // Empty Base64 is valid only for string-shaped payloads.
        for (String type : TYPES) {
            if (!type.equals("string") && !type.startsWith("fixed_string")) {
                rejectCell("V:", type);
            }
        }
        for (String bad : new String[]{"True", "FALSE", "1", "0", "true\n"}) {
            rejectCell(payload(bad), "bool");
        }
    }

    @Test
    public void supportedSchemaTypesAreCaseInsensitiveAndUnknownTypesAreRejected() {
        for (String type : TYPES) {
            ValueCodec.validateSupportedType(type);
            ValueCodec.validateSupportedType(" " + type.toUpperCase(java.util.Locale.ROOT) + " ");
        }
        assertEquals(ValueCodec.encode(Value.iVal(42), "int64"),
                ValueCodec.encode(Value.iVal(42), " INT "));
        for (String unsupported : Arrays.asList(null, "", "geography(any)", "geography(multipoint)",
                "list", "map", "set", "vertex", "unknown", "fixed_string", "fixed_string(0)",
                "fixed_string(-1)", "fixed_string(01)", "fixed_string(32768)",
                "fixed_string(9223372036854775808)")) {
            try {
                ValueCodec.validateSupportedType(unsupported);
                fail("Accepted unsupported schema type: " + unsupported);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().length() > 0);
            }
            rejectCell("N", unsupported);
        }
    }

    @Test
    public void geographyPointHasCanonicalShapeAndRawCoordinatePayload() {
        Value point = Value.ggVal(Geography.ptVal(new Point(new Coordinate(1, 2))));
        String expected = "[\"point\",[\"3ff0000000000000\",\"4000000000000000\"]]";
        assertEquals(payload(expected), ValueCodec.encode(point, "geography"));
        assertEquals(payload(expected), ValueCodec.encode(point, " Geography ( POINT ) "));
        assertGeography(point.getGgVal(), roundTrip(point, "geography(point)").getGgVal());
    }

    @Test
    public void geographyLineAndPolygonKeepRepeatedPointsClosureAndHoleOrder() {
        List<Coordinate> exterior = Arrays.asList(new Coordinate(-0.0, 0.0), new Coordinate(2, 0),
                new Coordinate(2, 0), new Coordinate(0, 2), new Coordinate(-0.0, 0.0));
        List<Coordinate> hole = Arrays.asList(new Coordinate(0.1, 0.1), new Coordinate(0.1, 0.2),
                new Coordinate(0.2, 0.1), new Coordinate(0.1, 0.1));
        Geography line = Geography.lsVal(new LineString(exterior));
        Geography polygon = Geography.pgVal(new Polygon(Arrays.asList(exterior, hole)));
        assertGeography(line, roundTrip(Value.ggVal(line), "geography(linestring)").getGgVal());
        assertGeography(polygon, roundTrip(Value.ggVal(polygon), "geography(polygon)").getGgVal());
        assertEquals(exterior.size(), ValueCodec.decode(ValueCodec.encode(Value.ggVal(line), "geography"),
                "geography").getGgVal().getLsVal().coordList.size());
        Geography reversedRings = Geography.pgVal(new Polygon(Arrays.asList(hole, exterior)));
        assertNotEquals(ValueCodec.encode(Value.ggVal(polygon), "geography"),
                ValueCodec.encode(Value.ggVal(reversedRings), "geography"));
    }

    @Test
    public void geographySchemaShapeMismatchAndWrongNativeTypeAreRejected() {
        Value[] values = {Value.ggVal(Geography.ptVal(new Point(new Coordinate(1, 2)))),
                Value.ggVal(Geography.lsVal(new LineString(Arrays.asList(new Coordinate(1, 2),
                        new Coordinate(3, 4))))),
                Value.ggVal(Geography.pgVal(new Polygon(Collections.emptyList())))};
        String[] shapes = {"geography(point)", "geography(linestring)", "geography(polygon)"};
        for (int i = 0; i < values.length; i++) {
            for (int j = 0; j < shapes.length; j++) {
                if (i != j) {
                    rejectEncode(values[i], shapes[j]);
                    rejectCell(ValueCodec.encode(values[i], "geography"), shapes[j]);
                }
            }
        }
        rejectEncode(bytes("POINT(1 2)"), "geography");
        rejectEncode(Value.iVal(1), "geography");
        rejectEncode(values[0], "string");
    }

    @Test
    public void geographyDoesNotNormalizeSignedZeroOrAdjacentFiniteDoubles() {
        for (long bits : new long[]{0L, Long.MIN_VALUE, 1L, 0x3ff0000000000001L}) {
            Geography point = Geography.ptVal(new Point(new Coordinate(Double.longBitsToDouble(bits), -0.0)));
            assertGeography(point, roundTrip(Value.ggVal(point), "geography(point)").getGgVal());
        }
        assertNotEquals(ValueCodec.encode(Value.ggVal(Geography.ptVal(new Point(new Coordinate(0.0, 0.0)))),
                "geography"), ValueCodec.encode(Value.ggVal(Geography.ptVal(new Point(
                        new Coordinate(-0.0, 0.0)))), "geography"));
    }

    @Test
    public void geographyNonFiniteCoordinatesAreRejectedOnBothExportAndImport() {
        for (long bits : new long[]{0x7ff0000000000000L, 0xfff0000000000000L,
                0x7ff8000000000042L, 0x7ff0000000000001L}) {
            double value = Double.longBitsToDouble(bits);
            rejectEncode(Value.ggVal(Geography.ptVal(new Point(new Coordinate(value, 0)))), "geography");
            rejectEncode(Value.ggVal(Geography.ptVal(new Point(new Coordinate(0, value)))), "geography");
            String hex = String.format(java.util.Locale.ROOT, "%016x", bits);
            rejectCell(payload("[\"point\",[\"" + hex + "\",\"0000000000000000\"]]"), "geography");
            rejectCell(payload("[\"point\",[\"0000000000000000\",\"" + hex + "\"]]"), "geography");
        }
    }

    @Test
    public void oneThousandPointLineAndPolygonShapesRoundTripBitForBit() {
        Random random = new Random(0x67656fL);
        for (int i = 0; i < 1000; i++) {
            List<Coordinate> first = randomCoordinates(random, 2 + i % 5);
            List<Coordinate> second = randomCoordinates(random, 3 + i % 7);
            Geography[] shapes = {Geography.ptVal(new Point(first.get(0))),
                    Geography.lsVal(new LineString(first)),
                    Geography.pgVal(new Polygon(Arrays.asList(first, second)))};
            for (Geography shape : shapes) {
                assertGeography(shape, roundTrip(Value.ggVal(shape), "geography").getGgVal());
            }
        }
    }

    @Test
    public void emptySequencesAreRetainedWithoutClaimingTheyAreValidDatabaseGeometries() {
        Geography line = Geography.lsVal(new LineString(Collections.emptyList()));
        Geography emptyPolygon = Geography.pgVal(new Polygon(Collections.emptyList()));
        Geography emptyRing = Geography.pgVal(new Polygon(Collections.singletonList(Collections.emptyList())));
        assertEquals(payload("[\"linestring\",[]]"), ValueCodec.encode(Value.ggVal(line), "geography"));
        assertGeography(line, roundTrip(Value.ggVal(line), "geography").getGgVal());
        assertGeography(emptyPolygon, roundTrip(Value.ggVal(emptyPolygon), "geography").getGgVal());
        assertGeography(emptyRing, roundTrip(Value.ggVal(emptyRing), "geography").getGgVal());
        assertNotEquals(ValueCodec.encode(Value.ggVal(emptyPolygon), "geography"),
                ValueCodec.encode(Value.ggVal(emptyRing), "geography"));
    }

    @Test
    public void geographyMissingShapeListsAndCoordinateComponentsFailClosed() {
        rejectEncode(Value.ggVal(new Geography()), "geography");
        rejectEncode(Value.ggVal(Geography.ptVal(new Point())), "geography");
        rejectEncode(Value.ggVal(Geography.ptVal(new Point(new Coordinate()))), "geography");
        rejectEncode(Value.ggVal(Geography.lsVal(new LineString())), "geography");
        rejectEncode(Value.ggVal(Geography.lsVal(new LineString(Collections.singletonList(null)))), "geography");
        rejectEncode(Value.ggVal(Geography.pgVal(new Polygon())), "geography");
        rejectEncode(Value.ggVal(Geography.pgVal(new Polygon(Collections.singletonList(null)))), "geography");
        Coordinate missingY = new Coordinate(1, 2);
        missingY.unsetY();
        rejectEncode(Value.ggVal(Geography.ptVal(new Point(missingY))), "geography");
    }

    @Test
    public void geographyParserRejectsNonCanonicalAndMalformedShapeTrees() {
        String good = "[\"point\",[\"3ff0000000000000\",\"4000000000000000\"]]";
        for (String malformed : new String[]{"", "[]", "null", "{}", "[\"point\",null]",
                "[\"point\",[1,2]]", "[\"point\",[\"0\",\"0\"]]", "[\"linestring\",null]",
                "[\"polygon\",[null]]", "[\"Point\",[]]", "[\"multipoint\",[]]",
                good + "\n", " " + good, good + "[]", good.replace(",", ", "),
                good.replace("3ff", "3FF"), good.replace("4000000000000000", "g000000000000000"),
                good.substring(0, good.length() - 1), good.replace("]]", ",]]"),
                good.replace("point", "point\\u0000")}) {
            rejectCell(payload(malformed), "geography");
        }
    }

    @Test
    public void generatedGeographyDecoderPreservesFiniteCoordinatesInBinaryAndCompactProtocols() throws Exception {
        List<Coordinate> coordinates = Arrays.asList(new Coordinate(-0.0, 0.0),
                new Coordinate(Math.nextUp(0.1), Double.MIN_VALUE), new Coordinate(180, -90));
        Geography[] shapes = {Geography.ptVal(new Point(coordinates.get(0))),
                Geography.lsVal(new LineString(coordinates)),
                Geography.pgVal(new Polygon(Arrays.asList(coordinates, coordinates)))};
        for (Geography shape : shapes) {
            for (boolean compact : new boolean[]{false, true}) {
                TMemoryBuffer buffer = new TMemoryBuffer(128);
                TProtocol writer = compact ? new TCompactProtocol(buffer) : new TBinaryProtocol(buffer);
                Value.ggVal(shape).write(writer);
                TProtocol reader = compact ? new TCompactProtocol(buffer) : new TBinaryProtocol(buffer);
                Value read = new Value();
                read.read(reader);
                assertEquals(Value.GGVAL, read.getSetField());
                assertGeography(shape, read.getGgVal());
            }
        }
    }

    private static List<Coordinate> randomCoordinates(Random random, int size) {
        List<Coordinate> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            result.add(new Coordinate(random.nextDouble() * 360 - 180,
                    random.nextDouble() * 180 - 90));
        }
        return result;
    }

    private static void assertGeography(Geography expected, Geography actual) {
        assertEquals(expected.getSetField(), actual.getSetField());
        switch (expected.getSetField()) {
            case Geography.PTVAL:
                assertCoordinate(expected.getPtVal().coord, actual.getPtVal().coord);
                break;
            case Geography.LSVAL:
                assertCoordinates(expected.getLsVal().coordList, actual.getLsVal().coordList);
                break;
            case Geography.PGVAL:
                List<List<Coordinate>> left = expected.getPgVal().coordListList;
                List<List<Coordinate>> right = actual.getPgVal().coordListList;
                assertEquals(left.size(), right.size());
                for (int i = 0; i < left.size(); i++) {
                    assertCoordinates(left.get(i), right.get(i));
                }
                break;
            default:
                fail("Unexpected test shape");
        }
    }

    private static void assertCoordinates(List<Coordinate> expected, List<Coordinate> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertCoordinate(expected.get(i), actual.get(i));
        }
    }

    private static void assertCoordinate(Coordinate expected, Coordinate actual) {
        assertBitsEqual(expected.x, actual.x);
        assertBitsEqual(expected.y, actual.y);
    }

    private static Value bytes(String text) {
        return Value.sVal(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String payload(String text) {
        return "V:" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Value roundTrip(Value value, String type) {
        String cell = ValueCodec.encode(value, type);
        Value decoded = ValueCodec.decode(cell, type);
        assertEquals(cell, ValueCodec.encode(decoded, type));
        return decoded;
    }

    private static Value nativeProtocolRoundTrip(Value value, boolean compact) throws Exception {
        TMemoryBuffer buffer = new TMemoryBuffer(64);
        value.write(compact ? new TCompactProtocol(buffer) : new TBinaryProtocol(buffer));
        Value read = new Value();
        read.read(compact ? new TCompactProtocol(buffer) : new TBinaryProtocol(buffer));
        return read;
    }

    private static void assertBitsEqual(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }

    private static void badInteger(String type, long value) {
        rejectEncode(Value.iVal(value), type);
        rejectCell(payload(Long.toString(value)), type);
    }

    private static void rejectEncode(Value value, String type) {
        try {
            ValueCodec.encode(value, type);
            fail("Encoded invalid value for " + type);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().length() > 0);
        }
    }

    private static void rejectCell(String cell, String type) {
        try {
            ValueCodec.decode(cell, type);
            fail("Decoded invalid cell for " + type + ": " + cell);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().length() > 0);
        }
    }
}
