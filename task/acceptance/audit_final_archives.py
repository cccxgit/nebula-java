#!/usr/bin/env python3
"""Read-only audit of the 90 final, two Schema and one geography evidence archives.

Reads tar members as streams; never extracts files or invokes Java/the database.
Only --out is written. --partial audits present reports but cannot produce PASSED.
"""
import argparse
import base64
import hashlib
import json
import re
import sys
import tarfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[2]
HEX = re.compile(r"[0-9a-f]{64}\Z")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def file_sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON object key: " + key)
        result[key] = value
    return result


def json_value(raw):
    def invalid_constant(value):
        raise ValueError("Non-JSON numeric constant: " + value)
    return json.loads(raw, object_pairs_hook=no_duplicates, parse_constant=invalid_constant)


def read_json(path):
    require(path.is_file() and not path.is_symlink(), "Missing/linked JSON: " + str(path))
    return json_value(path.read_bytes())


def positive_int(value, label, zero=False):
    require(type(value) is int and value >= (0 if zero else 1), "Invalid integer: " + label)
    return value


def hash_equal(actual, expected, label):
    require(isinstance(expected, str) and HEX.fullmatch(expected), "Invalid SHA-256: " + label)
    require(actual == expected, "SHA-256 mismatch: " + label)


def b64_cell(raw):
    return "V:" + base64.b64encode(raw).decode("ascii")


def identifier(cell, kind, vid_type=None):
    require(cell.startswith("V:"), "NULL/invalid identifier cell")
    raw = base64.b64decode(cell[2:], validate=True)
    require(b64_cell(raw) == cell, "Non-canonical identifier Base64")
    if kind == "rank" or vid_type == "INT64":
        number = raw.decode("ascii")
        value = int(number)
        require(str(value) == number and -(1 << 63) <= value < (1 << 63), "Invalid int64 identifier")
        require(kind != "rank" or value != -(1 << 63), "Unreplayable minimum edge rank")
    elif kind == "vid":
        match = re.fullmatch(r"FIXED_STRING\(([1-9][0-9]*)\)", vid_type or "")
        require(match is not None, "Invalid VID type")
        require(b"\0" not in raw and len(raw) <= int(match.group(1)), "Invalid string VID domain")
    else:
        require(kind == "name" and raw and b"\0" not in raw, "Invalid edge name")
        raw.decode("utf-8", errors="strict")
    return raw


def key_digest(keys):
    return sha(("nebula-requested-keys-v1\n" + "".join(key + "\n" for key in sorted(keys))).encode("utf-8"))


def stream_csv(stream, path):
    digest = hashlib.sha256()
    header = None
    rows, ids = 0, set()
    for raw in stream:
        digest.update(raw)
        require(raw.endswith(b"\n") and raw.isascii(), "CSV must contain complete ASCII lines: " + path)
        line = raw[:-1]
        if line.endswith(b"\r"):
            line = line[:-1]
        if header is None:
            header = line.decode("ascii").split(",")
            continue
        require(line and line.count(b",") + 1 == len(header), "CSV field count/blank row: " + path)
        # Value cells cannot contain CSV delimiters. Retain only complete identifier columns.
        count = 1 if header[0] in ("_vid", "vid") else (4 if header[0] == "src" else 3)
        fields = []
        start = 0
        for _ in range(count):
            end = line.find(b",", start)
            if end < 0:
                end = len(line)
            fields.append(line[start:end].decode("ascii"))
            start = end + 1
        item = tuple(fields)
        require(item not in ids, "Duplicate CSV identifier: " + path)
        ids.add(item)
        rows += 1
    require(header is not None, "Empty CSV: " + path)
    return {"sha256": digest.hexdigest(), "header": header, "rows": rows, "ids": ids}


