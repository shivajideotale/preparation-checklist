# S.O.L.I.D Principles

> Five foundational object-oriented design principles that make software **maintainable, extensible, and robust**.
> Coined by Robert C. Martin ("Uncle Bob"), arranged by Michael Feathers into the **SOLID** acronym.

---

## Overview

| # | Letter | Principle | One-Line Summary |
|---|---|---|---|
| 1 | **S** | Single Responsibility | A class should have only one reason to change |
| 2 | **O** | Open / Closed | Open for extension, closed for modification |
| 3 | **L** | Liskov Substitution | Subtypes must be substitutable for their base types |
| 4 | **I** | Interface Segregation | No client should depend on methods it doesn't use |
| 5 | **D** | Dependency Inversion | Depend on abstractions, not on concretions |

---

---

# S — Single Responsibility Principle (SRP)

## Definition

> **"A class should have only one reason to change."**

A class should do **one thing** and do it well. Every responsibility is a potential reason to change — if a class has multiple responsibilities, a change in one can break another.

---

## ❌ Violation — God Class

```java
// BAD: This class does EVERYTHING
public class Employee {
    private String name;
    private double salary;
    private String department;

    // Responsibility 1: Business logic
    public double calculateNetPay() {
        return salary * 0.85; // tax deduction
    }

    // Responsibility 2: Persistence — DB concern
    public void save() {
        String sql = "INSERT INTO employees VALUES ('" + name + "', " + salary + ")";
        System.out.println("[DB] " + sql);
    }

    // Responsibility 3: Reporting — Management concern
    public String generatePaySlip() {
        return "PAY SLIP\nName: " + name + "\nNet: ₹" + calculateNetPay();
    }

    // Responsibility 4: Notification — IT concern
    public void sendPaySlipEmail() {
        System.out.println("[Email] Sending slip to " + name);
    }
}
```

**Problem:** Finance changes `calculateNetPay()` → the DB class is affected. DBA changes the schema → the same file changes. Every actor forces a change on one class.

---

## ✅ Solution — One Class, One Job

```java
// 1. Domain object — just data + core business rule
public class Employee {
    private String name;
    private String department;
    private double salary;

    public Employee(String name, String department, double salary) {
        this.name = name; this.department = department; this.salary = salary;
    }
    public String getName()       { return name;       }
    public String getDepartment() { return department; }
    public double getSalary()     { return salary;     }
}

// 2. Finance's concern
public class PayCalculator {
    private static final double TAX_RATE   = 0.15;
    private static final double BONUS_RATE = 0.10;

    public double calculateNetPay(Employee e) {
        double tax   = e.getSalary() * TAX_RATE;
        double bonus = e.getDepartment().equals("Engineering") ? e.getSalary() * BONUS_RATE : 0;
        return e.getSalary() - tax + bonus;
    }
}

// 3. DBA's concern
public class EmployeeRepository {
    public void save(Employee e) {
        System.out.printf("[DB] INSERT INTO employees VALUES ('%s', '%.2f')%n",
                e.getName(), e.getSalary());
    }
    public Employee findByName(String name) {
        return new Employee(name, "Engineering", 80000); // simulated DB fetch
    }
}

// 4. Management's concern
public class PaySlipReporter {
    private final PayCalculator calc;
    public PaySlipReporter(PayCalculator calc) { this.calc = calc; }

    public String generate(Employee e) {
        return String.format(
            "===== PAY SLIP =====\nEmployee : %s\nDept     : %s\n" +
            "Gross    : ₹%.2f\nNet      : ₹%.2f\n====================",
            e.getName(), e.getDepartment(), e.getSalary(), calc.calculateNetPay(e));
    }
}

// 5. IT's concern
public class PaySlipEmailService {
    public void send(Employee e, String slip) {
        System.out.println("[Email] To: " + e.getName());
        System.out.println(slip);
    }
}

// Orchestrator — ties everything together
public class PayrollProcessor {
    private final PayCalculator       calc   = new PayCalculator();
    private final EmployeeRepository  repo   = new EmployeeRepository();
    private final PaySlipReporter     reporter = new PaySlipReporter(calc);
    private final PaySlipEmailService emailer  = new PaySlipEmailService();

    public void process(Employee e) {
        repo.save(e);
        emailer.send(e, reporter.generate(e));
    }

    public static void main(String[] args) {
        PayrollProcessor processor = new PayrollProcessor();
        processor.process(new Employee("Rahul Sharma", "Engineering", 95000));
        System.out.println();
        processor.process(new Employee("Priya Patel", "Marketing", 72000));
    }
}
```

