package com.vesoft.nebula.verification;

import java.util.SortedMap;
import java.util.TreeMap;

/** One outcome for every requested object, including explicit missing/error outcomes. */
public final class CaptureRecord {
    public String key;
    public String kind;
    public String status;
    public String error;
    public SortedMap<String, String> fields = new TreeMap<>();
}
