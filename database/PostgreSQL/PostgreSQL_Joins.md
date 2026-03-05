# PostgreSQL Joins — Complete Deep Dive Reference

> A comprehensive guide covering every join type in PostgreSQL with detailed explanations, examples, execution plans, and pitfalls.

---

## Table of Contents

1. [Sample Tables Setup](#sample-tables-setup)
2. [INNER JOIN](#1-inner-join)
3. [LEFT JOIN](#2-left-join-left-outer-join)
4. [RIGHT JOIN](#3-right-join-right-outer-join)
5. [FULL OUTER JOIN](#4-full-outer-join)
6. [CROSS JOIN](#5-cross-join)
7. [SELF JOIN](#6-self-join)
8. [EQUI JOIN](#7-equi-join)
9. [NON-EQUI JOIN](#8-non-equi-join)
10. [NATURAL JOIN](#9-natural-join)
11. [JOIN with USING](#10-join-with-using)
12. [Multiple Joins Chaining](#11-multiple-joins-chaining)
13. [JOIN with Aggregation](#12-join-with-aggregation)
14. [LATERAL JOIN](#13-lateral-join)
15. [Anti-Join](#14-anti-join-not-exists--left-join-is-null)
16. [Semi-Join](#15-semi-join-exists)
17. [Join Type Decision Guide](#join-type-decision-guide)
18. [Common Pitfalls](#common-pitfalls)
19. [Old-Style Implicit Joins](#old-style-implicit-joins-avoid)
20. [Further Reading](#further-reading)

---

## Sample Tables Setup

All examples below use these tables:

```sql
CREATE TABLE customers (
    id      SERIAL PRIMARY KEY,
    name    TEXT NOT NULL,
    city    TEXT
);

CREATE TABLE orders (
    id          SERIAL PRIMARY KEY,
    customer_id INTEGER REFERENCES customers(id),
    product     TEXT,
    amount      NUMERIC
);

-- customers data
INSERT INTO customers VALUES
  (1, 'Alice',   'Mumbai'),
  (2, 'Bob',     'Delhi'),
  (3, 'Charlie', 'Pune'),
  (4, 'Diana',   NULL);        -- no city

-- orders data
INSERT INTO orders VALUES
  (101, 1,    'Laptop',   80000),
  (102, 1,    'Mouse',    1500),
  (103, 2,    'Monitor',  25000),
  (104, NULL, 'Keyboard', 3000);  -- orphan order (no customer)
```

**Customers table:**

| id | name    | city   |
|----|---------|--------|
| 1  | Alice   | Mumbai |
| 2  | Bob     | Delhi  |
| 3  | Charlie | Pune   |
| 4  | Diana   | NULL   |

**Orders table:**

| id  | customer_id | product  | amount |
|-----|-------------|----------|--------|
| 101 | 1           | Laptop   | 80000  |
| 102 | 1           | Mouse    | 1500   |
| 103 | 2           | Monitor  | 25000  |
| 104 | NULL        | Keyboard | 3000   |

---

## 1. INNER JOIN

Returns only rows where there is a **match in BOTH tables**. Non-matching rows from either side are excluded entirely.

```sql
SELECT c.name, o.product, o.amount
FROM customers c
INNER JOIN orders o ON o.customer_id = c.id;
```

**Result:**

| name  | product | amount |
|-------|---------|--------|
| Alice | Laptop  | 80000  |
| Alice | Mouse   | 1500   |
| Bob   | Monitor | 25000  |

- **Charlie** (id=3) excluded — has no orders
- **Diana** (id=4) excluded — has no orders
- **Order 104** (customer_id=NULL) excluded — no matching customer

**How the planner executes it:**

```
Hash Join
  ->  Seq Scan on customers   (build hash table on id)
  ->  Seq Scan on orders      (probe hash table per row)
```

**Use cases:** Reports requiring data from both sides — invoices with customer details, employees with departments, products with categories.

---

## 2. LEFT JOIN (LEFT OUTER JOIN)

Returns **all rows from the left table**, plus matching rows from the right. Where there is no match, right-side columns are `NULL`.

```sql
SELECT c.name, o.product, o.amount
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id;
```

**Result:**

| name    | product | amount |
|---------|---------|--------|
| Alice   | Laptop  | 80000  |
| Alice   | Mouse   | 1500   |
| Bob     | Monitor | 25000  |
| Charlie | NULL    | NULL   |
| Diana   | NULL    | NULL   |

- **Charlie** and **Diana** appear with `NULL` order columns

**Find customers with NO orders (anti-join pattern):**

```sql
SELECT c.name
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id
WHERE o.id IS NULL;
-- Returns: Charlie, Diana
```

**Use cases:** Customer lists with optional order data, products with or without reviews, users with or without profile data.

---

## 3. RIGHT JOIN (RIGHT OUTER JOIN)

Returns **all rows from the right table**, plus matching rows from the left. Where there is no match, left-side columns are `NULL`.

```sql
SELECT c.name, o.product, o.amount
FROM customers c
RIGHT JOIN orders o ON o.customer_id = c.id;
```

**Result:**

| name  | product  | amount |
|-------|----------|--------|
| Alice | Laptop   | 80000  |
| Alice | Mouse    | 1500   |
| Bob   | Monitor  | 25000  |
| NULL  | Keyboard | 3000   |

- **Order 104** (orphan) appears with `NULL` customer columns

**Equivalent LEFT JOIN rewrite (preferred):**

```sql
-- These two queries return identical results:
SELECT c.name, o.product
FROM customers c
RIGHT JOIN orders o ON o.customer_id = c.id;

-- Cleaner — swap tables and use LEFT JOIN:
SELECT c.name, o.product
FROM orders o
LEFT JOIN customers c ON c.id = o.customer_id;
```

> **Tip:** `RIGHT JOIN` is rarely used in practice. Swap the table order and use `LEFT JOIN` for consistency and readability.

---

## 4. FULL OUTER JOIN

Returns **all rows from BOTH tables**. Non-matching sides are filled with `NULL`.

```sql
SELECT c.name, o.product, o.amount
FROM customers c
FULL OUTER JOIN orders o ON o.customer_id = c.id;
```

**Result:**

| name    | product  | amount |
|---------|----------|--------|
| Alice   | Laptop   | 80000  |
| Alice   | Mouse    | 1500   |
| Bob     | Monitor  | 25000  |
| Charlie | NULL     | NULL   |
| Diana   | NULL     | NULL   |
| NULL    | Keyboard | 3000   |

Every row from both tables appears — matched where possible, `NULL` where not.

**Find unmatched rows on BOTH sides simultaneously:**

```sql
SELECT c.name, o.product
FROM customers c
FULL OUTER JOIN orders o ON o.customer_id = c.id
WHERE c.id IS NULL OR o.id IS NULL;
-- Returns: Charlie, Diana (no orders) + Keyboard order (no customer)
```

**Use cases:** Data reconciliation between two systems, finding orphaned records on either side, database migration auditing.

---

## 5. CROSS JOIN

Returns the **Cartesian product** — every row of the left table paired with every row of the right table. No `ON` clause.

```sql
SELECT c.name, o.product
FROM customers c
CROSS JOIN orders o;
-- 4 customers x 4 orders = 16 rows
```

**Result (partial):**

| name  | product  |
|-------|----------|
| Alice | Laptop   |
| Alice | Mouse    |
| Alice | Monitor  |
| Alice | Keyboard |
| Bob   | Laptop   |
| Bob   | Mouse    |
| ...   | ...      |

**Practical use cases:**

```sql
-- Generate a date x product combination grid
SELECT d.dt, p.product
FROM generate_series(
    '2024-01-01'::date,
    '2024-01-07'::date,
    '1 day'
) AS d(dt)
CROSS JOIN (SELECT DISTINCT product FROM orders) AS p;

-- All possible seat x show combinations
SELECT s.seat_number, sh.show_time
FROM seats s
CROSS JOIN shows sh;
```

> ⚠️ **Warning:** Dangerous on large tables. 1,000 x 1,000 = 1,000,000 rows. 10,000 x 10,000 = 100,000,000 rows. Always verify intent before running.

---

## 6. SELF JOIN

A table joined **to itself**. Requires table aliases to distinguish the two "copies". Used for hierarchical data, comparisons within the same table, or finding related rows.

```sql
CREATE TABLE employees (
    id         SERIAL PRIMARY KEY,
    name       TEXT,
    manager_id INTEGER REFERENCES employees(id)
);

INSERT INTO employees VALUES
  (1, 'CEO',       NULL),
  (2, 'VP Eng',    1),
  (3, 'VP Sales',  1),
  (4, 'Dev Lead',  2),
  (5, 'Sales Rep', 3);

-- Each employee with their manager's name
SELECT e.name AS employee, m.name AS manager
FROM employees e
LEFT JOIN employees m ON m.id = e.manager_id;
```

**Result:**

| employee  | manager  |
|-----------|----------|
| CEO       | NULL     |
| VP Eng    | CEO      |
| VP Sales  | CEO      |
| Dev Lead  | VP Eng   |
| Sales Rep | VP Sales |

**Other self-join use cases:**

```sql
-- Find duplicate email addresses
SELECT a.id, b.id, a.email
FROM users a
JOIN users b ON a.email = b.email AND a.id < b.id;
-- a.id < b.id prevents matching a row with itself and avoids duplicate pairs

-- Find customers in the same city
SELECT a.name AS customer1, b.name AS customer2, a.city
FROM customers a
JOIN customers b ON a.city = b.city AND a.id < b.id;
```

---

## 7. EQUI JOIN

An **Equi Join** is any join that uses the **equality operator (`=`)** in its `ON` clause. It is not a separate SQL keyword — it is a *classification* describing the type of join condition used.

> `INNER JOIN`, `LEFT JOIN`, `RIGHT JOIN`, and `FULL OUTER JOIN` are all equi joins when their `ON` condition uses `=`.

```sql
-- Standard equi join (uses = operator)
SELECT c.name, o.product, o.amount
FROM customers c
JOIN orders o ON o.customer_id = c.id;   -- equality condition
```

### Equi Join with Multiple Equality Conditions

```sql
-- Join on composite key — all conditions use =
SELECT s.shipment_id, w.location
FROM shipments s
JOIN warehouses w
  ON s.warehouse_id = w.id        -- equality
 AND s.region       = w.region;   -- equality  -> still an equi join
```

### Equi Join on Non-PK Columns

```sql
-- Joining on a business key, not the primary key
SELECT e.name, d.dept_name
FROM employees e
JOIN departments d ON e.dept_code = d.dept_code;  -- equi join on dept_code
```

### Equi Join vs Non-Equi Join (Summary)

| Type | Operator Used | Example Condition |
|------|---------------|-------------------|
| **Equi Join** | `=` | `ON a.id = b.id` |
| **Non-Equi Join** | `<`, `>`, `<=`, `>=`, `BETWEEN`, `<>` | `ON a.price BETWEEN b.low AND b.high` |

### How the Planner Handles Equi Joins

Equi joins unlock the most powerful join algorithms:

```
Equi Join (=)   ->  Hash Join    YES  can build hash table on equality key
                ->  Merge Join   YES  can sort and step through both sides
                ->  Nested Loop  YES  can use B-tree index on equality

Non-Equi Join   ->  Nested Loop  YES  only viable algorithm in most cases
                ->  Hash Join    NO   hash tables only work on equality
                ->  Merge Join   NO   merge requires an equality step key
```

```sql
-- EXPLAIN an equi join — observe Hash Join or Merge Join
EXPLAIN ANALYZE
SELECT c.name, o.product
FROM customers c
JOIN orders o ON o.customer_id = c.id;

-- Typical output:
-- Hash Join  (cost=1.09..2.22 rows=3 width=64)
--   Hash Cond: (o.customer_id = c.id)
--   ->  Seq Scan on orders
--   ->  Hash
--         ->  Seq Scan on customers
```

### Equi Join with USING (shorthand)

When the join column name is **identical** in both tables, `USING` is clean shorthand:

```sql
-- Traditional ON equi join:
SELECT * FROM orders o JOIN customers c ON o.customer_id = c.customer_id;

-- USING shorthand:
SELECT * FROM orders JOIN customers USING (customer_id);
-- Bonus: USING deduplicates the column — appears only once in SELECT *

-- Multi-column USING:
SELECT * FROM order_items JOIN products USING (product_id, warehouse_id);
```

### Equi Join Performance Tips

```sql
-- 1. Always index the join column on the inner/smaller table
CREATE INDEX idx_orders_customer_id ON orders(customer_id);

-- 2. Ensure column data types match exactly
--    Mismatched types -> implicit cast -> index bypass -> Seq Scan
--    orders.customer_id (INTEGER) = customers.id (INTEGER)  GOOD
--    orders.customer_id (TEXT)    = customers.id (INTEGER)  BAD: implicit cast

-- 3. Keep statistics fresh on join columns
ANALYZE orders;
ANALYZE customers;

-- 4. Verify the planner chose Hash Join or Merge Join (not Nested Loop
--    with Seq Scan on inner) for large equi joins
EXPLAIN ANALYZE SELECT ...;
```

---

## 8. NON-EQUI JOIN

A **Non-Equi Join** uses any operator **other than `=`** in the join condition: `<`, `>`, `<=`, `>=`, `BETWEEN`, `<>`, or any expression that does not reduce to equality.

### Basic Example — Salary Band Classification

```sql
CREATE TABLE salary_bands (
    band    TEXT,
    min_sal NUMERIC,
    max_sal NUMERIC
);

INSERT INTO salary_bands VALUES
  ('Junior', 0,       500000),
  ('Mid',    500001,  1200000),
  ('Senior', 1200001, 9999999);

CREATE TABLE employees_sal (
    name   TEXT,
    salary NUMERIC
);

INSERT INTO employees_sal VALUES
  ('Ravi',  400000),
  ('Priya', 800000),
  ('Amit',  1500000);

-- Classify each employee into their salary band
SELECT e.name, e.salary, s.band
FROM employees_sal e
JOIN salary_bands s
  ON e.salary BETWEEN s.min_sal AND s.max_sal;
```

**Result:**

| name  | salary  | band   |
|-------|---------|--------|
| Ravi  | 400000  | Junior |
| Priya | 800000  | Mid    |
| Amit  | 1500000 | Senior |

### Date Range Non-Equi Join

```sql
-- Orders placed AFTER the customer's registration date
SELECT c.name, o.product, o.order_date
FROM customers c
JOIN orders o
  ON o.customer_id = c.id
 AND o.order_date  > c.registered_at;   -- non-equi condition
```

### Overlap Detection

```sql
-- Find hotel bookings that overlap with each other
SELECT a.booking_id, b.booking_id, a.room_id
FROM bookings a
JOIN bookings b
  ON a.room_id    =  b.room_id          -- equi (same room)
 AND a.booking_id <  b.booking_id       -- avoid self-match and duplicates
 AND a.check_in   <= b.check_out        -- non-equi: overlap start
 AND a.check_out  >= b.check_in;        -- non-equi: overlap end
```

### Price Proximity Join

```sql
-- Find products priced within 10% of each other
SELECT a.name, b.name, a.price, b.price
FROM products a
JOIN products b
  ON a.id    < b.id                          -- avoid self-match
 AND b.price BETWEEN a.price * 0.9
                 AND a.price * 1.1;          -- non-equi range
```

### Version / Effective Date Lookup (SCD Type 2)

```sql
-- Find the active price for each product on a specific date
CREATE TABLE price_history (
    product_id INT,
    price      NUMERIC,
    valid_from DATE,
    valid_to   DATE
);

SELECT p.name, ph.price
FROM products p
JOIN price_history ph
  ON ph.product_id =  p.id                   -- equi condition
 AND '2024-06-15'  BETWEEN ph.valid_from
                       AND ph.valid_to;       -- non-equi range lookup
```

### Planner Behaviour for Non-Equi Joins

```sql
EXPLAIN ANALYZE
SELECT e.name, s.band
FROM employees_sal e
JOIN salary_bands s ON e.salary BETWEEN s.min_sal AND s.max_sal;

-- Typical output:
-- Nested Loop  (cost=0.00..2.56 rows=3 width=64)
--   ->  Seq Scan on employees_sal
--   ->  Seq Scan on salary_bands
--         Filter: (e.salary BETWEEN min_sal AND max_sal)
```

The planner **almost always chooses Nested Loop** for non-equi joins because Hash Join and Merge Join require an equality key — which non-equi conditions do not provide.

**Performance mitigation for large non-equi joins:**

```sql
-- 1. Pre-filter rows as tightly as possible before the join

-- 2. Use BRIN indexes on large ordered range columns
CREATE INDEX idx_ph_brin ON price_history USING brin(valid_from, valid_to);

-- 3. Use range types + GiST indexes for BETWEEN / overlap queries
CREATE TABLE price_history_range (
    product_id  INT,
    price       NUMERIC,
    valid_range DATERANGE
);
CREATE INDEX idx_ph_gist ON price_history_range USING gist(valid_range);

-- GiST index is used for the range containment operator @>
SELECT p.name, ph.price
FROM products p
JOIN price_history_range ph
  ON ph.product_id = p.id
 AND ph.valid_range @> '2024-06-15'::date;   -- index used
```

### Equi Join vs Non-Equi Join — Full Comparison

| Aspect | Equi Join | Non-Equi Join |
|--------|-----------|---------------|
| Operator | `=` only | `<`, `>`, `<=`, `>=`, `BETWEEN`, `<>` |
| Join algorithms available | Hash Join, Merge Join, Nested Loop | Nested Loop only (in most cases) |
| Index usage | B-tree on equality column | BRIN or GiST for ranges |
| Performance at scale | Excellent (Hash/Merge) | Degrades — O(N x M) Nested Loop |
| Common use cases | FK lookups, ID matching | Ranges, bands, overlaps, SCD Type 2 |
| Planner node type | Hash Join or Merge Join | Nested Loop + Filter |

---

## 9. NATURAL JOIN

Automatically joins on **all columns with the same name** in both tables. No `ON` clause needed.

```sql
-- DANGEROUS: both tables have a column named 'id' — wrong match
SELECT * FROM customers NATURAL JOIN orders;
-- Joins on customers.id = orders.id -> WRONG semantics!

-- Only safe when column names are deliberately designed to match
CREATE TABLE departments (dept_id INT, dept_name TEXT);
CREATE TABLE staff       (staff_id INT, dept_id INT, name TEXT);

SELECT * FROM staff NATURAL JOIN departments;
-- Automatically joins on dept_id
```

> ⚠️ **Avoid `NATURAL JOIN` in production.** Adding a column to either table can silently change or break the join condition — with no error, no warning, and potentially wrong results. Always use explicit `ON` or `USING`.

---

## 10. JOIN with USING

A cleaner syntax for equi joins when the join column has the **same name** in both tables.

```sql
-- Traditional ON syntax:
SELECT * FROM orders o JOIN customers c ON o.customer_id = c.customer_id;

-- USING syntax (cleaner, deduplicates the column in SELECT *):
SELECT * FROM orders JOIN customers USING (customer_id);

-- Multiple columns:
SELECT * FROM order_items JOIN products USING (product_id, warehouse_id);
```

**Key difference between ON and USING:**

```sql
-- ON: both columns appear separately in SELECT *
SELECT * FROM a JOIN b ON a.id = b.id;
-- Columns: a.id, a.name, ..., b.id, b.name ...   (id appears TWICE)

-- USING: join column appears only once in SELECT *
SELECT * FROM a JOIN b USING (id);
-- Columns: id, a.name, ..., b.name ...            (id appears ONCE)
```

---

## 11. Multiple Joins (Chaining)

```sql
CREATE TABLE cities (
    city    TEXT PRIMARY KEY,
    country TEXT
);

INSERT INTO cities VALUES
  ('Mumbai', 'India'),
  ('Delhi',  'India'),
  ('Pune',   'India');

-- Three-table join: orders -> customers -> cities
SELECT o.product, c.name, ci.country
FROM orders o
JOIN customers c ON c.id    = o.customer_id
JOIN cities ci   ON ci.city = c.city;
```

**Result:**

| product | name  | country |
|---------|-------|---------|
| Laptop  | Alice | India   |
| Mouse   | Alice | India   |
| Monitor | Bob   | India   |

**Mix join types freely in the same query:**

```sql
-- Keep all orders even if the customer has no city data
SELECT o.product, c.name, ci.country
FROM orders o
JOIN      customers c ON c.id    = o.customer_id
LEFT JOIN cities ci   ON ci.city = c.city;
-- Customers with NULL city still appear; city columns will be NULL
```

> **Note:** Written join order affects readability but **not correctness** — the planner reorders joins by cost automatically.

---

## 12. JOIN with Aggregation

```sql
-- Total spend and order count per customer
SELECT
    c.name,
    COUNT(o.id)   AS order_count,
    SUM(o.amount) AS total_spent,
    AVG(o.amount) AS avg_order_value
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id
GROUP BY c.id, c.name
ORDER BY total_spent DESC NULLS LAST;
```

**Result:**

| name    | order_count | total_spent | avg_order_value |
|---------|-------------|-------------|-----------------|
| Alice   | 2           | 81500       | 40750           |
| Bob     | 1           | 25000       | 25000           |
| Charlie | 0           | NULL        | NULL            |
| Diana   | 0           | NULL        | NULL            |

> Always use `LEFT JOIN` (not `INNER JOIN`) when you want zero-count rows. `INNER JOIN` silently drops customers with no orders.

**Filtering after aggregation with HAVING:**

```sql
SELECT c.name, SUM(o.amount) AS total_spent
FROM customers c
JOIN orders o ON o.customer_id = c.id
GROUP BY c.id, c.name
HAVING SUM(o.amount) > 50000;
-- Returns: Alice (81500)
```

---

## 13. LATERAL JOIN

`LATERAL` allows a subquery on the right side to **reference columns from the left side** — like a correlated subquery, but able to return multiple rows and columns.

```sql
-- Get the most recent order for each customer
SELECT c.name, latest.product, latest.amount
FROM customers c
LEFT JOIN LATERAL (
    SELECT product, amount
    FROM orders
    WHERE customer_id = c.id        -- references c.id from outer query
    ORDER BY id DESC
    LIMIT 1
) latest ON true;
```

**Result:**

| name    | product | amount |
|---------|---------|--------|
| Alice   | Mouse   | 1500   |
| Bob     | Monitor | 25000  |
| Charlie | NULL    | NULL   |
| Diana   | NULL    | NULL   |

**Top N per group (classic LATERAL use case):**

```sql
-- Top 2 orders per customer by amount
SELECT c.name, top_orders.product, top_orders.amount
FROM customers c
LEFT JOIN LATERAL (
    SELECT product, amount
    FROM orders
    WHERE customer_id = c.id
    ORDER BY amount DESC
    LIMIT 2
) top_orders ON true;
```

**Unnest with row context:**

```sql
-- Explode an array column into individual rows
SELECT c.name, tag
FROM customers c,
LATERAL unnest(c.tags) AS tag;
-- Comma syntax is equivalent to CROSS JOIN LATERAL
```

**LATERAL vs regular subquery:**

| Feature | Subquery | LATERAL |
|---------|----------|---------|
| Can reference outer columns | NO | YES |
| Can return multiple rows | NO (scalar only) | YES |
| Can use LIMIT per outer row | NO | YES |

---

## 14. Anti-Join (NOT EXISTS / LEFT JOIN IS NULL)

Find rows in one table that have **no match** in another.

```sql
-- Method 1: NOT EXISTS (preferred — clearest intent, efficient anti-join plan)
SELECT c.name
FROM customers c
WHERE NOT EXISTS (
    SELECT 1 FROM orders o WHERE o.customer_id = c.id
);

-- Method 2: LEFT JOIN + IS NULL (anti-join pattern)
SELECT c.name
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id
WHERE o.id IS NULL;

-- Method 3: NOT IN (AVOID — NULL-unsafe!)
SELECT name FROM customers
WHERE id NOT IN (SELECT customer_id FROM orders);
-- Returns EMPTY if ANY customer_id in orders is NULL!
```

All three are intended to return: **Charlie, Diana** — but Method 3 returns nothing because Order 104 has `customer_id = NULL`.

**Why NOT IN fails with NULLs:**

```sql
-- NOT IN (1, 2, NULL) expands to:
--   id != 1 AND id != 2 AND id != NULL
-- "id != NULL" evaluates to UNKNOWN (SQL three-valued logic)
-- UNKNOWN in an AND chain = UNKNOWN = row excluded
-- Every single row gets excluded -> empty result
```

**EXPLAIN plan comparison:**

```
-- NOT EXISTS -> Hash Anti Join (most efficient)
Hash Anti Join
  ->  Seq Scan on customers
  ->  Hash
        ->  Seq Scan on orders

-- LEFT JOIN IS NULL -> Hash Left Join + Filter
Hash Left Join
  ->  Seq Scan on customers
  ->  Hash
        ->  Seq Scan on orders
  Filter: (o.id IS NULL)
```

---

## 15. Semi-Join (EXISTS)

Find rows that **have at least one match** — without returning duplicate rows from the left side and without needing `DISTINCT`.

```sql
-- Customers who have placed at least one order
SELECT c.name
FROM customers c
WHERE EXISTS (
    SELECT 1 FROM orders o WHERE o.customer_id = c.id
);
-- Returns: Alice, Bob  (no duplicates even though Alice has 2 orders)

-- Compare: INNER JOIN produces duplicates
SELECT c.name
FROM customers c
JOIN orders o ON o.customer_id = c.id;
-- Returns: Alice, Alice, Bob   <- duplicate Alice!

-- Fix with DISTINCT (less efficient than EXISTS):
SELECT DISTINCT c.name
FROM customers c
JOIN orders o ON o.customer_id = c.id;
```

**The planner converts `EXISTS` to a Hash Semi Join — stops scanning the inner table as soon as the first match is found per outer row:**

```
Hash Semi Join
  ->  Seq Scan on customers
  ->  Hash
        ->  Seq Scan on orders   <- stops at first match per customer
```

---

## Join Type Decision Guide

```
What do you need?                                       Use
────────────────────────────────────────────────────────────────────────────
Only rows matched in BOTH tables                    ->  INNER JOIN
All left rows + matching right (NULL if none)       ->  LEFT JOIN
All right rows + matching left (NULL if none)       ->  RIGHT JOIN
All rows from both, matched where possible          ->  FULL OUTER JOIN
Every combination of rows (Cartesian product)       ->  CROSS JOIN
Table compared/joined to itself                     ->  SELF JOIN
Join condition uses = operator                      ->  EQUI JOIN
Join condition uses <, >, <=, >=, BETWEEN, <>       ->  NON-EQUI JOIN
Left rows with NO match in right table              ->  LEFT JOIN ... WHERE right.id IS NULL
                                                        or NOT EXISTS (anti-join)
Left rows with AT LEAST ONE match in right          ->  EXISTS (semi-join)
Correlated subquery returning multiple rows         ->  LATERAL JOIN
Same-named join column, clean syntax                ->  JOIN ... USING
Avoid fragile implicit column matching              ->  Avoid NATURAL JOIN
```

---

## Common Pitfalls

| Pitfall | Problem | Fix |
|---------|---------|-----|
| `INNER JOIN` when zero-count rows needed | Customers with 0 orders silently disappear | Use `LEFT JOIN` |
| `NOT IN` with NULLs | Returns empty result silently — no error | Use `NOT EXISTS` |
| `NATURAL JOIN` in production | Column rename silently breaks the join | Always use explicit `ON` or `USING` |
| Missing index on join column | Seq Scan on inner side of Nested Loop | Add index on join column |
| Joining before filtering | Joins millions of rows unnecessarily | Push `WHERE` filters before joins |
| `CROSS JOIN` by accident | Missing `ON` clause creates Cartesian product | Always use explicit `JOIN ... ON` |
| Type mismatch on join column | Implicit cast disables index use | Ensure column data types match exactly |
| `DISTINCT` instead of `EXISTS` | Less efficient for "has at least one" queries | Use `EXISTS` semi-join |
| Non-equi join on large tables | O(N x M) Nested Loop — slow at scale | Pre-filter, or use GiST range indexes |
| Wrapping join column in a function | Index bypassed | Use functional indexes instead |
| Forgetting `GROUP BY` after `LEFT JOIN` | Aggregate counts inflated | Include all non-aggregate columns in `GROUP BY` |

---

## Old-Style Implicit Joins (Avoid)

```sql
-- Old style — comma-separated tables (implicit CROSS JOIN filtered by WHERE)
SELECT c.name, o.product
FROM customers c, orders o
WHERE o.customer_id = c.id;

-- Modern style — explicit JOIN ON (always preferred)
SELECT c.name, o.product
FROM customers c
JOIN orders o ON o.customer_id = c.id;
```

The old style:
- Makes `LEFT JOIN` impossible to express cleanly
- Easy to accidentally create a full Cartesian product by forgetting the `WHERE`
- Mixes join conditions and filter conditions in the same `WHERE` clause — hard to read
- Not supported by modern query linters and analyzers

---

## Further Reading

- [PostgreSQL Official Docs — SELECT / JOIN Types](https://www.postgresql.org/docs/current/queries-table-expressions.html)
- [PostgreSQL Official Docs — LATERAL](https://www.postgresql.org/docs/current/queries-table-expressions.html#QUERIES-LATERAL)
- [PostgreSQL Official Docs — Using EXPLAIN](https://www.postgresql.org/docs/current/using-explain.html)
- [Use the Index, Luke — Joins](https://use-the-index-luke.com/sql/join)
- [The Art of PostgreSQL](https://theartofpostgresql.com/)

---

*Generated with love for PostgreSQL engineers.*
