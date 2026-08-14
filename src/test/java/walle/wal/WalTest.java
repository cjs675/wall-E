package walle.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WalTest {

    /**
     * Validates write-ahead log recovery restores the correct final state
     */
    @Test
    void putsAndDeletesReplayToCorrectFinalState(@TempDir Path dir) throws Exception {
        try (Wal wal = Wal.open(dir, 1_000_000)) {
            wal.put("a", "1");
            wal.put("b", "2");
            wal.delete("a");
            wal.put("c", "3");
        }

        Map<String, String> state = Wal.recover(dir);

        assertEquals(Map.of("b", "2", "c", "3"), state);
    }

    /**
     * Validates monotonic LSN sequence persistence across log restarts
     */
    @Test
    void reopeningContinuesLsnSequenceInsteadOfResetting(@TempDir Path dir) throws Exception {
        long lastLsn;
        try (Wal wal = Wal.open(dir, 1_000_000)) {
            wal.put("a", "1");
            lastLsn = wal.put("b", "2");
        }

        try (Wal wal = Wal.open(dir, 1_000_000)) {
            long nextLsn = wal.put("c", "3");
            assertTrue(nextLsn > lastLsn, "LSNs must keep increasing across restarts");
        }
    }

    @Test
    void segmentRotationSplitsAcrossMultipleFiles(@TempDir Path dir) throws Exception {
        // segment limit small enough that a handful of records forces a rotation
        try (Wal wal = Wal.open(dir, 60)) {
            for (int i = 0; i < 10; i++) {
                wal.put("key" + i, "value" + i);
            }
        }

        long segmentCount;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "wal-*.log")) {
            segmentCount = 0;
            for (Path ignored : stream) segmentCount++;
        }
        assertTrue(segmentCount > 1, "expected rotation to produce more than one segment");

        // Regardless of how many segments, recovery must still see every record
        Map<String, String> state = Wal.recover(dir);
        assertEquals(10, state.size());
    }

    /**
     * Validates recovery handles truncated tail records gracefully
     */
    @Test
    void recoveryStopsCleanlyAtATornTailRecord(@TempDir Path dir) throws Exception {
        long lengthAfterSafeRecord;

        try (Wal wal = Wal.open(dir, 1_000_000)) {
            wal.put("safe", "value"); // fully committed

            List<Path> segments;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "wal-*.log")) {
                segments = new java.util.ArrayList<>();
                for (Path p : stream) segments.add(p);
            }
            Path segment = segments.get(0);
            lengthAfterSafeRecord = Files.size(segment);

            wal.put("torn", "value");   // this record will be partially truncated
        }

        List<Path> segments;
         try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "wal-*.log")) {
             segments = new java.util.ArrayList<>();
             for (Path p : stream) segments.add(p);
         }
         Path segment = segments.get(0);

         // Simulate a crash mid-write by truncating into the second record,
         // while leaving the first record fully intact
        try (RandomAccessFile raf = new RandomAccessFile(segment.toFile(), "rw")) {
            long truncatedLength = lengthAfterSafeRecord + 5;
            raf.setLength(raf.length() - 3);
        }

        // Should not throw -- recovery must treat a torn tail as "Stop Here"
        // instead of as an error
        Map<String, String> state = Wal.recover(dir);
        assertEquals(Map.of("safe", "value"), state);
    }
}