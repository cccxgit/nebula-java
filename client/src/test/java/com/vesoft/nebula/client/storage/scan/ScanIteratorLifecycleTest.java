/* Copyright (c) 2020 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.nebula.client.storage.scan;

import com.vesoft.nebula.DataSet;
import com.vesoft.nebula.ErrorCode;
import com.vesoft.nebula.HostAddr;
import com.vesoft.nebula.client.graph.data.HostAddress;
import com.vesoft.nebula.client.meta.exception.ExecuteFailedException;
import com.vesoft.nebula.client.storage.GraphStorageConnection;
import com.vesoft.nebula.client.storage.StorageConnPool;
import com.vesoft.nebula.client.storage.StoragePoolConfig;
import com.vesoft.nebula.storage.PartitionResult;
import com.vesoft.nebula.storage.ResponseCommon;
import com.vesoft.nebula.storage.ScanCursor;
import com.vesoft.nebula.storage.ScanEdgeRequest;
import com.vesoft.nebula.storage.ScanResponse;
import com.vesoft.nebula.storage.ScanVertexRequest;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ScanIteratorLifecycleTest {
    private static final HostAddress ORIGINAL_LEADER = new HostAddress("127.0.0.1", 9779);
    private static final HostAddress NEW_LEADER = new HostAddress("127.0.0.2", 9779);

    private final boolean edge;
    private final RecordingPool pool = new RecordingPool();

    public ScanIteratorLifecycleTest(boolean edge) {
        this.edge = edge;
    }

    @Parameterized.Parameters(name = "edge={0}")
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][] {{false}, {true}});
    }

    @After
    public void closePool() {
        pool.close();
    }

    @Test
    public void successfulScanReturnsConnectionExactlyOnce() throws Exception {
        pool.connections.put(ORIGINAL_LEADER, new StubConnection(successResponse()));
        ScanResultIterator iterator = newIterator();

        Object result = nextWithTimeout(iterator);

        if (edge) {
            Assert.assertTrue(((ScanEdgeResult) result).isAllSuccess());
        } else {
            Assert.assertTrue(((ScanVertexResult) result).isAllSuccess());
        }
        Assert.assertFalse(iterator.hasNext());
        Assert.assertEquals(Collections.singletonList(ORIGINAL_LEADER), pool.borrowAttempts);
        Assert.assertEquals(Collections.singletonList(ORIGINAL_LEADER), pool.returnAttempts);
        Assert.assertTrue(pool.borrowed.isEmpty());
    }

    @Test
    public void releaseFailureIsReportedWithoutHanging() throws Exception {
        pool.connections.put(ORIGINAL_LEADER, new StubConnection(successResponse()));
        pool.failRelease = true;

        assertScanFails(newIterator(), "release failed");

        Assert.assertEquals(Collections.singletonList(ORIGINAL_LEADER), pool.returnAttempts);
    }

    @Test
    public void missingPartitionCursorIsReportedWithoutHanging() throws Exception {
        ScanResponse response = successResponse();
        response.setCursors(Collections.emptyMap());
        pool.connections.put(ORIGINAL_LEADER, new StubConnection(response));

        assertScanFails(newIterator(), null);

        Assert.assertEquals(Collections.singletonList(ORIGINAL_LEADER), pool.returnAttempts);
        Assert.assertTrue(pool.borrowed.isEmpty());
    }

    @Test
    public void failedRedirectBorrowDoesNotReturnOldConnectionAgain() throws Exception {
        pool.connections.put(ORIGINAL_LEADER, new StubConnection(leaderChangedResponse()));

        assertScanFails(newIterator(), "borrow failed");

        Assert.assertEquals(Arrays.asList(ORIGINAL_LEADER, NEW_LEADER), pool.borrowAttempts);
        Assert.assertEquals(Collections.singletonList(ORIGINAL_LEADER), pool.returnAttempts);
        Assert.assertTrue(pool.borrowed.isEmpty());
    }

    @Test
    public void redirectReturnsEachConnectionToItsOwnLeader() throws Exception {
        pool.connections.put(ORIGINAL_LEADER, new StubConnection(leaderChangedResponse()));
        pool.connections.put(NEW_LEADER, new StubConnection(successResponse()));
        ScanResultIterator iterator = newIterator();

        nextWithTimeout(iterator);

        Assert.assertFalse(iterator.hasNext());
        Assert.assertEquals(Arrays.asList(ORIGINAL_LEADER, NEW_LEADER), pool.borrowAttempts);
        Assert.assertEquals(Arrays.asList(ORIGINAL_LEADER, NEW_LEADER), pool.returnAttempts);
        Assert.assertTrue(pool.borrowed.isEmpty());
    }

    @Test
    public void interruptionStopsWorkersAndPreservesInterruptFlag() throws Exception {
        BlockingConnection connection = new BlockingConnection();
        pool.connections.put(ORIGINAL_LEADER, connection);
        ScanResultIterator iterator = newIterator();
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        AtomicBoolean interruptFlag = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                if (edge) {
                    ((ScanEdgeResultIterator) iterator).next();
                } else {
                    ((ScanVertexResultIterator) iterator).next();
                }
                outcome.set(new AssertionError("scan should propagate interruption"));
            } catch (Throwable failure) {
                outcome.set(failure);
                interruptFlag.set(Thread.currentThread().isInterrupted());
            }
        });
        caller.setDaemon(true);
        caller.start();
        try {
            Assert.assertTrue("scan worker did not start",
                    connection.started.await(3, TimeUnit.SECONDS));

            caller.interrupt();
            caller.join(3000);

            Assert.assertFalse("interrupted caller did not finish", caller.isAlive());
            Assert.assertTrue("scan should throw InterruptedException",
                    outcome.get() instanceof InterruptedException);
            Assert.assertTrue("caller interrupt flag was cleared", interruptFlag.get());
            Assert.assertFalse(iterator.hasNext());
            Assert.assertTrue("scan worker was not interrupted",
                    connection.interrupted.await(3, TimeUnit.SECONDS));
            connection.worker.join(3000);
            Assert.assertFalse("scan executor left a worker running", connection.worker.isAlive());
            Assert.assertEquals(Collections.singletonList(ORIGINAL_LEADER), pool.returnAttempts);
            Assert.assertTrue(pool.borrowed.isEmpty());
        } finally {
            connection.unblock.countDown();
            caller.interrupt();
            caller.join(3000);
            shutdownWorkers(iterator);
        }
    }

    private ScanResultIterator newIterator() {
        Set<PartScanInfo> parts = new HashSet<>();
        parts.add(new PartScanInfo(1, ORIGINAL_LEADER));
        List<HostAddress> addresses = Collections.singletonList(ORIGINAL_LEADER);
        if (edge) {
            return new ScanEdgeResultIterator.ScanEdgeResultBuilder()
                    .withPool(pool)
                    .withPartScanInfo(parts)
                    .withAddresses(addresses)
                    .withRequest(new ScanEdgeRequest())
                    .withSpaceName("test_space")
                    .withEdgeName("test_edge")
                    .build();
        }
        return new ScanVertexResultIterator.ScanVertexResultBuilder()
                .withPool(pool)
                .withPartScanInfo(parts)
                .withAddresses(addresses)
                .withRequest(new ScanVertexRequest())
                .withSpaceName("test_space")
                .withTagName("test_tag")
                .build();
    }

    private void assertScanFails(ScanResultIterator iterator, String message) throws Exception {
        try {
            nextWithTimeout(iterator);
            Assert.fail("scan should report the worker failure");
        } catch (ExecuteFailedException expected) {
            if (message != null) {
                Assert.assertTrue(expected.getMessage(), expected.getMessage().contains(message));
            }
        }
        Assert.assertFalse(iterator.hasNext());
    }

    private Object nextWithTimeout(ScanResultIterator iterator) throws Exception {
        ExecutorService caller = Executors.newSingleThreadExecutor();
        Future<Object> future = caller.submit(() -> edge
                ? ((ScanEdgeResultIterator) iterator).next()
                : ((ScanVertexResultIterator) iterator).next());
        try {
            return future.get(3, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new AssertionError("scan did not finish after its worker completed", timeout);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception) {
                throw (Exception) failure.getCause();
            }
            throw new AssertionError(failure.getCause());
        } finally {
            future.cancel(true);
            caller.shutdownNow();
            caller.awaitTermination(3, TimeUnit.SECONDS);
            shutdownWorkers(iterator);
        }
    }

    private void shutdownWorkers(ScanResultIterator iterator) throws Exception {
        // Older implementations leak their executor when await() is interrupted.
        // Clean it up so the regression can fail without leaving Maven running.
        for (Class<?> type = iterator.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (ExecutorService.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    ExecutorService executor = (ExecutorService) field.get(iterator);
                    if (executor != null) {
                        executor.shutdownNow();
                        executor.awaitTermination(3, TimeUnit.SECONDS);
                    }
                }
            }
        }
    }

    private static ScanResponse successResponse() {
        return new ScanResponse(
                new ResponseCommon(Collections.emptyList(), 0),
                new DataSet(Collections.emptyList(), Collections.emptyList()),
                Collections.singletonMap(1, new ScanCursor()));
    }

    private static ScanResponse leaderChangedResponse() {
        PartitionResult failure = new PartitionResult(ErrorCode.E_LEADER_CHANGED, 1,
                new HostAddr(NEW_LEADER.getHost(), NEW_LEADER.getPort()));
        return new ScanResponse(new ResponseCommon(Collections.singletonList(failure), 0));
    }

    private static class StubConnection extends GraphStorageConnection {
        private final ScanResponse response;

        StubConnection(ScanResponse response) {
            this.response = response;
        }

        @Override
        public ScanResponse scanVertex(ScanVertexRequest request) {
            return response;
        }

        @Override
        public ScanResponse scanEdge(ScanEdgeRequest request) {
            return response;
        }
    }

    private static class BlockingConnection extends StubConnection {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch unblock = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);
        private Thread worker;

        BlockingConnection() {
            super(successResponse());
        }

        @Override
        public ScanResponse scanVertex(ScanVertexRequest request) {
            awaitInterruption();
            return super.scanVertex(request);
        }

        @Override
        public ScanResponse scanEdge(ScanEdgeRequest request) {
            awaitInterruption();
            return super.scanEdge(request);
        }

        private void awaitInterruption() {
            worker = Thread.currentThread();
            started.countDown();
            try {
                unblock.await();
            } catch (InterruptedException failure) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("scan interrupted", failure);
            }
        }
    }

    private static class RecordingPool extends StorageConnPool {
        private final Map<HostAddress, StubConnection> connections = new HashMap<>();
        private final Map<GraphStorageConnection, HostAddress> borrowed = new IdentityHashMap<>();
        private final List<HostAddress> borrowAttempts = new ArrayList<>();
        private final List<HostAddress> returnAttempts = new ArrayList<>();
        private boolean failRelease;

        RecordingPool() {
            super(new StoragePoolConfig());
        }

        @Override
        public synchronized GraphStorageConnection getStorageConnection(HostAddress address)
                throws Exception {
            borrowAttempts.add(address);
            GraphStorageConnection connection = connections.get(address);
            if (connection == null) {
                throw new Exception("borrow failed");
            }
            if (borrowed.put(connection, address) != null) {
                throw new IllegalStateException("connection already borrowed");
            }
            return connection;
        }

        @Override
        public synchronized void release(HostAddress address, GraphStorageConnection connection) {
            returnAttempts.add(address);
            HostAddress owner = borrowed.remove(connection);
            if (!address.equals(owner)) {
                throw new IllegalStateException("connection returned twice or to the wrong leader");
            }
            if (failRelease) {
                throw new IllegalStateException("release failed");
            }
        }
    }
}
