#!/usr/bin/env python3
"""Offline tampering probes for audit_final_archives; modifies temporary copies only."""
import argparse
import copy
import hashlib
import importlib.util
import io
import json
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "task/acceptance/audit_final_archives.py"
SPEC = importlib.util.spec_from_file_location("archive_audit", SCRIPT)
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)
CASE = "bool_values_i"


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        parser.error("Output must not exist")
    fixture = next(case for case in AUDIT.read_json(ROOT / "task/acceptance/ngql/scenarios.json")["cases"] if case["id"] == CASE)
    original = AUDIT.read_json(ROOT / ("task/acceptance/reports/final/" + CASE + ".json"))
    source_archive = ROOT / original["archive"]["file"]
    members = []
    with tarfile.open(source_archive, "r:gz") as archive:
        for member in archive:
            members.append((member, archive.extractfile(member).read() if member.isfile() else None))
    probes = [("unchanged", None), ("archive_size", "Archive byte count mismatch"),
              ("archive_sha", "SHA-256 mismatch: archive"), ("case_report", "Internal/external case-report differs"),
              ("missing_log", "Step log missing"), ("log_bytes", "SHA-256 mismatch: step log"),
              ("csv_bytes", "SHA-256 mismatch: bundle/"), ("csv_count", "Bundle CSV row count differs"),
              ("plan_csv_bytes", "SHA-256 mismatch: plan/vertices.csv"), ("plan_count", "Plan CSV header/count differs"),
              ("capture_missing_row", "Snapshot complete keys/counts differ"),
              ("capture_error_record", "Capture record is not OK"),
              ("wrong_plan_id", "Snapshot belongs to a different plan"),
              ("changed_target_value", "Actual source/target native field strings differ"),
              ("duplicate_tar_member", "Duplicate tar member"), ("duplicate_json_key", "Duplicate JSON object key")]
    outcomes = []
    for name, expected_error in probes:
        with tempfile.TemporaryDirectory(prefix="nebula-archive-audit-") as directory:
            root = Path(directory)
            report = copy.deepcopy(original)
            values = {member.name: raw for member, raw in members if raw is not None}
            prefix = CASE + "/"
            first_log = prefix + AUDIT.log_relative(report["steps"][0]["log"], CASE)
            if name == "missing_log":
                del values[first_log]
            elif name == "log_bytes":
                values[first_log] += b"tampered log\n"
            elif name == "csv_bytes":
                path = prefix + "bundle/0000_tag.csv"
                values[path] = values[path].replace(b"V:ZmFsc2U=", b"V:dHJ1ZQ==", 1)
            elif name == "csv_count":
                path = prefix + "bundle/manifest.json"
                document = json.loads(values[path]); document["tables"][0]["rowCount"] += 1
                values[path] = encoded(document)
            elif name == "plan_csv_bytes":
                path = prefix + "plan/vertices.csv"
                lines = values[path].splitlines(keepends=True)
                lines[1], lines[2] = lines[2], lines[1]
                values[path] = b"".join(lines)
            elif name == "plan_count":
                path = prefix + "plan/plan.json"
                document = json.loads(values[path]); document["vertexCount"] -= 1
                values[path] = encoded(document)
            elif name in ("capture_missing_row", "capture_error_record", "changed_target_value", "wrong_plan_id"):
                side = "target" if name == "changed_target_value" else "source"
                path = prefix + side + "/records.jsonl"
                lines = values[path].splitlines(keepends=True)
                manifest = json.loads(values[prefix + side + "/manifest.json"])
                if name == "capture_missing_row":
                    lines.pop()
                elif name == "capture_error_record":
                    row = json.loads(lines[0]); row["status"] = "ERROR"; row["error"] = "fabricated failure"
                    lines[0] = encoded(row)
                elif name == "changed_target_value":
                    row = json.loads(lines[0])
                    key = next(key for key in row["fields"] if "/prop/" in key or key.startswith("prop/"))
                    row["fields"][key] = '["bool",true]' if row["fields"][key] != '["bool",true]' else '["bool",false]'
                    lines[0] = encoded(row)
                else:
                    manifest["planId"] = "different-plan"
                values[path] = b"".join(lines)
                manifest["recordsSha256"] = AUDIT.sha(values[path])
                values[prefix + side + "/manifest.json"] = encoded(manifest)
                report[side + "Capture"] = manifest
            inner = copy.deepcopy(report); inner.pop("archive")
            if name == "case_report":
                inner["finishedAt"] = "changed-only-inside"
            values[prefix + "case-report.json"] = encoded(inner)
            if name == "duplicate_json_key":
                values[prefix + "case-report.json"] = b'{"status":"PASSED",' + values[prefix + "case-report.json"][1:]
            archived = root / "copy.tar.gz"
            with tarfile.open(archived, "w:gz") as archive:
                for member, raw in members:
                    if raw is not None and member.name not in values:
                        continue
                    info = copy.copy(member)
                    content = values.get(member.name)
                    if content is not None:
                        info.size = len(content)
                    archive.addfile(info, None if content is None else io.BytesIO(content))
                    if name == "duplicate_tar_member" and member.name == prefix + "plan/plan.json":
                        archive.addfile(info, io.BytesIO(content))
            report["archive"] = {"file": "copy.tar.gz", "bytes": archived.stat().st_size, "sha256": AUDIT.file_sha(archived)}
            if name == "archive_size": report["archive"]["bytes"] += 1
            if name == "archive_sha": report["archive"]["sha256"] = "0" * 64
            report_path = root / "report.json"; report_path.write_bytes(encoded(report))
            error = None
            try:
                AUDIT.audit_archive(root, report_path, fixture)
            except Exception as failure:
                error = str(failure)
            matched = error is None if expected_error is None else error is not None and expected_error in error
            outcomes.append({"probe": name, "expected": "PASS" if expected_error is None else "REJECT",
                             "expectationMatched": matched, "actualError": error})
            print(("PASS " if matched else "FAIL ") + name)
    # Real CLI exit codes: one valid archive cannot satisfy the final 93-case gate.
    with tempfile.TemporaryDirectory(prefix="nebula-archive-audit-cli-") as directory:
        root = Path(directory)
        for relative in ("task/acceptance/ngql/scenarios.json", "task/acceptance/schema-probes/scenarios.json",
                         "task/acceptance/probes/final-pilot/scenarios.json",
                         "task/acceptance/reports/final/" + CASE + ".json", original["archive"]["file"]):
            target = root / relative; target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((ROOT / relative).read_bytes())
        for partial in (False, True):
            output = root / ("partial.json" if partial else "complete.json")
            argv = [sys.executable, str(SCRIPT), "--root", str(root), "--out", str(output)]
            if partial:
                argv.append("--partial")
            process = subprocess.run(argv, capture_output=True)
            report = json.loads(output.read_bytes())
            expected_exit = 0 if partial else 1
            matched = (process.returncode == expected_exit and report["checkedArchives"] == 1
                       and len(report["missingReports"]) == 92
                       and report["status"] == ("PARTIAL" if partial else "FAILED")
                       and report["passed"] is partial)
            outcomes.append({"probe": "partial_is_not_final" if partial else "final_requires_all_93",
                             "expectedExit": expected_exit, "actualExit": process.returncode,
                             "status": report["status"], "expectationMatched": matched,
                             "missingReports": len(report["missingReports"])})
            print(("PASS " if matched else "FAIL ") + outcomes[-1]["probe"])
    result = {"allExpectationsMatched": all(item["expectationMatched"] for item in outcomes),
              "scriptSha256": AUDIT.file_sha(SCRIPT), "probeScriptSha256": AUDIT.file_sha(Path(__file__).resolve()),
              "originalArchiveSha256": AUDIT.file_sha(source_archive), "probes": outcomes,
              "scope": "Original files and database untouched; only temporary archive copies were altered."}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("x") as output:
        json.dump(result, output, indent=2); output.write("\n")
    return 0 if result["allExpectationsMatched"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
