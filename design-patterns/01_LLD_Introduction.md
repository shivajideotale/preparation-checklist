# 01 — What is Low Level Design (LLD)?

> **LLD is the art of translating requirements into a precise, code-ready blueprint** — defining every class, interface, relationship, and pattern before writing production code.

---

## Table of Contents

1. [LLD vs HLD — The Big Difference](#1-lld-vs-hld--the-big-difference)
2. [What LLD Covers](#2-what-lld-covers)
3. [The LLD Process — Step by Step](#3-the-lld-process--step-by-step)
4. [SOLID + Design Patterns Inside LLD](#4-solid--design-patterns-inside-lld)
5. [UML Class Diagram Basics](#5-uml-class-diagram-basics)
6. [Full Mini LLD — Parking Lot System](#6-full-mini-lld--parking-lot-system)
7. [Common LLD Interview Problems](#7-common-lld-interview-problems)
8. [LLD vs Code — What to Produce](#8-lld-vs-code--what-to-produce)
9. [Key Takeaway](#9-key-takeaway)

---

## 1. LLD vs HLD — The Big Difference

Think of designing a building:

```
HLD (High Level Design)              LLD (Low Level Design)
─────────────────────────            ──────────────────────────────────
"3 floors, 10 apartments,            "Floor 2, Apt 4B is 900 sq ft,
 1 parking garage,                    north-facing, with a sliding
 1 rooftop garden."                   wardrobe 6 ft wide, 3 power
                                      sockets, one 5-ton AC unit..."
```

| Aspect | HLD (High Level Design) | LLD (Low Level Design) |
|---|---|---|
| **Focus** | System architecture | Class / component internals |
| **Audience** | Architects, Tech Leads, Managers | Developers who write the code |
| **Output** | System diagram, tech stack, service boundaries | Class diagrams, method signatures, DB schema, API contracts |
| **Questions answered** | *What* services exist? *Which* technology? | *How* does each class work? *Which* pattern? |
| **Abstraction level** | 10,000 ft — bird's eye view | Ground level — hands in the code |
| **Example** | "We need a Payment Service and an Order Service" | `PaymentService` class has `charge(customerId, amount)` returning `PaymentResult`, implemented by `StripePaymentGateway` via `PaymentGateway` interface |

---

## 2. What LLD Covers

### 2A — Class Design

Defining every class: its fields, methods, visibility, and responsibilities.

```java
// HLD says: "We need a User Management Module"
// LLD defines EXACTLY this:

public class User {
    private Long          id;
    private String        name;
    private String        email;
    private String        passwordHash;
    private Role          role;
    private boolean       isActive;
    private LocalDateTime createdAt;

    // Constructor, getters, setters...
}

public interface UserRepository {
    Optional<User> findByEmail(String email);
    Optional<User> findById(Long id);
    User           save(User user);
    void           deleteById(Long id);
    List<User>     findAll();
}

public class UserService {
    private final UserRepository  repository;   // DIP — interface, not MySQL class
    private final PasswordEncoder encoder;
    private final EmailService    emailService;

    public UserService(UserRepository repo, PasswordEncoder encoder,
                       EmailService emailService) {
        this.repository   = repo;
        this.encoder      = encoder;
        this.emailService = emailService;
    }

    public User    register(RegisterRequest request) { ... }
    public User    login(String email, String password) { ... }
    public void    resetPassword(String email) { ... }
    public void    deactivate(Long userId) { ... }
}
```

---

### 2B — Relationships Between Classes

LLD specifies the **type** of relationship — not just "these classes are connected":

| Relationship | Meaning | Java Example |
|---|---|---|
| **Association** | A uses B | `Order` uses `Product` |
| **Aggregation** | A has B (B can live without A) | `Department` has `Employee`s |
| **Composition** | A owns B (B dies with A) | `Order` owns `OrderItem`s |
| **Inheritance** | A is-a B | `Dog extends Animal` |
| **Dependency** | A depends on B temporarily | Method parameter type |
| **Realization** | A implements B | `MySQLRepo implements UserRepository` |

```
User ──────────────────── Order
(1 user → many orders)    (Composition — items die with order)
                           │
                           ├── OrderItem  ──── Product
                           │   (many items)    (one product per item)
                           │
                           └── Payment
                               (1-to-1, composition)
```

---

### 2C — Design Patterns Applied

LLD is where you decide **which pattern** solves which problem:

```
Payment processing     → Strategy Pattern    (UPI / Card / NetBanking / Wallet)
Order status updates   → Observer Pattern    (Email / SMS / Push on status change)
Building order object  → Builder Pattern     (complex Order with many optional fields)
DB connection pool     → Singleton Pattern   (one shared pool)
Product creation       → Factory Pattern     (Digital vs Physical product)
Notification channels  → Chain of Responsibility (try Push → SMS → Email)
Legacy payment API     → Adapter Pattern     (wrap old API in new interface)
```

---

### 2D — API Contracts

```
POST   /api/orders
─────────────────────────────────────────────────
Request Body:
  {
    "userId":        123,
    "items":         [{ "productId": 456, "quantity": 2 }],
    "paymentMethod": "UPI",
    "addressId":     789
  }

Response 201 Created:
  {
    "orderId":           "ORD-20250331-001",
    "status":            "CONFIRMED",
    "totalAmount":       1499.00,
    "estimatedDelivery": "2025-04-03"
  }

Errors:
  400 Bad Request     — missing fields / invalid quantity
  402 Payment Failed  — payment gateway declined
  404 Not Found       — product or address not found
  409 Conflict        — insufficient stock
```

---

### 2E — Database Schema

```sql
-- Users table
CREATE TABLE users (
    id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(150) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          ENUM('ADMIN','USER','GUEST') DEFAULT 'USER',
    is_active     BOOLEAN      DEFAULT TRUE,
    created_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- Orders table
CREATE TABLE orders (
    id            BIGINT          PRIMARY KEY AUTO_INCREMENT,
    user_id       BIGINT          NOT NULL,
    status        ENUM('PENDING','CONFIRMED','SHIPPED','DELIVERED','CANCELLED'),
    total_amount  DECIMAL(10,2)   NOT NULL,
    created_at    TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Order Items table
CREATE TABLE order_items (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id    BIGINT NOT NULL,
    product_id  BIGINT NOT NULL,
    quantity    INT    NOT NULL,
    unit_price  DECIMAL(10,2) NOT NULL,
    FOREIGN KEY (order_id)   REFERENCES orders(id)   ON DELETE CASCADE,
    FOREIGN KEY (product_id) REFERENCES products(id)
);
```

---

## 3. The LLD Process — Step by Step

```
┌─────────────────────────────────────────────────────────────────┐
│  STEP 1 — Gather Requirements                                   │
│  ─────────────────────────────                                  │
│  Functional:     Users can place orders, track delivery,        │
│                  cancel within 10 minutes                       │
│  Non-Functional: Handle 10,000 orders/min, 99.9% uptime         │
│  Constraints:    No payment data stored on our servers          │
└──────────────────────────────┬──────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────┐
│  STEP 2 — Identify Entities (Nouns in requirements)             │
│  ──────────────────────────────────────────────────             │
│  User, Order, Product, Cart, OrderItem,                         │
│  Payment, Address, Notification, Review                         │
└──────────────────────────────┬──────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────┐
│  STEP 3 — Define Relationships                                  │
│  ─────────────────────────────                                  │
│  User      has many   Orders        (one-to-many)               │
│  Order     has many   OrderItems    (composition)               │
│  OrderItem references one Product   (association)               │
│  Order     has one    Payment       (composition)               │
│  User      has many   Addresses     (aggregation)               │
└──────────────────────────────┬──────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────┐
│  STEP 4 — Apply SOLID Principles                                │
│  ────────────────────────────────                               │
│  SRP → each class has one job                                   │
│  OCP → new payment method = new class, not edit existing        │
│  LSP → PaymentGateway implementations are substitutable         │
│  ISP → small interfaces (Chargeable, Refundable separate)       │
│  DIP → OrderService depends on PaymentGateway, not Stripe       │
└──────────────────────────────┬──────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────┐
│  STEP 5 — Choose Design Patterns                                │
│  ────────────────────────────────                               │
│  Where does behavior vary?  → Strategy Pattern                  │
│  Who needs to be notified?  → Observer Pattern                  │
│  Complex object creation?   → Builder Pattern                   │
│  One shared resource?       → Singleton Pattern                 │
│  Incompatible interfaces?   → Adapter Pattern                   │
└──────────────────────────────┬──────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────┐
│  STEP 6 — Draw Class Diagram                                    │
│  ────────────────────────────                                   │
│  Fields, methods, types, visibility, relationships              │
└──────────────────────────────┬──────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────┐
│  STEP 7 — Write Core Java Skeleton                              │
│  ──────────────────────────────────                             │
│  Interfaces, key classes, method stubs                          │
└──────────────────────────────┬──────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────┐
│  STEP 8 — Define APIs and DB Schema                             │
│  ──────────────────────────────────                             │
│  REST endpoints, request/response DTOs, tables, indexes         │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. SOLID + Design Patterns Inside LLD

This is the core of LLD — everything is connected:

```
LLD
 │
 ├── SOLID Principles  ← rules governing EVERY design decision
 │     ├── S  One class, one reason to change
 │     ├── O  Extend by adding code, not editing it
 │     ├── L  Subclasses must safely substitute their base
 │     ├── I  Small, focused interfaces — never force unused methods
 │     └── D  Depend on abstractions, inject concretions
 │
 └── Design Patterns  ← proven templates for common structural problems
       ├── Creational  (HOW objects are created)
       │     ├── Singleton   — one shared instance
       │     ├── Factory     — create without knowing concrete type
       │     ├── Builder     — step-by-step complex object construction
       │     ├── Prototype   — clone instead of new
       │     └── Abstract Factory — consistent families of objects
       │
       ├── Structural  (HOW objects are composed)
       │     ├── Adapter     — bridge incompatible interfaces
       │     ├── Decorator   — add behavior by wrapping
       │     ├── Proxy       — control access to an object
       │     ├── Facade      — simple interface to complex subsystem
       │     ├── Composite   — treat leaf and group uniformly
       │     ├── Bridge      — separate abstraction from implementation
       │     └── Flyweight   — share fine-grained objects
       │
       └── Behavioral  (HOW objects communicate)
             ├── Strategy    — interchangeable algorithms
             ├── Observer    — broadcast state changes
             ├── Command     — encapsulate request as object
             ├── State       — behavior changes with state
             ├── Chain of Responsibility — pass request along handlers
             ├── Template Method — fix skeleton, vary steps
             ├── Iterator    — sequential traversal
             ├── Mediator    — centralise communication
             ├── Memento     — snapshot and restore
             ├── Visitor     — add operations without changing structure
             ├── Interpreter — evaluate grammar rules
             └── Null Object — safe do-nothing substitute
```

> **LLD = SOLID Principles + Design Patterns + Class Diagrams + API Design + DB Schema**, applied to one specific system.

---

## 5. UML Class Diagram Basics

LLD is communicated through **UML Class Diagrams**. Key notations:

```
┌─────────────────────────┐
│       ClassName         │  ← Class name (bold/italic if abstract)
├─────────────────────────┤
│ - privateField: Type    │  ← Fields
│ # protectedField: Type  │    - = private
│ + publicField: Type     │    # = protected
├─────────────────────────┤    + = public
│ + publicMethod(): void  │  ← Methods
│ - privateMethod(): Type │
│ # protectedMethod()     │
└─────────────────────────┘

Relationships:
  ──────────►   Association       (A uses B)
  ◇─────────►   Aggregation       (A has B; B can exist without A)
  ◆─────────►   Composition       (A owns B; B dies with A)
  ──────────▷   Inheritance       (A extends B)
  - - - - -▷   Realization       (A implements B interface)
  - - - - -►   Dependency         (A depends on B temporarily)

Multiplicity:
  1     exactly one
  *     zero or many
  1..*  one or many
  0..1  zero or one
```

### Example — Order System Class Diagram

```
«interface»                      «interface»
PaymentGateway                   NotificationService
─────────────                    ───────────────────
+ charge()                       + notify()
+ refund()
     ▲                                  ▲
     │ implements                       │ implements
     │                                  │
StripeGateway   RazorpayGateway    EmailService   SMSService


      User                   Order
──────────────         ──────────────────
- id: Long        1  * - id: Long
- name: String  ──────  - status: Status
- email: String         - createdAt: Date
                        ◆ items: List<OrderItem>   (composition)
                        ◆ payment: Payment
                        + place()
                        + cancel()
                        + getTotal(): double

  OrderItem                    Product
──────────────────         ─────────────────
- quantity: int    *──1   - id: Long
- unitPrice: double        - name: String
- product: Product         - price: double
                           - stock: int
```

---

## 6. Full Mini LLD — Parking Lot System

### Requirements
- Multiple floors, multiple spots per floor
- Spot sizes: SMALL (bikes), MEDIUM (cars), LARGE (trucks)
- On entry: issue a ticket
- On exit: calculate fee, free the spot
- Pricing: hourly, by vehicle type

### Step 1 — Entities Identified
`ParkingLot`, `Floor`, `ParkingSpot`, `Vehicle`, `Ticket`, `Payment`, `PricingStrategy`

### Step 2 — Class Diagram (text)

```
ParkingLot (Singleton)
  ◆── floors: List<Floor>
  ◆── pricingStrategy: PricingStrategy   (Strategy Pattern)
  + parkVehicle(Vehicle): Ticket
  + exitVehicle(Ticket): double

Floor
  ◆── spots: List<ParkingSpot>
  + getAvailableSpot(Vehicle): Optional<ParkingSpot>

ParkingSpot
  - spotId: String
  - size: SpotSize
  - status: SpotStatus
  - parkedVehicle: Vehicle
  + canFit(Vehicle): boolean
  + park(Vehicle): void
  + vacate(): void

Vehicle
  - licensePlate: String
  - type: VehicleType

Ticket
  - ticketId: String
  - vehicle: Vehicle
  - spot: ParkingSpot
  - entryTime: LocalDateTime
  + getDurationMinutes(): long

«interface»
PricingStrategy                    (Strategy Pattern)
  + calculate(minutes, type): double

HourlyPricing implements PricingStrategy
FlatRatePricing implements PricingStrategy
```

### Step 3 — Full Java Code

```java
// ─── Enums ─────────────────────────────────────────────────────

public enum VehicleType { BIKE, CAR, TRUCK }

public enum SpotSize    { SMALL, MEDIUM, LARGE }

public enum SpotStatus  { AVAILABLE, OCCUPIED }

// ─── Vehicle ───────────────────────────────────────────────────

public class Vehicle {
    private final String      licensePlate;
    private final VehicleType type;

    public Vehicle(String licensePlate, VehicleType type) {
        this.licensePlate = licensePlate;
        this.type         = type;
    }

    public String      getLicensePlate() { return licensePlate; }
    public VehicleType getType()         { return type;         }

    @Override
    public String toString() {
        return type + "[" + licensePlate + "]";
    }
}

// ─── ParkingSpot ───────────────────────────────────────────────

public class ParkingSpot {
    private final String  spotId;
    private final SpotSize size;
    private SpotStatus    status  = SpotStatus.AVAILABLE;
    private Vehicle       parkedVehicle;

    public ParkingSpot(String spotId, SpotSize size) {
        this.spotId = spotId;
        this.size   = size;
    }

    public boolean canFit(Vehicle v) {
        if (status == SpotStatus.OCCUPIED) return false;
        return switch (v.getType()) {
            case BIKE  -> size == SpotSize.SMALL  || size == SpotSize.MEDIUM;
            case CAR   -> size == SpotSize.MEDIUM || size == SpotSize.LARGE;
            case TRUCK -> size == SpotSize.LARGE;
        };
    }

    public void park(Vehicle v) {
        this.parkedVehicle = v;
        this.status        = SpotStatus.OCCUPIED;
    }

    public void vacate() {
        this.parkedVehicle = null;
        this.status        = SpotStatus.AVAILABLE;
    }

    public boolean isAvailable()   { return status == SpotStatus.AVAILABLE; }
    public String  getSpotId()     { return spotId;  }
    public SpotSize getSize()      { return size;    }
    public Vehicle getVehicle()    { return parkedVehicle; }
}

// ─── Ticket ────────────────────────────────────────────────────

public class Ticket {
    private final String        ticketId;
    private final Vehicle       vehicle;
    private final ParkingSpot   spot;
    private final LocalDateTime entryTime;

    public Ticket(Vehicle vehicle, ParkingSpot spot) {
        this.ticketId  = "TKT-" + System.currentTimeMillis();
        this.vehicle   = vehicle;
        this.spot      = spot;
        this.entryTime = LocalDateTime.now();
    }

    public long getDurationMinutes() {
        return ChronoUnit.MINUTES.between(entryTime, LocalDateTime.now());
    }

    public Vehicle     getVehicle()   { return vehicle;   }
    public ParkingSpot getSpot()      { return spot;      }
    public String      getTicketId()  { return ticketId;  }
    public LocalDateTime getEntryTime(){ return entryTime; }

    @Override
    public String toString() {
        return String.format("Ticket[%s | %s | Spot: %s | Entry: %s]",
                ticketId, vehicle, spot.getSpotId(), entryTime);
    }
}

// ─── Strategy Pattern: PricingStrategy ─────────────────────────

public interface PricingStrategy {
    double calculate(long durationMinutes, VehicleType type);
}

public class HourlyPricing implements PricingStrategy {
    @Override
    public double calculate(long minutes, VehicleType type) {
        double ratePerHour = switch (type) {
            case BIKE  -> 20.0;
            case CAR   -> 40.0;
            case TRUCK -> 80.0;
        };
        long   hours = (long) Math.ceil(minutes / 60.0);
        return Math.max(1, hours) * ratePerHour; // minimum 1 hour
    }
}

public class FlatRatePricing implements PricingStrategy {
    @Override
    public double calculate(long minutes, VehicleType type) {
        // Flat rate regardless of duration
        return switch (type) {
            case BIKE  -> 50.0;
            case CAR   -> 100.0;
            case TRUCK -> 200.0;
        };
    }
}

// ─── Floor ─────────────────────────────────────────────────────

public class Floor {
    private final int              floorNumber;
    private final List<ParkingSpot> spots;

    public Floor(int floorNumber, List<ParkingSpot> spots) {
        this.floorNumber = floorNumber;
        this.spots       = new ArrayList<>(spots);
    }

    public Optional<ParkingSpot> getAvailableSpot(Vehicle vehicle) {
        return spots.stream()
                    .filter(s -> s.canFit(vehicle))
                    .findFirst();
    }

    public long countAvailable() {
        return spots.stream().filter(ParkingSpot::isAvailable).count();
    }

    public int getFloorNumber() { return floorNumber; }
}

// ─── ParkingLot — Singleton + Strategy ─────────────────────────

public class ParkingLot {
    private static volatile ParkingLot instance;

    private final String            name;
    private final List<Floor>       floors;
    private PricingStrategy         pricingStrategy;
    private final Map<String, Ticket> activeTickets = new HashMap<>();

    private ParkingLot(String name, List<Floor> floors, PricingStrategy pricing) {
        this.name            = name;
        this.floors          = floors;
        this.pricingStrategy = pricing;
    }

    // Singleton — double-checked locking
    public static ParkingLot getInstance(String name,
                                         List<Floor> floors,
                                         PricingStrategy pricing) {
        if (instance == null) {
            synchronized (ParkingLot.class) {
                if (instance == null) {
                    instance = new ParkingLot(name, floors, pricing);
                }
            }
        }
        return instance;
    }

    public void setPricingStrategy(PricingStrategy strategy) {
        this.pricingStrategy = strategy;
    }

    // Park a vehicle — find first available fitting spot across all floors
    public Ticket parkVehicle(Vehicle vehicle) {
        for (Floor floor : floors) {
            Optional<ParkingSpot> spot = floor.getAvailableSpot(vehicle);
            if (spot.isPresent()) {
                spot.get().park(vehicle);
                Ticket ticket = new Ticket(vehicle, spot.get());
                activeTickets.put(ticket.getTicketId(), ticket);
                System.out.printf(
                    "✅ Parked %-18s → Floor %d | Spot %-8s | Ticket: %s%n",
                    vehicle, floor.getFloorNumber(),
                    spot.get().getSpotId(), ticket.getTicketId()
                );
                return ticket;
            }
        }
        throw new RuntimeException("❌ No available spot for: " + vehicle);
    }

    // Exit — calculate fee, free the spot
    public double exitVehicle(String ticketId) {
        Ticket ticket = activeTickets.remove(ticketId);
        if (ticket == null) throw new IllegalArgumentException("Invalid ticket: " + ticketId);

        long   duration = ticket.getDurationMinutes();
        double fee      = pricingStrategy.calculate(duration, ticket.getVehicle().getType());

        ticket.getSpot().vacate();

        System.out.printf(
            "🚗 Exit  %-18s | Duration: %d min | Fee: ₹%.2f%n",
            ticket.getVehicle(), duration, fee
        );
        return fee;
    }

    public void printStatus() {
        System.out.println("\n📍 " + name + " — Status:");
        for (Floor f : floors) {
            System.out.printf("   Floor %d: %d spots available%n",
                    f.getFloorNumber(), f.countAvailable());
        }
        System.out.println("   Active tickets: " + activeTickets.size());
    }
}

// ─── Main / Client ─────────────────────────────────────────────

public class Main {
    public static void main(String[] args) throws InterruptedException {

        // Build floors
        Floor groundFloor = new Floor(0, List.of(
            new ParkingSpot("G-S1", SpotSize.SMALL),
            new ParkingSpot("G-S2", SpotSize.SMALL),
            new ParkingSpot("G-M1", SpotSize.MEDIUM),
            new ParkingSpot("G-M2", SpotSize.MEDIUM),
            new ParkingSpot("G-L1", SpotSize.LARGE)
        ));

        Floor firstFloor = new Floor(1, List.of(
            new ParkingSpot("F1-S1", SpotSize.SMALL),
            new ParkingSpot("F1-M1", SpotSize.MEDIUM),
            new ParkingSpot("F1-L1", SpotSize.LARGE)
        ));

        // Create ParkingLot (Singleton) with Hourly Pricing (Strategy)
        ParkingLot lot = ParkingLot.getInstance(
            "TechPark Parking",
            List.of(groundFloor, firstFloor),
            new HourlyPricing()
        );

        lot.printStatus();

        System.out.println("\n=== Vehicles Arriving ===");
        Vehicle bike1  = new Vehicle("MH12-AB-1234", VehicleType.BIKE);
        Vehicle car1   = new Vehicle("MH14-CD-5678", VehicleType.CAR);
        Vehicle truck1 = new Vehicle("MH01-EF-9999", VehicleType.TRUCK);
        Vehicle car2   = new Vehicle("MH20-GH-4321", VehicleType.CAR);
        Vehicle bike2  = new Vehicle("MH11-IJ-0001", VehicleType.BIKE);

        Ticket t1 = lot.parkVehicle(bike1);
        Ticket t2 = lot.parkVehicle(car1);
        Ticket t3 = lot.parkVehicle(truck1);
        Ticket t4 = lot.parkVehicle(car2);
        Ticket t5 = lot.parkVehicle(bike2);

        lot.printStatus();

        System.out.println("\n=== Vehicles Exiting ===");
        lot.exitVehicle(t1.getTicketId());
        lot.exitVehicle(t2.getTicketId());
        lot.exitVehicle(t3.getTicketId());

        lot.printStatus();

        System.out.println("\n=== Switching to Flat Rate Pricing ===");
        lot.setPricingStrategy(new FlatRatePricing()); // Strategy swapped at runtime!
        lot.exitVehicle(t4.getTicketId());
        lot.exitVehicle(t5.getTicketId());
    }
}
```

### Output

```
📍 TechPark Parking — Status:
   Floor 0: 5 spots available
   Floor 1: 3 spots available
   Active tickets: 0

=== Vehicles Arriving ===
✅ Parked BIKE[MH12-AB-1234]   → Floor 0 | Spot G-S1    | Ticket: TKT-...
✅ Parked CAR[MH14-CD-5678]    → Floor 0 | Spot G-M1    | Ticket: TKT-...
✅ Parked TRUCK[MH01-EF-9999]  → Floor 0 | Spot G-L1    | Ticket: TKT-...
✅ Parked CAR[MH20-GH-4321]    → Floor 0 | Spot G-M2    | Ticket: TKT-...
✅ Parked BIKE[MH11-IJ-0001]   → Floor 0 | Spot G-S2    | Ticket: TKT-...

📍 TechPark Parking — Status:
   Floor 0: 0 spots available
   Floor 1: 3 spots available
   Active tickets: 5

=== Vehicles Exiting ===
🚗 Exit  BIKE[MH12-AB-1234]   | Duration: 0 min | Fee: ₹20.00
🚗 Exit  CAR[MH14-CD-5678]    | Duration: 0 min | Fee: ₹40.00
🚗 Exit  TRUCK[MH01-EF-9999]  | Duration: 0 min | Fee: ₹80.00

📍 TechPark Parking — Status:
   Floor 0: 3 spots available
   Floor 1: 3 spots available
   Active tickets: 2

=== Switching to Flat Rate Pricing ===
🚗 Exit  CAR[MH20-GH-4321]    | Duration: 0 min | Fee: ₹100.00
🚗 Exit  BIKE[MH11-IJ-0001]   | Duration: 0 min | Fee: ₹50.00
```

---

## 7. Common LLD Interview Problems

These are the most frequently asked problems in LLD interviews at top companies:

| Problem | Key Entities | Key Patterns |
|---|---|---|
| **Parking Lot** | `ParkingLot`, `Floor`, `Spot`, `Vehicle`, `Ticket` | Singleton, Strategy |
| **Elevator System** | `Elevator`, `Controller`, `Request`, `Direction` | State, Strategy |
| **Chess Game** | `Board`, `Piece`, `King`, `Queen`, `Move`, `Game` | Strategy, Template Method |
| **Library Management** | `Book`, `Member`, `Loan`, `Catalog`, `Fine` | Observer, Strategy |
| **Food Delivery App** | `Restaurant`, `Order`, `Agent`, `Customer`, `Menu` | Observer, State, Strategy |
| **Splitwise** | `User`, `Group`, `Expense`, `Split`, `Settlement` | Strategy |
| **Hotel Booking** | `Hotel`, `Room`, `Booking`, `Guest`, `Pricing` | Strategy, Observer |
| **ATM Machine** | `ATM`, `Card`, `Account`, `Transaction`, `Cash` | State, Chain of Responsibility |
| **Ride Sharing (Ola/Uber)** | `Rider`, `Driver`, `Trip`, `PricingEngine`, `Map` | Strategy, Observer, State |
| **Movie Ticket Booking** | `Movie`, `Screen`, `Seat`, `Booking`, `Show` | Strategy, Observer |
| **Snake & Ladder** | `Board`, `Player`, `Dice`, `Snake`, `Ladder`, `Cell` | Template Method |
| **Logger Framework** | `Logger`, `Appender`, `Filter`, `Level` | Chain of Responsibility, Singleton |
| **Vending Machine** | `VendingMachine`, `Slot`, `Item`, `Payment` | State, Strategy |
| **Cache (LRU/LFU)** | `Cache`, `CachePolicy`, `Node`, `EvictionStrategy` | Strategy, Decorator |

---

## 8. LLD vs Code — What to Produce

In an interview or design session, LLD produces:

```
✅ What LLD produces                   ❌ What LLD is NOT
────────────────────────               ─────────────────────────
Class diagrams                         Full working production code
Interface definitions                  Unit tests (though you define testability)
Method signatures with return types    Performance benchmarks
Enum definitions                       Infrastructure setup
Key design patterns identified         DevOps / deployment scripts
DB schema outline
REST API contracts
Core business logic skeleton (not full implementation)
```

---

## 9. Key Takeaway

```
HLD asks WHAT the system is.
LLD asks HOW each piece works.

LLD is the bridge between a system diagram on a whiteboard
and clean, production-quality Java code.

The three pillars of LLD:

  1. SOLID Principles  ─── govern every class and interface decision
  2. Design Patterns   ─── solve recurring structural and behavioral problems
  3. Good Judgment     ─── knowing when to apply them and when to keep it simple

Master these three, and you can design any system cleanly,
confidently, and in a way any developer can read and extend.
```

> **"Good LLD is not about adding patterns everywhere — it is about writing code that is easy to change, easy to test, and easy to understand."**
