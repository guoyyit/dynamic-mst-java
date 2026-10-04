package com.github.guoyyit.dynamicmstjava.model;

import java.util.List;

/**
 * 最小生成森林查询结果：包含版本号、总权重、边数、分量数与森林边集合。
 */
public record MstResult(long version, long totalWeight, int edgeCount, int componentCount,
                        List<Edge> edges) {
}
