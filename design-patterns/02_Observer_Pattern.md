# Observer Pattern

## Category
**Behavioral Design Pattern**

---

## Intent
Define a one-to-many dependency between objects so that when one object changes state, all its dependents are notified and updated automatically.

Also known as: **Publish/Subscribe**, **Event Listener**, **Dependents**

---

## The Problem It Solves

You're building a stock market app. Many UI components need to react when a stock price changes:
- A **price label** must update
- A **chart** must redraw
- An **alert system** must check thresholds
- A **logger** must write to disk

Without Observer Pattern, the `StockData` class must manually call every component whenever price changes — creating tight coupling. Adding a new subscriber means modifying `StockData` every time.

Observer Pattern lets `StockData` be unaware of who is listening — subscribers register themselves and get notified automatically.

---

## Structure

```
Subject (Observable)
  ├── attach(Observer)
  ├── detach(Observer)
  └── notifyObservers()
        │
        ▼
   Observer (interface)
        │
   update(data)
        │
   ┌────┴────────┐
ConcreteObserverA  ConcreteObserverB
```

### Participants

| Role | Responsibility |
|---|---|
| **Subject** | Maintains list of observers; notifies them on state change |
| **Observer** | Interface with `update()` method |
| **ConcreteSubject** | Stores state; triggers notifications |
| **ConcreteObserver** | Reacts to notification; may query Subject for data |

---

## Java Example — Weather Station

### Step 1: Observer Interface

```java
public interface Observer {
    void update(float temperature, float humidity, float pressure);
}
```

### Step 2: Subject Interface

```java
public interface Subject {
    void addObserver(Observer o);
    void removeObserver(Observer o);
    void notifyObservers();
}
```

### Step 3: Concrete Subject — WeatherStation

```java
public class WeatherStation implements Subject {

    private List<Observer> observers = new ArrayList<>();
    private float temperature;
    private float humidity;
    private float pressure;

    @Override
    public void addObserver(Observer o) {
        observers.add(o);
        System.out.println("Observer added: " + o.getClass().getSimpleName());
    }

    @Override
    public void removeObserver(Observer o) {
        observers.remove(o);
        System.out.println("Observer removed: " + o.getClass().getSimpleName());
    }

    @Override
    public void notifyObservers() {
        for (Observer observer : observers) {
            observer.update(temperature, humidity, pressure);
        }
    }

    // Called when new measurements arrive
    public void setMeasurements(float temperature, float humidity, float pressure) {
        this.temperature = temperature;
        this.humidity    = humidity;
        this.pressure    = pressure;
        measurementsChanged(); // Trigger notification
    }

    private void measurementsChanged() {
        notifyObservers();
    }
}
```

### Step 4: Concrete Observers

```java
// Display for current conditions
public class CurrentConditionsDisplay implements Observer {
    private float temperature;
    private float humidity;

    @Override
    public void update(float temperature, float humidity, float pressure) {
        this.temperature = temperature;
        this.humidity    = humidity;
        display();
    }

    public void display() {
        System.out.printf("[CurrentConditions] Temp: %.1f°C | Humidity: %.1f%%%n",
                temperature, humidity);
    }
}

// Displays statistics (min/max/avg)
public class StatisticsDisplay implements Observer {
    private List<Float> temperatures = new ArrayList<>();

    @Override
    public void update(float temperature, float humidity, float pressure) {
        temperatures.add(temperature);
        display();
    }

    public void display() {
        float min = Collections.min(temperatures);
        float max = Collections.max(temperatures);
        float avg = (float) temperatures.stream().mapToDouble(Float::doubleValue).average().orElse(0);
        System.out.printf("[Statistics] Min: %.1f°C | Max: %.1f°C | Avg: %.1f°C%n", min, max, avg);
    }
}

// Alerts when temp exceeds threshold
public class AlertDisplay implements Observer {
    private float threshold;

    public AlertDisplay(float threshold) {
        this.threshold = threshold;
    }

    @Override
    public void update(float temperature, float humidity, float pressure) {
        if (temperature > threshold) {
            System.out.printf("[ALERT] 🔥 Temperature %.1f°C exceeds threshold %.1f°C!%n",
                    temperature, threshold);
        }
    }
}

// Logger
public class DataLogger implements Observer {
    private List<String> log = new ArrayList<>();

    @Override
    public void update(float temperature, float humidity, float pressure) {
        String entry = String.format("T=%.1f H=%.1f P=%.1f", temperature, humidity, pressure);
        log.add(entry);
        System.out.println("[Logger] Recorded: " + entry);
    }

    public List<String> getLog() { return Collections.unmodifiableList(log); }
}
```

### Step 5: Client Code

