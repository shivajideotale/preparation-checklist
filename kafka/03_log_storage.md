# 03 — Log Storage: Segments, Indexes & Record Format

> How Kafka physically stores messages on disk — the append-only log, segment files, sparse indexes, and the binary record format.

---

## The Append-Only Log

Kafka's storage model is built on one primitive: the **append-only log**. Every write is an append to the end of the active segment file. There are no in-place updates, no deletions of individual records, and no random writes.

```
Partition log (conceptual):
  ┌──────┬──────┬──────┬──────┬──────┬──────┬──────┐
  │ off=0│ off=1│ off=2│ off=3│ off=4│ off=5│ off=6│ ← writes go here
  └──────┴──────┴──────┴──────┴──────┴──────┴──────┘
  oldest                                            newest (active)
```

### Why append-only wins

```
Random I/O  (B-tree, hash index): ~150 IOPS → ~1.5 MB/s on HDD
Sequential I/O (append-only log): 200+ MB/s on HDD, 3+ GB/s on NVMe

Kafka's log writes at disk's maximum bandwidth by never seeking backward.
```

---

## Directory Structure

```
/var/kafka/logs/                          ← log.dirs
│
├── orders-0/                             ← topic "orders", partition 0
│   ├── 00000000000000000000.log          ← segment: offsets 0 → 499999
│   ├── 00000000000000000000.index        ← offset index for above segment
│   ├── 00000000000000000000.timeindex    ← timestamp index for above segment
│   │
│   ├── 00000000000000500000.log          ← segment: offsets 500000 → 999999
│   ├── 00000000000000500000.index
│   ├── 00000000000000500000.timeindex
│   │
│   ├── 00000000000001000000.log          ← ACTIVE segment (writes go here)
│   ├── 00000000000001000000.index        ← actively growing
│   ├── 00000000000001000000.timeindex    ← actively growing
│   │
│   └── leader-epoch-checkpoint           ← leader change history
│
└── __consumer_offsets-14/               ← internal topic partition
    └── ...
```

### The Filename Convention

The filename is a **20-digit zero-padded integer** representing the base offset — the offset of the first message in that segment:

```
00000000000000500000.log
└──────────────────┘
     base offset = 500,000
     First message in this segment has offset 500,000

Why 20 digits?
  Long.MAX_VALUE = 9,223,372,036,854,775,807 (19 digits)
  20 digits + zero-padding ensures lexicographic order = numeric order
  This enables binary search by filename: O(log N) where N = number of segments
```

**Finding which segment contains offset N:**
```
Segments: [0, 500000, 1000000, 1500000]
Looking for offset 750000:
  Binary search: largest base_offset ≤ 750000 = 500000
  → Open segment 00000000000000500000.log
```

---

## The Three Segment Files

Every segment consists of three files with the same base offset prefix:

### 1. The .log File — Raw Message Data

An append-only binary file containing a sequence of `RecordBatch` structures. Never modified after a write, only appended (while active) or read.

```
.log file layout:
  ┌─────────────────────────────────────────┐
  │ RecordBatch (base=500000, count=10)      │ ← 10 messages
  ├─────────────────────────────────────────┤
  │ RecordBatch (base=500010, count=1)       │ ← 1 message
  ├─────────────────────────────────────────┤
  │ RecordBatch (base=500011, count=50)      │ ← 50 messages compressed
  ├─────────────────────────────────────────┤
  │ RecordBatch (base=500061, count=25)      │
  └─────────────────────────────────────────┘
  File grows from top → appending at bottom
```

### 2. The .index File — Sparse Offset Index

Maps **relative offset → physical byte position** in the `.log` file. Enables O(log N) lookup for any offset.

```
Entry format (8 bytes fixed width):
  ┌──────────────────────┬──────────────────────┐
  │ relative offset      │ physical position     │
  │ (4 bytes, uint32)    │ (4 bytes, uint32)     │
  └──────────────────────┴──────────────────────┘

relative offset = actual_offset - segment_base_offset
physical position = byte offset within the .log file

Example (segment base=500000):
  [rel=0,      pos=0]       → offset 500000 starts at byte 0
  [rel=1000,   pos=45823]   → offset 501000 starts at byte 45823
  [rel=2000,   pos=91648]   → offset 502000 starts at byte 91648
  [rel=3000,   pos=137471]  → offset 503000 starts at byte 137471
```

**Sparse** — not every offset has an entry. An entry is written every `index.interval.bytes` (default 4096 bytes) of data written to the `.log` file. With 1 KB average messages: one index entry every ~4 messages.

