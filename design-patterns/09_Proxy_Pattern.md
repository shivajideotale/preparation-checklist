# Proxy Pattern

## Category
**Structural Design Pattern**

---

## Intent
Provide a surrogate or placeholder for another object to control access to it.

---

## The Problem It Solves

You have an expensive object (like a large image, a remote service, or a sensitive resource). You want to:
- **Delay** its creation until it's actually needed (lazy loading)
- **Control** who can access it (security)
- **Cache** its results (performance)
- **Log** every access to it (auditing)

Without Proxy, the client directly instantiates and accesses the real object, giving no opportunity to intercept or control that access.

---

## Types of Proxy

| Type | Purpose |
|---|---|
| **Virtual Proxy** | Lazy loading — create expensive object only when needed |
| **Protection Proxy** | Access control — check permissions before forwarding |
| **Remote Proxy** | Local representative of a remote object (RMI, gRPC) |
| **Caching Proxy** | Cache results of expensive operations |
| **Logging Proxy** | Log all operations on the subject |
| **Smart Reference** | Additional actions on access (ref counting, locking) |

---

## Structure

```
Client → Subject (interface)
              │
         ┌────┴────┐
      Proxy      RealSubject
    ├── ref to RealSubject
    └── request() → delegates → RealSubject.request()
```

Both `Proxy` and `RealSubject` implement `Subject` — the client can't tell the difference.

---

## Java Example 1 — Virtual Proxy (Lazy Loading)

### Problem: Loading a large image is expensive. Only load when `display()` is called.

```java
// Subject Interface
public interface Image {
    void display();
    String getFileName();
}

// Real Subject — expensive to create
public class RealImage implements Image {
    private String fileName;
    private byte[] imageData;

    public RealImage(String fileName) {
        this.fileName = fileName;
        loadFromDisk(); // expensive!
    }

    private void loadFromDisk() {
        System.out.println("[RealImage] Loading " + fileName + " from disk... (slow operation)");
        // Simulate expensive I/O
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        imageData = new byte[1024 * 1024]; // simulate 1MB image
        System.out.println("[RealImage] Loaded " + fileName);
    }

    @Override
    public void display() {
        System.out.println("[RealImage] Displaying " + fileName);
    }

    @Override
    public String getFileName() { return fileName; }
}

// Virtual Proxy — defers loading until display() is called
public class ImageProxy implements Image {
    private String fileName;
    private RealImage realImage; // null until needed

    public ImageProxy(String fileName) {
        this.fileName = fileName;
        // NO loading here!
    }

    @Override
    public void display() {
        if (realImage == null) {
            System.out.println("[Proxy] First access — creating RealImage...");
            realImage = new RealImage(fileName); // lazy initialization
        }
        realImage.display();
    }

    @Override
    public String getFileName() { return fileName; }
}

// Client
public class ImageViewer {
    public static void main(String[] args) {
        System.out.println("=== Creating image objects (no loading yet) ===");
        Image img1 = new ImageProxy("photo1.jpg");
        Image img2 = new ImageProxy("photo2.jpg");
        Image img3 = new ImageProxy("photo3.jpg");

        System.out.println("\n=== Only displaying img1 ===");
        img1.display(); // loads now

        System.out.println("\n=== Displaying img1 again ===");
        img1.display(); // already loaded — no reload!

        System.out.println("\n=== img2 and img3 were never used — never loaded ===");
        // img2 and img3 never got loaded — saved memory and time!
    }
}
```

### Output

```
=== Creating image objects (no loading yet) ===

=== Only displaying img1 ===
[Proxy] First access — creating RealImage...
[RealImage] Loading photo1.jpg from disk... (slow operation)
[RealImage] Loaded photo1.jpg
[RealImage] Displaying photo1.jpg

=== Displaying img1 again ===
[RealImage] Displaying photo1.jpg

=== img2 and img3 were never used — never loaded ===
```

---

## Java Example 2 — Protection Proxy (Access Control)

```java
// Subject
public interface DatabaseService {
    void read(String query);
    void write(String data);
    void delete(String table);
}

// Real Subject
public class RealDatabaseService implements DatabaseService {
    @Override
    public void read(String query) {
        System.out.println("[DB] Executing READ: " + query);
    }

    @Override
    public void write(String data) {
        System.out.println("[DB] Executing WRITE: " + data);
    }

    @Override
    public void delete(String table) {
        System.out.println("[DB] Executing DELETE on: " + table);
    }
}

// User roles
public enum Role { READ_ONLY, READ_WRITE, ADMIN }

// Protection Proxy
public class DatabaseServiceProxy implements DatabaseService {
    private RealDatabaseService realService = new RealDatabaseService();
    private String username;
    private Role role;

    public DatabaseServiceProxy(String username, Role role) {
        this.username = username;
        this.role = role;
    }

    @Override
    public void read(String query) {
        log("READ");
        realService.read(query); // All roles can read
    }

    @Override
    public void write(String data) {
        log("WRITE");
        if (role == Role.READ_ONLY) {
            throw new SecurityException("User '" + username + "' does not have WRITE permission!");
        }
        realService.write(data);
    }

    @Override
    public void delete(String table) {
        log("DELETE");
        if (role != Role.ADMIN) {
            throw new SecurityException("User '" + username + "' does not have DELETE permission!");
        }
        realService.delete(table);
    }

    private void log(String operation) {
        System.out.printf("[Proxy] User '%s' (%s) attempting %s%n", username, role, operation);
    }
}

// Client
public class Main {
    public static void main(String[] args) {
        DatabaseService adminService = new DatabaseServiceProxy("admin_user", Role.ADMIN);
        DatabaseService readOnly    = new DatabaseServiceProxy("guest_user", Role.READ_ONLY);

        System.out.println("--- Admin Access ---");
        adminService.read("SELECT * FROM users");
        adminService.write("INSERT INTO orders VALUES(...)");
        adminService.delete("old_logs");

        System.out.println("\n--- Read-Only Access ---");
        readOnly.read("SELECT * FROM products");

        try {
            readOnly.write("UPDATE prices SET price=0"); // Should fail!
        } catch (SecurityException e) {
            System.out.println("ACCESS DENIED: " + e.getMessage());
        }

        try {
            readOnly.delete("payments"); // Should fail!
        } catch (SecurityException e) {
            System.out.println("ACCESS DENIED: " + e.getMessage());
        }
    }
}
```

