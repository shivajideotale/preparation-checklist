# 07 — Replication: ISR, LEO, HW & LSO

> How Kafka replicates data across brokers and the offset concepts that govern durability and consumer visibility.

---

## Replication Fundamentals

Every partition has one **leader** and N-1 **followers** (replicas). The leader handles all reads and writes. Followers passively replicate from the leader.

```
Topic "orders", Partition 0, Replication Factor = 3:

Broker-0 (LEADER of orders-0):
  /var/kafka/logs/orders-0/  ← active writes go here
  
Broker-1 (FOLLOWER of orders-0):
  /var/kafka/logs/orders-0/  ← replica pulled from broker-0

Broker-2 (FOLLOWER of orders-0):
  /var/kafka/logs/orders-0/  ← replica pulled from broker-0

Producer → Broker-0 (writes)
Consumer → Broker-0 (reads, by default)
Followers → continuously fetch from Broker-0 (replication)
```

---

## The Four Offset Concepts

Understanding these four concepts is critical for reasoning about Kafka's consistency guarantees.

```
Partition log (visual):
  ┌────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┐
  │ 0  │ 1  │ 2  │ 3  │ 4  │ 5  │ 6  │ 7  │ 8  │ 9  │    │
  └────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┘
    ▲                                   ▲         ▲     ▲
   LSO                                  HW        LEO(F) LEO(L)
   (open txn                           (min       (some  (leader
    blocks here)                       ISR LEO)   follr) next write)
```

---

## LEO — Log End Offset

The **next offset to be written** on a specific replica. Each replica has its own LEO.

```
After writing 10 messages (offsets 0-9):
  Leader LEO    = 10  (next write will be offset 10)
  Follower-1 LEO = 9  (still replicating offset 9)
  Follower-2 LEO = 10 (fully caught up)
```

**How the leader tracks follower LEOs:**
Followers continuously send `FetchRequest` to the leader. Each request includes the follower's current `fetchOffset` (= its LEO). The leader reads this value from the request to track how far each follower has progressed.

```
Follower FetchRequest: {fetchOffset: 9}  ← "I need offset 9 next"
                                            "My current LEO is 9"
Leader: records Follower-1 LEO = 9
```

---

## ISR — In-Sync Replicas

The set of replicas currently **caught up** with the leader.

A replica is **in-sync** if it has fetched from the leader within `replica.lag.time.max.ms` (default 30 seconds).

```
ISR = {Broker-0(leader), Broker-1, Broker-2}  ← all three in sync

If Broker-1 stops fetching for 30+ seconds:
  ISR = {Broker-0, Broker-2}  ← Broker-1 removed from ISR

When Broker-1 recovers and catches up:
  ISR = {Broker-0, Broker-1, Broker-2}  ← Broker-1 added back
```

**ISR changes are written to ZooKeeper/KRaft metadata** and propagated to all brokers by the Controller. The Controller also broadcasts ISR changes to producers via metadata updates.

### Why ISR Matters

```
With acks=all + min.insync.replicas=2:

  Normal: ISR={B0, B1, B2}, write confirmed by all 3 → safe

  B2 falls out of ISR: ISR={B0, B1}
    ISR size (2) >= min.insync.replicas (2) → writes still accepted

  B1 also falls out: ISR={B0}
    ISR size (1) < min.insync.replicas (2)
    → NotEnoughReplicasException
    → Partition becomes WRITE-ONLY-PROTECTED
    → Prevents writing data only B0 has (single point of failure)
```

---

## HW — High Watermark

The minimum LEO across all **current ISR members**. The boundary of data that is safe for consumers to read.

```
HW = min(LEO of all ISR members)

Example:
  ISR = {B0, B1, B2}
  B0 LEO = 10
  B1 LEO = 9
  B2 LEO = 10
  HW = min(10, 9, 10) = 9

Consumers can read offsets 0–8 (up to HW-1)
Offset 9 is NOT visible to consumers yet
When B1 fetches offset 9: B1 LEO = 10, HW = 10
Now offset 9 is visible to consumers
```

### Why the HW Fence Exists

