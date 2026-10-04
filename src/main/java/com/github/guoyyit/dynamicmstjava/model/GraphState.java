package com.github.guoyyit.dynamicmstjava.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 图的一个可变状态：完整边集 + 与全量 Kruskal 结果完全一致的最小生成森林（MSF）。
 *
 * <p>规范 MST 定义（用于消除等权边的二义性）：边按 (weight, u, v) 字典序排序后执行
 * Kruskal，得到的森林是唯一的，作为“全量重算基准”。动态维护的森林在每次变更后都
 * 等于该规范结果：
 * <ul>
 *   <li>新增边：两端不连通则入林；否则替换其树上路径中的字典序最大边（当新边更优时）。</li>
 *   <li>删除树边/树边增重：移除该边形成割，取跨越割的字典序最小边补入（可能为空）。</li>
 *   <li>树边减重、非树边增重不改变森林结构；非树边减重按新增边的路径规则处理。</li>
 * </ul>
 * 自环永不入林。所有方法仅由单写线程调用。
 */
public final class GraphState {

    private final TreeMap<EdgeKey, Long> edges = new TreeMap<>();
    private final TreeMap<EdgeKey, Long> forestEdges = new TreeMap<>();
    private final Map<Integer, TreeMap<Integer, Long>> forestAdj = new TreeMap<>();
    private final Map<Integer, TreeSet<Integer>> fullAdj = new TreeMap<>();
    private long forestWeight;

    public GraphState() {
    }

    public GraphState(GraphState other) {
        edges.putAll(other.edges);
        forestEdges.putAll(other.forestEdges);
        for (Map.Entry<Integer, TreeMap<Integer, Long>> e : other.forestAdj.entrySet()) {
            forestAdj.put(e.getKey(), new TreeMap<>(e.getValue()));
        }
        for (Map.Entry<Integer, TreeSet<Integer>> e : other.fullAdj.entrySet()) {
            fullAdj.put(e.getKey(), new TreeSet<>(e.getValue()));
        }
        forestWeight = other.forestWeight;
    }

    /** 前置条件：该边当前不存在。 */
    public void insertEdge(EdgeKey key, long weight) {
        edges.put(key, weight);
        if (!key.isSelfLoop()) {
            fullAdj.computeIfAbsent(key.u(), k -> new TreeSet<>()).add(key.v());
            fullAdj.computeIfAbsent(key.v(), k -> new TreeSet<>()).add(key.u());
        }
        if (key.isSelfLoop()) {
            return;
        }
        PathMax path = findPathMax(key.u(), key.v());
        if (path == null) {
            addForestEdge(key, weight);
        } else if (canonicalLess(key, weight, path.key, path.weight)) {
            removeForestEdge(path.key);
            addForestEdge(key, weight);
        }
    }

    /** 前置条件：该边存在且 newWeight 与旧权重不同。 */
    public void updateWeight(EdgeKey key, long newWeight) {
        long oldWeight = edges.put(key, newWeight);
        if (key.isSelfLoop()) {
            return;
        }
        boolean wasTree = forestEdges.containsKey(key);
        if (newWeight < oldWeight) {
            if (!wasTree) {
                PathMax path = findPathMax(key.u(), key.v());
                if (path != null && canonicalLess(key, newWeight, path.key, path.weight)) {
                    removeForestEdge(path.key);
                    addForestEdge(key, newWeight);
                }
            } else {
                edges.put(key, newWeight);
                forestEdges.put(key, newWeight);
                forestAdj.get(key.u()).put(key.v(), newWeight);
                forestAdj.get(key.v()).put(key.u(), newWeight);
                forestWeight += newWeight - oldWeight;
            }
        } else {
            if (wasTree) {
                reevaluateTreeEdge(key, newWeight);
            }
        }
    }

    /**
     * 树边增重后重新评估：该边以新权重保留在边集中（本身也是跨割候选），
     * 从林中移除形成割，取跨割字典序最小边加回；自己最优则林结构不变、仅权重更新。
     */
    private void reevaluateTreeEdge(EdgeKey key, long newWeight) {
        long oldWeight = forestEdges.remove(key);
        removeForestAdjacency(key);
        forestWeight -= oldWeight;
        Edge best = minCrossing(key.u(), null);
        if (best != null) {
            addForestEdge(best.key(), best.weight());
        }
    }

    public boolean removeEdge(EdgeKey key) {
        Long w = edges.remove(key);
        if (w == null) {
            return false;
        }
        if (!key.isSelfLoop()) {
            removeFullNeighbor(key.u(), key.v());
            removeFullNeighbor(key.v(), key.u());
        }
        if (forestEdges.remove(key) != null) {
            removeForestAdjacency(key);
            forestWeight -= w;
            Edge replacement = key.isSelfLoop() ? null : minCrossing(key.u());
            if (replacement != null) {
                addForestEdge(replacement.key(), replacement.weight());
            }
        }
        return true;
    }

    public Long getWeight(EdgeKey key) {
        return edges.get(key);
    }

    public boolean inForest(EdgeKey key) {
        return forestEdges.containsKey(key);
    }

    public MstResult snapshot(long version) {
        List<Edge> list = new ArrayList<>(forestEdges.size());
        forestEdges.entrySet().stream()
                .map(e -> new Edge(e.getKey().u(), e.getKey().v(), e.getValue()))
                .sorted(Comparator.comparingLong(Edge::weight)
                        .thenComparing(Edge::u).thenComparing(Edge::v))
                .forEach(list::add);
        int components = fullAdj.size() - forestEdges.size();
        return new MstResult(version, forestWeight, forestEdges.size(), components, List.copyOf(list));
    }

    public int edgeCount() {
        return edges.size();
    }

