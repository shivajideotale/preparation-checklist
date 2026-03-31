# Abstract Factory Pattern

## Category
**Creational Design Pattern**

---

## Intent
Provide an interface for creating **families of related or dependent objects** without specifying their concrete classes.

Also known as: **Kit**

---

## The Problem It Solves

You're building a cross-platform UI library that must work on **Windows**, **macOS**, and **Linux**. Each platform needs its own widgets:

- **Windows**: `WindowsButton`, `WindowsCheckbox`, `WindowsScrollbar`
- **macOS**: `MacButton`, `MacCheckbox`, `MacScrollbar`
- **Linux**: `LinuxButton`, `LinuxCheckbox`, `LinuxScrollbar`

The problem:
- You can't mix widgets from different platforms (Windows button with Mac checkbox looks broken)
- Adding a new platform means adding many new classes
- Client code shouldn't need to know which platform it's running on

Abstract Factory creates the entire **family** of related objects through a single factory, ensuring consistency.

---

## Difference: Factory Method vs Abstract Factory

| Factory Method | Abstract Factory |
|---|---|
| Creates **one type** of product | Creates **families** of related products |
| One factory method | Multiple factory methods (one per product type) |
| Subclass decides which product | Factory object decides the family |

---

## Structure

```
AbstractFactory
  ├── createProductA() → AbstractProductA
  └── createProductB() → AbstractProductB

ConcreteFactory1
  ├── createProductA() → ConcreteProductA1
  └── createProductB() → ConcreteProductB1

ConcreteFactory2
  ├── createProductA() → ConcreteProductA2
  └── createProductB() → ConcreteProductB2
```

---

## Java Example — Cross-Platform UI

### Step 1: Abstract Products

```java
// Abstract Product A
public interface Button {
    void render();
    void onClick(String event);
}

// Abstract Product B
public interface Checkbox {
    void render();
    void onCheck(boolean checked);
}

// Abstract Product C
public interface TextField {
    void render();
    String getValue();
}
```

### Step 2: Concrete Products — Windows Family

```java
public class WindowsButton implements Button {
    @Override
    public void render() {
        System.out.println("[Windows] Rendering button with Win32 style");
    }

    @Override
    public void onClick(String event) {
        System.out.println("[Windows] Button clicked: " + event);
    }
}

public class WindowsCheckbox implements Checkbox {
    private boolean checked = false;

    @Override
    public void render() {
        System.out.println("[Windows] Rendering checkbox — checked: " + checked);
    }

    @Override
    public void onCheck(boolean checked) {
        this.checked = checked;
        System.out.println("[Windows] Checkbox state: " + checked);
    }
}

public class WindowsTextField implements TextField {
    private String value = "";

    @Override
    public void render() {
        System.out.println("[Windows] Rendering text field with value: '" + value + "'");
    }

    @Override
    public String getValue() { return value; }
}
```

### Step 3: Concrete Products — macOS Family

```java
public class MacButton implements Button {
    @Override
    public void render() {
        System.out.println("[macOS] Rendering button with Aqua style");
    }

    @Override
    public void onClick(String event) {
        System.out.println("[macOS] Button clicked: " + event);
    }
}

public class MacCheckbox implements Checkbox {
    private boolean checked = false;

    @Override
    public void render() {
        System.out.println("[macOS] Rendering checkbox (Aqua style) — checked: " + checked);
    }

    @Override
    public void onCheck(boolean checked) {
        this.checked = checked;
        System.out.println("[macOS] Checkbox toggled: " + checked);
    }
}

public class MacTextField implements TextField {
    private String value = "";

    @Override
    public void render() {
        System.out.println("[macOS] Rendering text field (rounded corners): '" + value + "'");
    }

    @Override
    public String getValue() { return value; }
}
```

### Step 4: Abstract Factory

```java
public interface UIFactory {
    Button    createButton();
    Checkbox  createCheckbox();
    TextField createTextField();
}
```

### Step 5: Concrete Factories

```java
public class WindowsUIFactory implements UIFactory {

    @Override
    public Button createButton() {
        return new WindowsButton();
    }

    @Override
    public Checkbox createCheckbox() {
        return new WindowsCheckbox();
    }

    @Override
    public TextField createTextField() {
        return new WindowsTextField();
    }
}

public class MacUIFactory implements UIFactory {

    @Override
    public Button createButton() {
        return new MacButton();
    }

    @Override
    public Checkbox createCheckbox() {
        return new MacCheckbox();
    }

    @Override
    public TextField createTextField() {
        return new MacTextField();
    }
}
```

