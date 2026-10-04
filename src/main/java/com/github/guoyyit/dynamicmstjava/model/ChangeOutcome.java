package com.github.guoyyit.dynamicmstjava.model;

import java.util.List;

/**
 * 一次提交（单条或批量）的结果。
 */
public record ChangeOutcome(long version, List<OpResult> results) {

    public record OpResult(ChangeType type, int u, int v, OpStatus status, String reason) {
    }
}
