# 🏛️ → ☁️ Mainframe to AWS Java Modernization

> A comprehensive guide to migrating IBM z/OS mainframe applications (COBOL, PL/I, JCL, VSAM, DB2, CICS, MQ) to a modern AWS-native Java microservices architecture.

---

## 📚 Table of Contents

1. [Modernization Overview](#1-modernization-overview)
2. [Migration Strategies (6 Rs)](#2-migration-strategies-6-rs)
3. [Mainframe Components → AWS Equivalents](#3-mainframe-components--aws-equivalents)
4. [Architecture Patterns](#4-architecture-patterns)
5. [COBOL to Java Translation Patterns](#5-cobol-to-java-translation-patterns)
6. [Data Migration: VSAM & DB2 → AWS](#6-data-migration-vsam--db2--aws)
7. [CICS Transaction → REST API](#7-cics-transaction--rest-api)
8. [JCL Batch → AWS Batch / Spring Batch](#8-jcl-batch--aws-batch--spring-batch)
9. [MQ Series → Amazon SQS / SNS / EventBridge](#9-mq-series--amazon-sqs--sns--eventbridge)
10. [Security & IAM Mapping](#10-security--iam-mapping)
11. [Testing Strategy](#11-testing-strategy)
12. [CI/CD Pipeline on AWS](#12-cicd-pipeline-on-aws)
13. [Observability & Monitoring](#13-observability--monitoring)
14. [Step-by-Step Migration Roadmap](#14-step-by-step-migration-roadmap)
15. [Cost Optimization](#15-cost-optimization)
16. [Common Pitfalls & How to Avoid Them](#16-common-pitfalls--how-to-avoid-them)

---

## 1. Modernization Overview

### Why Modernize?

| Mainframe Pain Point | AWS Java Solution |
|---|---|
| High MIPS licensing costs ($$$) | Pay-as-you-go EC2 / Lambda / ECS |
| COBOL/PL/I skill shortage | Java — massive talent pool |
| Monolithic — change one line, redeploy all | Microservices — independent deploys |
| Batch windows block business hours | Cloud-native async processing, no windows |
| Weeks to provision capacity | Auto-scaling in seconds |
| Hard-coded business rules in COBOL | Externalized rules (Drools, AWS Step Functions) |
| No API exposure | REST/GraphQL APIs via API Gateway |
| Limited observability | CloudWatch, X-Ray, OpenTelemetry |

### Modernization Spectrum

```
REHOST         REPLATFORM      REFACTOR         REARCHITECT
(Lift & Shift)  (Some changes)  (Major changes)   (Greenfield)
    │                │               │                  │
  COBOL on        COBOL on       COBOL → Java       Java Cloud-
  AWS EC2       AWS Mainframe    Monolith on        Native Micro-
  (Blu Age)      Modernization   AWS ECS            services
```

---

## 2. Migration Strategies (6 Rs)

### R1 — Rehost (Lift & Shift)
Move COBOL as-is to AWS using emulation tools.
- **Tools:** Micro Focus Enterprise Server, LzLabs SDM, Raincode
- **When:** Quick win, no time to refactor, risk-averse teams
- **Downside:** Still COBOL, still hard to maintain

### R2 — Replatform
Automated COBOL-to-Java transpilation.
- **Tools:** AWS Blu Age, Micro Focus COBOL-to-Java, TopSecret to Spring Security
- **When:** Large COBOL estate, limited Java expertise
- **Output:** Generated Java code (not always clean, needs review)

### R3 — Refactor
Manually rewrite critical programs in Java, retaining business logic.
- **Tools:** Java + Spring Boot + AWS managed services
- **When:** Programs are well-understood, bounded in scope
- **Best for:** CICS online transactions → REST microservices

### R4 — Repurchase
Replace with a SaaS solution.
- **Examples:** Replace custom COBOL payroll with Workday, replace homegrown reporting with Tableau + Redshift

### R5 — Retire
Decommission programs no longer in use.
- Identify dead code with mainframe profilers (IBM OMEGAMON, CA Detector)

### R6 — Retain
Keep on mainframe temporarily (Core banking, regulatory systems not yet cleared).
- Use **Strangler Fig** pattern to incrementally intercept traffic

---

## 3. Mainframe Components → AWS Equivalents

```
MAINFRAME LAYER          AWS / JAVA EQUIVALENT
─────────────────────────────────────────────────────────────────

RUNTIME
  z/OS LPAR              → EC2, ECS (Fargate), EKS, Lambda
  CICS Region            → Spring Boot Microservice on ECS/EKS
  IMS TM                 → API Gateway + Lambda / ECS

COMPUTE / BATCH
  JCL Job                → AWS Batch Job Definition
  JES2/JES3 Queue        → AWS Batch Job Queue
  PROC (Cataloged)       → AWS Batch Job Definition + Step Functions
  Job Scheduler (TWS)    → Amazon EventBridge Scheduler / Step Functions

STORAGE
  VSAM KSDS              → Amazon DynamoDB (key-value)
  VSAM ESDS              → Amazon Kinesis / S3 (sequential)
  VSAM RRDS              → Amazon RDS (relational records)
  GDG (Gen Data Groups)  → Amazon S3 with versioning
  PDS / PDSE             → Amazon S3 + CodeArtifact
  Flat Files (PS)        → Amazon S3 + Glue / Lambda

DATABASE
  DB2 for z/OS           → Amazon Aurora PostgreSQL / RDS
  IMS DB (Hierarchical)  → Amazon DynamoDB (JSON hierarchy)
  Adabas                 → Amazon Aurora

MESSAGING
  IBM MQ (MQSeries)      → Amazon SQS, SNS, Amazon MQ
  DataPower ESB          → Amazon API Gateway + EventBridge
  MQ Topics              → Amazon SNS Topics

SECURITY
  RACF                   → AWS IAM + Cognito + Secrets Manager
  ACF2 / TopSecret       → AWS IAM Policies
  LDAP / LDAP Groups     → AWS IAM Identity Center (SSO)
  SSL Certificates       → AWS Certificate Manager (ACM)

MONITORING
  OMEGAMON               → Amazon CloudWatch + X-Ray
  SMF Records            → CloudWatch Logs + Athena
  RMF                    → CloudWatch Container Insights

NETWORKING
  VTAM / SNA             → AWS VPC + Direct Connect / VPN
  TCPIP Stack            → AWS VPC, ALB, NLB
  Sysplex Distributor    → Application Load Balancer (ALB)

INTEGRATION
  Connect:Direct (NDM)   → AWS DataSync / S3 Transfer Acceleration
  FTP/SFTP               → AWS Transfer Family
  APPC/LU6.2             → REST/gRPC on API Gateway
```

---

## 4. Architecture Patterns

### Pattern 1: Strangler Fig (Recommended for Large Estates)

```
                         ┌─────────────────────────────┐
                         │        API Gateway           │
                         └──────────┬──────────────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    │         Route Rules            │
                    │  /new-feature → Microservice  │
                    │  /legacy/*    → Mainframe      │
                    └───────────────┬───────────────┘
                                    │
              ┌─────────────────────┴───────────────────────┐
              │                                             │
   ┌──────────▼──────────┐                    ┌────────────▼────────────┐
   │  Java Microservices │                    │  z/OS Mainframe         │
   │  (ECS / EKS)        │                    │  (CICS / IMS)           │
   │  ─ New features     │                    │  ─ Legacy features      │
   │  ─ Migrated CICS    │                    │  ─ Core banking         │
   └─────────────────────┘                    └─────────────────────────┘
              │                                             │
              └────────────────┐   ┌────────────────────────┘
                               │   │
                    ┌──────────▼───▼──────────┐
                    │  Shared Aurora / RDS     │
                    │  (migrated from DB2)     │
                    └──────────────────────────┘
```

### Pattern 2: Event-Driven Decoupling

```
Mainframe VSAM Write → CDC Tool (Qlik/Attunity) → Amazon Kinesis → Lambda → DynamoDB
                                                              ↓
                                                       EventBridge → SQS → Microservice
```

### Pattern 3: Target AWS Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           AWS Cloud                                             │
│                                                                                 │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │  Public Subnet                                                          │   │
│  │   ┌──────────────┐     ┌───────────────┐     ┌────────────────────┐    │   │
│  │   │  CloudFront  │────▶│ API Gateway   │────▶│  ALB               │    │   │
│  │   └──────────────┘     └───────────────┘     └────────┬───────────┘    │   │
│  └─────────────────────────────────────────────────────────│───────────────┘   │
│                                                            │                   │
│  ┌─────────────────────────────────────────────────────────│───────────────┐   │
│  │  Private Subnet (ECS / EKS Cluster)                     │               │   │
│  │                                                         ▼               │   │
│  │  ┌───────────────┐  ┌───────────────┐  ┌───────────────────────────┐   │   │
│  │  │ Account Svc   │  │ Payment Svc   │  │  Batch Processing Svc     │   │   │
│  │  │ (Spring Boot) │  │ (Spring Boot) │  │  (Spring Batch on Fargate)│   │   │
│  │  └───────┬───────┘  └───────┬───────┘  └───────────────────────────┘   │   │
│  └──────────│─────────────────-│─────────────────────────────────────────-┘   │
│             │                  │                                               │
│  ┌──────────│──────────────────│──────────────────────────────────────────┐   │
│  │  Data Layer                 │                                          │   │
│  │  ┌────────▼──────┐  ┌───────▼──────┐  ┌───────────┐  ┌────────────┐  │   │
│  │  │ Aurora PG     │  │  DynamoDB    │  │    S3     │  │ElastiCache │  │   │
│  │  │ (from DB2)    │  │ (from VSAM)  │  │  (Files)  │  │  (Redis)   │  │   │
│  │  └───────────────┘  └──────────────┘  └───────────┘  └────────────┘  │   │
│  └──────────────────────────────────────────────────────────────────────-┘   │
│                                                                               │
│  ┌──────────────────────────────────────────────────────────────────────-┐   │
│  │  Messaging & Events                                                    │   │
│  │   Amazon SQS ──── Amazon SNS ──── EventBridge ──── Amazon MQ          │   │
│  └────────────────────────────────────────────────────────────────────── ┘   │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. COBOL to Java Translation Patterns

### 5.1 Data Division → Java Domain Model

**COBOL:**
```cobol
01 CUSTOMER-RECORD.
   05 CUST-ID          PIC 9(8).
   05 CUST-NAME        PIC X(30).
   05 CUST-DOB         PIC 9(8).
   05 CUST-BALANCE     PIC S9(13)V99 COMP-3.
   05 CUST-STATUS      PIC X(1).
      88 ACTIVE        VALUE 'A'.
      88 INACTIVE      VALUE 'I'.
      88 SUSPENDED     VALUE 'S'.
```

**Java:**
```java
@Entity
@Table(name = "customers")
public class Customer {

    @Id
    @Column(name = "cust_id", length = 8)
    private Long customerId;

    @Column(name = "cust_name", length = 30, nullable = false)
    private String customerName;

    @Column(name = "cust_dob")
    private LocalDate dateOfBirth;

    @Column(name = "cust_balance", precision = 15, scale = 2)
    private BigDecimal balance; // COMP-3 packed decimal → BigDecimal

    @Enumerated(EnumType.STRING)
    @Column(name = "cust_status", length = 1)
    private CustomerStatus status;

    public enum CustomerStatus {
        A,  // ACTIVE
        I,  // INACTIVE
        S   // SUSPENDED
    }

    public boolean isActive() {
        return CustomerStatus.A.equals(this.status);
    }
}
```

### 5.2 PERFORM / PARAGRAPH → Service Methods

**COBOL:**
```cobol
PROCEDURE DIVISION.
    PERFORM VALIDATE-CUSTOMER
    PERFORM CALCULATE-INTEREST
    PERFORM UPDATE-BALANCE
    STOP RUN.

VALIDATE-CUSTOMER.
    IF CUST-ID = ZEROS
        MOVE 'INVALID-ID' TO WS-ERROR-CODE
        PERFORM HANDLE-ERROR
    END-IF.

CALCULATE-INTEREST.
    COMPUTE WS-INTEREST = CUST-BALANCE * INTEREST-RATE / 100.

HANDLE-ERROR.
    DISPLAY 'ERROR: ' WS-ERROR-CODE.
    MOVE -1 TO RETURN-CODE.
```

**Java:**
```java
@Service
@Slf4j
public class CustomerInterestService {

    private final CustomerRepository customerRepository;
    private final InterestRateService interestRateService;

    @Transactional
    public void processInterest(Long customerId) {
        Customer customer = validateCustomer(customerId);
        BigDecimal interest = calculateInterest(customer);
        updateBalance(customer, interest);
    }

    private Customer validateCustomer(Long customerId) {
        if (customerId == null || customerId == 0) {
            throw new InvalidCustomerIdException("INVALID-ID: Customer ID cannot be zero");
        }
        return customerRepository.findById(customerId)
            .orElseThrow(() -> new CustomerNotFoundException(customerId));
    }

    private BigDecimal calculateInterest(Customer customer) {
        BigDecimal rate = interestRateService.getCurrentRate();
        return customer.getBalance()
            .multiply(rate)
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private void updateBalance(Customer customer, BigDecimal interest) {
        customer.setBalance(customer.getBalance().add(interest));
        customerRepository.save(customer);
        log.info("Updated balance for customer {} with interest {}", 
                 customer.getCustomerId(), interest);
    }
}
```

### 5.3 COBOL File Processing → Spring Batch

**COBOL:**
```cobol
READ-TRANSACTION-FILE.
    READ TRANS-FILE INTO WS-TRANS-RECORD
        AT END MOVE 'Y' TO WS-EOF-FLAG
    END-READ.

PROCESS-TRANSACTION.
    EVALUATE WS-TRANS-TYPE
        WHEN 'CR' PERFORM CREDIT-ACCOUNT
        WHEN 'DR' PERFORM DEBIT-ACCOUNT
        WHEN OTHER PERFORM REJECT-TRANSACTION
    END-EVALUATE.
```

**Java (Spring Batch):**
```java
@Configuration
@EnableBatchProcessing
public class TransactionBatchConfig {

    @Bean
    public Job transactionProcessingJob(JobBuilderFactory jobs, Step processStep) {
        return jobs.get("transactionProcessingJob")
            .incrementer(new RunIdIncrementer())
            .flow(processStep)
            .end()
            .build();
    }

    @Bean
    public Step processTransactionsStep(StepBuilderFactory steps) {
        return steps.get("processTransactionsStep")
            .<TransactionRecord, ProcessedTransaction>chunk(500) // batch of 500
            .reader(transactionFileReader())
            .processor(transactionProcessor())
            .writer(transactionWriter())
            .faultTolerant()
            .skipLimit(100)
            .skip(InvalidTransactionException.class)
            .listener(new TransactionStepListener())
            .build();
    }

    @Bean
    @StepScope
    public FlatFileItemReader<TransactionRecord> transactionFileReader() {
        return new FlatFileItemReaderBuilder<TransactionRecord>()
            .name("transactionReader")
            .resource(new S3Resource("s3://my-bucket/transactions/input.dat"))
            .fixedLength()
            .columns(new Range[]{new Range(1,8), new Range(9,10), new Range(11,22)})
            .names("accountId", "transType", "amount")
            .targetType(TransactionRecord.class)
            .build();
    }

    @Bean
    public ItemProcessor<TransactionRecord, ProcessedTransaction> transactionProcessor() {
        return record -> switch (record.getTransType()) {
            case "CR" -> processCreditTransaction(record);
            case "DR" -> processDebitTransaction(record);
            default  -> throw new InvalidTransactionException(
                            "Unknown type: " + record.getTransType());
        };
    }
}
```

### 5.4 Packed Decimal (COMP-3) → BigDecimal

**COBOL packed decimal handling in Java:**
```java
public class PackedDecimalConverter {

    /**
     * Converts COBOL COMP-3 packed decimal bytes to BigDecimal.
     * Each byte holds 2 digits; last nibble is sign (C=positive, D=negative).
     */
    public static BigDecimal fromCobolPackedDecimal(byte[] packed, int scale) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < packed.length - 1; i++) {
            digits.append((packed[i] >> 4) & 0x0F);
            digits.append(packed[i] & 0x0F);
        }
        // Last byte: high nibble = last digit, low nibble = sign
        digits.append((packed[packed.length - 1] >> 4) & 0x0F);
        int signNibble = packed[packed.length - 1] & 0x0F;
        boolean negative = (signNibble == 0x0D); // D = negative

        BigDecimal result = new BigDecimal(digits.toString())
            .movePointLeft(scale)
            .setScale(scale, RoundingMode.UNNECESSARY);

        return negative ? result.negate() : result;
    }
}
```

### 5.5 COPY Book → Shared DTOs / Libraries

**COBOL COPY book:**
```cobol
* CUSTCOPY.CPY
01 CUSTOMER-COPY.
   05 COPY-CUST-ID    PIC 9(8).
   05 COPY-CUST-NAME  PIC X(30).
```

**Java shared library (Maven artifact):**
```java
// In shared-contracts module, published to CodeArtifact
public record CustomerDto(
    @NotNull Long customerId,
    @NotBlank @Size(max = 30) String customerName
) {}

// Used in multiple microservices via Maven dependency:
// <dependency>
//   <groupId>com.mybank</groupId>
//   <artifactId>shared-contracts</artifactId>
//   <version>1.0.0</version>
// </dependency>
```

---

## 6. Data Migration: VSAM & DB2 → AWS

### 6.1 DB2 z/OS → Amazon Aurora PostgreSQL

**Schema migration with AWS Schema Conversion Tool (SCT):**

```sql
-- DB2 z/OS source
CREATE TABLE ACCOUNT (
    ACCT_NBR     CHAR(10)     NOT NULL,
    ACCT_BAL     DECIMAL(15,2) NOT NULL,
    OPEN_DATE    DATE          NOT NULL,
    STATUS_CD    CHAR(1)       NOT NULL,
    PRIMARY KEY (ACCT_NBR)
) IN DATABASE BANKDB;

-- Aurora PostgreSQL target (SCT output + adjustments)
CREATE TABLE account (
    acct_nbr     CHAR(10)       NOT NULL,
    acct_bal     NUMERIC(15,2)  NOT NULL,
    open_date    DATE           NOT NULL,
    status_cd    CHAR(1)        NOT NULL,
    created_at   TIMESTAMPTZ    DEFAULT NOW(),  -- added for auditability
    updated_at   TIMESTAMPTZ    DEFAULT NOW(),
    PRIMARY KEY (acct_nbr)
);

-- DB2 REORG equivalent
VACUUM ANALYZE account;
```

**Java Repository for Aurora:**
```java
@Repository
public interface AccountRepository extends JpaRepository<Account, String> {

    // DB2: SELECT * FROM ACCOUNT WHERE STATUS_CD = 'A' AND ACCT_BAL > ?
    List<Account> findByStatusCdAndAcctBalGreaterThan(String statusCd, BigDecimal minBalance);

    @Query("""
        SELECT a FROM Account a
        WHERE a.openDate BETWEEN :startDate AND :endDate
        ORDER BY a.acctBal DESC
        """)
    Page<Account> findByOpenDateBetween(
        @Param("startDate") LocalDate start,
        @Param("endDate") LocalDate end,
        Pageable pageable
    );
}
```

### 6.2 VSAM KSDS → DynamoDB

**VSAM Key-Sequenced Data Set → DynamoDB:**
```java
@DynamoDbBean
public class VsamRecord {

    private String primaryKey;    // VSAM key → DynamoDB PK
    private String sortKey;       // optional composite key
    private String data;
    private Instant lastModified;

    @DynamoDbPartitionKey
    public String getPrimaryKey() { return primaryKey; }

    @DynamoDbSortKey
    public String getSortKey() { return sortKey; }
}

@Repository
public class VsamMigrationRepository {

    private final DynamoDbTable<VsamRecord> table;

    public void migrateFromVsam(List<CobolVsamRecord> vsamRecords) {
        // Batch write — 25 items max per DynamoDB batch
        List<List<CobolVsamRecord>> batches = Lists.partition(vsamRecords, 25);
        batches.forEach(batch -> {
            WriteBatch.Builder<VsamRecord> builder = WriteBatch.builder(VsamRecord.class)
                .mappedTableResource(table);
            batch.stream()
                 .map(this::toVsamRecord)
                 .forEach(builder::addPutItem);
            dynamoDbEnhancedClient.batchWriteItem(r -> r.writeBatches(builder.build()));
        });
    }
}
```

### 6.3 GDG (Generation Data Groups) → S3 with Versioning

```java
@Service
public class GdgMigrationService {

    private final S3Client s3Client;
    private static final String BUCKET = "my-bank-gdg-migration";

    /**
     * Emulates GDG(0) = current, GDG(-1) = previous gen using S3 versioning
     */
    public void writeGdgGeneration(String gdgBaseName, byte[] data) {
        String key = "gdg/" + gdgBaseName + "/current";
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(BUCKET)
                .key(key)
                .metadata(Map.of(
                    "gdg-base-name", gdgBaseName,
                    "generation-timestamp", Instant.now().toString()
                ))
                .build(),
            RequestBody.fromBytes(data)
        );
    }

    public byte[] readGdgGeneration(String gdgBaseName, int generation) {
        // generation 0 = latest, -1 = previous
        String key = "gdg/" + gdgBaseName + "/current";
        if (generation == 0) {
            return s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(BUCKET).key(key).build()
            ).asByteArray();
        }
        // For GDG(-n), list versions and pick nth
        ListObjectVersionsResponse versions = s3Client.listObjectVersions(
            ListObjectVersionsRequest.builder().bucket(BUCKET).prefix(key).build()
        );
        ObjectVersion targetVersion = versions.versions()
            .get(Math.abs(generation)); // -1 → index 1 (second latest)
        return s3Client.getObjectAsBytes(
            GetObjectRequest.builder()
                .bucket(BUCKET).key(key)
                .versionId(targetVersion.versionId())
                .build()
        ).asByteArray();
    }
}
```

---

## 7. CICS Transaction → REST API

### CICS Map (BMS) → Spring Boot REST Controller

**Original CICS:**
```
TRANSACTION: CACQ
MAP: CACQMAP
PROGRAM: CACQPROG
FUNCTION: Customer Account Query
```

**Java REST equivalent:**
```java
@RestController
@RequestMapping("/api/v1/accounts")
@Validated
@Slf4j
public class AccountQueryController {

    private final AccountQueryService accountQueryService;

    /**
     * Replaces CICS transaction CACQ
     * Previously: 3270 terminal → CICS CACQPROG → DB2 → response map
     * Now: REST client → API Gateway → ECS → Aurora → JSON response
     */
    @GetMapping("/{accountNumber}")
    public ResponseEntity<AccountQueryResponse> queryAccount(
            @PathVariable @Pattern(regexp = "\\d{10}") String accountNumber,
            @RequestHeader("X-Channel-Id") String channelId) {

        log.info("Account query: accountNumber={}, channel={}", accountNumber, channelId);

        AccountQueryResponse response = accountQueryService.queryAccount(accountNumber);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{accountNumber}/transactions")
    public ResponseEntity<Page<TransactionResponse>> getTransactions(
            @PathVariable String accountNumber,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            Pageable pageable) {

        Page<TransactionResponse> transactions =
            accountQueryService.getTransactions(accountNumber, fromDate, toDate, pageable);
        return ResponseEntity.ok(transactions);
    }
}

// DTOs replacing CICS map fields
public record AccountQueryResponse(
    String accountNumber,
    String accountHolderName,
    BigDecimal currentBalance,
    BigDecimal availableBalance,
    String accountStatus,
    LocalDate openDate,
    String branchCode
) {}
```

### CICS COMMAREA → Request/Response DTOs

```java
/**
 * COBOL COMMAREA (32KB max) is replaced by typed Java DTOs.
 * Each field in the COMMAREA maps to a DTO field.
 */
public class CommAreaMapper {

    // COMMAREA layout for CACQPROG
    // 01 DFHCOMMAREA.
    //    05 CA-FUNC-CODE     PIC X(4).
    //    05 CA-ACCT-NBR      PIC 9(10).
    //    05 CA-RESP-CODE     PIC 9(4).
    //    05 CA-BALANCE       PIC S9(13)V99 COMP-3.

    public AccountQueryRequest fromCommArea(byte[] commArea) {
        return AccountQueryRequest.builder()
            .functionCode(new String(commArea, 0, 4).trim())
            .accountNumber(new String(commArea, 4, 10).trim())
            .build();
    }

    public byte[] toCommArea(AccountQueryResponse response, String responseCode) {
        byte[] commArea = new byte[32768];
        // ... pack response into commArea for legacy callers
        return commArea;
    }
}
```

---

## 8. JCL Batch → AWS Batch / Spring Batch

### JCL Job → AWS Batch + Spring Batch

**Original JCL:**
```jcl
//MONTHRPT JOB (ACCT),'MONTHLY REPORT',CLASS=A,MSGCLASS=X
//*
//STEP01  EXEC PGM=EXTRACT,REGION=2048M
//INFILE  DD DSN=PROD.TRANS.MONTHLY,DISP=SHR
//OUTFILE DD DSN=PROD.REPORT.EXTRACT,DISP=(NEW,CATLG)
//*
//STEP02  EXEC PGM=SORTRPT,COND=(0,NE,STEP01)
//SYSIN   DD *
  SORT FIELDS=(1,8,CH,A,9,10,CH,A)
/*
//*
//STEP03  EXEC PGM=GENRPT,COND=(0,NE,STEP02)
//RPTFILE DD SYSOUT=A
```

**AWS Step Functions + Spring Batch equivalent:**
```java
// Step Functions State Machine (CDK)
StateMachine monthlyReportStateMachine = StateMachine.Builder.create(this, "MonthlyReport")
    .stateMachineName("monthly-report-pipeline")
    .definition(
        BatchSubmitJob.Builder.create("ExtractTransactions")
            .jobName("extract-transactions")
            .jobDefinitionArn(extractJobDef.getJobDefinitionArn())
            .jobQueueArn(batchQueue.getJobQueueArn())
            .resultPath("$.extractResult")
            .build()
        .next(
            BatchSubmitJob.Builder.create("SortAndProcess")
                .jobName("sort-and-process")
                .jobDefinitionArn(sortJobDef.getJobDefinitionArn())
                .jobQueueArn(batchQueue.getJobQueueArn())
                .build()
        )
        .next(
            BatchSubmitJob.Builder.create("GenerateReport")
                .jobName("generate-report")
                .jobDefinitionArn(reportJobDef.getJobDefinitionArn())
                .jobQueueArn(batchQueue.getJobQueueArn())
                .build()
        )
    )
    .build();

// Spring Batch Job replacing STEP01 (EXTRACT)
@Configuration
public class ExtractBatchJobConfig {

    @Bean
    public Job monthlyExtractJob() {
        return jobBuilderFactory.get("monthlyExtractJob")
            .start(extractStep())
            .next(sortStep())
            .next(reportStep())
            .build();
    }

    @Bean
    @StepScope
    public JdbcCursorItemReader<Transaction> extractReader(
            @Value("#{jobParameters['reportMonth']}") String reportMonth) {
        return new JdbcCursorItemReaderBuilder<Transaction>()
            .name("transactionExtractReader")
            .dataSource(dataSource)
            .sql("""
                SELECT acct_nbr, trans_date, trans_type, amount
                FROM transactions
                WHERE DATE_FORMAT(trans_date, '%Y-%m') = ?
                ORDER BY acct_nbr, trans_date
                """)
            .preparedStatementSetter(ps -> ps.setString(1, reportMonth))
            .rowMapper(new TransactionRowMapper())
            .fetchSize(1000)
            .build();
    }
}
```

### JCL SORT → Java Stream / AWS Glue

```java
// JCL: SORT FIELDS=(1,8,CH,A,9,10,CH,A) → Java Comparator
List<Transaction> sorted = transactions.stream()
    .sorted(Comparator
        .comparing(Transaction::getAccountNumber)      // field 1-8 ascending
        .thenComparing(Transaction::getTransactionDate) // field 9-18 ascending
    )
    .collect(Collectors.toList());

// For very large sorts (mainframe-scale): AWS Glue PySpark
// glue_job.py
// df = spark.read.csv("s3://bucket/input/")
// df_sorted = df.orderBy(["account_number", "trans_date"])
// df_sorted.write.csv("s3://bucket/output/sorted/")
```

---

## 9. MQ Series → Amazon SQS / SNS / EventBridge

### IBM MQ Producer → SQS Producer

**COBOL MQ (via Java bridge):**
```java
// Legacy IBM MQ (keep temporarily during migration)
@Service
public class LegacyMqProducer {
    @Autowired
    private JmsTemplate jmsTemplate;  // IBM MQ JMS

    public void sendMessage(String queueName, String payload) {
        jmsTemplate.convertAndSend(queueName, payload);
    }
}

// New Amazon SQS Producer
@Service
@Slf4j
public class SqsMessageProducer {

    private final SqsAsyncClient sqsClient;

    @Value("${aws.sqs.transaction-queue-url}")
    private String transactionQueueUrl;

    public CompletableFuture<SendMessageResponse> sendTransaction(
            TransactionEvent event, String messageGroupId) {

        String payload = objectMapper.writeValueAsString(event);

        return sqsClient.sendMessage(SendMessageRequest.builder()
            .queueUrl(transactionQueueUrl)
            .messageBody(payload)
            .messageGroupId(messageGroupId)          // for FIFO queue ordering
            .messageDeduplicationId(event.getEventId()) // idempotency
            .messageAttributes(Map.of(
                "eventType", MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(event.getClass().getSimpleName())
                    .build()
            ))
            .build()
        );
    }
}

// SQS Consumer (replaces MQ GET in COBOL)
@Component
@Slf4j
public class TransactionEventConsumer {

    @SqsListener(value = "${aws.sqs.transaction-queue-url}",
                 acknowledgementMode = SqsAcknowledgementMode.MANUAL)
    public void processTransaction(
            TransactionEvent event,
            Acknowledgement acknowledgement) {
        try {
            transactionService.process(event);
            acknowledgement.acknowledge();           // delete from queue
        } catch (RetryableException e) {
            log.warn("Retryable error, message will be retried: {}", e.getMessage());
            // DO NOT acknowledge — SQS will redeliver after visibilityTimeout
        } catch (PoisonPillException e) {
            log.error("Poison pill message, sending to DLQ: {}", e.getMessage());
            acknowledgement.acknowledge();           // remove from main queue (goes to DLQ)
        }
    }
}
```

### MQ Topic → SNS Fan-Out Pattern

```java
@Service
public class EventPublisher {

    private final SnsClient snsClient;

    @Value("${aws.sns.account-events-topic-arn}")
    private String accountEventsTopicArn;

    /**
     * Replaces IBM MQ Topic publish — multiple subscribers receive a copy
     * Subscribers: audit service, notification service, reporting service
     */
    public void publishAccountEvent(AccountEvent event) {
        PublishRequest request = PublishRequest.builder()
            .topicArn(accountEventsTopicArn)
            .message(objectMapper.writeValueAsString(event))
            .subject(event.getEventType())
            .messageAttributes(Map.of(
                "eventType", MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(event.getEventType())
                    .build()
            ))
            .build();

        snsClient.publish(request);
        log.info("Published {} event for account {}", 
                 event.getEventType(), event.getAccountNumber());
    }
}
```

---

## 10. Security & IAM Mapping

### RACF → AWS IAM + Spring Security

```java
// RACF dataset profile → IAM resource policy
// RACF: PERMIT 'PROD.PAYROLL.**' ACCESS(READ) ID(BATCHUSR)

// Equivalent IAM policy (CDK)
PolicyStatement.Builder.create()
    .effect(Effect.ALLOW)
    .actions(List.of("s3:GetObject", "s3:ListBucket"))
    .resources(List.of(
        "arn:aws:s3:::prod-payroll-bucket",
        "arn:aws:s3:::prod-payroll-bucket/*"
    ))
    .principals(List.of(new ArnPrincipal(batchTaskRoleArn)))
    .build();

// Spring Security configuration
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter()))
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/accounts/**").hasRole("ACCOUNT_VIEWER")
                .requestMatchers(HttpMethod.POST, "/api/v1/payments/**").hasRole("PAYMENT_PROCESSOR")
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .build();
    }
}

// Service-level RACF equivalent using @PreAuthorize
@Service
public class PaymentService {

    @PreAuthorize("hasRole('PAYMENT_PROCESSOR') and #amount < 1000000")
    public void processPayment(String accountId, BigDecimal amount) {
        // Only PAYMENT_PROCESSOR role can call this, and only for amounts < $1M
    }
}
```

### Secrets (Mainframe Keyring → AWS Secrets Manager)

```java
@Configuration
public class SecretsConfig {

    @Bean
    public DataSource dataSource() {
        // Replaces mainframe keyring / encrypted PARMS
        SecretsManagerClient secretsClient = SecretsManagerClient.create();
        String secretJson = secretsClient.getSecretValue(
            GetSecretValueRequest.builder()
                .secretId("prod/myapp/aurora-credentials")
                .build()
        ).secretString();

        DbCredentials creds = objectMapper.readValue(secretJson, DbCredentials.class);

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(creds.getHost());
        ds.setUsername(creds.getUsername());
        ds.setPassword(creds.getPassword());
        return ds;
    }
}
```

---

## 11. Testing Strategy

### Mainframe → Java Testing Pyramid

```
                    ┌──────────────┐
                    │  E2E Tests   │  ← Replaced: CICS integration tests
                    │  (5%)        │    Now: RestAssured / Selenium
                    ├──────────────┤
                   ─┤ Integration  ├─  ← Replaced: XPEDITER / debug sessions
                    │  Tests (25%) │    Now: @SpringBootTest + Testcontainers
                    ├──────────────┤
                   ─┤  Unit Tests  ├─  ← New: JUnit 5 + Mockito (mainframe had none)
                    │  (70%)       │
                    └──────────────┘
```

```java
// Integration test replacing CICS transaction test
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class AccountQueryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test");

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack"))
        .withServices(Service.SQS, Service.S3, Service.DYNAMODB);

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void givenValidAccount_whenQueryAccount_thenReturnAccountDetails() {
        // Given: seed test data (replaces CICS test data in VSAM)
        accountRepository.save(TestDataBuilder.validAccount("1234567890"));

        // When: call REST endpoint (replaces CICS CACQ transaction)
        ResponseEntity<AccountQueryResponse> response =
            restTemplate.getForEntity("/api/v1/accounts/1234567890",
                                      AccountQueryResponse.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().accountNumber()).isEqualTo("1234567890");
        assertThat(response.getBody().currentBalance()).isPositive();
    }
}
```

---

## 12. CI/CD Pipeline on AWS

```
Developer Push
     │
     ▼
┌─────────────────────────────────────────────────────────────────────┐
│  AWS CodePipeline                                                   │
│                                                                     │
│  Source          Build              Test              Deploy        │
│  ──────          ─────              ────              ──────        │
│  CodeCommit  →  CodeBuild       →  CodeBuild      →  CodeDeploy    │
│  (or GitHub)    ─ mvn compile      ─ Unit tests      ─ ECS Blue/   │
│                 ─ mvn test         ─ Integration         Green      │
│                 ─ SonarQube        ─ OWASP scan      ─ Lambda      │
│                 ─ Docker build     ─ Performance         versions   │
│                 ─ ECR push         ─ Smoke tests                   │
└─────────────────────────────────────────────────────────────────────┘
```

**buildspec.yml:**
```yaml
version: 0.2
phases:
  install:
    runtime-versions:
      java: corretto21
  pre_build:
    commands:
      - echo "Running pre-build checks..."
      - mvn dependency:check  # OWASP dependency scan
  build:
    commands:
      - mvn clean verify -P integration-tests
      - mvn sonar:sonar -Dsonar.host.url=$SONAR_URL
      - docker build -t $IMAGE_REPO_NAME:$CODEBUILD_RESOLVED_SOURCE_VERSION .
      - docker tag $IMAGE_REPO_NAME:$CODEBUILD_RESOLVED_SOURCE_VERSION
          $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$IMAGE_REPO_NAME:latest
  post_build:
    commands:
      - docker push $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$IMAGE_REPO_NAME:latest
      - printf '[{"name":"app","imageUri":"%s"}]' $IMAGE_URI > imagedefinitions.json
artifacts:
  files:
    - imagedefinitions.json
    - appspec.yml
    - taskdef.json
```

**Dockerfile (Spring Boot):**
```dockerfile
FROM amazoncorretto:21-alpine AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn clean package -DskipTests

FROM amazoncorretto:21-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /app/target/*.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
```

---

## 13. Observability & Monitoring

### SMF Records / OMEGAMON → CloudWatch + X-Ray

```java
@Configuration
public class ObservabilityConfig {

    // Replaces SMF record Type 30 (CPU/memory) + Type 110 (DB2)
    @Bean
    MeterRegistryCustomizer<MeterRegistry> metricsConfig(
            @Value("${spring.application.name}") String appName) {
        return registry -> registry.config().commonTags(
            "application", appName,
            "environment", "${ENVIRONMENT:local}"
        );
    }
}

@RestController
@Slf4j
public class AccountController {

    private final MeterRegistry meterRegistry;
    private final Tracer tracer; // AWS X-Ray / OpenTelemetry

    @GetMapping("/accounts/{id}")
    public AccountResponse getAccount(@PathVariable String id) {
        // Distributed trace (replaces OMEGAMON transaction trace)
        Span span = tracer.nextSpan().name("account-query").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span.start())) {
            span.tag("account.id", id);

            // Business metric (replaces SMF custom records)
            meterRegistry.counter("account.queries",
                "type", "online", "channel", "api").increment();

            Timer.Sample timer = Timer.start(meterRegistry);
            AccountResponse response = accountService.findById(id);
            timer.stop(meterRegistry.timer("account.query.duration"));

            return response;
        } finally {
            span.end();
        }
    }
}
```

**CloudWatch Alarms (CDK):**
```java
// Replaces OMEGAMON exception thresholds / WTO alerts
Alarm highErrorRateAlarm = Alarm.Builder.create(this, "HighErrorRate")
    .alarmDescription("Error rate > 1% — check ECS logs")
    .metric(Metric.Builder.create()
        .namespace("MyBank/AccountService")
        .metricName("ErrorRate")
        .statistic("Average")
        .period(Duration.minutes(5))
        .build())
    .threshold(1.0)
    .evaluationPeriods(2)
    .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
    .alarmActions(List.of(new SnsAction(opsTeamTopic)))
    .build();
```

---

## 14. Step-by-Step Migration Roadmap

```
PHASE 1 — DISCOVER & ASSESS (Months 1-3)
──────────────────────────────────────────
□ Inventory all COBOL/PL/I programs (IBM Application Discovery)
□ Identify program call chains and dependencies
□ Profile transaction volumes (SMF data analysis)
□ Classify programs: online (CICS), batch (JCL), utility
□ Assess data stores: DB2, VSAM, flat files
□ Identify dead code (programs with 0 SMF calls in 12 months → retire)
□ Define modernization approach per program (Rehost/Refactor/Retire)
□ Establish AWS landing zone (VPC, accounts, IAM, Direct Connect)

PHASE 2 — FOUNDATION (Months 3-6)
────────────────────────────────────
□ Set up AWS landing zone (Control Tower)
□ Establish Direct Connect / VPN to mainframe
□ Deploy CI/CD pipeline (CodePipeline)
□ Set up shared services: Aurora cluster, EKS/ECS cluster, SQS/SNS
□ Implement AWS Secrets Manager, Parameter Store
□ Migrate non-critical DB2 tables to Aurora (AWS DMS + SCT)
□ Establish observability stack (CloudWatch, X-Ray, Grafana)
□ Migrate first low-risk batch jobs to Spring Batch on AWS Batch
□ Implement strangler fig proxy (API Gateway as router)

PHASE 3 — MIGRATE READ PATH (Months 6-12)
───────────────────────────────────────────
□ Migrate read-only CICS transactions → REST microservices
□ Read from Aurora (replicated from DB2 via DMS CDC)
□ Shadow mode: mainframe answers + new service answers → compare
□ Route read traffic to new services via API Gateway (canary: 10% → 50% → 100%)
□ Migrate VSAM lookup files → DynamoDB
□ Decommission retired batch programs

PHASE 4 — MIGRATE WRITE PATH (Months 12-18)
─────────────────────────────────────────────
□ Migrate CICS write transactions to Java microservices
□ Implement dual-write: write to both Aurora and DB2 (via CDC)
□ Validate data consistency between mainframe and AWS
□ Migrate IBM MQ queues to Amazon SQS/SNS
□ Move core batch jobs (statement generation, interest, payroll)
□ Cutover write traffic (canary deployment)

PHASE 5 — FULL CUTOVER & DECOMMISSION (Months 18-24)
───────────────────────────────────────────────────────
□ Complete 100% traffic on AWS
□ Mainframe runs in read-only/shadow mode for 30-90 days
□ Validate all data reconciles (row counts, checksums, business KPIs)
□ Formal sign-off from business, compliance, and audit teams
□ Decommission mainframe LPARs (save MIPS cost)
□ Archive mainframe source code (COBOL, JCL, Copybooks) to S3 Glacier
```

---

## 15. Cost Optimization

| Component | Mainframe Cost | AWS Equivalent | Estimated Savings |
|---|---|---|---|
| MIPS (CPU) | ~$50-100K/MIPS/year | EC2 Graviton3 | 70-90% reduction |
| DB2 z/OS | High SW licensing | Aurora PostgreSQL (open-source engine) | 60-75% reduction |
| MQ Series | IBM licensing | Amazon SQS/SNS | 50-70% reduction |
| Storage (DASD) | Expensive proprietary | S3 / EBS / EFS | 80-95% reduction |
| Staff (COBOL) | Scarce, expensive | Java (large talent pool) | Lower hiring cost |
| Disaster Recovery | Full mirror LPAR | Multi-AZ + cross-region | 50-60% reduction |

**AWS cost controls:**
```java
// Use Savings Plans / Reserved Instances for baseline ECS tasks
// Use Spot Instances for non-critical batch jobs
@Bean
public BatchComputeEnvironment batchComputeEnvironment() {
    return BatchComputeEnvironment.Builder.create(this, "BatchEnv")
        .computeResources(ManagedEc2EcsComputeEnvironmentProps.builder()
            .instanceTypes(List.of(InstanceType.of(InstanceClass.R6G, InstanceSize.XLARGE)))
            .allocationStrategy(AllocationStrategy.SPOT_CAPACITY_OPTIMIZED) // 60-70% cheaper
            .spotBidPercentage(60)
            .build())
        .build();
}
```

---

## 16. Common Pitfalls & How to Avoid Them

| Pitfall | Description | Solution |
|---|---|---|
| **Big Bang Migration** | Try to migrate everything at once → risk of failure | Use Strangler Fig pattern, migrate incrementally |
| **Ignoring COMP-3** | Packed decimal precision loss during conversion | Use `BigDecimal` everywhere, write unit tests with exact values |
| **Batch Window Mindset** | Designing batch jobs with artificial time windows | Design for continuous processing; use SQS + Lambda for near-real-time |
| **Over-engineering** | Mapping every COBOL paragraph to a microservice | Group related programs into bounded contexts |
| **No Dual-Run Validation** | Cutting over without comparing mainframe vs AWS output | Run both in parallel, reconcile row counts and totals |
| **Ignoring EBCDIC** | Flat files in EBCDIC → Java reads garbage | Convert to UTF-8 at ingestion; use `Charset.forName("IBM037")` |
| **Skipping Performance Tests** | CICS handled 5000 TPS; new service handles 50 | Load test with Gatling/JMeter before cutover |
| **RACF → IAM 1:1 Mapping** | Trying to recreate every RACF profile in IAM | Rethink access model; use RBAC with Cognito groups |
| **Stateful Session Assumptions** | CICS pseudo-conversational state in COMMAREA | Design stateless REST services; use Redis/DynamoDB for state |
| **Numeric Precision** | `float`/`double` for monetary amounts | Always use `BigDecimal` for money; never float |

### EBCDIC to UTF-8 Conversion Example

```java
public class EbcdicConverter {

    private static final Charset EBCDIC = Charset.forName("IBM037");

    public static String ebcdicToUtf8(byte[] ebcdicBytes) {
        return new String(ebcdicBytes, EBCDIC);
    }

    public static byte[] utf8ToEbcdic(String text) {
        return text.getBytes(EBCDIC);
    }

    // For packed decimal in EBCDIC files
    public static BigDecimal readPackedDecimal(byte[] buffer, int offset, int length, int scale) {
        byte[] packed = Arrays.copyOfRange(buffer, offset, offset + length);
        return PackedDecimalConverter.fromCobolPackedDecimal(packed, scale);
    }
}
```

---

## 🛠️ Key AWS Services Quick Reference

```
AWS SERVICE                   REPLACES
──────────────────────────────────────────────────────
Amazon ECS (Fargate)        → CICS Regions / z/OS LPARs
AWS Batch                   → JES2/JES3 + JCL Jobs
AWS Step Functions          → Job Scheduler (TWS/OPC)
Amazon Aurora PostgreSQL    → DB2 for z/OS
Amazon DynamoDB             → VSAM KSDS
Amazon S3                   → VSAM ESDS / GDG / PDS
Amazon SQS                  → IBM MQ Queues (point-to-point)
Amazon SNS                  → IBM MQ Topics (pub/sub)
Amazon MQ                   → IBM MQ (lift-and-shift MQ)
Amazon EventBridge          → DataPower ESB / MQ routing
AWS Secrets Manager         → RACF Keyring / Encrypted PARMS
AWS IAM                     → RACF / ACF2 / TopSecret
Amazon Cognito              → RACF Users / Groups
Amazon CloudWatch           → OMEGAMON / RMF
AWS X-Ray                   → CICS Transaction Tracing
AWS DMS                     → Data migration (DB2 → Aurora)
AWS SCT                     → Schema conversion (DB2 DDL → PostgreSQL)
AWS DataSync                → Connect:Direct (NDM)
AWS Transfer Family         → FTP/SFTP servers
Amazon ECR                  → Mainframe load library (executable store)
AWS CodePipeline            → ISPF/SCLM + compile JCL
```

---

## 📖 Further Reading

- [AWS Mainframe Modernization Service](https://aws.amazon.com/mainframe-modernization/)
- [AWS Blu Age (Automated Refactoring)](https://aws.amazon.com/mainframe-modernization/patterns/automated-refactoring/)
- [AWS Database Migration Service](https://aws.amazon.com/dms/)
- [AWS Schema Conversion Tool](https://aws.amazon.com/dms/schema-conversion-tool/)
- [Spring Batch Reference](https://docs.spring.io/spring-batch/docs/current/reference/html/)
- [AWS Well-Architected Framework](https://aws.amazon.com/architecture/well-architected/)
- [IBM Application Discovery and Delivery Intelligence](https://www.ibm.com/products/app-discovery-and-delivery-intelligence)

---

*Comprehensive Mainframe to AWS Java Modernization Reference — covering COBOL, JCL, CICS, VSAM, DB2, MQ, RACF → Spring Boot, Aurora, ECS, SQS, IAM.*
