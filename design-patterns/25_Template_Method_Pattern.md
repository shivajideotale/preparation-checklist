# Template Method Pattern

## Category
**Behavioral Design Pattern**

---

## Intent
Define the **skeleton of an algorithm** in an operation, deferring some steps to subclasses. Template Method lets subclasses redefine certain steps of an algorithm without changing the algorithm's structure.

---

## The Problem It Solves

You're building a data mining application that extracts reports from different file formats: CSV, XML, PDF.

Each format has the same overall process:
1. Open file
2. Parse data
3. Analyze data
4. Generate report
5. Close file

Steps 3 and 4 are the same for all formats. Only steps 1, 2, and 5 differ per format.

Without Template Method:
- Each subclass duplicates the identical steps
- The common algorithm structure is scattered and inconsistent

Template Method extracts the common skeleton into a base class and lets subclasses override only the varying steps.

---

## Structure

```
AbstractClass
  └── templateMethod() {  ← final — defines skeleton
        step1();           ← concrete (shared)
        step2();           ← abstract (must override)
        step3();           ← abstract (must override)
        hook();            ← optional override (has default)
      }

ConcreteClass extends AbstractClass
  └── step2() { ... }   ← provides specific implementation
  └── step3() { ... }
```

---

## Key Concepts

| Method Type | In Template Method | Meaning |
|---|---|---|
| **template method** | `final` | The skeleton — must not be overridden |
| **abstract methods** | `abstract` | Subclasses MUST implement |
| **hook methods** | `protected`, empty | Subclasses MAY override (optional extension points) |
| **concrete methods** | Normal | Shared behavior, not overridden |

---

## Java Example 1 — Data Mining Framework

### Step 1: Abstract Class with Template Method

```java
public abstract class DataMiner {

    // ─── THE TEMPLATE METHOD ────────────────────────────────────
    // final — the skeleton is fixed; subclasses cannot change the order
    public final MiningReport mine(String filePath) {
        System.out.println("\n=== Mining: " + filePath + " ===");

        // 1. Open the file (varies per format)
        openFile(filePath);

        // 2. Extract data (varies per format)
        List<String[]> rawData = extractData();

        // 3. Parse data (common)
        List<DataRecord> records = parseData(rawData);

        // 4. Hook — subclasses may apply filtering (optional)
        records = filterRecords(records);

        // 5. Analyze data (common)
        MiningReport report = analyzeData(records, filePath);

        // 6. Close file (varies per format)
        closeFile();

        // 7. Hook — subclasses may post-process (optional)
        onMiningComplete(report);

        return report;
    }
    // ─────────────────────────────────────────────────────────────

    // Abstract — MUST be implemented by subclasses
    protected abstract void openFile(String filePath);
    protected abstract List<String[]> extractData();
    protected abstract void closeFile();

    // Concrete — shared logic (not overridden normally)
    protected List<DataRecord> parseData(List<String[]> rawData) {
        System.out.println("[Base] Parsing " + rawData.size() + " rows...");
        List<DataRecord> records = new ArrayList<>();
        for (String[] row : rawData) {
            if (row.length >= 2) {
                records.add(new DataRecord(row[0], row[1]));
            }
        }
        System.out.println("[Base] Parsed " + records.size() + " valid records.");
        return records;
    }

    protected MiningReport analyzeData(List<DataRecord> records, String source) {
        System.out.println("[Base] Analyzing data...");
        long total  = records.size();
        long unique = records.stream().map(DataRecord::category).distinct().count();
        return new MiningReport(source, total, unique);
    }

    // Hooks — optional, default implementations do nothing
    protected List<DataRecord> filterRecords(List<DataRecord> records) {
        return records; // default: no filtering
    }

    protected void onMiningComplete(MiningReport report) {
        // default: do nothing
    }
}
```

### Supporting Types

```java
public record DataRecord(String name, String category) {}

public record MiningReport(String source, long totalRecords, long uniqueCategories) {
    @Override
    public String toString() {
        return String.format("Report[source=%s | total=%d | categories=%d]",
                source, totalRecords, uniqueCategories);
    }
}
```

### Step 2: Concrete Implementations

