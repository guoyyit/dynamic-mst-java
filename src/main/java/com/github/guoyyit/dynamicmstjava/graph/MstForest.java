package com.github.guoyyit.dynamicmstjava.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Maintains a minimum spanning forest using adjacency lists plus binary-lifting
 * LCA for path-maximum queries.
 *
 * <p>The forest is rebuilt lazily (on the next query) after structural changes.
 * A rebuild costs O(n log n); path-max queries cost O(log n). This is far cheaper
 * than a full O(E log E) recomputation per change when structural updates are a
 * minority of the workload.
 */
public class MstForest {

    /** An edge in the MST adjacency. */
    private record MstEdge(int to, long weight, long edgeKey) {
    }

    private final Map<Integer, List<MstEdge>> adj = new HashMap<>();

    // ---- LCA binary-lifting table ----
    private int capacity = 16;
    private int[] depth = new int[capacity];
    private int[] root = new int[capacity];
    private int[][] up = new int[1][capacity];
    private long[][] maxWeight = new long[1][capacity];
    private long[][] maxEdgeKey = new long[1][capacity];
    private int kMax = 0;
    private boolean dirty = true;

    private void ensureCapacity(int vertexId) {
        if (vertexId >= capacity) {
            int newCap = capacity;
            while (newCap <= vertexId) {
                newCap *= 2;
            }
            depth = grow(depth, newCap);
            root = grow(root, newCap);
            up = grow(up, newCap);
            maxWeight = grow(maxWeight, newCap);
            maxEdgeKey = grow(maxEdgeKey, newCap);
            capacity = newCap;
        }
    }

    private static int[] grow(int[] arr, int newLen) {
        int[] out = new int[newLen];
        System.arraycopy(arr, 0, out, 0, arr.length);
        return out;
    }

    private static int[][] grow(int[][] arr, int newLen) {
        int[][] out = new int[arr.length][newLen];
        for (int i = 0; i < arr.length; i++) {
            System.arraycopy(arr[i], 0, out[i], 0, arr[i].length);
        }
        return out;
    }

    private static long[] grow(long[] arr, int newLen) {
        long[] out = new long[newLen];
        System.arraycopy(arr, 0, out, 0, arr.length);
        return out;
    }

    private static long[][] grow(long[][] arr, int newLen) {
        long[][] out = new long[arr.length][newLen];
        for (int i = 0; i < arr.length; i++) {
            System.arraycopy(arr[i], 0, out[i], 0, arr[i].length);
        }
        return out;
    }

    private void ensureVertex(int v) {
        ensureCapacity(v);
        adj.computeIfAbsent(v, k -> new ArrayList<>());
    }

    /**
     * Adds an edge to the forest.
     */
    public void link(int u, int v, long weight, long edgeKey) {
        ensureVertex(u);
        ensureVertex(v);
        adj.get(u).add(new MstEdge(v, weight, edgeKey));
        adj.get(v).add(new MstEdge(u, weight, edgeKey));
        dirty = true;
    }

    /**
     * Removes an edge from the forest. The edge must exist.
     */
    public void cut(int u, int v) {
        List<MstEdge> lu = adj.get(u);
        if (lu != null) {
            lu.removeIf(e -> e.to() == v);
        }
        List<MstEdge> lv = adj.get(v);
        if (lv != null) {
            lv.removeIf(e -> e.to() == u);
        }
        dirty = true;
    }

    /**
     * Returns true if u and v are in the same tree.
     */
    public boolean connected(int u, int v) {
        if (!adj.containsKey(u) || !adj.containsKey(v)) {
            return false;
        }
        rebuildIfNeeded();
        return root[u] == root[v];
    }

    /**
     * Returns the maximum weight of any edge on the path between u and v.
     * Returns {@code Long.MIN_VALUE} if u and v are not connected.
     */
    public long pathMaxWeight(int u, int v) {
        long[] result = pathMax(u, v);
        return result[0];
    }

    /**
     * Returns the edge key of the maximum-weight edge on the path between u and v.
     * Returns {@code -1} if u and v are not connected.
     */
    public long pathMaxEdgeKey(int u, int v) {
        long[] result = pathMax(u, v);
        return result[1];
    }

    /**
     * Returns [maxWeight, maxEdgeKey] on the path between u and v.
     */
    private long[] pathMax(int u, int v) {
        if (!adj.containsKey(u) || !adj.containsKey(v)) {
            return new long[] {Long.MIN_VALUE, -1};
        }
        rebuildIfNeeded();
        if (root[u] != root[v]) {
            return new long[] {Long.MIN_VALUE, -1};
        }
        long maxW = Long.MIN_VALUE;
        long maxKey = -1;
        if (depth[u] < depth[v]) {
            int tmp = u;
            u = v;
            v = tmp;
        }
        int diff = depth[u] - depth[v];
        for (int k = 0; k <= kMax; k++) {
            if ((diff & (1 << k)) != 0) {
                if (maxWeight[k][u] > maxW) {
                    maxW = maxWeight[k][u];
                    maxKey = maxEdgeKey[k][u];
                }
                u = up[k][u];
            }
        }
        if (u == v) {
            return new long[] {maxW, maxKey};
        }
        for (int k = kMax; k >= 0; k--) {
            if (up[k][u] != up[k][v]) {
                if (maxWeight[k][u] > maxW) {
                    maxW = maxWeight[k][u];
                    maxKey = maxEdgeKey[k][u];
                }
                if (maxWeight[k][v] > maxW) {
                    maxW = maxWeight[k][v];
                    maxKey = maxEdgeKey[k][v];
                }
                u = up[k][u];
                v = up[k][v];
            }
        }
        // Now u and v are children of the LCA.
        if (maxWeight[0][u] > maxW) {
            maxW = maxWeight[0][u];
            maxKey = maxEdgeKey[0][u];
        }
        if (maxWeight[0][v] > maxW) {
            maxW = maxWeight[0][v];
            maxKey = maxEdgeKey[0][v];
        }
        return new long[] {maxW, maxKey};
    }

