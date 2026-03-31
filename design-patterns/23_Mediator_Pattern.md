# Mediator Pattern

## Category
**Behavioral Design Pattern**

---

## Intent
Define an object that **encapsulates how a set of objects interact**. Mediator promotes loose coupling by keeping objects from referring to each other explicitly, and lets you vary their interaction independently.

Also known as: **Controller**, **Hub**

---

## The Problem It Solves

In an airport, aircraft communicate with **each other** to avoid collisions. With 50 planes, that's 50×49 = 2,450 communication channels.

Instead, all planes communicate with the **control tower** (mediator). Each plane only needs to know one entity. The tower coordinates everything.

Without Mediator (spaghetti):
```
ComponentA ←→ ComponentB
     ↑↘         ↙↓
ComponentD ←→ ComponentC
```

With Mediator (star topology):
```
A → Mediator ← B
        ↕
D → Mediator ← C
```

---

## Structure

```
Mediator (interface)
  └── notify(sender, event)

ConcreteMediator
  ├── componentA
  ├── componentB
  └── notify(sender, event) → coordinates components

Component (abstract)
  └── mediator: Mediator
  └── send(event)  { mediator.notify(this, event) }
```

---

## Java Example — Air Traffic Control

### Step 1: Mediator Interface

```java
public interface AirTrafficControl {
    void registerAircraft(Aircraft aircraft);
    void notify(Aircraft sender, String event, String data);
    void broadcast(String message);
}
```

### Step 2: Abstract Component

```java
public abstract class Aircraft {
    protected String      callSign;
    protected AirTrafficControl atc; // reference to mediator

    public Aircraft(String callSign, AirTrafficControl atc) {
        this.callSign = callSign;
        this.atc      = atc;
        atc.registerAircraft(this);
    }

    // Send event to mediator
    protected void send(String event, String data) {
        System.out.printf("[%s] Sending to ATC: %s — %s%n", callSign, event, data);
        atc.notify(this, event, data);
    }

    // Receive message from mediator
    public abstract void receive(String from, String message);

    public String getCallSign() { return callSign; }
}
```

### Step 3: Concrete Components

```java
public class CommercialAircraft extends Aircraft {
    private String status = "CRUISING";

    public CommercialAircraft(String callSign, AirTrafficControl atc) {
        super(callSign, atc);
    }

    public void requestLanding() {
        status = "LANDING_REQUEST";
        send("LANDING_REQUEST", "Ready for approach");
    }

    public void requestTakeoff() {
        status = "TAKEOFF_REQUEST";
        send("TAKEOFF_REQUEST", "Ready for departure");
    }

    public void reportEmergency() {
        send("EMERGENCY", "Mayday! Engine failure!");
    }

    @Override
    public void receive(String from, String message) {
        System.out.printf("  [%s] ← ATC (%s): %s%n", callSign, from, message);
    }
}

public class PrivateJet extends Aircraft {
    public PrivateJet(String callSign, AirTrafficControl atc) {
        super(callSign, atc);
    }

    public void requestHolding() {
        send("HOLDING_REQUEST", "Requesting holding pattern");
    }

    @Override
    public void receive(String from, String message) {
        System.out.printf("  [%s] ← ATC: %s%n", callSign, message);
    }
}
```

### Step 4: Concrete Mediator — ATC Tower

