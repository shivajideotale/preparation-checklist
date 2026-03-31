# Chain of Responsibility Pattern

## Category
**Behavioral Design Pattern**

---

## Intent
Avoid coupling the sender of a request to its receiver by giving more than one object a chance to handle the request. Chain the receiving objects and pass the request along the chain until an object handles it.

---

## The Problem It Solves

You're building a **customer support system**. A support ticket can be resolved at different levels:
- **Level 1** — Basic FAQ bot
- **Level 2** — Junior support agent
- **Level 3** — Senior agent
- **Level 4** — Manager
- **Level 5** — CEO (escalation)

Without Chain of Responsibility, the client must know which handler to call:
```java
if (ticket.getPriority() == LOW) { faqBot.handle(ticket); }
else if (ticket.getPriority() == MEDIUM) { junior.handle(ticket); }
// ...tightly coupled, hard to extend
```

Chain of Responsibility lines up the handlers. The request travels down the chain until someone handles it — or it falls off the end.

---

## Structure

```
Client → Handler (abstract)
              ├── setNext(Handler)
              └── handle(request)
                      │
                      ├── ConcreteHandler1.handle()
                      │         │ (if can't handle → pass to next)
                      ├── ConcreteHandler2.handle()
                      │         │
                      └── ConcreteHandler3.handle()
```

---

## Java Example — Support Ticket System

### Step 1: Define the Request

```java
public class SupportTicket {
    public enum Priority { LOW, MEDIUM, HIGH, CRITICAL }

    private String id;
    private String description;
    private Priority priority;

    public SupportTicket(String id, String description, Priority priority) {
        this.id = id;
        this.description = description;
        this.priority = priority;
    }

    public String getId()           { return id; }
    public String getDescription()  { return description; }
    public Priority getPriority()   { return priority; }

    @Override
    public String toString() {
        return String.format("Ticket[%s | %s | %s]", id, priority, description);
    }
}
```

### Step 2: Abstract Handler

```java
public abstract class SupportHandler {
    private SupportHandler nextHandler;

    // Fluent API — return next for chaining: A.setNext(B).setNext(C)
    public SupportHandler setNext(SupportHandler next) {
        this.nextHandler = next;
        return next;
    }

    public final void handle(SupportTicket ticket) {
        if (canHandle(ticket)) {
            process(ticket);
        } else if (nextHandler != null) {
            System.out.println(getClass().getSimpleName() +
                    " cannot handle " + ticket.getId() + " → escalating...");
            nextHandler.handle(ticket);
        } else {
            System.out.println("❌ No handler found for " + ticket);
        }
    }

    protected abstract boolean canHandle(SupportTicket ticket);
    protected abstract void process(SupportTicket ticket);
}
```

### Step 3: Concrete Handlers

```java
public class FAQBotHandler extends SupportHandler {

    @Override
    protected boolean canHandle(SupportTicket ticket) {
        return ticket.getPriority() == SupportTicket.Priority.LOW;
    }

    @Override
    protected void process(SupportTicket ticket) {
        System.out.printf("[FAQ Bot] ✅ Resolved %s: Sent auto-reply for '%s'%n",
                ticket.getId(), ticket.getDescription());
    }
}

public class JuniorAgentHandler extends SupportHandler {

    @Override
    protected boolean canHandle(SupportTicket ticket) {
        return ticket.getPriority() == SupportTicket.Priority.MEDIUM;
    }

    @Override
    protected void process(SupportTicket ticket) {
        System.out.printf("[Junior Agent] ✅ Resolved %s: Handled '%s' via standard process%n",
                ticket.getId(), ticket.getDescription());
    }
}

public class SeniorAgentHandler extends SupportHandler {

    @Override
    protected boolean canHandle(SupportTicket ticket) {
        return ticket.getPriority() == SupportTicket.Priority.HIGH;
    }

    @Override
    protected void process(SupportTicket ticket) {
        System.out.printf("[Senior Agent] ✅ Resolved %s: Deep-dived into '%s'%n",
                ticket.getId(), ticket.getDescription());
    }
}

public class ManagerHandler extends SupportHandler {

    @Override
    protected boolean canHandle(SupportTicket ticket) {
        return ticket.getPriority() == SupportTicket.Priority.CRITICAL;
    }

    @Override
    protected void process(SupportTicket ticket) {
        System.out.printf("[Manager] ✅ Resolved %s: Crisis-managed '%s'%n",
                ticket.getId(), ticket.getDescription());
    }
}
```

### Step 4: Build the Chain and Run

```java
public class SupportDesk {
    public static void main(String[] args) {
        // Build the chain
        FAQBotHandler faqBot = new FAQBotHandler();
        JuniorAgentHandler junior = new JuniorAgentHandler();
        SeniorAgentHandler senior = new SeniorAgentHandler();
        ManagerHandler manager = new ManagerHandler();

        // Chain: FAQ → Junior → Senior → Manager
        faqBot.setNext(junior)
              .setNext(senior)
              .setNext(manager);

        // Test tickets
        List<SupportTicket> tickets = List.of(
            new SupportTicket("T001", "How do I reset my password?",    SupportTicket.Priority.LOW),
            new SupportTicket("T002", "Payment not reflected",          SupportTicket.Priority.MEDIUM),
            new SupportTicket("T003", "Data breach suspected",          SupportTicket.Priority.HIGH),
            new SupportTicket("T004", "System down — revenue impacted", SupportTicket.Priority.CRITICAL)
        );

        for (SupportTicket ticket : tickets) {
            System.out.println("\nProcessing: " + ticket);
            faqBot.handle(ticket); // always starts from top of chain
        }
    }
}
```

