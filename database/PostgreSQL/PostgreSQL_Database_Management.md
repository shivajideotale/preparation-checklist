# PostgreSQL — Database Management Complete Reference

> A deep-dive guide covering configuration, connection management, VACUUM, autovacuum, ANALYZE, bloat, tablespaces, extensions, upgrades, monitoring, security hardening, and day-to-day DBA operations in PostgreSQL.

---

## Table of Contents

1.  [PostgreSQL Configuration Files](#1-postgresql-configuration-files)
2.  [Connection Management](#2-connection-management)
3.  [Authentication (pg_hba.conf)](#3-authentication-pg_hbaconf)
4.  [Database & Schema Management](#4-database--schema-management)
5.  [VACUUM & Dead Tuple Management](#5-vacuum--dead-tuple-management)
6.  [ANALYZE & Statistics Management](#6-analyze--statistics-management)
7.  [Autovacuum Tuning](#7-autovacuum-tuning)
8.  [Table & Index Bloat](#8-table--index-bloat)
9.  [Tablespaces](#9-tablespaces)
10. [Extensions Management](#10-extensions-management)
11. [System Catalog & Metadata](#11-system-catalog--metadata)
12. [Server Monitoring](#12-server-monitoring)
13. [Lock Management](#13-lock-management)
14. [PostgreSQL Upgrades](#14-postgresql-upgrades)
15. [Security Hardening](#15-security-hardening)
16. [Routine DBA Operations Checklist](#16-routine-dba-operations-checklist)
17. [Quick Reference Cheat Sheet](#17-quick-reference-cheat-sheet)

---

## 1. PostgreSQL Configuration Files

### File Locations

```bash
# Find config files from inside PostgreSQL
SELECT name, setting FROM pg_settings
WHERE name IN ('config_file','hba_file','ident_file','data_directory');

# Typical locations
$PGDATA/postgresql.conf      # main configuration
$PGDATA/pg_hba.conf          # client authentication
$PGDATA/pg_ident.conf        # ident mapping
$PGDATA/postgresql.auto.conf # ALTER SYSTEM writes here (overrides postgresql.conf)

# On Debian/Ubuntu
/etc/postgresql/16/main/postgresql.conf
/etc/postgresql/16/main/pg_hba.conf
/var/lib/postgresql/16/main/   # $PGDATA

# On RHEL/Amazon Linux
/var/lib/pgsql/16/data/postgresql.conf
/var/lib/pgsql/16/data/pg_hba.conf
```

### postgresql.conf — Key Parameters

```ini
# ─── Connection ─────────────────────────────────────────────────────────────
listen_addresses    = '*'           # listen on all interfaces (default: 'localhost')
port                = 5432          # default port
max_connections     = 200           # max simultaneous connections
superuser_reserved_connections = 3  # reserved for superuser (never set to 0)

# ─── Memory ─────────────────────────────────────────────────────────────────
shared_buffers            = 4GB     # 25% of RAM (most important setting)
effective_cache_size      = 12GB    # 75% of RAM (hint to planner, doesn't allocate)
work_mem                  = 64MB    # per sort/hash operation (careful: × connections)
maintenance_work_mem      = 1GB     # for VACUUM, CREATE INDEX, ALTER TABLE
wal_buffers               = 64MB    # WAL write buffer (auto-tuned from shared_buffers)
temp_buffers              = 16MB    # temporary table cache per session

# ─── WAL & Checkpoints ──────────────────────────────────────────────────────
wal_level                 = replica # minimal | replica | logical
fsync                     = on      # NEVER turn off in production
synchronous_commit        = on      # on | off | local | remote_write | remote_apply
wal_compression           = on      # compress WAL (CPU vs I/O trade-off)
checkpoint_timeout        = 10min   # max time between checkpoints
max_wal_size              = 4GB     # WAL grows up to this before forced checkpoint
min_wal_size              = 512MB   # minimum WAL kept on disk
checkpoint_completion_target = 0.9  # spread checkpoint I/O over 90% of interval
archive_mode              = off     # on for WAL archiving / PITR
archive_command           = ''      # command to copy WAL files to archive

# ─── Query Planner ──────────────────────────────────────────────────────────
random_page_cost          = 1.1     # 1.1 for SSD, 4.0 for HDD
seq_page_cost             = 1.0     # baseline sequential page cost
default_statistics_target = 100     # histogram buckets (increase for better estimates)
enable_partitionwise_join  = on
enable_partitionwise_aggregate = on

# ─── Parallelism ────────────────────────────────────────────────────────────
max_parallel_workers_per_gather = 4
max_parallel_workers            = 8
max_parallel_maintenance_workers = 4  # for CREATE INDEX, VACUUM

# ─── Logging ────────────────────────────────────────────────────────────────
logging_collector         = on
log_directory             = 'pg_log'
log_filename              = 'postgresql-%Y-%m-%d.log'
log_rotation_age          = 1d
log_rotation_size         = 100MB
log_min_duration_statement = 1000   # log queries taking > 1 second (ms)
log_checkpoints           = on      # log checkpoint activity
log_connections           = off     # log each new connection (noisy in OLTP)
log_disconnections        = off
log_lock_waits            = on      # log waits for locks > deadlock_timeout
log_temp_files            = 10MB    # log temp files > 10MB (sort/hash spills)
log_autovacuum_min_duration = 250ms # log autovacuum runs > 250ms
log_line_prefix           = '%m [%p] %q%u@%d '  # timestamp [pid] user@db
log_statement             = 'none'  # none | ddl | mod | all

# ─── Autovacuum ─────────────────────────────────────────────────────────────
autovacuum                = on      # NEVER turn off
autovacuum_max_workers    = 5       # parallel autovacuum workers
autovacuum_naptime        = 1min    # time between autovacuum checks
autovacuum_vacuum_scale_factor   = 0.10   # vacuum when 10% of rows are dead
autovacuum_analyze_scale_factor  = 0.05   # analyze when 5% rows changed
autovacuum_vacuum_cost_limit     = 400    # I/O budget per autovacuum run
autovacuum_vacuum_insert_scale_factor = 0.10 # vacuum after 10% inserts (PG 13+)

# ─── Locking & Deadlock ─────────────────────────────────────────────────────
deadlock_timeout          = 1s      # how long to wait before deadlock check
lock_timeout              = 0       # 0 = wait forever (set per session for safety)
statement_timeout         = 0       # 0 = no limit (set per role in production)
idle_in_transaction_session_timeout = 30min  # kill idle-in-txn sessions

# ─── Performance Misc ───────────────────────────────────────────────────────
jit                       = on      # JIT compilation for analytical queries
shared_preload_libraries  = 'pg_stat_statements,auto_explain'
track_io_timing           = on      # enables I/O timing in pg_stat_statements
track_activity_query_size = 4096    # max query text size in pg_stat_activity
```

### ALTER SYSTEM — Change Config Without Editing Files

```sql
-- Change a parameter dynamically (writes to postgresql.auto.conf)
ALTER SYSTEM SET shared_buffers      = '8GB';
ALTER SYSTEM SET work_mem            = '128MB';
ALTER SYSTEM SET max_connections     = 300;
ALTER SYSTEM SET log_min_duration_statement = 500;

-- Reset a parameter to its default (removes from postgresql.auto.conf)
ALTER SYSTEM RESET work_mem;
ALTER SYSTEM RESET ALL;          -- reset everything

-- Reload config (no restart needed for most parameters)
SELECT pg_reload_conf();         -- SQL
-- or from OS:
-- pg_ctl reload
-- sudo systemctl reload postgresql

-- Check which parameters need restart
SELECT name, setting, pending_restart
FROM pg_settings
WHERE pending_restart = true;

-- See what's in postgresql.auto.conf
SELECT * FROM pg_file_settings
WHERE sourcefile LIKE '%auto.conf'
ORDER BY seqno;

-- Check effective value vs config file value
SELECT name, setting, unit, source, sourcefile
FROM pg_settings
WHERE name = 'work_mem';
```

### Applying Config Changes

```sql
-- Reload only (no downtime) — for most parameters:
SELECT pg_reload_conf();

-- Restart required for:
-- shared_buffers, max_connections, wal_level,
-- shared_preload_libraries, listen_addresses, port

-- Check which parameters require restart:
SELECT name, context FROM pg_settings
WHERE context IN ('postmaster', 'backend')
ORDER BY context, name;

-- context = 'postmaster' → requires server restart
-- context = 'sighup'     → pg_reload_conf() is enough
-- context = 'user'       → can be set per session with SET
```

---

## 2. Connection Management

### Viewing Connections

```sql
-- All current connections
SELECT
    pid,
    usename                               AS username,
    datname                               AS database,
    application_name,
    client_addr,
    client_port,
    state,
    wait_event_type,
    wait_event,
    now() - backend_start                 AS connection_age,
    now() - state_change                  AS state_duration,
    now() - query_start                   AS query_running_for,
    LEFT(query, 80)                       AS current_query
FROM pg_stat_activity
WHERE pid <> pg_backend_pid()             -- exclude current connection
ORDER BY query_start NULLS LAST;

-- Connection count by database
SELECT datname, COUNT(*) AS connections
FROM pg_stat_activity
GROUP BY datname
ORDER BY connections DESC;

-- Connection count by user
SELECT usename, COUNT(*) AS connections,
       SUM(CASE WHEN state='active' THEN 1 ELSE 0 END) AS active
FROM pg_stat_activity
GROUP BY usename
ORDER BY connections DESC;

-- Near the connection limit?
SELECT
    MAX(setting)::INT                     AS max_connections,
    COUNT(*)                              AS current_connections,
    MAX(setting)::INT - COUNT(*)          AS available,
    ROUND(100.0 * COUNT(*) / MAX(setting)::INT, 1) AS used_pct
FROM pg_stat_activity, pg_settings
WHERE name = 'max_connections';
```

### Killing Connections

```sql
-- Gracefully terminate a query (lets transaction finish)
SELECT pg_cancel_backend(pid);

-- Forcefully terminate a connection immediately
SELECT pg_terminate_backend(pid);

-- Cancel all queries from a specific user
SELECT pg_cancel_backend(pid)
FROM pg_stat_activity
WHERE usename = 'reporting_user'
  AND state = 'active'
  AND pid <> pg_backend_pid();

-- Terminate all idle connections older than 30 minutes
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE state = 'idle'
  AND now() - state_change > INTERVAL '30 minutes'
  AND pid <> pg_backend_pid();

-- Terminate all connections to a specific database (for maintenance)
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE datname = 'mydb'
  AND pid <> pg_backend_pid();

-- Terminate idle-in-transaction connections > 10 minutes
SELECT pg_terminate_backend(pid), usename, now() - state_change AS idle_for
FROM pg_stat_activity
WHERE state = 'idle in transaction'
  AND now() - state_change > INTERVAL '10 minutes';
```

### Connection Limits per Database / Role

```sql
-- Limit connections to a database
ALTER DATABASE myapp CONNECTION LIMIT 100;

-- Limit connections for a role
ALTER ROLE app_user CONNECTION LIMIT 50;
ALTER ROLE reporting_user CONNECTION LIMIT 10;

-- Remove limit
ALTER DATABASE myapp CONNECTION LIMIT -1;
ALTER ROLE app_user CONNECTION LIMIT -1;

-- Check current limits
SELECT datname, datconnlimit FROM pg_database ORDER BY datname;
SELECT rolname, rolconnlimit FROM pg_roles WHERE rolconnlimit > 0;
```

### PgBouncer Integration Check

```sql
-- Identify PgBouncer connections (they use a single user with many virtual connections)
SELECT
    application_name,
    usename,
    COUNT(*)                              AS pooled_connections,
    SUM(CASE WHEN state='active' THEN 1 ELSE 0 END) AS active
FROM pg_stat_activity
WHERE application_name ILIKE '%pgbouncer%'
   OR application_name ILIKE '%bounce%'
GROUP BY application_name, usename;
```

---

## 3. Authentication (pg_hba.conf)

`pg_hba.conf` controls **who** can connect, **from where**, and **how** they must authenticate.

### File Format

```
# TYPE  DATABASE  USER         ADDRESS          METHOD       OPTIONS
local   all       postgres                      peer
host    all       all          127.0.0.1/32     scram-sha-256
host    all       all          ::1/128          scram-sha-256
host    myapp     myapp_user   10.0.0.0/8       scram-sha-256
host    all       all          0.0.0.0/0        reject
```

### Connection Types

```ini
# local    — Unix domain socket (same machine, no TCP)
# host     — TCP/IP (encrypted or unencrypted)
# hostssl  — TCP/IP with SSL required
# hostnossl— TCP/IP without SSL
# hostgssenc — TCP/IP with GSSAPI encryption

local   all   postgres   peer             # OS user 'postgres' → DB user 'postgres'
host    mydb  app_user   192.168.1.0/24   scram-sha-256
hostssl all   all        0.0.0.0/0        scram-sha-256   # SSL required
```

### Authentication Methods

```ini
# trust         — no password (DANGEROUS in production)
# reject        — always deny
# peer          — OS username must match DB username (local only)
# ident         — use ident server to verify username
# password      — plain-text password (NEVER use — insecure)
# md5           — MD5 hashed password (legacy, use scram instead)
# scram-sha-256 — SCRAM-SHA-256 (RECOMMENDED — most secure)
# gss           — GSSAPI/Kerberos
# sspi          — Windows SSPI
# ldap          — LDAP authentication
# radius        — RADIUS server
# cert          — SSL certificate authentication

# Best practice pg_hba.conf for production:
local   all          postgres                    peer
local   all          all                         scram-sha-256
host    all          all          127.0.0.1/32   scram-sha-256
host    all          all          ::1/128        scram-sha-256
host    myapp        myapp_user   10.0.0.0/8     scram-sha-256
host    replication  replicator   10.0.0.0/8     scram-sha-256
host    all          all          0.0.0.0/0      reject        # deny all others
```

### Reload After Changes

```bash
# Reload pg_hba.conf without restart
pg_ctl reload -D $PGDATA
# or
sudo systemctl reload postgresql
# or from SQL:
SELECT pg_reload_conf();

# Verify new rules are loaded
SELECT * FROM pg_hba_file_rules;
```

### Password Management

```sql
-- Set password (always use SCRAM)
ALTER USER myapp_user PASSWORD 'strong_password_here';

-- Check password encryption method
SHOW password_encryption;         -- should be 'scram-sha-256'

-- Force scram-sha-256 for all new passwords
ALTER SYSTEM SET password_encryption = 'scram-sha-256';
SELECT pg_reload_conf();

-- Set password expiry
ALTER ROLE myapp_user VALID UNTIL '2025-12-31 23:59:59';

-- Remove password expiry
ALTER ROLE myapp_user VALID UNTIL 'infinity';

-- Check role password info
SELECT rolname, rolvaliduntil, rolpassword IS NOT NULL AS has_password
FROM pg_authid
WHERE rolcanlogin = true
ORDER BY rolname;
```

---

## 4. Database & Schema Management

### Database Operations

```sql
-- Create with full options
CREATE DATABASE production
    OWNER     = prod_owner
    ENCODING  = 'UTF8'
    LC_COLLATE = 'en_US.UTF-8'
    LC_CTYPE   = 'en_US.UTF-8'
    TEMPLATE  = template0
    TABLESPACE = pg_default
    CONNECTION LIMIT = 500;

-- Clone a database (uses it as template)
CREATE DATABASE staging TEMPLATE production;
-- WARNING: all connections to 'production' must be closed first

-- Rename
ALTER DATABASE staging RENAME TO uat;

-- Change owner
ALTER DATABASE production OWNER TO new_owner;

-- Change tablespace (moves all objects)
ALTER DATABASE production SET TABLESPACE fast_ssd;

-- Set session defaults for a database
ALTER DATABASE production SET work_mem = '128MB';
ALTER DATABASE production SET search_path = myschema, public;
ALTER DATABASE production SET timezone = 'Asia/Kolkata';
ALTER DATABASE production SET log_min_duration_statement = 1000;

-- Drop (must not be connected to it)
DROP DATABASE IF EXISTS staging;

-- Force drop (PG 13+: disconnect all connections first)
DROP DATABASE staging WITH (FORCE);

-- Database sizes
SELECT
    datname,
    pg_size_pretty(pg_database_size(datname)) AS size,
    pg_database_size(datname)                  AS size_bytes
FROM pg_database
WHERE datistemplate = false
ORDER BY pg_database_size(datname) DESC;
```

### Schema Operations

```sql
-- Create schema
CREATE SCHEMA IF NOT EXISTS analytics;
CREATE SCHEMA sales AUTHORIZATION sales_manager;

-- Rename schema
ALTER SCHEMA analytics RENAME TO reporting;

-- Change owner
ALTER SCHEMA analytics OWNER TO analytics_admin;

-- Drop schema
DROP SCHEMA analytics;           -- fails if not empty
DROP SCHEMA analytics CASCADE;   -- drops all objects inside
DROP SCHEMA IF EXISTS analytics CASCADE;

-- List schemas with sizes
SELECT
    n.nspname                                    AS schema_name,
    r.rolname                                    AS owner,
    pg_size_pretty(
        SUM(pg_total_relation_size(c.oid))
    )                                            AS total_size
FROM pg_namespace n
JOIN pg_roles r ON r.oid = n.nspowner
LEFT JOIN pg_class c ON c.relnamespace = n.oid
WHERE n.nspname NOT IN ('pg_catalog','information_schema','pg_toast')
GROUP BY n.nspname, r.rolname
ORDER BY SUM(pg_total_relation_size(c.oid)) DESC NULLS LAST;

-- Move a table to a different schema
ALTER TABLE public.employees SET SCHEMA hr;
```

### Object Sizes

```sql
-- Table sizes (with indexes)
SELECT
    relname                                       AS table_name,
    pg_size_pretty(pg_relation_size(oid))         AS table_size,
    pg_size_pretty(pg_indexes_size(oid))          AS index_size,
    pg_size_pretty(pg_total_relation_size(oid))   AS total_size,
    pg_total_relation_size(oid)                   AS total_bytes
FROM pg_class
WHERE relkind = 'r'
  AND relnamespace = 'public'::regnamespace
ORDER BY pg_total_relation_size(oid) DESC
LIMIT 20;

-- Top 10 largest objects in entire cluster
SELECT
    n.nspname || '.' || c.relname              AS object_name,
    CASE c.relkind
        WHEN 'r' THEN 'table'
        WHEN 'i' THEN 'index'
        WHEN 'S' THEN 'sequence'
        WHEN 'v' THEN 'view'
        WHEN 'm' THEN 'mat_view'
        WHEN 't' THEN 'toast'
    END                                        AS type,
    pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r','i','m')
  AND n.nspname NOT IN ('pg_catalog','information_schema','pg_toast')
ORDER BY pg_total_relation_size(c.oid) DESC
LIMIT 10;
```

---

## 5. VACUUM & Dead Tuple Management

### Why VACUUM is Critical

```
PostgreSQL uses MVCC (Multi-Version Concurrency Control):
  - Every UPDATE creates a NEW row version (does not overwrite)
  - Every DELETE marks a row as dead (does not remove immediately)
  - Dead rows accumulate → "bloat"

VACUUM does three things:
  1. Reclaims space from dead tuples (makes it available for reuse)
  2. Updates the visibility map (enables Index-Only Scans)
  3. Advances the transaction ID freeze counter (prevents XID wraparound)

Without VACUUM:
  ❌ Tables grow indefinitely (never reclaim dead row space)
  ❌ Index-Only Scans degrade (visibility map stale)
  ❌ XID wraparound → DATABASE SHUTDOWN (catastrophic!)
```

### Manual VACUUM Commands

```sql
-- Basic VACUUM (reclaim dead tuples, update visibility map)
VACUUM employees;

-- VACUUM + ANALYZE (reclaim + update statistics)
VACUUM ANALYZE employees;

-- VACUUM FULL (compact table, reclaim disk space to OS)
-- WARNING: acquires exclusive lock — blocks all reads and writes!
-- Use only during maintenance windows
VACUUM FULL employees;

-- VACUUM FREEZE (aggressively freeze old XIDs — for XID wraparound prevention)
VACUUM FREEZE employees;

-- VERBOSE output (see what VACUUM is doing)
VACUUM VERBOSE employees;
VACUUM (VERBOSE, ANALYZE) employees;

-- VACUUM with all options
VACUUM (
    FULL     false,    -- full table rewrite (exclusive lock)
    FREEZE   false,    -- freeze old tuples
    VERBOSE  true,     -- print progress
    ANALYZE  true,     -- update statistics
    DISABLE_PAGE_SKIPPING false,  -- don't skip any pages
    SKIP_LOCKED         true,     -- skip tables we can't lock
    INDEX_CLEANUP       auto,     -- clean up indexes
    TRUNCATE            true,     -- truncate trailing empty pages
    PARALLEL            4         -- parallel workers (PG 13+)
) employees;

-- VACUUM entire database
VACUUM;
VACUUM ANALYZE;

-- VACUUM all tables in a schema
DO $$
DECLARE r RECORD;
BEGIN
    FOR r IN
        SELECT tablename FROM pg_tables WHERE schemaname = 'public'
    LOOP
        EXECUTE 'VACUUM ANALYZE ' || quote_ident(r.tablename);
        RAISE NOTICE 'Vacuumed: %', r.tablename;
    END LOOP;
END $$;
```

### Monitoring VACUUM Health

```sql
-- Dead tuple ratio per table (> 20% = VACUUM needed urgently)
SELECT
    schemaname,
    relname                                   AS table_name,
    n_live_tup                                AS live_rows,
    n_dead_tup                                AS dead_rows,
    ROUND(100.0 * n_dead_tup
          / NULLIF(n_live_tup + n_dead_tup, 0), 1) AS dead_pct,
    last_vacuum,
    last_autovacuum,
    vacuum_count,
    autovacuum_count
FROM pg_stat_user_tables
ORDER BY dead_pct DESC NULLS LAST
LIMIT 20;

-- Tables that need VACUUM most urgently
SELECT
    relname,
    n_dead_tup,
    n_live_tup,
    ROUND(100.0 * n_dead_tup / NULLIF(n_live_tup + n_dead_tup, 0), 1) AS dead_pct,
    last_autovacuum,
    now() - last_autovacuum AS since_last_vacuum
FROM pg_stat_user_tables
WHERE n_dead_tup > 10000
ORDER BY n_dead_tup DESC
LIMIT 10;

-- XID age — CRITICAL: must be < 2 billion!
SELECT
    datname,
    age(datfrozenxid)                         AS xid_age,
    2000000000 - age(datfrozenxid)            AS xids_remaining,
    ROUND(100.0 * age(datfrozenxid) / 2000000000.0, 2) AS pct_used
FROM pg_database
ORDER BY age(datfrozenxid) DESC;
-- Alert if xid_age > 1,500,000,000 (75% of limit)

-- Table-level XID age
SELECT
    relname,
    age(relfrozenxid)                         AS xid_age,
    2000000000 - age(relfrozenxid)            AS xids_remaining
FROM pg_class
WHERE relkind = 'r'
ORDER BY age(relfrozenxid) DESC
LIMIT 10;

-- Currently running VACUUM processes
SELECT
    pid,
    phase,
    relid::regclass                           AS table_name,
    heap_blks_scanned,
    heap_blks_vacuumed,
    index_vacuum_count,
    num_dead_tuples,
    now() - xact_start                        AS running_for
FROM pg_stat_progress_vacuum;
```

---

## 6. ANALYZE & Statistics Management

### Running ANALYZE

```sql
-- Analyze a single table (update planner statistics)
ANALYZE employees;

-- Analyze specific columns only
ANALYZE employees (salary, department, joined_at);

-- Analyze all tables in current database
ANALYZE;
ANALYZE VERBOSE;   -- with progress output

-- Analyze with increased statistics target
ALTER TABLE orders ALTER COLUMN status SET STATISTICS 500;
ANALYZE orders;

-- Check when tables were last analyzed
SELECT
    relname,
    last_analyze,
    last_autoanalyze,
    n_mod_since_analyze,
    ROUND(100.0 * n_mod_since_analyze / NULLIF(n_live_tup, 0), 1) AS mod_pct
FROM pg_stat_user_tables
WHERE n_mod_since_analyze > 0
ORDER BY mod_pct DESC NULLS LAST
LIMIT 20;
```

### Viewing Statistics

```sql
-- Column-level statistics
SELECT
    tablename,
    attname                                   AS column_name,
    n_distinct,
    correlation,
    most_common_vals,
    most_common_freqs,
    histogram_bounds
FROM pg_stats
WHERE tablename = 'orders'
  AND attname IN ('status', 'customer_id', 'amount')
ORDER BY attname;

-- Check if statistics are stale for high-traffic tables
SELECT
    relname,
    n_live_tup,
    n_mod_since_analyze,
    ROUND(100.0 * n_mod_since_analyze / NULLIF(n_live_tup,0), 1) AS mod_pct,
    last_autoanalyze
FROM pg_stat_user_tables
WHERE n_mod_since_analyze::FLOAT / NULLIF(n_live_tup,0) > 0.10
ORDER BY mod_pct DESC;
```

---

## 7. Autovacuum Tuning

Autovacuum runs automatically in the background. Tuning it correctly is critical for performance.

### How Autovacuum Triggers

```
Autovacuum VACUUM triggers when:
  dead_tuples > autovacuum_vacuum_threshold + autovacuum_vacuum_scale_factor × n_live_tup

Default:
  vacuum_threshold     = 50 rows
  vacuum_scale_factor  = 0.20  (20% of table)

For a 1M row table:
  50 + 0.20 × 1,000,000 = 200,050 dead rows before autovacuum triggers
  → Too many dead rows accumulate before cleanup!

Solution: lower scale_factor for large tables.

Autovacuum ANALYZE triggers when:
  changes > autovacuum_analyze_threshold + autovacuum_analyze_scale_factor × n_live_tup
```

### Global Autovacuum Settings

```sql
-- View current autovacuum settings
SELECT name, setting, unit, short_desc
FROM pg_settings
WHERE name LIKE 'autovacuum%'
ORDER BY name;

-- Recommended production settings (in postgresql.conf)
-- autovacuum                           = on       -- NEVER turn off
-- autovacuum_max_workers               = 5        -- parallel workers
-- autovacuum_naptime                   = 30s      -- check interval (default 1min)
-- autovacuum_vacuum_threshold          = 50       -- min rows before vacuum
-- autovacuum_vacuum_scale_factor       = 0.05     -- 5% (default 20%)
-- autovacuum_analyze_threshold         = 50
-- autovacuum_analyze_scale_factor      = 0.02     -- 2% (default 5%)
-- autovacuum_vacuum_cost_limit         = 800      -- I/O throttle (default 200)
-- autovacuum_vacuum_cost_delay         = 2ms      -- pause between I/O bursts
-- autovacuum_freeze_max_age            = 200000000
```

### Per-Table Autovacuum Overrides

```sql
-- Override autovacuum settings for specific tables
-- Use this for large, high-write tables that need more aggressive vacuuming

-- High-write OLTP table: very aggressive autovacuum
ALTER TABLE orders SET (
    autovacuum_vacuum_scale_factor   = 0.01,   -- vacuum at 1% dead rows
    autovacuum_analyze_scale_factor  = 0.005,  -- analyze at 0.5% changes
    autovacuum_vacuum_cost_limit     = 1000,   -- higher I/O budget
    autovacuum_vacuum_threshold      = 100,
    autovacuum_analyze_threshold     = 100,
    fillfactor                       = 80      -- leave room for HOT updates
);

-- Large read-heavy table: less frequent vacuum
ALTER TABLE audit_log SET (
    autovacuum_vacuum_scale_factor   = 0.20,   -- vacuum only at 20% dead
    autovacuum_analyze_scale_factor  = 0.10,
    autovacuum_vacuum_cost_limit     = 200     -- low I/O budget (read I/O priority)
);

-- Append-only time-series table: almost never vacuum, freeze periodically
ALTER TABLE metrics SET (
    autovacuum_vacuum_scale_factor   = 0.50,   -- rarely vacuum
    autovacuum_freeze_min_age        = 0,      -- freeze rows immediately
    autovacuum_freeze_max_age        = 100000000  -- force freeze more often
);

-- View per-table autovacuum overrides
SELECT relname,
       reloptions
FROM pg_class
WHERE reloptions IS NOT NULL
  AND relkind = 'r'
ORDER BY relname;

-- Remove per-table override (revert to global settings)
ALTER TABLE orders RESET (autovacuum_vacuum_scale_factor);
```

### Monitoring Autovacuum Activity

```sql
-- Currently running autovacuum workers
SELECT
    pid,
    now() - xact_start                       AS duration,
    query
FROM pg_stat_activity
WHERE query LIKE 'autovacuum:%'
ORDER BY duration DESC;

-- Autovacuum history per table
SELECT
    relname,
    autovacuum_count,
    last_autovacuum,
    autoanalyze_count,
    last_autoanalyze,
    n_dead_tup,
    n_live_tup
FROM pg_stat_user_tables
ORDER BY autovacuum_count DESC
LIMIT 20;

-- Tables that autovacuum is not keeping up with
SELECT
    relname,
    n_dead_tup,
    n_live_tup,
    last_autovacuum,
    now() - last_autovacuum               AS since_vacuum,
    ROUND(100.0 * n_dead_tup
          / NULLIF(n_live_tup + n_dead_tup, 0), 1) AS dead_pct
FROM pg_stat_user_tables
WHERE n_dead_tup > 10000
  AND (last_autovacuum IS NULL
       OR now() - last_autovacuum > INTERVAL '1 hour')
ORDER BY n_dead_tup DESC;
```

---

## 8. Table & Index Bloat

### What is Bloat?

```
Bloat = wasted space inside tables and indexes that cannot be reused
        because of MVCC dead tuples that have not been vacuumed,
        or page fragmentation after many updates/deletes.

Table bloat: dead rows taking up pages that could hold live data
Index bloat: index entries pointing to dead rows

Impact:
  ❌ Larger tables → more I/O → slower queries
  ❌ Larger indexes → more memory pressure
  ❌ Slower sequential scans (more pages to read)
  ❌ Wasted disk space

Fix:
  VACUUM ANALYZE → reclaims dead tuple space (no lock, fast)
  VACUUM FULL   → compacts table (exclusive lock, rewrites file)
  REINDEX       → rebuilds index (REINDEX CONCURRENTLY is non-blocking)
  pg_repack     → online table/index rebuild (no lock, preferred)
```

### Measure Table Bloat

```sql
-- Estimated table bloat using pg_stats
-- (approximation — not exact, but good enough for decisions)
SELECT
    schemaname,
    tablename,
    pg_size_pretty(pg_relation_size(schemaname||'.'||tablename)) AS table_size,
    n_dead_tup                                AS dead_rows,
    n_live_tup                                AS live_rows,
    ROUND(100.0 * n_dead_tup
          / NULLIF(n_live_tup + n_dead_tup, 0), 1) AS dead_pct,
    last_autovacuum,
    last_vacuum
FROM pg_stat_user_tables
WHERE n_dead_tup > 1000
ORDER BY n_dead_tup DESC
LIMIT 20;

-- More precise bloat estimate using page-level analysis
SELECT
    relname                                   AS table_name,
    pg_size_pretty(
        (pg_relation_size(oid) - n_live_tup * 100)
    )                                         AS approx_bloat,
    pg_size_pretty(pg_relation_size(oid))     AS table_size,
    ROUND(100.0 * n_dead_tup
          / NULLIF(n_live_tup + n_dead_tup, 0), 1) AS dead_pct
FROM pg_class c
JOIN pg_stat_user_tables s ON s.relname = c.relname
WHERE c.relkind = 'r'
  AND n_dead_tup > 0
ORDER BY n_dead_tup DESC
LIMIT 15;
```

### Measure Index Bloat

```sql
-- Index bloat: indexes with few live scans per size
SELECT
    indexrelname                              AS index_name,
    relname                                   AS table_name,
    pg_size_pretty(pg_relation_size(indexrelid)) AS index_size,
    idx_scan                                  AS times_used,
    pg_relation_size(indexrelid)              AS size_bytes
FROM pg_stat_user_indexes
WHERE pg_relation_size(indexrelid) > 10 * 1024 * 1024   -- larger than 10MB
ORDER BY pg_relation_size(indexrelid) DESC;

-- Rebuild a bloated index (non-blocking)
REINDEX INDEX CONCURRENTLY idx_orders_status;

-- Rebuild all indexes on a table (non-blocking, PG 12+)
REINDEX TABLE CONCURRENTLY orders;
```

### Fix Bloat Without Locking (pg_repack)

```bash
# pg_repack rebuilds tables and indexes ONLINE (no exclusive lock)
# Must be installed as extension first:
# CREATE EXTENSION pg_repack;

# Repack a specific table
pg_repack -h localhost -U postgres -d mydb --table orders

# Repack all tables in database
pg_repack -h localhost -U postgres -d mydb

# Repack indexes only
pg_repack -h localhost -U postgres -d mydb --table orders --only-indexes

# Dry run (what would be repacked)
pg_repack -h localhost -U postgres -d mydb --dry-run
```

### CLUSTER — Physically Reorder Table

```sql
-- CLUSTER rewrites the table in index order (improves sequential reads)
-- Requires exclusive lock — use during maintenance window

-- Cluster by an index
CLUSTER orders USING idx_orders_created_at;

-- Cluster all tables (remembers last cluster index)
CLUSTER;

-- Check last cluster time
SELECT relname, relhasindex, relpages
FROM pg_class WHERE relname = 'orders';

-- CLUSTER vs VACUUM FULL:
-- CLUSTER:      rewrites in index order + reclaims bloat (needs lock)
-- VACUUM FULL:  reclaims bloat only, no reordering (needs lock)
-- pg_repack:    repack online with no lock (preferred for production)
```

---

## 9. Tablespaces

A **tablespace** maps a symbolic name to a filesystem directory, allowing you to control where PostgreSQL stores its data.

```sql
-- Create a tablespace
CREATE TABLESPACE fast_ssd LOCATION '/mnt/nvme/pgdata';
CREATE TABLESPACE warm_hdd LOCATION '/mnt/hdd/pgdata';
CREATE TABLESPACE archive   LOCATION '/mnt/archive/pgdata';

-- List tablespaces
\db
SELECT spcname, pg_tablespace_location(oid), pg_size_pretty(pg_tablespace_size(oid))
FROM pg_tablespace ORDER BY spcname;

-- Create table in a specific tablespace
CREATE TABLE hot_events (
    id   BIGSERIAL PRIMARY KEY,
    data JSONB,
    ts   TIMESTAMPTZ DEFAULT NOW()
) TABLESPACE fast_ssd;

-- Create index in a specific tablespace
CREATE INDEX idx_hot_events_ts ON hot_events(ts)
TABLESPACE fast_ssd;

-- Move table to different tablespace (requires exclusive lock)
ALTER TABLE old_orders SET TABLESPACE warm_hdd;

-- Move all tables in database to a tablespace
-- (runs per-table ALTER TABLE)
DO $$
DECLARE r RECORD;
BEGIN
    FOR r IN SELECT tablename FROM pg_tables WHERE schemaname = 'archive'
    LOOP
        EXECUTE FORMAT('ALTER TABLE %I SET TABLESPACE warm_hdd', r.tablename);
        RAISE NOTICE 'Moved: %', r.tablename;
    END LOOP;
END $$;

-- Change default tablespace for a database
ALTER DATABASE myapp SET default_tablespace = fast_ssd;

-- Move index to different tablespace
ALTER INDEX idx_orders_created SET TABLESPACE warm_hdd;

-- Set tablespace for temp files
ALTER SYSTEM SET temp_tablespaces = 'fast_ssd';
SELECT pg_reload_conf();

-- Drop a tablespace (must be empty first)
DROP TABLESPACE IF EXISTS old_tablespace;

-- Check what objects are in each tablespace
SELECT
    t.spcname                                 AS tablespace,
    c.relname                                 AS object_name,
    CASE c.relkind
        WHEN 'r' THEN 'table'
        WHEN 'i' THEN 'index'
        WHEN 'm' THEN 'matview'
    END                                       AS type
FROM pg_class c
JOIN pg_tablespace t ON t.oid = c.reltablespace
WHERE c.relkind IN ('r','i','m')
ORDER BY t.spcname, c.relname;
```

---

## 10. Extensions Management

Extensions add functionality to PostgreSQL (new data types, functions, index types, etc.).

### Installing Extensions

```sql
-- List available extensions (installed in $PGSHARE/extension/)
SELECT name, default_version, comment
FROM pg_available_extensions
ORDER BY name;

-- Install an extension
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_trgm;       -- trigram matching
CREATE EXTENSION IF NOT EXISTS btree_gin;     -- GIN indexes for scalar types
CREATE EXTENSION IF NOT EXISTS btree_gist;    -- GiST indexes for scalar types
CREATE EXTENSION IF NOT EXISTS hstore;        -- key-value pairs
CREATE EXTENSION IF NOT EXISTS tablefunc;     -- crosstab / pivot
CREATE EXTENSION IF NOT EXISTS intarray;      -- integer array functions
CREATE EXTENSION IF NOT EXISTS earthdistance; -- geo distance
CREATE EXTENSION IF NOT EXISTS postgis;       -- full geospatial (if installed)
CREATE EXTENSION IF NOT EXISTS pg_repack;     -- online table repack
CREATE EXTENSION IF NOT EXISTS auto_explain;  -- log slow query plans
CREATE EXTENSION IF NOT EXISTS pg_partman;    -- partition management

-- Install in a specific schema
CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA extensions;

-- List installed extensions
\dx
SELECT extname, extversion, nspname AS schema
FROM pg_extension
JOIN pg_namespace ON pg_namespace.oid = extnamespace
ORDER BY extname;

-- Update an extension
ALTER EXTENSION pg_stat_statements UPDATE;
ALTER EXTENSION pg_stat_statements UPDATE TO '1.9';

-- Drop an extension
DROP EXTENSION IF EXISTS pg_trgm;
DROP EXTENSION IF EXISTS pg_trgm CASCADE;  -- also drops dependent objects
```

### Essential Extensions for Production

```sql
-- 1. pg_stat_statements — query performance monitoring
CREATE EXTENSION pg_stat_statements;
-- Requires: shared_preload_libraries = 'pg_stat_statements'
-- Usage: SELECT * FROM pg_stat_statements ORDER BY total_exec_time DESC;

-- 2. pgcrypto — cryptographic functions
CREATE EXTENSION pgcrypto;
-- Usage: SELECT gen_random_uuid(), crypt('password', gen_salt('bf'));

-- 3. pg_trgm — fuzzy text search, fast LIKE/ILIKE
CREATE EXTENSION pg_trgm;
-- Usage: CREATE INDEX ON employees USING gin(name gin_trgm_ops);
--        SELECT * FROM employees WHERE name % 'Alise';   -- fuzzy match

-- 4. auto_explain — log slow query execution plans automatically
CREATE EXTENSION auto_explain;
-- Requires: shared_preload_libraries = 'auto_explain'
-- Config:
ALTER SYSTEM SET auto_explain.log_min_duration = '1s';
ALTER SYSTEM SET auto_explain.log_analyze      = true;
ALTER SYSTEM SET auto_explain.log_buffers      = true;
ALTER SYSTEM SET auto_explain.log_format       = 'text';
SELECT pg_reload_conf();

-- 5. pg_stat_kcache — OS-level cache/CPU stats per query
CREATE EXTENSION pg_stat_kcache;
-- Requires: shared_preload_libraries = 'pg_stat_statements,pg_stat_kcache'
-- Usage: SELECT * FROM pg_stat_kcache();

-- 6. pg_cron — schedule jobs inside PostgreSQL
CREATE EXTENSION pg_cron;
-- Requires: shared_preload_libraries = 'pg_cron'
-- Usage:
SELECT cron.schedule('nightly-vacuum', '0 2 * * *', 'VACUUM ANALYZE orders');
SELECT cron.schedule('hourly-stats',   '0 * * * *', 'ANALYZE');
SELECT * FROM cron.job;
SELECT cron.unschedule('nightly-vacuum');
```

---

## 11. System Catalog & Metadata

PostgreSQL stores all metadata in system catalog tables in the `pg_catalog` schema.

### Key System Catalog Tables

```sql
-- ─── Databases ───────────────────────────────────────────────────────────
SELECT datname, datdba::regrole, pg_encoding_to_char(encoding),
       datcollate, datctype, datconnlimit
FROM pg_database WHERE datistemplate = false;

-- ─── Tables ──────────────────────────────────────────────────────────────
SELECT schemaname, tablename, tableowner, hasindexes, hastriggers, rowsecurity
FROM pg_tables WHERE schemaname = 'public'
ORDER BY tablename;

-- ─── Columns ─────────────────────────────────────────────────────────────
SELECT
    table_name, column_name, ordinal_position,
    data_type, character_maximum_length,
    is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name   = 'orders'
ORDER BY ordinal_position;

-- ─── Constraints ─────────────────────────────────────────────────────────
SELECT
    conname                               AS constraint_name,
    contype                               AS type,
    -- p=primary key, u=unique, c=check, f=foreign key
    conrelid::regclass                    AS table_name,
    confrelid::regclass                   AS references_table,
    pg_get_constraintdef(oid)             AS definition
FROM pg_constraint
WHERE conrelid = 'orders'::regclass
ORDER BY contype, conname;

-- ─── Indexes ─────────────────────────────────────────────────────────────
SELECT
    indexname, indexdef,
    pg_size_pretty(pg_relation_size(indexrelid)) AS size
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename  = 'orders'
ORDER BY indexname;

-- ─── Foreign Keys ────────────────────────────────────────────────────────
SELECT
    tc.table_name,
    kcu.column_name,
    ccu.table_name  AS foreign_table,
    ccu.column_name AS foreign_column,
    rc.update_rule,
    rc.delete_rule
FROM information_schema.table_constraints tc
JOIN information_schema.key_column_usage kcu
    ON tc.constraint_name = kcu.constraint_name
JOIN information_schema.referential_constraints rc
    ON tc.constraint_name = rc.constraint_name
JOIN information_schema.constraint_column_usage ccu
    ON rc.unique_constraint_name = ccu.constraint_name
WHERE tc.constraint_type = 'FOREIGN KEY'
  AND tc.table_schema = 'public'
ORDER BY tc.table_name;

-- ─── Sequences ───────────────────────────────────────────────────────────
SELECT sequencename, last_value, start_value, increment_by,
       max_value, cycle
FROM pg_sequences WHERE schemaname = 'public';

-- ─── Views ───────────────────────────────────────────────────────────────
SELECT viewname, viewowner,
       LEFT(definition, 100) AS definition_preview
FROM pg_views WHERE schemaname = 'public';

-- ─── Triggers ────────────────────────────────────────────────────────────
SELECT
    trigger_name, event_manipulation, event_object_table,
    action_timing, action_orientation, action_statement
FROM information_schema.triggers
WHERE trigger_schema = 'public'
ORDER BY event_object_table, trigger_name;

-- ─── Functions ───────────────────────────────────────────────────────────
SELECT
    p.proname                             AS function_name,
    pg_get_function_arguments(p.oid)      AS arguments,
    pg_get_function_result(p.oid)         AS return_type,
    l.lanname                             AS language
FROM pg_proc p
JOIN pg_language l ON l.oid = p.prolang
WHERE p.pronamespace = 'public'::regnamespace
  AND p.prokind      = 'f'
ORDER BY function_name;
```

---

## 12. Server Monitoring

### Real-Time Activity

```sql
-- Active queries with wait info
SELECT
    pid,
    usename,
    datname,
    application_name,
    state,
    wait_event_type,
    wait_event,
    now() - query_start                   AS query_age,
    LEFT(query, 100)                      AS query_snippet
FROM pg_stat_activity
WHERE state = 'active'
  AND pid <> pg_backend_pid()
ORDER BY query_start NULLS LAST;

-- Waiting queries (blocked by locks or other waits)
SELECT
    pid,
    usename,
    wait_event_type,
    wait_event,
    now() - query_start                   AS waiting_for,
    LEFT(query, 80)                       AS query
FROM pg_stat_activity
WHERE wait_event IS NOT NULL
  AND pid <> pg_backend_pid()
ORDER BY query_start;
```

### Cache Hit Rate

```sql
-- Overall cache hit ratio (target: > 99%)
SELECT
    SUM(heap_blks_hit)                    AS cache_hits,
    SUM(heap_blks_read)                   AS disk_reads,
    ROUND(100.0 * SUM(heap_blks_hit)
          / NULLIF(SUM(heap_blks_hit) + SUM(heap_blks_read), 0), 2) AS hit_pct
FROM pg_statio_user_tables;

-- Per-table cache hit rate
SELECT
    relname,
    heap_blks_hit,
    heap_blks_read,
    ROUND(100.0 * heap_blks_hit
          / NULLIF(heap_blks_hit + heap_blks_read, 0), 1) AS hit_pct
FROM pg_statio_user_tables
WHERE heap_blks_hit + heap_blks_read > 0
ORDER BY heap_blks_read DESC
LIMIT 20;

-- Index cache hit rate
SELECT
    indexrelname,
    idx_blks_hit,
    idx_blks_read,
    ROUND(100.0 * idx_blks_hit
          / NULLIF(idx_blks_hit + idx_blks_read, 0), 1) AS hit_pct
FROM pg_statio_user_indexes
WHERE idx_blks_hit + idx_blks_read > 0
ORDER BY idx_blks_read DESC
LIMIT 20;
```

### Checkpoint & WAL Activity

```sql
-- Checkpoint statistics
SELECT
    checkpoints_timed,
    checkpoints_req,
    checkpoint_write_time / 1000.0        AS write_sec,
    checkpoint_sync_time  / 1000.0        AS sync_sec,
    buffers_checkpoint,
    buffers_clean,
    buffers_backend,
    buffers_alloc,
    stats_reset
FROM pg_stat_bgwriter;

-- WAL statistics (PG 14+)
SELECT
    wal_records,
    wal_fpi,
    wal_bytes,
    wal_buffers_full,
    wal_write,
    wal_sync,
    wal_write_time / 1000.0               AS write_sec,
    wal_sync_time  / 1000.0               AS sync_sec
FROM pg_stat_wal;

-- Current WAL position
SELECT pg_current_wal_lsn(), pg_walfile_name(pg_current_wal_lsn());
```

### Table I/O Statistics

```sql
-- Most scanned tables (read-heavy tables)
SELECT
    relname,
    seq_scan,
    seq_tup_read,
    idx_scan,
    idx_tup_fetch,
    n_tup_ins,
    n_tup_upd,
    n_tup_del,
    n_live_tup,
    n_dead_tup
FROM pg_stat_user_tables
ORDER BY seq_scan + idx_scan DESC
LIMIT 20;

-- Temp file usage (sort/hash spills)
SELECT
    datname,
    temp_files,
    pg_size_pretty(temp_bytes)            AS temp_size,
    blks_hit,
    blks_read
FROM pg_stat_database
WHERE datname = current_database();
```

---

## 13. Lock Management

### Viewing Locks

```sql
-- All current locks
SELECT
    pid,
    locktype,
    relation::regclass                    AS table_name,
    mode,
    granted,
    now() - query_start                   AS lock_age,
    LEFT(query, 80)                       AS query
FROM pg_locks l
JOIN pg_stat_activity a USING (pid)
WHERE relation IS NOT NULL
ORDER BY lock_age DESC NULLS LAST;

-- Blocked queries with their blockers
SELECT
    blocked.pid                           AS blocked_pid,
    blocked.usename                       AS blocked_user,
    now() - blocked.query_start           AS blocked_for,
    LEFT(blocked.query, 80)               AS blocked_query,
    blocking.pid                          AS blocking_pid,
    blocking.usename                      AS blocking_user,
    LEFT(blocking.query, 80)              AS blocking_query
FROM pg_stat_activity blocked
JOIN pg_stat_activity blocking
    ON blocking.pid = ANY(pg_blocking_pids(blocked.pid))
WHERE blocked.cardinality(pg_blocking_pids(blocked.pid)) > 0;

-- Simplified blocked query detection
SELECT
    pid,
    usename,
    pg_blocking_pids(pid)                 AS blocked_by,
    now() - query_start                   AS blocked_for,
    LEFT(query, 80)                       AS query
FROM pg_stat_activity
WHERE cardinality(pg_blocking_pids(pid)) > 0;

-- Lock conflict matrix (which lock modes block which)
-- AccessShare  < RowShare  < RowExclusive < ShareUpdateExclusive
-- < Share < ShareRowExclusive < Exclusive < AccessExclusive
```

### Lock Modes Quick Reference

```
Lock Mode              | Blocked By
-----------------------+------------------------------------------
ACCESS SHARE           | ACCESS EXCLUSIVE only
ROW SHARE              | EXCLUSIVE, ACCESS EXCLUSIVE
ROW EXCLUSIVE          | SHARE, SHARE ROW EXCLUSIVE, EXCLUSIVE, ACCESS EXCLUSIVE
SHARE UPDATE EXCLUSIVE | SHARE UPDATE EXCLUSIVE, SHARE, SHARE ROW EXCLUSIVE,
                       | EXCLUSIVE, ACCESS EXCLUSIVE
SHARE                  | ROW EXCLUSIVE, SHARE UPDATE EXCLUSIVE,
                       | SHARE ROW EXCLUSIVE, EXCLUSIVE, ACCESS EXCLUSIVE
SHARE ROW EXCLUSIVE    | ROW EXCLUSIVE, SHARE UPDATE EXCLUSIVE, SHARE,
                       | SHARE ROW EXCLUSIVE, EXCLUSIVE, ACCESS EXCLUSIVE
EXCLUSIVE              | All except ACCESS SHARE
ACCESS EXCLUSIVE       | All lock modes (blocks everything)

Commands and their locks:
SELECT                → ACCESS SHARE
SELECT FOR UPDATE     → ROW SHARE
INSERT/UPDATE/DELETE  → ROW EXCLUSIVE
CREATE INDEX          → SHARE UPDATE EXCLUSIVE (CONCURRENTLY)
CREATE INDEX          → SHARE (non-concurrent)
VACUUM (not FULL)     → SHARE UPDATE EXCLUSIVE
ALTER TABLE           → ACCESS EXCLUSIVE (blocks EVERYTHING)
VACUUM FULL           → ACCESS EXCLUSIVE
DROP TABLE            → ACCESS EXCLUSIVE
TRUNCATE              → ACCESS EXCLUSIVE
```

### Deadlock Detection & Prevention

```sql
-- Deadlock will be auto-detected after deadlock_timeout (default 1s)
-- and one transaction will be cancelled with:
-- ERROR: deadlock detected

-- Set deadlock timeout
SHOW deadlock_timeout;
ALTER SYSTEM SET deadlock_timeout = '500ms';

-- Log deadlocks
ALTER SYSTEM SET log_lock_waits = on;
ALTER SYSTEM SET deadlock_timeout = '1s';
SELECT pg_reload_conf();

-- Prevent deadlocks: always lock multiple resources in the SAME ORDER
-- Bad (deadlock possible):
-- Transaction 1: LOCK TABLE a, then b
-- Transaction 2: LOCK TABLE b, then a

-- Good (consistent order):
-- Transaction 1: LOCK TABLE a, then b
-- Transaction 2: LOCK TABLE a, then b  ← same order

-- Use NOWAIT to fail immediately instead of waiting
SELECT * FROM orders WHERE id = 42 FOR UPDATE NOWAIT;
-- ERROR: could not obtain lock on row in relation "orders"

-- Use SKIP LOCKED to skip locked rows (batch processing)
SELECT * FROM orders WHERE status = 'pending'
ORDER BY created_at
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

---

## 14. PostgreSQL Upgrades

### Minor Version Upgrades (e.g., 16.1 → 16.3)

```bash
# Minor versions: bug fixes only, no data format changes
# Process: install new binaries, restart

# On Debian/Ubuntu
sudo apt update
sudo apt install postgresql-16

# Restart service
sudo systemctl restart postgresql

# Verify new version
psql -c "SELECT version();"
```

### Major Version Upgrades (e.g., 15 → 16)

```
Major upgrades REQUIRE a data migration — data format may change.
Methods:
  1. pg_upgrade     — in-place upgrade (fastest, minimal downtime)
  2. Logical replication — zero-downtime upgrade
  3. pg_dump/restore — slowest, safest for small databases
```

### Method 1: pg_upgrade

```bash
# Install new PostgreSQL alongside existing
sudo apt install postgresql-16

# Initialize new cluster
sudo -u postgres /usr/lib/postgresql/16/bin/initdb \
    -D /var/lib/postgresql/16/main

# Stop old PostgreSQL
sudo systemctl stop postgresql@15-main

# Run pg_upgrade
sudo -u postgres /usr/lib/postgresql/16/bin/pg_upgrade \
    --old-datadir=/var/lib/postgresql/15/main \
    --new-datadir=/var/lib/postgresql/16/main \
    --old-bindir=/usr/lib/postgresql/15/bin  \
    --new-bindir=/usr/lib/postgresql/16/bin  \
    --check   # dry run first!

# If check passes, run for real:
sudo -u postgres /usr/lib/postgresql/16/bin/pg_upgrade \
    --old-datadir=/var/lib/postgresql/15/main \
    --new-datadir=/var/lib/postgresql/16/main \
    --old-bindir=/usr/lib/postgresql/15/bin  \
    --new-bindir=/usr/lib/postgresql/16/bin  \
    --link    # hard-link files (fastest, no full copy)

# Start new cluster
sudo systemctl start postgresql@16-main

# Update statistics (pg_upgrade does not carry them)
sudo -u postgres /usr/lib/postgresql/16/bin/vacuumdb \
    --all --analyze-in-stages

# Run pg_upgrade's generated scripts
./analyze_new_cluster.sh   # update statistics
./delete_old_cluster.sh    # clean up old cluster (after verifying new one)
```

### Method 2: Zero-Downtime via Logical Replication

```sql
-- See PostgreSQL_Replication_HA.md Pattern 5 for full details

-- Summary:
-- 1. Set up PG16 instance
-- 2. Create logical replication from PG15 → PG16
-- 3. Wait for lag = 0
-- 4. Brief write pause (< 1 minute)
-- 5. Switch application to PG16
-- 6. Decommission PG15

-- On PG15 (old primary):
ALTER SYSTEM SET wal_level = 'logical';
SELECT pg_reload_conf();
CREATE PUBLICATION pub_for_upgrade FOR ALL TABLES;

-- On PG16 (new instance):
-- Create all tables with same schema
CREATE SUBSCRIPTION sub_from_pg15
    CONNECTION 'host=pg15_host port=5432 user=replicator dbname=mydb'
    PUBLICATION pub_for_upgrade;

-- Monitor lag
SELECT * FROM pg_stat_subscription;
-- When lag ≈ 0 → switch application connections to PG16
```

### Pre-Upgrade Checklist

```sql
-- 1. Check deprecated features
SELECT * FROM pg_settings WHERE pending_restart = true;

-- 2. Check extensions compatibility with new version
SELECT extname, extversion FROM pg_extension ORDER BY extname;
-- Verify each extension is available for target PG version

-- 3. Back up everything
pg_basebackup --pgdata=/backups/pre_upgrade --format=tar --compress=9

-- 4. Test pg_upgrade with --check flag
-- sudo -u postgres pg_upgrade --check ...

-- 5. Capture query plans (for regression testing after upgrade)
-- SET apg_plan_mgmt.capture_plan_baselines = automatic;
-- Run full workload for 24 hours
-- SET apg_plan_mgmt.capture_plan_baselines = off;

-- 6. Check for incompatible data types or functions
-- Run: pg_upgrade --check  (it reports issues)
```

---

## 15. Security Hardening

### Network Security

```ini
# postgresql.conf: restrict which interfaces PostgreSQL listens on
listen_addresses = 'localhost,10.0.1.5'   # NOT '*' in production
                                           # unless behind a firewall

# Use SSL for all non-local connections
ssl = on
ssl_cert_file = '/etc/ssl/certs/server.crt'
ssl_key_file  = '/etc/ssl/private/server.key'
ssl_ca_file   = '/etc/ssl/certs/ca.crt'
ssl_min_protocol_version = 'TLSv1.2'      # minimum TLS version
```

```
# pg_hba.conf: deny all, then allow specific hosts
host    all   all   0.0.0.0/0   reject    # deny everyone by default
hostssl myapp app   10.0.0.0/8  scram-sha-256  # app servers via SSL only
local   all   postgres          peer             # local superuser via peer
```

### Principle of Least Privilege

```sql
-- Create role hierarchy (no superusers for applications)
CREATE ROLE readonly     NOLOGIN NOINHERIT NOSUPERUSER;
CREATE ROLE readwrite    NOLOGIN NOINHERIT NOSUPERUSER;
CREATE ROLE app_admin    NOLOGIN NOINHERIT NOSUPERUSER;

-- Grant minimal permissions per role
GRANT CONNECT ON DATABASE myapp TO readonly;
GRANT CONNECT ON DATABASE myapp TO readwrite;
GRANT USAGE ON SCHEMA public TO readonly;
GRANT USAGE ON SCHEMA public TO readwrite;

GRANT SELECT ON ALL TABLES IN SCHEMA public TO readonly;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO readwrite;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO readwrite;

-- Future tables
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT ON TABLES TO readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO readwrite;

-- Application users inherit from role groups
CREATE ROLE api_service  LOGIN PASSWORD 'strongpass' IN ROLE readwrite;
CREATE ROLE report_user  LOGIN PASSWORD 'strongpass' IN ROLE readonly;
CREATE ROLE dba_user     LOGIN PASSWORD 'strongpass' IN ROLE app_admin;

-- Revoke PUBLIC privileges (schema access should be explicit)
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE myapp FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO readwrite;
GRANT USAGE ON SCHEMA public TO readonly;
```

### Row-Level Security (RLS)

```sql
-- Enable RLS
ALTER TABLE customer_data ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_data FORCE ROW LEVEL SECURITY;   -- apply to owner too

-- Policy: users only see their own data
CREATE POLICY customer_isolation
    ON customer_data
    FOR ALL
    USING (owner_email = CURRENT_USER);

-- Policy: service account sees everything
CREATE POLICY service_full_access
    ON customer_data
    FOR ALL
    TO api_service
    USING (true);

-- Policy: read-only role sees non-sensitive rows only
CREATE POLICY readonly_filter
    ON customer_data
    FOR SELECT
    TO report_user
    USING (sensitivity_level = 'public');
```

### Audit Logging

```sql
-- Log all DDL changes
ALTER SYSTEM SET log_statement = 'ddl';
SELECT pg_reload_conf();

-- Log all modifications (DDL + DML)
ALTER SYSTEM SET log_statement = 'mod';

-- Log slow queries
ALTER SYSTEM SET log_min_duration_statement = 1000;  -- 1 second

-- For detailed audit trail, use pgaudit extension:
CREATE EXTENSION pgaudit;
ALTER SYSTEM SET pgaudit.log = 'write, ddl, role';
ALTER SYSTEM SET pgaudit.log_relation = 'on';
ALTER SYSTEM SET pgaudit.log_parameter = 'on';
SELECT pg_reload_conf();

-- pgaudit logs:
-- AUDIT: SESSION,1,1,DDL,CREATE TABLE,,,"CREATE TABLE ...",<not logged>
-- AUDIT: SESSION,2,1,WRITE,INSERT,public,orders,"INSERT INTO orders ...",<not logged>
```

### SSL Certificate Setup

```bash
# Generate self-signed certificate (for testing)
openssl req -new -x509 -days 365 -nodes \
    -out /etc/ssl/certs/server.crt \
    -keyout /etc/ssl/private/server.key \
    -subj "/CN=mypostgres.example.com"

chmod 600 /etc/ssl/private/server.key
chown postgres:postgres /etc/ssl/private/server.key

# Verify SSL in psql
psql "sslmode=require host=mydb.example.com dbname=mydb user=app"
SELECT ssl, version, cipher FROM pg_stat_ssl WHERE pid = pg_backend_pid();
```

---

## 16. Routine DBA Operations Checklist

### Daily Checks

```sql
-- ─── 1. Check for long-running queries (> 5 minutes) ─────────────────────
SELECT pid, usename, now() - query_start AS duration,
       state, LEFT(query, 100) AS query
FROM pg_stat_activity
WHERE state = 'active'
  AND now() - query_start > INTERVAL '5 minutes'
  AND pid <> pg_backend_pid()
ORDER BY duration DESC;

-- ─── 2. Check for idle-in-transaction sessions ────────────────────────────
SELECT pid, usename, now() - state_change AS idle_for,
       LEFT(query, 80) AS last_query
FROM pg_stat_activity
WHERE state = 'idle in transaction'
  AND now() - state_change > INTERVAL '5 minutes';

-- ─── 3. Check for blocked queries ────────────────────────────────────────
SELECT pid, usename,
       pg_blocking_pids(pid) AS blocked_by,
       LEFT(query, 80) AS query
FROM pg_stat_activity
WHERE cardinality(pg_blocking_pids(pid)) > 0;

-- ─── 4. Check replication lag (if replicas exist) ────────────────────────
SELECT application_name, state, replay_lag
FROM pg_stat_replication
ORDER BY replay_lag DESC;

-- ─── 5. Check WAL archiver ───────────────────────────────────────────────
SELECT archived_count, failed_count, last_failed_time,
       now() - last_archived_time AS archive_lag
FROM pg_stat_archiver;

-- ─── 6. Check dead tuple backlog ─────────────────────────────────────────
SELECT relname, n_dead_tup, n_live_tup,
       ROUND(100.0 * n_dead_tup / NULLIF(n_live_tup + n_dead_tup,0),1) AS dead_pct
FROM pg_stat_user_tables
WHERE n_dead_tup > 10000
ORDER BY n_dead_tup DESC LIMIT 10;

-- ─── 7. Check XID age ───────────────────────────────────────────────────
SELECT datname, age(datfrozenxid) AS xid_age
FROM pg_database
ORDER BY age(datfrozenxid) DESC;
-- ALERT if age > 1.5 billion!

-- ─── 8. Check disk space (OS level) ─────────────────────────────────────
-- df -h /var/lib/postgresql
-- du -sh /var/lib/postgresql/16/main/base/*

-- ─── 9. Check slow queries from pg_stat_statements ───────────────────────
SELECT LEFT(query,80), calls, ROUND(mean_exec_time::NUMERIC,1) AS avg_ms
FROM pg_stat_statements
WHERE mean_exec_time > 1000    -- queries averaging > 1 second
ORDER BY mean_exec_time DESC LIMIT 10;
```

### Weekly Tasks

```sql
-- ─── 1. Run VACUUM ANALYZE on high-write tables ───────────────────────────
VACUUM ANALYZE orders;
VACUUM ANALYZE order_items;
VACUUM ANALYZE audit_log;

-- ─── 2. Check for unused indexes ─────────────────────────────────────────
SELECT schemaname, relname, indexrelname, idx_scan,
       pg_size_pretty(pg_relation_size(indexrelid)) AS size
FROM pg_stat_user_indexes
WHERE idx_scan < 5
  AND indexrelname NOT LIKE '%pkey%'
ORDER BY pg_relation_size(indexrelid) DESC;

-- ─── 3. Check for missing indexes (tables with many seq scans) ────────────
SELECT relname, seq_scan, idx_scan, n_live_tup
FROM pg_stat_user_tables
WHERE n_live_tup > 100000
  AND seq_scan > idx_scan
ORDER BY seq_scan DESC;

-- ─── 4. Reset pg_stat_statements (start fresh weekly) ────────────────────
-- SELECT pg_stat_statements_reset();   -- uncomment to run

-- ─── 5. Review autovacuum log entries for slow runs ───────────────────────
-- grep 'autovacuum:' /var/log/postgresql/postgresql-$(date +%Y-%m-%d).log | grep -v 'system usage'

-- ─── 6. Check database sizes trend ──────────────────────────────────────
SELECT datname, pg_size_pretty(pg_database_size(datname)) AS size
FROM pg_database WHERE datistemplate=false ORDER BY pg_database_size(datname) DESC;

-- ─── 7. Check for table bloat ────────────────────────────────────────────
SELECT relname, n_dead_tup, n_live_tup,
       ROUND(100.0 * n_dead_tup / NULLIF(n_live_tup + n_dead_tup,0),1) AS dead_pct,
       last_autovacuum
FROM pg_stat_user_tables
WHERE ROUND(100.0 * n_dead_tup / NULLIF(n_live_tup + n_dead_tup,0),1) > 10
ORDER BY dead_pct DESC;
```

### Monthly Tasks

```sql
-- ─── 1. REINDEX bloated or fragmented indexes ─────────────────────────────
-- Identify candidates:
SELECT indexrelname, pg_size_pretty(pg_relation_size(indexrelid)) AS size,
       idx_scan
FROM pg_stat_user_indexes
WHERE pg_relation_size(indexrelid) > 100 * 1024 * 1024  -- > 100MB
ORDER BY pg_relation_size(indexrelid) DESC;

-- Rebuild non-blocking:
REINDEX INDEX CONCURRENTLY idx_orders_status;

-- ─── 2. Review and rotate old log files ─────────────────────────────────
-- ls -lh /var/log/postgresql/ | head -20

-- ─── 3. Test backup restore ──────────────────────────────────────────────
-- pg_restore --list /backups/mydb_latest.dump

-- ─── 4. Verify all critical query plans are approved ─────────────────────
-- (if using apg_plan_mgmt):
-- SELECT status, COUNT(*) FROM apg_plan_mgmt.dba_plans GROUP BY status;

-- ─── 5. Verify replication slots are not accumulating WAL ────────────────
SELECT slot_name, active,
       pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS retained
FROM pg_replication_slots;

-- ─── 6. Full database backup ─────────────────────────────────────────────
-- pg_basebackup --pgdata=/backups/monthly --format=tar --compress=9

-- ─── 7. Check PostgreSQL release notes for security patches ──────────────
-- https://www.postgresql.org/support/security/
```

---

## 17. Quick Reference Cheat Sheet

```
╔════════════════════════════╦═════════════════════════════════════════════════╗
║ TOPIC                      ║ KEY COMMAND / SETTING                           ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ Config file location       ║ SELECT name,setting FROM pg_settings            ║
║                            ║   WHERE name='config_file';                     ║
║ Change config              ║ ALTER SYSTEM SET param = 'value';               ║
║ Reload config              ║ SELECT pg_reload_conf();                        ║
║ Check pending restart      ║ SELECT name FROM pg_settings                    ║
║                            ║   WHERE pending_restart=true;                   ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ View connections           ║ SELECT * FROM pg_stat_activity;                 ║
║ Kill query                 ║ SELECT pg_cancel_backend(pid);                  ║
║ Kill connection            ║ SELECT pg_terminate_backend(pid);               ║
║ Connection limit DB        ║ ALTER DATABASE db CONNECTION LIMIT 100;         ║
║ Connection limit role      ║ ALTER ROLE user CONNECTION LIMIT 50;            ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ Auth file                  ║ $PGDATA/pg_hba.conf                             ║
║ Reload auth                ║ SELECT pg_reload_conf();                        ║
║ Recommended auth method    ║ scram-sha-256 (best) > md5 > trust (never prod) ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ VACUUM                     ║ VACUUM ANALYZE tablename;                       ║
║ VACUUM FULL (with lock)    ║ VACUUM FULL tablename;                          ║
║ Check dead tuples          ║ SELECT relname, n_dead_tup, n_live_tup          ║
║                            ║   FROM pg_stat_user_tables;                     ║
║ XID age (critical)         ║ SELECT datname, age(datfrozenxid)               ║
║                            ║   FROM pg_database ORDER BY 2 DESC;             ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ ANALYZE                    ║ ANALYZE tablename;  or  ANALYZE;                ║
║ Statistics target          ║ ALTER TABLE t ALTER COLUMN c SET STATISTICS 500;║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ Autovacuum overrides       ║ ALTER TABLE t SET (autovacuum_vacuum_scale_     ║
║                            ║   factor = 0.01, ...);                          ║
║ Autovacuum monitoring      ║ SELECT * FROM pg_stat_user_tables               ║
║                            ║   WHERE n_dead_tup > 10000;                     ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ Tablespace create          ║ CREATE TABLESPACE name LOCATION '/path';        ║
║ Move table                 ║ ALTER TABLE t SET TABLESPACE name;              ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ Install extension          ║ CREATE EXTENSION IF NOT EXISTS extname;         ║
║ List extensions            ║ SELECT extname,extversion FROM pg_extension;    ║
║ Update extension           ║ ALTER EXTENSION extname UPDATE;                 ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ View locks                 ║ SELECT * FROM pg_locks JOIN pg_stat_activity    ║
║                            ║   USING(pid);                                   ║
║ Blocked queries            ║ SELECT pid, pg_blocking_pids(pid) AS blocked_by ║
║                            ║   FROM pg_stat_activity                         ║
║                            ║   WHERE cardinality(pg_blocking_pids(pid)) > 0; ║
║ Lock-free operations       ║ CREATE INDEX CONCURRENTLY ...                   ║
║                            ║ REINDEX INDEX CONCURRENTLY ...                  ║
║                            ║ ALTER TABLE ... DETACH PARTITION CONCURRENTLY   ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ Major upgrade              ║ pg_upgrade --check  (dry run first)             ║
║                            ║ pg_upgrade --link   (fastest upgrade)           ║
║ Minor upgrade              ║ Install packages + restart service              ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ Cache hit ratio            ║ SELECT ROUND(100.0 * SUM(heap_blks_hit) /       ║
║ (target: > 99%)            ║   NULLIF(SUM(heap_blks_hit)+SUM(heap_blks_read),║
║                            ║   0),2) FROM pg_statio_user_tables;             ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ Recommended Settings       ║ shared_buffers     = 25% RAM                   ║
║ (start here)               ║ effective_cache_size = 75% RAM                 ║
║                            ║ work_mem           = 64MB (tune up per session) ║
║                            ║ maintenance_work_mem = 1GB                     ║
║                            ║ random_page_cost   = 1.1 (SSD) / 4.0 (HDD)    ║
║                            ║ checkpoint_completion_target = 0.9              ║
║                            ║ max_wal_size       = 4GB                        ║
║                            ║ log_min_duration_statement = 1000 (ms)          ║
╠════════════════════════════╬═════════════════════════════════════════════════╣
║ Essential Extensions       ║ pg_stat_statements  — query performance         ║
║                            ║ pgcrypto            — UUID, encryption          ║
║                            ║ pg_trgm             — fuzzy search              ║
║                            ║ auto_explain        — auto-log slow plans       ║
║                            ║ pg_repack           — online table repack       ║
║                            ║ pg_cron             — job scheduling            ║
╚════════════════════════════╩═════════════════════════════════════════════════╝
```

---

## Further Reading

- [PostgreSQL Docs — Server Configuration](https://www.postgresql.org/docs/current/runtime-config.html)
- [PostgreSQL Docs — Client Authentication](https://www.postgresql.org/docs/current/client-authentication.html)
- [PostgreSQL Docs — Routine Maintenance](https://www.postgresql.org/docs/current/maintenance.html)
- [PostgreSQL Docs — VACUUM](https://www.postgresql.org/docs/current/sql-vacuum.html)
- [PostgreSQL Docs — Monitoring Statistics](https://www.postgresql.org/docs/current/monitoring-stats.html)
- [PostgreSQL Docs — Tablespaces](https://www.postgresql.org/docs/current/manage-ag-tablespaces.html)
- [PostgreSQL Docs — pg_upgrade](https://www.postgresql.org/docs/current/pgupgrade.html)
- [PostgreSQL Docs — Explicit Locking](https://www.postgresql.org/docs/current/explicit-locking.html)
- [pgBadger](https://pgbadger.darold.net/) — PostgreSQL log analyzer
- [pg_activity](https://github.com/dalibo/pg_activity) — top-like monitoring for PostgreSQL
- [pgaudit](https://www.pgaudit.org/) — detailed audit logging extension
- [pg_repack](https://reorg.github.io/pg_repack/) — online table/index rebuild without locks

---

*Generated with love for PostgreSQL DBAs.*
