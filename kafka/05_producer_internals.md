# 05 — Producer Internals

> How KafkaProducer works from send() to broker ack — RecordAccumulator, Sender thread, partitioning, batching, idempotence, and transactions.

---

## High-Level Architecture

```
Application thread(s)                    Background Sender thread
─────────────────────                    ────────────────────────
producer.send(record)                    Drains accumulator
       │                                        │
  [Partitioner]                         Groups by broker
  [Serializer]                                  │
  [RecordAccumulator]  ←──────────────  [NetworkClient (NIO)]
         │                                      │
   Future<RecordMetadata>              ProduceRequest → Broker
   returned immediately                ProduceResponse ← Broker
                                       Complete Future / retry
```

Application threads **only** touch the accumulator — they never touch the network. The Sender thread is the **sole** network actor.

---

## Step 1: Instantiation — new KafkaProducer<>()

```java
Properties props = new Properties();
props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker1:9092,broker2:9092");
props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class.getName());
props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class.getName());
props.put(ProducerConfig.ACKS_CONFIG, "all");
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
props.put(ProducerConfig.BATCH_SIZE_CONFIG, 131072);  // 128 KB
props.put(ProducerConfig.LINGER_MS_CONFIG, 5);

KafkaProducer<String, String> producer = new KafkaProducer<>(props);
```

**What happens internally during construction:**

| Object created | Purpose |
|---|---|
| `Metadata` | Empty cluster topology cache |
| `RecordAccumulator` | In-memory buffer (all partitions) |
| `BufferPool` | Reusable ByteBuffer pool |
| `Sender` thread | Started as background daemon |
| `NetworkClient` | Java NIO selector for broker connections |
| `Serializer` instances | Key and value serializers, `configure()`d |
| `Partitioner` | Default: `DefaultPartitioner` (sticky) |
| `Interceptors` | If any `ProducerInterceptor` configured |

**Zero network calls** during construction.

---

## Step 2: Metadata Fetch

The first `send()` call needs to know which broker leads the target partition.

```
producer.send(new ProducerRecord<>("orders", "key-123", value))
  │
  ├─ Check Metadata cache for topic "orders"
  │   Cache is EMPTY (first call)
  │   Block calling thread for up to max.block.ms (60s default)
  │
  └─ Sender thread fires MetadataRequest to any bootstrap broker:

MetadataRequest:
  topics: ["orders"]
  allowAutoTopicCreation: false

MetadataResponse:
  brokers: [{id:0, host:"b0", port:9092}, {id:1,...}, {id:2,...}]
  topics: [{
    name: "orders",
    partitions: [
      {index:0, leaderId:0, replicas:[0,1,2], isr:[0,1,2]},
      {index:1, leaderId:1, replicas:[1,2,0], isr:[1,2,0]},
      {index:2, leaderId:2, replicas:[2,0,1], isr:[2,0,1]},
    ]
  }]
```

Metadata refreshes automatically every `metadata.max.age.ms` (5 min) and immediately on `LEADER_NOT_AVAILABLE` error.

---

## Step 3: Partitioning

The partitioner assigns the record to a specific partition.

### Key-Based Partitioning (murmur2 hash)

```java
// When record has a non-null key
int partition = Utils.toPositive(Utils.murmur2(keyBytes)) % numPartitions;
```

**Same key ALWAYS → same partition** (ordering within a key is guaranteed).

```
key="order-123" → murmur2 → 2345678901 → % 6 = 3 → partition 3
key="order-123" → murmur2 → 2345678901 → % 6 = 3 → partition 3 (always)
key="order-456" → murmur2 → 9876543210 → % 6 = 0 → partition 0
```

⚠️ If partition count changes after production data exists, keys start routing differently. Pre-plan partition counts.

### Sticky Partitioner (null key, since Kafka 2.4)

```
StickyPartitioner picks a random partition and STICKS to it until:
  - Current batch reaches batch.size, OR
  - linger.ms expires

Then it switches to a different random partition.

Why sticky is better than round-robin for null keys:
  Round-robin: msg1→P0, msg2→P1, msg3→P2, msg4→P0, ...
    Result: 1 message per partition per batch → tiny batches → poor compression
    
  Sticky: msg1→msg100→P3 (full batch), then msg101→msg200→P1 (full batch)
    Result: full batches → good compression → fewer network calls
    Throughput improvement: 2-5x vs round-robin
```

### Custom Partitioner

```java
public class RegionPartitioner implements Partitioner {
    @Override
    public int partition(String topic, Object key, byte[] keyBytes,
                         Object value, byte[] valueBytes, Cluster cluster) {
        int numPartitions = cluster.partitionCountForTopic(topic);
        if (key != null && key.toString().startsWith("EU-")) {
            return 0;  // EU orders always go to partition 0
        }
        return Utils.toPositive(Utils.murmur2(keyBytes)) % (numPartitions - 1) + 1;
    }
}
```

---

## Step 4: Serialization

Key and value are converted to `byte[]` before entering the accumulator.

