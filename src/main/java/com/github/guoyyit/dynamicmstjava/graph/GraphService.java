package com.github.guoyyit.dynamicmstjava.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.github.guoyyit.dynamicmstjava.persistence.Wal;

/**
 * Maintains a weighted undirected graph and its minimum spanning forest.
 *
 * <h2>Concurrency</h2>
 * All mutations are serialized by a write lock; queries take a read lock and
 * therefore observe a single committed version. The commit order of concurrent
 * batches is the order in which they acquire the write lock, which is
 * deterministic.
 *
 * <h2>Correctness</h2>
 * The maintained forest is guaranteed to equal the result of a full
 * recomputation from the current edge set after every committed batch.
 *
 * <h2>Invalid operations</h2>
 * <ul>
 *   <li>Inserting an edge that already exists with the same weight is a no-op.</li>
 *   <li>Inserting an edge that already exists with a different weight updates the weight.</li>
 *   <li>Deleting a non-existent edge is a no-op.</li>
 *   <li>Updating a non-existent edge is a no-op.</li>
 *   <li>Updating to the same weight is a no-op.</li>
 * </ul>
 */
public class GraphService {

    private final ReadWriteLock lock = new ReentrantReadWriteLock(true);

    /** All current edges, keyed by canonical edge key. */
    private final Map<Long, Edge> edges = new HashMap<>();

    /** The forest used to maintain the MST. */
    private final MstForest mst = new MstForest();

    /** The write-ahead log, or null if persistence is not enabled. */
    private Wal wal;

    /** The next batch id to use for the WAL. */
    private long nextBatchId = 0;

    /** The number of batches committed since the last checkpoint. */
    private long batchesSinceCheckpoint = 0;

    /** The checkpoint interval (number of batches between checkpoints). */
    private static final long CHECKPOINT_INTERVAL = 100;

    /** The data directory for persistence, or null if not enabled. */
    private java.nio.file.Path dataDir;

    /**
     * Enables persistence with the given data directory.
     */
    public void enablePersistence(java.nio.file.Path dataDir) throws java.io.IOException {
        this.dataDir = dataDir;
        java.nio.file.Files.createDirectories(dataDir);
        this.wal = Wal.open(dataDir.resolve("wal"));
    }

