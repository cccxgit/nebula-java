/* Copyright (c) 2020 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.nebula.client.storage.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import com.vesoft.nebula.DataSet;
import com.vesoft.nebula.ErrorCode;
import com.vesoft.nebula.HostAddr;
import com.vesoft.nebula.Row;
import com.vesoft.nebula.Value;
import com.vesoft.nebula.client.graph.data.HostAddress;
import com.vesoft.nebula.client.meta.MetaManager;
import com.vesoft.nebula.client.storage.GraphStorageConnection;
import com.vesoft.nebula.client.storage.StorageConnPool;
import com.vesoft.nebula.client.storage.StoragePoolConfig;
import com.vesoft.nebula.client.storage.data.BaseTableRow;
import com.vesoft.nebula.storage.EdgeProp;
import com.vesoft.nebula.storage.PartitionResult;
import com.vesoft.nebula.storage.ResponseCommon;
import com.vesoft.nebula.storage.ScanCursor;
import com.vesoft.nebula.storage.ScanEdgeRequest;
import com.vesoft.nebula.storage.ScanResponse;
import com.vesoft.nebula.storage.ScanVertexRequest;
import com.vesoft.nebula.storage.VertexProp;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class ScanIteratorPaginationTest {
    private static final HostAddress FIRST_HOST = new HostAddress("storage-1", 9779);
    private static final HostAddress SECOND_HOST = new HostAddress("storage-2", 9779);
    private static final HostAddress IDLE_HOST = new HostAddress("storage-3", 9779);
    private static final List<HostAddress> HOSTS =
            Arrays.asList(FIRST_HOST, SECOND_HOST, IDLE_HOST);

    @Test(timeout = 10000)
    public void testSequentialTagsKeepEveryPageWithIdleHosts() throws Exception {
        assertSequentialLabels(false);
    }

    @Test(timeout = 10000)
    public void testSequentialEdgesKeepEveryPageWithIdleHosts() throws Exception {
        assertSequentialLabels(true);
    }

    @Test(timeout = 5000)
    public void testVertexPageSurvivesAnotherPartitionsLeaderChanges() throws Exception {
        assertPageSurvivesLeaderChanges(false);
    }

    @Test(timeout = 5000)
    public void testEdgePageSurvivesAnotherPartitionsLeaderChanges() throws Exception {
        assertPageSurvivesLeaderChanges(true);
    }

    private void assertSequentialLabels(boolean edge) throws Exception {
        for (int limit : new int[] {1, 2, 7}) {
            for (boolean partSuccess : new boolean[] {false, true}) {
                PagedPool pool = new PagedPool(limit, false);
                try {
                    // Reuse one connection pool while exporting each label independently.
                    for (int label = 1; label <= 3; label++) {
                        ScanResultIterator iterator = newIterator(
                                pool, edge, label, partSuccess);
                        List<Long> exported = new ArrayList<>();
                        int pages = 0;
                        while (iterator.hasNext()) {
                            exported.addAll(readPage(iterator, edge));
                            assertTrue("scan must eventually finish", ++pages < 20);
                        }
                        Set<Long> expected = pool.expectedRows(label);
                        assertEquals("all records must be exported", expected.size(),
                                exported.size());
                        assertEquals("records must not be skipped or duplicated", expected,
                                new HashSet<>(exported));
                        assertFalse(iterator.hasNext());
                    }
                } finally {
                    pool.close();
                }
            }
        }
    }

    private void assertPageSurvivesLeaderChanges(boolean edge) throws Exception {
        PagedPool pool = new PagedPool(2, true);
        try {
            ScanResultIterator iterator = newIterator(pool, edge, 1, false);
            List<Long> exported = new ArrayList<>(readPage(iterator, edge));
            // Partition 1 has already advanced its cursor. Partition 2 redirects twice,
            // leaving its cursor unchanged, and must not discard partition 1's full page.
            assertEquals(2, exported.size());
            assertTrue(iterator.hasNext());
            while (iterator.hasNext()) {
                exported.addAll(readPage(iterator, edge));
                assertTrue("scan must not duplicate pages", exported.size() <= 6);
            }
            assertEquals(6, exported.size());
            assertEquals(pool.expectedRows(1), new HashSet<>(exported));
            assertEquals(Arrays.asList("", "", ""), pool.cursors.get("1:2"));
            assertEquals(Arrays.asList("", "2"), pool.cursors.get("1:1"));
        } finally {
            pool.close();
        }
    }

    private ScanResultIterator newIterator(PagedPool pool, boolean edge, int label,
                                            boolean partSuccess) {
        Set<PartScanInfo> parts = new LinkedHashSet<>();
        parts.add(new PartScanInfo(1, FIRST_HOST));
        parts.add(new PartScanInfo(2, SECOND_HOST));
        if (!pool.redirect) {
            parts.add(new PartScanInfo(3, FIRST_HOST));
            parts.add(new PartScanInfo(4, SECOND_HOST));
        }
        MetaManager meta = pool.redirect ? mock(MetaManager.class) : null;
        if (edge) {
            ScanEdgeRequest request = new ScanEdgeRequest().setLimit(pool.limit)
                    .setReturn_columns(Collections.singletonList(
                            new EdgeProp(label, Collections.emptyList())));
            return new ScanEdgeResultIterator.ScanEdgeResultBuilder()
                    .withPool(pool).withMetaClient(meta).withPartScanInfo(parts)
                    .withAddresses(HOSTS).withSpaceName("test_space")
                    .withEdgeName("label" + label).withRequest(request)
                    .withPartSuccess(partSuccess).build();
        }
        ScanVertexRequest request = new ScanVertexRequest().setLimit(pool.limit)
                .setReturn_columns(Collections.singletonList(
                        new VertexProp(label, Collections.emptyList())));
        return new ScanVertexResultIterator.ScanVertexResultBuilder()
                .withPool(pool).withMetaClient(meta).withPartScanInfo(parts)
                .withAddresses(HOSTS).withSpaceName("test_space")
                .withTagName("label" + label).withRequest(request)
                .withPartSuccess(partSuccess).build();
    }

    private List<Long> readPage(ScanResultIterator iterator, boolean edge) throws Exception {
        List<? extends BaseTableRow> rows;
        if (edge) {
            rows = ((ScanEdgeResultIterator) iterator).next().getEdgeTableRows();
        } else {
            rows = ((ScanVertexResultIterator) iterator).next().getVertexTableRows();
        }
        List<Long> ids = new ArrayList<>();
        for (BaseTableRow row : rows) {
            ids.add(row.getLong(0));
        }
        return ids;
    }

    private static class PagedPool extends StorageConnPool {
        private final int limit;
        private final boolean redirect;
        private final Map<String, Integer> offsets = new HashMap<>();
        private final Map<String, List<String>> cursors = new HashMap<>();
        private int leaderChanges;

        PagedPool(int limit, boolean redirect) {
            super(new StoragePoolConfig());
            this.limit = limit;
            this.redirect = redirect;
        }

        @Override
        public GraphStorageConnection getStorageConnection(HostAddress address) {
            return new PagedConnection(this);
        }

        @Override
        public void release(HostAddress address, GraphStorageConnection connection) {
            // The in-memory connection has no resources to release.
        }

        private synchronized ScanResponse scan(boolean edge, int label,
                                                Map<Integer, ScanCursor> parts,
                                                long requestedLimit) {
            assertEquals(limit, requestedLimit);
            assertEquals(1, parts.size());
            Map.Entry<Integer, ScanCursor> entry = parts.entrySet().iterator().next();
            int part = entry.getKey();
            String key = label + ":" + part;
            String cursor = new String(entry.getValue().getNext_cursor(), StandardCharsets.UTF_8);
            cursors.computeIfAbsent(key, ignored -> new ArrayList<>()).add(cursor);
            int start = cursor.isEmpty() ? 0 : Integer.parseInt(cursor);
            assertEquals("the next request must resume at the returned cursor",
                    offsets.getOrDefault(key, 0).intValue(), start);
            if (redirect && part == 2 && leaderChanges++ < 2) {
                PartitionResult failure = new PartitionResult(ErrorCode.E_LEADER_CHANGED, part,
                        new HostAddr(SECOND_HOST.getHost(), SECOND_HOST.getPort()));
                return new ScanResponse(new ResponseCommon(Collections.singletonList(failure), 0));
            }
            int total = count(label, part);
            int end = Math.min(total, start + limit);
            offsets.put(key, end);
            List<Row> rows = new ArrayList<>();
            for (int offset = start; offset < end; offset++) {
                long id = rowId(label, part, offset);
                List<Value> values = edge
                        ? Arrays.asList(Value.iVal(id), Value.iVal(id + 1), Value.iVal(0))
                        : Arrays.asList(Value.iVal(id), Value.iVal(id));
                rows.add(new Row(values));
            }
            List<byte[]> columns = edge
                    ? Arrays.asList(bytes("label._src"), bytes("label._dst"), bytes("label._rank"))
                    : Arrays.asList(bytes("_vid"), bytes("label._vid"));
            ScanCursor next = end < total ? new ScanCursor(bytes(String.valueOf(end)))
                    : new ScanCursor();
            return new ScanResponse(new ResponseCommon(Collections.emptyList(), 0),
                    new DataSet(columns, rows), Collections.singletonMap(part, next));
        }

        private int count(int label, int part) {
            if (redirect) {
                return part == 1 ? 4 : 2;
            }
            switch (part) {
                case 1:
                    return limit * (label + 1) + 1;
                case 2:
                    return limit;
                case 3:
                    return 0;
                default:
                    return limit * 2;
            }
        }

        private Set<Long> expectedRows(int label) {
            Set<Long> ids = new HashSet<>();
            for (int part = 1; part <= (redirect ? 2 : 4); part++) {
                for (int offset = 0; offset < count(label, part); offset++) {
                    ids.add(rowId(label, part, offset));
                }
            }
            return ids;
        }

        private long rowId(int label, int part, int offset) {
            return label * 100000L + part * 1000L + offset;
        }

        private static byte[] bytes(String value) {
            return value.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static class PagedConnection extends GraphStorageConnection {
        private final PagedPool pool;

        PagedConnection(PagedPool pool) {
            this.pool = pool;
        }

        @Override
        public ScanResponse scanVertex(ScanVertexRequest request) {
            return pool.scan(false, request.getReturn_columns().get(0).getTag(),
                    request.getParts(), request.getLimit());
        }

        @Override
        public ScanResponse scanEdge(ScanEdgeRequest request) {
            return pool.scan(true, request.getReturn_columns().get(0).getType(),
                    request.getParts(), request.getLimit());
        }
    }
}
