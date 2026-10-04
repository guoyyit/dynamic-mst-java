package com.github.guoyyit.dynamicmstjava.graph;

import java.util.List;

/**
 * The result of a minimum spanning forest query.
 *
 * @param edges      the edges in the minimum spanning forest, in arbitrary order
 * @param totalWeight the total weight of all edges in the forest
 */
public record MstResult(List<Edge> edges, long totalWeight) {
    public MstResult {
        edges = List.copyOf(edges);
    }
}
