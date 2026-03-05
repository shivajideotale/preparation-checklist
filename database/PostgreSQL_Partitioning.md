# PostgreSQL — Table Partitioning Complete Reference

> A deep-dive guide covering all partitioning strategies, partition pruning, indexes, maintenance, inheritance-based partitioning, and real-world patterns in PostgreSQL.

---

## Table of Contents

1. [What is Partitioning?](#1-what-is-partitioning)
2. [Range Partitioning](#2-range-partitioning)
3. [List Partitioning](#3-list-partitioning)
4. [Hash Partitioning](#4-hash-partitioning)
5. [Sub-Partitioning (Composite)](#5-sub-partitioning-composite)
6. [Partition Pruning](#6-partition-pruning)
7. [Indexes on Partitioned Tables](#7-indexes-on-partitioned-tables)
8. [Constraints & Foreign Keys](#8-constraints--foreign-keys)
9. [Partition Maintenance](#9-partition-maintenance)
10. [Default Partition](#10-default-partition)
11. [Partition Routing & Routing Rules](#11-partition-routing--routing-rules)
12. [Inheritance-Based Partitioning (Legacy)](#12-inheritance-based-partitioning-legacy)
13. [Partitioning vs Indexing](#13-partitioning-vs-indexing)
14. [Real-World Patterns](#14-real-world-patterns)
15. [Monitoring Partitions](#15-monitoring-partitions)
16. [Performance Tuning](#16-performance-tuning)
17. [Quick Reference Cheat Sheet](#17-quick-reference-cheat-sheet)

---

## Sample Tables Used in All Examples

```sql
-- We'll build these progressively through the guide
-- Base reference tables
CREATE TABLE products (
    id       SERIAL PRIMARY KEY,
    name     TEXT,
    category TEXT,
    price    NUMERIC
);

INSERT INTO products VALUES
  (1, 'Laptop',   'Electronics', 80000),
  (2, 'Mouse',    'Accessories',  1500),
  (3, 'Monitor',  'Electronics', 25000),
  (4, 'Keyboard', 'Accessories',  3000),
  (5, 'Webcam',   'Electronics',  8000);
```

---

## 1. What is Partitioning?

**Partitioning** splits one logical table into multiple physical storage pieces called **partitions**. Applications query the parent table as normal — PostgreSQL automatically routes inserts to the right partition and prunes irrelevant partitions from queries.

### Why Partition?

```
Problem: A table with 500 million rows
  → Every query scans a massive amount of data
  → Indexes become huge and slow to update
  → VACUUM takes hours and blocks operations
  → Dropping old data requires DELETE on millions of rows

Solution: Partition by date, region, or hash
  → Queries touch only relevant partitions (partition pruning)
  → Indexes are smaller, per-partition
  → VACUUM runs per-partition — faster
  → Drop old data by dropping a partition — instant!
```

### How Declarative Partitioning Works (PostgreSQL 10+)

```
                  orders (parent — no data stored here)
                  /           |           \
        orders_2024    orders_2025    orders_default
        (partition)    (partition)    (catch-all)
           /    \
   q1_2024   q2_2024
  (sub-part) (sub-part)

INSERT INTO orders ... → routed to correct partition automatically
SELECT FROM orders ... → only relevant partitions scanned (pruning)
```

### Partition Types

| Type | Partition Key | Use Case |
|------|--------------|----------|
| **RANGE** | Continuous range (date, number) | Time-series, log data, archives |
| **LIST** | Explicit value list | Country, status, category, region |
| **HASH** | Hash of column value | Even distribution, sharding |

---

## 2. Range Partitioning

Range partitioning divides data into contiguous, non-overlapping ranges. The most common pattern is partitioning by **date**.

### Create a Range-Partitioned Table

```sql
-- Step 1: Create the partitioned parent table
CREATE TABLE orders (
    id          BIGSERIAL,
    customer_id INTEGER       NOT NULL,
    product_id  INTEGER       NOT NULL,
    amount      NUMERIC(12,2) NOT NULL,
    status      TEXT          NOT NULL DEFAULT 'pending',
    region      TEXT,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    PRIMARY KEY (id, created_at)   -- partition key MUST be part of PK
) PARTITION BY RANGE (created_at);
```

### Create Partitions

```sql
-- Annual partitions
CREATE TABLE orders_2022 PARTITION OF orders
    FOR VALUES FROM ('2022-01-01') TO ('2023-01-01');

CREATE TABLE orders_2023 PARTITION OF orders
    FOR VALUES FROM ('2023-01-01') TO ('2024-01-01');

CREATE TABLE orders_2024 PARTITION OF orders
    FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');

-- Quarterly partitions (more granular)
CREATE TABLE orders_2024_q1 PARTITION OF orders
    FOR VALUES FROM ('2024-01-01') TO ('2024-04-01');

CREATE TABLE orders_2024_q2 PARTITION OF orders
    FOR VALUES FROM ('2024-04-01') TO ('2024-07-01');

CREATE TABLE orders_2024_q3 PARTITION OF orders
    FOR VALUES FROM ('2024-07-01') TO ('2024-10-01');

CREATE TABLE orders_2024_q4 PARTITION OF orders
    FOR VALUES FROM ('2024-10-01') TO ('2025-01-01');

-- Monthly partitions (finest granularity)
CREATE TABLE orders_2024_01 PARTITION OF orders
    FOR VALUES FROM ('2024-01-01') TO ('2024-02-01');

CREATE TABLE orders_2024_02 PARTITION OF orders
    FOR VALUES FROM ('2024-02-01') TO ('2024-03-01');

CREATE TABLE orders_2024_03 PARTITION OF orders
    FOR VALUES FROM ('2024-03-01') TO ('2024-04-01');
-- ... continue for each month
```

### Range Partition Rules

```sql
-- Partition bounds are: FROM (inclusive) TO (exclusive)
-- '2024-01-01' TO '2024-04-01' means:
--   >= 2024-01-01 00:00:00  AND  < 2024-04-01 00:00:00

-- Test: insert routes to correct partition
INSERT INTO orders (customer_id, product_id, amount, created_at)
VALUES (1, 1, 80000, '2024-02-15');
-- → goes to orders_2024_q1 or orders_2024_02

-- Verify which partition holds data
SELECT tableoid::regclass AS partition, id, created_at
FROM orders
WHERE created_at = '2024-02-15';
-- Returns: orders_2024_q1 | 1 | 2024-02-15
```

### Range on Numeric Column

```sql
-- Partition orders by amount range
CREATE TABLE order_amounts (
    id     BIGSERIAL,
    amount NUMERIC NOT NULL,
    detail TEXT
) PARTITION BY RANGE (amount);

CREATE TABLE order_amounts_small  PARTITION OF order_amounts
    FOR VALUES FROM (MINVALUE) TO (10000);

CREATE TABLE order_amounts_medium PARTITION OF order_amounts
    FOR VALUES FROM (10000) TO (100000);

CREATE TABLE order_amounts_large  PARTITION OF order_amounts
    FOR VALUES FROM (100000) TO (MAXVALUE);
```

### Range on Multiple Columns

```sql
-- Partition by (year, region) — composite range key
CREATE TABLE sales_data (
    id        BIGSERIAL,
    year      INT  NOT NULL,
    region    TEXT NOT NULL,
    amount    NUMERIC,
    sale_date DATE,
    PRIMARY KEY (id, year, region)
) PARTITION BY RANGE (year, region);

CREATE TABLE sales_2024_east PARTITION OF sales_data
    FOR VALUES FROM (2024, 'East') TO (2024, 'North');

CREATE TABLE sales_2024_north PARTITION OF sales_data
    FOR VALUES FROM (2024, 'North') TO (2024, 'South');
-- Note: text ranges use lexicographic ordering
```

---

## 3. List Partitioning

List partitioning assigns specific **discrete values** to each partition. Used when you want to group by category, region, status, or country.

### Create a List-Partitioned Table

```sql
CREATE TABLE employees (
    id          SERIAL,
    name        TEXT         NOT NULL,
    email       TEXT,
    salary      NUMERIC,
    department  TEXT         NOT NULL,
    region      TEXT         NOT NULL,
    joined_at   DATE,
    is_active   BOOLEAN DEFAULT true,
    PRIMARY KEY (id, region)   -- partition key in PK
) PARTITION BY LIST (region);
```

### Create Partitions

```sql
-- Partition by region
CREATE TABLE employees_north PARTITION OF employees
    FOR VALUES IN ('North', 'Northwest', 'Northeast');

CREATE TABLE employees_south PARTITION OF employees
    FOR VALUES IN ('South', 'Southwest', 'Southeast');

CREATE TABLE employees_east  PARTITION OF employees
    FOR VALUES IN ('East');

CREATE TABLE employees_west  PARTITION OF employees
    FOR VALUES IN ('West');

-- Partition by department
CREATE TABLE employees_tech PARTITION OF employees
    FOR VALUES IN ('Engineering', 'DevOps', 'QA', 'Architecture');

CREATE TABLE employees_biz  PARTITION OF employees
    FOR VALUES IN ('Marketing', 'Sales', 'Finance', 'Legal');

CREATE TABLE employees_ops  PARTITION OF employees
    FOR VALUES IN ('HR', 'Operations', 'Admin', 'Facilities');
```

### List Partition with Status

```sql
CREATE TABLE order_pipeline (
    id         BIGSERIAL,
    customer_id INTEGER,
    amount     NUMERIC,
    status     TEXT NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (id, status)
) PARTITION BY LIST (status);

CREATE TABLE order_pipeline_active PARTITION OF order_pipeline
    FOR VALUES IN ('pending', 'processing', 'awaiting_payment');

CREATE TABLE order_pipeline_transit PARTITION OF order_pipeline
    FOR VALUES IN ('shipped', 'out_for_delivery', 'in_transit');

CREATE TABLE order_pipeline_closed PARTITION OF order_pipeline
    FOR VALUES IN ('delivered', 'cancelled', 'refunded', 'returned');
```

### Multi-Column List (PG 15+)

```sql
-- PostgreSQL 15+ supports multi-column list partitioning
CREATE TABLE global_sales (
    id      BIGSERIAL,
    country TEXT NOT NULL,
    channel TEXT NOT NULL,
    amount  NUMERIC,
    PRIMARY KEY (id, country, channel)
) PARTITION BY LIST (country, channel);

CREATE TABLE global_sales_india_online PARTITION OF global_sales
    FOR VALUES IN (('India', 'online'));

CREATE TABLE global_sales_india_store PARTITION OF global_sales
    FOR VALUES IN (('India', 'store'));

CREATE TABLE global_sales_us_online   PARTITION OF global_sales
    FOR VALUES IN (('US', 'online'));
```

---

## 4. Hash Partitioning

Hash partitioning distributes rows **evenly** across a fixed number of partitions using a hash of the partition key. No natural ordering — useful when you need even distribution.

### Create a Hash-Partitioned Table

```sql
CREATE TABLE user_sessions (
    id         BIGSERIAL,
    user_id    INTEGER      NOT NULL,
    session_token TEXT      NOT NULL,
    data       JSONB,
    created_at TIMESTAMPTZ  DEFAULT NOW(),
    expires_at TIMESTAMPTZ,
    PRIMARY KEY (id, user_id)
) PARTITION BY HASH (user_id);
```

### Create Partitions

```sql
-- 4 partitions (MODULUS = total partitions, REMAINDER = partition index 0-based)
CREATE TABLE user_sessions_0 PARTITION OF user_sessions
    FOR VALUES WITH (MODULUS 4, REMAINDER 0);

CREATE TABLE user_sessions_1 PARTITION OF user_sessions
    FOR VALUES WITH (MODULUS 4, REMAINDER 1);

CREATE TABLE user_sessions_2 PARTITION OF user_sessions
    FOR VALUES WITH (MODULUS 4, REMAINDER 2);

CREATE TABLE user_sessions_3 PARTITION OF user_sessions
    FOR VALUES WITH (MODULUS 4, REMAINDER 3);
```

### How Many Partitions?

```sql
-- Rule of thumb for hash partition count:
-- 2   partitions: 2x parallelism, minimal overhead
-- 4   partitions: good balance
-- 8   partitions: high-write tables with many concurrent writers
-- 16+ partitions: extreme scale, beware planning overhead

-- Verify data is evenly distributed
SELECT
    tableoid::regclass                        AS partition,
    COUNT(*)                                  AS row_count,
    ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 1) AS pct
FROM user_sessions
GROUP BY tableoid
ORDER BY partition;
```

### Scaling Up Hash Partitions

```sql
-- You CANNOT add hash partitions to an existing table
-- Strategy: create new table with more partitions, migrate data

CREATE TABLE user_sessions_v2 (
    id         BIGSERIAL,
    user_id    INTEGER NOT NULL,
    session_token TEXT NOT NULL,
    data       JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (id, user_id)
) PARTITION BY HASH (user_id);

CREATE TABLE user_sessions_v2_0 PARTITION OF user_sessions_v2
    FOR VALUES WITH (MODULUS 8, REMAINDER 0);
-- ... create all 8 partitions ...

-- Migrate data
INSERT INTO user_sessions_v2 SELECT * FROM user_sessions;

-- Atomic swap
BEGIN;
ALTER TABLE user_sessions RENAME TO user_sessions_old;
ALTER TABLE user_sessions_v2 RENAME TO user_sessions;
COMMIT;
```

---

## 5. Sub-Partitioning (Composite)

You can partition a partition — creating a two-level (or deeper) partition hierarchy.

### Range → List Sub-Partitioning

```sql
-- Level 1: partition orders by year (range)
CREATE TABLE orders_partitioned (
    id          BIGSERIAL,
    customer_id INTEGER      NOT NULL,
    amount      NUMERIC,
    status      TEXT         NOT NULL DEFAULT 'pending',
    region      TEXT         NOT NULL,
    created_at  DATE         NOT NULL,
    PRIMARY KEY (id, created_at, region)
) PARTITION BY RANGE (created_at);

-- Level 1 partitions: one per year
CREATE TABLE orders_2024 PARTITION OF orders_partitioned
    FOR VALUES FROM ('2024-01-01') TO ('2025-01-01')
    PARTITION BY LIST (region);   -- sub-partition by region

CREATE TABLE orders_2025 PARTITION OF orders_partitioned
    FOR VALUES FROM ('2025-01-01') TO ('2026-01-01')
    PARTITION BY LIST (region);

-- Level 2 partitions: each year split by region
CREATE TABLE orders_2024_north PARTITION OF orders_2024
    FOR VALUES IN ('North', 'Northeast', 'Northwest');

CREATE TABLE orders_2024_south PARTITION OF orders_2024
    FOR VALUES IN ('South', 'Southeast', 'Southwest');

CREATE TABLE orders_2024_east  PARTITION OF orders_2024
    FOR VALUES IN ('East');

CREATE TABLE orders_2024_west  PARTITION OF orders_2024
    FOR VALUES IN ('West');

-- Same for 2025
CREATE TABLE orders_2025_north PARTITION OF orders_2025
    FOR VALUES IN ('North', 'Northeast', 'Northwest');

CREATE TABLE orders_2025_south PARTITION OF orders_2025
    FOR VALUES IN ('South', 'Southeast', 'Southwest');
```

### Range → Hash Sub-Partitioning

```sql
-- Large time-series partitioned by month then hashed for write parallelism
CREATE TABLE metrics (
    id         BIGSERIAL,
    device_id  INTEGER NOT NULL,
    metric     TEXT    NOT NULL,
    value      NUMERIC,
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (id, recorded_at, device_id)
) PARTITION BY RANGE (recorded_at);

CREATE TABLE metrics_2024_01 PARTITION OF metrics
    FOR VALUES FROM ('2024-01-01') TO ('2024-02-01')
    PARTITION BY HASH (device_id);

-- Sub-partitions for January 2024
CREATE TABLE metrics_2024_01_h0 PARTITION OF metrics_2024_01
    FOR VALUES WITH (MODULUS 4, REMAINDER 0);

CREATE TABLE metrics_2024_01_h1 PARTITION OF metrics_2024_01
    FOR VALUES WITH (MODULUS 4, REMAINDER 1);

CREATE TABLE metrics_2024_01_h2 PARTITION OF metrics_2024_01
    FOR VALUES WITH (MODULUS 4, REMAINDER 2);

CREATE TABLE metrics_2024_01_h3 PARTITION OF metrics_2024_01
    FOR VALUES WITH (MODULUS 4, REMAINDER 3);
```

### Query a Sub-Partitioned Table

```sql
-- Query the parent — pruning works at every level
SELECT COUNT(*), SUM(amount)
FROM orders_partitioned
WHERE created_at BETWEEN '2024-01-01' AND '2024-03-31'
  AND region = 'North';
-- Prunes: all 2025 partitions → 1 first-level partition scanned
-- Prunes: south/east/west → 1 second-level partition scanned
-- Result: only orders_2024_north scanned!
```

---

## 6. Partition Pruning

**Partition pruning** is PostgreSQL's ability to skip partitions that cannot contain rows matching the query's WHERE clause. It is the primary performance benefit of partitioning.

### Static Pruning (Plan Time)

```sql
-- Pruning happens during query planning when the WHERE value is a constant
EXPLAIN SELECT * FROM orders
WHERE created_at >= '2024-01-01'
  AND created_at <  '2024-04-01';

-- Plan output:
-- Append
--   -> Seq Scan on orders_2024_q1   ← ONLY this partition scanned
-- Partitions removed: 5             ← other partitions skipped
```

### Dynamic Pruning (Execution Time)

```sql
-- Pruning at runtime when the value comes from a parameter or function
EXPLAIN (ANALYZE) SELECT * FROM orders
WHERE created_at >= NOW() - INTERVAL '30 days';

-- Plan output:
-- Append
--   -> Seq Scan on orders_2024_q4  (actual rows=...)
-- Partitions removed during execution: 5

-- Enable/disable dynamic pruning (default: on)
SET enable_partition_pruning = on;
```

### What Enables Pruning

```sql
-- Pruning WORKS when:
WHERE created_at = '2024-03-15'                    -- equality
WHERE created_at > '2024-01-01'                    -- range
WHERE created_at BETWEEN '2024-01-01' AND '2024-06-30' -- BETWEEN
WHERE region IN ('North', 'South')                 -- list IN
WHERE region = 'North'                             -- equality

-- Pruning DOES NOT work when:
WHERE DATE_TRUNC('month', created_at) = '2024-03-01'  -- function on column
WHERE EXTRACT(YEAR FROM created_at) = 2024            -- function on column
WHERE created_at::DATE = '2024-03-15'                 -- cast on column

-- FIX: rewrite to use bare column comparison
WHERE created_at >= '2024-03-01' AND created_at < '2024-04-01'
WHERE created_at >= '2024-01-01' AND created_at < '2025-01-01'
```

### Verify Pruning in EXPLAIN

```sql
EXPLAIN (ANALYZE, VERBOSE)
SELECT COUNT(*)
FROM orders
WHERE created_at BETWEEN '2024-01-01' AND '2024-06-30';

-- Look for:
-- "Partitions removed: N"             → static pruning at plan time
-- "Subplans Removed: N"               → dynamic pruning at runtime

-- Count partitions in the plan:
SELECT COUNT(*) FROM (
    SELECT unnest(ARRAY(
        SELECT '2024_q1','2024_q2'  -- expected partitions in plan
    ))
) x;
```

---

## 7. Indexes on Partitioned Tables

### Creating Indexes

```sql
-- Global index on parent → automatically created on ALL existing + future partitions
CREATE INDEX idx_orders_customer ON orders(customer_id);
CREATE INDEX idx_orders_status   ON orders(status);
CREATE INDEX idx_orders_created  ON orders(created_at DESC);

-- Verify indexes were created on each partition
SELECT
    i.relname         AS index_name,
    t.relname         AS partition_name,
    pg_size_pretty(pg_relation_size(i.oid)) AS size
FROM pg_index ix
JOIN pg_class i ON i.oid = ix.indexrelid
JOIN pg_class t ON t.oid = ix.indrelid
JOIN pg_inherits ih ON ih.inhrelid = t.oid
WHERE ih.inhparent = 'orders'::regclass
ORDER BY partition_name, index_name;

-- Create index on a SINGLE partition only (if needed)
CREATE INDEX idx_orders_2024_q1_amount
ON orders_2024_q1(amount DESC);
-- This index exists only on orders_2024_q1, not other partitions
```

### Unique Indexes on Partitioned Tables

```sql
-- IMPORTANT: Unique indexes MUST include the partition key
-- This allows PostgreSQL to guarantee uniqueness within each partition

-- WRONG: will fail
CREATE UNIQUE INDEX ON orders(id);
-- ERROR: unique constraint on partitioned table must include all
-- partitioning columns

-- CORRECT: include the partition key (created_at)
CREATE UNIQUE INDEX ON orders(id, created_at);

-- For a natural unique key:
CREATE UNIQUE INDEX ON orders(customer_id, product_id, created_at);
```

### Index-Only Scans on Partitions

```sql
-- Covering index on partitioned table
CREATE INDEX idx_orders_covering
ON orders(status, created_at DESC)
INCLUDE (customer_id, amount);

-- This query uses Index-Only Scan on each relevant partition:
EXPLAIN SELECT customer_id, amount
FROM orders
WHERE status = 'pending'
  AND created_at > NOW() - INTERVAL '30 days';
```

### CONCURRENTLY on Partitioned Tables

```sql
-- You CANNOT use CONCURRENTLY directly on the parent
-- You must create indexes on each partition individually
CREATE INDEX CONCURRENTLY idx_orders_2024_q1_status
ON orders_2024_q1(status);

CREATE INDEX CONCURRENTLY idx_orders_2024_q2_status
ON orders_2024_q2(status);

-- Then create on parent (will validate existing partition indexes)
CREATE INDEX idx_orders_status ON orders(status);
```

---

## 8. Constraints & Foreign Keys

### Primary Keys

```sql
-- PK must include partition key column(s)
CREATE TABLE logs (
    id         BIGSERIAL,
    event_type TEXT   NOT NULL,
    payload    JSONB,
    logged_at  DATE   NOT NULL,
    PRIMARY KEY (id, logged_at)   -- logged_at is partition key → must be in PK
) PARTITION BY RANGE (logged_at);
```

### Foreign Keys TO a Partitioned Table

```sql
-- PostgreSQL 12+ supports FK references TO partitioned tables
-- The referenced columns must form a unique constraint including the partition key

CREATE TABLE order_items (
    id         BIGSERIAL PRIMARY KEY,
    order_id   BIGINT  NOT NULL,
    order_date TIMESTAMPTZ NOT NULL,
    product_id INTEGER NOT NULL,
    quantity   INTEGER,
    FOREIGN KEY (order_id, order_date) REFERENCES orders(id, created_at)
);
```

### Foreign Keys FROM a Partitioned Table

```sql
-- FK from a partitioned table to a regular table — fully supported
CREATE TABLE orders_fk_example (
    id          BIGSERIAL,
    customer_id INTEGER   NOT NULL REFERENCES customers(id),
    product_id  INTEGER   NOT NULL REFERENCES products(id),
    amount      NUMERIC,
    created_at  DATE      NOT NULL,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);
```

### Check Constraints

```sql
-- Check constraints defined on parent apply to all partitions
CREATE TABLE transactions (
    id        BIGSERIAL,
    amount    NUMERIC     NOT NULL CHECK (amount > 0),
    currency  TEXT        NOT NULL CHECK (currency IN ('INR','USD','EUR')),
    txn_date  DATE        NOT NULL,
    PRIMARY KEY (id, txn_date)
) PARTITION BY RANGE (txn_date);

-- Partition-specific check constraint (additional restriction)
CREATE TABLE transactions_india PARTITION OF transactions
    FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');

ALTER TABLE transactions_india
ADD CONSTRAINT chk_india_currency CHECK (currency = 'INR');
```

---

## 9. Partition Maintenance

### Adding New Partitions

```sql
-- Add future partition (always do this before data arrives!)
CREATE TABLE orders_2025_q1 PARTITION OF orders
    FOR VALUES FROM ('2025-01-01') TO ('2025-04-01');

-- Automate with a function
CREATE OR REPLACE FUNCTION create_monthly_partition(
    parent_table TEXT,
    for_month    DATE
) RETURNS void AS $$
DECLARE
    partition_name TEXT;
    start_date     DATE;
    end_date       DATE;
BEGIN
    start_date     := DATE_TRUNC('month', for_month);
    end_date       := start_date + INTERVAL '1 month';
    partition_name := parent_table || '_' || TO_CHAR(for_month, 'YYYY_MM');

    EXECUTE FORMAT(
        'CREATE TABLE IF NOT EXISTS %I PARTITION OF %I
         FOR VALUES FROM (%L) TO (%L)',
        partition_name, parent_table, start_date, end_date
    );

    RAISE NOTICE 'Created partition: %', partition_name;
END;
$$ LANGUAGE plpgsql;

-- Create next 12 months of partitions
SELECT create_monthly_partition('orders', DATE_TRUNC('month', NOW()) + (i || ' months')::INTERVAL)
FROM generate_series(0, 11) i;
```

### Dropping Old Partitions (Instant Archive)

```sql
-- DROP is instant — no DELETE needed for millions of rows!
DROP TABLE orders_2022;
-- Immediately frees all disk space for that partition

-- Check partition size before dropping
SELECT
    child.relname                                      AS partition,
    pg_size_pretty(pg_relation_size(child.oid))        AS data_size,
    pg_size_pretty(pg_total_relation_size(child.oid))  AS total_size,
    psut.n_live_tup                                    AS live_rows
FROM pg_inherits
JOIN pg_class child  ON child.oid  = pg_inherits.inhrelid
JOIN pg_class parent ON parent.oid = pg_inherits.inhparent
LEFT JOIN pg_stat_user_tables psut ON psut.relname = child.relname
WHERE parent.relname = 'orders'
ORDER BY child.relname;
```

### Detach & Attach Partitions

```sql
-- DETACH: remove partition from table (data preserved, partition becomes standalone table)
ALTER TABLE orders DETACH PARTITION orders_2022;
-- orders_2022 is now an independent table
-- Queries to orders no longer see its data

-- Archive the detached table
ALTER TABLE orders_2022 RENAME TO orders_2022_archive;

-- CONCURRENTLY detach (PG 14+) — does not block reads/writes!
ALTER TABLE orders DETACH PARTITION orders_2023 CONCURRENTLY;

-- ATTACH: bring an existing table in as a partition
ALTER TABLE orders ATTACH PARTITION orders_2025_q1
    FOR VALUES FROM ('2025-01-01') TO ('2025-04-01');
-- NOTE: ATTACH validates all existing rows match the partition bounds
-- This can take time on large tables — see below for fast attach

-- Fast ATTACH without full validation:
-- 1. Add a check constraint matching partition bounds BEFORE attaching
ALTER TABLE orders_2025_q1
ADD CONSTRAINT chk_2025_q1 CHECK (
    created_at >= '2025-01-01' AND created_at < '2025-04-01'
);
-- 2. ATTACH — PostgreSQL trusts the check constraint, skips full scan
ALTER TABLE orders ATTACH PARTITION orders_2025_q1
    FOR VALUES FROM ('2025-01-01') TO ('2025-04-01');
-- 3. Drop redundant constraint (now enforced by partition bounds)
ALTER TABLE orders_2025_q1 DROP CONSTRAINT chk_2025_q1;
```

### Moving Rows Between Partitions

```sql
-- You CANNOT UPDATE the partition key to a value in a different partition
-- (unless enable_partition_key_update = true — discouraged for large tables)

-- Default behavior (PG 11+): row is deleted from old partition and inserted into new
SET enable_partition_key_update = true;
UPDATE orders
SET created_at = '2025-01-15'   -- moves row from 2024 partition to 2025 partition
WHERE id = 42;

-- Better pattern: explicit DELETE + INSERT in a transaction
BEGIN;
WITH moved AS (
    DELETE FROM orders WHERE id = 42 RETURNING *
)
INSERT INTO orders SELECT * FROM moved
-- set the new value:
UPDATE orders_new_partition SET created_at = '2025-01-15' WHERE id = 42;
COMMIT;
```

---

## 10. Default Partition

A **default partition** catches all rows that do not match any other partition. Without it, an INSERT with an unmatched value raises an error.

```sql
-- Add default partition
CREATE TABLE orders_default PARTITION OF orders DEFAULT;

-- Test: insert a row with year 2030 (no partition for it)
INSERT INTO orders (customer_id, product_id, amount, created_at)
VALUES (1, 1, 50000, '2030-06-15');
-- → goes to orders_default, not an error

-- Check what's in the default partition
SELECT COUNT(*), MIN(created_at), MAX(created_at)
FROM orders_default;
```

### Creating a New Partition from Default

```sql
-- When you create a new partition whose range overlaps with default:
-- 1. PostgreSQL moves matching rows out of default INTO the new partition

-- Before creating 2026 partition, default might have 2026 data
CREATE TABLE orders_2026 PARTITION OF orders
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
-- PostgreSQL scans orders_default, moves 2026 rows to orders_2026
-- This operation scans the default partition (may be slow if large!)

-- Best practice: keep default partition small by creating partitions AHEAD of time
```

---

## 11. Partition Routing & Routing Rules

### How INSERT Routing Works

```sql
-- PostgreSQL evaluates the partition key value and routes automatically
INSERT INTO orders (customer_id, product_id, amount, region, created_at)
VALUES (42, 3, 25000, 'North', '2024-09-15 10:30:00');

-- PostgreSQL checks each partition's bounds:
-- orders_2024_q3: FROM '2024-07-01' TO '2024-10-01'  → MATCH!
-- Row inserted into orders_2024_q3

-- Verify with tableoid
SELECT tableoid::regclass AS stored_in, id, created_at
FROM orders
WHERE customer_id = 42
ORDER BY created_at DESC
LIMIT 5;
```

### COPY and Bulk Insert

```sql
-- COPY also respects partition routing
COPY orders (customer_id, product_id, amount, region, created_at)
FROM '/tmp/orders_data.csv'
WITH (FORMAT csv, HEADER true);
-- Each row routed to correct partition automatically

-- COPY directly INTO a specific partition (faster, skips routing check)
COPY orders_2024_q3 (customer_id, product_id, amount, region, created_at)
FROM '/tmp/orders_q3_2024.csv'
WITH (FORMAT csv, HEADER true);
-- Rows MUST match partition bounds or error!
```

### Trigger-Based Routing (Legacy — avoid with declarative partitioning)

```sql
-- Only needed for inheritance-based partitioning (pre-PG10)
-- With declarative partitioning (PG10+), routing is automatic
-- No triggers needed
```

---

## 12. Inheritance-Based Partitioning (Legacy)

Before PostgreSQL 10, partitioning required manual setup using table inheritance and trigger functions. This is now largely replaced by declarative partitioning but still found in older systems.

```sql
-- Legacy approach (PostgreSQL < 10 style)

-- Step 1: Parent table (has no data itself — just structure)
CREATE TABLE orders_legacy (
    id          BIGSERIAL,
    customer_id INTEGER,
    amount      NUMERIC,
    created_at  DATE NOT NULL
);

-- Step 2: Child tables inherit parent structure
CREATE TABLE orders_legacy_2024
(CHECK (created_at >= '2024-01-01' AND created_at < '2025-01-01'))
INHERITS (orders_legacy);

CREATE TABLE orders_legacy_2025
(CHECK (created_at >= '2025-01-01' AND created_at < '2026-01-01'))
INHERITS (orders_legacy);

-- Step 3: Trigger to route inserts manually
CREATE OR REPLACE FUNCTION orders_legacy_insert()
RETURNS TRIGGER AS $$
BEGIN
    IF    NEW.created_at >= '2024-01-01' AND NEW.created_at < '2025-01-01' THEN
        INSERT INTO orders_legacy_2024 VALUES (NEW.*);
    ELSIF NEW.created_at >= '2025-01-01' AND NEW.created_at < '2026-01-01' THEN
        INSERT INTO orders_legacy_2025 VALUES (NEW.*);
    ELSE
        RAISE EXCEPTION 'No partition for date: %', NEW.created_at;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER orders_legacy_route
BEFORE INSERT ON orders_legacy
FOR EACH ROW EXECUTE FUNCTION orders_legacy_insert();

-- Step 4: Indexes on each child
CREATE INDEX ON orders_legacy_2024(customer_id);
CREATE INDEX ON orders_legacy_2025(customer_id);
```

### Legacy vs Declarative Comparison

| Feature | Declarative (PG10+) | Inheritance (Legacy) |
|---------|--------------------|--------------------|
| Setup | Simple DDL | Manual triggers |
| INSERT routing | Automatic | Trigger function |
| Partition pruning | Built-in, efficient | Requires constraint_exclusion=on |
| Bulk operations | Native | Manual |
| Foreign keys | Supported | Limited |
| Global indexes | Supported | Not supported |
| Performance | Better | Slower |
| Recommended | YES | NO (migrate away) |

---

## 13. Partitioning vs Indexing

Knowing **when to partition vs when to index** is critical.

### When to Partition

```
✅ Table has tens of millions+ of rows
✅ Common queries filter on the partition key (date, region, status)
✅ You need to drop/archive old data regularly and instantly
✅ Bulk loads for specific time periods (load month-by-month)
✅ VACUUM is too slow on the full table
✅ You want to place different partitions on different tablespaces (storage tiers)
✅ Table scan time is unacceptably long even with good indexes
```

### When NOT to Partition

```
❌ Table has < 10 million rows (indexes are sufficient)
❌ Queries don't filter on the partition key (no pruning benefit)
❌ You need cross-partition unique constraints (very limited)
❌ You have many small partitions (planning overhead)
❌ Application uses complex joins across many partitions (hash join regression)
❌ The partition key is never used in WHERE clauses
```

### Partition Key Selection

```sql
-- GOOD partition keys:
created_at    -- almost all queries filter by time
region        -- queries always scoped to region
status        -- clear lifecycle (active vs archived)
year / month  -- natural data lifecycle

-- BAD partition keys:
user_id       -- queries rarely filter on single user, no lifecycle benefit
              -- use hash partitioning if sharding is the goal
amount        -- ranges are artificial, no natural lifecycle
id            -- sequential, no query selectivity benefit
```

### Decision Table

| Scenario | Recommendation |
|----------|---------------|
| 100M rows, 95% queries filter by date | Partition by RANGE(date) |
| 10M rows, queries filter by status | Partial index on status |
| 500M rows, need to drop data by year | Partition by RANGE(year) |
| 50M rows, queries filter by customer_id | Index on customer_id |
| 1B rows, even write distribution needed | Hash partition + indexes |
| Table with hot "recent" data | Partition by date + index on other columns |

---

## 14. Real-World Patterns

### Pattern 1: Time-Series Logging with Auto-Creation

```sql
-- Events table partitioned by month
CREATE TABLE events (
    id         BIGSERIAL,
    user_id    INTEGER      NOT NULL,
    event_type TEXT         NOT NULL,
    payload    JSONB,
    ip_address INET,
    logged_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id, logged_at)
) PARTITION BY RANGE (logged_at);

-- Function to auto-create next month's partition
CREATE OR REPLACE FUNCTION ensure_event_partition(target_date DATE)
RETURNS TEXT AS $$
DECLARE
    partition_name TEXT;
    start_date     DATE;
    end_date       DATE;
BEGIN
    start_date     := DATE_TRUNC('month', target_date);
    end_date       := start_date + INTERVAL '1 month';
    partition_name := 'events_' || TO_CHAR(start_date, 'YYYY_MM');

    IF NOT EXISTS (
        SELECT 1 FROM pg_class WHERE relname = partition_name
    ) THEN
        EXECUTE FORMAT(
            'CREATE TABLE %I PARTITION OF events
             FOR VALUES FROM (%L) TO (%L)',
            partition_name, start_date, end_date
        );

        -- Create indexes on new partition
        EXECUTE FORMAT(
            'CREATE INDEX %I ON %I (user_id, logged_at DESC)',
            'idx_' || partition_name || '_user', partition_name
        );

        EXECUTE FORMAT(
            'CREATE INDEX %I ON %I USING gin (payload)',
            'idx_' || partition_name || '_payload', partition_name
        );

        RETURN 'Created: ' || partition_name;
    END IF;

    RETURN 'Already exists: ' || partition_name;
END;
$$ LANGUAGE plpgsql;

-- Pre-create partitions for next 3 months
SELECT ensure_event_partition(
    (DATE_TRUNC('month', NOW()) + (i || ' months')::INTERVAL)::DATE
)
FROM generate_series(0, 2) i;

-- Schedule with pg_cron (if available):
-- SELECT cron.schedule('0 0 1 * *', 'SELECT ensure_event_partition(NOW()::DATE + 45)');
```

### Pattern 2: Data Retention Policy

```sql
-- Keep only last 12 months, auto-drop older partitions
CREATE OR REPLACE FUNCTION drop_old_partitions(
    parent_table TEXT,
    retain_months INT
) RETURNS void AS $$
DECLARE
    cutoff         DATE;
    partition_rec  RECORD;
    partition_date DATE;
BEGIN
    cutoff := DATE_TRUNC('month', NOW()) - (retain_months || ' months')::INTERVAL;

    FOR partition_rec IN
        SELECT child.relname AS partition_name
        FROM pg_inherits
        JOIN pg_class child  ON child.oid  = pg_inherits.inhrelid
        JOIN pg_class parent ON parent.oid = pg_inherits.inhparent
        WHERE parent.relname = parent_table
    LOOP
        -- Extract date from partition name like "events_2023_01"
        BEGIN
            partition_date := TO_DATE(
                SUBSTRING(partition_rec.partition_name FROM '\d{4}_\d{2}$'),
                'YYYY_MM'
            );
            IF partition_date < cutoff THEN
                EXECUTE 'DROP TABLE ' || quote_ident(partition_rec.partition_name);
                RAISE NOTICE 'Dropped: %', partition_rec.partition_name;
            END IF;
        EXCEPTION WHEN OTHERS THEN
            NULL; -- skip if name doesn't match pattern (e.g., default partition)
        END;
    END LOOP;
END;
$$ LANGUAGE plpgsql;

-- Drop all event partitions older than 12 months
SELECT drop_old_partitions('events', 12);
```

### Pattern 3: Multi-Tenant Partitioning

```sql
-- Partition by tenant for isolation and performance
CREATE TABLE tenant_data (
    id          BIGSERIAL,
    tenant_id   INTEGER    NOT NULL,
    entity_type TEXT       NOT NULL,
    data        JSONB,
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (id, tenant_id)
) PARTITION BY HASH (tenant_id);

-- Create partitions (more = better isolation per tenant)
DO $$
BEGIN
    FOR i IN 0..15 LOOP
        EXECUTE FORMAT(
            'CREATE TABLE tenant_data_%s PARTITION OF tenant_data
             FOR VALUES WITH (MODULUS 16, REMAINDER %s)',
            i, i
        );
    END LOOP;
END $$;

-- Each tenant's queries hit only 1 of 16 partitions
-- Vacuum/maintenance per partition is lightweight
```

### Pattern 4: Hot / Warm / Cold Storage Tiers

```sql
-- Create tablespaces for different storage tiers
CREATE TABLESPACE fast_ssd LOCATION '/mnt/nvme/pg_data';
CREATE TABLESPACE warm_hdd LOCATION '/mnt/hdd/pg_data';
CREATE TABLESPACE cold_obj LOCATION '/mnt/object_store/pg_data';

-- Partitioned table
CREATE TABLE sensor_readings (
    id          BIGSERIAL,
    sensor_id   INTEGER NOT NULL,
    value       NUMERIC,
    recorded_at DATE    NOT NULL,
    PRIMARY KEY (id, recorded_at)
) PARTITION BY RANGE (recorded_at)
TABLESPACE fast_ssd;

-- Hot: last 3 months on NVMe
CREATE TABLE sensor_readings_2024_q4 PARTITION OF sensor_readings
    FOR VALUES FROM ('2024-10-01') TO ('2025-01-01')
    TABLESPACE fast_ssd;

-- Warm: 3-12 months on HDD
CREATE TABLE sensor_readings_2024_q1 PARTITION OF sensor_readings
    FOR VALUES FROM ('2024-01-01') TO ('2024-04-01')
    TABLESPACE warm_hdd;

-- Cold: older than 1 year on object storage
CREATE TABLE sensor_readings_2023 PARTITION OF sensor_readings
    FOR VALUES FROM ('2023-01-01') TO ('2024-01-01')
    TABLESPACE cold_obj;

-- Move a partition to a different tablespace as data ages
ALTER TABLE sensor_readings_2024_q1 SET TABLESPACE cold_obj;
```

### Pattern 5: Read Replica Offload per Partition

```sql
-- Use logical replication to sync only recent partitions to read replicas
-- Steps:
-- 1. Create publication for hot partitions only
CREATE PUBLICATION hot_data FOR TABLE orders_2024_q3, orders_2024_q4;

-- 2. On read replica, subscribe
CREATE SUBSCRIPTION hot_data_sub
CONNECTION 'host=primary_host dbname=mydb'
PUBLICATION hot_data;
-- Read replica only syncs recent data — saves bandwidth and storage
```

---

## 15. Monitoring Partitions

```sql
-- ─── List all partitions and their sizes ──────────────────────────────────
SELECT
    parent.relname                                          AS parent_table,
    child.relname                                           AS partition,
    pg_size_pretty(pg_relation_size(child.oid))             AS data_size,
    pg_size_pretty(pg_indexes_size(child.oid))              AS index_size,
    pg_size_pretty(pg_total_relation_size(child.oid))       AS total_size,
    psut.n_live_tup                                         AS live_rows,
    psut.n_dead_tup                                         AS dead_rows,
    psut.last_vacuum,
    psut.last_autovacuum
FROM pg_inherits
JOIN pg_class child  ON child.oid  = pg_inherits.inhrelid
JOIN pg_class parent ON parent.oid = pg_inherits.inhparent
LEFT JOIN pg_stat_user_tables psut ON psut.relname = child.relname
WHERE parent.relname = 'orders'
ORDER BY child.relname;

-- ─── Check partition bounds ───────────────────────────────────────────────
SELECT
    parent.relname      AS parent_table,
    child.relname       AS partition,
    pg_get_expr(child.relpartbound, child.oid, true) AS partition_expr
FROM pg_class child
JOIN pg_inherits ON pg_inherits.inhrelid = child.oid
JOIN pg_class parent ON parent.oid = pg_inherits.inhparent
WHERE parent.relname = 'orders'
ORDER BY child.relname;

-- ─── Total size across all partitions ────────────────────────────────────
SELECT
    parent.relname                                          AS table_name,
    COUNT(child.oid)                                        AS partition_count,
    pg_size_pretty(SUM(pg_total_relation_size(child.oid)))  AS total_size
FROM pg_inherits
JOIN pg_class child  ON child.oid  = pg_inherits.inhrelid
JOIN pg_class parent ON parent.oid = pg_inherits.inhparent
GROUP BY parent.relname
ORDER BY SUM(pg_total_relation_size(child.oid)) DESC;

-- ─── Rows per partition ───────────────────────────────────────────────────
SELECT
    tableoid::regclass AS partition,
    COUNT(*)           AS row_count
FROM orders
GROUP BY tableoid
ORDER BY tableoid::regclass::TEXT;

-- ─── Check for missing future partitions ─────────────────────────────────
-- Find the latest partition end date
SELECT
    MAX(
        (regexp_match(
            pg_get_expr(child.relpartbound, child.oid),
            'TO \(''([^'']+)''\)'
        ))[1]::DATE
    ) AS latest_partition_end
FROM pg_class child
JOIN pg_inherits ON pg_inherits.inhrelid = child.oid
JOIN pg_class parent ON parent.oid = pg_inherits.inhparent
WHERE parent.relname = 'orders'
  AND child.relname != 'orders_default';

-- ─── Identify partition bloat ─────────────────────────────────────────────
SELECT
    child.relname,
    psut.n_dead_tup,
    psut.n_live_tup,
    ROUND(100.0 * psut.n_dead_tup
          / NULLIF(psut.n_live_tup + psut.n_dead_tup, 0), 1) AS bloat_pct,
    psut.last_autovacuum
FROM pg_inherits
JOIN pg_class child  ON child.oid  = pg_inherits.inhrelid
JOIN pg_class parent ON parent.oid = pg_inherits.inhparent
LEFT JOIN pg_stat_user_tables psut ON psut.relname = child.relname
WHERE parent.relname = 'orders'
  AND psut.n_dead_tup > 1000
ORDER BY bloat_pct DESC;

-- ─── Vacuum all partitions of a table ────────────────────────────────────
DO $$
DECLARE r RECORD;
BEGIN
    FOR r IN
        SELECT child.relname
        FROM pg_inherits
        JOIN pg_class child  ON child.oid  = pg_inherits.inhrelid
        JOIN pg_class parent ON parent.oid = pg_inherits.inhparent
        WHERE parent.relname = 'orders'
    LOOP
        EXECUTE 'VACUUM ANALYZE ' || quote_ident(r.relname);
        RAISE NOTICE 'Vacuumed: %', r.relname;
    END LOOP;
END $$;
```

---

## 16. Performance Tuning

### Partition-Level Configuration

```sql
-- Autovacuum settings per partition (override for hot partitions)
ALTER TABLE orders_2024_q4 SET (
    autovacuum_vacuum_scale_factor   = 0.01,   -- vacuum when 1% dead
    autovacuum_analyze_scale_factor  = 0.005,  -- analyze when 0.5% new
    autovacuum_vacuum_cost_limit     = 800      -- more aggressive vacuum
);

-- Fillfactor: leave room for HOT updates on frequently updated partitions
ALTER TABLE order_pipeline_active SET (fillfactor = 80);
-- 20% free space per page → UPDATE stays on same page (no index update)
```

### Partition-Wise Joins & Aggregates

```sql
-- Enable partition-wise join and aggregation (PG 11+)
SET enable_partitionwise_join      = on;   -- default: off
SET enable_partitionwise_aggregate = on;   -- default: off

-- With these on, PostgreSQL can join/aggregate matching partitions in parallel
EXPLAIN (ANALYZE, VERBOSE)
SELECT o.region, COUNT(*), SUM(o.amount)
FROM orders o
JOIN orders_copy oc ON oc.id = o.id AND oc.created_at = o.created_at
GROUP BY o.region;
-- With partition-wise aggregate: each partition aggregated independently
-- then results merged — much better parallelism
```

### Parallel Partition Scans

```sql
-- Parallel query across partitions
SET max_parallel_workers_per_gather = 4;
SET parallel_setup_cost = 100;
SET parallel_tuple_cost = 0.001;

EXPLAIN SELECT COUNT(*), SUM(amount)
FROM orders
WHERE created_at >= '2024-01-01';
-- May show Gather node with multiple workers — one per partition
```

### Limiting Partition Count

```sql
-- Too many partitions → planning overhead, not just execution overhead
-- Rule of thumb:
--   < 100  partitions: fine, no concern
--   100-1000 partitions: monitor planning time
--   > 1000 partitions: carefully measure, may need different strategy

-- Check planning time impact
SET log_min_duration_statement = 0;
SELECT COUNT(*) FROM orders WHERE created_at = NOW();
-- Check log for "planning time: Xms"
-- If planning time > execution time → too many partitions

-- Increase planner memory for large partition counts
SET from_collapse_limit = 20;
SET join_collapse_limit  = 20;
```

### BRIN Indexes on Partitions

```sql
-- BRIN is extremely effective on time-ordered partitions
-- (already sorted by time within each monthly partition)
CREATE INDEX idx_orders_2024_q1_brin
ON orders_2024_q1 USING brin(created_at)
WITH (pages_per_range = 32);

-- BRIN on partitions: tiny index, fast range queries within partition
-- B-tree on parent: handles cross-partition pruning
```

---

## 17. Quick Reference Cheat Sheet

```
╔══════════════════════════╦═══════════════════════════════════════════════════╗
║ TOPIC                    ║ KEY SYNTAX / NOTES                                ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Partition Types          ║ RANGE  — continuous ranges (date, numeric)        ║
║                          ║ LIST   — explicit value sets (region, status)     ║
║                          ║ HASH   — even distribution (user_id, uuid)       ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Create Parent            ║ CREATE TABLE t (...) PARTITION BY RANGE/LIST/HASH ║
║                          ║ Partition key column(s) MUST be in PK             ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Create Partition         ║ CREATE TABLE t_part PARTITION OF t                ║
║ Range                    ║   FOR VALUES FROM ('2024-01-01') TO ('2025-01-01')║
║ List                     ║   FOR VALUES IN ('North', 'South')                ║
║ Hash                     ║   FOR VALUES WITH (MODULUS 4, REMAINDER 0)        ║
║ Default                  ║   DEFAULT                                         ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Sub-Partitioning         ║ Add PARTITION BY ... to CREATE TABLE t_part       ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Partition Pruning        ║ Automatic when WHERE uses bare partition key       ║
║                          ║ Broken by functions: DATE_TRUNC(col), col::text   ║
║                          ║ Verify with EXPLAIN — look for "Partitions removed"║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Indexes                  ║ CREATE INDEX ON parent → all partitions           ║
║                          ║ Unique indexes MUST include partition key         ║
║                          ║ CONCURRENTLY must target individual partitions    ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Add Partition            ║ CREATE TABLE t_new PARTITION OF t FOR VALUES ...  ║
║ Drop Partition           ║ DROP TABLE t_old            (instant!)            ║
║ Detach Partition         ║ ALTER TABLE t DETACH PARTITION t_old              ║
║                          ║ ALTER TABLE t DETACH PARTITION t_old CONCURRENTLY ║
║ Attach Partition         ║ ALTER TABLE t ATTACH PARTITION t_new              ║
║                          ║   FOR VALUES FROM (...) TO (...)                  ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Fast Attach              ║ Add CHECK constraint first → ATTACH skips scan    ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ List Partitions          ║ SELECT FROM pg_inherits JOIN pg_class ...         ║
║ Check Bounds             ║ pg_get_expr(relpartbound, oid, true)              ║
║ Partition Size           ║ pg_total_relation_size(child.oid)                 ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Performance              ║ enable_partitionwise_join = on                    ║
║                          ║ enable_partitionwise_aggregate = on               ║
║                          ║ Keep partition count < 100 to avoid plan overhead ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ When to Partition        ║ Table > 10M rows + queries filter on partition key ║
║ When NOT to Partition    ║ Table < 10M rows — use indexes instead            ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ MINVALUE / MAXVALUE      ║ Unbounded range ends                              ║
║                          ║ FROM (MINVALUE) TO (1000) — anything below 1000  ║
║                          ║ FROM (1000) TO (MAXVALUE) — anything above 1000  ║
╚══════════════════════════╩═══════════════════════════════════════════════════╝
```

---

## Further Reading

- [PostgreSQL Docs — Table Partitioning](https://www.postgresql.org/docs/current/ddl-partitioning.html)
- [PostgreSQL Docs — ATTACH PARTITION](https://www.postgresql.org/docs/current/sql-altertable.html)
- [PostgreSQL Docs — Partition Pruning](https://www.postgresql.org/docs/current/ddl-partitioning.html#DDL-PARTITION-PRUNING)
- [PostgreSQL Docs — Partitioning and Constraint Exclusion](https://www.postgresql.org/docs/current/ddl-partitioning.html#DDL-PARTITIONING-CONSTRAINT-EXCLUSION)
- [pg_partman Extension](https://github.com/pgpartman/pg_partman) — Automates partition creation and maintenance
- [Timescale DB](https://www.timescale.com) — Time-series partitioning at scale

---

*Generated with love for PostgreSQL engineers.*
