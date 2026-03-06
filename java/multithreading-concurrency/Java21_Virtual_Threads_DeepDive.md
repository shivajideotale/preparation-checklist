# ☕ Java 21 — Virtual Threads Deep Dive Complete Guide
> **JEP 444** — Virtual Threads (Finalized in Java 21 as a permanent feature)

---

## 📌 Table of Contents

1. [The Problem Virtual Threads Solve](#1-the-problem-virtual-threads-solve)
2. [What Are Virtual Threads?](#2-what-are-virtual-threads)
3. [JVM Architecture — How They Work Internally](#3-jvm-architecture--how-they-work-internally)
4. [Mounting & Unmounting Lifecycle](#4-mounting--unmounting-lifecycle)
5. [Creating Virtual Threads — All Ways](#5-creating-virtual-threads--all-ways)
6. [Virtual Threads with ExecutorService](#6-virtual-threads-with-executorservice)
7. [Thread State & Inspection](#7-thread-state--inspection)
8. [Blocking Operations & Unmounting](#8-blocking-operations--unmounting)
9. [Pinning — The Critical Gotcha](#9-pinning--the-critical-gotcha)
10. [Structured Concurrency (JEP 453)](#10-structured-concurrency-jep-453)
11. [Scoped Values (JEP 446)](#11-scoped-values-jep-446)
12. [Virtual Threads vs Platform Threads — Benchmarks](#12-virtual-threads-vs-platform-threads--benchmarks)
13. [Real-World Patterns](#13-real-world-patterns)
14. [What NOT To Do](#14-what-not-to-do)
15. [Virtual Threads with Frameworks](#15-virtual-threads-with-frameworks)
16. [Debugging & Monitoring](#16-debugging--monitoring)
17. [Migration Guide](#17-migration-guide)
18. [Interview Questions & Answers](#18-interview-questions--answers)
19. [Complete Reference Summary](#19-complete-reference-summary)

---

## 1. The Problem Virtual Threads Solve

### The Traditional "Thread-Per-Request" Model

Every incoming request gets its own **platform thread**. Platform threads are **OS threads** — heavy, expensive, and limited.

```
Traditional Server (Platform Threads)

Request-1 ──► [Platform Thread-1]  ═══waiting for DB═══════════════
Request-2 ──► [Platform Thread-2]  ══waiting for HTTP══════════════
Request-3 ──► [Platform Thread-3]  ═══waiting for File════════════
Request-4 ──► [Platform Thread-4]  ═══waiting for DB═══════════════
Request-5 ──► [Platform Thread-5]  ═══BLOCKED (pool full)══════════

Each thread holds ~1MB of RAM and an OS resource while doing NOTHING
```

### The Math Problem

```
Platform thread cost:
  - Stack size:        ~1 MB per thread (default)
  - OS thread handle:  kernel resource
  - Context switch:    expensive (save/restore registers)

10,000 concurrent users:
  10,000 × 1 MB = ~10 GB RAM  ❌
  10,000 OS threads            ❌ (OS limit is often 10k–100k)

Real servers use pools of 200–500 threads max
→ Other requests QUEUE UP and wait
→ Latency spikes under load
→ Throughput is capped by pool size
```

### The Reactive Alternative (Before Virtual Threads)

To avoid blocking, developers used reactive/async frameworks:

```java
// Reactive — hard to read, hard to debug, callback hell
userService.findById(id)
    .flatMap(user -> orderService.findOrders(user.getId()))
    .flatMap(orders -> inventoryService.checkStock(orders))
    .flatMap(stocked -> paymentService.charge(stocked))
    .subscribe(
        result -> sendResponse(result),
        error  -> handleError(error)
    );
```

Problems:
- Hard to read and reason about
- Stack traces are meaningless (callbacks are detached)
- Debugging is very difficult
- Requires special reactive libraries everywhere

### Virtual Threads — The Solution

```java
// Virtual Thread — looks like blocking, but IS non-blocking under the hood!
User    user   = userService.findById(id);       // ← JVM unmounts during wait
Orders  orders = orderService.findOrders(user);  // ← JVM unmounts during wait
boolean stocked = inventoryService.check(orders); // ← JVM unmounts during wait
Result  result = paymentService.charge(stocked);  // ← JVM unmounts during wait
sendResponse(result);
```

Same simple blocking style, but scales to millions of concurrent threads.

---

## 2. What Are Virtual Threads?

Virtual Threads are **JVM-managed lightweight threads** introduced as a preview in Java 19/20 and finalized in **Java 21 (JEP 444)**.

```
Platform Thread (Traditional):
  Java Thread Object
       │
       └──► OS Thread (kernel)
                 │
                 └──► CPU Core
  Cost: ~1MB stack, OS resource, expensive context switch

Virtual Thread:
  Virtual Thread Object  (millions possible)
       │
       └──► Carrier Thread (platform thread, JVM-managed ForkJoinPool)
                 │
                 └──► CPU Core
  Cost: ~few KB stack (growable), no OS thread per virtual thread
```

### Key Properties

| Property                      | Value / Behavior                                    |
|-------------------------------|-----------------------------------------------------|
| Managed by                    | JVM (not OS)                                        |
| Stack size                    | Starts at ~200 bytes, grows as needed               |
| Creation cost                 | Cheap (microseconds, no OS call)                    |
| Max concurrent virtual threads| Millions (limited only by heap memory)              |
| Carrier thread pool           | ForkJoinPool, size = number of CPU cores            |
| Blocking behavior             | Unmounts from carrier, frees carrier for other work |
| `Thread.isVirtual()`          | Returns `true`                                      |
| Daemon by default             | `true` (always daemon threads)                      |
| Thread priority               | Always `NORM_PRIORITY` (5) — cannot be changed      |
| `ThreadLocal` support         | ✅ (but be careful with memory in large scale)      |

---

## 3. JVM Architecture — How They Work Internally

### The Carrier Thread Pool

The JVM maintains a **ForkJoinPool** of platform threads called **carrier threads**. By default, the pool size equals the number of available CPU cores.

```
JVM Internals

ForkJoinPool (Carrier Threads)
┌─────────────────────────────────────────────────────┐
│  Carrier-1     Carrier-2     Carrier-3     Carrier-4│
│  [VT-101]      [VT-205]      [VT-310]      [VT-412] │
│  running       running       running       running  │
└─────────────────────────────────────────────────────┘
                    │
    When VT-101 hits I/O wait:
                    │
                    ▼
┌─────────────────────────────────────────────────────┐
│  Carrier-1     Carrier-2     Carrier-3     Carrier-4│
│  [VT-502]      [VT-205]      [VT-310]      [VT-412] │
│  ← NEW!        running       running       running  │
└─────────────────────────────────────────────────────┘
VT-101 is parked in heap (waiting for I/O to complete)
Carrier-1 immediately picks up VT-502 — no wasted time!
```

### Stack Storage

Platform thread stacks are allocated in **OS memory**.  
Virtual thread stacks are allocated on the **Java heap** as `StackChunk` objects.

```
Platform Thread:
  OS Memory
  ├── Thread Stack (1MB fixed — allocated at creation)
  └── Not resizable

Virtual Thread:
  Java Heap
  ├── StackChunk objects (few KB initially)
  ├── Grows dynamically as call stack deepens
  └── Freed when virtual thread terminates or unmounts
```

### Scheduler

Virtual threads use a custom **FIFO work-stealing scheduler** built on `ForkJoinPool`. You can configure the carrier pool size:

```bash
# Set carrier thread pool size (default = available processors)
java -Djdk.virtualThreadScheduler.parallelism=8 MyApp
java -Djdk.virtualThreadScheduler.maxPoolSize=256 MyApp
```

---

## 4. Mounting & Unmounting Lifecycle

This is the **heart** of how virtual threads achieve scalability.

### Full Lifecycle Diagram

```
Thread.startVirtualThread(task)
         │
         ▼
    [CREATED] ──────────────────────────────────────────────────────────────────────┐
         │                                                                          │
         ▼                                                                          │
   Scheduler picks                                                                  │
   carrier thread                                                                   │
         │                                                                          │
         ▼                                                                          │
    [MOUNTED] ← Virtual thread is assigned to a carrier thread                      │
         │                                                                          │
         ▼                                                                          │
    [RUNNING] ← Executing on carrier thread                                         │
         │                                                                          │
    Hits blocking op?                                                               │
    (I/O / sleep / lock)                                                            │
         │                                                                          │
         ├── YES ──► [UNMOUNTED] ── Stack saved to heap ──► Carrier thread freed    │
         │                │                                       │                 │
         │           Blocking op                           Carrier picks up         │
         │           completes                             another virtual thread   │
         │                │                                                         │
         │                ▼                                                         │
         │           [RUNNABLE] ── Scheduler remounts on any available carrier      │
         │                │                                                         │
         │                └──────────────────────────────────────────► [RUNNING]    │
         │                                                                          │
         └── NO  ──► Continues running on carrier                                   │
                          │                                                         │
                     Task complete                                                  │
                          │                                                         │
                          ▼                                                         │
                    [TERMINATED] ───────────────────────────────────────────────────┘
```

### Step-by-Step Walkthrough

```java
// Let's trace what happens when this code runs:
Thread vt = Thread.startVirtualThread(() -> {
    System.out.println("Step 1: Running on carrier");    // MOUNTED + RUNNING

    String data = readFromDatabase();                    // ← UNMOUNTS here!
    // While waiting for DB:
    //   - Virtual thread stack saved to heap
    //   - Carrier thread freed
    //   - Carrier picks up another virtual thread
    // When DB responds:
    //   - Virtual thread marked RUNNABLE
    //   - Scheduler mounts it on any available carrier

    System.out.println("Step 2: Resumed on carrier");   // REMOUNTED (may be different carrier!)
    System.out.println("Step 3: Done");
});                                                       // TERMINATED → GC'd
```

### What Operations Trigger Unmounting?

```
UNMOUNTING occurs for:
  ✅ Thread.sleep()
  ✅ Object.wait()
  ✅ Lock / ReentrantLock (blocking acquire)
  ✅ BlockingQueue operations (put/take)
  ✅ Socket I/O (read/write)
  ✅ File I/O (via java.nio)
  ✅ HTTP connections (java.net.http)
  ✅ Database connections (JDBC — most drivers)

PINNING occurs (does NOT unmount) for:
  ❌ synchronized block/method with blocking inside
  ❌ Native method calls (JNI)
  ❌ Foreign function calls
```

---

## 5. Creating Virtual Threads — All Ways

### Way 1: `Thread.ofVirtual().start()` — Named, immediate start

```java
public class CreateVT1 {
    public static void main(String[] args) throws InterruptedException {

        Thread vt = Thread.ofVirtual()
                          .name("vt-worker-1")
                          .start(() -> {
                              System.out.println("Thread name    : " + Thread.currentThread().getName());
                              System.out.println("Is virtual     : " + Thread.currentThread().isVirtual());
                              System.out.println("Is daemon      : " + Thread.currentThread().isDaemon());
                              System.out.println("Priority       : " + Thread.currentThread().getPriority());
                          });

        vt.join();
    }
}
```

**Output:**
```
Thread name    : vt-worker-1
Is virtual     : true
Is daemon      : true
Priority       : 5
```

---

### Way 2: `Thread.startVirtualThread()` — Quickest one-liner

```java
public class CreateVT2 {
    public static void main(String[] args) throws InterruptedException {

        Thread vt = Thread.startVirtualThread(() ->
            System.out.println("Quick virtual thread on: " + Thread.currentThread())
        );

        vt.join();
    }
}
```

**Output:**
```
Quick virtual thread on: VirtualThread[#21]/runnable@ForkJoinPool-1-worker-1
```

---

### Way 3: `Thread.ofVirtual().unstarted()` — Create then start later

```java
public class CreateVT3 {
    public static void main(String[] args) throws InterruptedException {

        Runnable task = () -> System.out.println("Running: " + Thread.currentThread().getName());

        Thread vt = Thread.ofVirtual()
                          .name("deferred-vt")
                          .unstarted(task);  // Created but NOT started

        System.out.println("State before start: " + vt.getState()); // NEW

        // Start it later
        vt.start();
        System.out.println("State after start:  " + vt.getState()); // RUNNABLE or TERMINATED

        vt.join();
        System.out.println("State after join:   " + vt.getState()); // TERMINATED
    }
}
```

---

### Way 4: `Thread.Builder` with auto-incrementing names

```java
public class CreateVT4 {
    public static void main(String[] args) throws InterruptedException {

        // Builder — reusable, creates threads with sequential names
        Thread.Builder.OfVirtual builder = Thread.ofVirtual()
                                                 .name("request-handler-", 1); // → request-handler-1, -2, -3...

        Thread t1 = builder.start(() -> System.out.println("I am " + Thread.currentThread().getName()));
        Thread t2 = builder.start(() -> System.out.println("I am " + Thread.currentThread().getName()));
        Thread t3 = builder.start(() -> System.out.println("I am " + Thread.currentThread().getName()));

        t1.join(); t2.join(); t3.join();
    }
}
```

**Output:**
```
I am request-handler-1
I am request-handler-2
I am request-handler-3
```

---

### Way 5: `ThreadFactory` from builder — Use with any framework

```java
import java.util.concurrent.*;

public class CreateVT5 {
    public static void main(String[] args) throws InterruptedException {

        ThreadFactory vtFactory = Thread.ofVirtual()
                                        .name("pool-vt-", 0)
                                        .factory();

        // Use the factory with ANY API that accepts ThreadFactory
        ExecutorService executor = Executors.newThreadPerTaskExecutor(vtFactory);

        for (int i = 0; i < 5; i++) {
            executor.submit(() ->
                System.out.println("Running on: " + Thread.currentThread().getName())
            );
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }
}
```

---

### Way 6: Spawning 1 Million Virtual Threads

```java
import java.util.*;
import java.util.concurrent.atomic.*;

public class MillionVirtualThreads {
    public static void main(String[] args) throws InterruptedException {

        int count = 1_000_000;
        AtomicInteger completed = new AtomicInteger(0);
        List<Thread> threads   = new ArrayList<>(count);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < count; i++) {
            Thread vt = Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(1000); // Simulate I/O wait
                    completed.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(vt);
        }

        for (Thread t : threads) t.join();

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.println("Completed : " + completed.get());
        System.out.println("Time      : " + elapsed + "ms");
        System.out.println("Expected  : ~1000ms (all run concurrently!)");

        // Try this with platform threads and the JVM will throw OutOfMemoryError!
    }
}
```

**Output:**
```
Completed : 1000000
Time      : ~1200ms
Expected  : ~1000ms (all run concurrently!)
```

---

## 6. Virtual Threads with ExecutorService

The idiomatic production pattern is `Executors.newVirtualThreadPerTaskExecutor()`.

### `newVirtualThreadPerTaskExecutor()` — One Virtual Thread Per Task

```java
import java.util.concurrent.*;

public class VTExecutorDemo {
    public static void main(String[] args) throws InterruptedException {

        // Creates a NEW virtual thread for EVERY submitted task
        // No pooling needed — virtual threads are cheap to create
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            for (int i = 1; i <= 10; i++) {
                final int taskId = i;
                executor.submit(() -> {
                    System.out.printf("Task %2d | Thread: %-40s | Virtual: %s%n",
                            taskId,
                            Thread.currentThread(),
                            Thread.currentThread().isVirtual());
                    try { Thread.sleep(500); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    System.out.println("Task " + taskId + " done.");
                });
            }

        } // try-with-resources: auto-calls shutdown() + awaitTermination()

        System.out.println("All tasks finished.");
    }
}
```

**Output:**
```
Task  1 | Thread: VirtualThread[#21]/runnable@ForkJoinPool-1-worker-1 | Virtual: true
Task  2 | Thread: VirtualThread[#22]/runnable@ForkJoinPool-1-worker-2 | Virtual: true
...
Task  1 done.
Task  2 done.
...
All tasks finished.
```

---

### `newThreadPerTaskExecutor(factory)` — Custom Named Virtual Threads

```java
import java.util.concurrent.*;

public class NamedVTExecutor {
    public static void main(String[] args) throws InterruptedException {

        ThreadFactory factory = Thread.ofVirtual()
                                      .name("http-handler-", 1)
                                      .factory();

        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {

            for (int i = 0; i < 5; i++) {
                executor.submit(() -> {
                    System.out.println("Handling request on: " +
                            Thread.currentThread().getName());
                    try { Thread.sleep(200); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
            }
        }
    }
}
```

---

### Executor Comparison

```java
// ❌ Old: Platform thread pool — limited, threads are expensive
ExecutorService old = Executors.newFixedThreadPool(200);

// ❌ Wrong: Pooling virtual threads — defeats the purpose!
// Virtual threads are cheap — never pool them
ExecutorService wrong = Executors.newFixedThreadPool(200, vtFactory); // DON'T DO THIS

// ✅ New: One virtual thread per task — idiomatic Java 21
ExecutorService correct = Executors.newVirtualThreadPerTaskExecutor();
```

---

## 7. Thread State & Inspection

### Checking Virtual Thread Properties

```java
public class VTInspectionDemo {
    public static void main(String[] args) throws InterruptedException {

        Thread vt = Thread.ofVirtual().name("inspector-thread").unstarted(() -> {
            System.out.println("=== Inside Virtual Thread ===");
            System.out.println("Name      : " + Thread.currentThread().getName());
            System.out.println("isVirtual : " + Thread.currentThread().isVirtual());
            System.out.println("isDaemon  : " + Thread.currentThread().isDaemon());
            System.out.println("Priority  : " + Thread.currentThread().getPriority());
            System.out.println("State     : " + Thread.currentThread().getState());
            System.out.println("toString  : " + Thread.currentThread().toString());
            System.out.println("Thread ID : " + Thread.currentThread().threadId());
        });

        System.out.println("Before start — State: " + vt.getState()); // NEW
        System.out.println("Before start — isVirtual: " + vt.isVirtual());

        vt.start();
        vt.join();

        System.out.println("After join  — State: " + vt.getState()); // TERMINATED
    }
}
```

**Output:**
```
Before start — State: NEW
Before start — isVirtual: true
=== Inside Virtual Thread ===
Name      : inspector-thread
isVirtual : true
isDaemon  : true
Priority  : 5
State     : RUNNABLE
toString  : VirtualThread[#21,inspector-thread]/runnable@ForkJoinPool-1-worker-1
Thread ID : 21
After join  — State: TERMINATED
```

---

### Virtual Thread States (Extended)

Virtual threads have additional internal states beyond `Thread.State`:

```
Standard Thread.State:          Virtual Thread also tracks:
  NEW                             UNSTARTED
  RUNNABLE                        RUNNABLE (in run queue)
  BLOCKED                         RUNNING (on carrier)
  WAITING                         PARKING (about to park)
  TIMED_WAITING                   PARKED (unmounted, waiting)
  TERMINATED                      PINNED (cannot unmount)
                                  TERMINATED
```

---

## 8. Blocking Operations & Unmounting

### How `Thread.sleep()` Behaves Differently

```java
public class SleepComparisonDemo {
    public static void main(String[] args) throws InterruptedException {

        System.out.println("=== Platform Thread sleep ===");
        // Platform thread: HOLDS OS thread resource while sleeping
        Thread platform = new Thread(() -> {
            System.out.println("Platform sleeping... (OS thread BLOCKED)");
            try { Thread.sleep(1000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            System.out.println("Platform awake!");
        });

        platform.start();
        platform.join();

        System.out.println("\n=== Virtual Thread sleep ===");
        // Virtual thread: RELEASES carrier thread while sleeping
        Thread virtual = Thread.ofVirtual().start(() -> {
            System.out.println("Virtual sleeping... (carrier thread FREED for others)");
            try { Thread.sleep(1000); } // Carrier is immediately freed!
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            System.out.println("Virtual awake! (remounted on any available carrier)");
        });

        virtual.join();
    }
}
```

---

### Concurrent I/O — Virtual Threads Shine

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ConcurrentIODemo {
    static final int REQUESTS = 10_000;
    static final int IO_DELAY = 100; // ms — simulates DB/HTTP call

    static void simulateIORequest(int id) throws InterruptedException {
        Thread.sleep(IO_DELAY); // Virtual thread UNMOUNTS here
    }

    public static void main(String[] args) throws InterruptedException {

        // ── Platform Thread Pool (200 threads) ────────────────────────────────
        AtomicInteger platformCount = new AtomicInteger();
        long t0 = System.currentTimeMillis();

        try (ExecutorService pool = Executors.newFixedThreadPool(200)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                final int id = i;
                futures.add(pool.submit(() -> {
                    try { simulateIORequest(id); platformCount.incrementAndGet(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }));
            }
            for (Future<?> f : futures) {
                try { f.get(); } catch (ExecutionException e) {}
            }
        }

        long platformMs = System.currentTimeMillis() - t0;
        System.out.printf("Platform Threads (200): %d requests in %dms%n",
                platformCount.get(), platformMs);
        // Time ≈ 10000/200 × 100ms = ~5000ms (processes 200 at a time in batches)

        // ── Virtual Threads ────────────────────────────────────────────────────
        AtomicInteger virtualCount = new AtomicInteger();
        long t1 = System.currentTimeMillis();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                final int id = i;
                futures.add(executor.submit(() -> {
                    try { simulateIORequest(id); virtualCount.incrementAndGet(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }));
            }
            for (Future<?> f : futures) {
                try { f.get(); } catch (ExecutionException e) {}
            }
        }

        long virtualMs = System.currentTimeMillis() - t1;
        System.out.printf("Virtual Threads:        %d requests in %dms%n",
                virtualCount.get(), virtualMs);
        // Time ≈ 100ms (all 10,000 run concurrently, all unmount during sleep!)
    }
}
```

**Typical Output:**
```
Platform Threads (200): 10000 requests in 5130ms
Virtual Threads:        10000 requests in  115ms   🚀 44x faster!
```

---

## 9. Pinning — The Critical Gotcha

**Pinning** = a virtual thread cannot unmount from its carrier thread during a blocking operation. The carrier thread is **blocked** just like a platform thread — defeating the purpose of virtual threads.

### What Causes Pinning

```
CAUSES OF PINNING:
  1. synchronized block/method + blocking operation inside
  2. Native method (JNI) calls
  3. Foreign function calls (FFI)
```

### Pinning Demonstration

```java
public class PinningDemo {
    private static final Object lock = new Object();

    // ❌ This PINS the virtual thread's carrier!
    static void pinnedOperation() throws InterruptedException {
        synchronized (lock) {
            System.out.println(Thread.currentThread().getName() +
                    " inside synchronized — CARRIER IS PINNED!");
            Thread.sleep(1000); // Carrier thread BLOCKED! Cannot run other virtual threads
        }
    }

    public static void main(String[] args) throws InterruptedException {

        // With 4 CPU cores = 4 carrier threads
        // If we start 4 virtual threads that all pin, ALL carriers are blocked!
        List<Thread> threads = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            final int id = i;
            threads.add(Thread.ofVirtual().name("vt-" + id).start(() -> {
                try { pinnedOperation(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }));
        }

        // These 5th virtual thread CANNOT run — all 4 carriers are PINNED
        Thread blocked = Thread.ofVirtual().name("vt-5-blocked").start(() ->
            System.out.println("vt-5 finally ran!")
        );

        for (Thread t : threads) t.join();
        blocked.join();
    }
}
```

---

### Fix 1: Replace `synchronized` with `ReentrantLock`

```java
import java.util.concurrent.locks.*;

public class PinningFixed {
    private static final ReentrantLock lock = new ReentrantLock(); // ✅ Virtual-thread-friendly

    // ✅ Virtual thread UNMOUNTS during lock.lock() — carrier is FREE!
    static void safeOperation() throws InterruptedException {
        lock.lock();
        try {
            System.out.println(Thread.currentThread().getName() +
                    " holding lock — carrier is NOT pinned!");
            Thread.sleep(1000); // Virtual thread UNMOUNTS here — carrier picks up others!
        } finally {
            lock.unlock(); // Always unlock in finally
        }
    }

    public static void main(String[] args) throws InterruptedException {

        List<Thread> threads = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            final int id = i;
            threads.add(Thread.ofVirtual().name("vt-" + id).start(() -> {
                try { safeOperation(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }));
        }

        for (Thread t : threads) t.join();
        System.out.println("All done — no pinning occurred!");
    }
}
```

---

### Fix 2: Move `synchronized` Outside the Blocking Call

```java
// ❌ BAD — blocking inside synchronized (PINS carrier)
synchronized (lock) {
    String result = callExternalService(); // Blocks while pinned!
    processResult(result);
}

// ✅ GOOD — synchronize only around non-blocking state update
String result = callExternalService(); // Unmounts here (no lock held)
synchronized (lock) {
    processResult(result);             // Fast operation — lock held briefly
}
```

---

### Detecting Pinning with JVM Flags

```bash
# Print a stack trace every time a virtual thread is pinned
java -Djdk.tracePinnedThreads=full MyApp.java

# Print just a one-line summary per pinning event
java -Djdk.tracePinnedThreads=short MyApp.java
```

**Sample pinning warning:**
```
Thread[#25,ForkJoinPool-1-worker-1,5,CarrierThreads]
    java.base/java.lang.VirtualThread$VThreadContinuation.onPinned(VirtualThread.java:185)
    java.base/jdk.internal.vm.Continuation.pin(Continuation.java:379)
    java.base/java.lang.VirtualThread.park(VirtualThread.java:582)
    java.base/java.lang.Thread.sleepNanos0(Thread.java:480)
    com.example.PinningDemo.pinnedOperation(PinningDemo.java:8)  ← HERE!
```

---

### Pinning Decision Guide

```
Inside your synchronized block, do you have any:
   Thread.sleep()         → Use ReentrantLock
   I/O operations         → Use ReentrantLock
   wait() / Object.wait() → Use ReentrantLock + Condition
   lock.lock()            → Already a lock, remove synchronized
   Other blocking calls    → Use ReentrantLock

If synchronized is only around:
   Simple field reads/writes  → OK (no blocking, so no pinning risk)
   Short non-blocking logic   → OK
```

---

## 10. Structured Concurrency (JEP 453)

Structured Concurrency makes concurrent code as readable as sequential code. It treats a **group of concurrent tasks as a single logical unit**.

### The Problem Without Structured Concurrency

```java
// Without Structured Concurrency — messy error handling
Future<User>    userFuture   = pool.submit(() -> fetchUser(id));
Future<Orders>  orderFuture  = pool.submit(() -> fetchOrders(id));

try {
    User   user   = userFuture.get();   // What if orderFuture already failed?
    Orders orders = orderFuture.get();  // Leaks resources if userFuture failed
    return new Dashboard(user, orders);
} catch (Exception e) {
    userFuture.cancel(true);   // Must manually cancel
    orderFuture.cancel(true);  // Easy to forget
    throw e;
}
```

### With `StructuredTaskScope`

```java
import java.util.concurrent.*;

public class StructuredConcurrencyDemo {

    record User(int id, String name) {}
    record Orders(int userId, int count) {}
    record Dashboard(User user, Orders orders) {}

    static User fetchUser(int id) throws InterruptedException {
        Thread.sleep(300); // Simulate DB call
        return new User(id, "Alice");
    }

    static Orders fetchOrders(int id) throws InterruptedException {
        Thread.sleep(500); // Simulate HTTP call
        return new Orders(id, 42);
    }

    // ── Pattern 1: ShutdownOnFailure — ALL must succeed ──────────────────────
    static Dashboard loadDashboard(int userId)
            throws InterruptedException, ExecutionException {

        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {

            // Fork both tasks — they run CONCURRENTLY on virtual threads
            StructuredTaskScope.Subtask<User>   userTask   = scope.fork(() -> fetchUser(userId));
            StructuredTaskScope.Subtask<Orders> ordersTask = scope.fork(() -> fetchOrders(userId));

            scope.join();           // Wait for BOTH to complete (or one to fail)
            scope.throwIfFailed();  // If any task threw, re-throw here

            // Both succeeded — safe to call .get()
            return new Dashboard(userTask.get(), ordersTask.get());
        }
        // If userTask fails → ordersTask is automatically cancelled
        // If ordersTask fails → userTask is automatically cancelled
        // No manual cleanup needed! ✅
    }

    public static void main(String[] args) throws Exception {
        long start = System.currentTimeMillis();
        Dashboard dash = loadDashboard(42);
        long ms = System.currentTimeMillis() - start;

        System.out.println("User:   " + dash.user());
        System.out.println("Orders: " + dash.orders());
        System.out.println("Time:   " + ms + "ms"); // ~500ms (not 300+500=800ms!)
    }
}
```

---

### Pattern 2: `ShutdownOnSuccess` — First Result Wins

```java
import java.util.concurrent.*;

public class FirstWinsDemo {

    static String fetchFromPrimary() throws InterruptedException {
        Thread.sleep(800);
        return "Primary server response";
    }

    static String fetchFromSecondary() throws InterruptedException {
        Thread.sleep(300); // Faster!
        return "Secondary server response";
    }

    static String fetchFromCache() throws InterruptedException {
        Thread.sleep(100); // Fastest!
        return "Cached response";
    }

    // Sends to all three servers, returns whichever responds first
    static String fetchFastest() throws InterruptedException, ExecutionException {

        try (var scope = new StructuredTaskScope.ShutdownOnSuccess<String>()) {

            scope.fork(() -> fetchFromPrimary());
            scope.fork(() -> fetchFromSecondary());
            scope.fork(() -> fetchFromCache());

            scope.join(); // Returns as soon as FIRST task succeeds
            // Other two tasks are automatically cancelled!

            return scope.result(); // The fastest result
        }
    }

    public static void main(String[] args) throws Exception {
        long start = System.currentTimeMillis();
        String result = fetchFastest();
        long ms = System.currentTimeMillis() - start;

        System.out.println("Result: " + result);  // Cached response
        System.out.println("Time:   " + ms + "ms"); // ~100ms!
    }
}
```

---

### Nested Structured Concurrency

```java
import java.util.concurrent.*;

public class NestedStructuredDemo {

    // Inner scope: fetch user + profile together
    static Object[] fetchUserData(int userId) throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var user    = scope.fork(() -> { Thread.sleep(200); return "User#" + userId; });
            var profile = scope.fork(() -> { Thread.sleep(300); return "Profile#" + userId; });
            scope.join().throwIfFailed();
            return new Object[]{user.get(), profile.get()};
        }
    }

    // Outer scope: fetch orders + userData together
    static void buildReport(int userId) throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var userData = scope.fork(() -> fetchUserData(userId)); // nested scope!
            var orders   = scope.fork(() -> { Thread.sleep(400); return "Orders#" + userId; });
            scope.join().throwIfFailed();

            Object[] ud = userData.get();
            System.out.println("Report: " + ud[0] + " | " + ud[1] + " | " + orders.get());
        }
    }

    public static void main(String[] args) throws Exception {
        long start = System.currentTimeMillis();
        buildReport(42);
        System.out.println("Time: " + (System.currentTimeMillis() - start) + "ms"); // ~400ms
    }
}
```

---

## 11. Scoped Values (JEP 446)

`ScopedValue` is the virtual-thread-friendly replacement for `ThreadLocal`. Where `ThreadLocal` gives each thread its own variable, `ScopedValue` provides **immutable, bounded context propagation**.

### ThreadLocal Problems in Virtual Thread World

```
ThreadLocal problems:
  - Mutable — can be changed anytime (hard to reason about)
  - Unbounded lifetime — must call remove() or leaks memory
  - With millions of VTs, memory bloat is serious
  - Inherited ThreadLocal copies are expensive
```

### `ScopedValue` — Immutable, Bounded Context

```java
import jdk.incubator.concurrent.ScopedValue;

public class ScopedValueDemo {

    // Declare scoped values as static final (like ThreadLocal)
    static final ScopedValue<String>  CURRENT_USER   = ScopedValue.newInstance();
    static final ScopedValue<String>  REQUEST_ID     = ScopedValue.newInstance();
    static final ScopedValue<Integer> TENANT_ID      = ScopedValue.newInstance();

    static void processRequest() {
        // Read from anywhere in the call chain — no passing as parameters!
        System.out.println("Processing for user   : " + CURRENT_USER.get());
        System.out.println("Request ID            : " + REQUEST_ID.get());
        System.out.println("Tenant ID             : " + TENANT_ID.get());

        // Nested scope — override for inner calls only
        ScopedValue.where(CURRENT_USER, "admin-override").run(() -> {
            System.out.println("Nested user (override): " + CURRENT_USER.get()); // admin-override
        });

        // Back to original value after nested scope exits
        System.out.println("After nested scope    : " + CURRENT_USER.get()); // alice
    }

    public static void main(String[] args) throws InterruptedException {

        // Bind values for the scope of this block — immutable within the scope
        Thread vt = Thread.ofVirtual().start(() ->
            ScopedValue
                .where(CURRENT_USER, "alice")
                .where(REQUEST_ID,   "REQ-12345")
                .where(TENANT_ID,    42)
                .run(() -> processRequest())
        );

        vt.join();

        // ScopedValue.get() OUTSIDE its binding throws NoSuchElementException
        System.out.println("Is bound: " + CURRENT_USER.isBound()); // false
    }
}
```

**Output:**
```
Processing for user   : alice
Request ID            : REQ-12345
Tenant ID             : 42
Nested user (override): admin-override
After nested scope    : alice
Is bound: false
```

---

### ScopedValue vs ThreadLocal

| Feature                 | `ThreadLocal`            | `ScopedValue`                  |
|-------------------------|--------------------------|--------------------------------|
| Mutability              | Mutable (set anytime)    | Immutable within scope         |
| Lifetime                | Until `remove()` or thread end | Bounded to `where().run()` block |
| Memory with 1M VTs      | High (copy per thread)   | Low (no copy — shared immutable ref) |
| Inheritance by child threads | Explicit `InheritableThreadLocal` | Automatic inheritance in scopes |
| Thread safety           | One copy per thread       | Immutable — inherently safe     |
| Structured Concurrency  | ❌ Complex                | ✅ Works seamlessly with `StructuredTaskScope` |

---

## 12. Virtual Threads vs Platform Threads — Benchmarks

### Benchmark 1: I/O-Bound Tasks (Sweet Spot for Virtual Threads)

```java
import java.util.concurrent.*;
import java.util.*;

public class IOBoundBenchmark {

    static final int TASKS      = 10_000;
    static final int IO_DELAY   = 100; // ms

    static long runWithPlatformPool(int poolSize) throws InterruptedException {
        long start = System.currentTimeMillis();
        try (ExecutorService pool = Executors.newFixedThreadPool(poolSize)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < TASKS; i++) {
                futures.add(pool.submit(() -> {
                    try { Thread.sleep(IO_DELAY); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }));
            }
            for (Future<?> f : futures) {
                try { f.get(); } catch (ExecutionException ignored) {}
            }
        }
        return System.currentTimeMillis() - start;
    }

    static long runWithVirtualThreads() throws InterruptedException {
        long start = System.currentTimeMillis();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < TASKS; i++) {
                futures.add(executor.submit(() -> {
                    try { Thread.sleep(IO_DELAY); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }));
            }
            for (Future<?> f : futures) {
                try { f.get(); } catch (ExecutionException ignored) {}
            }
        }
        return System.currentTimeMillis() - start;
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.printf("%-40s │ Time%n", "Configuration");
        System.out.println("─".repeat(50));
        System.out.printf("%-40s │ %dms%n", "Platform Threads (pool=100)",  runWithPlatformPool(100));
        System.out.printf("%-40s │ %dms%n", "Platform Threads (pool=500)",  runWithPlatformPool(500));
        System.out.printf("%-40s │ %dms%n", "Platform Threads (pool=1000)", runWithPlatformPool(1000));
        System.out.printf("%-40s │ %dms%n", "Virtual Threads",              runWithVirtualThreads());
    }
}
```

**Typical Output:**
```
Configuration                            │ Time
──────────────────────────────────────────────────
Platform Threads (pool=100)              │ 10,230ms
Platform Threads (pool=500)              │  2,050ms
Platform Threads (pool=1000)             │  1,070ms
Virtual Threads                          │    115ms  🚀 89x faster than pool=100!
```

---

### Benchmark 2: Memory Usage

```java
public class MemoryBenchmark {
    public static void main(String[] args) throws InterruptedException {

        Runtime rt = Runtime.getRuntime();
        rt.gc();

        long memBefore = rt.totalMemory() - rt.freeMemory();

        // Create 100,000 virtual threads (all sleeping)
        List<Thread> threads = new ArrayList<>(100_000);
        for (int i = 0; i < 100_000; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try { Thread.sleep(30_000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }));
        }

        Thread.sleep(1000); // Let threads get going
        rt.gc();
        long memAfter = rt.totalMemory() - rt.freeMemory();
        long usedMB   = (memAfter - memBefore) / (1024 * 1024);

        System.out.printf("100,000 virtual threads use: ~%d MB%n", usedMB);
        System.out.printf("Per thread: ~%d KB%n", usedMB * 1024 / 100_000);

        // Interrupt all
        threads.forEach(Thread::interrupt);
        for (Thread t : threads) t.join();
    }
}
```

**Typical Output:**
```
100,000 virtual threads use: ~150 MB
Per thread: ~1.5 KB

Compared to platform threads at 1 MB each:
100,000 × 1 MB = 100,000 MB (100 GB!) ← impossible!
```

---

### When Virtual Threads DON'T Help — CPU-Bound Work

```java
public class CPUBoundComparison {
    static long computeSum(int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) sum += i;
        return sum;
    }

    public static void main(String[] args) throws InterruptedException {

        int cpuCores = Runtime.getRuntime().availableProcessors();
        int tasks    = cpuCores * 4;

        // Platform thread pool (uses all CPU cores)
        long t0 = System.currentTimeMillis();
        try (ExecutorService pool = Executors.newFixedThreadPool(cpuCores)) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < tasks; i++)
                fs.add(pool.submit(() -> computeSum(10_000_000)));
            for (Future<?> f : fs) try { f.get(); } catch (Exception e) {}
        }
        System.out.println("Platform pool (CPU cores=" + cpuCores + "): " +
                (System.currentTimeMillis() - t0) + "ms");

        // Virtual threads
        long t1 = System.currentTimeMillis();
        try (ExecutorService vte = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < tasks; i++)
                fs.add(vte.submit(() -> computeSum(10_000_000)));
            for (Future<?> f : fs) try { f.get(); } catch (Exception e) {}
        }
        System.out.println("Virtual threads:                      " +
                (System.currentTimeMillis() - t1) + "ms");
    }
}
```

**Typical Output (CPU-bound — NO benefit):**
```
Platform pool (CPU cores=8): 420ms
Virtual threads:              440ms  ← Similar! (no I/O to unmount during)
```

> **Rule:** Virtual threads help with **I/O-bound** work. For CPU-bound work, use a **fixed platform thread pool** sized to CPU core count.

---

## 13. Real-World Patterns

### Pattern 1: Web Server Request Handler

```java
import java.util.concurrent.*;
import com.sun.net.httpserver.*;
import java.net.InetSocketAddress;

public class VirtualThreadWebServer {

    static String processRequest(String path) throws InterruptedException {
        // Simulate DB lookup
        Thread.sleep(50);  // ← unmounts VT, frees carrier

        // Simulate cache check
        Thread.sleep(20);  // ← unmounts again

        return "Response for: " + path;
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/api", exchange -> {
            try {
                String response = processRequest(exchange.getRequestURI().getPath());
                byte[] bytes    = response.getBytes();
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().close();
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, 0);
                exchange.getResponseBody().close();
            }
        });

        // Each HTTP request gets its OWN virtual thread — scales to thousands!
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("Server on http://localhost:8080/api");
        System.out.println("Each request = 1 virtual thread (scales to millions!)");
    }
}
```

---

### Pattern 2: Parallel Database Queries with StructuredTaskScope

```java
import java.util.concurrent.*;

public class ParallelDBQueries {

    record Product(int id, String name, double price) {}
    record Inventory(int productId, int quantity) {}
    record Review(int productId, double rating) {}
    record ProductPage(Product product, Inventory inventory, Review review) {}

    // Simulate DB calls
    static Product     fetchProduct(int id)   throws InterruptedException { Thread.sleep(200); return new Product(id, "Widget", 29.99); }
    static Inventory   fetchInventory(int id) throws InterruptedException { Thread.sleep(150); return new Inventory(id, 500); }
    static Review      fetchReviews(int id)   throws InterruptedException { Thread.sleep(300); return new Review(id, 4.7); }

    static ProductPage loadProductPage(int productId) throws Exception {

        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {

            // All three DB calls run IN PARALLEL on virtual threads
            var productTask   = scope.fork(() -> fetchProduct(productId));
            var inventoryTask = scope.fork(() -> fetchInventory(productId));
            var reviewsTask   = scope.fork(() -> fetchReviews(productId));

            scope.join().throwIfFailed();

            return new ProductPage(productTask.get(), inventoryTask.get(), reviewsTask.get());
        }
        // Total time ≈ max(200, 150, 300) = 300ms  (not 200+150+300 = 650ms!)
    }

    public static void main(String[] args) throws Exception {
        long start = System.currentTimeMillis();
        ProductPage page = loadProductPage(101);

        System.out.println("Product   : " + page.product());
        System.out.println("Inventory : " + page.inventory());
        System.out.println("Reviews   : " + page.review());
        System.out.printf("Total time: %dms  (sequential would be ~650ms)%n",
                System.currentTimeMillis() - start);
    }
}
```

---

### Pattern 3: Batch Processing with Throttling

```java
import java.util.concurrent.*;

public class ThrottledBatchProcessor {

    static final Semaphore THROTTLE = new Semaphore(50); // Max 50 concurrent operations

    static void processItem(int id) throws InterruptedException {
        THROTTLE.acquire(); // Limit concurrency even with virtual threads
        try {
            Thread.sleep(100); // Simulate external API call
            System.out.printf("Processed item %d on %s%n", id, Thread.currentThread().getName());
        } finally {
            THROTTLE.release();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        int totalItems = 500;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 1; i <= totalItems; i++) {
                final int item = i;
                executor.submit(() -> {
                    try { processItem(item); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
            }
        }
        System.out.println("All " + totalItems + " items processed.");
    }
}
```

---

### Pattern 4: Timeout with Virtual Threads

```java
import java.util.concurrent.*;

public class TimeoutPattern {

    static String callSlowService() throws InterruptedException {
        Thread.sleep(5000); // Slow external service
        return "slow response";
    }

    public static void main(String[] args) throws Exception {

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            Future<String> future = executor.submit(() -> callSlowService());

            try {
                // Wait at most 2 seconds
                String result = future.get(2, TimeUnit.SECONDS);
                System.out.println("Result: " + result);
            } catch (TimeoutException e) {
                System.out.println("Service timed out — cancelling.");
                future.cancel(true); // Interrupt the virtual thread
            } catch (ExecutionException e) {
                System.out.println("Service failed: " + e.getCause().getMessage());
            }
        }
    }
}
```

---

## 14. What NOT To Do

### ❌ Don't Pool Virtual Threads

```java
// ❌ WRONG — pooling virtual threads is pointless and harmful
ExecutorService vtPool = Executors.newFixedThreadPool(
    200,
    Thread.ofVirtual().factory()  // Pooling virtual threads!
);
// Virtual threads are lightweight — creating a new one per task is always correct

// ✅ CORRECT — one virtual thread per task
ExecutorService correct = Executors.newVirtualThreadPerTaskExecutor();
```

---

### ❌ Don't Use for CPU-Bound Work

```java
// ❌ WRONG — CPU-bound work gets no benefit from virtual threads
try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int i = 0; i < 16; i++)
        ex.submit(() -> encryptLargeFile()); // No I/O, no unmounting → no benefit
}

// ✅ CORRECT — use platform thread pool sized to CPU cores
int cores = Runtime.getRuntime().availableProcessors();
try (var ex = Executors.newFixedThreadPool(cores)) {
    for (int i = 0; i < 16; i++)
        ex.submit(() -> encryptLargeFile()); // Full CPU parallelism
}
```

---

### ❌ Don't Use `synchronized` with Blocking I/O (Pinning)

```java
// ❌ WRONG — pins the carrier thread during I/O
synchronized (this) {
    String result = httpClient.send(request).body();  // CARRIER PINNED!
    this.cache = result;
}

// ✅ CORRECT — do I/O outside the lock
String result = httpClient.send(request).body();   // Carrier freed during I/O ✅
synchronized (this) {
    this.cache = result;                             // Fast state update only
}

// ✅ ALSO CORRECT — use ReentrantLock
ReentrantLock lock = new ReentrantLock();
lock.lock();
try {
    String result = httpClient.send(request).body();  // Carrier freed ✅
    this.cache = result;
} finally { lock.unlock(); }
```

---

### ❌ Don't Rely on `ThreadLocal` for Heavy Objects at Scale

```java
// ❌ RISKY at scale — 1 million virtual threads × heavy object = memory pressure
ThreadLocal<HeavyConnectionPool> pool = ThreadLocal.withInitial(HeavyConnectionPool::new);

// ✅ BETTER — share a connection pool across all virtual threads
// (JDBC pools, HTTP clients are already thread-safe)
HikariDataSource sharedPool = new HikariDataSource(config);  // Shared across all VTs

// ✅ FOR CONTEXT PASSING — use ScopedValue instead
ScopedValue<String> userId = ScopedValue.newInstance();
ScopedValue.where(userId, "alice").run(() -> {
    // Immutable, automatically cleaned up, no memory leak
});
```

---

### ❌ Don't Set Priority on Virtual Threads

```java
Thread vt = Thread.ofVirtual().start(() -> {});

// ❌ These are silently ignored for virtual threads
vt.setPriority(Thread.MAX_PRIORITY); // Has no effect
System.out.println(vt.getPriority()); // Always returns 5 (NORM_PRIORITY)
```

---

## 15. Virtual Threads with Frameworks

### Spring Boot 3.2+

```properties
# application.properties — one line to enable virtual threads for ALL requests
spring.threads.virtual.enabled=true
```

```java
// Manual configuration if needed
@Configuration
public class VirtualThreadConfig {

    // Use virtual threads for Tomcat request handling
    @Bean
    public TomcatProtocolHandlerCustomizer<?> virtualThreadTomcat() {
        return handler -> handler.setExecutor(
            Executors.newVirtualThreadPerTaskExecutor()
        );
    }

    // Use virtual threads for @Async methods
    @Bean
    public AsyncTaskExecutor applicationTaskExecutor() {
        return new TaskExecutorAdapter(
            Executors.newVirtualThreadPerTaskExecutor()
        );
    }
}
```

```java
// Spring @Async with virtual threads
@Service
public class ReportService {

    @Async  // Runs on virtual thread with above config
    public CompletableFuture<Report> generateReport(int userId) {
        Report report = buildReport(userId); // I/O-heavy — perfect for VTs
        return CompletableFuture.completedFuture(report);
    }
}
```

---

### Plain Java HTTP Client (Java 11+)

```java
import java.net.http.*;
import java.net.URI;
import java.util.concurrent.*;

public class VirtualThreadHttpClient {
    public static void main(String[] args) throws Exception {

        HttpClient client = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

        // Send 100 HTTP requests concurrently on virtual threads
        List<CompletableFuture<String>> futures = new ArrayList<>();

        for (int i = 1; i <= 100; i++) {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://jsonplaceholder.typicode.com/posts/" + i))
                .build();

            futures.add(
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                      .thenApply(HttpResponse::body)
            );
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        System.out.println("All 100 HTTP requests complete!");
    }
}
```

---

### JDBC with Virtual Threads

```java
import java.sql.*;
import java.util.concurrent.*;

public class VirtualThreadJDBC {

    static String DB_URL = "jdbc:postgresql://localhost/mydb";

    static String queryUser(int userId) throws SQLException {
        // JDBC operations unmount virtual threads during I/O ✅
        try (Connection conn = DriverManager.getConnection(DB_URL, "user", "pass");
             PreparedStatement ps = conn.prepareStatement("SELECT name FROM users WHERE id=?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("name") : "Unknown";
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 1; i <= 1000; i++) {
                final int userId = i;
                executor.submit(() -> {
                    try {
                        String name = queryUser(userId);
                        System.out.println("User " + userId + ": " + name);
                    } catch (SQLException e) {
                        System.err.println("DB error: " + e.getMessage());
                    }
                });
            }
        }
    }
}
```

> ⚠️ Use a **connection pool** (HikariCP) to limit DB connections. Virtual threads can try to open millions of connections simultaneously without a pool!

---

## 16. Debugging & Monitoring

### JVM Flags for Virtual Thread Diagnostics

```bash
# Show pinning events (CRITICAL for performance tuning)
java -Djdk.tracePinnedThreads=full    MyApp.java   # Full stack trace per pin
java -Djdk.tracePinnedThreads=short   MyApp.java   # One-line summary per pin

# Control carrier thread pool
java -Djdk.virtualThreadScheduler.parallelism=8    MyApp.java  # Pool size
java -Djdk.virtualThreadScheduler.maxPoolSize=256  MyApp.java  # Max pool size

# Enable JFR (Java Flight Recorder) for virtual thread events
java -XX:StartFlightRecording=filename=vt.jfr,duration=60s MyApp.java
```

---

### Thread Dump — Virtual Threads in JDK 21

```bash
# Generate thread dump (includes virtual threads)
jcmd <pid> Thread.dump_to_file -format=json vt-dump.json

# Or with jstack (shows virtual threads too in JDK 21)
jstack <pid> > thread-dump.txt

# With kill -3 on Linux
kill -3 <pid>
```

**Sample virtual thread in thread dump:**
```
#21 "request-handler-1" virtual
      java.base/java.lang.VirtualThread.park(VirtualThread.java:582)
      java.base/java.lang.Thread.sleep(Thread.java:480)
      com.example.UserService.fetchUser(UserService.java:42)
      com.example.RequestHandler.handle(RequestHandler.java:28)
```

---

### Programmatic Monitoring

```java
import java.lang.management.*;
import java.util.concurrent.*;

public class VirtualThreadMonitor {
    public static void main(String[] args) throws InterruptedException {

        ThreadMXBean tmxBean = ManagementFactory.getThreadMXBean();

        // Start some virtual threads
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                try { Thread.sleep(5000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
        }

        Thread.sleep(500); // Let threads start

        System.out.println("Thread count    : " + tmxBean.getThreadCount());
        System.out.println("Peak threads    : " + tmxBean.getPeakThreadCount());
        System.out.println("Daemon threads  : " + tmxBean.getDaemonThreadCount());

        // Deadlock detection works for virtual threads too
        long[] deadlocked = tmxBean.findDeadlockedThreads();
        System.out.println("Deadlocked      : " + (deadlocked == null ? 0 : deadlocked.length));

        executor.shutdownNow();
    }
}
```

---

## 17. Migration Guide

### Step 1 — Identify Thread Pool Usage

```java
// BEFORE — platform thread pool
ExecutorService executor = Executors.newFixedThreadPool(200);

// AFTER — virtual threads (if I/O-bound tasks)
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
```

---

### Step 2 — Replace Raw Thread Creation

```java
// BEFORE
Thread t = new Thread(runnable);
t.start();

// AFTER
Thread t = Thread.ofVirtual().start(runnable);
```

---

### Step 3 — Audit `synchronized` + Blocking Combinations

```java
// SCAN your code for this pattern:
synchronized (something) {
    // Any of these inside = PINNING RISK:
    Thread.sleep(...)
    socket.read(...)
    fileChannel.read(...)
    connection.executeQuery(...)
    lock.lock()
}

// FIX: move blocking outside sync, or replace with ReentrantLock
```

---

### Step 4 — Audit `ThreadLocal` Usage

```java
// BEFORE — ThreadLocal (ok for small scale, risky at VT scale)
static ThreadLocal<UserContext> ctx = new ThreadLocal<>();

// AFTER — ScopedValue (immutable, no memory leaks)
static final ScopedValue<UserContext> ctx = ScopedValue.newInstance();
ScopedValue.where(ctx, userCtx).run(() -> handleRequest());
```

---

### Step 5 — Configure Spring Boot

```properties
# application.properties
spring.threads.virtual.enabled=true   # Enables virtual threads for all request handling
```

---

### Step 6 — Add Connection Pool Throttling

```java
// Virtual threads can overwhelm external resources — add a semaphore or use HikariCP
HikariConfig config = new HikariConfig();
config.setMaximumPoolSize(50);  // Limit DB connections even though you have millions of VTs
DataSource ds = new HikariDataSource(config);
```

---

### Migration Checklist

```
Pre-migration:
  [ ] Identify all ExecutorService usages
  [ ] Find all raw new Thread() creations
  [ ] Audit synchronized + blocking combinations
  [ ] Review ThreadLocal usage at scale

Code changes:
  [ ] Replace newFixedThreadPool with newVirtualThreadPerTaskExecutor (I/O tasks)
  [ ] Keep newFixedThreadPool(cpuCores) for CPU-bound tasks
  [ ] Replace synchronized+blocking with ReentrantLock
  [ ] Replace ThreadLocal with ScopedValue where applicable
  [ ] Add -Djdk.tracePinnedThreads=full in dev environment

Testing:
  [ ] Run with -Djdk.tracePinnedThreads=full (no pinning warnings?)
  [ ] Load test with realistic concurrency
  [ ] Monitor memory usage under high load
  [ ] Verify connection pool limits are set

Production:
  [ ] Set spring.threads.virtual.enabled=true (Spring Boot)
  [ ] Configure carrier thread pool size if needed
  [ ] Set up JFR monitoring for virtual thread events
```

---

## 18. Interview Questions & Answers

| # | Question | Answer |
|---|----------|--------|
| 1 | What are Virtual Threads? | Lightweight JVM-managed threads that unmount from carrier threads during blocking, allowing millions of concurrent threads |
| 2 | What JEP introduced Virtual Threads? | JEP 444 finalized in Java 21; previewed as JEP 425 (Java 19), JEP 436 (Java 20) |
| 3 | What is a carrier thread? | A platform thread from the JVM's ForkJoinPool that executes virtual threads |
| 4 | How many carrier threads exist? | Equal to `Runtime.getRuntime().availableProcessors()` by default |
| 5 | What is mounting/unmounting? | Mounting: VT assigned to a carrier to run. Unmounting: VT detached from carrier during blocking, carrier freed for others |
| 6 | What is pinning? | VT cannot unmount because it's inside `synchronized` + blocking or a native method |
| 7 | How to fix pinning? | Replace `synchronized` + blocking with `ReentrantLock`; move blocking outside `synchronized` |
| 8 | How to detect pinning? | `-Djdk.tracePinnedThreads=full` JVM flag |
| 9 | Should you pool virtual threads? | No — they're cheap; always use `newVirtualThreadPerTaskExecutor()` |
| 10 | When do VTs NOT help? | CPU-bound tasks — no I/O means no unmounting means no benefit |
| 11 | What is Structured Concurrency? | `StructuredTaskScope` treats forked tasks as a unit — auto-cancels siblings on failure (JEP 453) |
| 12 | `ShutdownOnFailure` vs `ShutdownOnSuccess`? | `ShutdownOnFailure` waits for all (first failure cancels others); `ShutdownOnSuccess` returns first success (cancels others) |
| 13 | What is ScopedValue? | Immutable, bounded per-scope variable — virtual-thread-friendly replacement for `ThreadLocal` (JEP 446) |
| 14 | Are VTs daemon threads? | Yes — always daemon threads by default |
| 15 | Can you set priority on VTs? | No — priority is always NORM_PRIORITY (5); `setPriority()` is ignored |
| 16 | VTs vs reactive programming? | VTs allow simple blocking code to scale like reactive, without callback complexity |
| 17 | How to enable VTs in Spring Boot? | `spring.threads.virtual.enabled=true` in application.properties (Spring Boot 3.2+) |
| 18 | Stack size of VTs vs platform? | VTs: ~200 bytes initial, grows dynamically; Platform: ~1 MB fixed |
| 19 | How does `Thread.sleep()` behave in VTs? | Unmounts VT from carrier; carrier is freed to run other VTs (unlike platform threads which block the OS thread) |
| 20 | `isVirtual()` method? | `Thread.currentThread().isVirtual()` returns `true` for virtual threads |

---

## 19. Complete Reference Summary

### Quick API Reference

```java
// ── Creating Virtual Threads ─────────────────────────────────────
Thread.startVirtualThread(Runnable r);                 // Quickest
Thread.ofVirtual().start(Runnable r);                  // Named start
Thread.ofVirtual().name("vt-1").start(r);              // Named
Thread.ofVirtual().unstarted(r);                       // Create without starting
Thread.ofVirtual().name("prefix-", 0).factory();       // ThreadFactory

// ── ExecutorService ──────────────────────────────────────────────
Executors.newVirtualThreadPerTaskExecutor();            // Main production API
Executors.newThreadPerTaskExecutor(vtFactory);          // With custom factory

// ── Checking if Virtual ──────────────────────────────────────────
Thread.currentThread().isVirtual();                    // true/false
thread.isVirtual();                                    // true/false

// ── Structured Concurrency ────────────────────────────────────────
new StructuredTaskScope.ShutdownOnFailure();            // All must succeed
new StructuredTaskScope.ShutdownOnSuccess<>();          // First success wins
scope.fork(Callable<T> task);                          // Submit subtask
scope.join();                                          // Wait for all
scope.throwIfFailed();                                 // Re-throw any exception
scope.result();                                        // Get first success (ShutdownOnSuccess)
subtask.get();                                         // Get subtask result

// ── Scoped Values ────────────────────────────────────────────────
ScopedValue<T> sv = ScopedValue.newInstance();
ScopedValue.where(sv, value).run(Runnable r);
ScopedValue.where(sv, value).call(Callable<T> c);
sv.get();                                              // Read current value
sv.isBound();                                          // Is it bound in this scope?

// ── Pinning Detection ────────────────────────────────────────────
// JVM flags:
// -Djdk.tracePinnedThreads=full
// -Djdk.tracePinnedThreads=short
```

---

### Architecture Diagram

```
Java 21 — Virtual Thread Architecture

                     User Code
                         │
           ┌─────────────┴──────────────┐
           │   Virtual Threads          │
           │   VT1 VT2 VT3 ... VT-1M    │  ← millions, JVM heap
           └─────────────┬──────────────┘
                         │ schedule
                         ▼
           ┌─────────────────────────────┐
           │   JVM Scheduler (FIFO)      │  ← work-stealing ForkJoinPool
           └─────────────┬───────────────┘
                         │ assign
                         ▼
    ┌────────────────────────────────────────┐
    │      Carrier Threads (= CPU cores)     │  ← platform threads
    │  CT-1      CT-2      CT-3      CT-4    │
    │  [VT-5]    [VT-12]   [VT-99]   [idle]  │
    └────────────────────────────────────────┘
                         │
                         ▼
           ┌─────────────────────────────┐
           │   OS / Hardware             │
           │   CPU-1 CPU-2 CPU-3 CPU-4   │
           └─────────────────────────────┘

When VT-5 hits I/O:
  VT-5 stack → saved to heap (few KB)
  CT-1 immediately picks up VT-1003 from queue
  When VT-5's I/O completes → back in scheduler queue
  Any available carrier picks it up
```

---

### Feature Map

```
Java 21 Virtual Threads Ecosystem
│
├── Core (JEP 444 — Final)
│   ├── Thread.ofVirtual()
│   ├── Thread.startVirtualThread()
│   ├── Thread.isVirtual()
│   └── Executors.newVirtualThreadPerTaskExecutor()
│
├── Structured Concurrency (JEP 453 — Preview in 21, Final in 22)
│   ├── StructuredTaskScope.ShutdownOnFailure
│   └── StructuredTaskScope.ShutdownOnSuccess
│
├── Scoped Values (JEP 446 — Preview in 21, Final in 22)
│   └── ScopedValue<T>
│
├── Gotchas
│   ├── Pinning (synchronized + blocking)  → use ReentrantLock
│   ├── No thread pooling                  → create per task
│   ├── Not for CPU-bound work             → use FixedThreadPool
│   └── ThreadLocal at scale               → use ScopedValue
│
├── Best For
│   ├── HTTP servers (one VT per request)
│   ├── DB queries (JDBC I/O)
│   ├── Microservice fan-out calls
│   ├── File I/O pipelines
│   └── Any I/O-bound concurrent workload
│
└── Framework Support
    ├── Spring Boot 3.2+ (spring.threads.virtual.enabled=true)
    ├── Helidon 4+
    ├── Quarkus (experimental)
    └── Java HttpServer (setExecutor)
```

---

*Made with ❤️ for Java 21 developers — JEP 444, 453, 446*
