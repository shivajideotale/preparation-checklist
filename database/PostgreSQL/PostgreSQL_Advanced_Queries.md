# PostgreSQL — Advanced Queries Complete Reference

> A deep-dive guide covering CTEs, recursive queries, window functions, lateral joins, subqueries, set operations, advanced aggregations, pivot, and real-world query patterns in PostgreSQL.

---

## Table of Contents

1. [Common Table Expressions (CTE)](#1-common-table-expressions-cte)
2. [Recursive CTEs](#2-recursive-ctes)
3. [Window Functions — Advanced](#3-window-functions--advanced)
4. [LATERAL Joins](#4-lateral-joins)
5. [Subqueries — All Types](#5-subqueries--all-types)
6. [Set Operations](#6-set-operations)
7. [Advanced Aggregations](#7-advanced-aggregations)
8. [GROUPING SETS, ROLLUP, CUBE](#8-grouping-sets-rollup-cube)
9. [Pivot / Crosstab](#9-pivot--crosstab)
10. [Advanced Filtering](#10-advanced-filtering)
11. [JSON Advanced Queries](#11-json-advanced-queries)
12. [Array Advanced Queries](#12-array-advanced-queries)
13. [Full Text Search Advanced](#13-full-text-search-advanced)
14. [Date & Time Advanced](#14-date--time-advanced)
15. [Analytical / Reporting Patterns](#15-analytical--reporting-patterns)
16. [Data Modification with RETURNING](#16-data-modification-with-returning)
17. [Upsert Patterns](#17-upsert-patterns)
18. [Advanced JOIN Patterns](#18-advanced-join-patterns)
19. [Performance Patterns](#19-performance-patterns)
20. [Quick Reference Cheat Sheet](#20-quick-reference-cheat-sheet)

---

## Sample Tables Used in All Examples

```sql
CREATE TABLE employees (
    id          SERIAL PRIMARY KEY,
    name        TEXT NOT NULL,
    email       TEXT,
    salary      NUMERIC,
    department  TEXT,
    manager_id  INTEGER REFERENCES employees(id),
    joined_at   DATE,
    is_active   BOOLEAN DEFAULT true,
    tags        TEXT[],
    metadata    JSONB
);

CREATE TABLE orders (
    id          BIGSERIAL PRIMARY KEY,
    customer_id INTEGER,
    product     TEXT,
    amount      NUMERIC,
    status      TEXT DEFAULT 'pending',
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE products (
    id       SERIAL PRIMARY KEY,
    name     TEXT,
    category TEXT,
    price    NUMERIC,
    stock    INTEGER
);

CREATE TABLE sales (
    id          SERIAL PRIMARY KEY,
    product_id  INTEGER REFERENCES products(id),
    employee_id INTEGER REFERENCES employees(id),
    region      TEXT,
    amount      NUMERIC,
    sale_date   DATE
);

-- Seed data
INSERT INTO employees VALUES
  (1, 'Alice Johnson',  'alice@co.com',  95000, 'Engineering', NULL,  '2019-03-15', true,  ARRAY['python','sql'],     '{"level":"staff","score":95}'),
  (2, 'Bob Smith',      'bob@co.com',    72000, 'Engineering', 1,     '2020-07-22', true,  ARRAY['java','aws'],       '{"level":"senior","score":82}'),
  (3, 'Charlie Brown',  'charlie@co.com',88000, 'Engineering', 1,     '2018-01-10', true,  ARRAY['python','devops'],  '{"level":"senior","score":88}'),
  (4, 'Diana Prince',   'diana@co.com',  65000, 'Marketing',   NULL,  '2021-11-05', true,  ARRAY['excel','crm'],      '{"level":"mid","score":74}'),
  (5, 'Eve Wilson',     'eve@co.com',    58000, 'Marketing',   4,     '2022-06-30', true,  ARRAY['seo','content'],    '{"level":"junior","score":65}'),
  (6, 'Frank Miller',   'frank@co.com',  91000, 'Sales',       NULL,  '2017-09-01', true,  ARRAY['crm','excel'],      '{"level":"senior","score":90}'),
  (7, 'Grace Lee',      'grace@co.com',  48000, 'Sales',       6,     '2023-02-14', false, ARRAY['crm'],              '{"level":"junior","score":60}'),
  (8, 'Henry Ford',     NULL,            110000,'Engineering', 1,     '2016-05-20', true,  ARRAY['sql','architecture'],'{"level":"principal","score":98}');

INSERT INTO products VALUES
  (1, 'Laptop',   'Electronics', 80000, 50),
  (2, 'Mouse',    'Accessories', 1500,  200),
  (3, 'Monitor',  'Electronics', 25000, 30),
  (4, 'Keyboard', 'Accessories', 3000,  150),
  (5, 'Webcam',   'Electronics', 8000,  75);

INSERT INTO sales (product_id, employee_id, region, amount, sale_date)
SELECT
    (random()*4+1)::INT,
    (random()*7+1)::INT,
    (ARRAY['North','South','East','West'])[ceil(random()*4)::INT],
    (random()*100000)::NUMERIC(10,2),
    CURRENT_DATE - (random()*365*2)::INT
FROM generate_series(1, 500);

INSERT INTO orders (customer_id, product, amount, status, created_at)
SELECT
    (random()*8+1)::INT,
    (ARRAY['Laptop','Mouse','Monitor','Keyboard'])[ceil(random()*4)::INT],
    (random()*100000)::NUMERIC(10,2),
    (ARRAY['pending','processing','shipped','delivered','cancelled'])[ceil(random()*5)::INT],
    NOW() - (random()*365*2 || ' days')::INTERVAL
FROM generate_series(1, 200);
```

---

## 1. Common Table Expressions (CTE)

A **CTE** (WITH clause) creates a named temporary result set scoped to a single query. It improves readability, allows reuse, and enables recursive queries.

### Basic CTE

```sql
-- Simple CTE: name a subquery for reuse
WITH high_earners AS (
    SELECT id, name, salary, department
    FROM employees
    WHERE salary > 80000
)
SELECT *
FROM high_earners
WHERE department = 'Engineering';
```

### Multiple CTEs (chained)

```sql
WITH
-- Step 1: filter active employees
active_employees AS (
    SELECT id, name, salary, department
    FROM employees
    WHERE is_active = true
),
-- Step 2: compute per-department averages
dept_averages AS (
    SELECT department,
           AVG(salary)   AS avg_salary,
           COUNT(*)      AS headcount
    FROM active_employees
    GROUP BY department
),
-- Step 3: label employees vs their dept average
labeled AS (
    SELECT
        ae.name,
        ae.salary,
        ae.department,
        da.avg_salary,
        CASE
            WHEN ae.salary > da.avg_salary THEN 'Above Average'
            WHEN ae.salary < da.avg_salary THEN 'Below Average'
            ELSE 'At Average'
        END AS performance_label
    FROM active_employees ae
    JOIN dept_averages da USING (department)
)
SELECT *
FROM labeled
ORDER BY department, salary DESC;
```

**Result:**

| name | salary | department | avg_salary | performance_label |
|------|--------|------------|------------|-------------------|
| Henry Ford | 110000 | Engineering | 91250 | Above Average |
| Alice Johnson | 95000 | Engineering | 91250 | Above Average |
| Charlie Brown | 88000 | Engineering | 91250 | Below Average |
| Bob Smith | 72000 | Engineering | 91250 | Below Average |
| Frank Miller | 91000 | Sales | 91000 | At Average |
| Diana Prince | 65000 | Marketing | 61500 | Above Average |
| Eve Wilson | 58000 | Marketing | 61500 | Below Average |

### CTE vs Subquery

```sql
-- Same logic as subquery (harder to read):
SELECT ae.name, ae.salary, da.avg_salary
FROM (SELECT id, name, salary, department FROM employees WHERE is_active=true) ae
JOIN (SELECT department, AVG(salary) AS avg_salary FROM employees
      WHERE is_active=true GROUP BY department) da
  USING (department);

-- CTE version (much cleaner and easier to maintain):
WITH active AS (
    SELECT id, name, salary, department
    FROM employees WHERE is_active = true
),
dept_avg AS (
    SELECT department, AVG(salary) AS avg_salary FROM active GROUP BY department
)
SELECT a.name, a.salary, d.avg_salary
FROM active a JOIN dept_avg d USING (department);
```

### Materialized vs Inlined CTEs (PostgreSQL 12+)

```sql
-- Default in PG12+: CTE is inlined (treated as subquery, planner can optimize)
WITH dept_avg AS (
    SELECT department, AVG(salary) AS avg FROM employees GROUP BY department
)
SELECT * FROM dept_avg WHERE department = 'Engineering';
-- PG12+: planner may push the WHERE into the CTE

-- Force materialization (compute once, reuse result):
WITH dept_avg AS MATERIALIZED (
    SELECT department, AVG(salary) AS avg FROM employees GROUP BY department
)
SELECT * FROM dept_avg WHERE department = 'Engineering';
-- Always computes the full CTE first, then filters

-- Force inlining (PG12 behavior, ignore even complex CTEs):
WITH dept_avg AS NOT MATERIALIZED (
    SELECT department, AVG(salary) AS avg FROM employees GROUP BY department
)
SELECT * FROM dept_avg WHERE department = 'Engineering';
```

### Writable CTEs (DML inside WITH)

```sql
-- Move employees to archive table in one atomic query
WITH moved AS (
    DELETE FROM employees
    WHERE is_active = false
    RETURNING *
)
INSERT INTO employees_archive
SELECT *, NOW() AS archived_at FROM moved;

-- Update and immediately report what changed
WITH updated AS (
    UPDATE employees
    SET salary = salary * 1.10
    WHERE department = 'Engineering'
      AND salary < 90000
    RETURNING id, name, salary AS new_salary,
              salary / 1.10 AS old_salary
)
SELECT
    name,
    ROUND(old_salary) AS before,
    ROUND(new_salary) AS after,
    ROUND(new_salary - old_salary) AS raise
FROM updated
ORDER BY raise DESC;
```

---

## 2. Recursive CTEs

A **recursive CTE** references itself, enabling traversal of hierarchical and graph data.

### Syntax

```sql
WITH RECURSIVE cte_name AS (
    -- Base case: non-recursive query (anchor)
    SELECT ...
    UNION ALL
    -- Recursive case: references cte_name
    SELECT ... FROM cte_name JOIN ...
)
SELECT * FROM cte_name;
```

### Employee Hierarchy (Tree Traversal)

```sql
-- Build the full reporting hierarchy under Alice (id=1)
WITH RECURSIVE org_chart AS (
    -- Base: start with Alice (the root)
    SELECT
        id,
        name,
        manager_id,
        department,
        salary,
        0           AS depth,
        name        AS path,
        ARRAY[id]   AS visited
    FROM employees
    WHERE id = 1

    UNION ALL

    -- Recursive: find direct reports of current level
    SELECT
        e.id,
        e.name,
        e.manager_id,
        e.department,
        e.salary,
        oc.depth + 1,
        oc.path || ' → ' || e.name,
        oc.visited || e.id
    FROM employees e
    JOIN org_chart oc ON e.manager_id = oc.id
    WHERE NOT e.id = ANY(oc.visited)   -- prevent infinite loops
)
SELECT
    depth,
    REPEAT('  ', depth) || name        AS org_name,
    department,
    salary,
    path
FROM org_chart
ORDER BY path;
```

**Result:**

| depth | org_name | department | salary | path |
|-------|----------|------------|--------|------|
| 0 | Alice Johnson | Engineering | 95000 | Alice Johnson |
| 1 | &nbsp;&nbsp;Bob Smith | Engineering | 72000 | Alice Johnson → Bob Smith |
| 1 | &nbsp;&nbsp;Charlie Brown | Engineering | 88000 | Alice Johnson → Charlie Brown |
| 1 | &nbsp;&nbsp;Henry Ford | Engineering | 110000 | Alice Johnson → Henry Ford |

### Find All Ancestors (Bottom-Up)

```sql
-- Who are all the managers above Bob (id=2)?
WITH RECURSIVE ancestors AS (
    -- Base: start with Bob
    SELECT id, name, manager_id, 0 AS level
    FROM employees
    WHERE id = 2

    UNION ALL

    -- Recursive: find each person's manager
    SELECT e.id, e.name, e.manager_id, a.level + 1
    FROM employees e
    JOIN ancestors a ON e.id = a.manager_id
)
SELECT level, name FROM ancestors
ORDER BY level;
```

### Generate a Number Series

```sql
-- Generate numbers 1 to 10 using recursion
WITH RECURSIVE nums AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM nums WHERE n < 10
)
SELECT n FROM nums;
```

### Generate a Date Series

```sql
-- Every date in Q1 2024
WITH RECURSIVE dates AS (
    SELECT '2024-01-01'::DATE AS dt
    UNION ALL
    SELECT dt + 1 FROM dates WHERE dt < '2024-03-31'
)
SELECT dt,
       TO_CHAR(dt, 'Day')  AS day_name,
       EXTRACT(WEEK FROM dt) AS week_num
FROM dates;
```

### Graph Traversal (Find All Paths)

```sql
CREATE TABLE connections (from_id INT, to_id INT, weight NUMERIC);
INSERT INTO connections VALUES
    (1,2,5),(1,3,3),(2,4,2),(3,4,7),(3,5,1),(4,6,4),(5,6,6);

-- Find all paths from node 1 to node 6 with total cost
WITH RECURSIVE paths AS (
    SELECT
        from_id,
        to_id,
        weight        AS total_cost,
        ARRAY[from_id, to_id] AS path
    FROM connections
    WHERE from_id = 1

    UNION ALL

    SELECT
        p.from_id,
        c.to_id,
        p.total_cost + c.weight,
        p.path || c.to_id
    FROM paths p
    JOIN connections c ON c.from_id = p.to_id
    WHERE NOT c.to_id = ANY(p.path)    -- no cycles
      AND p.to_id != 6                 -- stop when reaching destination
)
SELECT path, total_cost
FROM paths
WHERE to_id = 6
ORDER BY total_cost;
```

---

## 3. Window Functions — Advanced

### Running Totals & Moving Averages

```sql
SELECT
    s.sale_date,
    s.amount,
    p.name                                                AS product,

    -- Running total (cumulative sum)
    SUM(s.amount) OVER (ORDER BY s.sale_date)            AS running_total,

    -- Running total per product
    SUM(s.amount) OVER (
        PARTITION BY s.product_id ORDER BY s.sale_date
    )                                                    AS product_running,

    -- 7-day moving average
    ROUND(AVG(s.amount) OVER (
        ORDER BY s.sale_date
        ROWS BETWEEN 6 PRECEDING AND CURRENT ROW
    ), 2)                                                AS ma_7day,

    -- 30-day moving average
    ROUND(AVG(s.amount) OVER (
        ORDER BY s.sale_date
        ROWS BETWEEN 29 PRECEDING AND CURRENT ROW
    ), 2)                                                AS ma_30day,

    -- Running max
    MAX(s.amount) OVER (ORDER BY s.sale_date)            AS running_max,

    -- Expanding window: average from start to current
    ROUND(AVG(s.amount) OVER (
        ORDER BY s.sale_date
        ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
    ), 2)                                                AS expanding_avg

FROM sales s
JOIN products p ON p.id = s.product_id
ORDER BY s.sale_date;
```

### LAG & LEAD — Period-over-Period Comparison

```sql
WITH monthly_sales AS (
    SELECT
        DATE_TRUNC('month', sale_date) AS month,
        SUM(amount) AS total
    FROM sales
    GROUP BY 1
)
SELECT
    month,
    total,
    LAG(total, 1)  OVER (ORDER BY month)  AS prev_month,
    LEAD(total, 1) OVER (ORDER BY month)  AS next_month,

    -- Month-over-month change
    total - LAG(total, 1) OVER (ORDER BY month)  AS mom_change,

    -- Month-over-month % change
    ROUND(100.0 * (total - LAG(total, 1) OVER (ORDER BY month))
          / NULLIF(LAG(total, 1) OVER (ORDER BY month), 0), 1) AS mom_pct,

    -- Same month last year (12 months lag)
    LAG(total, 12) OVER (ORDER BY month)  AS same_month_last_year,

    -- YoY % change
    ROUND(100.0 * (total - LAG(total, 12) OVER (ORDER BY month))
          / NULLIF(LAG(total, 12) OVER (ORDER BY month), 0), 1) AS yoy_pct

FROM monthly_sales
ORDER BY month;
```

### RANK Functions — Top N Per Group

```sql
-- Top 2 earners in each department
SELECT *
FROM (
    SELECT
        name, department, salary,
        RANK() OVER (PARTITION BY department ORDER BY salary DESC) AS dept_rank,
        DENSE_RANK() OVER (PARTITION BY department ORDER BY salary DESC) AS dense,
        ROW_NUMBER() OVER (PARTITION BY department ORDER BY salary DESC) AS rn
    FROM employees
    WHERE is_active = true
) ranked
WHERE rn <= 2;
```

### PERCENTILE Functions

```sql
SELECT
    department,
    -- Continuous percentile (interpolated)
    PERCENTILE_CONT(0.25) WITHIN GROUP (ORDER BY salary) AS p25,
    PERCENTILE_CONT(0.50) WITHIN GROUP (ORDER BY salary) AS median,
    PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY salary) AS p75,
    PERCENTILE_CONT(0.90) WITHIN GROUP (ORDER BY salary) AS p90,
    PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY salary) AS p95,

    -- Discrete percentile (actual row value, not interpolated)
    PERCENTILE_DISC(0.50) WITHIN GROUP (ORDER BY salary) AS median_disc,

    -- Mode: most frequent value
    MODE() WITHIN GROUP (ORDER BY department) AS most_common_dept

FROM employees
GROUP BY department;
```

### FIRST_VALUE / LAST_VALUE with Proper Frame

```sql
SELECT
    name,
    department,
    salary,
    joined_at,

    -- First employee to join in each department
    FIRST_VALUE(name) OVER (
        PARTITION BY department ORDER BY joined_at ASC
        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
    ) AS first_joiner,

    -- Most recent joiner
    LAST_VALUE(name) OVER (
        PARTITION BY department ORDER BY joined_at ASC
        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
    ) AS latest_joiner,

    -- Second highest salary in department
    NTH_VALUE(salary, 2) OVER (
        PARTITION BY department ORDER BY salary DESC
        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
    ) AS second_highest_sal

FROM employees
ORDER BY department, salary DESC;
```

---

## 4. LATERAL Joins

A `LATERAL` subquery can reference columns from tables to its **left** in the FROM clause — like a correlated subquery that returns multiple rows.

### Top N Per Group

```sql
-- Top 2 orders (by amount) for each customer
SELECT
    e.name      AS customer,
    top2.product,
    top2.amount,
    top2.rank
FROM employees e
LEFT JOIN LATERAL (
    SELECT
        product,
        amount,
        ROW_NUMBER() OVER (ORDER BY amount DESC) AS rank
    FROM orders
    WHERE customer_id = e.id
    ORDER BY amount DESC
    LIMIT 2
) top2 ON true
ORDER BY e.name, top2.amount DESC;
```

### Latest Record Per Entity

```sql
-- Each employee's most recent sale
SELECT
    e.name,
    e.department,
    latest_sale.product_id,
    latest_sale.amount,
    latest_sale.sale_date
FROM employees e
LEFT JOIN LATERAL (
    SELECT product_id, amount, sale_date
    FROM sales
    WHERE employee_id = e.id
    ORDER BY sale_date DESC
    LIMIT 1
) latest_sale ON true;
```

### LATERAL with Aggregation

```sql
-- Stats about each employee's sales (without needing a separate join)
SELECT
    e.name,
    e.department,
    stats.total_sales,
    stats.avg_sale,
    stats.max_sale,
    stats.sale_count
FROM employees e
LEFT JOIN LATERAL (
    SELECT
        SUM(amount)   AS total_sales,
        AVG(amount)   AS avg_sale,
        MAX(amount)   AS max_sale,
        COUNT(*)      AS sale_count
    FROM sales
    WHERE employee_id = e.id
) stats ON true
ORDER BY stats.total_sales DESC NULLS LAST;
```

### LATERAL UNNEST — Explode Arrays

```sql
-- One row per tag per employee
SELECT e.name, e.department, tag
FROM employees e,
LATERAL UNNEST(e.tags) AS tag
ORDER BY e.name, tag;

-- Count tag frequency across all employees
SELECT tag, COUNT(*) AS employee_count
FROM employees,
LATERAL UNNEST(tags) AS tag
GROUP BY tag
ORDER BY employee_count DESC;
```

### LATERAL for Bucketed / Binned Values

```sql
-- Generate salary histogram buckets
SELECT
    bucket,
    COUNT(*) AS employees
FROM employees,
LATERAL (
    SELECT
        CASE
            WHEN salary < 60000  THEN '< 60k'
            WHEN salary < 80000  THEN '60k-80k'
            WHEN salary < 100000 THEN '80k-100k'
            ELSE '100k+'
        END AS bucket
) b
GROUP BY bucket
ORDER BY MIN(salary) OVER (PARTITION BY bucket);
```

---

## 5. Subqueries — All Types

### Scalar Subquery

```sql
-- Returns a single value used inline
SELECT
    name,
    salary,
    (SELECT AVG(salary) FROM employees)                AS company_avg,
    salary - (SELECT AVG(salary) FROM employees)       AS vs_avg,
    (SELECT MAX(salary) FROM employees WHERE department = e.department)
                                                       AS dept_max
FROM employees e
ORDER BY salary DESC;
```

### Correlated Subquery

```sql
-- Subquery references the outer query's row (runs once per outer row)
SELECT
    name,
    salary,
    department,
    (
        SELECT COUNT(*)
        FROM employees e2
        WHERE e2.department = e.department
          AND e2.salary > e.salary
    ) AS people_earning_more_in_dept
FROM employees e
ORDER BY department, salary DESC;
```

### EXISTS Subquery (Semi-Join)

```sql
-- Employees who have made at least one sale
SELECT name, department
FROM employees e
WHERE EXISTS (
    SELECT 1 FROM sales s WHERE s.employee_id = e.id
);

-- Employees with a sale over 50000
SELECT name, department
FROM employees e
WHERE EXISTS (
    SELECT 1 FROM sales s
    WHERE s.employee_id = e.id
      AND s.amount > 50000
);
```

### NOT EXISTS Subquery (Anti-Join)

```sql
-- Employees who have NEVER made a sale
SELECT name, department
FROM employees e
WHERE NOT EXISTS (
    SELECT 1 FROM sales s WHERE s.employee_id = e.id
);

-- Products with no sales this year
SELECT p.name
FROM products p
WHERE NOT EXISTS (
    SELECT 1 FROM sales s
    WHERE s.product_id = p.id
      AND s.sale_date >= DATE_TRUNC('year', CURRENT_DATE)
);
```

### IN / NOT IN Subquery

```sql
-- Employees in departments that have average salary > 80000
SELECT name, department, salary
FROM employees
WHERE department IN (
    SELECT department
    FROM employees
    GROUP BY department
    HAVING AVG(salary) > 80000
);

-- Orders for products in Electronics category
SELECT o.*
FROM orders o
WHERE o.product IN (
    SELECT name FROM products WHERE category = 'Electronics'
);
```

### ANY / ALL Subquery

```sql
-- Salary greater than ANY Engineering employee's salary
SELECT name, salary
FROM employees
WHERE salary > ANY (
    SELECT salary FROM employees WHERE department = 'Engineering'
);
-- Returns employees earning more than the MINIMUM Engineering salary

-- Salary greater than ALL Marketing employees' salaries
SELECT name, salary
FROM employees
WHERE salary > ALL (
    SELECT salary FROM employees WHERE department = 'Marketing'
);
-- Returns employees earning more than the MAXIMUM Marketing salary
```

### Subquery in FROM (Derived Table)

```sql
-- Derived table: subquery used as a table
SELECT dept_stats.department, dept_stats.avg_salary, e.name
FROM (
    SELECT
        department,
        AVG(salary) AS avg_salary,
        MAX(salary) AS max_salary
    FROM employees
    GROUP BY department
) dept_stats
JOIN employees e ON e.department = dept_stats.department
WHERE e.salary = dept_stats.max_salary;
```

---

## 6. Set Operations

### UNION — Combine rows, remove duplicates

```sql
-- All departments from employees and orders (deduplicated)
SELECT DISTINCT department AS source_value FROM employees
UNION
SELECT DISTINCT product FROM orders;
```

### UNION ALL — Combine rows, keep duplicates (faster)

```sql
-- Full audit log combining inserts and updates
SELECT 'employee' AS source, id, name, 'insert' AS action, NOW() AS ts
FROM employees WHERE joined_at > CURRENT_DATE - 30
UNION ALL
SELECT 'order', id::TEXT, product, 'insert', created_at
FROM orders WHERE created_at > NOW() - INTERVAL '30 days'
ORDER BY ts DESC;
```

### INTERSECT — Only rows present in BOTH

```sql
-- Products that appear in BOTH orders AND have active stock
SELECT name FROM products WHERE stock > 0
INTERSECT
SELECT product FROM orders WHERE status = 'delivered';
```

### EXCEPT — Rows in first but NOT in second

```sql
-- Products that were never ordered
SELECT name FROM products
EXCEPT
SELECT DISTINCT product FROM orders;

-- Employees in Engineering but NOT in the sales table
SELECT name FROM employees WHERE department = 'Engineering'
EXCEPT
SELECT DISTINCT e.name
FROM employees e
JOIN sales s ON s.employee_id = e.id;
```

---

## 7. Advanced Aggregations

### FILTER Clause — Conditional Aggregation

```sql
-- Count/sum with inline conditions (cleaner than CASE WHEN)
SELECT
    department,
    COUNT(*)                                         AS total,
    COUNT(*) FILTER (WHERE is_active = true)         AS active_count,
    COUNT(*) FILTER (WHERE is_active = false)        AS inactive_count,
    AVG(salary) FILTER (WHERE salary > 70000)        AS avg_high_sal,
    SUM(salary)  FILTER (WHERE is_active = true)     AS active_payroll,
    MAX(salary)  FILTER (WHERE joined_at > '2022-01-01') AS max_recent_hire,
    COUNT(*) FILTER (WHERE 'python' = ANY(tags))     AS python_devs
FROM employees
GROUP BY department;
```

### DISTINCT Inside Aggregates

```sql
SELECT
    COUNT(DISTINCT department)                       AS unique_depts,
    COUNT(DISTINCT manager_id)                       AS unique_managers,
    STRING_AGG(DISTINCT department, ', ' ORDER BY department)
                                                     AS dept_list,
    ARRAY_AGG(DISTINCT department ORDER BY department) AS dept_array
FROM employees;
```

### Ordered-Set Aggregates

```sql
SELECT
    department,

    -- Percentiles
    PERCENTILE_CONT(0.5)  WITHIN GROUP (ORDER BY salary) AS median_salary,
    PERCENTILE_CONT(0.9)  WITHIN GROUP (ORDER BY salary) AS p90_salary,
    PERCENTILE_DISC(0.5)  WITHIN GROUP (ORDER BY salary) AS median_disc,

    -- Mode (most frequent value)
    MODE() WITHIN GROUP (ORDER BY EXTRACT(YEAR FROM joined_at)::INT) AS most_common_join_year

FROM employees
GROUP BY department;
```

### Hypothetical-Set Aggregates

```sql
-- "What rank would a 100000 salary get in each department?"
SELECT
    department,
    RANK()        HYPOTHETICAL WITHIN GROUP (ORDER BY salary DESC) AS hyp_rank,
    DENSE_RANK()  HYPOTHETICAL WITHIN GROUP (ORDER BY salary DESC) AS hyp_dense_rank,
    PERCENT_RANK() HYPOTHETICAL WITHIN GROUP (ORDER BY salary DESC) AS hyp_pct_rank,
    CUME_DIST()   HYPOTHETICAL WITHIN GROUP (ORDER BY salary DESC) AS hyp_cume_dist
FROM employees, (VALUES (100000)) AS v(salary)
GROUP BY department;
```

### Multi-Level Aggregation

```sql
-- Sales stats at multiple levels in one query using window functions
SELECT
    region,
    product_id,
    SUM(amount)                                         AS product_region_total,
    SUM(SUM(amount)) OVER (PARTITION BY region)         AS region_total,
    SUM(SUM(amount)) OVER ()                            AS grand_total,
    ROUND(100.0 * SUM(amount)
          / SUM(SUM(amount)) OVER (PARTITION BY region), 1) AS pct_of_region,
    ROUND(100.0 * SUM(amount)
          / SUM(SUM(amount)) OVER (), 1)                AS pct_of_total
FROM sales
GROUP BY region, product_id
ORDER BY region, product_region_total DESC;
```

---

## 8. GROUPING SETS, ROLLUP, CUBE

### GROUPING SETS — Custom Multi-Level Grouping

```sql
-- Multiple GROUP BY combinations in one pass
SELECT
    department,
    EXTRACT(YEAR FROM joined_at) AS join_year,
    COUNT(*)                     AS headcount,
    SUM(salary)                  AS total_salary
FROM employees
GROUP BY GROUPING SETS (
    (department),            -- subtotal by department
    (join_year),             -- subtotal by year
    (department, join_year), -- detail: dept + year
    ()                       -- grand total
)
ORDER BY department NULLS LAST, join_year NULLS LAST;
```

### ROLLUP — Hierarchical Subtotals

```sql
-- Sales breakdown with subtotals at each level
SELECT
    COALESCE(region, 'ALL REGIONS')      AS region,
    COALESCE(p.category, 'ALL CATS')     AS category,
    COALESCE(p.name, 'ALL PRODUCTS')     AS product,
    SUM(s.amount)                        AS total_sales,
    GROUPING(region)                     AS is_region_subtotal,
    GROUPING(p.category)                 AS is_cat_subtotal,
    GROUPING(p.name)                     AS is_prod_subtotal
FROM sales s
JOIN products p ON p.id = s.product_id
GROUP BY ROLLUP(region, p.category, p.name)
ORDER BY region NULLS LAST, category NULLS LAST, product NULLS LAST;
```

**Result structure:**

```
North | Electronics | Laptop   | 4500000  -- detail
North | Electronics | Monitor  | 2100000  -- detail
North | Electronics | NULL     | 6600000  -- Electronics subtotal
North | Accessories | Keyboard | 980000   -- detail
North | Accessories | NULL     | 980000   -- Accessories subtotal
North | NULL        | NULL     | 7580000  -- North subtotal
South | ...         | ...      | ...
NULL  | NULL        | NULL     | GRAND TOTAL
```

### CUBE — All Combinations

```sql
-- Every possible combination of dimensions
SELECT
    COALESCE(region, 'ALL')        AS region,
    COALESCE(p.category, 'ALL')    AS category,
    COUNT(*)                       AS sales_count,
    ROUND(SUM(s.amount))           AS total
FROM sales s
JOIN products p ON p.id = s.product_id
GROUP BY CUBE(region, p.category)
ORDER BY region NULLS LAST, category NULLS LAST;
```

---

## 9. Pivot / Crosstab

### Manual Pivot with CASE WHEN

```sql
-- Sales by region (rows) × product category (columns)
SELECT
    region,
    ROUND(SUM(CASE WHEN p.category = 'Electronics' THEN s.amount END)) AS electronics,
    ROUND(SUM(CASE WHEN p.category = 'Accessories'  THEN s.amount END)) AS accessories,
    ROUND(SUM(s.amount))                                                  AS total
FROM sales s
JOIN products p ON p.id = s.product_id
GROUP BY region
ORDER BY region;
```

**Result:**

| region | electronics | accessories | total |
|--------|-------------|-------------|-------|
| East | 1250000 | 340000 | 1590000 |
| North | 2100000 | 480000 | 2580000 |
| South | 980000 | 220000 | 1200000 |
| West | 1680000 | 390000 | 2070000 |

### Pivot by Quarter

```sql
SELECT
    product_id,
    ROUND(SUM(CASE WHEN EXTRACT(QUARTER FROM sale_date) = 1 THEN amount END)) AS q1,
    ROUND(SUM(CASE WHEN EXTRACT(QUARTER FROM sale_date) = 2 THEN amount END)) AS q2,
    ROUND(SUM(CASE WHEN EXTRACT(QUARTER FROM sale_date) = 3 THEN amount END)) AS q3,
    ROUND(SUM(CASE WHEN EXTRACT(QUARTER FROM sale_date) = 4 THEN amount END)) AS q4,
    ROUND(SUM(amount))                                                          AS annual
FROM sales
GROUP BY product_id
ORDER BY product_id;
```

### Crosstab with tablefunc Extension

```sql
CREATE EXTENSION IF NOT EXISTS tablefunc;

-- Crosstab: rows = region, columns = category, values = total sales
SELECT *
FROM crosstab(
    $$
    SELECT region, p.category, ROUND(SUM(s.amount))::TEXT
    FROM sales s
    JOIN products p ON p.id = s.product_id
    GROUP BY region, p.category
    ORDER BY region, p.category
    $$,
    $$ SELECT DISTINCT category FROM products ORDER BY category $$
) AS pivot(
    region       TEXT,
    accessories  TEXT,
    electronics  TEXT
);
```

---

## 10. Advanced Filtering

### DISTINCT ON — First Row Per Group

```sql
-- Cheapest product per category (PostgreSQL-specific)
SELECT DISTINCT ON (category)
    category,
    name,
    price
FROM products
ORDER BY category, price ASC;
-- DISTINCT ON takes the FIRST row per (category) in the ORDER BY sequence

-- Most recent sale per employee
SELECT DISTINCT ON (employee_id)
    employee_id,
    amount,
    sale_date,
    product_id
FROM sales
ORDER BY employee_id, sale_date DESC;
```

### Ranges and Complex Boundaries

```sql
-- Orders in the last full month
SELECT *
FROM orders
WHERE created_at >= DATE_TRUNC('month', CURRENT_DATE - INTERVAL '1 month')
  AND created_at <  DATE_TRUNC('month', CURRENT_DATE);

-- Orders in the current week (Monday to Sunday)
SELECT *
FROM orders
WHERE created_at >= DATE_TRUNC('week', CURRENT_DATE)
  AND created_at <  DATE_TRUNC('week', CURRENT_DATE) + INTERVAL '7 days';

-- Fiscal year (April to March)
SELECT *
FROM sales
WHERE sale_date >= (
    CASE WHEN EXTRACT(MONTH FROM CURRENT_DATE) >= 4
         THEN DATE(EXTRACT(YEAR FROM CURRENT_DATE) || '-04-01')
         ELSE DATE((EXTRACT(YEAR FROM CURRENT_DATE)-1) || '-04-01')
    END
);
```

### ANY / ALL with Arrays

```sql
-- Employees with specific skills
SELECT name, tags
FROM employees
WHERE tags @> ARRAY['python', 'sql'];   -- has BOTH python AND sql

-- Has at least one of these skills
SELECT name, tags
FROM employees
WHERE tags && ARRAY['python', 'java', 'go'];

-- Has EXACTLY these tags (no more, no less)
SELECT name, tags
FROM employees
WHERE tags @> ARRAY['python', 'sql']
  AND ARRAY['python', 'sql'] @> tags;
```

### Pattern Matching Summary

```sql
-- LIKE: SQL standard pattern
SELECT * FROM employees WHERE name LIKE 'A%';        -- starts with A
SELECT * FROM employees WHERE name LIKE '%son';       -- ends with son
SELECT * FROM employees WHERE name LIKE '%li%';       -- contains li
SELECT * FROM employees WHERE name LIKE 'A___e%';     -- A + 3 chars + e

-- ILIKE: case-insensitive
SELECT * FROM employees WHERE name ILIKE '%alice%';

-- Regex
SELECT * FROM employees WHERE name ~ '^[AB]';         -- starts with A or B
SELECT * FROM employees WHERE email ~ '\d+@';         -- digit before @
SELECT * FROM employees WHERE name ~* 'alice|bob';    -- case-insensitive OR

-- SIMILAR TO: SQL-standard regex (limited)
SELECT * FROM employees WHERE name SIMILAR TO '(Alice|Bob)%';
```

---

## 11. JSON Advanced Queries

### Querying Nested JSON

```sql
-- Extract nested values
SELECT
    name,
    metadata->>'level'                          AS level,
    (metadata->>'score')::INT                   AS score,
    metadata->'address'->>'city'                AS city,
    metadata#>>'{contact,phone}'                AS phone
FROM employees;

-- Filter on JSON values
SELECT name, metadata
FROM employees
WHERE metadata->>'level' = 'senior'
  AND (metadata->>'score')::INT > 85;

-- JSON containment filter
SELECT name
FROM employees
WHERE metadata @> '{"level":"senior"}';

-- Existence check
SELECT name
FROM employees
WHERE metadata ? 'bonus_eligible';
```

### JSON Aggregation Patterns

```sql
-- Build a JSON report per department
SELECT
    department,
    jsonb_build_object(
        'headcount',    COUNT(*),
        'avg_salary',   ROUND(AVG(salary)),
        'max_salary',   MAX(salary),
        'members',      jsonb_agg(
                            jsonb_build_object(
                                'name',   name,
                                'salary', salary,
                                'level',  metadata->>'level'
                            ) ORDER BY salary DESC
                        )
    ) AS dept_summary
FROM employees
GROUP BY department;
```

### JSON Transformation

```sql
-- Flatten JSONB into columns using jsonb_to_record
SELECT
    name,
    r.level,
    r.score
FROM employees e,
LATERAL jsonb_to_record(e.metadata) AS r(level TEXT, score INT);

-- Expand all key-value pairs of JSONB into rows
SELECT e.name, kv.key, kv.value
FROM employees e,
LATERAL jsonb_each_text(e.metadata) AS kv(key, value);

-- Update deeply nested JSON
UPDATE employees
SET metadata = jsonb_set(
    metadata,
    '{address, city}',
    '"Mumbai"'
)
WHERE id = 1;

-- Delete a key
UPDATE employees
SET metadata = metadata - 'bonus'
WHERE department = 'Sales';

-- Merge/update multiple keys at once
UPDATE employees
SET metadata = metadata || '{"reviewed":true,"review_date":"2024-03-15"}'::JSONB
WHERE id = 1;
```

---

## 12. Array Advanced Queries

```sql
-- Find employees with exactly 2 skills
SELECT name, tags
FROM employees
WHERE CARDINALITY(tags) = 2;

-- Count occurrences of each skill across all employees
SELECT skill, COUNT(*) AS employee_count
FROM employees,
LATERAL UNNEST(tags) AS skill
GROUP BY skill
ORDER BY employee_count DESC;

-- Employees with more than one matching skill from a list
SELECT name, tags,
    CARDINALITY(
        ARRAY(
            SELECT UNNEST(tags)
            INTERSECT
            SELECT UNNEST(ARRAY['python','sql','java'])
        )
    ) AS matching_skill_count
FROM employees
HAVING CARDINALITY(
    ARRAY(
        SELECT UNNEST(tags)
        INTERSECT
        SELECT UNNEST(ARRAY['python','sql','java'])
    )
) >= 2;

-- Collect all tags per department (flattened)
SELECT
    department,
    ARRAY_AGG(DISTINCT skill ORDER BY skill) AS all_skills
FROM employees,
LATERAL UNNEST(tags) AS skill
GROUP BY department;
```

---

## 13. Full Text Search Advanced

### Ranked Search with Highlights

```sql
-- Full text search with ranking and snippet highlight
SELECT
    e.name,
    e.department,
    ts_rank(
        to_tsvector('english', e.name || ' ' || e.department),
        query
    )                                                    AS rank,
    ts_headline(
        'english',
        e.name || ' works in ' || e.department,
        query,
        'StartSel=**, StopSel=**, MaxWords=10'
    )                                                    AS headline
FROM employees e,
     to_tsquery('english', 'engineering | senior') query
WHERE to_tsvector('english', e.name || ' ' || e.department) @@ query
ORDER BY rank DESC;
```

### Combined FTS and Regular Filters

```sql
-- Full text search + salary filter + department filter
WITH search_query AS (
    SELECT plainto_tsquery('english', 'python sql') AS q
)
SELECT e.name, e.salary, e.department
FROM employees e, search_query
WHERE to_tsvector('english', ARRAY_TO_STRING(e.tags, ' ')) @@ search_query.q
  AND e.salary > 70000
  AND e.is_active = true
ORDER BY ts_rank(
    to_tsvector('english', ARRAY_TO_STRING(tags, ' ')),
    search_query.q
) DESC;
```

---

## 14. Date & Time Advanced

### Gap Detection (Missing Dates)

```sql
-- Find months with no sales
WITH all_months AS (
    SELECT generate_series(
        DATE_TRUNC('month', MIN(sale_date)),
        DATE_TRUNC('month', MAX(sale_date)),
        '1 month'::INTERVAL
    )::DATE AS month
    FROM sales
),
sales_months AS (
    SELECT DISTINCT DATE_TRUNC('month', sale_date)::DATE AS month
    FROM sales
)
SELECT am.month AS missing_month
FROM all_months am
LEFT JOIN sales_months sm USING (month)
WHERE sm.month IS NULL;
```

### Running Streak Detection

```sql
-- Find longest consecutive days with sales
WITH daily AS (
    SELECT DISTINCT sale_date::DATE AS day FROM sales
),
grouped AS (
    SELECT
        day,
        day - ROW_NUMBER() OVER (ORDER BY day) * INTERVAL '1 day' AS grp
    FROM daily
),
streaks AS (
    SELECT
        MIN(day) AS streak_start,
        MAX(day) AS streak_end,
        COUNT(*) AS streak_length
    FROM grouped
    GROUP BY grp
)
SELECT * FROM streaks
ORDER BY streak_length DESC
LIMIT 5;
```

### Business Day Calculation

```sql
-- Count business days between two dates
WITH dates AS (
    SELECT generate_series(
        '2024-01-01'::DATE,
        '2024-01-31'::DATE,
        '1 day'
    )::DATE AS d
)
SELECT COUNT(*) AS business_days
FROM dates
WHERE EXTRACT(DOW FROM d) NOT IN (0, 6);  -- exclude Sunday=0, Saturday=6

-- Add N business days to a date
WITH RECURSIVE business_days AS (
    SELECT '2024-01-15'::DATE AS dt, 0 AS days_added
    UNION ALL
    SELECT
        dt + 1,
        days_added + CASE WHEN EXTRACT(DOW FROM dt+1) IN (0,6) THEN 0 ELSE 1 END
    FROM business_days
    WHERE days_added < 10
)
SELECT MAX(dt) FROM business_days;
```

### Time Zone Handling

```sql
-- Convert timestamps across timezones
SELECT
    created_at                                              AS utc_time,
    created_at AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata' AS ist_time,
    created_at AT TIME ZONE 'UTC' AT TIME ZONE 'America/New_York' AS est_time,
    EXTRACT(HOUR FROM
        created_at AT TIME ZONE 'Asia/Kolkata'
    )                                                       AS ist_hour
FROM orders
LIMIT 5;
```

---

## 15. Analytical / Reporting Patterns

### Year-over-Year Comparison Report

```sql
WITH monthly AS (
    SELECT
        DATE_TRUNC('month', sale_date) AS month,
        SUM(amount) AS revenue
    FROM sales
    GROUP BY 1
)
SELECT
    TO_CHAR(month, 'YYYY-MM')                           AS period,
    ROUND(revenue)                                       AS revenue,
    ROUND(LAG(revenue,12) OVER (ORDER BY month))        AS prev_year,
    ROUND(revenue - LAG(revenue,12) OVER (ORDER BY month)) AS yoy_delta,
    ROUND(100.0 *
        (revenue - LAG(revenue,12) OVER (ORDER BY month))
        / NULLIF(LAG(revenue,12) OVER (ORDER BY month), 0),
        1
    )                                                    AS yoy_pct
FROM monthly
ORDER BY month;
```

### Cohort Analysis

```sql
-- Monthly cohort: when did users join and how many are still active?
WITH cohorts AS (
    SELECT
        DATE_TRUNC('month', joined_at)::DATE AS cohort_month,
        id
    FROM employees
),
cohort_data AS (
    SELECT
        c.cohort_month,
        COUNT(DISTINCT c.id) AS cohort_size,
        COUNT(DISTINCT CASE WHEN e.is_active THEN c.id END) AS still_active
    FROM cohorts c
    JOIN employees e ON e.id = c.id
    GROUP BY c.cohort_month
)
SELECT
    cohort_month,
    cohort_size,
    still_active,
    ROUND(100.0 * still_active / cohort_size, 1) AS retention_pct
FROM cohort_data
ORDER BY cohort_month;
```

### Funnel Analysis

```sql
-- Order status funnel
SELECT
    COUNT(*) FILTER (WHERE status IN ('pending','processing','shipped','delivered')) AS created,
    COUNT(*) FILTER (WHERE status IN ('processing','shipped','delivered'))           AS processed,
    COUNT(*) FILTER (WHERE status IN ('shipped','delivered'))                        AS shipped,
    COUNT(*) FILTER (WHERE status = 'delivered')                                     AS delivered,
    ROUND(100.0 * COUNT(*) FILTER (WHERE status = 'delivered')
          / NULLIF(COUNT(*), 0), 1)                                                  AS conversion_pct
FROM orders;
```

### Percentile Segmentation (Deciles)

```sql
-- Divide employees into salary deciles
SELECT
    name,
    salary,
    NTILE(10) OVER (ORDER BY salary)    AS decile,
    NTILE(4)  OVER (ORDER BY salary)    AS quartile,
    PERCENT_RANK() OVER (ORDER BY salary) AS pct_rank,
    CASE NTILE(4) OVER (ORDER BY salary)
        WHEN 1 THEN 'Bottom 25%'
        WHEN 2 THEN 'Lower Middle'
        WHEN 3 THEN 'Upper Middle'
        WHEN 4 THEN 'Top 25%'
    END AS salary_segment
FROM employees
ORDER BY salary;
```

---

## 16. Data Modification with RETURNING

### INSERT … RETURNING

```sql
-- Get generated IDs immediately after insert
INSERT INTO employees (name, email, salary, department, joined_at)
VALUES
    ('Zara Ahmed', 'zara@co.com', 75000, 'Engineering', CURRENT_DATE),
    ('Omar Hassan', 'omar@co.com', 68000, 'Marketing', CURRENT_DATE)
RETURNING id, name, salary;
```

### UPDATE … RETURNING

```sql
-- Apply raise and see before/after in one query
UPDATE employees
SET salary = salary * 1.10
WHERE department = 'Engineering'
  AND is_active = true
RETURNING
    id,
    name,
    salary                  AS new_salary,
    salary / 1.10           AS old_salary,
    salary - salary / 1.10  AS raise_amount;
```

### DELETE … RETURNING

```sql
-- Delete and archive in one atomic step
WITH deleted AS (
    DELETE FROM orders
    WHERE status = 'cancelled'
      AND created_at < NOW() - INTERVAL '1 year'
    RETURNING *
)
INSERT INTO orders_archive SELECT *, NOW() FROM deleted;
```

### Chained RETURNING with CTE

```sql
-- Multi-table update chain using RETURNING
WITH
-- Step 1: promote employees
promoted AS (
    UPDATE employees
    SET salary = salary * 1.15,
        metadata = jsonb_set(metadata, '{level}', '"senior"')
    WHERE (metadata->>'score')::INT > 90
      AND metadata->>'level' = 'mid'
    RETURNING id, name, salary
),
-- Step 2: log the promotions
logged AS (
    INSERT INTO audit_log (employee_id, action, details, created_at)
    SELECT id, 'PROMOTION', name || ' promoted to senior', NOW()
    FROM promoted
    RETURNING *
)
SELECT COUNT(*) AS promotions_made FROM logged;
```

---

## 17. Upsert Patterns

### ON CONFLICT DO UPDATE

```sql
-- Upsert: insert or update salary if employee exists
INSERT INTO employees (id, name, salary, department, joined_at)
VALUES (1, 'Alice Johnson', 100000, 'Engineering', '2019-03-15')
ON CONFLICT (id) DO UPDATE
    SET salary     = EXCLUDED.salary,
        department = EXCLUDED.department;

-- Only update if new value is higher
INSERT INTO products (id, name, price, stock)
VALUES (1, 'Laptop', 85000, 60)
ON CONFLICT (id) DO UPDATE
    SET price = EXCLUDED.price,
        stock = products.stock + EXCLUDED.stock  -- add incoming stock
    WHERE EXCLUDED.price > products.price;       -- only update price if higher

-- Track last updated time on conflict
INSERT INTO employees (email, name, salary, joined_at)
VALUES ('alice@co.com', 'Alice Johnson', 100000, CURRENT_DATE)
ON CONFLICT (email) DO UPDATE
    SET salary     = EXCLUDED.salary,
        updated_at = NOW()
RETURNING id, name, salary, (xmax != 0) AS was_updated;
-- xmax != 0 → row was updated (not inserted)
```

### ON CONFLICT DO NOTHING

```sql
-- Insert new employees, silently skip duplicates
INSERT INTO employees (email, name, salary, department, joined_at)
SELECT 'emp'||i||'@co.com', 'Emp '||i, 60000, 'Engineering', CURRENT_DATE
FROM generate_series(1,100) i
ON CONFLICT (email) DO NOTHING;
```

---

## 18. Advanced JOIN Patterns

### Self-Join for Comparisons

```sql
-- Find pairs of employees in the same department earning within 10% of each other
SELECT
    a.name AS emp1, a.salary AS sal1,
    b.name AS emp2, b.salary AS sal2,
    a.department
FROM employees a
JOIN employees b
  ON a.department = b.department
 AND a.id < b.id                               -- avoid duplicates and self-match
 AND ABS(a.salary - b.salary) / NULLIF(GREATEST(a.salary, b.salary), 0) < 0.10
ORDER BY a.department, a.salary;
```

### Non-Equi Join for Range Lookup

```sql
-- Classify each employee's salary into a band defined in another table
CREATE TABLE salary_bands (
    band  TEXT,
    lo    NUMERIC,
    hi    NUMERIC
);
INSERT INTO salary_bands VALUES
    ('Band 1', 0, 60000),
    ('Band 2', 60001, 80000),
    ('Band 3', 80001, 100000),
    ('Band 4', 100001, 9999999);

SELECT e.name, e.salary, sb.band
FROM employees e
JOIN salary_bands sb
  ON e.salary BETWEEN sb.lo AND sb.hi;
```

### Anti-Join Three Ways

```sql
-- 1. NOT EXISTS (best — uses Hash Anti Join)
SELECT name FROM employees e
WHERE NOT EXISTS (SELECT 1 FROM sales s WHERE s.employee_id = e.id);

-- 2. LEFT JOIN + IS NULL
SELECT e.name FROM employees e
LEFT JOIN sales s ON s.employee_id = e.id
WHERE s.id IS NULL;

-- 3. EXCEPT
SELECT id FROM employees
EXCEPT
SELECT DISTINCT employee_id FROM sales;
```

### Cross Join for Combinations

```sql
-- Generate all employee × product combinations for quota planning
SELECT
    e.name     AS salesperson,
    p.name     AS product,
    0::NUMERIC AS quota
FROM employees e
CROSS JOIN products p
WHERE e.department = 'Sales'
  AND e.is_active = true
ORDER BY e.name, p.name;
```

---

## 19. Performance Patterns

### Pagination — OFFSET vs Keyset

```sql
-- OFFSET pagination (slow on large datasets — skips N rows)
SELECT id, name, salary
FROM employees
ORDER BY salary DESC, id
LIMIT 20 OFFSET 1000;    -- scans and discards 1000 rows first

-- Keyset (cursor) pagination — fast regardless of page depth
-- First page:
SELECT id, name, salary
FROM employees
ORDER BY salary DESC, id
LIMIT 20;
-- Returns last row: salary=75000, id=42

-- Next page: WHERE continues from last seen values
SELECT id, name, salary
FROM employees
WHERE (salary, id) < (75000, 42)   -- continue from bookmark
ORDER BY salary DESC, id
LIMIT 20;
-- Instantly uses index — no row scanning overhead
```

### Batch Processing with CTEs

```sql
-- Process 1000 rows at a time without locking the whole table
WITH batch AS (
    SELECT id FROM orders
    WHERE status = 'pending'
      AND created_at < NOW() - INTERVAL '7 days'
    LIMIT 1000
    FOR UPDATE SKIP LOCKED
)
UPDATE orders
SET status = 'expired'
FROM batch
WHERE orders.id = batch.id
RETURNING orders.id;
-- Run repeatedly until 0 rows returned
```

### Pre-aggregation with Materialized CTE

```sql
-- Compute expensive aggregation ONCE, reuse many times
WITH MATERIALIZED dept_stats AS (
    SELECT
        department,
        AVG(salary)   AS avg_sal,
        STDDEV(salary) AS std_sal,
        COUNT(*)      AS headcount,
        SUM(salary)   AS total_payroll
    FROM employees
    WHERE is_active = true
    GROUP BY department
)
SELECT
    e.name,
    e.salary,
    ds.avg_sal,
    ds.std_sal,
    ds.headcount,
    (e.salary - ds.avg_sal) / NULLIF(ds.std_sal, 0)  AS z_score,
    e.salary / NULLIF(ds.total_payroll, 0) * 100      AS pct_of_dept_payroll
FROM employees e
JOIN dept_stats ds USING (department)
WHERE e.is_active = true
ORDER BY ABS((e.salary - ds.avg_sal) / NULLIF(ds.std_sal, 0)) DESC;
```

---

## 20. Quick Reference Cheat Sheet

```
╔═══════════════════════╦═══════════════════════════════════════════════════════╗
║ FEATURE               ║ SYNTAX / USE CASE                                     ║
╠═══════════════════════╬═══════════════════════════════════════════════════════╣
║ CTE                   ║ WITH name AS (SELECT ...) SELECT * FROM name          ║
║ Writable CTE          ║ WITH del AS (DELETE ... RETURNING *) INSERT ...       ║
║ Materialized CTE      ║ WITH name AS MATERIALIZED (SELECT ...)                ║
╠═══════════════════════╬═══════════════════════════════════════════════════════╣
║ Recursive CTE         ║ WITH RECURSIVE name AS (base UNION ALL recursive)     ║
║                       ║ Must include cycle-prevention: WHERE NOT id=ANY(path) ║
╠═══════════════════════╬═══════════════════════════════════════════════════════╣
║ Window Functions      ║ func() OVER (PARTITION BY x ORDER BY y ROWS ...)     ║
║ Running Total         ║ SUM(x) OVER (ORDER BY y)                              ║
║ Moving Average        ║ AVG(x) OVER (ORDER BY y ROWS BETWEEN 6 PRECEDING...)  ║
║ Period Comparison     ║ LAG(x,1) / LEAD(x,1) OVER (ORDER BY date)            ║
║ Percentile            ║ PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY x)       ║
╠═══════════════════════╬═══════════════════════════════════════════════════════╣
║ LATERAL               ║ FROM t, LATERAL (SELECT ... WHERE col = t.col) sub    ║
║                       ║ Use for: top-N per group, latest record, unnest       ║
╠═══════════════════════╬═══════════════════════════════════════════════════════╣
║ Subquery Types        ║ Scalar  — returns single value in SELECT              ║
║                       ║ Correlated — references outer row, runs per row       ║
║                       ║ EXISTS  — semi-join, stops at first match             ║
║                       ║ NOT EXISTS — anti-join pattern                        ║
║                       ║ IN/ANY/ALL — list or range membership checks          ║
╠═══════════════════════╬═══════════════════════════════════════════════════════╣
║ Set Operations        ║ UNION ALL  — combine rows, keep duplicates (fast)    ║
║                       ║ UNION      — combine rows, deduplicate (slow)        ║
║                       ║ INTERSECT  — rows in BOTH sets                       ║
║                       ║ EXCEPT     — rows in first but NOT second            ║
╠═══════════════════════╬═══════════════════════════════════════════════════════╣
║ Aggregation           ║ FILTER (WHERE cond) — conditional aggregate          ║
║                       ║ DISTINCT inside aggregate: COUNT(DISTINCT col)       ║
║                       ║ PERCENTILE_CONT/DISC — ordered-set aggregates        ║
╠═══════════════════════╬═══════════════════════════════════════════════════════╣
║ Grouping              ║ GROUPING SETS  — explicit list of group combos       ║
║                       ║ ROLLUP         — hierarchical subtotals              ║
║                       ║ CUBE           — all possible combinations           ║
║ Identify level        ║ GROUPING(col)  — 1 if col is aggregated, else 0     ║
╠═══════════════════════╬═══════════════════════════════════════════════════════╣
║ Pivot                 ║ SUM(CASE WHEN cat='X' THEN amount END) AS x         ║
║                       ║ crosstab() function via tablefunc extension          ║
╠═══════════════════════╬═══════════════════════════════════════════════════════╣
║ DISTINCT ON           ║ SELECT DISTINCT ON (col) ... ORDER BY col, sort_col  ║
║                       ║ First row per group by sort order (PostgreSQL only)  ║
╠═══════════════════════╬═══════════════════════════════════════════════════════╣
║ RETURNING             ║ INSERT/UPDATE/DELETE ... RETURNING col1, col2, ...   ║
║                       ║ Combine with CTE for atomic multi-step pipelines     ║
╠═══════════════════════╬═══════════════════════════════════════════════════════╣
║ Upsert                ║ INSERT ... ON CONFLICT (col) DO UPDATE SET ...       ║
║                       ║ ON CONFLICT DO NOTHING  — silently skip duplicates   ║
╠═══════════════════════╬═══════════════════════════════════════════════════════╣
║ Pagination            ║ Keyset: WHERE (col, id) < (last_val, last_id)        ║
║                       ║ Avoid OFFSET on large datasets                       ║
╠═══════════════════════╬═══════════════════════════════════════════════════════╣
║ JSON queries          ║ ->  ->>  @>  ?  jsonb_each  jsonb_to_record          ║
║ Array queries         ║ @>  &&  UNNEST  ARRAY_AGG  cardinality               ║
╚═══════════════════════╩═══════════════════════════════════════════════════════╝
```

---

## Further Reading

- [PostgreSQL Docs — WITH Queries (CTEs)](https://www.postgresql.org/docs/current/queries-with.html)
- [PostgreSQL Docs — Window Functions](https://www.postgresql.org/docs/current/tutorial-window.html)
- [PostgreSQL Docs — LATERAL](https://www.postgresql.org/docs/current/queries-table-expressions.html#QUERIES-LATERAL)
- [PostgreSQL Docs — Aggregate Functions](https://www.postgresql.org/docs/current/functions-aggregate.html)
- [PostgreSQL Docs — INSERT ON CONFLICT](https://www.postgresql.org/docs/current/sql-insert.html)
- [PostgreSQL Docs — crosstab](https://www.postgresql.org/docs/current/tablefunc.html)
- [Use the Index, Luke](https://use-the-index-luke.com/)
- [The Art of PostgreSQL](https://theartofpostgresql.com/)

---

*Generated with love for PostgreSQL engineers.*
