package com.vesoft.nebula.migration;

import com.vesoft.nebula.Date;
import com.vesoft.nebula.DateTime;
import com.vesoft.nebula.Duration;
import com.vesoft.nebula.NullType;
import com.vesoft.nebula.Time;
import com.vesoft.nebula.Value;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.net.Session;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;

/** Real-server samples with independent expectations, before any CSV codec is used. */
public final class AcceptanceSamples {
    public static final String[] NAMES = {"b", "i8", "i16", "i32", "i64", "f32", "f64",
        "s", "fs", "date_value", "time_value", "datetime_value", "ts", "dur"};
    public static final String[] TYPES = {"bool", "int8", "int16", "int32", "int64", "float",
        "double", "string", "fixed_string(128)", "date", "time", "datetime", "timestamp",
        "duration"};

    private AcceptanceSamples() { }

    public static final class Expected {
        public String table;
        public boolean edge;
        public String label;
        public List<Value> keys;
        public String[] columns;
        public List<Value> values;
    }

    public static final class Fixture {
        public final List<Expected> records = new ArrayList<>();
        public final List<String> cases = new ArrayList<>();
        public long propertyComparisons;
    }

    private static final class Sample {
        private final String label;
        private final List<Value> input;

        private Sample(String label, List<Value> input) {
            this.label = label;
            this.input = input;
        }
    }

