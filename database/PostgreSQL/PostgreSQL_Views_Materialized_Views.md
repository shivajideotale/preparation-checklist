# PostgreSQL — Views & Materialized Views Complete Reference

> A deep-dive guide covering regular views, updatable views, recursive views,
> materialized views, refresh strategies, indexing, security, performance
> patterns, and real-world use cases in PostgreSQL.

---

## Table of Contents

1.  [What Are Views?](#1-what-are-views)
2.  [Creating & Managing Views](#2-creating--managing-views)
3.  [Querying Through Views](#3-querying-through-views)
4.  [Updatable Views](#4-updatable-views)
5.  [WITH CHECK OPTION](#5-with-check-option)
6.  [INSTEAD OF Triggers on Views](#6-instead-of-triggers-on-views)
7.  [Recursive Views](#7-recursive-views)
8.  [Security Views & Row Filtering](#8-security-views--row-filtering)
9.  [What Are Materialized Views?](#9-what-are-materialized-views)
10. [Creating & Managing Materialized Views](#10-creating--managing-materialized-views)
11. [Refreshing Materialized Views](#11-refreshing-materialized-views)
12. [Indexing Materialized Views](#12-indexing-materialized-views)
13. [Incremental Refresh Patterns](#13-incremental-refresh-patterns)
14. [Views vs Materialized Views — Decision Guide](#14-views-vs-materialized-views--decision-guide)
15. [Real-World Patterns](#15-real-world-patterns)
16. [Monitoring & Maintenance](#16-monitoring--maintenance)
17. [Quick Reference Cheat Sheet](#17-quick-reference-cheat-sheet)

---

## Sample Tables Used in All Examples

```sql
-- Customers
CREATE TABLE customers (
    id         SERIAL PRIMARY KEY,
    name       TEXT            NOT NULL,
    email      TEXT            UNIQUE NOT NULL,
    country    TEXT,
    segment    TEXT,           -- gold | silver | bronze
    is_active  BOOLEAN         DEFAULT true,
    created_at DATE            DEFAULT CURRENT_DATE
);

-- Products
CREATE TABLE products (
    id          SERIAL PRIMARY KEY,
    name        TEXT            NOT NULL,
    category    TEXT,
    price       NUMERIC(10,2),
    cost        NUMERIC(10,2),
    is_active   BOOLEAN         DEFAULT true
);

-- Orders
CREATE TABLE orders (
    id          BIGSERIAL PRIMARY KEY,
    customer_id INTEGER         REFERENCES customers(id),
    status      TEXT            DEFAULT 'pending',
    region      TEXT,
    total       NUMERIC(12,2),
    created_at  TIMESTAMPTZ     DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     DEFAULT NOW()
);

-- Order Items
CREATE TABLE order_items (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT          REFERENCES orders(id),
    product_id  INTEGER         REFERENCES products(id),
    qty         INTEGER         NOT NULL CHECK (qty > 0),
    unit_price  NUMERIC(10,2)   NOT NULL
);

-- Employees
CREATE TABLE employees (
    id          SERIAL PRIMARY KEY,
    name        TEXT            NOT NULL,
    email       TEXT            UNIQUE,
    department  TEXT,
    salary      NUMERIC(12,2),
    manager_id  INTEGER         REFERENCES employees(id),
    joined_at   DATE            DEFAULT CURRENT_DATE,
    is_active   BOOLEAN         DEFAULT true
);

-- Seed realistic data
INSERT INTO customers (name, email, country, segment)
SELECT 'Customer '||i, 'c'||i||'@ex.com',
       (ARRAY['IN','US','UK','DE','JP'])[ceil(random()*5)::INT],
       (ARRAY['gold','silver','bronze'])[ceil(random()*3)::INT]
FROM generate_series(1, 100000) i;

INSERT INTO products (name, category, price, cost) VALUES
    ('Laptop Pro',    'Electronics', 85000,  45000),
    ('Wireless Mouse','Electronics',  1500,    400),
    ('Office Chair',  'Furniture',   12000,   5000),
    ('Standing Desk', 'Furniture',   25000,  10000),
    ('Notebook Pack', 'Stationery',    200,     50),
    ('USB-C Hub',     'Electronics',  3500,   1200),
    ('Monitor 27"',   'Electronics', 22000,  11000),
    ('Keyboard Mech', 'Electronics',  6500,   2800);

INSERT INTO orders (customer_id, status, region, total, created_at)
SELECT (random()*99999+1)::INT,
       (ARRAY['pending','processing','shipped','delivered','cancelled'])
           [ceil(random()*5)::INT],
       (ARRAY['North','South','East','West'])[ceil(random()*4)::INT],
       (random()*200000+100)::NUMERIC(12,2),
       NOW() - (random()*730 || ' days')::INTERVAL
FROM generate_series(1, 500000) i;

INSERT INTO order_items (order_id, product_id, qty, unit_price)
SELECT (random()*499999+1)::BIGINT,
       (random()*7+1)::INT,
       (random()*5+1)::INT,
       (random()*90000+100)::NUMERIC(10,2)
FROM generate_series(1, 1000000) i;

INSERT INTO employees (name, email, department, salary, manager_id, joined_at)
VALUES
    (1, 'Alice',   'alice@co.com',   'Engineering', 120000, NULL,       '2018-01-15'),
    (2, 'Bob',     'bob@co.com',     'Engineering',  95000, 1,          '2019-06-01'),
    (3, 'Carol',   'carol@co.com',   'Engineering',  88000, 1,          '2020-03-10'),
    (4, 'Dan',     'dan@co.com',     'Marketing',    75000, NULL,       '2019-11-20'),
    (5, 'Eve',     'eve@co.com',     'Marketing',    62000, 4,          '2021-07-05'),
    (6, 'Frank',   'frank@co.com',   'Sales',        80000, NULL,       '2018-09-30'),
    (7, 'Grace',   'grace@co.com',   'Sales',        68000, 6,          '2022-01-12'),
    (8, 'Heidi',   'heidi@co.com',   'HR',           70000, NULL,       '2020-08-15'),
    (9, 'Ivan',    'ivan@co.com',    'HR',           58000, 8,          '2023-02-28');

-- Fix INSERT for employees (serial id is auto-assigned):
TRUNCATE employees RESTART IDENTITY CASCADE;
INSERT INTO employees (name, email, department, salary, manager_id, joined_at) VALUES
    ('Alice', 'alice@co.com', 'Engineering',120000,NULL,'2018-01-15'),
    ('Bob',   'bob@co.com',   'Engineering', 95000, 1,  '2019-06-01'),
    ('Carol', 'carol@co.com', 'Engineering', 88000, 1,  '2020-03-10'),
    ('Dan',   'dan@co.com',   'Marketing',   75000,NULL,'2019-11-20'),
    ('Eve',   'eve@co.com',   'Marketing',   62000, 4,  '2021-07-05'),
    ('Frank', 'frank@co.com', 'Sales',       80000,NULL,'2018-09-30'),
    ('Grace', 'grace@co.com', 'Sales',       68000, 6,  '2022-01-12'),
    ('Heidi', 'heidi@co.com', 'HR',          70000,NULL,'2020-08-15'),
    ('Ivan',  'ivan@co.com',  'HR',          58000, 8,  '2023-02-28');

ANALYZE customers; ANALYZE products; ANALYZE orders;
ANALYZE order_items; ANALYZE employees;
```

---

## 1. What Are Views?

A **view** is a named, saved SQL query stored in the database catalog. It behaves
like a virtual table — you query it just like a real table, but it contains no
data of its own. Every time you query a view, PostgreSQL runs the underlying
`SELECT` statement dynamically.

```
┌──────────────────────────────────────────────────────────────────┐
│                    VIEW — How It Works                           │
│                                                                  │
│  CREATE VIEW active_orders AS                                    │
│      SELECT id, customer_id, total, status                       │
│      FROM orders WHERE status != 'cancelled';                    │
│                                                                  │
│  Query:  SELECT * FROM active_orders WHERE total > 50000;        │
│                                                                  │
│  PostgreSQL rewrites this to:                                    │
│      SELECT id, customer_id, total, status                       │
│      FROM orders                                                 │
│      WHERE status != 'cancelled'                                 │
│        AND total > 50000;          ← predicate pushed inside     │
│                                                                  │
│  The view definition is INLINED — no separate table scan.        │
└──────────────────────────────────────────────────────────────────┘
```

### Why Use Views?

```
┌─────────────────────────────┬────────────────────────────────────────────┐
│ Reason                      │ Example                                    │
├─────────────────────────────┼────────────────────────────────────────────┤
│ Simplify complex queries    │ Hide multi-table JOINs behind a simple name │
│ Reusability                 │ Define logic once, use everywhere           │
│ Security / column masking   │ Hide sensitive columns (salary, SSN)        │
│ Row-level filtering         │ Each user/tenant sees only their rows       │
│ Logical API layer           │ Decouple apps from schema changes           │
│ Backward compatibility      │ Rename tables without breaking applications │
│ Consistent business logic   │ One canonical definition of "active order"  │
└─────────────────────────────┴────────────────────────────────────────────┘
```

### Views vs Tables vs Materialized Views

```
┌─────────────────┬──────────────┬───────────────┬──────────────────────┐
│ Feature         │ Table        │ View          │ Materialized View     │
├─────────────────┼──────────────┼───────────────┼──────────────────────┤
│ Stores data     │ YES          │ NO            │ YES (snapshot)        │
│ Always current  │ YES          │ YES           │ NO (until refreshed)  │
│ Indexable       │ YES          │ NO            │ YES                   │
│ Query speed     │ Fast         │ Same as query │ Fast (pre-computed)   │
│ Write through   │ YES          │ Limited       │ NO                    │
│ Disk space      │ Full data    │ None          │ Full snapshot         │
│ Refresh needed  │ N/A          │ N/A           │ YES                   │
└─────────────────┴──────────────┴───────────────┴──────────────────────┘
```

---

## 2. Creating & Managing Views

### Basic CREATE VIEW

```sql
-- Syntax
CREATE [OR REPLACE] VIEW view_name
    [ (column_alias_1, column_alias_2, ...) ]
    [ WITH (security_barrier = true) ]
AS
    SELECT ...
    [WITH [CASCADED | LOCAL] CHECK OPTION];
```

### Simple View Examples

```sql
-- View 1: Active customers only
CREATE OR REPLACE VIEW active_customers AS
SELECT id, name, email, country, segment, created_at
FROM customers
WHERE is_active = true;

-- View 2: Orders with customer name joined in
CREATE OR REPLACE VIEW orders_with_customer AS
SELECT
    o.id          AS order_id,
    o.status,
    o.region,
    o.total,
    o.created_at,
    c.id          AS customer_id,
    c.name        AS customer_name,
    c.email       AS customer_email,
    c.segment     AS customer_segment
FROM orders o
JOIN customers c ON c.id = o.customer_id;

-- View 3: Order line detail (four-table join hidden)
CREATE OR REPLACE VIEW order_line_detail AS
SELECT
    o.id            AS order_id,
    o.created_at    AS order_date,
    o.status,
    c.name          AS customer_name,
    c.segment,
    p.name          AS product_name,
    p.category,
    oi.qty,
    oi.unit_price,
    oi.qty * oi.unit_price AS line_total
FROM order_items oi
JOIN orders   o ON o.id  = oi.order_id
JOIN customers c ON c.id = o.customer_id
JOIN products  p ON p.id = oi.product_id;

-- View 4: Named columns (aliases defined in view header)
CREATE OR REPLACE VIEW dept_headcount (department, headcount, avg_salary)
AS
SELECT department, COUNT(*), ROUND(AVG(salary), 2)
FROM employees
WHERE is_active = true
GROUP BY department;
```

### Modify a View

```sql
-- Replace view definition (OR REPLACE)
-- Restriction: column list cannot shrink or change types
CREATE OR REPLACE VIEW active_customers AS
SELECT id, name, email, country, segment, created_at,
       CURRENT_DATE - created_at AS days_since_joined   -- added column
FROM customers
WHERE is_active = true;

-- Add a column: use CREATE OR REPLACE (can only add columns at end)
-- Remove a column or change column type: must DROP then re-CREATE

-- Rename a view
ALTER VIEW active_customers RENAME TO live_customers;

-- Change owner
ALTER VIEW live_customers OWNER TO analytics_user;

-- Move to a different schema
ALTER VIEW live_customers SET SCHEMA analytics;

-- Add a comment
COMMENT ON VIEW live_customers IS
    'Active customers with tenure. Refreshes on every query.';
```

### Drop a View

```sql
-- Drop (fails if other objects depend on it)
DROP VIEW active_customers;

-- Drop if exists
DROP VIEW IF EXISTS active_customers;

-- Drop with CASCADE (also drops dependent views)
DROP VIEW IF EXISTS active_customers CASCADE;

-- Drop multiple
DROP VIEW IF EXISTS active_customers, orders_with_customer, order_line_detail;
```

### List & Inspect Views

```sql
-- List all views in current schema
\dv
\dv *.*         -- all schemas

-- List with details
SELECT
    schemaname,
    viewname,
    viewowner,
    LEFT(definition, 120)       AS definition_preview
FROM pg_views
WHERE schemaname = 'public'
ORDER BY viewname;

-- Full view definition
SELECT definition
FROM pg_views
WHERE viewname = 'orders_with_customer';

-- Or using psql
\d+ active_customers      -- describe the view with definition

-- Columns of a view
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name   = 'orders_with_customer'
ORDER BY ordinal_position;

-- Views that depend on a table (before you ALTER the table)
SELECT DISTINCT v.viewname
FROM pg_views v
WHERE v.definition ILIKE '%orders%'
  AND v.schemaname = 'public';
```

---

## 3. Querying Through Views

Views are fully transparent — use them exactly like tables.

```sql
-- Simple query
SELECT * FROM active_customers LIMIT 10;

-- Filter on view columns
SELECT name, email, segment
FROM active_customers
WHERE country = 'IN'
  AND segment = 'gold'
ORDER BY name;

-- Aggregate through a view
SELECT segment, COUNT(*) AS count, AVG(CURRENT_DATE - created_at) AS avg_days
FROM active_customers
GROUP BY segment
ORDER BY count DESC;

-- Join two views together
SELECT
    owc.customer_name,
    owc.customer_segment,
    COUNT(owc.order_id)                    AS order_count,
    SUM(owc.total)                         AS total_revenue
FROM orders_with_customer owc
JOIN active_customers ac ON ac.id = owc.customer_id
WHERE owc.status = 'delivered'
GROUP BY owc.customer_name, owc.customer_segment
ORDER BY total_revenue DESC
LIMIT 20;

-- JOIN a view to a table
SELECT p.name AS product, SUM(ld.line_total) AS revenue
FROM order_line_detail ld
JOIN products p ON p.id = ld.product_id         -- join table
WHERE ld.status = 'delivered'
  AND ld.order_date > NOW() - INTERVAL '30 days'
GROUP BY p.name
ORDER BY revenue DESC;

-- Subquery using a view
SELECT *
FROM (
    SELECT customer_name, SUM(total) AS total
    FROM orders_with_customer
    WHERE status != 'cancelled'
    GROUP BY customer_name
) ranked
WHERE total > 500000
ORDER BY total DESC;

-- CTE using a view
WITH top_customers AS (
    SELECT customer_id, SUM(total) AS lifetime_value
    FROM orders_with_customer
    WHERE status = 'delivered'
    GROUP BY customer_id
    ORDER BY lifetime_value DESC
    LIMIT 1000
)
SELECT ac.name, ac.segment, tc.lifetime_value
FROM top_customers tc
JOIN active_customers ac ON ac.id = tc.customer_id;
```

### View Query Rewriting (Predicate Pushdown)

```sql
-- The planner inlines the view and pushes predicates inside it
EXPLAIN SELECT * FROM active_customers WHERE country = 'IN';

-- The planner rewrites this to (no view scan overhead):
-- Seq Scan on customers
--   Filter: (is_active = true AND country = 'IN')
-- The predicate country='IN' is pushed INSIDE the view

-- Verify with EXPLAIN:
EXPLAIN (ANALYZE, VERBOSE)
SELECT name FROM active_customers WHERE segment = 'gold';
-- You'll see: "customers" in the scan node, not "active_customers"
-- The view is inlined — zero overhead from using a view
```

---

## 4. Updatable Views

A **simple view** can automatically support `INSERT`, `UPDATE`, and `DELETE`
without any additional code. PostgreSQL rewrites the DML to target the
underlying table directly.

### Rules for Auto-Updatable Views

```
A view is automatically updatable if ALL of the following are true:
  ✅ Based on exactly ONE table or updatable view (no JOINs)
  ✅ No DISTINCT in the SELECT list
  ✅ No aggregate functions (COUNT, SUM, AVG, etc.)
  ✅ No window functions (OVER clause)
  ✅ No GROUP BY or HAVING
  ✅ No LIMIT or OFFSET
  ✅ No set operations (UNION, INTERSECT, EXCEPT)
  ✅ No subqueries in the SELECT list
  ✅ All columns mapped directly to base table columns
```

### Updatable View Examples

```sql
-- This view IS updatable (simple filter on one table)
CREATE OR REPLACE VIEW engineering_employees AS
SELECT id, name, email, salary, manager_id, joined_at
FROM employees
WHERE department = 'Engineering'
  AND is_active = true;

-- Check if a view is updatable
SELECT is_updatable, is_insertable_into, is_trigger_updatable
FROM information_schema.views
WHERE table_schema = 'public'
  AND table_name   = 'engineering_employees';
-- is_updatable: YES

-- INSERT through the view (inserts into employees)
INSERT INTO engineering_employees (name, email, salary, joined_at)
VALUES ('New Dev', 'newdev@co.com', 72000, CURRENT_DATE);
-- Equivalent to:
-- INSERT INTO employees (name, email, salary, department, joined_at)
-- VALUES ('New Dev', 'newdev@co.com', 72000, 'Engineering', CURRENT_DATE);
-- Note: department column NOT in view → gets NULL unless DEFAULT is set

-- UPDATE through the view
UPDATE engineering_employees
SET salary = salary * 1.10
WHERE name = 'Bob';
-- Equivalent to:
-- UPDATE employees SET salary = salary * 1.10
-- WHERE department = 'Engineering' AND is_active = true AND name = 'Bob';

-- DELETE through the view
DELETE FROM engineering_employees WHERE name = 'New Dev';
-- Equivalent to:
-- DELETE FROM employees
-- WHERE department = 'Engineering' AND is_active = true AND name = 'New Dev';

-- RETURNING works too
UPDATE engineering_employees
SET salary = 100000
WHERE id = 1
RETURNING id, name, salary AS new_salary;
```

### Non-Updatable View — Requires INSTEAD OF Trigger

```sql
-- This view is NOT auto-updatable (has JOIN)
CREATE OR REPLACE VIEW orders_with_customer AS
SELECT o.id, o.total, o.status, c.name AS customer_name
FROM orders o
JOIN customers c ON c.id = o.customer_id;

-- Attempting INSERT will fail:
INSERT INTO orders_with_customer (total, status, customer_name)
VALUES (5000, 'pending', 'Alice');
-- ERROR: cannot insert into view "orders_with_customer"
-- DETAIL: Views that perform joins are not automatically updatable.
-- HINT: To enable inserting into the view, provide an INSTEAD OF INSERT trigger.
```

---

## 5. WITH CHECK OPTION

`WITH CHECK OPTION` prevents `INSERT` or `UPDATE` through a view from creating
rows that would then be **invisible** through that same view.

```sql
-- Without CHECK OPTION: you can insert rows that disappear from the view
CREATE OR REPLACE VIEW marketing_employees AS
SELECT id, name, email, salary, department
FROM employees
WHERE department = 'Marketing';

-- This succeeds but the inserted row is NOT visible in the view!
INSERT INTO marketing_employees (name, email, salary, department)
VALUES ('Sneaky', 'sneaky@co.com', 60000, 'Engineering');
-- Inserted into employees with department='Engineering'
-- Not visible in marketing_employees (filter: department='Marketing')
-- Silent data integrity issue!

-- WITH CHECK OPTION prevents this:
CREATE OR REPLACE VIEW marketing_employees AS
SELECT id, name, email, salary, department
FROM employees
WHERE department = 'Marketing'
WITH CHECK OPTION;

-- Now this raises an error:
INSERT INTO marketing_employees (name, email, salary, department)
VALUES ('Sneaky', 'sneaky@co.com', 60000, 'Engineering');
-- ERROR: new row violates check option for view "marketing_employees"
-- DETAIL: Failing row contains (10, Sneaky, sneaky@co.com, Engineering, 60000, ...).

-- Only rows that remain visible in the view are allowed:
INSERT INTO marketing_employees (name, email, salary, department)
VALUES ('Marketer', 'mkt@co.com', 65000, 'Marketing');   -- OK!

-- Also blocks updates that would make a row invisible:
UPDATE marketing_employees
SET department = 'Sales'           -- moves row OUT of view
WHERE name = 'Eve';
-- ERROR: new row violates check option for view "marketing_employees"
```

### LOCAL vs CASCADED Check

```sql
-- View hierarchy example:
-- base_view → derived_view → app_view

CREATE OR REPLACE VIEW high_salary_employees AS
SELECT id, name, salary, department
FROM employees
WHERE salary > 70000;

-- LOCAL: only checks THIS view's WHERE clause (ignores parent views)
CREATE OR REPLACE VIEW senior_engineering AS
SELECT id, name, salary, department
FROM high_salary_employees
WHERE department = 'Engineering'
WITH LOCAL CHECK OPTION;
-- Will enforce: department = 'Engineering'
-- Will NOT enforce: salary > 70000 (parent view's condition)
-- You could insert salary=50000, department='Engineering' — allowed!

-- CASCADED (default): checks ALL ancestor view conditions too
CREATE OR REPLACE VIEW senior_engineering AS
SELECT id, name, salary, department
FROM high_salary_employees
WHERE department = 'Engineering'
WITH CASCADED CHECK OPTION;
-- Will enforce: department = 'Engineering' AND salary > 70000
-- Cannot insert salary=50000 even with correct department
```

---

## 6. INSTEAD OF Triggers on Views

When a view is not auto-updatable (e.g., it has JOINs, aggregates, or subqueries),
you can define `INSTEAD OF` triggers to handle `INSERT`, `UPDATE`, `DELETE`
with custom logic.

### INSTEAD OF INSERT

```sql
-- Enable INSERT on the join view
CREATE OR REPLACE FUNCTION insert_order_with_customer()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_customer_id INTEGER;
BEGIN
    -- Look up customer by name (or create if new)
    SELECT id INTO v_customer_id
    FROM customers
    WHERE name = NEW.customer_name
    LIMIT 1;

    IF NOT FOUND THEN
        INSERT INTO customers (name, email, segment)
        VALUES (NEW.customer_name,
                LOWER(REPLACE(NEW.customer_name,' ','')) || '@new.com',
                'bronze')
        RETURNING id INTO v_customer_id;
    END IF;

    -- Insert the order
    INSERT INTO orders (customer_id, status, total, region)
    VALUES (v_customer_id, NEW.status, NEW.total, 'Unknown');

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_orders_with_customer_insert
    INSTEAD OF INSERT
    ON orders_with_customer
    FOR EACH ROW
    EXECUTE FUNCTION insert_order_with_customer();

-- Now INSERT works on the join view:
INSERT INTO orders_with_customer (customer_name, status, total)
VALUES ('Alice', 'pending', 5000.00);
```

### INSTEAD OF UPDATE & DELETE

```sql
-- Full DML support on a complex view
CREATE OR REPLACE FUNCTION manage_orders_with_customer()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        UPDATE orders
        SET status     = NEW.status,
            total      = NEW.total,
            updated_at = NOW()
        WHERE id = OLD.order_id;
        RETURN NEW;

    ELSIF TG_OP = 'DELETE' THEN
        -- soft delete
        UPDATE orders
        SET status = 'cancelled', updated_at = NOW()
        WHERE id = OLD.order_id;
        RETURN OLD;
    END IF;
END;
$$;

CREATE TRIGGER trg_orders_with_customer_upd_del
    INSTEAD OF UPDATE OR DELETE
    ON orders_with_customer
    FOR EACH ROW
    EXECUTE FUNCTION manage_orders_with_customer();

-- Now UPDATE and DELETE work:
UPDATE orders_with_customer SET status = 'shipped'  WHERE order_id = 42;
DELETE FROM orders_with_customer WHERE order_id = 99;
```

---

## 7. Recursive Views

A **recursive view** uses `WITH RECURSIVE` internally to traverse hierarchical
data. PostgreSQL 9.3+ supports the `CREATE RECURSIVE VIEW` syntax.

### Org Chart Example

```sql
-- Recursive view: full org hierarchy from top to bottom
CREATE OR REPLACE RECURSIVE VIEW employee_hierarchy (
    emp_id, emp_name, department, salary,
    manager_id, manager_name, depth, path
) AS
-- Base case: top-level employees (no manager)
SELECT
    e.id,
    e.name,
    e.department,
    e.salary,
    NULL::INTEGER     AS manager_id,
    NULL::TEXT        AS manager_name,
    0                 AS depth,
    e.name::TEXT      AS path
FROM employees e
WHERE e.manager_id IS NULL

UNION ALL

-- Recursive case: employees with a manager
SELECT
    e.id,
    e.name,
    e.department,
    e.salary,
    e.manager_id,
    h.emp_name        AS manager_name,
    h.depth + 1,
    h.path || ' → ' || e.name
FROM employees e
JOIN employee_hierarchy h ON h.emp_id = e.manager_id;

-- Query the hierarchy
SELECT emp_id, emp_name, department, depth, path
FROM employee_hierarchy
ORDER BY path;
```

**Result:**

| emp_id | emp_name | department | depth | path |
|--------|----------|-----------|-------|------|
| 1 | Alice | Engineering | 0 | Alice |
| 2 | Bob | Engineering | 1 | Alice → Bob |
| 3 | Carol | Engineering | 1 | Alice → Carol |
| 4 | Dan | Marketing | 0 | Dan |
| 5 | Eve | Marketing | 1 | Dan → Eve |

```sql
-- Equivalent using WITH RECURSIVE (non-view syntax — same result)
CREATE OR REPLACE VIEW employee_hierarchy AS
WITH RECURSIVE hier AS (
    SELECT id AS emp_id, name AS emp_name, department,
           salary, manager_id, name AS path, 0 AS depth
    FROM employees WHERE manager_id IS NULL
    UNION ALL
    SELECT e.id, e.name, e.department, e.salary, e.manager_id,
           h.path || ' → ' || e.name, h.depth + 1
    FROM employees e JOIN hier h ON h.emp_id = e.manager_id
)
SELECT * FROM hier;

-- Find all direct and indirect reports under a specific manager
SELECT emp_name, department, depth, path
FROM employee_hierarchy
WHERE path LIKE 'Alice%'   -- everyone in Alice's tree
ORDER BY depth, emp_name;

-- Count reports per manager
SELECT manager_name, COUNT(*) AS total_reports
FROM employee_hierarchy
WHERE manager_name IS NOT NULL
GROUP BY manager_name
ORDER BY total_reports DESC;
```

---

## 8. Security Views & Row Filtering

Views are a powerful tool for **data access control** — expose only what a role
should see, hiding sensitive columns and rows.

### Column Masking View

```sql
-- Full table has sensitive columns: salary, ssn, bank_account
-- Create a view that masks them for general use

CREATE OR REPLACE VIEW employees_public AS
SELECT
    id,
    name,
    email,
    department,
    -- salary masked: show band only
    CASE
        WHEN salary >= 100000 THEN 'Senior Band'
        WHEN salary >=  70000 THEN 'Mid Band'
        ELSE                       'Junior Band'
    END                            AS salary_band,
    joined_at,
    is_active
FROM employees;

-- Grant only the view to reporting role
GRANT SELECT ON employees_public TO reporting_user;
-- reporting_user cannot SELECT from employees directly (no grant)
-- They can only access employees_public
```

### Row Filtering View (Tenant Isolation)

```sql
-- Multi-tenant: each user sees only their own orders
-- Using CURRENT_USER to filter rows dynamically

CREATE OR REPLACE VIEW my_orders AS
SELECT o.id, o.total, o.status, o.created_at
FROM orders o
JOIN customers c ON c.id = o.customer_id
WHERE c.email = CURRENT_USER;    -- dynamic filter per session user

-- Each user connecting with their own DB role sees only their orders
-- CURRENT_USER changes per connection automatically
```

### Security Barrier Views

```sql
-- Problem: without security_barrier, a malicious function in the outer query
-- can be called BEFORE the view's WHERE filter, leaking data via side effects

-- Example attack scenario:
CREATE FUNCTION sneaky_leak(val TEXT) RETURNS BOOLEAN AS $$
BEGIN
    RAISE NOTICE 'Value seen: %', val;  -- leaks ALL emails before filter!
    RETURN TRUE;
END $$ LANGUAGE plpgsql COST 0.0001;  -- very low cost → runs first

-- Without security_barrier:
CREATE VIEW active_only AS
SELECT * FROM customers WHERE is_active = true;

SELECT * FROM active_only WHERE sneaky_leak(email);
-- Planner may run sneaky_leak() on ALL rows (including inactive) first!

-- WITH SECURITY_BARRIER: view filter always runs first
CREATE OR REPLACE VIEW active_only
WITH (security_barrier = true)
AS
SELECT * FROM customers WHERE is_active = true;

-- Now the view's WHERE clause is guaranteed to execute before outer conditions
-- sneaky_leak() only receives rows that pass is_active=true

-- Check security_barrier status
SELECT viewname, definition
FROM pg_views
WHERE viewname = 'active_only';

SELECT relname, reloptions
FROM pg_class
WHERE relname = 'active_only';
```

---

## 9. What Are Materialized Views?

A **materialized view** is like a view, but PostgreSQL **physically stores the
result set** on disk — like a real table. The data is a snapshot from the last
`REFRESH`, not a live query.

```
Regular View:
  Query ──► View definition ──► Run SELECT ──► Live result
  (no data stored)              (on every access)

Materialized View:
  REFRESH ──► Run SELECT ──► Store result on disk ──► Read fast
  (data stored as snapshot)    (only on refresh)
```

### When to Use Materialized Views

```
USE materialized views when:
  ✅ The underlying query is SLOW (aggregations, many JOINs, large tables)
  ✅ The data doesn't need to be real-time (hourly/daily refresh is OK)
  ✅ The same expensive query is run MANY TIMES
  ✅ You need to INDEX the result set
  ✅ Dashboard/reporting queries that aggregate millions of rows
  ✅ Pre-computed summaries (daily sales, monthly cohorts)
  ✅ External data (via FDW) that you want to cache locally

DO NOT use materialized views when:
  ❌ You need real-time / up-to-the-second data
  ❌ The result set is tiny (just use a regular view)
  ❌ The underlying data changes faster than you can refresh
  ❌ Write-through semantics are needed (mat views are read-only)
```

---

## 10. Creating & Managing Materialized Views

### Basic CREATE MATERIALIZED VIEW

```sql
-- Syntax
CREATE MATERIALIZED VIEW view_name
    [ (column_alias_1, ...) ]
    [ TABLESPACE tablespace_name ]
AS
    SELECT ...
[ WITH DATA | WITH NO DATA ];
```

### Examples

```sql
-- Materialized View 1: Department salary summary
CREATE MATERIALIZED VIEW mv_dept_summary AS
SELECT
    department,
    COUNT(*)                             AS headcount,
    ROUND(AVG(salary), 2)               AS avg_salary,
    SUM(salary)                         AS total_payroll,
    MIN(salary)                         AS min_salary,
    MAX(salary)                         AS max_salary,
    MIN(joined_at)                      AS earliest_hire
FROM employees
WHERE is_active = true
GROUP BY department
WITH DATA;           -- populate immediately (default)

-- Query it just like a table
SELECT * FROM mv_dept_summary ORDER BY total_payroll DESC;

-- Materialized View 2: Monthly order summary (expensive aggregation)
CREATE MATERIALIZED VIEW mv_monthly_orders AS
SELECT
    DATE_TRUNC('month', o.created_at)::DATE AS month,
    o.region,
    o.status,
    COUNT(*)                                 AS order_count,
    ROUND(SUM(o.total), 2)                  AS total_revenue,
    ROUND(AVG(o.total), 2)                  AS avg_order_value,
    COUNT(DISTINCT o.customer_id)           AS unique_customers
FROM orders o
GROUP BY 1, 2, 3
ORDER BY 1 DESC, 2, 3
WITH DATA;

-- Materialized View 3: Product revenue summary
CREATE MATERIALIZED VIEW mv_product_revenue AS
SELECT
    p.id                                    AS product_id,
    p.name                                  AS product_name,
    p.category,
    COUNT(DISTINCT oi.order_id)             AS times_ordered,
    SUM(oi.qty)                             AS units_sold,
    ROUND(SUM(oi.qty * oi.unit_price), 2)  AS gross_revenue,
    ROUND(SUM(oi.qty * (oi.unit_price - p.cost)), 2) AS gross_profit,
    ROUND(100.0 * SUM(oi.qty * (oi.unit_price - p.cost))
          / NULLIF(SUM(oi.qty * oi.unit_price), 0), 2) AS margin_pct
FROM order_items oi
JOIN products p ON p.id = oi.product_id
JOIN orders   o ON o.id = oi.order_id
WHERE o.status = 'delivered'
GROUP BY p.id, p.name, p.category
WITH DATA;

-- Materialized View 4: WITHOUT DATA (create structure, populate later)
CREATE MATERIALIZED VIEW mv_customer_ltv AS
SELECT
    c.id            AS customer_id,
    c.name,
    c.segment,
    c.country,
    COUNT(o.id)                             AS total_orders,
    COALESCE(SUM(o.total), 0)              AS lifetime_value,
    MAX(o.created_at)                      AS last_order_at,
    MIN(o.created_at)                      AS first_order_at
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id
                   AND o.status = 'delivered'
GROUP BY c.id, c.name, c.segment, c.country
WITH NO DATA;    -- empty until first REFRESH

-- Populate later:
REFRESH MATERIALIZED VIEW mv_customer_ltv;
```

### Alter & Drop Materialized Views

```sql
-- Rename
ALTER MATERIALIZED VIEW mv_dept_summary RENAME TO mv_department_summary;

-- Change owner
ALTER MATERIALIZED VIEW mv_monthly_orders OWNER TO analytics_user;

-- Move to different schema
ALTER MATERIALIZED VIEW mv_product_revenue SET SCHEMA analytics;

-- Move to different tablespace
ALTER MATERIALIZED VIEW mv_customer_ltv SET TABLESPACE fast_ssd;

-- List all materialized views
\dm             -- in psql
\dm+            -- with size info

SELECT
    schemaname,
    matviewname,
    matviewowner,
    ispopulated,   -- false = WITH NO DATA, not yet refreshed
    pg_size_pretty(pg_total_relation_size(
        schemaname || '.' || matviewname
    ))             AS size
FROM pg_matviews
ORDER BY schemaname, matviewname;

-- Drop
DROP MATERIALIZED VIEW IF EXISTS mv_dept_summary;
DROP MATERIALIZED VIEW IF EXISTS mv_dept_summary CASCADE;
```

---

## 11. Refreshing Materialized Views

### REFRESH MATERIALIZED VIEW

```sql
-- Full refresh — replaces ALL data (blocks reads during refresh)
REFRESH MATERIALIZED VIEW mv_dept_summary;

-- CONCURRENTLY — non-blocking refresh (readers can use view during refresh)
-- Requirement: UNIQUE index must exist on the materialized view
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_monthly_orders;

-- WITH NO DATA — empties the materialized view
REFRESH MATERIALIZED VIEW mv_dept_summary WITH NO DATA;
-- After this, ispopulated=false — cannot query until next REFRESH
```

### Standard vs CONCURRENTLY Refresh

```
REFRESH MATERIALIZED VIEW (standard):
  ─ Takes AccessExclusive lock during refresh
  ─ Blocks ALL reads and writes on the mat view
  ─ Faster (no diff computation)
  ─ Works without unique index
  ─ Suitable for: scheduled off-hours refresh, small mat views

REFRESH MATERIALIZED VIEW CONCURRENTLY:
  ─ Takes only ShareUpdateExclusive lock
  ─ Readers can still query old data during refresh
  ─ Computes diff between old and new data → slower
  ─ Requires UNIQUE INDEX on materialized view
  ─ Suitable for: production with high read traffic, large mat views
```

### Scheduling Refreshes

```sql
-- Option 1: pg_cron extension (runs inside PostgreSQL)
CREATE EXTENSION pg_cron;

-- Refresh every hour
SELECT cron.schedule(
    'refresh-monthly-orders',
    '0 * * * *',                     -- every hour at :00
    'REFRESH MATERIALIZED VIEW CONCURRENTLY mv_monthly_orders'
);

-- Refresh at 2 AM daily
SELECT cron.schedule(
    'refresh-product-revenue',
    '0 2 * * *',                     -- 2 AM every day
    'REFRESH MATERIALIZED VIEW mv_product_revenue'
);

-- Refresh at 1 AM on first day of each month
SELECT cron.schedule(
    'refresh-customer-ltv',
    '0 1 1 * *',                     -- 1 AM, 1st of month
    'REFRESH MATERIALIZED VIEW mv_customer_ltv'
);

-- List scheduled jobs
SELECT jobid, jobname, schedule, command, active
FROM cron.job
ORDER BY jobname;

-- Remove a schedule
SELECT cron.unschedule('refresh-monthly-orders');

-- Option 2: OS cron job (calls psql)
-- Add to crontab:
-- 0 * * * * psql -U postgres -d mydb -c
--   "REFRESH MATERIALIZED VIEW CONCURRENTLY mv_monthly_orders"

-- Option 3: Application-level (trigger refresh after major data load)
-- Refresh as part of ETL pipeline completion
```

### Refresh Inside a Procedure

```sql
-- Refresh all materialized views with logging
CREATE OR REPLACE PROCEDURE refresh_all_matviews()
LANGUAGE plpgsql
AS $$
DECLARE
    v_view    RECORD;
    v_start   TIMESTAMPTZ;
    v_elapsed INTERVAL;
BEGIN
    FOR v_view IN
        SELECT schemaname, matviewname, ispopulated
        FROM pg_matviews
        WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
        ORDER BY matviewname
    LOOP
        v_start := clock_timestamp();

        BEGIN
            EXECUTE FORMAT(
                'REFRESH MATERIALIZED VIEW %I.%I',
                v_view.schemaname,
                v_view.matviewname
            );
            v_elapsed := clock_timestamp() - v_start;
            RAISE NOTICE 'Refreshed % in %', v_view.matviewname, v_elapsed;

        EXCEPTION WHEN OTHERS THEN
            RAISE WARNING 'Failed to refresh %: %', v_view.matviewname, SQLERRM;
        END;
    END LOOP;
END;
$$;

CALL refresh_all_matviews();
```

---

## 12. Indexing Materialized Views

Because materialized views store data physically, they can be **indexed** like
regular tables — a major advantage over regular views.

```sql
-- Basic B-tree index (for equality and range queries)
CREATE INDEX idx_mv_monthly_orders_month
    ON mv_monthly_orders(month DESC);

CREATE INDEX idx_mv_monthly_orders_region
    ON mv_monthly_orders(region);

-- Composite index (for the most common filter combination)
CREATE INDEX idx_mv_monthly_orders_month_region
    ON mv_monthly_orders(month DESC, region);

-- Unique index (REQUIRED for REFRESH CONCURRENTLY)
CREATE UNIQUE INDEX idx_mv_product_revenue_product_id
    ON mv_product_revenue(product_id);

-- After creating unique index, concurrent refresh is available:
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_product_revenue;  -- now works!

-- Index for sorting
CREATE INDEX idx_mv_customer_ltv_value
    ON mv_customer_ltv(lifetime_value DESC);

-- Partial index (index only a subset of rows)
CREATE INDEX idx_mv_customer_ltv_gold
    ON mv_customer_ltv(lifetime_value DESC)
    WHERE segment = 'gold';

-- GIN index for JSONB or full-text columns in materialized view
-- (if the mat view contains such columns)
-- CREATE INDEX idx_mv_gin_tags ON mv_name USING GIN(tags);

-- After refresh, indexes are automatically updated
-- No special handling needed

-- List indexes on a materialized view
SELECT indexname, indexdef
FROM pg_indexes
WHERE tablename = 'mv_monthly_orders';

-- Rebuild indexes on a mat view (after full refresh to reduce bloat)
REINDEX TABLE mv_monthly_orders;
```

---

## 13. Incremental Refresh Patterns

PostgreSQL does not have built-in incremental refresh (unlike some other
databases). Here are patterns to implement it manually.

### Pattern 1: Append-Only (Time-Series)

```sql
-- Track the last refresh timestamp
CREATE TABLE matview_refresh_log (
    view_name   TEXT        PRIMARY KEY,
    last_refreshed_at TIMESTAMPTZ NOT NULL DEFAULT '1970-01-01'
);

INSERT INTO matview_refresh_log (view_name) VALUES ('mv_daily_sales');

-- The incremental materialized view
CREATE MATERIALIZED VIEW mv_daily_sales AS
SELECT
    DATE_TRUNC('day', created_at)::DATE AS day,
    region,
    SUM(total)                          AS daily_revenue,
    COUNT(*)                            AS order_count
FROM orders
WHERE created_at > '1970-01-01'   -- placeholder, overridden in procedure
GROUP BY 1, 2
WITH NO DATA;

-- Incremental load procedure
CREATE OR REPLACE PROCEDURE incremental_refresh_daily_sales()
LANGUAGE plpgsql
AS $$
DECLARE
    v_last_ts  TIMESTAMPTZ;
    v_now      TIMESTAMPTZ := NOW();
BEGIN
    -- Get last refresh timestamp
    SELECT last_refreshed_at INTO v_last_ts
    FROM matview_refresh_log
    WHERE view_name = 'mv_daily_sales';

    -- Insert only NEW data since last refresh
    INSERT INTO mv_daily_sales (day, region, daily_revenue, order_count)
    SELECT
        DATE_TRUNC('day', created_at)::DATE,
        region,
        SUM(total),
        COUNT(*)
    FROM orders
    WHERE created_at > v_last_ts
      AND created_at <= v_now
      AND status = 'delivered'
    GROUP BY 1, 2
    ON CONFLICT (day, region) DO UPDATE
        SET daily_revenue = mv_daily_sales.daily_revenue + EXCLUDED.daily_revenue,
            order_count   = mv_daily_sales.order_count   + EXCLUDED.order_count;

    -- Update last refresh timestamp
    UPDATE matview_refresh_log
    SET last_refreshed_at = v_now
    WHERE view_name = 'mv_daily_sales';

    RAISE NOTICE 'Incremental refresh complete: % to %', v_last_ts, v_now;
END;
$$;

-- Requires unique index for ON CONFLICT
CREATE UNIQUE INDEX idx_mv_daily_sales_day_region
    ON mv_daily_sales(day, region);

-- Run incremental refresh every 15 minutes
SELECT cron.schedule(
    'incremental-daily-sales',
    '*/15 * * * *',
    'CALL incremental_refresh_daily_sales()'
);
```

### Pattern 2: Delta Table Approach

```sql
-- Use a delta/change table to capture only changed rows
CREATE TABLE orders_delta (
    order_id    BIGINT,
    changed_at  TIMESTAMPTZ DEFAULT NOW(),
    operation   TEXT    -- INSERT / UPDATE / DELETE
);

-- Trigger populates delta table on every change
CREATE OR REPLACE FUNCTION capture_order_delta()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO orders_delta (order_id, operation) VALUES (NEW.id, 'INSERT');
    ELSIF TG_OP = 'UPDATE' THEN
        INSERT INTO orders_delta (order_id, operation) VALUES (NEW.id, 'UPDATE');
    ELSIF TG_OP = 'DELETE' THEN
        INSERT INTO orders_delta (order_id, operation) VALUES (OLD.id, 'DELETE');
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE TRIGGER trg_orders_delta
    AFTER INSERT OR UPDATE OR DELETE ON orders
    FOR EACH ROW EXECUTE FUNCTION capture_order_delta();

-- Merge delta changes into materialized summary
CREATE OR REPLACE PROCEDURE apply_order_delta()
LANGUAGE plpgsql AS $$
DECLARE
    v_batch_id BIGINT;
BEGIN
    -- Lock the delta table briefly
    LOCK TABLE orders_delta IN SHARE ROW EXCLUSIVE MODE;

    -- Process changed orders
    INSERT INTO mv_monthly_orders
        (month, region, status, order_count, total_revenue, avg_order_value, unique_customers)
    SELECT
        DATE_TRUNC('month', o.created_at)::DATE,
        o.region, o.status,
        COUNT(*), SUM(o.total), AVG(o.total), COUNT(DISTINCT o.customer_id)
    FROM orders o
    WHERE o.id IN (SELECT DISTINCT order_id FROM orders_delta)
    GROUP BY 1, 2, 3
    ON CONFLICT (month, region, status) DO UPDATE
        SET order_count       = EXCLUDED.order_count,
            total_revenue     = EXCLUDED.total_revenue,
            avg_order_value   = EXCLUDED.avg_order_value,
            unique_customers  = EXCLUDED.unique_customers;

    -- Clear processed delta
    DELETE FROM orders_delta;

    RAISE NOTICE 'Delta applied and cleared.';
END;
$$;
```

### Pattern 3: Swap Table (Zero-Downtime Full Refresh)

```sql
-- Build fresh copy in background, then swap atomically
CREATE OR REPLACE PROCEDURE refresh_with_swap(p_view TEXT)
LANGUAGE plpgsql AS $$
DECLARE
    v_tmp TEXT := p_view || '_new';
    v_old TEXT := p_view || '_old';
BEGIN
    -- Step 1: Build fresh data into a new table
    EXECUTE FORMAT('DROP TABLE IF EXISTS %I', v_tmp);
    EXECUTE FORMAT('CREATE TABLE %I AS SELECT * FROM %I_base_query', v_tmp, p_view);

    -- Step 2: Swap atomically inside a transaction
    BEGIN
        EXECUTE FORMAT('ALTER TABLE %I RENAME TO %I', p_view, v_old);
        EXECUTE FORMAT('ALTER TABLE %I RENAME TO %I', v_tmp,  p_view);
        EXECUTE FORMAT('DROP TABLE %I', v_old);
    END;

    RAISE NOTICE 'Swap complete for %', p_view;
END;
$$;
```

---

## 14. Views vs Materialized Views — Decision Guide

```
                    ┌─────────────────────────────┐
                    │  Does query return live data? │
                    └──────────────┬──────────────┘
                                   │
              ┌─────── YES ────────┤──── NO ──────┐
              │                   │               │
              ▼                   ▼               ▼
    Is query fast           Use regular       Use Materialized
    (< 1 second)?              VIEW               VIEW
              │
     ┌── YES ─┴── NO ──┐
     │                  │
     ▼                  ▼
 Regular VIEW      Is data needed
                   in real-time?
                        │
              ┌── YES ──┴── NO ──┐
              │                   │
              ▼                   ▼
      Optimize the         Materialized VIEW
      base query           with scheduled refresh
      (indexes, etc.)
```

### Detailed Comparison

```
┌─────────────────────────┬──────────────────────┬──────────────────────────┐
│ Criteria                │ Regular View          │ Materialized View        │
├─────────────────────────┼──────────────────────┼──────────────────────────┤
│ Data freshness          │ Always live           │ Snapshot (stale)         │
│ Query performance       │ Same as base query    │ Fast (pre-computed)      │
│ Disk storage            │ None                  │ Full result set          │
│ Can be indexed          │ No                    │ Yes                      │
│ Supports DML            │ Limited               │ No (read-only)           │
│ Supports REFRESH        │ N/A                   │ Yes                      │
│ Concurrent reads during │ Always                │ Yes (CONCURRENTLY)       │
│ refresh                 │                       │ No  (standard refresh)   │
│ When to prefer          │ Simple queries,       │ Complex aggregations,    │
│                         │ real-time data,       │ reporting, dashboards,   │
│                         │ small result sets,    │ slow base queries,       │
│                         │ security filtering    │ external/FDW data        │
└─────────────────────────┴──────────────────────┴──────────────────────────┘
```

---

## 15. Real-World Patterns

### Pattern 1: API Layer View (Hide Schema Complexity)

```sql
-- Application queries this clean, stable view
-- Backend schema can change without breaking the API contract

CREATE OR REPLACE VIEW api_orders AS
SELECT
    o.id                                    AS order_id,
    o.status,
    o.region,
    o.total                                 AS amount,
    o.created_at                            AS placed_at,
    c.id                                    AS customer_id,
    c.name                                  AS customer_name,
    c.email                                 AS customer_email,
    c.segment                               AS customer_tier,
    -- computed columns
    EXTRACT(EPOCH FROM (NOW() - o.created_at))/86400
                                            AS age_days,
    CASE
        WHEN o.status = 'delivered'         THEN true
        ELSE                                     false
    END                                     AS is_complete
FROM orders o
JOIN customers c ON c.id = o.customer_id
WHERE o.status != 'cancelled';

GRANT SELECT ON api_orders TO api_role;
```

### Pattern 2: Dashboard Materialized View

```sql
-- Pre-compute heavy dashboard query — refreshed every 15 minutes
CREATE MATERIALIZED VIEW mv_dashboard_summary AS
WITH order_stats AS (
    SELECT
        DATE_TRUNC('day', created_at)::DATE AS day,
        COUNT(*)                             AS orders,
        SUM(total)                           AS revenue,
        AVG(total)                           AS avg_order,
        COUNT(DISTINCT customer_id)          AS unique_buyers
    FROM orders
    WHERE status IN ('shipped','delivered')
      AND created_at >= CURRENT_DATE - 90
    GROUP BY 1
),
top_products AS (
    SELECT
        p.category,
        SUM(oi.qty * oi.unit_price)          AS category_revenue
    FROM order_items oi
    JOIN products p ON p.id = oi.product_id
    JOIN orders   o ON o.id = oi.order_id
    WHERE o.status = 'delivered'
      AND o.created_at >= CURRENT_DATE - 30
    GROUP BY p.category
)
SELECT
    os.day,
    os.orders,
    os.revenue,
    os.avg_order,
    os.unique_buyers,
    LAG(os.revenue) OVER (ORDER BY os.day) AS prev_day_revenue,
    ROUND(100.0 * (os.revenue -
        LAG(os.revenue) OVER (ORDER BY os.day))
        / NULLIF(LAG(os.revenue) OVER (ORDER BY os.day), 0), 2) AS revenue_growth_pct
FROM order_stats os
ORDER BY os.day DESC
WITH DATA;

CREATE UNIQUE INDEX idx_mv_dashboard_day ON mv_dashboard_summary(day);

-- Fast dashboard query (milliseconds instead of seconds)
SELECT * FROM mv_dashboard_summary
WHERE day >= CURRENT_DATE - 30
ORDER BY day DESC;
```

### Pattern 3: Backward-Compatible Rename

```sql
-- Rename a table without breaking existing queries
ALTER TABLE customers RENAME TO customers_v2;

-- Create a view with the old name
CREATE VIEW customers AS SELECT * FROM customers_v2;

-- Old queries still work:
SELECT * FROM customers WHERE country = 'IN';   -- uses the view
-- New queries can use customers_v2 directly
```

### Pattern 4: Permission-Based Row Filtering

```sql
-- Different views expose different rows based on role
CREATE OR REPLACE VIEW orders_view AS
SELECT o.*
FROM orders o
JOIN customers c ON c.id = o.customer_id
WHERE
    CASE
        -- Admins see everything
        WHEN pg_has_role(CURRENT_USER, 'admin_role', 'member') THEN true
        -- Regional managers see their region
        WHEN pg_has_role(CURRENT_USER, 'north_manager', 'member')
            THEN o.region = 'North'
        WHEN pg_has_role(CURRENT_USER, 'south_manager', 'member')
            THEN o.region = 'South'
        -- Regular users see only their own orders
        ELSE c.email = CURRENT_USER
    END;

GRANT SELECT ON orders_view TO PUBLIC;
-- Revoke direct table access
REVOKE SELECT ON orders FROM PUBLIC;
```

### Pattern 5: Chained Materialized Views

```sql
-- Tier 1: raw aggregation (refresh hourly)
CREATE MATERIALIZED VIEW mv_hourly_sales AS
SELECT
    DATE_TRUNC('hour', created_at)  AS hour,
    region,
    SUM(total)                       AS revenue,
    COUNT(*)                         AS orders
FROM orders
WHERE status = 'delivered'
GROUP BY 1, 2
WITH DATA;

CREATE UNIQUE INDEX ON mv_hourly_sales(hour, region);

-- Tier 2: daily rollup built on top of hourly (refresh daily)
CREATE MATERIALIZED VIEW mv_daily_sales AS
SELECT
    hour::DATE                       AS day,
    region,
    SUM(revenue)                     AS revenue,
    SUM(orders)                      AS orders
FROM mv_hourly_sales               -- built on top of another mat view!
GROUP BY 1, 2
WITH DATA;

CREATE UNIQUE INDEX ON mv_daily_sales(day, region);

-- Tier 3: monthly rollup
CREATE MATERIALIZED VIEW mv_monthly_sales AS
SELECT
    DATE_TRUNC('month', day)::DATE   AS month,
    region,
    SUM(revenue)                     AS revenue,
    SUM(orders)                      AS orders
FROM mv_daily_sales
GROUP BY 1, 2
WITH DATA;

CREATE UNIQUE INDEX ON mv_monthly_sales(month, region);

-- Refresh in dependency order (bottom-up)
CREATE OR REPLACE PROCEDURE refresh_sales_chain()
LANGUAGE plpgsql AS $$
BEGIN
    RAISE NOTICE 'Refreshing hourly...';
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_hourly_sales;

    RAISE NOTICE 'Refreshing daily...';
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_daily_sales;

    RAISE NOTICE 'Refreshing monthly...';
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_monthly_sales;

    RAISE NOTICE 'Chain refresh complete.';
END;
$$;
```

### Pattern 6: Pivot Report via Materialized View

```sql
-- Pre-compute a crosstab pivot for monthly/regional reporting
CREATE MATERIALIZED VIEW mv_region_monthly_pivot AS
SELECT
    DATE_TRUNC('month', created_at)::DATE         AS month,
    SUM(total) FILTER (WHERE region = 'North')    AS north_revenue,
    SUM(total) FILTER (WHERE region = 'South')    AS south_revenue,
    SUM(total) FILTER (WHERE region = 'East')     AS east_revenue,
    SUM(total) FILTER (WHERE region = 'West')     AS west_revenue,
    SUM(total)                                     AS total_revenue,
    COUNT(*) FILTER (WHERE region = 'North')      AS north_orders,
    COUNT(*) FILTER (WHERE region = 'South')      AS south_orders,
    COUNT(*) FILTER (WHERE region = 'East')        AS east_orders,
    COUNT(*) FILTER (WHERE region = 'West')        AS west_orders
FROM orders
WHERE status = 'delivered'
GROUP BY 1
ORDER BY 1 DESC
WITH DATA;

CREATE UNIQUE INDEX ON mv_region_monthly_pivot(month);

SELECT * FROM mv_region_monthly_pivot ORDER BY month DESC LIMIT 12;
```

---

## 16. Monitoring & Maintenance

### View Usage Monitoring

```sql
-- Views are transparent (queries on views show as queries on base tables)
-- There is no separate "view scan" counter in pg_stat_user_views

-- Find queries that use specific views via pg_stat_statements
SELECT
    LEFT(query, 100)     AS query_snippet,
    calls,
    ROUND(mean_exec_time::NUMERIC, 2) AS avg_ms
FROM pg_stat_statements
WHERE query ILIKE '%active_customers%'
   OR query ILIKE '%orders_with_customer%'
ORDER BY mean_exec_time DESC;

-- Views currently being queried
SELECT pid, usename, datname,
       LEFT(query, 100) AS query
FROM pg_stat_activity
WHERE query ILIKE '%mv_monthly_orders%'
  AND state = 'active';
```

### Materialized View Size & Freshness

```sql
-- All materialized views with size and last refresh info
SELECT
    schemaname                              AS schema,
    matviewname                             AS name,
    matviewowner                            AS owner,
    ispopulated,
    pg_size_pretty(
        pg_total_relation_size(schemaname || '.' || matviewname)
    )                                       AS total_size,
    pg_size_pretty(
        pg_relation_size(schemaname || '.' || matviewname)
    )                                       AS data_size,
    pg_size_pretty(
        pg_indexes_size(schemaname || '.' || matviewname)
    )                                       AS index_size
FROM pg_matviews
ORDER BY pg_total_relation_size(schemaname || '.' || matviewname) DESC;

-- Check if materialized views are populated
SELECT matviewname, ispopulated
FROM pg_matviews
WHERE ispopulated = false;    -- these need REFRESH before they can be queried

-- Row count and last statistics update (proxy for last refresh)
SELECT
    relname                                 AS matview_name,
    n_live_tup                              AS row_count,
    last_analyze,
    last_autoanalyze,
    pg_size_pretty(pg_relation_size(relid)) AS size
FROM pg_stat_user_tables
WHERE relname IN (
    SELECT matviewname FROM pg_matviews
)
ORDER BY n_live_tup DESC;
```

### Detect Stale Materialized Views

```sql
-- Track last refresh time using a custom log table
CREATE TABLE IF NOT EXISTS matview_refresh_history (
    id           BIGSERIAL PRIMARY KEY,
    view_name    TEXT          NOT NULL,
    refreshed_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    duration_ms  NUMERIC,
    row_count    BIGINT
);

-- Enhanced refresh procedure with history tracking
CREATE OR REPLACE PROCEDURE tracked_refresh(p_view TEXT)
LANGUAGE plpgsql AS $$
DECLARE
    v_start    TIMESTAMPTZ := clock_timestamp();
    v_rows     BIGINT;
    v_duration NUMERIC;
BEGIN
    EXECUTE FORMAT('REFRESH MATERIALIZED VIEW CONCURRENTLY %I', p_view);
    EXECUTE FORMAT('SELECT COUNT(*) FROM %I', p_view) INTO v_rows;
    v_duration := EXTRACT(EPOCH FROM (clock_timestamp() - v_start)) * 1000;

    INSERT INTO matview_refresh_history (view_name, refreshed_at, duration_ms, row_count)
    VALUES (p_view, NOW(), v_duration, v_rows);

    RAISE NOTICE 'Refreshed % in %ms (%s rows)', p_view, ROUND(v_duration,1), v_rows;
END;
$$;

-- Find stale materialized views (not refreshed in > 1 hour)
SELECT
    v_name,
    MAX(refreshed_at)                    AS last_refresh,
    NOW() - MAX(refreshed_at)            AS age,
    MAX(row_count)                       AS last_row_count
FROM matview_refresh_history
GROUP BY v_name
HAVING NOW() - MAX(refreshed_at) > INTERVAL '1 hour'
ORDER BY age DESC;
```

### Dependency Tracking

```sql
-- Find all views that depend on a specific table
-- (important before ALTER TABLE or DROP TABLE)
WITH RECURSIVE view_deps AS (
    -- Direct dependencies
    SELECT DISTINCT
        dependent_ns.nspname || '.' || dependent_view.relname AS view_name,
        dependent_view.relkind,
        1 AS depth
    FROM pg_depend dep
    JOIN pg_rewrite rw ON rw.oid = dep.objid
    JOIN pg_class  dependent_view ON dependent_view.oid = rw.ev_class
    JOIN pg_namespace dependent_ns ON dependent_ns.oid = dependent_view.relnamespace
    JOIN pg_class  source ON source.oid = dep.refobjid
    JOIN pg_namespace source_ns ON source_ns.oid = source.relnamespace
    WHERE source.relname = 'orders'                -- table name to check
      AND source_ns.nspname = 'public'
      AND dependent_view.relname <> 'orders'
      AND dependent_view.relkind IN ('v', 'm')

    UNION ALL

    -- Transitive dependencies (views of views)
    SELECT DISTINCT
        vd2.view_name,
        vd2.relkind,
        vd.depth + 1
    FROM view_deps vd
    JOIN (
        SELECT dependent_ns.nspname || '.' || dependent_view.relname AS view_name,
               source.relname AS source_name, dependent_view.relkind
        FROM pg_depend dep
        JOIN pg_rewrite rw ON rw.oid = dep.objid
        JOIN pg_class dependent_view ON dependent_view.oid = rw.ev_class
        JOIN pg_namespace dependent_ns ON dependent_ns.oid = dependent_view.relnamespace
        JOIN pg_class source ON source.oid = dep.refobjid
        WHERE dependent_view.relkind IN ('v','m')
    ) vd2 ON vd2.source_name = SPLIT_PART(vd.view_name, '.', 2)
)
SELECT DISTINCT
    view_name,
    CASE relkind WHEN 'v' THEN 'View' WHEN 'm' THEN 'Materialized View' END AS type,
    depth
FROM view_deps
ORDER BY depth, view_name;
```

---

## 17. Quick Reference Cheat Sheet

```
╔══════════════════════════════╦═══════════════════════════════════════════════╗
║ TOPIC                        ║ KEY SYNTAX                                    ║
╠══════════════════════════════╬═══════════════════════════════════════════════╣
║ Create View                  ║ CREATE [OR REPLACE] VIEW name AS SELECT ...;  ║
║ Replace View                 ║ CREATE OR REPLACE VIEW name AS SELECT ...;    ║
║ Rename View                  ║ ALTER VIEW name RENAME TO new_name;           ║
║ Drop View                    ║ DROP VIEW IF EXISTS name [CASCADE];           ║
║ List Views                   ║ \dv  or  SELECT * FROM pg_views;             ║
║ View Definition              ║ \d+ viewname  or  SELECT definition           ║
║                              ║   FROM pg_views WHERE viewname='name';        ║
╠══════════════════════════════╬═══════════════════════════════════════════════╣
║ Updatable View rules         ║ Single table, no JOIN, no GROUP BY,           ║
║                              ║ no DISTINCT, no aggregates, no LIMIT          ║
║ Check updatable status       ║ SELECT is_updatable FROM                      ║
║                              ║   information_schema.views WHERE ...;         ║
╠══════════════════════════════╬═══════════════════════════════════════════════╣
║ WITH CHECK OPTION            ║ ... WHERE cond WITH CHECK OPTION;             ║
║ LOCAL check                  ║ WITH LOCAL CHECK OPTION;                      ║
║ CASCADED check (default)     ║ WITH CASCADED CHECK OPTION;                   ║
╠══════════════════════════════╬═══════════════════════════════════════════════╣
║ Security Barrier             ║ CREATE VIEW v WITH (security_barrier=true)    ║
║                              ║   AS SELECT ...;                              ║
╠══════════════════════════════╬═══════════════════════════════════════════════╣
║ INSTEAD OF Trigger           ║ CREATE TRIGGER t INSTEAD OF INSERT            ║
║ (for non-updatable views)    ║   ON view FOR EACH ROW EXECUTE FUNCTION f();  ║
╠══════════════════════════════╬═══════════════════════════════════════════════╣
║ Recursive View               ║ CREATE RECURSIVE VIEW name (cols) AS          ║
║                              ║   base UNION ALL recursive_part;              ║
╠══════════════════════════════╬═══════════════════════════════════════════════╣
║ Create Materialized View     ║ CREATE MATERIALIZED VIEW name AS SELECT ...   ║
║                              ║   WITH DATA;                                  ║
║ Create empty Mat View        ║ ... WITH NO DATA;                             ║
║ List Mat Views               ║ \dm  or  SELECT * FROM pg_matviews;          ║
║ Drop Mat View                ║ DROP MATERIALIZED VIEW IF EXISTS name;        ║
╠══════════════════════════════╬═══════════════════════════════════════════════╣
║ Refresh (blocking)           ║ REFRESH MATERIALIZED VIEW name;               ║
║ Refresh (non-blocking)       ║ REFRESH MATERIALIZED VIEW CONCURRENTLY name;  ║
║                              ║ → Requires UNIQUE index on the mat view       ║
║ Empty mat view               ║ REFRESH MATERIALIZED VIEW name WITH NO DATA;  ║
╠══════════════════════════════╬═══════════════════════════════════════════════╣
║ Index on Mat View            ║ CREATE INDEX idx ON mat_view(col);            ║
║ Unique index (for CONCURR.)  ║ CREATE UNIQUE INDEX idx ON mat_view(col);     ║
╠══════════════════════════════╬═══════════════════════════════════════════════╣
║ Schedule Refresh             ║ SELECT cron.schedule('name','0 * * * *',      ║
║ (pg_cron)                    ║   'REFRESH MATERIALIZED VIEW CONCURRENTLY v');║
╠══════════════════════════════╬═══════════════════════════════════════════════╣
║ View vs Mat View             ║ View:     live data, no storage, no index     ║
║                              ║ Mat View: snapshot, fast read, indexable,     ║
║                              ║           needs periodic REFRESH              ║
╠══════════════════════════════╬═══════════════════════════════════════════════╣
║ Key Decision Rule            ║ Real-time data needed?  → Regular View        ║
║                              ║ Slow aggregation query? → Materialized View   ║
║                              ║ Need index on result?   → Materialized View   ║
║                              ║ Need to write through?  → Regular View        ║
║                              ║                         → (+ INSTEAD OF trig) ║
╚══════════════════════════════╩═══════════════════════════════════════════════╝
```

---

## Further Reading

- [PostgreSQL Docs — CREATE VIEW](https://www.postgresql.org/docs/current/sql-createview.html)
- [PostgreSQL Docs — CREATE MATERIALIZED VIEW](https://www.postgresql.org/docs/current/sql-creatematerializedview.html)
- [PostgreSQL Docs — REFRESH MATERIALIZED VIEW](https://www.postgresql.org/docs/current/sql-refreshmaterializedview.html)
- [PostgreSQL Docs — Updatable Views](https://www.postgresql.org/docs/current/sql-createview.html#SQL-CREATEVIEW-UPDATABLE-VIEWS)
- [PostgreSQL Docs — Rules & Views](https://www.postgresql.org/docs/current/rules-views.html)
- [PostgreSQL Docs — Recursive Queries](https://www.postgresql.org/docs/current/queries-with.html#QUERIES-WITH-RECURSIVE)
- [PostgreSQL Docs — Row Security Policies](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)
- [pg_cron — Job Scheduling](https://github.com/citusdata/pg_cron)

---

*Generated with love for PostgreSQL engineers.*
