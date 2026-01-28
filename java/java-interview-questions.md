# 🚀 Java Interview Masterclass: 100 Q&A (2026 Edition)

A comprehensive repository containing in-depth explanations for 100 essential Java, Spring, and System Design interview questions. This guide is optimized for senior-level engineering interviews in 2026.

---

## 📂 Table of Contents
1. [Core Java & JVM Internals](#i-core-java--jvm-internals)
2. [Java 8 to Java 25 & Concurrency](#ii-java-8-to-java-25--concurrency)
3. [Spring Framework & Microservices](#iii-spring-framework--microservices)
4. [JVM Tuning & Troubleshooting](#iv-jvm-tuning--troubleshooting)
5. [Design Patterns & System Design](#v-design-patterns--system-design)

---

## I. Core Java & JVM Internals

### 1. Explain JVM Architecture.
The JVM (Java Virtual Machine) is an engine that provides a runtime environment to drive Java Code.
*   **Class Loader Subsystem:** Performs Loading (Bootstrap, Platform, Application loaders), Linking (Verification, Preparation, Resolution), and Initialization.
*   **Runtime Data Areas:**
    *   **Method Area/Metaspace:** Stores class structures, field/method data, and static variables. In 2026, Metaspace is entirely off-heap.
    *   **Heap Area:** The shared memory where all objects are stored. It is divided into Young (Eden, S0, S1) and Old generations.
    *   **Stack Area:** Per-thread memory storing local variables and partial results.
    *   **PC Registers:** Contains the address of the currently executing instruction.
*   **Execution Engine:** Includes the **Interpreter**, **JIT Compiler** (optimizes hot spots into native code), and **Garbage Collector**.

### 2. Difference between JDK, JRE, and JVM.
*   **JVM:** The abstract machine that executes Bytecode. It is platform-dependent (different JVMs for Mac, Windows, Linux).
*   **JRE:** JVM + Library sets (rt.jar, etc.). It provides the environment to *run* an app.
*   **JDK:** JRE + Development Tools (`javac`, `jdb`, `jvisualvm`). It is the full kit to *build* and *run* an app.

### 3. How Garbage Collection works in Java?
GC is the process of reclaiming heap space by destroying unreachable objects.
*   **Mark:** The GC identifies which objects are in use by traversing "GC Roots" (Stack references, static fields).
*   **Sweep:** It removes the objects that were not "marked."
*   **Compact:** It moves the remaining objects to a contiguous memory block to reduce fragmentation.
*   **Generational Hypothesis:** Most objects die young. Therefore, GC runs frequently on the Young Generation (Minor GC) and less frequently on the Old Generation (Major GC).

### 4. Types of Garbage Collectors.
*   **Serial GC:** Single-threaded; pauses all app threads (Stop-The-World).
*   **Parallel GC:** Default in Java 8; uses multiple threads for Young Gen but still has STW pauses.
*   **G1 GC:** Default since Java 9; partitions heap into regions to predict and limit pause times.
*   **Generational ZGC:** The 2026 gold standard. It performs almost all work concurrently with application threads, keeping STW pauses under 1ms even for terabyte-sized heaps.

### 5. What is a memory leak in Java?
A memory leak occurs when objects are no longer needed by the program logic but are still referenced by a live root (e.g., a static `Map` that is never cleared). This prevents the GC from reclaiming the memory, eventually leading to `java.lang.OutOfMemoryError`.

### 6. Heap vs. Stack Memory.
*   **Heap:** Stores all objects created by `new`. It is shared by all threads. It is larger but slower to access.
*   **Stack:** Stores primitive local variables and object references. It is thread-private. It follows LIFO (Last-In-First-Out) and is very fast.

### 7. String vs. StringBuilder vs. StringBuffer.
*   **String:** Immutable. Modifying a string creates a new object in the **String Constant Pool**.
*   **StringBuilder:** Mutable. Faster than String for concatenation because it modifies the existing buffer. It is **not** thread-safe.
*   **StringBuffer:** Mutable and **thread-safe**. It uses synchronized methods, making it slower than `StringBuilder`.

### 8. How HashMap works internally?
It uses an array of "buckets."
*   **Index Calculation:** Index = `(n-1) & hash(key)`.
*   **Collision Handling:** Uses Linked Lists for entries in the same bucket.
*   **Treeification:** Since Java 8, if a bucket size exceeds 8 and the total map capacity > 64, the list converts to a **Red-Black Tree** (changing search time from $O(n)$ to $O(\log n)$).

### 9. HashMap vs. ConcurrentHashMap.
*   `HashMap` is not thread-safe; concurrent modifications can cause infinite loops or data corruption.
*   `ConcurrentHashMap` (CHM) provides high concurrency. In 2026, it uses **CAS (Compare-And-Swap)** for empty buckets and **synchronized** on the first node of the bucket for non-empty ones. It does not lock the entire map.

### 10. Why is String immutable?
*   **String Pool:** Allows multiple variables to point to the same memory, saving space.
*   **Security:** Parameters like database URLs or usernames cannot be changed once validated.
*   **Thread Safety:** Naturally thread-safe because their state cannot change.
*   **Caching:** The `hashCode` is calculated once and cached, making it fast as a key in `HashMap`.

### 11. equals() vs. hashCode().
*   `equals(Object o)`: Checks logical equality (e.g., do two `User` objects have the same ID?).
*   `hashCode()`: Returns an integer for hash-based storage.
*   **Contract:** If `a.equals(b)` is true, `a.hashCode()` **must** be the same as `b.hashCode()`. If they are not equal, the hashcodes *can* be the same (collision), but it's better if they aren't.

### 12. What is ClassLoader?
A part of the JRE that loads classes into the JVM on demand.
1.  **Bootstrap ClassLoader:** Loads internal classes (rt.jar, java.base).
2.  **Platform ClassLoader:** Loads Java SE platform modules.
3.  **Application ClassLoader:** Loads classes from the system classpath.
It follows the **Delegation Model**: A loader first asks its parent to load the class before trying itself.

### 13. Explain Reflection API.
An API that allows inspecting or modifying the behavior of classes, interfaces, fields, and methods at runtime. It is used to:
*   Instantiate objects without knowing the class name at compile time.
*   Access `private` members (using `setAccessible(true)`).
*   Power frameworks like Spring for Dependency Injection.

### 14. What are Soft, Weak, and Phantom references?
*   **Soft:** Objects are GC'd only if the JVM *needs* memory. Good for caches.
*   **Weak:** Objects are GC'd as soon as the next GC cycle runs. Used in `WeakHashMap`.
*   **Phantom:** Used for cleanup after an object is finalized. It is never automatically cleared by GC; you must clear it manually.

### 15. What is Autoboxing and Unboxing?
*   **Autoboxing:** The automatic conversion of primitive types to their wrapper objects (e.g., `int` to `Integer`).
*   **Unboxing:** The reverse process. *Warning:* Unboxing a `null` wrapper results in a `NullPointerException`.

### 16. Serialization vs. Deserialization.
*   **Serialization:** Converting an object state into a byte stream (implement `Serializable`).
*   **Deserialization:** Reverting the byte stream back into a Java object.
*   *Note:* Use `serialVersionUID` to ensure version compatibility.

### 17. Fail-fast vs. Fail-safe iterators.
*   **Fail-fast:** (e.g., `ArrayList`) Throws `ConcurrentModificationException` if the collection is modified while iterating.
*   **Fail-safe:** (e.g., `CopyOnWriteArrayList`) Operates on a clone/copy of the data, so modifications during iteration are allowed.

### 18. What is volatile keyword?
It ensures **Visibility**. If a variable is marked `volatile`, it is always read from/written to the main memory, skipping the CPU cache. This ensures that changes made by one thread are immediately visible to others.

### 19. What is transient keyword?
Used to indicate that a field should not be serialized. For example, you would mark a `password` field as `transient` so it isn't saved to disk or sent over a network.

### 20. Platform independence in Java.
The `javac` compiler converts code into **Bytecode** (.class files). Bytecode is a platform-neutral intermediate language. The **JVM** on each specific OS interprets this bytecode into native machine instructions, making the code "Write Once, Run Anywhere."

---

## II. Java 8 to Java 25 & Concurrency

### 21. Java IO vs. NIO.
*   **IO (Input/Output):** Stream-oriented and **blocking**. One thread handles one connection.
*   **NIO (New IO):** Buffer-oriented and **non-blocking**. It uses **Selectors** to allow one thread to manage multiple "Channels" (connections), making it highly scalable for servers.

### 22. What is Optional in Java 8?
A container object used to represent the presence or absence of a value. It replaces `null` checks with a functional API (`.map()`, `.orElse()`, `.ifPresent()`), significantly reducing `NullPointerExceptions`.

### 23. What is Stream API?
A pipeline of functional operations (filter, map, sorted, collect) performed on a sequence of elements.
*   **Lazy Evaluation:** Operations are only executed when a "Terminal Operation" (like `.collect()`) is called.
*   **Parallelism:** Easily switch to multi-core processing with `.parallelStream()`.

### 24. map() vs. flatMap().
*   **map():** Transforms each element into another value (e.g., a list of Users to a list of UserNames).
*   **flatMap():** Transforms each element into a stream and then flattens those streams into one (e.g., a list of Departments to a single list of all Employees).

### 25. Functional Interface.
An interface with exactly one abstract method (e.g., `Predicate`, `Function`, `Consumer`). In 2026, these are the foundation for Lambda expressions and Method References.

### 26. Thread lifecycle.
1.  **New:** Thread is created but not started.
2.  **Runnable:** `start()` called; eligible for CPU time.
3.  **Blocked:** Waiting for a monitor lock.
4.  **Waiting:** Waiting indefinitely for another thread (e.g., `wait()`).
5.  **Timed_Waiting:** Waiting for a specific period (e.g., `sleep(1000)`).
6.  **Terminated:** Execution finished.

### 27. Runnable vs. Callable.
*   **Runnable:** `run()` method returns `void` and cannot throw checked exceptions.
*   **Callable:** `call()` method returns a `Future<V>` result and can throw checked exceptions.

### 28. ExecutorService.
A framework to manage thread pools. Instead of creating `new Thread()` manually, you submit tasks to the `ExecutorService`, which reuses a pool of worker threads, improving performance and resource management.

### 29. Types of Thread Pools.
*   **FixedThreadPool:** Fixed number of threads.
*   **CachedThreadPool:** Creates threads as needed, deletes idle ones.
*   **ScheduledThreadPool:** For delayed or periodic tasks.
*   **SingleThreadExecutor:** One thread, sequential execution.

### 30. Deadlock and prevention.
Deadlock occurs when Thread A holds Lock 1 and waits for Lock 2, while Thread B holds Lock 2 and waits for Lock 1.
*   **Prevention:** Acquire locks in a consistent order; use `tryLock()` with a timeout; keep synchronization blocks as small as possible.

### 31. Race condition.
A situation where the output depends on the timing of uncontrollable events (multiple threads updating a shared counter). Solved using `synchronized`, `Lock`, or `AtomicInteger`.

### 32. Synchronization.
A mechanism to ensure that only one thread can access a resource at a time.
*   **Synchronized Method:** Locks the current object (`this`).
*   **Synchronized Block:** Locks a specific object.

### 33. synchronized vs. Lock.
*   `synchronized` is implicit and easier, but cannot be interrupted or timed out.
*   `Lock` (ReentrantLock) is explicit. It allows for `tryLock()` (non-blocking), fairness settings, and the ability to interrupt a thread waiting for the lock.

### 34. ReentrantLock.
A lock that allows the thread holding it to re-acquire the same lock multiple times without deadlocking itself (it keeps a hold count).

### 35. Semaphore.
A synchronization tool that maintains a set of "permits." Threads `acquire()` a permit to enter a critical section and `release()` it when done. Useful for limiting the number of concurrent database connections.

### 36. CountDownLatch.
A synchronization aid that allows one thread to wait until a set of operations being performed in other threads completes. It cannot be reset (one-time use).

### 37. CyclicBarrier.
Allows a set of threads to all wait for each other to reach a common barrier point. Unlike `CountDownLatch`, it can be reset and reused.

### 38. ForkJoinPool.
An `ExecutorService` for "Divide and Conquer" tasks. It uses a **Work-Stealing** algorithm: idle threads "steal" tasks from the back of the dequeues of busy threads to maximize CPU utilization.

### 39. Atomic variables.
Classes like `AtomicInteger` and `AtomicReference` use low-level CPU instructions (CAS) to provide thread-safe operations without the overhead of heavy locking.

### 40. Compare-And-Swap (CAS).
An atomic instruction used in multithreading to achieve synchronization without locks. It compares the current value of a variable to an "expected" value; if they match, it updates it to the "new" value.

### 41. ThreadLocal.
Provides variables that are local to a specific thread. Each thread has its own independently initialized copy of the variable. Used for keeping "User IDs" or "Transaction IDs" across method calls in a web request.

### 42. Happens-Before relationship.
A formal guarantee in the Java Memory Model. If action A happens-before action B, then the results of action A are visible to action B (e.g., unlocking a monitor happens-before any subsequent locking of that monitor).

### 43. BlockingQueue.
A queue that supports operations that wait for the queue to become non-empty when retrieving, and wait for space when storing.

### 44. Producer-Consumer problem.
A classic concurrency problem. Producers put data in a buffer; consumers take it out. Solved easily in Java using `ArrayBlockingQueue`.

### 45. Designing high-performance multithreading.
In 2026, the strategy shifts toward **Virtual Threads** (introduced in Project Loom). They are M:N scheduled (millions of virtual threads mapped to a few OS threads), allowing developers to write simple blocking code that is as performant as complex reactive code.

---

## III. Spring Framework & Microservices

### 46. Dependency Injection (DI).
A design pattern where a container (Spring) provides an object's dependencies at runtime rather than the object creating them itself. This makes code loosely coupled and easily testable.

### 47. Spring Bean lifecycle.
1.  **Instantiation.**
2.  **Populate Properties (DI).**
3.  **Aware Interfaces** (`BeanNameAware`, etc.).
4.  **BeanPostProcessor (Before).**
5.  **Initialization** (`@PostConstruct` or `InitializingBean`).
6.  **BeanPostProcessor (After).**
7.  **Ready to use.**
8.  **Destruction** (`@PreDestroy`).

### 48. @Component vs. @Service vs. @Repository.
*   `@Component`: General-purpose bean.
*   `@Service`: Stereotype for business logic.
*   `@Repository`: Stereotype for DAO layer; adds automatic persistence exception translation.

### 49. Constructor Injection vs. Field Injection.
*   **Field:** Convenient but makes testing harder and allows circular dependencies.
*   **Constructor:** Preferred in 2026. It ensures the bean is fully initialized before use and allows fields to be `final` (immutability).

### 50. AOP in Spring.
Aspect-Oriented Programming allows separating cross-cutting concerns (logging, security, transactions) from the main business logic using "Aspects" and "Advices."

### 51. Proxy in Spring.
Spring wraps beans in **Proxies** to implement AOP and `@Transactional`.
*   **JDK Proxy:** Used if the class implements an interface.
*   **CGLIB Proxy:** Used if the class does not implement an interface (subclassing).

### 52. REST vs. SOAP.
*   **REST:** Architectural style, uses JSON/HTTP, stateless, high performance.
*   **SOAP:** Protocol, uses XML, can be stateful, has built-in security standards (WS-Security).

### 53. Spring Boot Auto-Configuration.
The `@EnableAutoConfiguration` (part of `@SpringBootApplication`) tells Spring Boot to look at the classpath and "guess" what beans you need. (e.g., if it sees `h2.jar`, it automatically creates an H2 DataSource).

### 54. Spring Security flow.
1.  Request hits a **Filter Chain**.
2.  **AuthenticationFilter** extracts credentials.
3.  **AuthenticationManager** delegates to **AuthenticationProvider**.
4.  **UserDetailsService** loads user from DB.
5.  If successful, the **SecurityContext** is updated.

### 55. OAuth2.
An authorization framework that allows a "Client" to access resources on a "Resource Server" on behalf of a "User" without sharing their password, using "Access Tokens."

### 56. JWT (JSON Web Token).
A compact, stateless way to transmit claims. It is signed (using a secret or key) so it can be verified. It consists of a Header, Payload (data), and Signature.

### 57. Circuit Breaker pattern.
Prevents a failing service from causing a system-wide crash. If a service call fails repeatedly, the circuit "opens," and all subsequent calls fail fast or return a fallback, giving the service time to recover.

### 58. Service Discovery (Eureka).
In a dynamic environment, IP addresses of services change. Eureka acts as a phone book where services register themselves so others can find them by service name.

### 59. API Gateway.
The single entry point for all clients. It handles routing, security (JWT validation), rate limiting, and request aggregation.

### 60. Feign Client.
A declarative REST client for Spring Boot. You simply write an interface and annotate it; Spring creates the implementation to call other microservices.

### 61. Load Balancer.
Distributes traffic across multiple instances of a service. Spring Cloud LoadBalancer is the standard in 2026.

### 62. Config Server.
Centralizes the management of configuration properties for all microservices in all environments (Dev, QA, Prod), usually backed by a Git repository.

### 63. Kafka architecture.
A distributed event streaming platform.
*   **Producer:** Sends messages.
*   **Broker:** Stores messages.
*   **Topic:** Logical name for a stream.
*   **Partition:** How topics are split for scale.
*   **Consumer Group:** Group of consumers sharing the workload.

### 64. Event-Driven Architecture.
A design pattern where services communicate through events. It increases decoupling because the producer doesn't know who is consuming the message.

### 65. Saga Pattern.
Manages distributed transactions in microservices.
*   **Choreography:** Services exchange events without a central coordinator.
*   **Orchestration:** A central coordinator tells services what local transactions to run.

### 66. Distributed Tracing.
Using a **Trace ID** that follows a request through multiple microservices. Tools like Zipkin or Micrometer Tracing visualize where bottlenecks or failures occur.

### 67. Resilience4j.
The standard fault-tolerance library in 2026 (replacing Hystrix). It provides modules for Circuit Breakers, Rate Limiters, Retries, and Bulkheads.

### 68. Docker with Spring Boot.
Docker packages the Spring Boot JAR + JRE + OS config into a single **Image**, ensuring the application runs identically on a developer's laptop and in the cloud.

### 69. Kubernetes deployment strategies.
*   **Rolling Update:** Replaces pods one by one.
*   **Canary:** Routes 5% of traffic to the new version to test for bugs.
*   **Recreate:** Kills all old pods before starting new ones.

### 70. Blue-Green deployment.
Two identical production environments. Blue is live. You deploy to Green, test it, and then switch the router. If Green fails, you switch back to Blue instantly.

---

## IV. JVM Tuning & Troubleshooting

### 71. JIT Compiler.
The Just-In-Time compiler translates Bytecode into native machine code at runtime. It focuses on "hotspots" (code executed frequently) to make Java apps run as fast as native C++.

### 72. Metaspace vs. PermGen.
`PermGen` was part of the heap and had a fixed size. `Metaspace` (introduced in Java 8) uses native memory. It is more flexible and significantly reduces `java.lang.OutOfMemoryError: PermGen space`.

### 73. Heap Dump analysis.
A snapshot of the heap memory. Tools like **Eclipse MAT** or **VisualVM** analyze these dumps to identify which objects are consuming memory and find leak suspects.

### 74. OutOfMemoryError troubleshooting.
1.  Identify the type (Heap, Metaspace, or Stack).
2.  Enable `-XX:+HeapDumpOnOutOfMemoryError`.
3.  Analyze the dump for memory leaks or excessive object creation.
4.  Tune `-Xmx` (Max Heap) or `-Xss` (Stack size).

### 75. GC tuning.
Optimizing GC for **Latency** (pause times) or **Throughput** (amount of work). In 2026, most tuning involves setting the pause goal for **G1** or **ZGC** (e.g., `-XX:MaxGCPauseMillis=200`).

### 76. Stop-The-World events.
Moments when the JVM pauses all application threads to perform garbage collection. Minimizing STW is the primary goal of modern collectors like ZGC.

### 77. Escape Analysis.
A JVM optimization. If the compiler determines that an object created in a method never "escapes" that method, it may allocate the object on the **Stack** instead of the Heap, eliminating GC overhead.

### 78. Object Pooling.
Reusing objects (like DB connections) instead of creating new ones to save time and memory. *Avoid for simple objects, as modern GCs are faster than pools for small objects.*

### 79. ClassLoader memory leak.
Common in application servers. If a classloader is not garbage collected (often due to static references), all the classes it loaded stay in memory, eventually causing an OOM in Metaspace.

### 80. JVM Profiling tools.
*   **JFR (Java Flight Recorder):** Extremely low overhead; record production data.
*   **JVisualVM:** General-purpose monitoring.
*   **JProfiler:** Commercial, deep analysis.

### 81. JMX.
Java Management Extensions. A standard for monitoring and managing the JVM. You can expose your own "MBeans" to monitor custom app metrics via JConsole.

### 82. Memory Thrashing.
When the system spends more time moving data in/out of memory (paging or excessive GC) than actually executing instructions.

### 83. CPU profiling.
The process of identifying "Hotspots"—methods or threads that consume the most CPU cycles—using sampling or instrumentation.

### 84. Asynchronous processing.
Executing tasks in the background using `CompletableFuture` or Spring's `@Async`. This allows the main thread to return a response to the user while work continues in the background.

### 85. Reactive Programming.
A non-blocking paradigm centered around data streams. Used in **Spring WebFlux** to handle high-concurrency I/O with a small number of threads.

---

## V. Design Patterns & System Design

### 86. Singleton pattern (thread-safe).
Ensures a class has only one instance. The **Enum** implementation is the most thread-safe and robust against serialization/reflection attacks in 2026.

### 87. Factory vs. Abstract Factory.
*   **Factory Method:** Defines an interface for creating *one* object.
*   **Abstract Factory:** Creates families of related objects (e.g., a "UI Factory" that creates both Buttons and TextBoxes for a specific OS).

### 88. Builder pattern.
Used for creating complex objects with many parameters. It provides a readable, fluent API and avoids "telescoping constructors."

### 89. Strategy pattern.
Defines a family of algorithms and makes them interchangeable at runtime (e.g., switching between `CreditCardPayment` and `CryptoPayment`).

### 90. Observer pattern.
A one-to-many dependency where when the "Subject" changes state, all its "Observers" are notified automatically (basis for event listeners).

### 91. Proxy pattern.
Provides a surrogate or placeholder for another object to control access to it (e.g., Hibernate's "Lazy Loading").

### 92. Circuit Breaker pattern.
(See #57).

### 93. CQRS.
Command Query Responsibility Segregation. It separates the "Write" model from the "Read" model, often using different databases to optimize each.

### 94. Event Sourcing.
Instead of storing the *current state* of an object, you store a *history of events*. You can reconstruct the current state by replaying all historical events.

### 95. SOLID principles.
*   **S:** Single Responsibility.
*   **O:** Open/Closed (Open for extension, closed for modification).
*   **L:** Liskov Substitution (Subtypes must be substitutable for base types).
*   **I:** Interface Segregation.
*   **D:** Dependency Inversion.

### 96. Microservices vs. Monolith.
*   **Monolith:** Single code base; easy to deploy; hard to scale; single point of failure.
*   **Microservices:** Distributed; independent scaling; complex deployment; high fault tolerance.

### 97. CAP Theorem.
States that in a distributed system, you can only have two of: **Consistency**, **Availability**, and **Partition Tolerance**.

### 98. Idempotency.
An operation is idempotent if it can be performed multiple times without changing the result beyond the initial application (e.g., a `PUT` request in REST).

### 99. Rate Limiting design.
Used to prevent API abuse. Common algorithms include **Token Bucket** and **Leaky Bucket**. Implementation in Spring usually involves a Gateway or Redis.

### 100. Design a high-traffic REST API.
1.  **Horizontal Scaling:** Use a Load Balancer.
2.  **Caching:** Use Redis for hot data and CDNs for static assets.
3.  **Concurrency:** Use Virtual Threads (Java 21+) or Reactive code.
4.  **Database:** Use Read-Replicas and Indexing.
5.  **Asynchronicity:** Use Message Queues (Kafka/RabbitMQ) for long-running tasks.

---
*Maintained by the Tech Interview Community. Last Updated: Jan 2026.*
