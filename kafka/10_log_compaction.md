# 10 — Log Compaction

> How Kafka retains only the latest value per key, enabling infinite retention of current state without unbounded disk growth.

---

## What Log Compaction Is

Log compaction is a retention policy (`cleanup.policy=compact`) that replaces time/size-based deletion with key-based deduplication. Instead of deleting old data by age or size, it keeps **only the latest value for each key**.

```
Topic: account-balances (cleanup.policy=compact)

Messages over time:
  offset 0:  key=user-1, value={balance:100}
  offset 1:  key=user-2, value={balance:200}
  offset 2:  key=user-1, value={balance:150}   ← newer for user-1
  offset 3:  key=user-3, value={balance:300}
  offset 4:  key=user-2, value={balance:250}   ← newer for user-2
  offset 5:  key=user-1, value=null             ← tombstone: delete user-1
  offset 6:  key=user-3, value={balance:350}   ← newer for user-3
  offset 7:  key=user-2, value=null             ← tombstone: delete user-2
  offset 8:  key=user-4, value={balance:400}
  offset 9:  key=user-4, value={balance:450}   ← newer for user-4

After compaction (tombstones retained temporarily):
  offset 5:  key=user-1, value=null   ← tombstone kept
  offset 6:  key=user-3, value={balance:350}
  offset 7:  key=user-2, value=null   ← tombstone kept
  offset 9:  key=user-4, value={balance:450}

After delete.retention.ms (tombstones removed):
  offset 6:  key=user-3, value={balance:350}
  offset 9:  key=user-4, value={balance:450}
```

**Compaction guarantees:**
- At least the most recent value for every key is **always** retained
- Message ordering within a partition is preserved
- Consumer offsets are not invalidated (compacted-away records are simply skipped)

---

## When to Use Compaction

| Use Case | Why Compaction |
|---|---|
| Kafka Streams KTable changelogs | Consumer needs current state, not full history |
| CDC (Change Data Capture) topics | Database current state distributed via Kafka |
| Configuration distribution | Services need current config, not all history |
| User profile/session topics | Latest user state per user ID |
| Event sourcing snapshots | Materialise current aggregate state |
| Reference data topics | Latest price, product catalog, etc. |

**Do NOT use compaction for:**
- Audit logs (you need full history)
- Time-series analytics (each event matters independently)
- Topics where null keys are common (compaction requires keys)
- Topics where message ordering across keys must be preserved

---

## The Log Cleaner Architecture

### Thread Pool

```properties
log.cleaner.threads=1   # number of cleaner threads (increase to 2-4 for heavy compaction)
```

Each `LogCleaner` thread runs independently and picks partitions to clean based on the **dirty ratio**.

### Clean vs Dirty Partition

A compacted partition's log is divided into two sections:

```
Partition log:
  ┌─────────────────────────────────────────────────────────────────┐
  │  CLEAN portion              │  DIRTY portion                    │
  │  Already compacted          │  New records not yet compacted    │
  │  ≤1 value per key           │  May have multiple values per key │
  │  offsets: 0 → 50000         │  offsets: 50001 → 99999 (active) │
  └─────────────────────────────────────────────────────────────────┘
                                ↑
                        cleaner checkpoint

Active segment is NEVER compacted.
```

**Dirty ratio** = `dirty_bytes / total_log_bytes`

Compaction triggered when: `dirty_ratio >= min.cleanable.dirty.ratio` (default 0.5 = 50%)

### The Cleaning Process

**Step 1: Build offset map (in-memory dedup table)**

```java
// Cleaner reads through the dirty portion
// Builds map: key_hash → (offset, epoch)
Map<ByteBuffer, OffsetMap.Entry> offsetMap = new HashMap<>();

for (RecordBatch batch : dirtyPortion) {
    for (Record record : batch) {
        if (record.hasKey()) {
            offsetMap.put(record.key(), new Entry(record.offset(), batch.producerEpoch()));
        }
    }
}
// Result: for each key, the highest offset seen in the dirty portion
```

