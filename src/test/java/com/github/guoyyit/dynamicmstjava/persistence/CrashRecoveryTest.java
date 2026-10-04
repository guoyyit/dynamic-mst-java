package com.github.guoyyit.dynamicmstjava.persistence;

import com.github.guoyyit.dynamicmstjava.graph.Batch;
import com.github.guoyyit.dynamicmstjava.graph.Change;
import com.github.guoyyit.dynamicmstjava.graph.Edge;
import com.github.guoyyit.dynamicmstjava.graph.GraphService;
import com.github.guoyyit.dynamicmstjava.graph.MstResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests crash recovery: committed batches survive, uncommitted batches disappear.
 */
class CrashRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void committedBatchesSurviveCrash() throws IOException {
        Path dataDir = tempDir.resolve("data");
        // Phase 1: create service, commit batches, then "crash" (close without checkpoint).
        {
            GraphService svc = new GraphService();
            svc.enablePersistence(dataDir);
            svc.commit(new Batch(List.of(
                    new Change.Insert(0, 1, 10),
                    new Change.Insert(1, 2, 20)
            )));
            svc.commit(new Batch(List.of(
                    new Change.Insert(2, 3, 30)
            )));
            // Simulate crash: just abandon the service (no checkpoint).
        }
        // Phase 2: recover.
        GraphService recovered = Recovery.recover(dataDir);
        MstResult result = recovered.query();
        assertEquals(3, result.edges().size());
        assertEquals(60, result.totalWeight());
    }

    @Test
    void uncommittedBatchesDisappear() throws IOException {
        Path dataDir = tempDir.resolve("data");
        // Phase 1: create service, commit one batch, then simulate a partial write
        // (a batch that was written to the WAL but not committed).
        {
            GraphService svc = new GraphService();
            svc.enablePersistence(dataDir);
            svc.commit(new Batch(List.of(
                    new Change.Insert(0, 1, 10)
            )));
            // Simulate a partial WAL write: append BEGIN and a change but no COMMIT.
            // We do this by directly writing to the WAL file.
            Path walPath = dataDir.resolve("wal");
            try (java.io.BufferedWriter writer = java.nio.file.Files.newBufferedWriter(walPath,
                    java.nio.file.StandardOpenOption.APPEND)) {
                writer.write("B 1");
                writer.newLine();
                writer.write("I 1 1 2 20");
                writer.newLine();
                // No COMMIT record.
            }
        }
        // Phase 2: recover. The uncommitted batch should be discarded.
        GraphService recovered = Recovery.recover(dataDir);
        MstResult result = recovered.query();
        assertEquals(1, result.edges().size());
        assertEquals(10, result.totalWeight());
    }

    @Test
    void recoveryMatchesFullRecompute() throws IOException {
        Path dataDir = tempDir.resolve("data");
        // Generate a random sequence of changes and commit them.
        java.util.Random rng = new java.util.Random(123);
        java.util.List<Change> allChanges = new java.util.ArrayList<>();
        {
            GraphService svc = new GraphService();
            svc.enablePersistence(dataDir);
            for (int i = 0; i < 50; i++) {
                int u = rng.nextInt(10);
                int v = rng.nextInt(10);
                if (u == v) {
                    continue;
                }
                Change c = switch (rng.nextInt(3)) {
                    case 0 -> new Change.Insert(u, v, rng.nextLong(100));
                    case 1 -> new Change.Delete(u, v);
                    default -> new Change.Update(u, v, rng.nextLong(100));
                };
                allChanges.add(c);
                svc.commit(new Batch(List.of(c)));
            }
            // Force a checkpoint.
            svc.checkpoint();
        }
        // Recover and compare with full recompute.
        GraphService recovered = Recovery.recover(dataDir);
        List<Edge> edges = recovered.edges();
        MstResult expected = com.github.guoyyit.dynamicmstjava.graph.MstUtils.kruskal(edges);
        MstResult actual = recovered.query();
        assertEquals(expected.totalWeight(), actual.totalWeight());
        assertEquals(expected.edges().size(), actual.edges().size());
    }

    @Test
    void noDoubleApplication() throws IOException {
        Path dataDir = tempDir.resolve("data");
        // Commit a batch, checkpoint, then commit more batches.
        {
            GraphService svc = new GraphService();
            svc.enablePersistence(dataDir);
            svc.commit(new Batch(List.of(new Change.Insert(0, 1, 10))));
            svc.checkpoint();
            svc.commit(new Batch(List.of(new Change.Insert(1, 2, 20))));
        }
        // Recover. The checkpoint should not be replayed.
        GraphService recovered = Recovery.recover(dataDir);
        MstResult result = recovered.query();
        assertEquals(2, result.edges().size());
        assertEquals(30, result.totalWeight());
    }
}
