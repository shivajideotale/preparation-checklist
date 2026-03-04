# Spring Data JPA Specific Annotations Reference

This guide provides a comprehensive overview of the specialized annotations provided by [Spring Data JPA](https://spring.io) for repository management, auditing, and performance optimization.

---

## 1. Query & Execution Annotations
These annotations are used within your Repository interfaces to define custom data access logic.


| Annotation | Description |
| :--- | :--- |
| **`@Query`** | Declares [custom JPQL or Native SQL](https://docs.spring.io) queries. |
| **`@Param`** | Binds method parameters to [named query parameters](https://www.baeldung.com). |
| **`@Modifying`** | Required for queries that [perform DML operations](https://www.baeldung.com) (UPDATE, DELETE). |
| **`@Procedure`** | Maps a repository method to a [database stored procedure](https://docs.spring.io). |
| **`@EntityGraph`** | Specifies [attribute paths to fetch eagerly](https://www.baeldung.com) (prevents N+1 issues). |

### Example: Repository Usage
```java
public interface UserRepository extends JpaRepository<User, Long> {

    @Query("SELECT u FROM User u WHERE u.email = :email")
    Optional<User> findByEmail(@Param("email") String email);

    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.lastLogin = CURRENT_TIMESTAMP WHERE u.id = ?1")
    void updateLastLogin(Long id);
}
```

## 2. Auditing Annotations
Automatically track entity lifecycle events. To use these, you must add `@EnableJpaAuditing` to a [Spring Configuration class](https://www.baeldung.com).


| Annotation | Description |
| :--- | :--- |
| **`@CreatedDate`** | Captures the [creation timestamp](https://docs.spring.io) automatically. |
| **`@LastModifiedDate`** | Updates the [modification timestamp](https://docs.spring.io) on every save. |
| **`@CreatedBy`** | Stores the [user who created](https://www.baeldung.com) the entity (requires `AuditorAware`). |
| **`@LastModifiedBy`** | Stores the [user who last modified](https://www.baeldung.com) the entity. |

---

## 3. Pagination and Sorting
Spring Data JPA provides abstractions to handle large result sets efficiently without writing manual offset logic.


| Class/Interface | Purpose |
| :--- | :--- |
| **`Pageable`** | Input parameter to define [page number, size, and sorting](https://www.baeldung.com). |
| **`Page<T>`** | Result type containing data plus [total count metadata](https://www.geeksforgeeks.org) (triggers a `count` query). |
| **`Slice<T>`** | Result type for [infinite scroll](https://www.baeldung.com) (avoids `count` query for better performance). |
| **`Sort`** | Used to apply [dynamic sorting](https://www.baeldung.com) to queries. |

### Example: Pagination in Service
```java
@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;

    public Page<User> getUsers(int page, int size) {
        // Create a Pageable object with sorting logic
        Pageable pageable = PageRequest.of(page, size, Sort.by("username").ascending());
        
        return userRepository.findAll(pageable);
    }
}
```

## 4. Configuration & Advanced Features

These annotations provide fine-grained control over repository scanning, bean instantiation, and concurrency.


| Annotation | Description |
| :--- | :--- |
| **`@EnableJpaRepositories`** | Used on configuration classes to [manually trigger repository scanning](https://docs.spring.io) and define base packages. |
| **`@NoRepositoryBean`** | Ensures Spring does **not** create a [bean for a base repository](https://www.baeldung.com) interface, which is useful for shared custom logic. |
| **`@Lock`** | Configures [Pessimistic or Optimistic locking](https://www.baeldung.com) to prevent data inconsistency during concurrent updates. |

### Example: Configuration and Locking

```java
@Configuration
@EnableJpaRepositories(basePackages = "com.example.repository")
public class JpaConfig {
    // Custom configuration beans
}

public interface InventoryRepository extends JpaRepository<Product, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findAndLockById(@Param("id") Long id);
}
```
### 5. Troubleshooting Common Issues

When working with advanced JPA features, you may encounter specific exceptions related to locking and concurrency.


| Exception | Cause | Resolution |
| :--- | :--- | :--- |
| **`PessimisticLockException`** | A query timed out while waiting for a [database-level lock](https://www.baeldung.com) held by another transaction. | Increase the [query timeout hint](https://docs.spring.io) or optimize transaction length. |
| **`OptimisticLockException`** | Two transactions tried to [update the same version](https://www.baeldung.com) of an entity simultaneously. | Implement a retry mechanism or handle the conflict in the UI. |
| **`TransactionRequiredException`** | A method annotated with `@Modifying` was called [outside of a transaction](https://www.baeldung.com). | Ensure the service method is annotated with [Spring's @Transactional](https://www.baeldung.com). |

#### Example: Handling Lock Timeouts
```java
public interface ProductRepository extends JpaRepository<Product, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "javax.persistence.lock.timeout", value = "3000")})
    Optional<Product> findByIdCustom(Long id);
}
```
## 5. Modern Features (Spring Data JPA 3.x+)

Recent updates to Spring Data JPA (matching **Spring Boot 3+**) introduced more robust ways to handle nullability and native query execution.


| Feature / Annotation | Description |
| :--- | :--- |
| **`@NativeQuery`** | A new [composed annotation](https://docs.spring.io) that simplifies native SQL by removing the need for `nativeQuery = true` inside `@Query`. |
| **`@Value`** | Used within [Interface Projections](https://docs.spring.io) to perform **Open Projections** using SpEL (Spring Expression Language). |
| **`Runtime Null Safety`** | Methods like [getSingleResultOrNull()](https://docs.spring.io) now integrate with JSR-305 annotations for better null handling. |

### Example: Modern Repository Features
```java
public interface ProductRepository extends JpaRepository<Product, Long> {

    // Simplified Native Query (Spring Data JPA 3.x+)
    @NativeQuery("SELECT * FROM products p WHERE p.sku = :sku")
    Optional<Product> findBySku(@Param("sku") String sku);

    // Open Projection using SpEL
    interface ProductSummary {
        String getName();
        @Value("#{target.price * 0.9}")
        BigDecimal getDiscountedPrice();
    }

    List<ProductSummary> findAllByActiveTrue();
}
```
