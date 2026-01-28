# 🚀 Spring Framework Interview Questions:

## 📂 Table of Contents
1. [Design Patterns & System Design](#v-design-patterns--system-design)

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
>Used to prevent API abuse. Common algorithms include **Token Bucket** and **Leaky Bucket**. Implementation in Spring usually involves a Gateway or Redis.

### 100. Design a high-traffic REST API.
>1.  **Horizontal Scaling:** Use a Load Balancer.
>2.  **Caching:** Use Redis for hot data and CDNs for static assets.
>3.  **Concurrency:** Use Virtual Threads (Java 21+) or Reactive code.
>4.  **Database:** Use Read-Replicas and Indexing.
>5.  **Asynchronicity:** Use Message Queues (Kafka/RabbitMQ) for long-running tasks.

---