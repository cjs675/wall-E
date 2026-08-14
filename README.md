# wall-E 

`wall-E` is a lightweight WAL implemented in Java. 

This project demonstrates how durable storage systems record changes before applying/rebuilding state. 
It models core ideas used by databases, Log-structured storage engines and distributed systems: 
    - Append-only records
    - Monotonic log sequence numbers 
    - Segment rotation
    - Checksums 
    - `fsync`
    - Crash recovery

This is _not_ a production-ready DB, but more so a simple implementation of such a structure whose purpose is 
to provide an easy way to inspect the mechanics of a WAL. 

## What is a WAL? 

A write-ahead log is an append-only sequence of records describing changes to state.

Instead of immediately relying on an in-memory data structure as the 'source of truth,' each update is first 
written to durable storage (i.e., disk). 

If the process crashes, the system can replay the log from disk and reconstruct the last known valid state.

At a high level: 

1. A client writes an operation, such as `PUT key=value` or `DELETE key`
2. The operation is appended to the WAL
3. The WAL is flushed and forced to disk 
4. Recovery replays valid log records in order 

This is the same broad recovery pattern used by many real storage systems. 


## What this project demonstrates 

`wall-E` includes a minimal WAL with support for: 

- Append-only log records 
- `PUT` and `DELETE` operations 
- Monotonically increasing log sequence numbers (LSNs) 
- Segment files that rotate after a size limit 
- CRC-based record checksums 
- Length-prefixed records 
- Recovery by replaying log files 
- Detection of torn/truncated tail records 
- Safely stopping at corruption during recovery 


## High-Level Architecture

### `WAL`

`WAL` manages the lifecycle of the write-ahead log

Responsible for: 
    - Opening/Creating a WAL directory
    - Finding existing segment files 
    - Scanning old records to continue the LSN sequence 
    - Appending new records 
    - Rotating to a new segment when the current file grows too large 
    - Forcing writes to disk for durability 
    - Recovering state by replaying valid records 

During recovery, records are read in segment order. Each valid record updates an in-memory key-value map. 
If a torn/corrupt record is found, recovery stops immediately and returns the state reconstructed from 
records that were fully written before the failure. 


### `LogRecord` represents a single durable operation in the WAL

Each record contains: 
    - A log sequence number 
    - An operation type 
    - A key
    - An optional value 
    - A checksum
    - A length prefix 

The length prefix tells recovery how many bytes a complete record should contain. The checksum verifies that
the record contents are intact. 
Together, they allow the WAL to distinguish between a clean EOF and a partially written record caused by a crash. 


## On-Disk layout

WAL files are stored as segment files inside a directory: 

```
wal-data-demo
    | 
    ---- wal-1.log
    ---- wal-2.log
    ---- wal-3.log
```

Each segment contains a sequence of encoded log records. A record is stored as: 

```[length][checksum][payload]```

where the payload contains: 

```[lsn][record type][key length][key][value length][value]```

This format makes recovery deterministic: either a record is complete and valid, or recovery stops before applying it. 


## Recovery Behavior 

Recovery rebuilds the state by scanning WAL segments from oldest to newest. 

For each valid record: 
- `PUT key value` inserts or updates the key 
- `DELETE key` removes the key

If recovery reaches the clean end of file, it moves to the next segment. If it finds a truncated or checksum-invalid 
record, it treats that record as a torn tail and stops. 

This is important because bytes after a torn record cannot be trusted. 

For example: 

`[valid record][valid record][partial record]`

Recovery applies the first two records and ignores the partial one.

But if the only record is incomplete: 

`[partial record]`

recovery applies nothing, since there is no complete durable operation to replay. 


## Running the Demo 

Use Maven to compile and run the project

If not installed, can be done so via:

[Maven Install](https://maven.apache.org/install.html)  

`mvn test`

To run the demo application from an IDE, execute the `Main` class. 

The demo writes WAL files into: 

`wal-data-demo/`

The above directory is recreated during each demo run so the output stays repeatable. 





