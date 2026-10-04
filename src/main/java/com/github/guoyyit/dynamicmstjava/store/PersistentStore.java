package com.github.guoyyit.dynamicmstjava.store;

import com.github.guoyyit.dynamicmstjava.model.Edge;
import com.github.guoyyit.dynamicmstjava.model.EdgeChange;
import com.github.guoyyit.dynamicmstjava.model.ChangeType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 崩溃恢复存储：WAL + 快照，保证已提交变更落盘、未提交变更丢失。
 *
 * <p>磁盘布局（dataDir 下）：
 * <ul>
 *   <li>{@code snapshot-<version>.bin}：版本 {@code version} 的完整边集快照（原子临时文件 + rename 发布）。</li>
 *   <li>{@code wal-<startVersion>.bin}：顺序追加日志，包含 version &gt; startVersion 的全部已提交事务。</li>
 * </ul>
 *
 * <p>每条提交事务记录为一帧：[magic:int][recordLen:int][payload][crc32:int]。
 * append 后执行 {@code force(true)} 保证崩溃持久性；恢复时按帧顺序扫描，遇到不完整或
 * CRC 错误的尾帧即停止（截断掉未落盘完整的部分提交），因此未提交事务不会生效。
 */
public final class PersistentStore implements AutoCloseable {

    private static final Pattern SNAPSHOT_FILE = Pattern.compile("snapshot-(\\d+)\\.bin");
    private static final Pattern WAL_FILE = Pattern.compile("wal-(\\d+)\\.bin");
    private static final int MAGIC = 0x4D_53_57_4C; // "MSWL"

    private final Path dataDir;
    private FileChannel walChannel;
    private long walStartVersion;

    public record RecoveredState(List<Edge> edges, long version, long lastCheckpointVersion) {
    }

