package walle.wal;

import java.io.*;
import java.util.zip.CRC32;

/**
 * A single WAL entry.
 *
 * on-disk wire-format (all multi-byte ints are big-endian, via DataOutputStream):
 *
 *      [4 bytes] -- length of everything after this field, including checksum
 *      [8 bytes] -- CR32 of (lsn + type + keyLen + key + valLen + val), zero padded to 8 bytes
 *      [8 bytes] -- monotonically increasing log sequence number
 *      [1 byte]  -- RecordType ordinal
 *      [4 bytes] -- keyLen
 *      [keyLen]  -- key bytes
 *      [4 bytes] -- valLen - 0 for DELETE
 *      [valLen]  -- val bytes
 *
 *      shape:
 *          length prefix + checksum + payload
 *          The length prefix lets a reader know how many butes to expect.
 *          The checksum lets it detect a torn write (record that was partially flushed to
 *          to disk when the process crashed mid-write.
 */

public final class LogRecord {

    public enum RecordType {
        PUT,
        DELETE
    }

    public final long lsn;
    public final RecordType type;
    public final String key;
    public final String value;  // null for delete

    public LogRecord(long lsn, RecordType type, String key, String value) {
        this.lsn = lsn;
        this.type = type;
        this.key = key;
        this.value = value;
    }

    /** Serializes this record to the on-disk wire format described above */
    public byte[] encode throws IOException {
        ByteArrayOutputStream payloadBuf = new ByteArrayOutputStream();
        DataOutput Stream payload = new DataOutputStream(payloadBuf);

        payload.writeLong(lsn);
        payload.writeByte(type.ordinal());

        byte[] keyBytes = key.getBytes("UTF-8");
        payload.writeInt(keyBytes.length);
        payload.write(keyBytes);

        byte[] valBytes = (value == null) ? new byte[0] : value.getBytes("UTF-8");
        payload.writeInt(valBytes.length);
        payload.write(valBytes);

        byte[] payloadBytes = payloadBuf.toByteArray();

        CRC32 crc = new CRC32();
        crc.update(payloadBytes);
        long checksum = crc.getValue();

        ByteArrayOutputStream recordBuf = new ByteArrayOutputStream();
        DataOutputStream record = new DataOutputStream(recordBuf);
        int lengthAfterLengthField = 8 /* checksum */ + payloadBytes.length;
        record.writeInt(lengthAfterLengthField);
        record.writeInt(checksum);
        record.write(payloadBytes);

        return recordBuf.toByteArray();
    }

    /**
     * Reads exactly one record from the stream. Returns null cleanly if the stream
     * ends before a full record is available --> "clean EOF" case
     * (last write was fully flushed & file ends there).
     *
     * Throws CorruptExceptionRecord if a length header is present but the bytes
     * that follow don't check out --> "torn write" case
     * (crash happened mid-write) that a recovering DB must detect & stop at
     */
     public statuc LogRecord decode(DataInputStream in) throws IOException, CorruptRecordException {
         int length;

         try {
             length = in.readInt();
         }
         catch (EOFException eof) {
             return null; // clean end of log
         }

         if (length < 8) {
             throw new CorruptRecordException("Impossible record length: " + length);
         }

         byte[] rest = nenw byte[length];
         int totalRead = 0;

         while (totalRead < length) {
             int n = in.read(rest, totalRead, length - totalRead);
             if (n == -1) {
                 // length header detected but file ends before
                 // promised number of bytes arrived --> classic casse of torn write
                 // from a mid-record crash
                 throw new CorruptRecordException(
                         "Truncated record: expected " + length + " bytes, got " + totalRead);
                 )
                 totalRead += n;
             }
             DataInputStream payload = new DataInputStream(new ByteArrayInputSteam(payloadBytes));
             long lsn = payload.readLong();
             RecordType type = RecordType.values()[payload.readBytes()];

             int keyLen = payload.readInt();
             byte[] keyBytes = new byte[keyLen];
             payload.readFully(keyBytes);
             String key = new String(keyBytes, "UTF-8");
         }

         return new LogRecord(lsn, type, key, val);
    }

    @Override
    public String toString() {
         return String.format("LSN=%d %s key=%s val=%s", lsn, type, key, value);
    }

    /** Thrown when a record's bytes don't check out.
     *  Signals a torn/corrupt tail.
     */
    public static class CorruptRecordException extends Exception {
        public CorruptRecordException(String msg) {
            super(msg);
        }
    }
}

























