# ☕ Java Garbage Collection Tuning — Deep Dive Complete Guide

> JVM GC internals, all collectors, tuning flags, and production patterns — Java 8 through Java 21

---

## 📌 Table of Contents

1. [What is Garbage Collection?](#1-what-is-garbage-collection)
2. [GC Roots & Object Reachability](#2-gc-roots--object-reachability)
3. [Heap Structure & Generational GC](#3-heap-structure--generational-gc)
4. [GC Algorithms — Core Concepts](#4-gc-algorithms--core-concepts)
5. [Serial GC](#5-serial-gc)
6. [Parallel GC (Throughput Collector)](#6-parallel-gc-throughput-collector)
7. [CMS GC (Concurrent Mark Sweep) — Legacy](#7-cms-gc-concurrent-mark-sweep--legacy)
8. [G1GC — Garbage First (Default Java 9+)](#8-g1gc--garbage-first-default-java-9)
9. [ZGC — Ultra Low Latency (Java 15+)](#9-zgc--ultra-low-latency-java-15)
10. [Shenandoah GC](#10-shenandoah-gc)
11. [GC Log Analysis](#11-gc-log-analysis)
12. [GC Tuning Flags — Complete Reference](#12-gc-tuning-flags--complete-reference)
13. [Object Allocation & Escape Analysis](#13-object-allocation--escape-analysis)
14. [GC Safepoints & Stop-the-World](#14-gc-safepoints--stop-the-world)
15. [Memory Monitoring with JMX/MBeans](#15-memory-monitoring-with-jmxmbeans)
16. [Tuning for Specific Scenarios](#16-tuning-for-specific-scenarios)
17. [GC Performance Benchmarks](#17-gc-performance-benchmarks)
18. [Common GC Problems & Fixes](#18-common-gc-problems--fixes)
19. [Interview Questions & Answers](#19-interview-questions--answers)
20. [Complete Reference Summary](#20-complete-reference-summary)

---

## 1. What is Garbage Collection?

**Garbage Collection (GC)** is the process by which the JVM automatically identifies and reclaims memory occupied by objects that are no longer reachable by the running program. Java developers do not manually `free()` memory — the GC does it automatically.

```
Manual Memory (C/C++):                Java GC:
  int* p = malloc(sizeof(int));         Object obj = new Object();
  *p = 42;                              obj.doWork();
  free(p);   ← Developer's job         obj = null;  ← Just remove reference
                                        // GC collects it when convenient
```

### The GC Trade-off Triangle

```
                    ┌─────────────────┐
                    │   THROUGHPUT    │
                    │  (work per sec) │
                    └────────┬────────┘
                             │
              You can only   │   optimize
              TWO of THREE   │   at a time
                    ┌────────┴────────┐
                    │                 │
           ┌────────┴───┐       ┌─────┴──────────┐
           │  LATENCY   │       │  FOOTPRINT     │
           │ (pause ms) │       │  (heap size)   │
           └────────────┘       └────────────────┘

  High Throughput  → Parallel GC, large heap, longer pauses OK
  Low Latency      → ZGC, G1GC, sub-ms pauses, some throughput cost
  Small Footprint  → Serial GC, small heap, single CPU
```

### GC Responsibilities

```java
public class GCResponsibilities {
    public static void main(String[] args) {

        // GC tracks all of these automatically:

        // 1. Short-lived objects (most objects die young)
        for (int i = 0; i < 1_000_000; i++) {
            String temp = "temp-" + i;  // Created then immediately unreachable
            // No explicit free needed — GC handles it
        }

        // 2. Long-lived objects (promoted to old gen)
        List<String> persistent = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            persistent.add("item-" + i); // Stays alive as long as 'persistent' is reachable
        }

        // 3. Circular references (GC handles; ref-counting wouldn't)
        class Node { Node next; }
        Node a = new Node();
        Node b = new Node();
        a.next = b;
        b.next = a;  // Circular reference
        a = null;
        b = null;
        // Both a and b are unreachable — GC collects despite circular ref
        // (Reference counting would FAIL here — Java GC does not)

        System.out.println("GC managed all memory automatically");
    }
}
```

---

## 2. GC Roots & Object Reachability

The GC starts from **GC Roots** — a set of always-reachable references — and traces all objects reachable from them. Unreachable objects are collected.

### What Are GC Roots?

```
GC Roots (always considered reachable):
┌────────────────────────────────────────────────────────────┐
│  1. Active thread stacks (local variables in stack frames) │
│  2. Static fields of loaded classes                        │
│  3. JNI references (native method handles)                 │
│  4. Synchronized monitors (objects held by synchronized)   │
│  5. JVM internal references (system class loader, etc.)    │
└────────────────────────────────────────────────────────────┘
```

### Reachability Tracing — The Mark Phase

```
GC Root: main() stack frame
    │
    ├── List<Order> orders ──────────────────────────────── REACHABLE ✅
    │       │
    │       ├── Order[0] ──────────────────────────────── REACHABLE ✅
    │       │       └── Customer customer ─────────────── REACHABLE ✅
    │       │               └── Address address ──────── REACHABLE ✅
    │       └── Order[1] ──────────────────────────────── REACHABLE ✅
    │
    └── String config ──────────────────────────────────── REACHABLE ✅

Orphaned objects (not reachable from any GC root):
    ╳── TempBuffer buffer ────────────────────────────── UNREACHABLE ❌ → COLLECTED
    ╳── OldSession session ───────────────────────────── UNREACHABLE ❌ → COLLECTED
```

### Reference Types & GC Behavior

```java
import java.lang.ref.*;

public class ReferenceTypesDemo {

    record HeavyObject(String name, byte[] data) {
        HeavyObject(String name) {
            this(name, new byte[1024 * 1024]); // 1MB
        }
    }

    public static void main(String[] args) throws InterruptedException {

        // ── Strong Reference — default, never collected while reachable ───────
        HeavyObject strong = new HeavyObject("strong");
        // strong is collected only when strong = null AND no other strong refs

        // ── Soft Reference — collected only when JVM is LOW on memory ─────────
        SoftReference<HeavyObject> soft =
            new SoftReference<>(new HeavyObject("soft"));
        // The HeavyObject may still be alive:
        HeavyObject softObj = soft.get(); // null if GC cleared it
        System.out.println("Soft obj alive: " + (softObj != null));
        // Use for: memory-sensitive caches (cleared before OOM)

        // ── Weak Reference — collected at next GC regardless ──────────────────
        WeakReference<HeavyObject> weak =
            new WeakReference<>(new HeavyObject("weak"));
        System.out.println("Before GC: " + (weak.get() != null)); // true
        System.gc();
        Thread.sleep(100);
        System.out.println("After GC:  " + (weak.get() != null)); // likely false
        // Use for: canonicalization maps, WeakHashMap keys

        // ── Phantom Reference — enqueued AFTER object is finalized ────────────
        ReferenceQueue<HeavyObject> queue = new ReferenceQueue<>();
        PhantomReference<HeavyObject> phantom =
            new PhantomReference<>(new HeavyObject("phantom"), queue);
        System.out.println("Phantom.get() always null: " + phantom.get()); // null always
        // Use for: post-GC cleanup actions (replacing finalize())

        // ── WeakHashMap — entries evicted when key has no strong refs ─────────
        java.util.WeakHashMap<Object, String> weakMap = new java.util.WeakHashMap<>();
        Object key = new Object();
        weakMap.put(key, "value");
        System.out.println("Map size before: " + weakMap.size()); // 1
        key = null; // Remove strong reference
        System.gc();
        Thread.sleep(100);
        System.out.println("Map size after GC: " + weakMap.size()); // 0 (key collected)

        // Reference strength summary:
        System.out.println("""
            Strong  → Never GC'd while reachable
            Soft    → GC'd only when JVM needs memory (caches)
            Weak    → GC'd at next GC cycle (WeakHashMap keys)
            Phantom → GC'd, then enqueued for post-mortem cleanup
            """);
    }
}
```

---

## 3. Heap Structure & Generational GC

### The Generational Hypothesis

```
"Most objects die young"

Object survival rate over time:
100% ████████████████████████████████  ← Just allocated
 80% ████████████████████
 40% ████████
 10% ████                              ← After a few GC cycles
  2% █                                 ← Long-lived (e.g. caches, singletons)
  1% █                                 ← Permanent (static fields)
     ────────────────────────────────►
     0   1   2   3   4   5  GC cycles

→ Separate objects by age → collect young gen frequently (cheap)
→ Rarely collect old gen (expensive but infrequent)
```

### Heap Layout

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        JVM HEAP  (-Xms / -Xmx)                          │
│                                                                         │
│  ┌──────────────────────────────────────────┐  ┌─────────────────────┐ │
│  │          YOUNG GENERATION                │  │   OLD GENERATION    │ │
│  │   (Minor GC — frequent, fast, STW)       │  │   (Major/Full GC —  │ │
│  │                                          │  │    infrequent, slow) │ │
│  │  ┌──────────────────┐  ┌────┐  ┌────┐   │  │                     │ │
│  │  │   EDEN SPACE     │  │ S0 │  │ S1 │   │  │  Tenured Objects    │ │
│  │  │  (new objects)   │  │    │  │    │   │  │  (survived 15+      │ │
│  │  │                  │  │    │  │    │   │  │   minor GCs)        │ │
│  │  │  ~80% of Young   │  │~10%│  │~10%│   │  │                     │ │
│  │  └──────────────────┘  └────┘  └────┘   │  │                     │ │
│  │                        Survivor spaces   │  │                     │ │
│  └──────────────────────────────────────────┘  └─────────────────────┘ │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘

Default sizing: Young Gen = 1/3 of heap, Old Gen = 2/3 of heap
                (-XX:NewRatio=2 means Old:Young = 2:1)
```

### Object Lifecycle Through GC

```java
public class ObjectLifecycleDemo {

    static java.util.List<byte[]> oldGenObjects = new java.util.ArrayList<>();

    public static void main(String[] args) throws InterruptedException {

        // Step 1: Object born in Eden
        // new byte[1024] → allocated in Eden space

        // Step 2: Minor GC — Eden full, live objects copied to Survivor
        // Dead objects in Eden collected (free)
        // Live objects: age incremented, moved to S0 or S1

        // Step 3: Object ages through Survivor spaces
        // Each Minor GC that the object survives: age++
        // Default threshold: age 15 → promoted to Old Gen
        // (-XX:MaxTenuringThreshold=15)

        // Step 4: Promotion to Old Gen
        // Object kept in Old Gen until Major GC collects it

        // Demonstrate: force promotion by keeping objects alive
        System.out.println("Creating long-lived objects to fill Old Gen...");
        for (int i = 0; i < 100; i++) {
            // Each allocation in Eden; we keep a reference so it survives GCs
            oldGenObjects.add(new byte[512 * 1024]); // 512KB
            if (i % 10 == 0) {
                System.gc(); // Encourage GC to run — ages the objects
                System.out.printf("  %d objects, heap used: %,dMB%n",
                    oldGenObjects.size(),
                    (Runtime.getRuntime().totalMemory()
                   - Runtime.getRuntime().freeMemory()) / 1024 / 1024);
            }
        }

        // Step 5: Remove references → objects become unreachable
        oldGenObjects.clear();
        System.gc(); // Major GC collects old gen

        System.out.println("After clear + GC:");
        System.out.printf("  Heap used: %,dMB%n",
            (Runtime.getRuntime().totalMemory()
           - Runtime.getRuntime().freeMemory()) / 1024 / 1024);
    }
}
```

### Key GC Events

```
Minor GC (Young GC):
  • Triggered when Eden space is full
  • Collects ONLY the Young Generation
  • Stop-the-World: YES (but very short — milliseconds)
  • Frequency: Very high (hundreds per hour)
  • Eden survivors → Survivor space or promoted to Old Gen

Major GC (Old Gen GC):
  • Triggered when Old Gen is full
  • Collects the Old Generation (sometimes Young too)
  • Stop-the-World: YES (longer — tens to hundreds of ms)
  • Frequency: Low (depends on long-lived object accumulation)

Full GC:
  • Collects ENTIRE heap (Young + Old + Metaspace)
  • Stop-the-World: YES (longest pause)
  • Triggered by: explicit System.gc(), concurrent mode failure,
                  metaspace full, explicit GC from RMI/management
  • Should be RARE in production (goal: eliminate Full GC)
```

---

## 4. GC Algorithms — Core Concepts

All GC collectors use some combination of these fundamental algorithms.

### Mark — Sweep — Compact — Copy

```
┌─────────────────────────────────────────────────────────────┐
│  1. MARK PHASE                                              │
│                                                             │
│  [A●]─[B●]─[C○]─[D●]─[E○]─[F●]─[G○]                      │
│                                                             │
│  ● = Reachable (marked)                                     │
│  ○ = Unreachable (to be collected)                          │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│  2a. SWEEP (Mark-Sweep)                                     │
│                                                             │
│  [A●]─[ ○]─[C○ freed]─[D●]─[E○ freed]─[F●]─[G○ freed]    │
│                                                             │
│  Problem: Fragmentation — gaps between live objects         │
│           Large allocations may fail even with enough       │
│           total free memory                                 │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│  2b. COMPACT (Mark-Sweep-Compact)                           │
│                                                             │
│  Before: [A●][   ][   ][D●][   ][F●][   ]                  │
│  After:  [A●][D●][F●][         free space         ]        │
│                                                             │
│  Advantage: Eliminates fragmentation                        │
│  Disadvantage: Moving objects requires updating all refs    │
│                Stop-the-world during compaction             │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│  2c. COPY (Copying Collector)                               │
│                                                             │
│  From-space: [A●][  ][C○][D●][E○][F●][G○]                 │
│  To-space:   [A●][D●][F●][         free space         ]    │
│                                                             │
│  Live objects copied to new space; from-space cleared       │
│  Advantage: Extremely fast allocation (bump pointer)        │
│             No fragmentation                                │
│  Disadvantage: Uses 2x memory (from + to spaces)           │
│                Used by: Young Gen (Eden→Survivor)           │
└─────────────────────────────────────────────────────────────┘
```

### Bump-Pointer Allocation

```
Heap before allocation:
  [■■■■■■■■■■][Object A][Object B][─────────free─────────]
                                   ↑
                             allocation pointer

After: new byte[100]
  [■■■■■■■■■■][Object A][Object B][new byte[100]][──free──]
                                                  ↑
                                            pointer bumped by 100

→ Allocation is O(1): just increment a pointer!
→ Much faster than malloc() in C which searches free list
→ Only possible in compacted/copying heap regions
```

---

## 5. Serial GC

**Single-threaded** collector. Uses one thread for both GC and application pauses. Simple, lowest overhead, but longest pauses.

```
Minor GC (Serial):
  App Threads: ████─────────────────────────────████
  GC Thread:       │←──── STW pause ────────────→│
                   └── Single GC thread runs ────┘

Major GC (Serial):
  App Threads: ████─────────────────────────────────────────████
  GC Thread:       │←────────── Long STW pause ─────────────→│
```

```bash
# Enable Serial GC
-XX:+UseSerialGC

# When to use:
#   ✅ Embedded / constrained environments (< 100MB heap)
#   ✅ Single CPU machines
#   ✅ Batch jobs where throughput matters more than latency
#   ✅ JVM startup (GraalVM native image style)
#   ❌ Multi-core servers (wastes all other cores during GC)
#   ❌ Interactive applications (pauses are long and noticeable)
```

```java
public class SerialGCDemo {
    public static void main(String[] args) throws InterruptedException {
        // Run with: java -XX:+UseSerialGC -Xmx256m -verbose:gc SerialGCDemo
        System.out.println("GC: " + getActiveGC());

        java.util.List<byte[]> list = new java.util.ArrayList<>();
        for (int i = 0; i < 500; i++) {
            list.add(new byte[512 * 1024]); // 512KB each
            if (i % 50 == 0) {
                list.subList(0, list.size() / 2).clear();
            }
        }
        System.out.println("Done");
    }

    static String getActiveGC() {
        return java.lang.management.ManagementFactory
            .getGarbageCollectorMXBeans().stream()
            .map(java.lang.management.GarbageCollectorMXBean::getName)
            .reduce("", (a, b) -> a + " " + b).trim();
    }
}
```

---

## 6. Parallel GC (Throughput Collector)

Uses **multiple threads** for GC. All threads participate in GC while the application is fully paused. Maximizes throughput at the cost of longer (but parallelized) pauses.

```
Minor GC (Parallel):
  App Thread 1: ████─────────────████
  App Thread 2: ████─────────────████
  App Thread 3: ████─────────────████
  App Thread 4: ████─────────────████
  GC Thread 1:      │←─STW──→│
  GC Thread 2:      │←─STW──→│       ← Multiple GC threads shorten pause
  GC Thread 3:      │←─STW──→│
  GC Thread 4:      │←─STW──→│
```

```bash
# Enable Parallel GC (default Java 8)
-XX:+UseParallelGC

# Number of GC threads (default = number of CPU cores up to 8, then scaled)
-XX:ParallelGCThreads=8

# Target max pause time (soft goal, not guaranteed)
-XX:MaxGCPauseMillis=500

# Target throughput: 99% app time, 1% GC time (default)
-XX:GCTimeRatio=99
# GCTimeRatio=N means: throughput goal = N/(N+1) = 99/100 = 99% app time

# Adaptive sizing (enabled by default with Parallel GC)
-XX:+UseAdaptiveSizePolicy       # JVM auto-tunes Young/Old gen sizes
-XX:-UseAdaptiveSizePolicy       # Disable if you want manual control
```

```java
public class ParallelGCDemo {

    // Benchmark: Parallel GC throughput vs Serial GC
    public static void main(String[] args) {
        // Run with:
        // java -XX:+UseParallelGC -XX:ParallelGCThreads=4 -Xmx512m ParallelGCDemo
        // vs
        // java -XX:+UseSerialGC -Xmx512m ParallelGCDemo

        int iterations = 200;
        long start = System.currentTimeMillis();

        for (int i = 0; i < iterations; i++) {
            // Simulate batch processing — lots of allocation
            processChunk(i);
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("Processed %d chunks in %,dms (%.1f chunks/sec)%n",
            iterations, elapsed, iterations * 1000.0 / elapsed);
    }

    static void processChunk(int chunkId) {
        // Allocate and process 5MB worth of data
        byte[][] rows = new byte[1000][5 * 1024]; // 1000 rows × 5KB = 5MB
        long checksum = 0;
        for (byte[] row : rows) {
            java.util.Arrays.fill(row, (byte) (chunkId % 127));
            checksum += row[0];
        }
        // rows become unreachable here → eligible for GC
        if (chunkId % 50 == 0) {
            System.out.printf("  Chunk %3d done, checksum=%d%n", chunkId, checksum);
        }
    }
}
```

---

## 7. CMS GC (Concurrent Mark Sweep) — Legacy

**Deprecated in Java 9, removed in Java 14.** CMS runs most of its work concurrently with the application to minimize pause times, but suffers from fragmentation and concurrent mode failures.

```
CMS Phases:
  App:     ████│STW│██████████████████████████│STW│██████│STW│██████
  GC:          │IM │→concurrent mark──────────│re │sweep │rs │

  IM   = Initial Mark (STW — short)       → Mark GC roots
  CM   = Concurrent Mark (CONCURRENT)     → Trace from roots while app runs
  re   = Remark (STW — medium)            → Fix up changes made during CM
  sweep = Concurrent Sweep (CONCURRENT)   → Free unreachable objects
  rs   = Concurrent Reset                 → Reset for next cycle

Why CMS was problematic:
  1. No compaction → fragmentation → eventual Full GC
  2. Concurrent mode failure: if Old Gen fills during CMS → emergency Full GC
  3. Higher CPU usage (concurrent phases consume cores)
  4. Complex tuning requirements

→ Replaced by G1GC for low-latency use cases
```

---

## 8. G1GC — Garbage First (Default Java 9+)

**G1GC** divides the heap into **equally-sized regions** and collects the regions with the most garbage first (hence "Garbage First"). Designed to replace CMS with predictable pause times.

### G1GC Heap Layout

```
G1GC Heap — divided into equal-sized regions (1MB–32MB each):

┌────┬────┬────┬────┬────┬────┬────┬────┐
│ E  │ E  │ S  │ O  │ O  │ E  │ H  │ H  │  Row 1
├────┼────┼────┼────┼────┼────┼────┼────┤
│ O  │ F  │ O  │ E  │ S  │ O  │ F  │ O  │  Row 2
├────┼────┼────┼────┼────┼────┼────┼────┤
│ F  │ O  │ E  │ F  │ O  │ E  │ O  │ F  │  Row 3
├────┼────┼────┼────┼────┼────┼────┼────┤
│ O  │ E  │ O  │ H  │ F  │ O  │ E  │ O  │  Row 4
└────┴────┴────┴────┴────┴────┴────┴────┘

E = Eden    (young generation regions)
S = Survivor (young generation regions)
O = Old     (tenured/old generation regions)
H = Humongous (large objects spanning multiple regions, > 50% of region size)
F = Free    (available for allocation)

Key: Regions are NOT contiguous per generation!
     G1 can dynamically resize Young/Old by reassigning regions.
```

### G1GC Collection Phases

```
Phase 1: YOUNG-ONLY GC (Minor GC equivalent)
  • Stop-the-World
  • Collects only Eden and Survivor regions
  • Copies live objects to new Survivor or Old regions
  • Pause target: MaxGCPauseMillis (default 200ms)

Phase 2: CONCURRENT MARK CYCLE (triggered when Old Gen fills)
  ┌─────────────────────────────────────────────────────┐
  │ Step 1: Initial Mark (STW — piggybacks on Young GC) │
  │ Step 2: Root Region Scan (concurrent)               │
  │ Step 3: Concurrent Mark (concurrent)                │
  │ Step 4: Remark (STW — short)                        │
  │ Step 5: Cleanup (STW — short + concurrent)          │
  └─────────────────────────────────────────────────────┘

Phase 3: MIXED GC
  • Collects Young regions + SOME Old regions
  • Old regions selected by highest garbage ratio
  • Multiple Mixed GC cycles until Old Gen cleaned up
  • Eventually returns to Young-only phase
```

```java
public class G1GCDemo {

    // G1GC tuning JVM flags:
    // java -XX:+UseG1GC
    //      -XX:MaxGCPauseMillis=200        ← Target max pause (default 200ms)
    //      -XX:G1HeapRegionSize=16m        ← Region size (1MB–32MB, power of 2)
    //      -XX:G1NewSizePercent=5          ← Min Young gen % (default 5)
    //      -XX:G1MaxNewSizePercent=60      ← Max Young gen % (default 60)
    //      -XX:G1OldCSetRegionThreshold=10 ← Old regions per Mixed GC
    //      -XX:InitiatingHeapOccupancyPercent=45 ← Start concurrent mark at 45% Old
    //      -XX:G1MixedGCCountTarget=8      ← Mixed GC cycles to spread collection
    //      -Xms4g -Xmx4g                   ← Equal min/max avoids resize pauses

    public static void main(String[] args) throws InterruptedException {

        System.out.println("Active GC: " + SerialGCDemo.getActiveGC());

        // Simulate mixed workload: some short-lived, some long-lived
        java.util.List<byte[]> longLived = new java.util.ArrayList<>();
        java.util.Deque<byte[]> shortLived = new java.util.ArrayDeque<>();

        for (int i = 0; i < 2000; i++) {
            // 90% short-lived objects (die in Young Gen)
            shortLived.offer(new byte[10 * 1024]); // 10KB short-lived
            if (shortLived.size() > 100) shortLived.poll(); // Evict old ones

            // 10% long-lived (eventually promoted to Old Gen)
            if (i % 10 == 0) {
                longLived.add(new byte[100 * 1024]); // 100KB long-lived
            }

            // Remove 20% of long-lived periodically (simulates real workload)
            if (i % 100 == 99 && longLived.size() > 50) {
                longLived.subList(0, longLived.size() / 5).clear();
            }

            if (i % 200 == 0) {
                Runtime rt = Runtime.getRuntime();
                System.out.printf("  iter=%4d  heap used=%,dMB  long-lived=%d%n",
                    i,
                    (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024,
                    longLived.size());
            }
        }
    }
}
```

### G1GC Key Tuning Parameters

```bash
# ── Core G1GC Flags ───────────────────────────────────────────────────────────
-XX:+UseG1GC                              # Enable (default Java 9+)
-Xms4g -Xmx4g                            # Equal min/max (no resize pauses)

# ── Pause Time Goal ───────────────────────────────────────────────────────────
-XX:MaxGCPauseMillis=200                  # Target max pause ms (soft goal)
# G1 adjusts Young Gen size to meet this target
# Lower value = more frequent but shorter GC = lower throughput
# Higher value = less frequent but longer GC = higher throughput

# ── Region Size ───────────────────────────────────────────────────────────────
-XX:G1HeapRegionSize=16m
# Must be power of 2, between 1MB and 32MB
# Guideline: RegionSize = Xmx / 2048  (aim for ~2048 regions)
#   4GB heap  → 4096MB/2048 = 2MB  → -XX:G1HeapRegionSize=2m
#   16GB heap → 16384MB/2048 = 8MB → -XX:G1HeapRegionSize=8m
#   32GB heap → 32768MB/2048 = 16m → -XX:G1HeapRegionSize=16m

# ── Concurrent Mark Trigger ───────────────────────────────────────────────────
-XX:InitiatingHeapOccupancyPercent=45     # Start concurrent mark when Old Gen hits 45%
# Lower = more concurrent marks = less risk of Full GC = more CPU
# Higher = fewer marks = higher Full GC risk

# ── Young Generation Sizing ───────────────────────────────────────────────────
-XX:G1NewSizePercent=5                    # Min Young Gen as % of heap (default 5)
-XX:G1MaxNewSizePercent=60               # Max Young Gen as % of heap (default 60)

# ── Mixed GC Tuning ───────────────────────────────────────────────────────────
-XX:G1MixedGCLiveThresholdPercent=85     # Only collect Old regions < 85% live
-XX:G1MixedGCCountTarget=8               # Spread Mixed GC over N cycles
-XX:G1OldCSetRegionThreshold=10          # Max Old regions per Mixed GC

# ── Humongous Objects (> 50% of region size) ─────────────────────────────────
# Large objects go directly to Old Gen as Humongous objects
# They can cause fragmentation — avoid or tune region size
# If you have many 1.5MB objects: -XX:G1HeapRegionSize=4m (so 1.5MB < 2MB = not humongous)

# ── String Deduplication (Java 8u20+) ────────────────────────────────────────
-XX:+UseStringDeduplication              # Deduplicate identical String values on heap
-XX:+PrintStringDeduplicationStatistics # Show dedup stats in GC log
```

### Humongous Object Problem & Fix

```java
public class HumongousObjectDemo {

    // Run with:
    // java -XX:+UseG1GC -XX:G1HeapRegionSize=1m -Xlog:gc* HumongousObjectDemo

    public static void main(String[] args) {
        // Region size = 1MB → objects > 512KB = humongous

        // ❌ Problem: 600KB objects are humongous → Old Gen → more Full GCs
        for (int i = 0; i < 1000; i++) {
            byte[] obj = new byte[600 * 1024]; // 600KB > 50% of 1MB region = HUMONGOUS
            processAndDiscard(obj);
        }

        // ✅ Fix option 1: Increase region size so objects are not humongous
        // -XX:G1HeapRegionSize=4m → 600KB < 2MB threshold = normal Young Gen object

        // ✅ Fix option 2: Pool and reuse large objects
        byte[] pooledBuffer = new byte[600 * 1024]; // Allocate once
        for (int i = 0; i < 1000; i++) {
            processWithBuffer(pooledBuffer); // Reuse — no repeated allocation
        }
    }

    static void processAndDiscard(byte[] buf) {
        java.util.Arrays.fill(buf, (byte) 1);
    }

    static void processWithBuffer(byte[] buf) {
        java.util.Arrays.fill(buf, (byte) 1);
    }
}
```

---

## 9. ZGC — Ultra Low Latency (Java 15+)

**ZGC** (Z Garbage Collector) achieves sub-millisecond pause times regardless of heap size (up to terabytes) by doing almost all work concurrently. It uses **colored pointers** and **load barriers** to track and move objects while the application is running.

```
ZGC Phases:
  App:    █████████████████████████████████████████████████████
  GC:     │←───────────── All concurrent! ─────────────────→│
               Init   Concurrent   Concurrent   Concurrent
               Mark   Mark         Relocate     Remap
               (STW   (concurrent) (concurrent) (concurrent)
               <1ms)

ZGC pause sources:
  • Initial Mark:   < 1ms (stop-the-world, mark GC roots only)
  • Final Mark:     < 1ms (stop-the-world, short fixup)
  • Initial Relocate: < 1ms

Everything else runs CONCURRENTLY with the application threads!
```

### How ZGC Achieves Concurrency — Colored Pointers

```
Normal 64-bit pointer:
  [000000000000000000000000000000000000000000 ADDRESS_BITS]
   ^─ unused bits

ZGC colored pointer (uses spare bits for metadata):
  [Finalizable│Remapped│Marked1│Marked0│ ADDRESS_BITS (42 bits)]
   ↑ 4 metadata bits used to track object state

Load Barrier (automatically inserted by JIT at every pointer load):
  Object ref = someField;      // Your code
  // JIT inserts:
  if (ref is not in correct state) {
      ref = slowPath(ref);     // Heal the pointer (concurrent relocation)
  }

→ When GC moves an object, it updates the colored bit
→ Next time any thread loads the pointer, the load barrier heals it
→ No STW needed for compaction!
```

```bash
# Enable ZGC (Java 15+ for production)
-XX:+UseZGC

# Heap sizing
-Xms8g -Xmx32g                          # ZGC works well with large heaps

# GC threads (default: min(8, nCPUs/8+2) for concurrent, nCPUs for parallel)
-XX:ConcGCThreads=4                      # Concurrent GC threads
-XX:ParallelGCThreads=8                  # STW phase threads

# Pause target (Java 16+)
-XX:SoftMaxHeapSize=28g                  # Soft max: ZGC keeps heap below this

# Uncommit unused memory (Java 13+)
-XX:+ZUncommit                           # Return unused heap pages to OS
-XX:ZUncommitDelay=300                   # Wait 300s before uncommitting

# Generational ZGC (Java 21 — major improvement!)
-XX:+UseZGC -XX:+ZGenerational           # Java 21: Generational ZGC
```

```java
public class ZGCDemo {

    // Demonstrates ZGC's key advantage: stable pause times under memory pressure
    // Run with:
    //   java -XX:+UseZGC -Xmx4g -Xlog:gc*:file=zgc.log:time ZGCDemo
    // vs
    //   java -XX:+UseG1GC -Xmx4g -Xlog:gc*:file=g1.log:time ZGCDemo

    public static void main(String[] args) throws InterruptedException {
        java.util.List<byte[]> survivors = new java.util.ArrayList<>();
        java.util.Random rng = new java.util.Random(42);

        System.out.println("Measuring pause times...");

        long maxPause = 0;
        long totalPause = 0;
        int gcCount = 0;

        long loopStart = System.currentTimeMillis();

        for (int i = 0; i < 5000; i++) {
            long before = System.nanoTime();

            // Mixed allocation: small + medium objects
            survivors.add(new byte[rng.nextInt(50 * 1024)]); // 0–50KB
            if (survivors.size() > 500) {
                survivors.subList(0, 100).clear(); // Remove some
            }

            long pause = (System.nanoTime() - before) / 1_000; // microseconds
            if (pause > 1000) { // Pauses > 1ms
                maxPause = Math.max(maxPause, pause);
                totalPause += pause;
                gcCount++;
            }

            Thread.sleep(1); // 1ms between iterations
        }

        long elapsed = System.currentTimeMillis() - loopStart;
        System.out.printf("Elapsed: %,dms%n", elapsed);
        System.out.printf("Detected %d GC pauses%n", gcCount);
        System.out.printf("Max pause: %,d microseconds (%.1fms)%n",
            maxPause, maxPause / 1000.0);
        System.out.printf("Avg pause: %,d microseconds (%.1fms)%n",
            gcCount > 0 ? totalPause / gcCount : 0,
            gcCount > 0 ? totalPause / gcCount / 1000.0 : 0);

        // ZGC:  Max pause typically <1ms even with 32GB heap
        // G1GC: Max pause typically 10ms–200ms depending on heap/workload
    }
}
```

### ZGC vs G1GC Trade-offs

```
               ZGC                     G1GC
Pauses:        < 1ms  ✅               10ms–200ms
Throughput:    ~5–15% lower ❌          Better ✅
Heap size:     1MB – 16TB  ✅           Typically < 64GB
CPU overhead:  Higher (concurrent) ❌   Lower ✅
Maturity:      Java 15+ prod ✅         Java 9+, very mature ✅
Generational:  Java 21 ✅               Yes (always) ✅

Choose ZGC when: latency < 5ms required, heap > 16GB
Choose G1GC when: balanced workload, mature, good all-around
```

---

## 10. Shenandoah GC

**Shenandoah** is a low-latency GC from Red Hat, available in OpenJDK. Similar to ZGC in goals (concurrent compaction) but uses a different mechanism: **Brooks Pointers** (forwarding pointers).

```bash
# Enable Shenandoah
-XX:+UseShenandoahGC

# Heuristic (collection trigger strategy)
-XX:ShenandoahGCHeuristics=adaptive    # Default: auto-adapt
-XX:ShenandoahGCHeuristics=static      # Fixed thresholds
-XX:ShenandoahGCHeuristics=aggressive  # GC as often as possible
-XX:ShenandoahGCHeuristics=compact     # Minimize footprint

# Mode
-XX:ShenandoahGCMode=satb              # Default concurrent mode
-XX:ShenandoahGCMode=iu                # Incremental Update (Java 11+)
-XX:ShenandoahGCMode=passive           # STW only (debugging)

# Free heap trigger
-XX:ShenandoahMinFreeThreshold=10      # Start GC at 10% free remaining
-XX:ShenandoahInitFreeThreshold=70     # Initial free threshold %
```

---

## 11. GC Log Analysis

GC logs are your primary diagnostic tool for understanding GC behavior in production.

### Enabling GC Logging

```bash
# Java 9+ unified logging:
-Xlog:gc                                 # Basic GC events
-Xlog:gc*                                # All GC details
-Xlog:gc*:file=/var/log/gc.log:time,level,tags
-Xlog:gc*:file=/var/log/gc.log:time,level,tags:filecount=5,filesize=20m

# Java 8 (legacy):
-XX:+PrintGCDetails
-XX:+PrintGCDateStamps
-XX:+PrintGCTimeStamps
-Xloggc:/var/log/gc.log
-XX:+UseGCLogFileRotation
-XX:NumberOfGCLogFiles=5
-XX:GCLogFileSize=20m
```

### Reading G1GC Logs

```
Sample G1GC log output (annotated):

[0.234s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 45M->12M(256M) 8.234ms
  │       │      │   │        │         │                             │   │    │       │
  │       │      │   │        │         │                             │   │    │       └─ Pause duration
  │       │      │   │        │         │                             │   │    └─ Total heap
  │       │      │   │        │         │                             │   └─ After GC heap used
  │       │      │   │        │         │                             └─ Before GC heap used
  │       │      │   │        │         └─ Cause: Evacuation (normal Young GC)
  │       │      │   │        └─ Normal: no special conditions
  │       │      │   └─ GC(0): 1st GC event
  │       │      └─ gc log tag
  │       └─ info level
  └─ 0.234 seconds since JVM start

[2.341s][info][gc] GC(12) Pause Young (Concurrent Start) (G1 Humongous Allocation) 180M->165M(256M) 12.1ms
                                      ↑ Starting concurrent mark cycle
                                                             ↑ Triggered by humongous allocation!

[2.342s][info][gc] GC(13) Concurrent Mark Cycle
[2.345s][info][gc] GC(13) Pause Remark 170M->168M(256M) 2.3ms
[2.380s][info][gc] GC(13) Pause Cleanup 168M->168M(256M) 0.8ms
[2.380s][info][gc] GC(13) Concurrent Mark Cycle 38.234ms   ← Concurrent mark took 38ms total

[2.412s][info][gc] GC(14) Pause Young (Mixed) (G1 Evacuation Pause) 200M->140M(256M) 15.3ms
                                      ↑ Mixed GC: collecting Young + some Old regions

RED FLAGS in GC logs:
  "Full GC"                → Something is wrong — full heap collection
  "Allocation Failure"     → Objects couldn't be allocated in Eden
  "Humongous Allocation"   → Large objects triggering concurrent mark early
  "Concurrent Mode Failure"→ (CMS) Old Gen filled before CMS finished
  "Promotion Failed"       → Survivor/Old Gen full — degraded to Full GC
  Very long pause (>500ms) → Tune MaxGCPauseMillis or heap size
```

### Programmatic GC Event Monitoring

```java
import java.lang.management.*;
import javax.management.*;
import javax.management.openmbean.*;

public class GCEventMonitor {

    public static void main(String[] args) throws Exception {

        // Register listener for GC notifications
        for (GarbageCollectorMXBean gc :
                ManagementFactory.getGarbageCollectorMXBeans()) {

            NotificationEmitter emitter = (NotificationEmitter) gc;
            emitter.addNotificationListener((notification, handback) -> {

                if (notification.getType().equals(
                        com.sun.management.GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) {

                    com.sun.management.GarbageCollectionNotificationInfo info =
                        com.sun.management.GarbageCollectionNotificationInfo
                            .from((CompositeData) notification.getUserData());

                    com.sun.management.GcInfo gcInfo = info.getGcInfo();

                    System.out.printf("[GC EVENT] %s | cause=%s | duration=%dms%n",
                        info.getGcName(),
                        info.getGcCause(),
                        gcInfo.getDuration());

                    // Before/after memory usage
                    gcInfo.getMemoryUsageBeforeGc().forEach((pool, usage) -> {
                        long usedBefore = usage.getUsed() / 1024 / 1024;
                        long usedAfter  = gcInfo.getMemoryUsageAfterGc()
                                               .get(pool).getUsed() / 1024 / 1024;
                        if (usedBefore > 0 || usedAfter > 0) {
                            System.out.printf("  %-30s %,dMB → %,dMB%n",
                                pool, usedBefore, usedAfter);
                        }
                    });
                }
            }, null, null);
        }

        System.out.println("GC listener registered. Triggering GC events...");

        // Trigger some GC events
        java.util.List<byte[]> list = new java.util.ArrayList<>();
        for (int i = 0; i < 500; i++) {
            list.add(new byte[1024 * 1024]); // 1MB
            if (list.size() > 50) list.subList(0, 25).clear();
            Thread.sleep(10);
        }
    }
}
```

### GC Log Analyzers

```
Tools for analyzing GC logs:
  1. GCEasy (https://gceasy.io) — web-based, upload log, instant analysis
     → Shows: pause time distribution, throughput, memory trends

  2. Eclipse Memory Analyzer (MAT)
     → Heap dump analysis (not GC logs)

  3. GCViewer (open source)
     → Visualize GC log as charts

  4. JVM GC Logs Analyzer
     → Parse and query GC logs programmatically

Key metrics to track from GC logs:
  ✦ Average and max pause time
  ✦ GC frequency (Minor GC and Major GC per minute)
  ✦ Throughput (% of time NOT in GC)
  ✦ Heap occupancy before and after GC
  ✦ Promotion rate (bytes/sec moved from Young to Old)
  ✦ Allocation rate (bytes/sec created in Eden)
  ✦ Full GC frequency (should be 0 ideally)
```

---

## 12. GC Tuning Flags — Complete Reference

### Universal Flags (All Collectors)

```bash
# ── Heap Sizing ───────────────────────────────────────────────────────────────
-Xms<size>                    # Initial heap size (set = Xmx to avoid resize GC)
-Xmx<size>                    # Maximum heap size (ALWAYS set this)
-XX:NewSize=<size>            # Initial Young Gen size
-XX:MaxNewSize=<size>         # Max Young Gen size
-XX:NewRatio=N                # Old:Young ratio (default 2 = Young is 1/3 of heap)
-XX:SurvivorRatio=N           # Eden:Survivor ratio (default 8 = Eden is 8/10 of Young)
-Xss<size>                    # Thread stack size (default 512k–1m)

# ── Object Promotion ──────────────────────────────────────────────────────────
-XX:MaxTenuringThreshold=15   # Max GC cycles before promoting to Old Gen (default 15)
-XX:InitialTenuringThreshold=7# Starting tenuring threshold

# ── Metaspace ─────────────────────────────────────────────────────────────────
-XX:MetaspaceSize=<size>      # Initial metaspace (triggers first GC to resize)
-XX:MaxMetaspaceSize=<size>   # Cap metaspace (default: unlimited)

# ── GC Overhead ───────────────────────────────────────────────────────────────
-XX:GCTimeRatio=99            # Throughput goal: 99% app, 1% GC
-XX:-UseGCOverheadLimit       # Disable GC overhead limit (not recommended)

# ── Explicit GC ───────────────────────────────────────────────────────────────
-XX:+ExplicitGCInvokesConcurrent    # System.gc() uses concurrent GC (not Full GC)
-XX:+DisableExplicitGC              # Ignore all System.gc() calls

# ── Diagnostic ────────────────────────────────────────────────────────────────
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/tmp/heap.hprof
-XX:+ExitOnOutOfMemoryError
-Xlog:gc*:file=/var/log/gc.log:time,level,tags
-XX:+PrintAdaptiveSizePolicy          # Show adaptive sizing decisions

# ── Container Support (Java 10+) ──────────────────────────────────────────────
-XX:+UseContainerSupport              # Respect Docker memory limits (default Java 10+)
-XX:MaxRAMPercentage=75.0             # Heap = 75% of container memory
-XX:InitialRAMPercentage=50.0         # Initial heap = 50% of container memory
-XX:MinRAMPercentage=25.0             # Min heap % (for containers < 200MB)
```

### GC Selector Flags

```bash
-XX:+UseSerialGC       # Serial (single-threaded, small heap)
-XX:+UseParallelGC     # Parallel (throughput, Java 8 default)
-XX:+UseG1GC           # G1GC (balanced, Java 9+ default)
-XX:+UseZGC            # ZGC (ultra-low latency, Java 15+)
-XX:+UseShenandoahGC   # Shenandoah (low latency, OpenJDK)

# Java 21: Generational ZGC (major improvement to ZGC)
-XX:+UseZGC -XX:+ZGenerational
```

---

## 13. Object Allocation & Escape Analysis

### TLAB — Thread-Local Allocation Buffer

```
Each thread has a private buffer in Eden (TLAB):

Thread 1:  [TLAB 1: ████████░░░░░░░]
Thread 2:  [TLAB 2: █████████████░░]
Thread 3:  [TLAB 3: ██░░░░░░░░░░░░░]
              ↑ Filled    ↑ Free

Allocation in TLAB:
  new Object()
  → JIT code: tlab.pointer + size → O(1) without synchronization!
  → No lock needed (thread-private)
  → When TLAB full: get new TLAB from Eden (brief lock)

JVM flags:
-XX:TLABSize=<size>          # Fixed TLAB size (default: adaptive)
-XX:+PrintTLAB               # Print TLAB stats
-XX:-UseTLAB                 # Disable TLAB (never do this in production)
```

### Escape Analysis — Stack Allocation & Scalar Replacement

```java
public class EscapeAnalysisDemo {

    // Escape Analysis: JIT determines if an object "escapes" the method
    // Non-escaping objects can be allocated on STACK (not heap) → no GC needed!

    static class Point {
        double x, y;
        Point(double x, double y) { this.x = x; this.y = y; }

        double distance(Point other) {
            double dx = this.x - other.x;
            double dy = this.y - other.y;
            return Math.sqrt(dx * dx + dy * dy);
        }
    }

    // ── DOES NOT escape — JIT may allocate p1, p2 on stack ───────────────────
    static double calculateDistance(double x1, double y1, double x2, double y2) {
        Point p1 = new Point(x1, y1); // p1 stays in this method
        Point p2 = new Point(x2, y2); // p2 stays in this method
        return p1.distance(p2);
        // p1 and p2 "die" here — JIT can scalar-replace them!
    }
    // Scalar replacement: JIT converts Point's fields (x, y) to local variables
    // → No Point object created on heap at all → no GC pressure!

    // ── ESCAPES — must allocate on heap ──────────────────────────────────────
    static Point escapingPoint(double x, double y) {
        return new Point(x, y); // Returned to caller — ESCAPES method
        // Must be on heap (caller might keep it alive)
    }

    // ── Also escapes ──────────────────────────────────────────────────────────
    static java.util.List<Point> points = new java.util.ArrayList<>();
    static void storedInField(double x, double y) {
        points.add(new Point(x, y)); // Stored in static field — ESCAPES
    }

    public static void main(String[] args) {
        // Run with JIT flags:
        // java -XX:+DoEscapeAnalysis (default ON)
        //      -XX:+EliminateAllocations (default ON — scalar replacement)
        //      -XX:+PrintEscapeAnalysis  (debug output)

        long start = System.nanoTime();

        // This loop may generate ZERO heap allocations with escape analysis!
        double sum = 0;
        for (int i = 0; i < 10_000_000; i++) {
            sum += calculateDistance(i, i * 2, i + 1, i * 2 + 1);
        }

        long elapsed = System.nanoTime() - start;
        System.out.printf("Sum=%.2f in %,dms%n", sum, elapsed / 1_000_000);
        System.out.println("(Many Point objects may have been stack-allocated or eliminated)");
    }
}
```

---

## 14. GC Safepoints & Stop-the-World

### What is a Safepoint?

```
A Safepoint is a point in the application code where the JVM can safely
inspect or modify the state of all threads (for GC, deoptimization, etc.)

Safepoint mechanism:
  1. JVM requests a safepoint (e.g., GC needs to happen)
  2. JIT-compiled code has safepoint polls at:
       - Method return
       - Loop back edges (every N iterations)
       - Before/after certain operations
  3. When a thread reaches a safepoint poll → it pauses and signals
  4. JVM waits for ALL threads to reach safepoints
  5. GC (or other operation) runs while all threads are paused
  6. Threads resume

The "Time to Safepoint" (TTSP) is often invisible in GC logs:

Total GC pause:  [TTSP: 50ms] + [Actual GC work: 30ms] = 80ms
                  ↑ Often not logged! GC log shows only 30ms
                  This can make pauses appear shorter than they are
```

### Stop-the-World Visualization

```
                    ← STW Pause →
Thread 1: ──────────│            │──────────
Thread 2: ─────────────│        │────────── ← Thread 2 took longer to reach safepoint
Thread 3: ──────────│            │──────────
GC Thread: ─────────────────│GC│────────── ← GC starts only after ALL threads pause

Real pause = time from safepoint request → last thread pauses + GC work + resume

Safepoint-related JVM flags:
-XX:+PrintSafepointStatistics        # Print safepoint stats (Java 8)
-Xlog:safepoint*                     # Java 9+
-XX:+SafepointTimeout               # Enable timeout
-XX:SafepointTimeoutDelay=5000      # Timeout after 5s (log which thread is stuck)
```

```java
public class SafepointDemo {

    // ❌ Code that delays safepoints — can increase STW pause time
    static long countedLoop(long n) {
        long sum = 0;
        // JIT may place safepoint polls only at loop back edges
        // Counted loops CAN sometimes defer safepoint
        for (long i = 0; i < n; i++) {
            sum += i;
        }
        return sum;
    }

    // ✅ Break up long-running loops
    static long safeLongLoop(long n, int yieldEvery) throws InterruptedException {
        long sum = 0;
        for (long i = 0; i < n; i++) {
            sum += i;
            if (i % yieldEvery == 0) {
                Thread.yield(); // Allows safepoint — GC can proceed promptly
            }
        }
        return sum;
    }

    // Java 10+ JVM eliminates most counted loop safepoint issues
    // But on Java 8: -XX:+UseCountedLoopSafepoints can help
    public static void main(String[] args) throws InterruptedException {
        System.out.println(countedLoop(100_000_000L));
        System.out.println(safeLongLoop(100_000_000L, 10_000));
    }
}
```

---

## 15. Memory Monitoring with JMX/MBeans

```java
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;

public class GCMonitoringService {

    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gc-monitor");
            t.setDaemon(true);
            return t;
        });

    // ── Start periodic memory monitoring ──────────────────────────────────────
    public void startMonitoring(long intervalSeconds) {
        scheduler.scheduleAtFixedRate(this::printMemoryReport,
            0, intervalSeconds, TimeUnit.SECONDS);
    }

    // ── Full memory report ─────────────────────────────────────────────────────
    public void printMemoryReport() {
        System.out.println("══════════════════════════════════════════");
        System.out.println(" GC MEMORY REPORT — " + new java.util.Date());
        System.out.println("══════════════════════════════════════════");

        // Heap and Non-Heap
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap    = mem.getHeapMemoryUsage();
        MemoryUsage nonHeap = mem.getNonHeapMemoryUsage();

        printBar("Heap",     heap.getUsed(),    heap.getMax());
        printBar("Non-Heap", nonHeap.getUsed(), nonHeap.getCommitted());

        // Individual pools
        System.out.println("\nMemory Pools:");
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage u = pool.getUsage();
            if (u.getUsed() > 0) {
                System.out.printf("  %-30s used=%,6dMB  committed=%,6dMB  max=%s%n",
                    pool.getName(),
                    u.getUsed()      / 1024 / 1024,
                    u.getCommitted() / 1024 / 1024,
                    u.getMax() < 0   ? "unlimited"
                                     : String.format("%,dMB", u.getMax() / 1024 / 1024));
            }
        }

        // GC statistics
        System.out.println("\nGC Statistics:");
        long totalGCTime  = 0;
        long totalGCCount = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gc.getCollectionCount();
            long time  = gc.getCollectionTime();
            totalGCCount += count;
            totalGCTime  += time;
            System.out.printf("  %-35s count=%-6d time=%,dms  avg=%.1fms%n",
                gc.getName(), count, time,
                count > 0 ? (double) time / count : 0);
        }

        // Throughput calculation
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        double gcOverhead = uptime > 0 ? 100.0 * totalGCTime / uptime : 0;
        System.out.printf("%nGC Overhead: %.2f%% (JVM up %,dms, GC total %,dms)%n",
            gcOverhead, uptime, totalGCTime);

        if (gcOverhead > 10) {
            System.out.println("⚠ WARNING: GC overhead > 10% — consider tuning!");
        }
        System.out.println();
    }

    private void printBar(String label, long used, long max) {
        if (max <= 0) {
            System.out.printf("  %-10s  used=%,dMB  max=unlimited%n",
                label, used / 1024 / 1024);
            return;
        }
        double pct = 100.0 * used / max;
        int barLen  = 30;
        int filled  = (int)(pct / 100 * barLen);
        String bar  = "█".repeat(filled) + "░".repeat(barLen - filled);
        System.out.printf("  %-10s  [%s] %5.1f%%  %,dMB / %,dMB%n",
            label, bar, pct, used / 1024 / 1024, max / 1024 / 1024);
    }

    // ── Check if GC is under pressure ─────────────────────────────────────────
    public boolean isUnderGCPressure(double maxGCOverheadPct) {
        long gcTime   = ManagementFactory.getGarbageCollectorMXBeans()
                            .stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
        long uptime   = ManagementFactory.getRuntimeMXBean().getUptime();
        double overhead = uptime > 0 ? 100.0 * gcTime / uptime : 0;
        return overhead > maxGCOverheadPct;
    }

    public static void main(String[] args) throws InterruptedException {
        GCMonitoringService monitor = new GCMonitoringService();
        monitor.startMonitoring(5); // Report every 5 seconds

        // Simulate workload
        List<byte[]> data = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            data.add(new byte[1024 * 1024]);       // 1MB
            if (data.size() > 50) data.subList(0, 20).clear();
            Thread.sleep(100);
        }

        System.out.println("GC Pressure: " + monitor.isUnderGCPressure(5.0));
        monitor.scheduler.shutdown();
    }
}
```

---

## 16. Tuning for Specific Scenarios

### Scenario 1: Web Application (Spring Boot)

```bash
# Goal: Low latency for HTTP requests, balanced throughput
# Heap: 2–8GB typical

JAVA_OPTS="\
  -Xms2g -Xmx2g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=100 \
  -XX:G1HeapRegionSize=4m \
  -XX:InitiatingHeapOccupancyPercent=40 \
  -XX:MetaspaceSize=128m \
  -XX:MaxMetaspaceSize=256m \
  -XX:+UseStringDeduplication \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/dumps/ \
  -XX:+ExitOnOutOfMemoryError \
  -Xlog:gc*:file=/var/log/gc.log:time,level,tags:filecount=5,filesize=20m"
```

---

### Scenario 2: Microservice (Container — Kubernetes)

```bash
# Goal: Respect container limits, efficient small heap
# Container: 512MB–2GB memory limit

JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:InitialRAMPercentage=50.0 \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+ExitOnOutOfMemoryError \
  -XX:+HeapDumpOnOutOfMemoryError \
  -Xlog:gc*:file=/var/log/gc.log:time"

# For Java 21 with virtual threads (recommended):
JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseZGC -XX:+ZGenerational \
  -XX:+ExitOnOutOfMemoryError \
  -Xlog:gc*:file=/var/log/gc.log:time"
```

---

### Scenario 3: Batch Processing (Large Data)

```bash
# Goal: Maximum throughput, latency irrelevant
# Heap: 8–64GB, long-running processes

JAVA_OPTS="\
  -Xms16g -Xmx16g \
  -XX:+UseParallelGC \
  -XX:ParallelGCThreads=8 \
  -XX:GCTimeRatio=19 \
  -XX:NewRatio=3 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -Xlog:gc*:file=/var/log/gc-batch.log:time"

# If using G1 for batch (better handling of uneven promotion):
JAVA_OPTS="\
  -Xms16g -Xmx16g \
  -XX:+UseG1GC \
  -XX:G1HeapRegionSize=32m \
  -XX:MaxGCPauseMillis=1000 \
  -XX:GCTimeRatio=9 \
  -XX:InitiatingHeapOccupancyPercent=50"
```

---

### Scenario 4: Low-Latency (Trading, Gaming, Real-time)

```bash
# Goal: < 5ms GC pauses, deterministic response times
# Heap: 4–64GB

# Option A: ZGC (Java 15+ — sub-millisecond pauses)
JAVA_OPTS="\
  -Xms32g -Xmx32g \
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -XX:ConcGCThreads=4 \
  -XX:+ExitOnOutOfMemoryError \
  -Xlog:gc*:file=/var/log/gc.log:time"

# Option B: G1GC with aggressive tuning
JAVA_OPTS="\
  -Xms32g -Xmx32g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=20 \
  -XX:G1HeapRegionSize=32m \
  -XX:InitiatingHeapOccupancyPercent=25 \
  -XX:G1NewSizePercent=20 \
  -XX:G1MaxNewSizePercent=40 \
  -XX:+DisableExplicitGC"
```

---

### Scenario 5: Avoid Full GC (Most Critical Tuning Goal)

```java
public class AvoidFullGCPatterns {

    // ── Pattern 1: Avoid System.gc() calls ────────────────────────────────────
    // ❌ Triggers Full GC in many configurations
    System.gc(); // NEVER call in production code!

    // ✅ If needed, make it concurrent:
    // -XX:+ExplicitGCInvokesConcurrent (G1/CMS)

    // ── Pattern 2: Avoid humongous object flood ────────────────────────────────
    // ❌ Large short-lived objects → Humongous → Old Gen → more Full GC risk
    void processRequest(byte[] input) {
        byte[] processed = new byte[2 * 1024 * 1024]; // 2MB — humongous with 1MB regions!
        // ... process ...
    }

    // ✅ Pool large objects
    private final java.util.concurrent.BlockingQueue<byte[]> bufferPool =
        new java.util.concurrent.LinkedBlockingQueue<>();

    void processRequestPooled(byte[] input) throws InterruptedException {
        byte[] buffer = bufferPool.poll();
        if (buffer == null) buffer = new byte[2 * 1024 * 1024]; // Create if pool empty
        try {
            // ... use buffer ...
        } finally {
            bufferPool.offer(buffer); // Return to pool
        }
    }

    // ── Pattern 3: Keep Old Gen from filling up ────────────────────────────────
    // ❌ Loading entire dataset into memory
    void loadAll() {
        java.util.List<Record> records = database.findAll(); // 10M records → Old Gen fills!
        processAll(records);
    }

    // ✅ Stream and process in chunks
    void loadAndProcess() {
        database.streamAll() // Cursor-based streaming
                .forEach(record -> {
                    processRecord(record);
                    // record becomes unreachable after forEach → eligible for Young GC
                });
    }

    // ── Pattern 4: Tune IHOP to start concurrent mark early ──────────────────
    // Default IHOP = 45% → risky if allocation rate is high
    // Lower IHOP = concurrent mark starts earlier = less Full GC risk
    // JVM flag: -XX:InitiatingHeapOccupancyPercent=30

    // ── Pattern 5: Right-size your heap ───────────────────────────────────────
    // Too small: frequent GC, Full GC risk
    // Too large: longer GC pauses (more to scan), more memory cost
    // Rule: live data size × 3–4 = good heap size
    // Example: 500MB live data → -Xmx2g to -Xmx2500m

    void database() {}
    void processRecord(Object r) {}
    void processAll(java.util.List<?> l) {}
    interface Record {}
}
```

---

## 17. GC Performance Benchmarks

```java
import java.lang.management.*;
import java.util.*;

public class GCBenchmark {

    record GCStats(long count, long timeMs) {}

    static GCStats captureGCStats() {
        long count = 0, time = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += gc.getCollectionCount();
            time  += gc.getCollectionTime();
        }
        return new GCStats(count, time);
    }

    // ── Benchmark: Allocation Rate Impact ─────────────────────────────────────
    static void benchmarkAllocationRate() {
        int ITERATIONS = 1_000_000;
        int OBJ_SIZE   = 1024; // 1KB objects

        GCStats before = captureGCStats();
        long wallStart = System.currentTimeMillis();

        // High allocation rate
        long dummy = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            byte[] obj = new byte[OBJ_SIZE];
            obj[0] = (byte) i;
            dummy += obj[0]; // Prevent dead code elimination
        }

        long wallTime  = System.currentTimeMillis() - wallStart;
        GCStats after  = captureGCStats();

        long gcCount   = after.count() - before.count();
        long gcTime    = after.timeMs() - before.timeMs();
        double overhead = wallTime > 0 ? 100.0 * gcTime / wallTime : 0;
        double allocMBps = (ITERATIONS * OBJ_SIZE / 1024.0 / 1024.0) / (wallTime / 1000.0);

        System.out.printf("Benchmark: %,d × %dB objects%n", ITERATIONS, OBJ_SIZE);
        System.out.printf("  Wall time:      %,dms%n", wallTime);
        System.out.printf("  GC count:       %d%n", gcCount);
        System.out.printf("  GC time:        %,dms%n", gcTime);
        System.out.printf("  GC overhead:    %.1f%%%n", overhead);
        System.out.printf("  Alloc rate:     %.0f MB/sec%n", allocMBps);
        System.out.printf("  Dummy (prevent DCE): %d%n", dummy);
    }

    // ── Benchmark: Object Survival Rate Impact ────────────────────────────────
    static void benchmarkSurvivalRate(double survivalRate) {
        System.out.printf("%nSurvival rate = %.0f%%%n", survivalRate * 100);

        int ITERATIONS = 500;
        int BATCH_SIZE  = 1000;
        int surviveCount = (int)(BATCH_SIZE * survivalRate);

        // "Long-lived" holder
        Deque<byte[]> survivors = new ArrayDeque<>();

        GCStats before = captureGCStats();
        long wallStart = System.currentTimeMillis();

        for (int i = 0; i < ITERATIONS; i++) {
            List<byte[]> batch = new ArrayList<>(BATCH_SIZE);
            for (int j = 0; j < BATCH_SIZE; j++) {
                batch.add(new byte[10 * 1024]); // 10KB each
            }
            // Keep 'surviveCount' objects alive
            for (int j = 0; j < surviveCount; j++) {
                survivors.add(batch.get(j));
            }
            // Trim survivors to prevent unbounded growth
            while (survivors.size() > surviveCount * 10) {
                survivors.poll();
            }
        }

        long wallTime = System.currentTimeMillis() - wallStart;
        GCStats after = captureGCStats();

        System.out.printf("  Wall time:   %,dms%n",  wallTime);
        System.out.printf("  GC count:    %d%n",      after.count() - before.count());
        System.out.printf("  GC time:     %,dms%n",  after.timeMs() - before.timeMs());
        System.out.printf("  GC overhead: %.1f%%%n",
            wallTime > 0 ? 100.0 * (after.timeMs() - before.timeMs()) / wallTime : 0);
    }

    public static void main(String[] args) {
        System.out.println("Active GC: " + SerialGCDemo.getActiveGC());
        System.out.println();

        benchmarkAllocationRate();

        // Compare: few survivors vs many survivors
        benchmarkSurvivalRate(0.01); // 1% survive → mostly Young GC (fast)
        benchmarkSurvivalRate(0.50); // 50% survive → lots of promotion → Old Gen pressure
        benchmarkSurvivalRate(0.90); // 90% survive → Old Gen fills fast → Major GC
    }
}
```

---

## 18. Common GC Problems & Fixes

### Problem 1: Frequent Full GC

```
Symptoms:
  - GC logs show "Full GC" events frequently
  - High GC overhead (> 10%)
  - Application pauses visible to users (seconds)

Root Causes & Fixes:

1. Heap too small
   Fix: -Xmx (increase heap to 3–4x your live data size)

2. Memory leak (live data keeps growing)
   Fix: Heap dump analysis → find accumulating objects

3. IHOP too high (G1 starts concurrent mark too late)
   Fix: -XX:InitiatingHeapOccupancyPercent=30 (lower from default 45)

4. System.gc() called explicitly
   Fix: -XX:+DisableExplicitGC or find and remove the call

5. Humongous object flood (G1)
   Fix: Increase -XX:G1HeapRegionSize=16m
        Or pool large objects

6. Concurrent mode failure (G1/CMS)
   Fix: Lower IHOP, increase heap, reduce allocation rate
```

### Problem 2: Long GC Pauses

```
Symptoms:
  - Individual GC pauses > MaxGCPauseMillis target
  - P99 latency spikes

Root Causes & Fixes:

1. Large heap with G1GC
   Fix: Switch to -XX:+UseZGC for heap > 16GB

2. Large Young Gen (more objects to scan per Minor GC)
   Fix: -XX:G1MaxNewSizePercent=30 (cap Young Gen)

3. High promotion rate (many objects surviving to Old Gen)
   Fix: Investigate what's keeping objects alive,
        -XX:MaxTenuringThreshold=5 (promote sooner to avoid repeated copy)

4. Time to safepoint (TTSP) is high
   Fix: Break up long-running loops, check for safepoint-hostile code
        -XX:+SafepointTimeout -XX:SafepointTimeoutDelay=500

5. JVM warming up (JIT not yet optimized)
   Fix: Warm up period before measuring/alerting,
        -XX:+TieredCompilation (default on)
```

### Problem 3: High GC CPU Overhead

```
Symptoms:
  - CPU usage consistently high even without application load
  - GC threads consuming cores

Root Causes & Fixes:

1. Too many GC threads
   Fix: -XX:ParallelGCThreads=4 (reduce for smaller machines)
        -XX:ConcGCThreads=2 (reduce concurrent threads for ZGC/Shenandoah)

2. IHOP too low (concurrent marks too frequently)
   Fix: -XX:InitiatingHeapOccupancyPercent=50 (raise IHOP)

3. High allocation rate
   Fix: Object pooling, reduce unnecessary allocations,
        profile with -XX:+PrintTLAB

4. String deduplication overhead
   Fix: -XX:-UseStringDeduplication if it's consuming too much CPU
```

### Problem 4: G1 Humongous Allocation Issues

```java
public class HumongousDiagnosis {

    // Detect humongous allocation trigger in GC log:
    // [gc] GC(5) Pause Young (Concurrent Start) (G1 Humongous Allocation) 180M->175M(256M) 12ms
    //                                             ↑ Humongous object triggered concurrent mark early!

    // Find what's creating humongous objects:
    // 1. Enable GC log and search for "Humongous Allocation"
    // 2. Use Java Flight Recorder:
    //    -XX:StartFlightRecording=duration=60s,filename=rec.jfr
    //    Then analyze in JDK Mission Control

    // Common humongous objects:
    //   - Large byte[] for HTTP request/response bodies
    //   - Large String values (XML, JSON responses)
    //   - Large collection backing arrays when ArrayList grows
    //   - NIO ByteBuffers

    static void avoidHumongousArrayList() {
        // ❌ ArrayList starts at 10, grows: 15, 22, 33, 49, 73, 109 ...
        // The backing array becomes humongous when it exceeds region/2

        // ✅ Pre-size ArrayList if approximate size is known
        int expectedSize = 100_000;
        List<String> list = new ArrayList<>(expectedSize);
        // Internal array: 100_000 × 4 bytes ≈ 400KB — humongous only if region < 800KB
        // With -XX:G1HeapRegionSize=2m: 400KB < 1MB threshold = NOT humongous ✅
    }
}
```

---

## 19. Interview Questions & Answers

| # | Question | Answer |
|---|----------|--------|
| 1 | What is the generational hypothesis? | Most objects die young — empirically observed in most programs. This justifies dividing the heap into Young (collected frequently, cheaply) and Old (collected rarely) generations. |
| 2 | What is a Minor GC vs Major GC vs Full GC? | Minor GC: collects only Young Generation (fast). Major GC: collects Old Generation. Full GC: collects entire heap including Metaspace (slowest, should be rare). |
| 3 | What are GC Roots? | Starting points for GC reachability tracing: active thread stacks (local variables), static fields, JNI references, synchronized monitors. Objects reachable from roots are kept; others are collected. |
| 4 | What is Stop-the-World (STW)? | All application threads are paused so GC can safely modify the heap. During STW, the application doesn't process requests — this is the source of GC-induced latency. |
| 5 | How does G1GC differ from Parallel GC? | G1GC: region-based heap, predictable pause targets, concurrent marking, better for mixed workloads and large heaps. Parallel GC: simpler, whole-generation collection, higher throughput but unpredictable pauses. |
| 6 | What is the difference between ZGC and G1GC? | ZGC: sub-millisecond pauses (almost all work concurrent), higher CPU overhead, handles terabyte heaps. G1GC: 10–200ms pauses, lower overhead, better throughput, more mature. Choose ZGC for latency-critical apps. |
| 7 | What is `InitiatingHeapOccupancyPercent` (IHOP)? | The Old Gen occupancy % at which G1 starts the concurrent mark cycle (default 45%). Lower IHOP = marks start earlier = less Full GC risk but more CPU usage. |
| 8 | What is a Humongous Object in G1GC? | An object larger than 50% of a G1 region size. Allocated directly in Old Generation, bypassing Young Gen. Can trigger concurrent mark cycles early and cause fragmentation. |
| 9 | What does `-XX:MaxGCPauseMillis` do? | Sets a **soft** pause time target for G1GC. The JVM adjusts Young Gen size to try to meet this goal — it's not a hard guarantee. Lower value → smaller Young Gen → more frequent but shorter GCs. |
| 10 | What is Escape Analysis? | JIT optimization that determines if an object "escapes" its method (returned, stored in field). Non-escaping objects may be stack-allocated or scalar-replaced — no heap allocation, no GC pressure. |
| 11 | What is a TLAB? | Thread-Local Allocation Buffer — each thread has a private Eden sub-region for object allocation. Allocation is O(1) (bump pointer) without synchronization. Reduces contention on Eden. |
| 12 | Difference between `WeakReference` and `SoftReference`? | `WeakReference`: GC collects at next cycle when no strong refs. `SoftReference`: GC collects only when JVM is low on memory (good for caches). |
| 13 | What causes Concurrent Mode Failure? | (G1/CMS) The Old Generation fills up before the concurrent GC cycle finishes. Results in an emergency Full GC. Fix: lower IHOP, increase heap, reduce allocation rate. |
| 14 | What is String Deduplication? | `-XX:+UseStringDeduplication` (G1GC): G1 identifies String objects on heap with identical char arrays and makes them share one array. Reduces heap usage when many duplicate strings exist. |
| 15 | How do you tune GC for containers? | Use `-XX:+UseContainerSupport` (Java 10+) so JVM respects container memory limits. Set `-XX:MaxRAMPercentage=75.0` to use 75% of container memory as heap. Without this, JVM may see host machine RAM and ignore container limits. |
| 16 | What is the `-Xms == -Xmx` recommendation? | Setting initial heap = max heap avoids heap resize GC cycles (which are Full GCs). Prevents the JVM from growing the heap under load, which causes pauses. |
| 17 | What is Generational ZGC (Java 21)? | ZGC in Java 21 adds generational support — separate Young and Old generations. Improved throughput over non-generational ZGC with same sub-ms pauses. Enable with `-XX:+UseZGC -XX:+ZGenerational`. |
| 18 | What is a safepoint? | A point in JIT-compiled code where all thread state is known. JVM brings all threads to safepoints for STW operations (GC, deoptimization). TTSP (time to safepoint) contributes to visible pause time. |
| 19 | What is `-XX:+PrintAdaptiveSizePolicy`? | Shows how G1/Parallel GC automatically resizes Young/Old Gen based on observed allocation and survival rates. Useful to understand why the JVM chose certain heap sizes. |
| 20 | How do you eliminate Full GC in production? | Set `Xms=Xmx`, lower IHOP (`-XX:InitiatingHeapOccupancyPercent=30`), add `-XX:+DisableExplicitGC`, fix memory leaks (heap dump analysis), increase G1HeapRegionSize to avoid humongous allocations, switch to ZGC for large heaps. |

---

## 20. Complete Reference Summary

### GC Collector Comparison

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  Collector      STW Pause   Throughput  Heap Size   Java     Use Case        │
├──────────────────────────────────────────────────────────────────────────────┤
│  Serial         Long        Low         < 256MB     All      Embedded, CLI   │
│  Parallel       Medium      ✅ High     Any         All      Batch, CPU work │
│  CMS            Short       Good        Medium      8–13     (Removed 14)    │
│  G1GC           Short       Good        4GB–64GB    9+✅     Web, general    │
│  ZGC            < 1ms ✅    Good        1MB–16TB    15+✅    Latency-critical│
│  Shenandoah     < 10ms      Good        Any         12+      Low latency     │
│  Generational   < 1ms ✅    ✅ Better   Any         21+✅    Best overall    │
│  ZGC (Java 21)                                                                │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Decision Tree

```
Which GC should I use?
│
├── Java 21? → -XX:+UseZGC -XX:+ZGenerational (best overall for most apps)
│
├── Need sub-5ms pauses? → -XX:+UseZGC (Java 15+) or -XX:+UseShenandoahGC
│
├── Maximizing throughput (batch/compute)?
│     → -XX:+UseParallelGC -XX:GCTimeRatio=19
│
├── General web application, Java 9+?
│     → -XX:+UseG1GC -XX:MaxGCPauseMillis=200 (DEFAULT — probably fine)
│
├── Heap > 32GB?
│     → -XX:+UseZGC (G1 may have longer pauses at very large heaps)
│
└── Tiny heap < 256MB or single CPU?
      → -XX:+UseSerialGC
```

### Essential Tuning Checklist

```
□ Always set -Xmx (never run without it)
□ Set -Xms = -Xmx (avoid resize GC)
□ Add -XX:+HeapDumpOnOutOfMemoryError
□ Add -XX:+ExitOnOutOfMemoryError
□ Enable GC logging: -Xlog:gc*:file=gc.log:time
□ Set -XX:MaxRAMPercentage=75.0 in containers
□ Set -XX:MaxMetaspaceSize (for apps with dynamic class generation)
□ Lower IHOP if seeing Full GC: -XX:InitiatingHeapOccupancyPercent=30
□ Add -XX:+DisableExplicitGC (no System.gc() in production)
□ Right-size region: -XX:G1HeapRegionSize = heap/2048 (G1)
□ Monitor GC overhead (< 5% is good, > 10% needs attention)
□ Profile allocation hot spots with Java Flight Recorder
□ Validate GC settings in staging with production-like data volume
```

### Memory Architecture Quick Reference

```
JVM Memory Regions & Flags
├── Young Gen (Eden + Survivors) ─── -XX:NewRatio, -XX:SurvivorRatio
│   └── Minor GC (fast STW, frequent)
├── Old Gen (Tenured) ────────────── -Xmx minus Young
│   └── Major/Mixed GC (slower)
├── Metaspace ────────────────────── -XX:MaxMetaspaceSize
│   └── Cleaned during Full GC
├── Direct Memory ────────────────── -XX:MaxDirectMemorySize
│   └── Not GC-managed (explicit or Cleaner)
└── Thread Stacks ─────────────────  -Xss per thread
    └── Not GC-managed

GC Phases (G1):
  Young GC → Concurrent Mark → Mixed GC → (repeat)
  Avoid:  Full GC (entire heap STW)

Key Metrics to Monitor:
  • GC overhead % (target < 5%)
  • Minor GC frequency and pause time
  • Major/Mixed GC frequency
  • Full GC count (target: 0)
  • Heap occupancy trend (growing = leak)
  • Allocation rate (MB/sec) and promotion rate
```

---

*Made with ❤️ for Java developers — covers Java 8 through Java 21*
