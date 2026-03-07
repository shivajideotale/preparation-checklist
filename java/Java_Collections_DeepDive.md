# ☕ Java Collections — Deep Dive Complete Guide

> `java.util` Collections Framework — Java 2+ through Java 21

---

## 📌 Table of Contents

1. [What is the Collections Framework?](#1-what-is-the-collections-framework)
2. [Collections Hierarchy](#2-collections-hierarchy)
3. [List Interface & Implementations](#3-list-interface--implementations)
4. [Set Interface & Implementations](#4-set-interface--implementations)
5. [Map Interface & Implementations](#5-map-interface--implementations)
6. [Queue & Deque Interface](#6-queue--deque-interface)
7. [Stack](#7-stack)
8. [Iterator & ListIterator](#8-iterator--listiterator)
9. [Comparable vs Comparator](#9-comparable-vs-comparator)
10. [Collections Utility Class](#10-collections-utility-class)
11. [Arrays Utility Class](#11-arrays-utility-class)
12. [Generics in Collections](#12-generics-in-collections)
13. [Fail-Fast vs Fail-Safe Iterators](#13-fail-fast-vs-fail-safe-iterators)
14. [Unmodifiable & Immutable Collections](#14-unmodifiable--immutable-collections)
15. [Thread-Safe Collections](#15-thread-safe-collections)
16. [HashMap Internals — Deep Dive](#16-hashmap-internals--deep-dive)
17. [Performance Comparison](#17-performance-comparison)
18. [Real-World Patterns](#18-real-world-patterns)
19. [Java 9–21 Enhancements](#19-java-921-enhancements)
20. [Interview Questions & Answers](#20-interview-questions--answers)
21. [Complete Reference Summary](#21-complete-reference-summary)

---

## 1. What is the Collections Framework?

The **Java Collections Framework (JCF)** is a unified architecture for representing and manipulating groups of objects. It provides:

- **Interfaces** — abstract data types (List, Set, Map, Queue)
- **Implementations** — concrete classes (ArrayList, HashMap, TreeSet)
- **Algorithms** — static methods on the `Collections` class (sort, shuffle, binarySearch)

```
Without Collections Framework:
  int[] arr = new int[10];   // Fixed size!
  // No built-in search, sort, or resizing

With Collections Framework:
  List<String>    names   = new ArrayList<>();   // Dynamic size
  Map<String,Int> scores  = new HashMap<>();     // Key-value pairs
  Set<String>     unique  = new HashSet<>();     // No duplicates
  Queue<Task>     tasks   = new LinkedList<>();  // FIFO processing
```

### Why Use Collections?

| Feature                | Arrays         | Collections                   |
|------------------------|----------------|-------------------------------|
| Size                   | Fixed          | Dynamic (grows/shrinks)       |
| Type safety            | Primitives OK  | Generics (objects only)       |
| Built-in algorithms    | Limited        | sort, search, shuffle, etc.   |
| Null handling          | Manual         | Defined per implementation    |
| Thread safety          | Manual         | Concurrent versions available |
| Functional support     | Limited        | Full Stream API integration   |

---

## 2. Collections Hierarchy

```
java.lang.Iterable
    │
    └── java.util.Collection
            │
            ├── List  (ordered, allows duplicates, index-based)
            │     ├── ArrayList
            │     ├── LinkedList
            │     ├── Vector  (legacy)
            │     └── Stack   (legacy, extends Vector)
            │
            ├── Set  (no duplicates)
            │     ├── HashSet         (no order)
            │     ├── LinkedHashSet   (insertion order)
            │     └── TreeSet         (sorted order)
            │
            └── Queue  (FIFO / priority)
                  ├── LinkedList      (also implements List)
                  ├── PriorityQueue   (heap-based)
                  └── Deque  (double-ended queue)
                        ├── ArrayDeque
                        └── LinkedList

java.util.Map  (key-value pairs, NOT a Collection)
    ├── HashMap          (no order)
    ├── LinkedHashMap    (insertion order)
    ├── TreeMap          (sorted by key)
    ├── Hashtable        (legacy, synchronized)
    └── WeakHashMap      (weak-reference keys)
```

---

## 3. List Interface & Implementations

A **List** is an **ordered** collection that **allows duplicates** and supports **index-based access**.

### Core List Operations

```java
import java.util.*;

List<String> list = new ArrayList<>();

// Adding
list.add("Apple");              // Appends to end
list.add(0, "Mango");           // Inserts at index
list.addAll(List.of("B","C"));  // Adds all from another collection

// Reading
String first = list.get(0);     // By index
int idx = list.indexOf("Apple"); // First occurrence index
int lidx= list.lastIndexOf("Apple"); // Last occurrence index
boolean has = list.contains("Apple");// Contains check
int size = list.size();

// Updating
list.set(0, "Banana");          // Replace at index

// Removing
list.remove("Apple");           // By value (first occurrence)
list.remove(0);                 // By index
list.removeAll(List.of("B"));   // Remove all matching
list.retainAll(List.of("C"));   // Keep only matching

// Sub-list (view — modifications affect original!)
List<String> sub = list.subList(0, 2); // indices 0 (inclusive) to 2 (exclusive)

// Convert to array
Object[] arr = list.toArray();
String[] strArr = list.toArray(String[]::new); // Java 11+

// Iterate
for (String s : list) System.out.println(s);
list.forEach(System.out::println);
```

---

### ✅ ArrayList — Dynamic Array

**Best for:** Random access, iteration, when reads >> writes.

```java
import java.util.*;

public class ArrayListDeepDive {
    public static void main(String[] args) {

        // Default initial capacity = 10; grows by 50% when full
        ArrayList<String> names = new ArrayList<>();

        // Specify initial capacity to avoid resizing if size is known
        ArrayList<Integer> optimized = new ArrayList<>(1000);

        names.add("Alice");
        names.add("Bob");
        names.add("Charlie");
        names.add("Diana");
        names.add("Eve");

        // Indexed access — O(1)
        System.out.println("Index 2: " + names.get(2));    // Charlie

        // Insert at index — O(n) due to shifting
        names.add(2, "Zara");
        System.out.println("After insert at 2: " + names); // [Alice, Bob, Zara, Charlie, Diana, Eve]

        // Remove by index — O(n) due to shifting
        names.remove(2);
        System.out.println("After remove at 2: " + names); // [Alice, Bob, Charlie, Diana, Eve]

        // Search — O(n)
        System.out.println("Index of Charlie: " + names.indexOf("Charlie")); // 2

        // Sort — O(n log n)
        Collections.sort(names);
        System.out.println("Sorted: " + names); // [Alice, Bob, Charlie, Diana, Eve]

        // Check isEmpty / size
        System.out.println("Empty? " + names.isEmpty()); // false
        System.out.println("Size:  " + names.size());    // 5

        // trimToSize — release unused capacity
        names.trimToSize();

        // ensureCapacity — pre-allocate for bulk adds
        names.ensureCapacity(100);

        // Clear
        names.clear();
        System.out.println("After clear: " + names); // []
    }
}
```

**Internal resizing:**
```
Initial capacity: 10
After 11th add:   newCapacity = oldCapacity * 1.5 = 15
After 16th add:   newCapacity = 15 * 1.5 = 22
...
```

---

### ✅ LinkedList — Doubly Linked List

**Best for:** Frequent insertions/deletions at beginning or middle, implementing Queue/Deque.

```java
import java.util.*;

public class LinkedListDeepDive {
    public static void main(String[] args) {

        LinkedList<String> list = new LinkedList<>();

        // List operations
        list.add("B");
        list.add("C");
        list.add("D");

        // Deque operations — O(1) at both ends
        list.addFirst("A");     // Add to front
        list.addLast("E");      // Add to back
        System.out.println(list); // [A, B, C, D, E]

        System.out.println("First: " + list.getFirst());  // A
        System.out.println("Last:  " + list.getLast());   // E

        list.removeFirst(); // O(1)
        list.removeLast();  // O(1)
        System.out.println("After removing ends: " + list); // [B, C, D]

        // Queue operations (FIFO)
        list.offer("E");            // Enqueue (same as addLast)
        String head = list.poll();  // Dequeue (same as removeFirst) — returns null if empty
        String peek = list.peek();  // Peek at head without removing
        System.out.println("poll: " + head + " | peek: " + peek); // poll: B | peek: C

        // Stack operations (LIFO)
        list.push("X");             // Push to front (same as addFirst)
        String top = list.pop();    // Pop from front (same as removeFirst)
        System.out.println("push/pop: " + top); // X

        // Get by index — O(n) traversal!
        System.out.println("Index 0: " + list.get(0)); // C (after all above ops)
    }
}
```

**Internal structure:**
```
null ← [A] ⇄ [B] ⇄ [C] ⇄ [D] → null
       head                tail
```

---

### ArrayList vs LinkedList — Comparison

| Operation              | ArrayList       | LinkedList      |
|------------------------|-----------------|-----------------|
| `get(index)`           | O(1) ✅         | O(n) ❌         |
| `add(end)`             | O(1) amortized  | O(1) ✅         |
| `add(middle)`          | O(n) shifting   | O(n) traversal + O(1) insert |
| `remove(index)`        | O(n) shifting   | O(n) traversal + O(1) remove |
| `remove(first/last)`   | O(n)            | O(1) ✅         |
| `contains()`           | O(n)            | O(n)            |
| Memory                 | Less (array)    | More (node pointers) |
| Cache performance      | ✅ Better (contiguous) | ❌ Pointer chasing |

> **Rule of thumb:** Use `ArrayList` by default. Use `LinkedList` only when you have heavy insertions/deletions at the ends.

---

### Vector and Stack (Legacy — Avoid)

```java
// Vector — synchronized ArrayList; prefer ArrayList + explicit sync
Vector<String> v = new Vector<>();
v.add("A"); v.add("B");

// Stack — extends Vector; prefer ArrayDeque
Stack<Integer> stack = new Stack<>();
stack.push(1); stack.push(2); stack.push(3);
System.out.println(stack.pop());  // 3 (LIFO)
System.out.println(stack.peek()); // 2

// ✅ Prefer ArrayDeque over Stack
Deque<Integer> modernStack = new ArrayDeque<>();
modernStack.push(1); modernStack.push(2); modernStack.push(3);
System.out.println(modernStack.pop()); // 3
```

---

## 4. Set Interface & Implementations

A **Set** is a collection that **does not allow duplicate elements**. It models the mathematical set abstraction.

---

### ✅ HashSet — No Order, O(1) Operations

**Best for:** Fast membership testing, removing duplicates, when order doesn't matter.

```java
import java.util.*;

public class HashSetDeepDive {
    public static void main(String[] args) {

        HashSet<String> fruits = new HashSet<>();

        // Add — O(1) average
        fruits.add("Apple");
        fruits.add("Banana");
        fruits.add("Cherry");
        boolean added = fruits.add("Apple"); // Duplicate — NOT added
        System.out.println("Added duplicate: " + added); // false
        System.out.println(fruits); // [Banana, Cherry, Apple] (no order guaranteed)

        // Contains — O(1) average
        System.out.println("Has Apple:  " + fruits.contains("Apple")); // true
        System.out.println("Has Mango:  " + fruits.contains("Mango")); // false

        // Remove — O(1) average
        fruits.remove("Banana");
        System.out.println("After remove: " + fruits); // [Cherry, Apple]

        // Set operations
        Set<String> setA = new HashSet<>(Set.of("A", "B", "C", "D"));
        Set<String> setB = new HashSet<>(Set.of("C", "D", "E", "F"));

        // Union
        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);
        System.out.println("Union:        " + union); // [A, B, C, D, E, F]

        // Intersection
        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);
        System.out.println("Intersection: " + intersection); // [C, D]

        // Difference (A - B)
        Set<String> difference = new HashSet<>(setA);
        difference.removeAll(setB);
        System.out.println("Difference:   " + difference); // [A, B]

        // Subset check
        System.out.println("A subset of B? " + setB.containsAll(setA)); // false
    }
}
```

---

### ✅ LinkedHashSet — Insertion Order Preserved

**Best for:** When you need a Set that maintains insertion order.

```java
import java.util.*;

LinkedHashSet<String> linked = new LinkedHashSet<>();
linked.add("Banana");
linked.add("Apple");
linked.add("Cherry");
linked.add("Apple"); // Duplicate ignored, order maintained

System.out.println(linked); // [Banana, Apple, Cherry] ← insertion order preserved!

// Remove duplicates from list while preserving order
List<String> withDups = List.of("c", "a", "b", "a", "c", "d", "b");
Set<String> uniqueOrdered = new LinkedHashSet<>(withDups);
System.out.println(uniqueOrdered); // [c, a, b, d]
```

---

### ✅ TreeSet — Sorted Order, O(log n) Operations

**Best for:** Sorted unique elements, range queries.

```java
import java.util.*;

public class TreeSetDeepDive {
    public static void main(String[] args) {

        TreeSet<Integer> numbers = new TreeSet<>();
        numbers.add(5); numbers.add(1); numbers.add(9);
        numbers.add(3); numbers.add(7); numbers.add(2);

        System.out.println("TreeSet: " + numbers); // [1, 2, 3, 5, 7, 9] ← sorted!

        // Navigation methods — O(log n)
        System.out.println("first():          " + numbers.first());          // 1
        System.out.println("last():           " + numbers.last());           // 9
        System.out.println("floor(4):         " + numbers.floor(4));         // 3 (≤ 4)
        System.out.println("ceiling(4):       " + numbers.ceiling(4));       // 5 (≥ 4)
        System.out.println("lower(5):         " + numbers.lower(5));         // 3 (< 5)
        System.out.println("higher(5):        " + numbers.higher(5));        // 7 (> 5)

        // Range views — all are LIVE views of the original set
        System.out.println("headSet(5):       " + numbers.headSet(5));       // [1, 2, 3] (< 5)
        System.out.println("tailSet(5):       " + numbers.tailSet(5));       // [5, 7, 9] (≥ 5)
        System.out.println("subSet(2,7):      " + numbers.subSet(2, 7));     // [2, 3, 5] (2≤x<7)
        System.out.println("subSet(2T,7T):    " + numbers.subSet(2, true, 7, true)); // [2,3,5,7]

        // pollFirst / pollLast — retrieve and remove
        System.out.println("pollFirst():      " + numbers.pollFirst()); // 1
        System.out.println("pollLast():       " + numbers.pollLast());  // 9
        System.out.println("After polls:      " + numbers); // [2, 3, 5, 7]

        // Custom ordering — reverse
        TreeSet<String> reversed = new TreeSet<>(Comparator.reverseOrder());
        reversed.addAll(List.of("banana", "apple", "cherry"));
        System.out.println("Reversed TreeSet: " + reversed); // [cherry, banana, apple]

        // Natural order with custom object
        TreeSet<String> byLength = new TreeSet<>(
            Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder())
        );
        byLength.addAll(List.of("banana", "kiwi", "fig", "apple", "pear"));
        System.out.println("By length: " + byLength); // [fig, kiwi, pear, apple, banana]
    }
}
```

---

### Set Comparison Table

| Feature              | HashSet      | LinkedHashSet        | TreeSet          |
|----------------------|:------------:|:--------------------:|:----------------:|
| Order                | None         | Insertion order      | Sorted (natural/Comparator) |
| `add/remove/contains`| O(1) avg     | O(1) avg             | O(log n)         |
| Null allowed         | ✅ One null  | ✅ One null          | ❌ (throws NPE)  |
| Underlying structure | HashMap      | LinkedHashMap        | Red-Black Tree   |
| Navigation methods   | ❌           | ❌                   | ✅ (floor, ceiling, range) |
| Memory               | Least        | More (linked nodes)  | More (tree nodes)|

---

## 5. Map Interface & Implementations

A **Map** stores **key-value pairs**. Keys must be unique; values can be duplicated. Map does **NOT** extend `Collection`.

### Core Map Operations

```java
import java.util.*;

Map<String, Integer> map = new HashMap<>();

// Adding / Updating
map.put("Alice",   90);        // Add or replace
map.put("Bob",     85);
map.putIfAbsent("Alice", 100); // Only adds if key NOT present (Alice stays 90)
map.putAll(Map.of("Carol", 78, "Dave", 92)); // Add all

// Reading
int score = map.get("Alice");                      // 90 — null if not found
int safe  = map.getOrDefault("Unknown", 0);        // 0 — safe default
boolean hasAlice = map.containsKey("Alice");        // true
boolean has90    = map.containsValue(90);           // true
int size = map.size();

// Updating
map.replace("Alice", 95);                          // Replace only if key exists
map.replace("Bob", 85, 88);                        // Replace only if key+value match
map.compute("Alice", (k, v) -> v == null ? 1 : v + 5); // Alice: 90+5=95
map.computeIfAbsent("Eve", k -> k.length() * 10); // Add Eve:30 (key absent)
map.computeIfPresent("Bob", (k, v) -> v + 10);    // Bob: 85→95 (key present)
map.merge("Alice", 10, Integer::sum);              // Alice: 95+10=105

// Removing
map.remove("Dave");                                // Remove by key
map.remove("Bob", 88);                             // Remove only if key+value match

// Iterating — 3 ways
for (Map.Entry<String, Integer> entry : map.entrySet()) {
    System.out.println(entry.getKey() + " → " + entry.getValue());
}
map.forEach((k, v) -> System.out.println(k + " → " + v));
map.keySet().forEach(k  -> System.out.println(k));
map.values().forEach(v  -> System.out.println(v));
```

---

### ✅ HashMap — No Order, O(1) Average

**Best for:** General-purpose key-value storage when order doesn't matter.

```java
import java.util.*;

public class HashMapDeepDive {
    public static void main(String[] args) {

        // Default: initial capacity=16, load factor=0.75
        HashMap<String, List<String>> phoneBook = new HashMap<>();

        // Add entries
        phoneBook.put("Alice", new ArrayList<>(List.of("555-1234")));
        phoneBook.put("Bob",   new ArrayList<>(List.of("555-5678")));
        phoneBook.put("Carol", new ArrayList<>(List.of("555-9012")));

        // computeIfAbsent — add phone to existing or create new list
        phoneBook.computeIfAbsent("Dave",  k -> new ArrayList<>()).add("555-3456");
        phoneBook.computeIfAbsent("Alice", k -> new ArrayList<>()).add("555-7890"); // Adds to existing list

        System.out.println("Alice's numbers: " + phoneBook.get("Alice")); // [555-1234, 555-7890]

        // Frequency counter with merge
        String text = "the cat sat on the mat the cat";
        Map<String, Integer> freq = new HashMap<>();
        for (String word : text.split(" ")) {
            freq.merge(word, 1, Integer::sum);
        }
        System.out.println("Word frequencies: " + freq);
        // {on=1, the=3, sat=1, mat=1, cat=2}

        // Null key and value are allowed (only ONE null key)
        Map<String, String> withNulls = new HashMap<>();
        withNulls.put(null, "null-key-value");
        withNulls.put("key", null);
        System.out.println(withNulls.get(null)); // null-key-value
        System.out.println(withNulls.get("key")); // null
    }
}
```

---

### ✅ LinkedHashMap — Insertion Order or Access Order

**Best for:** Ordered map, LRU cache implementation.

```java
import java.util.*;

public class LinkedHashMapDeepDive {
    public static void main(String[] args) {

        // Default: insertion-order
        LinkedHashMap<String, Integer> insertionOrder = new LinkedHashMap<>();
        insertionOrder.put("C", 3);
        insertionOrder.put("A", 1);
        insertionOrder.put("B", 2);

        System.out.println("Insertion order: " + insertionOrder);
        // {C=3, A=1, B=2} ← preserved!

        // Access-order LinkedHashMap — great for LRU cache
        // accessOrder=true: entry moves to END on every get/put
        LinkedHashMap<String, Integer> lruCache = new LinkedHashMap<>(16, 0.75f, true) {
            private static final int MAX_SIZE = 3;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                return size() > MAX_SIZE; // Evict when over capacity
            }
        };

        lruCache.put("A", 1);
        lruCache.put("B", 2);
        lruCache.put("C", 3);
        System.out.println("LRU cache: " + lruCache); // {A=1, B=2, C=3}

        lruCache.get("A");  // A accessed → moves to end
        System.out.println("After get(A): " + lruCache); // {B=2, C=3, A=1}

        lruCache.put("D", 4); // D added → B evicted (oldest unused)
        System.out.println("After put(D): " + lruCache); // {C=3, A=1, D=4}
    }
}
```

---

### ✅ TreeMap — Sorted by Key, O(log n)

**Best for:** Sorted key-value pairs, range queries on keys.

```java
import java.util.*;

public class TreeMapDeepDive {
    public static void main(String[] args) {

        TreeMap<String, Integer> scores = new TreeMap<>();
        scores.put("Charlie", 85);
        scores.put("Alice",   92);
        scores.put("Eve",     78);
        scores.put("Bob",     88);
        scores.put("Diana",   95);

        System.out.println("TreeMap (sorted): " + scores);
        // {Alice=92, Bob=88, Charlie=85, Diana=95, Eve=78}

        // Navigation — O(log n)
        System.out.println("firstKey():         " + scores.firstKey());        // Alice
        System.out.println("lastKey():          " + scores.lastKey());         // Eve
        System.out.println("floorKey(C):        " + scores.floorKey("C"));     // Charlie
        System.out.println("ceilingKey(C):      " + scores.ceilingKey("C"));   // Charlie
        System.out.println("lowerKey(Charlie):  " + scores.lowerKey("Charlie")); // Bob
        System.out.println("higherKey(Charlie): " + scores.higherKey("Charlie")); // Diana

        // Range views
        System.out.println("headMap(Charlie):   " + scores.headMap("Charlie"));      // {Alice=92, Bob=88}
        System.out.println("tailMap(Charlie):   " + scores.tailMap("Charlie"));      // {Charlie=85, Diana=95, Eve=78}
        System.out.println("subMap(Bob,Diana):  " + scores.subMap("Bob", "Diana"));  // {Bob=88, Charlie=85}

        // pollFirstEntry / pollLastEntry
        Map.Entry<String, Integer> first = scores.pollFirstEntry();
        System.out.println("pollFirst: " + first.getKey() + "=" + first.getValue()); // Alice=92

        // Reverse order
        NavigableMap<String, Integer> reversed = scores.descendingMap();
        System.out.println("Descending: " + reversed); // {Eve=78, Diana=95, Charlie=85, Bob=88}
    }
}
```

---

### Map Comparison Table

| Feature              | HashMap      | LinkedHashMap       | TreeMap              |
|----------------------|:------------:|:-------------------:|:--------------------:|
| Ordering             | None         | Insertion / Access  | Sorted by key        |
| `get/put/remove`     | O(1) avg     | O(1) avg            | O(log n)             |
| Null keys            | ✅ One       | ✅ One              | ❌ (NPE)             |
| Null values          | ✅ Multiple  | ✅ Multiple         | ✅ Multiple          |
| Navigation methods   | ❌           | ❌                  | ✅ (floor, range)    |
| Underlying structure | Hash table   | Hash table + DLL    | Red-Black Tree       |
| Thread safety        | ❌           | ❌                  | ❌                   |

---

## 6. Queue & Deque Interface

**Queue** = FIFO (First-In-First-Out)  
**Deque** = Double-Ended Queue (add/remove from both ends)

---

### Queue Method Reference

| Action      | Throws Exception | Returns null/false |
|-------------|-----------------|-------------------|
| Insert      | `add(e)`        | `offer(e)`        |
| Remove head | `remove()`      | `poll()`          |
| Examine head| `element()`     | `peek()`          |

---

### ✅ PriorityQueue — Min-Heap by Default

**Best for:** Processing elements by priority, scheduling.

```java
import java.util.*;

public class PriorityQueueDeepDive {
    public static void main(String[] args) {

        // Min-heap by default (smallest element at head)
        PriorityQueue<Integer> minHeap = new PriorityQueue<>();
        minHeap.offer(5);
        minHeap.offer(1);
        minHeap.offer(3);
        minHeap.offer(2);
        minHeap.offer(4);

        System.out.print("Min-heap poll order: ");
        while (!minHeap.isEmpty()) {
            System.out.print(minHeap.poll() + " "); // 1 2 3 4 5
        }
        System.out.println();

        // Max-heap using reverseOrder()
        PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Comparator.reverseOrder());
        maxHeap.addAll(List.of(5, 1, 3, 2, 4));

        System.out.print("Max-heap poll order: ");
        while (!maxHeap.isEmpty()) {
            System.out.print(maxHeap.poll() + " "); // 5 4 3 2 1
        }
        System.out.println();

        // PriorityQueue with custom objects
        record Task(String name, int priority) {}

        PriorityQueue<Task> taskQueue = new PriorityQueue<>(
            Comparator.comparingInt(Task::priority).reversed() // Highest priority first
        );

        taskQueue.offer(new Task("Low-priority backup",      1));
        taskQueue.offer(new Task("Critical server restart", 10));
        taskQueue.offer(new Task("Normal report generation", 5));
        taskQueue.offer(new Task("Urgent security patch",    9));

        System.out.println("Processing tasks by priority:");
        while (!taskQueue.isEmpty()) {
            Task t = taskQueue.poll();
            System.out.printf("  [%d] %s%n", t.priority(), t.name());
        }
        // [10] Critical server restart
        // [9]  Urgent security patch
        // [5]  Normal report generation
        // [1]  Low-priority backup

        // Peek — inspect head without removing
        PriorityQueue<Integer> pq = new PriorityQueue<>(List.of(3, 1, 2));
        System.out.println("Peek (min): " + pq.peek()); // 1 (not removed)
        System.out.println("Size:       " + pq.size()); // 3
    }
}
```

---

### ✅ ArrayDeque — Fast Double-Ended Queue / Stack

**Best for:** Stack and Queue operations, BFS/DFS, sliding window problems.

```java
import java.util.*;

public class ArrayDequeDeepDive {
    public static void main(String[] args) {

        ArrayDeque<String> deque = new ArrayDeque<>();

        // Add to both ends — O(1)
        deque.addFirst("B");
        deque.addFirst("A");  // Front
        deque.addLast("C");
        deque.addLast("D");   // Back
        System.out.println("Deque: " + deque); // [A, B, C, D]

        // Peek — no removal
        System.out.println("peekFirst: " + deque.peekFirst()); // A
        System.out.println("peekLast:  " + deque.peekLast());  // D

        // Remove from both ends — O(1)
        System.out.println("pollFirst: " + deque.pollFirst()); // A
        System.out.println("pollLast:  " + deque.pollLast());  // D
        System.out.println("Deque: " + deque); // [B, C]

        // ── Use as STACK (LIFO) ─────────────────────────────────────
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(1);  // addFirst
        stack.push(2);
        stack.push(3);
        System.out.println("Stack: " + stack);           // [3, 2, 1]
        System.out.println("pop:   " + stack.pop());     // 3 (removeFirst)
        System.out.println("peek:  " + stack.peek());    // 2

        // ── Use as QUEUE (FIFO) ─────────────────────────────────────
        Deque<String> queue = new ArrayDeque<>();
        queue.offer("first");   // addLast
        queue.offer("second");
        queue.offer("third");
        System.out.println("Queue: " + queue);           // [first, second, third]
        System.out.println("poll: " + queue.poll());     // first (removeFirst)
        System.out.println("peek: " + queue.peek());     // second

        // ── Sliding window maximum ──────────────────────────────────
        int[] arr = {1, 3, -1, -3, 5, 3, 6, 7};
        int k = 3; // Window size
        Deque<Integer> window = new ArrayDeque<>();
        List<Integer> maxValues = new ArrayList<>();

        for (int i = 0; i < arr.length; i++) {
            // Remove out-of-window indices
            if (!window.isEmpty() && window.peekFirst() < i - k + 1) {
                window.pollFirst();
            }
            // Remove smaller elements from back
            while (!window.isEmpty() && arr[window.peekLast()] < arr[i]) {
                window.pollLast();
            }
            window.addLast(i);
            if (i >= k - 1) {
                maxValues.add(arr[window.peekFirst()]);
            }
        }
        System.out.println("Sliding window max: " + maxValues); // [3, 3, 5, 5, 6, 7]
    }
}
```

---

## 7. Stack

```java
import java.util.*;

// ── Balanced Parentheses Checker using Stack ─────────────────────────────────
public class StackPatterns {

    static boolean isBalanced(String expression) {
        Deque<Character> stack = new ArrayDeque<>();
        Map<Character, Character> pairs = Map.of(')', '(', '}', '{', ']', '[');

        for (char c : expression.toCharArray()) {
            if ("({[".indexOf(c) >= 0) {
                stack.push(c);
            } else if (pairs.containsKey(c)) {
                if (stack.isEmpty() || stack.pop() != pairs.get(c)) return false;
            }
        }
        return stack.isEmpty();
    }

    // ── Evaluate Reverse Polish Notation ─────────────────────────────────────
    static int evalRPN(String[] tokens) {
        Deque<Integer> stack = new ArrayDeque<>();
        Set<String> ops = Set.of("+", "-", "*", "/");

        for (String token : tokens) {
            if (ops.contains(token)) {
                int b = stack.pop(), a = stack.pop();
                switch (token) {
                    case "+" -> stack.push(a + b);
                    case "-" -> stack.push(a - b);
                    case "*" -> stack.push(a * b);
                    case "/" -> stack.push(a / b);
                }
            } else {
                stack.push(Integer.parseInt(token));
            }
        }
        return stack.pop();
    }

    public static void main(String[] args) {
        System.out.println(isBalanced("({[]})"));    // true
        System.out.println(isBalanced("([)]"));      // false
        System.out.println(isBalanced("{[()]}"));    // true

        // 2 3 + 4 * = (2+3)*4 = 20
        System.out.println(evalRPN(new String[]{"2","3","+","4","*"})); // 20
        // 5 1 2 + 4 * + 3 - = 5 + (1+2)*4 - 3 = 14
        System.out.println(evalRPN(new String[]{"5","1","2","+","4","*","+","3","-"})); // 14
    }
}
```

---

## 8. Iterator & ListIterator

**Iterator** allows safe traversal and removal during iteration.  
**ListIterator** extends Iterator with bidirectional traversal and modification.

```java
import java.util.*;

public class IteratorDeepDive {
    public static void main(String[] args) {

        List<String> names = new ArrayList<>(List.of("Alice", "Bob", "Charlie", "Diana", "Eve"));

        // ── Basic Iterator ────────────────────────────────────────────────────
        Iterator<String> it = names.iterator();
        while (it.hasNext()) {
            String name = it.next();
            if (name.startsWith("C")) {
                it.remove(); // Safe removal during iteration
            }
        }
        System.out.println("After iterator remove: " + names); // [Alice, Bob, Diana, Eve]

        // ── ListIterator — bidirectional ──────────────────────────────────────
        ListIterator<String> lit = names.listIterator();

        // Forward traversal
        System.out.print("Forward: ");
        while (lit.hasNext()) {
            System.out.print(lit.nextIndex() + ":" + lit.next() + " ");
        }
        System.out.println();

        // Backward traversal
        System.out.print("Backward: ");
        while (lit.hasPrevious()) {
            System.out.print(lit.previousIndex() + ":" + lit.previous() + " ");
        }
        System.out.println();

        // Modify while iterating
        ListIterator<String> modIt = names.listIterator();
        while (modIt.hasNext()) {
            String name = modIt.next();
            modIt.set(name.toUpperCase()); // Replace current element
        }
        System.out.println("After set: " + names); // [ALICE, BOB, DIANA, EVE]

        // Add during iteration
        ListIterator<String> addIt = names.listIterator();
        while (addIt.hasNext()) {
            String name = addIt.next();
            if (name.equals("BOB")) {
                addIt.add("CAROL"); // Add after BOB
            }
        }
        System.out.println("After add: " + names); // [ALICE, BOB, CAROL, DIANA, EVE]

        // ── Map Iterator ─────────────────────────────────────────────────────
        Map<String, Integer> map = new HashMap<>(Map.of("a", 1, "b", 2, "c", 3));
        Iterator<Map.Entry<String, Integer>> mapIt = map.entrySet().iterator();
        while (mapIt.hasNext()) {
            Map.Entry<String, Integer> entry = mapIt.next();
            if (entry.getValue() == 2) mapIt.remove(); // Safe map removal
        }
        System.out.println("Map after remove: " + map); // {a=1, c=3}
    }
}
```

---

## 9. Comparable vs Comparator

Both define ordering for objects, but in different ways.

| Feature          | `Comparable<T>`            | `Comparator<T>`              |
|------------------|---------------------------|------------------------------|
| Location         | Inside the class           | Outside (separate class / lambda) |
| Method           | `compareTo(T other)`       | `compare(T o1, T o2)`        |
| Modifies source? | Yes — modify the class     | No — can be added externally |
| Number of orderings | ONE natural ordering    | MANY different orderings     |
| Package          | `java.lang`                | `java.util`                  |
| Return value     | negative/0/positive        | negative/0/positive          |

---

### Comparable — Natural Ordering

```java
import java.util.*;

class Student implements Comparable<Student> {
    String name;
    int    grade;
    double gpa;

    Student(String name, int grade, double gpa) {
        this.name  = name;
        this.grade = grade;
        this.gpa   = gpa;
    }

    @Override
    public int compareTo(Student other) {
        // Natural order: by grade ascending
        return Integer.compare(this.grade, other.grade);
    }

    @Override
    public String toString() {
        return String.format("%s(g:%d,gpa:%.1f)", name, grade, gpa);
    }
}

public class ComparableDemo {
    public static void main(String[] args) {
        List<Student> students = new ArrayList<>(List.of(
            new Student("Charlie", 11, 3.5),
            new Student("Alice",   10, 3.8),
            new Student("Diana",   12, 3.2),
            new Student("Bob",     10, 3.6)
        ));

        Collections.sort(students); // Uses compareTo
        System.out.println("Natural (by grade): " + students);
        // [Alice(g:10,gpa:3.8), Bob(g:10,gpa:3.6), Charlie(g:11,gpa:3.5), Diana(g:12,gpa:3.2)]

        TreeSet<Student> tree = new TreeSet<>(students); // Uses compareTo for ordering
        System.out.println("TreeSet: " + tree);
    }
}
```

---

### Comparator — Custom Ordering

```java
import java.util.*;

public class ComparatorDemo {
    public static void main(String[] args) {
        List<Student> students = new ArrayList<>(List.of(
            new Student("Charlie", 11, 3.5),
            new Student("Alice",   10, 3.8),
            new Student("Diana",   12, 3.2),
            new Student("Bob",     10, 3.6)
        ));

        // By GPA descending
        students.sort(Comparator.comparingDouble(Student::gpa).reversed());
        System.out.println("By GPA desc: " + students);
        // [Alice(g:10,gpa:3.8), Bob(g:10,gpa:3.6), Charlie(g:11,gpa:3.5), Diana(g:12,gpa:3.2)]

        // By grade, then by GPA descending within same grade
        students.sort(
            Comparator.comparingInt((Student s) -> s.grade)
                      .thenComparingDouble((Student s) -> -s.gpa) // Negate for desc
        );
        System.out.println("Grade then GPA desc: " + students);

        // By name length, then alphabetically
        students.sort(
            Comparator.comparingInt((Student s) -> s.name.length())
                      .thenComparing(s -> s.name)
        );
        System.out.println("Name length then alpha: " + students);

        // Nulls-safe comparator (nulls last)
        List<String> withNulls = new ArrayList<>(Arrays.asList("banana", null, "apple", null, "cherry"));
        withNulls.sort(Comparator.nullsLast(Comparator.naturalOrder()));
        System.out.println("Nulls last: " + withNulls); // [apple, banana, cherry, null, null]

        // Nulls first
        withNulls.sort(Comparator.nullsFirst(Comparator.naturalOrder()));
        System.out.println("Nulls first: " + withNulls); // [null, null, apple, banana, cherry]

        // Comparing with key extractor
        Comparator<String> byLength = Comparator.comparingInt(String::length);
        List<String> words = new ArrayList<>(List.of("date", "banana", "fig", "apple"));
        words.sort(byLength);
        System.out.println("By length: " + words); // [fig, date, apple, banana]
    }
}
```

---

## 10. Collections Utility Class

`java.util.Collections` provides static algorithms for Collection operations.

```java
import java.util.*;

public class CollectionsUtilDemo {
    public static void main(String[] args) {

        List<Integer> numbers = new ArrayList<>(List.of(3, 1, 4, 1, 5, 9, 2, 6, 5, 3));

        // ── Sorting ───────────────────────────────────────────────────────────
        Collections.sort(numbers);
        System.out.println("Sorted:         " + numbers); // [1, 1, 2, 3, 3, 4, 5, 5, 6, 9]

        Collections.sort(numbers, Comparator.reverseOrder());
        System.out.println("Reverse sorted: " + numbers); // [9, 6, 5, 5, 4, 3, 3, 2, 1, 1]

        // ── Searching (requires sorted list) ─────────────────────────────────
        Collections.sort(numbers); // Sort first!
        int idx = Collections.binarySearch(numbers, 5);
        System.out.println("BinarySearch(5): index " + idx); // Some index with value 5

        // ── Min / Max ─────────────────────────────────────────────────────────
        System.out.println("Min: " + Collections.min(numbers)); // 1
        System.out.println("Max: " + Collections.max(numbers)); // 9

        // ── Frequency & Disjoint ─────────────────────────────────────────────
        System.out.println("Frequency of 5: " + Collections.frequency(numbers, 5)); // 2

        List<Integer> a = List.of(1, 2, 3);
        List<Integer> b = List.of(4, 5, 6);
        List<Integer> c = List.of(3, 4, 5);
        System.out.println("a,b disjoint: " + Collections.disjoint(a, b)); // true (no common)
        System.out.println("a,c disjoint: " + Collections.disjoint(a, c)); // false (3 is common)

        // ── Shuffling ─────────────────────────────────────────────────────────
        Collections.shuffle(numbers);
        System.out.println("Shuffled: " + numbers);

        Collections.shuffle(numbers, new Random(42)); // Reproducible with seed

        // ── Filling & Copying ─────────────────────────────────────────────────
        List<String> filled = new ArrayList<>(Collections.nCopies(5, "Java"));
        System.out.println("nCopies: " + filled); // [Java, Java, Java, Java, Java]

        Collections.fill(filled, "Stream");
        System.out.println("fill: " + filled); // [Stream, Stream, Stream, Stream, Stream]

        List<Integer> src  = List.of(1, 2, 3);
        List<Integer> dest = new ArrayList<>(List.of(0, 0, 0, 0));
        Collections.copy(dest, src); // dest must be at least as large as src
        System.out.println("copy: " + dest); // [1, 2, 3, 0]

        // ── Reverse & Rotate ─────────────────────────────────────────────────
        List<Integer> rev = new ArrayList<>(List.of(1, 2, 3, 4, 5));
        Collections.reverse(rev);
        System.out.println("reversed: " + rev); // [5, 4, 3, 2, 1]

        Collections.rotate(rev, 2); // Rotate right by 2 positions
        System.out.println("rotated(2): " + rev); // [2, 1, 5, 4, 3]

        // ── Swap ──────────────────────────────────────────────────────────────
        List<String> sw = new ArrayList<>(List.of("A", "B", "C", "D"));
        Collections.swap(sw, 0, 3);
        System.out.println("swapped 0,3: " + sw); // [D, B, C, A]

        // ── Unmodifiable wrappers ─────────────────────────────────────────────
        List<String> mutable   = new ArrayList<>(List.of("A", "B", "C"));
        List<String> immutable = Collections.unmodifiableList(mutable);
        // immutable.add("D"); // UnsupportedOperationException
        System.out.println("Unmodifiable: " + immutable);

        // ── Singleton collections ─────────────────────────────────────────────
        List<String>    singleList = Collections.singletonList("only");
        Set<String>     singleSet  = Collections.singleton("only");
        Map<String,Int> singleMap  = Collections.singletonMap("key", 1);

        // ── Empty collections ─────────────────────────────────────────────────
        List<String> emptyList = Collections.emptyList();
        Set<String>  emptySet  = Collections.emptySet();
        Map<?,?>     emptyMap  = Collections.emptyMap();

        // ── Synchronized wrappers ─────────────────────────────────────────────
        List<String> syncList = Collections.synchronizedList(new ArrayList<>());
        Map<String, Integer> syncMap = Collections.synchronizedMap(new HashMap<>());
    }
}
```

---

## 11. Arrays Utility Class

```java
import java.util.*;
import java.util.stream.*;

public class ArraysUtilDemo {
    public static void main(String[] args) {

        int[] numbers = {5, 3, 8, 1, 9, 2, 7, 4, 6};

        // Sort
        Arrays.sort(numbers);
        System.out.println("Sorted:        " + Arrays.toString(numbers)); // [1,2,3,4,5,6,7,8,9]

        // Binary search (sorted array required)
        int idx = Arrays.binarySearch(numbers, 7);
        System.out.println("Index of 7:    " + idx); // 6

        // Copy
        int[] copy    = Arrays.copyOf(numbers, 5);       // First 5 elements
        int[] range   = Arrays.copyOfRange(numbers, 2, 6); // indices 2–5
        System.out.println("copyOf(5):     " + Arrays.toString(copy));  // [1,2,3,4,5]
        System.out.println("copyOfRange:   " + Arrays.toString(range)); // [3,4,5,6]

        // Fill
        int[] filled = new int[5];
        Arrays.fill(filled, 7);
        System.out.println("fill(7):       " + Arrays.toString(filled)); // [7,7,7,7,7]

        // Equals
        int[] a = {1, 2, 3};
        int[] b = {1, 2, 3};
        int[] c = {1, 2, 4};
        System.out.println("a==b:          " + Arrays.equals(a, b)); // true
        System.out.println("a==c:          " + Arrays.equals(a, c)); // false

        // Deep equals / toString for multi-dimensional arrays
        int[][] matrix = {{1,2},{3,4},{5,6}};
        System.out.println("deepToString:  " + Arrays.deepToString(matrix)); // [[1,2],[3,4],[5,6]]

        // Sort with Comparator (requires Integer[])
        Integer[] words = {5, 3, 8, 1};
        Arrays.sort(words, Comparator.reverseOrder());
        System.out.println("Reverse sort:  " + Arrays.toString(words)); // [8,5,3,1]

        // Convert array to List (FIXED SIZE — backed by array)
        List<String> asList = Arrays.asList("A", "B", "C");
        asList.set(0, "X"); // Allowed — modifies underlying array
        // asList.add("D");  // UnsupportedOperationException — fixed size!

        // Convert to mutable ArrayList
        List<String> mutable = new ArrayList<>(Arrays.asList("A", "B", "C"));
        mutable.add("D"); // Works!

        // Stream from array
        IntStream.of(numbers).sum();
        Arrays.stream(numbers).filter(n -> n > 5).forEach(System.out::print);

        // Parallel sort (for large arrays)
        int[] big = new int[1_000_000];
        Arrays.fill(big, 42);
        Arrays.parallelSort(big); // Uses ForkJoinPool
    }
}
```

---

## 12. Generics in Collections

Generics provide **compile-time type safety** — catch errors at compile time rather than runtime.

```java
import java.util.*;
import java.util.function.*;

public class GenericsDeepDive {

    // Generic method — works with any Comparable type
    static <T extends Comparable<T>> T findMax(List<T> list) {
        return list.stream()
                   .max(Comparator.naturalOrder())
                   .orElseThrow(() -> new NoSuchElementException("List is empty"));
    }

    // Generic pair
    static <A, B> Map.Entry<A, B> pair(A first, B second) {
        return Map.entry(first, second);
    }

    // Bounded wildcard — read from collection of T or subtypes
    static double sumList(List<? extends Number> list) {
        return list.stream().mapToDouble(Number::doubleValue).sum();
    }

    // Lower bounded wildcard — add to collection of T or supertypes
    static void addNumbers(List<? super Integer> list) {
        list.add(1);
        list.add(2);
        list.add(3);
    }

    public static void main(String[] args) {
        // Type-safe collections
        List<String>  strings  = new ArrayList<>();
        List<Integer> integers = new ArrayList<>();
        // strings.add(42); // Compile error! ← caught at compile time

        // Type inference
        var names   = new ArrayList<String>();  // Java 10+ var
        var scores  = new HashMap<String, Integer>();

        // Generic method calls
        System.out.println(findMax(List.of(3, 1, 4, 1, 5, 9))); // 9
        System.out.println(findMax(List.of("fig", "apple", "cherry"))); // fig

        // Wildcards
        List<Integer>  ints    = List.of(1, 2, 3);
        List<Double>   doubles = List.of(1.5, 2.5, 3.5);
        System.out.println("Sum ints:    " + sumList(ints));    // 6.0
        System.out.println("Sum doubles: " + sumList(doubles)); // 7.5

        // Raw types — AVOID (no type safety)
        List rawList = new ArrayList(); // No generic = no safety
        rawList.add("string");
        rawList.add(42); // No error at compile time!
        // String s = (String) rawList.get(1); // ClassCastException at runtime!
    }
}
```

---

## 13. Fail-Fast vs Fail-Safe Iterators

---

### Fail-Fast — Throws ConcurrentModificationException

```java
import java.util.*;

public class FailFastDemo {
    public static void main(String[] args) {

        List<String> list = new ArrayList<>(List.of("A", "B", "C", "D"));

        // ❌ Fail-fast: modifying collection during for-each throws exception
        try {
            for (String item : list) {
                System.out.println("Processing: " + item);
                if (item.equals("B")) {
                    list.remove(item); // ← ConcurrentModificationException!
                }
            }
        } catch (ConcurrentModificationException e) {
            System.out.println("Exception: " + e.getClass().getSimpleName());
        }

        // ✅ Fix 1: Use Iterator.remove()
        Iterator<String> it = list.iterator();
        while (it.hasNext()) {
            if (it.next().equals("C")) it.remove(); // Safe!
        }
        System.out.println("After safe remove: " + list); // [A, D]

        // ✅ Fix 2: Collect to remove, then removeAll
        list.addAll(List.of("X", "Y", "Z"));
        list.removeIf(s -> s.equals("X") || s.equals("Y")); // Java 8+
        System.out.println("After removeIf: " + list); // [A, D, Z]

        // Fail-fast with Map
        Map<String, Integer> map = new HashMap<>(Map.of("a", 1, "b", 2));
        try {
            for (String key : map.keySet()) {
                map.remove(key); // ← ConcurrentModificationException!
            }
        } catch (ConcurrentModificationException e) {
            System.out.println("Map CME: " + e.getClass().getSimpleName());
        }

        // ✅ Fix: Use entrySet iterator
        Iterator<Map.Entry<String, Integer>> mapIt = map.entrySet().iterator();
        while (mapIt.hasNext()) {
            if (mapIt.next().getValue() == 1) mapIt.remove(); // Safe
        }
        System.out.println("Map after safe remove: " + map); // {b=2}
    }
}
```

---

### Fail-Safe — No Exception (Works on a Copy)

```java
import java.util.concurrent.*;

public class FailSafeDemo {
    public static void main(String[] args) {

        // CopyOnWriteArrayList — fail-safe iterator (iterates over a snapshot)
        CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>(
            List.of("A", "B", "C", "D")
        );

        // Safe to modify during iteration — iterator uses a snapshot from start
        for (String item : list) {
            System.out.println("Processing: " + item);
            if (item.equals("B")) {
                list.add("E");    // No exception — modifies COPY
                list.remove("C"); // No exception
            }
        }
        // Original list is modified AFTER iteration
        System.out.println("List after: " + list); // [A, B, D, E]

        // ConcurrentHashMap — fail-safe
        ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>(
            Map.of("a", 1, "b", 2, "c", 3)
        );

        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            System.out.println("Key: " + entry.getKey());
            map.put("d", 4); // Safe — no ConcurrentModificationException
        }
        System.out.println("Map: " + map);
    }
}
```

---

| Feature                | Fail-Fast                           | Fail-Safe                           |
|------------------------|-------------------------------------|-------------------------------------|
| Collections            | ArrayList, HashMap, HashSet...      | CopyOnWriteArrayList, ConcurrentHashMap |
| Behavior on modification| Throws ConcurrentModificationException | No exception (iterates over snapshot/copy) |
| Memory overhead        | Lower                               | Higher (copies data)                |
| Performance            | Faster                              | Slower (copying)                    |
| Detects modification via| `modCount` internal counter         | N/A — uses snapshot                 |

---

## 14. Unmodifiable & Immutable Collections

```java
import java.util.*;

public class ImmutableCollectionsDemo {
    public static void main(String[] args) {

        // ── Collections.unmodifiable* — thin wrapper ──────────────────────────
        // Still backed by original — changes to original ARE reflected!
        List<String> mutable = new ArrayList<>(List.of("A", "B", "C"));
        List<String> unmodifiable = Collections.unmodifiableList(mutable);

        // unmodifiable.add("D"); // UnsupportedOperationException
        mutable.add("D"); // Allowed — modifies the source
        System.out.println("unmodifiable sees D: " + unmodifiable); // [A, B, C, D] ← change visible!

        // ── List.of(), Set.of(), Map.of() — Java 9+ truly immutable ──────────
        List<String>        immutableList = List.of("A", "B", "C");
        Set<Integer>        immutableSet  = Set.of(1, 2, 3);
        Map<String,Integer> immutableMap  = Map.of("a", 1, "b", 2);

        // These throw UnsupportedOperationException:
        // immutableList.add("D");
        // immutableSet.add(4);
        // immutableMap.put("c", 3);

        // List.of — does NOT allow nulls
        // List.of("a", null, "b"); // NullPointerException!

        // List.of — does NOT allow duplicate keys in Map.of
        // Map.of("a", 1, "a", 2); // IllegalArgumentException!

        // List.copyOf() — immutable copy of existing collection (Java 10+)
        List<String> copy = List.copyOf(mutable); // Snapshot — not affected by future changes
        mutable.add("E");
        System.out.println("copy not affected: " + copy); // [A, B, C, D] ← no E!

        // ── Map.entry() and Map.ofEntries() — for >10 entries ─────────────────
        Map<String, Integer> bigMap = Map.ofEntries(
            Map.entry("a", 1), Map.entry("b", 2), Map.entry("c", 3),
            Map.entry("d", 4), Map.entry("e", 5), Map.entry("f", 6),
            Map.entry("g", 7), Map.entry("h", 8), Map.entry("i", 9),
            Map.entry("j", 10), Map.entry("k", 11) // Map.of() limited to 10 entries
        );
        System.out.println("Big map size: " + bigMap.size()); // 11
    }
}
```

---

## 15. Thread-Safe Collections

```java
import java.util.*;
import java.util.concurrent.*;

public class ThreadSafeCollections {
    public static void main(String[] args) throws InterruptedException {

        // ── ConcurrentHashMap — thread-safe Map ───────────────────────────────
        ConcurrentHashMap<String, Integer> concMap = new ConcurrentHashMap<>();

        Runnable mapTask = () -> {
            for (int i = 0; i < 1000; i++) {
                concMap.merge(Thread.currentThread().getName(), 1, Integer::sum);
            }
        };

        Thread t1 = new Thread(mapTask, "T1");
        Thread t2 = new Thread(mapTask, "T2");
        t1.start(); t2.start();
        t1.join(); t2.join();

        System.out.println("ConcurrentHashMap: " + concMap); // {T1=1000, T2=1000}

        // ── CopyOnWriteArrayList — thread-safe List ───────────────────────────
        CopyOnWriteArrayList<Integer> cowList = new CopyOnWriteArrayList<>();

        Runnable listTask = () -> {
            for (int i = 0; i < 100; i++) cowList.add(i);
        };

        Thread t3 = new Thread(listTask);
        Thread t4 = new Thread(listTask);
        t3.start(); t4.start();
        t3.join(); t4.join();

        System.out.println("COW list size: " + cowList.size()); // Always 200

        // ── BlockingQueue — producer-consumer ─────────────────────────────────
        BlockingQueue<String> queue = new LinkedBlockingQueue<>(5);

        Thread producer = new Thread(() -> {
            try {
                for (int i = 1; i <= 5; i++) {
                    queue.put("Task-" + i);
                    System.out.println("Produced: Task-" + i);
                }
                queue.put("STOP"); // Poison pill
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        Thread consumer = new Thread(() -> {
            try {
                while (true) {
                    String item = queue.take();
                    if ("STOP".equals(item)) break;
                    System.out.println("Consumed: " + item);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        producer.start(); consumer.start();
        producer.join();  consumer.join();

        // ── ConcurrentSkipListMap — thread-safe sorted Map ────────────────────
        ConcurrentSkipListMap<String, Integer> skipMap = new ConcurrentSkipListMap<>();
        skipMap.put("Charlie", 3);
        skipMap.put("Alice", 1);
        skipMap.put("Bob", 2);
        System.out.println("SkipListMap: " + skipMap); // {Alice=1, Bob=2, Charlie=3} sorted!

        // ── Collections.synchronized* — legacy wrappers ───────────────────────
        // Must manually synchronize on iteration!
        List<String> syncList = Collections.synchronizedList(new ArrayList<>());
        syncList.add("A"); syncList.add("B");

        synchronized (syncList) { // Must lock manually for iteration
            for (String s : syncList) System.out.println(s);
        }
    }
}
```

---

## 16. HashMap Internals — Deep Dive

Understanding how HashMap works internally is one of the most common interview topics.

### Internal Structure

```
HashMap<K, V> internal array (table) — default capacity: 16

Index  Bucket
  0  → null
  1  → [Entry: key="Alice", val=90, hash=h1, next=null]
  2  → null
  3  → [Entry: key="Bob", val=85, hash=h2, next=→]
         → [Entry: key="Charlie", val=78, hash=h2, next=null]  ← COLLISION (same bucket)
  ...
 15  → [Entry: key="Diana", val=95, hash=h15, next=null]

Java 8+: When bucket has >8 entries → converts LinkedList to Red-Black Tree (O(n)→O(log n))
```

### How `put(key, value)` Works

```
1. key.hashCode()        →  raw hash (e.g., 123456)
2. hash spread           →  (h = key ^ (h >>> 16)) — reduces collisions
3. index calculation     →  index = hash & (capacity - 1)  (e.g., 123456 & 15 = 0)
4. Check bucket at index →
   a. Empty → create new Entry, insert
   b. Key exists (equals) → replace value
   c. Key different (collision) → append to linked list / tree
5. If load factor exceeded (size > capacity × 0.75):
   → RESIZE: new capacity = old × 2, rehash all entries
```

```java
public class HashMapInternalsDemo {
    public static void main(String[] args) {

        // Default: capacity=16, loadFactor=0.75
        // Rehash happens when size > 16 × 0.75 = 12
        HashMap<String, Integer> map = new HashMap<>();

        // Pre-size to avoid resizing if you know approximate size
        // Rule: initialCapacity = expectedSize / loadFactor + 1
        HashMap<String, Integer> presized = new HashMap<>(32); // For ~24 entries
        HashMap<String, Integer> lowLoad  = new HashMap<>(16, 0.5f); // Rehash sooner

        // Illustrate hashCode distribution
        String[] keys = {"Alice", "Bob", "Charlie", "Diana"};
        for (String key : keys) {
            int hash  = key.hashCode();
            int spread = hash ^ (hash >>> 16);
            int index  = spread & (16 - 1); // capacity=16
            System.out.printf("%-10s hashCode=%-12d index=%d%n", key, hash, index);
        }

        // Collision example — keys with same hash bucket
        // Java String: "FB" and "Ea" happen to have same hashCode in some JVMs
        map.put("a", 1);
        map.put("b", 2);
        map.put("c", 3);
        System.out.println("Size: " + map.size()); // 3

        // entrySet iteration (most efficient for both key+value)
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            System.out.println(e.getKey() + " = " + e.getValue());
        }

        // ── equals() and hashCode() contract ─────────────────────────────────
        // If key1.equals(key2) is true → key1.hashCode() MUST == key2.hashCode()
        // If hashCodes are different → keys are definitely NOT equal
        // If hashCodes are equal → keys MIGHT be equal (collision)
    }
}
```

### Why Override `equals()` AND `hashCode()`

```java
class BadKey {
    int id;
    BadKey(int id) { this.id = id; }
    // ❌ Only overrides equals, NOT hashCode
    @Override public boolean equals(Object o) {
        if (!(o instanceof BadKey)) return false;
        return this.id == ((BadKey) o).id;
    }
}

class GoodKey {
    int id;
    GoodKey(int id) { this.id = id; }
    // ✅ Overrides BOTH
    @Override public boolean equals(Object o) {
        if (!(o instanceof GoodKey)) return false;
        return this.id == ((GoodKey) o).id;
    }
    @Override public int hashCode() {
        return Integer.hashCode(id); // Consistent with equals
    }
}

public class HashCodeEqualsDemo {
    public static void main(String[] args) {
        Map<BadKey, String> badMap = new HashMap<>();
        badMap.put(new BadKey(1), "Alice");
        System.out.println(badMap.get(new BadKey(1))); // null! ← BUG: different hashCode

        Map<GoodKey, String> goodMap = new HashMap<>();
        goodMap.put(new GoodKey(1), "Alice");
        System.out.println(goodMap.get(new GoodKey(1))); // Alice ✅
    }
}
```

---

## 17. Performance Comparison

| Operation       | ArrayList | LinkedList | HashSet  | TreeSet   | HashMap  | TreeMap   |
|-----------------|:---------:|:----------:|:--------:|:---------:|:--------:|:---------:|
| add (end)       | O(1)*     | O(1)       | O(1)*    | O(log n)  | O(1)*    | O(log n)  |
| add (index)     | O(n)      | O(n)       | —        | —         | —        | —         |
| get (index)     | O(1)      | O(n)       | —        | —         | —        | —         |
| get (key)       | —         | —          | —        | —         | O(1)*    | O(log n)  |
| contains/search | O(n)      | O(n)       | O(1)*    | O(log n)  | O(1)*    | O(log n)  |
| remove          | O(n)      | O(n)†      | O(1)*    | O(log n)  | O(1)*    | O(log n)  |
| iteration       | O(n)      | O(n)       | O(n)     | O(n)      | O(n)     | O(n)      |
| min/max         | O(n)      | O(n)       | O(n)     | O(1) ✅   | O(n)     | O(1) ✅   |

*`amortized` — O(1) amortized, O(n) worst case on resize  
†LinkedList remove by index is O(n) traversal + O(1) pointer update

---

## 18. Real-World Patterns

### Pattern 1: Word Frequency Counter

```java
import java.util.*;
import java.util.stream.*;

public class WordFrequency {
    public static void main(String[] args) {
        String text = "to be or not to be that is the question to be";

        // Count frequencies using merge
        Map<String, Integer> freq = new HashMap<>();
        for (String word : text.split(" ")) {
            freq.merge(word, 1, Integer::sum);
        }

        // Top 3 most frequent words
        freq.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(3)
            .forEach(e -> System.out.printf("%-10s: %d%n", e.getKey(), e.getValue()));
        // be        : 3
        // to        : 3
        // that      : 1
    }
}
```

---

### Pattern 2: Group Anagrams

```java
import java.util.*;
import java.util.stream.*;

public class GroupAnagrams {
    public static void main(String[] args) {
        String[] words = {"eat", "tea", "tan", "ate", "nat", "bat"};

        // Sort each word's characters → use as key to group anagrams
        Map<String, List<String>> groups = new HashMap<>();
        for (String word : words) {
            char[] chars = word.toCharArray();
            Arrays.sort(chars);
            String key = new String(chars); // "aet", "ant", "abt"
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(word);
        }

        groups.values().forEach(System.out::println);
        // [eat, tea, ate]
        // [tan, nat]
        // [bat]
    }
}
```

---

### Pattern 3: Graph with Adjacency List

```java
import java.util.*;

public class Graph {
    private final Map<String, List<String>> adjList = new HashMap<>();

    void addEdge(String from, String to) {
        adjList.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        adjList.computeIfAbsent(to,   k -> new ArrayList<>()).add(from); // Undirected
    }

    // BFS
    List<String> bfs(String start) {
        List<String>  visited = new ArrayList<>();
        Set<String>   seen    = new LinkedHashSet<>();
        Queue<String> queue   = new ArrayDeque<>();

        queue.offer(start);
        seen.add(start);

        while (!queue.isEmpty()) {
            String node = queue.poll();
            visited.add(node);
            for (String neighbor : adjList.getOrDefault(node, List.of())) {
                if (!seen.contains(neighbor)) {
                    seen.add(neighbor);
                    queue.offer(neighbor);
                }
            }
        }
        return visited;
    }

    // DFS
    List<String> dfs(String start) {
        List<String>  visited = new ArrayList<>();
        Set<String>   seen    = new HashSet<>();
        Deque<String> stack   = new ArrayDeque<>();

        stack.push(start);
        while (!stack.isEmpty()) {
            String node = stack.pop();
            if (!seen.contains(node)) {
                seen.add(node);
                visited.add(node);
                List<String> neighbors = adjList.getOrDefault(node, List.of());
                for (int i = neighbors.size() - 1; i >= 0; i--) {
                    if (!seen.contains(neighbors.get(i))) stack.push(neighbors.get(i));
                }
            }
        }
        return visited;
    }

    public static void main(String[] args) {
        Graph g = new Graph();
        g.addEdge("A", "B"); g.addEdge("A", "C");
        g.addEdge("B", "D"); g.addEdge("C", "D");
        g.addEdge("D", "E");

        System.out.println("BFS from A: " + g.bfs("A")); // [A, B, C, D, E]
        System.out.println("DFS from A: " + g.dfs("A")); // [A, B, D, E, C]
    }
}
```

---

### Pattern 4: Cache with Expiry using LinkedHashMap

```java
import java.util.*;

public class TTLCache<K, V> {
    private final long ttlMs;
    private final Map<K, long[]>  expiry;
    private final Map<K, V>       data;

    TTLCache(int maxSize, long ttlMs) {
        this.ttlMs  = ttlMs;
        this.expiry = new LinkedHashMap<>();
        this.data   = new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> e) {
                return size() > maxSize;
            }
        };
    }

    void put(K key, V value) {
        data.put(key, value);
        expiry.put(key, new long[]{System.currentTimeMillis() + ttlMs});
    }

    Optional<V> get(K key) {
        long[] exp = expiry.get(key);
        if (exp == null || System.currentTimeMillis() > exp[0]) {
            data.remove(key);
            expiry.remove(key);
            return Optional.empty();
        }
        return Optional.ofNullable(data.get(key));
    }

    public static void main(String[] args) throws InterruptedException {
        TTLCache<String, String> cache = new TTLCache<>(10, 500); // 500ms TTL
        cache.put("user:1", "Alice");
        System.out.println(cache.get("user:1")); // Optional[Alice]
        Thread.sleep(600);
        System.out.println(cache.get("user:1")); // Optional.empty (expired)
    }
}
```

---

## 19. Java 9–21 Enhancements

| Version | Feature | Description |
|---------|---------|-------------|
| Java 9 | `List.of()`, `Set.of()`, `Map.of()` | Immutable factory methods |
| Java 9 | `Map.ofEntries()` | Immutable map with >10 entries |
| Java 10 | `List.copyOf()`, `Set.copyOf()`, `Map.copyOf()` | Immutable copies |
| Java 10 | `Collectors.toUnmodifiableList/Set/Map()` | Unmodifiable stream collectors |
| Java 11 | `List.of()` from array with `Arrays.asList()` improvements | Minor improvements |
| Java 12 | `Collectors.teeing()` | Merge two collectors |
| Java 14 | `Map.entry()` improvements | Enhanced null checks |
| Java 16 | `Stream.toList()` | Short-hand for unmodifiable list |
| Java 21 | `SequencedCollection`, `SequencedSet`, `SequencedMap` | Ordered collection interfaces |

---

### Java 21 — Sequenced Collections

```java
import java.util.*;

public class SequencedCollectionsDemo {
    public static void main(String[] args) {

        // SequencedCollection — any collection with defined encounter order
        // New interface: getFirst(), getLast(), addFirst(), addLast(), reversed()

        // ArrayList implements SequencedCollection
        List<String> list = new ArrayList<>(List.of("A", "B", "C", "D"));
        System.out.println("getFirst: " + list.getFirst()); // A
        System.out.println("getLast:  " + list.getLast());  // D

        list.addFirst("Z"); // Adds at beginning
        list.addLast("X");  // Adds at end
        System.out.println("After addFirst/Last: " + list); // [Z, A, B, C, D, X]

        list.removeFirst(); // Remove first element
        list.removeLast();  // Remove last element
        System.out.println("After removeFirst/Last: " + list); // [A, B, C, D]

        // reversed() — reversed view (live, not a copy)
        SequencedCollection<String> rev = list.reversed();
        System.out.println("Reversed view: " + rev); // [D, C, B, A]

        // LinkedHashSet implements SequencedSet
        LinkedHashSet<Integer> seqSet = new LinkedHashSet<>(List.of(3, 1, 4, 1, 5));
        System.out.println("Set first: " + seqSet.getFirst()); // 3
        System.out.println("Set last:  " + seqSet.getLast());  // 5
        System.out.println("Set reversed: " + seqSet.reversed()); // [5, 4, 1, 3]

        // LinkedHashMap implements SequencedMap
        LinkedHashMap<String, Integer> seqMap = new LinkedHashMap<>();
        seqMap.put("C", 3); seqMap.put("A", 1); seqMap.put("B", 2);
        System.out.println("Map firstEntry: " + seqMap.firstEntry()); // C=3
        System.out.println("Map lastEntry:  " + seqMap.lastEntry());  // B=2
        System.out.println("Map reversed:   " + seqMap.reversed());   // {B=2, A=1, C=3}
    }
}
```

---

## 20. Interview Questions & Answers

| # | Question | Answer |
|---|----------|--------|
| 1 | `ArrayList` vs `LinkedList`? | ArrayList: O(1) random access, better cache locality. LinkedList: O(1) add/remove at ends, higher memory (node pointers). Use ArrayList by default. |
| 2 | `HashMap` vs `Hashtable`? | HashMap: not synchronized, allows one null key, faster. Hashtable: synchronized (slow), no null keys, legacy. Use ConcurrentHashMap for thread safety. |
| 3 | `HashMap` vs `TreeMap`? | HashMap: O(1) ops, no order. TreeMap: O(log n) ops, keys sorted, supports range queries. Use HashMap for speed, TreeMap for sorted iteration. |
| 4 | `HashSet` vs `TreeSet`? | HashSet: O(1) ops, no order. TreeSet: O(log n) ops, sorted, navigation methods. Use HashSet for fast lookup, TreeSet for sorted unique elements. |
| 5 | `HashMap` vs `LinkedHashMap`? | LinkedHashMap maintains insertion (or access) order. Small overhead for doubly linked list. Use for ordered iteration or LRU cache. |
| 6 | How does HashMap handle collisions? | Separate chaining: entries in same bucket stored in a linked list (Java 7) or red-black tree when >8 entries (Java 8+). |
| 7 | What is load factor? | Threshold = capacity × loadFactor (default 0.75). When size exceeds threshold, HashMap rehashes with doubled capacity. |
| 8 | Why override both `equals()` and `hashCode()`? | Contract: if a.equals(b), then a.hashCode() == b.hashCode(). Breaking this makes HashMap/HashSet fail silently — same object stored in different buckets. |
| 9 | `fail-fast` vs `fail-safe` iterator? | Fail-fast throws ConcurrentModificationException on structural modification during iteration. Fail-safe iterates over a snapshot — no exception (CopyOnWriteArrayList, ConcurrentHashMap). |
| 10 | How to make ArrayList thread-safe? | Use `Collections.synchronizedList(list)` (manual sync on iteration), or `CopyOnWriteArrayList` (for read-heavy workloads). |
| 11 | `Collection` vs `Collections`? | `Collection` is the root interface. `Collections` is the utility class with static methods (sort, shuffle, binarySearch, etc.). |
| 12 | `Array` vs `ArrayList`? | Array: fixed size, primitives OK, slightly faster. ArrayList: dynamic size, objects only, richer API. Use array only for performance-critical fixed-size data. |
| 13 | How does `TreeMap` maintain sorted order? | Red-Black Tree (self-balancing BST). All keys must implement `Comparable` or a `Comparator` must be provided. |
| 14 | What is `ConcurrentHashMap`? | Thread-safe Map using segment-level locking (Java 7) / CAS + node-level locking (Java 8+). Much faster than `Collections.synchronizedMap()` under contention. |
| 15 | `Comparable` vs `Comparator`? | `Comparable` defines natural ordering inside the class (`compareTo`). `Comparator` defines custom/external ordering outside (`compare`). One natural order vs many comparators. |
| 16 | What is `PriorityQueue`? | Min-heap by default. Peek/poll returns smallest element. O(1) peek, O(log n) add/poll, O(n) contains. Not thread-safe — use PriorityBlockingQueue. |
| 17 | `List.of()` vs `Arrays.asList()`? | `List.of()`: fully immutable (no set/add/remove). `Arrays.asList()`: fixed size but allows `set()` (backed by array). Neither supports `add()`. |
| 18 | What are Sequenced Collections (Java 21)? | New interfaces: `SequencedCollection`, `SequencedSet`, `SequencedMap` — add `getFirst()`, `getLast()`, `addFirst()`, `addLast()`, `reversed()` to ordered collections. |
| 19 | When to use `LinkedHashSet`? | When you need a Set with no duplicates AND insertion order preserved. Slightly slower than HashSet but predictable iteration order. |
| 20 | How to sort a Map by value? | `map.entrySet().stream().sorted(Map.Entry.comparingByValue()).collect(Collectors.toMap(..., LinkedHashMap::new))` or iterate sorted `entrySet()`. |

---

## 21. Complete Reference Summary

### Choosing the Right Collection

```
Need a list (ordered, duplicates OK)?
  → Default:         ArrayList      (O(1) access, O(n) insert)
  → Heavy add/remove ends: LinkedList
  → Thread-safe:     CopyOnWriteArrayList (read-heavy) / Collections.synchronizedList

Need unique elements (no duplicates)?
  → No order needed: HashSet        (O(1) ops)
  → Insertion order: LinkedHashSet  (O(1) ops)
  → Sorted order:    TreeSet        (O(log n) ops, navigation methods)
  → Thread-safe:     ConcurrentSkipListSet

Need key-value pairs?
  → Default:         HashMap        (O(1) ops, no order)
  → Insertion order: LinkedHashMap  (O(1) ops, LRU cache)
  → Sorted by key:   TreeMap        (O(log n) ops, range queries)
  → Thread-safe:     ConcurrentHashMap

Need FIFO queue?
  → Default:         ArrayDeque     (O(1) both ends, faster than LinkedList)
  → Priority order:  PriorityQueue  (min-heap, O(log n) poll)
  → Thread-safe:     LinkedBlockingQueue, ArrayBlockingQueue

Need stack (LIFO)?
  → ArrayDeque       (faster than Stack class, not synchronized)
```

---

### Full Architecture Map

```
Java Collections Framework
│
├── Collection (interface)
│   │
│   ├── List (ordered, duplicates OK, index access)
│   │   ├── ArrayList       — dynamic array, O(1) get, O(n) insert
│   │   ├── LinkedList      — doubly linked, O(1) ends, O(n) get
│   │   ├── Vector          — LEGACY (use ArrayList)
│   │   └── Stack           — LEGACY (use ArrayDeque)
│   │
│   ├── Set (unique elements)
│   │   ├── HashSet         — no order, O(1) ops, backed by HashMap
│   │   ├── LinkedHashSet   — insertion order, O(1) ops
│   │   └── TreeSet         — sorted, O(log n) ops, Red-Black Tree
│   │
│   └── Queue / Deque
│       ├── LinkedList      — FIFO queue + Deque
│       ├── ArrayDeque      — fast Deque/Stack (recommended)
│       ├── PriorityQueue   — min-heap, O(log n) offer/poll
│       └── BlockingQueue (concurrent)
│           ├── LinkedBlockingQueue
│           ├── ArrayBlockingQueue
│           └── PriorityBlockingQueue
│
├── Map (key-value, NOT a Collection)
│   ├── HashMap             — no order, O(1) avg, null key allowed
│   ├── LinkedHashMap       — insertion/access order, O(1) avg
│   ├── TreeMap             — sorted keys, O(log n), Red-Black Tree
│   ├── Hashtable           — LEGACY (use ConcurrentHashMap)
│   ├── WeakHashMap         — weak refs (GC can collect entries)
│   ├── IdentityHashMap     — uses == not .equals() for key comparison
│   └── EnumMap             — highly efficient map for enum keys
│
├── Concurrent Collections
│   ├── ConcurrentHashMap       — thread-safe Map, CAS operations
│   ├── CopyOnWriteArrayList    — thread-safe List (copy on write)
│   ├── CopyOnWriteArraySet     — thread-safe Set (copy on write)
│   ├── ConcurrentSkipListMap   — thread-safe sorted Map
│   └── ConcurrentSkipListSet   — thread-safe sorted Set
│
├── Utility Classes
│   ├── Collections  — sort, shuffle, binarySearch, min, max, reverse, fill
│   └── Arrays       — sort, binarySearch, copyOf, fill, equals, stream
│
└── Java 9–21 Additions
    ├── List.of(), Set.of(), Map.of()    — immutable factory (Java 9)
    ├── List.copyOf(), Map.copyOf()      — immutable copies (Java 10)
    ├── Collectors.toUnmodifiableList()  — immutable from stream (Java 10)
    ├── Stream.toList()                  — shorthand (Java 16)
    └── SequencedCollection/Set/Map      — ordered interface (Java 21)
```

---

*Made with ❤️ for Java developers — covers Java 2 through Java 21*