### Step 6: Client (Application)

```java
// Client uses only abstractions — zero knowledge of concrete classes
public class Application {
    private Button    submitButton;
    private Checkbox  agreeCheckbox;
    private TextField nameField;

    // Factory is injected — this is Dependency Injection!
    public Application(UIFactory factory) {
        submitButton  = factory.createButton();
        agreeCheckbox = factory.createCheckbox();
        nameField     = factory.createTextField();
    }

    public void buildUI() {
        System.out.println("=== Building UI ===");
        nameField.render();
        agreeCheckbox.render();
        submitButton.render();
    }

    public void simulateInteraction() {
        System.out.println("=== User Interaction ===");
        agreeCheckbox.onCheck(true);
        submitButton.onClick("submit-form");
    }
}
```

### Step 7: Factory Provider and Main

```java
public class UIFactoryProvider {

    public static UIFactory getFactory() {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            return new WindowsUIFactory();
        } else if (os.contains("mac")) {
            return new MacUIFactory();
        } else {
            return new WindowsUIFactory(); // default
        }
    }
}

public class Main {
    public static void main(String[] args) {

        // Platform-aware factory selection
        UIFactory factory = UIFactoryProvider.getFactory();
        Application app = new Application(factory);
        app.buildUI();
        app.simulateInteraction();

        System.out.println("\n--- Forcing Windows UI ---");
        Application winApp = new Application(new WindowsUIFactory());
        winApp.buildUI();

        System.out.println("\n--- Forcing macOS UI ---");
        Application macApp = new Application(new MacUIFactory());
        macApp.buildUI();
    }
}
```

### Output (on macOS)

```
=== Building UI ===
[macOS] Rendering text field (rounded corners): ''
[macOS] Rendering checkbox (Aqua style) — checked: false
[macOS] Rendering button with Aqua style
=== User Interaction ===
[macOS] Checkbox toggled: true
[macOS] Button clicked: submit-form

--- Forcing Windows UI ---
=== Building UI ===
[Windows] Rendering text field with value: ''
[Windows] Rendering checkbox — checked: false
[Windows] Rendering button with Win32 style

--- Forcing macOS UI ---
=== Building UI ===
[macOS] Rendering text field (rounded corners): ''
[macOS] Rendering checkbox (Aqua style) — checked: false
[macOS] Rendering button with Aqua style
```

---

## Extending — Adding a New Platform

To add Linux support, you only need to:
1. Create `LinuxButton`, `LinuxCheckbox`, `LinuxTextField`
2. Create `LinuxUIFactory`
3. Update `UIFactoryProvider` to detect Linux

**Zero changes to `Application` or existing factories.**

---

## Real-World Java Examples

| Location | Abstract Factory |
|---|---|
| `javax.xml.parsers.DocumentBuilderFactory` | Creates SAX or DOM parsers based on configuration |
| `java.sql.Connection` | Creates `Statement`, `PreparedStatement`, `CallableStatement` |
| Spring `BeanFactory` / `ApplicationContext` | Creates beans from different configurations |
| JDBC — different DB drivers | MySQL driver creates MySQL-specific connections, statements |

```java
// JDBC is a classic Abstract Factory
Connection conn = DriverManager.getConnection(url, user, pass);
// Factory: conn (Connection)
Statement stmt      = conn.createStatement();         // ProductA
PreparedStatement ps = conn.prepareStatement(sql);   // ProductB
CallableStatement cs = conn.prepareCall(proc);        // ProductC
// All products are consistent with the DB driver family!
```

---

## Pros and Cons

### ✅ Advantages
- **Consistency** — Products from one factory are guaranteed to be compatible
- **Isolation** — Client code isolated from concrete product classes
- **Easy swap** — Switch entire product families by changing the factory
- **Open/Closed** — New families without changing existing code

### ❌ Disadvantages
- **Hard to add new product types** — Adding a new product (e.g., `ScrollBar`) requires modifying the Abstract Factory interface and ALL concrete factories
- **Many classes** — For N factories × M products = N×M classes
- **Complexity** — Significant setup for simple cases

---

## When to Use

✔ When a system must be independent of how its products are created  
✔ When you need to enforce consistency among products in a family  
✔ When you want to provide a product library revealing only interfaces  
✔ When systems need to work with multiple families of objects  

---

## Key Takeaway

> **"Abstract Factory = a factory that makes factories."**  
> It ensures you never accidentally mix a Windows button with a Mac checkbox — the factory guarantees all products belong to the same consistent family.
