package com.github.guoyyit.dynamicmstjava.graph;

import java.util.List;

/**
 * An atomic batch of changes. Either all changes in the batch take effect or none do.
 *
 * @param changes the ordered list of changes in this batch
 */
public record Batch(List<Change> changes) {
    public Batch {
        changes = List.copyOf(changes);
    }
}
