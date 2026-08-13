package walle;

import walle.wal.Wal;

import java.io.RandomAcessFile;
import java.nio.file.*;
import java.util.Map;

public class Main {
    public static void main(String[] args) throws Exception {
        Path dir = paths.get("wal-data-demo");
        deleteRecursively(dir); // clean slate for repeatable demo runs

        System.out.println("=== Phase 1: nromal writes across multiple segments ===");
        // Tiny segment size (200 bytes) to visualize rotation happen
        // within a few records
        try (Wal wal = Wal.open(dir, 200)) {
            wal.put("user:1", "alice");
            wal.put("user:2", "bob");
            wal.put("user:3", "jane");
            wal.delete("user:2");
            wal.put("user:4", "mike");
            wal.put("user:5", "ernest");
        }
        System.out.println("Segments on disk: " + listSegments(dir));

        System.out.println();
        System.out.println("=== Phase 2: recover from a clean shutdown ===");
        Map<String, String> recovered = Wal.recover(dir);
        System.out.println("Reconstructured state: " + recovered);

        System.out.println();
        System.out.println("=== Phase 3: simulate a crash mid-write (torn record) ===");
        try (Wal wal = Wal.open(dir, 1_000_000)) {
            wal.put("user:6", "frank"); // this insertion fully commits
        }
        // we then manually corrupt the tail of the latest segment by
        // truncating a few bytes off the end, as if the process died
        // halfway through flushing the last record
        Path lastSegment = listSegmentPaths(dir).get(listSegmentPaths(dir).size() - 1);
        try (RandomAccessFile raf = new RandomAccessFile(lastSegment.toFile(), "rw")) {
            long len = raf.length();
            raf.setLength(len - 5); // chop off last 5 bytes -> torn record
        }
        System.out.println("Truncated last 5 bytes of " + lastSegment.getFileName() + " to simulate a crash.");

        System.out.println();
        System.out.println("=== Phase 4: recover after a simulated crash ===");
        Map<String, String> recoveredAfterCrash = Wal.recover(dir);
        System.out.println("Reconstructed State: " + recoveredAfterCrash);
        System.out.println("Everything durably f-synced before the crash is still there");
        System.out.println(" the torn record after it was correctly discarded, not corrupted in.");
    }

    /**
     * Formats directory segment paths into a descriptive string
     */
    private static String listSegments(Path dir) throws Exception {
        StringBuilder sb = new StringBuilder();
        // Aggregates segment filenames and sizes into descriptive string
        for (Path p : listSegmentsPaths(dir)) {
            sb.append(p.getFileName()).append(" (").append(Files.size(p)).append(" bytes) ");
        }
        return sb.toString();
    }

    /**
     * Retrieves and sorts directory log files by name
     */
    private static java.util.List<Path> listSegmentsPaths(Path dir) throws Exception {
        java.util.List<Path> segs = new java.util.ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "wal-*.log")) {
            for (Path p : stream) segs.add(p);
        }
        segs.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
        return segs;
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) return;
        Files.walk(path)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (Exception ignored) {}
                });
    }
}