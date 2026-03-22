# 🚀 Apache Kafka — Deep Dive for Java Developers

> Kafka 3.x | Spring Kafka | Kafka Streams | Schema Registry | Production Patterns

---

## 📌 Table of Contents

1. [What is Kafka & Why It Exists](#1-what-is-kafka--why-it-exists)
2. [Core Architecture & Concepts](#2-core-architecture--concepts)
3. [Topics, Partitions & Offsets](#3-topics-partitions--offsets)
4. [Producers — Deep Dive](#4-producers--deep-dive)
5. [Consumers & Consumer Groups — Deep Dive](#5-consumers--consumer-groups--deep-dive)
6. [Brokers, Replication & Fault Tolerance](#6-brokers-replication--fault-tolerance)
7. [Kafka with Spring Boot](#7-kafka-with-spring-boot)
8. [Serialization & Schema Registry (Avro)](#8-serialization--schema-registry-avro)
9. [Kafka Streams](#9-kafka-streams)
10. [Transactions & Exactly-Once Semantics](#10-transactions--exactly-once-semantics)
11. [Consumer Offset Management](#11-consumer-offset-management)
12. [Error Handling & Dead Letter Topics](#12-error-handling--dead-letter-topics)
13. [Kafka Security](#13-kafka-security)
14. [Performance Tuning](#14-performance-tuning)
15. [Kafka Connect](#15-kafka-connect)
16. [Monitoring & Observability](#16-monitoring--observability)
17. [Testing Kafka Applications](#17-testing-kafka-applications)
18. [Real-World Patterns & Architectures](#18-real-world-patterns--architectures)
19. [Interview Questions & Answers](#19-interview-questions--answers)
20. [Complete Reference Summary](#20-complete-reference-summary)

---

## 1. What is Kafka & Why It Exists

### The Problem Kafka Solves

```
BEFORE KAFKA — Point-to-Point Integration Hell:

 Service A ──────────────────────────► Service B
 Service A ──────────────────────────► Service C
 Service A ──────────────────────────► Service D
 Service B ──────────────────────────► Service C
 Service B ──────────────────────────► Service D

 N services = N×(N-1)/2 connections
 10 services = 45 connections — impossible to manage
 Each service must know about every downstream consumer
 Tight coupling: B's downtime blocks A

AFTER KAFKA — Decoupled Event Streaming:

 Service A ──► [Topic: orders] ──► Service B
                                 ──► Service C
                                 ──► Service D
                                 ──► Analytics
                                 ──► Audit Log

 Producers don't know (or care) who consumes
 Consumers read at their own pace
 Messages retained — replay is possible
 Add new consumers without changing producers
```

### Kafka vs Traditional Messaging

```
┌─────────────────┬──────────────────────┬──────────────────────┐
│  Feature        │  RabbitMQ / JMS      │  Apache Kafka        │
├─────────────────┼──────────────────────┼──────────────────────┤
│  Model          │  Message queue       │  Log-based streaming │
│  Retention      │  Gone after consume  │  Configurable (days) │
│  Replay         │  ❌ Not possible     │  ✅ Rewind offset    │
│  Ordering       │  Per queue           │  Per partition       │
│  Throughput     │  ~10K/s              │  ~1M/s               │
│  Consumer state │  Broker tracks       │  Consumer tracks     │
│  Scaling        │  Queue partitioning  │  Native partitions   │
│  Use case       │  Task queues, RPC    │  Event streaming,    │
│                 │  request-reply       │  CDC, log aggregation│
└─────────────────┴──────────────────────┴──────────────────────┘
```

---

## 2. Core Architecture & Concepts

### Kafka Cluster Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         KAFKA CLUSTER                                       │
│                                                                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                                   │
│  │ Broker 1 │  │ Broker 2 │  │ Broker 3 │   ← Each broker hosts             │
│  │          │  │          │  │          │     partitions (leaders/replicas) │
│  │ Lead P0  │  │ Lead P1  │  │ Lead P2  │                                   │
│  │ Rep  P1  │  │ Rep  P2  │  │ Rep  P0  │                                   │
│  │ Rep  P2  │  │ Rep  P0  │  │ Rep  P1  │                                   │
│  └──────────┘  └──────────┘  └──────────┘                                   │
│        │              │              │                                      │
│  ┌─────▼──────────────▼──────────────▼──────┐                               │
│  │              ZooKeeper / KRaft           │  ← Cluster coordination       │
│  │         (Kafka 3.x uses KRaft mode)      │    (no ZK needed in 3.x)      │
│  └──────────────────────────────────────────┘                               │
│                                                                             │
│  ┌─────────────┐                    ┌───────────────────┐                   │
│  │  PRODUCERS  │──── write ──────►  │     TOPICS        │                   │
│  │             │                    │  ┌────────────┐   │                   │
│  │  App A      │                    │  │ Partition 0│   │                   │
│  │  App B      │                    │  │ Partition 1│   │                   │
│  │  App C      │                    │  │ Partition 2│   │                   │
│  └─────────────┘                    │  └────────────┘   │                   │
│                                     └──────────┬────────┘                   │
│                                                │ read                       │
│  ┌─────────────────────────────────────────────▼──────┐                     │
│  │                    CONSUMER GROUPS                 │                     │
│  │   Group "analytics":  [Consumer A] [Consumer B]    │                     │
│  │   Group "billing":    [Consumer C]                 │                     │
│  │   Group "audit":      [Consumer D] [Consumer E]    │                     │
│  └────────────────────────────────────────────────────┘                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Key Terminology

```
┌──────────────────┬──────────────────────────────────────────────────────────┐
│  Term            │  Definition                                              │
├──────────────────┼──────────────────────────────────────────────────────────┤
│  Event/Record    │  Key + Value + Timestamp + Headers. Immutable once written│
│  Topic           │  Named log of events (like a DB table for events)        │
│  Partition       │  Ordered, immutable log segment within a topic           │
│  Offset          │  Position of a record in a partition (0, 1, 2, ...)      │
│  Broker          │  A single Kafka server that stores and serves partitions │
│  Leader          │  Partition replica that handles all reads/writes         │
│  Follower/ISR    │  Replica that syncs from leader (In-Sync Replica)        │
│  Producer        │  Client that publishes events to topics                  │
│  Consumer        │  Client that reads events from topics                    │
│  Consumer Group  │  Set of consumers sharing the work of reading a topic    │
│  Committed Offset│  Last offset consumer acknowledged as processed          │
│  Log Retention   │  How long Kafka keeps messages (time or size based)      │
│  Compaction      │  Keep only latest value per key (like a KV store)        │
│  KRaft           │  Kafka's internal Raft-based consensus (replaces ZK)     │
└──────────────────┴──────────────────────────────────────────────────────────┘
```

---

## 3. Topics, Partitions & Offsets

### Partition Layout

```
Topic: "orders"  (3 partitions, replication-factor=2)

Partition 0:  [0: order-A] [1: order-D] [2: order-G] [3: order-J] ← head
Partition 1:  [0: order-B] [1: order-E] [2: order-H]              ← head
Partition 2:  [0: order-C] [1: order-F] [2: order-I] [3: order-K] ← head

Ordering guarantee: WITHIN a partition only
order-A is always before order-D in Partition 0
No ordering guarantee ACROSS partitions

Key-based partitioning:
  key="user-123" → always → Partition 1  (consistent hashing)
  All events for user-123 are ordered relative to each other
```

### Topic Configuration

```java
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.config.TopicConfig;

public class TopicAdmin {

    // ── Create topic programmatically ─────────────────────────────────────────
    static AdminClient buildAdmin() {
        return AdminClient.create(Map.of(
            "bootstrap.servers", "localhost:9092"
        ));
    }

    static void createTopics() throws Exception {
        try (AdminClient admin = buildAdmin()) {

            // Standard event topic
            NewTopic ordersTopic = new NewTopic("orders", 12, (short) 3)
                .configs(Map.of(
                    TopicConfig.RETENTION_MS_CONFIG,          "604800000",  // 7 days
                    TopicConfig.CLEANUP_POLICY_CONFIG,        "delete",
                    TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG,  "2",
                    TopicConfig.COMPRESSION_TYPE_CONFIG,      "lz4",
                    TopicConfig.MAX_MESSAGE_BYTES_CONFIG,     "1048576"     // 1MB max
                ));

            // Compacted topic (keep latest value per key — like a KV store)
            NewTopic userProfilesTopic = new NewTopic("user-profiles", 6, (short) 3)
                .configs(Map.of(
                    TopicConfig.CLEANUP_POLICY_CONFIG,        "compact",
                    TopicConfig.MIN_CLEANABLE_DIRTY_RATIO_CONFIG, "0.1",
                    TopicConfig.SEGMENT_MS_CONFIG,            "86400000",  // Compact daily
                    TopicConfig.DELETE_RETENTION_MS_CONFIG,  "86400000"
                ));

            // Dead letter topic
            NewTopic dltTopic = new NewTopic("orders.DLT", 3, (short) 3)
                .configs(Map.of(
                    TopicConfig.RETENTION_MS_CONFIG, "2592000000"  // 30 days
                ));

            CreateTopicsResult result = admin.createTopics(
                List.of(ordersTopic, userProfilesTopic, dltTopic));
            result.all().get(); // Wait for completion
            System.out.println("Topics created successfully");
        }
    }

    // ── Describe a topic ──────────────────────────────────────────────────────
    static void describeTopic(String topicName) throws Exception {
        try (AdminClient admin = buildAdmin()) {
            DescribeTopicsResult result = admin.describeTopics(List.of(topicName));
            TopicDescription desc = result.topicNameValues().get(topicName).get();

            System.out.println("Topic: " + desc.name());
            System.out.println("Partitions: " + desc.partitions().size());
            desc.partitions().forEach(p -> {
                System.out.printf("  Partition %d: leader=%s, replicas=%s, isr=%s%n",
                    p.partition(),
                    p.leader().id(),
                    p.replicas().stream().map(n -> String.valueOf(n.id())).toList(),
                    p.isr().stream().map(n -> String.valueOf(n.id())).toList());
            });
        }
    }

    // ── List consumer group offsets ───────────────────────────────────────────
    static void listConsumerLag(String groupId) throws Exception {
        try (AdminClient admin = buildAdmin()) {
            Map<TopicPartition, OffsetAndMetadata> offsets =
                admin.listConsumerGroupOffsets(groupId)
                     .partitionsToOffsetAndMetadata().get();

            // Get end offsets to calculate lag
            Map<TopicPartition, OffsetSpec> specs = offsets.keySet().stream()
                .collect(java.util.stream.Collectors.toMap(tp -> tp,
                         tp -> OffsetSpec.latest()));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets =
                admin.listOffsets(specs).all().get();

            System.out.printf("%-30s %8s %8s %8s%n",
                "Topic-Partition", "Consumed", "EndOffset", "Lag");
            System.out.println("-".repeat(60));

            offsets.forEach((tp, oam) -> {
                long consumed = oam.offset();
                long end      = endOffsets.get(tp).offset();
                long lag      = end - consumed;
                System.out.printf("%-30s %8d %8d %8d%s%n",
                    tp.topic() + "-" + tp.partition(),
                    consumed, end, lag,
                    lag > 1000 ? " ⚠ HIGH LAG!" : "");
            });
        }
    }
}
```

---

## 4. Producers — Deep Dive

### Producer Internals

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      PRODUCER INTERNALS                                     │
│                                                                             │
│  send(record)                                                               │
│      │                                                                      │
│      ▼                                                                      │
│  ┌──────────────┐     ┌─────────────┐     ┌────────────────────────────┐   │
│  │  Serializer  │────►│ Partitioner │────►│   RecordAccumulator        │   │
│  │  Key + Value │     │             │     │                            │   │
│  └──────────────┘     │ key hash OR │     │  ┌──────┐ ┌──────┐        │   │
│                        │ round-robin │     │  │Batch │ │Batch │ ...    │   │
│                        │ OR custom   │     │  │ P0   │ │ P1   │        │   │
│                        └─────────────┘     │  └──────┘ └──────┘        │   │
│                                            └──────────────┬─────────────┘   │
│                                                           │                 │
│                                              linger.ms OR batch full        │
│                                                           │                 │
│                                            ┌──────────────▼─────────────┐  │
│                                            │       Sender Thread        │  │
│                                            │  compress + send to broker │  │
│                                            └────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘

Key configs that interact:
  batch.size   = max bytes per batch (default 16KB)
  linger.ms    = wait time to fill batch (default 0ms — send immediately)
  buffer.memory= total producer buffer (default 32MB)
  compression.type = none | gzip | snappy | lz4 | zstd
```

### Producer Configuration & Usage

```java
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;

public class KafkaProducerDemo {

    // ── Build a high-throughput producer ─────────────────────────────────────
    static KafkaProducer<String, String> buildProducer() {
        Properties props = new Properties();

        // Connection
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker1:9092,broker2:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // ── Reliability settings ──────────────────────────────────────────────
        // acks=all: leader + all ISR replicas must acknowledge
        // acks=1:   only leader must acknowledge (risk of data loss if leader fails)
        // acks=0:   fire-and-forget (fastest, no durability)
        props.put(ProducerConfig.ACKS_CONFIG, "all");

        // Retry on transient failures (network blips, leader elections)
        props.put(ProducerConfig.RETRIES_CONFIG,                     Integer.MAX_VALUE);
        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG,            100);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        // Set to 1 for strict per-partition ordering with retries
        // With idempotence=true (below), 5 is safe

        // Idempotent producer: exactly-once delivery per session
        // (deduplicates retries using sequence numbers)
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        // ── Throughput settings ───────────────────────────────────────────────
        props.put(ProducerConfig.BATCH_SIZE_CONFIG,         64 * 1024);  // 64KB batches
        props.put(ProducerConfig.LINGER_MS_CONFIG,          10);         // Wait 10ms to fill batch
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG,      64 * 1024 * 1024L); // 64MB buffer
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG,   "lz4");      // Fast compression

        // ── Timeout settings ──────────────────────────────────────────────────
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,  30_000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);   // Total retry window

        return new KafkaProducer<>(props);
    }

    // ── Fire-and-forget (fastest, possible data loss) ─────────────────────────
    static void sendFireAndForget(KafkaProducer<String, String> producer) {
        ProducerRecord<String, String> record =
            new ProducerRecord<>("orders", "user-123", "{\"orderId\":\"O-001\"}");
        producer.send(record); // No callback, no waiting
    }

    // ── Async send with callback (recommended for most cases) ────────────────
    static void sendAsync(KafkaProducer<String, String> producer) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
            "orders",                           // topic
            null,                               // partition (null = use partitioner)
            System.currentTimeMillis(),         // timestamp
            "user-123",                         // key (determines partition)
            "{\"orderId\":\"O-002\"}",          // value
            new RecordHeaders()                 // optional headers
                .add("source", "checkout-service".getBytes())
                .add("version", "v2".getBytes())
        );

        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                // Called on error (in sender thread — don't block here!)
                log.error("Failed to send order: topic={} partition={} offset={}",
                    metadata != null ? metadata.topic()     : "unknown",
                    metadata != null ? metadata.partition() : -1,
                    metadata != null ? metadata.offset()    : -1,
                    exception);
            } else {
                log.debug("Sent: topic={} partition={} offset={} timestamp={}",
                    metadata.topic(), metadata.partition(),
                    metadata.offset(), metadata.timestamp());
            }
        });
    }

    // ── Synchronous send (blocks until ack — use sparingly) ──────────────────
    static void sendSync(KafkaProducer<String, String> producer) throws Exception {
        ProducerRecord<String, String> record =
            new ProducerRecord<>("orders", "user-123", "{\"orderId\":\"O-003\"}");

        RecordMetadata metadata = producer.send(record).get(); // Blocks!
        System.out.printf("Sent synchronously to partition=%d offset=%d%n",
            metadata.partition(), metadata.offset());
    }

    // ── Batch send with flush ─────────────────────────────────────────────────
    static void sendBatch(KafkaProducer<String, String> producer,
                          List<Order> orders) throws Exception {
        List<Future<RecordMetadata>> futures = new ArrayList<>();

        for (Order order : orders) {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                "orders", order.userId(), order.toJson());
            futures.add(producer.send(record)); // Non-blocking send
        }

        producer.flush(); // Wait for all batches to be sent

        // Check results
        int success = 0, failed = 0;
        for (Future<RecordMetadata> f : futures) {
            try { f.get(); success++; }
            catch (Exception e) { failed++; log.error("Batch send failure", e); }
        }
        System.out.printf("Batch complete: %d success, %d failed%n", success, failed);
    }

    // ── Custom Partitioner ────────────────────────────────────────────────────
    public static class PriorityPartitioner implements Partitioner {
        @Override
        public int partition(String topic, Object key, byte[] keyBytes,
                             Object value, byte[] valueBytes, Cluster cluster) {
            int numPartitions = cluster.partitionCountForTopic(topic);

            // VIP customers always go to dedicated partitions (0,1,2)
            if (key != null && key.toString().startsWith("VIP-")) {
                return Math.abs(key.hashCode() % 3); // Partitions 0, 1, or 2
            }

            // Regular customers use remaining partitions
            if (keyBytes != null) {
                return 3 + Math.abs(java.util.Arrays.hashCode(keyBytes)
                           % (numPartitions - 3));
            }
            return (int)(Math.random() * (numPartitions - 3)) + 3;
        }

        @Override public void close() {}
        @Override public void configure(Map<String, ?> configs) {}
    }

    static org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(KafkaProducerDemo.class);
    record Order(String userId, String orderId) {
        String toJson() { return "{\"orderId\":\"" + orderId + "\"}"; }
    }
}
```

---

## 5. Consumers & Consumer Groups — Deep Dive

### Consumer Group Rebalancing

```
Topic: "orders" — 6 partitions

Initial state (Group "billing", 2 consumers):
  Consumer-1 reads: P0, P1, P2
  Consumer-2 reads: P3, P4, P5

After Consumer-3 joins (rebalance triggered):
  Consumer-1 reads: P0, P1
  Consumer-2 reads: P2, P3
  Consumer-3 reads: P4, P5

After Consumer-1 crashes (rebalance triggered):
  Consumer-2 reads: P0, P1, P2, P3
  Consumer-3 reads: P4, P5

Rules:
  • Each partition assigned to exactly ONE consumer in a group
  • If consumers > partitions: some consumers sit idle
  • Max parallelism = number of partitions
  • DURING rebalance: all consumption stops (stop-the-world)

Static Membership (Java 2.4+):
  Use group.instance.id to prevent rebalance on short restarts
  Consumer rejoins and reclaims its partitions without full rebalance
```

### Consumer Configuration & Usage

```java
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;

public class KafkaConsumerDemo {

    // ── Build a reliable consumer ─────────────────────────────────────────────
    static KafkaConsumer<String, String> buildConsumer(String groupId) {
        Properties props = new Properties();

        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker1:9092,broker2:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG,          groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class);

        // ── Offset reset: what to do when no committed offset exists ──────────
        // earliest: read from beginning of topic (new group sees all history)
        // latest:   start from now (new group misses old messages)
        // none:     throw exception if no committed offset
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // ── Manual commit (recommended for reliability) ───────────────────────
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // Auto-commit risks: offset committed before processing completes
        // Can cause duplicate processing after crash (committed but not processed)

        // ── Fetch tuning ──────────────────────────────────────────────────────
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,        500);   // Max per poll
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG,         1024);  // Wait for 1KB
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG,       500);   // Or 500ms
        props.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG,
                  1 * 1024 * 1024);  // 1MB per partition per fetch

        // ── Session / heartbeat ───────────────────────────────────────────────
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,      45_000); // Max since last heartbeat
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG,   3_000);  // Send every 3s
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,    300_000);// Max between polls (5min)
        // If processing takes > max.poll.interval.ms, consumer is removed from group!

        // ── Static group membership (prevents rebalance on restart) ───────────
        // props.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, "consumer-pod-1");

        return new KafkaConsumer<>(props);
    }

    // ── Standard poll loop with manual commit ─────────────────────────────────
    static void consumeWithManualCommit(String groupId, String topic)
            throws InterruptedException {

        KafkaConsumer<String, String> consumer = buildConsumer(groupId);
        consumer.subscribe(List.of(topic));

        // Shutdown hook for graceful stop
        AtomicBoolean running = new AtomicBoolean(true);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            consumer.wakeup(); // Causes poll() to throw WakeupException
        }));

        try {
            while (running.get()) {
                ConsumerRecords<String, String> records =
                    consumer.poll(Duration.ofMillis(100));

                if (records.isEmpty()) continue;

                for (ConsumerRecord<String, String> record : records) {
                    try {
                        processRecord(record);
                    } catch (RetryableException e) {
                        // Handle — will be re-processed after restart
                        log.error("Retryable error on offset {}", record.offset(), e);
                        consumer.seek(new TopicPartition(record.topic(),
                            record.partition()), record.offset()); // Rewind
                        break; // Re-process from this offset
                    }
                }

                // ✅ Synchronous commit — guarantees offset is persisted
                // before processing next batch
                consumer.commitSync();

                // OR async commit (faster, may lose offset on failure)
                // consumer.commitAsync((offsets, exception) -> {
                //     if (exception != null)
                //         log.error("Commit failed for offsets: {}", offsets, exception);
                // });
            }
        } catch (WakeupException e) {
            // Expected — raised by wakeup() in shutdown hook
        } finally {
            consumer.commitSync(); // Final commit before closing
            consumer.close();
            log.info("Consumer closed gracefully");
        }
    }

    // ── Per-partition processing with commit ──────────────────────────────────
    static void consumePerPartition(String groupId, String topic) {
        KafkaConsumer<String, String> consumer = buildConsumer(groupId);
        consumer.subscribe(List.of(topic));

        while (true) {
            ConsumerRecords<String, String> records =
                consumer.poll(Duration.ofMillis(100));

            // Process partition-by-partition (more granular commit)
            Map<TopicPartition, OffsetAndMetadata> commitMap = new HashMap<>();

            for (TopicPartition partition : records.partitions()) {
                List<ConsumerRecord<String, String>> partRecords =
                    records.records(partition);

                for (ConsumerRecord<String, String> record : partRecords) {
                    processRecord(record);
                }

                // Commit offset = last processed + 1
                long lastOffset = partRecords.get(partRecords.size() - 1).offset();
                commitMap.put(partition, new OffsetAndMetadata(lastOffset + 1));
            }

            consumer.commitSync(commitMap); // Commit per partition
        }
    }

    // ── Assign specific partitions (no group rebalancing) ────────────────────
    static void consumeSpecificPartitions() {
        KafkaConsumer<String, String> consumer = buildConsumer("manual");

        // Manually assign partitions — consumer doesn't join a group
        List<TopicPartition> partitions = List.of(
            new TopicPartition("orders", 0),
            new TopicPartition("orders", 1)
        );
        consumer.assign(partitions);

        // Seek to specific offset (useful for replay)
        consumer.seekToBeginning(partitions);    // Start from beginning
        // consumer.seekToEnd(partitions);        // Start from latest
        // consumer.seek(partition, offset);      // Start from specific offset

        while (true) {
            ConsumerRecords<String, String> records =
                consumer.poll(Duration.ofMillis(100));
            records.forEach(r -> processRecord(r));
            consumer.commitSync();
        }
    }

    // ── ConsumerRebalanceListener — handle partition assignment changes ────────
    static void consumeWithRebalanceListener(String groupId, String topic) {
        KafkaConsumer<String, String> consumer = buildConsumer(groupId);

        consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                // Called BEFORE partitions are reassigned to other consumers
                // Commit current offsets so the next consumer starts correctly
                log.info("Partitions revoked: {} — committing offsets", partitions);
                consumer.commitSync();
                partitions.forEach(tp -> saveState(tp)); // Save any local state
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                // Called AFTER partitions are assigned to this consumer
                log.info("Partitions assigned: {}", partitions);
                partitions.forEach(tp -> restoreState(tp)); // Restore local state
            }

            @Override
            public void onPartitionsLost(Collection<TopicPartition> partitions) {
                // Called when partitions are taken away without clean revocation
                // (e.g., session timeout) — can't commit, just clean up
                log.warn("Partitions lost unexpectedly: {}", partitions);
            }
        });

        while (true) {
            ConsumerRecords<String, String> records =
                consumer.poll(Duration.ofMillis(100));
            records.forEach(r -> processRecord(r));
            consumer.commitSync();
        }
    }

    static void processRecord(ConsumerRecord<String, String> r) {
        log.info("topic={} partition={} offset={} key={} value={}",
            r.topic(), r.partition(), r.offset(), r.key(), r.value());
    }

    static void saveState(TopicPartition tp) {}
    static void restoreState(TopicPartition tp) {}

    static org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(KafkaConsumerDemo.class);
    static class RetryableException extends RuntimeException {
        RetryableException(String msg) { super(msg); }
    }
}
```

---

## 6. Brokers, Replication & Fault Tolerance

### Leader Election & ISR

```
Topic "payments" — partition 0, replication-factor=3

