# Singleton Pattern

## Category
**Creational Design Pattern**

---

## Intent
Ensure a class has **only one instance** and provide a global point of access to it.

---

## The Problem It Solves

Some resources must be shared system-wide and created only once:
- **Database connection pool** — You don't want 100 separate connections
- **Configuration manager** — One central config for the whole app
- **Logger** — All modules write to the same log
- **Thread pool** — One shared pool of threads
- **Cache** — One shared in-memory cache

If you just call `new DatabasePool()` everywhere, you get multiple instances — wasted resources, inconsistent state, potential conflicts.

---

## Key Requirements

1. **Private constructor** — Prevent direct instantiation with `new`
2. **Static instance** — The single instance stored as a class-level field
3. **Public static accessor** — `getInstance()` method returns the single instance

---

## Implementation Variants

### Variant 1: Eager Initialization (simplest, thread-safe)

```java
public class ConfigurationManager {
    // Created at class loading time — always ready, always thread-safe
    private static final ConfigurationManager INSTANCE = new ConfigurationManager();

    private Map<String, String> properties = new HashMap<>();

    private ConfigurationManager() {
        loadProperties(); // load once
    }

    public static ConfigurationManager getInstance() {
        return INSTANCE; // already created
    }

    private void loadProperties() {
        properties.put("db.host",     "localhost");
        properties.put("db.port",     "5432");
        properties.put("db.name",     "myapp");
        properties.put("app.version", "2.1.0");
        System.out.println("[Config] Properties loaded.");
    }

    public String get(String key) {
        return properties.getOrDefault(key, "NOT_FOUND");
    }

    public String get(String key, String defaultValue) {
        return properties.getOrDefault(key, defaultValue);
    }

    public void set(String key, String value) {
        properties.put(key, value);
    }

    @Override
    public String toString() {
        return "ConfigurationManager@" + Integer.toHexString(hashCode());
    }
}
```

**Drawback**: Instance created even if never used. Acceptable in most cases.

---

### Variant 2: Lazy Initialization (NOT thread-safe — don't use in production)

```java
public class NaiveSingleton {
    private static NaiveSingleton instance;

    private NaiveSingleton() {}

    public static NaiveSingleton getInstance() {
        if (instance == null) {          // ← RACE CONDITION HERE
            instance = new NaiveSingleton();
        }
        return instance;
    }
}
// Two threads can both pass the null check simultaneously → two instances created!
```

---

### Variant 3: Thread-Safe with `synchronized` (simple but slow)

```java
public class ThreadSafeSingleton {
    private static ThreadSafeSingleton instance;

    private ThreadSafeSingleton() {}

    public static synchronized ThreadSafeSingleton getInstance() {
        if (instance == null) {
            instance = new ThreadSafeSingleton();
        }
        return instance;
    }
}
// Works! But synchronized on every call — unnecessary after first creation.
```

---

### Variant 4: Double-Checked Locking (⭐ Recommended for lazy + thread-safe)

```java
public class DatabaseConnectionPool {
    // volatile ensures visibility across threads
    private static volatile DatabaseConnectionPool instance;

    private List<Connection> pool = new ArrayList<>();
    private static final int POOL_SIZE = 10;

    private DatabaseConnectionPool() {
        initializePool();
    }

    public static DatabaseConnectionPool getInstance() {
        if (instance == null) {                          // Check 1 (no lock — fast)
            synchronized (DatabaseConnectionPool.class) {
                if (instance == null) {                  // Check 2 (with lock — safe)
                    instance = new DatabaseConnectionPool();
                }
            }
        }
        return instance;
    }

    private void initializePool() {
        System.out.println("[Pool] Initializing " + POOL_SIZE + " connections...");
        for (int i = 0; i < POOL_SIZE; i++) {
            pool.add(new MockConnection("conn-" + i));
        }
    }

    public synchronized Connection getConnection() {
        if (pool.isEmpty()) {
            throw new RuntimeException("No connections available!");
        }
        Connection conn = pool.remove(0);
        System.out.println("[Pool] Acquired: " + conn);
        return conn;
    }

    public synchronized void releaseConnection(Connection conn) {
        pool.add(conn);
        System.out.println("[Pool] Released: " + conn);
    }

    public int available() { return pool.size(); }

    // Simple mock connection for demo
    static class MockConnection implements Connection {
        private String id;
        MockConnection(String id) { this.id = id; }
        @Override public String toString() { return id; }
        // ... implement Connection methods
    }
}
```

---

### Variant 5: Initialization-on-Demand Holder Idiom (⭐⭐ Best practice)

```java
public class Logger {
    private Logger() {
        System.out.println("[Logger] Instance created.");
    }

    // Inner class loaded only when getInstance() is first called
    // JVM guarantees class loading is thread-safe!
    private static class SingletonHolder {
        private static final Logger INSTANCE = new Logger();
    }

    public static Logger getInstance() {
        return SingletonHolder.INSTANCE;
    }

    private List<String> logs = new ArrayList<>();

    public void log(String level, String message) {
        String entry = String.format("[%s] %s - %s", level, 
                java.time.LocalTime.now(), message);
        logs.add(entry);
        System.out.println(entry);
    }

    public void info(String msg)  { log("INFO ", msg); }
    public void warn(String msg)  { log("WARN ", msg); }
    public void error(String msg) { log("ERROR", msg); }

    public List<String> getLogs() { return Collections.unmodifiableList(logs); }
}
```

