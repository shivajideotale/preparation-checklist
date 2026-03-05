# 🐘 PostgreSQL Query Tuning — Senior Interview Guide
> **Target Level:** 20+ Years Backend Engineering Experience  
> **Focus:** Deep internals, production war stories, planner mechanics, tuning strategies  
> **Format:** Question → Concept → Code → Gotchas → Follow-up probes

---

## 📋 Table of Contents

1. [EXPLAIN ANALYZE Deep Dive](#q1-explain-analyze-deep-dive)
2. [Wrong Join Order Diagnosis](#q2-wrong-join-order-diagnosis)
3. [work_mem Lifecycle & Dangers](#q3-work_mem-lifecycle--dangers)
4. [LIKE Search & Trigram Indexes](#q4-like-search--trigram-indexes)
5. [Visibility Map & Free Space Map](#q5-visibility-map--free-space-map)
6. [ORDER BY + LIMIT Top-N Optimization](#q6-order-by--limit-top-n-optimization)
7. [Nested Loop vs Hash Join vs Merge Join](#q7-nested-loop-vs-hash-join-vs-merge-join)
8. [Predicate Pushdown & CTEs](#q8-predicate-pushdown--ctes)
9. [Dev vs Production Performance Gap](#q9-dev-vs-production-performance-gap)
10. [Hidden Index Costs](#q10-hidden-costs-of-indexes)
11. [Partitioning & Partition Pruning](#q11-partitioning--partition-pruning)
12. [Parallel Query Internals](#q12-parallel-query-internals)
13. [Vacuuming & Autovacuum Tuning](#q13-vacuuming--autovacuum-tuning)
14. [Connection Pooling Impact on Query Perf](#q14-connection-pooling-impact-on-query-perf)
15. [Serializable Isolation & SSI Performance](#q15-serializable-isolation--ssi-performance)

---

## Q1. EXPLAIN ANALYZE Deep Dive

### Question
> Walk me through every layer of `EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)` output. What signals do most engineers miss, and what is the single most dangerous misreading of the output?

### Core Concept
Most engineers read the top-level cost and stop. Veteran-level reading means consuming the **entire node tree bottom-up**, treating each node as a pipeline stage and identifying where rows balloon or cost explodes.

### Sample Query & Output

```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT u.name, COUNT(o.id)
FROM users u
JOIN orders o ON o.user_id = u.id
WHERE u.created_at > '2023-01-01'
GROUP BY u.name;
```

```
HashAggregate  (cost=45231.80..45731.80 rows=50000 width=40)
               (actual time=812.301..891.442 rows=48210 loops=1)
  Buffers: shared hit=3200 read=18900 written=45
  ->  Hash Join  (cost=12000..40000 rows=250000 width=32)
                 (actual time=120.4..650.1 rows=980000 loops=1)
        Buffers: shared hit=3200 read=18900
        Hash Cond: (o.user_id = u.id)
        ->  Seq Scan on orders  (cost=0..28000 rows=1500000 width=16)
                                (actual time=0.05..280.3 rows=1500000 loops=1)
              Buffers: shared hit=800 read=15200
        ->  Hash  (cost=9800..9800 rows=160000 width=24)
                  (actual time=118.2..118.2 rows=160000 loops=1)
              Buckets: 65536  Batches: 4  Memory Usage: 3072kB
              Buffers: shared hit=2400 read=3700
              ->  Seq Scan on users ...
                    Filter: (created_at > '2023-01-01')
                    Rows Removed by Filter: 40000
```

### Signal Interpretation Table

| Signal | Example | What It Means |
|--------|---------|---------------|
| `rows` estimate vs actual | Estimate: 250k → Actual: 980k | Stale stats → wrong join strategy chosen |
| `Buffers: read=18900` | High disk reads | Data not in `shared_buffers` or OS cache |
| `written=45` | Buffers written during query | Memory pressure, `work_mem` too low |
| `Batches: 4` | Hash join spilled to disk | Increase `work_mem` for this workload |
| `Rows Removed by Filter` | 40k discarded post-scan | A partial index could eliminate this |
| `loops=N` | Node ran N times | Nested loop with N outer rows = N inner scans |
| `actual time` total | `time=0.05..280.3` | Divide by loops to get per-iteration cost |

### The Golden Formula

```
Actual rows × loops = Total rows processed at that node
Actual time      = TOTAL time across ALL loops (not per loop)
Per-loop time    = actual time / loops
```

### The Most Dangerous Misread

```sql
-- This looks cheap per loop:
Nested Loop
  Inner: Index Scan  (actual time=0.01..0.5  loops=50000)
--                                            ^^^^^^^^^^^^^^
-- Real cost: 0.5ms × 50,000 = 25,000ms = 25 SECONDS
-- Engineers see "0.5ms" and call it fine
```

### Buffers Glossary

```
shared hit    = served from PostgreSQL shared_buffers (fastest)
shared read   = fetched from OS page cache or disk (slower)
shared written= dirty pages flushed during query (memory pressure)
local hit/read= temp table buffers
temp read/written = sort/hash spills to disk (very slow)
```

### Follow-up Probes
- *"How do you capture EXPLAIN ANALYZE output from production without killing performance?"*
- *"You see `Batches: 16` on a Hash Join. What's your remediation path?"*
- *"The planner shows cost=999..999 but actual time=1ms. What happened?"*

---

## Q2. Wrong Join Order Diagnosis

### Question
> The query planner is consistently choosing the wrong join order on a 6-table join. How do you diagnose and fix it without reaching for `enable_nestloop = off`?

### Core Concept
Wrong join order is almost always a **statistics accuracy problem** — the planner makes optimal decisions given what it knows, but row estimates diverge from reality due to column correlation, stale stats, or skewed data.

### Step 1 — Identify Misestimation Nodes

```sql
-- Look for 10x+ divergence between estimate and actual rows
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM a
JOIN b ON b.a_id = a.id
JOIN c ON c.b_id = b.id
JOIN d ON d.user_id = a.user_id;

-- Red flag:
-- Hash Join (cost=... rows=150) (actual rows=1,500,000)
--                    ^^^                  ^^^^^^^^^
--                    1k estimate          1M actual = 1000x off
```

### Step 2 — Inspect Statistics Depth

```sql
-- Check per-column statistics
SELECT
  attname,
  n_distinct,
  correlation,
  null_frac,
  avg_width
FROM pg_stats
WHERE tablename = 'orders' AND attname IN ('status', 'user_id', 'created_at');

-- n_distinct = -0.15 means ~15% of rows are distinct (estimated)
-- correlation near 1.0 means data is physically sorted by this column
-- correlation near 0.0 means random scatter → index less effective
```

```sql
-- Increase statistics target for problematic columns
ALTER TABLE orders ALTER COLUMN status SET STATISTICS 500;
ALTER TABLE orders ALTER COLUMN user_id SET STATISTICS 500;
ANALYZE orders;
-- Default = 100 samples; Range: 1–10000
```

### Step 3 — Extended Statistics for Correlated Columns

The planner assumes **column independence**. If `country` and `city` are correlated, combined selectivity estimates become wildly wrong.

```sql
-- Create multivariate statistics
CREATE STATISTICS orders_location_stats (dependencies, ndistinct, mcv)
ON country, city, region FROM orders;

ANALYZE orders;

-- Verify it was used
EXPLAIN (ANALYZE)
SELECT * FROM orders WHERE country = 'US' AND city = 'New York';
-- Should now show more accurate row estimates
```

```sql
-- Inspect what was learned
SELECT
  stxname,
  stxkind,        -- 'd'=dependencies, 'n'=ndistinct, 'm'=mcv
  stxddependencies
FROM pg_statistic_ext
JOIN pg_statistic_ext_data ON oid = stxoid
WHERE stxname = 'orders_location_stats';
```

### Step 4 — Join Collapse Limit

```sql
-- Beyond join_collapse_limit, planner uses GEQO (genetic algorithm)
-- GEQO is fast but non-deterministic and often suboptimal

SHOW join_collapse_limit;   -- default: 8
SHOW geqo_threshold;        -- default: 12

-- Tune for complex queries in session:
SET join_collapse_limit = 14;
SET geqo_threshold = 20;
SET geqo_effort = 10;       -- 1-10; higher = more iterations
```

### Step 5 — Force Join Order (Non-destructive)

```sql
-- Pre-v12: CTEs are always materialized = hard optimization fence
-- Use this deliberately to force join order

WITH step1 AS MATERIALIZED (
  -- This subquery is fully executed first, result materialized
  SELECT u.id, u.email
  FROM users u
  WHERE u.country = 'US' AND u.plan = 'enterprise'
),
step2 AS MATERIALIZED (
  SELECT o.*, s1.email
  FROM orders o
  JOIN step1 s1 ON s1.id = o.user_id
  WHERE o.status = 'pending'
)
SELECT * FROM step2 JOIN products p ON p.id = step2.product_id;
```

### Step 6 — pg_hint_plan (Last Resort)

```sql
-- Install extension, then use comment hints:
/*+ Leading(orders users products) HashJoin(orders users) SeqScan(products) */
SELECT u.name, o.total, p.name
FROM orders o
JOIN users u ON u.id = o.user_id
JOIN products p ON p.id = o.product_id
WHERE o.created_at > '2024-01-01';
```

### Follow-up Probes
- *"Your join order fix works today but breaks after a data distribution shift next month. How do you make it robust?"*
- *"When would you deliberately keep bad stats to maintain a known-good plan?"*

---

## Q3. work_mem Lifecycle & Dangers

### Question
> Explain the full lifecycle of `work_mem` in a complex query. Why can setting it too high bring down a production database?

### Core Concept
`work_mem` is allocated **per sort/hash operation, per node, per query, per connection** — not per query or per connection. This multiplicative effect is lethal at scale.

### The OOM Math

```
Active connections:         200
Sort/hash nodes per query:    5   (ORDER BY + 2 Hash Joins + GROUP BY + DISTINCT)
work_mem setting:           256MB
─────────────────────────────────────────────────────
Peak RAM consumption: 200 × 5 × 256MB = 256,000MB = 250GB
```

A server with 128GB RAM will **OOM-kill the Postgres process**.

### Where work_mem Is Consumed

```sql
-- Each of these nodes can consume up to work_mem independently:
EXPLAIN SELECT DISTINCT u.country,
       SUM(o.total),
       ROW_NUMBER() OVER (PARTITION BY u.country ORDER BY o.created_at)
FROM users u
JOIN orders o ON o.user_id = u.id       -- Hash Join → work_mem #1
JOIN products p ON p.id = o.product_id  -- Hash Join → work_mem #2
GROUP BY u.country                       -- HashAggregate → work_mem #3
ORDER BY SUM(o.total) DESC;             -- Sort → work_mem #4
-- WindowAgg may add work_mem #5
```

### Spill Detection

```sql
-- Sort spilling to disk — look for these in EXPLAIN output:
Sort  (actual time=8420..8820)
  Sort Key: last_name, first_name
  Sort Method: external merge  Disk: 184520kB   -- ← SPILLED TO DISK
  -- vs in-memory:
  Sort Method: quicksort  Memory: 2048kB        -- ← IN MEMORY ✓

-- Hash Join spill:
Hash  (actual time=...)
  Buckets: 65536  Batches: 8  Memory Usage: 8192kB
--                ^^^^^^^^^ > 1 batch means spilled to disk
```

### Tuning Strategy

```sql
-- Global (conservative baseline)
work_mem = '32MB';   -- Safe for 200 connections × 5 nodes

-- Per-session (controlled analytical workloads)
SET work_mem = '1GB';

-- Per-transaction (scoped, auto-reset on COMMIT)
BEGIN;
SET LOCAL work_mem = '4GB';
SELECT /* massive analytical query */ ...;
COMMIT;

-- Per-role (reporting users isolated from OLTP)
ALTER ROLE analyst_user SET work_mem = '512MB';
ALTER ROLE app_user SET work_mem = '16MB';
```

### hash_mem_multiplier (PostgreSQL 13+)

```sql
-- Hash operations get a separate multiplier on top of work_mem
SHOW hash_mem_multiplier;  -- default: 2.0

-- Effective hash memory = work_mem × hash_mem_multiplier
-- work_mem=64MB → Hash builds can use 128MB before spilling
-- Sorts still limited to work_mem exactly

-- Tune independently for hash-heavy workloads:
SET hash_mem_multiplier = 4.0;
```

### Memory Pressure Detection

```sql
-- Find queries currently spilling to disk
SELECT
  pid,
  query,
  temp_blks_written,
  temp_blks_read,
  now() - query_start AS duration
FROM pg_stat_activity
JOIN pg_stat_statements USING (queryid)   -- requires pg_stat_statements
WHERE temp_blks_written > 0
ORDER BY temp_blks_written DESC;

-- Or via pg_stat_statements aggregated view:
SELECT
  left(query, 80) AS query_preview,
  calls,
  total_exec_time / calls AS avg_ms,
  temp_blks_written / calls AS avg_temp_writes
FROM pg_stat_statements
WHERE temp_blks_written > 0
ORDER BY temp_blks_written DESC
LIMIT 20;
```

### Follow-up Probes
- *"You have a reporting workload at 2am and an OLTP workload at 2pm sharing one Postgres instance. How do you manage work_mem across both?"*
- *"Your monitoring shows temp file creation spiking. Walk me through your response playbook."*

---

## Q4. LIKE Search & Trigram Indexes

### Question
> You have a `LIKE '%search_term%'` query performing a full Seq Scan on 100M rows. The product team wants sub-100ms response. Walk through every solution, trade-offs included.

### Why B-Tree Fails for Leading Wildcards

```sql
-- B-Tree index stores data in sorted order
-- "LIKE '%term%'" means: value could start with ANYTHING
-- Planner cannot use sorted structure to skip pages → full scan

-- B-Tree CAN handle trailing wildcard (prefix search):
SELECT * FROM products WHERE name LIKE 'Apple%';  -- uses B-Tree ✓
SELECT * FROM products WHERE name LIKE '%Apple%'; -- full scan ✗
SELECT * FROM products WHERE name LIKE '%Apple';  -- full scan ✗
```

### Solution 1 — pg_trgm GIN Index (Best General Solution)

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- GIN index with trigram operator class
CREATE INDEX CONCURRENTLY idx_products_name_trgm
ON products USING GIN (name gin_trgm_ops);

-- Now ALL of these use the index:
SELECT * FROM products WHERE name LIKE '%bluetooth speaker%';   -- ✓
SELECT * FROM products WHERE name ILIKE '%Bluetooth%';          -- ✓ case-insensitive
SELECT * FROM products WHERE name ~ '^blue.*speak';             -- ✓ regex
SELECT * FROM products WHERE name % 'bluetooth';                -- ✓ similarity
```

**How trigrams work internally:**
```
"hello" → " he" + "hel" + "ell" + "llo" + "lo "
Search "%ell%" decomposes to trigram "ell" → GIN lookup → small candidate set → re-check
```

```sql
-- Verify index usage and similarity threshold:
SHOW pg_trgm.similarity_threshold;    -- default: 0.3
SET pg_trgm.similarity_threshold = 0.4;

-- Check trigrams for a string:
SELECT show_trgm('bluetooth speaker');
```

### Solution 2 — Full Text Search (Natural Language)

```sql
-- Best for: document search, relevance ranking, stemming, stopwords

-- Option A: Generated tsvector column (preferred)
ALTER TABLE articles
  ADD COLUMN search_vector tsvector
  GENERATED ALWAYS AS (
    to_tsvector('english', coalesce(title,'') || ' ' || coalesce(body,''))
  ) STORED;

CREATE INDEX idx_articles_fts ON articles USING GIN(search_vector);

-- Ranked search query:
SELECT
  title,
  ts_rank(search_vector, query) AS relevance,
  ts_headline('english', body, query, 'MaxFragments=2') AS snippet
FROM articles,
     to_tsquery('english', 'postgresql & query & tuning') AS query
WHERE search_vector @@ query
ORDER BY relevance DESC
LIMIT 20;

-- Option B: Multi-column FTS across joined tables (use materialized view)
CREATE MATERIALIZED VIEW product_search_index AS
SELECT
  p.id,
  to_tsvector('english',
    p.name || ' ' ||
    p.description || ' ' ||
    c.name || ' ' ||          -- category name
    string_agg(t.name, ' ')   -- tags
  ) AS search_vector
FROM products p
JOIN categories c ON c.id = p.category_id
LEFT JOIN product_tags pt ON pt.product_id = p.id
LEFT JOIN tags t ON t.id = pt.tag_id
GROUP BY p.id, p.name, p.description, c.name;

CREATE INDEX ON product_search_index USING GIN(search_vector);
```

### Solution 3 — Expression Index + Generated Column

```sql
-- For case-insensitive prefix search:
ALTER TABLE users
  ADD COLUMN email_lower TEXT
  GENERATED ALWAYS AS (lower(email)) STORED;

CREATE INDEX idx_users_email_lower ON users(email_lower text_pattern_ops);

-- Uses index:
SELECT * FROM users WHERE email_lower LIKE 'john.doe%';

-- For prefix search on original column:
CREATE INDEX idx_users_email_pattern
ON users(email text_pattern_ops);

SELECT * FROM users WHERE email LIKE 'john%';  -- uses index ✓
```

### Performance Comparison at 100M Rows

| Approach | Leading `%` | Trailing `%` | Middle `%` | Regex | Relevance | Index Size |
|----------|-------------|--------------|------------|-------|-----------|------------|
| B-Tree | ❌ | ✅ | ❌ | ❌ | ❌ | Small |
| B-Tree + text_pattern_ops | ❌ | ✅ | ❌ | ❌ | ❌ | Small |
| GIN + pg_trgm | ✅ | ✅ | ✅ | ✅ | ❌ | Large (3-5×) |
| GIN + tsvector | ✅ | ✅ | ✅ | Partial | ✅ | Medium |
| GiST + pg_trgm | ✅ | ✅ | ✅ | ✅ | ❌ | Medium |

```sql
-- GiST vs GIN for trigrams:
CREATE INDEX idx_trgm_gist ON products USING GIST (name gist_trgm_ops);
-- GiST: faster writes, smaller size, slightly slower reads
-- GIN: faster reads, larger size, slower writes (better for read-heavy)
```

### Follow-up Probes
- *"pg_trgm is slow for 2-character search terms. Why, and how do you handle it?"*
- *"You need to search across 5 joined tables simultaneously. What's your architecture?"*

---

## Q5. Visibility Map & Free Space Map

### Question
> What are the visibility map and free space map? How do they directly affect query execution performance — not just maintenance?

### Visibility Map (VM)

Each heap relation has a corresponding `_vm` fork — **2 bits per heap page**:
- **Bit 0 (all-visible):** Every tuple on this page is visible to all current and future transactions
- **Bit 1 (all-frozen):** All tuples are frozen (xid=2) — never need transaction ID comparison

```sql
-- Check VM coverage for a table:
SELECT
  heap_blks_total,
  heap_blks_scanned,
  index_vacuum_count,
  all_visible_above_root_blkno
FROM pg_stat_user_tables
WHERE relname = 'orders';

-- Per-page visibility inspection:
SELECT blkno, all_visible, all_frozen
FROM pg_visibility('orders')
WHERE NOT all_visible
LIMIT 20;  -- Find pages that need vacuuming
```

### Direct Performance Impact — Index-Only Scans

```sql
-- Index-Only Scan requires VM confirmation that heap doesn't need checking
CREATE INDEX idx_users_created_email ON users(created_at, email);

EXPLAIN (ANALYZE, BUFFERS)
SELECT email FROM users WHERE created_at > '2024-01-01';

-- Best case (high VM coverage):
Index Only Scan on users
  Heap Fetches: 0        ← Zero heap I/O — VM says all pages are all-visible

-- Degraded case (low VM coverage after bulk writes):
Index Only Scan on users
  Heap Fetches: 180000   ← Must visit heap for 180k pages to verify visibility
  -- Performance equivalent to Index Scan, not Index-Only Scan
```

```sql
-- Force VM refresh after bulk loads:
VACUUM ANALYZE users;

-- Aggressive freeze to maximize all-frozen pages:
VACUUM FREEZE ANALYZE users;

-- Monitor Index-Only Scan effectiveness:
SELECT
  relname,
  idx_scan,
  idx_tup_read,
  idx_tup_fetch,
  -- High ratio = many index entries pointing to visible heap pages
  round(idx_tup_fetch::numeric / NULLIF(idx_tup_read, 0) * 100, 2) AS heap_fetch_pct
FROM pg_stat_user_indexes
WHERE relname = 'users';
```

### Free Space Map (FSM)

```sql
-- FSM tracks available free space per heap page
-- Used by: INSERT, UPDATE (new tuple version placement)
-- Updated by: VACUUM (full rebuild) + incremental during DML

-- Without accurate FSM:
-- INSERT cannot find pages with space → table extends → bloat accumulates
-- Bloat → more pages to scan → slower Seq Scans

-- Measure table bloat:
SELECT
  schemaname,
  tablename,
  pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS total,
  n_dead_tup,
  n_live_tup,
  round(n_dead_tup::numeric / NULLIF(n_live_tup + n_dead_tup, 0) * 100, 2) AS dead_pct,
  last_autovacuum,
  last_autoanalyze
FROM pg_stat_user_tables
WHERE schemaname = 'public'
ORDER BY n_dead_tup DESC
LIMIT 20;

-- Detailed bloat estimation (pgstattuple extension):
CREATE EXTENSION pgstattuple;
SELECT * FROM pgstattuple('orders');
-- Returns: tuple_count, tuple_len, tuple_percent, dead_tuple_count, dead_tuple_percent, free_space, free_percent
```

### HOT Updates and the VM/FSM Interplay

```sql
-- HOT (Heap Only Tuple) update avoids index update + keeps data on same page
-- Requires: 1) updated column NOT in any index  2) free space on same page (FSM)

-- Set fillfactor to reserve space for HOT updates:
ALTER TABLE users SET (fillfactor = 70);  -- 30% free space per page
VACUUM FULL users;  -- Reorganize to apply new fillfactor

-- Measure HOT effectiveness:
SELECT
  relname,
  n_tup_upd,
  n_tup_hot_upd,
  round(n_tup_hot_upd::numeric / NULLIF(n_tup_upd, 0) * 100, 2) AS hot_pct
FROM pg_stat_user_tables
WHERE relname = 'users';
-- Target: hot_pct > 80% for high-update tables
-- Low hot_pct = consider removing indexes on frequently updated columns
```

### Follow-up Probes
- *"After a huge bulk delete (50% of table rows), your queries get slower. The dead_tuple count is low. What happened and why?"*
- *"How does transaction ID wraparound relate to the visibility map?"*

---

## Q6. ORDER BY + LIMIT Top-N Optimization

### Question
> You have an `ORDER BY + LIMIT` query that should be using a Top-N heap sort but isn't. The planner sorts all 200M rows before applying LIMIT. How do you diagnose and fix it?

### How Top-N HeapSort Works

```sql
-- Planner recognizes small LIMIT and maintains bounded heap:
EXPLAIN SELECT * FROM events ORDER BY created_at DESC LIMIT 10;

Sort (actual time=1240..1240 rows=10)
  Sort Key: created_at DESC
  Sort Method: top-N heapsort  Memory: 27kB  -- only holds 10 rows in memory
  -- vs full sort:
  Sort Method: quicksort  Memory: 819MB      -- holds ALL rows
```

### When Top-N Breaks

```sql
-- 1. CTE optimization fence (pre-v12 always; post-v12 if MATERIALIZED):
WITH ranked AS MATERIALIZED (
  SELECT *, ROW_NUMBER() OVER (ORDER BY created_at DESC) AS rn
  FROM events
)
SELECT * FROM ranked WHERE rn <= 10;
-- Full sort of ALL events, THEN filter for rn <= 10

-- Fix: rewrite without CTE
SELECT *, ROW_NUMBER() OVER (ORDER BY created_at DESC) AS rn
FROM events
ORDER BY created_at DESC
LIMIT 10;  -- Now Top-N can apply

-- 2. OFFSET hiding the LIMIT from planner's view:
SELECT * FROM (
  SELECT *, ROW_NUMBER() OVER (ORDER BY created_at DESC) AS rn FROM events
) sub
WHERE rn BETWEEN 1 AND 10;  -- planner doesn't see it as LIMIT
```

### Index-Based Zero-Sort Optimization

```sql
-- Best case: Index already sorted in query direction → no sort at all
CREATE INDEX idx_events_user_time
ON events(user_id, created_at DESC NULLS LAST);

-- This uses Index Scan with ZERO sort overhead:
EXPLAIN SELECT * FROM events
WHERE user_id = 123
ORDER BY created_at DESC
LIMIT 10;

-- Output:
Limit (rows=10)
  ->  Index Scan Backward on idx_events_user_time
        Index Cond: (user_id = 123)
        -- No Sort node at all!
```

### Index Sort Direction Alignment

```sql
-- Index: (a ASC, b ASC)
-- Allowed scans:
--   ORDER BY a ASC, b ASC       → forward scan ✓
--   ORDER BY a DESC, b DESC     → backward scan ✓ (symmetric)
--   ORDER BY a ASC, b DESC      → ✗ mixed — cannot use index for sort

-- Fix mixed direction:
CREATE INDEX idx_mixed ON events(category ASC, created_at DESC);

-- Now this uses the index:
SELECT * FROM events
ORDER BY category ASC, created_at DESC
LIMIT 10;
```

### Pagination Anti-Patterns

```sql
-- SLOW: OFFSET forces scan of all preceding rows
-- OFFSET 900000 scans 900,010 rows and discards 900,000
SELECT * FROM events ORDER BY id LIMIT 10 OFFSET 900000;
-- Gets slower with every page

-- FAST: Keyset (cursor) pagination
-- Page 1:
SELECT * FROM events ORDER BY id LIMIT 10;
-- Returns last id = 1000

-- Page 2 (pass cursor from previous page):
SELECT * FROM events
WHERE id > 1000     -- cursor
ORDER BY id
LIMIT 10;
-- Constant cost regardless of page number

-- Composite cursor (multi-column sort):
SELECT * FROM events
WHERE (created_at, id) < ('2024-01-15 10:00:00', 5000)
ORDER BY created_at DESC, id DESC
LIMIT 10;
```

### Follow-up Probes
- *"Your API supports jumping to arbitrary page numbers (user types 'page 9500'). Keyset pagination doesn't work here. What do you do?"*
- *"LIMIT 1 query is slow. The index exists. What are the possible causes?"*

---

## Q7. Nested Loop vs Hash Join vs Merge Join

### Question
> Explain the algorithm, cost model, and failure modes of each join strategy. When is the planner's choice catastrophically wrong, and what are the corrective actions short of disabling join types globally?

### Nested Loop Join

```
Algorithm:
FOR each row in outer_table:       -- O(outer_rows)
    FOR each row in inner WHERE condition:  -- O(inner_lookup)
        emit matching row

Total cost ≈ outer_rows × inner_lookup_cost
```

```sql
-- Ideal conditions for Nested Loop:
-- 1. Outer side is SMALL after filtering
-- 2. Inner side has an INDEX on join key
-- 3. High selectivity on outer side

-- EXPLAIN shows:
Nested Loop  (cost=0.56..1240.80 rows=15)
  ->  Index Scan on users WHERE plan='enterprise'  (rows=15)
  ->  Index Scan on orders WHERE user_id=$1        (rows=4)
--                                          ^^
--                              Parameterized inner scan — good ✓

-- CATASTROPHIC FAILURE:
-- Planner estimates outer=15, actual outer=15,000
-- 15,000 index scans on inner table = 15,000 random I/O bursts
-- What looked like "fast" becomes minutes of work

-- Fix session-level:
SET enable_nestloop = off;  -- Forces planner away from NL for this session
-- But: this can break other queries, use sparingly
```

### Hash Join

```
Algorithm:
Phase 1 (Build):  Load smaller table into in-memory hash table
Phase 2 (Probe):  For each row in larger table, probe hash

Cost ≈ build_side_size + probe_side_size
Memory: work_mem controls hash table size
```

```sql
-- EXPLAIN output analysis:
Hash Join  (cost=15000..85000 rows=500000)
  Hash Cond: (orders.user_id = users.id)
  ->  Seq Scan on orders             -- probe side (larger)
  ->  Hash                           -- build phase
        Buckets: 65536  Batches: 1  Memory Usage: 4096kB   -- fits in work_mem ✓
        Buckets: 65536  Batches: 8  Memory Usage: 8192kB   -- spilling to disk ✗

-- When Hash Join fails:
-- 1. Build side estimated as small, actually huge → massive disk spill
-- 2. work_mem too low for the data volume
-- 3. High-skew data → hash bucket collisions → probe degrades to O(n²)

-- Fix disk spill:
SET work_mem = '256MB';

-- Fix wrong build side:
-- Planner always builds on smaller side — if estimates are wrong, manually rewrite
-- to put smaller result in subquery (planner hint for build side)
```

### Merge Join

```
Algorithm:
1. Sort both inputs on join key (or use pre-sorted index scan)
2. Merge-scan both sorted streams: single pass through each

Cost ≈ sort(N) + sort(M) + N + M
     = O(N log N + M log M + N + M)
     = O((N+M) log(N+M)) if both need sorting
     = O(N+M) if both already sorted via indexes!
```

```sql
-- Merge Join shines when both sides already sorted:
Merge Join  (cost=0.00..120000 rows=500000)
  Merge Cond: (orders.created_at = events.ts)
  ->  Index Scan on orders using idx_orders_created  -- already sorted ✓
  ->  Index Scan on events using idx_events_ts       -- already sorted ✓
-- ZERO sort cost because indexes provide pre-sorted streams

-- Merge Join is the ONLY strategy for inequality joins in some cases:
-- a.value BETWEEN b.low AND b.high → only Merge Join handles this
-- NL is O(n²), Hash can't do inequalities

-- Failure mode:
-- Merge Join on unsorted data with no indexes = two expensive sorts
-- Planner should catch this, but stale stats can fool it
```

### Decision Matrix

| Scenario | Winner | Why |
|----------|--------|-----|
| Small outer + indexed inner (OLTP point lookups) | Nested Loop | Fast index seeks dominate |
| Large × large, equality, no pre-sort | Hash Join | Linear build + probe |
| Large × large, both pre-sorted via indexes | Merge Join | Zero sort cost |
| Range/inequality joins | Merge Join or NL | Hash can't do inequalities |
| Hash batches > 8 (excessive spill) | Switch to Merge | Disk I/O too high |
| Outer estimate 15, actual 150k | Fix stats, then re-evaluate | NL will be catastrophic |

```sql
-- Diagnose join choice issues:
-- 1. Find queries with bad join choices via pg_stat_statements:
SELECT
  left(query, 100),
  calls,
  total_exec_time / calls AS avg_ms,
  rows / calls AS avg_rows
FROM pg_stat_statements
WHERE total_exec_time / calls > 1000   -- over 1 second avg
ORDER BY total_exec_time DESC
LIMIT 20;
```

### Follow-up Probes
- *"You have a Hash Join with Batches: 32. Increasing work_mem isn't an option. What else can you do?"*
- *"When would you prefer a Merge Join over a Hash Join even when both sides fit in work_mem?"*

---

## Q8. Predicate Pushdown & CTEs

### Question
> Explain predicate pushdown and how it interacts with views, subqueries, CTEs, and security barrier views. Where does it silently fail and cost you orders of magnitude in performance?

### Predicate Pushdown — The Concept

Predicate pushdown moves `WHERE` filters as early (deep) in the execution tree as possible, reducing rows flowing through upper nodes.

```sql
-- Without pushdown: filter at top after full scan
-- With pushdown: filter at scan level, fewer rows to process
```

### With Views — Fully Transparent

```sql
CREATE VIEW recent_orders AS
SELECT * FROM orders WHERE created_at > NOW() - INTERVAL '30 days';

-- Additional predicate pushed INTO the view:
SELECT * FROM recent_orders WHERE user_id = 123;

-- Planner rewrites to:
SELECT * FROM orders
WHERE created_at > NOW() - INTERVAL '30 days'
  AND user_id = 123;
-- Both filters applied at base table scan level ✓
-- Index on (user_id, created_at) is usable
```

### With Regular Subqueries — Usually Pushed

```sql
-- Subquery is "flattened" by planner:
SELECT * FROM (
  SELECT id, user_id, total, status FROM orders
) sub
WHERE user_id = 123 AND status = 'pending';

-- Planner executes as:
SELECT id, user_id, total, status
FROM orders
WHERE user_id = 123 AND status = 'pending';
-- Filter pushed to base scan ✓
```

### The CTE Watershed — PostgreSQL v12

```sql
-- PRE-v12: All CTEs are optimization fences — ALWAYS materialized

WITH filtered AS (
  SELECT * FROM orders WHERE status = 'pending'  -- FULL TABLE SCAN
  -- Returns: 5,000,000 rows
)
SELECT * FROM filtered WHERE user_id = 123;
-- Filter for user_id=123 applied AFTER materializing 5M rows
-- Cannot push user_id into CTE

-- POST-v12: CTEs inlined by default if:
-- 1. Non-recursive
-- 2. Referenced exactly once
-- 3. No side effects (no VOLATILE functions)

-- Same CTE in v12+ is equivalent to subquery → predicate IS pushed ✓
```

```sql
-- Force materialization when you WANT the fence (prevent duplicate execution):
WITH expensive_stats AS MATERIALIZED (  -- explicit keyword
  SELECT
    user_id,
    SUM(total) AS total_spend,
    COUNT(*) AS order_count,
    AVG(total) AS avg_order
  FROM orders
  GROUP BY user_id
  -- This aggregation runs once, result reused
)
SELECT u.name, s.total_spend
FROM users u
JOIN expensive_stats s ON s.user_id = u.id
WHERE s.total_spend > 10000

UNION ALL

SELECT u.name, s.avg_order
FROM users u
JOIN expensive_stats s ON s.user_id = u.id
WHERE s.order_count > 100;
-- expensive_stats computed ONCE, used TWICE ✓
```

### Security Barrier Views — Intentional Pushdown Block

```sql
-- PROBLEM: Pushdown can leak data through side-effecting functions
CREATE VIEW user_balances AS
SELECT * FROM accounts WHERE owner_id = current_user_id();

-- Malicious WHERE clause with side effect:
SELECT * FROM user_balances WHERE log_if_seen(balance) > 0;
-- Without security_barrier: log_if_seen runs on ALL rows, then owner_id filter
-- With security_barrier: owner_id filter runs first, log_if_seen only on authorized rows

CREATE VIEW user_balances WITH (security_barrier) AS
SELECT * FROM accounts WHERE owner_id = current_user_id();

-- Now NO external predicates are pushed through this view
-- Performance cost: unavoidable for security-critical views
-- Mitigation: LEAKPROOF functions can still be pushed through security_barrier
CREATE FUNCTION safe_total(n numeric) RETURNS numeric
  LANGUAGE sql LEAKPROOF STABLE AS $$ SELECT $1 $$;
```

### Row-Level Security Pushdown

```sql
-- RLS policies interact with pushdown:
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON orders
  USING (tenant_id = current_setting('app.tenant_id')::int);

-- Planner CAN push the RLS policy predicate to the scan:
EXPLAIN SELECT * FROM orders WHERE status = 'pending';
-- Seq Scan on orders
--   Filter: ((tenant_id = 123) AND (status = 'pending'))
--                ^^^^^^^^^^^ pushed from RLS policy ✓
```

### Follow-up Probes
- *"You're seeing a view-based query running 100x slower than the equivalent direct table query. Same predicates, same indexes. What's the first thing you check?"*
- *"When does predicate pushdown into a UNION ALL fail, and how do you work around it?"*

---

## Q9. Dev vs Production Performance Gap

### Question
> Design a complete query tuning workflow for a query that runs in 50ms on dev but 45 seconds in production with 500M rows. Be specific about tooling, diagnostics, and each possible root cause.

### Step 1 — Capture Real Production Execution Plans Safely

```sql
-- NEVER run EXPLAIN ANALYZE on a slow query in production directly
-- It executes the full query — 45 seconds of damage

-- Option A: auto_explain (safe, zero overhead for fast queries)
-- postgresql.conf:
shared_preload_libraries = 'auto_explain'
auto_explain.log_min_duration = '1000'    -- log plans for queries > 1s
auto_explain.log_analyze = true
auto_explain.log_buffers = true
auto_explain.log_nested_statements = true
auto_explain.log_format = 'json'          -- easier to parse

-- Option B: pg_stat_activity snapshot (non-blocking)
SELECT
  pid,
  now() - query_start AS duration,
  state,
  wait_event_type,
  wait_event,
  query
FROM pg_stat_activity
WHERE state != 'idle'
  AND now() - query_start > INTERVAL '5 seconds'
ORDER BY duration DESC;

-- Option C: For read-only queries, use a replica with production data
SET enable_seqscan = off;  -- Force index path for comparison
EXPLAIN (ANALYZE, BUFFERS) SELECT ...;
```

### Step 2 — Statistics Parity

```sql
-- Dev: freshly loaded data, perfect statistics
-- Prod: years of churn, statistics lag behind actual distribution

-- Check statistics freshness:
SELECT
  relname,
  n_live_tup,
  n_dead_tup,
  last_vacuum,
  last_autovacuum,
  last_analyze,
  last_autoanalyze,
  -- staleness score:
  now() - last_autoanalyze AS stats_age
FROM pg_stat_user_tables
WHERE relname = 'orders'
ORDER BY stats_age DESC NULLIF;

-- Check if planner's estimate matches reality:
SELECT
  relname,
  reltuples::bigint AS estimated_rows,
  (SELECT COUNT(*) FROM orders) AS actual_rows
FROM pg_class
WHERE relname = 'orders';
-- If reltuples is 2x off from actual → run ANALYZE immediately

-- Force fresh statistics in prod:
ANALYZE VERBOSE orders;
-- Or targeted column analysis:
ANALYZE orders (user_id, status, created_at);
```

### Step 3 — Plan Caching (Parameter Sniffing)

```sql
-- Prepared statements cache the plan after first N executions
-- Plan optimized for first execution's parameter values

PREPARE order_lookup(int) AS
  SELECT * FROM orders
  WHERE user_id = $1
  ORDER BY created_at DESC
  LIMIT 50;

-- First execution: user_id=1 (admin, 1 row) → plan caches Index Scan
-- Later: user_id=99 (power user, 500k rows) → cached Index Scan for 500k rows
-- Should be Seq Scan, but plan is locked in

-- Check cached plan types:
SELECT
  name,
  generic_plans,
  custom_plans,
  -- High generic/custom ratio means caching is happening:
  round(generic_plans::numeric / NULLIF(generic_plans + custom_plans, 0) * 100, 2) AS generic_pct
FROM pg_prepared_statements;

-- Force re-planning:
SET plan_cache_mode = 'force_custom_plan';  -- always re-plan (slower but safer)
SET plan_cache_mode = 'force_generic_plan'; -- always use generic (good for uniform data)
SET plan_cache_mode = 'auto';               -- default: planner decides
```

### Step 4 — Table and Index Bloat

```sql
-- Dev: compact newly loaded data
-- Prod: years of UPDATEs creating dead tuples, bloated pages

-- Comprehensive bloat query:
WITH bloat AS (
  SELECT
    schemaname,
    tablename,
    pg_relation_size(schemaname||'.'||tablename) AS live_size,
    n_dead_tup,
    n_live_tup,
    round(n_dead_tup::numeric / NULLIF(n_live_tup + n_dead_tup, 0) * 100, 2) AS dead_pct
  FROM pg_stat_user_tables
)
SELECT *,
  pg_size_pretty(live_size) AS size,
  CASE
    WHEN dead_pct > 20 THEN '🔴 VACUUM URGENT'
    WHEN dead_pct > 10 THEN '🟡 VACUUM SOON'
    ELSE '🟢 OK'
  END AS status
FROM bloat
WHERE schemaname = 'public'
ORDER BY dead_pct DESC
LIMIT 20;

-- Fix bloat without table lock (for hot tables):
-- pg_repack replaces VACUUM FULL (no AccessExclusiveLock):
-- $ pg_repack -t orders -d mydb
```

### Step 5 — Parallel Query Configuration Differences

```sql
-- Dev: 4 cores, max_parallel_workers_per_gather = 2
-- Prod: 64 cores, max_parallel_workers_per_gather = 8
-- Queries tuned for prod parallelism look different on dev

-- Check current parallel config:
SHOW max_parallel_workers_per_gather;
SHOW max_parallel_workers;
SHOW parallel_setup_cost;
SHOW parallel_tuple_cost;
SHOW min_parallel_table_scan_size;

-- Simulate prod behavior on dev:
SET max_parallel_workers_per_gather = 8;

-- Check if query uses parallel:
EXPLAIN SELECT COUNT(*) FROM orders;
-- Gather (workers planned: 4)  ← parallel ✓
-- Seq Scan on orders  (workers: 4)
```

### Step 6 — Lock Contention Masquerading as Slow Queries

```sql
-- Query "runs slowly" but is actually WAITING for a lock
-- EXPLAIN time ≠ actual wall-clock time if lock wait included

-- Find lock waiters:
SELECT
  blocked.pid,
  blocked.query AS blocked_query,
  blocking.pid AS blocking_pid,
  blocking.query AS blocking_query,
  now() - blocked.query_start AS wait_duration
FROM pg_stat_activity blocked
JOIN pg_stat_activity blocking
  ON blocking.pid = ANY(pg_blocking_pids(blocked.pid))
ORDER BY wait_duration DESC;

-- Set lock timeouts to fail fast instead of silent waits:
SET lock_timeout = '5s';
SET statement_timeout = '30s';

-- Application-level: always set timeouts on connections
-- PgBouncer: client_idle_timeout, query_timeout
```

### Root Cause Checklist

```
□ Statistics freshness           (last_autoanalyze > 24h?)
□ Plan caching / sniffing        (pg_prepared_statements + plan_cache_mode)
□ Table/index bloat              (n_dead_tup / n_live_tup ratio)
□ Parallel config mismatch       (max_parallel_workers_per_gather)
□ Lock contention                (pg_blocking_pids)
□ work_mem insufficient          (temp file creation spikes)
□ shared_buffers / OS cache diff (Buffers: read vs hit ratio)
□ Connection pool saturation     (pg_stat_activity state counts)
□ autovacuum interference        (vacuum running during query)
□ Checkpoint storms              (bgwriter_stat, checkpoint_write_time)
```

---

## Q10. Hidden Costs of Indexes

### Question
> A junior engineer added 12 indexes to a high-write orders table to speed up reporting queries. Inserts that took 5ms now take 120ms. Explain every hidden cost of indexes and how you'd audit and remediate this.

### Write Amplification

```sql
-- Every write operation must maintain ALL indexes:
-- INSERT: 1 heap write + N index writes
-- UPDATE (indexed column): old index entry invalidated + new entry inserted
-- DELETE: index entry marked dead (cleaned by VACUUM later)

-- Measure write amplification:
EXPLAIN (ANALYZE, BUFFERS)
INSERT INTO orders(user_id, product_id, total, status, created_at)
VALUES (123, 456, 99.99, 'pending', NOW());

-- Compare Buffers: shared written with and without indexes
-- 1 index: ~2 buffer writes
-- 12 indexes: ~14 buffer writes → 7x write amplification
```

### Audit: Find Unused Indexes

```sql
-- Indexes that have never been scanned since stats reset
SELECT
  schemaname,
  tablename,
  indexrelname AS index_name,
  pg_size_pretty(pg_relation_size(indexrelid)) AS index_size,
  idx_scan,
  idx_tup_read,
  idx_tup_fetch
FROM pg_stat_user_indexes
WHERE schemaname = 'public'
  AND idx_scan = 0   -- never used for a scan
ORDER BY pg_relation_size(indexrelid) DESC;

-- Caution: stats reset at server restart — use pg_stat_reset() timestamp
SELECT stats_reset FROM pg_stat_bgwriter;
-- Only trust idx_scan = 0 if stats have been running for weeks/months
```

### Audit: Find Redundant Indexes

```sql
-- Indexes subsumed by other indexes (same leading column(s)):
SELECT
  a.indexrelname AS index_a,
  b.indexrelname AS index_b,
  a.amname,
  pg_size_pretty(pg_relation_size(a.indexrelid)) AS size_a,
  pg_size_pretty(pg_relation_size(b.indexrelid)) AS size_b
FROM pg_index ia
JOIN pg_index ib ON ia.indrelid = ib.indrelid AND ia.indexrelid != ib.indexrelid
JOIN pg_indexes a ON a.indexname = (SELECT relname FROM pg_class WHERE oid = ia.indexrelid)
JOIN pg_indexes b ON b.indexname = (SELECT relname FROM pg_class WHERE oid = ib.indexrelid)
JOIN pg_am ON pg_am.oid = (SELECT relam FROM pg_class WHERE oid = ia.indexrelid)
-- Indexes with same first column are often redundant
WHERE ia.indkey[0] = ib.indkey[0]
  AND array_length(ia.indkey::int[], 1) < array_length(ib.indkey::int[], 1);
-- Index A is a prefix of index B → A is likely redundant

-- Tool: pg_duplicate_indexes extension or pganalyze
```

### Index Bloat from MVCC

```sql
-- Dead index entries accumulate — not cleaned until VACUUM
-- Index VACUUM is more expensive than heap VACUUM:
--   Heap: mark pages in FSM, update visibility map
--   Index: scan entire index, remove dead entries, compact

-- Check index bloat:
SELECT
  indexrelname,
  pg_size_pretty(pg_relation_size(indexrelid)) AS current_size,
  idx_scan,
  -- Use pgstattuple for precise bloat measurement:
  (pgstattuple(indexrelid)).dead_leaf_percent AS dead_pct
FROM pg_stat_user_indexes
WHERE schemaname = 'public'
ORDER BY pg_relation_size(indexrelid) DESC;

-- Rebuild bloated index without table lock:
REINDEX INDEX CONCURRENTLY idx_orders_user_id;
-- CONCURRENTLY available from PostgreSQL 12+
```

### HOT Update Suppression

```sql
-- HOT (Heap Only Tuple) updates skip index maintenance entirely
-- Requires: updated column NOT present in ANY index
-- Adding indexes to frequently-updated columns kills HOT rate

-- Scenario: Adding index on 'last_active' for a session table
-- Before: 95% HOT updates (last_active updated every request)
-- After:  0% HOT updates → every session update now writes to index
-- Impact: Write throughput drops by 60%

-- Measure before adding an index:
SELECT
  n_tup_upd,
  n_tup_hot_upd,
  round(n_tup_hot_upd::numeric / NULLIF(n_tup_upd, 0) * 100, 2) AS hot_rate_pct
FROM pg_stat_user_tables
WHERE relname = 'sessions';
-- hot_rate_pct = 94% before adding index
-- hot_rate_pct = 1%  after adding index on 'last_active'
```

### Parallel Query Interference

```sql
-- Index Scan is single-threaded; Seq Scan can be parallelized
-- On large tables with moderate selectivity, Seq Scan with parallelism
-- OUTPERFORMS Index Scan

-- Example: 500M rows, 15% selectivity, 16-core server
-- Parallel Seq Scan: 16 workers × 31M rows each ≈ fast
-- Index Scan: single-threaded, 75M random I/O lookups ≈ slow

-- The planner should handle this, but only with accurate stats:
SHOW random_page_cost;    -- default: 4.0
SHOW seq_page_cost;       -- default: 1.0
-- On NVMe SSD, random I/O cost is much closer to sequential:
SET random_page_cost = 1.1;  -- SSD-appropriate value
-- With lower random_page_cost, planner more aggressively uses indexes
```

### Remediation Plan for 12-Index Table

```sql
-- Step 1: Identify candidates for removal
-- Step 2: Drop unused indexes (safely — can recreate if needed)
DROP INDEX CONCURRENTLY idx_orders_unused_column;

-- Step 3: Merge redundant indexes
-- Before: idx_orders_user_id, idx_orders_user_status
-- After:  idx_orders_user_status (covers user_id queries too as prefix)
CREATE INDEX CONCURRENTLY idx_orders_user_status
ON orders(user_id, status, created_at DESC);
DROP INDEX CONCURRENTLY idx_orders_user_id;

-- Step 4: Use partial indexes to reduce index size and write cost
-- Instead of full index on status:
CREATE INDEX CONCURRENTLY idx_orders_pending
ON orders(user_id, created_at DESC)
WHERE status = 'pending';
-- Only indexes 2% of rows (pending orders) vs 100%
-- Much cheaper to maintain on INSERT/UPDATE

-- Step 5: Measure improvement
-- Before: INSERT 120ms
-- After removing 6 unused/redundant indexes: INSERT 35ms
-- After switching to partial indexes: INSERT 18ms
```

---

## Q11. Partitioning & Partition Pruning

### Question
> You've partitioned a 5-billion-row events table by month (60 partitions). Queries are slower than the unpartitioned table. What went wrong?

### Common Partitioning Pitfalls

```sql
-- Problem 1: Partition key not in WHERE clause → all partitions scanned
-- Table: events PARTITIONED BY RANGE (created_at)

-- PRUNED (fast) — partition key in predicate:
SELECT * FROM events
WHERE created_at BETWEEN '2024-01-01' AND '2024-01-31'
  AND user_id = 123;
-- Planner scans only 1 of 60 partitions ✓

-- NOT PRUNED (slow) — no partition key predicate:
SELECT * FROM events WHERE user_id = 123;
-- Scans ALL 60 partitions → 60x overhead vs single table ✗

-- Check pruning in EXPLAIN:
EXPLAIN SELECT * FROM events WHERE user_id = 123;
-- "Append" node with 60 children = no pruning
-- "Append" node with 1 child = pruned correctly ✓
```

```sql
-- Problem 2: Partition key cast prevents pruning
-- Partition key: created_at (timestamptz)
-- Query: WHERE created_at::date = '2024-01-15'
-- Cast changes type → planner cannot prune!

-- Fix:
WHERE created_at >= '2024-01-15' AND created_at < '2024-01-16'
-- Use range, not cast ✓

-- Problem 3: Non-immutable function in partition key:
WHERE date_trunc('month', created_at) = date_trunc('month', NOW())
-- date_trunc is immutable but NOW() is not — pruning may fail
-- Fix:
WHERE created_at >= date_trunc('month', NOW())
  AND created_at < date_trunc('month', NOW()) + INTERVAL '1 month'
```

### Partition Overhead at Scale

```sql
-- With 60 partitions: planner must evaluate each partition for pruning
-- Planning time increases with partition count
-- At 1000+ partitions: planning can take seconds

-- Check planning overhead:
EXPLAIN (ANALYZE, TIMING)
SELECT COUNT(*) FROM events WHERE created_at > '2024-01-01';

-- Output includes:
-- Planning Time: 450ms   ← should be <10ms for simple queries
-- Execution Time: 120ms

-- Tune for large partition counts:
SET enable_partition_pruning = on;        -- ensure it's on
SET partition_prune_limit = 100;          -- limit pruning cost for huge tables

-- Check partition metadata overhead:
SELECT
  parent.relname AS parent,
  COUNT(child.relname) AS partition_count
FROM pg_inherits
JOIN pg_class parent ON parent.oid = inhparent
JOIN pg_class child  ON child.oid = inhrelid
WHERE parent.relname = 'events'
GROUP BY parent.relname;
```

### Partition-Wise Operations

```sql
-- Partition-wise JOIN: join each partition pair independently (parallelizable)
SET enable_partitionwise_join = on;

-- Partition-wise AGGREGATE: aggregate per partition, then combine
SET enable_partitionwise_aggregate = on;

-- Both are OFF by default (planning overhead can exceed benefit for few partitions)
-- Enable for OLAP workloads with many partitions and large parallel workers

-- Verify it's working:
EXPLAIN SELECT date_trunc('month', created_at), COUNT(*)
FROM events
GROUP BY 1;
-- Should show: "Partial Aggregate" within each partition worker
```

### ATTACH / DETACH for Zero-Downtime Maintenance

```sql
-- Loading historical data — avoid disrupting live partitions:
-- Step 1: Create and load staging table (no partition overhead)
CREATE TABLE events_2024_01_staging (LIKE events);
\COPY events_2024_01_staging FROM '/data/events_2024_01.csv' CSV;
CREATE INDEX ON events_2024_01_staging(user_id);
CREATE INDEX ON events_2024_01_staging(created_at);

-- Step 2: Validate data before attaching
ALTER TABLE events_2024_01_staging
  ADD CONSTRAINT chk_2024_01
  CHECK (created_at >= '2024-01-01' AND created_at < '2024-02-01');
-- Constraint makes ATTACH nearly lock-free (just check constraint verified)

-- Step 3: Atomic attach (brief ShareLock only, not AccessExclusiveLock)
ALTER TABLE events
  ATTACH PARTITION events_2024_01_staging
  FOR VALUES FROM ('2024-01-01') TO ('2024-02-01');

-- Step 4: Detach old partitions for archiving (no query disruption)
ALTER TABLE events DETACH PARTITION events_2022_01 CONCURRENTLY;
-- CONCURRENTLY mode available in PG 14+
```

---

## Q12. Parallel Query Internals

### Question
> A parallel query on a 16-core server is only using 2 workers. Your query touches 2 billion rows. Walk through every reason parallel query might be throttled and how to fix each.

### Parallel Query Architecture

```
Gather / Gather Merge
  ├── Worker 0: Partial Seq Scan (range 0)
  ├── Worker 1: Partial Seq Scan (range 1)
  ├── Worker 2: Partial Seq Scan (range 2)
  └── Leader:   Partial Seq Scan (range 3) + finalize aggregation
```

### Throttle Cause 1 — Size Threshold Not Met

```sql
-- Table must be larger than min_parallel_table_scan_size:
SHOW min_parallel_table_scan_size;  -- default: 8MB
-- For index scans:
SHOW min_parallel_index_scan_size;  -- default: 512kB

-- Check table size:
SELECT pg_size_pretty(pg_relation_size('events'));

-- Force parallel for testing:
SET min_parallel_table_scan_size = '1kB';
SET parallel_setup_cost = 0;
SET parallel_tuple_cost = 0;
```

### Throttle Cause 2 — Workers Cap

```sql
-- Global worker pool:
SHOW max_worker_processes;          -- total background workers (default: 8)
SHOW max_parallel_workers;          -- subset for parallel queries (default: 8)
SHOW max_parallel_workers_per_gather; -- per single Gather node (default: 2)

-- Often max_parallel_workers_per_gather = 2 limits every query to 2 workers
-- Tune for your core count:
ALTER SYSTEM SET max_parallel_workers_per_gather = 8;
ALTER SYSTEM SET max_parallel_workers = 14;
-- Leave 2 for autovacuum, wal_writer, checkpointer
SELECT pg_reload_conf();
```

### Throttle Cause 3 — Non-Parallelizable Operations

```sql
-- These operations CANNOT be parallelized:
-- - Queries with PARALLEL UNSAFE functions
-- - Queries inside a transaction that has written data
-- - Cursors
-- - Certain window functions
-- - CTEs that reference VOLATILE functions

-- Check function parallel safety:
SELECT
  proname,
  proparallel  -- 's'=safe, 'r'=restricted, 'u'=unsafe
FROM pg_proc
WHERE proname IN ('my_custom_function', 'another_function');

-- Mark functions parallel-safe (only if they truly are):
ALTER FUNCTION my_pure_calculation(numeric) PARALLEL SAFE;
```

### Throttle Cause 4 — Already In a Transaction

```sql
-- Parallel query disabled inside explicit transactions that modified data:
BEGIN;
INSERT INTO audit_log VALUES (...);  -- write occurred
-- Now parallel queries are disabled for this transaction:
SELECT COUNT(*) FROM events;  -- single-threaded, even with parallel config ✓
COMMIT;

-- Workaround: separate read queries from write transactions
-- Or use READ ONLY transactions:
BEGIN READ ONLY;
SELECT COUNT(*) FROM events;  -- parallel allowed ✓
COMMIT;
```

### Monitoring Parallel Query Usage

```sql
-- Check if parallel workers are actually being used:
SELECT
  pid,
  query,
  backend_type   -- 'parallel worker' vs 'client backend'
FROM pg_stat_activity
WHERE state = 'active'
ORDER BY backend_type;

-- Aggregate parallel worker utilization:
SELECT
  backend_type,
  COUNT(*) AS count,
  AVG(EXTRACT(EPOCH FROM now() - query_start)) AS avg_duration_sec
FROM pg_stat_activity
WHERE state = 'active'
GROUP BY backend_type;
```

---

## Q13. Vacuuming & Autovacuum Tuning

### Question
> Your autovacuum can't keep up with a 50,000 inserts/second + 20,000 updates/second workload. Table bloat is growing 2GB/hour. What's your tuning approach?

### Why Default Autovacuum Fails at Scale

```sql
-- Default autovacuum trigger:
-- vacuum_scale_factor = 0.2 (20% of table dead)
-- vacuum_threshold = 50 rows
-- Trigger: dead_tuples > 50 + 0.2 × reltuples

-- For 500M row table: trigger at 100,000,050 dead tuples
-- At 20,000 updates/sec × 3600 sec = 72M dead tuples/hour
-- Autovacuum never triggers!

-- Fix: lower scale factor for large tables
ALTER TABLE orders SET (
  autovacuum_vacuum_scale_factor = 0.01,    -- 1% instead of 20%
  autovacuum_vacuum_threshold = 1000,
  autovacuum_analyze_scale_factor = 0.005,
  autovacuum_analyze_threshold = 500,
  autovacuum_vacuum_cost_delay = 2,          -- reduce throttling (default: 2ms)
  autovacuum_vacuum_cost_limit = 400         -- increase work per vacuum round (default: 200)
);
```

### Increase Autovacuum Workers

```sql
-- Default: only 3 autovacuum workers for entire cluster
SHOW autovacuum_max_workers;  -- default: 3

-- For write-heavy workloads:
ALTER SYSTEM SET autovacuum_max_workers = 8;

-- Separate vacuum cost limits from autovacuum:
ALTER SYSTEM SET autovacuum_vacuum_cost_delay = '1ms';   -- less throttling
ALTER SYSTEM SET autovacuum_vacuum_cost_limit = 800;     -- more work per round

SELECT pg_reload_conf();
```

### Manual VACUUM for Emergency Bloat

```sql
-- Check what's happening with autovacuum:
SELECT
  relname,
  n_dead_tup,
  n_live_tup,
  last_vacuum,
  last_autovacuum,
  vacuum_count,
  autovacuum_count,
  -- Is autovacuum currently running?
  (SELECT COUNT(*) FROM pg_stat_activity
   WHERE query LIKE 'autovacuum:%' AND query LIKE '%'||relname||'%') AS autovac_running
FROM pg_stat_user_tables
WHERE schemaname = 'public'
ORDER BY n_dead_tup DESC
LIMIT 10;

-- Manual vacuum with verbosity:
VACUUM (VERBOSE, ANALYZE) orders;

-- Manual vacuum that doesn't hold lock as long as VACUUM FULL:
-- pg_repack for lock-free compaction:
-- $ pg_repack --no-superuser-check -t orders -d mydb
```

### Transaction ID Wraparound Emergency

```sql
-- XID wraparound causes forced shutdown if not addressed
-- Check proximity to wraparound:
SELECT
  datname,
  age(datfrozenxid) AS xid_age,
  2147483647 - age(datfrozenxid) AS xids_remaining,
  CASE
    WHEN age(datfrozenxid) > 1500000000 THEN '🚨 CRITICAL'
    WHEN age(datfrozenxid) > 1000000000 THEN '🔴 URGENT'
    WHEN age(datfrozenxid) > 500000000  THEN '🟡 WARNING'
    ELSE '🟢 OK'
  END AS status
FROM pg_database
ORDER BY xid_age DESC;

-- If in danger zone, force aggressive vacuum:
VACUUM FREEZE VERBOSE orders;
-- Set autovacuum_freeze_max_age lower to prevent reaching danger zone:
ALTER SYSTEM SET autovacuum_freeze_max_age = 150000000;  -- default: 200M
```

---

## Q14. Connection Pooling Impact on Query Performance

### Question
> At 10,000 RPS, your Postgres instance has 2,000 active connections. Queries that took 5ms now take 2 seconds. The queries themselves haven't changed. Diagnose and fix.

### The Connection Overhead Problem

```sql
-- Each PostgreSQL connection is a separate OS process
-- 2,000 connections = 2,000 processes competing for:
-- - CPU scheduler slots
-- - Shared memory (lock tables, buffer pool)
-- - Kernel resources (file descriptors, semaphores)

-- Check current connection state:
SELECT
  state,
  wait_event_type,
  wait_event,
  COUNT(*) AS connection_count
FROM pg_stat_activity
GROUP BY state, wait_event_type, wait_event
ORDER BY connection_count DESC;

-- Typical degraded state:
-- active: 50
-- idle: 1800          ← connections doing nothing but holding memory
-- idle in transaction: 150  ← MOST DANGEROUS — holding locks
```

### Identify Lock Contention from Connection Pile-up

```sql
-- Idle-in-transaction connections holding locks:
SELECT
  pid,
  now() - xact_start AS transaction_age,
  now() - state_change AS idle_duration,
  state,
  query
FROM pg_stat_activity
WHERE state = 'idle in transaction'
  AND (now() - state_change) > INTERVAL '30 seconds'
ORDER BY transaction_age DESC;

-- Kill dangerous idle-in-transaction connections:
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE state = 'idle in transaction'
  AND (now() - state_change) > INTERVAL '60 seconds';
```

### PgBouncer Configuration

```ini
# pgbouncer.ini — transaction pooling for OLTP

[databases]
mydb = host=127.0.0.1 port=5432 dbname=mydb

[pgbouncer]
pool_mode = transaction           # Return connection after each transaction
max_client_conn = 5000            # Clients PgBouncer accepts
default_pool_size = 50            # Actual Postgres connections per database/user
reserve_pool_size = 10            # Emergency pool
reserve_pool_timeout = 3          # Seconds before using reserve pool

# Connection lifetime management:
server_idle_timeout = 600
server_lifetime = 3600
client_idle_timeout = 60

# Transaction mode gotchas:
# - SET statements are per-connection, not per-transaction (will leak!)
# - Prepared statements don't work in transaction mode
# - Advisory locks are per-connection, not per-transaction
```

```sql
-- Fix prepared statement incompatibility with PgBouncer transaction mode:
-- Option 1: Disable prepared statements in your app
-- (Node.js pg: set prepare: false)

-- Option 2: Use pgBouncer session mode for connections needing prepared statements

-- Option 3: PgBouncer 1.21+ supports prepared statement tracking
-- server_reset_query = DISCARD ALL
-- track_extra_parameters = search_path, timezone, ...
```

### Statement-Level Timeouts as Circuit Breakers

```sql
-- Prevent slow queries from snowballing into connection saturation:

-- In postgresql.conf:
-- statement_timeout = '30s'      -- Kill queries running > 30s
-- idle_in_transaction_session_timeout = '60s'  -- Kill idle-in-transaction

-- Per-role (more granular):
ALTER ROLE app_user SET statement_timeout = '10s';
ALTER ROLE analyst_user SET statement_timeout = '300s';
ALTER ROLE app_user SET idle_in_transaction_session_timeout = '30s';
```

---

## Q15. Serializable Isolation & SSI Performance

### Question
> You've implemented a financial system using SERIALIZABLE isolation to ensure correctness. Under high concurrency it's failing with serialization errors and the retry logic is causing a thundering herd. How do you fix this without dropping isolation level?

### Understanding SSI (Serializable Snapshot Isolation)

```sql
-- SSI tracks read/write dependencies between transactions
-- Detects "dangerous structures" that would cause anomalies
-- Aborts one transaction in the cycle to prevent anomaly

-- Classic serialization anomaly (write skew):
-- T1: reads balance(alice)=100, balance(bob)=100, both > 0
-- T1: writes alice.balance = alice.balance - 200
-- T2: reads balance(alice)=100, balance(bob)=100, both > 0
-- T2: writes bob.balance = bob.balance - 200
-- Result: both negative — impossible if run serially!

BEGIN ISOLATION LEVEL SERIALIZABLE;
SELECT SUM(balance) FROM accounts WHERE user_id = 42;
-- If sum > 0, proceed with withdrawal
UPDATE accounts SET balance = balance - 200 WHERE account = 'checking';
COMMIT;  -- May get: ERROR: could not serialize access due to concurrent update
```

### Minimize Serialization Conflicts

```sql
-- Rule 1: Minimize transaction span (time between BEGIN and COMMIT)
-- Every second held = more concurrent transactions = more conflict potential

-- BAD: Long-running serializable transaction
BEGIN ISOLATION LEVEL SERIALIZABLE;
SELECT * FROM orders WHERE status = 'pending';  -- large read set
-- ... 500ms of application processing ...
UPDATE orders SET status = 'processing' WHERE id = $1;
COMMIT;

-- GOOD: Read outside transaction, write in short transaction
-- Step 1: Read without SERIALIZABLE
SELECT * FROM orders WHERE status = 'pending' LIMIT 100;
-- ... application processing ...

-- Step 2: Short SERIALIZABLE write
BEGIN ISOLATION LEVEL SERIALIZABLE;
UPDATE orders SET status = 'processing'
WHERE id = $1 AND status = 'pending';  -- re-check in transaction
-- SELECT to re-validate if needed
COMMIT;
```

### Retry Logic Without Thundering Herd

```python
import time
import random
import psycopg2

def execute_with_retry(conn, operation, max_retries=5):
    """Exponential backoff with jitter for serialization failures."""
    for attempt in range(max_retries):
        try:
            with conn.cursor() as cur:
                cur.execute("BEGIN ISOLATION LEVEL SERIALIZABLE")
                result = operation(cur)
                cur.execute("COMMIT")
                return result
        except psycopg2.errors.SerializationFailure as e:
            cur.execute("ROLLBACK")
            if attempt == max_retries - 1:
                raise
            # Exponential backoff + jitter prevents thundering herd:
            sleep_time = (2 ** attempt * 10) + random.uniform(0, 10)
            time.sleep(sleep_time / 1000)  # milliseconds
```

```sql
-- Monitor serialization failure rate:
SELECT
  datname,
  xact_commit,
  xact_rollback,
  round(xact_rollback::numeric / NULLIF(xact_commit + xact_rollback, 0) * 100, 2)
    AS rollback_pct
FROM pg_stat_database
WHERE datname = 'mydb';

-- Detailed SSI statistics:
SELECT * FROM pg_stat_user_tables
WHERE relname = 'accounts';
-- n_tup_hot_upd vs n_tup_upd shows HOT rate
-- seq_scan vs idx_scan shows scan pattern

-- pg_locks for predicate locks (SSI-specific):
SELECT locktype, relation::regclass, mode, granted
FROM pg_locks
WHERE locktype = 'relation'
  AND mode LIKE '%Serialize%';
```

### Tune SSI Parameters

```sql
-- max_pred_locks_per_transaction: predicate locks before escalation
-- Escalation promotes row/page locks to table-level → more conflicts
SHOW max_pred_locks_per_transaction;  -- default: 64

-- Increase for queries with large read sets:
ALTER SYSTEM SET max_pred_locks_per_transaction = 256;

-- max_pred_locks_per_relation: per-table cap
ALTER SYSTEM SET max_pred_locks_per_relation = -2;
-- Negative = max_pred_locks_per_transaction / abs(value) partitions

SELECT pg_reload_conf();
```

---

## 🏆 Evaluation Framework

### Rating Rubric for 20-Year Candidates

| Dimension | Junior Answer | Senior Answer | Expert Answer |
|-----------|--------------|---------------|---------------|
| **EXPLAIN reading** | Reads cost estimate | Reads actual vs estimate | Reads loops, buffers, spill signals |
| **Stats knowledge** | "Run ANALYZE" | Knows statistics targets | Extended statistics, correlation, MCV |
| **Index design** | Creates B-Tree | Considers partial/composite | Analyzes HOT rate, bloat, write amplification |
| **Join strategy** | "Add an index" | Knows hash/merge/NL tradeoffs | Debugs misestimation, uses extended stats |
| **Production ops** | Dev/prod are same | Mentions connection pooling | Full incident runbook, auto_explain, lock analysis |
| **Parallelism** | "Postgres is slow" | Knows parallel workers | Tunes per-role, identifies barriers |

### Must-Ask Follow-up Questions

```
1. "Tell me about the worst query performance incident you caused or solved."
   → Listen for: specific numbers, root cause analysis, prevention measures

2. "How do you know when NOT to add an index?"
   → Listen for: write amplification, HOT rate, unused index detection

3. "Your EXPLAIN ANALYZE shows 5ms but users report 8-second queries. What happened?"
   → Listen for: lock waits, connection pool saturation, result set transfer time, statement_timeout

4. "You need to add a column with a default value to a 500M-row table. Zero downtime required."
   → Listen for: PG 11+ instant ADD COLUMN with default, vs older approach with nullable+backfill+constraint

5. "Describe your runbook for a production Postgres that's at 99% CPU."
   → Listen for: pg_stat_activity, pg_stat_statements, long-running queries, lock contention, autovacuum storms
```

---

## 📚 Reference Queries Cheat Sheet

```sql
-- ① Top slow queries (requires pg_stat_statements)
SELECT left(query, 100), calls, total_exec_time/calls AS avg_ms,
       stddev_exec_time AS stddev_ms, rows/calls AS avg_rows
FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 20;

-- ② Active queries with wait events
SELECT pid, wait_event_type, wait_event, now()-query_start AS age, state, left(query,80)
FROM pg_stat_activity WHERE state != 'idle' ORDER BY age DESC;

-- ③ Lock blockers
SELECT blocked.pid, blocking.pid AS blocking_pid, blocked.query AS blocked_query,
       blocking.query AS blocking_query
FROM pg_stat_activity blocked
JOIN pg_stat_activity blocking ON blocking.pid = ANY(pg_blocking_pids(blocked.pid));

-- ④ Table bloat overview
SELECT relname, n_dead_tup, n_live_tup, last_autovacuum,
       round(n_dead_tup::numeric/NULLIF(n_live_tup+n_dead_tup,0)*100,2) AS dead_pct
FROM pg_stat_user_tables WHERE schemaname='public' ORDER BY n_dead_tup DESC LIMIT 20;

-- ⑤ Unused indexes
SELECT schemaname, tablename, indexrelname,
       pg_size_pretty(pg_relation_size(indexrelid)) AS size, idx_scan
FROM pg_stat_user_indexes WHERE idx_scan = 0 ORDER BY pg_relation_size(indexrelid) DESC;

-- ⑥ Index usage efficiency
SELECT relname, indexrelname, idx_scan, idx_tup_read, idx_tup_fetch,
       round(idx_tup_fetch::numeric/NULLIF(idx_tup_read,0)*100,2) AS fetch_pct
FROM pg_stat_user_indexes WHERE schemaname='public' ORDER BY idx_scan DESC;

-- ⑦ Cache hit ratio (target > 99%)
SELECT sum(heap_blks_hit)::float/(sum(heap_blks_hit)+sum(heap_blks_read)) AS hit_ratio
FROM pg_statio_user_tables;

-- ⑧ Transaction ID wraparound risk
SELECT datname, age(datfrozenxid) AS xid_age,
       2147483647-age(datfrozenxid) AS xids_remaining
FROM pg_database ORDER BY xid_age DESC;

-- ⑨ Replication lag
SELECT client_addr, state, sent_lsn, write_lsn, flush_lsn, replay_lsn,
       pg_wal_lsn_diff(sent_lsn, replay_lsn) AS lag_bytes
FROM pg_stat_replication ORDER BY lag_bytes DESC;

-- ⑩ Index-only scan efficiency
SELECT relname, indexrelname, idx_scan,
       idx_tup_read - idx_tup_fetch AS heap_fetches,
       round((idx_tup_read-idx_tup_fetch)::numeric/NULLIF(idx_tup_read,0)*100,2) AS heap_fetch_pct
FROM pg_stat_user_indexes WHERE idx_scan > 0 ORDER BY heap_fetch_pct DESC;
```

---

*Generated for: 20-Year Backend Engineering Interviews | PostgreSQL 14–17*  
*Last Updated: 2025 | Focus: Query Tuning, Planner Internals, Production Ops*
