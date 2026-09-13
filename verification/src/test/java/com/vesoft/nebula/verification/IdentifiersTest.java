package com.vesoft.nebula.verification;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.vesoft.nebula.NullType;
import com.vesoft.nebula.Value;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;

public class IdentifiersTest {
    @Test
    public void int64VidAndRankCellsPreserveBoundaries() {
        for (long value : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, -1, 0, 1, 9007199254740993L}) {
            String cell = Identifiers.cell(Value.iVal(value));
            assertEquals(value, Identifiers.decodeVid(cell, "INT64").getIVal());
            assertEquals(value, Identifiers.decodeVid(cell, " int ").getIVal());
            assertEquals(value, Identifiers.decodeRank(cell).getIVal());
        }
        assertEquals("V:OTIyMzM3MjAzNjg1NDc3NTgwNw==", Identifiers.cell(Value.iVal(Long.MAX_VALUE)));
        assertEquals("V:LTkyMjMzNzIwMzY4NTQ3NzU4MDg=", Identifiers.cell(Value.iVal(Long.MIN_VALUE)));
    }

    @Test
    public void integerCellsRejectTruncationOverflowAndNonCanonicalNumbers() {
        for (String invalid : new String[]{"", "+1", "01", "-0", "1.0", "1e2", " 1", "1 ",
                "9223372036854775808", "-9223372036854775809", "true", "１"}) {
            String cell = payload(invalid.getBytes(StandardCharsets.UTF_8));
            reject(() -> Identifiers.decodeVid(cell, "int64"));
            reject(() -> Identifiers.decodeRank(cell));
        }
    }

    @Test
    public void stringVidPreservesArbitraryNonNulBytesAndEmptyString() {
        byte[] bytes = new byte[255];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i + 1);
        }
        String cell = Identifiers.cell(Value.sVal(bytes));
        assertArrayEquals(bytes, Identifiers.decodeVid(cell, "fixed_string(255)").getSVal());
        assertArrayEquals(new byte[0], Identifiers.decodeVid("V:", "FIXED_STRING(1)").getSVal());
        byte[] invalidUtf8 = {(byte) 0xff, (byte) 0xc0, (byte) 0xaf, (byte) 0xed, (byte) 0xa0};
        assertArrayEquals(invalidUtf8,
                Identifiers.decodeVid(Identifiers.cell(Value.sVal(invalidUtf8)), "fixed_string(5)").getSVal());
    }

    @Test
    public void stringVidLengthIsCheckedInBytes() {
        String cell = Identifiers.cell(bytes("你好"));
        assertArrayEquals("你好".getBytes(StandardCharsets.UTF_8),
                Identifiers.decodeVid(cell, "fixed_string(6)").getSVal());
        reject(() -> Identifiers.decodeVid(cell, "fixed_string(5)"));
        reject(() -> Identifiers.decodeVid(payload(new byte[32768]), "fixed_string(32767)"));
    }

    @Test
    public void nulVidAndWrongNativeTypesAreRejected() {
        Value nul = bytes("u\u0000A");
        reject(() -> Identifiers.cell(nul));
        reject(() -> Identifiers.vidLiteral(nul));
        reject(() -> Identifiers.decodeVid(payload(nul.getSVal()), "fixed_string(32)"));
        for (Value invalid : new Value[]{null, new Value(), Value.nVal(NullType.__NULL__),
                Value.nVal(NullType.BAD_DATA), Value.fVal(1), Value.bVal(true)}) {
            reject(() -> Identifiers.cell(invalid));
            reject(() -> Identifiers.vidLiteral(invalid));
            reject(() -> Identifiers.rankLiteral(invalid));
        }
        reject(() -> Identifiers.rankLiteral(bytes("1")));
    }

    @Test
    public void everyVidByteUsesExactlyThreeOctalDigits() {
        byte[] all = new byte[255];
        for (int i = 0; i < all.length; i++) {
            all[i] = (byte) (i + 1);
        }
        String literal = Identifiers.vidLiteral(Value.sVal(all));
        assertEquals('"', literal.charAt(0));
        assertEquals('"', literal.charAt(literal.length() - 1));
        assertEquals(2 + all.length * 4, literal.length());
        byte[] decoded = new byte[255];
        for (int i = 0; i < all.length; i++) {
            int start = 1 + i * 4;
            assertEquals('\\', literal.charAt(start));
            String digits = literal.substring(start + 1, start + 4);
            assertTrue(digits.matches("[0-7]{3}"));
            decoded[i] = (byte) Integer.parseInt(digits, 8);
        }
        assertArrayEquals(all, decoded);
        assertEquals("\"\"", Identifiers.vidLiteral(bytes("")));
        assertEquals("\"\\042\\134\\061\\012\"", Identifiers.vidLiteral(bytes("\"\\1\n")));
    }

    @Test
    public void vidAndRankLiteralHandleIntegerGrammarBoundariesExplicitly() {
        assertEquals("toInteger(\"-9223372036854775808\")", Identifiers.vidLiteral(Value.iVal(Long.MIN_VALUE)));
        assertEquals("9223372036854775807", Identifiers.vidLiteral(Value.iVal(Long.MAX_VALUE)));
        assertEquals("-1", Identifiers.vidLiteral(Value.iVal(-1)));
        for (long value : new long[]{Long.MIN_VALUE + 1, -1, 0, Long.MAX_VALUE}) {
            assertEquals(Long.toString(value), Identifiers.rankLiteral(Value.iVal(value)));
        }
        reject(() -> Identifiers.rankLiteral(Value.iVal(Long.MIN_VALUE)));
    }

    @Test
    public void graphCellsRejectNullAndMalformedBase64() {
        for (String invalid : new String[]{null, "", "N", "N:", "NULL", "v:YWJj", "V:YQ",
                "V:YQ=", "V:YQ===", "V:YR==", "V:YQ==\n", "V:Y Q==", "V:-w==", "V:_w==",
                "V:!@#$", "V:YQ==junk"}) {
            reject(() -> Identifiers.decodeVid(invalid, "fixed_string(32)"));
            reject(() -> Identifiers.decodeRank(invalid));
            reject(() -> Identifiers.decodeName(invalid));
        }
    }

    @Test
    public void unsupportedVidSchemasAndInvalidLengthsAreRejected() {
        for (String invalid : new String[]{null, "", "string", "int32", "bool", "double", "geography",
                "fixed_string", "fixed_string(0)", "fixed_string(-1)", "fixed_string(01)",
                "fixed_string(32768)", "fixed_string(9223372036854775808)"}) {
            reject(() -> Identifiers.decodeVid("V:YQ==", invalid));
        }
    }

    @Test
    public void schemaNameCellsPreserveUnicodeAndDoNotNormalize() {
        for (String name : new String[]{"", "person", "order", "中文😀", "e\u0301", "é", "a/b", "N"}) {
            assertEquals(name, Identifiers.decodeName(Identifiers.nameCell(name)));
            assertEquals(Identifiers.nameCell(name), Identifiers.nameCell(name.getBytes(StandardCharsets.UTF_8)));
        }
        assertNotEquals(Identifiers.nameCell("e\u0301"), Identifiers.nameCell("é"));
        assertEquals("V:cGVyc29u", Identifiers.nameCell("person"));
        assertEquals("V:", Identifiers.nameCell(""));
    }

    @Test
    public void rawSchemaNameCellsKeepInvalidUtf8ButTextDecodingRefusesReplacement() {
        byte[] invalid = {(byte) 0xff, (byte) 0xfe};
        assertEquals("V://4=", Identifiers.nameCell(invalid));
        reject(() -> Identifiers.decodeName(Identifiers.nameCell(invalid)));
        reject(() -> Identifiers.nameCell("bad\ud800"));
        reject(() -> Identifiers.nameCell((String) null));
        reject(() -> Identifiers.nameCell((byte[]) null));
    }

    @Test
    public void namesAreQuotedWithoutCaseFoldingAndUnsafeQuotesAreRejected() {
        assertEquals("`order`", Identifiers.quote("order"));
        assertEquals("`My Tag`", Identifiers.quote("My Tag"));
        assertEquals("`中文😀`", Identifiers.quote("中文😀"));
        for (String invalid : new String[]{null, "", "`", "tag`); DROP SPACE x;", "a\u0000b", "bad\ud800"}) {
            reject(() -> Identifiers.quote(invalid));
        }
    }

    private static Value bytes(String text) {
        return Value.sVal(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String payload(byte[] bytes) {
        return "V:" + Base64.getEncoder().encodeToString(bytes);
    }

    private static void reject(Runnable action) {
        try {
            action.run();
            fail("Accepted invalid graph identifier/name");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().length() > 0);
        }
    }
}
