# 📂 Hibernate & JPA Annotations Master Reference

A complete, detailed guide to mapping Java objects (POJOs) to relational database tables using Hibernate and the Jakarta Persistence API (JPA).

---

## 🛠️ 1. Basic Mapping Annotations
These are the essential building blocks for every persistent class.

### **@Entity**
*   **Description:** Marks a Java class as a [managed persistence entity](https://docs.jboss.org).
*   **Example:**
    ```java
    @Entity
    public class User { ... }
    ```

### **@Table**
*   **Description:** Maps the entity to a specific [database table](https://www.baeldung.com).
*   **Attributes:** `name`, `schema`, `indexes`.
*   **Example:**
    ```java
    @Table(name = "app_users", schema = "public")
    ```

### **@Id & @GeneratedValue**
*   **Description:** `@Id` defines the [primary key](https://www.geeksforgeeks.org). `@GeneratedValue` specifies its auto-generation strategy.
*   **Strategies:** `IDENTITY` (Auto-increment), `SEQUENCE` (DB Sequence), `AUTO` (Provider choice).
*   **Example:**
    ```java
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    ```

---

## 🔗 2. Association & Relationship Mapping
Defines how tables are linked via foreign keys.

### **@ManyToOne**
*   **Description:** Many records of this entity link to one record of another (e.g., many Employees in one Dept).
*   **Example:**
    ```java
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dept_id")
    private Department department;
    ```

### **@OneToMany**
*   **Description:** One record links to a collection of others. Usually paired with `mappedBy` for bi-directional links.
*   **Example:**
    ```java
    @OneToMany(mappedBy = "department", cascade = CascadeType.ALL)
    private List<Employee> employees;
    ```

---

## ⚙️ 3. Column & Behavioral Customization

*   **@Column**: Customises the [DB column](https://medium.com) (name, nullable, length).
*   **@Transient**: Tells Hibernate to ignore this field; it won't be saved to the DB.
*   **@Enumerated**: Maps Java Enums. Use `EnumType.STRING` for database readability.
*   **@Temporal**: Formats `java.util.Date` as `DATE`, `TIME`, or `TIMESTAMP`.
*   **@Lob**: Used for "Large Objects" like long text (CLOB) or binary files (BLOB).
*   **@Version**: Enables [Optimistic Locking](https://ankurm.com) to prevent data collisions.

---

## 🧬 4. Inheritance Strategies
Specifies how class hierarchies are stored using the [`@Inheritance`](https://www.baeldung.com) annotation.

1.  **SINGLE_TABLE**: (Default) One table for all classes with a `@DiscriminatorColumn`.
2.  **JOINED**: Base fields in one table, subclass-specific fields in separate tables.
3.  **TABLE_PER_CLASS**: Each concrete class gets its own independent table.

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
