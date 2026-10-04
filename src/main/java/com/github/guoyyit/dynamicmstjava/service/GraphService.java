package com.github.guoyyit.dynamicmstjava.service;

import com.github.guoyyit.dynamicmstjava.model.ChangeOutcome;
import com.github.guoyyit.dynamicmstjava.model.EdgeChange;
import com.github.guoyyit.dynamicmstjava.model.EdgeKey;
import com.github.guoyyit.dynamicmstjava.model.GraphState;
import com.github.guoyyit.dynamicmstjava.model.MstResult;
import com.github.guoyyit.dynamicmstjava.model.OpStatus;
import com.github.guoyyit.dynamicmstjava.store.PersistentStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 图数据服务：串行化提交 + 不可变快照发布，查询无锁读已提交版本。
 *
 * <p>并发模型：单把写锁串行化所有提交（提交顺序即锁获取顺序，确定可复现）；查询不加锁，
 * 只读取 {@code volatile} 发布的不可变 {@link MstResult}，因此查询要么看到提交前完整版本，
 * 要么看到提交后完整版本，绝不会读到混合多个版本的中间状态。
 *
 * <p>无效操作的确定性语义（逐条返回 APPLIED/IGNORED，IGNORED 不改变边集）：
 * <ul>
 *   <li>INSERT 已存在的边：IGNORED。</li>
 *   <li>UPDATE_WEIGHT 不存在的边：IGNORED；新旧权重相同：IGNORED。</li>
 *   <li>DELETE 不存在的边（含重复删除）：IGNORED。</li>
 *   <li>UPSERT 已存在且权重相同：IGNORED；存在但权重不同：APPLIED；不存在：APPLIED 插入。</li>
 *   <li>自环按普通边存储，但永不进入生成森林。</li>
 * </ul>
 * 每个被接受的提交（单条或批量）生成一个新版本号；批次在写锁内一次性应用后整体发布。
 */
public class GraphService implements AutoCloseable {

    private final PersistentStore store;
    private final int maxBatchSize;
    private final long checkpointInterval;
    private final ReentrantLock writeLock = new ReentrantLock();

    private GraphState state;
    private long version;
    private volatile MstResult published;
    private long commitsSinceCheckpoint;

    public GraphService(String dataDir, int maxBatchSize, long checkpointInterval) {
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive");
        }
        if (checkpointInterval <= 0) {
            throw new IllegalArgumentException("checkpointInterval must be positive");
        }
        this.maxBatchSize = maxBatchSize;
        this.checkpointInterval = checkpointInterval;
        this.store = new PersistentStore(Path.of(dataDir));
        PersistentStore.RecoveredState recovered = store.recover();
        this.state = GraphState.fromEdges(recovered.edges());
        this.version = recovered.version();
        this.published = state.snapshot(version);
        this.commitsSinceCheckpoint = 0;
    }

    public ChangeOutcome submit(EdgeChange change) {
        validate(change);
        return commit(List.of(change));
    }

    public ChangeOutcome submitBatch(List<EdgeChange> changes) {
        if (changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("batch must contain at least one change");
        }
        if (changes.size() > maxBatchSize) {
            throw new IllegalArgumentException("batch size " + changes.size()
                    + " exceeds configured maxBatchSize " + maxBatchSize);
        }
        for (EdgeChange change : changes) {
            validate(change);
        }
        return commit(changes);
    }

    public MstResult queryMst() {
        return published;
    }

    public long currentVersion() {
        return version;
    }

    @Override
    public void close() {
        store.close();
    }

    private static void validate(EdgeChange change) {
        if (change == null || change.type() == null) {
            throw new IllegalArgumentException("change type is required");
        }
        IllegalArgumentException error = change.validate();
        if (error != null) {
            throw error;
        }
    }

    private ChangeOutcome commit(List<EdgeChange> changes) {
        writeLock.lock();
        try {
            GraphState next = new GraphState(state);
            List<ChangeOutcome.OpResult> results = new ArrayList<>(changes.size());
            for (EdgeChange change : changes) {
                results.add(applyOne(next, change));
            }
            long nextVersion = version + 1;
            store.appendCommitted(nextVersion, changes);
            state = next;
            version = nextVersion;
            published = next.snapshot(nextVersion);
            commitsSinceCheckpoint++;
            if (commitsSinceCheckpoint >= checkpointInterval) {
                store.checkpoint(nextVersion, next.allEdges());
                commitsSinceCheckpoint = 0;
            }
            return new ChangeOutcome(nextVersion, List.copyOf(results));
        } finally {
            writeLock.unlock();
        }
    }

    private ChangeOutcome.OpResult applyOne(GraphState target, EdgeChange change) {
        EdgeKey key = change.key();
        Long existing = target.getWeight(key);
        long newWeight = change.weight() == null ? 0L : change.weight();
        return switch (change.type()) {
            case INSERT -> {
                if (existing != null) {
                    yield ignored(change, "edge already exists");
                }
                target.insertEdge(key, newWeight);
                yield applied(change);
            }
            case UPDATE_WEIGHT -> {
                if (existing == null) {
                    yield ignored(change, "edge does not exist");
                }
                if (existing == newWeight) {
                    yield ignored(change, "weight unchanged");
                }
                target.updateWeight(key, newWeight);
                yield applied(change);
            }
            case UPSERT -> {
                if (existing == null) {
                    target.insertEdge(key, newWeight);
                    yield applied(change);
                }
                if (existing == newWeight) {
                    yield ignored(change, "weight unchanged");
                }
                target.updateWeight(key, newWeight);
                yield applied(change);
            }
            case DELETE -> {
                if (existing == null) {
                    yield ignored(change, "edge does not exist");
                }
                target.removeEdge(key);
                yield applied(change);
            }
        };
    }

    private static ChangeOutcome.OpResult applied(EdgeChange change) {
        return new ChangeOutcome.OpResult(change.type(), change.u(), change.v(),
                OpStatus.APPLIED, null);
    }

    private static ChangeOutcome.OpResult ignored(EdgeChange change, String reason) {
        return new ChangeOutcome.OpResult(change.type(), change.u(), change.v(),
                OpStatus.IGNORED, reason);
    }
}