### Output
```
[DB] INSERT INTO employees VALUES ('Rahul Sharma', '95000.00')
[Email] To: Rahul Sharma
===== PAY SLIP =====
Employee : Rahul Sharma
Dept     : Engineering
Gross    : ₹95000.00
Net      : ₹89300.00
====================

[DB] INSERT INTO employees VALUES ('Priya Patel', '72000.00')
[Email] To: Priya Patel
===== PAY SLIP =====
Employee : Priya Patel
Dept     : Marketing
Gross    : ₹72000.00
Net      : ₹61200.00
====================
```

---

## SRP at Every Level

| Level | Violation | Fix |
|---|---|---|
| **Method** | One method validates + saves + emails | Three separate methods |
| **Class** | One class handles business + DB + UI | Separate service, repo, controller |
| **Package** | Everything in `com.app` | `domain`, `repository`, `service`, `controller` |

## Key Takeaway — SRP
> **"One class. One job. One reason to change."**
> Split classes when different actors (Finance, IT, Management, DBA) each have a reason to force a change.

---
---

# O — Open / Closed Principle (OCP)

## Definition

> **"Software entities should be open for extension, but closed for modification."**
> — Bertrand Meyer

- **Open for extension** — Add new behavior freely
- **Closed for modification** — Never edit existing, tested code to add features

---

## ❌ Violation — Endless if-else

```java
// BAD: Every new discount type forces us to open and edit this class
public class DiscountCalculator {
    public double calculate(double price, String type) {
        if      (type.equals("SEASONAL"))   return price * 0.90;
        else if (type.equals("EMPLOYEE"))   return price * 0.70;
        else if (type.equals("LOYALTY"))    return price * 0.85;
        else if (type.equals("FLASH_SALE")) return price * 0.50; // ← had to modify!
        else if (type.equals("FIRST_ORDER"))return price * 0.80; // ← modify again!
        return price;
    }
}
```

---

## ✅ Solution — Extend via new classes

```java
// Stable abstraction — never changes
public interface DiscountStrategy {
    double apply(double price);
    String describe();
}

// Existing implementations — never touched again
public class SeasonalDiscount implements DiscountStrategy {
    public double apply(double price) { return price * 0.90; }
    public String describe()          { return "Seasonal  10% off"; }
}

public class EmployeeDiscount implements DiscountStrategy {
    public double apply(double price) { return price * 0.70; }
    public String describe()          { return "Employee  30% off"; }
}

public class LoyaltyDiscount implements DiscountStrategy {
    private final int points;
    public LoyaltyDiscount(int points) { this.points = points; }
    public double apply(double price)  { return price * (points > 1000 ? 0.80 : 0.90); }
    public String describe()           { return "Loyalty   10-20% off"; }
}

// ✅ NEW discount — added by writing a NEW class, zero edits to existing code
public class FlashSaleDiscount implements DiscountStrategy {
    public double apply(double price) { return price * 0.50; }
    public String describe()          { return "Flash Sale 50% off"; }
}

public class FirstOrderDiscount implements DiscountStrategy {
    public double apply(double price) { return price * 0.80; }
    public String describe()          { return "First Order 20% off"; }
}

// Context — CLOSED for modification forever
public class PriceEngine {
    private DiscountStrategy strategy;

    public PriceEngine(DiscountStrategy strategy) { this.strategy = strategy; }
    public void setStrategy(DiscountStrategy s)   { this.strategy = s; }

    public double finalPrice(double original) {
        double discounted = strategy.apply(original);
        System.out.printf("%-20s ₹%.2f → ₹%.2f  (saved ₹%.2f)%n",
                strategy.describe(), original, discounted, original - discounted);
        return discounted;
    }

    public static void main(String[] args) {
        double price = 5000.0;
        PriceEngine engine = new PriceEngine(new SeasonalDiscount());

        List<DiscountStrategy> all = List.of(
            new SeasonalDiscount(),
            new EmployeeDiscount(),
            new LoyaltyDiscount(1200),
            new FlashSaleDiscount(),
            new FirstOrderDiscount()
        );
        all.forEach(s -> { engine.setStrategy(s); engine.finalPrice(price); });
    }
}
```

