package walle.wal;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A minimal, realistic write-ahead log
 *
 * Directory layout on disk:
 *      <dir>/wal-0000000001.log
 *      <dir>/wal-0000000002.log
 *      ...
 *
 * Segments are rotated once they exceed maxSegmentBytes
 *      -- mirrors how:
 *         - pgsql (16mb WAL semgents)
 *         - Kafka (log.segment.bytes)
 *         - RocksDB
 *      avoid ever-growing single files by bounding recovery time
 *      and letting old segments be archived/deleted once a
 *      checkpoint confirms they're no longer needed.
 */

public final class Wal implements Closeable {

    private static final String SEGMENT_PREFIX = "wal-";
    private static final String SEGMENT_SUFFIX = ".log";

    private final Path dir;
    private final long maxSegmentBytes;
    private final AtomicLong nextLsn = new AtomicLong(1);

    private FileOutputStream currentOut;
    private FileChannel currentChannel;
    private long currentSegmentIndex;
    private long currentSegmentBytes;

    private Wal(Path dir, long maxSegmentBytes) {
        this.dir = dir;
        this.maxSegmentBytes = maxSegmentBytes;
    }

    /**
     * Opens/creates a WAL in the given directory, replays existing segments
     * to recompute the next LSN, opens the last segment for appending
     */
    public static Wal open(Path dir, long maxSegmentBytes) throws IOException {
        Files.createDirectories(dir);
        Wal wal = new Wal(dir, maxSegmentBytes);
        long maxLsnSeen = wal.scanExistingSegments();
        wal.nextLsn.set(maxLsnSeen + 1);
        wal.openLastSegmentForAppend();
        return wal;
    }

    private List<Path> listSegmentsSorted() throws IOException {
        List <Path> segments = new ArrayList<>();
        if (!Files.exists(dir))
            return segments;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, SEGMENT_PREFIX + "*" + SEGMENT_SUFFIX)) {
            for (Path p : stream) segments.add(p);
        }
        segments.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return segments;
    }

    private long segmentIndexOf(Path p) {
        String name = p.getFileName().toString();
        String digits = name.substring(SEGMENT_PREFIX.length(), name.length() - SEGMENT_SUFFIX.length());
        return Long.parseLong(digits);
    }
    private Path segmentPath(long index) {
        return dir.resolve(String.format("%s%01d%s", SEGMENT_PREFIX, index, SEGMENT_SUFFIX));
    }

    /** Scans every existing segment purely to find the highest LSN written so far. */
    private long scanExistingSegments() throws IOException {
        long maxLsn = 0;
        for (Path seg : listSegmentsSorted()) {
            currentSegmentIndex = segmentIndexOf(seg);
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(seg)))) {
                while (true) {
                    LogRecord rec;
                    try {
                        rec = LogRecord.decode(in);
                    } catch (LogRecord.CorruptRecordException e) {
                        // Torn tail on an old segment shouldn't normally happen,
                        // however we stop reading such a segment right where
                        // corruption starts should it occur
                        break;
                    }
                    if (rec == null) break;
                    maxLsn = Math.max(maxLsn, rec.lsn);
                }
            }
        }
        return maxLsn;
    }

    private void openLastSegmentForAppend() throws IOException {
        List<Path> segments = listSegmentsSorted();
        Path target;
        if (segments.isEmpty()) {
            currentSegmentIndex = 1;
            target = segmentPath(currentSegmentIndex);
        } else {
            target = segments.get(segments.size() - 1);
            currentSegmentIndex = segmentIndexOf(target);
        }
        currentOut = new FileOutputStream(target.toFile(), true);   // append mode
        currentChannel = currentOut.getChannel();
        currentSegmentBytes = Files.exists(target) ? Files.size(target) : 0;
    }

    private void rotateIfNeeded(int incomingBytes) throws IOException {
        if (currentSegmentBytes + incomingBytes <= maxSegmentBytes) return;
        currentOut.flush();
        currentChannel.force(true);
        currentOut.close();
        currentSegmentIndex++;
        Path next = segmentPath(currentSegmentIndex);
        currentOut = new FileOutputStream(next.toFile(), true);
        currentChannel = currentOut.getChannel();
        currentSegmentBytes = 0;
    }

    /**
     * Appends a PUT record and forces it to disk before returning
     *
     * The final fsync is the actual durability contract --
     *      once this method returns, the write survices a
     *      process crash (though not full-disk failure).
     * This is the same guarantee Postgres gives after a WAL
     *      fsync on commit & is also why WAL fsyncs are usually
     *      the throughput bottleneck of a DB.
     * IRL systems batch multiple client writes into a single
     *    fsync "group commit" instead of syncing once per
     *    record as this simple version does.
     */
    public synchronized long put(String key, String value) throws IOException {
        return append(LogRecord.RecordType.PUT, key, value);
    }

    public synchronized long delete(String key) throws IOException {
        return append(LogRecord.RecordType.DELETE, key, null);
    }

    private long append(LogRecord.RecordType type, String key, String value) throws IOException {
        long lsn = nextLsn.getAndIncrement();
        LogRecord rec = new LogRecord(lsn, type, key, value);
        byte[] bytes = rec.encode();

        rotateIfNeeded(bytes.length);

        currentOut.write(bytes);
        currentOut.flush();         // push from JVM buffers to OS
        currentChannel.force(true); // push from OS page cache to physical disk
        currentSegmentBytes += bytes.length;

        return lsn;
    }

    /**
     * Replays every segment in order & rebuilds a key -> value map
     *      in the same way a DB rebuilds its in-memory
     *      memtable/buffer-pool state after a crash.
     * Stops cleanly the moment it hits a torn/corrupt record, since
     *      anything after that point was never confirmed durable
     *      anyway.
     */
    public static Map<String, String> recover(Path dir) throws IOException {
        Map<String, String> state = new LinkedHashMap<>();
        Wal probe = new Wal(dir, Long.MAX_VALUE);
        for (Path seg : probe.listSegmentsSorted()) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(seg)))) {
                // Iterates log records; terminates recovery upon corruption detection
                while (true) {
                    LogRecord rec;
                    try {
                        rec = LogRecord.decode(in);
                    } catch (LogRecord.CorruptRecordException e) {
                        System.out.println("  [recovery] stopped at torn record in "
                                + seg.getFileName() + ": " + e.getMessage());
                        return state;
                    }
                    if (rec == null) break; // clean end of this segment
                    if (rec.type == LogRecord.RecordType.PUT) {
                        state.put(rec.key, rec.value);
                    } else {
                        state.remove(rec.key);
                    }
                }
            }
        }
        return state;
    }

    public void close() throws IOException {
        if (currentOut != null) {
            currentOut.flush();
            currentChannel.force(true);
            currentOut.close();
        }
    }
}
