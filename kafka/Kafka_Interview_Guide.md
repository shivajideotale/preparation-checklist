# Kafka Interview Guide — Senior Java Backend Engineer (20 Years Experience)

> 25 deep-dive questions across 5 topic areas. Every answer covers internals, trade-offs, and production nuances expected at the senior/staff level.

---

## Table of Contents

1. [Core Internals](#1-core-internals)
2. [Producer & Consumer](#2-producer--consumer)
3. [Architecture & Design](#3-architecture--design)
4. [Operations & Reliability](#4-operations--reliability)
5. [Kafka Streams & Java](#5-kafka-streams--java)

---

## 1. Core Internals

---

### Q1. Explain how Kafka achieves high throughput — cover page cache, zero-copy, batching, and sequential I/O together.

Kafka's exceptional throughput (millions of messages/sec on commodity hardware) is not from a single trick — it's from four cooperating mechanisms that stack on top of each other.

#### Sequential I/O
Traditional message brokers store messages in random-access data structures (B-trees, hash indexes). Random disk I/O on spinning disks yields ~100 IOPS. Sequential disk I/O on the same hardware yields ~200 MB/s. Kafka stores all messages in an **append-only log** — every write goes to the end of the current active segment. Reads are always sequential scans. This allows Kafka to exploit disk's maximum bandwidth rather than its IOPS ceiling. Even on SSDs, sequential access is faster because it reduces write amplification and leverages hardware prefetch.

#### OS Page Cache
Rather than managing its own in-process cache (like most databases), Kafka relies entirely on the **Linux OS page cache**. When a producer writes a message, the OS writes it to the page cache and marks it dirty — the actual disk flush happens asynchronously. When a consumer reads a message that was recently written, it often never touches disk at all: the OS serves it directly from RAM (page cache hit).

The JVM heap is avoided intentionally. A 32 GB JVM heap incurs GC pauses and object overhead. The OS page cache for the same 32 GB is managed efficiently by the kernel, persists across Kafka restarts, and requires no GC. A broker restart means the page cache is already warm — consumers pick up from cache immediately.

#### Zero-Copy Transfer
Without zero-copy, the path from disk to network socket is:
1. Read data from disk → kernel buffer
2. Copy kernel buffer → user-space application buffer (JVM)
3. Copy user-space buffer → kernel socket buffer
4. Send socket buffer → NIC

That's **4 context switches and 2 extra data copies** involving user space. Kafka uses the `sendfile()` Linux system call (exposed via Java's `FileChannel.transferTo()`), which transfers data directly from the OS page cache to the network socket **without ever entering user space**:

1. Read data from disk → kernel page cache
2. Copy page cache → socket buffer (kernel to kernel, DMA)
3. Send socket buffer → NIC

This eliminates the 2 user-space copies entirely, halving memory bandwidth for consumer reads.

#### Batching
Kafka batches at multiple levels:
- **Producer side**: `linger.ms` delays sending to accumulate a batch. `batch.size` sets the maximum batch bytes. One batch = one network round trip for potentially thousands of messages.
- **Consumer side**: `fetch.min.bytes` / `fetch.max.wait.ms` cause the broker to hold the fetch response until enough data accumulates. One fetch response = thousands of messages.
- **Compression**: Applied per-batch. A batch of 1000 JSON messages compresses far better than 1000 individual messages (cross-message redundancy). LZ4 gives ~3x compression with minimal CPU; ZSTD gives ~5x with moderate CPU.

**Combined effect**: A single network call carries thousands of messages, compressed, served from RAM, transferred via zero-copy. This is why Kafka saturates a 10 GbE link before it saturates CPU or disk.

---

### Q2. How does Kafka's Log Compaction work, and when would you choose it over retention by time or size?

#### Retention Policies
Kafka supports three strategies for managing log size:

- **Time-based retention** (`retention.ms`): Delete log segments older than N ms. Messages are eventually gone.
- **Size-based retention** (`retention.bytes`): Delete oldest segments when total log size exceeds N bytes.
- **Log compaction** (`cleanup.policy=compact`): Retain the **latest value for each key**. Old values for the same key are deleted, but the latest is kept forever (until a new value or tombstone).

#### How Compaction Works Internally
Kafka's **log cleaner** (a background thread pool) periodically scans the log. The log is divided into two sections:

- **Clean portion**: Already compacted. Guaranteed to contain at most one record per key.
- **Dirty portion**: Uncompacted new records. May contain multiple records for the same key.

The cleaner reads the dirty portion, builds an in-memory offset map (key → latest offset), then rewrites segments retaining only the latest record per key. Older duplicates are dropped.

**Tombstone records**: To delete a key entirely, produce a record with `value=null`. This is a tombstone. It propagates to all consumers. After `delete.retention.ms` (default 24h), the tombstone itself is deleted during the next compaction pass — giving consumers time to observe the deletion.

**Guarantees**:
- At least the most recent value per key is always retained.
- Message ordering is preserved within a partition.
- Consumer offsets are not invalidated — compacted records simply skip forward.

#### When to Use Log Compaction
Choose compaction when **consumers need current state, not full history**:

- **Change Data Capture (CDC)**: A DB change topic compacted by primary key. A new service joining late can replay the compact log to reconstruct current DB state rather than processing years of changes.
- **Event sourcing state snapshots**: A `account-balances` topic keyed by account ID. Consumers always see the current balance without replaying every transaction.
- **Configuration/metadata topics**: Service config distributed via Kafka. New service instances read the compacted log to get current config.
- **Kafka Streams KTable changelogs**: Compaction keeps changelogs small, reducing restore time after a stateful processor restarts.

Avoid compaction for: audit logs (you need all events), time-series analytics (history matters), topics where keys are not meaningful or unique.

---

### Q3. Walk me through exactly what happens — broker by broker — when a producer sends a message with acks=all to a topic with replication factor 3.

Assume: topic `orders`, partition 0, leader on Broker 1, followers on Broker 2 and Broker 3. ISR = {Broker1, Broker2, Broker3}.

#### Step-by-Step

1. **Producer → Leader (Broker 1)**: Producer sends a `ProduceRequest` to Broker 1 (the partition leader). The message is written to Broker 1's local log and appended to the partition's active segment on disk (actually to the page cache; fsync is async).

2. **Leader increments LEO**: Broker 1 updates its Log End Offset (LEO) for the partition.

3. **Followers fetch**: Broker 2 and Broker 3 each maintain a `ReplicaFetcher` thread that continuously polls the leader using `FetchRequest`. They fetch the new record and write it to their own local logs, updating their own LEO.

4. **Followers acknowledge via fetch**: Followers don't send an explicit ack. Instead, when Broker 2's `FetchRequest` includes a `fetchOffset` equal to or beyond the new record's offset, the leader knows Broker 2 has replicated it. Same for Broker 3.

5. **High Watermark advances**: Once all ISR members (Broker 1, 2, 3) have their LEO at or beyond the new record, the leader advances the **High Watermark (HW)** to cover that record. The HW is the highest offset that all ISR replicas have replicated.

6. **Leader responds to producer**: With `acks=all`, Broker 1 sends a successful `ProduceResponse` to the producer **only after the HW has advanced past the produced record's offset** — meaning all ISR replicas confirmed replication.

7. **Consumer visibility**: The record is now visible to consumers (consumers only read up to the HW).

#### acks=1 vs acks=all vs acks=0
- `acks=0`: Producer fires and forgets. No response awaited. Max throughput, zero durability.
- `acks=1`: Leader writes to its log and acks immediately. If leader crashes before followers replicate, data is lost.
- `acks=all` (also `-1`): Leader waits for all ISR members to replicate. Combined with `min.insync.replicas=2`, this ensures at least 2 replicas have the data before ack. Safe but adds ~1 RTT of latency (follower fetch cycle).

#### ISR and Slow Followers
If a follower hasn't fetched within `replica.lag.time.max.ms` (default 30s), it's removed from the ISR. Now `acks=all` only waits for the remaining ISR members. This preserves availability at the cost of a weaker durability guarantee — if the leader crashes, the lagging follower (out of ISR) might become leader and miss recent messages.

---

### Q4. What is the role of the Controller in Kafka? What happens if it dies, and how is a new one elected?

#### The Controller's Responsibilities
In any Kafka cluster, one broker is designated the **Controller**. It is responsible for:

- **Partition leader election**: When a partition's leader broker goes down, the Controller selects a new leader from the ISR and broadcasts the change to all brokers via `LeaderAndIsrRequest`.
- **ISR management**: Monitoring replica lag and updating the ISR set for each partition.
- **Broker lifecycle**: Detecting broker joins and departures (via ZooKeeper watches or Raft heartbeats in KRaft).
- **Topic/partition metadata management**: Propagating topic creation, deletion, and partition reassignment metadata to brokers.
- **Epoch tracking**: Controller epoch increments on each new controller election, preventing split-brain commands from zombie controllers.

#### ZooKeeper-based Election (Classic Mode)
Each broker tries to create an ephemeral ZNode `/controller` in ZooKeeper. The first one to succeed becomes the Controller. All other brokers watch this ZNode. When the Controller dies, ZooKeeper deletes the ephemeral node (after session timeout), and all watchers trigger a new race to create `/controller`. The winner becomes the new Controller.

**Downside**: ZooKeeper election involves session timeouts (default 6–18 seconds). During this window, no leader elections or ISR updates happen. A sudden broker failure during high load + controller failure = compounded outage window.

#### KRaft Mode (Kafka 3.3+, production-ready; ZK removed in Kafka 4.0)
KRaft replaces ZooKeeper with a **Raft consensus group** of controller nodes (typically 3 or 5). One controller is the active **Quorum Leader**, others are **Quorum Followers**. Metadata is stored in an internal `@metadata` topic replicated across the quorum.

- Election: Raft leader election using term numbers. A candidate requests votes; becomes leader with majority quorum.
- Faster: Raft detects failure in milliseconds (configurable heartbeat), not ZK session timeout seconds.
- No external dependency: No ZooKeeper cluster to manage, monitor, or secure separately.
- Controllers can be co-located with brokers or run as dedicated nodes.

**In interview context**: Know both models. Understand that KRaft was motivated by ZK's operational complexity, the controller's ZK bottleneck at large partition counts (10k+ partitions), and the need for a single consistent metadata store.

---

### Q5. Describe the difference between Kafka's ISR and HW. What consumer isolation guarantees does this create?

#### In-Sync Replicas (ISR)
The ISR is the **set of replicas that are considered "caught up" with the leader**. A replica is in-sync if it has fetched all messages up to the leader's LEO within `replica.lag.time.max.ms` (default 30 seconds). The leader tracks each follower's fetch progress.

The ISR is persisted in ZooKeeper (or the `@metadata` log in KRaft) and updated dynamically:
- A slow/dead follower is **removed** from the ISR after `replica.lag.time.max.ms`.
- A recovered follower is **added back** once it catches up to within the lag threshold.

#### High Watermark (HW)
The HW is the **highest offset that all current ISR replicas have written to their local log**. It represents the boundary of "committed" messages — data guaranteed to survive any single broker failure without data loss.

- `LEO` (Log End Offset): The next offset to be written on a replica. Each replica has its own LEO.
- `HW`: `min(LEO across all ISR members)`. Only the leader's HW is authoritative.

#### Consumer Isolation Guarantee
**Consumers can only read up to the High Watermark.** This is critical: it prevents a consumer from reading data that might be "rolled back" if the leader crashes and an out-of-sync replica takes over.

Example scenario without this guarantee:
1. Leader writes offset 100. Follower hasn't replicated yet (LEO=99).
2. Consumer reads offset 100.
3. Leader crashes. Follower (LEO=99) becomes leader.
4. Consumer's offset 100 is now "lost" — the new leader doesn't have it.
5. If the consumer committed offset 100, it will skip this message forever.

The HW fence prevents step 2: consumers only see offsets that all ISR members have confirmed.

#### Practical Implications
- **Read latency**: Consumers always lag behind producers by at least one replication cycle (the time for followers to fetch and advance HW). With fast networks this is <10ms.
- **ISR shrinkage risk**: If all ISR members are offline and `unclean.leader.election.enable=true` (default false in newer Kafka), an out-of-ISR replica can become leader. This is an availability-over-durability trade-off — you may serve older data and potentially lose messages. Most production configs set this to `false`.
- `min.insync.replicas`: If ISR shrinks below this threshold, the partition becomes **read-only** (producers get `NotEnoughReplicasException`). This prevents writes that only one replica would receive, which would be lost on that replica's failure.

---

## 2. Producer & Consumer

---

### Q6. A producer is experiencing high latency spikes. Walk me through every tunable — batch.size, linger.ms, buffer.memory, compression.type, max.block.ms — and the trade-offs.

#### The Producer's Internal Flow
The Java `KafkaProducer` is asynchronous internally:
1. `send()` serializes the record and places it into a per-partition **accumulator** (deque of `RecordBatch` objects).
2. A background **Sender thread** drains batches and sends `ProduceRequest`s to brokers.
3. The caller's `Future<RecordMetadata>` completes when the broker acks.

#### batch.size (default: 16384 bytes = 16 KB)
Maximum bytes per batch for a single partition. The Sender will send a batch once it reaches `batch.size` **or** `linger.ms` expires (whichever comes first).

- **Too small**: Lots of tiny batches, many network round trips, poor compression, high broker CPU from many small requests.
- **Too large**: Batches fill slowly under low load (rely on linger.ms), increased memory usage, larger in-flight messages if sending to many partitions.
- **Sweet spot for high throughput**: 64KB–1MB. For low-latency, keep it small so `linger.ms=0` sends immediately without waiting.

#### linger.ms (default: 0 ms)
How long the Sender waits before sending an incomplete batch. At `0`, the Sender sends the batch as soon as the Sender thread runs (nearly immediate). At `5`, it waits up to 5ms to accumulate more records.

- `linger.ms=0`: Minimum latency, poor batching under burst load.
- `linger.ms=5–20`: Dramatically improves batching and throughput with negligible latency impact for most applications. A 5ms delay is imperceptible for most use cases and can increase throughput 10x under high load.
- **Latency spike cause**: If `linger.ms` is too high and traffic is bursty, records queue up in the accumulator longer than expected.

#### buffer.memory (default: 33554432 bytes = 32 MB)
Total memory allocated to the producer's record accumulator (all partitions combined). If the accumulator is full (back-pressure from slow broker or slow Sender), `send()` **blocks** the calling thread for up to `max.block.ms`.

- Latency spike cause: `buffer.memory` too small → `send()` blocks waiting for space.
- Fix: Increase `buffer.memory`, reduce `batch.size`, or address the downstream slowness (broker overloaded, network congestion).

#### compression.type (default: none)
Options: `none`, `gzip`, `snappy`, `lz4`, `zstd`.

Compression is applied per-batch in the producer, sent compressed to the broker, stored compressed on disk, and decompressed by the consumer.

| Codec  | Compression Ratio | CPU Cost | Latency Impact |
|--------|------------------|----------|----------------|
| none   | 1x               | none     | none           |
| lz4    | ~3x (text)       | low      | +0–2ms         |
| snappy | ~3x (text)       | low      | +0–2ms         |
| gzip   | ~5x (text)       | high     | +5–20ms        |
| zstd   | ~5x (text)       | moderate | +2–5ms         |

- **Latency spike cause**: `gzip` on a CPU-constrained producer under burst load.
- **Recommendation**: `lz4` for latency-sensitive; `zstd` for bandwidth-constrained networks.

#### max.block.ms (default: 60000 ms = 60 seconds)
How long `send()` and `partitionsFor()` block when the buffer is full or metadata is unavailable. After this timeout, a `TimeoutException` is thrown.

- Too long: Threads pile up waiting, causing cascading latency spikes throughout the application.
- Too short: Transient broker hiccups cause producer exceptions.
- **Latency spike cause**: `buffer.memory` full → `send()` blocks for up to `max.block.ms`.

#### Holistic Tuning Strategy
For **high throughput**: Increase `batch.size` to 256KB–1MB, set `linger.ms=5–20`, use `lz4` or `zstd`, ensure `buffer.memory` is large enough (>= 2x `batch.size * num_partitions`).

For **low latency**: Keep `linger.ms=0`, `batch.size=16KB`, `compression=none` or `lz4`, increase `buffer.memory` to absorb bursts.

---

### Q7. Explain idempotent producers and exactly-once semantics (EOS) in Kafka. What are the limitations of each?

#### The Problem: Duplicate Writes
Without idempotency, if a producer sends a batch and the broker writes it but the ack is lost (network failure), the producer retries and the broker writes the message again. This creates duplicates in the log.

#### Idempotent Producer (enable.idempotence=true)
When idempotency is enabled:
- The broker assigns the producer a **Producer ID (PID)**.
- Each message gets a monotonically increasing **sequence number** per partition.
- The broker tracks the last sequence number per (PID, partition) pair.
- On retry, the broker detects the duplicate sequence number and **ignores the duplicate write**, returning a success to the producer.

This guarantees **exactly-once delivery within a single partition** for a single producer session. If the producer restarts (new PID), idempotency resets — a retry from a restarted producer can still duplicate.

**Automatically sets**: `acks=all`, `retries=Integer.MAX_VALUE`, `max.in.flight.requests.per.connection=5` (safe for ordering with idempotency).

#### Exactly-Once Semantics (EOS) — Transactions
Idempotency per-partition isn't enough for:
- **Multi-partition atomic writes**: Produce to partitions A and B either both succeed or both fail.
- **Read-process-write** (Kafka Streams): Consume from topic A, transform, produce to topic B — all atomically.

EOS uses **Kafka Transactions**:

```java
producer.initTransactions();
try {
    producer.beginTransaction();
    producer.send(new ProducerRecord<>("output-topic", key, value));
    producer.sendOffsetsToTransaction(offsets, groupMetadata); // atomic offset commit
    producer.commitTransaction();
} catch (ProducerFencedException e) {
    producer.close();
} catch (KafkaException e) {
    producer.abortTransaction();
}
```

**Mechanism**:
1. `transactional.id` is set. The broker uses this to fence zombie producers (prevents two instances of the same producer ID from running simultaneously).
2. `beginTransaction()` marks the start.
3. Messages are written to partitions with a marker that they're part of transaction T.
4. `commitTransaction()` writes a **Commit marker** to all involved partitions. Only then do consumers with `isolation.level=read_committed` see the messages.
5. `abortTransaction()` writes an **Abort marker** — consumers skip all messages from that transaction.

**Consumer side**: Set `isolation.level=read_committed`. The consumer buffers transactional messages until it sees the Commit or Abort marker before delivering them.

#### Limitations

**Idempotent producer limitations**:
- Per-session only. New producer instance = new PID = idempotency reset.
- Per-partition only. Doesn't coordinate across partitions.
- Doesn't protect against: consumer duplicates, external system duplicates.

**EOS limitations**:
- **Kafka-only**: Atomicity is only within Kafka. Writing to a database inside a transaction is not atomic with the Kafka commit — you need the Outbox pattern or idempotent DB writes.
- **Performance overhead**: ~20% throughput reduction due to two-phase commit, transaction log writes, and buffering.
- **Throughput limit**: Historically ~5,000 transactions/sec per broker (improved in newer versions).
- **Latency increase**: Consumers see messages only after commit marker is received. For long-running transactions, this can be significant.
- **Zombie fencing**: Requires unique `transactional.id` per logical producer. If two instances share an ID, the older one is fenced (gets `ProducerFencedException`) — intentional but must be handled.

---

### Q8. A consumer group has 6 consumers and 4 partitions. What happens? What about 4 consumers and 6 partitions?

#### Scenario 1: 6 Consumers, 4 Partitions
Kafka's fundamental rule: **each partition is assigned to at most one consumer per group**.

With 4 partitions and 6 consumers:
- 4 consumers each get assigned 1 partition.
- 2 consumers are **idle** — they poll but never receive any messages.

The idle consumers are not wasted in all senses — they serve as **hot standbys**. If one of the 4 active consumers dies, rebalance kicks in and an idle consumer takes over the partition immediately (no need to spin up a new instance). This is a valid HA pattern.

**What not to do**: Don't deploy more consumers than partitions thinking it increases parallelism — it doesn't. It wastes resources and adds rebalance overhead.

#### Scenario 2: 4 Consumers, 6 Partitions
With 6 partitions and 4 consumers:
- **RangeAssignor** (default): Partition assignment is per-topic. Consumers sorted lexicographically. Partitions 0,1 → Consumer 0; Partitions 2,3 → Consumer 1; Partition 4 → Consumer 2; Partition 5 → Consumer 3. Some consumers get more partitions.
- **RoundRobinAssignor**: Partitions distributed in round-robin. Consumer 0 gets {0,4}, Consumer 1 gets {1,5}, Consumer 2 gets {2}, Consumer 3 gets {3}. More balanced.
- **StickyAssignor**: Minimizes partition movement during rebalances while achieving balance. Useful to preserve locality in stateful consumers.
- **CooperativeStickyAssignor**: Same as Sticky but uses **incremental cooperative rebalancing** — only the partitions that need to move are revoked, others continue processing. No stop-the-world pause.

#### Rebalance Protocols
**Eager rebalance** (classic): All consumers **stop and release all partitions**. Group coordinator assigns partitions fresh. There's a processing gap.

**Cooperative rebalance** (CooperativeStickyAssignor, default since Kafka 2.4): Only the partitions that will change assignment are released. Other partitions keep being processed. Far better for latency-sensitive applications.

**Why partition count matters for scaling**: You can only scale a consumer group up to the number of partitions. Plan your partition count to be your **maximum expected consumer count × headroom factor**. Kafka recommends over-partitioning (more partitions than current consumers) to allow future scaling. A common rule: 10 × (peak consumer count).

---

### Q9. Explain the difference between auto.offset.reset=earliest vs latest, and describe a scenario where each causes a production bug.

#### What auto.offset.reset Applies To
This setting **only applies when there is no committed offset** for a consumer group + partition combination. This happens:
- First time the consumer group ever polls (no commits in `__consumer_offsets` for this group).
- The consumer group hasn't polled in so long that committed offsets have been deleted (7-day default retention on `__consumer_offsets`).
- You manually deleted the consumer group.

If committed offsets exist, `auto.offset.reset` is ignored entirely.

#### earliest
Starts from **offset 0** (or the earliest available offset if retention has deleted older segments). The consumer replays all available history.

**Bug scenario**: You deploy a new order-processing service for the first time against a `payments` topic that has 6 months of data (300 million records). With `auto.offset.reset=earliest` and the consumer group having no prior committed offsets, the service processes all 6 months of historical payments on startup — sending 300 million duplicate payment notifications, double-charging customers, or overwhelming downstream systems. The fix: always use `latest` for new consumer groups on topics with historical data you don't want replayed, or pre-set offsets using `kafka-consumer-groups.sh --reset-offsets --to-latest` before deploying.

#### latest
Starts from the **current end of the partition** — only new messages produced after the consumer group first polls will be consumed.

**Bug scenario**: You have a topic `user-registrations`. You deploy a new analytics service that processes registrations. You use `auto.offset.reset=latest`. During the deploy, there's a gap between when the consumer group was "created" (first call to `subscribe()`) and when the service actually started consuming. Any `user-registration` events produced in that window are **silently skipped**. The user is registered but never appears in analytics. This is especially insidious because there's no error — the consumer simply starts at the offset that was "latest" when it first polled.

#### The Critical Gotcha: committed offsets vs auto.offset.reset
Developers often think "setting `auto.offset.reset=latest` means I always start from the end." Wrong. If the consumer group has old committed offsets, it starts from the committed offset — even if that's far behind. `auto.offset.reset` is only a "no committed offset" fallback.

Another trap: `enable.auto.commit=true` (default). The consumer auto-commits on `poll()` — even if the application hasn't finished processing the fetched records. If the application crashes mid-batch, those records are marked committed and won't be reprocessed. Always use `enable.auto.commit=false` in production with manual `commitSync()` or `commitAsync()` **after** processing is complete.

---

### Q10. How would you implement a consumer that processes records exactly once, writing results to PostgreSQL?

Kafka EOS (transactions) only guarantees atomicity within Kafka. PostgreSQL is an external system — there is no Kafka-Postgres distributed transaction. The solution requires combining Kafka at-least-once delivery with PostgreSQL-side idempotency.

#### Strategy 1: Idempotent Upsert + Manual Offset Commit (Recommended)

```java
consumer.subscribe(List.of("orders"));
consumer.poll(Duration.ZERO); // trigger assignment

while (true) {
    ConsumerRecords<String, Order> records = consumer.poll(Duration.ofMillis(100));
    
    // process in a single DB transaction per batch
    try (Connection conn = dataSource.getConnection()) {
        conn.setAutoCommit(false);
        for (ConsumerRecord<String, Order> record : records) {
            // Idempotent upsert: offset as dedup key
            String sql = """
                INSERT INTO processed_orders (kafka_offset, partition, order_id, data)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (kafka_offset, partition) DO NOTHING
            """;
            // execute...
        }
        conn.commit();
    }
    // Only commit Kafka offset AFTER successful DB transaction
    consumer.commitSync();
}
```

**Why this works**: If the process crashes after DB commit but before Kafka commit, the consumer replays the same records. The `ON CONFLICT DO NOTHING` clause ignores the duplicates. The DB transaction ensures all-or-nothing writes for a batch.

**Tracking offsets in the DB**: An even stronger pattern stores Kafka offsets **inside the same DB transaction**:

```sql
CREATE TABLE kafka_offsets (
    topic VARCHAR, partition INT, consumer_group VARCHAR,
    offset BIGINT,
    PRIMARY KEY (topic, partition, consumer_group)
);
```

On startup, read offsets from DB and use `consumer.seek(partition, offset)` to position. On each batch: write to business tables + update `kafka_offsets` in the same DB transaction. Never commit Kafka offsets at all — the DB is the source of truth for offset position.

#### Strategy 2: Outbox Pattern (for Write-Heavy Systems)
The application writes to a DB `outbox` table inside its own DB transaction. A separate `outbox reader` (Debezium or polling) publishes outbox rows to Kafka, then marks them published. Downstream consumers process from Kafka idempotently. This separates the Kafka interaction from the core business logic transaction entirely.

#### What to Avoid
- `enable.auto.commit=true`: Offset committed even if processing fails.
- Committing Kafka offset before DB write: Guarantees at-most-once (data loss on crash).
- Non-idempotent DB writes: A `INSERT` without conflict handling causes duplicates on redelivery.

---

## 3. Architecture & Design

---

### Q11. Design a real-time fraud detection system using Kafka that must process 500k transactions/sec with sub-100ms p99 latency end-to-end.

#### Partitioning Strategy
Key insight: fraud detection needs to correlate multiple transactions for the same card/account. Partition by **card ID** to ensure all transactions for a given card land on the same partition — enabling stateful per-card processing without cross-partition coordination.

With 500k TPS at ~200 bytes/message = 100 MB/s. A single partition handles ~50-100 MB/s safely. Start with **20 partitions** for headroom. Rule of thumb: plan for 2x peak load, so 40 partitions is safer.

#### Pipeline Architecture

```
[Payment Gateway] 
    → (produce) → [transactions topic, 40 partitions, acks=1, linger=0]
    → [Kafka Streams App, 40 stream threads]
        - KTable: account state (last 10 txns, velocity)
        - Sliding window: transactions in last 60 seconds per card
        - Anomaly scoring: rules engine + ML model call
    → [fraud-scores topic]
    → [Decision Service] (Kafka consumer, fast read)
        → approve/decline → [decisions topic]
    → [Payment Gateway callback]
```

#### Latency Budget Breakdown (targeting p99 < 100ms)
- Producer → Broker write: ~5ms (acks=1 for speed, acceptable risk for this use case)
- Broker → Stream processor fetch: ~5ms
- Kafka Streams processing (stateful): ~20ms (RocksDB local state, no network hop)
- Score topic write + decision service read: ~10ms
- Decision service → gateway callback: ~10ms
- Total: ~50ms, leaving headroom for GC pauses and spikes

#### Key Design Decisions

**acks=1 vs acks=all**: For a fraud detection input topic, `acks=1` is acceptable — a lost transaction message means a missed fraud check, not lost money. The payment itself is recorded elsewhere. The risk of losing the fraud signal is lower than adding 30ms p99 latency from `acks=all`.

**Kafka Streams with RocksDB local state**: The ML scoring model needs recent transaction history. Store last N transactions per card in a RocksDB state store (local disk on the stream processor). No external DB call during processing. State is backed by a Kafka changelog topic for recovery.

**Windowed aggregations**: Use `SlidingWindows` or `TimeWindows` to count transactions per card per minute. Alert if velocity exceeds threshold.

**Standby replicas**: Set `num.standby.replicas=1` on Kafka Streams to pre-populate state on another instance. Failover restores state in seconds rather than replaying hours of changelog.

**Replication factor**: 3 for all topics. `min.insync.replicas=2`.

---

### Q12. How would you implement a reliable event-driven saga pattern using Kafka for a distributed transaction spanning Order, Payment, and Inventory services?

#### The Problem with Distributed Transactions
Traditional two-phase commit (2PC) across microservices creates tight coupling, blocks on participant failures, and doesn't work across heterogeneous systems. Sagas break a distributed transaction into a sequence of local transactions, each publishing an event that triggers the next step.

#### Choreography-based Saga

```
OrderService: 
  - Receive order request
  - Write to DB (order.status = PENDING)
  - Publish: OrderCreated {orderId, items, amount}

PaymentService (consumes OrderCreated):
  - Check account balance
  - Reserve funds (DB transaction)
  - Publish: PaymentAuthorized {orderId, paymentId}
    OR: PaymentFailed {orderId, reason}

InventoryService (consumes PaymentAuthorized):
  - Check stock availability
  - Reserve inventory (DB transaction)
  - Publish: InventoryReserved {orderId}
    OR: InventoryFailed {orderId}

OrderService (consumes InventoryReserved):
  - Update order.status = CONFIRMED
  - Publish: OrderConfirmed {orderId}
```

#### Compensating Transactions (Rollback)

```
If InventoryFailed:
  → PaymentService (consumes InventoryFailed): 
      - Release reserved funds
      - Publish: PaymentReleased
  → OrderService (consumes PaymentReleased):
      - Update order.status = FAILED
```

#### The Outbox Pattern — Solving the Dual Write Problem
The biggest risk: what if `OrderService` commits to its DB but fails before publishing to Kafka? Or publishes to Kafka but the DB commit fails?

**Solution — Outbox pattern**:
```sql
BEGIN;
INSERT INTO orders (id, status) VALUES (?, 'PENDING');
INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload)
    VALUES ('order', ?, 'OrderCreated', ?);
COMMIT;
```

A separate **outbox poller** (or Debezium CDC) reads the `outbox` table and publishes to Kafka, then marks rows as published. The DB transaction atomically creates the order AND the outbox record — they always succeed or fail together. Publishing to Kafka is a separate, retriable step.

#### Idempotent Consumers Are Mandatory
Every service in the saga must handle redelivery. Use an **idempotency key** (e.g., `orderId + eventType`) stored in the DB:
```sql
INSERT INTO processed_events (idempotency_key) VALUES (?) 
ON CONFLICT DO NOTHING RETURNING id;
-- If NULL returned, event already processed, skip
```

#### Partition by Saga ID
All events for a single order should land on the **same partition** (partition by `orderId`). This ensures **causal ordering** — `InventoryReserved` is always processed after `OrderCreated` in the same consumer group, because they're on the same partition.

#### Dead Letter Topics
If PaymentService fails to process `OrderCreated` after N retries (malformed message, transient DB error), route to `OrderCreated-dlt`. A human or automated recovery process investigates and potentially replays.

---

### Q13. When would you use Kafka Streams vs Flink or Spark Streaming?

#### Kafka Streams Strengths
- **Embedded library**: No cluster to manage. Runs inside your Java application. Scales by adding instances.
- **Kafka-native**: KTable joins, changelog-backed state, consumer group-managed partitioning — all first-class.
- **Simple ops**: Your app is your stream processor. Deploy like any service.
- **Exactly-once** out of the box with `processing.guarantee=exactly_once_v2`.
- **Perfect for**: microservice event processing, KTable-KStream joins, changelog aggregations, moderate-scale stateful processing.

#### Kafka Streams Limitations
- **Kafka-only source/sink**: Cannot natively join Kafka streams with data from S3, JDBC, or HBase without custom code.
- **State size bounded by disk**: Each Kafka Streams instance's RocksDB state is bounded by local disk. Very large state (hundreds of GB per partition) is challenging.
- **Session windows**: Supported but require careful timeout configuration. Complex event patterns (CEP) aren't native.
- **No SQL layer**: You write Java topology code. No declarative SQL for stream queries (Flink SQL, Spark SQL exist).
- **Rebalancing overhead**: Task migration during scaling involves state checkpoint/restore. In Flink, stateful rescaling is first-class.
- **Limited multi-input join patterns**: KStream-KTable join works well. KStream-KStream join (windowed) works. Three-way joins require chaining.

#### Apache Flink Strengths
- **True event-time processing**: Watermarks, allowed lateness, late data routing — more sophisticated than Kafka Streams.
- **Massive state**: Flink state backend (RocksDB or heap) can scale to TB of state with incremental checkpointing.
- **Rich windowing**: Event-time windows, session windows, custom triggers.
- **Flink SQL / Table API**: Write streaming jobs in SQL. Kafka Streams has no SQL equivalent.
- **CEP (Complex Event Processing)**: `FlinkCEP` for pattern matching across events (detect "3 failed logins within 10 minutes across any partition").
- **Multi-source joins**: Join Kafka with JDBC, Hive, HBase natively.
- **Better rescaling**: Stateful job rescaling without full state replay.

#### Apache Spark Structured Streaming
- **Micro-batch model**: Processes data in small time intervals (not true record-by-record streaming). Adds inherent latency (~100ms minimum).
- **Strengths**: Unified batch + streaming API, ML integration (MLlib), massive ecosystem, SQL layer.
- **Use when**: You're already using Spark for batch, need ML integration in the pipeline, or need batch/streaming unification.
- **Latency**: Not suitable for sub-100ms requirements.

#### Decision Framework
| Criterion | Kafka Streams | Flink | Spark Streaming |
|-----------|--------------|-------|-----------------|
| Kafka-only sources | ✓ | ✓ | ✓ |
| Multi-source joins | ✗ | ✓ | ✓ |
| Very large state | Limited | ✓ | Limited |
| SQL interface | ✗ | ✓ (Flink SQL) | ✓ |
| Sub-10ms latency | ✓ | ✓ | ✗ |
| Ops simplicity | High | Medium | Low |
| CEP patterns | ✗ | ✓ | ✗ |

---

### Q14. How would you migrate a Kafka cluster from ZooKeeper mode to KRaft mode in production with zero downtime?

#### Pre-migration Checks
- Kafka 3.4+ (earlier versions had KRaft in preview only). Kafka 4.0 removes ZooKeeper entirely.
- All brokers must run the same Kafka version.
- No third-party tools that directly read ZooKeeper metadata (some older monitoring tools do this).
- Ensure `inter.broker.protocol.version` and `log.message.format.version` are at current version (no cross-version mixed cluster).

#### Migration Steps (Kafka 3.5+ `kafka-storage` tool)

**Phase 1: Generate a cluster ID compatible with KRaft**
```bash
# If the cluster already has a ZK-based cluster ID, use it
kafka-cluster.sh cluster-id --bootstrap-server localhost:9092
```

**Phase 2: Configure KRaft controller quorum**
Add 3 dedicated controller nodes (or use co-located mode where brokers also act as controllers). Configure their `server.properties`:
```properties
process.roles=controller
node.id=1001
controller.quorum.voters=1001@ctrl1:9093,1002@ctrl2:9093,1003@ctrl3:9093
```

**Phase 3: Enable ZK migration mode**
On existing brokers, add:
```properties
zookeeper.metadata.migration.enable=true
```

**Phase 4: Start KRaft controllers in migration mode**
Controllers bootstrap from ZooKeeper metadata, creating a snapshot of the current state. This is the **bridge phase** — both ZK and KRaft controllers coexist temporarily.

**Phase 5: Rolling broker migration**
One broker at a time, update `server.properties` to add KRaft configuration and remove ZooKeeper. Restart each broker. The broker re-registers with the KRaft controller quorum instead of ZooKeeper.

```properties
# Remove:
zookeeper.connect=zk1:2181,zk2:2181
# Add:
controller.quorum.voters=1001@ctrl1:9093,1002@ctrl2:9093,1003@ctrl3:9093
```

During rolling restart, partition leaders and ISR remain stable — Kafka handles the leadership without disruption.

**Phase 6: Final cutover**
Once all brokers report to KRaft controller, disable ZooKeeper migration mode:
```bash
kafka-features.sh upgrade --feature metadata.version=<latest>
```

**Phase 7: Decommission ZooKeeper**
ZooKeeper cluster is no longer needed. Remove ZK-related configs, decommission nodes.

#### Rollback Strategy
If issues arise before all brokers migrate: roll back individual brokers to ZK mode. The ZK metadata is still authoritative until full migration completes. After full migration, rollback is not supported — plan a maintenance window for Phase 4 onwards, and test in staging first.

---

### Q15. How do you handle schema evolution in a Kafka-based system at scale — across dozens of producers and consumers that deploy independently?

#### The Schema Problem
Producers and consumers deploy independently. A producer adds a new required field to its payload. Old consumers that don't know about this field may crash, silently ignore it, or produce incorrect behavior. Without governance, schema evolution is a production incident waiting to happen.

#### Schema Registry
A **Schema Registry** (Confluent Schema Registry or Apicurio) stores schemas by subject (typically `<topic>-value` and `<topic>-key`). Producers register schemas before publishing. Consumers fetch the schema at read time using a schema ID embedded in the message.

**Wire format**: `[magic byte: 0x00] [4-byte schema ID] [serialized payload]`

The consumer reads the schema ID, fetches the schema from the registry, and uses it to deserialize the payload. Crucially, the consumer uses the **writer's schema** (stored in registry) and its own **reader's schema** (compiled into the consumer app) for schema resolution — this enables forward and backward compatibility.

#### Compatibility Modes

**BACKWARD** (most common): New schema can read data written with the old schema.
- Safe changes: Add optional fields with defaults.
- Unsafe: Remove required fields, rename fields.
- Use case: Deploy new consumer first (can read old and new data), then deploy new producer.

**FORWARD**: Old schema can read data written with the new schema.
- Safe changes: Add required fields, remove optional fields.
- Use case: Deploy new producer first (may produce fields old consumer ignores), then deploy consumers.

**FULL**: Both backward and forward compatible.
- Safe changes: Only add optional fields with defaults.
- Most restrictive, most safe.

**NONE**: No compatibility checking. Useful during development. Dangerous in production.

#### Avro vs Protobuf vs JSON Schema

**Avro**: Binary encoding, compact, requires schema for reading (no self-describing). Excellent Schema Registry support. Field resolution by name and type. Null defaults enable optional fields. Most popular in Kafka ecosystem.

**Protobuf**: Binary encoding, field numbers (not names) for compatibility. Adding fields with new numbers is always backward compatible. Excellent cross-language support. Self-describing in metadata sense.

**JSON Schema**: Schema defines structure, payload is human-readable JSON. More verbose than binary formats. Useful when debugging tooling is important or consumers are non-JVM.

#### Shadow Topic Migration
When a schema change is too breaking for in-place evolution:
1. Create a new topic `orders-v2` with the new schema.
2. Deploy a migration service that reads `orders-v1`, transforms, and writes to `orders-v2`.
3. Migrate consumers one by one to `orders-v2`.
4. When all consumers are on `orders-v2`, shut down the migration service and deprecate `orders-v1`.

#### Consumer-Driven Contract Testing
Before publishing a schema change, producers run **contract tests** against the current schemas registered for all known consumers. If any consumer's registered schema is incompatible with the proposed change, the test fails. This catches breaking changes before deployment, not in production.

---

## 4. Operations & Reliability

---

### Q16. A Kafka broker is lagging behind significantly in replication. Walk through your diagnostic process and remediation steps.

#### Initial Triage — Key JMX Metrics
```
kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions  # Non-zero = problem
kafka.server:type=ReplicaManager,name=IsrShrinksPerSec           # Replicas leaving ISR
kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec          # Inbound throughput
kafka.network:type=RequestMetrics,name=RequestsPerSec,request=Produce  # Produce rate
```

#### Diagnostic Steps

**Step 1: Identify the lagging broker**
```bash
kafka-topics.sh --bootstrap-server localhost:9092 \
  --describe --under-replicated-partitions
```
Look for partitions where a specific broker is consistently in "Replicas" but not in "Isr".

**Step 2: Check replica lag per broker**
```bash
kafka-replica-verification.sh --broker-list localhost:9092 \
  --topic-white-list ".*"
```

**Step 3: Investigate root causes**

*Disk I/O saturation*: Check `iostat -x 1` on the lagging broker. If `%util > 90%`, disk is saturated. Kafka's replication fetcher writes to disk and may compete with producer writes. Check if the broker also handles heavy consumer traffic (consuming reads from disk when page cache is cold).

*GC pauses*: Check GC logs: `grep "GC pause" /var/log/kafka/kafka-gc.log | tail -50`. Stop-the-world GC pauses cause the `ReplicaFetcherThread` to stall. Switch to G1GC or ZGC if using old CMS or ParNew collectors.

*Network saturation*: `sar -n DEV 1 10` — look for high `%ifutil`. Replication is on the same NIC as producer/consumer traffic. Consider separate network interfaces or bandwidth throttling.

*CPU contention*: High CPU from many partitions, heavy compression/decompression, or log compaction competing with replication.

**Step 4: Throttle replication bandwidth** (emergency — reduce impact on producers/consumers)
```bash
kafka-configs.sh --bootstrap-server localhost:9092 \
  --alter --entity-type brokers --entity-name 2 \
  --add-config follower.replication.throttled.rate=50000000   # 50 MB/s
```

This slows down replication on the follower to reduce I/O/network pressure on the lagging broker.

**Step 5: Partition reassignment** (if broker is permanently overwhelmed)
Use `kafka-reassign-partitions.sh` to move some partition leadership away from the overloaded broker, balancing load across the cluster.

#### Remediation Summary
- Short-term: Throttle replication, identify and fix the bottleneck (disk, GC, network).
- Medium-term: Add brokers and rebalance, upgrade hardware (NVMe > SATA SSD > HDD).
- Long-term: Set up continuous monitoring on `UnderReplicatedPartitions` and `IsrShrinksPerSec` with PagerDuty alerts, and add capacity planning alerts before hitting limits.

---

### Q17. Explain consumer lag, how you monitor it, and how you would set up alerting to distinguish healthy lag from catastrophic lag.

#### What is Consumer Lag?
Consumer lag = **latest offset** (end of partition log) - **committed consumer offset** (last successfully processed position).

A lag of 0 means the consumer is fully caught up. A lag of 1,000,000 means the consumer is 1 million records behind.

#### How to Measure Lag

**Command-line**:
```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group my-consumer-group
```
Output shows: GROUP, TOPIC, PARTITION, CURRENT-OFFSET, LOG-END-OFFSET, LAG, CONSUMER-ID.

**Programmatic** (Kafka AdminClient):
```java
Map<TopicPartition, OffsetAndMetadata> committed = adminClient
    .listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get();
Map<TopicPartition, Long> endOffsets = consumer.endOffsets(committed.keySet());
// lag = endOffsets[tp] - committed[tp].offset()
```

**Monitoring tools**:
- **Burrow** (LinkedIn): Polls consumer group offsets asynchronously. Detects stalled consumers (no new commits) vs slow consumers (commits advancing but lag growing). Sends alerts to HTTP endpoints or email.
- **Prometheus + kafka_exporter**: Scrapes Kafka JMX metrics. Grafana dashboard with `kafka_consumer_lag_sum` and `kafka_consumer_lag_partition`.
- **Confluent Control Center**, **Datadog**, **New Relic**: Commercial options with built-in lag monitoring.

#### Healthy vs Catastrophic Lag

**Healthy lag**: 
- Lag exists but is **stable or decreasing**. Consumer keeps up with the producer; occasional backlog during traffic bursts that resolves.
- Example: Lag spikes to 10,000 during a traffic burst, then returns to <100 within 5 minutes.

**Catastrophic lag**:
- Lag is **monotonically increasing**. Consumer is slower than the producer and will never catch up without intervention.
- `lag_rate > 0` sustained for >10 minutes = production incident.
- Stalled consumer: committed offset never advances (consumer is stuck, crashed, or in infinite retry loop).

#### Alerting Strategy
```yaml
# Prometheus alerting rules (simplified)
- alert: KafkaConsumerLagCritical
  expr: kafka_consumer_group_lag_sum > 100000
  for: 5m
  annotations:
    summary: "Consumer group {{ $labels.group }} has lag > 100k for 5 minutes"

- alert: KafkaConsumerStalled
  expr: delta(kafka_consumer_group_current_offset[5m]) == 0
  for: 10m
  annotations:
    summary: "Consumer group {{ $labels.group }} has not committed new offsets in 10 minutes"

- alert: KafkaConsumerLagIncreasing
  expr: deriv(kafka_consumer_group_lag_sum[10m]) > 500
  for: 5m
  annotations:
    summary: "Consumer group {{ $labels.group }} lag is growing at > 500 records/sec"
```

The stalled consumer alert is often more important than absolute lag — a stalled consumer with lag=100 is worse than a busy consumer with lag=1,000,000 that's decreasing.

---

### Q18. Describe your strategy for Kafka topic capacity planning for a topic expecting 100 MB/s sustained throughput.

#### Partition Count
Single partition throughput is bounded by:
- Broker disk I/O (~100–500 MB/s for NVMe SSDs, shared with all partitions on broker)
- Network bandwidth (typically 1–10 Gbps shared)
- Per-broker limitation: Kafka recommends <10,000 partitions total per broker

Rule of thumb: 50–100 MB/s per partition as a safe maximum. For 100 MB/s:
- **Minimum partitions for throughput**: 2 (but no consumer parallelism headroom)
- **Recommended**: 10–20 partitions (allows scaling to 10–20 parallel consumers, provides growth headroom)

Consider future growth: if you expect 5x growth in 2 years, over-partition now. Partition count can only increase, never decrease. Adding partitions later invalidates key-based partitioning (messages for the same key may now land on a different partition).

#### Replication Factor
Standard: **replication.factor=3**, **min.insync.replicas=2**.

Write amplification from replication: 100 MB/s × 3 replicas = **300 MB/s** of inter-broker replication traffic. Ensure your internal network can sustain this.

#### Retention
**Prefer bytes-based over time-based** for predictable disk usage:
```properties
retention.bytes=53687091200   # 50 GB per partition
retention.ms=604800000        # 7 days as secondary guard
```

For 100 MB/s × 20 partitions = 2,000 MB/s total... actually: 100 MB/s total across all producers writes to the topic, spread across 20 partitions = 5 MB/s per partition. Over 7 days: 5 MB/s × 86400 × 7 = ~3 TB per partition before replication. Total storage: 3 TB × 20 partitions × 3 replicas = **180 TB**.

If disk is constrained, reduce retention to 1–2 days for high-volume topics, or use tiered storage (Kafka 3.6+ supports remote storage to S3).

#### Segment Size
```properties
segment.bytes=1073741824   # 1 GB segments (default)
segment.ms=604800000       # 7 days maximum
```
Larger segments = fewer files, better compaction efficiency, slower recovery when only recent data needed. Smaller segments = faster deletion of old data, more file handles.

#### Producer and Broker Config Alignment
```properties
# Producer
compression.type=lz4            # Reduce storage by ~3x → effectively 33 MB/s on disk
batch.size=131072               # 128 KB batches
linger.ms=10

# Broker  
num.replica.fetchers=4          # Parallel replication threads
replica.fetch.max.bytes=1048576 # 1 MB per fetch request
```

---

### Q19. How would you implement a dead letter queue (DLQ) pattern in Kafka for a consumer that keeps failing on poisoned messages?

#### Why DLQ?
A poisoned message is one that consistently causes processing to fail (malformed payload, schema mismatch, unexpected null, downstream system rejection). Without a DLQ, the consumer retries forever, blocking all subsequent messages on that partition.

#### Spring Kafka Implementation

```java
@Bean
public DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafkaTemplate) {
    // Retry 3 times with exponential backoff
    ExponentialBackOffWithMaxRetries backoff = new ExponentialBackOffWithMaxRetries(3);
    backoff.setInitialInterval(1000L);
    backoff.setMultiplier(2.0);
    backoff.setMaxInterval(10000L);

    // After retries exhausted → publish to DLT
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
        kafkaTemplate,
        (record, exception) -> new TopicPartition(
            record.topic() + ".DLT",   // Dead Letter Topic
            record.partition()         // Maintain partition affinity
        )
    );

    return new DefaultErrorHandler(recoverer, backoff);
}
```

#### DLT Message Headers
Spring Kafka automatically adds diagnostic headers to DLT messages:
```
kafka_dlt-exception-fqcn       → com.example.ProcessingException
kafka_dlt-exception-message    → Failed to deserialize payload
kafka_dlt-original-topic       → orders
kafka_dlt-original-partition   → 3
kafka_dlt-original-offset      → 10045892
kafka_dlt-original-timestamp   → 1706789123456
```

These headers allow a DLT consumer to know exactly where the message came from and replay it later.

#### DLT Consumer: Alerting and Manual Replay
```java
@KafkaListener(topics = "orders.DLT")
public void handleDeadLetter(
    @Payload String payload,
    @Header(KafkaHeaders.RECEIVED_TOPIC) String originalTopic,
    @Header("kafka_dlt-original-offset") String originalOffset,
    @Header("kafka_dlt-exception-message") String error
) {
    // 1. Alert on-call team (PagerDuty, Slack)
    alertingService.sendAlert("Dead letter in " + originalTopic + 
                              " offset=" + originalOffset + 
                              " error=" + error);
    // 2. Store in DB for investigation/replay UI
    dlqRepository.save(new DLQEntry(originalTopic, originalOffset, payload, error));
}
```

#### Replay Strategy
After fixing the root cause (schema bug, downstream service restored):
```bash
# Option 1: Reset DLT consumer group to replay from beginning
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group dlt-recovery-group --reset-offsets \
  --topic orders.DLT --to-earliest --execute

# Option 2: Programmatic replay from DB entries
dlqRepository.findAllUnresolved().forEach(entry -> {
    kafkaTemplate.send("orders", entry.getKey(), entry.getPayload());
    entry.markReplayed();
});
```

#### Infinite Retry Loop Prevention
Be careful with DLT-to-original-topic replay: if the root cause isn't fixed, the message fails again, goes back to DLT, gets replayed again → infinite loop. Always:
1. Fix the consumer logic first.
2. Add a `replay_count` header and reject after N replays.
3. Consider a separate "manual review" queue for messages that fail DLT replay.

---

### Q20. A production incident: consumer offsets are lost and __consumer_offsets topic is corrupt. What do you do?

#### Understanding __consumer_offsets
`__consumer_offsets` is an internal Kafka topic with 50 partitions (configurable via `offsets.topic.num.partitions`), replication factor 3 (by default). It stores committed offsets for all consumer groups as compacted key-value records. Key = (group_id, topic, partition). Value = committed offset + metadata.

#### Incident Response Steps

**Step 1: Stop all consumers immediately**
Prevent further offset commits to a corrupt topic, and prevent consumers from resetting to `auto.offset.reset` positions that might process data incorrectly.

**Step 2: Assess the damage**
```bash
# Check which partitions of __consumer_offsets are under-replicated
kafka-topics.sh --bootstrap-server localhost:9092 \
  --describe --topic __consumer_offsets | grep -v "Leader: [0-9]"

# Inspect raw contents
kafka-dump-log.sh --files /var/kafka/logs/__consumer_offsets-0/00000000000000000000.log \
  --print-data-log | head -100
```

**Step 3: Attempt recovery from existing replicas**
If only some partitions of `__consumer_offsets` are corrupt, the replication factor 3 may have healthy replicas. Force a leader election to a healthy replica:
```bash
kafka-leader-election.sh --bootstrap-server localhost:9092 \
  --election-type PREFERRED --topic __consumer_offsets --partition 15
```

**Step 4: If offsets are truly lost — choose a recovery position**

Option A: Start from the latest offset (accept some message loss):
```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group my-consumer-group --reset-offsets \
  --all-topics --to-latest --execute
```

Option B: Start from a known timestamp (replay from last known-good checkpoint):
```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group my-consumer-group --reset-offsets \
  --all-topics --to-datetime 2024-01-15T10:30:00.000 --execute
```

Option C: Start from offset stored in your external monitoring system (if you store offset snapshots).

Option D: If you use DB-tracked offsets (storing Kafka offsets inside your application database — the safest pattern): read from DB and use `consumer.seek()` to position manually.

**Step 5: Prevent recurrence**
- Ensure `__consumer_offsets` has RF=3 and `min.insync.replicas=2`.
- Set up monitoring on `__consumer_offsets` partition health (same `UnderReplicatedPartitions` check).
- **Best practice**: Store Kafka offsets in your application database (as described in Q10). This gives you an independent, always-correct record of processing position.

---

## 5. Kafka Streams & Java

---

### Q21. Explain the difference between KStream and KTable in Kafka Streams. When does a KTable trigger a downstream update vs suppress it?

#### KStream: Unbounded Event Stream
A KStream represents an **infinite sequence of events**. Every record is independent. Consuming the same topic as a KStream means every message triggers a downstream processor. There is no concept of "the current value" — all records are additive.

Use KStream for: clickstream events, transaction logs, user actions, IoT sensor readings — anything where every occurrence matters independently.

#### KTable: Changelog / Materialized View
A KTable represents the **latest state per key** — like a continuously updating database table. Records with the same key update the current value; only the latest value per key is meaningful. A record with `value=null` is a tombstone (delete the key).

```java
KTable<String, Long> wordCounts = textStream
    .flatMapValues(text -> Arrays.asList(text.toLowerCase().split("\\s+")))
    .groupBy((key, word) -> word)
    .count();
// Result: for each word, the current count. Not all historical counts.
```

#### Caching and Suppression — When KTable Triggers Updates
This is where most candidates get it wrong. **A KTable does not emit every intermediate update to downstream processors by default.**

The Kafka Streams cache (`cache.max.bytes.buffering`, default 10MB) buffers updates per key. Updates to the same key accumulate in the cache, and **only the latest value is forwarded** when the cache is flushed. Flushes happen:
1. Cache is full (memory pressure).
2. `commit.interval.ms` elapses (default 30 seconds for at-least-once, 100ms for EOS).
3. Application shutdown.

This means a key that's updated 1,000 times between cache flushes only emits **one downstream record** — the latest value. This drastically reduces downstream processing load.

**To disable caching** (every update triggers downstream):
```java
StreamsBuilder builder = new StreamsBuilder();
Topology topology = builder.build();
Properties props = new Properties();
props.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 0);
```

**Explicit Suppression** for final results only (e.g., only emit window result after window closes):
```java
KTable<Windowed<String>, Long> windowedCounts = stream
    .groupByKey()
    .windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofMinutes(5), Duration.ofMinutes(1)))
    .count()
    .suppress(Suppressed.untilWindowCloses(BufferConfig.unbounded()));
```
Without `suppress()`, a windowed KTable emits an update every time a new record arrives in the window (many intermediate results). With `suppress()`, only the final count after the window closes + grace period is emitted.

#### KStream-KTable Join
The canonical pattern for enrichment:
```java
KStream<String, Order> orders = builder.stream("orders");
KTable<String, Customer> customers = builder.table("customers");

KStream<String, EnrichedOrder> enriched = orders.join(
    customers,
    (order, customer) -> new EnrichedOrder(order, customer)
);
```
Each order is enriched with the **current** customer record at the time of processing. If the customer is updated later, re-streaming old orders won't pick up the new data — the join is point-in-time.

---

### Q22. How does Kafka Streams handle state stores? Compare in-memory, persistent (RocksDB), and versioned state stores.

#### What are State Stores?
State stores are embedded databases within Kafka Streams that hold intermediate computation state — running counts, windowed aggregations, join tables. They are:
- **Local** to each Streams instance (no shared external state)
- **Backed by a Kafka changelog topic** for durability and recovery
- **Partitioned** — each Streams task's state store contains only the data for its assigned partitions

#### In-Memory State Store
```java
Materialized.as(Stores.inMemoryKeyValueStore("my-store"))
```

- **Storage**: Java heap (HashMap internally)
- **Durability**: None — data lost on application restart
- **Recovery**: Must replay entire changelog topic from beginning on restart
- **Performance**: Fastest reads/writes (no serialization to disk)
- **Use case**: Small state, rapid prototyping, or when data is ephemeral and changelog replay is fast

**Dangerous at scale**: A 50 GB in-memory store exhausts heap, triggers GC, crashes the JVM. Also, recovery from a 50 GB changelog replay can take hours.

#### Persistent State Store — RocksDB (Default)
```java
Materialized.as(Stores.persistentKeyValueStore("my-store"))
// Or just: Materialized.with(Serdes.String(), Serdes.Long())
// RocksDB is used by default
```

- **Storage**: RocksDB on local disk (log-structured merge tree, optimized for writes)
- **Durability**: Survives application restart — data is on disk
- **Recovery**: On restart, only events since the last RocksDB checkpoint are replayed from the changelog (much faster than full replay)
- **Performance**: Near-in-memory for writes; reads involve disk I/O for cache-miss keys
- **Use case**: Default choice for production. Handles GB-TB of state efficiently.

**RocksDB tuning in Kafka Streams**:
```java
// Custom RocksDB config (block cache, compaction, etc.)
class CustomRocksDBConfig implements RocksDBConfigSetter {
    public void setConfig(String storeName, Options options, Map<String, Object> configs) {
        BlockBasedTableConfig tableConfig = new BlockBasedTableConfig();
        tableConfig.setBlockCacheSize(64 * 1024 * 1024L);  // 64 MB block cache
        options.setTableFormatConfig(tableConfig);
        options.setMaxWriteBufferNumber(2);
        options.setWriteBufferSize(8 * 1024 * 1024L);      // 8 MB write buffer
    }
}
```

#### Versioned State Store (Kafka 3.5+)
```java
Materialized.as(Stores.persistentVersionedKeyValueStore("my-store", Duration.ofHours(24)))
```

- **Storage**: RocksDB with multiple versions per key
- **Purpose**: Enables **time-based lookups** — retrieve the value that was current at a specific timestamp
- **Use case**: KStream-KTable join with out-of-order events. A stream event arriving 10 seconds late can join against the KTable value that was current 10 seconds ago (not the current value, which may have been updated).
- **Without versioned stores**: Late stream events join with the *current* KTable value, potentially getting a "future" customer record and producing incorrect enrichments.

#### Standby Replicas
```properties
num.standby.replicas=1
```

Standby replicas are **shadow copies** of state stores pre-populated on other Kafka Streams instances in the same app. They continuously receive changelog updates without doing processing.

On failure: the failed instance's tasks are reassigned to an instance with a standby. Because the standby's state store is already ~current, failover completes in seconds (only the last few seconds of changelog need replaying) instead of minutes or hours.

---

### Q23. Describe the threading model of a Kafka Streams application and what happens during a rebalance.

#### Threading Model
```properties
num.stream.threads=4  # 4 StreamThreads per JVM instance
```

Each **StreamThread** manages a set of **tasks**. A task is the unit of parallel work: one task per partition (for a source topic with N partitions, there are N tasks).

```
JVM Instance
├── StreamThread-1
│   ├── Task 0 (partition 0)
│   │   ├── Source processor
│   │   ├── Filter processor  
│   │   ├── Aggregation processor (State Store: RocksDB partition 0)
│   │   └── Sink processor
│   └── Task 1 (partition 1)
│       └── ... same topology
├── StreamThread-2
│   ├── Task 2 (partition 2)
│   └── Task 3 (partition 3)
```

A StreamThread runs a single-threaded event loop: `poll() → process records → commit`. Multiple StreamThreads in one JVM provide parallelism across partitions. Multiple JVM instances provide horizontal scaling (more instances = more total threads = more tasks processed in parallel).

**Maximum parallelism = number of input partitions**. More threads than partitions are idle.

#### What Happens During Rebalance

**Trigger**: A Streams instance joins or leaves the consumer group (deployment, crash, scaling event).

**Eager Rebalance** (classic `PartitionAssignor`):
1. **Stop the world**: All StreamThreads stop processing. All tasks are revoked.
2. All tasks checkpoint state to their changelog topics.
3. Group coordinator redistributes tasks across all available Streams instances.
4. Each Streams instance resumes with its new task assignments.
5. **Processing gap**: 0 records processed during rebalance. Duration: 1–30 seconds depending on state checkpoint duration.

**Incremental Cooperative Rebalance** (default since Kafka 2.4, `CooperativeStickyAssignor`):
1. **Partial stop**: Only the tasks that need to move are revoked. Other tasks continue processing.
2. Moved tasks checkpoint state, transfer to new instances.
3. Receiving instances start the new tasks.
4. **Processing gap**: Only for moved partitions. Other partitions see zero downtime.

**Why cooperative rebalance matters**: In a 40-partition Streams application, if only 2 partitions need to move (due to a single instance dying), eager rebalance stops all 40 partitions for potentially 30 seconds. Cooperative rebalance only pauses those 2 partitions.

#### Stateful Task Migration
When a stateful task migrates from Instance A to Instance B:
1. Instance A flushes its RocksDB state to the changelog topic.
2. Instance B receives the task assignment.
3. Instance B restores state by replaying the changelog topic from the last checkpoint.
4. If `num.standby.replicas=1`, Instance B likely already has a standby store that's nearly current — only the last few seconds of changelog need replaying.

---

### Q24. How would you write a Kafka consumer in Java that handles back-pressure — consuming only as fast as your downstream processing allows, without triggering a rebalance?

#### The Problem
Kafka's consumer keeps its membership in the consumer group by calling `poll()` at least once every `max.poll.interval.ms` (default 5 minutes). If processing is slow and the consumer doesn't call `poll()` in time, the broker considers it dead and triggers a rebalance.

The naive back-pressure solution — "just don't call poll()" — breaks the heartbeat and causes rebalances.

#### Solution 1: Reduce max.poll.records
```properties
max.poll.records=10  # Default is 500
```
Fetch fewer records per poll, so each poll cycle completes faster. Call `poll()` more frequently. Simple and often sufficient.

**Trade-off**: More network calls, lower throughput. Suitable when processing is slow but predictable.

#### Solution 2: Pause/Resume Partitions (Production Pattern)
```java
private final BlockingQueue<ConsumerRecord<String, String>> queue = 
    new LinkedBlockingQueue<>(1000); // Bounded queue = back-pressure signal

// Thread 1: Poll thread — keeps polling Kafka
executor.submit(() -> {
    while (running) {
        // CRITICAL: Always poll, even when paused — maintains heartbeat
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
        
        for (ConsumerRecord<String, String> record : records) {
            try {
                queue.put(record);  // Blocks when queue is full (back-pressure)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        // Pause partitions when queue is nearly full
        if (queue.size() > 800) {
            consumer.pause(consumer.assignment());
        }
        // Resume when queue drains
        if (queue.size() < 200) {
            consumer.resume(consumer.assignment());
        }
    }
});

// Thread 2: Processing thread — drains the queue
executor.submit(() -> {
    while (running) {
        ConsumerRecord<String, String> record = queue.poll(100, TimeUnit.MILLISECONDS);
        if (record != null) {
            processRecord(record);  // Slow downstream call
            // Commit offset after successful processing
            consumer.commitAsync(Map.of(
                new TopicPartition(record.topic(), record.partition()),
                new OffsetAndMetadata(record.offset() + 1)
            ), null);
        }
    }
});
```

**Key insight**: `consumer.pause()` tells the broker "don't send more data in the next fetch", but `poll()` must still be called to maintain the heartbeat. Paused partitions return empty records on `poll()`, but the heartbeat and session keepalive still occur.

#### Solution 3: Reactor/RxJava Back-Pressure
For reactive applications (Project Reactor, RxJava):
```java
Flux.generate(sink -> {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    records.forEach(sink::next);
})
.onBackpressureBuffer(1000)
.flatMap(record -> processRecord(record), 10)  // 10 concurrent
.subscribe();
```

The reactive framework handles back-pressure signaling. `onBackpressureBuffer` buffers up to 1000 records; when full, it drops oldest or pauses source depending on strategy.

#### Critical: session.timeout.ms vs max.poll.interval.ms
These are often confused:
- `session.timeout.ms` (default 45s): Heartbeat-based. Broker uses this to detect consumer death between polls. Controlled by heartbeat thread, not poll thread.
- `max.poll.interval.ms` (default 300s): Processing-based. If `poll()` isn't called within this interval, the broker considers the consumer stuck and triggers rebalance.

The heartbeat runs on a **separate background thread** — it continues even while the main thread is blocked in slow processing. So `session.timeout.ms` won't trigger a rebalance due to slow processing; `max.poll.interval.ms` will. For very slow processing, increase `max.poll.interval.ms` — but this means a truly dead consumer takes longer to be detected.

---

### Q25. Implement a Kafka Streams topology that counts unique users per 5-minute window, with a grace period for late arrivals.

#### The Challenge: Unique Count
A simple `count()` in Kafka Streams counts total events, not unique users. Exact unique user counting (HyperLogLog for approximation, or actual Set for exactness) requires a custom aggregator.

#### Exact Unique Count (Small Data Sets)
```java
StreamsBuilder builder = new StreamsBuilder();

KStream<String, UserEvent> events = builder.stream(
    "user-events",
    Consumed.with(Serdes.String(), userEventSerde)
        .withTimestampExtractor(
            // Use event time (from payload) not ingestion time
            (record, previousTimestamp) -> ((UserEvent) record.value()).getEventTimestamp()
        )
);

KTable<Windowed<String>, Set<String>> uniqueUsers = events
    .groupByKey()  // or .groupBy((k, v) -> v.getPageId()) to count by page
    .windowedBy(
        TimeWindows
            .ofSizeAndGrace(Duration.ofMinutes(5), Duration.ofMinutes(2))
    )
    .aggregate(
        HashSet::new,  // Initializer
        (key, event, userSet) -> {
            userSet.add(event.getUserId());
            return userSet;
        },
        Materialized.<String, Set<String>, WindowStore<Bytes, byte[]>>as("unique-user-counts")
            .withValueSerde(setOfStringSerde)
    )
    .suppress(
        Suppressed.untilWindowCloses(
            Suppressed.BufferConfig.unbounded()
        )
    );

uniqueUsers.toStream()
    .map((windowedKey, userSet) -> KeyValue.pair(
        windowedKey.key() + "@" + windowedKey.window().start(),
        (long) userSet.size()
    ))
    .to("unique-user-counts-output");
```

#### Approximate Unique Count — HyperLogLog (Large Scale)
For high-cardinality scenarios (millions of users), storing a `HashSet<String>` per window per partition becomes memory-prohibitive.

```java
// Using StreamLib's HyperLogLog estimator
.aggregate(
    () -> new HyperLogLog(0.01),  // 1% error rate
    (key, event, hll) -> {
        hll.offerHashed(event.getUserId().hashCode());
        return hll;
    },
    Materialized.with(Serdes.String(), hllSerde)
)
```

#### Key Concepts Demonstrated

**Event-time vs processing-time**:
Using `withTimestampExtractor` tells Kafka Streams to use the timestamp in the event payload rather than when the event arrived at the broker. This is critical for correct windowing — a user event from 4 minutes ago (network delay) should count in the 5-minute window it belongs to, not the current window when it was processed.

**Grace period**:
`ofSizeAndGrace(Duration.ofMinutes(5), Duration.ofMinutes(2))` means:
- Window spans 5 minutes
- For 2 minutes after the window closes, late events are still accepted
- Events arriving >2 minutes late are dropped (or routed to a late-data topic if configured)

**Suppress.untilWindowCloses()**:
Without this, the KTable emits an update every time a new user is added to the set — potentially thousands of updates per window. With `suppress()`, only the final count after the window closes + grace period elapses is emitted. This is essential for producing clean "1 result per window" output.

**Late data routing** (beyond grace period):
```java
events
    .filter((k, v) -> isLate(v))  // custom check vs watermark
    .to("late-user-events");  // route late events separately for analysis
```

**Windowed output key**:
The output key is a `Windowed<String>` containing both the original key and the window bounds. Use `windowedKey.window().start()` and `.end()` to get window timestamps.

---

## Quick Reference: Key Configuration Properties

| Property | Default | Tuning Notes |
|---|---|---|
| `linger.ms` | 0 | Set 5–20ms for throughput |
| `batch.size` | 16384 | Increase to 256KB–1MB for throughput |
| `acks` | all | Use `1` for speed, `all` for durability |
| `compression.type` | none | `lz4` for balanced, `zstd` for max compression |
| `max.poll.records` | 500 | Reduce for slow processing |
| `max.poll.interval.ms` | 300000 | Increase for batch processing |
| `auto.offset.reset` | latest | `earliest` only if you need full history |
| `enable.auto.commit` | true | Always set `false` in production |
| `min.insync.replicas` | 1 | Set `2` for durability |
| `retention.ms` | 604800000 (7d) | Tune per throughput |
| `num.stream.threads` | 1 | Set to ~CPU cores for Kafka Streams |
| `cache.max.bytes.buffering` | 10MB | Set `0` to disable KTable caching |
| `commit.interval.ms` | 30000 | Lower for fresher KTable state |
| `replica.lag.time.max.ms` | 30000 | Tune based on replication network |

---

*This guide covers the depth expected at principal/staff engineer level. Pair each answer with real production examples from your own experience.*