### Output
```
Seasonal  10% off    ₹5000.00 → ₹4500.00  (saved ₹500.00)
Employee  30% off    ₹5000.00 → ₹3500.00  (saved ₹1500.00)
Loyalty   10-20% off ₹5000.00 → ₹4000.00  (saved ₹1000.00)
Flash Sale 50% off   ₹5000.00 → ₹2500.00  (saved ₹2500.00)
First Order 20% off  ₹5000.00 → ₹4000.00  (saved ₹1000.00)
```

---

## OCP Code Smells

| Smell | Why it Violates OCP |
|---|---|
| `if-else` / `switch` on type | Adding a type = modifying existing method |
| `instanceof` chains | Logic branching on concrete types |
| Enum driving behavior | Adding a value = editing every switch |

## Key Takeaway — OCP
> **"Add new code, don't touch old code."**
> Identify what changes (algorithm, format, rule), hide it behind an abstraction, then extend by writing new implementations — existing code stays locked and safe.

---
---

# L — Liskov Substitution Principle (LSP)

## Definition

> **"Objects of a superclass should be replaceable with objects of a subclass without affecting the correctness of the program."**
> — Barbara Liskov, 1987

If `S` is a subtype of `T`, then everywhere a `T` is used, an `S` can be placed — and the program still works correctly. Subclasses must **honor the behavioral contract** of their base class.

---

## ❌ Classic Violation — Square extends Rectangle

```java
// Base class
public class Rectangle {
    protected int width, height;

    public void setWidth(int w)  { this.width  = w; }
    public void setHeight(int h) { this.height = h; }
    public int  area()           { return width * height; }
}

// BAD: Square overrides setters in a contract-breaking way
public class Square extends Rectangle {
    @Override
    public void setWidth(int w)  { this.width = w; this.height = w; } // forces height!
    @Override
    public void setHeight(int h) { this.width = h; this.height = h; } // forces width!
}

// Client code — works with Rectangle, BREAKS with Square
public void assertArea(Rectangle r) {
    r.setWidth(5);
    r.setHeight(4);
    // Client expects width × height = 5 × 4 = 20
    System.out.println("Area: " + r.area()); // Rectangle=20 ✅  Square=16 ❌
}

assertArea(new Rectangle()); // Area: 20  ✅
assertArea(new Square());    // Area: 16  ❌ — Square set both to 4 after setHeight(4)!
```

The Square silently **changes the behavior** the client depends on. That is an LSP violation.

---

## ✅ Fix — Break the Bad Inheritance

```java
// Common abstraction — no setters, just area()
public interface Shape {
    int area();
    String describe();
}

// Each shape governs its own invariants independently
public class Rectangle implements Shape {
    private int width, height;
    public Rectangle(int w, int h) { this.width = w; this.height = h; }
    public void setWidth(int w)    { this.width  = w; }
    public void setHeight(int h)   { this.height = h; }
    public int  area()             { return width * height; }
    public String describe()       { return "Rectangle(" + width + "×" + height + ")=" + area(); }
}

public class Square implements Shape {
    private int side;
    public Square(int s)      { this.side = s; }
    public void setSide(int s){ this.side = s; }
    public int  area()        { return side * side; }
    public String describe()  { return "Square(" + side + "²)=" + area(); }
}

// ✅ Now both can be substituted safely
public void printArea(Shape s) { System.out.println(s.describe()); }

printArea(new Rectangle(5, 4)); // Rectangle(5×4)=20
printArea(new Square(4));       // Square(4²)=16
```

---

## ❌ Violation 2 — Throwing in Override

```java
// BAD: ReadOnlyList claims to be a List but breaks add()
public class ReadOnlyList<T> extends ArrayList<T> {
    @Override
    public boolean add(T element) {
        throw new UnsupportedOperationException("Read only!");
        // ← Any code calling list.add() will now crash unexpectedly
    }
}

public void populate(List<String> list) {
    list.add("Alice"); // ✅ ArrayList, ❌ ReadOnlyList — same interface, different contract
}
```

