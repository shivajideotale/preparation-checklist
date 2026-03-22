# Kafka Delivery Semantics — Deep Dive

> At-most-once, at-least-once, and exactly-once: how each guarantee works, what breaks it, how to achieve it, and what it costs. Written for senior Java backend engineers who need to reason about correctness under failure.

---

## Table of Contents

1. [The Two Independent Axes](#1-the-two-independent-axes)
2. [At-Most-Once Delivery](#2-at-most-once-delivery)
3. [At-Least-Once Delivery](#3-at-least-once-delivery)
4. [Exactly-Once Delivery](#4-exactly-once-delivery)
5. [Idempotent Producer Internals](#5-idempotent-producer-internals)
6. [Kafka Transactions for End-to-End EOS](#6-kafka-transactions-for-end-to-end-eos)
7. [Consumer-Side Semantics](#7-consumer-side-semantics)
8. [Exactly-Once with External Systems](#8-exactly-once-with-external-systems)
9. [Delivery Semantics in Kafka Streams](#9-delivery-semantics-in-kafka-streams)
10. [Failure Scenarios and Recovery](#10-failure-scenarios-and-recovery)
11. [Complete Java Implementation Examples](#11-complete-java-implementation-examples)
12. [Performance Trade-offs](#12-performance-trade-offs)
13. [Common Misconceptions](#13-common-misconceptions)
14. [Decision Guide](#14-decision-guide)
15. [Quick Reference](#15-quick-reference)

---

## 1. The Two Independent Axes

Delivery semantics in Kafka are determined by **two independent axes** that must both be correct for end-to-end guarantees.

### Axis 1: Producer → Broker

Controls whether a message reaches the broker's log and how many times:

```
acks=0        → Fire and forget. Message may be lost. No retries.
acks=1        → Leader must confirm. Message lost if leader fails before replication.
acks=all      → All ISR replicas must confirm. No message loss under normal conditions.
+ idempotence → Deduplicates retries. One copy in the log even after retry.
+ transactions → Atomic across partitions. Rollback possible.
```

### Axis 2: Consumer → Application

Controls how many times the application code sees each message:

```
auto-commit before processing → At-most-once. Crash loses messages.
auto-commit after processing  → At-least-once with a window for loss.
manual commit after processing → At-least-once. Crash causes redelivery.
offset in same DB transaction  → Exactly-once. Atomic with business write.
sendOffsetsToTransaction()    → Exactly-once. Atomic with Kafka output.
```

### The fundamental rule

```
End-to-end guarantee = min(producer guarantee, consumer guarantee)
```

A perfectly idempotent, transactional producer cannot compensate for a consumer that commits offsets before processing finishes. Both layers must be correct independently.

### Visualising the guarantee space

```
                     Consumer commits BEFORE processing
                     ──────────────────────────────────
Producer acks=0  →   At-most-once    (loss on both sides)
Producer acks=1  →   At-most-once    (producer loss + consumer loss)
Producer acks=all →  At-most-once    (consumer side still loses)

                     Consumer commits AFTER processing
                     ─────────────────────────────────
Producer acks=0  →   At-most-once    (producer can lose)
Producer acks=1  →   At-least-once   (retry without dedup = duplicates)
Producer acks=all →  At-least-once   (retry without dedup = duplicates)
+ enable.idempotence → At-least-once (producer dedup, consumer may still dupe)

                     Consumer uses Kafka transactions
                     ────────────────────────────────
Producer + transactional.id → Exactly-once (within Kafka)
```

---

## 2. At-Most-Once Delivery

A message is delivered **zero or one times**. It is never duplicated, but it may be silently lost.

### When at-most-once happens

At-most-once can happen on either the producer side or the consumer side independently.

#### Producer-side at-most-once

```java
Properties props = new Properties();
props.put(ProducerConfig.ACKS_CONFIG, "0");       // no acknowledgment
props.put(ProducerConfig.RETRIES_CONFIG, 0);       // no retry
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);

KafkaProducer<String, String> producer = new KafkaProducer<>(props);
producer.send(new ProducerRecord<>("my-topic", "key", "value"));
// Future completes immediately — no guarantee message reached broker
```

**What happens internally with acks=0:**

```
Producer accumulator → Sender thread dequeues batch
Sender fires ProduceRequest to broker leader
Sender immediately marks the Future as complete (success)
NO wait for broker response

If network drops the request:
→ Broker never received it
→ Producer never knows
→ Message is gone forever
→ No error to the application
```

**What happens with acks=1 (leader-only):**

```
Producer sends ProduceRequest to leader
Leader writes to its local log only
Leader sends ack to producer
Producer marks Future as success

If leader crashes before followers replicate:
→ Controller promotes a follower (which doesn't have the message)
→ Message is permanently lost from the log
→ Producer already received success ack — it never retries
```

#### Consumer-side at-most-once

```java
// DANGEROUS — default behavior
Properties props = new Properties();
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 100); // frequent commits

while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    // Auto-commit fires here — BEFORE processing starts
    for (ConsumerRecord<String, String> record : records) {
        process(record); // if this crashes, records are already committed
    }
}
```

**The failure timeline:**

```
t=0   poll() returns records [A, B, C, D, E]
t=0   auto-commit fires: committed offset advances past E
t=1   Application processes A → success
t=2   Application processes B → success
t=3   Application processes C → JVM OOM crash
t=4   Consumer restarts
t=4   Reads from committed offset → starts at F
t=4   Records D and E are PERMANENTLY SKIPPED
```

The commit fires in the background thread independently of processing. Even `auto.commit.interval.ms=5000` does not help — the commit fires based on time, not processing completion.

### Intentional at-most-once patterns

At-most-once is acceptable and even preferable in specific scenarios:

```java
// Pattern: metrics pipeline where loss is acceptable
// Missing 0.001% of metrics data points is fine
props.put(ProducerConfig.ACKS_CONFIG, "0");
props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
props.put(ProducerConfig.BATCH_SIZE_CONFIG, 524288);
// Result: maximum throughput, minimal latency, loss on network hiccup
```

**Legitimate use cases:**
- Real-time dashboard metrics where the next data point arrives in milliseconds
- High-frequency IoT sensor readings (temperature, GPS positions)
- Application access logs where occasional gaps are tolerable
- Performance counters and health check metrics
- A/B test event recording where statistical accuracy matters more than individual event capture

### At-most-once delivery guarantee contract

```
Message in log? → Only if the initial send succeeds AND broker doesn't fail before replication
                  (acks=all removes the second condition)

Message processed? → Only if app doesn't crash between poll() and the processing of that record
                     (when using auto-commit before processing)

Duplicates possible? → No. Never.
Loss possible? → Yes. On any network failure, broker failure, or app crash.
```

---

## 3. At-Least-Once Delivery

A message is delivered **one or more times**. It is never permanently lost, but it may be processed multiple times on failure.

### Producer-side at-least-once

```java
Properties props = new Properties();
props.put(ProducerConfig.ACKS_CONFIG, "all");         // wait for ISR
props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE); // retry forever
props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000); // 2 min total budget
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false); // without dedup
```

**The retry-duplicate failure scenario:**

```
t=0   Producer sends batch (seq=10) to partition leader
t=0   Leader writes to its log at offset 5000
t=0   All ISR followers replicate: offset 5000 now on 3 brokers
t=1   Leader sends ack response back to producer
t=1   Response packet is lost in network (TCP retransmit timeout, network partition)
t=3   Producer's request.timeout.ms fires — no ack received
t=3   Producer retries: sends the SAME batch again (seq=10)
t=3   Broker has no memory of seq=10 (no idempotence) — writes AGAIN at offset 5001
t=3   Now offsets 5000 and 5001 contain identical messages

Consumer sees: [msg at 5000] [msg at 5001] — duplicated
```

**Why acks=all doesn't prevent this:**
`acks=all` guarantees the message is durable — it prevents loss. But when the ack is lost and the producer retries, the broker has no way to distinguish a retry from a new message without idempotence tracking. The message is written twice.

### Consumer-side at-least-once

```java
Properties consumerProps = new Properties();
consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); // manual control
consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "my-service");

while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    for (ConsumerRecord<String, String> record : records) {
        process(record); // process first
    }
    consumer.commitSync(); // then commit — at-least-once
}
```

**The crash-redelivery scenario:**

```
t=0  poll() returns records [offset 100, 101, 102, 103, 104]
t=1  App processes offset 100 → DB write succeeds
t=2  App processes offset 101 → DB write succeeds
t=3  App processes offset 102 → JVM killed (OOM, SIGKILL, pod eviction)
t=4  Consumer restarts
t=4  Last committed offset = 99 (committed at end of previous batch)
t=4  Consumer re-reads from offset 100
t=4  Offsets 100 and 101 are REPROCESSED

Consumer processes offset 100 twice → DB write executed twice
Consumer processes offset 101 twice → DB write executed twice
```

**At-least-once guarantee:**
The message is guaranteed to be processed at least once. It may be processed more than once if there is a failure between processing and committing.

### Making at-least-once safe: idempotent consumers

The standard solution is to make the consumer's processing logic idempotent — safe to execute multiple times with the same result.

**Strategy 1: Natural business idempotency**

```java
// ORDER table has a unique constraint on order_id
// Re-processing the same order just updates to the same state
jdbcTemplate.update("""
    INSERT INTO orders (order_id, status, amount)
    VALUES (?, ?, ?)
    ON CONFLICT (order_id) DO UPDATE
        SET status = EXCLUDED.status,
            updated_at = NOW()
    WHERE orders.status != 'SHIPPED'  -- idempotent guard
""", record.key(), order.getStatus(), order.getAmount());
```

**Strategy 2: Kafka offset as idempotency key**

```java
String idempotencyKey = record.topic() + ":" +
                        record.partition() + ":" +
                        record.offset();

jdbcTemplate.update("""
    INSERT INTO processed_events (idempotency_key, payload, processed_at)
    VALUES (?, ?, NOW())
    ON CONFLICT (idempotency_key) DO NOTHING
""", idempotencyKey, record.value());
// Second execution: conflict on idempotency_key → DO NOTHING → safe
```

**Strategy 3: Redis-based deduplication**

```java
String dedupeKey = "processed:" + record.topic() + ":" +
                   record.partition() + ":" + record.offset();

// Returns true if key was newly set (not a duplicate)
Boolean isNew = redisTemplate.opsForValue()
    .setIfAbsent(dedupeKey, "1", Duration.ofDays(7));

if (Boolean.TRUE.equals(isNew)) {
    process(record); // process only if first time
}
consumer.commitSync();
```

---

## 4. Exactly-Once Delivery

A message is delivered **exactly one time** — no loss, no duplicates. This is the hardest guarantee to achieve and requires coordination at multiple layers.

### The two components of exactly-once

Exactly-once in Kafka requires solving two separate problems:

**Problem 1: Producer retry duplicates**
When a producer retries after a lost ack, the broker must detect and discard the duplicate.
*Solution: Idempotent producer (sequence numbers + PID)*

**Problem 2: Consumer reprocessing**
When a consumer crashes after processing but before committing, it reprocesses on restart.
*Solution: Atomic offset commit + produce via Kafka transactions*

### The guarantee scope

Kafka's exactly-once guarantee applies to **data flowing through Kafka**:

```
✓ Exactly-once: Input topic A → processing → Output topic B
✓ Exactly-once: Producer → Broker (idempotent producer)
✗ NOT exactly-once: Kafka → external database (no distributed transaction)
✗ NOT exactly-once: Kafka → REST API call
✗ NOT exactly-once: Kafka → filesystem write
```

For external systems, you need the Outbox pattern or idempotent writes (see Section 8).

### Exactly-once delivery guarantee contract

```
Message in log? → Yes, exactly once. Broker deduplicates retries via sequence numbers.
Message visible? → Only after transaction commits. Aborted transactions invisible to read_committed consumers.
Message processed? → Exactly once. Offset committed atomically with output. Crash = replay from last committed position, which means output was also rolled back.
Duplicates possible? → No, within Kafka.
Loss possible? → No, with acks=all.
```

---

## 5. Idempotent Producer Internals

The idempotent producer is the foundation of exactly-once semantics. It solves the retry-duplicate problem at the broker level.

### How sequence numbers work

Every producer is assigned a **Producer ID (PID)** by the broker. Each producer maintains an independent **sequence number counter per partition** (starts at 0, increments by the number of records in each batch):

```
Producer PID=1001, writing to partition 0:

Batch 1 (5 records): seq=0 → broker writes at offsets 0-4,  stores last_seq=4
Batch 2 (3 records): seq=5 → broker writes at offsets 5-7,  stores last_seq=7
Batch 3 (1 record):  seq=8 → broker writes at offset 8,     stores last_seq=8

Network failure — ack for batch 2 is lost

Producer retries batch 2: seq=5
Broker checks: PID=1001, partition=0, stored last_seq=7
Received seq=5 ≤ stored last_seq=7 → DUPLICATE
Broker discards batch 2 silently, returns success to producer

Result: Only one copy of batch 2 in the log
```

### The broker's sequence validation rules

The broker maintains `(PID, partition) → last_sequence_number` in memory (persisted to the replica log for recovery):

```
Received seq == last_seq + 1  →  ACCEPT  (expected next batch)
Received seq <= last_seq       →  DUPLICATE  (silently discard, return success)
Received seq > last_seq + 1   →  GAP  (throw OutOfOrderSequenceException — serious error, data lost)
```

The `OutOfOrderSequenceException` indicates a sequence gap — batches were sent out of order or lost without retry. This is a fatal error that requires producer restart.

### PID lifetime and limitations

The PID is assigned per producer **session** — it resets if:
- The producer calls `initTransactions()` again (new PID, epoch incremented)
- The producer is recreated (`new KafkaProducer<>()`)

Without `transactional.id`, a producer restart gets a new PID. The broker cannot link the old and new PIDs — the new producer starts with seq=0 and the old producer's sequence tracking is lost. A retry from the **old** producer instance after the new one started cannot be deduplicated.

```
Old producer (PID=1001): sends seq=5, ack lost, crashes
New producer (PID=1002): created fresh, sends its own seq=0 onwards

If old producer zombie wakes up and retries seq=5:
→ Broker only knows PID=1001 → still has last_seq for 1001 in memory
→ Duplicate detected if within the broker's dedup window
→ But if broker restarted, the in-memory state may be lost
```

**This is why transactional.id and zombie fencing exist** — to make PID assignment deterministic across producer restarts.

### Configuration

```java
Properties props = new Properties();
// Enable idempotent producer (default true since Kafka 3.0)
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

// These are auto-configured when idempotence is enabled:
// acks = all          (required for dedup state durability)
// retries = MAX_INT   (required — without retries, dedup is useless)
// max.in.flight.requests.per.connection = 5  (safe upper limit with dedup)
```

---

## 6. Kafka Transactions for End-to-End EOS

The idempotent producer solves producer-side duplicates. To solve the consumer reprocessing problem, Kafka uses **transactions** to atomically commit both the output records and the input offset.

### The read-process-write atomicity problem

```
Without transactions:

t=0  Consumer reads record X from input topic (offset 100)
t=1  Producer sends transformed record Y to output topic → SUCCESS
t=2  CRASH
t=3  Consumer restarts from offset 100 (not committed)
t=3  Consumer reads record X again
t=4  Producer sends transformed record Y to output topic → DUPLICATE in output topic
```

The output topic has two copies of Y. No amount of consumer-side logic can undo a record that was already written to an output Kafka topic.

### How transactions solve it

```java
producer.beginTransaction();

// Step 1: write to output topic (within transaction — not yet visible)
producer.send(new ProducerRecord<>("output-topic", key, value));

// Step 2: atomically commit the input offset as part of this transaction
producer.sendOffsetsToTransaction(
    Map.of(new TopicPartition("input-topic", 0),
           new OffsetAndMetadata(record.offset() + 1)),
    consumer.groupMetadata()
);

// Step 3: both commit together or neither does
producer.commitTransaction();
```

**If crash occurs before commitTransaction():**
- Transaction Coordinator sees incomplete transaction (state=Ongoing)
- On producer restart with same `transactional.id`, coordinator aborts the incomplete transaction
- ABORT markers written to output-topic → `read_committed` consumers skip the partial output
- Input offset NOT committed → consumer replays from offset 100
- On replay, a new transaction is created, processes correctly, commits successfully

**If crash occurs after commitTransaction() starts (after PREPARE_COMMIT):**
- Transaction Coordinator has durable intent to commit
- On coordinator restart, reads PREPARE_COMMIT, finishes writing COMMIT markers
- Both output record and offset commit complete
- No replay needed — exactly-once achieved

### Transaction state machine

```
Empty
  │
  │ beginTransaction()
  ▼
Ongoing ──────────────────────────────────────────────────────┐
  │ send() + sendOffsetsToTransaction()                        │
  │                                                            │ timeout or explicit abort
  │ commitTransaction() called                                 │
  ▼                                                            ▼
PrepareCommit                                           PrepareAbort
  │                                                            │
  │ Coordinator writes COMMIT markers to all partitions        │ Coordinator writes ABORT markers
  ▼                                                            ▼
CompleteCommit                                          CompleteAbort
  │                                                            │
  └──────────────────────── Empty ◄──────────────────────────┘
```

---

## 7. Consumer-Side Semantics

The consumer's delivery guarantee depends entirely on **when** the offset is committed relative to **when** processing completes.

### Commit timing and its effect

```
Timeline A: auto-commit (at-most-once risk)
─────────────────────────────────────────
poll() → [auto-commit fires] → process(A) → process(B) → [crash] → restart from after B

Timeline B: commitSync after batch (at-least-once)
─────────────────────────────────────────────────
poll() → process(A) → process(B) → [crash] → restart from before A → A and B reprocessed
poll() → process(A) → process(B) → commitSync() → [success]

Timeline C: commitSync per record (at-least-once, smaller redelivery window)
────────────────────────────────────────────────────────────────────────────
poll() → process(A) → commitSync() → process(B) → [crash] → restart from B → only B reprocessed

Timeline D: offset in same DB transaction (exactly-once with DB)
────────────────────────────────────────────────────────────────
poll() → BEGIN txn → process(A) → write to DB → update kafka_offsets → COMMIT txn → seek
         If crash after COMMIT: DB has A, offset advanced — no reprocessing needed
         If crash before COMMIT: DB has nothing, offset not updated — A reprocessed (idempotent insert handles it)
```

### The commitSync vs commitAsync decision

```java
// commitSync — blocks until broker confirms commit
// Use for: safety-critical, shutdown hook, before rebalance
try {
    consumer.commitSync();
} catch (CommitFailedException e) {
    // Rebalance happened — this partition may have been revoked
    // Don't process any more records until next poll
}

// commitAsync — non-blocking, callback on completion
// Use for: high-throughput main loop (pairs with commitSync on shutdown)
consumer.commitAsync((offsets, exception) -> {
    if (exception != null) {
        // Do NOT retry — a later commit may have already succeeded
        // Retrying an older offset can roll back a newer commit
        log.warn("Async commit failed: {}", exception.getMessage());
    }
});
```

**The safe pattern — async in loop, sync on shutdown:**

```java
try {
    while (running) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
        for (ConsumerRecord<String, String> record : records) {
            process(record);
        }
        consumer.commitAsync(); // fast, non-blocking in steady state
    }
} catch (Exception e) {
    log.error("Fatal error in consumer loop", e);
} finally {
    try {
        consumer.commitSync(); // blocking, retrying commit on clean shutdown
    } finally {
        consumer.close();
    }
}
```

### Offset commit strategies and their guarantees

| Strategy | Delivery | Redelivery window | Throughput |
|---|---|---|---|
| `enable.auto.commit=true` | At-most-once | Zero (already committed) | Highest |
| `commitSync()` per batch | At-least-once | Entire batch | Medium |
| `commitAsync()` per batch + `commitSync()` on shutdown | At-least-once | Entire batch | High |
| `commitSync()` per record | At-least-once | Single record | Low |
| Offset in DB transaction | Exactly-once* | None (atomic) | Medium |
| `sendOffsetsToTransaction()` | Exactly-once | None (atomic) | Medium |

*Requires storing Kafka offsets in the same database transaction as business writes.

### Handling rebalances during commit

When a rebalance occurs, partitions may be revoked before the consumer can commit. Use `ConsumerRebalanceListener` to handle this:

```java
consumer.subscribe(List.of("my-topic"), new ConsumerRebalanceListener() {
    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        // Partitions being taken away — commit what we've processed so far
        // to minimize redelivery on the new consumer
        try {
            consumer.commitSync(buildCurrentOffsets(partitions));
        } catch (CommitFailedException e) {
            // Another consumer may have already taken these partitions
            log.warn("Commit on revoke failed", e);
        }
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        // New partitions — you may want to seek to a specific position
        // e.g., read offsets from DB for exactly-once with external system
    }
});
```

---

## 8. Exactly-Once with External Systems

Kafka transactions provide exactly-once within Kafka. For writes to external systems (databases, REST APIs, caches), you need different strategies.

### Why Kafka transactions don't help with databases

```
producer.beginTransaction();
producer.send(new ProducerRecord<>("output", key, value));  // Kafka write

jdbcTemplate.update("INSERT INTO orders VALUES (?)", order); // DB write — NOT in transaction

producer.commitTransaction(); // Kafka commits, but what if DB insert already failed?

// Scenarios:
// A) Kafka commit succeeds, DB insert failed → Kafka has record, DB doesn't
// B) DB insert succeeds, Kafka commit fails → DB has record, Kafka doesn't
// Both = inconsistency
```

There is no distributed transaction that spans Kafka and a JDBC database. You must choose a pattern.

### Pattern 1: Outbox Pattern (recommended for DB → Kafka)

Write business data AND the Kafka event to the **same database** in one ACID transaction. A separate process publishes from DB to Kafka.

```sql
-- Single DB transaction — atomic by database ACID
BEGIN;

INSERT INTO orders (id, customer_id, amount, status)
VALUES (?, ?, ?, 'PENDING');

INSERT INTO outbox (id, topic, partition_key, payload, created_at, published)
VALUES (gen_random_uuid(), 'orders', ?, ?, NOW(), false);

COMMIT;
-- Either both succeed or both fail — no inconsistency possible
```

```java
// Outbox publisher (runs separately, e.g., Debezium CDC or polling)
@Scheduled(fixedDelay = 100)
public void publishOutbox() {
    List<OutboxEvent> pending = outboxRepo.findByPublishedFalse();
    for (OutboxEvent event : pending) {
        try {
            producer.send(new ProducerRecord<>(
                event.getTopic(), event.getPartitionKey(), event.getPayload()
            )).get(); // block for confirmation
            outboxRepo.markPublished(event.getId()); // mark in same DB
        } catch (Exception e) {
            log.warn("Failed to publish outbox event {}", event.getId(), e);
            // Will retry on next schedule run — producer is idempotent
        }
    }
}
```

**Guarantees**: The business event and Kafka message are always consistent. If publishing to Kafka fails, the outbox row is retried — with an idempotent producer, the Kafka message is deduplicated if the broker already received it.

### Pattern 2: Store Offsets in the Database

Instead of committing offsets to `__consumer_offsets`, store them inside the same DB transaction as your business write.

```java
@PostConstruct
public void init() {
    consumer.subscribe(List.of("orders"), new ConsumerRebalanceListener() {
        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            // Seek to DB-tracked offsets on assignment
            for (TopicPartition tp : partitions) {
                Long offset = offsetRepository.findOffset(groupId, tp);
                if (offset != null) {
                    consumer.seek(tp, offset);
                } else {
                    consumer.seekToBeginning(List.of(tp));
                }
            }
        }
        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {}
    });
}

public void processLoop() {
    while (running) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            for (ConsumerRecord<String, String> record : records) {
                // Business write
                insertOrderToDb(conn, parseOrder(record.value()));

                // Kafka offset tracked in SAME DB transaction
                upsertKafkaOffset(conn,
                    groupId, record.topic(), record.partition(),
                    record.offset() + 1
                );
            }

            conn.commit(); // atomic: business data + offset advance together

        } catch (SQLException e) {
            log.error("DB transaction failed — will reprocess on next poll", e);
            // No commit → offsets unchanged → safe reprocessing
        }
        // DO NOT call consumer.commitSync() — DB is the offset source of truth
    }
}

private void upsertKafkaOffset(Connection conn, String group,
        String topic, int partition, long offset) throws SQLException {
    conn.prepareStatement("""
        INSERT INTO kafka_offsets (consumer_group, topic, partition, committed_offset)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (consumer_group, topic, partition)
        DO UPDATE SET committed_offset = EXCLUDED.committed_offset
    """).executeUpdate(); // simplified — bind params in practice
}
```

**Why this achieves exactly-once**: If the DB transaction commits, both the business data and the offset are advanced — the message will not be reprocessed. If the DB transaction rolls back, neither is written — safe reprocessing occurs.

### Pattern 3: Idempotent Consumer with Kafka Offset as Key

The simplest pattern when you can make individual writes idempotent:

```java
for (ConsumerRecord<String, String> record : records) {
    String idempotencyKey = String.format("%s:%d:%d",
        record.topic(), record.partition(), record.offset());

    // Upsert: if already processed (same key), DO NOTHING
    int rows = jdbcTemplate.update("""
        INSERT INTO order_events (idempotency_key, order_id, event_type, payload)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (idempotency_key) DO NOTHING
    """, idempotencyKey, record.key(), eventType, record.value());

    if (rows == 0) {
        log.debug("Skipping duplicate: {}", idempotencyKey);
    }
}
consumer.commitSync();
```

**Limitation**: Requires the DB to support idempotent writes (most SQL databases do with `ON CONFLICT`). Does not work for non-idempotent side effects (emails, SMS, payment charges).

---

## 9. Delivery Semantics in Kafka Streams

Kafka Streams manages delivery semantics via a single configuration setting, abstracting away all the transaction management.

### The processing.guarantee setting

```java
Properties props = new Properties();
props.put(StreamsConfig.APPLICATION_ID_CONFIG, "order-processor");
props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "broker1:9092");

// Option 1: At-least-once (default)
props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG,
    StreamsConfig.AT_LEAST_ONCE);

// Option 2: Exactly-once V2 (recommended for EOS, since Kafka 2.6)
props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG,
    StreamsConfig.EXACTLY_ONCE_V2);
```

### What EXACTLY_ONCE_V2 does automatically

Kafka Streams with `EXACTLY_ONCE_V2` automatically:
1. Creates one transactional producer per task (not per thread like the old `EXACTLY_ONCE`)
2. Assigns `transactional.id = applicationId + "-" + taskId`
3. Wraps each commit interval's worth of processing in a transaction:
   ```
   beginTransaction()
   → process records
   → produce to output topics
   → sendOffsetsToTransaction() for all input partitions
   → commitTransaction()
   ```
4. Handles zombie fencing via producer epoch when tasks are reassigned
5. Configures `isolation.level=read_committed` on internal consumers

### EXACTLY_ONCE vs EXACTLY_ONCE_V2

| | EXACTLY_ONCE (old) | EXACTLY_ONCE_V2 |
|---|---|---|
| Kafka minimum version | 0.11 | 2.5 |
| Producers per app | One per thread | One per task |
| Fencing mechanism | Epoch per thread | Epoch per task |
| Throughput | Lower (more producers) | Higher (fewer producers, better batching) |
| Recommended | No | **Yes** |

### Commit interval and latency

```java
// How often Kafka Streams commits (default 100ms for EOS)
props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 100);

// Each commit = one transaction
// Lower commit interval → smaller transactions → lower latency but more overhead
// Higher commit interval → larger transactions → higher throughput but higher latency
// For EOS, 100ms is usually the right balance
```

### State stores and exactly-once

Kafka Streams state stores (RocksDB) are backed by changelog topics. With `EXACTLY_ONCE_V2`:
- State store writes are part of the same transaction as output records
- If the transaction aborts, state store changes are rolled back via changelog replay
- Provides exactly-once for stateful operations (aggregations, joins, windowing)

---

## 10. Failure Scenarios and Recovery

Understanding the exact failure modes helps you reason about which guarantee you're actually getting.

### Scenario 1: Leader broker failure (acks=1)

```
State: Producer sent batch with acks=1
       Leader wrote to local log
       Leader sent ack to producer
       Leader crashes before followers replicate

Outcome:
  - Producer received success ack (no retry)
  - Controller promotes a follower (which doesn't have the record)
  - Record is PERMANENTLY LOST from the log
  - Consumer never sees this record

Guarantee: AT-MOST-ONCE (producer side)
Fix: Use acks=all
```

### Scenario 2: Follower lag (acks=all + min.isr=2)

```
State: Producer sent batch with acks=all, min.isr=2
       Leader wrote to its log
       One follower replicated, one is lagging (ISR = {leader, follower1})
       Leader sent ack (2 of 3 replicas = meets min.isr=2)
       Leader + follower1 both crash

Outcome:
  - follower2 (lagging, not in ISR) becomes leader
  - It does NOT have this record
  - Record is PERMANENTLY LOST

Guarantee: Still loss possible under simultaneous multi-failure
Fix: Understand that acks=all + min.isr=2 tolerates at most 1 failure, not 2
```

### Scenario 3: Ack lost in network (without idempotence)

```
State: Producer sent batch, broker wrote it, ack packet dropped
       Producer's request.timeout.ms fires

Without enable.idempotence=true:
  - Producer retries the same batch
  - Broker has no memory of previous write
  - Broker writes DUPLICATE
  - Two identical records in the log

With enable.idempotence=true:
  - Producer retries with same PID + sequence number
  - Broker checks: already have this sequence for this PID
  - Broker discards retry silently
  - Only one copy in the log

Fix: Always enable.idempotence=true
```

### Scenario 4: Consumer crash after process, before commit

```
State: Consumer processed records [100, 101, 102]
       Consumer crashed before commitSync()
       Last committed offset = 99

On restart:
  - Consumer reads from offset 100
  - Records 100, 101, 102 reprocessed

With at-least-once pattern:
  - These records are processed twice
  - Consumer must be idempotent to handle safely

With exactly-once (Kafka transactions):
  - If output records from the first run were in an uncommitted transaction
  - Transaction coordinator aborts them on producer restart
  - read_committed consumers never saw the first run's output
  - Second run produces correctly and commits
  - Exactly once in output topic

Fix for non-Kafka targets: Idempotent writes or DB-stored offsets
```

### Scenario 5: Transaction coordinator failure

```
State: Producer called commitTransaction()
       Coordinator wrote PREPARE_COMMIT to __transaction_state
       Coordinator crashed before writing COMMIT markers to partitions

Recovery:
  - New coordinator leader elected
  - Reads __transaction_state: sees PREPARE_COMMIT for this transactional.id
  - Recognises intent to commit
  - Writes COMMIT markers to all involved partitions
  - Transaction completes successfully

Outcome: No data loss, no duplicates — the PREPARE_COMMIT is the durability point

Guarantee: Exactly-once achieved even across coordinator failure
```

### Scenario 6: Rebalance during transaction

```
State: Consumer is processing records in a transaction
       Rebalance is triggered (new consumer joined, heartbeat missed)
       Consumer's partition is revoked mid-transaction

Correct handling:
  onPartitionsRevoked() {
      producer.abortTransaction(); // abort incomplete transaction
  }
  // New consumer assigned the partition
  // Reads from last committed offset
  // Processes records that were in the aborted transaction again
  // Produces output that is committed successfully

Outcome: At-least-once processing at rebalance boundaries
         Within stable processing: exactly-once
```

---

## 11. Complete Java Implementation Examples

### At-least-once (standard production)

```java
@Service
public class AtLeastOnceConsumer {
    private final KafkaConsumer<String, String> consumer;
    private final OrderService orderService;

    public AtLeastOnceConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,   "broker1:9092,broker2:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG,            "order-service-prod");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,  false);  // manual commit
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,   "latest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,    500);
        props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
            CooperativeStickyAssignor.class.getName());
        this.consumer = new KafkaConsumer<>(props);
    }

    public void run() {
        consumer.subscribe(List.of("orders"), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                // Commit current progress before partitions are handed off
                consumer.commitSync();
            }
            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {}
        });

        try {
            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));

                for (ConsumerRecord<String, String> record : records) {
                    try {
                        // Idempotent processing — safe to call multiple times
                        orderService.upsertOrder(record.key(), record.value());
                    } catch (Exception e) {
                        log.error("Failed to process record at offset {}", record.offset(), e);
                        // Decide: skip or send to DLQ
                        sendToDLQ(record, e);
                    }
                }

                consumer.commitAsync((offsets, exception) -> {
                    if (exception != null) {
                        log.warn("Async commit failed: {}", exception.getMessage());
                    }
                });
            }
        } finally {
            try {
                consumer.commitSync(); // final sync commit on shutdown
            } finally {
                consumer.close();
            }
        }
    }
}
```

### Exactly-once (transactional, Kafka → Kafka)

```java
@Service
public class ExactlyOnceProcessor {
    private final KafkaProducer<String, String> producer;
    private final KafkaConsumer<String, String> consumer;

    public ExactlyOnceProcessor() {
        // Producer
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,  "broker1:9092");
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG,
            "order-enricher-" + System.getenv("POD_NAME")); // stable unique ID
        producerProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG,   "lz4");
        producerProps.put(ProducerConfig.BATCH_SIZE_CONFIG,         131072);
        producerProps.put(ProducerConfig.LINGER_MS_CONFIG,          5);
        this.producer = new KafkaProducer<>(producerProps);
        this.producer.initTransactions(); // must complete before use

        // Consumer
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,         "broker1:9092");
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG,                  "order-enricher-group");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,    StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,  StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,        false); // required for EOS
        consumerProps.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG,           "read_committed"); // required for EOS
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,         "earliest");
        consumerProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,          200); // smaller batches for EOS
        this.consumer = new KafkaConsumer<>(consumerProps);
    }

    public void run() {
        consumer.subscribe(List.of("orders-raw"));

        try {
            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                if (records.isEmpty()) continue;

                producer.beginTransaction();
                try {
                    for (ConsumerRecord<String, String> record : records) {
                        String enriched = enrich(record.value());
                        producer.send(new ProducerRecord<>("orders-enriched", record.key(), enriched));
                    }

                    // Build offset map: each partition → last offset + 1
                    Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
                    for (TopicPartition tp : records.partitions()) {
                        List<ConsumerRecord<String, String>> recs = records.records(tp);
                        long lastOffset = recs.get(recs.size() - 1).offset();
                        offsets.put(tp, new OffsetAndMetadata(lastOffset + 1));
                    }

                    // Atomic: output records + offset commit
                    producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
                    producer.commitTransaction();

                } catch (ProducerFencedException | InvalidProducerEpochException e) {
                    log.error("This instance has been fenced — shutting down");
                    producer.close();
                    System.exit(1); // truly fatal
                } catch (KafkaException e) {
                    log.warn("Transaction failed, aborting: {}", e.getMessage());
                    producer.abortTransaction();
                    // consumer position unchanged — will reprocess on next poll
                }
            }
        } finally {
            producer.close();
            consumer.close();
        }
    }
}
```

### At-most-once (intentional — high-throughput metrics)

```java
Properties producerProps = new Properties();
producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,  "broker1:9092");
producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class.getName());
producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
producerProps.put(ProducerConfig.ACKS_CONFIG,               "0");   // no ack
producerProps.put(ProducerConfig.RETRIES_CONFIG,            0);      // no retry
producerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);  // not needed
producerProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG,   "lz4");
producerProps.put(ProducerConfig.BATCH_SIZE_CONFIG,         524288); // 512 KB
producerProps.put(ProducerConfig.LINGER_MS_CONFIG,          10);     // 10ms for batching

// Send and forget — no callback, no Future.get()
metricsProducer.send(new ProducerRecord<>("app-metrics", hostname, metricJson));
```

---

## 12. Performance Trade-offs

Each delivery guarantee has a throughput and latency cost.

### Throughput comparison (relative)

| Configuration | Relative Throughput | Added Latency | Notes |
|---|---|---|---|
| acks=0, no retry | 100% | 0 | Baseline |
| acks=1, no idempotence | 85% | +5ms | Leader RTT |
| acks=all, no idempotence | 75% | +15ms | ISR replication RTT |
| acks=all + idempotence | 73% | +15ms | Negligible overhead vs acks=all |
| acks=all + transactions (good batching) | 65% | +20ms | ~15% vs acks=all |
| acks=all + transactions (1 txn per record) | 25% | +50ms | Never do this |

### Key cost drivers for transactions

**Transaction coordinator overhead:**
Each transaction requires at minimum:
- 1x `AddPartitionsToTxn` RPC (once per new partition per transaction)
- 1x `EndTxn` RPC (commit or abort)
- 1x `WriteTxnMarkers` per partition (written by coordinator, not producer)
- 4-5 records written to `__transaction_state`

**Amortisation through batching:**
```
Cost per transaction: ~5ms
Batch of 1 record:    5ms / 1 record = 5ms/record overhead
Batch of 1000 records: 5ms / 1000 records = 0.005ms/record overhead

Always batch multiple records per transaction.
```

**consumer read_committed overhead:**
`read_committed` consumers must buffer transactional records until they see a COMMIT or ABORT marker. This adds latency proportional to transaction duration (typically your `commit.interval.ms` — default 100ms in Kafka Streams).

### Tuning for throughput with EOS

```java
// Kafka Streams EOS with throughput tuning
Properties props = new Properties();
props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);

// Larger commit interval = larger transactions = better batching
props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000); // 1 second (default 100ms)

// Allow more records per commit to amortize transaction overhead
props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1000);

// Producer batching inside transaction
props.put(ProducerConfig.BATCH_SIZE_CONFIG, 262144);       // 256 KB
props.put(ProducerConfig.LINGER_MS_CONFIG, 20);            // 20ms within transaction
```

---

## 13. Common Misconceptions

### Misconception 1: "acks=all means exactly-once"

`acks=all` means **at least one copy of the message survives** broker failure. It does not prevent duplicate production (a lost ack still causes a retry that creates a duplicate). You need `enable.idempotence=true` in addition.

### Misconception 2: "enable.idempotence=true means exactly-once for the consumer"

The idempotent producer prevents **producer-side** duplicates. If the consumer crashes after processing but before committing, it will reprocess the message. The producer-side guarantee does not flow through to the consumer. Consumer-side idempotency is still needed.

### Misconception 3: "auto.offset.reset=latest prevents redelivery"

`auto.offset.reset` only applies when there is **no committed offset** — brand new consumer group or expired offsets. If a committed offset exists, `auto.offset.reset` is completely ignored. Redelivery after crash happens regardless of this setting.

### Misconception 4: "Kafka transactions cover external databases"

Kafka transactions are atomic only within Kafka. The `commitTransaction()` call commits Kafka offsets and makes Kafka output records visible — it has zero knowledge of, or effect on, any external system. Use the Outbox pattern for cross-system consistency.

### Misconception 5: "More retries = more duplicates"

With `enable.idempotence=true`, more retries do NOT create more duplicates. The broker deduplicates via sequence numbers — retrying the same batch 100 times produces exactly one copy in the log. Retries without idempotence create duplicates; retries with idempotence do not.

### Misconception 6: "enable.auto.commit=false gives at-least-once"

`enable.auto.commit=false` is **necessary** for at-least-once but not sufficient. You must also call `commitSync()` **after** processing completes. If you call `commitSync()` before processing, you still get at-most-once.

### Misconception 7: "Transactions work with any consumer isolation level"

Transactions only provide their guarantee to consumers configured with `isolation.level=read_committed`. A consumer with `read_uncommitted` (the default) will see records from open AND aborted transactions — completely bypassing the transactional guarantees.

---

## 14. Decision Guide

### Which guarantee do you need?

```
Is message loss acceptable?
├── Yes → At-most-once
│         acks=0 or acks=1
│         enable.auto.commit=true
│         Use for: metrics, dashboards, IoT telemetry
│
└── No → Message loss is NOT acceptable
         │
         └── Are duplicates acceptable?
             │
             ├── Yes → At-least-once (simpler, higher throughput)
             │         acks=all + retries=MAX_INT + enable.idempotence=true
             │         enable.auto.commit=false + commitSync() after processing
             │         Make consumer idempotent (ON CONFLICT DO NOTHING, upsert)
             │         Use for: most production business events
             │
             └── No → Exactly-once required
                       │
                       └── Output is another Kafka topic?
                           │
                           ├── Yes → Kafka Transactions
                           │         transactional.id + sendOffsetsToTransaction()
                           │         consumer: isolation.level=read_committed
                           │         Or use Kafka Streams EXACTLY_ONCE_V2
                           │
                           └── No → Output is database / external system?
                                     │
                                     ├── Can store offset in same DB txn?
                                     │   → DB-stored offsets + consumer.seek()
                                     │
                                     ├── Can make writes idempotent by offset key?
                                     │   → At-least-once + ON CONFLICT DO NOTHING
                                     │
                                     └── Source is DB, output is Kafka?
                                         → Outbox pattern
```

### Quick configuration checklist

**At-most-once:**
- [ ] `acks=0` (or `acks=1` for leader-only durability)
- [ ] `retries=0`
- [ ] `enable.auto.commit=true` OR manually commit before processing

**At-least-once:**
- [ ] `acks=all`
- [ ] `retries=Integer.MAX_VALUE`
- [ ] `enable.idempotence=true`
- [ ] `enable.auto.commit=false`
- [ ] Call `commitSync()` AFTER processing
- [ ] Consumer logic is idempotent

**Exactly-once (Kafka → Kafka):**
- [ ] `transactional.id` set to stable unique value per instance
- [ ] `enable.idempotence=true` (auto-set)
- [ ] `acks=all` (auto-set)
- [ ] Consumer: `isolation.level=read_committed`
- [ ] Consumer: `enable.auto.commit=false`
- [ ] Use `sendOffsetsToTransaction()` not `commitSync()`
- [ ] Handle `ProducerFencedException` as fatal

**Exactly-once (Kafka → Database):**
- [ ] Store Kafka offsets in same DB transaction as business data
- [ ] `consumer.seek()` to DB-tracked offset on partition assign
- [ ] Never commit to `__consumer_offsets`
- [ ] OR use idempotent upsert with Kafka offset as key + at-least-once

---

## 15. Quick Reference

### Delivery guarantee comparison

| Guarantee | Loss possible | Duplicates possible | Complexity | Throughput |
|---|---|---|---|---|
| At-most-once | Yes | No | Low | Highest |
| At-least-once | No | Yes | Medium | High |
| Exactly-once (Kafka) | No | No | High | Medium |
| Exactly-once (with DB) | No | No | Very high | Medium |

### Producer config by guarantee

| Property | At-most-once | At-least-once | Exactly-once |
|---|---|---|---|
| `acks` | `0` | `all` | `all` (auto) |
| `retries` | `0` | `MAX_INT` | `MAX_INT` (auto) |
| `enable.idempotence` | `false` | `true` | `true` (auto) |
| `transactional.id` | not set | not set | unique stable value |
| `compression.type` | `lz4` | `lz4` | `lz4` |

### Consumer config by guarantee

| Property | At-most-once | At-least-once | Exactly-once |
|---|---|---|---|
| `enable.auto.commit` | `true` | `false` | `false` |
| `isolation.level` | `read_uncommitted` | `read_uncommitted` | `read_committed` |
| Commit call | auto (or before process) | `commitSync()` after | `sendOffsetsToTransaction()` |
| Consumer idempotency | not needed | required | not needed (handled by txn) |

### Failure mode cheat sheet

| Failure | acks=0 | acks=1 | acks=all | acks=all + idempotence | acks=all + transactions |
|---|---|---|---|---|---|
| Producer → broker: network drop | Lost | Lost (retry=dupe) | Lost (retry=dupe) | Safe (dedup) | Safe |
| Leader crash before replication | Lost | Lost | Safe (ISR) | Safe | Safe |
| Leader crash after ack | N/A | Lost | Safe | Safe | Safe |
| Consumer crash before commit | Lost | Redelivered | Redelivered | Redelivered | Safe (txn aborted) |
| Broker restart | Lost | Possible loss | Safe | Safe | Safe |
| Coordinator crash mid-commit | N/A | N/A | N/A | N/A | Safe (PREPARE_COMMIT durable) |

---

*The key insight: delivery semantics are not a single dial. They are the product of independent choices at the producer, broker, and consumer layers. Getting exactly-once requires all three layers to be configured correctly — one wrong setting anywhere collapses the guarantee to at-most-once or at-least-once.*
