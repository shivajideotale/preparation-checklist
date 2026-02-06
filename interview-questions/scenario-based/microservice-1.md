# 🌐 Microservices Architecture & Troubleshooting Guide

This guide provides architectural patterns and operational solutions for managing distributed systems.

---

## 📋 Table of Contents
1. [Resilience & Stability](#1-resilience--stability)
2. [Communication & Connectivity](#2-communication--connectivity)
3. [Data & Configuration](#3-data--configuration)
4. [Deployment & Operations](#4-deployment--operations)

---

## 🛡 1. Resilience & Stability

### 1. Isolating a Single Slow Service
*   **The Symptom:** Only one service is lagging; others are healthy.
*   **Deep Dive:** Use **Distributed Tracing**. If Service A calls B, and B is slow, the trace will show a "long bar" at Service B.
*   **Action:** Check the [Jaeger Tracing](https://www.jaegertracing.io) dashboard to pinpoint if the bottleneck is CPU-bound logic, a slow DB query, or a blocked I/O thread.

### 2. Preventing Cascading Failures
*   **The Symptom:** Service B crashes, causing Service A (its caller) to exhaust its thread pool and crash too.
*   **Root Cause:** **Synchronous Blocking.** Service A waits forever for B, holding onto resources.
*   **Solution:** Implement the **Circuit Breaker Pattern** using [Resilience4j](https://resilience4j.readme.io). When failure rates cross a threshold, the breaker "trips," returning an immediate fallback (e.g., cached data) without hitting the broken service.

### 5. Design Failures in Traffic Spikes
*   **The Symptom:** A sudden burst of users crashes multiple services simultaneously.
*   **Root Cause:** **Lack of Load Shedding.** The system tries to process every request, leading to memory saturation.
*   **Solution:** Use **Rate Limiting** at the API Gateway level to reject excess traffic before it enters your internal network.

### 8. Safe Timeouts & Retries
*   **The Symptom:** A retry mechanism actually makes a failure worse (Self-Inflicted DDoS).
*   **Root Cause:** Retrying immediately after a timeout adds more load to a struggling service.
*   **Solution:** Implement **Exponential Backoff with Jitter**. This spreads out retry attempts randomly so they don't hit the server at the exact same millisecond.

### 11. Circuit Breaker Negative Impact
*   **The Symptom:** Enabling a circuit breaker made the system *slower*.
*   **Root Cause:** The **Fallback Logic** is too heavy. If your fallback involves another network call or expensive calculation, you aren't actually "breaking" the stress.
*   **Action:** Fallbacks should be "light," returning static defaults or data from a local [Redis](https://redis.io) cache.

---

## 📡 2. Communication & Connectivity

### 3. Fails Behind Gateway (Works Locally)
*   **The Symptom:** `curl localhost:8080` works, but `://api.company.com` fails.
*   **Root Cause:** **Header/Path Mismatch.** API Gateways often strip `Authorization` headers or change the context path.
*   **Action:** Check [Spring Cloud Gateway](https://spring.io) filters for `StripPrefix` or `RemoveRequestHeader` configurations.

### 6. Tracing a Single Request
*   **The Symptom:** "User 123 had an error," but you have 50 services and millions of logs.
*   **Solution:** **Correlation IDs.** Generate a unique `TraceID` at the Gateway and pass it in the HTTP Header (`X-Trace-Id`) to every downstream service.
*   **Action:** Use [Micrometer Tracing](https://micrometer.io) to automatically inject these IDs into your logs.

### 7. Risks of Synchronous REST at Scale
*   **The Symptom:** Adding more services makes the system progressively more fragile.
*   **Root Cause:** **Temporal Coupling.** Service A *must* have B and C online to finish a request. If any link is slow, the whole chain fails.
*   **Action:** Transition to **Asynchronous Messaging** using [Apache Kafka](https://kafka.apache.org) for non-critical flows like "Send Email" or "Update Analytics."

### 10. Managing API Versions
*   **The Symptom:** Updating a field in your JSON response breaks the Mobile App.
*   **Solution:** **Semantic Versioning.** Support `/v1/` and `/v2/` endpoints simultaneously.
*   **Action:** Use [Pact Contract Testing](https://docs.pact.io) to ensure your changes don't break "Consumer" expectations.

### 12. Securing Service-to-Service Traffic
*   **The Symptom:** Internal services trust each other blindly.
*   **Root Cause:** Lack of **Zero Trust Architecture**.
*   **Solution:** Implement **mTLS (Mutual TLS)** so services must prove their identity to each other, and use **JWT** for principal propagation.

---

## 💾 3. Data & Configuration

### 4. Zero-Redeploy Config Changes
*   **The Symptom:** Changing a feature flag requires a 20-minute CI/CD build.
*   **Solution:** Centralized Config Server.
*   **Action:** Use [Spring Cloud Config](https://spring.io) with the `@RefreshScope` annotation. When you update the config, hit the `/actuator/refresh` endpoint to update the bean without restarting.

### 9. Database Bottleneck
*   **The Symptom:** You scale your app pods, but response time doesn't improve.
*   **Root Cause:** **Shared Database Saturation.** All service instances are fighting for the same DB disk I/O or row locks.
*   **Action:** Implement **Read Replicas** or migrate to the **Database-per-Service** pattern to isolate load.

### 14. Idempotent API Design
*   **The Symptom:** A user clicks "Pay" twice; they are charged twice.
*   **Solution:** **Idempotency Keys.** The client sends a unique `UUID` with the request. The server stores this in [Redis](https://redis.io) for 24 hours. If the same UUID arrives again, the server returns the previous result instead of re-processing.

### 15. Distributed Consistency
*   **The Symptom:** Service A (Order) succeeds, but Service B (Payment) fails. Now the data is out of sync.
*   **Solution:** **The Saga Pattern.** Since you cannot use global DB locks (XA transactions) in microservices, you must write "Compensating Transactions" to undo Service A if Service B fails.

### 18. Risks of Shared Libraries
*   **The Symptom:** Upgrading a shared "Common-Util" library breaks 10 services.
*   **Root Cause:** **Tight Binary Coupling.** Services are now locked into the same version of Spring or Jackson.
*   **Solution:** Prefer **API-first integration** or duplicate small code snippets instead of creating a "God Library."

---

## ⚙️ 4. Deployment & Operations

### 13. System-wide Latency After One Deployment
*   **The Symptom:** You deployed Service X, and now Service Y is slow.
*   **Root Cause:** **Resource Stealing.** If both are on the same Kubernetes node, Service X might be consuming all the CPU/Network bandwidth.
*   **Action:** Define strict `Limits` and `Requests` in your [Kubernetes Pod Spec](https://kubernetes.io).

### 16. Why Autoscaling Fails
*   **The Symptom:** CPU is 100%, you add 10 more pods, but CPU stays 100% and latency increases.
*   **Root Cause:** **Startup Overhead or Dependency Bottlenecks.** The new pods are taking resources to start up, or they are just adding more connections to a DB that is already at 100% capacity.

### 17. Centralized Logging & Monitoring
*   **The Symptom:** You have to SSH into 10 different machines to find one error.
*   **Solution:** **The ELK Stack or Loki.**
*   **Action:** Push logs to [Grafana Loki](https://grafana.com) so you can query all service logs simultaneously using a `TraceID`.

### 19. Zero-Downtime Deployments
*   **The Symptom:** Every time you deploy, users see "502 Bad Gateway" for 30 seconds.
*   **Solution:** **Rolling Updates & Health Checks.**
*   **Action:** Configure Kubernetes `Liveness` and `Readiness` probes. Kubernetes won't route traffic to the new pod until it passes the readiness check.

### 20. When NOT to Use Microservices
*   **The Truth:** Microservices are an **Organizational solution**, not necessarily a technical one.
*   **Criteria:** Don't use them if you have a small team (< 10 people), a simple domain, or if you cannot handle the "Operational Tax" of tracing, centralized logging, and complex deployments.
*   **Recommendation:** Start with a **Modular Monolith**.