def stream_records(stream, path):
    digest = hashlib.sha256()
    records, counts = {}, {"VERTEX": 0, "EDGE": 0}
    for raw in stream:
        digest.update(raw)
        require(raw.endswith(b"\n") and raw.strip(), "Incomplete/blank JSONL record: " + path)
        record = json_value(raw)
        key, kind = record.get("key"), record.get("kind")
        require(kind in counts and isinstance(key, str), "Invalid capture identity: " + path)
        require(key.startswith("vertex|" if kind == "VERTEX" else "edge|"), "Capture key/kind mismatch")
        require(key not in records, "Duplicate capture key: " + key)
        require(record.get("status") == "OK" and not record.get("error"), "Capture record is not OK: " + key)
        fields = record.get("fields")
        require(isinstance(fields, dict) and all(isinstance(k, str) and isinstance(v, str)
                for k, v in fields.items()), "Invalid native fields: " + key)
        if kind == "VERTEX":
            require("$vid" in fields and any(k.startswith("tag/") and v == "present"
                    for k, v in fields.items()), "Missing VID/actual Tag: " + key)
        else:
            require({"$src", "$dst", "$name", "$rank"} <= set(fields), "Incomplete native edge identity: " + key)
        # Exact field strings retain raw value encodings; do not normalize values or floats.
        native_hash = sha(json.dumps(fields, sort_keys=True, ensure_ascii=True,
                                     separators=(",", ":")).encode("ascii"))
        records[key] = (kind, native_hash)
        counts[kind] += 1
    return {"sha256": digest.hexdigest(), "records": records, "counts": counts}


def archive_contents(path, case_id):
    """Hash every regular member, retaining only small JSON and row/key summaries."""
    files, documents, csvs, snapshots, seen = {}, {}, {}, {}, set()
    selected_json = {"case-report.json", "bundle/manifest.json", "bundle/verification-report.json",
                     "plan/plan.json", "source/manifest.json", "target/manifest.json",
                     "comparison/comparison-report.json", "source-fixture-oracle.json"}
    with tarfile.open(path, mode="r|gz") as archive:
        for member in archive:
            name = member.name.rstrip("/")
            pure = PurePosixPath(name)
            require(not pure.is_absolute() and ".." not in pure.parts and str(pure) == name,
                    "Unsafe/non-canonical tar path: " + name)
            require(name == case_id or name.startswith(case_id + "/"), "Wrong archive case root: " + name)
            require(name not in seen, "Duplicate tar member: " + name)
            seen.add(name)
            if member.isdir():
                continue
            require(member.isfile(), "Non-regular tar member: " + name)
            relative = name[len(case_id) + 1:]
            stream = archive.extractfile(member)
            require(stream is not None, "Unreadable tar member: " + name)
            if (relative.startswith("bundle/") and relative.endswith(".csv")) or relative in (
                    "plan/vertices.csv", "plan/edges.csv"):
                result = stream_csv(stream, name)
                csvs[relative] = result
                digest = result["sha256"]
            elif relative in ("source/records.jsonl", "target/records.jsonl"):
                result = stream_records(stream, name)
                snapshots[relative] = result
                digest = result["sha256"]
            elif relative in selected_json:
                raw = stream.read()
                documents[relative] = json_value(raw)
                digest = sha(raw)
            else:
                hasher = hashlib.sha256()
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    hasher.update(chunk)
                digest = hasher.hexdigest()
            files[relative] = {"sha256": digest, "bytes": member.size}
    return files, documents, csvs, snapshots


def log_relative(log, case_id):
    path = PurePosixPath(log)
    require(".." not in path.parts and path.parts.count(case_id) == 1,
            "Step log is outside/ambiguous for its case: " + log)
    return str(PurePosixPath(*path.parts[path.parts.index(case_id) + 1:]))


