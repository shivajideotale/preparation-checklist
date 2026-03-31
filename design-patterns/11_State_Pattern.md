# State Pattern

## Category
**Behavioral Design Pattern**

---

## Intent
Allow an object to alter its behavior when its internal state changes. The object will appear to change its class.

---

## The Problem It Solves

You're building a **traffic light system** (or a vending machine, or a media player). The object's behavior depends entirely on its current state:

- **Red light** → Cars stop
- **Green light** → Cars go
- **Yellow light** → Cars slow down

Without State Pattern, you'd implement this with massive conditionals:

```java
public void action() {
    if (state == RED) {
        // stop logic
    } else if (state == GREEN) {
        // go logic
    } else if (state == YELLOW) {
        // slow down logic
    }
}

public void changeState() {
    if (state == RED) {
        state = GREEN;
    } else if (state == GREEN) {
        state = YELLOW;
    } else if (state == YELLOW) {
        state = RED;
    }
}
```

This grows unmanageable quickly. Every method has duplicate conditional logic. Adding a new state means modifying every method.

State Pattern moves each state's behavior into its own class.

---

## Structure

```
Context
  ├── state: State
  ├── request() → state.handle(this)
  └── setState(State)

State (interface)
  └── handle(Context)

  ├── ConcreteStateA → handle() { ... context.setState(new ConcreteStateB()) }
  └── ConcreteStateB → handle() { ... context.setState(new ConcreteStateA()) }
```

### Key: States transition **each other** — context just delegates.

---

## Java Example — Vending Machine

### Step 1: State Interface

```java
public interface VendingMachineState {
    void insertCoin(VendingMachine machine);
    void selectProduct(VendingMachine machine, String product);
    void dispense(VendingMachine machine);
    void cancel(VendingMachine machine);
    String getStateName();
}
```

### Step 2: Context — Vending Machine

```java
public class VendingMachine {
    private VendingMachineState currentState;
    private Map<String, Integer> inventory = new HashMap<>();
    private double balance = 0.0;

    public VendingMachine() {
        // Start in idle state
        this.currentState = new IdleState();
        inventory.put("Cola",   5);
        inventory.put("Water",  3);
        inventory.put("Chips",  0); // out of stock
    }

    // Delegates all actions to current state
    public void insertCoin(double amount) {
        balance += amount;
        System.out.printf("[Machine] Coin inserted: ₹%.0f | Balance: ₹%.0f%n", amount, balance);
        currentState.insertCoin(this);
    }

    public void selectProduct(String product) {
        System.out.println("[Machine] Product selected: " + product);
        currentState.selectProduct(this, product);
    }

    public void dispense() {
        currentState.dispense(this);
    }

    public void cancel() {
        currentState.cancel(this);
    }

    // State management
    public void setState(VendingMachineState state) {
        System.out.println("[State] " + currentState.getStateName()
                + " → " + state.getStateName());
        this.currentState = state;
    }

    // Inventory management
    public boolean hasProduct(String product) {
        return inventory.getOrDefault(product, 0) > 0;
    }

    public void dispenseProduct(String product) {
        inventory.put(product, inventory.get(product) - 1);
        System.out.println("[Machine] ✅ Dispensing " + product);
    }

    public double getBalance() { return balance; }
    public void deductBalance(double amount) { balance -= amount; }
    public void refundBalance() {
        System.out.printf("[Machine] Refunding ₹%.0f%n", balance);
        balance = 0;
    }

    public void printStatus() {
        System.out.println("[Status] State: " + currentState.getStateName()
                + " | Balance: ₹" + balance);
    }
}
```

### Step 3: Concrete States

```java
// State 1: Waiting for coin
public class IdleState implements VendingMachineState {

    @Override
    public void insertCoin(VendingMachine machine) {
        machine.setState(new HasMoneyState());
    }

    @Override
    public void selectProduct(VendingMachine machine, String product) {
        System.out.println("❌ Please insert a coin first.");
    }

    @Override
    public void dispense(VendingMachine machine) {
        System.out.println("❌ No product selected or coin inserted.");
    }

    @Override
    public void cancel(VendingMachine machine) {
        System.out.println("ℹ️  Nothing to cancel.");
    }

    @Override
    public String getStateName() { return "IDLE"; }
}

// State 2: Coin inserted, waiting for product selection
public class HasMoneyState implements VendingMachineState {
    private String selectedProduct;

    @Override
    public void insertCoin(VendingMachine machine) {
        System.out.println("ℹ️  Coin already inserted. Please select a product.");
    }

    @Override
    public void selectProduct(VendingMachine machine, String product) {
        if (!machine.hasProduct(product)) {
            System.out.println("❌ " + product + " is out of stock.");
            return;
        }
        this.selectedProduct = product;
        machine.setState(new ProductSelectedState(product));
    }

    @Override
    public void dispense(VendingMachine machine) {
        System.out.println("❌ Please select a product first.");
    }

    @Override
    public void cancel(VendingMachine machine) {
        machine.refundBalance();
        machine.setState(new IdleState());
    }

    @Override
    public String getStateName() { return "HAS_MONEY"; }
}

// State 3: Product selected, ready to dispense
public class ProductSelectedState implements VendingMachineState {
    private String product;
    private static final Map<String, Double> prices =
            Map.of("Cola", 30.0, "Water", 20.0, "Chips", 25.0);

    public ProductSelectedState(String product) {
        this.product = product;
    }

    @Override
    public void insertCoin(VendingMachine machine) {
        System.out.println("ℹ️  Product already selected: " + product + ". Press dispense.");
    }

    @Override
    public void selectProduct(VendingMachine machine, String newProduct) {
        System.out.println("ℹ️  Already selected " + product + ". Cancel to change.");
    }

    @Override
    public void dispense(VendingMachine machine) {
        double price = prices.getOrDefault(product, 99.0);

        if (machine.getBalance() < price) {
            System.out.printf("❌ Insufficient balance. Need ₹%.0f, have ₹%.0f%n",
                    price, machine.getBalance());
            return;
        }

        machine.deductBalance(price);
        machine.dispenseProduct(product);

        if (machine.getBalance() > 0) {
            machine.refundBalance();
        }

        machine.setState(new IdleState());
    }

    @Override
    public void cancel(VendingMachine machine) {
        System.out.println("ℹ️  Cancelling selection: " + product);
        machine.refundBalance();
        machine.setState(new IdleState());
    }

    @Override
    public String getStateName() { return "PRODUCT_SELECTED(" + product + ")"; }
}
```

