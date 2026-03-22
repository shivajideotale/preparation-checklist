# 02 — Controller & KRaft

> The Controller is the cluster manager. KRaft replaces ZooKeeper with built-in Raft consensus.

---

## The Kafka Controller

### What It Is

The Controller is a **role** played by exactly one broker in the cluster at any time. It is responsible for all cluster-level metadata management. Every broker has the code to be a controller, but only one is active.

```
Cluster of 5 brokers:
  Broker-0  → Follower
  Broker-1  → Follower
  Broker-2  → CONTROLLER  ← this one
  Broker-3  → Follower
  Broker-4  → Follower
```

### Controller Responsibilities

#### 1. Partition Leader Election
When a broker fails, its partitions have no leader. The Controller:
1. Detects broker failure (ZK ephemeral node deletion or KRaft heartbeat timeout)
2. For each partition the dead broker led: selects a new leader from the ISR
3. Sends `LeaderAndIsrRequest` to all affected brokers
4. Updates cluster metadata

```
Broker-1 dies. It was leader of orders-0, payments-2.
ISR for orders-0 = {Broker-1(dead), Broker-2, Broker-3}
Controller picks Broker-2 (first in ISR after removing dead broker)
Sends: LeaderAndIsrRequest → all brokers
  "orders-0: leader=Broker-2, isr=[Broker-2, Broker-3]"
All brokers update their metadata cache.
Broker-2 starts serving orders-0 reads/writes.
```

