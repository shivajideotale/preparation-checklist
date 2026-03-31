# Factory Pattern (Factory Method)

## Category
**Creational Design Pattern**

---

## Intent
Define an interface for creating an object, but let subclasses decide which class to instantiate. Factory Method lets a class defer instantiation to subclasses.

---

## The Problem It Solves

You're building a logistics application. Initially it only handles **Truck** deliveries. Later, requirements expand to **Ship**, **Plane**, and **Drone** deliveries.

Without Factory Method, object creation is scattered:

```java
// Scattered new() calls everywhere
Transport t;
if (type.equals("truck")) {
    t = new Truck();
} else if (type.equals("ship")) {
    t = new Ship();
}
```

Problems:
- Code is tightly coupled to concrete classes
- Adding a new transport requires hunting down every `if-else` chain
- Unit testing the logistics logic requires real transport objects

Factory Method centralizes and delegates creation — the client never uses `new` directly.

---

## Structure

```
Creator (abstract)
  └── factoryMethod() → Product
  └── someOperation() { p = factoryMethod(); p.doWork(); }
       │
       ├── ConcreteCreatorA → factoryMethod() → ConcreteProductA
       └── ConcreteCreatorB → factoryMethod() → ConcreteProductB

Product (interface)
  ├── ConcreteProductA
  └── ConcreteProductB
```

---

## Java Example — Notification System

### Step 1: Product Interface

```java
public interface Notification {
    void send(String recipient, String message);
    String getType();
}
```

### Step 2: Concrete Products

```java
public class EmailNotification implements Notification {

    @Override
    public void send(String recipient, String message) {
        System.out.printf("[EMAIL] To: %s | Message: %s%n", recipient, message);
        // Real code: connect to SMTP server, send email
    }

    @Override
    public String getType() { return "Email"; }
}

public class SMSNotification implements Notification {

    @Override
    public void send(String recipient, String message) {
        System.out.printf("[SMS] To: %s | Message: %s%n", recipient, message);
        // Real code: call Twilio/SMS gateway API
    }

    @Override
    public String getType() { return "SMS"; }
}

public class PushNotification implements Notification {

    @Override
    public void send(String recipient, String message) {
        System.out.printf("[PUSH] To device: %s | Message: %s%n", recipient, message);
        // Real code: call Firebase Cloud Messaging
    }

    @Override
    public String getType() { return "Push"; }
}

public class SlackNotification implements Notification {

    @Override
    public void send(String recipient, String message) {
        System.out.printf("[SLACK] Channel: %s | Message: %s%n", recipient, message);
        // Real code: call Slack Webhook API
    }

    @Override
    public String getType() { return "Slack"; }
}
```

### Step 3: Creator (Abstract)

```java
public abstract class NotificationFactory {

    // THE FACTORY METHOD — subclasses override this
    public abstract Notification createNotification();

    // Template method — uses the factory method internally
    public void sendNotification(String recipient, String message) {
        Notification notification = createNotification(); // calls factory method
        System.out.println("Sending via " + notification.getType() + "...");
        notification.send(recipient, message);
    }
}
```

### Step 4: Concrete Creators

```java
public class EmailNotificationFactory extends NotificationFactory {
    @Override
    public Notification createNotification() {
        return new EmailNotification();
    }
}

public class SMSNotificationFactory extends NotificationFactory {
    @Override
    public Notification createNotification() {
        return new SMSNotification();
    }
}

public class PushNotificationFactory extends NotificationFactory {
    @Override
    public Notification createNotification() {
        return new PushNotification();
    }
}

public class SlackNotificationFactory extends NotificationFactory {
    @Override
    public Notification createNotification() {
        return new SlackNotification();
    }
}
```

### Step 5: Simple Factory Variant (Static Factory)

```java
// Not GoF Factory Method, but extremely common in practice
public class NotificationFactoryProvider {

    public static NotificationFactory getFactory(String channel) {
        return switch (channel.toLowerCase()) {
            case "email" -> new EmailNotificationFactory();
            case "sms"   -> new SMSNotificationFactory();
            case "push"  -> new PushNotificationFactory();
            case "slack" -> new SlackNotificationFactory();
            default      -> throw new IllegalArgumentException("Unknown channel: " + channel);
        };
    }
}
```

