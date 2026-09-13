/* Copyright (c) 2020 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.nebula.client.storage.scan;

import com.google.common.base.Charsets;
import com.vesoft.nebula.DataSet;
import com.vesoft.nebula.ErrorCode;
import com.vesoft.nebula.HostAddr;
import com.vesoft.nebula.client.graph.data.HostAddress;
import com.vesoft.nebula.client.meta.MetaManager;
import com.vesoft.nebula.client.storage.GraphStorageConnection;
import com.vesoft.nebula.client.storage.StorageConnPool;
import com.vesoft.nebula.client.storage.data.ScanStatus;
import com.vesoft.nebula.storage.ScanCursor;
import com.vesoft.nebula.storage.ScanEdgeRequest;
import com.vesoft.nebula.storage.ScanResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScanEdgeResultIterator extends ScanResultIterator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScanEdgeResultIterator.class);

    private final ScanEdgeRequest request;
    private       ExecutorService threadPool = null;

    private ScanEdgeResultIterator(MetaManager metaManager,
                                   StorageConnPool pool,
                                   Set<PartScanInfo> partScanInfoList,
                                   List<HostAddress> addresses,
                                   ScanEdgeRequest request,
                                   String spaceName,
                                   String labelName,
                                   boolean partSuccess,
                                   String user,
                                   String password,
                                   Map<String, String> storageAddressMapping) {
        super(metaManager, pool, new PartScanQueue(partScanInfoList), addresses, spaceName,
              labelName, partSuccess, user, password, storageAddressMapping);
        this.request = request;
    }


    /**
     * get the next edge set
     *
     * <p>in every next function, the client will send new scan request to storage server
     * parallel, and the parallel num is the space's leader hosts.
     *
     * @return {@link ScanEdgeResult}
     */
    public ScanEdgeResult next() throws Exception {
        if (!hasNext()) {
            throw new IllegalAccessException("iterator has no more data");
        }

        final List<DataSet> results =
                Collections.synchronizedList(new ArrayList<>(addresses.size()));
        List<Exception> exceptions =
                Collections.synchronizedList(new ArrayList<>(addresses.size()));
        CountDownLatch countDownLatch = new CountDownLatch(addresses.size());
        AtomicInteger  existSuccess   = new AtomicInteger(0);

        threadPool = Executors.newFixedThreadPool(addresses.size());
        try {
            for (HostAddress addr : addresses) {
                threadPool.submit(() -> {
                    PartScanInfo partInfo = null;
                    try {
                        HostAddress leader = addr;
                        partInfo = partScanQueue.getPart(leader);
                        // Publish all worker state before counting down the latch.
                        if (partInfo == null) {
                            existSuccess.incrementAndGet();
                            return;
                        }

                        Map<Integer, ScanCursor> cursorMap = new HashMap<>();
                        cursorMap.put(partInfo.getPart(), partInfo.getCursor());
                        ScanEdgeRequest partRequest = new ScanEdgeRequest(request);
                        partRequest.setParts(cursorMap);
                        if (user != null && password != null) {
                            partRequest.setUsername(user.getBytes(Charsets.UTF_8));
                            partRequest.setPassword(password.getBytes(Charsets.UTF_8));
                        }
                        partRequest.setNeed_authenticate(true);

                        GraphStorageConnection connection;
                        try {
                            connection = pool.getStorageConnection(leader);
                        } catch (Exception e) {
                            LOGGER.error("get storage client error, ", e);
                            exceptions.add(e);
                            return;
                        }

                        ScanResponse response;
                        try {
                            response = connection.scanEdge(partRequest);
                            if (response != null && response.getResult() != null
                                    && !response.getResult().failed_parts.isEmpty()
                                    && response.getResult().failed_parts.get(0).code
                                    == ErrorCode.E_LEADER_CHANGED) {
                                GraphStorageConnection oldConnection = connection;
                                connection = null;
                                pool.release(leader, oldConnection);
                                HostAddr newLeader =
                                        response.getResult().failed_parts.get(0).leader;
                                HostAddr availableLeader = storageAddressMapping
                                        .getOrDefault(newLeader, newLeader);
                                leader = new HostAddress(availableLeader.host,
                                                         availableLeader.getPort());
                                connection = pool.getStorageConnection(leader);
                                response = connection.scanEdge(partRequest);
                            }
                        } finally {
                            if (connection != null) {
                                pool.release(leader, connection);
                            }
                        }

                        if (response == null) {
                            handleNullResponse(partInfo, exceptions);
                            return;
                        }
                        if (response.getResult() == null) {
                            handleNullResult(partInfo, exceptions);
                            return;
                        }
                        if (isSuccessful(response)) {
                            handleSucceedResult(existSuccess, response, partInfo);
                            results.add(response.getProps());
                        } else {
                            handleFailedResult(response, partInfo, exceptions);
                        }
                    } catch (Exception e) {
                        LOGGER.error("Scan edge failed", e);
                        exceptions.add(e);
                        if (partInfo != null) {
                            partScanQueue.dropPart(partInfo);
                        }
                    } finally {
                        countDownLatch.countDown();
                    }
                });
            }
            countDownLatch.await();
        } catch (InterruptedException interruptedE) {
            hasNext = false;
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
            LOGGER.error("scan interrupted:", interruptedE);
            throw interruptedE;
        } finally {
            threadPool.shutdown();
        }

        if (partSuccess) {
            hasNext = partScanQueue.size() > 0;
            // no part succeed, throw ExecuteFailedException
            if (existSuccess.get() == 0) {
                throwExceptions(exceptions);
            }
            ScanStatus status = exceptions.size() > 0 ? ScanStatus.PART_SUCCESS :
                    ScanStatus.ALL_SUCCESS;
            return new ScanEdgeResult(results, status);
        } else {
            hasNext = partScanQueue.size() > 0 && exceptions.isEmpty();
            // any part failed, throw ExecuteFailedException
            if (!exceptions.isEmpty()) {
                throwExceptions(exceptions);
            }
            // Keep successful pages while leader changes are retried on the next call.
            return new ScanEdgeResult(results, ScanStatus.ALL_SUCCESS);
        }
    }


    /**
     * builder to build {@link ScanEdgeResultIterator}
     */
    public static class ScanEdgeResultBuilder {

        MetaManager         metaManager;
        StorageConnPool     pool;
        Set<PartScanInfo>   partScanInfoList;
        List<HostAddress>   addresses;
        ScanEdgeRequest     request;
        String              spaceName;
        String              edgeName;
        boolean             partSuccess           = false;
        String              user                  = null;
        String              password              = null;
        Map<String, String> storageAddressMapping = null;

        public ScanEdgeResultBuilder withMetaClient(MetaManager metaManager) {
            this.metaManager = metaManager;
            return this;
        }

        public ScanEdgeResultBuilder withPool(StorageConnPool pool) {
            this.pool = pool;
            return this;
        }

        public ScanEdgeResultBuilder withPartScanInfo(Set<PartScanInfo> partScanInfoList) {
            this.partScanInfoList = partScanInfoList;
            return this;
        }

        public ScanEdgeResultBuilder withAddresses(List<HostAddress> addresses) {
            this.addresses = addresses;
            return this;
        }

        public ScanEdgeResultBuilder withRequest(ScanEdgeRequest request) {
            this.request = request;
            return this;
        }

        public ScanEdgeResultBuilder withSpaceName(String spaceName) {
            this.spaceName = spaceName;
            return this;
        }

        public ScanEdgeResultBuilder withEdgeName(String edgeName) {
            this.edgeName = edgeName;
            return this;
        }

        public ScanEdgeResultBuilder withPartSuccess(boolean partSuccess) {
            this.partSuccess = partSuccess;
            return this;
        }

        public ScanEdgeResultBuilder withUser(String user) {
            this.user = user;
            return this;
        }

        public ScanEdgeResultBuilder withPassword(String password) {
            this.password = password;
            return this;
        }

        public ScanEdgeResultBuilder withStorageAddressMapping(
                Map<String, String> storageAddressMapping) {
            this.storageAddressMapping = storageAddressMapping;
            return this;
        }

        public ScanEdgeResultIterator build() {
            return new ScanEdgeResultIterator(
                    metaManager,
                    pool,
                    partScanInfoList,
                    addresses,
                    request,
                    spaceName,
                    edgeName,
                    partSuccess,
                    user,
                    password,
                    storageAddressMapping);
        }
    }
}