```java
// CSV Miner
public class CSVMiner extends DataMiner {
    private BufferedReader reader;
    private String filePath;

    @Override
    protected void openFile(String filePath) {
        this.filePath = filePath;
        System.out.println("[CSV] Opening file: " + filePath);
        // Real: reader = new BufferedReader(new FileReader(filePath));
    }

    @Override
    protected List<String[]> extractData() {
        System.out.println("[CSV] Extracting data (splitting by comma)...");
        // Simulate CSV rows
        return List.of(
                new String[]{"Product A", "Electronics"},
                new String[]{"Product B", "Clothing"},
                new String[]{"Product C", "Electronics"},
                new String[]{"Product D", "Food"},
                new String[]{"invalid-row"}
        );
    }

    @Override
    protected void closeFile() {
        System.out.println("[CSV] Closing file.");
        // Real: reader.close();
    }

    // Override hook — CSV miner filters out short names
    @Override
    protected List<DataRecord> filterRecords(List<DataRecord> records) {
        System.out.println("[CSV] Filtering records with short names...");
        return records.stream()
                .filter(r -> r.name().length() > 5)
                .toList();
    }
}

// XML Miner
public class XMLMiner extends DataMiner {

    @Override
    protected void openFile(String filePath) {
        System.out.println("[XML] Parsing XML document: " + filePath);
    }

    @Override
    protected List<String[]> extractData() {
        System.out.println("[XML] Extracting elements from XML nodes...");
        return List.of(
                new String[]{"Widget Alpha", "Hardware"},
                new String[]{"Widget Beta",  "Software"},
                new String[]{"Widget Gamma", "Hardware"},
                new String[]{"Widget Delta", "Services"}
        );
    }

    @Override
    protected void closeFile() {
        System.out.println("[XML] Releasing XML document.");
    }

    // Override hook — send notification when done
    @Override
    protected void onMiningComplete(MiningReport report) {
        System.out.println("[XML] 📧 Sending report notification: " + report);
    }
}

// PDF Miner
public class PDFMiner extends DataMiner {

    @Override
    protected void openFile(String filePath) {
        System.out.println("[PDF] Loading PDF with OCR engine: " + filePath);
    }

    @Override
    protected List<String[]> extractData() {
        System.out.println("[PDF] Running OCR and table extraction...");
        return List.of(
                new String[]{"Invoice 001", "Finance"},
                new String[]{"Invoice 002", "Finance"},
                new String[]{"Receipt 001", "Finance"}
        );
    }

    @Override
    protected void closeFile() {
        System.out.println("[PDF] Closing PDF engine.");
    }
}
```

### Step 3: Client Code

```java
public class ReportGenerator {
    public static void main(String[] args) {
        DataMiner csvMiner = new CSVMiner();
        DataMiner xmlMiner = new XMLMiner();
        DataMiner pdfMiner = new PDFMiner();

        // Same template method call — different behaviors!
        MiningReport r1 = csvMiner.mine("sales_data.csv");
        MiningReport r2 = xmlMiner.mine("products.xml");
        MiningReport r3 = pdfMiner.mine("invoices.pdf");

        System.out.println("\n=== Summary ===");
        System.out.println(r1);
        System.out.println(r2);
        System.out.println(r3);
    }
}
```

### Output

```
=== Mining: sales_data.csv ===
[CSV] Opening file: sales_data.csv
[CSV] Extracting data (splitting by comma)...
[Base] Parsing 5 rows...
[Base] Parsed 4 valid records.
[CSV] Filtering records with short names...
[Base] Analyzing data...
[CSV] Closing file.

=== Mining: products.xml ===
[XML] Parsing XML document: products.xml
[XML] Extracting elements from XML nodes...
[Base] Parsing 4 rows...
[Base] Parsed 4 valid records.
[Base] Analyzing data...
[XML] Releasing XML document.
[XML] 📧 Sending report notification: Report[source=products.xml | total=4 | categories=3]

=== Mining: invoices.pdf ===
[PDF] Loading PDF with OCR engine: invoices.pdf
[PDF] Running OCR and table extraction...
[Base] Parsing 3 rows...
[Base] Parsed 3 valid records.
[Base] Analyzing data...
[PDF] Closing PDF engine.

=== Summary ===
Report[source=sales_data.csv | total=3 | categories=3]
Report[source=products.xml | total=4 | categories=3]
Report[source=invoices.pdf | total=3 | categories=1]
```