    public PersistentStore(Path dataDir) {
        this.dataDir = dataDir;
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public RecoveredState recover() {
        try {
            long snapshotVersion = -1L;
            Path snapshot = null;
            try (Stream<Path> files = Files.list(dataDir)) {
                for (Path p : (Iterable<Path>) files::iterator) {
                    Matcher m = SNAPSHOT_FILE.matcher(p.getFileName().toString());
                    if (m.matches()) {
                        long v = Long.parseLong(m.group(1));
                        if (v > snapshotVersion) {
                            snapshotVersion = v;
                            snapshot = p;
                        }
                    }
                }
            }

            List<Edge> snapshotEdges;
            if (snapshot != null) {
                snapshotEdges = readSnapshot(snapshot);
            } else {
                snapshotEdges = List.of();
            }

            List<Path> walFiles = new ArrayList<>();
            try (Stream<Path> files = Files.list(dataDir)) {
                for (Path p : (Iterable<Path>) files::iterator) {
                    Matcher m = WAL_FILE.matcher(p.getFileName().toString());
                    if (m.matches() && Long.parseLong(m.group(1)) >= snapshotVersion) {
                        walFiles.add(p);
                    }
                }
            }
            walFiles.sort((a, b) -> Long.compare(startOf(a), startOf(b)));

            TreeMap<com.github.guoyyit.dynamicmstjava.model.EdgeKey, Long> live = new TreeMap<>();
            for (Edge e : snapshotEdges) {
                live.put(e.key(), e.weight());
            }
            long version = Math.max(snapshotVersion, 0L);
            for (Path wal : walFiles) {
                try (FileChannel ch = FileChannel.open(wal, StandardOpenOption.READ,
                        StandardOpenOption.WRITE)) {
                    List<LogRecord> records = readAllRecords(ch);
                    for (LogRecord record : records) {
                        if (record.version() <= snapshotVersion) {
                            continue;
                        }
                        applyTo(live, record);
                        version = Math.max(version, record.version());
                    }
                }
            }

            List<Edge> result = new ArrayList<>(live.size());
            live.forEach((k, w) -> result.add(new Edge(k.u(), k.v(), w)));

            Path activeWal;
            if (version > 0) {
                long base = walFiles.isEmpty() ? 0L : startOf(walFiles.get(walFiles.size() - 1));
                activeWal = dataDir.resolve("wal-" + base + ".bin");
                for (Path wal : walFiles) {
                    if (!wal.equals(activeWal)) {
                        Files.deleteIfExists(wal);
                    }
                }
                this.walStartVersion = base;
            } else {
                for (Path wal : walFiles) {
                    Files.deleteIfExists(wal);
                }
                activeWal = dataDir.resolve("wal-0.bin");
                this.walStartVersion = 0L;
            }
            this.walChannel = FileChannel.open(activeWal,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            return new RecoveredState(List.copyOf(result), version, Math.max(snapshotVersion, 0L));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void appendCommitted(long version, List<EdgeChange> changes) {
        try {
            byte[] payload = encodeRecord(version, changes);
            ByteBuffer frame = ByteBuffer.allocate(payload.length + 12);
            frame.putInt(MAGIC);
            frame.putInt(payload.length);
            frame.put(payload);
            frame.putInt(crc32(payload));
            frame.flip();
            while (frame.hasRemaining()) {
                walChannel.write(frame);
            }
            walChannel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void checkpoint(long version, List<Edge> allEdges) {
        try {
            Path temp = dataDir.resolve("snapshot-" + version + ".tmp");
            Path target = dataDir.resolve("snapshot-" + version + ".bin");
            try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer header = ByteBuffer.allocate(4);
                header.putInt(MAGIC);
                header.flip();
                ch.write(header);
                ByteBuffer meta = ByteBuffer.allocate(8);
                meta.putInt(1); // snapshot format version
                meta.putInt(allEdges.size());
                meta.flip();
                ch.write(meta);
                ByteBuffer body = ByteBuffer.allocate(16 * allEdges.size());
                for (Edge e : allEdges) {
                    body.putInt(e.u());
                    body.putInt(e.v());
                    body.putLong(e.weight());
                }
                body.flip();
                while (body.hasRemaining()) {
                    ch.write(body);
                }
                ch.force(true);
            }
            Files.move(temp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            walChannel.close();
            Path oldWal = dataDir.resolve("wal-" + walStartVersion + ".bin");
            walStartVersion = version;
            Path newWal = dataDir.resolve("wal-" + version + ".bin");
            walChannel = FileChannel.open(newWal, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            walChannel.force(true);
            Files.deleteIfExists(oldWal);

            pruneOlderSnapshots(version);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (walChannel != null) {
                walChannel.force(true);
                walChannel.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long startOf(Path wal) {
        Matcher m = WAL_FILE.matcher(wal.getFileName().toString());
        return m.matches() ? Long.parseLong(m.group(1)) : Long.MAX_VALUE;
    }

    private static void applyTo(TreeMap<com.github.guoyyit.dynamicmstjava.model.EdgeKey, Long> live,
                                LogRecord record) {
        for (EdgeChange change : record.changes()) {
            com.github.guoyyit.dynamicmstjava.model.EdgeKey key = change.key();
            switch (change.type()) {
                case DELETE -> live.remove(key);
                case INSERT, UPSERT, UPDATE_WEIGHT -> live.put(key, change.weight());
            }
        }
    }

    private static byte[] encodeRecord(long version, List<EdgeChange> changes) {
        int size = 12 + changes.size() * 21;
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.putLong(version);
        buffer.putInt(changes.size());
        for (EdgeChange c : changes) {
            buffer.putInt(c.type().ordinal());
            buffer.putInt(c.u());
            buffer.putInt(c.v());
            byte present = (byte) (c.weight() != null ? 1 : 0);
            buffer.put(present);
            buffer.putLong(present == 1 ? c.weight() : 0L);
        }
        return buffer.array();
    }

    private static List<LogRecord> readAllRecords(FileChannel channel) throws IOException {
        long total = channel.size();
        ByteBuffer all = ByteBuffer.allocate((int) total);
        while (all.hasRemaining()) {
            int read = channel.read(all);
            if (read < 0) {
                break;
            }
        }
        all.flip();
        List<LogRecord> records = new ArrayList<>();
        while (all.remaining() >= 12) {
            int start = all.position();
            int magic = all.getInt();
            int len = all.getInt();
            if (magic != MAGIC || len < 0 || all.remaining() < len + 4) {
                all.position(start);
                break;
            }
            byte[] payload = new byte[len];
            all.get(payload);
            int crc = all.getInt();
            if (crc != crc32(payload)) {
                all.position(start);
                break;
            }
            records.add(decodeRecord(payload));
        }
        long validLength = all.position();
        if (validLength < total) {
            channel.truncate(validLength);
        }
        return records;
    }

    private static LogRecord decodeRecord(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        long version = buffer.getLong();
        int count = buffer.getInt();
        List<EdgeChange> changes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ChangeType type = ChangeType.values()[buffer.getInt()];
            int u = buffer.getInt();
            int v = buffer.getInt();
            boolean present = buffer.get() == 1;
            long weight = buffer.getLong();
            changes.add(new EdgeChange(type, u, v, present ? weight : null));
        }
        return new LogRecord(version, changes);
    }

    private static int crc32(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return (int) crc.getValue();
    }

    private static List<Edge> readSnapshot(Path snapshot) throws IOException {
        try (FileChannel ch = FileChannel.open(snapshot, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(12);
            while (header.hasRemaining()) {
                ch.read(header);
            }
            header.flip();
            int magic = header.getInt();
            int format = header.getInt();
            int count = header.getInt();
            if (magic != MAGIC || format != 1) {
                throw new IOException("invalid snapshot file: " + snapshot);
            }
            ByteBuffer body = ByteBuffer.allocate(16 * count);
            while (body.hasRemaining()) {
                ch.read(body);
            }
            body.flip();
            List<Edge> edges = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                edges.add(new Edge(body.getInt(), body.getInt(), body.getLong()));
            }
            return edges;
        }
    }

    private void pruneOlderSnapshots(long currentVersion) throws IOException {
        try (Stream<Path> files = Files.list(dataDir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Matcher m = SNAPSHOT_FILE.matcher(p.getFileName().toString());
                if (m.matches() && Long.parseLong(m.group(1)) < currentVersion) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

}
