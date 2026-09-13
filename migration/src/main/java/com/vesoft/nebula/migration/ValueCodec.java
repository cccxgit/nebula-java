package com.vesoft.nebula.migration;

import com.vesoft.nebula.Date;
import com.vesoft.nebula.DateTime;
import com.vesoft.nebula.Duration;
import com.vesoft.nebula.NullType;
import com.vesoft.nebula.Time;
import com.vesoft.nebula.Value;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lossless, canonical CSV cells for native storage scan values.
 *
 * <p>The schema supplies the type: normal NULL is {@code N}, and a non-null value is
 * {@code V:} followed by padded, unwrapped standard Base64. Strings are raw bytes.
 * Floating-point payloads are the scanned double's raw 64 bits as 16 lowercase hex
 * characters, including for FLOAT columns. This codec does not guarantee that a
 * database server will accept non-finite values when replayed.
 */
public final class ValueCodec {
    private static final long MAX_TIMESTAMP = Long.MAX_VALUE / 1_000_000_000L;
    private static final Pattern FIXED_STRING = Pattern.compile("fixed_string\\(([1-9][0-9]*)\\)");
    private static final Pattern INTEGER = Pattern.compile("(?:0|-?[1-9][0-9]*)");
    private static final Pattern FLOAT_BITS = Pattern.compile("[0-9a-f]{16}");

    private ValueCodec() {
    }

    public static void validateSupportedType(String schemaType) {
        type(schemaType);
    }

    public static String encode(Value value, String schemaType) {
        String type = type(schemaType);
        if (value == null || value.getFieldValue() == null) {
            throw invalid("Missing native value; missing data is not NULL");
        }
        if (value.getSetField() == Value.NVAL) {
            if (value.getNVal() != NullType.__NULL__) {
                throw invalid("Database error value cannot be encoded as NULL: " + value.getNVal());
            }
            return "N";
        }

        byte[] payload;
        switch (type) {
            case "bool":
                requireField(value, Value.BVAL, type);
                payload = ascii(Boolean.toString(value.isBVal()));
                break;
            case "int8":
            case "int16":
            case "int32":
            case "int64":
            case "timestamp":
                requireField(value, Value.IVAL, type);
                checkInteger(value.getIVal(), type);
                payload = ascii(Long.toString(value.getIVal()));
                break;
            case "float":
            case "double":
                requireField(value, Value.FVAL, type);
                String hex = Long.toHexString(Double.doubleToRawLongBits(value.getFVal()));
                payload = ascii("0000000000000000".substring(hex.length()) + hex);
                break;
            case "date":
                requireField(value, Value.DVAL, type);
                Date date = value.getDVal();
                checkDate(date.year, date.month, date.day);
                payload = array(date.year, date.month, date.day);
                break;
            case "time":
                requireField(value, Value.TVAL, type);
                Time time = value.getTVal();
                checkTime(time.hour, time.minute, time.sec, time.microsec);
                payload = array(time.hour, time.minute, time.sec, time.microsec);
                break;
            case "datetime":
                requireField(value, Value.DTVAL, type);
                DateTime dateTime = value.getDtVal();
                checkDate(dateTime.year, dateTime.month, dateTime.day);
                checkTime(dateTime.hour, dateTime.minute, dateTime.sec, dateTime.microsec);
                payload = array(dateTime.year, dateTime.month, dateTime.day, dateTime.hour,
                        dateTime.minute, dateTime.sec, dateTime.microsec);
                break;
            case "duration":
                requireField(value, Value.DUVAL, type);
                Duration duration = value.getDuVal();
                payload = array(duration.months, duration.seconds, duration.microseconds);
                break;
            default:
                requireField(value, Value.SVAL, type);
                payload = value.getSVal();
                checkString(payload, type);
        }
        return "V:" + Base64.getEncoder().encodeToString(payload);
    }

