package com.vesoft.nebula.verification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Applies only to objects in the fixed plan, not to unlisted objects or global counts. */
public final class ComparisonReport {
    public String scope = "CONTENTS_OF_LISTED_OBJECTS_ONLY";
    public String comparedAt = Instant.now().toString();
    public String status;
    public boolean matched;
    public String planId;
    public String sourceSpace;
    public String targetSpace;
    public String sourceCapturedAt;
    public String targetCapturedAt;
    public long comparedRecords;
    public long differentRecords;
    public long errorRecords;
    public long differenceFields;
    public boolean detailsTruncated;
    public List<Difference> differences = new ArrayList<>();

    public static final class Difference {
        public String key;
        public String field;
        public String reason;
        public String sourceValue;
        public String targetValue;
    }
}
