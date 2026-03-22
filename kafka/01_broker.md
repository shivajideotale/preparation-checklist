# 01 — Kafka Broker

> A single Kafka server process. The fundamental unit of a Kafka cluster.

---

## What a Broker Is

A Kafka broker is a **JVM process** (`kafka.Kafka`) that runs on a server and performs three core functions:

1. **Stores** partition log files on disk
2. **Accepts** `ProduceRequest` from producers
3. **Serves** `FetchRequest` to consumers

A cluster is simply a group of brokers sharing a metadata layer (ZooKeeper or KRaft). Brokers are identified by a unique integer `broker.id` configured in `server.properties`.

```
Kafka Cluster (3 brokers):
┌─────────────────────────────────────────────────────────────┐
│  Broker-0 (broker.id=0)                                     │
│    Leads:    orders-0, orders-3, payments-1                 │
│    Follows:  orders-1, orders-2, payments-0                 │
├─────────────────────────────────────────────────────────────┤
│  Broker-1 (broker.id=1)                                     │
│    Leads:    orders-1, orders-4, payments-2                 │
│    Follows:  orders-0, orders-3, payments-1                 │
├─────────────────────────────────────────────────────────────┤
│  Broker-2 (broker.id=2)                                     │
│    Leads:    orders-2, orders-5, payments-0                 │
│    Follows:  orders-1, orders-4, payments-2                 │
└─────────────────────────────────────────────────────────────┘
```

---

## Internal Architecture

### Thread Model

```
Incoming Network
      │
  ┌───▼────────────────────────────────────────────────────────┐
  │  Acceptor Thread  (1 per listener)                        │
  │  Accepts TCP connections, hands to Processor threads       │
  └───┬────────────────────────────────────────────────────────┘
      │
  ┌───▼────────────────────────────────────────────────────────┐
  │  Processor Threads  (num.network.threads = 3 default)      │
  │  Read bytes from sockets, parse request headers            │
  │  Place requests in RequestChannel queue                    │
  │  Write response bytes back to sockets                      │
  └───┬────────────────────────────────────────────────────────┘
      │  RequestChannel (in-memory queue)
  ┌───▼────────────────────────────────────────────────────────┐
  │  KafkaRequestHandler Pool  (num.io.threads = 8 default)    │
  │  Validate auth and CRC                                     │
  │  Write to / read from log (ReplicaManager)                 │
  │  Build response objects                                     │
  │  Place responses in ResponseQueue                          │
  └────────────────────────────────────────────────────────────┘
```

### Key Internal Components

| Component | Role |
|---|---|
| `SocketServer` | Manages network threads, acceptors, processors |
| `ReplicaManager` | Coordinates partition leaders/followers, ISR, HW |
| `LogManager` | Manages log directories, segments, compaction |
| `GroupCoordinator` | Manages consumer group membership, offsets |
| `TransactionCoordinator` | Manages Kafka transactions (2PC) |
| `KafkaController` | One per cluster — partition leader elections |
| `ZkClient` / `KRaftManager` | Cluster metadata coordination |

---

## How a Broker Handles a ProduceRequest

```
1. Network thread reads request bytes from socket
2. Parses into ProduceRequest object, adds to RequestChannel

3. I/O thread picks it up:
   a. Checks authorization (ACLs)
   b. Validates CRC32C of each RecordBatch
   c. Validates max.message.bytes
   d. If idempotent: validates PID + epoch + sequence number
   e. If transactional: updates __transaction_state

4. ReplicaManager.appendRecords():
   a. Verifies this broker is the partition LEADER
      (if not: LEADER_NOT_AVAILABLE → producer retries)
   b. FileChannel.write(records) → OS page cache
      (NOT direct to disk — async OS flush)
   c. Updates Log End Offset (LEO)
   d. Updates offset/time indexes if threshold crossed

5. If acks=all: schedules callback for when HW advances past new records
   (waits for all ISR followers to replicate)

6. I/O thread builds ProduceResponse, places in ResponseQueue

7. Network thread writes response bytes back to producer socket
```

---

## How a Broker Handles a FetchRequest

```
1. Network thread reads FetchRequest bytes
   (from consumer OR from follower replica fetcher)

2. I/O thread:
   a. Verifies this broker is the partition LEADER
   b. Checks fetchOffset ≤ High Watermark (read_uncommitted)
      OR fetchOffset ≤ Last Stable Offset (read_committed)
   c. LogManager finds correct segment by filename binary search
   d. OffsetIndex binary search → physical byte position
   e. Builds FetchResponse

3. ReplicaManager.readFromLocalLog()
   
4. Network layer: FileChannel.transferTo() → sendfile()
   (zero-copy: page cache → NIC, bypasses JVM heap)
```

