package com.github.guoyyit.dynamicmstjava.graph;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests concurrent reads and writes.
 */
class ConcurrencyTest {

    @Test
    void concurrentReadsAndWrites() throws InterruptedException {
        GraphService svc = new GraphService();
        int numWriters = 4;
        int numReaders = 4;
        int batchesPerWriter = 50;
        int numVertices = 20;

        ExecutorService writerPool = Executors.newFixedThreadPool(numWriters);
        ExecutorService readerPool = Executors.newFixedThreadPool(numReaders);
        CountDownLatch writerLatch = new CountDownLatch(numWriters);
        CountDownLatch readerLatch = new CountDownLatch(numReaders);
        AtomicBoolean failed = new AtomicBoolean(false);

        // Writers: commit random batches.
        for (int w = 0; w < numWriters; w++) {
            final int writerId = w;
            writerPool.submit(() -> {
                try {
                    Random rng = new Random(writerId * 1000L);
                    for (int i = 0; i < batchesPerWriter; i++) {
                        List<Change> changes = new ArrayList<>();
                        int batchSize = rng.nextInt(5) + 1;
                        for (int j = 0; j < batchSize; j++) {
                            int u = rng.nextInt(numVertices);
                            int v = rng.nextInt(numVertices);
                            if (u == v) {
                                continue;
                            }
                            Change c = switch (rng.nextInt(3)) {
                                case 0 -> new Change.Insert(u, v, rng.nextLong(100));
                                case 1 -> new Change.Delete(u, v);
                                default -> new Change.Update(u, v, rng.nextLong(100));
                            };
                            changes.add(c);
                        }
                        if (!changes.isEmpty()) {
                            svc.commit(new Batch(changes));
                        }
                    }
                } catch (Exception e) {
                    failed.set(true);
                    e.printStackTrace();
                } finally {
                    writerLatch.countDown();
                }
            });
        }

        // Readers: query continuously and verify consistency.
        for (int r = 0; r < numReaders; r++) {
            readerPool.submit(() -> {
                try {
                    while (!writerLatch.await(10, TimeUnit.MILLISECONDS)) {
                        MstResult result = svc.query();
                        // Verify that the result is a valid spanning forest.
                        // The number of edges should be <= numVertices - 1.
                        assertTrue(result.edges().size() <= numVertices - 1,
                                "MST has too many edges: " + result.edges().size());
                        // Verify that the total weight is non-negative.
                        assertTrue(result.totalWeight() >= 0,
                                "MST has negative total weight: " + result.totalWeight());
                    }
                } catch (Exception e) {
                    failed.set(true);
                    e.printStackTrace();
                } finally {
                    readerLatch.countDown();
                }
            });
        }

        writerLatch.await(30, TimeUnit.SECONDS);
        readerLatch.await(5, TimeUnit.SECONDS);
        writerPool.shutdown();
        readerPool.shutdown();

        // Final verification: the MST should match a full recompute.
        List<Edge> edges = svc.edges();
        MstResult expected = MstUtils.kruskal(edges);
        MstResult actual = svc.query();
        assertEquals(expected.totalWeight(), actual.totalWeight(),
                "Final MST does not match full recompute");
        assertEquals(expected.edges().size(), actual.edges().size(),
                "Final MST edge count does not match full recompute");

        assertTrue(!failed.get(), "Concurrent test failed");
    }

    @Test
    void querySeesConsistentSnapshot() throws InterruptedException {
        GraphService svc = new GraphService();
        // Insert a triangle.
        svc.commit(new Batch(List.of(
                new Change.Insert(0, 1, 1),
                new Change.Insert(1, 2, 2),
                new Change.Insert(0, 2, 3)
        )));
        // Query should see a consistent result.
        MstResult result = svc.query();
        assertEquals(2, result.edges().size());
        assertEquals(3, result.totalWeight());
    }
}