### Output

```
--- Admin Access ---
[Proxy] User 'admin_user' (ADMIN) attempting READ
[DB] Executing READ: SELECT * FROM users
[Proxy] User 'admin_user' (ADMIN) attempting WRITE
[DB] Executing WRITE: INSERT INTO orders VALUES(...)
[Proxy] User 'admin_user' (ADMIN) attempting DELETE
[DB] Executing DELETE on: old_logs

--- Read-Only Access ---
[Proxy] User 'guest_user' (READ_ONLY) attempting READ
[DB] Executing READ: SELECT * FROM products
[Proxy] User 'guest_user' (READ_ONLY) attempting WRITE
ACCESS DENIED: User 'guest_user' does not have WRITE permission!
[Proxy] User 'guest_user' (READ_ONLY) attempting DELETE
ACCESS DENIED: User 'guest_user' does not have DELETE permission!
```

---

## Java Example 3 — Caching Proxy

```java
public interface WeatherService {
    String getWeather(String city);
}

public class RealWeatherService implements WeatherService {
    @Override
    public String getWeather(String city) {
        System.out.println("[API] Fetching weather for " + city + "... (network call)");
        // Simulate network call
        return "Sunny, 28°C in " + city;
    }
}

public class CachingWeatherProxy implements WeatherService {
    private RealWeatherService realService = new RealWeatherService();
    private Map<String, String> cache = new HashMap<>();
    private static final long CACHE_TTL = 60_000; // 1 minute
    private Map<String, Long> cacheTimestamps = new HashMap<>();

    @Override
    public String getWeather(String city) {
        long now = System.currentTimeMillis();

        if (cache.containsKey(city) &&
            (now - cacheTimestamps.get(city)) < CACHE_TTL) {
            System.out.println("[Cache] Returning cached result for " + city);
            return cache.get(city);
        }

        String result = realService.getWeather(city);
        cache.put(city, result);
        cacheTimestamps.put(city, now);
        return result;
    }
}

// Usage
WeatherService service = new CachingWeatherProxy();
System.out.println(service.getWeather("Pune"));   // API call
System.out.println(service.getWeather("Pune"));   // Cache hit!
System.out.println(service.getWeather("Mumbai")); // API call
System.out.println(service.getWeather("Mumbai")); // Cache hit!
```

---

## Dynamic Proxy in Java

Java provides built-in support via `java.lang.reflect.Proxy`:

```java
import java.lang.reflect.*;

public class LoggingProxy {
    public static <T> T createProxy(T target, Class<T> iface) {
        return (T) Proxy.newProxyInstance(
            iface.getClassLoader(),
            new Class[]{iface},
            (proxy, method, args) -> {
                System.out.println("Before: " + method.getName());
                Object result = method.invoke(target, args);
                System.out.println("After: " + method.getName());
                return result;
            }
        );
    }
}

// Usage — auto-logs all method calls without any manual proxy class!
DatabaseService real   = new RealDatabaseService();
DatabaseService logged = LoggingProxy.createProxy(real, DatabaseService.class);
logged.read("SELECT 1"); // Automatically logged!
```

---

## Real-World Java Examples

| Framework | Proxy Usage |
|---|---|
| **Spring AOP** | `@Transactional`, `@Cacheable`, `@Async` all use dynamic proxies |
| **Hibernate** | Lazy-loaded entities are proxies until accessed |
| **Java RMI** | Stub is a remote proxy for the real server object |
| **Mockito** | `mock()` creates a proxy of the interface |

---

## Pros and Cons

### ✅ Advantages
- **Lazy initialization** — Heavy objects created only when needed
- **Access control** — Fine-grained permission checks
- **Caching** — Avoid repeated expensive operations
- **Logging/Auditing** — Intercept all calls transparently
- **Client is unaware** — Works through the same interface

### ❌ Disadvantages
- **Added latency** — Extra layer of indirection
- **Complexity** — More classes, potential for bugs in delegation
- **Response delay** — Lazy loading means first access is slow

---

## When to Use

✔ **Virtual Proxy** — Delay initialization of heavy objects  
✔ **Protection Proxy** — Control access based on permissions  
✔ **Remote Proxy** — Represent objects in a different address space  
✔ **Caching Proxy** — Cache expensive calls  
✔ **Logging Proxy** — Add observability without changing the real class  

---

## Key Takeaway

> **"Same interface, extra layer."**  
> A Proxy looks exactly like the real object to the client, but it intercepts every call — adding behavior (lazy loading, security, caching, logging) transparently.
