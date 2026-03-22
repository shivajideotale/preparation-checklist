# Kafka Internals — All Core Concepts (Brief Reference)

> 65+ core Kafka internal concepts, each explained briefly and precisely. Organised by layer. Covers every topic that appears in senior/staff-level interviews and production debugging.

---

## Table of Contents

1. [Cluster & Broker](#1-cluster--broker)
2. [Log Storage](#2-log-storage)
3. [Producer Internals](#3-producer-internals)
4. [Consumer & Consumer Groups](#4-consumer--consumer-groups)
5. [Offset Management](#5-offset-management)
6. [Delivery Semantics](#6-delivery-semantics)
7. [Replication](#7-replication)
8. [Performance & I/O](#8-performance--io)
9. [Security](#9-security)
10. [Mental Models & Diagrams](#10-mental-models--diagrams)
11. [Interview Quick-Fire Reference](#11-interview-quick-fire-reference)

---

## 1. Cluster & Broker

### Broker
Single Kafka server process. Stores partition segment files on disk. Accepts `ProduceRequest` from producers and `FetchRequest` from consumers. One broker can lead thousands of partitions. The JVM heap is kept small (6 GB) — the OS page cache (24 GB on a 32 GB machine) does the real caching work and survives JVM restarts.

---

### Controller
Exactly **one** broker per cluster holds the Controller role. Its jobs:
- **Partition leader election** — when a broker dies, elects a new leader from ISR
- **ISR updates** — keeps track of which replicas are in-sync
- **Broker lifecycle** — detects brokers joining and leaving
- **Metadata propagation** — sends `LeaderAndIsrRequest` to all brokers on topology change

Pure control-plane. Zero message data passes through it.

---

### KRaft Mode (Kafka 3.3+ GA; ZooKeeper removed in Kafka 4.0)
Replaces ZooKeeper with internal Raft consensus among 3 or 5 dedicated controller nodes. Metadata stored in `@metadata` internal topic. Faster failure detection (milliseconds vs ZK session timeout of 6–18 seconds). Eliminates the external ZooKeeper dependency, simplifies operations, and scales to millions of partitions (vs ZK bottleneck at ~200K).

---

### ZooKeeper (Legacy)
External coordination service. Stored controller election via ephemeral `/controller` ZNode — first broker to create it becomes controller. Stored broker registrations, ACLs. Main limitation: ZK session timeout drives controller failover speed. Deprecated since Kafka 3.x, fully removed in Kafka 4.0.

---

### Cluster Metadata
Every broker caches the full cluster topology: which broker leads which partition, ISR sets, topic configs. Producers and consumers also cache metadata locally, refreshing every `metadata.max.age.ms` (5 min default) or immediately on `LEADER_NOT_AVAILABLE` error. Stale metadata → wrong broker targeted → error → automatic refresh and retry.

---

## 2. Log Storage

### Topic
Logical named stream. No physical storage — data lives in partition directories. Created with replication factor, partition count, retention, and cleanup policy. Partition count can only increase, never decrease. Data retention: time (`retention.ms`) or size (`retention.bytes`).

---

### Partition
An **ordered, immutable, append-only log**. The unit of parallelism AND the unit of replication.

```
/var/kafka/logs/
├── orders-0/   ← topic "orders", partition 0
├── orders-1/   ← topic "orders", partition 1
└── orders-2/   ← topic "orders", partition 2
```

Key rules:
- Messages within a partition are strictly ordered by offset
- Only ONE consumer per partition per consumer group can read at a time
- Each partition has exactly ONE leader and N-1 followers

---

### Segment (.log file)
The actual data file. An append-only sequence of `RecordBatch` entries. **Active segment** receives all writes. Rolled (closed, new one created) when:
- Size reaches `segment.bytes` (default 1 GB), OR
- Age reaches `segment.ms` (default 7 days)

Rolled segments are **immutable** — eligible for deletion or compaction, never modified.

```
Filename = 20-digit zero-padded base offset:
  00000000000000000000.log  ← contains messages starting at offset 0
  00000000000000500000.log  ← contains messages starting at offset 500000
  00000000000001000000.log  ← ACTIVE (current writes go here)
```

Binary search by filename finds the right segment in O(log N).

---

### Offset Index (.index)
Sparse, memory-mapped file mapping **relative offset → physical byte position** in the `.log` file.

```
Entry size: 8 bytes
  4 bytes: relative offset  (actual_offset - segment_base_offset)
  4 bytes: physical position in .log file

Written every index.interval.bytes (4 KB) of data.
Memory-mapped via FileChannel.map() — zero syscall for lookups.
Pre-allocated to segment.index.bytes (10 MB).
```

Enables jumping directly to any offset without scanning the full log file.

---

### Timestamp Index (.timeindex)
Same sparse structure as `.index` but maps **timestamp → relative offset**. Written alongside each `.index` entry. Powers `consumer.offsetsForTimes()` — finding the first message at or after a target timestamp. Used for "replay from N minutes ago" and disaster recovery.

---

### RecordBatch
The **unit of write**. One `ProduceRequest` carries one or more `RecordBatch` entries.

```
RecordBatch (61-byte fixed header + variable records):
  baseOffset:          int64   ← offset of first record
  batchLength:         int32   ← total byte length
  magic:               int8    ← format version = 2
  crc:                 int32   ← CRC32C checksum
  attributes:          int16   ← compression codec + transactional flag
  lastOffsetDelta:     int32
  firstTimestamp:      int64
  maxTimestamp:        int64
  producerId:          int64   ← for idempotent/transactional
  producerEpoch:       int16   ← for zombie fencing
  baseSequence:        int32   ← for deduplication
  numRecords:          int32
  records:             bytes   ← compressed or raw records
```

Compression applied to the **whole batch** — cross-message redundancy (shared JSON field names, similar timestamps) makes batch-level compression far more effective than per-message.

---

### Record
Individual message inside a batch. Uses **varint delta-encoding** to save space.

```
Record fields:
  length:          varint
  attributes:      int8    (reserved, = 0)
  timestampDelta:  varint  ← record.ts - batch.firstTimestamp
  offsetDelta:     varint  ← record.offset - batch.baseOffset
  keyLength:       varint  (-1 = null key)
  key:             bytes
  valueLength:     varint  (-1 = null value = TOMBSTONE)
  value:           bytes
  headersCount:    varint
  headers:         []
```

`value=null` = tombstone record — signals key deletion for compacted topics.

---

### Page Cache
The **Linux OS page cache** is Kafka's primary caching layer — not the JVM heap.

Why this design wins:
- **No GC overhead** — OS manages page cache without garbage collection
- **Survives JVM restart** — cache is warm immediately after broker restart
- **No double-buffering** — file access already uses page cache; JVM heap cache would be a second copy
- **OS prefetching** — kernel's readahead prefetches upcoming sequential pages automatically
- **Producer-consumer locality** — recently written messages served from RAM with zero disk I/O

```
Producer writes → page cache (RAM) → OS flushes to disk async
Consumer reads → check page cache → if warm (recent data): served from RAM
                                 → if cold (old data): disk I/O → page cache → served
```

---

### Zero-Copy Transfer (sendfile)
Serving consumer reads without copying data through the JVM heap.

```
Normal path (4 copies, 4 context switches):
  disk → kernel page cache → JVM heap → kernel socket buffer → NIC

Zero-copy path (2 copies, 2 context switches):
  disk → kernel page cache ──DMA──► NIC
  (Java: FileChannel.transferTo() → Linux sendfile() syscall)
```

Eliminates ~50% of memory bandwidth for consumer read operations. **Disabled when SSL/TLS is enabled** — data must enter JVM heap for encryption. Expect ~30% throughput reduction with SSL.

---

### Sequential I/O
All Kafka writes are appends to the active segment end. Consumers read forward from their current offset. This pattern exploits hardware:

```
Spinning disk:
  Sequential I/O: 200+ MB/s
  Random I/O:     ~2 MB/s
  Ratio: 100x

SSD:
  Sequential I/O: 3+ GB/s (NVMe)
  Random I/O:     500+ MB/s
  Ratio: 6x (smaller gap but still significant)
```

Deletions are whole-file (`unlink` old segment) — not record-by-record, no index cleanup needed.

---

### Log Compaction
Retention policy (`cleanup.policy=compact`) that keeps only the **latest value per key**.

```
Before compaction:
  offset 0: key=user-1, value={balance:100}
  offset 1: key=user-2, value={balance:200}
  offset 2: key=user-1, value={balance:150}   ← newer, supersedes offset 0
  offset 3: key=user-1, value=null             ← tombstone: delete user-1
  offset 4: key=user-2, value={balance:250}   ← newer, supersedes offset 1

After compaction (tombstone retained temporarily):
  offset 3: key=user-1, value=null   ← tombstone kept for delete.retention.ms (24h)
  offset 4: key=user-2, value=250

After delete.retention.ms expires:
  offset 4: key=user-2, value=250   ← only latest values, tombstones gone
```

**Use cases**: KTable changelogs, CDC topics, config distribution, event sourcing snapshots — anywhere consumers need current state, not full history.

---

### Log Cleaner
Background thread pool (`log.cleaner.threads=1` default) that runs compaction on closed segments.

```
For each partition where dirty_bytes/total_bytes >= min.cleanable.dirty.ratio (0.5):
  1. Read dirty portion → build offset map (key → highest offset seen)
     [bounded by log.cleaner.dedupe.buffer.size = 128 MB]
  2. Rewrite segments keeping only records whose offset matches the map
  3. Rename old segments → .deleted, wait log.segment.delete.delay.ms, unlink
```

Never touches the active segment. Runs continuously in the background without impacting producers.

---

### Retention
Deletion of old log segments when limits are exceeded.

| Type | Config | Behaviour |
|---|---|---|
| Time-based | `retention.ms` (default 7 days) | Delete segments whose last record exceeds age limit |
| Size-based | `retention.bytes` (default -1 = off) | Delete oldest segments until total size within limit |
| Both | Both set | Whichever triggers first causes deletion |

**Critical**: Only **closed** segments are deleted. The active segment is never deleted. Low-volume topics need `segment.ms` to force segment rolls so time-based retention can function.

---

## 3. Producer Internals

### RecordAccumulator
In-memory buffer organised as `Map<TopicPartition, Deque<RecordBatch>>`. All `send()` calls land here first. Backed by a **BufferPool** of reusable `ByteBuffer` objects (`buffer.memory` = 32 MB default). When pool is exhausted (broker back-pressure), `send()` blocks the calling thread for `max.block.ms` (60s) then throws `TimeoutException`.

```
RecordAccumulator {
  orders-0: [RecordBatch(FULL:512KB) | RecordBatch(ACTIVE:45KB)]
  orders-2: [RecordBatch(ACTIVE:12KB)]
}
```

---

### Sender Thread
The **only thread** that touches broker network I/O. The calling application thread only writes to the accumulator — never the network.

```
Loop (infinite):
  1. Ask accumulator: which partitions have ready batches?
     (ready = batch.size reached OR linger.ms elapsed OR retrying)
  2. Group by destination broker
  3. Build ProduceRequest per broker (multi-partition, batched)
  4. NIO selector: send requests, receive responses
  5. Handle response: complete Futures / retry / callback
```

---

### Partitioner
Determines the target partition for each record.

| Case | Algorithm | Ordering guarantee |
|---|---|---|
| Key not null | `murmur2(key) % numPartitions` | Same key → same partition → per-key order |
| Key null | StickyPartitioner (since Kafka 2.4) | Stick until batch full, then switch |
| Custom | Implement `Partitioner` interface | Whatever your logic returns |

---

### batch.size
Maximum bytes per batch per partition before the Sender sends it immediately. Default 16 KB. Too small = many tiny network calls + poor compression. High throughput: 128 KB–512 KB. Pairing rule: a batch sends when it hits `batch.size` **OR** `linger.ms` expires — whichever comes first.

---

### linger.ms
How long the Sender waits for a batch to fill before sending. Default 0 (immediate). Setting to 5 ms can improve throughput 5–10x with negligible added latency for most applications. Only keep at 0 for strict sub-millisecond latency requirements.

```
Recommended profiles:
  High throughput:  batch.size=524288, linger.ms=20
  Balanced (prod):  batch.size=131072, linger.ms=5
  Low latency:      batch.size=16384,  linger.ms=0
```

---

### buffer.memory
Total memory pool for the RecordAccumulator. Default 32 MB. When full: `send()` blocks for `max.block.ms` then `TimeoutException`. Increase for many partitions: target `>= batch.size × active_partition_count`.

---

### acks
Controls durability vs latency trade-off.

| acks | Confirmed by | Durability | Latency |
|---|---|---|---|
| `0` | Nobody | None — loss on network drop | Lowest |
| `1` | Leader only | Loss if leader crashes before replication | Low |
| `all` (-1) | All ISR replicas | No loss (with `min.insync.replicas=2`) | Higher |

Default `all` since Kafka 3.0.

---

### Idempotent Producer
Prevents **duplicate writes** caused by producer retries on lost acks.

```
enable.idempotence=true  (default since Kafka 3.0)

Mechanism:
  Broker assigns Producer ID (PID) to the producer
  Each batch gets sequence number (seq) per partition
  On retry: broker checks (PID, partition, seq)
    → already seen → DUPLICATE → silently discard, return success
    → not seen → accept, write

Zero performance cost.
Auto-configures: acks=all, retries=MAX_INT, max.in.flight.requests=5
```

---

### Transactional Producer
Atomic writes across **multiple partitions** + atomic offset commit.

```
initTransactions()         ← register transactional.id, get PID, increment epoch
beginTransaction()         ← client-side flag only, no network call
send(record1)              ← written to log, invisible to read_committed consumers
send(record2)              ← same
sendOffsetsToTransaction() ← atomic offset commit included in transaction
commitTransaction()        ← 2PC: PREPARE_COMMIT → COMMIT markers → COMPLETE
abortTransaction()         ← ABORT markers → records permanently invisible
```

**Zombie fencing**: `initTransactions()` increments epoch. Any prior instance with same `transactional.id` → `ProducerFencedException` → must stop immediately.

---

### BufferPool
Reusable `ByteBuffer` pool backing the RecordAccumulator. When a batch needs memory: takes from pool (zero allocation). When batch fully sent: returns to pool (zero GC). Under steady high throughput, producer GC pressure is near zero. Falls back to normal allocation only when pool is temporarily empty.

---

## 4. Consumer & Consumer Groups

### Consumer Group
All consumers with the same `group.id` cooperate. Each partition assigned to **exactly one** consumer per group. Key rules:
- Adding consumers beyond partition count = idle consumers (hot standbys)
- Two groups with different `group.id` both receive ALL messages independently
- Partition assignment managed by Group Coordinator + Group Leader

```
Topic "orders" (6 partitions), 3 consumers in group "order-svc":
  Consumer-A → [orders-0, orders-1]
  Consumer-B → [orders-2, orders-3]
  Consumer-C → [orders-4, orders-5]
```

---

### Group Coordinator
The broker leading the `__consumer_offsets` partition for a given group.

```
Partition = abs(groupId.hashCode()) % offsets.topic.num.partitions (default 50)
Coordinator = leader of __consumer_offsets-{Partition}
```

Manages: group membership, JoinGroup/SyncGroup rebalance protocol, heartbeat monitoring, offset storage/retrieval. **Does NOT handle message data** — `FetchRequest` and `ProduceRequest` bypass it entirely.

---

### Group Leader
A **consumer instance** (not the broker) elected to compute partition assignments. Receives the full member list in `JoinGroupResponse` (other members receive empty list). Runs the configured `PartitionAssignor` algorithm **client-side** and submits assignment via `SyncGroupRequest`.

Why client-side? Allows pluggable assignment strategies without broker changes.

---

### JoinGroup / SyncGroup Protocol
Two-phase rebalance driven by the Group Coordinator.

```
Phase 1 — JoinGroup:
  All members → JoinGroupRequest → Coordinator
  Coordinator: waits up to rebalance.timeout.ms, generationId++, elects leader
  Leader ← JoinGroupResponse { members: [A, B, C, D] }
  Others ← JoinGroupResponse { members: [] }

Phase 2 — SyncGroup:
  Leader → SyncGroupRequest { assignments: [{A→[0,1]}, {B→[2,3]}, {C→[4,5]}] }
  Others → SyncGroupRequest { assignments: [] }
  Each ← SyncGroupResponse { assignment: their-own-slice }
  Group state → Stable
```

---

### Generation ID
Monotonically increasing counter, incremented on every rebalance. Included in all group RPCs. Stale `generationId` → `ILLEGAL_GENERATION` error. Prevents a slow consumer from committing offsets for partitions it no longer owns after a rebalance.

---

### Group States

```
Empty ──► PreparingRebalance ──► CompletingRebalance ──► Stable
  ▲              ▲                                          │
  │              └──────────────────────────────────────────┘
  │                          (any membership change)
  └─────────────────── Dead (after offsets.retention.minutes)
```

- **Empty**: No active members
- **PreparingRebalance**: Collecting JoinGroupRequest from all members
- **CompletingRebalance**: Waiting for leader's SyncGroupRequest (assignment)
- **Stable**: Normal operation — processing, heartbeating, committing
- **Dead**: All members left, metadata can be cleaned up

---

### Partition Assignors

| Assignor | Balance | Rebalance type | Recommendation |
|---|---|---|---|
| `RangeAssignor` | Uneven (multi-topic) | Eager (stop-the-world) | Avoid |
| `RoundRobinAssignor` | Balanced | Eager | Better than Range |
| `StickyAssignor` | Balanced + minimal movement | Eager | Good |
| `CooperativeStickyAssignor` | Balanced + minimal movement | **Incremental** | **Use this** |

---

### Eager vs Cooperative Rebalance

**Eager** (RangeAssignor, RoundRobinAssignor, StickyAssignor):
```
ALL consumers stop processing
ALL partitions revoked (onPartitionsRevoked([all]))
JoinGroup → SyncGroup completes
ALL consumers resume (onPartitionsAssigned([new]))

Impact: 5-30 second processing gap on EVERY partition,
        even those whose assignment doesn't change.
```

**Cooperative** (CooperativeStickyAssignor):
```
ONLY partitions that must move are revoked
Other partitions keep processing uninterrupted
Two-phase JoinGroup/SyncGroup

Impact: Only affected partitions pause briefly.
        Example: 50-partition topic, 1 partition moves →
        49 partitions see zero interruption.
```

---

### Static Membership (`group.instance.id`)
Set to a stable identifier (e.g., pod name). Consumer reconnects within `session.timeout.ms` → gets back prior partitions without rebalance.

```java
props.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, System.getenv("POD_NAME"));
```

Rolling restart of 10 pods: **0 rebalances** (static) vs 10 rebalances (dynamic).

`consumer.close()` does NOT send `LeaveGroupRequest` in static mode — the assignment is held open for reconnect.

---

### session.timeout.ms
Default 45 seconds. **Server-side** timer on the Group Coordinator. If no heartbeat received within this window → consumer declared dead → rebalance triggered.

The **heartbeat thread** (background, independent of `poll()`) keeps the session alive even during slow processing. This timeout detects actual crashes (JVM killed, OOM, network partition) — NOT slow processing.

---

### max.poll.interval.ms
Default 5 minutes. **Client-side** enforcement. If `poll()` is not called within this window because processing is too slow → consumer proactively sends `LeaveGroupRequest` → rebalance.

```
Critical constraint:
  max.poll.records × avg_processing_time_per_record < max.poll.interval.ms

Example failure:
  max.poll.records=500, processing=1s each → 500s > 300s → rebalance triggered

Fix: Reduce max.poll.records to 50 OR increase max.poll.interval.ms to 600000
```

---

### Heartbeat Thread
Background daemon thread sending `HeartbeatRequest` every `heartbeat.interval.ms` (default 3s). Constraint: `heartbeat.interval.ms < session.timeout.ms / 3`. Completely decoupled from business logic. `HeartbeatResponse` delivers `REBALANCE_IN_PROGRESS` signal without waiting for next `poll()`.

---

### Consumer Lag
```
lag per partition = High Watermark - committed_offset
total group lag   = sum across all assigned partitions
```

| Lag pattern | Meaning |
|---|---|
| Small and stable | Healthy — consumer keeping up |
| Growing | Consumer slower than producer — alert! |
| Stalled committed offset | Consumer stuck — error loop or deadlock |

Alert on **rate of change**, not absolute value. A lag of 1M decreasing is healthier than a lag of 100 increasing.

---

### auto.offset.reset
**Only fires when NO committed offset exists** for a (group, partition) pair.

| Value | Behaviour | Risk |
|---|---|---|
| `latest` (default) | Start at High Watermark | Silently skips all historical messages |
| `earliest` | Start at Log Start Offset | Replays all retained history |
| `none` | Throw NoOffsetForPartitionException | Forces explicit handling |

If committed offsets exist → this setting is **completely ignored**. Always pre-set offsets for new groups before first deployment.

---

## 5. Offset Management

### Committed Offset
Durable consumer position checkpoint stored in `__consumer_offsets`. Represents the **next offset to consume** (last processed + 1). Written by `OffsetCommitRequest`, read by `OffsetFetchRequest` on restart or rebalance.

```
Processed offset 45899 → commit offset 45900
On restart: consumer resumes from 45900
```

---

### __consumer_offsets
Internal Kafka topic. 50 partitions (default), replication factor 3, cleanup policy: compact.

```
Key:   (groupId, topic, partition)
Value: (offset, leaderEpoch, metadata, commitTimestamp)
```

Stores both **committed offsets** AND **group metadata** (members, assignment, generation). Coordinator reads from this topic at startup (log replay) to restore in-memory state.

---

### enable.auto.commit
**Default `true` — the most dangerous Kafka default.** Commits current position on `poll()` — before your application finishes processing.

```
Timeline of the problem:
  poll() → [auto-commit fires: offsets committed] → process(A) → process(B) → CRASH
  On restart: consumer reads from after B — A and B permanently skipped (at-most-once)

Fix: Always set enable.auto.commit=false in production.
     Use commitSync() AFTER processing.
```

---

### commitSync()
Blocking offset commit. Retries automatically on retriable errors. Use after processing each batch. Also use in `onPartitionsRevoked()` before partition handoff.

```java
// After processing the batch:
consumer.commitSync();

// OR with explicit offsets:
consumer.commitSync(Map.of(
    new TopicPartition("orders", 2), new OffsetAndMetadata(record.offset() + 1)
));
```

---

### commitAsync()
Non-blocking offset commit. Returns immediately. **Do NOT retry in the callback** — a later commit may have already succeeded; retrying an older offset rolls back progress.

```java
// Safe production pattern:
while (true) {
    records = consumer.poll(Duration.ofMillis(100));
    process(records);
    consumer.commitAsync(); // fast, non-blocking
}
// On shutdown (in finally block):
consumer.commitSync(); // blocking, retrying, correct final state
```

---

### consumer.seek()
Override the fetch position for a specific partition. Effective immediately. **Must call inside `onPartitionsAssigned()`** to survive rebalances.

```java
consumer.subscribe(topics, new ConsumerRebalanceListener() {
    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        // Seek to DB-stored offsets for exactly-once with external system
        partitions.forEach(tp -> {
            Long dbOffset = offsetRepository.get(tp);
            if (dbOffset != null) consumer.seek(tp, dbOffset);
        });
    }
});
```

---

### offsetsForTimes()
Find the first offset with timestamp ≥ target. Binary-searches the `.timeindex` sparse index.

```java
Map<TopicPartition, OffsetAndTimestamp> result = consumer.offsetsForTimes(
    Map.of(tp, Instant.now().minus(30, MINUTES).toEpochMilli())
);
// Returns: first offset from 30 minutes ago
```

Used for disaster recovery and ad-hoc reprocessing from a known point in time.

---

### offsets.retention.minutes
How long committed offsets are kept after a group stops committing. Default 10080 (7 days). After expiry: group's offsets deleted, next start applies `auto.offset.reset`.

**Common trap**: A weekly batch job loses its position after 7 days. Fix: set `offsets.retention.minutes=43200` (30 days) on the broker.

---

## 6. Delivery Semantics

### At-Most-Once
Message delivered **0 or 1 times**. Loss possible. No duplicates.

```java
// Producer
props.put(ProducerConfig.ACKS_CONFIG, "0");
props.put(ProducerConfig.RETRIES_CONFIG, 0);

// Consumer
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);  // commits before processing
```

Use for: high-frequency telemetry, real-time dashboards, access logs — where occasional loss is acceptable and throughput is paramount.

---

### At-Least-Once
Message delivered **one or more times**. No loss. Duplicates possible.

```java
// Producer
props.put(ProducerConfig.ACKS_CONFIG, "all");
props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);  // dedup producer retries

// Consumer
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
// After processing:
consumer.commitSync();
```

Consumer must be idempotent: `ON CONFLICT DO NOTHING`, upsert by business key, or offset-keyed dedup. **Standard for most production workloads.**

---

### Exactly-Once (EOS)
Message delivered **exactly one time**. No loss. No duplicates. **Within Kafka only** — external systems need Outbox pattern or DB-stored offsets.

```java
// Producer
props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "processor-" + podName);
// Consumer
props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

// Processing loop:
producer.beginTransaction();
producer.send(new ProducerRecord<>("output-topic", key, value));
producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
producer.commitTransaction();  // atomic: output visible + offset committed
```

Cost: ~15% throughput reduction vs at-least-once.

---

### transactional.id
Unique, stable identifier per logical producer instance. Enables:
1. **Kafka transactions** — atomic multi-partition writes
2. **Zombie fencing** — `initTransactions()` increments epoch, old instances get `ProducerFencedException`

```
CORRECT: "payment-processor-" + System.getenv("POD_NAME")
         (stable, unique per pod — fencing works correctly)

WRONG:   "producer-" + UUID.randomUUID()
         (new ID on every restart — no fencing, no dedup)
```

---

### sendOffsetsToTransaction()
Atomically ties the consumer offset commit to the current transaction.

```
If commitTransaction() → output record visible AND offset committed
If abortTransaction()  → output invisible AND offset NOT committed → consumer replays
```

Must pass `consumer.groupMetadata()` (includes `generationId`) — not just `group.id`. The `generationId` detects stale commits if a rebalance happened mid-transaction.

---

### isolation.level

| Value | Sees | Consumer reads up to |
|---|---|---|
| `read_uncommitted` (default) | All records + open/aborted transactions | High Watermark |
| `read_committed` | Only committed transaction records; aborted = skipped | Last Stable Offset |

Set `read_committed` when consuming from topics written by transactional producers.

---

### Transaction Coordinator
Broker leading the `__transaction_state` partition for a given `transactional.id`. Drives the two-phase commit:

```
Phase 1: Write PREPARE_COMMIT to __transaction_state  ← DURABILITY POINT
          (transaction will complete even if coordinator crashes after this)
Phase 2: Write COMMIT markers to all involved partitions
Final:    Write COMPLETE_COMMIT to __transaction_state
```

---

### PREPARE_COMMIT
The **durability point** of a Kafka transaction. Written to `__transaction_state` before COMMIT markers are sent to partitions. After this write, the transaction WILL commit even if the coordinator crashes mid-execution — recovery reads this state and completes the work.

---

### COMMIT / ABORT Markers
Control records written by the Transaction Coordinator to every partition involved in a transaction.

- **COMMIT marker**: All preceding records in this transaction become visible to `read_committed` consumers
- **ABORT marker**: All preceding records permanently skipped by `read_committed` consumers

Markers remain in the partition log permanently — filtered client-side by the consumer library, not deleted from broker storage.

---

### Outbox Pattern
The solution for exactly-once semantics when Kafka is the sink (Kafka → Database consistency problem).

```
WRONG (dual-write problem):
  Write to DB → Write to Kafka → inconsistency if either fails

CORRECT (Outbox):
  Single DB transaction:
    INSERT INTO orders (...)      ← business data
    INSERT INTO outbox (topic, key, payload)  ← event to publish
  COMMIT;
  
  Separate outbox publisher:
    Read from outbox table
    Publish to Kafka (idempotently)
    Mark as published
```

Guarantees DB and Kafka are always consistent — they share the same ACID transaction boundary.

---

## 7. Replication

### Replication Factor
Number of copies of each partition. Always 3 in production.

```
RF=1: data loss on ANY broker failure
RF=2: no redundancy while replacement syncs (avoid)
RF=3: survives 2 simultaneous broker failures (standard)

Write amplification: 100 MB/s × RF=3 = 300 MB/s inter-broker replication traffic
```

---

### ISR (In-Sync Replicas)
The set of replicas currently caught up with the leader. A replica stays in-sync by fetching within `replica.lag.time.max.ms` (default 30s).

```
ISR = {broker-0 (leader), broker-1, broker-2}
HW = min(LEO across all ISR members)

If broker-2 falls behind:
  ISR = {broker-0, broker-1}
  HW = min(leader_LEO, broker-1_LEO)

If broker-1 also falls behind:
  ISR = {broker-0}
  With min.insync.replicas=2: writes REJECTED (NotEnoughReplicasException)
```

---

### LEO (Log End Offset)
The **next offset to be written** on a specific replica. Each replica has its own LEO. Leader's LEO is always the highest. Followers advance their LEO as they replicate. The leader tracks follower LEOs via the `fetchOffset` in each `FetchRequest` received from followers.

---

### HW (High Watermark)
The minimum LEO across all current ISR members. **Consumers can only read up to the HW.**

```
Leader:    LEO=1000001
Follower1: LEO=1000001  (fully caught up)
Follower2: LEO=1000000  (one batch behind)
HW = min(1000001, 1000001, 1000000) = 1000000

Consumer fetch: only offsets 0–999999 are visible
When Follower2 replicates: HW advances to 1000001
```

Without HW: consumer could read data that the new leader (after failover) doesn't have.

---

### LSO (Last Stable Offset)
HW bounded by the lowest open transaction. `read_committed` consumers read up to LSO, not HW.

```
Partition: [0][1][2][TXN_START:3...open...][8][9]   HW=9, LSO=2

read_uncommitted consumer: reads offsets 0-9
read_committed consumer:   reads offsets 0-2 (blocked by open txn at 3)
```

A stuck open transaction blocks ALL `read_committed` consumers on that partition. Set `transaction.timeout.ms` (default 60s) to auto-abort stuck transactions and unblock LSO.

---

### ReplicaFetcherThread
Background thread on each follower, continuously pulling from the leader.

```
Loop:
  fetchOffset = my_current_LEO
  response = leader.fetch(topic, partition, fetchOffset, maxBytes=1MB)
  write response.records to my_own_log (page cache)
  advance my LEO
  repeat
```

Leader tracks follower progress via the `fetchOffset` in each request. This is how the leader knows when to advance HW. `num.replica.fetchers` (default 1) — increase to 4 for high-throughput clusters.

---

### Leader Epoch
Monotonically increasing counter per partition leadership change. Written into `RecordBatch` headers. Used during crash recovery: followers use the leader epoch history to identify and truncate data written by a prior leader that was never fully replicated.

---

### min.insync.replicas
Minimum ISR replicas required for `acks=all` writes to succeed. Default 1 is **unsafe**.

```
Production standard: RF=3, min.insync.replicas=2, acks=all

ISR shrinks to 1 → NotEnoughReplicasException → partition write-protected
This is CORRECT behavior — prevents writing data only 1 replica has (single point of failure)
```

---

### unclean.leader.election.enable
```
false (default, production): All ISR members offline → partition stays OFFLINE until recovery
                              No data loss. Preferred.

true:  Out-of-ISR replica becomes leader → partition recovers
       BUT messages only on old leader are PERMANENTLY LOST
       Use only for non-critical topics where availability > durability.
```

---

### Preferred Leader
The first replica in the partition's assignment list. Kafka periodically rebalances leadership back to preferred leaders (`auto.leader.rebalance.enable=true` default) after broker recovery, preventing one broker from becoming leader of all partitions after another broker repeatedly fails.

---

## 8. Performance & I/O

### Compression
Applied by producer per batch. Batch-level compression is far more effective than per-message due to cross-message redundancy (shared JSON field names, similar timestamps, correlated values).

| Codec | Ratio | CPU | Best for |
|---|---|---|---|
| `none` | 1x | None | Development only |
| `lz4` | ~3x | Very low | General production default |
| `snappy` | ~2.5x | Low | CPU-constrained producers |
| `zstd` | ~5x | Moderate | Bandwidth-constrained or cross-DC |
| `gzip` | ~4x | High | Maximum compression, archival |

Use `compression.type=producer` on the topic (default) — broker stores as-is, no recompression CPU cost.

---

### Fetch Pipelining
Up to `max.in.flight.requests.per.connection=5` unacknowledged `ProduceRequest`s per broker connection. The Sender thread fires new requests without waiting for each ack. NIO selector handles concurrent sends and receives simultaneously. A major contributor to Kafka's throughput advantage.

With `enable.idempotence=true`: safe up to 5 (ordering maintained). Without: set to 1 to preserve ordering across retries.

---

### fetch.min.bytes + fetch.max.wait.ms
Broker-side fetch batching controlling latency vs throughput.

```
fetch.min.bytes=1 (default):      respond immediately with any data → low latency
fetch.min.bytes=1048576 (1 MB):   wait until 1 MB ready → high throughput
fetch.max.wait.ms=500 (default):  max wait even if min.bytes not met

Profiles:
  Real-time consumer: fetch.min.bytes=1,       fetch.max.wait.ms=100
  Batch consumer:     fetch.min.bytes=1048576, fetch.max.wait.ms=1000
```

---

### num.io.threads / num.network.threads
```properties
num.network.threads=3   # socket I/O: accept connections, read request bytes, write response bytes
num.io.threads=8        # request processing: parse, validate CRC+auth, write to log, build response
```

If network threads saturated → increase `num.network.threads`. If I/O threads saturated → increase `num.io.threads` or improve disk throughput (NVMe upgrade).

---

### num.replica.fetchers
Number of `ReplicaFetcherThread` instances per follower broker (default 1). Each thread handles multiple partitions but sequentially. A single thread can become a replication bottleneck for high-throughput clusters. Increase to 4 if `UnderReplicatedPartitions` JMX metric is non-zero without an obvious disk issue.

---

## 9. Security

### SASL (Authentication)

| Mechanism | Credentials | Use case |
|---|---|---|
| `PLAIN` | Username/password (plaintext) | Simple — use with SSL |
| `SCRAM-SHA-256/512` | Challenge-response, creds in ZK/KRaft | Secure password auth |
| `GSSAPI` | Kerberos tickets | Enterprise / Active Directory |
| `OAUTHBEARER` | JWT tokens | Cloud-native, microservices |

```properties
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-256
```

---

### SSL/TLS
Encrypts all traffic. Trade-off: **disables zero-copy** (data must enter JVM for encryption/decryption). Expect ~30% throughput reduction vs plaintext. Use dedicated keystore/truststore JKS files. Configure both listeners (client-facing) and inter-broker protocol separately.

---

### ACLs
Fine-grained authorization. Resource types: `TOPIC`, `GROUP`, `CLUSTER`, `TRANSACTIONAL_ID`. Operations: `READ`, `WRITE`, `CREATE`, `DELETE`, `ALTER`, `DESCRIBE`, `ALL`.

```bash
kafka-acls.sh --bootstrap-server broker:9092 \
  --add --allow-principal User:order-service \
  --operation READ --topic orders \
  --operation READ --group order-service-prod
```

Default `allow.everyone.if.no.acl.found=true` — all access allowed without ACLs. Set to `false` for production.

---

### Quotas
Per-client byte-rate limits. Types: `producer_byte_rate`, `consumer_byte_rate`, `request_percentage` (CPU). Broker **throttles** (delays responses) rather than dropping requests — client experiences slow responses, not errors. Prevents one heavy producer from starving others.

```bash
kafka-configs.sh --bootstrap-server broker:9092 \
  --entity-type clients --entity-name heavy-producer \
  --alter --add-config producer_byte_rate=10485760  # 10 MB/s
```

---

## 10. Mental Models & Diagrams

### The full data flow

```
PRODUCER                      BROKER CLUSTER                     CONSUMER
────────                      ──────────────                     ────────
send(record)
    │
Partitioner: key hash / sticky
    │
Serializer: key → bytes, value → bytes
    │
RecordAccumulator: add to batch[partition]
    │
Sender Thread (background):
    │──ProduceRequest──────────►Partition Leader
    │                                │
    │                           Write to page cache (RAM)
    │                           LEO advances
    │                                │
    │                           Followers fetch──────►Follower-1 (replicate)
    │                                │               ►Follower-2 (replicate)
    │                           HW advances (min LEO across ISR)
    │                                │
    │◄─ProduceResponse───────────────┘  (after HW advance with acks=all)
Future.complete()
Callback.fire()
                                                    ConsumerCoordinator:
                                                      FindCoordinator
                                                      JoinGroup → SyncGroup
                                                      Assigned: [orders-2, orders-3]
                                                      OffsetFetch: start at offset 45892

                                                    HeartbeatThread (background):
                                                      every 3s → Coordinator

                                                    Fetcher:
                                                      FetchRequest ──► orders-2 leader
                                                      FetchRequest ──► orders-3 leader
                                                          │
                                                     Zero-copy: sendfile()
                                                     page cache → NIC → consumer
                                                          │
                                                    process(records)
                                                    commitSync() ──► Coordinator
                                                                 ──► __consumer_offsets
```

---

### The ISR / LEO / HW / LSO relationship

```
Partition log (offsets):
  0────────500────────1000────────[TXN@1001]────────2000
  │                    │                              │
  LSO=1000             HW=1000                       LEO=2000
  (open txn blocks)   (min LEO of ISR)           (next to write)

read_committed consumer sees: 0–999
read_uncommitted consumer sees: 0–1999 (including open txn records)
```

---

### Delivery semantics decision tree

```
Is message loss acceptable?
├── Yes → At-most-once (acks=0, auto-commit)
└── No
    ├── Are duplicates acceptable (with idempotent consumer)?
    │   └── Yes → At-least-once (acks=all + commitSync after processing)
    └── No duplicates allowed
        ├── Output is Kafka topic?
        │   └── Yes → Kafka Transactions (transactional.id + sendOffsetsToTransaction)
        └── Output is external DB/API?
            ├── DB supports ACID? → Store offset in same DB transaction
            └── No ACID?          → Outbox pattern + idempotent writes
```

---

## 11. Interview Quick-Fire Reference

### The six concepts asked in almost every senior interview

| Concept | Key insight |
|---|---|
| ISR + HW + LEO | HW = min(LEO across ISR). Consumers read up to HW. Ensures consumers never read unreplicated data. |
| Idempotent producer | PID + sequence numbers. Broker deduplicates retries silently. Zero cost. Default since Kafka 3.0. |
| Kafka transactions (2PC) | PREPARE_COMMIT is the durability point. COMMIT/ABORT markers control consumer visibility. |
| Cooperative rebalance | Only partitions that must move are revoked. Others keep processing. No stop-the-world. |
| Page cache + zero-copy | OS page cache is the real cache. sendfile() serves consumers from RAM → NIC, bypassing JVM. |
| Group Coordinator | Controls group membership, not message data. Determined by hash(groupId) % 50. |

---

### Six most common interview traps

| Question | Wrong answer | Correct answer |
|---|---|---|
| "Does acks=all prevent duplicates?" | "Yes" | No — prevents loss only. Duplicates need enable.idempotence=true |
| "Does auto.commit give at-least-once?" | "Yes" | No — at-most-once (commits before processing, loss on crash) |
| "What does auto.offset.reset=latest do?" | "Always start from end" | Only when no committed offset exists. Ignored otherwise. |
| "Does heartbeat detect slow processing?" | "Yes" | No — session.timeout.ms detects crashes. max.poll.interval.ms detects slow processing. |
| "Does Group Coordinator route messages?" | "Yes" | No — FetchRequest bypasses coordinator, goes directly to partition leader. |
| "Do Kafka transactions guarantee exactly-once with PostgreSQL?" | "Yes" | No — Kafka-only. Use Outbox pattern or DB-stored offsets for cross-system EOS. |

---

### Key formulas

```
Group Coordinator partition    = abs(groupId.hashCode())         % offsets.topic.num.partitions (50)
Transaction Coordinator part.  = hash(transactional.id)          % transaction.state.log.num.partitions (50)
Partition from key             = murmur2(keyBytes)               % numPartitions
HW                             = min(LEO across all ISR members)
Consumer lag (per partition)   = High Watermark - committed_offset
Max safe records per poll()    = max.poll.interval.ms            / avg_processing_time_per_record
Heartbeat constraint           = heartbeat.interval.ms           < session.timeout.ms / 3
Total disk per broker          = write_rate × retention_seconds × replication_factor / num_brokers
```

---

### Configuration cheat sheet

```properties
# Producer — production baseline
acks=all
enable.idempotence=true
compression.type=lz4
batch.size=131072
linger.ms=5
buffer.memory=67108864
retries=2147483647
delivery.timeout.ms=120000

# Consumer — production baseline
enable.auto.commit=false
auto.offset.reset=latest
max.poll.records=500
max.poll.interval.ms=300000
session.timeout.ms=45000
heartbeat.interval.ms=15000
partition.assignment.strategy=org.apache.kafka.clients.consumer.CooperativeStickyAssignor
isolation.level=read_committed  # only when consuming from transactional topics

# Broker — production baseline
num.network.threads=3
num.io.threads=8
num.replica.fetchers=2
log.retention.hours=168
log.segment.bytes=1073741824
offsets.topic.replication.factor=3
transaction.state.log.replication.factor=3
min.insync.replicas=2
unclean.leader.election.enable=false
auto.create.topics.enable=false
```

---

*Everything in Kafka connects: sequential I/O enables the page cache strategy, which enables zero-copy, which enables the throughput that makes replication cheap, which enables the ISR model, which enables the HW fence, which enables the delivery semantics that producers and consumers rely on. Pull on any thread and you find all the others.*