---

## Java Example 2 — Beverage Preparation

```java
public abstract class Beverage {

    // Template Method
    public final void prepare() {
        boilWater();
        brew();
        pourInCup();
        if (customerWantsCondiments()) { // hook controls flow
            addCondiments();
        }
    }

    protected void boilWater() {
        System.out.println("Boiling water...");
    }

    protected void pourInCup() {
        System.out.println("Pouring into cup...");
    }

    protected abstract void brew();
    protected abstract void addCondiments();

    // Hook — subclass can override to customize flow
    protected boolean customerWantsCondiments() {
        return true; // default: yes
    }
}

public class Tea extends Beverage {
    @Override
    protected void brew() { System.out.println("Steeping the tea..."); }

    @Override
    protected void addCondiments() { System.out.println("Adding lemon..."); }
}

public class Coffee extends Beverage {
    @Override
    protected void brew() { System.out.println("Dripping coffee through filter..."); }

    @Override
    protected void addCondiments() { System.out.println("Adding sugar and milk..."); }

    // Override hook — ask user preference
    @Override
    protected boolean customerWantsCondiments() {
        Scanner scanner = new Scanner(System.in);
        System.out.print("Would you like milk and sugar? (y/n): ");
        return scanner.nextLine().equalsIgnoreCase("y");
    }
}

// Usage
Beverage tea    = new Tea();    tea.prepare();
Beverage coffee = new Coffee(); coffee.prepare();
```

---

## Real-World Java Examples

| Usage | Template Method |
|---|---|
| `java.util.AbstractList` | `get()` and `size()` abstract; `iterator()`, `indexOf()` concrete template methods |
| `java.io.InputStream` | `read(byte[], int, int)` template calls abstract `read()` |
| Spring's `JdbcTemplate` | `execute()` handles connection; you provide SQL + callback |
| Spring's `AbstractController` | `handleRequest()` is template; `handleRequestInternal()` abstract |
| `javax.servlet.http.HttpServlet` | `service()` template dispatches to `doGet()`, `doPost()` |

```java
// HttpServlet IS Template Method Pattern
public class MyServlet extends HttpServlet {
    // service() is the template method (defined in HttpServlet)
    // It calls doGet() or doPost() based on HTTP method

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        // Your implementation of the "doGet step"
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
        // Your implementation of the "doPost step"
    }
}
```

---

## Hollywood Principle

Template Method is the classic example of the **Hollywood Principle**:

> **"Don't call us, we'll call you."**

The base class controls when and in what order subclass methods are called. Subclasses don't call the template — the template calls them.

---

## Template Method vs Strategy

| Aspect | Template Method | Strategy |
|---|---|---|
| **Mechanism** | Inheritance | Composition |
| **When** | Compile-time | Runtime |
| **Granularity** | Steps of an algorithm | The whole algorithm |
| **Coupling** | Subclass tightly coupled to base | Context loosely coupled to strategy |
| **Change** | Subclass to change steps | Swap strategy object to change algorithm |

---

## Pros and Cons

### ✅ Advantages
- **Code reuse** — Common algorithm skeleton in one place
- **Inversion of control** — Base class controls flow; subclasses fill in steps
- **Open/Closed** — Add behavior by subclassing without changing base
- **Consistency** — Enforces algorithm structure across implementations

### ❌ Disadvantages
- **Inheritance coupling** — Subclasses tied to base class structure
- **Liskov issues** — Subclasses must respect base class contract
- **Can be limiting** — Base class skeleton is rigid; hard to reorder steps
- **Deep hierarchies** — Can get confusing with many levels of inheritance

---

## When to Use

✔ When you have an algorithm with invariant parts and variable steps  
✔ When you want to avoid code duplication in subclasses  
✔ When you want to control which parts of an algorithm subclasses can override  
✔ Frameworks where you define how things run but users fill in the specifics  

---

## Key Takeaway

> **"Define the plan, delegate the details."**  
> Template Method fixes the algorithm's skeleton in the base class and lets subclasses fill in the blanks — the Hollywood Principle in action: the base class calls you, not the other way around.