Normal state:
  Broker 1: LEADER   (handles all reads + writes)
  Broker 2: FOLLOWER (in ISR — synced up)
  Broker 3: FOLLOWER (in ISR — synced up)

  Producer writes with acks=all:
  → Write to Broker 1 (leader)
  → Broker 2 replicates
  → Broker 3 replicates
  → Leader ACKs producer only after ALL ISR replicas confirm

Broker 1 crashes:
  KRaft/ZK detects leader failure
  New leader elected from ISR (e.g., Broker 2)
  Broker 3 now follows Broker 2
  Producers/consumers automatically redirect

  If acks=all + min.insync.replicas=2:
  → With 2 remaining ISR replicas: writes continue safely
  → With only 1 replica alive: writes BLOCKED (NotEnoughReplicasException)
  → Guarantees: no data loss even with 1 broker failure

Unclean leader election:
  unclean.leader.election.enable=false (default, recommended)
  → Never elect an out-of-sync replica as leader
  → Prefer availability loss over data loss
  unclean.leader.election.enable=true
  → May lose messages but topic stays available
```

### Broker Configuration (server.properties)

```properties
# ── Identity ──────────────────────────────────────────────────────────────────
broker.id=1
node.id=1

# ── Listeners ─────────────────────────────────────────────────────────────────
listeners=PLAINTEXT://0.0.0.0:9092,SSL://0.0.0.0:9093
advertised.listeners=PLAINTEXT://broker1.example.com:9092