def audit_archive(root, report_path, fixture):
    require(report_path.is_file() and not report_path.is_symlink(), "Report missing/linked")
    report_raw = report_path.read_bytes()
    report = json_value(report_raw)
    case_id = fixture["id"]
    proof = {"id": case_id, "reportFile": str(report_path.relative_to(root)),
             "reportSha256": sha(report_raw), "passed": False}
    require(report.get("id") == case_id and report.get("status") == "PASSED", "Report case/status is not PASSED")
    require(report.get("expected") == fixture["expected"], "Report counts differ from fixture inventory")
    require(report.get("sourceSpace") == fixture["sourceSpace"], "Report source differs from fixture")
    require(report.get("sourceSpace") != report.get("targetSpace") and report.get("targetSpace"), "Invalid target space")
    expected = fixture["expected"]
    require(expected["vertices"] == expected["edges"] == 1000, "Expected 1000 vertices and 1000 edges per case")
    info = report["archive"]
    archive_path = root / info["file"]
    require(not archive_path.is_symlink() and archive_path.is_file(), "Archive missing/linked")
    try:
        archive_path.resolve().relative_to(root.resolve())
    except ValueError:
        raise ValueError("Archive outside repository")
    require(archive_path.stat().st_size == positive_int(info["bytes"], "archive bytes"), "Archive byte count mismatch")
    archive_hash = file_sha(archive_path)
    hash_equal(archive_hash, info["sha256"], "archive")
    proof.update({"archiveFile": info["file"], "archiveSha256": archive_hash, "archiveBytes": info["bytes"]})
    files, docs, csvs, snapshots = archive_contents(archive_path, case_id)
    expected_inner = dict(report)
    expected_inner.pop("archive")
    require(docs["case-report.json"] == expected_inner, "Internal/external case-report differs beyond archive field")
    require(isinstance(report.get("steps"), list) and report["steps"], "Missing command steps")
    log_proofs, log_names = [], set()
    for step in report["steps"]:
        relative = log_relative(step["log"], case_id)
        require(relative not in log_names, "Duplicate step log: " + relative)
        log_names.add(relative)
        require(relative in files, "Step log missing from archive: " + relative)
        hash_equal(files[relative]["sha256"], step["sha256"], "step log " + relative)
        require(type(step.get("exitCode")) is int and step["exitCode"] == 0, "Nonzero/missing successful step exit code")
        log_proofs.append(dict(file=relative, **files[relative]))

    bundle = docs["bundle/manifest.json"]
    require(bundle["formatVersion"] == 1 and bundle["sourceSpace"] == report["sourceSpace"], "Wrong bundle identity")
    require(bundle["vidType"] == report["vidType"] == fixture["vidType"], "Bundle/report VID type differs")
    require(bundle["valueEncoding"] == "N-or-V:base64;float=raw-f64-hex;temporal=integer-array", "Unknown bundle encoding")
    vertices, edges, table_files, table_names, table_proofs = set(), set(), set(), set(), []
    table_counts = {"TAG": {}, "EDGE": {}}
    for table in bundle["tables"]:
        kind, name, filename = table["kind"], table["name"], table["file"]
        require(kind in table_counts and (kind, name) not in table_names, "Invalid/duplicate bundle table")
        require(re.fullmatch(r"[0-9]+_(tag|edge)\.csv", filename) is not None, "Invalid bundle CSV filename")
        relative = "bundle/" + filename
        require(relative not in table_files, "Reused bundle CSV")
        table_names.add((kind, name)); table_files.add(relative)
        data = csvs[relative]
        hash_equal(data["sha256"], table["sha256"], relative)
        wanted_header = (["_vid"] if kind == "TAG" else ["_src", "_dst", "_rank"]) + [
            "p" + str(i) for i in range(len(table["columns"]))]
        require(data["header"] == wanted_header, "Bundle CSV header differs: " + filename)
        require(data["rows"] == positive_int(table["rowCount"], "table rowCount", zero=True), "Bundle CSV row count differs")
        table_counts[kind][name] = data["rows"]
        name_cell = b64_cell(name.encode("utf-8"))
        for cells in data["ids"]:
            identifier(cells[0], "vid", bundle["vidType"])
            vertices.add(cells[0])
            if kind == "EDGE":
                identifier(cells[1], "vid", bundle["vidType"])
                identifier(cells[2], "rank")
                vertices.add(cells[1])
                key = "edge|" + "|".join((name_cell, cells[0], cells[2], cells[1]))
                require(key not in edges, "Duplicate complete bundle edge key")
                edges.add(key)
        table_proofs.append({"file": relative, "kind": kind, "name": name,
                             "sha256": data["sha256"], "rows": data["rows"]})
    require(table_files == {name for name in csvs if name.startswith("bundle/")}, "Unlisted/missing bundle CSV")
    require(table_counts == {"TAG": expected["tags"], "EDGE": expected["edgeTypes"]}, "Actual per-table counts differ from fixture")
    require(len(vertices) == expected["vertices"] and len(edges) == expected["edges"], "Bundle complete-key counts differ")

    plan = docs["plan/plan.json"]
    require(plan["formatVersion"] == 1 and plan["vidType"] == bundle["vidType"] and plan["planId"], "Invalid plan metadata")
    hash_equal(files["bundle/manifest.json"]["sha256"], plan["sourceExportManifestSha256"], "plan export manifest binding")
    plan_keys = set()
    for filename, header, hash_field, count_field in (
            ("vertices.csv", ["vid"], "verticesSha256", "vertexCount"),
            ("edges.csv", ["src", "edge", "rank", "dst"], "edgesSha256", "edgeCount")):
        data = csvs["plan/" + filename]
        hash_equal(data["sha256"], plan[hash_field], "plan/" + filename)
        require(data["header"] == header and data["rows"] == plan[count_field] == 1000, "Plan CSV header/count differs")
        for cells in data["ids"]:
            identifier(cells[0], "vid", plan["vidType"])
            if filename == "vertices.csv":
                plan_keys.add("vertex|" + cells[0])
            else:
                identifier(cells[1], "name"); identifier(cells[2], "rank"); identifier(cells[3], "vid", plan["vidType"])
                plan_keys.add("edge|" + "|".join((cells[1], cells[0], cells[2], cells[3])))
    require(plan_keys == {"vertex|" + vid for vid in vertices} | edges, "Plan does not contain bundle's exact complete keys")
    computed_plan_digest = sha(("nebula-verification-plan-v1\n" + plan["planId"] + "\n" + plan["vidType"]
                               + "\n" + plan["verticesSha256"] + "\n" + plan["edgesSha256"]).encode("utf-8"))
    hash_equal(computed_plan_digest, plan["digest"], "plan digest")
    snapshot_proofs = {}
    for side in ("source", "target"):
        manifest = docs[side + "/manifest.json"]
        data = snapshots[side + "/records.jsonl"]
        require(manifest == report[side + "Capture"], "Archived/reported capture manifest differs: " + side)
        require(manifest["formatVersion"] == 1 and manifest["valueFormat"] == "nebula-native-fields-v1", "Unknown snapshot format")
        require(manifest["status"] == "COMPLETE" and manifest["errors"] == [], "Snapshot is not COMPLETE")
        require(manifest["space"] == report[side + "Space"] and manifest["vidType"] == plan["vidType"], "Wrong snapshot space/VID type")
        require(manifest["planId"] == plan["planId"] and manifest["planDigest"] == plan["digest"], "Snapshot belongs to a different plan")
        require(set(data["records"]) == plan_keys and data["counts"] == {"VERTEX": 1000, "EDGE": 1000}, "Snapshot complete keys/counts differ from plan")
        require(manifest["recordCount"] == manifest["requestedCount"] == manifest["okCount"] == 2000
                and manifest["missingCount"] == manifest["errorCount"] == 0, "Snapshot outcome counters differ")
        hash_equal(data["sha256"], manifest["recordsSha256"], side + " records.jsonl")
        hash_equal(key_digest(data["records"]), manifest["requestedKeysSha256"], side + " requested keys")
        snapshot_proofs[side] = {"records": 2000, "vertices": 1000, "edges": 1000,
                                 "recordsSha256": data["sha256"], "manifestSha256": files[side + "/manifest.json"]["sha256"],
                                 "requestedKeysSha256": manifest["requestedKeysSha256"]}
    require(snapshots["source/records.jsonl"]["records"] == snapshots["target/records.jsonl"]["records"],
            "Actual source/target native field strings differ")
    require(docs["source/manifest.json"]["schemas"] == docs["target/manifest.json"]["schemas"], "Source/target schema snapshots differ")
    comparison = docs["comparison/comparison-report.json"]
    require(comparison == report["verification"], "Archived/reported comparison differs")
    require(comparison["matched"] is True and comparison["status"] == "MATCH" and comparison["comparedRecords"] == 2000
            and comparison["differentRecords"] == comparison["errorRecords"] == comparison["differenceFields"] == 0
            and comparison["differences"] == [] and comparison["planId"] == plan["planId"], "Comparison counters/status/plan differs")
    if "sourceFixtureOracle" in report:
        hash_equal(files["source-fixture-oracle.json"]["sha256"], report["sourceFixtureOracle"]["reportSha256"], "source oracle")
    proof.update({"passed": True, "checkedLogs": len(log_proofs), "logs": log_proofs,
                  "bundleTables": table_proofs, "bundleTagRows": sum(table_counts["TAG"].values()),
                  "bundleEdgeRows": sum(table_counts["EDGE"].values()), "planId": plan["planId"], "planDigest": plan["digest"],
                  "sourceRecords": 2000, "targetRecords": 2000, "snapshots": snapshot_proofs,
                  "nativeFieldsMatched": True, "planKeysBoundToBundle": True,
                  "proofFiles": {name: files[name] for name in (
                      "case-report.json", "bundle/manifest.json", "plan/plan.json", "plan/vertices.csv", "plan/edges.csv",
                      "source/manifest.json", "source/records.jsonl", "target/manifest.json", "target/records.jsonl",
                      "comparison/comparison-report.json")}})
    return proof


