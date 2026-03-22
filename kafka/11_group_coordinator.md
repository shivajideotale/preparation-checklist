# 11 — Group Coordinator

> The broker component that manages consumer group membership, rebalances, and offset storage.

---

## What the Group Coordinator Is

The Group Coordinator is a **role** played by a specific broker — the one leading the `__consumer_offsets` partition assigned to a given consumer group.

```
Determining the Group Coordinator:
  partition = abs(groupId.hashCode()) % offsets.topic.num.partitions (50)
  Coordinator = leader of __consumer_offsets-{partition}

"order-service-prod".hashCode() → partition 14
Leader of __consumer_offsets-14 = Broker-2
→ Broker-2 is the Group Coordinator for "order-service-prod"
```

**Key responsibilities:**
- Group membership tracking
- Rebalance orchestration (JoinGroup / SyncGroup)
- Heartbeat monitoring (detect dead members)
- Offset storage (write to `__consumer_offsets`)
- Offset retrieval (serve committed offsets on join)

**NOT responsible for:**
- Routing message data (FetchRequest goes directly to partition leaders)
- Running the partition assignment algorithm (that runs client-side in the Group Leader consumer)

---

## Group State Machine

```
              ┌─────────────────────────┐
 (initial)    │          Empty          │
    ─────────►│  No active members      │◄──────────────────────────────────┐
              └────────────┬────────────┘                                   │
                           │ First JoinGroupRequest received                │
                           ▼                                                 │
              ┌─────────────────────────┐                                   │
              │   PreparingRebalance    │◄────────────────────────────────┐ │
              │  Collecting JoinGroup   │  Any membership change           │ │
              │  from all members       │  (join, leave, timeout, topic    │ │
              └────────────┬────────────┘   partition change)              │ │
                           │ All members joined OR rebalance.timeout.ms    │ │
                           ▼                                                │ │
              ┌─────────────────────────┐                                  │ │
              │  CompletingRebalance    │                                  │ │
              │  Awaiting SyncGroup     │                                  │ │
              │  from group leader      │                                  │ │
              └────────────┬────────────┘                                  │ │
                           │ SyncGroupResponse sent to all                 │ │
                           ▼                                                │ │
              ┌─────────────────────────┐                                  │ │
              │         Stable          │──────────────────────────────────┘ │
              │  Members processing     │                                     │
              │  Heartbeats received    │                                     │
              └────────────┬────────────┘                                     │
                           │ All members leave OR offset retention expires    │
                           ▼                                                  │
              ┌─────────────────────────┐                                    │
              │           Dead          │────────────────────────────────────┘
              │  Metadata can be        │  New JoinGroupRequest
              │  cleaned up             │  (creates fresh Empty group)
              └─────────────────────────┘
```

---

## All RPCs Handled by the Coordinator

### 1. FindCoordinator

**From**: Consumer, AdminClient  
**To**: Any broker  
**Purpose**: Discover which broker is the coordinator

```
Request:
  key: "order-service-prod"
  keyType: GROUP (0)

Response:
  coordinator: {nodeId: 2, host: "broker-2", port: 9092}

Error codes:
  COORDINATOR_LOAD_IN_PROGRESS (14): coordinator loading from __consumer_offsets → retry
  COORDINATOR_NOT_AVAILABLE (15):    __consumer_offsets partition has no leader → retry
```

### 2. JoinGroup

**From**: All consumers in the group  
**To**: Group Coordinator  
**Purpose**: Register membership, trigger rebalance, elect leader

```
Request (new member):
  groupId: "order-service-prod"
  sessionTimeoutMs: 45000
  rebalanceTimeoutMs: 300000
  memberId: ""           ← empty = new member
  groupInstanceId: null  ← null = dynamic membership
  protocols: [{
    name: "cooperative-sticky",
    metadata: {topics:["orders"], ownedPartitions:[...]}
  }]

Response for GROUP LEADER:
  generationId: 6
  leader: "consumer-A-uuid"
  memberId: "consumer-A-uuid"
  members: [{memberId:..., metadata:...}, ...]  ← full list

Response for non-leaders:
  generationId: 6
  leader: "consumer-A-uuid"
  memberId: "consumer-B-uuid"
  members: []  ← empty
```

### 3. SyncGroup

**From**: All consumers (leader sends assignment, others send empty)  
**To**: Group Coordinator  
**Purpose**: Distribute partition assignment

```
Leader's request:
  groupId: "order-service-prod"
  generationId: 6
  memberId: "consumer-A-uuid"
  assignments: [
    {memberId:"consumer-A", assignment:{partitions:[P0]}},
    {memberId:"consumer-B", assignment:{partitions:[P2,P3]}},
    ...
  ]

Non-leader's request:
  assignments: []  ← empty

Response to each member:
  assignment: {partitions: [their-slice]}

Group state: CompletingRebalance → Stable
```