```java
// FIX: Don't inherit — model it as a distinct type
public class ReadOnlyCollection<T> implements Iterable<T> {
    private final List<T> data;
    public ReadOnlyCollection(List<T> data) { this.data = Collections.unmodifiableList(data); }
    public T   get(int i) { return data.get(i); }
    public int size()     { return data.size(); }
    @Override
    public Iterator<T> iterator() { return data.iterator(); }
    // No add(), no remove() — this type never promised them
}
```

---

## ❌ Violation 3 — Strengthened Preconditions

```java
// Base: accepts any non-null order
public class OrderProcessor {
    public void process(Order order) {
        if (order == null) throw new IllegalArgumentException("Order cannot be null");
        // process...
    }
}

// BAD: Subclass adds extra restriction — client passes valid orders that now fail
public class PremiumOrderProcessor extends OrderProcessor {
    @Override
    public void process(Order order) {
        if (order.getAmount() < 10000) // ← stricter than base!
            throw new IllegalArgumentException("Premium requires ₹10,000 minimum");
        super.process(order);
    }
}

// Client uses OrderProcessor reference — now crashes with valid small orders
OrderProcessor proc = new PremiumOrderProcessor();
proc.process(new Order(500)); // ❌ throws — client didn't expect this!
```

---

## LSP Contract Rules

| Rule | Meaning |
|---|---|
| **Preconditions** | Subclass must NOT strengthen (make stricter) preconditions |
| **Postconditions** | Subclass must NOT weaken (return less/different) postconditions |
| **Invariants** | All invariants of the base class must be preserved |
| **Exceptions** | Subclass must NOT throw new unexpected exception types |
| **History** | Base class history (state transitions) must still hold |

---

## Key Takeaway — LSP
> **"If it walks like a duck and quacks like a duck but needs batteries — you have the wrong abstraction."**
> Subclasses must behave as the base class promises. If a subclass needs to throw exceptions, refuse operations, or change expected output, it probably shouldn't be inheriting — use composition or redesign the hierarchy.

---
---

# I — Interface Segregation Principle (ISP)

## Definition

> **"No client should be forced to depend on methods it does not use."**
> — Robert C. Martin

Prefer many small, focused interfaces over one large, general-purpose interface. A class implementing an interface should use **every** method in it — if it can't, the interface is too fat.

---

## ❌ Violation — Fat Interface

```java
// BAD: One giant interface that forces every device to implement everything
public interface MultifunctionDevice {
    void print(Document d);
    void scan(Document d);
    void fax(Document d);
    void photocopy(Document d);
    void emailDocument(Document d);
    void printDuplex(Document d);
}

// BasicPrinter can only print — but ISP forces it to "implement" everything else
public class BasicPrinter implements MultifunctionDevice {
    @Override
    public void print(Document d) { System.out.println("Printing: " + d.getTitle()); }

    // ❌ Forced to implement methods it doesn't support
    @Override public void scan(Document d)          { throw new UnsupportedOperationException("No scanner!"); }
    @Override public void fax(Document d)           { throw new UnsupportedOperationException("No fax!"); }
    @Override public void photocopy(Document d)     { throw new UnsupportedOperationException("No copier!"); }
    @Override public void emailDocument(Document d) { throw new UnsupportedOperationException("No email!"); }
    @Override public void printDuplex(Document d)   { throw new UnsupportedOperationException("No duplex!"); }
}
```

Problems:
- `BasicPrinter` is coupled to methods it will never use
- Adding a new method to `MultifunctionDevice` breaks **every** implementation
- Clients of `BasicPrinter` might call `.scan()` expecting it to work

---

## ✅ Solution — Segregated Interfaces

