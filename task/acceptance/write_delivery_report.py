#!/usr/bin/env python3
"""Generate the final Chinese delivery report only after all required evidence passes.

This script reads local evidence only. It never connects to NebulaGraph, changes fixtures,
executes tests or archives files. Use --check-only to audit without producing the report.
"""

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tarfile
import tempfile
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
FAULT_IDS = {
    "property_change", "edge_key_replace", "tag_membership",
    "geography_coordinate_bit", "geography_schema_shape",
    "bundle_integrity", "snapshot_integrity",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    require(path.is_file(), "Missing evidence: " + str(path))
    value = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(value, dict), "Expected JSON object: " + str(path))
    return value


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def instant(value):
    require(isinstance(value, str), "Missing attempt timestamp")
    parts = re.fullmatch(r"(.+T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})", value)
    require(parts is not None, "Attempt timestamp must include timezone")
    parsed = datetime.fromisoformat(parts[1] + parts[3].replace("Z", "+00:00"))
    elapsed = parsed - datetime(1970, 1, 1, tzinfo=timezone.utc)
    # Java Instant emits nanoseconds; older Python fromisoformat only accepts 3/6
    # fractional digits. Retain all nine digits for strict evidence ordering.
    return (elapsed.days * 86400 + elapsed.seconds) * 1000000000 + int((parts[2] or "").ljust(9, "0"))


