package com.github.guoyyit.dynamicmstjava.model;

/**
 * 无向边的规范化键：端点按大小归一化，保证 {u,v} 与 {v,u} 是同一条边。
 */
public record EdgeKey(int u, int v) implements Comparable<EdgeKey> {

    public static EdgeKey of(int a, int b) {
        return a <= b ? new EdgeKey(a, b) : new EdgeKey(b, a);
    }

    public boolean isSelfLoop() {
        return u == v;
    }

    @Override
    public int compareTo(EdgeKey other) {
        int c = Integer.compare(u, other.u);
        return c != 0 ? c : Integer.compare(v, other.v);
    }
}
