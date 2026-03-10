# 📐 CQRS — Command Query Responsibility Segregation

> A comprehensive deep-dive into CQRS: the theory, architecture, implementation patterns, event sourcing integration, real-world examples, trade-offs, and when (not) to use it.

---

## 📋 Table of Contents

- [What is CQRS?](#what-is-cqrs)
- [The Problem CQRS Solves](#the-problem-cqrs-solves)
- [Core Concepts](#core-concepts)
- [CQRS Architecture Layers](#cqrs-architecture-layers)
- [Commands — Deep Dive](#commands--deep-dive)
- [Queries — Deep Dive](#queries--deep-dive)
- [The Write Side (Command Stack)](#the-write-side-command-stack)
- [The Read Side (Query Stack)](#the-read-side-query-stack)
- [Synchronization — How Write Feeds Read](#synchronization--how-write-feeds-read)
- [CQRS + Event Sourcing](#cqrs--event-sourcing)
- [CQRS Levels of Implementation](#cqrs-levels-of-implementation)
- [Real-World Example — E-Commerce Order System](#real-world-example--e-commerce-order-system)
- [CQRS in Microservices](#cqrs-in-microservices)
- [Eventual Consistency in CQRS](#eventual-consistency-in-cqrs)
- [Projections — Building Read Models](#projections--building-read-models)
- [CQRS with Different Databases](#cqrs-with-different-databases)
- [Frameworks & Tools](#frameworks--tools)
- [Code Examples](#code-examples)
- [Benefits & Trade-offs](#benefits--trade-offs)
- [When to Use and When NOT to Use CQRS](#when-to-use-and-when-not-to-use-cqrs)
- [Anti-Patterns](#anti-patterns)
- [Summary](#summary)

---

## What is CQRS?

**CQRS** stands for **Command Query Responsibility Segregation**. It is an architectural pattern, originally described by **Greg Young** (building on Bertrand Meyer's **Command Query Separation (CQS)** principle), that separates **reading data** from **writing data** into two distinct models.

### The Root Principle — CQS (Command Query Separation)

Bertrand Meyer's original principle (1988):

> *"Every method should either be a command that performs an action, or a query that returns data — but not both."*

```
CQS at method level:
  int GetBalance()          ← Query: returns data, no side effects
  void Deposit(amount)      ← Command: changes state, returns nothing
  int WithdrawAndReturn()   ← ❌ Violation: does both
```

### CQRS — Taking CQS to the Architecture Level

CQRS applies this separation not just to methods, but to entire **models, databases, and services**:

```
Traditional (one model):              CQRS (two models):

     ┌───────────────────┐            ┌─────────────────┐   ┌─────────────────┐
     │    Single Model   │            │  Command Model  │   │  Query Model    │
     │   (reads+writes)  │     →      │  (writes only)  │   │  (reads only)   │
     └───────────────────┘            └─────────────────┘   └─────────────────┘
           │                                  │                      │
     ┌─────────────┐                  ┌──────────────┐     ┌────────────────┐
     │  Single DB  │                  │   Write DB   │     │   Read DB(s)   │
     └─────────────┘                  └──────────────┘     └────────────────┘
```

---

## The Problem CQRS Solves

### Problem 1 — The Impedance Mismatch Between Reads and Writes

Writes and reads have fundamentally different characteristics:

| Dimension | Writes (Commands) | Reads (Queries) |
|---|---|---|
| **Frequency** | Low (10–20% of traffic) | High (80–90% of traffic) |
| **Complexity** | Business logic, validation, rules | Data aggregation, joins, formatting |
| **Data shape** | Normalized, relational | Denormalized, view-specific |
| **Consistency** | Must be strongly consistent | Can tolerate slight staleness |
| **Scaling needs** | Vertical, transactional | Horizontal, eventually consistent |
| **Optimization** | Indexed for constraints | Indexed for query patterns |

**A single model optimized for writes is rarely optimal for reads, and vice versa.**

---

### Problem 2 — Complex Domain Models Are Hard to Query

A rich domain model with business rules is great for enforcing invariants — terrible for fetching data for a dashboard.

```
❌ Domain model used for reads:

public class Order {
    private List<OrderLine> lines;
    private Customer customer;          ← lazy-loaded (N+1 problem)
    private List<Payment> payments;     ← more lazy loads
    private ShippingAddress address;    ← yet another load
    private List<AuditLog> auditLog;    ← never needed for dashboard!

    // 200 lines of business logic that runs even for a simple GET
    public void validateInventory() {...}
    public void applyDiscounts() {...}
    public void calculateTax() {...}
}

// Just to show order summary on a dashboard → loads entire object graph 😩
```

**✅ With CQRS — query model is purpose-built:**
```sql
-- Read model: one optimized, denormalized query
SELECT
    o.id,
    o.total,
    o.status,
    c.name AS customer_name,
    COUNT(ol.id) AS item_count
FROM order_summary_view  -- pre-computed, denormalized
WHERE o.customer_id = ?
```

---

### Problem 3 — Read/Write Scaling Conflict

```
❌ Single database:
High read traffic → add replicas → but writes still go to master
High write traffic → optimize for writes → reads slow down
Can't scale independently ❌

✅ CQRS:
Write DB: PostgreSQL, optimized for ACID, moderate load
Read DB:  Elasticsearch (for search), Redis (for hot data),
          Cassandra (for time-series), replicated widely
Scale each independently ✅
```

---

## Core Concepts

### Commands

A **Command** is an **intent to change state**. It:
- Is named in imperative form: `PlaceOrder`, `CancelShipment`, `UpdateUserEmail`
- May be **rejected** (fails validation)
- Has **no return value** (or only an acknowledgement ID)
- Changes the state of the system
- Should be **targeted at a single aggregate**

```
Command → Validation → Business Logic → State Change → Event(s) Emitted
```

### Queries

A **Query** is a **request to read data**. It:
- Is named in interrogative form: `GetOrderById`, `ListUserOrders`, `SearchProducts`
- **Never changes state**
- Returns data shaped for the caller's needs
- Can be served from a different (optimized) data store
- Can be **cached** freely

```
Query → Read Model → Return DTO
```

### Events

An **Event** represents **something that happened**. It:
- Is named in past tense: `OrderPlaced`, `PaymentCharged`, `ShipmentDispatched`
- Is **immutable** — it already happened, cannot be undone
- Is the bridge that updates the read model when the write model changes
- Is stored in an event log (with Event Sourcing)

```
Command ──causes──→ Event ──updates──→ Read Model
```

---

## CQRS Architecture Layers

```
┌──────────────────────────────────────────────────────────────────────┐
│                         CLIENT / UI                                  │
│          Sends Commands                    Sends Queries             │
└──────────────────┬───────────────────────────────┬───────────────────┘
                   │                               │
        ┌──────────▼──────────┐        ┌───────────▼──────────┐
        │   COMMAND SIDE      │        │    QUERY SIDE         │
        │                     │        │                       │
        │  ┌───────────────┐  │        │  ┌─────────────────┐ │
        │  │ Command Bus   │  │        │  │  Query Bus      │ │
        │  └───────┬───────┘  │        │  └────────┬────────┘ │
        │          │          │        │           │          │
        │  ┌───────▼───────┐  │        │  ┌────────▼────────┐ │
        │  │Command Handler│  │        │  │  Query Handler  │ │
        │  └───────┬───────┘  │        │  └────────┬────────┘ │
        │          │          │        │           │          │
        │  ┌───────▼───────┐  │        │  ┌────────▼────────┐ │
        │  │   Aggregate   │  │        │  │   Read Model    │ │
        │  │ (Domain Model)│  │        │  │  (Projection)   │ │
        │  └───────┬───────┘  │        │  └────────┬────────┘ │
        │          │ Events   │        │           │          │
        │  ┌───────▼───────┐  │        │  ┌────────▼────────┐ │
        │  │   Write DB    │  │        │  │    Read DB      │ │
        │  │ (PostgreSQL)  │  │        │  │(Elasticsearch/  │ │
        │  └───────────────┘  │        │  │ Redis/Cassandra)│ │
        └──────────┬──────────┘        └───────────────────── ┘
                   │                               ▲
                   │    Events / Change Feed        │
                   └────────────────────────────────┘
                         (Kafka / Event Store / CDC)
```

---

## Commands — Deep Dive

### Command Structure

A command is a plain data object that captures the **intent** and all necessary **data** to fulfill it.

```java
// Command — imperative name, carries all needed data
public record PlaceOrderCommand(
    String customerId,
    List<OrderItem> items,
    ShippingAddress shippingAddress,
    String paymentMethodId
) implements Command {}

public record CancelOrderCommand(
    String orderId,
    String reason,
    String requestedBy
) implements Command {}

public record UpdateShippingAddressCommand(
    String orderId,
    ShippingAddress newAddress
) implements Command {}
```

### Command Validation — Two Layers

```
Layer 1 — Structural Validation (at the boundary, before dispatch):
  - Are required fields present?
  - Are field formats valid? (UUID format, email regex, positive amounts)
  - This should be fast and cheap

Layer 2 — Business Validation (inside the aggregate/handler):
  - Does this customer exist?
  - Does this order belong to this customer?
  - Is the order in a state that allows cancellation?
  - Is there enough inventory?
  - This is where business invariants are enforced
```

```java
// Layer 1: Structural (annotation-based or fluent)
public record PlaceOrderCommand(
    @NotNull String customerId,
    @NotEmpty List<OrderItem> items,
    @Valid ShippingAddress shippingAddress
) {}

// Layer 2: Business (inside the aggregate)
public class Order {
    public List<DomainEvent> cancel(String reason) {
        if (this.status == SHIPPED) {
            throw new OrderAlreadyShippedException(this.id);
        }
        if (this.status == CANCELLED) {
            throw new OrderAlreadyCancelledException(this.id);
        }
        this.status = CANCELLED;
        return List.of(new OrderCancelledEvent(this.id, reason));
    }
}
```

### Command Bus

Routes commands to their handlers. Supports middleware for cross-cutting concerns.

```
PlaceOrderCommand
      │
      ▼
┌─────────────────────────────────────────────┐
│               Command Bus                   │
│  ┌─────────────┐  ┌───────────────────────┐ │
│  │ Middleware 1│  │ Middleware 2           │ │
│  │ (Logging)   │  │ (Auth check)          │ │
│  └─────────────┘  └───────────────────────┘ │
│  ┌─────────────┐  ┌───────────────────────┐ │
│  │ Middleware 3│  │ Middleware 4           │ │
│  │ (Validation)│  │ (Transaction wrapper) │ │
│  └─────────────┘  └───────────────────────┘ │
└─────────────────────────────────────────────┘
      │
      ▼
PlaceOrderCommandHandler.handle(command)
```

### Command Handler

```java
@Component
public class PlaceOrderCommandHandler implements CommandHandler<PlaceOrderCommand> {

    private final OrderRepository orderRepository;
    private final EventPublisher eventPublisher;

    @Override
    @Transactional
    public CommandResult handle(PlaceOrderCommand command) {
        // 1. Load or create aggregate
        Order order = Order.create(
            OrderId.generate(),
            CustomerId.of(command.customerId()),
            command.items(),
            command.shippingAddress()
        );

        // 2. Execute business logic — aggregate returns domain events
        List<DomainEvent> events = order.place(command.paymentMethodId());

        // 3. Persist the aggregate
        orderRepository.save(order);

        // 4. Publish events (for read model update + downstream consumers)
        eventPublisher.publishAll(events);

        // 5. Return acknowledgement (not the full order — that's a query!)
        return CommandResult.success(order.getId().value());
    }
}
```

---

## Queries — Deep Dive

### Query Structure

```java
// Queries — interrogative name, carries filter/pagination params
public record GetOrderByIdQuery(String orderId) implements Query<OrderDetailView> {}

public record ListCustomerOrdersQuery(
    String customerId,
    OrderStatus statusFilter,
    LocalDate fromDate,
    LocalDate toDate,
    int page,
    int pageSize
) implements Query<PagedResult<OrderSummaryView>> {}

public record SearchProductsQuery(
    String searchText,
    List<String> categoryIds,
    PriceRange priceRange,
    SortOption sortBy
) implements Query<List<ProductSearchResult>> {}
```

### Query Handler — Thin & Fast

Query handlers should be **thin** — their only job is to fetch pre-shaped data from the read model.

```java
@Component
public class GetOrderByIdQueryHandler implements QueryHandler<GetOrderByIdQuery, OrderDetailView> {

    private final OrderReadRepository readRepository;

    @Override
    public OrderDetailView handle(GetOrderByIdQuery query) {
        // Direct read from denormalized read model — no domain logic!
        return readRepository.findDetailById(query.orderId())
            .orElseThrow(() -> new OrderNotFoundException(query.orderId()));
    }
}

// Read Repository — talks directly to read DB
public interface OrderReadRepository {
    Optional<OrderDetailView> findDetailById(String orderId);
    PagedResult<OrderSummaryView> findByCustomer(String customerId, PageRequest page);
}
```

### Read Model DTOs (View Models)

Each view gets its own purpose-built DTO — **not** the domain model.

```java
// View model for order detail page — includes everything the UI needs
public record OrderDetailView(
    String orderId,
    String status,
    String customerName,
    String customerEmail,
    List<OrderLineView> items,
    MoneyView subtotal,
    MoneyView tax,
    MoneyView total,
    String shippingCarrier,
    String trackingNumber,
    String estimatedDelivery,
    List<OrderEventView> timeline   // order history
) {}

// View model for order list page — minimal data only
public record OrderSummaryView(
    String orderId,
    String status,
    String placedAt,
    int itemCount,
    MoneyView total
) {}

// View model for admin dashboard
public record OrderAdminView(
    String orderId,
    String customerId,
    String customerName,
    String status,
    String fraudScore,
    String paymentStatus,
    String internalNotes
) {}
```

---

## The Write Side (Command Stack)

### Aggregates — The Core of the Write Side

An **Aggregate** is a cluster of domain objects that must be treated as a unit for data changes. It:
- Enforces **business invariants** (rules that must always be true)
- Encapsulates state — no direct external mutation
- Produces **domain events** when state changes
- Has a single **Aggregate Root** that controls all access

```java
public class Order {   // Aggregate Root

    private OrderId id;
    private CustomerId customerId;
    private OrderStatus status;
    private List<OrderLine> lines;       // child entities
    private Money total;
    private List<DomainEvent> events = new ArrayList<>();  // captured events

    // Factory method
    public static Order create(OrderId id, CustomerId customerId,
                               List<OrderItem> items, ShippingAddress address) {
        Order order = new Order();
        // Apply event — changes state AND records event
        order.apply(new OrderCreatedEvent(id, customerId, items, address));
        return order;
    }

    // Business operation
    public void cancel(String reason) {
        // Business invariant check
        if (this.status == OrderStatus.SHIPPED) {
            throw new CannotCancelShippedOrderException(this.id);
        }
        // Apply the event
        apply(new OrderCancelledEvent(this.id, reason, LocalDateTime.now()));
    }

    // Reserve inventory
    public void confirmInventory(List<ReservedItem> reservations) {
        if (this.status != OrderStatus.PENDING) {
            throw new InvalidOrderStateException(this.id, this.status);
        }
        apply(new InventoryConfirmedEvent(this.id, reservations));
    }

    // Internal — applies event (changes state)
    private void apply(OrderCreatedEvent event) {
        this.id = event.orderId();
        this.customerId = event.customerId();
        this.status = OrderStatus.PENDING;
        this.lines = buildLines(event.items());
        this.total = calculateTotal(event.items());
        this.events.add(event);
    }

    private void apply(OrderCancelledEvent event) {
        this.status = OrderStatus.CANCELLED;
        this.events.add(event);
    }

    // Return and clear captured events (for publishing)
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> result = new ArrayList<>(this.events);
        this.events.clear();
        return result;
    }
}
```

### Write Database — Optimized for Consistency

```
Write DB characteristics:
  ✅ Normalized (3NF) — no data duplication
  ✅ ACID transactions — full consistency
  ✅ Optimized for writes and constraint enforcement
  ✅ Foreign keys, check constraints, unique constraints active
  ✅ Moderate read load — only used for loading aggregates
  ❌ Not optimized for complex queries
  ❌ Should not be queried directly by the UI
```

```sql
-- Write DB schema (normalized, constrained)
CREATE TABLE orders (
    id          UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customers(id),
    status      VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','CONFIRMED','SHIPPED','CANCELLED')),
    total_cents BIGINT NOT NULL CHECK (total_cents >= 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version     INT NOT NULL DEFAULT 0    -- optimistic locking
);

CREATE TABLE order_lines (
    id          UUID PRIMARY KEY,
    order_id    UUID NOT NULL REFERENCES orders(id),
    product_id  UUID NOT NULL REFERENCES products(id),
    qty         INT NOT NULL CHECK (qty > 0),
    unit_price  BIGINT NOT NULL
);
```

---

## The Read Side (Query Stack)

### Projections — Building the Read Model

A **Projection** listens to domain events and updates the read model accordingly. It is the **bridge** between the write side and read side.

```
Domain Events → Event Consumer (Projection) → Read Database
```

```java
@Component
public class OrderProjection {

    private final OrderReadRepository readDb;

    // Reacts to OrderCreated → builds initial read record
    @EventHandler
    public void on(OrderCreatedEvent event) {
        OrderSummaryView view = new OrderSummaryView(
            event.orderId().value(),
            "PENDING",
            event.occurredAt().toString(),
            event.items().size(),
            event.total()
        );
        readDb.save(view);
    }

    // Reacts to OrderShipped → updates status + adds tracking
    @EventHandler
    public void on(OrderShippedEvent event) {
        readDb.updateStatus(event.orderId().value(), "SHIPPED");
        readDb.updateTracking(event.orderId().value(), event.trackingNumber());
    }

    // Reacts to OrderCancelled → updates status
    @EventHandler
    public void on(OrderCancelledEvent event) {
        readDb.updateStatus(event.orderId().value(), "CANCELLED");
    }
}
```

### Multiple Read Models from Same Events

One of CQRS's superpowers: **the same events feed multiple read models**, each optimized for a different query pattern.

```
OrderCreatedEvent ──→ OrderSummaryProjection      → PostgreSQL (general queries)
                  ──→ OrderSearchProjection        → Elasticsearch (full-text search)
                  ──→ OrderAnalyticsProjection     → ClickHouse (analytics/reporting)
                  ──→ OrderDashboardProjection     → Redis (real-time dashboard)
                  ──→ CustomerOrderHistoryProj.    → Cassandra (customer history)
```

Each read model is a **different shape of the same data**, tuned for its consumer.

---

## Synchronization — How Write Feeds Read

### Option 1 — Synchronous (In-Process)

Write and update read model in the same transaction. Simple but couples write and read sides.

```
BEGIN TRANSACTION
  UPDATE orders SET status = 'CANCELLED'   ← write side
  UPDATE order_summary SET status = 'CANCELLED'  ← read side (same DB)
COMMIT

✅ Immediately consistent
❌ Only works if both sides share the same DB
❌ Read model complexity bleeds into write transaction
```

### Option 2 — Asynchronous via Message Broker (Recommended)

```
Write side: saves aggregate → publishes events to Kafka
                                      │
                              Kafka topic: order-events
                                      │
          ┌───────────────────────────┼────────────────────────────┐
          ↓                           ↓                            ↓
 OrderSummaryProjection    OrderSearchProjection     AnalyticsProjection
  (updates PostgreSQL)      (updates Elasticsearch)  (updates ClickHouse)

✅ Write side is decoupled — doesn't know about read models
✅ Multiple read models updated independently
✅ Read models can be rebuilt by replaying events
❌ Eventual consistency — brief lag between write and read
```

### Option 3 — Change Data Capture (CDC with Debezium)

```
Write side: saves to PostgreSQL
                │
       PostgreSQL WAL (transaction log)
                │
           Debezium CDC
                │
          Kafka topics
                │
        Projections update read DBs

✅ No code changes to write side
✅ Guaranteed at-least-once delivery
✅ Works even if projections are down (Kafka retains events)
```

---

## CQRS + Event Sourcing

CQRS and Event Sourcing are **complementary but independent**. They're often used together but you can have one without the other.

### How They Combine

```
COMMAND SIDE (Event Sourcing):
  Command → Aggregate loads from Event Store (replays events)
          → Aggregate executes business logic
          → New events appended to Event Store
          → Events published to message broker

READ SIDE (CQRS):
  Events → Projections → Multiple Read Models (PostgreSQL, Redis, Elasticsearch)
  Queries → Read Models → DTOs returned to client
```

### Event Store as Source of Truth

```
Event Store (append-only log):
┌────────────────────────────────────────────────────────────┐
│ seq │ aggregateId │ event           │ payload    │ version │
├─────┼─────────────┼─────────────────┼────────────┼─────────┤
│ 1   │ ord-123     │ OrderCreated    │ {...}      │ 1       │
│ 2   │ ord-123     │ PaymentCharged  │ {...}      │ 2       │
│ 3   │ ord-456     │ OrderCreated    │ {...}      │ 1       │
│ 4   │ ord-123     │ OrderShipped    │ {...}      │ 3       │
│ 5   │ ord-456     │ OrderCancelled  │ {...}      │ 2       │
└────────────────────────────────────────────────────────────┘

To load Order ord-123:
  → Fetch events where aggregateId = 'ord-123' ORDER BY version
  → Replay: OrderCreated → PaymentCharged → OrderShipped
  → Current state: { status: SHIPPED, ... } ✅
```

### Rebuilding Read Models

This is the killer feature of CQRS + Event Sourcing:

```
Read model is wrong / corrupted / needs new fields?

1. Shut down the projection (or add a new one)
2. Clear the read DB table
3. Replay ALL events from event store from the beginning
4. Projection re-processes every event → read model rebuilt perfectly

New read model needed for a new feature?
1. Write a new projection
2. Replay all historical events through it
3. New read model is fully populated, ready to query ✅
```

---

## CQRS Levels of Implementation

CQRS is not binary — you can adopt it gradually.

### Level 0 — No CQRS (Traditional)

```
One model, one DB, one repository:
Controller → Service → Repository → DB
         ← data    ←           ←
```

### Level 1 — Logical Separation (Same DB)

Separate command and query methods, but same database.

```
OrderCommandService  → uses write-optimized queries → same DB
OrderQueryService    → uses read-optimized queries  → same DB (maybe read replica)

✅ Low risk, easy refactor
✅ Can start here if unsure about CQRS
❌ Still one DB — limited scalability
```

### Level 2 — Separate Models, Same DB

Distinct command and query objects, separate DAOs, same database.

```
PlaceOrderCommand → CommandHandler → Order (domain model) → OrdersTable
GetOrderQuery     → QueryHandler   → OrderDetailView DTO  → OrdersTable (join view)

✅ Clear separation of concerns
✅ Domain model only used for writes
❌ Read model still limited by write DB schema
```

### Level 3 — Separate Models, Separate Databases (Full CQRS)

```
Write side: PostgreSQL (normalized, transactional)
Read side:  Elasticsearch + Redis + PostgreSQL read replica

Events synchronize write → read asynchronously via Kafka

✅ Full independent scaling
✅ Read DB optimized freely
✅ Multiple read models
❌ Eventual consistency
❌ Higher operational complexity
```

### Level 4 — Full CQRS + Event Sourcing

```
Write side: Event Store (Axon Server, EventStoreDB)
            Domain state = replay of events

Read side:  Multiple purpose-built stores (Redis, Elasticsearch, etc.)
            Rebuilt anytime by replaying events

✅ Complete audit trail
✅ Time-travel queries
✅ Any read model, any time
❌ Highest complexity
```

---

## Real-World Example — E-Commerce Order System

### Write Side Flow

```
1. User submits order form
   │
2. API Controller receives request
   → validates input structure
   → creates PlaceOrderCommand

3. Command Bus dispatches to PlaceOrderCommandHandler

4. Handler:
   a. Validates business rules (customer exists, items available)
   b. Creates Order aggregate
   c. Order.place() → produces [OrderCreatedEvent, InventoryReservedEvent]
   d. Saves Order to write DB
   e. Publishes events to Kafka topic: "order-events"
   f. Returns { orderId: "ord-789", status: "PENDING" }

5. Client receives orderId — uses it for subsequent queries
```

### Read Side — Multiple Projections

```
Kafka: order-events topic
         │
         ├──→ OrderSummaryProjection
         │       Updates: order_summaries table (PostgreSQL)
         │       Query: GET /orders?customerId=456
         │
         ├──→ OrderDetailProjection
         │       Updates: order_details table (PostgreSQL, denormalized)
         │       Query: GET /orders/789
         │
         ├──→ OrderSearchProjection
         │       Updates: Elasticsearch index
         │       Query: GET /orders/search?q=laptop&status=SHIPPED
         │
         ├──→ AdminDashboardProjection
         │       Updates: Redis (real-time counters)
         │       Query: GET /admin/stats (orders per minute, revenue today)
         │
         └──→ AnalyticsProjection
                 Updates: ClickHouse (columnar, OLAP)
                 Query: GET /reports/revenue?groupBy=month
```

### Sequence Diagram

```
Client           API Gateway      Command Handler    Event Bus     Projection     Read DB
  │                  │                  │               │              │              │
  │─PlaceOrder──────→│                  │               │              │              │
  │                  │─PlaceOrderCmd───→│               │              │              │
  │                  │                  │─save order────→(write DB)    │              │
  │                  │                  │─publish───────→│             │              │
  │                  │                  │               │─OrderCreated→│              │
  │                  │                  │               │              │─update──────→│
  │                  │←─orderId─────────│               │              │              │
  │←─202 Accepted────│                  │               │              │              │
  │                  │                  │               │              │              │
  │─GET /orders/789─→│                  │               │              │              │
  │                  │─────────GetOrderByIdQuery─────────────────────────────────────→│
  │←─OrderDetailView─│←────────────────────────────────────────────────────OrderView─│
```

---

## CQRS in Microservices

### Service Boundary Per Bounded Context

```
Order Microservice (owns CQRS internally):
┌─────────────────────────────────────────────────────────────┐
│  Command API:    POST /orders, PUT /orders/{id}/cancel       │
│  Query API:      GET /orders/{id}, GET /orders?customerId=X  │
│                                                             │
│  Write Side:  Order Aggregate → PostgreSQL                  │
│  Read Side:   Order Projections → PostgreSQL + Redis        │
│  Event Bus:   Kafka (publishes to other services too)       │
└─────────────────────────────────────────────────────────────┘
```

### Cross-Service Read Models

Services maintain their **own** read models built from other services' events:

```
Shipping Service needs order data for delivery tracking:
  → Subscribes to order-events from Order Service
  → Maintains its own local ShipmentOrderView (only fields it needs)
  → No cross-service query needed at read time ✅

Notification Service needs customer email + order status:
  → Subscribes to order-events + user-events
  → Maintains NotificationView { orderId, customerEmail, status }
  → Self-sufficient read model ✅
```

### CQRS + API Gateway Pattern

```
                     ┌─────────────────────┐
                     │     API Gateway      │
                     └─────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              │                               │
     Commands (writes)                  Queries (reads)
              │                               │
              ↓                               ↓
   ┌──────────────────┐            ┌──────────────────┐
   │  Command Service │            │  Query Service   │
   │  (write-heavy)   │            │  (read-heavy)    │
   │  2 replicas      │            │  10 replicas     │
   └──────────────────┘            └──────────────────┘
```

---

## Eventual Consistency in CQRS

### The Consistency Window

```
T=0:    User submits PlaceOrderCommand
T=1ms:  Order saved to write DB ✅
T=5ms:  OrderCreatedEvent published to Kafka
T=8ms:  Projection consumes event
T=10ms: Read model updated ✅

User queries GET /orders/123 at T=3ms:
  → Read model not yet updated
  → Returns "Order not found" or stale data ⚠️
```

### Strategies for Managing the Consistency Window

**a) Read-Your-Own-Writes**
```
After placing order → redirect to /orders/{id}
Client polls: GET /orders/{id} with retry until data appears
OR
Return enough data in command response to render UI without a query:
  { orderId: "ord-789", status: "PENDING", placedAt: "2024-01-10T10:30Z" }
```

**b) Optimistic UI Updates**
```
UI immediately shows new state (based on command input)
Silently syncs with server in background
If server state differs → update UI
(Used by Gmail, Twitter, etc.)
```

**c) Versioned Queries**
```
Command returns: { orderId: "ord-789", version: 5 }
Client queries:  GET /orders/789?minVersion=5
Server: if read model version < 5 → wait up to 2s, then return
```

**d) Consistency SLAs**
```
Define and communicate per operation:
  PlaceOrder → read model consistent within 2 seconds
  CancelOrder → read model consistent within 1 second
  UpdateProfile → read model consistent within 5 seconds
```

---

## Projections — Building Read Models

### Types of Projections

**1. Live Projections (always up to date)**
```
Consumes events in real-time via Kafka Consumer Group
Small lag (milliseconds to seconds)
```

**2. Catchup Projections (replay historical events)**
```
New projection reads all events from beginning of event store
Used when: adding a new read model, fixing a bug in existing projection
```

**3. Snapshot Projections (periodic materialized views)**
```
DB job runs every hour → rebuilds entire materialized view
Simpler but higher latency
```

### Projection Rebuilding Strategy

```
Problem: Read model has a bug, missing a field, or needs restructuring

Solution with Event Sourcing:
  1. Deploy new projection code (new table/index name: order_summary_v2)
  2. Replay all events → populate order_summary_v2
  3. Switch query handlers to use order_summary_v2
  4. Drop order_summary_v1

Without Event Sourcing (CDC replay):
  1. Replay DB change log (Debezium) from a past timestamp
  2. Or: one-time migration script from write DB
```

---

## CQRS with Different Databases

### Choosing the Right Read Store

| Read Model Need | Best Database | Why |
|---|---|---|
| Complex relational queries | PostgreSQL read replica | Familiar SQL, joins |
| Full-text search | Elasticsearch | Inverted index, relevance scoring |
| Real-time dashboards | Redis | Sub-millisecond reads, data structures |
| Time-series / analytics | ClickHouse / TimescaleDB | Columnar, OLAP optimized |
| Graph relationships | Neo4j | Native graph traversal |
| High-scale key-value | DynamoDB / Cassandra | Horizontal scale, low latency |
| Geospatial queries | PostGIS / Elasticsearch | Geo-indexing |
| Document store | MongoDB | Flexible schema, nested docs |

### Example: Three Read Models for One Domain

```
Order Domain Events → Kafka
     │
     ├──→ PostgreSQL Read Replica
     │      Tables: order_summaries, order_details, order_lines
     │      Used for: standard order management UI
     │
     ├──→ Elasticsearch
     │      Index: orders (with nested customer, product names)
     │      Used for: admin search "find all laptop orders from John"
     │
     └──→ Redis
            Keys: dashboard:orders:today → count
                  dashboard:revenue:today → total
                  dashboard:top_products → sorted set
            Used for: real-time admin dashboard
```

---

## Frameworks & Tools

### Java / Spring Ecosystem

| Tool | Role |
|---|---|
| **Axon Framework** | Full CQRS + Event Sourcing framework (command bus, event bus, projections) |
| **Spring CQRS** | Lightweight CQRS with Spring + Kafka |
| **EventStoreDB** | Purpose-built event store database |
| **Axon Server** | Event store + message routing (pairs with Axon Framework) |

### .NET Ecosystem

| Tool | Role |
|---|---|
| **MediatR** | Command/query bus for .NET (very popular) |
| **EventFlow** | CQRS + Event Sourcing framework for .NET |
| **NEventStore** | Event store abstraction |
| **Marten** | PostgreSQL-backed event store + document store |

### Event Streaming / Messaging

| Tool | Role |
|---|---|
| **Apache Kafka** | Primary event bus between write and read sides |
| **RabbitMQ** | Alternative message broker |
| **AWS EventBridge** | Managed event bus (AWS ecosystem) |
| **Debezium** | CDC — captures DB changes as events (Outbox pattern) |

### Observability

| Tool | Role |
|---|---|
| **OpenTelemetry** | Trace commands and queries end-to-end |
| **Prometheus + Grafana** | Command latency, error rates, projection lag metrics |
| **Jaeger / Zipkin** | Distributed tracing |

---

## Code Examples

### Full CQRS Flow — Java (Spring + Axon)

```java
// ── COMMAND ──────────────────────────────────────────────────
public record PlaceOrderCommand(
    @TargetAggregateIdentifier String orderId,
    String customerId,
    List<OrderItem> items
) {}

// ── AGGREGATE (Write Side) ────────────────────────────────────
@Aggregate
public class OrderAggregate {

    @AggregateIdentifier
    private String orderId;
    private OrderStatus status;

    @CommandHandler
    public OrderAggregate(PlaceOrderCommand cmd) {
        // validate, then emit event
        AggregateLifecycle.apply(new OrderPlacedEvent(
            cmd.orderId(), cmd.customerId(), cmd.items()
        ));
    }

    @EventSourcingHandler
    public void on(OrderPlacedEvent event) {
        this.orderId = event.orderId();
        this.status = OrderStatus.PENDING;
    }

    @CommandHandler
    public void handle(CancelOrderCommand cmd) {
        if (status == OrderStatus.SHIPPED) {
            throw new IllegalStateException("Cannot cancel shipped order");
        }
        AggregateLifecycle.apply(new OrderCancelledEvent(orderId, cmd.reason()));
    }

    @EventSourcingHandler
    public void on(OrderCancelledEvent event) {
        this.status = OrderStatus.CANCELLED;
    }
}

// ── PROJECTION (Read Side) ────────────────────────────────────
@Component
@ProcessingGroup("order-summary-projection")
public class OrderSummaryProjection {

    private final OrderSummaryRepository repo;

    @EventHandler
    public void on(OrderPlacedEvent event, @Timestamp Instant timestamp) {
        repo.save(new OrderSummaryEntity(
            event.orderId(),
            event.customerId(),
            "PENDING",
            event.items().size(),
            timestamp
        ));
    }

    @EventHandler
    public void on(OrderCancelledEvent event) {
        repo.updateStatus(event.orderId(), "CANCELLED");
    }

    // ── QUERY HANDLER ──────────────────────────────────────────
    @QueryHandler
    public OrderSummaryView handle(GetOrderSummaryQuery query) {
        return repo.findById(query.orderId())
            .map(this::toView)
            .orElseThrow(() -> new OrderNotFoundException(query.orderId()));
    }
}

// ── CONTROLLER ────────────────────────────────────────────────
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;

    @PostMapping
    public ResponseEntity<Map<String, String>> placeOrder(@RequestBody PlaceOrderRequest req) {
        String orderId = UUID.randomUUID().toString();
        commandGateway.sendAndWait(new PlaceOrderCommand(orderId, req.customerId(), req.items()));
        return ResponseEntity.accepted().body(Map.of("orderId", orderId));
    }

    @GetMapping("/{orderId}")
    public OrderSummaryView getOrder(@PathVariable String orderId) {
        return queryGateway.query(
            new GetOrderSummaryQuery(orderId),
            ResponseTypes.instanceOf(OrderSummaryView.class)
        ).join();
    }
}
```

### MediatR Pattern — .NET

```csharp
// Command
public record PlaceOrderCommand(string CustomerId, List<OrderItem> Items)
    : IRequest<PlaceOrderResult>;

// Command Handler
public class PlaceOrderCommandHandler : IRequestHandler<PlaceOrderCommand, PlaceOrderResult>
{
    private readonly IOrderRepository _repo;
    private readonly IEventBus _eventBus;

    public async Task<PlaceOrderResult> Handle(PlaceOrderCommand cmd, CancellationToken ct)
    {
        var order = Order.Create(cmd.CustomerId, cmd.Items);
        await _repo.SaveAsync(order, ct);
        await _eventBus.PublishAsync(order.DomainEvents, ct);
        return new PlaceOrderResult(order.Id);
    }
}

// Query
public record GetOrderByIdQuery(string OrderId) : IRequest<OrderDetailView>;

// Query Handler
public class GetOrderByIdQueryHandler : IRequestHandler<GetOrderByIdQuery, OrderDetailView>
{
    private readonly IOrderReadDb _readDb;

    public async Task<OrderDetailView> Handle(GetOrderByIdQuery query, CancellationToken ct)
        => await _readDb.GetOrderDetailAsync(query.OrderId, ct);
}

// Controller
[ApiController, Route("orders")]
public class OrdersController : ControllerBase
{
    private readonly IMediator _mediator;

    [HttpPost]
    public async Task<IActionResult> PlaceOrder(PlaceOrderRequest req)
    {
        var result = await _mediator.Send(new PlaceOrderCommand(req.CustomerId, req.Items));
        return Accepted(new { orderId = result.OrderId });
    }

    [HttpGet("{orderId}")]
    public async Task<OrderDetailView> GetOrder(string orderId)
        => await _mediator.Send(new GetOrderByIdQuery(orderId));
}
```

---

## Benefits & Trade-offs

### ✅ Benefits

| Benefit | Description |
|---|---|
| **Independent scaling** | Scale read and write services independently based on load |
| **Optimized models** | Each model is purpose-built — writes for consistency, reads for performance |
| **Multiple read models** | Same data exposed in different shapes for different consumers |
| **Simpler domain model** | Domain model focuses only on business rules, not query needs |
| **Better performance** | Read queries avoid loading heavy domain objects and running business logic |
| **Auditability** | Events provide a natural audit trail of all changes |
| **Flexibility** | Change read model without touching write model, and vice versa |
| **Resilience** | Read side can serve cached data if write side is temporarily down |
| **Rebuild-ability** | Read models can be rebuilt from events at any time |

### ❌ Trade-offs

| Trade-off | Description |
|---|---|
| **Eventual consistency** | Read model may lag behind write model (milliseconds to seconds) |
| **Complexity** | Two models, two databases, event synchronization infrastructure |
| **More code** | Separate commands, queries, handlers, projections — more classes |
| **Debugging difficulty** | Async event flow is harder to trace than synchronous call stack |
| **Infrastructure overhead** | Kafka, separate databases, CDC tools add operational burden |
| **Event schema evolution** | Changing event structure requires migration of old events |
| **Learning curve** | Team must understand CQRS, DDD aggregates, event sourcing concepts |

---

## When to Use and When NOT to Use CQRS

### ✅ Use CQRS When:

```
✅ Read/write ratio is highly asymmetric (90% reads, 10% writes)
✅ Read and write scalability needs are very different
✅ Complex domain with rich business rules that conflict with query needs
✅ Multiple clients need data in different shapes (mobile, web, API partners)
✅ Full audit trail required (financial, healthcare, compliance systems)
✅ You need to support complex search (full-text, geospatial, faceted)
✅ Real-time dashboards and analytics alongside transactional data
✅ Large team — CQRS enables parallel development on read/write sides
✅ Event-driven architecture already in place
```

### ❌ Do NOT Use CQRS When:

```
❌ Simple CRUD application — CQRS adds massive overhead for no benefit
❌ Small team or early-stage startup — complexity slows down velocity
❌ Domain logic is minimal — separation has nothing to separate
❌ Strong consistency required everywhere — eventual consistency is a problem
❌ Team is unfamiliar with DDD and event-driven patterns — steep learning curve
❌ Reads and writes are roughly equal and simple — no asymmetry to exploit
❌ Application is small or short-lived — ROI doesn't justify investment
```

> **Rule of thumb:** Start without CQRS. Add it when you feel the pain it solves — not before.

---

## Anti-Patterns

### ❌ Querying the Write Database from the Read Side

```
❌ Don't do this:
QueryHandler → Order (domain aggregate) → write DB
             → Customer aggregate → write DB
             → Payment aggregate → write DB
             → join everything → return DTO

This defeats the purpose: you're using the write model for reads,
running unnecessary business logic, and coupling both sides to one DB.
```

### ❌ Returning Domain Objects from Query Handlers

```
❌ Wrong:
QueryHandler → loads Order aggregate → returns Order domain object to controller

✅ Right:
QueryHandler → reads OrderDetailView from read DB → returns DTO

Domain objects should never leave the write side.
```

### ❌ Commands with Return Values (Beyond Acknowledgement)

```
❌ Wrong:
PlaceOrderCommand → handler → returns full OrderDetailView

✅ Right:
PlaceOrderCommand → handler → returns { orderId } (just enough to query)
Client then calls: GET /orders/{orderId}

Commands are fire-and-effect, not fire-and-fetch.
```

### ❌ Fat Commands (Logic in Command Objects)

```
❌ Wrong:
public class PlaceOrderCommand {
    public Money calculateTotal() { ... }    // ← business logic in command!
    public boolean isValid() { ... }         // ← validation belongs in handler
}

✅ Right:
Commands are dumb data bags. Logic belongs in handlers and aggregates.
```

### ❌ Skipping Events and Directly Updating Read DB

```
❌ Wrong:
CommandHandler → updates write DB → ALSO directly updates read DB

This tightly couples write and read sides, removes the ability to
rebuild read models, and defeats the decoupling CQRS provides.

✅ Right:
CommandHandler → updates write DB → publishes event
Event → Projection → updates read DB (async, decoupled)
```

---

## Summary

```
CQRS in one picture:

  ┌─────────────────────────────────────────────────────────────────┐
  │                      YOUR APPLICATION                          │
  │                                                                 │
  │   WRITES                              READS                     │
  │   ──────                              ─────                     │
  │   Commands                            Queries                   │
  │   (intent to change)                  (request to read)        │
  │        │                                    │                   │
  │        ▼                                    ▼                   │
  │   Command Handler                     Query Handler             │
  │   + Aggregate                         (thin layer)              │
  │   (business rules)                         │                    │
  │        │                                    │                   │
  │        ▼                                    ▼                   │
  │   Write Database                      Read Database(s)          │
  │   (normalized,                        (denormalized,            │
  │    consistent)                         fast, multiple)          │
  │        │                                    ▲                   │
  │        └──── Events ──→ Projections ────────┘                   │
  │                         (bridge that keeps                      │
  │                          read side current)                     │
  └─────────────────────────────────────────────────────────────────┘

Key Rules:
  1. Commands change state. Queries read state. Never both.
  2. Queries NEVER touch the write DB or domain model.
  3. Commands NEVER return query results — only acknowledgements.
  4. Read models are rebuilt from events — they are disposable.
  5. Start simple (Level 1). Scale complexity as needed.
```

---

## 📚 Further Reading

- [Greg Young's Original CQRS Document](https://cqrs.files.wordpress.com/2010/11/cqrs_documents.pdf)
- [Martin Fowler — CQRS](https://martinfowler.com/bliki/CQRS.html)
- [Microsoft CQRS Pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/cqrs)
- [Axon Framework Documentation](https://docs.axoniq.io/)
- [EventStoreDB Documentation](https://developers.eventstore.com/)
- [Implementing Domain-Driven Design — Vaughn Vernon](https://www.oreilly.com/library/view/implementing-domain-driven-design/9780133039900/)
- [Microservices Patterns — Chris Richardson](https://microservices.io/book)

---

*Last updated: March 2026 | Covers CQRS Levels 0–4, Event Sourcing Integration, Real-World Patterns & Anti-Patterns*
