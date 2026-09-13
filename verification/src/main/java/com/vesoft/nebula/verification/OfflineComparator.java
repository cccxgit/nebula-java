package com.vesoft.nebula.verification;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Compares two captures without opening a database connection or requiring credentials. */
public final class OfflineComparator {
    private static final int MAX_DETAILS = 1000;
    private OfflineComparator() { }

    public static ComparisonReport compare(Path sourceDirectory, Path targetDirectory) throws Exception {
        SnapshotFiles.Snapshot source = SnapshotFiles.load(sourceDirectory);
        SnapshotFiles.Snapshot target = SnapshotFiles.load(targetDirectory);
        SnapshotManifest a = source.manifest;
        SnapshotManifest b = target.manifest;
        FileSupport.require(a.planId.equals(b.planId) && a.planDigest.equals(b.planDigest),
                "Captures belong to different plans; do not mix verification batches");
        FileSupport.require(a.vidType.equals(b.vidType), "Capture VID types differ");
        FileSupport.require(source.records.keySet().equals(target.records.keySet()),
                "Captures do not contain the same requested keys");
        ComparisonReport report = new ComparisonReport();
        report.planId = a.planId;
        report.sourceSpace = a.space;
        report.targetSpace = b.space;
        report.sourceCapturedAt = a.capturedAt;
        report.targetCapturedAt = b.capturedAt;
        Set<String> schemas = new TreeSet<>(a.schemas.keySet());
        schemas.addAll(b.schemas.keySet());
        for (String schema : schemas) {
            if (!Objects.equals(a.schemas.get(schema), b.schemas.get(schema))) {
                difference(report, "schema|" + schema, "schema", "SCHEMA_DIFFERS",
                        a.schemas.get(schema), b.schemas.get(schema));
            }
        }
        for (String key : source.records.keySet()) {
            CaptureRecord left = source.records.get(key);
            CaptureRecord right = target.records.get(key);
            if (!"OK".equals(left.status) || !"OK".equals(right.status)) {
                report.errorRecords++;
                difference(report, key, "status", "OBJECT_NOT_SUCCESSFULLY_CAPTURED",
                        left.status + ": " + left.error, right.status + ": " + right.error);
                continue;
            }
            report.comparedRecords++;
            boolean differs = false;
            Set<String> fields = new TreeSet<>(left.fields.keySet());
            fields.addAll(right.fields.keySet());
            for (String field : fields) {
                if (!Objects.equals(left.fields.get(field), right.fields.get(field))) {
                    differs = true;
                    difference(report, key, field, "FIELD_DIFFERS", left.fields.get(field), right.fields.get(field));
                }
            }
            if (differs) {
                report.differentRecords++;
            }
        }
        for (String error : a.errors) {
            difference(report, "capture", "source", "CAPTURE_ERROR", error, null);
        }
        for (String error : b.errors) {
            difference(report, "capture", "target", "CAPTURE_ERROR", null, error);
        }
        if (!"COMPLETE".equals(a.status) || !"COMPLETE".equals(b.status)) {
            report.status = "ERROR";
        } else if (report.differenceFields > 0) {
            report.status = "DIFFERENT";
        } else {
            report.status = "MATCH";
            report.matched = true;
        }
        return report;
    }

    private static void difference(ComparisonReport report, String key, String field, String reason,
                                   String source, String target) {
        report.differenceFields++;
        if (report.differences.size() >= MAX_DETAILS) {
            report.detailsTruncated = true;
            return;
        }
        ComparisonReport.Difference difference = new ComparisonReport.Difference();
        difference.key = key;
        difference.field = field;
        difference.reason = reason;
        difference.sourceValue = preview(source);
        difference.targetValue = preview(target);
        report.differences.add(difference);
    }

    private static String preview(String value) {
        return value == null || value.length() <= 256 ? value : value.substring(0, 256) + "...[see records.jsonl]";
    }
}
