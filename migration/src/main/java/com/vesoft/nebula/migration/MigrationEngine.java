package com.vesoft.nebula.migration;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.facebook.thrift.TException;
import com.facebook.thrift.TDeserializer;
import com.facebook.thrift.TSerializer;
import com.facebook.thrift.protocol.TBinaryProtocol;
import com.facebook.thrift.protocol.TField;
import com.facebook.thrift.protocol.TList;
import com.facebook.thrift.protocol.TProtocol;
import com.facebook.thrift.protocol.TStruct;
import com.facebook.thrift.protocol.TType;
import com.vesoft.nebula.Coordinate;
import com.vesoft.nebula.DataSet;
import com.vesoft.nebula.ErrorCode;
import com.vesoft.nebula.Geography;
import com.vesoft.nebula.Polygon;
import com.vesoft.nebula.PropertyType;
import com.vesoft.nebula.Row;
import com.vesoft.nebula.Value;
import com.vesoft.nebula.client.graph.NebulaPoolConfig;
import com.vesoft.nebula.client.graph.data.HostAddress;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.net.NebulaPool;
import com.vesoft.nebula.client.graph.net.Session;
import com.vesoft.nebula.client.meta.MetaClient;
import com.vesoft.nebula.client.storage.StorageClient;
import com.vesoft.nebula.client.storage.scan.ScanEdgeResult;
import com.vesoft.nebula.client.storage.scan.ScanEdgeResultIterator;
import com.vesoft.nebula.client.storage.scan.ScanVertexResult;
import com.vesoft.nebula.client.storage.scan.ScanVertexResultIterator;
import com.vesoft.nebula.meta.ColumnDef;
import com.vesoft.nebula.meta.ColumnTypeDef;
import com.vesoft.nebula.meta.CreateEdgeReq;
import com.vesoft.nebula.meta.CreateTagReq;
import com.vesoft.nebula.meta.EdgeItem;
import com.vesoft.nebula.meta.ExecResp;
import com.vesoft.nebula.meta.GeoShape;
import com.vesoft.nebula.meta.MetaService;
import com.vesoft.nebula.meta.Schema;
import com.vesoft.nebula.meta.SpaceDesc;
import com.vesoft.nebula.meta.TagItem;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Correctness-first, synchronous migration. Sources must remain quiescent from export through
 * verification. This is not a database snapshot or a backup of indexes, users or permissions.
 * All rows are held in memory so complete keys and exact encoded values can be compared.
 */
public final class MigrationEngine implements AutoCloseable {
    private static final String MANIFEST_FILE = "manifest.json";
    private final ConnectionConfig source;
    private final ConnectionConfig target;
    private final int scanLimit;

    public MigrationEngine(ConnectionConfig source, ConnectionConfig target, int scanLimit) {
        if (source == null || target == null || scanLimit < 1) {
            throw new IllegalArgumentException("Connections and positive scanLimit are required");
        }
        this.source = source;
        this.target = target;
        this.scanLimit = scanLimit;
    }

