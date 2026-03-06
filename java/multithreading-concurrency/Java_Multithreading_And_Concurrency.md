# ☕ Java Multithreading & Concurrency — Deep Dive Complete Guide

---

## 📌 Table of Contents

1. [What is a Thread & Process?](#1-what-is-a-thread--process)
2. [Thread Lifecycle — All 6 States](#2-thread-lifecycle--all-6-states)
3. [Creating Threads — All 5 Ways](#3-creating-threads--all-5-ways)
4. [Thread Priority & Daemon Threads](#4-thread-priority--daemon-threads)
5. [Thread Methods Deep Dive](#5-thread-methods-deep-dive)
6. [Race Condition](#6-race-condition)
7. [Synchronization — Method, Block, Static](#7-synchronization--method-block-static)
8. [Inter-Thread Communication](#8-inter-thread-communication)
9. [volatile Keyword](#9-volatile-keyword)
10. [Deadlock, Livelock & Starvation](#10-deadlock-livelock--starvation)
11. [ReentrantLock & Locks API](#11-reentrantlock--locks-api)
12. [Executor Framework & Thread Pools](#12-executor-framework--thread-pools)
13. [Callable, Future & FutureTask](#13-callable-future--futuretask)
14. [Atomic Classes](#14-atomic-classes)
15. [Concurrent Collections](#15-concurrent-collections)
16. [Concurrency Utilities](#16-concurrency-utilities)
17. [ThreadLocal](#17-threadlocal)
18. [Fork/Join Framework](#18-forkjoin-framework)
19. [CompletableFuture](#19-completablefuture)
20. [Interview Questions & Answers](#20-interview-questions--answers)

---

## 1. What is a Thread & Process?

A **process** is an independent program in execution with its own memory space.  
A **thread** is the smallest unit of execution *within* a process — threads share the process's memory (heap) but each has its own stack.

```
JVM Process
┌──────────────────────────────────────────────────────────┐
│  HEAP  (shared by ALL threads)                           │
│  ┌────────────────────────────────────────────────────┐  │
│  │  Objects,  Static Fields,  String Pool             │  │
│  └────────────────────────────────────────────────────┘  │
│                                                          │
│  Thread-1          Thread-2            Thread-3          │
│  ┌────────────┐    ┌────────────┐    ┌────────────┐      │
│  │ Stack      │    │ Stack      │    │ Stack      │      │
│  │ - frames   │    │ - frames   │    │ - frames   │      │
│  │ - locals   │    │ - locals   │    │ - locals   │      │
│  ├────────────┤    ├────────────┤    ├────────────┤      │
│  │ PC Register│    │ PC Register│    │ PC Register│      │
│  └────────────┘    └────────────┘    └────────────┘      │
└──────────────────────────────────────────────────────────┘
```

| Memory Area    | Shared? | What Lives There                   |
|----------------|---------|------------------------------------|
| Heap           | ✅ Yes  | Objects, instance variables        |
| Method Area    | ✅ Yes  | Class metadata, static variables   |
| Stack          | ❌ No   | Method frames, local variables     |
| PC Register    | ❌ No   | Current bytecode instruction       |
| Native Stack   | ❌ No   | Native method calls                |

### Why Use Multithreading?

| Reason           | Explanation                                              |
|------------------|----------------------------------------------------------|
| Performance      | Use multiple CPU cores simultaneously                    |
| Responsiveness   | UI remains active while background work continues        |
| Throughput       | Handle many requests at once (e.g., web servers)         |
| Resource Sharing | Threads share heap; no expensive IPC needed              |
| Simplicity       | Model concurrent tasks naturally (download + UI update)  |

---

## 2. Thread Lifecycle — All 6 States

```
                    ┌──────────────────────────────────────────────┐
                    │           TIMED_WAITING                      │
                    │  (sleep/wait(ms)/join(ms)/LockSupport.park)  │
                    └─────────────┬────────────────────────────────┘
                                  │ timeout expires / notified
NEW ──► start() ──► RUNNABLE ◄───┘
                       │   ▲
         scheduler     │   │ preempted
         picks thread  ▼   │
                     RUNNING ──► wait()/join() ──► WAITING
                       │                             │
              lock not │                   notify()/notifyAll()
              available│                             │
                       ▼                             ▼
                    BLOCKED ──── lock acquired ──► RUNNABLE
                       │
              run() completes / exception
                       │
                       ▼
                   TERMINATED
```

| State           | Java Enum                     | Trigger                                            |
|-----------------|-------------------------------|-----------------------------------------------------|
| NEW             | `Thread.State.NEW`            | Thread created, `start()` not yet called           |
| RUNNABLE        | `Thread.State.RUNNABLE`       | `start()` called, ready or actively running        |
| BLOCKED         | `Thread.State.BLOCKED`        | Waiting to enter a `synchronized` block            |
| WAITING         | `Thread.State.WAITING`        | `wait()`, `join()`, `LockSupport.park()`           |
| TIMED_WAITING   | `Thread.State.TIMED_WAITING`  | `sleep(n)`, `wait(n)`, `join(n)`                   |
| TERMINATED      | `Thread.State.TERMINATED`     | `run()` completed or threw uncaught exception      |

```java
public class ThreadStateDemo {
    public static void main(String[] args) throws InterruptedException {

        Object lock = new Object();

        Thread t = new Thread(() -> {
            synchronized (lock) {
                try {
                    System.out.println("Inside thread, going WAITING...");
                    lock.wait();  // → WAITING
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // Simulate some work after notify
            try { Thread.sleep(200); } catch (InterruptedException e) {}
        });

        System.out.println("After creation  → " + t.getState());   // NEW

        t.start();
        Thread.sleep(100);
        System.out.println("After start()   → " + t.getState());   // WAITING

        synchronized (lock) { lock.notify(); }

        Thread.sleep(50);
        System.out.println("After notify()  → " + t.getState());   // TIMED_WAITING (in sleep)

        t.join();
        System.out.println("After join()    → " + t.getState());   // TERMINATED
    }
}
```

**Output:**
```
After creation  → NEW
Inside thread, going WAITING...
After start()   → WAITING
After notify()  → TIMED_WAITING
After join()    → TERMINATED
```

---

## 3. Creating Threads — All 5 Ways

---

### ✅ Way 1: Extending `Thread` Class

```java
class CounterThread extends Thread {

    private final String label;
    private final int    limit;

    CounterThread(String label, int limit) {
        super(label);       // Sets thread name via Thread constructor
        this.label = label;
        this.limit = limit;
    }

    @Override
    public void run() {
        for (int i = 1; i <= limit; i++) {
            System.out.printf("[%s] count = %d%n",
                    Thread.currentThread().getName(), i);
            try { Thread.sleep(100); }
            catch (InterruptedException e) {
                System.out.println(label + " interrupted — stopping.");
                return;   // Clean exit on interrupt
            }
        }
    }
}

public class ExtendThreadDemo {
    public static void main(String[] args) throws InterruptedException {
        CounterThread t1 = new CounterThread("Thread-A", 5);
        CounterThread t2 = new CounterThread("Thread-B", 5);

        t1.start();   // ✅ Creates new thread and calls run() on it
        t2.start();

        // ⚠️  NEVER call t1.run() directly!
        //     run() executes on the CALLING thread — no new thread is spawned.

        t1.join();
        t2.join();
        System.out.println("Both threads finished.");
    }
}
```

**Output (order may vary due to scheduling):**
```
[Thread-A] count = 1
[Thread-B] count = 1
[Thread-A] count = 2
[Thread-B] count = 2
...
Both threads finished.
```

> ⚠️ **Limitation:** Java is single-inheritance. If your class extends `Thread`, it cannot extend any other class.

---

### ✅ Way 2: Implementing `Runnable` *(Most Common — Preferred)*

```java
class FileDownloader implements Runnable {

    private final String fileName;
    private final int    sizeInMB;

    FileDownloader(String fileName, int sizeInMB) {
        this.fileName = fileName;
        this.sizeInMB = sizeInMB;
    }

    @Override
    public void run() {
        System.out.printf("⬇ Starting: %s (%d MB)%n", fileName, sizeInMB);
        for (int mb = 1; mb <= sizeInMB; mb++) {
            try { Thread.sleep(200); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("Download interrupted: " + fileName);
                return;
            }
            System.out.printf("  %s → %d/%d MB downloaded%n", fileName, mb, sizeInMB);
        }
        System.out.printf("✅ Done: %s%n", fileName);
    }
}

public class RunnableDemo {
    public static void main(String[] args) throws InterruptedException {

        Thread t1 = new Thread(new FileDownloader("movie.mp4", 3), "Downloader-1");
        Thread t2 = new Thread(new FileDownloader("music.mp3", 2), "Downloader-2");
        Thread t3 = new Thread(new FileDownloader("photo.zip", 4), "Downloader-3");

        t1.start();
        t2.start();
        t3.start();

        t1.join();
        t2.join();
        t3.join();

        System.out.println("🎉 All downloads complete!");
    }
}
```

> **Why prefer `Runnable`?**
> - Keeps task logic **separate** from thread mechanics
> - Allows extending another class (`class MyTask extends SomeBase implements Runnable`)
> - Works seamlessly with `ExecutorService`, thread pools, and lambdas

---

### ✅ Way 3: Lambda Expression (Java 8+)

```java
public class LambdaThreadDemo {
    public static void main(String[] args) throws InterruptedException {

        // Single statement lambda
        Thread t1 = new Thread(
            () -> System.out.println("Hello from Lambda Thread! " +
                                     Thread.currentThread().getName())
        );

        // Multi-statement lambda
        Thread t2 = new Thread(() -> {
            for (int i = 1; i <= 3; i++) {
                System.out.println("Lambda loop #" + i);
                try { Thread.sleep(100); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });

        // Named lambda thread
        Thread t3 = new Thread(
            () -> System.out.println("Named lambda: " + Thread.currentThread().getName()),
            "LambdaWorker-3"
        );

        t1.start();
        t2.start();
        t3.start();

        t1.join(); t2.join(); t3.join();
    }
}
```

---

### ✅ Way 4: Anonymous Class

```java
public class AnonymousClassDemo {
    public static void main(String[] args) throws InterruptedException {

        // Anonymous Runnable
        Thread t1 = new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("Anonymous Runnable on: " +
                        Thread.currentThread().getName());
            }
        });

        // Anonymous Thread subclass
        Thread t2 = new Thread() {
            @Override
            public void run() {
                System.out.println("Anonymous Thread subclass on: " +
                        Thread.currentThread().getName());
            }
        };

        t1.start(); t2.start();
        t1.join();  t2.join();
    }
}
```

---

### ✅ Way 5: ThreadFactory

```java
import java.util.concurrent.*;

public class ThreadFactoryDemo {
    public static void main(String[] args) throws InterruptedException {

        // Custom factory giving full control: name, priority, daemon status
        ThreadFactory factory = runnable -> {
            Thread t = new Thread(runnable);
            t.setName("CustomWorker-" + t.getId());
            t.setPriority(Thread.NORM_PRIORITY);
            t.setDaemon(false);
            System.out.println("Factory created: " + t.getName());
            return t;
        };

        Thread t1 = factory.newThread(() -> System.out.println("Task 1 running"));
        Thread t2 = factory.newThread(() -> System.out.println("Task 2 running"));

        t1.start(); t2.start();
        t1.join();  t2.join();
    }
}
```

---

### Comparison Table

| Method           | Returns Value? | Can Extend Other Class? | Use With Pool? | Recommended For     |
|------------------|:--------------:|:-----------------------:|:--------------:|---------------------|
| `extends Thread` | ❌             | ❌                      | ❌             | Simple, quick tasks |
| `Runnable`       | ❌             | ✅                      | ✅             | Most situations     |
| Lambda           | ❌             | ✅                      | ✅             | Short inline tasks  |
| Anonymous Class  | ❌             | ✅                      | ✅             | Older codebases     |
| `ThreadFactory`  | ❌             | ✅                      | ✅             | Controlling thread attributes |

---

## 4. Thread Priority & Daemon Threads

### Thread Priority

Java threads have a priority from `1` (lowest) to `10` (highest). The OS scheduler **uses** this as a *hint* — it is not guaranteed.

```java
public class PriorityDemo {
    public static void main(String[] args) {

        Thread low = new Thread(() -> {
            for (int i = 0; i < 5; i++)
                System.out.println("LOW priority: " + i);
        });

        Thread high = new Thread(() -> {
            for (int i = 0; i < 5; i++)
                System.out.println("HIGH priority: " + i);
        });

        low.setPriority(Thread.MIN_PRIORITY);   // 1
        high.setPriority(Thread.MAX_PRIORITY);  // 10

        System.out.println("Low  priority: " + low.getPriority());   // 1
        System.out.println("High priority: " + high.getPriority());  // 10

        low.start();
        high.start();
    }
}
```

| Constant                  | Value |
|---------------------------|-------|
| `Thread.MIN_PRIORITY`     | 1     |
| `Thread.NORM_PRIORITY`    | 5     |
| `Thread.MAX_PRIORITY`     | 10    |

---

### Daemon Threads

A **daemon thread** is a background/service thread. When all **non-daemon** (user) threads finish, the JVM terminates — even if daemon threads are still running.

```java
public class DaemonDemo {
    public static void main(String[] args) throws InterruptedException {

        Thread daemon = new Thread(() -> {
            int i = 0;
            while (true) {
                System.out.println("Daemon heartbeat #" + (++i));
                try { Thread.sleep(500); }
                catch (InterruptedException e) { return; }
            }
        });

        daemon.setDaemon(true);   // Must set BEFORE start()
        daemon.start();

        // Main (user) thread runs for 2 seconds, then exits
        Thread.sleep(2000);
        System.out.println("Main thread done → JVM will exit now.");
        // JVM exits here; daemon is killed automatically
    }
}
```

**Output:**
```
Daemon heartbeat #1
Daemon heartbeat #2
Daemon heartbeat #3
Daemon heartbeat #4
Main thread done → JVM will exit now.
```

> ✅ Use daemon threads for: garbage collection, monitoring, heartbeats, log flushers.  
> ⚠️ Daemon threads must **not** do critical I/O — they may be killed mid-operation!

---

## 5. Thread Methods Deep Dive

| Method                    | Description                                                  |
|---------------------------|--------------------------------------------------------------|
| `start()`                 | Starts a new thread and calls `run()` on it                  |
| `run()`                   | The task to execute — do NOT call directly                   |
| `sleep(ms)`               | Pauses thread; does NOT release locks                        |
| `join()`                  | Caller waits until this thread terminates                    |
| `join(ms)`                | Caller waits at most `ms` milliseconds                       |
| `yield()`                 | Hints scheduler to let another thread run                    |
| `interrupt()`             | Sets the interrupt flag; wakes sleeping/waiting thread       |
| `isInterrupted()`         | Checks interrupt flag WITHOUT clearing it                    |
| `interrupted()`           | Checks AND CLEARS the interrupt flag (static)                |
| `isAlive()`               | Returns `true` if thread has been started and not terminated |
| `setName(s)` / `getName()`| Get / set thread name                                        |
| `setPriority(n)`          | Set scheduling priority (1–10)                               |
| `setDaemon(b)`            | Mark as daemon (must be called before `start()`)             |
| `currentThread()`         | Returns reference to the currently executing thread          |
| `getState()`              | Returns the current `Thread.State`                           |
| `getId()`                 | Returns the unique long ID                                   |

---

### `sleep()` — Pause without releasing lock

```java
public class SleepDemo {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("START at " + System.currentTimeMillis());

        Thread.sleep(2000); // Pauses for ~2 seconds; does NOT release any held lock

        System.out.println("AFTER SLEEP at " + System.currentTimeMillis());
    }
}
```

> ⚠️ `sleep()` does **NOT** release locks. If another thread is waiting on the same lock, it stays waiting.

---

### `join()` — Wait for a thread to finish

```java
public class JoinDemo {
    public static void main(String[] args) throws InterruptedException {

        Thread dataLoader = new Thread(() -> {
            System.out.println("Loading data...");
            try { Thread.sleep(1500); } catch (InterruptedException e) {}
            System.out.println("Data loaded!");
        });

        Thread processor = new Thread(() -> {
            System.out.println("Processing started.");
        });

        dataLoader.start();
        dataLoader.join();    // Main thread waits here until dataLoader finishes

        processor.start();    // Only starts AFTER data is loaded
        processor.join();

        System.out.println("Pipeline complete.");
    }
}
```

**Output (guaranteed order):**
```
Loading data...
Data loaded!
Processing started.
Pipeline complete.
```

---

### `interrupt()` — Signal a thread to stop

```java
public class InterruptDemo {
    public static void main(String[] args) throws InterruptedException {

        Thread worker = new Thread(() -> {
            System.out.println("Worker started");
            while (!Thread.currentThread().isInterrupted()) {
                System.out.println("Working...");
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    // sleep() clears the interrupt flag when it throws
                    // We must re-set it so the while-check sees it
                    Thread.currentThread().interrupt();
                }
            }
            System.out.println("Worker stopping cleanly.");
        });

        worker.start();
        Thread.sleep(1500);
        worker.interrupt();   // Signal the worker
        worker.join();
        System.out.println("Worker stopped.");
    }
}
```

**Output:**
```
Worker started
Working...
Working...
Working...
Worker stopping cleanly.
Worker stopped.
```

---

### `yield()` — Hint to give up CPU

```java
public class YieldDemo {
    public static void main(String[] args) {

        Runnable task = () -> {
            for (int i = 0; i < 3; i++) {
                System.out.println(Thread.currentThread().getName() + " → " + i);
                Thread.yield(); // Hint: let another thread run now
            }
        };

        Thread t1 = new Thread(task, "T1");
        Thread t2 = new Thread(task, "T2");

        t1.start();
        t2.start();
    }
}
```

> ⚠️ `yield()` is a **hint** only — the scheduler may ignore it entirely.

---

## 6. Race Condition

A **race condition** occurs when two or more threads access shared data concurrently and the final result depends on the order of execution.

### Anatomy of a Race Condition

`count++` looks like ONE operation but is actually THREE:

```
Thread-1                    Thread-2
────────                    ────────
1. READ  count (= 0)        
                            1. READ  count (= 0)  ← reads STALE value!
2. ADD   0 + 1  = 1         
                            2. ADD   0 + 1  = 1
3. WRITE count  = 1
                            3. WRITE count  = 1   ← overwrites Thread-1's write!

Final count = 1  (WRONG — should be 2!)
```

### Race Condition Demo

```java
class BankAccount {
    int balance = 1000;

    void withdraw(int amount) {
        if (balance >= amount) {
            System.out.println(Thread.currentThread().getName()
                    + " approved withdrawal of ₹" + amount
                    + " | balance before = " + balance);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
            balance -= amount;
            System.out.println(Thread.currentThread().getName()
                    + " | balance after = " + balance);
        } else {
            System.out.println(Thread.currentThread().getName()
                    + " DENIED — insufficient funds");
        }
    }
}

public class RaceConditionDemo {
    public static void main(String[] args) throws InterruptedException {
        BankAccount account = new BankAccount();

        // Both threads try to withdraw ₹800 from ₹1000 balance
        Thread t1 = new Thread(() -> account.withdraw(800), "Thread-1");
        Thread t2 = new Thread(() -> account.withdraw(800), "Thread-2");

        t1.start();
        t2.start();

        t1.join();
        t2.join();

        System.out.println("Final balance: ₹" + account.balance); // May be NEGATIVE!
    }
}
```

**Possible output (BUG!):**
```
Thread-1 approved withdrawal of ₹800 | balance before = 1000
Thread-2 approved withdrawal of ₹800 | balance before = 1000
Thread-1 | balance after = 200
Thread-2 | balance after = -600   ← NEGATIVE BALANCE BUG!
Final balance: ₹-600
```

---

## 7. Synchronization — Method, Block, Static

**Synchronization** ensures that only one thread at a time accesses a critical section by acquiring a **monitor lock** (intrinsic lock / mutex) on an object.

### How Locks Work

```
Thread-1 acquires lock ──► enters synchronized block
Thread-2 tries to acquire ──► BLOCKED (waits in entry set)
Thread-1 exits synchronized block ──► releases lock
Thread-2 acquires lock ──► enters synchronized block
```

---

### Type 1: Synchronized Method (locks `this`)

```java
class SafeBankAccount {
    private int balance;

    SafeBankAccount(int initialBalance) {
        this.balance = initialBalance;
    }

    // Lock on 'this' instance — only one thread can run this at a time
    public synchronized void withdraw(int amount) {
        if (balance >= amount) {
            System.out.println(Thread.currentThread().getName()
                    + " withdrawing ₹" + amount);
            balance -= amount;
            System.out.println(Thread.currentThread().getName()
                    + " done → balance = ₹" + balance);
        } else {
            System.out.println(Thread.currentThread().getName()
                    + " DENIED — balance = ₹" + balance);
        }
    }

    public synchronized int getBalance() { return balance; }
}

public class SynchronizedMethodDemo {
    public static void main(String[] args) throws InterruptedException {
        SafeBankAccount account = new SafeBankAccount(1000);

        Thread t1 = new Thread(() -> account.withdraw(800), "Thread-1");
        Thread t2 = new Thread(() -> account.withdraw(800), "Thread-2");

        t1.start(); t2.start();
        t1.join();  t2.join();

        System.out.println("Final balance: ₹" + account.getBalance()); // Always ≥ 0
    }
}
```

**Output (Thread-2 is correctly denied):**
```
Thread-1 withdrawing ₹800
Thread-1 done → balance = ₹200
Thread-2 DENIED — balance = ₹200
Final balance: ₹200
```

---

### Type 2: Synchronized Block (finer granularity)

```java
class OrderProcessor {
    private int orderCount = 0;
    private final Object orderLock = new Object(); // Dedicated lock object

    private int paymentCount = 0;
    private final Object paymentLock = new Object(); // Separate lock

    public void processOrder() {
        // Only lock the critical section — not the whole method
        synchronized (orderLock) {
            orderCount++;
        }
        // Other non-critical code here runs WITHOUT holding any lock
        System.out.println("Processing order #" + orderCount);
    }

    public void processPayment() {
        synchronized (paymentLock) { // Different lock — can run concurrently with processOrder!
            paymentCount++;
        }
        System.out.println("Processing payment #" + paymentCount);
    }
}
```

> **Synchronized block advantage:** Two threads can run `processOrder()` and `processPayment()` simultaneously since they use **different locks**.

---

### Type 3: Static Synchronization (locks the Class object)

```java
class IdGenerator {
    private static int nextId = 0;

    // Locks on IdGenerator.class — shared across ALL instances
    public static synchronized int getNextId() {
        return ++nextId;
    }
}

public class StaticSyncDemo {
    public static void main(String[] args) throws InterruptedException {
        Runnable task = () -> {
            for (int i = 0; i < 5; i++) {
                System.out.println(Thread.currentThread().getName()
                        + " got ID: " + IdGenerator.getNextId());
            }
        };

        Thread t1 = new Thread(task, "Worker-1");
        Thread t2 = new Thread(task, "Worker-2");

        t1.start(); t2.start();
        t1.join();  t2.join();
    }
}
```

---

### Lock Levels Comparison

```
┌──────────────────────┬─────────────────────────┬─────────────────────────────────┐
│  Type                │  Locks On               │  Scope                          │
├──────────────────────┼─────────────────────────┼─────────────────────────────────┤
│ synchronized method  │  this (instance)        │  Per-object                     │
│ synchronized block   │  any object you choose  │  Flexible; use for fine control │
│ static synchronized  │  ClassName.class        │  Shared across all instances    │
└──────────────────────┴─────────────────────────┴─────────────────────────────────┘
```

---

## 8. Inter-Thread Communication

Threads communicate via `wait()`, `notify()`, and `notifyAll()` — all defined in `java.lang.Object`.

| Method          | What It Does                                            | Must Be Inside       |
|-----------------|----------------------------------------------------------|----------------------|
| `wait()`        | Releases lock, thread enters WAITING until notified      | `synchronized` block |
| `wait(ms)`      | Same but times out after `ms` milliseconds               | `synchronized` block |
| `notify()`      | Wakes ONE arbitrary waiting thread                       | `synchronized` block |
| `notifyAll()`   | Wakes ALL waiting threads                                | `synchronized` block |

---

### Classic Producer-Consumer (1 Producer, 1 Consumer)

```java
class DataPipe {
    private int data;
    private boolean hasData = false;  // True = full, False = empty

    // ── PRODUCER calls this ─────────────────────────────────────────────
    public synchronized void produce(int value) throws InterruptedException {
        while (hasData) {
            System.out.println("Producer waiting — pipe is full...");
            wait();  // Releases lock; wakes when consumer calls notify()
        }
        data    = value;
        hasData = true;
        System.out.println("Produced: " + value);
        notify(); // Wake up consumer
    }

    // ── CONSUMER calls this ─────────────────────────────────────────────
    public synchronized int consume() throws InterruptedException {
        while (!hasData) {
            System.out.println("Consumer waiting — pipe is empty...");
            wait();  // Releases lock; wakes when producer calls notify()
        }
        hasData = false;
        System.out.println("Consumed: " + data);
        notify(); // Wake up producer
        return data;
    }
}

public class ProducerConsumerDemo {
    public static void main(String[] args) {
        DataPipe pipe = new DataPipe();

        Thread producer = new Thread(() -> {
            try {
                for (int i = 1; i <= 5; i++) {
                    pipe.produce(i * 10);
                    Thread.sleep(500);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Producer");

        Thread consumer = new Thread(() -> {
            try {
                for (int i = 1; i <= 5; i++) {
                    pipe.consume();
                    Thread.sleep(1000); // Consumer is slower than producer
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Consumer");

        producer.start();
        consumer.start();
    }
}
```

**Output:**
```
Produced: 10
Consumed: 10
Produced: 20
Producer waiting — pipe is full...
Consumed: 20
Produced: 30
...
```

---

### Multi-Producer Multi-Consumer with `notifyAll()`

```java
import java.util.LinkedList;
import java.util.Queue;

class BoundedBuffer {
    private final Queue<Integer> queue;
    private final int capacity;

    BoundedBuffer(int capacity) {
        this.queue    = new LinkedList<>();
        this.capacity = capacity;
    }

    public synchronized void put(int item) throws InterruptedException {
        while (queue.size() == capacity) {
            System.out.println(Thread.currentThread().getName() + " waiting (buffer full)");
            wait();
        }
        queue.add(item);
        System.out.println(Thread.currentThread().getName() + " produced " + item
                + "  | buffer size = " + queue.size());
        notifyAll(); // Wake ALL waiting consumers
    }

    public synchronized int take() throws InterruptedException {
        while (queue.isEmpty()) {
            System.out.println(Thread.currentThread().getName() + " waiting (buffer empty)");
            wait();
        }
        int item = queue.poll();
        System.out.println(Thread.currentThread().getName() + " consumed " + item
                + "  | buffer size = " + queue.size());
        notifyAll(); // Wake ALL waiting producers
        return item;
    }
}

public class MultiProducerConsumer {
    public static void main(String[] args) {
        BoundedBuffer buffer = new BoundedBuffer(3); // Buffer holds max 3 items

        // 2 producers
        for (int p = 1; p <= 2; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    for (int i = 1; i <= 5; i++) {
                        buffer.put(producerId * 100 + i);
                        Thread.sleep(200);
                    }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, "Producer-" + p).start();
        }

        // 2 consumers
        for (int c = 1; c <= 2; c++) {
            new Thread(() -> {
                try {
                    for (int i = 1; i <= 5; i++) {
                        buffer.take();
                        Thread.sleep(400);
                    }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, "Consumer-" + c).start();
        }
    }
}
```

---

## 9. volatile Keyword

`volatile` solves the **visibility problem**: without it, threads may read stale cached values of shared variables from their CPU cache instead of main memory.

```
Without volatile:

  Main Memory:  flag = true
                               ┌──────────────────┐
  Thread-1 cache: flag = false │ Stale! Not synced │
                               └──────────────────┘

With volatile:

  Main Memory:  flag = true
                               ┌──────────────────────────────────┐
  Thread-1:  reads from main  │ Always sees latest value ✅       │
             memory directly  └──────────────────────────────────┘
```

### Volatile Flag Example

```java
public class VolatileDemo {
    // Without volatile, the JVM may optimize this into an infinite loop
    private static volatile boolean stopRequested = false;

    public static void main(String[] args) throws InterruptedException {
        Thread worker = new Thread(() -> {
            System.out.println("Worker started");
            while (!stopRequested) {
                // Doing work...
            }
            System.out.println("Worker stopped cleanly.");
        });

        worker.start();
        Thread.sleep(2000);
        stopRequested = true;  // Main thread writes; worker immediately sees it
        System.out.println("Stop signal sent.");
        worker.join();
    }
}
```

### `volatile` vs `synchronized`

| Feature            | `volatile`                  | `synchronized`                    |
|--------------------|-----------------------------|-----------------------------------|
| Visibility         | ✅ Guarantees               | ✅ Guarantees                     |
| Atomicity          | ❌ Only for read/write of single variable | ✅ For compound operations |
| Mutual Exclusion   | ❌ No blocking              | ✅ One thread at a time           |
| Performance        | Fast (no lock overhead)     | Slower (lock/unlock overhead)     |
| Use Case           | Simple flags & status vars  | Compound operations like `count++` |

```java
// volatile is SAFE here — single write, single read
volatile boolean isReady = false;

// volatile is NOT SAFE here — count++ is read-modify-write (3 steps)
volatile int count = 0;
count++;  // ← NOT atomic! Use AtomicInteger instead
```

---

## 10. Deadlock, Livelock & Starvation

### ☠️ Deadlock

Two or more threads **wait forever** for each other's locks.

```
Thread-1 holds Lock-A, wants Lock-B →
Thread-2 holds Lock-B, wants Lock-A →
      CIRCULAR WAIT → DEADLOCK
```

```java
public class DeadlockDemo {
    static final Object lockA = new Object();
    static final Object lockB = new Object();

    public static void main(String[] args) {
        Thread t1 = new Thread(() -> {
            synchronized (lockA) {
                System.out.println("T1 acquired Lock-A");
                try { Thread.sleep(100); } catch (InterruptedException e) {}
                System.out.println("T1 waiting for Lock-B...");
                synchronized (lockB) {   // ← BLOCKED, T2 holds B
                    System.out.println("T1 acquired Lock-B");
                }
            }
        });

        Thread t2 = new Thread(() -> {
            synchronized (lockB) {
                System.out.println("T2 acquired Lock-B");
                try { Thread.sleep(100); } catch (InterruptedException e) {}
                System.out.println("T2 waiting for Lock-A...");
                synchronized (lockA) {   // ← BLOCKED, T1 holds A
                    System.out.println("T2 acquired Lock-A");
                }
            }
        });

        t1.start();
        t2.start();
        // Program HANGS here indefinitely!
    }
}
```

#### Deadlock Prevention — Always Acquire Locks in the Same Order

```java
public class DeadlockFixed {
    static final Object lock1 = new Object();
    static final Object lock2 = new Object();

    public static void main(String[] args) {
        // BOTH threads acquire lock1 FIRST, then lock2 — no circular wait possible!
        Thread t1 = new Thread(() -> {
            synchronized (lock1) {
                synchronized (lock2) {
                    System.out.println("T1 has both locks.");
                }
            }
        });

        Thread t2 = new Thread(() -> {
            synchronized (lock1) {   // Same order!
                synchronized (lock2) {
                    System.out.println("T2 has both locks.");
                }
            }
        });

        t1.start();
        t2.start();
    }
}
```

#### Detect Deadlock Using `ThreadMXBean`

```java
import java.lang.management.*;

ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
long[] deadlockedIds = tmx.findDeadlockedThreads();

if (deadlockedIds != null) {
    ThreadInfo[] info = tmx.getThreadInfo(deadlockedIds, true, true);
    System.out.println("DEADLOCK DETECTED:");
    for (ThreadInfo ti : info) {
        System.out.println(ti);
    }
}
```

---

### ⚡ Livelock

Threads are **active but make no progress** — they keep reacting to each other without completing work.

```java
// Classic livelock: two threads keep "politely" backing off
class Resource { volatile boolean inUse = false; }

// Thread-1 and Thread-2 both see the other is using the resource
// and keep yielding — neither ever completes
```

> **Real analogy:** Two people in a corridor both step aside for the other — simultaneously — forever.

---

### 😴 Starvation

A low-priority thread **never gets CPU time** because high-priority threads continuously preempt it.

```java
// High priority threads keep getting scheduled, leaving low-priority starved
Thread high1 = new Thread(task); high1.setPriority(10);
Thread high2 = new Thread(task); high2.setPriority(10);
Thread low   = new Thread(task); low.setPriority(1);   // May never run!

high1.start(); high2.start(); low.start();
```

---

## 11. ReentrantLock & Locks API

`ReentrantLock` gives you everything `synchronized` gives, **plus**:

| Feature                     | `synchronized` | `ReentrantLock`     |
|-----------------------------|:--------------:|:-------------------:|
| Automatic unlock            | ✅             | ❌ (manual `unlock`) |
| Try-lock without blocking   | ❌             | ✅ `tryLock()`       |
| Timed lock attempt          | ❌             | ✅ `tryLock(ms)`     |
| Interruptible lock wait     | ❌             | ✅ `lockInterruptibly()` |
| Fairness (FIFO order)       | ❌             | ✅ `new ReentrantLock(true)` |
| Multiple conditions         | ❌ (1 implicit)| ✅ `newCondition()`  |
| Check if locked             | ❌             | ✅ `isLocked()`      |

---

### Basic ReentrantLock

```java
import java.util.concurrent.locks.*;

class SafeCounter {
    private int count = 0;
    private final ReentrantLock lock = new ReentrantLock();

    public void increment() {
        lock.lock();
        try {
            count++;
        } finally {
            lock.unlock(); // ALWAYS in finally — otherwise lock is never released on exception!
        }
    }

    public int getCount() {
        lock.lock();
        try { return count; }
        finally { lock.unlock(); }
    }
}
```

---

### `tryLock()` — Non-blocking attempt

```java
import java.util.concurrent.locks.*;
import java.util.concurrent.TimeUnit;

class TryLockDemo {
    private final ReentrantLock lock = new ReentrantLock();

    public void doWork(String threadName) {
        // Try to acquire — don't block if unavailable
        if (lock.tryLock()) {
            try {
                System.out.println(threadName + " acquired lock, working...");
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        } else {
            System.out.println(threadName + " lock busy — skipping.");
        }
    }

    public void doWorkWithTimeout(String threadName) throws InterruptedException {
        // Wait at most 2 seconds to acquire
        if (lock.tryLock(2, TimeUnit.SECONDS)) {
            try {
                System.out.println(threadName + " got lock within timeout.");
            } finally {
                lock.unlock();
            }
        } else {
            System.out.println(threadName + " timed out waiting for lock.");
        }
    }
}
```

---

### `Condition` — Replacement for `wait()` / `notify()`

```java
import java.util.concurrent.locks.*;

class ConditionProducerConsumer {
    private final ReentrantLock lock       = new ReentrantLock();
    private final Condition     notFull    = lock.newCondition();
    private final Condition     notEmpty   = lock.newCondition();

    private final int[]  buffer   = new int[5];
    private       int    count    = 0, putIdx = 0, takeIdx = 0;

    public void put(int item) throws InterruptedException {
        lock.lock();
        try {
            while (count == buffer.length) notFull.await();  // Wait on notFull condition
            buffer[putIdx] = item;
            putIdx = (putIdx + 1) % buffer.length;
            count++;
            System.out.println("Put: " + item + " | count=" + count);
            notEmpty.signal(); // Signal that buffer is no longer empty
        } finally {
            lock.unlock();
        }
    }

    public int take() throws InterruptedException {
        lock.lock();
        try {
            while (count == 0) notEmpty.await(); // Wait on notEmpty condition
            int item = buffer[takeIdx];
            takeIdx = (takeIdx + 1) % buffer.length;
            count--;
            System.out.println("Took: " + item + " | count=" + count);
            notFull.signal(); // Signal that buffer is no longer full
            return item;
        } finally {
            lock.unlock();
        }
    }
}
```

---

### ReadWriteLock — Multiple Readers, One Writer

```java
import java.util.concurrent.locks.*;

class SharedData {
    private String data = "initial";
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    // Multiple threads can READ simultaneously
    public String read() {
        rwLock.readLock().lock();
        try {
            System.out.println(Thread.currentThread().getName() + " reading: " + data);
            Thread.sleep(100);
            return data;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return data;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // Only ONE thread can WRITE at a time (blocks all readers too)
    public void write(String newData) {
        rwLock.writeLock().lock();
        try {
            System.out.println(Thread.currentThread().getName() + " writing: " + newData);
            Thread.sleep(500);
            data = newData;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            rwLock.writeLock().unlock();
        }
    }
}
```

---

## 12. Executor Framework & Thread Pools

Creating a raw `new Thread()` for every task is **expensive** (OS-level resource). Thread pools reuse a fixed set of threads.

```
Submit Task ──► ExecutorService (pool) ──► [Thread-1] [Thread-2] [Thread-3]
                     │                          │           │          │
              Internal queue              Execute task  Execute task  Execute task
              (pending tasks wait here)
```

### Four Built-in Pool Types

```java
import java.util.concurrent.*;

// 1. Fixed Pool — fixed number of threads; extra tasks queue up
ExecutorService fixed = Executors.newFixedThreadPool(4);

// 2. Cached Pool — grows and shrinks as needed; idles threads die after 60s
ExecutorService cached = Executors.newCachedThreadPool();

// 3. Single Thread — 1 thread; tasks run sequentially in submission order
ExecutorService single = Executors.newSingleThreadExecutor();

// 4. Scheduled Pool — run tasks after a delay, or repeatedly
ScheduledExecutorService scheduled = Executors.newScheduledThreadPool(2);
```

---

### Fixed Thread Pool — Full Example

```java
import java.util.concurrent.*;

public class FixedPoolDemo {
    public static void main(String[] args) throws InterruptedException {

        ExecutorService pool = Executors.newFixedThreadPool(3);

        System.out.println("Submitting 8 tasks to a pool of 3 threads:\n");

        for (int i = 1; i <= 8; i++) {
            final int taskId = i;
            pool.submit(() -> {
                System.out.printf("Task %d started   on %s%n",
                        taskId, Thread.currentThread().getName());
                try { Thread.sleep(500); } // Simulate work
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                System.out.printf("Task %d finished  on %s%n",
                        taskId, Thread.currentThread().getName());
            });
        }

        pool.shutdown();                          // Stop accepting new tasks
        pool.awaitTermination(10, TimeUnit.SECONDS); // Wait for all to finish
        System.out.println("\nAll tasks complete!");
    }
}
```

**Output:**
```
Task 1 started   on pool-1-thread-1
Task 2 started   on pool-1-thread-2
Task 3 started   on pool-1-thread-3
Task 1 finished  on pool-1-thread-1   ← thread reused!
Task 4 started   on pool-1-thread-1
Task 2 finished  on pool-1-thread-2
Task 5 started   on pool-1-thread-2
...
All tasks complete!
```

---

### Scheduled Thread Pool

```java
import java.util.concurrent.*;

public class ScheduledPoolDemo {
    public static void main(String[] args) throws InterruptedException {

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        // Run ONCE after 2-second delay
        scheduler.schedule(
            () -> System.out.println("One-time task at " + System.currentTimeMillis()),
            2, TimeUnit.SECONDS
        );

        // Run EVERY 1 second, with initial 1-second delay
        scheduler.scheduleAtFixedRate(
            () -> System.out.println("Heartbeat: " + System.currentTimeMillis()),
            1, 1, TimeUnit.SECONDS
        );

        // Run 1 second AFTER the previous execution finishes
        scheduler.scheduleWithFixedDelay(
            () -> System.out.println("FixedDelay task"),
            1, 1, TimeUnit.SECONDS
        );

        Thread.sleep(5000);
        scheduler.shutdown();
    }
}
```

---

### ExecutorService Lifecycle

```
ExecutorService States:
  RUNNING ──► shutdown() ──► SHUTTING DOWN ──► (all tasks finish) ──► TERMINATED
           └► shutdownNow() ──► (interrupts running tasks) ──► TERMINATED
```

```java
pool.shutdown();                              // Graceful — finishes current tasks, no new tasks
pool.shutdownNow();                           // Forceful — interrupts running tasks
pool.awaitTermination(60, TimeUnit.SECONDS);  // Block until shutdown complete or timeout
pool.isShutdown();                            // True once shutdown() has been called
pool.isTerminated();                          // True once all tasks have completed post-shutdown
```

---

## 13. Callable, Future & FutureTask

`Runnable.run()` returns `void`. When you need a **result** from a thread, use `Callable<T>`.

| Feature              | `Runnable`   | `Callable<T>`         |
|----------------------|--------------|-----------------------|
| Method               | `run()`      | `call()`              |
| Return type          | `void`       | Generic `T`           |
| Checked exceptions   | ❌ Can't throw | ✅ Can throw          |
| Use with pool        | `execute()` / `submit()` | `submit()` |

---

### Callable & Future

```java
import java.util.concurrent.*;

public class CallableFutureDemo {
    public static void main(String[] args) throws ExecutionException, InterruptedException {

        ExecutorService pool = Executors.newFixedThreadPool(3);

        // Task 1: compute factorial
        Callable<Long> factTask = () -> {
            long result = 1;
            for (int i = 1; i <= 15; i++) result *= i;
            Thread.sleep(1000); // Simulate heavy computation
            return result;
        };

        // Task 2: count characters
        Callable<Integer> charTask = () -> {
            Thread.sleep(500);
            return "Hello Multithreading!".length();
        };

        // Submit returns a Future — represents a pending result
        Future<Long>    factFuture = pool.submit(factTask);
        Future<Integer> charFuture = pool.submit(charTask);

        System.out.println("Tasks submitted, doing other work...");
        Thread.sleep(200); // Main thread doing other stuff

        // get() BLOCKS until result is ready
        System.out.println("15! = " + factFuture.get());          // 1307674368000
        System.out.println("Char count = " + charFuture.get());   // 21

        pool.shutdown();
    }
}
```

---

### Future Methods

```java
Future<String> f = pool.submit(() -> "result");

f.get();                              // Block until done, return result
f.get(5, TimeUnit.SECONDS);           // Block at most 5s, throws TimeoutException
f.isDone();                           // true if completed (normally, exception, or cancelled)
f.isCancelled();                      // true if cancelled
f.cancel(true);                       // Attempt to cancel; true = interrupt if running
```

---

### Multiple Futures — `invokeAll()` and `invokeAny()`

```java
import java.util.concurrent.*;
import java.util.*;

public class InvokeDemo {
    public static void main(String[] args) throws InterruptedException, ExecutionException {
        ExecutorService pool = Executors.newFixedThreadPool(4);

        List<Callable<String>> tasks = List.of(
            () -> { Thread.sleep(300); return "Result from Task A"; },
            () -> { Thread.sleep(100); return "Result from Task B"; },
            () -> { Thread.sleep(200); return "Result from Task C"; }
        );

        // invokeAll — waits for ALL tasks to complete
        System.out.println("=== invokeAll ===");
        List<Future<String>> allResults = pool.invokeAll(tasks);
        for (Future<String> f : allResults) {
            System.out.println(f.get());
        }

        // invokeAny — returns the FIRST successful result, cancels the rest
        System.out.println("\n=== invokeAny ===");
        String firstResult = pool.invokeAny(tasks); // Returns "Result from Task B" (fastest)
        System.out.println("First done: " + firstResult);

        pool.shutdown();
    }
}
```

---

### FutureTask — Runnable + Future in One

```java
import java.util.concurrent.*;

public class FutureTaskDemo {
    public static void main(String[] args) throws ExecutionException, InterruptedException {

        // FutureTask wraps a Callable and is itself a Runnable
        FutureTask<String> futureTask = new FutureTask<>(() -> {
            Thread.sleep(1000);
            return "FutureTask result: " + Thread.currentThread().getName();
        });

        Thread t = new Thread(futureTask, "FutureTaskThread");
        t.start();

        System.out.println("Main doing other work...");
        System.out.println("Result: " + futureTask.get()); // Blocks until done
    }
}
```

---

## 14. Atomic Classes

`java.util.concurrent.atomic` provides **lock-free** thread-safe operations using CPU-level CAS (Compare-And-Swap) hardware instructions.

### CAS (Compare-And-Swap) — How It Works

```
CAS(address, expectedValue, newValue):
  If memory[address] == expectedValue:
      memory[address] = newValue  (atomic!)
      return true
  Else:
      return false  (retry)
```

This is **optimistic locking** — no blocking, just retry if someone else changed the value first.

---

### AtomicInteger

```java
import java.util.concurrent.atomic.*;

public class AtomicDemo {
    public static void main(String[] args) throws InterruptedException {

        AtomicInteger counter = new AtomicInteger(0);

        Runnable task = () -> {
            for (int i = 0; i < 1000; i++) {
                counter.incrementAndGet();        // Atomic count++
            }
        };

        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        t1.start(); t2.start();
        t1.join();  t2.join();

        System.out.println("Final count: " + counter.get()); // Always 2000 ✅

        // Other useful AtomicInteger methods:
        AtomicInteger ai = new AtomicInteger(10);
        ai.getAndIncrement();                         // Returns 10, then sets to 11
        ai.incrementAndGet();                         // Sets to 12, returns 12
        ai.addAndGet(5);                              // Adds 5, returns 17
        ai.compareAndSet(17, 100);                    // If value == 17, set to 100 → true
        ai.getAndSet(0);                              // Returns 100, sets to 0
    }
}
```

---

### AtomicLong, AtomicBoolean, AtomicReference

```java
import java.util.concurrent.atomic.*;

// AtomicLong — for long counters (page views, bytes transferred, etc.)
AtomicLong pageViews = new AtomicLong(0);
pageViews.incrementAndGet();
System.out.println("Views: " + pageViews.get());

// AtomicBoolean — for flags (initialized once, checked often)
AtomicBoolean initialized = new AtomicBoolean(false);
if (initialized.compareAndSet(false, true)) {
    System.out.println("First initialization — setting up system.");
} else {
    System.out.println("Already initialized — skip.");
}

// AtomicReference — for atomic object reference swaps
AtomicReference<String> current = new AtomicReference<>("old");
boolean swapped = current.compareAndSet("old", "new");
System.out.println("Swapped: " + swapped + " | value: " + current.get());
```

---

### LongAdder vs AtomicLong — High-Contention Counters

```java
import java.util.concurrent.atomic.*;

// Under HIGH thread contention, LongAdder is much faster
// It maintains an array of cells — threads increment different cells,
// avoiding CAS retries. sum() merges all cells at the end.
LongAdder adder = new LongAdder();
adder.increment();
adder.add(5);
adder.sum();   // Total across all cells

// Use AtomicLong when: you need compareAndSet
// Use LongAdder  when: you just need a counter with many writers
```

---

## 15. Concurrent Collections

Thread-safe collections from `java.util.concurrent` — designed for concurrent access **without** locking the entire collection.

---

### ConcurrentHashMap

`HashMap` is **not thread-safe**. `Hashtable` synchronizes every method (very slow). `ConcurrentHashMap` uses **segment-level locking** (Java 7) / **CAS + node-level locking** (Java 8+).

```java
import java.util.concurrent.*;

public class ConcurrentHashMapDemo {
    public static void main(String[] args) throws InterruptedException {

        ConcurrentHashMap<String, Integer> scores = new ConcurrentHashMap<>();

        Runnable addScores = () -> {
            for (int i = 0; i < 100; i++) {
                String key = "player-" + (i % 10);
                scores.merge(key, 1, Integer::sum); // Thread-safe increment
            }
        };

        Thread t1 = new Thread(addScores);
        Thread t2 = new Thread(addScores);
        t1.start(); t2.start();
        t1.join();  t2.join();

        int total = scores.values().stream().mapToInt(Integer::intValue).sum();
        System.out.println("Total scores: " + total); // Always 200

        // Atomic operations
        scores.putIfAbsent("newPlayer", 0);
        scores.computeIfAbsent("anotherPlayer", k -> 0);
        scores.computeIfPresent("player-0", (k, v) -> v + 10);
        scores.compute("player-1", (k, v) -> (v == null ? 0 : v) + 1);
    }
}
```

---

### CopyOnWriteArrayList

Every **write** (add/set/remove) creates a **fresh copy** of the underlying array. Read operations need no locking at all.

```java
import java.util.concurrent.*;

CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();

// Safe to iterate while other threads modify
list.add("Apple");
list.add("Banana");

// Iteration uses a snapshot — modifications during iteration won't cause ConcurrentModificationException
for (String item : list) {
    System.out.println(item);
    list.add("Concurrent add!"); // Safe — modifies a COPY
}

// ✅ Use when: reads >> writes (e.g., event listener lists)
// ❌ Avoid when: many writes (each write copies the entire array)
```

---

### BlockingQueue Family

```java
import java.util.concurrent.*;

// LinkedBlockingQueue — optionally bounded, linked-list backed
BlockingQueue<String> linked = new LinkedBlockingQueue<>(10);

// ArrayBlockingQueue — bounded, array backed, FIFO
BlockingQueue<String> array = new ArrayBlockingQueue<>(10);

// PriorityBlockingQueue — unbounded, elements sorted by natural order or Comparator
BlockingQueue<Integer> priority = new PriorityBlockingQueue<>();

// DelayQueue — elements only become available after a specified delay
// SynchronousQueue — no capacity; each put() must wait for a take()

// Core methods:
linked.put("task");             // Blocks if full
linked.take();                  // Blocks if empty
linked.offer("task", 1, TimeUnit.SECONDS); // Wait up to 1s; returns false if still full
linked.poll(1, TimeUnit.SECONDS);          // Wait up to 1s; returns null if still empty
```

### BlockingQueue Producer-Consumer — Best Practice

```java
import java.util.concurrent.*;

public class BlockingQueueProducerConsumer {

    private static final String POISON_PILL = "STOP"; // Shutdown signal

    public static void main(String[] args) throws InterruptedException {

        BlockingQueue<String> queue = new LinkedBlockingQueue<>(5);

        Thread producer = new Thread(() -> {
            try {
                String[] tasks = {"Task-1", "Task-2", "Task-3", "Task-4", "Task-5"};
                for (String task : tasks) {
                    queue.put(task);
                    System.out.println("Produced: " + task);
                    Thread.sleep(200);
                }
                queue.put(POISON_PILL); // Signal consumer to stop
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "Producer");

        Thread consumer = new Thread(() -> {
            try {
                while (true) {
                    String task = queue.take();
                    if (POISON_PILL.equals(task)) {
                        System.out.println("Consumer received stop signal.");
                        break;
                    }
                    System.out.println("Consumed: " + task);
                    Thread.sleep(400);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "Consumer");

        producer.start();
        consumer.start();
        producer.join();
        consumer.join();
        System.out.println("Done.");
    }
}
```

---

## 16. Concurrency Utilities

### CountDownLatch — "Start together" or "Wait for all to finish"

```java
import java.util.concurrent.*;

public class CountDownLatchDemo {
    public static void main(String[] args) throws InterruptedException {

        int numWorkers = 5;
        CountDownLatch startSignal = new CountDownLatch(1); // 1 = not yet started
        CountDownLatch doneSignal  = new CountDownLatch(numWorkers);

        for (int i = 1; i <= numWorkers; i++) {
            final int id = i;
            new Thread(() -> {
                try {
                    System.out.println("Worker " + id + " ready, waiting for start...");
                    startSignal.await(); // All workers wait here
                    System.out.println("Worker " + id + " RUNNING!");
                    Thread.sleep((long)(Math.random() * 1000));
                    System.out.println("Worker " + id + " done.");
                    doneSignal.countDown(); // Signal one worker done
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }).start();
        }

        Thread.sleep(1000); // Pretend setup takes time
        System.out.println("\n🚦 GO! Starting all workers...\n");
        startSignal.countDown(); // Release all waiting workers at once

        doneSignal.await(); // Wait until all workers finish
        System.out.println("\n✅ All workers finished!");
    }
}
```

---

### CyclicBarrier — "Rendezvous" — wait until everyone arrives, then continue together

```java
import java.util.concurrent.*;

public class CyclicBarrierDemo {
    public static void main(String[] args) {

        int parties = 3;
        CyclicBarrier barrier = new CyclicBarrier(parties,
            () -> System.out.println("\n--- All parties arrived! Proceeding to next phase ---\n")
        );

        for (int i = 1; i <= parties; i++) {
            final int id = i;
            new Thread(() -> {
                try {
                    System.out.println("Thread " + id + " doing Phase-1 work...");
                    Thread.sleep((long)(Math.random() * 1000));
                    System.out.println("Thread " + id + " waiting at barrier...");
                    barrier.await(); // Wait until ALL threads reach here

                    System.out.println("Thread " + id + " doing Phase-2 work...");
                    Thread.sleep((long)(Math.random() * 1000));
                    System.out.println("Thread " + id + " waiting at barrier again...");
                    barrier.await(); // Barrier is REUSABLE (unlike CountDownLatch)

                    System.out.println("Thread " + id + " doing Phase-3 work...");
                } catch (Exception e) { Thread.currentThread().interrupt(); }
            }).start();
        }
    }
}
```

---

### Semaphore — Limit concurrent access to a resource

```java
import java.util.concurrent.*;

public class SemaphoreDemo {
    // Only 3 database connections allowed at once
    static final Semaphore dbConnections = new Semaphore(3);

    static void queryDatabase(int requestId) throws InterruptedException {
        System.out.println("Request " + requestId + " waiting for DB connection...");
        dbConnections.acquire();  // Blocks if all 3 permits are taken
        try {
            System.out.println("Request " + requestId + " GOT connection | available=" +
                    dbConnections.availablePermits());
            Thread.sleep(1000); // Simulate query
        } finally {
            System.out.println("Request " + requestId + " RELEASED connection");
            dbConnections.release();
        }
    }

    public static void main(String[] args) {
        for (int i = 1; i <= 8; i++) {
            final int reqId = i;
            new Thread(() -> {
                try { queryDatabase(reqId); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }).start();
        }
    }
}
```

---

### Exchanger — Swap data between two threads

```java
import java.util.concurrent.*;

public class ExchangerDemo {
    public static void main(String[] args) {

        Exchanger<String> exchanger = new Exchanger<>();

        Thread producer = new Thread(() -> {
            try {
                String data = "DATA PACKET from Producer";
                System.out.println("Producer sending: " + data);
                String received = exchanger.exchange(data); // Blocks until partner arrives
                System.out.println("Producer received: " + received);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        Thread consumer = new Thread(() -> {
            try {
                String ack = "ACK from Consumer";
                System.out.println("Consumer sending: " + ack);
                String received = exchanger.exchange(ack); // Blocks until partner arrives
                System.out.println("Consumer received: " + received);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        producer.start();
        consumer.start();
    }
}
```

---

## 17. ThreadLocal

`ThreadLocal<T>` gives each thread its **own isolated copy** of a variable — no synchronization needed.

```
Shared variable (BAD):
  Thread-1 ──┐
  Thread-2 ──┼──► shared int counter = 0   (race condition!)
  Thread-3 ──┘

ThreadLocal (GOOD):
  Thread-1 ──► own copy: counter = 5
  Thread-2 ──► own copy: counter = 2
  Thread-3 ──► own copy: counter = 8
```

```java
public class ThreadLocalDemo {

    // Each thread gets its own SimpleDateFormat instance (SimpleDateFormat is NOT thread-safe!)
    private static final ThreadLocal<java.text.SimpleDateFormat> dateFormat =
        ThreadLocal.withInitial(() -> new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));

    // Transaction ID per thread
    private static final ThreadLocal<String> transactionId = new ThreadLocal<>();

    static String formatDate(java.util.Date date) {
        return dateFormat.get().format(date); // Safe — each thread has its own instance
    }

    public static void main(String[] args) throws InterruptedException {

        for (int i = 1; i <= 3; i++) {
            final int threadId = i;
            Thread t = new Thread(() -> {
                transactionId.set("TXN-" + threadId); // Set this thread's transaction ID
                try {
                    System.out.println(Thread.currentThread().getName()
                            + " | TxnId = " + transactionId.get()
                            + " | Date  = " + formatDate(new java.util.Date()));
                    Thread.sleep(100);
                    System.out.println(Thread.currentThread().getName()
                            + " | TxnId still = " + transactionId.get()); // Still isolated!
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    transactionId.remove(); // ⚠️ ALWAYS remove to prevent memory leaks in thread pools!
                }
            }, "Thread-" + i);
            t.start();
            t.join();
        }
    }
}
```

> ⚠️ **Always call `remove()`** in thread pools — threads are reused, so leftover `ThreadLocal` values from previous tasks can leak into new tasks.

---

## 18. Fork/Join Framework

The Fork/Join framework is designed for **divide-and-conquer** parallelism — split a big task into smaller tasks, run them in parallel, combine results.

```
Big Task
   │
   ├──► Sub-task A ──► Sub-task A1
   │                └─► Sub-task A2
   └──► Sub-task B ──► Sub-task B1
                    └─► Sub-task B2
```

Uses **work-stealing**: idle threads steal tasks from busy threads' queues.

```java
import java.util.concurrent.*;

// Parallel sum of a large array
class ParallelSum extends RecursiveTask<Long> {

    private static final int THRESHOLD = 1000; // Base case threshold
    private final int[] array;
    private final int   from, to;

    ParallelSum(int[] array, int from, int to) {
        this.array = array;
        this.from  = from;
        this.to    = to;
    }

    @Override
    protected Long compute() {
        int length = to - from;

        // BASE CASE: small enough to compute directly
        if (length <= THRESHOLD) {
            long sum = 0;
            for (int i = from; i < to; i++) sum += array[i];
            return sum;
        }

        // DIVIDE: split into two halves
        int mid = from + length / 2;
        ParallelSum leftTask  = new ParallelSum(array, from, mid);
        ParallelSum rightTask = new ParallelSum(array, mid,  to);

        leftTask.fork();              // Submit left task to thread pool
        long rightResult = rightTask.compute(); // Compute right in current thread
        long leftResult  = leftTask.join();     // Wait for left result

        return leftResult + rightResult;        // COMBINE
    }
}

public class ForkJoinDemo {
    public static void main(String[] args) {

        int size = 10_000_000;
        int[] data = new int[size];
        for (int i = 0; i < size; i++) data[i] = i + 1;

        ForkJoinPool pool = ForkJoinPool.commonPool();

        long start = System.currentTimeMillis();
        long sum   = pool.invoke(new ParallelSum(data, 0, size));
        long end   = System.currentTimeMillis();

        System.out.println("Sum = " + sum);
        System.out.println("Time = " + (end - start) + "ms");
        System.out.println("Parallelism = " + pool.getParallelism());
    }
}
```

---

## 19. CompletableFuture

`CompletableFuture<T>` (Java 8+) enables **non-blocking async programming** with a fluent, chainable API. It replaces nested callbacks.

---

### Creating CompletableFutures

```java
import java.util.concurrent.*;

// 1. Already-completed future
CompletableFuture<String> done = CompletableFuture.completedFuture("immediate result");

// 2. Run async (no return value) — uses ForkJoinPool.commonPool()
CompletableFuture<Void> fire = CompletableFuture.runAsync(
    () -> System.out.println("Fire and forget!")
);

// 3. Supply async (with return value)
CompletableFuture<String> future = CompletableFuture.supplyAsync(
    () -> "Hello from async!"
);

// 4. Custom executor
ExecutorService pool = Executors.newFixedThreadPool(4);
CompletableFuture<String> custom = CompletableFuture.supplyAsync(
    () -> "Running on custom pool", pool
);
```

---

### Chaining — `thenApply`, `thenAccept`, `thenRun`

```java
import java.util.concurrent.*;

public class CompletableFutureChainDemo {
    public static void main(String[] args) throws ExecutionException, InterruptedException {

        CompletableFuture<String> result = CompletableFuture
            .supplyAsync(() -> {
                // Step 1: fetch user ID from "database"
                System.out.println("Fetching user ID...");
                sleep(500);
                return 42;
            })
            .thenApply(userId -> {
                // Step 2: transform — fetch user details
                System.out.println("Fetching details for userId: " + userId);
                sleep(300);
                return "UserProfile{id=" + userId + ", name='Alice'}";
            })
            .thenApply(profile -> {
                // Step 3: transform — format for display
                return "FORMATTED: " + profile.toUpperCase();
            });

        // thenAccept — consume the result (no return value)
        result.thenAccept(formatted -> System.out.println("Final: " + formatted));

        result.get(); // Wait for pipeline to complete
        System.out.println("Main continues...");
    }

    static void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {}
    }
}
```

---

### Combining — `thenCombine`, `allOf`, `anyOf`

```java
import java.util.concurrent.*;

public class CompletableFutureCombineDemo {
    public static void main(String[] args) throws ExecutionException, InterruptedException {

        // thenCombine — combine two independent futures
        CompletableFuture<String> userFuture   = CompletableFuture.supplyAsync(() -> { sleep(400); return "Alice"; });
        CompletableFuture<Integer> ageFuture   = CompletableFuture.supplyAsync(() -> { sleep(300); return 30; });

        CompletableFuture<String> combined = userFuture.thenCombine(
            ageFuture,
            (name, age) -> name + " is " + age + " years old"
        );
        System.out.println(combined.get()); // Alice is 30 years old

        // allOf — wait for ALL futures to complete
        CompletableFuture<Void> all = CompletableFuture.allOf(
            CompletableFuture.runAsync(() -> { sleep(300); System.out.println("Task A done"); }),
            CompletableFuture.runAsync(() -> { sleep(100); System.out.println("Task B done"); }),
            CompletableFuture.runAsync(() -> { sleep(200); System.out.println("Task C done"); })
        );
        all.get(); // Blocks until ALL are done
        System.out.println("All tasks done.");

        // anyOf — return result of FIRST completed future
        CompletableFuture<Object> first = CompletableFuture.anyOf(
            CompletableFuture.supplyAsync(() -> { sleep(500); return "Slow server"; }),
            CompletableFuture.supplyAsync(() -> { sleep(100); return "Fast server"; }),
            CompletableFuture.supplyAsync(() -> { sleep(300); return "Medium server"; })
        );
        System.out.println("First result: " + first.get()); // "Fast server"
    }

    static void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {}
    }
}
```

---

### Exception Handling

```java
import java.util.concurrent.*;

public class CompletableFutureExceptionDemo {
    public static void main(String[] args) throws ExecutionException, InterruptedException {

        // exceptionally — recover from exception
        CompletableFuture<String> recovered = CompletableFuture
            .supplyAsync(() -> {
                throw new RuntimeException("Service unavailable!");
            })
            .exceptionally(ex -> {
                System.out.println("Caught: " + ex.getMessage());
                return "Default value"; // Fallback
            });
        System.out.println(recovered.get()); // Default value

        // handle — always called (with result OR exception)
        CompletableFuture<String> handled = CompletableFuture
            .supplyAsync(() -> "Success!")
            .handle((result, ex) -> {
                if (ex != null) {
                    System.out.println("Exception: " + ex.getMessage());
                    return "Error fallback";
                }
                return result + " (handled)";
            });
        System.out.println(handled.get()); // Success! (handled)

        // whenComplete — side-effect after completion (doesn't transform result)
        CompletableFuture
            .supplyAsync(() -> "Result")
            .whenComplete((result, ex) -> {
                if (ex == null) System.out.println("Completed with: " + result);
                else            System.out.println("Failed with: " + ex.getMessage());
            })
            .get();
    }
}
```

---

### Real-World: Parallel API Calls

```java
import java.util.concurrent.*;

public class ParallelApiCalls {
    static String fetchUser(int id)    { sleep(400); return "User#"    + id; }
    static String fetchOrders(int id)  { sleep(300); return "Orders#"  + id; }
    static String fetchAddress(int id) { sleep(500); return "Address#" + id; }

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        int userId = 42;

        long start = System.currentTimeMillis();

        // Run all three API calls IN PARALLEL
        CompletableFuture<String> userFuture    = CompletableFuture.supplyAsync(() -> fetchUser(userId));
        CompletableFuture<String> orderFuture   = CompletableFuture.supplyAsync(() -> fetchOrders(userId));
        CompletableFuture<String> addressFuture = CompletableFuture.supplyAsync(() -> fetchAddress(userId));

        // Wait for all and combine
        CompletableFuture<String> dashboard = userFuture
            .thenCombine(orderFuture,   (u, o) -> u + " | " + o)
            .thenCombine(addressFuture, (partial, a) -> partial + " | " + a);

        System.out.println("Dashboard: " + dashboard.get());
        System.out.println("Time: " + (System.currentTimeMillis() - start) + "ms");
        // Time ≈ 500ms (longest call), not 400+300+500=1200ms!
    }

    static void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {}
    }
}
```

---

## 20. Interview Questions & Answers

| # | Question | Answer |
|---|----------|--------|
| 1 | `start()` vs `run()` | `start()` creates a **new OS thread** then calls `run()`; calling `run()` directly runs on the **current thread** |
| 2 | `sleep()` vs `wait()` | `sleep()` does NOT release the lock, is a `Thread` static method; `wait()` RELEASES the lock, is on `Object`, must be in `synchronized` |
| 3 | `notify()` vs `notifyAll()` | `notify()` wakes ONE arbitrary thread; `notifyAll()` wakes ALL waiting threads (safer, avoids missed signals) |
| 4 | What is a race condition? | Multiple threads accessing shared data concurrently, final result depends on thread scheduling order |
| 5 | What is a deadlock? | Two+ threads hold locks and wait for each other's locks — circular wait, never resolves |
| 6 | How to prevent deadlock? | Consistent lock ordering, `tryLock()` with timeout, avoid nested locks |
| 7 | `volatile` vs `synchronized` | `volatile` = visibility only (no atomicity); `synchronized` = visibility + atomicity + mutual exclusion |
| 8 | When to use `volatile`? | Simple boolean flags and single-variable state; NOT for compound operations like `count++` |
| 9 | `Runnable` vs `Callable` | `Runnable.run()` returns void; `Callable.call()` returns a value and can throw checked exceptions |
| 10 | What is a thread pool? | Fixed set of reusable threads managed by `ExecutorService`; avoids overhead of creating/destroying threads |
| 11 | `submit()` vs `execute()` | `execute()` returns void (fire-and-forget); `submit()` returns a `Future` (can retrieve result/exception) |
| 12 | `synchronized` vs `ReentrantLock` | `ReentrantLock` has `tryLock()`, timeout, fairness, multiple conditions; `synchronized` is simpler |
| 13 | What is `ThreadLocal`? | Per-thread variable storage — each thread gets its own copy, no synchronization needed |
| 14 | What is `CompletableFuture`? | Async computation pipeline; chainable, non-blocking, supports combining and exception handling |
| 15 | `ConcurrentHashMap` vs `Hashtable` | `ConcurrentHashMap` uses fine-grained locking (per-bucket); `Hashtable` locks the whole map on every operation |
| 16 | What is `CountDownLatch`? | Allows one+ threads to wait until a set of operations (countdowns) complete; NOT reusable |
| 17 | What is `CyclicBarrier`? | Makes a group of threads wait at a barrier until all arrive, then proceed together; **IS** reusable |
| 18 | What is `Semaphore`? | Controls access to a resource pool with a fixed number of permits |
| 19 | What is pinning (Java 21)? | Virtual thread cannot unmount from carrier thread during blocking (usually inside `synchronized`) |
| 20 | `AtomicInteger` vs `synchronized int` | `AtomicInteger` uses CAS (lock-free hardware instruction); faster under low-medium contention |

---

## Complete Reference Summary

```
Java Multithreading & Concurrency
│
├── BASICS
│   ├── Thread Lifecycle:  NEW → RUNNABLE → RUNNING → BLOCKED/WAITING → TERMINATED
│   ├── Creating Threads:  Thread | Runnable | Lambda | Anonymous | ThreadFactory
│   ├── Thread Attributes: name, priority (1-10), daemon
│   └── Key Methods:       start, sleep, join, interrupt, yield, isAlive
│
├── SYNCHRONIZATION
│   ├── synchronized method  → locks this
│   ├── synchronized block   → locks chosen object
│   ├── static synchronized  → locks ClassName.class
│   └── volatile             → visibility only (no atomicity)
│
├── INTER-THREAD COMMUNICATION
│   └── wait() / notify() / notifyAll() — must be inside synchronized
│
├── CONCURRENCY PROBLEMS
│   ├── Race Condition  → fix with synchronized / atomic
│   ├── Deadlock        → fix with consistent lock ordering / tryLock
│   ├── Livelock        → threads active but making no progress
│   └── Starvation      → low-priority threads never scheduled
│
├── LOCKS API
│   ├── ReentrantLock       → tryLock, timed, interruptible, fair
│   ├── ReadWriteLock       → many readers OR one writer
│   └── Condition           → fine-grained wait/signal per condition
│
├── EXECUTOR FRAMEWORK
│   ├── FixedThreadPool     → fixed N threads
│   ├── CachedThreadPool    → grows/shrinks dynamically
│   ├── SingleThreadExecutor → sequential execution
│   └── ScheduledThreadPool → delayed / periodic tasks
│
├── CALLABLES & FUTURES
│   ├── Callable<T>   → returns value, throws checked exceptions
│   ├── Future<T>     → handle to async result (get, cancel, isDone)
│   └── FutureTask<T> → Runnable + Future combined
│
├── ATOMIC CLASSES
│   ├── AtomicInteger / AtomicLong / AtomicBoolean / AtomicReference
│   ├── CAS (Compare-And-Swap) — lock-free hardware instruction
│   └── LongAdder — faster counter under high contention
│
├── CONCURRENT COLLECTIONS
│   ├── ConcurrentHashMap       → thread-safe Map (fine-grained locking)
│   ├── CopyOnWriteArrayList    → thread-safe List (copy-on-write)
│   ├── BlockingQueue family    → LinkedBlockingQueue, ArrayBlockingQueue, etc.
│   └── ConcurrentLinkedQueue   → lock-free FIFO queue
│
├── CONCURRENCY UTILITIES
│   ├── CountDownLatch  → wait for N events (not reusable)
│   ├── CyclicBarrier   → rendezvous point for N threads (reusable)
│   ├── Semaphore       → limit concurrent resource access
│   └── Exchanger       → swap data between exactly 2 threads
│
├── ADVANCED
│   ├── ThreadLocal      → per-thread variable isolation
│   ├── Fork/Join        → divide-and-conquer parallelism (work-stealing)
│   └── CompletableFuture→ async pipeline (chainable, combinable, non-blocking)
│
└── JAVA 21
    └── Virtual Threads  → millions of lightweight JVM-managed threads (JEP 444)
```

---

*Made with ❤️ for Java developers — covers Java 8 through Java 21*
