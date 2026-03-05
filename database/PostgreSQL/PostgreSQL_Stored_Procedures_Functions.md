# PostgreSQL — Stored Procedures & Functions Complete Reference

> A deep-dive guide covering functions, stored procedures, PL/pgSQL, triggers, cursors, exception handling, security, and real-world patterns in PostgreSQL.

---

## Table of Contents

1. [Functions vs Procedures — Key Differences](#1-functions-vs-procedures--key-differences)
2. [CREATE FUNCTION Syntax](#2-create-function-syntax)
3. [PL/pgSQL Language Basics](#3-plpgsql-language-basics)
4. [Variables & Data Types](#4-variables--data-types)
5. [Control Flow](#5-control-flow)
6. [Cursors](#6-cursors)
7. [Exception Handling](#7-exception-handling)
8. [Return Types — All Variants](#8-return-types--all-variants)
9. [Stored Procedures (CALL)](#9-stored-procedures-call)
10. [Function Overloading](#10-function-overloading)
11. [Function Volatility & Behavior](#11-function-volatility--behavior)
12. [Triggers & Trigger Functions](#12-triggers--trigger-functions)
13. [Security & Permissions](#13-security--permissions)
14. [Dynamic SQL](#14-dynamic-sql)
15. [Advanced Patterns](#15-advanced-patterns)
16. [Debugging & Testing](#16-debugging--testing)
17. [Quick Reference Cheat Sheet](#17-quick-reference-cheat-sheet)

---

## Sample Tables Used in All Examples

```sql
CREATE TABLE employees (
    id          SERIAL PRIMARY KEY,
    name        TEXT         NOT NULL,
    email       TEXT         UNIQUE,
    salary      NUMERIC(12,2),
    department  TEXT,
    manager_id  INTEGER      REFERENCES employees(id),
    joined_at   DATE         DEFAULT CURRENT_DATE,
    is_active   BOOLEAN      DEFAULT true,
    metadata    JSONB        DEFAULT '{}'
);

CREATE TABLE accounts (
    id          SERIAL PRIMARY KEY,
    owner       TEXT         NOT NULL,
    balance     NUMERIC(14,2) NOT NULL CHECK (balance >= 0),
    currency    TEXT         DEFAULT 'INR',
    updated_at  TIMESTAMPTZ  DEFAULT NOW()
);

CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    table_name  TEXT,
    operation   TEXT,
    old_data    JSONB,
    new_data    JSONB,
    changed_by  TEXT         DEFAULT CURRENT_USER,
    changed_at  TIMESTAMPTZ  DEFAULT NOW()
);

CREATE TABLE orders (
    id          BIGSERIAL PRIMARY KEY,
    customer_id INTEGER,
    product     TEXT,
    amount      NUMERIC(12,2),
    status      TEXT         DEFAULT 'pending',
    created_at  TIMESTAMPTZ  DEFAULT NOW()
);

INSERT INTO employees VALUES
  (1,'Alice Johnson','alice@co.com', 95000,'Engineering',NULL, '2019-03-15',true,'{"level":"staff"}'),
  (2,'Bob Smith',    'bob@co.com',   72000,'Engineering',1,    '2020-07-22',true,'{"level":"senior"}'),
  (3,'Charlie Brown','charlie@co.com',88000,'Engineering',1,   '2018-01-10',true,'{"level":"senior"}'),
  (4,'Diana Prince', 'diana@co.com', 65000,'Marketing',  NULL, '2021-11-05',true,'{"level":"mid"}'),
  (5,'Eve Wilson',   'eve@co.com',   58000,'Marketing',  4,    '2022-06-30',true,'{"level":"junior"}');

INSERT INTO accounts VALUES
  (1,'Alice',10000,'INR',NOW()),
  (2,'Bob',   5000,'INR',NOW()),
  (3,'Carol',8000,'INR',NOW());
```

---

## 1. Functions vs Procedures — Key Differences

| Feature | FUNCTION | PROCEDURE |
|---------|----------|-----------|
| Returns value | YES (required) | OPTIONAL (OUT params only) |
| Called with | `SELECT` or inside expressions | `CALL` statement |
| Transaction control | NO (cannot COMMIT/ROLLBACK) | YES (can COMMIT/ROLLBACK) |
| Used in SQL | YES (SELECT, WHERE, JOIN) | NO |
| Can return result set | YES (RETURNS TABLE / SETOF) | Via OUT / INOUT params |
| Available since | Always | PostgreSQL 11+ |
| `RETURNS VOID` | YES (side-effect only) | N/A |

```sql
-- FUNCTION: used inline in SQL expressions
SELECT my_function(42);
SELECT * FROM my_table_function();
INSERT INTO t SELECT compute_value(col) FROM other_t;

-- PROCEDURE: called with CALL, can manage transactions
CALL my_procedure(42);
CALL transfer_money(1, 2, 500.00);
```

---

## 2. CREATE FUNCTION Syntax

### Complete Syntax

```sql
CREATE [OR REPLACE] FUNCTION function_name (
    param_name  param_type [DEFAULT default_value],
    ...
)
RETURNS return_type
LANGUAGE plpgsql
[VOLATILE | STABLE | IMMUTABLE]
[CALLED ON NULL INPUT | RETURNS NULL ON NULL INPUT | STRICT]
[SECURITY DEFINER | SECURITY INVOKER]
[COST cost_value]
[ROWS rows_estimate]
[SET config_param = value]
AS $$
DECLARE
    -- variable declarations
BEGIN
    -- function body
    RETURN value;
END;
$$;
```

### Hello World Function

```sql
-- Simplest possible function
CREATE OR REPLACE FUNCTION greet(name TEXT)
RETURNS TEXT
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN 'Hello, ' || name || '!';
END;
$$;

-- Call it
SELECT greet('Alice');           -- Hello, Alice!
SELECT greet('World');           -- Hello, World!
```

### SQL Language Function (simpler for single expressions)

```sql
-- Use LANGUAGE sql for simple, single-expression functions
CREATE OR REPLACE FUNCTION add_numbers(a NUMERIC, b NUMERIC)
RETURNS NUMERIC
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT a + b;
$$;

SELECT add_numbers(10, 20);      -- 30

-- Multi-statement SQL function
CREATE OR REPLACE FUNCTION get_employee_count(dept TEXT)
RETURNS BIGINT
LANGUAGE sql
STABLE
AS $$
    SELECT COUNT(*)
    FROM employees
    WHERE department = dept
      AND is_active = true;
$$;

SELECT get_employee_count('Engineering');   -- 3
```

### Default Parameter Values

```sql
CREATE OR REPLACE FUNCTION raise_salary(
    emp_id      INTEGER,
    raise_pct   NUMERIC  DEFAULT 10.0,    -- 10% default
    cap_amount  NUMERIC  DEFAULT 200000   -- salary cap default
)
RETURNS NUMERIC
LANGUAGE plpgsql
AS $$
DECLARE
    new_salary NUMERIC;
BEGIN
    UPDATE employees
    SET salary = LEAST(salary * (1 + raise_pct / 100.0), cap_amount)
    WHERE id = emp_id
    RETURNING salary INTO new_salary;

    RETURN new_salary;
END;
$$;

SELECT raise_salary(1);              -- 10% raise, cap 200000
SELECT raise_salary(2, 15);          -- 15% raise, cap 200000
SELECT raise_salary(3, 20, 150000);  -- 20% raise, cap 150000

-- Named parameters (any order)
SELECT raise_salary(emp_id => 4, cap_amount => 100000, raise_pct => 5);
```

---

## 3. PL/pgSQL Language Basics

PL/pgSQL is PostgreSQL's primary procedural language — a superset of SQL with variables, control flow, and exception handling.

### Block Structure

```sql
-- PL/pgSQL block anatomy
DO $$                            -- anonymous block (no FUNCTION wrapper)
DECLARE
    -- All variables declared here
    v_name      TEXT;            -- uninitialized = NULL
    v_count     INTEGER := 0;    -- initialized
    v_salary    NUMERIC(12,2) := 50000.00;
    v_now       TIMESTAMPTZ := NOW();
    v_active    BOOLEAN := true;

BEGIN
    -- Executable statements here
    v_name := 'Alice';
    v_count := v_count + 1;

    -- SQL statement inside PL/pgSQL
    SELECT name INTO v_name
    FROM employees WHERE id = 1;

    RAISE NOTICE 'Name: %, Count: %', v_name, v_count;

EXCEPTION
    WHEN OTHERS THEN
        RAISE NOTICE 'Error: %', SQLERRM;
END;
$$;
```

### SELECT INTO

```sql
CREATE OR REPLACE FUNCTION get_employee_salary(p_id INTEGER)
RETURNS NUMERIC
LANGUAGE plpgsql
AS $$
DECLARE
    v_salary    NUMERIC;
    v_name      TEXT;
BEGIN
    -- Fetch single row into variables
    SELECT name, salary
    INTO   v_name, v_salary
    FROM   employees
    WHERE  id = p_id;

    -- Check if row was found
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Employee % not found', p_id;
    END IF;

    RAISE NOTICE 'Found: % with salary %', v_name, v_salary;
    RETURN v_salary;
END;
$$;

SELECT get_employee_salary(1);   -- 95000
SELECT get_employee_salary(99);  -- ERROR: Employee 99 not found
```

### RAISE — Messages and Errors

```sql
DO $$
BEGIN
    -- Log levels (printed to client or log file)
    RAISE DEBUG   'Debug message (hidden by default)';
    RAISE INFO    'Info: processing started';
    RAISE NOTICE  'Notice: % rows affected', 42;
    RAISE WARNING 'Warning: salary below minimum';
    RAISE LOG     'Log: audit entry created';

    -- Raise an error (stops execution, triggers exception handler)
    RAISE EXCEPTION 'Business rule violated: %', 'insufficient funds';

    -- Raise with SQLSTATE (standard error code)
    RAISE EXCEPTION USING
        MESSAGE  = 'Custom error message',
        DETAIL   = 'The balance was 0 when 500 was required',
        HINT     = 'Please top up your account',
        ERRCODE  = 'P0001';   -- custom error code
END;
$$;
```

---

## 4. Variables & Data Types

### Variable Declaration

```sql
DO $$
DECLARE
    -- Scalar variables
    v_id        INTEGER;
    v_name      TEXT        := 'default';
    v_salary    NUMERIC     := 0;
    v_flag      BOOLEAN     := false;
    v_date      DATE        := CURRENT_DATE;
    v_ts        TIMESTAMPTZ := NOW();

    -- %TYPE — inherits column type (safe against schema changes)
    v_emp_name  employees.name%TYPE;
    v_emp_sal   employees.salary%TYPE;

    -- %ROWTYPE — inherits entire row structure
    v_emp_row   employees%ROWTYPE;

    -- RECORD — generic row (type determined at runtime)
    v_rec       RECORD;

    -- Array
    v_tags      TEXT[]      := ARRAY['a','b','c'];
    v_nums      INTEGER[]   := '{1,2,3}';

    -- Composite / custom type
    v_json      JSONB       := '{}';

    -- Constant
    c_max_sal   CONSTANT NUMERIC := 500000;

BEGIN
    -- %ROWTYPE usage
    SELECT * INTO v_emp_row FROM employees WHERE id = 1;
    RAISE NOTICE 'Employee: %, Salary: %', v_emp_row.name, v_emp_row.salary;

    -- RECORD usage (from any query)
    SELECT id, name, salary INTO v_rec FROM employees WHERE id = 2;
    RAISE NOTICE 'Record: %, %', v_rec.name, v_rec.salary;

    -- Array operations
    v_tags := ARRAY_APPEND(v_tags, 'd');
    RAISE NOTICE 'Tags: %', v_tags;
END;
$$;
```

### FOUND Special Variable

```sql
-- FOUND is set automatically after certain statements
DO $$
DECLARE
    v_emp employees%ROWTYPE;
BEGIN
    -- After SELECT INTO
    SELECT * INTO v_emp FROM employees WHERE id = 999;
    IF FOUND THEN
        RAISE NOTICE 'Employee found';
    ELSE
        RAISE NOTICE 'No employee with id 999';  -- this runs
    END IF;

    -- After UPDATE/DELETE/INSERT
    UPDATE employees SET salary = 96000 WHERE id = 1;
    IF FOUND THEN
        RAISE NOTICE 'Update succeeded';         -- this runs
    END IF;

    DELETE FROM employees WHERE id = 999;
    IF NOT FOUND THEN
        RAISE NOTICE 'Nothing to delete';        -- this runs
    END IF;
END;
$$;
```

---

## 5. Control Flow

### IF / ELSIF / ELSE

```sql
CREATE OR REPLACE FUNCTION salary_grade(p_salary NUMERIC)
RETURNS TEXT
LANGUAGE plpgsql
IMMUTABLE
AS $$
BEGIN
    IF    p_salary >= 150000 THEN
        RETURN 'L6 — Principal';
    ELSIF p_salary >= 100000 THEN
        RETURN 'L5 — Staff';
    ELSIF p_salary >= 80000  THEN
        RETURN 'L4 — Senior';
    ELSIF p_salary >= 60000  THEN
        RETURN 'L3 — Mid';
    ELSIF p_salary >= 40000  THEN
        RETURN 'L2 — Junior';
    ELSE
        RETURN 'L1 — Intern';
    END IF;
END;
$$;

SELECT name, salary, salary_grade(salary) AS grade
FROM employees ORDER BY salary DESC;
```

### CASE Statement

```sql
CREATE OR REPLACE FUNCTION dept_code(dept TEXT)
RETURNS TEXT
LANGUAGE plpgsql
IMMUTABLE
AS $$
BEGIN
    RETURN CASE dept
        WHEN 'Engineering' THEN 'ENG'
        WHEN 'Marketing'   THEN 'MKT'
        WHEN 'Sales'       THEN 'SLS'
        WHEN 'HR'          THEN 'HR'
        ELSE                    'OTH'
    END;
END;
$$;

SELECT name, department, dept_code(department) FROM employees;
```

### LOOP

```sql
-- Basic LOOP with EXIT
CREATE OR REPLACE FUNCTION sum_to_n(n INTEGER)
RETURNS INTEGER
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    total   INTEGER := 0;
    counter INTEGER := 1;
BEGIN
    LOOP
        EXIT WHEN counter > n;      -- exit condition
        total   := total + counter;
        counter := counter + 1;
    END LOOP;
    RETURN total;
END;
$$;

SELECT sum_to_n(10);    -- 55
SELECT sum_to_n(100);   -- 5050
```

### WHILE LOOP

```sql
CREATE OR REPLACE FUNCTION fibonacci(n INTEGER)
RETURNS INTEGER
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    a   INTEGER := 0;
    b   INTEGER := 1;
    tmp INTEGER;
    i   INTEGER := 2;
BEGIN
    IF n = 0 THEN RETURN 0; END IF;
    IF n = 1 THEN RETURN 1; END IF;

    WHILE i <= n LOOP
        tmp := a + b;
        a   := b;
        b   := tmp;
        i   := i + 1;
    END LOOP;

    RETURN b;
END;
$$;

SELECT fibonacci(10);    -- 55
SELECT fibonacci(20);    -- 6765
```

### FOR LOOP — Integer Range

```sql
CREATE OR REPLACE FUNCTION print_range(start_val INTEGER, end_val INTEGER)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    i INTEGER;
BEGIN
    FOR i IN start_val .. end_val LOOP
        RAISE NOTICE 'i = %', i;
    END LOOP;

    -- Reverse
    FOR i IN REVERSE end_val .. start_val LOOP
        RAISE NOTICE 'reverse i = %', i;
    END LOOP;

    -- With step
    FOR i IN 0 .. 20 BY 5 LOOP
        RAISE NOTICE 'step i = %', i;   -- 0, 5, 10, 15, 20
    END LOOP;
END;
$$;
```

### FOR LOOP — Query Results

```sql
CREATE OR REPLACE FUNCTION process_dept_employees(p_dept TEXT)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    emp_rec RECORD;
    total   NUMERIC := 0;
BEGIN
    -- Loop over query results
    FOR emp_rec IN
        SELECT id, name, salary
        FROM employees
        WHERE department = p_dept
          AND is_active = true
        ORDER BY salary DESC
    LOOP
        total := total + emp_rec.salary;
        RAISE NOTICE 'Processing: % (salary: %)', emp_rec.name, emp_rec.salary;
    END LOOP;

    RAISE NOTICE 'Total payroll for %: %', p_dept, total;
END;
$$;

SELECT process_dept_employees('Engineering');
```

### FOREACH — Array Loop

```sql
CREATE OR REPLACE FUNCTION process_ids(p_ids INTEGER[])
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    v_id  INTEGER;
    v_rec RECORD;
BEGIN
    FOREACH v_id IN ARRAY p_ids LOOP
        SELECT name, salary INTO v_rec FROM employees WHERE id = v_id;
        IF FOUND THEN
            RAISE NOTICE 'ID %: % (salary: %)', v_id, v_rec.name, v_rec.salary;
        ELSE
            RAISE NOTICE 'ID % not found', v_id;
        END IF;
    END LOOP;
END;
$$;

SELECT process_ids(ARRAY[1,2,3,99]);
```

### CONTINUE — Skip Iteration

```sql
DO $$
DECLARE
    i INTEGER;
BEGIN
    FOR i IN 1..10 LOOP
        CONTINUE WHEN i % 2 = 0;    -- skip even numbers
        RAISE NOTICE 'Odd: %', i;
    END LOOP;
    -- Prints: 1, 3, 5, 7, 9
END;
$$;
```

---

## 6. Cursors

Cursors allow row-by-row processing of query results, useful when the result set is too large to fit in memory or when you need complex per-row logic.

### Explicit Cursor

```sql
CREATE OR REPLACE FUNCTION process_large_table()
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    -- Declare cursor
    emp_cursor CURSOR FOR
        SELECT id, name, salary
        FROM employees
        WHERE is_active = true
        ORDER BY id;

    v_id     INTEGER;
    v_name   TEXT;
    v_salary NUMERIC;
BEGIN
    -- Open cursor
    OPEN emp_cursor;

    LOOP
        -- Fetch next row
        FETCH emp_cursor INTO v_id, v_name, v_salary;

        -- Exit when no more rows
        EXIT WHEN NOT FOUND;

        -- Process each row
        RAISE NOTICE 'Processing employee: % (%)', v_name, v_salary;

        IF v_salary < 60000 THEN
            UPDATE employees SET salary = 60000 WHERE id = v_id;
        END IF;
    END LOOP;

    -- Close cursor (important!)
    CLOSE emp_cursor;
END;
$$;
```

### Parameterized Cursor

```sql
CREATE OR REPLACE FUNCTION process_dept(p_dept TEXT)
RETURNS TABLE(emp_id INT, emp_name TEXT, new_salary NUMERIC)
LANGUAGE plpgsql
AS $$
DECLARE
    -- Parameterized cursor
    dept_cursor CURSOR(dept_name TEXT) FOR
        SELECT id, name, salary
        FROM employees
        WHERE department = dept_name
          AND is_active = true;

    v_rec RECORD;
BEGIN
    -- Open with parameter
    OPEN dept_cursor(p_dept);

    LOOP
        FETCH dept_cursor INTO v_rec;
        EXIT WHEN NOT FOUND;

        -- Compute new salary
        emp_id     := v_rec.id;
        emp_name   := v_rec.name;
        new_salary := ROUND(v_rec.salary * 1.05, 2);

        RETURN NEXT;    -- emit this row in the result set
    END LOOP;

    CLOSE dept_cursor;
END;
$$;

SELECT * FROM process_dept('Engineering');
```

### Cursor for UPDATE (FOR UPDATE)

```sql
CREATE OR REPLACE FUNCTION update_low_salaries(min_salary NUMERIC)
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    update_cursor CURSOR FOR
        SELECT id, salary
        FROM employees
        WHERE salary < min_salary
        FOR UPDATE;             -- lock rows for update

    v_rec    RECORD;
    v_count  INTEGER := 0;
BEGIN
    OPEN update_cursor;

    LOOP
        FETCH update_cursor INTO v_rec;
        EXIT WHEN NOT FOUND;

        -- Update via cursor (avoids re-finding the row)
        UPDATE employees
        SET salary = min_salary
        WHERE CURRENT OF update_cursor;   -- CURRENT OF = current cursor row

        v_count := v_count + 1;
    END LOOP;

    CLOSE update_cursor;
    RETURN v_count;
END;
$$;

SELECT update_low_salaries(60000);   -- returns number of rows updated
```

### Ref Cursors (Return Cursor to Client)

```sql
CREATE OR REPLACE FUNCTION get_emp_cursor(p_dept TEXT)
RETURNS refcursor
LANGUAGE plpgsql
AS $$
DECLARE
    ref_cur refcursor := 'emp_cur';   -- named cursor
BEGIN
    OPEN ref_cur FOR
        SELECT id, name, salary
        FROM employees
        WHERE department = p_dept;

    RETURN ref_cur;
END;
$$;

-- Client must be in a transaction to use refcursor
BEGIN;
SELECT get_emp_cursor('Engineering');     -- returns cursor name: emp_cur
FETCH ALL FROM emp_cur;                   -- fetch all rows
CLOSE emp_cur;
COMMIT;
```

---

## 7. Exception Handling

### EXCEPTION Block

```sql
CREATE OR REPLACE FUNCTION safe_divide(a NUMERIC, b NUMERIC)
RETURNS NUMERIC
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN a / b;

EXCEPTION
    WHEN division_by_zero THEN
        RAISE NOTICE 'Cannot divide by zero — returning NULL';
        RETURN NULL;
    WHEN numeric_value_out_of_range THEN
        RAISE NOTICE 'Result out of range';
        RETURN NULL;
    WHEN OTHERS THEN
        RAISE NOTICE 'Unexpected error: % (SQLSTATE: %)', SQLERRM, SQLSTATE;
        RETURN NULL;
END;
$$;

SELECT safe_divide(10, 2);    -- 5
SELECT safe_divide(10, 0);    -- NULL with notice
```

### Named Exception Conditions

```sql
CREATE OR REPLACE FUNCTION insert_employee(
    p_name  TEXT,
    p_email TEXT,
    p_sal   NUMERIC
)
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    new_id INTEGER;
BEGIN
    INSERT INTO employees(name, email, salary, department, joined_at)
    VALUES (p_name, p_email, p_sal, 'New Hire', CURRENT_DATE)
    RETURNING id INTO new_id;

    RETURN new_id;

EXCEPTION
    -- Unique constraint violation (duplicate email)
    WHEN unique_violation THEN
        RAISE EXCEPTION 'Email % already exists', p_email
            USING ERRCODE = '23505';

    -- NOT NULL violation
    WHEN not_null_violation THEN
        RAISE EXCEPTION 'Required field is missing'
            USING ERRCODE = '23502';

    -- Foreign key violation
    WHEN foreign_key_violation THEN
        RAISE EXCEPTION 'Referenced record does not exist'
            USING ERRCODE = '23503';

    -- Check constraint violation
    WHEN check_violation THEN
        RAISE EXCEPTION 'Value violates business constraint'
            USING ERRCODE = '23514';

    WHEN OTHERS THEN
        RAISE EXCEPTION 'Insert failed: %', SQLERRM;
END;
$$;
```

### SQLSTATE Error Codes

```sql
-- Common SQLSTATE codes:
-- 23505  unique_violation          (duplicate key)
-- 23503  foreign_key_violation
-- 23502  not_null_violation
-- 23514  check_violation
-- 42P01  undefined_table
-- 42703  undefined_column
-- 42883  undefined_function
-- 55P03  lock_not_available        (NOWAIT failed)
-- 40001  serialization_failure     (retry needed)
-- 40P01  deadlock_detected
-- P0001  raise_exception           (user-raised)

-- Catch by SQLSTATE:
EXCEPTION
    WHEN SQLSTATE '23505' THEN
        -- handle duplicate
    WHEN SQLSTATE '40001' THEN
        -- handle serialization failure (retry logic)
```

### Nested Blocks & Rollback to Savepoint

```sql
CREATE OR REPLACE FUNCTION try_insert_with_fallback(p_name TEXT, p_email TEXT)
RETURNS TEXT
LANGUAGE plpgsql
AS $$
DECLARE
    result TEXT;
BEGIN
    -- Outer block
    BEGIN
        INSERT INTO employees(name, email, salary, department, joined_at)
        VALUES (p_name, p_email, 50000, 'Temp', CURRENT_DATE);
        result := 'inserted';

    EXCEPTION
        WHEN unique_violation THEN
            -- Inner exception: update instead
            UPDATE employees SET name = p_name WHERE email = p_email;
            result := 'updated';
    END;

    -- Outer block continues regardless
    RAISE NOTICE 'Operation: %', result;
    RETURN result;
END;
$$;
```

### GET STACKED DIAGNOSTICS

```sql
CREATE OR REPLACE FUNCTION logged_operation()
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    v_state   TEXT;
    v_msg     TEXT;
    v_detail  TEXT;
    v_hint    TEXT;
    v_context TEXT;
BEGIN
    -- ... do something that might fail ...
    RAISE EXCEPTION 'Something went wrong';

EXCEPTION
    WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS
            v_state   = RETURNED_SQLSTATE,
            v_msg     = MESSAGE_TEXT,
            v_detail  = PG_EXCEPTION_DETAIL,
            v_hint    = PG_EXCEPTION_HINT,
            v_context = PG_EXCEPTION_CONTEXT;

        -- Log full diagnostic info
        RAISE NOTICE E'Error: %\nState: %\nDetail: %\nHint: %\nContext: %',
            v_msg, v_state, v_detail, v_hint, v_context;

        -- Re-raise or handle
        RAISE;
END;
$$;
```

---

## 8. Return Types — All Variants

### RETURNS VOID

```sql
-- No return value — used for side effects only
CREATE OR REPLACE FUNCTION log_event(p_action TEXT, p_detail TEXT)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO audit_log(table_name, operation, new_data)
    VALUES ('system', p_action, jsonb_build_object('detail', p_detail));
END;
$$;

SELECT log_event('LOGIN', 'User alice logged in');
-- or just call it:
PERFORM log_event('LOGIN', 'User alice logged in');   -- inside PL/pgSQL
```

### RETURNS SETOF — Return Multiple Rows

```sql
-- Return a set of scalar values
CREATE OR REPLACE FUNCTION get_dept_names()
RETURNS SETOF TEXT
LANGUAGE plpgsql
STABLE
AS $$
BEGIN
    RETURN QUERY
        SELECT DISTINCT department
        FROM employees
        WHERE is_active = true
        ORDER BY department;
END;
$$;

SELECT * FROM get_dept_names();
-- Engineering
-- Marketing
-- Sales
```

### RETURNS TABLE — Return Typed Rows

```sql
-- Most common pattern for returning result sets
CREATE OR REPLACE FUNCTION get_department_summary()
RETURNS TABLE(
    dept        TEXT,
    headcount   BIGINT,
    avg_salary  NUMERIC,
    total_cost  NUMERIC,
    min_salary  NUMERIC,
    max_salary  NUMERIC
)
LANGUAGE plpgsql
STABLE
AS $$
BEGIN
    RETURN QUERY
        SELECT
            department,
            COUNT(*)::BIGINT,
            ROUND(AVG(salary), 2),
            ROUND(SUM(salary), 2),
            MIN(salary),
            MAX(salary)
        FROM employees
        WHERE is_active = true
        GROUP BY department
        ORDER BY total_cost DESC;
END;
$$;

SELECT * FROM get_department_summary();
```

**Result:**

| dept | headcount | avg_salary | total_cost | min_salary | max_salary |
|------|-----------|-----------|------------|------------|------------|
| Engineering | 3 | 85000 | 255000 | 72000 | 95000 |
| Marketing | 2 | 61500 | 123000 | 58000 | 65000 |

### RETURNS TABLE with RETURN NEXT

```sql
-- Emit rows one at a time (useful for complex row-building logic)
CREATE OR REPLACE FUNCTION enriched_employees(p_dept TEXT DEFAULT NULL)
RETURNS TABLE(
    emp_id    INTEGER,
    full_name TEXT,
    salary    NUMERIC,
    grade     TEXT,
    dept      TEXT,
    tenure_yrs NUMERIC
)
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT e.id, e.name, e.salary, e.department, e.joined_at
        FROM   employees e
        WHERE  (p_dept IS NULL OR e.department = p_dept)
          AND  e.is_active = true
        ORDER BY e.salary DESC
    LOOP
        emp_id     := r.id;
        full_name  := r.name;
        salary     := r.salary;
        grade      := salary_grade(r.salary);    -- call another function
        dept       := r.department;
        tenure_yrs := ROUND(
            EXTRACT(EPOCH FROM (NOW() - r.joined_at)) / 86400 / 365, 1
        );
        RETURN NEXT;    -- emit current row
    END LOOP;
END;
$$;

SELECT * FROM enriched_employees();
SELECT * FROM enriched_employees('Engineering');
```

### RETURNS RECORD (Generic)

```sql
CREATE OR REPLACE FUNCTION get_min_max(p_dept TEXT)
RETURNS RECORD
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    result RECORD;
BEGIN
    SELECT MIN(salary), MAX(salary), AVG(salary)
    INTO   result
    FROM   employees
    WHERE  department = p_dept;

    RETURN result;
END;
$$;

-- Must specify column aliases when calling:
SELECT * FROM get_min_max('Engineering') AS (min_sal NUMERIC, max_sal NUMERIC, avg_sal NUMERIC);
```

### OUT Parameters

```sql
-- OUT parameters effectively replace RETURN in some patterns
CREATE OR REPLACE FUNCTION salary_stats(
    p_dept      TEXT,
    OUT min_sal NUMERIC,
    OUT max_sal NUMERIC,
    OUT avg_sal NUMERIC,
    OUT count   BIGINT
)
LANGUAGE plpgsql
STABLE
AS $$
BEGIN
    SELECT MIN(salary), MAX(salary), AVG(salary), COUNT(*)
    INTO   min_sal, max_sal, avg_sal, count
    FROM   employees
    WHERE  department = p_dept;
END;
$$;

-- Returns a single row automatically (no RETURN needed)
SELECT * FROM salary_stats('Engineering');
-- Or call individual OUT params:
SELECT (salary_stats('Engineering')).avg_sal;
```

### INOUT Parameters

```sql
-- INOUT: parameter is both input and output
CREATE OR REPLACE FUNCTION apply_bonus(
    INOUT p_salary  NUMERIC,
    IN    pct       NUMERIC DEFAULT 10
)
LANGUAGE plpgsql
AS $$
BEGIN
    p_salary := p_salary * (1 + pct / 100.0);
END;
$$;

SELECT apply_bonus(80000);         -- returns 88000
SELECT apply_bonus(80000, 15);     -- returns 92000
```

---

## 9. Stored Procedures (CALL)

Procedures are similar to functions but support transaction control with `COMMIT` and `ROLLBACK` inside the body.

### Basic Procedure

```sql
CREATE OR REPLACE PROCEDURE update_salaries(
    p_dept  TEXT,
    p_pct   NUMERIC
)
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE employees
    SET salary = salary * (1 + p_pct / 100.0)
    WHERE department = p_dept
      AND is_active  = true;

    RAISE NOTICE '% employees in % received a % %% raise',
        ROW_COUNT(), p_dept, p_pct;
END;
$$;

CALL update_salaries('Engineering', 10);
CALL update_salaries('Marketing', 5);
```

### Procedure with Transaction Control

```sql
-- Procedures can COMMIT/ROLLBACK — functions cannot!
CREATE OR REPLACE PROCEDURE batch_process_orders(batch_size INTEGER DEFAULT 1000)
LANGUAGE plpgsql
AS $$
DECLARE
    processed INTEGER := 0;
    v_id      BIGINT;
BEGIN
    LOOP
        -- Process one batch
        WITH batch AS (
            SELECT id FROM orders
            WHERE status = 'pending'
            ORDER BY created_at
            LIMIT batch_size
            FOR UPDATE SKIP LOCKED
        )
        UPDATE orders
        SET status = 'processing'
        FROM batch
        WHERE orders.id = batch.id;

        GET DIAGNOSTICS processed = ROW_COUNT;
        EXIT WHEN processed = 0;    -- no more rows

        RAISE NOTICE 'Processed batch of % orders', processed;

        COMMIT;     -- commit each batch! (not possible in functions)
                    -- releases locks, frees WAL, allows vacuuming
    END LOOP;

    RAISE NOTICE 'Batch processing complete';
END;
$$;

CALL batch_process_orders(500);
```

### Money Transfer Procedure

```sql
CREATE OR REPLACE PROCEDURE transfer_money(
    p_from_id INTEGER,
    p_to_id   INTEGER,
    p_amount  NUMERIC
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_from_balance NUMERIC;
    v_to_name      TEXT;
    v_from_name    TEXT;
BEGIN
    -- Validate amount
    IF p_amount <= 0 THEN
        RAISE EXCEPTION 'Transfer amount must be positive: %', p_amount;
    END IF;

    -- Lock both rows in consistent order (prevent deadlock)
    SELECT balance, owner INTO v_from_balance, v_from_name
    FROM accounts
    WHERE id = p_from_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Source account % not found', p_from_id;
    END IF;

    SELECT owner INTO v_to_name
    FROM accounts
    WHERE id = p_to_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Destination account % not found', p_to_id;
    END IF;

    -- Check sufficient funds
    IF v_from_balance < p_amount THEN
        RAISE EXCEPTION 'Insufficient funds: balance=% required=%',
            v_from_balance, p_amount;
    END IF;

    -- Execute transfer
    UPDATE accounts SET balance = balance - p_amount,
                        updated_at = NOW()
    WHERE id = p_from_id;

    UPDATE accounts SET balance = balance + p_amount,
                        updated_at = NOW()
    WHERE id = p_to_id;

    -- Log transfer
    INSERT INTO audit_log(table_name, operation, new_data)
    VALUES ('accounts', 'TRANSFER',
        jsonb_build_object(
            'from', v_from_name, 'to', v_to_name,
            'amount', p_amount, 'timestamp', NOW()
        )
    );

    RAISE NOTICE 'Transferred % from % to %', p_amount, v_from_name, v_to_name;
END;
$$;

-- Usage
BEGIN;
CALL transfer_money(1, 2, 500.00);
COMMIT;

-- Or just CALL (auto-commit):
CALL transfer_money(1, 2, 500.00);
```

---

## 10. Function Overloading

PostgreSQL allows **multiple functions with the same name** but different parameter types.

```sql
-- Overload 1: takes INTEGER
CREATE OR REPLACE FUNCTION get_employee(p_id INTEGER)
RETURNS TEXT
LANGUAGE plpgsql
STABLE
AS $$
DECLARE v_name TEXT;
BEGIN
    SELECT name INTO v_name FROM employees WHERE id = p_id;
    RETURN COALESCE(v_name, 'Not found by ID: ' || p_id);
END;
$$;

-- Overload 2: takes TEXT (email lookup)
CREATE OR REPLACE FUNCTION get_employee(p_email TEXT)
RETURNS TEXT
LANGUAGE plpgsql
STABLE
AS $$
DECLARE v_name TEXT;
BEGIN
    SELECT name INTO v_name FROM employees WHERE email = p_email;
    RETURN COALESCE(v_name, 'Not found by email: ' || p_email);
END;
$$;

-- Overload 3: takes nothing (returns count)
CREATE OR REPLACE FUNCTION get_employee()
RETURNS BIGINT
LANGUAGE sql
STABLE
AS $$
    SELECT COUNT(*) FROM employees WHERE is_active = true;
$$;

-- PostgreSQL resolves by argument type:
SELECT get_employee(1);                     -- by id
SELECT get_employee('alice@co.com');        -- by email
SELECT get_employee();                      -- count

-- Drop specific overload:
DROP FUNCTION get_employee(INTEGER);
DROP FUNCTION get_employee(TEXT);
```

---

## 11. Function Volatility & Behavior

### Volatility Categories

```sql
-- VOLATILE (default): may return different results each call
-- Cannot be optimized away. Can modify database.
CREATE OR REPLACE FUNCTION get_random_employee()
RETURNS TEXT
LANGUAGE sql
VOLATILE                    -- default, even if omitted
AS $$
    SELECT name FROM employees ORDER BY RANDOM() LIMIT 1;
$$;

-- STABLE: same result for same input WITHIN a transaction.
-- Can be used in indexes. Cannot modify database.
CREATE OR REPLACE FUNCTION get_emp_name(p_id INTEGER)
RETURNS TEXT
LANGUAGE sql
STABLE                      -- same input → same result in this transaction
AS $$
    SELECT name FROM employees WHERE id = p_id;
$$;

-- IMMUTABLE: same result ALWAYS for same input.
-- Can be used in index expressions. Planner may evaluate at plan time.
CREATE OR REPLACE FUNCTION full_name(first TEXT, last TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE                   -- pure function: no DB access, no side effects
AS $$
    SELECT first || ' ' || last;
$$;

-- Index using IMMUTABLE function:
CREATE INDEX idx_emp_lower_name ON employees(LOWER(name));
-- LOWER() is IMMUTABLE → safe to use in index
```

### STRICT / RETURNS NULL ON NULL INPUT

```sql
-- STRICT: if any input is NULL, return NULL without executing
CREATE OR REPLACE FUNCTION safe_upper(s TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
STRICT              -- returns NULL if s IS NULL (no body execution)
AS $$
    SELECT UPPER(s);
$$;

SELECT safe_upper('hello');  -- HELLO
SELECT safe_upper(NULL);     -- NULL (body not executed)

-- Default (CALLED ON NULL INPUT): function is called even with NULL inputs
CREATE OR REPLACE FUNCTION coalesce_custom(a TEXT, b TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
CALLED ON NULL INPUT    -- default, explicit
AS $$
    SELECT COALESCE(a, b, 'default');
$$;

SELECT coalesce_custom(NULL, 'fallback');  -- fallback
```

### COST and ROWS Hints

```sql
-- COST: tells planner how expensive this function is (default: 100)
-- Higher = planner avoids calling it when possible
-- ROWS: for set-returning functions, estimated rows returned

CREATE OR REPLACE FUNCTION expensive_lookup(p_id INTEGER)
RETURNS NUMERIC
LANGUAGE plpgsql
STABLE
COST 500            -- 5× more expensive than default
AS $$
BEGIN
    -- complex multi-table lookup
    RETURN (SELECT SUM(oi.qty * oi.unit_price)
            FROM order_items oi
            JOIN orders o ON o.id = oi.order_id
            WHERE o.customer_id = p_id);
END;
$$;

CREATE OR REPLACE FUNCTION get_active_employees()
RETURNS SETOF employees
LANGUAGE sql
STABLE
ROWS 100            -- planner estimates 100 rows returned (tune this!)
AS $$
    SELECT * FROM employees WHERE is_active = true;
$$;
```

---

## 12. Triggers & Trigger Functions

A **trigger** fires automatically before or after a data change. The **trigger function** is a regular function returning `TRIGGER`.

### BEFORE INSERT Trigger — Auto-populate fields

```sql
-- Trigger function (must return TRIGGER type)
CREATE OR REPLACE FUNCTION set_defaults_before_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    -- NEW = the row being inserted
    -- OLD = the old row (for UPDATE/DELETE)

    -- Normalize email to lowercase
    NEW.email := LOWER(TRIM(NEW.email));

    -- Ensure name is title-cased
    NEW.name  := INITCAP(TRIM(NEW.name));

    -- Set joined_at if not provided
    IF NEW.joined_at IS NULL THEN
        NEW.joined_at := CURRENT_DATE;
    END IF;

    RETURN NEW;    -- return modified row
END;
$$;

-- Attach trigger to employees table
CREATE TRIGGER trg_employees_before_insert
    BEFORE INSERT
    ON employees
    FOR EACH ROW
    EXECUTE FUNCTION set_defaults_before_insert();

-- Test
INSERT INTO employees(name, email, salary, department)
VALUES ('  john doe  ', '  JOHN@CO.COM  ', 70000, 'Sales');
-- Name stored as: John Doe
-- Email stored as: john@co.com
```

### AFTER INSERT/UPDATE/DELETE — Audit Log

```sql
CREATE OR REPLACE FUNCTION audit_employee_changes()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO audit_log(table_name, operation, old_data, new_data)
        VALUES (TG_TABLE_NAME, 'INSERT', NULL, row_to_json(NEW)::JSONB);
        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN
        -- Only log if something actually changed
        IF row_to_json(NEW)::JSONB <> row_to_json(OLD)::JSONB THEN
            INSERT INTO audit_log(table_name, operation, old_data, new_data)
            VALUES (TG_TABLE_NAME, 'UPDATE',
                    row_to_json(OLD)::JSONB,
                    row_to_json(NEW)::JSONB);
        END IF;
        RETURN NEW;

    ELSIF TG_OP = 'DELETE' THEN
        INSERT INTO audit_log(table_name, operation, old_data, new_data)
        VALUES (TG_TABLE_NAME, 'DELETE', row_to_json(OLD)::JSONB, NULL);
        RETURN OLD;     -- must return OLD for DELETE triggers
    END IF;

    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_employees_audit
    AFTER INSERT OR UPDATE OR DELETE
    ON employees
    FOR EACH ROW
    EXECUTE FUNCTION audit_employee_changes();

-- Test
UPDATE employees SET salary = 100000 WHERE id = 1;
SELECT * FROM audit_log ORDER BY id DESC LIMIT 3;
```

### BEFORE UPDATE — Validation Trigger

```sql
CREATE OR REPLACE FUNCTION validate_salary_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    max_raise_pct CONSTANT NUMERIC := 50.0;   -- max 50% raise at once
BEGIN
    -- Prevent salary decrease
    IF NEW.salary < OLD.salary THEN
        RAISE EXCEPTION 'Salary decrease not allowed: % → %',
            OLD.salary, NEW.salary
            USING ERRCODE = 'P0001';
    END IF;

    -- Prevent raise > 50%
    IF NEW.salary > OLD.salary * (1 + max_raise_pct / 100.0) THEN
        RAISE EXCEPTION 'Raise of more than %% not allowed: % → %',
            max_raise_pct, OLD.salary, NEW.salary
            USING ERRCODE = 'P0001';
    END IF;

    -- Auto-track who changed it
    NEW.metadata := NEW.metadata || jsonb_build_object(
        'last_salary_change_by',   CURRENT_USER,
        'last_salary_change_at',   NOW(),
        'previous_salary',         OLD.salary
    );

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_employees_salary_check
    BEFORE UPDATE OF salary
    ON employees
    FOR EACH ROW
    EXECUTE FUNCTION validate_salary_change();

-- Test
UPDATE employees SET salary = 150000 WHERE id = 1;  -- 95000 → 150000: too big!
-- ERROR: Raise of more than 50% not allowed: 95000 → 150000
```

### STATEMENT-Level Trigger

```sql
-- Fires once per statement (not per row)
CREATE OR REPLACE FUNCTION log_bulk_operation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    -- TG_OP  = INSERT, UPDATE, or DELETE
    -- TG_TABLE_NAME = table name
    -- No NEW/OLD in statement-level triggers
    INSERT INTO audit_log(table_name, operation, new_data)
    VALUES (TG_TABLE_NAME, 'BULK_' || TG_OP,
            jsonb_build_object('user', CURRENT_USER, 'at', NOW()));
    RETURN NULL;    -- statement triggers return NULL
END;
$$;

CREATE TRIGGER trg_orders_bulk_log
    AFTER INSERT OR UPDATE OR DELETE
    ON orders
    FOR EACH STATEMENT    -- once per statement, not per row
    EXECUTE FUNCTION log_bulk_operation();
```

### INSTEAD OF Trigger (Views)

```sql
-- Enable INSERT/UPDATE/DELETE on a view
CREATE VIEW active_employees AS
    SELECT id, name, email, salary, department
    FROM employees
    WHERE is_active = true;

CREATE OR REPLACE FUNCTION view_employee_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO employees(name, email, salary, department, is_active)
    VALUES (NEW.name, NEW.email, NEW.salary, NEW.department, true);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_active_employees_insert
    INSTEAD OF INSERT
    ON active_employees
    FOR EACH ROW
    EXECUTE FUNCTION view_employee_insert();

-- Now you can INSERT into the view
INSERT INTO active_employees(name, email, salary, department)
VALUES ('New Person', 'new@co.com', 55000, 'Support');
```

### Trigger Special Variables

```sql
-- Available inside trigger functions:
NEW          -- new row (INSERT, UPDATE); NULL for DELETE
OLD          -- old row (UPDATE, DELETE); NULL for INSERT
TG_OP        -- 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE'
TG_TABLE_NAME  -- name of the table
TG_TABLE_SCHEMA -- schema name
TG_WHEN        -- 'BEFORE', 'AFTER', 'INSTEAD OF'
TG_LEVEL       -- 'ROW', 'STATEMENT'
TG_NAME        -- trigger name
TG_NARGS       -- number of trigger arguments
TG_ARGV[]      -- trigger arguments array (from CREATE TRIGGER ... EXECUTE FUNCTION f('arg1','arg2'))
```

---

## 13. Security & Permissions

### SECURITY DEFINER vs SECURITY INVOKER

```sql
-- SECURITY INVOKER (default): function runs with CALLER's permissions
CREATE OR REPLACE FUNCTION public_query()
RETURNS BIGINT
LANGUAGE sql
SECURITY INVOKER    -- caller must have SELECT on employees
STABLE
AS $$
    SELECT COUNT(*) FROM employees;
$$;

-- SECURITY DEFINER: function runs with OWNER's permissions
-- Useful for giving controlled access to restricted tables
CREATE OR REPLACE FUNCTION get_salary(p_id INTEGER)
RETURNS NUMERIC
LANGUAGE plpgsql
SECURITY DEFINER    -- runs as function owner, not caller
STABLE
-- Always set search_path when using SECURITY DEFINER (prevent injection)
SET search_path = public, pg_temp
AS $$
DECLARE
    v_salary NUMERIC;
BEGIN
    SELECT salary INTO v_salary FROM employees WHERE id = p_id;
    RETURN v_salary;
END;
$$;

-- Grant execute to app user (but app user has no direct SELECT on employees)
GRANT EXECUTE ON FUNCTION get_salary(INTEGER) TO app_user;
-- Now app_user can call get_salary() but cannot SELECT from employees directly
```

### Function Permissions

```sql
-- Grant execute to a role
GRANT EXECUTE ON FUNCTION greet(TEXT) TO app_user;
GRANT EXECUTE ON FUNCTION get_department_summary() TO reporting_user;

-- Revoke execute
REVOKE EXECUTE ON FUNCTION greet(TEXT) FROM app_user;

-- Grant to PUBLIC (everyone)
GRANT EXECUTE ON FUNCTION add_numbers(NUMERIC, NUMERIC) TO PUBLIC;

-- Revoke from PUBLIC (default is PUBLIC can execute)
REVOKE EXECUTE ON FUNCTION salary_grade(NUMERIC) FROM PUBLIC;

-- Grant all functions in schema
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA myschema TO app_user;

-- Alter default privileges for future functions
ALTER DEFAULT PRIVILEGES IN SCHEMA myschema
    GRANT EXECUTE ON FUNCTIONS TO app_user;

-- Change function owner
ALTER FUNCTION get_salary(INTEGER) OWNER TO new_owner;
```

---

## 14. Dynamic SQL

### EXECUTE — Run Dynamic SQL

```sql
CREATE OR REPLACE FUNCTION count_table_rows(p_table TEXT)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
    v_count BIGINT;
    v_sql   TEXT;
BEGIN
    -- Build and execute dynamic SQL
    v_sql := FORMAT('SELECT COUNT(*) FROM %I', p_table);
    EXECUTE v_sql INTO v_count;
    RETURN v_count;
END;
$$;

SELECT count_table_rows('employees');   -- dynamic table name
SELECT count_table_rows('orders');
```

### FORMAT — Safe Dynamic SQL

```sql
-- FORMAT with %I (identifier) and %L (literal) prevents SQL injection
CREATE OR REPLACE FUNCTION dynamic_filter(
    p_table  TEXT,
    p_column TEXT,
    p_value  TEXT
)
RETURNS TABLE(result JSONB)
LANGUAGE plpgsql
AS $$
DECLARE
    v_sql TEXT;
BEGIN
    -- %I = quoted identifier (safe for table/column names)
    -- %L = quoted literal     (safe for values)
    v_sql := FORMAT(
        'SELECT row_to_json(t)::JSONB FROM %I t WHERE %I = %L',
        p_table,     -- %I → "employees"
        p_column,    -- %I → "department"
        p_value      -- %L → 'Engineering'
    );

    RETURN QUERY EXECUTE v_sql;
END;
$$;

SELECT * FROM dynamic_filter('employees', 'department', 'Engineering');

-- NEVER concatenate user input directly:
-- v_sql := 'SELECT * FROM ' || p_table;  -- SQL INJECTION RISK!
-- Always use FORMAT with %I and %L
```

### EXECUTE with Parameters (USING)

```sql
CREATE OR REPLACE FUNCTION dynamic_lookup(
    p_table  TEXT,
    p_id     INTEGER
)
RETURNS JSONB
LANGUAGE plpgsql
AS $$
DECLARE
    v_result JSONB;
BEGIN
    -- USING clause: safe parameterized values (no quoting needed)
    EXECUTE FORMAT('SELECT row_to_json(t)::JSONB FROM %I t WHERE id = $1', p_table)
    INTO    v_result
    USING   p_id;   -- $1 bound to p_id safely

    RETURN v_result;
END;
$$;

SELECT dynamic_lookup('employees', 1);
SELECT dynamic_lookup('orders', 42);
```

### Dynamic Table Creation & Index Building

```sql
CREATE OR REPLACE PROCEDURE create_monthly_summary(p_month DATE)
LANGUAGE plpgsql
AS $$
DECLARE
    v_table TEXT;
    v_month TEXT;
BEGIN
    v_month := TO_CHAR(p_month, 'YYYY_MM');
    v_table := 'summary_' || v_month;

    -- Create summary table
    EXECUTE FORMAT('
        CREATE TABLE IF NOT EXISTS %I AS
        SELECT
            customer_id,
            COUNT(*)           AS order_count,
            SUM(amount)        AS total_amount,
            AVG(amount)        AS avg_amount
        FROM orders
        WHERE DATE_TRUNC(''month'', created_at) = %L
        GROUP BY customer_id',
        v_table,
        DATE_TRUNC('month', p_month)
    );

    -- Add index
    EXECUTE FORMAT(
        'CREATE INDEX IF NOT EXISTS %I ON %I(customer_id)',
        'idx_' || v_table || '_cust',
        v_table
    );

    RAISE NOTICE 'Created summary table: %', v_table;
    COMMIT;
END;
$$;

CALL create_monthly_summary('2024-03-01');
```

---

## 15. Advanced Patterns

### Pattern 1: Retry on Serialization Failure

```sql
CREATE OR REPLACE PROCEDURE transfer_with_retry(
    p_from    INTEGER,
    p_to      INTEGER,
    p_amount  NUMERIC,
    p_retries INTEGER DEFAULT 5
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_attempt INTEGER := 0;
    v_done    BOOLEAN := false;
BEGIN
    WHILE NOT v_done AND v_attempt < p_retries LOOP
        BEGIN
            v_attempt := v_attempt + 1;

            UPDATE accounts SET balance = balance - p_amount
            WHERE id = p_from;

            UPDATE accounts SET balance = balance + p_amount
            WHERE id = p_to;

            COMMIT;         -- commit inside procedure
            v_done := true;

        EXCEPTION
            WHEN serialization_failure OR deadlock_detected THEN
                ROLLBACK;
                RAISE NOTICE 'Attempt % failed, retrying...', v_attempt;
                PERFORM pg_sleep(0.1 * v_attempt);  -- exponential backoff
        END;
    END LOOP;

    IF NOT v_done THEN
        RAISE EXCEPTION 'Transfer failed after % attempts', p_retries;
    END IF;
END;
$$;
```

### Pattern 2: Upsert Function

```sql
CREATE OR REPLACE FUNCTION upsert_employee(
    p_email   TEXT,
    p_name    TEXT,
    p_salary  NUMERIC,
    p_dept    TEXT
)
RETURNS TABLE(emp_id INTEGER, action TEXT)
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN QUERY
    INSERT INTO employees(name, email, salary, department, joined_at)
    VALUES (p_name, p_email, p_salary, p_dept, CURRENT_DATE)
    ON CONFLICT (email) DO UPDATE
        SET name       = EXCLUDED.name,
            salary     = EXCLUDED.salary,
            department = EXCLUDED.department
    RETURNING
        id,
        CASE WHEN xmax = 0 THEN 'inserted' ELSE 'updated' END;
END;
$$;

SELECT * FROM upsert_employee('alice@co.com', 'Alice Johnson', 100000, 'Engineering');
-- (1, 'updated')

SELECT * FROM upsert_employee('new@co.com', 'New Person', 55000, 'HR');
-- (6, 'inserted')
```

### Pattern 3: Soft Delete with History

```sql
-- Add deleted_at column
ALTER TABLE employees ADD COLUMN deleted_at TIMESTAMPTZ;

-- Soft delete function
CREATE OR REPLACE FUNCTION soft_delete_employee(p_id INTEGER)
RETURNS BOOLEAN
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE employees
    SET is_active  = false,
        deleted_at = NOW()
    WHERE id = p_id
      AND deleted_at IS NULL;

    IF FOUND THEN
        INSERT INTO audit_log(table_name, operation, old_data)
        SELECT 'employees', 'SOFT_DELETE', row_to_json(e)::JSONB
        FROM employees e WHERE id = p_id;
        RETURN true;
    END IF;

    RETURN false;
END;
$$;

-- Restore function
CREATE OR REPLACE FUNCTION restore_employee(p_id INTEGER)
RETURNS BOOLEAN
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE employees
    SET is_active  = true,
        deleted_at = NULL
    WHERE id = p_id
      AND deleted_at IS NOT NULL;

    RETURN FOUND;
END;
$$;

SELECT soft_delete_employee(5);   -- true
SELECT restore_employee(5);       -- true
```

### Pattern 4: Table-Valued Function for Reporting

```sql
CREATE OR REPLACE FUNCTION monthly_sales_report(
    p_start DATE,
    p_end   DATE
)
RETURNS TABLE(
    month        TEXT,
    total_orders BIGINT,
    total_amount NUMERIC,
    avg_amount   NUMERIC,
    max_amount   NUMERIC,
    pct_change   NUMERIC
)
LANGUAGE plpgsql
STABLE
AS $$
BEGIN
    RETURN QUERY
    WITH monthly AS (
        SELECT
            DATE_TRUNC('month', created_at)::DATE           AS mnth,
            COUNT(*)                                         AS orders,
            SUM(amount)                                      AS total,
            AVG(amount)                                      AS avg_amt,
            MAX(amount)                                      AS max_amt
        FROM orders
        WHERE created_at::DATE BETWEEN p_start AND p_end
        GROUP BY 1
    )
    SELECT
        TO_CHAR(mnth, 'Month YYYY'),
        orders,
        ROUND(total, 2),
        ROUND(avg_amt, 2),
        ROUND(max_amt, 2),
        ROUND(
            100.0 * (total - LAG(total) OVER (ORDER BY mnth))
                  / NULLIF(LAG(total) OVER (ORDER BY mnth), 0),
            1
        ) AS pct_change
    FROM monthly
    ORDER BY mnth;
END;
$$;

SELECT * FROM monthly_sales_report('2024-01-01', '2024-12-31');
```

### Pattern 5: Recursive Function (Org Chart)

```sql
CREATE OR REPLACE FUNCTION get_org_tree(p_manager_id INTEGER)
RETURNS TABLE(
    emp_id      INTEGER,
    emp_name    TEXT,
    depth       INTEGER,
    path        TEXT
)
LANGUAGE plpgsql
STABLE
AS $$
BEGIN
    RETURN QUERY
    WITH RECURSIVE tree AS (
        -- Base case
        SELECT id, name, 0 AS depth,
               name::TEXT AS path,
               ARRAY[id]  AS visited
        FROM employees
        WHERE id = p_manager_id

        UNION ALL

        -- Recursive case
        SELECT e.id, e.name,
               t.depth + 1,
               t.path || ' → ' || e.name,
               t.visited || e.id
        FROM employees e
        JOIN tree t ON e.manager_id = t.emp_id
        WHERE NOT e.id = ANY(t.visited)
    )
    SELECT id, name, depth, path FROM tree
    ORDER BY path;
END;
$$;

SELECT * FROM get_org_tree(1);   -- entire tree under Alice
```

---

## 16. Debugging & Testing

### RAISE for Debugging

```sql
CREATE OR REPLACE FUNCTION debug_example(p_id INTEGER)
RETURNS NUMERIC
LANGUAGE plpgsql
AS $$
DECLARE
    v_salary NUMERIC;
    v_dept   TEXT;
BEGIN
    RAISE DEBUG 'Starting debug_example with id=%', p_id;

    SELECT salary, department
    INTO   v_salary, v_dept
    FROM   employees WHERE id = p_id;

    RAISE NOTICE 'Found employee: dept=%, salary=%', v_dept, v_salary;

    IF v_salary IS NULL THEN
        RAISE WARNING 'Salary is NULL for id=%', p_id;
        RETURN 0;
    END IF;

    RAISE DEBUG 'Returning salary: %', v_salary;
    RETURN v_salary;
END;
$$;

-- Enable debug messages in session:
SET client_min_messages = debug;
SELECT debug_example(1);
SET client_min_messages = notice;   -- restore default
```

### List All Functions

```sql
-- List functions in current schema
SELECT
    n.nspname                           AS schema,
    p.proname                           AS function_name,
    pg_get_function_arguments(p.oid)    AS arguments,
    pg_get_function_result(p.oid)       AS return_type,
    l.lanname                           AS language,
    CASE p.provolatile
        WHEN 'v' THEN 'VOLATILE'
        WHEN 's' THEN 'STABLE'
        WHEN 'i' THEN 'IMMUTABLE'
    END                                 AS volatility,
    p.prosecdef                         AS security_definer,
    p.proisstrict                       AS is_strict,
    obj_description(p.oid, 'pg_proc')   AS description
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
JOIN pg_language l  ON l.oid = p.prolang
WHERE n.nspname NOT IN ('pg_catalog','information_schema')
  AND p.prokind = 'f'   -- 'f'=function, 'p'=procedure, 'a'=aggregate, 'w'=window
ORDER BY schema, function_name;

-- List procedures only
SELECT proname, pg_get_function_arguments(oid)
FROM pg_proc
WHERE prokind = 'p'
  AND pronamespace = 'public'::regnamespace;
```

### View Function Source Code

```sql
-- View the source of any function
SELECT pg_get_functiondef('get_employee_salary(integer)'::regprocedure);

-- Or:
SELECT proname, prosrc
FROM pg_proc
WHERE proname = 'get_employee_salary'
  AND pronamespace = 'public'::regnamespace;
```

### Add Comments to Functions

```sql
-- Document your functions
COMMENT ON FUNCTION greet(TEXT)
    IS 'Returns a greeting message for the given name.
        Parameters: name — the name to greet.
        Returns: greeting string.';

COMMENT ON FUNCTION transfer_money(INTEGER, INTEGER, NUMERIC)
    IS 'Transfers amount from one account to another.
        Raises exception if insufficient funds.
        Safe against concurrent transfers (row locking).';

-- View comments
SELECT proname, obj_description(oid, 'pg_proc') AS description
FROM pg_proc
WHERE pronamespace = 'public'::regnamespace
  AND obj_description(oid, 'pg_proc') IS NOT NULL;
```

### Testing Functions

```sql
-- Simple test harness using DO block
DO $$
DECLARE
    v_result NUMERIC;
    v_pass   INTEGER := 0;
    v_fail   INTEGER := 0;
BEGIN
    -- Test 1: normal raise
    v_result := raise_salary(2, 10);
    IF v_result = 72000 * 1.1 THEN
        v_pass := v_pass + 1;
        RAISE NOTICE 'PASS: Test 1 — normal raise';
    ELSE
        v_fail := v_fail + 1;
        RAISE WARNING 'FAIL: Test 1 — expected %, got %', 72000*1.1, v_result;
    END IF;

    -- Test 2: salary_grade boundaries
    IF salary_grade(100000) = 'L5 — Staff' THEN
        v_pass := v_pass + 1;
        RAISE NOTICE 'PASS: Test 2 — grade boundary';
    ELSE
        v_fail := v_fail + 1;
        RAISE WARNING 'FAIL: Test 2 — salary_grade(100000)';
    END IF;

    -- Test 3: NULL handling
    IF safe_divide(10, 0) IS NULL THEN
        v_pass := v_pass + 1;
        RAISE NOTICE 'PASS: Test 3 — divide by zero returns NULL';
    ELSE
        v_fail := v_fail + 1;
        RAISE WARNING 'FAIL: Test 3 — safe_divide(10,0) should be NULL';
    END IF;

    RAISE NOTICE '=== Results: % passed, % failed ===', v_pass, v_fail;
END;
$$;
```

### DROP Function / Procedure

```sql
-- Drop function (must specify argument types for overloaded functions)
DROP FUNCTION IF EXISTS greet(TEXT);
DROP FUNCTION IF EXISTS get_employee(INTEGER);
DROP FUNCTION IF EXISTS get_employee(TEXT);

-- Drop procedure
DROP PROCEDURE IF EXISTS transfer_money(INTEGER, INTEGER, NUMERIC);

-- Drop with CASCADE (also drops dependent objects like triggers)
DROP FUNCTION IF EXISTS audit_employee_changes() CASCADE;

-- Drop trigger
DROP TRIGGER IF EXISTS trg_employees_audit ON employees;
DROP TRIGGER IF EXISTS trg_employees_before_insert ON employees;
```

---

## 17. Quick Reference Cheat Sheet

```
╔═══════════════════════════╦══════════════════════════════════════════════════╗
║ TOPIC                     ║ KEY SYNTAX / NOTES                               ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Create Function           ║ CREATE [OR REPLACE] FUNCTION name(args)          ║
║                           ║   RETURNS type LANGUAGE plpgsql AS $$ ... $$;   ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Create Procedure          ║ CREATE [OR REPLACE] PROCEDURE name(args)         ║
║ (PG 11+)                  ║   LANGUAGE plpgsql AS $$ ... $$;                 ║
║                           ║ Call with: CALL name(args);                      ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Return Types              ║ RETURNS TEXT/NUMERIC/INTEGER/...  — scalar       ║
║                           ║ RETURNS VOID                      — no value     ║
║                           ║ RETURNS SETOF type                — multiple rows║
║                           ║ RETURNS TABLE(col type, ...)      — typed rows   ║
║                           ║ RETURN QUERY SELECT ...;          — inline query ║
║                           ║ RETURN NEXT;                      — emit one row ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ OUT / INOUT               ║ OUT col type  — output parameter (no RETURN)     ║
║                           ║ INOUT col type — in and out parameter            ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Volatility                ║ VOLATILE   — default; can change per call        ║
║                           ║ STABLE     — same result within a transaction    ║
║                           ║ IMMUTABLE  — same result always; usable in index ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ NULL handling             ║ STRICT / RETURNS NULL ON NULL INPUT              ║
║                           ║ CALLED ON NULL INPUT (default)                   ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Security                  ║ SECURITY INVOKER (default) — runs as caller      ║
║                           ║ SECURITY DEFINER          — runs as owner        ║
║                           ║ Always: SET search_path with SECURITY DEFINER   ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Variable Declaration      ║ DECLARE  v_name TEXT := 'val';                   ║
║                           ║ %TYPE    — inherit column type                   ║
║                           ║ %ROWTYPE — inherit row structure                 ║
║                           ║ RECORD   — generic row                           ║
║                           ║ CONSTANT — cannot be changed                     ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Control Flow              ║ IF / ELSIF / ELSE / END IF                       ║
║                           ║ CASE x WHEN ... THEN ... END CASE                ║
║                           ║ LOOP / EXIT WHEN / END LOOP                      ║
║                           ║ WHILE cond LOOP / END LOOP                       ║
║                           ║ FOR i IN 1..n LOOP / END LOOP                   ║
║                           ║ FOR rec IN SELECT ... LOOP / END LOOP           ║
║                           ║ FOREACH v IN ARRAY arr LOOP / END LOOP          ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Cursors                   ║ CURSOR FOR SELECT ...; OPEN; FETCH; CLOSE        ║
║                           ║ WHERE CURRENT OF cursor — update via cursor      ║
║                           ║ RETURN refcursor — return cursor to client       ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Exception Handling        ║ EXCEPTION WHEN condition THEN ...                ║
║                           ║ WHEN OTHERS — catch all exceptions               ║
║                           ║ SQLERRM, SQLSTATE — error info                   ║
║                           ║ GET STACKED DIAGNOSTICS — full error context     ║
║                           ║ RAISE EXCEPTION '...' USING ERRCODE='P0001'      ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Triggers                  ║ BEFORE / AFTER / INSTEAD OF                      ║
║                           ║ INSERT / UPDATE / UPDATE OF col / DELETE         ║
║                           ║ FOR EACH ROW / FOR EACH STATEMENT               ║
║                           ║ NEW (inserted/updated row), OLD (old row)        ║
║                           ║ TG_OP, TG_TABLE_NAME, TG_WHEN, TG_LEVEL         ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Dynamic SQL               ║ EXECUTE 'SELECT ...' INTO var USING $1, $2       ║
║                           ║ FORMAT('%I table, %L literal', name, val)        ║
║                           ║ Never concatenate user input → use %I/%L/%s      ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Useful Functions          ║ FOUND            — row found after SELECT INTO   ║
║                           ║ GET DIAGNOSTICS v = ROW_COUNT  — rows affected   ║
║                           ║ PERFORM expr     — call function, discard result ║
║                           ║ RAISE NOTICE/WARNING/EXCEPTION                   ║
╠═══════════════════════════╬══════════════════════════════════════════════════╣
║ Inspect Functions         ║ SELECT pg_get_functiondef('f(int)'::regprocedure)║
║                           ║ SELECT * FROM pg_proc WHERE proname='f'          ║
║ Drop Function             ║ DROP FUNCTION IF EXISTS f(arg_types);            ║
╚═══════════════════════════╩══════════════════════════════════════════════════╝
```

---

## Further Reading

- [PostgreSQL Docs — PL/pgSQL](https://www.postgresql.org/docs/current/plpgsql.html)
- [PostgreSQL Docs — CREATE FUNCTION](https://www.postgresql.org/docs/current/sql-createfunction.html)
- [PostgreSQL Docs — CREATE PROCEDURE](https://www.postgresql.org/docs/current/sql-createprocedure.html)
- [PostgreSQL Docs — Trigger Functions](https://www.postgresql.org/docs/current/plpgsql-trigger.html)
- [PostgreSQL Docs — Cursors](https://www.postgresql.org/docs/current/plpgsql-cursors.html)
- [PostgreSQL Docs — Dynamic SQL](https://www.postgresql.org/docs/current/plpgsql-statements.html#PLPGSQL-STATEMENTS-EXECUTING-DYN)
- [PostgreSQL Docs — Error Codes](https://www.postgresql.org/docs/current/errcodes-appendix.html)

---

*Generated with love for PostgreSQL engineers.*
