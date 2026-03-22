# 09 — Delivery Semantics

> At-most-once, at-least-once, and exactly-once — how each guarantee works, what breaks it, and how to achieve it.

---

## The Two Independent Axes

Delivery semantics are determined by two independent layers that must BOTH be correct.

```
Axis 1: Producer → Broker
  Controls: Does the message reach the broker? How many times?
  Config:   acks, retries, enable.idempotence, transactional.id

Axis 2: Consumer → Application
  Controls: How many times does app code process each message?
  Config:   enable.auto.commit, commit timing, isolation.level

End-to-end guarantee = min(producer guarantee, consumer guarantee)
A perfectly idempotent producer cannot compensate for
a consumer that commits offsets before processing.
```

---

## At-Most-Once

Message delivered **0 or 1 times**. Loss possible. No duplicates.

### Producer-Side At-Most-Once

```java
Properties props = new Properties();
props.put(ProducerConfig.ACKS_CONFIG, "0");   // no acknowledgment
props.put(ProducerConfig.RETRIES_CONFIG, 0);   // no retry

producer.send(record);  // fire and forget
// Future completes immediately — no guarantee message reached broker
```

**What happens on network failure:**
```
t=0  Producer sends ProduceRequest to broker
t=0  Network drops the request
t=0  Producer: Future already marked SUCCESS (acks=0 doesn't wait)
t=1  Broker never received it
     Message is PERMANENTLY LOST — no error to the application
```

**What happens with acks=1 on leader failure:**
```
t=0  Producer sends ProduceRequest (acks=1)
t=0  Leader writes to its log
t=0  Leader sends ack to producer
t=1  Ack received by producer — Future SUCCESS
t=1  Leader crashes BEFORE followers replicate
t=2  Follower promoted — does not have this message
     Message is PERMANENTLY LOST — producer already received SUCCESS ack
```

### Consumer-Side At-Most-Once

```java
// DANGEROUS — default behavior
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);

while (true) {
    ConsumerRecords<?, ?> records = consumer.poll(Duration.ofMillis(100));
    // Auto-commit fires HERE (based on auto.commit.interval.ms timer)
    // Committed: offsets for records just returned
    for (var record : records) {
        process(record);  // if this crashes, records are already committed
    }
}
```

**Failure timeline:**
```
poll() → [auto-commit: offsets 100-104 committed] → process(100) → process(101) → CRASH
Restart: reads from offset 105
Offsets 102, 103, 104: PERMANENTLY SKIPPED
```

### When At-Most-Once Is Acceptable

- High-frequency sensor readings (next reading arrives in milliseconds)
- Real-time dashboard metrics (missing a data point is tolerable)
- Access/click logs (statistical accuracy more important than completeness)
- Any use case where throughput >> correctness

---

## At-Least-Once

Message delivered **one or more times**. No loss. Duplicates possible.

### Producer-Side At-Least-Once

```java
Properties props = new Properties();
props.put(ProducerConfig.ACKS_CONFIG, "all");
props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);  // dedup producer retries
props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000);
```

**How duplicates occur WITHOUT idempotence:**
```
t=0  Producer sends batch [seq=5]
t=0  Broker writes at offset 1000000
t=0  Broker sends ack
t=1  ACK LOST in network (TCP retransmit timeout)
t=3  Producer retries [seq=5]
t=3  Broker has no memory of prior write (no idempotence)
t=3  Broker writes AGAIN at offset 1000001
     Consumer sees DUPLICATE at offsets 1000000 and 1000001
```

**With idempotence (enable.idempotence=true):**
```
t=3  Producer retries [PID=1001, seq=5]
t=3  Broker checks: already have seq=5 for PID=1001
t=3  DUPLICATE — broker silently discards, returns SUCCESS
     One copy in the log at offset 1000000 — no duplicate
```

### Consumer-Side At-Least-Once

```java
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

while (true) {
    ConsumerRecords<?, ?> records = consumer.poll(Duration.ofMillis(100));
    for (var record : records) {
        process(record);
    }
    consumer.commitSync();  // commit AFTER processing
}
```

**Crash scenario:**
```
poll() → records [100, 101, 102, 103, 104]
process(100) → success
process(101) → success
process(102) → CRASH (before commitSync)

Restart: last committed offset = 99
Records 100, 101 REDELIVERED → processed TWICE
Consumer must be idempotent to handle this safely
```

### Making Consumers Idempotent

