package com.vesoft.nebula.verification;

import com.alibaba.fastjson.JSON;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Standalone entry point; only capture needs a graph connection. */
public final class VerifierCli {
    private VerifierCli() { }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    public static int run(String[] args) {
        Path reportDirectory = null;
        try {
            if (args.length == 0 || (args.length == 1
                    && ("--help".equals(args[0]) || "help".equals(args[0])))) {
                help();
                return 0;
            }
            String command = args[0];
            Options options = new Options(args);
            switch (command) {
                case "prepare": {
                    options.only("migration-dir", "out");
                    PlanBundle.Plan plan = PlanBundle.prepare(options.path("migration-dir"), options.path("out"));
                    printPlan(plan, options.path("out"));
                    return 0;
                }
                case "prepare-ids": {
                    options.only("vertices", "edges", "vid-type", "out");
                    PlanBundle.Plan plan = PlanBundle.prepareIds(options.path("vertices"), options.path("edges"),
                            options.required("vid-type"), options.path("out"));
                    printPlan(plan, options.path("out"));
                    return 0;
                }
                case "capture": {
                    options.only("plan", "space", "out", "host", "port", "user", "password-env", "timeout-ms");
                    PlanBundle.Plan plan = PlanBundle.load(options.path("plan"));
                    String space = options.required("space");
                    Path out = options.path("out");
                    String variable = options.value("password-env", "NEBULA_PASSWORD");
                    String password = System.getenv(variable);
                    FileSupport.require(password != null, "Missing password environment variable: " + variable);
                    ConnectionSettings settings = new ConnectionSettings(options.value("host", "127.0.0.1"),
                            options.number("port", 9669), options.value("user", "root"), password);
                    settings.timeoutMs = options.number("timeout-ms", 60000);
                    SnapshotManifest manifest = FetchCollector.capture(plan, settings, space, out);
                    System.out.println(JSON.toJSONString(manifest));
                    System.out.println("Capture: " + out);
                    return "COMPLETE".equals(manifest.status) ? 0 : 1;
                }
                case "compare": {
                    options.only("source", "target", "out");
                    Path source = options.path("source");
                    Path target = options.path("target");
                    reportDirectory = FileSupport.emptyDirectory(options.path("out"));
                    ComparisonReport report = OfflineComparator.compare(source, target);
                    FileSupport.writeJson(reportDirectory.resolve("comparison-report.json"), report);
                    System.out.println(JSON.toJSONString(report));
                    System.out.println("Report: " + reportDirectory.resolve("comparison-report.json"));
                    return report.matched ? 0 : ("DIFFERENT".equals(report.status) ? 2 : 1);
                }
                default:
                    throw new IllegalArgumentException("Unknown command: " + command + "; use --help");
            }
        } catch (Exception failure) {
            String message = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            System.err.println("ERROR: " + message);
            if (reportDirectory != null) {
                ComparisonReport report = new ComparisonReport();
                report.status = "ERROR";
                ComparisonReport.Difference detail = new ComparisonReport.Difference();
                detail.key = "capture-files";
                detail.field = "integrity";
                detail.reason = message;
                report.differences.add(detail);
                report.differenceFields = 1;
                try {
                    FileSupport.writeJson(reportDirectory.resolve("comparison-report.json"), report);
                } catch (Exception writeFailure) {
                    System.err.println("Unable to write error report: " + writeFailure.getMessage());
                }
            }
            return 1;
        }
    }

    private static void printPlan(PlanBundle.Plan plan, Path output) {
        System.out.println("Plan: " + output + "; planId=" + plan.planId + "; listed vertices="
                + plan.vertices.size() + "; listed edges=" + plan.edges.size());
        System.out.println("Scope: contents of listed objects only; no database-wide count assertion.");
    }

    private static void help() {
        System.out.println("Nebula Data Verifier: separate source/target FETCH capture and offline comparison.\n"
                + "prepare --migration-dir EXPORT --out PLAN\n"
                + "prepare-ids --vertices VERTICES_CSV --edges EDGES_CSV --vid-type TYPE --out PLAN\n"
                + "capture --plan PLAN --space SPACE --out CAPTURE [--host 127.0.0.1] [--port 9669]\n"
                + "        [--user root] [--password-env NEBULA_PASSWORD] [--timeout-ms 60000]\n"
                + "compare --source SOURCE_CAPTURE --target TARGET_CAPTURE --out REPORT_DIRECTORY\n"
                + "All output directories must be new or empty. Copy the SAME plan to both environments.\n"
                + "Exit codes: 0=success/MATCH, 2=DIFFERENT, 1=ERROR/incomplete capture.\n"
                + "Scope: listed objects only; strings compare raw bytes, floating values compare raw bits.");
    }

    private static final class Options {
        private final Map<String, String> values = new HashMap<>();

        Options(String[] args) {
            for (int i = 1; i < args.length; i += 2) {
                FileSupport.require(args[i].startsWith("--") && args[i].length() > 2
                        && i + 1 < args.length && !args[i + 1].startsWith("--"),
                        "Expected --name value arguments");
                String key = args[i].substring(2);
                FileSupport.require(values.put(key, args[i + 1]) == null, "Duplicate option: --" + key);
            }
        }

        void only(String... allowed) {
            Set<String> names = new HashSet<>(Arrays.asList(allowed));
            for (String key : values.keySet()) {
                FileSupport.require(names.contains(key), "Unknown option: --" + key);
            }
        }

        String required(String key) {
            String value = values.get(key);
            FileSupport.require(value != null && !value.isEmpty(), "Required option: --" + key);
            return value;
        }

        String value(String key, String fallback) { return values.containsKey(key) ? required(key) : fallback; }
        Path path(String key) { return Paths.get(required(key)).toAbsolutePath().normalize(); }
        int number(String key, int fallback) { return Integer.parseInt(value(key, Integer.toString(fallback))); }
    }
}
