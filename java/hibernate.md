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
* `FetchType`:
    * `LAZY`:    Loads the association only when you call the getter (highly recommended for performance in
      collections).
    * `EAGER`:     Loads the association immediately with the parent entity (default for @ManyToOne).
* `@JoinColumn`:   Defines the physical mapping of the foreign key in the database table.
* `orphanRemoval`:    If set to true, removing a child from the parent's collection will also delete that child record
  from the database.

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