### Step 6: Client Code

```java
public class Main {
    public static void main(String[] args) {

        // Direct factory usage
        NotificationFactory emailFactory = new EmailNotificationFactory();
        emailFactory.sendNotification("user@example.com", "Your order has shipped!");

        NotificationFactory smsFactory = new SMSNotificationFactory();
        smsFactory.sendNotification("+91-9876543210", "OTP: 4729");

        System.out.println("---");

        // Via provider (runtime decision)
        String[] channels = {"email", "sms", "push", "slack"};
        String   message  = "System maintenance at midnight.";

        for (String channel : channels) {
            NotificationFactory factory = NotificationFactoryProvider.getFactory(channel);
            factory.sendNotification("admin", message);
        }
    }
}
```

### Output

```
Sending via Email...
[EMAIL] To: user@example.com | Message: Your order has shipped!
Sending via SMS...
[SMS] To: +91-9876543210 | Message: OTP: 4729
---
Sending via Email...
[EMAIL] To: admin | Message: System maintenance at midnight.
Sending via SMS...
[SMS] To: admin | Message: System maintenance at midnight.
Sending via Push...
[PUSH] To device: admin | Message: System maintenance at midnight.
Sending via Slack...
[SLACK] Channel: admin | Message: System maintenance at midnight.
```

---

## Parameterized Factory Method

```java
public abstract class DocumentFactory {

    // Factory method with parameter
    public abstract Document createDocument(String format);

    public void openDocument(String path, String format) {
        Document doc = createDocument(format);
        doc.open(path);
    }
}

public class ConcreteDocumentFactory extends DocumentFactory {
    @Override
    public Document createDocument(String format) {
        return switch (format) {
            case "pdf"  -> new PDFDocument();
            case "docx" -> new WordDocument();
            case "xlsx" -> new ExcelDocument();
            default     -> throw new IllegalArgumentException("Unknown format: " + format);
        };
    }
}
```

---

## Real-World Java Examples

| Location | Factory Method |
|---|---|
| `java.util.Calendar.getInstance()` | Returns locale-specific Calendar subclass |
| `java.sql.DriverManager.getConnection()` | Returns database-specific Connection |
| `java.util.Iterator` from Collections | `ArrayList.iterator()` returns `Itr` inner class |
| `javax.xml.parsers.DocumentBuilderFactory` | Creates XML parsers |
| `LoggerFactory.getLogger()` (SLF4J) | Returns appropriate Logger implementation |

```java
// Calendar — you get an appropriate subclass, not Calendar directly
Calendar cal = Calendar.getInstance(); // Could be GregorianCalendar

// JDBC — DriverManager uses factory method internally
Connection conn = DriverManager.getConnection("jdbc:mysql://...", user, pass);
```

---

## Pros and Cons

### ✅ Advantages
- **Loose coupling** — Client depends on interface, not concrete class
- **Single Responsibility** — Object creation in one place
- **Open/Closed Principle** — Add new products by adding new factories
- **Testability** — Inject mock factories in unit tests

### ❌ Disadvantages
- **Class proliferation** — Every new product requires a new creator subclass
- **Complexity** — Overkill if only one product family is needed
- **Indirection** — More layers between caller and object creation

---

## Factory Variants Summary

| Variant | Description |
|---|---|
| **Simple Factory** | Static method returns an object; not a GoF pattern but common |
| **Factory Method** | Abstract creator with subclasses for each product (GoF) |
| **Abstract Factory** | Factory of factories — creates families of objects |

---

## When to Use

✔ When you don't know ahead of time what class you need to instantiate  
✔ When you want subclasses to specify what objects they create  
✔ When you want to provide a hook for extension in a framework  
✔ When constructors are complex and should be encapsulated  

---

## Key Takeaway

> **"Program to an interface, not an implementation — including how you create objects."**  
> Factory Method shifts the responsibility of `new` from the client to a dedicated creator, keeping the client decoupled from concrete classes.