Memory bounded by `log.cleaner.dedupe.buffer.size` (default 128 MB). If the offset map doesn't fit in memory, the cleaner processes the dirty portion in multiple passes.

**Step 2: Rewrite segments**

```java
// For each record in the partition (clean + dirty):
for (Record record : allRecords) {
    boolean keep;
    if (!record.hasKey()) {
        keep = true;  // keyless records always kept
    } else if (offsetMap.containsKey(record.key())) {
        // Record is in dirty portion
        keep = (offsetMap.get(record.key()).offset == record.offset());
        // Only keep if this IS the latest value for this key
    } else {
        // Record is in clean portion and not in offset map
        // (key doesn't appear in dirty portion = already latest)
        keep = true;
    }
    
    if (keep) {
        newSegment.append(record);
    }
}
```

**Step 3: Replace old segments**

```
1. Write cleaned records to temporary new segments
2. Rename old segments: .log → .log.deleted
3. Atomic swap: temporary segments become the new segment files
4. After log.segment.delete.delay.ms (60s): delete .log.deleted files
```

---

## Tombstones — Signaling Key Deletion

A **tombstone** is a record with `value=null`. It signals that a key should be deleted from the log.

```java
// Produce a tombstone for user-1
producer.send(new ProducerRecord<>("account-balances", "user-1", null));
//                                                              ^^^^ null value
```

### Tombstone Lifecycle

```
t=0    Tombstone produced: key=user-1, value=null, offset=50004

t=0    Compaction runs:
         key=user-1 has highest offset = 50004 (tombstone)
         All prior records for user-1 DELETED
         Tombstone RETAINED (consumers need to see it)

t=0    Consumers with read_committed:
         See offset 50004: {key=user-1, value=null}
         Application logic: "user-1 was deleted"

t=24h  delete.retention.ms expires
         Next compaction pass:
         Tombstone at offset 50004 NOT copied to new segment
         user-1 key completely gone from the log

t=24h+ New consumer joining from earliest:
         user-1 is absent — the deletion was propagated
```

**Why keep tombstones for 24 hours?**

Without the delay, consumers that are up to 24 hours behind might see:
1. user-1's latest value (before deletion)
2. No tombstone (already removed by compaction)
3. Consumer never knows user-1 was deleted → stale state

The `delete.retention.ms` window gives slow consumers time to observe the deletion before the tombstone is removed.

---

## Compaction Configuration

### Per-Topic Configuration

```bash
# Create compacted topic
kafka-topics.sh --bootstrap-server broker:9092 \
  --create --topic account-balances \
  --config cleanup.policy=compact \
  --config min.cleanable.dirty.ratio=0.1 \
  --config delete.retention.ms=86400000 \  # 24h tombstone retention
  --config segment.bytes=268435456 \         # 256 MB (smaller = more frequent compaction)
  --config min.compaction.lag.ms=0 \         # compact as soon as possible
  --config max.compaction.lag.ms=86400000    # force compact within 24h

# Modify existing topic
kafka-configs.sh --bootstrap-server broker:9092 \
  --entity-type topics --entity-name account-balances \
  --alter --add-config min.cleanable.dirty.ratio=0.1
```

### Compaction + Deletion Combined

```bash
# Keep latest value per key AND cap total disk size
kafka-topics.sh --bootstrap-server broker:9092 \
  --create --topic user-events \
  --config cleanup.policy=delete,compact \
  --config retention.ms=604800000 \          # 7 days max
  --config min.cleanable.dirty.ratio=0.1
```

### Broker-Level Compaction Settings

