package com.vesoft.nebula.verification;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/** Portable native-value captures, with mandatory completion metadata and integrity checks. */
public final class SnapshotFiles {
    private SnapshotFiles() { }

    public static final class Snapshot {
        public SnapshotManifest manifest;
        public SortedMap<String, CaptureRecord> records;
    }

    public static BufferedWriter begin(Path directory) throws Exception {
        Path out = FileSupport.emptyDirectory(directory);
        return Files.newBufferedWriter(out.resolve("records.jsonl"), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }

    public static void writeRecord(BufferedWriter writer, CaptureRecord record) throws Exception {
        validateRecord(record);
        writer.write(JSON.toJSONString(record, SerializerFeature.MapSortField));
        writer.write('\n');
    }

    public static String keyDigest(Set<String> keys) throws Exception {
        StringBuilder text = new StringBuilder("nebula-requested-keys-v1\n");
        for (String key : new TreeSet<>(keys)) {
            text.append(key).append('\n');
        }
        return FileSupport.sha256(text.toString());
    }

    /** Call only after closing the records writer. Incomplete captures remain explicitly marked. */
    public static void finish(Path directory, SnapshotManifest manifest) throws Exception {
        SortedMap<String, CaptureRecord> records = readRecords(directory.resolve("records.jsonl"));
        manifest.recordsSha256 = FileSupport.sha256(directory.resolve("records.jsonl"));
        validateManifest(manifest, records);
        FileSupport.writeJson(directory.resolve("manifest.json"), manifest);
    }

    public static Snapshot load(Path directory) throws Exception {
        Snapshot result = new Snapshot();
        result.manifest = FileSupport.readJson(directory.resolve("manifest.json"), SnapshotManifest.class);
        Path file = directory.resolve("records.jsonl");
        FileSupport.require(Files.isRegularFile(file) && !Files.isSymbolicLink(file), "Missing capture records");
        FileSupport.require(FileSupport.sha256(file).equals(result.manifest.recordsSha256),
                "Capture records SHA-256 mismatch: " + file);
        result.records = readRecords(file);
        validateManifest(result.manifest, result.records);
        return result;
    }

    private static SortedMap<String, CaptureRecord> readRecords(Path file) throws Exception {
        SortedMap<String, CaptureRecord> records = new TreeMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            long lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                FileSupport.require(!line.isEmpty(), "Blank capture record at line " + lineNumber);
                CaptureRecord record = JSON.parseObject(line).toJavaObject(CaptureRecord.class);
                validateRecord(record);
                FileSupport.require(records.put(record.key, record) == null,
                        "Duplicate requested object in capture: " + record.key);
            }
        }
        return records;
    }

    private static void validateRecord(CaptureRecord record) {
        FileSupport.require(record != null && record.key != null && !record.key.isEmpty(), "Missing record key");
        FileSupport.require("VERTEX".equals(record.kind) || "EDGE".equals(record.kind), "Invalid record kind");
        FileSupport.require(record.key.startsWith("VERTEX".equals(record.kind) ? "vertex|" : "edge|"),
                "Record key/kind mismatch");
        FileSupport.require(Arrays.asList("OK", "MISSING", "ERROR").contains(record.status), "Invalid record status");
        FileSupport.require(record.fields != null, "Missing record fields");
        for (java.util.Map.Entry<String, String> field : record.fields.entrySet()) {
            FileSupport.require(field.getKey() != null && field.getValue() != null, "Null capture field");
        }
        if ("OK".equals(record.status)) {
            FileSupport.require(record.error == null || record.error.isEmpty(), "OK record has an error");
            if ("VERTEX".equals(record.kind)) {
                FileSupport.require(record.fields.containsKey("$vid"), "Vertex is missing native VID");
                boolean hasTag = false;
                for (java.util.Map.Entry<String, String> field : record.fields.entrySet()) {
                    if (field.getKey().startsWith("tag/") && "present".equals(field.getValue())) {
                        hasTag = true;
                    }
                }
                FileSupport.require(hasTag, "Vertex with no actual Tag cannot be an OK listed object");
            } else {
                FileSupport.require(record.fields.keySet().containsAll(
                        Arrays.asList("$src", "$dst", "$name", "$rank")), "Edge identity is incomplete");
            }
        } else {
            FileSupport.require(record.error != null && !record.error.isEmpty(), "Missing failure explanation");
        }
    }

    private static void validateManifest(SnapshotManifest manifest, SortedMap<String, CaptureRecord> records)
            throws Exception {
        FileSupport.require(manifest != null && manifest.formatVersion == 1
                && "nebula-native-fields-v1".equals(manifest.valueFormat), "Unsupported capture format");
        FileSupport.require(manifest.planId != null && !manifest.planId.isEmpty()
                && manifest.planDigest != null && manifest.planDigest.matches("[0-9a-f]{64}"),
                "Capture has no valid plan identity");
        FileSupport.require(manifest.vidType != null && manifest.space != null && manifest.capturedAt != null,
                "Capture metadata is incomplete");
        FileSupport.require(manifest.schemas != null && manifest.errors != null, "Missing capture schemas/errors");
        FileSupport.require(manifest.requestedCount > 0 && manifest.requestedCount == records.size()
                && manifest.recordCount == records.size(), "Capture does not cover every requested object");
        FileSupport.require(keyDigest(records.keySet()).equals(manifest.requestedKeysSha256),
                "Capture requested-key set mismatch");
        long ok = 0, missing = 0, errors = 0;
        for (CaptureRecord record : records.values()) {
            if ("OK".equals(record.status)) { ok++; }
            else if ("MISSING".equals(record.status)) { missing++; }
            else { errors++; }
        }
        FileSupport.require(manifest.okCount == ok && manifest.missingCount == missing
                && manifest.errorCount == errors, "Capture outcome counters mismatch");
        boolean complete = missing == 0 && errors == 0 && manifest.errors.isEmpty();
        FileSupport.require((complete ? "COMPLETE" : "INCOMPLETE").equals(manifest.status),
                "Capture completion status contradicts its outcomes");
    }
}
