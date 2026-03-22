# Kafka Transactions — Deep Dive

> Everything a senior Java backend engineer needs to know: internals, two-phase commit, zombie fencing, exactly-once semantics, isolation levels, and production patterns.

---

## Table of Contents

1. [Why Transactions Exist](#1-why-transactions-exist)
2. [Core Concepts and Terminology](#2-core-concepts-and-terminology)
3. [Transaction Internals — Step by Step](#3-transaction-internals--step-by-step)
4. [The Two-Phase Commit Protocol](#4-the-two-phase-commit-protocol)
5. [Idempotent Producer — The Foundation](#5-idempotent-producer--the-foundation)
6. [Zombie Fencing and Producer Epochs](#6-zombie-fencing-and-producer-epochs)
7. [Isolation Levels — read_committed vs read_uncommitted](#7-isolation-levels--read_committed-vs-read_uncommitted)
8. [The Read-Process-Write Pattern (EOS)](#8-the-read-process-write-pattern-eos)
9. [Complete Java Implementation](#9-complete-java-implementation)
10. [Transaction Configuration Reference](#10-transaction-configuration-reference)
11. [The __transaction_state Topic](#11-the-__transaction_state-topic)
12. [Transactions vs External Systems](#12-transactions-vs-external-systems)
13. [Error Handling and Recovery](#13-error-handling-and-recovery)
14. [Performance Implications](#14-performance-implications)
15. [Common Pitfalls](#15-common-pitfalls)
16. [Monitoring Transactions](#16-monitoring-transactions)
17. [Quick Reference](#17-quick-reference)

---

## 1. Why Transactions Exist

Without transactions, Kafka provides **at-least-once delivery** — messages are delivered at least once but may be delivered multiple times on failure. This is acceptable for many use cases but breaks applications that require correctness.

### The Fundamental Problem

Consider a stream processing application that reads from Topic A, transforms records, and writes to Topic B:

```
Consumer reads record (offset 100) from Topic A
     ↓
Application transforms the record
     ↓
Producer writes result to Topic B   ← SUCCESS
     ↓
CRASH HERE (before offset commit)
     ↓
On restart: consumer replays from offset 100
     ↓
Producer writes DUPLICATE result to Topic B ← PROBLEM
```

Two failure modes without transactions:

**At-least-once** (typical pattern): Process → produce → commit offset. If crash between produce and commit, message is produced twice.

**At-most-once** (dangerous): Commit offset → process → produce. If crash between commit and produce, message is lost forever.

### What Transactions Provide

Kafka transactions give you **exactly-once processing** within the Kafka ecosystem:

- Atomic writes across **multiple partitions** — either all succeed or all are rolled back
- Atomic **offset commit + message produce** — the consumer position advances only if the output was successfully written
- **Idempotent delivery** even across producer restarts via zombie fencing

---

## 2. Core Concepts and Terminology

### Transaction Coordinator

A **Transaction Coordinator** is a role played by a specific broker for each `transactional.id`. It is the broker that leads the `__transaction_state` partition assigned to that `transactional.id`:

```
partition = hash(transactional.id) % transaction.state.log.num.partitions (default 50)
```

The Transaction Coordinator:
- Stores the ongoing transaction log (which partitions are involved, current state)
- Drives the two-phase commit protocol
- Handles producer epoch management and zombie fencing
- Recovers incomplete transactions after broker restarts

### Producer ID (PID)

A unique numeric identifier assigned to a producer by the broker when it calls `initTransactions()`. Used together with the producer epoch to:
1. Enable idempotent deduplication (sequence numbers per PID+partition)
2. Fence zombie producers (old epoch = rejected)

### Producer Epoch

A monotonically increasing counter tied to a `transactional.id`. Each new call to `initTransactions()` with the same `transactional.id` increments the epoch. Any produce requests with an older epoch are **rejected by the broker** — this is zombie fencing.

```
First initTransactions():  PID=1001, epoch=0
Second initTransactions(): PID=1001, epoch=1  ← invalidates epoch=0
Third initTransactions():  PID=1001, epoch=2  ← invalidates epoch=1
```

### Transaction Log (`__transaction_state`)

An internal Kafka topic (50 partitions, replication factor 3 by default) that stores the durable state of every transaction. Written by the Transaction Coordinator. Compacted — keeps only the latest state per `transactional.id`.

States stored:
- `Empty` → initial state, no active transaction
- `Ongoing` → transaction in progress
- `PrepareCommit` → two-phase commit phase 1 complete
- `PrepareAbort` → abort decided, writing markers
- `CompleteCommit` → all commit markers written, transaction done
- `CompleteAbort` → all abort markers written, transaction done
- `Dead` → transactional.id expired (after `transactional.id.expiration.ms`)

### Commit and Abort Markers

Special **control records** written by the Transaction Coordinator to every partition involved in a transaction. These are not user data — they are metadata records in the partition log that tell consumers where transactions begin and end.

- **COMMIT marker**: All transactional records preceding this marker in the same transaction are now visible to `read_committed` consumers.
- **ABORT marker**: All transactional records preceding this marker are to be skipped by `read_committed` consumers.

### Last Stable Offset (LSO)

The highest offset in a partition where all preceding transactions are fully resolved (committed or aborted). `read_committed` consumers only read up to the LSO — they cannot read past an open transaction.

```
Partition offsets:   0    1    2    3    4    5    6    7    8
                    [msg][msg][TXN_START...........][msg][msg]
                                    ↑
                                   LSO = 2
                                   HW  = 8

read_uncommitted sees: offsets 0–8
read_committed sees:   offsets 0–2 (blocked by open transaction at offset 3)
```

---

## 3. Transaction Internals — Step by Step

### Step 1: `producer.initTransactions()`

**What happens internally:**

```
Producer → FindCoordinator(transactional.id) → Any Broker
                                                    ↓
                              Returns: coordinator broker ID and address
                                                    ↓
Producer → InitProducerId(transactional.id, transactionTimeoutMs) → Coordinator
                                                    ↓
                       Coordinator checks __transaction_state for this transactional.id
                       If existing state: increment epoch, fence old producer
                       If new: assign new PID and epoch=0
                                                    ↓
                       Writes new PID+epoch to __transaction_state (durable)
                                                    ↓
                              Returns: PID, epoch → Producer
```

This call **blocks** until complete. It must succeed before any sends. The epoch increment is the zombie fencing mechanism — any prior producer instance with the same `transactional.id` will now be rejected.

---

### Step 2: `producer.beginTransaction()`

Pure client-side state change. Sets an internal flag `transactionInFlight = true`. No network calls. No broker state change.

---

### Step 3: `producer.send()`

```
Producer writes record to accumulator (with PID and epoch tagged)
     ↓
Sender thread batches records for each partition
     ↓
First send to a new partition within this transaction:
     → AddPartitionsToTxn(transactional.id, [topic-partition]) → Coordinator
     → Coordinator writes "partition is part of transaction" to __transaction_state
     → Coordinator returns success
     ↓
Produce batch to partition leader (with transactional metadata: PID, epoch, sequence)
     ↓
Records written to partition log — VISIBLE IN LOG but not yet visible
to read_committed consumers (they read only up to LSO, which hasn't advanced)
```

Records **are written to disk** during the transaction. They are NOT buffered in memory on the broker waiting for commit. The commit/abort markers control consumer visibility — not whether records exist on disk.

---

### Step 4: `producer.sendOffsetsToTransaction(offsets, groupMetadata)`

Used in read-process-write (EOS) patterns to atomically advance the consumer position:

```
Producer → TxnOffsetCommit(transactional.id, consumer_group, offsets) → Coordinator
     ↓
Coordinator forwards to __consumer_offsets partition for this consumer group
     ↓
Offsets written to __consumer_offsets as TRANSACTIONAL records
(pending — not yet visible to the group coordinator for restart positioning)
     ↓
Returns success
```

The offset commit is now part of the transaction. If the transaction commits, the offset is visible. If aborted, the offset commit is discarded.

---

### Step 5a: `producer.commitTransaction()` — Two-Phase Commit

**Phase 1 — Prepare (durable intent):**
```
Producer → EndTxn(transactional.id, commit=true) → Coordinator
     ↓
Coordinator writes PREPARE_COMMIT to __transaction_state
(After this write, the transaction WILL commit even if coordinator crashes)
     ↓
Returns to producer (producer's work is done here)
```

**Phase 2 — Write markers (done by coordinator, async from producer):**
```
Coordinator sends WriteTxnMarkers to each partition leader involved in the transaction
     ↓
Each partition leader writes COMMIT marker to its log
     ↓
LSO on each partition advances past the committed records
read_committed consumers now see all records in this transaction
     ↓
Coordinator writes COMPLETE_COMMIT to __transaction_state
Transaction is fully done
```

---

### Step 5b: `producer.abortTransaction()` — Rollback

```
Producer → EndTxn(transactional.id, commit=false) → Coordinator
     ↓
Coordinator writes PREPARE_ABORT to __transaction_state
     ↓
Coordinator sends WriteTxnMarkers(abort) to each partition
     ↓
Each partition leader writes ABORT marker to its log
     ↓
read_committed consumers skip all records in this transaction when they encounter the ABORT marker
     ↓
Coordinator writes COMPLETE_ABORT to __transaction_state
```

---

## 4. The Two-Phase Commit Protocol

Kafka's transaction protocol is a variant of two-phase commit (2PC) designed for the distributed log model.

### Why 2PC is needed

The problem: we need to atomically write commit markers to N partitions potentially on N different brokers. If we write to some but then crash, some consumers see the commit, some don't — inconsistency.

### How Kafka's 2PC works

```
Phase 1 — Coordinator writes durable intent:

  Coordinator → __transaction_state (durable write)
  State: PREPARE_COMMIT

  Effect: Even if the coordinator dies here,
          on recovery it will find PREPARE_COMMIT
          and complete the commit.

Phase 2 — Coordinator writes markers to all partitions:

  Coordinator → Broker A (partition 0 leader) → COMMIT marker
  Coordinator → Broker B (partition 1 leader) → COMMIT marker
  Coordinator → Broker C (__consumer_offsets)  → COMMIT marker
  
  Effect: Once all markers are written, consumers can see the data.
          If coordinator dies mid-phase-2, recovery picks up where it left off.

Final — Coordinator marks transaction complete:

  Coordinator → __transaction_state → COMPLETE_COMMIT
```

### Crash recovery scenarios

| Crash point | Recovery action |
|---|---|
| Before `initTransactions()` completes | Producer retries. No partial state. |
| After `send()` but before `commitTransaction()` | On producer restart: new `initTransactions()` → coordinator checks state → state is `Ongoing` → coordinator **aborts** the incomplete transaction automatically |
| After `PREPARE_COMMIT` written | Coordinator restarts → reads `PREPARE_COMMIT` from `__transaction_state` → continues writing COMMIT markers → completes successfully |
| During Phase 2 (some markers written) | Coordinator restarts → re-reads partition list from `__transaction_state` → retries writing missing markers → idempotent (writing a marker twice is harmless) |

---

## 5. Idempotent Producer — The Foundation

Kafka transactions are built on top of the **idempotent producer**. You cannot use transactions without idempotence (it is automatically enabled when you set `transactional.id`).

### The idempotency problem

Without idempotence: producer sends batch, broker writes it, ack is lost in network, producer retries → **broker writes duplicate**.

### How sequence numbers solve it

Each producer is assigned a **PID** and maintains a **sequence number per partition** (starts at 0, increments by 1 per batch):

```
Producer sends batch to partition 0:
  PID=1001, epoch=0, seq=0 → broker writes at offset 5, stores (PID=1001, seq=0)
  
Network failure — ack lost

Producer retries:
  PID=1001, epoch=0, seq=0 → broker checks: already have seq=0 for PID=1001
                            → DUPLICATE, return success without writing
                            
Producer sends next batch:
  PID=1001, epoch=0, seq=1 → broker checks: expected seq=1 for PID=1001 ✓
                            → writes to offset 6
```

The broker detects and discards duplicates in flight. The producer's `Future` resolves as success (transparent to the application).

### Sequence number validation

The broker also validates **ordering**:
- Receives seq=5 but expected seq=7 → `OutOfOrderSequenceException` (a gap — data may be lost, serious error)
- Receives seq=9 but already have seq=9 → duplicate, silently discard

---

## 6. Zombie Fencing and Producer Epochs

### The zombie producer scenario

```
Timeline:
t=0  Instance A starts, gets epoch=5 for transactional.id="processor-1"
t=5  Instance A begins transaction, sends records
t=8  Instance A experiences long GC pause (appears dead to orchestrator)
t=9  Orchestrator starts Instance B with same transactional.id="processor-1"
t=9  Instance B calls initTransactions() → gets epoch=6, old epoch=5 now invalid
t=10 Instance B begins transaction, sends records, commits
t=12 Instance A recovers from GC pause, tries to commit its transaction
t=12 Broker: epoch=5 < current epoch=6 → ProducerFencedException → A must stop
```

Without fencing, both A and B could commit overlapping data for the same logical stream.

### The epoch mechanism

```java
// Instance A (zombie, epoch=5)
producer.send(record);
// ↑ Broker rejects: "You present epoch=5, current epoch for this transactional.id is 6"
// Throws: ProducerFencedException

// Instance B (live, epoch=6)
producer.send(record);  // ✓ accepted
producer.commitTransaction(); // ✓ succeeds
```

### Handling ProducerFencedException

`ProducerFencedException` is **unrecoverable** for the current producer instance. The instance must stop immediately — it is no longer the rightful owner of that `transactional.id`.

```java
try {
    producer.beginTransaction();
    producer.send(record);
    producer.commitTransaction();
} catch (ProducerFencedException e) {
    // MUST close and stop. Do NOT create a new producer with the same transactional.id
    // in this instance — that would fence the new legitimate instance.
    producer.close();
    log.error("This instance has been fenced. Shutting down.", e);
    // Signal the orchestrator to not restart this instance
    System.exit(1);
} catch (KafkaException e) {
    // Recoverable: abort and retry the transaction
    producer.abortTransaction();
    // retry logic...
}
```

### Rules for transactional.id assignment

```java
// CORRECT — stable, unique per logical stream partition
String transactionalId = "payment-processor-" + assignedPartition;
// e.g., "payment-processor-3"

// CORRECT — stable pod identity in Kubernetes  
String transactionalId = "order-service-" + System.getenv("POD_NAME");
// e.g., "order-service-pod-0"

// CORRECT — stable per application instance role
String transactionalId = appName + "-" + instanceIndex;
// e.g., "fraud-detector-0"

// WRONG — new ID on every restart, no fencing protection
String transactionalId = "producer-" + UUID.randomUUID();

// WRONG — shared ID across multiple instances, they'll fence each other
String transactionalId = "shared-processor";
```

---

## 7. Isolation Levels — read_committed vs read_uncommitted

### read_uncommitted (default)

Consumer reads up to the **High Watermark (HW)**. Sees:
- All committed non-transactional records
- All transactional records that have been written (even in open transactions)
- All records from **aborted** transactions (consumer has no way to distinguish them)

This is the correct setting for topics that don't use transactions. If you set `read_uncommitted` on a consumer reading transactional data, it will receive records from aborted transactions — data that should have been invisible.

### read_committed

Consumer reads up to the **Last Stable Offset (LSO)**. Sees:
- All committed non-transactional records
- Records from **committed** transactions only
- **Filters out** all records from aborted transactions (skips them transparently)
- Buffers records from open transactions until they are committed or aborted

```java
// Required for EOS consumers
props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
```

### How ABORT filtering works on the consumer side

When a consumer with `read_committed` encounters an ABORT marker while fetching:

```
Consumer fetches offsets 100–120:
  offset 100: record (committed, non-transactional) ← returned to app
  offset 101: record (part of txn PID=1001)         ← buffered
  offset 102: record (part of txn PID=1001)         ← buffered
  offset 103: ABORT marker (txn PID=1001)           ← signals: discard buffered records
  offset 104: record (committed, non-transactional) ← returned to app
  
Consumer delivers to application: [offset 100, offset 104]
Records 101, 102 are silently dropped — never seen by application code
```

The ABORT marker and transactional records remain physically in the log forever. They are filtered by the consumer library, not deleted from the broker.

### The LSO blocking problem

A long-running open transaction blocks all `read_committed` consumers on every partition the transaction has written to:

```
Transaction opens at offset 1000
Transaction is open for 30 seconds
During those 30 seconds, 50,000 new records arrive (offsets 1001–51000)

read_committed consumer: stuck at offset 999
                         cannot read any of the 50,000 new records
                         because LSO = 999 (blocked by open transaction at 1000)

read_uncommitted consumer: reads all 50,000 records normally
```

This is why `transaction.timeout.ms` (default 60s) is critical — it auto-aborts transactions that take too long, unblocking LSO.

---

## 8. The Read-Process-Write Pattern (EOS)

The primary use case for Kafka transactions is **exactly-once stream processing**: consume from input topic → process → produce to output topic → advance consumer position, all atomically.

### The pattern explained

```java
consumer.subscribe(List.of("input-topic"));

while (running) {
    ConsumerRecords<String, Order> records = consumer.poll(Duration.ofMillis(100));
    if (records.isEmpty()) continue;

    producer.beginTransaction();
    try {
        // 1. Process and produce output
        for (ConsumerRecord<String, Order> record : records) {
            ProcessedOrder result = processOrder(record.value());
            producer.send(new ProducerRecord<>("output-topic", record.key(), result));
        }

        // 2. Atomically commit input offsets as part of the same transaction
        Map<TopicPartition, OffsetAndMetadata> offsets = buildOffsets(records);
        producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());

        // 3. Commit — both output records and offset commit become visible atomically
        producer.commitTransaction();

    } catch (ProducerFencedException e) {
        producer.close();
        throw e; // fatal — this instance is a zombie
    } catch (KafkaException e) {
        // Abort — output records invisible, offset NOT advanced
        // Consumer will re-read from last committed position on next poll
        producer.abortTransaction();
    }
}
```

### Why `consumer.groupMetadata()` instead of just group ID

`sendOffsetsToTransaction` takes `ConsumerGroupMetadata` (not just `group.id`) because it needs the consumer's **generation ID** and **member ID** — which change on every rebalance. This prevents a stale offset commit from a pre-rebalance consumer from being applied after a rebalance.

### What "exactly-once" actually means

Exactly-once in Kafka means:
- Each input record produces **exactly one output record** — no duplicates, no drops
- Within the Kafka system (input topic → processing → output topic)

It does **not** mean:
- Exactly-once with respect to external systems (databases, REST APIs)
- Exactly-once if your processing logic throws an uncaught exception after producing but before calling `commitTransaction()`

### Why you must NOT manually call commitSync()

In a transactional consumer, the offset is committed via `sendOffsetsToTransaction`. Manually calling `consumer.commitSync()` bypasses the transaction mechanism — the offset commit is no longer atomic with the produce. This creates exactly the duplicate-processing scenario transactions are meant to prevent.

```java
// WRONG — breaks EOS
producer.commitTransaction();
consumer.commitSync(); // ← redundant AND dangerous

// CORRECT — offset committed inside transaction
producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
producer.commitTransaction(); // both output AND offset commit atomically
```

---

## 9. Complete Java Implementation

### Full transactional producer + consumer (EOS)

```java
@Component
public class ExactlyOnceProcessor {

    private final KafkaProducer<String, String> producer;
    private final KafkaConsumer<String, String> consumer;
    private volatile boolean running = true;

    public ExactlyOnceProcessor(KafkaProperties kafkaProps) {
        // Producer setup
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
            "broker1:9092,broker2:9092,broker3:9092");
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
            StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            StringSerializer.class.getName());
        // Required for transactions
        producerProps.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG,
            "order-processor-" + System.getenv("POD_NAME")); // stable unique ID
        // Auto-configured by transactional.id:
        // enable.idempotence = true
        // acks = all
        // retries = MAX_INT
        producerProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        producerProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 131072);
        producerProps.put(ProducerConfig.LINGER_MS_CONFIG, 5);

        this.producer = new KafkaProducer<>(producerProps);
        this.producer.initTransactions(); // blocking — must complete before use

        // Consumer setup
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            "broker1:9092,broker2:9092,broker3:9092");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "order-processor-group");
        // Critical for EOS consumers:
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerProps.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        this.consumer = new KafkaConsumer<>(consumerProps);
        this.consumer.subscribe(List.of("orders-input"));
    }

    public void processLoop() {
        try {
            while (running) {
                ConsumerRecords<String, String> records =
                    consumer.poll(Duration.ofMillis(100));

                if (records.isEmpty()) continue;

                producer.beginTransaction();
                try {
                    // Process records and produce output
                    for (ConsumerRecord<String, String> record : records) {
                        String transformed = transform(record.value());
                        producer.send(
                            new ProducerRecord<>("orders-output", record.key(), transformed),
                            (metadata, exception) -> {
                                if (exception != null) {
                                    // Send failed — transaction will be aborted
                                    log.error("Send failed", exception);
                                }
                            }
                        );
                    }

                    // Build offsets map: partition → (lastOffset + 1)
                    Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
                    for (TopicPartition partition : records.partitions()) {
                        List<ConsumerRecord<String, String>> partRecs =
                            records.records(partition);
                        long lastOffset =
                            partRecs.get(partRecs.size() - 1).offset();
                        offsets.put(partition, new OffsetAndMetadata(lastOffset + 1));
                    }

                    // Atomic: output records + offset commit in one transaction
                    producer.sendOffsetsToTransaction(
                        offsets,
                        consumer.groupMetadata() // NOT just group.id — needs generation
                    );

                    // Commit — all-or-nothing
                    producer.commitTransaction();
                    log.debug("Transaction committed for {} records", records.count());

                } catch (ProducerFencedException | InvalidProducerEpochException e) {
                    // Fatal — this instance is a zombie, another took over
                    log.error("Producer fenced — this instance is a zombie. Shutting down.");
                    producer.close();
                    throw new RuntimeException("Producer fenced", e);

                } catch (KafkaException e) {
                    // Retriable — abort and the consumer will re-read from last position
                    log.warn("Transaction failed, aborting: {}", e.getMessage());
                    try {
                        producer.abortTransaction();
                    } catch (Exception abortEx) {
                        log.error("Abort failed", abortEx);
                        // Producer is in unknown state — close and restart
                        throw new RuntimeException("Abort failed", abortEx);
                    }
                    // Don't commit offsets — consumer will re-read on next poll
                }
            }
        } finally {
            producer.close();
            consumer.close();
        }
    }

    private String transform(String input) {
        // Your business logic here
        return input.toUpperCase();
    }
}
```

### Kafka Streams — EOS built-in

If you are using Kafka Streams, exactly-once is managed for you via configuration:

```java
Properties streamsProps = new Properties();
streamsProps.put(StreamsConfig.APPLICATION_ID_CONFIG, "order-processor");
streamsProps.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "broker1:9092");
// Enable exactly-once (v2 is the modern version, preferred since Kafka 2.6)
streamsProps.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG,
    StreamsConfig.EXACTLY_ONCE_V2);

// Kafka Streams handles:
// - transactional.id assignment (applicationId + task ID)
// - beginTransaction() / commitTransaction() per commit interval
// - sendOffsetsToTransaction() automatically
// - zombie fencing via epoch management

StreamsBuilder builder = new StreamsBuilder();
builder.stream("orders-input")
    .mapValues(value -> value.toString().toUpperCase())
    .to("orders-output");

KafkaStreams streams = new KafkaStreams(builder.build(), streamsProps);
streams.start();
```

`EXACTLY_ONCE_V2` (introduced in Kafka 2.6) uses one producer per Kafka Streams task (rather than one per thread in the older `EXACTLY_ONCE` setting), significantly reducing the number of transactional producers needed.

### Transactional producer only (multi-partition atomic write)

```java
// Atomic write to multiple partitions — useful for fan-out patterns
producer.initTransactions();

producer.beginTransaction();
try {
    // Write to multiple partitions atomically
    producer.send(new ProducerRecord<>("orders", orderId, orderJson));
    producer.send(new ProducerRecord<>("audit-log", orderId, auditJson));
    producer.send(new ProducerRecord<>("inventory-reservations", itemId, reserveJson));

    // All three either commit together or none do
    producer.commitTransaction();

} catch (KafkaException e) {
    producer.abortTransaction();
    throw e;
}
```

---

## 10. Transaction Configuration Reference

### Producer Configuration

| Property | Default | Required for Transactions | Notes |
|---|---|---|---|
| `transactional.id` | `null` | **Yes** | Unique stable ID per logical producer |
| `enable.idempotence` | `true` | Auto-set | Auto-configured by `transactional.id` |
| `acks` | `all` | Auto-set | Auto-configured by `transactional.id` |
| `retries` | `MAX_INT` | Auto-set | Auto-configured by `transactional.id` |
| `max.in.flight.requests.per.connection` | `5` | Auto-set | Auto-configured |
| `transaction.timeout.ms` | `60000` | Configure | Max time for one transaction |

```java
// Only these two are required — rest auto-configure
props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "my-processor-0");
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true); // redundant but explicit
```

---

### `transaction.timeout.ms`
| | |
|---|---|
| Default | `60000` (60 seconds) |
| Production | `30000–120000` depending on processing time |

Maximum time between `beginTransaction()` and `commitTransaction()` / `abortTransaction()`. If the transaction is not resolved within this time, the Transaction Coordinator **automatically aborts it**.

This is critical for `read_committed` consumers — a stuck open transaction blocks LSO from advancing. Set this lower than your SLA for consumer freshness.

```java
props.put(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, 30000); // 30 seconds
```

---

### `transactional.id.expiration.ms`
| | |
|---|---|
| Default | `604800000` (7 days) |
| Scope | Broker (`server.properties`) |

How long the Transaction Coordinator keeps a `transactional.id`'s state after it was last used. After expiry, the PID and epoch are reset — the next `initTransactions()` gets a fresh PID with epoch=0. Zombie fencing is only guaranteed for `transactional.id`s that haven't expired.

For batch producers that run less frequently than once per week: increase this value.

```properties
# server.properties
transactional.id.expiration.ms=2592000000  # 30 days
```

---

### `transaction.state.log.num.partitions`
| | |
|---|---|
| Default | `50` |
| Scope | Broker |

Partitions in the `__transaction_state` topic. More partitions = better distribution of Transaction Coordinator load for clusters with many `transactional.id`s. Cannot change after cluster creation.

---

### `transaction.state.log.replication.factor`
| | |
|---|---|
| Default | `3` |
| Scope | Broker |

Replication factor for `__transaction_state`. Must be ≥ `transaction.state.log.min.isr` (default 2). If this topic loses quorum, transactions cannot proceed — critical for production.

---

### Consumer Configuration for Transactions

| Property | Required | Value |
|---|---|---|
| `isolation.level` | **Yes** | `read_committed` |
| `enable.auto.commit` | **Yes** | `false` |
| `group.id` | **Yes** | Your consumer group |

---

## 11. The `__transaction_state` Topic

The internal topic that persists all transaction state. Understanding it helps with debugging.

### Structure

```
Topic: __transaction_state
Partitions: 50 (by default)
Replication factor: 3
Cleanup policy: compact
```

### Key format (per record)

```
Key:   transactional.id (string)
Value: TransactionMetadata {
    producerId:       long,
    producerEpoch:    short,
    txnTimeoutMs:     int,
    state:            Enum (Empty|Ongoing|PrepareCommit|PrepareAbort|CompleteCommit|CompleteAbort|Dead),
    topicPartitions:  Set<TopicPartition>,
    txnStartTimestamp: long,
    txnLastUpdateTimestamp: long
}
```

### Viewing transaction state (debugging)

```bash
# View raw __transaction_state contents
kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic __transaction_state \
  --formatter "kafka.coordinator.transaction.TransactionLog\$TransactionLogMessageFormatter" \
  --from-beginning

# Output example:
# transactional.id=order-processor-0::TransactionMetadata(
#   transactionalId=order-processor-0,
#   producerId=5001,
#   producerEpoch=3,
#   txnTimeoutMs=60000,
#   state=CompleteCommit,
#   topicPartitions=[orders-input-0, orders-output-0],
#   txnStartTimestamp=1706789100000,
#   txnLastUpdateTimestamp=1706789101234
# )
```

---

## 12. Transactions vs External Systems

### The fundamental limitation

Kafka transactions provide atomicity **within Kafka only**. The transaction has no knowledge of:
- PostgreSQL / MySQL / any database
- REST API calls
- Redis / Elasticsearch / any external store
- Filesystem operations

### The dual-write problem

```
producer.beginTransaction();
producer.send(outputTopic, record);
jdbcTemplate.update("INSERT INTO orders VALUES (?)", order); // ← NOT part of transaction
producer.commitTransaction();

// If Kafka commit succeeds but DB insert had already failed:
// → Kafka has the record, DB doesn't. Inconsistent.

// If DB insert succeeds but Kafka commit fails:
// → DB has the record, Kafka output topic doesn't. Inconsistent.
```

### Solution 1: Outbox Pattern (Recommended)

Keep Kafka as the only external write target. Write business data AND event to the **same database in a single DB transaction**, then publish from DB to Kafka via a separate process:

```sql
-- Single DB transaction (atomic by DB)
BEGIN;
INSERT INTO orders (id, status) VALUES (?, 'PENDING');
INSERT INTO outbox (topic, key, payload, created_at)
  VALUES ('orders', ?, ?, NOW());
COMMIT;
```

```java
// Separate outbox publisher (Debezium CDC or polling)
// Reads outbox table → publishes to Kafka → marks as published
// Kafka publish is idempotent (use idempotent producer or upsert by offset)
```

This separates the Kafka interaction entirely from the core business transaction.

### Solution 2: Idempotent Writes to External System

Use the Kafka offset as an idempotency key for external writes:

```java
// PostgreSQL with idempotency key
for (ConsumerRecord<String, Order> record : records) {
    String idempotencyKey = record.topic() + ":" +
                            record.partition() + ":" +
                            record.offset();

    jdbcTemplate.update("""
        INSERT INTO processed_orders (idempotency_key, order_id, data)
        VALUES (?, ?, ?)
        ON CONFLICT (idempotency_key) DO NOTHING
    """, idempotencyKey, record.key(), record.value());
}
// After successful DB writes:
consumer.commitSync(buildOffsets(records));
```

A crash and replay will retry the same insert — the `ON CONFLICT DO NOTHING` makes it safe. This achieves exactly-once semantics for DB writes even without Kafka transactions.

### Solution 3: Store Offsets in the Database

The most robust pattern — track Kafka offsets inside the same DB transaction as business data:

```java
// Both business data and offset tracked in same DB transaction
try (Connection conn = dataSource.getConnection()) {
    conn.setAutoCommit(false);
    for (ConsumerRecord<String, Order> record : records) {
        insertOrderToDb(conn, record.value());
        updateKafkaOffset(conn, record.topic(), record.partition(),
                          record.offset() + 1);
    }
    conn.commit(); // atomic: business data + offset position
    // DON'T commit to __consumer_offsets — DB is the source of truth
}
// On startup: read offset from DB and consumer.seek() to it
```

---

## 13. Error Handling and Recovery

### Exception taxonomy

| Exception | Meaning | Action |
|---|---|---|
| `ProducerFencedException` | Another instance took over this `transactional.id` | Close producer, stop instance |
| `InvalidProducerEpochException` | Same as above (newer Kafka versions) | Close producer, stop instance |
| `TransactionAbortedException` | Transaction was auto-aborted (timeout or fencing) | Create new producer, restart transaction |
| `KafkaException` (generic) | Retriable error during produce/commit | `abortTransaction()`, retry |
| `TimeoutException` | Request timed out (broker slow/down) | `abortTransaction()`, retry with backoff |
| `CommitFailedException` | Offset commit failed (rebalance happened mid-transaction) | `abortTransaction()`, re-seek consumer |

### Complete error handling pattern

```java
private static final int MAX_RETRY_ATTEMPTS = 3;

public void processWithRetry(ConsumerRecords<String, String> records) {
    int attempts = 0;

    while (attempts < MAX_RETRY_ATTEMPTS) {
        try {
            producer.beginTransaction();

            for (ConsumerRecord<String, String> record : records) {
                producer.send(new ProducerRecord<>(
                    "output-topic", record.key(), transform(record.value())
                ));
            }

            producer.sendOffsetsToTransaction(
                buildOffsets(records), consumer.groupMetadata()
            );
            producer.commitTransaction();
            return; // success

        } catch (ProducerFencedException | InvalidProducerEpochException e) {
            // Non-retriable: this instance must stop
            log.error("Producer fenced. Shutting down.");
            producer.close();
            throw new FatalProducerException("Producer fenced", e);

        } catch (KafkaException e) {
            attempts++;
            log.warn("Transaction attempt {} failed: {}", attempts, e.getMessage());

            try {
                producer.abortTransaction();
            } catch (KafkaException abortEx) {
                log.error("Failed to abort transaction — producer in unknown state", abortEx);
                producer.close();
                // Recreate producer and reset
                initializeNewProducer();
                return; // will retry on next poll
            }

            if (attempts >= MAX_RETRY_ATTEMPTS) {
                log.error("Max retry attempts reached. Sending to DLQ.");
                sendToDLQ(records, e);
                // Advance consumer offset past these records
                consumer.commitSync(buildOffsets(records));
                return;
            }

            // Exponential backoff before retry
            sleepWithBackoff(attempts);
        }
    }
}
```

### Handling rebalance mid-transaction

When a rebalance occurs while a transaction is in progress, the `sendOffsetsToTransaction` call will fail because the consumer's generation ID has changed. The transaction must be aborted:

```java
consumer.subscribe(List.of("input-topic"), new ConsumerRebalanceListener() {
    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        // Partitions being taken away — abort any in-flight transaction
        if (transactionInFlight) {
            try {
                producer.abortTransaction();
            } catch (KafkaException e) {
                log.error("Failed to abort on rebalance", e);
            }
            transactionInFlight = false;
        }
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        // New partitions assigned — ready for new transactions
        log.info("Assigned partitions: {}", partitions);
    }
});
```

---

## 14. Performance Implications

### Overhead per transaction

Each transaction incurs:
1. `AddPartitionsToTxn` RPC to coordinator — one per new partition per transaction
2. `EndTxn` RPC — one per transaction commit/abort
3. `WriteTxnMarkers` RPC — one per partition per transaction (sent by coordinator, not producer)
4. One record written to `__transaction_state` per state transition (4–5 records per transaction)

For a transaction touching 5 partitions: approximately 11–13 extra internal records/RPCs.

### Throughput recommendations

**Too many small transactions** (one transaction per record):
```java
// BAD — enormous overhead
for (ConsumerRecord<?, ?> record : records) {
    producer.beginTransaction();
    producer.send(...);
    producer.commitTransaction(); // 10+ RPCs per record
}
```

**Optimal batching** (one transaction per poll batch):
```java
// GOOD — one transaction covers entire batch
producer.beginTransaction();
for (ConsumerRecord<?, ?> record : records) {
    producer.send(...); // all in same transaction
}
producer.commitTransaction(); // 10+ RPCs per 500 records
```

### Throughput impact numbers

Approximate overhead for EOS vs at-least-once on the same workload:

| Configuration | Relative Throughput |
|---|---|
| `enable.idempotence=false, acks=1` | 100% (baseline) |
| `enable.idempotence=true, acks=all` | ~95% |
| `acks=all` + transactions (optimal batching) | ~85–90% |
| `acks=all` + transactions (one txn per record) | ~30–50% |

The performance cost of transactions is **negligible with good batching** — the key is batching multiple records per transaction, not one transaction per record.

### Kafka Streams `EXACTLY_ONCE_V2` vs `EXACTLY_ONCE`

| Setting | Producers per app | Overhead |
|---|---|---|
| `EXACTLY_ONCE` (old) | One per Kafka Streams thread | High — many transactional producers |
| `EXACTLY_ONCE_V2` (recommended) | One per Kafka Streams task | Lower — fewer producers, better batching |

Use `EXACTLY_ONCE_V2` for all new deployments.

---

## 15. Common Pitfalls

### Pitfall 1: Using UUID as transactional.id

```java
// WRONG — new ID on every restart, no zombie fencing
props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, UUID.randomUUID().toString());
```

Each restart is treated as an entirely new producer. Old instances are never fenced. Zombie producers can coexist and produce duplicates.

---

### Pitfall 2: Catching ProducerFencedException and continuing

```java
// WRONG — continuing after being fenced produces duplicates
try {
    producer.commitTransaction();
} catch (ProducerFencedException e) {
    log.warn("Fenced, retrying...");
    producer.beginTransaction(); // This will also throw or be invalid
    // Duplicates from the zombie's uncommitted sends may now appear
}
```

`ProducerFencedException` means you **are the zombie**. Stop immediately.

---

### Pitfall 3: Manually calling consumer.commitSync() in EOS pipeline

```java
// WRONG — offsets committed outside the transaction
producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
producer.commitTransaction();
consumer.commitSync(); // ← redundant and breaks transactional semantics
```

The offset is already committed by `sendOffsetsToTransaction` + `commitTransaction()`. Adding `commitSync()` is a no-op at best and confusing at worst. Remove it.

---

### Pitfall 4: Long transactions blocking read_committed consumers

```java
producer.beginTransaction();
for (ConsumerRecord<?, ?> record : records) {
    producer.send(...);
    Thread.sleep(1000); // Processing each record takes 1 second
    // With 500 records: 500 seconds for one transaction
    // transaction.timeout.ms=60s → transaction auto-aborted after 60 seconds
    // read_committed consumers blocked for 60 seconds
}
```

Set `max.poll.records` low enough that one batch can be processed and committed within `transaction.timeout.ms`. Or increase `transaction.timeout.ms`.

---

### Pitfall 5: Not handling `TransactionAbortedException` on flush

`producer.flush()` inside a transaction can throw `TransactionAbortedException` if the transaction was auto-aborted (timeout). Always wrap flush in error handling:

```java
producer.beginTransaction();
for (ConsumerRecord<?, ?> r : records) {
    producer.send(...);
}
try {
    producer.flush(); // ← can throw TransactionAbortedException
    producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
    producer.commitTransaction();
} catch (TransactionAbortedException e) {
    // Transaction was already aborted (timeout, fencing)
    // Do NOT call abortTransaction() — it's already aborted
    log.warn("Transaction auto-aborted, will retry");
}
```

---

### Pitfall 6: read_uncommitted consumer reading from transactional topics

```java
// Consumer set to read_uncommitted on a transactional topic
props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_uncommitted"); // default

// This consumer will receive:
// - Records from committed transactions ✓
// - Records from ABORTED transactions ✗ (should have been invisible)
// - Records from open/incomplete transactions ✗ (premature reads)
```

Always use `read_committed` when consuming from topics written by transactional producers.

---

## 16. Monitoring Transactions

### Key JMX Metrics

```
# Producer metrics
kafka.producer:type=producer-metrics,client-id=*
  txn-abort-rate          → Transactions aborted per second
  txn-commit-rate         → Transactions committed per second
  txn-begin-rate          → Transactions begun per second
  txn-send-offsets-rate   → sendOffsetsToTransaction() calls per second
  txn-init-time-ns-total  → Total time spent in initTransactions()
  txn-begin-time-ns-total → Total time per beginTransaction()
  txn-commit-time-ns-total → Total time per commitTransaction()
  txn-abort-time-ns-total → Total time per abortTransaction()

# Broker metrics (Transaction Coordinator)
kafka.coordinator.transaction:type=TransactionCoordinator-AllMetrics,name=*
  TransactionsActiveCount          → Currently open transactions
  TransactionsLogAppendRateAndTimeMs → Rate of writes to __transaction_state
```

### Prometheus alerting rules

```yaml
groups:
  - name: kafka_transactions
    rules:

    # Alert on high transaction abort rate (indicates processing errors)
    - alert: KafkaHighTransactionAbortRate
      expr: rate(kafka_producer_txn_abort_total[5m]) > 0.1
      for: 5m
      annotations:
        summary: "Producer {{ $labels.client_id }} aborting > 0.1 transactions/sec"

    # Alert on long transaction duration (risks blocking LSO)
    - alert: KafkaLongTransactionDuration
      expr: kafka_producer_txn_commit_time_ns_total / 1e9 > 30
      for: 1m
      annotations:
        summary: "Transaction commit taking > 30 seconds"

    # Alert on stalled LSO (sign of stuck open transaction)
    - alert: KafkaLSONotAdvancing
      expr: delta(kafka_log_log_start_offset[5m]) == 0 and kafka_log_log_end_offset > 0
      for: 10m
      annotations:
        summary: "LSO not advancing on {{ $labels.topic }}-{{ $labels.partition }}"
```

### Checking for stuck transactions

```bash
# Check for long-running transactions via __transaction_state topic
kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic __transaction_state \
  --formatter "kafka.coordinator.transaction.TransactionLog\$TransactionLogMessageFormatter" \
  --from-beginning 2>/dev/null | \
  grep "Ongoing" | \
  awk -F'txnStartTimestamp=' '{print $2}' | \
  awk -F',' '{if (systime()*1000 - $1 > 60000) print "STUCK: " $0}'
```

---

## 17. Quick Reference

### Transaction API methods

| Method | Blocking | Network Call | When to Use |
|---|---|---|---|
| `initTransactions()` | Yes | Yes | Once on startup |
| `beginTransaction()` | No | No | Before each transaction |
| `send()` | No (async) | Yes (batched) | Add record to transaction |
| `sendOffsetsToTransaction()` | Yes | Yes | In EOS patterns only |
| `commitTransaction()` | Yes | Yes | At end of successful processing |
| `abortTransaction()` | Yes | Yes | On processing failure |

### Configuration checklist

```
Producer:
  ✓ transactional.id = unique, stable per instance
  ✓ transaction.timeout.ms = less than your LSO freshness SLA

Consumer (if EOS):
  ✓ isolation.level = read_committed
  ✓ enable.auto.commit = false
  ✗ Never call consumer.commitSync() — offset committed via sendOffsetsToTransaction

Broker:
  ✓ transaction.state.log.replication.factor = 3
  ✓ transaction.state.log.min.isr = 2
  ✓ transactional.id.expiration.ms >= interval between producer runs
```

### Delivery guarantee comparison

| Pattern | Guarantee | Use When |
|---|---|---|
| `acks=0` | At-most-once | Metrics, logs (loss acceptable) |
| `acks=1` | At-most-once | Non-critical low-latency |
| `acks=all`, no transactions | At-least-once | Most production workloads |
| `enable.idempotence=true` | Exactly-once per partition per session | Single producer, no cross-partition atomicity needed |
| Kafka Transactions | Exactly-once within Kafka | Read-process-write stream processing |
| Transactions + Outbox pattern | Exactly-once including external DB | Microservices with DB + Kafka consistency |

### The golden rule

> Transactions guarantee atomicity **within Kafka**. For external system consistency, use the Outbox pattern or idempotent writes keyed by Kafka offset. There is no distributed transaction that spans Kafka and a database.