### Step 4: Client Code

```java
public class Main {
    public static void main(String[] args) {
        VendingMachine machine = new VendingMachine();

        System.out.println("=== Scenario 1: Happy path ===");
        machine.insertCoin(30);
        machine.selectProduct("Cola");
        machine.dispense();

        System.out.println("\n=== Scenario 2: Out of stock ===");
        machine.insertCoin(30);
        machine.selectProduct("Chips"); // out of stock

        System.out.println("\n=== Scenario 3: Cancel ===");
        machine.selectProduct("Cola");
        machine.cancel(); // refunds

        System.out.println("\n=== Scenario 4: Insufficient balance ===");
        machine.insertCoin(10);
        machine.selectProduct("Water");
        machine.dispense(); // Water costs ₹20, only have ₹10

        machine.insertCoin(10); // top up
        machine.dispense(); // now ₹20 — works!
    }
}
```

### Output

```
=== Scenario 1: Happy path ===
[Machine] Coin inserted: ₹30 | Balance: ₹30
[State] IDLE → HAS_MONEY
[Machine] Product selected: Cola
[State] HAS_MONEY → PRODUCT_SELECTED(Cola)
[Machine] ✅ Dispensing Cola
[State] PRODUCT_SELECTED(Cola) → IDLE

=== Scenario 2: Out of stock ===
[Machine] Coin inserted: ₹30 | Balance: ₹30
[State] IDLE → HAS_MONEY
[Machine] Product selected: Chips
❌ Chips is out of stock.

=== Scenario 3: Cancel ===
[Machine] Product selected: Cola
[State] HAS_MONEY → PRODUCT_SELECTED(Cola)
ℹ️  Cancelling selection: Cola
[Machine] Refunding ₹30
[State] PRODUCT_SELECTED(Cola) → IDLE

=== Scenario 4: Insufficient balance ===
[Machine] Coin inserted: ₹10 | Balance: ₹10
[State] IDLE → HAS_MONEY
[Machine] Product selected: Water
[State] HAS_MONEY → PRODUCT_SELECTED(Water)
❌ Insufficient balance. Need ₹20, have ₹10
[Machine] Coin inserted: ₹10 | Balance: ₹20
[Machine] ✅ Dispensing Water
[State] PRODUCT_SELECTED(Water) → IDLE
```

---

## State Transition Diagram

```
           insertCoin
 [IDLE] ───────────────► [HAS_MONEY]
   ▲                          │
   │   cancel                 │ selectProduct
   │◄─────────────────────────┤
   │                          ▼
   │         cancel    [PRODUCT_SELECTED]
   │◄──────────────────────── │
   │                          │ dispense
   └──────────────────────────┘
```

---

## Real-World Java Examples

| Domain | States |
|---|---|
| **HTTP Connection** | CONNECTING → OPEN → HALF_CLOSED → CLOSED |
| **Order System** | PENDING → CONFIRMED → SHIPPED → DELIVERED → CANCELLED |
| **Thread lifecycle** | NEW → RUNNABLE → BLOCKED → WAITING → TERMINATED |
| **TCP Connection** | LISTEN → SYN_SENT → ESTABLISHED → FIN_WAIT → CLOSED |

---

## State vs Strategy

| Aspect | State | Strategy |
|---|---|---|
| **Intent** | Varies behavior by internal state | Varies algorithm by external injection |
| **Transitions** | States change each other | Client changes strategy |
| **Awareness** | States know about each other | Strategies are independent |
| **Context change** | Context transitions automatically | Client switches explicitly |

---

## Pros and Cons

### ✅ Advantages
- **Eliminates conditionals** — No giant `if/switch` blocks
- **Single Responsibility** — Each state class handles one state's logic
- **Open/Closed** — New states without changing existing ones
- **State transitions explicit** — Easy to see and reason about

### ❌ Disadvantages
- **Class proliferation** — One class per state
- **Shared state** — Context may need to expose internal state to state objects
- **Overkill** — Simple two-state objects don't need this pattern

---

## When to Use

✔ When an object's behavior depends on its state and must change at runtime  
✔ When operations have large, multi-part conditionals based on the object's state  
✔ When states and transitions are explicit and numerous  

---

## Key Takeaway

> **"Objects change personality based on their state."**  
> Instead of one class with endless conditionals, each state is its own class — the context simply delegates behavior to whichever state it's currently in.