**Memory-mapped**: The broker maps `.index` files using `FileChannel.map()` (Java `MappedByteBuffer`). Lookups are direct memory reads — no syscall overhead. The OS pages in/out as needed.

**Lookup process to find offset N in segment with base=B:**
```
1. relativeOffset = N - B
2. Binary search .index for largest entry where entry.relativeOffset ≤ relativeOffset
3. Get physical position P from that entry
4. Open .log file, seek to position P
5. Scan forward byte-by-byte until batch with offsetDelta matching target is found
   (worst case scan = index.interval.bytes = 4 KB ≈ few records)
```

### 3. The .timeindex File — Timestamp Index

Same sparse structure as `.index` but maps **timestamp → relative offset**.

```
Entry format (12 bytes):
  ┌──────────────────────────────┬──────────────────────┐
  │ timestamp (int64, 8 bytes)   │ relative offset       │
  │                              │ (int32, 4 bytes)      │
  └──────────────────────────────┴──────────────────────┘

timestamp = maxTimestamp of the RecordBatch at that point
relative offset = offsetDelta of that batch

Example:
  [ts=1706789100000, rel=0]      → first batch with ts 1706789100000 starts at rel-offset 0
  [ts=1706789104000, rel=1000]   → next indexed timestamp entry
```

Used by `consumer.offsetsForTimes()`:
```java
// Find first message from 30 minutes ago
Map<TopicPartition, OffsetAndTimestamp> result = consumer.offsetsForTimes(
    Map.of(tp, System.currentTimeMillis() - 30 * 60 * 1000)
);
```

---

## RecordBatch Format

The unit of write. One `ProduceRequest` can carry multiple batches. Each batch may contain multiple records, all compressed together.

```
RecordBatch wire format (magic=2, Kafka 0.11+):

 Offset  Size  Field
 ──────  ────  ─────
      0     8  baseOffset (int64)
              → offset of the FIRST record in this batch
      8     4  batchLength (int32)
              → byte length from partitionLeaderEpoch to end
     12     4  partitionLeaderEpoch (int32)
              → leader epoch when this batch was produced
     16     1  magic (int8) = 2
              → RecordBatch format version
     17     4  crc (int32) = CRC32C
              → checksum of everything from attributes to end
     21     2  attributes (int16)
              → bits 0-2: compression codec (0=none,1=gzip,2=snappy,3=lz4,4=zstd)
              → bit  3:   timestamp type (0=CREATE,1=LOG_APPEND)
              → bit  4:   is transactional
              → bit  5:   is control batch (transaction marker)
              → bits 6-15: reserved = 0
     23     4  lastOffsetDelta (int32)
              → (lastOffset - baseOffset); used for quick range check
     27     8  firstTimestamp (int64)
     35     8  maxTimestamp (int64)
     43     8  producerId (int64)
              → PID for idempotent/transactional producers; -1 if none
     51     2  producerEpoch (int16)
              → for zombie fencing; -1 if none
     53     4  baseSequence (int32)
              → sequence number of first record; -1 if non-idempotent
     57     4  numRecords (int32)
              → count of records in this batch
     61     ?  records (bytes, compressed if attributes.codec != none)
              → N records in delta-encoded format

Total fixed header size: 61 bytes
```

**Why batch-level compression?**

Messages in a batch share structure — JSON field names repeat, timestamps are close together, values have similar patterns. Compressing 1000 messages together exploits this redundancy:

```
Individual message compression:  {"orderId": "...", "amount": 99.99}  → 2:1 ratio
Batch of 1000 same-structure messages → 10:1 ratio (field names shared across all)
```

---

## Record Format (Inside a RecordBatch)

Individual records use **varint (variable-length integer)** encoding and **delta encoding** relative to the batch header.

```
Record format (variable length, uses zigzag varint encoding):

  length            varint  → total length of this record
  attributes        int8    → per-record attributes (reserved = 0)
  timestampDelta    varint  → this_record.timestamp - batch.firstTimestamp
                              (saves 6–7 bytes vs absolute int64)
  offsetDelta       varint  → this_record.offset - batch.baseOffset
                              (saves 6–7 bytes vs absolute int64)
  keyLength         varint  → byte length of key; -1 if null key
  key               bytes   → key bytes
  valueLength       varint  → byte length of value; -1 if null (tombstone)
  value             bytes   → value bytes
  headersCount      varint  → number of headers
  headers           []      → array of {keyLength, key, valueLength, value}
```

