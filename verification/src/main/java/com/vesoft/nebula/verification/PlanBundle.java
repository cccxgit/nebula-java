package com.vesoft.nebula.verification;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** A portable, fixed list of requested objects. Its counts describe the list, not the database. */
public final class PlanBundle {
    private PlanBundle() { }

    public static final class Plan {
        public String planId;
        public String digest;
        public String vidType;
        public List<String> vertices = new ArrayList<>();
        public List<EdgeKey> edges = new ArrayList<>();

        public Set<String> keys() {
            Set<String> result = new TreeSet<>();
            for (String vertex : vertices) {
                result.add(vertexKey(vertex));
            }
            for (EdgeKey edge : edges) {
                result.add(edgeKey(edge));
            }
            return result;
        }
    }

    public static final class EdgeKey {
        public String src;
        /** V:Base64 of the edge name, never an internal numeric type ID. */
        public String edge;
        public String rank;
        public String dst;
    }

    public static final class Manifest {
        public int formatVersion = 1;
        public String planId;
        public String digest;
        public String vidType;
        public String createdAt;
        public String verticesSha256;
        public String edgesSha256;
        public String sourceExportManifestSha256;
        public long vertexCount;
        public long edgeCount;
    }

    public static String vertexKey(String vid) {
        return "vertex|" + vid;
    }

    public static String edgeKey(EdgeKey edge) {
        return "edge|" + edge.edge + "|" + edge.src + "|" + edge.rank + "|" + edge.dst;
    }

