# 📂 Hibernate & JPA Annotations Master Reference

A complete, detailed guide to mapping Java objects (POJOs) to relational database tables using Hibernate and the Jakarta
Persistence API (JPA).

---

## 🛠️ 1. Basic Mapping Annotations

These are the essential building blocks for every persistent class.

### **@Entity**

* **Description:** Marks a Java class as a [managed persistence entity](https://docs.jboss.org).
* **Example:**
  ```java
  @Entity
  public class User { ... }
  ```

### **@Table**

* **Description:** Maps the entity to a specific [database table](https://www.baeldung.com).
* **Attributes:** `name`, `schema`, `indexes`.
* **Example:**
  ```java
  @Table(name = "app_users", schema = "public")
  ```

### **@Id & @GeneratedValue**

* **Description:** `@Id` defines the [primary key](https://www.geeksforgeeks.org). `@GeneratedValue` specifies its
  auto-generation strategy.
* **Strategies:** `IDENTITY` (Auto-increment), `SEQUENCE` (DB Sequence), `AUTO` (Provider choice), `UUID` (Generates a
  128-bit unique identifier) .
* **Example:**
  ```java
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  ```
* If you manually assign a value to an ID field that is annotated with @Id and @GeneratedValue, JPA/Hibernate will use
  the manually provided value and the @GeneratedValue annotation will be ignored for that specific entity instance.
* The generation strategy is only applied if the ID field is null (or 0 for some primitive types) when the entity is
  persisted.
* If all IDs for an entity are assigned manually, simply use the @Id annotation without @GeneratedValue. This makes the
  intent clear and avoids potential confusion.

---

## 🔗 2. Association & Relationship Mapping

Defines how tables are linked via foreign keys.
Detailed Example: Department and Employee.
In this scenario, many Employee entities belong to one Department.

### 1. The Owning Side (Many-to-One)

* **Description:** The **"Many"** side is the owner of the relationship because it holds the foreign key `(dept_id)` in
  the database.
* **Example:**
  ```java
  @Entity
  public class Employee {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
      private Long id;
  
      private String name;
  
      @ManyToOne(fetch = FetchType.LAZY) // Lazy loading for better performance
      @JoinColumn(name = "dept_id")      // Specifies the foreign key column name
      private Department department;
  
      // Getters, Setters, and Constructors
  }
  ```

### 2. The Non-Owning Side (One-to-Many)

* **Description:** The **"One"** side uses the `mappedBy` attribute to indicate it is the inverse side. This prevents
  Hibernate from creating an unnecessary redundant join table..
* **Example:**
  ```java
  @Entity
  public class Department {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
      private Long id;
  
      private String name;
  
      @OneToMany(mappedBy = "department", // References the 'department' field in Employee
                 cascade = CascadeType.ALL, 
                 orphanRemoval = true)
      private List<Employee> employees = new ArrayList<>();
  
      // Helper method to sync both sides of the relationship
      public void addEmployee(Employee employee) {
          employees.add(employee);
          employee.setDepartment(this);
      }
  }
  ```

### Critical Association Details

* `mappedBy`:   Essential for bidirectional relationships. It must point to the field name in the child class that "
  owns" the relationship.
* `CascadeType`:   Controls how operations like PERSIST or REMOVE flow from parent to child. CascadeType.ALL is common
  for parent-child relationships.
    * `ALL`: Propagates all operations (persist, merge, remove, refresh, detach). Usually used for parent-child
      relationships where the child cannot exist without the parent.
    * `PERSIST`: When you save the parent, the child is automatically saved.
    * `MERGE`: When you update the parent, the child's state is also synced with the database.
    * `REMOVE`: Deleting the parent automatically deletes all associated children.
    * `REFRESH`: Reloads the state of the child from the database when the parent is reloaded.
    * `DETACH`: If the parent is removed from the Hibernate session (becoming "detached"), the child is detached too.

* `FetchType`:
    * `LAZY`:    Loads the association only when you call the getter (highly recommended for performance in
      collections).
    * `EAGER`:     Loads the association immediately with the parent entity (default for @ManyToOne).
* `@JoinColumn`:   Defines the physical mapping of the foreign key in the database table.
* `orphanRemoval`:    If set to true, removing a child from the parent's collection will also delete that child record
  from the database.
*

---

## ⚙️ 3. Column & Behavioral Customization

* **@Column**: Customises the DB column (name, nullable, length).
* **@Transient**: Tells Hibernate to ignore this field; it won't be saved to the DB.
* **@Enumerated**: Maps Java Enums. Use `EnumType.STRING` for database readability.
* **@Temporal**: Formats `java.util.Date` as `DATE`, `TIME`, or `TIMESTAMP`.
* **@Lob**: Used for "Large Objects" like long text (CLOB) or binary files (BLOB).
* **@Version**: Enables Optimistic Locking to prevent data collisions.

---

## 🧬 4. Inheritance Strategies

Specifies how class hierarchies are stored using the `@Inheritance` annotation.

1. **SINGLE_TABLE**: (Default) One table for all classes with a `@DiscriminatorColumn`.
2. **JOINED**: Base fields in one table, subclass-specific fields in separate tables.
3. **TABLE_PER_CLASS**: Each concrete class gets its own independent table.

---

## 💻 5. Complete Implementation Example

```java
import jakarta.persistence.*;

import java.util.Date;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", unique = true, nullable = false)
    private String orderNo;

    @Temporal(TemporalType.TIMESTAMP)
    private Date orderDate = new Date();

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Version
    private Integer version;

    // Getters and Setters...
}
```

---

## 💻 6. Hibernate Session Management

The [Hibernate Session interface](https://docs.jboss.org) acts as the primary runtime bridge between a Java application
and the database, managing the lifecycle of mapped entity classes.

## 🚀 Core Persistence Methods

| Method                | Description                                                                                                                          |
|:----------------------|:-------------------------------------------------------------------------------------------------------------------------------------|
| **`persist(entity)`** | Makes a transient instance persistent. Does not guarantee immediate `INSERT`; it schedules it for the next flush.                    |
| **`merge(entity)`**   | Copies state from a detached object to a managed entity. Best for updating records from [closed sessions](https://www.baeldung.com). |
| **`save(entity)`**    | *Deprecated.* Use `persist()` for modern, JPA-compliant code. Returns the identifier immediately.                                    |
| **`update(entity)`**  | *Deprecated.* Reattaches a detached instance. Use `merge()` for better compatibility.                                                |

## 🔍 Retrieval Methods

* **`get(Entity.class, id)`**: Hits the database immediately. Returns `null` if the record is not found.
* **`load(Entity.class, id)`**: Returns a **Proxy** (placeholder). Hits the database only when a property is accessed.
  Throws an exception if the record is missing.

### `get()` vs `load()` Comparison

| Feature            | `get()`                 | `load()`                         |
|:-------------------|:------------------------|:---------------------------------|
| **Database Hit**   | Immediate               | Lazy (Delayed)                   |
| **Missing Record** | Returns `null`          | Throws `ObjectNotFoundException` |
| **Performance**    | Slower (Always hits DB) | Faster (If only ID is required)  |

## 🧹 Deletion & Synchronization

* **`remove(entity)`**: Deletes a managed entity instance from the database.
* **`flush()`**: Forces synchronization of the in-memory state with the database (executes pending SQL).
* **`clear()`**: Evicts all loaded entities from the session, making them **detached**.
* **`close()`**: Ends the session and releases the database connection.

---

# Hibernate Caching Architecture

Hibernate optimizes database performance through a multi-layer [Caching System](https://docs.jboss.org). This reduces
the number of SQL queries by storing frequently used entities in memory.

## 🧱 Cache Hierarchy

### 1. First-Level Cache (L1)

The **L1 Cache** is the default, mandatory cache associated with the [Session object](https://www.baeldung.com).

* **Scope:** Local to the current session.
* **Persistence Context:** Every object fetched via `get()` or `load()` is stored here.
* **Automatic:** You don't need to configure anything.
* **Clearing:** Managed via `session.evict(entity)` or `session.clear()`.

### 2. Second-Level Cache (L2)

The **L2 Cache** is an optional, pluggable cache shared across the entire `SessionFactory`.

* **Scope:** Global (Shared across all sessions).
* **Providers:** Requires libraries like [Ehcache](https://www.baeldung.com) or [Infinispan](https://infinispan.org).
* **Configuration:** Must be explicitly enabled in `hibernate.cfg.xml`.

### 3. Query Cache

Stores the results of specific queries (the IDs of the entities returned).

* **Requirement:** Must have L2 Cache enabled to work effectively.
* **Usage:** Enable globally and then set `query.setCacheable(true)` on specific HQL/Criteria queries.

---

## ⚙️ Configuration Example

To enable **L2 Caching** with Ehcache, add these properties to your configuration:

```xml
<!-- Enable L2 Cache -->
<property name="hibernate.cache.use_second_level_cache">true</property>

<!-- Specify Cache Provider -->
<property name="hibernate.cache.region.factory_class">
    org.hibernate.cache.ehcache.EhcacheRegionFactory
</property>

<!-- Enable Query Cache -->
<property name="hibernate.cache.use_query_cache">true</property>