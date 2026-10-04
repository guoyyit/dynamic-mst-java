package com.github.guoyyit.dynamicmstjava.graph;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Large-scale correctness test that checks the MST after each change.
 */
class LargeScaleCorrectnessTest {

    @Test
    void largeScaleRandomSequence() {
        int numVertices = 100;
        int numEdges = 500;
        int numChanges = 200;

        Random rng = new Random(42);
        GraphService svc = new GraphService();

        // Build initial graph.
        List<Edge> initialEdges = new ArrayList<>();
        for (int i = 0; i < numEdges; i++) {
            int u = rng.nextInt(numVertices);
            int v = rng.nextInt(numVertices);
            if (u == v) {
                v = (v + 1) % numVertices;
            }
            initialEdges.add(new Edge(Math.min(u, v), Math.max(u, v), rng.nextLong(1000)));
        }
        svc.loadEdges(initialEdges);

        // Apply changes one by one, checking after each.
        for (int i = 0; i < numChanges; i++) {
            int u = rng.nextInt(numVertices);
            int v = rng.nextInt(numVertices);
            if (u == v) {
                v = (v + 1) % numVertices;
            }
            Change c = switch (rng.nextInt(3)) {
                case 0 -> new Change.Insert(u, v, rng.nextLong(1000));
                case 1 -> new Change.Delete(u, v);
                default -> new Change.Update(u, v, rng.nextLong(1000));
            };
            svc.commit(new Batch(List.of(c)));

            List<Edge> currentEdges = svc.edges();
            MstResult expected = MstUtils.kruskal(currentEdges);
            MstResult actual = svc.query();
            assertEquals(expected.totalWeight(), actual.totalWeight(),
                    "Mismatch after change " + i + ": " + c);
            assertEquals(expected.edges().size(), actual.edges().size(),
                    "Edge count mismatch after change " + i + ": " + c);
        }
    }
}
