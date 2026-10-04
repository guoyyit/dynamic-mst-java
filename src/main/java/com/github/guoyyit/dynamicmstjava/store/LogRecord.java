package com.github.guoyyit.dynamicmstjava.store;

import com.github.guoyyit.dynamicmstjava.model.EdgeChange;

import java.util.List;

/**
 * WAL 中一条已提交事务记录（单条或批量）。
 */
public record LogRecord(long version, List<EdgeChange> changes) {
}
