# 🐘 PostgreSQL Query Optimization — Senior Interview Guide
> **Target Level:** 20+ Years Backend Engineering Experience  
> **Focus:** Query rewriting, execution strategies, schema-level optimization, cost model mastery  
> **Format:** Question → Concept → Deep Dive → Code → Gotchas → Follow-up Probes

---

## 📋 Table of Contents

1. [Query Rewriting & the Planner's Transformation Pipeline](#q1-query-rewriting--the-planners-transformation-pipeline)
2. [Subquery vs JOIN vs CTE — Performance Semantics](#q2-subquery-vs-join-vs-cte--performance-semantics)
3. [Aggregate Optimization — Push Down, Pre-aggregate, Partial](#q3-aggregate-optimization--push-down-pre-aggregate-partial)
4. [Window Functions — Frame Clauses, Partition Design, Spill Prevention](#q4-window-functions--frame-clauses-partition-design-spill-prevention)
5. [Index Design for Query Shapes — Composite, Partial, Expression, Covering](#q5-index-design-for-query-shapes--composite-partial-expression-covering)
6. [NULL Handling and Its Invisible Performance Traps](#q6-null-handling-and-its-invisible-performance-traps)
7. [Correlated Subqueries — Detection, Rewriting, and Lateral Joins](#q7-correlated-subqueries--detection-rewriting-and-lateral-joins)
8. [Materialized Views — Refresh Strategies and Incremental Maintenance](#q8-materialized-views--refresh-strategies-and-incremental-maintenance)
9. [JSON/JSONB Query Optimization](#q9-jsonjsonb-query-optimization)
10. [Recursive CTEs — Optimization Strategies and Pitfalls](#q10-recursive-ctes--optimization-strategies-and-pitfalls)
11. [OR Conditions and Bitmap Scan Mechanics](#q11-or-conditions-and-bitmap-scan-mechanics)
12. [DISTINCT and Deduplication Strategies](#q12-distinct-and-deduplication-strategies)
13. [UPDATE/DELETE Optimization at Scale](#q13-updatedelete-optimization-at-scale)
14. [Schema Design Decisions That Dominate Query Performance](#q14-schema-design-decisions-that-dominate-query-performance)
15. [Cost Model Internals — How the Planner Prices Operations](#q15-cost-model-internals--how-the-planner-prices-operations)
16. [Full-Text Search Architecture and Ranking Optimization](#q16-full-text-search-architecture-and-ranking-optimization)
17. [Anti-Patterns Hall of Shame](#q17-anti-patterns-hall-of-shame)
18. [Query Optimization Diagnostic Runbook](#q18-query-optimization-diagnostic-runbook)

---

## Q1. Query Rewriting & the Planner's Transformation Pipeline

### Question
> Before the planner touches cost estimation, it rewrites your SQL through several transformation passes. Name every transformation stage and explain how understanding them helps you write queries that are naturally optimized.

### Core Concept
PostgreSQL doesn't execute your SQL directly. It transforms it through a deterministic pipeline before any cost-based decisions are made. Writing SQL that exits the pipeline in optimal form is half the battle.

### The Transformation Pipeline

```
Raw SQL Text
    ↓ [Lexer / Parser]
Parse Tree (raw AST)
    ↓ [Analyzer / Semantic Analysis]
Query Tree (names resolved, types checked)
    ↓ [Rewriter — Rule System]
Rewritten Query Tree (views expanded, rules applied)
    ↓ [Planner/Optimizer]
    ├── Preprocessing (subquery flattening, predicate simplification)
    ├── Statistics Gathering (pg_statistic lookups)
    ├── Path Generation (all valid access paths)
    ├── Cost Estimation (CPU + I/O cost for each path)
    └── Plan Selection (cheapest total or startup cost)
Plan Tree
    ↓ [Executor]
Result Rows
```

### Stage 1 — Parser: Syntactic Validation Only

```sql
-- Parser catches syntax errors, nothing semantic:
SELECT * FORM users;  -- Parse error: "FORM" not recognized
SELECT * FROM nonexistent_table;  -- Parse succeeds! Semantic error comes later

-- Practical impact: none on performance, but parse trees ARE cached
-- for frequently-used query structures (prepared statements)
```

### Stage 2 — Analyzer: Type Resolution & Coercion

```sql
-- The analyzer resolves types and inserts implicit casts
-- DANGER: Implicit casts can prevent index usage

-- This looks fine but may not use an index on user_id (integer):
SELECT * FROM orders WHERE user_id = '12345';
--                                   ^^^^^^^ string literal
-- Analyzer inserts: WHERE user_id = '12345'::integer
-- For integer columns, this cast is fine — index works ✓

-- DANGEROUS case: index on varchar, comparing to integer
CREATE INDEX idx_code ON products(code);  -- code is VARCHAR
SELECT * FROM products WHERE code = 12345;
-- Analyzer inserts: WHERE code = 12345::varchar → index works
-- BUT if the column is on the wrong side of an operator:
SELECT * FROM products WHERE 12345 = code;
-- Some older patterns with function wrapping:
SELECT * FROM products WHERE UPPER(code) = 'ABC123';
-- Analyzer wraps column in function → index on code NOT usable
-- Fix: CREATE INDEX ON products(UPPER(code));
```

### Stage 3 — Rewriter: View Expansion & Rules

```sql
-- Views are transparently substituted (not materialized at this stage)
CREATE VIEW pending_orders AS
  SELECT * FROM orders WHERE status = 'pending';

-- This query:
SELECT * FROM pending_orders WHERE user_id = 123;

-- Is rewritten to:
SELECT * FROM (SELECT * FROM orders WHERE status = 'pending') _view
WHERE user_id = 123;
-- Then flattened in next stage to:
SELECT * FROM orders WHERE status = 'pending' AND user_id = 123;

-- Check rewriter output:
SET debug_print_rewritten = on;
SET client_min_messages = debug5;
-- Look for "rewritten query tree" in logs
```

### Stage 4 — Preprocessor: Subquery Flattening

```sql
-- The preprocessor attempts to "flatten" subqueries into JOINs
-- This is where most performance-relevant transformations happen

-- Original:
SELECT * FROM orders
WHERE user_id IN (SELECT id FROM users WHERE country = 'US');

-- Flattened to semi-join:
SELECT orders.* FROM orders
JOIN users ON users.id = orders.user_id
WHERE users.country = 'US';
-- (Planner can now choose optimal join strategy)

-- When flattening FAILS (subquery preserved as subplan):
-- 1. Subquery has DISTINCT, GROUP BY, aggregate without HAVING
-- 2. Subquery uses set operations (UNION, INTERSECT, EXCEPT)
-- 3. Subquery has LIMIT/OFFSET
-- 4. Subquery references outer query columns (correlated)

-- PRESERVED as subplan (cannot flatten):
SELECT * FROM orders
WHERE user_id IN (
  SELECT DISTINCT user_id FROM vip_users  -- DISTINCT blocks flattening
  LIMIT 1000                               -- LIMIT blocks flattening
);
```

### Stage 5 — Constant Folding & Expression Simplification

```sql
-- Planner evaluates constant expressions at plan time, not execution time

-- These are folded at plan time:
WHERE created_at > NOW() - INTERVAL '30 days'
-- Becomes: WHERE created_at > '2024-01-15 10:00:00'::timestamptz
-- Value computed once, baked into plan

-- DANGER: NOW() in prepared statements
PREPARE recent_orders(int) AS
  SELECT * FROM orders WHERE user_id = $1 AND created_at > NOW() - INTERVAL '30 days';
-- NOW() is evaluated at PREPARE time for generic plans!
-- After 30 days, the baked-in timestamp is wrong and plan is never re-evaluated
-- Fix: pass the timestamp as a parameter
PREPARE recent_orders(int, timestamptz) AS
  SELECT * FROM orders WHERE user_id = $1 AND created_at > $2;

-- Constant folding examples:
WHERE 1 = 1         → removed entirely (always true)
WHERE 1 = 2         → query returns 0 rows immediately
WHERE x = 1 + 2     → WHERE x = 3
WHERE x IN (1)      → WHERE x = 1
WHERE x = ANY('{1}') → WHERE x = 1
```

### Follow-up Probes
- *"You have a view that's performing poorly. Before looking at the query, what's the first thing you check about how the view was defined?"*
- *"Why can adding a LIMIT clause to a subquery sometimes make a query slower instead of faster?"*

---

## Q2. Subquery vs JOIN vs CTE — Performance Semantics

### Question
> Given a query that can be written as a correlated subquery, an uncorrelated subquery, a JOIN, or a CTE, explain the performance model for each and the conditions under which each wins.

### The Four Formulations — One Problem

```sql
-- GOAL: Get users and their most recent order total

-- Formulation 1: Correlated Subquery
SELECT
  u.id,
  u.name,
  (SELECT o.total
   FROM orders o
   WHERE o.user_id = u.id
   ORDER BY o.created_at DESC
   LIMIT 1) AS last_order_total
FROM users u;
-- Executes inner query ONCE PER USER ROW
-- For 100k users → 100k subquery executions
-- Cost: O(users × index_lookup_cost)
-- Good if: very few users, fast indexed inner lookup
-- Bad if: large outer result set

-- Formulation 2: Uncorrelated Subquery (IN / EXISTS)
SELECT u.id, u.name
FROM users u
WHERE u.id IN (
  SELECT DISTINCT user_id FROM orders WHERE total > 1000
);
-- Inner runs ONCE, result set used for semi-join
-- Planner can flatten to hash semi-join
-- Cost: O(orders) build + O(users) probe
-- Good if: inner set is bounded, equality match

-- Formulation 3: Explicit JOIN
SELECT DISTINCT ON (u.id)
  u.id,
  u.name,
  o.total AS last_order_total
FROM users u
JOIN orders o ON o.user_id = u.id
ORDER BY u.id, o.created_at DESC;
-- Full join of both tables, then deduplication
-- Planner has maximum freedom: Hash Join, Merge Join, NL
-- Cost: O(users + orders)
-- Good if: need to return columns from both tables
-- Bad if: large Cartesian expansion before DISTINCT

-- Formulation 4: CTE + JOIN (Post-v12 inlined, Pre-v12 fence)
WITH last_orders AS (
  SELECT DISTINCT ON (user_id)
    user_id, total
  FROM orders
  ORDER BY user_id, created_at DESC
)
SELECT u.id, u.name, lo.total
FROM users u
LEFT JOIN last_orders lo ON lo.user_id = u.id;
-- Pre-v12: last_orders fully materialized, then joined → predictable
-- Post-v12: planner may inline → optimizer decides
```

### Semi-Join vs Anti-Join Optimization

```sql
-- IN subquery → semi-join (stop at first match)
SELECT * FROM users WHERE id IN (SELECT user_id FROM orders);
-- Planner rewrites as SEMI JOIN:
-- For each user, check if ANY order exists → stop on first match
-- EXPLAIN shows: Hash Semi Join

-- NOT IN subquery → anti-join (return rows with NO match)
SELECT * FROM users WHERE id NOT IN (SELECT user_id FROM orders);
-- DANGER: NOT IN with NULLs returns NOTHING
-- If any orders.user_id IS NULL → WHERE NULL IN (...) = NULL → no rows returned!

-- SAFE anti-join:
SELECT * FROM users u
WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id);
-- EXISTS handles NULLs correctly
-- EXPLAIN shows: Hash Anti Join (same efficiency as semi-join)

-- Verify planner flattened to anti-join:
EXPLAIN SELECT * FROM users WHERE id NOT IN (SELECT user_id FROM orders WHERE user_id IS NOT NULL);
-- Hash Anti Join  ← flattened and optimized
```

### EXISTS vs COUNT(*) > 0 vs IN

```sql
-- Check if any orders exist for user 123:

-- WORST: COUNT scans all matching rows
SELECT * FROM users WHERE (SELECT COUNT(*) FROM orders WHERE user_id = id) > 0;

-- BETTER: IN uses semi-join (stops at first match)
SELECT * FROM users WHERE id IN (SELECT user_id FROM orders);

-- BEST: EXISTS short-circuits immediately on first match
SELECT * FROM users u WHERE EXISTS (
  SELECT 1 FROM orders o WHERE o.user_id = u.id LIMIT 1
);
-- EXISTS: stops executing the moment one row is found
-- COUNT: must count ALL matching rows

-- They produce identical plans in PostgreSQL in most cases,
-- but EXISTS intent is clearer and guards against planner regression
```

### Lateral JOIN — The Correlated JOIN

```sql
-- LATERAL allows subquery to reference outer query columns
-- Enables correlated subquery power with JOIN flexibility

-- Get each user's last 3 orders:
SELECT u.id, u.name, recent.total, recent.created_at
FROM users u
CROSS JOIN LATERAL (
  SELECT total, created_at
  FROM orders
  WHERE user_id = u.id   -- references outer query ← LATERAL
  ORDER BY created_at DESC
  LIMIT 3
) recent;

-- Vs correlated subquery (can only return single value):
SELECT u.id, u.name,
  (SELECT total FROM orders WHERE user_id = u.id
   ORDER BY created_at DESC LIMIT 1) AS last_total  -- single value only
FROM users u;

-- LATERAL executes once per outer row but returns multiple rows
-- Equivalent to:  FOR each user → run inner query → UNION results
-- Use cases:
--   1. Top-N per group
--   2. Calling set-returning functions per row
--   3. Parameterized subqueries returning multiple columns
```

### Performance Decision Matrix

| Formulation | When to Use | Avoid When |
|------------|-------------|------------|
| Correlated subquery | Single scalar value, tiny outer set | Large outer result set |
| Uncorrelated IN | Bounded inner set, equality check | NULLs in inner, DISTINCT inside |
| NOT IN | Never (NULL danger) | Always |
| NOT EXISTS | Anti-join with potential NULLs | Never (safe and fast) |
| JOIN | Multi-column access from both sides | Cartesian explosion risk |
| LATERAL | Top-N per group, multiple values | Simple scalar lookups |
| MATERIALIZED CTE | Expensive expr used multiple times | Single-use expressions |

### Follow-up Probes
- *"You have `WHERE id IN (SELECT user_id FROM orders)` returning zero rows unexpectedly. What's the most likely cause?"*
- *"When does a subquery get worse performance characteristics than the logically equivalent JOIN even in PostgreSQL 14?"*

---

## Q3. Aggregate Optimization — Push Down, Pre-aggregate, Partial

### Question
> You have a slow aggregation query joining 4 tables and grouping by 3 columns. Walk through every optimization technique, from rewriting to physical execution strategies.

### The Core Problem with Aggregation + Joins

```sql
-- SLOW: Join first (explodes row count), then aggregate collapsed rows
SELECT
  u.country,
  p.category,
  DATE_TRUNC('month', o.created_at) AS month,
  SUM(oi.quantity * oi.price) AS revenue,
  COUNT(DISTINCT o.id) AS order_count
FROM users u
JOIN orders o ON o.user_id = u.id          -- users × orders
JOIN order_items oi ON oi.order_id = o.id  -- × items (fan-out)
JOIN products p ON p.id = oi.product_id    -- × products
WHERE o.created_at >= '2024-01-01'
GROUP BY u.country, p.category, month;

-- Problem: order_items fans out massively before aggregation
-- 1M orders × 5 items avg = 5M rows flowing through join,
-- then collapsed to a few thousand aggregate buckets
```

### Technique 1 — Pre-aggregate Before Joining

```sql
-- FAST: Aggregate at the smallest granularity first, then join
WITH order_revenue AS (
  -- Aggregate at order level BEFORE joining to users/products
  SELECT
    o.id AS order_id,
    o.user_id,
    o.created_at,
    SUM(oi.quantity * oi.price) AS order_total
  FROM orders o
  JOIN order_items oi ON oi.order_id = o.id
  WHERE o.created_at >= '2024-01-01'
  GROUP BY o.id, o.user_id, o.created_at
  -- Reduces 5M rows → 1M rows before the next join
)
SELECT
  u.country,
  p.category,
  DATE_TRUNC('month', r.created_at) AS month,
  SUM(r.order_total) AS revenue,
  COUNT(*) AS order_count
FROM order_revenue r
JOIN users u ON u.id = r.user_id
JOIN orders o ON o.id = r.order_id
JOIN order_items oi ON oi.order_id = o.id
JOIN products p ON p.id = oi.product_id
GROUP BY u.country, p.category, month;
-- Pre-aggregated: 1M rows join instead of 5M ✓
```

### Technique 2 — Partial Aggregation (Parallel)

```sql
-- PostgreSQL can split aggregation into:
-- 1. Partial Aggregate: each parallel worker aggregates its partition
-- 2. Finalize Aggregate: leader combines partial results

-- Verify this is happening:
EXPLAIN SELECT country, SUM(total) FROM orders GROUP BY country;

-- Good plan with parallelism:
Finalize HashAggregate  (rows=50)
  ->  Gather  (workers planned: 4)
        ->  Partial HashAggregate  (rows=50)  ← each worker does partial SUM
              ->  Parallel Seq Scan on orders

-- If you see NO Partial Aggregate:
-- 1. Table too small for parallel threshold
-- 2. Aggregate function is not parallelism-safe (custom functions)
-- 3. max_parallel_workers_per_gather = 0

-- Check aggregate parallel safety:
SELECT
  aggfnoid::regproc AS agg_name,
  aggcombinefn::regproc AS combine_func  -- NULL = not parallelizable
FROM pg_aggregate
WHERE aggfnoid::regproc IN ('sum', 'count', 'avg', 'my_custom_agg');
```

### Technique 3 — Filter Pushdown Into Aggregates

```sql
-- SLOWER: Filter applied after aggregation
SELECT user_id, SUM(total)
FROM orders
GROUP BY user_id
HAVING user_id IN (SELECT id FROM premium_users);
-- Aggregates ALL users, then discards non-premium

-- FASTER: Filter before aggregation
SELECT o.user_id, SUM(o.total)
FROM orders o
JOIN premium_users pu ON pu.id = o.user_id  -- reduces input rows
GROUP BY o.user_id;
-- Only aggregates premium users' orders

-- PLANNER usually handles this via predicate pushdown,
-- but complex HAVING clauses can prevent it:
-- Safe for planner to push: HAVING user_id = 123 (no agg function)
-- NOT safe to push: HAVING SUM(total) > 1000 (requires aggregation first)
```

### Technique 4 — HashAggregate vs SortAggregate

```sql
-- PostgreSQL chooses between two aggregate strategies:
-- HashAggregate: build hash table of groups in memory
-- GroupAggregate: sort input by group key, then scan sequentially

-- HashAggregate wins: many rows, few distinct groups, fits in work_mem
-- GroupAggregate wins: data already sorted (index!), very many distinct groups

-- Force specific strategy (for testing):
SET enable_hashagg = off;   -- forces GroupAggregate (sort-based)

-- GroupAggregate with pre-sorted index:
CREATE INDEX idx_orders_user_created ON orders(user_id, created_at DESC);

EXPLAIN SELECT user_id, DATE_TRUNC('day', created_at), COUNT(*)
FROM orders
GROUP BY user_id, DATE_TRUNC('day', created_at)
ORDER BY user_id, DATE_TRUNC('day', created_at);
-- GroupAggregate using pre-sorted index → zero sort cost ✓

-- HashAggregate memory: check spill behavior
EXPLAIN (ANALYZE, BUFFERS)
SELECT country, COUNT(*)
FROM users
GROUP BY country;
-- HashAggregate  Batches: 1  Memory Usage: 512kB   ← in memory ✓
-- HashAggregate  Batches: 4  Memory Usage: 4096kB  ← spilled ✗
-- Fix: SET work_mem = '256MB';  or reduce cardinality
```

### Technique 5 — COUNT DISTINCT Optimization

```sql
-- COUNT(DISTINCT x) is expensive — forces sort or hash dedup before counting
-- Cannot be partially aggregated in parallel!

-- Check: this cannot parallelize COUNT(DISTINCT):
SELECT country, COUNT(DISTINCT user_id) FROM orders GROUP BY country;
-- Finalize Aggregate (rows=50)
--   Gather (workers planned: 0)    ← NO parallelism!
--   HashAggregate

-- Option 1: HyperLogLog approximation (pg_hll extension)
CREATE EXTENSION hll;
SELECT country, hll_cardinality(hll_add_agg(hll_hash_integer(user_id)))
FROM orders
GROUP BY country;
-- 2% error margin, but fully parallelizable and much faster

-- Option 2: Rewrite using EXISTS if just checking presence
-- Instead of COUNT(DISTINCT user_id) > 0, use EXISTS

-- Option 3: Subquery pre-deduplication
SELECT country, COUNT(*) AS distinct_users
FROM (
  SELECT DISTINCT country, user_id FROM orders  -- deduplicate first
) deduped
GROUP BY country;
-- Planner may optimize better than inline COUNT(DISTINCT)

-- Option 4: Materialized view for expensive repeated aggregations
CREATE MATERIALIZED VIEW daily_distinct_users AS
SELECT
  DATE_TRUNC('day', created_at) AS day,
  country,
  COUNT(DISTINCT user_id) AS unique_users
FROM orders
GROUP BY 1, 2;
-- Refresh on schedule, query the materialized view
```

### Follow-up Probes
- *"COUNT(DISTINCT) vs COUNT(*) — explain the exact execution difference and when you'd use approximation."*
- *"Your GROUP BY query has 50 columns in the SELECT list. How does that impact HashAggregate memory?"*

---

## Q4. Window Functions — Frame Clauses, Partition Design, Spill Prevention

### Question
> Explain the full execution model of window functions, the performance implications of frame clause choices, and how you'd optimize a query with 5 different window functions over a 500M-row table.

### Window Function Execution Model

```sql
-- Window functions execute AFTER WHERE, GROUP BY, HAVING
-- but BEFORE the final SELECT projection and ORDER BY

-- Pipeline:
-- FROM → WHERE → GROUP BY → HAVING → [window functions] → SELECT → ORDER BY → LIMIT

-- Each window function defines:
FUNCTION_NAME() OVER (
  PARTITION BY partition_cols  -- splits input into independent groups
  ORDER BY sort_cols           -- defines row ordering within partition
  ROWS/RANGE BETWEEN           -- defines which rows are "in the frame"
    start_bound AND end_bound
)
```

### Frame Clause Performance Deep Dive

```sql
-- RANGE vs ROWS — critical performance difference:

-- ROWS BETWEEN: exact row count, O(1) per frame advance
SELECT
  id,
  SUM(total) OVER (
    ORDER BY created_at
    ROWS BETWEEN 6 PRECEDING AND CURRENT ROW  -- always exactly 7 rows
  ) AS rolling_7_day
FROM orders;

-- RANGE BETWEEN: value-based boundaries, variable frame size
SELECT
  id,
  SUM(total) OVER (
    ORDER BY created_at
    RANGE BETWEEN INTERVAL '7 days' PRECEDING AND CURRENT ROW
    -- frame = all rows within 7 days, could be 0 or 1000 rows
  ) AS rolling_7_day_revenue
FROM orders;
-- RANGE is logically correct for time-series but requires:
-- 1. Sorting by the ORDER BY column
-- 2. Binary search for frame boundaries per row
-- Performance: O(N log N) vs ROWS O(N)

-- Frame bounds and their costs:
-- UNBOUNDED PRECEDING TO CURRENT ROW  → running total, O(1) incremental ✓
-- CURRENT ROW TO UNBOUNDED FOLLOWING  → reverse running total, O(1) ✓
-- N PRECEDING TO N FOLLOWING          → sliding window, O(1) ✓
-- UNBOUNDED PRECEDING TO UNBOUNDED FOLLOWING → full partition for each row, O(N²) ✗

-- GROUPS mode (PostgreSQL 11+): frame by peer groups
SELECT
  id, score,
  RANK() OVER (ORDER BY score),  -- same as RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
  AVG(score) OVER (
    ORDER BY score
    GROUPS BETWEEN 1 PRECEDING AND 1 FOLLOWING  -- adjacent rank groups
  ) AS neighboring_avg
FROM leaderboard;
```

### Optimizing Multiple Window Functions

```sql
-- SLOW: Each OVER clause with different specs = separate sort + scan
SELECT
  id,
  SUM(total) OVER (PARTITION BY user_id ORDER BY created_at
                   ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS user_running_total,
  AVG(total) OVER (PARTITION BY user_id ORDER BY created_at
                   ROWS BETWEEN 29 PRECEDING AND CURRENT ROW) AS user_30day_avg,
  RANK() OVER (PARTITION BY user_id ORDER BY total DESC) AS rank_by_total,
  SUM(total) OVER (ORDER BY created_at
                   ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS global_running_total,
  LAG(total, 1) OVER (PARTITION BY user_id ORDER BY created_at) AS prev_total
FROM orders;
-- 5 different OVER specs = 5 separate sort passes through data

-- FAST: Group identical specs — planner reuses sort passes
-- Rule: Identical PARTITION BY + ORDER BY = single sort pass shared
SELECT
  id,
  -- These 3 share PARTITION BY user_id ORDER BY created_at → 1 sort pass
  SUM(total) OVER w_user_time AS user_running_total,
  AVG(total) OVER (w_user_time ROWS BETWEEN 29 PRECEDING AND CURRENT ROW) AS rolling_avg,
  LAG(total, 1) OVER w_user_time AS prev_total,
  -- This gets its own sort pass (different ORDER BY)
  RANK() OVER (PARTITION BY user_id ORDER BY total DESC) AS rank_by_total,
  -- This gets its own sort pass (different PARTITION BY)
  SUM(total) OVER (ORDER BY created_at ROWS UNBOUNDED PRECEDING) AS global_total
FROM orders
WINDOW w_user_time AS (PARTITION BY user_id ORDER BY created_at);
-- Named window reuse → 3 window functions share 1 sort ✓
-- Total: 3 sort passes instead of 5
```

### Preventing Window Function Spills

```sql
-- Window functions buffer the entire partition in work_mem
-- Large partitions = large work_mem requirement = disk spill

-- Check work_mem adequacy:
EXPLAIN (ANALYZE, BUFFERS)
SELECT user_id, total,
  SUM(total) OVER (PARTITION BY user_id ORDER BY created_at) AS running
FROM orders;

-- Look for:
-- WindowAgg  (cost=... actual time=...)
--   Sort  Sort Method: external merge  Disk: 245MB  ← PARTITION too large

-- Strategies:
-- 1. Narrow the partition scope
-- 2. Pre-filter before windowing
-- 3. Use incremental computation outside the query (triggers/materialized views)

-- Pre-filter before windowing:
WITH filtered AS (
  SELECT * FROM orders
  WHERE created_at >= '2024-01-01'  -- reduce rows before window
    AND user_id IN (SELECT id FROM active_users)
)
SELECT
  user_id,
  total,
  SUM(total) OVER (PARTITION BY user_id ORDER BY created_at) AS running
FROM filtered;
```

### Follow-up Probes
- *"Explain why `ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING` is an O(N²) operation and how you'd rewrite a query that uses it."*
- *"You need a running total that resets at the start of each calendar month. What frame clause handles this?"*

---

## Q5. Index Design for Query Shapes

### Question
> Walk through how you'd design the index strategy for a table that serves 5 distinct query shapes. Cover composite, partial, expression, and covering indexes and explain the trade-offs of each choice.

### The Reference Table

```sql
CREATE TABLE orders (
  id          BIGSERIAL PRIMARY KEY,
  user_id     BIGINT NOT NULL,
  status      VARCHAR(20) NOT NULL,  -- pending/processing/shipped/delivered/cancelled
  total       NUMERIC(10,2),
  country     VARCHAR(3),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  metadata    JSONB
);
-- 500M rows, 10M inserts/day, 2M updates/day
```

### Query Shape 1 — User Inbox (High Frequency OLTP)

```sql
-- Query: Load a user's recent orders
SELECT id, status, total, created_at
FROM orders
WHERE user_id = 123
ORDER BY created_at DESC
LIMIT 20;

-- Index design: composite covering index
CREATE INDEX idx_orders_user_recent
ON orders(user_id, created_at DESC)
INCLUDE (status, total);
-- ^^^^^^^^^^^^^^^^^^^^ covering: avoids heap fetch entirely
-- (user_id, created_at DESC) = predicate + sort
-- INCLUDE (status, total) = projected columns in index leaf
-- Result: Index Only Scan, zero heap access ✓

-- Why NOT (user_id, created_at DESC, status, total)?
-- Including in key adds to comparison cost for all lookups
-- INCLUDE columns are in leaf only — free for retrieval, no key overhead
```

### Query Shape 2 — Operations Dashboard (Selective Filter)

```sql
-- Query: Find all pending orders (rare status)
SELECT id, user_id, total, created_at
FROM orders
WHERE status = 'pending'
ORDER BY created_at ASC;
-- 'pending' = ~0.1% of orders (500k rows)

-- FULL INDEX on status wastes space indexing 99.9% of rows you'll never filter
-- PARTIAL INDEX only indexes relevant rows:
CREATE INDEX idx_orders_pending
ON orders(created_at ASC)
WHERE status = 'pending';
-- Index contains only 500k rows (0.1% of table) ✓
-- Much smaller → fits in cache → fast scans
-- Maintenance: only updated when status='pending' rows change ✓

-- Planner automatically uses partial index when predicate matches:
EXPLAIN SELECT * FROM orders WHERE status = 'pending' ORDER BY created_at;
-- Index Scan using idx_orders_pending ✓

-- IMPORTANT: partial index only helps if predicate is EXACT match
-- WHERE status = 'pending' → uses index ✓
-- WHERE status IN ('pending', 'processing') → does NOT use index ✗
```

### Query Shape 3 — Analytics (Expression-Based)

```sql
-- Query: Daily revenue report
SELECT
  DATE_TRUNC('day', created_at) AS day,
  country,
  SUM(total)
FROM orders
WHERE DATE_TRUNC('day', created_at) = '2024-01-15'
GROUP BY 1, 2;

-- Plain index on created_at doesn't help: DATE_TRUNC wraps the column
-- Expression index on the function result:
CREATE INDEX idx_orders_daily
ON orders(DATE_TRUNC('day', created_at), country);
-- Now the expression is indexed directly ✓

-- Alternative: rewrite query to use range instead of function
SELECT DATE_TRUNC('day', created_at), country, SUM(total)
FROM orders
WHERE created_at >= '2024-01-15'
  AND created_at <  '2024-01-16'  -- plain B-Tree on created_at works ✓
GROUP BY 1, 2;
-- Range query + regular index is usually faster than expression index
```

### Query Shape 4 — Multi-Tenant Isolation (Compound)

```sql
-- Query: All shipped orders in Germany
SELECT id, user_id, total
FROM orders
WHERE country = 'DE'
  AND status = 'shipped'
ORDER BY created_at DESC
LIMIT 50;

-- Column selectivity analysis:
-- country: ~200 distinct values → moderate selectivity
-- status: ~5 distinct values → low selectivity
-- country + status: high combined selectivity

-- Index column order rule: MOST SELECTIVE first
-- country = 'DE' eliminates ~99.5% of non-DE rows
-- status = 'shipped' is low selectivity on its own, high AFTER country filter

CREATE INDEX idx_orders_country_status_time
ON orders(country, status, created_at DESC)
INCLUDE (user_id, total);

-- Planner uses:
-- Index Cond: country = 'DE' AND status = 'shipped'
-- then: Index Scan direction matches ORDER BY created_at DESC ✓
-- then: INCLUDE columns serve projection without heap access ✓
```

### Query Shape 5 — JSONB Metadata Search

```sql
-- Query: Find orders with specific metadata flags
SELECT id, user_id
FROM orders
WHERE metadata @> '{"fraud_flagged": true}'
  AND metadata ->> 'source' = 'mobile';

-- GIN index for containment:
CREATE INDEX idx_orders_metadata_gin
ON orders USING GIN (metadata jsonb_path_ops);
-- jsonb_path_ops: only supports @>, @?, @@ operators (smaller index)
-- jsonb_ops: supports all operators including key existence (larger index)

-- Expression index for specific key access:
CREATE INDEX idx_orders_metadata_source
ON orders((metadata ->> 'source'))
WHERE metadata ? 'source';  -- partial: only rows with 'source' key
-- Handles: WHERE metadata ->> 'source' = 'mobile'

-- Combined strategy query:
EXPLAIN SELECT id FROM orders
WHERE metadata @> '{"fraud_flagged": true}'  -- uses GIN
  AND metadata ->> 'source' = 'mobile';      -- uses expression index
-- Planner may combine both via BitmapAnd ✓
```

### Index Design Decision Framework

```sql
-- Before creating ANY index, answer these questions:

-- 1. What is the query frequency? (check pg_stat_statements)
SELECT left(query,80), calls FROM pg_stat_statements ORDER BY calls DESC LIMIT 20;

-- 2. What is the column selectivity?
SELECT
  COUNT(DISTINCT status) AS distinct_vals,
  COUNT(*) AS total_rows,
  COUNT(DISTINCT status)::float / COUNT(*) AS selectivity
FROM orders;
-- Selectivity near 1.0 = high (each value unique) → excellent index candidate
-- Selectivity near 0 = low (few distinct values) → poor standalone index candidate

-- 3. What is the current write cost?
-- High write table + many indexes = write amplification
-- Check HOT rate to understand update cost:
SELECT n_tup_upd, n_tup_hot_upd,
  round(n_tup_hot_upd::numeric/NULLIF(n_tup_upd,0)*100,2) AS hot_pct
FROM pg_stat_user_tables WHERE relname = 'orders';

-- 4. Will INCLUDE columns make it covering?
-- Check projections in actual query plans
```

### Follow-up Probes
- *"You have a composite index on (a, b, c). Which of these queries can use it: WHERE b = 1, WHERE a = 1 AND c = 1, WHERE a = 1 ORDER BY b, WHERE a = 1 AND b > 5 AND c = 2?"*
- *"Explain why a B-Tree index on a boolean column is almost always useless, and what you'd use instead."*

---

## Q6. NULL Handling and Its Invisible Performance Traps

### Question
> NULL is PostgreSQL's biggest silent query killer. Name every way NULL values affect query optimization, index usage, and correctness — and demonstrate the fix for each.

### NULL and Index Storage

```sql
-- B-Tree indexes DO store NULL values (in PostgreSQL, unlike some other DBMS)
-- NULLs stored at end of ascending index (or start of descending)

-- Index: CREATE INDEX ON orders(cancelled_at);
-- Storage: [NULL, NULL, NULL, ..., '2024-01-01', '2024-01-02', ...]

-- IS NULL query CAN use the index:
SELECT * FROM orders WHERE cancelled_at IS NULL;
-- Index Scan: scan NULL portion of index ✓

-- IS NOT NULL can use index (avoids NULL block):
SELECT * FROM orders WHERE cancelled_at IS NOT NULL;
-- Index Scan: skip NULL portion ✓

-- But statistics for NULLs are separate:
SELECT null_frac, n_distinct, correlation
FROM pg_stats
WHERE tablename='orders' AND attname='cancelled_at';
-- null_frac = 0.85 means 85% NULLs
-- Planner uses null_frac to estimate IS NULL selectivity
```

### NULL and Join Correctness

```sql
-- NULL = NULL is FALSE in SQL (three-valued logic)
-- JOIN conditions with NULLs silently drop rows

-- Table: orders with some NULL user_ids (legacy data)
SELECT u.name, o.total
FROM users u
JOIN orders o ON o.user_id = u.id;
-- Orders with NULL user_id are silently excluded from results!

-- To include NULL user_ids:
SELECT u.name, o.total
FROM users u
RIGHT JOIN orders o ON o.user_id = u.id;
-- Still excludes NULL: o.user_id IS NULL joins to nothing

-- Explicit NULL handling:
SELECT u.name, o.total
FROM orders o
LEFT JOIN users u ON u.id = o.user_id OR (o.user_id IS NULL AND u.id IS NULL);
-- Ugly but correct for NULL = NULL semantics

-- BETTER: Use COALESCE to normalize before joining
SELECT u.name, o.total
FROM orders o
LEFT JOIN users u ON u.id = COALESCE(o.user_id, -1);
-- Ensure user id = -1 exists as "unknown user" sentinel

-- Best: NEVER have NULLs in FK columns — use NOT NULL constraint
ALTER TABLE orders ALTER COLUMN user_id SET NOT NULL;
```

### NULL and NOT IN — The Silent Killer

```sql
-- The most common NULL bug in production SQL:
SELECT * FROM users
WHERE id NOT IN (
  SELECT user_id FROM orders WHERE user_id IS NULL  -- has NULLs!
);
-- Returns ZERO rows!

-- Why: NOT IN with any NULL → WHERE id NOT IN (NULL, 1, 2, 3)
-- Any comparison with NULL = UNKNOWN (not TRUE or FALSE)
-- Row is included only if condition = TRUE, never UNKNOWN
-- Result: entire NOT IN returns empty set when NULLs present

-- ALWAYS use NOT EXISTS for anti-join:
SELECT * FROM users u
WHERE NOT EXISTS (
  SELECT 1 FROM orders o WHERE o.user_id = u.id
);
-- Correct and efficient regardless of NULLs ✓

-- Or EXCEPT (set operation, NULL-safe):
SELECT id FROM users
EXCEPT
SELECT user_id FROM orders WHERE user_id IS NOT NULL;
```

### NULL and Aggregates

```sql
-- All aggregate functions (except COUNT(*)) ignore NULLs:
SELECT AVG(discount) FROM orders;
-- If 90% of discounts are NULL, AVG only averages the 10% non-NULL values
-- This is often WRONG behavior — may want to treat NULL as 0

-- Explicit NULL handling in aggregates:
SELECT
  AVG(COALESCE(discount, 0)) AS avg_discount_including_nulls,
  AVG(discount) AS avg_discount_excluding_nulls,
  COUNT(*) AS total_orders,
  COUNT(discount) AS orders_with_discount,  -- COUNT(col) ignores NULLs
  COUNT(*) - COUNT(discount) AS orders_without_discount
FROM orders;

-- SUM of all NULLs returns NULL, not 0:
SELECT SUM(discount) FROM orders WHERE user_id = 999;  -- no orders
-- Returns: NULL  (not 0)
-- Fix:
SELECT COALESCE(SUM(discount), 0) FROM orders WHERE user_id = 999;
```

### NULL and Index-Only Scans

```sql
-- INCLUDE clause in index stores NULLs, so IS NULL checks must go to heap
-- unless visibility map confirms page is all-visible

-- Partial index to exclude NULLs (if you never query NULL values):
CREATE INDEX idx_orders_cancelled_at
ON orders(cancelled_at)
WHERE cancelled_at IS NOT NULL;
-- Smaller index: excludes 85% NULL rows
-- Only useful for: WHERE cancelled_at = $1 (not IS NULL checks)

-- Expression index to normalize NULLs:
CREATE INDEX idx_orders_discount_normalized
ON orders(COALESCE(discount, 0));

-- Query must use same expression:
SELECT * FROM orders WHERE COALESCE(discount, 0) > 10;
-- Uses index ✓

-- Direct: WHERE discount > 10  does NOT use COALESCE index ✗
```

### Follow-up Probes
- *"NULLIF vs COALESCE — when does choosing wrong between these cause a performance regression?"*
- *"You have a query using `ORDER BY nullable_col ASC`. Where do NULLs sort, and how do you control it?"*

---

## Q7. Correlated Subqueries — Detection, Rewriting, and Lateral Joins

### Question
> Walk through the execution model of a correlated subquery, explain why they're catastrophically slow at scale, and demonstrate the complete spectrum of rewriting techniques.

### How Correlated Subqueries Execute

```sql
-- Correlated subquery: references outer query column
-- PostgreSQL executes this as:
--   FOR each row in outer table:
--       Execute inner query with outer row's values
--       Return result to outer query

SELECT
  u.id,
  u.name,
  -- This correlated subquery runs ONCE PER USER:
  (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) AS order_count,
  (SELECT SUM(total) FROM orders o WHERE o.user_id = u.id) AS total_spent,
  (SELECT MAX(created_at) FROM orders o WHERE o.user_id = u.id) AS last_order
FROM users u;
-- 100k users × 3 subqueries each = 300k index lookups
-- Even at 0.1ms each = 30 SECONDS
```

### Detection in EXPLAIN

```sql
EXPLAIN SELECT u.id,
  (SELECT COUNT(*) FROM orders WHERE user_id = u.id) AS cnt
FROM users u;

-- Output showing correlated subquery as "SubPlan":
Seq Scan on users  (cost=0..500000000)
  SubPlan 1
    ->  Aggregate  (cost=8.30..8.31 rows=1)
          ->  Index Scan on idx_orders_user_id
                Index Cond: (user_id = u.id)
--                                    ^^^^^ outer reference: executed per row

-- vs flattened join (what we want):
Hash Join  (cost=...)
  ->  Seq Scan on users
  ->  Hash
        ->  HashAggregate  -- runs ONCE for all users
```

### Rewrite 1 — Aggregate JOIN (Most Common Fix)

```sql
-- BEFORE (correlated, O(users × lookup_cost)):
SELECT u.id, u.name,
  (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) AS order_count,
  (SELECT SUM(total) FROM orders o WHERE o.user_id = u.id) AS total_spent
FROM users u;

-- AFTER (aggregate then join, O(users + orders)):
SELECT
  u.id,
  u.name,
  COALESCE(stats.order_count, 0) AS order_count,
  COALESCE(stats.total_spent, 0) AS total_spent
FROM users u
LEFT JOIN (
  SELECT
    user_id,
    COUNT(*) AS order_count,
    SUM(total) AS total_spent
  FROM orders
  GROUP BY user_id
) stats ON stats.user_id = u.id;
-- Pre-aggregates ALL users in one pass, then joins ✓
-- Performance: 300k lookups → 1 scan + 1 hash join
```

### Rewrite 2 — Window Function (For Per-Row Context)

```sql
-- BEFORE: Correlated subquery for row-level comparison
SELECT
  id,
  user_id,
  total,
  (SELECT AVG(total) FROM orders o2 WHERE o2.user_id = o1.user_id) AS user_avg_total
FROM orders o1
WHERE total > (SELECT AVG(total) FROM orders o2 WHERE o2.user_id = o1.user_id);

-- AFTER: Window function computes group average once per partition
SELECT id, user_id, total, user_avg
FROM (
  SELECT
    id,
    user_id,
    total,
    AVG(total) OVER (PARTITION BY user_id) AS user_avg
  FROM orders
) windowed
WHERE total > user_avg;
-- Window function computed in single pass over orders ✓
```

### Rewrite 3 — LATERAL JOIN (For Top-N Per Group)

```sql
-- BEFORE: Correlated subquery returning array/multiple values
SELECT
  u.id,
  u.name,
  ARRAY(
    SELECT o.id FROM orders o WHERE o.user_id = u.id
    ORDER BY o.created_at DESC LIMIT 3
  ) AS recent_order_ids
FROM users u;
-- ARRAY() correlated subquery: executes per user

-- AFTER: LATERAL JOIN
SELECT
  u.id,
  u.name,
  recent.id AS recent_order_id,
  recent.created_at
FROM users u
CROSS JOIN LATERAL (
  SELECT id, created_at
  FROM orders
  WHERE user_id = u.id
  ORDER BY created_at DESC
  LIMIT 3
) recent;
-- LATERAL executes per user BUT allows index usage + LIMIT pushdown
-- Results in multiple rows per user (un-pivoted) — often what you want

-- If you need array output with LATERAL:
SELECT u.id, u.name, ARRAY_AGG(recent.id ORDER BY recent.created_at DESC) AS ids
FROM users u
CROSS JOIN LATERAL (
  SELECT id, created_at FROM orders WHERE user_id = u.id
  ORDER BY created_at DESC LIMIT 3
) recent
GROUP BY u.id, u.name;
```

### Rewrite 4 — Conditional Aggregation (EAV / Pivot)

```sql
-- BEFORE: One subquery per attribute (Entity-Attribute-Value pattern)
SELECT
  p.id,
  (SELECT value FROM attributes WHERE product_id = p.id AND key = 'color') AS color,
  (SELECT value FROM attributes WHERE product_id = p.id AND key = 'size') AS size,
  (SELECT value FROM attributes WHERE product_id = p.id AND key = 'weight') AS weight
FROM products p;
-- 3 subqueries per product row

-- AFTER: Single conditional aggregation
SELECT
  p.id,
  MAX(CASE WHEN a.key = 'color' THEN a.value END) AS color,
  MAX(CASE WHEN a.key = 'size' THEN a.value END) AS size,
  MAX(CASE WHEN a.key = 'weight' THEN a.value END) AS weight
FROM products p
LEFT JOIN attributes a ON a.product_id = p.id AND a.key IN ('color', 'size', 'weight')
GROUP BY p.id;
-- Single scan of attributes with filter, then aggregate ✓

-- Or using FILTER clause (PostgreSQL 9.4+):
SELECT
  p.id,
  MAX(a.value) FILTER (WHERE a.key = 'color') AS color,
  MAX(a.value) FILTER (WHERE a.key = 'size') AS size
FROM products p
LEFT JOIN attributes a ON a.product_id = p.id
GROUP BY p.id;
```

### Follow-up Probes
- *"The planner is NOT flattening your IN subquery into a semi-join. What properties of the subquery are blocking it?"*
- *"LATERAL vs correlated subquery: when does LATERAL have worse performance than the correlated version?"*

---

## Q8. Materialized Views — Refresh Strategies and Incremental Maintenance

### Question
> You have an analytical dashboard running 20 complex queries over 2 billion rows. Page load is 45 seconds. Walk through your materialized view strategy, refresh patterns, and staleness management.

### Basic Materialized View Architecture

```sql
-- Create materialized view for expensive aggregation:
CREATE MATERIALIZED VIEW daily_revenue_summary AS
SELECT
  DATE_TRUNC('day', o.created_at) AS day,
  u.country,
  p.category,
  COUNT(DISTINCT o.id) AS order_count,
  COUNT(DISTINCT o.user_id) AS unique_customers,
  SUM(oi.quantity * oi.price) AS revenue,
  AVG(o.total) AS avg_order_value
FROM orders o
JOIN users u ON u.id = o.user_id
JOIN order_items oi ON oi.order_id = o.id
JOIN products p ON p.id = oi.product_id
WHERE o.created_at >= NOW() - INTERVAL '2 years'
GROUP BY 1, 2, 3
WITH DATA;  -- populate immediately

-- Index the materialized view:
CREATE INDEX ON daily_revenue_summary(day DESC);
CREATE INDEX ON daily_revenue_summary(country, category, day DESC);
CREATE UNIQUE INDEX ON daily_revenue_summary(day, country, category);
-- Unique index required for REFRESH CONCURRENTLY
```

### Refresh Strategies

```sql
-- Strategy 1: Full Refresh (blocking)
REFRESH MATERIALIZED VIEW daily_revenue_summary;
-- Acquires AccessExclusiveLock — blocks ALL reads during refresh
-- Use for: non-critical views, low-traffic windows, small views

-- Strategy 2: Concurrent Refresh (non-blocking)
REFRESH MATERIALIZED VIEW CONCURRENTLY daily_revenue_summary;
-- Requires: unique index on the materialized view
-- Process:
--   1. Build new data into temp relation
--   2. Diff temp vs current (using unique key)
--   3. Apply diff (INSERT missing, DELETE stale)
-- Readers continue during entire process ✓
-- Cost: ~2x the work of full refresh (diff computation)

-- Strategy 3: Scheduled Refresh with pg_cron
CREATE EXTENSION pg_cron;

SELECT cron.schedule(
  'refresh-daily-revenue',
  '5 0 * * *',        -- 00:05 every day
  $$REFRESH MATERIALIZED VIEW CONCURRENTLY daily_revenue_summary$$
);

-- Strategy 4: Trigger-based refresh (near-real-time)
CREATE OR REPLACE FUNCTION refresh_daily_revenue()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  -- Debounce: only refresh if not refreshed in last 5 minutes
  IF NOT EXISTS (
    SELECT 1 FROM mat_view_refresh_log
    WHERE view_name = 'daily_revenue_summary'
      AND refreshed_at > NOW() - INTERVAL '5 minutes'
  ) THEN
    REFRESH MATERIALIZED VIEW CONCURRENTLY daily_revenue_summary;
    INSERT INTO mat_view_refresh_log VALUES ('daily_revenue_summary', NOW())
      ON CONFLICT (view_name) DO UPDATE SET refreshed_at = NOW();
  END IF;
  RETURN NULL;
END;
$$;

CREATE TRIGGER trg_refresh_revenue
AFTER INSERT OR UPDATE OR DELETE ON orders
FOR EACH STATEMENT EXECUTE FUNCTION refresh_daily_revenue();
-- WARNING: Can be slow for high-write tables — use with debouncing
```

### Incremental Refresh Pattern (Manual)

```sql
-- PostgreSQL doesn't have native incremental refresh (unlike some databases)
-- Build it manually:

-- Track what's been processed:
CREATE TABLE mat_view_watermarks (
  view_name TEXT PRIMARY KEY,
  last_processed_at TIMESTAMPTZ NOT NULL DEFAULT '-infinity'
);

INSERT INTO mat_view_watermarks VALUES ('daily_revenue_summary', NOW() - INTERVAL '2 years');

-- Incremental refresh function:
CREATE OR REPLACE FUNCTION incremental_refresh_revenue()
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
  v_watermark TIMESTAMPTZ;
  v_new_watermark TIMESTAMPTZ;
BEGIN
  -- Get current watermark
  SELECT last_processed_at INTO v_watermark
  FROM mat_view_watermarks
  WHERE view_name = 'daily_revenue_summary';

  v_new_watermark := NOW();

  -- Upsert only changed days (days where new orders arrived since watermark)
  INSERT INTO daily_revenue_summary
    (day, country, category, order_count, unique_customers, revenue, avg_order_value)
  SELECT
    DATE_TRUNC('day', o.created_at) AS day,
    u.country,
    p.category,
    COUNT(DISTINCT o.id),
    COUNT(DISTINCT o.user_id),
    SUM(oi.quantity * oi.price),
    AVG(o.total)
  FROM orders o
  JOIN users u ON u.id = o.user_id
  JOIN order_items oi ON oi.order_id = o.id
  JOIN products p ON p.id = oi.product_id
  WHERE o.created_at > v_watermark  -- only new orders
  GROUP BY 1, 2, 3
  ON CONFLICT (day, country, category) DO UPDATE SET
    order_count = EXCLUDED.order_count,
    unique_customers = EXCLUDED.unique_customers,
    revenue = EXCLUDED.revenue,
    avg_order_value = EXCLUDED.avg_order_value;

  -- Advance watermark
  UPDATE mat_view_watermarks
  SET last_processed_at = v_new_watermark
  WHERE view_name = 'daily_revenue_summary';
END;
$$;
```

### Staleness Management

```sql
-- Track view freshness:
CREATE VIEW mat_view_status AS
SELECT
  schemaname,
  matviewname,
  ispopulated,
  definition
FROM pg_matviews;

-- Custom freshness tracking:
SELECT
  view_name,
  last_processed_at,
  NOW() - last_processed_at AS staleness,
  CASE
    WHEN NOW() - last_processed_at < INTERVAL '1 hour' THEN '🟢 FRESH'
    WHEN NOW() - last_processed_at < INTERVAL '6 hours' THEN '🟡 STALE'
    ELSE '🔴 VERY STALE'
  END AS status
FROM mat_view_watermarks;
```

### Follow-up Probes
- *"REFRESH MATERIALIZED VIEW CONCURRENTLY is taking 4x longer than a full refresh. Why, and what would you do?"*
- *"Design a materialized view strategy where the dashboard shows data no more than 30 seconds stale but base table has 100k inserts/minute."*

---

## Q9. JSON/JSONB Query Optimization

### Question
> Your application stores configuration and metadata in JSONB columns. Queries filtering on JSONB fields are performing full table scans on a 200M row table. Walk through the complete optimization strategy.

### JSONB Internal Storage

```sql
-- JSONB: stored in decomposed binary format
--   - Keys sorted and deduplicated
--   - Enables fast key existence checks and @> containment
--   - Supports full GIN indexing
-- JSON: stored as text (validates, but no indexing beyond full scan)

-- Never use JSON type for queryable data — always JSONB

-- Check JSONB column size:
SELECT
  pg_column_size(metadata) AS bytes_per_row,
  AVG(pg_column_size(metadata)) AS avg_bytes,
  pg_size_pretty(SUM(pg_column_size(metadata))) AS total_metadata_size
FROM orders
SAMPLE SYSTEM (1);  -- 1% sample for large tables
```

### GIN Index Types and Operator Coverage

```sql
-- Option 1: jsonb_ops (default, all operators)
CREATE INDEX idx_metadata_gin ON orders USING GIN (metadata);
-- Supports: @>, ?, ?|, ?&, @?, @@
-- Index size: LARGE (indexes every key path)

-- Option 2: jsonb_path_ops (path operations only, smaller)
CREATE INDEX idx_metadata_gin_path ON orders USING GIN (metadata jsonb_path_ops);
-- Supports: @>, @?, @@
-- Does NOT support: ?, ?|, ?& (key existence operators)
-- Index size: ~40% smaller than jsonb_ops

-- Benchmark:
SELECT pg_size_pretty(pg_relation_size('idx_metadata_gin'));
SELECT pg_size_pretty(pg_relation_size('idx_metadata_gin_path'));
-- jsonb_path_ops is typically 30-60% smaller

-- Which queries use which index:
-- @> (containment):       both operator classes ✓
-- ? (key exists):         only jsonb_ops ✓
-- jsonpath (@?, @@):      both operator classes ✓
```

### Specific Key Expression Indexes

```sql
-- GIN index is great for flexible queries, but expensive to maintain
-- For frequently-queried known keys: expression indexes are cheaper

-- If you always query metadata->>'source' = 'mobile':
CREATE INDEX idx_orders_metadata_source
ON orders ((metadata ->> 'source'))
WHERE metadata ? 'source';  -- partial: only rows with this key

-- Composite with expression:
CREATE INDEX idx_orders_metadata_source_created
ON orders ((metadata ->> 'source'), created_at DESC)
WHERE metadata ? 'source';

-- Query that uses it:
SELECT id, created_at
FROM orders
WHERE metadata ->> 'source' = 'mobile'
  AND created_at > NOW() - INTERVAL '7 days';
-- Index Scan on idx_orders_metadata_source_created ✓
```

### JSONPath Queries (PostgreSQL 12+)

```sql
-- JSONPath provides powerful filtering with index support:

-- Check if any item in array matches condition:
SELECT id FROM orders
WHERE metadata @? '$.items[*] ? (@.price > 100)';
-- Uses GIN index with jsonb_path_ops ✓

-- Extract with jsonb_path_query:
SELECT
  id,
  jsonb_path_query_first(metadata, '$.shipping.address.city') AS city
FROM orders
WHERE metadata @@ '$.total_items > 3';

-- Indexed JSONPath:
CREATE INDEX idx_orders_high_value_items ON orders
USING GIN (metadata jsonb_path_ops)
WHERE jsonb_path_exists(metadata, '$.items[*] ? (@.price > 100)');
```

### JSONB vs Normalized Schema — The Decision Tree

```sql
-- When JSONB is APPROPRIATE:
--   1. Schema varies per row (user-defined fields, plugin configs)
--   2. Deep nesting rarely queried (just stored + retrieved)
--   3. Frequent schema changes (add fields without migrations)
--   4. JSON API responses cached as-is

-- When normalized schema WINS:
--   1. Fields are queried/filtered regularly
--   2. Fields need referential integrity (FK constraints)
--   3. Fields are aggregated (SUM, AVG, GROUP BY)
--   4. Joins needed on those field values

-- Hybrid strategy (best of both worlds):
CREATE TABLE orders (
  id          BIGSERIAL PRIMARY KEY,
  user_id     BIGINT NOT NULL REFERENCES users(id),
  status      TEXT NOT NULL,      -- normalized: queried frequently
  total       NUMERIC(10,2),      -- normalized: aggregated
  created_at  TIMESTAMPTZ NOT NULL,  -- normalized: sorted/filtered
  metadata    JSONB               -- flexible: rarely queried fields
);
-- Hot columns are normalized + indexed normally
-- Cold/flexible columns live in JSONB
```

### Follow-up Probes
- *"You need to index a JSONB array field where you query `WHERE metadata->'tags' @> '["premium"]'`. Which index type works and why?"*
- *"JSONB column is causing table bloat because metadata objects are large and frequently updated. How do you address this?"*

---

## Q10. Recursive CTEs — Optimization Strategies and Pitfalls

### Question
> You have an organizational hierarchy of 1 million nodes stored as adjacency list. Your recursive CTE to find all descendants is taking 8 minutes. Walk through every optimization approach.

### Recursive CTE Execution Model

```sql
-- Standard adjacency list:
CREATE TABLE employees (
  id         BIGINT PRIMARY KEY,
  name       TEXT NOT NULL,
  manager_id BIGINT REFERENCES employees(id),
  department TEXT,
  salary     NUMERIC
);
-- 1M employees, deeply nested hierarchy (up to 20 levels)

-- Naive recursive CTE:
WITH RECURSIVE subordinates AS (
  -- Base case: start node
  SELECT id, name, manager_id, 1 AS depth
  FROM employees
  WHERE id = 1  -- root

  UNION ALL

  -- Recursive case: join to working table
  SELECT e.id, e.name, e.manager_id, s.depth + 1
  FROM employees e
  JOIN subordinates s ON e.manager_id = s.id
  WHERE s.depth < 20  -- safety limit!
)
SELECT * FROM subordinates;
-- Iterates depth by depth
-- Each iteration: full scan of employees looking for matching manager_id
-- 20 iterations × table scan = very slow without proper index
```

### Optimization 1 — Index on the FK Column

```sql
-- Most critical: index on manager_id for recursive join
-- Without it: each recursive step is O(N) full scan
CREATE INDEX idx_employees_manager_id ON employees(manager_id);

-- Now each recursive step uses:
-- Index Scan on idx_employees_manager_id
-- WHERE manager_id = ANY(previous_level_ids)
-- Cost drops from O(N × depth) to O(result_size × log N)

-- Verify it's used:
EXPLAIN WITH RECURSIVE sub AS (
  SELECT id, manager_id FROM employees WHERE id = 1
  UNION ALL
  SELECT e.id, e.manager_id FROM employees e
  JOIN sub s ON e.manager_id = s.id
)
SELECT COUNT(*) FROM sub;
-- Should show: Index Scan on idx_employees_manager_id
```

### Optimization 2 — Materialized Path (Denormalized)

```sql
-- Store the full path as a column → no recursion needed
ALTER TABLE employees ADD COLUMN path TEXT;
-- path = '1.5.23.456.7890' for employee 7890

-- Update path on insert/move:
CREATE OR REPLACE FUNCTION compute_employee_path(emp_id BIGINT)
RETURNS TEXT AS $$
  WITH RECURSIVE p AS (
    SELECT id, manager_id, id::TEXT AS path
    FROM employees WHERE manager_id IS NULL  -- root

    UNION ALL

    SELECT e.id, e.manager_id, p.path || '.' || e.id
    FROM employees e
    JOIN p ON e.manager_id = p.id
  )
  SELECT path FROM p WHERE id = emp_id;
$$ LANGUAGE SQL STABLE;

-- Index for subtree queries:
CREATE INDEX idx_employees_path ON employees USING GIST (path gist_ltree_ops);
-- Requires ltree extension for proper operator support

-- With ltree extension:
CREATE EXTENSION ltree;
ALTER TABLE employees ADD COLUMN ltree_path ltree;

-- Subtree query becomes a simple index scan:
SELECT * FROM employees
WHERE ltree_path <@ '1.5.23'::ltree;  -- all descendants of node 23
-- O(subtree_size) with index, instead of O(depth × table_size)
```

### Optimization 3 — Nested Sets Model

```sql
-- Encode tree as left/right bounds (nested intervals)
ALTER TABLE employees
  ADD COLUMN lft INTEGER,
  ADD COLUMN rgt INTEGER;
-- Node 1: lft=1, rgt=2000000 (contains all nodes)
-- Node 5: lft=100, rgt=500 (subtree of 5 is rows where lft BETWEEN 100 AND 500)

-- Finding all descendants: O(1) query with index!
SELECT * FROM employees
WHERE lft BETWEEN 100 AND 500  -- subtree of node 5
ORDER BY lft;

-- Creating indexes for nested sets:
CREATE INDEX ON employees(lft);
CREATE INDEX ON employees(rgt);
CREATE INDEX ON employees(lft, rgt);  -- for subtree queries

-- Tradeoff: reads are O(1) but inserts/moves are O(N) (must renumber)
-- Best for: read-heavy, rarely-modified hierarchies
```

### Optimization 4 — Closure Table

```sql
-- Store all ancestor-descendant pairs explicitly
CREATE TABLE employee_hierarchy (
  ancestor_id   BIGINT NOT NULL REFERENCES employees(id),
  descendant_id BIGINT NOT NULL REFERENCES employees(id),
  depth         INTEGER NOT NULL,
  PRIMARY KEY (ancestor_id, descendant_id)
);

-- Index for both directions:
CREATE INDEX ON employee_hierarchy(ancestor_id, depth);
CREATE INDEX ON employee_hierarchy(descendant_id, depth);

-- Finding all subordinates of manager 5:
SELECT e.*
FROM employees e
JOIN employee_hierarchy h ON h.descendant_id = e.id
WHERE h.ancestor_id = 5
  AND h.depth > 0  -- exclude self
ORDER BY h.depth;
-- Single index scan, O(subtree_size) ✓

-- Finding full management chain for employee 7890:
SELECT e.*, h.depth
FROM employees e
JOIN employee_hierarchy h ON h.ancestor_id = e.id
WHERE h.descendant_id = 7890
ORDER BY h.depth;
-- Clean, fast, single join ✓

-- Cost: N² storage in worst case (star topology)
-- Benefit: O(1) reads for any tree query
```

### Strategy Comparison

| Model | Read Speed | Write Speed | Storage | Best For |
|-------|-----------|-------------|---------|----------|
| Adjacency list | O(depth × N) | O(1) | Minimal | Small trees, frequent writes |
| Adjacency list + index | O(result × log N) | O(1) | +index | General purpose |
| Materialized path (ltree) | O(subtree) | O(depth) | Low | Read-heavy, variable depth |
| Nested sets | O(1) | O(N) | Low | Read-only or rarely modified |
| Closure table | O(subtree) | O(depth²) | O(N²) | Complex tree queries |

### Follow-up Probes
- *"Your recursive CTE has no termination condition. How does PostgreSQL protect against infinite recursion, and what's the performance impact of hitting the cycle?"*
- *"Explain why `UNION ALL` is almost always preferable to `UNION` in recursive CTEs."*

---

## Q11. OR Conditions and Bitmap Scan Mechanics

### Question
> OR conditions are notorious query killers. Explain exactly why, describe the Bitmap Scan mechanism, and walk through every technique to make OR-heavy queries performant.

### Why OR Breaks Normal Index Scans

```sql
-- B-Tree index scan assumes a contiguous range
-- OR creates two separate ranges → cannot scan contiguously

-- EXPLAIN this:
SELECT * FROM orders
WHERE status = 'pending' OR status = 'processing';

-- Without optimization: Seq Scan (planner skips index for multiple ranges)
-- With optimization: Bitmap OR of two index scans

-- The two strategies:
-- 1. Index Scan: sequential I/O through index, random heap access per tuple
-- 2. Bitmap Scan: builds bitmap of matching heap pages, then batch fetches pages
```

### Bitmap Scan Mechanics

```sql
-- How Bitmap Scan works:
-- Phase 1 (Index phase):
--   Scan index, build an in-memory bitmap of heap page numbers
--   Bit set = "this page has at least one matching row"
--   Multiple scans can be combined: Bitmap OR, Bitmap AND

-- Phase 2 (Heap phase):
--   Sort heap pages by physical location
--   Read heap pages in page order (sequential-ish I/O)
--   Re-check actual row predicates (bitmap is lossy — page level, not row level)

EXPLAIN SELECT * FROM orders
WHERE status = 'pending' OR status = 'processing';

-- Expected plan:
Bitmap Heap Scan on orders
  Recheck Cond: ((status = 'pending') OR (status = 'processing'))
  ->  BitmapOr
        ->  Bitmap Index Scan on idx_orders_status
              Index Cond: (status = 'pending')
        ->  Bitmap Index Scan on idx_orders_status
              Index Cond: (status = 'processing')
-- Two index scans combined with BitmapOr ✓
-- Heap fetched in page order (better than random access) ✓
```

### OR Rewrite Strategies

```sql
-- Strategy 1: IN clause (syntactic sugar → same plan)
-- IN is rewritten to OR internally by the planner
SELECT * FROM orders WHERE status IN ('pending', 'processing');
-- Identical plan to OR version ✓

-- Strategy 2: UNION ALL (forces separate execution paths)
SELECT * FROM orders WHERE status = 'pending'
UNION ALL
SELECT * FROM orders WHERE status = 'processing';
-- Two Index Scans concatenated
-- Better when: cardinality very different between values
-- Allows different query plans per branch (one might seq scan, one index scan)

-- Strategy 3: ANY array operator
SELECT * FROM orders
WHERE status = ANY(ARRAY['pending', 'processing', 'on_hold']);
-- Planner treats as IN → bitmap scan over index ✓
-- Parameterizable:
SELECT * FROM orders WHERE status = ANY($1::text[]);

-- Strategy 4: Partial indexes for known OR patterns
CREATE INDEX idx_orders_active
ON orders(created_at, user_id)
WHERE status IN ('pending', 'processing');
-- Index only contains 'active' orders
-- Query: WHERE status IN ('pending', 'processing') ORDER BY created_at
-- Uses partial index → much smaller, faster ✓
```

### OR Across Different Columns — The Hard Case

```sql
-- Cross-column OR cannot use a single B-Tree index
SELECT * FROM orders
WHERE user_id = 123 OR product_id = 456;  -- different columns!

-- Only options:
-- 1. Bitmap OR of two separate indexes
CREATE INDEX ON orders(user_id);
CREATE INDEX ON orders(product_id);
-- Planner: BitmapOr(Bitmap Index Scan on user_id, Bitmap Index Scan on product_id) ✓

-- 2. UNION ALL (always works, planner has max freedom)
SELECT * FROM orders WHERE user_id = 123
UNION
SELECT * FROM orders WHERE product_id = 456;
-- UNION deduplicates; UNION ALL is faster if you need both

-- 3. Full text search style — store searchable key in separate column
ALTER TABLE orders ADD COLUMN search_ids TEXT;
-- Populate: user_id::text || ' ' || product_id::text
-- GIN index on search_ids — but this is a denormalization hack

-- Check if Bitmap OR is happening:
EXPLAIN SELECT * FROM orders WHERE user_id = 123 OR product_id = 456;
-- Goal: see BitmapOr node combining two bitmap index scans
-- If you see Seq Scan: one or both indexes are not worth using (low selectivity)
```

### Follow-up Probes
- *"You see 'Lossy' in a Bitmap Heap Scan output. What does that mean and what are the performance implications?"*
- *"When does UNION ALL outperform the equivalent OR query, even after the planner applies its transformations?"*

---

## Q12. DISTINCT and Deduplication Strategies

### Question
> You have a query with `SELECT DISTINCT` over a 100M row join result. It's doing a full sort of 50M rows. Walk through the alternatives and when each is appropriate.

### DISTINCT Execution Models

```sql
-- DISTINCT forces deduplication before returning results
-- Two strategies:

-- HashAggregate strategy (planner prefers when work_mem allows):
EXPLAIN SELECT DISTINCT user_id FROM orders;
-- HashAggregate (rows=50000)
--   Seq Scan on orders

-- Sort + Unique strategy (when data is sorted or work_mem insufficient):
EXPLAIN SELECT DISTINCT user_id FROM orders ORDER BY user_id;
-- Unique (rows=50000)
--   Sort (Sort Key: user_id)
--     Seq Scan on orders
```

### DISTINCT ON — PostgreSQL's Powerful Extension

```sql
-- DISTINCT ON: keep first row per group (ordered by your choice)
-- Far more powerful than standard DISTINCT

-- Get latest order per user:
SELECT DISTINCT ON (user_id)
  user_id, id, total, created_at
FROM orders
ORDER BY user_id, created_at DESC;
-- Keeps: first row in each (user_id) group after sorting by created_at DESC
-- = Most recent order per user ✓

-- Performance: Sort required (unless index provides ordering)
-- With index on (user_id, created_at DESC):
CREATE INDEX idx_orders_user_created ON orders(user_id, created_at DESC);

EXPLAIN SELECT DISTINCT ON (user_id) user_id, id, total, created_at
FROM orders
ORDER BY user_id, created_at DESC;
-- Index Scan on idx_orders_user_created (already sorted!)
-- Unique (rows=users_count)
-- NO Sort node! ✓ Index provides the ordering for free
```

### Replacing DISTINCT with EXISTS

```sql
-- Anti-pattern: DISTINCT to hide bad joins
-- The join multiplies rows, DISTINCT collapses them — wasteful!

-- BAD: Join explodes rows, DISTINCT collapses them
SELECT DISTINCT u.id, u.name, u.email
FROM users u
JOIN orders o ON o.user_id = u.id  -- fans out to multiple rows per user
JOIN order_items oi ON oi.order_id = o.id  -- fans out again
WHERE oi.product_id = 42;
-- Result: millions of rows, deduplicated back to thousands

-- GOOD: EXISTS stops at first match per user
SELECT u.id, u.name, u.email
FROM users u
WHERE EXISTS (
  SELECT 1 FROM orders o
  JOIN order_items oi ON oi.order_id = o.id
  WHERE o.user_id = u.id AND oi.product_id = 42
);
-- SEMI JOIN: stops at first matching order item per user ✓
-- Zero explosion, zero deduplication needed
```

### DISTINCT vs GROUP BY — Subtle Differences

```sql
-- DISTINCT and GROUP BY often produce same result but different plans

-- DISTINCT: deduplication semantic, may use HashAgg or Sort+Unique
SELECT DISTINCT user_id, status FROM orders;

-- GROUP BY: aggregation semantic, always uses HashAgg or GroupAgg
SELECT user_id, status FROM orders GROUP BY user_id, status;

-- Performance: usually identical for non-aggregated queries
-- GROUP BY wins when: you need aggregates alongside deduplication
-- DISTINCT ON wins when: you need to keep specific row context (not just key)

-- Force planner to use index scan + unique:
SET enable_hashagg = off;
EXPLAIN SELECT DISTINCT user_id FROM orders ORDER BY user_id;
-- Now uses Sort + Unique → if index exists, becomes Index Scan + Unique
```

### Follow-up Probes
- *"You need to find all users who have purchased from at least 3 different product categories. Write it without DISTINCT."*
- *"DISTINCT ON vs ROW_NUMBER() OVER — when does ROW_NUMBER give you more control, and when is DISTINCT ON faster?"*

---

## Q13. UPDATE/DELETE Optimization at Scale

### Question
> You need to delete 400M rows from a 1B-row table and archive them to another table. A naive DELETE takes 6 hours, fills WAL, and kills replication. Walk through the complete strategy.

### Why Bulk DELETE is Catastrophic

```sql
-- Problems with DELETE FROM orders WHERE created_at < '2023-01-01':
-- 1. Single huge transaction → massive WAL generation
-- 2. Single huge transaction → lock held for hours → replication lag
-- 3. MVCC: 400M dead tuples accumulate → table bloat
-- 4. VACUUM must process all dead tuples after → secondary performance hit
-- 5. Index entries for 400M rows marked dead → index bloat

-- Also: this pattern fills WAL even on SSDs:
DELETE FROM orders WHERE created_at < '2023-01-01';
-- Expected WAL generation: rows × ~100 bytes = 40GB WAL
```

### Strategy 1 — Batch DELETE with Cursor Control

```sql
-- Delete in small batches with sleep to allow WAL archival and replication sync

DO $$
DECLARE
  rows_deleted INT;
  total_deleted BIGINT := 0;
  batch_size INT := 10000;
  cutoff_date TIMESTAMPTZ := '2023-01-01';
BEGIN
  LOOP
    -- Delete one batch
    WITH deleted AS (
      DELETE FROM orders
      WHERE id IN (
        SELECT id FROM orders
        WHERE created_at < cutoff_date
        LIMIT batch_size
        FOR UPDATE SKIP LOCKED  -- skip rows locked by other transactions
      )
      RETURNING id
    )
    SELECT COUNT(*) INTO rows_deleted FROM deleted;

    total_deleted := total_deleted + rows_deleted;

    -- Exit when no more rows
    EXIT WHEN rows_deleted = 0;

    -- Brief pause to allow replication to catch up
    PERFORM pg_sleep(0.1);  -- 100ms between batches

    -- Log progress every 100 batches
    IF MOD(total_deleted, batch_size * 100) = 0 THEN
      RAISE NOTICE 'Deleted % rows total', total_deleted;
    END IF;
  END LOOP;

  RAISE NOTICE 'Complete: deleted % rows total', total_deleted;
END;
$$;
```

### Strategy 2 — Archive + Partition Swap (Fastest)

```sql
-- For very large deletes: partition detach is O(1)

-- Step 1: Partition the table by date (if not already partitioned)
-- Step 2: DETACH old partitions instead of deleting rows

-- If table is already partitioned:
ALTER TABLE orders DETACH PARTITION orders_2022 CONCURRENTLY;
-- O(1) operation! No row-level work ✓

-- If table is NOT partitioned, use pg_partman to migrate:
-- 1. Create new partitioned table
-- 2. Migrate data in batches
-- 3. Rename swap
-- OR use pg_repack with partitioning

-- Quick archive pattern:
CREATE TABLE orders_archive_2022 (LIKE orders INCLUDING ALL);

-- Move data:
INSERT INTO orders_archive_2022
SELECT * FROM orders WHERE created_at < '2023-01-01';

-- Delete original (still slow, but archive is already safe):
-- Better: do after creating partition structure
```

### Strategy 3 — Conditional UPDATE Anti-Pattern Fix

```sql
-- SLOW: UPDATE with complex subquery for 100M rows
UPDATE user_stats
SET total_orders = (
  SELECT COUNT(*) FROM orders WHERE user_id = user_stats.user_id
)
WHERE user_id IN (SELECT DISTINCT user_id FROM recent_orders);
-- Correlated subquery = O(users × order_scan)

-- FAST: Pre-aggregate, then bulk UPDATE with JOIN
UPDATE user_stats
SET total_orders = agg.count
FROM (
  SELECT user_id, COUNT(*) AS count
  FROM orders
  GROUP BY user_id
) agg
WHERE user_stats.user_id = agg.user_id;
-- Single scan of orders + hash join + bulk update ✓

-- For very large updates, use batch UPDATE:
UPDATE user_stats
SET total_orders = agg.count
FROM (
  SELECT user_id, COUNT(*) AS count
  FROM orders
  WHERE created_at >= NOW() - INTERVAL '1 day'  -- only changed users
  GROUP BY user_id
) agg
WHERE user_stats.user_id = agg.user_id;
-- Incremental: only update stats for users with recent activity
```

### Strategy 4 — Soft Delete Pattern Optimization

```sql
-- Soft delete: mark rows as deleted instead of removing them
ALTER TABLE orders ADD COLUMN deleted_at TIMESTAMPTZ;

-- Problem: every query now needs WHERE deleted_at IS NULL
-- Full table scans for each query to filter deleted rows

-- Solution 1: Partial index excludes deleted rows
CREATE INDEX idx_orders_active_user ON orders(user_id, created_at DESC)
WHERE deleted_at IS NULL;
-- Index only contains non-deleted rows
-- Queries with WHERE deleted_at IS NULL use this smaller index ✓

-- Solution 2: Views with Row Level Security
CREATE VIEW active_orders AS
  SELECT * FROM orders WHERE deleted_at IS NULL;

CREATE INDEX ON orders(created_at)
WHERE deleted_at IS NULL;

-- Solution 3: Partitioned soft-delete (best for very high delete volume)
-- Active partition: deleted_at IS NULL
-- Archive partition: deleted_at IS NOT NULL
-- Attach/detach for bulk archival
```

### Follow-up Probes
- *"Your batched DELETE is generating enormous WAL and causing 30-second replication lag spikes. How do you reduce WAL generation while maintaining safety?"*
- *"You need to UPDATE 50M rows to backfill a new column. You can't lock the table. What's your approach?"*

---

## Q14. Schema Design Decisions That Dominate Query Performance

### Question
> Walk through the schema design decisions that have the highest impact on query performance — beyond just indexing. Focus on decisions that can't easily be fixed later.

### Decision 1 — Data Type Precision

```sql
-- WRONG: Oversized types waste storage + cache efficiency
CREATE TABLE events (
  id         VARCHAR(255),      -- should be BIGSERIAL
  session_id VARCHAR(255),      -- should be UUID or BIGINT
  event_type VARCHAR(255),      -- should be TEXT or ENUM
  payload    TEXT               -- should be JSONB if queried
);

-- RIGHT: Smallest appropriate type
CREATE TABLE events (
  id         BIGSERIAL PRIMARY KEY,            -- 8 bytes, auto-increment
  session_id UUID NOT NULL,                    -- 16 bytes, native type
  event_type event_type_enum NOT NULL,         -- ENUM: 4 bytes + type check
  payload    JSONB                             -- binary, queryable
);

-- Performance impact of type choice:
-- VARCHAR(255) vs TEXT: identical storage in PG (variable length)
-- INTEGER (4 bytes) vs BIGINT (8 bytes): 2M rows × 4 bytes = 8MB difference
-- More rows per page → better cache utilization → faster scans

-- ENUM performance:
CREATE TYPE order_status AS ENUM ('pending','processing','shipped','delivered','cancelled');
ALTER TABLE orders ALTER COLUMN status TYPE order_status USING status::order_status;
-- Stored as 4-byte OID internally
-- Index on ENUM uses integer comparison (fast) instead of string comparison
-- Constraint enforcement is free (type system, not CHECK constraint)
```

### Decision 2 — Normalization Level

```sql
-- Under-normalized: repeated string data, bloated storage
CREATE TABLE orders (
  id           BIGSERIAL,
  user_name    TEXT,        -- should be FK to users
  user_email   TEXT,        -- duplicated across thousands of orders
  user_country TEXT,
  status       TEXT
);
-- Problems:
-- 1. "user_country" must be scanned as text, not integer FK
-- 2. Update anomalies: user changes email → must update all orders
-- 3. Bloated rows → fewer rows per page → slower scans

-- Over-normalized: too many joins for simple queries
CREATE TABLE order_status_history (
  order_id    BIGINT,
  status_code SMALLINT,    -- FK to status_codes table
  changed_at  TIMESTAMPTZ
);
CREATE TABLE status_codes (
  code INT, name TEXT
);
-- Every status display requires a join to a 5-row lookup table
-- Cache these lookups in application, or denormalize with ENUM

-- Optimal: normalize volatile data, denormalize stable lookup data
CREATE TABLE orders (
  id         BIGSERIAL,
  user_id    BIGINT NOT NULL REFERENCES users(id),   -- FK: volatile
  status     order_status NOT NULL,                   -- ENUM: stable
  country    CHAR(2) NOT NULL                         -- denormalized: rarely changes
);
```

### Decision 3 — Surrogate vs Natural Keys

```sql
-- Natural key (email as PK):
CREATE TABLE users (
  email   TEXT PRIMARY KEY,   -- natural key
  name    TEXT
);
CREATE TABLE orders (
  id      BIGSERIAL,
  email   TEXT REFERENCES users(email)  -- 40-100 bytes per FK reference
);
-- Problems:
-- 1. Long text keys → larger B-Tree nodes → index deeper + wider
-- 2. FK columns in orders store full email string
-- 3. Email changes → cascade update all FK references
-- 4. Index comparison: string comparison vs integer comparison

-- Surrogate key (integer as PK):
CREATE TABLE users (
  id      BIGSERIAL PRIMARY KEY,    -- 8 bytes
  email   TEXT UNIQUE NOT NULL,
  name    TEXT
);
CREATE TABLE orders (
  id      BIGSERIAL PRIMARY KEY,
  user_id BIGINT REFERENCES users(id)  -- 8 bytes per FK reference
);
-- Benefits: compact FK, fast integer comparison, independent of business changes

-- UUID as PK (distributed systems):
CREATE TABLE events (
  id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  ...
);
-- 16 bytes (2x BIGINT) - acceptable
-- Random UUIDs cause B-Tree fragmentation (random insertion order)
-- Use UUID v7 (time-ordered) to maintain B-Tree locality:
-- SELECT gen_random_uuid() → random (bad)
-- Use pg_ulid extension or application-generated time-ordered UUIDs
```

### Decision 4 — Horizontal vs Vertical Partitioning

```sql
-- Vertical partitioning: split wide tables
-- Problem: 200-column "mega table" from ERP migration

CREATE TABLE products (
  id              BIGSERIAL PRIMARY KEY,
  name            TEXT NOT NULL,
  price           NUMERIC,
  -- Frequently queried (hot):
  status          TEXT,
  category_id     INT,
  -- Rarely queried (cold):
  description     TEXT,          -- large
  specifications  JSONB,         -- large
  seo_metadata    JSONB,         -- large
  import_data     JSONB          -- very large
);
-- Problem: even simple queries like "get product name + price"
-- must load entire row including huge JSONB fields (TOAST notwithstanding)

-- Vertical split:
CREATE TABLE products (                -- hot: always loaded
  id          BIGSERIAL PRIMARY KEY,
  name        TEXT NOT NULL,
  price       NUMERIC,
  status      TEXT,
  category_id INT
);

CREATE TABLE product_details (         -- cold: loaded on demand
  product_id     BIGINT PRIMARY KEY REFERENCES products(id),
  description    TEXT,
  specifications JSONB,
  seo_metadata   JSONB
);
-- Hot queries: scan products (small rows, more per page, better cache)
-- Detail queries: JOIN to product_details (only when needed)
```

### Decision 5 — Temporal Data Modeling

```sql
-- Anti-pattern: UPDATE current row (destroys history)
UPDATE prices SET amount = 29.99 WHERE product_id = 1;
-- No history, no rollback, no auditing

-- Pattern 1: Append-only with effective dates
CREATE TABLE product_prices (
  id           BIGSERIAL PRIMARY KEY,
  product_id   BIGINT NOT NULL,
  amount       NUMERIC NOT NULL,
  valid_from   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  valid_to     TIMESTAMPTZ DEFAULT NULL  -- NULL = current
);

-- Current price query (optimized with partial index):
CREATE INDEX idx_prices_current
ON product_prices(product_id, valid_from DESC)
WHERE valid_to IS NULL;

SELECT amount FROM product_prices
WHERE product_id = 1 AND valid_to IS NULL;
-- Index Only Scan on idx_prices_current ✓

-- Pattern 2: PostgreSQL temporal tables (tsrange)
CREATE TABLE product_prices (
  id         BIGSERIAL PRIMARY KEY,
  product_id BIGINT NOT NULL,
  amount     NUMERIC NOT NULL,
  valid_during TSTZRANGE NOT NULL DEFAULT tstzrange(NOW(), 'infinity', '[)')
);

-- GiST index for range queries:
CREATE INDEX ON product_prices USING GIST (product_id, valid_during);

-- Current price:
SELECT amount FROM product_prices
WHERE product_id = 1
  AND valid_during @> NOW()::TIMESTAMPTZ;
-- Uses GiST index ✓

-- Exclude constraint (no overlapping prices):
ALTER TABLE product_prices
  ADD CONSTRAINT no_overlap
  EXCLUDE USING GIST (product_id WITH =, valid_during WITH &&);
```

### Follow-up Probes
- *"Your application is joining to a 5-row lookup/reference table on every query. What are three ways to eliminate this join entirely?"*
- *"Explain how storing phone numbers as TEXT vs BIGINT vs a normalized phone_numbers table affects query performance."*

---

## Q15. Cost Model Internals — How the Planner Prices Operations

### Question
> Walk through the PostgreSQL cost model. What are the units, what are the inputs, and how do miscalibrated cost parameters cause the planner to make wrong decisions?

### Cost Units and Parameters

```sql
-- PostgreSQL cost is in "abstract page fetch units"
-- All costs normalized relative to seq_page_cost = 1.0

SHOW seq_page_cost;       -- default: 1.0   (sequential page read)
SHOW random_page_cost;    -- default: 4.0   (random page read = 4× more expensive)
SHOW cpu_tuple_cost;      -- default: 0.01  (cost to process one row)
SHOW cpu_index_tuple_cost;-- default: 0.005 (cost per index entry)
SHOW cpu_operator_cost;   -- default: 0.0025 (cost per operator evaluation)
SHOW parallel_setup_cost; -- default: 1000  (cost to launch parallel workers)
SHOW parallel_tuple_cost; -- default: 0.1   (cost to transfer row between workers)

-- What "cost=100..250" means:
-- startup_cost=100 (cost before first row returned — sort, hash build, etc.)
-- total_cost=250   (cost to process ALL rows)
-- The planner optimizes for total_cost by default (for analytical queries)
-- Can optimize for startup_cost (for EXISTS, LIMIT 1, cursors)

SHOW cursor_tuple_fraction;  -- default: 0.1
-- When fraction of rows needed < this, planner optimizes for startup cost
```

### Calibrating for SSD Storage

```sql
-- Default random_page_cost=4.0 assumes spinning disk
-- On NVMe SSD, random I/O cost is nearly equal to sequential

-- Miscalibration effect:
-- Planner thinks: "index scan = 4× cost of seq scan per page"
-- Reality on NVMe: "index scan ≈ 1.1× cost of seq scan per page"
-- Result: planner avoids indexes it should use → unnecessary seq scans

-- Calibrate for SSD:
ALTER SYSTEM SET random_page_cost = 1.1;   -- NVMe SSD
ALTER SYSTEM SET random_page_cost = 2.0;   -- SATA SSD
ALTER SYSTEM SET random_page_cost = 4.0;   -- HDD (default)

-- Calibrate for in-memory workload:
ALTER SYSTEM SET random_page_cost = 1.0;   -- everything in RAM
ALTER SYSTEM SET effective_cache_size = '200GB';  -- hint: OS page cache size
-- effective_cache_size doesn't allocate memory, just tells planner how much
-- data is likely cached → affects cost estimates for random reads

SELECT pg_reload_conf();

-- Verify calibration by checking plan changes:
SET random_page_cost = 1.1;
EXPLAIN SELECT * FROM orders WHERE user_id = 123;
-- Before: Seq Scan (planner thought index too expensive)
-- After: Index Scan (planner now correctly prefers index)
```

### Row Count Estimation Process

```sql
-- The planner estimates selectivity using:
-- 1. Column statistics (pg_statistic / pg_stats)
-- 2. Most common values (MCV) list
-- 3. Histogram buckets
-- 4. Null fraction
-- 5. Correlation

-- Inspect what the planner knows:
SELECT
  attname,
  n_distinct,      -- estimated distinct values (-0.5 = 50% of rows are distinct)
  null_frac,       -- fraction of NULLs (0.0 to 1.0)
  avg_width,       -- average column width in bytes
  correlation,     -- correlation with physical order (-1 to 1)
  most_common_vals,
  most_common_freqs,
  histogram_bounds
FROM pg_stats
WHERE tablename = 'orders' AND attname = 'status';

-- Manually test selectivity estimate:
SELECT
  *,
  -- The planner uses this to estimate: status = 'pending' selectivity
  most_common_freqs[
    ARRAY_POSITION(most_common_vals::text[], 'pending')
  ] AS pending_selectivity
FROM pg_stats
WHERE tablename = 'orders' AND attname = 'status';
```

### Cost Calculation for Common Nodes

```sql
-- Seq Scan cost:
-- total_cost = seq_page_cost × relpages + cpu_tuple_cost × reltuples
-- For orders table: 1.0 × 50000 pages + 0.01 × 500000 rows = 55000

-- Index Scan cost (simplified):
-- startup_cost = root traversal ≈ log(index_size) × cpu_index_tuple_cost
-- total_cost = index_leaf_cost + heap_fetch_cost
--   index_leaf_cost = cpu_index_tuple_cost × matched_tuples
--   heap_fetch_cost = random_page_cost × estimated_distinct_pages

-- Hash Join cost:
-- build_phase = seq_page_cost × inner_pages + cpu_tuple_cost × inner_rows
--             + hash bucketing overhead
-- probe_phase = seq_page_cost × outer_pages + cpu_operator_cost × rows × 2

-- Sort cost:
-- sort_in_memory = cpu_operator_cost × N × log(N)  [quicksort]
-- sort_on_disk   = seq_page_cost × N × log(N) / log(work_mem_pages)  [merge sort]

-- See cost breakdown in EXPLAIN:
EXPLAIN (FORMAT JSON)
SELECT * FROM orders WHERE user_id = 123;
-- JSON output includes startup_cost, total_cost per node
-- Parse with: jq '.[] | .Plan | recurse(.Plans[]?) | {node: ."Node Type", cost: ."Total Cost"}'
```

### When Cost Model Fails

```sql
-- 1. Highly correlated predicates (planner assumes independence):
SELECT * FROM orders WHERE country = 'US' AND state = 'CA';
-- Planner estimates: selectivity(country='US') × selectivity(state='CA')
-- = 0.3 × 0.02 = 0.006 (0.6% of rows)
-- Reality: state='CA' only valid for country='US' rows = 0.05 (5% of rows)
-- Fix: CREATE STATISTICS (dependencies) ON country, state FROM orders;

-- 2. Skewed distributions (histogram doesn't capture extremes):
SELECT * FROM orders WHERE user_id = 1;  -- admin user, 5M orders
-- Planner estimates: 500M / 100k users = 5000 orders
-- Reality: 5,000,000 orders (1000× off)
-- Fix: Increase statistics target for user_id column
ALTER TABLE orders ALTER COLUMN user_id SET STATISTICS 1000;
ANALYZE orders;

-- 3. Function-wrapped predicates (planner can't estimate):
SELECT * FROM orders WHERE LOWER(status) = 'pending';
-- Planner has no statistics for LOWER(status)
-- Default estimate: 0.5% selectivity (1/200 default for unknown)
-- Fix: Use expression statistics or rewrite to avoid function
ALTER TABLE orders ALTER COLUMN status
  ADD CHECK (status = LOWER(status));  -- enforce lowercase at insert
-- Now: WHERE status = 'pending' → planner uses column statistics ✓
```

### Follow-up Probes
- *"The planner's cost estimate for your query is 1,200 but the actual execution shows cost equivalent to 50,000. Walk through every possible source of this discrepancy."*
- *"When would you intentionally miscalibrate cost parameters to improve query performance, and what are the risks?"*

---

## Q16. Full-Text Search Architecture and Ranking Optimization

### Question
> Design a production full-text search system in PostgreSQL that handles 50M documents, supports phrase search, fuzzy matching, faceted filtering, and relevance ranking. Address index maintenance and update latency.

### Foundation: tsvector and tsquery

```sql
-- tsvector: preprocessed, normalized text representation
SELECT to_tsvector('english', 'PostgreSQL is the world''s most advanced open source database');
-- 'advanc':8 'databas':10 'open':9 'postgresql':1 'sourc':? 'world':4
-- Note: stopwords removed, words stemmed, positions stored

-- tsquery: search expression
SELECT to_tsquery('english', 'postgresql & (tuning | optimization)');
-- 'postgresql' & ( 'tune' | 'optim' )  ← stemmed

-- Phrase query (position-aware):
SELECT to_tsquery('english', 'query <-> tuning');  -- "query" immediately before "tuning"
SELECT to_tsquery('english', 'query <2> performance');  -- within 2 words

-- Prefix search (useful for autocomplete):
SELECT to_tsquery('english', 'postgr:*');  -- matches "PostgreSQL", "postgres", etc.
```

### Schema for Production FTS

```sql
-- Generated tsvector column (automatically maintained):
CREATE TABLE articles (
  id            BIGSERIAL PRIMARY KEY,
  title         TEXT NOT NULL,
  body          TEXT NOT NULL,
  author_id     BIGINT REFERENCES users(id),
  category      TEXT,
  tags          TEXT[],
  published_at  TIMESTAMPTZ,
  -- Weighted FTS vector: title words count more than body words
  search_vector TSVECTOR GENERATED ALWAYS AS (
    SETWEIGHT(to_tsvector('english', COALESCE(title, '')), 'A') ||
    SETWEIGHT(to_tsvector('english', COALESCE(body, '')), 'B') ||
    SETWEIGHT(to_tsvector('english', ARRAY_TO_STRING(COALESCE(tags, '{}'), ' ')), 'C')
  ) STORED
);

-- Index for FTS:
CREATE INDEX idx_articles_fts ON articles USING GIN (search_vector);

-- Composite index for faceted search (category + FTS):
-- GIN doesn't support composite indexes — use separate indexes + BitmapAnd
CREATE INDEX idx_articles_category ON articles(category, published_at DESC);
```

### Relevance Ranking

```sql
-- ts_rank: frequency-based ranking
-- ts_rank_cd: cover density ranking (rewards phrase proximity)

SELECT
  id,
  title,
  ts_rank(search_vector, query) AS rank_freq,
  ts_rank_cd(search_vector, query, 32) AS rank_cd,
  -- 32 = normalize by document length (divides by log(length))
  ts_headline('english', body, query,
    'MaxFragments=3, MaxWords=30, MinWords=15, StartSel=<b>, StopSel=</b>'
  ) AS snippet
FROM articles,
  to_tsquery('english', 'postgresql & optimization') AS query
WHERE search_vector @@ query
ORDER BY ts_rank_cd(search_vector, query, 32) DESC
LIMIT 20;

-- Weights affect ts_rank:
-- setweight('A') contributes more to rank than 'B', 'C', 'D'
-- Custom weight array: ts_rank(vector, query, '{0.1, 0.2, 0.4, 1.0}')
--                                               D      C      B     A
```

### Fuzzy Search Combination

```sql
-- Combine FTS with pg_trgm for typo tolerance:
CREATE EXTENSION pg_trgm;
CREATE INDEX idx_articles_title_trgm ON articles USING GIN (title gin_trgm_ops);

-- Query: FTS for semantic match + trigram for fuzzy title match
SELECT DISTINCT ON (a.id)
  a.id,
  a.title,
  GREATEST(
    ts_rank(a.search_vector, fts_query),
    similarity(a.title, 'postgresq tuning')  -- typo-tolerant
  ) AS relevance_score
FROM articles a,
  plainto_tsquery('english', 'postgresql tuning') AS fts_query
WHERE
  a.search_vector @@ fts_query        -- semantic match
  OR a.title % 'postgresq tuning'     -- fuzzy title match (% = similarity threshold)
ORDER BY a.id, relevance_score DESC;
```

### High-Update FTS — Maintaining Index Freshness

```sql
-- Problem: GIN index updates are expensive for high-write tables
-- GIN inserts are buffered but can cause "pending list" bloat

-- Check pending list size:
SELECT
  indexrelname,
  pg_size_pretty(pg_relation_size(indexrelid)) AS index_size
FROM pg_stat_user_indexes
WHERE indexrelname = 'idx_articles_fts';

-- Control GIN pending list:
ALTER INDEX idx_articles_fts SET (fastupdate = on, gin_pending_list_limit = 4096);
-- fastupdate: buffers inserts in pending list (default on)
-- gin_pending_list_limit: flush pending list when this size exceeded (in kB)

-- For very high write rates: trigger-based async refresh
-- Insert to articles → mark dirty → async job updates search vector

-- Alternative: Separate search index table (decoupled from writes)
CREATE TABLE article_search_index (
  article_id   BIGINT PRIMARY KEY REFERENCES articles(id),
  search_vector TSVECTOR,
  indexed_at   TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX ON article_search_index USING GIN (search_vector);

-- Update search index asynchronously (worker process):
-- Read articles updated since last_indexed_at
-- Recompute tsvector
-- UPSERT into article_search_index
-- Allows search index to lag slightly without blocking writes
```

### Follow-up Probes
- *"Users search in Spanish and English mixed. How do you handle multi-language tsvector in one index?"*
- *"ts_rank is giving poor results for short documents (they rank lower than long documents containing same terms). Why, and how do you fix it?"*

---

## Q17. Anti-Patterns Hall of Shame

### Question
> List the most costly SQL anti-patterns you've seen in 20 years, with the exact execution penalty each one imposes and the fix.

### Anti-Pattern 1 — SELECT * in Production Queries

```sql
-- The invisible performance tax:
SELECT * FROM orders WHERE user_id = 123;
-- Fetches ALL columns: id, user_id, status, total, country,
-- created_at, updated_at, metadata (JSONB = potentially huge!)

-- Problems:
-- 1. Cannot use index-only scans (must fetch heap for all columns)
-- 2. Metadata JSONB column: each row = hundreds of extra bytes
-- 3. Network transfer: 10× more data than needed
-- 4. Planner can't use covering index optimization

-- Fix: Always project only needed columns
SELECT id, status, total, created_at
FROM orders WHERE user_id = 123;
-- Now: covering index on (user_id, created_at DESC) INCLUDE (status, total)
-- = Index Only Scan, zero heap access ✓
```

### Anti-Pattern 2 — Implicit Type Cast in WHERE Clause

```sql
-- Index exists on user_id (INTEGER), but:
SELECT * FROM orders WHERE user_id = '12345';  -- string literal
-- PostgreSQL coerces string to integer: user_id = '12345'::integer ✓ (safe)

-- DANGEROUS: index on varchar column, compared to integer
CREATE INDEX ON users(phone_number);  -- VARCHAR column
SELECT * FROM users WHERE phone_number = 5551234567;  -- integer literal
-- PostgreSQL must cast all index entries to integer for comparison
-- OR cast the literal to varchar → depends on operator precedence
-- Result: often a full index scan or seq scan instead of index seek

-- Always cast explicitly:
SELECT * FROM users WHERE phone_number = '5551234567';  -- varchar literal ✓

-- Function-wrapped cast (blocks index):
SELECT * FROM orders WHERE CAST(user_id AS TEXT) = '123';
-- Index on user_id (integer) is USELESS — function wrapping prevents use
-- Fix:
SELECT * FROM orders WHERE user_id = 123;  -- remove cast ✓
```

### Anti-Pattern 3 — OFFSET Pagination at High Page Numbers

```sql
-- OFFSET must scan and discard all preceding rows:
SELECT * FROM products ORDER BY created_at DESC LIMIT 20 OFFSET 100000;
-- Scans 100,020 rows, returns last 20
-- OFFSET 1000000 → scans 1,000,020 rows. Gets slower with each page.

-- Fix: Keyset pagination (cursor-based)
-- Page 1:
SELECT id, name, created_at FROM products ORDER BY created_at DESC LIMIT 20;
-- Get: last_created_at = '2024-01-15 10:00:00', last_id = 5000

-- Page 2:
SELECT id, name, created_at FROM products
WHERE (created_at, id) < ('2024-01-15 10:00:00', 5000)  -- cursor
ORDER BY created_at DESC, id DESC
LIMIT 20;
-- Constant cost regardless of page depth ✓
-- Requires: composite index on (created_at DESC, id DESC)
```

### Anti-Pattern 4 — N+1 Queries

```sql
-- Application code:
-- orders = SELECT * FROM orders WHERE user_id = 123    (1 query)
-- for order in orders:
--     items = SELECT * FROM order_items WHERE order_id = order.id  (N queries)

-- This generates N+1 database round trips
-- For 100 orders → 101 queries → 101 × network latency

-- Fix: JOIN in single query
SELECT
  o.id AS order_id,
  o.total,
  oi.product_id,
  oi.quantity,
  oi.price
FROM orders o
JOIN order_items oi ON oi.order_id = o.id
WHERE o.user_id = 123;
-- 1 query, 1 round trip ✓

-- Or: batch fetch with IN
-- orders = SELECT * FROM orders WHERE user_id = 123
-- order_ids = [1, 2, 3, ...]
-- items = SELECT * FROM order_items WHERE order_id = ANY($1)
-- 2 queries instead of N+1 ✓
```

### Anti-Pattern 5 — Inefficient LIKE with Function

```sql
-- Kills index usage with function wrapping:
SELECT * FROM users WHERE LOWER(email) LIKE LOWER('%JOHN%');
-- LOWER() wraps the column → index on email cannot be used

-- Fix 1: Store lowercase, query lowercase
ALTER TABLE users ALTER COLUMN email TYPE TEXT;
-- Application enforces lowercase on insert
SELECT * FROM users WHERE email LIKE '%john%';
CREATE INDEX ON users USING GIN (email gin_trgm_ops);  -- for LIKE '%...%'

-- Fix 2: Expression index + query must match exactly
CREATE INDEX ON users (LOWER(email));
SELECT * FROM users WHERE LOWER(email) = 'john@example.com';  -- uses expression index ✓
SELECT * FROM users WHERE LOWER(email) LIKE 'john%';          -- uses expression index ✓
SELECT * FROM users WHERE LOWER(email) LIKE '%john%';          -- does NOT use B-Tree ✗
```

### Anti-Pattern 6 — NOT IN with Potential NULLs

```sql
-- Already covered in Q6 — the NULL trap:
SELECT * FROM users WHERE id NOT IN (SELECT user_id FROM orders);
-- If ANY order has NULL user_id → returns EMPTY SET

-- Fix: NOT EXISTS (always use for anti-joins):
SELECT * FROM users u
WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id);
```

### Anti-Pattern 7 — Storing Delimited Lists in Text Columns

```sql
-- Common in legacy systems:
CREATE TABLE products (
  id       BIGSERIAL,
  tag_ids  TEXT  -- stored as '1,4,7,23,45'
);
-- Queries require pattern matching or splitting:
SELECT * FROM products WHERE tag_ids LIKE '%,7,%';  -- full scan, always
SELECT * FROM products WHERE tag_ids ~ '(^|,)7(,|$)'; -- slightly better, still full scan

-- Fix 1: Array column (native PostgreSQL)
ALTER TABLE products ADD COLUMN tag_id_array INTEGER[];
-- UPDATE to populate from split
UPDATE products SET tag_id_array = string_to_array(tag_ids, ',')::INTEGER[];
CREATE INDEX ON products USING GIN (tag_id_array);

SELECT * FROM products WHERE tag_id_array @> ARRAY[7];  -- uses GIN index ✓
SELECT * FROM products WHERE tag_id_array && ARRAY[7, 23];  -- contains any ✓

-- Fix 2: Junction table (proper normalization)
CREATE TABLE product_tags (
  product_id BIGINT REFERENCES products(id),
  tag_id     INTEGER REFERENCES tags(id),
  PRIMARY KEY (product_id, tag_id)
);
CREATE INDEX ON product_tags(tag_id);  -- for "find products by tag"
```

---

## Q18. Query Optimization Diagnostic Runbook

### The Complete Optimization Workflow

```sql
-- ═══════════════════════════════════════════════════════════════
-- STEP 1: FIND THE PROBLEM QUERIES
-- ═══════════════════════════════════════════════════════════════

-- Top queries by total time:
SELECT
  LEFT(query, 100) AS query_preview,
  calls,
  ROUND((total_exec_time / calls)::numeric, 2) AS avg_ms,
  ROUND(stddev_exec_time::numeric, 2) AS stddev_ms,
  ROUND((total_exec_time / SUM(total_exec_time) OVER () * 100)::numeric, 2) AS pct_of_total,
  rows / calls AS avg_rows,
  shared_blks_hit,
  shared_blks_read,
  temp_blks_written / NULLIF(calls, 0) AS avg_temp_blocks
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 20;

-- Highest variance queries (unpredictable — plan instability):
SELECT
  LEFT(query, 100),
  calls,
  ROUND(mean_exec_time::numeric, 2) AS mean_ms,
  ROUND(stddev_exec_time::numeric, 2) AS stddev_ms,
  ROUND((stddev_exec_time / NULLIF(mean_exec_time, 0))::numeric, 2) AS cv
FROM pg_stat_statements
WHERE calls > 100
ORDER BY cv DESC
LIMIT 10;

-- ═══════════════════════════════════════════════════════════════
-- STEP 2: CAPTURE PRODUCTION PLAN SAFELY
-- ═══════════════════════════════════════════════════════════════

-- Read replica or dev copy with production statistics:
-- Method A: pg_dump --schema-only + pg_restore + ANALYZE with production data sample
-- Method B: auto_explain in postgresql.conf (zero overhead for fast queries)

-- auto_explain setup:
-- shared_preload_libraries = 'auto_explain'
-- auto_explain.log_min_duration = 500
-- auto_explain.log_analyze = true
-- auto_explain.log_buffers = true

-- ═══════════════════════════════════════════════════════════════
-- STEP 3: READ EXPLAIN PLAN — CHECKLIST
-- ═══════════════════════════════════════════════════════════════

-- ✅ Are estimated rows within 2× of actual rows?
-- ✅ Are there Seq Scans on large tables without WHERE clause?
-- ✅ Are Hash Join batch counts > 1? (spill to disk)
-- ✅ Are Sort Method "external merge"? (spill to disk)
-- ✅ Are there SubPlan nodes? (correlated subqueries)
-- ✅ Are Nested Loop loops counts > 10,000?
-- ✅ Buffer reads >> hits? (cache miss rate high)
-- ✅ Index Only Scan heap fetches high? (VM not all-visible)

-- ═══════════════════════════════════════════════════════════════
-- STEP 4: STATISTICS HEALTH CHECK
-- ═══════════════════════════════════════════════════════════════

SELECT
  schemaname,
  relname AS table_name,
  n_live_tup,
  n_dead_tup,
  last_analyze,
  last_autoanalyze,
  NOW() - GREATEST(last_analyze, last_autoanalyze) AS stats_age
FROM pg_stat_user_tables
WHERE schemaname = 'public'
  AND n_live_tup > 100000  -- only care about large tables
ORDER BY stats_age DESC NULLS FIRST
LIMIT 20;

-- ═══════════════════════════════════════════════════════════════
-- STEP 5: INDEX HEALTH CHECK
-- ═══════════════════════════════════════════════════════════════

-- Unused indexes (zero scans since stats reset):
SELECT
  schemaname,
  tablename,
  indexrelname,
  pg_size_pretty(pg_relation_size(indexrelid)) AS size,
  idx_scan AS scan_count
FROM pg_stat_user_indexes
WHERE idx_scan = 0
  AND schemaname = 'public'
  AND indexrelname NOT LIKE '%_pkey'  -- exclude PKs
ORDER BY pg_relation_size(indexrelid) DESC;

-- Index utilization summary:
SELECT
  tablename,
  seq_scan,
  idx_scan,
  ROUND(idx_scan::numeric / NULLIF(seq_scan + idx_scan, 0) * 100, 2) AS index_hit_pct
FROM pg_stat_user_tables
WHERE seq_scan + idx_scan > 100
  AND schemaname = 'public'
ORDER BY seq_scan DESC
LIMIT 20;

-- ═══════════════════════════════════════════════════════════════
-- STEP 6: MEMORY PRESSURE CHECK
-- ═══════════════════════════════════════════════════════════════

-- Cache hit ratio (target: > 99%):
SELECT
  'heap' AS type,
  SUM(heap_blks_hit)::FLOAT /
    NULLIF(SUM(heap_blks_hit) + SUM(heap_blks_read), 0) AS hit_ratio
FROM pg_statio_user_tables
UNION ALL
SELECT
  'index',
  SUM(idx_blks_hit)::FLOAT /
    NULLIF(SUM(idx_blks_hit) + SUM(idx_blks_read), 0)
FROM pg_statio_user_indexes;

-- Temp file usage (work_mem spills):
SELECT
  datname,
  temp_files,
  pg_size_pretty(temp_bytes) AS temp_bytes_spilled
FROM pg_stat_database
ORDER BY temp_bytes DESC;

-- ═══════════════════════════════════════════════════════════════
-- STEP 7: LOCK AND WAIT ANALYSIS
-- ═══════════════════════════════════════════════════════════════

-- Current waits:
SELECT
  pid,
  wait_event_type,
  wait_event,
  NOW() - query_start AS wait_duration,
  LEFT(query, 80) AS query
FROM pg_stat_activity
WHERE wait_event IS NOT NULL
  AND state = 'active'
ORDER BY wait_duration DESC;

-- Lock chain analysis:
WITH RECURSIVE lock_chain AS (
  SELECT
    pid,
    pg_blocking_pids(pid) AS blocked_by,
    query,
    wait_event,
    1 AS depth
  FROM pg_stat_activity
  WHERE cardinality(pg_blocking_pids(pid)) > 0

  UNION ALL

  SELECT
    sa.pid,
    pg_blocking_pids(sa.pid),
    sa.query,
    sa.wait_event,
    lc.depth + 1
  FROM pg_stat_activity sa
  JOIN lock_chain lc ON sa.pid = ANY(lc.blocked_by)
  WHERE lc.depth < 5
)
SELECT * FROM lock_chain ORDER BY depth;

-- ═══════════════════════════════════════════════════════════════
-- STEP 8: BLOAT CHECK
-- ═══════════════════════════════════════════════════════════════

SELECT
  schemaname || '.' || relname AS table_full_name,
  n_live_tup,
  n_dead_tup,
  ROUND(n_dead_tup::NUMERIC / NULLIF(n_live_tup + n_dead_tup, 0) * 100, 2) AS dead_pct,
  last_autovacuum,
  pg_size_pretty(pg_total_relation_size(schemaname || '.' || relname)) AS total_size,
  CASE
    WHEN n_dead_tup::FLOAT / NULLIF(n_live_tup, 0) > 0.3 THEN '🔴 VACUUM NOW'
    WHEN n_dead_tup::FLOAT / NULLIF(n_live_tup, 0) > 0.1 THEN '🟡 VACUUM SOON'
    ELSE '🟢 OK'
  END AS status
FROM pg_stat_user_tables
WHERE n_live_tup > 10000
ORDER BY n_dead_tup DESC
LIMIT 20;
```

### Optimization Decision Tree

```
Query is slow
│
├── Is estimated rows >> actual rows?
│   └── YES → Fix statistics (ANALYZE, increase statistics target, CREATE STATISTICS)
│
├── Is there a Seq Scan on a large table?
│   ├── No WHERE clause → likely correct, consider query redesign
│   ├── Has WHERE clause → missing index, wrong index, or type mismatch
│   └── Selectivity > 20% → seq scan may be correct (check random_page_cost)
│
├── Is there a SubPlan node?
│   └── YES → Rewrite as JOIN or window function (see Q7)
│
├── Is Hash Join batches > 1?
│   └── YES → Increase work_mem or fix row estimate
│
├── Is Sort using "external merge"?
│   └── YES → Increase work_mem or add index for sort order
│
├── Is Nested Loop with loops > 10,000?
│   └── YES → Fix outer row estimate or switch join type
│
├── Is buffer read >> hit ratio?
│   └── YES → Increase shared_buffers or effective_cache_size tuning
│
└── Is query waiting for locks?
    └── YES → Identify blocking query, optimize transaction duration
```

---

## 🏆 Evaluation Rubric for 20-Year Candidates

| Question Area | What Separates Good from Great |
|---------------|-------------------------------|
| Query rewriting | Great: knows exact pipeline stage where each transformation occurs |
| Subquery semantics | Great: explains NULL trap in NOT IN from first principles |
| Aggregate optimization | Great: designs pre-aggregation strategy, knows partial agg limits |
| Window functions | Great: frame clause performance model, named window reuse |
| Index design | Great: HOT rate analysis before adding any index |
| NULL handling | Great: three-valued logic explanation, all NULL aggregate edge cases |
| Correlated subqueries | Great: identifies SubPlan in EXPLAIN, rewrites to LATERAL |
| Materialized views | Great: designs incremental refresh with watermark pattern |
| JSONB | Great: compares jsonb_ops vs jsonb_path_ops index sizes |
| Recursive CTEs | Great: knows exactly when to use closure table vs ltree |
| OR conditions | Great: explains BitmapOr mechanics and lossy bitmap threshold |
| Bulk DML | Great: designs WAL-aware batching with SKIP LOCKED |
| Schema design | Great: type precision, vertical partitioning, temporal modeling |
| Cost model | Great: calibrates random_page_cost for storage tier |
| Full-text search | Great: weights, cover density ranking, GIN pending list management |

### The Ultimate Test Questions

```
1. "Walk me through a query that performed perfectly for 2 years, then 
    suddenly became slow overnight. What changed and how did you find it?"
    → Tests: stats drift detection, data distribution shifts, plan cache invalidation

2. "You need to move 500GB of data between two tables with zero downtime 
    and maintain referential integrity throughout."
    → Tests: batching strategy, FK deferability, partition swap knowledge

3. "Design the query layer for a system that must handle 1M concurrent 
    users each with real-time personalized feeds."
    → Tests: materialized view strategy, connection pooling, read replica routing

4. "Your new junior engineer added 15 indexes to speed up a slow report. 
    OLTP throughput dropped 40%. Walk them through why, and how to fix it."
    → Tests: write amplification, HOT rate, covering index strategy
```

---

## 📚 Essential Reference Queries

```sql
-- ① Worst queries by total execution time
SELECT LEFT(query,80), calls, ROUND(total_exec_time/calls::numeric,2) avg_ms,
       ROUND(total_exec_time::numeric,2) total_ms
FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 15;

-- ② Tables with worst seq scan ratio (missing indexes)
SELECT relname, seq_scan, idx_scan,
       ROUND(seq_scan::numeric/NULLIF(seq_scan+idx_scan,0)*100,2) AS seq_pct
FROM pg_stat_user_tables WHERE seq_scan+idx_scan > 100 ORDER BY seq_pct DESC LIMIT 15;

-- ③ Index bloat and usage
SELECT indexrelname, pg_size_pretty(pg_relation_size(indexrelid)) AS size, idx_scan
FROM pg_stat_user_indexes WHERE schemaname='public' ORDER BY pg_relation_size(indexrelid) DESC LIMIT 20;

-- ④ Cache hit ratio
SELECT SUM(heap_blks_hit)::FLOAT/NULLIF(SUM(heap_blks_hit)+SUM(heap_blks_read),0) AS heap_hit_ratio,
       SUM(idx_blks_hit)::FLOAT/NULLIF(SUM(idx_blks_hit)+SUM(idx_blks_read),0) AS idx_hit_ratio
FROM pg_statio_user_tables, pg_statio_user_indexes;

-- ⑤ Temp file spills (work_mem pressure)
SELECT datname, temp_files, pg_size_pretty(temp_bytes) AS spilled
FROM pg_stat_database ORDER BY temp_bytes DESC LIMIT 10;

-- ⑥ Current locks and waiters
SELECT pid, wait_event_type, wait_event, NOW()-query_start AS age, LEFT(query,80)
FROM pg_stat_activity WHERE wait_event IS NOT NULL ORDER BY age DESC LIMIT 20;

-- ⑦ Table bloat score
SELECT relname, n_dead_tup, n_live_tup,
       ROUND(n_dead_tup::numeric/NULLIF(n_live_tup+n_dead_tup,0)*100,2) AS dead_pct,
       last_autovacuum
FROM pg_stat_user_tables ORDER BY n_dead_tup DESC LIMIT 15;

-- ⑧ Autovacuum status per table
SELECT relname, last_vacuum, last_autovacuum, vacuum_count, autovacuum_count,
       n_dead_tup, autovacuum_count
FROM pg_stat_user_tables WHERE n_live_tup > 50000 ORDER BY n_dead_tup DESC LIMIT 20;

-- ⑨ Index-only scan efficiency
SELECT indexrelname, idx_scan, idx_tup_read, idx_tup_fetch,
       ROUND((1-idx_tup_fetch::numeric/NULLIF(idx_tup_read,0))*100,2) AS heap_fetch_pct
FROM pg_stat_user_indexes WHERE idx_scan > 100 ORDER BY heap_fetch_pct DESC LIMIT 15;

-- ⑩ XID wraparound risk
SELECT datname, age(datfrozenxid) AS xid_age, 2147483647-age(datfrozenxid) AS xids_remaining
FROM pg_database ORDER BY xid_age DESC;

-- ⑪ Replication lag
SELECT application_name, state, sent_lsn, replay_lsn,
       pg_wal_lsn_diff(sent_lsn, replay_lsn) AS lag_bytes
FROM pg_stat_replication ORDER BY lag_bytes DESC;

-- ⑫ Top tables by total size
SELECT schemaname, tablename,
       pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS total,
       pg_size_pretty(pg_relation_size(schemaname||'.'||tablename)) AS table_only,
       pg_size_pretty(pg_indexes_size(schemaname||'.'||tablename)) AS indexes_only
FROM pg_tables WHERE schemaname='public' ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC LIMIT 20;
```

---

*Generated for: 20-Year Backend Engineering Interviews | Focus: Query Optimization*  
*PostgreSQL Versions: 12–17 | Last Updated: 2025*
