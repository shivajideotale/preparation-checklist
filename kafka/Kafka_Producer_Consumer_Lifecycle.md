# Kafka: Producer & Consumer Group Lifecycle — Deep Dive

> Every step a producer takes from instantiation to disk write, and every step a consumer group takes from startup through stable message consumption — with internal request/response details, network calls, and Java code examples.

---

## Table of Contents

### Part 1 — Producer Journey
1. [KafkaProducer Instantiated](#1-kafkaproducer-instantiated)
2. [First send() — Metadata Fetch](#2-first-send--metadata-fetch)
3. [Partitioner Assigns Target Partition](#3-partitioner-assigns-target-partition)
4. [Serialization](#4-serialization)
5. [Record Added to RecordAccumulator](#5-record-added-to-recordaccumulator)
6. [Sender Thread Drains and Sends](#6-sender-thread-drains-and-sends)
7. [Broker Writes to Partition Log](#7-broker-writes-to-partition-log)
8. [ProduceResponse and Future Completion](#8-produceresponse-and-future-completion)

### Part 2 — Consumer Group Journey
9.  [KafkaConsumer Instantiated](#9-kafkaconsumer-instantiated)
10. [subscribe() — Topic Registration](#10-subscribe--topic-registration)
11. [poll() — FindCoordinator Request](#11-poll--findcoordinator-request)
12. [JoinGroup — Requesting Group Membership](#12-joingroup--requesting-group-membership)
13. [JoinGroup Response — Leader Elected](#13-joingroup-response--leader-elected)
14. [Leader Computes Partition Assignment](#14-leader-computes-partition-assignment)
15. [SyncGroup — Assignment Distributed](#15-syncgroup--assignment-distributed)
16. [OffsetFetch — Where to Start Reading](#16-offsetfetch--where-to-start-reading)
17. [Heartbeat Thread — Liveness Signal](#17-heartbeat-thread--liveness-signal)
18. [FetchRequest — Pulling Messages](#18-fetchrequest--pulling-messages)
19. [Offset Commit — Checkpointing Progress](#19-offset-commit--checkpointing-progress)

### Part 3 — End-to-End Flow
20. [Full Flow Diagram](#20-full-flow-diagram)
21. [Rebalance When a New Consumer Joins](#21-rebalance-when-a-new-consumer-joins)
22. [What Happens on Consumer Crash](#22-what-happens-on-consumer-crash)

---

## Part 1 — Producer Journey

### Flow Overview

```
Application
    │
    │ new KafkaProducer<>(props)
    ▼
┌─────────────────────────────────────────────────────────────┐
│                    KafkaProducer                            │
│                                                             │
│  ┌──────────────────┐    ┌──────────────────────────────┐  │
│  │  Metadata Cache  │    │      RecordAccumulator       │  │
│  │  (topic→brokers) │    │  orders-0: [Batch | Batch]   │  │
│  └────────┬─────────┘    │  orders-1: [Batch(active)]   │  │
│           │              └──────────────┬───────────────┘  │
│           │                             │                   │
│  ┌────────▼─────────────────────────────▼──────────────┐   │
│  │              Sender Thread (background)              │   │
│  │   - Reads ready batches from accumulator            │   │
│  │   - Groups batches by destination broker            │   │
│  │   - Sends ProduceRequest via NetworkClient (NIO)    │   │
│  │   - Handles ProduceResponse, retries, callbacks     │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
         │                            │
    TCP connection              TCP connection
         │                            │
    Broker-1 (leader              Broker-2 (leader
    of orders-0)                  of orders-1)
```

---

### 1. KafkaProducer Instantiated

```java
Properties props = new Properties();
props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,  "broker1:9092,broker2:9092,broker3:9092");
props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class.getName());
props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
props.put(ProducerConfig.ACKS_CONFIG,               "all");
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG,   "lz4");
props.put(ProducerConfig.BATCH_SIZE_CONFIG,         131072);   // 128 KB
props.put(ProducerConfig.LINGER_MS_CONFIG,          5);

KafkaProducer<String, String> producer = new KafkaProducer<>(props);
```

**What happens internally:**

| Component Created | Purpose |
|---|---|
| `Metadata` | Empty cluster state cache (broker list, partition leaders) |
| `RecordAccumulator` | In-memory buffer: `Map<TopicPartition, Deque<RecordBatch>>` |
| `BufferPool` | Reusable `ByteBuffer` pool — total size = `buffer.memory` (32 MB default) |
| `Sender` thread | Background daemon thread — handles ALL network I/O |
| `NetworkClient` | Java NIO selector for non-blocking socket I/O |
| Key/Value `Serializer` instances | Instantiated and `configure()`d |
| `Partitioner` | Default: `DefaultPartitioner` (sticky) or custom |

**Zero network calls** happen during construction. The producer is not connected to any broker yet.

---

### 2. First send() — Metadata Fetch

```java
Future<RecordMetadata> future = producer.send(
    new ProducerRecord<>("orders", "order-123", "{\"amount\": 99.99}")
);
```

**Step 2a: Metadata check**

The producer checks its `Metadata` object for topic `orders`. It's empty — the producer has never talked to a broker. It blocks the calling thread (up to `max.block.ms` = 60s) waiting for metadata to arrive.

**Step 2b: MetadataRequest fired by Sender thread**

```
Producer → MetadataRequest → broker1:9092 (from bootstrap.servers)

MetadataRequest {
  topics: ["orders"],
  allowAutoTopicCreation: false
}
```

**Step 2c: MetadataResponse received**

```
MetadataResponse {
  throttleTimeMs: 0,
  brokers: [
    {nodeId: 0, host: "broker-0", port: 9092, rack: "us-east-1a"},
    {nodeId: 1, host: "broker-1", port: 9092, rack: "us-east-1b"},
    {nodeId: 2, host: "broker-2", port: 9092, rack: "us-east-1c"}
  ],
  clusterId: "lkjh23423JKHJK",
  controllerId: 0,
  topics: [
    {
      errorCode: 0,
      name: "orders",
      partitions: [
        {errorCode:0, partitionIndex:0, leaderId:0, replicaNodes:[0,1,2], isrNodes:[0,1,2]},
        {errorCode:0, partitionIndex:1, leaderId:1, replicaNodes:[1,2,0], isrNodes:[1,2,0]},
        {errorCode:0, partitionIndex:2, leaderId:2, replicaNodes:[2,0,1], isrNodes:[2,0,1]},
        {errorCode:0, partitionIndex:3, leaderId:0, replicaNodes:[0,2,1], isrNodes:[0,2,1]},
        {errorCode:0, partitionIndex:4, leaderId:1, replicaNodes:[1,0,2], isrNodes:[1,0,2]},
        {errorCode:0, partitionIndex:5, leaderId:2, replicaNodes:[2,1,0], isrNodes:[2,1,0]}
      ]
    }
  ]
}
```

The producer now knows: `orders` has 6 partitions, distributed across 3 brokers. Metadata is cached and refreshed every `metadata.max.age.ms` (default 5 minutes) and also on certain errors.

---

### 3. Partitioner Assigns Target Partition

The partitioner determines which partition the record goes to:

**Case 1: Key is NOT null (consistent hash routing)**

```java
// Key = "order-123"
int partition = Utils.toPositive(Utils.murmur2(keyBytes)) % numPartitions;
// murmur2("order-123") = some hash value
// result = 2  (e.g., always routes to partition 2)
```

All records with key `"order-123"` always go to the same partition. This guarantees **ordering within a key** across the entire topic lifetime (as long as partition count doesn't change).

**Case 2: Key is null (Sticky Partitioner — default since Kafka 2.4)**

```
No key → StickyPartitioner picks a random partition
         Sticks to that partition until:
           - Current batch is full (batch.size reached)
           - linger.ms expires
         Then switches to a different random partition

Before Sticky Partitioner (old RoundRobinPartitioner):
  Message 1 → partition 0
  Message 2 → partition 1
  Message 3 → partition 2
  Result: tiny batches (one message per partition per cycle)

Sticky Partitioner:
  Messages 1-100 → partition 3 (until batch fills)
  Messages 101-200 → partition 1 (switch after full batch)
  Result: larger batches → better compression → fewer network round trips
```

**Case 3: Custom partitioner**

```java
public class PriorityPartitioner implements Partitioner {
    @Override
    public int partition(String topic, Object key, byte[] keyBytes,
                         Object value, byte[] valueBytes, Cluster cluster) {
        int numPartitions = cluster.partitionCountForTopic(topic);
        // VIP customers → first partition, others spread across the rest
        if (key != null && key.toString().startsWith("VIP-")) {
            return 0;
        }
        return Utils.toPositive(Utils.murmur2(keyBytes)) % (numPartitions - 1) + 1;
    }
}
```

---

### 4. Serialization

Before the record enters the accumulator, key and value are serialized:

```java
// Configured serializers are called:
byte[] keyBytes   = keySerializer.serialize("orders", key);    // "order-123" → bytes
byte[] valueBytes = valueSerializer.serialize("orders", value); // JSON string → bytes
```

**Important details:**

- Serialization happens on the **calling thread** (not the Sender thread) — slow serializers block `send()`
- For Avro with Schema Registry: serializer first registers or looks up the schema ID, prepends the 5-byte schema ID to the payload (`0x00` magic byte + 4-byte schema ID + Avro bytes)
- Broker is **schema-agnostic** — it never inspects or validates the payload bytes
- Key bytes are also passed to the partitioner for consistent hashing

```java
// Avro serializer adds schema ID as prefix
byte[] avroBytes = avroSerializer.serialize("orders", avroRecord);
// avroBytes = [0x00, schemaId(4 bytes), avro_payload...]
// Consumer's Avro deserializer strips the prefix and uses schemaId to fetch the schema
```

---

### 5. Record Added to RecordAccumulator

The serialized record is appended to the accumulator — the central in-memory buffer organized by `TopicPartition`:

```
RecordAccumulator state after several sends:

┌─────────────────────────────────────────────────────────────────┐
│ TopicPartition: orders-2                                        │
│   Deque:                                                        │
│     [RecordBatch(FULL: 131072 bytes) | RecordBatch(ACTIVE)]     │
│                                            ▲ new records here   │
├─────────────────────────────────────────────────────────────────┤
│ TopicPartition: orders-4                                        │
│   Deque:                                                        │
│     [RecordBatch(ACTIVE: 45230 bytes)]                          │
└─────────────────────────────────────────────────────────────────┘
```

**BufferPool — memory reuse:**

```
BufferPool (total = buffer.memory = 32 MB):
  Available buffers: [ByteBuffer(128KB), ByteBuffer(128KB), ...]
  
When a batch needs a new ByteBuffer:
  → Take from pool (no allocation)
When a batch is fully sent and acked:
  → Return ByteBuffer to pool (no GC)

If pool is exhausted (all buffer.memory consumed):
  → send() BLOCKS the calling thread for max.block.ms
  → Then throws TimeoutException if still no memory
```

**A batch is declared "ready to send" when:**
1. Its size reaches `batch.size` (default 16384 bytes / 16 KB), OR
2. `linger.ms` has elapsed since the first record was added to the batch, OR
3. `buffer.memory` is full (flush eagerly to free space), OR
4. Producer is closing (`flush()` or `close()` called)

The `send()` method returns a `Future<RecordMetadata>` immediately after adding to the accumulator — before any network activity.

---

### 6. Sender Thread Drains and Sends

The Sender thread is the **only thread that talks to the network**. It runs in an infinite loop:

```java
// Simplified Sender loop
while (running) {
    long now = time.milliseconds();
    long pollTimeout = sendProducerData(now);  // drain accumulator → enqueue requests
    client.poll(pollTimeout, now);              // NIO select() — send/receive
}
```

**sendProducerData() — what happens each iteration:**

```
Step 1: Find ready TopicPartitions
  For each TopicPartition in accumulator:
    - Does it have a FULL batch? → READY
    - Has linger.ms elapsed for its oldest batch? → READY
    - Is it retrying a previously failed batch? → READY

Step 2: Check leader availability for each ready partition
  - Is there a known leader? (from metadata) → proceed
  - Is there an in-flight request to this leader? (max.in.flight check) → wait
  - Is the leader temporarily unavailable? → wait with backoff

Step 3: Group ready batches by destination broker
  orders-0 (leader=broker-0) → [batch-A, batch-B]
  orders-2 (leader=broker-2) → [batch-C]
  orders-4 (leader=broker-0) → [batch-D]

  → broker-0 gets: [batch-A (orders-0), batch-B (orders-0), batch-D (orders-4)]
  → broker-2 gets: [batch-C (orders-2)]

Step 4: Build ProduceRequest per broker
  One ProduceRequest to broker-0 carrying batches for multiple partitions:
  ProduceRequest {
    transactionalId: null,
    acks: -1,  // acks=all
    timeoutMs: 30000,
    topicData: [
      {topic:"orders", partitionData: [
        {partition:0, records: batch-A + batch-B},
        {partition:4, records: batch-D}
      ]}
    ]
  }

Step 5: client.send(node, request) → enqueued in NIO outbound buffer
```

**NIO select() loop:**

```
client.poll() fires Java NIO selector:
  CONNECT events: complete TCP handshakes to new broker connections
  WRITE events:   flush outbound buffers to network sockets
  READ events:    receive ProduceResponse bytes from brokers
                  parse response → complete Futures → fire callbacks
```

Up to `max.in.flight.requests.per.connection` = 5 requests can be in-flight to a single broker simultaneously, pipelining sends without waiting for each ack.

---

### 7. Broker Writes to Partition Log

When the broker receives a `ProduceRequest`:

```
Broker I/O thread (num.io.threads = 8, one picks up the request):

Step 1: Parse and validate
  - Deserialize ProduceRequest wire format
  - Validate CRC32C checksum of each record batch
  - Check batch size ≤ max.message.bytes (topic-level config)
  - Check authorization (ACLs if enabled)
  - If enable.idempotence: validate PID + epoch + sequence number

Step 2: Write to active segment (per partition in the request)
  FileChannel.write(batchBytes)
  → OS writes to page cache (NOT directly to disk)
  → Returns immediately — no disk I/O blocking the thread

Step 3: Update in-memory state
  partition.leaderEndOffset += batchRecordCount
  Update offset index if threshold crossed (index.interval.bytes)

Step 4: Await ISR replication (if acks=-1 / acks=all)
  Follower ReplicaFetcherThreads are continuously pulling from leaders:
    FetchRequest(fetchOffset=leaderEndOffset) → leader
    Leader returns new batch bytes
    Follower writes to its own log (also page cache)
    Follower advances its LEO
    Follower's next FetchRequest carries its new fetchOffset
      → leader sees follower caught up → ISR confirmed

Step 5: Advance High Watermark
  HW = min(LEO across all current ISR members)
  When all ISR members have LEO ≥ new records: HW advances
  Records at or below HW are now visible to consumers

Step 6: Send ProduceResponse
  With acks=all: after HW advance
  With acks=1:   after leader write to page cache
  With acks=0:   never sent
```

**Disk write timeline:**

```
t=0  Producer sends batch
t=0  Broker writes to page cache (RAM) — immediate
t=0  Follower-1 fetches → writes to its page cache
t=0  Follower-2 fetches → writes to its page cache
t=0  HW advances — ack sent to producer
t=?  OS async flush: page cache pages written to actual disk
     (timing determined by OS dirty page policy, not Kafka)
```

Durability comes from **replication to 3 machines**, not from disk fsync. All 3 would have to crash before flushing their page caches for data loss to occur.

---

### 8. ProduceResponse and Future Completion

```
ProduceResponse {
  throttleTimeMs: 0,
  responses: [
    {
      name: "orders",
      partitionResponses: [
        {
          index: 0,
          errorCode: 0,         // 0 = NONE (success)
          baseOffset: 1000000,  // offset of first record in this batch
          logAppendTimeMs: -1,
          logStartOffset: 0
        }
      ]
    }
  ]
}
```

**Sender thread processes response:**

```java
// For each batch in the response:
if (errorCode == 0) {
    // SUCCESS — complete the Future<RecordMetadata>
    batch.done(baseOffset, appendTime, null);
    // Each record's Future now resolves:
    // RecordMetadata { topic="orders", partition=0, offset=1000000, timestamp=... }
    // Callback fires if provided:
    // (metadata, null) → success callback
} else if (isRetriableError(errorCode)) {
    // LEADER_NOT_AVAILABLE, REQUEST_TIMED_OUT, etc.
    batch.reenqueue(); // put back in accumulator for retry
    requestMetadataUpdate(); // refresh metadata (leader may have changed)
} else {
    // RECORD_TOO_LARGE, INVALID_REQUIRED_ACKS, etc.
    batch.done(null, -1, exception); // fail the Future
}
```

**Retry with exponential backoff:**

```
Retriable error received for batch:
  retry #1: wait retry.backoff.ms (default 100ms)
  retry #2: wait min(retry.backoff.max.ms, 100ms * 2) = 200ms
  retry #3: wait 400ms
  ...until delivery.timeout.ms (default 120s) expires

With enable.idempotence=true:
  Retry is SAFE — broker detects duplicate via PID + sequence number
  → One copy in the log even after 10 retries
```

---

## Part 2 — Consumer Group Journey

### Flow Overview

```
Consumer Instance(s)
       │
       │ new KafkaConsumer<>(props)
       │ consumer.subscribe(["orders"])
       ▼
┌──────────────────────────────────────────────────────────────────┐
│                     KafkaConsumer                                │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │              ConsumerCoordinator                        │    │
│  │  - Manages group membership (join/sync/heartbeat)       │    │
│  │  - Handles rebalances                                   │    │
│  │  - Commits/fetches offsets to/from __consumer_offsets   │    │
│  └───────────────────────┬─────────────────────────────────┘    │
│                           │ Coordinator RPCs                     │
│  ┌────────────────────────▼────────────────────────────────┐    │
│  │              Fetcher                                    │    │
│  │  - Sends FetchRequests to partition leader brokers      │    │
│  │  - Buffers received records                             │    │
│  │  - Handles fetch offsets per TopicPartition             │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
│  HeartbeatThread ──── runs independently ───────────────────    │
└──────────────────────────────────────────────────────────────────┘
       │ FindCoordinator/JoinGroup/SyncGroup  │ FetchRequest
       ▼                                      ▼
 Group Coordinator                    Partition Leader Brokers
 (broker leading                      (different brokers)
 __consumer_offsets-N)
```

---

### 9. KafkaConsumer Instantiated

```java
Properties props = new Properties();
props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,          "broker1:9092,broker2:9092");
props.put(ConsumerConfig.GROUP_ID_CONFIG,                   "order-service-prod");
props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,     StringDeserializer.class.getName());
props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class.getName());
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,         false);
props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,          "latest");
props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,           500);
props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,       300000);
props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,         45000);
props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG,      15000);
props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
    CooperativeStickyAssignor.class.getName());

KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
```

**Objects created internally:**

| Object | Purpose |
|---|---|
| `ConsumerNetworkClient` | NIO-based async network layer (wraps `NetworkClient`) |
| `ConsumerCoordinator` | Group membership, rebalance protocol, offset management |
| `SubscriptionState` | Tracks subscribed topics, assigned partitions, fetch positions |
| `Fetcher` | Manages FetchRequests and response buffering per partition |
| `HeartbeatThread` | Background thread — not yet started |
| `ConsumerMetrics` | JMX metric reporters |

No network calls yet. Consumer is not connected to any broker.

---

### 10. subscribe() — Topic Registration

```java
consumer.subscribe(
    List.of("orders"),
    new ConsumerRebalanceListener() {
        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            // Called BEFORE partitions are taken away (commit here!)
            consumer.commitSync(buildCurrentOffsets(partitions));
        }
        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            // Called AFTER new partitions are assigned (seek here if needed)
            log.info("Assigned: {}", partitions);
        }
    }
);
```

**What happens:**

```
SubscriptionState.subscribe(["orders"], rebalanceListener)
  → topics = {"orders"}
  → subscriptionType = AUTO_TOPICS
  → needsRebalance = true (flag for next poll)
```

Zero network calls. The subscription is purely local state.

**Three subscription modes:**

```java
// Mode 1: Auto-managed (consumer group) — uses group coordinator
consumer.subscribe(List.of("orders"));

// Mode 2: Pattern subscription — matches topics dynamically
consumer.subscribe(Pattern.compile("order-.*"));  // matches: orders, order-details, order-history

// Mode 3: Manual assignment — no group coordinator, no rebalance
consumer.assign(List.of(
    new TopicPartition("orders", 0),
    new TopicPartition("orders", 1)
));
// When using assign(): no group coordinator, no rebalance
// You are responsible for partition management and offset tracking
```

---

### 11. poll() — FindCoordinator Request

```java
ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
```

**poll()** is the heartbeat of the consumer. Every internal operation is triggered from within `poll()` including coordinator discovery, group join, rebalance handling, and actual message fetching.

**Step 11a: Check if coordinator is known**

```
coordinatorNode = null  → need to find it

Send FindCoordinatorRequest to any bootstrap broker:
FindCoordinatorRequest {
  coordinatorType: 0,   // 0 = GROUP
  coordinatorKey: "order-service-prod"  // the group.id
}
```

**Step 11b: Broker computes coordinator partition**

```
// Broker executes:
int coordinatorPartitionIndex = Utils.abs(groupId.hashCode()) % 
                                 offsetsTopicNumPartitions;
// = abs("order-service-prod".hashCode()) % 50
// = e.g., 14

// Leader of __consumer_offsets-14 is the Group Coordinator
// e.g., broker-2 leads __consumer_offsets-14
```

**Step 11c: FindCoordinatorResponse**

```
FindCoordinatorResponse {
  throttleTimeMs: 0,
  errorCode: 0,
  coordinator: {
    nodeId: 2,
    host: "broker-2",
    port: 9092
  }
}
```

Consumer opens a dedicated TCP connection to broker-2. All group management requests (JoinGroup, SyncGroup, Heartbeat, OffsetCommit, OffsetFetch) go to this specific broker.

---

### 12. JoinGroup — Requesting Group Membership

```
Consumer → JoinGroupRequest → broker-2 (Group Coordinator)

JoinGroupRequest {
  groupId: "order-service-prod",
  sessionTimeoutMs: 45000,
  rebalanceTimeoutMs: 300000,
  memberId: "",                // "" = brand new member (no prior ID)
  groupInstanceId: null,       // null = dynamic membership, not static
  protocolType: "consumer",
  protocols: [                 // assignment strategies this consumer supports
    {
      name: "cooperative-sticky",
      metadata: {
        version: 1,
        topics: ["orders"],
        userData: { ... }      // current partition assignments (for sticky)
      }
    },
    {
      name: "range",           // fallback if others don't support cooperative-sticky
      metadata: { topics: ["orders"] }
    }
  ]
}
```

**Group Coordinator decision tree:**

```
Group "order-service-prod" state = EMPTY (no members):

1. Assign memberId to new consumer: "consumer-order-service-prod-3-uuid-abc123"
2. Transition group state: EMPTY → PREPARING_REBALANCE
3. Wait group.initial.rebalance.delay.ms (default 3000ms) for other consumers to join
4. After delay: appoint first consumer that joined as GROUP LEADER
5. Send JoinGroupResponse to all joined members
```

**If group already exists and is stable (other consumers running):**

```
1. Consumer C joins while A and B are running
2. Coordinator: STABLE → PREPARING_REBALANCE
3. Signals existing members A and B via next HeartbeatResponse:
   HeartbeatResponse { errorCode: REBALANCE_IN_PROGRESS }
4. A and B stop fetching, call onPartitionsRevoked(), send JoinGroup
5. Once all members have sent JoinGroup: proceed to election
```

---

### 13. JoinGroup Response — Leader Elected

**For the GROUP LEADER (first member):**

```
JoinGroupResponse {
  throttleTimeMs: 0,
  errorCode: 0,
  generationId: 5,                      // increments on every rebalance
  protocolType: "consumer",
  protocolName: "cooperative-sticky",   // chosen strategy (must be supported by all)
  leader: "consumer-order-service-prod-3-uuid-abc123",  // this consumer's ID
  memberId: "consumer-order-service-prod-3-uuid-abc123",
  members: [                            // ONLY leader gets this full list
    {
      memberId: "consumer-order-service-prod-3-uuid-abc123",
      groupInstanceId: null,
      metadata: {topics: ["orders"], userData: {currentAssignment: []}}
    },
    {
      memberId: "consumer-order-service-prod-7-uuid-def456",
      groupInstanceId: null,
      metadata: {topics: ["orders"], userData: {currentAssignment: ["orders-2","orders-3"]}}
    },
    {
      memberId: "consumer-order-service-prod-9-uuid-ghi789",
      groupInstanceId: null,
      metadata: {topics: ["orders"], userData: {currentAssignment: ["orders-4","orders-5"]}}
    }
  ]
}
```

**For NON-LEADER members (members array is EMPTY):**

```
JoinGroupResponse {
  generationId: 5,
  leader: "consumer-order-service-prod-3-uuid-abc123",  // who the leader is
  memberId: "consumer-order-service-prod-7-uuid-def456",
  members: []   // empty — they don't need to know others
}
```

**Why is the leader a consumer instance and not the broker?**

The partition assignment algorithm runs CLIENT-SIDE. This design allows:
- Pluggable assignment strategies (add new ones without broker changes)
- Rich assignment metadata (consumer rack, capacity, existing assignments)
- The broker only needs to receive and distribute the final assignment

---

### 14. Leader Computes Partition Assignment

The GROUP LEADER consumer runs the configured `PartitionAssignor`:

```java
// CooperativeStickyAssignor.assign() is called with:
// - List of all member subscriptions
// - List of all partitions for subscribed topics
// - Each member's previous assignment (from userData in JoinGroup metadata)

// Input:
Map<String, Subscription> subscriptions = {
  "consumer-abc": Subscription(topics=["orders"], ownedPartitions=[]),
  "consumer-def": Subscription(topics=["orders"], ownedPartitions=["orders-2","orders-3"]),
  "consumer-ghi": Subscription(topics=["orders"], ownedPartitions=["orders-4","orders-5"])
};
List<TopicPartition> allPartitions = [orders-0, orders-1, orders-2, orders-3, orders-4, orders-5];

// CooperativeStickyAssignor strategy:
// 1. Determine which partitions should change hands (imbalanced or newly assigned)
// 2. Minimize changes to existing assignments (sticky)
// 3. Only revoke partitions that MUST move

// Output:
Map<String, Assignment> assignment = {
  "consumer-abc": Assignment(partitions=[orders-0, orders-1]),
  "consumer-def": Assignment(partitions=[orders-2, orders-3]),  // unchanged
  "consumer-ghi": Assignment(partitions=[orders-4, orders-5])   // unchanged
};
```

**Assignment strategy comparison:**

```
Topic "orders" with 6 partitions, 3 consumers A, B, C:

RangeAssignor (per-topic contiguous ranges):
  A → [0, 1]     (first 2 of 6)
  B → [2, 3]     (next 2)
  C → [4, 5]     (last 2)
  ⚠ Uneven with multiple topics (one consumer always gets extra partition)

RoundRobinAssignor (round-robin across all):
  A → [0, 3]
  B → [1, 4]
  C → [2, 5]
  ✓ Balanced, but ignores existing assignments → lots of partition movement

StickyAssignor (balanced + minimal movement):
  Tries to keep existing assignments when rebalancing
  ✓ Balanced AND minimises partition movement
  ✗ Eager rebalance: ALL consumers stop during assignment

CooperativeStickyAssignor (recommended — incremental cooperative):
  Same algorithm as Sticky, BUT:
  ✓ Only partitions that MUST move are revoked
  ✓ Other consumers keep processing during rebalance
  ✓ Two-phase rebalance (no stop-the-world)
```

---

### 15. SyncGroup — Assignment Distributed

**GROUP LEADER sends the full assignment:**

```
Consumer-abc (leader) → SyncGroupRequest → broker-2 (coordinator)

SyncGroupRequest {
  groupId: "order-service-prod",
  generationId: 5,
  memberId: "consumer-abc",
  protocolType: "consumer",
  protocolName: "cooperative-sticky",
  assignments: [
    {
      memberId: "consumer-abc",
      assignment: {
        partitions: [{topic:"orders", partition:0}, {topic:"orders", partition:1}],
        userData: ...
      }
    },
    {
      memberId: "consumer-def",
      assignment: {
        partitions: [{topic:"orders", partition:2}, {topic:"orders", partition:3}]
      }
    },
    {
      memberId: "consumer-ghi",
      assignment: {
        partitions: [{topic:"orders", partition:4}, {topic:"orders", partition:5}]
      }
    }
  ]
}
```

**ALL members (including leader) send SyncGroupRequest** — non-leaders send empty assignments:

```
Consumer-def → SyncGroupRequest {
  memberId: "consumer-def",
  assignments: []  // empty — only leader sends the full map
}
```

**Group Coordinator stores the assignment and responds to EACH consumer:**

```
SyncGroupResponse for consumer-abc {
  protocolType: "consumer",
  protocolName: "cooperative-sticky",
  assignment: {
    partitions: [{topic:"orders", partition:0}, {topic:"orders", partition:1}]
  }
}

SyncGroupResponse for consumer-def {
  assignment: {
    partitions: [{topic:"orders", partition:2}, {topic:"orders", partition:3}]
  }
}
```

Group state transitions: `CompletingRebalance → Stable`

**`onPartitionsAssigned()` callback fires:**

```java
// ConsumerRebalanceListener.onPartitionsAssigned called with new partitions
public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
    // partitions = [orders-0, orders-1] for consumer-abc
    
    // Optional: override fetch position using seek
    for (TopicPartition tp : partitions) {
        Long offset = offsetTracker.getOffset(tp);  // from DB, Redis, etc.
        if (offset != null) {
            consumer.seek(tp, offset);  // start from custom position
        }
    }
}
```

---

### 16. OffsetFetch — Where to Start Reading

After partition assignment, each consumer needs its starting position:

```
Consumer-def → OffsetFetchRequest → broker-2 (coordinator)

OffsetFetchRequest {
  groupId: "order-service-prod",
  partitions: [
    {topic: "orders", partition: 2},
    {topic: "orders", partition: 3}
  ]
}
```

Coordinator reads from `__consumer_offsets` topic (which it leads) and responds:

```
OffsetFetchResponse {
  partitions: [
    {
      topic: "orders",
      partition: 2,
      committedOffset: 45892,    // last committed offset for this group+partition
      leaderEpoch: 3,
      metadata: "",
      errorCode: 0
    },
    {
      topic: "orders",
      partition: 3,
      committedOffset: -1,       // -1 = no committed offset (new group or expired)
      leaderEpoch: -1,
      metadata: "",
      errorCode: 0
    }
  ]
}
```

**Handling the -1 (no committed offset) case:**

```
partition 3 has no committed offset for this consumer group

auto.offset.reset=latest:
  → consumer.seekToEnd([orders-3])
  → fetch position = current High Watermark
  → starts consuming new messages only (skips existing)

auto.offset.reset=earliest:
  → consumer.seekToBeginning([orders-3])
  → fetch position = Log Start Offset (oldest available)
  → replays all retained history

auto.offset.reset=none:
  → throw NoOffsetForPartitionException
  → application must handle this explicitly
```

**Best practice — pre-set offsets for new consumer groups:**

```bash
# Run BEFORE deploying a new consumer group to avoid replay
kafka-consumer-groups.sh --bootstrap-server broker:9092 \
  --group order-service-prod \
  --topic orders \
  --reset-offsets --to-latest --execute
```

---

### 17. Heartbeat Thread — Liveness Signal

After joining the group, a dedicated **HeartbeatThread** starts as a background daemon thread:

```java
// HeartbeatThread simplified
while (!closed) {
    long timeToNextHeartbeat = heartbeat.timeToNextHeartbeat(now);
    
    if (timeToNextHeartbeat == 0) {
        // Time to send a heartbeat
        HeartbeatRequest request = new HeartbeatRequest.Builder(
            new HeartbeatRequestData()
                .setGroupId("order-service-prod")
                .setGenerationId(5)
                .setMemberId("consumer-def")
                .setGroupInstanceId(null)
        );
        client.send(coordinatorNode, request);
    }
    
    Thread.sleep(Math.min(timeToNextHeartbeat, 1000));
}
```

**HeartbeatRequest / HeartbeatResponse:**

```
Every heartbeat.interval.ms (default 3000ms):

Consumer → HeartbeatRequest { groupId, generationId, memberId }
         → Group Coordinator

HeartbeatResponse {
  errorCode: 0  // success — continue
}

OR if rebalance triggered:
HeartbeatResponse {
  errorCode: 27  // REBALANCE_IN_PROGRESS
}
→ Consumer stops fetching, calls onPartitionsRevoked(), sends JoinGroup
```

**Two different timeouts — often confused:**

```
session.timeout.ms (default 45000ms = 45s):
  ┌─ Heartbeat-based
  ├─ If coordinator receives NO heartbeat within this window:
  │   consumer is declared DEAD → rebalance triggered
  └─ Detects: JVM crash, OOM kill, network partition

max.poll.interval.ms (default 300000ms = 5min):
  ┌─ poll()-based (NOT heartbeat)
  ├─ If consumer doesn't call poll() within this window:
  │   broker assumes consumer is STUCK in processing → rebalance
  └─ Detects: slow processing (e.g., 500 records × 100ms each = 50s > 30s limit)

TRICK: heartbeat runs even when processing is slow
       → session.timeout.ms won't fire for slow processing
       → max.poll.interval.ms will fire for slow processing
```

---

### 18. FetchRequest — Pulling Messages

Now the consumer has: partition assignment, starting offsets, running heartbeat thread. It can fetch messages.

FetchRequests go directly to **partition leader brokers** — NOT to the group coordinator:

```
Consumer-def (assigned orders-2, orders-3) sends FetchRequests to their leaders:

orders-2 leader = broker-2 → FetchRequest to broker-2
orders-3 leader = broker-0 → FetchRequest to broker-0

(two simultaneous FetchRequests to different brokers)
```

```
FetchRequest {
  maxWaitMs: 500,           // fetch.max.wait.ms — broker waits this long if not enough data
  minBytes: 1,              // fetch.min.bytes — minimum data to return
  maxBytes: 52428800,       // fetch.max.bytes = 50 MB total
  partitions: [
    {
      topic: "orders",
      partitions: [
        {
          partition: 2,
          fetchOffset: 45892,       // start here (from OffsetFetch)
          maxBytes: 1048576,        // max.partition.fetch.bytes = 1 MB per partition
          currentLeaderEpoch: 3
        }
      ]
    }
  ]
}
```

**Broker processes FetchRequest:**

```
1. Validate fetchOffset ≤ High Watermark (for read_uncommitted)
   or ≤ Last Stable Offset (for read_committed)

2. Find segment file: binary search filenames → O(log S)
   orders-2/: [00...0.log, 00...45000.log, 00...45800.log(active)]
   fetchOffset=45892 → segment 00...45800.log

3. Find position: binary search .index (memory-mapped) → O(log I)
   relativeOffset = 45892 - 45800 = 92
   .index lookup → physical byte position = 4423

4. Read from .log at position 4423 → scan to offset 45892

5. sendfile() → zero-copy to consumer socket
```

```
FetchResponse {
  partitions: [
    {
      topic: "orders",
      partitions: [
        {
          partition: 2,
          errorCode: 0,
          highWatermark: 46000,
          lastStableOffset: 46000,
          logStartOffset: 0,
          records: [
            RecordBatch {
              baseOffset: 45892,
              records: [
                Record { offset:45892, key:"order-101", value:"{...}", timestamp:... },
                Record { offset:45893, key:"order-102", value:"{...}", timestamp:... },
                ... (up to maxBytes)
              ]
            }
          ]
        }
      ]
    }
  ]
}
```

**Application receives records via poll():**

```java
ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
// records contains all fetched records from all assigned partitions

for (ConsumerRecord<String, String> record : records) {
    System.out.printf("partition=%d offset=%d key=%s value=%s%n",
        record.partition(), record.offset(), record.key(), record.value());
}

// IMPORTANT: poll() also:
// - Checks max.poll.interval.ms — resets the timer
// - Triggers pending rebalance callbacks
// - Checks heartbeat response for REBALANCE_IN_PROGRESS
// - Completes pending offset commits
// NEVER block for long inside the loop — it pushes the next poll() late
```

---

### 19. Offset Commit — Checkpointing Progress

After processing, the consumer records its progress so it can resume correctly after restart:

**Manual commit (recommended — at-least-once):**

```java
// After processing all records in the batch:
consumer.commitSync(); // block until coordinator confirms

// OR with per-partition precision:
Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
for (TopicPartition tp : records.partitions()) {
    List<ConsumerRecord<String, String>> partRecords = records.records(tp);
    long lastOffset = partRecords.get(partRecords.size() - 1).offset();
    offsets.put(tp, new OffsetAndMetadata(lastOffset + 1)); // +1 = next to read
}
consumer.commitSync(offsets);
```

**OffsetCommitRequest wire format:**

```
Consumer → OffsetCommitRequest → broker-2 (Group Coordinator)

OffsetCommitRequest {
  groupId: "order-service-prod",
  generationId: 5,
  memberId: "consumer-def",
  groupInstanceId: null,
  retentionTimeMs: -1,
  topics: [
    {
      name: "orders",
      partitions: [
        {partitionIndex: 2, committedOffset: 45900, committedLeaderEpoch: 3, metadata: ""},
        {partitionIndex: 3, committedOffset: 850,   committedLeaderEpoch: 2, metadata: ""}
      ]
    }
  ]
}
```

**Coordinator stores offset:**

```
Coordinator writes to __consumer_offsets topic:

Key:   ["order-service-prod", "orders", 2]
Value: {offset: 45900, leaderEpoch: 3, metadata: "", commitTimestamp: 1706789123456}

This is the data read on next restart/rebalance by OffsetFetch.
```

**OffsetCommitResponse:**

```
OffsetCommitResponse {
  topics: [
    {name: "orders", partitions: [
      {partitionIndex: 2, errorCode: 0},
      {partitionIndex: 3, errorCode: 0}
    ]}
  ]
}
```

---

## Part 3 — End-to-End Flow

### 20. Full Flow Diagram

```
PRODUCER SIDE                    KAFKA CLUSTER                  CONSUMER SIDE
─────────────                    ─────────────                  ─────────────

new KafkaProducer()
       │
       │──MetadataRequest──►  Any Broker
       │◄─MetadataResponse───     │
       │                          │ cluster topology cached
       │
send("orders", key, value)
       │
 [Partitioner]
  key→partition 2
       │
 [Serializer]
  → bytes
       │
 [RecordAccumulator]
  batch for orders-2
       │
 [Sender Thread]
       │──ProduceRequest──►  Broker-2 (leader of orders-2)
       │                          │
       │                     [Write to page cache]
       │                     [Update LEO: 1000001]
       │                          │
       │                     [Followers fetch]──►  Broker-0
       │                          │             ──►  Broker-1
       │                     [HW advances to 1000001]
       │                          │
       │◄──ProduceResponse────────┘
       │  {offset: 1000000}
 [Future complete]
 [Callback fires]


                                                  new KafkaConsumer()
                                                         │
                                                  subscribe(["orders"])
                                                         │
                                                  poll() → FindCoordinator
                                                         │──FindCoord──►  Any Broker
                                                         │◄─Response───     │
                                                         │  coordinator = Broker-2
                                                         │
                                                         │──JoinGroupRequest──►  Broker-2
                                                         │◄─JoinGroupResponse──     │
                                                         │  assigned partitions
                                                         │  [2, 3]
                                                         │
                                                         │──SyncGroupRequest──►
                                                         │◄─SyncGroupResponse──
                                                         │
                                                         │──OffsetFetchRequest──►  Broker-2
                                                         │◄─OffsetFetchResponse──
                                                         │  partition 2 → offset 45892
                                                         │  partition 3 → offset -1 (latest)
                                                         │
                                                  [HeartbeatThread starts]
                                                         │
                                                         │──FetchRequest──►  Broker-2 (orders-2 leader)
                                                         │──FetchRequest──►  Broker-0 (orders-3 leader)
                                                         │◄─FetchResponse── records [45892..45999]
                                                         │
                                                  [process(records)]
                                                         │
                                                         │──OffsetCommitRequest──►  Broker-2
                                                         │◄─OffsetCommitResponse──
                                                         │  partition 2 → 46000 committed
                                                         │
                                                  [next poll()]
```

---

### 21. Rebalance When a New Consumer Joins

When consumer-D starts and subscribes to the same `group.id`:

```
Current state:
  consumer-A → [orders-0, orders-1]
  consumer-B → [orders-2, orders-3]
  consumer-C → [orders-4, orders-5]

t=0   consumer-D calls subscribe(["orders"]) and poll()
      → sends FindCoordinator → gets broker-2
      → sends JoinGroupRequest to broker-2

t=0   Coordinator: group "order-service-prod" is STABLE
      → transition to PREPARING_REBALANCE
      → sends REBALANCE_IN_PROGRESS via HeartbeatResponse to A, B, C

t=1   A, B, C receive rebalance signal via heartbeat:
      → call onPartitionsRevoked() on their listeners
      → COOPERATIVE: revoke only what must move (not all partitions)
      → all 4 consumers send JoinGroupRequest

With CooperativeStickyAssignor — PHASE 1:
  t=2   Leader computes:
        D needs 1 partition (6 partitions / 4 consumers = 1.5, round up)
        Best choice: take orders-1 from A (A keeps orders-0)
        
        JoinGroupResponse assignment (phase 1):
          A → [orders-0]               (revoke orders-1)
          B → [orders-2, orders-3]     (unchanged — keep processing!)
          C → [orders-4, orders-5]     (unchanged — keep processing!)
          D → [orders-1]               (newly assigned)

  t=3   SyncGroup: coordinator distributes this assignment
        ONLY A calls onPartitionsRevoked([orders-1])
        B and C NEVER stop — continuous processing during rebalance!

  t=4   D receives assignment [orders-1], calls onPartitionsAssigned()
        D starts sending FetchRequests to orders-1 leader

Final state:
  A → [orders-0]
  B → [orders-2, orders-3]
  C → [orders-4, orders-5]
  D → [orders-1]


With RangeAssignor (avoid!) — STOP-THE-WORLD:
  t=2   ALL consumers call onPartitionsRevoked([all their partitions])
        B and C STOP processing orders-2, 3, 4, 5 entirely
        Full reassignment computed:
          A → [0]    B → [1, 2]    C → [3, 4]    D → [5]
  t=3   onPartitionsAssigned on all consumers
        Processing resumes — but gap of several seconds for ALL partitions
```

---

### 22. What Happens on Consumer Crash

```
Running state:
  consumer-A → [orders-0, orders-1]    ← CRASHES (OOM, SIGKILL, network partition)
  consumer-B → [orders-2, orders-3]
  consumer-C → [orders-4, orders-5]

t=0   consumer-A JVM exits abruptly
      Last committed offset for orders-0: 500000
      Last committed offset for orders-1: 250000

t=0 → t+session.timeout.ms (45s):
      Heartbeat thread is DEAD (it was in the JVM)
      Coordinator receives no heartbeats from consumer-A

t=45s Coordinator's session.timeout.ms fires:
      consumer-A declared dead
      Group state: STABLE → PREPARING_REBALANCE

t=45s Coordinator signals B and C via HeartbeatResponse:
      errorCode: REBALANCE_IN_PROGRESS

t=46s B and C send JoinGroupRequest
      Coordinator assigns orders-0 and orders-1:
        (assume B gets orders-0, C gets orders-1)

t=47s SyncGroupResponse distributed
      B calls onPartitionsAssigned([orders-0])
      C calls onPartitionsAssigned([orders-1])

t=47s B sends OffsetFetchRequest for orders-0
      Response: last committed offset = 500000
      B starts FetchRequest from offset 500000

t=47s C sends OffsetFetchRequest for orders-1
      Response: last committed offset = 250000
      C starts FetchRequest from offset 250000

Records 500001-current (orders-0) and 250001-current (orders-1)
that consumer-A was processing AT THE TIME of crash:
  → These will be REDELIVERED to B and C (at-least-once)
  → Make B and C's processing idempotent to handle safely
```

---

## Configuration Cheat Sheet

### Producer key configs for correct join + send

```java
Properties producerProps = new Properties();
// Connection
producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "b1:9092,b2:9092,b3:9092");
// Serialization
producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class.getName());
producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
// Durability
producerProps.put(ProducerConfig.ACKS_CONFIG,               "all");
producerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
producerProps.put(ProducerConfig.RETRIES_CONFIG,            Integer.MAX_VALUE);
// Throughput
producerProps.put(ProducerConfig.BATCH_SIZE_CONFIG,         131072);  // 128 KB
producerProps.put(ProducerConfig.LINGER_MS_CONFIG,          5);       // 5ms
producerProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG,   "lz4");
producerProps.put(ProducerConfig.BUFFER_MEMORY_CONFIG,      67108864); // 64 MB
// Timeout
producerProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000);
producerProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,  30000);
```

### Consumer key configs for correct group join + consumption

```java
Properties consumerProps = new Properties();
// Connection
consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,         "b1:9092,b2:9092,b3:9092");
// Group
consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG,                  "order-service-prod");
consumerProps.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG,         System.getenv("POD_NAME")); // static membership
// Deserialization
consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,    StringDeserializer.class.getName());
consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,  StringDeserializer.class.getName());
// Offset management
consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,        false);
consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,         "latest");
// Throughput and rebalance
consumerProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,          500);
consumerProps.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,      300000);
consumerProps.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,        45000);
consumerProps.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG,     15000);
// Assignment strategy (no stop-the-world rebalance)
consumerProps.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
    CooperativeStickyAssignor.class.getName());
// Fetch tuning
consumerProps.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG,           1);
consumerProps.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG,         500);
consumerProps.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG,           "read_committed"); // if using transactions
```

---

## Step Summary Tables

### Producer — Steps at a Glance

| Step | Action | Network Call? | Thread |
|---|---|---|---|
| 1 | `new KafkaProducer<>()` | None | App thread |
| 2 | First `send()` → MetadataRequest | Yes — any bootstrap broker | Sender thread |
| 3 | Partitioner assigns partition | None | App thread |
| 4 | Serializer encodes key + value | None | App thread |
| 5 | Record added to RecordAccumulator | None | App thread |
| 6 | Sender drains batch → ProduceRequest | Yes — partition leader | Sender thread |
| 7 | Broker writes to log + replication | None (broker-side) | Broker I/O thread |
| 8 | ProduceResponse → Future complete | Yes (receive) | Sender thread |

### Consumer Group — Steps at a Glance

| Step | Action | Network Call? | Destination |
|---|---|---|---|
| 9 | `new KafkaConsumer<>()` | None | — |
| 10 | `subscribe(["orders"])` | None | — |
| 11 | `poll()` → FindCoordinator | Yes | Any broker |
| 12 | JoinGroupRequest | Yes | Group Coordinator |
| 13 | JoinGroupResponse (leader elected) | Yes (receive) | Group Coordinator |
| 14 | Leader computes assignment | None | Client-side computation |
| 15 | SyncGroupRequest + Response | Yes | Group Coordinator |
| 16 | OffsetFetchRequest + Response | Yes | Group Coordinator |
| 17 | HeartbeatThread starts (background) | Yes (every 3s) | Group Coordinator |
| 18 | FetchRequest + Response | Yes | Partition Leader Brokers |
| 19 | OffsetCommitRequest + Response | Yes | Group Coordinator |

---

*The key insight: the Group Coordinator (a specific broker) handles group lifecycle — join, sync, heartbeat, offset commit. But message fetching goes directly to partition leader brokers, completely bypassing the coordinator. These two paths are completely independent, which is why Kafka can scale coordinator load and data throughput independently.*
