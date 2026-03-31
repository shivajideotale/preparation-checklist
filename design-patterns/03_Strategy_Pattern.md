# Strategy Pattern

## Category
**Behavioral Design Pattern**

---

## Intent
Define a family of algorithms, encapsulate each one, and make them interchangeable. Strategy lets the algorithm vary independently from the clients that use it.

---

## The Problem It Solves

Imagine you are building a navigation app. You need to support multiple routing strategies:
- **By Car** (fastest road route)
- **By Walk** (shortest foot path)
- **By Bicycle** (cycling lanes)
- **By Public Transport** (bus/train)

Without Strategy Pattern, you'd write one giant class with conditionals like:

```java
if (type.equals("car")) { ... }
else if (type.equals("walk")) { ... }
else if (type.equals("bike")) { ... }
```

This violates the **Open/Closed Principle** — every time you add a new transport type, you modify the existing class. The Strategy Pattern fixes this by extracting each algorithm into its own class.

---

## Structure

```
Context
  └── uses ──► Strategy (interface)
                    ├── ConcreteStrategyA
                    ├── ConcreteStrategyB
                    └── ConcreteStrategyC
```

### Participants

| Role | Responsibility |
|---|---|
| **Strategy** | Interface declaring the algorithm method |
| **ConcreteStrategy** | Implements a specific algorithm |
| **Context** | Holds a reference to a Strategy; delegates work to it |

---

## Java Example — Payment System

### Step 1: Define the Strategy Interface

```java
public interface PaymentStrategy {
    void pay(double amount);
}
```

### Step 2: Create Concrete Strategies

```java
// Credit Card Payment
public class CreditCardPayment implements PaymentStrategy {
    private String cardNumber;
    private String cardHolder;

    public CreditCardPayment(String cardHolder, String cardNumber) {
        this.cardHolder = cardHolder;
        this.cardNumber = cardNumber;
    }

    @Override
    public void pay(double amount) {
        System.out.printf("Paid ₹%.2f using Credit Card [%s] held by %s%n",
                amount, cardNumber, cardHolder);
    }
}

// UPI Payment
public class UPIPayment implements PaymentStrategy {
    private String upiId;

    public UPIPayment(String upiId) {
        this.upiId = upiId;
    }

    @Override
    public void pay(double amount) {
        System.out.printf("Paid ₹%.2f using UPI ID: %s%n", amount, upiId);
    }
}

// Net Banking Payment
public class NetBankingPayment implements PaymentStrategy {
    private String bankName;
    private String accountNumber;

    public NetBankingPayment(String bankName, String accountNumber) {
        this.bankName = bankName;
        this.accountNumber = accountNumber;
    }

    @Override
    public void pay(double amount) {
        System.out.printf("Paid ₹%.2f via Net Banking [%s] Account: %s%n",
                amount, bankName, accountNumber);
    }
}
```

### Step 3: Create the Context

```java
public class ShoppingCart {
    private List<Item> items = new ArrayList<>();
    private PaymentStrategy paymentStrategy;

    // Strategy can be set/changed at runtime
    public void setPaymentStrategy(PaymentStrategy strategy) {
        this.paymentStrategy = strategy;
    }

    public void addItem(Item item) {
        items.add(item);
    }

    public double calculateTotal() {
        return items.stream()
                    .mapToDouble(Item::getPrice)
                    .sum();
    }

    public void checkout() {
        if (paymentStrategy == null) {
            throw new IllegalStateException("Payment strategy not set!");
        }
        double total = calculateTotal();
        System.out.println("Order Total: ₹" + total);
        paymentStrategy.pay(total);
    }
}
```

### Step 4: Item Model

```java
public class Item {
    private String name;
    private double price;

    public Item(String name, double price) {
        this.name = name;
        this.price = price;
    }

    public double getPrice() { return price; }
    public String getName()  { return name;  }
}
```

### Step 5: Client Code

```java
public class Main {
    public static void main(String[] args) {
        ShoppingCart cart = new ShoppingCart();
        cart.addItem(new Item("Laptop", 75000));
        cart.addItem(new Item("Mouse",  1500));
        cart.addItem(new Item("Bag",    2000));

        // Pay with Credit Card
        cart.setPaymentStrategy(new CreditCardPayment("Rahul Sharma", "4111-1111-1111-1111"));
        cart.checkout();

        System.out.println("---");

        // Switch to UPI at runtime — no code change in cart!
        cart.setPaymentStrategy(new UPIPayment("rahul@okaxis"));
        cart.checkout();

        System.out.println("---");

        // Switch to Net Banking
        cart.setPaymentStrategy(new NetBankingPayment("SBI", "ACC-9876543210"));
        cart.checkout();
    }
}
```

### Output

```
Order Total: ₹78500.0
Paid ₹78500.00 using Credit Card [4111-1111-1111-1111] held by Rahul Sharma
---
Order Total: ₹78500.0
Paid ₹78500.00 using UPI ID: rahul@okaxis
---
Order Total: ₹78500.0
Paid ₹78500.00 via Net Banking [SBI] Account: ACC-9876543210
```

---

## Real-World Java Examples

| Framework / Library | Usage |
|---|---|
| `java.util.Comparator` | Sorting strategy passed to `Collections.sort()` |
| `javax.servlet.Filter` | Request filtering strategy in web apps |
| Spring Security `AuthenticationStrategy` | Authentication algorithm selection |
| `java.util.concurrent.ThreadPoolExecutor` | `RejectedExecutionHandler` as strategy |

```java
// Comparator IS a Strategy Pattern
List<String> names = Arrays.asList("Priya", "Amit", "Zara");

// Strategy 1: Natural order
Collections.sort(names, Comparator.naturalOrder());

// Strategy 2: Reverse order
Collections.sort(names, Comparator.reverseOrder());

// Strategy 3: By length
Collections.sort(names, Comparator.comparingInt(String::length));
```

---

## Pros and Cons

### ✅ Advantages
- **Open/Closed Principle** — Add new strategies without modifying Context
- **Eliminates conditionals** — No more `if-else` or `switch` chains
- **Runtime flexibility** — Switch algorithms on the fly
- **Testability** — Each strategy can be tested in isolation
- **Single Responsibility** — Each class does one thing

### ❌ Disadvantages
- **More classes** — Every algorithm becomes a separate class
- **Client awareness** — The client must know about different strategies to choose the right one
- **Overkill for simple cases** — If you only have 2 algorithms that rarely change, lambdas may be simpler

---

## Strategy vs Related Patterns

| Pattern | Difference |
|---|---|
| **State** | State transitions happen internally; Strategy is set externally by the client |
| **Template Method** | Uses inheritance; Strategy uses composition |
| **Command** | Encapsulates a request/action; Strategy encapsulates an algorithm |
| **Decorator** | Adds behavior to objects; Strategy replaces algorithm entirely |

---

## When to Use

✔ When you have multiple variants of an algorithm and want to switch between them  
✔ When you want to eliminate large conditional blocks  
✔ When different classes differ only in their behavior  
✔ When you need to isolate business logic from its implementation details  

---

## Key Takeaway

> **"Favor composition over inheritance."**  
> Strategy Pattern is the poster child of this principle — instead of subclassing to change behavior, you **inject** the behavior as an object.