---

## Broker Startup Sequence

```
1. Load server.properties configuration
2. Connect to ZooKeeper (legacy) or form KRaft quorum
3. Register as broker: /brokers/ids/{broker.id} (ZK) or metadata log (KRaft)
4. Create or recover log directories (log.dirs)
   - Replay each partition's log to restore in-memory state
   - Rebuild offset indexes if missing (recovery.point.checkpoint)
5. Start SocketServer (begin accepting connections)
6. Participate in Controller election (ZK) or rejoin quorum (KRaft)
7. Receive LeaderAndIsrRequest from Controller
   - Learn which partitions it leads, which it follows
8. Start ReplicaFetcherThreads for partitions it follows
9. Broker is READY — producers and consumers can connect
```

---

## Broker Storage on Disk

```
/var/kafka/logs/              ← log.dirs
├── orders-0/                 ← TopicPartition directory
│   ├── 00000000000000000000.log
│   ├── 00000000000000000000.index
│   ├── 00000000000000000000.timeindex
│   ├── 00000000000001000000.log     ← active segment
│   ├── leader-epoch-checkpoint
│   └── ...
├── __consumer_offsets-14/    ← internal topic partition
├── __transaction_state-7/    ← internal topic partition
├── replication-offset-checkpoint
├── log-start-offset-checkpoint
└── recovery-point-offset-checkpoint
```

---

## Important Broker Configurations

```properties
# Identity
broker.id=0
listeners=PLAINTEXT://0.0.0.0:9092
advertised.listeners=PLAINTEXT://broker-0:9092

# Storage
log.dirs=/var/kafka/logs
log.retention.hours=168
log.segment.bytes=1073741824       # 1 GB per segment
log.retention.check.interval.ms=300000

# Threading
num.network.threads=3              # socket I/O threads
num.io.threads=8                   # request processing threads
num.replica.fetchers=1             # replication threads (increase to 4 for high throughput)

# Durability
default.replication.factor=3
min.insync.replicas=2
unclean.leader.election.enable=false
auto.leader.rebalance.enable=true

# Internal topics
offsets.topic.replication.factor=3
transaction.state.log.replication.factor=3
auto.create.topics.enable=false    # always false in production
```

---

## JVM Heap vs Page Cache

The broker intentionally keeps JVM heap small and relies on OS page cache for data caching.

```
32 GB machine — recommended allocation:
  JVM heap:   6 GB   (-Xms6g -Xmx6g)
    Contains: metadata, network buffers, thread stacks, request objects
  OS page cache: ~24 GB
    Contains: .log file contents (actual message data)
              .index and .timeindex files (memory-mapped)

Why NOT use JVM heap for data:
  - GC pauses increase with heap size → latency spikes
  - Page cache survives JVM restart → warm immediately on reconnect
  - JVM heap would be a second copy of data already in page cache
```

---

## Broker Metrics to Monitor

```
# Partition leadership
kafka.server:type=ReplicaManager,name=LeaderCount           → partitions this broker leads
kafka.server:type=ReplicaManager,name=PartitionCount        → total partitions on this broker
kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions → MUST be 0

# Request handling
kafka.network:type=RequestMetrics,name=RequestsPerSec,request=Produce
kafka.network:type=RequestMetrics,name=RequestsPerSec,request=FetchConsumer
kafka.network:type=RequestMetrics,name=TotalTimeMs,request=Produce  → p99 produce latency

# I/O
kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec     → inbound MB/s
kafka.server:type=BrokerTopicMetrics,name=BytesOutPerSec    → outbound MB/s

# OS
node_filesystem_used_bytes{mountpoint="/var/kafka"} → disk usage
node_memory_MemAvailable_bytes                       → free RAM for page cache
```

---

## Common Broker Failure Modes

| Failure | Symptom | Recovery |
|---|---|---|
| Disk full | `IOException` in logs, producer gets errors | Add disk, adjust retention |
| High GC pauses | Request timeouts, rebalances | Tune G1GC, reduce heap |
| Network saturation | Replication lag, consumer lag | Rate-limit clients via quotas |
| Too many partitions | High file handle count, slow startup | Reduce partitions or add brokers |
| OOM kill | Broker dies silently | Increase heap or add RAM |

---

## Summary

The broker is a **stateless request router + durable log manager**. It does not understand message semantics — it stores opaque bytes. Its performance comes from:
1. Appending to a sequential log (no random I/O)
2. Serving reads from OS page cache (no disk I/O for recent data)
3. Zero-copy transfer for consumer reads (no JVM heap involvement)
4. Replication for durability (no fsync per message needed)
