# PostgreSQL — `apg_plan_mgmt` Query Plan Management Complete Reference

> A deep-dive guide covering Amazon Aurora PostgreSQL's `apg_plan_mgmt` extension — query plan capture, approval, enforcement, evolution, maintenance, and real-world plan stability patterns.

---

## Table of Contents

1.  [What is apg_plan_mgmt?](#1-what-is-apg_plan_mgmt)
2.  [How It Works — Architecture](#2-how-it-works--architecture)
3.  [Installation & Setup](#3-installation--setup)
4.  [Plan Capture Modes](#4-plan-capture-modes)
5.  [The Plan Managed Table](#5-the-plan-managed-table)
6.  [Plan Status Lifecycle](#6-plan-status-lifecycle)
7.  [Approving & Rejecting Plans](#7-approving--rejecting-plans)
8.  [Plan Use Modes (Enforcement)](#8-plan-use-modes-enforcement)
9.  [Plan Evolution — Automatic Improvement](#9-plan-evolution--automatic-improvement)
10. [Validating Plans](#10-validating-plans)
11. [Fixing Plans — force_custom_plan & Hints](#11-fixing-plans--force_custom_plan--hints)
12. [Monitoring & Reporting](#12-monitoring--reporting)
13. [Plan Maintenance Functions](#13-plan-maintenance-functions)
14. [Exporting & Importing Plans](#14-exporting--importing-plans)
15. [Real-World Patterns](#15-real-world-patterns)
16. [Troubleshooting](#16-troubleshooting)
17. [Quick Reference Cheat Sheet](#17-quick-reference-cheat-sheet)

---

## Sample Schema Used in All Examples

```sql
CREATE TABLE customers (
    id          SERIAL PRIMARY KEY,
    name        TEXT        NOT NULL,
    email       TEXT        UNIQUE,
    country     TEXT,
    segment     TEXT,
    created_at  DATE        DEFAULT CURRENT_DATE
);

CREATE TABLE orders (
    id          BIGSERIAL   PRIMARY KEY,
    customer_id INTEGER     REFERENCES customers(id),
    product     TEXT,
    amount      NUMERIC(12,2),
    status      TEXT        DEFAULT 'pending',
    region      TEXT,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE order_items (
    id          BIGSERIAL   PRIMARY KEY,
    order_id    BIGINT      REFERENCES orders(id),
    product_id  INTEGER,
    qty         INTEGER,
    unit_price  NUMERIC(10,2)
);

-- Realistic data volumes
INSERT INTO customers (name, email, country, segment)
SELECT 'Customer '||i, 'c'||i||'@ex.com',
       (ARRAY['IN','US','UK','DE'])[ceil(random()*4)::INT],
       (ARRAY['gold','silver','bronze'])[ceil(random()*3)::INT]
FROM generate_series(1,200000) i;

INSERT INTO orders (customer_id, product, amount, status, region, created_at)
SELECT (random()*199999+1)::INT,
       (ARRAY['Laptop','Phone','Monitor','Tablet'])[ceil(random()*4)::INT],
       (random()*100000)::NUMERIC(12,2),
       (ARRAY['pending','shipped','delivered','cancelled'])[ceil(random()*4)::INT],
       (ARRAY['North','South','East','West'])[ceil(random()*4)::INT],
       NOW() - (random()*730 || ' days')::INTERVAL
FROM generate_series(1,2000000) i;

ANALYZE customers;
ANALYZE orders;
```

---

## 1. What is apg_plan_mgmt?

`apg_plan_mgmt` is Amazon Aurora PostgreSQL's **Query Plan Management (QPM)** extension. It gives DBAs and developers explicit control over which query execution plans PostgreSQL uses, preventing unexpected plan regressions caused by:

```
Plan regression sources:
  ┌──────────────────────────────────────────────────────────┐
  │ • Statistics changes after ANALYZE or autovacuum         │
  │ • Data distribution changes (new rows, deletes)          │
  │ • PostgreSQL version upgrades                            │
  │ • Configuration parameter changes (work_mem, etc.)       │
  │ • Schema changes (new indexes, dropped indexes)          │
  │ • Query parameter value changes (parameter sniffing)     │
  │ • Planner bug fixes between minor versions               │
  └──────────────────────────────────────────────────────────┘
```

### Core Capabilities

```
┌──────────────────────────────────────────────────────────────────┐
│                   apg_plan_mgmt Capabilities                     │
├────────────────────────┬─────────────────────────────────────────┤
│  CAPTURE               │ Record plans as queries run             │
│  APPROVE               │ Mark plans as trusted/preferred         │
│  ENFORCE               │ Force only approved plans to run        │
│  EVOLVE                │ Automatically detect better plans       │
│  VALIDATE              │ Check plans are still executable        │
│  EXPORT / IMPORT       │ Move plans across environments          │
└────────────────────────┴─────────────────────────────────────────┘
```

### When to Use apg_plan_mgmt

```
✅ After a PostgreSQL major version upgrade
✅ Before and after a large data load that changes statistics
✅ Critical OLTP queries that must never regress
✅ Queries with parameter sniffing problems (generic plans differ by param)
✅ After adding or dropping an index (want to test before committing)
✅ Regulated environments needing plan auditability
✅ Production queries that ran well — capture & freeze them
✅ Preventing the planner from choosing a known-bad plan
```

### apg_plan_mgmt vs PostgreSQL Standard

| Feature | Standard PostgreSQL | apg_plan_mgmt |
|---------|--------------------|-|
| Plan control | None (planner decides) | Full capture + enforce |
| Plan regression prevention | None | Yes (enforce approved) |
| Automatic plan evolution | None | Yes (evolve) |
| Plan portability | None | Export/import to S3 |
| Plan audit trail | None | Full history in table |
| pg_hint_plan | External extension | Integrated via hints |
| Availability | All PostgreSQL | Aurora PostgreSQL only |

---

## 2. How It Works — Architecture

### Data Flow

```
                        ┌──────────────────────────────┐
                        │     Application Query        │
                        └──────────────┬───────────────┘
                                       │
                                       ▼
                        ┌──────────────────────────────┐
                        │   PostgreSQL Planner          │
                        │   Generates candidate plan    │
                        └──────────────┬───────────────┘
                                       │
                                       ▼
              ┌──────────────────────────────────────────────┐
              │            apg_plan_mgmt Hook                │
              │                                              │
              │  1. Compute SQL hash (query fingerprint)     │
              │  2. Look up apg_plan_mgmt.plans table        │
              │  3. Apply use_plan_baselines setting         │
              │                                              │
              │  If CAPTURE mode:                            │
              │    → Save plan to plans table (unapproved)   │
              │                                              │
              │  If ENFORCE mode:                            │
              │    → Only allow "approved" plans             │
              │    → Reject unapproved plans (use fallback)  │
              └──────────────┬───────────────────────────────┘
                             │
                             ▼
              ┌──────────────────────────────┐
              │        Plan Execution        │
              └──────────────────────────────┘
```

### Key Components

```sql
-- The extension stores everything in:
apg_plan_mgmt.plans          -- the managed plan table (central store)
apg_plan_mgmt.dba_plans      -- view with extra metadata for DBAs

-- Key GUC (configuration) parameters:
apg_plan_mgmt.capture_plan_baselines   -- on/off/auto
apg_plan_mgmt.use_plan_baselines       -- on/off
apg_plan_mgmt.plan_retention_period    -- how long to keep unused plans
apg_plan_mgmt.max_plans_per_query      -- cap on plans per SQL hash

-- Key functions:
apg_plan_mgmt.validate_plans()         -- check all plans are still valid
apg_plan_mgmt.evolve_plan_baselines()  -- detect and approve faster plans
apg_plan_mgmt.get_explain_plan()       -- get plan text for a managed plan
apg_plan_mgmt.export_plans_to_s3()     -- export plans to S3
apg_plan_mgmt.import_plans_from_s3()   -- import plans from S3
apg_plan_mgmt.delete_plan()            -- remove a plan
apg_plan_mgmt.approve_plan()           -- approve a plan (programmatic)
apg_plan_mgmt.reject_plan()            -- reject a plan
```

---

## 3. Installation & Setup

### Prerequisites

```
apg_plan_mgmt is available on:
  - Amazon Aurora PostgreSQL (all supported versions)
  - NOT available on standard open-source PostgreSQL
  - Aurora PostgreSQL 10.5+, 11.4+, 12.x, 13.x, 14.x, 15.x, 16.x
```

### Enable the Extension

```sql
-- Step 1: Add to shared_preload_libraries in parameter group
-- (Aurora Parameter Group — requires instance restart)
-- shared_preload_libraries = 'apg_plan_mgmt'

-- Step 2: Create the extension (run as rds_superuser)
CREATE EXTENSION apg_plan_mgmt;

-- Verify installation
SELECT extname, extversion FROM pg_extension WHERE extname = 'apg_plan_mgmt';

-- View extension schema
\dn apg_plan_mgmt
\dt apg_plan_mgmt.*
\df apg_plan_mgmt.*
```

### Required Permissions

```sql
-- Grant plan management to specific role
GRANT apg_plan_mgmt TO dba_role;

-- The rds_superuser role has full access by default
-- Application users need no special grants — plans are enforced transparently

-- Check who has access
SELECT rolname FROM pg_roles
WHERE pg_has_role(rolname, 'apg_plan_mgmt', 'member');
```

### Initial Configuration

```sql
-- View all apg_plan_mgmt parameters
SELECT name, setting, unit, short_desc
FROM pg_settings
WHERE name LIKE 'apg_plan_mgmt%'
ORDER BY name;

-- Key settings and their defaults:
SHOW apg_plan_mgmt.capture_plan_baselines;    -- off
SHOW apg_plan_mgmt.use_plan_baselines;        -- off
SHOW apg_plan_mgmt.max_plans_per_query;       -- 10000
SHOW apg_plan_mgmt.plan_retention_period;     -- 32 (days)

-- Set globally (session level for testing, parameter group for permanent)
SET apg_plan_mgmt.capture_plan_baselines = off;
SET apg_plan_mgmt.use_plan_baselines     = off;

-- In Aurora Parameter Group (persistent):
-- apg_plan_mgmt.capture_plan_baselines = off   (default)
-- apg_plan_mgmt.use_plan_baselines     = off   (default)
```

---

## 4. Plan Capture Modes

### capture_plan_baselines Settings

| Value | Behavior |
|-------|----------|
| `off` | No capture (default) |
| `automatic` | Capture plans for all queries automatically |
| `manual` | Capture only when explicitly enabled per session |

### Mode 1: Off (Default — No Capture)

```sql
-- Default: no plan management active
SET apg_plan_mgmt.capture_plan_baselines = off;
SET apg_plan_mgmt.use_plan_baselines     = off;

-- Queries run normally with no plan management overhead
SELECT * FROM orders WHERE customer_id = 42;
-- → Pure planner decision, nothing stored
```

### Mode 2: Automatic Capture (Workload Baseline)

```sql
-- Capture ALL query plans automatically as they execute
-- Use this to build a baseline for an existing workload

-- Enable at session level (testing)
SET apg_plan_mgmt.capture_plan_baselines = automatic;

-- Enable at instance level (Aurora Parameter Group):
-- apg_plan_mgmt.capture_plan_baselines = automatic

-- Now run your workload — every distinct plan is saved
SELECT * FROM orders WHERE customer_id = 42;
-- Plan is now stored in apg_plan_mgmt.plans with status = 'Unapproved'

SELECT COUNT(*), SUM(amount) FROM orders WHERE status = 'pending';
-- Another plan saved

-- Check what was captured
SELECT sql_hash, plan_hash, status, enabled, query_text_hash
FROM apg_plan_mgmt.dba_plans
ORDER BY last_used DESC;

-- IMPORTANT: After capturing, turn off capture to prevent unbounded growth
SET apg_plan_mgmt.capture_plan_baselines = off;
```

### Mode 3: Manual Capture (Targeted)

```sql
-- Capture plans ONLY for specific queries in a controlled session

-- Step 1: Enable capture for this session only
SET apg_plan_mgmt.capture_plan_baselines = manual;

-- Step 2: Run only the queries you want to capture
-- Run each query multiple times with different parameter values
-- to capture multiple plans

EXPLAIN SELECT * FROM orders WHERE customer_id = 1;      -- captures plan
EXPLAIN SELECT * FROM orders WHERE customer_id = 500000; -- may capture different plan
EXPLAIN SELECT * FROM orders WHERE status = 'pending';
EXPLAIN SELECT o.*, c.name FROM orders o
        JOIN customers c ON c.id = o.customer_id
        WHERE o.amount > 50000 AND o.status = 'shipped';

-- Step 3: Turn off capture
SET apg_plan_mgmt.capture_plan_baselines = off;

-- Step 4: Review what was captured
SELECT
    sql_hash,
    plan_hash,
    status,
    enabled,
    estimated_startup_cost,
    estimated_total_cost,
    planning_time_ms
FROM apg_plan_mgmt.dba_plans
ORDER BY last_used DESC;
```

### Capture with EXPLAIN

```sql
-- You can trigger capture using EXPLAIN (no rows returned to client)
-- Useful for capturing plans without actually running the full query

SET apg_plan_mgmt.capture_plan_baselines = manual;

-- Capture plan without executing (planning only)
EXPLAIN
SELECT o.id, o.amount, c.name, c.segment
FROM orders o
JOIN customers c ON c.id = o.customer_id
WHERE o.status = 'delivered'
  AND c.country = 'IN'
  AND o.amount > 10000
ORDER BY o.amount DESC;

SET apg_plan_mgmt.capture_plan_baselines = off;

-- Verify capture
SELECT sql_hash, plan_hash, status, estimated_total_cost
FROM apg_plan_mgmt.dba_plans
ORDER BY created_at DESC LIMIT 5;
```

---

## 5. The Plan Managed Table

The central table `apg_plan_mgmt.plans` (exposed via `apg_plan_mgmt.dba_plans`) stores all managed plans.

### Schema of dba_plans View

```sql
-- View full schema
\d apg_plan_mgmt.dba_plans

-- Key columns:
SELECT
    sql_hash,           -- hash of normalized SQL text (identifies the query)
    plan_hash,          -- hash of the execution plan (identifies this specific plan)
    status,             -- 'Approved' | 'Unapproved' | 'Rejected' | 'Preferred'
    enabled,            -- true = plan is eligible to be used
    sql_text,           -- the original SQL text
    plan_outline,       -- the plan structure (JSON)
    estimated_startup_cost,
    estimated_total_cost,
    total_plan_time,    -- cumulative execution time
    calls,              -- number of times this plan was used
    avg_plan_time_ms,   -- average execution time in ms
    planning_time_ms,   -- time to plan this query
    last_used,          -- last time this plan was chosen
    created_at,         -- when this plan was first captured
    last_validated,     -- last time validate_plans() checked this
    origin,             -- 'Automatic' | 'Manual' | 'Evolve'
    environment_variables, -- GUC settings in effect when captured
    notes               -- DBA notes / comments
FROM apg_plan_mgmt.dba_plans;
```

### Querying the Plans Table

```sql
-- All plans for a specific query (by sql_hash)
SELECT
    sql_hash,
    plan_hash,
    status,
    enabled,
    ROUND(estimated_total_cost::NUMERIC, 2)    AS est_cost,
    calls,
    ROUND(avg_plan_time_ms::NUMERIC, 2)        AS avg_ms,
    origin,
    last_used
FROM apg_plan_mgmt.dba_plans
WHERE sql_hash = '<your_sql_hash>'
ORDER BY estimated_total_cost;

-- All approved plans
SELECT sql_hash, plan_hash, status, calls, avg_plan_time_ms
FROM apg_plan_mgmt.dba_plans
WHERE status = 'Approved'
ORDER BY calls DESC;

-- Unapproved plans (need DBA review)
SELECT sql_hash, plan_hash, status, estimated_total_cost, last_used
FROM apg_plan_mgmt.dba_plans
WHERE status = 'Unapproved'
ORDER BY last_used DESC;

-- Plans that have never been used
SELECT sql_hash, plan_hash, status, created_at
FROM apg_plan_mgmt.dba_plans
WHERE calls = 0
  AND created_at < NOW() - INTERVAL '7 days'
ORDER BY created_at;

-- Summary by status
SELECT status, COUNT(*) AS plan_count
FROM apg_plan_mgmt.dba_plans
GROUP BY status
ORDER BY plan_count DESC;

-- Plans with high execution time
SELECT
    sql_hash, plan_hash, status,
    calls,
    ROUND(avg_plan_time_ms::NUMERIC, 2) AS avg_ms,
    ROUND(total_plan_time::NUMERIC, 0)  AS total_ms,
    last_used
FROM apg_plan_mgmt.dba_plans
WHERE avg_plan_time_ms > 1000        -- slower than 1 second
ORDER BY total_plan_time DESC
LIMIT 20;
```

---

## 6. Plan Status Lifecycle

Every plan moves through a set of statuses:

```
Capture
  │
  ▼
┌────────────┐     DBA approves      ┌────────────┐
│ Unapproved │ ──────────────────► │  Approved  │
└────────────┘                      └────────────┘
      │                                   │
      │ DBA rejects                       │ Mark as preferred
      ▼                                   ▼
┌────────────┐                      ┌────────────┐
│  Rejected  │                      │  Preferred │
└────────────┘                      └────────────┘
      │
      │ DBA re-enables
      ▼
(can be re-approved)
```

### Status Definitions

| Status | Meaning | Used When enforce=on? |
|--------|---------|----------------------|
| `Unapproved` | Captured, not yet reviewed | NO (rejected as baseline) |
| `Approved` | DBA verified this plan | YES |
| `Preferred` | Best plan — tried first | YES (tried before others) |
| `Rejected` | DBA flagged as bad | NEVER |

### enabled Flag

```sql
-- enabled=true:  plan is eligible to be enforced
-- enabled=false: plan exists but will never be used (soft disable)

-- A plan must be BOTH status='Approved' AND enabled=true to be enforced

-- Disable without deleting
UPDATE apg_plan_mgmt.plans
SET enabled = false
WHERE plan_hash = '<hash>';

-- Re-enable
UPDATE apg_plan_mgmt.plans
SET enabled = true
WHERE plan_hash = '<hash>';
```

---

## 7. Approving & Rejecting Plans

### Approve a Specific Plan

```sql
-- Approve by plan_hash
SELECT apg_plan_mgmt.approve_plan('<plan_hash>');

-- Approve by sql_hash + plan_hash (more precise)
SELECT apg_plan_mgmt.approve_plan(
    sql_hash  := '<sql_hash>',
    plan_hash := '<plan_hash>'
);

-- Approve all unapproved plans for a specific query
SELECT apg_plan_mgmt.approve_plan(plan_hash)
FROM apg_plan_mgmt.dba_plans
WHERE sql_hash = '<sql_hash>'
  AND status = 'Unapproved';

-- Bulk approve all unapproved plans (use carefully!)
-- Only do this if you trust all captured plans
SELECT apg_plan_mgmt.approve_plan(plan_hash)
FROM apg_plan_mgmt.dba_plans
WHERE status = 'Unapproved';
```

### Reject a Plan

```sql
-- Reject a specific plan (prevents it from ever being used)
SELECT apg_plan_mgmt.reject_plan('<plan_hash>');

-- Reject with a reason note
UPDATE apg_plan_mgmt.plans
SET status = 'Rejected',
    notes  = 'Slow nested loop on 2M row table — rejected 2024-03-15 by DBA'
WHERE plan_hash = '<plan_hash>';
```

### Set a Plan as Preferred

```sql
-- Mark the best known plan as Preferred
-- Preferred plans are tried before Approved plans
UPDATE apg_plan_mgmt.plans
SET status = 'Preferred'
WHERE plan_hash = '<plan_hash>'
  AND sql_hash  = '<sql_hash>';

-- Only one plan per query should be Preferred
-- If multiple are Preferred, planner picks lowest cost among them
```

### Approve with SQL Text Match

```sql
-- Find and approve plans for a query you know
-- Step 1: Find the sql_hash for your query
SELECT DISTINCT sql_hash, LEFT(sql_text, 80) AS query_preview
FROM apg_plan_mgmt.dba_plans
WHERE sql_text ILIKE '%FROM orders%WHERE status%'
ORDER BY sql_hash;

-- Step 2: View all plans for that query
SELECT plan_hash, status, estimated_total_cost, calls, avg_plan_time_ms
FROM apg_plan_mgmt.dba_plans
WHERE sql_hash = '<found_hash>'
ORDER BY estimated_total_cost;

-- Step 3: Approve the best plan
SELECT apg_plan_mgmt.approve_plan('<plan_hash_of_best_plan>');

-- Step 4: Reject known bad plans
SELECT apg_plan_mgmt.reject_plan('<plan_hash_of_bad_plan>');
```

---

## 8. Plan Use Modes (Enforcement)

### use_plan_baselines Setting

```sql
-- OFF: apg_plan_mgmt is purely observational
SET apg_plan_mgmt.use_plan_baselines = off;
-- Queries run freely; captured plans recorded but not enforced

-- ON: only approved plans are allowed
SET apg_plan_mgmt.use_plan_baselines = on;
-- If planner generates an unapproved plan → falls back to approved baseline
-- If no approved plan exists → query runs with planner's choice

-- Set permanently in Aurora Parameter Group:
-- apg_plan_mgmt.use_plan_baselines = on
```

### How Enforcement Works

```
Query submitted
        │
        ▼
Planner generates plan P
        │
        ▼
apg_plan_mgmt checks plans table
        │
        ├── Is there an approved plan for this sql_hash?
        │       │
        │       ├── YES: Is plan P in the approved set?
        │       │           │
        │       │           ├── YES → Execute plan P  ✅
        │       │           │
        │       │           └── NO  → Reject P, use approved plan  🔄
        │       │
        │       └── NO: No baseline exists → Execute plan P freely  ✅
        │
        └── Is plan P rejected?
                    │
                    └── YES → Use approved plan or re-plan  🔄
```

### Enforcement Scenarios

```sql
-- SCENARIO 1: Query has an approved plan → enforcement active

-- After approval:
SET apg_plan_mgmt.use_plan_baselines = on;
-- Query now always uses the approved plan

EXPLAIN SELECT * FROM orders WHERE status = 'pending';
-- Plan: Index Scan using idx_orders_status (approved)
-- NOT a Seq Scan even if planner would prefer it today


-- SCENARIO 2: Query has no approved plan → no enforcement

SET apg_plan_mgmt.use_plan_baselines = on;
-- New query never seen before:
SELECT * FROM order_items WHERE product_id = 99;
-- No plan in baseline → planner decides freely


-- SCENARIO 3: Planner wants a Seq Scan, approved plan is Index Scan

-- Planner generates: Seq Scan on orders
-- Approved plan is:  Index Scan using idx_orders_status_amount
-- Result: apg_plan_mgmt forces the Index Scan
-- The Seq Scan is stored as 'Unapproved' for DBA review
```

---

## 9. Plan Evolution — Automatic Improvement

**Plan evolution** automatically detects when a new (unapproved) plan is significantly faster than the current approved plan, and optionally promotes it.

### evolve_plan_baselines() Function

```sql
-- Function signature
SELECT apg_plan_mgmt.evolve_plan_baselines(
    sql_hash          TEXT,            -- specific query (or NULL for all)
    plan_hash         TEXT,            -- specific plan  (or NULL for all)
    min_improve_factor NUMERIC,        -- minimum speedup factor to approve (e.g. 1.1 = 10% faster)
    action            TEXT             -- 'approve', 'reject', or 'nothing'
);
```

### Running Plan Evolution

```sql
-- Evolve ALL queries: approve any plan that is 10% faster
SELECT apg_plan_mgmt.evolve_plan_baselines(
    sql_hash           := NULL,    -- all queries
    plan_hash          := NULL,    -- all plans
    min_improve_factor := 1.10,    -- must be at least 10% faster
    action             := 'approve'
);

-- Evolve a specific query only
SELECT apg_plan_mgmt.evolve_plan_baselines(
    sql_hash           := '<your_sql_hash>',
    plan_hash          := NULL,
    min_improve_factor := 1.05,    -- 5% faster is enough
    action             := 'approve'
);

-- Dry run: just report, don't approve
SELECT apg_plan_mgmt.evolve_plan_baselines(
    sql_hash           := NULL,
    plan_hash          := NULL,
    min_improve_factor := 1.10,
    action             := 'nothing'   -- report only, no changes
);
```

### Evolution Output

```sql
-- evolve_plan_baselines returns a result set showing:
SELECT
    sql_hash,
    plan_hash,
    status,                      -- 'Approved' | 'Unchanged'
    baseline_plan_hash,          -- the plan it was compared to
    speedup_factor,              -- how much faster (2.0 = 2× faster)
    action_taken                 -- what was done
FROM apg_plan_mgmt.evolve_plan_baselines(
    NULL, NULL, 1.10, 'approve'
);

-- Example output:
-- sql_hash | plan_hash | status   | speedup_factor | action_taken
-- abc123   | def456    | Approved | 2.34           | Plan approved (2.34× faster)
-- abc123   | ghi789    | Unchanged| 0.95           | Not improved (0.95× — slower)
```

### Schedule Evolution as a Maintenance Job

```sql
-- Run evolution nightly to keep plans current
-- Create a procedure for scheduling

CREATE OR REPLACE PROCEDURE run_plan_evolution()
LANGUAGE plpgsql
AS $$
DECLARE
    v_result RECORD;
    v_approved INTEGER := 0;
BEGIN
    FOR v_result IN
        SELECT *
        FROM apg_plan_mgmt.evolve_plan_baselines(
            NULL, NULL, 1.10, 'approve'
        )
        WHERE status = 'Approved'
    LOOP
        v_approved := v_approved + 1;
        RAISE NOTICE 'Approved plan % for query % (%.2f× faster)',
            v_result.plan_hash, v_result.sql_hash, v_result.speedup_factor;
    END LOOP;

    RAISE NOTICE 'Evolution complete. Plans approved: %', v_approved;
END;
$$;

-- Schedule with pg_cron (if available on Aurora):
-- SELECT cron.schedule('0 2 * * *', 'CALL run_plan_evolution()');
```

---

## 10. Validating Plans

Over time, plans can become **invalid** if underlying objects change (tables dropped, indexes removed, columns altered). `validate_plans()` checks that stored plans are still executable.

### validate_plans() Function

```sql
-- Validate ALL plans
SELECT apg_plan_mgmt.validate_plans(
    sql_hash  := NULL,          -- NULL = all queries
    plan_hash := NULL,          -- NULL = all plans
    action    := 'nothing'      -- 'nothing' | 'disable' | 'delete'
);

-- Validate and disable invalid plans
SELECT apg_plan_mgmt.validate_plans(
    sql_hash  := NULL,
    plan_hash := NULL,
    action    := 'disable'     -- set enabled=false for invalid plans
);

-- Validate and DELETE invalid plans
SELECT apg_plan_mgmt.validate_plans(
    sql_hash  := NULL,
    plan_hash := NULL,
    action    := 'delete'      -- remove invalid plans permanently
);

-- Validate a specific query only
SELECT apg_plan_mgmt.validate_plans(
    sql_hash  := '<your_sql_hash>',
    plan_hash := NULL,
    action    := 'disable'
);
```

### Validate Output

```sql
-- validate_plans returns validation results:
SELECT
    sql_hash,
    plan_hash,
    status,
    valid,                  -- true = still valid, false = broken
    message,                -- reason if invalid
    action_taken
FROM apg_plan_mgmt.validate_plans(NULL, NULL, 'disable');

-- Example:
-- sql_hash  | plan_hash | valid | message                          | action_taken
-- abc123    | def456    | true  | Valid                            | None
-- abc123    | ghi789    | false | Index "idx_old_status" not found | Plan disabled
```

### When to Run Validation

```
Run validate_plans() BEFORE:
  ✅ Dropping an index (check if any plans depend on it)
  ✅ Altering a column type
  ✅ Dropping a table
  ✅ Major schema migrations

Run validate_plans() AFTER:
  ✅ A PostgreSQL version upgrade
  ✅ Any DDL change to managed tables
  ✅ Scheduled nightly maintenance
```

---

## 11. Fixing Plans — force_custom_plan & Hints

### The Generic vs Custom Plan Problem

```sql
-- PostgreSQL caches generic plans for prepared statements
-- A generic plan ignores specific parameter values → may be suboptimal

PREPARE stmt AS SELECT * FROM orders WHERE customer_id = $1;

-- First 5 executions: PostgreSQL uses a custom plan (for each value)
EXECUTE stmt(1);       -- custom plan for customer_id=1
EXECUTE stmt(100000);  -- custom plan for customer_id=100000

-- After 5 executions: PostgreSQL may switch to a generic plan
-- Generic plan: uses average statistics, ignores specific value
-- Can be slow for skewed data distributions!

-- apg_plan_mgmt captures BOTH custom and generic plans separately
```

### force_custom_plan Setting

```sql
-- Force PostgreSQL to always use a custom plan (per-parameter-value plan)
-- Useful when different parameter values need radically different plans

-- Per session:
SET plan_cache_mode = 'force_custom_plan';

-- Per prepared statement (in application code):
-- SET LOCAL plan_cache_mode = 'force_custom_plan';

-- Capture plans with force_custom_plan enabled:
SET apg_plan_mgmt.capture_plan_baselines = manual;
SET plan_cache_mode = 'force_custom_plan';

EXPLAIN EXECUTE stmt(1);          -- captures custom plan for value=1
EXPLAIN EXECUTE stmt(100000);     -- captures potentially different plan

SET apg_plan_mgmt.capture_plan_baselines = off;
```

### Combining with pg_hint_plan

```sql
-- apg_plan_mgmt integrates with pg_hint_plan for explicit plan hints
-- Hints override the planner's decisions

-- Available hints (if pg_hint_plan is loaded):
/*+ SeqScan(orders) */              -- force sequential scan
/*+ IndexScan(orders idx_name) */   -- force specific index
/*+ HashJoin(orders customers) */   -- force hash join
/*+ NestLoop(orders customers) */   -- force nested loop
/*+ MergeJoin(orders customers) */  -- force merge join
/*+ Leading(orders customers) */    -- force join order
/*+ Parallel(orders 4) */           -- force parallel workers
/*+ NoParallel(orders) */           -- disable parallelism

-- Example: force an index scan that the planner avoids
SET apg_plan_mgmt.capture_plan_baselines = manual;

EXPLAIN
/*+ IndexScan(o idx_orders_status) */
SELECT o.id, o.amount, c.name
FROM orders o
JOIN customers c ON c.id = o.customer_id
WHERE o.status = 'pending'
  AND o.amount > 50000;

SET apg_plan_mgmt.capture_plan_baselines = off;

-- The hinted plan is now captured and can be approved
-- When enforcement is on, this hinted plan will always be used
SELECT apg_plan_mgmt.approve_plan('<plan_hash_of_hinted_plan>');
```

### Overriding a Bad Plan with a Known Good One

```sql
-- Workflow: Bad plan detected in production → fix it

-- Step 1: Identify the bad plan
SELECT sql_hash, plan_hash, avg_plan_time_ms, calls
FROM apg_plan_mgmt.dba_plans
WHERE avg_plan_time_ms > 5000   -- slow plans
  AND calls > 100               -- frequently called
ORDER BY avg_plan_time_ms DESC;

-- Step 2: Reject the bad plan
SELECT apg_plan_mgmt.reject_plan('<bad_plan_hash>');

-- Step 3: Capture a better plan with hints
SET apg_plan_mgmt.capture_plan_baselines = manual;

EXPLAIN
/*+ IndexScan(o idx_orders_cust_status) HashJoin(o c) */
SELECT o.id, o.amount, c.name
FROM orders o
JOIN customers c ON c.id = o.customer_id
WHERE o.status = 'pending'
  AND o.customer_id > 100000;

SET apg_plan_mgmt.capture_plan_baselines = off;

-- Step 4: Approve the hinted plan
SELECT apg_plan_mgmt.approve_plan('<new_hinted_plan_hash>');

-- Step 5: Enable enforcement
SET apg_plan_mgmt.use_plan_baselines = on;
-- Now only the hinted plan is used for this query
```

---

## 12. Monitoring & Reporting

### Plan Regression Detection

```sql
-- Find queries where a new (unapproved) plan is being chosen despite enforcement
-- These indicate the approved plan may be invalid or less optimal now

SELECT
    d.sql_hash,
    d.plan_hash,
    d.status,
    d.calls,
    ROUND(d.avg_plan_time_ms::NUMERIC, 2)       AS avg_ms,
    ROUND(d.estimated_total_cost::NUMERIC, 2)   AS est_cost,
    d.last_used,
    LEFT(d.sql_text, 100)                       AS query_snippet
FROM apg_plan_mgmt.dba_plans d
WHERE d.status    = 'Unapproved'
  AND d.calls     > 0              -- being used despite unapproved
  AND d.last_used > NOW() - INTERVAL '1 day'
ORDER BY d.calls DESC;
```

### Performance Comparison Across Plans

```sql
-- Compare all plans for a query side-by-side
SELECT
    plan_hash,
    status,
    enabled,
    ROUND(estimated_total_cost::NUMERIC, 2)     AS est_cost,
    calls,
    ROUND(avg_plan_time_ms::NUMERIC, 2)         AS avg_exec_ms,
    ROUND(total_plan_time::NUMERIC, 0)          AS total_ms,
    origin,
    last_used,
    notes
FROM apg_plan_mgmt.dba_plans
WHERE sql_hash = '<your_sql_hash>'
ORDER BY
    CASE status
        WHEN 'Preferred'  THEN 1
        WHEN 'Approved'   THEN 2
        WHEN 'Unapproved' THEN 3
        WHEN 'Rejected'   THEN 4
    END,
    avg_plan_time_ms;
```

### Overall Plan Health Dashboard

```sql
-- Summary of plan management health
SELECT
    status,
    COUNT(*)                                     AS plan_count,
    COUNT(DISTINCT sql_hash)                     AS unique_queries,
    SUM(calls)                                   AS total_executions,
    ROUND(AVG(avg_plan_time_ms)::NUMERIC, 2)     AS avg_exec_ms,
    MAX(last_used)                               AS most_recent_use
FROM apg_plan_mgmt.dba_plans
GROUP BY status
ORDER BY plan_count DESC;
```

**Example output:**

| status | plan_count | unique_queries | total_executions | avg_exec_ms |
|--------|-----------|----------------|-----------------|-------------|
| Approved | 145 | 89 | 4502340 | 12.4 |
| Unapproved | 32 | 24 | 18 | 234.1 |
| Rejected | 8 | 6 | 0 | — |
| Preferred | 12 | 12 | 892100 | 3.2 |

### Identify Queries Without Approved Plans

```sql
-- Queries where enforcement provides no protection
-- (new queries or queries where all plans were rejected)
SELECT DISTINCT d.sql_hash, LEFT(d.sql_text, 100) AS query_preview
FROM apg_plan_mgmt.dba_plans d
WHERE NOT EXISTS (
    SELECT 1 FROM apg_plan_mgmt.dba_plans d2
    WHERE d2.sql_hash = d.sql_hash
      AND d2.status IN ('Approved', 'Preferred')
      AND d2.enabled = true
)
ORDER BY d.sql_hash;
```

### Stale Plans (Not Used Recently)

```sql
-- Plans not used in the last 30 days — candidates for cleanup
SELECT
    sql_hash,
    plan_hash,
    status,
    calls,
    last_used,
    created_at,
    NOW() - last_used AS days_stale
FROM apg_plan_mgmt.dba_plans
WHERE last_used < NOW() - INTERVAL '30 days'
   OR (last_used IS NULL AND created_at < NOW() - INTERVAL '30 days')
ORDER BY last_used NULLS FIRST;
```

---

## 13. Plan Maintenance Functions

### get_explain_plan() — View a Stored Plan

```sql
-- Get the EXPLAIN text for a specific plan
SELECT apg_plan_mgmt.get_explain_plan(
    sql_hash  := '<sql_hash>',
    plan_hash := '<plan_hash>',
    format    := 'text'         -- 'text' | 'json' | 'xml' | 'yaml'
);

-- Get all plans for a query as text
SELECT
    plan_hash,
    status,
    apg_plan_mgmt.get_explain_plan(sql_hash, plan_hash, 'text') AS plan_text
FROM apg_plan_mgmt.dba_plans
WHERE sql_hash = '<your_sql_hash>';
```

### delete_plan() — Remove a Plan

```sql
-- Delete a specific plan
SELECT apg_plan_mgmt.delete_plan(
    sql_hash  := '<sql_hash>',
    plan_hash := '<plan_hash>'
);

-- Delete ALL plans for a specific query
SELECT apg_plan_mgmt.delete_plan(sql_hash, plan_hash)
FROM apg_plan_mgmt.dba_plans
WHERE sql_hash = '<sql_hash>';

-- Delete all rejected plans (cleanup)
SELECT apg_plan_mgmt.delete_plan(sql_hash, plan_hash)
FROM apg_plan_mgmt.dba_plans
WHERE status = 'Rejected';

-- Delete plans not used in 60 days (cleanup old stale plans)
SELECT apg_plan_mgmt.delete_plan(sql_hash, plan_hash)
FROM apg_plan_mgmt.dba_plans
WHERE last_used < NOW() - INTERVAL '60 days'
  AND status = 'Unapproved';   -- only delete unapproved stale plans
```

### set_plan_status() — Direct Status Update

```sql
-- Alternative to approve_plan / reject_plan
SELECT apg_plan_mgmt.set_plan_status(
    sql_hash  := '<sql_hash>',
    plan_hash := '<plan_hash>',
    status    := 'Approved'     -- 'Approved' | 'Unapproved' | 'Rejected' | 'Preferred'
);

-- Set with notes
UPDATE apg_plan_mgmt.plans
SET status = 'Preferred',
    notes  = 'Verified fastest plan on 2024-03-15 load test'
WHERE plan_hash = '<plan_hash>';
```

### Maintenance Stored Procedure (Full Cleanup)

```sql
CREATE OR REPLACE PROCEDURE plan_mgmt_maintenance(
    p_stale_days  INTEGER  DEFAULT 30,
    p_dry_run     BOOLEAN  DEFAULT true
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_stale     INTEGER := 0;
    v_invalid   INTEGER := 0;
    v_evolved   INTEGER := 0;
    v_rec       RECORD;
BEGIN
    RAISE NOTICE '=== Plan Management Maintenance (dry_run=%) ===', p_dry_run;

    -- 1. Validate all plans
    FOR v_rec IN
        SELECT *
        FROM apg_plan_mgmt.validate_plans(NULL, NULL,
            CASE WHEN p_dry_run THEN 'nothing' ELSE 'disable' END)
        WHERE valid = false
    LOOP
        v_invalid := v_invalid + 1;
        RAISE NOTICE 'INVALID plan %: %', v_rec.plan_hash, v_rec.message;
    END LOOP;
    RAISE NOTICE 'Invalid plans found: %', v_invalid;

    -- 2. Evolve plans (approve if 10% faster)
    FOR v_rec IN
        SELECT *
        FROM apg_plan_mgmt.evolve_plan_baselines(NULL, NULL, 1.10,
            CASE WHEN p_dry_run THEN 'nothing' ELSE 'approve' END)
        WHERE status = 'Approved'
    LOOP
        v_evolved := v_evolved + 1;
        RAISE NOTICE 'EVOLVED plan %: %.2f× faster', v_rec.plan_hash, v_rec.speedup_factor;
    END LOOP;
    RAISE NOTICE 'Plans evolved/approved: %', v_evolved;

    -- 3. Delete stale unapproved plans
    FOR v_rec IN
        SELECT sql_hash, plan_hash, last_used
        FROM apg_plan_mgmt.dba_plans
        WHERE status = 'Unapproved'
          AND (last_used < NOW() - (p_stale_days || ' days')::INTERVAL
               OR last_used IS NULL)
    LOOP
        v_stale := v_stale + 1;
        RAISE NOTICE 'STALE plan %: last used %', v_rec.plan_hash, v_rec.last_used;
        IF NOT p_dry_run THEN
            PERFORM apg_plan_mgmt.delete_plan(v_rec.sql_hash, v_rec.plan_hash);
        END IF;
    END LOOP;
    RAISE NOTICE 'Stale plans %: %',
        CASE WHEN p_dry_run THEN 'found' ELSE 'deleted' END, v_stale;

    RAISE NOTICE '=== Maintenance Complete ===';
END;
$$;

-- Dry run first
CALL plan_mgmt_maintenance(p_stale_days := 30, p_dry_run := true);

-- Execute for real
CALL plan_mgmt_maintenance(p_stale_days := 30, p_dry_run := false);
```

---

## 14. Exporting & Importing Plans

Move plans between environments (dev → staging → production) or backup/restore.

### Export to S3

```sql
-- Export ALL plans to S3
SELECT apg_plan_mgmt.export_plans_to_s3(
    's3://my-bucket/plan-backups/',
    NULL,       -- sql_hash: NULL = all queries
    NULL        -- plan_hash: NULL = all plans
);

-- Export plans for a specific query
SELECT apg_plan_mgmt.export_plans_to_s3(
    's3://my-bucket/plan-backups/critical-query/',
    '<sql_hash>',
    NULL
);

-- Export only approved plans
-- (filter first, then export each)
SELECT apg_plan_mgmt.export_plans_to_s3(
    's3://my-bucket/plan-backups/approved/',
    sql_hash,
    plan_hash
)
FROM apg_plan_mgmt.dba_plans
WHERE status IN ('Approved', 'Preferred');
```

### Import from S3

```sql
-- Import plans from S3 to current cluster
SELECT apg_plan_mgmt.import_plans_from_s3(
    's3://my-bucket/plan-backups/approved/',
    NULL        -- import all files in the S3 prefix
);

-- After import, plans arrive as 'Unapproved' in the target DB
-- Review and approve them:
SELECT sql_hash, plan_hash, status, estimated_total_cost
FROM apg_plan_mgmt.dba_plans
WHERE status = 'Unapproved'
  AND origin = 'Manual'     -- imported plans
ORDER BY estimated_total_cost;
```

### Export / Import via pg_dump (Alternative)

```sql
-- Backup the plans table directly
pg_dump \
    --schema=apg_plan_mgmt \
    --table=apg_plan_mgmt.plans \
    --data-only \
    --file=/backups/plan_baselines_$(date +%Y%m%d).sql \
    mydb

-- Restore
psql -d target_db -f /backups/plan_baselines_20240315.sql
```

### Promotion Workflow: Dev → Staging → Production

```
Development Environment
    │
    │ 1. Capture plans during testing
    │    SET capture_plan_baselines = manual;
    │    -- run workload --
    │
    │ 2. Approve best plans
    │    SELECT apg_plan_mgmt.approve_plan(...)
    │
    │ 3. Export approved plans to S3
    │    SELECT apg_plan_mgmt.export_plans_to_s3('s3://...')
    │
    ▼
Staging Environment
    │
    │ 4. Import from S3
    │    SELECT apg_plan_mgmt.import_plans_from_s3('s3://...')
    │
    │ 5. Validate plans (schema must match)
    │    SELECT apg_plan_mgmt.validate_plans(NULL, NULL, 'disable')
    │
    │ 6. Test with enforcement
    │    SET use_plan_baselines = on;
    │    -- run regression tests --
    │
    ▼
Production Environment
    │
    │ 7. Import validated plans
    │ 8. Enable enforcement
    │    SET use_plan_baselines = on;
    │
    └── Plan regressions prevented ✅
```

---

## 15. Real-World Patterns

### Pattern 1: Pre-Upgrade Plan Freeze

```sql
-- BEFORE a PostgreSQL version upgrade:
-- Capture and approve all critical query plans
-- After upgrade, plans are enforced so no regressions

-- Step 1: Enable automatic capture for 1 week of production workload
-- (In Aurora Parameter Group)
-- apg_plan_mgmt.capture_plan_baselines = automatic

-- Wait 1 week to capture full workload variety...

-- Step 2: Review and approve plans with good performance
SELECT apg_plan_mgmt.approve_plan(plan_hash)
FROM apg_plan_mgmt.dba_plans
WHERE status = 'Unapproved'
  AND calls > 100                      -- well-exercised plans
  AND avg_plan_time_ms < 500           -- fast enough to trust
  AND estimated_total_cost < 100000;   -- reasonable cost estimate

-- Step 3: Export approved plans
SELECT apg_plan_mgmt.export_plans_to_s3('s3://my-bucket/pre-upgrade-plans/');

-- Step 4: Perform PostgreSQL version upgrade

-- Step 5: Import plans to upgraded cluster
SELECT apg_plan_mgmt.import_plans_from_s3('s3://my-bucket/pre-upgrade-plans/');

-- Step 6: Validate all plans on new version
SELECT apg_plan_mgmt.validate_plans(NULL, NULL, 'disable');

-- Step 7: Approve imported plans
SELECT apg_plan_mgmt.approve_plan(plan_hash)
FROM apg_plan_mgmt.dba_plans
WHERE status = 'Unapproved';

-- Step 8: Enable enforcement
-- apg_plan_mgmt.use_plan_baselines = on
```

### Pattern 2: Critical Query Protection

```sql
-- Protect your most important queries from ever regressing

-- Step 1: Identify critical queries from pg_stat_statements
SELECT query, calls, mean_exec_time, total_exec_time
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 10;

-- Step 2: Capture plans for critical queries only (targeted)
SET apg_plan_mgmt.capture_plan_baselines = manual;

-- Run each critical query
EXPLAIN SELECT o.id, o.amount, c.name
        FROM orders o JOIN customers c ON c.id = o.customer_id
        WHERE o.status = 'shipped' AND c.segment = 'gold';

EXPLAIN SELECT COUNT(*), SUM(amount)
        FROM orders
        WHERE created_at >= CURRENT_DATE - 7
          AND status IN ('shipped', 'delivered');

SET apg_plan_mgmt.capture_plan_baselines = off;

-- Step 3: Review and approve captured plans
SELECT plan_hash, estimated_total_cost, calls
FROM apg_plan_mgmt.dba_plans WHERE status = 'Unapproved';

SELECT apg_plan_mgmt.approve_plan('<plan_hash>');

-- Step 4: Enable enforcement globally
-- (In Aurora Parameter Group)
-- apg_plan_mgmt.use_plan_baselines = on

-- These critical queries are now protected from plan regressions forever
```

### Pattern 3: Emergency Plan Fix

```sql
-- EMERGENCY: Production query suddenly running slow after ANALYZE

-- Step 1: Identify the regression
SELECT sql_hash, plan_hash, status, avg_plan_time_ms, calls, last_used
FROM apg_plan_mgmt.dba_plans
WHERE avg_plan_time_ms > 10000   -- suddenly > 10 seconds
  AND calls > 10
ORDER BY avg_plan_time_ms DESC;

-- Step 2: Check what changed — compare old vs new plan
SELECT plan_hash, status, estimated_total_cost, avg_plan_time_ms, calls, last_used
FROM apg_plan_mgmt.dba_plans
WHERE sql_hash = '<affected_sql_hash>'
ORDER BY last_used DESC;

-- Step 3: Approve the OLD fast plan (from before regression)
SELECT apg_plan_mgmt.approve_plan('<fast_old_plan_hash>');

-- Step 4: Reject the new slow plan
SELECT apg_plan_mgmt.reject_plan('<slow_new_plan_hash>');

-- Step 5: Enable enforcement immediately
SET apg_plan_mgmt.use_plan_baselines = on;
-- Query now reverts to the fast plan within milliseconds
-- No code changes, no deployment needed ← big advantage

-- Step 6: Investigate root cause asynchronously
-- (statistics refresh, index issue, data skew, etc.)
```

### Pattern 4: Index Change Safety Net

```sql
-- BEFORE dropping an index: check if any managed plans depend on it

-- Step 1: Get EXPLAIN text for all approved plans
-- and search for the index name
SELECT plan_hash, status,
       apg_plan_mgmt.get_explain_plan(sql_hash, plan_hash, 'text') AS plan_text
FROM apg_plan_mgmt.dba_plans
WHERE status IN ('Approved', 'Preferred');
-- Manually scan for "Index Scan using idx_name_to_drop"

-- Step 2: If plans depend on the index — validate after dropping
DROP INDEX CONCURRENTLY idx_old_index_name;

-- Step 3: Validate all plans
SELECT *
FROM apg_plan_mgmt.validate_plans(NULL, NULL, 'disable');
-- Plans using the dropped index will be marked invalid and disabled

-- Step 4: Capture and approve new plans without the old index
SET apg_plan_mgmt.capture_plan_baselines = manual;
-- Run affected queries...
SET apg_plan_mgmt.capture_plan_baselines = off;

-- Step 5: Approve best new plans
SELECT apg_plan_mgmt.approve_plan('<new_plan_hash>');
```

---

## 16. Troubleshooting

### Problem: Queries Ignoring Approved Plans

```sql
-- Symptom: use_plan_baselines=on but planner still uses unapproved plans

-- Check 1: Is the plan truly approved and enabled?
SELECT status, enabled, sql_hash, plan_hash
FROM apg_plan_mgmt.dba_plans
WHERE sql_hash = '<hash>'
  AND status IN ('Approved', 'Preferred');
-- Must have enabled=true

-- Check 2: Does the sql_hash match exactly?
-- apg_plan_mgmt normalizes SQL before hashing
-- Whitespace differences, case differences → different hash
-- Test: capture the EXACT query string from application

-- Check 3: Is the approved plan still valid?
SELECT *
FROM apg_plan_mgmt.validate_plans('<sql_hash>', NULL, 'nothing');
-- If valid=false → plan is broken, needs replacement

-- Check 4: GUC environment mismatch
-- Plans are tied to specific GUC values when captured
-- Different work_mem, search_path → different plan hash
SELECT environment_variables FROM apg_plan_mgmt.dba_plans
WHERE plan_hash = '<hash>';
-- Compare with current session GUCs
```

### Problem: Too Many Unapproved Plans Accumulating

```sql
-- symptom: plans table growing with unchecked unapproved plans

-- Check volume
SELECT status, COUNT(*) FROM apg_plan_mgmt.dba_plans GROUP BY status;

-- Option 1: Bulk approve low-cost, well-tested plans
SELECT apg_plan_mgmt.approve_plan(plan_hash)
FROM apg_plan_mgmt.dba_plans
WHERE status   = 'Unapproved'
  AND calls    > 500                  -- heavily used
  AND avg_plan_time_ms < 100          -- fast plans
  AND estimated_total_cost < 10000;   -- low cost plans

-- Option 2: Delete old unapproved plans that were never used
SELECT apg_plan_mgmt.delete_plan(sql_hash, plan_hash)
FROM apg_plan_mgmt.dba_plans
WHERE status   = 'Unapproved'
  AND calls    = 0
  AND created_at < NOW() - INTERVAL '14 days';

-- Option 3: Limit future capture
-- apg_plan_mgmt.max_plans_per_query = 5  (default 10000)
-- apg_plan_mgmt.plan_retention_period = 14  (days, default 32)
```

### Problem: Plan Capture Not Working

```sql
-- Check extension is loaded
SELECT * FROM pg_extension WHERE extname = 'apg_plan_mgmt';

-- Check capture is enabled
SHOW apg_plan_mgmt.capture_plan_baselines;

-- Check wal_level (must be >= replica)
SHOW wal_level;

-- Check current user has permission
SELECT pg_has_role(CURRENT_USER, 'apg_plan_mgmt', 'member');

-- Check shared_preload_libraries was set correctly
SHOW shared_preload_libraries;
-- Must include 'apg_plan_mgmt'

-- Verify by running a test query and checking plans table
SET apg_plan_mgmt.capture_plan_baselines = manual;
SELECT 1;  -- even this gets a plan entry
SELECT COUNT(*) FROM apg_plan_mgmt.dba_plans
WHERE created_at > NOW() - INTERVAL '1 minute';
SET apg_plan_mgmt.capture_plan_baselines = off;
```

### Problem: Performance Overhead

```sql
-- apg_plan_mgmt adds a small overhead to every query
-- (hash computation + plans table lookup)

-- Measure overhead:
-- Run query with use_plan_baselines=off (baseline)
-- Run same query with use_plan_baselines=on
-- Compare: overhead should be < 1ms for most queries

-- Reduce overhead:
-- 1. Turn off capture when not needed
SET apg_plan_mgmt.capture_plan_baselines = off;   -- after baselining

-- 2. Limit plans per query
-- apg_plan_mgmt.max_plans_per_query = 10  (reduce from 10000)

-- 3. Purge stale plans regularly (smaller table = faster lookup)
CALL plan_mgmt_maintenance(p_stale_days := 14, p_dry_run := false);

-- 4. Use enforcement only for critical queries
-- Don't enforce all queries — only high-risk / high-value ones
```

---

## 17. Quick Reference Cheat Sheet

```
╔════════════════════════════╦═════════════════════════════════════════════════╗
║ TOPIC                      ║ KEY COMMAND / SETTING                           ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ Install                    ║ CREATE EXTENSION apg_plan_mgmt;                  ║
║                            ║ shared_preload_libraries = 'apg_plan_mgmt'       ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ Capture Modes              ║ SET apg_plan_mgmt.capture_plan_baselines =       ║
║                            ║   off        → no capture (default)             ║
║                            ║   automatic  → capture all queries              ║
║                            ║   manual     → capture in this session only     ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ Enforcement                ║ SET apg_plan_mgmt.use_plan_baselines =           ║
║                            ║   off  → observe only (default)                 ║
║                            ║   on   → enforce approved plans only            ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ Plan Statuses              ║ Unapproved → captured, not reviewed             ║
║                            ║ Approved   → DBA verified, enforced             ║
║                            ║ Preferred  → best plan, tried first             ║
║                            ║ Rejected   → bad plan, never used               ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ Approve Plan               ║ SELECT apg_plan_mgmt.approve_plan('<hash>');    ║
║ Reject Plan                ║ SELECT apg_plan_mgmt.reject_plan('<hash>');     ║
║ Delete Plan                ║ SELECT apg_plan_mgmt.delete_plan(sql, plan);    ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ View Plans                 ║ SELECT * FROM apg_plan_mgmt.dba_plans;          ║
║ View Plan Text             ║ SELECT apg_plan_mgmt.get_explain_plan(s,p,'text')║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ Validate Plans             ║ SELECT apg_plan_mgmt.validate_plans(            ║
║                            ║   NULL, NULL, 'disable');                       ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ Evolve Plans               ║ SELECT apg_plan_mgmt.evolve_plan_baselines(     ║
║                            ║   NULL, NULL, 1.10, 'approve');                 ║
║                            ║ → Approves plans ≥10% faster than current       ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ Export to S3               ║ SELECT apg_plan_mgmt.export_plans_to_s3('s3://…')║
║ Import from S3             ║ SELECT apg_plan_mgmt.import_plans_from_s3('s3://…')║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ Plan with Hints            ║ /*+ IndexScan(t idx_name) */  in query text     ║
║ Force Custom Plan          ║ SET plan_cache_mode = 'force_custom_plan';      ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ Recommended Workflow       ║ 1. capture_plan_baselines = manual              ║
║                            ║ 2. Run workload / EXPLAIN queries               ║
║                            ║ 3. Review dba_plans                             ║
║                            ║ 4. approve_plan() best plans                    ║
║                            ║ 5. reject_plan() bad plans                      ║
║                            ║ 6. capture_plan_baselines = off                 ║
║                            ║ 7. use_plan_baselines = on                      ║
║                            ║ 8. Schedule validate + evolve nightly           ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ Key GUC Parameters         ║ capture_plan_baselines   → off/automatic/manual ║
║                            ║ use_plan_baselines        → off/on              ║
║                            ║ max_plans_per_query       → default 10000       ║
║                            ║ plan_retention_period     → default 32 (days)   ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ Aurora Only                ║ NOT available on standard PostgreSQL            ║
║                            ║ Available: Aurora PostgreSQL 10.5+              ║
╚════════════════════════════╩═════════════════════════════════════════════════╝
```

---

## Further Reading

- [AWS Docs — Managing Query Execution Plans](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/AuroraPostgreSQL.Optimize.html)
- [AWS Docs — apg_plan_mgmt Reference](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/AuroraPostgreSQL.Optimize.Functions.html)
- [AWS Docs — Best Practices for QPM](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/AuroraPostgreSQL.Optimize.BestPractice.html)
- [AWS Docs — Plan Evolution](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/AuroraPostgreSQL.Optimize.EvolvingPlans.html)
- [AWS Docs — Exporting & Importing Plans](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/AuroraPostgreSQL.Optimize.ExportImport.html)
- [pg_hint_plan Documentation](https://pghintplan.osdn.jp/pg_hint_plan.html)
- [PostgreSQL Docs — Planner Configuration](https://www.postgresql.org/docs/current/runtime-config-query.html)

---

*Generated with love for Aurora PostgreSQL engineers.*
