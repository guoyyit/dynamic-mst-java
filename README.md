# Dynamic MST Java

A local graph data service that maintains a weighted undirected graph edge set and
provides edge-set mutation and minimum spanning tree (MST) query capabilities.

## Features

- **Edge mutations**: insert, delete, and weight update.
- **Atomic batch commits**: all changes in a batch take effect or none do.
- **Concurrent reads and writes**: queries observe a single committed version; commits
  are serialized with a deterministic order.
- **Crash recovery**: write-ahead logging (WAL) with periodic checkpoints ensures that
  committed changes survive crashes and uncommitted changes are discarded.
- **Minimum spanning forest**: for disconnected graphs, the query returns the minimum
  spanning forest covering each connected component.
- **Deterministic invalid-operation semantics**: duplicate inserts, deletes of
  non-existent edges, and no-op weight updates are handled deterministically without
  changing query results.

## Consistency Semantics

### Snapshot Isolation

Each query observes the graph as of a single committed version. A query never sees a
mixed state from multiple versions or intermediate states of a batch. This is enforced
by a read-write lock: queries take a read lock, commits take the write lock.

### Deterministic Commit Order

Concurrent commits are serialized by the write lock. The commit order is the order in
which batches acquire the write lock, which is deterministic (FIFO).

### Correctness Guarantee

The maintained minimum spanning forest is guaranteed to equal the result of a full
recomputation (Kruskal's algorithm) from the current edge set after every committed
batch. This is verified by randomized tests that compare the maintained forest against
a full recomputation after every change.

## Invalid-Operation Behavior

| Operation | Condition | Behavior |
|-----------|-----------|----------|
| Insert | Edge already exists with same weight | No-op |
| Insert | Edge already exists with different weight | Weight update |
| Insert | Edge does not exist | Add edge |
| Delete | Edge exists | Remove edge |
| Delete | Edge does not exist | No-op |
| Update | Edge does not exist | No-op |
| Update | Same weight | No-op |
| Update | Different weight | Update weight |

All invalid operations are deterministic and do not change the query result.

## Minimum Spanning Forest for Disconnected Graphs

When the graph is disconnected, the MST query returns the minimum spanning forest
covering each connected component. The total weight is the sum of the weights of all
edges in the forest. This semantics is stable and consistent with a full recomputation.

## Architecture

### Core Components

- `GraphService`: maintains the edge set and MST, handles commits and queries.
- `MstForest`: maintains the MST adjacency and supports path-maximum queries via
  binary-lifting LCA.
- `MstUtils`: utility for full recomputation (Kruskal's algorithm).

### Persistence

- `Wal`: write-ahead log for committed batches. Each batch is written as BEGIN,
  change records, and COMMIT. The COMMIT record is fsynced before the batch is applied.
- `Checkpoint`: atomic snapshot of the full edge set at a given batch id.
- `Recovery`: recovers a `GraphService` from a checkpoint and WAL.

### Concurrency

- A `ReentrantReadWriteLock` serializes commits and allows concurrent queries.
- Queries observe a single committed version.

### Performance

The MST is maintained incrementally:

- **Insert**: O(log n) for path-maximum query + O(1) for tree update.
- **Delete (non-MST edge)**: O(1).
- **Delete (MST edge)**: O(E) for replacement-edge scan.
- **Weight update**: O(log n) or O(E) depending on whether the edge is in the MST.

Structural changes (edge added/removed from the MST) trigger a lazy rebuild of the
LCA table (O(n log n)). This is far cheaper than a full O(E log E) recomputation per
change when structural updates are a minority of the workload.

### Memory

- Edge set: O(E).
- LCA table: O(n log n).
- WAL: bounded by checkpoint interval.

## Benchmark

The `MstBenchmark` test compares the dynamic MST throughput against full recomputation.
On a graph with 100K edges, the dynamic MST is significantly faster than full
recomputation per change.

Run the benchmark:

```bash
mvn test -Dtest=MstBenchmark
```

## API

### Commit a batch

```bash
POST /api/graph/batch
Content-Type: application/json

{
  "changes": [
    {"kind": "insert", "u": 0, "v": 1, "weight": 10},
    {"kind": "delete", "u": 2, "v": 3},
    {"kind": "update", "u": 0, "v": 1, "weight": 20}
  ]
}
```

### Query the MST

```bash
GET /api/graph/mst
```

### Query all edges

```bash
GET /api/graph/edges
```

## Configuration

- `graph.data-dir`: directory for persistence files (default: `./data`).

## Building and Running

```bash
# Build
mvn compile

# Run tests
mvn test

# Run the application
mvn spring-boot:run
```
