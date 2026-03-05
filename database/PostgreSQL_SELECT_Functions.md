# PostgreSQL — All Functions Used in SELECT Clause

> A complete reference of every function category available in PostgreSQL `SELECT` statements, with syntax, description, and working examples.

---

## Table of Contents

1. [String Functions](#1-string-functions)
2. [Numeric & Math Functions](#2-numeric--math-functions)
3. [Date & Time Functions](#3-date--time-functions)
4. [Conditional Functions](#4-conditional-functions)
5. [Aggregate Functions](#5-aggregate-functions)
6. [Window Functions](#6-window-functions)
7. [Type Casting Functions](#7-type-casting-functions)
8. [NULL Handling Functions](#8-null-handling-functions)
9. [Array Functions](#9-array-functions)
10. [JSON / JSONB Functions](#10-json--jsonb-functions)
11. [Regular Expression Functions](#11-regular-expression-functions)
12. [Full Text Search Functions](#12-full-text-search-functions)
13. [Range Functions](#13-range-functions)
14. [System & Information Functions](#14-system--information-functions)
15. [Formatting & Encoding Functions](#15-formatting--encoding-functions)
16. [Quick Reference Cheat Sheet](#16-quick-reference-cheat-sheet)

---

## Sample Table Used in All Examples

```sql
CREATE TABLE employees (
    id          SERIAL PRIMARY KEY,
    name        TEXT,
    email       TEXT,
    salary      NUMERIC,
    department  TEXT,
    joined_at   DATE,
    is_active   BOOLEAN,
    tags        TEXT[],
    metadata    JSONB
);

INSERT INTO employees VALUES
  (1, 'Alice Johnson',  'alice@company.com',  85000, 'Engineering', '2020-03-15', true,
      ARRAY['python','sql'],     '{"level":"senior","score":92}'),
  (2, 'Bob Smith',      'bob@company.com',    62000, 'Marketing',   '2019-07-22', true,
      ARRAY['excel','crm'],      '{"level":"mid","score":74}'),
  (3, 'Charlie Brown',  'charlie@company.com',91000, 'Engineering', '2018-01-10', false,
      ARRAY['java','sql','aws'], '{"level":"senior","score":88}'),
  (4, 'Diana Prince',   'diana@company.com',  73000, 'HR',          '2021-11-05', true,
      ARRAY['hr','recruiting'],  '{"level":"mid","score":81}'),
  (5, 'Eve Wilson',     NULL,                 54000, 'Marketing',   '2022-06-30', true,
      ARRAY['seo','content'],    '{"level":"junior","score":65}');
```

---

## 1. String Functions

| Function | Syntax | Description |
|----------|--------|-------------|
| `UPPER` | `UPPER(str)` | Convert to uppercase |
| `LOWER` | `LOWER(str)` | Convert to lowercase |
| `INITCAP` | `INITCAP(str)` | Capitalise first letter of each word |
| `LENGTH` | `LENGTH(str)` | Number of characters |
| `CHAR_LENGTH` | `CHAR_LENGTH(str)` | SQL-standard alias for LENGTH |
| `OCTET_LENGTH` | `OCTET_LENGTH(str)` | Number of bytes |
| `BIT_LENGTH` | `BIT_LENGTH(str)` | Number of bits |
| `TRIM` | `TRIM([BOTH/LEADING/TRAILING] [char] FROM str)` | Remove leading/trailing chars |
| `LTRIM` | `LTRIM(str [,chars])` | Remove leading characters |
| `RTRIM` | `RTRIM(str [,chars])` | Remove trailing characters |
| `LPAD` | `LPAD(str, n, fill)` | Left-pad to length n |
| `RPAD` | `RPAD(str, n, fill)` | Right-pad to length n |
| `SUBSTRING` | `SUBSTRING(str FROM pos FOR len)` | Extract substring |
| `LEFT` | `LEFT(str, n)` | First n characters |
| `RIGHT` | `RIGHT(str, n)` | Last n characters |
| `POSITION` | `POSITION(sub IN str)` | Find position of substring |
| `STRPOS` | `STRPOS(str, sub)` | Find substring position (function form) |
| `REPLACE` | `REPLACE(str, from, to)` | Replace all occurrences |
| `CONCAT` | `CONCAT(val1, val2, ...)` | Concatenate values, ignores NULLs |
| `CONCAT_WS` | `CONCAT_WS(sep, val1, val2, ...)` | Concatenate with separator, skips NULLs |
| `SPLIT_PART` | `SPLIT_PART(str, delim, n)` | Get nth token after splitting |
| `REVERSE` | `REVERSE(str)` | Reverse a string |
| `REPEAT` | `REPEAT(str, n)` | Repeat string n times |
| `OVERLAY` | `OVERLAY(str PLACING new FROM pos FOR len)` | Replace portion of string |
| `TRANSLATE` | `TRANSLATE(str, from, to)` | Character-by-character replacement |
| `MD5` | `MD5(str)` | MD5 hash as hex string |
| `CHR` | `CHR(n)` | Character from ASCII/Unicode code |
| `ASCII` | `ASCII(str)` | ASCII code of first character |
| `FORMAT` | `FORMAT(fmt, arg, ...)` | Printf-style string formatting |

```sql
SELECT
    UPPER(name)                        AS upper_name,      -- ALICE JOHNSON
    LOWER(name)                        AS lower_name,      -- alice johnson
    INITCAP('hello world')             AS initcap,         -- Hello World
    LENGTH(name)                       AS name_len,        -- 13
    CHAR_LENGTH(name)                  AS char_len,        -- 13
    OCTET_LENGTH(name)                 AS bytes,           -- 13
    TRIM('  hello  ')                  AS trimmed,         -- hello
    LTRIM('  hello')                   AS ltrimmed,        -- hello
    RTRIM('hello  ')                   AS rtrimmed,        -- hello
    LPAD(id::TEXT, 5, '0')             AS padded_id,       -- 00001
    RPAD(department, 15, '.')          AS padded_dept,     -- Engineering...
    SUBSTRING(name FROM 1 FOR 5)       AS sub5,            -- Alice
    LEFT(name, 5)                      AS left5,           -- Alice
    RIGHT(name, 7)                     AS right7,          -- Johnson
    POSITION('o' IN name)              AS pos_o,           -- 10
    STRPOS(name, 'son')                AS strpos,          -- 11
    REPLACE(name, ' ', '_')            AS underscored,     -- Alice_Johnson
    CONCAT(name, ' | ', department)    AS concat,          -- Alice Johnson | Engineering
    CONCAT_WS(' | ', name, NULL, department) AS concat_ws, -- Alice Johnson | Engineering
    SPLIT_PART(email, '@', 1)          AS email_user,      -- alice
    SPLIT_PART(email, '@', 2)          AS email_domain,    -- company.com
    REVERSE(name)                      AS reversed,        -- nosnhoJ ecilA
    REPEAT('*', 5)                     AS stars,           -- *****
    OVERLAY(name PLACING '***' FROM 3 FOR 3) AS overlaid,  -- Al***Johnson
    TRANSLATE(name,'aeiouAEIOU','**********') AS no_vowels, -- *l*c* J*hns*n
    MD5(email)                         AS md5_hash,        -- 5d41402abc...
    CHR(65)                            AS chr_A,           -- A
    ASCII('A')                         AS ascii_A,         -- 65
    FORMAT('%s earns %s', name, salary) AS fmt_msg         -- Alice Johnson earns 85000
FROM employees WHERE id = 1;
```

---

## 2. Numeric & Math Functions

| Function | Syntax | Description |
|----------|--------|-------------|
| `ROUND` | `ROUND(n [, d])` | Round to d decimal places |
| `CEIL / CEILING` | `CEIL(n)` | Round up to nearest integer |
| `FLOOR` | `FLOOR(n)` | Round down to nearest integer |
| `TRUNC` | `TRUNC(n [, d])` | Truncate without rounding |
| `ABS` | `ABS(n)` | Absolute value |
| `SIGN` | `SIGN(n)` | Returns -1, 0, or 1 |
| `MOD` | `MOD(n, d)` | Modulo (remainder) |
| `POWER` | `POWER(base, exp)` | Raise to power |
| `SQRT` | `SQRT(n)` | Square root |
| `CBRT` | `CBRT(n)` | Cube root |
| `EXP` | `EXP(n)` | e raised to power n |
| `LN` | `LN(n)` | Natural logarithm |
| `LOG` | `LOG(base, n)` | Logarithm in given base |
| `RANDOM` | `RANDOM()` | Random float 0.0 to 1.0 |
| `GREATEST` | `GREATEST(v1, v2, ...)` | Largest of list (non-aggregate) |
| `LEAST` | `LEAST(v1, v2, ...)` | Smallest of list (non-aggregate) |
| `PI` | `PI()` | Value of pi |
| `DEGREES` | `DEGREES(rad)` | Radians to degrees |
| `RADIANS` | `RADIANS(deg)` | Degrees to radians |
| `SIN` | `SIN(rad)` | Sine |
| `COS` | `COS(rad)` | Cosine |
| `TAN` | `TAN(rad)` | Tangent |
| `ASIN` | `ASIN(n)` | Inverse sine |
| `ACOS` | `ACOS(n)` | Inverse cosine |
| `ATAN` | `ATAN(n)` | Inverse tangent |
| `ATAN2` | `ATAN2(y, x)` | Angle of point from origin |

```sql
SELECT
    salary,
    ROUND(salary / 12, 2)          AS monthly,         -- 7083.33
    CEIL(salary / 12.0)            AS monthly_ceil,    -- 7084
    FLOOR(salary / 12.0)           AS monthly_floor,   -- 7083
    TRUNC(salary / 12.0, 2)        AS monthly_trunc,   -- 7083.33
    ABS(salary - 75000)            AS diff_75k,        -- 10000
    SIGN(salary - 75000)           AS direction,       -- 1
    MOD(salary::INT, 1000)         AS mod_1000,        -- 0
    POWER(2, 10)                   AS two_pow_10,      -- 1024
    SQRT(salary)                   AS sqrt_sal,        -- 291.54
    CBRT(salary)                   AS cbrt_sal,        -- 44.08
    EXP(1)                         AS e_const,         -- 2.71828
    LN(salary)                     AS ln_sal,          -- 11.35
    LOG(10, salary)                AS log10_sal,       -- 4.93
    RANDOM()                       AS rnd,             -- 0.42...
    FLOOR(RANDOM() * 100 + 1)      AS rand_1_100,      -- 1 to 100
    GREATEST(salary, 70000)        AS floor_70k,       -- 85000
    LEAST(salary, 90000)           AS cap_90k,         -- 85000
    PI()                           AS pi,              -- 3.14159
    DEGREES(PI())                  AS pi_degrees,      -- 180
    SIN(RADIANS(30))               AS sin_30,          -- 0.5
    COS(RADIANS(60))               AS cos_60,          -- 0.5
    TAN(RADIANS(45))               AS tan_45           -- 1.0
FROM employees WHERE id = 1;
```

---

## 3. Date & Time Functions

| Function | Syntax | Description |
|----------|--------|-------------|
| `NOW` | `NOW()` | Current timestamp with timezone |
| `CURRENT_DATE` | `CURRENT_DATE` | Current date only |
| `CURRENT_TIME` | `CURRENT_TIME` | Current time with timezone |
| `CURRENT_TIMESTAMP` | `CURRENT_TIMESTAMP` | Current timestamp with timezone |
| `LOCALTIMESTAMP` | `LOCALTIMESTAMP` | Timestamp without timezone |
| `CLOCK_TIMESTAMP` | `CLOCK_TIMESTAMP()` | Real clock time, changes per row |
| `EXTRACT` | `EXTRACT(field FROM source)` | Get a date/time component |
| `DATE_PART` | `DATE_PART(field, source)` | Get a date/time component (function form) |
| `DATE_TRUNC` | `DATE_TRUNC(unit, value)` | Truncate to given precision |
| `AGE` | `AGE(ts1 [, ts2])` | Interval between two timestamps |
| `TO_CHAR` | `TO_CHAR(val, format)` | Format date/time as string |
| `TO_DATE` | `TO_DATE(str, format)` | Parse string to DATE |
| `TO_TIMESTAMP` | `TO_TIMESTAMP(str, format)` | Parse string to TIMESTAMP |
| `MAKE_DATE` | `MAKE_DATE(y, m, d)` | Build date from parts |
| `MAKE_INTERVAL` | `MAKE_INTERVAL(...)` | Build interval from parts |
| `MAKE_TIMESTAMP` | `MAKE_TIMESTAMP(y,m,d,h,mi,s)` | Build timestamp from parts |

### EXTRACT / DATE_PART Field Values

| Field | Description |
|-------|-------------|
| `YEAR` | Year number |
| `MONTH` | Month 1-12 |
| `DAY` | Day of month |
| `HOUR` | Hour 0-23 |
| `MINUTE` | Minute 0-59 |
| `SECOND` | Seconds incl. fractional |
| `DOW` | Day of week: 0=Sunday |
| `DOY` | Day of year 1-366 |
| `WEEK` | ISO week number |
| `QUARTER` | Quarter 1-4 |
| `EPOCH` | Seconds since 1970-01-01 |

```sql
SELECT
    joined_at,
    CURRENT_DATE                                AS today,
    NOW()                                       AS now_ts,
    EXTRACT(YEAR    FROM joined_at)             AS yr,           -- 2020
    EXTRACT(MONTH   FROM joined_at)             AS mo,           -- 3
    EXTRACT(DAY     FROM joined_at)             AS dy,           -- 15
    EXTRACT(DOW     FROM joined_at)             AS dow,          -- 0 = Sunday
    EXTRACT(QUARTER FROM joined_at)             AS qtr,          -- 1
    EXTRACT(WEEK    FROM joined_at)             AS wk,           -- 11
    EXTRACT(EPOCH   FROM joined_at)             AS epoch,        -- 1584230400
    DATE_PART('year', joined_at)                AS dp_yr,        -- 2020
    DATE_TRUNC('month',   joined_at)            AS trunc_month,  -- 2020-03-01
    DATE_TRUNC('year',    joined_at)            AS trunc_year,   -- 2020-01-01
    DATE_TRUNC('quarter', joined_at)            AS trunc_qtr,    -- 2020-01-01
    DATE_TRUNC('week',    joined_at)            AS trunc_week,   -- 2020-03-09
    AGE(CURRENT_DATE, joined_at)                AS tenure,       -- 5 years 2 mons
    EXTRACT(YEAR FROM AGE(joined_at))           AS years_in,     -- 5
    CURRENT_DATE - joined_at                    AS days_in,      -- 1911
    joined_at + INTERVAL '30 days'              AS plus_30,      -- 2020-04-14
    joined_at - INTERVAL '1 year'               AS minus_1yr,    -- 2019-03-15
    TO_CHAR(joined_at, 'DD-Mon-YYYY')           AS fmt1,         -- 15-Mar-2020
    TO_CHAR(joined_at, 'Day, DD Month YYYY')    AS fmt2,         -- Sunday, 15 March 2020
    TO_CHAR(joined_at, 'YYYY-"Q"Q')             AS fmt3,         -- 2020-Q1
    TO_CHAR(NOW(), 'YYYY-MM-DD HH24:MI:SS')     AS full_ts,
    TO_DATE('15/03/2020', 'DD/MM/YYYY')         AS parsed,       -- 2020-03-15
    MAKE_DATE(2024, 12, 25)                     AS xmas,         -- 2024-12-25
    MAKE_INTERVAL(years => 1, months => 6)      AS interval_1_6  -- 1 year 6 mons
FROM employees WHERE id = 1;
```

---

## 4. Conditional Functions

| Function | Syntax | Description |
|----------|--------|-------------|
| `CASE WHEN` | `CASE WHEN cond THEN val ... ELSE val END` | Conditional expression |
| `COALESCE` | `COALESCE(v1, v2, ...)` | First non-NULL value |
| `NULLIF` | `NULLIF(a, b)` | NULL if a equals b, else a |
| `GREATEST` | `GREATEST(v1, v2, ...)` | Largest value in list |
| `LEAST` | `LEAST(v1, v2, ...)` | Smallest value in list |

```sql
SELECT
    name,
    salary,

    -- Simple CASE (value matching)
    CASE department
        WHEN 'Engineering' THEN 'Tech'
        WHEN 'Marketing'   THEN 'Business'
        WHEN 'HR'          THEN 'People'
        ELSE 'Other'
    END                                         AS dept_group,

    -- Searched CASE (condition based)
    CASE
        WHEN salary >= 90000 THEN 'High'
        WHEN salary >= 70000 THEN 'Mid'
        WHEN salary >= 50000 THEN 'Low'
        ELSE 'Below Band'
    END                                         AS salary_band,

    -- COALESCE: first non-NULL
    COALESCE(email, 'no-email@company.com')     AS safe_email,

    -- NULLIF: return NULL when equal (prevents divide-by-zero)
    100.0 / NULLIF(salary, 0)                   AS safe_div,
    NULLIF(department, 'HR')                    AS dept_not_hr,

    -- GREATEST / LEAST (non-aggregate comparisons)
    GREATEST(salary, 60000)                     AS min_floor,
    LEAST(salary, 90000)                        AS max_cap,

    -- Inline boolean label
    CASE WHEN is_active THEN 'Active' ELSE 'Inactive' END AS status,

    -- Nested CASE
    CASE
        WHEN department = 'Engineering' AND salary > 85000 THEN 'Staff Eng'
        WHEN department = 'Engineering'                    THEN 'Engineer'
        WHEN is_active = false                             THEN 'Offboarded'
        ELSE department
    END                                         AS role

FROM employees;
```

**Result:**

| name | dept_group | salary_band | safe_email | status | role |
|------|------------|-------------|------------|--------|------|
| Alice Johnson | Tech | High | alice@company.com | Active | Engineer |
| Bob Smith | Business | Low | bob@company.com | Active | Marketing |
| Charlie Brown | Tech | High | charlie@company.com | Inactive | Offboarded |
| Diana Prince | People | Mid | diana@company.com | Active | HR |
| Eve Wilson | Business | Low | no-email@company.com | Active | Marketing |

---

## 5. Aggregate Functions

> Operate over a set of rows. Require `GROUP BY` unless used alone.

| Function | Syntax | Description |
|----------|--------|-------------|
| `COUNT` | `COUNT(* / col / DISTINCT col)` | Count rows or non-NULL values |
| `SUM` | `SUM(col)` | Sum of non-NULL values |
| `AVG` | `AVG(col)` | Average of non-NULL values |
| `MIN` | `MIN(col)` | Minimum value |
| `MAX` | `MAX(col)` | Maximum value |
| `STDDEV` | `STDDEV(col)` | Sample standard deviation |
| `STDDEV_POP` | `STDDEV_POP(col)` | Population standard deviation |
| `VARIANCE` | `VARIANCE(col)` | Sample variance |
| `VAR_POP` | `VAR_POP(col)` | Population variance |
| `CORR` | `CORR(y, x)` | Pearson correlation coefficient |
| `REGR_SLOPE` | `REGR_SLOPE(y, x)` | Slope of linear regression line |
| `STRING_AGG` | `STRING_AGG(col, sep ORDER BY ...)` | Concatenate strings |
| `ARRAY_AGG` | `ARRAY_AGG(col ORDER BY ...)` | Collect values into array |
| `JSON_AGG` | `JSON_AGG(col)` | Collect values into JSON array |
| `JSONB_AGG` | `JSONB_AGG(col)` | Collect values into JSONB array |
| `JSON_OBJECT_AGG` | `JSON_OBJECT_AGG(key, val)` | Build JSON object from pairs |
| `BOOL_AND` | `BOOL_AND(col)` | True if all values are true |
| `BOOL_OR` | `BOOL_OR(col)` | True if any value is true |
| `BIT_AND` | `BIT_AND(col)` | Bitwise AND across rows |
| `BIT_OR` | `BIT_OR(col)` | Bitwise OR across rows |

```sql
SELECT
    department,
    COUNT(*)                                    AS total_rows,
    COUNT(email)                                AS with_email,
    COUNT(DISTINCT department)                  AS unique_depts,
    SUM(salary)                                 AS total_sal,
    AVG(salary)                                 AS avg_sal,
    ROUND(AVG(salary), 0)                       AS avg_rounded,
    MIN(salary)                                 AS min_sal,
    MAX(salary)                                 AS max_sal,
    MAX(salary) - MIN(salary)                   AS sal_range,
    STDDEV(salary)                              AS stddev,
    VARIANCE(salary)                            AS variance,
    STRING_AGG(name, ', ' ORDER BY name)        AS names_csv,
    ARRAY_AGG(name ORDER BY salary DESC)        AS names_arr,
    JSON_AGG(name)                              AS names_json,
    JSON_OBJECT_AGG(name, salary)               AS name_sal_map,
    BOOL_AND(is_active)                         AS all_active,
    BOOL_OR(is_active)                          AS any_active
FROM employees
GROUP BY department
ORDER BY total_sal DESC;
```

**Result:**

| department | total_rows | avg_sal | names_csv | all_active |
|------------|-----------|---------|-----------|------------|
| Engineering | 2 | 88000 | Alice Johnson, Charlie Brown | false |
| HR | 1 | 73000 | Diana Prince | true |
| Marketing | 2 | 58000 | Bob Smith, Eve Wilson | true |

---

## 6. Window Functions

> Used with `OVER(...)`. Do NOT collapse rows like aggregates do.

| Function | Description |
|----------|-------------|
| `ROW_NUMBER()` | Unique sequential number — always distinct |
| `RANK()` | Rank with gaps after ties |
| `DENSE_RANK()` | Rank without gaps after ties |
| `NTILE(n)` | Distribute rows into n equal buckets |
| `PERCENT_RANK()` | Relative rank as 0.0 to 1.0 |
| `CUME_DIST()` | Cumulative distribution 0.0 to 1.0 |
| `LAG(col, n, default)` | Value from n rows behind current row |
| `LEAD(col, n, default)` | Value from n rows ahead of current row |
| `FIRST_VALUE(col)` | First value in window frame |
| `LAST_VALUE(col)` | Last value in window frame |
| `NTH_VALUE(col, n)` | Nth value in window frame |
| `SUM() OVER` | Running or partitioned sum |
| `AVG() OVER` | Running or partitioned average |
| `MIN() OVER` | Running or partitioned minimum |
| `MAX() OVER` | Running or partitioned maximum |
| `COUNT() OVER` | Running or partitioned count |

```sql
SELECT
    name, department, salary,

    -- Global ranking
    ROW_NUMBER()   OVER (ORDER BY salary DESC)                          AS row_num,
    RANK()         OVER (ORDER BY salary DESC)                          AS rank,
    DENSE_RANK()   OVER (ORDER BY salary DESC)                          AS dense_rank,
    NTILE(3)       OVER (ORDER BY salary DESC)                          AS tier,
    PERCENT_RANK() OVER (ORDER BY salary)                               AS pct_rank,
    CUME_DIST()    OVER (ORDER BY salary)                               AS cume_dist,

    -- Per-department ranking
    ROW_NUMBER() OVER (PARTITION BY department ORDER BY salary DESC)    AS dept_rank,

    -- Department aggregates without GROUP BY
    AVG(salary)  OVER (PARTITION BY department)                         AS dept_avg,
    MAX(salary)  OVER (PARTITION BY department)                         AS dept_max,
    SUM(salary)  OVER (PARTITION BY department)                         AS dept_total,
    COUNT(*)     OVER (PARTITION BY department)                         AS dept_count,

    -- vs department average
    ROUND(salary - AVG(salary) OVER (PARTITION BY department), 0)      AS vs_avg,

    -- Running total
    SUM(salary)  OVER (ORDER BY id)                                     AS running_total,

    -- Offset functions
    LAG(salary,  1, 0)     OVER (ORDER BY salary)                       AS prev_salary,
    LEAD(salary, 1, 0)     OVER (ORDER BY salary)                       AS next_salary,
    LAG(name,    1, 'N/A') OVER (ORDER BY salary)                       AS prev_name,

    -- Frame boundary functions
    FIRST_VALUE(name) OVER (PARTITION BY department ORDER BY salary DESC) AS top_earner,
    LAST_VALUE(name)  OVER (
        PARTITION BY department ORDER BY salary DESC
        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
    )                                                                    AS lowest_earner,

    -- 3-row moving average
    ROUND(AVG(salary) OVER (
        ORDER BY id ROWS BETWEEN 2 PRECEDING AND CURRENT ROW
    ), 0)                                                                AS moving_avg_3

FROM employees ORDER BY salary DESC;
```

**Result:**

| name | salary | rank | dept_rank | dept_avg | vs_avg | top_earner |
|------|--------|------|-----------|----------|--------|------------|
| Charlie Brown | 91000 | 1 | 1 | 88000 | +3000 | Charlie Brown |
| Alice Johnson | 85000 | 2 | 2 | 88000 | -3000 | Charlie Brown |
| Diana Prince | 73000 | 3 | 1 | 73000 | 0 | Diana Prince |
| Bob Smith | 62000 | 4 | 1 | 58000 | +4000 | Bob Smith |
| Eve Wilson | 54000 | 5 | 2 | 58000 | -4000 | Bob Smith |

### RANK vs DENSE_RANK vs ROW_NUMBER

| salary | ROW_NUMBER | RANK | DENSE_RANK |
|--------|-----------|------|------------|
| 91000 | 1 | 1 | 1 |
| 85000 | 2 | 2 | 2 |
| 85000 | 3 | 2 | 2 |
| 73000 | 4 | 4 | 3 |
| 62000 | 5 | 5 | 4 |

> `RANK` skips numbers after ties. `DENSE_RANK` never skips. `ROW_NUMBER` is always unique.

### Window Frame Clauses

```sql
ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW          -- running total
ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING  -- entire partition
ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING                  -- 3-row sliding window
ROWS BETWEEN 2 PRECEDING AND CURRENT ROW                  -- 3-row moving avg
ROWS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING          -- forward-looking
```

---

## 7. Type Casting Functions

| Function / Operator | Syntax | Description |
|---------------------|--------|-------------|
| `CAST` | `CAST(val AS type)` | SQL-standard type conversion |
| `::` | `val::type` | PostgreSQL shorthand cast |
| `TO_CHAR` | `TO_CHAR(val, fmt)` | Number or date to formatted string |
| `TO_NUMBER` | `TO_NUMBER(str, fmt)` | String to numeric |
| `TO_DATE` | `TO_DATE(str, fmt)` | String to date |
| `TO_TIMESTAMP` | `TO_TIMESTAMP(str, fmt)` | String to timestamp |
| `pg_typeof` | `pg_typeof(val)` | Returns the data type name |

```sql
SELECT
    CAST(salary    AS TEXT)                     AS sal_text,        -- '85000'
    CAST('123.45'  AS NUMERIC)                  AS num,             -- 123.45
    CAST('2024-01-15' AS DATE)                  AS dt,              -- 2024-01-15
    CAST(is_active AS INT)                      AS bool_int,        -- 1

    -- :: shorthand (PostgreSQL specific)
    salary::TEXT                                AS sal_str,
    '2024-01-15'::DATE                          AS date_cast,
    '{"a":1}'::JSONB                            AS json_cast,
    is_active::INT                              AS active_int,

    -- TO_ functions
    TO_CHAR(salary,    'FM$999,999')            AS fmt_sal,         -- $85,000
    TO_CHAR(salary,    'FM999,999.00')          AS fmt_sal2,        -- 85,000.00
    TO_CHAR(joined_at, 'YYYY-MM-DD')            AS fmt_date,        -- 2020-03-15
    TO_NUMBER('1,234.56', '9,999.99')           AS parsed_num,      -- 1234.56
    TO_DATE('15/03/2020', 'DD/MM/YYYY')         AS parsed_date,     -- 2020-03-15

    -- Type inspection
    pg_typeof(salary)                           AS sal_type,        -- numeric
    pg_typeof(joined_at)                        AS date_type,       -- date
    pg_typeof(metadata)                         AS meta_type,       -- jsonb
    pg_typeof(tags)                             AS tags_type        -- text[]

FROM employees WHERE id = 1;
```

---

## 8. NULL Handling Functions

| Function | Syntax | Description |
|----------|--------|-------------|
| `COALESCE` | `COALESCE(v1, v2, ...)` | First non-NULL value |
| `NULLIF` | `NULLIF(a, b)` | NULL if a = b, else a |
| `IS NULL` | `val IS NULL` | TRUE if value is NULL |
| `IS NOT NULL` | `val IS NOT NULL` | TRUE if value is not NULL |
| `IS DISTINCT FROM` | `a IS DISTINCT FROM b` | NULL-safe inequality |
| `IS NOT DISTINCT FROM` | `a IS NOT DISTINCT FROM b` | NULL-safe equality |

```sql
SELECT
    name,
    email,
    COALESCE(email, 'no-email@company.com')                 AS safe_email,
    COALESCE(NULL, NULL, 'fallback')                        AS fallback,
    COALESCE(email,
        LOWER(REPLACE(name,' ','')) || '@company.com')      AS auto_email,
    NULLIF(email, '')                                       AS empty_to_null,
    NULLIF(department, 'HR')                                AS hide_hr,
    100.0 / NULLIF(salary, 0)                               AS safe_div,
    (email IS NULL)                                         AS missing_email,
    (email IS NOT NULL)                                     AS has_email,
    (email IS NOT DISTINCT FROM NULL)                       AS null_eq_safe,
    (email IS DISTINCT FROM 'alice@company.com')            AS not_alice
FROM employees;
```

**Result:**

| name | email | safe_email | missing_email | auto_email |
|------|-------|------------|---------------|------------|
| Alice Johnson | alice@company.com | alice@company.com | false | alice@company.com |
| Eve Wilson | NULL | no-email@company.com | true | evewilson@company.com |

---

## 9. Array Functions

| Function / Operator | Description |
|---------------------|-------------|
| `ARRAY_LENGTH(arr, dim)` | Length along given dimension |
| `CARDINALITY(arr)` | Total number of elements |
| `ARRAY_NDIMS(arr)` | Number of dimensions |
| `ARRAY_UPPER(arr, dim)` | Upper bound of dimension |
| `ARRAY_LOWER(arr, dim)` | Lower bound of dimension |
| `ARRAY_APPEND(arr, val)` | Append element to end |
| `ARRAY_PREPEND(val, arr)` | Prepend element to front |
| `ARRAY_CAT(arr1, arr2)` | Concatenate two arrays |
| `ARRAY_REMOVE(arr, val)` | Remove all occurrences of val |
| `ARRAY_REPLACE(arr, from, to)` | Replace all occurrences |
| `ARRAY_POSITION(arr, val)` | Index of first occurrence (1-based) |
| `ARRAY_POSITIONS(arr, val)` | All indices of value |
| `ARRAY_TO_STRING(arr, sep)` | Join elements into string |
| `STRING_TO_ARRAY(str, sep)` | Split string into array |
| `UNNEST(arr)` | Expand array into one row per element |
| `= ANY(arr)` | Value matches any element |
| `= ALL(arr)` | Value matches all elements |
| `arr @> arr2` | Array contains all of arr2 |
| `arr && arr2` | Arrays share at least one element |

```sql
SELECT
    name, tags,
    ARRAY_LENGTH(tags, 1)               AS tag_count,       -- 2
    CARDINALITY(tags)                   AS cardinality,     -- 2
    tags[1]                             AS first_tag,       -- python
    tags[1:2]                           AS slice_1_2,       -- {python,sql}
    ARRAY_APPEND(tags, 'go')            AS appended,        -- {python,sql,go}
    ARRAY_PREPEND('top', tags)          AS prepended,       -- {top,python,sql}
    ARRAY_CAT(tags, ARRAY['a','b'])     AS merged,
    ARRAY_REMOVE(tags, 'sql')           AS no_sql,          -- {python}
    ARRAY_REPLACE(tags, 'sql', 'SQL')   AS replaced,        -- {python,SQL}
    ARRAY_POSITION(tags, 'sql')         AS sql_idx,         -- 2
    ARRAY_TO_STRING(tags, ', ')         AS csv,             -- python, sql
    STRING_TO_ARRAY('a,b,c', ',')       AS from_str,        -- {a,b,c}
    'sql' = ANY(tags)                   AS has_sql,         -- true
    tags @> ARRAY['sql']                AS contains_sql,    -- true
    tags && ARRAY['java','sql']         AS overlaps         -- true
FROM employees WHERE id = 1;
```

---

## 10. JSON / JSONB Functions

| Operator / Function | Returns | Description |
|---------------------|---------|-------------|
| `-> 'key'` | JSON | Get field as JSON |
| `->> 'key'` | TEXT | Get field as text |
| `#> '{a,b}'` | JSON | Get nested path as JSON |
| `#>> '{a,b}'` | TEXT | Get nested path as text |
| `? 'key'` | BOOL | Key exists |
| `?| arr` | BOOL | Any key exists |
| `?& arr` | BOOL | All keys exist |
| `@> json` | BOOL | Left contains right |
| `<@ json` | BOOL | Left contained in right |
| `|| json` | JSONB | Merge two objects |
| `- 'key'` | JSONB | Delete a key |
| `jsonb_set(doc, path, val)` | JSONB | Set value at path |
| `jsonb_insert(doc, path, val)` | JSONB | Insert value at path |
| `jsonb_object_keys(obj)` | TEXT | Enumerate top-level keys |
| `jsonb_each(obj)` | SETOF rows | Expand to key-value rows |
| `jsonb_each_text(obj)` | SETOF rows | Expand to key-text rows |
| `jsonb_typeof(val)` | TEXT | Type: object, array, string, number, boolean, null |
| `jsonb_build_object(k,v,...)` | JSONB | Build object from k/v pairs |
| `jsonb_build_array(v,...)` | JSONB | Build array from values |
| `row_to_json(row)` | JSON | Convert row to JSON |
| `jsonb_pretty(val)` | TEXT | Pretty-print JSON |
| `jsonb_strip_nulls(val)` | JSONB | Remove null-value keys |

```sql
SELECT
    name, metadata,
    metadata -> 'level'                             AS level_json,   -- "senior"
    metadata ->> 'level'                            AS level_text,   -- senior
    (metadata ->> 'score')::INT                     AS score_int,    -- 92
    metadata ? 'level'                              AS has_level,    -- true
    metadata ?| ARRAY['level','bonus']              AS has_any,      -- true
    metadata ?& ARRAY['level','score']              AS has_all,      -- true
    metadata @> '{"level":"senior"}'::JSONB         AS is_senior,    -- true
    metadata || '{"bonus":5000}'::JSONB             AS with_bonus,
    metadata - 'score'                              AS no_score,
    jsonb_set(metadata, '{score}', '100')           AS score_100,
    jsonb_object_keys(metadata)                     AS keys,
    jsonb_typeof(metadata -> 'score')               AS score_type,   -- number
    jsonb_build_object('name', name, 'sal', salary) AS built,
    jsonb_pretty(metadata)                          AS pretty,
    row_to_json(employees.*)                        AS full_row
FROM employees WHERE id = 1;
```

---

## 11. Regular Expression Functions

| Function / Operator | Description |
|---------------------|-------------|
| `~` | Match regex (case-sensitive) → BOOL |
| `~*` | Match regex (case-insensitive) → BOOL |
| `!~` | Not match regex (case-sensitive) |
| `!~*` | Not match regex (case-insensitive) |
| `LIKE` | Pattern: `%` = any sequence, `_` = one char |
| `ILIKE` | Case-insensitive LIKE |
| `NOT LIKE` | Negated LIKE |
| `SIMILAR TO` | SQL-standard regex matching |
| `REGEXP_MATCH` | Return first match capture groups as array |
| `REGEXP_MATCHES` | Return all matches |
| `REGEXP_REPLACE` | Replace matches |
| `REGEXP_SPLIT_TO_ARRAY` | Split string into array on pattern |
| `REGEXP_SPLIT_TO_TABLE` | Split string into rows on pattern |

```sql
SELECT
    name, email,
    name ~ '^Alice'                                AS starts_alice,  -- true
    name ~* 'JOHNSON'                              AS has_johnson,   -- true
    name !~ '^Bob'                                 AS not_bob,       -- true
    name LIKE  '%Johnson%'                         AS like_j,        -- true
    name ILIKE '%johnson%'                         AS ilike_j,       -- true
    email LIKE '%@company.com'                     AS company_email, -- true
    REGEXP_MATCH(email, '([^@]+)@(.+)')            AS parts,         -- {alice,company.com}
    (REGEXP_MATCH(email, '([^@]+)@(.+)'))[1]       AS email_user,    -- alice
    (REGEXP_MATCH(email, '([^@]+)@(.+)'))[2]       AS email_domain,  -- company.com
    REGEXP_REPLACE(name, '\s+', '_', 'g')         AS underscored,   -- Alice_Johnson
    REGEXP_REPLACE(email, '@.*$', '@hidden')       AS masked,        -- alice@hidden
    REGEXP_REPLACE(name, '[aeiouAEIOU]', '*', 'g') AS no_vowels,     -- *l*c* J*hns*n
    REGEXP_SPLIT_TO_ARRAY(name, '\s+')             AS words_arr      -- {Alice,Johnson}
FROM employees WHERE id = 1;
```

---

## 12. Full Text Search Functions

| Function | Description |
|----------|-------------|
| `to_tsvector(config, text)` | Convert text to tokenised tsvector |
| `to_tsquery(config, text)` | Convert term to tsquery |
| `plainto_tsquery(config, text)` | Plain text to AND query |
| `phraseto_tsquery(config, text)` | Ordered phrase query |
| `websearch_to_tsquery(config, text)` | Google-style query |
| `@@` | tsvector matches tsquery |
| `ts_rank(vector, query)` | Relevance score |
| `ts_rank_cd(vector, query)` | Cover density score |
| `ts_headline(config, text, query)` | Highlighted result snippet |

```sql
SELECT
    name, department,
    to_tsvector('english', name || ' ' || department)       AS doc,
    to_tsvector('english', name || ' ' || department)
      @@ to_tsquery('english', 'engineering')               AS matches,
    ts_rank(
        to_tsvector('english', name || ' ' || department),
        to_tsquery('english', 'engineering')
    )                                                       AS rank,
    ts_headline('english',
        name || ' works in ' || department,
        to_tsquery('engineering'),
        'StartSel=<b>, StopSel=</b>'
    )                                                       AS snippet
FROM employees;
```

### Production Pattern

```sql
ALTER TABLE employees
    ADD COLUMN fts tsvector
    GENERATED ALWAYS AS (
        to_tsvector('english',
            COALESCE(name,'') || ' ' || COALESCE(department,''))
    ) STORED;

CREATE INDEX idx_emp_fts ON employees USING GIN(fts);

SELECT name FROM employees
WHERE fts @@ plainto_tsquery('english', 'senior engineering');
```

---

## 13. Range Functions

| Function / Operator | Description |
|---------------------|-------------|
| `int4range(l, u)` | Integer range |
| `numrange(l, u)` | Numeric range |
| `daterange(l, u)` | Date range |
| `tsrange(l, u)` | Timestamp range |
| `tstzrange(l, u)` | Timestamp with TZ range |
| `@>` | Range/value containment |
| `<@` | Contained in |
| `&&` | Ranges overlap |
| `<<` | Strictly left of |
| `>>` | Strictly right of |
| `+` | Union |
| `*` | Intersection |
| `lower(r)` | Lower bound |
| `upper(r)` | Upper bound |
| `isempty(r)` | True if empty range |
| `lower_inc(r)` | Lower bound inclusive |
| `upper_inc(r)` | Upper bound inclusive |
| `lower_inf(r)` | Lower bound infinite |
| `upper_inf(r)` | Upper bound infinite |

```sql
SELECT
    int4range(1, 10)                         AS int_r,        -- [1,10)
    numrange(50000, 90000)                   AS sal_r,
    daterange('2024-01-01', '2024-12-31')    AS year_r,
    numrange(50000, 90000) @> salary         AS in_range,     -- true
    int4range(1,10) && int4range(5,15)       AS overlaps,     -- true
    int4range(1,5)  << int4range(6,10)       AS left_of,      -- true
    int4range(1,5)  +  int4range(3,10)       AS union_r,      -- [1,10)
    int4range(1,10) *  int4range(5,15)       AS intersect_r,  -- [5,10)
    lower(numrange(50000, 90000))            AS lo,           -- 50000
    upper(numrange(50000, 90000))            AS hi,           -- 90000
    isempty(int4range(5, 5))                 AS is_empty,     -- true
    lower_inf(numrange(NULL, 90000))         AS lo_inf        -- true
FROM employees WHERE id = 1;
```

---

## 14. System & Information Functions

| Function | Description |
|----------|-------------|
| `CURRENT_USER` | Name of current logged-in user |
| `SESSION_USER` | Original session user |
| `CURRENT_DATABASE()` | Current database name |
| `CURRENT_SCHEMA()` | Current schema name |
| `pg_backend_pid()` | PID of current backend process |
| `VERSION()` | PostgreSQL version string |
| `current_setting(name)` | Value of a GUC parameter |
| `pg_relation_size(rel)` | Table/index size in bytes |
| `pg_total_relation_size(rel)` | Total size including indexes |
| `pg_size_pretty(bytes)` | Human-readable size |
| `pg_typeof(val)` | Data type name of a value |
| `ctid` | Physical row location |
| `tableoid` | OID of owning table |
| `sha256(bytea)` | SHA-256 hash |
| `encode(bytea, fmt)` | Encode to base64 or hex |

```sql
SELECT
    id,
    ctid                                            AS phys_loc,
    tableoid::regclass                              AS tbl_name,
    CURRENT_USER                                    AS cur_user,
    CURRENT_DATABASE()                              AS cur_db,
    CURRENT_SCHEMA()                                AS cur_schema,
    pg_backend_pid()                                AS pid,
    VERSION()                                       AS pg_ver,
    current_setting('work_mem')                     AS work_mem,
    pg_size_pretty(pg_relation_size('employees'))   AS tbl_size,
    pg_size_pretty(pg_total_relation_size('employees')) AS total_size,
    pg_typeof(salary)                               AS sal_type,
    MD5(name)                                       AS md5,
    encode(sha256(name::bytea), 'hex')              AS sha256
FROM employees WHERE id = 1;
```

---

## 15. Formatting & Encoding Functions

| Function | Syntax | Description |
|----------|--------|-------------|
| `FORMAT` | `FORMAT(fmt, arg, ...)` | Printf-style string formatting |
| `QUOTE_LITERAL` | `QUOTE_LITERAL(str)` | Wrap in single-quotes, escape internals |
| `QUOTE_IDENT` | `QUOTE_IDENT(str)` | Wrap in double-quotes if needed |
| `QUOTE_NULLABLE` | `QUOTE_NULLABLE(val)` | Quote or return NULL |
| `ENCODE` | `ENCODE(bytea, fmt)` | Encode binary: base64 or hex |
| `DECODE` | `DECODE(str, fmt)` | Decode to binary |
| `CHR` | `CHR(n)` | Character from codepoint |
| `ASCII` | `ASCII(str)` | ASCII code of first char |
| `TRANSLATE` | `TRANSLATE(str, from, to)` | Char-by-char substitution |
| `OVERLAY` | `OVERLAY(str PLACING new FROM pos FOR len)` | Replace portion of string |
| `TO_HEX` | `TO_HEX(n)` | Integer to hexadecimal string |

```sql
SELECT
    name, salary, email,
    FORMAT('%s earns %s in %s',
           name,
           TO_CHAR(salary, 'FM$999,999'),
           department)                          AS summary,
    FORMAT('Row %s: %-20s', id, name)           AS row_fmt,
    QUOTE_LITERAL(name)                         AS ql,       -- 'Alice Johnson'
    QUOTE_IDENT(department)                     AS qi,       -- "Engineering"
    QUOTE_NULLABLE(email)                       AS qn,       -- 'alice@company.com'
    QUOTE_NULLABLE(NULL::TEXT)                  AS qnull,    -- NULL
    ENCODE('hello'::BYTEA, 'base64')            AS b64,      -- aGVsbG8=
    ENCODE('hello'::BYTEA, 'hex')               AS hex,      -- 68656c6c6f
    CHR(65)                                     AS A,        -- A
    CHR(9829)                                   AS heart,    -- heart symbol
    ASCII('Z')                                  AS z_asc,    -- 90
    TRANSLATE(name, 'aeiouAEIOU','**********')  AS no_vowels,
    OVERLAY(name PLACING '***' FROM 3 FOR 3)    AS overlaid,
    TO_HEX(255)                                 AS ff,       -- ff
    TO_HEX(salary::INT)                         AS sal_hex
FROM employees WHERE id = 1;
```

---

## 16. Quick Reference Cheat Sheet

```
╔══════════════════╦══════════════════════════════════════════════════════════╗
║ CATEGORY         ║ KEY FUNCTIONS                                            ║
╠══════════════════╬══════════════════════════════════════════════════════════╣
║ String           ║ UPPER  LOWER  INITCAP  LENGTH  TRIM  LPAD  RPAD         ║
║                  ║ SUBSTRING  LEFT  RIGHT  POSITION  STRPOS  REPLACE        ║
║                  ║ CONCAT  CONCAT_WS  SPLIT_PART  REVERSE  REPEAT          ║
║                  ║ OVERLAY  TRANSLATE  CHR  ASCII  MD5  FORMAT              ║
╠══════════════════╬══════════════════════════════════════════════════════════╣
║ Numeric          ║ ROUND  CEIL  FLOOR  TRUNC  ABS  SIGN  MOD               ║
║                  ║ POWER  SQRT  CBRT  EXP  LN  LOG  RANDOM                 ║
║                  ║ GREATEST  LEAST  PI  SIN  COS  TAN  ATAN2               ║
╠══════════════════╬══════════════════════════════════════════════════════════╣
║ Date / Time      ║ NOW  CURRENT_DATE  CURRENT_TIMESTAMP  LOCALTIMESTAMP     ║
║                  ║ EXTRACT  DATE_PART  DATE_TRUNC  AGE                     ║
║                  ║ TO_CHAR  TO_DATE  TO_TIMESTAMP  MAKE_DATE               ║
╠══════════════════╬══════════════════════════════════════════════════════════╣
║ Conditional      ║ CASE WHEN  COALESCE  NULLIF  GREATEST  LEAST            ║
╠══════════════════╬══════════════════════════════════════════════════════════╣
║ Aggregate        ║ COUNT  SUM  AVG  MIN  MAX  STDDEV  VARIANCE             ║
║                  ║ STRING_AGG  ARRAY_AGG  JSON_AGG  JSON_OBJECT_AGG        ║
║                  ║ BOOL_AND  BOOL_OR  CORR  REGR_SLOPE                     ║
╠══════════════════╬══════════════════════════════════════════════════════════╣
║ Window           ║ ROW_NUMBER  RANK  DENSE_RANK  NTILE                     ║
║                  ║ PERCENT_RANK  CUME_DIST                                 ║
║                  ║ LAG  LEAD  FIRST_VALUE  LAST_VALUE  NTH_VALUE           ║
║                  ║ SUM/AVG/MIN/MAX/COUNT  OVER (PARTITION BY  ORDER BY)    ║
╠══════════════════╬══════════════════════════════════════════════════════════╣
║ Casting          ║ CAST  ::  TO_CHAR  TO_NUMBER  TO_DATE  pg_typeof        ║
╠══════════════════╬══════════════════════════════════════════════════════════╣
║ NULL Handling    ║ COALESCE  NULLIF  IS NULL  IS NOT DISTINCT FROM         ║
╠══════════════════╬══════════════════════════════════════════════════════════╣
║ Array            ║ ARRAY_LENGTH  CARDINALITY  ARRAY_APPEND  ARRAY_REMOVE   ║
║                  ║ ARRAY_TO_STRING  STRING_TO_ARRAY  UNNEST  ANY  ALL      ║
╠══════════════════╬══════════════════════════════════════════════════════════╣
║ JSON / JSONB     ║ ->  ->>  #>  #>>  ?  ?|  ?&  @>  ||                    ║
║                  ║ jsonb_set  jsonb_build_object  row_to_json              ║
║                  ║ jsonb_each  jsonb_object_keys  jsonb_pretty             ║
╠══════════════════╬══════════════════════════════════════════════════════════╣
║ Regex            ║ ~  ~*  !~  !~*  LIKE  ILIKE  SIMILAR TO               ║
║                  ║ REGEXP_MATCH  REGEXP_REPLACE  REGEXP_SPLIT_TO_ARRAY     ║
╠══════════════════╬══════════════════════════════════════════════════════════╣
║ Full Text        ║ to_tsvector  to_tsquery  plainto_tsquery               ║
║                  ║ @@  ts_rank  ts_rank_cd  ts_headline                   ║
╠══════════════════╬══════════════════════════════════════════════════════════╣
║ Range            ║ int4range  numrange  daterange  tsrange                 ║
║                  ║ @>  <@  &&  <<  >>  +  *  lower  upper  isempty        ║
╠══════════════════╬══════════════════════════════════════════════════════════╣
║ System           ║ CURRENT_USER  CURRENT_DATABASE  pg_backend_pid         ║
║                  ║ pg_relation_size  pg_size_pretty  VERSION  sha256       ║
╠══════════════════╬══════════════════════════════════════════════════════════╣
║ Formatting       ║ FORMAT  QUOTE_LITERAL  QUOTE_IDENT  QUOTE_NULLABLE      ║
║                  ║ ENCODE  DECODE  CHR  ASCII  TRANSLATE  TO_HEX          ║
╚══════════════════╩══════════════════════════════════════════════════════════╝
```

---

## Further Reading

- [PostgreSQL Docs — String Functions](https://www.postgresql.org/docs/current/functions-string.html)
- [PostgreSQL Docs — Math Functions](https://www.postgresql.org/docs/current/functions-math.html)
- [PostgreSQL Docs — Date/Time Functions](https://www.postgresql.org/docs/current/functions-datetime.html)
- [PostgreSQL Docs — Conditional Expressions](https://www.postgresql.org/docs/current/functions-conditional.html)
- [PostgreSQL Docs — Aggregate Functions](https://www.postgresql.org/docs/current/functions-aggregate.html)
- [PostgreSQL Docs — Window Functions](https://www.postgresql.org/docs/current/functions-window.html)
- [PostgreSQL Docs — JSON Functions](https://www.postgresql.org/docs/current/functions-json.html)
- [PostgreSQL Docs — Full Text Search](https://www.postgresql.org/docs/current/textsearch.html)
- [PostgreSQL Docs — Range Functions](https://www.postgresql.org/docs/current/functions-range.html)

---

*Generated with love for PostgreSQL engineers.*