# ── Log storage ───────────────────────────────────────────────────────────────
log.dirs=/var/kafka/data1,/var/kafka/data2   # Multiple disks = better throughput
log.retention.hours=168                       # 7 days default
log.retention.bytes=-1                        # Unlimited size (use time-based)
log.segment.bytes=1073741824                  # 1GB per segment file
log.cleanup.policy=delete

# ── Replication ───────────────────────────────────────────────────────────────
default.replication.factor=3
min.insync.replicas=2                         # Require 2 ISR before ack
unclean.leader.election.enable=false          # No data loss

# ── Performance ───────────────────────────────────────────────────────────────
num.network.threads=8                         # Network handling threads
num.io.threads=16                             # I/O handling threads
socket.send.buffer.bytes=102400
socket.receive.buffer.bytes=102400
socket.request.max.bytes=104857600           # 100MB max request

# ── KRaft mode (Kafka 3.x — no ZooKeeper) ────────────────────────────────────
process.roles=broker,controller
controller.quorum.voters=1@broker1:9093,2@broker2:9093,3@broker3:9093
```

---

## 7. Kafka with Spring Boot

### Setup

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

```yaml
# application.yml
spring:
  kafka:
    bootstrap-servers: localhost:9092

    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
      retries: 2147483647
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 5
        linger.ms: 10
        batch.size: 65536
        compression.type: lz4

    consumer:
      group-id: my-service
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      auto-offset-reset: earliest
      enable-auto-commit: false
      max-poll-records: 500
      properties:
        spring.json.trusted.packages: "com.example.events"
        max.poll.interval.ms: 300000
        session.timeout.ms: 45000

    listener:
      ack-mode: MANUAL_IMMEDIATE   # Manual offset commit
      concurrency: 3               # 3 threads per @KafkaListener container
      type: BATCH                  # Receive List<ConsumerRecord> instead of single

    # Schema Registry (Confluent)
    properties:
      schema.registry.url: http://schema-registry:8081
```

### Producer with Spring

```java
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.*;

@Service
public class OrderEventProducer {

    private final KafkaTemplate<String, OrderEvent> kafkaTemplate;

    public OrderEventProducer(KafkaTemplate<String, OrderEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    // ── Simple send ────────────────────────────────────────────────────────────
    public void publishOrderCreated(OrderEvent event) {
        kafkaTemplate.send("orders", event.orderId(), event);
    }

    // ── Send with full control ─────────────────────────────────────────────────
    public CompletableFuture<SendResult<String, OrderEvent>> publishWithResult(
            OrderEvent event) {

        ProducerRecord<String, OrderEvent> record = new ProducerRecord<>(
            "orders",
            null,
            System.currentTimeMillis(),
            event.orderId(),
            event
        );

        // Add headers
        record.headers()
            .add("eventType",   "ORDER_CREATED".getBytes())
            .add("sourceApp",   "checkout-service".getBytes())
            .add("traceId",     getTraceId().getBytes());

        return kafkaTemplate.send(record)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish order {}: {}",
                        event.orderId(), ex.getMessage());
                    meterRegistry.counter("kafka.publish.error",
                        "topic", "orders").increment();
                } else {
                    RecordMetadata meta = result.getRecordMetadata();
                    log.info("Published order {} to partition={} offset={}",
                        event.orderId(), meta.partition(), meta.offset());
                    meterRegistry.counter("kafka.publish.success",
                        "topic", "orders").increment();
                }
            });
    }

    // ── Send to specific partition ─────────────────────────────────────────────
    public void publishToPartition(OrderEvent event, int partition) {
        kafkaTemplate.send("orders", partition,
            event.orderId(), event);
    }

    private String getTraceId() {
        return java.util.UUID.randomUUID().toString();
    }

    @Autowired io.micrometer.core.instrument.MeterRegistry meterRegistry;
    static org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(OrderEventProducer.class);
}

// ── Domain Event ──────────────────────────────────────────────────────────────
public record OrderEvent(
    String orderId,
    String userId,
    String status,
    java.math.BigDecimal total,
    java.time.Instant createdAt
) {}
```

### Consumer with Spring

```java
import org.springframework.kafka.annotation.*;
import org.springframework.kafka.support.*;
import org.springframework.messaging.handler.annotation.*;

@Component
public class OrderEventConsumer {

    // ── Simple single-record listener ─────────────────────────────────────────
    @KafkaListener(topics = "orders", groupId = "billing-service")
    public void handleOrder(OrderEvent event,
                            @Header(KafkaHeaders.RECEIVED_TOPIC)     String topic,
                            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                            @Header(KafkaHeaders.OFFSET)             long offset) {
        log.info("Received order {} from partition={} offset={}",
            event.orderId(), partition, offset);
        billingService.processOrder(event);
    }

    // ── Manual acknowledgment ─────────────────────────────────────────────────
    @KafkaListener(topics = "orders", groupId = "inventory-service",
                   containerFactory = "manualAckFactory")
    public void handleOrderWithAck(ConsumerRecord<String, OrderEvent> record,
                                   Acknowledgment ack) {
        try {
            inventoryService.reserve(record.value());
            ack.acknowledge(); // Commit this offset
        } catch (InventoryException e) {
            log.error("Failed to reserve inventory for order {}",
                record.value().orderId(), e);
            // Don't ack — will be redelivered
            // For infinite retries this is risky — use retry + DLT instead
        }
    }

    // ── Batch listener ────────────────────────────────────────────────────────
    @KafkaListener(topics = "orders", groupId = "analytics",
                   containerFactory = "batchFactory")
    public void handleOrderBatch(
            List<ConsumerRecord<String, OrderEvent>> records,
            Acknowledgment ack) {

        log.info("Processing batch of {} orders", records.size());

        // Bulk process
        List<OrderEvent> events = records.stream()
            .map(ConsumerRecord::value)
            .toList();
        analyticsService.bulkProcess(events);

        ack.acknowledge(); // Commit all records in batch
    }

    // ── Listen to multiple topics ─────────────────────────────────────────────
    @KafkaListener(topics = {"orders", "order-updates"},
                   groupId = "audit")
    public void handleMultipleTopics(
            ConsumerRecord<String, OrderEvent> record) {
        auditService.log(record.topic(), record.key(), record.value());
    }

