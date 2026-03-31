# Decorator Pattern

## Category
**Structural Design Pattern**

---

## Intent
Attach additional responsibilities to an object dynamically. Decorators provide a flexible alternative to subclassing for extending functionality.

Also known as: **Wrapper**

---

## The Problem It Solves

You're building a coffee shop system. You have a basic `Coffee` class. Customers can add extras:
- Milk
- Sugar
- Whipped Cream
- Caramel
- Vanilla

Using inheritance, you'd need a class for every combination:
`CoffeeWithMilk`, `CoffeeWithMilkAndSugar`, `CoffeeWithMilkSugarAndCaramel`...

With just 5 add-ons, that's **32 possible combinations** — a class explosion!

Decorator Pattern solves this by wrapping the base object with decorator objects, each adding one responsibility. You compose features at runtime.

---

## Structure

```
Component (interface)
  ├── ConcreteComponent        ← base object
  └── Decorator (abstract)     ← wraps a Component
        ├── ConcreteDecoratorA
        ├── ConcreteDecoratorB
        └── ConcreteDecoratorC
```

### Key insight: **Decorator IS-A Component AND HAS-A Component**

---

## Java Example — Coffee Shop

### Step 1: Component Interface

```java
public interface Coffee {
    String getDescription();
    double getCost();
}
```

### Step 2: Concrete Component (base beverage)

```java
public class SimpleCoffee implements Coffee {

    @Override
    public String getDescription() {
        return "Simple Coffee";
    }

    @Override
    public double getCost() {
        return 50.0; // ₹50 base price
    }
}

public class Espresso implements Coffee {

    @Override
    public String getDescription() {
        return "Espresso";
    }

    @Override
    public double getCost() {
        return 80.0;
    }
}
```

### Step 3: Abstract Decorator

```java
public abstract class CoffeeDecorator implements Coffee {
    protected Coffee decoratedCoffee; // HAS-A component

    public CoffeeDecorator(Coffee coffee) {
        this.decoratedCoffee = coffee;
    }

    @Override
    public String getDescription() {
        return decoratedCoffee.getDescription(); // delegates to wrapped object
    }

    @Override
    public double getCost() {
        return decoratedCoffee.getCost(); // delegates to wrapped object
    }
}
```

### Step 4: Concrete Decorators

```java
public class MilkDecorator extends CoffeeDecorator {

    public MilkDecorator(Coffee coffee) {
        super(coffee);
    }

    @Override
    public String getDescription() {
        return decoratedCoffee.getDescription() + ", Milk";
    }

    @Override
    public double getCost() {
        return decoratedCoffee.getCost() + 15.0; // ₹15 extra
    }
}

public class SugarDecorator extends CoffeeDecorator {

    public SugarDecorator(Coffee coffee) {
        super(coffee);
    }

    @Override
    public String getDescription() {
        return decoratedCoffee.getDescription() + ", Sugar";
    }

    @Override
    public double getCost() {
        return decoratedCoffee.getCost() + 5.0; // ₹5 extra
    }
}

public class WhippedCreamDecorator extends CoffeeDecorator {

    public WhippedCreamDecorator(Coffee coffee) {
        super(coffee);
    }

    @Override
    public String getDescription() {
        return decoratedCoffee.getDescription() + ", Whipped Cream";
    }

    @Override
    public double getCost() {
        return decoratedCoffee.getCost() + 25.0; // ₹25 extra
    }
}

public class CaramelDecorator extends CoffeeDecorator {

    public CaramelDecorator(Coffee coffee) {
        super(coffee);
    }

    @Override
    public String getDescription() {
        return decoratedCoffee.getDescription() + ", Caramel";
    }

    @Override
    public double getCost() {
        return decoratedCoffee.getCost() + 30.0;
    }
}
```

### Step 5: Client Code

```java
public class CoffeeShop {
    public static void main(String[] args) {

        // Order 1: Plain Coffee
        Coffee order1 = new SimpleCoffee();
        printOrder(order1);

        // Order 2: Coffee with Milk and Sugar
        Coffee order2 = new SimpleCoffee();
        order2 = new MilkDecorator(order2);
        order2 = new SugarDecorator(order2);
        printOrder(order2);

        // Order 3: Espresso with Whipped Cream, Caramel, and extra Sugar
        Coffee order3 = new Espresso();
        order3 = new WhippedCreamDecorator(order3);
        order3 = new CaramelDecorator(order3);
        order3 = new SugarDecorator(order3);
        printOrder(order3);

        // Order 4: Double milk (decorator applied twice!)
        Coffee order4 = new SimpleCoffee();
        order4 = new MilkDecorator(order4);
        order4 = new MilkDecorator(order4); // extra milk
        printOrder(order4);
    }

    static void printOrder(Coffee coffee) {
        System.out.println("Description : " + coffee.getDescription());
        System.out.printf("Total Cost  : ₹%.1f%n%n", coffee.getCost());
    }
}
```