```java
// Called on the application thread (not Sender thread)
byte[] keyBytes   = keySerializer.serialize("orders", record.key());
byte[] valueBytes = valueSerializer.serialize("orders", record.value());
```

Key bytes are used by the partitioner for consistent hashing — same object must serialize to the same bytes every time.

**Avro with Schema Registry:**
```java
// KafkaAvroSerializer prepends a 5-byte magic header:
// [0x00][4-byte schema ID][avro payload]
// Schema ID registered/fetched from Schema Registry on first use
```

---

## Step 5: RecordAccumulator

The in-memory buffer. Organized as:

```java
Map<TopicPartition, Deque<ProducerBatch>> batches;
// For each partition: a queue of ProducerBatch objects
// Last in the deque = active batch (receiving new records)
// Others = full batches waiting for Sender to drain them
```

```
RecordAccumulator state:
  orders-0: [ProducerBatch(FULL:131072B)] → [ProducerBatch(ACTIVE:45230B)]
                                             ▲ new records appended here
  orders-2: [ProducerBatch(ACTIVE:12000B)]
  payments-1: [ProducerBatch(ACTIVE:3000B)]
```

### BufferPool — Memory Reuse

All `ProducerBatch` objects are backed by `ByteBuffer` instances from the `BufferPool`.

```
BufferPool (total = buffer.memory = 32 MB):
  Free pool: [ByteBuffer(128KB), ByteBuffer(128KB), ByteBuffer(128KB), ...]

New batch needed:
  → take from free pool (zero allocation, zero GC pressure)

Batch fully sent and acked:
  → return ByteBuffer to free pool (zero GC)

Pool exhausted:
  send() blocks on calling thread for max.block.ms (60s)
  If pool not freed within max.block.ms: TimeoutException
```

**A batch is ready to send when:**
1. Size ≥ `batch.size` (default 16 KB), OR
2. `linger.ms` has elapsed since first record was added to the batch, OR
3. `buffer.memory` is exhausted (flush eagerly to free space), OR
4. Producer `flush()` or `close()` called

---

## Step 6: Sender Thread

The background thread that converts ready batches into broker network requests.

```java
// Simplified Sender loop
void run() {
    while (running) {
        long now = time.milliseconds();
        long pollTimeout = sendProducerData(now);  // ← drain accumulator
        client.poll(pollTimeout, now);              // ← NIO: send/receive
    }
    // Shutdown: flush remaining data, close connections
}
```

### sendProducerData() — Every Iteration

```
1. FIND READY BATCHES:
   For each TopicPartition in accumulator:
     - Batch full (size ≥ batch.size)? → READY
     - linger.ms elapsed? → READY  
     - Retry pending? → READY (with retry.backoff.ms elapsed)
     - buffer.memory full? → ALL READY (emergency flush)

2. CHECK LEADER AVAILABILITY:
   For each ready partition:
     - Leader known in metadata? YES → proceed
     - Leader unknown (new topic, after failover)? NO → wait for metadata refresh
     - Too many in-flight requests to that broker? → wait

3. GROUP BY BROKER:
   orders-0 (leader=broker-1) → batch-A
   orders-2 (leader=broker-2) → batch-B, batch-C
   payments-1 (leader=broker-1) → batch-D

   broker-1 gets: [batch-A, batch-D]  (multi-partition, one request)
   broker-2 gets: [batch-B, batch-C]

4. BUILD ProduceRequest PER BROKER:
   ProduceRequest {
     acks: -1,
     timeoutMs: 30000,
     topicData: [
       {topic:"orders", partitionData:[{partition:0, records: batch-A}]},
       {topic:"payments", partitionData:[{partition:1, records: batch-D}]}
     ]
   }

5. ENQUEUE IN NetworkClient:
   Up to max.in.flight.requests.per.connection=5 requests per broker
   NIO selector: write to socket when WRITE event fires
```

### Handling ProduceResponse

```
ProduceResponse received for broker-1:
  orders-0: errorCode=0, baseOffset=1000000 → SUCCESS
    - Complete each record's Future<RecordMetadata>
    - RecordMetadata: {topic=orders, partition=0, offset=1000000}
    - Fire callback if provided

  payments-1: errorCode=6 (NOT_LEADER_OR_FOLLOWER) → retriable error
    - Trigger metadata refresh (leader may have changed)
    - Re-enqueue batch for retry after retry.backoff.ms (100ms default)
    - Retry continues until delivery.timeout.ms (120s default) expires
```

### Retry Backoff

```java
// Exponential backoff with jitter
long backoff = Math.min(
    retryBackoffMaxMs,                           // retry.backoff.max.ms = 1000ms
    retryBackoffMs * (long) Math.pow(2, attempt) // retry.backoff.ms = 100ms
);
// With jitter: actual = backoff * (0.5 + random * 0.5)
```

---

## Step 7: Idempotent Producer

### The Problem Without Idempotence

