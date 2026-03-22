# Kafka Offset Management — Deep Dive

> Everything a senior Java backend engineer needs to know about how Kafka tracks, commits, manages, and recovers consumer positions.

---

## Table of Contents

1. [What is an Offset?](#1-what-is-an-offset)
2. [Types of Offsets](#2-types-of-offsets)
3. [The __consumer_offsets Topic](#3-the-__consumer_offsets-topic)
4. [Commit Strategies](#4-commit-strategies)
5. [auto.offset.reset](#5-autooffsetreset)
6. [Consumer Lag](#6-consumer-lag)
7. [Offset Reset — Manual Operations](#7-offset-reset--manual-operations)
8. [Exactly-Once and Transactional Offsets](#8-exactly-once-and-transactional-offsets)
9. [Storing Offsets Outside Kafka](#9-storing-offsets-outside-kafka)
10. [Common Bugs and Pitfalls](#10-common-bugs-and-pitfalls)
11. [Monitoring and Alerting](#11-monitoring-and-alerting)
12. [Quick Reference](#12-quick-reference)

---

## 1. What is an Offset?

An **offset** is a monotonically increasing integer that uniquely identifies every message written to a Kafka partition. Offsets start at 0 and never repeat within a partition.

```
Partition 0:
┌────────┬────────┬────────┬────────┬────────┬────────┐
│ msg A  │ msg B  │ msg C  │ msg D  │ msg E  │ msg F  │
│ off=0  │ off=1  │ off=2  │ off=3  │ off=4  │ off=5  │
└────────┴────────┴────────┴────────┴────────┴────────┘
```

Key properties:
- Offsets are **per-partition**, not global. Offset 5 on Partition 0 and Offset 5 on Partition 1 are completely different messages.
- Offsets are **immutable** — a message's offset never changes after it's written.
- Offsets are **sequential** — Kafka guarantees messages within a partition are delivered in offset order.
- Offsets are **used for consumer positioning** — a consumer group remembers its position in each partition by storing the last committed offset.

---

## 2. Types of Offsets

There are six distinct offset concepts in Kafka. Confusing them is the source of most offset-related bugs.

### 2.1 Log Start Offset (LSO / Earliest)

The **earliest available offset** in a partition. This is NOT necessarily 0. As Kafka deletes old log segments (due to retention policy), the Log Start Offset advances forward. Messages below the LSO are permanently gone.

```
Time passes, retention deletes old segments:

Before:  [0][1][2][3][4][5][6][7]   LSO = 0
After:   [4][5][6][7]               LSO = 4  (offsets 0-3 deleted)
```

If your consumer's committed offset is below the current LSO (e.g., you committed offset 2 but retention deleted offsets 0-3), the consumer gets an `OffsetOutOfRangeException`. The `auto.offset.reset` setting controls what happens next.

### 2.2 Log End Offset (LEO)

The **next offset to be written** on a specific replica. If 10 messages have been written (offsets 0-9), the LEO is 10.

Each replica maintains its own LEO. The leader's LEO is always the highest. Follower LEOs advance as they replicate from the leader.

```
Leader:    LEO = 10  (has offsets 0-9)
Follower1: LEO = 9   (has offsets 0-8, still replicating offset 9)
Follower2: LEO = 10  (fully caught up)
```

### 2.3 High Watermark (HW)

The **minimum LEO across all In-Sync Replicas (ISR)**. This represents the highest offset that ALL ISR members have successfully written.

```
Leader:    LEO = 10
Follower1: LEO = 9   ← HW is bounded by the slowest ISR member
Follower2: LEO = 10

HW = min(10, 9, 10) = 9
```

**Critical rule**: Consumers can only read messages up to the High Watermark. Even though the leader has offset 9, consumers cannot read it until Follower1 replicates it and HW advances to 10.

This prevents consumers from reading "uncommitted" data that might be rolled back if the leader crashes before replication completes.

### 2.4 Current Position (In-Memory)

The **in-flight position** of a consumer — the offset of the next message the consumer will fetch. This lives only in the consumer's memory and is NOT persisted.

If the consumer crashes without committing, its current position is lost and on restart it will resume from the last **committed offset**, potentially reprocessing messages.

```
Committed offset: 5
Current position: 8   (processing messages 6, 7, 8)

Consumer crashes → on restart, current position resets to 5
Messages 6, 7, 8 will be redelivered
```

### 2.5 Committed Offset

The **durable checkpoint** — the last offset explicitly committed by the consumer group. Stored in the `__consumer_offsets` internal topic. On restart, the consumer resumes from `committed offset + 1`.

The committed offset should point to the **next message to be consumed**, not the last message consumed. So if you successfully processed offset 5, you commit offset 6 (not 5).

```java
// After processing record at offset 5:
consumer.commitSync(Map.of(
    new TopicPartition("my-topic", 0),
    new OffsetAndMetadata(record.offset() + 1)  // commit 6, not 5
));
```

### 2.6 Last Stable Offset (LSO) — Transactional

When using Kafka transactions with `isolation.level=read_committed`, consumers read only up to the **Last Stable Offset** — the highest offset where all transactions have been either committed or aborted.

```
Offset: 0  1  2  3  4  5  6  7  8  9
        ✓  ✓  ✓  [txn start...]      ← open transaction
                                ↑
                               LSO = 3
Consumer with read_committed stops here, even though HW = 9
```

Messages within an open (uncommitted) transaction are held back from `read_committed` consumers. This prevents consumers from reading data that might be aborted.

---

## 3. The __consumer_offsets Topic

Kafka stores all consumer group offset commits in an internal topic called `__consumer_offsets`. Understanding its internals helps diagnose offset-related production issues.

### Structure

```
__consumer_offsets has 50 partitions by default
(controlled by offsets.topic.num.partitions)

Replication factor: 3 by default
(controlled by offsets.topic.replication.factor)

Cleanup policy: compact
(offsets for deleted consumer groups are tombstoned and cleaned up)
```

### What gets stored

Each commit writes a key-value record:

**Key** (identifies what is being committed):
```
group_id + topic + partition
e.g., "payment-service" + "orders" + 2
```

**Value** (the committed state):
```json
{
  "offset": 10045892,
  "metadata": "optional-user-metadata",
  "timestamp": 1706789123456,
  "leaderEpoch": 5
}
```

### Which partition of __consumer_offsets is used?

Kafka determines the partition for a consumer group's offset commits using:
```
partition = hash(group_id) % offsets.topic.num.partitions
```

All offsets for a given consumer group are written to the **same partition** of `__consumer_offsets`. The broker that leads that partition is the **offset coordinator** (group coordinator) for that consumer group.

### Inspecting __consumer_offsets

```bash
# Read raw contents of __consumer_offsets
kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic __consumer_offsets \
  --formatter "kafka.coordinator.group.GroupMetadataManager\$OffsetsMessageFormatter" \
  --from-beginning

# Output:
# [payment-service,orders,0]::OffsetAndMetadata[offset=10045892,leaderEpoch=5,metadata=,commitTimestamp=1706789123456]
```

### Retention of __consumer_offsets

Offset commits have a separate retention: `offsets.retention.minutes` (default: 10080 = 7 days).

If a consumer group doesn't commit any offsets for 7 days (e.g., it was stopped), all its committed offsets are deleted. The next time the consumer group starts, it has no committed offsets and `auto.offset.reset` applies.

This is a common production gotcha for seasonal consumers or batch jobs.

---

## 4. Commit Strategies

The commit strategy determines the delivery guarantee your consumer provides and how it recovers after a crash.

### 4.1 Auto Commit (enable.auto.commit=true) — Default, Dangerous

```java
Properties props = new Properties();
props.put("enable.auto.commit", "true");
props.put("auto.commit.interval.ms", "5000");  // commit every 5 seconds

KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
consumer.subscribe(List.of("my-topic"));

while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    for (ConsumerRecord<String, String> record : records) {
        processRecord(record);  // auto-commit happens independently of this
    }
}
```

**How it works**: A background thread commits the current position every `auto.commit.interval.ms`. The commit happens **when poll() is called**, not when processing finishes.

**The danger**: Auto-commit commits the offset of the LAST record returned by `poll()`, regardless of whether your application has finished processing those records.

```
Timeline:
poll() returns records [0, 1, 2, 3, 4]
App starts processing...
auto.commit fires → commits offset 5 (next after 4)
App crashes while processing record 2
↓
On restart: consumer starts at offset 5
Records 2, 3, 4 are PERMANENTLY SKIPPED
```

This is **at-most-once** delivery — you can lose messages. Avoid `enable.auto.commit=true` in any production application where message loss is unacceptable.

### 4.2 Manual Synchronous Commit (commitSync)

```java
props.put("enable.auto.commit", "false");
KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
consumer.subscribe(List.of("my-topic"));

while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    for (ConsumerRecord<String, String> record : records) {
        processRecord(record);
    }
    consumer.commitSync();  // blocks until broker confirms commit
}
```

**How it works**: After processing the entire batch, `commitSync()` sends a commit request to the broker and **blocks the calling thread** until the broker confirms it's stored in `__consumer_offsets`.

**Guarantee**: At-least-once. If processing succeeds and commit succeeds — exactly once seen by consumer. If commit fails, `commitSync()` **automatically retries** with backoff until success or an unrecoverable error.

**Drawback**: Blocking reduces throughput. Each batch incurs one network round-trip before the next poll can begin.

**When to use**: When message loss is unacceptable and throughput is not the primary concern. Good for transactional processing where correctness > speed.

```java
// Commit with specific offsets (more control)
Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
for (ConsumerRecord<String, String> record : records) {
    processRecord(record);
    offsets.put(
        new TopicPartition(record.topic(), record.partition()),
        new OffsetAndMetadata(record.offset() + 1)
    );
}
consumer.commitSync(offsets);
```

### 4.3 Manual Asynchronous Commit (commitAsync)

```java
while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    for (ConsumerRecord<String, String> record : records) {
        processRecord(record);
    }
    consumer.commitAsync((offsets, exception) -> {
        if (exception != null) {
            log.error("Commit failed for {}", offsets, exception);
            // Do NOT retry here — a later commitAsync may have already succeeded
        }
    });
}
```

**How it works**: Sends the commit request and returns immediately without waiting for broker confirmation. The optional callback fires when the commit completes (success or failure).

**Critical difference from commitSync**: `commitAsync()` does NOT retry on failure. This is intentional. If commit 5 fails and you retry, but commit 10 already succeeded, retrying commit 5 would roll back the offset to 5 — causing massive redelivery.

**The safe pattern — combine both**:

```java
try {
    while (true) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
        for (ConsumerRecord<String, String> record : records) {
            processRecord(record);
        }
        consumer.commitAsync();  // fast, non-blocking for normal operation
    }
} catch (Exception e) {
    log.error("Unexpected error", e);
} finally {
    try {
        consumer.commitSync();  // blocking, retrying commit on shutdown
    } finally {
        consumer.close();
    }
}
```

Use `commitAsync()` in the main loop for throughput, `commitSync()` on shutdown for correctness.

### 4.4 Per-Record Commit (Highest Guarantee, Lowest Throughput)

```java
while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    for (ConsumerRecord<String, String> record : records) {
        processRecord(record);
        consumer.commitSync(Map.of(
            new TopicPartition(record.topic(), record.partition()),
            new OffsetAndMetadata(record.offset() + 1)
        ));
    }
}
```

Commits after EVERY single message. Maximum correctness, minimum throughput. Only use when individual message loss would be catastrophic and throughput is low.

### 4.5 Batch Commit with Per-Partition Tracking

The most production-grade pattern for high-throughput systems:

```java
Map<TopicPartition, OffsetAndMetadata> latestOffsets = new HashMap<>();

while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    
    for (ConsumerRecord<String, String> record : records) {
        processRecord(record);
        // Track latest processed offset per partition
        latestOffsets.put(
            new TopicPartition(record.topic(), record.partition()),
            new OffsetAndMetadata(record.offset() + 1, record.leaderEpoch().orElse(null), null)
        );
    }
    
    if (!latestOffsets.isEmpty()) {
        consumer.commitAsync(latestOffsets, (offsets, exception) -> {
            if (exception != null) {
                log.warn("Async commit failed for {}", offsets, exception);
            }
        });
        latestOffsets.clear();
    }
}
```

This commits the highest processed offset per partition — efficient (one commit per poll cycle) and correct (only commits what's been processed).

### Commit Strategy Comparison

| Strategy | Delivery | Throughput | Risk |
|---|---|---|---|
| `enable.auto.commit=true` | At-most-once | Highest | Message loss on crash |
| `commitSync()` per batch | At-least-once | Medium | Redelivery on crash |
| `commitAsync()` per batch | At-least-once | High | Redelivery on crash |
| `commitSync()` per record | At-least-once | Lowest | Redelivery only per record |
| DB offset + `seek()` | Exactly-once* | Medium | Complexity |

*Exactly-once with external DB requires idempotent writes — see Section 9.

---

## 5. auto.offset.reset

`auto.offset.reset` is a **fallback setting** — it ONLY applies when a consumer group has NO committed offset for a partition. This happens when:

1. The consumer group is brand new (never consumed this topic).
2. The consumer group's committed offsets expired (7-day retention in `__consumer_offsets`).
3. The committed offset is below the Log Start Offset (retention deleted the referenced segment).
4. You manually deleted the consumer group.

If a committed offset exists, `auto.offset.reset` is **completely ignored**.

### earliest

```properties
auto.offset.reset=earliest
```

Consumer starts from the **Log Start Offset** (oldest available message). Replays all retained history.

**Bug scenario**: You deploy a new notification service against a `user-events` topic that has 6 months of data. With `earliest` and no prior committed offsets, the service processes 6 months of historical events — sending millions of stale notifications. Always check whether historical replay is intended before setting `earliest`.

**Legitimate uses**:
- New analytics job that needs full historical data.
- Event sourcing consumer bootstrapping its state from scratch.
- Data pipeline backfilling a new data store.

### latest

```properties
auto.offset.reset=latest
```

Consumer starts at the **High Watermark** (newest messages). Ignores all historical messages.

**Bug scenario**: A new `audit-log` consumer group starts. During the 2-second gap between the consumer group's first `subscribe()` call and when it actually begins polling, 500 events are produced. With `latest`, all 500 are silently skipped. The audit log has gaps from day one.

**Legitimate uses**:
- Real-time dashboards that only care about current state.
- Consumers where historical data would cause incorrect behavior (e.g., sending duplicate alerts for old events).

### none

```properties
auto.offset.reset=none
```

Throws `NoOffsetForPartitionException` if no committed offset exists. Forces you to handle the "no prior offset" case explicitly. Useful in strict production environments where silent behavior change would be dangerous.

### Setting offsets before first consumption

The safest approach for new consumer groups is to pre-set offsets before the first poll:

```bash
# Pre-set to latest before deploying a new consumer
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group my-new-consumer-group \
  --topic my-topic \
  --reset-offsets --to-latest \
  --execute

# Now deploy the consumer — it will find committed offsets at latest
# auto.offset.reset is never triggered
```

---

## 6. Consumer Lag

Consumer lag measures how far behind a consumer group is from the latest available data.

### Formula

```
lag per partition = High Watermark - Committed Offset

total lag = sum of lag across all partitions assigned to the consumer group
```

### Three States of Lag

**Healthy lag**: Lag is small and stable (or decreasing). Consumer is keeping up with the producer. A small, constant lag during peak hours is normal — the consumer is processing as fast as it can and staying close to real-time.

**Growing lag**: `deriv(lag) > 0` sustained over time. Consumer is slower than the producer. Will never catch up without intervention (scale up consumers, optimize processing, reduce message volume).

**Stalled consumer**: `lag > 0` AND committed offset is NOT advancing. Consumer is alive (still polling) but not committing. Usually indicates: processing stuck in a retry loop, exception being swallowed, deadlock in processing thread, or consumer paused.

### Why lag is not always bad

A consumer with lag=1,000,000 that is DECREASING (catching up after a deploy) is healthier than a consumer with lag=1,000 that is INCREASING. Alert on lag rate of change, not just absolute lag.

### Measuring Lag

**Command-line**:
```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group my-consumer-group

# Output:
# GROUP              TOPIC   PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID
# my-consumer-group  orders  0          10045892        10046000        108  consumer-1
# my-consumer-group  orders  1          9987234         9987310         76   consumer-2
```

**Programmatic (AdminClient)**:
```java
AdminClient admin = AdminClient.create(props);

// Get committed offsets for the group
Map<TopicPartition, OffsetAndMetadata> committed = admin
    .listConsumerGroupOffsets("my-consumer-group")
    .partitionsToOffsetAndMetadata()
    .get();

// Get latest offsets (HW) for those partitions
Map<TopicPartition, Long> endOffsets;
try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
    endOffsets = consumer.endOffsets(committed.keySet());
}

// Calculate lag per partition
committed.forEach((tp, oam) -> {
    long lag = endOffsets.get(tp) - oam.offset();
    System.out.printf("Partition %s: lag = %d%n", tp, lag);
});
```

**Prometheus metrics** (via kafka_exporter):
```
kafka_consumer_group_lag{topic="orders", partition="0", consumergroup="my-consumer-group"} 108
kafka_consumergroup_current_offset{topic="orders", partition="0", consumergroup="my-consumer-group"} 10045892
```

---

## 7. Offset Reset — Manual Operations

Use `kafka-consumer-groups.sh --reset-offsets` to manually reposition a consumer group. The consumer group must be INACTIVE (all consumers stopped) for this to work.

### Reset to Earliest

```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group my-consumer-group \
  --topic orders \
  --reset-offsets --to-earliest \
  --execute
```

Moves all partitions of `orders` to offset 0 (or LSO if old segments were deleted). Consumer will replay all retained history on next start.

### Reset to Latest

```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group my-consumer-group \
  --topic orders \
  --reset-offsets --to-latest \
  --execute
```

Skip all existing messages. Consumer starts fresh from the current end.

### Reset to Specific Timestamp

```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group my-consumer-group \
  --topic orders \
  --reset-offsets --to-datetime 2024-01-15T10:30:00.000 \
  --execute
```

Each partition's offset is set to the first message with a timestamp >= the specified datetime. Useful for "replay from the last known-good time" disaster recovery.

### Reset to Specific Offset

```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group my-consumer-group \
  --topic orders:3   \
  --reset-offsets --to-offset 10000000 \
  --execute
```

Set partition 3 of `orders` to exactly offset 10000000. Surgical replay of a specific range.

### Shift by N

```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group my-consumer-group \
  --topic orders \
  --reset-offsets --shift-by -1000 \
  --execute
```

Move all partitions back by 1000 messages. Useful to reprocess the last N messages after a bug fix.

### Dry run before executing

```bash
# Always dry-run first — remove --execute to preview
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group my-consumer-group \
  --topic orders \
  --reset-offsets --to-datetime 2024-01-15T10:30:00.000

# Output shows what WOULD happen, no changes made
```

### Programmatic seek (consumer.seek)

```java
consumer.subscribe(List.of("orders"), new ConsumerRebalanceListener() {
    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        // Seek all assigned partitions to a specific timestamp on assignment
        Map<TopicPartition, Long> timestampsToSearch = partitions.stream()
            .collect(Collectors.toMap(
                tp -> tp,
                tp -> Instant.parse("2024-01-15T10:30:00Z").toEpochMilli()
            ));
        
        Map<TopicPartition, OffsetAndTimestamp> offsets = 
            consumer.offsetsForTimes(timestampsToSearch);
        
        offsets.forEach((tp, offsetAndTimestamp) -> {
            if (offsetAndTimestamp != null) {
                consumer.seek(tp, offsetAndTimestamp.offset());
            } else {
                consumer.seekToEnd(List.of(tp));  // no message at that time → go to end
            }
        });
    }
    
    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {}
});
```

This is useful when you want to restart consumption from a specific point without stopping the consumer — useful in Kafka Streams applications or consumers that need custom positioning on rebalance.

---

## 8. Exactly-Once and Transactional Offsets

Kafka's transaction API enables atomic offset commits — combining message production and offset commitment in a single transaction.

### The Problem Without Transactions

In a read-process-write pattern (consume from Topic A, transform, produce to Topic B):

```
1. Consumer reads message from Topic A (offset 100)
2. Produces transformed result to Topic B
3. ← CRASH HERE ←
4. Commit offset 101 to __consumer_offsets (never reached)

Result: On restart, consumer replays offset 100.
Topic B receives the transformed message TWICE.
```

### Kafka Transactions — Atomic Offset Commit

```java
Properties producerProps = new Properties();
producerProps.put("transactional.id", "payment-processor-1");  // unique per instance
producerProps.put("enable.idempotence", "true");
producerProps.put("acks", "all");

KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps);
producer.initTransactions();

Properties consumerProps = new Properties();
consumerProps.put("isolation.level", "read_committed");
consumerProps.put("enable.auto.commit", "false");
consumerProps.put("group.id", "payment-processor-group");

KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps);
consumer.subscribe(List.of("payments-input"));

while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    
    if (records.isEmpty()) continue;
    
    producer.beginTransaction();
    try {
        for (ConsumerRecord<String, String> record : records) {
            // Process and produce output
            String result = transform(record.value());
            producer.send(new ProducerRecord<>("payments-output", record.key(), result));
        }
        
        // Atomically commit: produce to output topic + commit input offset
        // Both happen in the same transaction
        producer.sendOffsetsToTransaction(
            getOffsets(records),           // offsets to commit
            consumer.groupMetadata()       // consumer group info
        );
        
        producer.commitTransaction();
        // Now: output records visible AND input offsets committed — atomically
        
    } catch (ProducerFencedException e) {
        producer.close();  // zombie — another instance took over
        throw e;
    } catch (KafkaException e) {
        producer.abortTransaction();
        // Input offsets NOT committed → will reprocess → idempotent output needed
    }
}

private Map<TopicPartition, OffsetAndMetadata> getOffsets(ConsumerRecords<String, String> records) {
    Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
    for (TopicPartition tp : records.partitions()) {
        List<ConsumerRecord<String, String>> partitionRecords = records.records(tp);
        long lastOffset = partitionRecords.get(partitionRecords.size() - 1).offset();
        offsets.put(tp, new OffsetAndMetadata(lastOffset + 1));
    }
    return offsets;
}
```

### What sendOffsetsToTransaction does

`sendOffsetsToTransaction` writes the offset commit as part of the **ongoing transaction**. The offsets are written to `__consumer_offsets` but marked as "pending" — they are not visible to the consumer group coordinator until `commitTransaction()` is called.

If `abortTransaction()` is called, the pending offsets are discarded. The consumer group's committed offset stays at the previous value → reprocessing occurs.

### Consumer isolation levels

```properties
# read_uncommitted (default): read all messages including those in open/aborted transactions
isolation.level=read_uncommitted

# read_committed: only read messages from committed transactions
# Messages from aborted transactions are skipped entirely
isolation.level=read_committed
```

With `read_committed`, consumers buffer transactional messages until they see the Commit or Abort marker. This adds some latency proportional to transaction duration.

### Transactional ID and Zombie Fencing

The `transactional.id` must be unique per logical producer instance. If you restart with the same `transactional.id`, Kafka **fences the old instance** — any in-flight transactions from the old instance are aborted. This prevents duplicate writes from zombie producers (old instances that haven't detected they should stop).

```
Instance 1 (old): transactional.id=processor-1, epoch=5
Instance 2 (new): transactional.id=processor-1, epoch=6 (incremented by broker)

Instance 1 tries to produce: broker rejects with ProducerFencedException (epoch 5 < 6)
Instance 2 takes over cleanly
```

---

## 9. Storing Offsets Outside Kafka

The safest pattern for exactly-once processing against external systems (databases, APIs) is to store Kafka offsets inside the same external transaction as your business data.

### Pattern: Offsets in PostgreSQL

```sql
-- Create an offset tracking table
CREATE TABLE kafka_offsets (
    consumer_group VARCHAR(255) NOT NULL,
    topic          VARCHAR(255) NOT NULL,
    partition      INT NOT NULL,
    committed_offset BIGINT NOT NULL,
    updated_at     TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (consumer_group, topic, partition)
);
```

```java
@Service
public class TransactionalKafkaConsumer {
    
    @Autowired DataSource dataSource;
    KafkaConsumer<String, Order> consumer;
    
    @PostConstruct
    public void start() {
        consumer = new KafkaConsumer<>(consumerProps());
        consumer.subscribe(List.of("orders"), new ConsumerRebalanceListener() {
            
            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                // On rebalance: seek to DB-tracked offsets instead of __consumer_offsets
                try (Connection conn = dataSource.getConnection()) {
                    for (TopicPartition tp : partitions) {
                        Long offset = loadOffsetFromDB(conn, "order-service", tp);
                        if (offset != null) {
                            consumer.seek(tp, offset);
                        } else {
                            consumer.seekToBeginning(List.of(tp));
                        }
                    }
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to load offsets from DB", e);
                }
            }
            
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                // Flush any in-progress work before partitions are reassigned
            }
        });
    }
    
    public void processLoop() {
        while (true) {
            ConsumerRecords<String, Order> records = consumer.poll(Duration.ofMillis(100));
            
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                
                for (ConsumerRecord<String, Order> record : records) {
                    // 1. Write business data
                    insertOrder(conn, record.value());
                    
                    // 2. Write offset IN THE SAME DB TRANSACTION
                    updateOffset(conn, "order-service", record.topic(), 
                                 record.partition(), record.offset() + 1);
                }
                
                conn.commit();  // Business data + offsets commit atomically
                // DO NOT commit to __consumer_offsets — DB is the source of truth
                
            } catch (SQLException e) {
                // DB transaction rolled back → offset not updated → reprocess
                log.error("DB transaction failed, will reprocess", e);
            }
        }
    }
    
    private Long loadOffsetFromDB(Connection conn, String group, TopicPartition tp) throws SQLException {
        String sql = "SELECT committed_offset FROM kafka_offsets WHERE consumer_group=? AND topic=? AND partition=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, group); ps.setString(2, tp.topic()); ps.setInt(3, tp.partition());
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong("committed_offset") : null;
        }
    }
    
    private void updateOffset(Connection conn, String group, String topic, int partition, long offset) throws SQLException {
        String sql = """
            INSERT INTO kafka_offsets (consumer_group, topic, partition, committed_offset)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (consumer_group, topic, partition)
            DO UPDATE SET committed_offset = EXCLUDED.committed_offset, updated_at = NOW()
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, group); ps.setString(2, topic); ps.setInt(3, partition); ps.setLong(4, offset);
            ps.executeUpdate();
        }
    }
}
```

### Why this achieves exactly-once

- If DB transaction **succeeds**: business data written AND offset updated → no reprocessing.
- If DB transaction **fails**: both business data AND offset rolled back → safe reprocessing.
- No intermediate state where data is written but offset not updated (or vice versa).
- On consumer restart/rebalance: reads offset from DB and seeks to it → no duplicate processing.

### Idempotent writes as an alternative

If you can't use DB transactions (e.g., calling an external API), make your business logic **idempotent** — using the Kafka offset as a natural idempotency key:

```java
for (ConsumerRecord<String, Order> record : records) {
    String idempotencyKey = record.topic() + ":" + record.partition() + ":" + record.offset();
    
    // Only process if we haven't seen this exact offset before
    if (!processedOffsets.contains(idempotencyKey)) {
        externalApiCall(record.value());
        processedOffsets.add(idempotencyKey);  // persist this in Redis or DB
    }
}
consumer.commitSync();
```

---

## 10. Common Bugs and Pitfalls

### Bug 1: Committing before processing completes

```java
// WRONG — commits the offset before processRecord() runs
while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    consumer.commitSync();  // committed before any processing!
    for (ConsumerRecord<String, String> record : records) {
        processRecord(record);  // if this throws, message is lost
    }
}

// CORRECT
while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    for (ConsumerRecord<String, String> record : records) {
        processRecord(record);
    }
    consumer.commitSync();  // commit only after all records in batch are processed
}
```

### Bug 2: Off-by-one in manual offset commit

```java
// WRONG — commits the last processed offset, not the next to process
consumer.commitSync(Map.of(
    new TopicPartition(record.topic(), record.partition()),
    new OffsetAndMetadata(record.offset())  // commits offset 5
));
// On restart: re-reads offset 5 (duplicate processing)

// CORRECT — commit offset+1 (the next message to read)
consumer.commitSync(Map.of(
    new TopicPartition(record.topic(), record.partition()),
    new OffsetAndMetadata(record.offset() + 1)  // commits 6 → resumes from 6
));
```

### Bug 3: Not calling poll() frequently enough → rebalance

```java
// WRONG — slow processing blocks poll() past max.poll.interval.ms
while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    for (ConsumerRecord<String, String> record : records) {
        slowExternalApiCall(record);  // takes 10 seconds per record, 500 records per batch = 5000 seconds
        // max.poll.interval.ms = 300,000ms → broker thinks consumer is dead → rebalance
    }
}

// CORRECT — reduce max.poll.records or pause partitions
props.put("max.poll.records", "5");  // process fewer records per poll
// Or use pause/resume pattern (see below)
```

### Bug 4: Using commitAsync() retry in callback

```java
// WRONG — retrying commitAsync can undo a later successful commit
consumer.commitAsync((offsets, exception) -> {
    if (exception != null) {
        consumer.commitAsync(offsets, null);  // DANGEROUS retry
    }
});
// If commit for offset 5 fails and 10 already committed, retrying 5 rolls back to 5
```

### Bug 5: Sharing consumer across threads

```java
// WRONG — KafkaConsumer is NOT thread-safe
final KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
executor.submit(() -> consumer.poll(Duration.ofMillis(100)));  // thread 1
executor.submit(() -> consumer.commitSync());                   // thread 2 → ConcurrentModificationException
```

One `KafkaConsumer` instance must only be used from ONE thread. Use a separate consumer per thread, or use the pause/resume pattern with a single consumer and a separate processing thread.

### Bug 6: Seeking during poll loop without using ConsumerRebalanceListener

```java
// WRONG — seek is overridden by the next rebalance
consumer.subscribe(List.of("my-topic"));
consumer.seek(new TopicPartition("my-topic", 0), 1000);  // set position
// Rebalance happens → seek is lost, position resets to committed offset

// CORRECT — seek in onPartitionsAssigned, which fires after every rebalance
consumer.subscribe(List.of("my-topic"), new ConsumerRebalanceListener() {
    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        partitions.forEach(tp -> consumer.seek(tp, 1000));  // re-applied after every rebalance
    }
    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {}
});
```

### Bug 7: auto.offset.reset=earliest on high-volume topic in production

A new consumer group with `earliest` deployed against a 1TB topic that has 6 months of data will process all 6 months. This can:
- Overwhelm downstream systems with historical events.
- Take days to catch up to real-time.
- Trigger alerts for "consumer is always lagging."

Always pre-set offsets for new consumer groups using `kafka-consumer-groups.sh --reset-offsets` before first deployment.

---

## 11. Monitoring and Alerting

### Key JMX Metrics

```
kafka.consumer:type=consumer-fetch-manager-metrics,client-id=*
  records-lag              → current lag per partition
  records-lag-max          → max lag across all partitions
  records-consumed-rate    → messages per second
  fetch-latency-avg        → average fetch latency

kafka.consumer:type=consumer-coordinator-metrics,client-id=*
  commit-latency-avg       → average commit round-trip time
  commit-rate              → commits per second
  rebalance-rate-per-hour  → how often rebalances are occurring (should be near 0)
  last-rebalance-seconds-ago → time since last rebalance
```

### Prometheus Alert Rules

```yaml
groups:
  - name: kafka_consumer_lag
    rules:

    # Alert if lag is growing (consumer slower than producer)
    - alert: KafkaConsumerLagGrowing
      expr: deriv(kafka_consumer_group_lag_sum[10m]) > 100
      for: 10m
      labels:
        severity: warning
      annotations:
        summary: "Consumer group {{ $labels.consumergroup }} lag growing at {{ $value }}/sec"
        description: "Consumer is not keeping up with producer. Investigate processing bottleneck."

    # Alert on very high absolute lag
    - alert: KafkaConsumerLagCritical
      expr: kafka_consumer_group_lag_sum > 1000000
      for: 5m
      labels:
        severity: critical
      annotations:
        summary: "Consumer group {{ $labels.consumergroup }} lag exceeds 1M messages"

    # Alert on stalled consumer (committed offset not advancing)
    - alert: KafkaConsumerStalled
      expr: rate(kafka_consumergroup_current_offset[5m]) == 0 and kafka_consumer_group_lag_sum > 0
      for: 10m
      labels:
        severity: critical
      annotations:
        summary: "Consumer group {{ $labels.consumergroup }} is stalled — no offset commits in 10 minutes"
        description: "Consumer may be stuck in error loop, deadlocked, or paused."

    # Alert on frequent rebalances
    - alert: KafkaConsumerFrequentRebalances
      expr: rate(kafka_consumer_rebalance_total[30m]) > 0.1
      for: 15m
      labels:
        severity: warning
      annotations:
        summary: "Consumer group {{ $labels.consumergroup }} is rebalancing frequently"
        description: "Check for consumers crashing, slow processing exceeding max.poll.interval.ms, or unstable network."
```

### Burrow — Asynchronous Lag Monitoring

Burrow (from LinkedIn) is the gold standard for consumer lag monitoring. Unlike polling `kafka-consumer-groups.sh`, Burrow evaluates consumer health using a **sliding window** of offset commit history:

- **OK**: Offsets are advancing and lag is stable or decreasing.
- **WARNING**: Offsets are advancing but lag is increasing (consumer is slow).
- **ERROR**: Offsets are NOT advancing (consumer is stalled or dead).
- **STOP**: Consumer has been stopped (no commits at all).

Burrow distinguishes "slow consumer" from "stalled consumer" — two very different problems with different remediation.

---

## 12. Quick Reference

### Key Properties

| Property | Default | Description |
|---|---|---|
| `enable.auto.commit` | `true` | Auto-commit offsets. Set `false` in production. |
| `auto.commit.interval.ms` | `5000` | How often auto-commit fires (ms). |
| `auto.offset.reset` | `latest` | What to do with no committed offset. |
| `max.poll.records` | `500` | Max records per poll(). Reduce for slow processing. |
| `max.poll.interval.ms` | `300000` | Max time between polls before broker triggers rebalance. |
| `session.timeout.ms` | `45000` | Time before broker considers consumer dead (heartbeat-based). |
| `isolation.level` | `read_uncommitted` | Set `read_committed` for transactional consumers. |
| `offsets.topic.num.partitions` | `50` | Partitions in __consumer_offsets (broker config). |
| `offsets.retention.minutes` | `10080` | How long to keep committed offsets (7 days). |
| `offsets.topic.replication.factor` | `3` | Replication of __consumer_offsets. |

### Offset Position Summary

| Term | Description | Where stored |
|---|---|---|
| LSO (Log Start Offset) | Earliest available message | Broker log |
| LEO (Log End Offset) | Next offset to write | Per-replica, in memory |
| HW (High Watermark) | Max readable by consumers | Leader, in memory |
| Committed offset | Consumer group's checkpoint | `__consumer_offsets` topic |
| Current position | Where consumer is now | Consumer in-memory only |
| LSO (Last Stable Offset) | Transactional read boundary | Leader, in memory |

### Delivery Guarantees

| Pattern | Guarantee | Notes |
|---|---|---|
| `enable.auto.commit=true` | At-most-once | Can lose messages |
| Manual `commitSync()` after batch | At-least-once | Redelivery on crash |
| Manual `commitAsync()` after batch | At-least-once | Redelivery on crash |
| Kafka transactions + `sendOffsetsToTransaction` | Exactly-once (within Kafka) | Read-process-write atomically |
| DB-stored offsets + idempotent writes | Exactly-once (with external DB) | Most reliable for DB consumers |

### Decision Tree: Which commit strategy?

```
Is message loss acceptable?
├── Yes → enable.auto.commit=true (at-most-once)
└── No
    ├── Writing to Kafka only (no external DB)?
    │   ├── Yes → Kafka transactions (EOS)
    │   └── No
    │       ├── Can you make DB writes idempotent?
    │       │   ├── Yes → commitSync() after batch + idempotent DB writes
    │       │   └── No → DB-stored offsets + consumer.seek()
    └── Throughput critical?
        ├── Yes → commitAsync() per batch + commitSync() on shutdown
        └── No → commitSync() per record (highest safety)
```

---

*The golden rule: always commit AFTER successful processing, never before. The offset you commit is the next message to process on restart — make sure you actually want to restart from there.*