    /** Exports to a new/empty directory; manifest.json is the final completion marker. */
    public SchemaManifest exportSpace(String sourceSpace, Path directory) throws Exception {
        quoteIdentifier(sourceSpace);
        Path dir = directory.toAbsolutePath().normalize();
        Files.createDirectories(dir);
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            require(!files.findAny().isPresent(), "Export directory must be empty: " + dir);
        }
        try (GraphConnection graph = openGraph(source); Metadata metadata = new Metadata(source)) {
            SchemaManifest manifest = describe(graph, metadata.client, sourceSpace);
            Map<String, Map<String, String>> snapshot = scanAll(source, sourceSpace, manifest);
            assertSchemasEqual(manifest, describe(graph, metadata.client, sourceSpace));
            for (SchemaManifest.Table table : manifest.tables) {
                Map<String, String> rows = snapshot.get(tableKey(table));
                Path csv = dir.resolve(table.file);
                try (BufferedWriter out = Files.newBufferedWriter(csv, StandardCharsets.US_ASCII,
                        StandardOpenOption.CREATE_NEW)) {
                    out.write(header(table));
                    out.write('\n');
                    for (String row : rows.values()) {
                        out.write(row);
                        out.write('\n');
                    }
                }
                table.rowCount = rows.size();
                table.sha256 = sha256(csv);
            }
            String json = JSON.toJSONString(manifest, SerializerFeature.PrettyFormat,
                    SerializerFeature.WriteMapNullValue);
            Files.write(dir.resolve(MANIFEST_FILE), json.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW);
            return manifest;
        }
    }

    /** Validates every file/value before creating the new target space and inserting rows. */
    public void importSpace(Path directory, String targetSpace) throws Exception {
        quoteIdentifier(targetSpace);
        Bundle bundle = loadBundle(directory);
        SchemaManifest manifest = bundle.manifest;
        try (GraphConnection graph = openGraph(target)) {
            if (manifest.vidType.startsWith("FIXED_STRING(") || manifest.spaceCommentBase64 != null) {
                verifyStringLiteralSupport(graph.session);
            }
            final boolean legacyPolygonWire = hasPolygonSchema(manifest)
                    && detectLegacyPolygonWire(value -> {
                        ResultSet result = graph.execute("YIELD $migration_polygon_probe AS p",
                                Collections.singletonMap("migration_polygon_probe", value));
                        require(!result.isEmpty() && result.getRows().size() == 1
                                        && result.getRows().get(0).values.size() == 1,
                                "Polygon wire probe did not return exactly one value");
                        return result.getRows().get(0).values.get(0);
                    });
            ResultSet spaces = graph.execute("SHOW SPACES");
            if (!spaces.isEmpty()) {
                for (Row row : spaces.getRows()) {
                    require(!targetSpace.equals(text(row.values.get(0))),
                            "Target space already exists; refusing to overwrite: " + targetSpace);
                }
            }
            String ddl = "CREATE SPACE " + quoteIdentifier(targetSpace)
                    + "(partition_num=" + manifest.partitionNum
                    + ",replica_factor=" + manifest.replicaFactor
                    + ",vid_type=" + manifest.vidType
                    + ",charset=" + quoteIdentifier(manifest.charset)
                    + ",collate=" + quoteIdentifier(manifest.collation) + ")"
                    + spaceCommentClause(manifest.spaceCommentBase64);
            graph.execute(ddl);
            awaitSchema(target);
            graph.execute("USE " + quoteIdentifier(targetSpace));
            try (Metadata metadata = new Metadata(target)) {
                int spaceId = metadata.client.getSpace(targetSpace).space_id;
                for (SchemaManifest.Table table : manifest.tables) {
                    if (table.nativeSchemaBase64 == null) {
                        // Legacy exports without defaults retain the original path. A new export
                        // always contains the complete native schema and never executes SHOW CREATE.
                        graph.execute(table.createStatement);
                    } else {
                        metadata.client.createSchema(spaceId, table, nativeSchema(table));
                    }
                }
            }
            if (!manifest.tables.isEmpty()) {
                awaitSchema(target);
            }
            try (Metadata metadata = new Metadata(target)) {
                assertSchemasEqual(manifest, describe(graph, metadata.client, targetSpace));
            }
            for (SchemaManifest.Table table : manifest.tables) {
                long rowNumber = 0;
                for (String row : bundle.rows.get(tableKey(table)).values()) {
                    rowNumber++;
                    try {
                        Map<String, Object> parameters = parameters(table, manifest.vidType, row);
                        if (legacyPolygonWire) {
                            for (Map.Entry<String, Object> parameter : parameters.entrySet()) {
                                parameter.setValue(polygonParameter((Value) parameter.getValue(), true));
                            }
                        }
                        graph.execute(insertStatement(table, parameters), parameters);
                    } catch (Exception failure) {
                        throw new IOException("INSERT failed for " + tableKey(table)
                                + " CSV row " + rowNumber + "; target is incomplete: "
                                + targetSpace, failure);
                    }
                }
            }
        }
    }

    /** Compares the target's schema and every complete key/value to the validated export. */
    public VerificationReport verify(Path directory, String targetSpace) throws Exception {
        Bundle bundle = loadBundle(directory);
        compareCluster(bundle, target, targetSpace);
        return report(bundle, targetSpace, false);
    }

    /** Also verifies that the source still exactly matches the exported baseline. */
    public VerificationReport verifySourceAndTarget(Path directory, String targetSpace)
            throws Exception {
        Bundle bundle = loadBundle(directory);
        compareCluster(bundle, target, targetSpace);
        compareCluster(bundle, source, bundle.manifest.sourceSpace);
        return report(bundle, targetSpace, true);
    }

    public VerificationReport migrate(String sourceSpace, String targetSpace, Path directory)
            throws Exception {
        require(!(source.host.equals(target.host) && source.metaPort == target.metaPort
                        && sourceSpace.equals(targetSpace)),
                "Source and target must be distinct spaces or clusters");
        exportSpace(sourceSpace, directory);
        importSpace(directory, targetSpace);
        return verifySourceAndTarget(directory, targetSpace);
    }

    /** Read and validate an export without accessing either cluster. */
    public static SchemaManifest inspect(Path directory) throws Exception {
        return loadBundle(directory).manifest;
    }

    private void compareCluster(Bundle expected, ConnectionConfig config, String space)
            throws Exception {
        try (GraphConnection graph = openGraph(config); Metadata metadata = new Metadata(config)) {
            assertSchemasEqual(expected.manifest, describe(graph, metadata.client, space));
            Map<String, Map<String, String>> actual = scanAll(config, space, expected.manifest);
            for (SchemaManifest.Table table : expected.manifest.tables) {
                String name = tableKey(table);
                Map<String, String> wanted = expected.rows.get(name);
                Map<String, String> got = actual.get(name);
                require(wanted.size() == got.size(), "Row count differs in " + space + "/"
                        + name + ": expected=" + wanted.size() + ", actual=" + got.size());
                for (Map.Entry<String, String> row : wanted.entrySet()) {
                    String found = got.get(row.getKey());
                    require(found != null, "Missing complete key in " + space + "/" + name
                            + ": " + shortKey(row.getKey()));
                    require(found.equals(row.getValue()), "Exact property value differs in "
                            + space + "/" + name + ", key=" + shortKey(row.getKey()));
                }
            }
            assertSchemasEqual(expected.manifest, describe(graph, metadata.client, space));
        }
    }

    private Map<String, Map<String, String>> scanAll(ConnectionConfig config, String space,
                                                     SchemaManifest manifest) throws Exception {
        StorageClient storage = new StorageClient(Collections.singletonList(
                new HostAddress(config.host, config.metaPort)), config.timeoutMs);
        storage.setUser(config.user).setPassword(config.password);
        try {
            storage.connect();
            Map<String, Map<String, String>> result = new LinkedHashMap<>();
            for (SchemaManifest.Table table : manifest.tables) {
                Map<String, String> rows = new TreeMap<>();
                List<String> properties = new ArrayList<>();
                for (SchemaManifest.Column column : table.columns) {
                    properties.add(column.name);
                }
                if ("TAG".equals(table.kind)) {
                    ScanVertexResultIterator iterator = storage.scanVertex(space, table.name,
                            properties, scanLimit, 0, Long.MAX_VALUE, false, false);
                    while (iterator.hasNext()) {
                        ScanVertexResult page = iterator.next();
                        require(page != null && page.isAllSuccess(),
                                "Incomplete vertex scan: " + table.name);
                        collect(page.getDataSets(), table, manifest.vidType, rows);
                    }
                } else {
                    ScanEdgeResultIterator iterator = storage.scanEdge(space, table.name,
                            properties, scanLimit, 0, Long.MAX_VALUE, false, false);
                    while (iterator.hasNext()) {
                        ScanEdgeResult page = iterator.next();
                        require(page != null && page.isAllSuccess(),
                                "Incomplete edge scan: " + table.name);
                        collect(page.getDataSets(), table, manifest.vidType, rows);
                    }
                }
                result.put(tableKey(table), rows);
            }
            return result;
        } finally {
            storage.close();
        }
    }

    private static void collect(List<DataSet> dataSets, SchemaManifest.Table table, String vidType,
                                Map<String, String> output) {
        if (dataSets == null) {
            return;
        }
        List<String> names = valueNames(table);
        List<String> types = valueTypes(table, vidType);
        for (DataSet data : dataSets) {
            require(data != null && data.rows != null, "Missing scan dataset/rows");
            if (data.rows.isEmpty()) {
                continue;
            }
            Map<String, Integer> indexes = new HashMap<>();
            for (int i = 0; i < data.column_names.size(); i++) {
                String name = new String(data.column_names.get(i), StandardCharsets.UTF_8);
                require(indexes.put(name, i) == null, "Duplicate scan column: " + name);
            }
            List<Integer> required = new ArrayList<>();
            for (String name : names) {
                Integer index = indexes.get(table.name + "." + name);
                require(index != null, "Missing scan column " + table.name + "." + name
                        + "; returned=" + indexes.keySet());
                required.add(index);
            }
            for (Row row : data.rows) {
                require(row.values.size() == data.column_names.size(),
                        "Scan column/value count mismatch in " + tableKey(table));
                List<String> cells = new ArrayList<>();
                for (int i = 0; i < required.size(); i++) {
                    cells.add(ValueCodec.encode(row.values.get(required.get(i)), types.get(i)));
                }
                validateCells(table, vidType, cells);
                String line = String.join(",", cells);
                String key = key(table, cells);
                require(output.put(key, line) == null, "Duplicate complete key during scan of "
                        + tableKey(table) + ": " + shortKey(key));
            }
        }
    }

    private static SchemaManifest describe(GraphConnection graph, MetaClient metadata, String space)
            throws Exception {
        SpaceDesc desc = metadata.getSpace(space).properties;
        SchemaManifest manifest = new SchemaManifest();
        manifest.sourceSpace = space;
        manifest.createdAt = Instant.now().toString();
        manifest.vidType = schemaType(desc.vid_type);
        require(manifest.vidType.equals("INT64") || manifest.vidType.startsWith("FIXED_STRING("),
                "Unsupported VID type: " + manifest.vidType);
        manifest.partitionNum = desc.partition_num;
        manifest.replicaFactor = desc.replica_factor;
        manifest.charset = new String(desc.charset_name, StandardCharsets.UTF_8);
        manifest.collation = new String(desc.collate_name, StandardCharsets.UTF_8);
        manifest.spaceCommentBase64 = base64Nullable(desc.comment);
        manifest.showCreateSpace = showCreate(graph, "SPACE", space);
        graph.execute("USE " + quoteIdentifier(space));
        Set<String> tags = new TreeSet<>();
        for (TagItem tag : metadata.getTags(space)) {
            tags.add(new String(tag.tag_name, StandardCharsets.UTF_8));
        }
        Set<String> edges = new TreeSet<>();
        for (EdgeItem edge : metadata.getEdges(space)) {
            edges.add(new String(edge.edge_name, StandardCharsets.UTF_8));
        }
        for (String name : tags) {
            manifest.tables.add(table(graph, "TAG", name, metadata.getTag(space, name),
                    manifest.tables.size()));
        }
        for (String name : edges) {
            manifest.tables.add(table(graph, "EDGE", name, metadata.getEdge(space, name),
                    manifest.tables.size()));
        }
        return manifest;
    }

    private static SchemaManifest.Table table(GraphConnection graph, String kind, String name,
                                               Schema schema, int number) throws Exception {
        SchemaManifest.Table table = new SchemaManifest.Table();
        table.kind = kind;
        table.name = name;
        table.file = String.format(java.util.Locale.ROOT, "%04d_%s.csv", number,
                kind.toLowerCase(java.util.Locale.ROOT));
        table.createStatement = showCreate(graph, kind, name);
        table.nativeSchemaBase64 = encodeNativeSchema(schema);
        require(schema.schema_prop == null || schema.schema_prop.ttl_duration == 0,
                "Enabled TTL is outside the stable-source migration contract: " + kind + "/" + name);
        table.columns = schemaColumns(schema);
        return table;
    }

    private static List<SchemaManifest.Column> schemaColumns(Schema schema) {
        require(schema != null && schema.columns != null, "Missing native Schema columns");
        List<SchemaManifest.Column> columns = new ArrayList<>();
        for (ColumnDef column : schema.columns) {
            require(column != null && column.name != null, "Missing native Schema column");
            SchemaManifest.Column property = new SchemaManifest.Column();
            property.name = new String(column.name, StandardCharsets.UTF_8);
            property.type = schemaType(column.type);
            ValueCodec.validateSupportedType(property.type);
            property.nullable = column.nullable;
            property.defaultExpressionBase64 = base64Nullable(column.default_value);
            property.commentBase64 = base64Nullable(column.comment);
            columns.add(property);
        }
        return columns;
    }

    static String encodeNativeSchema(Schema schema) {
        try {
            return Base64.getEncoder().encodeToString(
                    new TSerializer(new TBinaryProtocol.Factory()).serialize(schema));
        } catch (TException failure) {
            throw new IllegalStateException("Cannot encode native Schema", failure);
        }
    }

    /** Validate the executable binary schema against the separately readable column inventory. */
    static Schema nativeSchema(SchemaManifest.Table table) {
        require(table.nativeSchemaBase64 != null, "Missing native Schema; re-export the source space");
        Schema schema = new Schema();
        try {
            byte[] bytes = Base64.getDecoder().decode(table.nativeSchemaBase64);
            new TDeserializer(new TBinaryProtocol.Factory()).deserialize(schema, bytes);
        } catch (TException | IllegalArgumentException failure) {
            throw new IllegalStateException("Invalid native Schema payload", failure);
        }
        require(table.nativeSchemaBase64.equals(encodeNativeSchema(schema)),
                "Non-canonical native Schema payload");
        require(schema.schema_prop == null || schema.schema_prop.ttl_duration == 0,
                "Enabled TTL in native Schema is outside the stable-source migration contract");
        require(JSON.toJSONString(table.columns).equals(JSON.toJSONString(schemaColumns(schema))),
                "Native Schema columns/default/comment differ from manifest inventory");
        return schema;
    }

    static String schemaType(ColumnTypeDef type) {
        require(type != null && type.type != null, "Missing property schema type");
        String name = type.type.name();
        if (type.type == PropertyType.FIXED_STRING) {
            return name + "(" + type.type_length + ")";
        }
        if (type.type == PropertyType.GEOGRAPHY) {
            // An omitted shape is ANY in the metadata protocol. Specific constraints must
            // survive export so POINT and unrestricted GEOGRAPHY cannot compare as equal.
            GeoShape shape = type.geo_shape;
            return shape == null || shape == GeoShape.ANY
                    ? name : name + "(" + shape.name() + ")";
        }
        return name;
    }

    private static String showCreate(GraphConnection graph, String kind, String name)
            throws Exception {
        ResultSet result = graph.execute("SHOW CREATE " + kind + " " + quoteIdentifier(name));
        require(!result.isEmpty() && result.getRows().size() == 1
                && result.getRows().get(0).values.size() >= 2, "Missing SHOW CREATE " + kind);
        return text(result.getRows().get(0).values.get(1));
    }

    static void assertSchemasEqual(SchemaManifest expected, SchemaManifest actual) {
        require(Objects.equals(expected.vidType, actual.vidType), "Space VID schema differs");
        require(Objects.equals(expected.charset, actual.charset)
                && Objects.equals(expected.collation, actual.collation),
                "Space charset/collation differs");
        require(Objects.equals(expected.spaceCommentBase64, actual.spaceCommentBase64),
                "Space COMMENT bytes or presence differs");
        require(expected.tables.size() == actual.tables.size(), "Tag/Edge schema count differs");
        for (int i = 0; i < expected.tables.size(); i++) {
            SchemaManifest.Table wanted = expected.tables.get(i);
            SchemaManifest.Table got = actual.tables.get(i);
            require(tableKey(wanted).equals(tableKey(got)), "Tag/Edge schema inventory differs");
            require(wanted.createStatement.equals(got.createStatement),
                    "SHOW CREATE differs for " + tableKey(wanted));
            require(JSON.toJSONString(wanted.columns).equals(JSON.toJSONString(got.columns)),
                    "Property schema/default/comment differs for " + tableKey(wanted));
            if (wanted.nativeSchemaBase64 != null) {
                require(wanted.nativeSchemaBase64.equals(got.nativeSchemaBase64),
                        "Native Schema/default/comment differs for " + tableKey(wanted));
            }
        }
    }

    private static Bundle loadBundle(Path directory) throws Exception {
        Path dir = directory.toAbsolutePath().normalize();
        String json = new String(Files.readAllBytes(dir.resolve(MANIFEST_FILE)), StandardCharsets.UTF_8);
        // Parse through a plain JSON object, without enabling polymorphic auto-type support.
        SchemaManifest manifest = JSON.parseObject(json).toJavaObject(SchemaManifest.class);
        require(manifest != null && manifest.formatVersion == 1, "Unsupported manifest version");
        require(new SchemaManifest().valueEncoding.equals(manifest.valueEncoding),
                "Unsupported value encoding");
        quoteIdentifier(manifest.sourceSpace);
        require(manifest.vidType != null && (manifest.vidType.equals("INT64")
                || manifest.vidType.matches("FIXED_STRING\\([1-9][0-9]*\\)")),
                "Invalid VID type");
        ValueCodec.validateSupportedType(manifest.vidType);
        require(manifest.partitionNum > 0 && manifest.replicaFactor > 0,
                "Invalid space partition/replica counts");
        quoteIdentifier(manifest.charset);
        quoteIdentifier(manifest.collation);
        spaceCommentClause(manifest.spaceCommentBase64);
        // Old exports stored only SHOW CREATE SPACE, whose printf-style COMMENT display can
        // truncate at NUL. Refuse that legacy case rather than trying to parse lost bytes.
        require(manifest.spaceCommentBase64 != null || manifest.showCreateSpace == null
                        || !manifest.showCreateSpace.toLowerCase(java.util.Locale.ROOT).contains(" comment = "),
                "Legacy space COMMENT requires raw metadata; re-export the source space");
        require(manifest.tables != null, "Missing tables inventory");
        Bundle bundle = new Bundle(manifest);
        Set<String> files = new HashSet<>();
        for (SchemaManifest.Table table : manifest.tables) {
            require(table != null && ("TAG".equals(table.kind) || "EDGE".equals(table.kind)),
                    "Invalid table kind");
            quoteIdentifier(table.name);
            require(table.file != null && table.file.matches("[0-9]+_(tag|edge)\\.csv")
                    && files.add(table.file), "Invalid/duplicate CSV filename");
            require(table.createStatement != null && table.createStatement.startsWith(
                    "CREATE " + table.kind + " " + quoteIdentifier(table.name)),
                    "Manifest SHOW CREATE does not match table identity: " + tableKey(table));
            require(table.columns != null && table.rowCount >= 0, "Invalid table metadata");
            Set<String> names = new HashSet<>();
            for (SchemaManifest.Column column : table.columns) {
                quoteIdentifier(column.name);
                require(names.add(column.name), "Duplicate property name: " + column.name);
                ValueCodec.validateSupportedType(column.type);
                require(table.nativeSchemaBase64 != null || column.defaultExpressionBase64 == null,
                        "Legacy bundle with DEFAULT requires native Schema; re-export the source space");
            }
            if (table.nativeSchemaBase64 != null) {
                nativeSchema(table);
            }
            Path csv = dir.resolve(table.file);
            require(Files.isRegularFile(csv) && !Files.isSymbolicLink(csv), "Missing CSV file");
            require(sha256(csv).equals(table.sha256), "CSV SHA-256 mismatch: " + table.file);
            Map<String, String> rows = new TreeMap<>();
            try (BufferedReader in = Files.newBufferedReader(csv, StandardCharsets.US_ASCII)) {
                require(header(table).equals(in.readLine()), "CSV header mismatch: " + table.file);
                String line;
                long number = 1;
                while ((line = in.readLine()) != null) {
                    number++;
                    List<String> cells = Arrays.asList(line.split(",", -1));
                    try {
                        validateCells(table, manifest.vidType, cells);
                        String key = key(table, cells);
                        require(rows.put(key, line) == null, "Duplicate complete key in CSV");
                    } catch (RuntimeException failure) {
                        throw new IOException("Invalid " + table.file + " line " + number, failure);
                    }
                }
            }
            require(table.rowCount == rows.size(), "CSV row count mismatch: " + table.file);
            require(bundle.rows.put(tableKey(table), rows) == null, "Duplicate table identity");
        }
        return bundle;
    }

    private static void validateCells(SchemaManifest.Table table, String vidType, List<String> cells) {
        List<String> types = valueTypes(table, vidType);
        require(cells.size() == types.size(), "CSV field count differs from schema");
        int keys = "TAG".equals(table.kind) ? 1 : 3;
        for (int i = 0; i < cells.size(); i++) {
            String cell = cells.get(i);
            require(i >= keys || !"N".equals(cell), "NULL graph identifier/rank");
            if (i >= keys && "N".equals(cell)) {
                require(table.columns.get(i - keys).nullable, "NULL in NOT NULL property");
            }
            Value decoded = ValueCodec.decode(cell, types.get(i));
            if ("EDGE".equals(table.kind) && i == 2) {
                rankLiteral(decoded);
            }
            validateReplayableFloat(decoded, types.get(i));
            require(cell.equals(ValueCodec.encode(decoded, types.get(i))),
                    "Non-canonical/lossy value encoding");
        }
    }

    private static void validateReplayableFloat(Value value, String type) {
        if (value.getSetField() != Value.FVAL) {
            return;
        }
        double number = value.getFVal();
        long bits = Double.doubleToRawLongBits(number);
        // The bundled Thrift writer uses doubleToLongBits and therefore canonicalizes NaNs.
        require(bits == Double.doubleToLongBits(number),
                "Non-canonical NaN cannot be replayed without changing its bits by this client");
        if ("FLOAT".equalsIgnoreCase(type)) {
            require(!Double.isInfinite(number),
                    "FLOAT infinity is outside the exact replay contract");
            require(bits == Double.doubleToRawLongBits((double) (float) number),
                    "FLOAT payload is not an exact float32 value promoted to double");
        }
    }

    private static boolean hasPolygonSchema(SchemaManifest manifest) {
        for (SchemaManifest.Table table : manifest.tables) {
            for (SchemaManifest.Column column : table.columns) {
                String type = column.type.replaceAll("\\s+", "");
                if (type.equalsIgnoreCase("GEOGRAPHY")
                        || type.equalsIgnoreCase("GEOGRAPHY(POLYGON)")) {
                    return true;
                }
            }
        }
        return false;
    }

    @FunctionalInterface
    interface PolygonProbe {
        Value roundTrip(Value parameter) throws Exception;
    }

    /**
     * Some older graphd versions read Polygon's list-of-lists as list-of-structs.
     * Probe both paths before creating a target, and use a workaround only after proving
     * the complete nested shape and coordinate bits round-trip through that connection.
     */
    static boolean detectLegacyPolygonWire(PolygonProbe probe) throws Exception {
        Coordinate origin = new Coordinate(-0.0d, 0.0d);
        Polygon polygon = new Polygon(Arrays.asList(
                Arrays.asList(origin, new Coordinate(Math.nextUp(1.0d), 0.0d),
                        new Coordinate(1.0d, 1.0d), new Coordinate(0.0d, 1.0d), origin),
                Arrays.asList(new Coordinate(0.2d, 0.2d), new Coordinate(0.2d, 0.3d),
                        new Coordinate(0.3d, 0.3d), new Coordinate(0.3d, 0.2d),
                        new Coordinate(0.2d, 0.2d))));
        Value wanted = Value.ggVal(Geography.pgVal(polygon));
        String expected = ValueCodec.encode(wanted, "GEOGRAPHY(POLYGON)");
        Exception standardFailure = null;
        for (boolean legacy : new boolean[] {false, true}) {
            try {
                Value actual = probe.roundTrip(polygonParameter(wanted, legacy));
                require(expected.equals(ValueCodec.encode(actual, "GEOGRAPHY(POLYGON)")),
                        "Polygon parameter changed its rings or coordinate bits");
                return legacy;
            } catch (Exception failure) {
                if (!legacy) {
                    standardFailure = failure;
                } else {
                    IllegalStateException rejected = new IllegalStateException(
                            "Target cannot preserve Polygon parameters with standard or legacy wire format",
                            failure);
                    rejected.addSuppressed(standardFailure);
                    throw rejected;
                }
            }
        }
        throw new IllegalStateException("Polygon wire probe did not complete");
    }

    static Value polygonParameter(Value value, boolean legacy) {
        if (!legacy || value.getSetField() != Value.GGVAL
                || value.getGgVal().getSetField() != Geography.PGVAL) {
            return value;
        }
        return Value.ggVal(Geography.pgVal(new LegacyPolygon(value.getGgVal().getPgVal())));
    }

    /** Only the outer list element header differs; all coordinate bytes remain standard. */
    private static final class LegacyPolygon extends Polygon {
        private LegacyPolygon(Polygon original) {
            super(original.coordListList);
        }

        @Override
        public void write(TProtocol protocol) throws TException {
            protocol.writeStructBegin(new TStruct("Polygon"));
            protocol.writeFieldBegin(new TField("coordListList", TType.LIST, (short) 1));
            protocol.writeListBegin(new TList(TType.STRUCT, coordListList.size()));
            for (List<Coordinate> ring : coordListList) {
                protocol.writeListBegin(new TList(TType.STRUCT, ring.size()));
                for (Coordinate coordinate : ring) {
                    coordinate.write(protocol);
                }
                protocol.writeListEnd();
            }
            protocol.writeListEnd();
            protocol.writeFieldEnd();
            protocol.writeFieldStop();
            protocol.writeStructEnd();
        }
    }

    private static Map<String, Object> parameters(SchemaManifest.Table table, String vidType,
                                                 String row) {
        String[] cells = row.split(",", -1);
        List<String> types = valueTypes(table, vidType);
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (int i = 0; i < cells.length; i++) {
            parameters.put("v" + i, ValueCodec.decode(cells[i], types.get(i)));
        }
        return parameters;
    }

    private static String insertStatement(SchemaManifest.Table table, Map<String, Object> parameters) {
        boolean tag = "TAG".equals(table.kind);
        StringBuilder query = new StringBuilder(tag ? "INSERT VERTEX " : "INSERT EDGE ");
        query.append(quoteIdentifier(table.name)).append('(');
        for (int i = 0; i < table.columns.size(); i++) {
            if (i > 0) {
                query.append(',');
            }
            query.append(quoteIdentifier(table.columns.get(i).name));
        }
        query.append(") VALUES ").append(vidLiteral((Value) parameters.get("v0")));
        if (!tag) {
            query.append("->").append(vidLiteral((Value) parameters.get("v1")))
                    .append('@').append(rankLiteral((Value) parameters.get("v2")));
        }
        query.append(":(");
        int offset = tag ? 1 : 3;
        for (int i = 0; i < table.columns.size(); i++) {
            if (i > 0) {
                query.append(',');
            }
            query.append("$v").append(i + offset);
        }
        return query.append(')').toString();
    }

    private static List<String> valueNames(SchemaManifest.Table table) {
        List<String> names = new ArrayList<>();
        if ("TAG".equals(table.kind)) {
            names.add("_vid");
        } else {
            names.addAll(Arrays.asList("_src", "_dst", "_rank"));
        }
        for (SchemaManifest.Column column : table.columns) {
            names.add(column.name);
        }
        return names;
    }

    private static List<String> valueTypes(SchemaManifest.Table table, String vidType) {
        List<String> types = new ArrayList<>();
        types.add(vidType);
        if ("EDGE".equals(table.kind)) {
            types.add(vidType);
            types.add("INT64");
        }
        for (SchemaManifest.Column column : table.columns) {
            types.add(column.type);
        }
        return types;
    }

    private static String header(SchemaManifest.Table table) {
        StringBuilder header = new StringBuilder("TAG".equals(table.kind)
                ? "_vid" : "_src,_dst,_rank");
        for (int i = 0; i < table.columns.size(); i++) {
            header.append(",p").append(i);
        }
        return header.toString();
    }

    private static String key(SchemaManifest.Table table, List<String> cells) {
        return "TAG".equals(table.kind) ? cells.get(0)
                : String.join(",", cells.subList(0, 3));
    }

    private static String tableKey(SchemaManifest.Table table) {
        return table.kind + "/" + table.name;
    }

    private static String shortKey(String key) {
        return key.length() <= 160 ? key : key.substring(0, 160) + "...";
    }

    private static VerificationReport report(Bundle bundle, String targetSpace, boolean sourceChecked) {
        VerificationReport report = new VerificationReport();
        report.matched = true;
        report.sourceRechecked = sourceChecked;
        report.sourceSpace = bundle.manifest.sourceSpace;
        report.targetSpace = targetSpace;
        for (SchemaManifest.Table table : bundle.manifest.tables) {
            report.rowsByTable.put(tableKey(table), table.rowCount);
            if ("TAG".equals(table.kind)) {
                report.tagRows += table.rowCount;
            } else {
                report.edgeRows += table.rowCount;
            }
        }
        return report;
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    public static String quoteIdentifier(String name) {
        require(name != null && !name.isEmpty() && name.indexOf('`') < 0 && name.indexOf('\0') < 0,
                "Invalid nGQL identifier");
        return "`" + name + "`";
    }

    /**
     * NebulaGraph 3.6 does not accept parameters in INSERT VID positions. Encode each original
     * byte as its own three-digit octal escape; no character-set conversion is involved.
     */
    public static String vidLiteral(Value value) {
        require(value != null, "Missing VID");
        if (value.getSetField() == Value.IVAL) {
            // 3.6's VID unary_integer production cannot lex abs(Long.MIN_VALUE), whereas
            // function-call VIDs can evaluate an exact string-to-integer conversion.
            if (value.getIVal() == Long.MIN_VALUE) {
                return "toInteger(\"-9223372036854775808\")";
            }
            return Long.toString(value.getIVal());
        }
        require(value.getSetField() == Value.SVAL, "VID must be an integer or raw string");
        require(value.getSVal() != null, "Missing VID bytes");
        for (byte item : value.getSVal()) {
            require(item != 0, "NUL in VID violates the source-data prerequisite");
        }
        return byteStringLiteral(value.getSVal());
    }

    /** Generic raw byte literal: COMMENT and ordinary STRING may legitimately contain NUL. */
    static String byteStringLiteral(byte[] bytes) {
        require(bytes != null, "Missing raw string bytes");
        StringBuilder result = new StringBuilder("\"");
        for (byte item : bytes) {
            int unsigned = item & 0xff;
            result.append('\\').append((char) ('0' + (unsigned >> 6)))
                    .append((char) ('0' + ((unsigned >> 3) & 7)))
                    .append((char) ('0' + (unsigned & 7)));
        }
        return result.append('"').toString();
    }

    static String spaceCommentClause(String encoded) {
        if (encoded == null) {
            return "";
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("Invalid space COMMENT Base64", failure);
        }
        require(encoded.equals(Base64.getEncoder().encodeToString(bytes)),
                "Non-canonical space COMMENT Base64");
        return " COMMENT = " + byteStringLiteral(bytes);
    }

    public static String rankLiteral(Value value) {
        require(value != null && value.getSetField() == Value.IVAL, "Edge rank must be INT64");
        require(value.getIVal() != Long.MIN_VALUE,
                "Edge rank Long.MIN_VALUE (-9223372036854775808) cannot be expressed by "
                        + "NebulaGraph 3.6 nGQL INSERT; refusing lossy migration before writing");
        return Long.toString(value.getIVal());
    }

    /** Fail before any write if the target cannot interpret all 256 raw literal byte values. */
    public static void verifyStringLiteralSupport(Session session) throws Exception {
        byte[] expected = new byte[256];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) i;
        }
        ResultSet probe = session.execute("YIELD " + byteStringLiteral(expected) + " AS probe");
        require(probe.isSucceeded() && !probe.isEmpty(),
                "Unable to verify nGQL byte-literal support: " + probe.getErrorMessage());
        Value value = probe.getRows().get(0).values.get(0);
        require(value.getSetField() == Value.SVAL
                        && Arrays.equals(value.getSVal(), expected),
                "Raw string/COMMENT replay requires graphd disable_octal_escape_char=false");
    }

    private static String text(Value value) {
        require(value != null && value.getSetField() == Value.SVAL, "Expected metadata string");
        return new String(value.getSVal(), StandardCharsets.UTF_8);
    }

    private static String base64Nullable(byte[] bytes) {
        return bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void awaitSchema(ConnectionConfig config) throws InterruptedException {
        require(config.schemaWaitMillis >= 0, "Negative schema propagation wait");
        if (config.schemaWaitMillis > 0) {
            try {
                Thread.sleep(config.schemaWaitMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }
    }

    @Override
    public void close() {
        // Each operation owns and closes its connections; there is no retained background work.
    }

    public static GraphConnection openGraph(ConnectionConfig config) throws Exception {
        return new GraphConnection(config);
    }

    public static final class GraphConnection implements AutoCloseable {
        private final NebulaPool pool = new NebulaPool();
        public final Session session;

        private GraphConnection(ConnectionConfig config) throws Exception {
            NebulaPoolConfig poolConfig = new NebulaPoolConfig();
            poolConfig.setMaxConnSize(2);
            poolConfig.setTimeout(config.timeoutMs);
            try {
                require(pool.init(Collections.singletonList(new HostAddress(config.host,
                        config.graphPort)), poolConfig), "Graph connection pool initialization failed");
                session = pool.getSession(config.user, config.password, false);
            } catch (Exception failure) {
                pool.close();
                throw failure;
            }
        }

        public ResultSet execute(String ngql) throws Exception {
            return execute(ngql, Collections.emptyMap());
        }

        public ResultSet execute(String ngql, Map<String, Object> parameters) throws Exception {
            ResultSet result = session.executeWithParameter(ngql, parameters);
            require(result.isSucceeded(), "nGQL failed (" + result.getErrorCode() + "): "
                    + result.getErrorMessage());
            return result;
        }

        @Override
        public void close() {
            try {
                session.release();
            } finally {
                pool.close();
            }
        }
    }

    private static final class Metadata implements AutoCloseable {
        private final WritableMetaClient client;

        private Metadata(ConnectionConfig config) throws Exception {
            client = new WritableMetaClient(config);
            try {
                client.connect();
            } catch (Exception failure) {
                client.close();
                throw failure;
            }
        }

        @Override
        public void close() {
            client.close();
        }
    }

    /** Reuses MetaClient's transport, client-version handshake and resource lifecycle. */
    private static final class WritableMetaClient extends MetaClient {
        private WritableMetaClient(ConnectionConfig config) throws java.net.UnknownHostException {
            super(Collections.singletonList(new HostAddress(config.host, config.metaPort)),
                    config.timeoutMs, 1, 1);
        }

        private synchronized void createSchema(int spaceId, SchemaManifest.Table table, Schema schema)
                throws TException {
            MetaService.Client writer = new MetaService.Client(protocol);
            byte[] name = table.name.getBytes(StandardCharsets.UTF_8);
            ExecResp response = "TAG".equals(table.kind)
                    ? writer.createTag(new CreateTagReq(spaceId, name, schema, false))
                    : writer.createEdge(new CreateEdgeReq(spaceId, name, schema, false));
            require(response != null && response.code == ErrorCode.SUCCEEDED,
                    "Meta create " + tableKey(table) + " failed: "
                            + (response == null ? "missing response" : response.code)
                            + "; target is incomplete");
        }
    }

    private static final class Bundle {
        private final SchemaManifest manifest;
        private final Map<String, Map<String, String>> rows = new LinkedHashMap<>();

        private Bundle(SchemaManifest manifest) {
            this.manifest = manifest;
        }
    }
}