### Output

```
Processing: Ticket[T001 | LOW | How do I reset my password?]
[FAQ Bot] ✅ Resolved T001: Sent auto-reply for 'How do I reset my password?'

Processing: Ticket[T002 | MEDIUM | Payment not reflected]
FAQBotHandler cannot handle T002 → escalating...
[Junior Agent] ✅ Resolved T002: Handled 'Payment not reflected' via standard process

Processing: Ticket[T003 | HIGH | Data breach suspected]
FAQBotHandler cannot handle T003 → escalating...
JuniorAgentHandler cannot handle T003 → escalating...
[Senior Agent] ✅ Resolved T003: Deep-dived into 'Data breach suspected'

Processing: Ticket[T004 | CRITICAL | System down — revenue impacted]
FAQBotHandler cannot handle T004 → escalating...
JuniorAgentHandler cannot handle T004 → escalating...
SeniorAgentHandler cannot handle T004 → escalating...
[Manager] ✅ Resolved T004: Crisis-managed 'System down — revenue impacted'
```

---

## Variant — Middleware Pipeline (All handlers run)

In some implementations, every handler processes the request (like HTTP middleware). A handler can stop the chain or let it continue.

```java
public abstract class MiddlewareHandler {
    private MiddlewareHandler next;

    public MiddlewareHandler linkWith(MiddlewareHandler next) {
        this.next = next;
        return next;
    }

    // Returns true to continue chain, false to stop
    public abstract boolean check(String username, String password);

    protected boolean checkNext(String username, String password) {
        if (next == null) return true; // End of chain — allow
        return next.check(username, password);
    }
}

public class AuthHandler extends MiddlewareHandler {
    private Map<String, String> users = Map.of("admin", "secret");

    @Override
    public boolean check(String username, String password) {
        if (!users.containsKey(username) || !users.get(username).equals(password)) {
            System.out.println("AuthHandler: Invalid credentials.");
            return false; // Stop chain
        }
        System.out.println("AuthHandler: Authenticated.");
        return checkNext(username, password); // Continue chain
    }
}

public class RateLimitHandler extends MiddlewareHandler {
    private Map<String, Integer> attempts = new HashMap<>();
    private static final int MAX = 3;

    @Override
    public boolean check(String username, String password) {
        int count = attempts.getOrDefault(username, 0) + 1;
        attempts.put(username, count);

        if (count > MAX) {
            System.out.println("RateLimitHandler: Too many attempts. Blocked.");
            return false;
        }
        System.out.println("RateLimitHandler: Attempt " + count + "/" + MAX);
        return checkNext(username, password);
    }
}

public class LoggingHandler extends MiddlewareHandler {
    @Override
    public boolean check(String username, String password) {
        System.out.println("LoggingHandler: Login attempt for " + username);
        return checkNext(username, password);
    }
}

// Usage
LoggingHandler logger = new LoggingHandler();
RateLimitHandler rateLimit = new RateLimitHandler();
AuthHandler auth = new AuthHandler();
logger.linkWith(rateLimit).linkWith(auth); // ... → rate → auth
logger.check("admin", "secret"); // passes
logger.check("admin", "wrong");  // fails at auth
```

---

## Real-World Java Examples

| Framework | Usage |
|---|---|
| **Java Servlet Filters** | `Filter.doFilter()` chains request through middleware |
| **Spring Security** | `SecurityFilterChain` — authentication, CSRF, CORS filters |
| **Apache Log4j** | Logger chain — appenders form a chain |
| **JavaFX Event Handling** | Events bubble through component hierarchy |
| **OkHttp Interceptors** | HTTP request/response interceptor chain |

```java
// Spring Security is essentially Chain of Responsibility
http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
    .addFilterAfter(loggingFilter, JwtFilter.class);
// Request passes through: JwtFilter → UsernamePasswordFilter → LoggingFilter
```

---

## Pros and Cons

### ✅ Advantages
- **Loose coupling** — Sender doesn't know which handler will process the request
- **Flexible chain** — Add/remove/reorder handlers without changing client code
- **Single Responsibility** — Each handler focuses on one concern
- **Open/Closed** — New handlers without modifying existing ones

### ❌ Disadvantages
- **No guarantee** — Request may fall through the chain unhandled
- **Debugging difficulty** — Hard to trace which handler processed a request
- **Performance** — Long chains cause latency (every handler gets the request)
- **Unintentional skips** — A misconfigured chain can drop requests silently

---

## When to Use

✔ When more than one object may handle a request, and the handler isn't known a priori  
✔ When you want to issue a request to one of several objects without specifying the receiver explicitly  
✔ When the set of objects that can handle a request should be specified dynamically  
✔ Building pipelines: HTTP middleware, event processing, logging frameworks  

---

## Key Takeaway

> **"Don't call me, I'll call the next one."**  
> Chain of Responsibility decouples the request sender from the specific handler by passing the request down a chain until something handles it — or it reaches the end.