    public List<Edge> allEdges() {
        List<Edge> list = new ArrayList<>(edges.size());
        edges.forEach((k, w) -> list.add(new Edge(k.u(), k.v(), w)));
        return list;
    }

    public static GraphState fromEdges(Iterable<Edge> edges) {
        GraphState state = new GraphState();
        for (Edge edge : edges) {
            state.insertEdge(edge.key(), edge.weight());
        }
        return state;
    }

    private void addForestEdge(EdgeKey key, long weight) {
        forestEdges.put(key, weight);
        forestAdj.computeIfAbsent(key.u(), k -> new TreeMap<>()).put(key.v(), weight);
        forestAdj.computeIfAbsent(key.v(), k -> new TreeMap<>()).put(key.u(), weight);
        forestWeight += weight;
    }

    private void removeForestEdge(EdgeKey key) {
        long weight = forestEdges.remove(key);
        removeForestAdjacency(key);
        forestWeight -= weight;
    }

    private void removeForestAdjacency(EdgeKey key) {
        removeNeighbor(key.u(), key.v());
        removeNeighbor(key.v(), key.u());
    }

    private void removeNeighbor(int a, int b) {
        Map<Integer, Long> neighbors = forestAdj.get(a);
        if (neighbors != null) {
            neighbors.remove(b);
            if (neighbors.isEmpty()) {
                forestAdj.remove(a);
            }
        }
    }

    private void removeFullNeighbor(int a, int b) {
        TreeSet<Integer> neighbors = fullAdj.get(a);
        if (neighbors != null) {
            neighbors.remove(b);
            if (neighbors.isEmpty()) {
                fullAdj.remove(a);
            }
        }
    }

    /**
     * BFS 求森林中 from 到 to 路径上的字典序最大边；不连通返回 null。时间 O(V)。
     */
    private PathMax findPathMax(int from, int to) {
        if (from == to || !forestAdj.containsKey(from) || !forestAdj.containsKey(to)) {
            return null;
        }
        Map<Integer, Integer> parentNode = new HashMap<>();
        Map<Integer, EdgeKey> parentEdge = new HashMap<>();
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(from);
        parentNode.put(from, from);
        while (!queue.isEmpty()) {
            int node = queue.poll();
            if (node == to) {
                break;
            }
            for (Map.Entry<Integer, Long> next : forestAdj.get(node).entrySet()) {
                if (parentNode.putIfAbsent(next.getKey(), node) == null) {
                    parentEdge.put(next.getKey(), EdgeKey.of(node, next.getKey()));
                    queue.add(next.getKey());
                }
            }
        }
        if (!parentNode.containsKey(to)) {
            return null;
        }
        EdgeKey maxKey = null;
        long maxWeight = Long.MIN_VALUE;
        int node = to;
        while (node != from) {
            EdgeKey pe = parentEdge.get(node);
            long pw = forestEdges.get(pe);
            if (maxKey == null || canonicalLess(maxKey, maxWeight, pe, pw)) {
                maxKey = pe;
                maxWeight = pw;
            }
            node = parentNode.get(node);
        }
        return new PathMax(maxKey, maxWeight);
    }

    /**
     * 树边移除后求跨越新割的字典序最小边。
     *
     * <p>{@code forestSide}：从 side 沿当前生成森林 DFS，得到森林树中 side 一侧的顶点
     * （被移除边已不在森林邻接中，故 DFS 不会越过该割）。
     * <p>{@code component}：从 side 沿完整边集 BFS，得到完整图中 side 所在连通分量，
     * 覆盖仅出现在非树边中的顶点（如变为森林孤立点、但仍有非树边相连的叶子）。
     * 一条边是合法替换边当且仅当：两端都在完整分量内，且恰有一端在 forestSide 中。
     * 时间 O(V+E)。
     */
    private Edge minCrossing(int side) {
        Set<Integer> forestSide = new HashSet<>();
        Deque<Integer> stack = new ArrayDeque<>();
        forestSide.add(side);
        stack.push(side);
        while (!stack.isEmpty()) {
            int node = stack.pop();
            Map<Integer, Long> neighbors = forestAdj.get(node);
            if (neighbors == null) {
                continue;
            }
            for (int next : neighbors.keySet()) {
                if (forestSide.add(next)) {
                    stack.push(next);
                }
            }
        }

        Set<Integer> component = componentOf(side);

        Edge best = null;
        for (Map.Entry<EdgeKey, Long> e : edges.entrySet()) {
            EdgeKey k = e.getKey();
            boolean cross = false;
            if (!k.isSelfLoop()
                    && component.contains(k.u()) && component.contains(k.v())) {
                cross = forestSide.contains(k.u()) != forestSide.contains(k.v());
            }
            if (cross) {
                Edge candidate = new Edge(k.u(), k.v(), e.getValue());
                if (best == null || canonicalLess(candidate.key(), candidate.weight(),
                        best.key(), best.weight())) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    /** 完整边集中 start 所在连通分量（含仅出现在非树边中的顶点）。时间 O(V+E)。 */
    private Set<Integer> componentOf(int start) {
        Set<Integer> seen = new HashSet<>();
        Deque<Integer> stack = new ArrayDeque<>();
        seen.add(start);
        stack.push(start);
        while (!stack.isEmpty()) {
            int node = stack.pop();
            TreeSet<Integer> neighbors = fullAdj.get(node);
            if (neighbors == null) {
                continue;
            }
            for (int other : neighbors) {
                if (seen.add(other)) {
                    stack.push(other);
                }
            }
        }
        return seen;
    }

    private static boolean canonicalLess(EdgeKey a, long wa, EdgeKey b, long wb) {
        if (wa != wb) {
            return wa < wb;
        }
        return a.compareTo(b) < 0;
    }

    private record PathMax(EdgeKey key, long weight) {
    }
}