**Why this is the best**: Lazy (created on first use), thread-safe (JVM guarantees), no locking overhead, simple code.

---

### Variant 6: Enum Singleton (⭐⭐⭐ Most robust)

```java
public enum AppConfig {
    INSTANCE;

    private final Map<String, String> config = new HashMap<>();

    AppConfig() {
        // Load configuration
        config.put("api.url",     "https://api.example.com");
        config.put("api.timeout", "30");
        config.put("app.env",     "production");
    }

    public String get(String key) {
        return config.getOrDefault(key, "");
    }

    public void set(String key, String value) {
        config.put(key, value);
    }
}

// Usage
String url = AppConfig.INSTANCE.get("api.url");
AppConfig.INSTANCE.set("debug", "true");
```

**Why Enum is recommended by Joshua Bloch (Effective Java)**:
- Thread-safe by JVM
- Serialization-safe (no duplicate on deserialization)
- Reflection-safe (can't bypass with `newInstance()`)
- Concise

---

## Complete Example — Application Logger

```java
// Using Holder Idiom
public class ApplicationLogger {
    private final List<LogEntry> history = new ArrayList<>();

    private ApplicationLogger() {}

    private static class Holder {
        static final ApplicationLogger INSTANCE = new ApplicationLogger();
    }

    public static ApplicationLogger getInstance() {
        return Holder.INSTANCE;
    }

    public synchronized void log(String component, String level, String message) {
        LogEntry entry = new LogEntry(component, level, message);
        history.add(entry);
        System.out.println(entry);
    }

    public void info(String component, String message) {
        log(component, "INFO ", message);
    }

    public void error(String component, String message) {
        log(component, "ERROR", message);
    }

    public List<LogEntry> getHistory() {
        return Collections.unmodifiableList(history);
    }

    record LogEntry(String component, String level, String message) {
        @Override
        public String toString() {
            return String.format("[%s][%s] %s: %s",
                    java.time.LocalTime.now(), level, component, message);
        }
    }
}

// Used from anywhere in the app
public class OrderService {
    private static final ApplicationLogger log = ApplicationLogger.getInstance();

    public void placeOrder(String orderId) {
        log.info("OrderService", "Placing order: " + orderId);
        // business logic...
        log.info("OrderService", "Order placed: " + orderId);
    }
}

public class PaymentService {
    private static final ApplicationLogger log = ApplicationLogger.getInstance();

    public void processPayment(String txnId) {
        log.info("PaymentService", "Processing: " + txnId);
        // payment logic...
    }
}

// Main
public class Main {
    public static void main(String[] args) {
        // Both services use THE SAME logger instance
        new OrderService().placeOrder("ORD-001");
        new PaymentService().processPayment("TXN-789");

        // Verify they're the same instance
        ApplicationLogger l1 = ApplicationLogger.getInstance();
        ApplicationLogger l2 = ApplicationLogger.getInstance();
        System.out.println("Same instance? " + (l1 == l2)); // true

        System.out.println("Total log entries: " + l1.getHistory().size());
    }
}
```

---

## Thread Safety Comparison

| Implementation | Lazy? | Thread-Safe? | Performance | Recommended? |
|---|---|---|---|---|
| Eager | ❌ | ✅ | Fast | ✅ Simple cases |
| Synchronized | ✅ | ✅ | Slow | ❌ |
| Double-Checked | ✅ | ✅ | Fast | ✅ |
| Holder Idiom | ✅ | ✅ | Fast | ✅✅ |
| Enum | ❌ | ✅ | Fast | ✅✅✅ |

---

## Singleton Anti-Patterns and Pitfalls

```java
// ❌ Problem 1: Reflection can break Singleton
Constructor<MySingleton> c = MySingleton.class.getDeclaredConstructor();
c.setAccessible(true);
MySingleton s1 = c.newInstance(); // Creates second instance!

// Fix: throw in constructor if already created
private MySingleton() {
    if (Holder.INSTANCE != null) {
        throw new IllegalStateException("Use getInstance()");
    }
}

// ❌ Problem 2: Serialization creates new instance
// Fix: implement readResolve()
protected Object readResolve() {
    return getInstance();
}

// ❌ Problem 3: Singleton + global mutable state = testing nightmare
// Fix: use Dependency Injection — inject the singleton rather than accessing globally
```

---

## Pros and Cons

### ✅ Advantages
- **Controlled access** — Only one instance exists
- **Global access** — No need to pass instance around
- **Resource efficiency** — Single shared resource
- **Lazy init** — (with Holder/enum) created only when needed

### ❌ Disadvantages
- **Global state** — Hard to test; tests may affect each other
- **Hidden dependencies** — Classes secretly use a global singleton
- **Difficult to mock** — Unit testing requires special handling
- **Concurrency issues** — If not implemented correctly

---

## When to Use

✔ Database connection pools  
✔ Application-wide configuration  
✔ Logging services  
✔ Thread pools  
✔ Cache services  
✔ Device managers (e.g., printer spooler)  

---

## Key Takeaway

> **"One ring to rule them all."**  
> Singleton guarantees exactly one instance of a class — but use it sparingly. Over-reliance on Singletons creates hidden global state that makes code hard to test and reason about. Prefer Dependency Injection with a singleton-scoped component where possible.
