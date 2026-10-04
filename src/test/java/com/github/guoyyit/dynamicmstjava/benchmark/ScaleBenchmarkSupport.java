package com.github.guoyyit.dynamicmstjava.benchmark;

import com.github.guoyyit.dynamicmstjava.model.Edge;
import com.github.guoyyit.dynamicmstjava.model.EdgeKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 全量重算基准工具（测试共用）。 */
final class ScaleBenchmarkSupport {

    private ScaleBenchmarkSupport() {
    }

    static List<Edge> kruskal(Map<EdgeKey, Long> edges) {
        List<Edge> sorted = new ArrayList<>(edges.size());
        edges.forEach((k, w) -> {
            if (!k.isSelfLoop()) {
                sorted.add(new Edge(k.u(), k.v(), w));
            }
        });
        sorted.sort(Comparator.comparingLong(Edge::weight)
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

    static long totalWeight(List<Edge> edges) {
        long sum = 0;
        for (Edge e : edges) {
            sum += e.weight();
        }
        return sum;
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
        return root;
    }
}