    /** Extracts identifiers from an existing migration export, without depending on its JAR. */
    public static Plan prepare(Path migrationDirectory, Path output) throws Exception {
        Path directory = migrationDirectory.toAbsolutePath().normalize();
        Path manifestFile = directory.resolve("manifest.json");
        JSONObject manifest = JSON.parseObject(new String(Files.readAllBytes(manifestFile),
                StandardCharsets.UTF_8));
        FileSupport.require(manifest.getIntValue("formatVersion") == 1, "Unsupported migration format");
        FileSupport.require("N-or-V:base64;float=raw-f64-hex;temporal=integer-array"
                .equals(manifest.getString("valueEncoding")), "Unsupported migration value encoding");
        Plan plan = new Plan();
        plan.vidType = normalizeVidType(manifest.getString("vidType"));
        TreeSet<String> vertices = new TreeSet<>();
        TreeMap<String, EdgeKey> edges = new TreeMap<>();
        JSONArray tables = manifest.getJSONArray("tables");
        FileSupport.require(tables != null, "Missing migration table inventory");
        Set<String> filenames = new HashSet<>();
        Set<String> tableNames = new HashSet<>();
        for (int tableNumber = 0; tableNumber < tables.size(); tableNumber++) {
            JSONObject table = tables.getJSONObject(tableNumber);
            String kind = table.getString("kind");
            String name = table.getString("name");
            FileSupport.require("TAG".equals(kind) || "EDGE".equals(kind), "Invalid table kind");
            Identifiers.quote(name);
            FileSupport.require(tableNames.add(kind + "/" + name), "Duplicate migration table");
            String filename = table.getString("file");
            FileSupport.require(filename != null && filename.matches("[0-9]+_(tag|edge)\\.csv")
                    && filenames.add(filename), "Invalid/duplicate migration filename");
            Path csv = directory.resolve(filename);
            FileSupport.require(Files.isRegularFile(csv) && !Files.isSymbolicLink(csv), "Missing CSV");
            FileSupport.require(FileSupport.sha256(csv).equals(table.getString("sha256")),
                    "Migration CSV SHA-256 mismatch: " + filename);
            JSONArray columns = table.getJSONArray("columns");
            FileSupport.require(columns != null, "Missing migration columns");
            boolean tag = "TAG".equals(kind);
            StringBuilder header = new StringBuilder(tag ? "_vid" : "_src,_dst,_rank");
            for (int i = 0; i < columns.size(); i++) {
                header.append(",p").append(i);
            }
            Set<String> tableKeys = new HashSet<>();
            long rows = 0;
            try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.US_ASCII)) {
                FileSupport.require(header.toString().equals(reader.readLine()), "Bad migration CSV header");
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] cells = line.split(",", -1);
                    FileSupport.require(cells.length == columns.size() + (tag ? 1 : 3),
                            "Wrong migration CSV field count: " + filename);
                    Identifiers.decodeVid(cells[0], plan.vidType);
                    if (tag) {
                        FileSupport.require(tableKeys.add(cells[0]), "Duplicate Tag record in migration CSV");
                        vertices.add(cells[0]);
                    } else {
                        EdgeKey key = new EdgeKey();
                        key.src = cells[0];
                        key.dst = cells[1];
                        key.rank = cells[2];
                        key.edge = Identifiers.nameCell(name);
                        validateEdge(key, plan.vidType);
                        FileSupport.require(tableKeys.add(edgeKey(key)), "Duplicate Edge record in migration CSV");
                        FileSupport.require(edges.put(edgeKey(key), key) == null, "Duplicate edge identity");
                        // Endpoints are also listed so their actual Tag membership can be checked.
                        vertices.add(key.src);
                        vertices.add(key.dst);
                    }
                    rows++;
                }
            }
            FileSupport.require(table.containsKey("rowCount") && rows == table.getLongValue("rowCount"),
                    "Migration CSV row count differs from manifest: " + filename);
        }
        plan.vertices.addAll(vertices);
        plan.edges.addAll(edges.values());
        return write(plan, output, FileSupport.sha256(manifestFile));
    }

    /** Builds the same portable plan from the documented standalone identifier CSVs. */
    public static Plan prepareIds(Path verticesCsv, Path edgesCsv, String vidType, Path output)
            throws Exception {
        Plan plan = readIds(verticesCsv, edgesCsv, normalizeVidType(vidType));
        return write(plan, output, null);
    }

    public static Plan load(Path directory) throws Exception {
        Manifest manifest = FileSupport.readJson(directory.resolve("plan.json"), Manifest.class);
        FileSupport.require(manifest.formatVersion == 1, "Unsupported plan version");
        FileSupport.require(manifest.planId != null && !manifest.planId.isEmpty(), "Missing plan ID");
        FileSupport.require(manifest.vertexCount >= 0 && manifest.edgeCount >= 0, "Invalid plan counts");
        FileSupport.require(FileSupport.sha256(directory.resolve("vertices.csv"))
                .equals(manifest.verticesSha256), "Plan vertices SHA-256 mismatch");
        FileSupport.require(FileSupport.sha256(directory.resolve("edges.csv"))
                .equals(manifest.edgesSha256), "Plan edges SHA-256 mismatch");
        FileSupport.require(digest(manifest).equals(manifest.digest), "Plan digest mismatch");
        Plan plan = readIds(directory.resolve("vertices.csv"), directory.resolve("edges.csv"),
                normalizeVidType(manifest.vidType));
        FileSupport.require(plan.vertices.size() == manifest.vertexCount
                && plan.edges.size() == manifest.edgeCount, "Plan record count mismatch");
        plan.planId = manifest.planId;
        plan.digest = manifest.digest;
        return plan;
    }

    private static Plan readIds(Path verticesCsv, Path edgesCsv, String vidType) throws Exception {
        Plan plan = new Plan();
        plan.vidType = vidType;
        TreeSet<String> vertices = new TreeSet<>();
        TreeMap<String, EdgeKey> edges = new TreeMap<>();
        try (BufferedReader reader = Files.newBufferedReader(verticesCsv, StandardCharsets.US_ASCII)) {
            FileSupport.require("vid".equals(reader.readLine()), "Expected vertex CSV header: vid");
            String line;
            while ((line = reader.readLine()) != null) {
                Identifiers.decodeVid(line, vidType);
                FileSupport.require(vertices.add(line), "Duplicate vertex in identifier CSV");
            }
        }
        try (BufferedReader reader = Files.newBufferedReader(edgesCsv, StandardCharsets.US_ASCII)) {
            FileSupport.require("src,edge,rank,dst".equals(reader.readLine()),
                    "Expected edge CSV header: src,edge,rank,dst");
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cells = line.split(",", -1);
                FileSupport.require(cells.length == 4, "Wrong edge identifier field count");
                EdgeKey edge = new EdgeKey();
                edge.src = cells[0]; edge.edge = cells[1]; edge.rank = cells[2]; edge.dst = cells[3];
                validateEdge(edge, vidType);
                FileSupport.require(edges.put(edgeKey(edge), edge) == null, "Duplicate edge in identifier CSV");
            }
        }
        plan.vertices.addAll(vertices);
        plan.edges.addAll(edges.values());
        return plan;
    }

    private static void validateEdge(EdgeKey edge, String vidType) {
        Identifiers.decodeVid(edge.src, vidType);
        Identifiers.decodeVid(edge.dst, vidType);
        Identifiers.quote(Identifiers.decodeName(edge.edge));
        Identifiers.rankLiteral(Identifiers.decodeRank(edge.rank));
    }

    private static Plan write(Plan plan, Path output, String exportHash) throws Exception {
        FileSupport.require(!plan.vertices.isEmpty() || !plan.edges.isEmpty(),
                "The plan must contain at least one requested object");
        Path directory = FileSupport.emptyDirectory(output);
        Path verticesCsv = directory.resolve("vertices.csv");
        Path edgesCsv = directory.resolve("edges.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(verticesCsv, StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE_NEW)) {
            writer.write("vid\n");
            for (String vertex : plan.vertices) {
                writer.write(vertex); writer.write('\n');
            }
        }
        try (BufferedWriter writer = Files.newBufferedWriter(edgesCsv, StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE_NEW)) {
            writer.write("src,edge,rank,dst\n");
            for (EdgeKey edge : plan.edges) {
                writer.write(String.join(",", Arrays.asList(edge.src, edge.edge, edge.rank, edge.dst)));
                writer.write('\n');
            }
        }
        Manifest manifest = new Manifest();
        manifest.planId = UUID.randomUUID().toString();
        manifest.vidType = plan.vidType;
        manifest.createdAt = Instant.now().toString();
        manifest.verticesSha256 = FileSupport.sha256(verticesCsv);
        manifest.edgesSha256 = FileSupport.sha256(edgesCsv);
        manifest.vertexCount = plan.vertices.size();
        manifest.edgeCount = plan.edges.size();
        manifest.sourceExportManifestSha256 = exportHash;
        manifest.digest = digest(manifest);
        FileSupport.writeJson(directory.resolve("plan.json"), manifest);
        plan.planId = manifest.planId;
        plan.digest = manifest.digest;
        return plan;
    }

    private static String digest(Manifest manifest) throws Exception {
        return FileSupport.sha256("nebula-verification-plan-v1\n" + manifest.planId + "\n"
                + manifest.vidType + "\n" + manifest.verticesSha256 + "\n" + manifest.edgesSha256);
    }

    private static String normalizeVidType(String value) {
        FileSupport.require(value != null, "Missing VID type");
        String type = value.trim().toUpperCase(Locale.ROOT);
        if ("INT".equals(type)) {
            type = "INT64";
        }
        FileSupport.require("INT64".equals(type) || type.matches("FIXED_STRING\\([1-9][0-9]*\\)"),
                "VID type must be INT64 or FIXED_STRING(N)");
        if (type.startsWith("FIXED_STRING(")) {
            int length = Integer.parseInt(type.substring(13, type.length() - 1));
            FileSupport.require(length <= Short.MAX_VALUE, "FIXED_STRING length out of range");
        }
        return type;
    }
}
