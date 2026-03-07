# ☕ Java Streams — Deep Dive Complete Guide

> Java 8+ `java.util.stream` — Functional-style operations on sequences of elements

---

## 📌 Table of Contents

1. [What is a Stream?](#1-what-is-a-stream)
2. [Stream Pipeline Architecture](#2-stream-pipeline-architecture)
3. [Creating Streams — All Ways](#3-creating-streams--all-ways)
4. [Intermediate Operations](#4-intermediate-operations)
5. [Terminal Operations](#5-terminal-operations)
6. [Collectors — Deep Dive](#6-collectors--deep-dive)
7. [FlatMap — Flattening Streams](#7-flatmap--flattening-streams)
8. [Optional — Null-Safe Streams](#8-optional--null-safe-streams)
9. [Primitive Streams](#9-primitive-streams)
10. [Parallel Streams](#10-parallel-streams)
11. [Infinite Streams](#11-infinite-streams)
12. [Stream of Custom Objects — Real World](#12-stream-of-custom-objects--real-world)
13. [Method References](#13-method-references)
14. [Comparator with Streams](#14-comparator-with-streams)
15. [Grouping, Partitioning & Summarizing](#15-grouping-partitioning--summarizing)
16. [Stream Laziness — How It Works](#16-stream-laziness--how-it-works)
17. [Common Mistakes & Pitfalls](#17-common-mistakes--pitfalls)
18. [Java 9–21 Stream Enhancements](#18-java-921-stream-enhancements)
19. [Interview Questions & Answers](#19-interview-questions--answers)
20. [Complete Reference Summary](#20-complete-reference-summary)

---

## 1. What is a Stream?

A **Stream** is a sequence of elements that supports **functional-style aggregate operations**. It is NOT a data structure — it does NOT store data. It processes data from a source (collection, array, I/O) in a **pipeline** of operations.

```
Traditional (Imperative) approach:
  List<String> result = new ArrayList<>();
  for (String name : names) {
      if (name.startsWith("A")) {
          result.add(name.toUpperCase());
      }
  }

Stream (Declarative) approach:
  List<String> result = names.stream()
      .filter(name -> name.startsWith("A"))
      .map(String::toUpperCase)
      .collect(Collectors.toList());
```

### Key Properties of Streams

| Property             | Description                                                            |
|----------------------|------------------------------------------------------------------------|
| **Not a structure**  | Streams don't store data — they process it from a source               |
| **Lazy**             | Intermediate operations don't execute until a terminal op is called    |
| **Consumable**       | A stream can only be traversed ONCE; after terminal op it is exhausted |
| **Possibly infinite**| `Stream.iterate()` and `Stream.generate()` produce infinite streams    |
| **Can be parallel**  | `.parallel()` switches to multi-threaded processing                    |
| **Functional**       | Operations don't modify the source; they produce new streams           |

---

## 2. Stream Pipeline Architecture

A Stream pipeline has **3 parts**:

```
Source ──► Intermediate Operations ──► Terminal Operation
              (lazy, return Stream)      (eager, triggers execution)

┌──────────┐    ┌────────┐    ┌────────┐    ┌────────┐    ┌──────────┐
│  Source  │───►│filter()│───►│ map()  │───►│sorted()│───►│collect() │
│          │    │(lazy)  │    │(lazy)  │    │(lazy)  │    │(TERMINAL)│
└──────────┘    └────────┘    └────────┘    └────────┘    └──────────┘
     │                                                           │
  No work                                               ALL work happens
  done yet!                                              RIGHT HERE
```

### Lazy Evaluation — Proof

```java
import java.util.stream.*;
import java.util.*;

public class LazyDemo {
    public static void main(String[] args) {

        List<Integer> numbers = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        // Build the pipeline — NO WORK DONE YET
        Stream<Integer> pipeline = numbers.stream()
            .filter(n -> {
                System.out.println("  filter: " + n);
                return n % 2 == 0;
            })
            .map(n -> {
                System.out.println("  map: " + n);
                return n * n;
            });

        System.out.println("Pipeline built — no work done yet.");
        System.out.println("Calling terminal operation...");

        // Terminal operation — TRIGGERS EXECUTION
        List<Integer> result = pipeline.collect(Collectors.toList());

        System.out.println("Result: " + result);
    }
}
```

**Output:**
```
Pipeline built — no work done yet.
Calling terminal operation...
  filter: 1
  filter: 2
  map: 2
  filter: 3
  filter: 4
  map: 4
  filter: 5
  filter: 6
  map: 6
  ...
Result: [4, 16, 36, 64, 100]
```

> Notice elements are processed **one at a time** through the FULL pipeline — NOT filter-all then map-all.

---

## 3. Creating Streams — All Ways

### From Collection

```java
import java.util.*;
import java.util.stream.*;

List<String> list = List.of("Apple", "Banana", "Cherry");
Stream<String> stream1 = list.stream();          // Sequential
Stream<String> stream2 = list.parallelStream();  // Parallel

Set<Integer> set = new HashSet<>(Set.of(1, 2, 3));
Stream<Integer> stream3 = set.stream();

Map<String, Integer> map = Map.of("a", 1, "b", 2);
Stream<Map.Entry<String, Integer>> stream4 = map.entrySet().stream();
```

---

### From Array

```java
import java.util.Arrays;
import java.util.stream.Stream;

String[] array = {"X", "Y", "Z"};

Stream<String>  s1 = Arrays.stream(array);          // Full array
Stream<String>  s2 = Arrays.stream(array, 0, 2);    // Subarray (indices 0, 1)
Stream<String>  s3 = Stream.of("X", "Y", "Z");      // Varargs
```

---

### From Values — `Stream.of()`

```java
Stream<String>  s1 = Stream.of("one", "two", "three");
Stream<Integer> s2 = Stream.of(10, 20, 30, 40, 50);
Stream<Object>  s3 = Stream.of(1, "hello", 3.14, true); // Mixed types
Stream<String>  empty = Stream.empty();                   // Empty stream
```

---

### From `Stream.builder()`

```java
Stream.Builder<String> builder = Stream.builder();
builder.add("First");
builder.add("Second");

if (true) builder.accept("Conditional");

Stream<String> stream = builder.build();
stream.forEach(System.out::println);
```

---

### From `Stream.iterate()` — Ordered sequences

```java
// iterate(seed, operator) — infinite
Stream.iterate(0, n -> n + 2)
      .limit(10)
      .forEach(System.out::println); // 0, 2, 4, 6, 8, 10, 12, 14, 16, 18

// iterate(seed, predicate, operator) — Java 9+, finite
Stream.iterate(1, n -> n <= 100, n -> n * 2)
      .forEach(System.out::println); // 1, 2, 4, 8, 16, 32, 64
```

---

### From `Stream.generate()` — Supplier-based

```java
import java.util.Random;

// Infinite stream of random numbers
Stream.generate(() -> new Random().nextInt(100))
      .limit(5)
      .forEach(System.out::println); // e.g. 42, 7, 83, 15, 91

// Infinite stream of a constant
Stream.generate(() -> "Hello")
      .limit(3)
      .forEach(System.out::println); // Hello Hello Hello

// Infinite UUID stream
Stream.generate(java.util.UUID::randomUUID)
      .limit(3)
      .forEach(System.out::println);
```

---

### From Strings and Files

```java
import java.util.stream.*;
import java.io.*;
import java.nio.file.*;

// From String — each character as IntStream
"Hello".chars()
       .forEach(c -> System.out.print((char) c + " ")); // H e l l o

// From String — split into words
Arrays.stream("The quick brown fox".split(" "))
      .forEach(System.out::println);

// From file — each line as a stream element (Java 8+)
try (Stream<String> lines = Files.lines(Path.of("data.txt"))) {
    lines.filter(line -> !line.isEmpty())
         .map(String::trim)
         .forEach(System.out::println);
} // Stream is AutoCloseable — always use try-with-resources for file streams!

// Walk directory tree (Java 8+)
try (Stream<Path> paths = Files.walk(Path.of("src"))) {
    paths.filter(Files::isRegularFile)
         .filter(p -> p.toString().endsWith(".java"))
         .forEach(System.out::println);
}
```

---

### From `IntStream.range()` and `IntStream.rangeClosed()`

```java
import java.util.stream.*;

IntStream.range(1, 6)       // 1, 2, 3, 4, 5 (exclusive end)
         .forEach(System.out::println);

IntStream.rangeClosed(1, 5) // 1, 2, 3, 4, 5 (inclusive end)
         .forEach(System.out::println);

// Useful for indexed iteration
List<String> items = List.of("A", "B", "C");
IntStream.range(0, items.size())
         .forEach(i -> System.out.println(i + " -> " + items.get(i)));
// Output: 0 -> A, 1 -> B, 2 -> C
```

---

### Concatenating Streams — `Stream.concat()`

```java
Stream<String> s1 = Stream.of("A", "B", "C");
Stream<String> s2 = Stream.of("D", "E", "F");

Stream<String> combined = Stream.concat(s1, s2);
combined.forEach(System.out::println); // A B C D E F
```

---

## 4. Intermediate Operations

Intermediate operations are **lazy** — they return a new `Stream` and do not execute until a terminal operation is called.

---

### `filter(Predicate)` — Keep elements matching condition

```java
import java.util.*;
import java.util.stream.*;

List<Integer> numbers = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

// Filter even numbers
List<Integer> evens = numbers.stream()
    .filter(n -> n % 2 == 0)
    .collect(Collectors.toList());
System.out.println(evens); // [2, 4, 6, 8, 10]

// Filter strings by length
List<String> names = List.of("Ali", "Bob", "Charlotte", "Dan", "Elizabeth");

List<String> longNames = names.stream()
    .filter(name -> name.length() > 4)
    .collect(Collectors.toList());
System.out.println(longNames); // [Charlotte, Elizabeth]

// Multiple filters (chained)
List<Integer> result = numbers.stream()
    .filter(n -> n > 3)           // > 3
    .filter(n -> n % 2 != 0)      // odd
    .filter(n -> n < 9)           // < 9
    .collect(Collectors.toList());
System.out.println(result); // [5, 7]
```

---

### `map(Function)` — Transform each element

```java
List<String> names = List.of("alice", "bob", "charlie");

// Transform to uppercase
List<String> upper = names.stream()
    .map(String::toUpperCase)
    .collect(Collectors.toList());
System.out.println(upper); // [ALICE, BOB, CHARLIE]

// Transform to length
List<Integer> lengths = names.stream()
    .map(String::length)
    .collect(Collectors.toList());
System.out.println(lengths); // [5, 3, 7]

// Transform String to custom object
record Person(String name, int length) {}

List<Person> people = names.stream()
    .map(name -> new Person(name, name.length()))
    .collect(Collectors.toList());
System.out.println(people); // [Person[name=alice, length=5], ...]

// Transform numbers
List<Integer> numbers = List.of(1, 2, 3, 4, 5);
List<Integer> squared = numbers.stream()
    .map(n -> n * n)
    .collect(Collectors.toList());
System.out.println(squared); // [1, 4, 9, 16, 25]
```

---

### `sorted()` and `sorted(Comparator)` — Sort stream elements

```java
List<Integer> numbers = List.of(5, 3, 8, 1, 9, 2, 7, 4, 6);

// Natural order (ascending)
List<Integer> asc = numbers.stream()
    .sorted()
    .collect(Collectors.toList());
System.out.println(asc); // [1, 2, 3, 4, 5, 6, 7, 8, 9]

// Reverse order
List<Integer> desc = numbers.stream()
    .sorted(Comparator.reverseOrder())
    .collect(Collectors.toList());
System.out.println(desc); // [9, 8, 7, 6, 5, 4, 3, 2, 1]

// Sort strings by length, then alphabetically
List<String> names = List.of("banana", "apple", "fig", "date", "cherry", "kiwi");

List<String> sorted = names.stream()
    .sorted(Comparator.comparingInt(String::length)
                      .thenComparing(Comparator.naturalOrder()))
    .collect(Collectors.toList());
System.out.println(sorted); // [fig, date, kiwi, apple, banana, cherry]
```

---

### `distinct()` — Remove duplicates

```java
List<Integer> withDups = List.of(1, 2, 2, 3, 3, 3, 4, 5, 5);

List<Integer> unique = withDups.stream()
    .distinct()
    .collect(Collectors.toList());
System.out.println(unique); // [1, 2, 3, 4, 5]

// Distinct strings (uses .equals())
List<String> words = List.of("apple", "banana", "apple", "cherry", "banana");
List<String> uniqueWords = words.stream()
    .distinct()
    .collect(Collectors.toList());
System.out.println(uniqueWords); // [apple, banana, cherry]
```

---

### `limit(n)` and `skip(n)` — Pagination

```java
List<Integer> numbers = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

// Take first 5
List<Integer> first5 = numbers.stream()
    .limit(5)
    .collect(Collectors.toList());
System.out.println(first5); // [1, 2, 3, 4, 5]

// Skip first 5
List<Integer> last5 = numbers.stream()
    .skip(5)
    .collect(Collectors.toList());
System.out.println(last5); // [6, 7, 8, 9, 10]

// Pagination — page 2 (page size 3)
int pageSize = 3;
int pageNum  = 2; // 0-indexed

List<Integer> page2 = numbers.stream()
    .skip((long) pageNum * pageSize)  // skip 6
    .limit(pageSize)                   // take 3
    .collect(Collectors.toList());
System.out.println(page2); // [7, 8, 9]
```

---

### `peek(Consumer)` — Debug/inspect without consuming

```java
List<Integer> numbers = List.of(1, 2, 3, 4, 5);

List<Integer> result = numbers.stream()
    .filter(n -> n % 2 == 0)
    .peek(n -> System.out.println("  After filter: " + n))  // Debug
    .map(n -> n * 10)
    .peek(n -> System.out.println("  After map: " + n))     // Debug
    .collect(Collectors.toList());

System.out.println("Result: " + result);
```

**Output:**
```
  After filter: 2
  After map: 20
  After filter: 4
  After map: 40
Result: [20, 40]
```

---

### `mapToInt()`, `mapToLong()`, `mapToDouble()` — Primitive specializations

```java
List<String> words = List.of("hello", "world", "java", "streams");

// Get total character count (avoids boxing overhead)
int totalChars = words.stream()
    .mapToInt(String::length) // Returns IntStream, not Stream<Integer>
    .sum();
System.out.println("Total chars: " + totalChars); // 20

// Statistics
IntSummaryStatistics stats = words.stream()
    .mapToInt(String::length)
    .summaryStatistics();

System.out.println("Count  : " + stats.getCount());   // 4
System.out.println("Sum    : " + stats.getSum());     // 20
System.out.println("Min    : " + stats.getMin());     // 4
System.out.println("Max    : " + stats.getMax());     // 7
System.out.println("Average: " + stats.getAverage()); // 5.0
```

---

### `takeWhile()` and `dropWhile()` — Java 9+

```java
// takeWhile — take elements WHILE predicate is true; stop at first false
List<Integer> nums = List.of(2, 4, 6, 7, 8, 10); // 7 breaks the even streak

List<Integer> taken = nums.stream()
    .takeWhile(n -> n % 2 == 0) // Takes: 2, 4, 6 — stops at 7
    .collect(Collectors.toList());
System.out.println(taken); // [2, 4, 6]

// dropWhile — drop elements WHILE predicate is true; keep from first false
List<Integer> dropped = nums.stream()
    .dropWhile(n -> n % 2 == 0) // Drops: 2, 4, 6 — keeps from 7 onward
    .collect(Collectors.toList());
System.out.println(dropped); // [7, 8, 10]

// Practical: process log file after a marker line
List<String> lines = List.of("header1", "header2", "=== DATA START ===", "row1", "row2");
List<String> dataLines = lines.stream()
    .dropWhile(line -> !line.equals("=== DATA START ==="))
    .skip(1) // Skip the marker itself
    .collect(Collectors.toList());
System.out.println(dataLines); // [row1, row2]
```

---

## 5. Terminal Operations

Terminal operations **trigger** the pipeline execution and produce a result or side-effect.

---

### `collect()` — Accumulate into a container

*(See §6 for full Collectors deep-dive)*

```java
List<String> names = List.of("Alice", "Bob", "Charlie", "Diana");

List<String>       asList  = names.stream().collect(Collectors.toList());
Set<String>        asSet   = names.stream().collect(Collectors.toSet());
String             joined  = names.stream().collect(Collectors.joining(", "));
Map<Integer,List<String>> grouped = names.stream()
                                         .collect(Collectors.groupingBy(String::length));
```

---

### `forEach()` — Iterate with side-effects

```java
List<String> names = List.of("Alice", "Bob", "Charlie");

// Simple print
names.stream().forEach(System.out::println);

// With index (use AtomicInteger for counter in stream)
java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger(0);
names.stream().forEach(name ->
    System.out.println(idx.getAndIncrement() + ": " + name)
);
```

---

### `reduce()` — Combine elements into a single value

```java
List<Integer> numbers = List.of(1, 2, 3, 4, 5);

// reduce(identity, BinaryOperator)
int sum = numbers.stream()
    .reduce(0, Integer::sum);
System.out.println("Sum: " + sum); // 15

int product = numbers.stream()
    .reduce(1, (a, b) -> a * b);
System.out.println("Product: " + product); // 120

// reduce(BinaryOperator) — returns Optional (no identity value)
Optional<Integer> max = numbers.stream()
    .reduce((a, b) -> a > b ? a : b);
max.ifPresent(m -> System.out.println("Max: " + m)); // 5

// String concatenation
List<String> words = List.of("Java", " ", "Streams", " ", "Rock");
String sentence = words.stream()
    .reduce("", String::concat);
System.out.println(sentence); // Java Streams Rock
```

---

### `count()` — Count elements

```java
List<String> names = List.of("Alice", "Bob", "Charlie", "David", "Eve");

long count = names.stream()
    .filter(name -> name.length() > 3)
    .count();
System.out.println("Names longer than 3 chars: " + count); // 3
```

---

### `findFirst()` and `findAny()` — Return an element

```java
List<Integer> numbers = List.of(3, 7, 2, 8, 1, 5, 9, 4, 6);

// findFirst — returns first element matching filter (deterministic order)
Optional<Integer> first = numbers.stream()
    .filter(n -> n > 5)
    .findFirst();
first.ifPresent(n -> System.out.println("First > 5: " + n)); // 7

// findAny — returns any element (better for parallel streams, non-deterministic)
Optional<Integer> any = numbers.parallelStream()
    .filter(n -> n > 5)
    .findAny();
any.ifPresent(n -> System.out.println("Any > 5: " + n)); // Could be 7, 8, or 9
```

---

### `anyMatch()`, `allMatch()`, `noneMatch()` — Short-circuit boolean tests

```java
List<Integer> numbers = List.of(2, 4, 6, 8, 10);
List<String>  names   = List.of("Alice", "Bob", "Charlie");

// anyMatch — true if ANY element matches
boolean hasEven = numbers.stream().anyMatch(n -> n % 2 == 0);
System.out.println("Any even: " + hasEven); // true

boolean hasNegative = numbers.stream().anyMatch(n -> n < 0);
System.out.println("Any negative: " + hasNegative); // false

// allMatch — true if ALL elements match
boolean allEven = numbers.stream().allMatch(n -> n % 2 == 0);
System.out.println("All even: " + allEven); // true

boolean allLong = names.stream().allMatch(name -> name.length() > 5);
System.out.println("All long names: " + allLong); // false (Bob has length 3)

// noneMatch — true if NO element matches
boolean noneNegative = numbers.stream().noneMatch(n -> n < 0);
System.out.println("None negative: " + noneNegative); // true

// Short-circuiting: stops as soon as result is determined
// anyMatch stops at first TRUE, allMatch stops at first FALSE
```

---

### `min()` and `max()` — Find extremes

```java
List<Integer> numbers = List.of(5, 3, 8, 1, 9, 2, 7, 4, 6);

Optional<Integer> min = numbers.stream().min(Comparator.naturalOrder());
Optional<Integer> max = numbers.stream().max(Comparator.naturalOrder());

System.out.println("Min: " + min.get()); // 1
System.out.println("Max: " + max.get()); // 9

// With custom objects
List<String> words = List.of("banana", "apple", "cherry", "date");

Optional<String> shortest = words.stream()
    .min(Comparator.comparingInt(String::length));
Optional<String> longest = words.stream()
    .max(Comparator.comparingInt(String::length));

System.out.println("Shortest: " + shortest.get()); // date
System.out.println("Longest:  " + longest.get());  // banana/cherry
```

---

### `toArray()` — Collect to array

```java
List<String> names = List.of("Alice", "Bob", "Charlie");

// Object array
Object[] objArray = names.stream().toArray();

// Typed array (pass constructor reference)
String[] strArray = names.stream().toArray(String[]::new);

System.out.println(Arrays.toString(strArray)); // [Alice, Bob, Charlie]
```

---

### `sum()`, `average()`, `summaryStatistics()` — Numeric terminals

```java
// Only available on IntStream / LongStream / DoubleStream
int[] numbers = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

System.out.println("Sum    : " + Arrays.stream(numbers).sum());           // 55
System.out.println("Average: " + Arrays.stream(numbers).average().getAsDouble()); // 5.5
System.out.println("Min    : " + Arrays.stream(numbers).min().getAsInt()); // 1
System.out.println("Max    : " + Arrays.stream(numbers).max().getAsInt()); // 10

IntSummaryStatistics stats = Arrays.stream(numbers).summaryStatistics();
System.out.println(stats); // IntSummaryStatistics{count=10, sum=55, min=1, average=5.500000, max=10}
```

---

## 6. Collectors — Deep Dive

`Collectors` is a utility class with factory methods for the most common `collect()` use cases.

---

### `toList()`, `toSet()`, `toUnmodifiableList()`

```java
List<String> names = List.of("Alice", "Bob", "Charlie", "Alice");

// Mutable List
List<String> mutableList = names.stream()
    .collect(Collectors.toList()); // Mutable, allows duplicates

// Unmodifiable List (Java 10+)
List<String> immutableList = names.stream()
    .collect(Collectors.toUnmodifiableList());

// Unmodifiable Set (removes duplicates)
Set<String> uniqueNames = names.stream()
    .collect(Collectors.toUnmodifiableSet());
System.out.println(uniqueNames); // [Alice, Bob, Charlie]

// Java 16+ — Stream.toList() shorthand (always unmodifiable)
List<String> jdk16List = names.stream()
    .filter(n -> n.length() > 3)
    .toList(); // Shorthand for Collectors.toUnmodifiableList()
```

---

### `toMap()` — Collect to Map

```java
List<String> names = List.of("Alice", "Bob", "Charlie", "Diana");

// name → length
Map<String, Integer> nameToLength = names.stream()
    .collect(Collectors.toMap(
        name -> name,       // Key extractor
        String::length      // Value extractor
    ));
System.out.println(nameToLength); // {Alice=5, Bob=3, Charlie=7, Diana=5}

// With merge function (handle duplicate keys)
List<String> withDup = List.of("Alice", "Anna", "Bob", "Ben");
Map<Character, String> firstLetterMap = withDup.stream()
    .collect(Collectors.toMap(
        name -> name.charAt(0),          // Key: first letter
        name -> name,                    // Value: name
        (existing, newVal) -> existing + "," + newVal  // Merge duplicates
    ));
System.out.println(firstLetterMap); // {A=Alice,Anna, B=Bob,Ben}

// Collect to LinkedHashMap (preserve insertion order)
Map<String, Integer> ordered = names.stream()
    .collect(Collectors.toMap(
        name -> name,
        String::length,
        (a, b) -> a,          // Merge: keep first on duplicate
        java.util.LinkedHashMap::new  // Map factory
    ));
```

---

### `joining()` — Concatenate strings

```java
List<String> names = List.of("Alice", "Bob", "Charlie", "Diana");

// Simple join
String simple   = names.stream().collect(Collectors.joining());
System.out.println(simple); // AliceBobCharlieDiana

// With delimiter
String csv      = names.stream().collect(Collectors.joining(", "));
System.out.println(csv); // Alice, Bob, Charlie, Diana

// With delimiter, prefix, suffix
String wrapped  = names.stream().collect(Collectors.joining(", ", "[", "]"));
System.out.println(wrapped); // [Alice, Bob, Charlie, Diana]

// JSON array style
String json = names.stream()
    .map(n -> "\"" + n + "\"")
    .collect(Collectors.joining(", ", "[", "]"));
System.out.println(json); // ["Alice", "Bob", "Charlie", "Diana"]
```

---

### `groupingBy()` — Group elements by classifier

```java
import java.util.*;
import java.util.stream.*;

List<String> names = List.of("Alice", "Bob", "Ann", "Charlie", "Brian", "Carol");

// Group by first letter
Map<Character, List<String>> byLetter = names.stream()
    .collect(Collectors.groupingBy(name -> name.charAt(0)));
System.out.println(byLetter);
// {A=[Alice, Ann], B=[Bob, Brian], C=[Charlie, Carol]}

// Group by length
Map<Integer, List<String>> byLength = names.stream()
    .collect(Collectors.groupingBy(String::length));
System.out.println(byLength);
// {3=[Bob, Ann], 5=[Alice, Brian, Carol], 7=[Charlie]}

// Group by length → count (downstream collector)
Map<Integer, Long> countByLength = names.stream()
    .collect(Collectors.groupingBy(String::length, Collectors.counting()));
System.out.println(countByLength);
// {3=2, 5=3, 7=1}

// Group by length → names joined
Map<Integer, String> joinedByLength = names.stream()
    .collect(Collectors.groupingBy(String::length, Collectors.joining(", ")));
System.out.println(joinedByLength);
// {3=Bob, Ann, 5=Alice, Brian, Carol, 7=Charlie}

// Multi-level grouping: first by first letter, then by length
Map<Character, Map<Integer, List<String>>> multilevel = names.stream()
    .collect(Collectors.groupingBy(
        name -> name.charAt(0),
        Collectors.groupingBy(String::length)
    ));
System.out.println(multilevel);
// {A={3=[Ann], 5=[Alice]}, B={3=[Bob], 5=[Brian]}, C={5=[Carol], 7=[Charlie]}}
```

---

### `partitioningBy()` — Split into two groups

```java
List<Integer> numbers = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

// Partition into even / odd
Map<Boolean, List<Integer>> evenOdd = numbers.stream()
    .collect(Collectors.partitioningBy(n -> n % 2 == 0));
System.out.println("Even: " + evenOdd.get(true));   // [2, 4, 6, 8, 10]
System.out.println("Odd:  " + evenOdd.get(false));  // [1, 3, 5, 7, 9]

// Partition with counting
Map<Boolean, Long> counted = numbers.stream()
    .collect(Collectors.partitioningBy(
        n -> n > 5,
        Collectors.counting()
    ));
System.out.println(counted); // {false=5, true=5}
```

---

### `counting()`, `summingInt()`, `averagingInt()`

```java
List<String> names = List.of("Alice", "Bob", "Charlie", "Diana", "Eve");

// Count
long count = names.stream().collect(Collectors.counting());
System.out.println("Count: " + count); // 5

// Sum of lengths
int totalLength = names.stream()
    .collect(Collectors.summingInt(String::length));
System.out.println("Total length: " + totalLength); // 24

// Average length
double avgLength = names.stream()
    .collect(Collectors.averagingInt(String::length));
System.out.println("Average length: " + avgLength); // 4.8

// Summary statistics
IntSummaryStatistics stats = names.stream()
    .collect(Collectors.summarizingInt(String::length));
System.out.println(stats); // IntSummaryStatistics{count=5, sum=24, min=3, average=4.800000, max=7}
```

---

### `Collectors.toMap()` vs `groupingBy()` — When to Use Which

```
toMap():
  - When each element maps to a UNIQUE key
  - Result: Map<K, V>
  - Throws exception on duplicate keys (unless merge function provided)
  - Use for: ID → Object, name → value lookups

groupingBy():
  - When MULTIPLE elements can share the same key
  - Result: Map<K, List<V>>
  - Never throws on duplicates — groups them into a list
  - Use for: category → items, length → names
```

---

### Custom Collector with `Collectors.teeing()` — Java 12+

```java
// teeing — apply TWO collectors simultaneously, then merge results
List<Integer> numbers = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

record Stats(double average, long count) {}

Stats stats = numbers.stream()
    .collect(Collectors.teeing(
        Collectors.averagingInt(Integer::intValue),  // Collector 1
        Collectors.counting(),                        // Collector 2
        Stats::new                                    // Merge function
    ));
System.out.println("Average: " + stats.average()); // 5.5
System.out.println("Count:   " + stats.count());   // 10
```

---

## 7. FlatMap — Flattening Streams

`flatMap` maps each element to a Stream, then **flattens** all those streams into one.

```
map:     [1,2,3] → [[1,2],[3,4],[5]] (nested!)
flatMap: [1,2,3] → [1,2,3,4,5]      (flat!)
```

---

### Basic `flatMap` Example

```java
List<List<Integer>> nested = List.of(
    List.of(1, 2, 3),
    List.of(4, 5, 6),
    List.of(7, 8, 9)
);

// map — produces Stream<Stream<Integer>> (WRONG for what we want)
nested.stream()
    .map(List::stream)
    .forEach(System.out::println); // Each prints: java.util.stream.ReferencePipeline$Head@...

// flatMap — flattens to Stream<Integer>
List<Integer> flat = nested.stream()
    .flatMap(List::stream)  // Each inner List becomes a Stream, all merged into one
    .collect(Collectors.toList());
System.out.println(flat); // [1, 2, 3, 4, 5, 6, 7, 8, 9]
```

---

### Words in Sentences — Classic FlatMap Use Case

```java
List<String> sentences = List.of(
    "Hello World Java",
    "Streams are powerful",
    "FlatMap is very useful"
);

// All unique words across all sentences
List<String> allWords = sentences.stream()
    .flatMap(sentence -> Arrays.stream(sentence.split(" ")))
    .distinct()
    .sorted()
    .collect(Collectors.toList());

System.out.println(allWords);
// [FlatMap, Hello, Java, Streams, World, are, is, powerful, useful, very]

// Word count
long wordCount = sentences.stream()
    .flatMap(sentence -> Arrays.stream(sentence.split(" ")))
    .count();
System.out.println("Total words: " + wordCount); // 10
```

---

### FlatMap with Optional (Java 9+)

```java
List<Optional<String>> optionals = List.of(
    Optional.of("Alice"),
    Optional.empty(),
    Optional.of("Bob"),
    Optional.empty(),
    Optional.of("Charlie")
);

// Java 9+ — flatMap with Optional::stream (empty Optionals produce empty streams)
List<String> present = optionals.stream()
    .flatMap(Optional::stream)
    .collect(Collectors.toList());
System.out.println(present); // [Alice, Bob, Charlie]
```

---

### FlatMap with Custom Objects

```java
record Order(int id, List<String> items) {}

List<Order> orders = List.of(
    new Order(1, List.of("laptop", "mouse", "keyboard")),
    new Order(2, List.of("monitor")),
    new Order(3, List.of("headset", "webcam"))
);

// All items from all orders (flat list)
List<String> allItems = orders.stream()
    .flatMap(order -> order.items().stream())
    .distinct()
    .sorted()
    .collect(Collectors.toList());
System.out.println(allItems);
// [headset, keyboard, laptop, monitor, mouse, webcam]

// Count items per order
orders.stream()
    .forEach(o -> System.out.println("Order " + o.id() + ": " + o.items().size() + " items"));
```

---

## 8. Optional — Null-Safe Streams

`Optional<T>` is a container that may or may not contain a value. It avoids `NullPointerException` and forces you to handle the "no value" case explicitly.

---

### Creating Optionals

```java
import java.util.Optional;

Optional<String> present = Optional.of("Hello");       // Contains "Hello" — NEVER null
Optional<String> empty   = Optional.empty();           // Empty Optional
Optional<String> maybe   = Optional.ofNullable(null);  // Empty if null, value if not null

// Optional.of(null) → throws NullPointerException immediately!
// Optional.ofNullable(null) → returns Optional.empty() safely
```

---

### Reading from Optional

```java
Optional<String> name = Optional.of("Alice");
Optional<String> none = Optional.empty();

// get() — UNSAFE, throws if empty
name.get();   // "Alice"
// none.get(); // NoSuchElementException!

// orElse — return default if empty
System.out.println(name.orElse("Unknown")); // Alice
System.out.println(none.orElse("Unknown")); // Unknown

// orElseGet — lazy supplier (better when default is expensive to compute)
System.out.println(none.orElseGet(() -> "Computed Default")); // Computed Default

// orElseThrow — throw custom exception if empty
name.orElseThrow(() -> new IllegalStateException("No name found")); // Alice

// ifPresent — run action only if value exists
name.ifPresent(n -> System.out.println("Hello, " + n)); // Hello, Alice
none.ifPresent(n -> System.out.println("Hello, " + n)); // (nothing)

// ifPresentOrElse (Java 9+) — run one of two actions
name.ifPresentOrElse(
    n -> System.out.println("Found: " + n),
    ()  -> System.out.println("Not found")
); // Found: Alice

// isPresent / isEmpty (Java 11+)
System.out.println(name.isPresent()); // true
System.out.println(none.isEmpty());   // true
```

---

### Transforming Optionals

```java
Optional<String> name = Optional.of("  alice  ");

// map — transform if present
Optional<String> upper = name
    .map(String::trim)
    .map(String::toUpperCase);
System.out.println(upper.get()); // ALICE

// flatMap — when transformation itself returns Optional
Optional<String> firstName = Optional.of("Alice Smith");
Optional<String> firstPart = firstName
    .flatMap(n -> {
        String[] parts = n.split(" ");
        return parts.length > 0 ? Optional.of(parts[0]) : Optional.empty();
    });
System.out.println(firstPart.get()); // Alice

// filter — Optional becomes empty if predicate fails
Optional<Integer> age    = Optional.of(25);
Optional<Integer> adult  = age.filter(a -> a >= 18); // Optional[25]
Optional<Integer> minor  = age.filter(a -> a < 18);  // Optional.empty

// or (Java 9+) — return alternative Optional if empty
Optional<String> result = none
    .or(() -> Optional.of("Fallback"));
System.out.println(result.get()); // Fallback

// stream (Java 9+) — empty Optional = empty Stream, present = one-element Stream
none.stream().forEach(System.out::println);   // (nothing)
name.stream().forEach(System.out::println);   // alice (trimmed)
```

---

### Optional in a Real-World Scenario

```java
import java.util.*;

record User(int id, String name, Optional<String> email) {}
record Order(int userId, double amount) {}

class UserRepository {
    static Map<Integer, User> db = Map.of(
        1, new User(1, "Alice", Optional.of("alice@example.com")),
        2, new User(2, "Bob",   Optional.empty())
    );

    static Optional<User> findById(int id) {
        return Optional.ofNullable(db.get(id));
    }
}

public class OptionalChainDemo {
    public static void main(String[] args) {
        // Get email for user 1
        String email1 = UserRepository.findById(1)
            .flatMap(User::email)
            .map(String::toLowerCase)
            .orElse("no-email@default.com");
        System.out.println("User 1 email: " + email1); // alice@example.com

        // Get email for user 2 (no email)
        String email2 = UserRepository.findById(2)
            .flatMap(User::email)
            .orElse("no-email@default.com");
        System.out.println("User 2 email: " + email2); // no-email@default.com

        // User not found (id 99)
        String email3 = UserRepository.findById(99)
            .flatMap(User::email)
            .orElse("no-email@default.com");
        System.out.println("User 99 email: " + email3); // no-email@default.com
    }
}
```

---

## 9. Primitive Streams

`IntStream`, `LongStream`, `DoubleStream` avoid boxing/unboxing overhead compared to `Stream<Integer>`, `Stream<Long>`, `Stream<Double>`.

```
Stream<Integer>  ← boxes each int (Integer object, heap allocation)
IntStream        ← raw int values (no boxing, stack/register)
```

---

### IntStream Operations

```java
import java.util.stream.*;

// Range
IntStream.range(1, 6)       .forEach(System.out::print); // 12345
IntStream.rangeClosed(1, 5) .forEach(System.out::print); // 12345

// Arithmetic
System.out.println(IntStream.rangeClosed(1, 100).sum());       // 5050
System.out.println(IntStream.rangeClosed(1, 100).average());   // OptionalDouble[50.5]
System.out.println(IntStream.of(3, 1, 4, 1, 5, 9).max());     // OptionalInt[9]
System.out.println(IntStream.of(3, 1, 4, 1, 5, 9).min());     // OptionalInt[1]

// Convert to Stream<Integer> (boxing)
Stream<Integer> boxed = IntStream.rangeClosed(1, 5).boxed();

// From Stream<Integer> to IntStream (unboxing)
List<Integer> numbers = List.of(1, 2, 3, 4, 5);
int sum = numbers.stream()
    .mapToInt(Integer::intValue)  // Unbox to IntStream
    .sum();
System.out.println(sum); // 15

// IntStream.of
int product = IntStream.of(2, 3, 4)
    .reduce(1, (a, b) -> a * b);
System.out.println("Product: " + product); // 24
```

---

### LongStream and DoubleStream

```java
// LongStream — for large numbers (factorial, financial data)
long factorial10 = LongStream.rangeClosed(1, 10)
    .reduce(1L, (a, b) -> a * b);
System.out.println("10! = " + factorial10); // 3628800

// DoubleStream — for floating point
double[] prices = {10.5, 20.0, 15.75, 30.25};
double total = DoubleStream.of(prices).sum();
double avg   = DoubleStream.of(prices).average().orElse(0);
System.out.println("Total: " + total); // 76.5
System.out.println("Avg:   " + avg);   // 19.125
```

---

### Conversions Between Stream Types

```java
// IntStream → Stream<String>
Stream<String> asStrings = IntStream.rangeClosed(1, 5)
    .mapToObj(i -> "Item " + i);
asStrings.forEach(System.out::println); // Item 1, Item 2, ...

// IntStream → LongStream / DoubleStream
LongStream   asLong   = IntStream.rangeClosed(1, 5).asLongStream();
DoubleStream asDouble = IntStream.rangeClosed(1, 5).asDoubleStream();

// Stream<String> → IntStream
IntStream wordLengths = Stream.of("hello", "world", "java")
    .mapToInt(String::length);
System.out.println(wordLengths.sum()); // 13
```

---

## 10. Parallel Streams

Parallel streams split data into chunks and process them on multiple threads using the **ForkJoinPool.commonPool()**.

---

### Enabling Parallel Streams

```java
List<Integer> numbers = new ArrayList<>();
for (int i = 1; i <= 1_000_000; i++) numbers.add(i);

// Option 1: .parallelStream() on collection
long sumParallel = numbers.parallelStream()
    .mapToLong(Integer::longValue)
    .sum();

// Option 2: .parallel() on existing stream
long sumAlsoParallel = numbers.stream()
    .parallel()
    .mapToLong(Integer::longValue)
    .sum();

// Option 3: Convert back to sequential
long sumSequential = numbers.parallelStream()
    .filter(n -> n % 2 == 0)
    .sequential() // Switch back to sequential
    .mapToLong(Integer::longValue)
    .sum();
```

---

### Parallel Streams Performance Comparison

```java
import java.util.*;
import java.util.stream.*;

public class ParallelBenchmark {
    public static void main(String[] args) {

        List<Integer> bigList = new ArrayList<>();
        for (int i = 1; i <= 10_000_000; i++) bigList.add(i);

        // Sequential
        long t0 = System.currentTimeMillis();
        long seqSum = bigList.stream()
            .mapToLong(Integer::longValue)
            .sum();
        long seqTime = System.currentTimeMillis() - t0;

        // Parallel
        long t1 = System.currentTimeMillis();
        long parSum = bigList.parallelStream()
            .mapToLong(Integer::longValue)
            .sum();
        long parTime = System.currentTimeMillis() - t1;

        System.out.printf("Sequential: sum=%d in %dms%n", seqSum, seqTime);
        System.out.printf("Parallel:   sum=%d in %dms%n", parSum, parTime);
        System.out.printf("Speedup: %.1fx%n", (double)seqTime / parTime);
    }
}
```

**Typical Output (8-core machine):**
```
Sequential: sum=50000005000000 in 95ms
Parallel:   sum=50000005000000 in 18ms
Speedup: 5.3x
```

---

### When to Use (and NOT Use) Parallel Streams

```
✅ USE parallel streams when:
  - Data size is LARGE (>10,000 elements)
  - Operations are CPU-intensive (heavy computation)
  - Operations are STATELESS and INDEPENDENT
  - Source is splittable (ArrayList, arrays — NOT LinkedList)

❌ AVOID parallel streams when:
  - Data is small (overhead > benefit)
  - Operations have SIDE EFFECTS (writes to shared state)
  - Operations are I/O-bound (threads sit idle)
  - Order matters and stream is not ordered
  - Using non-thread-safe collections as output
```

---

### Thread Safety in Parallel Streams

```java
// ❌ UNSAFE — shared mutable state
List<Integer> unsafeResult = new ArrayList<>();
IntStream.rangeClosed(1, 10_000)
    .parallel()
    .forEach(unsafeResult::add); // ArrayList is NOT thread-safe!
System.out.println("Size: " + unsafeResult.size()); // May be < 10000!

// ✅ SAFE — use collect() which handles thread safety internally
List<Integer> safeResult = IntStream.rangeClosed(1, 10_000)
    .parallel()
    .boxed()
    .collect(Collectors.toList()); // Thread-safe!
System.out.println("Size: " + safeResult.size()); // Always 10000

// ✅ ALSO SAFE — thread-safe collection
List<Integer> concurrentResult = IntStream.rangeClosed(1, 10_000)
    .parallel()
    .boxed()
    .collect(Collectors.toCollection(
        java.util.concurrent.CopyOnWriteArrayList::new
    ));
```

---

### `forEachOrdered()` — Preserve order in parallel

```java
// forEach in parallel — order NOT guaranteed
System.out.println("forEach (parallel, unordered):");
IntStream.rangeClosed(1, 5)
    .parallel()
    .forEach(i -> System.out.print(i + " ")); // e.g. 3 1 4 2 5
System.out.println();

// forEachOrdered — preserve order even in parallel (slower but ordered)
System.out.println("forEachOrdered (parallel, ordered):");
IntStream.rangeClosed(1, 5)
    .parallel()
    .forEachOrdered(i -> System.out.print(i + " ")); // Always: 1 2 3 4 5
```

---

## 11. Infinite Streams

Streams can be infinite when created with `iterate()` or `generate()`. Always add `limit()` or `takeWhile()` before a terminal operation!

---

### Fibonacci with `iterate()`

```java
// Java 9+ iterate with two-state seed
Stream.iterate(new long[]{0, 1}, fib -> new long[]{fib[1], fib[0] + fib[1]})
    .limit(15)
    .map(fib -> fib[0])
    .forEach(n -> System.out.print(n + " "));
// 0 1 1 2 3 5 8 13 21 34 55 89 144 233 377
```

---

### Prime Numbers with `iterate()`

```java
// Check if a number is prime
java.util.function.Predicate<Integer> isPrime = n -> {
    if (n < 2) return false;
    return IntStream.rangeClosed(2, (int)Math.sqrt(n))
                    .allMatch(i -> n % i != 0);
};

// Infinite stream of natural numbers, filtered to primes, take first 20
List<Integer> primes = Stream.iterate(2, n -> n + 1)
    .filter(isPrime)
    .limit(20)
    .collect(Collectors.toList());
System.out.println(primes);
// [2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53, 59, 61, 67, 71]
```

---

### Random Data with `generate()`

```java
import java.util.*;

// Infinite random names
List<String> randomNames = Stream.generate(() ->
    List.of("Alice","Bob","Carol","Dave","Eve")
        .get(new Random().nextInt(5)))
    .distinct()    // Keep unique
    .limit(3)      // Stop after 3 unique names
    .collect(Collectors.toList());
System.out.println(randomNames); // e.g. [Bob, Eve, Alice]

// UUID factory
Stream.generate(UUID::randomUUID)
    .limit(3)
    .forEach(System.out::println);
```

---

### `takeWhile()` on Infinite Stream — Java 9+

```java
// Take numbers from infinite stream while they are < 50
List<Integer> smallNumbers = Stream.iterate(1, n -> n + 1)
    .takeWhile(n -> n < 50) // Stops automatically when n >= 50
    .collect(Collectors.toList());
System.out.println(smallNumbers.size()); // 49
System.out.println(smallNumbers.get(smallNumbers.size() - 1)); // 49
```

---

## 12. Stream of Custom Objects — Real World

```java
import java.util.*;
import java.util.stream.*;

// Domain model
record Product(String name, String category, double price, int stock) {}

public class ProductStreamDemo {

    static List<Product> catalog = List.of(
        new Product("Laptop",    "Electronics", 999.99, 50),
        new Product("Mouse",     "Electronics", 29.99,  200),
        new Product("Keyboard",  "Electronics", 79.99,  150),
        new Product("Desk",      "Furniture",   299.99, 30),
        new Product("Chair",     "Furniture",   199.99, 75),
        new Product("Monitor",   "Electronics", 349.99, 80),
        new Product("Headset",   "Electronics", 89.99,  120),
        new Product("Bookshelf", "Furniture",   149.99, 45),
        new Product("Lamp",      "Furniture",   49.99,  200),
        new Product("Webcam",    "Electronics", 69.99,  90)
    );

    public static void main(String[] args) {

        // 1. All electronics sorted by price desc
        System.out.println("=== Electronics by price (desc) ===");
        catalog.stream()
            .filter(p -> p.category().equals("Electronics"))
            .sorted(Comparator.comparingDouble(Product::price).reversed())
            .forEach(p -> System.out.printf("  %-12s $%.2f%n", p.name(), p.price()));

        // 2. Total value of inventory (price × stock)
        double totalInventoryValue = catalog.stream()
            .mapToDouble(p -> p.price() * p.stock())
            .sum();
        System.out.printf("%n=== Total inventory value: $%.2f ===%n", totalInventoryValue);

        // 3. Most expensive product per category
        System.out.println("\n=== Most expensive per category ===");
        catalog.stream()
            .collect(Collectors.groupingBy(
                Product::category,
                Collectors.maxBy(Comparator.comparingDouble(Product::price))
            ))
            .forEach((cat, product) ->
                product.ifPresent(p ->
                    System.out.printf("  %-12s → %-12s $%.2f%n",
                        cat, p.name(), p.price())
                )
            );

        // 4. Average price per category
        System.out.println("\n=== Average price per category ===");
        catalog.stream()
            .collect(Collectors.groupingBy(
                Product::category,
                Collectors.averagingDouble(Product::price)
            ))
            .forEach((cat, avg) ->
                System.out.printf("  %-12s → $%.2f%n", cat, avg)
            );

        // 5. Products with low stock (<= 50), sorted by stock
        System.out.println("\n=== Low stock alert (<=50) ===");
        catalog.stream()
            .filter(p -> p.stock() <= 50)
            .sorted(Comparator.comparingInt(Product::stock))
            .forEach(p -> System.out.printf("  %-12s stock=%d%n", p.name(), p.stock()));

        // 6. All product names joined as CSV
        System.out.println("\n=== All products ===");
        String allNames = catalog.stream()
            .map(Product::name)
            .collect(Collectors.joining(", "));
        System.out.println(allNames);

        // 7. Category → list of product names
        System.out.println("\n=== Products by category ===");
        catalog.stream()
            .collect(Collectors.groupingBy(
                Product::category,
                Collectors.mapping(Product::name, Collectors.toList())
            ))
            .forEach((cat, names) ->
                System.out.println("  " + cat + ": " + names)
            );

        // 8. Is there any product cheaper than $20?
        boolean hasAffordable = catalog.stream()
            .anyMatch(p -> p.price() < 20.0);
        System.out.println("\nAny product < $20? " + hasAffordable); // false

        // 9. Count electronics
        long electronicCount = catalog.stream()
            .filter(p -> p.category().equals("Electronics"))
            .count();
        System.out.println("Electronics count: " + electronicCount); // 6

        // 10. Product name → price map
        Map<String, Double> priceMap = catalog.stream()
            .collect(Collectors.toMap(Product::name, Product::price));
        System.out.println("\nLaptop price: $" + priceMap.get("Laptop")); // $999.99
    }
}
```

---

## 13. Method References

Method references are a shorthand for lambdas that call a single method.

```
Lambda:            x -> System.out.println(x)
Method Reference:  System.out::println
```

### 4 Types of Method References

```java
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

// ── Type 1: Static Method Reference ──────────────────────────────────────────
// Lambda:   x -> Integer.parseInt(x)
// Ref:      Integer::parseInt
List<String> nums = List.of("1", "2", "3", "4", "5");
List<Integer> parsed = nums.stream()
    .map(Integer::parseInt)   // Static: Integer.parseInt(x)
    .collect(Collectors.toList());
System.out.println(parsed); // [1, 2, 3, 4, 5]

// ── Type 2: Instance Method on Particular Instance ───────────────────────────
// Lambda:   x -> System.out.println(x)
// Ref:      System.out::println
List<String> words = List.of("hello", "world");
words.stream().forEach(System.out::println); // Instance method on System.out

// ── Type 3: Instance Method on Arbitrary Instance of Type ────────────────────
// Lambda:   x -> x.toUpperCase()
// Ref:      String::toUpperCase
List<String> upper = words.stream()
    .map(String::toUpperCase)  // Called on each String element
    .collect(Collectors.toList());
System.out.println(upper); // [HELLO, WORLD]

// More examples:
words.stream().map(String::length).forEach(System.out::println);    // 5, 5
words.stream().sorted(String::compareTo).forEach(System.out::println); // hello, world

// ── Type 4: Constructor Reference ────────────────────────────────────────────
// Lambda:   s -> new StringBuilder(s)
// Ref:      StringBuilder::new
List<StringBuilder> builders = words.stream()
    .map(StringBuilder::new)
    .collect(Collectors.toList());
builders.forEach(sb -> System.out.println(sb.reverse())); // olleh, dlrow

record Person(String name) {}
List<Person> people = words.stream()
    .map(Person::new)
    .collect(Collectors.toList());
System.out.println(people); // [Person[name=hello], Person[name=world]]
```

---

## 14. Comparator with Streams

```java
import java.util.*;
import java.util.stream.*;

record Employee(String name, String dept, int salary, int age) {}

List<Employee> employees = List.of(
    new Employee("Alice",   "Engineering", 90000, 30),
    new Employee("Bob",     "Engineering", 85000, 35),
    new Employee("Charlie", "HR",          60000, 28),
    new Employee("Diana",   "Engineering", 95000, 32),
    new Employee("Eve",     "HR",          65000, 29),
    new Employee("Frank",   "Marketing",   70000, 45)
);

// Sort by salary descending
employees.stream()
    .sorted(Comparator.comparingInt(Employee::salary).reversed())
    .forEach(e -> System.out.printf("%-10s $%,d%n", e.name(), e.salary()));

// Sort by dept, then salary desc within dept
employees.stream()
    .sorted(Comparator.comparing(Employee::dept)
                      .thenComparing(Comparator.comparingInt(Employee::salary).reversed()))
    .forEach(e -> System.out.printf("%-12s %-10s $%,d%n", e.dept(), e.name(), e.salary()));

// Min salary employee
Optional<Employee> lowestPaid = employees.stream()
    .min(Comparator.comparingInt(Employee::salary));
lowestPaid.ifPresent(e -> System.out.println("Lowest paid: " + e.name()));

// Max salary per department
employees.stream()
    .collect(Collectors.groupingBy(
        Employee::dept,
        Collectors.maxBy(Comparator.comparingInt(Employee::salary))
    ))
    .forEach((dept, emp) ->
        emp.ifPresent(e -> System.out.printf("Top in %-12s: %s ($%,d)%n",
            dept, e.name(), e.salary()))
    );
```

---

## 15. Grouping, Partitioning & Summarizing

Real-world data aggregation patterns:

```java
import java.util.*;
import java.util.stream.*;

record Sale(String rep, String region, String product, double amount, int year) {}

List<Sale> sales = List.of(
    new Sale("Alice", "North", "Laptop",  1200.0, 2023),
    new Sale("Bob",   "South", "Monitor",  450.0, 2023),
    new Sale("Alice", "North", "Mouse",     30.0, 2023),
    new Sale("Carol", "East",  "Laptop",  1100.0, 2024),
    new Sale("Bob",   "South", "Keyboard", 80.0, 2024),
    new Sale("Alice", "North", "Headset",  90.0, 2024),
    new Sale("Carol", "East",  "Monitor", 400.0, 2024),
    new Sale("Dave",  "West",  "Laptop",  1300.0, 2023)
);

// 1. Total revenue per sales rep
System.out.println("=== Revenue per rep ===");
sales.stream()
    .collect(Collectors.groupingBy(Sale::rep,
             Collectors.summingDouble(Sale::amount)))
    .entrySet().stream()
    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
    .forEach(e -> System.out.printf("  %-8s $%.2f%n", e.getKey(), e.getValue()));

// 2. Sales count per region
System.out.println("\n=== Sales count per region ===");
sales.stream()
    .collect(Collectors.groupingBy(Sale::region, Collectors.counting()))
    .forEach((r, c) -> System.out.printf("  %-6s %d sales%n", r, c));

// 3. Revenue per year per region (multi-level grouping)
System.out.println("\n=== Revenue: year → region ===");
sales.stream()
    .collect(Collectors.groupingBy(Sale::year,
             Collectors.groupingBy(Sale::region,
             Collectors.summingDouble(Sale::amount))))
    .forEach((year, regionMap) -> {
        System.out.println("  " + year + ":");
        regionMap.forEach((region, total) ->
            System.out.printf("    %-6s $%.2f%n", region, total));
    });

// 4. Partition sales by amount (high value vs low value)
System.out.println("\n=== High value (>=$500) vs Low value ===");
Map<Boolean, List<Sale>> partitioned = sales.stream()
    .collect(Collectors.partitioningBy(s -> s.amount() >= 500));
System.out.println("High: " + partitioned.get(true).stream()
    .map(Sale::product).collect(Collectors.joining(", ")));
System.out.println("Low:  " + partitioned.get(false).stream()
    .map(Sale::product).collect(Collectors.joining(", ")));

// 5. Summary statistics for amounts
System.out.println("\n=== Amount statistics ===");
DoubleSummaryStatistics stats = sales.stream()
    .collect(Collectors.summarizingDouble(Sale::amount));
System.out.printf("Count: %d, Sum: $%.2f, Avg: $%.2f, Min: $%.2f, Max: $%.2f%n",
    stats.getCount(), stats.getSum(), stats.getAverage(), stats.getMin(), stats.getMax());

// 6. Product → set of reps who sold it
System.out.println("\n=== Reps per product ===");
sales.stream()
    .collect(Collectors.groupingBy(Sale::product,
             Collectors.mapping(Sale::rep,
             Collectors.toSet())))
    .forEach((prod, reps) ->
        System.out.printf("  %-10s sold by: %s%n", prod, reps));
```

---

## 16. Stream Laziness — How It Works

Understanding laziness is key to writing efficient streams.

```java
public class LazyDeepDive {
    public static void main(String[] args) {

        List<Integer> numbers = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        // Short-circuit with findFirst — stops after finding first match
        System.out.println("=== Short-circuit findFirst ===");
        Optional<Integer> firstEven = numbers.stream()
            .filter(n -> {
                System.out.println("  Checking: " + n);
                return n % 2 == 0;
            })
            .findFirst(); // Stops as soon as 2 is found — never processes 3-10!
        System.out.println("  Result: " + firstEven.get());

        // Short-circuit with anyMatch
        System.out.println("\n=== Short-circuit anyMatch ===");
        boolean found = numbers.stream()
            .filter(n -> {
                System.out.println("  Testing: " + n);
                return n > 3;
            })
            .anyMatch(n -> n > 3); // Stops at first n > 3 — after seeing 4!
        System.out.println("  Found: " + found);

        // Vertical (element-by-element) processing
        System.out.println("\n=== Vertical processing order ===");
        numbers.stream()
            .filter(n -> n <= 3)
            .map(n -> n * 10)
            .forEach(n -> System.out.println("  Final: " + n));
        // Processing order: 1→filter→map→print, 2→filter→map→print, 3→filter→map→print
        // NOT: filter all, THEN map all, THEN print all
    }
}
```

**Output:**
```
=== Short-circuit findFirst ===
  Checking: 1
  Checking: 2
  Result: 2

=== Short-circuit anyMatch ===
  Testing: 1
  Testing: 2
  Testing: 3
  Testing: 4
  Found: true

=== Vertical processing order ===
  Final: 10
  Final: 20
  Final: 30
```

---

## 17. Common Mistakes & Pitfalls

### ❌ Mistake 1: Reusing a consumed stream

```java
Stream<String> stream = Stream.of("A", "B", "C");
stream.forEach(System.out::println); // Fine

// ❌ Using stream again after terminal operation
stream.forEach(System.out::println); // IllegalStateException: stream has already been operated upon!

// ✅ Create a new stream each time
List<String> source = List.of("A", "B", "C");
source.stream().forEach(System.out::println);
source.stream().filter(s -> !s.equals("A")).forEach(System.out::println);
```

---

### ❌ Mistake 2: Infinite stream without termination

```java
// ❌ This runs FOREVER — no limit or takeWhile
Stream.iterate(1, n -> n + 1)
    .filter(n -> n % 2 == 0)
    .forEach(System.out::println); // HANGS!

// ✅ Always add limit() or takeWhile() on infinite streams
Stream.iterate(1, n -> n + 1)
    .filter(n -> n % 2 == 0)
    .limit(10)
    .forEach(System.out::println);
```

---

### ❌ Mistake 3: Modifying source collection during stream operation

```java
List<String> names = new ArrayList<>(List.of("Alice", "Bob", "Charlie"));

// ❌ Concurrent modification exception!
names.stream().forEach(name -> {
    if (name.equals("Bob")) names.remove(name); // ConcurrentModificationException!
});

// ✅ Collect to separate list, then modify
List<String> toRemove = names.stream()
    .filter(name -> name.equals("Bob"))
    .collect(Collectors.toList());
names.removeAll(toRemove);
// Or simply:
names.removeIf(name -> name.equals("Bob")); // Best for this use case
```

---

### ❌ Mistake 4: Side effects in parallel stream

```java
List<Integer> results = new ArrayList<>();

// ❌ ArrayList is NOT thread-safe
IntStream.rangeClosed(1, 1000)
    .parallel()
    .forEach(results::add); // Race condition! May lose elements

System.out.println("Size: " + results.size()); // < 1000 sometimes!

// ✅ Use collect()
List<Integer> safeResults = IntStream.rangeClosed(1, 1000)
    .parallel()
    .boxed()
    .collect(Collectors.toList()); // Thread-safe!
System.out.println("Size: " + safeResults.size()); // Always 1000
```

---

### ❌ Mistake 5: Using `peek()` as primary logic

```java
// ❌ WRONG — peek is for DEBUGGING, not primary logic
long count = Stream.of(1, 2, 3, 4, 5)
    .peek(n -> System.out.println("Processing: " + n))
    .filter(n -> n % 2 == 0)
    .peek(n -> {
        database.save(n); // ❌ Side effects in peek are unreliable!
        // In some optimized scenarios, peek may not be called at all
    })
    .count();

// ✅ CORRECT — use forEach or explicit collect for actions
Stream.of(1, 2, 3, 4, 5)
    .filter(n -> n % 2 == 0)
    .forEach(database::save); // Clear intent
```

---

### ❌ Mistake 6: Ignoring Optional from `findFirst()`, `reduce()`, etc.

```java
List<Integer> numbers = List.of(1, 2, 3, 4, 5);

// ❌ get() without checking — throws NoSuchElementException if stream is empty!
Integer first = numbers.stream().filter(n -> n > 100).findFirst().get(); // Throws!

// ✅ Always handle the empty case
Optional<Integer> result = numbers.stream().filter(n -> n > 100).findFirst();
int value = result.orElse(-1);                             // Default value
int value2 = result.orElseThrow(() ->
    new RuntimeException("No element found"));             // Custom exception
result.ifPresent(v -> System.out.println("Found: " + v)); // Only if present
```

---

### ❌ Mistake 7: Forgetting `close()` on file streams

```java
// ❌ Resource leak — Files.lines() must be closed!
Stream<String> lines = Files.lines(Path.of("data.txt"));
lines.forEach(System.out::println);
// lines never closed if exception thrown!

// ✅ Always use try-with-resources for I/O streams
try (Stream<String> fileLines = Files.lines(Path.of("data.txt"))) {
    fileLines.forEach(System.out::println);
}
```

---

## 18. Java 9–21 Stream Enhancements

| Version | Feature | Description |
|---------|---------|-------------|
| Java 9  | `Stream.iterate(seed, pred, op)` | Finite iterate with predicate |
| Java 9  | `Stream.takeWhile(predicate)` | Take while predicate is true |
| Java 9  | `Stream.dropWhile(predicate)` | Drop while predicate is true |
| Java 9  | `Stream.ofNullable(value)` | Empty stream if null, else singleton |
| Java 9  | `Optional.stream()` | Convert Optional to 0 or 1 element stream |
| Java 10 | `Collectors.toUnmodifiableList/Set/Map()` | Unmodifiable collectors |
| Java 12 | `Collectors.teeing()` | Two downstream collectors merged |
| Java 16 | `Stream.toList()` | Shorthand for `collect(Collectors.toUnmodifiableList())` |

---

### `Stream.ofNullable()` — Java 9

```java
// Returns empty stream for null, singleton for non-null
String value = null;
Stream.ofNullable(value).forEach(System.out::println); // (nothing — no NPE!)

String real = "Hello";
Stream.ofNullable(real).forEach(System.out::println); // Hello

// Practical: process nullable fields
record User(String name, String nickname) {}
List<User> users = List.of(
    new User("Alice", "Ali"),
    new User("Bob",   null),
    new User("Charlie", "Chuck")
);

// Get all non-null nicknames
List<String> nicknames = users.stream()
    .flatMap(u -> Stream.ofNullable(u.nickname())) // Skips null nicknames
    .collect(Collectors.toList());
System.out.println(nicknames); // [Ali, Chuck]
```

---

### `Stream.toList()` — Java 16

```java
List<String> names = List.of("Charlie", "Alice", "Bob");

// Java 16+ — concise, returns UNMODIFIABLE list
List<String> sorted = names.stream()
    .sorted()
    .toList(); // Replaces: .collect(Collectors.toUnmodifiableList())

System.out.println(sorted); // [Alice, Bob, Charlie]

// sorted.add("Diana"); // UnsupportedOperationException — it's unmodifiable!
```

---

### `Collectors.teeing()` — Java 12

```java
// Apply two collectors at once, combine results
record MinMax(int min, int max) {}

List<Integer> nums = List.of(3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5);

MinMax minMax = nums.stream()
    .collect(Collectors.teeing(
        Collectors.minBy(Integer::compareTo),  // → Optional<Integer>
        Collectors.maxBy(Integer::compareTo),  // → Optional<Integer>
        (min, max) -> new MinMax(min.get(), max.get())
    ));
System.out.println("Min: " + minMax.min() + ", Max: " + minMax.max()); // Min: 1, Max: 9

// More complex example: above and below average
double avg = nums.stream().mapToInt(Integer::intValue).average().orElse(0);
Map<String, List<Integer>> aboveBelow = nums.stream()
    .collect(Collectors.teeing(
        Collectors.filtering(n -> n >= avg, Collectors.toList()),
        Collectors.filtering(n -> n  < avg, Collectors.toList()),
        (above, below) -> Map.of("above", above, "below", below)
    )).entrySet().stream()
    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
System.out.println("Above avg (>=" + avg + "): " + aboveBelow.get("above"));
System.out.println("Below avg (<"  + avg + "): " + aboveBelow.get("below"));
```

---

## 19. Interview Questions & Answers

| # | Question | Answer |
|---|----------|--------|
| 1 | What is a Stream? | A sequence of elements from a source supporting functional aggregate operations. NOT a data structure — it doesn't store data. |
| 2 | What is lazy evaluation in streams? | Intermediate operations (filter, map) don't execute until a terminal operation is called. Enables short-circuiting and efficiency. |
| 3 | What are short-circuit operations? | Operations that don't process the entire stream: `findFirst`, `findAny`, `anyMatch`, `allMatch`, `noneMatch`, `limit` |
| 4 | `map` vs `flatMap`? | `map` transforms each element to one value (1:1). `flatMap` transforms each element to a Stream, then flattens all streams (1:N) |
| 5 | Can a stream be reused? | No. Once a terminal operation is called, the stream is consumed. Using it again throws `IllegalStateException` |
| 6 | `findFirst` vs `findAny`? | `findFirst` returns the first element (deterministic). `findAny` returns any element — better for parallel streams (non-deterministic) |
| 7 | What is the difference between `forEach` and `forEachOrdered`? | `forEach` doesn't guarantee order (especially in parallel). `forEachOrdered` preserves encounter order even in parallel streams (slower) |
| 8 | When to use parallel streams? | Large datasets (>10k elements), CPU-intensive stateless operations, splittable sources (ArrayList, arrays). NOT for small data, I/O, or shared mutable state |
| 9 | `reduce` vs `collect`? | `reduce` combines elements into a single value via BinaryOperator. `collect` accumulates elements into a mutable container (List, Map) |
| 10 | What is `peek` used for? | Debugging — inspect elements as they flow through the pipeline without consuming the stream. Should NOT be used for primary logic |
| 11 | `Stream<Integer>` vs `IntStream`? | `IntStream` avoids boxing/unboxing overhead — stores raw `int` values, not `Integer` objects. Also has `sum()`, `average()`, `range()` |
| 12 | What is `Collectors.groupingBy`? | Groups stream elements by a classifier function into `Map<K, List<V>>`. Supports downstream collectors for further aggregation |
| 13 | `groupingBy` vs `toMap`? | `toMap` expects unique keys (throws on duplicates). `groupingBy` handles multiple elements with the same key by collecting them into a list |
| 14 | What is `Collectors.teeing()`? | Java 12+ — applies two downstream collectors simultaneously, then merges their results with a BiFunction |
| 15 | How does `Stream.iterate` differ in Java 9? | Java 9 adds a two-arg overload: `Stream.iterate(seed, predicate, operator)` — creates a finite stream that stops when predicate is false |
| 16 | What does `Stream.toList()` return (Java 16)? | An unmodifiable list. Shorter than `collect(Collectors.toUnmodifiableList())` |
| 17 | When would you use `takeWhile` vs `filter`? | `takeWhile` stops processing at the first non-matching element (useful for ordered/sorted data). `filter` processes the entire stream. |
| 18 | How to handle null values in streams? | Use `Stream.ofNullable()`, `filter(Objects::nonNull)`, or `Optional.ofNullable()`. Avoid `null` in stream pipelines. |
| 19 | What is a collector's `downstream`? | A second collector passed to groupingBy/partitioningBy to further transform the grouped values (e.g., `counting()`, `joining()`, `toSet()`) |
| 20 | How to get distinct objects by a field? | Use `Collectors.toMap` with a merge function, or write a stateful predicate with `ConcurrentHashMap::putIfAbsent` as a filter |

---

## 20. Complete Reference Summary

### Intermediate Operations Quick Reference

| Operation | Signature | Description |
|-----------|-----------|-------------|
| `filter` | `filter(Predicate<T>)` | Keep matching elements |
| `map` | `map(Function<T,R>)` | Transform each element |
| `flatMap` | `flatMap(Function<T,Stream<R>>)` | Transform + flatten |
| `distinct` | `distinct()` | Remove duplicates (`equals`) |
| `sorted` | `sorted()` / `sorted(Comparator)` | Sort elements |
| `limit` | `limit(long n)` | Take first n elements |
| `skip` | `skip(long n)` | Skip first n elements |
| `peek` | `peek(Consumer<T>)` | Debug/inspect (no modification) |
| `mapToInt` | `mapToInt(ToIntFunction<T>)` | Map to `IntStream` |
| `mapToLong` | `mapToLong(ToLongFunction<T>)` | Map to `LongStream` |
| `mapToDouble` | `mapToDouble(ToDoubleFunction<T>)` | Map to `DoubleStream` |
| `mapToObj` | `mapToObj(IntFunction<R>)` | Primitive stream → `Stream<R>` |
| `takeWhile` | `takeWhile(Predicate<T>)` | Take while true (Java 9+) |
| `dropWhile` | `dropWhile(Predicate<T>)` | Drop while true (Java 9+) |

### Terminal Operations Quick Reference

| Operation | Return Type | Description |
|-----------|-------------|-------------|
| `collect` | `R` | Accumulate into container |
| `forEach` | `void` | Apply action to each element |
| `forEachOrdered` | `void` | Apply action (ordered) |
| `reduce` | `T` / `Optional<T>` | Combine to single value |
| `count` | `long` | Count elements |
| `findFirst` | `Optional<T>` | First element |
| `findAny` | `Optional<T>` | Any element |
| `anyMatch` | `boolean` | Any element matches? |
| `allMatch` | `boolean` | All elements match? |
| `noneMatch` | `boolean` | No element matches? |
| `min` | `Optional<T>` | Minimum element |
| `max` | `Optional<T>` | Maximum element |
| `toArray` | `Object[]` | Convert to array |
| `sum` | `int/long/double` | Sum (primitive streams) |
| `average` | `OptionalDouble` | Average (primitive streams) |
| `summaryStatistics` | `*SummaryStatistics` | Stats object (primitive) |
| `toList` | `List<T>` | Unmodifiable list (Java 16+) |

### Collectors Quick Reference

| Collector | Returns | Description |
|-----------|---------|-------------|
| `toList()` | `List<T>` | Mutable list |
| `toUnmodifiableList()` | `List<T>` | Unmodifiable list |
| `toSet()` | `Set<T>` | Mutable set |
| `toMap(k, v)` | `Map<K,V>` | Map with key/value extractors |
| `joining()` | `String` | Concatenate strings |
| `joining(delim)` | `String` | With delimiter |
| `joining(d,p,s)` | `String` | With delimiter, prefix, suffix |
| `groupingBy(f)` | `Map<K,List<T>>` | Group by classifier |
| `groupingBy(f, down)` | `Map<K,D>` | Group with downstream |
| `partitioningBy(p)` | `Map<Boolean,List<T>>` | Split into true/false |
| `counting()` | `Long` | Count elements |
| `summingInt(f)` | `Integer` | Sum of int values |
| `averagingInt(f)` | `Double` | Average of int values |
| `summarizingInt(f)` | `IntSummaryStatistics` | All stats |
| `minBy(comp)` | `Optional<T>` | Minimum element |
| `maxBy(comp)` | `Optional<T>` | Maximum element |
| `mapping(f, down)` | `R` | Transform then collect downstream |
| `filtering(p, down)` | `R` | Filter then collect downstream |
| `teeing(c1,c2,merge)` | `R` | Two collectors merged (Java 12) |
| `toUnmodifiableMap()` | `Map<K,V>` | Unmodifiable map |

---

### Stream Architecture Diagram

```
Java Streams Architecture
│
├── Sources
│   ├── Collection.stream() / .parallelStream()
│   ├── Arrays.stream(array)
│   ├── Stream.of(values...)
│   ├── Stream.iterate(seed, op)
│   ├── Stream.generate(supplier)
│   ├── IntStream.range / rangeClosed
│   ├── Files.lines(path)
│   └── Stream.concat(s1, s2)
│
├── Intermediate Operations (LAZY)
│   ├── Filtering:   filter, distinct, limit, skip, takeWhile, dropWhile
│   ├── Mapping:     map, flatMap, mapToInt/Long/Double, mapToObj
│   ├── Sorting:     sorted
│   └── Inspecting:  peek
│
├── Terminal Operations (EAGER — triggers pipeline)
│   ├── Collecting:  collect, toList, toArray
│   ├── Iterating:   forEach, forEachOrdered
│   ├── Aggregating: reduce, count, sum, min, max, average
│   ├── Searching:   findFirst, findAny
│   └── Matching:    anyMatch, allMatch, noneMatch
│
├── Collectors (rich accumulation)
│   ├── Containers:  toList, toSet, toMap
│   ├── String:      joining
│   ├── Grouping:    groupingBy, partitioningBy
│   ├── Statistics:  counting, summing, averaging, summarizing
│   ├── Extremes:    minBy, maxBy
│   ├── Transform:   mapping, filtering, teeing
│   └── Custom:      Collector.of(...)
│
├── Primitive Streams (no boxing)
│   ├── IntStream    — int operations, range, rangeClosed
│   ├── LongStream   — long operations
│   └── DoubleStream — double operations
│
├── Parallel Streams
│   ├── .parallelStream() or .parallel()
│   ├── Uses ForkJoinPool.commonPool()
│   └── Best for: large data, CPU-bound, stateless, splittable sources
│
└── Key Java 9–16 Additions
    ├── Java 9:  takeWhile, dropWhile, ofNullable, iterate(pred), Optional.stream()
    ├── Java 12: Collectors.teeing()
    └── Java 16: Stream.toList()
```

---

*Made with ❤️ for Java developers — covers Java 8 through Java 21*
