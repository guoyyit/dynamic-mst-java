package com.github.guoyyit.dynamicmstjava.model;

/**
 * 一次边变更。UPSERT/INSERT/UPDATE_WEIGHT 需要 weight，DELETE 忽略 weight。
 */
public record EdgeChange(ChangeType type, int u, int v, Long weight) {

    public static EdgeChange upsert(int u, int v, long weight) {
        return new EdgeChange(ChangeType.UPSERT, u, v, weight);
    }

    public static EdgeChange insert(int u, int v, long weight) {
        return new EdgeChange(ChangeType.INSERT, u, v, weight);
    }

    public static EdgeChange updateWeight(int u, int v, long weight) {
        return new EdgeChange(ChangeType.UPDATE_WEIGHT, u, v, weight);
    }

    public static EdgeChange delete(int u, int v) {
        return new EdgeChange(ChangeType.DELETE, u, v, null);
    }

    public EdgeKey key() {
        return EdgeKey.of(u, v);
    }

    /**
     * 结构性校验：写类操作必须携带权重。
     */
    public IllegalArgumentException validate() {
        if (type != ChangeType.DELETE && weight == null) {
            return new IllegalArgumentException("weight is required for " + type);
        }
        return null;
    }
}
