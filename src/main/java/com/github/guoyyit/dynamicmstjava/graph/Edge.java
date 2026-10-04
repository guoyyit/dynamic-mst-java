package com.github.guoyyit.dynamicmstjava.graph;

/**
 * A weighted undirected edge between two vertices.
 *
 * @param u      one endpoint (the smaller vertex id, canonical)
 * @param v      the other endpoint (the larger vertex id, canonical)
 * @param weight the edge weight
 */
public record Edge(int u, int v, long weight) {
    public Edge {
        if (u < 0 || v < 0) {
            throw new IllegalArgumentException("vertex ids must be non-negative");
        }
        if (u > v) {
            throw new IllegalArgumentException("edge must be canonical (u <= v)");
        }
    }

    /** Returns the canonical key for this edge. */
    public long key() {
        return EdgeKey.of(u, v);
    }
}
