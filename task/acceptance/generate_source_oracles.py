#!/usr/bin/env python3
"""Generate complete, read-only fixture oracles from the deterministic fixture generator.

No database calls. File digests and regenerated INSERT files must match scenarios.json.
The Java oracle evaluates the listed expressions with YIELD and checks every FETCH.
"""
import argparse
import base64
import collections
import hashlib
import json
from pathlib import Path
import re
import tempfile

import generate_fixtures as fixtures


def cell(raw):
    return "V:" + base64.b64encode(raw).decode("ascii")


def vid_cell(expression, vid_type):
    if vid_type == "INT64":
        if expression == 'toInteger("-9223372036854775808")':
            expression = "-9223372036854775808"
        if not re.fullmatch(r"0|-?[1-9][0-9]*", expression):
            raise ValueError("Unexpected integer VID expression")
        return cell(expression.encode("ascii"))
    if not re.fullmatch(r'"(?:\\[0-7]{3})*"', expression):
        raise ValueError("Expected byte-exact octal VID expression")
    return cell(bytes(int(item, 8) for item in re.findall(r"\\([0-7]{3})", expression)))


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def generate(case, fixture_dir):
    definitions = {item["scenarioId"]: item for item in fixtures.build_scenarios()}
    scenario = definitions[case["scenarioId"]]
    # Bind the oracle to both the published fixture files and the generator's present behavior.
    with tempfile.TemporaryDirectory(prefix="nebula-oracle-fixture-") as temporary:
        regenerated = fixtures.generate(temporary, case["vidType"], case["scenarioId"],
                                       case["sourceSpace"], case["rows"])
        if regenerated != case:
            raise ValueError("Case metadata differs from regenerated fixture: " + case["id"])
        for name, expected in case["files"].items():
            path = fixture_dir / name
            if path.stat().st_size != expected["bytes"] or sha256(path) != expected["sha256"]:
                raise ValueError("Fixture file digest mismatch: " + str(path))

    rows, mode, negative = case["rows"], scenario["mode"], scenario["negative"]
    tag_name = "tag with space" if mode == "quoted" else "case_tag"
    edge_name = "edge with space" if mode == "quoted" else "case_edge"
    vids = fixtures._vids(case["vidType"], rows, mode == "vid")
    groups, group_ids, records, variants = {}, {}, [], collections.Counter()

    def group(values):
        if len(values) != len(scenario["columns"]):
            raise ValueError("Property expression count differs from schema")
        properties = [{"name": name, "type": datatype, "expression": value}
                      for (name, datatype), value in zip(scenario["columns"], values)]
        key = json.dumps(properties, ensure_ascii=False, separators=(",", ":"))
        if key not in group_ids:
            group_ids[key] = "values_" + str(len(groups))
            groups[group_ids[key]] = properties
        return group_ids[key]

    for index in range(rows):
        label, values = scenario["variants"][index % len(scenario["variants"])]
        if negative:
            label = "positive_control"
            values = {"bad_geo": [fixtures.geo("POINT(0 0)")], "bad_int8": ["0"],
                      "bad_float_infinity": [fixtures.float_expr("0.1")], "bad_rank": []}.get(
                          mode, [fixtures.literal("positive_control")])
        elif mode == "defaults":
            values = ["42", fixtures.literal("default,'\"\\\r\n😀"), "true", 'date("2024-02-29")']
        elif mode == "combined":
            label, values = "combination_%d" % (index % 19), fixtures.baseline_values(index)
        variants[label] += 1
        value_id = group(values)
        tags = [{"name": tag_name, "values": value_id}]
        if mode == "multi":
            tags.append({"name": "extra_tag", "values": None})
        records.append({"index": index, "kind": "VERTEX", "variant": label,
                        "vid": vid_cell(vids[index], case["vidType"]), "tags": tags})
        source, rest = fixtures._edge_key(scenario, vids, index).split("->")
        target, rank = rest.rsplit("@", 1)
        records.append({"index": index, "kind": "EDGE", "variant": label,
                        "src": vid_cell(source, case["vidType"]),
                        "dst": vid_cell(target, case["vidType"]),
                        "rank": cell(rank.encode("ascii")), "name": edge_name, "values": value_id})
    schemas = [{"kind": kind, "name": name,
                "columns": [{"name": column, "type": datatype,
                             "nullable": mode not in ("bad_geo", "defaults")}
                            for column, datatype in scenario["columns"]]}
               for kind, name in [("TAG", tag_name), ("EDGE", edge_name)]]
    if mode == "multi":
        schemas.append({"kind": "TAG", "name": "extra_tag", "columns": []})
    return {"formatVersion": 1, "id": case["id"], "scenarioId": case["scenarioId"],
            "sourceSpace": case["sourceSpace"], "vidType": case["vidType"],
            "negativeControl": negative, "expected": case["expected"],
            "fixtureFileHashes": case["files"], "variantCounts": dict(variants),
            "schemas": schemas, "expressionGroups": groups, "records": records,
            "comparison": "Native fields are exact; only FLOAT float32 promotion is applied. "
                          "GEOGRAPHY expected values use ST_GeogFromText YIELD, which normalizes "
                          "and validates on the source server; the oracle never normalizes FETCH values."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, default=Path(__file__).parent / "ngql")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--only", help="Comma-separated fixture case IDs")
    args = parser.parse_args()
    metadata = json.loads((args.fixtures / "scenarios.json").read_text())
    cases = metadata["cases"]
    if args.only:
        wanted = set(args.only.split(","))
        cases = [case for case in cases if case["id"] in wanted]
        if {case["id"] for case in cases} != wanted:
            parser.error("Unknown case ID")
    args.out.mkdir(parents=True, exist_ok=True)
    manifest = {"formatVersion": 1, "fixturesSha256": sha256(args.fixtures / "scenarios.json"), "cases": {}}
    for case in cases:
        result = generate(case, args.fixtures)
        path = args.out / (case["id"] + ".json")
        path.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n")
        manifest["cases"][case["id"]] = {"file": path.name, "sha256": sha256(path),
                                         "vertices": case["expected"]["vertices"],
                                         "edges": case["expected"]["edges"]}
    (args.out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"generatedCases": len(cases), "out": str(args.out)}))


if __name__ == "__main__":
    main()