```java
public class ControlTower implements AirTrafficControl {
    private List<Aircraft> aircraft = new ArrayList<>();
    private Queue<Aircraft> landingQueue = new LinkedList<>();
    private boolean runwayClear = true;

    @Override
    public void registerAircraft(Aircraft aircraft) {
        this.aircraft.add(aircraft);
        System.out.println("[ATC] Registered: " + aircraft.getCallSign());
    }

    @Override
    public void notify(Aircraft sender, String event, String data) {
        System.out.println("[ATC Tower] Processing from " + sender.getCallSign()
                + ": " + event);

        switch (event) {
            case "LANDING_REQUEST" -> handleLandingRequest(sender);
            case "TAKEOFF_REQUEST" -> handleTakeoffRequest(sender);
            case "EMERGENCY"       -> handleEmergency(sender);
            case "HOLDING_REQUEST" -> handleHolding(sender);
        }
    }

    private void handleLandingRequest(Aircraft aircraft) {
        if (runwayClear) {
            runwayClear = false;
            aircraft.receive("TOWER", "Cleared to land. Runway 28L available.");
            // Inform others the runway is in use
            notifyOthers(aircraft, "RUNWAY OCCUPIED — " + aircraft.getCallSign() + " landing");
        } else {
            landingQueue.add(aircraft);
            aircraft.receive("TOWER", "Hold your position. Runway busy. Queue position: " + landingQueue.size());
        }
    }

    private void handleTakeoffRequest(Aircraft aircraft) {
        if (runwayClear) {
            runwayClear = false;
            aircraft.receive("TOWER", "Cleared for takeoff. Wind 270 at 12 knots.");
        } else {
            aircraft.receive("TOWER", "Hold short of runway. Traffic on approach.");
        }
    }

    private void handleEmergency(Aircraft aircraft) {
        System.out.println("[ATC] 🚨 EMERGENCY DECLARED by " + aircraft.getCallSign());
        broadcast("MAYDAY from " + aircraft.getCallSign() + " — all aircraft clear the area!");
        aircraft.receive("TOWER", "Emergency services alerted. Runway cleared. Land immediately!");
        runwayClear = false;
    }

    private void handleHolding(Aircraft aircraft) {
        aircraft.receive("TOWER", "Assigned holding pattern at FL150. Expect approach in 10 min.");
    }

    public void clearRunway() {
        runwayClear = true;
        System.out.println("[ATC] Runway cleared.");
        if (!landingQueue.isEmpty()) {
            Aircraft next = landingQueue.poll();
            System.out.println("[ATC] Next in queue: " + next.getCallSign());
            handleLandingRequest(next);
        }
    }

    @Override
    public void broadcast(String message) {
        System.out.println("[ATC BROADCAST] " + message);
        aircraft.forEach(a -> a.receive("BROADCAST", message));
    }

    private void notifyOthers(Aircraft sender, String message) {
        aircraft.stream()
                .filter(a -> a != sender)
                .forEach(a -> a.receive("INFO", message));
    }
}
```

### Step 5: Client Code

```java
public class Airport {
    public static void main(String[] args) {
        ControlTower atc = new ControlTower();

        CommercialAircraft ai101  = new CommercialAircraft("AI-101",  atc);
        CommercialAircraft ba202  = new CommercialAircraft("BA-202",  atc);
        CommercialAircraft em303  = new CommercialAircraft("EM-303",  atc);
        PrivateJet        pj007  = new PrivateJet("PJ-007", atc);

        System.out.println("\n--- AI-101 requests landing ---");
        ai101.requestLanding(); // gets clearance

        System.out.println("\n--- BA-202 also requests landing (runway busy) ---");
        ba202.requestLanding(); // put in queue

        System.out.println("\n--- PJ-007 requests holding ---");
        pj007.requestHolding();

        System.out.println("\n--- EM-303 declares EMERGENCY ---");
        em303.reportEmergency();

        System.out.println("\n--- AI-101 has landed, clear runway ---");
        atc.clearRunway(); // BA-202 from queue should get clearance
    }
}
```

### Output

```
[ATC] Registered: AI-101
[ATC] Registered: BA-202
[ATC] Registered: EM-303
[ATC] Registered: PJ-007

--- AI-101 requests landing ---
[AI-101] Sending to ATC: LANDING_REQUEST — Ready for approach
[ATC Tower] Processing from AI-101: LANDING_REQUEST
  [AI-101] ← ATC (TOWER): Cleared to land. Runway 28L available.
  [BA-202] ← ATC: INFO — RUNWAY OCCUPIED — AI-101 landing
  [EM-303] ← ATC: INFO — RUNWAY OCCUPIED — AI-101 landing
  [PJ-007] ← ATC: INFO — RUNWAY OCCUPIED — AI-101 landing

--- BA-202 also requests landing (runway busy) ---
[BA-202] Sending to ATC: LANDING_REQUEST — Ready for approach
[ATC Tower] Processing from BA-202: LANDING_REQUEST
  [BA-202] ← ATC (TOWER): Hold your position. Runway busy. Queue position: 1

--- PJ-007 requests holding ---
[PJ-007] Sending to ATC: HOLDING_REQUEST — Requesting holding pattern
[ATC Tower] Processing from PJ-007: HOLDING_REQUEST
  [PJ-007] ← ATC: Assigned holding pattern at FL150. Expect approach in 10 min.

--- EM-303 declares EMERGENCY ---
[ATC] 🚨 EMERGENCY DECLARED by EM-303
[ATC BROADCAST] MAYDAY from EM-303 — all aircraft clear the area!
  [AI-101] ← ATC: BROADCAST — MAYDAY from EM-303...
  ...
  [EM-303] ← ATC (TOWER): Emergency services alerted. Land immediately!

--- AI-101 has landed, clear runway ---
[ATC] Runway cleared.
[ATC] Next in queue: BA-202
  [BA-202] ← ATC (TOWER): Cleared to land. Runway 28L available.
```

