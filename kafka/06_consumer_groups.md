# 06 — Consumer & Consumer Groups

> How KafkaConsumer works, how consumer groups share partition assignments, and what happens during rebalances.

---

## Consumer Group Fundamentals

### What a Consumer Group Is

All `KafkaConsumer` instances with the same `group.id` form a **consumer group**. Kafka guarantees that each partition is assigned to exactly one consumer per group at any time.

```
Topic "orders" with 6 partitions:

Group "order-service" (3 consumers):    Group "analytics" (2 consumers):
  C1 → [orders-0, orders-1]              C1 → [orders-0, orders-1, orders-2]
  C2 → [orders-2, orders-3]              C2 → [orders-3, orders-4, orders-5]
  C3 → [orders-4, orders-5]

Both groups receive ALL messages independently.
Each partition has exactly ONE consumer per group.
```

### Scaling Rules

```
consumers < partitions:   some consumers get multiple partitions
  3 consumers, 6 partitions: C1=[P0,P1], C2=[P2,P3], C3=[P4,P5]

consumers = partitions:   each consumer gets exactly one partition
  6 consumers, 6 partitions: C1=[P0], C2=[P1], ..., C6=[P5]

consumers > partitions:   extra consumers sit IDLE
  8 consumers, 6 partitions: 6 active, 2 idle (hot standbys)
  Adding consumers beyond partition count provides NO parallelism benefit
```

---

## Consumer Internal Architecture

```
KafkaConsumer (single-threaded — NOT thread-safe)
├── ConsumerNetworkClient     ← NIO-based async networking
├── ConsumerCoordinator       ← group membership, rebalance, offset ops
│   ├── GroupRebalanceListener
│   └── HeartbeatThread (background) ← liveness signal to coordinator
├── Fetcher                   ← manages FetchRequests to partition leaders
├── SubscriptionState         ← tracks assigned partitions + fetch positions
└── ConsumerMetrics           ← JMX reporting
```

**Critical: `KafkaConsumer` is NOT thread-safe.** All calls (poll, commit, seek, subscribe) must happen on the same thread.

---

## Step 1: Instantiation

```java
Properties props = new Properties();
props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,          "b1:9092,b2:9092");
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

Zero network calls during construction.

---

## Step 2: subscribe()

```java
consumer.subscribe(List.of("orders"), new ConsumerRebalanceListener() {
    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        // Called BEFORE partitions are taken away
        // COMMIT HERE to minimize redelivery
        consumer.commitSync(buildOffsets(partitions));
    }
    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        // Called AFTER new partitions are assigned
        // SEEK HERE for custom starting positions
    }
});
```

Updates `SubscriptionState` locally. **Zero network calls**.

**Three subscription modes:**

| Mode | Method | Behavior |
|---|---|---|
| Group-managed | `subscribe(Collection<String>)` | Partitions assigned by coordinator via rebalance |
| Pattern-matched | `subscribe(Pattern)` | Regex match — new topics auto-added on metadata change |
| Manual | `assign(Collection<TopicPartition>)` | No coordinator, no rebalance, no group membership |

---

## Step 3: poll() — The Engine

`poll()` drives everything. It must be called regularly by the application thread.

```java
ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
```

Every call to `poll()` triggers:
1. **Rebalance handling** — if REBALANCE_IN_PROGRESS: run onPartitionsRevoked → JoinGroup
2. **Coordinator discovery** — if coordinator unknown: FindCoordinator
3. **Group join** — if not in group: JoinGroup → SyncGroup
4. **Fetch sending** — send FetchRequests to partition leaders
5. **Response collection** — collect FetchResponses, buffer records
6. **max.poll.interval.ms reset** — informs the coordinator timer
7. **Pending callbacks** — fire any pending async commit callbacks

---

## Step 4: FindCoordinator

On first `poll()` (or after coordinator failure):

```
consumer → FindCoordinatorRequest(groupId="order-service-prod") → any broker

Any broker computes:
  partition = abs("order-service-prod".hashCode()) % 50 = 14
  Leader of __consumer_offsets-14 = broker-2

FindCoordinatorResponse:
  coordinator: {nodeId: 2, host: "broker-2", port: 9092}

Consumer opens TCP connection to broker-2.
All group management RPCs go to broker-2.
```

---

## Step 5: JoinGroup

```
consumer → JoinGroupRequest → broker-2

JoinGroupRequest:
  groupId: "order-service-prod"
  sessionTimeoutMs: 45000
  rebalanceTimeoutMs: 300000    ← max.poll.interval.ms
  memberId: ""                  ← "" = brand new member
  groupInstanceId: null         ← null = dynamic membership
  protocols: [
    {name: "cooperative-sticky", metadata: {topics:["orders"], ownedPartitions:[...]}}
  ]