```java
public class Main {
    public static void main(String[] args) {
        WeatherStation station = new WeatherStation();

        // Create observers
        CurrentConditionsDisplay currentDisplay = new CurrentConditionsDisplay();
        StatisticsDisplay statsDisplay = new StatisticsDisplay();
        AlertDisplay alertDisplay = new AlertDisplay(35.0f);
        DataLogger logger = new DataLogger();

        // Register observers
        station.addObserver(currentDisplay);
        station.addObserver(statsDisplay);
        station.addObserver(alertDisplay);
        station.addObserver(logger);

        System.out.println("\n--- Measurement 1 ---");
        station.setMeasurements(28.5f, 65.0f, 1013.2f);

        System.out.println("\n--- Measurement 2 ---");
        station.setMeasurements(36.2f, 70.0f, 1009.5f); // Should trigger alert!

        System.out.println("\n--- Removing Alert ---");
        station.removeObserver(alertDisplay);

        System.out.println("\n--- Measurement 3 ---");
        station.setMeasurements(22.0f, 55.0f, 1015.0f); // No alert anymore

        System.out.println("\nFull Log: " + logger.getLog());
    }
}
```

### Output

```
Observer added: CurrentConditionsDisplay
Observer added: StatisticsDisplay
Observer added: AlertDisplay
Observer added: DataLogger

--- Measurement 1 ---
[CurrentConditions] Temp: 28.5°C | Humidity: 65.0%
[Statistics] Min: 28.5°C | Max: 28.5°C | Avg: 28.5°C
[Logger] Recorded: T=28.5 H=65.0 P=1013.2

--- Measurement 2 ---
[CurrentConditions] Temp: 36.2°C | Humidity: 70.0%
[Statistics] Min: 28.5°C | Max: 36.2°C | Avg: 32.4°C
[ALERT] 🔥 Temperature 36.2°C exceeds threshold 35.0°C!
[Logger] Recorded: T=36.2 H=70.0 P=1009.5

--- Removing Alert ---
Observer removed: AlertDisplay

--- Measurement 3 ---
[CurrentConditions] Temp: 22.0°C | Humidity: 55.0%
[Statistics] Min: 22.0°C | Max: 36.2°C | Avg: 28.9°C
[Logger] Recorded: T=22.0 H=55.0 P=1015.0

Full Log: [T=28.5 H=65.0 P=1013.2, T=36.2 H=70.0 P=1009.5, T=22.0 H=55.0 P=1015.0]
```

---

## Built-in Java Support

Java provides built-in support through `java.util.Observable` (deprecated in Java 9) and `java.util.EventListener`.

### Modern Java — Using PropertyChangeListener

```java
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;

public class StockPrice {
    private final PropertyChangeSupport support = new PropertyChangeSupport(this);
    private double price;

    public void addPropertyChangeListener(PropertyChangeListener l) {
        support.addPropertyChangeListener(l);
    }

    public void setPrice(double newPrice) {
        double oldPrice = this.price;
        this.price = newPrice;
        support.firePropertyChange("price", oldPrice, newPrice);
    }
}

// Usage
StockPrice stock = new StockPrice();
stock.addPropertyChangeListener(event ->
    System.out.println("Price changed: " + event.getOldValue() + " → " + event.getNewValue())
);
stock.setPrice(150.0);
stock.setPrice(162.5);
```

---

## Real-World Usage

| Framework | Usage |
|---|---|
| Java Swing | `ActionListener`, `MouseListener`, `KeyListener` |
| Android | `LiveData` observers in ViewModel |
| Spring | `ApplicationEventPublisher` / `@EventListener` |
| RxJava | `Observable` / `Observer` (reactive streams) |
| JavaFX | `ObservableList`, `ChangeListener` |

---

## Push vs Pull Model

| Model | Description | Example |
|---|---|---|
| **Push** | Subject sends all data in `update()` | Our weather example above |
| **Pull** | Subject sends itself; observer pulls what it needs | Observer stores reference to subject and calls getters |

```java
// Pull Model — Observer stores Subject reference
public class PullObserver implements Observer {
    private WeatherStation station; // holds reference

    public PullObserver(WeatherStation station) {
        this.station = station;
    }

    @Override
    public void update() { // no data passed
        // Pull only what's needed
        float temp = station.getTemperature();
        System.out.println("Pulled temp: " + temp);
    }
}
```

---

## Pros and Cons

### ✅ Advantages
- **Loose coupling** — Subject doesn't know concrete observer types
- **Dynamic relationships** — Add/remove observers at runtime
- **Open/Closed Principle** — New observers without changing Subject
- **Broadcast communication** — One event notifies many listeners

### ❌ Disadvantages
- **Unexpected updates** — Observers may be notified in unpredictable order
- **Memory leaks** — Forgetting to `removeObserver()` causes leaks (especially in Java)
- **Cascade updates** — One notification can trigger a chain of updates
- **Debugging difficulty** — Hard to trace why an update was triggered

---

## When to Use

✔ When changes to one object require changing unknown numbers of other objects  
✔ When objects should be able to notify others without assumptions about who those objects are  
✔ Event-driven systems, GUI frameworks, real-time dashboards  

---

## Key Takeaway

> **"Don't call us, we'll call you."**  
> The Subject doesn't poll its dependents — it broadcasts, and any interested Observer will handle it.
