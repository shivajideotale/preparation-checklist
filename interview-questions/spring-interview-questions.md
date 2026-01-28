# 🚀 Spring Framework Interview Questions:

## 📂 Table of Contents
1. [Spring Framework & Microservices](#iii-spring-framework--microservices)

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