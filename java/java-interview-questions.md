# 🚀 Java Interview Masterclass (2026 Edition)

A comprehensive guide covering 100 essential Java interview questions. This repository serves as a roadmap for mastering Core Java, JVM internals, Concurrency, Spring Boot, and Microservices architecture for 2026.

---

## 📋 Table of Contents
1. [Core Java & JVM Internals](#i-core-java--jvm-internals)
2. [Advanced Concepts](#ii-advanced-concepts)
3. [Java 8+ & Functional Programming](#iii-java-8-and-functional-programming)
4. [Concurrency & Multithreading](#iv-concurrency--multithreading)
5. [Spring & Spring Boot](#v-spring--spring-boot)
6. [Microservices & Distributed Systems](#vi-microservices--distributed-systems)
7. [JVM Performance & Troubleshooting](#vii-jvm-performance--troubleshooting)
8. [Design Patterns & Architecture](#viii-design-patterns--architecture)

---

## I. Core Java & JVM Internals

1. **Explain JVM Architecture.**
   Consists of three main subsystems: **Class Loader**, **Runtime Data Areas** (Heap, Stack, Metaspace), and **Execution Engine** (JIT Compiler, GC).

2. **JDK vs. JRE vs. JVM.**
   - **JVM**: Runs bytecode.
   - **JRE**: JVM + Runtime libraries.
   - **JDK**: JRE + Development tools (`javac`).

3. **How Garbage Collection works?**
   It identifies unreachable objects and reclaims their memory. Modern JVMs use generational collection (Young vs. Old generation).

4. **Types of Garbage Collectors.**
   - **Serial/Parallel**: Throughput focused.
   - **G1 GC**: Low-latency default.
   - **ZGC (Generational)**: Ultra-low latency (<1ms) for 2026 standards.

5. **Memory Leak.**
   When objects are no longer used but are still referenced, preventing the GC from cleaning them up.

6. **Heap vs. Stack.**
   - **Heap**: Stores objects; shared across threads.
   - **Stack**: Stores primitive local variables and method frames; thread-private.

7. **String vs. StringBuilder vs. StringBuffer.**
   `String` is immutable; `StringBuilder` is mutable/fast; `StringBuffer` is mutable/thread-safe.

8. **HashMap Internal Working.**
   Uses `hashCode()` for bucket indexing and `equals()` for collision resolution. Java 8+ uses balanced trees for large collisions.

9. **HashMap vs. ConcurrentHashMap.**
   `ConcurrentHashMap` uses bucket-level locking (CAS) for thread safety without locking the whole map.

10. **Why is String immutable?**
    Security, thread safety, and efficiency (String Pool).

---

## II. Advanced Concepts

11. **equals() vs. hashCode().**
    If `equals()` is true, `hashCode()` must be the same. Always override both together.
12. **ClassLoader.** Loads `.class` files into the JVM at runtime.
13. **Reflection API.** Ability to inspect/modify classes and methods at runtime.
14. **References.** **Soft** (GC if memory low), **Weak** (GC next cycle), **Phantom** (Post-mortem cleanup).
15. **Autoboxing.** Auto-conversion between primitives (int) and Wrappers (Integer).
16. **Serialization.** Converting an object into a byte stream for storage/transfer.
17. **Iterators.** **Fail-fast** (throws error on change) vs. **Fail-safe** (works on copy).
18. **volatile.** Ensures a variable is read from/written to main memory, not CPU cache.
19. **transient.** Prevents a variable from being serialized.
20. **Platform Independence.** Bytecode runs on any OS provided a JVM is present.

---

## III. Java 8+ and Functional Programming

21. **Java IO vs. NIO.** IO is blocking/stream-oriented; NIO is non-blocking/buffer-oriented.
22. **Optional.** A wrapper to avoid `NullPointerException`.
23. **Stream API.** Declarative data processing (filter, map, collect).
24. **map() vs. flatMap().** `map` transforms; `flatMap` transforms and flattens nested collections.
25. **Functional Interface.** Interface with exactly one abstract method (enables Lambdas).

---

## IV. Concurrency & Multithreading

26. **Thread Lifecycle.** New, Runnable, Blocked, Waiting, Terminated.
27. **Runnable vs. Callable.** `Callable` returns a value and throws checked exceptions.
28. **ExecutorService.** Framework for managing thread pools and task execution.
29. **Thread Pool Types.** Fixed, Cached, Scheduled, Single.
30. **Deadlock.** Two threads waiting for each other's locks. Prevented via resource ordering.
31. **Race Condition.** Concurrent access leading to inconsistent data.
32. **Synchronization.** Restricting access to one thread at a time.
33. **synchronized vs. Lock.** `Lock` API offers timeouts and fairness settings.
34. **ReentrantLock.** A lock that can be re-acquired by the thread already holding it.
35. **Semaphore.** Limits access to $N$ number of threads.
36. **CountDownLatch.** Waits for $N$ events to occur before proceeding.
37. **CyclicBarrier.** Threads wait for each other at a common point.
38. **ForkJoinPool.** Optimized for recursive "divide and conquer" tasks.
39. **Atomic Variables.** Thread-safe variables using CAS (e.g., `AtomicInteger`).
40. **CAS (Compare-And-Swap).** Low-level atomic instruction for lock-free updates.
41. **ThreadLocal.** Provides variables unique to a single thread.
42. **Happens-Before.** A guarantee that memory writes are visible to other threads.
43. **BlockingQueue.** A queue that blocks during "put" (if full) or "take" (if empty).
44. **Producer-Consumer.** Classic problem solved using `BlockingQueue`.
45. **High Performance.** Use **Virtual Threads** (Project Loom) for lightweight concurrency.

---

## V. Spring & Spring Boot

46. **Dependency Injection.** Objects receive dependencies rather than creating them.
47. **Bean Lifecycle.** PostConstruct -> Init -> Use -> PreDestroy.
48. **Stereotypes.** `@Component` (General), `@Service` (Logic), `@Repository` (Data).
49. **Injection.** Constructor injection is the 2026 industry standard for testability.
50. **AOP.** Separates cross-cutting concerns like logging or transactions.
51. **Proxy.** Spring uses JDK/CGLIB proxies to wrap beans for AOP.
52. **REST vs. SOAP.** REST (HTTP/JSON/Stateless) vs. SOAP (Protocol/XML).
53. **Auto-Configuration.** Spring Boot's ability to guess and configure beans based on classpath.
54. **Security Flow.** Filters intercept requests -> AuthenticationManager -> Success/Failure.
55. **OAuth2.** Industry standard for delegated authorization.
56. **JWT.** Stateless token-based authentication.

---

## VI. Microservices & Distributed Systems

57. **Circuit Breaker.** Prevents cascading failures (e.g., Resilience4j).
58. **Service Discovery.** Registry (Eureka) so services can find each other.
59. **API Gateway.** Single entry point for routing, security, and rate limiting.
60. **Feign Client.** Declarative REST client for inter-service communication.
61. **Load Balancer.** Distributes traffic across service instances.
62. **Config Server.** Centralized configuration management.
63. **Kafka.** Distributed event streaming platform.
64. **Event-Driven.** Architecture where services react to asynchronous events.
65. **Saga Pattern.** Manages distributed transactions across services.
66. **Distributed Tracing.** Tracking request flow via TraceIDs (e.g., Zipkin/OpenTelemetry).
67. **Resilience4j.** Modern replacement for Hystrix for fault tolerance.
68. **Docker.** Containerizing the Spring Boot app and its dependencies.
69. **Kubernetes.** Managing container scaling, health, and networking.
70. **Blue-Green.** Release strategy to minimize downtime during updates.

---

## VII. JVM Performance & Troubleshooting

71. **JIT Compiler.** Compiles hot code to native machine code at runtime.
72. **Metaspace.** Replaced PermGen; uses native memory for class metadata.
73. **Heap Dump.** Snapshot of memory used to find leaks (MAT tool).
74. **OutOfMemoryError.** Troubleshooting via `-XX:+HeapDumpOnOutOfMemoryError`.
75. **GC Tuning.** Tuning `-Xmx`, `-Xms`, and Pause Time goals.
76. **Stop-The-World.** Pausing application threads for GC.
77. **Escape Analysis.** JVM optimization to allocate objects on the stack.
78. **Object Pooling.** Reusing objects (e.g., DB connections) to save overhead.
79. **ClassLoader Leak.** Common in web apps when classes aren't unloaded.
80. **Profiling Tools.** JFR (Flight Recorder), VisualVM, JProfiler.
81. **JMX.** Technology for monitoring and managing the JVM.
82. **Memory Thrashing.** Excessive paging/swapping leading to performance collapse.
83. **CPU Profiling.** Finding methods consuming the most CPU cycles.
84. **Asynchronous.** Non-blocking execution using `CompletableFuture`.
85. **Reactive.** Non-blocking data streams (Spring WebFlux).

---

## VIII. Design Patterns & Architecture

86. **Singleton.** One instance per JVM.
87. **Factory.** Creates objects without exposing creation logic.
88. **Builder.** Complex object construction step-by-step.
89. **Strategy.** Switching algorithms at runtime.
90. **Observer.** One-to-many notification system.
91. **Proxy.** Wrapper to control access to an object.
92. **Circuit Breaker.** (Architectural pattern for fault tolerance).
93. **CQRS.** Separating Read and Write models.
94. **Event Sourcing.** Storing state as a series of events.
95. **SOLID.** Single Responsibility, Open-Closed, Liskov, Interface Segregation, Dependency Inversion.
96. **Microservices vs. Monolith.** Distributed vs. Centralized codebases.
97. **CAP Theorem.** Balancing Consistency, Availability, and Partition Tolerance.
98. **Idempotency.** Ensuring an API call has the same effect regardless of how many times it's called.
99. **Rate Limiting.** Protecting APIs from abuse (Token Bucket/Leaky Bucket).
100. **High-Traffic Design.** Use of Caching, Load Balancing, and Non-blocking architectures.

---
*Created for 2026 Java Technical Interviews.*
