package com.github.guoyyit.dynamicmstjava.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility methods for MST computation.
 */
public final class MstUtils {

    private MstUtils() {
    }

    /**
     * Full recomputation of the minimum spanning forest using Kruskal's algorithm.
     */
    public static MstResult kruskal(List<Edge> edges) {
        List<Edge> sorted = new ArrayList<>(edges);
        sorted.sort(Comparator.comparingLong(Edge::weight));
        DSU dsu = new DSU();
        List<Edge> mst = new ArrayList<>();
        long total = 0;
        for (Edge e : sorted) {
            if (dsu.union(e.u(), e.v())) {
                mst.add(e);
                total += e.weight();
            }
        }
        return new MstResult(mst, total);
    }

    /**
     * A simple disjoint-set union data structure.
     */
    public static class DSU {
        private final Map<Integer, Integer> parent = new HashMap<>();

        public int find(int x) {
            parent.putIfAbsent(x, x);
            int root = x;
            while (parent.get(root) != root) {
                root = parent.get(root);
            }
            while (parent.get(x) != x) {
                int next = parent.get(x);
                parent.put(x, root);
                x = next;
            }
            return root;
        }

        public boolean union(int a, int b) {
            int ra = find(a);
            int rb = find(b);
            if (ra == rb) {
                return false;
            }
            parent.put(ra, rb);
            return true;
        }
    }
}