    public static Fixture populate(Session session, String space, boolean integerVid,
                                   long schemaWaitMillis) throws Exception {
        run(session, "USE " + quote(space));
        if (!integerVid) {
            MigrationEngine.verifyStringLiteralSupport(session);
        }
        StringBuilder properties = new StringBuilder();
        for (int i = 0; i < NAMES.length; i++) {
            if (i > 0) {
                properties.append(',');
            }
            properties.append(quote(NAMES[i])).append(' ').append(TYPES[i]).append(" NULL");
        }
        run(session, "CREATE TAG all_types(" + properties + ")");
        run(session, "CREATE EDGE all_edges(" + properties + ")");
        run(session, "CREATE TAG bare_tag()");
        run(session, "CREATE EDGE bare_edge()");
        run(session, "CREATE TAG empty_tag(s string)");
        run(session, "CREATE EDGE empty_edge(s string)");
        run(session, "CREATE TAG alias_tag(label string)");
        run(session, "CREATE TAG `tag with space`(`a b` string NOT NULL DEFAULT \"default\", "
            + "`count` int DEFAULT 42)");
        Thread.sleep(schemaWaitMillis);
        Fixture fixture = new Fixture();
        List<Sample> samples = samples();
        List<Value> ids = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            ids.add(integerVid ? Value.iVal(i + 100L) : str("case_" + i));
        }
        if (integerVid) {
            ids.set(0, Value.iVal(Long.MIN_VALUE));
            ids.set(1, Value.iVal(Long.MAX_VALUE));
            ids.set(2, Value.iVal(0));
            ids.set(3, Value.iVal(-1));
        } else {
            ids.set(0, str("id'\"\\\r\n😀e\u0301"));
            ids.set(1, Value.sVal(new byte[] {'i', 'd', (byte) 0xff, (byte) 0xc0, (byte) 0xaf}));
            ids.set(2, str(repeat("v", 128)));
            ids.set(3, str("é"));
            ids.set(4, str("e\u0301"));
            ids.set(5, str(""));
        }
        for (int i = 0; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            fixture.cases.add(sample.label);
            add(fixture, session, "all_types", false, sample.label,
                Arrays.asList(ids.get(i)), NAMES, sample.input, true);
            // Every data case is independently exercised on an edge as well.
            // This server's rank grammar rejects Long.MIN_VALUE; exercise its writable
            // lower boundary here and separately assert the rejected boundary in acceptance.
            long rank = i == 0 ? Long.MIN_VALUE + 1 : i == 1 ? Long.MAX_VALUE : i - 20L;
            Value dst = i % 7 == 0 ? ids.get(i) : ids.get((i + 1) % ids.size());
            add(fixture, session, "all_edges", true, sample.label,
                Arrays.asList(ids.get(i), dst, Value.iVal(rank)), NAMES, sample.input, true);
            if ((i + 1) % 20 == 0) {
                System.out.println("ACCEPTANCE inserted " + (i + 1) + "/" + samples.size()
                    + " paired property cases in " + space);
            }
        }
        // Distinct ranks on exactly the same endpoints; no implicit edge deduplication is allowed.
        for (long rank : new long[] {-1, 0, 1}) {
            add(fixture, session, "all_edges", true, "parallel_edge_rank_" + rank,
                Arrays.asList(ids.get(0), ids.get(1), Value.iVal(rank)), NAMES, baseline(), true);
        }
        for (int i = 0; i < 5; i++) {
            add(fixture, session, "alias_tag", false, "multiple_tags_" + i,
                Arrays.asList(ids.get(i)), new String[] {"label"},
                Arrays.asList(str("alias," + i)), false);
        }
        add(fixture, session, "bare_tag", false, "zero_property_tag",
            Arrays.asList(ids.get(0)), new String[0], new ArrayList<Value>(), false);
        add(fixture, session, "bare_edge", true, "zero_property_edge",
            Arrays.asList(ids.get(0), ids.get(0), Value.iVal(-7)),
            new String[0], new ArrayList<Value>(), false);
        add(fixture, session, "tag with space", false, "quoted_schema_names",
            Arrays.asList(ids.get(0)), new String[] {"a b", "count"},
            Arrays.asList(str("quote'\"\\\r\n\u0000"), Value.iVal(99)), false);
        return fixture;
    }

    private static List<Sample> samples() {
        List<Sample> result = new ArrayList<>();
        result.add(new Sample("baseline", baseline()));
        List<Value> allNull = new ArrayList<>();
        for (String ignored : NAMES) {
            allNull.add(Value.nVal(NullType.__NULL__));
        }
        result.add(new Sample("all_nullable_properties", allNull));
        for (int column = 0; column < NAMES.length; column++) {
            change(result, "null_" + NAMES[column], column, Value.nVal(NullType.__NULL__));
        }
        change(result, "bool_false", 0, Value.bVal(false));
        long[][] limits = {{-128, 127}, {-32768, 32767}, {Integer.MIN_VALUE, Integer.MAX_VALUE},
            {Long.MIN_VALUE, Long.MAX_VALUE}};
        for (int column = 1; column <= 4; column++) {
            for (long number : new long[] {limits[column - 1][0], limits[column - 1][1], -1, 0, 1}) {
                change(result, NAMES[column] + "_" + number, column, Value.iVal(number));
            }
        }
        double[] floats = {Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_NORMAL,
            -Float.MIN_NORMAL, Float.MIN_VALUE, -Float.MIN_VALUE, 0.0, -0.0, 0.1,
            1.23456789, Math.nextUp(1.0f), Math.nextDown(1.0f)};
        double[] doubles = {Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_NORMAL,
            -Double.MIN_NORMAL, Double.MIN_VALUE, -Double.MIN_VALUE, 0.0, -0.0, 0.1,
            1.23456789, Math.nextUp(1.0), Math.nextDown(1.0)};
        String[] floatNames = {"positive_max", "negative_max", "positive_min_normal",
            "negative_min_normal", "positive_min_subnormal", "negative_min_subnormal",
            "positive_zero", "negative_zero", "decimal_0_1", "decimal_1_23456789",
            "next_up_one", "next_down_one"};
        for (int i = 0; i < floats.length; i++) {
            change(result, "float_" + floatNames[i], 5, Value.fVal(floats[i]));
            change(result, "double_" + floatNames[i], 6, Value.fVal(doubles[i]));
        }
        String[] strings = {"", "NULL", "N", "V:", "'\"", "\\", "\\n\\000",
            "\r", "\n", "\r\n", "\t", "\u0000", "abc\u0000def", "😀🧑‍💻",
            "é", "e\u0301", "你好,\"A\"\\B\r\nC\tD\u0000😀", " a ", "\u2028\u2029",
            "=1+1", "\ufeffBOM-as-data", "end\u0000"};
        String[] stringNames = {"empty", "literal_NULL", "literal_N", "literal_V_prefix",
            "quotes", "backslash", "literal_escape_text", "CR", "LF", "CR_LF", "TAB",
            "NUL_only", "embedded_NUL", "emoji_ZWJ", "NFC", "NFD_combining", "mixed_specials",
            "leading_trailing_space", "unicode_line_separators", "formula_text", "BOM_data",
            "trailing_NUL"};
        for (int i = 0; i < strings.length; i++) {
            change(result, "string_" + stringNames[i], 7, str(strings[i]));
        }
        byte[] allBytes = new byte[256];
        for (int i = 0; i < allBytes.length; i++) {
            allBytes[i] = (byte) i;
        }
        change(result, "string_all_256_byte_values", 7, Value.sVal(allBytes));
        byte[][] invalid = {{(byte) 0xff, (byte) 0xfe}, {(byte) 0xc0, (byte) 0xaf},
            {(byte) 0xe2, (byte) 0x82}, {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
            {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, {(byte) 0x80}};
        for (int i = 0; i < invalid.length; i++) {
            change(result, "invalid_utf8_" + i, 7, Value.sVal(invalid[i]));
        }
        byte[] large = new byte[1024 * 1024 + 17];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i % 256);
        }
        change(result, "binary_string_1MiB_plus_17", 7, Value.sVal(large));
        change(result, "utf8_string_1MiB", 7, str(repeat("😀", 262144)));
        int fixedCase = 0;
        for (String fixed : new String[] {"", " A ", "'\"\\\r\n😀e\u0301",
                repeat("a", 128), repeat("😀", 32)}) {
            change(result, "fixed_string_case_" + fixedCase++ + "_bytes_"
                    + fixed.getBytes(StandardCharsets.UTF_8).length,
                8, str(fixed));
        }
        for (int year : new int[] {-32768, -1, 0, 2024, 9999, 32767}) {
            change(result, "date_year_" + year, 9,
                Value.dVal(new Date((short) year, (byte) 1, (byte) 1)));
        }
        for (int micro : new int[] {0, 1, 999, 1000, 123456, 999999}) {
            change(result, "time_micro_" + micro, 10,
                Value.tVal(new Time((byte) 23, (byte) 59, (byte) 59, micro)));
            change(result, "datetime_micro_" + micro, 11,
                Value.dtVal(new DateTime((short) 2024, (byte) 2, (byte) 29,
                    (byte) 23, (byte) 59, (byte) 59, micro)));
        }
        for (long ts : new long[] {0, 1, 1709164800L, 2147483647L, 2147483648L, 9223372036L}) {
            change(result, "timestamp_" + ts, 12, Value.iVal(ts));
        }
        Duration[] durations = {new Duration(0, 0, 0), new Duration(259204, 500006, 2),
            new Duration(-2, -3, -1), new Duration(2, -3, 1),
            new Duration(Long.MAX_VALUE, 999999, Integer.MAX_VALUE),
            new Duration(Long.MIN_VALUE, -999999, Integer.MIN_VALUE)};
        for (int i = 0; i < durations.length; i++) {
            change(result, "duration_" + i, 13, Value.duVal(durations[i]));
        }
        return result;
    }

    private static List<Value> baseline() {
        return new ArrayList<>(Arrays.asList(Value.bVal(true), Value.iVal(12), Value.iVal(1234),
            Value.iVal(123456), Value.iVal(9007199254740993L), Value.fVal(0.1), Value.fVal(0.1),
            str("baseline"), str("A001"), Value.dVal(new Date((short) 2024, (byte) 2, (byte) 29)),
            Value.tVal(new Time((byte) 13, (byte) 14, (byte) 15, 123456)),
            Value.dtVal(new DateTime((short) 2024, (byte) 2, (byte) 29,
                (byte) 13, (byte) 14, (byte) 15, 123456)), Value.iVal(1709164800L),
            Value.duVal(new Duration(259204, 500006, 2))));
    }

    private static void change(List<Sample> samples, String label, int column, Value value) {
        List<Value> values = baseline();
        values.set(column, value);
        samples.add(new Sample(label, values));
    }

    private static void add(Fixture fixture, Session session, String table, boolean edge,
                            String label, List<Value> keys, String[] columns, List<Value> input,
                            boolean normalizeFloat) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("src", keys.get(0));
        String keyExpression = MigrationEngine.vidLiteral(keys.get(0));
        if (edge) {
            params.put("dst", keys.get(1));
            params.put("rank", keys.get(2));
            keyExpression += "->" + MigrationEngine.vidLiteral(keys.get(1))
                + "@" + MigrationEngine.rankLiteral(keys.get(2));
        }
        StringBuilder columnSql = new StringBuilder();
        StringBuilder valueSql = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                columnSql.append(',');
                valueSql.append(',');
            }
            columnSql.append(quote(columns[i]));
            valueSql.append("$p").append(i);
            params.put("p" + i, input.get(i));
        }
        String sql = "INSERT " + (edge ? "EDGE " : "VERTEX ") + quote(table)
            + "(" + columnSql + ") VALUES " + keyExpression + ":(" + valueSql + ")";
        ResultSet rs = session.executeWithParameter(sql, params);
        Assert.assertTrue("Source INSERT failed for " + label + ": " + rs.getErrorMessage(),
            rs.isSucceeded());
        Expected expected = new Expected();
        expected.table = table;
        expected.edge = edge;
        expected.label = label;
        expected.keys = keys;
        expected.columns = columns;
        expected.values = new ArrayList<>(input);
        if (normalizeFloat && expected.values.get(5).getSetField() == Value.FVAL) {
            expected.values.set(5, Value.fVal((double) (float) input.get(5).getFVal()));
        }
        fixture.records.add(expected);
    }

    /** Read each independently known source key via FETCH, avoiding codec-based self-validation. */
    public static long verifyExpected(Session session, String space, Fixture fixture) throws Exception {
        run(session, "USE " + quote(space));
        long comparisons = 0;
        for (Expected expected : fixture.records) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("src", expected.keys.get(0));
            String keySql = MigrationEngine.vidLiteral(expected.keys.get(0));
            if (expected.edge) {
                params.put("dst", expected.keys.get(1));
                params.put("rank", expected.keys.get(2));
                keySql += "->" + MigrationEngine.vidLiteral(expected.keys.get(1))
                    + "@" + MigrationEngine.rankLiteral(expected.keys.get(2));
            }
            StringBuilder yield = new StringBuilder();
            for (int i = 0; i < expected.columns.length; i++) {
                if (i > 0) {
                    yield.append(',');
                }
                yield.append(quote(expected.table)).append('.').append(quote(expected.columns[i]))
                    .append(" AS p").append(i);
            }
            if (expected.columns.length == 0) {
                yield.append(expected.edge ? "rank(edge)" : "id(vertex)");
            }
            ResultSet result = session.executeWithParameter("FETCH PROP ON " + quote(expected.table)
                + " " + keySql + " YIELD " + yield, params);
            Assert.assertTrue(expected.label + " FETCH: " + result.getErrorMessage(), result.isSucceeded());
            Assert.assertEquals(expected.label + " row count", 1, result.rowsSize());
            for (int i = 0; i < expected.values.size(); i++) {
                assertNativeValue(expected.label + "/" + expected.columns[i],
                    expected.values.get(i), result.getRows().get(0).getValues().get(i));
                comparisons++;
            }
        }
        fixture.propertyComparisons = comparisons;
        return comparisons;
    }

    public static void assertNativeValue(String label, Value expected, Value actual) {
        Assert.assertEquals(label + " union type", expected.getSetField(), actual.getSetField());
        switch (expected.getSetField()) {
            case Value.SVAL:
                Assert.assertArrayEquals(label + " bytes", expected.getSVal(), actual.getSVal());
                break;
            case Value.FVAL:
                Assert.assertEquals(label + " IEEE754 bits", Double.doubleToRawLongBits(expected.getFVal()),
                    Double.doubleToRawLongBits(actual.getFVal()));
                break;
            case Value.NVAL:
                Assert.assertEquals(label, expected.getNVal(), actual.getNVal());
                break;
            case Value.BVAL:
                Assert.assertEquals(label, expected.isBVal(), actual.isBVal());
                break;
            case Value.IVAL:
                Assert.assertEquals(label, expected.getIVal(), actual.getIVal());
                break;
            case Value.DVAL:
                Assert.assertEquals(label, expected.getDVal(), actual.getDVal());
                break;
            case Value.TVAL:
                Assert.assertEquals(label, expected.getTVal(), actual.getTVal());
                break;
            case Value.DTVAL:
                Assert.assertEquals(label, expected.getDtVal(), actual.getDtVal());
                break;
            case Value.DUVAL:
                Assert.assertEquals(label, expected.getDuVal(), actual.getDuVal());
                break;
            default:
                Assert.fail("Unsupported expected type: " + expected.getSetField());
        }
    }

    public static void run(Session session, String statement) throws Exception {
        ResultSet result = session.execute(statement);
        Assert.assertTrue(statement + ": " + result.getErrorMessage(), result.isSucceeded());
    }

    public static String quote(String name) {
        return "`" + name.replace("`", "\\`") + "`";
    }

    private static Value str(String text) {
        return Value.sVal(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String repeat(String value, int count) {
        StringBuilder text = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            text.append(value);
        }
        return text.toString();
    }
}
