package com.github.guoyyit.dynamicmstjava.service;

import com.github.guoyyit.dynamicmstjava.model.ChangeOutcome;
import com.github.guoyyit.dynamicmstjava.model.Edge;
import com.github.guoyyit.dynamicmstjava.model.EdgeChange;
import com.github.guoyyit.dynamicmstjava.model.EdgeKey;
import com.github.guoyyit.dynamicmstjava.model.MstResult;
import com.github.guoyyit.dynamicmstjava.model.OpStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphServiceTest {

    @TempDir
    Path tempDir;

    private GraphService newService(String name, int maxBatch) {
        return new GraphService(tempDir.resolve(name).toString(), maxBatch, 50);
    }

    @Test
    void invalidOperationsAreDeterministicAndDoNotChangeResult() {
        try (GraphService service = newService("invalid", 10)) {
            assertEquals(OpStatus.IGNORED,
                    service.submit(EdgeChange.delete(1, 2)).results().get(0).status());
            assertEquals(OpStatus.IGNORED,
                    service.submit(EdgeChange.updateWeight(1, 2, 5)).results().get(0).status());

            assertEquals(OpStatus.APPLIED,
                    service.submit(EdgeChange.insert(1, 2, 5)).results().get(0).status());
            assertEquals(OpStatus.IGNORED,
                    service.submit(EdgeChange.insert(1, 2, 9)).results().get(0).status());
            assertEquals(OpStatus.IGNORED,
                    service.submit(EdgeChange.upsert(1, 2, 5)).results().get(0).status());
            assertEquals(OpStatus.IGNORED,
                    service.submit(EdgeChange.updateWeight(2, 1, 5)).results().get(0).status());

            MstResult before = service.queryMst();
            service.submit(EdgeChange.delete(3, 4));
            service.submit(EdgeChange.updateWeight(1, 2, 5));
            MstResult after = service.queryMst();
            assertEquals(before.totalWeight(), after.totalWeight());
            assertEquals(before.edges(), after.edges());

            assertEquals(OpStatus.APPLIED,
                    service.submit(EdgeChange.delete(2, 1)).results().get(0).status());
            assertEquals(OpStatus.IGNORED,
                    service.submit(EdgeChange.delete(1, 2)).results().get(0).status());
        }
    }

    @Test
    void batchIsAtomicAndOversizedBatchRejected() {
        try (GraphService service = newService("batch", 3)) {
            long version = service.currentVersion();
            ChangeOutcome outcome = service.submitBatch(List.of(
                    EdgeChange.insert(1, 2, 1),
                    EdgeChange.insert(1, 2, 9),
                    EdgeChange.upsert(2, 3, 2)));
            assertEquals(version + 1, outcome.version());
            assertEquals(List.of(OpStatus.APPLIED, OpStatus.IGNORED,
                    OpStatus.APPLIED),
                    outcome.results().stream().map(ChangeOutcome.OpResult::status).toList());

            assertThrows(IllegalArgumentException.class, () -> service.submitBatch(List.of(
                    EdgeChange.upsert(1, 2, 1),
                    new EdgeChange(null, 1, 2, 3L))));
            assertEquals(version + 1, service.currentVersion());

            assertThrows(IllegalArgumentException.class, () -> service.submitBatch(List.of(
                    EdgeChange.upsert(1, 2, 1),
                    EdgeChange.upsert(2, 3, 2),
                    EdgeChange.upsert(3, 4, 3),
                    EdgeChange.upsert(4, 5, 4))));
            assertEquals(version + 1, service.currentVersion());
        }
    }

    @Test
    void randomBatchesMatchFullRecomputation() {
        try (GraphService service = newService("random", 100)) {
            Random rng = new Random(99887766L);
            Map<EdgeKey, Long> expected = new HashMap<>();
            for (int step = 0; step < 300; step++) {
                int batchSize = 1 + rng.nextInt(8);
                List<EdgeChange> batch = new java.util.ArrayList<>();
                for (int i = 0; i < batchSize; i++) {
                    EdgeKey key = EdgeKey.of(rng.nextInt(12), rng.nextInt(12));
                    int kind = rng.nextInt(3);
                    if (kind == 0) {
                        batch.add(EdgeChange.delete(key.u(), key.v()));
                    } else {
                        long w = rng.nextInt(20);
                        batch.add(EdgeChange.upsert(key.u(), key.v(), w));
                    }
                }
                service.submitBatch(batch);
                for (EdgeChange change : batch) {
                    if (change.type() == com.github.guoyyit.dynamicmstjava.model.ChangeType.DELETE) {
                        expected.remove(change.key());
                    } else {
                        expected.put(change.key(), change.weight());
                    }
                }
                ReferenceMst ref = ReferenceMst.of(expected);
                MstResult actual = service.queryMst();
                assertEquals(ref.weight(), actual.totalWeight());
                assertEquals(ref.edges().size(), actual.edgeCount());
                java.util.TreeSet<Edge> actualSet =
                        new java.util.TreeSet<>(java.util.Comparator.comparing(Edge::key));
                actualSet.addAll(actual.edges());
                assertEquals(ref.edges(), actualSet);
            }
        }
    }

    @Test
    void concurrentQueriesAlwaysObserveCommittedVersions() throws Exception {
        try (GraphService service = newService("concurrent", 1000)) {
            int writers = 4;
            int readers = 4;
            int commitsPerWriter = 200;
            ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
            CountDownLatch start = new CountDownLatch(1);
            AtomicLong invalidReads = new AtomicLong();
            for (int r = 0; r < readers; r++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        long endAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                        while (System.nanoTime() < endAt) {
                            MstResult result = service.queryMst();
                            long computed = 0;
                            for (Edge e : result.edges()) {
                                computed += e.weight();
                            }
                            if (computed != result.totalWeight() || result.edgeCount() != result.edges().size()) {
                                invalidReads.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            for (int w = 0; w < writers; w++) {
                int writerId = w;
                pool.submit(() -> {
                    try {
                        start.await();
                        Random rng = new Random(writerId);
                        for (int i = 0; i < commitsPerWriter; i++) {
                            service.submit(EdgeChange.upsert(
                                    rng.nextInt(50), rng.nextInt(50), rng.nextInt(100)));
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
            assertEquals(0, invalidReads.get());
        }
    }

    @Test
    void committedChangesSurviveRestartUncommittedDoNot() {
        String dir = tempDir.resolve("restart").toString();
        try (GraphService service = new GraphService(dir, 100, 2)) {
            service.submit(EdgeChange.upsert(1, 2, 3));
            service.submit(EdgeChange.upsert(2, 3, 4));
            service.submit(EdgeChange.upsert(3, 4, 5));
            service.submit(EdgeChange.delete(2, 3));
            service.submit(EdgeChange.upsert(4, 5, 6));
        }
        try (GraphService service = new GraphService(dir, 100, 2)) {
            MstResult result = service.queryMst();
            assertEquals(14, result.totalWeight());
            assertEquals(3, result.edgeCount());
            assertEquals(2, result.componentCount());

            service.submit(EdgeChange.upsert(1, 5, 2));
        }
        try (GraphService service = new GraphService(dir, 100, 2)) {
            MstResult result = service.queryMst();
            assertEquals(16, result.totalWeight());
            assertEquals(4, result.edgeCount());
        }
    }

    /** 测试内的全量重算基准。 */
    record ReferenceMst(long weight, java.util.TreeSet<Edge> edges) {
        static ReferenceMst of(Map<EdgeKey, Long> edges) {
            List<Edge> sorted = new java.util.ArrayList<>();
            edges.forEach((k, w) -> {
                if (!k.isSelfLoop()) {
                    sorted.add(new Edge(k.u(), k.v(), w));
                }
            });
            sorted.sort(java.util.Comparator.comparingLong(Edge::weight)
                    .thenComparing(Edge::u).thenComparing(Edge::v));
            Map<Integer, Integer> parent = new ConcurrentHashMap<>();
            java.util.TreeSet<Edge> chosen = new java.util.TreeSet<>(
                    java.util.Comparator.comparing(Edge::key));
            long total = 0;
            for (Edge e : sorted) {
                int ra = find(parent, e.u());
                int rb = find(parent, e.v());
                if (ra != rb) {
                    parent.put(ra, rb);
                    chosen.add(e);
                    total += e.weight();
                }
            }
            return new ReferenceMst(total, chosen);
        }

        private static int find(Map<Integer, Integer> parent, int x) {
            int root = x;
            while (true) {
                Integer p = parent.get(root);
                if (p == null) {
                    break;
                }
                root = p;
            }
            return root;
        }
    }
}