    // ── Listen to specific partitions ─────────────────────────────────────────
    @KafkaListener(
        topicPartitions = @TopicPartition(
            topic = "orders",
            partitions = {"0", "1"},               // Static partition assignment
            partitionOffsets = @PartitionOffset(
                partition = "2", initialOffset = "0") // Partition 2 from beginning
        ),
        groupId = "partition-specific"
    )
    public void handleSpecificPartitions(ConsumerRecord<String, OrderEvent> record) {
        // Processes only from partitions 0, 1, 2
    }

    // ── Header-based routing ──────────────────────────────────────────────────
    @KafkaListener(topics = "orders", groupId = "router")
    public void routeByEventType(
            ConsumerRecord<String, OrderEvent> record,
            @Header(value = "eventType", required = false) String eventType) {

        if ("ORDER_CREATED".equals(eventType)) {
            handleNewOrder(record.value());
        } else if ("ORDER_CANCELLED".equals(eventType)) {
            handleCancellation(record.value());
        }
    }

    void handleNewOrder(OrderEvent e) {}
    void handleCancellation(OrderEvent e) {}

    @Autowired BillingService billingService;
    @Autowired InventoryService inventoryService;
    @Autowired AnalyticsService analyticsService;
    @Autowired AuditService auditService;

    interface BillingService  { void processOrder(OrderEvent e); }
    interface InventoryService{ void reserve(OrderEvent e); }
    interface AnalyticsService{ void bulkProcess(List<OrderEvent> e); }
    interface AuditService    { void log(String t, String k, OrderEvent e); }
    static class InventoryException extends RuntimeException {}
    static org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(OrderEventConsumer.class);
}
```

### KafkaListenerContainerFactory Configuration

```java
@Configuration
public class KafkaConsumerConfig {

    @Autowired
    private ConsumerFactory<String, OrderEvent> consumerFactory;

    // ── Manual ack container factory ──────────────────────────────────────────
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, OrderEvent>
            manualAckFactory() {
        ConcurrentKafkaListenerContainerFactory<String, OrderEvent> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(
            ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setConcurrency(3);
        return factory;
    }

    // ── Batch container factory ───────────────────────────────────────────────
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, OrderEvent>
            batchFactory() {
        ConcurrentKafkaListenerContainerFactory<String, OrderEvent> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(
            ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setConcurrency(3);
        return factory;
    }
}
```

---

## 8. Serialization & Schema Registry (Avro)

### Why Schema Registry?

```
Without Schema Registry:
  Producer sends: {"orderId":"123", "total":99.99, "userId":"U-1"}
  Consumer expects: {"orderId", "total", "userId", "currency"}
  → Deserialization fails or silently drops "currency"
  → No contract enforcement between teams

With Schema Registry:
  Schemas stored centrally in Confluent Schema Registry
  Compatibility rules enforced: BACKWARD, FORWARD, FULL
  Binary encoding with schema ID: [magic byte][schema ID][avro bytes]
  Schema evolution without breaking consumers
```

### Avro Schema & Usage

```java
// ── 1. Define Avro schema (src/main/avro/OrderEvent.avsc) ─────────────────────
/*
{
  "type": "record",
  "name": "OrderEvent",
  "namespace": "com.example.events",
  "fields": [
    {"name": "orderId",   "type": "string"},
    {"name": "userId",    "type": "string"},
    {"name": "status",    "type": {"type": "enum", "name": "OrderStatus",
                          "symbols": ["CREATED","PROCESSING","SHIPPED","CANCELLED"]}},
    {"name": "total",     "type": {"type": "bytes", "logicalType": "decimal",
                          "precision": 10, "scale": 2}},
    {"name": "createdAt", "type": {"type": "long",  "logicalType": "timestamp-millis"}},
    {"name": "currency",  "type": "string", "default": "USD"}
  ]
}
*/

// ── 2. Maven plugin generates OrderEvent.java from .avsc ──────────────────────
/*
<plugin>
  <groupId>org.apache.avro</groupId>
  <artifactId>avro-maven-plugin</artifactId>
  <executions><execution>
    <goals><goal>schema</goal></goals>
    <configuration>
      <sourceDirectory>${project.basedir}/src/main/avro</sourceDirectory>
    </configuration>
  </execution></executions>
</plugin>
*/

// ── 3. Spring Kafka with Avro serializer ──────────────────────────────────────
import io.confluent.kafka.serializers.*;

@Configuration
public class AvroKafkaConfig {

    @Bean
    public ProducerFactory<String, OrderEvent> avroProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
            StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            KafkaAvroSerializer.class);
        props.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG,
            "http://schema-registry:8081");
        props.put(AbstractKafkaAvroSerDeConfig.AUTO_REGISTER_SCHEMAS,
            true);  // false in prod — register schemas manually
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public ConsumerFactory<String, OrderEvent> avroConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            KafkaAvroDeserializer.class);
        props.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG,
            "http://schema-registry:8081");
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG,
            true);  // Return generated OrderEvent class (not GenericRecord)
        return new DefaultKafkaConsumerFactory<>(props);
    }
}

// ── 4. Schema evolution rules ─────────────────────────────────────────────────
/*
  BACKWARD compatible (default):
    ✅ Add optional field with default
    ✅ Remove field
    ❌ Add required field (no default)
    ❌ Change field type

  To add a new field safely:
  Old schema: {"name":"orderId","type":"string"}
  New schema: {"name":"orderId","type":"string"},
              {"name":"currency","type":"string","default":"USD"} ← default required!

  Old consumers reading new messages: see "USD" for currency (default)
  New consumers reading old messages: see "USD" for currency (default)
*/
```

---

## 9. Kafka Streams

### Kafka Streams Architecture

```
Input Topics → KStream/KTable → Operations → Output Topics

Topology (DAG of processors):
  Source Processor → Transform → Aggregate → Sink Processor

Key features:
  • Library (not a cluster) — runs inside your JVM
  • Fault-tolerant via changelog topics in Kafka
  • Exactly-once processing with EOS
  • Local state stores (RocksDB)
  • Event time processing with windowing
```

### Kafka Streams Application

```java
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.*;
import java.time.Duration;

public class OrderStreamProcessor {

    // ── Real-time order analytics ──────────────────────────────────────────────
    public static Topology buildTopology() {
        StreamsBuilder builder = new StreamsBuilder();

        // ── Source streams ────────────────────────────────────────────────────
        KStream<String, OrderEvent> orders =
            builder.stream("orders",
                Consumed.with(Serdes.String(), orderEventSerde()));

        KTable<String, UserProfile> users =
            builder.table("user-profiles",
                Consumed.with(Serdes.String(), userProfileSerde()));

        // ── Filter ────────────────────────────────────────────────────────────
        KStream<String, OrderEvent> validOrders = orders
            .filter((key, order) -> order != null && order.total() > 0)
            .filterNot((key, order) -> order.status().equals("CANCELLED"));

        // ── Transform (map, flatMap, mapValues) ───────────────────────────────
        KStream<String, EnrichedOrder> enriched = validOrders
            .mapValues(order -> new EnrichedOrder(order, "PROCESSED"))
            .map((key, order) -> new KeyValue<>(
                order.userId(),    // Re-key by userId
                order
            ));

        // ── Join KStream with KTable (enrich orders with user data) ───────────
        KStream<String, EnrichedOrder> withUserData = enriched
            .join(
                users,
                (order, user) -> new EnrichedOrder(order, user),
                Joined.with(Serdes.String(), enrichedOrderSerde(), userProfileSerde())
            );

        // ── Branch (route to multiple topics) ─────────────────────────────────
        Map<String, KStream<String, EnrichedOrder>> branches = withUserData
            .split(Named.as("branch-"))
            .branch((k, v) -> v.total() > 1000,   Named.as("high-value"))
            .branch((k, v) -> v.total() > 100,    Named.as("medium-value"))
            .defaultBranch(Named.as("low-value"));

        branches.get("branch-high-value")
            .to("orders-high-value",
                Produced.with(Serdes.String(), enrichedOrderSerde()));
        branches.get("branch-medium-value")
            .to("orders-medium-value",
                Produced.with(Serdes.String(), enrichedOrderSerde()));
        branches.get("branch-low-value")
            .to("orders-low-value",
                Produced.with(Serdes.String(), enrichedOrderSerde()));

        // ── Windowed aggregation: orders per minute per user ──────────────────
        KTable<Windowed<String>, Long> ordersPerMinute = validOrders
            .groupByKey()
            .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(1)))
            .count(Materialized.<String, Long, WindowStore<Bytes, byte[]>>as(
                "orders-per-minute-store")
                .withKeySerde(Serdes.String())
                .withValueSerde(Serdes.Long()));

        ordersPerMinute
            .toStream()
            .map((windowedKey, count) -> new KeyValue<>(
                windowedKey.key() + "@" +
                windowedKey.window().startTime().toString(),
                count
            ))
            .to("order-counts-per-minute",
                Produced.with(Serdes.String(), Serdes.Long()));

        // ── Session window: user activity sessions ─────────────────────────────
        KTable<Windowed<String>, Long> sessionCounts = validOrders
            .groupByKey()
            .windowedBy(SessionWindows.ofInactivityGapWithNoGrace(
                Duration.ofMinutes(30)))  // Session ends after 30min inactivity
            .count();

        // ── Tumbling window: hourly revenue ───────────────────────────────────
        KTable<Windowed<String>, Double> hourlyRevenue = validOrders
            .groupBy((key, order) -> new KeyValue<>("all", order),
                Grouped.with(Serdes.String(), orderEventSerde()))
            .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofHours(1)))
            .aggregate(
                () -> 0.0,
                (key, order, total) -> total + order.total(),
                Materialized.<String, Double, WindowStore<Bytes, byte[]>>as(
                    "hourly-revenue-store")
                    .withKeySerde(Serdes.String())
                    .withValueSerde(Serdes.Double())
            );

        // ── KStream-KStream join: correlate orders with payments ───────────────
        KStream<String, PaymentEvent> payments =
            builder.stream("payments",
                Consumed.with(Serdes.String(), paymentEventSerde()));

        KStream<String, OrderPaymentPair> matched = validOrders.join(
            payments,
            (order, payment) -> new OrderPaymentPair(order, payment),
            JoinWindows.ofTimeDifferenceWithNoGrace(Duration.ofMinutes(5)),
            StreamJoined.with(Serdes.String(), orderEventSerde(), paymentEventSerde())
        );
        matched.to("matched-order-payments");

        return builder.build();
    }

    // ── Interactive queries: read local state store ────────────────────────────
    public static class OrderCountQueryService {
        private final KafkaStreams streams;

        public OrderCountQueryService(KafkaStreams streams) {
            this.streams = streams;
        }

        public long getOrderCount(String userId) {
            ReadOnlyKeyValueStore<String, Long> store =
                streams.store(StoreQueryParameters.fromNameAndType(
                    "orders-per-minute-store",
                    QueryableStoreTypes.keyValueStore()));

            Long count = store.get(userId);
            return count != null ? count : 0L;
        }

        // Query windowed store
        public List<Long> getOrderCountsInWindow(String userId,
                                                  Instant from, Instant to) {
            ReadOnlyWindowStore<String, Long> windowStore =
                streams.store(StoreQueryParameters.fromNameAndType(
                    "orders-per-minute-store",
                    QueryableStoreTypes.windowStore()));

            List<Long> counts = new ArrayList<>();
            try (WindowStoreIterator<Long> iter =
                    windowStore.fetch(userId, from, to)) {
                while (iter.hasNext()) {
                    counts.add(iter.next().value);
                }
            }
            return counts;
        }
    }

    // ── Application wiring ────────────────────────────────────────────────────
    public static KafkaStreams buildApp() {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG,    "order-processor");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 4);
        props.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 3);
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 100);

        // Exactly-once processing
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG,
            StreamsConfig.EXACTLY_ONCE_V2);

        // RocksDB state store config
        props.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG,
            10 * 1024 * 1024L); // 10MB cache

        // Logging: restore from changelog on restart
        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
            LogAndContinueExceptionHandler.class);

        Topology topology = buildTopology();
        KafkaStreams streams = new KafkaStreams(topology, props);

        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(
            new Thread(streams::close));

        // State listener
        streams.setStateListener((newState, oldState) ->
            log.info("Streams state: {} → {}", oldState, newState));

        streams.start();
        return streams;
    }

    static org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(OrderStreamProcessor.class);

    // Serde factories (use Avro or Jackson in practice)
    static Serde<OrderEvent>    orderEventSerde()    { return null; }
    static Serde<EnrichedOrder> enrichedOrderSerde() { return null; }
    static Serde<UserProfile>   userProfileSerde()   { return null; }
    static Serde<PaymentEvent>  paymentEventSerde()  { return null; }

    record OrderEvent(String orderId, String userId, String status, double total) {}
    record PaymentEvent(String orderId, double amount) {}
    record UserProfile(String userId, String name) {}
    record EnrichedOrder(Object data, Object extra) {
        double total() { return 0; }
        String userId() { return ""; }
        EnrichedOrder(EnrichedOrder o, UserProfile u) { this(o, (Object)u); }
    }
    record OrderPaymentPair(OrderEvent order, PaymentEvent payment) {}
}
```

---

## 10. Transactions & Exactly-Once Semantics

### Delivery Guarantees

```
┌──────────────────┬───────────────────────────────────────────────────────────┐
│  Guarantee        │  Meaning                                                 │
├──────────────────┼───────────────────────────────────────────────────────────┤
│  At-most-once     │  May lose messages (acks=0, no retries)                 │
│  At-least-once    │  May duplicate (acks=all, retries, no idempotence)       │
│  Exactly-once     │  No loss, no duplicates (idempotent + transactional)    │
└──────────────────┴───────────────────────────────────────────────────────────┘

