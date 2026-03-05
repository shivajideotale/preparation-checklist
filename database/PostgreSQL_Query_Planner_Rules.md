# PostgreSQL Query Planner — Rules & Decision Logic

> A deep-dive reference covering every rule the PostgreSQL cost-based optimizer follows when deciding *how* to execute your query.

---

## Table of Contents

1. [Rule 1 — The Planner Always Chooses the Lowest-Cost Plan](#rule-1--the-planner-always-chooses-the-lowest-cost-plan)
2. [Rule 2 — Seq Scan Wins When Selectivity is Low](#rule-2--seq-scan-wins-when-selectivity-is-low)
3. [Rule 3 — Statistics Drive All Estimates — Stale Stats = Bad Plans](#rule-3--statistics-drive-all-estimates--stale-stats--bad-plans)
4. [Rule 4 — The Planner Assumes Column Independence](#rule-4--the-planner-assumes-column-independence)
5. [Rule 5 — Join Order Matters — The Planner Searches the Space](#rule-5--join-order-matters--the-planner-searches-the-space)
6. [Rule 6 — Join Algorithm is Chosen Based on Set Size & Indexes](#rule-6--join-algorithm-is-chosen-based-on-set-size--indexes)
7. [Rule 7 — Index Types Are Used Differently](#rule-7--index-types-are-used-differently)
8. [Rule 8 — Implicit Type Casts Kill Index Usage](#rule-8--implicit-type-casts-kill-index-usage)
9. [Rule 9 — Partial Indexes — Predicate Must Match](#rule-9--partial-indexes--predicate-must-match)
10. [Rule 10 — `work_mem` Controls Sort and Hash Memory](#rule-10--work_mem-controls-sort-and-hash-memory)
11. [Rule 11 — `enable_*` Flags for Diagnosis Only](#rule-11--enable_-flags-for-diagnosis-only)
12. [Rule 12 — Parallel Query Rules](#rule-12--parallel-query-rules)
13. [Rule 13 — CTEs Are Optimization Fences (Pre-PG12)](#rule-13--ctes-are-optimization-fences-pre-pg12)
14. [Rule 14 — The Planner Respects LIMIT — Startup Cost Matters](#rule-14--the-planner-respects-limit--startup-cost-matters)
15. [Rule 15 — NOT IN vs NOT EXISTS](#rule-15--not-in-vs-not-exists)
16. [Summary: The Golden Rules](#summary-the-golden-rules)

---

## Rule 1 — The Planner Always Chooses the Lowest-Cost Plan

The planner is a **cost-based optimizer (CBO)**. It generates multiple possible execution plans, assigns a numeric cost to each, and picks the cheapest one. It never guesses — it calculates.

```sql
-- The planner compares all candidate plans internally
EXPLAIN SELECT * FROM orders WHERE customer_id = 5;

--   Plan A: Seq Scan  cost = 8500
--   Plan B: Index Scan cost = 12
--   → Chooses Plan B
```

> **Rule:** If you think the planner chose wrong, it either has wrong statistics or wrong cost constants — never blame the planner first. Diagnose before forcing.

---

## Rule 2 — Seq Scan Wins When Selectivity is Low

The planner uses `Seq Scan` when the query is expected to return a **large fraction of the table** (~10–20%+ of pages), because sequential I/O is cheaper than random I/O per page.

```
random_page_cost = 4.0   (default — tuned for spinning disk)
seq_page_cost    = 1.0

→ Fetching rows via Index Scan costs ~4× more per page than Seq Scan
→ At high row counts, sequential wins
```

```sql
-- Fix for SSDs: lower random_page_cost to reflect real hardware
ALTER SYSTEM SET random_page_cost = 1.1;
SELECT pg_reload_conf();
```

> **Rule:** Selectivity drives scan choice. An index is only used when the planner estimates it will *actually* read fewer pages overall. On SSDs, always set `random_page_cost = 1.1`.

---

## Rule 3 — Statistics Drive All Estimates — Stale Stats = Bad Plans

The planner estimates row counts using statistics stored in `pg_statistic` (exposed via `pg_stats`). If stats are stale, every downstream decision — scan type, join order, join algorithm — is wrong.

```sql
-- Check stats freshness
SELECT relname, last_analyze, last_autoanalyze, n_live_tup, n_dead_tup
FROM pg_stat_user_tables
WHERE relname = 'orders';

-- Fix stale stats on a table
ANALYZE orders;

-- Fix globally
ANALYZE;
```

**Key statistics fields:**

| Field | Meaning |
|---|---|
| `n_distinct` | Estimated distinct values (`-1` = unique, negative fraction = fraction of rows) |
| `most_common_vals` | Top N most frequent values |
| `most_common_freqs` | Frequency of each common value |
| `histogram_bounds` | Distribution buckets for range queries |
| `correlation` | Physical vs logical order (`0` = random, `1` = perfectly sorted) |

```sql
-- Increase statistics target for skewed columns (default: 100)
ALTER TABLE orders ALTER COLUMN status SET STATISTICS 500;
ANALYZE orders;
```

> **Rule:** Always run `ANALYZE` after bulk inserts, deletes, or schema changes. Autovacuum may lag behind aggressive write workloads.

---

## Rule 4 — The Planner Assumes Column Independence

For a query like `WHERE city = 'Mumbai' AND zip = '400001'`, the planner multiplies the selectivities of each predicate independently:

```
P(city = 'Mumbai') = 0.01
P(zip  = '400001') = 0.001
Combined estimate  = 0.01 × 0.001 = 0.00001   ← WRONG if columns are correlated
```

In reality, if you're in Mumbai, almost all zip codes are Mumbai zip codes — the real selectivity is much higher.

**Fix: Extended statistics**

```sql
-- Teach the planner about column correlation
CREATE STATISTICS orders_city_zip (dependencies) ON city, zip FROM orders;
ANALYZE orders;

-- Also supports MCV (most common value combinations) and ndistinct
CREATE STATISTICS orders_city_zip (dependencies, mcv, ndistinct) ON city, zip FROM orders;
```

> **Rule:** Any time you filter on 2+ related columns and row estimates are wildly off, extended statistics are the solution.

---

## Rule 5 — Join Order Matters — The Planner Searches the Space

For N-table joins, the planner explores possible join orderings. The number of orderings grows factorially:

| Tables | Possible Orderings |
|---|---|
| 3 | 6 |
| 5 | 120 |
| 8 | 40,320 |
| 12+ | Genetic algorithm (GEQO) kicks in |

```sql
-- Threshold where planner switches from exhaustive to genetic algorithm
SHOW geqo_threshold;  -- default: 12

-- Disable GEQO to force exhaustive search (testing only)
SET geqo = off;

-- Tune GEQO effort (1=fast/worse, 10=slow/better, default: 5)
SET geqo_effort = 8;
```

> **Rule:** The planner tries to join the smallest intermediate result sets first. Badly skewed statistics mislead join order selection — fix the stats, not the join order manually.

---

## Rule 6 — Join Algorithm is Chosen Based on Set Size & Indexes

| Condition | Chosen Algorithm |
|---|---|
| Inner set is small or has an index on the join key | `Nested Loop` |
| Both sets are large, equi-join, no useful index | `Hash Join` |
| Both inputs are already sorted on the join key | `Merge Join` |

**Algorithm details:**

- **Nested Loop**: O(N × M) worst case, but excellent when the inner side has an index. The inner scan executes once per outer row.
- **Hash Join**: Builds a hash table on the smaller set, then probes it with each row from the larger set. Requires `work_mem`. If hash table is too large → spills to disk (`Batches > 1`).
- **Merge Join**: Both inputs must be sorted on the join key. If not already sorted, PostgreSQL inserts a `Sort` node above each input.

```sql
-- Force/forbid join algorithms for testing
SET enable_nestloop  = off;
SET enable_hashjoin  = off;
SET enable_mergejoin = off;
-- Always restore after testing!
SET enable_nestloop  = on;
SET enable_hashjoin  = on;
SET enable_mergejoin = on;
```

> **Rule:** The planner never uses `Nested Loop` if it requires a full `Seq Scan` on the inner side for every outer row — it switches to `Hash Join` or `Merge Join` at that point.

---

## Rule 7 — Index Types Are Used Differently

| Index Type | Operators Supported | Best Used For |
|---|---|---|
| `B-tree` (default) | `=`, `<`, `>`, `<=`, `>=`, `BETWEEN`, `IN`, `LIKE 'foo%'`, `ORDER BY` | General purpose — most queries |
| `Hash` | `=` only | Pure equality lookups (faster than B-tree for `=`) |
| `GIN` | `@>`, `&&`, `@@`, `?` | Arrays, JSONB keys, full-text search |
| `GiST` | Geometric, range, full-text | Spatial data, range types |
| `BRIN` | Range-based `=`, `<`, `>` | Very large, naturally ordered tables (e.g., time-series) |
| `SP-GiST` | Non-balanced trees | IP ranges, phone trees, hierarchical data |

```sql
-- For LIKE with leading wildcard (e.g. LIKE '%foo'), use trigram GIN:
CREATE EXTENSION pg_trgm;
CREATE INDEX idx_title_trgm ON articles USING gin(title gin_trgm_ops);
WHERE title LIKE '%postgresql%';  -- now uses the GIN index ✅

-- For JSONB key lookups:
CREATE INDEX idx_data_gin ON events USING gin(payload);
WHERE payload @> '{"type": "click"}';

-- For time-series (append-only large tables):
CREATE INDEX idx_orders_brin ON orders USING brin(created_at);
```

> **Rule:** The planner only considers an index if the operator in the `WHERE` clause matches the index's **operator class**. A `B-tree` index on `name` will **not** be used for `LIKE '%foo'` (trailing wildcard).

---

## Rule 8 — Implicit Type Casts Kill Index Usage

If your `WHERE` clause causes an implicit type cast on the indexed column, the index is bypassed entirely because the index stores values of the original type, not the cast type.

```sql
-- ❌ BAD: user_id is INTEGER, '42' is TEXT → implicit cast → index bypassed
WHERE user_id = '42'

-- ✅ GOOD: types match → index used
WHERE user_id = 42

-- ❌ BAD: wrapping column in a function → index bypassed
WHERE LOWER(email) = 'user@example.com'
WHERE DATE(created_at) = '2024-01-01'
WHERE EXTRACT(year FROM created_at) = 2024

-- ✅ GOOD: use a functional index to match
CREATE INDEX idx_email_lower ON users (LOWER(email));
WHERE LOWER(email) = 'user@example.com'  -- index used ✅

-- ✅ GOOD: rewrite to keep column bare
WHERE created_at >= '2024-01-01' AND created_at < '2024-01-02'
```

> **Rule:** Never wrap indexed columns in functions or cast them in `WHERE` clauses. Move all transformations to the *value side*, not the *column side*.

---

## Rule 9 — Partial Indexes — Predicate Must Match

A partial index is only considered by the planner when the query's `WHERE` clause *implies* the index predicate.

```sql
-- Create a partial index for a common filtered query
CREATE INDEX idx_pending ON orders(created_at) WHERE status = 'pending';

-- ✅ Planner WILL use this index (query predicate implies index predicate)
SELECT * FROM orders
WHERE status = 'pending'
  AND created_at > NOW() - INTERVAL '7 days';

-- ❌ Planner WILL NOT use this index (status condition is missing)
SELECT * FROM orders
WHERE created_at > NOW() - INTERVAL '7 days';
```

**Benefits of partial indexes:**

- Smaller index size (only indexes rows matching the predicate)
- Faster writes (fewer rows to maintain)
- Planner gets better selectivity estimates within the indexed subset

> **Rule:** Partial indexes are highly efficient but require the query to include the index predicate (or one that logically implies it). Design partial indexes around your most common, most selective query patterns.

---

## Rule 10 — `work_mem` Controls Sort and Hash Memory Per Operation

`work_mem` is the memory budget for a **single sort or hash operation**. One query can use `work_mem` multiple times if it contains multiple sorts, hashes, or joins.

```sql
SHOW work_mem;  -- default: 4MB (very low for real workloads!)

-- Set for current session (analytical queries)
SET work_mem = '256MB';

-- Set globally in postgresql.conf
work_mem = 64MB

-- Check if Sort spilled to disk in EXPLAIN ANALYZE output:
--   "Batches: 1"  → fit in memory ✅
--   "Batches: 8"  → spilled to disk ❌ → increase work_mem
```

**Memory usage formula:**

```
Max RAM for sorts/hashes = max_connections × work_mem × (nodes per query)

Example:
  100 connections × 256MB × 3 nodes = 76.8GB potential RAM usage
```

> **Rule:** `work_mem = 4MB` (default) almost always causes disk spills on real workloads. Tune upward carefully — but remember each connection can consume it multiple times simultaneously.

---

## Rule 11 — `enable_*` Flags for Diagnosis Only

PostgreSQL exposes flags to disable individual plan choices. These are **diagnostic tools** — not production tuning knobs.

```sql
SET enable_seqscan      = off;  -- Force index use (testing)
SET enable_indexscan    = off;  -- Forbid index scans
SET enable_bitmapscan   = off;  -- Forbid bitmap scans
SET enable_nestloop     = off;  -- Forbid nested loop joins
SET enable_hashjoin     = off;  -- Forbid hash joins
SET enable_mergejoin    = off;  -- Forbid merge joins
SET enable_hashagg      = off;  -- Forbid hash aggregates
SET enable_sort         = off;  -- Forbid explicit sorts
SET enable_parallel_query = off; -- Disable parallelism
```

**Correct diagnostic workflow:**

```sql
-- 1. See what plan the planner naturally chooses
EXPLAIN ANALYZE SELECT ...;

-- 2. Disable one node type and compare
SET enable_seqscan = off;
EXPLAIN ANALYZE SELECT ...;

-- 3. If the forced plan is faster → statistics or cost constants are wrong
--    Fix stats, fix random_page_cost — don't leave enable_seqscan = off permanently

-- 4. Always restore
SET enable_seqscan = on;
```

> **Rule:** These flags make the planner choose a "least bad" alternative when the preferred type is disabled — not necessarily a *good* plan. Never set them permanently in production.

---

## Rule 12 — Parallel Query Rules

The planner enables parallel execution when all of the following are true:

1. `max_parallel_workers_per_gather > 0`
2. Table is larger than `min_parallel_table_scan_size` (default: 8MB)
3. Query is read-only (no `FOR UPDATE`, no data-modifying CTEs)
4. No functions marked `PARALLEL UNSAFE` are called

```sql
-- Check a function's parallel safety
SELECT proname, proparallel FROM pg_proc WHERE proname = 'my_function';
-- proparallel values:
--   's' = SAFE       → can run in parallel workers
--   'r' = RESTRICTED → can run in parallel, but not in workers
--   'u' = UNSAFE     → disables parallel query entirely (default for user functions)

-- Fix: mark your function safe after verifying it has no side effects
ALTER FUNCTION my_function() PARALLEL SAFE;

-- Tune parallelism
SET max_parallel_workers_per_gather = 4;
SET min_parallel_table_scan_size    = '8MB';
SET min_parallel_index_scan_size    = '512kB';

-- Disable parallelism for a session (e.g., OLTP queries)
SET max_parallel_workers_per_gather = 0;
```

> **Rule:** Any user-defined function is `PARALLEL UNSAFE` by default. If your query calls one, the planner disables parallelism for the entire query — even if the function is perfectly safe to parallelize.

---

## Rule 13 — CTEs Are Optimization Fences (Pre-PostgreSQL 12)

Before PostgreSQL 12, every `WITH` (CTE) clause was **always materialized** — treated as an opaque black box. The planner could not push down predicates or optimize across CTE boundaries.

```sql
-- Pre-PG12: CTE always materialized → planner cannot push WHERE inside
WITH recent AS (
  SELECT * FROM orders WHERE created_at > NOW() - INTERVAL '30 days'
)
SELECT * FROM recent WHERE customer_id = 42;
-- Execution: scan ALL recent orders first → then filter by customer_id ❌

-- PG12+: CTE is NOT materialized by default (inlined like a subquery)
-- Planner pushes customer_id = 42 into the CTE scan ✅

-- Force materialization explicitly when needed (e.g., prevent repeated evaluation,
-- or intentionally create a fence to stabilize a plan):
WITH recent AS MATERIALIZED (
  SELECT * FROM orders WHERE created_at > NOW() - INTERVAL '30 days'
)
SELECT * FROM recent WHERE customer_id = 42;
```

> **Rule:** In PostgreSQL 12+, CTEs are inlined by default. Use `MATERIALIZED` explicitly only when you *want* to prevent the planner from optimizing across the CTE boundary, or to ensure a volatile function is called only once.

---

## Rule 14 — The Planner Respects LIMIT — Startup Cost Matters

When `LIMIT N` is present, the planner prefers plans with **low startup cost** even if their total cost is higher, because it only needs to return N rows, not all rows.

The planner adjusts cost estimation with the formula:

```
adjusted_cost = startup_cost + (limit / total_rows) × (total_cost - startup_cost)
```

```sql
-- Without LIMIT: Hash Join (low total cost) wins
-- With LIMIT 10: Nested Loop (low startup cost) wins because the planner
--                expects to stop early — no need to build the full hash table

EXPLAIN SELECT o.*, c.name
FROM orders o
JOIN customers c ON c.id = o.customer_id
ORDER BY o.created_at DESC
LIMIT 10;
-- Likely: Index Scan on created_at + Nested Loop (not Seq Scan + Hash Join)
```

> **Rule:** `LIMIT` changes the entire cost calculation. Adding or removing a `LIMIT` can flip the planner's choice between `Seq Scan` and `Index Scan`, or between `Hash Join` and `Nested Loop`.

---

## Rule 15 — NOT IN vs NOT EXISTS

`NOT IN` and `NOT EXISTS` are logically similar but the planner handles them very differently — and `NOT IN` has dangerous `NULL` semantics.

```sql
-- ❌ NOT IN: NULL semantics cause silent bugs + poor planner optimization
SELECT * FROM orders
WHERE customer_id NOT IN (SELECT id FROM customers);
-- If ANY row in customers has id = NULL, the ENTIRE result set is empty!
-- The planner also cannot use an anti-join efficiently here.

-- ✅ NOT EXISTS: NULL-safe + planner uses efficient anti-join
SELECT * FROM orders o
WHERE NOT EXISTS (
  SELECT 1 FROM customers c WHERE c.id = o.customer_id
);

-- ✅ Alternative: LEFT JOIN anti-join pattern (also NULL-safe and efficient)
SELECT o.*
FROM orders o
LEFT JOIN customers c ON c.id = o.customer_id
WHERE c.id IS NULL;
```

**Why `NOT IN` fails with NULLs:**

```sql
-- This returns empty even if most customers exist:
SELECT * FROM orders WHERE customer_id NOT IN (1, 2, NULL);
-- SQL logic: customer_id != 1 AND customer_id != 2 AND customer_id != NULL
-- customer_id != NULL is always UNKNOWN → entire AND is UNKNOWN → row excluded
```

> **Rule:** Always prefer `NOT EXISTS` or `LEFT JOIN ... WHERE IS NULL` over `NOT IN` when NULLs might be present. The planner also optimizes anti-joins (from `NOT EXISTS`) far more efficiently than correlated `NOT IN` subqueries.

---

## Summary: The Golden Rules

| # | Rule | Key Action |
|---|---|---|
| 1 | Planner picks lowest-cost plan — always | Diagnose before forcing plans |
| 2 | Seq Scan wins at low selectivity | Set `random_page_cost = 1.1` on SSDs |
| 3 | Stale stats = bad plans | Run `ANALYZE` after bulk writes |
| 4 | Planner assumes column independence | Use extended statistics for correlated columns |
| 5 | Join order is exhaustively searched up to 12 tables | Fix statistics, not join order |
| 6 | Join algorithm depends on set size and indexes | Add indexes on join columns |
| 7 | Index type must match the operator | Use GIN for JSONB/arrays, B-tree for ranges |
| 8 | Functions/casts on columns disable index use | Keep indexed columns bare in `WHERE` |
| 9 | Partial indexes require matching query predicate | Design indexes around common queries |
| 10 | `work_mem` controls sort/hash memory per operation | Increase to prevent disk spills |
| 11 | `enable_*` flags are for diagnosis only | Never leave them disabled in production |
| 12 | Any `PARALLEL UNSAFE` function disables parallelism | Mark safe functions explicitly |
| 13 | CTEs are inlined in PG12+; use `MATERIALIZED` to fence | Upgrade away from pre-PG12 if possible |
| 14 | `LIMIT` shifts planner toward low-startup-cost plans | Expect different plans with/without LIMIT |
| 15 | `NOT IN` is NULL-unsafe and poorly optimized | Always use `NOT EXISTS` or anti-join pattern |

---

## Further Reading

- [PostgreSQL Official Docs — Query Planning](https://www.postgresql.org/docs/current/runtime-config-query.html)
- [PostgreSQL Official Docs — Planner Statistics](https://www.postgresql.org/docs/current/planner-stats.html)
- [PostgreSQL Official Docs — Controlling the Planner](https://www.postgresql.org/docs/current/explicit-joins.html)
- [PostgreSQL Official Docs — Extended Statistics](https://www.postgresql.org/docs/current/sql-createstatistics.html)
- [PostgreSQL Official Docs — Parallel Query](https://www.postgresql.org/docs/current/parallel-query.html)
- [Use the Index, Luke](https://use-the-index-luke.com/) — Visual guide to SQL indexing

---

*Generated with ❤️ for PostgreSQL performance engineers.*