```properties
# Cleaner threads (increase for heavy compaction workloads)
log.cleaner.threads=1

# Memory for offset map (shared across all cleaner threads)
log.cleaner.dedupe.buffer.size=134217728   # 128 MB

# I/O buffer per cleaner thread
log.cleaner.io.buffer.size=524288          # 512 KB

# Throttle compaction I/O (MB/s) — prevent starving other I/O
log.cleaner.io.max.bytes.per.second=1.7976931348623157E308  # unlimited by default

# How often to check if compaction is needed
log.cleaner.backoff.ms=15000               # 15s

# Minimum ratio of dirty / total before cleaning starts
log.cleaner.min.cleanable.ratio=0.5        # 50%
```

---

## Compaction Guarantees

### 1. Latest Value Always Retained
The cleaner guarantees that for every key present in the log, the **most recent value** is retained. A new consumer can always get the current state by reading from the beginning of a compacted topic.

### 2. Ordering Preserved Within Key
Records for the same key appear in offset order after compaction. The relative order of different keys may change as intermediate records are removed.

### 3. Consumer Offsets Valid
Compaction never removes records whose offset is referenced by a committed consumer offset. Committed offsets remain valid — consumers simply skip over the positions where records were compacted away.

### 4. Dirty Portion Always Available
The dirty (uncompacted) section of the log is always fully accessible to consumers. Only the clean section has been compacted.

---

## Compaction and Kafka Streams

Kafka Streams relies heavily on log compaction for its state management.

### KTable Changelog Topics

```java
// Kafka Streams KTable backed by changelog topic (auto-compacted)
KTable<String, AccountBalance> balances = builder.table(
    "account-balances",   // compacted topic
    Materialized.<String, AccountBalance, KeyValueStore<Bytes, byte[]>>as("balances-store")
);

// Internally:
// 1. Kafka Streams creates a RocksDB state store
// 2. Changelog topic (cleanup.policy=compact) mirrors the state store
// 3. On crash recovery: Kafka Streams replays the compact changelog
//    → Only needs to process ONE record per key (the latest)
//    → Much faster than replaying a full event history
```

### Recovery Speed Improvement

```
Without compaction (delete policy changelog):
  100M records in changelog
  90% are superseded updates
  Recovery: replay all 100M records
  Time: 10 minutes

With compaction (compact policy changelog):
  10M records in changelog (only latest per key)
  Recovery: replay 10M records
  Time: 1 minute
  Improvement: 10x faster recovery
```

---

## Monitoring Compaction

```
# JMX metrics
kafka.log:type=LogCleanerManager,name=max-dirty-percent
  → Highest dirty ratio across all compacted partitions
  → If consistently > 80%: cleaner is falling behind

kafka.log:type=LogCleaner,name=cleaner-recopy-percent
  → Percentage of data being re-copied (higher = more redundant work)

kafka.log:type=LogCleaner,name=max-clean-time-secs
  → Time for last compaction cycle (spike = slow)

kafka.log:type=LogCleaner,name=max-compaction-delay-secs
  → Time since last compaction for slowest partition
```

### Compaction Health Alerts

```yaml
- alert: KafkaCompactionFallingBehind
  expr: kafka_log_cleaner_manager_max_dirty_percent > 0.8
  for: 15m
  annotations:
    summary: "Log cleaner cannot keep up — dirty ratio > 80%"

- alert: KafkaCompactionLagHigh
  expr: kafka_log_cleaner_max_compaction_delay_secs > 86400
  for: 5m
  annotations:
    summary: "A compacted partition hasn't been cleaned in > 24 hours"
```

---

## Summary

| Feature | Detail |
|---|---|
| Policy | `cleanup.policy=compact` |
| What it keeps | Latest value per key |
| What it removes | Older values for the same key |
| Tombstones | `value=null` — marks key for deletion; retained for `delete.retention.ms` |
| Trigger | `dirty_bytes / total_bytes >= min.cleanable.dirty.ratio` |
| Active segment | Never compacted |
| Consumer offsets | Always valid — compacted records skipped transparently |
| Primary use cases | KTable changelogs, CDC, reference data, current state snapshots |