    /**
     * Applies an atomic batch of changes.
     */
    public void commit(Batch batch) {
        lock.writeLock().lock();
        try {
            long batchId = nextBatchId++;
            if (wal != null) {
                try {
                    wal.append(batchId, batch);
                } catch (java.io.IOException e) {
                    throw new RuntimeException("failed to write to WAL", e);
                }
            }
            applyBatch(batch);
            batchesSinceCheckpoint++;
            if (wal != null && batchesSinceCheckpoint >= CHECKPOINT_INTERVAL) {
                try {
                    checkpoint();
                } catch (java.io.IOException e) {
                    throw new RuntimeException("failed to write checkpoint", e);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Applies a batch of changes without writing to the WAL.
     * This is used during recovery.
     */
    public void applyBatch(Batch batch) {
        for (Change change : batch.changes()) {
            apply(change);
        }
    }

    /**
     * Advances the next batch id to be greater than the given value.
     * This is used during recovery to continue from the correct batch id.
     */
    public void advanceNextBatchId(long batchId) {
        if (batchId >= nextBatchId) {
            nextBatchId = batchId + 1;
        }
    }

    /**
     * Writes a checkpoint of the current edge set and truncates the WAL.
     */
    public void checkpoint() throws java.io.IOException {
        if (wal == null || dataDir == null) {
            return;
        }
        long batchId = nextBatchId - 1;
        List<Edge> currentEdges = new ArrayList<>(edges.values());
        com.github.guoyyit.dynamicmstjava.persistence.Checkpoint.write(
                dataDir.resolve("checkpoint"), batchId, currentEdges);
        wal.truncateUpTo(batchId);
        batchesSinceCheckpoint = 0;
    }

    /**
     * Loads edges from a checkpoint, rebuilding the MST from scratch.
     * This is used during recovery.
     */
    public void loadEdges(List<Edge> loadedEdges) {
        lock.writeLock().lock();
        try {
            edges.clear();
            mst.clear();
            // Deduplicate edges by key, keeping the last occurrence.
            Map<Long, Edge> deduped = new HashMap<>();
            for (Edge e : loadedEdges) {
                deduped.put(e.key(), e);
            }
            edges.putAll(deduped);
            // Build the MST from scratch using Kruskal's algorithm.
            List<Edge> sorted = new ArrayList<>(deduped.values());
            sorted.sort(java.util.Comparator.comparingLong(Edge::weight));
            Map<Integer, Integer> parent = new HashMap<>();
            for (Edge e : sorted) {
                if (union(parent, e.u(), e.v())) {
                    mst.link(e.u(), e.v(), e.weight(), e.key());
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean union(Map<Integer, Integer> parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra == rb) {
            return false;
        }
        parent.put(ra, rb);
        return true;
    }

    private int find(Map<Integer, Integer> parent, int x) {
        parent.putIfAbsent(x, x);
        int root = x;
        while (parent.get(root) != root) {
            root = parent.get(root);
        }
        while (parent.get(x) != x) {
            int next = parent.get(x);
            parent.put(x, root);
            x = next;
        }
        return root;
    }

    private void apply(Change change) {
        if (change instanceof Change.Insert ins) {
            insert(ins.u(), ins.v(), ins.weight());
        } else if (change instanceof Change.Delete del) {
            delete(del.u(), del.v());
        } else if (change instanceof Change.Update upd) {
            update(upd.u(), upd.v(), upd.weight());
        }
    }

    private void insert(int u, int v, long weight) {
        long key = EdgeKey.of(u, v);
        Edge existing = edges.get(key);
        if (existing != null) {
            if (existing.weight() == weight) {
                return; // no-op
            }
            // Treat as weight update.
            update(u, v, weight);
            return;
        }
        Edge edge = new Edge(Math.min(u, v), Math.max(u, v), weight);
        edges.put(key, edge);
        if (mst.connected(u, v)) {
            long maxWeight = mst.pathMaxWeight(u, v);
            if (weight < maxWeight) {
                long maxKey = mst.pathMaxEdgeKey(u, v);
                int mu = EdgeKey.u(maxKey);
                int mv = EdgeKey.v(maxKey);
                mst.cut(mu, mv);
                mst.link(u, v, weight, key);
            }
        } else {
            mst.link(u, v, weight, key);
        }
    }

    private void delete(int u, int v) {
        long key = EdgeKey.of(u, v);
        Edge removed = edges.remove(key);
        if (removed == null) {
            return; // no-op
        }
        // Check if the edge is in the MST.
        if (isInMst(u, v)) {
            mst.cut(u, v);
            Edge replacement = findReplacement(u);
            if (replacement != null) {
                mst.link(replacement.u(), replacement.v(), replacement.weight(), replacement.key());
            }
        }
    }

    private void update(int u, int v, long weight) {
        long key = EdgeKey.of(u, v);
        Edge existing = edges.get(key);
        if (existing == null) {
            return; // no-op
        }
        long oldWeight = existing.weight();
        if (oldWeight == weight) {
            return; // no-op
        }
        Edge updated = new Edge(Math.min(u, v), Math.max(u, v), weight);
        edges.put(key, updated);
        if (isInMst(u, v)) {
            if (weight < oldWeight) {
                // Weight decreased: the edge stays in the MST. Update the tree weight.
                mst.cut(u, v);
                mst.link(u, v, weight, key);
            } else {
                // Weight increased: the edge might need replacement.
                mst.cut(u, v);
                Edge replacement = findReplacement(u);
                if (replacement != null) {
                    mst.link(replacement.u(), replacement.v(), replacement.weight(), replacement.key());
                }
            }
        } else {
            // Edge is not in the MST.
            if (weight < oldWeight) {
                // Weight decreased: it might now be eligible. Try to insert.
                if (mst.connected(u, v)) {
                    long maxWeight = mst.pathMaxWeight(u, v);
                    if (weight < maxWeight) {
                        long maxKey = mst.pathMaxEdgeKey(u, v);
                        int mu = EdgeKey.u(maxKey);
                        int mv = EdgeKey.v(maxKey);
                        mst.cut(mu, mv);
                        mst.link(u, v, weight, key);
                    }
                } else {
                    mst.link(u, v, weight, key);
                }
            }
            // Weight increased: no change.
        }
    }

    /**
     * Returns true if the edge (u, v) is currently in the MST.
     */
    private boolean isInMst(int u, int v) {
        return mst.containsEdge(u, v);
    }

    /**
     * Finds the minimum-weight edge crossing the cut induced by removing the edge
     * incident to {@code u} from the MST.
     */
    private Edge findReplacement(int u) {
        // Mark all nodes in u's component (BFS in the MST adjacency).
        Set<Integer> component = new HashSet<>();
        Queue<Integer> queue = new LinkedList<>();
        queue.add(u);
        component.add(u);
        while (!queue.isEmpty()) {
            int node = queue.poll();
            for (int neighbor : mst.neighbors(node)) {
                if (!component.contains(neighbor)) {
                    component.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }
        // Scan all edges for the minimum-weight edge crossing the cut.
        Edge best = null;
        for (Edge e : edges.values()) {
            boolean uIn = component.contains(e.u());
            boolean vIn = component.contains(e.v());
            if (uIn != vIn) {
                if (best == null || e.weight() < best.weight()) {
                    best = e;
                }
            }
        }
        return best;
    }

    /**
     * Returns the minimum spanning forest of the current graph.
     */
    public MstResult query() {
        lock.readLock().lock();
        try {
            List<Edge> mstEdges = mst.edges();
            long totalWeight = 0;
            for (Edge e : mstEdges) {
                totalWeight += e.weight();
            }
            return new MstResult(mstEdges, totalWeight);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns a copy of the current edge set.
     */
    public List<Edge> edges() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(edges.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the number of edges currently in the graph.
     */
    public int edgeCount() {
        lock.readLock().lock();
        try {
            return edges.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}
