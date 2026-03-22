# 12 — Performance & I/O

> How Kafka achieves extreme throughput — compression, batching, NIO pipelining, fetch tuning, and hardware recommendations.

---

## Why Kafka Is Fast: The Stack

Kafka's throughput advantage comes from layered optimizations, each multiplying the effect of the others:

```
Layer 1: Sequential I/O
  All writes = appends to end of file
  All reads = forward scans from offset
  Exploits: 100-200x throughput advantage over random I/O on spinning disks

Layer 2: OS Page Cache
  OS RAM used as cache, not JVM heap
  Recent messages served from RAM (producer-consumer locality)
  No GC pressure, survives JVM restart

Layer 3: Zero-Copy (sendfile)
  Consumer reads skip JVM heap entirely
  Page cache → NIC via DMA (2 copies instead of 4)
  Halves memory bandwidth for read-heavy workloads

Layer 4: Batch Compression
  Multiple records compressed together (cross-message redundancy)
  3-10x compression ratio on JSON/text
  Reduces network + disk bandwidth simultaneously

Layer 5: NIO Pipelining
  Up to 5 ProduceRequests in-flight per broker (no sequential ack waiting)
  Single Sender thread handles all brokers via NIO selector
  No thread-per-connection overhead
```

---

## Compression

### How It Works

Compression is applied **per-batch** by the producer. The entire `RecordBatch` (all records) is compressed together before transmission and storage.

```
Producer accumulates 1000 JSON messages:
  {"orderId":"abc-123","customerId":"cust-456","amount":99.99,"status":"PENDING","timestamp":...}
  {"orderId":"def-456","customerId":"cust-789","amount":49.99,"status":"SHIPPED","timestamp":...}
  ...

Batch-level compression (lz4): ~5:1 ratio on repetitive JSON
  - Field names ("orderId","customerId","amount") shared across all 1000 messages
  - Timestamps are numerically close (sequential batching)
  - Status values repeat frequently

vs Per-message compression: ~2:1 ratio
  - No cross-message context for the compressor to exploit
```

### Codec Comparison

| Codec | Ratio (JSON) | CPU (producer) | Latency | Use case |
|---|---|---|---|---|
| `none` | 1x | None | None | Development only |
| `lz4` | ~3x | Very low | +0-2ms | **General production default** |
| `snappy` | ~2.5x | Low | +0-2ms | CPU-constrained producers |
| `zstd` | ~5x | Moderate | +2-5ms | Bandwidth-constrained / cross-DC |
| `gzip` | ~4x | High | +5-20ms | Maximum compression, archival |

### Configuration

```java
// Producer
props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

// Broker (topic-level override)
// compression.type=producer (DEFAULT — store as producer sent it, no broker CPU)
// compression.type=lz4      (broker recompresses — adds CPU, avoid unless necessary)
// compression.type=none     (decompress on arrival — wastes storage)
```

**Always use `compression.type=producer` on the topic** (default). Broker-side recompression wastes CPU and adds latency.

### Impact on Network and Disk

```
100 MB/s uncompressed producer throughput with lz4 (~3x):
  Network bandwidth consumed:    100/3 = ~33 MB/s  (3x reduction)
  Disk write bandwidth:          100/3 = ~33 MB/s  (3x reduction)
  Total cluster storage saved:   66% for this topic

On a retention.bytes=100GB topic:
  Without compression: 100 GB of disk per partition
  With lz4 (3x): 33 GB of disk per partition
```

---

## Batching: batch.size + linger.ms

The two highest-impact producer throughput tunables.

### How They Interact

```
A batch is sent when:
  Size >= batch.size (default 16 KB)
  OR
  linger.ms has elapsed (default 0ms = immediate)

WhicheVER comes first.
```

### linger.ms Impact