    public static Value decode(String cell, String schemaType) {
        String type = type(schemaType);
        if ("N".equals(cell)) {
            return Value.nVal(NullType.__NULL__);
        }
        if (cell == null || !cell.startsWith("V:")) {
            throw invalid("Cell must be N or V:<canonical Base64>; an empty cell is invalid");
        }
        String encoded = cell.substring(2);
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid Base64 payload", e);
        }
        if (!Base64.getEncoder().encodeToString(payload).equals(encoded)) {
            throw invalid("Non-canonical Base64 payload (padding and unused bits must be canonical)");
        }
        if (type.equals("string") || type.startsWith("fixed_string(")) {
            checkString(payload, type);
            return Value.sVal(payload);
        }
        String text = asciiText(payload);
        switch (type) {
            case "bool":
                if (!text.equals("true") && !text.equals("false")) {
                    throw invalid("BOOL payload must be true or false");
                }
                return Value.bVal(Boolean.parseBoolean(text));
            case "int8":
            case "int16":
            case "int32":
            case "int64":
            case "timestamp":
                long integer = parseInteger(text);
                checkInteger(integer, type);
                return Value.iVal(integer);
            case "float":
            case "double":
                if (!FLOAT_BITS.matcher(text).matches()) {
                    throw invalid("Floating-point payload must contain 16 lowercase hex digits");
                }
                return Value.fVal(Double.longBitsToDouble(Long.parseUnsignedLong(text, 16)));
            case "date":
                long[] date = parseArray(text, 3);
                checkDate(date[0], date[1], date[2]);
                return Value.dVal(new Date((short) date[0], (byte) date[1], (byte) date[2]));
            case "time":
                long[] time = parseArray(text, 4);
                checkTime(time[0], time[1], time[2], time[3]);
                return Value.tVal(new Time((byte) time[0], (byte) time[1], (byte) time[2],
                        (int) time[3]));
            case "datetime":
                long[] dt = parseArray(text, 7);
                checkDate(dt[0], dt[1], dt[2]);
                checkTime(dt[3], dt[4], dt[5], dt[6]);
                return Value.dtVal(new DateTime((short) dt[0], (byte) dt[1], (byte) dt[2],
                        (byte) dt[3], (byte) dt[4], (byte) dt[5], (int) dt[6]));
            case "duration":
                long[] duration = parseArray(text, 3);
                range(duration[0], Integer.MIN_VALUE, Integer.MAX_VALUE, "duration months");
                range(duration[2], Integer.MIN_VALUE, Integer.MAX_VALUE, "duration microseconds");
                return Value.duVal(new Duration(duration[1], (int) duration[2], (int) duration[0]));
            default:
                throw invalid("Unsupported schema type: " + type);
        }
    }

    private static String type(String schemaType) {
        if (schemaType == null) {
            throw invalid("Schema type is required");
        }
        String normalized = schemaType.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("int")) {
            return "int64";
        }
        switch (normalized) {
            case "bool":
            case "int8":
            case "int16":
            case "int32":
            case "int64":
            case "float":
            case "double":
            case "string":
            case "date":
            case "time":
            case "datetime":
            case "timestamp":
            case "duration":
                return normalized;
            default:
                Matcher matcher = FIXED_STRING.matcher(normalized);
                if (matcher.matches()) {
                    long length = parseInteger(matcher.group(1));
                    range(length, 1, Short.MAX_VALUE, "FIXED_STRING length");
                    return normalized;
                }
                throw invalid("Unsupported schema type: " + schemaType);
        }
    }

    private static void requireField(Value value, int expected, String type) {
        if (value.getSetField() != expected) {
            throw invalid("Native Value field " + value.getSetField() + " does not match " + type);
        }
    }

    private static void checkInteger(long value, String type) {
        switch (type) {
            case "int8":
                range(value, Byte.MIN_VALUE, Byte.MAX_VALUE, type);
                break;
            case "int16":
                range(value, Short.MIN_VALUE, Short.MAX_VALUE, type);
                break;
            case "int32":
                range(value, Integer.MIN_VALUE, Integer.MAX_VALUE, type);
                break;
            case "timestamp":
                range(value, 0, MAX_TIMESTAMP, type);
                break;
            default:
                break;
        }
    }

    private static void checkString(byte[] bytes, String type) {
        if (bytes == null) {
            throw invalid("Missing string bytes");
        }
        if (type.startsWith("fixed_string(")) {
            int length = Integer.parseInt(type.substring(13, type.length() - 1));
            if (bytes.length > length) {
                throw invalid("String exceeds " + type + " byte capacity");
            }
            for (byte b : bytes) {
                if (b == 0) {
                    throw invalid("NUL in FIXED_STRING violates the source-data prerequisite");
                }
            }
        }
    }

    private static void checkDate(long year, long month, long day) {
        range(year, Short.MIN_VALUE, Short.MAX_VALUE, "year");
        range(month, 1, 12, "month");
        range(day, 1, 31, "day");
        try {
            LocalDate.of((int) year, (int) month, (int) day);
        } catch (DateTimeException e) {
            throw invalid("Invalid calendar date", e);
        }
    }

    private static void checkTime(long hour, long minute, long second, long microsecond) {
        range(hour, 0, 23, "hour");
        range(minute, 0, 59, "minute");
        range(second, 0, 59, "second");
        range(microsecond, 0, 999999, "microsecond");
    }

    private static void range(long value, long minimum, long maximum, String name) {
        if (value < minimum || value > maximum) {
            throw invalid(name + " out of range [" + minimum + "," + maximum + "]: " + value);
        }
    }

    private static long parseInteger(String text) {
        if (!INTEGER.matcher(text).matches()) {
            throw invalid("Expected canonical decimal integer");
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw invalid("Integer exceeds signed 64-bit range", e);
        }
    }

    private static long[] parseArray(String text, int count) {
        if (!text.startsWith("[") || !text.endsWith("]")) {
            throw invalid("Expected compact integer array");
        }
        String[] fields = text.substring(1, text.length() - 1).split(",", -1);
        if (fields.length != count) {
            throw invalid("Expected " + count + " integer fields");
        }
        long[] result = new long[count];
        for (int i = 0; i < count; i++) {
            result[i] = parseInteger(fields[i]);
        }
        return result;
    }

    private static byte[] array(long... fields) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < fields.length; i++) {
            if (i != 0) {
                result.append(',');
            }
            result.append(fields[i]);
        }
        return ascii(result.append(']').toString());
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static String asciiText(byte[] bytes) {
        for (byte b : bytes) {
            if (b < 0) {
                throw invalid("Non-string payload must contain ASCII only");
            }
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException invalid(String message, Exception cause) {
        return new IllegalArgumentException(message, cause);
    }
}