### Output

```
Description : Simple Coffee
Total Cost  : ₹50.0

Description : Simple Coffee, Milk, Sugar
Total Cost  : ₹70.0

Description : Espresso, Whipped Cream, Caramel, Sugar
Total Cost  : ₹140.0

Description : Simple Coffee, Milk, Milk
Total Cost  : ₹80.0
```

---

## How It Works — Object Chain Visualization

For `order3`:

```
SugarDecorator
  └── CaramelDecorator
        └── WhippedCreamDecorator
              └── Espresso (base)

getCost() call unwraps like a chain:
  SugarDecorator.getCost()
    → CaramelDecorator.getCost()
      → WhippedCreamDecorator.getCost()
        → Espresso.getCost() → 80.0
      → + 25.0 = 105.0
    → + 30.0 = 135.0
  → + 5.0 = 140.0 ✓
```

---

## Real-World Java Example — I/O Streams

Java's entire I/O system is built on the Decorator Pattern:

```java
// Base stream (ConcreteComponent)
FileInputStream file = new FileInputStream("data.txt");

// Decorated with buffering (performance)
BufferedInputStream buffered = new BufferedInputStream(file);

// Decorated with data reading capability
DataInputStream data = new DataInputStream(buffered);

// Now reads buffered, typed data from file
int value = data.readInt();

// All these implement InputStream — that's the Component interface!
```

```
InputStream (Component)
  ├── FileInputStream        ← ConcreteComponent
  ├── FilterInputStream      ← Abstract Decorator
  │     ├── BufferedInputStream
  │     ├── DataInputStream
  │     └── GZIPInputStream
  └── ByteArrayInputStream   ← ConcreteComponent
```

---

## Another Example — Text Formatter

```java
public interface TextFormatter {
    String format(String text);
}

public class PlainText implements TextFormatter {
    @Override
    public String format(String text) { return text; }
}

public abstract class TextDecorator implements TextFormatter {
    protected TextFormatter wrapped;
    public TextDecorator(TextFormatter f) { this.wrapped = f; }
}

public class UpperCaseDecorator extends TextDecorator {
    public UpperCaseDecorator(TextFormatter f) { super(f); }
    @Override
    public String format(String text) {
        return wrapped.format(text).toUpperCase();
    }
}

public class TrimDecorator extends TextDecorator {
    public TrimDecorator(TextFormatter f) { super(f); }
    @Override
    public String format(String text) {
        return wrapped.format(text).trim();
    }
}

public class ExclamationDecorator extends TextDecorator {
    public ExclamationDecorator(TextFormatter f) { super(f); }
    @Override
    public String format(String text) {
        return wrapped.format(text) + "!!!";
    }
}

// Usage
TextFormatter formatter = new PlainText();
formatter = new TrimDecorator(formatter);
formatter = new UpperCaseDecorator(formatter);
formatter = new ExclamationDecorator(formatter);

System.out.println(formatter.format("  hello world  "));
// Output: HELLO WORLD!!!
```

---

## Pros and Cons

### ✅ Advantages
- **Avoids class explosion** — Compose behavior instead of inheriting it
- **Single Responsibility** — Each decorator handles one concern
- **Open/Closed Principle** — New decorators without changing existing code
- **Runtime flexibility** — Wrap/unwrap behavior dynamically

### ❌ Disadvantages
- **Many small objects** — Can create a large number of tiny wrapper classes
- **Order sensitivity** — The order of wrapping matters (e.g., trim before uppercase vs. after)
- **Debugging complexity** — Stack of decorators can be confusing to trace
- **Type identity** — `instanceof` checks fail (a `MilkDecorator` is NOT a `SimpleCoffee`)

---

## Decorator vs Related Patterns

| Pattern | Key Difference |
|---|---|
| **Inheritance** | Static at compile-time; Decorator is dynamic at runtime |
| **Composite** | Composite treats children uniformly; Decorator adds to one object |
| **Proxy** | Proxy controls access; Decorator adds behavior |
| **Strategy** | Strategy changes the algorithm inside; Decorator adds around |

---

## When to Use

✔ When you want to add responsibilities to objects without subclassing  
✔ When extension by subclassing would cause a class explosion  
✔ When you want to add/remove behaviors at runtime  
✔ When wrapping is more natural than inheritance  

---

## Key Takeaway

> **"Wrap, don't subclass."**  
> Instead of building a new class for every combination of features, compose them at runtime by layering decorator objects — each adding one piece of responsibility.
