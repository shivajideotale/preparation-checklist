# ☕ Java Exception Handling — Deep Dive Complete Guide

> `java.lang.Throwable` hierarchy — Java 8 through Java 21

---

## 📌 Table of Contents

1. [What is an Exception?](#1-what-is-an-exception)
2. [Exception Hierarchy](#2-exception-hierarchy)
3. [Checked vs Unchecked Exceptions](#3-checked-vs-unchecked-exceptions)
4. [try-catch-finally](#4-try-catch-finally)
5. [Multi-catch & Exception Ordering](#5-multi-catch--exception-ordering)
6. [finally Block — Deep Dive](#6-finally-block--deep-dive)
7. [try-with-resources](#7-try-with-resources)
8. [throw vs throws](#8-throw-vs-throws)
9. [Custom Exceptions](#9-custom-exceptions)
10. [Exception Chaining & Causes](#10-exception-chaining--causes)
11. [Stack Trace — Reading & Analyzing](#11-stack-trace--reading--analyzing)
12. [Common Built-in Exceptions](#12-common-built-in-exceptions)
13. [Best Practices](#13-best-practices)
14. [Anti-Patterns — What NOT To Do](#14-anti-patterns--what-not-to-do)
15. [Exception Handling in Streams & Lambdas](#15-exception-handling-in-streams--lambdas)
16. [Exception Handling in Multithreading](#16-exception-handling-in-multithreading)
17. [Logging Exceptions](#17-logging-exceptions)
18. [Real-World Patterns](#18-real-world-patterns)
19. [Interview Questions & Answers](#19-interview-questions--answers)
20. [Complete Reference Summary](#20-complete-reference-summary)

---

## 1. What is an Exception?

An **exception** is an event that disrupts the normal flow of program execution. When an error occurs, the JVM creates an **Exception object** containing information about the error and throws it. If not handled, the JVM prints a stack trace and terminates the program.

```
Normal flow:
  main() → methodA() → methodB() → methodC()
                                       ↓
                                   SUCCESS

Exceptional flow:
  main() → methodA() → methodB() → methodC()
                                       ↓
                                   EXCEPTION THROWN
                                       ↓
               Propagates UP the call stack until caught
                                       ↓
                         methodB() catches it → handles
                                       ↓
                              Continues execution
```

### Why Exception Handling?

```java
// Without exception handling — crashes on any error
public static int divide(int a, int b) {
    return a / b;  // If b=0 → JVM crashes with ArithmeticException
}

// With exception handling — graceful recovery
public static int divide(int a, int b) {
    if (b == 0) throw new ArithmeticException("Division by zero");
    return a / b;
}

// With try-catch — handled at call site
try {
    int result = divide(10, 0);
} catch (ArithmeticException e) {
    System.err.println("Cannot divide by zero: " + e.getMessage());
    // Program continues...
}
```

---

## 2. Exception Hierarchy

```
java.lang.Object
    │
    └── java.lang.Throwable                     ← Root of all exceptions
            │
            ├── java.lang.Error                 ← Serious JVM errors (don't catch!)
            │       ├── OutOfMemoryError
            │       ├── StackOverflowError
            │       ├── VirtualMachineError
            │       ├── AssertionError
            │       └── LinkageError
            │               └── NoClassDefFoundError
            │
            └── java.lang.Exception             ← Application-level exceptions
                    │
                    ├── java.lang.RuntimeException    ← UNCHECKED (no declare/catch required)
                    │       ├── NullPointerException
                    │       ├── ArrayIndexOutOfBoundsException
                    │       ├── ClassCastException
                    │       ├── ArithmeticException
                    │       ├── IllegalArgumentException
                    │       │       └── NumberFormatException
                    │       ├── IllegalStateException
                    │       ├── UnsupportedOperationException
                    │       ├── ConcurrentModificationException
                    │       └── StackOverflowError (from recursion)
                    │
                    └── (all other Exception subclasses) ← CHECKED (must declare or catch)
                            ├── IOException
                            │       ├── FileNotFoundException
                            │       ├── EOFException
                            │       └── SocketException
                            ├── SQLException
                            ├── ClassNotFoundException
                            ├── InterruptedException
                            ├── ParseException
                            └── CloneNotSupportedException
```

### Throwable Key Methods

```java
Throwable t = new RuntimeException("Something went wrong");

t.getMessage();          // "Something went wrong" — the error message
t.getCause();            // null or the causing Throwable (exception chaining)
t.getClass().getName();  // "java.lang.RuntimeException"
t.toString();            // "java.lang.RuntimeException: Something went wrong"
t.getStackTrace();       // StackTraceElement[] array
t.printStackTrace();     // Prints stack trace to System.err
t.printStackTrace(System.out); // Print to any PrintStream
t.initCause(cause);      // Set cause (if not set in constructor)

// Java 7+
t.getSuppressed();       // Suppressed exceptions (from try-with-resources)
t.addSuppressed(other);  // Add suppressed exception
```

---

## 3. Checked vs Unchecked Exceptions

This is one of the most important distinctions in Java exception handling.

```
CHECKED Exception:
  → Extends Exception (but NOT RuntimeException)
  → Compiler FORCES you to either:
      a) catch it in a try-catch block, OR
      b) declare it in method signature with throws

UNCHECKED Exception:
  → Extends RuntimeException (or Error)
  → Compiler does NOT force handling
  → Usually indicates programming bugs

Error:
  → Extends Error
  → JVM-level problems — should NEVER be caught
  → OutOfMemoryError, StackOverflowError
```

---

### Checked Exception — Must Handle

```java
import java.io.*;
import java.nio.file.*;

public class CheckedDemo {

    // ❌ Compiler error if you don't handle IOException
    static String readFile(String path) throws IOException {
        // Declaring with 'throws' — caller must handle
        return Files.readString(Path.of(path));
    }

    // ✅ Option 1: Declare with throws (propagate to caller)
    static void processFile1(String path) throws IOException {
        String content = readFile(path);
        System.out.println("Content: " + content);
    }

    // ✅ Option 2: Handle with try-catch (swallow here)
    static void processFile2(String path) {
        try {
            String content = readFile(path);
            System.out.println("Content: " + content);
        } catch (IOException e) {
            System.err.println("File error: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        // Must handle because processFile1 throws IOException
        try {
            processFile1("data.txt");
        } catch (IOException e) {
            System.err.println("Failed to process file: " + e.getMessage());
        }

        // No try-catch needed — processFile2 handles internally
        processFile2("data.txt");
    }
}
```

---

### Unchecked Exception — Optional Handling

```java
public class UncheckedDemo {

    // No 'throws' needed — unchecked exceptions don't require it
    static int divide(int a, int b) {
        if (b == 0) {
            throw new ArithmeticException("Cannot divide by zero");
        }
        return a / b;
    }

    static String getChar(String s, int idx) {
        // This can throw IndexOutOfBoundsException — unchecked, no declaration needed
        return String.valueOf(s.charAt(idx));
    }

    public static void main(String[] args) {

        // ── NullPointerException ──────────────────────────────────────────────
        String s = null;
        try {
            int len = s.length(); // NPE
        } catch (NullPointerException e) {
            System.out.println("NPE caught: " + e.getMessage());
        }

        // ── ArrayIndexOutOfBoundsException ────────────────────────────────────
        int[] arr = {1, 2, 3};
        try {
            int val = arr[5]; // Index out of bounds
        } catch (ArrayIndexOutOfBoundsException e) {
            System.out.println("Array bounds: " + e.getMessage());
        }

        // ── ClassCastException ────────────────────────────────────────────────
        Object obj = "Hello";
        try {
            Integer num = (Integer) obj; // Can't cast String to Integer
        } catch (ClassCastException e) {
            System.out.println("Cast failed: " + e.getMessage());
        }

        // ── NumberFormatException ─────────────────────────────────────────────
        try {
            int num = Integer.parseInt("not-a-number");
        } catch (NumberFormatException e) {
            System.out.println("Parse failed: " + e.getMessage());
        }

        // ── StackOverflowError ────────────────────────────────────────────────
        try {
            infiniteRecursion();
        } catch (StackOverflowError e) {
            System.out.println("Stack overflow caught!");
        }
    }

    static void infiniteRecursion() {
        infiniteRecursion(); // Calls itself forever
    }
}
```

---

### Checked vs Unchecked — When to Use Which

```
Use CHECKED exceptions when:
  ✅ The caller can reasonably recover from the error
  ✅ It's an external resource problem (file, network, DB)
  ✅ You want to FORCE callers to acknowledge and handle the error
  Examples: FileNotFoundException, SQLException, IOException

Use UNCHECKED exceptions when:
  ✅ It's a programming bug (wrong input, null where not expected)
  ✅ Recovery is not meaningful at the call site
  ✅ Every method in the call chain would need to declare it
  Examples: NullPointerException, IllegalArgumentException, IllegalStateException
```

---

## 4. try-catch-finally

The fundamental structure for exception handling.

### Basic Syntax and Flow

```java
try {
    // Code that might throw an exception
} catch (ExceptionType1 e) {
    // Handle ExceptionType1
} catch (ExceptionType2 e) {
    // Handle ExceptionType2
} finally {
    // ALWAYS runs (exception or not)
    // Use for cleanup: close resources, reset state
}
```

---

### Complete Flow Demo

```java
public class TryCatchFlowDemo {

    static int riskyMethod(int x) {
        System.out.println("  riskyMethod: start, x=" + x);
        if (x == 0) throw new ArithmeticException("x cannot be zero");
        if (x < 0)  throw new IllegalArgumentException("x must be positive, got: " + x);
        System.out.println("  riskyMethod: success, returning " + (100 / x));
        return 100 / x;
    }

    static void demonstrate(int x) {
        System.out.println("\n--- demonstrate(" + x + ") ---");
        try {
            System.out.println("try: before call");
            int result = riskyMethod(x);
            System.out.println("try: after call, result=" + result); // Skipped if exception
        } catch (ArithmeticException e) {
            System.out.println("catch ArithmeticException: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            System.out.println("catch IllegalArgumentException: " + e.getMessage());
        } finally {
            System.out.println("finally: always executes!");
        }
        System.out.println("After try-catch-finally: continuing...");
    }

    public static void main(String[] args) {
        demonstrate(5);    // Normal flow
        demonstrate(0);    // ArithmeticException
        demonstrate(-3);   // IllegalArgumentException
    }
}
```

**Output:**
```
--- demonstrate(5) ---
try: before call
  riskyMethod: start, x=5
  riskyMethod: success, returning 20
try: after call, result=20
finally: always executes!
After try-catch-finally: continuing...

--- demonstrate(0) ---
try: before call
  riskyMethod: start, x=0
catch ArithmeticException: x cannot be zero
finally: always executes!
After try-catch-finally: continuing...

--- demonstrate(-3) ---
try: before call
  riskyMethod: start, x=-3
catch IllegalArgumentException: x must be positive, got: -3
finally: always executes!
After try-catch-finally: continuing...
```

---

### Catching Multiple Exception Types

```java
import java.io.*;

public class MultiCatchDemo {
    public static void main(String[] args) {

        String[] inputs = {"42", "hello", null, "99"};

        for (String input : inputs) {
            try {
                // Multiple things that can go wrong
                int value = Integer.parseInt(input);   // NumberFormatException
                int result = 1000 / value;             // ArithmeticException
                System.out.println(input + " → " + result);

            } catch (NullPointerException e) {
                System.out.println("Input was null!");
            } catch (NumberFormatException e) {
                System.out.println("'" + input + "' is not a number");
            } catch (ArithmeticException e) {
                System.out.println("Cannot divide by " + input);
            }
        }
    }
}
```

**Output:**
```
42 → 23
'hello' is not a number
Input was null!
99 → 10
```

---

## 5. Multi-catch & Exception Ordering

### Multi-catch (Java 7+) — Handle Multiple Types the Same Way

```java
import java.io.*;
import java.sql.*;

public class MultiCatchDemo {
    public static void main(String[] args) {

        // ── Java 7+ Multi-catch ───────────────────────────────────────────────
        // When multiple exceptions share the same handling logic
        try {
            String input = getUserInput();         // Could be null
            int id = Integer.parseInt(input);      // NumberFormatException
            loadFromDatabase(id);                  // SQLException
        } catch (NumberFormatException | SQLException e) {
            // Single handler for both types
            System.err.println("Data error: " + e.getMessage());
            logError(e);
        }

        // ── The same WITHOUT multi-catch ─────────────────────────────────────
        try {
            // ...
        } catch (NumberFormatException e) {
            System.err.println("Data error: " + e.getMessage());
            logError(e);
        } catch (SQLException e) {
            System.err.println("Data error: " + e.getMessage());
            logError(e);
        }
        // Multi-catch is cleaner and avoids duplication ✅

        // ── Note: Multi-catch variable is implicitly final ────────────────────
        try {
            // ...
        } catch (NumberFormatException | IllegalArgumentException e) {
            // e = new RuntimeException("test"); // Compile error! 'e' is final here
            System.out.println(e.getMessage());
        }
    }

    static String getUserInput()  { return null; }
    static void loadFromDatabase(int id) throws SQLException {}
    static void logError(Exception e) {}
}
```

---

### Exception Ordering — Most Specific FIRST

```java
public class ExceptionOrderDemo {

    // ❌ WRONG ORDER — compiler error
    // More specific subclass after its superclass is unreachable!
    static void wrongOrder() {
        try {
            throw new FileNotFoundException("file.txt");
        } catch (IOException e) {       // IOException is PARENT of FileNotFoundException
            System.out.println("IO error");
        } catch (FileNotFoundException e) { // ❌ UNREACHABLE — compiler error!
            System.out.println("File not found");
        }
    }

    // ✅ CORRECT ORDER — most specific first
    static void correctOrder(String filename) {
        try {
            readFile(filename);
        } catch (FileNotFoundException e) { // Child first
            System.out.println("File not found: " + e.getMessage());
        } catch (IOException e) {           // Parent second
            System.out.println("IO error: " + e.getMessage());
        } catch (Exception e) {             // Most general last
            System.out.println("Unexpected: " + e.getMessage());
        }
    }

    // ✅ Exception hierarchy example
    static void hierarchyDemo() {
        try {
            throw new NumberFormatException("bad number");
        } catch (NumberFormatException e) { // Caught here (most specific)
            System.out.println("NumberFormatException: " + e.getMessage());
        } catch (IllegalArgumentException e) { // Would catch if above not present
            System.out.println("IllegalArgumentException");
        } catch (RuntimeException e) {         // Would catch if above not present
            System.out.println("RuntimeException");
        }
    }

    static void readFile(String name) throws IOException {
        throw new FileNotFoundException(name + " not found");
    }
}
```

---

## 6. finally Block — Deep Dive

`finally` **always** executes — whether the try block completes normally, throws an exception, or even executes a `return` statement.

```java
public class FinallyDeepDive {

    // ── finally runs even with return in try ─────────────────────────────────
    static String withReturn() {
        try {
            System.out.println("try: executing");
            return "from try"; // return is prepared but NOT executed yet
        } finally {
            System.out.println("finally: runs before return!");
            // "from try" is the final returned value
        }
    }

    // ── finally return OVERRIDES try return ───────────────────────────────────
    static String finallyOverridesReturn() {
        try {
            return "from try"; // Prepared, but...
        } finally {
            return "from finally"; // ← This REPLACES the try return!
        }
    }

    // ── finally runs even with System.exit? ─────────────────────────────────
    // System.exit() is the ONE case where finally does NOT run!
    static void withSystemExit() {
        try {
            System.out.println("try");
            System.exit(0); // JVM terminates — finally is SKIPPED
        } finally {
            System.out.println("finally: NEVER RUNS after System.exit()");
        }
    }

    // ── finally for cleanup ───────────────────────────────────────────────────
    static void withCleanup() {
        Connection conn = null;
        try {
            conn = openConnection();
            processData(conn);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.close(); // Close even if exception occurred
                } catch (Exception closeEx) {
                    System.err.println("Error closing: " + closeEx.getMessage());
                }
            }
        }
    }

    // ── finally exception SUPPRESSES try exception ────────────────────────────
    static void finallyThrows() throws Exception {
        try {
            throw new RuntimeException("Exception from try");
        } finally {
            throw new RuntimeException("Exception from finally");
            // The try exception is LOST! Only finally's exception propagates
            // This is why try-with-resources is better — it uses addSuppressed()
        }
    }

    public static void main(String[] args) {
        System.out.println(withReturn());             // finally: runs before return! → from try
        System.out.println(finallyOverridesReturn()); // from finally (try's return overridden!)
    }

    static Connection openConnection() throws Exception { return new Connection(); }
    static void processData(Connection c) throws Exception {}

    static class Connection {
        void close() throws Exception { System.out.println("Connection closed"); }
    }
}
```

---

## 7. try-with-resources

Java 7+ — Automatically closes resources that implement `AutoCloseable`. Eliminates finally boilerplate and correctly handles exceptions.

### The Problem Without try-with-resources

```java
// ❌ Old way — verbose and fragile
BufferedReader reader = null;
try {
    reader = new BufferedReader(new FileReader("file.txt"));
    String line = reader.readLine();
    System.out.println(line);
} catch (IOException e) {
    System.err.println("IO Error: " + e.getMessage());
} finally {
    if (reader != null) {
        try {
            reader.close(); // close() can also throw IOException!
        } catch (IOException closeEx) {
            System.err.println("Close error: " + closeEx.getMessage());
            // Original exception from try block is now LOST!
        }
    }
}
```

### try-with-resources — Clean Solution

```java
import java.io.*;

public class TryWithResourcesDemo {

    // ✅ Single resource
    static void singleResource() {
        try (BufferedReader reader = new BufferedReader(new FileReader("file.txt"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        } catch (IOException e) {
            System.err.println("IO error: " + e.getMessage());
        }
        // reader.close() called automatically — even if exception thrown!
    }

    // ✅ Multiple resources — closed in REVERSE order of declaration
    static void multipleResources() {
        try (
            FileInputStream  fis    = new FileInputStream("input.txt");  // Opened first
            FileOutputStream fos    = new FileOutputStream("output.txt"); // Opened second
            BufferedReader   reader = new BufferedReader(new InputStreamReader(fis));
            BufferedWriter   writer = new BufferedWriter(new OutputStreamWriter(fos))
        ) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line.toUpperCase());
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
        }
        // Closed in order: writer → reader → fos → fis
    }

    // ✅ try-with-resources with finally (Java 7+)
    static void withFinally() throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader("file.txt"))) {
            processReader(reader);
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
        } finally {
            System.out.println("Finally block — runs after close()");
        }
    }

    // ✅ Effectively-final variable in try-with-resources (Java 9+)
    static void java9Style() throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader("file.txt")); // Existing variable
        try (reader) {  // Java 9+ — use existing variable directly (must be effectively final)
            System.out.println(reader.readLine());
        }
        // reader.close() called automatically
    }

    static void processReader(BufferedReader r) throws IOException {}
}
```

---

### Suppressed Exceptions — How try-with-resources Handles Them

```java
public class SuppressedDemo {

    // Custom AutoCloseable
    static class Resource implements AutoCloseable {
        final String name;

        Resource(String name) {
            this.name = name;
            System.out.println("Opening: " + name);
        }

        void use() {
            System.out.println("Using: " + name);
            if (name.equals("bad")) throw new RuntimeException("Error USING " + name);
        }

        @Override
        public void close() {
            System.out.println("Closing: " + name);
            if (name.equals("bad")) throw new RuntimeException("Error CLOSING " + name);
        }
    }

    public static void main(String[] args) {

        // ── When BOTH try block AND close() throw ────────────────────────────
        // try-with-resources: primary exception propagates, close() exception is SUPPRESSED
        try (Resource r = new Resource("bad")) {
            r.use();  // Throws "Error USING bad"
        } catch (RuntimeException e) {
            System.out.println("\nPrimary exception: " + e.getMessage());
            // "Error CLOSING bad" is stored as suppressed
            for (Throwable suppressed : e.getSuppressed()) {
                System.out.println("Suppressed: " + suppressed.getMessage());
            }
        }

        System.out.println();

        // ── Multiple resources — all closed, exceptions suppressed ────────────
        try (
            Resource r1 = new Resource("good");
            Resource r2 = new Resource("bad");  // close() throws
            Resource r3 = new Resource("good2")
        ) {
            r1.use();
            r2.use(); // throws
            r3.use();
        } catch (RuntimeException e) {
            System.out.println("Primary: " + e.getMessage());
            for (Throwable s : e.getSuppressed()) {
                System.out.println("Suppressed: " + s.getMessage());
            }
        }
    }
}
```

**Output:**
```
Opening: bad
Using: bad
Closing: bad

Primary exception: Error USING bad
Suppressed: Error CLOSING bad
```

---

### Custom AutoCloseable Resource

```java
public class CustomResource implements AutoCloseable {

    private final String name;
    private boolean closed = false;

    public CustomResource(String name) {
        this.name = name;
        System.out.println("[" + name + "] opened");
    }

    public void doWork() {
        if (closed) throw new IllegalStateException("Resource already closed: " + name);
        System.out.println("[" + name + "] working...");
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            System.out.println("[" + name + "] closed");
        }
    }

    public static void main(String[] args) {
        try (
            CustomResource db  = new CustomResource("Database");
            CustomResource http = new CustomResource("HttpClient")
        ) {
            db.doWork();
            http.doWork();
            // Simulate error
            throw new RuntimeException("Business logic error");
        } catch (RuntimeException e) {
            System.out.println("Caught: " + e.getMessage());
        }
        // HttpClient closed first, then Database (reverse declaration order)
    }
}
```

**Output:**
```
[Database] opened
[HttpClient] opened
[Database] working...
[HttpClient] working...
[HttpClient] closed
[Database] closed
Caught: Business logic error
```

---

## 8. throw vs throws

```
throw  — used to THROW an exception (action — inside method body)
throws — used to DECLARE that a method CAN throw (declaration — in method signature)
```

```java
import java.io.*;

public class ThrowVsThrowsDemo {

    // 'throws' in signature — declares this method may throw these exceptions
    static void validateAge(int age) throws IllegalArgumentException {
        if (age < 0)   throw new IllegalArgumentException("Age cannot be negative: " + age);
        if (age > 150) throw new IllegalArgumentException("Age cannot exceed 150: " + age);
        System.out.println("Valid age: " + age);
    }

    // 'throws' for checked exception
    static String readConfig(String path) throws IOException {
        if (!new File(path).exists()) {
            throw new FileNotFoundException("Config not found: " + path);
        }
        return new String(new java.io.FileInputStream(path).readAllBytes());
    }

    // Multiple throws declarations
    static void complexMethod(String path, int age)
            throws IOException, IllegalArgumentException {
        validateAge(age);
        readConfig(path);
    }

    // throw inside conditional logic
    static double calculateBMI(double weightKg, double heightM) {
        if (weightKg <= 0) throw new IllegalArgumentException("Weight must be positive");
        if (heightM  <= 0) throw new IllegalArgumentException("Height must be positive");
        if (heightM  >  3) throw new IllegalArgumentException("Height > 3m seems wrong");
        return weightKg / (heightM * heightM);
    }

    // Re-throwing an exception
    static void processData(String data) throws Exception {
        try {
            int value = Integer.parseInt(data);
            System.out.println("Parsed: " + value);
        } catch (NumberFormatException e) {
            System.err.println("Logging error: " + e.getMessage());
            throw e; // Re-throw the same exception (caller must handle)
        }
    }

    // Re-throwing as different type (wrapping)
    static void loadUser(int id) throws ServiceException {
        try {
            // dbOperation(id); // throws SQLException
        } catch (Exception e) {
            throw new ServiceException("Failed to load user " + id, e); // Wrap
        }
    }

    public static void main(String[] args) {
        // throw usage
        try {
            validateAge(-5);
        } catch (IllegalArgumentException e) {
            System.out.println("Validation failed: " + e.getMessage());
        }

        try {
            double bmi = calculateBMI(70, 1.75);
            System.out.printf("BMI: %.2f%n", bmi);
        } catch (IllegalArgumentException e) {
            System.out.println("Bad input: " + e.getMessage());
        }
    }

    static class ServiceException extends Exception {
        ServiceException(String msg, Throwable cause) { super(msg, cause); }
    }
}
```

---

## 9. Custom Exceptions

Creating custom exceptions makes your code more expressive and provides richer error context.

### Custom Checked Exception

```java
// Custom checked exception — extends Exception
public class InsufficientFundsException extends Exception {

    private final double balance;
    private final double amount;

    // Constructor 1: message only
    public InsufficientFundsException(String message) {
        super(message);
        this.balance = 0;
        this.amount  = 0;
    }

    // Constructor 2: with context fields
    public InsufficientFundsException(double balance, double amount) {
        super(String.format(
            "Insufficient funds: balance=%.2f, attempted withdrawal=%.2f, shortfall=%.2f",
            balance, amount, amount - balance
        ));
        this.balance = balance;
        this.amount  = amount;
    }

    // Constructor 3: with cause (for exception chaining)
    public InsufficientFundsException(String message, Throwable cause) {
        super(message, cause);
        this.balance = 0;
        this.amount  = 0;
    }

    // Custom getters for richer context
    public double getBalance()   { return balance; }
    public double getAmount()    { return amount; }
    public double getShortfall() { return amount - balance; }
}
```

### Custom Unchecked Exception

```java
// Custom unchecked exception — extends RuntimeException
public class UserNotFoundException extends RuntimeException {

    private final int userId;

    public UserNotFoundException(int userId) {
        super("User not found with ID: " + userId);
        this.userId = userId;
    }

    public UserNotFoundException(int userId, Throwable cause) {
        super("User not found with ID: " + userId, cause);
        this.userId = userId;
    }

    public int getUserId() { return userId; }
}
```

### Exception Hierarchy for a Banking Application

```java
// Base exception for the domain
public class BankingException extends Exception {
    private final String errorCode;

    public BankingException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BankingException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() { return errorCode; }
}

// Specific banking exceptions
public class AccountNotFoundException    extends BankingException {
    public AccountNotFoundException(String accountId) {
        super("ACC_NOT_FOUND", "Account not found: " + accountId);
    }
}

public class AccountFrozenException      extends BankingException {
    public AccountFrozenException(String accountId) {
        super("ACC_FROZEN", "Account is frozen: " + accountId);
    }
}

public class TransactionLimitException   extends BankingException {
    private final double limit;
    public TransactionLimitException(double limit, double attempted) {
        super("TXN_LIMIT", String.format("Transaction %.2f exceeds limit %.2f", attempted, limit));
        this.limit = limit;
    }
    public double getLimit() { return limit; }
}
```

### Using Custom Exceptions

```java
public class BankAccount {
    private final String id;
    private double balance;
    private boolean frozen;

    BankAccount(String id, double initialBalance) {
        this.id      = id;
        this.balance = initialBalance;
    }

    public void withdraw(double amount) throws InsufficientFundsException, AccountFrozenException {
        if (frozen) {
            throw new AccountFrozenException(id);
        }
        if (amount > balance) {
            throw new InsufficientFundsException(balance, amount);
        }
        balance -= amount;
        System.out.printf("Withdrew %.2f — new balance: %.2f%n", amount, balance);
    }

    public void freeze() { this.frozen = true; }

    public static void main(String[] args) {
        BankAccount account = new BankAccount("ACC-001", 500.00);

        // Normal withdrawal
        try {
            account.withdraw(200.00);
        } catch (InsufficientFundsException | AccountFrozenException e) {
            System.err.println("Withdrawal failed: " + e.getMessage());
        }

        // Insufficient funds
        try {
            account.withdraw(1000.00);
        } catch (InsufficientFundsException e) {
            System.err.printf("Failed: shortfall=%.2f%n", e.getShortfall());
        } catch (AccountFrozenException e) {
            System.err.println("Account frozen: " + e.getMessage());
        }

        // Frozen account
        account.freeze();
        try {
            account.withdraw(50.00);
        } catch (InsufficientFundsException | AccountFrozenException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
}
```

---

## 10. Exception Chaining & Causes

Exception chaining preserves the original cause when wrapping exceptions — critical for debugging.

```java
import java.sql.*;
import java.io.*;

public class ExceptionChainingDemo {

    // ── Service layer wraps repository exception ──────────────────────────────
    static class DatabaseException extends RuntimeException {
        DatabaseException(String msg, Throwable cause) { super(msg, cause); }
    }

    static class ServiceException extends RuntimeException {
        ServiceException(String msg, Throwable cause) { super(msg, cause); }
    }

    // Repository layer — throws low-level exception
    static void repositoryFindUser(int id) throws SQLException {
        throw new SQLException("Connection timeout to db-server:5432", "08001", 1040);
    }

    // Service layer — wraps repository exception
    static void serviceFindUser(int id) {
        try {
            repositoryFindUser(id);
        } catch (SQLException e) {
            // Wrap in domain exception — preserve original cause!
            throw new DatabaseException("Failed to query user table", e);
        }
    }

    // Controller layer — wraps service exception
    static void controllerGetUser(int id) {
        try {
            serviceFindUser(id);
        } catch (DatabaseException e) {
            throw new ServiceException("User service unavailable for id=" + id, e);
        }
    }

    public static void main(String[] args) {
        try {
            controllerGetUser(42);
        } catch (ServiceException e) {

            System.out.println("=== Exception Chain ===");
            System.out.println("Layer 3: " + e.getMessage());

            Throwable cause = e.getCause();
            if (cause != null) {
                System.out.println("Layer 2: " + cause.getMessage());

                Throwable rootCause = cause.getCause();
                if (rootCause != null) {
                    System.out.println("Layer 1: " + rootCause.getMessage());
                }
            }

            // Full chain traversal
            System.out.println("\n=== Full Chain Traversal ===");
            Throwable current = e;
            int depth = 0;
            while (current != null) {
                System.out.printf("  [%d] %s: %s%n",
                    depth++, current.getClass().getSimpleName(), current.getMessage());
                current = current.getCause();
            }

            System.out.println("\n=== Full Stack Trace ===");
            e.printStackTrace();
        }
    }
}
```

**Output:**
```
=== Exception Chain ===
Layer 3: User service unavailable for id=42
Layer 2: Failed to query user table
Layer 1: Connection timeout to db-server:5432

=== Full Chain Traversal ===
  [0] ServiceException: User service unavailable for id=42
  [1] DatabaseException: Failed to query user table
  [2] SQLException: Connection timeout to db-server:5432

=== Full Stack Trace ===
ServiceException: User service unavailable for id=42
    at ExceptionChainingDemo.controllerGetUser(...)
    at ExceptionChainingDemo.main(...)
Caused by: DatabaseException: Failed to query user table
    at ExceptionChainingDemo.serviceFindUser(...)
    ...
Caused by: java.sql.SQLException: Connection timeout to db-server:5432
    at ExceptionChainingDemo.repositoryFindUser(...)
    ...
```

---

## 11. Stack Trace — Reading & Analyzing

```java
public class StackTraceDemo {

    static void methodC() {
        throw new RuntimeException("Error in methodC");
    }

    static void methodB() {
        methodC(); // Calls C, exception propagates here
    }

    static void methodA() {
        methodB(); // Calls B, exception propagates here
    }

    public static void main(String[] args) {
        try {
            methodA();
        } catch (RuntimeException e) {

            // Print to stderr (default)
            e.printStackTrace();

            // Print to stdout
            e.printStackTrace(System.out);

            // Get as string (useful for logging)
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            String stackTraceStr = sw.toString();

            // Access individual frames
            System.out.println("\n=== Stack Frames ===");
            StackTraceElement[] frames = e.getStackTrace();
            for (StackTraceElement frame : frames) {
                System.out.printf("  %s.%s(%s:%d)%n",
                    frame.getClassName(),
                    frame.getMethodName(),
                    frame.getFileName(),
                    frame.getLineNumber()
                );
            }

            // Get the bottom of the call stack (root method)
            StackTraceElement root = frames[frames.length - 1];
            System.out.println("\nRoot caller: " + root.getMethodName());
        }
    }
}
```

**Reading a Stack Trace:**
```
java.lang.RuntimeException: Error in methodC            ← Exception type: message
    at StackTraceDemo.methodC(StackTraceDemo.java:4)    ← WHERE it was thrown (top = throw site)
    at StackTraceDemo.methodB(StackTraceDemo.java:8)    ← Who called methodC
    at StackTraceDemo.methodA(StackTraceDemo.java:12)   ← Who called methodB
    at StackTraceDemo.main(StackTraceDemo.java:17)      ← Entry point (bottom = main)
```

---

## 12. Common Built-in Exceptions

### NullPointerException — Most Common

```java
public class NPEDemo {
    record User(String name, Address address) {}
    record Address(String city, String zip) {}

    public static void main(String[] args) {

        User user1 = new User("Alice", new Address("NY", "10001"));
        User user2 = new User("Bob", null);    // address is null
        User user3 = null;                     // entire user is null

        // ── Traditional NPE-prone code ────────────────────────────────────────
        try {
            System.out.println(user2.address().city()); // NPE — address is null
        } catch (NullPointerException e) {
            System.out.println("NPE: " + e.getMessage()); // JDK 14+: helpful NPE message!
            // "Cannot invoke "StackTraceDemo$Address.city()" because
            //  the return value of "StackTraceDemo$User.address()" is null"
        }

        // ── Safe approaches ───────────────────────────────────────────────────
        // 1. Null check
        if (user2 != null && user2.address() != null) {
            System.out.println(user2.address().city());
        }

        // 2. Optional chain
        java.util.Optional.ofNullable(user2)
            .map(User::address)
            .map(Address::city)
            .ifPresentOrElse(
                city -> System.out.println("City: " + city),
                ()   -> System.out.println("No city available")
            );

        // 3. Objects.requireNonNull — fail fast with better message
        try {
            java.util.Objects.requireNonNull(user3, "User cannot be null");
        } catch (NullPointerException e) {
            System.out.println("Validation: " + e.getMessage());
        }

        // 4. getOrDefault pattern
        String city = user2.address() != null ? user2.address().city() : "Unknown";
        System.out.println("City: " + city);
    }
}
```

---

### StackOverflowError — Infinite Recursion

```java
public class StackOverflowDemo {

    // ❌ Infinite recursion
    static int badFactorial(int n) {
        return n * badFactorial(n - 1); // Missing base case!
    }

    // ✅ Correct with base case
    static long factorial(int n) {
        if (n <= 1) return 1; // Base case
        return n * factorial(n - 1);
    }

    // ✅ Iterative (no stack risk)
    static long factorialIterative(int n) {
        long result = 1;
        for (int i = 2; i <= n; i++) result *= i;
        return result;
    }

    public static void main(String[] args) {
        try {
            badFactorial(10);
        } catch (StackOverflowError e) {
            System.out.println("StackOverflow caught — missing base case!");
        }

        System.out.println("10! = " + factorial(10));           // 3628800
        System.out.println("10! = " + factorialIterative(10));  // 3628800
    }
}
```

---

### ClassCastException — Type Safety

```java
import java.util.*;

public class ClassCastDemo {
    public static void main(String[] args) {

        // ❌ Without generics (raw types) — runtime ClassCastException
        List rawList = new ArrayList();
        rawList.add("hello");
        rawList.add(42);

        try {
            String s = (String) rawList.get(1); // 42 is not a String!
        } catch (ClassCastException e) {
            System.out.println("ClassCast: " + e.getMessage());
        }

        // ✅ Use instanceof before casting
        Object obj = "Hello World";
        if (obj instanceof String s) {         // Java 16+ pattern matching
            System.out.println("Length: " + s.length());
        }

        // ✅ Use generics to prevent ClassCastException at compile time
        List<String> typedList = new ArrayList<>();
        typedList.add("hello");
        // typedList.add(42); // Compile error — caught early!
        String s = typedList.get(0); // No cast needed
    }
}
```

---

### ConcurrentModificationException

```java
import java.util.*;

public class CMEDemo {
    public static void main(String[] args) {

        List<Integer> numbers = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6));

        // ❌ Modifying while iterating with for-each
        try {
            for (Integer n : numbers) {
                if (n % 2 == 0) numbers.remove(n); // CME!
            }
        } catch (ConcurrentModificationException e) {
            System.out.println("CME caught!");
        }

        // ✅ Fix 1: removeIf (Java 8+)
        numbers.removeIf(n -> n % 2 == 0);
        System.out.println("After removeIf: " + numbers); // [1, 3, 5]

        // ✅ Fix 2: Iterator.remove()
        numbers = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6));
        Iterator<Integer> it = numbers.iterator();
        while (it.hasNext()) {
            if (it.next() % 2 == 0) it.remove();
        }
        System.out.println("After iterator remove: " + numbers); // [1, 3, 5]

        // ✅ Fix 3: Collect first, then remove
        numbers = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6));
        List<Integer> toRemove = new ArrayList<>();
        for (Integer n : numbers) {
            if (n % 2 == 0) toRemove.add(n);
        }
        numbers.removeAll(toRemove);
        System.out.println("After removeAll: " + numbers); // [1, 3, 5]
    }
}
```

---

## 13. Best Practices

### 1. Be Specific — Catch the Right Exception

```java
// ❌ Too broad — hides bugs
try {
    process();
} catch (Exception e) {
    log(e); // Catches NPE, ClassCastException, your bugs too!
}

// ✅ Specific — only handle what you expect
try {
    process();
} catch (IOException e) {
    log("IO failed", e);
    retry();
} catch (ParseException e) {
    log("Parse failed", e);
    returnDefaultValue();
}
```

---

### 2. Never Swallow Exceptions Silently

```java
// ❌ Silent swallow — exceptions disappear without a trace
try {
    importantOperation();
} catch (Exception e) {
    // Empty catch — DON'T DO THIS!
}

// ✅ At minimum, log it
try {
    importantOperation();
} catch (Exception e) {
    logger.error("importantOperation failed", e); // Always log!
    // Then decide: rethrow? return default? notify?
}
```

---

### 3. Wrap Exceptions with Context

```java
// ❌ Re-throw without context — loses information
try {
    userRepository.save(user);
} catch (SQLException e) {
    throw new RuntimeException(e); // What was being saved? Which user?
}

// ✅ Add context when wrapping
try {
    userRepository.save(user);
} catch (SQLException e) {
    throw new ServiceException(
        "Failed to save user id=" + user.getId() + " name=" + user.getName(), e
    );
}
```

---

### 4. Use Unchecked for Programming Errors

```java
// ✅ Use unchecked for validation (caller made a mistake)
public void setAge(int age) {
    if (age < 0 || age > 150) {
        throw new IllegalArgumentException("Invalid age: " + age);
    }
    this.age = age;
}

// ✅ Use unchecked for impossible states
public Status getStatus() {
    return switch (code) {
        case 1 -> Status.ACTIVE;
        case 2 -> Status.INACTIVE;
        default -> throw new IllegalStateException("Unknown status code: " + code);
    };
}
```

---

### 5. Early Validation — Fail Fast

```java
// ✅ Validate parameters at method entry — fail fast with clear message
public void transferMoney(Account from, Account to, double amount) {
    Objects.requireNonNull(from,   "Source account cannot be null");
    Objects.requireNonNull(to,     "Destination account cannot be null");
    if (amount <= 0) throw new IllegalArgumentException("Amount must be positive: " + amount);
    if (from.equals(to)) throw new IllegalArgumentException("Cannot transfer to same account");

    // Business logic — we know inputs are valid here
    from.debit(amount);
    to.credit(amount);
}
```

---

### 6. Document Exceptions with Javadoc

```java
/**
 * Reads configuration from the specified path.
 *
 * @param path the path to the configuration file
 * @return the configuration content
 * @throws FileNotFoundException if the file does not exist at {@code path}
 * @throws IOException           if an I/O error occurs while reading
 * @throws IllegalArgumentException if {@code path} is null or blank
 */
public String readConfig(String path) throws IOException {
    if (path == null || path.isBlank()) {
        throw new IllegalArgumentException("Path cannot be null or blank");
    }
    return Files.readString(Path.of(path));
}
```

---

## 14. Anti-Patterns — What NOT To Do

### ❌ Anti-Pattern 1: Catch and Ignore

```java
// ❌ NEVER do this
try {
    Thread.sleep(1000);
} catch (InterruptedException e) {
    // Empty — interrupt signal is LOST!
}

// ✅ Restore the interrupt flag
try {
    Thread.sleep(1000);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt(); // Restore interrupt status
    throw new RuntimeException("Sleep interrupted", e); // or handle
}
```

---

### ❌ Anti-Pattern 2: Exception for Control Flow

```java
// ❌ Using exceptions for normal control flow (very slow!)
public boolean userExists(int id) {
    try {
        userRepository.findById(id); // Throws if not found
        return true;
    } catch (UserNotFoundException e) {
        return false; // Using exception for normal boolean result!
    }
}

// ✅ Use a method that returns Optional or boolean
public boolean userExists(int id) {
    return userRepository.existsById(id); // Simple boolean check
}

public Optional<User> findUser(int id) {
    return userRepository.findById(id); // Returns Optional — no exception
}
```

---

### ❌ Anti-Pattern 3: Catching Throwable or Error

```java
// ❌ Never catch Throwable or Error in application code
try {
    riskyOperation();
} catch (Throwable t) {    // Catches OutOfMemoryError, StackOverflowError!
    log(t);
    continue; // The JVM is in an unknown state — continuing is dangerous!
}

// ✅ Catch specific exceptions
try {
    riskyOperation();
} catch (SpecificException e) {
    // Handle known exception types only
}
```

---

### ❌ Anti-Pattern 4: Losing the Original Cause

```java
// ❌ Cause is lost — no way to debug root cause
try {
    dbOperation();
} catch (SQLException e) {
    throw new ServiceException("DB failed"); // Cause lost!
}

// ✅ Always chain the cause
try {
    dbOperation();
} catch (SQLException e) {
    throw new ServiceException("DB failed", e); // Cause preserved ✅
}
```

---

### ❌ Anti-Pattern 5: Using finally for Return Value

```java
// ❌ finally return overrides try return — confusing and error-prone
String riskyMethod() {
    try {
        return "try result";
    } finally {
        return "finally result"; // Overrides try result silently!
    }
}
// Returns "finally result" — the try return is SILENTLY LOST

// ✅ Don't return from finally
String betterMethod() {
    String result = "default";
    try {
        result = "try result";
    } finally {
        // Only cleanup here, no return
        cleanup();
    }
    return result; // Explicitly return after try-finally
}
```

---

## 15. Exception Handling in Streams & Lambdas

Lambdas make exception handling awkward because functional interfaces don't declare checked exceptions.

```java
import java.util.*;
import java.util.stream.*;
import java.io.*;
import java.nio.file.*;

public class StreamExceptionDemo {

    // ── Problem: lambda can't throw checked exceptions ────────────────────────
    // ❌ Won't compile — Files.readString throws IOException (checked)
    /*
    List<String> contents = paths.stream()
        .map(p -> Files.readString(p))  // Compile error!
        .collect(Collectors.toList());
    */

    // ── Solution 1: Wrap in unchecked exception ───────────────────────────────
    @FunctionalInterface
    interface CheckedFunction<T, R> {
        R apply(T t) throws Exception;
    }

    static <T, R> java.util.function.Function<T, R> wrap(CheckedFunction<T, R> fn) {
        return t -> {
            try {
                return fn.apply(t);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    // ── Solution 2: Map to Optional — skip errors silently ───────────────────
    static Optional<String> safeReadFile(Path path) {
        try {
            return Optional.of(Files.readString(path));
        } catch (IOException e) {
            System.err.println("Skipping unreadable file: " + path);
            return Optional.empty();
        }
    }

    // ── Solution 3: Result type — Either<Error, Success> pattern ─────────────
    record Result<T>(T value, Exception error) {
        static <T> Result<T> success(T value) { return new Result<>(value, null); }
        static <T> Result<T> failure(Exception e) { return new Result<>(null, e); }
        boolean isSuccess() { return error == null; }
    }

    static Result<String> tryReadFile(Path path) {
        try {
            return Result.success(Files.readString(path));
        } catch (IOException e) {
            return Result.failure(e);
        }
    }

    public static void main(String[] args) throws IOException {
        List<Path> paths = List.of(
            Path.of("file1.txt"),
            Path.of("file2.txt"),
            Path.of("missing.txt")
        );

        // Using wrap() — exceptions become RuntimeExceptions in stream
        /*
        List<String> contents = paths.stream()
            .map(wrap(Files::readString)) // OK now — checked → unchecked
            .collect(Collectors.toList());
        */

        // Using safeReadFile — skip errors, process successes
        List<String> contents = paths.stream()
            .map(StreamExceptionDemo::safeReadFile)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(Collectors.toList());
        System.out.println("Read " + contents.size() + " files");

        // Using Result type — collect all results, then handle errors separately
        List<Result<String>> results = paths.stream()
            .map(StreamExceptionDemo::tryReadFile)
            .collect(Collectors.toList());

        results.stream().filter(Result::isSuccess)
               .forEach(r -> System.out.println("Content: " + r.value()));
        results.stream().filter(r -> !r.isSuccess())
               .forEach(r -> System.err.println("Error: " + r.error().getMessage()));
    }
}
```

---

## 16. Exception Handling in Multithreading

```java
import java.util.concurrent.*;

public class ThreadExceptionDemo {

    // ── Uncaught exceptions in threads ────────────────────────────────────────
    public static void main(String[] args) throws Exception {

        // Default: uncaught exceptions in threads print to stderr, thread terminates
        Thread t1 = new Thread(() -> {
            throw new RuntimeException("Thread 1 crashed!");
        });
        t1.start();
        t1.join();
        System.out.println("Main continues after thread 1 (exception was in thread 1)");

        // ── UncaughtExceptionHandler ──────────────────────────────────────────
        Thread t2 = new Thread(() -> {
            throw new RuntimeException("Thread 2 crashed!");
        });
        t2.setUncaughtExceptionHandler((thread, ex) -> {
            System.err.println("Thread " + thread.getName() + " failed: " + ex.getMessage());
            // Log, alert, restart, etc.
        });
        t2.start();
        t2.join();

        // ── Global default handler ────────────────────────────────────────────
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            System.err.println("[GLOBAL HANDLER] " + thread.getName() + ": " + ex.getMessage());
        });

        // ── ExecutorService — exceptions in submitted tasks ───────────────────
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // submit() — exceptions captured in Future
        Future<?> future = pool.submit(() -> {
            throw new RuntimeException("Task failed in pool");
        });

        try {
            future.get(); // ExecutionException wraps the original exception
        } catch (ExecutionException e) {
            System.out.println("Task exception: " + e.getCause().getMessage());
        }

        // execute() — exceptions go to UncaughtExceptionHandler (or print to stderr)
        pool.execute(() -> {
            throw new RuntimeException("execute() task failed — UncaughtExceptionHandler called");
        });

        // ── Callable — checked exceptions from threads ────────────────────────
        Future<String> callableFuture = pool.submit(() -> {
            // Callable can throw checked exceptions!
            if (true) throw new IOException("Network error in callable");
            return "success";
        });

        try {
            String result = callableFuture.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioEx) {
                System.out.println("IO error: " + ioEx.getMessage());
            }
        }

        pool.shutdown();
    }
}
```

---

## 17. Logging Exceptions

```java
import java.util.logging.*;
import java.io.*;

public class LoggingExceptionDemo {

    private static final Logger logger = Logger.getLogger(LoggingExceptionDemo.class.getName());

    // ── java.util.logging ────────────────────────────────────────────────────
    static void withJUL() {
        try {
            throw new IOException("Connection refused");
        } catch (IOException e) {
            // ❌ Bad — only logs message, no stack trace
            logger.severe("IO error: " + e.getMessage());

            // ✅ Good — logs with full stack trace
            logger.log(Level.SEVERE, "IO error occurred", e);
        }
    }

    // ── Structured exception logging pattern ─────────────────────────────────
    static void logException(String operation, Exception e) {
        logger.log(Level.SEVERE,
            String.format("Operation '%s' failed: %s [%s]",
                operation,
                e.getMessage(),
                e.getClass().getSimpleName()),
            e // Stack trace appended
        );
    }

    // ── Getting stack trace as string (for logging frameworks) ───────────────
    static String stackTraceToString(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    // ── Logging best practices ────────────────────────────────────────────────
    static void processingExample(String userId) {
        try {
            processUser(userId);
        } catch (IllegalArgumentException e) {
            // Expected validation error — log as WARNING with context
            logger.warning("Validation failed for userId=" + userId + ": " + e.getMessage());
        } catch (RuntimeException e) {
            // Unexpected error — log as SEVERE with full trace
            logger.log(Level.SEVERE, "Unexpected error processing userId=" + userId, e);
            throw e; // Re-throw — don't swallow unexpected errors
        }
    }

    static void processUser(String id) {
        if (id == null) throw new IllegalArgumentException("userId is null");
    }
}
```

---

## 18. Real-World Patterns

### Pattern 1: Repository with Exception Hierarchy

```java
import java.util.*;

// Exception hierarchy
class AppException extends RuntimeException {
    final String code;
    AppException(String code, String msg)              { super(msg); this.code = code; }
    AppException(String code, String msg, Throwable c) { super(msg, c); this.code = code; }
}
class NotFoundException       extends AppException {
    NotFoundException(String msg)  { super("NOT_FOUND", msg); }
}
class ValidationException      extends AppException {
    ValidationException(String msg){ super("VALIDATION", msg); }
}
class DataAccessException       extends AppException {
    DataAccessException(String m, Throwable c) { super("DATA_ERROR", m, c); }
}

// Domain model
record User(int id, String name, String email) {}

// Repository
class UserRepository {
    private final Map<Integer, User> store = new HashMap<>(Map.of(
        1, new User(1, "Alice", "alice@example.com"),
        2, new User(2, "Bob",   "bob@example.com")
    ));

    User findById(int id) {
        if (id <= 0) throw new ValidationException("User ID must be positive, got: " + id);
        User u = store.get(id);
        if (u == null) throw new NotFoundException("User not found: id=" + id);
        return u;
    }

    User save(User user) {
        Objects.requireNonNull(user, "User cannot be null");
        if (user.name() == null || user.name().isBlank())
            throw new ValidationException("User name is required");
        if (user.email() == null || !user.email().contains("@"))
            throw new ValidationException("Invalid email: " + user.email());
        try {
            store.put(user.id(), user);
            return user;
        } catch (Exception e) {
            throw new DataAccessException("Failed to save user: " + user.id(), e);
        }
    }
}

// Service
class UserService {
    private final UserRepository repo;

    UserService(UserRepository repo) { this.repo = repo; }

    Optional<User> findUser(int id) {
        try {
            return Optional.of(repo.findById(id));
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }

    User getUser(int id) {
        return repo.findById(id); // Let NotFoundException propagate
    }

    User createUser(int id, String name, String email) {
        // Check duplicate
        try {
            repo.findById(id);
            throw new ValidationException("User already exists: id=" + id);
        } catch (NotFoundException ignored) {
            // Expected — user doesn't exist, proceed with creation
        }
        return repo.save(new User(id, name, email));
    }
}

// Controller / Main
public class RealWorldPattern {
    public static void main(String[] args) {
        UserRepository repo    = new UserRepository();
        UserService    service = new UserService(repo);

        // Find existing
        service.findUser(1).ifPresentOrElse(
            u -> System.out.println("Found: " + u.name()),
            ()  -> System.out.println("Not found")
        );

        // Find missing — returns empty, no exception
        service.findUser(99).ifPresentOrElse(
            u -> System.out.println("Found: " + u.name()),
            ()  -> System.out.println("User 99: not found")
        );

        // Create user
        try {
            User created = service.createUser(3, "Carol", "carol@example.com");
            System.out.println("Created: " + created);
        } catch (ValidationException e) {
            System.err.println("Validation: " + e.getMessage());
        }

        // Validation error
        try {
            service.createUser(4, "", "bad-email");
        } catch (ValidationException e) {
            System.err.println("Validation error: [" + e.code + "] " + e.getMessage());
        }
    }
}
```

---

### Pattern 2: Retry with Exception Handling

```java
import java.util.function.*;
import java.util.concurrent.*;

public class RetryPattern {

    static <T> T retry(
            Supplier<T> operation,
            int maxAttempts,
            long delayMs,
            Class<? extends Exception>... retryOn) throws Exception {

        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                T result = operation.get();
                if (attempt > 1) {
                    System.out.printf("Succeeded on attempt %d%n", attempt);
                }
                return result;
            } catch (Exception e) {
                boolean shouldRetry = false;
                for (Class<? extends Exception> retryClass : retryOn) {
                    if (retryClass.isAssignableFrom(e.getClass())) {
                        shouldRetry = true;
                        break;
                    }
                }

                if (!shouldRetry || attempt == maxAttempts) {
                    throw e;
                }

                lastException = e;
                System.out.printf("Attempt %d/%d failed: %s. Retrying in %dms...%n",
                    attempt, maxAttempts, e.getMessage(), delayMs);

                TimeUnit.MILLISECONDS.sleep(delayMs);
            }
        }
        throw lastException;
    }

    static int callCount = 0;

    static String unreliableService() {
        callCount++;
        if (callCount < 3) throw new RuntimeException("Service temporarily unavailable");
        return "Success after " + callCount + " attempts";
    }

    public static void main(String[] args) throws Exception {
        String result = retry(
            RetryPattern::unreliableService,
            5,         // max attempts
            200,       // delay between retries in ms
            RuntimeException.class
        );
        System.out.println("Result: " + result);
    }
}
```

---

### Pattern 3: Global Exception Handler (Web-style)

```java
import java.util.*;

public class GlobalExceptionHandler {

    record ErrorResponse(String code, String message, Map<String, String> details) {}

    static ErrorResponse handle(Exception e) {
        return switch (e) {
            case NotFoundException ex ->
                new ErrorResponse("NOT_FOUND", ex.getMessage(), Map.of());

            case ValidationException ex ->
                new ErrorResponse("VALIDATION_ERROR", ex.getMessage(),
                    Map.of("field", "value"));

            case DataAccessException ex ->
                new ErrorResponse("DB_ERROR",
                    "A database error occurred. Please try again.",
                    Map.of("internal", ex.getMessage())); // Don't expose internals!

            case IllegalArgumentException ex ->
                new ErrorResponse("BAD_REQUEST", ex.getMessage(), Map.of());

            default ->
                new ErrorResponse("INTERNAL_ERROR",
                    "An unexpected error occurred.",
                    Map.of("type", e.getClass().getSimpleName()));
        };
    }

    public static void main(String[] args) {
        List<Exception> exceptions = List.of(
            new NotFoundException("User not found: id=99"),
            new ValidationException("Email is required"),
            new RuntimeException("Unexpected NullPointerException")
        );

        for (Exception e : exceptions) {
            ErrorResponse response = handle(e);
            System.out.printf("[%s] %s%n", response.code(), response.message());
        }
    }
}
```

---

## 19. Interview Questions & Answers

| # | Question | Answer |
|---|----------|--------|
| 1 | What is the difference between `Exception` and `Error`? | `Exception` = application-level problems (recoverable). `Error` = JVM-level problems (OutOfMemoryError, StackOverflowError) — should NOT be caught in application code. |
| 2 | Checked vs Unchecked exceptions? | Checked: extends Exception (not RuntimeException) — compiler forces handling. Unchecked: extends RuntimeException — no forced handling. |
| 3 | Can you `throw` a checked exception without `throws`? | No — compiler error. You must either declare it with `throws` in the method signature or catch it inside the method. |
| 4 | Does `finally` always execute? | Yes — except when `System.exit()` is called or the JVM crashes. Even after `return` in try/catch, finally runs before the return takes effect. |
| 5 | What if both try and finally throw exceptions? | The finally exception propagates; the try exception is LOST. This is why try-with-resources uses `addSuppressed()` — both exceptions are preserved. |
| 6 | `throw e` vs `throw new Exception(e)` when re-throwing? | `throw e` preserves the original stack trace. `throw new Exception(e)` adds a new wrapper — use when you want to add context; chain the original cause. |
| 7 | What is exception chaining? | Setting the `cause` of one exception to another (`new Exception("msg", originalCause)`). Preserves the full error chain for debugging. |
| 8 | What is try-with-resources? | Java 7+ — automatically calls `close()` on `AutoCloseable` resources. Resources closed in reverse declaration order. Close exceptions are suppressed, not lost. |
| 9 | What are suppressed exceptions? | Exceptions thrown by `close()` in try-with-resources when there's already a primary exception. Added via `addSuppressed()`, accessible via `getSuppressed()`. |
| 10 | Can you have try without catch? | Yes — `try-finally` (no catch) is valid. Also try-with-resources without catch is valid in some contexts. |
| 11 | Can constructors throw exceptions? | Yes — constructors can throw both checked and unchecked exceptions. This is used for validation (fail fast). |
| 12 | `final` vs `finally` vs `finalize()`? | `final` = keyword (immutable). `finally` = block that always runs. `finalize()` = deprecated method called before GC (don't use). |
| 13 | How to handle exceptions in lambdas/streams? | Wrap checked exceptions in unchecked (custom wrapper function), use Optional to skip errors, or use a Result type to collect errors. |
| 14 | What happens to exceptions in threads? | Uncaught exceptions print to stderr and terminate the thread. Set `UncaughtExceptionHandler` on the thread or use a global default handler. |
| 15 | What is `Objects.requireNonNull()`? | Static helper that throws NullPointerException with a useful message if the argument is null. Use for fail-fast parameter validation. |
| 16 | `multi-catch` (Java 7)? | `catch (IOException | SQLException e)` — handle multiple exception types with one catch block. The variable `e` is implicitly final. |
| 17 | Should you catch `RuntimeException`? | Only at high-level boundaries (e.g., request handlers) for logging/recovery. Don't catch RuntimeException in lower layers — it hides bugs. |
| 18 | What is the NoSuchElementException? | Thrown when accessing an element that doesn't exist (e.g., `Optional.get()` on empty, `Iterator.next()` when `hasNext()` is false). |
| 19 | When to create custom exceptions? | When: you need to add domain-specific context, callers need to distinguish your error type, you want to enforce a hierarchy for a module. |
| 20 | What is the benefit of specific catch blocks over `catch(Exception)`? | Specific catches: document intent, allow different recovery strategies, prevent catching bugs as normal errors, make the code self-documenting. |

---

## 20. Complete Reference Summary

### Exception Handling Quick Reference

```java
// Basic structure
try {
    // risky code
} catch (SpecificException e) {
    // handle
} catch (AnotherException | YetAnother e) { // Multi-catch (Java 7+)
    // handle both the same way
} finally {
    // always runs (cleanup)
}

// try-with-resources (Java 7+)
try (Resource r = new Resource()) {
    r.use();
} catch (Exception e) {
    // handle
}
// r.close() called automatically

// throw
throw new CustomException("message");
throw new CustomException("message", cause); // With chaining

// throws (method signature)
void method() throws IOException, SQLException { }

// Re-throw
catch (Exception e) {
    throw e;                             // Same exception, same stack trace
    throw new WrapperException("msg", e); // Wrapped, with cause
}

// Custom exception
class MyException extends Exception {
    MyException(String msg) { super(msg); }
    MyException(String msg, Throwable cause) { super(msg, cause); }
}
```

---

### Full Architecture Map

```
Java Exception Handling
│
├── Hierarchy
│   ├── Throwable
│   │   ├── Error (DON'T CATCH — JVM problems)
│   │   │   ├── OutOfMemoryError
│   │   │   └── StackOverflowError
│   │   └── Exception
│   │       ├── RuntimeException (UNCHECKED — optional handling)
│   │       │   ├── NullPointerException
│   │       │   ├── IllegalArgumentException
│   │       │   ├── IndexOutOfBoundsException
│   │       │   └── ...
│   │       └── (others) (CHECKED — mandatory handling)
│   │           ├── IOException
│   │           ├── SQLException
│   │           └── ...
│
├── Handling Mechanisms
│   ├── try-catch-finally       — basic structure
│   ├── try-with-resources      — auto-close (Java 7+)
│   ├── multi-catch             — |  syntax (Java 7+)
│   ├── throw                   — raise exception
│   └── throws                  — declare in signature
│
├── Exception Chaining
│   ├── new Exception("msg", cause)
│   ├── initCause(e)
│   ├── getCause()
│   └── getSuppressed()         — try-with-resources
│
├── Best Practices
│   ├── Catch specific exceptions
│   ├── Never swallow silently
│   ├── Chain cause when wrapping
│   ├── Fail fast with validation
│   ├── Use unchecked for bugs
│   └── Document with @throws
│
├── Anti-Patterns
│   ├── Empty catch blocks
│   ├── Catching Exception/Throwable broadly
│   ├── Exceptions for control flow
│   ├── Losing cause when wrapping
│   └── return from finally
│
└── Advanced
    ├── Streams — wrap checked in unchecked
    ├── Threads — UncaughtExceptionHandler
    ├── Custom exception hierarchy
    ├── Result/Either pattern
    └── Global exception handling
```

---

*Made with ❤️ for Java developers — covers Java 7 through Java 21*
