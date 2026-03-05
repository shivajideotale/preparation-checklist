# Hibernate Complete Guide

A comprehensive guide covering Hibernate annotations, associations, inheritance strategies, session management, and caching.

---

## Table of Contents

1. [Hibernate Annotations](#1-hibernate-annotations)
2. [Association & Relationship Mapping](#2-association--relationship-mapping)
3. [Inheritance Strategies](#3-inheritance-strategies)
4. [Hibernate Session Management](#4-hibernate-session-management)
5. [Hibernate Caching](#5-hibernate-caching)

---

# 1. Hibernate Annotations

Hibernate annotations are used to map Java classes and their fields to database tables and columns, replacing XML-based configuration.

---

### 1.1 `@Entity`
Marks a class as a Hibernate entity (mapped to a DB table).

```java
@Entity
public class Student {
    // fields...
}
```

---

### 1.2 `@Table`
Specifies the table name in the database.

```java
@Entity
@Table(name = "students")
public class Student {
}
```

---

### 1.3 `@Id`
Marks the primary key field.

```java
@Id
private int id;
```

---

### 1.4 `@GeneratedValue`
Defines the strategy for auto-generating primary key values.

| Strategy | Description |
|---|---|
| `AUTO` | Hibernate chooses the strategy |
| `IDENTITY` | DB auto-increment |
| `SEQUENCE` | Uses a DB sequence |
| `TABLE` | Uses a separate table |

```java
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private int id;
```

---

### 1.5 `@Column`
Maps a field to a specific column with constraints.

```java
@Column(name = "student_name", nullable = false, length = 100)
private String name;
```

---

### 1.6 `@Transient`
Excludes a field from being persisted in the database.

```java
@Transient
private int temporaryScore;
```

---

### 1.7 `@Temporal`
Used for `java.util.Date` or `java.util.Calendar` fields to specify date/time type.

```java
@Temporal(TemporalType.DATE)
private Date enrollmentDate;
```

---

### 1.8 `@Lob`
Maps large objects (text or binary) like `CLOB` or `BLOB`.

```java
@Lob
private String biography;       // CLOB

@Lob
private byte[] profilePicture;  // BLOB
```

---

### 1.9 `@Enumerated`
Maps Java enums to database columns.

```java
public enum Gender { MALE, FEMALE }

@Enumerated(EnumType.STRING)
private Gender gender;
```

---

### 1.10 Relationship Annotations

#### `@OneToOne`
```java
@Entity
public class Student {
    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "address_id")
    private Address address;
}
```

#### `@OneToMany` / `@ManyToOne`
```java
@Entity
public class Department {
    @OneToMany(mappedBy = "department")
    private List<Employee> employees;
}

@Entity
public class Employee {
    @ManyToOne
    @JoinColumn(name = "dept_id")
    private Department department;
}
```

#### `@ManyToMany`
```java
@Entity
public class Course {
    @ManyToMany
    @JoinTable(
        name = "student_course",
        joinColumns = @JoinColumn(name = "course_id"),
        inverseJoinColumns = @JoinColumn(name = "student_id")
    )
    private List<Student> students;
}
```

---

### 1.11 `@Embeddable` / `@Embedded`
Embeds a non-entity class's fields into the parent table.

```java
@Embeddable
public class Address {
    private String city;
    private String zip;
}

@Entity
public class Student {
    @Embedded
    private Address address;
}
```

---

### 1.12 `@NamedQuery`
Defines a reusable HQL query at the class level.

```java
@Entity
@NamedQuery(name = "Student.findAll", query = "FROM Student")
public class Student {
}
```

---

### Complete Annotation Example

```java
@Entity
@Table(name = "students")
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "full_name", nullable = false, length = 100)
    private String name;

    @Column(unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    @Temporal(TemporalType.DATE)
    private Date enrollmentDate;

    @Lob
    private String bio;

    @Transient
    private int tempScore;

    @Embedded
    private Address address;

    @ManyToOne
    @JoinColumn(name = "dept_id")
    private Department department;

    // Getters & Setters
}
```

---

### Annotation Quick Reference

| Annotation | Purpose |
|---|---|
| `@Entity` | Marks class as DB entity |
| `@Table` | Specifies table name |
| `@Id` | Defines primary key |
| `@GeneratedValue` | Auto key generation |
| `@Column` | Column mapping & constraints |
| `@Transient` | Skip field from persistence |
| `@Temporal` | Date/time mapping |
| `@Lob` | Large object mapping |
| `@Enumerated` | Enum mapping |
| `@OneToOne` | One-to-one relation |
| `@OneToMany` | One-to-many relation |
| `@ManyToOne` | Many-to-one relation |
| `@ManyToMany` | Many-to-many relation |
| `@Embedded` | Embed value object |
| `@NamedQuery` | Reusable HQL query |

---

# 2. Association & Relationship Mapping

---

### Types of Associations

| Type | Example |
|---|---|
| `@OneToOne` | Employee ↔ ParkingSpot |
| `@OneToMany` | Department → Employees |
| `@ManyToOne` | Employee → Department |
| `@ManyToMany` | Employee ↔ Project |

---

### 2.1 `@ManyToOne` — Employee → Department

> Many employees belong to one department.

**Department.java**
```java
@Entity
@Table(name = "department")
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "dept_name", nullable = false)
    private String deptName;

    // Getters & Setters
}
```

**Employee.java**
```java
@Entity
@Table(name = "employee")
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "emp_name")
    private String empName;

    @ManyToOne                              // Many employees → One department
    @JoinColumn(name = "dept_id")          // FK column in employee table
    private Department department;

    // Getters & Setters
}
```

**DB Structure:**
```
employee table               department table
-----------------------      -------------------
id | emp_name | dept_id  →   id | dept_name
```

---

### 2.2 `@OneToMany` — Department → Employees

> One department has many employees.

```java
@Entity
@Table(name = "department")
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "dept_name")
    private String deptName;

    @OneToMany(mappedBy = "department",         // refers to field in Employee
               cascade = CascadeType.ALL,        // operations cascade to employees
               fetch = FetchType.LAZY)           // load employees only when needed
    private List<Employee> employees = new ArrayList<>();

    // Getters & Setters
}
```

> `mappedBy = "department"` tells Hibernate that `Employee` owns the relationship (has the FK).

---

### 2.3 Bidirectional Mapping (Both sides)

> Both `Department` and `Employee` are aware of each other.

**Department.java**
```java
@Entity
@Table(name = "department")
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "dept_name")
    private String deptName;

    @OneToMany(mappedBy = "department", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Employee> employees = new ArrayList<>();

    // Helper method to maintain both sides
    public void addEmployee(Employee emp) {
        employees.add(emp);
        emp.setDepartment(this);
    }

    // Getters & Setters
}
```

**Employee.java**
```java
@Entity
@Table(name = "employee")
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "emp_name")
    private String empName;

    @Column(name = "salary")
    private double salary;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dept_id", nullable = false)   // FK in employee table
    private Department department;

    // Getters & Setters
}
```

---

### 2.4 `@OneToOne` — Employee ↔ ParkingSpot

> Each employee has exactly one parking spot.

**ParkingSpot.java**
```java
@Entity
@Table(name = "parking_spot")
public class ParkingSpot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "spot_number")
    private String spotNumber;

    @OneToOne(mappedBy = "parkingSpot")   // owned by Employee
    private Employee employee;

    // Getters & Setters
}
```

**Employee.java** *(add this field)*
```java
@OneToOne(cascade = CascadeType.ALL)
@JoinColumn(name = "parking_spot_id", unique = true)   // FK in employee table
private ParkingSpot parkingSpot;
```

---

### 2.5 `@ManyToMany` — Employee ↔ Project

> Employees can work on many projects; projects can have many employees.

**Project.java**
```java
@Entity
@Table(name = "project")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "project_name")
    private String projectName;

    @ManyToMany(mappedBy = "projects")     // owned by Employee
    private List<Employee> employees = new ArrayList<>();

    // Getters & Setters
}
```

**Employee.java** *(add this field)*
```java
@ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE })
@JoinTable(
    name = "employee_project",                            // join/bridge table
    joinColumns = @JoinColumn(name = "employee_id"),      // FK to employee
    inverseJoinColumns = @JoinColumn(name = "project_id") // FK to project
)
private List<Project> projects = new ArrayList<>();
```

**DB Structure:**
```
employee_project (join table)
------------------------------
employee_id  |  project_id
```

---

### 2.6 Cascade Types

| CascadeType | Behavior |
|---|---|
| `PERSIST` | Save child when parent is saved |
| `MERGE` | Update child when parent is updated |
| `REMOVE` | Delete child when parent is deleted |
| `REFRESH` | Refresh child when parent is refreshed |
| `DETACH` | Detach child when parent is detached |
| `ALL` | Apply all of the above |

```java
@OneToMany(mappedBy = "department", cascade = CascadeType.ALL)
private List<Employee> employees;
```

---

### 2.7 Fetch Types

| FetchType | Behavior | Default |
|---|---|---|
| `LAZY` | Load related data **only when accessed** | `@OneToMany`, `@ManyToMany` |
| `EAGER` | Load related data **immediately with parent** | `@ManyToOne`, `@OneToOne` |

```java
// Employees loaded only when dept.getEmployees() is called
@OneToMany(mappedBy = "department", fetch = FetchType.LAZY)
private List<Employee> employees;

// Department loaded immediately with employee
@ManyToOne(fetch = FetchType.EAGER)
@JoinColumn(name = "dept_id")
private Department department;
```

---

### Association Usage Example

```java
// Create Department
Department dept = new Department();
dept.setDeptName("Engineering");

// Create Employees
Employee emp1 = new Employee();
emp1.setEmpName("Alice");
emp1.setSalary(75000);

Employee emp2 = new Employee();
emp2.setEmpName("Bob");
emp2.setSalary(68000);

// Link employees to department (bidirectional)
dept.addEmployee(emp1);
dept.addEmployee(emp2);

// Save — cascades to employees automatically
session.save(dept);

// Fetch department with employees
Department fetchedDept = session.get(Department.class, 1);
fetchedDept.getEmployees().forEach(e ->
    System.out.println(e.getEmpName() + " - " + e.getSalary())
);
```

---

### Association Summary

```
Department (1) ────────────── (Many) Employee
    │                                   │
    │ @OneToMany(mappedBy="department") │ @ManyToOne
    │                                   │ @JoinColumn(name="dept_id")
    └───────────────────────────────────┘

Employee (1) ──────── (1) ParkingSpot      → @OneToOne
Employee (Many) ───── (Many) Project       → @ManyToMany @JoinTable
```

---

# 3. Inheritance Strategies

Hibernate provides **4 strategies** to map Java inheritance hierarchies to database tables.

---

### Base Class Setup (Used in all strategies)

```java
@Entity
public abstract class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "emp_name")
    private String empName;

    @Column(name = "salary")
    private double salary;

    // Getters & Setters
}
```

**Subclasses:**
```java
@Entity
public class FullTimeEmployee extends Employee {
    private String benefits;      // extra field
}

@Entity
public class PartTimeEmployee extends Employee {
    private int hoursPerWeek;     // extra field
}
```

---

### Strategy 1: `SINGLE_TABLE` (Default)

> All classes in the hierarchy are mapped to **one single table**.  
> A **discriminator column** identifies which subclass each row belongs to.

```java
@Entity
@Table(name = "employee")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "emp_type",
                     discriminatorType = DiscriminatorType.STRING)
public abstract class Employee {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    private String empName;
    private double salary;
}

@Entity
@DiscriminatorValue("FULL_TIME")
public class FullTimeEmployee extends Employee {
    private String benefits;
}

@Entity
@DiscriminatorValue("PART_TIME")
public class PartTimeEmployee extends Employee {
    private int hoursPerWeek;
}
```

**DB Structure:**
```
employee table
--------------------------------------------------------
id | emp_name | salary | emp_type  | benefits | hoursPerWeek
1  | Alice    | 80000  | FULL_TIME | Health   | NULL
2  | Bob      | 0      | PART_TIME | NULL     | 20
```

✅ **Pros:** Simple, best performance (no joins), easy to query  
❌ **Cons:** Nullable columns for subclass fields, wastes space

---

### Strategy 2: `TABLE_PER_CLASS`

> Each **concrete class** gets its **own table** with ALL fields (including inherited ones).

```java
@Entity
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
public abstract class Employee {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)   // AUTO required, not IDENTITY
    private int id;
    private String empName;
    private double salary;
}

@Entity
@Table(name = "full_time_employee")
public class FullTimeEmployee extends Employee {
    private String benefits;
}

@Entity
@Table(name = "part_time_employee")
public class PartTimeEmployee extends Employee {
    private int hoursPerWeek;
}
```

**DB Structure:**
```
full_time_employee table          part_time_employee table
-------------------------------   ------------------------------
id | emp_name | salary | benefits  id | emp_name | salary | hoursPerWeek
1  | Alice    | 80000  | Health    2  | Bob      | 0      | 20
```

> ⚠️ `GenerationType.IDENTITY` won't work here — use `AUTO` or `SEQUENCE` to ensure unique IDs across tables.

✅ **Pros:** No NULL columns, clean table structure  
❌ **Cons:** Duplicated base columns, slow polymorphic queries (uses UNION)

---

### Strategy 3: `JOINED` (Table Per Subclass)

> Base class has its **own table**, each subclass has a **separate table** with only its extra fields.  
> Tables are joined via **primary key / foreign key**.

```java
@Entity
@Table(name = "employee")
@Inheritance(strategy = InheritanceType.JOINED)
public abstract class Employee {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    private String empName;
    private double salary;
}

@Entity
@Table(name = "full_time_employee")
@PrimaryKeyJoinColumn(name = "emp_id")     // FK referencing employee.id
public class FullTimeEmployee extends Employee {
    private String benefits;
}

@Entity
@Table(name = "part_time_employee")
@PrimaryKeyJoinColumn(name = "emp_id")
public class PartTimeEmployee extends Employee {
    private int hoursPerWeek;
}
```

**DB Structure:**
```
employee (base table)             full_time_employee        part_time_employee
----------------------            ------------------        ------------------
id | emp_name | salary            emp_id | benefits         emp_id | hoursPerWeek
1  | Alice    | 80000      →      1      | Health
2  | Bob      | 0          →                                2      | 20
```

✅ **Pros:** Normalized, no NULL columns, clean design  
❌ **Cons:** Requires JOIN queries, slower than SINGLE_TABLE

---

### Strategy 4: `@MappedSuperclass`

> The base class is **not an entity** — it is **not mapped to any table**.  
> Each subclass gets its **own table** with all fields including inherited ones.  
> ⚠️ No polymorphic queries possible.

```java
@MappedSuperclass                   // NOT @Entity — no table created
public abstract class Employee {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    private String empName;
    private double salary;
    // Getters & Setters
}

@Entity
@Table(name = "full_time_employee")
public class FullTimeEmployee extends Employee {
    private String benefits;
}

@Entity
@Table(name = "part_time_employee")
public class PartTimeEmployee extends Employee {
    private int hoursPerWeek;
}
```

**DB Structure:**
```
full_time_employee                 part_time_employee
-------------------------------    --------------------------------
id | emp_name | salary | benefits  id | emp_name | salary | hoursPerWeek
1  | Alice    | 80000  | Health    2  | Bob      | 0      | 20
```

✅ **Pros:** Simple, independent tables, no joins needed  
❌ **Cons:** Cannot query across the hierarchy, no shared table

---

### Inheritance Strategy Comparison

| Feature | SINGLE_TABLE | TABLE_PER_CLASS | JOINED | MappedSuperclass |
|---|---|---|---|---|
| Tables Created | 1 | 1 per subclass | 1 base + 1 per subclass | 1 per subclass |
| NULL Columns | ✅ Yes | ❌ No | ❌ No | ❌ No |
| JOIN Required | ❌ No | ❌ No | ✅ Yes | ❌ No |
| Polymorphic Query | ✅ Yes | ✅ Yes (UNION) | ✅ Yes | ❌ No |
| Performance | ⭐⭐⭐ Best | ⭐⭐ Medium | ⭐⭐ Medium | ⭐⭐⭐ Best |
| Normalized Design | ❌ No | ❌ No | ✅ Yes | ❌ No |
| Base class is Entity | ✅ Yes | ✅ Yes | ✅ Yes | ❌ No |

---

### When to Use Which?

```
Need best performance & simple queries?
        └──→  SINGLE_TABLE ✅

Need clean normalized database design?
        └──→  JOINED ✅

Need fully independent tables, no polymorphism?
        └──→  TABLE_PER_CLASS or MappedSuperclass ✅

Just sharing common fields (no polymorphic queries)?
        └──→  MappedSuperclass ✅
```

---

# 4. Hibernate Session Management

A **Session** is the core interface in Hibernate used to interact with the database — performing CRUD operations, managing transactions, and caching objects.

---

### Session Lifecycle

```
SessionFactory (created once per app)
        │
        ▼
    Session (created per request/unit of work)
        │
        ├── beginTransaction()
        │         │
        │    [ CRUD Operations ]
        │         │
        ├── commit() / rollback()
        │
        └── close()
```

---

### 4.1 SessionFactory — Created Once

```java
public class HibernateUtil {

    private static SessionFactory sessionFactory;

    static {
        try {
            Configuration config = new Configuration();
            config.configure("hibernate.cfg.xml");     // load config file

            // Register entity classes
            config.addAnnotatedClass(Employee.class);
            config.addAnnotatedClass(Department.class);

            ServiceRegistry serviceRegistry = new StandardServiceRegistryBuilder()
                    .applySettings(config.getProperties())
                    .build();

            sessionFactory = config.buildSessionFactory(serviceRegistry);

        } catch (Exception e) {
            e.printStackTrace();
            throw new ExceptionInInitializerError(e);
        }
    }

    // Returns single instance of SessionFactory
    public static SessionFactory getSessionFactory() {
        return sessionFactory;
    }

    // Closes factory on app shutdown
    public static void shutdown() {
        sessionFactory.close();
    }
}
```

---

### 4.2 Opening & Closing a Session

```java
// Open session
Session session = HibernateUtil.getSessionFactory().openSession();

// Always close session in finally block
try {
    // operations...
} finally {
    if (session != null && session.isOpen()) {
        session.close();
    }
}
```

---

### 4.3 CRUD Operations with Session

#### Save (INSERT)
```java
Session session = HibernateUtil.getSessionFactory().openSession();
Transaction tx = null;

try {
    tx = session.beginTransaction();

    Employee emp = new Employee();
    emp.setEmpName("Alice");
    emp.setSalary(75000);

    session.save(emp);           // INSERT into employee
    tx.commit();
    System.out.println("Saved: " + emp.getId());

} catch (Exception e) {
    if (tx != null) tx.rollback();
    e.printStackTrace();
} finally {
    session.close();
}
```

---

#### Get vs Load (SELECT)

```java
// get() → hits DB immediately, returns NULL if not found
Employee emp1 = session.get(Employee.class, 1);

// load() → returns proxy, hits DB only when accessed
//          throws ObjectNotFoundException if not found
Employee emp2 = session.load(Employee.class, 2);

System.out.println(emp1.getEmpName());    // direct object
System.out.println(emp2.getEmpName());    // DB hit happens here (lazy proxy)
```

| Feature | `get()` | `load()` |
|---|---|---|
| DB Hit | Immediately | When object is accessed |
| Not Found | Returns `null` | Throws exception |
| Returns | Real object | Proxy object |
| Use Case | When unsure if record exists | When sure record exists |

---

#### Update
```java
try {
    tx = session.beginTransaction();

    // Method 1: Direct update using get()
    Employee emp = session.get(Employee.class, 1);
    emp.setSalary(90000);              // just modify — no explicit update needed
    tx.commit();                       // Hibernate detects dirty object & updates

    // Method 2: session.update() for detached objects
    Employee detachedEmp = new Employee();
    detachedEmp.setId(2);
    detachedEmp.setEmpName("Bob Updated");
    detachedEmp.setSalary(68000);

    session.update(detachedEmp);       // UPDATE using id
    tx.commit();

} catch (Exception e) {
    if (tx != null) tx.rollback();
} finally {
    session.close();
}
```

---

#### `saveOrUpdate()`
```java
// Inserts if new, updates if already exists (based on ID)
Employee emp = new Employee();
emp.setId(0);                     // id=0 → INSERT
emp.setEmpName("Charlie");
session.saveOrUpdate(emp);

emp.setId(5);                     // id=5 → UPDATE (if exists)
session.saveOrUpdate(emp);
```

---

#### Delete
```java
try {
    tx = session.beginTransaction();

    Employee emp = session.get(Employee.class, 1);
    if (emp != null) {
        session.delete(emp);       // DELETE from employee where id=1
    }
    tx.commit();

} catch (Exception e) {
    if (tx != null) tx.rollback();
} finally {
    session.close();
}
```

---

### 4.4 Object States in Hibernate

```
  new Employee()          session.save()         session.close()
  ─────────────→  TRANSIENT ──────────→ PERSISTENT ──────────→ DETACHED
                                │                                   │
                                │ session.delete()                  │ session.update()
                                ▼                                   │ session.merge()
                             REMOVED                                ▼
                                                               PERSISTENT
```

| State | Description | In Session? | In DB? |
|---|---|---|---|
| **Transient** | Newly created, not associated with session | ❌ | ❌ |
| **Persistent** | Associated with session, tracked by Hibernate | ✅ | ✅ |
| **Detached** | Was persistent, session is now closed | ❌ | ✅ |
| **Removed** | Scheduled for deletion | ✅ | ❌ (pending) |

```java
// TRANSIENT
Employee emp = new Employee();
emp.setEmpName("Alice");               // not tracked

// PERSISTENT
session.save(emp);                     // now tracked by Hibernate
emp.setSalary(80000);                  // auto-detected, will update on commit

// DETACHED
session.close();                       // emp is now detached

// Re-attach detached object
Session newSession = sessionFactory.openSession();
session.update(emp);                   // back to PERSISTENT
// OR
Employee mergedEmp = (Employee) session.merge(emp);  // safer merge
```

---

### 4.5 Transaction Management

```java
Transaction tx = null;
try {
    tx = session.beginTransaction();

    // multiple operations in one transaction
    Department dept = new Department();
    dept.setDeptName("HR");
    session.save(dept);

    Employee emp = new Employee();
    emp.setEmpName("Alice");
    emp.setDepartment(dept);
    session.save(emp);

    tx.commit();                       // both saved or none

} catch (Exception e) {
    if (tx != null) tx.rollback();    // rollback on failure
    e.printStackTrace();
} finally {
    session.close();
}
```

---

### 4.6 Session Cache (First-Level Cache)

> Hibernate caches objects **within a session** automatically. Same session + same ID = **no DB hit**.

```java
Session session = sessionFactory.openSession();

Employee emp1 = session.get(Employee.class, 1);   // DB hit ✅
Employee emp2 = session.get(Employee.class, 1);   // from cache — NO DB hit ⚡
Employee emp3 = session.get(Employee.class, 1);   // from cache — NO DB hit ⚡

System.out.println(emp1 == emp2);                 // true — same object!

// Clear cache manually
session.evict(emp1);                  // remove specific object from cache
session.clear();                      // remove all objects from cache
session.flush();                      // sync cache state to DB (before commit)
```

---

### 4.7 `getCurrentSession` vs `openSession`

```java
// openSession() — manually manage open/close
Session session = sessionFactory.openSession();
// must call session.close() manually

// getCurrentSession() — tied to current thread/transaction
// automatically closed when transaction commits/rolls back
Session session = sessionFactory.getCurrentSession();
// requires hibernate.current_session_context_class = thread in config
```

| Feature | `openSession()` | `getCurrentSession()` |
|---|---|---|
| Session per call | New session every time | Reuses existing session |
| Close manually | ✅ Required | ❌ Auto-closed |
| Transaction bound | ❌ No | ✅ Yes |
| Best for | Batch jobs, manual control | Web apps, Spring apps |

---

### 4.8 Complete CRUD Example

```java
public class EmployeeDAO {

    // CREATE
    public void saveEmployee(Employee emp) {
        Session session = HibernateUtil.getSessionFactory().openSession();
        Transaction tx = null;
        try {
            tx = session.beginTransaction();
            session.save(emp);
            tx.commit();
        } catch (Exception e) {
            if (tx != null) tx.rollback();
        } finally {
            session.close();
        }
    }

    // READ
    public Employee getEmployee(int id) {
        Session session = HibernateUtil.getSessionFactory().openSession();
        try {
            return session.get(Employee.class, id);
        } finally {
            session.close();
        }
    }

    // UPDATE
    public void updateEmployee(Employee emp) {
        Session session = HibernateUtil.getSessionFactory().openSession();
        Transaction tx = null;
        try {
            tx = session.beginTransaction();
            session.update(emp);
            tx.commit();
        } catch (Exception e) {
            if (tx != null) tx.rollback();
        } finally {
            session.close();
        }
    }

    // DELETE
    public void deleteEmployee(int id) {
        Session session = HibernateUtil.getSessionFactory().openSession();
        Transaction tx = null;
        try {
            tx = session.beginTransaction();
            Employee emp = session.get(Employee.class, id);
            if (emp != null) session.delete(emp);
            tx.commit();
        } catch (Exception e) {
            if (tx != null) tx.rollback();
        } finally {
            session.close();
        }
    }
}
```

---

### Session Management Quick Reference

```
SessionFactory  →  Created ONCE at startup (expensive)
Session         →  Created per request (lightweight)
Transaction     →  Wraps every DB operation
get()           →  Safe fetch, returns null if missing
load()          →  Lazy proxy, throws if missing
save()          →  INSERT new record
update()        →  UPDATE detached object
saveOrUpdate()  →  INSERT or UPDATE based on ID
delete()        →  DELETE record
merge()         →  Re-attach detached object safely
flush()         →  Sync session cache → DB
clear()         →  Wipe session cache
close()         →  End session
```

---

# 5. Hibernate Caching

Hibernate caching reduces database hits by storing frequently accessed data in memory, improving application performance significantly.

---

### Caching Architecture Overview

```
Application
     │
     ▼
 Session (First-Level Cache)  ←── Always enabled, per session
     │
     ▼
 SessionFactory (Second-Level Cache)  ←── Optional, shared across sessions
     │
     ▼
 Query Cache  ←── Caches query results
     │
     ▼
 Database
```

---

## 5.1 Level 1 Cache — First-Level Cache

> Built-in, **always enabled**, scoped to a **single Session**.  
> Objects are cached within the same session automatically.

```java
Session session = sessionFactory.openSession();

// First call → hits DB
Employee emp1 = session.get(Employee.class, 1);   // SELECT fired ✅

// Second call → served from L1 cache (NO DB hit)
Employee emp2 = session.get(Employee.class, 1);   // from cache ⚡

System.out.println(emp1 == emp2);   // true — exact same object

session.close();   // L1 cache destroyed here
```

---

### L1 Cache Control Methods

```java
Session session = sessionFactory.openSession();
Employee emp = session.get(Employee.class, 1);

session.evict(emp);          // Remove specific object from cache
session.refresh(emp);        // Reload from DB (bypasses cache)
session.clear();             // Clear entire session cache
session.flush();             // Sync cache changes to DB without committing

boolean cached = session.contains(emp);
System.out.println("In cache: " + cached);    // true / false
```

---

### L1 Cache Behavior Across Sessions

```java
// Session 1
Session session1 = sessionFactory.openSession();
Employee emp1 = session1.get(Employee.class, 1);  // DB hit
session1.close();                                  // L1 cache cleared ❌

// Session 2 — L1 cache is fresh, hits DB again
Session session2 = sessionFactory.openSession();
Employee emp2 = session2.get(Employee.class, 1);  // DB hit again ✅
session2.close();
```

> L1 cache does **NOT** share across sessions — that's where **L2 cache** comes in.

---

## 5.2 Level 2 Cache — Second-Level Cache

> **Optional**, must be explicitly configured.  
> Scoped to **SessionFactory** — shared across all sessions.  
> Survives session close/open cycles.

---

### Step 1 — Add EHCache Dependency (`pom.xml`)

```xml
<!-- Hibernate EHCache Integration -->
<dependency>
    <groupId>org.hibernate</groupId>
    <artifactId>hibernate-ehcache</artifactId>
    <version>5.6.15.Final</version>
</dependency>

<!-- EHCache Core -->
<dependency>
    <groupId>net.sf.ehcache</groupId>
    <artifactId>ehcache</artifactId>
    <version>2.10.9.2</version>
</dependency>
```

---

### Step 2 — Configure `hibernate.cfg.xml`

```xml
<hibernate-configuration>
    <session-factory>

        <property name="hibernate.connection.driver_class">com.mysql.cj.jdbc.Driver</property>
        <property name="hibernate.connection.url">jdbc:mysql://localhost:3306/companydb</property>
        <property name="hibernate.connection.username">root</property>
        <property name="hibernate.connection.password">password</property>

        <!-- Enable Second-Level Cache -->
        <property name="hibernate.cache.use_second_level_cache">true</property>

        <!-- EHCache as the cache provider -->
        <property name="hibernate.cache.region.factory_class">
            org.hibernate.cache.ehcache.EhCacheRegionFactory
        </property>

        <!-- Enable Query Cache -->
        <property name="hibernate.cache.use_query_cache">true</property>

        <property name="hibernate.show_sql">true</property>

        <mapping class="com.example.Employee"/>
        <mapping class="com.example.Department"/>

    </session-factory>
</hibernate-configuration>
```

---

### Step 3 — Configure `ehcache.xml`

```xml
<ehcache>

    <defaultCache
        maxEntriesLocalHeap="1000"
        eternal="false"
        timeToIdleSeconds="300"
        timeToLiveSeconds="600"
        memoryStoreEvictionPolicy="LRU"/>

    <cache name="com.example.Employee"
        maxEntriesLocalHeap="500"
        eternal="false"
        timeToIdleSeconds="200"
        timeToLiveSeconds="500"
        memoryStoreEvictionPolicy="LRU"/>

    <cache name="com.example.Department"
        maxEntriesLocalHeap="100"
        eternal="false"
        timeToIdleSeconds="300"
        timeToLiveSeconds="600"
        memoryStoreEvictionPolicy="LFU"/>

</ehcache>
```

---

### Step 4 — Annotate Entity for L2 Cache

```java
@Entity
@Table(name = "employee")
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)   // L2 cache enabled
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    private String empName;
    private double salary;

    @ManyToOne
    @JoinColumn(name = "dept_id")
    @Cache(usage = CacheConcurrencyStrategy.READ_ONLY)  // cache the association too
    private Department department;

    // Getters & Setters
}
```

---

### Cache Concurrency Strategies

| Strategy | Description | Use Case |
|---|---|---|
| `READ_ONLY` | No updates after insert | Reference/static data |
| `READ_WRITE` | Supports reads and updates safely | Most entities |
| `NONSTRICT_READ_WRITE` | Updates without strict locking | Rarely updated data |
| `TRANSACTIONAL` | Full transaction support | Critical/financial data |

```java
@Cache(usage = CacheConcurrencyStrategy.READ_ONLY)
public class Country { }                              // static data

@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class Employee { }                             // regular entities

@Cache(usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)
public class Department { }                           // rarely updated

@Cache(usage = CacheConcurrencyStrategy.TRANSACTIONAL)
public class BankAccount { }                          // critical data
```

---

### L2 Cache in Action

```java
// Session 1 — first fetch hits DB
Session session1 = sessionFactory.openSession();
Employee emp1 = session1.get(Employee.class, 1);   // SELECT fired, stored in L2 ✅
session1.close();                                   // L1 destroyed, L2 survives ⚡

// Session 2 — served from L2 cache (NO DB hit)
Session session2 = sessionFactory.openSession();
Employee emp2 = session2.get(Employee.class, 1);   // from L2 cache ⚡ NO SQL!
session2.close();

// Session 3 — still from L2 cache
Session session3 = sessionFactory.openSession();
Employee emp3 = session3.get(Employee.class, 1);   // from L2 cache ⚡ NO SQL!
session3.close();
```

---

## 5.3 Query Cache

> Caches the **results of HQL/Criteria queries** (not just entities).  
> Works together with L2 cache.  
> Stores query + parameters → result IDs mapping.

```java
Session session = sessionFactory.openSession();

// Enable query cache on specific query
List<Employee> employees = session.createQuery("FROM Employee WHERE salary > :sal")
        .setParameter("sal", 50000)
        .setCacheable(true)                        // enable query cache ✅
        .setCacheRegion("employee.highSalary")     // optional named region
        .list();

// Same query fired again — served from query cache ⚡
List<Employee> cachedResult = session.createQuery("FROM Employee WHERE salary > :sal")
        .setParameter("sal", 50000)
        .setCacheable(true)
        .list();                                   // NO DB hit ⚡
```

---

### Query Cache Flow

```
Query fired
    │
    ▼
Query Cache hit?
    ├── YES → return cached IDs → fetch entities from L2 cache → ⚡ done
    └── NO  → hit DB → store result IDs in Query Cache
                     → store entities in L2 Cache
```

> ⚠️ Query cache stores **IDs only**, not full objects. Entities must also be in L2 cache.

---

## 5.4 Caching Comparison

| Feature | L1 Cache | L2 Cache | Query Cache |
|---|---|---|---|
| Scope | Per Session | Per SessionFactory | Per SessionFactory |
| Enabled by default | ✅ Always | ❌ Must configure | ❌ Must configure |
| Shared across sessions | ❌ No | ✅ Yes | ✅ Yes |
| Survives session close | ❌ No | ✅ Yes | ✅ Yes |
| Caches | Entity objects | Entity objects | Query result IDs |
| Provider needed | None | EHCache / Redis | EHCache / Redis |

---

## 5.5 Cache Eviction & Invalidation

```java
// Evict specific entity from L2 cache
sessionFactory.getCache().evictEntity(Employee.class, 1);

// Evict all cached Employee entities
sessionFactory.getCache().evictEntityData(Employee.class);

// Evict query cache region
sessionFactory.getCache().evictQueryRegion("employee.highSalary");

// Evict all query cache
sessionFactory.getCache().evictAllRegions();

// Check if entity is in L2 cache
boolean inCache = sessionFactory.getCache()
                                .containsEntity(Employee.class, 1);
System.out.println("In L2 Cache: " + inCache);
```

---

## 5.6 Complete Caching Setup Summary

```
1. Add EHCache dependency (pom.xml)
        │
        ▼
2. Enable L2 cache in hibernate.cfg.xml
   hibernate.cache.use_second_level_cache = true
   hibernate.cache.use_query_cache        = true
        │
        ▼
3. Configure cache regions in ehcache.xml
   (TTL, max entries, eviction policy)
        │
        ▼
4. Annotate entities with @Cache
   @Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
        │
        ▼
5. Use .setCacheable(true) on queries
```

---

### Caching Quick Reference

```
L1 Cache    → Auto, per session, no config needed
L2 Cache    → Manual, shared, needs EHCache setup + @Cache on entity
Query Cache → Manual, caches query results, needs setCacheable(true)

READ_ONLY          → Static/reference data
READ_WRITE         → Regular entities ← most common
NONSTRICT_R_W      → Rarely updated, stale ok
TRANSACTIONAL      → Critical data, JTA needed

evict()                        → remove from L1
session.clear()                → clear all L1
sessionFactory.getCache()
  .evictEntity()               → remove from L2
```

---

*End of Hibernate Complete Guide*