---

## Java Example 2 — Chat Room Mediator

```java
public interface ChatMediator {
    void sendMessage(String message, User sender);
    void addUser(User user);
}

public abstract class User {
    protected ChatMediator mediator;
    protected String name;

    public User(ChatMediator mediator, String name) {
        this.mediator = mediator;
        this.name     = name;
    }

    public abstract void send(String message);
    public abstract void receive(String from, String message);
    public String getName() { return name; }
}

public class ChatRoom implements ChatMediator {
    private List<User> users = new ArrayList<>();

    @Override
    public void addUser(User user) { users.add(user); }

    @Override
    public void sendMessage(String message, User sender) {
        users.stream()
             .filter(u -> u != sender)
             .forEach(u -> u.receive(sender.getName(), message));
    }
}

public class ChatUser extends User {
    public ChatUser(ChatMediator mediator, String name) {
        super(mediator, name);
        mediator.addUser(this);
    }

    @Override
    public void send(String message) {
        System.out.println(name + " → All: " + message);
        mediator.sendMessage(message, this);
    }

    @Override
    public void receive(String from, String message) {
        System.out.println("  " + name + " received from " + from + ": " + message);
    }
}

// Usage
ChatRoom room = new ChatRoom();
ChatUser rahul = new ChatUser(room, "Rahul");
ChatUser priya = new ChatUser(room, "Priya");
ChatUser amit  = new ChatUser(room, "Amit");

rahul.send("Hello everyone!");
// Priya and Amit receive — Rahul doesn't receive his own message
```

---

## Real-World Java Examples

| Usage | Mediator |
|---|---|
| Java's `EventBus` (Guava) | Events mediated through central bus |
| MVC Controller | Mediates between Model and View |
| Spring's `ApplicationEventPublisher` | Central event mediator |
| Java Swing `JFrame` | Mediates between UI components |
| Message Broker (Kafka, RabbitMQ) | Mediates between producers and consumers |

---

## Mediator vs Observer

| Aspect | Mediator | Observer |
|---|---|---|
| **Communication** | Bidirectional, centralized | One-to-many, broadcast |
| **Coupling** | Components coupled to mediator only | Subject unaware of observer types |
| **Control** | Mediator can add logic/conditions | Observer reacts to events passively |
| **Use case** | Complex interaction orchestration | Simple event notification |

---

## Pros and Cons

### ✅ Advantages
- **Loose coupling** — Components don't reference each other directly
- **Centralized control** — All interaction logic in one place
- **Easy to change interactions** — Modify mediator without touching components
- **Reusable components** — Components work with any mediator

### ❌ Disadvantages
- **God object risk** — Mediator can become too complex and monolithic
- **Single point of failure** — If mediator has a bug, everything breaks
- **Harder to understand** — Indirect communication is less obvious

---

## When to Use

✔ When many objects communicate in complex ways, creating tight coupling  
✔ When you can't reuse a component because it's too dependent on others  
✔ When you want to customize behavior distributed across several classes  
✔ Chat systems, air traffic control, GUI component coordination, event buses  

---

## Key Takeaway

> **"Talk to the control tower, not to each other."**  
> Mediator replaces a web of direct object-to-object references with a central hub — reducing M×N connections to M+N connections.