Coordinator actions:
  If group is EMPTY: create it, state → PREPARING_REBALANCE
  If group is STABLE: trigger rebalance, state → PREPARING_REBALANCE
  Wait for ALL current members to send JoinGroup (up to rebalance.timeout.ms)
  Assign generationId++ 
  Elect group leader (first member to join, or existing leader if present)
```

**JoinGroupResponse differs by role:**

```
For GROUP LEADER:
  {generationId: 6, leader: "consumer-A-uuid", memberId: "consumer-A-uuid",
   members: [
     {memberId: "consumer-A-uuid", metadata: {topics:["orders"], ownedPartitions:[0,1]}},
     {memberId: "consumer-B-uuid", metadata: {topics:["orders"], ownedPartitions:[2,3]}},
     {memberId: "consumer-C-uuid", metadata: {topics:["orders"], ownedPartitions:[4,5]}}
   ]}

For NON-LEADERS:
  {generationId: 6, leader: "consumer-A-uuid", memberId: "consumer-B-uuid",
   members: []}     ← empty! Others don't need the full member list
```

---

## Step 6: Partition Assignment (Client-Side!)

The **Group Leader consumer** (not the broker) runs the assignment algorithm.

```java
// CooperativeStickyAssignor.assign() called with:
Map<String, ConsumerPartitionAssignor.Subscription> subscriptions = {
    "consumer-A": Subscription(topics=["orders"], ownedPartitions=[P0,P1]),
    "consumer-B": Subscription(topics=["orders"], ownedPartitions=[P2,P3]),
    "consumer-C": Subscription(topics=["orders"], ownedPartitions=[P4,P5]),
    "consumer-D": Subscription(topics=["orders"], ownedPartitions=[])  // new
};

// Algorithm output:
Map<String, Assignment> assignment = {
    "consumer-A": Assignment([P0]),      // gave P1 to D
    "consumer-B": Assignment([P2, P3]), // unchanged
    "consumer-C": Assignment([P4, P5]), // unchanged
    "consumer-D": Assignment([P1])       // new member gets P1
};
```

Why client-side? Allows new assignment strategies without broker code changes.

---

## Step 7: SyncGroup

```
Consumer-A (leader) → SyncGroupRequest → coordinator:
  {assignments: [
    {memberId:"consumer-A", assignment:{partitions:[P0]}},
    {memberId:"consumer-B", assignment:{partitions:[P2,P3]}},
    {memberId:"consumer-C", assignment:{partitions:[P4,P5]}},
    {memberId:"consumer-D", assignment:{partitions:[P1]}}
  ]}

Consumer-B/C/D → SyncGroupRequest → coordinator:
  {assignments: []}  ← non-leaders send empty

Coordinator:
  Stores assignment
  Transitions group: CompletingRebalance → Stable
  Responds to each consumer with their slice:
    Consumer-A ← {assignment: {partitions:[P0]}}
    Consumer-B ← {assignment: {partitions:[P2,P3]}}
    Consumer-D ← {assignment: {partitions:[P1]}}
```

After SyncGroup:
- `onPartitionsRevoked()` called for revoked partitions
- `onPartitionsAssigned()` called for newly assigned partitions

---

## Step 8: OffsetFetch

After partition assignment, consumer discovers starting positions:

```
consumer → OffsetFetchRequest(partitions=[P0]) → coordinator

coordinator reads from __consumer_offsets topic:
  P0: last committed offset = 500000 (from previous session)
  P1: last committed offset = -1 (no committed offset — new group)

OffsetFetchResponse:
  P0: {offset: 500000, leaderEpoch: 3}
  P1: {offset: -1}    ← no committed offset → auto.offset.reset applies

For P0: consumer.seek(P0, 500000) → resume from where left off
For P1: auto.offset.reset=latest → consumer.seekToEnd(P1) → start at HW
```

---

## Step 9: Heartbeat Thread

```java
// Runs independently of poll() — separate daemon thread
class HeartbeatThread extends Thread {
    void run() {
        while (!closed) {
            if (timeToNextHeartbeat <= 0) {
                HeartbeatRequest req = new HeartbeatRequest(
                    groupId, generationId, memberId, groupInstanceId
                );
                client.send(coordinator, req);
            }
            Thread.sleep(Math.min(timeToNextHeartbeat, 1000));
        }
    }
}
```

HeartbeatResponse error codes:

| Error code | Meaning | Consumer action |
|---|---|---|
| 0 (NONE) | All good | Continue |
| REBALANCE_IN_PROGRESS | New rebalance triggered | Stop fetching, JoinGroup |
| ILLEGAL_GENERATION | Stale generation | Rejoin immediately |
| UNKNOWN_MEMBER_ID | Coordinator lost record | Rejoin as new member |

**Two different timeout concepts:**

```
session.timeout.ms (default 45s):
  Server-side. Coordinator tracks time since last heartbeat.
  Fires on: JVM crash, OOM kill, network partition.
  Heartbeat thread keeps running even during slow processing — session stays alive.