```java
// Small, focused interfaces — each client uses only what it needs
public interface Printer {
    void print(Document d);
}

public interface Scanner {
    void scan(Document d);
}

public interface FaxMachine {
    void fax(Document d);
}

public interface Photocopier {
    void photocopy(Document d);
}

public interface EmailSender {
    void emailDocument(Document d);
}

public interface DuplexPrinter extends Printer {
    void printDuplex(Document d);
}

// ------------------------------------------------------------------

// BasicPrinter — only implements what it actually supports
public class BasicPrinter implements Printer {
    @Override
    public void print(Document d) {
        System.out.println("[Basic Printer] Printing: " + d.getTitle());
    }
}

// OfficePrinter — supports print + scan + photocopy
public class OfficePrinter implements Printer, Scanner, Photocopier {
    @Override
    public void print(Document d) {
        System.out.println("[Office Printer] Printing: " + d.getTitle());
    }
    @Override
    public void scan(Document d) {
        System.out.println("[Office Printer] Scanning: " + d.getTitle());
    }
    @Override
    public void photocopy(Document d) {
        System.out.println("[Office Printer] Photocopying: " + d.getTitle());
    }
}

// ExecutiveMFD — the full-featured machine implements everything
public class ExecutiveMFD implements Printer, Scanner, FaxMachine,
                                      Photocopier, EmailSender, DuplexPrinter {
    @Override public void print(Document d)         { System.out.println("[MFD] Print: "     + d.getTitle()); }
    @Override public void scan(Document d)          { System.out.println("[MFD] Scan: "      + d.getTitle()); }
    @Override public void fax(Document d)           { System.out.println("[MFD] Fax: "       + d.getTitle()); }
    @Override public void photocopy(Document d)     { System.out.println("[MFD] Photocopy: " + d.getTitle()); }
    @Override public void emailDocument(Document d) { System.out.println("[MFD] Email: "     + d.getTitle()); }
    @Override public void printDuplex(Document d)   { System.out.println("[MFD] Duplex: "    + d.getTitle()); }
}

// ------------------------------------------------------------------

// Clients depend ONLY on what they need
public class PrinterService {
    private final Printer printer; // only needs Printer
    public PrinterService(Printer p) { this.printer = p; }
    public void printReport(Document d) { printer.print(d); }
}

public class ScannerService {
    private final Scanner scanner; // only needs Scanner
    public ScannerService(Scanner s) { this.scanner = s; }
    public void scanDocument(Document d) { scanner.scan(d); }
}

// ------------------------------------------------------------------

public class Main {
    public static void main(String[] args) {
        Document invoice = new Document("Invoice_March_2025");

        Printer  basic  = new BasicPrinter();
        OfficePrinter office = new OfficePrinter();
        ExecutiveMFD  mfd    = new ExecutiveMFD();

        new PrinterService(basic).printReport(invoice);   // ✅ Basic print
        new PrinterService(office).printReport(invoice);  // ✅ Office print
        new ScannerService(office).scanDocument(invoice); // ✅ Office scan

        // MFD does everything
        new PrinterService(mfd).printReport(invoice);
        new ScannerService(mfd).scanDocument(invoice);
        mfd.fax(invoice);
        mfd.emailDocument(invoice);
        mfd.printDuplex(invoice);
    }
}
```

### Output
```
[Basic Printer] Printing: Invoice_March_2025
[Office Printer] Printing: Invoice_March_2025
[Office Printer] Scanning: Invoice_March_2025
[MFD] Print: Invoice_March_2025
[MFD] Scan: Invoice_March_2025
[MFD] Fax: Invoice_March_2025
[MFD] Email: Invoice_March_2025
[MFD] Duplex: Invoice_March_2025
```

---

## Real-World Example — Worker Interface

```java
// ❌ FAT interface — Robot cannot eat or sleep
public interface Worker {
    void work();
    void eat();
    void sleep();
    void attendMeeting();
}

public class Robot implements Worker {
    public void work()          { System.out.println("Robot working"); }
    public void eat()           { throw new UnsupportedOperationException(); } // ❌
    public void sleep()         { throw new UnsupportedOperationException(); } // ❌
    public void attendMeeting() { throw new UnsupportedOperationException(); } // ❌
}

// ✅ SEGREGATED — each role gets its own interface
public interface Workable   { void work(); }
public interface Feedable   { void eat();  }
public interface Sleepable  { void sleep(); }
public interface Meetable   { void attendMeeting(); }

public class HumanWorker implements Workable, Feedable, Sleepable, Meetable {
    public void work()          { System.out.println("Human working"); }
    public void eat()           { System.out.println("Human eating");  }
    public void sleep()         { System.out.println("Human sleeping");}
    public void attendMeeting() { System.out.println("Human in meeting"); }
}

public class RobotWorker implements Workable {
    // ✅ Only implements what it actually does — no forced empty methods
    public void work() { System.out.println("Robot working"); }
}
```

