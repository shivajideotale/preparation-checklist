# Null Object Pattern

## Category
**Behavioral Design Pattern** *(also considered a Special Case Pattern)*

---

## Intent
Provide an object with defined neutral ("do nothing") behavior as a substitute for `null`. This eliminates the need for null checks throughout the codebase.

---

## The Problem It Solves

Every Java developer has seen this:

```java
User user = userService.findById(id);
if (user != null) {
    if (user.getAddress() != null) {
        if (user.getAddress().getCity() != null) {
            System.out.println(user.getAddress().getCity());
        }
    }
}
```

Null checks:
- **Clutter** the business logic
- Are **easy to forget**, causing `NullPointerException`
- Make code **hard to read and test**

Tony Hoare, who invented null references in 1965, called it his **"billion dollar mistake."**

The Null Object Pattern replaces `null` with a **real object** that implements the same interface but does nothing (or returns safe defaults).

---

## Structure

```
AbstractObject (interface/abstract class)
  ├── RealObject      → actual behavior
  └── NullObject      → neutral "do nothing" behavior
```

Both types implement the same interface — the client doesn't need to check which one it has.

---

## Java Example — Logging System

### Problem: A logger might or might not be provided. Without null object, every usage requires a null check.

```java
// Subject Interface
public interface Logger {
    void info(String message);
    void warn(String message);
    void error(String message, Throwable t);
    boolean isEnabled();
}

// Real Logger
public class ConsoleLogger implements Logger {

    @Override
    public void info(String message) {
        System.out.println("[INFO]  " + message);
    }

    @Override
    public void warn(String message) {
        System.out.println("[WARN]  " + message);
    }

    @Override
    public void error(String message, Throwable t) {
        System.out.println("[ERROR] " + message + " - " + t.getMessage());
    }

    @Override
    public boolean isEnabled() { return true; }
}

// NULL Logger — does absolutely nothing but is safe to call
public class NullLogger implements Logger {

    @Override
    public void info(String message)  { /* intentionally empty */ }

    @Override
    public void warn(String message)  { /* intentionally empty */ }

    @Override
    public void error(String message, Throwable t) { /* intentionally empty */ }

    @Override
    public boolean isEnabled() { return false; }
}
```

### Client Code — Before (with null checks)

```java
// Messy — null checks everywhere
public class OrderService {
    private Logger logger; // might be null

    public OrderService(Logger logger) {
        this.logger = logger;
    }

    public void processOrder(String orderId) {
        if (logger != null) logger.info("Processing order: " + orderId);

        // business logic...

        if (logger != null) logger.info("Order processed: " + orderId);

        if (logger != null) logger.warn("Order high-value: " + orderId);
    }
}
```

### Client Code — After (with Null Object)

```java
// Clean — zero null checks!
public class OrderService {
    private Logger logger;

    public OrderService(Logger logger) {
        // Never assign null — use NullLogger as default
        this.logger = (logger != null) ? logger : new NullLogger();
    }

    public void processOrder(String orderId) {
        logger.info("Processing order: " + orderId); // always safe!

        // business logic...

        logger.info("Order processed: " + orderId);
        logger.warn("Consider review: " + orderId);
    }
}

// Usage
OrderService withLogging    = new OrderService(new ConsoleLogger());
OrderService withoutLogging = new OrderService(null); // gets NullLogger internally

withLogging.processOrder("ORD-001");    // Logs everything
withoutLogging.processOrder("ORD-002"); // Silent — no NPE, no null check
```

### Output

```
[INFO]  Processing order: ORD-001
[INFO]  Order processed: ORD-001
[WARN]  Consider review: ORD-001
// ORD-002 produces no output — NullLogger silently does nothing
```

---

## Java Example 2 — User Permissions

