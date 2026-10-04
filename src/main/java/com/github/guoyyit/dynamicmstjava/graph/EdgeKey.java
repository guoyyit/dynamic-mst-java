package com.github.guoyyit.dynamicmstjava.graph;

/**
 * Canonical key for an undirected edge. Vertex ids are packed into a single
 * {@code long} so that edge lookups are O(1) without object allocation.
 */
public final class EdgeKey {
    private EdgeKey() {
    }

    /**
     * Packs two vertex ids into a canonical edge key. The smaller id becomes
     * the high 32 bits, the larger id the low 32 bits.
     */
    public static long of(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    /** Returns the smaller endpoint of a packed key. */
    public static int u(long key) {
        return (int) (key >> 32);
    }

    /** Returns the larger endpoint of a packed key. */
    public static int v(long key) {
        return (int) key;
    }
}
