# PostgreSQL — Transactions & Concurrency Complete Reference

> A deep-dive guide covering transactions, ACID properties, isolation levels, locking, MVCC, deadlocks, and concurrency patterns in PostgreSQL.

---

## Table of Contents

1. [What is a Transaction?](#1-what-is-a-transaction)
2. [ACID Properties](#2-acid-properties)
3. [Transaction Syntax](#3-transaction-syntax)
4. [Savepoints](#4-savepoints)
5. [Isolation Levels](#5-isolation-levels)
6. [Concurrency Anomalies](#6-concurrency-anomalies)
7. [MVCC — Multi-Version Concurrency Control](#7-mvcc--multi-version-concurrency-control)
8. [Locking](#8-locking)
9. [Row-Level Locks](#9-row-level-locks)
10. [Advisory Locks](#10-advisory-locks)
11. [Deadlocks](#11-deadlocks)
12. [Transaction Monitoring](#12-transaction-monitoring)
13. [Optimistic vs Pessimistic Concurrency](#13-optimistic-vs-pessimistic-concurrency)
14. [Common Concurrency Patterns](#14-common-concurrency-patterns)
15. [Performance & Tuning](#15-performance--tuning)
16. [Quick Reference Cheat Sheet](#16-quick-reference-cheat-sheet)

---

## Sample Tables Used in Examples

```sql
CREATE TABLE accounts (
    id         SERIAL PRIMARY KEY,
    owner      TEXT NOT NULL,
    balance    NUMERIC NOT NULL CHECK (balance >= 0)
);

CREATE TABLE orders (
    id          SERIAL PRIMARY KEY,
    customer_id INTEGER REFERENCES accounts(id),
    product     TEXT,
    amount      NUMERIC,
    status      TEXT DEFAULT 'pending',
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE inventory (
    product_id  INTEGER PRIMARY KEY,
    name        TEXT,
    stock       INTEGER NOT NULL CHECK (stock >= 0)
);

INSERT INTO accounts VALUES
  (1, 'Alice', 10000),
  (2, 'Bob',   5000),
  (3, 'Carol', 8000);

INSERT INTO inventory VALUES
  (101, 'Laptop',  50),
  (102, 'Mouse',  200),
  (103, 'Monitor', 30);
```

---

## 1. What is a Transaction?

A **transaction** is a sequence of SQL statements that are treated as a single logical unit of work. Either **all** statements succeed and are committed, or **none** of them take effect (rolled back).

```
BEGIN                   ← start transaction
  Statement 1           ← do work
  Statement 2           ← do more work
  Statement 3           ← do more work
COMMIT  or  ROLLBACK    ← end transaction
```

### Without a Transaction (Auto-commit mode)

```sql
-- Each statement is its own transaction — no protection
UPDATE accounts SET balance = balance - 500 WHERE id = 1;  -- committed immediately
UPDATE accounts SET balance = balance + 500 WHERE id = 2;  -- committed immediately
-- If the second update fails, the first is already committed → money disappears!
```

### With a Transaction

```sql
BEGIN;
    UPDATE accounts SET balance = balance - 500 WHERE id = 1;
    UPDATE accounts SET balance = balance + 500 WHERE id = 2;
COMMIT;
-- Both succeed or neither is applied
```

### Transaction Lifecycle

```
┌─────────┐   BEGIN    ┌─────────────┐   COMMIT     ┌──────────────┐
│  Idle   │ ─────────► │  In Trans.  │ ───────────► │   Idle       │
└─────────┘            └─────────────┘              │  (changes    │
                              │                     │   persisted) │
                              │ ROLLBACK            └──────────────┘
                              ▼
                       ┌──────────────┐
                       │   Idle       │
                       │  (changes    │
                       │   discarded) │
                       └──────────────┘
```

---

## 2. ACID Properties

PostgreSQL guarantees all four ACID properties for every transaction.

### A — Atomicity

> **All or nothing.** Every statement in a transaction either fully succeeds or the entire transaction is rolled back.

```sql
BEGIN;
    UPDATE accounts SET balance = balance - 1000 WHERE id = 1;  -- Alice -1000
    UPDATE accounts SET balance = balance + 1000 WHERE id = 2;  -- Bob   +1000
    -- Simulate an error:
    UPDATE accounts SET balance = balance - 9999999 WHERE id = 3; -- violates CHECK
ROLLBACK;
-- Result: ALL three updates are undone. Alice and Bob's balances unchanged.
```

### C — Consistency

> **Data must always move from one valid state to another valid state.** Constraints, foreign keys, and rules are enforced.

```sql
BEGIN;
    UPDATE accounts SET balance = -500 WHERE id = 1;  -- violates CHECK (balance >= 0)
    -- ERROR: new row for relation "accounts" violates check constraint
ROLLBACK;
-- Consistency maintained: negative balance never stored
```

### I — Isolation

> **Concurrent transactions appear to run independently.** One transaction's in-progress changes are not visible to others (by default).

```sql
-- Session A:
BEGIN;
UPDATE accounts SET balance = balance + 9999 WHERE id = 1;
-- NOT committed yet

-- Session B (concurrent):
SELECT balance FROM accounts WHERE id = 1;
-- Returns ORIGINAL balance — Session B cannot see Session A's uncommitted change
```

### D — Durability

> **Once committed, changes survive crashes, power failures, and restarts.**

PostgreSQL achieves durability through the **Write-Ahead Log (WAL)**:

```
Every committed change is first written to the WAL on disk,
BEFORE the data pages are updated.

On crash recovery:
  → PostgreSQL replays the WAL to restore committed transactions
  → Uncommitted transactions are rolled back

Configuration:
  synchronous_commit = on    (default — guarantees durability)
  synchronous_commit = off   (faster writes, small risk of loss on crash)
  fsync = on                 (default — ensures WAL is flushed to disk)
```

---

## 3. Transaction Syntax

### Basic Commands

```sql
-- Start a transaction
BEGIN;
BEGIN TRANSACTION;
START TRANSACTION;   -- SQL standard

-- Commit (make permanent)
COMMIT;
COMMIT WORK;

-- Rollback (undo everything)
ROLLBACK;
ROLLBACK WORK;
```

### Transaction with Error Handling

```sql
BEGIN;

    -- Debit Alice
    UPDATE accounts
    SET balance = balance - 500
    WHERE id = 1;

    -- Check for negative balance manually
    DO $$
    DECLARE
        bal NUMERIC;
    BEGIN
        SELECT balance INTO bal FROM accounts WHERE id = 1;
        IF bal < 0 THEN
            RAISE EXCEPTION 'Insufficient funds';
        END IF;
    END $$;

    -- Credit Bob
    UPDATE accounts
    SET balance = balance + 500
    WHERE id = 2;

COMMIT;
-- On any error, the DO block raises exception → entire transaction rolls back
```

### DDL Inside Transactions

PostgreSQL supports DDL statements (CREATE, ALTER, DROP) inside transactions — unlike many other databases.

```sql
BEGIN;
    CREATE TABLE temp_report (
        id      SERIAL,
        summary TEXT,
        created DATE DEFAULT CURRENT_DATE
    );
    INSERT INTO temp_report (summary) VALUES ('Q1 completed');
    -- Change your mind:
ROLLBACK;
-- The table was never created — DDL is fully transactional in PostgreSQL
```

---

## 4. Savepoints

Savepoints allow partial rollbacks within a transaction without abandoning the whole transaction.

```sql
BEGIN;

    UPDATE accounts SET balance = balance - 200 WHERE id = 1;  -- Step 1

    SAVEPOINT step1;    -- Mark a point we can return to

    UPDATE accounts SET balance = balance - 9999 WHERE id = 1; -- Step 2 (risky)

    -- Something went wrong — rollback ONLY to the savepoint
    ROLLBACK TO SAVEPOINT step1;
    -- Step 1 is still in effect; Step 2 is undone

    UPDATE accounts SET balance = balance - 100 WHERE id = 2;  -- Step 3

    -- Release the savepoint (optional cleanup)
    RELEASE SAVEPOINT step1;

COMMIT;
-- Final state: Alice -200, Bob -100
```

### Multiple Savepoints

```sql
BEGIN;
    INSERT INTO orders (customer_id, product, amount) VALUES (1, 'Laptop', 80000);
    SAVEPOINT after_order;

    UPDATE inventory SET stock = stock - 1 WHERE product_id = 101;
    SAVEPOINT after_inventory;

    -- Simulate a payment failure
    UPDATE accounts SET balance = balance - 80000 WHERE id = 1;  -- fails if insufficient

    ROLLBACK TO SAVEPOINT after_inventory;
    -- Order and inventory update still intact; only payment rolled back

    -- Try alternative payment...
    UPDATE accounts SET balance = balance - 80000 WHERE id = 2;

COMMIT;
```

---

## 5. Isolation Levels

Isolation levels control **how much a transaction is isolated from other concurrent transactions**. PostgreSQL supports all four standard SQL isolation levels.

```sql
-- Set isolation level for a transaction
BEGIN TRANSACTION ISOLATION LEVEL READ COMMITTED;
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ;
BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE;

-- Or after BEGIN:
BEGIN;
SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;
```

### The Four Isolation Levels

| Isolation Level | Dirty Read | Non-Repeatable Read | Phantom Read | Serialization Anomaly |
|-----------------|-----------|--------------------|--------------|-----------------------|
| Read Uncommitted | Possible* | Possible | Possible | Possible |
| **Read Committed** (default) | Not possible | Possible | Possible | Possible |
| Repeatable Read | Not possible | Not possible | Possible* | Possible |
| **Serializable** | Not possible | Not possible | Not possible | Not possible |

> *In PostgreSQL, Read Uncommitted behaves like Read Committed (dirty reads never happen due to MVCC). Repeatable Read also prevents phantom reads in PostgreSQL.

---

### Level 1: READ COMMITTED (Default)

Each statement sees a **fresh snapshot** of committed data as of when *that statement* starts.

```sql
-- Session A:
BEGIN;
UPDATE accounts SET balance = 20000 WHERE id = 1;
-- Not committed yet...

-- Session B (READ COMMITTED — the default):
BEGIN;
SELECT balance FROM accounts WHERE id = 1;
-- Returns: 10000  (original — cannot see Session A's uncommitted change)

-- Session A commits:
COMMIT;

-- Session B runs the same query again:
SELECT balance FROM accounts WHERE id = 1;
-- Returns: 20000  (NOW sees Session A's committed change — non-repeatable read!)
COMMIT;
```

> **Use when:** General OLTP workloads where you always want fresh committed data.

---

### Level 2: REPEATABLE READ

The transaction sees a **snapshot from the moment it begins**. The snapshot does NOT change during the transaction.

```sql
-- Session A:
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ;
SELECT balance FROM accounts WHERE id = 1;
-- Returns: 10000

-- Session B concurrently commits a change:
UPDATE accounts SET balance = 20000 WHERE id = 1;
COMMIT;

-- Session A queries again:
SELECT balance FROM accounts WHERE id = 1;
-- Still returns: 10000  (uses its own snapshot from transaction start)
COMMIT;
```

**Write conflict detection in Repeatable Read:**

```sql
-- Session A:
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ;
UPDATE accounts SET balance = balance + 100 WHERE id = 1;

-- Session B tries to update the SAME row:
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ;
UPDATE accounts SET balance = balance + 200 WHERE id = 1;
-- Session B BLOCKS (waiting for Session A's lock)

-- Session A commits:
COMMIT;

-- Session B's update:
-- ERROR: could not serialize access due to concurrent update
-- Session B must retry its transaction
```

> **Use when:** Long-running read queries that must see a consistent snapshot (reports, backups, analytics).

---

### Level 3: SERIALIZABLE

The **strictest** level. Transactions appear to execute **serially** (one after another), even though they actually run concurrently. PostgreSQL uses **Serializable Snapshot Isolation (SSI)** — detects read/write conflicts that would produce non-serializable outcomes.

```sql
-- Classic anomaly: Transfer scenario
-- accounts: Alice=1000, Bob=1000, total must always = 2000

-- Session A:
BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE;
SELECT SUM(balance) FROM accounts;   -- sees 2000
UPDATE accounts SET balance = balance + 100 WHERE owner = 'Alice';

-- Session B (concurrent):
BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE;
SELECT SUM(balance) FROM accounts;   -- sees 2000
UPDATE accounts SET balance = balance + 100 WHERE owner = 'Bob';

-- Session A commits: OK
COMMIT;

-- Session B tries to commit:
COMMIT;
-- ERROR: could not serialize access due to read/write dependencies
-- Session B must retry
```

**SSI detects "rw-anti-dependency" cycles** that would produce results impossible in any serial execution.

> **Use when:** Financial systems, inventory management, or any domain where correctness is more important than performance. Requires retry logic for serialization failures.

---

## 6. Concurrency Anomalies

### Dirty Read

> Reading **uncommitted** changes from another transaction.

```sql
-- Session A:
BEGIN;
UPDATE accounts SET balance = 99999 WHERE id = 1;
-- NOT committed

-- Session B (if dirty reads were possible):
SELECT balance FROM accounts WHERE id = 1;
-- Would return: 99999 (WRONG — Session A might ROLLBACK!)

-- In PostgreSQL: NEVER happens — MVCC prevents all dirty reads
```

### Non-Repeatable Read

> Reading the same row **twice** in a transaction and getting **different results** because another transaction committed a change between the reads.

```sql
-- Session A (READ COMMITTED):
BEGIN;
SELECT balance FROM accounts WHERE id = 1;  -- returns 10000

-- Session B commits a change:
UPDATE accounts SET balance = 20000 WHERE id = 1;
COMMIT;

-- Session A reads again:
SELECT balance FROM accounts WHERE id = 1;  -- returns 20000 (DIFFERENT!)
COMMIT;
-- This is a non-repeatable read — happens in READ COMMITTED
-- Does NOT happen in REPEATABLE READ or SERIALIZABLE
```

### Phantom Read

> A re-execution of a query returns **new rows** that weren't there before, because another transaction inserted and committed rows matching the WHERE clause.

```sql
-- Session A (READ COMMITTED or REPEATABLE READ):
BEGIN;
SELECT COUNT(*) FROM orders WHERE status = 'pending';  -- returns 5

-- Session B inserts a new pending order:
INSERT INTO orders (customer_id, product, amount, status) VALUES (1, 'Mouse', 1500, 'pending');
COMMIT;

-- Session A re-runs the query:
SELECT COUNT(*) FROM orders WHERE status = 'pending';  -- returns 6 (PHANTOM!)
COMMIT;
-- Does NOT happen in SERIALIZABLE (SSI detects this)
-- In PostgreSQL REPEATABLE READ also prevents phantom reads
```

### Serialization Anomaly (Write Skew)

> Two transactions each read the same data, then **both update different rows** based on what they read — producing a result that could not occur in any serial execution.

```sql
-- Rule: At least one of Alice or Bob must be on-call at all times

-- Session A:
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ;
SELECT COUNT(*) FROM on_call WHERE active = true;  -- returns 2 (Alice and Bob)
-- Both are active, so OK to remove Alice:
UPDATE on_call SET active = false WHERE name = 'Alice';

-- Session B (concurrent):
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ;
SELECT COUNT(*) FROM on_call WHERE active = true;  -- also returns 2
-- Both still active (session A not committed yet), so OK to remove Bob:
UPDATE on_call SET active = false WHERE name = 'Bob';

-- Both commit — now NOBODY is on-call! Invariant violated.
-- SERIALIZABLE isolation detects and prevents this.
```

---

## 7. MVCC — Multi-Version Concurrency Control

PostgreSQL uses **MVCC** to allow readers and writers to never block each other. Instead of locking rows, PostgreSQL keeps **multiple versions** of each row.

### How MVCC Works

```
Row inserted by Transaction 100:
┌────────────────────────────────────────────────────┐
│ xmin=100 │ xmax=0 │ data: balance=10000            │
└────────────────────────────────────────────────────┘
  ↑ created by txn 100   ↑ not deleted yet

Transaction 200 updates the row:
┌────────────────────────────────────────────────────┐
│ xmin=100 │ xmax=200 │ data: balance=10000 (OLD)    │  ← visible to txns before 200
└────────────────────────────────────────────────────┘
┌────────────────────────────────────────────────────┐
│ xmin=200 │ xmax=0   │ data: balance=20000 (NEW)    │  ← visible to txns from 200+
└────────────────────────────────────────────────────┘
```

### xmin and xmax System Columns

```sql
-- See the hidden MVCC columns on every row
SELECT xmin, xmax, id, owner, balance
FROM accounts;
```

| xmin | xmax | id | owner | balance |
|------|------|----|-------|---------|
| 100 | 0 | 1 | Alice | 10000 |
| 100 | 0 | 2 | Bob | 5000 |

- `xmin` — Transaction ID that created this row version
- `xmax` — Transaction ID that deleted/updated this row (0 = still live)
- A row is **visible** to a transaction if `xmin` is committed and before the snapshot, and `xmax` is either 0 or not yet committed

### MVCC Key Benefits

```
Readers never block writers
Writers never block readers
Old row versions provide consistent snapshots for long-running queries
No shared read locks needed
```

### Dead Tuples and VACUUM

MVCC creates **dead tuples** — old row versions no longer visible to any transaction. VACUUM reclaims this space.

```sql
-- Check dead tuples
SELECT relname, n_live_tup, n_dead_tup,
       last_vacuum, last_autovacuum
FROM pg_stat_user_tables
WHERE relname = 'accounts';

-- Manual vacuum
VACUUM accounts;
VACUUM ANALYZE accounts;

-- Aggressive vacuum (reclaims space to OS)
VACUUM FULL accounts;  -- WARNING: takes full table lock

-- View transaction ID horizon (oldest xmin still active)
SELECT min(backend_xmin) FROM pg_stat_activity;
```

---

## 8. Locking

PostgreSQL uses **table-level** and **row-level** locks to manage concurrent access.

### Table-Level Lock Modes

| Lock Mode | Acquired By | Blocks |
|-----------|-------------|--------|
| `ACCESS SHARE` | `SELECT` | `ACCESS EXCLUSIVE` only |
| `ROW SHARE` | `SELECT FOR UPDATE/SHARE` | `EXCLUSIVE`, `ACCESS EXCLUSIVE` |
| `ROW EXCLUSIVE` | `INSERT`, `UPDATE`, `DELETE` | `SHARE`, `SHARE ROW EXCLUSIVE`, `EXCLUSIVE`, `ACCESS EXCLUSIVE` |
| `SHARE UPDATE EXCLUSIVE` | `VACUUM`, `ANALYZE`, `CREATE INDEX CONCURRENTLY` | Self and above |
| `SHARE` | `CREATE INDEX` | `ROW EXCLUSIVE` and above |
| `SHARE ROW EXCLUSIVE` | Rare — some DDL | All writes |
| `EXCLUSIVE` | Rare — some DDL | All except `ACCESS SHARE` |
| `ACCESS EXCLUSIVE` | `DROP`, `TRUNCATE`, `ALTER TABLE`, `LOCK TABLE` | **Everything** |

```sql
-- Explicitly lock a table (rarely needed — DML locks automatically)
LOCK TABLE accounts IN SHARE MODE;
LOCK TABLE accounts IN ACCESS EXCLUSIVE MODE;

-- See current table locks
SELECT pid, relation::regclass, mode, granted
FROM pg_locks
WHERE relation = 'accounts'::regclass;
```

### Lock Conflicts Matrix

```
                     Requested Lock →
Held Lock ↓       AS   RS   RE   SUE  S    SRE  E    AE
ACCESS SHARE       .    .    .    .    .    .    .    X
ROW SHARE          .    .    .    .    .    .    X    X
ROW EXCLUSIVE      .    .    .    .    X    X    X    X
SHARE UPD EXCL     .    .    .    X    .    X    X    X
SHARE              .    .    X    X    .    X    X    X
SHARE ROW EXCL     .    .    X    X    X    X    X    X
EXCLUSIVE          .    X    X    X    X    X    X    X
ACCESS EXCLUSIVE   X    X    X    X    X    X    X    X

X = conflict (blocks)    . = compatible (does not block)
```

---

## 9. Row-Level Locks

Row-level locks are taken automatically by `UPDATE` and `DELETE`, but can also be requested explicitly.

### SELECT FOR UPDATE

> Locks selected rows for update. Other transactions trying to lock or update the same rows will **wait**.

```sql
BEGIN;
    -- Lock Alice's row — prevents other transactions from updating it
    SELECT * FROM accounts WHERE id = 1 FOR UPDATE;

    -- Now safely update
    UPDATE accounts SET balance = balance - 500 WHERE id = 1;
COMMIT;
```

### SELECT FOR SHARE

> Allows multiple readers to share the lock, but blocks writers.

```sql
BEGIN;
    SELECT * FROM accounts WHERE id = 1 FOR SHARE;
    -- Other sessions can also SELECT FOR SHARE
    -- But no session can UPDATE/DELETE until we commit
COMMIT;
```

### NOWAIT — Fail Immediately Instead of Waiting

```sql
BEGIN;
    SELECT * FROM accounts WHERE id = 1 FOR UPDATE NOWAIT;
    -- If the row is already locked by another transaction:
    -- ERROR: could not obtain lock on row in relation "accounts"
    -- Fail fast — useful when you cannot afford to wait
COMMIT;
```

### SKIP LOCKED — Skip Locked Rows

```sql
-- Useful for queue processing — grab the next available row
BEGIN;
    SELECT * FROM orders
    WHERE status = 'pending'
    ORDER BY created_at
    LIMIT 1
    FOR UPDATE SKIP LOCKED;
    -- Returns first unlocked pending order
    -- If all rows are locked by other workers, returns nothing (no wait)
COMMIT;
```

### Row Lock Modes

| Mode | Syntax | Description |
|------|--------|-------------|
| `FOR UPDATE` | `SELECT ... FOR UPDATE` | Exclusive row lock — blocks all other row locks |
| `FOR NO KEY UPDATE` | `SELECT ... FOR NO KEY UPDATE` | Like FOR UPDATE but allows FOR KEY SHARE |
| `FOR SHARE` | `SELECT ... FOR SHARE` | Shared lock — blocks UPDATE/DELETE |
| `FOR KEY SHARE` | `SELECT ... FOR KEY SHARE` | Weakest lock — only blocks FOR UPDATE |

```sql
-- Lock specific tables in a multi-table query
SELECT a.*, o.*
FROM accounts a
JOIN orders o ON o.customer_id = a.id
WHERE a.id = 1
FOR UPDATE OF a          -- lock only the accounts row
FOR SHARE  OF o;         -- share-lock the orders rows
```

---

## 10. Advisory Locks

Application-level locks managed manually. Not tied to database objects — identified by integer IDs.

```sql
-- Session-level advisory lock (held until released or session ends)
SELECT pg_advisory_lock(12345);          -- acquire (blocks if held by another)
SELECT pg_advisory_unlock(12345);        -- release

-- Try to acquire without waiting
SELECT pg_try_advisory_lock(12345);      -- returns true if acquired, false if not

-- Transaction-level advisory lock (auto-released on COMMIT/ROLLBACK)
BEGIN;
SELECT pg_advisory_xact_lock(12345);     -- auto-released at transaction end
-- no manual unlock needed
COMMIT;

-- Shared advisory lock (multiple sessions can hold simultaneously)
SELECT pg_advisory_lock_shared(12345);
SELECT pg_advisory_unlock_shared(12345);

-- View current advisory locks
SELECT pid, classid, objid, mode, granted
FROM pg_locks
WHERE locktype = 'advisory';
```

### Advisory Lock Use Cases

```sql
-- Use case 1: Prevent duplicate cron job execution
-- Only one instance runs at a time
CREATE OR REPLACE FUNCTION run_nightly_job() RETURNS void AS $$
BEGIN
    IF NOT pg_try_advisory_lock(999) THEN
        RAISE NOTICE 'Job already running — skipping';
        RETURN;
    END IF;
    -- ... do the job ...
    PERFORM pg_advisory_unlock(999);
END;
$$ LANGUAGE plpgsql;

-- Use case 2: Application-level mutex per user
-- Lock per user ID to prevent double-spend
BEGIN;
    SELECT pg_advisory_xact_lock(user_id)  -- user_id as lock key
    FROM accounts WHERE owner = 'Alice';
    -- Now safely process Alice's transaction
    UPDATE accounts SET balance = balance - 100 WHERE owner = 'Alice';
COMMIT;  -- lock auto-released
```

---

## 11. Deadlocks

A **deadlock** occurs when two or more transactions are each waiting for the other to release a lock — creating a circular dependency that can never resolve.

### Deadlock Example

```sql
-- Session A:
BEGIN;
UPDATE accounts SET balance = balance - 100 WHERE id = 1;  -- Locks row 1
-- Now waiting for row 2...

-- Session B (concurrent):
BEGIN;
UPDATE accounts SET balance = balance - 100 WHERE id = 2;  -- Locks row 2
UPDATE accounts SET balance = balance + 100 WHERE id = 1;  -- WAITS for row 1 (held by A)

-- Session A continues:
UPDATE accounts SET balance = balance + 100 WHERE id = 2;  -- WAITS for row 2 (held by B)

-- DEADLOCK: A waits for B, B waits for A
-- PostgreSQL detects this (within deadlock_timeout, default 1 second)
-- and kills one transaction:
-- ERROR:  deadlock detected
-- DETAIL: Process 1234 waits for ShareLock on transaction 5678;
--         blocked by process 5678
--         Process 5678 waits for ShareLock on transaction 1234;
--         blocked by process 1234
-- HINT:  See server log for query details.
```

### Deadlock Prevention Rules

**Rule 1: Always lock resources in a consistent order**

```sql
-- BAD: Session A locks id=1 then id=2
--      Session B locks id=2 then id=1  → deadlock possible

-- GOOD: Both sessions always lock lower id first
BEGIN;
-- Always update lower id first
UPDATE accounts SET balance = balance - 100 WHERE id = 1;  -- lower id first
UPDATE accounts SET balance = balance + 100 WHERE id = 2;  -- then higher id
COMMIT;
```

**Rule 2: Lock all rows at once using FOR UPDATE**

```sql
BEGIN;
    -- Acquire all locks upfront in one statement
    SELECT * FROM accounts
    WHERE id IN (1, 2)
    ORDER BY id                  -- consistent order
    FOR UPDATE;

    -- Now both rows are locked — no deadlock possible
    UPDATE accounts SET balance = balance - 100 WHERE id = 1;
    UPDATE accounts SET balance = balance + 100 WHERE id = 2;
COMMIT;
```

**Rule 3: Keep transactions short**

```sql
-- BAD: Long transaction holding locks
BEGIN;
    SELECT * FROM accounts FOR UPDATE;    -- locks all rows
    PERFORM pg_sleep(30);                 -- holds locks for 30 seconds!
    UPDATE accounts SET balance = 0;
COMMIT;

-- GOOD: Do heavy computation BEFORE acquiring locks
-- Compute values first...
-- THEN start short transaction
BEGIN;
    UPDATE accounts SET balance = computed_value WHERE id = 1;
COMMIT;
```

### Deadlock Configuration

```sql
-- How long to wait before checking for deadlock (default: 1s)
SHOW deadlock_timeout;

-- Set per session
SET deadlock_timeout = '500ms';

-- In postgresql.conf:
-- deadlock_timeout = 1s
-- log_lock_waits = on     (log queries that wait longer than deadlock_timeout)
```

---

## 12. Transaction Monitoring

```sql
-- ─── View all running transactions ────────────────────────────────────────────
SELECT
    pid,
    usename,
    application_name,
    state,
    wait_event_type,
    wait_event,
    now() - xact_start        AS txn_duration,
    now() - query_start        AS query_duration,
    left(query, 80)            AS current_query
FROM pg_stat_activity
WHERE state != 'idle'
ORDER BY txn_duration DESC NULLS LAST;

-- ─── Find long-running transactions ──────────────────────────────────────────
SELECT pid, usename, state,
       now() - xact_start AS duration,
       left(query, 100)   AS query
FROM pg_stat_activity
WHERE xact_start < NOW() - INTERVAL '5 minutes'
  AND state != 'idle';

-- ─── Find blocked queries ─────────────────────────────────────────────────────
SELECT
    blocked.pid                AS blocked_pid,
    blocked.usename            AS blocked_user,
    left(blocked.query, 60)    AS blocked_query,
    blocking.pid               AS blocking_pid,
    blocking.usename           AS blocking_user,
    left(blocking.query, 60)   AS blocking_query
FROM pg_stat_activity blocked
JOIN pg_stat_activity blocking
  ON blocking.pid = ANY(pg_blocking_pids(blocked.pid))
WHERE cardinality(pg_blocking_pids(blocked.pid)) > 0;

-- ─── View all current locks ───────────────────────────────────────────────────
SELECT
    l.pid,
    l.relation::regclass       AS locked_table,
    l.mode,
    l.granted,
    a.usename,
    left(a.query, 60)          AS query
FROM pg_locks l
JOIN pg_stat_activity a ON a.pid = l.pid
WHERE l.relation IS NOT NULL
ORDER BY l.pid;

-- ─── Kill a blocking session ──────────────────────────────────────────────────
SELECT pg_cancel_backend(pid);   -- cancel current query (gentle)
SELECT pg_terminate_backend(pid); -- terminate session (hard)

-- ─── Transaction ID and snapshot info ────────────────────────────────────────
SELECT txid_current();                 -- current transaction ID
SELECT txid_current_snapshot();        -- current snapshot
SELECT txid_snapshot_xmin(txid_current_snapshot());  -- oldest active txn
```

---

## 13. Optimistic vs Pessimistic Concurrency

### Pessimistic Concurrency

> **Lock first, then work.** Assumes conflicts are likely. Prevents them upfront using locks.

```sql
-- Pessimistic: Lock the row before reading and updating
BEGIN;
    SELECT balance FROM accounts
    WHERE id = 1
    FOR UPDATE;              -- lock immediately

    -- Now safe to check and update
    UPDATE accounts
    SET balance = balance - 500
    WHERE id = 1;
COMMIT;
```

**Pros:** Simple logic, no retry needed.
**Cons:** Locks held longer, reduced throughput, deadlock risk.

### Optimistic Concurrency

> **Work without locks, then check for conflicts at commit time.** Assumes conflicts are rare. Uses a version column or timestamp.

```sql
-- Add a version column
ALTER TABLE accounts ADD COLUMN version INTEGER DEFAULT 1;

-- Step 1: Read the row and note the version
SELECT id, balance, version FROM accounts WHERE id = 1;
-- Returns: balance=10000, version=5

-- Step 2: Update only if version hasn't changed
UPDATE accounts
SET balance = balance - 500,
    version = version + 1
WHERE id = 1
  AND version = 5;          -- optimistic check

-- Step 3: Check if the update actually happened
GET DIAGNOSTICS row_count = ROW_COUNT;
-- If row_count = 0: conflict! Another transaction changed the row → retry
-- If row_count = 1: success!
```

**Using timestamp instead of version:**

```sql
ALTER TABLE accounts ADD COLUMN updated_at TIMESTAMPTZ DEFAULT NOW();

-- Read
SELECT id, balance, updated_at FROM accounts WHERE id = 1;

-- Conditional update
UPDATE accounts
SET balance = balance - 500,
    updated_at = NOW()
WHERE id = 1
  AND updated_at = '2024-03-15 10:30:00+05:30';  -- must match what we read

-- If 0 rows updated → someone else changed it → retry
```

### When to Use Each

| | Pessimistic | Optimistic |
|---|---|---|
| Conflict frequency | High | Low |
| Transaction length | Short OK | Short preferred |
| Retry logic needed | No | Yes |
| Throughput | Lower | Higher |
| Best for | Financial transfers, inventory | User profiles, settings, CMS |

---

## 14. Common Concurrency Patterns

### Pattern 1: Safe Money Transfer

```sql
CREATE OR REPLACE FUNCTION transfer_money(
    from_id INTEGER,
    to_id   INTEGER,
    amount  NUMERIC
) RETURNS void AS $$
BEGIN
    -- Lock both rows in consistent order (lower id first) to prevent deadlocks
    PERFORM id FROM accounts
    WHERE id IN (from_id, to_id)
    ORDER BY id
    FOR UPDATE;

    -- Check sufficient funds
    IF (SELECT balance FROM accounts WHERE id = from_id) < amount THEN
        RAISE EXCEPTION 'Insufficient funds in account %', from_id;
    END IF;

    -- Perform transfer
    UPDATE accounts SET balance = balance - amount WHERE id = from_id;
    UPDATE accounts SET balance = balance + amount WHERE id = to_id;
END;
$$ LANGUAGE plpgsql;

-- Usage:
BEGIN;
SELECT transfer_money(1, 2, 500);
COMMIT;
```

### Pattern 2: Queue Worker with SKIP LOCKED

```sql
-- Multiple workers process orders concurrently without overlap
CREATE OR REPLACE FUNCTION claim_next_order(worker_id TEXT)
RETURNS TABLE (order_id INT, product TEXT) AS $$
BEGIN
    RETURN QUERY
    UPDATE orders
    SET status = 'processing'
    WHERE id = (
        SELECT id FROM orders
        WHERE status = 'pending'
        ORDER BY created_at
        LIMIT 1
        FOR UPDATE SKIP LOCKED   -- skip rows locked by other workers
    )
    RETURNING id, product;
END;
$$ LANGUAGE plpgsql;

-- Worker 1:
BEGIN;
SELECT * FROM claim_next_order('worker-1');
-- Processes the order...
UPDATE orders SET status = 'done' WHERE id = :order_id;
COMMIT;
```

### Pattern 3: Upsert (INSERT ON CONFLICT)

```sql
-- Atomic insert-or-update — no race condition
INSERT INTO inventory (product_id, name, stock)
VALUES (101, 'Laptop', 10)
ON CONFLICT (product_id) DO UPDATE
    SET stock = inventory.stock + EXCLUDED.stock;

-- Insert or ignore
INSERT INTO accounts (id, owner, balance)
VALUES (1, 'Alice', 10000)
ON CONFLICT (id) DO NOTHING;

-- Conditional upsert
INSERT INTO accounts (id, owner, balance)
VALUES (1, 'Alice', 10000)
ON CONFLICT (id) DO UPDATE
    SET balance = EXCLUDED.balance
    WHERE accounts.balance < EXCLUDED.balance;  -- only update if new value is larger
```

### Pattern 4: Counter Without Lock Contention

```sql
-- BAD: High contention — all sessions lock the same row
UPDATE page_views SET count = count + 1 WHERE page = '/home';

-- GOOD: Use unlogged table or separate counter shards
CREATE TABLE page_view_shards (
    page    TEXT,
    shard   INTEGER,
    count   BIGINT DEFAULT 0,
    PRIMARY KEY (page, shard)
);

-- Each request updates a random shard (8 shards = 8x less contention)
UPDATE page_view_shards
SET count = count + 1
WHERE page = '/home'
  AND shard = floor(random() * 8)::INT;

-- Read total
SELECT SUM(count) FROM page_view_shards WHERE page = '/home';
```

### Pattern 5: SELECT ... FOR UPDATE with CTE

```sql
-- Atomically claim and return a row using CTE
WITH claimed AS (
    UPDATE orders
    SET status = 'claimed',
        claimed_at = NOW()
    WHERE id = (
        SELECT id FROM orders
        WHERE status = 'pending'
        ORDER BY created_at ASC
        LIMIT 1
        FOR UPDATE SKIP LOCKED
    )
    RETURNING *
)
SELECT * FROM claimed;
```

---

## 15. Performance & Tuning

### Lock Timeout — Fail Fast Instead of Waiting

```sql
-- Set how long to wait for a lock before erroring
SET lock_timeout = '3s';

BEGIN;
    SELECT * FROM accounts WHERE id = 1 FOR UPDATE;
    -- ERROR: canceling statement due to lock timeout (if not acquired in 3s)
COMMIT;
```

### Statement Timeout — Kill Long Queries

```sql
-- Kill any query running longer than 30 seconds
SET statement_timeout = '30s';

-- Per role in postgresql.conf
ALTER ROLE reporting_user SET statement_timeout = '5min';
```

### Idle in Transaction Timeout

```sql
-- Prevent sessions from holding transactions open indefinitely
SET idle_in_transaction_session_timeout = '5min';
-- Session holding BEGIN but doing nothing → killed after 5 minutes
```

### Transaction Pool Settings (postgresql.conf)

```
max_connections          = 100      -- max simultaneous connections
shared_buffers           = 256MB    -- memory for shared data cache
work_mem                 = 64MB     -- per-operation sort/hash memory
deadlock_timeout         = 1s       -- time before deadlock check
lock_timeout             = 0        -- 0 = wait forever (set per session)
idle_in_transaction_session_timeout = 0   -- 0 = no timeout
log_lock_waits           = on       -- log queries waiting for locks
log_min_duration_statement = 1000   -- log queries over 1 second
```

### Index for Concurrency

```sql
-- CREATE INDEX CONCURRENTLY — does not block reads or writes
CREATE INDEX CONCURRENTLY idx_orders_status
ON orders (status)
WHERE status = 'pending';   -- partial index

-- DROP INDEX CONCURRENTLY — does not block
DROP INDEX CONCURRENTLY idx_orders_status;
```

### Connection Pooling

```
Direct connections are expensive — use a connection pooler.

PgBouncer modes:
  Session pooling    — one server connection per client session
  Transaction pooling — one server connection per transaction (recommended)
  Statement pooling  — one connection per statement (most aggressive)

Typical setup:
  Application → PgBouncer (port 6432) → PostgreSQL (port 5432)

Transaction pooling config (pgbouncer.ini):
  pool_mode = transaction
  max_client_conn = 1000
  default_pool_size = 20
```

---

## 16. Quick Reference Cheat Sheet

```
╔══════════════════════════╦═══════════════════════════════════════════════════╗
║ TOPIC                    ║ KEY COMMANDS / NOTES                              ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Transaction Control      ║ BEGIN / COMMIT / ROLLBACK                         ║
║                          ║ START TRANSACTION ISOLATION LEVEL ...             ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Savepoints               ║ SAVEPOINT name                                    ║
║                          ║ ROLLBACK TO SAVEPOINT name                        ║
║                          ║ RELEASE SAVEPOINT name                            ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Isolation Levels         ║ READ COMMITTED    (default) — fresh snapshot/stmt ║
║                          ║ REPEATABLE READ   — snapshot frozen at txn start  ║
║                          ║ SERIALIZABLE      — full serial equivalence (SSI) ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Anomalies                ║ Dirty Read         — prevented by all levels      ║
║                          ║ Non-Repeatable     — prevented by REPEATABLE READ ║
║                          ║ Phantom Read       — prevented by SERIALIZABLE    ║
║                          ║ Write Skew         — prevented by SERIALIZABLE    ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ MVCC                     ║ Readers never block writers                       ║
║                          ║ Writers never block readers                       ║
║                          ║ xmin / xmax hidden columns track visibility       ║
║                          ║ VACUUM reclaims dead tuples                       ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Row Locks                ║ FOR UPDATE         — exclusive, block all         ║
║                          ║ FOR NO KEY UPDATE  — exclusive, allow KEY SHARE   ║
║                          ║ FOR SHARE          — shared, block writes         ║
║                          ║ FOR KEY SHARE      — weakest, block FOR UPDATE    ║
║                          ║ NOWAIT             — error instead of wait        ║
║                          ║ SKIP LOCKED        — skip rows locked by others   ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Advisory Locks           ║ pg_advisory_lock(id)                              ║
║                          ║ pg_try_advisory_lock(id)  → returns bool          ║
║                          ║ pg_advisory_xact_lock(id) → auto-released         ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Deadlock Prevention      ║ Always lock in consistent (e.g. ascending) order  ║
║                          ║ Acquire all locks upfront with FOR UPDATE         ║
║                          ║ Keep transactions short                           ║
║                          ║ Use NOWAIT to fail fast                           ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Monitoring               ║ pg_stat_activity   — running sessions/queries     ║
║                          ║ pg_locks           — current lock holders         ║
║                          ║ pg_blocking_pids() — who is blocking whom         ║
║                          ║ pg_cancel_backend  — cancel a query               ║
║                          ║ pg_terminate_backend — kill a session             ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Timeouts                 ║ lock_timeout                — fail if lock waits  ║
║                          ║ statement_timeout           — kill long queries   ║
║                          ║ deadlock_timeout            — deadlock check      ║
║                          ║ idle_in_transaction_...     — kill idle txns      ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Concurrency Patterns     ║ SKIP LOCKED         — queue worker pattern        ║
║                          ║ ON CONFLICT DO UPDATE — atomic upsert             ║
║                          ║ Sharded counters    — reduce hot row contention   ║
║                          ║ Optimistic locking  — version column + retry      ║
║                          ║ Advisory locks      — app-level mutex             ║
╚══════════════════════════╩═══════════════════════════════════════════════════╝
```

---

## Further Reading

- [PostgreSQL Docs — Transactions](https://www.postgresql.org/docs/current/tutorial-transactions.html)
- [PostgreSQL Docs — Concurrency Control](https://www.postgresql.org/docs/current/mvcc.html)
- [PostgreSQL Docs — Explicit Locking](https://www.postgresql.org/docs/current/explicit-locking.html)
- [PostgreSQL Docs — Transaction Isolation](https://www.postgresql.org/docs/current/transaction-iso.html)
- [PostgreSQL Docs — VACUUM](https://www.postgresql.org/docs/current/sql-vacuum.html)
- [PostgreSQL Docs — pg_stat_activity](https://www.postgresql.org/docs/current/monitoring-stats.html)

---

*Generated with love for PostgreSQL engineers.*
