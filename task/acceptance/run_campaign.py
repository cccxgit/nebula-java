#!/usr/bin/env python3
"""Serial real-cluster acceptance of console fixtures and both packaged Java tools.

Every successful case captures its source BEFORE importing a new target. All
commands and full results are preserved in a compressed, portable case archive.
No application data is deleted; choose a fresh fixture prefix for a fresh run.
"""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import time
import traceback
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[2]


def now():
    return datetime.now(timezone.utc).isoformat()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            checksum.update(chunk)
    return checksum.hexdigest()


def identifier(name):
    if not re.fullmatch(r"[a-zA-Z0-9_]+", name):
        raise ValueError("Unsafe generated space identifier")
    return "`" + name + "`"


def console_tables(output):
    """Keep complete ASCII tables, excluding varying prompts and execution times."""
    tables, current = [], []
    for line in output.splitlines():
        if re.match(rb"^\+[-+]+\+$", line.strip()):
            current.append(line)
        elif current and line.startswith(b"Got ") and b"rows (time spent" in line:
            tables.append(b"\n".join(current))
            current = []
        elif current:
            current.append(line)
    if current:
        raise RuntimeError("Unterminated console result table")
    return tables


class Campaign:
    def __init__(self, args):
        self.args = args
        self.fixture_dir = Path(args.fixtures).resolve()
        self.work_root = Path(args.work).resolve()
        self.report_root = Path(args.reports).resolve()
        self.archive_root = Path(args.archives).resolve()
        self.password = os.environ.get(args.password_env, "nebula")
        self.env = dict(os.environ)
        self.env["NEBULA_PASSWORD"] = self.password
        self.migration = ROOT / "migration/target/migration-3.8.4.jar"
        self.verifier = ROOT / "verification/target/nebula-data-verifier-3.8.4.jar"
        self.steps = []

    def command(self, argv, log, allowed=(0,), timeout=1800, reject_console_errors=False):
        log.parent.mkdir(parents=True, exist_ok=True)
        safe_argv = list(map(str, argv))
        for i, value in enumerate(safe_argv[:-1]):
            if value in ("-p", "-password"):
                safe_argv[i + 1] = "<password-env>"
        step = {"argv": safe_argv, "log": str(log), "startedAt": now()}
        self.steps.append(step)
        begin = time.monotonic()
        try:
            with log.open("wb") as output:
                result = subprocess.run(list(map(str, argv)), cwd=ROOT, env=self.env,
                                        stdout=output, stderr=subprocess.STDOUT, timeout=timeout)
            step["exitCode"] = result.returncode
            step["elapsedSeconds"] = round(time.monotonic() - begin, 3)
            data = log.read_bytes()
            step["sha256"] = digest(log)
            if result.returncode not in allowed:
                raise RuntimeError("Command failed: " + str(log) + "; exit=" + str(result.returncode))
            if reject_console_errors and re.search(rb"\[ERROR\s*\(", data):
                raise RuntimeError("Console reported an nGQL error: " + str(log))
            return data
        finally:
            step["finishedAt"] = now()

    def console(self, script, log, allow_errors=False):
        return self.command([self.args.console, "-addr", self.args.host, "-port", self.args.port,
                             "-u", self.args.user, "-p", self.password, "-timeout", "60000",
                             "-f", script], log, reject_console_errors=not allow_errors)

    def query(self, space, query, directory, name):
        script = directory / (name + ".ngql")
        script.parent.mkdir(parents=True, exist_ok=True)
        script.write_text("USE " + identifier(space) + ";\n" + query.rstrip(";\n") + ";\n")
        return self.console(script, directory / (name + ".txt"))

    def tool(self, jar, command, log, allowed=(0,), **options):
        argv = ["java", "-Xmx" + self.args.heap, "-jar", jar, command]
        for key, value in options.items():
            argv += ["--" + key.replace("_", "-"), str(value)]
        return self.command(argv, log, allowed=allowed)

    def stats(self, space, directory, expected):
        started = self.query(space, "SUBMIT JOB STATS", directory, "submit-stats")
        ids = re.findall(rb"^\|\s*(\d+)\s*\|\s*$", started, re.M)
        if len(ids) != 1:
            raise RuntimeError("Unable to determine newly submitted STATS job")
        job_id = int(ids[0])
        deadline = time.monotonic() + 180
        attempt = 0
        while True:
            state = self.query(space, "SHOW JOB " + str(job_id), directory, "job-" + str(attempt))
            if b'"FINISHED"' in state and b'"SUCCEEDED"' in state:
                break
            if b'"FAILED"' in state or time.monotonic() > deadline:
                raise RuntimeError("STATS job did not finish successfully: " + str(directory))
            time.sleep(1)
            attempt += 1
        output = self.query(space, "SHOW STATS", directory, "show-stats")
        entries = {}
        for line in output.decode("utf-8", errors="strict").splitlines():
            if not line.startswith("|"):
                continue
            values = [value.strip().strip('"') for value in line.strip().strip("|").split("|")]
            if len(values) == 3 and values[2].isdigit():
                key = values[0].lower() + "/" + values[1]
                if key in entries:
                    raise RuntimeError("Duplicate SHOW STATS key: " + key)
                entries[key] = int(values[2])
        required = {"space/vertices": expected["vertices"], "space/edges": expected["edges"]}
        required.update({"tag/" + k: v for k, v in expected["tags"].items()})
        required.update({"edge/" + k: v for k, v in expected["edgeTypes"].items()})
        if entries != required:
            raise RuntimeError("SHOW STATS differs from fixture expectation: "
                               + json.dumps({"actual": entries, "expected": required}))
        result = {"jobId": job_id, "finished": True, "counts": entries,
                  "expectedCounts": required, "matchedExpected": True}
        write_json(directory / "stats.json", result)
        return result

    def collect(self, plan, space, output, log):
        self.tool(self.verifier, "capture", log, plan=plan, space=space, out=output,
                  host=self.args.host, port=self.args.port, user=self.args.user)
        manifest = json.loads((output / "manifest.json").read_text())
        if manifest["status"] != "COMPLETE" or manifest["errorCount"] or manifest["missingCount"]:
            raise RuntimeError("Capture was incomplete")
        return manifest

    def samples(self, case, space, directory):
        query = "\n".join(q.rstrip(";\n") + ";" for q in case["sampleQueries"])
        output = self.query(space, query, directory, "fetch-samples")
        tables = console_tables(output)
        if len(tables) != len(case["sampleQueries"]):
            raise RuntimeError("Unexpected number of console FETCH sample results")
        normalized = b"\n\n".join(tables) + b"\n"
        (directory / "fetch-tables.txt").write_bytes(normalized)
        return {"queries": len(tables), "tablesSha256": hashlib.sha256(normalized).hexdigest()}

    def seed(self, case, work):
        data_key = "controlFile" if case.get("negative") else "dataFile"
        for label in ("spaceFile", "schemaFile", data_key):
            self.console(self.fixture_dir / case[label], work / (label + ".txt"))
            if label != data_key:
                time.sleep(self.args.schema_wait_ms / 1000)

    def rejected_inputs(self, case, work):
        before = self.stats(case["sourceSpace"], work / "before-rejection-stats", case["expected"])
        plan = work / "rejection-control-plan"
        control = work / "rejection-control-bundle"
        self.tool(self.migration, "export", work / "control-export.txt",
                  source_space=case["sourceSpace"], directory=control, source_host=self.args.host,
                  source_graph_port=self.args.port, source_meta_port=self.args.meta_port,
                  source_user=self.args.user, scan_limit=self.args.scan_limit)
        self.tool(self.verifier, "prepare", work / "control-prepare.txt", migration_dir=control, out=plan)
        self.collect(plan, case["sourceSpace"], work / "before-rejection", work / "before-rejection.txt")
        output = self.console(self.fixture_dir / case["dataFile"], work / "rejected-inserts.txt", allow_errors=True)
        errors = re.findall(rb"\[ERROR\s*\((-?\d+)\)\]: ([^\r\n]*)", output)
        if len(errors) != case["expectedRejects"]:
            raise RuntimeError("Expected exactly %d rejected requests, got %d" % (case["expectedRejects"], len(errors)))
        allowed = {
            "reject_int8_range": ("-1005", r"Out of range value"),
            "reject_float_infinity": ("-1005", r"Out of range value"),
            "reject_geography": ("-1005", r"^Wrong value type: ST_GeogFromText"),
            "reject_rank_min": ("-1004", r"^SyntaxError: Out of range:"),
            "reject_vid_domain": (("-1004", r"(Out of range|out of range)")
                                  if case["vidType"] == "INT64" else ("-1005", r"(vid|Vid|VID)"))
        }[case["scenarioId"]]
        for code, message in errors:
            if code.decode() != allowed[0] or not re.search(allowed[1], message.decode(errors="replace")):
                raise RuntimeError("Unexpected rejection reason: %s %s" % (code, message))
        self.collect(plan, case["sourceSpace"], work / "after-rejection", work / "after-rejection.txt")
        self.tool(self.verifier, "compare", work / "rejection-compare.txt",
                  source=work / "before-rejection", target=work / "after-rejection", out=work / "rejection-comparison")
        after = self.stats(case["sourceSpace"], work / "after-rejection-stats", case["expected"])
        if before["counts"] != after["counts"]:
            raise RuntimeError("Rejected writes changed counts")
        return {"rejectedRequests": len(errors), "expectedRejectedRequests": case["expectedRejects"],
                "errorCodes": sorted(set(code.decode() for code, _ in errors)),
                "errorMessages": sorted(set(message.decode(errors="replace") for _, message in errors)),
                "controlContentUnchanged": True, "controlStatsUnchanged": True}

    def seed_all(self, cases):
        """Create source spaces and schemas in phases; no target is created here."""
        work = self.work_root / "source-seeding"
        if work.exists():
            raise RuntimeError("Seeding output already exists: " + str(work))
        work.mkdir(parents=True)
        result = {"startedAt": now(), "status": "RUNNING", "cases": [], "steps": self.steps}
        try:
            phases = {"all": ("spaceFile", "schemaFile", "dataFile"),
                      "data": ("dataFile",), "ddl": ("spaceFile", "schemaFile")}[self.args.seed_phase]
            for label in phases:
                for case in cases:
                    print(now(), "SEED", label, case["id"], flush=True)
                    key = "controlFile" if label == "dataFile" and case.get("negative") else label
                    self.console(self.fixture_dir / case[key], work / case["id"] / (key + ".txt"))
                    if label == "dataFile":
                        result["cases"].append(case["id"])
                if label != "dataFile":
                    time.sleep(self.args.schema_wait_ms / 1000)
            result["status"] = "PASSED"
        except Exception as error:
            result["status"] = "FAILED"
            result["failure"] = str(error)
            raise
        finally:
            result["finishedAt"] = now()
            write_json(work / "seeding-report.json", result)
            result["archive"] = self.archive(work, "source-seeding")
            write_json(self.report_root / "source-seeding.json", result)

    def archive(self, work, case_id):
        self.archive_root.mkdir(parents=True, exist_ok=True)
        archive = self.archive_root / (case_id + ".tar.gz")
        with archive.open("wb") as raw:
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=6) as zipped:
                with tarfile.open(fileobj=zipped, mode="w|") as bundle:
                    bundle.add(work, arcname=case_id)
        return {"file": str(archive.relative_to(ROOT)), "bytes": archive.stat().st_size,
                "sha256": digest(archive)}

    def positive(self, case):
        case_id = case["id"]
        work = self.work_root / case_id
        for output in (work, self.report_root / (case_id + ".json"), self.archive_root / (case_id + ".tar.gz")):
            if output.exists():
                raise RuntimeError("Case output already exists; select fresh output paths: " + str(output))
        work.mkdir(parents=True)
        self.steps = []
        report = {"id": case_id, "scenarioId": case["scenarioId"], "vidType": case["vidType"],
                  "description": case["description"], "sourceSpace": case["sourceSpace"],
                  "targetSpace": case["targetSpace"], "expected": case["expected"],
                  "startedAt": now(), "status": "RUNNING", "steps": self.steps,
                  "dataRowsPerScene": case["rows"], "schemaWaitMillis": self.args.schema_wait_ms,
                  "variantCounts": case.get("variantCounts", {}), "negative": case.get("negative", False),
                  "scanLimit": self.args.scan_limit,
                  "migrationJarSha256": digest(self.migration), "verificationJarSha256": digest(self.verifier),
                  "fixtureFileHashes": case.get("files", {})}
        try:
            for relative, wanted in case.get("files", {}).items():
                actual = self.fixture_dir / relative
                if not actual.is_file() or actual.stat().st_size != wanted["bytes"] or digest(actual) != wanted["sha256"]:
                    raise RuntimeError("Fixture file differs from manifest: " + relative)
            if not self.args.already_seeded:
                self.seed(case, work)
            if case.get("negative"):
                report["rejectedInputValidation"] = self.rejected_inputs(case, work)
            if case.get("files") and not self.args.skip_source_oracle:
                oracle_path = Path(self.args.oracle_dir).resolve() / (case_id + ".json")
                oracle_result = work / "source-fixture-oracle.json"
                self.command(["java", "-Xmx" + self.args.heap, "-cp",
                              str(self.verifier) + os.pathsep + str(ROOT / "verification/target/fixture-oracle"),
                              "FixtureSourceOracle", "--oracle", oracle_path,
                              "--space", case["sourceSpace"], "--host", self.args.host,
                              "--port", self.args.port, "--user", self.args.user,
                              "--out", oracle_result], work / "source-fixture-oracle.txt")
                oracle = json.loads(oracle_result.read_text())
                if not oracle["passed"] or oracle["status"] != "PASSED" \
                        or oracle["validatedVertices"] != case["expected"]["vertices"] \
                        or oracle["validatedEdges"] != case["expected"]["edges"] \
                        or oracle["fixtureFileHashes"] != case["files"]:
                    raise RuntimeError("Source does not contain the exact intended fixture values")
                report["sourceFixtureOracle"] = {k: v for k, v in oracle.items() if k != "records"}
                report["sourceFixtureOracle"]["reportSha256"] = digest(oracle_result)
            elif self.args.skip_source_oracle:
                report["sourceFixtureOracleSkipped"] = True
                report["sourceFixtureOracleSkipReason"] = "Supplementary dynamic-default scenario uses a dedicated schema/static-default oracle; random default results are captured as the source baseline."
            report["sourceStats"] = self.stats(case["sourceSpace"], work / "source-stats", case["expected"])
            report["sourceConsole"] = self.samples(case, case["sourceSpace"], work / "source-console")
            bundle = work / "bundle"
            self.tool(self.migration, "export", work / "migration-export.txt",
                      source_space=case["sourceSpace"], directory=bundle, source_host=self.args.host,
                      source_graph_port=self.args.port, source_meta_port=self.args.meta_port,
                      source_user=self.args.user, scan_limit=self.args.scan_limit)
            plan = work / "plan"
            self.tool(self.verifier, "prepare", work / "prepare.txt", migration_dir=bundle, out=plan)
            manifest = json.loads((plan / "plan.json").read_text())
            if manifest["vertexCount"] != case["expected"]["vertices"] \
                    or manifest["edgeCount"] != case["expected"]["edges"]:
                raise RuntimeError("Exported plan does not cover expected point/edge identities")
            report["sourceCapture"] = self.collect(plan, case["sourceSpace"], work / "source", work / "source-capture.txt")
            report["sourceBaselineFinishedAt"] = now()
            report["targetImportStartedAt"] = now()
            self.tool(self.migration, "import", work / "migration-import.txt",
                      target_space=case["targetSpace"], directory=bundle, target_host=self.args.host,
                      target_graph_port=self.args.port, target_meta_port=self.args.meta_port,
                      target_user=self.args.user, schema_wait_ms=self.args.schema_wait_ms,
                      scan_limit=self.args.scan_limit)
            report["targetImportFinishedAt"] = now()
            report["migrationVerification"] = json.loads((bundle / "verification-report.json").read_text())
            migrated = report["migrationVerification"]
            if not migrated["matched"] or migrated["tagRows"] != sum(case["expected"]["tags"].values()) \
                    or migrated["edgeRows"] != case["expected"]["edges"]:
                raise RuntimeError("Migration result does not confirm all expected rows")
            report["targetCapture"] = self.collect(plan, case["targetSpace"], work / "target", work / "target-capture.txt")
            self.tool(self.verifier, "compare", work / "compare.txt",
                      source=work / "source", target=work / "target", out=work / "comparison")
            report["verification"] = json.loads((work / "comparison/comparison-report.json").read_text())
            if not report["verification"]["matched"] or report["verification"]["status"] != "MATCH" \
                    or report["verification"]["comparedRecords"] != case["expected"]["vertices"] + case["expected"]["edges"]:
                raise RuntimeError("Independent FETCH contents differ")
            report["targetStats"] = self.stats(case["targetSpace"], work / "target-stats", case["expected"])
            if report["sourceStats"]["counts"] != report["targetStats"]["counts"]:
                raise RuntimeError("Source/target SHOW STATS counts differ")
            report["targetConsole"] = self.samples(case, case["targetSpace"], work / "target-console")
            if report["sourceConsole"]["tablesSha256"] != report["targetConsole"]["tablesSha256"]:
                raise RuntimeError("Console FETCH sample tables differ")
            report["consoleSamplesMatched"] = True
            report["statsMatched"] = True
            if case["scenarioId"] == "pagination":
                report["pagination"] = []
                for limit in case["scanLimits"]:
                    self.tool(self.migration, "verify", work / ("pagination-%s.txt" % limit),
                              target_space=case["targetSpace"], directory=bundle,
                              source_host=self.args.host, source_graph_port=self.args.port,
                              source_meta_port=self.args.meta_port, source_user=self.args.user,
                              target_host=self.args.host, target_graph_port=self.args.port,
                              target_meta_port=self.args.meta_port, target_user=self.args.user,
                              scan_limit=limit)
                    result = json.loads((bundle / "verification-report.json").read_text())
                    if not result["matched"] or not result["sourceRechecked"] \
                            or result["tagRows"] != sum(case["expected"]["tags"].values()) \
                            or result["edgeRows"] != case["expected"]["edges"]:
                        raise RuntimeError("Pagination scan did not verify every expected row")
                    write_json(work / ("pagination-%s.json" % limit), result)
                    report["pagination"].append({"limit": limit, "report": result})
            report["status"] = "PASSED"
        except Exception as error:
            report["status"] = "FAILED"
            report["failure"] = str(error)
            (work / "failure.txt").write_text(traceback.format_exc())
        finally:
            report["finishedAt"] = now()
            write_json(work / "case-report.json", report)
            report["archive"] = self.archive(work, case_id)
            write_json(self.report_root / (case_id + ".json"), report)
        return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", default=ROOT / "task/acceptance/ngql")
    parser.add_argument("--work", default=ROOT / "verification/target/task-campaign")
    parser.add_argument("--reports", default=ROOT / "task/acceptance/reports")
    parser.add_argument("--archives", default=ROOT / "task/acceptance/artifacts")
    parser.add_argument("--console", default="/home/sch/nebula-run/nebula-console")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9669)
    parser.add_argument("--meta-port", type=int, default=9559)
    parser.add_argument("--user", default="root")
    parser.add_argument("--password-env", default="NEBULA_PASSWORD")
    parser.add_argument("--schema-wait-ms", type=int, default=20000)
    parser.add_argument("--scan-limit", type=int, default=113)
    parser.add_argument("--heap", default="4g")
    parser.add_argument("--oracle-dir", default=ROOT / "task/acceptance/oracles")
    parser.add_argument("--target-suffix", default="", help="Use a fresh target name for a complete independent rerun")
    parser.add_argument("--skip-source-oracle", action="store_true", help="Supplementary dynamic-default fixtures only; never accepted by the main campaign auditor")
    parser.add_argument("--only", help="Comma-separated generated case IDs")
    parser.add_argument("--already-seeded", action="store_true", help="Use freshly seeded, stable source fixtures")
    parser.add_argument("--seed-only", action="store_true", help="Create source fixtures / negative controls in phases")
    parser.add_argument("--seed-phase", choices=("all", "ddl", "data"), default="all")
    args = parser.parse_args()
    campaign = Campaign(args)
    metadata = json.loads((campaign.fixture_dir / "scenarios.json").read_text())
    cases = metadata if isinstance(metadata, list) else metadata["cases"]
    if args.only:
        selected = set(args.only.split(","))
        cases = [case for case in cases if case["id"] in selected]
        if {case["id"] for case in cases} != selected:
            parser.error("Unknown selected case ID")
    if not cases:
        parser.error("No scenarios selected")
    if args.seed_only:
        campaign.seed_all(cases)
        return 0
    for i, case in enumerate(cases, 1):
        case = dict(case)
        case["targetSpace"] += args.target_suffix
        print(now(), "START", i, "/", len(cases), case["id"], flush=True)
        report = campaign.positive(case)
        print(now(), report["status"], case["id"], report.get("failure", ""), flush=True)
        if report["status"] != "PASSED":
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
