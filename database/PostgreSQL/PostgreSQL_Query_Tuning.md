# PostgreSQL — Query Tuning Complete Reference

> A deep-dive guide covering EXPLAIN analysis, statistics, planner hints, join strategies, index tuning, memory settings, rewrite patterns, and systematic slow-query diagnosis in PostgreSQL.

---

## Table of Contents

1. [Query Tuning Workflow](#1-query-tuning-workflow)
2. [Finding Slow Queries](#2-finding-slow-queries)
3. [EXPLAIN Deep Dive](#3-explain-deep-dive)
4. [Table & Column Statistics](#4-table--column-statistics)
5. [Planner Cost Model](#5-planner-cost-model)
6. [Scan Node Tuning](#6-scan-node-tuning)
7. [Join Tuning](#7-join-tuning)
8. [Sort & Aggregation Tuning](#8-sort--aggregation-tuning)
9. [Index Tuning for Queries](#9-index-tuning-for-queries)
10. [Memory Tuning](#10-memory-tuning)
11. [Query Rewrite Patterns](#11-query-rewrite-patterns)
12. [CTE & Subquery Tuning](#12-cte--subquery-tuning)
13. [Parallel Query Tuning](#13-parallel-query-tuning)
14. [Partition Pruning Tuning](#14-partition-pruning-tuning)
15. [Connection & Session Tuning](#15-connection--session-tuning)
16. [Systematic Diagnosis Checklist](#16-systematic-diagnosis-checklist)
17. [Quick Reference Cheat Sheet](#17-quick-reference-cheat-sheet)

---

## Sample Tables Used in All Examples

```sql
CREATE TABLE customers (
    id          SERIAL PRIMARY KEY,
    name        TEXT NOT NULL,
    email       TEXT UNIQUE,
    country     TEXT,
    segment     TEXT,
    created_at  DATE DEFAULT CURRENT_DATE
);

CREATE TABLE orders (
    id          BIGSERIAL PRIMARY KEY,
    customer_id INTEGER REFERENCES customers(id),
    product     TEXT,
    amount      NUMERIC(12,2),
    status      TEXT DEFAULT 'pending',
    region      TEXT,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE order_items (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT REFERENCES orders(id),
    product_id  INTEGER,
    qty         INTEGER,
    unit_price  NUMERIC(10,2)
);

-- Realistic data volumes
INSERT INTO customers (name, email, country, segment, created_at)
SELECT
    'Customer ' || i,
    'cust' || i || '@example.com',
    (ARRAY['India','US','UK','DE','JP'])[ceil(random()*5)::INT],
    (ARRAY['gold','silver','bronze'])[ceil(random()*3)::INT],
    CURRENT_DATE - (random()*365*3)::INT
FROM generate_series(1, 500000) i;

INSERT INTO orders (customer_id, product, amount, status, region, created_at)
SELECT
    (random()*499999+1)::INT,
    (ARRAY['Laptop','Phone','Monitor','Tablet','Headset'])[ceil(random()*5)::INT],
    (random()*100000)::NUMERIC(12,2),
    (ARRAY['pending','processing','shipped','delivered','cancelled'])[ceil(random()*5)::INT],
    (ARRAY['North','South','East','West'])[ceil(random()*4)::INT],
    NOW() - (random()*365*2 || ' days')::INTERVAL
FROM generate_series(1, 2000000) i;

INSERT INTO order_items (order_id, product_id, qty, unit_price)
SELECT
    (random()*1999999+1)::INT,
    (random()*99+1)::INT,
    (random()*10+1)::INT,
    (random()*10000)::NUMERIC(10,2)
FROM generate_series(1, 5000000) i;

ANALYZE customers;
ANALYZE orders;
ANALYZE order_items;
```

---

## 1. Query Tuning Workflow

Always follow a **systematic process** — never guess.

```
┌─────────────────────────────────────────────────────────────────┐
│                  QUERY TUNING WORKFLOW                          │
│                                                                 │
│  1. FIND        Identify slow queries (pg_stat_statements)      │
│       ↓                                                         │
│  2. BASELINE    Record current execution time + plan            │
│       ↓                                                         │
│  3. EXPLAIN     Read EXPLAIN (ANALYZE, BUFFERS) output          │
│       ↓                                                         │
│  4. DIAGNOSE    Find the bottleneck (scan? join? sort? memory?) │
│       ↓                                                         │
│  5. HYPOTHESIZE Choose one fix at a time                        │
│       ↓                                                         │
│  6. TEST        Apply fix, measure improvement                  │
│       ↓                                                         │
│  7. VERIFY      Re-run EXPLAIN, compare plans                   │
│       ↓                                                         │
│  8. MONITOR     Watch query in production over time             │
└─────────────────────────────────────────────────────────────────┘
```

### Golden Rules

```
Rule 1:  Measure before and after EVERY change
Rule 2:  Change ONE thing at a time
Rule 3:  Read the EXPLAIN plan before touching indexes
Rule 4:  Never use SET enable_seqscan = off in production
Rule 5:  Stale statistics cause more problems than missing indexes
Rule 6:  work_mem affects more than you think — set it carefully
Rule 7:  A query rewrite often beats any index
Rule 8:  The planner is usually right — understand before overriding
```

---

## 2. Finding Slow Queries

### Enable pg_stat_statements

```sql
-- postgresql.conf
-- shared_preload_libraries = 'pg_stat_statements'

-- Enable extension
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Reset statistics (start fresh)
SELECT pg_stat_statements_reset();
```

### Top Slow Queries by Mean Time

```sql
-- Top 20 slowest queries by average execution time
SELECT
    LEFT(query, 100)                            AS query_snippet,
    calls,
    ROUND(mean_exec_time::NUMERIC, 2)           AS avg_ms,
    ROUND(max_exec_time::NUMERIC, 2)            AS max_ms,
    ROUND(stddev_exec_time::NUMERIC, 2)         AS stddev_ms,
    ROUND(total_exec_time::NUMERIC, 2)          AS total_ms,
    rows,
    ROUND(rows::NUMERIC / NULLIF(calls,0), 1)   AS avg_rows,
    shared_blks_hit,
    shared_blks_read,
    ROUND(100.0 * shared_blks_hit
          / NULLIF(shared_blks_hit + shared_blks_read, 0), 1) AS cache_hit_pct
FROM pg_stat_statements
WHERE calls > 10
ORDER BY mean_exec_time DESC
LIMIT 20;
```

### Top Queries by Total Time (Most Impactful)

```sql
-- These consume the most cumulative server time
SELECT
    LEFT(query, 100)                            AS query_snippet,
    calls,
    ROUND(total_exec_time::NUMERIC, 0)          AS total_ms,
    ROUND(mean_exec_time::NUMERIC, 2)           AS avg_ms,
    ROUND(100.0 * total_exec_time
          / SUM(total_exec_time) OVER (), 1)    AS pct_of_total
FROM pg_stat_statements
WHERE calls > 5
ORDER BY total_exec_time DESC
LIMIT 20;
```

### Queries with Most Cache Misses (I/O Bound)

```sql
SELECT
    LEFT(query, 100)                            AS query_snippet,
    calls,
    shared_blks_read                            AS disk_reads,
    shared_blks_hit                             AS cache_hits,
    ROUND(mean_exec_time::NUMERIC, 2)           AS avg_ms,
    ROUND(100.0 * shared_blks_hit
          / NULLIF(shared_blks_hit + shared_blks_read, 0), 1) AS cache_hit_pct
FROM pg_stat_statements
WHERE shared_blks_read > 1000
ORDER BY shared_blks_read DESC
LIMIT 20;
```

### High Row-Estimate Error Queries (Bad Statistics)

```sql
-- Queries where estimates are wildly off → stale stats → run ANALYZE
SELECT
    LEFT(query, 100)                            AS query_snippet,
    calls,
    rows                                        AS actual_rows,
    ROUND(mean_exec_time::NUMERIC, 2)           AS avg_ms
FROM pg_stat_statements
WHERE calls > 10
  AND rows / NULLIF(calls, 0) > 100000   -- returns many rows on average
ORDER BY mean_exec_time DESC
LIMIT 10;
```

### Slow Queries Right Now

```sql
-- Queries running longer than 5 seconds at this moment
SELECT
    pid,
    usename,
    now() - query_start                        AS running_for,
    state,
    wait_event_type,
    wait_event,
    LEFT(query, 120)                            AS query
FROM pg_stat_activity
WHERE state  = 'active'
  AND query_start < NOW() - INTERVAL '5 seconds'
  AND query NOT LIKE '%pg_stat_activity%'
ORDER BY running_for DESC;
```

---

## 3. EXPLAIN Deep Dive

### EXPLAIN Options

```sql
-- Basic plan (no execution, estimates only)
EXPLAIN SELECT * FROM orders WHERE status = 'pending';

-- Full analysis — USE THIS for tuning
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT * FROM orders WHERE status = 'pending';

-- All options
EXPLAIN (
    ANALYZE   true,   -- actually execute, show real times
    BUFFERS   true,   -- show buffer/disk hit counts
    VERBOSE   true,   -- show column lists, schemas
    COSTS     true,   -- show cost estimates (default)
    TIMING    true,   -- per-node actual time
    SUMMARY   true,   -- planning + execution time summary
    FORMAT    TEXT    -- TEXT, JSON, XML, YAML
)
SELECT * FROM orders
WHERE customer_id = 42
  AND status = 'pending';
```

### Reading an EXPLAIN Plan

```
Hash Join  (cost=18425.00..48234.50 rows=1243 width=96)
           (actual time=120.3..245.8 rows=1198 loops=1)
  │         ↑        ↑        ↑     ↑      ↑      ↑
  │      startup  total   est.    est.  actual actual
  │       cost    cost    rows   width  rows  loops
  │
  │  Buffers: shared hit=5423 read=1891 dirtied=0 written=0
  │                        ↑          ↑
  │                   cache hits   disk reads
  │
  ├── Hash Cond: (orders.customer_id = customers.id)
  │
  ├── Seq Scan on orders  (cost=0.00..46832.00 rows=2000000 width=60)
  │     Filter: (status = 'pending')
  │     Rows Removed by Filter: 1598217    ← how many rows filtered out
  │
  └── Hash  (cost=8213.00..8213.00 rows=500000 width=36)
        Buckets: 524288  Batches: 1  Memory Usage: 28672kB
        └── Seq Scan on customers  (cost=0.00..8213.00 rows=500000 width=36)
```

### Key Metrics to Read First

```sql
-- 1. Plan vs Actual rows — large mismatch = bad statistics
--    Plan: rows=10  Actual: rows=500000  → ANALYZE needed

-- 2. Execution time of each node (ANALYZE mode)
--    Find the node consuming the most time

-- 3. Buffers: shared read > 0 means disk I/O
--    Buffers: shared hit = 100%  → data in cache (good)
--    Buffers: shared read = many → cache misses (bad)

-- 4. Loops count
--    loops=50000 means this node ran 50000 times
--    Multiply cost/rows by loops for true total

-- 5. Sort Method
--    "Sort Method: quicksort  Memory: 4096kB"   → in memory (good)
--    "Sort Method: external merge  Disk: 245kB"  → spilled to disk (bad)

-- 6. Hash Batches
--    "Batches: 1"   → hash table fits in work_mem (good)
--    "Batches: 8"   → hash table spilled to disk  (bad)
```

### EXPLAIN JSON for Tooling

```sql
-- JSON format for use with explain.depesz.com or pgMustard
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT o.id, o.amount, c.name
FROM orders o
JOIN customers c ON c.id = o.customer_id
WHERE o.status = 'pending'
  AND o.created_at > NOW() - INTERVAL '30 days';
```

### Compare Two Plans

```sql
-- Save plans for comparison
-- Before optimization:
\o /tmp/plan_before.txt
EXPLAIN (ANALYZE, BUFFERS) <your query>;
\o

-- After adding index:
\o /tmp/plan_after.txt
EXPLAIN (ANALYZE, BUFFERS) <your query>;
\o

-- Compare with diff
-- diff /tmp/plan_before.txt /tmp/plan_after.txt
```

---

## 4. Table & Column Statistics

The query planner relies entirely on **statistics** to choose plans. Stale or insufficient statistics cause bad plans.

### View Current Statistics

```sql
-- Per-column statistics
SELECT
    attname                             AS column_name,
    n_distinct,                         -- estimated distinct values (-1 = all unique)
    correlation,                        -- physical ordering: 1=sorted, 0=random
    most_common_vals,                   -- top values
    most_common_freqs,                  -- frequency of top values
    histogram_bounds                    -- value distribution
FROM pg_stats
WHERE tablename = 'orders'
ORDER BY attname;
```

### n_distinct — Cardinality Estimation

```sql
-- n_distinct interpretation:
-- n_distinct =  500    → planner estimates 500 distinct values
-- n_distinct = -0.5    → planner estimates 50% of rows are distinct
-- n_distinct = -1      → all values are unique (like a PK)

-- Check if estimates are accurate:
SELECT
    attname,
    n_distinct                          AS stats_distinct,
    (SELECT COUNT(DISTINCT orders.status)
     FROM orders)                       AS actual_distinct
FROM pg_stats
WHERE tablename = 'orders'
  AND attname = 'status';
```

### correlation — Physical Order

```sql
-- correlation = 1.0   → column values are perfectly sorted on disk
--                       → Index Scan is cheap (few page jumps)
-- correlation = 0.0   → column values are completely random on disk
--                       → Index Scan visits many pages (expensive)
--                       → Planner may prefer Seq Scan + BRIN won't help

SELECT attname, correlation
FROM pg_stats
WHERE tablename = 'orders'
ORDER BY ABS(correlation) DESC;

-- created_at: correlation ≈ 0.99  → BRIN index very effective
-- customer_id: correlation ≈ 0.01 → random, BRIN useless, need B-tree
```

### Increase Statistics Target

```sql
-- Default statistics_target = 100 (collects 100 histogram buckets)
-- Low cardinality or skewed data needs higher target

-- Global setting (all new columns)
ALTER SYSTEM SET default_statistics_target = 200;
SELECT pg_reload_conf();

-- Per-column (for important filter/join columns)
ALTER TABLE orders
    ALTER COLUMN status          SET STATISTICS 500;
ALTER TABLE orders
    ALTER COLUMN customer_id     SET STATISTICS 500;
ALTER TABLE orders
    ALTER COLUMN created_at      SET STATISTICS 1000;

-- Then run ANALYZE to collect with new target
ANALYZE orders;

-- Verify improvement in histogram_bounds:
SELECT array_length(histogram_bounds, 1) AS buckets
FROM pg_stats
WHERE tablename = 'orders' AND attname = 'created_at';
-- Now returns 1000 buckets instead of 100
```

### Extended Statistics (Multi-Column Correlations)

```sql
-- Problem: planner assumes columns are independent
-- If WHERE status = 'pending' AND region = 'North' always go together,
-- the planner underestimates selectivity → wrong plan

-- Solution: extended statistics tracks correlations between columns
CREATE STATISTICS stat_orders_status_region
    (ndistinct, dependencies, mcv)      -- ndistinct=combined, dependencies=functional
ON status, region
FROM orders;

-- Run ANALYZE to populate
ANALYZE orders;

-- View extended statistics
SELECT stxname, stxkeys, stxkind
FROM pg_statistic_ext
WHERE stxrelid = 'orders'::regclass;

-- Check if it's being used:
-- EXPLAIN output will reference it in "Statistics objects"
EXPLAIN SELECT * FROM orders
WHERE status = 'pending' AND region = 'North';
```

### ANALYZE Strategies

```sql
-- Manual ANALYZE (after bulk load or major updates)
ANALYZE orders;
ANALYZE orders (status, customer_id, created_at);  -- specific columns only

-- ANALYZE VERBOSE (see what was collected)
ANALYZE VERBOSE orders;

-- Check when last analyzed
SELECT relname, last_analyze, last_autoanalyze, n_mod_since_analyze
FROM pg_stat_user_tables
WHERE relname IN ('orders', 'customers', 'order_items')
ORDER BY n_mod_since_analyze DESC;

-- Tables that need ANALYZE urgently
SELECT relname, n_mod_since_analyze, n_live_tup,
       ROUND(100.0 * n_mod_since_analyze / NULLIF(n_live_tup,0), 1) AS mod_pct
FROM pg_stat_user_tables
WHERE n_mod_since_analyze > 0
ORDER BY mod_pct DESC
LIMIT 20;
```

---

## 5. Planner Cost Model

PostgreSQL uses a **cost model** to compare plans. Every operation has a cost. The planner picks the lowest-cost plan.

### Cost Units

```
seq_page_cost    = 1.0   (cost per sequential page read)
random_page_cost = 4.0   (cost per random page read — HDD default)
cpu_tuple_cost   = 0.01  (cost per row processed)
cpu_index_tuple_cost = 0.005  (cost per index entry)
cpu_operator_cost    = 0.0025 (cost per operator evaluation)

For SSD: random_page_cost ≈ 1.1 (random reads nearly as fast as sequential)
```

### Tune Cost Parameters for Your Storage

```sql
-- Check current settings
SHOW random_page_cost;
SHOW seq_page_cost;
SHOW effective_cache_size;

-- For SSD storage (most common today)
ALTER SYSTEM SET random_page_cost    = 1.1;
ALTER SYSTEM SET seq_page_cost       = 1.0;
ALTER SYSTEM SET effective_cache_size = 12GB;  -- 75% of RAM
SELECT pg_reload_conf();

-- For HDD storage
ALTER SYSTEM SET random_page_cost    = 4.0;
ALTER SYSTEM SET seq_page_cost       = 1.0;

-- For in-memory datasets (all data fits in shared_buffers + OS cache)
ALTER SYSTEM SET random_page_cost    = 1.0;
ALTER SYSTEM SET effective_cache_size = 32GB;  -- high = encourages index scans
```

### How the Planner Uses Costs

```sql
-- The planner computes estimated cost for each plan alternative:

-- Plan A: Seq Scan (scan all 2M rows, filter to 1000)
-- Cost = pages * seq_page_cost + rows * cpu_tuple_cost
-- Cost = 18000 * 1.0 + 2000000 * 0.01 = 38000

-- Plan B: Index Scan (use index, fetch 1000 rows directly)
-- Cost = index_pages * random_page_cost + result_rows * random_page_cost
-- Cost = 3 * 1.1 + 1000 * 1.1 = 1103.3

-- Planner chooses Plan B (lower cost) → Index Scan
```

### effective_cache_size Matters

```sql
-- effective_cache_size tells the planner how much memory is available
-- for caching (shared_buffers + OS page cache)
-- It does NOT allocate memory — it's just a hint to the cost model

-- Low effective_cache_size → planner thinks data won't be in cache
--                          → random_page_cost assumed always
--                          → discourages index scans

-- High effective_cache_size → planner thinks data will be cached
--                           → reduces effective random_page_cost
--                           → encourages index scans

-- Check your RAM and set accordingly:
-- RAM=16GB: effective_cache_size = 12GB
-- RAM=64GB: effective_cache_size = 48GB
-- RAM=256GB: effective_cache_size = 192GB
```

---

## 6. Scan Node Tuning

### Sequential Scan (Seq Scan)

A Seq Scan reads **every page** of the table.

```sql
-- When a Seq Scan is correct:
-- 1. No usable index exists
-- 2. Query returns > ~10-15% of rows (index overhead not worth it)
-- 3. Table is tiny (fits in 1-2 pages)
-- 4. Table is in cache, seq scan is fast

-- When Seq Scan is a problem:
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM orders WHERE status = 'pending';
-- Seq Scan on orders  (actual rows=400042 loops=1)
--   Filter: (status = 'pending')
--   Rows Removed by Filter: 1599958  ← scanning 2M rows to get 400K

-- Fix: create index if < 10% of rows match
CREATE INDEX idx_orders_status ON orders(status)
WHERE status = 'pending';   -- partial index = much smaller

-- But if status='pending' = 20% of 2M rows = 400K rows
-- Seq Scan may actually be faster than Index Scan for 400K rows!
-- Test both and compare execution time
```

### Index Scan vs Bitmap Index Scan

```sql
-- Index Scan: random access for EACH matching row
-- Good when: few rows, high correlation, LIMIT applied

-- Bitmap Index Scan: build bitmap, sort page accesses, read sequentially
-- Good when: moderate rows (1000-100000), low correlation

EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM orders
WHERE created_at > NOW() - INTERVAL '7 days';

-- Index Scan  → good if 7 days = small fraction of 2M rows
-- Bitmap Heap Scan → good if 7 days = ~50K rows spread randomly

-- Force Bitmap scan to see if faster (testing only):
SET enable_indexscan = off;
EXPLAIN (ANALYZE, BUFFERS) SELECT ...;
SET enable_indexscan = on;
```

### Index Only Scan

```sql
-- Fastest scan: no heap access at all
-- Requires: all needed columns in the index

-- Create covering index
CREATE INDEX idx_orders_status_covering
ON orders(status)
INCLUDE (amount, customer_id, created_at);

EXPLAIN (ANALYZE, BUFFERS)
SELECT amount, customer_id, created_at
FROM orders
WHERE status = 'shipped';
-- Index Only Scan → Heap Fetches: 0  (perfect!)

-- If Heap Fetches > 0, run VACUUM to update visibility map:
VACUUM orders;
EXPLAIN (ANALYZE, BUFFERS)
SELECT amount, customer_id, created_at
FROM orders WHERE status = 'shipped';
-- After VACUUM: Heap Fetches: 0
```

### Seq Scan on Large Table — Force Index for Testing

```sql
-- NEVER use these in production — testing only!
SET enable_seqscan = off;           -- forces index use
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM orders WHERE status = 'pending';
-- See: Index Scan used instead — compare times

SET enable_seqscan = on;            -- restore
```

---

## 7. Join Tuning

### Nested Loop Join

```
For each row in outer:
    scan inner for matching rows

Cost: O(outer_rows × inner_scan_cost)
Best when:
  - Outer set is very small (< 100 rows after filtering)
  - Inner table has an index on join column
  - LIMIT applied (can stop early)
```

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT c.name, COUNT(o.id)
FROM customers c
JOIN orders o ON o.customer_id = c.id
WHERE c.country = 'India'
GROUP BY c.name;

-- If plan shows Nested Loop with large outer set → needs index
-- Inner table MUST have index on join column:
CREATE INDEX idx_orders_customer_id ON orders(customer_id);
-- Now Nested Loop is efficient: index lookup per outer row
```

### Hash Join

```
Build hash table from smaller side.
Probe hash table with each row from larger side.

Cost: O(inner_rows + outer_rows) with hash table in work_mem
Best when:
  - No useful indexes on join columns
  - Joining large tables
  - hash table fits in work_mem
```

```sql
-- Hash Join spilling to disk (bad):
EXPLAIN (ANALYZE, BUFFERS)
SELECT o.id, oi.qty
FROM orders o
JOIN order_items oi ON oi.order_id = o.id;
-- Hash Batches: 8  ← spilling, needs more work_mem

-- Fix: increase work_mem for this session
SET work_mem = '256MB';
EXPLAIN (ANALYZE, BUFFERS)
SELECT o.id, oi.qty
FROM orders o
JOIN order_items oi ON oi.order_id = o.id;
-- Hash Batches: 1  ← fits in memory now
```

### Merge Join

```
Both sides must be sorted on join key.
Merges two sorted streams simultaneously.

Cost: O((outer + inner) × log(N)) for sort step
Best when:
  - Both sides already sorted (index provides order)
  - Large equi-joins
  - No good hash join due to memory limits
```

```sql
-- Encourage merge join (sorted inputs available):
CREATE INDEX idx_orders_cust_id      ON orders(customer_id);
CREATE INDEX idx_customers_id        ON customers(id);

-- Merge Join will be chosen if both sides provide sorted output:
EXPLAIN SELECT c.name, o.amount
FROM customers c
JOIN orders o ON o.customer_id = c.id
ORDER BY c.id;
-- Merge Join  (cost=...)  ← both sides sorted on id
```

### Join Order Tuning

```sql
-- PostgreSQL tries all join orders for ≤ join_collapse_limit tables (default 8)
SHOW join_collapse_limit;     -- 8

-- For > 8 tables, use GEQO (genetic query optimizer)
SHOW geqo_threshold;          -- 12

-- If the planner picks a bad join order:
-- 1. First check statistics (ANALYZE all tables)
-- 2. Check for index on all join columns
-- 3. Increase statistics target for join columns

-- Control join order explicitly (set = 1 to fix order as written):
SET join_collapse_limit = 1;   -- respect FROM clause order exactly
EXPLAIN SELECT ...;
SET join_collapse_limit = 8;   -- restore

-- Rewrite join order to hint at preferred strategy:
-- Put smaller/more-filtered table first in FROM clause
SELECT /*+ Leading(c o oi) */ ...  -- pg_hint_plan extension
```

### Small Result Set Anti-Pattern

```sql
-- BAD: Join entire orders table, then filter
SELECT o.amount, c.name
FROM orders o
JOIN customers c ON c.id = o.customer_id
WHERE c.country = 'India'
  AND c.segment = 'gold';

-- GOOD: Filter customers first (much smaller), then join
SELECT o.amount, c.name
FROM (
    SELECT id, name FROM customers
    WHERE country = 'India' AND segment = 'gold'
) c
JOIN orders o ON o.customer_id = c.id;
-- Planner usually does this automatically, but explicit helps complex cases
```

---

## 8. Sort & Aggregation Tuning

### Sort Tuning

```sql
-- Sort spilling to disk:
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM orders ORDER BY amount DESC;
-- Sort  (cost=...) (actual time=8432..)
--   Sort Key: amount DESC
--   Sort Method: external merge  Disk: 78MB    ← PROBLEM

-- Fix 1: Increase work_mem
SET work_mem = '256MB';
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM orders ORDER BY amount DESC;
-- Sort Method: quicksort  Memory: 185000kB    ← in memory now

-- Fix 2: Add index to avoid Sort node entirely
CREATE INDEX idx_orders_amount_desc ON orders(amount DESC);
EXPLAIN SELECT * FROM orders ORDER BY amount DESC LIMIT 100;
-- Index Scan Backward → no Sort node at all!

-- Fix 3: Index with WHERE clause for selective sorts
CREATE INDEX idx_orders_pending_amount ON orders(amount DESC)
WHERE status = 'pending';
EXPLAIN SELECT * FROM orders
WHERE status = 'pending'
ORDER BY amount DESC
LIMIT 10;
-- Index Scan → no Sort, no filter
```

### GROUP BY Tuning

```sql
-- HashAggregate (default for most GROUP BY)
EXPLAIN (ANALYZE, BUFFERS)
SELECT status, COUNT(*), SUM(amount)
FROM orders
GROUP BY status;
-- HashAggregate  (actual time=...)
--   Group Key: status
--   Batches: 1  Memory Usage: 40kB  ← fine

-- HashAggregate spilling to disk:
-- Batches: 4  ← spilling to disk, increase work_mem

-- GroupAggregate (sorted input — needs sorted data or index)
SET enable_hashagg = off;
EXPLAIN SELECT status, COUNT(*) FROM orders GROUP BY status;
-- GroupAggregate  → Sort node appears above table scan
SET enable_hashagg = on;

-- When index makes GroupAggregate faster:
CREATE INDEX idx_orders_status_amount ON orders(status, amount);
-- Enables Index Scan + GroupAggregate without Sort node
EXPLAIN SELECT status, SUM(amount) FROM orders GROUP BY status;
```

### DISTINCT Tuning

```sql
-- DISTINCT often causes Sort + Unique nodes:
EXPLAIN SELECT DISTINCT status FROM orders;
-- Sort + Unique  → expensive for large tables

-- Fix: index scan provides sorted output, avoids explicit Sort:
CREATE INDEX idx_orders_status ON orders(status);
EXPLAIN SELECT DISTINCT status FROM orders;
-- Index Only Scan + Unique  → much cheaper

-- Or: use GROUP BY (often faster than DISTINCT)
SELECT status FROM orders GROUP BY status;
-- vs
SELECT DISTINCT status FROM orders;
-- Both logical equivalent; GROUP BY usually picks HashAggregate
```

### Window Function Tuning

```sql
-- Window functions require Sort if partition/order not indexed
EXPLAIN (ANALYZE, BUFFERS)
SELECT customer_id, amount,
       RANK() OVER (PARTITION BY customer_id ORDER BY amount DESC)
FROM orders;
-- Sort  (cost=high...)  ← expensive for 2M rows

-- Fix: index on (customer_id, amount DESC) satisfies window ORDER BY
CREATE INDEX idx_orders_cust_amount ON orders(customer_id, amount DESC);
EXPLAIN SELECT customer_id, amount,
       RANK() OVER (PARTITION BY customer_id ORDER BY amount DESC)
FROM orders;
-- May now use Index Scan avoiding Sort node

-- Limit window function scope to reduce sort size
WITH recent AS (
    SELECT * FROM orders
    WHERE created_at > NOW() - INTERVAL '30 days'  -- filter BEFORE window
)
SELECT customer_id, amount,
       RANK() OVER (PARTITION BY customer_id ORDER BY amount DESC)
FROM recent;
```

---

## 9. Index Tuning for Queries

### Identify Missing Indexes

```sql
-- Tables with many sequential scans on large tables
SELECT
    relname                             AS table_name,
    seq_scan,
    idx_scan,
    n_live_tup                          AS live_rows,
    ROUND(seq_scan::NUMERIC
          / NULLIF(seq_scan+idx_scan,0)*100, 1) AS seq_pct
FROM pg_stat_user_tables
WHERE n_live_tup > 100000
  AND seq_scan > idx_scan
ORDER BY seq_scan DESC
LIMIT 20;
```

### Index Selectivity Check

```sql
-- Before creating an index, measure selectivity
-- Low selectivity (few distinct values) = poor index candidates
SELECT
    status,
    COUNT(*)                                        AS row_count,
    ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER(), 1) AS pct
FROM orders
GROUP BY status
ORDER BY row_count DESC;

-- status='pending'   = 20% of rows  → index marginally useful
-- status='delivered' = 60% of rows  → Seq Scan faster for this value
-- status='cancelled' = 2%  of rows  → index very effective

-- Solution: partial index for rare values only
CREATE INDEX idx_orders_cancelled ON orders(created_at)
WHERE status = 'cancelled';   -- index only 2% of rows
```

### Multi-Column Index Column Order

```sql
-- Query pattern:
SELECT * FROM orders
WHERE customer_id = 42          -- equality predicate
  AND status = 'pending'        -- equality predicate
  AND created_at > '2024-01-01' -- range predicate
ORDER BY amount DESC;           -- sort

-- Index column order rules:
-- 1. Equality columns first (any order among equals)
-- 2. Range column next
-- 3. Sort column last (if matches ORDER BY direction)

-- Best index for this query:
CREATE INDEX idx_orders_tuned
ON orders(customer_id, status, created_at, amount DESC);

-- Verify it's used:
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM orders
WHERE customer_id = 42
  AND status = 'pending'
  AND created_at > '2024-01-01'
ORDER BY amount DESC;
-- Index Scan using idx_orders_tuned (no Sort node!)
```

### Unused Index Detection

```sql
-- Indexes wasting space and slowing writes
SELECT
    schemaname,
    relname                             AS table_name,
    indexrelname                        AS index_name,
    idx_scan                            AS times_used,
    pg_size_pretty(pg_relation_size(indexrelid)) AS index_size,
    indexdef
FROM pg_stat_user_indexes
JOIN pg_indexes ON indexrelname = indexname
                AND relname = tablename
WHERE idx_scan < 50
  AND indexrelname NOT LIKE '%pkey%'
  AND indexrelname NOT LIKE '%unique%'
ORDER BY pg_relation_size(indexrelid) DESC
LIMIT 20;

-- WARNING: idx_scan resets on pg_stat_reset() or server restart
-- Only drop indexes that have been 0 for months
-- Check pg_stat_user_indexes.relname creation date too
```

### Index Bloat Check

```sql
-- Bloated indexes waste space and slow queries
SELECT
    indexrelname,
    pg_size_pretty(pg_relation_size(indexrelid)) AS index_size,
    idx_scan,
    idx_tup_read,
    idx_tup_fetch
FROM pg_stat_user_indexes
WHERE relname = 'orders'
ORDER BY pg_relation_size(indexrelid) DESC;

-- Rebuild bloated index (non-blocking)
REINDEX INDEX CONCURRENTLY idx_orders_status;
```

---

## 10. Memory Tuning

### work_mem — Per-Operation Memory

```sql
-- work_mem is allocated PER SORT/HASH operation PER connection
-- Total memory = max_connections × sorts_per_query × work_mem
-- Be careful! Too high = OOM killer

SHOW work_mem;              -- default: 4MB (too low for most workloads)

-- Check if sorts/hashes are spilling to disk:
SELECT query, temp_blks_read, temp_blks_written
FROM pg_stat_statements
WHERE temp_blks_written > 0
ORDER BY temp_blks_written DESC;

-- Set globally (careful with total memory)
ALTER SYSTEM SET work_mem = '64MB';

-- Set per session for one heavy query
SET work_mem = '256MB';
EXPLAIN (ANALYZE, BUFFERS) SELECT ...;  -- no more spill
RESET work_mem;

-- Set per role for reporting users
ALTER ROLE reporting_user SET work_mem = '256MB';

-- Estimate work_mem needed:
-- Look at Sort/Hash node: "Disk: 45MB"
-- Set work_mem > 45MB to keep in memory
-- "external merge  Disk: 45MB" → need work_mem > 45MB
```

### shared_buffers — Shared Page Cache

```sql
-- shared_buffers = PostgreSQL's internal page cache
-- Too small: everything goes to OS cache (still works, but less efficient)
-- Recommended: 25% of RAM

SHOW shared_buffers;    -- default: 128MB (way too small!)

-- In postgresql.conf:
-- RAM=16GB:  shared_buffers = 4GB
-- RAM=64GB:  shared_buffers = 16GB
-- RAM=256GB: shared_buffers = 64GB

-- Check cache hit ratio (target > 99%)
SELECT
    sum(heap_blks_hit)  AS heap_hit,
    sum(heap_blks_read) AS heap_read,
    ROUND(100.0 * sum(heap_blks_hit)
          / NULLIF(sum(heap_blks_hit) + sum(heap_blks_read), 0), 2) AS cache_hit_pct
FROM pg_statio_user_tables;

-- Per-table cache hit ratio
SELECT relname,
       heap_blks_hit,
       heap_blks_read,
       ROUND(100.0 * heap_blks_hit
             / NULLIF(heap_blks_hit + heap_blks_read, 0), 1) AS cache_pct
FROM pg_statio_user_tables
ORDER BY heap_blks_read DESC
LIMIT 20;
```

### temp_buffers — Temporary Table Cache

```sql
-- temp_buffers: memory for temporary tables per session
SHOW temp_buffers;      -- default: 8MB

-- Increase for sessions using large temp tables
SET temp_buffers = '64MB';
CREATE TEMP TABLE t AS SELECT * FROM orders WHERE status = 'pending';
-- Now temp table is cached in memory
```

### maintenance_work_mem — Index & VACUUM Memory

```sql
-- Used by: CREATE INDEX, VACUUM, ALTER TABLE, CLUSTER
SHOW maintenance_work_mem;     -- default: 64MB

-- Increase for faster index builds and vacuums
ALTER SYSTEM SET maintenance_work_mem = '1GB';

-- Per session for a big index build
SET maintenance_work_mem = '4GB';
CREATE INDEX CONCURRENTLY idx_order_items_order ON order_items(order_id);
RESET maintenance_work_mem;
```

---

## 11. Query Rewrite Patterns

### Pattern 1: EXISTS Instead of IN (for subqueries)

```sql
-- SLOW: IN with subquery — may execute subquery once per row
SELECT id, name
FROM customers
WHERE id IN (
    SELECT customer_id FROM orders WHERE status = 'pending'
);

-- FASTER: EXISTS — stops at first match
SELECT id, name
FROM customers c
WHERE EXISTS (
    SELECT 1 FROM orders o
    WHERE o.customer_id = c.id
      AND o.status = 'pending'
);

-- ALSO FAST: Semi-join rewrite (modern PG usually optimizes IN → EXISTS internally)
-- But EXISTS is explicit and clearer about intent
```

### Pattern 2: Anti-Join Instead of NOT IN

```sql
-- DANGEROUS: NOT IN fails silently with NULLs
SELECT id FROM customers
WHERE id NOT IN (SELECT customer_id FROM orders);
-- If ANY customer_id in orders is NULL → returns 0 rows! Bug!

-- SAFE & FASTER: NOT EXISTS
SELECT c.id
FROM customers c
WHERE NOT EXISTS (
    SELECT 1 FROM orders o WHERE o.customer_id = c.id
);

-- ALSO FAST: LEFT JOIN + IS NULL (anti-join)
SELECT c.id
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id
WHERE o.id IS NULL;
```

### Pattern 3: Avoid Functions on Indexed Columns

```sql
-- SLOW: function wraps indexed column → index bypassed
SELECT * FROM orders WHERE DATE(created_at) = '2024-03-15';
SELECT * FROM customers WHERE LOWER(email) = 'alice@example.com';
SELECT * FROM orders WHERE EXTRACT(YEAR FROM created_at) = 2024;

-- FAST: rewrite to use bare column with range
SELECT * FROM orders
WHERE created_at >= '2024-03-15' AND created_at < '2024-03-16';

SELECT * FROM customers WHERE email = 'alice@example.com';
-- Or create functional index:
CREATE INDEX idx_cust_email_lower ON customers(LOWER(email));
SELECT * FROM customers WHERE LOWER(email) = 'alice@example.com';

SELECT * FROM orders
WHERE created_at >= '2024-01-01' AND created_at < '2025-01-01';
```

### Pattern 4: LIMIT Early to Reduce Work

```sql
-- SLOW: aggregates all matching rows then takes first 10
SELECT customer_id, SUM(amount)
FROM orders
WHERE status = 'delivered'
GROUP BY customer_id
ORDER BY SUM(amount) DESC
LIMIT 10;

-- Already optimal — LIMIT here can't be pushed earlier
-- But ensure index exists: (status, customer_id, amount)

-- SLOW: window function on full table
SELECT * FROM (
    SELECT id, amount,
           ROW_NUMBER() OVER (ORDER BY amount DESC) AS rn
    FROM orders
) x WHERE rn <= 10;

-- FAST: just use ORDER BY + LIMIT directly
SELECT id, amount FROM orders ORDER BY amount DESC LIMIT 10;
-- Planner pushes LIMIT into Index Scan → stops after 10 rows
```

### Pattern 5: OR → UNION ALL

```sql
-- SLOW: OR on different indexed columns forces seq scan or bitmap OR
SELECT * FROM orders
WHERE status = 'pending'
   OR customer_id = 42;
-- Bitmap OR Scan — two indexes merged, may be slow

-- FAST: UNION ALL uses each index independently
SELECT * FROM orders WHERE status = 'pending'
UNION ALL
SELECT * FROM orders WHERE customer_id = 42 AND status != 'pending';
-- Two separate Index Scans — often faster
```

### Pattern 6: Avoid DISTINCT When Not Needed

```sql
-- SLOW: DISTINCT forces sort + dedup on large set
SELECT DISTINCT c.name
FROM customers c
JOIN orders o ON o.customer_id = c.id
WHERE o.status = 'pending';

-- FAST: EXISTS avoids joining all orders, no dedup needed
SELECT c.name
FROM customers c
WHERE EXISTS (
    SELECT 1 FROM orders o
    WHERE o.customer_id = c.id AND o.status = 'pending'
);
```

### Pattern 7: Move Computation Out of WHERE

```sql
-- SLOW: recomputes NOW() once per row (actually cached, but demonstrates principle)
-- More importantly: prevents index use
SELECT * FROM orders WHERE created_at > NOW() - INTERVAL '30 days';
-- This IS index-friendly (bare column), leave it

-- SLOW: computation inside WHERE on column
SELECT * FROM orders WHERE amount * 0.1 > 1000;   -- function on amount!
-- index on amount not usable

-- FAST: move math to the constant side
SELECT * FROM orders WHERE amount > 1000 / 0.1;   -- = WHERE amount > 10000
-- index on amount now usable
```

### Pattern 8: Keyset Pagination vs OFFSET

```sql
-- SLOW: OFFSET scans and discards rows — O(offset) cost
SELECT id, amount FROM orders
ORDER BY created_at DESC, id
LIMIT 20 OFFSET 10000;   -- scans + discards 10000 rows first

-- FAST: keyset pagination — always O(1) regardless of page depth
-- First page:
SELECT id, amount, created_at FROM orders
ORDER BY created_at DESC, id
LIMIT 20;
-- Note last row: (created_at='2024-03-10', id=54321)

-- Next page:
SELECT id, amount, created_at FROM orders
WHERE (created_at, id) < ('2024-03-10', 54321)
ORDER BY created_at DESC, id
LIMIT 20;
-- Index scan from bookmark → instant at any depth
```

### Pattern 9: Partial Results with FETCH FIRST

```sql
-- Use FETCH FIRST when you only need a few rows
-- Combined with appropriate index → very fast

-- Top 5 biggest orders per customer (lateral)
SELECT c.name, top5.*
FROM customers c
LEFT JOIN LATERAL (
    SELECT amount, created_at
    FROM orders
    WHERE customer_id = c.id
    ORDER BY amount DESC
    LIMIT 5
) top5 ON true
WHERE c.country = 'India';
```

### Pattern 10: Pre-aggregate for Repeated Computation

```sql
-- SLOW: compute same aggregation in every query
SELECT
    o.id,
    o.amount,
    (SELECT SUM(amount) FROM orders WHERE customer_id = o.customer_id) AS cust_total
FROM orders o
WHERE o.status = 'pending';
-- Correlated subquery: runs once per row!

-- FAST: pre-aggregate, then join
WITH cust_totals AS (
    SELECT customer_id, SUM(amount) AS total
    FROM orders
    GROUP BY customer_id
)
SELECT o.id, o.amount, ct.total AS cust_total
FROM orders o
JOIN cust_totals ct ON ct.customer_id = o.customer_id
WHERE o.status = 'pending';
-- Aggregation runs once, then joined
```

---

## 12. CTE & Subquery Tuning

### CTE Materialization (PostgreSQL 12+)

```sql
-- PostgreSQL 12+: CTEs are INLINED by default (treated as subqueries)
-- Planner can push predicates through inlined CTEs

-- NOT MATERIALIZED (default in PG12+) — planner optimizes freely
WITH active AS (
    SELECT * FROM customers WHERE segment = 'gold'
)
SELECT * FROM active WHERE country = 'India';
-- Planner may push country='India' filter into CTE → fewer rows scanned

-- MATERIALIZED — forces full CTE execution before outer filter
WITH active AS MATERIALIZED (
    SELECT * FROM customers WHERE segment = 'gold'
)
SELECT * FROM active WHERE country = 'India';
-- Always computes full gold segment first → then filters India

-- When to use MATERIALIZED:
-- 1. CTE is used multiple times (avoid repeated computation)
-- 2. CTE has side effects (DML: DELETE/UPDATE RETURNING)
-- 3. Planner makes bad decision for inlined CTE

-- When to use NOT MATERIALIZED (or default):
-- 1. CTE used once, outer query filters heavily
-- 2. You want planner to optimize the full query holistically
```

### Subquery vs JOIN Performance

```sql
-- Derived table subquery (almost always same as JOIN after PG12+)
SELECT o.id, cust_avg.avg_amount
FROM orders o
JOIN (
    SELECT customer_id, AVG(amount) AS avg_amount
    FROM orders GROUP BY customer_id
) cust_avg ON cust_avg.customer_id = o.customer_id;

-- Window function (often faster — avoids extra grouping step)
SELECT id,
       AVG(amount) OVER (PARTITION BY customer_id) AS avg_amount
FROM orders;

-- For large tables, window function can avoid an entire JOIN node
```

### Correlated Subquery Rewrites

```sql
-- SLOW: correlated subquery executes per row
SELECT id, name,
    (SELECT COUNT(*) FROM orders WHERE customer_id = c.id) AS order_count
FROM customers c;
-- Runs COUNT query 500,000 times!

-- FAST: lateral join (same semantics, one pass)
SELECT c.id, c.name, stats.order_count
FROM customers c
LEFT JOIN LATERAL (
    SELECT COUNT(*) AS order_count
    FROM orders WHERE customer_id = c.id
) stats ON true;

-- FASTEST: pre-aggregate with CTE or subquery
WITH counts AS (
    SELECT customer_id, COUNT(*) AS order_count
    FROM orders GROUP BY customer_id
)
SELECT c.id, c.name, COALESCE(cnt.order_count, 0) AS order_count
FROM customers c
LEFT JOIN counts cnt ON cnt.customer_id = c.id;
```

---

## 13. Parallel Query Tuning

### Enable & Configure Parallel Query

```sql
SHOW max_parallel_workers_per_gather;  -- default: 2
SHOW max_parallel_workers;             -- default: 8
SHOW min_parallel_table_scan_size;     -- default: 8MB
SHOW min_parallel_index_scan_size;     -- default: 512kB
SHOW parallel_setup_cost;              -- default: 1000
SHOW parallel_tuple_cost;              -- default: 0.1

-- Tune for your workload:
ALTER SYSTEM SET max_parallel_workers_per_gather = 4;
ALTER SYSTEM SET max_parallel_workers            = 16;
ALTER SYSTEM SET parallel_setup_cost             = 100;   -- lower = more parallel
ALTER SYSTEM SET parallel_tuple_cost             = 0.01;  -- lower = more parallel
SELECT pg_reload_conf();
```

### Force & Debug Parallel Execution

```sql
-- Check if query uses parallel scan
EXPLAIN (ANALYZE, VERBOSE)
SELECT COUNT(*), SUM(amount) FROM orders;
-- Gather  (cost=...)
--   Workers Planned: 2
--   Workers Launched: 2   ← parallel workers running
--   -> Partial Aggregate
--      -> Parallel Seq Scan on orders

-- Why parallel query may NOT activate:
-- 1. Table too small (< min_parallel_table_scan_size)
-- 2. max_parallel_workers_per_gather = 0
-- 3. Inside a transaction with FOR UPDATE
-- 4. Query modifies data (INSERT/UPDATE/DELETE)
-- 5. Function with parallel safety = UNSAFE

-- Force parallel for testing:
SET max_parallel_workers_per_gather = 4;
SET parallel_setup_cost = 0;
EXPLAIN SELECT COUNT(*) FROM orders;

-- Disable parallel for testing:
SET max_parallel_workers_per_gather = 0;
```

### Function Parallel Safety

```sql
-- Functions called in parallel queries must be marked PARALLEL SAFE
-- Default: PARALLEL UNSAFE (prevents parallelism)

-- Check function safety:
SELECT proname, proparallel
FROM pg_proc
WHERE proname = 'my_function';
-- proparallel: s=safe, r=restricted, u=unsafe

-- Mark as safe (only if truly stateless and side-effect free):
CREATE OR REPLACE FUNCTION my_calc(x NUMERIC)
RETURNS NUMERIC
LANGUAGE sql
PARALLEL SAFE    -- ← allows use in parallel queries
AS $$ SELECT x * 1.1 $$;
```

---

## 14. Partition Pruning Tuning

### Verify Partition Pruning

```sql
-- GOOD: pruning works
EXPLAIN SELECT * FROM orders WHERE created_at >= '2024-01-01';
-- Append
--   -> Seq Scan on orders_2024_q1  ← only matching partitions
--   -> Seq Scan on orders_2024_q2
-- Partitions removed: 6            ← how many skipped

-- BAD: function prevents pruning
EXPLAIN SELECT * FROM orders WHERE DATE_TRUNC('year', created_at) = '2024-01-01';
-- Append
--   -> Seq Scan on orders_2022_q1  ← ALL partitions scanned!
--   -> Seq Scan on orders_2022_q2
--   -> ...
-- No "Partitions removed" line!

-- FIX: rewrite without function on partition key
EXPLAIN SELECT * FROM orders
WHERE created_at >= '2024-01-01' AND created_at < '2025-01-01';
-- Pruning works again
```

### Runtime Partition Pruning

```sql
-- Enable runtime pruning (default: on)
SET enable_partition_pruning = on;

-- Runtime pruning with parameterized queries
PREPARE order_query(DATE, DATE) AS
SELECT * FROM orders WHERE created_at BETWEEN $1 AND $2;

EXPLAIN EXECUTE order_query('2024-01-01', '2024-06-30');
-- Partitions pruned at execution time based on parameter values
```

---

## 15. Connection & Session Tuning

### Session-Level Overrides for Specific Queries

```sql
-- For a specific reporting session: more memory, longer timeout
SET work_mem                        = '512MB';
SET statement_timeout               = '10min';
SET lock_timeout                    = '5s';
SET idle_in_transaction_session_timeout = '5min';

-- Enable JIT for long analytical queries (PG 11+)
SET jit = on;
SET jit_above_cost = 100000;    -- use JIT only for expensive queries

-- Disable JIT for OLTP (JIT compilation overhead > benefit for short queries)
SET jit = off;

-- Set search_path for schema access
SET search_path = myschema, public;

-- Per-query parallel workers
SET max_parallel_workers_per_gather = 8;   -- this session gets more workers
```

### Lock Timeout vs Statement Timeout

```sql
-- statement_timeout: cancel query if it runs longer than N
SET statement_timeout = '30s';
-- All queries in this session cancelled after 30 seconds

-- lock_timeout: cancel if waiting for lock longer than N
SET lock_timeout = '3s';
-- Don't wait more than 3 seconds for any lock

-- Per role defaults (postgresql.conf or ALTER ROLE):
ALTER ROLE app_user          SET statement_timeout = '30s';
ALTER ROLE reporting_user    SET statement_timeout = '10min';
ALTER ROLE background_worker SET statement_timeout = '0';       -- no limit
ALTER ROLE app_user          SET lock_timeout      = '5s';
```

### JIT Compilation

```sql
-- JIT compiles expressions to native code — helps big analytical queries
-- Overhead: ~5-10ms compilation time → only beneficial for > 1s queries
SHOW jit;                  -- on/off
SHOW jit_above_cost;       -- apply JIT when plan cost > this (default: 100000)
SHOW jit_optimize_above_cost;  -- apply optimization when cost > this

-- Check if JIT was used:
EXPLAIN (ANALYZE, VERBOSE)
SELECT SUM(amount * qty) FROM order_items WHERE unit_price > 5000;
-- JIT: functions 3 inlining 1 optimization 1 emission 1 generation 1
--      Functions: 3
--      Options: Inlining true, Optimization true, Expressions true
--      Timing: Generation 1.2ms, Inlining 1.8ms, Optimization 12.4ms,
--              Emission 8.1ms, Total 23.5ms

-- If JIT total time > query benefit → disable per session
SET jit = off;
```

---

## 16. Systematic Diagnosis Checklist

Use this checklist for every slow query investigation.

### Step 1: Capture the Slow Query

```sql
-- Get the full query text and metrics
SELECT
    query,
    calls,
    ROUND(mean_exec_time::NUMERIC, 2)   AS avg_ms,
    ROUND(max_exec_time::NUMERIC, 2)    AS max_ms,
    rows / calls                         AS avg_rows,
    shared_blks_read                    AS disk_reads
FROM pg_stat_statements
WHERE query ILIKE '%orders%'          -- filter to relevant table
ORDER BY mean_exec_time DESC
LIMIT 5;
```

### Step 2: Get Full EXPLAIN Output

```sql
-- Copy the exact query from pg_stat_statements
-- Replace $1, $2 with real values
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT o.id, c.name, o.amount
FROM orders o
JOIN customers c ON c.id = o.customer_id
WHERE o.status = 'pending'
  AND o.created_at > '2024-01-01'
ORDER BY o.amount DESC
LIMIT 100;
```

### Step 3: Identify the Bottleneck

```sql
-- Read EXPLAIN output top-down (outermost node first)
-- Look for:

-- A) Large row estimate mismatch
--    Estimated rows=10, Actual rows=500000  → run ANALYZE

-- B) Seq Scan on large table with heavy filter
--    "Rows Removed by Filter: 1900000"     → add index

-- C) Sort spilling to disk
--    "Sort Method: external merge Disk: 45MB" → increase work_mem

-- D) Hash spilling to disk
--    "Hash Batches: 8"                     → increase work_mem

-- E) Nested Loop with large outer set and Seq Scan on inner
--    → add index on inner join column

-- F) High buffer reads (Buffers: read=50000)
--    → increase shared_buffers or add covering index

-- G) Many loops on expensive node
--    "loops=50000" on slow node                → most expensive issue
```

### Step 4: Apply Fixes in Priority Order

```sql
-- Priority 1: Run ANALYZE (free, instant, often fixes bad plans)
ANALYZE orders;
ANALYZE customers;

-- Priority 2: Add missing index (cheap, targeted)
CREATE INDEX CONCURRENTLY idx_orders_fix ON orders(status, created_at);

-- Priority 3: Increase work_mem (session-level first)
SET work_mem = '128MB';
EXPLAIN (ANALYZE, BUFFERS) <query>;  -- test with more memory

-- Priority 4: Rewrite the query
-- See patterns in Section 11

-- Priority 5: Tune postgresql.conf parameters
ALTER SYSTEM SET random_page_cost = 1.1;
ALTER SYSTEM SET effective_cache_size = '12GB';
SELECT pg_reload_conf();

-- Priority 6: Increase statistics target
ALTER TABLE orders ALTER COLUMN status SET STATISTICS 500;
ANALYZE orders;
```

### Step 5: Validate the Fix

```sql
-- Compare plans before and after
-- Record execution time for same query N times

-- Warm cache test (realistic):
\timing
SELECT o.id, c.name FROM orders o JOIN customers c ON c.id = o.customer_id
WHERE o.status = 'pending' LIMIT 100;
-- Run 3 times, take median

-- Check pg_stat_statements after fix (wait for production traffic):
SELECT mean_exec_time, calls FROM pg_stat_statements
WHERE query ILIKE '%your query%';
```

### Common Red Flags Reference

```
╔══════════════════════════════════╦═══════════════════════════════════════╗
║ EXPLAIN SIGNAL                   ║ DIAGNOSIS + FIX                       ║
╠══════════════════════════════════╬═══════════════════════════════════════╣
║ rows=10 actual=500000            ║ Stale stats → ANALYZE                 ║
║ Seq Scan + "Rows Removed: 1.9M"  ║ Missing index on filter column        ║
║ Sort Method: external merge      ║ Sort spill → increase work_mem        ║
║ Hash Batches: 4+                 ║ Hash spill → increase work_mem        ║
║ Nested Loop + Seq Scan inner     ║ Missing index on inner join column    ║
║ loops=50000 on slow node         ║ Correlated subquery → rewrite         ║
║ Buffers: read=100000             ║ I/O bound → index or shared_buffers   ║
║ Index Scan + Heap Fetches: 50000 ║ Run VACUUM for visibility map         ║
║ Bitmap Heap Scan + recheck cond  ║ lossy bitmap → increase work_mem      ║
║ Planning Time: 500ms+            ║ Too many partitions or dynamic SQL    ║
╚══════════════════════════════════╩═══════════════════════════════════════╝
```

---

## 17. Quick Reference Cheat Sheet

```
╔═══════════════════════════╦══════════════════════════════════════════════════╗
║ TOPIC                     ║ KEY COMMANDS / NOTES                             ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Find Slow Queries         ║ SELECT FROM pg_stat_statements ORDER BY          ║
║                           ║   mean_exec_time / total_exec_time DESC          ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Full EXPLAIN              ║ EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) <query>  ║
║ JSON for tools            ║ EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) <query>  ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Statistics                ║ ANALYZE table;                                   ║
║ More buckets              ║ ALTER TABLE t ALTER COLUMN c SET STATISTICS 500; ║
║ Extended stats            ║ CREATE STATISTICS s ON col1, col2 FROM t;        ║
║ Check stats               ║ SELECT * FROM pg_stats WHERE tablename='t';      ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Cost Parameters           ║ random_page_cost = 1.1  (SSD)                    ║
║                           ║ effective_cache_size = 75% of RAM                ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Scan Nodes                ║ Seq Scan → index if < 10-15% rows match          ║
║                           ║ Index Only Scan → covering index + VACUUM        ║
║                           ║ Bitmap Heap Scan → check work_mem (recheck cond) ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Join Nodes                ║ Nested Loop → index on inner join column         ║
║                           ║ Hash Join spill → increase work_mem              ║
║                           ║ Merge Join → sorted inputs / index on join col   ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Sort / Agg spill          ║ "external merge Disk:" → increase work_mem       ║
║                           ║ "Hash Batches: N>" → increase work_mem           ║
║ Avoid Sort node           ║ Index with matching ORDER BY column order        ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Index Column Order        ║ Equality columns first, Range next, Sort last    ║
║ Covering Index            ║ INCLUDE (col1, col2) → Index Only Scan           ║
║ Partial Index             ║ WHERE is_active=true → smaller, faster index     ║
║ Functional Index          ║ ON t(LOWER(name)) for WHERE LOWER(name)=...      ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ work_mem                  ║ 64MB default; increase per session for big sorts  ║
║ shared_buffers            ║ 25% of RAM; monitor cache hit % (target >99%)    ║
║ maintenance_work_mem      ║ 1GB+ for CREATE INDEX, VACUUM                    ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Query Rewrites            ║ EXISTS > IN for subqueries                       ║
║                           ║ NOT EXISTS > NOT IN (NULL safety)                ║
║                           ║ Bare column > function(column) in WHERE          ║
║                           ║ UNION ALL > OR on different columns              ║
║                           ║ Keyset > OFFSET for pagination                   ║
║                           ║ Pre-aggregate > correlated subquery              ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ CTE Tuning                ║ AS MATERIALIZED → compute once, reuse            ║
║                           ║ AS NOT MATERIALIZED → let planner optimize       ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Parallel Query            ║ max_parallel_workers_per_gather = 4              ║
║                           ║ parallel_setup_cost = 100 (lower = more parallel)║
║                           ║ Mark functions PARALLEL SAFE                     ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Fix Priority Order        ║ 1. ANALYZE (free, instant)                       ║
║                           ║ 2. Add index (CONCURRENTLY)                      ║
║                           ║ 3. Increase work_mem (session first)             ║
║                           ║ 4. Rewrite query                                 ║
║                           ║ 5. Tune postgresql.conf                          ║
║                           ║ 6. Increase statistics target                    ║
╚═══════════════════════════╩══════════════════════════════════════════════════╝
```

---

## Further Reading

- [PostgreSQL Docs — Using EXPLAIN](https://www.postgresql.org/docs/current/using-explain.html)
- [PostgreSQL Docs — Planner Statistics](https://www.postgresql.org/docs/current/planner-stats.html)
- [PostgreSQL Docs — Controlling the Planner](https://www.postgresql.org/docs/current/runtime-config-query.html)
- [PostgreSQL Docs — Parallel Query](https://www.postgresql.org/docs/current/parallel-query.html)
- [PostgreSQL Docs — pg_stat_statements](https://www.postgresql.org/docs/current/pgstatstatements.html)
- [Use the Index, Luke](https://use-the-index-luke.com/) — Visual index and tuning guide
- [explain.depesz.com](https://explain.depesz.com) — EXPLAIN plan visualizer
- [pgMustard](https://www.pgmustard.com) — EXPLAIN plan analyzer with recommendations
- [pg_hint_plan](https://pghintplan.osdn.jp/pg_hint_plan.html) — Planner hints extension
- [auto_explain](https://www.postgresql.org/docs/current/auto-explain.html) — Auto-log slow query plans

---

*Generated with love for PostgreSQL engineers.*
