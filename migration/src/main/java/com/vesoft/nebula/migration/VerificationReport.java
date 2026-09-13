package com.vesoft.nebula.migration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Returned only after all schema, complete-key and encoded-value comparisons pass. */
public final class VerificationReport {
    public String verifiedAt = Instant.now().toString();
    public boolean matched;
    public boolean sourceRechecked;
    public String sourceSpace;
    public String targetSpace;
    public long tagRows;
    public long edgeRows;
    public Map<String, Long> rowsByTable = new LinkedHashMap<>();
}
