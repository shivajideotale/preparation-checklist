# ☕ Java OutOfMemoryError — Deep Dive Complete Guide

> `java.lang.OutOfMemoryError` — JVM Memory Management, Causes, Detection & Prevention

---

## 📌 Table of Contents

1. [What is OutOfMemoryError?](#1-what-is-outofmemoryerror)
2. [JVM Memory Architecture](#2-jvm-memory-architecture)
3. [Types of OutOfMemoryError](#3-types-of-outofmemoryerror)
4. [Java Heap Space OOM](#4-java-heap-space-oom)
5. [GC Overhead Limit Exceeded](#5-gc-overhead-limit-exceeded)
6. [Metaspace OOM](#6-metaspace-oom)
7. [Direct Buffer Memory OOM](#7-direct-buffer-memory-oom)
8. [Unable to Create Native Thread OOM](#8-unable-to-create-native-thread-oom)
9. [Requested Array Size Exceeds VM Limit](#9-requested-array-size-exceeds-vm-limit)
10. [Kill Process or Sacrifice Child](#10-kill-process-or-sacrifice-child)
11. [Memory Leaks — Root Causes](#11-memory-leaks--root-causes)
12. [Detecting & Diagnosing OOM](#12-detecting--diagnosing-oom)
13. [Heap Dumps — Analysis](#13-heap-dumps--analysis)
14. [JVM Tuning Flags](#14-jvm-tuning-flags)
15. [Prevention Best Practices](#15-prevention-best-practices)
16. [Garbage Collectors Overview](#16-garbage-collectors-overview)
17. [Real-World OOM Scenarios & Fixes](#17-real-world-oom-scenarios--fixes)
18. [Monitoring & Alerting](#18-monitoring--alerting)
19. [Interview Questions & Answers](#19-interview-questions--answers)
20. [Complete Reference Summary](#20-complete-reference-summary)

---

## 1. What is OutOfMemoryError?

`OutOfMemoryError` is a subclass of `Error` (not `Exception`) thrown by the JVM when it **cannot allocate an object** because there is not enough memory available, even after Garbage Collection has been attempted.

```
java.lang.Object
    └── java.lang.Throwable
            └── java.lang.Error               ← Not Exception!
                    └── java.lang.VirtualMachineError
                            └── java.lang.OutOfMemoryError
```

### Key Characteristics

```
┌─────────────────────────────────────────────────────────────────┐
│  OutOfMemoryError                                               │
│                                                                 │
│  • Extends Error — signals JVM cannot recover                   │
│  • Should NOT be caught in normal application code              │
│  • Has several sub-types, each with a different message         │
│  • The JVM tries full GC before throwing OOM                    │
│  • On OOM, the JVM can dump heap and execute a command          │
└─────────────────────────────────────────────────────────────────┘
```

```java
// OutOfMemoryError IS catchable (it's a Throwable)
// but catching it is RARELY correct
try {
    byte[] hugeArray = new byte[Integer.MAX_VALUE];
} catch (OutOfMemoryError e) {
    // The JVM may be in an unstable state here
    // Only acceptable use: log and shutdown gracefully
    System.err.println("FATAL: Out of memory — " + e.getMessage());
    System.exit(1); // Controlled shutdown
}
```

---

## 2. JVM Memory Architecture

Understanding JVM memory regions is essential to diagnosing OOM errors.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           JVM MEMORY LAYOUT                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                         HEAP  (-Xms / -Xmx)                         │    │
│  │                                                                     │    │
│  │  ┌──────────────────────────────────┐  ┌────────────────────────┐   │    │
│  │  │        Young Generation          │  │    Old Generation      │   │    │
│  │  │                                  │  │    (Tenured Space)     │   │    │
│  │  │  ┌──────────┐  ┌──┐  ┌──┐        │  │                        │   │    │
│  │  │  │   Eden   │  │S0│  │S1│        │  │  Long-lived objects    │   │    │
│  │  │  │  Space   │  │  │  │  │        │  │  (survived many GCs)   │   │    │
│  │  │  └──────────┘  └──┘  └──┘        │  │                        │   │    │
│  │  │  (new objects born here)  (Survivors)                        │   │    │
│  │  └──────────────────────────────────┘  └────────────────────────┘   │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                             │
│  ┌──────────────────────────┐   ┌────────────────────────────┐              │
│  │   METASPACE              │   │   DIRECT BUFFER MEMORY     │              │
│  │  (class metadata,        │   │  (NIO ByteBuffers,         │              │
│  │   method info, JIT code) │   │   off-heap allocations)    │              │
│  │   (-XX:MaxMetaspaceSize) │   │   (-XX:MaxDirectMemorySize)│              │
│  └──────────────────────────┘   └────────────────────────────┘              │
│                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  THREAD STACKS  (one per thread)  (-Xss per thread)                  │   │
│  │  [Stack Frame][Stack Frame][Stack Frame]...                          │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Memory Region Summary

| Region | Stores | OOM Message | Default Size |
|--------|--------|-------------|--------------|
| **Heap (Young)** | New objects, short-lived | `Java heap space` | ~1/3 of heap |
| **Heap (Old/Tenured)** | Long-lived objects | `Java heap space` | ~2/3 of heap |
| **Metaspace** | Class metadata, method bytecode | `Metaspace` | Unlimited (OS limit) |
| **Direct Memory** | NIO off-heap ByteBuffers | `Direct buffer memory` | = `-Xmx` value |
| **Thread Stack** | Stack frames, local vars | `Unable to create native thread` | 512KB–1MB/thread |
| **Code Cache** | JIT-compiled native code | `CodeCache is full` | 240MB (Java 9+) |

---

## 3. Types of OutOfMemoryError

Java OOM is not one error — there are **7 distinct types**, each with a different message indicating a different root cause.

```
OutOfMemoryError Messages:
┌──────────────────────────────────────────────────────────────────┐
│ "Java heap space"                 ← Heap full                    │
│ "GC overhead limit exceeded"      ← GC spending too much time    │
│ "Metaspace"                       ← Class metadata full          │
│ "Direct buffer memory"            ← NIO off-heap full            │
│ "unable to create native thread"  ← Too many threads             │
│ "Requested array size exceeds VM limit" ← Array too large        │
│ "kill process or sacrifice child" ← OS killed JVM (Linux OOM)    │
└──────────────────────────────────────────────────────────────────┘
```

---

## 4. Java Heap Space OOM

**Most common OOM.** Thrown when the heap cannot allocate a new object even after full GC.

### Trigger: Accumulating Objects Without Release

```java
import java.util.*;

public class HeapSpaceOOM {

    // ── Example 1: Classic memory leak — unbounded List ───────────────────────
    static List<byte[]> leakyList = new ArrayList<>();

    static void fillHeap() {
        System.out.println("Filling heap...");
        while (true) {
            leakyList.add(new byte[1024 * 1024]); // Add 1MB chunks endlessly
            System.out.println("Heap used: " + leakyList.size() + "MB");
        }
    }

    // ── Example 2: Large object allocation ────────────────────────────────────
    static void allocateLargeObject() {
        // Single allocation larger than available heap
        // Run with: java -Xmx64m HeapSpaceOOM
        byte[] massive = new byte[500 * 1024 * 1024]; // 500MB but -Xmx64m
        System.out.println("Allocated: " + massive.length);
    }

    // ── Example 3: Static field accumulation ─────────────────────────────────
    static Map<String, List<Object>> staticCache = new HashMap<>();

    static void cacheAccumulation(String key, Object value) {
        // Objects cached in static field — NEVER eligible for GC
        staticCache.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }

    // ── Example 4: Event listener leak ───────────────────────────────────────
    static List<Runnable> listeners = new ArrayList<>();

    static void registerListener(Runnable listener) {
        listeners.add(listener); // Added but NEVER removed
    }

    // ── SAFE version: bounded cache with eviction ─────────────────────────────
    static Map<String, byte[]> safeBoundedCache = new LinkedHashMap<>(100, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > 100; // Evict when over 100 entries
        }
    };

    public static void main(String[] args) {
        // ❌ This will OOM:
        // Run with: java -Xmx64m HeapSpaceOOM
        try {
            fillHeap();
        } catch (OutOfMemoryError e) {
            System.err.println("OOM caught: " + e.getMessage());
            // "Java heap space"
        }

        // ✅ Safe bounded cache — won't OOM
        for (int i = 0; i < 1000; i++) {
            safeBoundedCache.put("key-" + i, new byte[1024]);
        }
        System.out.println("Safe cache size: " + safeBoundedCache.size()); // 100
    }
}
```

---

### Memory Leak via Inner Class

```java
public class InnerClassLeak {

    byte[] largeData = new byte[10 * 1024 * 1024]; // 10MB per instance

    // ❌ Non-static inner class holds implicit reference to outer instance
    class InnerTask implements Runnable {
        @Override
        public void run() {
            System.out.println("Running task...");
            // Even if we don't USE largeData here,
            // InnerTask holds a reference to InnerClassLeak.this
            // → largeData can NEVER be GC'd while InnerTask is alive
        }
    }

    // ✅ Static nested class — no reference to outer instance
    static class StaticTask implements Runnable {
        @Override
        public void run() {
            System.out.println("Running static task — no outer reference");
        }
    }

    public static void main(String[] args) {
        List<Runnable> tasks = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            InnerClassLeak outer = new InnerClassLeak(); // 10MB

            // ❌ Stores inner instance → keeps outer → keeps 10MB alive
            tasks.add(outer.new InnerTask()); // 100 × 10MB = 1GB leak

            // ✅ Static task — outer becomes eligible for GC
            // tasks.add(new StaticTask());
        }

        System.out.println("Tasks stored: " + tasks.size());
    }
}
```

---

### Memory Leak via ThreadLocal

```java
public class ThreadLocalLeak {

    // ❌ ThreadLocal value holds 5MB per thread
    static ThreadLocal<byte[]> threadLocalData =
        ThreadLocal.withInitial(() -> new byte[5 * 1024 * 1024]);

    static void processRequest() {
        byte[] data = threadLocalData.get(); // Set for this thread
        // ... do work with data ...

        // ❌ MISSING: threadLocalData.remove();
        // In a thread pool: thread is reused → ThreadLocal keeps 5MB forever per thread
    }

    // ✅ Always remove ThreadLocal in thread pools
    static void processRequestSafe() {
        try {
            byte[] data = threadLocalData.get();
            // ... use data ...
        } finally {
            threadLocalData.remove(); // ← CRITICAL in thread pools!
        }
    }

    public static void main(String[] args) throws InterruptedException {
        java.util.concurrent.ExecutorService pool =
            java.util.concurrent.Executors.newFixedThreadPool(10);

        for (int i = 0; i < 1000; i++) {
            pool.submit(ThreadLocalLeak::processRequest); // Leaks 5MB per thread
            // pool.submit(ThreadLocalLeak::processRequestSafe); // Safe
        }

        pool.shutdown();
    }
}
```

---

### Memory Leak via HashMap with Mutable Keys

```java
import java.util.*;

public class MutableKeyLeak {

    // ❌ Mutable class used as HashMap key
    static class MutableKey {
        int id;
        MutableKey(int id) { this.id = id; }

        @Override public int hashCode() { return id; }
        @Override public boolean equals(Object o) {
            return o instanceof MutableKey mk && mk.id == this.id;
        }
    }

    public static void main(String[] args) {
        Map<MutableKey, String> map = new HashMap<>();

        MutableKey key = new MutableKey(1);
        map.put(key, "value");

        System.out.println("Before mutation: " + map.get(key)); // value

        key.id = 99; // ← Mutate the key AFTER insertion

        // Now the key is in the wrong bucket!
        System.out.println("After mutation:  " + map.get(key)); // null (lost!)
        System.out.println("Map size:        " + map.size());   // 1 (still there, unreachable!)

        // The entry is permanently UNREACHABLE — it's a "zombie" entry
        // The map grows unbounded as more mutated keys accumulate

        // ✅ Use immutable keys: String, Integer, Long, UUID, record
        Map<String, String> safeMap = new HashMap<>();
        safeMap.put("key-1", "value"); // String is immutable — always safe
    }
}
```

---

## 5. GC Overhead Limit Exceeded

Thrown when the JVM spends **more than 98% of its time doing GC** but recovers **less than 2% of heap** across multiple consecutive GC cycles. It's a "struggling" signal before full OOM.

```java
import java.util.*;

public class GCOverheadOOM {

    // This pattern causes GC to work endlessly but recover very little
    // Run with: java -Xmx32m -XX:+UseParallelGC GCOverheadOOM
    public static void main(String[] args) {

        Map<String, String> map = new HashMap<>();
        int i = 0;

        try {
            while (true) {
                // Generate many short-lived interned strings
                // These fill the heap with objects that are "almost" collectable
                String key   = "key-" + i;
                String value = "value-" + UUID.randomUUID().toString() + "-" + i;
                map.put(key, value);
                i++;

                // Periodically remove old ones — GC has to chase references constantly
                if (i % 1000 == 0) {
                    for (int j = i - 500; j < i - 100; j++) {
                        map.remove("key-" + j);
                    }
                    System.out.println("Map size: " + map.size() + " entries");
                }
            }
        } catch (OutOfMemoryError e) {
            System.err.println("OOM: " + e.getMessage());
            // "GC overhead limit exceeded"
        }
    }
}
```

### How to Diagnose GC Overhead

```
JVM flags to add:

-verbose:gc
-XX:+PrintGCDetails        (Java 8)
-Xlog:gc*                  (Java 9+)
-XX:+PrintGCDateStamps     (Java 8)

Sample GC log showing the problem:
[GC (Allocation Failure) 30696K->29968K(31744K), 0.9870350 secs]  ← 30KB freed in ~1sec
[GC (Allocation Failure) 29968K->29952K(31744K), 0.8950234 secs]  ← 16KB freed
[GC (Allocation Failure) 29952K->29944K(31744K), 0.9234560 secs]  ← 8KB freed
→ GC is spinning, recovering near-zero memory → OOM imminent
```

### Fixes for GC Overhead

```
1. Increase heap size: -Xmx512m → -Xmx2g
2. Fix the memory leak (root cause)
3. Use off-heap storage (Direct Buffers, disk-backed cache)
4. Disable the limit (NOT recommended — delays inevitable OOM):
   -XX:-UseGCOverheadLimit
5. Use a better GC: -XX:+UseG1GC or -XX:+UseZGC
```

---

## 6. Metaspace OOM

**Metaspace** (Java 8+, replaced PermGen) stores **class metadata** — class definitions, method bytecode, JIT code. By default it can grow until the OS runs out of memory.

### What Goes in Metaspace

```
Metaspace stores:
  ✦ Class definitions (bytecode of loaded classes)
  ✦ Method signatures and bytecode
  ✦ Static fields (references stored here, objects on heap)
  ✦ Constant pool
  ✦ JIT-compiled native code (Code Cache)
  ✦ Annotations metadata
  ✦ Interface information
```

### Trigger: Dynamic Class Generation

```java
import javassist.*;
import java.util.*;

public class MetaspaceOOM {

    // ── Scenario 1: Dynamic class generation (real-world cause) ──────────────
    // Frameworks like Spring, Hibernate, CGLIB generate proxy classes at runtime
    // If class loaders are not collected properly → Metaspace fills up

    // Simulating with reflection-based class definition
    static void simulateClassLoaderLeak() throws Exception {
        List<Class<?>> loadedClasses = new ArrayList<>();

        // In real apps this happens with:
        // - Hot-deploy / class reload in app servers
        // - CGLIB proxy generation without caching
        // - Groovy/scripting engine without class eviction
        // - OSGi bundle reloading

        // Each iteration loads a NEW class into Metaspace
        // If ClassLoaders are not GC'd → classes are never unloaded
        for (int i = 0; i < 100_000; i++) {
            ClassLoader cl = new ClassLoader(null) {
                // Custom class loader that holds a reference to loaded class
            };
            loadedClasses.add(cl.loadClass("java.lang.String")); // Simple example
        }
    }

    // ── Scenario 2: String.intern() abuse ────────────────────────────────────
    // In Java 7+, interned strings are on heap (not PermGen/Metaspace)
    // But excessive interning wastes heap
    static void internAbuse() {
        List<String> interned = new ArrayList<>();
        for (int i = 0; i < 10_000_000; i++) {
            interned.add(("unique-value-" + i).intern()); // Each unique string retained permanently
        }
    }

    // ── Scenario 3: JSP recompilation / scripting engines ────────────────────
    // Each JSP page compile creates new classes
    // Without MaxMetaspaceSize, this grows unbounded

    public static void main(String[] args) {
        // Run with: java -XX:MaxMetaspaceSize=64m MetaspaceOOM
        System.out.println("Metaspace OOM typically caused by:");
        System.out.println("  1. Dynamic proxy generation (CGLIB, Spring AOP)");
        System.out.println("  2. Hot deployment / class reloading in app servers");
        System.out.println("  3. Scripting engines generating classes per script");
        System.out.println("  4. ClassLoader leaks (parent loader can't be GC'd)");

        // Detect current metaspace usage
        java.lang.management.MemoryMXBean memBean =
            java.lang.management.ManagementFactory.getMemoryMXBean();

        for (java.lang.management.MemoryPoolMXBean pool :
                java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getName().toLowerCase().contains("metaspace")) {
                System.out.printf("%nMetaspace: used=%dMB, committed=%dMB, max=%s%n",
                    pool.getUsage().getUsed()      / 1024 / 1024,
                    pool.getUsage().getCommitted() / 1024 / 1024,
                    pool.getUsage().getMax() == -1 ? "unlimited"
                        : pool.getUsage().getMax() / 1024 / 1024 + "MB"
                );
            }
        }
    }
}
```

### Metaspace JVM Flags

```
# Limit Metaspace (triggers OOM instead of consuming all OS memory)
-XX:MaxMetaspaceSize=256m

# Initial Metaspace size (avoid early resizing)
-XX:MetaspaceSize=128m

# Minimum free ratio before expanding (default 40%)
-XX:MinMetaspaceFreeRatio=40

# Maximum free ratio before shrinking (default 70%)
-XX:MaxMetaspaceFreeRatio=70

# Print class loading/unloading events
-XX:+TraceClassLoading
-XX:+TraceClassUnloading

# Monitor in jconsole / jvisualvm → Memory → Metaspace pool
```

---

## 7. Direct Buffer Memory OOM

**Direct Buffers** are allocated **outside the heap** using `ByteBuffer.allocateDirect()`. Used by NIO channels, network I/O, and file operations for zero-copy performance.

```java
import java.nio.*;
import java.util.*;

public class DirectBufferOOM {

    // ── Why Direct Buffers? ───────────────────────────────────────────────────
    // Heap buffer:   Java heap → copy to OS buffer → I/O
    // Direct buffer: OS buffer (off-heap) → I/O (zero-copy, faster for large I/O)

    // ── Scenario 1: Accumulating direct buffers ───────────────────────────────
    static void directBufferLeak() {
        // Run with: java -XX:MaxDirectMemorySize=64m DirectBufferOOM
        List<ByteBuffer> buffers = new ArrayList<>();
        int count = 0;

        try {
            while (true) {
                ByteBuffer buf = ByteBuffer.allocateDirect(1024 * 1024); // 1MB direct buffer
                buffers.add(buf); // Held in list → never freed
                count++;
                System.out.println("Allocated direct buffers: " + count + " MB");
            }
        } catch (OutOfMemoryError e) {
            System.err.println("OOM: " + e.getMessage());
            // "Direct buffer memory"
        }
    }

    // ── Scenario 2: Netty / NIO framework buffer mismanagement ───────────────
    // Frameworks like Netty use direct buffers extensively
    // Forgetting to release → DirectBufferOOM even with heap free

    // ── Heap vs Direct buffer comparison ─────────────────────────────────────
    static void compareBuffers() throws Exception {
        int size = 100 * 1024 * 1024; // 100MB

        // Heap buffer — uses JVM heap
        long t0 = System.nanoTime();
        ByteBuffer heap = ByteBuffer.allocate(size);
        heap.put(new byte[size]);
        long heapTime = System.nanoTime() - t0;

        // Direct buffer — uses off-heap memory
        long t1 = System.nanoTime();
        ByteBuffer direct = ByteBuffer.allocateDirect(size);
        direct.put(new byte[size]);
        long directTime = System.nanoTime() - t1;

        System.out.printf("Heap buffer write:   %,d ns%n", heapTime);
        System.out.printf("Direct buffer write: %,d ns%n", directTime);
        System.out.println("(Direct is faster for I/O operations involving OS)");

        // ✅ Clean up direct buffer explicitly
        // GC does not immediately collect direct buffers — their Cleaner runs
        // Use sun.misc.Cleaner or force GC (not recommended in production)
        heap   = null; // GC can collect this immediately
        direct = null; // The off-heap memory is freed only when Cleaner runs
        System.gc();   // Suggest GC — may trigger direct buffer cleanup
    }

    // ── Monitoring direct memory ──────────────────────────────────────────────
    static void monitorDirectMemory() {
        try {
            // Access via JMX BufferPool MXBean
            java.lang.management.ManagementFactory
                .getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)
                .forEach(pool -> System.out.printf(
                    "Buffer pool %-10s: count=%d, used=%dMB, capacity=%dMB%n",
                    pool.getName(),
                    pool.getCount(),
                    pool.getMemoryUsed()   / 1024 / 1024,
                    pool.getTotalCapacity()/ 1024 / 1024
                ));
        } catch (Exception e) {
            System.err.println("Monitoring error: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        compareBuffers();
        monitorDirectMemory();
    }
}
```

### Direct Buffer Best Practices

```
JVM flags:
  -XX:MaxDirectMemorySize=512m   ← Limit off-heap allocation

Safe patterns:
  ✅ Reuse direct buffers (pool them — don't allocate per request)
  ✅ Use try-finally or Cleaner to release direct buffers
  ✅ Monitor with -Djdk.nio.maxCachedBufferSize (Java 9+)
  ✅ Use jcmd <pid> VM.native_memory to see direct memory usage

Netty-specific:
  ✅ Use ReferenceCountUtil.release(buf) after use
  ✅ Use ResourceLeakDetector.setLevel(PARANOID) during dev/test
  ✅ Enable leak detection: -Dio.netty.leakDetectionLevel=advanced
```

---

## 8. Unable to Create Native Thread OOM

Thrown when the JVM **cannot create a new thread**. The heap may be completely free — the problem is the OS thread limit or physical memory for thread stacks.

```java
import java.util.concurrent.*;
import java.util.*;

public class NativeThreadOOM {

    // ── Scenario: Too many threads ────────────────────────────────────────────
    // Run this and it will eventually fail with:
    // "java.lang.OutOfMemoryError: unable to create native thread"
    static void threadExplosion() {
        List<Thread> threads = new ArrayList<>();
        int count = 0;

        try {
            while (true) {
                Thread t = new Thread(() -> {
                    try {
                        Thread.sleep(Long.MAX_VALUE); // Sleep forever — holds thread stack
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                t.setDaemon(true);
                t.start();
                threads.add(t);
                count++;

                if (count % 100 == 0) {
                    System.out.println("Created " + count + " threads");
                }
            }
        } catch (OutOfMemoryError e) {
            System.err.println("OOM at thread count=" + count + ": " + e.getMessage());
            // "unable to create native thread: possibly out of memory or process/resource limits exceeded"
        }
    }

    // ── Why does this happen? ─────────────────────────────────────────────────
    // Each thread has a NATIVE STACK (not on JVM heap)
    // Default stack size: 512KB–1MB per thread (OS and JVM dependent)
    // 1000 threads × 1MB = 1GB of native memory just for stacks!
    //
    // Limits:
    //   OS limit: /proc/sys/kernel/threads-max  (Linux)
    //   Process limit: ulimit -u  (Linux: max user processes)
    //   Memory: available native memory for stack allocation
    //   JVM: no built-in thread count limit (unlike connections)

    // ── Calculating max threads (approximate) ────────────────────────────────
    static void estimateMaxThreads() {
        Runtime rt = Runtime.getRuntime();
        long heapFreeBytes  = rt.maxMemory() - rt.totalMemory() + rt.freeMemory();
        long stackSizeBytes = 512 * 1024; // 512KB per thread (typical default)

        // Crude estimate: remaining native memory ≈ physical RAM - heap - metaspace
        System.out.printf("Heap max:          %,d MB%n", rt.maxMemory()     / 1024 / 1024);
        System.out.printf("Heap used:         %,d MB%n",
            (rt.totalMemory() - rt.freeMemory())                            / 1024 / 1024);
        System.out.printf("Heap free:         %,d MB%n", heapFreeBytes      / 1024 / 1024);
        System.out.printf("Stack size/thread: %,d KB%n", stackSizeBytes     / 1024);
        System.out.printf("Thread count now:  %d%n", Thread.activeCount());
    }

    // ── SAFE thread management patterns ──────────────────────────────────────
    static void safeThreadManagement() throws InterruptedException {
        // ✅ Use thread pools — bounded number of threads
        ExecutorService pool = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() * 2 // Sensible upper bound
        );

        // ✅ Java 21: Virtual threads — thousands of tasks, few carrier threads
        ExecutorService virtualPool = Executors.newVirtualThreadPerTaskExecutor();

        // ✅ Limit work queue to prevent unbounded task buildup
        ExecutorService bounded = new ThreadPoolExecutor(
            4,                    // Core threads
            10,                   // Max threads
            60, TimeUnit.SECONDS, // Keep-alive
            new ArrayBlockingQueue<>(100), // Queue limit!
            new ThreadPoolExecutor.CallerRunsPolicy() // Back-pressure: caller executes
        );

        try {
            // Submit tasks safely
            for (int i = 0; i < 20; i++) {
                final int taskId = i;
                bounded.submit(() -> {
                    System.out.println("Task " + taskId + " on " + Thread.currentThread().getName());
                    try { Thread.sleep(100); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                });
            }
        } finally {
            pool.shutdown();
            virtualPool.shutdown();
            bounded.shutdown();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        estimateMaxThreads();
        safeThreadManagement();
    }
}
```

### OS-Level Thread Limit Fixes (Linux)

```bash
# Check current thread limits
cat /proc/sys/kernel/threads-max       # System-wide thread limit
ulimit -u                              # Max processes for current user

# Increase process limit temporarily
ulimit -u 65536

# Permanently (edit /etc/security/limits.conf)
echo "* soft nproc 65536" >> /etc/security/limits.conf
echo "* hard nproc 65536" >> /etc/security/limits.conf

# JVM: reduce stack size to fit more threads
-Xss256k    # 256KB per thread instead of 1MB → 4x more threads possible

# Check how many threads JVM is using
jstack <pid> | grep "java.lang.Thread.State" | wc -l
```

---

## 9. Requested Array Size Exceeds VM Limit

Thrown when trying to allocate an array **larger than the JVM's maximum array size** (approximately `Integer.MAX_VALUE - 5` elements = ~2 billion).

```java
public class ArraySizeOOM {

    // ── The VM limit ──────────────────────────────────────────────────────────
    // Java arrays are indexed by int → max index = Integer.MAX_VALUE = 2,147,483,647
    // JVM reserves a few slots → practical max = Integer.MAX_VALUE - 5 ≈ 2,147,483,642
    // Even if you have 100GB of RAM, you can't have one array larger than this

    // ── Scenario 1: Direct oversized allocation ───────────────────────────────
    static void directOversizeArray() {
        try {
            int[] huge = new int[Integer.MAX_VALUE]; // 8GB for int[], practically impossible
            System.out.println("Array created: " + huge.length);
        } catch (OutOfMemoryError e) {
            System.err.println("OOM: " + e.getMessage());
            // "Requested array size exceeds VM limit"  ← if > MAX_ARRAY_SIZE
            // "Java heap space"                        ← if within limit but no memory
        }
    }

    // ── Scenario 2: Integer overflow causing huge allocation ─────────────────
    static void overflowBug() {
        int rows = 100_000;
        int cols = 100_000;

        try {
            // ❌ rows * cols overflows int! 100000 * 100000 = 10^10 wraps to negative
            int  badSize  = rows * cols;         // Overflows to -727379968!
            System.out.println("Bad size: " + badSize); // Negative number!

            // When used as array size with negative value → NegativeArraySizeException
            // When cast to long first → OutOfMemoryError (10 billion elements)
            long goodSize = (long) rows * cols;
            System.out.println("Good size: " + goodSize + " elements"); // 10,000,000,000

            // ❌ Don't do this — 10 billion ints = 40GB
            // int[][] matrix = new int[rows][cols];

            // ✅ Use a sparse representation or chunked approach
            System.out.println("Use sparse matrix or chunked storage for large 2D data");

        } catch (NegativeArraySizeException e) {
            System.err.println("Negative size: " + e.getMessage());
        }
    }

    // ── Scenario 3: Reading malformed data into array ─────────────────────────
    static byte[] readNetworkPacket(java.io.DataInputStream in) throws java.io.IOException {
        int length = in.readInt(); // Attacker sends: length = 2,000,000,000

        // ❌ No validation → OOM if length is massive
        // byte[] data = new byte[length];

        // ✅ Validate before allocation
        if (length < 0 || length > 10 * 1024 * 1024) { // Max 10MB
            throw new IllegalArgumentException("Packet too large: " + length + " bytes");
        }
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }

    // ── Safe large data handling ──────────────────────────────────────────────
    static void safeLargeDataProcessing() throws java.io.IOException {
        // ✅ Stream large data instead of loading into memory
        java.nio.file.Path path = java.nio.file.Path.of("large-file.csv");

        // Don't do this for large files:
        // byte[] allBytes = Files.readAllBytes(path); // OOM if file > heap

        // ✅ Do this instead:
        try (java.io.BufferedReader reader =
                 java.nio.file.Files.newBufferedReader(path)) {
            reader.lines()
                  .limit(1000) // Process in bounded chunks
                  .forEach(line -> processLine(line));
        } catch (java.io.IOException e) {
            System.err.println("File not found (expected in demo): " + e.getMessage());
        }
    }

    static void processLine(String line) {
        // Process one line at a time — O(1) memory
    }

    public static void main(String[] args) throws java.io.IOException {
        directOversizeArray();
        overflowBug();
        safeLargeDataProcessing();

        // Show max array constant
        System.out.println("Integer.MAX_VALUE: " + Integer.MAX_VALUE);
        System.out.println("Max safe array size ≈ " + (Integer.MAX_VALUE - 5));
    }
}
```

---

## 10. Kill Process or Sacrifice Child

This is not thrown by the JVM itself — it's generated when the **Linux OOM Killer** terminates the JVM process because the OS is critically low on physical memory.

```
Error message in logs:
  # java.lang.OutOfMemoryError: kill process or sacrifice child

This appears in:
  - /var/log/syslog  or  /var/log/kern.log
  - Application logs if the JVM logs it before dying

Linux OOM Killer:
  - Linux kernel monitors physical RAM + swap
  - When critically low: kernel kills the highest "oom_score" process
  - JVMs are often killed because they use a lot of memory
```

```bash
# Check if Linux OOM killer fired
dmesg | grep -i "out of memory"
dmesg | grep -i "oom"
grep -i "oom" /var/log/syslog

# Sample OOM killer log output:
# Out of memory: Kill process 12345 (java) score 892 or sacrifice child
# Killed process 12345 (java) total-vm:4194304kB, anon-rss:3145728kB

# Check OOM score of your JVM process
cat /proc/$(pgrep java)/oom_score

# Protect your JVM from OOM killer (score -1000 = never kill)
echo -1000 > /proc/$(pgrep java)/oom_score_adj  # Root required

# Or set in JVM startup script
echo -17 > /proc/self/oom_adj  # Legacy (deprecated but works)
```

### Mitigation

```bash
# JVM flags: dump heap before dying so you can analyze
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/tmp/heapdump.hprof

# Execute a script on OOM (send alert, restart, etc.)
-XX:OnOutOfMemoryError="kill -9 %p; /path/to/restart-app.sh"

# Infrastructure fix: add more RAM / swap, reduce heap (-Xmx), use swap
# Set proper container limits in Docker/Kubernetes
```

---

## 11. Memory Leaks — Root Causes

A **memory leak** in Java occurs when objects are no longer needed by the application but are still referenced, preventing GC.

```
Memory Leak Checklist:

┌──────────────────────────────────────────────────────────────────┐
│ 1. Static collections (grow unbounded)                           │
│ 2. Caches without eviction policy                                │
│ 3. Event listeners / callbacks never removed                     │
│ 4. ThreadLocal not removed in thread pools                       │
│ 5. Non-static inner classes holding outer reference              │
│ 6. ClassLoader leaks (dynamic class generation)                  │
│ 7. Closeable resources not closed (Streams, DB connections)      │
│ 8. Mutable objects used as Map keys                              │
│ 9. InterruptedException caught without restoring interrupt flag  │
│ 10. Long-lived objects holding short-lived references            │
└──────────────────────────────────────────────────────────────────┘
```

### Leak Patterns with Fixes

```java
import java.util.*;
import java.lang.ref.*;

public class MemoryLeakPatterns {

    // ── Pattern 1: Static unbounded collection ────────────────────────────────
    // ❌ Leak
    static Map<String, byte[]> badCache = new HashMap<>();
    static void addToBadCache(String key) {
        badCache.put(key, new byte[1024 * 100]); // Never evicts
    }

    // ✅ Fixed: LRU-bounded cache
    static Map<String, byte[]> goodCache = new LinkedHashMap<>(200, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) {
            return size() > 100; // Max 100 entries
        }
    };

    // ── Pattern 2: Listener leak ──────────────────────────────────────────────
    interface EventListener { void onEvent(String event); }

    // ❌ Leak: listeners registered but never removed
    static List<EventListener> badListeners = new ArrayList<>();
    static void badRegister(EventListener l) { badListeners.add(l); }
    // No corresponding deregister() method!

    // ✅ Fixed: WeakReference listeners (GC can collect them)
    static List<WeakReference<EventListener>> weakListeners = new ArrayList<>();
    static void goodRegister(EventListener l) {
        weakListeners.add(new WeakReference<>(l));
    }
    static void fireEvent(String event) {
        Iterator<WeakReference<EventListener>> it = weakListeners.iterator();
        while (it.hasNext()) {
            EventListener l = it.next().get();
            if (l == null) {
                it.remove(); // GC'd — clean up dead weak reference
            } else {
                l.onEvent(event);
            }
        }
    }

    // ── Pattern 3: WeakHashMap for caching ────────────────────────────────────
    // Keys are weakly referenced — GC can evict entries when key has no other refs
    static WeakHashMap<Object, byte[]> weakCache = new WeakHashMap<>();

    static void demonstrateWeakHashMap() {
        Object key1 = new Object();
        Object key2 = new Object();

        weakCache.put(key1, new byte[1024 * 1024]); // 1MB
        weakCache.put(key2, new byte[1024 * 1024]); // 1MB

        System.out.println("Cache size before GC: " + weakCache.size()); // 2

        key1 = null; // Remove strong reference to key1

        System.gc(); // Suggest GC
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        System.out.println("Cache size after GC:  " + weakCache.size()); // May be 1 (key1 evicted)
    }

    // ── Pattern 4: SoftReference for memory-sensitive cache ───────────────────
    // Soft references are kept until JVM is LOW on memory — then GC clears them
    static Map<String, SoftReference<byte[]>> softCache = new HashMap<>();

    static byte[] getFromSoftCache(String key) {
        SoftReference<byte[]> ref = softCache.get(key);
        if (ref != null) {
            byte[] value = ref.get(); // Returns null if GC cleared it
            if (value != null) return value;
            softCache.remove(key); // Clean up dead ref
        }
        // Cache miss — load and store
        byte[] loaded = loadExpensiveData(key);
        softCache.put(key, new SoftReference<>(loaded));
        return loaded;
    }

    static byte[] loadExpensiveData(String key) {
        return new byte[1024]; // Simulate loading
    }

    public static void main(String[] args) {
        demonstrateWeakHashMap();

        // SoftReference demo
        for (int i = 0; i < 10; i++) {
            byte[] data = getFromSoftCache("key-" + i);
            System.out.println("Loaded " + data.length + " bytes for key-" + i);
        }
        System.out.println("Soft cache size: " + softCache.size());
    }
}
```

---

## 12. Detecting & Diagnosing OOM

### JVM Flags for Diagnosis

```bash
# ── Heap Dump on OOM ──────────────────────────────────────────────────────────
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/dumps/heapdump-%t.hprof   # %t = timestamp

# ── GC Logging ────────────────────────────────────────────────────────────────
# Java 8:
-verbose:gc
-XX:+PrintGCDetails
-XX:+PrintGCDateStamps
-Xloggc:/var/log/app/gc.log

# Java 9+:
-Xlog:gc*:file=/var/log/app/gc.log:time,level,tags:filecount=5,filesize=20m

# ── On OOM: run a command ─────────────────────────────────────────────────────
-XX:OnOutOfMemoryError="kill -9 %p"
# Or restart:
-XX:OnOutOfMemoryError="/usr/local/bin/restart-app.sh"

# ── Exit on OOM (don't limp along in broken state) ───────────────────────────
-XX:+ExitOnOutOfMemoryError   # Java 8u92+

# ── Native memory tracking ────────────────────────────────────────────────────
-XX:NativeMemoryTracking=summary    # or =detail (more overhead)
# Then: jcmd <pid> VM.native_memory summary
```

---

### Runtime Memory Monitoring with JMX

```java
import java.lang.management.*;
import java.util.*;

public class MemoryMonitor {

    static void printMemoryStatus() {
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();

        MemoryUsage heapUsage    = memBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memBean.getNonHeapMemoryUsage();

        System.out.println("═══ JVM Memory Status ═══");
        printUsage("Heap",     heapUsage);
        printUsage("Non-Heap", nonHeapUsage);

        System.out.println("\n═══ Memory Pools ═══");
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage usage = pool.getUsage();
            if (usage.getMax() > 0) {
                double pct = 100.0 * usage.getUsed() / usage.getMax();
                System.out.printf("  %-28s used=%5dMB / max=%5dMB (%5.1f%%)%n",
                    pool.getName(),
                    usage.getUsed()  / 1024 / 1024,
                    usage.getMax()   / 1024 / 1024,
                    pct);

                if (pct > 90) {
                    System.out.printf("  ⚠ WARNING: %s is %.1f%% full!%n", pool.getName(), pct);
                }
            }
        }

        System.out.println("\n═══ GC Stats ═══");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            System.out.printf("  %-20s count=%-8d time=%dms%n",
                gc.getName(), gc.getCollectionCount(), gc.getCollectionTime());
        }
    }

    static void printUsage(String name, MemoryUsage usage) {
        System.out.printf("  %-10s init=%,dMB  used=%,dMB  committed=%,dMB  max=%s%n",
            name,
            usage.getInit()      / 1024 / 1024,
            usage.getUsed()      / 1024 / 1024,
            usage.getCommitted() / 1024 / 1024,
            usage.getMax() < 0 ? "unlimited" : usage.getMax() / 1024 / 1024 + "MB"
        );
    }

    // ── Memory threshold notification ─────────────────────────────────────────
    static void setupMemoryAlert(double thresholdPercent) {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.isUsageThresholdSupported() && pool.getUsage().getMax() > 0) {
                long threshold = (long)(pool.getUsage().getMax() * thresholdPercent / 100);
                pool.setUsageThreshold(threshold);
                System.out.println("Alert set: " + pool.getName()
                    + " at " + thresholdPercent + "% (" + threshold/1024/1024 + "MB)");
            }
        }
    }

    // ── Runtime memory statistics ──────────────────────────────────────────────
    static void runtimeStats() {
        Runtime rt = Runtime.getRuntime();
        long maxMemory   = rt.maxMemory();
        long totalMemory = rt.totalMemory();
        long freeMemory  = rt.freeMemory();
        long usedMemory  = totalMemory - freeMemory;

        System.out.printf("%n═══ Runtime Memory ═══%n");
        System.out.printf("  Max (Xmx):   %,d MB%n", maxMemory   / 1024 / 1024);
        System.out.printf("  Committed:   %,d MB%n", totalMemory / 1024 / 1024);
        System.out.printf("  Used:        %,d MB%n", usedMemory  / 1024 / 1024);
        System.out.printf("  Free:        %,d MB%n", freeMemory  / 1024 / 1024);
        System.out.printf("  Available:   %,d MB%n", (maxMemory - usedMemory) / 1024 / 1024);
        System.out.printf("  CPU cores:   %d%n",     rt.availableProcessors());
    }

    public static void main(String[] args) throws InterruptedException {
        printMemoryStatus();
        runtimeStats();
        setupMemoryAlert(80.0); // Alert at 80% usage
    }
}
```

---

### Command-Line Diagnostic Tools

```bash
# ── jps: list Java processes ──────────────────────────────────────────────────
jps -lv

# ── jmap: heap info and heap dump ────────────────────────────────────────────
jmap -heap <pid>                            # Heap summary
jmap -histo:live <pid>                      # Object histogram (forces GC first)
jmap -histo <pid> | head -30               # Top 30 object types by count
jmap -dump:format=b,file=heap.hprof <pid>   # Generate heap dump manually

# ── jstat: GC statistics ──────────────────────────────────────────────────────
jstat -gc <pid> 1000 10       # GC stats every 1000ms, 10 times
jstat -gcutil <pid> 1000      # GC utilization percentages
jstat -gccause <pid> 1000     # Last GC cause

# ── jcmd: all-in-one (Java 7+) ───────────────────────────────────────────────
jcmd <pid> VM.flags                         # JVM flags
jcmd <pid> GC.heap_info                     # Heap summary
jcmd <pid> GC.run                           # Force GC
jcmd <pid> Thread.print                     # All thread stacks
jcmd <pid> VM.native_memory summary        # Native memory usage
jcmd <pid> GC.heap_dump /tmp/dump.hprof    # Heap dump

# ── jstack: thread dumps ──────────────────────────────────────────────────────
jstack <pid>                                # All thread stacks (deadlock detection)
jstack -l <pid>                             # With lock info

# ── jconsole / jvisualvm: GUI tools ──────────────────────────────────────────
jconsole                    # Built-in JMX console
jvisualvm                   # Advanced profiler (may need separate install)
```

---

## 13. Heap Dumps — Analysis

A **heap dump** is a snapshot of all objects in the JVM heap at a specific moment. It's the most powerful tool for diagnosing memory leaks.

```bash
# Generate heap dump:
# 1. Automatically on OOM (add to JVM flags):
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/

# 2. Manually while running:
jmap -dump:live,format=b,file=heap.hprof <pid>
jcmd <pid> GC.heap_dump /tmp/heap.hprof

# 3. From within Java:
```

```java
import java.lang.management.*;
import com.sun.management.HotSpotDiagnosticMXBean;

public class HeapDumpUtil {

    // Programmatically trigger heap dump (requires HotSpot JVM)
    static void dumpHeap(String filePath, boolean liveObjectsOnly) throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        HotSpotDiagnosticMXBean diagBean = ManagementFactory.newPlatformMXBeanProxy(
            server,
            "com.sun.management:type=HotSpotDiagnostic",
            HotSpotDiagnosticMXBean.class
        );
        diagBean.dumpHeap(filePath, liveObjectsOnly);
        System.out.println("Heap dump written to: " + filePath);
    }

    public static void main(String[] args) throws Exception {
        // Create some objects for the dump
        List<String> data = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            data.add("Sample string " + i);
        }

        dumpHeap("/tmp/app-heap.hprof", true); // true = live objects only
    }
}
```

### Analyzing with Eclipse MAT (Memory Analyzer Tool)

```
Download MAT: https://eclipse.dev/mat/

Key analyses in MAT:

1. OVERVIEW: Total heap size, leak suspects report
   → File → Open Heap Dump → Overview

2. HISTOGRAM: Objects by class
   → Window → Heap Dump Details → Histogram
   → Sort by "Retained Heap" (total memory held by class)
   → Look for: unexpected large classes, internal arrays growing large

3. DOMINATOR TREE: Objects retaining the most memory
   → Window → Heap Dump Details → Dominator Tree
   → Shows the "biggest" objects keeping memory alive

4. LEAK SUSPECTS: Automatic leak detection
   → File → Open Heap Dump → Run Leak Suspects Report
   → Shows accumulation points with object chains

5. OQL (Object Query Language):
   SELECT * FROM java.util.ArrayList WHERE size > 100000
   SELECT * FROM java.lang.String s WHERE s.count > 10000
   SELECT OBJECTS a FROM int[] a WHERE a.@length > 1000000

6. Retained vs Shallow heap:
   Shallow heap = memory of the object itself (without referenced objects)
   Retained heap = memory freed if object + all exclusive refs were GC'd
   → High retained heap = this object is holding a lot of memory hostage
```

---

## 14. JVM Tuning Flags

### Heap Size Flags

```bash
# ── Basic heap sizing ─────────────────────────────────────────────────────────
-Xms512m              # Initial heap size (min)
-Xmx4g                # Maximum heap size (CRITICAL — set this always!)
-Xmn256m              # Young generation size (or use -XX:NewRatio)
-XX:NewRatio=2         # Old:Young ratio = 2:1 (Young = 1/3 of heap)
-XX:SurvivorRatio=8    # Eden:Survivor ratio within Young gen

# ── Best practice: set Xms = Xmx ─────────────────────────────────────────────
# Prevents JVM from repeatedly growing the heap (avoids GC pauses on resize)
-Xms4g -Xmx4g

# ── Container-aware (Java 10+) ────────────────────────────────────────────────
-XX:+UseContainerSupport            # Respect Docker/k8s memory limits
-XX:MaxRAMPercentage=75.0           # Use 75% of container RAM as max heap
-XX:InitialRAMPercentage=50.0       # Start at 50% of container RAM

# ── Metaspace ─────────────────────────────────────────────────────────────────
-XX:MetaspaceSize=128m              # Initial metaspace (avoids early resize)
-XX:MaxMetaspaceSize=256m           # Cap metaspace (prevent unbounded growth)

# ── Direct memory ─────────────────────────────────────────────────────────────
-XX:MaxDirectMemorySize=512m        # Cap off-heap direct buffers

# ── Thread stacks ─────────────────────────────────────────────────────────────
-Xss512k                            # Stack size per thread (reduce for more threads)

# ── GC tuning ─────────────────────────────────────────────────────────────────
-XX:+UseG1GC                        # G1GC (default Java 9+)
-XX:MaxGCPauseMillis=200            # Target max GC pause
-XX:G1HeapRegionSize=16m            # G1 region size (1MB–32MB, power of 2)

-XX:+UseZGC                         # ZGC (Java 15+ production, sub-millisecond pauses)
-XX:+UseShenandoahGC               # Shenandoah (low-latency alternative)

# ── Diagnostic flags ──────────────────────────────────────────────────────────
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/dumps/
-XX:+ExitOnOutOfMemoryError
-XX:OnOutOfMemoryError="kill -9 %p"
-Xlog:gc*:file=/var/log/gc.log:time:filecount=5,filesize=10m
```

---

### JVM Memory Tuning for Common Scenarios

```bash
# ── Web Application (Tomcat/Spring Boot) ─────────────────────────────────────
JAVA_OPTS="\
  -Xms1g -Xmx2g \
  -XX:MetaspaceSize=128m \
  -XX:MaxMetaspaceSize=256m \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/dumps/ \
  -XX:+ExitOnOutOfMemoryError \
  -Xlog:gc*:file=/var/log/gc.log:time"

# ── Microservice (small container) ────────────────────────────────────────────
JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:+ExitOnOutOfMemoryError"

# ── Batch Processing (large data) ─────────────────────────────────────────────
JAVA_OPTS="\
  -Xms4g -Xmx8g \
  -XX:NewRatio=3 \
  -XX:+UseG1GC \
  -XX:G1HeapRegionSize=32m \
  -XX:+HeapDumpOnOutOfMemoryError"

# ── Low-latency Service (trading, gaming) ─────────────────────────────────────
JAVA_OPTS="\
  -Xms4g -Xmx4g \
  -XX:+UseZGC \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:+ExitOnOutOfMemoryError"
```

---

## 15. Prevention Best Practices

### 1. Always Set `-Xmx`

```bash
# ❌ No -Xmx: JVM uses default (25% of physical RAM or 256MB)
# Leads to: either OOM too early, or JVM consuming all RAM

# ✅ Always set -Xmx explicitly
java -Xmx2g -jar myapp.jar
```

---

### 2. Use Bounded Collections

```java
// ❌ Unbounded growth
Map<String, Object> cache = new HashMap<>();
cache.put(key, value); // Grows forever

// ✅ Bounded with eviction
Map<String, Object> boundedCache = Collections.synchronizedMap(
    new LinkedHashMap<>(1000, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object> e) {
            return size() > 1000;
        }
    }
);

// ✅ Or use Guava Cache / Caffeine
// Cache<String, Object> cache = Caffeine.newBuilder()
//     .maximumSize(1000)
//     .expireAfterWrite(10, TimeUnit.MINUTES)
//     .build();
```

---

### 3. Stream Large Files, Don't Load Them

```java
import java.nio.file.*;
import java.util.stream.*;

// ❌ OOM for large files
byte[] allBytes = Files.readAllBytes(Path.of("huge-file.csv")); // OOM if > Xmx

// ❌ Loads all lines into memory
List<String> allLines = Files.readAllLines(Path.of("huge-file.csv")); // OOM

// ✅ Stream line by line — O(1) memory
try (Stream<String> lines = Files.lines(Path.of("huge-file.csv"))) {
    lines
        .filter(line -> line.startsWith("IMPORTANT:"))
        .limit(100)
        .forEach(System.out::println);
} // Stream closed automatically
```

---

### 4. Release Resources Explicitly

```java
// ❌ Resource leak
Connection conn = dataSource.getConnection();
Statement stmt = conn.createStatement();
ResultSet rs = stmt.executeQuery("SELECT * FROM users");
// ... if exception here: conn/stmt/rs never closed!

// ✅ try-with-resources — always closed
try (Connection conn   = dataSource.getConnection();
     Statement  stmt   = conn.createStatement();
     ResultSet  rs     = stmt.executeQuery("SELECT * FROM users")) {
    while (rs.next()) {
        processRow(rs);
    }
}
```

---

### 5. Use Pagination for Large Queries

```java
// ❌ Loads entire table into memory
List<User> allUsers = userRepository.findAll(); // Could be millions!

// ✅ Paginate
int page = 0, pageSize = 1000;
List<User> batch;
do {
    batch = userRepository.findAll(PageRequest.of(page++, pageSize));
    processBatch(batch);
    batch.clear(); // Hint GC
} while (!batch.isEmpty());

// ✅ Or use database cursors / streaming
userRepository.streamAll().forEach(user -> {
    processUser(user);
    // Each user is eligible for GC after this lambda returns
});
```

---

### 6. Use String.format() Carefully with Large Data

```java
// ❌ Building huge strings in memory
StringBuilder sb = new StringBuilder();
for (int i = 0; i < 1_000_000; i++) {
    sb.append("Line ").append(i).append(": ").append(generateData()).append("\n");
}
String result = sb.toString(); // One massive String object in heap!

// ✅ Write directly to output stream
try (PrintWriter writer = new PrintWriter(new FileWriter("output.txt"))) {
    for (int i = 0; i < 1_000_000; i++) {
        writer.println("Line " + i + ": " + generateData());
        // Each line written to disk, not accumulated in memory
    }
}
```

---

### 7. Weak and Soft References

```java
import java.lang.ref.*;

// WeakReference — GC can collect at any time when no strong refs exist
WeakReference<HeavyObject> weakRef = new WeakReference<>(new HeavyObject());
HeavyObject obj = weakRef.get(); // null if GC'd
if (obj != null) {
    obj.doWork(); // Safe to use
}

// SoftReference — GC collects only when JVM is LOW on memory
// Perfect for memory-sensitive caches
SoftReference<byte[]> cache = new SoftReference<>(loadLargeData());
byte[] data = cache.get(); // null only when JVM nearly OOM
if (data == null) {
    data = loadLargeData(); // Cache miss — reload
    cache = new SoftReference<>(data);
}
```

---

## 16. Garbage Collectors Overview

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  GC           When to Use          Pause     Throughput  Java Version        │
├──────────────────────────────────────────────────────────────────────────────┤
│  Serial       Single-core, small   Long      Low         All                 │
│               heap, CLI apps                                                 │
│                                                                              │
│  Parallel     Batch/throughput     Medium    High        All (Java 8 def.)  │
│  (Throughput) CPU-bound work                             (-XX:+UseParallelGC)│
│                                                                              │
│  G1GC         General purpose      Short     Good        Java 9+ (default)  │
│               Mixed workloads      (<200ms)              (-XX:+UseG1GC)      │
│               Heap 4GB–50GB                                                  │
│                                                                              │
│  ZGC          Ultra-low latency    <1ms      Good        Java 15+ prod       │
│               Very large heaps     (sub-ms)              (-XX:+UseZGC)       │
│               Heap > 100GB                                                   │
│                                                                              │
│  Shenandoah   Low-latency,        <10ms     Good        Java 12+ (RedHat)   │
│               large heaps                               (-XX:+UseShenandoahGC)│
└──────────────────────────────────────────────────────────────────────────────┘
```

```java
public class GCInfoDemo {
    public static void main(String[] args) {

        // Print active GC
        java.lang.management.ManagementFactory
            .getGarbageCollectorMXBeans()
            .forEach(gc -> System.out.println(
                "GC: " + gc.getName() +
                " | pools: " + Arrays.toString(gc.getMemoryPoolNames())
            ));

        // Suggest GC (not guaranteed to run)
        System.out.println("\nBefore GC:");
        Runtime rt = Runtime.getRuntime();
        System.out.println("Used: " + (rt.totalMemory()-rt.freeMemory())/1024/1024 + "MB");

        System.gc(); // Just a hint!

        System.out.println("After GC:");
        System.out.println("Used: " + (rt.totalMemory()-rt.freeMemory())/1024/1024 + "MB");
    }
}
```

---

## 17. Real-World OOM Scenarios & Fixes

### Scenario 1: Spring Boot App Leaking Session Data

```java
// ❌ Problem: storing large objects in HTTP session indefinitely
@Controller
public class ReportController {

    @GetMapping("/report")
    public String generateReport(HttpSession session) {
        // 50MB report stored in session — never expires
        byte[] reportData = generateLargeReport();
        session.setAttribute("report", reportData);  // LEAK!
        return "report-view";
    }
}

// ✅ Fix: don't store large data in session; use temp files or cache with TTL
@Controller
public class ReportControllerFixed {

    @GetMapping("/report")
    public ResponseEntity<byte[]> generateReport() {
        byte[] reportData = generateLargeReport();
        // Return directly — not stored in session
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=report.pdf")
            .body(reportData);
        // reportData eligible for GC after response sent
    }
}
```

---

### Scenario 2: Hibernate N+1 Loading All Records

```java
// ❌ Problem: Hibernate loads 1 million users + all their orders
@Service
public class UserService {
    @Autowired UserRepository repo;

    public void processAllUsers() {
        List<User> allUsers = repo.findAll(); // 1M users with EAGER orders loaded!
        // 1M users × 50 orders × ~1KB = 50GB in memory → OOM
        allUsers.forEach(this::processUser);
    }
}

// ✅ Fix: Use streaming/pagination
@Service
public class UserServiceFixed {
    @Autowired UserRepository repo;

    @Transactional(readOnly = true)
    public void processAllUsers() {
        // Stream: processes one at a time, eligible for GC per iteration
        try (Stream<User> stream = repo.streamAll()) {
            stream.forEach(user -> {
                processUser(user);
                // EntityManager.detach(user) if you're not updating it
            });
        }
    }

    public void processUsersBatched() {
        int page = 0, size = 500;
        Page<User> batch;
        do {
            batch = repo.findAll(PageRequest.of(page++, size));
            batch.getContent().forEach(this::processUser);
            // Hibernate session cleared between pages
        } while (batch.hasNext());
    }
}
```

---

### Scenario 3: Connection Pool Starvation (Not OOM, but Similar Symptoms)

```java
// ❌ Connections not closed → pool exhausted → app hangs
public List<User> getUsers() throws SQLException {
    Connection conn = dataSource.getConnection(); // Gets from pool
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT * FROM users");
    List<User> users = new ArrayList<>();
    while (rs.next()) {
        users.add(mapUser(rs));
    }
    // ❌ Missing: conn.close() → connection never returned to pool!
    return users; // Pool exhausted after N calls
}

// ✅ Always use try-with-resources
public List<User> getUsersFixed() throws SQLException {
    List<User> users = new ArrayList<>();
    try (Connection conn = dataSource.getConnection();
         Statement  stmt = conn.createStatement();
         ResultSet  rs   = stmt.executeQuery("SELECT * FROM users")) {
        while (rs.next()) {
            users.add(mapUser(rs));
        }
    } // All three closed automatically
    return users;
}
```

---

### Scenario 4: Log4j / SLF4J Causing OOM (String Concatenation)

```java
// ❌ String built even when debug logging is OFF
log.debug("Processing user: " + user.toDetailedString()); // Always builds the string!
// user.toDetailedString() might be expensive and return MB of data

// ✅ Lambda-deferred evaluation — only runs if DEBUG is enabled
log.debug("Processing user: {}", user::toDetailedString); // SLF4J supplier
log.debug(() -> "Processing user: " + user.toDetailedString()); // Log4j2 lambda

// ✅ Or check level first
if (log.isDebugEnabled()) {
    log.debug("Processing user: " + user.toDetailedString());
}
```

---

## 18. Monitoring & Alerting

### Prometheus + Micrometer (Spring Boot)

```java
// application.properties
// management.endpoints.web.exposure.include=prometheus,health,metrics
// management.metrics.export.prometheus.enabled=true

// Add to pom.xml:
// <dependency>
//   <groupId>io.micrometer</groupId>
//   <artifactId>micrometer-registry-prometheus</artifactId>
// </dependency>

// Key metrics exposed at /actuator/prometheus:
// jvm_memory_used_bytes{area="heap"}
// jvm_memory_max_bytes{area="heap"}
// jvm_gc_pause_seconds_sum
// jvm_gc_pause_seconds_count
// jvm_buffer_memory_used_bytes{id="direct"}
```

```yaml
# Grafana Alert Rule (example)
# Alert: Heap usage > 85% for 5 minutes
- alert: JvmHeapHigh
  expr: (jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}) > 0.85
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "JVM Heap usage high on {{ $labels.instance }}"
    description: "Heap is {{ $value | humanizePercentage }} full"

# Alert: OOM occurred
- alert: JvmOomDetected
  expr: increase(jvm_gc_pause_seconds_count{cause="Allocation Failure"}[5m]) > 10
  for: 1m
  labels:
    severity: critical
```

---

## 19. Interview Questions & Answers

| # | Question | Answer |
|---|----------|--------|
| 1 | What is `OutOfMemoryError`? | A subclass of `Error` (not `Exception`) thrown by the JVM when it cannot allocate an object even after GC. Signals a fatal JVM-level condition. |
| 2 | What are the different OOM types? | Heap Space, GC Overhead Limit Exceeded, Metaspace, Direct Buffer Memory, Unable to Create Native Thread, Requested Array Size Exceeds VM Limit, Kill Process or Sacrifice Child. |
| 3 | Should you catch `OutOfMemoryError`? | Generally no. Only at the highest level for graceful shutdown logging. The JVM may be in an unstable state after OOM. |
| 4 | What is the difference between `PermGen` and `Metaspace`? | PermGen (Java 7-) was a fixed-size heap region for class metadata. Metaspace (Java 8+) uses native memory and grows dynamically by default. No more `PermGen OOM` — instead you get `Metaspace OOM` if you set `-XX:MaxMetaspaceSize`. |
| 5 | What causes "GC overhead limit exceeded"? | JVM spends >98% of CPU time doing GC but recovers <2% of heap in multiple consecutive GC cycles. Indicates the heap is nearly full and GC is ineffective. |
| 6 | What is a memory leak in Java? | Objects that are no longer needed by the application but still referenced — preventing GC. Common causes: static collections, listener leaks, ThreadLocal, inner class references. |
| 7 | How do you detect a memory leak? | Enable heap dump on OOM (`-XX:+HeapDumpOnOutOfMemoryError`), analyze with Eclipse MAT (dominator tree, leak suspects), monitor heap growth with JMX/jstat. |
| 8 | What is a heap dump and how do you analyze it? | A snapshot of all JVM heap objects. Generated via jmap, jcmd, or automatically on OOM. Analyzed with Eclipse MAT — look at dominator tree, histogram, leak suspects. |
| 9 | Difference between `WeakReference` and `SoftReference`? | `WeakReference`: GC collects as soon as no strong refs exist (good for canonicalization maps). `SoftReference`: GC collects only when JVM is low on memory (good for memory-sensitive caches). |
| 10 | What does `-XX:+HeapDumpOnOutOfMemoryError` do? | Tells the JVM to automatically write a heap dump file when OOM occurs. Essential for production diagnosis. Combine with `-XX:HeapDumpPath` to specify location. |
| 11 | What is "unable to create native thread" OOM? | Heap may be fine — the OS cannot create more threads due to system-wide thread limits (`ulimit -u`) or not enough native memory for thread stacks. Fix: reduce stack size (`-Xss`), use virtual threads, or increase OS limits. |
| 12 | Why does `ThreadLocal` cause memory leaks? | In thread pools, threads are reused. If `ThreadLocal.remove()` is not called, the value (potentially large) remains associated with the thread forever. Always call `remove()` in a `finally` block. |
| 13 | How does `-XX:MaxRAMPercentage` help in containers? | In Docker/Kubernetes, `-XX:MaxRAMPercentage=75.0` tells the JVM to use 75% of the container's memory limit as heap max. Without this, the JVM doesn't see container limits and may OOM the container. |
| 14 | What is the difference between direct and heap buffers? | Heap buffers: allocated on JVM heap, GC managed. Direct buffers: allocated off-heap in native memory, faster for I/O (zero-copy), freed by Cleaner (not directly by GC). Use `-XX:MaxDirectMemorySize` to limit. |
| 15 | How do you tune GC to reduce OOM risk? | Choose appropriate GC (`-XX:+UseG1GC` for balanced, `-XX:+UseZGC` for low-latency), set `MaxGCPauseMillis`, increase heap (`-Xmx`), tune `NewRatio` for your object lifetime patterns. |
| 16 | What is `String.intern()` and can it cause OOM? | Intern returns a canonical string from the string pool. Interning many unique strings fills the string pool (heap in Java 7+). Avoid interning dynamic/unique strings. |
| 17 | What JVM flag exits the JVM on OOM? | `-XX:+ExitOnOutOfMemoryError` (Java 8u92+). Better than limping along in a broken state. Combine with a process manager (systemd, Kubernetes) to restart automatically. |
| 18 | How do you handle OOM in a multithreaded application? | Set an `UncaughtExceptionHandler` on threads. For thread pools, submit tasks via `Future` and catch `ExecutionException`. Always log and consider a controlled restart. |
| 19 | What is native memory tracking? | Enable with `-XX:NativeMemoryTracking=summary`. Use `jcmd <pid> VM.native_memory summary` to see breakdown: Java Heap, Class (Metaspace), Code, Thread, Internal, Other. Helps diagnose non-heap memory growth. |
| 20 | How do you prevent OOM in batch processing? | Use pagination (process N records at a time), streaming (line by line), clear Hibernate session periodically, avoid loading full dataset into memory, use bounded queues for parallelism. |

---

## 20. Complete Reference Summary

### OOM Type Quick Reference

```
OOM Message                          → Region       → Common Cause
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
"Java heap space"                    → Heap         → Memory leak, heap too small
"GC overhead limit exceeded"         → Heap         → Heap nearly full, GC spinning
"Metaspace"                          → Metaspace    → Class loader leak, dynamic proxies
"Direct buffer memory"               → Off-heap     → NIO buffer not released
"unable to create native thread"     → Native/Stack → Too many threads, OS limits
"Requested array size exceeds VM limit" → Heap      → Array > Integer.MAX_VALUE
"kill process or sacrifice child"    → OS           → Linux OOM killer
```

### Most Important JVM Flags

```bash
# Sizing (always set these)
-Xms<size> -Xmx<size>                    # Heap bounds
-XX:MaxMetaspaceSize=<size>              # Metaspace cap
-XX:MaxDirectMemorySize=<size>           # Direct buffer cap
-Xss<size>                              # Thread stack size

# Container (Java 10+)
-XX:+UseContainerSupport
-XX:MaxRAMPercentage=75.0

# Diagnosis
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/path/to/dumps/
-XX:+ExitOnOutOfMemoryError
-Xlog:gc*:file=/path/to/gc.log:time

# GC choice
-XX:+UseG1GC          # Balanced (default Java 9+)
-XX:+UseZGC           # Ultra-low latency (Java 15+)
```

### OOM Prevention Checklist

```
□ Set -Xmx explicitly (never run without it)
□ Set -XX:+HeapDumpOnOutOfMemoryError
□ Set -XX:MaxMetaspaceSize for dynamic class apps
□ Use bounded caches (LinkedHashMap with removeEldestEntry)
□ Always close resources (try-with-resources)
□ Remove ThreadLocal in finally blocks
□ Paginate or stream large data sets
□ Use static nested classes instead of inner classes
□ Use WeakReference / SoftReference for optional caches
□ Set MaxRAMPercentage when running in containers
□ Monitor heap and GC metrics in production
□ Test with realistic data volumes in staging
□ Use pagination in ORM queries (never findAll() on large tables)
□ Avoid String concatenation in hot loops (use StringBuilder)
□ Limit thread creation (thread pools, virtual threads)
```

---

```
JVM Memory Full Architecture
│
├── Heap (controlled by -Xms/-Xmx)
│   ├── Young Generation
│   │   ├── Eden Space        ← New objects born here
│   │   ├── Survivor 0 (S0)  ← Survived 1+ minor GC
│   │   └── Survivor 1 (S1)  ← Survived 1+ minor GC
│   └── Old Generation        ← Long-lived objects
│       (OOM: "Java heap space")
│
├── Metaspace (-XX:MaxMetaspaceSize)
│   ├── Class definitions
│   ├── Method bytecode
│   └── JIT code cache
│       (OOM: "Metaspace")
│
├── Direct Buffer Memory (-XX:MaxDirectMemorySize)
│   └── ByteBuffer.allocateDirect()
│       (OOM: "Direct buffer memory")
│
├── Thread Stacks (-Xss per thread)
│   └── One stack per thread
│       (OOM: "unable to create native thread")
│
└── OS Native Memory
    └── JVM internals, GC bookkeeping
        (OOM: "kill process or sacrifice child" via Linux OOM killer)
```

---

*Made with ❤️ for Java developers — covers Java 8 through Java 21*
