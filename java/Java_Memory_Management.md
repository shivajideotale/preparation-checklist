
# Java Memory Management

Java memory management is automatic and handled by the **JVM (Java Virtual Machine)**, primarily through **Garbage Collection (GC)**.

---

## Table of Contents

1. [JVM Memory Structure](#1-jvm-memory-structure)
2. [Object Lifecycle](#2-object-lifecycle)
3. [Garbage Collection](#3-garbage-collection)
4. [Memory Leaks in Java](#4-memory-leaks-in-java)
5. [Key JVM Flags](#5-key-jvm-flags)
6. [Reference Types](#6-reference-types)
7. [Common Errors](#7-common-errors)
8. [Stack and Heap — How They Work Together](#8-stack-and-heap--how-they-work-together)
9. [Pass-by-Value: The Critical Gotcha](#9-pass-by-value-the-critical-gotcha)
10. [Memory Lifecycle Summary](#10-memory-lifecycle-summary)

---

## 1. JVM Memory Structure

Java memory is divided into several regions:

### 🔵 Heap Memory
The largest memory area, shared across all threads. This is where **objects** live.

- **Young Generation** — newly created objects
  - **Eden Space**: Objects are first allocated here
  - **Survivor Spaces (S0, S1)**: Objects that survive GC cycles move here
- **Old Generation (Tenured)**: Long-lived objects promoted from Young Gen
- **Metaspace** (Java 8+): Stores class metadata (replaced PermGen)

### 🟢 Stack Memory
- Each thread has its **own stack**
- Stores **local variables**, **method calls**, and **references** (not objects)
- Follows LIFO — automatically freed when a method returns
- Throws `StackOverflowError` when full

### 🟡 Other Areas

| Area | Purpose |
|---|---|
| **PC Register** | Tracks current instruction per thread |
| **Native Method Stack** | For native (C/C++) method calls |
| **Code Cache** | JIT-compiled native code |

---

## 2. Object Lifecycle

```
new Object()
    ↓
Eden Space  →  (Minor GC)  →  Survivor S0/S1  →  (Major GC)  →  Old Gen
                                                                      ↓
                                                               Garbage Collected
```

1. Object created → goes to **Eden**
2. Minor GC runs → survivors move to **S0 or S1**, age counter increments
3. After reaching **age threshold** (default: 15) → promoted to **Old Gen**
4. Major/Full GC → cleans Old Gen

---

## 3. Garbage Collection

GC automatically reclaims memory from **unreachable objects** (no live references pointing to them).

### How GC Identifies Garbage
- **Reference Counting** — not used in Java (can't handle circular refs)
- **Reachability Analysis** — traces from **GC Roots** (stack vars, static fields, JNI refs); anything unreachable is garbage

### GC Algorithms

| Algorithm | How it works | Best for |
|---|---|---|
| **Serial GC** | Single-threaded, stop-the-world | Small apps |
| **Parallel GC** | Multi-threaded, stop-the-world | Throughput-focused |
| **CMS (deprecated)** | Concurrent mark-sweep, low pause | Low latency |
| **G1 GC** (default Java 9+) | Divides heap into regions, balances throughput/latency | General purpose |
| **ZGC** | Sub-millisecond pauses, concurrent | Large heaps |
| **Shenandoah** | Ultra-low pause times | Latency-critical |

### GC Phases (G1 example)
1. **Initial Mark** — mark GC roots (STW pause)
2. **Concurrent Mark** — trace object graph concurrently
3. **Remark** — finalize marking (STW pause)
4. **Cleanup / Evacuation** — reclaim and compact regions

---

## 4. Memory Leaks in Java

Even with GC, leaks happen when **objects are reachable but never used again**.

**Common causes:**
- Static collections holding references (`static List<Object>`)
- Unclosed resources (`InputStream`, `Connection`)
- Listeners/callbacks never deregistered
- `ThreadLocal` variables not removed
- Caches with no eviction policy

---

## 5. Key JVM Flags

```bash
-Xms512m          # Initial heap size
-Xmx4g            # Max heap size
-Xss512k          # Stack size per thread
-XX:+UseG1GC      # Use G1 garbage collector
-XX:+UseZGC       # Use ZGC
-verbose:gc       # Print GC logs
-XX:+HeapDumpOnOutOfMemoryError  # Dump heap on OOM
```

---

## 6. Reference Types

Java provides 4 reference strengths to give you control over GC behavior:

| Type | Class | GC Behavior |
|---|---|---|
| **Strong** | Default (`Object o = new Object()`) | Never collected while reachable |
| **Soft** | `SoftReference<T>` | Collected only when memory is low |
| **Weak** | `WeakReference<T>` | Collected at next GC cycle |
| **Phantom** | `PhantomReference<T>` | Post-finalization cleanup |

---

## 7. Common Errors

| Error | Cause | Fix |
|---|---|---|
| `OutOfMemoryError: Java heap space` | Heap exhausted | Increase `-Xmx`, fix leaks |
| `OutOfMemoryError: Metaspace` | Too many classes loaded | Increase `-XX:MaxMetaspaceSize` |
| `StackOverflowError` | Deep/infinite recursion | Fix recursion, increase `-Xss` |
| `GC overhead limit exceeded` | GC running constantly | Tune GC, increase heap |

---

## 8. Stack and Heap — How They Work Together

The stack and heap are **complementary**: the stack manages *execution flow and references*, while the heap manages *actual object data*.

### The Core Relationship

```
Stack                          Heap
─────────────────              ──────────────────────────
│ main()           │           │                        │
│  name ──────────────────────►│  "Alice"  (String obj) │
│  person ────────────────────►│  Person { age: 30 }    │
│  age = 30        │           │                        │
└──────────────────┘           └────────────────────────┘

Stack holds REFERENCES.        Heap holds OBJECTS.
```

> The stack never stores objects — only **primitive values** and **memory addresses (references)** pointing into the heap.

---

### Step-by-Step: What Happens in Memory

```java
public class Main {
    public static void main(String[] args) {
        int age = 30;                        // Line 1
        String name = new String("Alice");   // Line 2
        Person p = new Person("Alice", 30);  // Line 3
        greet(p);                            // Line 4
    }

    static void greet(Person person) {       // Line 5
        String msg = "Hello " + person.name; // Line 6
    }
}
```

**Line 1 — Primitive on Stack**
```
Stack                    Heap
┌─────────────────┐      ┌────────┐
│ main()          │      │        │
│   age = 30      │      │ (empty)│
└─────────────────┘      └────────┘
```
Primitive `int` lives **directly on the stack**. No heap involved.

**Line 2 — Object on Heap, Reference on Stack**
```
Stack                    Heap
┌─────────────────┐      ┌──────────────────┐
│ main()          │      │ 0x001            │
│   age = 30      │      │ String("Alice")  │
│   name = 0x001──────►  │                  │
└─────────────────┘      └──────────────────┘
```
- `new String("Alice")` allocates the object on the **heap**
- `name` on the stack holds the **heap address** `0x001`

**Line 3 — Complex Object on Heap**
```
Stack                    Heap
┌─────────────────┐      ┌──────────────────┐
│ main()          │      │ 0x001            │
│   age = 30      │      │ String("Alice")  │
│   name = 0x001  │      ├──────────────────┤
│   p    = 0x002──────►  │ 0x002            │
└─────────────────┘      │ Person {         │
                         │   name → 0x001   │
                         │   age  = 30      │
                         │ }                │
                         └──────────────────┘
```
- `Person` object lives on the **heap**
- Its `name` field holds a reference back to the **same** `"Alice"` string
- `p` on the stack holds address `0x002`

**Lines 4–6 — Method Call Creates New Stack Frame**
```
Stack                    Heap
┌─────────────────┐      ┌──────────────────┐
│ greet()         │      │ 0x001            │
│  person = 0x002─┐      │ String("Alice")  │
│  msg    = 0x003 │      ├──────────────────┤
├─────────────────┤ └──► │ 0x002            │
│ main()          │      │ Person { ... }   │
│   age = 30      │      ├──────────────────┤
│   name = 0x001  │      │ 0x003            │
│   p    = 0x002  │      │ String("Hello    │
└─────────────────┘      │  Alice")         │
                         └──────────────────┘
```
- A **new stack frame** is pushed for `greet()`
- `person` in `greet()` is a **copy of the reference** — both point to the **same heap object**
- `msg` is a new String allocated on the heap

**After `greet()` Returns — Stack Frame Popped**
```
Stack                    Heap
┌─────────────────┐      ┌──────────────────┐
│ main()          │      │ 0x001  "Alice"    │
│   age = 30      │      │ 0x002  Person     │
│   name = 0x001  │      │ 0x003  ← ORPHAN  │  ← eligible for GC
│   p    = 0x002  │      │  (no references) │
└─────────────────┘      └──────────────────┘
```
- `greet()`'s stack frame is **instantly destroyed**
- The `msg` string on the heap is now **unreachable** → GC will collect it
- The `Person` object **survives** because `main()` still holds a reference

---

### Key Rules Summarized

| Rule | Detail |
|---|---|
| **Primitives → Stack** | `int`, `double`, `boolean`, etc. stored directly in the frame |
| **Objects → Heap** | All `new` allocations go to the heap |
| **References → Stack** | Variables hold memory addresses, not objects |
| **Method call → push frame** | New stack frame created with its own local variables |
| **Method return → pop frame** | Frame destroyed instantly, locals gone |
| **GC → cleans heap** | Objects with no stack references are eligible for collection |
| **Shared references** | Two variables can point to the same heap object |

---

## 9. Pass-by-Value: The Critical Gotcha

```java
void birthday(Person p) {
    p.age++;          // ✅ Modifies the heap object — visible outside
    p = new Person(); // ❌ Only changes local copy of reference — caller unaffected
}
```

Java always passes the **reference by value** — you get a copy of the address, not the object itself. Mutating the object works; reassigning the variable doesn't affect the caller.

---

## 10. Memory Lifecycle Summary

```
Stack memory freed:    IMMEDIATELY when method returns (deterministic)
Heap memory freed:     WHENEVER GC runs (non-deterministic)
```

> This is why you should **close resources explicitly** (`try-with-resources`) rather than relying on GC for cleanup — GC timing is not guaranteed.

### Multi-Thread Memory Model

```
Thread 1 Stack        Thread 2 Stack           Shared Heap
──────────────        ──────────────       ──────────────────────
│ frame 3   │         │ frame 1   │        │ Object A           │
│ frame 2   │         └──────────►│────────► Object B (shared)  │
│ frame 1   │                     │        │ Object C           │
└──────────►│─────────────────────┘────────► Object D           │
            │                              └────────────────────┘
Each thread gets its OWN stack.    All threads SHARE the heap.
```

This shared heap is what makes **thread-safety** critical — multiple threads can read/write the same object simultaneously without synchronization.

### JVM Memory at a Glance

```
JVM Memory
├── Heap (GC managed)
│   ├── Young Gen: Eden + S0 + S1
│   └── Old Gen
├── Metaspace (class metadata)
├── Stack (per thread, auto managed)
└── Native / Code Cache
```

---

*Java's memory model lets you focus on business logic while the JVM handles allocation and cleanup — but understanding it helps you write efficient, leak-free applications and tune performance under load.*
