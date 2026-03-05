# PostgreSQL — Indexing & Performance Complete Reference

> A deep-dive guide covering every index type, index strategies, query optimization, EXPLAIN analysis, VACUUM, partitioning, and performance tuning in PostgreSQL.

---

## Table of Contents

1. [What is an Index?](#1-what-is-an-index)
2. [B-Tree Index](#2-b-tree-index-default)
3. [Hash Index](#3-hash-index)
4. [GIN Index](#4-gin-index-generalized-inverted-index)
5. [GiST Index](#5-gist-index-generalized-search-tree)
6. [BRIN Index](#6-brin-index-block-range-index)
7. [SP-GiST Index](#7-sp-gist-index)
8. [Partial Indexes](#8-partial-indexes)
9. [Functional / Expression Indexes](#9-functional--expression-indexes)
10. [Composite Indexes](#10-composite-indexes)
11. [Covering Indexes (INCLUDE)](#11-covering-indexes-include)
12. [Index-Only Scans](#12-index-only-scans)
13. [EXPLAIN & EXPLAIN ANALYZE](#13-explain--explain-analyze)
14. [Query Optimization Rules](#14-query-optimization-rules)
15. [VACUUM & ANALYZE](#15-vacuum--analyze)
16. [Table Partitioning](#16-table-partitioning)
17. [Configuration Tuning](#17-configuration-tuning)
18. [Performance Monitoring](#18-performance-monitoring)
19. [Quick Reference Cheat Sheet](#19-quick-reference-cheat-sheet)

---

## Sample Tables Used in Examples

```sql
CREATE TABLE employees (
    id          SERIAL PRIMARY KEY,
    name        TEXT NOT NULL,
    email       TEXT UNIQUE,
    salary      NUMERIC,
    department  TEXT,
    joined_at   DATE,
    is_active   BOOLEAN DEFAULT true,
    tags        TEXT[],
    location    POINT,
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

-- Insert sample data
INSERT INTO employees (name, email, salary, department, joined_at, tags, metadata)
SELECT
    'Employee ' || i,
    'emp' || i || '@company.com',
    50000 + (random() * 80000)::INT,
    (ARRAY['Engineering','Marketing','HR','Sales'])[ceil(random()*4)::INT],
    CURRENT_DATE - (random() * 2000)::INT,
    ARRAY['sql','python','java'],
    jsonb_build_object('level', 'mid', 'score', (random()*100)::INT)
FROM generate_series(1, 100000) i;

INSERT INTO orders (customer_id, product, amount, status, created_at)
SELECT
    (random() * 1000)::INT + 1,
    (ARRAY['Laptop','Mouse','Monitor','Keyboard','Webcam'])[ceil(random()*5)::INT],
    (random() * 100000)::NUMERIC(10,2),
    (ARRAY['pending','processing','shipped','delivered','cancelled'])[ceil(random()*5)::INT],
    NOW() - (random() * 365 * 3 || ' days')::INTERVAL
FROM generate_series(1, 1000000) i;
```

---

## 1. What is an Index?

An **index** is a separate data structure that PostgreSQL maintains alongside a table. It stores a subset of table data in a way that allows the database to find rows much faster — without scanning every row.

### Without an Index (Sequential Scan)

```sql
-- No index on salary column
EXPLAIN ANALYZE
SELECT * FROM employees WHERE salary > 90000;

-- Output:
-- Seq Scan on employees  (cost=0.00..2841.00 rows=12500 width=200)
--                        (actual time=0.012..28.3 rows=12489 loops=1)
-- Filter: (salary > 90000)
-- Rows Removed by Filter: 87511
-- Planning Time: 0.1 ms
-- Execution Time: 31.2 ms
-- Reads EVERY row to find matches
```

### With an Index (Index Scan)

```sql
-- After creating index:
CREATE INDEX idx_employees_salary ON employees(salary);

EXPLAIN ANALYZE
SELECT * FROM employees WHERE salary > 90000;

-- Output:
-- Index Scan using idx_employees_salary on employees
--   (cost=0.29..612.40 rows=12500 width=200)
--   (actual time=0.041..4.2 rows=12489 loops=1)
-- Index Cond: (salary > 90000)
-- Planning Time: 0.2 ms
-- Execution Time: 5.1 ms   ← 6x faster!
```

### How a B-Tree Index Works Internally

```
Table: employees (100,000 rows)
                                                  
         ┌──────────────────────────────┐         
         │    ROOT NODE                 │         
         │  50000 | 75000 | 100000      │         
         └──────┬────────┬──────┬───────┘         
                │        │      │                  
    ┌───────────┘  ┌──────┘  ┌──┘                 
    ▼              ▼         ▼                     
┌────────┐   ┌──────────┐  ┌──────────┐           
│INTERNAL│   │ INTERNAL │  │ INTERNAL │           
│50k-75k │   │ 75k-100k │  │ 100k+    │           
└────┬───┘   └─────┬────┘  └────┬─────┘           
     │             │             │                 
     ▼             ▼             ▼                 
┌─────────┐  ┌─────────┐  ┌─────────┐            
│  LEAF   │→ │  LEAF   │→ │  LEAF   │            
│(sal,ctid│  │(sal,ctid│  │(sal,ctid│            
│ pairs)  │  │ pairs)  │  │ pairs)  │            
└─────────┘  └─────────┘  └─────────┘            

ctid = physical row location (page, offset)
O(log N) lookups instead of O(N) sequential scans
```

### Index Trade-offs

| Benefit | Cost |
|---------|------|
| Faster SELECT queries | Slower INSERT / UPDATE / DELETE |
| Enables efficient sorting | Disk space usage |
| Supports JOIN acceleration | VACUUM overhead |
| Enables constraint enforcement | Planning time increases with many indexes |

---

## 2. B-Tree Index (Default)

The default and most versatile index type. Stores data in a balanced tree structure. Supports equality, range, sorting, and prefix matching.

### Supported Operators

```
=   <   >   <=   >=   BETWEEN   IN   IS NULL   IS NOT NULL
LIKE 'prefix%'   ORDER BY   MIN()   MAX()
```

### Basic Creation

```sql
-- Single column
CREATE INDEX idx_emp_salary    ON employees(salary);
CREATE INDEX idx_emp_dept      ON employees(department);
CREATE INDEX idx_emp_joined    ON employees(joined_at);

-- Unique index (enforces uniqueness)
CREATE UNIQUE INDEX idx_emp_email ON employees(email);

-- Descending order (useful for "latest first" queries)
CREATE INDEX idx_orders_created_desc ON orders(created_at DESC);

-- Concurrent creation (does not block reads/writes)
CREATE INDEX CONCURRENTLY idx_emp_name ON employees(name);

-- Drop index
DROP INDEX idx_emp_salary;
DROP INDEX CONCURRENTLY idx_emp_salary;  -- non-blocking

-- Rebuild index (fixes bloat)
REINDEX INDEX idx_emp_salary;
REINDEX TABLE employees;       -- rebuild all indexes on table
REINDEX TABLE CONCURRENTLY employees;  -- non-blocking (PG 12+)
```

### B-Tree Usage Examples

```sql
-- Equality: uses index
SELECT * FROM employees WHERE department = 'Engineering';

-- Range: uses index
SELECT * FROM employees WHERE salary BETWEEN 60000 AND 90000;
SELECT * FROM employees WHERE joined_at > '2022-01-01';

-- Prefix LIKE: uses index
SELECT * FROM employees WHERE name LIKE 'Alice%';   -- YES: prefix
SELECT * FROM employees WHERE name LIKE '%Alice%';  -- NO:  leading wildcard

-- Sorting: index avoids Sort node in plan
SELECT * FROM employees ORDER BY salary DESC LIMIT 10;

-- IS NULL / IS NOT NULL: uses index
SELECT * FROM employees WHERE email IS NULL;

-- IN list: uses index
SELECT * FROM employees WHERE department IN ('Engineering', 'HR');

-- Pattern that BREAKS index use:
SELECT * FROM employees WHERE LOWER(name) = 'alice';  -- function wrapping
-- Fix: CREATE INDEX idx_emp_name_lower ON employees(LOWER(name));
```

---

## 3. Hash Index

Stores a hash of the indexed value. Extremely fast for equality lookups (`=`). **Cannot** be used for ranges, sorting, or LIKE.

### When to Use

- Pure equality lookups only
- Very high cardinality columns (UUIDs, emails, hashes)
- Slightly faster than B-tree for equality on large values

```sql
-- Create hash index
CREATE INDEX idx_emp_email_hash ON employees USING hash(email);

-- Only works for = operator
SELECT * FROM employees WHERE email = 'emp100@company.com';  -- uses hash index

-- Does NOT work for:
SELECT * FROM employees WHERE email LIKE 'emp%';    -- no hash support
SELECT * FROM employees WHERE email > 'emp100';     -- no hash support
SELECT * FROM employees ORDER BY email;             -- no hash support
```

### B-Tree vs Hash

| Feature | B-Tree | Hash |
|---------|--------|------|
| Equality (`=`) | YES | YES (faster) |
| Range (`<`, `>`, `BETWEEN`) | YES | NO |
| Sorting (`ORDER BY`) | YES | NO |
| LIKE prefix | YES | NO |
| Null values | YES | YES |
| WAL-logged (crash safe) | YES | YES (PG 10+) |
| Multi-column | YES | NO |

---

## 4. GIN Index (Generalized Inverted Index)

Stores a mapping from **each element** to the rows containing it. Best for multi-valued types: arrays, JSONB, full-text search, and `pg_trgm`.

### Supported Types

```
TEXT[] arrays      JSONB      tsvector (full text)
hstore             pg_trgm    Range types
```

### Array Indexes

```sql
CREATE INDEX idx_emp_tags_gin ON employees USING gin(tags);

-- Contains: all employees who have 'python' tag
SELECT * FROM employees WHERE tags @> ARRAY['python'];

-- Overlap: employees with any of these tags
SELECT * FROM employees WHERE tags && ARRAY['python', 'java'];

-- Any element equals
SELECT * FROM employees WHERE 'sql' = ANY(tags);
```

### JSONB Indexes

```sql
CREATE INDEX idx_emp_metadata_gin ON employees USING gin(metadata);

-- Key existence
SELECT * FROM employees WHERE metadata ? 'level';

-- Contains value
SELECT * FROM employees WHERE metadata @> '{"level":"senior"}';

-- Any key in list
SELECT * FROM employees WHERE metadata ?| ARRAY['level','score'];

-- Index specific JSONB path (more efficient than full GIN)
CREATE INDEX idx_emp_level ON employees((metadata->>'level'));
-- Then query:
SELECT * FROM employees WHERE metadata->>'level' = 'senior';
```

### Full Text Search Indexes

```sql
-- Add generated tsvector column
ALTER TABLE employees
    ADD COLUMN fts tsvector
    GENERATED ALWAYS AS (
        to_tsvector('english', COALESCE(name,'') || ' ' || COALESCE(department,''))
    ) STORED;

CREATE INDEX idx_emp_fts ON employees USING gin(fts);

-- Fast full-text search
SELECT name, department
FROM employees
WHERE fts @@ plainto_tsquery('english', 'engineering senior');
```

### Trigram Index (pg_trgm) — LIKE / ILIKE on any pattern

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Trigram GIN index — supports leading wildcard LIKE
CREATE INDEX idx_emp_name_trgm ON employees USING gin(name gin_trgm_ops);

-- Now ALL of these use the index:
SELECT * FROM employees WHERE name LIKE  '%Alice%';   -- YES!
SELECT * FROM employees WHERE name ILIKE '%alice%';   -- YES!
SELECT * FROM employees WHERE name ~     'Alice';     -- YES!
SELECT * FROM employees WHERE name ~*    'alice';     -- YES!

-- Similarity search
SELECT name, similarity(name, 'Alise') AS sim
FROM employees
WHERE name % 'Alise'           -- similarity threshold (default 0.3)
ORDER BY sim DESC
LIMIT 10;
```

### GIN vs GiST for Full Text / Trigram

| | GIN | GiST |
|---|---|---|
| Build time | Slower | Faster |
| Index size | Larger | Smaller |
| Query speed | Faster | Slower |
| Best for | Static data, read-heavy | Frequently updated |

---

## 5. GiST Index (Generalized Search Tree)

A framework for extensible indexes. Used for geometric types, ranges, network addresses, and full-text search. Supports **overlap**, **containment**, and **nearest-neighbor** queries.

### Geometric / PostGIS

```sql
CREATE EXTENSION IF NOT EXISTS cube;

-- Index a POINT column for proximity queries
CREATE INDEX idx_emp_location ON employees USING gist(location);

-- Nearest neighbor: employees closest to a point
SELECT name, location
FROM employees
ORDER BY location <-> POINT(12.9716, 77.5946)   -- Bangalore coords
LIMIT 5;

-- Within a bounding box
SELECT name FROM employees
WHERE location @ BOX(POINT(0,0), POINT(100,100));
```

### Range Type Indexes

```sql
CREATE TABLE bookings (
    id          SERIAL PRIMARY KEY,
    room_id     INTEGER,
    guest       TEXT,
    stay        DATERANGE
);

CREATE INDEX idx_bookings_stay ON bookings USING gist(stay);

-- Find bookings overlapping a date range
SELECT * FROM bookings
WHERE stay && DATERANGE('2024-03-01', '2024-03-10');

-- Find bookings containing a specific date
SELECT * FROM bookings
WHERE stay @> '2024-03-05'::DATE;

-- Prevent overlapping bookings (EXCLUDE constraint uses GiST)
ALTER TABLE bookings
ADD CONSTRAINT no_double_booking
EXCLUDE USING gist (
    room_id WITH =,
    stay    WITH &&
);
```

---

## 6. BRIN Index (Block Range Index)

Stores **minimum and maximum values** per block range (128 pages by default). Extremely small and fast to build. Best for **naturally ordered, append-only, very large tables**.

### When BRIN is Perfect

```
Time-series tables (logs, events, metrics)
Append-only insert-heavy tables
Columns with strong physical correlation to insertion order
Tables too large for B-tree to be practical
```

```sql
-- BRIN index on timestamp (naturally ordered by insertion)
CREATE INDEX idx_orders_created_brin
ON orders USING brin(created_at);

-- BRIN is tiny: typically 1000x smaller than B-tree
-- B-tree on 1M rows: ~50 MB
-- BRIN  on 1M rows: ~50 KB

-- Range queries benefit greatly
SELECT * FROM orders
WHERE created_at BETWEEN '2024-01-01' AND '2024-03-31';

-- BRIN with custom pages_per_range (smaller = more precise, larger file)
CREATE INDEX idx_orders_created_brin2
ON orders USING brin(created_at)
WITH (pages_per_range = 64);   -- default is 128

-- Check BRIN correlation (should be close to 1.0 for BRIN to be effective)
SELECT attname, correlation
FROM pg_stats
WHERE tablename = 'orders' AND attname = 'created_at';
-- correlation = 0.99 → BRIN very effective
-- correlation = 0.01 → BRIN useless, use B-tree
```

### BRIN vs B-Tree

| | BRIN | B-Tree |
|---|---|---|
| Index size | Tiny (KB) | Large (MB/GB) |
| Build time | Very fast | Slower |
| Query precision | Low (block-level) | Exact |
| Best for | Ordered time-series | Any column |
| Selectivity needed | Low | Any |
| Correlation required | High (>0.9) | Not required |

---

## 7. SP-GiST Index

Space-Partitioned GiST. Handles non-balanced tree structures: quadtrees, k-d trees, radix trees. Good for non-overlapping partitioned spaces.

```sql
-- IP address prefix matching (inet type)
CREATE INDEX idx_network_ip ON network_logs USING spgist(ip_address);

SELECT * FROM network_logs
WHERE ip_address << inet '192.168.1.0/24';   -- subnet containment

-- Text prefix search (radix tree internally)
CREATE INDEX idx_emp_name_spgist ON employees USING spgist(name);

SELECT * FROM employees WHERE name LIKE 'Alice%';

-- 2D Point data (quadtree internally)
CREATE INDEX idx_locations_spgist ON locations USING spgist(coords);
SELECT * FROM locations WHERE coords <@ BOX(POINT(0,0), POINT(100,100));
```

---

## 8. Partial Indexes

An index built on a **subset of rows** defined by a WHERE clause. Smaller, faster, and only used when the query predicate implies the index predicate.

```sql
-- Index only active employees (saves space if 90% are active)
CREATE INDEX idx_emp_active
ON employees(salary)
WHERE is_active = true;

-- Query MUST include the predicate to use the partial index:
SELECT * FROM employees
WHERE is_active = true AND salary > 80000;  -- uses index!

SELECT * FROM employees
WHERE salary > 80000;  -- does NOT use index (predicate not implied)

-- Partial index on pending orders only
CREATE INDEX idx_orders_pending
ON orders(created_at)
WHERE status = 'pending';

-- Partial unique index: email must be unique among active users
CREATE UNIQUE INDEX idx_emp_email_active
ON employees(email)
WHERE is_active = true;

-- Index non-NULL values only
CREATE INDEX idx_emp_email_notnull
ON employees(email)
WHERE email IS NOT NULL;

-- Combine partial + expression
CREATE INDEX idx_orders_recent_high_value
ON orders(customer_id, amount DESC)
WHERE created_at > '2024-01-01'
  AND amount > 10000;
```

### Why Partial Indexes Win

```sql
-- Scenario: 1,000,000 orders, only 5,000 are 'pending'

-- Full index on status:
CREATE INDEX idx_orders_status ON orders(status);
-- Index size: ~30 MB
-- Contains all 1,000,000 entries

-- Partial index on pending only:
CREATE INDEX idx_orders_pending ON orders(created_at) WHERE status='pending';
-- Index size: ~150 KB (200x smaller!)
-- Contains only 5,000 entries
-- Dramatically faster for pending-only queries
```

---

## 9. Functional / Expression Indexes

An index on the **result of a function or expression** applied to a column. Allows indexing computed values.

```sql
-- Case-insensitive search
CREATE INDEX idx_emp_name_lower ON employees(LOWER(name));

SELECT * FROM employees WHERE LOWER(name) = 'alice johnson';  -- uses index!

-- Date part extraction
CREATE INDEX idx_orders_year ON orders(EXTRACT(YEAR FROM created_at));

SELECT * FROM orders
WHERE EXTRACT(YEAR FROM created_at) = 2024;  -- uses index!

-- Computed column
CREATE INDEX idx_emp_annual_cost
ON employees((salary * 12 + 50000));  -- parentheses required for expressions

-- JSONB field extraction
CREATE INDEX idx_emp_level
ON employees((metadata->>'level'));

SELECT * FROM employees
WHERE metadata->>'level' = 'senior';  -- uses expression index!

-- Coalesce / NULLIF expressions
CREATE INDEX idx_emp_email_lower
ON employees(COALESCE(LOWER(email), 'no-email'));

-- Multi-expression index
CREATE INDEX idx_orders_composite_expr
ON orders(
    DATE_TRUNC('month', created_at),
    UPPER(status)
);
```

### Critical Rule

```sql
-- The query expression must EXACTLY match the index expression
-- Index: LOWER(name)
SELECT * FROM employees WHERE LOWER(name) = 'alice';   -- YES: exact match
SELECT * FROM employees WHERE name = 'alice';           -- NO:  different expression
SELECT * FROM employees WHERE UPPER(name) = 'ALICE';   -- NO:  different function
```

---

## 10. Composite Indexes

An index on **multiple columns**. Column order matters enormously.

### Column Order Rules

```
Rule 1: Put equality columns FIRST
Rule 2: Put range/sort columns LAST
Rule 3: Put highest-selectivity columns first (within equality group)
Rule 4: The leading column(s) can be used independently
```

```sql
-- Composite index
CREATE INDEX idx_emp_dept_salary
ON employees(department, salary);

-- Uses index (leading column match):
SELECT * FROM employees WHERE department = 'Engineering';

-- Uses index (both columns):
SELECT * FROM employees WHERE department = 'Engineering' AND salary > 80000;

-- Does NOT use index (non-leading column alone):
SELECT * FROM employees WHERE salary > 80000;
-- → Seq Scan or separate index needed

-- Optimal order for common query pattern:
-- Query: WHERE department = ? AND salary BETWEEN ? AND ? ORDER BY salary
CREATE INDEX idx_emp_dept_salary_opt
ON employees(department, salary DESC);
-- department = ? → equality, goes FIRST
-- salary range + ORDER BY → goes LAST

-- Three-column composite
CREATE INDEX idx_orders_status_cust_date
ON orders(status, customer_id, created_at DESC);

-- Benefits queries like:
SELECT * FROM orders
WHERE status = 'pending'
  AND customer_id = 42
ORDER BY created_at DESC;
```

### Index Selectivity Check

```sql
-- Check how selective each column is before building composite index
SELECT
    COUNT(DISTINCT department) AS dept_distinct,   -- low = less selective
    COUNT(DISTINCT salary)     AS salary_distinct, -- high = more selective
    COUNT(DISTINCT status)     AS status_distinct, -- low = less selective
    COUNT(*)                   AS total_rows
FROM employees;
-- Put highest distinct count column first for best performance
```

---

## 11. Covering Indexes (INCLUDE)

An **INCLUDE** clause adds extra columns to the index **without** making them part of the index key. Enables **index-only scans** by storing needed columns in the index itself.

```sql
-- Without INCLUDE: index has department key, must visit heap for salary
CREATE INDEX idx_emp_dept ON employees(department);
-- Query needs to fetch salary from heap for each row

-- With INCLUDE: salary stored IN the index — no heap visit needed
CREATE INDEX idx_emp_dept_covering
ON employees(department)
INCLUDE (salary, name, email);

-- This query now uses Index-Only Scan (no heap access):
SELECT name, salary, email
FROM employees
WHERE department = 'Engineering';

-- Covering index for a common reporting query
CREATE INDEX idx_orders_covering
ON orders(status, created_at DESC)
INCLUDE (customer_id, amount, product);

-- No heap access for:
SELECT customer_id, amount, product
FROM orders
WHERE status = 'shipped'
ORDER BY created_at DESC
LIMIT 100;
```

### INCLUDE vs Adding Column to Key

```sql
-- Key column: affects sort order, usable in WHERE / ORDER BY
CREATE INDEX idx_a ON employees(department, salary);   -- salary in key
-- Can use: WHERE salary > 80000
-- Can use: ORDER BY salary

-- INCLUDE column: stored but NOT sortable/filterable
CREATE INDEX idx_b ON employees(department) INCLUDE (salary);
-- CANNOT use: WHERE salary > 80000   (salary not in key)
-- CANNOT use: ORDER BY salary         (salary not sorted)
-- CAN use: SELECT salary WHERE department = 'Eng' (no heap access)
```

---

## 12. Index-Only Scans

When **all columns** needed by a query are present in the index (either as key or INCLUDE), PostgreSQL can answer the query **entirely from the index** without touching the heap (table data pages).

```sql
-- Setup: covering index
CREATE INDEX idx_orders_ioss
ON orders(status)
INCLUDE (amount, customer_id);

-- Check EXPLAIN output
EXPLAIN ANALYZE
SELECT amount, customer_id
FROM orders
WHERE status = 'pending';

-- Look for:
-- Index Only Scan using idx_orders_ioss on orders
--   (actual time=0.02..1.2 rows=5432 loops=1)
-- Heap Fetches: 0           ← no heap access!

-- Visibility map must be up-to-date for index-only scans
-- VACUUM keeps visibility map current
VACUUM employees;

-- Check visibility map coverage
SELECT
    relname,
    100.0 * heap_blks_hit / NULLIF(heap_blks_hit + heap_blks_read, 0) AS cache_hit_pct,
    n_live_tup,
    n_dead_tup
FROM pg_stat_user_tables
WHERE relname = 'orders';
```

---

## 13. EXPLAIN & EXPLAIN ANALYZE

### Reading the Plan

```sql
-- Basic EXPLAIN (estimates only, query NOT executed)
EXPLAIN SELECT * FROM orders WHERE status = 'pending';

-- EXPLAIN ANALYZE (executes query, shows actual stats)
EXPLAIN ANALYZE SELECT * FROM orders WHERE status = 'pending';

-- Full options
EXPLAIN (ANALYZE, BUFFERS, VERBOSE, FORMAT TEXT)
SELECT o.*, e.name
FROM orders o
JOIN employees e ON e.id = o.customer_id
WHERE o.status = 'pending'
  AND o.created_at > NOW() - INTERVAL '30 days';
```

### Understanding the Output

```
Seq Scan on orders  (cost=0.00..4321.00 rows=120 width=96)
                          ↑       ↑       ↑       ↑
                     startup  total  est.rows  row width(bytes)
                    (actual time=0.042..45.2 rows=9843 loops=1)
                                       ↑             ↑
                                  actual rows    loop count

Buffers: shared hit=432 read=1891
                    ↑          ↑
                cache hits   disk reads
```

### Plan Node Types

```sql
-- SCAN NODES (leaf — read data)
Seq Scan          -- full table scan, no index
Index Scan        -- uses index, fetches heap rows
Index Only Scan   -- uses index only (all columns in index)
Bitmap Index Scan -- builds bitmap of matching pages
Bitmap Heap Scan  -- reads heap using bitmap

-- JOIN NODES
Nested Loop       -- for each outer row, scan inner
Hash Join         -- build hash table on smaller side, probe with larger
Merge Join        -- both sides sorted, merge step-by-step

-- PROCESSING NODES
Sort              -- sort rows (uses work_mem, may spill to disk)
Hash              -- build hash table
Aggregate         -- GROUP BY, COUNT, SUM...
HashAggregate     -- aggregate using hash table
Limit             -- stop after N rows
Gather            -- collect parallel worker results
```

### Red Flags in EXPLAIN Output

```sql
-- 1. Huge row estimate vs actual mismatch
--    rows=10 (estimated) vs rows=500000 (actual) → stale stats → run ANALYZE

-- 2. Sort with Batches > 1 → disk spill → increase work_mem
Sort Method: external merge  Disk: 245kB

-- 3. Hash with Batches > 1 → hash table spilling to disk
Hash Batches: 8   Memory Usage: 4096kB  → increase work_mem

-- 4. Seq Scan on large table with heavy filter
Seq Scan on orders  Rows Removed by Filter: 990000  → add index

-- 5. Nested Loop with large outer set + Seq Scan on inner → missing index

-- 6. High Buffers read (vs hit) → data not in cache → increase shared_buffers
```

### Practical Diagnosis Workflow

```sql
-- Step 1: Find slow queries
SELECT query, calls, mean_exec_time, total_exec_time, rows
FROM pg_stat_statements
ORDER BY mean_exec_time DESC
LIMIT 20;

-- Step 2: Get full plan
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
<paste slow query here>;

-- Step 3: Check table stats
SELECT tablename, attname, n_distinct, correlation
FROM pg_stats
WHERE tablename = 'orders'
ORDER BY n_distinct;

-- Step 4: Check existing indexes
SELECT indexname, indexdef
FROM pg_indexes
WHERE tablename = 'orders';

-- Step 5: Check index usage
SELECT indexrelname, idx_scan, idx_tup_read, idx_tup_fetch
FROM pg_stat_user_indexes
WHERE relname = 'orders'
ORDER BY idx_scan DESC;

-- Step 6: Find unused indexes (wasting space & write overhead)
SELECT indexrelname, idx_scan, pg_size_pretty(pg_relation_size(indexrelid))
FROM pg_stat_user_indexes
WHERE idx_scan = 0
  AND indexrelname NOT LIKE 'pg_%'
ORDER BY pg_relation_size(indexrelid) DESC;
```

---

## 14. Query Optimization Rules

### Rule 1: Use Indexes on JOIN Columns

```sql
-- Without index: Seq Scan on inner table for every outer row
SELECT o.*, e.name
FROM orders o
JOIN employees e ON e.id = o.customer_id;

-- Add index on join column
CREATE INDEX idx_orders_customer_id ON orders(customer_id);
CREATE INDEX idx_emp_id ON employees(id);  -- usually PK, already indexed
-- Now: Hash Join or Nested Loop with Index Scan
```

### Rule 2: Avoid Functions on Indexed Columns in WHERE

```sql
-- BAD: function wraps indexed column → index bypassed
WHERE UPPER(department) = 'ENGINEERING'
WHERE DATE(created_at) = '2024-01-01'
WHERE salary::TEXT = '85000'

-- GOOD: move transformation to value side, keep column bare
WHERE department = 'Engineering'
WHERE created_at >= '2024-01-01' AND created_at < '2024-01-02'
WHERE salary = 85000

-- Or create a functional index
CREATE INDEX idx_dept_upper ON employees(UPPER(department));
WHERE UPPER(department) = 'ENGINEERING'  -- now uses index
```

### Rule 3: Use LIMIT to Reduce Work

```sql
-- Without LIMIT: scan and sort ALL matching rows
SELECT * FROM orders WHERE status = 'pending' ORDER BY created_at;

-- With LIMIT: stop after first 10 — much less work
SELECT * FROM orders WHERE status = 'pending' ORDER BY created_at LIMIT 10;

-- Index aligned with ORDER BY avoids Sort node entirely
CREATE INDEX idx_orders_status_created
ON orders(status, created_at);

-- Query: WHERE + ORDER BY + LIMIT — all served from index
SELECT * FROM orders
WHERE status = 'pending'
ORDER BY created_at
LIMIT 10;
-- Plan: Index Scan using idx_orders_status_created (no Sort node!)
```

### Rule 4: Avoid SELECT * — Fetch Only Needed Columns

```sql
-- BAD: fetches all columns including large JSONB, arrays
SELECT * FROM employees WHERE department = 'Engineering';

-- GOOD: fetch only what you need
SELECT id, name, salary FROM employees WHERE department = 'Engineering';

-- Even better with covering index:
CREATE INDEX idx_emp_dept_covering
ON employees(department)
INCLUDE (id, name, salary);
-- → Index Only Scan (no heap access at all)
```

### Rule 5: Push Filters Early with CTEs / Subqueries

```sql
-- BAD: join all rows then filter
SELECT e.name, o.amount
FROM employees e
JOIN orders o ON o.customer_id = e.id
WHERE e.department = 'Engineering'
  AND o.status = 'pending';

-- GOOD: filter in subquery before joining
SELECT e.name, o.amount
FROM (
    SELECT id, name FROM employees WHERE department = 'Engineering'
) e
JOIN (
    SELECT customer_id, amount FROM orders WHERE status = 'pending'
) o ON o.customer_id = e.id;
-- Planner often does this automatically, but explicit subqueries help in complex cases
```

### Rule 6: Use EXISTS Instead of COUNT for Existence Check

```sql
-- BAD: counts all matching rows just to check existence
SELECT COUNT(*) FROM orders WHERE customer_id = 1;  -- returns 150
-- Then application checks: if count > 0...

-- GOOD: stops at first match
SELECT EXISTS(SELECT 1 FROM orders WHERE customer_id = 1);
-- Stops after finding the first row — much faster
```

### Rule 7: Avoid OR on Different Columns — Use UNION ALL

```sql
-- BAD: OR on different indexed columns forces Seq Scan
SELECT * FROM orders WHERE status = 'pending' OR customer_id = 42;

-- GOOD: UNION ALL uses index for each branch separately
SELECT * FROM orders WHERE status = 'pending'
UNION ALL
SELECT * FROM orders WHERE customer_id = 42 AND status != 'pending';
```

### Rule 8: Use Proper Data Types

```sql
-- BAD: storing numbers as text → no numeric range index, implicit cast
CREATE TABLE logs (event_id TEXT);
SELECT * FROM logs WHERE event_id = 12345;   -- implicit cast, may break index

-- GOOD: use correct types
CREATE TABLE logs (event_id BIGINT);
SELECT * FROM logs WHERE event_id = 12345;   -- exact type match, uses index

-- BAD: using VARCHAR(255) everywhere
-- GOOD: use TEXT (no performance difference, no arbitrary limit)
```

---

## 15. VACUUM & ANALYZE

### Why VACUUM is Critical for Performance

PostgreSQL's MVCC creates **dead tuples** (old row versions) on every UPDATE and DELETE. Without VACUUM:
- Table bloat grows unboundedly
- Index bloat degrades performance
- Transaction ID wraparound causes database shutdown

```sql
-- Manual VACUUM
VACUUM employees;                   -- reclaim dead tuples, keep space in table
VACUUM ANALYZE employees;           -- also update statistics
VACUUM FULL employees;              -- reclaim space to OS (LOCKS TABLE!)
VACUUM VERBOSE ANALYZE employees;   -- show detailed output

-- ANALYZE only (update statistics without vacuuming)
ANALYZE employees;
ANALYZE employees (salary, department);   -- specific columns

-- Check vacuum health
SELECT
    relname,
    n_live_tup,
    n_dead_tup,
    ROUND(100.0 * n_dead_tup / NULLIF(n_live_tup + n_dead_tup, 0), 1) AS dead_pct,
    last_vacuum,
    last_autovacuum,
    last_analyze,
    last_autoanalyze
FROM pg_stat_user_tables
ORDER BY n_dead_tup DESC
LIMIT 20;

-- Check table bloat
SELECT
    tablename,
    pg_size_pretty(pg_total_relation_size(quote_ident(tablename))) AS total_size,
    pg_size_pretty(pg_relation_size(quote_ident(tablename)))       AS table_size,
    pg_size_pretty(pg_indexes_size(quote_ident(tablename)))        AS index_size
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY pg_total_relation_size(quote_ident(tablename)) DESC
LIMIT 20;
```

### Autovacuum Tuning (postgresql.conf)

```ini
# Enable autovacuum (default: on — never disable!)
autovacuum = on

# How many dead tuples trigger autovacuum
autovacuum_vacuum_scale_factor   = 0.02    -- default 0.2 (2%)
autovacuum_vacuum_threshold      = 50      -- minimum dead tuples before trigger

# How often analyze runs
autovacuum_analyze_scale_factor  = 0.01   -- default 0.1 (1%)
autovacuum_analyze_threshold     = 50

# For high-write tables: lower thresholds
-- Per-table override:
ALTER TABLE orders SET (
    autovacuum_vacuum_scale_factor = 0.01,
    autovacuum_analyze_scale_factor = 0.005
);

# Autovacuum workers
autovacuum_max_workers = 5     -- default 3
autovacuum_naptime     = 30s   -- how often autovacuum wakes up

# Prevent vacuum from being too disruptive
autovacuum_vacuum_cost_limit = 400   -- default 200 (higher = faster but more I/O)
autovacuum_vacuum_cost_delay = 2ms   -- default 2ms (pause between chunks)
```

### Transaction ID Wraparound Prevention

```sql
-- Monitor XID age — alert if > 1.5 billion (out of 2 billion max)
SELECT
    datname,
    age(datfrozenxid)          AS xid_age,
    2000000000 - age(datfrozenxid) AS xids_remaining
FROM pg_database
ORDER BY xid_age DESC;

-- Tables approaching wraparound
SELECT
    relname,
    age(relfrozenxid)          AS xid_age
FROM pg_class
WHERE relkind = 'r'
ORDER BY xid_age DESC
LIMIT 20;

-- VACUUM FREEZE prevents wraparound
VACUUM FREEZE employees;
```

---

## 16. Table Partitioning

Partitioning splits a large table into smaller physical pieces while appearing as a single table to queries. Can dramatically improve performance on large tables.

### Range Partitioning (by date)

```sql
-- Create partitioned parent table
CREATE TABLE orders_partitioned (
    id          BIGSERIAL,
    customer_id INTEGER,
    product     TEXT,
    amount      NUMERIC,
    status      TEXT,
    created_at  TIMESTAMPTZ NOT NULL
) PARTITION BY RANGE (created_at);

-- Create partitions (one per quarter)
CREATE TABLE orders_2024_q1 PARTITION OF orders_partitioned
    FOR VALUES FROM ('2024-01-01') TO ('2024-04-01');

CREATE TABLE orders_2024_q2 PARTITION OF orders_partitioned
    FOR VALUES FROM ('2024-04-01') TO ('2024-07-01');

CREATE TABLE orders_2024_q3 PARTITION OF orders_partitioned
    FOR VALUES FROM ('2024-07-01') TO ('2024-10-01');

CREATE TABLE orders_2024_q4 PARTITION OF orders_partitioned
    FOR VALUES FROM ('2024-10-01') TO ('2025-01-01');

-- Catch-all default partition
CREATE TABLE orders_default PARTITION OF orders_partitioned DEFAULT;

-- Indexes on partitions (automatically applied to all)
CREATE INDEX ON orders_partitioned(status);
CREATE INDEX ON orders_partitioned(customer_id);
CREATE INDEX ON orders_partitioned(created_at);

-- Query: partition pruning automatically skips irrelevant partitions
EXPLAIN SELECT * FROM orders_partitioned
WHERE created_at BETWEEN '2024-01-01' AND '2024-03-31';
-- Only scans orders_2024_q1 — other partitions pruned!
```

### List Partitioning (by category)

```sql
CREATE TABLE employees_by_dept (
    id          SERIAL,
    name        TEXT,
    salary      NUMERIC,
    department  TEXT NOT NULL
) PARTITION BY LIST (department);

CREATE TABLE employees_engineering PARTITION OF employees_by_dept
    FOR VALUES IN ('Engineering', 'DevOps', 'QA');

CREATE TABLE employees_business PARTITION OF employees_by_dept
    FOR VALUES IN ('Marketing', 'Sales', 'Finance');

CREATE TABLE employees_other PARTITION OF employees_by_dept DEFAULT;
```

### Hash Partitioning (even distribution)

```sql
CREATE TABLE sessions (
    id          BIGSERIAL,
    user_id     INTEGER NOT NULL,
    data        JSONB,
    created_at  TIMESTAMPTZ
) PARTITION BY HASH (user_id);

-- 4 partitions, evenly distributed by hash(user_id)
CREATE TABLE sessions_0 PARTITION OF sessions FOR VALUES WITH (MODULUS 4, REMAINDER 0);
CREATE TABLE sessions_1 PARTITION OF sessions FOR VALUES WITH (MODULUS 4, REMAINDER 1);
CREATE TABLE sessions_2 PARTITION OF sessions FOR VALUES WITH (MODULUS 4, REMAINDER 2);
CREATE TABLE sessions_3 PARTITION OF sessions FOR VALUES WITH (MODULUS 4, REMAINDER 3);
```

### Partition Maintenance

```sql
-- Drop old partition (instant — no DELETE needed)
DROP TABLE orders_2023_q1;

-- Detach partition for archiving
ALTER TABLE orders_partitioned DETACH PARTITION orders_2023_q1;

-- Attach existing table as partition
ALTER TABLE orders_partitioned
ATTACH PARTITION orders_archive
FOR VALUES FROM ('2023-01-01') TO ('2024-01-01');

-- Check partition info
SELECT
    inhrelid::regclass AS partition_name,
    pg_size_pretty(pg_relation_size(inhrelid)) AS size
FROM pg_inherits
WHERE inhparent = 'orders_partitioned'::regclass
ORDER BY partition_name;
```

---

## 17. Configuration Tuning

### Key Parameters (postgresql.conf)

```ini
# ─── Memory ─────────────────────────────────────────────────────────────────
shared_buffers          = 4GB       # 25% of RAM — PostgreSQL data cache
effective_cache_size    = 12GB      # 75% of RAM — OS + PG cache estimate
work_mem                = 64MB      # Per-operation sort/hash memory
                                    # CAREFUL: max_connections × work_mem
maintenance_work_mem    = 1GB       # VACUUM, CREATE INDEX, ALTER TABLE

# ─── I/O ─────────────────────────────────────────────────────────────────────
random_page_cost        = 1.1       # SSD: 1.1  HDD: 4.0 (default)
seq_page_cost           = 1.0       # Cost of sequential page read
effective_io_concurrency = 200      # SSD: 200   HDD: 2

# ─── Parallelism ─────────────────────────────────────────────────────────────
max_parallel_workers_per_gather    = 4   # parallel query workers
max_parallel_workers               = 8   # total parallel workers
max_parallel_maintenance_workers   = 4   # parallel index builds/vacuum
min_parallel_table_scan_size       = 8MB

# ─── WAL / Durability ────────────────────────────────────────────────────────
wal_buffers             = 64MB      # default: 1/32 of shared_buffers
synchronous_commit      = on        # off = faster, small data loss risk
checkpoint_completion_target = 0.9  # spread checkpoint I/O over 90% interval
max_wal_size            = 4GB       # WAL file ceiling

# ─── Query Planner ───────────────────────────────────────────────────────────
default_statistics_target = 200     # default 100; higher = better estimates
enable_seqscan          = on        # never disable permanently
enable_hashjoin         = on
enable_mergejoin        = on
jit                     = on        # JIT compilation for complex queries

# ─── Logging ─────────────────────────────────────────────────────────────────
log_min_duration_statement = 1000   # log queries over 1 second
log_lock_waits             = on     # log lock wait events
log_checkpoints            = on     # log checkpoint activity
log_autovacuum_min_duration = 250ms # log slow autovacuum runs
```

### Per-Table Storage Parameters

```sql
-- High-write table: more aggressive autovacuum
ALTER TABLE orders SET (
    autovacuum_vacuum_scale_factor   = 0.01,
    autovacuum_vacuum_threshold      = 100,
    autovacuum_analyze_scale_factor  = 0.005,
    autovacuum_analyze_threshold     = 100
);

-- Read-heavy static table: fillfactor for faster reads
-- fillfactor=100 means pack pages fully (no room for HOT updates)
ALTER TABLE reference_data SET (fillfactor = 100);

-- Update-heavy table: leave room for in-page updates (HOT)
ALTER TABLE sessions SET (fillfactor = 70);
-- 30% free space → UPDATE stays on same page → no index update needed
```

---

## 18. Performance Monitoring

```sql
-- ─── Slowest queries (requires pg_stat_statements extension) ──────────────
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

SELECT
    LEFT(query, 80)                             AS query_snippet,
    calls,
    ROUND(mean_exec_time::NUMERIC, 2)           AS avg_ms,
    ROUND(total_exec_time::NUMERIC, 2)          AS total_ms,
    ROUND(stddev_exec_time::NUMERIC, 2)         AS stddev_ms,
    rows
FROM pg_stat_statements
ORDER BY mean_exec_time DESC
LIMIT 20;

-- ─── Cache hit rates ──────────────────────────────────────────────────────
SELECT
    relname,
    heap_blks_hit,
    heap_blks_read,
    ROUND(100.0 * heap_blks_hit
          / NULLIF(heap_blks_hit + heap_blks_read, 0), 1) AS cache_hit_pct
FROM pg_statio_user_tables
ORDER BY heap_blks_read DESC
LIMIT 20;
-- Target: cache_hit_pct > 99%

-- ─── Index hit rates ─────────────────────────────────────────────────────
SELECT
    relname                                          AS table_name,
    indexrelname                                     AS index_name,
    idx_scan                                         AS times_used,
    pg_size_pretty(pg_relation_size(indexrelid))     AS index_size
FROM pg_stat_user_indexes
ORDER BY idx_scan DESC;

-- ─── Unused indexes (candidates for removal) ─────────────────────────────
SELECT
    schemaname,
    relname                                          AS table_name,
    indexrelname                                     AS index_name,
    idx_scan                                         AS scans,
    pg_size_pretty(pg_relation_size(indexrelid))     AS size
FROM pg_stat_user_indexes
WHERE idx_scan < 10
  AND indexrelname NOT LIKE 'pg_%'
ORDER BY pg_relation_size(indexrelid) DESC;

-- ─── Table sizes ─────────────────────────────────────────────────────────
SELECT
    tablename,
    pg_size_pretty(pg_relation_size(quote_ident(tablename)))       AS table,
    pg_size_pretty(pg_indexes_size(quote_ident(tablename)))        AS indexes,
    pg_size_pretty(pg_total_relation_size(quote_ident(tablename))) AS total
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY pg_total_relation_size(quote_ident(tablename)) DESC;

-- ─── Currently running queries ───────────────────────────────────────────
SELECT pid, usename, state,
       now() - query_start AS duration,
       LEFT(query, 100) AS query
FROM pg_stat_activity
WHERE state != 'idle'
ORDER BY duration DESC NULLS LAST;

-- ─── Seq Scan detector — tables that should have indexes ─────────────────
SELECT relname, seq_scan, idx_scan,
       ROUND(100.0 * seq_scan / NULLIF(seq_scan + idx_scan, 0), 1) AS seq_pct,
       n_live_tup
FROM pg_stat_user_tables
WHERE n_live_tup > 10000       -- only substantial tables
  AND seq_scan > idx_scan      -- more seq scans than index scans
ORDER BY seq_scan DESC;

-- ─── Bloat check ─────────────────────────────────────────────────────────
SELECT relname, n_dead_tup,
       ROUND(100.0 * n_dead_tup / NULLIF(n_live_tup + n_dead_tup, 0), 1) AS bloat_pct
FROM pg_stat_user_tables
WHERE n_dead_tup > 1000
ORDER BY n_dead_tup DESC;
```

---

## 19. Quick Reference Cheat Sheet

```
╔════════════════════╦══════════════════════════════════════════════════════╗
║ INDEX TYPE         ║ USE WHEN                                             ║
╠════════════════════╬══════════════════════════════════════════════════════╣
║ B-Tree (default)   ║ =  <  >  BETWEEN  LIKE 'x%'  ORDER BY  IS NULL      ║
║ Hash               ║ = only, high cardinality (UUIDs, emails)             ║
║ GIN                ║ Arrays, JSONB, full-text, LIKE '%x%' (pg_trgm)      ║
║ GiST               ║ Ranges, geometry, overlap, nearest-neighbor          ║
║ BRIN               ║ Huge ordered tables (time-series, logs)              ║
║ SP-GiST            ║ IP addresses, prefix trees, non-overlapping spaces   ║
╠════════════════════╬══════════════════════════════════════════════════════╣
║ INDEX STRATEGY     ║ RULE                                                 ║
╠════════════════════╬══════════════════════════════════════════════════════╣
║ Partial            ║ Index subset of rows: WHERE is_active = true         ║
║ Functional         ║ Index expression: LOWER(name), metadata->>'level'    ║
║ Composite          ║ Equality cols first, range/sort cols last            ║
║ Covering (INCLUDE) ║ Add extra columns to enable Index-Only Scan          ║
╠════════════════════╬══════════════════════════════════════════════════════╣
║ EXPLAIN SIGNALS    ║ MEANING / FIX                                        ║
╠════════════════════╬══════════════════════════════════════════════════════╣
║ Seq Scan (large)   ║ Missing index → CREATE INDEX                         ║
║ rows misestimate   ║ Stale stats → ANALYZE                                ║
║ Sort Batches > 1   ║ Sort spilling to disk → increase work_mem            ║
║ Hash Batches > 1   ║ Hash spilling to disk → increase work_mem            ║
║ Buffers read high  ║ Cold/small cache → increase shared_buffers           ║
║ Nested Loop huge   ║ Missing index on inner join column                   ║
╠════════════════════╬══════════════════════════════════════════════════════╣
║ QUERY RULES        ║ GUIDELINE                                            ║
╠════════════════════╬══════════════════════════════════════════════════════╣
║ Never wrap cols    ║ Keep indexed columns bare in WHERE                   ║
║ Use LIMIT          ║ Reduces work + enables index-friendly plans          ║
║ Use EXISTS         ║ Faster than COUNT(*) > 0 for existence checks        ║
║ Avoid SELECT *     ║ Fetch only needed columns (enables Index-Only Scans) ║
║ Match data types   ║ Mismatched types cause implicit casts → no index     ║
╠════════════════════╬══════════════════════════════════════════════════════╣
║ MAINTENANCE        ║ COMMAND                                              ║
╠════════════════════╬══════════════════════════════════════════════════════╣
║ Update stats       ║ ANALYZE table                                        ║
║ Reclaim space      ║ VACUUM table                                         ║
║ Reclaim to OS      ║ VACUUM FULL table  (locks!)                          ║
║ Prevent wraparound ║ VACUUM FREEZE table                                  ║
║ Fix bloated index  ║ REINDEX INDEX CONCURRENTLY idx_name                  ║
║ Non-blocking index ║ CREATE INDEX CONCURRENTLY                            ║
╚════════════════════╩══════════════════════════════════════════════════════╝
```

---

## Further Reading

- [PostgreSQL Docs — Indexes](https://www.postgresql.org/docs/current/indexes.html)
- [PostgreSQL Docs — Using EXPLAIN](https://www.postgresql.org/docs/current/using-explain.html)
- [PostgreSQL Docs — Routine Vacuuming](https://www.postgresql.org/docs/current/routine-vacuuming.html)
- [PostgreSQL Docs — Table Partitioning](https://www.postgresql.org/docs/current/ddl-partitioning.html)
- [PostgreSQL Docs — Server Configuration](https://www.postgresql.org/docs/current/runtime-config.html)
- [Use the Index, Luke](https://use-the-index-luke.com/) — Visual index guide
- [pgMustard](https://www.pgmustard.com) — EXPLAIN plan analyzer

---

*Generated with love for PostgreSQL engineers.*
