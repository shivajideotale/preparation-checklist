# PostgreSQL — Fundamentals Complete Reference

> A deep-dive guide covering databases, schemas, data types, DDL, DML, constraints, users, roles, sequences, views, and core SQL in PostgreSQL — the complete foundation every engineer needs.

---

## Table of Contents

1.  [What is PostgreSQL?](#1-what-is-postgresql)
2.  [Architecture Overview](#2-architecture-overview)
3.  [Databases & Schemas](#3-databases--schemas)
4.  [Data Types](#4-data-types)
5.  [DDL — Creating & Managing Tables](#5-ddl--creating--managing-tables)
6.  [Constraints](#6-constraints)
7.  [DML — INSERT, UPDATE, DELETE](#7-dml--insert-update-delete)
8.  [SELECT — Querying Data](#8-select--querying-data)
9.  [Joins](#9-joins)
10. [Aggregation & Grouping](#10-aggregation--grouping)
11. [Sequences & Auto-Increment](#11-sequences--auto-increment)
12. [Views](#12-views)
13. [Indexes — Basics](#13-indexes--basics)
14. [Users, Roles & Permissions](#14-users-roles--permissions)
15. [Transactions — Basics](#15-transactions--basics)
16. [psql CLI Reference](#16-psql-cli-reference)
17. [Quick Reference Cheat Sheet](#17-quick-reference-cheat-sheet)

---

## 1. What is PostgreSQL?

**PostgreSQL** (also called "Postgres") is a free, open-source, **object-relational database management system (ORDBMS)**. It has been actively developed for over 35 years and is known for reliability, standards compliance, and an extremely rich feature set.

### Key Facts

```
Full name    : PostgreSQL (pronounced "post-gress-Q-L")
License      : PostgreSQL License (free, open source)
First release: 1989 (as POSTGRES), 1996 (as PostgreSQL)
Latest stable: PostgreSQL 16
Default port : 5432
Config file  : postgresql.conf
Auth file    : pg_hba.conf
Data dir     : $PGDATA  (e.g., /var/lib/postgresql/16/main)
```

### Why PostgreSQL?

```
✅ ACID compliant                 ✅ Full-text search
✅ MVCC (no read locks)           ✅ JSON / JSONB support
✅ Extensible (custom types)      ✅ Partitioning
✅ Window functions               ✅ Logical & streaming replication
✅ Recursive queries (CTEs)       ✅ Parallel query execution
✅ Row-level security             ✅ Foreign Data Wrappers (FDW)
✅ Stored procedures & triggers   ✅ Geospatial (PostGIS)
✅ Table inheritance              ✅ Free & open source
```

### PostgreSQL vs Other Databases

| Feature | PostgreSQL | MySQL | SQL Server | Oracle |
|---------|------------|-------|------------|--------|
| License | Open source | Open source | Commercial | Commercial |
| ACID | Full | Partial (MyISAM) | Full | Full |
| JSON | JSONB (native) | JSON | JSON | JSON |
| Window Functions | Full | Limited | Full | Full |
| CTEs / Recursive | Full | PG8+ | Full | Full |
| Partitioning | Full (native) | Limited | Full | Full |
| Extensions | Hundreds | Few | Few | Few |
| Array Types | Native | No | No | No |
| Cost | Free | Free / Commercial | Commercial | Commercial |

---

## 2. Architecture Overview

### Process Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    PostgreSQL Server                        │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │                  Shared Memory                        │  │
│  │   shared_buffers   │   WAL buffers   │   Lock table  │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                             │
│  Background Processes:                                      │
│  ┌──────────────┐  ┌──────────┐  ┌──────────────────────┐  │
│  │  Postmaster  │  │ WAL      │  │  Autovacuum workers  │  │
│  │  (listener)  │  │ writer   │  │  Checkpointer        │  │
│  └──────────────┘  └──────────┘  │  Background writer   │  │
│                                  │  Stats collector      │  │
│                                  └──────────────────────┘  │
│                                                             │
│  Per-Connection Backend Processes:                          │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐        │
│  │  Backend 1  │  │  Backend 2  │  │  Backend 3  │        │
│  │  (App conn) │  │  (App conn) │  │  (psql)     │        │
│  └─────────────┘  └─────────────┘  └─────────────┘        │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Storage Hierarchy

```
Cluster (one $PGDATA directory)
│
├── Database 1  (e.g., myapp)
│   ├── Schema 1  (public)
│   │   ├── Table:  users
│   │   ├── Table:  orders
│   │   ├── Index:  idx_users_email
│   │   ├── View:   active_users
│   │   └── Sequence: users_id_seq
│   │
│   └── Schema 2  (analytics)
│       ├── Table:  reports
│       └── View:   monthly_summary
│
├── Database 2  (e.g., myapp_test)
│
└── Database 3  (postgres — default admin DB)
```

### Query Execution Flow

```
Client SQL
   │
   ▼
Parser          → checks SQL syntax, builds parse tree
   │
   ▼
Analyzer        → resolves names, checks semantics
   │
   ▼
Rewriter        → applies rules (views expanded here)
   │
   ▼
Planner/Optimizer → generates best execution plan using statistics
   │
   ▼
Executor        → runs the plan, returns rows
   │
   ▼
Client Results
```

---

## 3. Databases & Schemas

### Databases

```sql
-- Create a database
CREATE DATABASE myapp;
CREATE DATABASE myapp_test
    WITH
    OWNER     = myapp_user
    ENCODING  = 'UTF8'
    LC_COLLATE = 'en_US.UTF-8'
    LC_CTYPE   = 'en_US.UTF-8'
    TEMPLATE  = template0
    CONNECTION LIMIT = 100;

-- Connect to a database (psql)
\c myapp

-- List all databases
\l
-- or:
SELECT datname, pg_size_pretty(pg_database_size(datname)) AS size
FROM pg_database
ORDER BY pg_database_size(datname) DESC;

-- Rename a database
ALTER DATABASE myapp_test RENAME TO myapp_staging;

-- Change owner
ALTER DATABASE myapp OWNER TO new_owner;

-- Drop a database (must not be connected to it)
DROP DATABASE IF EXISTS myapp_test;

-- Copy a database using template
CREATE DATABASE myapp_copy TEMPLATE myapp;
```

### Schemas

A **schema** is a namespace inside a database. Tables, views, indexes, and functions live inside schemas.

```sql
-- Default schema is "public"
-- Create schemas
CREATE SCHEMA analytics;
CREATE SCHEMA IF NOT EXISTS reporting;
CREATE SCHEMA sales AUTHORIZATION sales_user;   -- owned by sales_user

-- List schemas
\dn
-- or:
SELECT schema_name, schema_owner
FROM information_schema.schemata
ORDER BY schema_name;

-- Create table in specific schema
CREATE TABLE analytics.page_views (
    id         BIGSERIAL PRIMARY KEY,
    page       TEXT,
    viewed_at  TIMESTAMPTZ DEFAULT NOW()
);

-- Set search path (which schemas are searched by default)
SET search_path = analytics, public;
-- Now: SELECT * FROM page_views; -- finds analytics.page_views first

-- Permanent search_path for a user
ALTER ROLE myapp_user SET search_path = myapp_schema, public;

-- Drop schema
DROP SCHEMA analytics;                  -- fails if not empty
DROP SCHEMA analytics CASCADE;          -- drops schema + all objects inside

-- Rename schema
ALTER SCHEMA analytics RENAME TO insights;

-- Change schema owner
ALTER SCHEMA analytics OWNER TO analytics_user;
```

---

## 4. Data Types

### Numeric Types

```sql
-- Integer types
SMALLINT          -- 2 bytes, -32768 to +32767
INTEGER  / INT    -- 4 bytes, -2.1B to +2.1B   (most common)
BIGINT            -- 8 bytes, -9.2 × 10^18 to +9.2 × 10^18

-- Auto-increment shorthand (creates a sequence)
SMALLSERIAL       -- SMALLINT with auto-increment
SERIAL            -- INTEGER  with auto-increment
BIGSERIAL         -- BIGINT   with auto-increment

-- Exact decimal (no floating-point rounding)
NUMERIC(precision, scale)  -- e.g., NUMERIC(10,2) for 12345678.99
DECIMAL(precision, scale)  -- synonym for NUMERIC

-- Floating-point (approximate)
REAL              -- 4 bytes, 6 decimal digits precision
DOUBLE PRECISION  -- 8 bytes, 15 decimal digits precision
FLOAT             -- alias for DOUBLE PRECISION

-- Examples
salary      NUMERIC(12, 2)   -- 9999999999.99 max
temperature REAL             -- approximate: 36.6
price       DECIMAL(10, 2)   -- 99999999.99 max
big_id      BIGINT           -- very large integers
```

### Character Types

```sql
CHAR(n)            -- fixed-length, blank-padded (rarely useful)
VARCHAR(n)         -- variable-length up to n characters
TEXT               -- variable-length, unlimited (PREFERRED in PostgreSQL)
-- Note: TEXT and VARCHAR have identical performance in PostgreSQL
-- Use TEXT unless you need a length constraint

-- Examples
name        TEXT             -- no length limit
code        CHAR(3)          -- always 3 chars: 'USD', 'INR'
description VARCHAR(500)     -- up to 500 chars
email       TEXT             -- best to use TEXT + CHECK constraint
```

### Date & Time Types

```sql
DATE                         -- date only: '2024-03-15'
TIME                         -- time only: '10:30:00'
TIME WITH TIME ZONE          -- time with tz: '10:30:00+05:30'
TIMESTAMP                    -- date + time (no tz): '2024-03-15 10:30:00'
TIMESTAMPTZ                  -- date + time WITH timezone (PREFERRED)
INTERVAL                     -- duration: '1 year 2 months', '3 days', '2 hours'

-- Examples
birth_date   DATE
created_at   TIMESTAMPTZ DEFAULT NOW()    -- store in UTC
event_time   TIME
duration     INTERVAL
meeting_at   TIMESTAMPTZ WITH TIME ZONE

-- Date literals
SELECT '2024-03-15'::DATE;
SELECT '2024-03-15 10:30:00+05:30'::TIMESTAMPTZ;
SELECT NOW();                 -- current timestamp with tz
SELECT CURRENT_DATE;          -- today's date
SELECT CURRENT_TIME;          -- current time
```

### Boolean Type

```sql
BOOLEAN   -- true / false / NULL

-- Accepted true values:  true, 't', 'yes', 'y', 'on', '1'
-- Accepted false values: false, 'f', 'no', 'n', 'off', '0'

is_active   BOOLEAN DEFAULT true
is_verified BOOLEAN DEFAULT false
has_paid    BOOLEAN

SELECT true, false, NULL::BOOLEAN;
SELECT * FROM users WHERE is_active = true;
SELECT * FROM users WHERE is_active;          -- same as = true
SELECT * FROM users WHERE NOT is_active;      -- is_active = false
```

### UUID Type

```sql
UUID   -- Universally Unique Identifier
       -- 128-bit, stored as 16 bytes
       -- Format: 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'

-- Generate UUIDs
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
SELECT gen_random_uuid();                  -- v4 random UUID (pgcrypto)

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
SELECT uuid_generate_v4();                -- v4 random
SELECT uuid_generate_v1();                -- v1 time-based

-- UUID as primary key (good for distributed systems)
CREATE TABLE events (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
```

### JSON & JSONB

```sql
JSON    -- stores exact JSON text (preserves whitespace, key order)
JSONB   -- stores parsed binary JSON (faster, indexed, PREFERRED)

-- Examples
settings    JSONB DEFAULT '{}'
metadata    JSONB
tags        JSONB   -- store arrays as JSON

-- JSONB operators
SELECT metadata -> 'level'              -- returns JSON
SELECT metadata ->> 'level'             -- returns TEXT
SELECT metadata @> '{"level":"senior"}' -- contains check
SELECT metadata ? 'bonus'               -- key exists

-- When to use JSON vs JSONB:
-- JSON:  preserve exact input (e.g., audit of original payload)
-- JSONB: everything else — faster reads, indexable, operators work
```

### Array Types

```sql
TEXT[]          -- array of text
INTEGER[]       -- array of integers
NUMERIC[]
BOOLEAN[]

-- Examples
tags        TEXT[]    DEFAULT '{}'
scores      INTEGER[]
permissions TEXT[]

-- Array literals
SELECT ARRAY[1, 2, 3];
SELECT '{1,2,3}'::INTEGER[];
SELECT ARRAY['read', 'write', 'admin'];

-- Array operations
SELECT tags @> ARRAY['python']          -- contains
SELECT tags && ARRAY['java', 'python']  -- overlap
SELECT ARRAY_APPEND(tags, 'go')         -- add element
SELECT UNNEST(tags)                     -- expand to rows
SELECT CARDINALITY(tags)                -- array length
```

### Network Types

```sql
INET     -- IPv4 or IPv6 address:  '192.168.1.1', '::1'
CIDR     -- network block:         '192.168.1.0/24'
MACADDR  -- MAC address:           '08:00:2b:01:02:03'

-- Examples
ip_address   INET
subnet       CIDR
mac_addr     MACADDR

-- Operators
SELECT '192.168.1.5'::INET << '192.168.1.0/24'::CIDR;  -- contained in subnet
```

### Range Types

```sql
INT4RANGE   -- range of INTEGER
INT8RANGE   -- range of BIGINT
NUMRANGE    -- range of NUMERIC
DATERANGE   -- range of DATE
TSRANGE     -- range of TIMESTAMP
TSTZRANGE   -- range of TIMESTAMPTZ

-- Examples
stay        DATERANGE    -- hotel booking: '[2024-03-01, 2024-03-07)'
valid_for   TSTZRANGE    -- subscription validity period
price_range NUMRANGE     -- '[10.00, 50.00)'

-- Operators
SELECT '[2024-01-01, 2024-06-30]'::DATERANGE @> '2024-03-15'::DATE;  -- contains
SELECT '[1,10]'::INT4RANGE && '[5,15]'::INT4RANGE;   -- overlaps
```

### Other Notable Types

```sql
BYTEA          -- binary data (bytes)
BIT(n)         -- fixed-length bit string
VARBIT(n)      -- variable-length bit string
MONEY          -- currency (locale-dependent, use NUMERIC instead)
POINT          -- geometric point (x, y)
LINE           -- infinite line
POLYGON        -- polygon
CIRCLE         -- circle
TSVECTOR       -- full-text search document
TSQUERY        -- full-text search query
XML            -- XML data
OID            -- object identifier (internal)
```

### Type Casting

```sql
-- Cast syntax
SELECT '42'::INTEGER;
SELECT '2024-03-15'::DATE;
SELECT 3.14::NUMERIC(5,2);
SELECT 100::TEXT;
SELECT CAST('42' AS INTEGER);       -- ANSI SQL style

-- Implicit vs Explicit casts
SELECT 1 + 1.5;          -- integer + numeric → numeric (implicit)
SELECT '10' + 5;         -- ERROR: no implicit cast text→integer
SELECT '10'::INT + 5;    -- OK: explicit cast

-- pg_typeof: check the type of an expression
SELECT pg_typeof(42);            -- integer
SELECT pg_typeof(42.0);          -- numeric
SELECT pg_typeof('hello');       -- unknown / text
SELECT pg_typeof(NOW());         -- timestamp with time zone
```

---

## 5. DDL — Creating & Managing Tables

### CREATE TABLE

```sql
-- Complete table creation example
CREATE TABLE employees (
    id          BIGSERIAL       PRIMARY KEY,
    name        TEXT            NOT NULL,
    email       TEXT            NOT NULL UNIQUE,
    phone       TEXT,
    salary      NUMERIC(12, 2)  NOT NULL DEFAULT 0,
    department  TEXT            NOT NULL,
    manager_id  BIGINT          REFERENCES employees(id),
    joined_at   DATE            NOT NULL DEFAULT CURRENT_DATE,
    is_active   BOOLEAN         NOT NULL DEFAULT true,
    metadata    JSONB           NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);
```

### CREATE TABLE AS (CTAS)

```sql
-- Create a new table from a SELECT result
CREATE TABLE engineering_employees AS
SELECT id, name, email, salary
FROM employees
WHERE department = 'Engineering';

-- Create table with same structure but no data
CREATE TABLE employees_backup (LIKE employees INCLUDING ALL);
-- INCLUDING ALL copies: defaults, constraints, indexes, storage settings
-- Alternatives: INCLUDING DEFAULTS | INCLUDING CONSTRAINTS | INCLUDING INDEXES
```

### ALTER TABLE

```sql
-- Add a column
ALTER TABLE employees ADD COLUMN rating SMALLINT;
ALTER TABLE employees ADD COLUMN bio TEXT DEFAULT '';

-- Drop a column
ALTER TABLE employees DROP COLUMN bio;
ALTER TABLE employees DROP COLUMN IF EXISTS rating;
ALTER TABLE employees DROP COLUMN bio CASCADE;   -- also drops dependent objects

-- Rename a column
ALTER TABLE employees RENAME COLUMN phone TO phone_number;

-- Change column type
ALTER TABLE employees ALTER COLUMN salary TYPE BIGINT;
ALTER TABLE employees ALTER COLUMN rating TYPE NUMERIC(4,2)
    USING rating::NUMERIC(4,2);     -- USING clause for type conversion

-- Set / remove DEFAULT
ALTER TABLE employees ALTER COLUMN is_active SET DEFAULT true;
ALTER TABLE employees ALTER COLUMN rating DROP DEFAULT;

-- Set / remove NOT NULL
ALTER TABLE employees ALTER COLUMN phone SET NOT NULL;
ALTER TABLE employees ALTER COLUMN phone DROP NOT NULL;

-- Rename table
ALTER TABLE employees RENAME TO staff;

-- Change table owner
ALTER TABLE employees OWNER TO hr_admin;

-- Move to different schema
ALTER TABLE employees SET SCHEMA hr;

-- Set storage parameters
ALTER TABLE employees SET (fillfactor = 80);
ALTER TABLE employees SET (autovacuum_vacuum_scale_factor = 0.01);
```

### DROP TABLE

```sql
DROP TABLE employees;                 -- fails if depended on by others
DROP TABLE IF EXISTS employees;       -- no error if not found
DROP TABLE employees CASCADE;         -- also drops FK references, views, etc.
DROP TABLE employees RESTRICT;        -- explicit: fail if dependencies exist

-- Drop multiple tables
DROP TABLE IF EXISTS orders, order_items, products;
```

### TRUNCATE

```sql
-- Remove all rows — much faster than DELETE (no WHERE clause)
TRUNCATE employees;                        -- empty the table
TRUNCATE employees RESTART IDENTITY;       -- also reset sequences
TRUNCATE employees, orders CASCADE;        -- truncate + referenced tables
TRUNCATE employees RESTRICT;               -- fail if FK references exist
```

### TEMP Tables

```sql
-- Temporary table: exists only for the current session
CREATE TEMP TABLE session_data (
    key   TEXT,
    value TEXT
);

-- OR TEMPORARY keyword
CREATE TEMPORARY TABLE temp_calc AS
SELECT customer_id, SUM(amount) AS total
FROM orders
GROUP BY customer_id;

-- Temp tables are automatically dropped at session end
-- Drop manually:
DROP TABLE IF EXISTS session_data;
```

---

## 6. Constraints

Constraints enforce data integrity rules at the database level.

### PRIMARY KEY

```sql
-- Single column PK (inline)
CREATE TABLE users (
    id   SERIAL PRIMARY KEY,
    name TEXT
);

-- Composite PK (table-level)
CREATE TABLE order_items (
    order_id    INTEGER,
    product_id  INTEGER,
    quantity    INTEGER,
    PRIMARY KEY (order_id, product_id)
);

-- Add PK to existing table
ALTER TABLE employees ADD PRIMARY KEY (id);

-- Drop PK
ALTER TABLE employees DROP CONSTRAINT employees_pkey;
```

### NOT NULL

```sql
-- Inline
name TEXT NOT NULL

-- Add NOT NULL to existing column
ALTER TABLE employees ALTER COLUMN name SET NOT NULL;

-- Remove NOT NULL
ALTER TABLE employees ALTER COLUMN phone DROP NOT NULL;
```

### UNIQUE

```sql
-- Single column UNIQUE (inline)
email TEXT UNIQUE

-- Named unique constraint
email TEXT,
CONSTRAINT uq_employees_email UNIQUE (email)

-- Composite UNIQUE
CONSTRAINT uq_name_dept UNIQUE (name, department)

-- Add UNIQUE to existing table
ALTER TABLE employees ADD CONSTRAINT uq_employees_email UNIQUE (email);

-- UNIQUE with NULL: NULLs are NOT considered equal
-- Multiple rows can have NULL in a UNIQUE column
INSERT INTO users (email) VALUES (NULL);  -- OK
INSERT INTO users (email) VALUES (NULL);  -- OK (NULL != NULL)
INSERT INTO users (email) VALUES ('a@b.com');
INSERT INTO users (email) VALUES ('a@b.com');  -- ERROR: duplicate
```

### CHECK

```sql
-- Inline CHECK
salary NUMERIC CHECK (salary >= 0)
age    INTEGER CHECK (age BETWEEN 18 AND 120)
email  TEXT    CHECK (email LIKE '%@%')

-- Named CHECK constraint
salary NUMERIC,
CONSTRAINT chk_salary_positive CHECK (salary >= 0 AND salary <= 10000000)

-- Multi-column CHECK
start_date DATE,
end_date   DATE,
CONSTRAINT chk_date_order CHECK (end_date >= start_date)

-- Add CHECK to existing table
ALTER TABLE employees
ADD CONSTRAINT chk_emp_salary CHECK (salary BETWEEN 0 AND 5000000);

-- Drop a constraint
ALTER TABLE employees DROP CONSTRAINT chk_emp_salary;
```

### FOREIGN KEY

```sql
-- Inline FK
CREATE TABLE orders (
    id          SERIAL PRIMARY KEY,
    customer_id INTEGER REFERENCES customers(id)    -- implicit NOT NULL? No.
);

-- Full FK with actions
CREATE TABLE orders (
    id          SERIAL PRIMARY KEY,
    customer_id INTEGER NOT NULL,
    CONSTRAINT fk_orders_customer
        FOREIGN KEY (customer_id)
        REFERENCES customers(id)
        ON DELETE RESTRICT      -- prevent delete if orders exist
        ON UPDATE CASCADE       -- update FK if PK changes
);

-- ON DELETE / ON UPDATE options:
-- RESTRICT    : error if referenced row would be deleted/updated
-- NO ACTION   : like RESTRICT but checked at end of transaction (default)
-- CASCADE     : delete/update child rows automatically
-- SET NULL    : set FK column to NULL
-- SET DEFAULT : set FK column to its DEFAULT value

-- Add FK to existing table
ALTER TABLE orders
ADD CONSTRAINT fk_orders_customer
    FOREIGN KEY (customer_id) REFERENCES customers(id)
    ON DELETE CASCADE;

-- Temporarily disable FK checking (for bulk loads)
ALTER TABLE orders DISABLE TRIGGER ALL;   -- disables FK triggers
-- ... bulk load ...
ALTER TABLE orders ENABLE TRIGGER ALL;

-- Deferrable FK (check at COMMIT instead of per-statement)
CONSTRAINT fk_orders_customer
    FOREIGN KEY (customer_id) REFERENCES customers(id)
    DEFERRABLE INITIALLY DEFERRED
```

### DEFAULT Values

```sql
-- Literals
status      TEXT        DEFAULT 'pending'
is_active   BOOLEAN     DEFAULT true
score       INTEGER     DEFAULT 0

-- Functions
created_at  TIMESTAMPTZ DEFAULT NOW()
joined_at   DATE        DEFAULT CURRENT_DATE
uuid_col    UUID        DEFAULT gen_random_uuid()
token       TEXT        DEFAULT MD5(RANDOM()::TEXT)

-- Expressions
expiry      DATE        DEFAULT CURRENT_DATE + INTERVAL '30 days'
```

### GENERATED Columns (PG 12+)

```sql
-- GENERATED ALWAYS AS: computed from other columns, stored on disk
CREATE TABLE rectangles (
    width   NUMERIC,
    height  NUMERIC,
    area    NUMERIC GENERATED ALWAYS AS (width * height) STORED
);

INSERT INTO rectangles(width, height) VALUES (5, 3);
SELECT * FROM rectangles;
-- width=5, height=3, area=15 (computed automatically)

-- Full-text search vector (common use case)
CREATE TABLE articles (
    id      SERIAL PRIMARY KEY,
    title   TEXT,
    body    TEXT,
    tsv     TSVECTOR GENERATED ALWAYS AS (
        to_tsvector('english', COALESCE(title,'') || ' ' || COALESCE(body,''))
    ) STORED
);
CREATE INDEX idx_articles_fts ON articles USING gin(tsv);
```

---

## 7. DML — INSERT, UPDATE, DELETE

### INSERT

```sql
-- Single row
INSERT INTO employees (name, email, salary, department)
VALUES ('Alice Johnson', 'alice@co.com', 95000, 'Engineering');

-- Multiple rows
INSERT INTO employees (name, email, salary, department) VALUES
    ('Bob Smith',    'bob@co.com',   72000, 'Engineering'),
    ('Carol Davis',  'carol@co.com', 65000, 'Marketing'),
    ('Dan Brown',    'dan@co.com',   88000, 'Sales');

-- Insert from SELECT
INSERT INTO employees_archive (name, email, salary, department, left_at)
SELECT name, email, salary, department, NOW()
FROM employees
WHERE is_active = false;

-- Insert with defaults (omit column)
INSERT INTO employees (name, department)
VALUES ('Eve Wilson', 'HR');
-- salary gets DEFAULT 0, is_active gets DEFAULT true, etc.

-- Insert ALL columns (must match table order exactly)
INSERT INTO employees
VALUES (DEFAULT, 'Frank', 'frank@co.com', NULL, 75000,
        'Finance', NULL, CURRENT_DATE, true, '{}', NOW(), NOW());

-- RETURNING — get inserted row back
INSERT INTO employees (name, email, salary, department)
VALUES ('Grace', 'grace@co.com', 80000, 'Engineering')
RETURNING id, name, created_at;

-- ON CONFLICT — upsert
INSERT INTO employees (email, name, salary, department)
VALUES ('alice@co.com', 'Alice Updated', 100000, 'Engineering')
ON CONFLICT (email) DO UPDATE
    SET name       = EXCLUDED.name,
        salary     = EXCLUDED.salary,
        updated_at = NOW();

-- ON CONFLICT DO NOTHING
INSERT INTO employees (email, name, salary, department)
VALUES ('alice@co.com', 'Alice', 95000, 'Engineering')
ON CONFLICT (email) DO NOTHING;
```

### UPDATE

```sql
-- Update specific rows
UPDATE employees
SET salary = 100000
WHERE id = 1;

-- Update multiple columns
UPDATE employees
SET salary     = salary * 1.10,
    updated_at = NOW()
WHERE department = 'Engineering'
  AND is_active = true;

-- Update with computation
UPDATE employees
SET metadata = metadata || jsonb_build_object('last_raise', NOW())
WHERE salary < 60000;

-- Update from another table (JOIN-style)
UPDATE employees
SET department = d.new_name
FROM department_renames d
WHERE employees.department = d.old_name;

-- RETURNING — see what changed
UPDATE employees
SET salary = salary * 1.15
WHERE department = 'Sales'
RETURNING id, name, salary AS new_salary;

-- Update with subquery
UPDATE orders
SET status = 'vip'
WHERE customer_id IN (
    SELECT id FROM customers WHERE segment = 'gold'
);
```

### DELETE

```sql
-- Delete specific rows
DELETE FROM employees WHERE id = 5;

-- Delete with condition
DELETE FROM orders
WHERE status = 'cancelled'
  AND created_at < NOW() - INTERVAL '1 year';

-- Delete all rows (slow — use TRUNCATE for full table wipe)
DELETE FROM temp_data;

-- Delete with JOIN (using USING clause)
DELETE FROM orders
USING customers
WHERE orders.customer_id = customers.id
  AND customers.is_blocked = true;

-- Delete with subquery
DELETE FROM employees
WHERE id IN (
    SELECT id FROM employees WHERE department = 'Temp'
);

-- RETURNING — see deleted rows
DELETE FROM employees
WHERE is_active = false
RETURNING id, name, email;

-- Delete with CTE
WITH to_delete AS (
    SELECT id FROM orders
    WHERE status = 'pending'
      AND created_at < NOW() - INTERVAL '7 days'
    LIMIT 1000
)
DELETE FROM orders
USING to_delete
WHERE orders.id = to_delete.id;
```

---

## 8. SELECT — Querying Data

### Basic SELECT

```sql
-- All columns
SELECT * FROM employees;

-- Specific columns
SELECT id, name, email, salary FROM employees;

-- Column aliases
SELECT
    id,
    name        AS employee_name,
    salary      AS annual_salary,
    salary / 12 AS monthly_salary
FROM employees;

-- Computed columns
SELECT
    name,
    salary,
    salary * 1.10                AS with_raise,
    UPPER(name)                  AS name_upper,
    LENGTH(name)                 AS name_length,
    CURRENT_DATE - joined_at     AS days_employed
FROM employees;

-- Distinct values
SELECT DISTINCT department FROM employees ORDER BY department;
SELECT DISTINCT ON (department) department, name, salary
FROM employees ORDER BY department, salary DESC;
```

### WHERE Clause

```sql
-- Comparison operators
WHERE salary = 95000
WHERE salary != 95000    -- or <>
WHERE salary > 80000
WHERE salary >= 80000
WHERE salary < 60000
WHERE salary <= 60000

-- Range
WHERE salary BETWEEN 60000 AND 100000   -- inclusive both ends
WHERE joined_at BETWEEN '2020-01-01' AND '2022-12-31'

-- List membership
WHERE department IN ('Engineering', 'Marketing', 'Sales')
WHERE id NOT IN (1, 2, 3)

-- Pattern matching
WHERE name LIKE 'A%'             -- starts with A
WHERE name LIKE '%son'           -- ends with son
WHERE name LIKE '%ali%'          -- contains ali
WHERE name ILIKE '%alice%'       -- case-insensitive
WHERE email NOT LIKE '%@test.%'  -- exclude test emails

-- NULL checks (always use IS NULL, not = NULL)
WHERE manager_id IS NULL         -- no manager (top-level)
WHERE phone IS NOT NULL          -- has a phone number

-- Boolean
WHERE is_active = true
WHERE is_active                  -- shorthand for = true
WHERE NOT is_active              -- is_active = false

-- Combine with AND / OR / NOT
WHERE department = 'Engineering' AND salary > 80000
WHERE department = 'HR' OR department = 'Finance'
WHERE NOT (salary < 50000 OR is_active = false)

-- Subquery in WHERE
WHERE id IN (SELECT customer_id FROM orders WHERE amount > 10000)
WHERE EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = employees.id)
```

### ORDER BY

```sql
SELECT name, salary FROM employees
ORDER BY salary DESC;          -- descending

ORDER BY salary ASC;           -- ascending (default)
ORDER BY salary DESC, name ASC -- multiple columns

-- NULL handling
ORDER BY salary DESC NULLS LAST    -- NULLs go to end
ORDER BY salary DESC NULLS FIRST   -- NULLs go to front

-- By column position
ORDER BY 2 DESC, 1 ASC             -- 2nd column desc, 1st column asc

-- By expression
ORDER BY LENGTH(name) DESC
ORDER BY LOWER(name)
```

### LIMIT & OFFSET

```sql
-- First 10 rows
SELECT * FROM employees LIMIT 10;

-- Rows 21-30 (page 3 with page size 10)
SELECT * FROM employees ORDER BY id LIMIT 10 OFFSET 20;

-- FETCH FIRST (SQL standard alternative to LIMIT)
SELECT * FROM employees
ORDER BY salary DESC
FETCH FIRST 5 ROWS ONLY;

-- FETCH with OFFSET
SELECT * FROM employees
ORDER BY salary DESC
OFFSET 10 ROWS
FETCH NEXT 10 ROWS ONLY;
```

### String Functions in SELECT

```sql
SELECT
    UPPER(name)                              AS upper_name,
    LOWER(email)                             AS lower_email,
    LENGTH(name)                             AS name_len,
    TRIM(name)                               AS trimmed,
    LPAD(id::TEXT, 6, '0')                   AS padded_id,
    SUBSTRING(email FROM 1 FOR POSITION('@' IN email)-1) AS username,
    REPLACE(name, ' ', '_')                  AS username_style,
    CONCAT(name, ' <', email, '>')           AS display,
    name || ' (' || department || ')'        AS label,
    SPLIT_PART(email, '@', 2)                AS email_domain,
    LEFT(name, 5)                            AS first5,
    RIGHT(name, 5)                           AS last5
FROM employees;
```

### Date Functions in SELECT

```sql
SELECT
    NOW()                                    AS current_ts,
    CURRENT_DATE                             AS today,
    CURRENT_DATE - joined_at                 AS days_employed,
    EXTRACT(YEAR  FROM joined_at)            AS join_year,
    EXTRACT(MONTH FROM joined_at)            AS join_month,
    DATE_TRUNC('month', joined_at)           AS join_month_start,
    AGE(CURRENT_DATE, joined_at)             AS tenure,
    TO_CHAR(joined_at, 'DD Mon YYYY')        AS formatted_date,
    joined_at + INTERVAL '90 days'           AS probation_end,
    DATE_PART('year', AGE(joined_at))        AS years_employed
FROM employees;
```

### Conditional Expressions

```sql
SELECT
    name,
    salary,
    -- CASE expression
    CASE
        WHEN salary >= 100000 THEN 'Senior'
        WHEN salary >= 70000  THEN 'Mid'
        WHEN salary >= 50000  THEN 'Junior'
        ELSE                       'Intern'
    END                            AS grade,

    -- CASE simple form
    CASE department
        WHEN 'Engineering' THEN 'Tech'
        WHEN 'Marketing'   THEN 'Biz'
        ELSE                    'Other'
    END                            AS dept_type,

    -- COALESCE: return first non-NULL
    COALESCE(phone, email, 'no-contact') AS contact,

    -- NULLIF: return NULL if equal, else first value
    NULLIF(department, 'N/A')      AS clean_dept,

    -- GREATEST / LEAST
    GREATEST(salary, 50000)        AS min_50k,
    LEAST(salary, 200000)          AS max_200k
FROM employees;
```

---

## 9. Joins

### INNER JOIN

```sql
-- Returns rows where both sides match
SELECT e.name, e.department, o.id AS order_id, o.amount
FROM employees e
INNER JOIN orders o ON o.customer_id = e.id;
-- or just JOIN (INNER is default)
```

### LEFT JOIN

```sql
-- All rows from left table, matching rows from right (NULL if no match)
SELECT e.name, COUNT(o.id) AS order_count
FROM employees e
LEFT JOIN orders o ON o.customer_id = e.id
GROUP BY e.name
ORDER BY order_count DESC;
-- Employees with NO orders appear with order_count = 0
```

### RIGHT JOIN

```sql
-- All rows from right table, matching rows from left
SELECT e.name, o.id, o.amount
FROM employees e
RIGHT JOIN orders o ON e.id = o.customer_id;
-- All orders returned, even if employee is not found
```

### FULL OUTER JOIN

```sql
-- All rows from both tables (matched + unmatched)
SELECT e.name, o.id
FROM employees e
FULL OUTER JOIN orders o ON e.id = o.customer_id;
-- Shows: matched pairs + employees with no orders + orders with no employee
```

### CROSS JOIN

```sql
-- Every combination of both tables (cartesian product)
SELECT e.name, p.product_name
FROM employees e
CROSS JOIN products p;
-- 5 employees × 10 products = 50 rows
```

### SELF JOIN

```sql
-- Join a table to itself (e.g., employee → manager)
SELECT
    e.name       AS employee,
    m.name       AS manager
FROM employees e
LEFT JOIN employees m ON m.id = e.manager_id;
```

### JOIN with Multiple Conditions

```sql
SELECT o.id, e.name, p.name AS product
FROM orders o
JOIN employees e ON e.id = o.customer_id
                 AND e.is_active = true          -- extra condition
JOIN products  p ON p.name = o.product
                 AND p.is_available = true;
```

### USING Clause (when column names match)

```sql
-- USING: shorthand when FK and PK have the same column name
SELECT e.name, o.amount
FROM employees e
JOIN orders o USING (id);   -- joins on e.id = o.id
-- id appears once in result (not duplicated)
```

---

## 10. Aggregation & Grouping

### Aggregate Functions

```sql
SELECT
    COUNT(*)                         AS total_rows,
    COUNT(phone)                     AS rows_with_phone,    -- excludes NULLs
    COUNT(DISTINCT department)       AS unique_depts,
    SUM(salary)                      AS total_payroll,
    AVG(salary)                      AS avg_salary,
    ROUND(AVG(salary), 2)            AS avg_salary_rounded,
    MIN(salary)                      AS lowest_salary,
    MAX(salary)                      AS highest_salary,
    MIN(joined_at)                   AS earliest_hire,
    MAX(joined_at)                   AS latest_hire,
    STRING_AGG(name, ', ' ORDER BY name) AS all_names,
    ARRAY_AGG(name ORDER BY name)    AS names_array
FROM employees
WHERE is_active = true;
```

### GROUP BY

```sql
-- Aggregate per group
SELECT
    department,
    COUNT(*)                         AS headcount,
    ROUND(AVG(salary), 2)            AS avg_salary,
    SUM(salary)                      AS total_cost,
    MIN(salary)                      AS min_salary,
    MAX(salary)                      AS max_salary
FROM employees
WHERE is_active = true
GROUP BY department
ORDER BY total_cost DESC;
```

### HAVING — Filter Groups

```sql
-- WHERE filters rows BEFORE grouping
-- HAVING filters groups AFTER grouping

SELECT department, COUNT(*), AVG(salary)
FROM employees
WHERE is_active = true           -- row-level filter (before GROUP)
GROUP BY department
HAVING COUNT(*) >= 2             -- group-level filter (after GROUP)
   AND AVG(salary) > 70000
ORDER BY AVG(salary) DESC;
```

### GROUP BY + JOIN

```sql
SELECT
    e.department,
    COUNT(DISTINCT e.id)             AS employees,
    COUNT(o.id)                      AS total_orders,
    COALESCE(SUM(o.amount), 0)       AS total_revenue
FROM employees e
LEFT JOIN orders o ON o.customer_id = e.id
GROUP BY e.department
ORDER BY total_revenue DESC;
```

### FILTER — Conditional Aggregation

```sql
SELECT
    department,
    COUNT(*)                                          AS total,
    COUNT(*) FILTER (WHERE salary > 80000)            AS high_earners,
    COUNT(*) FILTER (WHERE is_active = true)          AS active,
    AVG(salary) FILTER (WHERE is_active = true)       AS avg_active_salary,
    SUM(salary)  FILTER (WHERE department = 'Engineering') AS eng_payroll
FROM employees
GROUP BY department;
```

---

## 11. Sequences & Auto-Increment

### SERIAL (Shorthand)

```sql
-- SERIAL is syntactic sugar for SEQUENCE + DEFAULT
CREATE TABLE products (
    id    SERIAL PRIMARY KEY,   -- creates sequence products_id_seq
    name  TEXT
);

-- Equivalent to:
CREATE SEQUENCE products_id_seq;
CREATE TABLE products (
    id   INTEGER DEFAULT nextval('products_id_seq') PRIMARY KEY,
    name TEXT
);
```

### BIGSERIAL for Large Tables

```sql
CREATE TABLE events (
    id         BIGSERIAL PRIMARY KEY,   -- up to 9.2 × 10^18
    event_type TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
```

### IDENTITY Columns (PG 10+ — Preferred over SERIAL)

```sql
-- GENERATED ALWAYS AS IDENTITY — cannot override with INSERT
CREATE TABLE customers (
    id   INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name TEXT
);
INSERT INTO customers (name) VALUES ('Alice');    -- OK
INSERT INTO customers (id, name) VALUES (1, 'Alice');  -- ERROR

-- GENERATED BY DEFAULT AS IDENTITY — can override with INSERT
CREATE TABLE customers (
    id   INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name TEXT
);
INSERT INTO customers (id, name) VALUES (1000, 'Alice');  -- OK
```

### Working with Sequences Directly

```sql
-- Create a standalone sequence
CREATE SEQUENCE order_number_seq
    START WITH    10000
    INCREMENT BY  1
    MINVALUE      10000
    MAXVALUE      9999999
    CACHE         10         -- pre-allocate 10 values in memory
    NO CYCLE;                -- error at MAXVALUE (don't wrap around)

-- Use it
SELECT nextval('order_number_seq');   -- 10000
SELECT nextval('order_number_seq');   -- 10001
SELECT currval('order_number_seq');   -- 10001 (current for this session)
SELECT lastval();                     -- last nextval in this session

-- Reset sequence
ALTER SEQUENCE order_number_seq RESTART WITH 10000;
SELECT setval('order_number_seq', 50000);  -- set to specific value

-- Get sequence info
SELECT * FROM pg_sequences WHERE sequencename = 'order_number_seq';

-- View all sequences
SELECT sequencename, start_value, increment_by, last_value
FROM pg_sequences
WHERE schemaname = 'public';

-- Drop sequence
DROP SEQUENCE IF EXISTS order_number_seq;
DROP SEQUENCE IF EXISTS order_number_seq CASCADE;  -- also drops defaults using it
```

---

## 12. Views

A **view** is a named, stored SQL query. It acts like a table but stores no data itself — it runs the underlying query each time it's accessed.

### Basic View

```sql
-- Create a view
CREATE VIEW active_employees AS
SELECT id, name, email, salary, department, joined_at
FROM employees
WHERE is_active = true;

-- Query the view
SELECT * FROM active_employees;
SELECT name, salary FROM active_employees WHERE department = 'Engineering';

-- Replace/update a view definition
CREATE OR REPLACE VIEW active_employees AS
SELECT id, name, email, salary, department, joined_at,
       CURRENT_DATE - joined_at AS days_employed
FROM employees
WHERE is_active = true;

-- List views
\dv
SELECT viewname, definition FROM pg_views WHERE schemaname = 'public';

-- Drop a view
DROP VIEW IF EXISTS active_employees;
DROP VIEW IF EXISTS active_employees CASCADE;   -- also drops dependent views
```

### View with JOIN

```sql
CREATE VIEW employee_order_summary AS
SELECT
    e.id           AS employee_id,
    e.name,
    e.department,
    COUNT(o.id)                        AS order_count,
    COALESCE(SUM(o.amount), 0)         AS total_amount,
    MAX(o.created_at)                  AS last_order_at
FROM employees e
LEFT JOIN orders o ON o.customer_id = e.id
GROUP BY e.id, e.name, e.department;

SELECT * FROM employee_order_summary WHERE department = 'Sales';
```

### Updatable Views

```sql
-- Simple views (one table, no aggregation) are automatically updatable
CREATE VIEW junior_employees AS
SELECT id, name, email, salary, department
FROM employees
WHERE salary < 60000;

-- Can INSERT, UPDATE, DELETE through simple views
INSERT INTO junior_employees (name, email, salary, department)
VALUES ('Newbie', 'new@co.com', 45000, 'Support');

UPDATE junior_employees SET salary = 50000 WHERE id = 10;

-- WITH CHECK OPTION: prevent INSERT/UPDATE that would make row invisible in view
CREATE VIEW junior_employees AS
SELECT id, name, email, salary, department
FROM employees
WHERE salary < 60000
WITH CHECK OPTION;
-- Now: UPDATE junior_employees SET salary = 90000 WHERE id=1; → ERROR
```

### Materialized Views

```sql
-- Materialized view: stores the result physically on disk (like a table)
-- Must be refreshed to update data
CREATE MATERIALIZED VIEW department_stats AS
SELECT
    department,
    COUNT(*)            AS headcount,
    AVG(salary)         AS avg_salary,
    SUM(salary)         AS total_payroll
FROM employees
WHERE is_active = true
GROUP BY department
WITH DATA;              -- populate immediately (default)

-- WITHOUT DATA: create structure without populating
CREATE MATERIALIZED VIEW mv_expensive AS
SELECT ...
WITHOUT DATA;

-- Query materialized view (uses stored data)
SELECT * FROM department_stats;

-- Refresh the data (re-runs the query)
REFRESH MATERIALIZED VIEW department_stats;

-- Non-blocking refresh (PG 9.4+ — requires UNIQUE index)
CREATE UNIQUE INDEX ON department_stats(department);
REFRESH MATERIALIZED VIEW CONCURRENTLY department_stats;
-- Concurrent refresh: old data available while refreshing

-- Drop materialized view
DROP MATERIALIZED VIEW IF EXISTS department_stats;

-- When to use materialized views:
-- Slow, complex queries that are read frequently but updated infrequently
-- Dashboard aggregations, reporting summaries, complex JOINs
```

---

## 13. Indexes — Basics

An **index** is a separate data structure that speeds up data retrieval.

```sql
-- Create a basic B-tree index
CREATE INDEX idx_employees_department ON employees(department);
CREATE INDEX idx_employees_email      ON employees(email);
CREATE INDEX idx_orders_customer      ON orders(customer_id);
CREATE INDEX idx_orders_created       ON orders(created_at DESC);

-- Unique index (also enforces uniqueness)
CREATE UNIQUE INDEX idx_employees_email_unique ON employees(email);

-- Composite index (multi-column)
CREATE INDEX idx_orders_status_date ON orders(status, created_at DESC);

-- Partial index (index subset of rows)
CREATE INDEX idx_orders_pending ON orders(created_at)
WHERE status = 'pending';

-- Concurrent index (doesn't block writes)
CREATE INDEX CONCURRENTLY idx_employees_name ON employees(name);

-- List indexes on a table
\di employees
SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'employees';

-- Drop an index
DROP INDEX idx_employees_department;
DROP INDEX CONCURRENTLY idx_employees_department;   -- non-blocking

-- Rebuild an index
REINDEX INDEX idx_employees_department;
REINDEX TABLE employees;   -- rebuild all indexes on table

-- Which index types to use:
-- B-tree:  default, works for =, <, >, BETWEEN, LIKE 'x%'
-- Hash:    equality only (=)
-- GIN:     arrays, JSONB, full-text search
-- GiST:    geometric, ranges, nearest-neighbor
-- BRIN:    huge tables with natural order (time-series)
```

---

## 14. Users, Roles & Permissions

In PostgreSQL, **users** and **groups** are both implemented as **roles**.

### CREATE ROLE / USER

```sql
-- Create a login role (= user)
CREATE ROLE alice LOGIN PASSWORD 'securepassword123';

-- CREATE USER is equivalent (with LOGIN by default)
CREATE USER alice WITH PASSWORD 'securepassword123';

-- Role with more options
CREATE ROLE app_user
    WITH
    LOGIN                         -- can log in
    PASSWORD 'strong_password'
    NOSUPERUSER                   -- not a superuser
    NOCREATEDB                    -- cannot create databases
    NOCREATEROLE                  -- cannot create roles
    NOINHERIT                     -- does not inherit group permissions
    CONNECTION LIMIT 50           -- max 50 connections
    VALID UNTIL '2025-12-31';     -- account expires

-- Create a group role (no login)
CREATE ROLE developers NOLOGIN;

-- Create superuser (admin)
CREATE ROLE admin_user LOGIN SUPERUSER PASSWORD 'admin_pass';

-- List all roles
\du
SELECT rolname, rolsuper, rolcreatedb, rolcreaterole, rolcanlogin
FROM pg_roles ORDER BY rolname;
```

### GRANT Permissions

```sql
-- Connect to a database
GRANT CONNECT ON DATABASE myapp TO app_user;

-- Use a schema
GRANT USAGE ON SCHEMA public TO app_user;

-- Table-level permissions
GRANT SELECT             ON employees TO readonly_user;
GRANT SELECT, INSERT     ON employees TO app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON employees TO app_admin;
GRANT ALL PRIVILEGES     ON employees TO app_admin;

-- All tables in schema
GRANT SELECT ON ALL TABLES IN SCHEMA public TO readonly_user;
GRANT ALL    ON ALL TABLES IN SCHEMA public TO app_admin;

-- Future tables (default privileges)
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT ON TABLES TO readonly_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_user;

-- Sequences (needed for SERIAL / INSERT with auto-increment)
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO app_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO app_user;

-- Functions
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO app_user;

-- Grant role to another role (inheritance)
GRANT developers TO alice;
GRANT readonly_user TO analytics_service;

-- Grant with GRANT OPTION (allow them to grant to others)
GRANT SELECT ON employees TO alice WITH GRANT OPTION;
```

### REVOKE Permissions

```sql
REVOKE SELECT ON employees FROM readonly_user;
REVOKE ALL PRIVILEGES ON employees FROM app_user;
REVOKE CONNECT ON DATABASE myapp FROM old_user;
REVOKE developers FROM alice;     -- remove alice from developers group
```

### ALTER ROLE

```sql
-- Change password
ALTER ROLE alice PASSWORD 'newpassword123';

-- Remove password
ALTER ROLE alice PASSWORD NULL;

-- Add login privilege
ALTER ROLE alice LOGIN;

-- Make superuser
ALTER ROLE alice SUPERUSER;

-- Set session defaults for a role
ALTER ROLE reporting_user SET work_mem = '256MB';
ALTER ROLE reporting_user SET statement_timeout = '5min';
ALTER ROLE reporting_user SET search_path = analytics, public;

-- Rename a role
ALTER ROLE alice RENAME TO alice_admin;

-- Drop a role
DROP ROLE IF EXISTS alice;
DROP ROLE IF EXISTS alice CASCADE;  -- drops dependent objects
```

### Row-Level Security (RLS)

```sql
-- Enable RLS on a table
ALTER TABLE employees ENABLE ROW LEVEL SECURITY;

-- Create policies
-- Policy: employees can only see their own row
CREATE POLICY emp_self_view
ON employees
FOR SELECT
USING (email = CURRENT_USER);

-- Policy: managers can see their team
CREATE POLICY manager_team_view
ON employees
FOR SELECT
USING (
    manager_id IN (SELECT id FROM employees WHERE email = CURRENT_USER)
    OR email = CURRENT_USER
);

-- Policy: HR can see everyone
CREATE POLICY hr_full_access
ON employees
FOR ALL
TO hr_role
USING (true);                  -- no restriction

-- Superusers and table owners bypass RLS
-- To make owner also subject to RLS:
ALTER TABLE employees FORCE ROW LEVEL SECURITY;

-- List policies
SELECT * FROM pg_policies WHERE tablename = 'employees';

-- Drop policy
DROP POLICY emp_self_view ON employees;

-- Disable RLS
ALTER TABLE employees DISABLE ROW LEVEL SECURITY;
```

---

## 15. Transactions — Basics

A **transaction** is a group of SQL statements that execute as one atomic unit.

```sql
-- Start a transaction
BEGIN;

    -- All statements here are part of the transaction
    UPDATE accounts SET balance = balance - 500 WHERE id = 1;
    UPDATE accounts SET balance = balance + 500 WHERE id = 2;

    -- If both succeed:
COMMIT;

-- If anything goes wrong:
-- ROLLBACK;
```

### ACID in Practice

```sql
-- Atomicity: all or nothing
BEGIN;
    INSERT INTO orders (customer_id, amount) VALUES (1, 5000);
    UPDATE accounts SET balance = balance - 5000 WHERE id = 1;
    -- If UPDATE fails → ROLLBACK automatically rolls back the INSERT too
COMMIT;

-- If an error occurs inside a transaction block, use ROLLBACK:
BEGIN;
    UPDATE accounts SET balance = balance - 9999999 WHERE id = 1;
    -- ERROR: check constraint violation
ROLLBACK;   -- undo everything

-- Savepoints: partial rollback within a transaction
BEGIN;
    UPDATE accounts SET balance = balance - 100 WHERE id = 1;
    SAVEPOINT checkpoint_1;

    UPDATE accounts SET balance = balance - 999999 WHERE id = 1;  -- too much
    ROLLBACK TO SAVEPOINT checkpoint_1;   -- undo only the second update

    UPDATE accounts SET balance = balance - 50 WHERE id = 2;
COMMIT;
```

### Transaction Isolation (Quick Reference)

```sql
-- Set isolation level (must be first statement in transaction)
BEGIN TRANSACTION ISOLATION LEVEL READ COMMITTED;    -- default
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ;
BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE;

-- Autocommit mode (each statement is its own transaction by default)
-- In psql or application, outside BEGIN/COMMIT block:
UPDATE accounts SET balance = 100 WHERE id = 1;  -- auto-committed immediately
```

---

## 16. psql CLI Reference

**psql** is the official PostgreSQL interactive terminal.

### Connecting

```bash
# Basic connection
psql -h localhost -p 5432 -U postgres -d mydb

# Connection string
psql "postgresql://username:password@host:5432/dbname"
psql "host=localhost dbname=myapp user=alice password=secret"

# Connect to local socket (no password if pg_hba allows)
psql -U postgres

# Environment variables
export PGHOST=localhost
export PGPORT=5432
export PGUSER=alice
export PGPASSWORD=secret
export PGDATABASE=myapp
psql   # uses all env vars
```

### Meta-Commands (start with \)

```
DATABASE & SCHEMA
\l              List all databases
\l+             List with sizes
\c mydb         Connect to database mydb
\dn             List schemas
\dn+            List schemas with permissions

TABLE & COLUMNS
\dt             List tables in current schema
\dt+            List tables with sizes
\dt schema.*    List tables in specific schema
\d tablename    Describe a table (columns, indexes, constraints)
\d+ tablename   Describe table with extra info (storage, OIDs)
\di             List indexes
\ds             List sequences
\dv             List views
\dm             List materialized views
\df             List functions
\dp             List table permissions

USERS & ROLES
\du             List all roles/users
\du rolename    Describe a role

OUTPUT & FORMATTING
\x              Toggle expanded display (vertical output)
\x auto         Auto-switch based on terminal width
\timing         Toggle query execution timing
\echo text      Print text
\o filename     Send output to file
\o              Stop sending to file

QUERY EDITING
\e              Open last query in editor
\ef funcname    Edit function source
\i filename     Execute SQL from file
\ir filename    Execute SQL relative to current script

HELP
\h              SQL command help
\h SELECT       Help for SELECT
\?              psql meta-command help
\q              Quit psql

HISTORY & VARIABLES
\s              Show query history
\set VAR value  Set psql variable
\unset VAR      Unset variable
\echo :VAR      Print variable value
```

### Useful psql Settings

```sql
-- In psql session
\timing on                         -- show query time
\x on                              -- expanded row display
\pset null '(null)'                -- show NULLs explicitly
\pset border 2                     -- table borders
\pset format aligned               -- aligned output (default)
\pset format csv                   -- CSV output
\pset format html                  -- HTML table output

-- In ~/.psqlrc (startup file — auto-loaded)
\set PROMPT1 '%[%033[1;32m%]%n@%m:%>/%/ %[%033[0m%]%# '
\set HISTSIZE 2000
\timing
\pset null '(null)'
```

### Running SQL from File

```bash
# Run a script file
psql -U postgres -d mydb -f /path/to/script.sql

# Run inline SQL
psql -U postgres -d mydb -c "SELECT COUNT(*) FROM employees;"

# Pipe SQL
echo "SELECT NOW();" | psql -U postgres -d mydb

# Export query to CSV
psql -U postgres -d mydb -c "\COPY (SELECT * FROM employees) TO '/tmp/emp.csv' CSV HEADER"
```

### COPY — Bulk Import/Export

```sql
-- Export table to CSV
COPY employees TO '/tmp/employees.csv' WITH (FORMAT csv, HEADER true);

-- Export query result to CSV
COPY (SELECT id, name, salary FROM employees WHERE is_active = true)
TO '/tmp/active_emp.csv' WITH (FORMAT csv, HEADER true);

-- Import from CSV
COPY employees (name, email, salary, department)
FROM '/tmp/new_employees.csv' WITH (FORMAT csv, HEADER true);

-- \COPY (client-side — no superuser needed)
\COPY employees TO '/tmp/emp.csv' CSV HEADER
\COPY employees FROM '/tmp/emp.csv' CSV HEADER
```

---

## 17. Quick Reference Cheat Sheet

```
╔══════════════════════════╦═══════════════════════════════════════════════════╗
║ TOPIC                    ║ KEY SYNTAX                                        ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Connect                  ║ psql -h host -U user -d dbname                    ║
║ Create DB                ║ CREATE DATABASE name;                             ║
║ Create Schema            ║ CREATE SCHEMA name;                               ║
║ Switch DB                ║ \c dbname  (psql)                                 ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Create Table             ║ CREATE TABLE t (col type [constraints], ...);     ║
║ Alter Table              ║ ALTER TABLE t ADD/DROP/ALTER COLUMN ...;          ║
║ Drop Table               ║ DROP TABLE IF EXISTS t [CASCADE];                 ║
║ Truncate                 ║ TRUNCATE t [RESTART IDENTITY] [CASCADE];          ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ INSERT                   ║ INSERT INTO t (cols) VALUES (...);                ║
║ INSERT many              ║ INSERT INTO t (cols) VALUES (...), (...);         ║
║ INSERT from SELECT       ║ INSERT INTO t SELECT ... FROM other;              ║
║ UPSERT                   ║ INSERT ... ON CONFLICT (col) DO UPDATE SET ...;   ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ SELECT                   ║ SELECT cols FROM t WHERE cond ORDER BY c LIMIT n; ║
║ DISTINCT                 ║ SELECT DISTINCT col FROM t;                       ║
║ ALIAS                    ║ SELECT col AS alias FROM t AS tbl;                ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ UPDATE                   ║ UPDATE t SET col=val WHERE cond RETURNING cols;   ║
║ DELETE                   ║ DELETE FROM t WHERE cond RETURNING cols;          ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ JOIN types               ║ INNER JOIN / LEFT JOIN / RIGHT JOIN / FULL JOIN   ║
║ CROSS JOIN               ║ Cartesian product of both tables                  ║
║ SELF JOIN                ║ JOIN table to itself with alias                   ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Aggregates               ║ COUNT(*) SUM() AVG() MIN() MAX()                  ║
║ GROUP BY                 ║ GROUP BY col HAVING condition                     ║
║ Conditional agg          ║ COUNT(*) FILTER (WHERE condition)                 ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Primary Key              ║ id SERIAL PRIMARY KEY                             ║
║ Identity (preferred)     ║ id INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY   ║
║ Foreign Key              ║ REFERENCES other_table(id) ON DELETE CASCADE      ║
║ Check                    ║ CHECK (salary > 0 AND salary < 1000000)           ║
║ Unique                   ║ UNIQUE (email)  or  col TEXT UNIQUE               ║
║ Not Null                 ║ col TEXT NOT NULL                                 ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ View                     ║ CREATE [OR REPLACE] VIEW name AS SELECT ...;      ║
║ Materialized View        ║ CREATE MATERIALIZED VIEW name AS SELECT ...;      ║
║ Refresh Mat. View        ║ REFRESH MATERIALIZED VIEW [CONCURRENTLY] name;    ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Index                    ║ CREATE INDEX name ON t(col);                      ║
║ Unique Index             ║ CREATE UNIQUE INDEX name ON t(col);               ║
║ Partial Index            ║ CREATE INDEX name ON t(col) WHERE cond;           ║
║ Non-blocking             ║ CREATE INDEX CONCURRENTLY name ON t(col);         ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Create Role/User         ║ CREATE ROLE name LOGIN PASSWORD 'pass';           ║
║ Grant                    ║ GRANT SELECT ON t TO role;                        ║
║ Revoke                   ║ REVOKE SELECT ON t FROM role;                     ║
║ Grant role               ║ GRANT group_role TO user_role;                    ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Transaction              ║ BEGIN; ... COMMIT; / ROLLBACK;                    ║
║ Savepoint                ║ SAVEPOINT sp; ROLLBACK TO SAVEPOINT sp;           ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ Key Data Types           ║ INT  BIGINT  NUMERIC(p,s)  TEXT  BOOLEAN          ║
║                          ║ DATE  TIMESTAMPTZ  UUID  JSONB  TEXT[]            ║
╠══════════════════════════╬═══════════════════════════════════════════════════╣
║ psql Commands            ║ \l=list DBs  \dt=tables  \d t=describe            ║
║                          ║ \c db=connect  \du=users  \q=quit                 ║
║                          ║ \i file=run SQL  \timing=show timing              ║
╚══════════════════════════╩═══════════════════════════════════════════════════╝
```

---

## Further Reading

- [PostgreSQL Docs — Getting Started](https://www.postgresql.org/docs/current/tutorial.html)
- [PostgreSQL Docs — Data Types](https://www.postgresql.org/docs/current/datatype.html)
- [PostgreSQL Docs — Data Definition (DDL)](https://www.postgresql.org/docs/current/ddl.html)
- [PostgreSQL Docs — Data Manipulation (DML)](https://www.postgresql.org/docs/current/dml.html)
- [PostgreSQL Docs — Queries (SELECT)](https://www.postgresql.org/docs/current/queries.html)
- [PostgreSQL Docs — Functions & Operators](https://www.postgresql.org/docs/current/functions.html)
- [PostgreSQL Docs — Database Roles](https://www.postgresql.org/docs/current/database-roles.html)
- [PostgreSQL Docs — Indexes](https://www.postgresql.org/docs/current/indexes.html)
- [PostgreSQL Docs — Views](https://www.postgresql.org/docs/current/rules-views.html)
- [PostgreSQL Docs — psql Reference](https://www.postgresql.org/docs/current/app-psql.html)

---

*Generated with love for PostgreSQL engineers.*
