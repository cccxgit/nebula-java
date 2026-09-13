/**
 * Read-only scalar protocol probe; this class never executes CREATE, INSERT or UPDATE.
 * Requires pre-seeded qa_20260914_[s/i]_[float/double]_nonfinite source spaces.
 * Run from the repository root with JDK 11+ source-file mode:
 *   java -cp migration/target/migration-3.8.4.jar task/acceptance/probes/ScalarWireProbe.java
 * Or compile with javac -cp <jar> -d <temporary-directory> and execute that class.
 * Defaults: localhost graph 9669, meta 9559. Password: NEBULA_PASSWORD or nebula.
 * These are parameter/FETCH/scan observations, not an INSERT or migration acceptance.
 */
import com.vesoft.nebula.*;
import com.vesoft.nebula.migration.*;
import com.vesoft.nebula.client.graph.data.*;
import com.vesoft.nebula.client.storage.*;
import com.vesoft.nebula.client.storage.scan.*;
import java.util.*;

public class ScalarWireProbe {
  static String bits(Value v) {
    return v.getSetField() == Value.FVAL
      ? String.format("%016x", Double.doubleToRawLongBits(v.getFVal()))
      : "field=" + v.getSetField() + ":" + v.getFieldValue();
  }
  static void count(Map<String,Integer> histogram, Value value) {
    String bits = bits(value);
    histogram.put(bits, histogram.getOrDefault(bits, 0) + 1);
  }
  public static void main(String[] args) throws Exception {
    String password = System.getenv().getOrDefault("NEBULA_PASSWORD", "nebula");
    ConnectionConfig config = new ConnectionConfig("127.0.0.1", 9669, 9559, "root", password);
    try (MigrationEngine.GraphConnection graph = MigrationEngine.openGraph(config)) {
      for (long raw : new long[]{0L, Long.MIN_VALUE, 1L, 0x7fefffffffffffffL,
          0x7ff0000000000000L, 0xfff0000000000000L, 0x7ff8000000000000L,
          0x7ff8000000000001L, 0xfff8000000000042L}) {
        Value value = Value.fVal(Double.longBitsToDouble(raw));
        Value result = graph.execute("YIELD $p AS p", Collections.singletonMap("p", value))
            .getRows().get(0).values.get(0);
        System.out.println("PARAM " + bits(value) + " -> " + bits(result));
      }
      for (String kind : Arrays.asList("float", "double")) {
        for (String id : Arrays.asList("s", "i")) {
          String space = "qa_20260914_" + id + "_" + kind + "_nonfinite";
          graph.execute("USE `" + space + "`");
          String vid = id.equals("s") ? "\"v00000\"" : "100";
          ResultSet fetched = graph.execute("FETCH PROP ON case_tag " + vid + " YIELD case_tag.value AS value");
          for (Row row : fetched.getRows()) System.out.println("FETCH " + space + " " + bits(row.values.get(0)));
        }
      }
    }
    StorageClient storage = new StorageClient(Collections.singletonList(new HostAddress("127.0.0.1",9559)), 60000);
    storage.setUser("root").setPassword(password);
    try {
      storage.connect();
      for (String kind : Arrays.asList("float", "double")) {
        for (String id : Arrays.asList("s", "i")) {
          String space = "qa_20260914_" + id + "_" + kind + "_nonfinite";
          Map<String,Integer> histogram = new TreeMap<>();
          ScanVertexResultIterator scan = storage.scanVertex(space,"case_tag",Collections.singletonList("value"),127,0,Long.MAX_VALUE,false,false);
          while (scan.hasNext()) {
            ScanVertexResult page = scan.next();
            if (!page.isAllSuccess()) throw new IllegalStateException("Incomplete " + space);
            for (DataSet data : page.getDataSets()) for (Row row : data.rows) for (Value v : row.values) {
              if (v.getSetField() == Value.FVAL) count(histogram, v);
            }
          }
          System.out.println("SCAN " + space + " " + histogram);
        }
      }
    } finally { storage.close(); }
  }
}