```
linger.ms=0 (default):
  Sender fires as soon as it runs (nearly immediate after each send())
  Under low load: 1 message per batch → 1000 network calls/sec for 1000 msg/sec
  Under high load: some batching happens naturally (Sender can't keep up)
  
linger.ms=5:
  Sender waits 5ms before sending
  1000 msg/sec × 5ms window = ~5 messages per batch minimum
  Under moderate load: 50-100 msg batch → 10-20 network calls/sec
  Under high load: batches fill to batch.size regularly
  Throughput improvement: 5-10x with only 5ms added latency

linger.ms=20:
  Good for high-throughput pipelines where latency < 100ms is acceptable
  Allows batches to fill more completely
```

### Tuning Profiles

```java
// Profile 1: Low latency (payment confirmations, alerts)
props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);     // 16 KB
props.put(ProducerConfig.LINGER_MS_CONFIG, 0);           // immediate
props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
// Expected throughput: 100-200 MB/s

// Profile 2: Balanced (general production)
props.put(ProducerConfig.BATCH_SIZE_CONFIG, 131072);    // 128 KB
props.put(ProducerConfig.LINGER_MS_CONFIG, 5);           // 5ms
props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 67108864); // 64 MB
// Expected throughput: 400-600 MB/s

// Profile 3: High throughput (log aggregation, event streaming)
props.put(ProducerConfig.BATCH_SIZE_CONFIG, 524288);    // 512 KB
props.put(ProducerConfig.LINGER_MS_CONFIG, 20);          // 20ms
props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 134217728); // 128 MB
// Expected throughput: 800 MB/s - 1+ GB/s
```

---

## NIO Pipelining (max.in.flight.requests)

```java
props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION_CONFIG, 5);
// With enable.idempotence=true: safe up to 5
// Without idempotence: set to 1 to preserve ordering
```

### How Pipelining Works

```
Without pipelining (sequential):
  Send batch-A → wait for ack → send batch-B → wait → send batch-C
  Network utilization: RTT-bound (mostly idle waiting)
  
  50ms RTT → max 20 requests/sec per connection
  With 100KB batches: 2 MB/s (terrible)

With pipelining (5 in-flight):
  Send batch-A → send batch-B → send batch-C → send batch-D → send batch-E
  → receive ack-A → send batch-F → ...
  
  5 batches in-flight simultaneously
  Network utilization: nearly 100% of bandwidth
  With 100KB batches: 10 MB/s (5x improvement at 50ms RTT)
```

### Ordering Guarantee with Pipelining

```
With enable.idempotence=true (default):
  Broker tracks (PID, partition, sequence) for each in-flight request
  Out-of-order delivery is detected and reordered by the broker
  Retries are deduplicated via sequence numbers
  Safe to use max.in.flight.requests=5

Without idempotence:
  Retry of failed batch-A can arrive AFTER batch-B already succeeded:
    t=0  Send [A:seq=0] and [B:seq=1]
    t=1  [B:seq=1] succeeds
    t=2  [A:seq=0] fails → retry
    t=3  Retry [A:seq=0] arrives → now in log AFTER [B:seq=1]
    Consumer sees: B, A → OUT OF ORDER
  
  Must set max.in.flight.requests=1 to prevent this.
```

---

## Fetch Tuning: fetch.min.bytes + fetch.max.wait.ms

These broker-side settings control fetch batching — trading latency for throughput on the consumer side.

### How Fetch Batching Works

```
Consumer sends FetchRequest:
  maxWaitMs: fetch.max.wait.ms (500ms default)
  minBytes: fetch.min.bytes (1 byte default)

Broker checks partition at fetchOffset:
  data >= fetch.min.bytes → respond immediately
  data < fetch.min.bytes → hold request up to fetch.max.wait.ms
    At fetch.max.wait.ms: respond with whatever is available
```

### Tuning Profiles

```java
// Profile 1: Real-time consumer (low latency)
props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);        // respond immediately
props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 100);    // max 100ms wait
props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
// Result: sub-100ms end-to-end latency

// Profile 2: Batch consumer (high throughput)
props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1048576);  // wait for 1 MB
props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 1000);   // max 1s wait
props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 2000);
// Result: large fetches, fewer requests, better decompression efficiency

// Profile 3: Very high throughput
props.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, 104857600); // 100 MB per response
props.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 10485760); // 10 MB per partition
props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 10485760);  // 10 MB
props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);
```

