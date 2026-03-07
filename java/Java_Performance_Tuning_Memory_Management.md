# ⚡ Java Application Performance Tuning & Memory Management
## Deep Dive Complete Guide

> Java 8 through Java 21 — Profiling, JVM Tuning, Memory, GC, Concurrency & Production Patterns

---

## 📌 Table of Contents

1. [Performance Fundamentals](#1-performance-fundamentals)
2. [JVM Architecture & Memory Model](#2-jvm-architecture--memory-model)
3. [Heap Memory Tuning](#3-heap-memory-tuning)
4. [Garbage Collection Tuning](#4-garbage-collection-tuning)
5. [Object Allocation Optimization](#5-object-allocation-optimization)
6. [String Optimization](#6-string-optimization)
7. [Collections Performance](#7-collections-performance)
8. [CPU & Threading Performance](#8-cpu--threading-performance)
9. [I/O Performance](#9-io-performance)
10. [JIT Compiler Optimization](#10-jit-compiler-optimization)
11. [Database & Connection Pool Tuning](#11-database--connection-pool-tuning)
12. [Caching Strategies](#12-caching-strategies)
13. [Profiling Tools & Techniques](#13-profiling-tools--techniques)
14. [Memory Leak Detection](#14-memory-leak-detection)
15. [Benchmarking with JMH](#15-benchmarking-with-jmh)
16. [Spring Boot Performance](#16-spring-boot-performance)
17. [Container & Cloud Tuning](#17-container--cloud-tuning)
18. [Performance Anti-Patterns](#18-performance-anti-patterns)
19. [Production Monitoring](#19-production-monitoring)
20. [Interview Questions & Answers](#20-interview-questions--answers)
21. [Complete Reference Summary](#21-complete-reference-summary)

---

## 1. Performance Fundamentals

### The Performance Tuning Cycle

```
┌─────────────────────────────────────────────────────────────────────┐
│                   PERFORMANCE TUNING CYCLE                          │
│                                                                     │
│   1. MEASURE ──► 2. IDENTIFY ──► 3. OPTIMIZE ──► 4. VERIFY        │
│        │              │                │               │            │
│   Establish       Find the          Apply the      Confirm         │
│   baseline        bottleneck        fix            improvement     │
│   metrics         (profiler)        (code/JVM)     (re-benchmark)  │
│                                                                     │
│   NEVER optimize without measuring first!                          │
│   "Premature optimization is the root of all evil" — Knuth         │
└─────────────────────────────────────────────────────────────────────┘
```

### Key Performance Metrics

```java
import java.lang.management.*;

public class PerformanceBaseline {

    public static void captureBaseline() {
        Runtime rt = Runtime.getRuntime();

        // Memory
        long heapUsed  = rt.totalMemory() - rt.freeMemory();
        long heapMax   = rt.maxMemory();
        double heapPct = 100.0 * heapUsed / heapMax;

        // GC
        long totalGCTime  = ManagementFactory.getGarbageCollectorMXBeans()
            .stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
        long totalGCCount = ManagementFactory.getGarbageCollectorMXBeans()
            .stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();

        // CPU
        com.sun.management.OperatingSystemMXBean os =
            (com.sun.management.OperatingSystemMXBean)
            ManagementFactory.getOperatingSystemMXBean();
        double cpuLoad = os.getCpuLoad() * 100;

        // Threads
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();

        // JVM uptime
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();

        System.out.println("══════ Performance Baseline ══════");
        System.out.printf("Heap:     %,dMB / %,dMB (%.1f%%)%n",
            heapUsed/1024/1024, heapMax/1024/1024, heapPct);
        System.out.printf("GC:       %d collections, %,dms total%n",
            totalGCCount, totalGCTime);
        System.out.printf("CPU load: %.1f%%%n", cpuLoad);
        System.out.printf("Threads:  %d live, %d peak%n",
            threads.getThreadCount(), threads.getPeakThreadCount());
        System.out.printf("Uptime:   %,dms%n", uptimeMs);

        if (heapPct > 80)                  System.out.println("WARNING: Heap > 80%");
        if (cpuLoad > 80)                  System.out.println("WARNING: CPU > 80%");
        if (threads.getThreadCount() > 500)System.out.println("WARNING: Threads > 500");
    }

    // Measure method execution time
    public static <T> T timed(String label,
                              java.util.concurrent.Callable<T> task) throws Exception {
        long start  = System.nanoTime();
        T result    = task.call();
        long elapsed= System.nanoTime() - start;
        System.out.printf("[PERF] %-40s %,9.3f ms%n", label, elapsed / 1_000_000.0);
        return result;
    }
}
```

### Where Time Goes in a Typical Java App

```
Time distribution in a typical web service:

  DB queries          ████████████████████ 40–60%   ← Biggest bottleneck
  Network I/O         ████████████         20–30%
  Business logic      ████████             15–20%
  Serialization       ████                  5–10%
  GC pauses           ███                   3–8%
  Thread contention   ██                    2–5%
  Class loading       █                     1–3%

→ Always profile first — never guess the bottleneck
```

---

## 2. JVM Architecture & Memory Model

### Full JVM Memory Layout

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                         JVM PROCESS MEMORY                                   │
│                                                                              │
│  ┌─────────────────────────────────────────────────┐  (-Xms / -Xmx)        │
│  │                    HEAP                          │                        │
│  │  ┌──────────────────────┐  ┌──────────────────┐ │                        │
│  │  │   YOUNG GENERATION   │  │  OLD GENERATION  │ │                        │
│  │  │  ┌──────┐ ┌──┐ ┌──┐ │  │  Long-lived      │ │                        │
│  │  │  │ Eden │ │S0│ │S1│ │  │  objects         │ │                        │
│  │  │  └──────┘ └──┘ └──┘ │  │                  │ │                        │
│  │  └──────────────────────┘  └──────────────────┘ │                        │
│  └─────────────────────────────────────────────────┘                        │
│                                                                              │
│  ┌──────────────────┐  ┌──────────────────┐  ┌────────────────────────────┐ │
│  │   METASPACE      │  │  CODE CACHE      │  │  DIRECT MEMORY             │ │
│  │  Class metadata  │  │  JIT-compiled    │  │  NIO ByteBuffers           │ │
│  │  -XX:MaxMeta..   │  │  native code     │  │  -XX:MaxDirectMemorySize  │ │
│  └──────────────────┘  └──────────────────┘  └────────────────────────────┘ │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  THREAD STACKS  (one per thread, -Xss each)                          │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  Total JVM = Heap + Metaspace + CodeCache + DirectMemory + N × ThreadStack  │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Java Memory Model (JMM) — Visibility & Ordering

```java
public class JMMDemo {

    // ❌ Without synchronization — visibility NOT guaranteed
    private boolean stop = false;

    // ✅ volatile — guaranteed visibility across threads
    private volatile boolean stopVolatile = false;

    // ✅ AtomicBoolean — thread-safe read-modify-write
    private final java.util.concurrent.atomic.AtomicBoolean stopAtomic =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    // Happens-before rules:
    // 1. Program order:  x=1; y=2  → x=1 hb y=2 in same thread
    // 2. Monitor lock:   unlock(m) hb lock(m) by another thread
    // 3. volatile write: write(v)  hb read(v) by another thread
    // 4. Thread start:   start()   hb any action in started thread
    // 5. Thread join:    all actions in thread hb thread.join() return

    // ✅ Double-checked locking (requires volatile — Java 5+)
    private volatile ExpensiveService instance;

    public ExpensiveService getInstance() {
        if (instance == null) {
            synchronized (this) {
                if (instance == null) {
                    instance = new ExpensiveService(); // volatile write
                }
            }
        }
        return instance; // volatile read
    }

    // ✅ final fields — safely published without synchronization
    public static class ImmutablePoint {
        final double x;
        final double y;
        ImmutablePoint(double x, double y) { this.x = x; this.y = y; }
    }

    static class ExpensiveService {}
}
```

---

## 3. Heap Memory Tuning

### Heap Sizing Strategy

```bash
# Rule of thumb: heap = 3–4× live data set size
# Live data = memory used after a full GC
# Example: 200MB live data → -Xmx800m to -Xmx1g

# ALWAYS set Xms = Xmx (prevents resize GC pauses)
-Xms2g -Xmx2g

# Young generation sizing
-XX:NewRatio=2          # Old = 2× Young (default for most collectors)
-XX:SurvivorRatio=8     # Eden=80%, S0=10%, S1=10% of Young (default)
-XX:MaxTenuringThreshold=15  # GC cycles before promotion to Old (default 15)

# Container-aware (Java 10+)
-XX:+UseContainerSupport
-XX:MaxRAMPercentage=75.0       # Use 75% of container RAM as heap max
-XX:InitialRAMPercentage=50.0   # Start at 50%
```

### Memory Pool Monitor

```java
import java.lang.management.*;

public class MemoryPoolMonitor {

    public static void printMemoryPools() {
        System.out.printf("%-35s %8s %8s %8s %8s %8s%n",
            "Pool", "Init MB", "Used MB", "Commit MB", "Max MB", "Used%");
        System.out.println("─".repeat(85));

        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage u = pool.getUsage();
            long max      = u.getMax();
            double pct    = max > 0 ? 100.0 * u.getUsed() / max : -1;

            System.out.printf("%-35s %8.0f %8.0f %8.0f %8s %7s%n",
                pool.getName(),
                u.getInit()      / 1024.0 / 1024,
                u.getUsed()      / 1024.0 / 1024,
                u.getCommitted() / 1024.0 / 1024,
                max > 0 ? String.format("%,.0f", max/1024.0/1024) : "unlimited",
                pct >= 0 ? String.format("%.1f%%", pct) : "N/A"
            );

            if      (pct > 90) System.out.println("  CRITICAL: >90% full!");
            else if (pct > 75) System.out.println("  WARNING:  >75% full");
        }
    }

    public static void setOldGenAlert(double pct) {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getName().contains("Old") || pool.getName().contains("Tenured")) {
                if (pool.isUsageThresholdSupported() && pool.getUsage().getMax() > 0) {
                    long threshold = (long)(pool.getUsage().getMax() * pct / 100);
                    pool.setUsageThreshold(threshold);
                    System.out.printf("Alert set on '%s' at %.0f%% (%,dMB)%n",
                        pool.getName(), pct, threshold/1024/1024);
                }
            }
        }
    }

    public static void main(String[] args) {
        printMemoryPools();
        setOldGenAlert(80.0);
    }
}
```

---

## 4. Garbage Collection Tuning

### Choosing the Right GC

```bash
# Java 21 — Best default for new projects:
-XX:+UseZGC -XX:+ZGenerational    # Sub-ms pauses + better throughput

# General web app, Java 9+:
-XX:+UseG1GC -XX:MaxGCPauseMillis=200

# Maximum throughput (batch / CPU-bound):
-XX:+UseParallelGC -XX:GCTimeRatio=19

# Tiny heap (<256MB) or single CPU:
-XX:+UseSerialGC

# G1GC production config
JAVA_OPTS="\
  -Xms4g -Xmx4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:G1HeapRegionSize=8m \
  -XX:InitiatingHeapOccupancyPercent=40 \
  -XX:+UseStringDeduplication \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/dumps/ \
  -XX:+ExitOnOutOfMemoryError \
  -Xlog:gc*:file=/var/log/gc.log:time,level,tags:filecount=5,filesize=20m"

# ZGC production config (Java 21)
JAVA_OPTS="\
  -Xms8g -Xmx8g \
  -XX:+UseZGC -XX:+ZGenerational \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/dumps/ \
  -XX:+ExitOnOutOfMemoryError \
  -Xlog:gc*:file=/var/log/gc.log:time"
```

### GC Pressure Monitor

```java
import java.lang.management.*;

public class GCPressureMonitor {

    private long lastGCTime = 0, lastGCCount = 0;
    private long lastCheck  = System.currentTimeMillis();

    public record GCReport(double overheadPct, long gcCountPerMin,
                           long avgPauseMs,    boolean underPressure) {}

    public GCReport sample() {
        long now         = System.currentTimeMillis();
        long elapsed     = now - lastCheck;

        long currentTime  = ManagementFactory.getGarbageCollectorMXBeans()
            .stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
        long currentCount = ManagementFactory.getGarbageCollectorMXBeans()
            .stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();

        long deltaTime  = currentTime  - lastGCTime;
        long deltaCount = currentCount - lastGCCount;

        double overhead = elapsed > 0 ? 100.0 * deltaTime / elapsed : 0;
        long perMin     = elapsed > 0 ? deltaCount * 60_000 / elapsed : 0;
        long avgPause   = deltaCount > 0 ? deltaTime / deltaCount : 0;

        lastGCTime = currentTime; lastGCCount = currentCount; lastCheck = now;
        return new GCReport(overhead, perMin, avgPause, overhead > 5.0);
    }

    public void startMonitoring(int intervalSec) {
        java.util.concurrent.Executors
            .newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "gc-monitor"); t.setDaemon(true); return t;
            })
            .scheduleAtFixedRate(() -> {
                GCReport r = sample();
                System.out.printf("[GC] overhead=%.2f%% | rate=%d/min | avgPause=%dms%s%n",
                    r.overheadPct(), r.gcCountPerMin(), r.avgPauseMs(),
                    r.underPressure() ? " ⚠ PRESSURE!" : "");
            }, intervalSec, intervalSec, java.util.concurrent.TimeUnit.SECONDS);
    }
}
```

### GC Log Reading Guide

```
G1GC log (Java 9+ unified logging):

[0.234s][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 45M->12M(256M) 8.234ms
              │        │                  │                     │   │    │       │
              │        │                  │                     │   │    │       └─ Pause ms
              │        │                  │                     │   │    └─ Total heap
              │        │                  │                     │   └─ After GC heap used
              │        │                  │                     └─ Before GC heap used
              │        │                  └─ GC cause
              │        └─ GC(N): Nth GC event
              └─ Timestamp

RED FLAGS:
  "Full GC"                   → Something wrong — entire heap collected
  "G1 Humongous Allocation"   → Large objects triggering early concurrent mark
  "Allocation Failure"        → Eden full, couldn't allocate
  "Promotion Failed"          → Survivor/Old full → emergency Full GC
  Pause > 500ms               → Tune MaxGCPauseMillis or heap size
```

---

## 5. Object Allocation Optimization

```java
import java.util.*;

public class AllocationOptimization {

    // ── 1. ThreadLocal pool for reusable objects ──────────────────────────────
    // ❌ New StringBuilder every call
    static String buildBad(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        parts.forEach(p -> sb.append(p).append(","));
        return sb.toString();
    }

    // ✅ Reuse via ThreadLocal (thread-safe, no allocation)
    private static final ThreadLocal<StringBuilder> SB_POOL =
        ThreadLocal.withInitial(() -> new StringBuilder(256));

    static String buildGood(List<String> parts) {
        StringBuilder sb = SB_POOL.get();
        sb.setLength(0); // Reset — reuses internal char[]
        parts.forEach(p -> sb.append(p).append(","));
        return sb.toString();
    }

    // ── 2. Primitives over boxed types ───────────────────────────────────────
    // ❌ Auto-boxing: Integer objects on heap
    static long sumBoxed(List<Integer> list) {
        long total = 0;
        for (Integer n : list) total += n; // Unboxes each Integer
        return total;
    }

    // ✅ int[] — no boxing, ~5x less memory
    static long sumPrimitive(int[] arr) {
        long total = 0;
        for (int n : arr) total += n;
        return total;
    }

    // ✅ IntStream.sum() — no boxing, vectorized by JIT
    static long sumStream(int[] arr) {
        return java.util.Arrays.stream(arr).asLongStream().sum();
    }

    // ── 3. Object pooling for expensive objects ───────────────────────────────
    public static class ObjectPool<T> {
        private final java.util.concurrent.BlockingQueue<T> pool;
        private final java.util.function.Supplier<T> factory;
        private final java.util.function.Consumer<T> reset;

        public ObjectPool(int size,
                          java.util.function.Supplier<T> factory,
                          java.util.function.Consumer<T> reset) {
            this.pool    = new java.util.concurrent.ArrayBlockingQueue<>(size);
            this.factory = factory;
            this.reset   = reset;
            for (int i = 0; i < size; i++) pool.offer(factory.get());
        }

        public <R> R withObject(java.util.function.Function<T, R> action) {
            T obj = pool.poll();
            if (obj == null) obj = factory.get(); // Pool empty — create new
            try {
                return action.apply(obj);
            } finally {
                reset.accept(obj);
                pool.offer(obj); // Return to pool
            }
        }
    }

    // Usage: pool of 64KB byte arrays for network I/O
    static ObjectPool<byte[]> bufferPool = new ObjectPool<>(
        20,
        () -> new byte[64 * 1024],
        buf -> Arrays.fill(buf, (byte)0)
    );

    // ── 4. Records — compact immutable value objects ──────────────────────────
    record Point(double x, double y) {
        double distanceTo(Point other) {
            double dx = x - other.x, dy = y - other.y;
            return Math.sqrt(dx*dx + dy*dy);
        }
    }

    // ── 5. Lazy initialization ────────────────────────────────────────────────
    class LazyReport {
        private List<String> headers;
        public List<String> getHeaders() {
            if (headers == null) headers = new ArrayList<>(50); // Created only when needed
            return headers;
        }
    }

    // ── 6. Avoid varargs allocation in hot paths ──────────────────────────────
    // ❌ varargs always creates an array
    static void logBad(String fmt, Object... args) { /* array always allocated! */ }

    // ✅ Specific overloads (like SLF4J)
    static void log1(String fmt, Object a)            { /* no array */ }
    static void log2(String fmt, Object a, Object b)  { /* no array */ }
}
```

---

## 6. String Optimization

```java
public class StringOptimization {

    // ── 1. String concatenation in loops ─────────────────────────────────────
    // ❌ O(n²) — new String on every iteration
    static String bad(List<String> items) {
        String result = "";
        for (String item : items) result += item + ",";
        return result;
    }

    // ✅ O(n) — StringBuilder
    static String good(List<String> items) {
        StringBuilder sb = new StringBuilder(items.size() * 20);
        for (String item : items) sb.append(item).append(',');
        if (!sb.isEmpty()) sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
    }

    // ✅ Most readable — same performance as StringBuilder
    static String best(List<String> items) {
        return String.join(",", items);
    }

    // ── 2. Regex — compile once, reuse ───────────────────────────────────────
    // ❌ Compiles regex on every call
    static boolean containsDigitBad(String s) {
        return s.matches(".*[0-9].*");
    }

    // ✅ Static compiled pattern — compile once
    private static final java.util.regex.Pattern DIGIT =
        java.util.regex.Pattern.compile("[0-9]");
    static boolean containsDigitOk(String s) {
        return DIGIT.matcher(s).find();
    }

    // ✅ For simple checks — plain char loop is fastest
    static boolean containsDigitFast(String s) {
        for (int i = 0; i < s.length(); i++)
            if (Character.isDigit(s.charAt(i))) return true;
        return false;
    }

    // ── 3. String.format vs concatenation ────────────────────────────────────
    // ❌ String.format is slow (regex parser internally)
    static String formatSlow(String name, int age) {
        return String.format("Name: %s, Age: %d", name, age);
    }

    // ✅ Concatenation — faster for simple cases
    static String formatFast(String name, int age) {
        return "Name: " + name + ", Age: " + age;
    }

    // ✅ Text block for multi-line (Java 15+)
    static String jsonTemplate(String name, int age) {
        return """
            { "name": "%s", "age": %d }
            """.formatted(name, age);
    }

    // ── 4. JVM string deduplication ──────────────────────────────────────────
    // -XX:+UseStringDeduplication (G1GC only)
    // GC identifies char[] with identical content and shares them — zero code change
    // Good when: lots of duplicate strings from network / DB / file reading
}
```

---

## 7. Collections Performance

```java
import java.util.*;
import java.util.concurrent.*;

public class CollectionsPerformance {

    /*
    Operation         ArrayList  LinkedList  HashMap  TreeMap  EnumMap
    Random access     O(1) ✅    O(n) ❌     O(1)✅   O(logn)  O(1)✅
    Add to end        O(1) ✅    O(1) ✅
    Add to front      O(n) ❌    O(1) ✅
    Contains          O(n)       O(n)        O(1) ✅  O(logn)  O(1)✅
    Sorted iteration  O(nlogn)   O(nlogn)    ❌        O(n) ✅
    */

    // ── 1. Pre-size collections ───────────────────────────────────────────────
    // ❌ Resizes: 16 → 24 → 36 → 54 ... copying each time
    List<String> badList = new ArrayList<>();
    Map<String, Integer> badMap = new HashMap<>();

    // ✅ Pre-sized: no resizing
    List<String> goodList = new ArrayList<>(10_000);
    Map<String, Integer> goodMap = new HashMap<>(10_000 * 4 / 3 + 1);

    // ── 2. EnumMap / EnumSet — fastest for enum keys ─────────────────────────
    enum Status { ACTIVE, INACTIVE, PENDING }

    Map<Status, List<Object>> byStatusBad  = new HashMap<>();     // hashes the enum
    Map<Status, List<Object>> byStatusGood = new EnumMap<>(Status.class); // array-backed O(1)
    Set<Status> activeStatuses = EnumSet.of(Status.ACTIVE, Status.PENDING); // bit-vector

    // ── 3. Immutable collections (Java 9+) ───────────────────────────────────
    // ✅ List.of / Set.of / Map.of — truly immutable, compact representation
    List<String> immutable    = List.of("a", "b", "c");     // ~40% less memory than ArrayList
    Set<String>  immutableSet = Set.of("x", "y", "z");

    // ── 4. ConcurrentHashMap vs synchronizedMap ───────────────────────────────
    Map<String, Integer> syncMap = Collections.synchronizedMap(new HashMap<>()); // whole-map lock
    ConcurrentHashMap<String, Integer> concMap = new ConcurrentHashMap<>();      // segment lock

    // ✅ Atomic update without separate lock
    concMap.compute("key", (k, v) -> v == null ? 1 : v + 1);
    concMap.merge("key", 1, Integer::sum);

    // ── 5. Primitive arrays vs boxed collections ──────────────────────────────
    // int[1_000_000] = 4MB
    // List<Integer> 1_000_000 = ~20MB (Integer objects + list overhead)
    int[] primitiveArr = new int[1_000_000]; // 5x less memory!
}
```

---

## 8. CPU & Threading Performance

### Thread Pool Sizing

```java
import java.util.concurrent.*;

public class ThreadingOptimization {

    static int cores = Runtime.getRuntime().availableProcessors();

    // Formula:
    //   CPU-bound: threads = cores
    //   I/O-bound: threads = cores × (1 + wait_time / cpu_time)
    //   Example: 8 cores, 80% wait → 8 × (1 + 4) = 40 threads

    // ── CPU-bound pool ────────────────────────────────────────────────────────
    static ExecutorService cpuPool = new ThreadPoolExecutor(
        cores, cores, 0L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(1000),
        r -> { Thread t = new Thread(r, "cpu-worker"); t.setDaemon(true); return t; },
        new ThreadPoolExecutor.CallerRunsPolicy()  // Back-pressure
    );

    // ── I/O-bound pool ────────────────────────────────────────────────────────
    static ExecutorService ioPool = new ThreadPoolExecutor(
        cores * 5, cores * 20,
        60L, TimeUnit.SECONDS,
        new SynchronousQueue<>(),
        Executors.defaultThreadFactory(),
        new ThreadPoolExecutor.AbortPolicy()
    );

    // ── Java 21: Virtual threads for I/O-bound (no sizing needed) ────────────
    static ExecutorService virtualPool =
        Executors.newVirtualThreadPerTaskExecutor();

    // ── Pool health monitoring ────────────────────────────────────────────────
    static void monitorPool(ThreadPoolExecutor pool, String name) {
        System.out.printf("[Pool: %-15s] active=%d/%d queued=%d completed=%d%n",
            name, pool.getActiveCount(), pool.getPoolSize(),
            pool.getQueue().size(), pool.getCompletedTaskCount());
    }

    // ── Lock-free counters ────────────────────────────────────────────────────
    private final java.util.concurrent.atomic.AtomicLong counter =
        new java.util.concurrent.atomic.AtomicLong();
    void increment() { counter.incrementAndGet(); }

    // ✅ LongAdder: faster under high contention (per-cell accumulation)
    private final java.util.concurrent.atomic.LongAdder adder =
        new java.util.concurrent.atomic.LongAdder();
    void countFastest() { adder.increment(); }
    long getTotal()     { return adder.sum(); }

    // ── StampedLock — optimistic reads (faster than ReadWriteLock) ────────────
    static class StampedPoint {
        private double x, y;
        private final java.util.concurrent.locks.StampedLock lock =
            new java.util.concurrent.locks.StampedLock();

        void move(double dx, double dy) {
            long stamp = lock.writeLock();
            try { x += dx; y += dy; }
            finally { lock.unlockWrite(stamp); }
        }

        double distanceFromOrigin() {
            long stamp = lock.tryOptimisticRead(); // No lock!
            double cx = x, cy = y;
            if (!lock.validate(stamp)) {           // Write happened — fall back
                stamp = lock.readLock();
                try { cx = x; cy = y; }
                finally { lock.unlockRead(stamp); }
            }
            return Math.sqrt(cx*cx + cy*cy);
        }
    }
}
```

---

## 9. I/O Performance

```java
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;

public class IOOptimization {

    // ── 1. Always buffer I/O ──────────────────────────────────────────────────
    // ❌ One system call per byte
    static void readUnbuffered(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            int b; while ((b = in.read()) != -1) process(b);
        }
    }

    // ✅ Reads 64KB chunks — far fewer system calls
    static void readBuffered(Path path) throws IOException {
        try (BufferedInputStream in =
                new BufferedInputStream(Files.newInputStream(path), 65536)) {
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) != -1) process(buf, read);
        }
    }

    // ── 2. Memory-mapped files — zero-copy access ─────────────────────────────
    static void processLargeFile(Path path) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            MappedByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            while (buf.hasRemaining()) process(buf.get());
        }
    }

    // ── 3. Zero-copy file transfer (sendfile syscall) ─────────────────────────
    static void copyZeroCopy(Path src, Path dst) throws IOException {
        try (FileChannel s = FileChannel.open(src, StandardOpenOption.READ);
             FileChannel d = FileChannel.open(dst,
                 StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
            s.transferTo(0, s.size(), d); // No user-space copy!
        }
    }

    // ── 4. Stream large text files — O(1) memory ─────────────────────────────
    static void processLargeTextFile(Path path) throws IOException {
        // ❌ Loads all lines into memory
        // List<String> all = Files.readAllLines(path);

        // ✅ One line at a time
        try (var lines = Files.lines(path)) {
            lines.filter(l -> l.startsWith("ERROR:"))
                 .limit(1000)
                 .forEach(System.out::println);
        }
    }

    // ── 5. Async file reads ───────────────────────────────────────────────────
    static java.util.concurrent.CompletableFuture<String> readAsync(Path path) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return Files.readString(path); }
            catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        });
    }

    static void process(int b) {}
    static void process(byte[] buf, int len) {}
    static void process(byte b) {}
}
```

---

## 10. JIT Compiler Optimization

```java
public class JITOptimization {

    // JIT Compilation Tiers:
    // Tier 0: Interpreter         → First few invocations
    // Tier 1-3: C1 (quick JIT)   → After ~1,000 invocations
    // Tier 4: C2 (deep JIT)      → After ~10,000 invocations
    //
    // C2 optimizations: inlining, loop unrolling, escape analysis,
    //                   dead code elimination, constant folding, intrinsics

    // ── Keep methods small for inlining (<35 bytecodes default) ──────────────
    // ❌ Large method — JIT may not inline callers of this
    static double calculateBig(double x, double y, double z) {
        // 50+ lines → caller stays as method call overhead
        return Math.sqrt(x*x + y*y + z*z) + Math.atan2(y,x) + Math.acos(z);
    }

    // ✅ Small methods — aggressively inlined
    static double magnitude(double x, double y, double z) { return Math.sqrt(x*x+y*y+z*z); }
    static double angle(double y, double x)     { return Math.atan2(y, x); }

    // ── Hoist loop-invariant computations ────────────────────────────────────
    // ❌ magnitude() called N times (JIT may or may not hoist it)
    static double[] normalizeBad(double[] arr) {
        double[] result = new double[arr.length];
        for (int i = 0; i < arr.length; i++)
            result[i] = arr[i] / computeMagnitude(arr); // recomputed each iteration!
        return result;
    }

    // ✅ Compute once outside loop
    static double[] normalizeGood(double[] arr) {
        double mag    = computeMagnitude(arr);      // Computed ONCE
        double[] result = new double[arr.length];
        for (int i = 0; i < arr.length; i++)
            result[i] = arr[i] / mag;
        return result;
    }

    // ── Sorted branches — better CPU branch prediction ────────────────────────
    static int processRandom(int[] arr) {
        int sum = 0;
        for (int v : arr) if (v % 2 == 0) sum += v; // ~50% misprediction
        return sum;
    }

    static int processSorted(int[] arr) {
        java.util.Arrays.sort(arr.clone()); // Sort first: evens cluster → predictable
        int sum = 0;
        for (int v : arr) if (v % 2 == 0) sum += v;
        return sum;
    }

    // ── JIT flags (diagnostic) ───────────────────────────────────────────────
    // -XX:+PrintInlining       → See what JIT inlines
    // -XX:+PrintCompilation    → See compilation events
    // -XX:MaxInlineSize=35     → Bytecode size limit for inlining
    // -XX:FreqInlineSize=325   → Size limit for hot-path inlining

    static double computeMagnitude(double[] arr) {
        double sum = 0;
        for (double v : arr) sum += v * v;
        return Math.sqrt(sum);
    }
}
```

---

## 11. Database & Connection Pool Tuning

```java
import com.zaxxer.hikari.*;

public class DatabaseTuning {

    // ── HikariCP — fastest Java connection pool ───────────────────────────────
    static HikariDataSource configurePool() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:postgresql://localhost:5432/mydb");
        cfg.setUsername("user");
        cfg.setPassword("secret");

        // Pool size: (cores × 2) + effective_spindle_count
        // SSD: spindle ≈ 1  →  8-core + SSD → 8×2+1 = 17
        int cores = Runtime.getRuntime().availableProcessors();
        cfg.setMaximumPoolSize(cores * 2 + 1);
        cfg.setMinimumIdle(Math.max(cores / 2, 2));

        cfg.setConnectionTimeout(30_000);  // Max wait for connection
        cfg.setIdleTimeout(600_000);       // Remove idle connection after 10min
        cfg.setMaxLifetime(1_800_000);     // Replace connection after 30min
        cfg.setKeepaliveTime(60_000);      // Keepalive query every 60s

        // Performance
        cfg.setAutoCommit(false);
        cfg.addDataSourceProperty("cachePrepStmts",        "true");
        cfg.addDataSourceProperty("prepStmtCacheSize",      "500");
        cfg.addDataSourceProperty("reWriteBatchedInserts", "true");  // PostgreSQL bulk
        cfg.setRegisterMbeans(true);
        return new HikariDataSource(cfg);
    }

    // ── N+1 query problem — #1 ORM performance killer ────────────────────────
    /*
    // ❌ 1 query for orders + N queries for each customer
    List<Order> orders = orderRepo.findAll();
    orders.forEach(o -> o.getCustomer().getName()); // N lazy-load queries!

    // ✅ JOIN FETCH: one query
    @Query("SELECT o FROM Order o JOIN FETCH o.customer WHERE o.status = :s")
    List<Order> findWithCustomer(@Param("s") OrderStatus s);

    // ✅ @EntityGraph: declarative fetch
    @EntityGraph(attributePaths = {"customer", "items"})
    List<Order> findByStatus(OrderStatus status);

    // ✅ DTO projection: only needed columns
    @Query("SELECT new com.example.OrderSummary(o.id, c.name, o.total) " +
           "FROM Order o JOIN o.customer c WHERE o.status = :s")
    List<OrderSummary> findSummaries(@Param("s") OrderStatus s);
    */

    // ── Batch insert — JDBC level ─────────────────────────────────────────────
    static void batchInsert(java.sql.Connection conn, List<User> users)
            throws java.sql.SQLException {
        conn.setAutoCommit(false);
        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users(name, email) VALUES(?, ?)")) {
            for (int i = 0; i < users.size(); i++) {
                ps.setString(1, users.get(i).name());
                ps.setString(2, users.get(i).email());
                ps.addBatch();
                if (i % 500 == 0) { ps.executeBatch(); conn.commit(); }
            }
            ps.executeBatch(); conn.commit();
        }
    }

    // Hibernate batch settings (application.properties):
    // spring.jpa.properties.hibernate.jdbc.batch_size=50
    // spring.jpa.properties.hibernate.order_inserts=true
    // spring.jpa.properties.hibernate.order_updates=true
    // spring.jpa.open-in-view=false  ← ALWAYS disable OSIV!

    record User(String name, String email) {}
}
```

---

## 12. Caching Strategies

```java
import com.github.benmanes.caffeine.cache.*;
import java.time.Duration;

public class CachingPatterns {

    // ── Caffeine — fastest Java local cache ───────────────────────────────────
    static Cache<Long, User> buildCache() {
        return Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .expireAfterAccess(Duration.ofMinutes(5))
            .refreshAfterWrite(Duration.ofMinutes(8))  // Async refresh before expiry
            .recordStats()
            .build();
    }

    // ── Loading cache — auto-loads on miss ────────────────────────────────────
    static LoadingCache<Long, User> loadingCache(UserRepository repo) {
        return Caffeine.newBuilder()
            .maximumSize(5_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build(id -> repo.findById(id).orElse(null));
    }

    // ── Usage patterns ────────────────────────────────────────────────────────
    static void patterns(Cache<Long, User> cache, UserService svc) {
        // Atomic get-or-load
        User user = cache.get(42L, id -> svc.load(id));

        // Invalidate on update
        svc.update(42L);
        cache.invalidate(42L);

        // Stats
        CacheStats stats = cache.stats();
        System.out.printf("hitRate=%.2f%% miss=%d evictions=%d%n",
            stats.hitRate()*100, stats.missCount(), stats.evictionCount());
    }

    // ── Two-level cache: L1 (JVM heap) + L2 (Redis) ──────────────────────────
    static class TwoLevelCache<K, V> {
        private final Cache<K, V> l1;      // ~nanoseconds, bounded
        private final RedisCache<K, V> l2; // ~milliseconds, large

        public V get(K key) {
            V val = l1.getIfPresent(key);
            if (val != null) return val;      // L1 hit
            val = l2.get(key);
            if (val != null) { l1.put(key, val); return val; } // L2 hit → promote
            return null;                      // Miss — caller loads from DB
        }

        public void put(K key, V value) { l1.put(key, value); l2.put(key, value); }
        public void invalidate(K key)  { l1.invalidate(key);  l2.delete(key);     }

        interface RedisCache<K,V> { V get(K k); void put(K k, V v); void delete(K k); }
    }

    interface UserRepository { java.util.Optional<User> findById(Long id); }
    interface UserService    { User load(Long id); void update(Long id); }
    record User(Long id, String name) {}
}
```

---

## 13. Profiling Tools & Techniques

### Command-Line Tools

```bash
# ── jps: list Java processes ──────────────────────────────────────────────────
jps -lv

# ── jstack: thread dump (deadlock detection) ─────────────────────────────────
jstack <pid>
jstack -l <pid>      # Include lock info

# ── jmap: heap inspection ─────────────────────────────────────────────────────
jmap -heap <pid>                         # Heap summary
jmap -histo:live <pid> | head -30        # Top objects by count
jmap -dump:live,format=b,file=heap.hprof <pid>

# ── jstat: live GC stats ──────────────────────────────────────────────────────
jstat -gc       <pid> 1000 20   # GC stats every 1s, 20 times
jstat -gcutil   <pid> 1000      # Utilization % (E/O/M columns)
jstat -gccause  <pid> 2000      # GC cause

# ── jcmd: all-in-one ──────────────────────────────────────────────────────────
jcmd <pid> VM.flags
jcmd <pid> GC.heap_info
jcmd <pid> Thread.print
jcmd <pid> VM.native_memory summary
jcmd <pid> GC.heap_dump /tmp/dump.hprof

# ── async-profiler: flame graphs ─────────────────────────────────────────────
# Download: https://github.com/async-profiler/async-profiler
./asprof -d 30 -f cpu.html   <pid>    # CPU flame graph 30s
./asprof -e alloc -d 30 -f alloc.html <pid>  # Allocation profile
./asprof -e lock  -d 30 -f lock.html  <pid>  # Lock contention

# ── Java Flight Recorder (~1% overhead, production-safe) ─────────────────────
java -XX:StartFlightRecording=duration=60s,filename=app.jfr MyApp
jcmd <pid> JFR.start duration=60s filename=app.jfr
# Analyze with: jmc (JDK Mission Control GUI)
```

### Programmatic Profiling

```java
import java.lang.management.*;

public class RuntimeProfiler {

    // ── CPU time per thread ───────────────────────────────────────────────────
    static void printThreadCPU() {
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        tmx.setThreadCpuTimeEnabled(true);

        System.out.printf("%-40s %12s %12s%n", "Thread Name", "CPU ms", "User ms");
        System.out.println("-".repeat(66));

        for (long id : tmx.getAllThreadIds()) {
            ThreadInfo info = tmx.getThreadInfo(id);
            if (info == null) continue;
            long cpuNs  = tmx.getThreadCpuTime(id);
            long userNs = tmx.getThreadUserTime(id);
            if (cpuNs < 0) continue;
            System.out.printf("%-40s %12.2f %12.2f%n",
                info.getThreadName(), cpuNs/1_000_000.0, userNs/1_000_000.0);
        }
    }

    // ── Find deadlocked threads ───────────────────────────────────────────────
    static void checkDeadlocks() {
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        long[] deadlocked = tmx.findDeadlockedThreads();
        if (deadlocked == null) { System.out.println("No deadlocks."); return; }

        System.out.println("DEADLOCK DETECTED!");
        for (ThreadInfo info : tmx.getThreadInfo(deadlocked, true, true)) {
            System.out.println("  Thread:   " + info.getThreadName());
            System.out.println("  Waiting:  " + info.getLockName());
            System.out.println("  Held by:  " + info.getLockOwnerName());
        }
    }

    // ── Allocation rate for current thread ────────────────────────────────────
    static void trackAllocations(Runnable work) {
        com.sun.management.ThreadMXBean tmx =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().getId();
        long before   = tmx.getThreadAllocatedBytes(threadId);
        work.run();
        long allocated = tmx.getThreadAllocatedBytes(threadId) - before;
        System.out.printf("Allocated: %,d bytes (%.2f MB)%n",
            allocated, allocated/1024.0/1024.0);
    }
}
```

---

## 14. Memory Leak Detection

```java
import java.lang.ref.*;
import java.util.*;

public class MemoryLeakDetection {

    // ── Common leaks and fixes ────────────────────────────────────────────────

    // LEAK 1: Static collection without eviction
    static Map<String, byte[]> badCache = new HashMap<>();
    static void leak1(String key) { badCache.put(key, new byte[1024]); } // Grows forever!

    // ✅ Bounded LRU
    static Map<String, byte[]> goodCache = new LinkedHashMap<>(500, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) {
            return size() > 500;
        }
    };

    // LEAK 2: Listeners never removed
    static List<Runnable> badListeners = new ArrayList<>();
    static void register(Runnable l) { badListeners.add(l); } // Never deregistered!

    // ✅ WeakReference — GC collects listeners with no other strong refs
    static List<WeakReference<Runnable>> weakListeners = new ArrayList<>();
    static void registerWeak(Runnable l) { weakListeners.add(new WeakReference<>(l)); }
    static void fire() {
        weakListeners.removeIf(ref -> {
            Runnable l = ref.get();
            if (l == null) return true; // GC'd — remove dead ref
            l.run(); return false;
        });
    }

    // LEAK 3: ThreadLocal not cleaned in thread pool
    static ThreadLocal<byte[]> tl = ThreadLocal.withInitial(() -> new byte[1024*1024]);

    // ❌ 1MB stays in pool thread forever
    static void leakyTask()    { byte[] d = tl.get(); /* use */ }

    // ✅ Remove in finally
    static void safeTask() {
        try   { byte[] d = tl.get(); /* use */ }
        finally { tl.remove(); } // Critical!
    }

    // ── Heap growth detector ──────────────────────────────────────────────────
    static void detectHeapGrowth(int intervalMs, int samples) throws Exception {
        long[] heap = new long[samples];
        Runtime rt  = Runtime.getRuntime();

        for (int i = 0; i < samples; i++) {
            System.gc(); Thread.sleep(intervalMs);
            heap[i] = rt.totalMemory() - rt.freeMemory();
            System.out.printf("  Sample %2d: %,dMB%n", i+1, heap[i]/1024/1024);
        }

        long first = 0, second = 0;
        for (int i = 0;         i < samples/2; i++) first  += heap[i];
        for (int i = samples/2; i < samples;   i++) second += heap[i];

        double growth = 100.0 * (second - first) / first;
        System.out.printf("Trend: %+.1f%%%s%n", growth,
            growth > 20 ? " ⚠ Possible memory leak!" : " ✓ Stable");
    }

    public static void main(String[] args) throws Exception {
        detectHeapGrowth(2000, 10);
    }
}
```

---

## 15. Benchmarking with JMH

```java
import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;
import java.util.*;

// pom.xml: org.openjdk.jmh:jmh-core:1.37

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
public class StringConcatBenchmark {

    @Param({"10", "100", "1000"})
    private int size;
    private List<String> strings;

    @Setup
    public void setUp() {
        strings = new ArrayList<>(size);
        for (int i = 0; i < size; i++) strings.add("item-" + i);
    }

    @Benchmark
    public String plusOperator() {
        String r = "";
        for (String s : strings) r += s;
        return r;
    }

    @Benchmark
    public String stringBuilder() {
        StringBuilder sb = new StringBuilder(size * 10);
        for (String s : strings) sb.append(s);
        return sb.toString();
    }

    @Benchmark
    public String stringJoin() {
        return String.join("", strings);
    }

    // Use Blackhole to prevent dead-code elimination
    @Benchmark
    public void computeWithBlackhole(org.openjdk.jmh.infra.Blackhole bh) {
        for (int i = 0; i < size; i++) bh.consume(i * i);
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.runner.options.Options opts =
            new org.openjdk.jmh.runner.options.OptionsBuilder()
                .include(StringConcatBenchmark.class.getSimpleName())
                .forks(1).build();
        new org.openjdk.jmh.runner.Runner(opts).run();
    }
}

/*
Typical JMH output:
Benchmark                (size)  Mode  Cnt     Score   Error  Units
plusOperator               1000  avgt   20  1423.891±34.234  us/op  ← O(n²)
stringBuilder              1000  avgt   20    10.231± 0.189  us/op  ← O(n)
stringJoin                 1000  avgt   20     8.341± 0.156  us/op  ← Fastest
*/
```

---

## 16. Spring Boot Performance

```properties
# application.properties — key performance settings

# ── JPA (most impactful settings) ────────────────────────────────────────────
spring.jpa.open-in-view=false                          # CRITICAL: disable OSIV
spring.jpa.show-sql=false                              # Never true in production
spring.jpa.properties.hibernate.jdbc.batch_size=50
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true

# ── Connection Pool ───────────────────────────────────────────────────────────
spring.datasource.hikari.maximum-pool-size=17
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=30000
spring.datasource.hikari.max-lifetime=1800000

# ── Web ───────────────────────────────────────────────────────────────────────
server.tomcat.threads.max=200
server.tomcat.threads.min-spare=10
server.compression.enabled=true
server.compression.mime-types=application/json,text/html,text/plain
server.compression.min-response-size=1024

# ── Jackson ───────────────────────────────────────────────────────────────────
spring.jackson.default-property-inclusion=NON_NULL    # Skip null fields → smaller JSON

# ── Startup ───────────────────────────────────────────────────────────────────
spring.main.lazy-initialization=true                  # Faster startup (slower first request)
spring.jmx.enabled=false                              # Disable JMX if not needed
```

```java
@Configuration
public class SpringBootPerfConfig {

    // ── Tuned async executor ──────────────────────────────────────────────────
    @Bean @Primary
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        int cores = Runtime.getRuntime().availableProcessors();
        exec.setCorePoolSize(cores);
        exec.setMaxPoolSize(cores * 4);
        exec.setQueueCapacity(500);
        exec.setThreadNamePrefix("async-");
        exec.setRejectedExecutionHandler(
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        return exec;
    }

    // ── HTTP response caching for static resources ────────────────────────────
    @Bean
    public org.springframework.web.servlet.config.annotation.WebMvcConfigurer
            cachingConfigurer() {
        return new org.springframework.web.servlet.config.annotation
                .WebMvcConfigurer() {
            @Override
            public void addResourceHandlers(
                    org.springframework.web.servlet.config.annotation
                    .ResourceHandlerRegistry registry) {
                registry.addResourceHandler("/static/**")
                    .addResourceLocations("classpath:/static/")
                    .setCacheControl(
                        org.springframework.http.CacheControl
                            .maxAge(java.time.Duration.ofDays(365)));
            }
        };
    }
}
```

---

## 17. Container & Cloud Tuning

```bash
# ── Dockerfile JVM settings ───────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

ENV JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:InitialRAMPercentage=50.0 \
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/tmp/ \
  -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom \
  -Xlog:gc*:file=/var/log/gc.log:time:filecount=3,filesize=10m"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]

# ── Kubernetes resources ──────────────────────────────────────────────────────
# resources:
#   requests:
#     memory: "1Gi"
#     cpu: "500m"
#   limits:
#     memory: "2Gi"    ← JVM heap = 75% of 2Gi = 1.5Gi
#     cpu: "2000m"

# ── Probes ────────────────────────────────────────────────────────────────────
# livenessProbe:
#   httpGet: { path: /actuator/health/liveness, port: 8080 }
#   initialDelaySeconds: 30
#   periodSeconds: 10
#
# readinessProbe:
#   httpGet: { path: /actuator/health/readiness, port: 8080 }
#   initialDelaySeconds: 20
#   periodSeconds: 5

# ── GraalVM Native Image (Spring Boot 3.x) ───────────────────────────────────
# Build: ./mvnw -Pnative native:compile
# Startup: ~50ms vs 3s JVM  |  Memory: ~80MB vs 250MB JVM
# Trade-off: lower peak throughput (no JIT), complex build
```

---

## 18. Performance Anti-Patterns

```java
public class PerformanceAntiPatterns {

    // ❌ Anti-pattern 1: Exception handling in hot loops
    // Exception creation captures stack trace — very expensive!
    static int parseIntSlow(String s) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return 0; } // Stack trace captured!
    }

    // ✅ Validate first — no exception
    static int parseIntFast(String s) {
        if (s == null || s.isEmpty()) return 0;
        for (char c : s.toCharArray()) if (!Character.isDigit(c)) return 0;
        return Integer.parseInt(s);
    }

    // ❌ Anti-pattern 2: Logging in tight loops (string built even when OFF)
    static void processBad(List<Object> items) {
        for (Object o : items)
            log.debug("Processing: " + o.toString()); // toString() called even if !DEBUG
    }

    // ✅ Lazy parameter — toString() called ONLY if DEBUG is on
    static void processGood(List<Object> items) {
        for (Object o : items)
            log.debug("Processing: {}", o);
    }

    // ❌ Anti-pattern 3: N+1 queries
    static List<String> summariesBad(List<Long> ids) {
        return ids.stream().map(id -> {
            Order o = orderRepo.findById(id).orElseThrow();  // N queries
            User  u = userRepo.findById(o.getUserId()).orElseThrow(); // N more!
            return u.getName() + ": " + o.getTotal();
        }).toList();
    }

    // ✅ Batch fetch: 2 queries total
    static List<String> summariesGood(List<Long> ids) {
        List<Order> orders  = orderRepo.findAllById(ids);
        Set<Long>   uids    = orders.stream().map(Order::getUserId).collect(java.util.stream.Collectors.toSet());
        Map<Long, User> users = userRepo.findAllById(uids).stream()
            .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));
        return orders.stream()
            .map(o -> users.get(o.getUserId()).getName() + ": " + o.getTotal())
            .toList();
    }

    // ❌ Anti-pattern 4: Large object in HTTP session
    static void sessionLeak(jakarta.servlet.http.HttpSession session) {
        byte[] report = generate(); // 50MB per user session!
        session.setAttribute("report", report);
    }

    // ✅ Return directly — no session storage
    static org.springframework.http.ResponseEntity<byte[]> returnDirect() {
        return org.springframework.http.ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=report.pdf")
            .body(generate());
    }

    // ❌ Anti-pattern 5: Blocking in async/reactive context
    static reactor.core.publisher.Mono<String> blockingMono() {
        return reactor.core.publisher.Mono.fromCallable(() -> {
            Thread.sleep(5000); // Blocks event loop thread!
            return "result";
        });
    }

    // ✅ Offload blocking work to bounded elastic scheduler
    static reactor.core.publisher.Mono<String> nonBlocking() {
        return reactor.core.publisher.Mono.fromCallable(() -> {
            Thread.sleep(5000); return "result";
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    static org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PerformanceAntiPatterns.class);
    interface Order  { Long getUserId(); double getTotal(); }
    interface User   { Long getId();     String getName(); }
    interface OrderRepository { java.util.Optional<Order> findById(Long id); List<Order> findAllById(Iterable<Long> ids); }
    interface UserRepository  { java.util.Optional<User>  findById(Long id); List<User>  findAllById(Iterable<Long> ids); }
    static OrderRepository orderRepo = null;
    static UserRepository  userRepo  = null;
    static byte[] generate() { return new byte[0]; }
}
```

---

## 19. Production Monitoring

```java
import io.micrometer.core.instrument.*;

@Service
public class MetricsAwareService {

    private final Counter orderCounter;
    private final Timer   orderTimer;

    public MetricsAwareService(MeterRegistry registry) {
        this.orderCounter = Counter.builder("orders.created")
            .description("Total orders created")
            .register(registry);

        this.orderTimer = Timer.builder("orders.processing.time")
            .description("Order processing latency")
            .publishPercentiles(0.5, 0.95, 0.99)   // p50, p95, p99
            .register(registry);

        // Queue depth gauge (tracks current value automatically)
        java.util.Queue<Object> queue = new java.util.concurrent.LinkedBlockingQueue<>();
        Gauge.builder("orders.queue.depth", queue, java.util.Collection::size)
            .register(registry);
    }

    public void processOrder(Object request) {
        orderCounter.increment();
        orderTimer.record(() -> {
            // Business logic timed automatically
            doProcess(request);
        });
    }

    void doProcess(Object r) {}
}

// Actuator settings (application.properties):
// management.endpoints.web.exposure.include=prometheus,health,metrics
// management.metrics.export.prometheus.enabled=true
// management.endpoint.health.show-details=always
```

```yaml
# Prometheus alerting rules
- alert: HighHeapUsage
  expr: >
    jvm_memory_used_bytes{area="heap"}
    / jvm_memory_max_bytes{area="heap"} > 0.85
  for: 5m
  annotations:
    summary: "JVM heap {{ $value | humanizePercentage }} on {{ $labels.instance }}"

- alert: HighGCOverhead
  expr: >
    rate(jvm_gc_pause_seconds_sum[5m])
    / rate(jvm_gc_pause_seconds_count[5m]) > 0.10
  for: 2m
  annotations:
    summary: "GC overhead >10% on {{ $labels.instance }}"

- alert: HighP99Latency
  expr: >
    histogram_quantile(0.99,
      rate(orders_processing_time_seconds_bucket[5m])) > 2.0
  for: 3m
  annotations:
    summary: "P99 order processing >2s on {{ $labels.instance }}"
```

---

## 20. Interview Questions & Answers

| # | Question | Answer |
|---|----------|--------|
| 1 | How do you approach performance tuning? | Measure first → identify bottleneck with profiler → apply targeted fix → measure again. Never guess or optimize without data. Premature optimization adds complexity without guaranteed benefit. |
| 2 | What is the JMM and why does it matter? | JMM defines visibility and ordering guarantees for shared variable access across threads. Without `volatile` or `synchronized`, threads may see stale values cached in CPU registers or L1 cache. `volatile` establishes happens-before on write→read. |
| 3 | How do you tune GC for low-latency? | Java 21: `-XX:+UseZGC -XX:+ZGenerational` (sub-ms pauses). Set `-Xms=-Xmx`. Use `MaxRAMPercentage` in containers. Keep heap 3-4× live data size. Avoid humongous allocations. Use flame graphs to find allocation hot spots. |
| 4 | What causes memory leaks in Java? | Static collections without eviction, listeners never unregistered, ThreadLocal not removed in thread pools, non-static inner classes holding outer reference, ClassLoader leaks from dynamic class generation, finalize() preventing timely collection. |
| 5 | What is escape analysis? | JIT determines if an object "escapes" a method (returned, stored in field, shared). Non-escaping objects are stack-allocated or scalar-replaced (fields → local variables). No heap allocation = no GC pressure. Default on: `-XX:+DoEscapeAnalysis`. |
| 6 | `volatile` vs `synchronized` vs `AtomicInteger`? | `volatile`: visibility only, no atomicity (safe for flags). `synchronized`: visibility + mutual exclusion (safest, slowest). `AtomicInteger`: CAS-based lock-free increment (fast for single variable). `LongAdder`: fastest for high-contention counting. |
| 7 | How do you size a thread pool? | CPU-bound: `threads = cores`. I/O-bound: `threads = cores × (1 + wait/cpu)`. Too few → underutilized CPU. Too many → context switch overhead. Java 21 virtual threads eliminate I/O-bound sizing — use unlimited virtual threads. |
| 8 | What is false sharing? | Two threads modify different variables on the same CPU cache line (64 bytes). Each write invalidates the other's cache. Fix: pad fields apart, use `@Contended` (JDK 8+, needs `-XX:-RestrictContended`), or restructure data layout. |
| 9 | How do you profile a Java app? | async-profiler (CPU flame graphs, allocation profiles, ~1% overhead), Java Flight Recorder (JFR, continuous, production-safe), JDK Mission Control (JFR analysis), jstack (thread dumps), Eclipse MAT (heap dump analysis). Always use production-like load. |
| 10 | String concatenation performance? | `+` in loops = O(n²) — new String each iteration. `StringBuilder` = O(n). `String.join()` and `Collectors.joining()` are fastest and most readable. Avoid `String.format()` in hot paths. Pre-compile regex as `static final Pattern`. |
| 11 | How does JIT work? | Starts in interpreter, compiles to C1 after ~1K invocations (quick compile), then C2 after ~10K (deep optimizations: inlining, loop unrolling, escape analysis, intrinsics). Always warm up benchmarks — cold JVM measurements are meaningless. |
| 12 | How do you tune HikariCP? | Pool = (cores×2)+1. Set `connectionTimeout` to fail fast. Set `maxLifetime` to recycle stale connections. Enable `prepStmtCacheSize`. Set `keepaliveTime` to prevent firewall timeouts. Use JMX to monitor pool usage in production. |
| 13 | What is the N+1 problem and how to fix it? | Loading N items then running 1 DB query per item = N+1 total queries. Fix with JPA `JOIN FETCH`, `@EntityGraph`, `findAllById` batch loading, or DTO projections that fetch needed data in one query. Always disable `open-in-view`. |
| 14 | `ConcurrentHashMap` vs `synchronizedMap`? | `synchronizedMap` locks the entire map per operation. `ConcurrentHashMap` uses CAS + segment locking — concurrent reads are non-blocking, writes fine-grained. Always prefer `ConcurrentHashMap`. Use `compute()`/`merge()` for atomic updates. |
| 15 | How do you reduce memory usage? | Right-size heap, use primitive arrays instead of `List<Integer>` for large data, pool large objects, enable string deduplication (`-XX:+UseStringDeduplication`), lazy initialization, `List.of()` for immutable collections, choose right collection type. |
| 16 | What is a CPU flame graph? | Visualization of stack trace samples. Width = total time in code path (wide = hot). Y-axis = call depth. Generated by async-profiler: `./asprof -d 30 -f flamegraph.html <pid>`. Find wide boxes at top — those are bottlenecks to optimize. |
| 17 | How do you benchmark correctly with JMH? | Warmup iterations (JIT must optimize first), multiple measurement iterations, multiple forks (separate JVM = clean JIT state). Use `Blackhole` to prevent dead-code elimination. Never benchmark in `@Test` directly — JIT optimizes away benchmark code. |
| 18 | What are Java 21 virtual threads and when to use? | JVM-managed lightweight threads (~200 bytes vs ~1MB native stack). Enable massive I/O concurrency. Use for I/O-bound workloads (DB queries, HTTP calls). Not beneficial for CPU-bound tasks — still limited by physical cores. Use `Executors.newVirtualThreadPerTaskExecutor()`. |
| 19 | How to tune Spring Boot performance? | `spring.jpa.open-in-view=false`, batch_size=50, disable show-sql. Use `@Async` for non-critical work. Enable HTTP compression. Use DTO projections. Tune HikariCP pool size. Enable response caching for static content. Profile with actuator + Micrometer + flame graphs. |
| 20 | How to debug high CPU usage? | `jstack <pid>` → look for RUNNABLE threads (not WAITING/BLOCKED). `jstat -gcutil` → check GC overhead. async-profiler CPU flame graph → find hot code paths. Common causes: tight loops, excessive GC (heap too small), regex in hot path, lock contention spinning. |

---

## 21. Complete Reference Summary

### Performance Tuning Checklist

```
MEMORY
  □ -Xms = -Xmx (no resize GC)
  □ -XX:+UseContainerSupport + MaxRAMPercentage=75 in containers
  □ Set MaxMetaspaceSize for dynamic class apps
  □ -XX:+HeapDumpOnOutOfMemoryError + HeapDumpPath
  □ -XX:+ExitOnOutOfMemoryError
  □ Use primitive arrays over List<Integer> for large data
  □ Pool large/expensive objects (ByteBuffer, connections)
  □ Use List.of() for immutable collections (40% less memory)
  □ Remove ThreadLocal in finally blocks in thread pools
  □ Bounded caches (LinkedHashMap removeEldestEntry, Caffeine)

GC
  □ Java 21: -XX:+UseZGC -XX:+ZGenerational
  □ Enable GC logging: -Xlog:gc*:file=gc.log:time
  □ Target 0 Full GC events in production
  □ Lower IHOP if seeing Full GC: -XX:InitiatingHeapOccupancyPercent=35
  □ G1 region size = heap/2048: -XX:G1HeapRegionSize
  □ -XX:+DisableExplicitGC (block System.gc())
  □ -XX:+UseStringDeduplication (G1GC only)

CPU / THREADING
  □ CPU-bound pool = CPU cores
  □ I/O-bound: cores × (1 + wait/cpu)
  □ Java 21 virtual threads for I/O-bound
  □ LongAdder over AtomicLong for high-contention counters
  □ ConcurrentHashMap over synchronizedMap
  □ StampedLock optimistic reads for read-heavy paths
  □ Hoist loop-invariant computations out of loops
  □ Pre-compile Regex as static final Pattern

DATABASE
  □ HikariCP: pool = (cores × 2) + 1
  □ spring.jpa.open-in-view=false (always!)
  □ hibernate.jdbc.batch_size=50
  □ JOIN FETCH / @EntityGraph to prevent N+1
  □ DTO projections for read-only queries
  □ Index WHERE, JOIN, ORDER BY columns

STRING / COLLECTIONS
  □ StringBuilder / String.join for concatenation
  □ Pre-size ArrayList(n) and HashMap(n*4/3+1)
  □ EnumMap/EnumSet for enum-keyed structures
  □ int[] instead of List<Integer> for large data

I/O
  □ Always buffer I/O (BufferedInputStream)
  □ Memory-map large files (FileChannel.map)
  □ transferTo for zero-copy operations
  □ Files.lines() for large text (O(1) memory)

PROFILING WORKFLOW
  1. Baseline (JMH or manual timing + memory snapshot)
  2. Enable GC logging
  3. async-profiler: CPU flame graph + allocation profile
  4. Find wide boxes in flame graph → hot code
  5. Fix ONE bottleneck at a time
  6. Re-measure vs baseline
  7. Repeat
```

### JVM Flag Quick Reference

```bash
# Heap sizing
-Xms<size> -Xmx<size>               # Min/max heap (set equal)
-XX:+UseContainerSupport             # Docker/k8s aware
-XX:MaxRAMPercentage=75.0            # % of container memory for heap
-XX:NewRatio=2                       # Old:Young = 2:1

# GC selection
-XX:+UseZGC -XX:+ZGenerational      # Java 21 — best default
-XX:+UseG1GC                         # Java 9+, general purpose
-XX:MaxGCPauseMillis=200             # G1 pause target
-XX:InitiatingHeapOccupancyPercent=40# G1 concurrent mark trigger

# GC behavior
-XX:+DisableExplicitGC               # Block System.gc()
-XX:+UseStringDeduplication          # G1: dedup equal Strings

# Diagnosis
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/tmp/
-XX:+ExitOnOutOfMemoryError
-Xlog:gc*:file=/var/log/gc.log:time
-XX:NativeMemoryTracking=summary
-XX:StartFlightRecording=duration=60s,filename=app.jfr

# JIT
-XX:+DoEscapeAnalysis                # Stack alloc (default on)
-XX:+EliminateAllocations            # Scalar replacement (default on)
-XX:MaxInlineSize=35                 # Inlining threshold (bytecodes)
```

### Tools Quick Reference

```
Tool            Purpose                           Overhead
─────────────────────────────────────────────────────────────
jps             List Java processes               None
jstack          Thread dumps, deadlock detect     Pauses JVM briefly
jmap            Heap info + heap dump             Pauses for dump
jstat           Live GC stats                     Very low
jcmd            All-in-one command                Low
async-profiler  CPU/alloc/lock flame graphs       1–3%
JFR             Continuous production profiling   ~1%
JDK Mission Ctrl JFR analysis GUI                 Offline
Eclipse MAT     Heap dump analysis                Offline
GCEasy.io       GC log visualization              Offline
JMH             Micro-benchmarking                N/A (test tool)
```

---

*Made with ❤️ for Java developers — Java 8 through Java 21*
