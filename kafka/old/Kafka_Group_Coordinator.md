# Kafka Group Coordinator — Deep Dive

> Everything about the Group Coordinator: what it is, how it is elected, every RPC it handles, the complete group state machine, offset storage internals, rebalance orchestration, failover, and static membership.

---

## Table of Contents

1. [What the Group Coordinator Is](#1-what-the-group-coordinator-is)
2. [How the Coordinator is Located](#2-how-the-coordinator-is-located)
3. [The __consumer_offsets Topic](#3-the-__consumer_offsets-topic)
4. [Group State Machine](#4-group-state-machine)
5. [The Generation ID](#5-the-generation-id)
6. [Every RPC the Coordinator Handles](#6-every-rpc-the-coordinator-handles)
   - [FindCoordinator](#61-findcoordinator)
   - [JoinGroup](#62-joingroup)
   - [SyncGroup](#63-syncgroup)
   - [Heartbeat](#64-heartbeat)
   - [LeaveGroup](#65-leavegroup)
   - [OffsetCommit](#66-offsetcommit)
   - [OffsetFetch](#67-offsetfetch)
   - [DescribeGroups / ListGroups](#68-describegroups--listgroups)
   - [DeleteGroups](#69-deletegroups)
7. [Rebalance Protocol — Step by Step](#7-rebalance-protocol--step-by-step)
8. [Cooperative vs Eager Rebalance](#8-cooperative-vs-eager-rebalance)
9. [Offset Storage Internals](#9-offset-storage-internals)
10. [Coordinator Failover](#10-coordinator-failover)
11. [Static Group Membership](#11-static-group-membership)
12. [Multiple Groups on One Coordinator](#12-multiple-groups-on-one-coordinator)
13. [Coordinator vs Controller — What's Different](#13-coordinator-vs-controller--whats-different)
14. [Monitoring the Group Coordinator](#14-monitoring-the-group-coordinator)
15. [Common Problems and Diagnostics](#15-common-problems-and-diagnostics)
16. [Configuration Reference](#16-configuration-reference)
17. [Quick Reference](#17-quick-reference)

---

## 1. What the Group Coordinator Is

The **Group Coordinator** is not a separate service or process. It is a **role** played by a specific broker — the one that is currently the leader of the `__consumer_offsets` partition assigned to a given consumer group.

### Responsibilities

| Responsibility | Details |
|---|---|
| Group membership tracking | Which consumer instances are alive, their IDs, assignment strategies |
| Rebalance orchestration | Drives JoinGroup → SyncGroup protocol, elects Group Leader |
| Heartbeat monitoring | Declares members dead if no heartbeat within `session.timeout.ms` |
| Offset persistence | Writes committed offsets to `__consumer_offsets` (durable) |
| Offset serving | Reads last committed offsets on consumer start or rebalance |
| Group metadata | Group state, generation ID, protocol type, assigned protocol name |

### What the Coordinator does NOT do

The Coordinator handles **control plane** operations only. It has **zero involvement** in data:

```
Data plane (bypasses Coordinator entirely):
  Consumer → FetchRequest  → Partition Leader Broker
  Producer → ProduceRequest → Partition Leader Broker

Control plane (goes to Coordinator):
  Consumer → FindCoordinatorRequest → Any Broker → "coordinator is broker-2"
  Consumer → JoinGroupRequest       → broker-2
  Consumer → SyncGroupRequest       → broker-2
  Consumer → HeartbeatRequest       → broker-2
  Consumer → OffsetCommitRequest    → broker-2
  Consumer → OffsetFetchRequest     → broker-2
```

This separation means Kafka can independently scale message throughput (add partition leaders) and group management capacity (add `__consumer_offsets` partitions) without interference.

---

## 2. How the Coordinator is Located

Every consumer group maps to exactly one `__consumer_offsets` partition, and the leader of that partition is the Coordinator.

### The mapping formula

```java
// Kafka source: GroupMetadataManager.scala
int coordinatorPartitionIndex = Math.abs(groupId.hashCode()) % 
                                 offsetsTopicNumPartitions;
// offsetsTopicNumPartitions = offsets.topic.num.partitions = 50 (default)
```

Example:
```
group.id = "order-service-prod"
"order-service-prod".hashCode() = 1234567890 (hypothetical)
abs(1234567890) % 50 = 40

→ __consumer_offsets partition 40
→ The broker currently leading __consumer_offsets-40 is the Coordinator
```

### FindCoordinator flow

```
Consumer startup:
  consumer → FindCoordinatorRequest(groupId="order-service-prod") → broker-1 (any bootstrap)

  broker-1 computes:
    partition = abs("order-service-prod".hashCode()) % 50 = 40
    leader of __consumer_offsets-40 = broker-2

  Response:
  FindCoordinatorResponse {
    throttleTimeMs: 0,
    errorCode: 0,
    coordinator: {
      nodeId: 2,
      host: "broker-2.kafka.svc.cluster.local",
      port: 9092
    }
  }

Consumer opens a dedicated TCP connection to broker-2.
All group management RPCs now go exclusively to broker-2.
```

### Why 50 partitions by default

The `offsets.topic.num.partitions` default of 50 distributes coordinator load. With 3 brokers and 50 partitions:
- Each broker leads approximately 50/3 ≈ 17 `__consumer_offsets` partitions
- Each broker acts as coordinator for approximately 1/3 of all consumer groups
- With 300 consumer groups: each broker coordinates ~100 groups

This cannot be changed after `__consumer_offsets` is created (first consumer group activity). Set it before production deployment.

---

## 3. The __consumer_offsets Topic

The Coordinator's storage backend. All group metadata and committed offsets live here.

### Topic configuration

```
Topic name: __consumer_offsets
Partitions: 50  (offsets.topic.num.partitions)
Replication factor: 3  (offsets.topic.replication.factor)
Min ISR: 2  (offsets.topic.min.isr)
Cleanup policy: compact  (compaction keeps latest value per key)
Segment size: 100 MB  (offsets.topic.segment.bytes)
Compression: none by default
```

### Record types stored

`__consumer_offsets` contains two types of records:

**Type 1: Offset commit records** (written by OffsetCommitRequest)

```
Key structure:
  version:   int16 = 1
  group:     string  ← consumer group ID
  topic:     string  ← topic name
  partition: int32   ← partition index

Value structure:
  version:         int16 = 1
  offset:          int64  ← committed offset (next to consume)
  leaderEpoch:     int32  ← partition leader epoch at commit time
  metadata:        string ← user-defined metadata string (usually "")
  commitTimestamp: int64  ← Unix timestamp of the commit
  expireTimestamp: int64  ← timestamp after which this offset can be deleted
```

**Type 2: Group metadata records** (written by JoinGroup/SyncGroup protocol)

```
Key structure:
  version: int16 = 2
  group:   string ← consumer group ID

Value structure:
  version:           int16
  protocolType:      string ← "consumer"
  generation:        int32  ← current generation ID
  protocol:          string ← assigned protocol name (e.g., "cooperative-sticky")
  leader:            string ← member ID of current group leader
  currentStateTimestamp: int64
  members: [
    {
      memberId:        string
      groupInstanceId: string  ← null if not static membership
      clientId:        string
      clientHost:      string
      rebalanceTimeout: int32
      sessionTimeout:  int32
      subscription:    bytes  ← serialized subscription info
      assignment:      bytes  ← serialized partition assignment
    }
  ]
```

### Viewing raw __consumer_offsets contents

```bash
# View offset commits (formatted)
kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic __consumer_offsets \
  --formatter "kafka.coordinator.group.GroupMetadataManager\$OffsetsMessageFormatter" \
  --from-beginning 2>/dev/null

# Sample output:
# [order-service-prod,orders,2]::OffsetAndMetadata[offset=45900,leaderEpoch=3,metadata=,commitTimestamp=1706789123456,expireTimestamp=-1]

# View group metadata records
kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic __consumer_offsets \
  --formatter "kafka.coordinator.group.GroupMetadataManager\$GroupMetadataMessageFormatter" \
  --from-beginning 2>/dev/null
```

### Compaction and offset expiry

`__consumer_offsets` uses log compaction, which retains the latest value per key. Old offset commits for the same (group, topic, partition) are cleaned up automatically.

**Offset expiry**: Committed offsets expire after `offsets.retention.minutes` (default 10,080 = 7 days) if the group has been inactive (no new commits). After expiry, tombstone records are written and the next compaction removes them.

```
t=0    Group "batch-job" commits offsets for all partitions
t=1d   Group "batch-job" has not committed since
t=7d   offsets.retention.minutes expires
       Coordinator writes tombstone (value=null) for each offset record
t=7d+  Next compaction removes the tombstone records from disk
t=8d   Group "batch-job" restarts, calls OffsetFetch → gets -1 (expired)
       auto.offset.reset applies
```

**Implication for weekly batch jobs**: Set `offsets.retention.minutes` higher than your job interval. For a job running every 14 days, set `offsets.retention.minutes=28800` (20 days).

---

## 4. Group State Machine

The Group Coordinator maintains an in-memory state machine for each group. States are also persisted to `__consumer_offsets` group metadata records for recovery.

### State diagram

```
                         ┌──────────────────────────────────────────────────────────────┐
                         │                                                              │
            ┌────────────▼──────────────┐                                              │
 (initial)  │          Empty            │                                              │
     ──────►│  No active members        │                                              │
            │  Only offset storage use  │                                              │
            └────────────┬──────────────┘                                              │
                         │ First JoinGroupRequest received                             │
                         ▼                                                              │
            ┌────────────────────────────┐                                             │
            │    PreparingRebalance      │◄──────────────────────────────────────────┐ │
            │  Collecting JoinGroups     │  Any membership change while Stable:      │ │
            │  from all current members  │  • New member joins                       │ │
            └────────────┬───────────────┘  • Member session times out               │ │
                         │ All members joined (or                                     │ │
                         │ rebalance.timeout.ms expired)                              │ │
                         ▼                                                             │ │
            ┌────────────────────────────┐                                            │ │
            │   CompletingRebalance      │                                            │ │
            │  Leader computing and      │                                            │ │
            │  submitting assignment     │                                            │ │
            └────────────┬───────────────┘                                            │ │
                         │ SyncGroupResponse sent to all members                      │ │
                         ▼                                                             │ │
            ┌────────────────────────────┐                                            │ │
            │          Stable            │────────────────────────────────────────────┘ │
            │  Members processing,       │                                              │
            │  heartbeating, committing  │                                              │
            └────────────┬───────────────┘                                              │
                         │ All members leave OR offset expiry                           │
                         ▼                                                              │
            ┌────────────────────────────┐                                              │
            │           Dead             │──────────────────────────────────────────────┘
            │  Group purged, metadata    │  New member arrives after death
            │  can be cleaned up         │  (creates fresh Empty group)
            └────────────────────────────┘
```

### State descriptions

#### Empty
- No active group members
- Offset storage may still have records (group exists in `__consumer_offsets` for offset-only use)
- Transitions to PreparingRebalance when first JoinGroupRequest arrives
- Valid to commit offsets in this state (when group uses `assign()` mode without group membership)

#### PreparingRebalance
- A rebalance has been triggered
- Coordinator broadcasts "rebalance needed" via HeartbeatResponse error codes
- Waiting for **all** current members to submit a new JoinGroupRequest
- Waits up to `rebalance.timeout.ms` (default 5 minutes) for late members
- Members who don't join in time are removed from the group

**Triggers from Stable → PreparingRebalance:**
- New consumer calls JoinGroupRequest (group is growing)
- `session.timeout.ms` fires for a member (no heartbeat received)
- `max.poll.interval.ms` fires for a member (not calling poll() fast enough)
- Consumer calls `consumer.close()` → sends LeaveGroupRequest
- Subscribed topic gains new partitions (metadata change)
- Administrator triggers rebalance (via AdminClient or CLI)

**Triggers from Empty → PreparingRebalance:**
- First consumer ever joins the group

#### CompletingRebalance
- All JoinGroupRequests collected
- Group Leader elected (the consumer that joined first, or the existing leader if still present)
- Coordinator sent JoinGroupResponse to all members
  - Leader receives: full member list + their subscriptions
  - Others receive: empty member list (just confirmation they're in the group)
- Waiting for SyncGroupRequest from all members (especially the leader's assignment)
- Transitions to Stable once SyncGroupResponse sent to all

#### Stable
- Normal operating state
- All members have partition assignments
- Heartbeats are arriving within `session.timeout.ms`
- OffsetCommit and OffsetFetch requests are served normally
- Any membership change immediately triggers transition to PreparingRebalance

#### Dead
- The group has no members AND has been inactive past `offsets.retention.minutes`
- Coordinator may purge the group metadata record entirely
- Tombstones written to `__consumer_offsets` for all offset records
- New JoinGroupRequest after death: treated as a fresh group in Empty state

---

## 5. The Generation ID

The **generation ID** is an integer counter that monotonically increases with every rebalance. It starts at 1 when the group first forms.

### Purpose: preventing stale requests

```
t=0   Group in generation 5. consumer-A assigned orders-2.
t=1   New consumer joins → rebalance → generation becomes 6.
      consumer-B is now assigned orders-2.
t=2   consumer-A was slow — it just finished processing its batch.
      consumer-A tries to commit offsets for orders-2:
        OffsetCommitRequest { generationId: 5, memberId: "consumer-A", ... }
      
      Coordinator rejects: ILLEGAL_GENERATION
      (Coordinator is in generation 6 — generation 5 requests are stale)

      Why this is important:
      consumer-B has already started consuming orders-2 from offset X.
      If consumer-A's stale commit of offset X+100 succeeded,
      consumer-B would skip 100 records on its next restart.
```

### Generation ID in every group management RPC

The generation ID is included in:
- `JoinGroupResponse` → tells consumer which generation they joined
- `SyncGroupRequest/Response` → validates correct generation
- `HeartbeatRequest` → Coordinator detects stale heartbeats
- `OffsetCommitRequest` → Coordinator rejects stale commits
- `LeaveGroupRequest` → identifies the leaving generation

---

## 6. Every RPC the Coordinator Handles

### 6.1 FindCoordinator

**Direction**: Consumer → Any Bootstrap Broker

**Purpose**: Discover which broker is the Group Coordinator

```
Request:
FindCoordinatorRequest {
  key: "order-service-prod",  // group.id
  keyType: 0                   // 0 = GROUP, 1 = TRANSACTION
}

Response:
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

**Error codes:**
- `COORDINATOR_LOAD_IN_PROGRESS` (14): The coordinator is still loading its state from `__consumer_offsets`. Retry after backoff.
- `COORDINATOR_NOT_AVAILABLE` (15): `__consumer_offsets` partition has no leader yet. Retry after backoff.

---

### 6.2 JoinGroup

**Direction**: All consumers in the group → Group Coordinator

**Purpose**: Register membership, trigger rebalance, elect Group Leader

```
Request:
JoinGroupRequest {
  groupId: "order-service-prod",
  sessionTimeoutMs: 45000,         // consumer's session.timeout.ms
  rebalanceTimeoutMs: 300000,      // consumer's max.poll.interval.ms
  memberId: "",                    // "" = new member, existing = prior member ID
  groupInstanceId: null,           // set for static membership
  protocolType: "consumer",
  protocols: [
    {
      name: "cooperative-sticky",
      metadata: {
        version: 1,
        topics: ["orders"],
        userData: {                 // current assignments for sticky algo
          ownedPartitions: [
            {topic: "orders", partitions: [2, 3]}
          ]
        }
      }
    }
  ]
}

Response for GROUP LEADER:
JoinGroupResponse {
  throttleTimeMs: 0,
  errorCode: 0,
  generationId: 6,
  protocolType: "consumer",
  protocolName: "cooperative-sticky",  // protocol all members support
  leader: "consumer-abc-uuid",
  memberId: "consumer-abc-uuid",
  members: [                           // ONLY leader gets this list
    {memberId: "consumer-abc-uuid", metadata: {...}},
    {memberId: "consumer-def-uuid", metadata: {...}},
    {memberId: "consumer-ghi-uuid", metadata: {...}}
  ]
}

Response for NON-LEADER members:
JoinGroupResponse {
  generationId: 6,
  leader: "consumer-abc-uuid",
  memberId: "consumer-def-uuid",
  members: []                          // empty for non-leaders
}
```

**Coordinator logic — group leader election:**
1. If the previous leader is still present in this round → keep the same leader
2. If the previous leader left or timed out → pick the first member alphabetically by member ID (deterministic)

**Protocol selection:**
The Coordinator picks the protocol supported by ALL members. With `CooperativeStickyAssignor`, all consumers must support it. If you have a mixed deployment (some consumers with old `StickyAssignor`, some with new `CooperativeStickyAssignor`), the Coordinator picks the common denominator (`StickyAssignor`), forgoing cooperative behavior.

---

### 6.3 SyncGroup

**Direction**: All consumers → Group Coordinator

**Purpose**: Submit the computed assignment (leader only) and receive individual assignment (all)

```
Leader's request:
SyncGroupRequest {
  groupId: "order-service-prod",
  generationId: 6,
  memberId: "consumer-abc-uuid",
  groupInstanceId: null,
  protocolType: "consumer",
  protocolName: "cooperative-sticky",
  assignments: [
    {
      memberId: "consumer-abc-uuid",
      assignment: {
        partitions: [
          {topic: "orders", partitions: [0, 1]}
        ],
        userData: null
      }
    },
    {
      memberId: "consumer-def-uuid",
      assignment: {
        partitions: [{topic: "orders", partitions: [2, 3]}]
      }
    },
    {
      memberId: "consumer-ghi-uuid",
      assignment: {
        partitions: [{topic: "orders", partitions: [4, 5]}]
      }
    }
  ]
}

Non-leader's request (empty assignments):
SyncGroupRequest {
  groupId: "order-service-prod",
  generationId: 6,
  memberId: "consumer-def-uuid",
  assignments: []    // non-leader sends empty
}

Response (specific to each member):
SyncGroupResponse {
  throttleTimeMs: 0,
  errorCode: 0,
  protocolType: "consumer",
  protocolName: "cooperative-sticky",
  assignment: {
    partitions: [{topic: "orders", partitions: [2, 3]}]
  }
}
```

After SyncGroup, the Coordinator:
1. Writes group metadata (including assignment) to `__consumer_offsets`
2. Transitions group state to `Stable`
3. Notifies the heartbeat mechanism that the generation is now valid

---

### 6.4 Heartbeat

**Direction**: Consumer background thread → Group Coordinator

**Purpose**: Liveness signal; receive rebalance notifications

```
Request (sent every heartbeat.interval.ms = 3000ms):
HeartbeatRequest {
  groupId: "order-service-prod",
  generationId: 6,
  memberId: "consumer-def-uuid",
  groupInstanceId: null
}

Response — normal operation:
HeartbeatResponse {
  throttleTimeMs: 0,
  errorCode: 0    // NONE — continue normally
}

Response — rebalance triggered:
HeartbeatResponse {
  errorCode: 27   // REBALANCE_IN_PROGRESS
}
// Consumer: stop fetching, call onPartitionsRevoked(), send JoinGroupRequest

Response — stale generation:
HeartbeatResponse {
  errorCode: 22   // ILLEGAL_GENERATION
}
// Consumer: rejoin the group immediately

Response — session timed out, coordinator lost member record:
HeartbeatResponse {
  errorCode: 25   // UNKNOWN_MEMBER_ID
}
// Consumer: set memberId = "", rejoin as new member
```

**What happens if the Coordinator receives no heartbeat:**
```
t=0   consumer-def's last heartbeat received by Coordinator
t=45s session.timeout.ms fires
      Coordinator removes "consumer-def" from group
      Coordinator transitions group: Stable → PreparingRebalance
      Coordinator sends REBALANCE_IN_PROGRESS to remaining members
      consumer-def's partitions [2, 3] will be reassigned
```

**Critical distinction:**
- `session.timeout.ms` is monitored by the **Coordinator** (server-side)
- `heartbeat.interval.ms` controls how often the **consumer** sends heartbeats (client-side)
- `max.poll.interval.ms` is enforced by the **consumer** itself — if poll() is not called in time, the consumer proactively sends LeaveGroupRequest

---

### 6.5 LeaveGroup

**Direction**: Consumer → Group Coordinator

**Purpose**: Voluntary departure — immediately triggers rebalance (much faster than timeout)

```
Request:
LeaveGroupRequest {
  groupId: "order-service-prod",
  memberId: "consumer-def-uuid",    // classic single-member leave
  members: [                         // batch leave (Kafka 2.4+)
    {
      memberId: "consumer-def-uuid",
      groupInstanceId: null,
      reason: "consumer close"
    }
  ]
}

Response:
LeaveGroupResponse {
  throttleTimeMs: 0,
  errorCode: 0,
  members: [
    {memberId: "consumer-def-uuid", errorCode: 0}
  ]
}
```

**When LeaveGroup is sent:**
- `consumer.close()` called explicitly
- `consumer.close(Duration.ZERO)` — immediate close
- JVM shutdown hook (if consumer registered one)

**When LeaveGroup is NOT sent:**
- JVM crash (OOM, SIGKILL) — no shutdown hook runs
- Network partition — cannot reach Coordinator
- Static membership (`group.instance.id` set) — `consumer.close()` does NOT send LeaveGroup by default, preserving the assignment for reconnect

**Why LeaveGroup matters:**
Without LeaveGroup, the Coordinator waits `session.timeout.ms` (45 seconds) before removing the member. With LeaveGroup, the rebalance starts immediately. For rolling deployments, LeaveGroup reduces total rebalance time from `num_pods × 45s` to a few seconds.

---

### 6.6 OffsetCommit

**Direction**: Consumer → Group Coordinator

**Purpose**: Persist the consumer's position checkpoint in `__consumer_offsets`

```
Request:
OffsetCommitRequest {
  groupId: "order-service-prod",
  generationId: 6,                  // current generation — stale commits rejected
  memberId: "consumer-def-uuid",
  groupInstanceId: null,
  retentionTimeMs: -1,             // -1 = use offsets.retention.minutes
  topics: [
    {
      name: "orders",
      partitions: [
        {
          partitionIndex: 2,
          committedOffset: 45900,
          committedLeaderEpoch: 3,
          metadata: "",             // optional user metadata string
          commitTimestamp: -1       // -1 = use broker timestamp
        },
        {
          partitionIndex: 3,
          committedOffset: 850,
          committedLeaderEpoch: 2,
          metadata: "",
          commitTimestamp: -1
        }
      ]
    }
  ]
}

Response:
OffsetCommitResponse {
  throttleTimeMs: 0,
  topics: [
    {
      name: "orders",
      partitions: [
        {partitionIndex: 2, errorCode: 0},
        {partitionIndex: 3, errorCode: 0}
      ]
    }
  ]
}
```

**What the Coordinator does on receiving OffsetCommitRequest:**
1. Validates `generationId` matches current group generation → else `ILLEGAL_GENERATION`
2. Validates `memberId` is a known member → else `UNKNOWN_MEMBER_ID`
3. Validates consumer owns the partitions being committed → else `NOT_COORDINATOR`
4. Writes offset records to its own `__consumer_offsets` partition (which it leads)
5. Waits for replication (min ISR) before responding
6. Responds with per-partition error codes

**OffsetCommit error codes:**

| Error Code | Meaning | Action |
|---|---|---|
| 0 (NONE) | Success | Continue |
| 22 (ILLEGAL_GENERATION) | Rebalance happened since last JoinGroup | Rejoin group |
| 25 (UNKNOWN_MEMBER_ID) | Member was removed (e.g., session timeout) | Rejoin as new member |
| 27 (REBALANCE_IN_PROGRESS) | Rebalance currently in progress | Stop, rejoin |
| 14 (COORDINATOR_LOAD_IN_PROGRESS) | Coordinator loading state | Retry with backoff |
| 16 (NOT_COORDINATOR) | This broker is no longer coordinator | FindCoordinator again |

---

### 6.7 OffsetFetch

**Direction**: Consumer → Group Coordinator

**Purpose**: Read last committed offsets after rebalance or restart

```
Request:
OffsetFetchRequest {
  groupId: "order-service-prod",
  requireStable: false,            // true = wait for pending txn commits
  groups: [
    {
      groupId: "order-service-prod",
      topics: [
        {
          name: "orders",
          partitionIndexes: [2, 3]
        }
      ]
    }
  ]
}

Response:
OffsetFetchResponse {
  throttleTimeMs: 0,
  groups: [
    {
      groupId: "order-service-prod",
      topics: [
        {
          name: "orders",
          partitions: [
            {
              partitionIndex: 2,
              committedOffset: 45900,     // last committed offset
              committedLeaderEpoch: 3,
              metadata: "",
              errorCode: 0
            },
            {
              partitionIndex: 3,
              committedOffset: -1,         // -1 = no committed offset
              committedLeaderEpoch: -1,
              metadata: "",
              errorCode: 0
            }
          ]
        }
      ],
      errorCode: 0
    }
  ]
}
```

**`requireStable: true`**: When using Kafka transactions with `isolation.level=read_committed`, the consumer should pass `requireStable=true`. This causes the Coordinator to wait until all pending transactions are resolved before returning offsets, preventing a consumer from resuming at an offset within an uncommitted transaction.

---

### 6.8 DescribeGroups / ListGroups

**Direction**: AdminClient / CLI → Group Coordinator

**Purpose**: Inspect group state, members, assignments, lag

```bash
# CLI
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group order-service-prod

# Output:
GROUP               TOPIC  PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID
order-service-prod  orders 0          500000          500100          100  consumer-abc-...
order-service-prod  orders 1          250000          250050          50   consumer-abc-...
order-service-prod  orders 2          45900           46000           100  consumer-def-...
...
```

**Internal flow:**
1. AdminClient calls `FindCoordinator` for the group → gets coordinator broker
2. AdminClient calls `DescribeGroupsRequest` to that specific coordinator
3. Coordinator responds with GroupDescription:
   - Group state (Stable, PreparingRebalance, etc.)
   - Protocol type and name
   - Members with their client IDs, host addresses, assigned partitions
4. AdminClient separately calls `ListOffsetsRequest` to partition leaders to get LOG-END-OFFSET
5. LAG = LOG-END-OFFSET - CURRENT-OFFSET computed client-side

**ListGroups** returns all groups managed by a specific coordinator broker. AdminClient fans out ListGroups to all brokers and aggregates.

---

### 6.9 DeleteGroups

**Direction**: AdminClient → Group Coordinator

**Purpose**: Delete a consumer group and all its committed offsets

```java
// Java AdminClient
adminClient.deleteConsumerGroups(List.of("old-consumer-group"));
```

**Coordinator behavior:**
1. Validates the group is in `Empty` or `Dead` state (all members must have left)
2. If members are still active: returns `NON_EMPTY_GROUP` error
3. Writes tombstone records (value=null) to `__consumer_offsets` for all offsets
4. Removes the group metadata record
5. Transitions group to `Dead`

```bash
# CLI
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --delete --group old-consumer-group
```

---

## 7. Rebalance Protocol — Step by Step

### Full rebalance sequence (three consumers, one new joiner)

```
INITIAL STATE:
  Group: order-service-prod, Generation: 5, State: Stable
  consumer-A → [orders-0, orders-1]
  consumer-B → [orders-2, orders-3]
  consumer-C → [orders-4, orders-5]

EVENT: consumer-D starts and calls subscribe() + poll()
─────────────────────────────────────────────────────────

STEP 1: consumer-D sends FindCoordinatorRequest
  → any broker responds: coordinator = broker-2

STEP 2: consumer-D sends JoinGroupRequest to broker-2
  memberId: "" (new member)
  protocols: [cooperative-sticky, ...]

STEP 3: Coordinator receives JoinGroupRequest from consumer-D
  Group state: Stable → PreparingRebalance
  Coordinator sends REBALANCE_IN_PROGRESS via HeartbeatResponse to A, B, C

STEP 4: A, B, C receive REBALANCE_IN_PROGRESS in heartbeat
  Each consumer:
    - Stops fetching new messages (completes current batch)
    - Calls onPartitionsRevoked() callback
    - With cooperative: revokes only partitions that will move
    - With eager: revokes ALL assigned partitions
    - Sends JoinGroupRequest to Coordinator

STEP 5: Coordinator collects JoinGroupRequest from A, B, C, D
  (Waits for all 4 within rebalance.timeout.ms = 300s)
  Assigns new member IDs
  Selects group leader: consumer-A (existing leader retained if present)
  Increments generation: 5 → 6

STEP 6: Coordinator sends JoinGroupResponse to ALL members
  A receives: {generationId:6, leader:"consumer-A", members:[A,B,C,D-with-subscriptions]}
  B receives: {generationId:6, leader:"consumer-A", members:[]}
  C receives: {generationId:6, leader:"consumer-A", members:[]}
  D receives: {generationId:6, leader:"consumer-A", members:[]}

STEP 7: consumer-A (GROUP LEADER) runs assignment algorithm
  Input: 4 members, 6 partitions, existing assignments from members' metadata
  CooperativeStickyAssignor:
    Currently: A=[0,1], B=[2,3], C=[4,5], D=[]
    Target: A=[0], B=[2,3], C=[4,5], D=[1]
    Changes: A drops partition 1 → D gets partition 1

STEP 8: consumer-A sends SyncGroupRequest with full assignment
  B, C, D send SyncGroupRequest with empty assignments

STEP 9: Coordinator stores assignment, transitions: CompletingRebalance → Stable
  Responds to each consumer:
  A: {assignment: [orders-0]}
  B: {assignment: [orders-2, orders-3]}
  C: {assignment: [orders-4, orders-5]}
  D: {assignment: [orders-1]}

STEP 10: Each consumer calls onPartitionsAssigned() with new partitions
  D calls OffsetFetchRequest for orders-1 → starts fetching

FINAL STATE:
  Group: order-service-prod, Generation: 6, State: Stable
  A → [orders-0]
  B → [orders-2, orders-3]
  C → [orders-4, orders-5]
  D → [orders-1]
```

---

## 8. Cooperative vs Eager Rebalance

### Eager rebalance (old default — RangeAssignor, RoundRobinAssignor)

```
During rebalance:
  ALL consumers STOP processing
  ALL consumers revoke ALL their partitions
  onPartitionsRevoked([ALL partitions]) called on everyone
  JoinGroup → SyncGroup completes
  onPartitionsAssigned() called on everyone
  ALL consumers resume

Impact with 10 consumers, 50 partitions:
  Rebalance takes 5-10 seconds
  ALL 50 partitions see a processing gap
  Even the 48 partitions that don't change assignment have a gap
```

### Cooperative (incremental) rebalance — CooperativeStickyAssignor

```
During rebalance:
  ONLY the partitions that MUST MOVE are revoked
  Other partitions continue processing uninterrupted

Phase 1:
  Consumers that own partitions that need to move → revoke only those
  Other consumers keep their partitions and keep processing
  JoinGroup + SyncGroup (round 1)

Phase 2:
  Newly freed partitions assigned to consumers that need them
  JoinGroup + SyncGroup (round 2)
  New partition owners call onPartitionsAssigned()

Impact with 10 consumers, 50 partitions, 1 partition moving:
  Only 1 partition sees any gap
  Other 49 partitions: zero interruption
```

### Configuration

```java
// In consumer properties
props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
    CooperativeStickyAssignor.class.getName());
```

### Mixed cluster migration (from StickyAssignor to CooperativeStickyAssignor)

When migrating from eager to cooperative without downtime:

```java
// Step 1: Deploy with both strategies listed (StickyAssignor first)
props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
    StickyAssignor.class.getName() + "," +
    CooperativeStickyAssignor.class.getName());
// Result: Coordinator picks StickyAssignor (supported by all)
// All consumers upgrade. Group uses StickyAssignor for now.

// Step 2: Remove StickyAssignor, keep only CooperativeStickyAssignor
props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
    CooperativeStickyAssignor.class.getName());
// Once all consumers upgraded: Coordinator picks CooperativeStickyAssignor
// Full cooperative rebalance now active
```

This two-step migration avoids having a mixed cluster where some consumers support cooperative and some don't, which would force the Coordinator to fall back to eager.

---

## 9. Offset Storage Internals

### How offsets are stored

```
OffsetCommitRequest received by Coordinator:
  (group="order-service-prod", topic="orders", partition=2, offset=45900)

Coordinator produces a record to its own __consumer_offsets partition:

  Key bytes:   [version=1][group="order-service-prod"][topic="orders"][partition=2]
  Value bytes: [version=1][offset=45900][leaderEpoch=3][metadata=""][timestamp=1706789123456]

  This is a normal Kafka produce to the local partition:
  - Written to page cache immediately
  - Replicated to followers
  - High Watermark advances
  - OffsetCommitResponse sent to consumer only after HW advances
    (ensuring offset is on at least min.insync.replicas replicas)
```

### How offsets are read

The Coordinator maintains an **in-memory cache** loaded at startup by replaying `__consumer_offsets`. On OffsetFetchRequest:

```java
// Coordinator in-memory cache:
Map<GroupTopicPartition, OffsetAndMetadata> offsetCache = {
  (order-service-prod, orders, 0) → {offset: 500000, ...},
  (order-service-prod, orders, 1) → {offset: 250000, ...},
  (order-service-prod, orders, 2) → {offset: 45900, ...},
  ...
}

// OffsetFetch is served from this in-memory cache
// No disk read needed (barring cache miss, e.g., after coordinator failover)
```

### Compaction effect on __consumer_offsets

Since `__consumer_offsets` uses compaction, only the **latest offset per (group, topic, partition)** is retained on disk:

```
Before compaction:
  (order-service-prod, orders, 2) → offset=45000  [old]
  (order-service-prod, orders, 2) → offset=45500  [old]
  (order-service-prod, orders, 2) → offset=45900  [current]

After compaction:
  (order-service-prod, orders, 2) → offset=45900  [only latest kept]
```

This keeps `__consumer_offsets` small and startup replay time short.

### Offset storage for manual assignment (assign() mode)

Groups using `consumer.assign()` (no group protocol, no coordinator membership) can still store offsets in `__consumer_offsets`:

```java
consumer.assign(List.of(new TopicPartition("orders", 0)));
// ... process records ...
consumer.commitSync(); // stores to __consumer_offsets without group membership
```

For `assign()` mode, OffsetCommitRequest uses `generationId=-1` and `memberId=""` (no group membership validation). The Coordinator stores these offsets under the `group.id` as a standalone entry.

---

## 10. Coordinator Failover

### What happens when the Coordinator broker dies

```
INITIAL STATE:
  broker-2 leads __consumer_offsets-40
  broker-2 is Coordinator for group "order-service-prod"
  3 consumers connected and processing normally

t=0   broker-2 crashes (OOM, hardware failure, network partition)

t=0   Consumers' HeartbeatRequest to broker-2 fails:
        java.net.ConnectException or ConnectionRefusedException
      ConsumerCoordinator marks coordinator as "unknown"
      Consumer NetworkClient starts retry with exponential backoff

t=0   Kafka Controller detects broker-2 failure:
        ZooKeeper ephemeral node /brokers/ids/2 expires (session timeout)
        OR KRaft Raft heartbeat timeout
      Controller initiates leader election for all partitions led by broker-2
      __consumer_offsets-40: new leader elected (say broker-3)

t=5s  broker-3 replays __consumer_offsets-40 log:
        Loads all offset records into memory
        Loads all group metadata records into memory
        Coordinator state fully restored from log

t=10s Consumer retries FindCoordinatorRequest to any available broker:
        Response: coordinator = broker-3 (new leader of __consumer_offsets-40)
      Consumer opens new TCP connection to broker-3

t=11s Consumer sends JoinGroupRequest to broker-3:
        Group was lost from broker-3's memory (it's a fresh state)
        Triggers a full rebalance
        
t=15s Rebalance completes — all consumers reassigned
      Processing resumes

TOTAL DOWNTIME FOR CONSUMERS: ~10-20 seconds
  (depends on session.timeout.ms and ZK/KRaft election speed)
```

### Durability of committed offsets through failover

```
Before broker-2 crash:
  consumer committed offset 45900 for orders-2
  This was written to __consumer_offsets-40 with min.insync.replicas=2
  broker-3 (follower) has a replica of __consumer_offsets-40
  → offset 45900 is safe on broker-3

After failover:
  broker-3 becomes leader of __consumer_offsets-40
  Replays log → offset 45900 loaded into memory
  Consumer reconnects → OffsetFetch returns 45900
  Consumer resumes from correct position — no data loss, no replay
```

### Reducing impact with static membership

```java
// Consumer config — stable pod identity
props.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, System.getenv("POD_NAME"));
props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 120000); // 2 minutes
```

With `group.instance.id` set, a consumer that disconnects from the coordinator (e.g., during failover) and reconnects within `session.timeout.ms` gets back its prior partitions **without triggering a rebalance**:

```
Scenario: Coordinator failover with static membership

t=0   broker-2 dies. Consumers lose connection to coordinator.
t=5s  broker-3 becomes new coordinator, loads state.
t=10s consumer-A (pod="order-service-0") reconnects:
        JoinGroupRequest { groupInstanceId: "order-service-0", memberId: "prior-id" }
        New coordinator sees "order-service-0" with matching prior assignment
        Returns existing assignment: [orders-0, orders-1]
        NO REBALANCE TRIGGERED

t=10s consumer-B (pod="order-service-1") reconnects:
        Same — gets back [orders-2, orders-3] without rebalance

t=10s consumer-C (pod="order-service-2") reconnects:
        Same — gets back [orders-4, orders-5] without rebalance

RESULT: No rebalance at all. All consumers resume from prior position.
```

---

## 11. Static Group Membership

Static membership (`group.instance.id`) fundamentally changes how the Coordinator treats disconnects.

### Dynamic membership (default)

```
Consumer disconnects → Coordinator starts session.timeout.ms countdown
Consumer doesn't reconnect within timeout → member declared dead → rebalance
Consumer reconnects → treated as a BRAND NEW member → gets new member ID → rebalance
```

### Static membership

```java
props.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, "order-service-pod-0");
```

```
Consumer disconnects → Coordinator holds the partition assignment for this instanceId
  (does NOT start session.timeout.ms countdown immediately in the same way)
Consumer reconnects with same group.instance.id → gets back its prior partitions
  (if reconnects within session.timeout.ms — NO rebalance)
Consumer reconnects after session.timeout.ms → member was evicted → rebalance
```

### LeaveGroup behavior with static membership

By default, `consumer.close()` with static membership does NOT send `LeaveGroupRequest`. The Coordinator holds the member slot open until `session.timeout.ms` expires. This allows quick restart without triggering rebalance.

To force immediate departure (e.g., permanent scale-down):

```java
// Force LeaveGroup even with static membership
consumer.close(Duration.ZERO); // immediate close, force sends LeaveGroup
```

### Kubernetes deployment example

```java
// StatefulSet pods have stable names: order-service-0, order-service-1, etc.
String podName = System.getenv("POD_NAME"); // from Downward API

Properties props = new Properties();
props.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, podName);
props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 60000); // 60s — enough for pod restart
props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 20000);

// Result: rolling restart of 10 pods = 0 rebalances
// (each pod restarts and reconnects before session.timeout.ms expires)
```

---

## 12. Multiple Groups on One Coordinator

One coordinator broker manages ALL consumer groups whose hash maps to its `__consumer_offsets` partitions.

```
Broker-1 leads: __consumer_offsets-0, 3, 6, 9, 12, 15, 18, 21, 24, 27, ...
              → Coordinator for groups: payment-service, recommendation-engine, ...

Broker-2 leads: __consumer_offsets-1, 4, 7, 10, 13, 16, 19, 22, 25, 28, ...
              → Coordinator for groups: order-service, fraud-detection, ...

Broker-3 leads: __consumer_offsets-2, 5, 8, 11, 14, 17, 20, 23, 26, 29, ...
              → Coordinator for groups: analytics-pipeline, audit-service, ...
```

Each group is managed completely independently. A rebalance in `order-service` has zero impact on `payment-service`, even if they share the same coordinator broker.

### Coordinator load with many groups

A coordinator broker with 100 active consumer groups, each with 10 members sending heartbeats every 3 seconds:

```
Heartbeat rate = 100 groups × 10 members × (1 / 3s) = ~333 RPCs/second per coordinator

With 3 brokers and 50 __consumer_offsets partitions:
  Each broker = ~17 coordinator partitions
  Heartbeat rate per broker ≈ 333 / 3 ≈ 110 RPS (trivial)
```

The coordinator is rarely the bottleneck. Each group's heartbeat + offset commit traffic is minimal compared to data throughput.

---

## 13. Coordinator vs Controller — What's Different

These two "coordinator" concepts are completely separate:

| Aspect | Group Coordinator | Kafka Controller |
|---|---|---|
| What it is | Leader of one `__consumer_offsets` partition | One broker with cluster-wide control role |
| Scope | One consumer group | Entire Kafka cluster |
| How elected | Leader election of `__consumer_offsets-N` | ZooKeeper ephemeral node or Raft leader (KRaft) |
| Count per cluster | One per `__consumer_offsets` partition (50 by default) | Always exactly ONE |
| Manages | Consumer membership, offsets, rebalance | Partition leaders, ISR, broker lifecycle |
| Handles | JoinGroup, SyncGroup, Heartbeat, OffsetCommit | LeaderAndIsr, StopReplica, UpdateMetadata |
| Storage | `__consumer_offsets` topic | `__cluster_metadata` (KRaft) or ZooKeeper |
| Data access | No involvement in message reads/writes | No involvement in message reads/writes |

Both are control-plane services. Neither touches message data (FetchRequest / ProduceRequest).

---

## 14. Monitoring the Group Coordinator

### Key JMX metrics

```
# Per-broker coordinator metrics
kafka.coordinator.group:type=GroupMetadataManager,name=NumOffsets
  → Total committed offsets across all groups on this coordinator
kafka.coordinator.group:type=GroupMetadataManager,name=NumGroups
  → Number of groups this coordinator manages
kafka.coordinator.group:type=GroupMetadataManager,name=NumGroupsPreparingRebalance
  → Groups currently in rebalance (should be near 0 in steady state)
kafka.coordinator.group:type=GroupMetadataManager,name=NumGroupsCompletingRebalance
  → Groups waiting for SyncGroup (should be near 0)

# Network metrics for coordinator RPCs
kafka.network:type=RequestMetrics,name=RequestsPerSec,request=JoinGroup
kafka.network:type=RequestMetrics,name=RequestsPerSec,request=SyncGroup
kafka.network:type=RequestMetrics,name=RequestsPerSec,request=Heartbeat
kafka.network:type=RequestMetrics,name=RequestsPerSec,request=OffsetCommit
kafka.network:type=RequestMetrics,name=TotalTimeMs,request=JoinGroup  → rebalance latency
```

### Consumer-side metrics

```
kafka.consumer:type=consumer-coordinator-metrics,client-id=*
  last-rebalance-seconds-ago       → time since last rebalance (high = stable)
  rebalance-rate-per-hour          → should approach 0 in stable operation
  rebalance-latency-max            → max rebalance duration in ms
  commit-rate                      → offset commits per second
  commit-latency-avg               → average OffsetCommit round-trip time
  join-rate                        → JoinGroup requests per second
  sync-rate                        → SyncGroup requests per second
  heartbeat-rate                   → heartbeats per second
  heartbeat-response-time-max      → max heartbeat round-trip (high = coordinator slow)
  last-heartbeat-seconds-ago       → should be < heartbeat.interval.ms / 1000
```

### Prometheus alerting rules

```yaml
groups:
  - name: kafka_coordinator
    rules:

    # Groups stuck in rebalance
    - alert: KafkaGroupsRebalancing
      expr: kafka_coordinator_group_metadata_manager_num_groups_preparing_rebalance > 0
      for: 5m
      annotations:
        summary: "{{ $value }} consumer groups stuck in rebalance for >5 minutes"

    # Frequent rebalances (should be near 0 in steady state)
    - alert: KafkaFrequentRebalances
      expr: rate(kafka_consumer_rebalance_total[15m]) > 0.2
      for: 10m
      annotations:
        summary: "Consumer group {{ $labels.group }} rebalancing more than once per 5 min"

    # Coordinator load in progress (coordinator recovering)
    - alert: KafkaCoordinatorLoading
      expr: kafka_network_requests_total{request="FindCoordinator",error="COORDINATOR_LOAD_IN_PROGRESS"} > 0
      for: 2m
      annotations:
        summary: "Coordinator is loading state — consumers waiting to join"

    # High commit latency
    - alert: KafkaOffsetCommitLatencyHigh
      expr: kafka_network_request_total_time_ms{request="OffsetCommit",quantile="0.99"} > 1000
      for: 5m
      annotations:
        summary: "OffsetCommit p99 latency > 1 second — coordinator may be slow"
```

---

## 15. Common Problems and Diagnostics

### Problem 1: Continuous rebalancing (rebalance storm)

**Symptoms**: `rebalance-rate-per-hour` is high, consumers repeatedly rejoin.

**Causes and fixes**:

```
Cause: max.poll.interval.ms too low for processing time
Fix:   props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 1800000);
       props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50); // smaller batches

Cause: GC pauses > session.timeout.ms
Fix:   Use G1GC or ZGC with low-pause settings
       Increase session.timeout.ms

Cause: Network instability between consumer and coordinator
Fix:   Check network; increase session.timeout.ms

Cause: No static membership on rolling deploys
Fix:   props.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, podName);
```

### Problem 2: Consumer stuck in PreparingRebalance

**Symptoms**: `kafka-consumer-groups.sh --describe` shows state=PreparingRebalance for a long time.

**Cause**: One consumer is not sending JoinGroupRequest within `rebalance.timeout.ms`.

**Diagnosis**:
```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group order-service-prod

# Look for members with CLIENT-ID showing a host that's unresponsive
# Or members listed with empty CONSUMER-ID (timed out but coordinator hasn't cleaned up)
```

**Fix**: Identify and restart the stale consumer instance. Or wait for `rebalance.timeout.ms` to evict it.

### Problem 3: ILLEGAL_GENERATION on OffsetCommit

**Symptoms**: Logs show `CommitFailedException: Offset commit cannot be completed since the consumer is not part of an active group for auto partition assignment...`

**Cause**: Rebalance happened while the consumer was processing. The consumer's generation ID is now stale.

**Fix**: This is expected behavior. Ensure processing completes within `max.poll.interval.ms`. Design consumer logic to handle `CommitFailedException` gracefully:

```java
try {
    consumer.commitSync();
} catch (CommitFailedException e) {
    // Rebalance happened — offsets not committed
    // Records will be redelivered to the new owner — ensure idempotent processing
    log.warn("Commit failed due to rebalance: {}", e.getMessage());
}
```

### Problem 4: COORDINATOR_NOT_AVAILABLE on startup

**Symptoms**: Consumers fail with `CoordinatorNotAvailableException` on initial connect.

**Cause**: `__consumer_offsets` does not yet exist or its leader has not been elected.

**Cause 2**: `offsets.topic.replication.factor` is greater than the number of available brokers.

**Fix**:
```bash
# Verify __consumer_offsets exists and is healthy
kafka-topics.sh --bootstrap-server localhost:9092 \
  --describe --topic __consumer_offsets

# If replication.factor > available brokers, reduce it:
kafka-configs.sh --bootstrap-server localhost:9092 \
  --entity-type brokers --entity-default \
  --alter --add-config offsets.topic.replication.factor=1
# (only on single-broker dev clusters)
```

### Problem 5: Offset commit rejected — consumer not a member

**Symptoms**: `UnknownMemberIdException` on `commitSync()`

**Cause**: The consumer's session timed out (Coordinator removed it from the group) but the consumer itself didn't know yet. Common when processing takes longer than `session.timeout.ms`.

**Cause 2**: Consumer is using `assign()` mode but trying to commit with an invalid group ID or member ID.

**Fix**:
```java
// Increase session.timeout.ms to give more time before eviction
props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 120000); // 2 min

// Or handle the exception:
try {
    consumer.commitSync();
} catch (UnknownMemberIdException e) {
    // Session timed out — must rejoin. Handle gracefully.
    consumer.subscribe(consumer.subscription()); // trigger rejoin on next poll
}
```

---

## 16. Configuration Reference

### Broker-side (server.properties)

```properties
# __consumer_offsets topic structure (set before first consumer group)
offsets.topic.num.partitions=50
offsets.topic.replication.factor=3
offsets.topic.min.isr=2
offsets.topic.segment.bytes=104857600       # 100 MB per segment
offsets.topic.compression.codec=0           # 0=none, 2=snappy

# Offset retention
offsets.retention.minutes=10080             # 7 days
offsets.retention.check.interval.ms=600000  # check every 10 minutes

# Offset commit config
offsets.commit.timeout.ms=5000              # coordinator commit timeout
offsets.commit.required.acks=-1             # acks=all for offset commits (durability)
offsets.load.buffer.size=5242880            # 5 MB buffer for loading offsets at startup

# Group rebalance config
group.initial.rebalance.delay.ms=3000       # wait 3s for members to join before first rebalance
group.min.session.timeout.ms=6000           # minimum consumer session.timeout.ms
group.max.session.timeout.ms=1800000        # maximum consumer session.timeout.ms (30 min)
```

### Consumer-side (producer.properties)

```properties
# Required
group.id=my-consumer-group
bootstrap.servers=broker1:9092,broker2:9092,broker3:9092

# Session and heartbeat (critical triad — must satisfy: heartbeat < session/3)
session.timeout.ms=45000           # default: 45s
heartbeat.interval.ms=15000        # default: 3s (set to session/3)
max.poll.interval.ms=300000        # default: 5 min (must > max processing time)

# Rebalance protocol
partition.assignment.strategy=org.apache.kafka.clients.consumer.CooperativeStickyAssignor

# Static membership (Kubernetes / stable deployments)
group.instance.id=                 # set to pod name or stable instance identifier

# Offset management
enable.auto.commit=false           # never true in production
auto.offset.reset=latest           # latest or earliest

# Throughput
max.poll.records=500               # reduce for slow processing
fetch.min.bytes=1                  # 1 = low latency, 1MB = high throughput
fetch.max.wait.ms=500
```

---

## 17. Quick Reference

### Group state transitions

```
New group: → Empty → PreparingRebalance → CompletingRebalance → Stable
Membership change: Stable → PreparingRebalance → CompletingRebalance → Stable
All leave: Stable → PreparingRebalance → Empty
Timeout: Empty → Dead (after offsets.retention.minutes)
```

### Coordinator RPC summary

| RPC | Who sends | When | What Coordinator does |
|---|---|---|---|
| `FindCoordinator` | Consumer | Startup / reconnect | Compute partition index, return leader broker |
| `JoinGroup` | All consumers | Rebalance start | Collect members, elect leader, return assignment metadata |
| `SyncGroup` | All consumers | After JoinGroup | Receive assignment (leader), distribute slices |
| `Heartbeat` | Background thread | Every `heartbeat.interval.ms` | Confirm liveness, signal rebalance |
| `LeaveGroup` | Consumer | `close()` called | Remove member, trigger rebalance |
| `OffsetCommit` | Consumer | After processing | Write offset to `__consumer_offsets` |
| `OffsetFetch` | Consumer | After rebalance | Read last committed offset |
| `DescribeGroups` | AdminClient/CLI | Monitoring | Return group state, members, assignments |
| `DeleteGroups` | AdminClient/CLI | Cleanup | Write tombstones, purge group metadata |

### Key formula

```
Coordinator Partition = abs(group.id.hashCode()) % offsets.topic.num.partitions
Coordinator Broker    = leader of __consumer_offsets-{Coordinator Partition}
```

### Three things that are NOT the Coordinator's job

1. **Routing message reads/writes** — that's partition leaders (FetchRequest/ProduceRequest bypass the Coordinator entirely)
2. **Assigning partitions** — the Group **Leader** (a consumer instance) runs the assignment algorithm client-side
3. **Managing partition leaders** — that's the Kafka Controller

### The key insight

> The Group Coordinator is a **stateful consensus point** for group membership and offset storage. It uses `__consumer_offsets` as its durable backing store, which means it can fail and recover without losing any group state. Every committed offset and every group metadata change is replicated to the `__consumer_offsets` followers before acknowledgment — coordinator failover is seamless from a data perspective, only causing a brief reconnection delay.