Idempotent Producer (acks=all + enable.idempotence=true):
  • Each message gets sequence number
  • Broker deduplicates retries within a session
  • Only per-partition, per-producer-session guarantee

Transactional Producer (read-process-write in one atomic unit):
  • Read from topic A, process, write to topic B + commit offsets
  • ALL writes succeed or ALL are rolled back
  • Consumers with isolation.level=read_committed skip uncommitted messages
```

### Transactional Producer

```java
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.clients.consumer.*;

public class ExactlyOnceProcessor {

    // ── Transactional producer setup ──────────────────────────────────────────
    static KafkaProducer<String, String> buildTransactionalProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,  "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
            StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            StringSerializer.class);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG,               "all");
        props.put(ProducerConfig.RETRIES_CONFIG,            Integer.MAX_VALUE);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

        // Unique transactional ID per producer instance
        // Use different IDs for different application instances
        props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG,
            "order-processor-txn-1");

        KafkaProducer<String, String> producer = new KafkaProducer<>(props);
        producer.initTransactions(); // Register with broker
        return producer;
    }

    // ── Read-Process-Write transaction ────────────────────────────────────────
    static void processWithTransaction(
            KafkaConsumer<String, String>  consumer,
            KafkaProducer<String, String>  producer,
            String                          inputTopic,
            String                          outputTopic) {

        while (true) {
            ConsumerRecords<String, String> records =
                consumer.poll(Duration.ofMillis(100));
            if (records.isEmpty()) continue;

            producer.beginTransaction();

            try {
                for (ConsumerRecord<String, String> record : records) {
                    // Process
                    String result = processRecord(record.value());

                    // Write output
                    producer.send(new ProducerRecord<>(
                        outputTopic, record.key(), result));
                }

                // Commit consumer offsets AS PART OF the transaction
                // This is the key to exactly-once: offsets and output
                // are committed atomically
                Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
                records.partitions().forEach(tp -> {
                    long lastOffset = records.records(tp)
                        .get(records.records(tp).size() - 1).offset();
                    offsets.put(tp, new OffsetAndMetadata(lastOffset + 1));
                });

                producer.sendOffsetsToTransaction(offsets,
                    new ConsumerGroupMetadata(consumer.groupMetadata()));

                producer.commitTransaction(); // Atomic commit!

            } catch (Exception e) {
                log.error("Transaction failed, aborting", e);
                producer.abortTransaction(); // Rolls back ALL writes
                // Consumer will reprocess from last committed offset
            }
        }
    }

    // ── Consumer for read_committed ───────────────────────────────────────────
    static KafkaConsumer<String, String> buildExactlyOnceConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG,          "eos-consumer");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // CRITICAL: only read committed messages (skip in-flight transactions)
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG,
            "read_committed");

        return new KafkaConsumer<>(props);
    }

    static String processRecord(String value) { return value.toUpperCase(); }
    static org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(ExactlyOnceProcessor.class);
}
```

---

## 11. Consumer Offset Management

```java
public class OffsetManagement {

    // ── Offset storage options ─────────────────────────────────────────────────
    // 1. Kafka (default): stored in __consumer_offsets topic
    //    group.id + topic + partition → offset
    // 2. External DB: store in your own DB (use assign() not subscribe())
    //    Enables exactly-once with idempotent DB writes

    // ── External offset store (DB-based exactly-once) ─────────────────────────
    @Service
    @Transactional
    public static class ExternalOffsetConsumer {

        private final KafkaConsumer<String, OrderEvent> consumer;
        private final OffsetRepository offsetRepo;
        private final OrderRepository  orderRepo;

        public ExternalOffsetConsumer(OffsetRepository offsetRepo,
                                      OrderRepository orderRepo) {
            this.offsetRepo = offsetRepo;
            this.orderRepo  = orderRepo;
            this.consumer   = buildConsumer();
            assignPartitions(); // Manually assign, not subscribe
        }

        private void assignPartitions() {
            // Load previously committed offsets from DB
            List<TopicPartition> partitions = List.of(
                new TopicPartition("orders", 0),
                new TopicPartition("orders", 1),
                new TopicPartition("orders", 2)
            );
            consumer.assign(partitions);

            // Seek to DB-stored offset for each partition
            partitions.forEach(tp -> {
                Long savedOffset = offsetRepo.getOffset(
                    "my-service", tp.topic(), tp.partition());
                if (savedOffset != null) {
                    consumer.seek(tp, savedOffset);
                } else {
                    consumer.seekToBeginning(List.of(tp));
                }
            });
        }

        @Transactional // DB transaction wraps processing + offset update
        public void processNext() {
            ConsumerRecords<String, OrderEvent> records =
                consumer.poll(Duration.ofMillis(100));

            for (ConsumerRecord<String, OrderEvent> record : records) {
                // Idempotent write: ON CONFLICT DO NOTHING
                orderRepo.upsert(record.value()); // DB write

                // Save offset in SAME transaction
                offsetRepo.saveOffset(
                    "my-service",
                    record.topic(),
                    record.partition(),
                    record.offset() + 1
                );
                // If DB transaction rolls back: offset NOT saved
                // Next poll will reprocess from previous offset
                // → Exactly-once if orderRepo.upsert is idempotent
            }
        }

        private KafkaConsumer<String, OrderEvent> buildConsumer() {
            Properties props = new Properties();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            // No group.id needed — manual partition assignment
            return new KafkaConsumer<>(props);
        }

        interface OffsetRepository {
            Long getOffset(String group, String topic, int partition);
            void saveOffset(String group, String topic, int partition, long offset);
        }
        interface OrderRepository { void upsert(OrderEvent event); }
    }

    // ── Offset seek for replay / debugging ────────────────────────────────────
    static void replayFromTimestamp(KafkaConsumer<String, String> consumer,
                                    String topic, long timestampMs) {
        // Get partitions for topic
        List<TopicPartition> partitions = consumer.partitionsFor(topic)
            .stream()
            .map(p -> new TopicPartition(p.topic(), p.partition()))
            .toList();
        consumer.assign(partitions);

        // Find offsets at or after the timestamp
        Map<TopicPartition, Long> timestamps = partitions.stream()
            .collect(java.util.stream.Collectors.toMap(
                tp -> tp, tp -> timestampMs));

        Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes =
            consumer.offsetsForTimes(timestamps);

        // Seek each partition to the found offset
        offsetsForTimes.forEach((tp, offsetAndTimestamp) -> {
            if (offsetAndTimestamp != null) {
                consumer.seek(tp, offsetAndTimestamp.offset());
                System.out.printf("Partition %d: seeking to offset %d (timestamp %d)%n",
                    tp.partition(),
                    offsetAndTimestamp.offset(),
                    offsetAndTimestamp.timestamp());
            } else {
                consumer.seekToEnd(List.of(tp)); // No messages after that time
            }
        });
    }
}
```

---

## 12. Error Handling & Dead Letter Topics

### Spring Kafka Error Handling

```java
import org.springframework.kafka.listener.*;
import org.springframework.kafka.retrytopic.*;

@Configuration
public class KafkaErrorHandlingConfig {

    // ── Non-blocking retry with Dead Letter Topic ─────────────────────────────
    // Recommended: retries happen on separate topics, don't block main topic
    @Bean
    public RetryTopicConfiguration retryTopicConfig(KafkaTemplate<String, OrderEvent> tpl) {
        return RetryTopicConfigurationBuilder
            .newInstance()
            .maxAttempts(4)                     // 1 attempt + 3 retries
            .fixedBackOff(1000L)                // 1s between retries
            // OR: exponential backoff
            // .exponentialBackoff(1000, 2, 30_000) // 1s, 2s, 4s, max 30s
            .retryOn(RetryableOrderException.class)
            .notRetryOn(InvalidOrderException.class)  // Send directly to DLT
            .dltSuffix(".DLT")                         // Topic: orders.DLT
            .dltProcessingFailureStrategy(
                DltStrategy.FAIL_ON_ERROR)             // DLT processing must succeed
            .autoCreateTopics(true, 3, (short)3)
            .create(tpl);
    }

