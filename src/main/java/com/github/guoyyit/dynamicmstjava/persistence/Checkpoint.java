package com.github.guoyyit.dynamicmstjava.persistence;

import com.github.guoyyit.dynamicmstjava.graph.Edge;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A checkpoint captures the full edge set at a given batch id.
 *
 * <p>Checkpoints are written atomically: the edge set is written to a temporary
 * file, fsynced, and then atomically renamed over the previous checkpoint.
 *
 * <p>File format:
 * <ul>
 *   <li>First line: {@code CP <batchId>}</li>
 *   <li>Subsequent lines: {@code <u> <v> <weight>} (one per edge)</li>
 * </ul>
 */
public class Checkpoint {

    private Checkpoint() {
    }

    /**
     * Writes a checkpoint atomically.
     */
    public static void write(Path path, long batchId, List<Edge> edges) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp.toFile());
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {
            writer.write("CP " + batchId);
            writer.newLine();
            for (Edge e : edges) {
                writer.write(e.u() + " " + e.v() + " " + e.weight());
                writer.newLine();
            }
            writer.flush();
            fos.getFD().sync();
        }
        Files.move(tmp, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Reads a checkpoint. Returns null if no checkpoint exists.
     */
    public static Snapshot read(Path path) throws IOException {
        if (!Files.exists(path)) {
            return null;
        }
        long batchId = -1;
        List<Edge> edges = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line = reader.readLine();
            if (line == null || !line.startsWith("CP ")) {
                return null;
            }
            batchId = Long.parseLong(line.substring(3));
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(" ");
                if (parts.length < 3) {
                    continue;
                }
                int u = Integer.parseInt(parts[0]);
                int v = Integer.parseInt(parts[1]);
                long w = Long.parseLong(parts[2]);
                edges.add(new Edge(Math.min(u, v), Math.max(u, v), w));
            }
        }
        return new Snapshot(batchId, edges);
    }

    /**
     * A checkpoint snapshot.
     */
    public record Snapshot(long batchId, List<Edge> edges) {
    }
}
