package com.github.guoyyit.dynamicmstjava.web;

import com.github.guoyyit.dynamicmstjava.model.ChangeType;

/**
 * 单条变更请求体。
 */
public record ChangeRequest(ChangeType type, Integer u, Integer v, Long weight) {
}