    // ── DLT consumer: handle unprocessable messages ────────────────────────────
    @DltHandler
    @KafkaListener(topics = "orders.DLT")
    public void handleDlt(
            ConsumerRecord<String, OrderEvent> record,
            @Header(KafkaHeaders.EXCEPTION_MESSAGE) String exceptionMsg,
            @Header(KafkaHeaders.EXCEPTION_STACKTRACE) String stackTrace,
            @Header(KafkaHeaders.ORIGINAL_TOPIC) String originalTopic,
            @Header(KafkaHeaders.ORIGINAL_OFFSET) long originalOffset) {

        log.error("DLT message: orderId={} originalTopic={} offset={} error={}",
            record.value().orderId(), originalTopic, originalOffset, exceptionMsg);

        // Options:
        // 1. Alert operations team
        alertService.sendDltAlert(record, exceptionMsg);
        // 2. Save to DB for manual review
        deadLetterRepo.save(new DeadLetterRecord(record, exceptionMsg, stackTrace));
        // 3. Try alternative processing path
        // fallbackService.process(record.value());
    }

    // ── Global error handler ──────────────────────────────────────────────────
    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<?, ?> template) {
        // DLT publisher — sends to <topic>.DLT
        DeadLetterPublishingRecoverer recoverer =
            new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(
                    record.topic() + ".DLT",
                    record.partition())); // Keep same partition

        // Retry policy: exponential backoff
        ExponentialBackOffWithMaxRetries backoff =
            new ExponentialBackOffWithMaxRetries(3);
        backoff.setInitialInterval(1_000L);
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(30_000L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backoff);

        // Exceptions to NOT retry (send straight to DLT)
        handler.addNotRetryableExceptions(
            InvalidOrderException.class,
            java.io.IOException.class,
            org.springframework.kafka.support.serializer.DeserializationException.class
        );

        // Retryable exceptions
        handler.addRetryableExceptions(
            RetryableOrderException.class,
            org.springframework.dao.TransientDataAccessException.class
        );

        // Log all errors
        handler.setRetryListeners((record, ex, deliveryAttempt) ->
            log.warn("Retry attempt {} for record at offset {}",
                deliveryAttempt, record.offset(), ex));

        return handler;
    }

    // ── Deserialization error handler ─────────────────────────────────────────
    @Bean
    public DefaultKafkaConsumerFactory<String, OrderEvent> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        // ... other props ...

        // Wrap deserializer to handle poison pills (malformed messages)
        ErrorHandlingDeserializer<OrderEvent> valueDeserializer =
            new ErrorHandlingDeserializer<>(new JsonDeserializer<>(OrderEvent.class));

        return new DefaultKafkaConsumerFactory<>(props,
            new StringDeserializer(), valueDeserializer);
    }

    @Autowired AlertService alertService;
    @Autowired DeadLetterRepository deadLetterRepo;

    interface AlertService    { void sendDltAlert(ConsumerRecord<?,?> r, String msg); }
    interface DeadLetterRepository { void save(Object r); }
    record DeadLetterRecord(Object r, String msg, String trace) {}
    static org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(KafkaErrorHandlingConfig.class);
    static class RetryableOrderException extends RuntimeException {}
    static class InvalidOrderException   extends RuntimeException {}
}
```

---

## 13. Kafka Security

### SSL + SASL Configuration

```java
// ── SSL Producer config ───────────────────────────────────────────────────────
public class SecureKafkaConfig {

    static Properties sslProducerProps() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
            "broker1:9093,broker2:9093");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // SSL
        props.put("security.protocol",               "SSL");
        props.put("ssl.truststore.location",         "/etc/kafka/ssl/kafka.truststore.jks");
        props.put("ssl.truststore.password",         "truststore-password");
        props.put("ssl.keystore.location",           "/etc/kafka/ssl/kafka.keystore.jks");
        props.put("ssl.keystore.password",           "keystore-password");
        props.put("ssl.key.password",                "key-password");
        props.put("ssl.endpoint.identification.algorithm", "https"); // Verify hostname

        return props;
    }

    // ── SASL/SCRAM (username/password) ────────────────────────────────────────
    static Properties saslScramProps() {
        Properties props = new Properties();
        props.put("bootstrap.servers", "broker1:9094,broker2:9094");
        props.put("security.protocol", "SASL_SSL");
        props.put("sasl.mechanism",    "SCRAM-SHA-512");
        props.put("sasl.jaas.config",
            "org.apache.kafka.common.security.scram.ScramLoginModule required " +
            "username=\"my-app\" " +
            "password=\"secret\";");
        return props;
    }

    // ── Spring Boot application.yml (SSL + SASL) ──────────────────────────────
    /*
    spring:
      kafka:
        bootstrap-servers: broker1:9093
        security:
          protocol: SASL_SSL
        ssl:
          trust-store-location: classpath:kafka.truststore.jks
          trust-store-password: ${TRUSTSTORE_PASSWORD}
          key-store-location: classpath:kafka.keystore.jks
          key-store-password: ${KEYSTORE_PASSWORD}
        properties:
          sasl.mechanism: SCRAM-SHA-512
          sasl.jaas.config: >
            org.apache.kafka.common.security.scram.ScramLoginModule required
            username="${KAFKA_USERNAME}"
            password="${KAFKA_PASSWORD}";
    */

    // ── ACL setup (via kafka-acls.sh) ─────────────────────────────────────────
    /*
    # Allow producer to write to "orders" topic:
    kafka-acls.sh --bootstrap-server localhost:9092 \
      --add --allow-principal User:checkout-service \
      --operation Write --topic orders

    # Allow consumer group to read from "orders":
    kafka-acls.sh --bootstrap-server localhost:9092 \
      --add --allow-principal User:billing-service \
      --operation Read --topic orders \
      --operation Read --group billing-service-group
    */
}
```

---

## 14. Performance Tuning

### Producer Throughput vs Latency Tradeoffs

```java
public class KafkaPerformanceTuning {

    // ── MAX THROUGHPUT config ─────────────────────────────────────────────────
    static Properties maxThroughputProducer() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        p.put(ProducerConfig.BATCH_SIZE_CONFIG,        128 * 1024);   // 128KB batches
        p.put(ProducerConfig.LINGER_MS_CONFIG,         50);           // Wait 50ms to fill
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");         // Fast compression
        p.put(ProducerConfig.BUFFER_MEMORY_CONFIG,    128*1024*1024L);// 128MB buffer
        p.put(ProducerConfig.ACKS_CONFIG,             "1");           // Leader only
        p.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 10);
        return p;
    }

    // ── MAX RELIABILITY config ────────────────────────────────────────────────
    static Properties maxReliabilityProducer() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        p.put(ProducerConfig.ACKS_CONFIG,             "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        p.put(ProducerConfig.RETRIES_CONFIG,           Integer.MAX_VALUE);
        p.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        p.put(ProducerConfig.BATCH_SIZE_CONFIG,       64 * 1024);
        p.put(ProducerConfig.LINGER_MS_CONFIG,        10);
        return p;
    }

    // ── Consumer throughput tuning ────────────────────────────────────────────
    static Properties highThroughputConsumer() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        p.put(ConsumerConfig.GROUP_ID_CONFIG,           "fast-consumer");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,   1000);     // Large batches
        p.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG,    1024*1024);// Wait for 1MB
        p.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG,  500);      // Or 500ms
        p.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 10*1024*1024); // 10MB
        p.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 600_000); // 10min for large batches
        return p;
    }

    // ── Partition count sizing ────────────────────────────────────────────────
    /*
    Target throughput:  100 MB/s
    Per-partition max:  ~10 MB/s (single-threaded writes/reads)
    Partitions needed:  100/10 = 10 partitions minimum

    Consumer parallelism: partitions = max consumers in group
    Rule of thumb: 2–3× expected peak consumers

    Too many partitions: more overhead (file handles, memory, rebalance time)
    Too few: bottleneck on throughput

    Recommendation: start with 12–24 partitions for most topics
    */

    // ── Broker performance settings ───────────────────────────────────────────
    /*
    # server.properties
    num.network.threads=8
    num.io.threads=16
    socket.send.buffer.bytes=102400
    socket.receive.buffer.bytes=102400
    num.replica.fetchers=4           # Faster replication
    log.flush.interval.messages=10000  # Batch fsync
    log.flush.interval.ms=1000
    */
}
```

---

## 15. Kafka Connect

```java
// Kafka Connect moves data between Kafka and external systems
// without writing custom consumer/producer code

// ── Source connector: DB → Kafka (Debezium CDC) ───────────────────────────────
/*
POST /connectors
{
  "name": "postgres-source",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "tasks.max": "1",
    "database.hostname": "postgres",
    "database.port": "5432",
    "database.user": "debezium",
    "database.password": "secret",
    "database.dbname": "mydb",
    "table.include.list": "public.orders,public.users",
    "topic.prefix": "mydb",
    "plugin.name": "pgoutput",

    // CDC produces events to: mydb.public.orders, mydb.public.users
    // Events include: before + after row images for INSERT/UPDATE/DELETE
  }
}
*/

// ── Sink connector: Kafka → Elasticsearch ────────────────────────────────────
/*
POST /connectors
{
  "name": "elastic-sink",
  "config": {
    "connector.class": "io.confluent.connect.elasticsearch.ElasticsearchSinkConnector",
    "tasks.max": "3",
    "topics": "orders",
    "connection.url": "http://elasticsearch:9200",
    "type.name": "_doc",
    "key.ignore": "false",
    "schema.ignore": "true",
    "behavior.on.malformed.documents": "warn",

    // Transform: add timestamp before writing to ES
    "transforms": "addTimestamp",
    "transforms.addTimestamp.type": "org.apache.kafka.connect.transforms.InsertField$Value",
    "transforms.addTimestamp.timestamp.field": "indexedAt"
  }
}
*/

// ── Manage connectors via Java ─────────────────────────────────────────────────
public class ConnectManager {

    static void getConnectorStatus(String connectorName) throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(
                "http://connect:8083/connectors/" + connectorName + "/status"))
            .GET().build();

        java.net.http.HttpResponse<String> resp =
            client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        System.out.println("Connector status: " + resp.body());
    }

    static void restartConnector(String connectorName) throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(
                "http://connect:8083/connectors/" + connectorName + "/restart"))
            .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
            .build();
        client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        System.out.println("Restarted: " + connectorName);
    }
}
```

---

## 16. Monitoring & Observability

```java
import io.micrometer.core.instrument.*;

@Component
public class KafkaMetricsCollector {

    private final MeterRegistry registry;

    // ── Spring Kafka provides these metrics automatically: ────────────────────
    // kafka.producer.record-send-rate          (records/sec sent)
    // kafka.producer.record-error-rate         (errors/sec)
    // kafka.producer.batch-size-avg            (avg bytes per batch)
    // kafka.consumer.records-consumed-rate     (records/sec consumed)
    // kafka.consumer.fetch-latency-avg         (ms waiting for data)
    // kafka.consumer.records-lag               (lag per partition) ← CRITICAL
    // kafka.consumer.records-lag-max           (max lag across partitions)

    public KafkaMetricsCollector(MeterRegistry registry) {
        this.registry = registry;
    }