max.poll.interval.ms (default 5 min):
  Client-side. Consumer tracks time since last poll().
  Fires on: processing too slow → no poll() call in time.
  Consumer proactively sends LeaveGroupRequest → rebalance.
  Heartbeat thread has NO effect on this timer.
```

---

## Step 10: FetchRequest

**Goes directly to partition leader brokers — NOT through the coordinator.**

```
Consumer assigned [P0, P1] for topic "orders":
  P0 leader = broker-0 → FetchRequest to broker-0
  P1 leader = broker-1 → FetchRequest to broker-1
  (Two simultaneous requests to different brokers)

FetchRequest:
  maxWaitMs: 500             ← fetch.max.wait.ms
  minBytes: 1                ← fetch.min.bytes
  maxBytes: 52428800         ← fetch.max.bytes = 50 MB
  partitions: [
    {topic:"orders", partition:0, fetchOffset:500000, maxBytes:1048576}
  ]

FetchResponse:
  partitions: [
    {partition:0, highWatermark:500200, records: [batch of records...]}
  ]
```

---

## Eager vs Cooperative Rebalance

### Eager (Stop-the-World)

Used by: `RangeAssignor`, `RoundRobinAssignor`, `StickyAssignor`.

```
Consumer-D joins:
  ALL consumers stop processing
  ALL consumers call onPartitionsRevoked([all their partitions])
  ALL consumers send JoinGroup
  Assignment computed, SyncGroup
  ALL consumers call onPartitionsAssigned([new partitions])
  ALL consumers resume

Impact: 5-30 second processing gap on EVERY partition
        Even P2 and P3 (which stay with consumer-B) have a gap
```

### Cooperative (Incremental)

Used by: `CooperativeStickyAssignor` (recommended).

```
Consumer-D joins:
  Only consumer-A revokes P1 (the partition that must move)
  Consumer-B and C KEEP PROCESSING throughout
  Two-phase JoinGroup/SyncGroup

Impact: Only P1 has a brief gap
        P0, P2, P3, P4, P5: zero interruption
```

```java
// Enable cooperative rebalance:
props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
    CooperativeStickyAssignor.class.getName());
```

---

## Static Membership

```java
props.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, System.getenv("POD_NAME"));
```

**Dynamic (default):**
```
Consumer disconnects → coordinator starts session.timeout.ms countdown
Reconnects within timeout? → too late — member already declared dead → rebalance
Reconnects after timeout? → treated as brand new member → rebalance
```

**Static:**
```
Consumer disconnects → coordinator holds assignment for this group.instance.id
Reconnects within session.timeout.ms → gets back same partitions, NO rebalance
Rolling restart of 10 pods → 0 rebalances (vs 10 without static)
```

---

## The Complete Consumer Loop

```java
consumer.subscribe(List.of("orders"), rebalanceListener);

try {
    while (running) {
        // poll() does: rebalance handling, fetch, return buffered records
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));

        for (ConsumerRecord<String, String> record : records) {
            processRecord(record);  // your business logic
        }

        // Commit AFTER processing — at-least-once delivery
        consumer.commitAsync((offsets, ex) -> {
            if (ex != null) log.warn("Commit failed", ex);
        });
    }
} catch (WakeupException e) {
    // Triggered by consumer.wakeup() from another thread — clean shutdown
} finally {
    try {
        consumer.commitSync();  // final blocking commit on shutdown
    } finally {
        consumer.close();  // sends LeaveGroupRequest, closes connections
    }
}
```

---

## Key Configuration Reference

```properties
group.id=order-service-prod
bootstrap.servers=b1:9092,b2:9092,b3:9092

# Offset management
enable.auto.commit=false      # NEVER true in production
auto.offset.reset=latest      # or earliest

# Rebalance tuning
session.timeout.ms=45000
heartbeat.interval.ms=15000   # must be < session.timeout.ms / 3
max.poll.interval.ms=300000   # must be > max batch processing time
max.poll.records=500          # reduce if processing is slow

# Assignment strategy
partition.assignment.strategy=org.apache.kafka.clients.consumer.CooperativeStickyAssignor

# Static membership (for Kubernetes)
group.instance.id=             # set to pod name

# Fetch tuning
fetch.min.bytes=1
fetch.max.wait.ms=500
fetch.max.bytes=52428800       # 50 MB
max.partition.fetch.bytes=1048576  # 1 MB per partition

# Isolation (for transactional topics)
isolation.level=read_committed
```