**Strategy 1: Natural business idempotency**
```java
// Upsert instead of insert — safe to call multiple times
jdbcTemplate.update("""
    INSERT INTO orders (order_id, status, amount)
    VALUES (?, ?, ?)
    ON CONFLICT (order_id) DO UPDATE
    SET status = EXCLUDED.status
    WHERE orders.status != 'SHIPPED'
""", record.key(), order.getStatus(), order.getAmount());
```

**Strategy 2: Offset-based idempotency key**
```java
String idempotencyKey = record.topic() + ":" + record.partition() + ":" + record.offset();

jdbcTemplate.update("""
    INSERT INTO processed_events (idempotency_key, payload)
    VALUES (?, ?)
    ON CONFLICT (idempotency_key) DO NOTHING
""", idempotencyKey, record.value());
```

**Strategy 3: Redis deduplication**
```java
String key = "processed:" + record.topic() + ":" + record.partition() + ":" + record.offset();
Boolean isNew = redis.setIfAbsent(key, "1", Duration.ofDays(7));
if (Boolean.TRUE.equals(isNew)) {
    process(record);
}
consumer.commitSync();
```

---

## Exactly-Once (EOS)

Message delivered **exactly one time**. No loss. No duplicates. **Within Kafka only** — external systems need additional patterns.

### Component 1: Idempotent Producer

Solves producer-retry duplicates (see above). Prerequisite for transactions.

### Component 2: Kafka Transactions (2PC)

Solves consumer-reprocessing duplicates by making output produce and offset commit **atomic**.

**The problem without transactions:**
```
t=0  Consumer reads offset 100 from orders-input
t=1  Producer writes transformed record to orders-output → SUCCESS
t=2  CRASH (before committing input offset)
t=3  Consumer restarts from offset 100
t=4  Producer writes DUPLICATE to orders-output
     orders-output has two copies of the record → duplicate
```

**With transactions:**
```java
producer.initTransactions();

while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    if (records.isEmpty()) continue;

    producer.beginTransaction();
    try {
        for (ConsumerRecord<String, String> record : records) {
            String transformed = transform(record.value());
            producer.send(new ProducerRecord<>("orders-output", record.key(), transformed));
        }

        // ATOMIC: output produce + offset commit in one transaction
        Map<TopicPartition, OffsetAndMetadata> offsets = buildOffsets(records);
        producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
        producer.commitTransaction();
        // NOW: output record visible AND input offset committed — atomically

    } catch (ProducerFencedException e) {
        producer.close();  // THIS INSTANCE IS A ZOMBIE — stop immediately
        throw e;
    } catch (KafkaException e) {
        producer.abortTransaction();
        // Output invisible AND offset unchanged → safe replay
    }
}
```

**Required configuration:**
```java
// Producer
props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "processor-" + podName);
// Auto-configured by transactional.id:
// enable.idempotence=true, acks=all, retries=MAX_INT

// Consumer
props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
// DO NOT call consumer.commitSync() — offset committed via sendOffsetsToTransaction
```

---

## Transaction Internals

### transactional.id and Zombie Fencing

```
Instance A: transactional.id="processor-pod-0", epoch=5
  → crashes (OOM), orchestrator starts replacement

Instance B: transactional.id="processor-pod-0", epoch=6  (after initTransactions())
  → becomes the rightful owner

Instance A revives from crash, tries to produce:
  → Broker rejects: epoch=5 < current epoch=6
  → ProducerFencedException → Instance A must close and stop
```

**transactional.id naming rules:**
```
CORRECT: "payment-processor-" + System.getenv("POD_NAME")
         "order-enricher-" + assignedPartition

WRONG:   "processor-" + UUID.randomUUID()  // new ID every restart, no fencing
WRONG:   "shared-processor"               // multiple instances share same ID → fence each other
```

### Two-Phase Commit Protocol

```
producer.commitTransaction():

Phase 1 — PREPARE (durable intent):
  Producer → EndTxn(commit=true) → Transaction Coordinator
  Coordinator writes PREPARE_COMMIT to __transaction_state
  ← DURABILITY POINT — transaction WILL complete even if coordinator crashes
  Coordinator returns to producer (producer's job is done)

Phase 2 — COMMIT (coordinator-driven, async from producer):
  Coordinator → WriteTxnMarkers(COMMIT) → each partition leader
  Each leader writes COMMIT control record to its .log
  Consumers with read_committed now see all records in this transaction
  Coordinator writes COMPLETE_COMMIT to __transaction_state

Recovery (coordinator crashes after PREPARE_COMMIT):
  New coordinator reads __transaction_state
  Finds PREPARE_COMMIT → knows it must complete
  Re-sends WriteTxnMarkers to all partitions
  Transaction completes correctly
```

