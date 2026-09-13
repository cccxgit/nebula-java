#!/usr/bin/env python3
"""Inject accuracy faults into new target copies or copied evidence, never the source.

Requires a completed run_campaign.py baseline with >=1000 vertices and edges.
PASSED means the intended fault was detected, not that the damaged data matched.
"""

import argparse
import base64
import copy
import csv
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import struct
import time
import traceback
import uuid

try:
    from .run_campaign import Campaign, ROOT, console_tables, digest, identifier, now, write_json
except ImportError:
    from run_campaign import Campaign, ROOT, console_tables, digest, identifier, now, write_json


FAULTS = [
    {"id": "property_change", "baseline": "bool", "database": True,
     "description": "Flip one BOOL property while preserving every vertex and edge count",
     "expectedStatus": "DIFFERENT", "expectedSamples": "DIFFERENT"},
    {"id": "edge_key_replace", "baseline": "bool", "database": True,
     "description": "Replace one complete edge key with another rank while total counts stay equal",
     "expectedStatus": "ERROR", "expectedSamples": "DIFFERENT_OR_MISSING"},
    {"id": "tag_membership", "baseline": "bool", "database": True,
     "description": "Replace a vertex's original Tag with a new zero-property Tag",
     "expectedStatus": "DIFFERENT", "expectedSamples": "DIFFERENT"},
    {"id": "geography_coordinate_bit", "baseline": "geography", "database": True,
     "description": "Flip exactly one low coordinate bit in one POINT value",
     "expectedStatus": "DIFFERENT", "expectedSamples": "DISPLAY_MAY_MATCH"},
    {"id": "geography_schema_shape", "baseline": "geography", "database": True,
     "description": "Change the Tag declaration from GEOGRAPHY(POINT) to GEOGRAPHY(LINESTRING)",
     "expectedStatus": "ERROR", "expectedSamples": "SAME_OR_QUERY_ERROR"},
    {"id": "bundle_integrity", "baseline": "bool", "database": False,
     "description": "Reject modified CSV bytes and a duplicate key with refreshed file hash/count",
     "expectedStatus": "ERROR", "subchecks": ["csv_hash", "duplicate_key"]},
    {"id": "snapshot_integrity", "baseline": "bool", "database": False,
     "description": "Reject truncation, mixed plan identity, forged COMPLETE and identical MISSING records",
     "expectedStatus": "ERROR", "subchecks": ["truncation", "wrong_plan", "false_complete", "same_missing"]},
]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def json_file(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def name64(name):
    return base64.b64encode(name.encode("utf-8")).decode("ascii")


def cell_bytes(cell):
    require(cell.startswith("V:"), "Expected a non-NULL identifier cell")
    raw = base64.b64decode(cell[2:], validate=True)
    require(base64.b64encode(raw).decode("ascii") == cell[2:], "Noncanonical Base64 identifier")
    return raw


def vid_literal(cell, vid_type):
    raw = cell_bytes(cell)
    if vid_type == "INT64":
        value = int(raw.decode("ascii"))
        require(-(1 << 63) <= value < (1 << 63), "Integer VID out of range")
        return 'toInteger("-9223372036854775808")' if value == -(1 << 63) else str(value)
    require(b"\x00" not in raw, "NUL VID is outside the supported source domain")
    return '"' + "".join("\\%03o" % byte for byte in raw) + '"'


def records(directory):
    result = {}
    for line in (Path(directory) / "records.jsonl").read_text(encoding="utf-8").splitlines():
        record = json.loads(line)
        require(record["key"] not in result, "Duplicate record in baseline")
        result[record["key"]] = record
    return result


def copy_tree(source, target):
    require(not Path(target).exists(), "Refusing to overwrite evidence: " + str(target))
    shutil.copytree(source, target)


class FaultCampaign(Campaign):
    def __init__(self, args):
        super().__init__(args)
        self.baselines = {"bool": Path(args.baseline_work).resolve()}
        if args.geography_baseline_work:
            self.baselines["geography"] = Path(args.geography_baseline_work).resolve()
        metadata = json_file(self.fixture_dir / "scenarios.json")
        self.cases = {case["id"]: case for case in metadata["cases"]}

    def baseline(self, definition):
        kind = definition["baseline"]
        require(kind in self.baselines, "A geography-point baseline is required for geography faults")
        directory = self.baselines[kind]
        report = json_file(directory / "case-report.json")
        require(report["status"] == "PASSED", "Baseline case did not pass: " + str(directory))
        require(report["verification"]["matched"], "Baseline verification must be MATCH")
        require(report["expected"]["vertices"] >= 1000 and report["expected"]["edges"] >= 1000,
                "Fault controls require at least 1000 vertices and 1000 edges")
        require(report["scenarioId"] == ("bool_values" if kind == "bool" else "geography_point"),
                "Use bool_values or geography_point baselines for reproducible mutations")
        require(report["id"] in self.cases, "Baseline case is absent from fixture inventory")
        case = copy.deepcopy(self.cases[report["id"]])
        case["sourceSpace"] = report["sourceSpace"]
        for item in ("bundle", "plan", "source", "target", "source-stats", "target-stats",
                     "source-console", "target-console"):
            require((directory / item).is_dir(), "Missing baseline evidence: " + item)
        return directory, report, case

    def compare(self, source, target, directory, expected):
        allowed = (0,) if expected == "MATCH" else (0, 1, 2)
        self.tool(self.verifier, "compare", directory.parent / (directory.name + ".txt"),
                  allowed=allowed, source=source, target=target, out=directory)
        result = json_file(directory / "comparison-report.json")
        exit_code = self.steps[-1]["exitCode"]
        require(result["status"] == expected,
                "Expected %s, got %s: %s" % (expected, result["status"], directory))
        require(result["matched"] == (expected == "MATCH"), "Contradictory comparison outcome")
        require(exit_code == {"MATCH": 0, "DIFFERENT": 2, "ERROR": 1}[expected],
                "Unexpected comparison exit status")
        return result

    def baseline_proof(self, baseline, work, report):
        proof = work / "baseline-proof"
        proof.mkdir()
        shutil.copy2(baseline / "case-report.json", proof / "case-report.json")
        for item in ("bundle", "plan", "source", "target", "source-stats", "target-stats", "source-console", "target-console"):
            copy_tree(baseline / item, proof / item)
        report["baselineVerification"] = self.compare(proof / "source", proof / "target",
                                                      work / "baseline-proof-comparison", "MATCH")
        manifest = json_file(proof / "source/manifest.json")
        require(manifest["status"] == "COMPLETE" and manifest["okCount"] >= 2000,
                "Baseline source capture is incomplete or too small")
        report["baselineEvidence"] = {
            "path": str(proof), "caseReportSha256": digest(proof / "case-report.json"),
            "sourceCaptureSha256": digest(proof / "source/records.jsonl"),
            "targetCaptureSha256": digest(proof / "target/records.jsonl"),
            "stats": json_file(proof / "source-stats/stats.json"),
            "sourceConsoleSha256": digest(proof / "source-console/fetch-tables.txt"),
            "targetConsoleSha256": digest(proof / "target-console/fetch-tables.txt")}

    def fresh_target(self, baseline, case, work, report, scene):
        copy_tree(baseline / "bundle", work / "bundle")
        copy_tree(baseline / "plan", work / "plan")
        plan = work / "plan"
        report["sourceStatsBeforeImport"] = self.stats(case["sourceSpace"], work / "source-stats", case["expected"])
        report["sourceConsoleBeforeImport"] = self.samples(case, case["sourceSpace"], work / "source-console")
        report["sourceCaptureBeforeImport"] = self.collect(plan, case["sourceSpace"], work / "source",
                                                          work / "source-capture.txt")
        self.compare(work / "baseline-proof/source", work / "source", work / "source-still-stable", "MATCH")
        report["sourceBaselineFinishedAt"] = now()
        target = self.args.target_prefix + "_" + scene
        require(len(target) <= 63, "Generated target space name exceeds 63 characters")
        identifier(target)
        require(target not in (case["sourceSpace"], case.get("targetSpace")), "Target must be a new space")
        report["targetSpace"] = target
        report["targetImportStartedAt"] = now()
        self.tool(self.migration, "import", work / "migration-import.txt",
                  directory=work / "bundle", target_space=target, target_host=self.args.host,
                  target_graph_port=self.args.port, target_meta_port=self.args.meta_port,
                  target_user=self.args.user, schema_wait_ms=self.args.schema_wait_ms,
                  scan_limit=self.args.scan_limit)
        report["targetImportFinishedAt"] = now()
        self.collect(plan, target, work / "target-before", work / "target-before-capture.txt")
        report["freshTargetBaseline"] = self.compare(work / "source", work / "target-before",
                                                   work / "target-before-comparison", "MATCH")
        report["targetStatsBeforeFault"] = self.stats(target, work / "target-before-stats", case["expected"])
        report["targetConsoleBeforeFault"] = self.samples(case, target, work / "target-before-console")
        require(report["sourceConsoleBeforeImport"]["tablesSha256"]
                == report["targetConsoleBeforeFault"]["tablesSha256"], "Fresh target console samples differ")
        return target

    def observed_samples(self, space, queries, directory):
        directory.mkdir(parents=True)
        script = directory / "fetch.ngql"
        script.write_text("USE %s;\n%s\n" % (identifier(space), "\n".join(queries)), encoding="utf-8")
        output = self.console(script, directory / "fetch.txt", allow_errors=True)
        # nebula-console still prints a bordered header for zero-row results, but
        # terminates it with "Empty set" rather than "Got N rows". Keep the raw
        # evidence untouched and adapt only this parser's completion marker.
        empty_results = sum(line.startswith(b"Empty set (time spent ") for line in output.splitlines())
        parseable = b"\n".join(b"Got 0 rows (time spent omitted)" if line.startswith(b"Empty set (time spent ")
                                else line for line in output.splitlines()) + b"\n"
        tables = console_tables(parseable)
        normalized = b"\n\n".join(tables) + b"\n"
        (directory / "tables.txt").write_bytes(normalized)
        errors = [line.decode("utf-8", errors="replace") for line in output.splitlines() if b"[ERROR" in line]
        return {"queryCount": len(queries), "returnedTables": len(tables), "emptyResults": empty_results, "errors": errors,
                "tablesSha256": hashlib.sha256(normalized).hexdigest()}

    def database_fault(self, definition, baseline, case, work, report):
        scene = definition["id"]
        target = self.fresh_target(baseline, case, work, report, scene)
        source_records = records(work / "source")
        vertex = next(record for record in source_records.values() if record["kind"] == "VERTEX")
        vid_cell = vertex["key"].split("|", 1)[1]
        vid = vid_literal(vid_cell, case["vidType"])
        path = "tag/" + name64("case_tag") + "/prop/" + name64("value")
        statements = []
        expected_stats = copy.deepcopy(case["expected"])
        queries = list(case["sampleQueries"])
        coordinate_assertion = None
        if scene == "property_change":
            value = json.loads(vertex["fields"][path])
            require(value[0] == "bool", "Expected BOOL baseline property")
            statements.append("UPDATE VERTEX ON case_tag %s SET `value`=%s;" %
                              (vid, "false" if value[1] else "true"))
            queries.append("FETCH PROP ON * %s YIELD vertex AS v;" % vid)
        elif scene == "edge_key_replace":
            with (work / "plan/edges.csv").open(newline="", encoding="ascii") as stream:
                edge = next(csv.DictReader(stream))
            name = cell_bytes(edge["edge"]).decode("utf-8")
            require(name == "case_edge", "Expected simple edge baseline")
            src = vid_literal(edge["src"], case["vidType"])
            dst = vid_literal(edge["dst"], case["vidType"])
            rank = int(cell_bytes(edge["rank"]).decode("ascii"))
            key = "edge|%s|%s|%s|%s" % (edge["edge"], edge["src"], edge["rank"], edge["dst"])
            value = json.loads(source_records[key]["fields"]["prop/" + name64("value")])
            require(value[0] == "bool", "Expected BOOL edge property")
            replacement_rank = rank + 1000000
            new_rank_cell = "V:" + base64.b64encode(str(replacement_rank).encode()).decode()
            new_key = "edge|%s|%s|%s|%s" % (edge["edge"], edge["src"], new_rank_cell, edge["dst"])
            require(new_key not in source_records, "Replacement edge already exists in baseline")
            old = "%s->%s@%d" % (src, dst, rank)
            new = "%s->%s@%d" % (src, dst, replacement_rank)
            statements += ["DELETE EDGE case_edge %s;" % old,
                           "INSERT EDGE case_edge(`value`) VALUES %s:(%s);" %
                           (new, "true" if value[1] else "false")]
            queries += ["FETCH PROP ON case_edge %s YIELD edge AS e;" % old,
                        "FETCH PROP ON case_edge %s YIELD edge AS e;" % new]
            report["replacedEdge"] = {"oldKey": key, "newKey": new_key, "countsExpectedEqual": True}
        elif scene == "tag_membership":
            # Creation is separate because Schema propagation must precede the INSERT.
            self.query(target, "CREATE TAG fault_extra()", work / "mutation", "create-extra-tag")
            time.sleep(self.args.schema_wait_ms / 1000)
            statements += ["DELETE TAG case_tag FROM %s;" % vid,
                           "INSERT VERTEX fault_extra() VALUES %s:();" % vid]
            expected_stats["tags"]["case_tag"] -= 1
            expected_stats["tags"]["fault_extra"] = 1
            queries.append("FETCH PROP ON * %s YIELD vertex AS v;" % vid)
        elif scene == "geography_coordinate_bit":
            for candidate in source_records.values():
                if candidate["kind"] != "VERTEX" or path not in candidate["fields"]:
                    continue
                old = json.loads(candidate["fields"][path])
                if old[:2] != ["geography", "point"]:
                    continue
                x = struct.unpack(">d", bytes.fromhex(old[2][0]))[0]
                if 1 < abs(x) < 180:
                    vertex, coordinate_assertion = candidate, old
                    break
            require(coordinate_assertion is not None, "Geography baseline has no suitable finite coordinate")
            vid = vid_literal(vertex["key"].split("|", 1)[1], case["vidType"])
            old_x_hex, y_hex = coordinate_assertion[2]
            new_x_hex = "%016x" % (int(old_x_hex, 16) ^ 1)
            new_x = struct.unpack(">d", bytes.fromhex(new_x_hex))[0]
            y = struct.unpack(">d", bytes.fromhex(y_hex))[0]
            require(math.isfinite(new_x) and abs(new_x) <= 180, "Invalid injected geography coordinate")
            statements.append('UPDATE VERTEX ON case_tag %s SET `value`=ST_GeogFromText("POINT(%s %s)");' %
                              (vid, format(new_x, ".17g"), format(y, ".17g")))
            queries.append("FETCH PROP ON * %s YIELD vertex AS v;" % vid)
            report["coordinateMutation"] = {"record": vertex["key"], "oldXBits": old_x_hex,
                                            "newXBits": new_x_hex, "xor": "0000000000000001",
                                            "unchangedYBits": y_hex}
            coordinate_assertion = ["geography", "point", [new_x_hex, y_hex]]
        elif scene == "geography_schema_shape":
            self.query(target, "SHOW CREATE TAG case_tag", work / "schema", "before")
            shape = "GEOGRAPHY" if self.args.schema_shape_mode == "generic" else "GEOGRAPHY(LINESTRING)"
            statements.append("ALTER TAG case_tag CHANGE (`value` %s NULL);" % shape)
            report["schemaShapeMode"] = self.args.schema_shape_mode
        else:
            raise RuntimeError("Unknown database fault")
        source_samples = self.observed_samples(case["sourceSpace"], queries, work / "fault-source-console")
        require(not source_samples["errors"], "Source fault-control queries failed")
        report["faultStartedAt"] = now()
        self.query(target, "\n".join(statements), work / "mutation", "inject-fault")
        if scene == "geography_schema_shape":
            time.sleep(self.args.schema_wait_ms / 1000)
            self.query(target, "SHOW CREATE TAG case_tag", work / "schema", "after")
        self.tool(self.verifier, "capture", work / "fault-target-capture.txt", allowed=(0, 1),
                  plan=work / "plan", space=target, out=work / "target-after", host=self.args.host,
                  port=self.args.port, user=self.args.user)
        report["faultCapture"] = json_file(work / "target-after/manifest.json")
        if coordinate_assertion is not None:
            actual = json.loads(records(work / "target-after")[vertex["key"]]["fields"][path])
            require(actual == coordinate_assertion, "Injected coordinate did not survive as exactly one changed bit")
            report["coordinateMutation"]["nativeOneBitChangeConfirmed"] = True
        report["faultVerification"] = self.compare(work / "source", work / "target-after",
                                                   work / "fault-comparison", definition["expectedStatus"])
        report["sourceStatsAfterFault"] = self.stats(case["sourceSpace"], work / "source-after-stats", case["expected"])
        report["targetStatsAfterFault"] = self.stats(target, work / "target-after-stats", expected_stats)
        require(report["sourceStatsBeforeImport"]["counts"] == report["sourceStatsAfterFault"]["counts"],
                "Source counts changed during fault test")
        target_samples = self.observed_samples(target, queries, work / "fault-target-console")
        same = source_samples["tablesSha256"] == target_samples["tablesSha256"] and not target_samples["errors"]
        if definition["expectedSamples"].startswith("DIFFERENT"):
            require(not same, "The targeted console samples failed to show the injected fault")
        report["faultConsole"] = {"source": source_samples, "target": target_samples,
                                  "tablesMatched": same, "expected": definition["expectedSamples"]}
        if scene == "geography_coordinate_bit":
            report["faultConsole"]["displayPrecisionIsNotNativeEquality"] = True
        report["expectedStatsAfterFault"] = expected_stats
        report["countsStayedEqual"] = report["sourceStatsAfterFault"]["counts"] == report["targetStatsAfterFault"]["counts"]
        report["targetLeftForInspection"] = True

    def expect_prepare_error(self, bundle, output, reason):
        log = output.parent / (output.name + ".txt")
        data = self.tool(self.verifier, "prepare", log, allowed=(0, 1), migration_dir=bundle, out=output)
        exit_code = self.steps[-1]["exitCode"]
        require(exit_code == 1 and reason.encode() in data, "Expected specific bundle rejection: " + reason)
        return {"expectedStatus": "ERROR", "actualStatus": "ERROR", "exitCode": exit_code,
                "expectedReason": reason, "reasonObserved": True, "logSha256": digest(log)}

    def absent_space(self, source_space, target_space, directory, name):
        output = self.query(source_space, "SHOW SPACES", directory, name)
        require(len(console_tables(output)) == 1, "SHOW SPACES did not return one complete table")
        names = []
        for line in output.decode("utf-8", errors="strict").splitlines():
            if line.startswith("|"):
                value = line.strip().strip("|").strip()
                if value.startswith('"') and value.endswith('"'):
                    names.append(json.loads(value))
        require(source_space in names, "SHOW SPACES inventory did not include the known source")
        require(target_space not in names, "Rejected import created or reused target space: " + target_space)
        return {"targetSpace": target_space, "absent": True,
                "log": str(directory / (name + ".txt")), "logSha256": digest(directory / (name + ".txt"))}

    def expect_import_error(self, bundle, target_space, directory, reason):
        log = directory / "migration-import.txt"
        data = self.tool(self.migration, "import", log, allowed=(0, 1), directory=bundle,
                         target_space=target_space, target_host=self.args.host,
                         target_graph_port=self.args.port, target_meta_port=self.args.meta_port,
                         target_user=self.args.user, schema_wait_ms=self.args.schema_wait_ms,
                         scan_limit=self.args.scan_limit)
        exit_code = self.steps[-1]["exitCode"]
        require(exit_code == 1 and reason.encode() in data, "Expected specific migration import rejection: " + reason)
        return {"expectedStatus": "ERROR", "actualStatus": "ERROR", "exitCode": exit_code,
                "expectedReason": reason, "reasonObserved": True, "logSha256": digest(log)}

    def bundle_fault(self, baseline, work, report):
        results = {}
        for name in ("csv_hash", "duplicate_key"):
            bundle = work / name / "bundle"
            copy_tree(baseline / "bundle", bundle)
            manifest = json_file(bundle / "manifest.json")
            table = next(item for item in manifest["tables"] if item["kind"] == "TAG" and item["rowCount"] >= 1000)
            data = bundle / table["file"]
            original = data.read_bytes()
            rows = original.splitlines(keepends=True)
            require(len(rows) >= 1001, "Bundle lacks required 1000-record Tag control")
            if name == "csv_hash":
                data.write_bytes(original + b"CORRUPT\n")
                reason = "CSV SHA-256 mismatch"
            else:
                data.write_bytes(original + rows[1])
                table["rowCount"] += 1
                table["sha256"] = digest(data)
                write_json(bundle / "manifest.json", manifest)
                reason = "Duplicate Tag record"
            write_json(work / name / "injection.json", {"file": table["file"], "originalSha256": hashlib.sha256(original).hexdigest(),
                                                        "modifiedSha256": digest(data), "refreshedManifest": name == "duplicate_key"})
            target = self.args.target_prefix + "_bundle_" + name
            identifier(target)
            require(len(target) <= 63, "Generated rejected-import target name exceeds 63 characters")
            before = self.absent_space(report["sourceSpace"], target, work / name / "space-check", "before-import")
            prepared = self.expect_prepare_error(bundle, work / name / "prepared-plan", reason)
            import_reason = "CSV SHA-256 mismatch" if name == "csv_hash" else "Duplicate complete key in CSV"
            imported = self.expect_import_error(bundle, target, work / name, import_reason)
            after = self.absent_space(report["sourceSpace"], target, work / name / "space-check", "after-import")
            results[name] = {"expectedStatus": "ERROR", "actualStatus": "ERROR", "status": "PASSED",
                             "verificationPrepare": prepared, "migrationImport": imported,
                             "targetAbsence": {"before": before, "after": after, "absentBeforeAndAfter": True}}
        report["subchecks"] = results

    def snapshot_fault(self, baseline, work, report):
        source = work / "baseline-proof/source"
        results = {}
        truncated = work / "truncation/target"
        copy_tree(baseline / "target", truncated)
        path = truncated / "records.jsonl"
        data = path.read_bytes()
        path.write_bytes(data[:len(data) // 2])
        results["truncation"] = self.compare(source, truncated, work / "truncation/comparison", "ERROR")

        # Build an actual second plan over the same identifiers; changing batches must be rejected.
        self.tool(self.verifier, "prepare", work / "wrong-plan-prepare.txt",
                  migration_dir=work / "baseline-proof/bundle", out=work / "wrong-plan")
        wrong = work / "wrong-plan-target"
        copy_tree(baseline / "target", wrong)
        wrong_plan = json_file(work / "wrong-plan/plan.json")
        manifest = json_file(wrong / "manifest.json")
        manifest["planId"] = wrong_plan["planId"]
        manifest["planDigest"] = wrong_plan["digest"]
        write_json(wrong / "manifest.json", manifest)
        results["wrong_plan"] = self.compare(source, wrong, work / "wrong-plan-comparison", "ERROR")

        missing = work / "synthetic-missing"
        copy_tree(baseline / "target", missing)
        changed = records(missing)
        for record in changed.values():
            record["status"] = "MISSING"
            record["error"] = "Fault injection: object was not returned"
            record["fields"] = {}
        (missing / "records.jsonl").write_text("".join(json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n"
                                                        for record in changed.values()), encoding="utf-8")
        manifest = json_file(missing / "manifest.json")
        manifest.update({"status": "INCOMPLETE", "recordsSha256": digest(missing / "records.jsonl"),
                         "okCount": 0, "missingCount": len(changed), "errorCount": 0})
        write_json(missing / "manifest.json", manifest)
        missing_copy = work / "synthetic-missing-copy"
        copy_tree(missing, missing_copy)
        results["same_missing"] = self.compare(missing, missing_copy, work / "same-missing-comparison", "ERROR")
        require(results["same_missing"]["errorRecords"] == len(changed), "Identical MISSING records were not all rejected")
        forged = work / "forged-complete"
        copy_tree(missing, forged)
        manifest["status"] = "COMPLETE"
        write_json(forged / "manifest.json", manifest)
        results["false_complete"] = self.compare(source, forged, work / "false-complete-comparison", "ERROR")
        report["subchecks"] = results
        report["syntheticMissingRecords"] = len(changed)
        report["injectionsAreFileSimulations"] = True

    def run_fault(self, definition):
        scene = definition["id"]
        case_id = self.args.target_prefix + "_" + scene
        work = self.work_root / scene
        for path in (work, self.report_root / (case_id + ".json"), self.archive_root / (case_id + ".tar.gz")):
            require(not path.exists(), "Refusing to overwrite prior output: " + str(path))
        baseline, baseline_report, case = self.baseline(definition)
        work.mkdir(parents=True)
        self.steps = []
        report = {"id": case_id, "faultId": scene, "description": definition["description"],
                  "startedAt": now(), "status": "RUNNING", "expected": definition,
                  "baselineWork": str(baseline), "baselineCaseId": case["id"],
                  "sourceSpace": case["sourceSpace"], "controlCounts": baseline_report["expected"],
                  "passMeaning": "The injected fault was detected; damaged data was never accepted as MATCH",
                  "steps": self.steps}
        try:
            self.baseline_proof(baseline, work, report)
            if definition["database"]:
                self.database_fault(definition, baseline, case, work, report)
            elif scene == "bundle_integrity":
                self.bundle_fault(baseline, work, report)
            else:
                self.snapshot_fault(baseline, work, report)
            report["actual"] = {"faultDetected": True, "acceptedAsMatch": False}
            report["status"] = "PASSED"
        except Exception as failure:
            report["status"] = "FAILED"
            report["failure"] = str(failure)
            (work / "failure.txt").write_text(traceback.format_exc(), encoding="utf-8")
        finally:
            report["finishedAt"] = now()
            write_json(work / "fault-report.json", report)
            self.markdown_report(report, work / "fault-report.md")
            report["archive"] = self.archive(work, case_id)
            write_json(self.report_root / (case_id + ".json"), report)
            self.markdown_report(report, self.report_root / (case_id + ".md"))
        return report

    def markdown_report(self, report, path):
        def linked(label, target):
            return "[%s](<%s>)" % (label, os.path.relpath(target, path.parent))
        control = report["controlCounts"]
        lines = ["# 准确性故障验收：" + report["faultId"], "", "结果：**" + report["status"] + "**。PASSED表示成功检出故障，不表示被破坏的数据一致。", "",
                 "- 基线：`%s`，%d个顶点、%d条边。" % (report["baselineCaseId"], control["vertices"], control["edges"]),
                 "- 源空间：`%s`。" % report["sourceSpace"],
                 "- 预期：`%s`；%s。" % (report["expected"]["expectedStatus"], report["description"]),
                 "- 时间：%s 至 %s。" % (report["startedAt"], report.get("finishedAt", "进行中")), ""]
        if report.get("targetSpace"):
            lines += ["本场景只修改新建目标 `%s`，保留现场供复核。" % report["targetSpace"], ""]
        if report.get("failure"):
            lines += ["失败原因：`%s`。" % report["failure"].replace("`", "'"), ""]
        actual = report.get("faultVerification")
        if actual:
            lines += ["| 实际verification状态 | matched | 成功比较对象 | 差异对象 | 错误/缺失对象 |", "| --- | --- | --- | --- | --- |",
                      "| %s | %s | %s | %s | %s |" % (actual["status"], actual["matched"], actual.get("comparedRecords", 0), actual.get("differentRecords", 0), actual.get("errorRecords", 0)), ""]
        if report.get("subchecks"):
            lines += ["| 子检查 | 实际结果 | 检出证据 |", "| --- | --- | --- |"]
            for name, item in report["subchecks"].items():
                status = item.get("actualStatus", item.get("status", "UNKNOWN"))
                evidence = "matched=%s，errorRecords=%s" % (item.get("matched", False), item.get("errorRecords", 0))
                if "migrationImport" in item:
                    evidence = "prepare退出1；import退出1；目标在SHOW SPACES前后均不存在；原因：" + item["migrationImport"]["expectedReason"]
                lines.append("| %s | %s | %s |" % (name, status, evidence))
            lines.append("")
        if report.get("sourceStatsAfterFault"):
            source = report["sourceStatsAfterFault"]["counts"]
            target = report["targetStatsAfterFault"]["counts"]
            lines += ["| 新完成统计任务的计数项 | 源 | 注入后目标 |", "| --- | --- | --- |"]
            for key in sorted(set(source) | set(target)):
                lines.append("| %s | %s | %s |" % (key, source.get(key, "—"), target.get(key, "—")))
            lines.append("")
        else:
            lines += ["统计和console证据使用已通过的完整基线副本。本场景不修改数据库内容；损坏bundle的import必须在创建目标之前拒绝。", ""]
        if report.get("faultConsole"):
            display = report["faultConsole"]
            lines += ["FETCH显示表相同：`%s`；该场景显示预期：`%s`。原始查询、输出和针对故障对象的额外抽样均归档。" % (display["tablesMatched"], display["expected"]), ""]
        if report.get("coordinateMutation"):
            item = report["coordinateMutation"]
            lines += ["坐标原生位：`%s` → `%s`，异或为`%s`；目标单比特变化确认：`%s`。console显示精度不能代替该断言。" % (item["oldXBits"], item["newXBits"], item["xor"], item.get("nativeOneBitChangeConfirmed", False)), ""]
        archive = report.get("archive", {})
        archive_path = ROOT / archive["file"] if archive.get("file") else self.archive_root / (report["id"] + ".tar.gz")
        lines += ["- " + linked("完整证据压缩包", archive_path),
                  "- " + linked("完整JSON报告", self.report_root / (report["id"] + ".json"))]
        if archive.get("sha256"):
            lines.append("- 压缩包SHA-256：`%s`，%s字节。" % (archive["sha256"], archive["bytes"]))
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def write_summary(self, reports, definitions):
        failed = sum(report["status"] == "FAILED" for report in reports)
        pending = len(definitions) - len(reports)
        status = "FAILED" if failed else ("RUNNING" if pending else "PASSED")
        prefix = self.args.target_prefix + "_summary"
        summary = {"status": status, "updatedAt": now(), "expectedScenes": len(definitions),
                   "completedScenes": len(reports), "passedScenes": sum(report["status"] == "PASSED" for report in reports),
                   "failedScenes": failed, "pendingScenes": pending, "selectedFaults": [item["id"] for item in definitions],
                   "passMeaning": "Expected faults were detected, never accepted as MATCH", "scenes": []}
        lines = ["# 准确性故障验收汇总", "", "状态：**%s**。完成%d/%d场景，通过%d，失败%d，待执行%d。" %
                 (status, len(reports), len(definitions), summary["passedScenes"], failed, pending), "",
                 "每个场景使用至少1000点和1000边的已通过基线。PASSED表示检出预期故障，不能解读为受损数据一致。", "",
                 "| 场景 | 结果 | 基线点/边 | 期望verification | 报告 | 证据 |", "| --- | --- | --- | --- | --- | --- |"]
        for report in reports:
            archive = report.get("archive", {})
            entry = {"id": report["id"], "faultId": report["faultId"], "status": report["status"],
                     "controlCounts": report["controlCounts"], "expectedStatus": report["expected"]["expectedStatus"],
                     "actual": report.get("actual"), "failure": report.get("failure"), "archive": archive,
                     "report": str(self.report_root / (report["id"] + ".json"))}
            summary["scenes"].append(entry)
            archive_link = os.path.relpath(ROOT / archive["file"], self.report_root) if archive.get("file") else ""
            lines.append("| %s | %s | %s / %s | %s | [%s](%s.md) | [tar.gz](<%s>) |" %
                         (report["faultId"], report["status"], report["controlCounts"]["vertices"], report["controlCounts"]["edges"],
                          report["expected"]["expectedStatus"], report["faultId"], report["id"], archive_link))
        if pending:
            lines += ["", "未执行场景：" + "、".join(item["id"] for item in definitions[len(reports):]) + "。"]
        write_json(self.report_root / (prefix + ".json"), summary)
        (self.report_root / (prefix + ".md")).write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-work", required=True, help="Completed bool_values_s/i case work directory")
    parser.add_argument("--geography-baseline-work", help="Completed geography_point_s/i case work directory")
    parser.add_argument("--fixtures", default=ROOT / "task/acceptance/ngql")
    parser.add_argument("--work", required=True, help="New output parent for fault evidence")
    parser.add_argument("--reports", default=ROOT / "task/acceptance/reports/faults")
    parser.add_argument("--archives", default=ROOT / "task/acceptance/artifacts/faults")
    parser.add_argument("--target-prefix", default="fault_" + time.strftime("%m%d_%H%M%S") + "_" + uuid.uuid4().hex[:4])
    parser.add_argument("--console", default="/home/sch/nebula-run/nebula-console")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9669)
    parser.add_argument("--meta-port", type=int, default=9559)
    parser.add_argument("--user", default="root")
    parser.add_argument("--password-env", default="NEBULA_PASSWORD")
    parser.add_argument("--schema-wait-ms", type=int, default=20000)
    parser.add_argument("--scan-limit", type=int, default=113)
    parser.add_argument("--heap", default="4g")
    parser.add_argument("--schema-shape-mode", choices=("incompatible", "generic"), default="incompatible",
                        help="Schema fault: POINT to LINESTRING (ERROR), or POINT to generic GEOGRAPHY (DIFFERENT)")
    parser.add_argument("--only", help="Comma-separated fault IDs")
    args = parser.parse_args()
    selected = copy.deepcopy(FAULTS)
    if args.only:
        names = set(args.only.split(","))
        selected = [fault for fault in selected if fault["id"] in names]
        if {fault["id"] for fault in selected} != names:
            parser.error("Unknown fault ID")
    if any(fault["baseline"] == "geography" for fault in selected) and not args.geography_baseline_work:
        parser.error("--geography-baseline-work is required for geography faults")
    if args.schema_shape_mode == "generic":
        for fault in selected:
            if fault["id"] == "geography_schema_shape":
                fault["description"] = "Widen GEOGRAPHY(POINT) to generic GEOGRAPHY while native POINT values stay unchanged"
                fault["expectedStatus"] = "DIFFERENT"
    campaign = FaultCampaign(args)
    for suffix in (".json", ".md"):
        require(not (campaign.report_root / (args.target_prefix + "_summary" + suffix)).exists(),
                "Refusing to overwrite an earlier fault summary")
    reports = []
    for definition in selected:
        print(now(), "START FAULT", definition["id"], flush=True)
        report = campaign.run_fault(definition)
        reports.append(report)
        campaign.write_summary(reports, selected)
        print(now(), report["status"], definition["id"], report.get("failure", ""), flush=True)
        if report["status"] != "PASSED":
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