    // ── Custom consumer lag alert ─────────────────────────────────────────────
    @Scheduled(fixedDelay = 10_000)
    public void checkConsumerLag() {
        // Spring Kafka exposes consumer lag via Micrometer automatically
        // Set alert threshold in Prometheus/Grafana:
        // kafka_consumer_records_lag > 10000 → alert

        Gauge.builder("kafka.business.processing.lag",
                this, MetricsCollector -> getCurrentLag())
            .tag("consumer_group", "billing-service")
            .description("Business-level processing lag")
            .register(registry);
    }

    double getCurrentLag() {
        // Implement actual lag calculation via AdminClient
        return 0;
    }

    interface MetricsCollector {}
}
```

```yaml
# Prometheus alerting rules for Kafka
groups:
  - name: kafka
    rules:
      - alert: KafkaHighConsumerLag
        expr: kafka_consumer_records_lag_max > 10000
        for: 5m
        annotations:
          summary: "Consumer lag >10K on {{ $labels.client_id }}"

      - alert: KafkaProducerErrors
        expr: rate(kafka_producer_record_error_total[5m]) > 1
        for: 2m
        annotations:
          summary: "Producer errors on {{ $labels.client_id }}"

      - alert: KafkaUnderReplicatedPartitions
        expr: kafka_server_replicamanager_underreplicatedpartitions > 0
        for: 2m
        annotations:
          summary: "Under-replicated partitions on {{ $labels.instance }}"

      - alert: KafkaOfflinePartitions
        expr: kafka_controller_kafkacontroller_offlinepartitionscount > 0
        for: 1m
        annotations:
          summary: "Offline partitions detected!"
```

---

## 17. Testing Kafka Applications

```java
import org.apache.kafka.clients.consumer.*;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.boot.test.context.SpringBootTest;

// ── Integration test with embedded Kafka ──────────────────────────────────────
@SpringBootTest
@EmbeddedKafka(
    partitions = 3,
    topics = {"orders", "orders.DLT"},
    brokerProperties = {
        "auto.create.topics.enable=false",
        "log.retention.ms=5000"
    }
)
class OrderEventIntegrationTest {

    @Autowired KafkaTemplate<String, OrderEvent>  producer;
    @Autowired OrderEventConsumer                  consumer;
    @Autowired EmbeddedKafkaBroker                broker;

    @Test
    void publishAndConsume_ShouldProcessOrder() throws Exception {
        // Publish
        OrderEvent event = new OrderEvent("O-001", "U-001", "CREATED", 99.99);
        producer.send("orders", event.orderId(), event).get();

        // Consume
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
            "test-group", "true", broker);
        KafkaConsumer<String, OrderEvent> testConsumer =
            new KafkaConsumer<>(consumerProps);
        testConsumer.subscribe(List.of("orders"));

        ConsumerRecords<String, OrderEvent> records =
            KafkaTestUtils.getRecords(testConsumer, Duration.ofSeconds(5));

        assertThat(records.count()).isEqualTo(1);
        OrderEvent received = records.iterator().next().value();
        assertThat(received.orderId()).isEqualTo("O-001");
        assertThat(received.status()).isEqualTo("CREATED");
        testConsumer.close();
    }

    @Test
    void invalidOrder_ShouldGoToDLT() throws Exception {
        // Publish invalid order that triggers exception
        OrderEvent invalid = new OrderEvent("INVALID", null, null, -1.0);
        producer.send("orders", invalid.orderId(), invalid).get();

        // Verify it ends up in DLT after retries
        Map<String, Object> props = KafkaTestUtils.consumerProps(
            "dlt-test", "true", broker);
        KafkaConsumer<String, OrderEvent> dltConsumer = new KafkaConsumer<>(props);
        dltConsumer.subscribe(List.of("orders.DLT"));

        ConsumerRecords<String, OrderEvent> records =
            KafkaTestUtils.getRecords(dltConsumer, Duration.ofSeconds(15));
        assertThat(records.count()).isEqualTo(1);
        dltConsumer.close();
    }
}

// ── Unit test: Kafka Streams topology ─────────────────────────────────────────
class OrderStreamTopologyTest {

    private TopologyTestDriver testDriver;
    private TestInputTopic<String, OrderEvent> inputTopic;
    private TestOutputTopic<String, Long>      outputTopic;

    @BeforeEach
    void setUp() {
        Topology topology = OrderStreamProcessor.buildTopology();

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG,    "test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");

        testDriver  = new TopologyTestDriver(topology, props);
        inputTopic  = testDriver.createInputTopic("orders",
            new StringSerializer(), new JsonSerializer<>());
        outputTopic = testDriver.createOutputTopic("order-counts-per-minute",
            new StringDeserializer(), new LongDeserializer());
    }

    @AfterEach
    void tearDown() { testDriver.close(); }

    @Test
    void countOrders_ShouldEmitCorrectCount() {
        // Send 3 orders for same user in same minute
        Instant now = Instant.now();
        OrderEvent order = new OrderEvent("O-1","U-1","CREATED",50.0);

        inputTopic.pipeInput("U-1", order, now);
        inputTopic.pipeInput("U-1", order, now.plusSeconds(10));
        inputTopic.pipeInput("U-1", order, now.plusSeconds(20));

        // Advance time past the window
        testDriver.advanceWallClockTime(Duration.ofMinutes(2));

        List<KeyValue<String, Long>> results = outputTopic.readKeyValuesToList();
        assertThat(results).isNotEmpty();

        // Last update for the window should be 3
        long lastCount = results.get(results.size()-1).value;
        assertThat(lastCount).isEqualTo(3L);
    }

    record OrderEvent(String orderId, String userId, String status, double total) {}
}

// ── Mock Kafka with MockConsumer / MockProducer ───────────────────────────────
class OrderServiceUnitTest {

    @Test
    void service_ShouldPublishEvent() {
        MockProducer<String, OrderEvent> mockProducer =
            new MockProducer<>(true,            // autoComplete = true (auto-ack)
                new StringSerializer(),
                new JsonSerializer<>());

        OrderEventProducer producer = new OrderEventProducer(
            new KafkaTemplate<>(new MockProducerFactory<>(mockProducer)));

        OrderEvent event = new OrderEvent("O-1","U-1","CREATED",99.0);
        producer.publishOrderCreated(event);

        assertThat(mockProducer.history()).hasSize(1);
        ProducerRecord<String, OrderEvent> sent = mockProducer.history().get(0);
        assertThat(sent.topic()).isEqualTo("orders");
        assertThat(sent.key()).isEqualTo("O-1");
        assertThat(sent.value().orderId()).isEqualTo("O-1");
    }

    record OrderEvent(String orderId, String userId, String status, double total) {}
    interface MockProducerFactory<K,V> extends ProducerFactory<K,V> {}
}
```

---

## 18. Real-World Patterns & Architectures

### Outbox Pattern — Guaranteed Event Publishing

```java
// Problem: Save DB record AND publish Kafka event atomically
// ❌ Two-phase: DB save succeeds, Kafka publish fails → inconsistency

// ✅ Outbox pattern: write event to DB table (same transaction),
//   relay reads outbox and publishes to Kafka

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id @GeneratedValue
    Long id;
    String aggregateType;  // "ORDER"
    String aggregateId;    // order ID
    String eventType;      // "ORDER_CREATED"
    String payload;        // JSON
    boolean published;
    Instant createdAt;
}

@Service
@Transactional
public class OrderService {

    @Autowired OrderRepository   orderRepo;
    @Autowired OutboxRepository  outboxRepo;

    public Order createOrder(CreateOrderRequest req) {
        // 1. Save business entity
        Order order = orderRepo.save(new Order(req));

        // 2. Write to outbox IN SAME TRANSACTION
        outboxRepo.save(new OutboxEvent(
            "ORDER", order.getId(), "ORDER_CREATED",
            toJson(new OrderEvent(order)), false, Instant.now()
        ));

        // If transaction commits: both order AND outbox saved
        // If transaction rolls back: neither saved
        return order;
    }
}

@Component
public class OutboxRelay {

    @Autowired OutboxRepository  outboxRepo;
    @Autowired KafkaTemplate<String, String> kafka;

    @Scheduled(fixedDelay = 1000) // Poll every second
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxRepo
            .findByPublishedFalseOrderByCreatedAtAsc(Pageable.ofSize(100));

        pending.forEach(event -> {
            kafka.send("orders", event.aggregateId(), event.payload());
            event.setPublished(true); // Mark as published
        });
        // Debezium CDC is a better alternative: streams outbox table changes to Kafka
    }
}
```

### Event Sourcing Pattern

```java
// Store all state changes as immutable events
// Current state = replay of all events

@Service
public class OrderEventSourcingService {

    @Autowired KafkaTemplate<String, Object> kafka;

    // Publish event (source of truth)
    public void placeOrder(PlaceOrderCommand cmd) {
        OrderPlacedEvent event = new OrderPlacedEvent(
            cmd.orderId(), cmd.userId(), cmd.items(), cmd.total(),
            Instant.now()
        );
        kafka.send("order-events", cmd.orderId(), event);
    }

    public void shipOrder(String orderId) {
        OrderShippedEvent event = new OrderShippedEvent(
            orderId, Instant.now(), trackingNumber());
        kafka.send("order-events", orderId, event);
    }

    // Rebuild state by replaying events
    public Order rebuildOrder(String orderId) {
        // Seek to beginning of partition containing this orderId
        // Replay all events with this key → reconstruct current state
        Order order = new Order();

        // Pseudo-code:
        // events = kafka.getAll("order-events", key=orderId)
        // events.forEach(event -> order.apply(event))
        return order;
    }

    String trackingNumber() { return java.util.UUID.randomUUID().toString(); }
    record PlaceOrderCommand(String orderId, String userId,
                             List<Object> items, double total) {}
    record OrderPlacedEvent (String orderId, String userId,
                             List<Object> items, double total, Instant at) {}
    record OrderShippedEvent(String orderId, Instant shippedAt, String tracking) {}
    record Order() { void apply(Object event) {} }
}
```

### CQRS with Kafka

```
Commands (writes):
  POST /orders ──► OrderCommandService ──► [orders-commands] topic
                                                │
                                           OrderAggregate
                                                │
                                           [orders-events] topic

Queries (reads):
  [orders-events] ──► OrderProjectionService ──► Read DB (Postgres/Redis)
  GET /orders ──► OrderQueryService ──► Read DB (fast, denormalized view)

Benefits:
  • Write and read models scaled independently
  • Multiple read models from same event stream
  • Time-travel: rebuild any projection by replaying events
  • Event log is the source of truth
