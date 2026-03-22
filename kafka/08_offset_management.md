# 08 — Offset Management

> How Kafka tracks consumer positions — committed offsets, the __consumer_offsets topic, commit strategies, and recovery semantics.

---

## What an Offset Is

An **offset** is a monotonically increasing integer that uniquely identifies every message in a partition. Offsets start at 0 and never repeat or decrease.

```
Partition "orders-0":
  offset 0: {key:"order-A", value:{amount:50}}
  offset 1: {key:"order-B", value:{amount:75}}
  offset 2: {key:"order-A", value:{amount:100}}
  offset 3: {key:"order-C", value:{amount:25}}
  offset 4: (next message will be here)
```

**Key offset concepts:**

| Term | Definition |
|---|---|
| Log Start Offset | Earliest available offset (advances as old segments are deleted) |
| Log End Offset (LEO) | Next offset to write (leader's is always highest) |
| High Watermark (HW) | min(LEO across ISR) — consumer visibility boundary |
| Last Stable Offset (LSO) | HW bounded by open transactions |
| Committed Offset | Consumer group's durable checkpoint |
| Current Position | Consumer's in-memory fetch position (lost on restart) |

---

## The Committed Offset

The **committed offset** represents the **next offset to consume** — one past the last successfully processed message.

```
Consumer processes offset 45899 successfully:
  Commit: offset 45900  (= 45899 + 1 = "next to read")
  
On restart: consumer reads committed offset 45900
            resumes from offset 45900 (does not re-read 45899)
```

**If you commit the wrong value:**
```
Committed offset 45899 (the record just processed, not +1):
  On restart: consumer reads 45899 again → DUPLICATE

Committed offset 45901 (skipped one):
  On restart: consumer starts at 45901 → offset 45900 SKIPPED FOREVER
```

Always commit `record.offset() + 1`.

---

## The __consumer_offsets Topic

Kafka's internal storage for all committed offsets and consumer group metadata.

```
Topic name: __consumer_offsets
Partitions: 50  (offsets.topic.num.partitions — set before first use, cannot change)
Replication factor: 3  (offsets.topic.replication.factor)
Min ISR: 2  (offsets.topic.min.isr)
Cleanup policy: compact  (keeps latest offset per key)
```

### Record Format

**Offset commit records** (written by OffsetCommitRequest):
```
Key (identifies the entry):
  version:   int16
  group:     string  ← "order-service-prod"
  topic:     string  ← "orders"
  partition: int32   ← 2

Value (the committed state):
  version:         int16
  offset:          int64  ← 45900 (next to read)
  leaderEpoch:     int32  ← 3 (partition leader epoch at commit time)
  metadata:        string ← "" (user-defined metadata, usually empty)
  commitTimestamp: int64  ← 1706789123456
  expireTimestamp: int64  ← -1 (use offsets.retention.minutes instead)
```

**Group metadata records** (written by JoinGroup/SyncGroup protocol):
```
Key:
  version: int16
  group:   string ← "order-service-prod"

Value:
  protocolType: "consumer"
  generation:   int32   ← current generation ID
  protocol:     string  ← "cooperative-sticky"
  leader:       string  ← member ID of current leader
  members: [{
    memberId:        string
    groupInstanceId: string  ← null if not static
    subscription:    bytes   ← serialized subscription info
    assignment:      bytes   ← serialized partition assignment
  }]
```

### How the Group Coordinator Finds It

```
Coordinator partition = abs(groupId.hashCode()) % offsets.topic.num.partitions

"order-service-prod".hashCode() = 1234567890 (example)
abs(1234567890) % 50 = 40

Leader of __consumer_offsets-40 = the Group Coordinator for "order-service-prod"
```

### Compaction of __consumer_offsets

Log compaction keeps only the **latest offset per key**:
```
Before compaction:
  (order-service, orders, 2) → offset=45000  [old]
  (order-service, orders, 2) → offset=45500  [old]
  (order-service, orders, 2) → offset=45900  [current]

After compaction:
  (order-service, orders, 2) → offset=45900  [only latest kept]
```

---

## auto.offset.reset

**Only applies when there is NO committed offset for a (group, partition) pair.**

This happens when:
- Consumer group is brand new (never committed to this partition)
- Group's committed offsets expired (`offsets.retention.minutes`, default 7 days)
- Committed offset is below Log Start Offset (retention deleted the segment)

```
auto.offset.reset=latest (default):
  Start at High Watermark (current end of log)
  Silently skips all historical messages
  Common cause of "missing data" for new consumer groups

auto.offset.reset=earliest:
  Start at Log Start Offset (oldest available message)
  Replays all retained history
  Dangerous on high-volume topics with years of data

auto.offset.reset=none:
  Throw NoOffsetForPartitionException
  Forces application to handle the "no prior offset" case explicitly
```

**If committed offsets exist, this setting is COMPLETELY IGNORED.**

**Best practice for new consumer groups:**
```bash
# Pre-set offsets BEFORE deploying the consumer group
kafka-consumer-groups.sh --bootstrap-server broker:9092 \
  --group order-service-prod \
  --topic orders \
  --reset-offsets --to-latest --execute

# Now auto.offset.reset is irrelevant — committed offsets exist
```

---

## enable.auto.commit — The Most Dangerous Default

```properties
enable.auto.commit=true  # DEFAULT — dangerous in production
auto.commit.interval.ms=5000  # fires every 5 seconds
```

**What actually happens:**
```
t=0  poll() returns records [offset 100, 101, 102, 103, 104]
     Auto-commit fires HERE (on poll() call, based on time)
     Committed offset = 105 (next after 104)
     
t=1  App processes offset 100 → DB write succeeds
t=2  App processes offset 101 → DB write succeeds
t=3  CRASH (OOM, SIGKILL, hardware failure)

t=4  Consumer restarts
     Committed offset = 105 (written before crash)
     Consumer reads from 105 → offsets 102, 103, 104 PERMANENTLY SKIPPED
     
This is at-most-once delivery — message loss is possible.
```

**Always set `enable.auto.commit=false` in production.**

---

## Commit Strategies

### Strategy 1: commitSync() — Blocking, Retrying

```java
// After processing each batch:
while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    
    for (ConsumerRecord<String, String> record : records) {
        processRecord(record);  // your business logic
    }
    
    consumer.commitSync();  // blocks until coordinator confirms
}
```

**Behavior:**
- Blocks the calling thread until `__consumer_offsets` confirms the commit
- Automatically retries on retriable errors (`COORDINATOR_NOT_AVAILABLE`, leader change)
- Throws `CommitFailedException` on non-retriable errors (rebalance happened)
- Use in: main loop, `onPartitionsRevoked()`, shutdown `finally` block

**Wire format:**
```
OffsetCommitRequest:
  groupId: "order-service-prod"
  generationId: 6    ← stale generationId → ILLEGAL_GENERATION error
  memberId: "consumer-abc-uuid"
  topics: [{
    name: "orders",
    partitions: [
      {partitionIndex:2, committedOffset:45900, committedLeaderEpoch:3, metadata:""}
    ]
  }]
```

### Strategy 2: commitAsync() — Non-Blocking

```java
while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    processRecords(records);
    
    consumer.commitAsync((offsets, exception) -> {
        if (exception != null) {
            // DO NOT RETRY HERE
            // A later commit may have already succeeded
            // Retrying older offset would ROLL BACK progress
            log.warn("Async commit failed: {}", exception.getMessage());
        }
    });
}
```

**Why NOT retry in the async callback:**
```
t=0  Commit offset 100 → in-flight
t=1  Commit offset 200 → in-flight
t=2  Commit 100 FAILS → you retry
t=3  Commit 200 SUCCEEDS → offset now at 200
t=4  Retry of commit 100 SUCCEEDS → offset ROLLED BACK to 100
     Consumer re-reads offsets 101-200 → DUPLICATE PROCESSING
```

### Strategy 3: The Safe Production Pattern

```java
try {
    while (running) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
        processRecords(records);
        consumer.commitAsync();  // fast, non-blocking in the main loop
    }
} catch (Exception e) {
    log.error("Fatal error in consumer loop", e);
} finally {
    try {
        consumer.commitSync();  // blocking, retrying final commit on shutdown
    } finally {
        consumer.close();
    }
}
```

### Strategy 4: Per-Partition Offset Commit

```java
Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();

for (TopicPartition partition : records.partitions()) {
    List<ConsumerRecord<String, String>> partRecords = records.records(partition);
    long lastOffset = partRecords.get(partRecords.size() - 1).offset();
    offsets.put(partition, new OffsetAndMetadata(lastOffset + 1));
}

consumer.commitSync(offsets);  // commit exact positions per partition
```

### Strategy 5: Offset in Database (Exactly-Once)

```java
try (Connection conn = dataSource.getConnection()) {
    conn.setAutoCommit(false);
    
    for (ConsumerRecord<String, String> record : records) {
        // Write business data
        insertOrder(conn, record.value());
        
        // Write Kafka offset IN SAME DB TRANSACTION
        upsertKafkaOffset(conn,
            "order-service-prod", record.topic(), record.partition(),
            record.offset() + 1
        );
    }
    
    conn.commit();  // atomic: business data + offset
    // DO NOT call consumer.commitSync() — DB is the source of truth
}

// On startup: read offset from DB, consumer.seek() to that position
consumer.subscribe(topics, new ConsumerRebalanceListener() {
    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        partitions.forEach(tp -> {
            Long offset = db.getOffset("order-service-prod", tp);
            if (offset != null) consumer.seek(tp, offset);
        });
    }
});
```

---

## consumer.seek()

Override the fetch position without committing.

```java
// Seek to specific offset
consumer.seek(new TopicPartition("orders", 2), 45000);

// Seek to beginning (oldest available)
consumer.seekToBeginning(consumer.assignment());

// Seek to end (latest, HW)
consumer.seekToEnd(consumer.assignment());
```

**MUST be called inside `onPartitionsAssigned()`** to persist across rebalances:
```java
consumer.subscribe(topics, new ConsumerRebalanceListener() {
    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        // Any seek called here is stable — re-applied after every rebalance
        partitions.forEach(tp -> {
            Long dbOffset = offsetRepository.get(tp);
            if (dbOffset != null) {
                consumer.seek(tp, dbOffset);
            }
        });
    }
});
```

A `seek()` called between `poll()` calls (but outside `onPartitionsAssigned()`) is overridden the next time a rebalance assigns the partition back.

---

## offsetsForTimes()

Find the first message at or after a target timestamp.

```java
// Find offset for "30 minutes ago" on all assigned partitions
Map<TopicPartition, Long> timestamps = consumer.assignment().stream()
    .collect(Collectors.toMap(
        tp -> tp,
        tp -> System.currentTimeMillis() - 30 * 60 * 1000
    ));

Map<TopicPartition, OffsetAndTimestamp> result = consumer.offsetsForTimes(timestamps);

result.forEach((tp, offsetAndTimestamp) -> {
    if (offsetAndTimestamp != null) {
        consumer.seek(tp, offsetAndTimestamp.offset());
    } else {
        // No message with that timestamp → seek to end
        consumer.seekToEnd(List.of(tp));
    }
});
```

**Internally uses `.timeindex`** — binary search to find the first entry with timestamp ≥ target, then resolves to the actual offset.

---

## Offset Expiry

Committed offsets expire if the consumer group is inactive for `offsets.retention.minutes` (default 10080 = 7 days).

```
t=0    Group "batch-job" commits offsets for all partitions
t=7d   offsets.retention.minutes fires
       Coordinator writes tombstone (value=null) for each offset record
       Next __consumer_offsets compaction removes the tombstones
t=7d+  "batch-job" starts, calls OffsetFetch → returns -1 (expired)
       auto.offset.reset applies (could replay months of data)
```

**Fix for infrequent consumers:**
```properties
# server.properties on broker
offsets.retention.minutes=43200  # 30 days
```

---

## Manual Offset Reset (CLI)

Used for disaster recovery, reprocessing, and initial consumer group setup.

```bash
# Consumer group must be STOPPED before reset

# Reset to latest
kafka-consumer-groups.sh --bootstrap-server broker:9092 \
  --group order-service-prod --topic orders \
  --reset-offsets --to-latest --execute

# Reset to earliest (replay all)
kafka-consumer-groups.sh --bootstrap-server broker:9092 \
  --group order-service-prod --topic orders \
  --reset-offsets --to-earliest --execute

# Reset to timestamp (replay from 2 hours ago)
kafka-consumer-groups.sh --bootstrap-server broker:9092 \
  --group order-service-prod --topic orders \
  --reset-offsets --to-datetime 2024-01-15T10:30:00.000 --execute

# Reset to specific offset
kafka-consumer-groups.sh --bootstrap-server broker:9092 \
  --group order-service-prod --topic orders:2 \
  --reset-offsets --to-offset 45000 --execute

# Shift by -1000 (replay last 1000 messages)
kafka-consumer-groups.sh --bootstrap-server broker:9092 \
  --group order-service-prod --topic orders \
  --reset-offsets --shift-by -1000 --execute

# Always dry-run first (remove --execute to preview)
```

---

## Inspecting Committed Offsets

```bash
# Describe consumer group (shows lag)
kafka-consumer-groups.sh --bootstrap-server broker:9092 \
  --describe --group order-service-prod

# Output:
# GROUP              TOPIC  PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
# order-service-prod orders 0          500000          500100          100
# order-service-prod orders 1          250000          250050          50

# View raw __consumer_offsets records
kafka-console-consumer.sh \
  --bootstrap-server broker:9092 \
  --topic __consumer_offsets \
  --formatter "kafka.coordinator.group.GroupMetadataManager\$OffsetsMessageFormatter" \
  --from-beginning 2>/dev/null
```

---

## Offset Commit Error Codes

| Error code | Meaning | Consumer action |
|---|---|---|
| 0 (NONE) | Committed successfully | Continue |
| ILLEGAL_GENERATION (22) | Rebalance happened, generation is stale | Accept — records will be redelivered |
| UNKNOWN_MEMBER_ID (25) | Session timed out, member removed | Rejoin group |
| REBALANCE_IN_PROGRESS (27) | Rebalance in progress | Complete rebalance first |
| COORDINATOR_LOAD_IN_PROGRESS (14) | Coordinator loading state | Retry after backoff |
| NOT_COORDINATOR (16) | Coordinator changed | FindCoordinator again |

---

## Summary

```
Offset flow:
  poll() → records with offsets
  process(records) → success
  commit(offset + 1) → OffsetCommitRequest → coordinator → __consumer_offsets
  
  Next restart:
  OffsetFetchRequest → coordinator → reads from __consumer_offsets
  consumer.seek(tp, committedOffset) → resumes from correct position

Delivery guarantee:
  Commit BEFORE processing → at-most-once (loss possible)
  Commit AFTER processing  → at-least-once (redelivery possible)
  Offset in same DB txn    → exactly-once (with external systems)
  sendOffsetsToTransaction → exactly-once (within Kafka)
```
