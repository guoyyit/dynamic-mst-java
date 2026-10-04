package com.github.guoyyit.dynamicmstjava.persistence;

import com.github.guoyyit.dynamicmstjava.graph.Batch;
import com.github.guoyyit.dynamicmstjava.graph.Change;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Write-ahead log for committed batches.
 *
 * <p>Each batch is written as a sequence of records: a BEGIN record, one record
 * per change, and a COMMIT record. The COMMIT record is fsynced before the batch
 * is applied to the in-memory state, guaranteeing that every committed batch is
 * durable.
 *
 * <p>Record format (one record per line):
 * <ul>
 *   <li>{@code B <batchId>} — BEGIN</li>
 *   <li>{@code I <batchId> <u> <v> <weight>} — INSERT</li>
 *   <li>{@code D <batchId> <u> <v>} — DELETE</li>
 *   <li>{@code U <batchId> <u> <v> <weight>} — UPDATE</li>
 *   <li>{@code C <batchId>} — COMMIT</li>
 * </ul>
 */
public class Wal implements AutoCloseable {

    private final Path path;
    private FileOutputStream fos;
    private BufferedWriter writer;

    private Wal(Path path) throws IOException {
        this.path = path;
        this.fos = new FileOutputStream(path.toFile(), true);
        this.writer = new BufferedWriter(new OutputStreamWriter(fos, java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Opens (or creates) a WAL file at the given path.
     */
    public static Wal open(Path path) throws IOException {
        return new Wal(path);
    }

    /**
     * Appends a committed batch to the WAL and fsyncs.
     */
    public synchronized void append(long batchId, Batch batch) throws IOException {
        writer.write("B " + batchId);
        writer.newLine();
        for (Change change : batch.changes()) {
            if (change instanceof Change.Insert ins) {
                writer.write("I " + batchId + " " + ins.u() + " " + ins.v() + " " + ins.weight());
                writer.newLine();
            } else if (change instanceof Change.Delete del) {
                writer.write("D " + batchId + " " + del.u() + " " + del.v());
                writer.newLine();
            } else if (change instanceof Change.Update upd) {
                writer.write("U " + batchId + " " + upd.u() + " " + upd.v() + " " + upd.weight());
                writer.newLine();
            }
        }
        writer.write("C " + batchId);
        writer.newLine();
        writer.flush();
        // fsync to guarantee durability.
        fsync();
    }

    private void fsync() throws IOException {
        fos.getFD().sync();
    }

    /**
     * Reads all committed batches from the WAL in order.
     */
    public List<CommittedBatch> readCommitted() throws IOException {
        Map<Long, List<Change>> pending = new HashMap<>();
        List<Long> committedOrder = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(" ");
                if (parts.length < 2) {
                    continue;
                }
                String type = parts[0];
                long batchId = Long.parseLong(parts[1]);
                switch (type) {
                    case "B" -> pending.put(batchId, new ArrayList<>());
                    case "I" -> {
                        int u = Integer.parseInt(parts[2]);
                        int v = Integer.parseInt(parts[3]);
                        long w = Long.parseLong(parts[4]);
                        pending.get(batchId).add(new Change.Insert(u, v, w));
                    }
                    case "D" -> {
                        int u = Integer.parseInt(parts[2]);
                        int v = Integer.parseInt(parts[3]);
                        pending.get(batchId).add(new Change.Delete(u, v));
                    }
                    case "U" -> {
                        int u = Integer.parseInt(parts[2]);
                        int v = Integer.parseInt(parts[3]);
                        long w = Long.parseLong(parts[4]);
                        pending.get(batchId).add(new Change.Update(u, v, w));
                    }
                    case "C" -> {
                        if (pending.containsKey(batchId)) {
                            committedOrder.add(batchId);
                        }
                    }
                }
            }
        }
        List<CommittedBatch> result = new ArrayList<>();
        for (long batchId : committedOrder) {
            List<Change> changes = pending.get(batchId);
            if (changes != null) {
                result.add(new CommittedBatch(batchId, new Batch(changes)));
            }
        }
        return result;
    }

    /**
     * Truncates the WAL, removing all records with batchId <= the given value.
     */
    public void truncateUpTo(long batchId) throws IOException {
        // Read all records, keep only those with batchId > batchId.
        List<String> kept = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(" ");
                if (parts.length < 2) {
                    continue;
                }
                long id = Long.parseLong(parts[1]);
                if (id > batchId) {
                    kept.add(line);
                }
            }
        }
        // Close the writer and fos, rewrite the file, reopen.
        writer.close();
        fos.close();
        Files.writeString(path, String.join("\n", kept) + (kept.isEmpty() ? "" : "\n"),
                StandardOpenOption.TRUNCATE_EXISTING);
        fos = new FileOutputStream(path.toFile(), true);
        writer = new BufferedWriter(new OutputStreamWriter(fos, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws IOException {
        if (writer != null) {
            writer.close();
        }
        if (fos != null) {
            fos.close();
        }
    }

    /**
     * A batch that was durably committed to the WAL.
     */
    public record CommittedBatch(long batchId, Batch batch) {
    }
}
