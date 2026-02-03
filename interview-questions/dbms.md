# 🚀 DBMS Interview Questions:

## 🗄️ Database Internals: Under the Hood

A deep-dive exploration of how a Database Management System (DBMS) processes, stores, and retrieves data at the hardware and software levels.

##### 🚀 The Query Pipeline
When a query is submitted, it travels through the following internal stages:

1.  **Parsing & Translation:**
    *   Checks for syntax and semantic correctness.
    *   Generates an **Abstract Syntax Tree (AST)**.
2.  **Optimization:**
    *   Analyzes multiple execution paths.
    *   Uses **Cost-Based Optimization (CBO)** to pick the fastest route.
3.  **Execution:**
    *   The Engine executes the plan and interacts with the **Storage Manager**.

##### 🏗️ Storage Architecture
This project demonstrates how data moves from high-level SQL to low-level bits:

*   **Logical Layer:** Tables, Rows, and Columns (Relational Model).
*   **Physical Layer:**
    *   **Pages:** Data is stored in fixed-size blocks (usually 8KB).
    *   **Buffer Pool:** Frequently used pages are cached in **RAM** to avoid disk I/O.
    *   **Heap Files:** Unordered storage for raw data records.

##### 🔒 Reliability (ACID)
To ensure data integrity, the system implements:
*   **WAL (Write-Ahead Logging):** Changes are written to a log *before* the data file to survive crashes.
*   **MVCC (Multi-Version Concurrency Control):** Allows simultaneous reads and writes without blocking.