---

## ISP Violation Signals

| Signal | What It Means |
|---|---|
| `throw new UnsupportedOperationException()` | Interface is too fat for this implementor |
| Empty method body `{ }` in implementation | Forced to implement something irrelevant |
| "Does not apply" comments | Design smell — wrong abstraction |
| Clients import interfaces and use 1-2 methods | Interface has too many responsibilities |

---

## Key Takeaway — ISP
> **"Don't force classes to implement what they'll never use."**
> Fat interfaces create unnecessary dependencies and force implementations to lie (via empty bodies or exceptions). Split them by client need — each consumer should see only the interface relevant to it.

---
---

# D — Dependency Inversion Principle (DIP)

## Definition

> **A. High-level modules should not depend on low-level modules. Both should depend on abstractions.**
> **B. Abstractions should not depend on details. Details (concrete implementations) should depend on abstractions.**
> — Robert C. Martin

In simple terms:
- **Don't `new` up concretions inside business logic**
- Code to **interfaces**, not to classes
- Inject dependencies from outside (Dependency Injection)

---

## ❌ Violation — High-level depends on Low-level

```java
// Low-level module
public class MySQLDatabase {
    public void save(String data) {
        System.out.println("[MySQL] Saving: " + data);
    }
    public String load(String id) {
        return "[MySQL] Data for " + id;
    }
}

// Another low-level module
public class GmailService {
    public void sendEmail(String to, String body) {
        System.out.println("[Gmail] Sending to " + to + ": " + body);
    }
}

// ❌ BAD: High-level business logic directly depends on concrete low-level classes
public class OrderService {
    private MySQLDatabase database = new MySQLDatabase(); // ← direct dependency!
    private GmailService  emailer  = new GmailService();  // ← direct dependency!

    public void placeOrder(String orderId, String customerEmail) {
        // Business logic tightly coupled to MySQL and Gmail
        database.save("ORDER:" + orderId);
        emailer.sendEmail(customerEmail, "Order " + orderId + " confirmed!");
        System.out.println("Order placed: " + orderId);
    }
}
```

**Problems:**
- Can't test `OrderService` without a real MySQL DB and real Gmail account
- Switching MySQL → PostgreSQL or Gmail → SendGrid means editing `OrderService`
- Violates OCP too — business logic must change when infrastructure changes

---

## ✅ Solution — Depend on Abstractions

### Step 1: Define Abstractions (interfaces)

```java
// Abstraction for persistence
public interface OrderRepository {
    void save(String orderId);
    String findById(String orderId);
}

// Abstraction for notifications
public interface NotificationService {
    void notify(String recipient, String message);
}

// Abstraction for payment
public interface PaymentGateway {
    boolean charge(String customerId, double amount);
}
```

### Step 2: Concrete Low-Level Implementations

```java
// MySQL implementation of repository
public class MySQLOrderRepository implements OrderRepository {
    @Override
    public void save(String orderId) {
        System.out.println("[MySQL] INSERT INTO orders WHERE id='" + orderId + "'");
    }
    @Override
    public String findById(String orderId) {
        return "[MySQL] Order data for: " + orderId;
    }
}

// PostgreSQL implementation — swap in without touching OrderService
public class PostgreSQLOrderRepository implements OrderRepository {
    @Override
    public void save(String orderId) {
        System.out.println("[PostgreSQL] INSERT INTO orders WHERE id='" + orderId + "'");
    }
    @Override
    public String findById(String orderId) {
        return "[PostgreSQL] Order data for: " + orderId;
    }
}

// Gmail notification
public class GmailNotificationService implements NotificationService {
    @Override
    public void notify(String recipient, String message) {
        System.out.println("[Gmail] To: " + recipient + " | Msg: " + message);
    }
}

// SMS notification — added without touching business logic
public class SMSNotificationService implements NotificationService {
    @Override
    public void notify(String recipient, String message) {
        System.out.println("[SMS] To: " + recipient + " | Msg: " + message);
    }
}

// Stripe payment gateway
public class StripePaymentGateway implements PaymentGateway {
    @Override
    public boolean charge(String customerId, double amount) {
        System.out.printf("[Stripe] Charging ₹%.2f to customer %s%n", amount, customerId);
        return true; // success
    }
}

// Razorpay payment gateway — swap without touching OrderService
public class RazorpayPaymentGateway implements PaymentGateway {
    @Override
    public boolean charge(String customerId, double amount) {
        System.out.printf("[Razorpay] Charging ₹%.2f to customer %s%n", amount, customerId);
        return true;
    }
}
```