```
Scenario WITHOUT HW fence:
  t=0  Leader (B0) writes offset 9
  t=0  Consumer reads offset 9 from B0 → gets the data
  t=1  B0 crashes (B1 hasn't replicated offset 9 yet)
  t=1  B1 becomes leader. B1 only has offsets 0-8.
  t=2  Consumer committed offset 9 (just read it from B0)
  t=2  Consumer restarts, reads from offset 10
  t=2  Offset 9 is PERMANENTLY MISSING from the new leader
  t=2  Consumer processed a message that "never existed" in the new reality
  t=2  DATA INCONSISTENCY

WITH HW fence:
  t=0  Leader (B0) writes offset 9
  t=0  Consumer tries to read offset 9 → B0 says: offset 9 > HW (=8) → NOT served
  t=1  B0 crashes before B1 replicates offset 9
  t=1  B1 becomes leader. B1 has offsets 0-8. HW = 8.
  t=2  Consumer reads from offset 8 (last committed) → gets data consistent with new leader
  t=2  No inconsistency.
```

### HW Propagation

```
Leader advances HW when all ISR members have LEO ≥ new record's offset.

Leader broadcasts new HW to followers via FetchResponse:
  FetchResponse {
    highWatermark: 10,
    records: [...]
  }

Followers update their own HW from leader's FetchResponse.
This ensures followers also reject reads of uncommitted data
(relevant for rack-aware consumer routing where consumers may read from followers).
```

---

## LSO — Last Stable Offset

The HW bounded by open transactions. **`read_committed` consumers read up to LSO, not HW.**

```
Partition: [0][1][2][TXN_OPEN:3][4][5][6][7][8][9]
                        ↑
                        Transaction started at offset 3 (still open)

HW = 9 (all messages replicated to ISR)
LSO = 2 (bounded by the first open transaction at offset 3)

read_uncommitted consumer: reads 0-9 (sees all, including open txn records)
read_committed consumer:   reads 0-2 (blocked by open txn at 3)
```

### Why LSO Exists

```
Without LSO:
  Transaction starts at offset 3.
  Consumer reads offsets 3-6 (all within the transaction).
  Transaction ABORTS.
  ABORT marker written at offset 7.
  Consumer already processed offsets 3-6 — those records should have been invisible!

With LSO:
  Transaction open at offset 3.
  read_committed consumer can read 0-2 only (LSO = 2).
  Transaction commits (COMMIT marker at offset 7).
  LSO advances past offset 7.
  Consumer reads 3-6 (safe — they're committed now).
```

### LSO Blocking — A Common Production Problem

```
Scenario:
  Long-running transaction open at offset 1000.
  During the next 60 seconds: 500,000 new messages arrive (offsets 1001-501000).
  HW = 501000, LSO = 999 (blocked by open txn at 1000).

  read_committed consumers: completely blocked — no new messages visible
  read_uncommitted consumers: reads all 500,000 normally

Fix: Set transaction.timeout.ms (default 60s) to auto-abort stuck transactions.
     Monitor: kafka.log:type=Log,name=LastStableOffsetLag
```

---

## ReplicaFetcherThread

The background thread on each follower that replicates from the leader.

```
Follower Broker-1 → ReplicaFetcherThread for each source broker:

Loop:
  for each partition this broker follows (led by Broker-0):
    fetchOffset = partition.log.localLogEndOffset()  // my current LEO
    
  request = FetchRequest {
    maxWaitMs: 500,
    minBytes: 1,
    replicaId: 1,  // identifies this as a replica fetch (not consumer fetch)
    topics: [{
      name: "orders",
      partitions: [{
        partition: 0,
        fetchOffset: 9,      // I need offset 9
        maxBytes: 1048576
      }]
    }]
  }
  
  response = leader.fetch(request)
  partition.log.append(response.records)  // write to follower's log
  partition.updateLEO()
  
  // Report new fetchOffset in next request → leader updates follower LEO tracking
```

`num.replica.fetchers` (default 1): controls how many threads per broker handle replication. Increase to 4 for high-throughput clusters. Each thread handles multiple partitions from the same source broker.

---

## Leader Epoch

Monotonically increasing counter per partition. Increments on every leader change. Written into RecordBatch headers.

```
Leader epoch history (leader-epoch-checkpoint):
  epoch=0, startOffset=0      ← Broker-0 led from the beginning
  epoch=1, startOffset=500000 ← Broker-1 took over at offset 500000
  epoch=2, startOffset=750000 ← Broker-2 took over at offset 750000
```

**Used during crash recovery to prevent data divergence:**

