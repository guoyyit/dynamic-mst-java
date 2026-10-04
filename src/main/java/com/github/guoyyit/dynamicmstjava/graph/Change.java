package com.github.guoyyit.dynamicmstjava.graph;

/**
 * A single edge-set mutation.
 */
public sealed interface Change permits Change.Insert, Change.Delete, Change.Update {

    /** Insert an edge. If the edge already exists with the same weight this is a no-op;
     *  if it exists with a different weight this is treated as an update. */
    record Insert(int u, int v, long weight) implements Change {
    }

    /** Delete an edge. Deleting a non-existent edge is a no-op. */
    record Delete(int u, int v) implements Change {
    }

    /** Update the weight of an existing edge. Updating to the same weight is a no-op;
     *  updating a non-existent edge is a no-op. */
    record Update(int u, int v, long weight) implements Change {
    }
}
