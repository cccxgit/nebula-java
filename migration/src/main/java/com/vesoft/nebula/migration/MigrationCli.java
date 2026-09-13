package com.vesoft.nebula.migration;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Small synchronous CLI. Passwords are read from the environment, never exported. */
public final class MigrationCli {
    private static final Set<String> OPTIONS = new HashSet<>(Arrays.asList(
        "source-space", "target-space", "directory", "source-host", "source-graph-port",
        "source-meta-port", "source-user", "source-password-env", "target-host",
        "target-graph-port", "target-meta-port", "target-user", "target-password-env",
        "scan-limit", "timeout-ms", "schema-wait-ms"));

    private MigrationCli() { }

    public static void main(String[] args) {
        if (args.length == 0 || "--help".equals(args[0]) || "help".equals(args[0])) {
            usage();
            return;
        }
        try {
            execute(args);
        } catch (Exception failure) {
            System.err.println("Migration failed; consistency has NOT been confirmed.");
            Throwable cause = failure;
            for (int i = 0; cause != null && i < 5; i++, cause = cause.getCause()) {
                System.err.println(cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
            System.exit(1);
        }
    }

    private static void execute(String[] args) throws Exception {
        String command = args[0];
        if (!Arrays.asList("migrate", "export", "import", "verify", "inspect").contains(command)) {
            throw new IllegalArgumentException("Unknown command: " + command);
        }
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("Options require --name value pairs");
            }
            String key = args[i].substring(2);
            if (!OPTIONS.contains(key) || options.put(key, args[i + 1]) != null) {
                throw new IllegalArgumentException("Unknown or duplicate option: " + key);
            }
        }
        Path directory = Paths.get(required(options, "directory")).toAbsolutePath().normalize();
        if ("inspect".equals(command)) {
            print(MigrationEngine.inspect(directory));
            return;
        }
        boolean needsSource = !"import".equals(command);
        boolean needsTarget = !"export".equals(command);
        ConnectionConfig source = connection(options, "source", needsSource);
        ConnectionConfig target = connection(options, "target", needsTarget);
        int limit = Integer.parseInt(options.getOrDefault("scan-limit", "100"));
        try (MigrationEngine engine = new MigrationEngine(source, target, limit)) {
            VerificationReport report;
            switch (command) {
                case "export":
                    print(engine.exportSpace(required(options, "source-space"), directory));
                    return;
                case "migrate":
                    report = engine.migrate(required(options, "source-space"),
                        required(options, "target-space"), directory);
                    break;
                case "import":
                    engine.importSpace(directory, required(options, "target-space"));
                    report = engine.verify(directory, required(options, "target-space"));
                    break;
                case "verify":
                    report = engine.verifySourceAndTarget(directory, required(options, "target-space"));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown command");
            }
            Files.write(directory.resolve("verification-report.json"),
                JSON.toJSONString(report, SerializerFeature.PrettyFormat).getBytes(StandardCharsets.UTF_8));
            print(report);
        }
    }

    private static ConnectionConfig connection(Map<String, String> options, String side,
                                               boolean required) {
        String specificEnv = "NEBULA_" + side.toUpperCase(java.util.Locale.ROOT) + "_PASSWORD";
        String defaultEnv = System.getenv(specificEnv) == null ? "NEBULA_PASSWORD" : specificEnv;
        String env = options.getOrDefault(side + "-password-env", defaultEnv);
        String password = System.getenv(env);
        if (required && password == null) {
            throw new IllegalArgumentException("Set password environment variable " + env);
        }
        ConnectionConfig config = new ConnectionConfig(options.getOrDefault(side + "-host", "127.0.0.1"),
            Integer.parseInt(options.getOrDefault(side + "-graph-port", "9669")),
            Integer.parseInt(options.getOrDefault(side + "-meta-port", "9559")),
            options.getOrDefault(side + "-user", "root"), password == null ? "" : password);
        config.timeoutMs = Integer.parseInt(options.getOrDefault("timeout-ms", "60000"));
        config.schemaWaitMillis = Long.parseLong(options.getOrDefault("schema-wait-ms", "20000"));
        if (config.timeoutMs <= 0 || config.schemaWaitMillis < 0) {
            throw new IllegalArgumentException("timeout-ms must be positive; schema-wait-ms must be nonnegative");
        }
        return config;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Missing --" + name);
        }
        return value;
    }

    private static void print(Object result) {
        System.out.println(JSON.toJSONString(result, SerializerFeature.PrettyFormat));
    }

    private static void usage() {
        System.out.println("Nebula CSV migration (Java 8+)\n"
            + "  migrate --source-space A --target-space B --directory DIR\n"
            + "  export  --source-space A --directory DIR\n"
            + "  import  --target-space B --directory DIR\n"
            + "  verify  --target-space B --directory DIR\n"
            + "  inspect --directory DIR\n\n"
            + "Connections: --source-host/--target-host, --source-graph-port/--target-graph-port,\n"
            + "--source-meta-port/--target-meta-port, --source-user/--target-user.\n"
            + "Defaults: 127.0.0.1, graph 9669, meta 9559, root.\n"
            + "Passwords: NEBULA_PASSWORD or NEBULA_SOURCE_PASSWORD/NEBULA_TARGET_PASSWORD;\n"
            + "override with --source-password-env/--target-password-env.\n"
            + "--scan-limit 100 --timeout-ms 60000 --schema-wait-ms 20000\n\n"
            + "Keep the source quiescent. Target must not exist; export directory must be empty.\n"
            + "No GEOGRAPHY, active TTL, untagged vertices, or NUL-containing fixed strings/VIDs.\n"
            + "Edge rank Long.MIN_VALUE is rejected because the 3.6 INSERT grammar cannot express it.\n"
            + "Rows are compared by complete keys and exact native-value encoding.\n"
            + "No automatic retry or resume; errors return exit code 1.");
    }
}
