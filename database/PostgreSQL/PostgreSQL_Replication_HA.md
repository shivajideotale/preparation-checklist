[PostgreSQL_Replication_and_HA.md](../../../../Users/shiva/Downloads/PostgreSQL_Replication_and_HA.md)# PostgreSQL — Replication & High Availability Complete Reference

> A deep-dive guide covering WAL, streaming replication, logical replication, synchronous replication, failover, connection pooling, monitoring, and real-world HA architectures in PostgreSQL.

---

## Table of Contents

1. [What is Replication?](#1-what-is-replication)
2. [Write-Ahead Log (WAL)](#2-write-ahead-log-wal)
3. [Streaming Replication](#3-streaming-replication)
4. [Replication Slots](#4-replication-slots)
5. [Synchronous Replication](#5-synchronous-replication)
6. [Logical Replication](#6-logical-replication)
7. [Cascading Replication](#7-cascading-replication)
8. [Standby Server Configuration](#8-standby-server-configuration)
9. [Failover & Switchover](#9-failover--switchover)
10. [Connection Pooling (PgBouncer)](#10-connection-pooling-pgbouncer)
11. [Load Balancing Read Replicas](#11-load-balancing-read-replicas)
12. [Backup Strategies](#12-backup-strategies)
13. [Point-In-Time Recovery (PITR)](#13-point-in-time-recovery-pitr)
14. [High Availability Tools](#14-high-availability-tools)
15. [Monitoring Replication](#15-monitoring-replication)
16. [HA Architecture Patterns](#16-ha-architecture-patterns)
17. [Quick Reference Cheat Sheet](#17-quick-reference-cheat-sheet)

---

## 1. What is Replication?

**Replication** is the process of continuously copying data from one PostgreSQL server (**primary**) to one or more other servers (**standbys / replicas**). It enables:

```
┌─────────────────────────────────────────────────────────────┐
│                    WHY REPLICATE?                           │
├──────────────────────┬──────────────────────────────────────┤
│ High Availability    │ Automatic failover if primary fails  │
│ Read Scaling         │ Route SELECT queries to replicas     │
│ Disaster Recovery    │ Replica in different data center     │
│ Zero-Downtime Backup │ Backup from standby (no load on PRI) │
│ Analytics Offload    │ Heavy reports on replica, not primary│
│ Upgrade Safety       │ Test on replica before upgrading PRI │
└──────────────────────┴──────────────────────────────────────┘
```

### Replication Types Overview

```
PHYSICAL REPLICATION (Streaming)
  Primary ──[WAL bytes]──► Standby
  • Exact byte-for-byte copy of entire cluster
  • Binary level — all databases, all tables
  • Standby can serve read-only queries
  • Fastest, lowest overhead
  • Cannot replicate to different PostgreSQL major versions

LOGICAL REPLICATION
  Primary ──[decoded changes]──► Subscriber
  • Row-level changes decoded to SQL-like events
  • Selective: choose specific tables or schemas
  • Can replicate to different PostgreSQL versions
  • Can replicate to external systems (Kafka, etc.)
  • Supports bidirectional replication (PG 16+)
```

### Replication Terminology

| Term | Meaning |
|------|---------|
| Primary / Master | Main server — accepts writes |
| Standby / Replica | Copy of primary — read-only |
| WAL | Write-Ahead Log — the change journal |
| LSN | Log Sequence Number — WAL position pointer |
| Replication Lag | How far behind the standby is from primary |
| Failover | Promoting standby to new primary (emergency) |
| Switchover | Planned promotion of standby to primary |
| Replication Slot | Ensures WAL is retained until replica consumes it |
| Publication | Logical replication source (set of tables) |
| Subscription | Logical replication consumer |
| Synchronous | Primary waits for standby to confirm WAL receipt |
| Asynchronous | Primary does not wait for standby confirmation |

---

## 2. Write-Ahead Log (WAL)

Every change in PostgreSQL is first written to the **WAL** before the data pages are modified. WAL is the foundation of both replication and crash recovery.

### How WAL Works

```
Application writes:
  UPDATE accounts SET balance = 500 WHERE id = 1;

PostgreSQL process:
  1. Write change to WAL buffer (memory)
  2. Flush WAL buffer to WAL files on disk  ← durable here
  3. Modify shared buffer (data cache)
  4. Background writer eventually writes data pages to disk

WAL files location:
  $PGDATA/pg_wal/
  000000010000000000000001   ← WAL segment file (16MB each)
  000000010000000000000002
  000000010000000000000003

Replication:
  Primary WAL files ──[streaming]──► Standby replays them
```

### WAL Configuration (postgresql.conf)

```ini
# ─── WAL Level ─────────────────────────────────────────────────────────────
# minimal   : minimum WAL — no replication possible
# replica   : supports streaming replication (default in PG10+)
# logical   : supports logical replication (superset of replica)
wal_level = replica          # set to 'logical' for logical replication

# ─── WAL Archiving ──────────────────────────────────────────────────────────
archive_mode = on            # enable WAL archiving
archive_command = 'cp %p /mnt/wal_archive/%f'
# or with compression:
archive_command = 'gzip < %p > /mnt/wal_archive/%f.gz'
# or to S3:
archive_command = 'aws s3 cp %p s3://my-bucket/wal/%f'
archive_timeout = 60         # force WAL segment switch every 60 seconds

# ─── WAL Size & Performance ─────────────────────────────────────────────────
wal_buffers              = 64MB   # WAL write buffer (default: 1/32 shared_buffers)
min_wal_size             = 1GB    # keep at least 1GB of WAL
max_wal_size             = 8GB    # allow up to 8GB before forcing checkpoint
checkpoint_completion_target = 0.9  # spread checkpoint over 90% of interval
checkpoint_timeout       = 10min  # max time between checkpoints

# ─── Durability ─────────────────────────────────────────────────────────────
synchronous_commit = on    # on = durable (default), off = faster but risk loss
fsync              = on    # never set to off in production!
full_page_writes   = on    # needed for crash safety

# ─── WAL Senders (for replication) ─────────────────────────────────────────
max_wal_senders    = 10    # max number of standby connections
wal_keep_size      = 1GB   # retain this much WAL for standbys (no slot needed)
```

### Inspect WAL

```sql
-- Current WAL write position
SELECT pg_current_wal_lsn();            -- e.g., 0/3A1B2C40
SELECT pg_current_wal_insert_lsn();     -- where next write will go

-- WAL file for a given LSN
SELECT pg_walfile_name('0/3A1B2C40');   -- 000000010000000000000003

-- LSN to byte offset
SELECT pg_walfile_name_offset('0/3A1B2C40');

-- Distance between two LSNs
SELECT '0/3B000000'::pg_lsn - '0/3A000000'::pg_lsn AS bytes_between;

-- WAL files on disk
SELECT name, size FROM pg_ls_waldir() ORDER BY modification DESC LIMIT 10;
```

---

## 3. Streaming Replication

The primary **streams WAL** to standbys in real time over a TCP connection. Standbys replay WAL continuously, staying close to the primary.

### Primary Server Setup

```ini
# postgresql.conf on PRIMARY
wal_level          = replica
max_wal_senders    = 5         # number of standbys + extra
wal_keep_size      = 1GB       # keep WAL without slots
hot_standby        = on        # allow reads on standby

# pg_hba.conf on PRIMARY — allow replication connections
# TYPE  DATABASE        USER            ADDRESS         METHOD
host    replication     replicator      192.168.1.0/24  scram-sha-256
host    replication     replicator      10.0.0.0/8      scram-sha-256
```

```sql
-- Create replication user on PRIMARY
CREATE ROLE replicator
    WITH REPLICATION
    LOGIN
    PASSWORD 'strong_password_here';
```

### Standby Server Setup

```bash
# Step 1: Take base backup from primary
pg_basebackup \
    --host=192.168.1.10 \
    --port=5432 \
    --username=replicator \
    --pgdata=/var/lib/postgresql/14/standby \
    --wal-method=stream \
    --write-recovery-conf \
    --progress \
    --verbose

# Options explained:
# --wal-method=stream   : stream WAL during backup (consistent backup)
# --write-recovery-conf : write standby.signal + postgresql.auto.conf automatically
# --checkpoint=fast     : trigger immediate checkpoint (faster backup start)
```

```ini
# postgresql.conf on STANDBY
# These are written by pg_basebackup --write-recovery-conf, verify:
primary_conninfo = 'host=192.168.1.10 port=5432 user=replicator password=strong_password_here application_name=standby1'
restore_command  = ''    # for WAL archiving fallback (optional)
hot_standby      = on    # allow read queries on this standby
```

```bash
# Step 2: Create standby.signal file (PostgreSQL 12+)
touch /var/lib/postgresql/14/standby/standby.signal

# Step 3: Start standby
pg_ctl start -D /var/lib/postgresql/14/standby

# Pre-PG12: use recovery.conf file instead of standby.signal
```

### Verify Replication is Working

```sql
-- On PRIMARY: check connected standbys
SELECT
    application_name,
    client_addr,
    state,
    sent_lsn,
    write_lsn,
    flush_lsn,
    replay_lsn,
    sent_lsn - replay_lsn           AS total_lag_bytes,
    write_lag,
    flush_lag,
    replay_lag,
    sync_state
FROM pg_stat_replication
ORDER BY application_name;
```

**Result on primary with one standby:**

| application_name | client_addr | state | replay_lag | sync_state |
|-----------------|-------------|-------|------------|------------|
| standby1 | 192.168.1.11 | streaming | 00:00:00.003 | async |

```sql
-- On STANDBY: check recovery status
SELECT
    pg_is_in_recovery()            AS is_standby,
    pg_last_wal_receive_lsn()      AS received_lsn,
    pg_last_wal_replay_lsn()       AS replayed_lsn,
    pg_last_xact_replay_timestamp() AS last_replayed_at,
    NOW() - pg_last_xact_replay_timestamp() AS replication_lag;
```

---

## 4. Replication Slots

A **replication slot** ensures the primary **retains WAL** until the slot's consumer (standby or logical subscriber) has consumed it. Prevents the primary from recycling WAL that the standby still needs.

### Physical Replication Slots

```sql
-- On PRIMARY: create a physical slot for a standby
SELECT pg_create_physical_replication_slot('standby1_slot');

-- List all slots
SELECT
    slot_name,
    slot_type,
    active,
    active_pid,
    restart_lsn,
    confirmed_flush_lsn,
    wal_status,
    pg_size_pretty(
        pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)
    )                   AS retained_wal_size
FROM pg_replication_slots;

-- Drop a slot (if standby is decommissioned)
SELECT pg_drop_replication_slot('standby1_slot');
```

```ini
# On STANDBY: configure to use the slot
# postgresql.auto.conf or postgresql.conf
primary_slot_name = 'standby1_slot'
```

### ⚠️ Replication Slot Risk

```
DANGER: Unused slots cause WAL accumulation!
If standby goes offline, primary RETAINS all WAL since slot's restart_lsn.
This can fill the disk and crash the primary.

Monitor slot lag:
SELECT slot_name, wal_status,
       pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn))
FROM pg_replication_slots
WHERE active = false;

Safety net: set a limit on retained WAL
max_slot_wal_keep_size = 10GB  -- drop slot if it would retain more than 10GB
```

---

## 5. Synchronous Replication

By default replication is **asynchronous** — the primary commits without waiting for standbys. **Synchronous** replication waits for one or more standbys to confirm WAL receipt before committing.

### Configuration

```ini
# postgresql.conf on PRIMARY

# List of standby application names that can be synchronous
synchronous_standby_names = 'standby1'

# Wait for 1 standby (ANY 1 from list):
synchronous_standby_names = 'ANY 1 (standby1, standby2, standby3)'

# Wait for ALL listed standbys:
synchronous_standby_names = 'FIRST 1 (standby1, standby2)'

# Priority-based: wait for first N in priority order:
synchronous_standby_names = 'FIRST 2 (standby1, standby2, standby3)'

# Synchronous commit levels:
synchronous_commit = on           # wait for standby flush to disk (default safe)
synchronous_commit = remote_write # wait for standby write (OS buffer, not fsync)
synchronous_commit = remote_apply # wait for standby to REPLAY the WAL (strongest)
synchronous_commit = local        # local durability only, don't wait for standby
synchronous_commit = off          # async (fastest, tiny data loss risk)
```

### Synchronous Commit Levels Compared

```
                    Primary   Standby    Standby
                    WAL Disk  Received   Applied
off               :   NO        NO         NO     (fastest, risky)
local             :   YES       NO         NO
remote_write      :   YES       OS buffer  NO
on (default)      :   YES       YES(disk)  NO
remote_apply      :   YES       YES(disk)  YES    (safest, slowest)
```

### Per-Transaction Synchronous Override

```sql
-- Default: uses postgresql.conf setting
BEGIN;
UPDATE accounts SET balance = balance - 1000 WHERE id = 1;
COMMIT;

-- Override per-transaction: critical financial transaction
BEGIN;
SET LOCAL synchronous_commit = on;   -- override for this transaction
UPDATE accounts SET balance = balance - 1000 WHERE id = 1;
COMMIT;

-- Override per-session: make this session asynchronous
SET synchronous_commit = off;
-- Bulk insert - OK to lose if crash (will re-generate)
INSERT INTO logs SELECT * FROM raw_events;
SET synchronous_commit = on;
```

### Synchronous vs Asynchronous Trade-offs

| | Asynchronous | Synchronous |
|---|---|---|
| Performance | Higher throughput | Lower throughput (RTT added) |
| Data Safety | Small window of data loss | Zero data loss (on = remote_write, remote_apply) |
| Latency | Lower | Higher (network RTT per commit) |
| Failover | May lose recent transactions | No data loss on failover |
| Best for | High-volume logs, analytics | Financial, critical data |

---

## 6. Logical Replication

Logical replication replicates **row-level changes** (INSERT, UPDATE, DELETE) decoded from WAL. Unlike streaming replication, it is **selective** and works across major versions.

### Setup: Publication on Primary

```sql
-- postgresql.conf on PRIMARY
-- wal_level = logical   (required for logical replication)

-- Create publication: replicate all tables
CREATE PUBLICATION pub_all FOR ALL TABLES;

-- Create publication: specific tables only
CREATE PUBLICATION pub_orders FOR TABLE orders, order_items, products;

-- Create publication: specific operations only
CREATE PUBLICATION pub_inserts_only
FOR TABLE events
WITH (publish = 'insert');  -- only replicate INSERTs, not UPDATE/DELETE

-- Publication for a schema (PG 15+)
CREATE PUBLICATION pub_sales FOR TABLES IN SCHEMA sales;

-- View publications
SELECT pubname, puballtables, pubinsert, pubupdate, pubdelete
FROM pg_publication;

-- View tables in a publication
SELECT * FROM pg_publication_tables WHERE pubname = 'pub_orders';

-- Add table to existing publication
ALTER PUBLICATION pub_orders ADD TABLE customers;

-- Remove table
ALTER PUBLICATION pub_orders DROP TABLE products;
```

### Setup: Subscription on Subscriber

```sql
-- On SUBSCRIBER (can be different PG major version, different server)

-- Tables must exist on subscriber with matching structure
CREATE TABLE orders (LIKE orders_template INCLUDING ALL);

-- Create subscription
CREATE SUBSCRIPTION sub_orders
CONNECTION 'host=192.168.1.10 port=5432 user=replicator password=secret dbname=mydb'
PUBLICATION pub_orders;

-- Subscription with options
CREATE SUBSCRIPTION sub_events
CONNECTION 'host=192.168.1.10 port=5432 user=replicator password=secret dbname=mydb'
PUBLICATION pub_inserts_only
WITH (
    copy_data    = true,   -- initial data copy (default: true)
    enabled      = true,   -- start immediately (default: true)
    slot_name    = 'sub_events_slot'
);

-- View subscriptions
SELECT subname, subenabled, subslotname, subpublications
FROM pg_subscription;

-- View subscription status per table
SELECT * FROM pg_subscription_rel;

-- Disable subscription (pause)
ALTER SUBSCRIPTION sub_orders DISABLE;

-- Resume
ALTER SUBSCRIPTION sub_orders ENABLE;

-- Refresh (pick up newly added tables from publication)
ALTER SUBSCRIPTION sub_orders REFRESH PUBLICATION;

-- Drop subscription
DROP SUBSCRIPTION sub_orders;
```

### Logical Replication Monitoring

```sql
-- On PRIMARY: check logical replication slots
SELECT
    slot_name,
    active,
    confirmed_flush_lsn,
    pg_size_pretty(
        pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)
    )                   AS lag_size,
    pg_stat_replication.write_lag,
    pg_stat_replication.replay_lag
FROM pg_replication_slots
LEFT JOIN pg_stat_replication
    ON pg_stat_replication.pid = pg_replication_slots.active_pid
WHERE slot_type = 'logical';

-- On SUBSCRIBER: check worker status
SELECT * FROM pg_stat_subscription;
```

### Bidirectional Logical Replication (PG 16+)

```sql
-- Node A publishes to Node B AND Node B publishes to Node A
-- Useful for multi-master writes (with conflict handling)

-- On Node A:
CREATE PUBLICATION pub_a FOR TABLE shared_table;
CREATE SUBSCRIPTION sub_from_b
    CONNECTION 'host=node_b ...'
    PUBLICATION pub_b
    WITH (origin = none);  -- avoid infinite loop

-- On Node B:
CREATE PUBLICATION pub_b FOR TABLE shared_table;
CREATE SUBSCRIPTION sub_from_a
    CONNECTION 'host=node_a ...'
    PUBLICATION pub_a
    WITH (origin = none);

-- Conflict detection: last-write-wins by default
-- Custom conflict handling requires application logic or extensions
```

### Logical Replication Restrictions

```sql
-- NOT replicated by logical replication:
-- DDL statements (CREATE TABLE, ALTER TABLE, etc.)
-- Sequences (values not replicated, only table data)
-- Large objects
-- TRUNCATE (before PG11)

-- Workarounds:
-- Apply DDL manually on subscriber before adding to publication
-- Or use schema sync tools (e.g., pg_schema_sync)

-- TRUNCATE is replicated from PG11+:
CREATE PUBLICATION pub_with_truncate FOR TABLE orders
WITH (publish = 'insert, update, delete, truncate');
```

---

## 7. Cascading Replication

A standby can itself act as a **source** for other standbys — reducing load on the primary.

```
PRIMARY
   │  (streams WAL)
   ▼
STANDBY-1  (hot standby, reads allowed)
   │  (re-streams WAL)
   ├──► STANDBY-2  (cascade replica for analytics)
   └──► STANDBY-3  (cascade replica in DR site)
```

### Setup

```ini
# On STANDBY-1 (the intermediate standby):
# Already configured as primary's standby
# Also acts as upstream for STANDBY-2 and STANDBY-3

# No special config needed on STANDBY-1 beyond:
wal_level       = replica      # allows it to forward WAL
max_wal_senders = 5            # allow cascaded connections

# pg_hba.conf on STANDBY-1:
host  replication  replicator  192.168.1.12/32  scram-sha-256
host  replication  replicator  192.168.1.13/32  scram-sha-256
```

```ini
# On STANDBY-2 (cascade standby):
primary_conninfo = 'host=192.168.1.11 port=5432 user=replicator password=secret application_name=standby2'
# ↑ Points to STANDBY-1, NOT directly to primary
```

```sql
-- On STANDBY-1: verify cascade standbys are connected
SELECT application_name, client_addr, state, sync_state
FROM pg_stat_replication;
-- Shows STANDBY-2 and STANDBY-3 connected to STANDBY-1
```

---

## 8. Standby Server Configuration

### Full postgresql.conf for a Standby

```ini
# ─── Connection ──────────────────────────────────────────────────────────────
listen_addresses = '*'
port             = 5432

# ─── Replication ──────────────────────────────────────────────────────────────
# Written by pg_basebackup, in postgresql.auto.conf:
primary_conninfo = 'host=192.168.1.10 port=5432 user=replicator
                    password=secret application_name=standby1
                    sslmode=require keepalives=1 keepalives_idle=30
                    keepalives_interval=10 keepalives_count=5'
primary_slot_name  = 'standby1_slot'     # physical replication slot
restore_command    = 'cp /mnt/wal_archive/%f %p'  # WAL archive fallback

# ─── Hot Standby ──────────────────────────────────────────────────────────────
hot_standby                   = on     # allow read queries
hot_standby_feedback          = on     # tell primary about long-running queries
                                       # prevents vacuum from removing rows we need
max_standby_archive_delay     = 30s    # how long to delay before canceling queries
max_standby_streaming_delay   = 30s    # when conflict detected during streaming

# ─── Recovery Target ──────────────────────────────────────────────────────────
# For PITR standbys (not normal streaming standbys):
# recovery_target_time  = '2024-03-15 10:30:00'
# recovery_target_action = 'promote'

# ─── Promote Trigger ──────────────────────────────────────────────────────────
# promote_trigger_file = '/tmp/promote_standby'  # touch this file to promote
```

### Hot Standby Conflicts

```sql
-- Conflicts occur when:
-- 1. Vacuum on primary removes rows needed by standby query
-- 2. Tablespace dropped on primary
-- 3. Table locked exclusively on primary

-- View conflict statistics on standby
SELECT
    confl_tablespace,
    confl_lock,
    confl_snapshot,
    confl_bufferpin,
    confl_deadlock
FROM pg_stat_database_conflicts
WHERE datname = current_database();

-- Resolve conflict strategy:
-- Option 1: hot_standby_feedback = on  (standby tells primary "don't vacuum yet")
--   Risk: primary may retain dead tuples longer → table bloat
-- Option 2: Increase max_standby_streaming_delay (give query more time to finish)
-- Option 3: Accept cancellations on standby (default behavior)
```

### Delayed Standby (for accidental deletion recovery)

```sql
-- Create a standby that replays WAL with a 2-hour delay
-- If someone drops a table on primary, you have 2 hours to recover from standby
```

```ini
# postgresql.conf on DELAYED STANDBY
recovery_min_apply_delay = '2h'   # replay WAL 2 hours behind primary
primary_conninfo          = 'host=192.168.1.10 ...'
```

---

## 9. Failover & Switchover

### Planned Switchover (Zero Downtime Maintenance)

```bash
# Step 1: On PRIMARY — stop new writes (graceful)
psql -c "SELECT pg_wal_switch();"     # flush current WAL segment
# Or pause application writes

# Step 2: Verify standby is fully caught up
# On STANDBY:
psql -c "SELECT pg_last_wal_replay_lsn();"
# On PRIMARY:
psql -c "SELECT pg_current_wal_lsn();"
# Wait until they match

# Step 3: Promote standby to primary
pg_ctl promote -D /var/lib/postgresql/14/standby
# Or:
psql -c "SELECT pg_promote();"

# Step 4: Reconfigure old primary as new standby
# On OLD PRIMARY:
# 1. Stop PostgreSQL
pg_ctl stop -D /var/lib/postgresql/14/primary

# 2. Create standby.signal
touch /var/lib/postgresql/14/primary/standby.signal

# 3. Update primary_conninfo to point to NEW primary (old standby)
echo "primary_conninfo = 'host=192.168.1.11 port=5432 user=replicator password=secret'" \
     >> /var/lib/postgresql/14/primary/postgresql.auto.conf

# 4. Start as new standby
pg_ctl start -D /var/lib/postgresql/14/primary
```

### Emergency Failover (Primary Crashed)

```bash
# Step 1: Confirm primary is truly dead
ping 192.168.1.10
psql -h 192.168.1.10 -c "SELECT 1;"  # should fail

# Step 2: Check standby lag before promoting
psql -h 192.168.1.11 -c "
SELECT
    pg_last_wal_receive_lsn()  AS received,
    pg_last_wal_replay_lsn()   AS replayed,
    NOW() - pg_last_xact_replay_timestamp() AS lag;"

# Step 3: Promote the best standby (least lag)
pg_ctl promote -D /var/lib/postgresql/14/standby
# Or using trigger file method:
touch /tmp/promote_standby

# Step 4: Update application connection string
# Point to new primary: 192.168.1.11

# Step 5: Reattach other standbys to new primary
# On other standbys — update primary_conninfo:
psql -c "ALTER SYSTEM SET primary_conninfo = 'host=192.168.1.11 ...';"
pg_ctl reload

# Or use pg_rewind to sync old primary if it comes back:
pg_rewind \
    --target-pgdata=/var/lib/postgresql/14/primary \
    --source-server="host=192.168.1.11 port=5432 user=replicator"
```

### pg_rewind — Resync After Failover

```bash
# pg_rewind brings an old primary (that diverged) back in sync
# without a full base backup

# Requirements:
# - wal_log_hints = on  OR  data checksums enabled
# - enough WAL available since divergence point

# Usage:
pg_rewind \
    --target-pgdata=/var/lib/postgresql/14/old_primary \
    --source-server="host=new_primary port=5432 user=replicator dbname=postgres" \
    --progress

# Then configure as standby and start
touch /var/lib/postgresql/14/old_primary/standby.signal
pg_ctl start -D /var/lib/postgresql/14/old_primary
```

### Promote with pg_promote() (SQL function, PG 12+)

```sql
-- On standby: promote without touching the filesystem
SELECT pg_promote(wait := true, wait_seconds := 60);
-- wait = true: waits until promotion is complete before returning
-- Returns: true if promoted successfully
```

---

## 10. Connection Pooling (PgBouncer)

Every PostgreSQL connection consumes memory (~5-10MB). Direct connections from hundreds of clients are expensive. **PgBouncer** is a lightweight connection pooler that sits between applications and PostgreSQL.

### Architecture

```
Applications (1000 connections)
        │
        ▼
┌───────────────┐
│   PgBouncer   │  port 6432
│  (pooler)     │  maintains pool of real PG connections
└───────────────┘
        │
        ▼  (20-100 real connections)
┌───────────────┐
│  PostgreSQL   │  port 5432
└───────────────┘
```

### pgbouncer.ini Configuration

```ini
[databases]
# name = connection string to PostgreSQL
mydb = host=127.0.0.1 port=5432 dbname=mydb
mydb_replica = host=192.168.1.11 port=5432 dbname=mydb

[pgbouncer]
listen_addr        = *
listen_port        = 6432
auth_type          = scram-sha-256
auth_file          = /etc/pgbouncer/userlist.txt

# Pool mode:
# session     — one server connection per client session (like direct connection)
# transaction — one server connection per transaction (RECOMMENDED for most apps)
# statement   — one server connection per statement (most aggressive, limited use)
pool_mode          = transaction

max_client_conn    = 1000     # max clients connecting to pgbouncer
default_pool_size  = 25       # server connections per database+user pair
min_pool_size      = 5        # keep at least 5 server connections ready
reserve_pool_size  = 5        # extra connections for bursts
reserve_pool_timeout = 5      # seconds before using reserve pool

# Timeouts
server_idle_timeout    = 600   # close idle server connections after 600s
client_idle_timeout    = 0     # 0 = no timeout for idle clients
query_timeout          = 0     # 0 = no per-query timeout
query_wait_timeout     = 120   # error if waiting > 120s for a connection

# Logging
log_connections  = 1
log_disconnections = 1
log_pooler_errors  = 1

# Admin
admin_users        = pgbouncer_admin
stats_users        = pgbouncer_stats
```

```bash
# userlist.txt format
"app_user" "md5_hashed_or_plain_password"
"readonly" "password_here"

# Start pgbouncer
pgbouncer -d /etc/pgbouncer/pgbouncer.ini

# Admin console
psql -h 127.0.0.1 -p 6432 -U pgbouncer_admin pgbouncer

# Useful admin commands:
SHOW POOLS;      -- pool status
SHOW CLIENTS;    -- connected clients
SHOW SERVERS;    -- backend connections
SHOW STATS;      -- aggregate statistics
SHOW CONFIG;     -- current configuration

PAUSE mydb;      -- pause a database (for maintenance)
RESUME mydb;     -- resume a database
RELOAD;          -- reload configuration
```

### Transaction Pooling Limitations

```sql
-- These DO NOT work with transaction pooling (session-scoped state lost):
SET search_path = myschema;      -- lost after transaction
PREPARE stmt AS SELECT ...;      -- prepared statements not preserved
LISTEN/NOTIFY                    -- session-scoped, broken
SET ROLE / SET LOCAL             -- lost between transactions
Advisory locks (session-level)   -- lost between transactions

-- Solutions:
-- Use SET LOCAL inside transactions (transaction-scoped, preserved per pool)
-- Use named prepared statements via protocol (pgbouncer 1.21+ passes these through)
-- Use session pooling for applications that need these features
```

---

## 11. Load Balancing Read Replicas

### Application-Level Routing

```python
# Python example with psycopg2 — route reads to replicas
import psycopg2
from psycopg2 import pool
import random

# Primary: for writes
primary_pool = psycopg2.pool.ThreadedConnectionPool(
    minconn=5, maxconn=20,
    host='192.168.1.10', port=5432,
    database='mydb', user='app', password='secret'
)

# Replicas: for reads
replica_pools = [
    psycopg2.pool.ThreadedConnectionPool(
        minconn=5, maxconn=20,
        host='192.168.1.11', port=5432,
        database='mydb', user='readonly', password='secret'
    ),
    psycopg2.pool.ThreadedConnectionPool(
        minconn=5, maxconn=20,
        host='192.168.1.12', port=5432,
        database='mydb', user='readonly', password='secret'
    ),
]

def get_write_conn():
    return primary_pool.getconn()

def get_read_conn():
    # Round-robin across replicas
    pool = random.choice(replica_pools)
    return pool.getconn()
```

### HAProxy Configuration for Read/Write Split

```
# haproxy.cfg

frontend pg_write
    bind *:5432
    default_backend pg_primary

frontend pg_read
    bind *:5433
    default_backend pg_replicas

backend pg_primary
    option tcp-check
    server primary 192.168.1.10:5432 check inter 5s

backend pg_replicas
    balance roundrobin
    option tcp-check
    server replica1 192.168.1.11:5432 check inter 5s
    server replica2 192.168.1.12:5432 check inter 5s
    server primary  192.168.1.10:5432 check inter 5s backup  # fallback
```

### Patroni-based Load Balancing

```yaml
# application connection strings with Patroni
write_url: "postgres://app:secret@haproxy:5432/mydb"   # port 5432 = primary only
read_url:  "postgres://app:secret@haproxy:5433/mydb"   # port 5433 = any replica

# Patroni REST API for health checks:
# GET http://patroni_node:8008/primary  → 200 if this node is primary
# GET http://patroni_node:8008/replica  → 200 if this node is replica
# GET http://patroni_node:8008/health   → 200 if this node is running
```

---

## 12. Backup Strategies

### pg_dump — Logical Backup

```bash
# Backup a single database (SQL format)
pg_dump \
    --host=localhost \
    --port=5432 \
    --username=postgres \
    --dbname=mydb \
    --format=custom \          # custom format: compressed, parallel restore
    --file=/backups/mydb_$(date +%Y%m%d).dump \
    --verbose

# Parallel backup (custom or directory format)
pg_dump \
    --format=directory \
    --jobs=4 \                 # 4 parallel workers
    --file=/backups/mydb_dir \
    mydb

# Restore
pg_restore \
    --host=localhost \
    --dbname=mydb_restore \
    --format=custom \
    --jobs=4 \                 # parallel restore
    --verbose \
    /backups/mydb_20240315.dump

# Schema only
pg_dump --schema-only mydb > schema.sql

# Data only
pg_dump --data-only mydb > data.sql

# Specific tables only
pg_dump --table=orders --table=customers mydb > selected_tables.dump
```

### pg_basebackup — Physical Backup

```bash
# Full cluster backup (all databases)
pg_basebackup \
    --host=192.168.1.10 \
    --username=replicator \
    --pgdata=/backups/base_$(date +%Y%m%d) \
    --format=tar \             # compressed tar archives
    --compress=9 \             # gzip compression
    --wal-method=stream \      # include WAL changes during backup
    --checkpoint=fast \        # start backup quickly
    --progress \
    --verbose

# Backup from STANDBY (reduces load on primary!)
pg_basebackup \
    --host=192.168.1.11 \      # standby IP
    --username=replicator \
    --pgdata=/backups/base_$(date +%Y%m%d) \
    --wal-method=fetch \       # WAL fetched from archive (not from standby)
    --format=tar \
    --compress=9
```

### Continuous Archiving (WAL Archiving)

```ini
# postgresql.conf — enable WAL archiving
archive_mode    = on
archive_command = 'test ! -f /mnt/wal_archive/%f && cp %p /mnt/wal_archive/%f'

# Or to S3 using WAL-G or pgBackRest:
archive_command = 'wal-g wal-push %p'
# restore_command = 'wal-g wal-fetch %f %p'
```

### pgBackRest — Enterprise Backup Tool

```bash
# pgbackrest.conf
[global]
repo1-path=/var/lib/pgbackrest
repo1-retention-full=4         # keep 4 full backups
repo1-retention-diff=14        # keep 14 differential backups
log-level-console=info
compress-type=lz4

[mydb]
pg1-path=/var/lib/postgresql/14/main
pg1-host=192.168.1.10

# Full backup
pgbackrest --stanza=mydb backup --type=full

# Differential backup (since last full)
pgbackrest --stanza=mydb backup --type=diff

# Incremental backup (since last backup of any type)
pgbackrest --stanza=mydb backup --type=incr

# Restore
pgbackrest --stanza=mydb restore

# PITR restore to specific time
pgbackrest --stanza=mydb restore \
    --target="2024-03-15 10:30:00" \
    --target-action=promote
```

---

## 13. Point-In-Time Recovery (PITR)

PITR lets you restore a database to **any point in time** using a base backup + WAL archive.

### How PITR Works

```
Base Backup                    WAL Archive                    Target Time
(2024-03-01)                (2024-03-01 to 2024-03-15)       2024-03-15 10:30
     │                              │                              │
     └──── restore base ────────────┴──── replay WAL ─────────────►
     starts from consistent state   replays every transaction     stops here
```

### PITR Recovery Configuration

```bash
# Step 1: Restore base backup
rm -rf /var/lib/postgresql/14/main
pg_basebackup --pgdata=/var/lib/postgresql/14/main \
              --host=backup_server ...
# Or restore from pgbackrest / barman / WAL-G
```

```ini
# Step 2: Configure recovery target in postgresql.conf

# Recover to a specific time
recovery_target_time   = '2024-03-15 10:30:00'
recovery_target_action = 'promote'    # become a primary after reaching target

# Or recover to a specific transaction ID
recovery_target_xid    = '12345678'
recovery_target_action = 'promote'

# Or recover to a named restore point
recovery_target_name   = 'before_bad_migration'
recovery_target_action = 'promote'

# Or recover to latest (default)
# recovery_target = 'immediate'  -- stop at end of backup

# WAL source
restore_command = 'cp /mnt/wal_archive/%f %p'
# Or from S3:
restore_command = 'wal-g wal-fetch %f %p'
```

```bash
# Step 3: Create recovery signal
touch /var/lib/postgresql/14/main/recovery.signal

# Step 4: Start PostgreSQL
pg_ctl start -D /var/lib/postgresql/14/main
# PostgreSQL replays WAL until recovery_target_time
# Then promotes to primary (if recovery_target_action = promote)
```

### Create Named Restore Points

```sql
-- Create a labeled restore point before a risky migration
SELECT pg_create_restore_point('before_v2_migration');

-- After a disaster, recover to this point:
-- recovery_target_name = 'before_v2_migration'
```

---

## 14. High Availability Tools

### Patroni — Automated Failover Orchestrator

```yaml
# patroni.yml — configuration for one node

scope: postgres_cluster
namespace: /service/
name: node1

restapi:
  listen: 0.0.0.0:8008
  connect_address: 192.168.1.10:8008

etcd:
  hosts: etcd1:2379,etcd2:2379,etcd3:2379

bootstrap:
  dcs:
    ttl: 30
    loop_wait: 10
    retry_timeout: 10
    maximum_lag_on_failover: 1048576    # 1MB max lag to qualify for failover
    master_start_timeout: 300
    postgresql:
      use_pg_rewind: true
      use_slots: true
      parameters:
        wal_level:            replica
        hot_standby:          "on"
        max_wal_senders:      10
        max_replication_slots: 10
        wal_log_hints:        "on"   # required for pg_rewind

  initdb:
    - encoding: UTF8
    - data-checksums          # required for pg_rewind

postgresql:
  listen: 0.0.0.0:5432
  connect_address: 192.168.1.10:5432
  data_dir: /var/lib/postgresql/14/main
  bin_dir: /usr/lib/postgresql/14/bin
  authentication:
    replication:
      username: replicator
      password: secret
    superuser:
      username: postgres
      password: postgres_secret

tags:
  nofailover: false       # this node can become primary
  noloadbalance: false    # this node can serve reads
```

```bash
# Patronictl commands
patronictl -c patroni.yml list              # cluster status
patronictl -c patroni.yml switchover        # planned switchover
patronictl -c patroni.yml failover          # force failover
patronictl -c patroni.yml reinit node2      # rebuild a standby
patronictl -c patroni.yml pause             # pause auto-failover
patronictl -c patroni.yml resume            # resume auto-failover
patronictl -c patroni.yml edit-config       # edit DCS config
```

### Repmgr — Replication Manager

```bash
# repmgr.conf
node_id=1
node_name=node1
conninfo='host=192.168.1.10 port=5432 user=repmgr dbname=repmgr'
data_directory='/var/lib/postgresql/14/main'
failover=automatic
promote_command='repmgr standby promote -f /etc/repmgr.conf'
follow_command='repmgr standby follow -f /etc/repmgr.conf -W --upstream-node-id=%n'

# Register primary
repmgr -f /etc/repmgr.conf primary register

# Clone and register standby
repmgr -h 192.168.1.10 -U repmgr -d repmgr \
       -f /etc/repmgr.conf standby clone

repmgr -f /etc/repmgr.conf standby register

# View cluster
repmgr -f /etc/repmgr.conf cluster show

# Manual switchover
repmgr -f /etc/repmgr.conf standby switchover

# Test failover
repmgr -f /etc/repmgr.conf cluster show --compact
```

### Keepalived — Virtual IP Failover

```conf
# keepalived.conf — runs on each PostgreSQL node
# Provides a floating VIP that follows the primary

vrrp_script check_postgres {
    script "/usr/local/bin/check_postgres_primary.sh"
    interval 5
    weight -20    # subtract 20 from priority if script fails
}

vrrp_instance VI_POSTGRES {
    state MASTER              # MASTER on primary, BACKUP on standbys
    interface eth0
    virtual_router_id 51
    priority 100              # higher priority = preferred primary
    advert_int 1

    authentication {
        auth_type PASS
        auth_pass secret_vrrp
    }

    virtual_ipaddress {
        192.168.1.100/24      # floating VIP
    }

    track_script {
        check_postgres
    }
}
```

```bash
# check_postgres_primary.sh
#!/bin/bash
# Returns 0 (success) if this node is the PostgreSQL primary
result=$(psql -h localhost -U postgres -t -c "SELECT pg_is_in_recovery();" 2>/dev/null)
if [ "$result" = " f" ]; then
    exit 0   # is primary
else
    exit 1   # is standby or down
fi
```

---

## 15. Monitoring Replication

```sql
-- ─── Primary: Connected standbys and lag ─────────────────────────────────
SELECT
    application_name,
    client_addr,
    state,                               -- startup, catchup, streaming, backup
    sent_lsn,
    write_lsn,
    flush_lsn,
    replay_lsn,
    pg_wal_lsn_diff(sent_lsn, replay_lsn)  AS total_lag_bytes,
    write_lag,                           -- time to write to OS buffer
    flush_lag,                           -- time to flush to disk
    replay_lag,                          -- time to replay
    sync_state,                          -- async, sync, quorum, potential
    sync_priority
FROM pg_stat_replication
ORDER BY replay_lag DESC NULLS LAST;

-- ─── Primary: Replication slots health ───────────────────────────────────
SELECT
    slot_name,
    slot_type,
    active,
    pg_size_pretty(
        pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)
    )                                    AS retained_wal,
    wal_status,                          -- reserved, extended, unreserved, lost
    safe_wal_size
FROM pg_replication_slots
ORDER BY pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn) DESC;

-- ─── Standby: Replication lag ────────────────────────────────────────────
SELECT
    pg_is_in_recovery()                  AS is_standby,
    pg_last_wal_receive_lsn()            AS received_lsn,
    pg_last_wal_replay_lsn()             AS replayed_lsn,
    pg_wal_lsn_diff(
        pg_last_wal_receive_lsn(),
        pg_last_wal_replay_lsn()
    )                                    AS receive_vs_replay_bytes,
    pg_last_xact_replay_timestamp()      AS last_replayed_at,
    NOW() - pg_last_xact_replay_timestamp() AS lag_time;

-- ─── WAL archive monitoring ───────────────────────────────────────────────
SELECT
    archived_count,
    last_archived_wal,
    last_archived_time,
    failed_count,
    last_failed_wal,
    last_failed_time,
    NOW() - last_archived_time           AS archive_lag
FROM pg_stat_archiver;

-- ─── Logical replication monitoring ──────────────────────────────────────
-- On subscriber:
SELECT
    subname,
    pid,
    relid::regclass                      AS table_name,
    received_lsn,
    latest_end_lsn,
    latest_end_time,
    NOW() - latest_end_time              AS lag
FROM pg_stat_subscription
JOIN pg_subscription_rel USING (subid);

-- ─── Alert queries ────────────────────────────────────────────────────────
-- Alert if replication lag > 30 seconds
SELECT application_name, replay_lag
FROM pg_stat_replication
WHERE replay_lag > INTERVAL '30 seconds';

-- Alert if slot retaining > 5GB
SELECT slot_name,
       pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn))
FROM pg_replication_slots
WHERE pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn) > 5 * 1024^3;

-- Alert if archiver failing
SELECT failed_count, last_failed_wal, last_failed_time
FROM pg_stat_archiver
WHERE failed_count > 0;
```

---

## 16. HA Architecture Patterns

### Pattern 1: Simple Primary + Standby

```
                    ┌──────────────────┐
   Application ────►│   PRIMARY        │ 192.168.1.10:5432
   (reads+writes)   │   (hot_standby)  │
                    └────────┬─────────┘
                             │ streaming WAL
                             ▼
                    ┌──────────────────┐
                    │   STANDBY        │ 192.168.1.11:5432
                    │   (read-only)    │
                    └──────────────────┘

Use for:
  - Simple setups needing basic HA
  - Small teams without complex tooling
  - Manual failover acceptable (5-10 min RTO)
```

### Pattern 2: Primary + 2 Standbys + PgBouncer

```
                    ┌──────────────────┐
                    │   PgBouncer      │ :6432 (write)
                    │   PgBouncer      │ :6433 (read, round-robin)
                    └──────┬───────────┘
                           │
           ┌───────────────┼──────────────────┐
           ▼               ▼                  ▼
   ┌──────────────┐ ┌──────────────┐  ┌──────────────┐
   │  PRIMARY     │ │  STANDBY-1   │  │  STANDBY-2   │
   │  (writes)    │ │  (reads)     │  │  (reads/DR)  │
   │  sync commit │ │  async       │  │  different DC│
   └──────────────┘ └──────────────┘  └──────────────┘

Use for:
  - Production workloads
  - Read scaling + HA
  - One standby for local reads, one for DR
```

### Pattern 3: Patroni + etcd Cluster (Production HA)

```
               ┌─────────────────────────────────────────┐
               │          etcd Cluster (3 nodes)          │
               │  (distributed consensus & config store)  │
               └─────────────────────────────────────────┘
                        ↑ leader election ↑
         ┌──────────────┼──────────────────┼──────────────┐
         │              │                  │              │
         ▼              ▼                  ▼              │
  ┌────────────┐  ┌────────────┐  ┌────────────┐         │
  │  Patroni   │  │  Patroni   │  │  Patroni   │         │
  │  node1     │  │  node2     │  │  node3     │         │
  │  PRIMARY   │  │  STANDBY   │  │  STANDBY   │         │
  └────────────┘  └────────────┘  └────────────┘         │
         │              │                  │              │
         └──────────────┴──────────────────┘              │
                        │                                 │
                        ▼                                 │
               ┌──────────────────┐                       │
               │   HAProxy        │                       │
               │  port 5432 → PRI │                       │
               │  port 5433 → ANY │◄──────────────────────┘
               └──────────────────┘
                        │
               Application connections

Failover RTO: 10-30 seconds (automatic)
RPO:          0 seconds with synchronous commit
```

### Pattern 4: Multi-Region Active-Passive

```
          Region A (Primary)              Region B (DR)
     ┌─────────────────────────┐    ┌─────────────────────────┐
     │  Patroni Cluster        │    │  Patroni Cluster         │
     │  ┌──────┐  ┌──────┐    │    │  ┌──────┐  ┌──────┐    │
     │  │ PRI  │──│ STB  │    │    │  │ STB  │  │ STB  │    │
     │  └──────┘  └──────┘    │    │  └──────┘  └──────┘    │
     │       (sync)            │    │       (async)           │
     └───────────┬─────────────┘    └────────────────────────┘
                 │                            ▲
                 └────── WAL streaming ────────┘
                         (cross-region)

     All writes → Region A
     All reads  → Region A or Region B
     Failover   → Promote Region B manually or via DNS failover

Use for:
  - Disaster recovery across data centers
  - Compliance (geo-distributed data)
  - RTO: 1-5 minutes (manual) or automated with DNS
```

### Pattern 5: Logical Replication for Upgrades

```
Version 14 (current)                    Version 16 (new)
┌──────────────────────┐               ┌──────────────────────┐
│  PostgreSQL 14       │               │  PostgreSQL 16        │
│  PRIMARY             │──[logical]───►│  Subscriber          │
│  Publication: all    │               │  (catching up)        │
└──────────────────────┘               └──────────────────────┘

Zero-downtime major version upgrade steps:
1. Set up PG16 as logical subscriber from PG14
2. Wait for lag = 0
3. Switch application writes to PG16 (brief pause)
4. PG16 becomes new primary
5. Decommission PG14
```

---

## 17. Quick Reference Cheat Sheet

```
╔═══════════════════════════╦══════════════════════════════════════════════════╗
║ TOPIC                     ║ KEY COMMANDS / NOTES                             ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ WAL Level                 ║ wal_level = replica  (streaming)                 ║
║                           ║ wal_level = logical  (logical replication)       ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Base Backup               ║ pg_basebackup --wal-method=stream --write-recovery-conf ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Standby Signal            ║ touch $PGDATA/standby.signal  (PG12+)            ║
║ Promote                   ║ pg_ctl promote  OR  SELECT pg_promote()          ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Replication Slots         ║ SELECT pg_create_physical_replication_slot('name')║
║ Slot Risk                 ║ Unused slots fill disk — monitor and drop        ║
║ Slot Safety               ║ max_slot_wal_keep_size = 10GB                    ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Sync Commit Levels        ║ off < local < remote_write < on < remote_apply   ║
║ Zero Data Loss            ║ synchronous_commit = remote_apply                ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Logical Replication       ║ CREATE PUBLICATION pub FOR TABLE t1, t2          ║
║                           ║ CREATE SUBSCRIPTION sub CONNECTION '...' PUBLICATION pub ║
║ Restrictions              ║ DDL not replicated, sequences not replicated     ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Monitor (Primary)         ║ SELECT * FROM pg_stat_replication                ║
║ Monitor (Standby)         ║ SELECT pg_last_xact_replay_timestamp(),          ║
║                           ║        NOW() - pg_last_xact_replay_timestamp()   ║
║ Monitor Slots             ║ SELECT * FROM pg_replication_slots               ║
║ Monitor Archiver          ║ SELECT * FROM pg_stat_archiver                   ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Hot Standby               ║ hot_standby = on                                 ║
║ Delayed Standby           ║ recovery_min_apply_delay = '2h'                  ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ pg_rewind                 ║ Resync diverged primary — needs wal_log_hints=on ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ PITR                      ║ recovery_target_time = '2024-03-15 10:30:00'     ║
║                           ║ recovery_target_action = promote                 ║
║ Named Restore Point       ║ SELECT pg_create_restore_point('label')          ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ PgBouncer Pool Mode       ║ transaction (recommended for most apps)          ║
║ Not compatible with       ║ SET, PREPARE, LISTEN, session advisory locks     ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ HA Tools                  ║ Patroni   — automated failover with etcd/consul  ║
║                           ║ Repmgr    — simpler failover management          ║
║                           ║ Keepalived — floating VIP for transparent switch ║
║                           ║ HAProxy   — TCP load balancing + health checks   ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ RTO / RPO Guide           ║ Manual failover:   RTO 5-15min, RPO seconds      ║
║                           ║ Patroni auto:      RTO 10-30s,  RPO 0 (sync)     ║
║                           ║ Async replication: RTO varies,  RPO seconds      ║
║                           ║ Sync replication:  RTO varies,  RPO 0            ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Backup Tools              ║ pg_dump        — logical, single DB              ║
║                           ║ pg_basebackup  — physical, full cluster          ║
║                           ║ pgBackRest     — enterprise, incremental, PITR   ║
║                           ║ WAL-G          — WAL archiving to cloud storage  ║
╚═══════════════════════════╩══════════════════════════════════════════════════╝
```

---

## Further Reading

- [PostgreSQL Docs — High Availability](https://www.postgresql.org/docs/current/high-availability.html)
- [PostgreSQL Docs — Streaming Replication](https://www.postgresql.org/docs/current/warm-standby.html)
- [PostgreSQL Docs — Logical Replication](https://www.postgresql.org/docs/current/logical-replication.html)
- [PostgreSQL Docs — WAL Configuration](https://www.postgresql.org/docs/current/wal-configuration.html)
- [PostgreSQL Docs — Recovery Configuration](https://www.postgresql.org/docs/current/recovery-config.html)
- [Patroni Documentation](https://patroni.readthedocs.io/)
- [pgBackRest Documentation](https://pgbackrest.org/)
- [PgBouncer Documentation](https://www.pgbouncer.org/)
- [Repmgr Documentation](https://repmgr.org/)
- [WAL-G](https://github.com/wal-g/wal-g) — WAL archiving to S3/GCS/Azure

---

*Generated with love for PostgreSQL engineers.*
