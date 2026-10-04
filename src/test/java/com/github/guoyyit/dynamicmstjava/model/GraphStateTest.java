package com.github.guoyyit.dynamicmstjava.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphStateTest {

    /** 规范基准：对当前边集全量重算 Kruskal，边按 (weight,u,v) 字典序打破平局。 */
    private static List<Edge> referenceMst(Map<EdgeKey, Long> edges) {
        List<Edge> sorted = new ArrayList<>();
        edges.forEach((k, w) -> {
            if (!k.isSelfLoop()) {
                sorted.add(new Edge(k.u(), k.v(), w));
            }
        });
        sorted.sort(java.util.Comparator.comparingLong(Edge::weight)
                .thenComparing(Edge::u).thenComparing(Edge::v));
        Map<Integer, Integer> parent = new HashMap<>();
        List<Edge> result = new ArrayList<>();
        for (Edge e : sorted) {
            int ra = find(parent, e.u());
            int rb = find(parent, e.v());
            if (ra != rb) {
                parent.put(ra, rb);
                result.add(e);
            }
        }
        return result;
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
        while (true) {
            Integer p = parent.get(x);
            if (p == null) {
                break;
            }
            parent.put(x, root);
            x = p;
        }
        return root;
    }

    private void assertMatches(GraphState state, Map<EdgeKey, Long> expected) {
        List<Edge> ref = referenceMst(expected);
        MstResult actual = state.snapshot(0);
        assertEquals(ref.size(), actual.edgeCount(),
                () -> "ref=" + ref + " actual=" + actual.edges() + " all=" + expected);
        long total = 0;
        for (Edge e : ref) {
            total += e.weight();
        }
        assertEquals(total, actual.totalWeight(),
                () -> "ref=" + ref + " actual=" + actual.edges() + " all=" + expected);
        List<Edge> actualEdges = new ArrayList<>(actual.edges());
        actualEdges.sort(java.util.Comparator.comparing(Edge::key));
        ref.sort(java.util.Comparator.comparing(Edge::key));
        assertEquals(ref, actualEdges);
    }

    @Test
    void emptyGraphHasEmptyForest() {
        GraphState state = new GraphState();
        MstResult result = state.snapshot(7);
        assertEquals(7, result.version());
        assertEquals(0, result.edgeCount());
        assertEquals(0, result.componentCount());
        assertEquals(0, result.totalWeight());
    }

    @Test
    void disconnectedGraphYieldsForest() {
        GraphState state = new GraphState();
        state.insertEdge(EdgeKey.of(1, 2), 3);
        state.insertEdge(EdgeKey.of(2, 3), 5);
        state.insertEdge(EdgeKey.of(4, 5), 2);
        MstResult result = state.snapshot(1);
        assertEquals(3, result.edgeCount());
        assertEquals(2, result.componentCount());
        assertEquals(10, result.totalWeight());
    }

    @Test
    void selfLoopsAreNeverInForest() {
        GraphState state = new GraphState();
        state.insertEdge(EdgeKey.of(1, 1), 1);
        state.insertEdge(EdgeKey.of(1, 2), 4);
        assertEquals(1, state.snapshot(1).edgeCount());
        state.updateWeight(EdgeKey.of(1, 1), 9);
        assertEquals(1, state.snapshot(2).edgeCount());
        state.removeEdge(EdgeKey.of(1, 1));
        assertEquals(1, state.snapshot(3).edgeCount());
    }

    @Test
    void randomChangesMatchFullRecomputation() {
        Random rng = new Random(20240517L);
        int runs = 30;
            for (int run = 0; run < runs; run++) {
                GraphState state = new GraphState();
                Map<EdgeKey, Long> expected = new HashMap<>();
                for (int step = 0; step < 400; step++) {
                int a = rng.nextInt(8);
                int b = rng.nextInt(8);
                EdgeKey key = EdgeKey.of(a, b);
                int action = rng.nextInt(3);
                if (action == 0) {
                    long w = rng.nextInt(10);
                    if (!expected.containsKey(key)) {
                        state.insertEdge(key, w);
                        expected.put(key, w);
                    } else {
                        state.updateWeight(key, w);
                        expected.put(key, w);
                    }
                } else if (action == 1) {
                    state.removeEdge(key);
                    expected.remove(key);
                } else if (!expected.containsKey(key)) {
                    long w = rng.nextInt(10);
                    state.insertEdge(key, w);
                    expected.put(key, w);
                } else if (rng.nextBoolean()) {
                    long w = rng.nextInt(10);
                    state.updateWeight(key, w);
                    expected.put(key, w);
                }
                assertMatches(state, expected);
            }
        }
    }

    @Test
    void randomChangesWithManyEqualWeights() {
        Random rng = new Random(5555L);
        for (int run = 0; run < 20; run++) {
            GraphState state = new GraphState();
            Map<EdgeKey, Long> expected = new HashMap<>();
            for (int step = 0; step < 600; step++) {
                EdgeKey key = EdgeKey.of(rng.nextInt(15), rng.nextInt(15));
                long w = rng.nextInt(3);
                if (!expected.containsKey(key)) {
                    state.insertEdge(key, w);
                    expected.put(key, w);
                } else {
                    state.removeEdge(key);
                    expected.remove(key);
                    if (rng.nextBoolean()) {
                        state.insertEdge(key, w);
                        expected.put(key, w);
                    }
                }
                assertMatches(state, expected);
            }
        }
    }

    @Test
    void copyConstructorIsIndependent() {
        GraphState state = new GraphState();
        state.insertEdge(EdgeKey.of(1, 2), 1);
        GraphState copy = new GraphState(state);
        copy.insertEdge(EdgeKey.of(2, 3), 2);
        assertEquals(1, state.snapshot(0).edgeCount());
        assertEquals(2, copy.snapshot(0).edgeCount());
    }

    @Test
    void largeRandomSequenceMatchesFullRecomputation() {
        long[] seeds = {1L, 42L, 20261004L, -7L, 99L};
        for (long seed : seeds) {
            Random rng = new Random(seed);
            GraphState state = new GraphState();
            Map<EdgeKey, Long> expected = new HashMap<>();
            int vertices = 500 + rng.nextInt(3_000);
            for (int step = 0; step < 4_000; step++) {
                EdgeKey key = EdgeKey.of(rng.nextInt(vertices), rng.nextInt(vertices));
                if (key.isSelfLoop()) {
                    continue;
                }
                long w = rng.nextInt(1_000);
                int action = rng.nextInt(10);
                if (action < 5) {
                    if (!expected.containsKey(key)) {
                        state.insertEdge(key, w);
                        expected.put(key, w);
                    } else {
                        state.updateWeight(key, w);
                        expected.put(key, w);
                    }
                } else if (action < 8) {
                    if (expected.containsKey(key)) {
                        state.removeEdge(key);
                        expected.remove(key);
                    }
                } else if (!expected.containsKey(key)) {
                    state.insertEdge(key, w);
                    expected.put(key, w);
                }
                if ((step & 255) == 0) {
                    assertMatches(state, expected);
                }
            }
            assertMatches(state, expected);
        }
    }

    @Test
    void longUpdateHeavySequenceMatchesReference() {
        long[] seeds = {20261004L};
        for (long seed : seeds) {
            Random rng = new Random(seed);
            GraphState state = new GraphState();
            Map<EdgeKey, Long> expected = new HashMap<>();
            int vertices = 2_000;
            while (expected.size() < 8_000) {
                EdgeKey key = EdgeKey.of(rng.nextInt(vertices), rng.nextInt(vertices));
                if (!key.isSelfLoop() && !expected.containsKey(key)) {
                    long w = rng.nextInt(1_000_000);
                    expected.put(key, w);
                    state.insertEdge(key, w);
                }
            }
            List<EdgeKey> keys = new ArrayList<>(expected.keySet());
            for (int step = 0; step < 20_000; step++) {
                EdgeKey key = keys.get(rng.nextInt(keys.size()));
                long w = rng.nextInt(1_000_000);
                state.updateWeight(key, w);
                expected.put(key, w);
                if ((step % 1000) == 999) {
                    assertMatches(state, expected);
                }
            }
            assertMatches(state, expected);
        }
    }
}
