import com.facebook.thrift.TException;
import com.facebook.thrift.protocol.*;
import com.vesoft.nebula.*;
import com.vesoft.nebula.migration.*;
import com.vesoft.nebula.client.graph.data.ResultSet;
import java.util.*;

/** Read-only YIELD probes; this program never creates spaces or writes database data.
 * Run after compiling against the migration fat JAR, with NEBULA_PASSWORD set.
 */
public class GeographyWireProbe {
  static class CompatPolygon extends Polygon {
    CompatPolygon(Polygon source) { super(source.coordListList); }
    @Override public void write(TProtocol p) throws TException {
      p.writeStructBegin(new TStruct("Polygon"));
      p.writeFieldBegin(new TField("coordListList", TType.LIST, (short) 1));
      p.writeListBegin(new TList(TType.STRUCT, coordListList.size()));
      for (List<Coordinate> ring : coordListList) {
        p.writeListBegin(new TList(TType.STRUCT, ring.size()));
        for (Coordinate point : ring) point.write(p);
        p.writeListEnd();
      }
      p.writeListEnd(); p.writeFieldEnd(); p.writeFieldStop(); p.writeStructEnd();
    }
  }
  private static String password() {
    String password = System.getenv("NEBULA_PASSWORD");
    if (password == null) throw new IllegalArgumentException("Set NEBULA_PASSWORD");
    return password;
  }
  public static void main(String[] args) throws Exception {
    Coordinate a = new Coordinate(-0.0, 0.0), b = new Coordinate(1.0000000000000002,0.0);
    Coordinate c = new Coordinate(1.0,1.0), d = new Coordinate(0.0,1.0);
    Polygon polygon = new Polygon(Arrays.asList(Arrays.asList(a,b,c,d,a),
        Arrays.asList(new Coordinate(.2,.2),new Coordinate(.2,.3),new Coordinate(.3,.3),
          new Coordinate(.3,.2),new Coordinate(.2,.2))));
    Value wanted = Value.ggVal(Geography.pgVal(polygon));
    try (MigrationEngine.GraphConnection g = MigrationEngine.openGraph(
        new ConnectionConfig("127.0.0.1",9669,9559,"root",password()))) {
      for (boolean compat : new boolean[]{false,true}) {
        Value input = compat ? Value.ggVal(Geography.pgVal(new CompatPolygon(polygon))) : wanted;
        ResultSet rs = g.execute("YIELD $p AS p", Collections.singletonMap("p", input));
        Value output = rs.getRows().get(0).values.get(0);
        System.out.println("compat="+compat+" output="+new String(java.util.Base64.getDecoder()
            .decode(ValueCodec.encode(output,"GEOGRAPHY").substring(2)),"US-ASCII"));
        System.out.println("equal="+ValueCodec.encode(wanted,"GEOGRAPHY").equals(ValueCodec.encode(output,"GEOGRAPHY")));
      }
      for (String expr : Arrays.asList("toFloat(\"NaN\")","toFloat(\"Infinity\")","toFloat(\"-Infinity\")")) {
        Value out = g.execute("YIELD "+expr+" AS p").getRows().get(0).values.get(0);
        System.out.println(expr+" field="+out.getSetField()+" value="+out.getFieldValue());
      }
      for (double v : new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
        Value out = g.execute("YIELD $p AS p",Collections.singletonMap("p",Value.fVal(v))).getRows().get(0).values.get(0);
        System.out.println("native input="+v+" field="+out.getSetField()+" raw="+
          Long.toHexString(Double.doubleToRawLongBits(out.getFVal())));
      }
    }
  }
}
