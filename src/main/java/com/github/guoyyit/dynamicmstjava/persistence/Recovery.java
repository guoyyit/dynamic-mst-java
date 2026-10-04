package com.github.guoyyit.dynamicmstjava.persistence;

import com.github.guoyyit.dynamicmstjava.graph.Edge;
import com.github.guoyyit.dynamicmstjava.graph.GraphService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Recovers a {@link GraphService} from a checkpoint and WAL.
 *
 * <p>Recovery loads the latest checkpoint (if any), then replays WAL records
 * with batch ids greater than the checkpoint's batch id. Only batches with a
 * COMMIT record are replayed, so uncommitted batches are discarded.
 */
public class Recovery {

    private Recovery() {
    }

    /**
     * Recovers the graph service from the given data directory.
     */
    public static GraphService recover(Path dataDir) throws IOException {
        Path checkpointPath = dataDir.resolve("checkpoint");
        Path walPath = dataDir.resolve("wal");

        GraphService service = new GraphService();
        service.enablePersistence(dataDir);

        Checkpoint.Snapshot snapshot = Checkpoint.read(checkpointPath);
        long checkpointBatchId = -1;
        if (snapshot != null) {
            service.loadEdges(snapshot.edges());
            checkpointBatchId = snapshot.batchId();
        }

        // Replay committed WAL batches with batchId > checkpointBatchId.
        if (java.nio.file.Files.exists(walPath)) {
            try (Wal wal = Wal.open(walPath)) {
                List<Wal.CommittedBatch> committed = wal.readCommitted();
                for (Wal.CommittedBatch cb : committed) {
                    if (cb.batchId() > checkpointBatchId) {
                        service.applyBatch(cb.batch());
                        service.advanceNextBatchId(cb.batchId());
                    }
                }
            }
        }

        return service;
    }
}
