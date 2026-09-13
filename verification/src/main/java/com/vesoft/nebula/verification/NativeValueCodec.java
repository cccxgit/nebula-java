package com.vesoft.nebula.verification;

import com.vesoft.nebula.Coordinate;
import com.vesoft.nebula.Date;
import com.vesoft.nebula.DateTime;
import com.vesoft.nebula.Duration;
import com.vesoft.nebula.Edge;
import com.vesoft.nebula.Geography;
import com.vesoft.nebula.NullType;
import com.vesoft.nebula.Tag;
import com.vesoft.nebula.Time;
import com.vesoft.nebula.Value;
import com.vesoft.nebula.Vertex;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/** Independent canonical representation of query results; no migration codecs are used. */
public final class NativeValueCodec {
    private NativeValueCodec() {
    }

    /** Encodes a scalar as ASCII JSON, preserving binary strings and floating-point raw bits. */
    public static String encode(Value value) {
        require(value != null && value.getFieldValue() != null, "Missing native value");
        switch (value.getSetField()) {
            case Value.NVAL:
                require(value.getNVal() == NullType.__NULL__,
                        "Error NULL cannot be compared as normal NULL: " + value.getNVal());
                return "[\"null\"]";
            case Value.BVAL:
                return "[\"bool\"," + value.isBVal() + "]";
            case Value.IVAL:
                return numbers("int", value.getIVal());
            case Value.FVAL:
                String bits = Long.toHexString(Double.doubleToRawLongBits(value.getFVal()));
                bits = "0000000000000000".substring(bits.length()) + bits;
                return "[\"float\",\"" + bits + "\"]";
            case Value.SVAL:
                return "[\"string\",\"" + base64(value.getSVal()) + "\"]";
            case Value.DVAL:
                Date date = value.getDVal();
                return numbers("date", date.year, date.month, date.day);
            case Value.TVAL:
                Time time = value.getTVal();
                return numbers("time", time.hour, time.minute, time.sec, time.microsec);
            case Value.DTVAL:
                DateTime dt = value.getDtVal();
                return numbers("datetime", dt.year, dt.month, dt.day,
                        dt.hour, dt.minute, dt.sec, dt.microsec);
            case Value.DUVAL:
                Duration duration = value.getDuVal();
                return numbers("duration", duration.months, duration.seconds, duration.microseconds);
            case Value.GGVAL:
                return geography(value.getGgVal());
            default:
                throw new IllegalArgumentException("Unsupported native scalar field: " + value.getSetField());
        }
    }

    /**
     * Canonical, individually comparable fields of a fetched vertex or edge. Empty tags have
     * an explicit presence entry. Names are encoded from their original bytes. Edge type IDs
     * are deliberately omitted because they differ between independently created spaces.
     */
    public static SortedMap<String, String> fields(Value value) {
        require(value != null && value.getFieldValue() != null, "Missing graph value");
        SortedMap<String, String> result = new TreeMap<>();
        if (value.getSetField() == Value.VVAL) {
            Vertex vertex = value.getVVal();
            identifier(vertex.vid);
            put(result, "$vid", encode(vertex.vid));
            require(vertex.tags != null, "Missing vertex tags");
            for (Tag tag : vertex.tags) {
                require(tag != null, "Missing tag");
                String prefix = "tag/" + base64(tag.name);
                put(result, prefix, "present");
                properties(result, prefix + "/prop/", tag.props);
            }
        } else if (value.getSetField() == Value.EVAL) {
            Edge edge = value.getEVal();
            identifier(edge.src);
            identifier(edge.dst);
            require(edge.isSetRanking(), "Missing edge rank");
            put(result, "$src", encode(edge.src));
            put(result, "$dst", encode(edge.dst));
            put(result, "$name", "[\"string\",\"" + base64(edge.name) + "\"]");
            put(result, "$rank", encode(Value.iVal(edge.ranking)));
            properties(result, "prop/", edge.props);
        } else {
            throw new IllegalArgumentException("Expected a native VERTEX or EDGE value");
        }
        return result;
    }

    private static void properties(SortedMap<String, String> result, String prefix,
            Map<byte[], Value> properties) {
        require(properties != null, "Missing property map");
        for (Map.Entry<byte[], Value> property : properties.entrySet()) {
            put(result, prefix + base64(property.getKey()), encode(property.getValue()));
        }
    }

    /** Preserve the source's returned geometry, including ordering, closure and coordinate bits. */
    private static String geography(Geography value) {
        require(value != null && value.getFieldValue() != null, "Missing Geography shape");
        StringBuilder result = new StringBuilder("[\"geography\",");
        switch (value.getSetField()) {
            case Geography.PTVAL:
                result.append("\"point\",");
                coordinate(result, value.getPtVal().coord);
                break;
            case Geography.LSVAL:
                result.append("\"linestring\",");
                sequence(result, value.getLsVal().coordList);
                break;
            case Geography.PGVAL:
                result.append("\"polygon\",[");
                List<List<Coordinate>> rings = value.getPgVal().coordListList;
                require(rings != null, "Missing Geography polygon rings");
                for (int i = 0; i < rings.size(); i++) {
                    if (i > 0) {
                        result.append(',');
                    }
                    sequence(result, rings.get(i));
                }
                result.append(']');
                break;
            default:
                throw new IllegalArgumentException("Unsupported Geography shape: " + value.getSetField());
        }
        return result.append(']').toString();
    }

    private static void sequence(StringBuilder result, List<Coordinate> coordinates) {
        require(coordinates != null, "Missing Geography coordinate sequence");
        result.append('[');
        for (int i = 0; i < coordinates.size(); i++) {
            if (i > 0) {
                result.append(',');
            }
            coordinate(result, coordinates.get(i));
        }
        result.append(']');
    }

    private static void coordinate(StringBuilder result, Coordinate coordinate) {
        require(coordinate != null && coordinate.isSetX() && coordinate.isSetY(),
                "Missing Geography coordinate component");
        require(Double.isFinite(coordinate.x) && Double.isFinite(coordinate.y),
                "Geography coordinate must be finite");
        String x = String.format(java.util.Locale.ROOT, "%016x", Double.doubleToRawLongBits(coordinate.x));
        String y = String.format(java.util.Locale.ROOT, "%016x", Double.doubleToRawLongBits(coordinate.y));
        result.append("[\"").append(x).append("\",\"").append(y).append("\"]");
    }

    private static void identifier(Value value) {
        require(value != null && value.getFieldValue() != null
                        && (value.getSetField() == Value.IVAL || value.getSetField() == Value.SVAL),
                "Graph identifier must be a native integer or string");
    }

    private static String base64(byte[] bytes) {
        require(bytes != null, "Missing raw bytes");
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String numbers(String type, long... values) {
        StringBuilder result = new StringBuilder("[\"").append(type).append('"');
        for (long value : values) {
            result.append(",\"").append(value).append('"');
        }
        return result.append(']').toString();
    }

    private static void put(SortedMap<String, String> result, String key, String value) {
        require(!result.containsKey(key), "Duplicate native field name: " + key);
        result.put(key, value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