```java
public interface UserRole {
    boolean canRead();
    boolean canWrite();
    boolean canDelete();
    String getRoleName();
}

public class AdminRole implements UserRole {
    @Override public boolean canRead()   { return true; }
    @Override public boolean canWrite()  { return true; }
    @Override public boolean canDelete() { return true; }
    @Override public String getRoleName() { return "Admin"; }
}

public class GuestRole implements UserRole {
    @Override public boolean canRead()   { return true; }
    @Override public boolean canWrite()  { return false; }
    @Override public boolean canDelete() { return false; }
    @Override public String getRoleName() { return "Guest"; }
}

// Null Object — returned when no user is logged in
public class NullRole implements UserRole {
    @Override public boolean canRead()   { return false; }
    @Override public boolean canWrite()  { return false; }
    @Override public boolean canDelete() { return false; }
    @Override public String getRoleName() { return "Anonymous"; }
}

// User entity
public class User {
    private String name;
    private UserRole role;

    public User(String name, UserRole role) {
        this.name = name;
        this.role = (role != null) ? role : new NullRole();
    }

    public UserRole getRole() { return role; }
    public String getName()   { return name; }
}

// Repository
public class UserRepository {
    private Map<Integer, User> db = new HashMap<>();

    public UserRepository() {
        db.put(1, new User("Rahul",   new AdminRole()));
        db.put(2, new User("Priya",  new GuestRole()));
    }

    public User findById(int id) {
        // Return a NullRole user instead of null
        return db.getOrDefault(id,
            new User("Unknown", new NullRole()));
    }
}

// Client — zero null checks!
public class Main {
    public static void main(String[] args) {
        UserRepository repo = new UserRepository();

        int[] userIds = {1, 2, 99}; // 99 doesn't exist

        for (int id : userIds) {
            User user = repo.findById(id);
            UserRole role = user.getRole();

            System.out.printf("User: %-10s | Role: %-10s | Read: %s | Write: %s | Delete: %s%n",
                    user.getName(), role.getRoleName(),
                    role.canRead(), role.canWrite(), role.canDelete());
        }
    }
}
```

### Output

```
User: Rahul      | Role: Admin      | Read: true  | Write: true  | Delete: true
User: Priya      | Role: Guest      | Read: true  | Write: false | Delete: false
User: Unknown    | Role: Anonymous  | Read: false | Write: false | Delete: false
```

---

## Java Example 3 — Null Object with Chaining (Builder)

```java
public interface Discount {
    double apply(double price);
    String describe();
}

public class PercentageDiscount implements Discount {
    private double percent;

    public PercentageDiscount(double percent) {
        this.percent = percent;
    }

    @Override
    public double apply(double price) { return price * (1 - percent / 100); }

    @Override
    public String describe() { return percent + "% off"; }
}

// NULL Discount — no discount applied
public class NoDiscount implements Discount {
    @Override
    public double apply(double price) { return price; } // unchanged

    @Override
    public String describe() { return "No discount"; }
}

// Product
public class Product {
    private String name;
    private double price;
    private Discount discount;

    public Product(String name, double price, Discount discount) {
        this.name     = name;
        this.price    = price;
        this.discount = (discount != null) ? discount : new NoDiscount();
    }

    public void printPrice() {
        double finalPrice = discount.apply(price);
        System.out.printf("%s: ₹%.2f (Discount: %s → ₹%.2f)%n",
                name, price, discount.describe(), finalPrice);
    }
}

// Client
Product p1 = new Product("Laptop", 80000, new PercentageDiscount(10));
Product p2 = new Product("Pen",       20, null); // No discount — uses NoDiscount

p1.printPrice(); // Laptop: ₹80000.00 (Discount: 10.0% off → ₹72000.00)
p2.printPrice(); // Pen: ₹20.00 (Discount: No discount → ₹20.00)
```

---

## Java Standard Library — Null Object Instances

Java itself uses Null Object Pattern:

```java
// Collections.emptyList() is a Null Object for List
List<String> list = Collections.emptyList();
list.forEach(System.out::println); // No NPE, no output
list.size();    // 0
list.isEmpty(); // true

// Optional<T> is a structured Null Object
Optional<String> name = Optional.empty();
name.ifPresent(System.out::println); // No NPE, no output

// Comparator.naturalOrder() is a Null Object for comparison
```

---

## Pros and Cons

### ✅ Advantages
- **Eliminates null checks** — Cleaner, more readable code
- **No NullPointerException** — Null Objects are always safe to call
- **Uniform interface** — Client treats real and null objects identically
- **Open/Closed Principle** — Add Null Objects without changing client code

### ❌ Disadvantages
- **Hides bugs** — Silent failures; you might not notice something isn't working
- **More classes** — One extra class per interface
- **May not fit all cases** — Sometimes `null` needs to be explicitly handled (not ignored)
- **Return value confusion** — Null Object returns 0, false, "" — may be valid values

---

## When to Use

✔ When you want to avoid repetitive null checks  
✔ When a "do nothing" response is valid (logging, rendering, discounts)  
✔ When you want default/neutral behavior without conditionals  
✔ In plugin systems where a plugin may or may not exist  

---

## Key Takeaway

> **"Give me a real object that does nothing, instead of nothing."**  
> A Null Object replaces `null` with a safe, do-nothing implementation — eliminating null checks without exceptions or surprises.