#### 2. ISR Management
Tracks which replicas are in-sync. When a follower:
- **Falls behind** (hasn't fetched within `replica.lag.time.max.ms`): Controller removes from ISR
- **Catches up**: Controller adds back to ISR

Both ISR changes are written to ZooKeeper/KRaft metadata and propagated to all brokers via `UpdateMetadataRequest`.

#### 3. Broker Lifecycle
- **Broker joins**: Registers itself, receives current partition assignment
- **Broker leaves** (graceful): Transfers leadership proactively before shutdown
- **Broker dies** (abrupt): Controller detects via ZK/KRaft, triggers leader elections

#### 4. Metadata Propagation
Any cluster change (new topic, partition increase, reassignment) is processed by the Controller and broadcast to all brokers. Brokers maintain a metadata cache that producers and consumers also cache.

#### 5. Epoch Management
Every new Controller election increments the **Controller Epoch**. This prevents **zombie controllers** — a previous controller that comes back from a network partition cannot issue commands with an old epoch. Brokers reject `LeaderAndIsrRequest` with an older epoch.

---

## ZooKeeper-Based Controller Election (Legacy)

### How It Works

```
All brokers race to create /controller ZNode in ZooKeeper:

  Broker-0: create /controller → FAILED (Broker-2 won)
  Broker-1: create /controller → FAILED (Broker-2 won)
  Broker-2: create /controller → SUCCESS → becomes Controller
  ...

ZNode contents: {version:1, brokerid:2, timestamp:1706789123456}

All non-controller brokers watch /controller:
  If /controller is deleted → ALL brokers race again
```

### Failure Detection via ZooKeeper Sessions

```
ZooKeeper session:
  Controller keeps a session alive with ZK heartbeats
  If Controller dies → ZK session times out (zookeeper.session.timeout.ms, default 18s)
  ZK deletes the ephemeral /controller node
  All brokers' watches fire → new election race begins

Total failover time: ZK session timeout + election time = 6–30 seconds
  During this window: no leader elections, no ISR updates (risky under load)
```

### ZooKeeper Bottleneck at Scale

```
With 10,000 partitions:
  Controller must send LeaderAndIsrRequest to every broker for each change
  High partition count → many simultaneous changes → ZK write bottleneck
  Practical limit: ~200,000 partitions per cluster
```

---

## KRaft Mode (Kafka 3.3+ GA)

### Architecture

KRaft (Kafka Raft) replaces ZooKeeper with an internal metadata quorum. A set of brokers (typically 3 or 5) designated as **controllers** runs the Raft consensus algorithm.

```
KRaft Cluster (3 controllers, 3 brokers — can overlap):

Controllers (Raft quorum):
  Controller-0  → Raft follower
  Controller-1  → RAFT LEADER (active Controller)
  Controller-2  → Raft follower

Brokers (data plane):
  Broker-3  → serves producers/consumers
  Broker-4  → serves producers/consumers
  Broker-5  → serves producers/consumers

(In smaller clusters, controllers and brokers can be the same nodes)
```

### The @metadata Topic

All cluster metadata is stored in an internal topic called `@metadata` (not user-visible). This topic:
- Is replicated only across controller nodes (Raft log)
- Contains all partition assignments, ISR sets, topic configs, broker registrations
- Is compacted to keep it manageable
- Brokers subscribe to it to keep their metadata cache current

```
@metadata records include:
  BrokerRegistration   — a broker joined or left
  PartitionRecord      — partition created, leader changed, ISR updated
  TopicRecord          — topic created or deleted
  ConfigRecord         — config change
  ProducerIds          — PID assignments for idempotent producers
```

### Raft Consensus

KRaft uses a variant of the Raft algorithm for leader election and log replication among controllers.

```
Raft states:
  Follower  → receives log entries from leader
  Candidate → running for election (timeout without leader heartbeat)
  Leader    → accepts writes, replicates to followers, commits

Election:
  Follower timeout (no heartbeat from leader) → becomes Candidate
  Sends RequestVote to other controllers
  If majority grants vote → becomes Raft Leader (= active Controller)

Log replication:
  Leader appends metadata record to its log
  Sends AppendEntries to followers
  When majority of controllers confirm → record is COMMITTED
  Leader notifies all controllers → they apply to state machine
```

### KRaft vs ZooKeeper Comparison

| Aspect | ZooKeeper Mode | KRaft Mode |
|---|---|---|
| External dependency | ZooKeeper cluster (3-5 nodes) | None — self-contained |
| Failure detection | ZK session timeout: 6–18s | Raft heartbeat timeout: ms |
| Controller election | Race to create ZNode | Raft vote (< 1s) |
| Metadata storage | ZooKeeper znodes | @metadata internal topic |
| Partition limit | ~200K (ZK write bottleneck) | Millions |
| Operational complexity | Two systems to manage | One system |
| Kafka version | All versions | 3.3+ production ready |
| Removed | Kafka 4.0 | — |

### KRaft Controller Quorum Configuration

```properties
# server.properties for a combined broker+controller node

# This node is BOTH a controller and a broker
process.roles=broker,controller

# This node's unique ID
node.id=1

# All controller nodes in the quorum
controller.quorum.voters=1@controller-0:9093,2@controller-1:9093,3@controller-2:9093

# Listener for Raft protocol (between controllers)
controller.listener.names=CONTROLLER
listeners=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
advertised.listeners=PLAINTEXT://broker-1:9092
```

For dedicated controller nodes (no broker role):
```properties
process.roles=controller
node.id=1
controller.quorum.voters=1@controller-0:9093,2@controller-1:9093,3@controller-2:9093
listeners=CONTROLLER://0.0.0.0:9093
```

### Initialising a KRaft Cluster

```bash
# Generate cluster UUID (once per cluster)
CLUSTER_UUID=$(kafka-storage.sh random-uuid)

# Format storage on EACH node
kafka-storage.sh format \
  --config /etc/kafka/server.properties \
  --cluster-id $CLUSTER_UUID

# Start each node
kafka-server-start.sh /etc/kafka/server.properties
```

---

## ZooKeeper to KRaft Migration (Kafka 3.5+)

For clusters running on ZooKeeper that need to migrate to KRaft:

```bash
# Step 1: Start KRaft controllers alongside ZooKeeper
# Configure controllers in "migration" mode

# Step 2: Controllers load metadata from ZooKeeper
# (one-time snapshot of all cluster state)

# Step 3: All brokers updated to register with KRaft controllers
# Rolling restart of brokers

# Step 4: ZooKeeper removed from configuration
# kafka-features.sh upgrade --feature metadata.version=<latest>

# Step 5: ZooKeeper cluster decommissioned
```

---

## Controller Epoch — Preventing Zombie Splits

```
Scenario:
  t=0  Controller (epoch=5) is Broker-2
  t=1  Network partition: Broker-2 isolated from Broker-3 and Broker-4
  t=2  Broker-3 and Broker-4 elect Broker-3 as new Controller (epoch=6)
  t=3  Network heals. NOW TWO CONTROLLERS EXIST momentarily:
         Broker-2 thinks it's Controller (epoch=5)
         Broker-3 thinks it's Controller (epoch=6)

  t=4  Broker-2 sends LeaderAndIsrRequest with epoch=5
       Broker-3 receives it: epoch=5 < current epoch=6 → REJECTED
       Broker-2 realises it is no longer the Controller
       Broker-2 steps down

Epoch monotonically increases → guarantees only the latest Controller's commands are accepted.
```

---

## Key Metrics

```
# Controller health
kafka.controller:type=KafkaController,name=ActiveControllerCount  → must always be 1
kafka.controller:type=KafkaController,name=OfflinePartitionsCount → should be 0

# Election activity (high = instability)
kafka.controller:type=ControllerStats,name=LeaderElectionRateAndTimeMs
kafka.controller:type=ControllerStats,name=UncleanLeaderElectionsPerSec → should be 0

# KRaft specific
kafka.raft:type=RaftManager,name=CurrentLeader
kafka.raft:type=RaftManager,name=HighWatermark     → latest committed metadata offset
```

---

## Summary

| Feature | Detail |
|---|---|
| Count | Exactly 1 active controller per cluster |
| Election | ZK ephemeral node (legacy) or Raft vote (KRaft) |
| Duties | Leader election, ISR tracking, metadata distribution |
| Data plane | Zero involvement — message data bypasses controller |
| Epoch | Guards against zombie controllers |
| KRaft benefit | Millisecond failover vs 6–30s with ZooKeeper |