**Delta encoding saves space:**
```
Batch baseOffset = 1000000, firstTimestamp = 1706789100000

Record at offset 1000003:
  Absolute: offset=1000003 (8 bytes), timestamp=1706789100027 (8 bytes)
  Delta:    offsetDelta=3 (1 varint byte!), timestampDelta=27 (1 byte)
  Savings:  ~12 bytes per record
  At 1M records/sec: 12 MB/s of saved network + disk bandwidth
```

**Tombstone record (null value):**
```
valueLength = -1 (encoded as varint)
value field is absent (0 bytes)
```

Tombstones signal key deletion in compacted topics.

---

## Segment Lifecycle

### Active Segment
- Receives all new writes via `FileChannel.write()`
- Index files grow as entries are added
- NOT eligible for deletion or compaction

### Rolled Segment
A segment is **rolled** (closed and replaced) when any condition is met:
1. `.log` file size ≥ `segment.bytes` (default 1 GB)
2. Age ≥ `segment.ms` (default 7 days)
3. `.index` file pre-allocation is full (`segment.index.bytes` = 10 MB)

On roll:
```
1. Close active .log file (no more appends)
2. Truncate .index to actual used size (release pre-allocated space)
3. Truncate .timeindex to actual used size
4. Create new active .log, .index, .timeindex with base = current LEO
5. Old segment is now CLOSED — immutable, eligible for deletion/compaction
```

**Why `segment.ms` matters for low-volume topics:**
```
Topic with 1 message per day:
  Active segment grows slowly — might take years to hit segment.bytes
  retention.ms=7 days: but the single active segment is NEVER deleted
  Without segment.ms=7days: data is retained FOREVER despite retention setting
  With segment.ms=7days: segment rolls every 7 days → old segments deleted by retention
```

### Deleted Segment
```
1. Segment identified as exceeding retention (by time or size)
2. Renamed: .log → .log.deleted, .index → .index.deleted, .timeindex → .timeindex.deleted
3. Broker waits log.segment.delete.delay.ms (default 60s)
   (in-progress reads on the segment can complete safely)
4. Files physically unlinked (delete syscall)
5. Log Start Offset advances to base offset of oldest remaining segment
```

---

## The leader-epoch-checkpoint File

Tracks the history of leader changes for this partition:

```
# {epoch} {startOffset}
0 0         ← first leader wrote from offset 0
1 500000    ← new leader after first failover, started at offset 500000
2 750000    ← another leader change
```

Used during recovery: when a follower becomes leader, it uses this file to determine which messages from a prior leader to truncate (messages that may not have been fully replicated).

---

## Checkpoint Files at Log Root Level

```
recovery-point-offset-checkpoint
  → Per partition: the last offset flushed to disk
  → On crash recovery: replay only records after this point

log-start-offset-checkpoint
  → Per partition: the earliest available offset (Log Start Offset)
  → Updated when old segments are deleted

replication-offset-checkpoint
  → Per partition: the high watermark at last successful replica sync
  → Used to restore follower state after restart
```

---

## Segment File Inspection

```bash
# Dump RecordBatch headers from a .log file
kafka-dump-log.sh \
  --files /var/kafka/logs/orders-0/00000000000001000000.log \
  --print-data-log | head -30

# Output:
# Starting offset: 1000000
# baseOffset: 1000000 lastOffset: 1000009 count: 10 baseSequence: 0
#   lastSequence: 9 producerId: -1 producerEpoch: -1 partitionLeaderEpoch: 2
#   isTransactional: false isControl: false position: 0
#   CreateTime: 1706789123456 size: 1234 magic: 2 compresscodec: LZ4
#   crc: 2034567890 isvalid: true

# Check index file
kafka-dump-log.sh \
  --files /var/kafka/logs/orders-0/00000000000001000000.index

# Output:
# offset: 1001000 position: 45823
# offset: 1002000 position: 91648
```

---

## Summary

| File | Format | Purpose | Mmap? |
|---|---|---|---|
| `.log` | Binary RecordBatch sequence | Raw message data | No |
| `.index` | 8-byte fixed entries | Offset → position lookup | Yes |
| `.timeindex` | 12-byte fixed entries | Timestamp → offset lookup | Yes |
| `leader-epoch-checkpoint` | Text | Leader change history | No |

The combination of append-only writes, sparse indexes, and memory-mapped index files enables Kafka to achieve hundreds of MB/s throughput while still providing O(log N) random access to any offset.
