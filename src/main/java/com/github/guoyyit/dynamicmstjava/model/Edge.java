package com.github.guoyyit.dynamicmstjava.model;

/**
 * 一条带权无向边（端点顺序无关）。
 */
public record Edge(int u, int v, long weight) {

    public Edge {
        if (v < u) {
            int tmp = u;
            u = v;
            v = tmp;
        }
    }

    public static Edge of(int a, int b, long weight) {
        return new Edge(a, b, weight);
    }

    public EdgeKey key() {
        return new EdgeKey(u, v);
    }
}
