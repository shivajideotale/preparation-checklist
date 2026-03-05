# PostgreSQL EXPLAIN Plan — Deep Dive Reference

> A comprehensive guide to reading, interpreting, and acting on PostgreSQL query execution plans.

---

## Table of Contents

1. [What is EXPLAIN?](#1-what-is-explain)
2. [Reading the Output](#2-reading-the-output)
3. [Node Types — The Building Blocks](#3-node-types--the-building-blocks)
   - [Scan Nodes](#-scan-nodes-leaf-nodes--read-data)
   - [Join Nodes](#-join-nodes)
   - [Processing Nodes](#-processing-nodes)
4. [EXPLAIN ANALYZE — Actual vs Estimated](#4-explain-analyze--actual-vs-estimated)
5. [Full Options Reference](#5-full-options-explain-format-buffers-verbose)
6. [Understanding Cost Calculation](#6-understanding-cost-calculation)
7. [Statistics & the Planner](#7-statistics--the-planner)
8. [Parallel Query Nodes](#8-parallel-query-nodes)
9. [Common Anti-Patterns to Spot](#9-common-anti-patterns-to-spot)
10. [Practical Workflow](#10-practical-workflow)
11. [Useful Tools](#11-useful-tools)
12. [Quick Reference Cheat Sheet](#12-quick-reference-cheat-sheet)

---

## 1. What is EXPLAIN?

`EXPLAIN` shows the **query execution plan** — the internal steps PostgreSQL will take to execute your SQL. It does **not** run the query unless you add `ANALYZE`.

```sql
EXPLAIN SELECT * FROM orders WHERE customer_id = 42;
EXPLAIN ANALYZE SELECT * FROM orders WHERE customer_id = 42;
```

| Option | Runs Query? | Shows Actual Rows/Time? |
|---|---|---|
| `EXPLAIN` | ❌ No | ❌ No (estimates only) |
| `EXPLAIN ANALYZE` | ✅ Yes | ✅ Yes |
| `EXPLAIN (ANALYZE, BUFFERS)` | ✅ Yes | ✅ Yes + I/O stats |

---

## 2. Reading the Output

```
Seq Scan on orders  (cost=0.00..4321.00 rows=1 width=64)
                          ↑       ↑      ↑       ↑
                     startup  total  est.rows  row width (bytes)
```

### The cost tuple `(X..Y)`

- **Startup cost** (`X`): Cost before the first row is returned. For example, a sort must complete before returning anything, so it has a high startup cost.
- **Total cost** (`Y`): Estimated cost to return **all** rows.
- These are **not milliseconds** — they are abstract units based on `seq_page_cost`, `cpu_tuple_cost`, and related settings in `postgresql.conf`.

### Other fields

| Field | Meaning |
|---|---|
| `rows=` | Planner's *estimate* of rows returned (derived from table statistics via `ANALYZE`) |
| `width=` | Average byte width of each output row |

---

## 3. Node Types — The Building Blocks

Every plan is a **tree of nodes**. Each node feeds rows upward to its parent. Understanding what each node does is essential for diagnosis.

---

### 🔍 Scan Nodes (leaf nodes — read data)

| Node | When Used | Notes |
|---|---|---|
| `Seq Scan` | No usable index, small table, or large % of rows needed | Reads every page sequentially |
| `Index Scan` | Selective query with a matching index | Random I/O to heap for each row |
| `Index Only Scan` | All needed columns are in the index | No heap access — fastest option |
| `Bitmap Heap Scan` | Medium selectivity | Two-phase: build bitmap, then read heap |
| `Bitmap Index Scan` | Works together with Bitmap Heap Scan | Builds a bitmap of matching pages |
| `TID Scan` | `WHERE ctid = ...` | Rare — direct physical address access |

**Seq Scan vs Index Scan decision:**

PostgreSQL chooses `Seq Scan` when it estimates the query touches **~10–20% or more of pages**. Random I/O for an `Index Scan` is more expensive than sequential reads at high volumes — this is controlled by `random_page_cost` vs `seq_page_cost`.

---

### 🔗 Join Nodes

| Node | Algorithm | Best When |
|---|---|---|
| `Nested Loop` | For each outer row, scan inner set | Inner set is small or indexed |
| `Hash Join` | Build hash table on smaller set, probe with larger | Large sets, no index, equi-join only |
| `Merge Join` | Both inputs sorted on join key | Pre-sorted data, large sorted inputs |

**Details:**

- **Nested Loop**: O(N × M) in worst case but excellent with indexes on the inner side. The inner scan is executed once per outer row.
- **Hash Join**: Needs memory (`work_mem`). If the hash table is too large, it spills to disk — visible as `Batches > 1` in the output.
- **Merge Join**: Requires sorted input on both sides. If the data isn't already sorted, PostgreSQL adds a `Sort` node above each input.

---

### 🔧 Processing Nodes

| Node | What It Does |
|---|---|
| `Sort` | Sort rows — uses `work_mem`, spills to disk if exceeded |
| `Hash` | Build hash table (inner side of Hash Join) |
| `Aggregate` | `GROUP BY`, `COUNT`, `SUM`, etc. |
| `HashAggregate` | Aggregate using a hash table |
| `GroupAggregate` | Aggregate pre-sorted input (requires sorted input) |
| `Limit` | Stop after N rows |
| `Unique` | Remove duplicates from sorted input |
| `Append` | Union multiple subplans (partitioning, `UNION ALL`) |
| `Gather` / `Gather Merge` | Collect results from parallel workers |
| `Materialize` | Cache subplan results in memory or disk |
| `Subquery Scan` | Treat a subquery as a scan node |
| `WindowAgg` | Window functions (`OVER (...)`) |
| `LockRows` | `SELECT FOR UPDATE` |

---

## 4. EXPLAIN ANALYZE — Actual vs Estimated

```sql
EXPLAIN ANALYZE SELECT * FROM orders WHERE status = 'pending';
```

Example output:
```
Seq Scan on orders  (cost=0.00..8500.00 rows=120 width=96)
                    (actual time=0.042..45.231 rows=9843 loops=1)
```

- `rows=120` (estimate) vs `rows=9843` (actual) — **massive mis-estimate!**
- This is the #1 clue that statistics are stale → run `ANALYZE orders;`

### Understanding `loops=N`

The node executed `N` times. This is common on the inner side of a Nested Loop. **All actual stats are per-loop** — multiply by loops for totals.

```
Index Scan on orders_idx  (actual time=0.01..0.02 rows=5 loops=1000)
-- Total actual rows: 5 × 1000 = 5,000
-- Total actual time: 0.02ms × 1000 = 20ms
```

---

## 5. Full Options: `EXPLAIN (FORMAT, BUFFERS, VERBOSE...)`

```sql
EXPLAIN (ANALYZE, BUFFERS, VERBOSE, FORMAT TEXT)
SELECT ...;
```

| Option | What It Adds |
|---|---|
| `ANALYZE` | Actually runs the query; shows actual time and actual row counts |
| `BUFFERS` | Shows I/O: shared/local blocks hit (cache) vs read (disk) |
| `VERBOSE` | Shows output column list, schema-qualified object names |
| `FORMAT JSON/YAML` | Machine-readable output (for tools like pgMustard, explain.dalibo.com) |
| `SETTINGS` | Shows any non-default planner settings affecting the plan |
| `WAL` | Shows WAL record generation (useful for write queries) |
| `TIMING false` | Skip per-node timing (reduces overhead for very fast queries) |
| `SUMMARY` | Shows total planning + execution time at the bottom |

### BUFFERS breakdown

```
Buffers: shared hit=432 read=1891 dirtied=0 written=0
```

| Field | Meaning |
|---|---|
| `hit` | Pages found in **shared_buffers** (fast, in-memory) |
| `read` | Pages fetched from **OS cache or disk** (slower) |
| `dirtied` | Pages modified in memory |
| `written` | Pages flushed to disk during query execution |

> **High `read` / low `hit`** → insufficient `shared_buffers` or a cold cache.

---

## 6. Understanding Cost Calculation

PostgreSQL calculates costs using constants from `postgresql.conf`:

| Parameter | Default | Meaning |
|---|---|---|
| `seq_page_cost` | 1.0 | Cost to read a page sequentially |
| `random_page_cost` | 4.0 | Cost to read a page randomly |
| `cpu_tuple_cost` | 0.01 | Cost per row processed |
| `cpu_index_tuple_cost` | 0.005 | Cost per index entry processed |
| `cpu_operator_cost` | 0.0025 | Cost per operator or function call |
| `parallel_tuple_cost` | 0.1 | Cost to pass a tuple between parallel workers |

### Cost formulas

**Seq Scan:**
```
cost = (pages × seq_page_cost) + (rows × cpu_tuple_cost)
```

**Index Scan:**
```
cost = (index_pages × random_page_cost)
     + (heap_pages × random_page_cost)
     + (rows × cpu_index_tuple_cost)
```

> **SSD tip:** On SSDs, set `random_page_cost = 1.1` (close to `seq_page_cost`). This better reflects real hardware and can dramatically change which plans the planner chooses.

---

## 7. Statistics & the Planner

The planner relies on statistics collected by `ANALYZE` (also runs automatically during `AUTOVACUUM`).

```sql
SELECT * FROM pg_stats WHERE tablename = 'orders' AND attname = 'status';
```

### Key statistics fields

| Field | Meaning |
|---|---|
| `n_distinct` | Estimated distinct values. `-1` = unique, positive = count, negative fraction = fraction of total rows |
| `most_common_vals` | Top N most frequent values |
| `most_common_freqs` | Frequency of each `most_common_vals` entry |
| `histogram_bounds` | Distribution buckets used for range queries |
| `correlation` | Physical vs logical order correlation. `0` = random, `1` = perfectly sorted. Affects index scan cost estimate. |

### Increasing statistics target

The default statistics target is `100`. Increase it for columns with skewed distributions:

```sql
ALTER TABLE orders ALTER COLUMN status SET STATISTICS 500;
ANALYZE orders;
```

### Extended statistics (correlated columns)

When two columns are correlated (e.g., `city` and `zip_code`), the planner underestimates selectivity for multi-column predicates. Fix this with extended statistics:

```sql
CREATE STATISTICS orders_city_zip ON city, zip_code FROM orders;
ANALYZE orders;
```

---

## 8. Parallel Query Nodes

```
Gather Merge  (cost=... rows=...)
  ->  Parallel Index Scan on orders  (actual ... loops=4)
```

- `Gather` / `Gather Merge`: Collects rows from N parallel workers
- `loops=4` on a child node means 4 workers executed that node
- Controlled by `max_parallel_workers_per_gather` and `min_parallel_table_scan_size`

```sql
-- Adjust parallelism
SET max_parallel_workers_per_gather = 4;
SET min_parallel_table_scan_size = '8MB';
```

---

## 9. Common Anti-Patterns to Spot

### ❌ Row estimate wildly off

```
rows=1 (estimated) vs rows=500000 (actual)
```

**Cause:** Stale statistics or highly skewed data.  
**Fix:** Run `ANALYZE`, increase statistics target, check for correlated columns and create extended statistics.

---

### ❌ Sort spilling to disk

```
Sort  (cost=...) (actual ... Batches: 8)
```

`Batches > 1` means the sort overflowed to disk.  
**Fix:** Increase `work_mem` (per session or globally).

```sql
SET work_mem = '256MB';
```

---

### ❌ Hash Join with many batches

```
Hash  (actual rows=... Batches=16 Memory Usage=4096kB)
```

The hash table couldn't fit in memory and spilled to disk.  
**Fix:** Increase `work_mem`.

---

### ❌ Seq Scan on large table with heavy filtering

```
Seq Scan on orders  (rows=10000000)
  Filter: (status = 'pending')
  Rows Removed by Filter: 9990000
```

Reading 10M rows to return 10K is extremely wasteful.  
**Fix:** Create an index on `status`, or a partial index:

```sql
CREATE INDEX idx_orders_pending ON orders(created_at) WHERE status = 'pending';
```

---

### ❌ Nested Loop with large outer set and no index on inner

Forces O(N²) behaviour — catastrophic at scale.  
**Fix:** Add an index on the inner table's join column.

---

### ❌ Index Scan with low correlation

When `correlation ≈ 0` (data is physically scrambled), the random I/O cost of an `Index Scan` can exceed a `Seq Scan`. The planner may correctly prefer `Seq Scan`. Use `CLUSTER` to reorder the table physically, or test with:

```sql
SET enable_seqscan = off;  -- force index scan to compare
EXPLAIN ANALYZE SELECT ...;
SET enable_seqscan = on;   -- always restore!
```

---

### ❌ Unused index

A perfectly valid index is ignored because the planner prefers a `Seq Scan`.  
**Causes:** Low `rows` estimate, outdated stats, `random_page_cost` too high, non-selective predicate, or implicit type cast preventing index use.

```sql
-- Implicit cast breaking index use (bad):
WHERE user_id = '42'   -- user_id is integer, '42' is text → cast → no index

-- Fixed:
WHERE user_id = 42
```

---

## 10. Practical Workflow

```sql
-- Step 1: Get the full plan
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
  SELECT o.*, c.name
  FROM orders o
  JOIN customers c ON c.id = o.customer_id
  WHERE o.created_at > NOW() - INTERVAL '7 days';

-- Step 2: Look for red flags
--   • Seq Scans on large tables
--   • Rows estimate vs actual mismatch > 10x
--   • High 'read' buffer counts (cache misses)
--   • Sort or Hash Batches > 1 (disk spill)
--   • Nested Loop with large row counts on both sides

-- Step 3: Check column statistics
SELECT attname, n_distinct, correlation, most_common_vals, most_common_freqs
FROM pg_stats
WHERE tablename = 'orders';

-- Step 4: Check for stale stats / table bloat
SELECT relname, last_analyze, last_autoanalyze, n_live_tup, n_dead_tup
FROM pg_stat_user_tables
WHERE relname = 'orders';

-- Step 5: Check existing indexes
SELECT indexname, indexdef
FROM pg_indexes
WHERE tablename = 'orders';

-- Step 6: Check for slow queries (requires pg_stat_statements)
SELECT query, calls, mean_exec_time, total_exec_time
FROM pg_stat_statements
ORDER BY mean_exec_time DESC
LIMIT 20;
```

---

## 11. Useful Tools

| Tool | What It Does |
|---|---|
| [explain.dalibo.com](https://explain.dalibo.com) | Visual plan tree from JSON `EXPLAIN` output |
| [pgMustard](https://www.pgmustard.com) | AI-powered plan analysis with recommendations |
| `pg_stat_statements` | Extension to track slowest queries across all executions |
| `auto_explain` | Extension to log slow query plans automatically |

### Enable auto_explain

```sql
-- In postgresql.conf:
shared_preload_libraries = 'auto_explain'
auto_explain.log_min_duration = '100ms'
auto_explain.log_analyze = true
auto_explain.log_buffers = true

-- Or per session (no restart needed):
LOAD 'auto_explain';
SET auto_explain.log_min_duration = '100ms';
SET auto_explain.log_analyze = true;
```

### Enable pg_stat_statements

```sql
-- In postgresql.conf:
shared_preload_libraries = 'pg_stat_statements'

-- Then in your database:
CREATE EXTENSION pg_stat_statements;
```

---

## 12. Quick Reference Cheat Sheet

```
────────────────────────────────────────────────────────────
 PLAN OUTPUT ANATOMY
────────────────────────────────────────────────────────────
 (cost=startup..total  rows=estimate  width=bytes)
 (actual time=start..total  rows=actual  loops=N)
                                               ↑
                               multiply all stats by this!

 Buffers: shared hit=N read=N   ← hit=cache, read=disk

────────────────────────────────────────────────────────────
 SCAN NODES
────────────────────────────────────────────────────────────
 Seq Scan        → no index / full table / >~15% rows
 Index Scan      → selective, random I/O to heap
 Index Only      → all cols in index (fastest, no heap I/O)
 Bitmap Heap     → medium selectivity, batched heap I/O
 Bitmap Index    → builds page bitmap (feeds Bitmap Heap)

────────────────────────────────────────────────────────────
 JOIN NODES
────────────────────────────────────────────────────────────
 Nested Loop     → small inner set or indexed inner
 Hash Join       → large sets, equi-join, needs work_mem
 Merge Join      → pre-sorted inputs, large sorted sets

────────────────────────────────────────────────────────────
 RED FLAGS
────────────────────────────────────────────────────────────
 Sort Batches > 1          → disk spill   → ↑ work_mem
 Hash Batches > 1          → disk spill   → ↑ work_mem
 estimate >> actual rows   → stale stats  → ANALYZE
 Seq Scan + heavy Filter   → missing index
 Nested Loop + large sets  → missing index on inner join col
 High Buffers read         → cold/small cache → ↑ shared_buffers

────────────────────────────────────────────────────────────
 KEY SETTINGS TO TUNE
────────────────────────────────────────────────────────────
 random_page_cost = 1.1        (for SSDs)
 work_mem = 64MB–256MB         (per operation, per connection)
 shared_buffers = 25% of RAM
 effective_cache_size = 75% of RAM
────────────────────────────────────────────────────────────
```

---

## Further Reading

- [PostgreSQL Official Docs — EXPLAIN](https://www.postgresql.org/docs/current/sql-explain.html)
- [PostgreSQL Official Docs — Using EXPLAIN](https://www.postgresql.org/docs/current/using-explain.html)
- [PostgreSQL Official Docs — Planner Statistics](https://www.postgresql.org/docs/current/planner-stats.html)
- [PostgreSQL Official Docs — Planner Cost Constants](https://www.postgresql.org/docs/current/runtime-config-query.html#RUNTIME-CONFIG-QUERY-CONSTANTS)
- [The Art of PostgreSQL](https://theartofpostgresql.com/)

---

*Generated with ❤️ for PostgreSQL performance engineers.*
