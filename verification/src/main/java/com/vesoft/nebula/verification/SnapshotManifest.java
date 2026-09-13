package com.vesoft.nebula.verification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Metadata is separated from comparable values: times and space names are not compared. */
public final class SnapshotManifest {
    public int formatVersion = 1;
    public String valueFormat = "nebula-native-fields-v1";
    public String planId;
    public String planDigest;
    public String vidType;
    public String space;
    public String capturedAt;
    public String status;
    public String recordsSha256;
    public String requestedKeysSha256;
    public long requestedCount;
    public long recordCount;
    public long okCount;
    public long missingCount;
    public long errorCount;
    public Map<String, String> schemas = new TreeMap<>();
    public List<String> errors = new ArrayList<>();
}
