package com.github.guoyyit.dynamicmstjava.graph;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the maintained MST always matches a full recomputation (Kruskal).
 */
class MstCorrectnessTest {

    // Kruskal implementation moved to MstUtils.kruskal.

    @Test
    void emptyGraph() {
        GraphService svc = new GraphService();
        MstResult result = svc.query();
        assertTrue(result.edges().isEmpty());
        assertEquals(0, result.totalWeight());
    }

    @Test
    void singleEdge() {
        GraphService svc = new GraphService();
        svc.commit(new Batch(List.of(new Change.Insert(0, 1, 5))));
        MstResult result = svc.query();
        assertEquals(1, result.edges().size());
        assertEquals(5, result.totalWeight());
    }

    @Test
    void triangle() {
        GraphService svc = new GraphService();
        svc.commit(new Batch(List.of(
                new Change.Insert(0, 1, 1),
                new Change.Insert(1, 2, 2),
                new Change.Insert(0, 2, 3)
        )));
        MstResult result = svc.query();
        // MST should have edges (0,1,1) and (1,2,2), total 3.
        assertEquals(2, result.edges().size());
        assertEquals(3, result.totalWeight());
    }

    @Test
    void randomSequenceMatchesKruskal() {
        Random rng = new Random(42);
        for (int trial = 0; trial < 20; trial++) {
            GraphService svc = new GraphService();
            List<Change> changes = new ArrayList<>();
            int numVertices = 20;
            // Generate a random sequence of changes.
            for (int i = 0; i < 200; i++) {
                int u = rng.nextInt(numVertices);
                int v = rng.nextInt(numVertices);
                if (u == v) {
                    continue;
                }
                int type = rng.nextInt(3);
                switch (type) {
                    case 0 -> changes.add(new Change.Insert(u, v, rng.nextLong(100)));
                    case 1 -> changes.add(new Change.Delete(u, v));
                    case 2 -> changes.add(new Change.Update(u, v, rng.nextLong(100)));
                }
            }
            // Apply changes one by one, checking after each.
            for (Change c : changes) {
                svc.commit(new Batch(List.of(c)));
                List<Edge> currentEdges = svc.edges();
                MstResult expected = MstUtils.kruskal(currentEdges);
                MstResult actual = svc.query();
                assertEquals(expected.totalWeight(), actual.totalWeight(),
                        "Mismatch after change: " + c);
                // Also check that the actual MST is a valid spanning forest.
                assertEquals(expected.edges().size(), actual.edges().size(),
                        "Edge count mismatch after change: " + c);
            }
        }
    }

    @Test
    void batchAtomicity() {
        GraphService svc = new GraphService();
        // A batch with a duplicate insert (no-op) and a new edge.
        svc.commit(new Batch(List.of(
                new Change.Insert(0, 1, 5),
                new Change.Insert(0, 1, 5), // duplicate, no-op
                new Change.Insert(1, 2, 3)
        )));
        MstResult result = svc.query();
        assertEquals(2, result.edges().size());
        assertEquals(8, result.totalWeight());
    }

    @Test
    void deleteNonExistentIsNoop() {
        GraphService svc = new GraphService();
        svc.commit(new Batch(List.of(
                new Change.Insert(0, 1, 5),
                new Change.Delete(2, 3) // non-existent
        )));
        assertEquals(1, svc.edgeCount());
        assertEquals(5, svc.query().totalWeight());
    }

    @Test
    void updateNonExistentIsNoop() {
        GraphService svc = new GraphService();
        svc.commit(new Batch(List.of(
                new Change.Insert(0, 1, 5),
                new Change.Update(2, 3, 10) // non-existent
        )));
        assertEquals(1, svc.edgeCount());
        assertEquals(5, svc.query().totalWeight());
    }

    @Test
    void updateSameWeightIsNoop() {
        GraphService svc = new GraphService();
        svc.commit(new Batch(List.of(
                new Change.Insert(0, 1, 5),
                new Change.Update(0, 1, 5) // same weight
        )));
        assertEquals(1, svc.edgeCount());
        assertEquals(5, svc.query().totalWeight());
    }

    @Test
    void disconnectedGraphReturnsForest() {
        GraphService svc = new GraphService();
        svc.commit(new Batch(List.of(
                new Change.Insert(0, 1, 1),
                new Change.Insert(2, 3, 2),
                new Change.Insert(4, 5, 3)
        )));
        MstResult result = svc.query();
        assertEquals(3, result.edges().size());
        assertEquals(6, result.totalWeight());
    }

    @Test
    void weightUpdateTriggersReplacement() {
        GraphService svc = new GraphService();
        // Build a graph where (0,1) is the MST edge but (0,2) is a cheaper replacement.
        svc.commit(new Batch(List.of(
                new Change.Insert(0, 1, 10),
                new Change.Insert(1, 2, 10),
                new Change.Insert(0, 2, 100) // not in MST
        )));
        // MST: (0,1,10), (1,2,10). Total 20.
        assertEquals(20, svc.query().totalWeight());
        // Increase weight of (0,1) to 200. It should be replaced by (0,2,100).
        svc.commit(new Batch(List.of(new Change.Update(0, 1, 200))));
        // MST: (0,2,100), (1,2,10). Total 110.
        assertEquals(110, svc.query().totalWeight());
    }

    @Test
    void deleteMstEdgeTriggersReplacement() {
        GraphService svc = new GraphService();
        svc.commit(new Batch(List.of(
                new Change.Insert(0, 1, 10),
                new Change.Insert(1, 2, 10),
                new Change.Insert(0, 2, 100) // not in MST
        )));
        // Delete (0,1) from MST. It should be replaced by (0,2,100).
        svc.commit(new Batch(List.of(new Change.Delete(0, 1))));
        // MST: (0,2,100), (1,2,10). Total 110.
        assertEquals(110, svc.query().totalWeight());
    }
}
