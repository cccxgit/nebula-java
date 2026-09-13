package com.vesoft.nebula.verification;

import com.vesoft.nebula.Value;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Independent graph-key CSV decoding and byte-preserving nGQL identifier construction. */
public final class Identifiers {
    private static final Pattern INTEGER = Pattern.compile("(?:0|-?[1-9][0-9]*)");
    private static final Pattern FIXED_STRING = Pattern.compile("fixed_string\\(([1-9][0-9]*)\\)");

    private Identifiers() {
    }

    public static Value decodeVid(String cell, String vidType) {
        require(vidType != null, "Missing VID schema type");
        String type = vidType.trim().toLowerCase(Locale.ROOT);
        if (type.equals("int64") || type.equals("int")) {
            return Value.iVal(integer(payload(cell)));
        }
        Matcher matcher = FIXED_STRING.matcher(type);
        require(matcher.matches(), "VID schema must be INT64 or FIXED_STRING(N)");
        long capacity;
        try {
            capacity = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid VID byte capacity", e);
        }
        require(capacity > 0 && capacity <= Short.MAX_VALUE, "Invalid VID byte capacity");
        byte[] bytes = payload(cell);
        require(bytes.length <= capacity, "VID exceeds schema byte capacity");
        noNul(bytes);
        return Value.sVal(bytes);
    }

    public static Value decodeRank(String cell) {
        return Value.iVal(integer(payload(cell)));
    }

    /** Encodes INT64/string graph identifiers in the migration manifest's CSV cell format. */
    public static String cell(Value value) {
        identifier(value);
        if (value.getSetField() == Value.IVAL) {
            return encode(Long.toString(value.getIVal()).getBytes(StandardCharsets.US_ASCII));
        }
        noNul(value.getSVal());
        return encode(value.getSVal());
    }

    /** Requires graphd's octal escapes to be enabled; every byte uses exactly three digits. */
    public static String vidLiteral(Value value) {
        identifier(value);
        if (value.getSetField() == Value.IVAL) {
            if (value.getIVal() == Long.MIN_VALUE) {
                return "toInteger(\"-9223372036854775808\")";
            }
            return Long.toString(value.getIVal());
        }
        noNul(value.getSVal());
        StringBuilder result = new StringBuilder("\"");
        for (byte item : value.getSVal()) {
            int unsigned = item & 0xff;
            result.append('\\').append((char) ('0' + (unsigned >> 6)))
                    .append((char) ('0' + ((unsigned >> 3) & 7)))
                    .append((char) ('0' + (unsigned & 7)));
        }
        return result.append('"').toString();
    }

    public static String rankLiteral(Value value) {
        require(value != null && value.getFieldValue() != null && value.getSetField() == Value.IVAL,
                "Edge rank must be a native INT64");
        require(value.getIVal() != Long.MIN_VALUE,
                "Edge rank Long.MIN_VALUE cannot be expressed by the nGQL FETCH/INSERT rank grammar");
        return Long.toString(value.getIVal());
    }

    public static String quote(String name) {
        require(name != null && !name.isEmpty() && name.indexOf('`') < 0 && name.indexOf('\0') < 0,
                "Invalid nGQL identifier");
        utf8(name);
        return "`" + name + "`";
    }

    public static String nameCell(String name) {
        return encode(utf8(name));
    }

    /** Records a name exactly as returned by the wire protocol, without character conversion. */
    public static String nameCell(byte[] name) {
        require(name != null, "Missing schema name bytes");
        return encode(name);
    }

    public static String decodeName(String cell) {
        byte[] bytes = payload(cell);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Schema name is not valid UTF-8", e);
        }
    }

    private static byte[] utf8(String name) {
        require(name != null, "Missing schema name");
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(name));
            byte[] result = new byte[bytes.remaining()];
            bytes.get(result);
            return result;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Schema name contains malformed Unicode", e);
        }
    }

    private static byte[] payload(String cell) {
        require(cell != null && cell.startsWith("V:"),
                "Graph key/name requires V:<canonical Base64>; NULL and empty cells are invalid");
        String encoded = cell.substring(2);
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid graph-key Base64", e);
        }
        require(Base64.getEncoder().encodeToString(bytes).equals(encoded), "Non-canonical graph-key Base64");
        return bytes;
    }

    private static long integer(byte[] bytes) {
        for (byte item : bytes) {
            require(item >= 0, "Integer key must contain ASCII only");
        }
        String text = new String(bytes, StandardCharsets.US_ASCII);
        require(INTEGER.matcher(text).matches(), "Integer key must be canonical decimal INT64");
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Integer key exceeds INT64 range", e);
        }
    }

    private static String encode(byte[] bytes) {
        return "V:" + Base64.getEncoder().encodeToString(bytes);
    }

    private static void identifier(Value value) {
        require(value != null && value.getFieldValue() != null
                        && (value.getSetField() == Value.IVAL || value.getSetField() == Value.SVAL),
                "Graph key must be a non-NULL integer or raw string");
    }

    private static void noNul(byte[] bytes) {
        require(bytes != null, "Missing VID bytes");
        for (byte item : bytes) {
            require(item != 0, "NUL in VID violates the source-data prerequisite");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