### 4. Heartbeat

**From**: Consumer background HeartbeatThread  
**To**: Group Coordinator  
**Frequency**: Every `heartbeat.interval.ms` (default 3s)

```
Request:
  groupId: "order-service-prod"
  generationId: 6
  memberId: "consumer-B-uuid"

Response error codes:
  0 (NONE):                    All good, continue
  REBALANCE_IN_PROGRESS (27):  Stop fetching, send JoinGroupRequest
  ILLEGAL_GENERATION (22):     Stale generation, rejoin
  UNKNOWN_MEMBER_ID (25):      Session expired, rejoin as new member
```

**If coordinator receives no heartbeat within `session.timeout.ms`:**
```
Member "consumer-C" last heartbeat: t=0
t=45s: session.timeout.ms fires
Coordinator removes "consumer-C" from group
Group state: Stable → PreparingRebalance
Other members notified via next HeartbeatResponse: REBALANCE_IN_PROGRESS
```

### 5. LeaveGroup

**From**: Consumer calling `close()`  
**To**: Group Coordinator  
**Purpose**: Voluntary departure — triggers immediate rebalance

```
Request:
  groupId: "order-service-prod"
  members: [{memberId:"consumer-B-uuid", reason:"consumer close"}]

Coordinator:
  Removes member immediately
  Triggers rebalance (vs waiting session.timeout.ms for heartbeat to expire)

Note: With group.instance.id (static membership):
  consumer.close() does NOT send LeaveGroupRequest by default
  Assignment held for session.timeout.ms to allow fast reconnect
```

### 6. OffsetCommit

**From**: Consumer  
**To**: Group Coordinator  
**Purpose**: Persist committed offset checkpoint

```
Request:
  groupId: "order-service-prod"
  generationId: 6                ← stale generationId → ILLEGAL_GENERATION
  memberId: "consumer-B-uuid"
  topics: [{
    name: "orders",
    partitions: [{
      partitionIndex: 2,
      committedOffset: 45900,    ← "next to read" (last processed + 1)
      committedLeaderEpoch: 3,
      metadata: ""
    }]
  }]

Coordinator:
  Validates: generationId, memberId, topic-partition ownership
  Writes record to __consumer_offsets (local partition leader write)
  Waits for replication (min ISR)
  Returns: per-partition error codes

Response error codes for OffsetCommit:
  0:                         Success
  ILLEGAL_GENERATION (22):   Rebalance happened — commit rejected
  UNKNOWN_MEMBER_ID (25):    Member was removed (session timeout)
  NOT_COORDINATOR (16):      Coordinator changed — FindCoordinator again
```

### 7. OffsetFetch

**From**: Consumer (after rebalance or restart)  
**To**: Group Coordinator  
**Purpose**: Read last committed offsets

```
Request:
  groupId: "order-service-prod"
  partitions: [{topic:"orders", partition:2}, {topic:"orders", partition:3}]

Response:
  partitions: [
    {partition:2, committedOffset:45900, leaderEpoch:3},
    {partition:3, committedOffset:-1}   ← -1 = no committed offset
  ]

For -1: auto.offset.reset applies
  latest → seekToEnd
  earliest → seekToBeginning
  none → throw NoOffsetForPartitionException
```

### 8. DescribeGroups / ListGroups

**From**: AdminClient, CLI  
**To**: Group Coordinator  
**Purpose**: Inspect group state for monitoring/debugging

```bash
kafka-consumer-groups.sh --bootstrap-server broker:9092 \
  --describe --group order-service-prod

# Output:
# GROUP              TOPIC  PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  HOST
# order-svc-prod     orders 0          500000          500100          100  broker-1
# order-svc-prod     orders 1          250000          250050          50   broker-1
```

---

## Coordinator Failover

When the Coordinator broker fails:

```
t=0   Broker-2 (Coordinator for "order-service") crashes

t=0   Consumers' HeartbeatRequests to Broker-2 fail:
      ConnectionRefusedException / ConnectionTimeoutException
      ConsumerCoordinator marks coordinator as "unknown"

t=5s  Kafka Controller detects Broker-2 failure
      Triggers leader election for __consumer_offsets-14
      Broker-3 elected as new leader

t=6s  Broker-3 replays __consumer_offsets-14 from the beginning:
      Loads all committed offsets into memory
      Loads all group metadata into memory
      Full state restored from the log

t=10s Consumers retry FindCoordinatorRequest to bootstrap brokers
      Response: coordinator = Broker-3

t=11s Consumers send JoinGroupRequest to Broker-3
      Rebalance executes → assignment redistributed
      Consumers resume from last committed offsets

Total gap: ~10-15 seconds (depends on session.timeout.ms and leader election speed)
```

