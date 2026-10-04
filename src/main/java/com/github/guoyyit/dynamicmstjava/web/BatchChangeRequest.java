package com.github.guoyyit.dynamicmstjava.web;

import java.util.List;

/**
 * 批量变更请求体。
 */
public record BatchChangeRequest(List<ChangeRequest> changes) {
}
