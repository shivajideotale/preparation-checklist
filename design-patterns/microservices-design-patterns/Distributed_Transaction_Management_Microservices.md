# 🔄 Distributed Transaction Management in Microservices

> A comprehensive deep-dive into managing transactions across distributed systems — patterns, pitfalls, failure handling, and production-grade solutions.

---

## 📋 Table of Contents

- [Introduction](#introduction)
- [Why Traditional Transactions Fail](#why-traditional-transactions-fail)
- [CAP Theorem & BASE Properties](#cap-theorem--base-properties)
- [Core Patterns](#core-patterns)
  - [Saga Pattern](#1-saga-pattern)
    - [Choreography-Based Saga](#choreography-based-saga)
    - [Orchestration-Based Saga](#orchestration-based-saga)
    - [Compensating Transactions](#compensating-transactions)
  - [Two-Phase Commit (2PC)](#2-two-phase-commit-2pc)
  - [Three-Phase Commit (3PC)](#3-three-phase-commit-3pc)
  - [Outbox Pattern](#4-outbox-pattern)
  - [Event Sourcing](#5-event-sourcing)
  - [CQRS](#6-cqrs-command-query-responsibility-segregation)
- [Failure Scenarios & Handling](#failure-scenarios--handling)
- [Idempotency](#idempotency)
- [Eventual Consistency Deep Dive](#eventual-consistency-deep-dive)
- [Real-World Architecture Example](#real-world-architecture-example)
- [Tool & Framework Ecosystem](#tool--framework-ecosystem)
- [Pattern Decision Guide](#pattern-decision-guide)
- [Anti-Patterns to Avoid](#anti-patterns-to-avoid)
- [Code Examples](#code-examples)
- [Summary Comparison Table](#summary-comparison-table)

---

## Introduction

In a **monolithic** application, a database transaction guarantees that a group of operations either all succeed or all fail — this is the **ACID** contract (Atomicity, Consistency, Isolation, Durability).

In a **microservices** architecture:
- Each service owns its **own private database** (Database-Per-Service pattern)
- There is **no shared transaction manager** across services
- Services communicate over a **network**, which is inherently unreliable
- You must design for **partial failures**, **network partitions**, and **out-of-order message delivery**

This makes distributed transaction management one of the **hardest problems** in modern software engineering.

---

## Why Traditional Transactions Fail

### The Dual-Write Problem

Consider a simple order placement flow:

```
1. Save order to OrderDB       ← succeeds ✅
2. Publish "OrderCreated" event ← network fails ❌
```

Now your database has an order, but no downstream service knows about it. You have **inconsistent state**.

Naive solutions and why they fail:

| Approach | Problem |
|---|---|
| Write to DB first, then publish event | Event may never be published if process crashes |
| Publish event first, then write to DB | DB write may fail, event already sent |
| Use a distributed lock | Doesn't solve the atomic guarantee, adds latency |
| XA Transactions | Requires all systems to support XA, poor performance, blocking |

### ACID vs. Reality in Distributed Systems

| ACID Property | Monolith Reality | Microservices Reality |
|---|---|---|
| **Atomicity** | Single DB transaction | Must be manually implemented via Sagas |
| **Consistency** | Enforced by DB constraints | Eventual consistency across services |
| **Isolation** | DB-level locking | Requires careful design (versioning, idempotency) |
| **Durability** | WAL in single DB | Event logs, message brokers, idempotent retries |

---

## CAP Theorem & BASE Properties

### CAP Theorem

In a distributed system, you can only guarantee **2 out of 3**:

```
         Consistency
              /\
             /  \
            /    \
           /      \
          /________\
   Availability   Partition
                  Tolerance
```

| System Type | What it gives up | Example |
|---|---|---|
| **CP** (Consistent + Partition-tolerant) | Availability | HBase, Zookeeper |
| **AP** (Available + Partition-tolerant) | Strong consistency | Cassandra, DynamoDB |
| **CA** (Consistent + Available) | Partition tolerance | Traditional RDBMS (single node) |

> Microservices almost always choose **AP** — the system remains available and eventually becomes consistent.

### BASE Properties (The Distributed Alternative to ACID)

| Property | Meaning |
|---|---|
| **B**asically Available | System remains operational even during partial failures |
| **S**oft State | State may change over time even without new input (due to eventual consistency) |
| **E**ventually Consistent | System will become consistent once all events are processed |

---

## Core Patterns

---

### 1. Saga Pattern

A **Saga** is a sequence of local transactions where each step publishes an event or sends a message to trigger the next step. If a step fails, compensating transactions are executed to undo the previous steps.

#### Choreography-Based Saga

Each service reacts to events and decides what to do next. There is **no central coordinator**.

```
┌─────────────┐    OrderCreated     ┌─────────────────┐
│ OrderService│ ─────────────────→  │ PaymentService  │
└─────────────┘                     └─────────────────┘
                                             │
                                    PaymentProcessed
                                             │
                                             ↓
                                    ┌─────────────────┐
                                    │InventoryService │
                                    └─────────────────┘
                                             │
                                     StockReserved
                                             │
                                             ↓
                                    ┌─────────────────┐
                                    │ShippingService  │
                                    └─────────────────┘
                                             │
                                    ShipmentScheduled
                                             │
                                             ↓
                                    ┌─────────────────┐
                                    │  OrderService   │  ← marks order CONFIRMED
                                    └─────────────────┘
```

**Failure path (payment fails):**
```
PaymentFailed event
  → InventoryService receives it → does nothing (not yet reserved)
  → OrderService receives it → marks order FAILED
```

**Pros:**
- Loose coupling — services don't know about each other directly
- No single point of failure
- Easy to add new services to the flow

**Cons:**
- Difficult to track the overall transaction state
- Hard to debug when failures happen mid-flow
- Risk of cyclic event dependencies
- Requires careful event schema management

---

#### Orchestration-Based Saga

A **central orchestrator** (saga manager) explicitly tells each service what to do and tracks the overall state.

```
                    ┌──────────────────────────┐
                    │     Saga Orchestrator     │
                    │   (OrderSagaManager)      │
                    └──────────────────────────┘
                         │        │        │
              ┌──────────┘        │        └──────────┐
              ↓                   ↓                   ↓
    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
    │   Payment    │    │  Inventory   │    │   Shipping   │
    │   Service    │    │   Service    │    │   Service    │
    └──────────────┘    └──────────────┘    └──────────────┘
         Reply               Reply               Reply
          ↓                   ↓                   ↓
                    ┌──────────────────────────┐
                    │   Orchestrator decides   │
                    │   next step or rollback  │
                    └──────────────────────────┘
```

**State machine inside the orchestrator:**

```
PENDING → PAYMENT_PROCESSING → INVENTORY_RESERVING → SHIPPING_SCHEDULING → CONFIRMED
                │                      │                      │
           PAYMENT_FAILED       INVENTORY_FAILED       SHIPPING_FAILED
                │                      │                      │
           [compensate]           [refund payment]     [refund + release stock]
                ↓                      ↓                      ↓
           ORDER_CANCELLED       ORDER_CANCELLED        ORDER_CANCELLED
```

**Pros:**
- Single place to understand and debug the transaction flow
- Clear state machine — easy to monitor
- Easier to implement complex logic (retries, conditional branches)

**Cons:**
- Orchestrator can become a bottleneck or single point of failure (mitigate with clustering)
- Tighter coupling — orchestrator knows about all services
- More complex initial setup

---

#### Compensating Transactions

Every step in a Saga **must have a compensating transaction** — an operation that reverses the effect of the original action.

| Step | Forward Action | Compensating Action |
|---|---|---|
| 1. Create Order | `INSERT INTO orders (status='PENDING')` | `UPDATE orders SET status='CANCELLED'` |
| 2. Reserve Inventory | `UPDATE stock SET reserved=reserved+qty` | `UPDATE stock SET reserved=reserved-qty` |
| 3. Charge Payment | `POST /payments/charge` | `POST /payments/refund` |
| 4. Schedule Shipment | `POST /shipments/create` | `POST /shipments/cancel` |

> ⚠️ **Important:** Compensating transactions are **not rollbacks** in the traditional DB sense. They are new forward-moving transactions that undo a previous effect. They can also fail and must themselves be retried.

---

### 2. Two-Phase Commit (2PC)

2PC is a classic distributed algorithm for achieving **atomicity** across multiple databases.

#### How It Works

**Phase 1 — Prepare (Voting Phase):**
```
Coordinator → "PREPARE" → Participant A  → "VOTE-COMMIT" ✅
                        → Participant B  → "VOTE-COMMIT" ✅
                        → Participant C  → "VOTE-COMMIT" ✅
```

**Phase 2 — Commit (Completion Phase):**
```
Coordinator → "COMMIT"  → Participant A  → writes, releases locks ✅
                        → Participant B  → writes, releases locks ✅
                        → Participant C  → writes, releases locks ✅
```

**If any participant votes NO:**
```
Coordinator → "ROLLBACK" → All participants abort
```

#### Failure Scenarios in 2PC

| Failure Point | Effect |
|---|---|
| Participant crashes before voting | Coordinator times out → abort |
| Participant crashes after voting YES | Participant must recover and await coordinator decision |
| **Coordinator crashes after PREPARE** | **All participants block indefinitely** (the blocking problem) |
| Coordinator crashes after partial COMMIT | Some committed, some didn't → **data inconsistency** |

#### Why 2PC is Rarely Used in Microservices

1. **Blocking protocol** — participants hold locks waiting for coordinator
2. **Coordinator is a single point of failure**
3. **Poor performance** — synchronous, lock-heavy, high latency
4. **Not all datastores support XA** (required for cross-system 2PC)
5. **Doesn't scale** — more participants = more lock contention

> ✅ Use only in controlled, low-scale environments where strong consistency is non-negotiable.

---

### 3. Three-Phase Commit (3PC)

An improvement over 2PC that adds a **pre-commit** phase to avoid blocking.

```
Phase 1 — CanCommit:   Coordinator asks "Can you commit?"
Phase 2 — PreCommit:   Coordinator says "Get ready to commit" (no locks held yet)
Phase 3 — DoCommit:    Coordinator says "Commit now"
```

- Reduces blocking in **some** failure scenarios
- Still vulnerable to network partitions
- More complex than 2PC
- Rarely implemented in practice — Sagas are preferred

---

### 4. Outbox Pattern

The Outbox Pattern solves the **dual-write problem** by writing both the database record and the event in a **single local transaction**.

#### Architecture

```
┌─────────────────────────────────────┐
│           Service DB                │
│  ┌──────────────────────────────┐   │
│  │       orders table           │   │  ← 1. Write order
│  │  id | status | customer_id   │   │
│  └──────────────────────────────┘   │
│  ┌──────────────────────────────┐   │
│  │       outbox table           │   │  ← 2. Write event (same transaction)
│  │  id | event_type | payload   │   │
│  │  | status | created_at       │   │
│  └──────────────────────────────┘   │
└──────────────────┬──────────────────┘
                   │
                   │ (3. Relay polls outbox)
                   ↓
        ┌──────────────────────┐
        │   Message Relay /    │  ← Debezium CDC, Polling, or Transactional Outbox
        │   Event Publisher    │
        └──────────────────────┘
                   │
                   │ (4. Publishes to broker)
                   ↓
        ┌──────────────────────┐
        │    Message Broker    │  ← Kafka, RabbitMQ, SQS
        │    (Kafka topic)     │
        └──────────────────────┘
                   │
                   ↓
        ┌──────────────────────┐
        │  Downstream Services │
        └──────────────────────┘
```

#### Outbox Table Schema

```sql
CREATE TABLE outbox_events (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id  UUID NOT NULL,           -- e.g. order_id
    aggregate_type VARCHAR(100) NOT NULL,  -- e.g. 'Order'
    event_type    VARCHAR(100) NOT NULL,   -- e.g. 'OrderCreated'
    payload       JSONB NOT NULL,          -- event data
    status        VARCHAR(20) DEFAULT 'PENDING',  -- PENDING, PUBLISHED, FAILED
    created_at    TIMESTAMPTZ DEFAULT NOW(),
    published_at  TIMESTAMPTZ
);
```

#### Relay Strategies

| Strategy | How It Works | Pros | Cons |
|---|---|---|---|
| **Polling** | Relay queries for PENDING events on interval | Simple, no extra tools | Adds DB load, slight latency |
| **CDC (Debezium)** | Reads DB transaction log (WAL) | Near real-time, minimal DB overhead | Extra infrastructure |
| **Transactional log tailing** | Direct WAL reading | Most efficient | DB-specific |

> 💡 **Debezium + Kafka** is the most popular production combination for the Outbox Pattern.

---

### 5. Event Sourcing

Instead of storing the **current state** of an entity, Event Sourcing stores the **full history of events** that led to the current state.

#### State vs. Event Sourcing

**Traditional (state-based):**
```
orders table:
id=1, status='SHIPPED', amount=99.99, updated_at='2024-01-10'
```

**Event Sourced:**
```
order_events table:
id=1, order_id=1, event='OrderCreated',   payload={amount:99.99},  ts='2024-01-08'
id=2, order_id=1, event='PaymentCharged', payload={amount:99.99},  ts='2024-01-08'
id=3, order_id=1, event='OrderShipped',   payload={tracking:'XY'}, ts='2024-01-10'
```

Current state is computed by **replaying** all events.

#### Benefits

- **Complete audit trail** — know exactly what happened and when
- **Temporal queries** — reconstruct state at any point in time
- **Decoupled event publishing** — events are already stored, just publish them
- **Debugging** — replay events to reproduce bugs

#### Challenges

- **Event versioning** — older events must still be processable after schema changes
- **Eventual consistency** — read models must be rebuilt from events
- **Storage** — events accumulate; snapshotting needed for performance
- **Complexity** — paradigm shift from CRUD thinking

#### Snapshotting

```
event 1: OrderCreated
event 2: ItemAdded
event 3: ItemAdded
event 4: ItemRemoved
...
event 500: DiscountApplied
                ↓
[SNAPSHOT at event 500: {items: [...], total: 89.99}]
                ↓
event 501: OrderShipped  ← only replay from snapshot forward
```

---

### 6. CQRS (Command Query Responsibility Segregation)

CQRS separates **write operations (Commands)** from **read operations (Queries)** into different models.

```
                    ┌─────────────┐
                    │   Client    │
                    └─────────────┘
                     │           │
              Commands │           │ Queries
                     ↓           ↓
            ┌──────────────┐  ┌──────────────┐
            │  Write Model  │  │  Read Model  │
            │  (Commands)   │  │  (Queries)   │
            └──────────────┘  └──────────────┘
                   │                  ↑
              Events│                  │ Projections
                   ↓                  │
            ┌──────────────┐          │
            │  Event Store  │──────────┘
            │  (Source of   │
            │    Truth)     │
            └──────────────┘
```

**Works naturally with Event Sourcing:**
- Commands → generate events → stored in event store
- Events → project into read-optimized views (denormalized, cached)
- Read models can be rebuilt at any time from the event store

---

## Failure Scenarios & Handling

### Types of Failures

| Failure Type | Description | Handling Strategy |
|---|---|---|
| **Crash failure** | Service crashes mid-transaction | Idempotent retries, saga recovery |
| **Network partition** | Services can't communicate | Circuit breaker, timeout, compensate |
| **Byzantine failure** | Service returns incorrect data | Validation, checksums, consensus |
| **Timing failure** | Response too slow | Timeout + retry with backoff |
| **Semantic failure** | Business rule violated | Compensating transactions |

### Circuit Breaker Pattern

```
State machine:

    CLOSED ──(failure threshold exceeded)──→ OPEN
      ↑                                        │
      │                                        │ (after timeout)
      └──(success)── HALF-OPEN ←──────────────┘
```

- **CLOSED**: Normal operation, requests pass through
- **OPEN**: Service appears down, requests fail immediately (no waiting)
- **HALF-OPEN**: Allow a few test requests to probe recovery

### Retry with Exponential Backoff

```
Attempt 1: wait 100ms
Attempt 2: wait 200ms
Attempt 3: wait 400ms
Attempt 4: wait 800ms + jitter
Attempt 5: Dead Letter Queue
```

> ⚠️ Always add **jitter** (random offset) to avoid synchronized retry storms across services.

### Dead Letter Queue (DLQ)

```
Normal Flow:
Message → Queue → Consumer → Processed ✅

On repeated failure:
Message → Queue → Consumer (fails 3x) → Dead Letter Queue → Alert/Manual Review
```

---

## Idempotency

**Idempotency** means performing the same operation multiple times produces the same result as performing it once.

Critical in distributed systems because:
- Messages may be delivered **more than once** (at-least-once delivery)
- Retries must be safe to repeat
- Network failures cause uncertainty about whether an operation completed

### Implementation Strategies

**1. Idempotency Keys (client-generated):**
```http
POST /api/payments
Idempotency-Key: a8098c1a-f86e-11da-bd1a-00112444be1e

{
  "amount": 99.99,
  "currency": "USD"
}
```
Server stores the key + result. If same key arrives again, return cached result.

**2. Natural Idempotency:**
```sql
-- INSERT with conflict handling
INSERT INTO reservations (order_id, item_id, qty)
VALUES (123, 456, 2)
ON CONFLICT (order_id, item_id) DO NOTHING;
```

**3. Version/ETag-based:**
```sql
UPDATE orders
SET status = 'SHIPPED', version = version + 1
WHERE id = 123 AND version = 5;
-- If version doesn't match, operation is rejected (optimistic locking)
```

---

## Eventual Consistency Deep Dive

### Consistency Models

```
Strict ◄────────────────────────────────► Eventual
         │              │             │
    Strong           Causal        Eventual
   (linearizable)  consistency   consistency
         │
   Highest latency                Highest availability
   Lowest availability            Lowest latency
```

| Model | Guarantee | Use Case |
|---|---|---|
| **Strong** | All reads see latest write | Financial ledgers, inventory counts |
| **Causal** | Causally related ops seen in order | Social feeds, collaborative editing |
| **Eventual** | System will converge, no time guarantee | Shopping carts, DNS, user profiles |

### Handling Stale Reads

```
Time:  T1              T2              T3
       │               │               │
Write: ┌───────────────┤               │
       │ status=SHIPPED│               │
       └───────────────┤               │
                       │               │
Read (replica):        │  still reads  │  now reads
                       │  status=PACKED│  status=SHIPPED ✅
```

**Strategies:**
- Read-your-own-writes: Route reads to primary after a write
- Session tokens: Track version per user session
- UI hints: Show "as of X minutes ago" to set user expectations
- Polling: Let client poll until it sees the expected state

---

## Real-World Architecture Example

### E-Commerce Order Flow

```
                              ┌──────────────────┐
                              │    API Gateway    │
                              └──────────────────┘
                                       │
                              ┌──────────────────┐
                              │  Order Service   │
                              │  ┌────────────┐  │
                              │  │  Outbox DB │  │
                              │  └────────────┘  │
                              └──────────────────┘
                                       │
                              ┌──────────────────┐
                              │   Kafka Broker   │
                              └──────────────────┘
                    ┌──────────────┬────────┴──────────────┐
                    ↓              ↓                        ↓
         ┌──────────────┐ ┌──────────────┐      ┌──────────────────┐
         │   Payment    │ │  Inventory   │      │  Notification    │
         │   Service    │ │   Service    │      │    Service       │
         │  ┌────────┐  │ │  ┌────────┐  │      │  (Email/SMS)     │
         │  │Saga DB │  │ │  │Stock DB│  │      └──────────────────┘
         │  └────────┘  │ │  └────────┘  │
         └──────────────┘ └──────────────┘
                    │              │
                    └──────────────┘
                           │
                  ┌─────────────────┐
                  │  Saga           │
                  │  Orchestrator   │
                  │  (Temporal /    │
                  │   Axon)         │
                  └─────────────────┘
```

**Full flow with Outbox + Saga Orchestration:**

```
1.  Client → POST /orders
2.  OrderService → INSERT order (status=PENDING) + INSERT outbox_event (OrderCreated)
              [single DB transaction]
3.  Debezium reads outbox → publishes OrderCreated to Kafka
4.  SagaOrchestrator consumes OrderCreated → starts OrderSaga
5.  SagaOrchestrator → calls PaymentService.chargePayment(orderId, amount)
6a. SUCCESS: PaymentService → charges card → emits PaymentCharged
         → SagaOrchestrator → calls InventoryService.reserveStock(orderId, items)
         → InventoryService → reserves stock → emits StockReserved
         → SagaOrchestrator → calls ShippingService.scheduleShipment(orderId)
         → ShippingService → schedules → emits ShipmentScheduled
         → SagaOrchestrator → calls OrderService.confirmOrder(orderId)
         → OrderService → status=CONFIRMED ✅

6b. FAILURE (payment declined):
         → SagaOrchestrator receives PaymentFailed
         → No compensation needed (nothing was done yet)
         → SagaOrchestrator → calls OrderService.cancelOrder(orderId)
         → OrderService → status=CANCELLED

6c. FAILURE (stock unavailable after payment):
         → SagaOrchestrator receives StockUnavailable
         → SagaOrchestrator → calls PaymentService.refundPayment(chargeId)
         → PaymentService → issues refund → emits PaymentRefunded
         → SagaOrchestrator → calls OrderService.cancelOrder(orderId)
         → OrderService → status=CANCELLED ❌ (with refund issued)
```

---

## Tool & Framework Ecosystem

### Orchestration Frameworks

| Framework | Language | Key Features |
|---|---|---|
| **Temporal** | Go/Java/.NET/Python | Durable workflows, automatic retry, visibility UI |
| **Axon Framework** | Java | Saga + Event Sourcing + CQRS out of the box |
| **Conductor (Netflix)** | Language-agnostic | Workflow DSL, visual editor, multi-tenant |
| **Apache Camel** | Java | Enterprise integration patterns, wide connector library |
| **Eventuate Tram** | Java | Saga framework specifically for microservices |

### Message Brokers

| Broker | Best For | Notes |
|---|---|---|
| **Apache Kafka** | High-throughput event streaming | Durable log, replay, partitioning |
| **RabbitMQ** | Traditional message queuing | Routing, flexible topologies |
| **AWS SQS/SNS** | Cloud-native, serverless | Managed, simple, at-least-once |
| **Azure Service Bus** | Azure ecosystem | Sessions for ordered delivery |
| **NATS JetStream** | Low-latency, lightweight | Good for service mesh |

### Change Data Capture (CDC)

| Tool | Description |
|---|---|
| **Debezium** | Open-source CDC; supports PostgreSQL, MySQL, MongoDB, SQL Server |
| **AWS DMS** | Managed CDC for AWS databases |
| **Maxwell's Daemon** | MySQL CDC → Kafka |
| **PGLogical** | PostgreSQL logical replication |

### Service Mesh & Resilience

| Tool | Purpose |
|---|---|
| **Istio** | Traffic management, circuit breaking, retries |
| **Resilience4j** | Java circuit breaker, retry, bulkhead |
| **Polly** | .NET resilience library |
| **Envoy** | Sidecar proxy with built-in retries and circuit breaking |

---

## Pattern Decision Guide

```
START
  │
  ├─ Do you need strong ACID consistency?
  │     ├─ YES → Is it cross-service?
  │     │           ├─ NO  → Use local DB transactions ✅
  │     │           └─ YES → Can you redesign to avoid it?
  │     │                       ├─ YES → Redesign (preferred) ✅
  │     │                       └─ NO  → Use 2PC carefully ⚠️
  │     └─ NO → Continue ↓
  │
  ├─ Is eventual consistency acceptable?
  │     └─ YES → Continue ↓
  │
  ├─ Do you need a full audit trail or time-travel queries?
  │     └─ YES → Use Event Sourcing + CQRS ✅
  │
  ├─ How complex is the transaction flow?
  │     ├─ Simple (2-3 services, linear flow)
  │     │     └─ Use Choreography Saga ✅
  │     └─ Complex (4+ services, branching logic, retries)
  │           └─ Use Orchestration Saga ✅
  │
  ├─ Do you have a dual-write problem?
  │     └─ YES → Use Outbox Pattern ✅ (combine with Saga)
  │
  └─ Do you need both?
        └─ YES → Outbox Pattern + Saga Orchestration ✅ (most robust)
```

---

## Anti-Patterns to Avoid

### ❌ Distributed Monolith
Splitting code into microservices but sharing a single database — you get the complexity of microservices without the independence benefits.

### ❌ Synchronous Saga Steps
Making each saga step a synchronous HTTP call. One slow service blocks the entire transaction. Use async messaging instead.

### ❌ Ignoring Idempotency
Building retry logic without ensuring operations are idempotent. Causes double-charges, duplicate orders, etc.

### ❌ Too-Fine-Grained Sagas
Creating a saga for every tiny operation. Each saga step adds latency and failure surface area. Batch related operations within the same service.

### ❌ Missing Compensating Transactions
Designing the happy path only. Every saga step **must** have a compensation strategy before implementation.

### ❌ Shared Database Between Services
Defeats the purpose of microservices. Forces tight coupling and shared schema migrations.

### ❌ Event Sourcing Everything
Event sourcing adds significant complexity. Only use where audit trails or temporal queries are genuinely needed.

---

## Code Examples

### Saga Orchestrator (Pseudocode)

```python
class OrderSaga:
    def start(self, order_id: str):
        self.state = SagaState(order_id=order_id, step=Step.PAYMENT)
        self.save_state()
        self.payment_service.charge(order_id)

    def on_payment_success(self, order_id: str, charge_id: str):
        self.state.charge_id = charge_id
        self.state.step = Step.INVENTORY
        self.save_state()
        self.inventory_service.reserve(order_id)

    def on_inventory_unavailable(self, order_id: str):
        # Compensate: refund the payment
        self.state.step = Step.COMPENSATING_PAYMENT
        self.save_state()
        self.payment_service.refund(self.state.charge_id)

    def on_payment_refunded(self, order_id: str):
        self.state.step = Step.CANCELLED
        self.save_state()
        self.order_service.cancel(order_id)
```

### Outbox Pattern (SQL)

```sql
-- Application code: single transaction
BEGIN;

INSERT INTO orders (id, customer_id, status, total)
VALUES ('ord-123', 'cust-456', 'PENDING', 99.99);

INSERT INTO outbox_events (aggregate_id, aggregate_type, event_type, payload)
VALUES (
  'ord-123',
  'Order',
  'OrderCreated',
  '{"orderId":"ord-123","customerId":"cust-456","total":99.99}'
);

COMMIT;

-- Relay process (runs separately)
SELECT * FROM outbox_events WHERE status = 'PENDING' LIMIT 100;
-- Publish to Kafka, then:
UPDATE outbox_events SET status = 'PUBLISHED', published_at = NOW()
WHERE id IN (...);
```

### Idempotency Key Check

```python
def charge_payment(request):
    idempotency_key = request.headers.get("Idempotency-Key")

    # Check if we've seen this request before
    cached = redis.get(f"idem:{idempotency_key}")
    if cached:
        return json.loads(cached)  # Return same result as before

    # Process the request
    result = payment_gateway.charge(
        amount=request.body["amount"],
        card_token=request.body["card_token"]
    )

    # Cache the result for 24 hours
    redis.setex(f"idem:{idempotency_key}", 86400, json.dumps(result))

    return result
```

---

## Summary Comparison Table

| Pattern | Consistency | Complexity | Performance | Best For |
|---|---|---|---|---|
| **Local Transaction** | Strong ACID | Low | High | Single-service operations |
| **2PC** | Strong ACID | High | Low | Small-scale, controlled envs |
| **Choreography Saga** | Eventual | Medium | High | Simple flows, loose coupling |
| **Orchestration Saga** | Eventual | High | Medium | Complex flows, observability |
| **Outbox Pattern** | Eventual | Medium | High | Reliable event publishing |
| **Event Sourcing** | Eventual | Very High | Medium | Audit trails, temporal queries |
| **CQRS** | Eventual | High | High (reads) | Read-heavy systems |

---

## Further Reading

- [Designing Data-Intensive Applications — Martin Kleppmann](https://dataintensive.net/)
- [Microservices Patterns — Chris Richardson](https://microservices.io/book)
- [Saga Pattern — Chris Richardson's microservices.io](https://microservices.io/patterns/data/saga.html)
- [Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html)
- [Event Sourcing — Martin Fowler](https://martinfowler.com/eaaDev/EventSourcing.html)
- [Temporal Documentation](https://docs.temporal.io/)
- [Debezium Documentation](https://debezium.io/documentation/)

---

*Last updated: March 2026 | Maintained as a living reference document*
