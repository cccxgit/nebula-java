package com.vesoft.nebula.migration;

import java.util.ArrayList;
import java.util.List;

/** Versioned schema and file inventory, persisted as manifest.json after export succeeds. */
public final class SchemaManifest {
    public int formatVersion = 1;
    public String valueEncoding = "N-or-V:base64;float=raw-f64-hex;temporal=integer-array";
    public String sourceSpace;
    public String createdAt;
    public String vidType;
    public int partitionNum;
    public int replicaFactor;
    public String charset;
    public String collation;
    public String showCreateSpace;
    public List<Table> tables = new ArrayList<>();

    public static final class Table {
        public String kind;
        public String name;
        public String file;
        public String createStatement;
        public List<Column> columns = new ArrayList<>();
        public long rowCount;
        public String sha256;
    }

    public static final class Column {
        public String name;
        public String type;
        public boolean nullable;
        /** Original encoded metadata expression, for exact schema verification. */
        public String defaultExpressionBase64;
        public String commentBase64;
    }
}