def inventory(root):
    groups = [("final", "task/acceptance/ngql/scenarios.json", 90),
              ("schema-stress", "task/acceptance/schema-probes/scenarios.json", 2),
              ("geography-final", "task/acceptance/probes/final-pilot/scenarios.json", 1)]
    selected, manifests = [], []
    for group, relative, count in groups:
        path = root / relative
        cases = read_json(path)["cases"]
        require(len(cases) == count and len({case["id"] for case in cases}) == count, "Wrong/duplicate fixture inventory: " + relative)
        manifests.append({"file": relative, "sha256": file_sha(path), "cases": count})
        expected_ids = {case["id"] for case in cases}
        report_dir = root / "task/acceptance/reports" / group
        for path in report_dir.glob("*.json"):
            if path.stem in expected_ids:
                continue
            extra = read_json(path)
            require(not ("archive" in extra and "id" in extra), "Unexpected case report: " + str(path))
        for case in cases:
            selected.append((report_dir / (case["id"] + ".json"), case))
    require(len(selected) == 93, "Final inventory must contain 93 archives")
    return selected, manifests


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--out", type=Path, required=True, help="New audit JSON path; existing files are never overwritten")
    parser.add_argument("--partial", action="store_true", help="Audit present reports; missing reports remain explicit and status cannot be PASSED")
    args = parser.parse_args()
    if args.out.exists():
        parser.error("--out must not exist")
    root = args.root.resolve()
    result = {"formatVersion": 1, "startedAt": datetime.now(timezone.utc).isoformat(),
              "scriptFile": "task/acceptance/audit_final_archives.py", "scriptSha256": file_sha(Path(__file__).resolve()),
              "partial": args.partial, "passed": False, "status": "FAILED", "expectedArchives": 93,
              "checkedArchives": 0, "archives": [], "failures": [], "missingReports": [],
              "scope": "Read-only original archive bytes, manifests, logs, complete keys and exact native field strings; no database or Java execution."}
    try:
        selected, manifests = inventory(root)
        result["fixtureInventories"] = manifests
        for report_path, case in selected:
            relative = str(report_path.relative_to(root))
            if not report_path.exists():
                result["missingReports"].append(relative)
                continue
            try:
                proof = audit_archive(root, report_path, case)
                result["archives"].append(proof)
                result["checkedArchives"] += 1
                print("PASS " + relative, flush=True)
            except Exception as error:
                failure = {"reportFile": relative, "id": case["id"], "error": str(error)}
                result["failures"].append(failure)
                result["archives"].append(dict(failure, passed=False))
                print("FAIL " + relative + ": " + str(error), file=sys.stderr, flush=True)
        if result["missingReports"] and not args.partial:
            result["failures"].append({"error": "Missing " + str(len(result["missingReports"])) + " required final reports"})
        if not result["checkedArchives"]:
            result["failures"].append({"error": "No archives were successfully audited"})
        if not result["failures"]:
            result["passed"] = True
            result["status"] = "PARTIAL" if args.partial else "PASSED"
    except Exception as error:
        result["failures"].append({"error": str(error)})
    result["finishedAt"] = datetime.now(timezone.utc).isoformat()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("x", encoding="utf-8") as output:
        json.dump(result, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print(json.dumps({"status": result["status"], "checkedArchives": result["checkedArchives"],
                      "missingReports": len(result["missingReports"]), "failures": len(result["failures"]),
                      "out": str(args.out)}, ensure_ascii=False))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