### COMMIT and ABORT Markers

Special control records written by the Transaction Coordinator to every partition involved in a transaction.

```
Partition log after transaction:
  offset 100: [TXN_START] ← transactional record (flagged in RecordBatch attributes)
  offset 101: [TXN record] data
  offset 102: [TXN record] data
  offset 103: [COMMIT marker] ← Transaction Coordinator wrote this

read_committed consumer:
  Sees offsets 100-102 only AFTER encountering COMMIT marker at 103
  If ABORT marker was at 103: offsets 100-102 skipped entirely

Note: COMMIT/ABORT markers remain in the log permanently.
      Filtering happens client-side in consumer library.
```

### isolation.level Deep Dive

```java
// read_uncommitted (default):
props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_uncommitted");
// Consumer reads up to HIGH WATERMARK
// Sees: committed records, open transaction records, aborted transaction records
// Risk: processes data that might later be aborted

// read_committed:
props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
// Consumer reads up to LAST STABLE OFFSET (LSO)
// Sees: only committed transaction records
// Skips: aborted transaction records
// Buffers: open transaction records until COMMIT/ABORT marker seen
```

**LSO blocking risk:**
```
Open transaction at offset 1000:
  LSO = 999
  read_committed consumers: STUCK at offset 999
  Even though HW=5000, consumers cannot read 1001-5000

Mitigation: transaction.timeout.ms=60000 (default)
  After 60 seconds, Transaction Coordinator auto-aborts the stuck transaction
  LSO advances, consumers unblocked
```

---

## Exactly-Once with External Systems

Kafka transactions don't extend to databases, REST APIs, or file systems. For cross-system exactly-once:

### Outbox Pattern (DB → Kafka)

```sql
-- Business data and Kafka event in ONE ACID transaction
BEGIN;
INSERT INTO orders (id, customer_id, amount, status) VALUES (?, ?, ?, 'PENDING');
INSERT INTO outbox (topic, partition_key, payload, created_at, published)
    VALUES ('orders', ?, ?, NOW(), false);
COMMIT;
-- If either INSERT fails, both are rolled back (DB ACID guarantee)
```

```java
// Separate outbox publisher (idempotent, runs async)
@Scheduled(fixedDelay = 100)
public void publishOutbox() {
    List<OutboxEvent> pending = outboxRepo.findByPublishedFalse();
    for (OutboxEvent event : pending) {
        producer.send(new ProducerRecord<>(event.getTopic(), event.getKey(), event.getPayload()));
        outboxRepo.markPublished(event.getId());
    }
}
```

### DB-Stored Offsets (Kafka → DB)

```java
// Store Kafka offset in same DB transaction as business data
try (Connection conn = dataSource.getConnection()) {
    conn.setAutoCommit(false);
    
    for (ConsumerRecord<?, ?> record : records) {
        insertBusinessData(conn, record);
        upsertKafkaOffset(conn, groupId, record.topic(), record.partition(), record.offset() + 1);
    }
    
    conn.commit();  // ATOMIC: business data + offset
}

// On startup: read offset from DB and seek to it
// auto.offset.reset is irrelevant — we manage offsets manually
```

---

## Delivery Semantics Comparison

| Guarantee | Loss possible | Duplicates possible | Config requirement |
|---|---|---|---|
| At-most-once | Yes | No | acks=0 or auto-commit before processing |
| At-least-once | No | Yes | acks=all + commit after processing + idempotent consumer |
| EOS (within Kafka) | No | No | transactional.id + isolation.level=read_committed |
| EOS (with DB) | No | No | Outbox pattern or DB-stored offsets |

---

## Common Mistakes

| Mistake | Consequence | Fix |
|---|---|---|
| `acks=all` without idempotence | Duplicates on retry | `enable.idempotence=true` |
| `enable.auto.commit=true` | At-most-once (loss) | `enable.auto.commit=false` |
| `commitSync()` before processing | At-most-once (loss) | commitSync() after processing |
| `auto.offset.reset=earliest` on old topic | Replay months of data | Pre-set offsets before deploy |
| Retry in `commitAsync()` callback | Offset rollback → duplicates | Don't retry async commits |
| `transactional.id=UUID.randomUUID()` | No zombie fencing | Use stable pod/instance ID |
| `consumer.commitSync()` in EOS loop | Breaks transactional offset | Use `sendOffsetsToTransaction` |
| Kafka EOS for external DB writes | Not atomic across systems | Use Outbox pattern |