### max.poll.records and max.poll.interval.ms

```
Critical constraint:
  max.poll.records × avg_processing_time_per_record < max.poll.interval.ms

Example: max.poll.records=500, processing=100ms each
  500 × 100ms = 50,000ms = 50 seconds
  max.poll.interval.ms = 300,000ms = 5 minutes → safe

Example: max.poll.records=500, processing=1s each
  500 × 1000ms = 500,000ms = 8.3 minutes
  max.poll.interval.ms = 300,000ms → REBALANCE TRIGGERED

Fix: Reduce max.poll.records to 50 (50 × 1000ms = 50s < 5min)
     OR increase max.poll.interval.ms to 1800000 (30 min)
```

---

## Broker Threading

```
num.network.threads=3   # socket I/O (accept, read bytes, write bytes)
num.io.threads=8        # request processing (parse, validate, write to log, respond)
```

### Request Flow Through Threads

```
1. Acceptor thread (1 per listener):
   Accepts new TCP connections
   Round-robin assignment to Processor threads

2. Processor threads (num.network.threads):
   Read raw bytes from client sockets (NIO)
   Decode request type and routing info
   Place Request object in RequestChannel queue
   Write Response objects back to sockets

3. KafkaRequestHandler threads (num.io.threads):
   Pick requests from RequestChannel queue
   For ProduceRequest: validate + write to log
   For FetchRequest: find segment + build response
   Place Response in Processor's response queue

Queue depth: socketServer.requestMaxBytes controls RequestChannel queue size
```

### Bottleneck Identification

```
If num.network.threads is saturated:
  Symptom: network.idle.time ≈ 0, RequestQueueSize growing
  Fix: increase num.network.threads (typically to 6)

If num.io.threads is saturated:
  Symptom: RequestHandlerAvgIdlePercent < 30%, high produce latency
  Fix: increase num.io.threads (typically to 16) OR improve disk throughput

If disk I/O is saturated:
  Symptom: high await in iostat, I/O thread queue growing
  Fix: NVMe upgrade, JBOD expansion, or reduce replication traffic
```

---

## num.replica.fetchers

```properties
num.replica.fetchers=1  # default — often a bottleneck for high-throughput clusters
```

Number of `ReplicaFetcherThread` instances per follower broker. Each thread handles multiple partitions from the same source broker.

```
With num.replica.fetchers=1:
  Single thread fetches from Broker-0 for ALL partitions it follows on Broker-0
  If Broker-0 has 500 partitions → single thread handles all 500
  Under high write load: thread falls behind → replication lag → ISR shrinkage

With num.replica.fetchers=4:
  4 threads each handle ~125 partitions from Broker-0
  Parallelism: 4x replication throughput per source broker
  
Recommendation: Set to 4 for clusters with > 50 partitions per broker or > 50 MB/s per broker.
Monitor: kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions > 0 → increase fetchers
```

---

## Hardware Recommendations

### Disk

```
Best: NVMe SSD (PCIe Gen 4)
  Sequential write: 3-7 GB/s
  Random read: 700K+ IOPS
  Use for: <2000 partitions per broker, high throughput

Good: SATA SSD
  Sequential write: 400-600 MB/s
  Random read: 80K IOPS
  Use for: <1000 partitions per broker

Acceptable: SAS/HDD (7200 RPM) with JBOD
  Sequential write: 200 MB/s (exploits sequential I/O)
  Random read: 150 IOPS (index lookups are slow — compensated by mmap)
  Use for: low throughput, cost-sensitive deployments

JBOD (Just a Bunch of Disks) > RAID for Kafka:
  JBOD: log.dirs=/disk1,/disk2,/disk3 → Kafka distributes partitions
        Failure of one disk affects only its partitions
  RAID-5: parity calculations add write latency
  Kafka provides redundancy through replication — hardware RAID is unnecessary
```

### Memory

