# Kafka Configuration Reference
### Topic · Partition · Producer · Consumer · Consumer Group

> Complete configuration guide for senior Java backend engineers. Every property includes default, production recommendation, and the "why" behind it.

---

## Table of Contents

1. [Topic Configuration](#1-topic-configuration)
2. [Partition Configuration](#2-partition-configuration)
3. [Producer Configuration](#3-producer-configuration)
4. [Consumer Configuration](#4-consumer-configuration)
5. [Consumer Group Configuration](#5-consumer-group-configuration)
6. [Production-Ready Config Templates](#6-production-ready-config-templates)
7. [Configuration Interaction Map](#7-configuration-interaction-map)
8. [Quick Reference Tables](#8-quick-reference-tables)

---

## 1. Topic Configuration

Topic configs control how data is stored, replicated, and retained. Set at the broker level as defaults, overridden per-topic using `kafka-configs.sh`.

```bash
# Override config on an existing topic
kafka-configs.sh --bootstrap-server localhost:9092 \
  --entity-type topics --entity-name my-topic \
  --alter --add-config retention.ms=86400000,min.insync.replicas=2
```

---

### `num.partitions`
| | |
|---|---|
| Default | `1` |
| Production | `12–48` |
| Scope | Broker default (per-topic at creation) |

Controls the default number of partitions when a new topic is created without specifying partition count.

**Why it matters**: Partitions are the unit of parallelism. A consumer group can scale up to at most the number of partitions — extra consumers sit idle. Partitions can only be **added, never removed** after creation.

**How to decide partition count**:
- Start with: `max expected consumers × 2` for headroom
- Consider throughput: each partition handles ~50–100 MB/s safely
- Consider lag recovery: more partitions = faster catch-up by parallel consumers
- Common production values: `12`, `24`, `48`

```bash
kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic orders --partitions 24 --replication-factor 3
```

---

### `replication.factor`
| | |
|---|---|
| Default | `1` |
| Production | `3` |
| Scope | Broker default (per-topic at creation) |

How many copies of each partition exist across brokers. One copy is the leader (handles reads/writes), the rest are followers (replicate silently).

**Why 3 is the magic number**:
- Survives 2 simultaneous broker failures
- `replication.factor=2` leaves zero redundancy while the replacement is syncing — avoid it
- Must be ≤ number of brokers in the cluster

**Cost**: Each byte written is replicated `replication.factor` times. 100 MB/s producer with RF=3 = 300 MB/s of inter-broker replication traffic.

---

### `min.insync.replicas`
| | |
|---|---|
| Default | `1` |
| Production | `2` |
| Scope | Broker default + per-topic override |
| **Risk** | Default is dangerous |

The minimum number of replicas (including leader) that must have acknowledged a write before the producer receives success. Only enforced when `acks=all` on the producer.

**The safety math**:
```
replication.factor=3, min.insync.replicas=2, acks=all

Can tolerate: 1 broker failure (2 of 3 still alive = meets min.insync.replicas)
Cannot write if: 2 brokers fail (1 of 3 alive = below min.insync.replicas)
```

**What happens when ISR shrinks below `min.insync.replicas`**:
The partition becomes **write-unavailable**. Producers get `NotEnoughReplicasException`. The partition is still readable (up to the current High Watermark), just not writable. This prevents writing data that only one replica would have — a single point of failure.

```bash
# Set per topic
kafka-configs.sh --bootstrap-server localhost:9092 \
  --entity-type topics --entity-name orders \
  --alter --add-config min.insync.replicas=2
```

---

### `retention.ms`
| | |
|---|---|
| Default | `604800000` (7 days) |
| Production | Per use-case |
| Scope | Per-topic |

How long Kafka retains messages before deleting old log segments. After this period, the oldest segments are eligible for deletion.

**Common settings by use case**:

| Use Case | Setting |
|---|---|
| Real-time event stream | `86400000` (1 day) |
| Order / transaction events | `604800000` (7 days) |
| Audit log | `-1` (infinite) |
| Metrics / telemetry | `3600000` (1 hour) |
| Event sourcing | `-1` (infinite) |

**`retention.ms` vs `retention.bytes`**: Use `retention.bytes` for high-throughput topics where disk usage must be predictable. Use `retention.ms` for time-based regulatory requirements.

---

### `retention.bytes`
| | |
|---|---|
| Default | `-1` (disabled) |
| Production | Set for high-volume topics |
| Scope | Per-topic, per-partition |

Maximum size of the log **per partition** before old segments are deleted. Total disk used = `retention.bytes × num.partitions × replication.factor`.

**Example planning**:
```
retention.bytes = 5 GB per partition
num.partitions  = 20
replication.factor = 3

Total disk = 5 GB × 20 × 3 = 300 GB
```

Both `retention.ms` and `retention.bytes` can be set — whichever limit is reached first triggers deletion.

---

### `cleanup.policy`
| | |
|---|---|
| Default | `delete` |
| Production | `delete` or `compact` or `delete,compact` |
| Scope | Per-topic |

Controls what happens to old data:

- **`delete`**: Deletes segments that exceed `retention.ms` or `retention.bytes`. Messages are permanently gone after the retention window.

- **`compact`**: Keeps only the **latest value per key**. Older records with the same key are removed during compaction. A record with `value=null` is a **tombstone** — it marks the key for deletion and is itself removed after `delete.retention.ms`.

- **`delete,compact`**: Both policies apply — compacts to latest-per-key AND caps total size.

**When to use `compact`**:
- Kafka Streams KTable changelog topics (stores current state, not history)
- CDC (Change Data Capture) topics keyed by primary key
- Configuration distribution topics (latest config per service)
- Event sourcing snapshot topics

```bash
kafka-configs.sh --bootstrap-server localhost:9092 \
  --entity-type topics --entity-name account-balances \
  --alter --add-config cleanup.policy=compact,min.cleanable.dirty.ratio=0.1
```

---

### `max.message.bytes`
| | |
|---|---|
| Default | `1048588` (~1 MB) |
| Production | Match with producer + consumer settings |
| Scope | Per-topic |

Maximum size of a single compressed message batch. If a producer sends a larger record, it receives `RecordTooLargeException` immediately without retrying.

**Must coordinate across all three**:
```
topic:    max.message.bytes      = 10485760  (10 MB)
producer: message.max.bytes      = 10485760  (10 MB)  ← same
consumer: fetch.max.bytes        = 52428800  (50 MB)  ← >= topic setting
consumer: max.partition.fetch.bytes = 10485760 (10 MB) ← >= topic setting
```

**Prefer smaller messages**: Large messages hurt throughput, increase broker memory pressure, and complicate replication. For genuinely large payloads (images, documents), store in object storage (S3/GCS) and send the URL in Kafka.

---

### `unclean.leader.election.enable`
| | |
|---|---|
| Default | `false` |
| Production | `false` |
| Scope | Broker + per-topic |

Controls what happens when all ISR replicas for a partition are offline.

- **`false` (recommended)**: Partition stays offline until an ISR member recovers. No data loss.
- **`true`**: An out-of-ISR replica is promoted to leader. Partition recovers but any messages the old leader had that this replica never replicated are **permanently lost**.

Only set `true` for non-critical topics where availability > durability (e.g., real-time dashboards, telemetry).

---

### `compression.type`
| | |
|---|---|
| Default | `producer` |
| Production | `producer` (almost always) |
| Scope | Per-topic |

Controls broker-side compression:

- **`producer`**: Store data using whatever compression the producer chose. Zero extra CPU on broker.
- **`none`**: Decompress on arrival and store uncompressed. Wastes disk.
- **`lz4`/`snappy`/`gzip`/`zstd`**: Broker recompresses all data — adds CPU cost.

Keep at `producer` — let producers choose their compression and pay the CPU cost there, not on the broker where it affects all workloads.

---

## 2. Partition Configuration

Partition-level configs affect how the log files are managed on disk and how internal topics are set up.

---

### `auto.create.topics.enable`
| | |
|---|---|
| Default | `true` |
| Production | `false` |
| Scope | Broker |

When `true`, Kafka automatically creates topics when a producer or consumer references a non-existent topic name. The topic is created with defaults from `num.partitions` and `replication.factor` in `server.properties`.

**Always set `false` in production**. A typo in a topic name silently creates a junk topic with 1 partition and 1 replica. Explicit topic creation via `kafka-topics.sh` or Terraform/Ansible gives you control over partition count and replication.

```properties
# server.properties
auto.create.topics.enable=false
```

---

### `log.retention.check.interval.ms`
| | |
|---|---|
| Default | `300000` (5 minutes) |
| Production | `300000` |
| Scope | Broker |

How often the log cleaner checks whether any segments have exceeded their retention limits. Lower value = more frequent checks = faster segment deletion after retention limit is hit, but more I/O overhead on the broker.

The default 5 minutes is appropriate for most workloads. Only reduce if you have very tight disk constraints and need near-instant cleanup after the retention window closes.

---

### `offsets.topic.num.partitions`
| | |
|---|---|
| Default | `50` |
| Production | `50` (set before first use) |
| Scope | Broker |
| **Warning** | Cannot change after creation |

The number of partitions for the internal `__consumer_offsets` topic. Every consumer group's offset commits land on partition `hash(group_id) % offsets.topic.num.partitions`. The broker leading that partition is the **group coordinator** for that consumer group.

50 partitions distributes coordinator load well for clusters with hundreds of consumer groups. This setting must be configured **before any consumer group ever connects** — `__consumer_offsets` is created on first use and partition count cannot change afterwards.

---

### `offsets.topic.replication.factor`
| | |
|---|---|
| Default | `3` |
| Production | `3` |
| Scope | Broker |

Replication factor for `__consumer_offsets`. Must match your cluster's standard replication factor. If this topic becomes unavailable, **all consumer groups lose their offset tracking** — a catastrophic failure. Always match production replication factor (3).

Like `offsets.topic.num.partitions`, this is fixed at `__consumer_offsets` creation time.

---

### `log.segment.delete.delay.ms`
| | |
|---|---|
| Default | `60000` (1 minute) |
| Production | `300000` (5 minutes) recommended |
| Scope | Broker |

After a log segment is marked for deletion (retention exceeded or topic deleted), Kafka waits this long before physically removing the file. This gives slow consumers time to finish reading from the segment before it disappears.

If you see `OffsetOutOfRangeException` on consumers that are near the retention boundary, increase this delay. 60 seconds is often too tight for consumers that process in large batches.

---

### `delete.topic.enable`
| | |
|---|---|
| Default | `true` |
| Production | `true` (control via ACLs) |
| Scope | Broker |

When `true`, `kafka-topics.sh --delete` actually works. When `false`, delete commands are silently ignored — topics can only be disabled, not removed.

Keep `true` but control who can delete topics via Kafka ACLs. Topic deletion is asynchronous — the topic is marked for deletion and the Controller handles segment cleanup in the background. The topic disappears from metadata immediately but files are cleaned up over the next few minutes.

---

### `num.replica.fetchers`
| | |
|---|---|
| Default | `1` |
| Production | `4` for high-throughput clusters |
| Scope | Broker |

Number of fetcher threads used by follower replicas to replicate from the leader. More threads = faster replication = shorter ISR lag = smaller window for data loss on leader failure.

For clusters with many partitions or high write throughput, increase to 4–8. Each fetcher thread handles multiple partitions concurrently.

---

## 3. Producer Configuration

Producer configs control how records are serialized, batched, compressed, and delivered to brokers. Set in `Properties` passed to `KafkaProducer`.

```java
Properties props = new Properties();
props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker1:9092,broker2:9092,broker3:9092");
props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
KafkaProducer<String, String> producer = new KafkaProducer<>(props);
```

---

### `bootstrap.servers`
| | |
|---|---|
| Default | (required) |
| Production | List all brokers |
| Risk | Single point of failure if only 1 listed |

Comma-separated list of `host:port` pairs. Only a subset is needed for initial connection — the producer discovers the full cluster metadata from any broker. However, if only one broker is listed and it is down at startup, the producer cannot connect.

**Best practice**: List all brokers in the cluster. The producer will find working ones automatically.

```java
props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
    "broker1:9092,broker2:9092,broker3:9092");
```

---

### `acks`
| | |
|---|---|
| Default | `all` (since Kafka 3.0) |
| Production | `all` |
| Options | `0`, `1`, `all` (or `-1`) |

Controls durability vs latency trade-off:

| Value | Behavior | Latency | Durability |
|---|---|---|---|
| `0` | Fire and forget — no broker confirmation | Lowest | None — data loss possible |
| `1` | Leader writes to log and acks | Low | Data loss if leader crashes before replication |
| `all` | All ISR replicas must confirm | Higher | No data loss (with min.insync.replicas=2) |

**`acks=all` + `min.insync.replicas=2`** is the standard production durability combination. The extra latency from waiting for follower replication is typically 5–30ms — negligible for most applications.

Use `acks=1` only for truly non-critical, high-volume, low-latency topics (metrics dashboards, access logs) where occasional message loss is acceptable.

---

### `retries` and `delivery.timeout.ms`
| | |
|---|---|
| `retries` default | `MAX_INT` |
| `delivery.timeout.ms` default | `120000` (2 minutes) |
| Production | Keep defaults; tune `delivery.timeout.ms` |

These two settings work together:
- `retries`: How many retry attempts per failed request. `MAX_INT` means retry until `delivery.timeout.ms` expires.
- `delivery.timeout.ms`: The total time budget for a record to be delivered — covers accumulator wait + network + all retries.

With `enable.idempotence=true` (default), retries are **safe** — the broker deduplicates using sequence numbers. Without idempotence, retries can produce duplicates.

```java
// For slow broker environments
props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 300000);  // 5 minutes
props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 60000);     // 60 seconds per attempt
```

---

### `batch.size` and `linger.ms`
| | |
|---|---|
| `batch.size` default | `16384` (16 KB) |
| `linger.ms` default | `0` |
| Production | `batch.size=131072`, `linger.ms=5` |

These two are the **most impactful throughput tunables** for producers. They work together:

```
Record accumulator (per partition)
├── Records accumulate in a batch
├── Batch sends when: batch.size reached OR linger.ms expires
└── Sender thread drains accumulated batches → sends ProduceRequest
```

**`linger.ms=0`**: Send immediately. One network round-trip per record under low load. Terrible throughput.

**`linger.ms=5`**: Wait 5ms to accumulate a batch. Under any meaningful load, this 5ms wait fills batches, reducing network calls by 10–100x. The latency cost of 5ms is usually imperceptible.

**Real-world tuning**:
```java
// High-throughput (logs, events, metrics)
props.put(ProducerConfig.BATCH_SIZE_CONFIG, 524288);      // 512 KB
props.put(ProducerConfig.LINGER_MS_CONFIG, 20);            // 20ms

// Balanced (general production)
props.put(ProducerConfig.BATCH_SIZE_CONFIG, 131072);      // 128 KB
props.put(ProducerConfig.LINGER_MS_CONFIG, 5);             // 5ms

// Low-latency (payment confirmations, alerts)
props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);       // 16 KB (default)
props.put(ProducerConfig.LINGER_MS_CONFIG, 0);             // immediate
```

---

### `buffer.memory` and `max.block.ms`
| | |
|---|---|
| `buffer.memory` default | `33554432` (32 MB) |
| `max.block.ms` default | `60000` (1 minute) |
| Production | Tune `buffer.memory` based on partition count |

`buffer.memory` is the total memory pool for the producer's record accumulator — shared across all partitions. When full (broker back-pressure), `send()` **blocks** the calling thread for up to `max.block.ms` before throwing `TimeoutException`.

**Size calculation**:
```
buffer.memory >= batch.size × active_partitions × in-flight-factor
```

For a producer writing to 40 partitions with 512KB batches:
```
minimum: 512KB × 40 = 20 MB
recommended: 512KB × 40 × 2 = 40 MB (headroom for buffering during back-pressure)
```

---

### `compression.type`
| | |
|---|---|
| Default | `none` |
| Production | `lz4` or `zstd` |
| **Risk** | Default wastes bandwidth and disk |

Compression is applied per-batch. Because batches contain multiple records with shared structure (JSON field names, timestamps, similar values), compression ratios are far better than single-message compression.

| Codec | Compression Ratio | CPU Cost | Best For |
|---|---|---|---|
| `none` | 1x | None | Never, basically |
| `lz4` | ~3x (text/JSON) | Very low | General production |
| `snappy` | ~2.5x | Low | CPU-constrained producers |
| `zstd` | ~5x | Moderate | Bandwidth-constrained, cross-DC replication |
| `gzip` | ~4x | High | Maximum compression, archival |

```java
props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
```

---

### `enable.idempotence`
| | |
|---|---|
| Default | `true` (since Kafka 3.0) |
| Production | `true` — never disable |
| Cost | Zero performance overhead |

When enabled:
1. Broker assigns the producer a **Producer ID (PID)**
2. Each message gets a monotonically increasing **sequence number** per partition
3. On retry, the broker checks sequence number — if already seen, it **ignores the duplicate** and returns success
4. Automatically configures: `acks=all`, `retries=MAX_INT`, `max.in.flight.requests.per.connection=5`

This gives you exactly-once **within a single producer session** — no duplicates from retries. There is no performance downside. Always keep it enabled.

---

### `transactional.id`
| | |
|---|---|
| Default | `null` (transactions disabled) |
| Production | Set for exactly-once semantics |

Enables Kafka transactions — atomic writes across multiple partitions and atomic offset commits.

```java
Properties props = new Properties();
props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "payment-processor-instance-0");
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
props.put(ProducerConfig.ACKS_CONFIG, "all");

KafkaProducer<String, String> producer = new KafkaProducer<>(props);
producer.initTransactions();

producer.beginTransaction();
try {
    producer.send(new ProducerRecord<>("output-topic", key, value));
    producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
    producer.commitTransaction();
} catch (KafkaException e) {
    producer.abortTransaction();
}
```

**Zombie fencing**: If two instances use the same `transactional.id`, the older one gets `ProducerFencedException` and is shut down. Only the newest instance can write. Use a stable, unique ID per logical producer (e.g., `service-name-partition-id`).

---

### `max.in.flight.requests.per.connection`
| | |
|---|---|
| Default | `5` |
| Production | `5` (with idempotence) or `1` (without) |

Number of unacknowledged requests the producer can have open to a single broker connection simultaneously. Higher = more pipelining = better throughput.

With `enable.idempotence=true`: up to `5` is safe — ordering is maintained because the broker reorders based on sequence numbers.

Without idempotence: set to `1` — a retry of request N can otherwise arrive after request N+1, causing out-of-order delivery.

Setting `>5` with `enable.idempotence=true` causes `IllegalArgumentException` at producer startup.

---

## 4. Consumer Configuration

Consumer configs control how records are fetched, how offsets are managed, and how the consumer participates in a consumer group. Set in `Properties` passed to `KafkaConsumer`.

```java
Properties props = new Properties();
props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker1:9092,broker2:9092,broker3:9092");
props.put(ConsumerConfig.GROUP_ID_CONFIG, "order-service-prod");
props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
```

---

### `group.id`
| | |
|---|---|
| Default | (required for subscribe()) |
| Production | service-name-env |
| Risk | Wrong group.id = wrong offset tracking |

All consumers with the same `group.id` cooperate to consume a topic — each partition goes to exactly one consumer. Two services with different `group.id` values both receive all messages independently.

**Naming convention**:
```
order-service-prod
payment-processor-staging
analytics-pipeline-v2
```

A typo or wrong `group.id` creates an entirely new group at `auto.offset.reset` position — potentially replaying months of data or skipping current messages.

---

### `auto.offset.reset`
| | |
|---|---|
| Default | `latest` |
| Production | `latest` (with pre-set offsets for new groups) |
| **Risk** | Silently skips or replays large amounts of data |

Applies **only** when the consumer group has **no committed offset** for a partition:
- New consumer group (never consumed this topic)
- Group's committed offsets expired (7-day `offsets.retention.minutes` default)
- Group was deleted and recreated

| Value | Behavior | Risk |
|---|---|---|
| `latest` | Start at current end | Silently skips all historical messages |
| `earliest` | Start from Log Start Offset | Replays all retained history |
| `none` | Throw `NoOffsetForPartitionException` | Fails loudly — forces explicit handling |

**Best practice**: Always pre-set offsets for new consumer groups before first deployment:
```bash
# Pre-set to latest before deploying new service
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group order-service-prod \
  --topic orders \
  --reset-offsets --to-latest --execute
```

---

### `enable.auto.commit`
| | |
|---|---|
| Default | `true` |
| Production | `false` — always |
| **Risk** | Message loss (at-most-once delivery) |

When `true`, a background thread commits the current position every `auto.commit.interval.ms`. The commit fires based on time, **not** processing completion. A crash mid-batch = those records are marked committed and will never be redelivered.

```java
// ALWAYS set false in production
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

// Correct at-least-once pattern:
while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    for (ConsumerRecord<String, String> record : records) {
        process(record);  // finish processing FIRST
    }
    consumer.commitSync();  // THEN commit
}
```

---

### `max.poll.records`
| | |
|---|---|
| Default | `500` |
| Production | Tune to processing throughput |

Maximum records returned per `poll()` call. Controls the processing batch size.

**The critical relationship with `max.poll.interval.ms`**:
```
max safe batch size = max.poll.interval.ms / time_per_record

Example: 300s interval, 10ms per record → 30,000 max records
Example: 300s interval, 500ms per record → 600 max records
```

```java
// For slow downstream calls (DB writes, HTTP calls)
props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);

// For fast in-memory processing
props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 2000);
```

---

### `max.poll.interval.ms`
| | |
|---|---|
| Default | `300000` (5 minutes) |
| Production | Must be > max batch processing time |
| **Risk** | Rebalance storms if set too low |

If `poll()` is not called within this interval, the broker **assumes the consumer is stuck** and removes it from the group (triggering rebalance). This is **NOT** heartbeat-based — the heartbeat thread runs independently. This specifically detects processing that takes too long.

**Common mistakes**:
- Long database transaction inside the processing loop → set `max.poll.interval.ms` higher
- Calling an external REST API with no timeout → add timeouts and reduce `max.poll.records`
- Heavy batch processing → increase to 1800000 (30 min) for batch-style consumers

```java
// For batch processing jobs (e.g., writing to DB in bulk)
props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 1800000);  // 30 min
props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
```

---

### `session.timeout.ms` and `heartbeat.interval.ms`
| | |
|---|---|
| `session.timeout.ms` default | `45000` (45 sec) |
| `heartbeat.interval.ms` default | `3000` (3 sec) |
| Production | Consider reducing session.timeout.ms to 15–20s |

These two control **crash detection** (not processing speed detection):

- **`session.timeout.ms`**: If the broker doesn't receive a heartbeat within this window, the consumer is declared dead and the group rebalances.
- **`heartbeat.interval.ms`**: How often the background heartbeat thread sends heartbeats. Must be < `session.timeout.ms / 3`.

The heartbeat thread runs completely independently of your `poll()` loop — even if your processing is slow, heartbeats continue. `session.timeout.ms` only fires if the **JVM crashes**, the machine dies, or the network completely partitions.

```java
// Faster crash detection (at cost of GC pause sensitivity)
props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 15000);      // 15 sec
props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 5000);    // 5 sec
```

---

### `fetch.min.bytes` and `fetch.max.wait.ms`
| | |
|---|---|
| `fetch.min.bytes` default | `1` |
| `fetch.max.wait.ms` default | `500` |
| Production | Tune together based on latency/throughput needs |

These two configure broker-side fetch batching:

- **`fetch.min.bytes`**: Broker waits until this many bytes are available before responding
- **`fetch.max.wait.ms`**: Even if `fetch.min.bytes` isn't met, respond after this many ms

```
Consumer sends FetchRequest → Broker checks available data
├── data >= fetch.min.bytes → respond immediately
└── data < fetch.min.bytes → wait up to fetch.max.wait.ms, then respond with what's available
```

**Tuning profiles**:
```java
// Real-time (low latency)
props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);
props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 100);

// High-throughput batch
props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1048576);   // 1 MB
props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 1000);    // 1 second
```

---

### `isolation.level`
| | |
|---|---|
| Default | `read_uncommitted` |
| Production | `read_committed` when using transactions |
| Risk | Default exposes consumers to aborted transaction data |

Controls which messages are visible to the consumer:

- **`read_uncommitted`**: Consumer sees all messages including those in open or aborted transactions. Fast, but may deliver messages that are later aborted.
- **`read_committed`**: Consumer only sees messages from **committed** transactions. Messages from aborted transactions are silently skipped. Open transaction messages are held back until the transaction commits or aborts.

```java
// Required when consuming from transactional producers
props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
```

---

### `partition.assignment.strategy`
| | |
|---|---|
| Default | `RangeAssignor` |
| Production | `CooperativeStickyAssignor` |
| Risk | Default causes stop-the-world rebalances |

Controls how partitions are distributed among consumers in a group when a rebalance occurs.

| Strategy | Balance | Rebalance Type | Recommendation |
|---|---|---|---|
| `RangeAssignor` | Uneven with multiple topics | Stop-the-world | Avoid |
| `RoundRobinAssignor` | Even | Stop-the-world | Better than Range |
| `StickyAssignor` | Even + minimal movement | Stop-the-world | Good |
| `CooperativeStickyAssignor` | Even + minimal movement | **Incremental** | **Best** |

**Why `CooperativeStickyAssignor` matters**: With eager (stop-the-world) rebalance, ALL consumers stop processing while partitions are reassigned — even if only 1 partition needs to move. With cooperative rebalance, only the partitions actually changing hands are paused. For a 40-partition topic where 1 partition moves: 39 partitions never stop.

```java
props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
    CooperativeStickyAssignor.class.getName());
```

---

## 5. Consumer Group Configuration

Consumer group configs are set at the **broker level** (`server.properties` or `kafka-configs.sh`) and control how the broker manages group membership, offset storage, and rebalances.

---

### `group.instance.id` (Static Membership)
| | |
|---|---|
| Default | `null` (dynamic membership) |
| Production | Set for Kubernetes deployments |
| Set on | Consumer (client config) |

When set, the consumer uses **static group membership**. Behavior change:
- Consumer disconnects (crash, restart, rolling deploy) → **no rebalance triggered**
- Broker holds the consumer's partition assignments for `session.timeout.ms`
- Consumer reconnects with same `group.instance.id` → gets its old partitions back immediately

Without static membership, every rolling restart of a 10-pod service causes 10 sequential rebalances. With static membership: zero rebalances.

```java
// Set to pod name for Kubernetes
String podName = System.getenv("POD_NAME");  // e.g., "order-service-pod-3"
props.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, podName);
```

---

### `offsets.retention.minutes`
| | |
|---|---|
| Default | `10080` (7 days) |
| Production | `43200` (30 days) for infrequent consumers |
| Scope | Broker |

How long committed offsets for a consumer group are retained. If a consumer group doesn't commit any offsets for this period, **all its committed offsets are deleted**. The next time the group starts, it has no position — `auto.offset.reset` applies.

**Common trap**: A batch job that runs weekly will lose its committed position after 7 days. After 2 weeks off, it replays from `earliest` or starts at `latest` depending on your setting.

```properties
# server.properties
offsets.retention.minutes=43200  # 30 days
```

---

### `group.initial.rebalance.delay.ms`
| | |
|---|---|
| Default | `3000` (3 seconds) |
| Production | `10000–30000` for large deployments |
| Scope | Broker |

When a consumer group first forms (no existing members), the coordinator waits this long before triggering the initial partition assignment. This allows multiple consumers starting simultaneously (e.g., 10 pods starting at deploy time) to all join before assignment happens — avoiding 10 sequential rebalances.

Without this delay:
1. Pod 1 joins → assignment to Pod 1 (10 partitions)
2. Pod 2 joins → rebalance (5+5)
3. Pod 3 joins → rebalance (4+3+3)
4. ... 10 rebalances total

With `group.initial.rebalance.delay.ms=10000`: all 10 pods join, then one assignment happens.

---

### `rebalance.timeout.ms`
| | |
|---|---|
| Default | `300000` (5 minutes) |
| Production | Match `max.poll.interval.ms` |
| Scope | Broker / GroupCoordinator |

After a rebalance is triggered, all current group members must send a `JoinGroup` request within this window. Consumers that don't join in time are **removed from the group**, which can trigger another rebalance — creating a rebalance storm.

Should be >= `max.poll.interval.ms` to ensure slow-but-alive consumers have time to finish their current batch and rejoin.

---

### `group.min.session.timeout.ms` / `group.max.session.timeout.ms`
| | |
|---|---|
| Min default | `6000` (6 sec) |
| Max default | `1800000` (30 min) |
| Scope | Broker |

Broker-enforced bounds on what consumers can set for `session.timeout.ms`. Consumers requesting a value outside this range get `InvalidSessionTimeoutException` on group join.

Tighten the max in production to prevent consumers from setting very long session timeouts (which would delay dead consumer detection):
```properties
group.max.session.timeout.ms=300000  # 5 minutes maximum
```

---

### `offsets.commit.timeout.ms`
| | |
|---|---|
| Default | `5000` (5 sec) |
| Production | Default usually fine |
| Scope | Broker |

How long the group coordinator waits for all partition leaders to acknowledge the offset commit before timing out. Frequent `CommitFailedException` from timeouts usually indicates a sick group coordinator or network issue — investigate the broker, not this config.

---

## 6. Production-Ready Config Templates

### Topic — Standard Production

```properties
# Create with explicit settings
kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic orders \
  --partitions 24 \
  --replication-factor 3 \
  --config min.insync.replicas=2 \
  --config retention.ms=604800000 \
  --config compression.type=producer \
  --config max.message.bytes=1048588 \
  --config cleanup.policy=delete
```

### Topic — Compacted (KTable Changelog / CDC)

```properties
kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic account-balances \
  --partitions 24 \
  --replication-factor 3 \
  --config cleanup.policy=compact \
  --config min.insync.replicas=2 \
  --config min.cleanable.dirty.ratio=0.1 \
  --config segment.bytes=268435456 \
  --config delete.retention.ms=86400000
```

### Producer — High Throughput

```java
Properties props = new Properties();
props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,     "broker1:9092,broker2:9092,broker3:9092");
props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,  StringSerializer.class.getName());
props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class.getName());
props.put(ProducerConfig.ACKS_CONFIG,                  "all");
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,    true);
props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG,      "lz4");
props.put(ProducerConfig.BATCH_SIZE_CONFIG,            524288);    // 512 KB
props.put(ProducerConfig.LINGER_MS_CONFIG,             20);        // 20ms
props.put(ProducerConfig.BUFFER_MEMORY_CONFIG,         134217728); // 128 MB
props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION_CONFIG, 5);
props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,   120000);
props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,    30000);
```

### Producer — Low Latency

```java
Properties props = new Properties();
props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,     "broker1:9092,broker2:9092,broker3:9092");
props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,  StringSerializer.class.getName());
props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class.getName());
props.put(ProducerConfig.ACKS_CONFIG,                  "1");          // leader ack only
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,    false);        // can't combine with acks=1 in strict mode
props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG,      "lz4");
props.put(ProducerConfig.BATCH_SIZE_CONFIG,            16384);        // 16 KB (default)
props.put(ProducerConfig.LINGER_MS_CONFIG,             0);            // immediate send
props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION_CONFIG, 5);
```

### Producer — Exactly-Once (Transactional)

```java
Properties props = new Properties();
props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,       "broker1:9092,broker2:9092,broker3:9092");
props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,    StringSerializer.class.getName());
props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,  StringSerializer.class.getName());
props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG,        "payment-processor-" + instanceId);
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,      true);    // auto-set by transactional.id
props.put(ProducerConfig.ACKS_CONFIG,                    "all");   // auto-set by transactional.id
props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG,        "lz4");
props.put(ProducerConfig.BATCH_SIZE_CONFIG,              131072);
props.put(ProducerConfig.LINGER_MS_CONFIG,               5);
```

### Consumer — Standard At-Least-Once

```java
Properties props = new Properties();
props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,          "broker1:9092,broker2:9092,broker3:9092");
props.put(ConsumerConfig.GROUP_ID_CONFIG,                   "order-service-prod");
props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,     StringDeserializer.class.getName());
props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class.getName());
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,         false);
props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,          "latest");
props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,           500);
props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,       300000);
props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,         45000);
props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG,      15000);
props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG,            1);
props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG,          500);
props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
    CooperativeStickyAssignor.class.getName());
```

### Consumer — Static Membership (Kubernetes)

```java
Properties props = new Properties();
// ... all standard consumer props above ...
props.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG,
    System.getenv("POD_NAME"));  // stable pod name from Downward API
props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 60000);  // higher timeout for static
```

### Consumer — Transactional (EOS)

```java
Properties props = new Properties();
// ... standard consumer props ...
props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG,       "read_committed");
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,    false);  // mandatory — offset committed via producer transaction
props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,      100);    // smaller batches for transaction overhead
```

---

## 7. Configuration Interaction Map

Understanding how configs across components must be coordinated:

### Durability Chain

```
Producer             Topic                    Broker
acks=all        +    min.insync.replicas=2  + replication.factor=3
     │                      │                      │
     └──── all require each other to be meaningful ─────┘

If any one is missing:
  acks=1 alone      → leader failure = data loss
  min.isr=1 alone   → no minimum replica guarantee
  rf=2 alone        → no third copy as safety net
```

### Message Size Chain

```
Producer                  Topic                  Consumer
message.max.bytes    ≤   max.message.bytes   ≤   fetch.max.bytes
                                              ≤   max.partition.fetch.bytes

All three must be aligned. Mismatch = RecordTooLargeException or truncated messages.
```

### Offset Commit Chain

```
Consumer                        Broker
enable.auto.commit=false
max.poll.interval.ms=300000  >  group.max.session.timeout.ms (must be within range)
session.timeout.ms=45000     within [group.min, group.max].session.timeout.ms
heartbeat.interval.ms=15000  <  session.timeout.ms / 3
```

### Throughput Chain

```
Producer                      Network              Consumer
batch.size + linger.ms   →   compression   →   fetch.min.bytes + fetch.max.wait.ms
     ↑                                                  ↑
Tune together to                              Tune together to
fill batches efficiently                      fill fetch responses efficiently
```

---

## 8. Quick Reference Tables

### Topic Configs

| Property | Default | Production | Impact |
|---|---|---|---|
| `num.partitions` | `1` | `12–48` | Parallelism ceiling |
| `replication.factor` | `1` | `3` | Durability |
| `min.insync.replicas` | `1` | `2` | Write safety |
| `retention.ms` | `604800000` | Per use-case | Storage |
| `retention.bytes` | `-1` | Set for high-volume | Disk predictability |
| `cleanup.policy` | `delete` | `compact` for changelog | Storage model |
| `max.message.bytes` | `1048588` | Match producer/consumer | Message size |
| `unclean.leader.election.enable` | `false` | `false` | Data safety |
| `compression.type` | `producer` | `producer` | Storage efficiency |

### Producer Configs

| Property | Default | Production | Impact |
|---|---|---|---|
| `acks` | `all` | `all` | Durability |
| `enable.idempotence` | `true` | `true` | Dedup on retry |
| `compression.type` | `none` | `lz4` or `zstd` | Throughput + storage |
| `batch.size` | `16384` | `131072–524288` | Throughput |
| `linger.ms` | `0` | `5–20` | Throughput |
| `buffer.memory` | `33554432` | Increase for many partitions | Back-pressure |
| `retries` | `MAX_INT` | `MAX_INT` | Reliability |
| `delivery.timeout.ms` | `120000` | Tune per SLA | Total retry budget |
| `transactional.id` | `null` | Set for EOS | Exactly-once |

### Consumer Configs

| Property | Default | Production | Impact |
|---|---|---|---|
| `enable.auto.commit` | `true` | `false` | Delivery guarantee |
| `auto.offset.reset` | `latest` | `latest` + pre-set | Start position |
| `max.poll.records` | `500` | Tune to throughput | Batch size |
| `max.poll.interval.ms` | `300000` | > batch processing time | Rebalance trigger |
| `session.timeout.ms` | `45000` | `15000–45000` | Crash detection |
| `heartbeat.interval.ms` | `3000` | `< session / 3` | Liveness signal |
| `fetch.min.bytes` | `1` | `1` or `1048576` | Fetch latency/throughput |
| `isolation.level` | `read_uncommitted` | `read_committed` (EOS) | Transactional reads |
| `partition.assignment.strategy` | `RangeAssignor` | `CooperativeStickyAssignor` | Rebalance impact |

### Consumer Group Configs (Broker-Side)

| Property | Default | Production | Impact |
|---|---|---|---|
| `group.instance.id` | `null` | Pod name | Static membership |
| `offsets.retention.minutes` | `10080` | `43200` | Offset expiry |
| `group.initial.rebalance.delay.ms` | `3000` | `10000–30000` | Deploy rebalance storm |
| `rebalance.timeout.ms` | `300000` | Match `max.poll.interval.ms` | Rebalance completeness |
| `group.max.session.timeout.ms` | `1800000` | `300000` | Max allowed session |
| `offsets.topic.num.partitions` | `50` | `50` (set before use) | Offset commit distribution |
| `offsets.topic.replication.factor` | `3` | `3` | Offset durability |

---

### Config Risk Summary

| Config | Default Risk | Correct Production Setting |
|---|---|---|
| `replication.factor=1` | Data loss on any broker failure | `3` |
| `min.insync.replicas=1` | No minimum durability guarantee | `2` |
| `enable.auto.commit=true` | At-most-once delivery (message loss) | `false` |
| `auto.create.topics.enable=true` | Junk topics from typos | `false` |
| `compression.type=none` (producer) | Wasted bandwidth and disk | `lz4` |
| `linger.ms=0` | Poor batching under any load | `5–20` |
| `partition.assignment.strategy=RangeAssignor` | Stop-the-world rebalances | `CooperativeStickyAssignor` |
| `isolation.level=read_uncommitted` | Reads aborted transaction data | `read_committed` (with transactions) |
| `auto.offset.reset=earliest` | Replays months of history on new group | Pre-set offsets before deploy |

---

*The rule of thumb: every config that controls durability defaults to unsafe for performance reasons. Every config that controls delivery semantics defaults to the easiest-to-implement option, not the most correct one. Always review these explicitly for each service you deploy.*
