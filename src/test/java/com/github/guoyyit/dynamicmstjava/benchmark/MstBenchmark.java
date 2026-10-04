package com.github.guoyyit.dynamicmstjava.benchmark;

import com.github.guoyyit.dynamicmstjava.graph.Batch;
import com.github.guoyyit.dynamicmstjava.graph.Change;
import com.github.guoyyit.dynamicmstjava.graph.Edge;
import com.github.guoyyit.dynamicmstjava.graph.GraphService;
import com.github.guoyyit.dynamicmstjava.graph.MstUtils;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Benchmark comparing the dynamic MST throughput against full recomputation.
 *
 * <p>Run with: {@code mvn test -Dtest=MstBenchmark}
 */
class MstBenchmark {

    @Test
    void benchmark100kEdges() {
        int numEdges = 100_000;
        int numVertices = 10_000;
        int numChanges = 100;
        int batchSize = 10;

        Random rng = new Random(42);
        GraphService svc = new GraphService();

        // Build initial graph with numEdges edges using Kruskal (fast).
        List<Edge> initialEdges = new ArrayList<>();
        for (int i = 0; i < numEdges; i++) {
            int u = rng.nextInt(numVertices);
            int v = rng.nextInt(numVertices);
            if (u == v) {
                v = (v + 1) % numVertices;
            }
            initialEdges.add(new Edge(Math.min(u, v), Math.max(u, v), rng.nextLong(1_000_000)));
        }
        svc.loadEdges(initialEdges);
        System.out.println("Initial graph built: " + svc.edgeCount() + " edges");

        // Generate a stream of changes (mostly weight updates on existing edges).
        List<Change> changes = new ArrayList<>();
        for (int i = 0; i < numChanges; i++) {
            int u = rng.nextInt(numVertices);
            int v = rng.nextInt(numVertices);
            if (u == v) {
                v = (v + 1) % numVertices;
            }
            // 70% weight updates, 20% inserts, 10% deletes.
            int type = rng.nextInt(10);
            if (type < 7) {
                changes.add(new Change.Update(u, v, rng.nextLong(1_000_000)));
            } else if (type < 9) {
                changes.add(new Change.Insert(u, v, rng.nextLong(1_000_000)));
            } else {
                changes.add(new Change.Delete(u, v));
            }
        }

        // Benchmark 1: dynamic MST (our approach).
        long startDynamic = System.nanoTime();
        for (int i = 0; i < changes.size(); i += batchSize) {
            int end = Math.min(i + batchSize, changes.size());
            svc.commit(new Batch(changes.subList(i, end)));
        }
        long dynamicTime = System.nanoTime() - startDynamic;
        System.out.printf("Dynamic MST: %d ms for %d changes (%.2f us/change)%n",
                dynamicTime / 1_000_000, numChanges,
                (double) dynamicTime / numChanges / 1000);

        // Benchmark 2: full recompute per batch.
        // Reset to initial state.
        svc = new GraphService();
        svc.loadEdges(initialEdges);
        long startRecompute = System.nanoTime();
        for (int i = 0; i < changes.size(); i += batchSize) {
            int end = Math.min(i + batchSize, changes.size());
            svc.commit(new Batch(changes.subList(i, end)));
            // Full recompute after each batch.
            List<Edge> edges = svc.edges();
            MstUtils.kruskal(edges);
        }
        long recomputeTime = System.nanoTime() - startRecompute;
        System.out.printf("Full recompute: %d ms for %d changes (%.2f us/change)%n",
                recomputeTime / 1_000_000, numChanges,
                (double) recomputeTime / numChanges / 1000);

        double speedup = (double) recomputeTime / dynamicTime;
        System.out.printf("Speedup: %.2fx%n", speedup);

        // Estimate memory usage of the data structures.
        // Edge set: E edges * (Edge record + Long key) ~ 24 bytes per entry.
        // LCA table: n vertices * log2(n) levels * (4 + 8 + 8) bytes.
        // MST adjacency: n vertices * 2 * MstEdge record ~ 32 bytes per entry.
        int numEdgesActual = svc.edgeCount();
        int numVerticesActual = numVertices;
        int logN = 31 - Integer.numberOfLeadingZeros(numVerticesActual);
        long edgeSetMemory = numEdgesActual * 24L;
        long lcaMemory = (long) numVerticesActual * logN * 20;
        long mstMemory = (long) numVerticesActual * 2 * 32;
        long totalMemory = edgeSetMemory + lcaMemory + mstMemory;
        System.out.printf("Estimated memory: %.2f MB (edge set: %.2f MB, LCA table: %.2f MB, MST adj: %.2f MB)%n",
                totalMemory / (1024.0 * 1024.0),
                edgeSetMemory / (1024.0 * 1024.0),
                lcaMemory / (1024.0 * 1024.0),
                mstMemory / (1024.0 * 1024.0));

        // Verify correctness: final MST should match full recompute.
        List<Edge> finalEdges = svc.edges();
        var expected = MstUtils.kruskal(finalEdges);
        var actual = svc.query();
        if (expected.totalWeight() != actual.totalWeight()) {
            throw new AssertionError("MST mismatch: expected " + expected.totalWeight()
                    + " but got " + actual.totalWeight());
        }
        System.out.println("Correctness verified: MST matches full recompute");
    }
}
