#!/usr/bin/env python3
"""Generate reproducible nebula-console fixtures; Python standard library only.

The generated files never execute themselves. Run spaceFile, wait for propagation,
run schemaFile, wait again, then run dataFile. Each line is one complete statement.
"""

import argparse
import collections
import hashlib
import json
from pathlib import Path


MIN_I64 = -(1 << 63)
MAX_I64 = (1 << 63) - 1
ALL_BYTES = bytes(range(256))


def quote(name):
    if not name or "`" in name or "\x00" in name:
        raise ValueError("Unsupported schema identifier")
    return "`" + name + "`"


def literal(value):
    """Every byte gets three octal digits, including valid and invalid UTF-8."""
    if isinstance(value, str):
        value = value.encode("utf-8")
    return '"' + "".join("\\%03o" % byte for byte in value) + '"'


def integer(value):
    if value == MIN_I64:
        return 'toInteger("-9223372036854775808")'
    return str(value)


def float_expr(value):
    return 'toFloat("%s")' % value


def geo(wkt):
    return 'ST_GeogFromText("%s")' % wkt


def time_expr(microseconds, hour=23, minute=59, second=59):
    return "time({hour:%d,minute:%d,second:%d,millisecond:%d,microsecond:%d})" % (
        hour, minute, second, microseconds // 1000, microseconds % 1000)


def datetime_expr(year, microseconds, month=2, day=28):
    return ("datetime({year:%d,month:%d,day:%d,hour:23,minute:59,second:59,"
            "millisecond:%d,microsecond:%d})") % (
        year, month, day, microseconds // 1000, microseconds % 1000)


def duration_expr(months, seconds, microseconds):
    return "duration({months:%d,seconds:%s,microseconds:%d})" % (
        months, integer(seconds), microseconds)


POINTS = [
    "POINT(0 0)", "POINT(-0 -0)", "POINT(180 90)", "POINT(-180 -90)",
    "POINT(179.99999999999997 0.000000000000001)",
    "POINT(-179.99999999999997 -0.000000000000001)",
    "POINT(121.47370123456789 31.23040123456789)", "POINT(0 90)", "POINT(0 -90)"]
LINES = [
    "LINESTRING(0 0,1 1)", "LINESTRING(179 0,-179 0)",
    "LINESTRING(-180 -80,-90 -20,0 0,90 20,180 80)",
    "LINESTRING(-0 -0,0.000000000000001 0.000000000000001,1 1)",
    "LINESTRING(121.47370123456789 31.23040123456789,121.57370123456789 31.33040123456789)"]
POLYGONS = [
    "POLYGON((0 0,2 0,2 2,0 0))",
    "POLYGON((0 0,0 2,2 2,2 0,0 0))",
    "POLYGON((0 0,10 0,10 10,0 10,0 0),(2 2,2 4,4 4,4 2,2 2))",
    "POLYGON((179 -1,-179 -1,-179 1,179 1,179 -1))",
    "POLYGON((0.000000000000001 0,1.000000000000001 0,1 1,0.000000000000001 0))"]


def all_columns():
    return [("b", "BOOL"), ("i8", "INT8"), ("i16", "INT16"), ("i32", "INT32"),
            ("i64", "INT64"), ("f32", "FLOAT"), ("f64", "DOUBLE"), ("s", "STRING"),
            ("fs", "FIXED_STRING(128)"), ("d", "DATE"), ("t", "TIME"),
            ("dt", "DATETIME"), ("ts", "TIMESTAMP"), ("du", "DURATION"),
            ("gp", "GEOGRAPHY(POINT)"), ("gl", "GEOGRAPHY(LINESTRING)"),
            ("ga", "GEOGRAPHY(POLYGON)"), ("g", "GEOGRAPHY")]


def baseline_values(index):
    return ["true" if index % 2 else "false", str(index % 256 - 128),
            str(index * 61 % 65536 - 32768), str(index * 1234567 % 4294967296 - 2147483648),
            integer([MIN_I64, MAX_I64, 9007199254740993, -1, 0][index % 5]),
            float_expr(["0.1", "-0.0", "1.401298464324817e-45"][index % 3]),
            float_expr(["0.1", "-0.0", "4.9406564584124654e-324"][index % 3]),
            literal("混合,'\"\\\r\n\t\x00😀e\u0301"), literal("A001😀"),
            "date({year:2024,month:2,day:29})", time_expr(index * 997 % 1000000),
            datetime_expr(2024, index * 997 % 1000000, 2, 29), "1709164800",
            duration_expr(index % 12 - 6, index - 500, index * 97 - 50000),
            geo(POINTS[index % len(POINTS)]), geo(LINES[index % len(LINES)]),
            geo(POLYGONS[index % len(POLYGONS)]), geo((POINTS + LINES + POLYGONS)[index % 19])]


def build_scenarios():
    """Return the complete flat matrix. Each row receives 1,000 vertices and edges."""
    result = []

    def add(case_id, description, column_type="STRING", variants=None, categories=None,
            columns=None, mode="normal", negative=False):
        result.append({"scenarioId": case_id, "description": description,
                       "columns": columns if columns is not None else [("value", column_type)],
                       "variants": variants or [("baseline", [literal("baseline")])],
                       "categories": categories or [case_id], "mode": mode, "negative": negative})

    def scalar(case_id, description, datatype, values, labels=None):
        add(case_id, description, datatype,
            [(labels[i] if labels else str(i), [value]) for i, value in enumerate(values)],
            [datatype.split("(")[0].lower()])

    add("vid_values", "VID全范围代表值：整数上下界或空串、1..256字节、1..255单字节、特殊字符及无效UTF-8",
        mode="vid", categories=["vid", "boundary", "unicode", "binary"])
    scalar("bool_values", "BOOL真假值", "BOOL", ["false", "true"], ["false", "true"])
    for bits in (8, 16, 32, 64):
        low, high = -(1 << (bits - 1)), (1 << (bits - 1)) - 1
        values = [low, low + 1, -1, 0, 1, high - 1, high]
        if bits == 64:
            values += [-9007199254740993, -9007199254740992, 9007199254740992, 9007199254740993]
        scalar("int%d_values" % bits, "INT%d上下界、相邻值、零和正负值" % bits,
               "INT%d" % bits, [integer(v) for v in values], [str(v) for v in values])
    finite = {
        "float": ["3.4028234663852886e38", "-3.4028234663852886e38", "1.1754943508222875e-38",
                  "-1.1754943508222875e-38", "1.401298464324817e-45", "-1.401298464324817e-45",
                  "0.1", "1.0000001192092896", "0.9999999403953552"],
        "double": ["1.7976931348623157e308", "-1.7976931348623157e308", "2.2250738585072014e-308",
                   "-2.2250738585072014e-308", "4.9406564584124654e-324", "-4.9406564584124654e-324",
                   "0.1", "1.0000000000000002", "0.9999999999999999"]}
    for kind, values in finite.items():
        scalar(kind + "_finite", kind.upper() + "最大有限值、最小正规及次正规数、小数和相邻值",
               kind.upper(), [float_expr(v) for v in values], values)
        scalar(kind + "_signed_zero", kind.upper() + "正零和负零按IEEE754校验",
               kind.upper(), [float_expr("0.0"), float_expr("-0.0")], ["positive_zero", "negative_zero"])
        special_values = ["NaN"] if kind == "float" else ["NaN", "Infinity", "-Infinity"]
        special_labels = ["NaN"] if kind == "float" else ["NaN", "positive_infinity", "negative_infinity"]
        scalar(kind + "_nonfinite", kind.upper()
               + (" NaN；FLOAT Infinity由存储拒绝，另列负向" if kind == "float"
                  else " NaN、正负Infinity；采用源原生读取值为基准"),
               kind.upper(), [float_expr(v) for v in special_values], special_labels)
    special = [("quotes", "'\""), ("backslash", "\\"), ("literal_escapes", "\\n\\000"),
               ("comma", ","), ("CR", "\r"), ("LF", "\n"), ("CRLF", "\r\n"), ("TAB", "\t"),
               ("NUL", "\x00"), ("embedded_NUL", "abc\x00def"), ("trailing_NUL", "end\x00"),
               ("NFC", "é"), ("NFD", "e\u0301"), ("emoji_ZWJ", "😀🧑‍💻"),
               ("invisible", "\u200b\u2028\u2029\ufeff"), ("whitespace", "  a \t "),
               ("csv_formula", '=SUM(A1:A2),"x"'), ("mixed", "你好,'\"\\\r\n\t\x00😀")]
    scalar("string_special", "STRING所有特殊字符类别且不做Unicode规范化", "STRING",
           [literal(v) for _, v in special], [k for k, _ in special])
    scalar("string_all_bytes", "每条STRING均包含全部256字节，包含NUL及不可解码字节", "STRING",
           [literal(ALL_BYTES)], ["all_256_byte_values"])
    invalid = [b"\xff\xfe", b"\xc0\xaf", b"\xe2\x82", b"\xed\xa0\x80",
               b"\xf4\x90\x80\x80", b"\x80", b"a\xff\x00b"]
    scalar("string_invalid_utf8", "无效UTF-8、截断序列、过长编码及代理区字节", "STRING",
           [literal(v) for v in invalid], [v.hex() for v in invalid])
    scalar("string_long", "每条64KiB长STRING，以lpad服务端生成ASCII、UTF-8或全字节混合",
           "STRING", ['lpad("",65536,%s)' % literal(v) for v in [b"A", "😀".encode(), ALL_BYTES]],
           ["ascii_64KiB", "utf8_64KiB", "all_bytes_64KiB"])
    scalar("string_empty", "STRING空值长度为0，与NULL区分", "STRING", ['""'], ["empty_string"])
    scalar("string_literal_null", "数据库NULL、空串、字面NULL/N/V:之间不混淆", "STRING",
           ["NULL", '""', literal("NULL"), literal("N"), literal("V:")],
           ["database_null", "empty", "literal_NULL", "literal_N", "literal_V_prefix"])
    fixed = [(k, v) for k, v in special if "NUL" not in k and k != "mixed"]
    scalar("fixed_string_special", "FIXED_STRING保留特殊字符，不含源接口不支持的NUL",
           "FIXED_STRING(128)", [literal(v) for _, v in fixed], [k for k, _ in fixed])
    scalar("fixed_string_capacity", "FIXED_STRING(128)空、1、127、128字节及恰好128字节UTF-8",
           "FIXED_STRING(128)", [literal(v) for v in ["", "a", "a" * 127, "a" * 128, "😀" * 32]],
           ["0_bytes", "1_byte", "127_bytes", "128_ascii_bytes", "128_utf8_bytes"])
    add("all_types_null", "所有持久化属性同时为数据库NULL，含全部地理形状约束",
        columns=all_columns(), variants=[("all_null", ["NULL"] * len(all_columns()))],
        categories=["null", "all_types"])
    scalar("date_values", "DATE负年、0年、闰日及16位年份边界", "DATE",
           ["date({year:%d,month:%d,day:%d})" % date for date in
            [(-32768, 1, 1), (-1, 12, 31), (0, 1, 1), (2024, 2, 29), (9999, 12, 31), (32767, 12, 31)]])
    micros = [0, 1, 999, 1000, 123456, 999998, 999999]
    scalar("time_microseconds", "TIME午夜、最后一秒及微秒边界", "TIME",
           [time_expr(m) for m in micros] + [time_expr(0, 0, 0, 0)],
           ["micro_%d" % m for m in micros] + ["midnight"])
    scalar("datetime_values", "DATETIME年份边界、闰日与微秒完整保存", "DATETIME",
           [datetime_expr(2024, m, 2, 29) for m in micros]
           + [datetime_expr(y, 999999, 12, 31) for y in [-32768, -1, 0, 9999, 32767]])
    scalar("timestamp_values", "TIMESTAMP纪元、2038边界及服务端可写高值", "TIMESTAMP",
           [str(v) for v in [0, 1, 1709164800, 2147483647, 2147483648, 9223372036]])
    durations = [(0, 0, 0), (2, 259204, 500006), (-1, -2, -3), (1, 2, -3),
                 (2147483647, MAX_I64, 999999), (-2147483648, MIN_I64, -999999)]
    scalar("duration_values", "DURATION月份/秒/微秒、混合符号与边界，不折算月份", "DURATION",
           [duration_expr(*v) for v in durations], [str(v) for v in durations])
    duration_micros = [-2147483648, -2147483647, -1000000, -999999, -1, 0, 1,
                       999999, 1000000, 2147483646, 2147483647]
    scalar("duration_microsecond_bounds", "DURATION微秒原始int32上下界、相邻值与跨秒值，不归一化",
           "DURATION", [duration_expr(0, 0, value) for value in duration_micros],
           ["microseconds_%d" % value for value in duration_micros])
    for case, datatype, values, description in [
            ("geography_point", "GEOGRAPHY(POINT)", POINTS, "POINT经纬度极限、负零和高精度"),
            ("geography_line", "GEOGRAPHY(LINESTRING)", LINES, "LINESTRING多点、日期线和高精度"),
            ("geography_polygon", "GEOGRAPHY(POLYGON)", POLYGONS, "POLYGON内环、方向归一化、日期线和高精度"),
            ("geography_mixed", "GEOGRAPHY", POINTS + LINES + POLYGONS, "泛型GEOGRAPHY混存三种形状")]:
        scalar(case, description + "；以源原生读取值为比较基准", datatype,
               [geo(v) for v in values], values)
    add("zero_properties", "1000个零属性tag与1000条零属性edge", columns=[], variants=[("empty", [])],
        categories=["zero_property", "tag_presence", "edge_presence"])
    add("multiple_tags", "每个VID同时具有数据tag和零属性extra_tag，保留tag集合", mode="multi",
        categories=["multiple_tags", "zero_property"])
    add("edge_rank_bounds", "边rank可表达上下界、-1、0、1及大整数", mode="ranks",
        categories=["edge_rank", "boundary"])
    add("self_loops", "1000个顶点各自带一条自环", mode="self", categories=["self_loop"])
    add("parallel_edges", "1000条边共用相同端点，用1000个rank区分；其他顶点保持独立存在", mode="parallel",
        categories=["parallel_edges", "isolated_vertices", "edge_rank"])
    add("quoted_schema", "带空格/中文/关键字的Schema名称和属性名称", mode="quoted",
        columns=[("name with space", "STRING"), ("属性", "STRING"), ("limit", "INT64")],
        variants=[("quoted_names", [literal("a,'\"\\\r\n\x00"), literal("中文😀"), "42"])],
        categories=["schema_names", "unicode", "keywords"])
    add("not_null_defaults", "NOT NULL属性省略写入时使用确定性DEFAULT，搬迁后原值和Schema一致",
        mode="defaults", columns=[("required_int", "INT64"), ("required_text", "STRING"),
                                  ("required_bool", "BOOL"), ("required_date", "DATE")],
        variants=[("all_properties_omitted", [])], categories=["not_null", "default_values", "omitted_properties"])
    add("combined_types", "每条记录同时包含全部18种声明类型/地理约束，交叉组合值", columns=all_columns(),
        mode="combined", categories=["all_types", "combinations"])
    add("pagination", "7个分区各自分页、1000点和1000边；runner分别用limit=1,7,127,1000,1001",
        mode="pagination", categories=["pagination", "partitions", "end_of_scan"])
    for case_id, desc, columns, mode in [
            ("reject_vid_domain", "字符串VID超过256字节或整数VID超出INT64，每次写入均应拒绝", [("value", "STRING")], "bad_vid"),
            ("reject_int8_range", "INT8属性128/-129，存储必须拒绝越界值", [("value", "INT8")], "bad_int8"),
            ("reject_float_infinity", "FLOAT属性正负Infinity，存储范围检查必须拒绝", [("value", "FLOAT")], "bad_float_infinity"),
            ("reject_rank_min", "当前服务端rank语法不接受Long.MIN_VALUE，每条请求必须拒绝", [], "bad_rank"),
            ("reject_geography", "非法经纬度、非法线/多边形写入NOT NULL地理属性必须拒绝", [("value", "GEOGRAPHY")], "bad_geo")]:
        add(case_id, desc, columns=columns, mode=mode, negative=True, categories=["negative", "rejected_input"])
    return result


def _vids(vid_type, rows, special):
    if vid_type == "INT64":
        values = [MIN_I64, MAX_I64, 0, -1, 1, MIN_I64 + 1, MAX_I64 - 1,
                  -9007199254740993, 9007199254740993] if special else []
        seen = set(values)
        for value in range(100, rows + 100):
            if value not in seen:
                values.append(value)
        return [integer(v) for v in values[:rows]]
    raw = []
    if special:
        raw = [b"", b"v" * 256, "'\"\\\r\n\t😀".encode(), "é".encode(), "e\u0301".encode(),
               b" ", b"\xff\xfe", b"\xc0\xaf", b"\xe2\x82", b"\xed\xa0\x80"]
        raw += [bytes([i]) for i in range(1, 256)]
        raw += [b"v" * length for length in range(1, 257)]
    raw += [("v%05d" % i).encode() for i in range(rows)]
    unique = list(dict.fromkeys(raw))[:rows]
    assert len(unique) == rows and all(len(v) <= 256 and b"\x00" not in v for v in unique)
    return [literal(v) for v in unique]


def _edge_key(scenario, vids, index):
    mode = scenario["mode"]
    src = vids[0] if mode == "parallel" else vids[index]
    dst = vids[1] if mode == "parallel" else vids[index if mode == "self" else (index + 1) % len(vids)]
    rank = index - len(vids) // 2
    if mode == "ranks":
        rank = [MIN_I64 + 1, MIN_I64 + 2, -9007199254740993, -1, 0, 1,
                9007199254740993, MAX_I64 - 1, MAX_I64][index % 9]
    return "%s->%s@%s" % (src, dst, rank)


def _write(output, relative, lines):
    path = output / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    data = ("\n".join(lines) + "\n").encode("utf-8")
    path.write_bytes(data)
    return {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def render_sample_queries(metadata, space):
    """Use the exact same independent FETCH query list against either cluster space."""
    return "USE %s;\n%s\n" % (quote(space), "\n".join(metadata["sampleQueries"]))


def generate(output_dir, vid_type, scenario_id, space_name, rows=1000):
    """Write one scenario and return metadata with paths relative to output_dir."""
    if rows < 1000:
        raise ValueError("Every declared scenario requires at least 1000 data items")
    normalized = vid_type.upper()
    if normalized in ("FIXED_STRING", "FIXED_STRING(256)", "STRING", "S"):
        vid_type = "FIXED_STRING(256)"
    elif normalized in ("INT", "INT64", "I"):
        vid_type = "INT64"
    else:
        raise ValueError("VID type must be INT64 or FIXED_STRING(256)")
    scenarios = {s["scenarioId"]: s for s in build_scenarios()}
    scenario = scenarios[scenario_id]
    suffix = "i" if vid_type == "INT64" else "s"
    case_id = scenario_id + "_" + suffix
    directory = Path(case_id)
    output = Path(output_dir)
    mode, negative = scenario["mode"], scenario["negative"]
    tag_name = "tag with space" if mode == "quoted" else "case_tag"
    edge_name = "edge with space" if mode == "quoted" else "case_edge"
    columns = scenario["columns"]
    schema = ",".join(quote(name) + " " + datatype + (" NOT NULL" if mode == "bad_geo" else " NULL")
                      for name, datatype in columns)
    properties = ",".join(quote(name) for name, _ in columns)
    if mode == "defaults":
        default_values = ["42", literal("default,'\"\\\r\n😀"), "true", 'date("2024-02-29")']
        schema = ",".join(quote(name) + " " + datatype + " NOT NULL DEFAULT " + default
                          for (name, datatype), default in zip(columns, default_values))
        properties = ""
    use = "USE %s;" % quote(space_name)
    space_lines = ["CREATE SPACE %s(partition_num=7,replica_factor=1,vid_type=%s);" %
                   (quote(space_name), vid_type)]
    schema_lines = [use, "CREATE TAG %s(%s);" % (quote(tag_name), schema),
                    "CREATE EDGE %s(%s);" % (quote(edge_name), schema)]
    if mode == "multi":
        schema_lines.append("CREATE TAG extra_tag();")
    vids = _vids(vid_type, rows, mode == "vid")
    data = [use]
    counts = collections.Counter()
    for index in range(rows):
        label, values = scenario["variants"][index % len(scenario["variants"])]
        if mode == "combined":
            label, values = "combination_%d" % (index % 19), baseline_values(index)
        counts[label] += 1
        if negative:
            if mode == "bad_vid":
                bad_key = "9223372036854775808" if vid_type == "INT64" else literal(b"x" * 257)
                statement = "INSERT VERTEX %s(%s) VALUES %s:(%s);" % (
                    quote(tag_name), properties, bad_key, literal("rejected"))
            elif mode == "bad_int8":
                statement = "INSERT VERTEX %s(%s) VALUES %s:(%s);" % (
                    quote(tag_name), properties, vids[index], 128 if index % 2 else -129)
            elif mode == "bad_rank":
                statement = "INSERT EDGE %s() VALUES %s->%s@-9223372036854775808:();" % (
                    quote(edge_name), vids[index], vids[(index + 1) % rows])
            elif mode == "bad_float_infinity":
                statement = "INSERT VERTEX %s(%s) VALUES %s:(%s);" % (
                    quote(tag_name), properties, vids[index],
                    float_expr("Infinity" if index % 2 else "-Infinity"))
            else:
                invalid = ["POINT(181 0)", "POINT(0 91)", "LINESTRING(0 0)", "POLYGON((0 0,1 1,0 0))"]
                statement = "INSERT VERTEX %s(%s) VALUES %s:(%s);" % (
                    quote(tag_name), properties, vids[index], geo(invalid[index % len(invalid)]))
            data.append(statement)
            continue
        csv = ",".join(values)
        data.append("INSERT VERTEX %s(%s) VALUES %s:(%s);" % (quote(tag_name), properties, vids[index], csv))
        if mode == "multi":
            data.append("INSERT VERTEX extra_tag() VALUES %s:();" % vids[index])
        data.append("INSERT EDGE %s(%s) VALUES %s:(%s);" %
                    (quote(edge_name), properties, _edge_key(scenario, vids, index), csv))
    queries = []
    sample_indices = [0, rows // 2, rows - 1]
    for index in sample_indices:
        queries.append("FETCH PROP ON * %s YIELD vertex AS v;" % vids[index])
        queries.append("FETCH PROP ON %s %s YIELD edge AS e;" %
                       (quote(edge_name), _edge_key(scenario, vids, index)))
    expected_rows = rows
    metadata = {
        "id": case_id, "scenarioId": scenario_id, "vidType": vid_type,
        "sourceSpace": space_name, "targetSpace": space_name + "_target", "rows": rows,
        "description": scenario["description"], "categories": scenario["categories"],
        "negative": negative, "expectedRejects": rows if negative else 0,
        "spaceFile": str(directory / "space.ngql"), "schemaFile": str(directory / "schema.ngql"),
        "dataFile": str(directory / "data.ngql"), "sampleFile": str(directory / "sample.ngql"),
        "sampleQueries": queries, "sampleIndices": sample_indices,
        "expected": {"vertices": expected_rows, "edges": expected_rows,
                     "tags": {tag_name: expected_rows}, "edgeTypes": {edge_name: expected_rows}},
        "columns": [{"name": name, "type": datatype} for name, datatype in columns],
        "variants": [label for label, _ in scenario["variants"]], "variantCounts": dict(counts),
        "variantDefinitions": [{"id": label, "expressions": values,
                                "recordsPerTable": counts.get(label, 0)}
                               for label, values in scenario["variants"]],
        "recordSemantics": "Each positive scenario has >=1000 distinct VID vertices and >=1000 edge keys; representative values cycle within that scenario.",
        "scanLimits": [1, 7, 127, 1000, 1001] if mode == "pagination" else [127],
        "sourceNormalization": "Source native FETCH values are authoritative; schema FLOAT rounding and valid GEOGRAPHY normalization may occur on first INSERT.",
        "files": {}}
    if negative:
        controls = [use]
        if mode == "bad_geo":
            control_values = [geo("POINT(0 0)")]
        elif mode == "bad_int8":
            control_values = ["0"]
        elif mode == "bad_float_infinity":
            control_values = [float_expr("0.1")]
        elif mode == "bad_rank":
            control_values = []
        else:
            control_values = [literal("positive_control")]
        for index in range(rows):
            csv = ",".join(control_values)
            controls.append("INSERT VERTEX %s(%s) VALUES %s:(%s);" %
                            (quote(tag_name), properties, vids[index], csv))
            controls.append("INSERT EDGE %s(%s) VALUES %s:(%s);" %
                            (quote(edge_name), properties, _edge_key(scenario, vids, index), csv))
        metadata["controlFile"] = str(directory / "control.ngql")
        metadata["files"][metadata["controlFile"]] = _write(output, metadata["controlFile"], controls)
        metadata["negativeExpectedBefore"] = dict(metadata["expected"])
        metadata["negativeExpectedAfter"] = dict(metadata["expected"])
        metadata["expectedAddedByRejectedRequests"] = {"vertices": 0, "edges": 0}
    if mode == "defaults":
        metadata["defaults"] = [{"name": name, "expression": default, "nullable": False}
                                for (name, _), default in zip(columns, default_values)]
    if mode == "multi":
        metadata["expected"]["tags"]["extra_tag"] = rows
    if mode == "vid":
        metadata["vidCoverage"] = {
            "distinctVids": rows,
            "integerValues": [str(v) for v in [MIN_I64, MAX_I64, 0, -1, 1, MIN_I64 + 1,
                                               MAX_I64 - 1, -9007199254740993, 9007199254740993]]
            if vid_type == "INT64" else [],
            "stringByteLengths": list(range(257)) if vid_type != "INT64" else [],
            "singleBytes": list(range(1, 256)) if vid_type != "INT64" else [],
            "excluded": ["NUL bytes in string VID are outside the supported scan-source domain"]
            if vid_type != "INT64" else []}
    if mode == "combined":
        metadata["variantDefinitions"] = [{"id": "combination_%d" % i,
                                           "exampleExpressions": baseline_values(i),
                                           "recordsPerTable": counts["combination_%d" % i]}
                                          for i in range(19)]
    for key, lines in [("spaceFile", space_lines), ("schemaFile", schema_lines), ("dataFile", data),
                       ("sampleFile", [use] + queries)]:
        metadata["files"][metadata[key]] = _write(output, metadata[key], lines)
    return metadata


def generate_all(output_dir, prefix="qa", rows=1000):
    """Generate the matrix and scenarios.json; no connection or external dependency."""
    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    cases = []
    for scenario in build_scenarios():
        for vid_type, suffix in [("FIXED_STRING(256)", "s"), ("INT64", "i")]:
            space = "%s_%s_%s" % (prefix, suffix, scenario["scenarioId"])
            cases.append(generate(output, vid_type, scenario["scenarioId"], space, rows))
    manifest = {
        "formatVersion": 1, "generator": "generate_fixtures.py", "rowsPerScenario": rows,
        "prefix": prefix, "positiveScenarioDefinitions": sum(not s["negative"] for s in build_scenarios()),
        "positiveExecutions": sum(not c["negative"] for c in cases),
        "negativeExecutions": sum(c["negative"] for c in cases),
        "minimumPositiveVertices": sum(c["expected"]["vertices"] for c in cases if not c["negative"]),
        "minimumPositiveEdges": sum(c["expected"]["edges"] for c in cases if not c["negative"]),
        "negativeControlVertices": sum(c["expected"]["vertices"] for c in cases if c["negative"]),
        "negativeControlEdges": sum(c["expected"]["edges"] for c in cases if c["negative"]),
        "expectedRejectedRequests": sum(c["expectedRejects"] for c in cases),
        "prerequisites": [
            "graphd disable_octal_escape_char=false; verify YIELD size(\"\\000\")==1 first",
            "Do not place NUL in VID or FIXED_STRING business content; those are source-interface prerequisites, not ordinary escaping cases",
            "Source writes and schema changes are stopped during migration; target is a new isolated space",
            "Run spaceFile and schemaFile separately with the configured schema propagation wait",
            "Negative dataFile contains only rejected requests; execute its controlFile first and verify control data before and after rejection",
            "Long-string main scenario is 64KiB per property; the earlier 1MiB sample is supplementary and does not imply unlimited length support",
            "SHOW STATS requires SUBMIT JOB STATS completion; console FETCH is supplementary, verification native snapshots are authoritative"],
        "cases": cases}
    (output / "scenarios.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path(__file__).parent / "ngql")
    parser.add_argument("--prefix", default="qa")
    parser.add_argument("--rows", type=int, default=1000)
    parser.add_argument("--all", action="store_true", help="Generate all scenarios (default)")
    parser.add_argument("--scenario", choices=[s["scenarioId"] for s in build_scenarios()])
    parser.add_argument("--vid-type", default="FIXED_STRING(256)")
    parser.add_argument("--space", help="Source space name for a single scenario")
    args = parser.parse_args()
    if args.scenario:
        if not args.space:
            parser.error("--space is required with --scenario")
        result = generate(args.output, args.vid_type, args.scenario, args.space, args.rows)
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "scenario.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"id": result["id"], "output": str(args.output)}, ensure_ascii=False))
    else:
        result = generate_all(args.output, args.prefix, args.rows)
        print(json.dumps({key: result[key] for key in ["positiveExecutions", "negativeExecutions", "minimumPositiveVertices", "minimumPositiveEdges", "expectedRejectedRequests"]}))


if __name__ == "__main__":
    main()