class Evidence:
    def __init__(self, root):
        self.root = root.resolve()
        self.base = self.root / "task/acceptance"
        self.output = self.root / "task/任务产出报告.md"
        self.digests = {}
        self.main = []
        self.faults = []
        self.schema_stress = []
        self.types = set()

    def path(self, relative):
        path = (self.root / relative).resolve()
        require(path == self.root or self.root in path.parents,
                "Evidence path escapes repository: " + str(relative))
        require(path.is_file(), "Missing evidence: " + str(path))
        return path

    def digest(self, path):
        path = path.resolve()
        if path not in self.digests:
            self.digests[path] = sha256(path)
        return self.digests[path]

    def check_hash(self, path, expected):
        require(isinstance(expected, str) and re.fullmatch(r"[0-9a-f]{64}", expected),
                "Missing or malformed SHA-256 for " + str(path))
        require(self.digest(path) == expected, "SHA-256 mismatch: " + str(path))

    def archive(self, report):
        entry = report["archive"]
        path = self.path(entry["file"])
        self.check_hash(path, entry["sha256"])
        require(path.stat().st_size == entry["bytes"], "Archive size mismatch: " + str(path))
        return path

    def check_roundtrip(self, report, expected):
        require(report["status"] == "PASSED", "Unpassed roundtrip: " + report.get("id", "?"))
        require(report["expected"] == expected, "Changed expected counts")
        require(instant(report["sourceBaselineFinishedAt"])
                <= instant(report["targetImportStartedAt"])
                <= instant(report["targetImportFinishedAt"])
                <= instant(report["targetCapture"]["capturedAt"]),
                "Unsafe source/target capture ordering")
        count = expected["vertices"] + expected["edges"]
        for side in ("sourceCapture", "targetCapture"):
            captured = report[side]
            require(captured["status"] == "COMPLETE"
                    and captured["okCount"] == captured["recordCount"] == count
                    and captured["requestedCount"] == count
                    and captured["missingCount"] == captured["errorCount"] == 0
                    and not captured["errors"], "Incomplete capture: " + side)
        result = report["verification"]
        require(result["status"] == "MATCH" and result["matched"] is True
                and result["comparedRecords"] == count
                and all(result[key] == 0 for key in
                        ("differentRecords", "differenceFields", "errorRecords")),
                "Independent verification did not match")
        migrated = report["migrationVerification"]
        require(migrated["matched"] is True
                and migrated["tagRows"] == sum(expected["tags"].values())
                and migrated["edgeRows"] == expected["edges"], "Migration verification mismatch")
        require(report["statsMatched"] is True and report["consoleSamplesMatched"] is True,
                "Statistics or console sample comparison did not match")
        for side in ("sourceStats", "targetStats"):
            require(report[side]["finished"] is True and report[side]["matchedExpected"] is True,
                    "Incomplete or incorrect statistics")
        self.archive(report)

    def check_final_fault_artifacts(self):
        root = self.base / "reports/final-faults"
        self.fault_artifacts_path = root / "execution-artifacts.json"
        artifacts = read_json(self.fault_artifacts_path)
        self.fault_artifacts_ref = {
            "file": self.fault_artifacts_path.relative_to(self.root).as_posix(),
            "sha256": self.digest(self.fault_artifacts_path),
        }
        expected_jars = {
            "migration/target/migration-3.8.4.jar": self.jar_hashes["migrationJarSha256"],
            "verification/target/nebula-data-verifier-3.8.4.jar": self.jar_hashes["verificationJarSha256"],
            "task/deliverables/migration-3.8.4.jar": self.jar_hashes["migrationJarSha256"],
            "task/deliverables/nebula-data-verifier-3.8.4.jar": self.jar_hashes["verificationJarSha256"],
        }
        inventory = {item["file"]: item for item in artifacts["files"]}
        require(len(inventory) == len(artifacts["files"])
                and set(inventory) == set(expected_jars) | {
                    "task/acceptance/run_campaign.py", "task/acceptance/run_fault_campaign.py",
                    "task/acceptance/ngql/scenarios.json"},
                "Final fault execution inventory is incomplete or duplicated")
        for name, item in inventory.items():
            if name in expected_jars:
                require(item["sha256"] == expected_jars[name],
                        "Final fault executed a different JAR: " + name)
            # Runtime target/ files need not survive a clean checkout; the immutable
            # launch and completion inventories bind those bytes to the delivered JAR.
            if "/target/" not in name:
                path = self.path(name)
                self.check_hash(path, item["sha256"])
                require(path.stat().st_size == item["bytes"], "Final fault input size changed")
        self.fault_audit_path = root / "final-execution-audit.json"
        self.fault_audit = read_json(self.fault_audit_path)
        self.fault_audit_ref = {
            "file": self.fault_audit_path.relative_to(self.root).as_posix(),
            "sha256": self.digest(self.fault_audit_path),
        }
        require(self.fault_audit["status"] == "PASSED"
                and self.fault_audit["artifactsUnchanged"] is True
                and self.fault_audit["executionArtifacts"] == self.fault_artifacts_ref
                and all(self.fault_audit[key] == digest for key, digest in self.jar_hashes.items()),
                "Final fault executable completion audit failed")
        completed = {}
        for item in self.fault_audit["files"]:
            require(item["file"] not in completed and item["unchanged"] is True,
                    "Final fault completion inventory duplicated or changed")
            completed[item["file"]] = {key: item[key] for key in ("file", "bytes", "sha256")}
        require(completed == inventory, "Final fault inputs changed between launch and completion")

    def check_fault_binding(self, report):
        require(report["executionArtifacts"] == self.fault_artifacts_ref,
                "Fault report is not bound to the final execution inventory")
        ref = report["executionBinding"]
        path = self.path(ref["file"])
        self.check_hash(path, ref["sha256"])
        binding = read_json(path)
        require(binding["status"] == "PASSED" and binding["artifactsUnchanged"] is True
                and binding["caseId"] == report["id"] and binding["faultId"] == report["faultId"]
                and binding["executionArtifacts"] == self.fault_artifacts_ref
                and binding["finalExecutionAudit"] == self.fault_audit_ref
                and binding["archiveSha256"] == report["archive"]["sha256"]
                and all(binding[key] == digest for key, digest in self.jar_hashes.items())
                and binding["preLaunchVerification"]["verifiedBeforeLaunch"] is True
                and binding["preLaunchVerification"]["kind"] == "AGENT_OBSERVED_SHA256_CHECK"
                and bool(binding["preLaunchVerification"]["explanation"])
                and binding["actualCommandJarArgumentsVerified"] is True,
                "Final fault execution binding did not pass")
        used = {entry["file"]: entry["sha256"] for entry in binding["executedJars"]}
        wanted = {"verification/target/nebula-data-verifier-3.8.4.jar": self.jar_hashes["verificationJarSha256"]}
        if report["faultId"] != "snapshot_integrity":
            wanted["migration/target/migration-3.8.4.jar"] = self.jar_hashes["migrationJarSha256"]
        require(used == wanted and len(used) == len(binding["executedJars"]),
                "Actual fault command JARs do not match the required executed artifacts")
        require(instant(report["finishedAt"]) <= instant(self.fault_audit["checkedAt"])
                <= instant(binding["boundAt"]), "Final fault binding predates completion")
        original = {key: value for key, value in report.items()
                    if key not in ("executionArtifacts", "executionBinding")}
        original_bytes = (json.dumps(original, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        require(hashlib.sha256(original_bytes).hexdigest() == binding["originalReportSha256"],
                "Fault binding does not preserve the original external report")
        archive = self.archive(report)
        with tarfile.open(archive, "r:gz") as stream:
            member = report["id"] + "/fault-report.json"
            matches = [item for item in stream.getmembers() if item.name == member]
            require(len(matches) == 1 and matches[0].isfile(),
                    "Missing or duplicated original archived fault report")
            with stream.extractfile(matches[0]) as source:
                archived = json.load(source)
        require(archived == {key: value for key, value in original.items() if key != "archive"},
                "External fault result differs from the preserved archive")

    def check_schema_stress(self):
        stress_metadata_path = self.base / "schema-probes/scenarios.json"
        self.stress_metadata = read_json(stress_metadata_path)
        stress_cases = {case["id"]: case for case in self.stress_metadata["cases"]}
        require(self.stress_metadata["supplementary"] is True
                and set(stress_cases) == {"schema_defaults_s", "schema_defaults_i"}
                and len(self.stress_metadata["cases"]) == 2,
                "Both independent Schema DEFAULT/COMMENT stress fixtures are required")
        stress_root = self.base / "reports/schema-stress"
        for case_id in sorted(stress_cases):
            case = stress_cases[case_id]
            path = stress_root / (case_id + ".json")
            report = read_json(path)
            require(report["id"] == case_id and report["sourceFixtureOracleSkipped"] is True
                    and case["expected"]["vertices"] == case["expected"]["edges"] == 1000,
                    "Schema stress must identify its dedicated oracle and complete control counts")
            self.check_roundtrip(report, case["expected"])
            require(all(report[key] == digest for key, digest in self.jar_hashes.items()),
                    "Schema stress used a different JAR than the delivered artifact")
            require(report["fixtureFileHashes"] == case["files"], "Schema stress fixture inventory mismatch")
            for name, details in case["files"].items():
                fixture = self.path("task/acceptance/schema-probes/" + name)
                self.check_hash(fixture, details["sha256"])
                require(fixture.stat().st_size == details["bytes"], "Schema stress fixture byte count mismatch")
            oracles = {}
            native_schemas = {}
            metadata_results = {}
            for side in ("source", "target"):
                oracle_path = stress_root / (case_id + "-" + side + "-oracle.json")
                oracle = read_json(oracle_path)
                require(oracle["caseId"] == case_id and oracle["space"] == report[side + "Space"]
                        and oracle["status"] == "PASSED" and oracle["passed"] is True
                        and oracle["schemaAndCommentsMatched"] is True
                        and oracle["dynamicOriginalExpressionsRetained"] is True
                        and oracle["validatedVertices"] == oracle["validatedEdges"] == 1000
                        and oracle["validatedRows"] == len(oracle["records"]) == 2000,
                        "Dedicated SchemaDefaultsOracle did not fully pass: " + str(oracle_path))
                metadata_path = oracle_path.with_name(oracle_path.name + ".metadata.json")
                self.check_hash(metadata_path, oracle["metadataReportSha256"])
                metadata = read_json(metadata_path)
                require(metadata["caseId"] == case_id and metadata["space"] == report[side + "Space"]
                        and metadata["matchedExpected"] is True and len(metadata["tables"]) == 2,
                        "Dedicated raw SchemaMetadataProbe did not match")
                schemas = {}
                for table in metadata["tables"]:
                    key = (table["kind"], table["name"])
                    require(key not in schemas, "Duplicate supplementary metadata schema")
                    raw = base64.b64decode(table["nativeSchemaBase64"], validate=True)
                    require(base64.b64encode(raw).decode("ascii") == table["nativeSchemaBase64"]
                            and base64.b64encode(hashlib.sha256(raw).digest()).decode("ascii")
                            == table["nativeSchemaSha256Base64"], "Native supplementary Schema digest mismatch")
                    schemas[key] = table["nativeSchemaBase64"]
                require(set(schemas) == {("TAG", "defaults_tag"), ("EDGE", "defaults_edge")},
                        "Supplementary Schema inventory differs")
                native_schemas[side] = schemas
                metadata_results[side] = (metadata_path, metadata)
                oracles[side] = (oracle_path, oracle)
            require(native_schemas["source"] == native_schemas["target"],
                    "Raw Schema/default/comment bytes differ between supplementary spaces")
            require(metadata_results["source"][1]["spaceCommentBase64"]
                    == metadata_results["target"][1]["spaceCommentBase64"],
                    "Raw supplementary space COMMENT bytes differ")
            require(oracles["source"][1]["records"] == oracles["target"][1]["records"],
                    "Dedicated Schema oracle per-record native field hashes differ")
            metadata_comparison = read_json(stress_root / (case_id + "-metadata-comparison.json"))
            require(metadata_comparison["status"] == "MATCH" and metadata_comparison["matched"] is True
                    and metadata_comparison["differences"] == []
                    and metadata_comparison["comparedTables"] == 2
                    and metadata_comparison["comparedDefaultExpressions"] == 60,
                    "Supplementary metadata comparison did not cover all defaults")
            record_comparison = read_json(stress_root / (case_id + "-record-comparison.json"))
            require(record_comparison["caseId"] == case_id and record_comparison["status"] == "MATCH"
                    and record_comparison["matched"] is True and record_comparison["comparedRows"] == 2000
                    and record_comparison["comparedVertices"] == record_comparison["comparedEdges"] == 1000
                    and record_comparison["completeRecordEntriesMatched"] is True
                    and record_comparison["orderedFieldsSha256ListsMatched"] is True,
                    "Supplementary record comparison did not fully pass")
            for side in ("source", "target"):
                require(metadata_comparison[side + "Space"] == report[side + "Space"],
                        "Supplementary metadata comparison used another space")
                self.check_hash(metadata_results[side][0], metadata_comparison[side + "SnapshotSha256"])
                self.check_hash(oracles[side][0], record_comparison[side + "OracleSha256"])
            fields_list = "\n".join(item["fieldsSha256"] for item in oracles["source"][1]["records"]) + "\n"
            require(hashlib.sha256(fields_list.encode("ascii")).hexdigest()
                    == record_comparison["fieldsSha256ListSha256"],
                    "Supplementary ordered native field digest index differs")
            self.schema_stress.append((path, report, oracles))

    def check_final_archives(self):
        self.archive_audit_path = self.base / "reports/final-archive-audit.json"
        audit = read_json(self.archive_audit_path)
        selected = self.main + [(path, report) for path, report, _ in self.schema_stress]
        selected.append((self.pilot_path, self.pilot))
        expected = {path.relative_to(self.root).as_posix(): (path, report) for path, report in selected}
        require(len(expected) == len(selected) and audit["status"] == "PASSED"
                and audit["passed"] is True and audit["partial"] is False
                and audit["expectedArchives"] == audit["checkedArchives"] == len(expected)
                and len(expected) >= 93 and audit["failures"] == [] and audit["missingReports"] == [],
                "Final roundtrip archive audit is partial, failed or incomplete")
        self.check_hash(self.base / "audit_final_archives.py", audit["scriptSha256"])
        entries = {entry["reportFile"]: entry for entry in audit["archives"]}
        require(set(entries) == set(expected) and len(entries) == len(audit["archives"]),
                "Final archive audit did not cover the exact selected reports")
        for name, (path, report) in expected.items():
            entry = entries[name]
            require(entry["passed"] is True and entry["archiveFile"] == report["archive"]["file"]
                    and entry["archiveSha256"] == report["archive"]["sha256"]
                    and entry["archiveBytes"] == report["archive"]["bytes"]
                    and entry["checkedLogs"] > 0
                    and entry["sourceRecords"] == entry["targetRecords"] == 2000,
                    "Final archive evidence differs from selected case: " + name)
            self.check_hash(path, entry["reportSha256"])
        self.archive_audit = audit

    def audit(self):
        self.summary = read_json(self.base / "reports/campaign-summary.json")
        self.metadata = read_json(self.base / "ngql/scenarios.json")
        cases = {item["id"]: item for item in self.metadata["cases"]}
        self.main_count = len(cases)
        self.positive_count = sum(not item["negative"] for item in cases.values())
        self.negative_count = sum(item["negative"] for item in cases.values())
        require(self.main_count == len(self.metadata["cases"]) and self.main_count >= 90,
                "Expected at least 90 unique fixture cases including DURATION microsecond boundaries")
        require(self.positive_count >= 80 and self.negative_count == 10,
                "Expected at least 80 positive and exactly 10 rejection-control cases")
        require({"duration_microsecond_bounds_s", "duration_microsecond_bounds_i"} <= set(cases),
                "Both VID variants of DURATION int32 microsecond boundaries are required")
        require(self.summary["status"] == "PASSED" and not self.summary["failures"]
                and self.summary["expectedExecutions"] == self.summary["completedExecutions"] == self.main_count
                and len(self.summary["cases"]) == self.main_count,
                "Main campaign must pass every fixture execution, including both new DURATION cases")
        require({entry["id"] for entry in self.summary["cases"]} == set(cases),
                "Campaign IDs differ from fixture IDs")
        self.oracle_manifest = read_json(self.base / "oracles/manifest.json")
        require(set(self.oracle_manifest["cases"]) == set(cases), "Oracle case inventory differs")
        self.fixture_file_count = len({name for case in cases.values() for name in case["files"]})
        self.check_hash(self.base / "ngql/scenarios.json", self.oracle_manifest["fixturesSha256"])
        self.jar_hashes = {
            "migrationJarSha256": self.digest(self.path("task/deliverables/migration-3.8.4.jar")),
            "verificationJarSha256": self.digest(self.path("task/deliverables/nebula-data-verifier-3.8.4.jar")),
        }
        totals = {"vertices": 0, "edges": 0, "rejectedRequests": 0}
        for entry in self.summary["cases"]:
            case = cases[entry["id"]]
            require(entry["auditPassed"] is True, "Campaign contains unaudited case: " + case["id"])
            path = self.path(entry["report"])
            require(path.parent == self.base / "reports/final",
                    "Main report must come from the final full-JAR campaign")
            report = read_json(path)
            require(report["id"] == case["id"] and report["archive"] == entry["archive"],
                    "Summary and case report disagree")
            require(case["expected"]["vertices"] == case["expected"]["edges"] == 1000,
                    "Every main case must contain exactly 1000 valid vertices and edges")
            self.check_roundtrip(report, case["expected"])
            require(all(report[key] == digest for key, digest in self.jar_hashes.items()),
                    "Case used a different JAR than the delivered artifact")
            require(report["fixtureFileHashes"] == case["files"], "Fixture inventory mismatch")
            for name, details in case["files"].items():
                fixture = self.path("task/acceptance/ngql/" + name)
                self.check_hash(fixture, details["sha256"])
                require(fixture.stat().st_size == details["bytes"], "Fixture byte count mismatch")
            manifest_entry = self.oracle_manifest["cases"][case["id"]]
            oracle_path = self.path("task/acceptance/oracles/" + manifest_entry["file"])
            self.check_hash(oracle_path, manifest_entry["sha256"])
            oracle = read_json(oracle_path)
            require(oracle["id"] == case["id"] and oracle["expected"] == case["expected"]
                    and oracle["fixtureFileHashes"] == case["files"]
                    and len(oracle["records"]) == 2000, "Oracle structure or counts differ")
            for schema in oracle["schemas"]:
                for column in schema["columns"]:
                    self.types.add(column["type"])
            source_oracle = report["sourceFixtureOracle"]
            require(report.get("sourceFixtureOracleSkipped", False) is False
                    and source_oracle["passed"] is True and source_oracle["status"] == "PASSED"
                    and source_oracle["schemaMatched"] is True
                    and source_oracle["validatedVertices"] == source_oracle["validatedEdges"] == 1000
                    and source_oracle["validatedRows"] == 2000
                    and source_oracle["fixtureFileHashes"] == case["files"], "Source fixture oracle failed")
            self.check_hash(oracle_path, source_oracle["oracleSha256"])
            if case["negative"]:
                rejected = report["rejectedInputValidation"]
                require(rejected["rejectedRequests"] == case["expectedRejects"] == 1000
                        and rejected["controlContentUnchanged"] is True
                        and rejected["controlStatsUnchanged"] is True, "Illegal input altered valid controls")
                totals["rejectedRequests"] += rejected["rejectedRequests"]
            if case["scenarioId"] == "pagination":
                require([page["limit"] for page in report["pagination"]] == case["scanLimits"],
                        "Pagination limits are not fully covered")
                require(all(page["report"]["matched"] and page["report"]["sourceRechecked"]
                            for page in report["pagination"]), "Pagination recheck failed")
            totals["vertices"] += case["expected"]["vertices"]
            totals["edges"] += case["expected"]["edges"]
            self.main.append((path, report))
        self.totals = {"vertices": sum(case["expected"]["vertices"] for case in cases.values()),
                       "edges": sum(case["expected"]["edges"] for case in cases.values()),
                       "rejectedRequests": sum(case["expectedRejects"] for case in cases.values())}
        require(totals == self.summary["totals"] == self.totals
                and self.totals["vertices"] >= 90000 and self.totals["edges"] >= 90000
                and self.totals["rejectedRequests"] == 10000, "Campaign totals differ")
        require({item["vidType"] for item in cases.values()} == {"FIXED_STRING(256)", "INT64"},
                "Expected both declared VID types")
        self.check_final_fault_artifacts()
        latest = {}
        for path in (self.base / "reports/final-faults").rglob("*.json"):
            if path.name.endswith(".execution-binding.json"):
                continue
            report = read_json(path)
            fault_id = report.get("faultId")
            if fault_id not in FAULT_IDS:
                continue
            order = (instant(report["startedAt"]), instant(report["finishedAt"]))
            previous = latest.get(fault_id)
            if previous is None or order > previous[0]:
                latest[fault_id] = (order, path, report)
            elif order == previous[0]:
                require(report == previous[2], "Ambiguous latest attempt for " + fault_id)
        require(set(latest) == FAULT_IDS, "Missing required fault attempts: " + ", ".join(sorted(FAULT_IDS - set(latest))))
        for fault_id in sorted(FAULT_IDS):
            _, path, report = latest[fault_id]
            require(report["status"] == "PASSED" and report["actual"]["faultDetected"] is True
                    and report["actual"]["acceptedAsMatch"] is False,
                    "Latest fault attempt did not pass: " + fault_id)
            require(report["controlCounts"]["vertices"] == report["controlCounts"]["edges"] == 1000,
                    "Fault controls must contain 1000 vertices and edges")
            require(report["baselineVerification"]["status"] == "MATCH"
                    and report["baselineVerification"]["matched"] is True, "Fault baseline does not match")
            if fault_id == "bundle_integrity":
                require(set(report["subchecks"]) == {"csv_hash", "duplicate_key"}, "Missing bundle subcheck")
                require(all(item["status"] == "PASSED" and item["actualStatus"] == "ERROR"
                            and item["targetAbsence"]["absentBeforeAndAfter"] is True
                            for item in report["subchecks"].values()), "Bundle corruption was not rejected")
            elif fault_id == "snapshot_integrity":
                require(set(report["subchecks"]) == {"truncation", "wrong_plan", "false_complete", "same_missing"},
                        "Missing snapshot subcheck")
                require(all(item["status"] == "ERROR" and item["matched"] is False
                            for item in report["subchecks"].values()), "Snapshot corruption was not rejected")
            else:
                result = report["faultVerification"]
                require(result["status"] == report["expected"]["expectedStatus"]
                        and result["matched"] is False, "Injected database fault was not detected")
                if fault_id == "geography_coordinate_bit":
                    require(report["coordinateMutation"]["nativeOneBitChangeConfirmed"] is True,
                            "One coordinate bit mutation was not confirmed")
            self.check_fault_binding(report)
            self.faults.append((path, report))
        self.check_schema_stress()
        self.pilot_path = self.base / "reports/geography-final/geography_pilot.json"
        self.pilot = read_json(self.pilot_path)
        require(self.pilot["expected"]["vertices"] == self.pilot["expected"]["edges"] == 1000,
                "Geography pilot count differs")
        self.check_roundtrip(self.pilot, self.pilot["expected"])
        require(all(self.pilot[key] == digest for key, digest in self.jar_hashes.items()),
                "Final geography pilot used a different JAR than the delivered artifact")
        self.check_final_archives()
        self.unit = read_json(self.base / "build/unit-test-summary.json")
        self.unit_count = self.unit["totals"]["tests"]
        require(self.unit["status"] == "PASSED" and self.unit_count >= 196
                and all(self.unit["totals"][name] == 0 for name in ("failures", "errors", "skipped")),
                "Expected at least 196 passing tests including the new Schema/COMMENT regressions")
        xml_totals = {name: 0 for name in ("tests", "failures", "errors", "skipped")}
        suites = {}
        module_tests = {}
        for suite in self.unit["suites"]:
            require(suite["suite"] not in suites, "Duplicate unit suite in summary")
            suites[suite["suite"]] = suite["tests"]
            module_tests[suite["module"]] = module_tests.get(suite["module"], 0) + suite["tests"]
            xml = ET.parse(self.base / "build/surefire" / suite["module"] /
                           ("TEST-" + suite["suite"] + ".xml")).getroot()
            for name in xml_totals:
                require(int(xml.attrib.get(name, 0)) == suite[name], "Unit XML differs from summary")
                xml_totals[name] += suite[name]
        require(xml_totals == self.unit["totals"], "Unit suite totals differ")
        require(suites.get("com.vesoft.nebula.migration.MigrationBundleTest", 0) >= 48
                and suites.get("com.vesoft.nebula.migration.ValueCodecTest", 0) >= 37
                and module_tests.get("client", 0) >= 18
                and module_tests.get("verification", 0) >= 93,
                "Missing required new Schema tests or existing client/codec/verifier regression suites")
        for relative in ("reports/source-seeding.json", "reports/geography_pilot.json"):
            failed = read_json(self.base / relative)
            require(failed["status"] == "FAILED", "Expected preserved initial failure evidence")
            self.archive(failed)
        self.report_paths = sorted((self.base / "reports").rglob("*.json"))
        self.archive_paths = sorted((self.base / "artifacts").rglob("*.tar.gz"))
        return self

    def link(self, label, path):
        require(path.exists(), "Missing navigation target: " + str(path))
        relative = Path(os.path.relpath(path, self.output.parent)).as_posix()
        return "[%s](<%s>)" % (label, relative)

    def code(self, label, relative, needle):
        path = self.path(relative)
        lines = path.read_text(encoding="utf-8").splitlines()
        hits = [number for number, line in enumerate(lines, 1) if needle in line]
        require(len(hits) == 1, "Cannot uniquely locate code: " + needle)
        relative = Path(os.path.relpath(path, self.output.parent)).as_posix()
        return "[%s](<%s#L%d>)" % (label, relative, hits[0])

    def render(self):
        link = lambda label, relative: self.link(label, self.root / relative)
        engine = "migration/src/main/java/com/vesoft/nebula/migration/MigrationEngine.java"
        lines = ["# 任务产出报告", "", "本报告由实际验收证据生成。生成时间：%s。" %
                 datetime.now(timezone.utc).isoformat(), "",
                 "## 1. 完成结果与计数口径", "",
                 "%d 个主场景全部通过：%d 个正向场景和 %d 个拒绝场景，每个场景均搬迁 1000 个有效顶点与 1000 条有效边，合计 **%d 个顶点、%d 条边**。拒绝场景另外执行 **%d 次非法请求**，均被拒绝且有效对照内容、数量不变。" %
                 (self.main_count, self.positive_count, self.negative_count, self.totals["vertices"],
                  self.totals["edges"], self.totals["rejectedRequests"]), "",
                 "另有 **7 个故障检测场景**，每个以 1000 个顶点和 1000 条边为完整对照；以及 **1 个地理专项试跑**，包含 1000 个顶点和 1000 条边。故障对照可能复用同一已通过基线，不把它们重复累计成新的唯一源数据量。7 个故障场景覆盖 5 项数据库故障、2 项迁移包子检查、4 项快照子检查，共 11 项检出断言；PASSED 表示检出了预期故障，绝不表示损坏后的数据一致。", "",
                 "另行通过 **2 个 Schema DEFAULT/COMMENT 压力场景**，两种 VID 各 1000 点、1000 边，共 2000 点、2000 边。这两组与正式主矩阵分开统计，使用专用 SchemaDefaultsOracle 和 SchemaMetadataProbe 验证全部原生默认值、动态默认表达式及二进制注释。", "",
                 "选定单元测试 **%d 项通过，失败 0、错误 0、跳过 0**。完整证据链接：" % self.unit_count +
                 link("主场景汇总", "task/acceptance/reports/campaign-summary.json") + "、" +
                 link("逐场景报告", "task/acceptance/reports/README.md") + "、" +
                 link("单元测试汇总", "task/acceptance/build/unit-test-summary.json") + "。", "",
                 "本地环境：NebulaGraph **3.6**，服务端构建提交标识 **de9b3ed80**；Java 客户端及两个运行 JAR 版本 **3.8.4**。数据库位于 `/home/sch/nebula-run/nebula-3.6`，源与目标是同一集群内相互独立的图空间。主场景覆盖 FIXED_STRING(256) 与 INT64 两种 VID，所有主场景实际使用 7 分区、1 副本。该服务端标识不是本次 Java 工程的 Git 提交号。环境原始记录见 " +
                 link("environment.json", "task/acceptance/build/environment.json") + "。", "",
                 "## 2. 实现与修复", "",
                 "- 沿用前序扫描修复，本次分页场景复核：worker 在 finally 中完成 latch 通知，页结果及异常在通知前发布；分别保留每个分区的游标和末页数据，避免死锁及整页漏失。这些 client 修复已经存在于前序交付，不计作本任务新修改。位置：" +
                 self.code("边扫描 next", "client/src/main/java/com/vesoft/nebula/client/storage/scan/ScanEdgeResultIterator.java", "public ScanEdgeResult next()") + "、" +
                 self.code("点扫描 next", "client/src/main/java/com/vesoft/nebula/client/storage/scan/ScanVertexResultIterator.java", "public ScanVertexResult next()") + "。",
                 "- GEOGRAPHY：保留 Meta 的 ANY/POINT/LINESTRING/POLYGON 约束，CSV 载荷为形状树及坐标 raw64；不经 WKT 十进制往返，不排序环或点。位置：" +
                 self.code("Schema 类型", engine, "static String schemaType(") + "、" +
                 self.code("地理编码", "migration/src/main/java/com/vesoft/nebula/migration/ValueCodec.java", "private static String geography(") + "。",
                 "- 原生 Schema 重建：SHOW CREATE 可能输出未转义的字符串 DEFAULT 或裸 DATE 默认值，显示文本不能可靠重放。新导出保存完整 Schema 的 Thrift binary Base64，含列类型、nullability、原始 default_value 表达式、列/表 COMMENT 和 SchemaProp；认证 Graph 先创建全新目标空间，再以原生 Meta createTag/createEdge 建 Schema。写数据前复核完整原生 Schema、列清单及 SHOW CREATE；不省略 DEFAULT，也不对损坏的显示文本做正则修补。位置：" +
                 self.code("原生 Schema 还原与预检", engine, "static Schema nativeSchema(") + "。",
                 "- Polygon 旧协议缺陷：本地 3.6 C++ Polygon 外层列表错误声明元素类型为 STRUCT，标准 Java LIST 头会被跳过，源完整多环被目标读成空多边形。导入前先做只读原生参数回传探测；仅在标准路径失败、兼容路径保留完整环与每个坐标位时启用该连接的兼容包装，两条路径都失败则在建目标前终止。位置：" +
                 self.code("兼容探测", engine, "static boolean detectLegacyPolygonWire(") + "。",
                 "- FLOAT 支持 canonical NaN（7ff8000000000000），拒绝 ±Infinity；有限 FLOAT 必须满足 float32 提升后 raw64 不变。DOUBLE 支持 canonical NaN 与 ±Infinity。任何会被当前 Thrift 写入器规范化而改位的非 canonical NaN 均前置拒绝；地理坐标仍只接受有限值。位置：" +
                 self.code("浮点重放约束", engine, "private static void validateReplayableFloat(") + "。",
                 "- DURATION 补充两种 VID 的 microseconds 原始 int32 边界场景，各 1000 点、1000 边。本地 3.6 不将该字段归一化为 ±999999；本轮明确覆盖 Integer.MIN_VALUE 与 Integer.MAX_VALUE。已有编码按原始字段保存，补充的是此前尚未覆盖的真实边界验收。",
                 "- 独立校验读取原生 VERTEX/EDGE，比较完整键、实际 Tag 集合（含零属性 Tag）、属性、相关 Schema 和地理原始位；不比较跨空间内部 tagID/edgeType 数字。位置：" +
                 self.code("原生采集", "verification/src/main/java/com/vesoft/nebula/verification/FetchCollector.java", "public static SnapshotManifest capture(") + "、" +
                 self.code("独立地理编码", "verification/src/main/java/com/vesoft/nebula/verification/NativeValueCodec.java", "private static String geography(") + "。", "",
                 "当前夹具 Oracle 的实际 Schema 类型：`" + "`、`".join(sorted(self.types)) + "`。", "",
                 "## 3. 源基准与三类验证", "",
                 "每个主场景在导出和目标写入前执行 FixtureSourceOracle：从预制 nGQL 对应 Oracle 的已知键、表达式和 Schema 出发，逐个 FETCH 源记录，核对 1000 个顶点、1000 条边及全部原生属性。它不从 scan 导出结果反推期望，因此能阻止源夹具写入错误、漏页清单或同源编解码错误被误当成正确基准。FLOAT 期望按源单精度语义提升；GEOGRAPHY 预期表达式先经源端 YIELD 验证/规范化，实际 FETCH 结果本身不再被 Oracle 规范化。", "",
                 "源码与预制基准：" + link("FixtureSourceOracle.java", "task/acceptance/FixtureSourceOracle.java") + "、" +
                 link("Oracle 清单及摘要", "task/acceptance/oracles/manifest.json") + "。每个场景 JSON 记录 sourceFixtureOracle 结果，完整逐记录报告位于对应证据归档。", "",
                 "1. **迁移自身精确比较**：源写入 → scan → CSV 编码/解码 → 目标 INSERT → 目标 scan，比较 Schema、完整键、每个规范属性值；分页场景额外按清单要求逐个 limit 复扫并复核源。",
                 "2. **独立 FETCH 全量比较**：固定计划逐 ID 读取两端全部属性，先完成源快照，等待目标导入结束，再采集目标和离线 compare。双方必须 COMPLETE；ERROR/MISSING 即使两边完全相同也不能得到 MATCH。",
                 "3. **独立数量与直观抽查**：两端分别等待 STATS 任务完成，逐项核对顶点、边、每种 Tag/Edge 数量；控制台固定 FETCH 抽查。数量和显示文本是辅助证据，不能取代原生属性位比较。", "",
                 "## 4. 故障与专项结果", "",
                 "故障只在 final-faults 目录读取最终 JAR 的尝试，按 startedAt、finishedAt 选择每个 faultId 的最新一次，不回退使用旧版本 PASSED。每份结果绑定不可变执行文件清单、执行后摘要复核和原始归档；原报告、归档与本次交付 JAR 摘要全部核对一致。执行文件清单在首批开始后写入，其启动前 JAR 核验由 preLaunchVerification 如实记录，不将清单写入时间伪装成启动前。", "",
                 "| 故障 | 完整对照点 / 边 | 检测结果含义 | 最新报告 |", "|---|---|---|---|"]
        for path, report in self.faults:
            lines.append("| `%s` | 1000 / 1000 | PASSED：故障被检出，未接受为 MATCH | %s |" %
                         (report["faultId"], self.link("JSON", path)))
        lines += ["", "地理专项 `%s → %s`：1000 点、1000 边，迁移、独立 FETCH、统计和抽查全部通过。%s。" %
                  (self.pilot["sourceSpace"], self.pilot["targetSpace"], self.link("专项报告", self.pilot_path)), "",
                  "### Schema DEFAULT/COMMENT 补充压力场景", "",
                  "这两组补充场景允许跳过正式 FixtureSourceOracle，使用独立的 SchemaDefaultsOracle/SchemaMetadataProbe 替代；正式主矩阵不允许跳过。两端分别核对静态默认表达式对应原生值、2000 条完整记录及其字段摘要，验证动态属性实际类型和源生成值，确认动态 DEFAULT 仍保留原始非恒定表达式；两端完整 native Schema 的二进制逐字节相同，包括列/表 COMMENT、默认表达式和 SchemaProp。空间原始 COMMENT 也分别与预制值核对。迁移仍显式写入源端已生成的属性值，不在目标重新执行随机默认值。", "",
                  "| 补充场景 | 点 / 边 | 搬迁与三类比较 | 源专用 Oracle | 目标专用 Oracle |", "|---|---|---|---|---|"]
        for path, report, oracles in self.schema_stress:
            lines.append("| `%s` | 1000 / 1000 | %s | %s | %s |" %
                         (report["id"], self.link("PASSED 报告", path),
                          self.link("PASSED", oracles["source"][0]), self.link("PASSED", oracles["target"][0])))
            lines.append("| `%s` 独立复核 | 2 表 / 60 个 DEFAULT | %s | %s | — |" %
                         (report["id"],
                          self.link("元数据 MATCH", path.with_name(report["id"] + "-metadata-comparison.json")),
                          self.link("2000 条原生记录 MATCH", path.with_name(report["id"] + "-record-comparison.json"))))
        lines += ["",
                  "## 5. 保留失败尝试与修复证据", "",
                  "首次源播种在 FLOAT 非有限输入处失败；修订为 FLOAT canonical NaN 正向场景与 ±Infinity 拒绝场景，DOUBLE 保留三种非有限代表值。最初 geography pilot 因 Polygon rings 丢失失败；修复旧协议兼容后以新目标空间复测通过。失败报告及归档保留，未被改写成通过：", "",
                  "- " + link("首次 seed FAILED", "task/acceptance/reports/source-seeding.json") + "；" +
                  link("首次 seed 完整档案", "task/acceptance/artifacts/source-seeding.tar.gz") + "。",
                  "- " + link("首次地理 pilot FAILED", "task/acceptance/reports/geography_pilot.json") + "；" +
                  link("首次地理 pilot 完整档案", "task/acceptance/artifacts/geography_pilot.tar.gz") + "。",
                  "- " + link("原生协议探针说明及证据", "task/acceptance/probes/README.md") + "。", "",
                  "以下自动列出报告目录中全部 FAILED 记录。失败可能来自产品、夹具或验收脚本，不能只凭 FAILED 状态归因；例如边键替换故障曾因脚本未正确处理 console 的 Empty set 显示而中断，该尝试属于验收解析问题。正式结论只采用每个场景已经复核的最新通过证据。", "",
                  "| 保留失败报告 | 原始失败原因简述 | 归档 |", "|---|---|---|"]
        for path in self.report_paths:
            report = read_json(path)
            if report.get("status") != "FAILED":
                continue
            reason = report.get("failure") or report.get("failures") or "汇总标记 FAILED，具体原因见报告"
            reason = str(reason).replace("\n", " ").replace("|", "\\|").replace("`", "'")
            if len(reason) > 320:
                reason = reason[:317] + "…"
            archive = report.get("archive", {}).get("file")
            archived = link("完整归档", archive) if archive else "见其逐场景报告"
            lines.append("| %s | %s | %s |" %
                         (self.link(path.relative_to(self.base / "reports").as_posix(), path), reason, archived))
        lines += ["", "## 6. 交付导航与复核方式", ""]
        navigation = [
            ("原始需求", "task/task.md"), ("手动测试指导书", "task/acceptance/手动测试指导书.md"),
            ("主场景矩阵", "task/acceptance/SCENARIO_MATRIX.md"), ("故障矩阵", "task/acceptance/FAULT_MATRIX.md"),
            ("预制 nGQL 说明", "task/acceptance/README-FIXTURES.md"),
            ("全部 nGQL 与代表值数量/文件摘要索引", "task/acceptance/ngql/scenarios.json"),
            ("全部 Oracle JSON 与摘要索引", "task/acceptance/oracles/manifest.json"),
            ("Schema 压力场景 nGQL 及原始预期", "task/acceptance/schema-probes/scenarios.json"),
            ("Schema 默认值专用源/目标 Oracle", "task/acceptance/schema-probes/SchemaDefaultsOracle.java"),
            ("Schema 原始元数据独立探针", "task/acceptance/schema-probes/SchemaMetadataProbe.java"),
            ("搬迁操作手册", "数据搬迁操作手册.md"), ("校验工具使用手册", "数据校验工具使用手册.md"),
            ("实现分析报告", "migration/系统软件实现分析报告.md"),
            ("独立运行包说明", "task/deliverables/README.md"),
            ("搬迁 JAR", "task/deliverables/migration-3.8.4.jar"),
            ("校验 JAR", "task/deliverables/nebula-data-verifier-3.8.4.jar"),
            ("JAR SHA256SUMS", "task/deliverables/SHA256SUMS"),
            ("构建/%d 项单元测试汇总" % self.unit_count, "task/acceptance/build/unit-test-summary.json"),
            ("主场景执行及归档索引", "task/acceptance/reports/README.md"),
            ("最终故障执行文件摘要", "task/acceptance/reports/final-faults/execution-artifacts.json"),
            ("最终故障完成摘要复核", "task/acceptance/reports/final-faults/final-execution-audit.json"),
            ("最终 JAR 地理专项复测", "task/acceptance/reports/geography-final/geography_pilot.json"),
            ("93 份最终往返归档的内部证据审计", "task/acceptance/reports/final-archive-audit.json"),
        ]
        lines += ["- " + link(label, relative) + "。" for label, relative in navigation]
        lines += ["", "主场景共交付 **%d 个 nGQL 文件、%d 份逐场景 Oracle JSON**；nGQL 索引列出每个文件的路径、字节数和 SHA-256，Oracle 索引列出每个基准文件的路径和 SHA-256。" %
                  (self.fixture_file_count, len(self.oracle_manifest["cases"])), "",
                  "往返场景 tar.gz 保存当次完整 CSV、manifest、固定计划、原生快照、NGQL、统计、console 输出及工具日志；故障场景归档保存各自完整对照和故障证据。独立归档审计逐一核对最终主矩阵、两组 Schema 压力及地理专项共 93 份档案内的原报告、全部命令日志摘要、CSV 摘要与实际行数、计划清单及双端原生快照的摘要/行数/计划身份；7 份最终故障档案另外逐项封存核验。解包后可用独立校验 JAR 的 compare 命令复核快照；源 Oracle 清单和 nGQL 均单独交付，能够重新构造源数据。所有摘要均由本脚本读取当前本地文件计算，SHA-256 用于完整性核对，不代表签名认证。", "",
                  "## 7. 覆盖范围与限制", "",
                  "本次结果是指定 NebulaGraph 3.6 环境下的有限代表值和边界场景验收，包含全部 15 种持久化属性类型及地理三种形状；它不是对无限值域的穷举，不是跨版本/跨集群商用认证，也不证明性能、并发写入一致性、故障恢复或断点续传。每场景的 1000 条记录内部循环分配代表值，不能宣称每个代表值各测了 1000 次。", "",
                  "源数据和 Schema 在整个操作期间必须稳定，TTL 不得活跃，目标空间必须全新。FIXED_STRING 属性与字符串 VID 含 NUL、无 Tag 顶点仍在先前约定的源数据排除范围；普通 STRING 任意字节继续按原始 byte[] 处理。最小 INT64 边 rank 因该版 nGQL 语法不可重放而明确拒绝；INT64 VID 与属性的最小值保留支持。", "",
                  "原生 Schema 重放要求目标 Meta 服务可访问并获准写入新空间，且源目标兼容 Nebula 默认表达式的二进制编码和平台布局。Meta version handshake 不单独证明表达式兼容；遇到元数据 leader 重定向或写入失败会明确终止，不做自动重试。旧导出包若包含 DEFAULT 却没有 nativeSchemaBase64，必须重新导出；不会通过丢弃默认值完成导入。", "",
                  "verification 的范围是清单内对象内容；单独使用它不能发现清单以外新增的目标对象，也不能从不完整扫描清单证明全库完整性。本次以已知源 FixtureOracle、全量迁移回扫和逐类型 STATS 补充证明。console 会格式化浮点/地理坐标，显示相同不保证原生位相同；坐标单 bit 故障以原生位断言为准。", "",
                  "## 8. 报告及完整归档 SHA-256 索引", "",
                  "本次结论引用 %d 个主场景、%d 个最新故障场景、%d 个 Schema 压力场景和 1 个地理专项，共 **%d 个通过场景报告**。当前报告目录实际有 **%d 个 JSON 文件**，完整归档目录有 **%d 个 tar.gz 文件**；后两项包括汇总、专用 Oracle、播种及保留失败尝试，不等于通过场景数。" %
                  (self.main_count, len(self.faults), len(self.schema_stress),
                   self.main_count + len(self.faults) + len(self.schema_stress) + 1,
                   len(self.report_paths), len(self.archive_paths)), "",
                  "### 报告 JSON", "", "| 文件 | SHA-256 |", "|---|---|"]
        for path in self.report_paths:
            lines.append("| %s | `%s` |" % (self.link(path.relative_to(self.base / "reports").as_posix(), path), self.digest(path)))
        lines += ["", "### 完整证据归档", "", "| 文件 | 字节数 | SHA-256 |", "|---|---:|---|"]
        for path in self.archive_paths:
            lines.append("| %s | %d | `%s` |" %
                         (self.link(path.relative_to(self.base / "artifacts").as_posix(), path),
                          path.stat().st_size, self.digest(path)))
        lines += ["", "### 交付 JAR", "", "| 文件 | SHA-256 |", "|---|---|"]
        for name in ("migration-3.8.4.jar", "nebula-data-verifier-3.8.4.jar"):
            path = self.root / "task/deliverables" / name
            lines.append("| %s | `%s` |" % (self.link(name, path), self.digest(path)))
        return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="Repository root containing task evidence")
    parser.add_argument("--check-only", action="store_true", help="Audit without writing the report")
    parser.add_argument("--overwrite", action="store_true", help="Replace an existing report after a new full audit")
    args = parser.parse_args()
    try:
        evidence = Evidence(args.root).audit()
        text = evidence.render()
        if args.check_only:
            print("PASSED: %d main executions, %d latest fault attempts, %d Schema stress cases, geography pilot, %d unit tests; no report written" %
                  (evidence.main_count, len(evidence.faults), len(evidence.schema_stress), evidence.unit_count))
            return 0
        require(args.overwrite or not evidence.output.exists(), "Report already exists; use --overwrite explicitly")
        # Only create an output after every gate and navigation target has been checked.
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=evidence.output.parent,
                                         prefix=".delivery-report-", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(text)
        try:
            if args.overwrite:
                os.replace(temporary, evidence.output)
            else:
                os.link(temporary, evidence.output)  # Atomic no-overwrite publication.
        finally:
            temporary.unlink(missing_ok=True)
        print("Wrote " + str(evidence.output))
        return 0
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError, tarfile.TarError) as failure:
        print("Report not generated: " + str(failure), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