### Step 3: High-Level Module Depends Only on Abstractions

```java
// ✅ GOOD: OrderService depends ONLY on interfaces — knows nothing about MySQL or Gmail
public class OrderService {
    private final OrderRepository     repository;  // abstraction
    private final NotificationService notifier;    // abstraction
    private final PaymentGateway      payment;     // abstraction

    // Dependencies injected from outside (Constructor Injection)
    public OrderService(OrderRepository repository,
                        NotificationService notifier,
                        PaymentGateway payment) {
        this.repository = repository;
        this.notifier   = notifier;
        this.payment    = payment;
    }

    public boolean placeOrder(String orderId, String customerId,
                              String email, double amount) {
        System.out.println("\n--- Placing Order: " + orderId + " ---");

        // Step 1: Charge payment
        boolean charged = payment.charge(customerId, amount);
        if (!charged) {
            System.out.println("❌ Payment failed for order: " + orderId);
            return false;
        }

        // Step 2: Save to repository
        repository.save(orderId);

        // Step 3: Notify customer
        notifier.notify(email, "Your order " + orderId + " (₹" + amount + ") is confirmed!");

        System.out.println("✅ Order placed successfully: " + orderId);
        return true;
    }
}
```

### Step 4: Compose at the Outermost Layer (Main / Config)

```java
public class Application {
    public static void main(String[] args) {

        // ── Configuration A: MySQL + Gmail + Stripe ──────────
        OrderService productionService = new OrderService(
                new MySQLOrderRepository(),
                new GmailNotificationService(),
                new StripePaymentGateway()
        );
        productionService.placeOrder("ORD-001", "CUST-42", "rahul@email.com", 4999.0);

        // ── Configuration B: PostgreSQL + SMS + Razorpay ─────
        // Zero changes to OrderService — just swap implementations!
        OrderService alternateService = new OrderService(
                new PostgreSQLOrderRepository(),
                new SMSNotificationService(),
                new RazorpayPaymentGateway()
        );
        alternateService.placeOrder("ORD-002", "CUST-87", "+91-9876543210", 1299.0);

        // ── Configuration C: In-memory + console (for testing) ─
        OrderService testService = new OrderService(
                new InMemoryOrderRepository(),  // no real DB!
                new ConsoleNotificationService(), // just prints!
                new MockPaymentGateway()         // always succeeds!
        );
        testService.placeOrder("ORD-TEST", "CUST-TEST", "test@test.com", 100.0);
    }
}

// Lightweight test doubles — enabled by DIP!
class InMemoryOrderRepository implements OrderRepository {
    private final Map<String, String> store = new HashMap<>();
    public void save(String id)              { store.put(id, id); System.out.println("[InMem] Saved: " + id); }
    public String findById(String id)        { return store.get(id); }
}

class ConsoleNotificationService implements NotificationService {
    public void notify(String r, String msg) { System.out.println("[Console] " + r + ": " + msg); }
}

class MockPaymentGateway implements PaymentGateway {
    public boolean charge(String cid, double amt) {
        System.out.println("[Mock] Payment OK for " + cid);
        return true;
    }
}
```

### Output

```
--- Placing Order: ORD-001 ---
[Stripe] Charging ₹4999.00 to customer CUST-42
[MySQL] INSERT INTO orders WHERE id='ORD-001'
[Gmail] To: rahul@email.com | Msg: Your order ORD-001 (₹4999.0) is confirmed!
✅ Order placed successfully: ORD-001

--- Placing Order: ORD-002 ---
[Razorpay] Charging ₹1299.00 to customer CUST-87
[PostgreSQL] INSERT INTO orders WHERE id='ORD-002'
[SMS] To: +91-9876543210 | Msg: Your order ORD-002 (₹1299.0) is confirmed!
✅ Order placed successfully: ORD-002

--- Placing Order: ORD-TEST ---
[Mock] Payment OK for CUST-TEST
[InMem] Saved: ORD-TEST
[Console] test@test.com: Your order ORD-TEST (₹100.0) is confirmed!
✅ Order placed successfully: ORD-TEST
```