### Durability During Failover

```
Before Broker-2 crash:
  Consumer committed offset 45900 for orders-2
  Written to __consumer_offsets-14 with min.insync.replicas=2
  Broker-3 (follower of __consumer_offsets-14) has a replica

After failover:
  Broker-3 becomes leader of __consumer_offsets-14
  Replays log → offset 45900 loaded into memory
  Consumer reconnects → OffsetFetch returns 45900
  Consumer resumes from correct position — zero data loss
```

---

## Static Membership Deep Dive

```java
props.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, "order-service-pod-0");
props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 120000); // 2 minutes
```

**How it changes coordinator behavior:**

```
Dynamic membership (default):
  Consumer disconnects → coordinator timer starts (session.timeout.ms)
  Consumer reconnects within timeout → too late, member already removed → rebalance
  Consumer reconnects after timeout → treated as brand new member → rebalance

Static membership:
  Consumer disconnects → coordinator HOLDS assignment for group.instance.id
  Consumer reconnects with same group.instance.id within session.timeout.ms
    → Gets back original partitions
    → NO rebalance triggered
    → JoinGroupResponse includes prior assignment

  Consumer does NOT reconnect within session.timeout.ms
    → Member declared dead → rebalance → partitions reassigned
```

**LeaveGroup behavior:**
```java
// WITHOUT static membership: consumer.close() sends LeaveGroupRequest
// → Coordinator removes member immediately → rebalance triggered

// WITH static membership: consumer.close() does NOT send LeaveGroupRequest
// → Assignment held open for reconnect (up to session.timeout.ms)
// → For permanent scale-down, force LeaveGroup:
consumer.close(Duration.ZERO);  // forces LeaveGroupRequest even with static membership
```

**Kubernetes example:**
```yaml
# StatefulSet pod: order-service-0, order-service-1, order-service-2
# Pod name is stable across restarts
```
```java
props.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, System.getenv("POD_NAME"));
props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 60000); // 60s = longer than pod restart
// Rolling restart: pod restarts, reconnects within 60s, zero rebalances
```

---

## Multiple Groups on One Coordinator

One coordinator broker manages ALL consumer groups whose hash maps to its `__consumer_offsets` partitions.

```
With 50 __consumer_offsets partitions and 3 brokers:
  Broker-0 leads: partitions 0, 3, 6, 9, 12, ... (≈17 partitions)
  Broker-1 leads: partitions 1, 4, 7, 10, 13, ...
  Broker-2 leads: partitions 2, 5, 8, 11, 14, ...

With 300 consumer groups (evenly hashed):
  Each broker coordinates ≈100 groups
  
Heartbeat rate (100 groups × 10 members × 1/3s = 333 RPCs/broker/sec):
  This is negligible — coordinator is rarely the bottleneck.
```

Groups are managed completely independently. A rebalance in "order-service" has zero impact on "payment-service" even if they share the same coordinator broker.

---

## Coordinator vs Controller

These are completely separate concepts, often confused.

| Aspect | Group Coordinator | Kafka Controller |
|---|---|---|
| Count | ~17 per broker (50 partitions / 3 brokers) | Exactly 1 per cluster |
| What it manages | Consumer group lifecycle | Cluster partition topology |
| Storage | `__consumer_offsets` topic | ZooKeeper or `@metadata` (KRaft) |
| Handles | JoinGroup, Heartbeat, OffsetCommit | LeaderAndIsr, BrokerRegistration |
| Data plane | Zero — no message routing | Zero — no message routing |

---

## Key Configuration Summary

```properties
# Broker (affects all coordinators on this broker)
offsets.topic.num.partitions=50               # CANNOT CHANGE AFTER CREATION
offsets.topic.replication.factor=3
offsets.topic.min.isr=2
offsets.retention.minutes=10080               # 7 days offset expiry
group.min.session.timeout.ms=6000             # minimum session.timeout.ms consumers can request
group.max.session.timeout.ms=1800000          # maximum session.timeout.ms (30 min)
group.initial.rebalance.delay.ms=3000         # wait 3s for members to join before first rebalance

# Consumer (affects coordinator behavior)
group.id=order-service-prod
session.timeout.ms=45000
heartbeat.interval.ms=15000                   # must be < session.timeout.ms / 3
max.poll.interval.ms=300000                   # rebalance.timeout.ms sent in JoinGroupRequest
group.instance.id=                            # stable ID for static membership
partition.assignment.strategy=CooperativeStickyAssignor
```