    /**
     * Returns all edges currently in the forest.
     */
    public List<Edge> edges() {
        List<Edge> result = new ArrayList<>();
        for (Map.Entry<Integer, List<MstEdge>> entry : adj.entrySet()) {
            int u = entry.getKey();
            for (MstEdge e : entry.getValue()) {
                if (u < e.to()) {
                    result = addEdge(result, u, e);
                }
            }
        }
        return result;
    }

    private List<Edge> addEdge(List<Edge> list, int u, MstEdge e) {
        if (list == null) {
            list = new ArrayList<>();
        }
        list.add(new Edge(u, e.to(), e.weight()));
        return list;
    }

    /**
     * Returns the number of edges in the forest.
     */
    public int edgeCount() {
        int count = 0;
        for (List<MstEdge> list : adj.values()) {
            count += list.size();
        }
        return count / 2;
    }

    /**
     * Returns true if the edge (u, v) is currently in the forest.
     */
    public boolean containsEdge(int u, int v) {
        List<MstEdge> list = adj.get(u);
        if (list == null) {
            return false;
        }
        for (MstEdge e : list) {
            if (e.to() == v) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the neighbors of the given vertex in the forest.
     */
    public List<Integer> neighbors(int u) {
        List<MstEdge> list = adj.get(u);
        if (list == null) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>(list.size());
        for (MstEdge e : list) {
            result.add(e.to());
        }
        return result;
    }

    /**
     * Removes all edges from the forest.
     */
    public void clear() {
        adj.clear();
        dirty = true;
    }

    private void rebuildIfNeeded() {
        if (dirty) {
            rebuild();
            dirty = false;
        }
    }

    private void rebuild() {
        // Determine the number of vertices and the max depth.
        int n = adj.size();
        if (n == 0) {
            kMax = 0;
            return;
        }
        // kMax = ceil(log2(n))
        kMax = 0;
        while ((1 << kMax) < n) {
            kMax++;
        }
        // Ensure arrays are large enough for kMax (grow rows).
        if (up.length <= kMax) {
            int newRows = kMax + 1;
            int[][] newUp = new int[newRows][capacity];
            long[][] newMaxWeight = new long[newRows][capacity];
            long[][] newMaxEdgeKey = new long[newRows][capacity];
            for (int k = 0; k < up.length; k++) {
                System.arraycopy(up[k], 0, newUp[k], 0, capacity);
                System.arraycopy(maxWeight[k], 0, newMaxWeight[k], 0, capacity);
                System.arraycopy(maxEdgeKey[k], 0, newMaxEdgeKey[k], 0, capacity);
            }
            up = newUp;
            maxWeight = newMaxWeight;
            maxEdgeKey = newMaxEdgeKey;
        }
        // Reset arrays.
        Arrays.fill(depth, 0, capacity, 0);
        Arrays.fill(root, 0, capacity, 0);
        for (int k = 0; k <= kMax; k++) {
            Arrays.fill(up[k], 0, capacity, 0);
            Arrays.fill(maxWeight[k], 0, capacity, Long.MIN_VALUE);
            Arrays.fill(maxEdgeKey[k], 0, capacity, -1);
        }
        // DFS from each vertex to compute depth, root, up[0], maxWeight[0], maxEdgeKey[0].
        boolean[] visited = new boolean[capacity];
        for (int start : adj.keySet()) {
            if (visited[start]) {
                continue;
            }
            // BFS to assign root and depth.
            Queue<Integer> queue = new LinkedList<>();
            queue.add(start);
            visited[start] = true;
            root[start] = start;
            depth[start] = 0;
            up[0][start] = start;
            while (!queue.isEmpty()) {
                int u = queue.poll();
                for (MstEdge e : adj.get(u)) {
                    int v = e.to();
                    if (!visited[v]) {
                        visited[v] = true;
                        root[v] = start;
                        depth[v] = depth[u] + 1;
                        up[0][v] = u;
                        maxWeight[0][v] = e.weight();
                        maxEdgeKey[0][v] = e.edgeKey();
                        queue.add(v);
                    }
                }
            }
        }
        // Compute up[k] and maxWeight[k] for k >= 1.
        for (int k = 1; k <= kMax; k++) {
            for (int v = 0; v < capacity; v++) {
                if (!visited[v]) {
                    continue;
                }
                up[k][v] = up[k - 1][up[k - 1][v]];
                long w1 = maxWeight[k - 1][v];
                long w2 = maxWeight[k - 1][up[k - 1][v]];
                if (w1 > w2) {
                    maxWeight[k][v] = w1;
                    maxEdgeKey[k][v] = maxEdgeKey[k - 1][v];
                } else {
                    maxWeight[k][v] = w2;
                    maxEdgeKey[k][v] = maxEdgeKey[k - 1][up[k - 1][v]];
                }
            }
        }
    }
}