```
Scenario:
  Old leader (Broker-0, epoch=1) wrote offsets 0-999.
  Follower (Broker-1) only replicated 0-899.
  Broker-0 crashes.
  Broker-1 elected as new leader (epoch=2).
  Broker-0 recovers and rejoins as a follower.

  Without leader epoch:
    Broker-0 has offsets 0-999.
    Broker-1 (new leader) has offsets 0-899.
    They DIVERGE at offset 900-999.
    Consumers see inconsistent data depending on which broker they read from.

  With leader epoch:
    Broker-0 asks new leader: "What is your log end offset for epoch=1?"
    New leader responds: "For epoch=1, my log ends at 899."
    Broker-0 TRUNCATES its log to offset 899 (removes 900-999 from epoch=1).
    Broker-0 fetches 900+ from new leader.
    Consistent logs restored.
```

---

## min.insync.replicas Deep Dive

```properties
min.insync.replicas=2  # set per-topic or broker default
```

This config only has effect when **`acks=all`**. It defines the minimum number of replicas (including the leader) that must be in ISR for a write to succeed.

```
Configuration matrix:

RF=3, min.isr=1, acks=all:
  Safe when: ISR has at least 1 member (always the leader)
  Effectively same as acks=1 (leader alone can ack)
  Risk: data loss if leader crashes before any follower replicates

RF=3, min.isr=2, acks=all  ← RECOMMENDED:
  Safe when: ISR has at least 2 members
  Tolerates 1 broker failure
  If second failure reduces ISR to 1: writes blocked (safe — prevents single point of failure)

RF=3, min.isr=3, acks=all:
  Safe when: ALL 3 replicas in ISR
  No tolerance for any broker failure
  Very conservative — use only for extremely critical data
```

---

## unclean.leader.election.enable

```properties
unclean.leader.election.enable=false  # default, recommended
```

**Scenario: All ISR members are offline.**

```
ISR = {B0, B1, B2}
B0 crashes. ISR = {B1, B2}.
B1 crashes. ISR = {B2}.
B2 crashes. ISR = {}  ← ALL ISR members offline.

Partition is offline. No reads or writes.

unclean.leader.election.enable=false (default):
  Partition stays OFFLINE.
  Wait for any ISR member to recover.
  No data loss when it comes back.

unclean.leader.election.enable=true:
  Out-of-ISR replica (if any) can become leader.
  Partition recovers (writes resume).
  BUT: messages the old ISR leader had that this out-of-ISR replica never replicated
       are PERMANENTLY LOST.
  Use only when: availability > durability for this topic.
```

---

## Preferred Leader

The **preferred leader** is the first replica in a partition's replica assignment list.

```
orders-0 assignment: replicas=[B0, B1, B2]
                     preferred leader = B0
```

After B0 fails and B1 takes over as leader, when B0 recovers, it becomes a follower of B1. This is unbalanced — B0 is idle while B1 is overloaded.

**Auto-rebalancing** (`auto.leader.rebalance.enable=true` default): A background thread periodically checks for partitions where the preferred leader is not the current leader. If found, it triggers a preferred leader election to restore balance.

```bash
# Manual trigger
kafka-leader-election.sh --bootstrap-server broker:9092 \
  --election-type PREFERRED \
  --topic orders --partition 0
```

---

## Replication Lag Monitoring

```
# JMX metrics
kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions
  → partitions where ISR < replication.factor
  → MUST be 0 in steady state (non-zero = problem)

kafka.server:type=ReplicaFetcherManager,name=MaxLag,clientId=Replica
  → maximum replication lag in messages across all followers
  → should be < 1000 in a healthy cluster

kafka.server:type=ReplicaManager,name=IsrShrinksPerSec
  → rate of replicas falling out of ISR
  → spikes indicate slow followers (disk I/O, GC, network)

kafka.server:type=ReplicaManager,name=IsrExpandsPerSec
  → rate of replicas rejoining ISR
  → should follow IsrShrinksPerSec (pairs of shrink+expand)
```

---

## Summary

| Concept | Definition | Scope |
|---|---|---|
| LEO | Next offset to write | Per-replica |
| ISR | Set of caught-up replicas | Per-partition |
| HW | min(LEO across ISR) | Per-partition (leader is authoritative) |
| LSO | HW bounded by open transactions | Per-partition |
| Leader epoch | Leadership change counter | Per-partition |
| Preferred leader | First replica in assignment list | Per-partition |

```
Write path:
  Producer → Leader → log write → LEO advances
  Followers → fetch from leader → replicate → follower LEO advances
  Leader: min(follower LEOs) ≥ new record → HW advances
  Consumers: can now read up to HW (or LSO for read_committed)

Failover path:
  Leader crashes → ISR loses leader
  Controller elects new leader from ISR
  New leader's LEO becomes the starting point
  Other ISR members fetch from new leader
  Old leader rejoins → truncates to leader epoch boundary → fetches fresh data
```