```

---

## 19. Interview Questions & Answers

| # | Question | Answer |
|---|----------|--------|
| 1 | What is Kafka and how is it different from RabbitMQ? | Kafka is a distributed event streaming platform using an immutable, append-only log model. Key differences: Kafka retains messages after consumption (configurable), supports replay, has much higher throughput (~1M msg/s vs ~10K), consumers track offsets themselves, and scales natively via partitions. RabbitMQ is better for task queues and request-reply patterns. |
| 2 | What is a consumer group and how does partition assignment work? | A consumer group is a set of consumers that share the work of reading a topic. Each partition is assigned to exactly one consumer in a group. This enables parallel processing — max parallelism = number of partitions. If consumers > partitions, some consumers sit idle. Adding/removing consumers triggers a rebalance. |
| 3 | What is the difference between at-least-once and exactly-once? | At-least-once: messages are never lost but may be delivered multiple times (acks=all + retries). Exactly-once: messages never lost AND never duplicated. Requires idempotent producer (deduplicates retries via sequence numbers) + transactional API (atomically commits output + consumer offsets). |
| 4 | What does `acks=all` mean and when would you use `acks=1`? | `acks=all`: leader waits for all ISR replicas to confirm write — strongest durability guarantee. `acks=1`: only leader confirms — faster but risks data loss if leader fails before replication. `acks=0`: fire-and-forget. Use `acks=all` + `min.insync.replicas=2` for financial/critical data. Use `acks=1` for high-throughput, loss-tolerant use cases like metrics. |
| 5 | What is `auto.offset.reset` and what are the options? | Defines what to do when no committed offset exists for a consumer group. `earliest`: read from the beginning of the partition (new consumers see all history). `latest`: start from the current end (new consumers miss old messages). `none`: throw an exception. Use `earliest` for event sourcing/reprocessing, `latest` for real-time feeds. |
| 6 | How does Kafka ensure message ordering? | Ordering is guaranteed WITHIN a partition, not across partitions. Messages with the same key always go to the same partition (consistent hash of key). To guarantee global ordering for a resource (e.g., all events for order-123), always use the same key. Use 1 partition for global ordering (sacrifices parallelism). |
| 7 | What is `min.insync.replicas` and why does it matter? | Minimum number of ISR replicas that must acknowledge a write when `acks=all`. If ISR drops below this threshold, the broker rejects writes with `NotEnoughReplicasException`. Typical setting: replication-factor=3, min.insync.replicas=2. This tolerates 1 broker failure while guaranteeing at least 2 replicas have the data. |
| 8 | What causes a consumer rebalance and how can you minimize impact? | Triggers: consumer joins/leaves group, session timeout, max.poll.interval.ms exceeded. Impact: all consumption stops during rebalance. Minimize by: using static membership (`group.instance.id`), increasing `session.timeout.ms`, increasing `max.poll.interval.ms`, using cooperative/incremental rebalance protocol (`partition.assignment.strategy=CooperativeStickyAssignor`). |
| 9 | What is the difference between KStream and KTable in Kafka Streams? | `KStream`: unbounded stream of events — each record is independent (e.g., clicks, orders). `KTable`: changelog stream — each record is an update keyed value, like a database table (only latest value per key is kept). KTable is backed by a local state store (RocksDB). Use KStream for event-by-event processing, KTable for current-state lookups. |
| 10 | What is log compaction and when should you use it? | Compaction retains only the LATEST record for each key, deleting older ones. Unlike time-based deletion, data is kept indefinitely (as long as it's the latest value for its key). Null-value tombstones delete a key. Use for: user profiles, product catalog, any current-state store. Enables Kafka as a distributed KV store. |
| 11 | How does the Outbox pattern solve the dual-write problem? | Instead of writing to DB + Kafka separately (which can fail independently), write the event to an `outbox` table IN THE SAME DB TRANSACTION as your business entity. A separate relay process reads the outbox and publishes to Kafka. Guarantees consistency: either both the entity and the event are persisted, or neither. |
| 12 | What is the `max.poll.interval.ms` setting? | Maximum time between calls to `poll()`. If exceeded, the consumer is considered dead and removed from the group, triggering a rebalance. This happens if processing takes too long. Fix: reduce `max.poll.records`, move heavy processing to async thread, or increase `max.poll.interval.ms`. |
| 13 | What is Schema Registry and why is it needed? | Centralized repository for Avro/Protobuf/JSON schemas. Provides: schema versioning, compatibility enforcement (BACKWARD/FORWARD/FULL), compact binary format (schema ID + bytes instead of full schema in every message), and prevents producers/consumers from silently breaking each other with schema changes. |
| 14 | How does Kafka Streams handle state and fault tolerance? | State is stored in local RocksDB stores. All state changes are logged to internal Kafka changelog topics. On failure/restart, Kafka Streams rebuilds state by replaying the changelog topic. This makes state fault-tolerant without external DB. Interactive queries allow reading local state directly via REST. |
| 15 | What is the difference between `commitSync` and `commitAsync`? | `commitSync`: blocks until broker confirms the commit, retries on failure — safest but slower. `commitAsync`: non-blocking, provides callback — faster but may lose the offset on failure (callback not retried to avoid out-of-order commits). Production pattern: use `commitAsync` for normal processing, `commitSync` in the finally block on shutdown. |
| 16 | How many partitions should a topic have? | Partitions = max desired consumer parallelism. Rule of thumb: target partition throughput ~10MB/s. If you need 100MB/s, use 10 partitions. Over-partitioning causes overhead: more file handles, longer rebalance, higher memory. Start with 12-24 partitions for most topics; increase later (but can't decrease without recreating). |
| 17 | What happens if a consumer's `session.timeout.ms` expires? | The broker removes the consumer from the consumer group and triggers a rebalance. The partitions assigned to that consumer are redistributed to remaining consumers. This happens if the consumer fails to send heartbeats within the session timeout period. |
| 18 | Explain the Kafka transaction flow for read-process-write. | 1. `initTransactions()` on startup. 2. `beginTransaction()`. 3. Read from input topic (consumer). 4. Process records. 5. `send()` results to output topic. 6. `sendOffsetsToTransaction()` — atomic commit of consumer offsets. 7. `commitTransaction()` or `abortTransaction()` on error. Consumers must use `isolation.level=read_committed` to skip uncommitted messages. |
| 19 | What is KRaft mode in Kafka 3.x? | KRaft replaces ZooKeeper as Kafka's metadata and coordination system. Uses Raft consensus protocol internally. Benefits: simpler deployment (no ZK cluster needed), faster controller failover (~30s → <1s), supports 1M+ partitions (ZK struggled with large metadata). Kafka 4.0 fully removes ZooKeeper support. |
| 20 | How do you handle poison pills (unprocessable messages)? | Options: 1. Retry with backoff then send to Dead Letter Topic (DLT). 2. Skip and log (risk of data loss). 3. Use `ErrorHandlingDeserializer` to handle deserialization failures gracefully. 4. Spring Kafka `@RetryableTopic` automates retry + DLT routing. Always send to DLT with original topic/offset/exception headers for observability. |

---

## 20. Complete Reference Summary

### Quick Config Cheat Sheet

```
PRODUCER
  bootstrap.servers         Comma-separated broker list
  acks                      all (safe) | 1 (fast) | 0 (fastest)
  enable.idempotence        true (deduplicates retries)
  retries                   Integer.MAX_VALUE (with idempotence)
  batch.size                16384 (16KB default) → 65536 for throughput
  linger.ms                 0 (low latency) → 10-50 for throughput
  compression.type          none | gzip | snappy | lz4 | zstd
  buffer.memory             33554432 (32MB default)
  max.in.flight.requests    5 (with idempotence) | 1 (strict ordering)
  delivery.timeout.ms       120000 (2 min total retry window)

CONSUMER
  group.id                  Consumer group name
  auto.offset.reset         earliest | latest | none
  enable.auto.commit        false (use manual for reliability)
  max.poll.records          500 (default) → 1000 for throughput
  max.poll.interval.ms      300000 (5 min default)
  session.timeout.ms        45000 (45s default)
  heartbeat.interval.ms     3000 (3s default, must be < session.timeout/3)
  fetch.min.bytes           1 → 1048576 for throughput
  isolation.level           read_committed (for EOS consumers)
  group.instance.id         Set for static membership (fewer rebalances)

TOPIC
  retention.ms              604800000 (7 days)
  cleanup.policy            delete | compact
  min.insync.replicas       2 (with replication-factor=3)
  compression.type          producer (inherit) | lz4 | gzip | zstd
  max.message.bytes         1048576 (1MB default)

KAFKA STREAMS
  application.id            Unique per app (used for group.id + changelog topics)
  num.stream.threads        4 (default 1 — increase for parallelism)
  processing.guarantee      at_least_once | exactly_once_v2
  commit.interval.ms        100 (ms, default 30000)
  statestore.cache.max.bytes 10485760 (10MB default)
```

### Architecture Decision Guide

```
Use Case                        → Solution
──────────────────────────────────────────────────────────────────────
Simple async messaging          → @KafkaListener + KafkaTemplate
Event sourcing                  → Append-only topic + compacted state topic
Real-time stream processing     → Kafka Streams (stateful: aggregations, joins)
CDC (DB → Kafka)                → Kafka Connect + Debezium
Kafka → DB/ES/S3                → Kafka Connect Sink
Guaranteed event publishing     → Outbox Pattern
Exactly-once processing         → Idempotent producer + transactions
Low-latency analytics           → KTable + interactive queries
Fan-out (1 event → N services)  → Multiple consumer groups on same topic
Global ordering                 → 1 partition (sacrifices parallelism)
Key-based ordering              → Partition by key (same key = same partition)
Schema evolution                → Avro + Schema Registry (BACKWARD compatibility)
```

### Delivery Guarantees Summary

```
Configuration                        Guarantee           Tradeoff
──────────────────────────────────────────────────────────────────
acks=0                               At-most-once        Fastest, data loss possible
acks=1                               At-least-once*      Fast, loss if leader fails
acks=all + no idempotence            At-least-once       Safe, may duplicate on retry
acks=all + enable.idempotence=true   Exactly-once*       Safe, no duplicates per session
acks=all + transactions              Exactly-once         Atomic read-process-write
```

### Troubleshooting Cheat Sheet

```
SYMPTOM                     → CAUSE                    → FIX
────────────────────────────────────────────────────────────────────────
Consumer lag growing         Processing too slow         Scale consumers / optimize logic
Consumer lag growing         Rebalancing constantly      Increase max.poll.interval.ms
                                                         Reduce max.poll.records
Consumer lag growing         Not enough partitions       Increase partition count

Messages lost               acks=0 or acks=1            Set acks=all
Messages lost               Consumer crash before commit Use manual commit + idempotent writes

Duplicate messages          Retries without idempotence  enable.idempotence=true
Duplicate messages          At-least-once + crash        Make consumer idempotent (upsert)

Rebalance every 5min        max.poll.interval.ms timeout  Reduce batch size or increase timeout
Rebalance on restart        Dynamic membership           Set group.instance.id

Producer BUFFER_EXHAUSTED   Throughput > producer speed  Increase buffer.memory
                                                         Tune batch.size + linger.ms

NotEnoughReplicasException  ISR < min.insync.replicas    Broker down — check broker health
LeaderNotAvailableException Leader election in progress  Transient — add retry with backoff
```

---

*Made with ❤️ for Java developers — Kafka 3.x | Spring Kafka 3.x | Kafka Streams 3.x*
