# 🌍 Multi-Region Kafka Failover Playbook

This document outlines the standard operating procedures (SOP) for resolving a Kafka outage in a specific geographical region while maintaining global application availability.

---

## 🛠 1. Immediate Failover Strategies

The resolution path depends on the pre-configured architectural model of the application.

### Active-Passive (Disaster Recovery)
*   **The Setup:** A primary cluster handles traffic; a secondary cluster in a different region serves as a standby replica.
*   **Resolution:**
    *   **Promotion:** Promote the standby cluster to "Primary" status.
    *   **DNS Redirection:** Update global traffic managers (e.g., AWS Route 53, Cloudflare) to point the Kafka bootstrap endpoint to the healthy region.
    *   **Offset Translation:** Use MirrorMaker 2 (MM2) checkpoints or Cluster Linking to ensure consumers resume from the last committed offset in the new region.

### Active-Active (Multi-Regional)
*   **The Setup:** Multiple regions process local traffic simultaneously; data is replicated across regions.
*   **Resolution:**
    *   **Traffic Re-routing:** Immediately re-route 100% of global traffic to the surviving region.
    *   **Auto-Scaling:** Trigger auto-scaling for the healthy Kafka brokers and consumer microservices to handle the 2x surge in load.
    *   **Zero-Downtime Switch:** Since the secondary region is already "hot," the transition is near-instant with no bootstrap server changes needed if using a global Load Balancer.

---

## ⚡ 2. Operational Recovery Steps

When a regional Kafka cluster becomes unresponsive, follow these steps to restore data integrity:

*   **Step 1: Stop Replication Ingress**  
    Kill the replication process (MirrorMaker/Cluster Linking) flowing *from* the failing region to prevent "poison pills" or corrupted metadata from reaching the healthy cluster.
*   **Step 2: Verify Replication Lag**  
    Check the Recovery Point Objective (RPO). Since cross-region replication is asynchronous, identify the data gap (usually measured in milliseconds/seconds).
*   **Step 3: Client Re-pointing**  
    Update the `bootstrap.servers` configuration for Producers and Consumers. In 2026, this is ideally managed via a **Service Mesh** (Istio/Linkerd) or **Config Server** (Spring Cloud Config) without a full application restart.
*   **Step 4: Consumer Offset Sync**  
    Manually or automatically sync consumer offsets using `kafka-consumer-groups --reset-offsets` if automated offset translation was not active at the time of failure.

---

## 🛡️ 3. Application-Level Resilience

To prevent the application from crashing while Kafka is unreachable, the following patterns must be implemented:

*   **Circuit Breaking (Resilience4j):** Detect Kafka timeout errors and "open the circuit." This prevents the application from exhausting its thread pool waiting for Kafka acknowledgments.
*   **Local Outbox Pattern:** If Kafka is down, the application writes messages to a local persistent store (e.g., Postgres or a local disk-based queue). Once connectivity is restored, a background "Relay" process pushes the data to the new region.
*   **Producer Backpressure:** Use `max.block.ms` and `delivery.timeout.ms` settings to ensure the application fails fast rather than hanging indefinitely.
*   **Graceful Degradation:** Switch the UI to a "Read-Only" mode or notify users that "Updates are processing with delay" to maintain a positive user experience.

---

## 🔧 4. Technical Toolkit (2026 Standards)

The following tools are recommended for implementing these solutions:

*   **MirrorMaker 2 (MM2):** For cross-cluster data and offset replication.
*   **Confluent Cluster Linking:** For native, broker-to-broker replication without the overhead of Connect workers.
*   **WarpStream:** A modern Kafka alternative that uses multi-region S3 buckets as a storage backend, allowing for zero-data-loss regional failover.
*   **Service Mesh (Istio):** To handle regional traffic routing at the network layer.

---

## 📈 5. Post-Mortem and Prevention

Once the region is restored:
1.  **Reconciliation:** Compare the data in the recovered region with the failover region to identify any "dark data" (data produced but not replicated).
2.  **Back-filling:** Use a migration tool to move missed messages into the primary stream.
3.  **Failback:** Re-route traffic back to the original region once stability is verified for at least 24 hours.
