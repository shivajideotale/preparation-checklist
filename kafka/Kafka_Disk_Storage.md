# Kafka — How Data Is Stored on Disk

> A complete deep dive into Kafka's log-structured storage engine: directory layout, segment files, record format, index structures, write path, read path, zero-copy I/O, page cache strategy, log compaction, and retention. Written for senior Java backend engineers.

---

## Table of Contents

1. [Design Philosophy — Why a Log?](#1-design-philosophy--why-a-log)
2. [Directory and File Layout](#2-directory-and-file-layout)
3. [Segment Files — The Three-File Trio](#3-segment-files--the-three-file-trio)
4. [Record and Batch Format](#4-record-and-batch-format)
5. [The OS Page Cache Strategy](#5-the-os-page-cache-strategy)
6. [Write Path — Producer to Disk](#6-write-path--producer-to-disk)
7. [Segment Rolling](#7-segment-rolling)
8. [Read Path — Disk to Consumer](#8-read-path--disk-to-consumer)
9. [Zero-Copy Transfer — sendfile()](#9-zero-copy-transfer--sendfile)
10. [Log Compaction Internals](#10-log-compaction-internals)
11. [Retention and Deletion](#11-retention-and-deletion)
12. [Replication and Disk Coordination](#12-replication-and-disk-coordination)
13. [Storage Configuration Reference](#13-storage-configuration-reference)
14. [Disk Sizing and Capacity Planning](#14-disk-sizing-and-capacity-planning)
15. [Monitoring Disk Health](#15-monitoring-disk-health)
16. [Quick Reference](#16-quick-reference)

---

## 1. Design Philosophy — Why a Log?

Kafka's entire storage model is built on one fundamental data structure: the **append-only log**. Understanding why this choice was made explains everything else about how Kafka stores data.

### The problem with traditional message brokers

Traditional brokers (RabbitMQ, ActiveMQ) store messages in random-access data structures — typically B-trees or hash indexes backed by persistent storage. This gives them `O(log N)` read/write performance but creates several problems at scale:

- **Random I/O**: Both reads and writes involve random seeks on disk, which is extremely slow on spinning disks (~100 IOPS) and inefficient even on SSDs
- **Memory pressure**: The working set must fit in memory for acceptable performance
- **Deletion complexity**: Tracking which messages have been consumed and are safe to delete requires complex bookkeeping

### Why an append-only log wins

The append-only log solves all of these:

```
All writes go to the END of the file → Pure sequential I/O
Sequential disk I/O = 200+ MB/s vs random I/O = ~2 MB/s
```

On a spinning disk, sequential write is **100x faster** than random write. On SSDs, the gap is smaller but still significant due to write amplification avoidance. Kafka essentially uses disk like a tape drive — always writing forward, never backward.

**Reads are also sequential**: Consumers read in order from their last offset. This is the most cache-friendly access pattern possible — the OS page cache prefetches upcoming data automatically.

**Deletion is trivial**: Delete entire segment files (the oldest ones) instead of finding and removing individual messages. Segment deletion is a single filesystem unlink operation.

### The key insight

```
Kafka's performance secret:
  - Writes are always sequential (append to log end)
  - Reads are always sequential (consumer reads forward from offset)
  - Deletion is whole-file (delete entire old segments)
  - All of the above favour the OS page cache and disk hardware
```

A well-tuned Kafka broker can sustain **hundreds of MB/s** of throughput on commodity hardware — performance that would be impossible with a random-access storage model.

---

## 2. Directory and File Layout

### Root log directory

Set via `log.dirs` in `server.properties`. Can be multiple directories (comma-separated) to stripe data across multiple disks:

```properties
# Single disk
log.dirs=/var/kafka/logs

# Multiple disks — partitions distributed across all
log.dirs=/mnt/disk1/kafka,/mnt/disk2/kafka,/mnt/disk3/kafka
```

When multiple directories are configured, Kafka uses the directory with the fewest partitions for each new partition assignment (round-robin by partition count, not by bytes). This distributes I/O load but does NOT automatically rebalance if one disk fills up faster than others.

### Partition directories

Each partition gets its own subdirectory:

```
/var/kafka/logs/
├── orders-0/           ← topic "orders", partition 0
├── orders-1/           ← topic "orders", partition 1
├── orders-2/           ← topic "orders", partition 2
├── payments-0/
├── payments-1/
├── __consumer_offsets-0/   ← internal topic
├── __consumer_offsets-1/
...
├── __transaction_state-0/  ← internal topic
...
└── replication-offset-checkpoint
```

**Directory naming**: `{topic_name}-{partition_number}`

Special files in the log root:
- `replication-offset-checkpoint`: Maps each partition to the last replicated offset (used for recovery)
- `log-start-offset-checkpoint`: Tracks the Log Start Offset (earliest available) for each partition
- `recovery-point-offset-checkpoint`: The last offset that was flushed to disk (for crash recovery)

### Inside a partition directory

```
orders-0/
├── 00000000000000000000.log         ← segment: base offset 0
├── 00000000000000000000.index       ← offset index for segment starting at 0
├── 00000000000000000000.timeindex   ← timestamp index for segment starting at 0
│
├── 00000000000000500000.log         ← segment: base offset 500000
├── 00000000000000500000.index
├── 00000000000000500000.timeindex
│
├── 00000000000001000000.log         ← ACTIVE segment: current writes
├── 00000000000001000000.index       ← actively growing index
├── 00000000000001000000.timeindex   ← actively growing time index
│
└── leader-epoch-checkpoint          ← leader epoch history for this partition
```

### The filename encoding

Every segment's three files share a **base offset** as their filename — a 20-digit zero-padded integer representing the offset of the first message in that segment:

```
00000000000000500000.log
└──────────────────┘
     base offset = 500000

This segment contains messages starting at offset 500000.
The next segment starts at whichever offset this segment ended at.
```

Why 20 digits? `Long.MAX_VALUE = 9223372036854775807` (19 digits). 20 digits with zero-padding ensures lexicographic sort = numeric sort, which is critical for binary search by filename.

**Finding which segment contains offset N:**
```
Segment files: 0, 500000, 1000000, 1500000 (sorted ascending)
Looking for offset 750000:

Binary search: largest base_offset ≤ 750000 = 500000
→ Look in segment starting at 500000
```

This binary search is `O(log S)` where S is the number of segments, typically single digits.

---

## 3. Segment Files — The Three-File Trio

### The .log file — raw message data

The core data file. An append-only sequence of **RecordBatch** structures written one after another. Never modified after writing — only appended to (while active) or read from.

```
orders-0/00000000000000500000.log:

[RecordBatch: base=500000, count=10]  ← 10 messages, offsets 500000-500009
[RecordBatch: base=500010, count=1]   ← 1 message, offset 500010
[RecordBatch: base=500011, count=50]  ← 50 messages, compressed together
[RecordBatch: base=500061, count=25]
...
```

The file grows until `segment.bytes` (default 1 GB) is reached, at which point a new segment is created.

### The .index file — sparse offset index

A fixed-size file of 8-byte entries mapping **relative offset → physical byte position** in the .log file:

```
Entry structure (8 bytes):
  4 bytes: relative offset (uint32) = actual_offset - base_offset
  4 bytes: physical position (uint32) in the .log file

Example (segment base offset = 500000):
  [relative=0,     position=0]         → offset 500000 is at byte 0
  [relative=1000,  position=45823]     → offset 501000 is at byte 45823
  [relative=2000,  position=91648]     → offset 502000 is at byte 91648
  ...
```

**Sparse** means not every offset has an index entry. An entry is written every `index.interval.bytes` (default 4096 bytes = 4 KB) of data written to the .log file. With 1 KB average message size, an index entry is written roughly every 4 messages.

**Memory-mapped**: The broker maps the .index file directly into virtual memory using `FileChannel.map()`. Reading an index entry requires no filesystem syscall — it's a direct memory read. The OS handles paging the index data in/out of physical RAM.

**Size**: The index file is pre-allocated to `segment.index.bytes` (default 10 MB) and the used portion grows as entries are added. On segment roll, the index is truncated to its actual size.

### The .timeindex file — timestamp index

Same structure as .index but maps **timestamp → relative offset** instead of offset → position:

```
Entry structure (12 bytes):
  8 bytes: timestamp (int64) = max timestamp of a batch
  4 bytes: relative offset (uint32) of the batch with that timestamp

Example:
  [timestamp=1706789100000, relativeOffset=0]
  [timestamp=1706789104000, relativeOffset=1000]
  ...
```

Used by `consumer.offsetsForTimes()` — finding the first message at or after a given timestamp. This powers "replay from timestamp" operations:

```java
// Find offsets for "30 minutes ago"
Map<TopicPartition, Long> timestamps = Map.of(
    new TopicPartition("orders", 0),
    System.currentTimeMillis() - 30 * 60 * 1000
);
Map<TopicPartition, OffsetAndTimestamp> result = consumer.offsetsForTimes(timestamps);
// result contains the first offset with timestamp >= 30 minutes ago
```

### The leader-epoch-checkpoint file

Tracks the history of leader changes for this partition:

```
# epoch  startOffset
0        0          ← first leader wrote from offset 0
1        500000     ← new leader started at offset 500000 (after leader change)
2        750000     ← another leader change at offset 750000
```

Used during replica recovery to determine which messages the new leader may need to truncate (messages written by a former leader that weren't replicated before it failed).

---

## 4. Record and Batch Format

Kafka uses a layered encoding: individual **Records** are grouped into **RecordBatches**, which are written as units to the .log file.

### RecordBatch — the write unit

```
RecordBatch (variable size):
┌─────────────────────────────────────────────────────────────────┐
│ baseOffset          int64    (8 bytes) — offset of first record │
│ batchLength         int32    (4 bytes) — total bytes from here  │
│ partitionLeaderEpoch int32   (4 bytes) — leader epoch           │
│ magic               int8     (1 byte)  — format version = 2     │
│ crc                 int32    (4 bytes) — CRC32C checksum        │
│ attributes          int16    (2 bytes) — flags:                 │
│   bits 0-2: compression codec (0=none, 1=gzip, 2=snappy,       │
│             3=lz4, 4=zstd)                                      │
│   bit 3:    timestamp type (0=create, 1=log append)             │
│   bit 4:    is transactional                                     │
│   bit 5:    is control batch (txn marker)                       │
│   bits 6-15: reserved                                           │
│ lastOffsetDelta     int32    (4 bytes) — last_offset - baseOffset│
│ firstTimestamp      int64    (8 bytes)                           │
│ maxTimestamp        int64    (8 bytes)                           │
│ producerId          int64    (8 bytes) — for idempotent/txn     │
│ producerEpoch       int16    (2 bytes) — for zombie fencing     │
│ baseSequence        int32    (4 bytes) — sequence start for dedup│
│ numRecords          int32    (4 bytes) — count of records inside │
│ records[]           bytes    (variable) — compressed or raw     │
└─────────────────────────────────────────────────────────────────┘

Total fixed overhead per batch: 61 bytes
```

**Why batch-level compression?** Messages in the same batch tend to have similar structure (same JSON field names, similar timestamps, related content). Compressing the entire batch achieves far better ratios than compressing individual messages. A batch of 100 JSON payment events might compress 5:1, while individual compression of each event might achieve only 2:1.

### Record — individual message (inside the batch)

Records inside a batch use **varint encoding** (variable-length integers) and **delta encoding** to minimize space:

```
Record (variable size, uses varint encoding):
┌──────────────────────────────────────────────────────────────┐
│ length          varint   — total length of this record       │
│ attributes      int8     — per-record flags (reserved = 0)   │
│ timestampDelta  varint   — (record.ts - batch.firstTimestamp) │
│ offsetDelta     varint   — (record.offset - batch.baseOffset) │
│ keyLength       varint   — byte length of key, -1 if null    │
│ key             bytes    — key bytes (0 bytes if null)       │
│ valueLength     varint   — byte length of value, -1 if null  │
│ value           bytes    — value bytes (0 bytes if null)     │
│ headersCount    varint   — number of headers                 │
│ headers[]                — key-value string pairs            │
│   headerKeyLength varint                                      │
│   headerKey       bytes                                       │
│   headerValueLength varint                                    │
│   headerValue     bytes                                       │
└──────────────────────────────────────────────────────────────┘
```

**Delta encoding**: Instead of storing the full timestamp and offset for every record (8 + 8 = 16 bytes each), Kafka stores the difference from the batch's base values. A delta of "3 milliseconds" encodes as 1-2 varint bytes instead of 8 bytes for a full timestamp.

**Null values (tombstones)**: A record with `valueLength = -1` and no value bytes is a **tombstone** — signals deletion for log-compacted topics. The key identifies what is being deleted.

### CRC check on read

When the broker reads a batch (e.g., to serve a consumer), it validates the CRC32C checksum. Bit corruption on disk or in network transmission is detected immediately. The consumer receives an error rather than silently corrupt data.

---

## 5. The OS Page Cache Strategy

Kafka's most important design decision — one that sets it apart from almost every other storage system — is **deliberate reliance on the OS page cache instead of managing its own in-process cache**.

### What is the page cache?

The OS page cache is a region of RAM that the kernel uses to cache the contents of files. When you read from a file, the kernel caches the data in RAM so subsequent reads are served from memory without disk I/O. When you write to a file, the kernel writes to the page cache and marks the page "dirty" — flushing to disk happens asynchronously.

```
Physical Memory (32 GB machine):
┌──────────────────────────────────────────────────────────┐
│ JVM heap (Kafka broker): 6 GB                            │
│   - NetworkReceive buffers                               │
│   - Request handler threads                              │
│   - Metadata (topic/partition info, ISR sets)            │
│   - Index files (memory-mapped, counted separately)      │
│                                                          │
│ OS Page Cache: 24 GB  ← Kafka relies on THIS for caching │
│   - Cached .log file contents                            │
│   - Recently written messages (for producer-consumer     │
│     locality: recent messages served without disk I/O)   │
│   - Cached .index files (partially — rest is mmap'd)    │
└──────────────────────────────────────────────────────────┘
```

### Why page cache beats JVM heap for caching

**1. No GC overhead**: A 24 GB JVM heap incurs significant GC pressure. Pause times grow with heap size. The OS manages the page cache without any GC — it's not managed memory. A 24 GB page cache causes zero GC pauses.

**2. Survives broker restart**: When the Kafka JVM restarts, the JVM heap is wiped clean. The OS page cache is NOT cleared — it persists across JVM restarts. A restarted Kafka broker immediately benefits from warm page cache. For consumers replaying recent data, no disk I/O is needed even right after broker restart.

**3. Avoids double-buffering**: If Kafka stored data in both a JVM heap cache AND the OS page cache (which it would, since any file access goes through the page cache), every byte would consume memory twice. By not caching in JVM heap, Kafka avoids this waste.

**4. OS cache is global**: The page cache serves all processes on the machine. Index files, log files, and even consumer fetch responses all benefit from the same shared cache.

**5. Optimal prefetching**: The OS kernel's readahead algorithm is highly tuned for sequential access patterns. When Kafka reads sequentially from a log file, the kernel automatically prefetches upcoming pages — the next megabytes arrive in RAM before they're needed.

### Producer-consumer locality

The most important page cache benefit is **producer-consumer locality**:

```
t=0  Producer writes message A to active segment
     → OS writes message A to page cache (RAM)
     → page cache page is now "dirty" (in RAM, not yet on disk)

t=1  Consumer fetches message A
     → Consumer's fetchOffset points to message A's position
     → Broker calls transferTo() for that position
     → OS checks page cache: message A is already in RAM!
     → Zero disk I/O — data served entirely from RAM

t=?  OS async flush
     → Page cache dirty pages eventually written to disk
     → Consumer already received the data — irrelevant timing
```

For the common case where consumers are nearly caught up (lag < retention), **messages are served directly from RAM with no disk I/O at all**. The disk write is purely for durability — it doesn't block the consumer.

### Page cache sizing recommendation

Leave as much RAM as possible for the page cache. Kafka's JVM heap only needs to hold metadata, not data:

```properties
# Kafka JVM heap — keep small, leave rest for page cache
KAFKA_HEAP_OPTS="-Xmx6g -Xms6g"

# On a 32 GB machine:
# JVM heap: 6 GB
# OS + system: 2 GB
# Page cache: 24 GB ← 75% of RAM for Kafka's actual "cache"
```

The recommendation is that the page cache should be large enough to hold all the data consumed from a topic in the last `replica.lag.time.max.ms` (default 30 seconds). If consumers are consistently behind by more than the page cache can hold, they will fall off the cache and cause disk reads.

---

## 6. Write Path — Producer to Disk

Understanding the complete write path from producer to durable storage.

### Step 1: Network receive

```java
// Internally, the broker's SocketServer receives the ProduceRequest
// on a dedicated network thread (num.network.threads)
// The request is placed in a queue for I/O threads

// Network thread → request queue → I/O thread (num.io.threads)
```

The ProduceRequest arrives as raw bytes on the socket. The broker's `RequestChannel` hands it to one of `num.io.threads` (default 8) for processing.

### Step 2: Validation

The I/O thread validates the incoming batch:

1. **CRC check**: Validates the batch's CRC32C checksum — detects network corruption
2. **Magic version**: Confirms the format version is supported
3. **Size check**: Verifies batch size ≤ `max.message.bytes`
4. **Authorization**: Checks ACLs if security is enabled
5. **Idempotent sequence check**: If `enable.idempotence=true`, validates PID + epoch + sequence number
6. **Transaction state**: If transactional, updates `__transaction_state`

### Step 3: Write to page cache

```java
// Simplified internal representation
log.append(records);

// Internally this calls:
// FileChannel.write(ByteBuffer records) on the active .log file
// → OS writes to page cache (in-memory)
// → Returns immediately (non-blocking for the broker thread)
// → The broker does NOT wait for disk flush
```

The critical insight: `FileChannel.write()` writes to the **OS page cache**, not directly to disk. The OS marks those pages as "dirty" and will flush them to disk asynchronously. The broker I/O thread returns immediately after the page cache write — it does not block on disk I/O.

### Step 4: Update in-memory state

After the page cache write:

```
Log End Offset (LEO) updated:
  partition.log.logEndOffset += batch.numRecords

Index updated (if threshold crossed):
  if (bytesSinceLastIndexEntry >= log.index.interval.bytes) {
      offsetIndex.append(relativeOffset, physicalPosition)
      timeIndex.append(maxTimestamp, relativeOffset)
  }

In-memory fetch position updated:
  Follower replicas can now fetch this new batch
```

### Step 5: Follower replication

Each follower runs a `ReplicaFetcherThread` that continuously issues `FetchRequest` to the leader:

```
Follower ReplicaFetcherThread:
  loop:
    fetchOffset = partition.log.logEndOffset  ← fetch from where follower left off
    response = leader.fetch(fetchOffset, maxBytes=1MB)
    partition.log.append(response.records)     ← write to follower's own .log file
    partition.log.updateLEO()
    report fetchOffset to leader (via next FetchRequest's fetchOffset field)
```

The leader tracks each follower's fetch position. When a follower's fetch position advances past the newly written batch, the leader knows the follower has replicated it.

### Step 6: High Watermark advance

```
Leader tracks ISR members:
  ISR = {broker-1 (leader), broker-2 (follower), broker-3 (follower)}

After new batch written at offset 1000000:
  broker-1 LEO = 1000001
  broker-2 LEO = 1000001  ← fetched and replicated
  broker-3 LEO = 1000000  ← still fetching

HW = min(LEO across all ISR) = min(1000001, 1000001, 1000000) = 1000000

When broker-3 fetches and advances to 1000001:
  HW = 1000001  ← advances — record now visible to consumers
```

### Step 7: Producer ack sent

With `acks=all`, the leader sends the `ProduceResponse` only after HW has advanced past the written batch. With `acks=1`, it sends after its own write (not waiting for followers). With `acks=0`, the response is not sent at all.

### Fsync — when does data actually hit disk?

**Kafka does NOT fsync per message by default.** The OS page cache flush determines when dirty pages reach disk. This is controlled by OS kernel parameters:

```bash
# Linux kernel parameters controlling page cache flush
/proc/sys/vm/dirty_ratio=80          # flush when 80% of RAM is dirty pages
/proc/sys/vm/dirty_background_ratio=5 # background flush when 5% of RAM is dirty
/proc/sys/vm/dirty_expire_centisecs=3000 # flush pages dirty for > 30 seconds
/proc/sys/vm/dirty_writeback_centisecs=500 # writeback thread wakes every 5 seconds
```

**Why is this safe?** Because **replication provides durability**, not fsync. With `replication.factor=3` and `acks=all`, the data is in the page cache of 3 different machines simultaneously. For all three to lose the data, all three machines would need to crash (OOM, power failure) before their page caches are flushed — an extremely unlikely simultaneous event.

Kafka does offer fsync-per-N-messages as a configuration:

```properties
# Force fsync every N messages (default disabled = 9223372036854775807)
log.flush.interval.messages=10000

# Force fsync every N milliseconds (default disabled)
log.flush.interval.ms=1000
```

In practice, these are rarely used in production because they dramatically reduce throughput and the durability benefit is marginal compared to replication.

---

## 7. Segment Rolling

A segment is "rolled" (closed and replaced with a new active segment) when any of the following conditions is met:

### Size-based roll: `segment.bytes`

```properties
segment.bytes=1073741824  # 1 GB (default)
```

When the active segment's .log file reaches this size, it is closed (becomes immutable) and a new active segment starts at the current LEO.

```
At LEO = 1000000, active segment is full:
  00000000000001000000.log → CLOSED (immutable)
  00000000000001000000.index → CLOSED (truncated to actual size)
  00000000000001000000.timeindex → CLOSED

New active segment:
  00000000000002000000.log → created, empty
  00000000000002000000.index → created, pre-allocated
  00000000000002000000.timeindex → created
```

### Time-based roll: `segment.ms`

```properties
segment.ms=604800000  # 7 days (default)
```

If the active segment hasn't rolled in `segment.ms`, it is force-rolled even if it hasn't reached `segment.bytes`. This ensures:
- Low-volume topics still have their old segments rolled
- Time-based retention (`retention.ms`) can actually delete old data — retention only deletes entire segments, so a segment must roll before the oldest data in it can be deleted

**Important**: On a low-volume topic, the active segment may contain data spanning months. It won't be eligible for retention deletion until it rolls. `segment.ms` forces the roll.

### Index full roll: `segment.index.bytes`

```properties
segment.index.bytes=10485760  # 10 MB (default)
```

If the .index file is full (all pre-allocated space used), the segment is rolled. Rare with default settings — 10 MB holds 1.25 million index entries.

### What happens during a roll

```
1. Active segment .log file closed — no more appends
2. .index file truncated to actual used size (pre-allocation released)
3. .timeindex file truncated similarly
4. New empty .log created with base offset = current LEO
5. New .index pre-allocated to segment.index.bytes
6. New .timeindex pre-allocated
7. Old closed segment now eligible for:
   - Deletion (if retention limits exceeded)
   - Compaction (if cleanup.policy=compact)
```

---

## 8. Read Path — Disk to Consumer

### Finding the right segment

Consumer sends `FetchRequest(topic=orders, partition=0, fetchOffset=750000, maxBytes=1048576)`.

**Step 1**: Find the segment file:
```
Segment files in memory (sorted list): [0, 500000, 1000000, 1500000]
Binary search for largest base_offset ≤ 750000:
  → 500000  (segment 00000000000000500000.log)
```

**Step 2**: Find the physical position in the .log file using the .index:
```
.index file contents (relative to base=500000):
  [rel=0,      pos=0]
  [rel=10000,  pos=456789]
  [rel=20000,  pos=913578]
  ...

Looking for relative offset = 750000 - 500000 = 250000:
Binary search .index for largest rel ≤ 250000
→ [rel=250000, pos=11453920]  (hypothetical match)
```

**Step 3**: Scan .log from physical position 11453920:
```
At position 11453920: RecordBatch(baseOffset=750000, ...) ← FOUND
Scan forward from here to collect up to maxBytes of data
```

**Step 4**: Serve using zero-copy (see next section).

### The HW boundary check

Before serving, the broker checks:

```
fetchOffset = 750000
High Watermark = 760000  (only committed up to this point)
isolation.level = read_uncommitted

Serve offsets 750000 → min(750000 + maxBytes, HW) = up to 760000
```

For `read_committed`, the boundary is the Last Stable Offset (LSO) instead of HW.

### FetchRequest vs FetchResponse size

The consumer's `FetchRequest` specifies `maxBytes` and `maxPartitionBytes` but these are soft limits — if a single record batch is larger than `maxPartitionBytes`, it is still returned (Kafka won't split a batch). The broker will return at least one complete batch even if it exceeds the byte limit.

---

## 9. Zero-Copy Transfer — sendfile()

This is one of Kafka's most significant performance optimisations. It enables serving consumer reads without copying data into the JVM heap at all.

### The naive data path (without zero-copy)

Without zero-copy, serving a consumer fetch would require:

```
1. Application calls read(fd, userBuffer, maxBytes)
   → Kernel: disk → kernel page cache (DMA copy #1)
   → Kernel: page cache → user space buffer (CPU copy #2)
   → System call returns
   Application has data in JVM heap

2. Application calls write(socket, userBuffer, maxBytes)
   → Kernel: user space → kernel socket buffer (CPU copy #3)
   → Kernel: socket buffer → NIC (DMA copy #4)

Total: 4 memory copies, 4 context switches (user↔kernel)
```

### The zero-copy path (with sendfile)

```java
// Kafka uses FileChannel.transferTo() which maps to sendfile() on Linux
fileChannel.transferTo(position, count, socketChannel);

// Kernel execution:
// 1. Check page cache for [position, position+count]
//    → If cached: already in RAM, no disk read needed
//    → If not cached: disk → page cache (DMA, 1 copy)
// 2. DMA descriptor created: page cache → NIC (DMA, 1 copy)
//    → Data never enters user space
//    → Data never enters JVM heap

Total: 2 memory copies (or just 1 DMA if the NIC supports scatter-gather I/O)
       2 context switches (user→kernel for sendfile, kernel signals completion)
```

### The performance impact

```
Without zero-copy (naive read-write):
  200 MB/s throughput: 
  → 4 copies × 200 MB/s = 800 MB/s memory bandwidth consumed

With zero-copy (sendfile):
  200 MB/s throughput:
  → 2 copies × 200 MB/s = 400 MB/s memory bandwidth consumed
  → Half the memory bandwidth → CPU freed up → higher throughput possible
```

On a benchmark serving 200 MB/s to consumers, zero-copy reduces CPU utilisation from ~40% to ~5%.

### When zero-copy is NOT used

Zero-copy (`transferTo`) only works when Kafka serves data **as-is** — the same bytes that are on disk go directly to the consumer. If the broker needs to modify the data in transit, zero-copy cannot be used:

1. **SSL/TLS**: Encrypting data requires copying into heap for the crypto operation. Encryption disables zero-copy. If you use SSL, expect ~30% throughput reduction.

2. **Broker-side compression recompression**: If `compression.type` on the topic differs from what the producer sent, the broker decompresses and recompresses — requires heap copy.

3. **Message format conversion**: Old consumers (pre-0.10) receive a different format. The broker must convert — requires heap copy. Modern consumers all use format v2 — no conversion needed.

---

## 10. Log Compaction Internals

Log compaction is an alternative to time/size-based retention. Instead of deleting old data by age, compaction keeps **only the latest value per key**, ensuring every key's current state is preserved indefinitely.

### When compaction applies

Set via `cleanup.policy=compact` (or `delete,compact` for both):

```bash
kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic account-balances \
  --config cleanup.policy=compact \
  --config min.cleanable.dirty.ratio=0.1 \
  --config segment.bytes=268435456     # 256 MB (smaller for faster compaction)
```

### The log cleaner architecture

The broker runs a pool of log cleaner threads (`log.cleaner.threads`, default 1). Each thread:

1. Identifies a partition that needs cleaning (dirty ratio above `min.cleanable.dirty.ratio`)
2. Reads through the dirty portion to build an **offset map**
3. Rewrites segments keeping only records that are the latest for their key

### The clean/dirty boundary

```
Partition log (compacted topic):
┌──────────────────────────────────────────────────────────────────┐
│ Clean portion            │ Dirty portion                         │
│ (already compacted)      │ (new records, not yet compacted)      │
│ offset 0 → 50000         │ offset 50001 → 99999 (active)        │
└──────────────────────────────────────────────────────────────────┘
          ↑
    cleaner checkpoint

The cleaner processes the dirty portion and merges result with clean portion.
The active segment is NEVER compacted (it's being written to).
```

### Building the offset map

The cleaner reads the dirty portion and builds a hash map of `key → highest_offset_seen`:

```
Dirty portion records:
  offset 50001: key=user-1, value={"bal": 100}
  offset 50002: key=user-2, value={"bal": 200}
  offset 50003: key=user-1, value={"bal": 150}   ← user-1 newer value
  offset 50004: key=user-3, value=null             ← tombstone
  offset 50005: key=user-2, value={"bal": 250}   ← user-2 newer value

Offset map after scanning:
  user-1 → 50003
  user-2 → 50005
  user-3 → 50004 (tombstone)
```

Memory limit: `log.cleaner.dedupe.buffer.size` (default 128 MB). If the offset map doesn't fit in memory, the cleaner processes the dirty portion in multiple passes.

### Rewriting segments

The cleaner reads through segments and writes a new version keeping only records whose offset matches the offset map:

```
For each record in dirty segment:
  if offsetMap[record.key] == record.offset:
    KEEP → copy to new segment
  else:
    DISCARD (a newer value for this key exists)

Tombstone records:
  Keep tombstone until delete.retention.ms has passed
  Then discard tombstone on next compaction pass
```

The old segment file is renamed (`.deleted` suffix) and physically removed after `log.segment.delete.delay.ms`.

### Tombstone lifecycle

```
t=0    Producer sends: key=user-1, value=null  (tombstone at offset 50004)
t=0    Tombstone written to log — key=user-1 is "deleted" semantically

t=1    Consumer reads offset 50004 — sees tombstone
       Consumer application knows: "user-1 was deleted"

t=24h  delete.retention.ms expires
       Next compaction pass: tombstone for user-1 is NOT copied to new segment
       user-1 key is now completely gone from the log

t=24h+ New consumer joining: starts from earliest
        user-1 is absent — the deletion was propagated
```

The 24-hour `delete.retention.ms` window ensures consumers that are up to 24 hours behind still see the tombstone and can process the deletion. After that, the tombstone is safe to remove.

### Compaction guarantees

1. At least the most recent value for every key is always retained
2. Records that have not been compacted yet (dirty portion) are always fully retained
3. Message ordering within a partition is preserved (offsets only increase)
4. Consumer offsets are not invalidated — compacted-away records are simply skipped

### Compaction tuning

```properties
# How dirty (uncompacted fraction) before cleaning starts (0.0–1.0)
min.cleanable.dirty.ratio=0.1   # clean when 10% dirty (aggressive)
                                 # default is 0.5 (50% dirty)

# Max fraction of log that can be left uncleaned (prevents tiny topics from
# consuming all cleaner resources)
max.compaction.lag.ms=86400000  # records newer than 1 day won't be compacted

# Min time a record must be in the log before it can be compacted
min.compaction.lag.ms=0         # 0 = compact immediately

# Size of cleaner I/O buffer
log.cleaner.io.buffer.size=524288  # 512 KB per cleaner thread

# Memory for the offset map
log.cleaner.dedupe.buffer.size=134217728  # 128 MB (shared across threads)
```

---

## 11. Retention and Deletion

Retention controls when old data is deleted from disk. It applies to topics with `cleanup.policy=delete` (the default).

### Retention check loop

The log cleaner background thread checks for segments to delete every `log.retention.check.interval.ms` (default 5 minutes). For each partition, it evaluates whether the **oldest closed segments** can be deleted.

**Critical**: Only **closed** (non-active) segments can be deleted. The active segment is never deleted. This means a topic with very low write volume may retain data much longer than `retention.ms` if the active segment hasn't rolled.

### Time-based retention: `retention.ms`

```properties
retention.ms=604800000  # 7 days (default)
```

A segment is eligible for deletion when the **timestamp of its latest record** is older than `retention.ms`.

```
Segment 00000000000000000000.log:
  lastRecord.timestamp = 8 days ago
  retention.ms = 7 days
  → ELIGIBLE for deletion

Segment 00000000000000500000.log:
  lastRecord.timestamp = 3 days ago
  → NOT eligible (too recent)
```

### Size-based retention: `retention.bytes`

```properties
retention.bytes=5368709120  # 5 GB per partition (default -1 = disabled)
```

When the total size of all segments in a partition exceeds `retention.bytes`, the oldest segments are deleted until total size is below the threshold.

```
Partition total size: 7 GB
retention.bytes: 5 GB
Excess: 2 GB

Delete oldest segments until total ≤ 5 GB:
  Delete segment 0 (800 MB) → total = 6.2 GB
  Delete segment 500000 (900 MB) → total = 5.3 GB
  Delete segment 1000000 (500 MB) → total = 4.8 GB ≤ 5 GB → STOP
```

### Deletion mechanics

```
1. Segment identified as deletable
2. Segment renamed: .log → .log.deleted, .index → .index.deleted, .timeindex → .timeindex.deleted
3. Broker waits log.segment.delete.delay.ms (default 60000 = 1 minute)
   (gives in-progress reads time to complete before the file disappears)
4. Files physically unlinked (deleted) from the filesystem

The delay prevents I/O errors for slow consumers reading near the retention boundary.
```

### Log Start Offset (LSO) advancement

When old segments are deleted, the **Log Start Offset** (the earliest available offset) advances to the base offset of the oldest remaining segment:

```
Before deletion:
  Segments: [0, 500000, 1000000, 1500000]
  LSO = 0

After deleting segment 0:
  Segments: [500000, 1000000, 1500000]
  LSO = 500000

Consumers trying to read offset 100 (which no longer exists):
  → OffsetOutOfRangeException
  → Consumer's auto.offset.reset applies (or manual seek needed)
```

This is the source of `OffsetOutOfRangeException` — a consumer that was far behind has had its position deleted. Solution: increase retention, reduce consumer lag, or configure `auto.offset.reset=earliest` to jump to the new LSO automatically.

---

## 12. Replication and Disk Coordination

How leader and follower disk files stay in sync.

### Follower replication — identical file structure

Each follower maintains an **identical directory structure** to the leader — same segment files, same index files. The only differences:

1. Followers may lag slightly behind the leader (ISR lag)
2. Followers do not update the High Watermark — only the leader controls HW
3. Followers do not serve consumer reads (by default)

### Recovery after follower restart

When a follower broker restarts, it reads `replication-offset-checkpoint` to determine the last replicated offset for each partition. It then truncates its log to that checkpoint (discarding any data written after the checkpoint that might not have been committed):

```
Follower restarts:
  1. Read recovery-point-offset-checkpoint: orders-0 was at offset 750000
  2. Truncate orders-0 log to offset 750000 (discard any records past this point)
  3. Start ReplicaFetcherThread from offset 750001
  4. Fetch and replicate from leader until caught up
  5. Rejoin ISR once lag < replica.lag.time.max.ms
```

### Recovery after leader failure

When a leader fails and a follower is promoted, the new leader may need to truncate its own log:

```
Original leader (broker-1) wrote up to offset 1000000
  - broker-2 (follower) replicated up to offset 999900
  - broker-3 (follower) replicated up to offset 999800

broker-1 crashes. broker-2 becomes new leader.
broker-2 has offsets 0 → 999900 in its log.

The 100 records (999901 → 1000000) that were only on broker-1 are LOST.
(This is why acks=all + min.insync.replicas=2 is needed — broker-3 also
has up to 999800, so only records 999801 → 1000000 were ever at risk.)

broker-3 rejoins:
  1. Contacts new leader (broker-2) to reconcile
  2. broker-2 tells broker-3: my HW is 999900
  3. broker-3 truncates its log to 999800 (its own last committed point)
  4. broker-3 fetches 999801 → 999900 from broker-2
  5. broker-3 rejoins ISR
```

This truncation is coordinated via the **leader epoch checkpoint** — the leader tells followers which epoch is current, and followers can truncate data from old epochs.

---

## 13. Storage Configuration Reference

### Core storage properties (`server.properties`)

```properties
#─────────────────────────────────────────────
# Log directories
#─────────────────────────────────────────────
log.dirs=/var/kafka/logs          # Single disk
# log.dirs=/mnt/disk1/kafka,/mnt/disk2/kafka  # Multiple disks

#─────────────────────────────────────────────
# Segment sizing
#─────────────────────────────────────────────
log.segment.bytes=1073741824      # 1 GB per segment (default)
log.roll.ms=604800000             # Roll segment after 7 days (default)
log.index.size.max.bytes=10485760 # 10 MB index file pre-allocation
log.index.interval.bytes=4096     # Index entry every 4 KB of data

#─────────────────────────────────────────────
# Retention
#─────────────────────────────────────────────
log.retention.hours=168           # 7 days (default)
log.retention.bytes=-1            # No size limit (default)
log.retention.check.interval.ms=300000  # Check every 5 min
log.segment.delete.delay.ms=60000 # 1 min before physical delete

#─────────────────────────────────────────────
# Flush policy (usually leave at defaults)
#─────────────────────────────────────────────
log.flush.interval.messages=9223372036854775807  # Never (rely on OS)
log.flush.interval.ms=9223372036854775807        # Never (rely on OS)
log.flush.scheduler.interval.ms=9223372036854775807

#─────────────────────────────────────────────
# Compaction
#─────────────────────────────────────────────
log.cleaner.enable=true           # Enable log compaction
log.cleaner.threads=1             # Cleaner thread count
log.cleaner.dedupe.buffer.size=134217728  # 128 MB offset map
log.cleaner.io.buffer.size=524288         # 512 KB I/O buffer per thread
log.cleaner.min.cleanable.ratio=0.5       # 50% dirty ratio threshold
log.cleaner.io.max.bytes.per.second=1.7976931348623157E308  # Unlimited

#─────────────────────────────────────────────
# Replication
#─────────────────────────────────────────────
num.replica.fetchers=1            # Threads per broker for replication
replica.fetch.max.bytes=1048576   # 1 MB per replica fetch
```

### Per-topic overrides (via kafka-configs.sh)

```bash
# Set retention for a specific topic
kafka-configs.sh --bootstrap-server localhost:9092 \
  --entity-type topics --entity-name orders \
  --alter --add-config \
  retention.ms=86400000,\          # 1 day retention
  segment.bytes=268435456,\        # 256 MB segments
  min.insync.replicas=2,\
  compression.type=lz4

# Enable compaction on a topic
kafka-configs.sh --bootstrap-server localhost:9092 \
  --entity-type topics --entity-name account-balances \
  --alter --add-config \
  cleanup.policy=compact,\
  min.cleanable.dirty.ratio=0.1,\
  delete.retention.ms=86400000,\
  segment.bytes=268435456

# Set both delete and compact
kafka-configs.sh --bootstrap-server localhost:9092 \
  --entity-type topics --entity-name user-events \
  --alter --add-config cleanup.policy=delete,compact
```

---

## 14. Disk Sizing and Capacity Planning

### Formula for disk space

```
Disk required per broker =
  sum over all partitions on this broker of:
    (message_rate_MB_per_sec × retention_seconds × replication_factor) / num_brokers

Simplified (assuming even distribution):
  Disk per broker = total_write_rate × retention_time × replication_factor / num_brokers
```

**Example**:
```
Write rate:          100 MB/s total (across all producers)
Retention:           7 days = 604800 seconds
Replication factor:  3
Brokers:             3

Raw data retained = 100 MB/s × 604800s = 60,480,000 MB = 58.6 TB
With replication:   58.6 TB × 3 = 175.8 TB across cluster
Per broker:         175.8 TB / 3 = 58.6 TB

With compression (lz4, ~3x ratio):
  Actual disk per broker ≈ 58.6 TB / 3 = 19.5 TB per broker
```

### Overhead to add

- **Index files**: ~1% of log data size (negligible)
- **OS overhead**: ~5% for filesystem metadata, journal
- **Safety margin**: 20-30% for burst writes, compaction temporary space

```
Total disk per broker = (data_size × 1.01 × 1.05 × 1.25)
```

### Storage type recommendations

| Storage Type | IOPS | Throughput | Suitable | Notes |
|---|---|---|---|---|
| NVMe SSD | 500K+ | 3+ GB/s | Best for high throughput | Most cost-effective for Kafka workloads |
| SATA SSD | 80K | 500 MB/s | Good for medium throughput | Good balance of cost and performance |
| HDD (7200 RPM) | 150 | 200 MB/s | Low throughput only | Sequential writes help but random I/O (index) hurts |
| EBS gp3 (AWS) | 16K | 1 GB/s | Cloud deployments | Tune IOPS separately from capacity |

Kafka's sequential write pattern benefits HDDs more than most databases, but SSDs are recommended for any serious production workload due to index lookups requiring random reads.

### Multiple disk configuration

Striping data across multiple disks with `log.dirs`:

```properties
log.dirs=/mnt/disk1/kafka,/mnt/disk2/kafka,/mnt/disk3/kafka,/mnt/disk4/kafka
```

**Benefits**:
- 4x aggregate sequential I/O bandwidth
- Partition reassignment distributes load naturally
- A failed disk only affects partitions on that disk (not all partitions)

**Kafka does NOT do RAID** — it relies on replication for redundancy. Don't use RAID-1 or RAID-5 with Kafka — use `log.dirs` with multiple JBOD disks for better throughput and simpler management.

---

## 15. Monitoring Disk Health

### Critical metrics to monitor

```bash
# Disk usage per partition
kafka-log-dirs.sh --bootstrap-server localhost:9092 \
  --topic-list orders,payments \
  --describe

# Output: per-partition size in bytes, offset lag info
```

### JMX metrics for storage

```
# Segment and partition counts
kafka.log:type=LogManager,name=ActiveSegmentCount → total active segments
kafka.log:type=LogManager,name=OfflinePartitionsCount → should always be 0

# Per-partition log metrics
kafka.log:type=Log,name=LogEndOffset,topic=orders,partition=0
kafka.log:type=Log,name=LogStartOffset,topic=orders,partition=0
kafka.log:type=Log,name=NumLogSegments,topic=orders,partition=0
kafka.log:type=Log,name=Size,topic=orders,partition=0 → bytes

# Log cleaner metrics
kafka.log:type=LogCleanerManager,name=max-dirty-percent → should be < 80%
kafka.log:type=LogCleaner,name=cleaner-recopy-percent → recompaction effort
kafka.log:type=LogCleaner,name=max-clean-time-secs → compaction duration

# Under-replicated (disk issue may cause replica lag)
kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions → should be 0
kafka.server:type=ReplicaManager,name=UnderMinIsrPartitionCount → should be 0
```

### Prometheus alert rules for storage

```yaml
groups:
  - name: kafka_storage
    rules:

    # Disk usage approaching capacity
    - alert: KafkaBrokerDiskUsageHigh
      expr: (node_filesystem_used_bytes{mountpoint="/var/kafka"} /
             node_filesystem_size_bytes{mountpoint="/var/kafka"}) > 0.80
      for: 5m
      annotations:
        summary: "Kafka broker disk usage > 80% on {{ $labels.instance }}"

    # Log cleaner falling behind (dirty ratio growing)
    - alert: KafkaLogCleanerFallingBehind
      expr: kafka_log_cleaner_manager_max_dirty_percent > 0.80
      for: 15m
      annotations:
        summary: "Log cleaner cannot keep up — dirty ratio > 80%"

    # Offline partitions — critical
    - alert: KafkaOfflinePartitions
      expr: kafka_controller_offline_partitions_count > 0
      for: 1m
      annotations:
        summary: "{{ $value }} partitions are offline — disk failure possible"

    # Under-replicated partitions — may indicate disk pressure
    - alert: KafkaUnderReplicatedPartitions
      expr: kafka_server_replica_manager_under_replicated_partitions > 0
      for: 5m
      annotations:
        summary: "{{ $value }} partitions are under-replicated"

    # Log start offset advancing rapidly (consumers at risk)
    - alert: KafkaRetentionDeletingFast
      expr: rate(kafka_log_log_start_offset[5m]) > 1000
      for: 10m
      annotations:
        summary: "Retention is deleting offsets rapidly — consumers may lag behind LSO"
```

### Diagnosing disk issues

```bash
# Check which topics use the most disk
du -sh /var/kafka/logs/*/ | sort -rh | head -20

# Check segment count per partition
ls /var/kafka/logs/orders-0/*.log | wc -l

# Check index file sizes (should be < segment.index.bytes = 10 MB)
ls -lh /var/kafka/logs/orders-0/*.index

# Verify segment integrity (dumps batch headers)
kafka-dump-log.sh --files /var/kafka/logs/orders-0/00000000000000000000.log \
  --print-data-log | head -50

# Check log directory configuration
kafka-log-dirs.sh --bootstrap-server localhost:9092 \
  --broker-list 0,1,2 --describe | python3 -m json.tool
```

---

## 16. Quick Reference

### File types and their purpose

| File | Format | Purpose | Memory-mapped? |
|---|---|---|---|
| `.log` | Binary RecordBatch sequence | Raw message storage | No — read via FileChannel |
| `.index` | 8-byte fixed entries | Offset → physical position | Yes — mmap'd for fast lookup |
| `.timeindex` | 12-byte fixed entries | Timestamp → offset | Yes — mmap'd |
| `leader-epoch-checkpoint` | Text | Leader change history | No |
| `recovery-point-offset-checkpoint` | Text | Last flushed offset per partition | No |
| `log-start-offset-checkpoint` | Text | Earliest available offset per partition | No |
| `replication-offset-checkpoint` | Text | Last replicated offset per partition | No |

### Key configuration properties

| Property | Default | Tunes |
|---|---|---|
| `log.dirs` | `/tmp/kafka-logs` | Storage location |
| `log.segment.bytes` | `1 GB` | Segment size |
| `log.roll.ms` | `7 days` | Segment time roll |
| `log.retention.hours` | `168` (7 days) | Time-based retention |
| `log.retention.bytes` | `-1` (off) | Size-based retention |
| `log.index.interval.bytes` | `4096` | Offset index density |
| `log.cleaner.threads` | `1` | Compaction parallelism |
| `log.cleaner.dedupe.buffer.size` | `128 MB` | Compaction offset map |
| `min.cleanable.dirty.ratio` | `0.5` | Compaction aggressiveness |
| `log.segment.delete.delay.ms` | `60000` | Delay before physical delete |

### Write path summary

```
Producer send()
  ↓ Network receive
  ↓ CRC validation
  ↓ FileChannel.write() → OS page cache (NOT direct to disk)
  ↓ In-memory LEO update
  ↓ Follower replication (also to page cache)
  ↓ HW advance (when all ISR replicated)
  ↓ Producer ack sent
  ↓ OS async flush → disk (background, no fixed timing)
```

### Read path summary

```
Consumer FetchRequest(offset=N)
  ↓ Find segment: binary search filename list → O(log S)
  ↓ Find position: binary search .index file → O(log I) (mmap'd)
  ↓ Scan to exact offset: O(index.interval.bytes / avg_record_size)
  ↓ FileChannel.transferTo() → sendfile() syscall
  ↓ Page cache → NIC (zero-copy, no JVM heap involved)
  ↓ Data on wire
```

### Storage guarantee summary

| Guarantee | Mechanism |
|---|---|
| Durability | Replication (not fsync) — data on N machines simultaneously |
| No duplicates on write | Idempotent producer (sequence numbers per PID+partition) |
| Sequential consistency | Single active segment, append-only, HW monotonically advances |
| Fast recovery | Page cache survives broker restart; only unflushed data needs recovery |
| Ordered deletion | Only oldest full segments deleted, never partial |
| Compaction correctness | Tombstones retained for delete.retention.ms before removal |

---

*Kafka's storage design is a masterclass in working with hardware constraints rather than against them. Sequential I/O, OS page cache reliance, zero-copy transfer, and replication-based durability combine to deliver throughput that dedicated storage systems struggle to match.*