```
Rule: Leave 60-70% of RAM for OS page cache

32 GB machine: 6 GB JVM heap + 24 GB page cache
64 GB machine: 8 GB JVM heap + 48 GB page cache

JVM heap sizing:
  -Xms6g -Xmx6g (or -Xms8g -Xmx8g for large clusters)
  Always set Xms = Xmx (prevent heap resizing during GC)

Page cache sizing target:
  Ideally: hold all data consumed in the last replica.lag.time.max.ms (30s)
  At 500 MB/s throughput: 30s × 500 MB/s = 15 GB of page cache needed
  → 24 GB page cache is comfortable
```

### Network

```
Minimum: 1 GbE (1 Gbps)
  Max throughput: ~100 MB/s (accounting for replication × 3)
  Suitable for: < 30 MB/s producer throughput

Recommended: 10 GbE (10 Gbps)
  Max throughput: ~1 GB/s
  Suitable for: up to 300 MB/s producer throughput per broker

Enterprise: 25 GbE / 40 GbE
  For very high throughput clusters or cross-AZ replication

Consider: dedicated replication NIC if replication traffic is saturating producer NIC
```

---

## Quotas — Preventing Resource Starvation

```bash
# Limit a single producer to 10 MB/s
kafka-configs.sh --bootstrap-server broker:9092 \
  --entity-type clients --entity-name heavy-producer \
  --alter --add-config producer_byte_rate=10485760

# Limit a consumer group to 50 MB/s
kafka-configs.sh --bootstrap-server broker:9092 \
  --entity-type clients --entity-name analytics-consumer \
  --alter --add-config consumer_byte_rate=52428800

# Limit by user (SASL)
kafka-configs.sh --bootstrap-server broker:9092 \
  --entity-type users --entity-name service-account-1 \
  --alter --add-config producer_byte_rate=10485760,consumer_byte_rate=52428800

# CPU quota (percentage of network threads)
kafka-configs.sh --bootstrap-server broker:9092 \
  --entity-type clients --entity-name heavy-producer \
  --alter --add-config request_percentage=25
```

**Broker throttles (delays), does NOT drop:** The broker adds artificial delay to throttle responses rather than rejecting them. Client sees high latency but no errors.

---

## Key Metrics for Performance Tuning

```
# Producer
kafka.producer:type=producer-metrics,name=record-send-rate      → records/sec
kafka.producer:type=producer-metrics,name=byte-rate             → bytes/sec
kafka.producer:type=producer-metrics,name=record-size-avg       → avg record bytes
kafka.producer:type=producer-metrics,name=batch-size-avg        → avg batch utilization
kafka.producer:type=producer-metrics,name=record-queue-time-avg → time in accumulator

# Consumer
kafka.consumer:type=consumer-fetch-manager-metrics,name=fetch-rate
kafka.consumer:type=consumer-fetch-manager-metrics,name=records-consumed-rate
kafka.consumer:type=consumer-fetch-manager-metrics,name=fetch-size-avg

# Broker
kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec         → inbound throughput
kafka.server:type=BrokerTopicMetrics,name=BytesOutPerSec        → outbound throughput
kafka.network:type=RequestMetrics,name=TotalTimeMs,request=Produce  → p99 produce latency
kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent → network thread load
kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent → I/O thread load
```

---

## Summary

| Optimization | Mechanism | Tunable |
|---|---|---|
| Sequential I/O | Append-only log | `segment.bytes`, `segment.ms` |
| Batch compression | Per-batch codec | `compression.type`, `batch.size`, `linger.ms` |
| NIO pipelining | 5 in-flight requests | `max.in.flight.requests.per.connection` |
| Fetch batching | Server-side hold | `fetch.min.bytes`, `fetch.max.wait.ms` |
| Page cache | OS RAM | JVM heap size, `vm.dirty_*` OS params |
| Zero-copy | `sendfile()` | Avoid SSL for maximum throughput |
| Replication parallelism | Multiple fetcher threads | `num.replica.fetchers` |
| Request processing | Thread pools | `num.network.threads`, `num.io.threads` |