---

## DIP and Dependency Injection

DIP is the **principle**. Dependency Injection (DI) is the most common **technique** to achieve it.

| DI Type | Example |
|---|---|
| **Constructor Injection** ✅ | `new OrderService(repo, notifier, payment)` |
| **Setter Injection** | `service.setRepository(repo)` |
| **Field Injection** (Spring `@Autowired`) | Framework injects via reflection |
| **Interface Injection** | Service locator pattern |

Spring Boot automates DIP with `@Component`, `@Service`, `@Repository`, `@Autowired`:

```java
@Service
public class OrderService {
    private final OrderRepository     repository;
    private final NotificationService notifier;
    private final PaymentGateway      payment;

    @Autowired // Spring injects all three — DIP in action
    public OrderService(OrderRepository repository,
                        NotificationService notifier,
                        PaymentGateway payment) {
        this.repository = repository;
        this.notifier   = notifier;
        this.payment    = payment;
    }
}

@Repository
public class MySQLOrderRepository implements OrderRepository { ... }

@Component
public class GmailNotificationService implements NotificationService { ... }
```

---

## DIP Violation Signals

| Code Pattern | Why It Violates DIP |
|---|---|
| `new ConcreteClass()` in business logic | High-level now coupled to low-level |
| `import com.mysql.*` in service layer | Infrastructure detail leaking up |
| Static utility methods called directly | Can't swap, can't mock |
| `if (env.equals("prod")) new MySQL() else new H2()` | Config logic in domain code |

---

## Key Takeaway — DIP
> **"Code to an interface, not an implementation."**
> High-level policy (business logic) must not reach down and grab a concrete tool (MySQL, Gmail, Stripe). Both sides must look toward a shared abstraction — the interface. This is what enables testing with mocks, swapping implementations freely, and building systems where infrastructure changes don't ripple into business logic.

---
---

# SOLID — Quick Summary

```
S ── Single Responsibility ── One class, one job, one reason to change
O ── Open / Closed         ── Extend by adding code, not by editing it
L ── Liskov Substitution   ── Subclasses must keep the base class promises
I ── Interface Segregation ── Small interfaces, never force unused methods
D ── Dependency Inversion  ── Depend on abstractions, inject concretions
```

## All Principles Together — E-Commerce System

```
┌──────────────────────────────────────────────────┐
│              OrderService (High-level)           │◄── D: Depends on interfaces
│  ┌──────────┐  ┌──────────┐  ┌───────────────┐  │
│  │ Repository│  │Notifier  │  │PaymentGateway │  │◄── I: Small focused interfaces
│  └────┬─────┘  └────┬─────┘  └───────┬───────┘  │
└───────┼─────────────┼────────────────┼───────────┘
        │             │                │
   ┌────┴────┐   ┌────┴────┐   ┌──────┴──────┐
   │  MySQL  │   │  Gmail  │   │   Stripe    │   ◄── D: Low-level details
   │Postgres │   │   SMS   │   │  Razorpay   │   ◄── O: Add new impl = new class
   │InMemory │   │Slack    │   │  MockGW     │   ◄── L: All substitutable safely
   └─────────┘   └─────────┘   └─────────────┘
        │
   Each class has ONE job (S)
```

## Violation Quick-Reference

| Principle | Red Flag in Code |
|---|---|
| SRP | God class with 10+ methods doing unrelated things |
| OCP | `if-else` / `switch` on type that grows every sprint |
| LSP | `throw new UnsupportedOperationException()` in a subclass |
| ISP | Implementing an interface method with an empty body or `throw` |
| DIP | `new MySQLDatabase()` inside a service class |

## Relationship to Design Patterns

| Principle | Design Patterns That Embody It |
|---|---|
| SRP | Command, Observer, Strategy |
| OCP | Strategy, Decorator, Observer, Template Method |
| LSP | Template Method, Composite |
| ISP | Strategy, Command, Iterator |
| DIP | Factory Method, Abstract Factory, Strategy, Proxy |
