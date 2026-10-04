package com.github.guoyyit.dynamicmstjava.benchmark;

import com.github.guoyyit.dynamicmstjava.model.Edge;
import com.github.guoyyit.dynamicmstjava.model.EdgeKey;
import com.github.guoyyit.dynamicmstjava.model.GraphState;
import com.github.guoyyit.dynamicmstjava.model.MstResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规模基准测试：十万边图上动态增量维护 vs 每次变更全量 Kruskal 重算。
 * 默认跳过，使用 {@code -Dbench=true} 启用（耗时较长）。
 */
@EnabledIfSystemProperty(named = "bench", matches = "true")
class ScaleBenchmarkTest {

    private static final int EDGE_COUNT = 100_000;
    private static final int VERTEX_COUNT = 20_000;
    private static final int WARMUP = 200;
    private static final int MEASURED = 2_000;

    @Test
    void dynamicMaintenanceOutperformsFullRecomputation() {
        Random rng = new Random(20261004L);
        GraphState state = new GraphState();
        Map<EdgeKey, Long> reference = new HashMap<>();

        while (reference.size() < EDGE_COUNT) {
            int a = rng.nextInt(VERTEX_COUNT);
            int b = rng.nextInt(VERTEX_COUNT);
            EdgeKey key = EdgeKey.of(a, b);
            if (key.isSelfLoop() || reference.containsKey(key)) {
                continue;
            }
            long weight = rng.nextInt(1_000_000);
            reference.put(key, weight);
            state.insertEdge(key, weight);
        }

        MstResult initial = state.snapshot(0);
        List<Edge> refInitial = kruskal(reference);
        assertEquals(totalWeight(refInitial), initial.totalWeight(),
                () -> "initial mismatch refEdges=" + refInitial.size()
                        + " actualEdges=" + initial.edgeCount());

        List<EdgeChangeSeed> seeds = new ArrayList<>(WARMUP + MEASURED);
        for (int i = 0; i < WARMUP + MEASURED; i++) {
            List<Edge> existing = state.allEdges();
            Edge base = existing.get(rng.nextInt(existing.size()));
            seeds.add(new EdgeChangeSeed(base.key(), rng.nextInt(1_000_000)));
        }

        for (int i = 0; i < WARMUP; i++) {
            EdgeChangeSeed seed = seeds.get(i);
            state.updateWeight(seed.key(), seed.weight());
            reference.put(seed.key(), seed.weight());
        }

        Runtime runtime = Runtime.getRuntime();
        long beforeMem = usedMemory(runtime);
        long start = System.nanoTime();
        for (int i = WARMUP; i < WARMUP + MEASURED; i++) {
            EdgeChangeSeed seed = seeds.get(i);
            state.updateWeight(seed.key(), seed.weight());
        }
        long dynamicNanos = System.nanoTime() - start;
        long afterMem = usedMemory(runtime);

        long refStart = System.nanoTime();
        long checksum = 0;
        for (int i = WARMUP; i < WARMUP + 20; i++) {
            reference.put(seeds.get(i).key(), seeds.get(i).weight());
            checksum += totalWeight(kruskal(reference));
        }
        long baselineNanos20 = System.nanoTime() - refStart;
        double baselineNanosPerOp = baselineNanos20 / 20.0;
        double dynamicNanosPerOp = dynamicNanos / (double) MEASURED;

        for (int i = WARMUP; i < WARMUP + MEASURED; i++) {
            reference.put(seeds.get(i).key(), seeds.get(i).weight());
        }
        assertEquals(totalWeight(kruskal(reference)), state.snapshot(1).totalWeight());

        long retainedBytes = Math.max(0, afterMem - beforeMem);
        double speedup = baselineNanosPerOp / dynamicNanosPerOp;
        System.out.printf("%n=== 十万边规模基准 (图规模: %d 边 / %d 顶点) ===%n",
                reference.size(), VERTEX_COUNT);
        System.out.printf("动态增量更新: %.2f us/op%n", dynamicNanosPerOp / 1_000.0);
        System.out.printf("全量Kruskal : %.2f us/op%n", baselineNanosPerOp / 1_000.0);
        System.out.printf("吞吐提升    : %.1fx%n", speedup);
        System.out.printf("测量期堆增长: %.2f KB (有界, %d 次提交)%n",
                retainedBytes / 1024.0, MEASURED);
        System.out.printf("校验值      : %d%n%n", checksum);

        assertTrue(speedup >= 3.0,
                "动态维护应显著优于全量重算 (>=3x)，实际 " + speedup + "x");
    }

    private static long usedMemory(Runtime runtime) {
        runtime.gc();
        runtime.gc();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long totalWeight(List<Edge> edges) {
        long sum = 0;
        for (Edge e : edges) {
            sum += e.weight();
        }
        return sum;
    }

    private static List<Edge> kruskal(Map<EdgeKey, Long> edges) {
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

    private record EdgeChangeSeed(EdgeKey key, long weight) {
    }
}
