# 🏗️ Microservices Design Patterns

> A comprehensive reference guide to all major design patterns used in microservices architecture — categorized, explained in depth, with diagrams, trade-offs, and real-world usage.

---

## 📋 Table of Contents

- [Decomposition Patterns](#-decomposition-patterns)
- [Database Patterns](#-database-patterns)
- [Communication Patterns](#-communication-patterns)
- [Reliability Patterns](#-reliability-patterns)
- [Security Patterns](#-security-patterns)
- [Observability Patterns](#-observability-patterns)
- [Deployment Patterns](#-deployment-patterns)
- [UI Patterns](#-ui-patterns)
- [Cross-Cutting Patterns](#-cross-cutting-patterns)
- [Pattern Summary Table](#-pattern-summary-table)

---

## 🔪 Decomposition Patterns

Patterns for breaking a monolith or designing service boundaries.

---

### 1. Decompose by Business Capability

Split services based on **what the business does**, not technical layers.

```
❌ Technical Layering:        ✅ Business Capability:
┌──────────────────┐         ┌──────────┐ ┌───────────┐ ┌──────────┐
│   UI Layer       │         │  Orders  │ │ Payments  │ │ Catalog  │
├──────────────────┤         └──────────┘ └───────────┘ └──────────┘
│   Service Layer  │         ┌──────────┐ ┌───────────┐ ┌──────────┐
├──────────────────┤         │Inventory │ │ Shipping  │ │  Users   │
│   Data Layer     │         └──────────┘ └───────────┘ └──────────┘
└──────────────────┘
```

- Each service = one business capability
- Owned by a single team (Conway's Law alignment)
- Independent deployability

---

### 2. Decompose by Subdomain (Domain-Driven Design)

Use **DDD Bounded Contexts** to define service boundaries.

```
E-Commerce Domain:
┌─────────────────────────────────────────────────────┐
│                                                     │
│  ┌─────────────┐   ┌─────────────┐   ┌───────────┐ │
│  │   Catalog   │   │   Orders    │   │  Shipping │ │
│  │  Subdomain  │   │  Subdomain  │   │ Subdomain │ │
│  └─────────────┘   └─────────────┘   └───────────┘ │
│                                                     │
│  ┌─────────────┐   ┌─────────────┐                 │
│  │  Payments   │   │  Identity   │                 │
│  │  Subdomain  │   │  Subdomain  │                 │
│  └─────────────┘   └─────────────┘                 │
└─────────────────────────────────────────────────────┘
```

**Subdomain Types:**
| Type | Description | Example |
|---|---|---|
| **Core** | Core competitive advantage | Recommendation Engine |
| **Supporting** | Supports core, non-differentiating | Inventory |
| **Generic** | Off-the-shelf solutions work | Email, Auth |

---

### 3. Strangler Fig Pattern

Gradually migrate a monolith to microservices without a big-bang rewrite.

```
Phase 1 — Monolith only:
Client → Monolith (all features)

Phase 2 — Strangle begins:
Client → Facade/Proxy → ┌─ New Microservice (Orders)
                        └─ Monolith (everything else)

Phase 3 — Full migration:
Client → API Gateway → Microservice A
                    → Microservice B
                    → Microservice C
         (Monolith retired) ✅
```

- Zero downtime migration
- Incremental, reversible steps
- Named after the strangler fig tree that grows around a host tree

---

### 4. Anti-Corruption Layer (ACL)

A translation layer that prevents a legacy system's model from polluting the new domain model.

```
New Service ←→ [Anti-Corruption Layer] ←→ Legacy System
               (translates models,
                adapts interfaces,
                shields from legacy chaos)
```

---

## 🗄️ Database Patterns

---

### 5. Database Per Service

Each service owns its **private database** — no direct DB sharing between services.

```
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│ Order Service│   │Pay. Service  │   │ User Service │
│  ┌────────┐  │   │  ┌────────┐  │   │  ┌────────┐  │
│  │ Orders │  │   │  │ Ledger │  │   │  │ Users  │  │
│  │   DB   │  │   │  │   DB   │  │   │  │   DB   │  │
│  └────────┘  │   │  └────────┘  │   │  └────────┘  │
└──────────────┘   └──────────────┘   └──────────────┘
     PostgreSQL          MySQL             MongoDB
```

- Services can choose the right DB for their use case
- Schema changes don't affect other services
- Requires Sagas/events to maintain data consistency

---

### 6. Shared Database (Anti-Pattern ⚠️)

Multiple services share a single database. Simpler but creates tight coupling.

```
┌──────────┐  ┌──────────┐  ┌──────────┐
│ Service A│  │ Service B│  │ Service C│
└──────────┘  └──────────┘  └──────────┘
      └──────────────┼──────────────┘
                     ↓
            ┌─────────────────┐
            │   Shared DB     │  ← schema changes break all services
            └─────────────────┘
```

Use only as a **transitional step** during migration.

---

### 7. Saga Pattern *(also a Transaction Pattern)*

Manage distributed transactions via a sequence of local transactions with compensations.
*(See full detail in [Distributed Transaction Management](Distributed_Transaction_Management_Microservices.md))*

---

### 8. CQRS (Command Query Responsibility Segregation)

Separate read and write models for scalability and flexibility.

```
                    Commands (writes)          Queries (reads)
                          │                         │
                          ↓                         ↓
                  ┌──────────────┐         ┌──────────────┐
                  │  Write Model │         │  Read Model  │
                  │  (normalized)│         │(denormalized)│
                  └──────────────┘         └──────────────┘
                          │                         ↑
                      Events                   Projections
                          └─────────────────────────┘
```

- Write model optimized for consistency
- Read model optimized for query performance
- Multiple read models possible (e.g., one per UI view)

---

### 9. Event Sourcing

Store state as a sequence of events instead of current state.

```
Order Lifecycle:
[OrderCreated] → [ItemAdded] → [PaymentCharged] → [OrderShipped]
     ↓                ↓               ↓                  ↓
  state v1         state v2        state v3           state v4 (current)
```

- Full audit trail
- Replay events to rebuild state
- Pairs naturally with CQRS

---

### 10. Outbox Pattern

Atomically write to DB and publish events using a transactional outbox table.

```
Service DB (single transaction):
  orders table  ←── write order
  outbox table  ←── write event

Message Relay → reads outbox → publishes to broker ✅
```

---

## 📡 Communication Patterns

---

### 11. API Gateway

Single entry point for all client requests — routes, authenticates, rate-limits, and transforms.

```
                    ┌─────────────────────┐
Mobile App ────────→│                     │→ User Service
Web App ───────────→│    API Gateway      │→ Order Service
Third-party ───────→│                     │→ Product Service
                    └─────────────────────┘
                      Auth | Rate Limit
                      Routing | SSL Termination
                      Request Aggregation
```

**Responsibilities:**
- Authentication & Authorization
- SSL termination
- Rate limiting & throttling
- Request routing & load balancing
- Response aggregation
- Protocol translation (REST ↔ gRPC ↔ WebSocket)

---

### 12. Backend for Frontend (BFF)

A dedicated API gateway **per client type**, tailored to each frontend's needs.

```
                    ┌──────────────┐
Mobile App ────────→│  Mobile BFF  │─┐
                    └──────────────┘ │
                    ┌──────────────┐ ├──→ Microservices
Web App ───────────→│   Web BFF    │─┤
                    └──────────────┘ │
                    ┌──────────────┐ │
Partner API ───────→│  Partner BFF │─┘
                    └──────────────┘
```

- Each BFF shapes data for its specific client
- Mobile BFF returns minimal payloads
- Web BFF can return richer aggregated data

---

### 13. Service Mesh

Infrastructure layer that handles **all service-to-service communication** via sidecar proxies.

```
┌──────────────────────┐    ┌──────────────────────┐
│  Service A           │    │  Service B           │
│  ┌────────────────┐  │    │  ┌────────────────┐  │
│  │  App Code      │  │    │  │  App Code      │  │
│  └────────────────┘  │    │  └────────────────┘  │
│  ┌────────────────┐  │    │  ┌────────────────┐  │
│  │ Sidecar Proxy  │◄─┼────┼─►│ Sidecar Proxy  │  │
│  │ (Envoy)        │  │    │  │ (Envoy)        │  │
│  └────────────────┘  │    │  └────────────────┘  │
└──────────────────────┘    └──────────────────────┘
           ↑                           ↑
           └───────────────────────────┘
                  Control Plane (Istio)
              (mTLS, retries, circuit breaker,
               traffic splitting, observability)
```

- **mTLS** between all services automatically
- Traffic management without code changes
- Tools: Istio, Linkerd, Consul Connect

---

### 14. Synchronous Communication (Request/Response)

Services call each other directly and wait for a response.

```
Client → Service A → Service B → Service C
                              ← response
                  ← response
       ← response
```

**Protocols:**
| Protocol | Use Case |
|---|---|
| **REST/HTTP** | External APIs, CRUD operations |
| **gRPC** | Internal high-performance calls, streaming |
| **GraphQL** | Flexible querying, BFF layer |

**Pros:** Simple, easy to reason about
**Cons:** Temporal coupling — if B is down, A fails

---

### 15. Asynchronous Messaging

Services communicate via messages through a broker — no waiting for response.

```
Producer → [Message Broker] → Consumer
              (Kafka/RabbitMQ)
```

**Messaging Models:**
| Model | Description | Example |
|---|---|---|
| **Point-to-Point (Queue)** | One producer, one consumer | Task queue |
| **Publish/Subscribe (Topic)** | One producer, many consumers | Event broadcast |
| **Request/Reply (Async)** | Async request with reply-to queue | Order status check |

**Pros:** Loose coupling, resilience, backpressure handling
**Cons:** Eventual consistency, harder to debug

---

### 16. Event-Driven Architecture

Services emit and react to domain events without direct knowledge of each other.

```
OrderService emits: OrderPlaced
  → PaymentService    (charges card)
  → InventoryService  (reserves stock)
  → NotificationSvc   (emails customer)
  → AnalyticsService  (records event)
```

- Highly decoupled
- Easy to add new consumers without changing producers
- Requires careful event schema management

---

## 🛡️ Reliability Patterns

---

### 17. Circuit Breaker

Prevent cascading failures by stopping calls to a failing service.

```
CLOSED ──(failures > threshold)──→ OPEN
  ↑                                  │
  └──(probe succeeds)── HALF-OPEN ←──┘ (after timeout)

CLOSED:    Normal traffic flows
OPEN:      Requests fail immediately (no waiting)
HALF-OPEN: Limited traffic to test recovery
```

**Benefits:**
- Fail fast instead of waiting for timeouts
- Allows failing service time to recover
- Prevents thread/connection pool exhaustion

---

### 18. Retry Pattern

Automatically retry failed operations with backoff.

```
Request → Fail → Wait 100ms → Retry
               → Fail → Wait 200ms → Retry
                        → Fail → Wait 400ms + jitter → Retry
                                 → Fail → Dead Letter Queue
```

**Always combine with:**
- **Idempotency** — retries must be safe
- **Jitter** — prevents synchronized retry storms
- **Max attempts** — avoid infinite loops

---

### 19. Bulkhead Pattern

Isolate failures by partitioning resources (thread pools, connection pools) per service.

```
Without Bulkhead:
All services share one thread pool → Service B overload kills A, C, D

With Bulkhead:
┌────────────┐ ┌────────────┐ ┌────────────┐
│ Service A  │ │ Service B  │ │ Service C  │
│ [threads]  │ │ [threads]  │ │ [threads]  │
│ pool: 10   │ │ pool: 10   │ │ pool: 10   │
└────────────┘ └────────────┘ └────────────┘
B's pool exhausted → only B fails, A and C unaffected ✅
```

---

### 20. Timeout Pattern

Set explicit timeouts on all remote calls to avoid blocking indefinitely.

```
Service A calls Service B:

Without timeout: A waits forever if B hangs 🔴
With timeout:    A waits 2s, then fails fast and compensates ✅
```

Always set timeouts at **every level**: HTTP client, DB connection, message consumer.

---

### 21. Rate Limiting / Throttling

Control the rate of requests to protect services from overload.

```
API Gateway:
Client → [Rate Limiter] → Service
          │
          ├─ Token Bucket: allow bursts up to X per window
          ├─ Leaky Bucket: smooth out traffic
          └─ Fixed Window: X requests per minute
```

**Response strategies:**
- Return `429 Too Many Requests`
- Queue excess requests
- Serve degraded/cached response

---

### 22. Health Check Pattern

Services expose health endpoints; orchestrators use them to route traffic.

```
GET /health/live   → Is the process running?
GET /health/ready  → Is the service ready to receive traffic?
GET /health/startup → Has the app started up?
```

**Kubernetes Probes map directly:**
- `livenessProbe` → `/health/live`
- `readinessProbe` → `/health/ready`
- `startupProbe` → `/health/startup`

---

## 🔐 Security Patterns

---

### 23. Access Token / JWT Pattern

Use short-lived tokens (JWT) for stateless authentication across services.

```
Client → Auth Service → JWT Token
Client → [JWT] → API Gateway → validates token → Service A
                                               → Service B
```

**JWT Structure:**
```
Header.Payload.Signature
  │        │        │
algorithm  claims  HMAC/RSA
           (userId,
            roles,
            expiry)
```

---

### 24. Service-to-Service Authentication (mTLS)

Services authenticate each other using mutual TLS certificates.

```
Service A → [present cert] → Service B
         ← [verify cert]  ←
         → [verify cert]  →
              ✅ Mutual trust established
              All traffic encrypted
```

- No passwords/tokens in code
- Certificates managed by service mesh (Istio) or Vault
- Zero-trust network model

---

### 25. API Gateway Security Pattern

Centralize security concerns at the gateway level.

```
Internet → API Gateway → Internal Network
            │
            ├─ Auth (OAuth2/JWT verification)
            ├─ Rate limiting
            ├─ IP allowlisting/blocklisting
            ├─ Input validation / WAF
            ├─ SSL/TLS termination
            └─ Audit logging
```

---

### 26. Secrets Management Pattern

Never store secrets in code or config files — use a centralized secrets store.

```
Service → [Vault / AWS Secrets Manager] → gets DB password at runtime
                │
                ├─ Secrets encrypted at rest
                ├─ Fine-grained access control
                ├─ Secret rotation without redeploys
                └─ Full audit trail of secret access
```

**Tools:** HashiCorp Vault, AWS Secrets Manager, Azure Key Vault, GCP Secret Manager

---

## 📊 Observability Patterns

---

### 27. Log Aggregation Pattern

Collect and centralize logs from all services into one searchable system.

```
Service A ─┐
Service B ─┼─→ Log Shipper → Elasticsearch / Loki → Kibana / Grafana
Service C ─┘   (Fluentd/
                Filebeat)
```

**Structured Logging (JSON):**
```json
{
  "timestamp": "2024-01-10T10:30:00Z",
  "level": "ERROR",
  "service": "order-service",
  "traceId": "abc-123",
  "orderId": "ord-456",
  "message": "Payment charge failed",
  "error": "Card declined"
}
```

---

### 28. Distributed Tracing

Track a request as it flows across multiple services.

```
Client Request (traceId: abc-123)
  │
  ├─ API Gateway    (spanId: 001, duration: 5ms)
  │   │
  │   ├─ Order Service    (spanId: 002, duration: 45ms)
  │   │   │
  │   │   ├─ Payment Service  (spanId: 003, duration: 30ms)
  │   │   │
  │   │   └─ Inventory Svc    (spanId: 004, duration: 10ms)
  │   │
  │   └─ Notification Svc (spanId: 005, duration: 5ms)
```

- Every service propagates `traceId` and `spanId` headers
- Tools: Jaeger, Zipkin, AWS X-Ray, OpenTelemetry

---

### 29. Metrics & Alerting Pattern

Expose and collect service metrics for monitoring and alerting.

```
Service → Metrics Endpoint (/metrics) → Prometheus → Grafana Dashboards
                                                   → AlertManager → PagerDuty
```

**Key metrics per service (RED Method):**
- **R**ate — requests per second
- **E**rrors — error rate %
- **D**uration — response latency (p50, p95, p99)

---

### 30. Health Dashboard Pattern

Centralized real-time view of all service health.

```
┌─────────────────────────────────────────┐
│         Service Health Dashboard        │
├──────────────┬──────────┬───────────────┤
│ Service      │ Status   │ Uptime        │
├──────────────┼──────────┼───────────────┤
│ Order Svc    │ 🟢 UP    │ 99.98%        │
│ Payment Svc  │ 🟢 UP    │ 99.95%        │
│ Inventory    │ 🟡 WARN  │ 98.50%        │
│ Shipping     │ 🔴 DOWN  │ 85.00%        │
└──────────────┴──────────┴───────────────┘
```

---

## 🚀 Deployment Patterns

---

### 31. Sidecar Pattern

Attach a helper container (sidecar) to each service pod to handle cross-cutting concerns.

```
┌──────────────────────────────┐
│           Pod                │
│  ┌────────────┐ ┌──────────┐ │
│  │ Main App   │ │ Sidecar  │ │
│  │            │ │ - Proxy  │ │
│  │  (Service) │ │ - Logging│ │
│  │            │ │ - Metrics│ │
│  └────────────┘ └──────────┘ │
└──────────────────────────────┘
```

- Sidecar shares network namespace with main app
- Concerns separated from business logic
- Examples: Envoy proxy, log shipper, secrets injector

---

### 32. Blue-Green Deployment

Maintain two identical environments; switch traffic instantly.

```
Current (Blue):  v1 ← 100% traffic
Staging (Green): v2 ← 0% traffic

After testing Green:
Blue:  v1 ← 0% traffic  (kept for instant rollback)
Green: v2 ← 100% traffic ✅
```

- Zero downtime deployments
- Instant rollback capability
- Doubles infrastructure cost

---

### 33. Canary Deployment

Roll out new version to a small percentage of users first.

```
v1: ██████████████████  95% traffic
v2: █                    5% traffic  (canary)

If metrics look good:
v1: ██████████           50% traffic
v2: ██████████           50% traffic

Full rollout:
v1:                       0% traffic
v2: ████████████████████ 100% traffic ✅
```

- Reduces blast radius of bad deploys
- Real-world traffic testing
- Automated rollback on error rate spike

---

### 34. Rolling Deployment

Replace instances one (or a few) at a time.

```
Start: [v1] [v1] [v1] [v1] [v1]
Step1: [v2] [v1] [v1] [v1] [v1]
Step2: [v2] [v2] [v1] [v1] [v1]
Step3: [v2] [v2] [v2] [v1] [v1]
Step4: [v2] [v2] [v2] [v2] [v1]
Done:  [v2] [v2] [v2] [v2] [v2] ✅
```

- No extra infrastructure cost
- Brief period of mixed versions running
- Default in Kubernetes

---

### 35. Service Discovery

Services find each other dynamically without hardcoded addresses.

```
Service A starts → registers with Service Registry (host:port)
Service B wants to call A → queries Registry → gets A's address
Service A scales/moves → Registry updated automatically
```

**Patterns:**
| Pattern | How | Tools |
|---|---|---|
| **Client-side discovery** | Client queries registry directly | Eureka + Ribbon |
| **Server-side discovery** | Load balancer queries registry | AWS ALB + Route 53 |
| **DNS-based** | Service name resolves via DNS | Kubernetes DNS, Consul |

---

## 🖥️ UI Patterns

---

### 36. Micro Frontends

Apply microservices thinking to the frontend — each team owns a frontend fragment.

```
┌──────────────────────────────────────────────┐
│                   Shell App                  │
│ ┌──────────────┐ ┌──────────┐ ┌───────────┐  │
│ │ Product Team │ │Cart Team │ │Order Team │  │
│ │  (React)     │ │ (Vue)    │ │(Angular)  │  │
│ └──────────────┘ └──────────┘ └───────────┘  │
└──────────────────────────────────────────────┘
```

- Each team deploys their UI independently
- Can use different frameworks per fragment
- Composed at runtime via Module Federation or iframes

---

### 37. API Composition Pattern

Aggregate data from multiple services for a single UI screen.

```
Product Detail Page needs:
  - Product info (from Catalog Service)
  - Stock level (from Inventory Service)
  - Price/offers (from Pricing Service)
  - Reviews (from Review Service)

API Composer (BFF or Gateway):
  → calls all 4 services (parallel)
  → merges responses
  → returns single response to UI ✅
```

---

## 🔄 Cross-Cutting Patterns

---

### 38. Externalized Configuration

Store configuration outside of the service code/image.

```
Service → [Config Server / Env Variables] → runtime config

Sources:
- Environment variables       (12-factor app standard)
- Kubernetes ConfigMaps       (non-secret config)
- Kubernetes Secrets          (sensitive config)
- Spring Cloud Config Server  (Java ecosystem)
- HashiCorp Consul KV         (dynamic config)
- AWS Parameter Store         (managed)
```

**Benefits:**
- Same image deployed to dev/staging/prod
- Config changes without rebuilds
- Central audit trail of config changes

---

### 39. Service Template / Chassis Pattern

A starter template with all cross-cutting concerns pre-wired so teams don't reinvent the wheel.

```
New Service = Service Chassis (template) + Business Logic

Chassis includes:
  ✅ Structured logging
  ✅ Metrics endpoint (/metrics)
  ✅ Health endpoints (/health/live, /health/ready)
  ✅ Distributed tracing (OpenTelemetry)
  ✅ Circuit breaker (Resilience4j)
  ✅ Auth middleware (JWT validation)
  ✅ Configuration loading
  ✅ Graceful shutdown
  ✅ Dockerfile + Helm chart
```

---

### 40. Idempotency Pattern

Ensure operations can be safely retried without unintended side effects.

```
POST /payments
Idempotency-Key: uuid-abc-123

First call:  → process payment → cache result with key → return result
Second call: → find cached result → return same result (no double charge) ✅
```

---

### 41. Saga / Compensating Transaction

*(See full detail in [Distributed Transaction Management](Distributed_Transaction_Management_Microservices.md))*

Every distributed multi-step workflow needs explicit compensating actions for rollback.

---

## 📊 Pattern Summary Table

| # | Pattern | Category | Solves | Trade-off |
|---|---|---|---|---|
| 1 | Decompose by Capability | Decomposition | Service boundaries | Requires domain knowledge |
| 2 | Decompose by Subdomain | Decomposition | DDD alignment | Complex upfront design |
| 3 | Strangler Fig | Decomposition | Safe migration | Slow, proxy overhead |
| 4 | Anti-Corruption Layer | Decomposition | Legacy isolation | Extra translation layer |
| 5 | Database Per Service | Database | Independence | Distributed transactions |
| 6 | Shared Database | Database | Simple (⚠️ anti-pattern) | Tight coupling |
| 7 | Saga | Database / Transactions | Distributed consistency | Complexity, compensation |
| 8 | CQRS | Database | Read/write scalability | Eventual consistency |
| 9 | Event Sourcing | Database | Audit trail, replay | Storage, complexity |
| 10 | Outbox Pattern | Database | Reliable event publishing | Relay infrastructure |
| 11 | API Gateway | Communication | Single entry point | Single point of failure |
| 12 | Backend for Frontend (BFF) | Communication | Per-client APIs | More services to maintain |
| 13 | Service Mesh | Communication | Cross-cutting networking | Infrastructure complexity |
| 14 | Sync Communication | Communication | Simplicity | Temporal coupling |
| 15 | Async Messaging | Communication | Loose coupling | Eventual consistency |
| 16 | Event-Driven | Communication | Decoupling | Debugging complexity |
| 17 | Circuit Breaker | Reliability | Cascading failure prevention | State management |
| 18 | Retry | Reliability | Transient failures | Must be idempotent |
| 19 | Bulkhead | Reliability | Fault isolation | Resource overhead |
| 20 | Timeout | Reliability | Resource protection | Tuning required |
| 21 | Rate Limiting | Reliability | Overload protection | Client experience |
| 22 | Health Check | Reliability | Automated recovery | Must be accurate |
| 23 | JWT / Access Token | Security | Stateless auth | Token expiry management |
| 24 | mTLS | Security | Service authentication | Cert management |
| 25 | Gateway Security | Security | Centralized auth | Gateway becomes critical |
| 26 | Secrets Management | Security | Credential safety | Extra dependency |
| 27 | Log Aggregation | Observability | Centralized logging | Storage costs |
| 28 | Distributed Tracing | Observability | Request flow visibility | Sampling overhead |
| 29 | Metrics & Alerting | Observability | Proactive monitoring | Alert fatigue |
| 30 | Health Dashboard | Observability | Operational visibility | Maintenance |
| 31 | Sidecar | Deployment | Separation of concerns | Resource overhead |
| 32 | Blue-Green | Deployment | Zero downtime deploys | Double infra cost |
| 33 | Canary | Deployment | Risk reduction | Traffic management complexity |
| 34 | Rolling | Deployment | No extra cost | Mixed versions |
| 35 | Service Discovery | Deployment | Dynamic addressing | Registry availability |
| 36 | Micro Frontends | UI | Frontend independence | Integration complexity |
| 37 | API Composition | UI | Aggregated responses | Latency accumulation |
| 38 | Ext. Configuration | Cross-cutting | Environment portability | Config service dependency |
| 39 | Service Chassis | Cross-cutting | Consistency across services | Template maintenance |
| 40 | Idempotency | Cross-cutting | Safe retries | Key management |
| 41 | Compensating Txn | Cross-cutting | Undo distributed ops | Must design rollbacks |

---

## 🗺️ Pattern Relationships

```
Decomposition Patterns
    └─→ Service Boundaries defined
            └─→ Database Per Service
                    └─→ Distributed Transaction Problem
                            ├─→ Saga Pattern
                            │       └─→ Choreography or Orchestration
                            ├─→ Outbox Pattern
                            └─→ Event Sourcing + CQRS

Communication
    ├─→ API Gateway / Backend for Frontend (BFF) (external)
    ├─→ Service Mesh (internal)
    ├─→ Sync (REST/gRPC) or Async (Kafka/RabbitMQ)
    └─→ Event-Driven Architecture

Reliability (always apply)
    └─→ Circuit Breaker + Retry + Bulkhead + Timeout + Health Check

Observability (always apply)
    └─→ Logs + Traces + Metrics (the three pillars)

Deployment
    └─→ Blue-Green or Canary + Service Discovery + Sidecar
```

---

## 📚 Further Reading

- [Microservices.io — Chris Richardson](https://microservices.io/patterns/)
- [Building Microservices — Sam Newman](https://www.oreilly.com/library/view/building-microservices-2nd/9781492034018/)
- [Designing Distributed Systems — Brendan Burns](https://www.oreilly.com/library/view/designing-distributed-systems/9781491983638/)
- [Microsoft Azure Architecture Patterns](https://learn.microsoft.com/en-us/azure/architecture/patterns/)
- [Martin Fowler's Microservices Resource Guide](https://martinfowler.com/microservices/)

---

*Last updated: March 2026 | 41 Patterns across 9 Categories*
