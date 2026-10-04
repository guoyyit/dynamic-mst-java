package com.github.guoyyit.dynamicmstjava.store;

import com.github.guoyyit.dynamicmstjava.model.Edge;
import com.github.guoyyit.dynamicmstjava.model.EdgeChange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PersistentStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void committedTransactionsSurviveReopen() throws IOException {
        Path dir = tempDir.resolve("g1");
        try (PersistentStore store = new PersistentStore(dir)) {
            PersistentStore.RecoveredState recovered = store.recover();
            assertEquals(0, recovered.version());
            store.appendCommitted(1, List.of(EdgeChange.upsert(1, 2, 5)));
            store.appendCommitted(2, List.of(
                    EdgeChange.upsert(2, 3, 7), EdgeChange.delete(1, 2)));
        }
        try (PersistentStore store = new PersistentStore(dir)) {
            PersistentStore.RecoveredState recovered = store.recover();
            assertEquals(2, recovered.version());
            assertEquals(List.of(new Edge(2, 3, 7)), recovered.edges());
        }
    }

    @Test
    void tornTrailingFrameIsDiscarded() throws IOException {
        Path dir = tempDir.resolve("g2");
        try (PersistentStore store = new PersistentStore(dir)) {
            store.recover();
            store.appendCommitted(1, List.of(EdgeChange.upsert(1, 2, 5)));
        }
        Path wal = dir.resolve("wal-0.bin");
        try (FileChannel ch = FileChannel.open(wal, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap(new byte[]{0, 1, 2, 3, 4, 5, 6}));
        }
        try (PersistentStore store = new PersistentStore(dir)) {
            PersistentStore.RecoveredState recovered = store.recover();
            assertEquals(1, recovered.version());
            assertEquals(List.of(new Edge(1, 2, 5)), recovered.edges());
            long binFiles = Files.list(dir).filter(p -> p.getFileName().toString().endsWith(".bin"))
                    .count();
            assertEquals(1, binFiles);
            Path activeWal = wal;
            int validFrameSize = 4 + 4 + 12 + 21 + 4;
            assertEquals(validFrameSize, Files.size(activeWal));
        }
    }

    @Test
    void checkpointCompactsLogAndRemainsRecoverable() throws IOException {
        Path dir = tempDir.resolve("g3");
        try (PersistentStore store = new PersistentStore(dir)) {
            store.recover();
            store.appendCommitted(1, List.of(EdgeChange.upsert(1, 2, 5)));
            store.appendCommitted(2, List.of(EdgeChange.upsert(2, 3, 9)));
            store.checkpoint(2, List.of(new Edge(1, 2, 5), new Edge(2, 3, 9)));
            store.appendCommitted(3, List.of(EdgeChange.delete(1, 2)));
        }
        try (PersistentStore store = new PersistentStore(dir)) {
            PersistentStore.RecoveredState recovered = store.recover();
            assertEquals(3, recovered.version());
            assertEquals(List.of(new Edge(2, 3, 9)), recovered.edges());
        }
        assertEquals(2, Files.list(dir).filter(p -> p.getFileName().toString().endsWith(".bin"))
                .count());
    }
}