```
t=0  Producer sends batch [seq=5] to broker
     Broker writes to log at offset 1000000
     Broker sends ack
     ACK PACKET IS LOST (network issue)
     
t=3  Producer's request.timeout.ms fires — no ack received
     Producer retries: sends [seq=5] again
     
t=3  Broker receives same batch again
     NO idempotence: writes AGAIN at offset 1000001
     Consumer sees duplicate at offsets 1000000 and 1000001
```

### How Sequence Numbers Solve It

```java
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
// Also auto-sets:
// acks=all
// retries=Integer.MAX_VALUE
// max.in.flight.requests.per.connection=5
```

```
Broker assigns Producer ID (PID) on first connection.
Producer tracks sequence number per partition (starts at 0).
Each batch: baseSequence = current_seq; seq increments by numRecords.

Broker maintains in-memory: Map<PID, Map<Partition, lastSeq>>

Retry scenario with idempotence:
  Producer sends [PID=1001, partition=0, seq=5]
  Broker: writes at offset 1000000, stores (PID=1001, partition=0, lastSeq=5)
  Ack lost. Producer retries [PID=1001, partition=0, seq=5]
  Broker: checks stored lastSeq=5, received seq=5 → DUPLICATE
  Broker: returns success without writing → one copy in log
```

**Sequence number validation rules:**

| Received seq | Expected seq | Action |
|---|---|---|
| expected + 1 | ✓ | Write batch |
| ≤ stored lastSeq | DUPLICATE | Discard, return success |
| > stored lastSeq + 1 | GAP | `OutOfOrderSequenceException` (fatal) |

---

## Step 8: Transactional Producer

### Setup and Usage

```java
Properties props = new Properties();
props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "payment-processor-pod-0");
// transactional.id auto-enables: enable.idempotence=true, acks=all

KafkaProducer<String, String> producer = new KafkaProducer<>(props);
producer.initTransactions();  // registers with Transaction Coordinator, gets PID

// Processing loop:
producer.beginTransaction();       // client-side flag only, no network call
try {
    producer.send(new ProducerRecord<>("output", key, value));
    producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
    producer.commitTransaction();  // 2PC: PREPARE_COMMIT → COMMIT markers
} catch (ProducerFencedException e) {
    producer.close();  // this instance is a zombie — stop immediately
} catch (KafkaException e) {
    producer.abortTransaction();   // ABORT markers written — records invisible
}
```

### Transaction Internal Flow

```
initTransactions():
  → FindCoordinatorRequest(transactional.id) → any broker
  → Coordinator = leader of __transaction_state-{hash(txnId) % 50}
  → InitProducerIdRequest to Coordinator
  → Coordinator: increments epoch for this transactional.id (zombie fencing)
  → Returns: PID, epoch

beginTransaction():
  → Sets transactionInFlight = true (local flag only, no network)

send():
  → First send to a new partition in this transaction:
     AddPartitionsToTxnRequest → Coordinator (records partition as part of txn)
  → Normal produce to partition leader (records marked with PID + epoch)
  → Records written to .log but INVISIBLE to read_committed consumers

commitTransaction():
  → EndTxnRequest(commit=true) → Coordinator
  → Coordinator writes PREPARE_COMMIT to __transaction_state
    [DURABILITY POINT — transaction will complete even if coordinator crashes]
  → Coordinator sends WriteTxnMarkersRequest to each partition leader
  → Each leader writes COMMIT marker to its .log
  → Consumers with read_committed see records become visible
  → Coordinator writes COMPLETE_COMMIT
```

---

## Key Configurations Reference

```properties
# Connection
bootstrap.servers=b1:9092,b2:9092,b3:9092

# Serialization
key.serializer=org.apache.kafka.common.serialization.StringSerializer
value.serializer=org.apache.kafka.common.serialization.StringSerializer

# Durability
acks=all
enable.idempotence=true
retries=2147483647
delivery.timeout.ms=120000
request.timeout.ms=30000

# Throughput
compression.type=lz4
batch.size=131072          # 128 KB
linger.ms=5                # 5ms wait
buffer.memory=67108864     # 64 MB
max.block.ms=60000         # block on full buffer for 60s
max.in.flight.requests.per.connection=5

# Transactions (optional)
transactional.id=service-name-pod-name
transaction.timeout.ms=60000
```

---

## Throughput Profiles

| Profile | batch.size | linger.ms | Expected Throughput |
|---|---|---|---|
| Low latency | 16 KB | 0 | ~100 MB/s |
| Balanced | 128 KB | 5ms | ~500 MB/s |
| High throughput | 512 KB | 20ms | ~1+ GB/s |

---

## Summary

```
send(record)
  → Partitioner: which partition?
  → Serializer: key/value → bytes
  → RecordAccumulator: append to batch
  → Future returned to caller (async)

[Background Sender Thread]
  → Find ready batches (full OR linger.ms elapsed)
  → Group by broker → build ProduceRequest
  → NIO send → broker
  → Broker writes to page cache → replication → HW advances
  → ProduceResponse received
  → Complete Futures → fire callbacks
  → Retry on retriable errors with exponential backoff
```
