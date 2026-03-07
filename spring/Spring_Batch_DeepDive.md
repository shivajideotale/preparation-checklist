# 🔄 Spring Batch — Deep Dive Complete Guide

> Spring Batch 5.x / Spring Boot 3.x — Jobs, Steps, Readers, Processors, Writers, Scaling & Production Patterns

---

## 📌 Table of Contents

1. [What is Spring Batch?](#1-what-is-spring-batch)
2. [Core Architecture & Concepts](#2-core-architecture--concepts)
3. [Project Setup](#3-project-setup)
4. [Job & Step Configuration](#4-job--step-configuration)
5. [ItemReader — Reading Data](#5-itemreader--reading-data)
6. [ItemProcessor — Transforming Data](#6-itemprocessor--transforming-data)
7. [ItemWriter — Writing Data](#7-itemwriter--writing-data)
8. [Chunk-Oriented Processing](#8-chunk-oriented-processing)
9. [Tasklet Step](#9-tasklet-step)
10. [Job Parameters & Execution Context](#10-job-parameters--execution-context)
11. [JobRepository & Metadata](#11-jobrepository--metadata)
12. [Listeners & Callbacks](#12-listeners--callbacks)
13. [Skip, Retry & Fault Tolerance](#13-skip-retry--fault-tolerance)
14. [Conditional Flow & Decision](#14-conditional-flow--decision)
15. [Parallel & Scaling Strategies](#15-parallel--scaling-strategies)
16. [Partitioning](#16-partitioning)
17. [Scheduling & Triggering Jobs](#17-scheduling--triggering-jobs)
18. [Testing Spring Batch](#18-testing-spring-batch)
19. [Real-World Complete Example](#19-real-world-complete-example)
20. [Interview Questions & Answers](#20-interview-questions--answers)
21. [Complete Reference Summary](#21-complete-reference-summary)

---

## 1. What is Spring Batch?

**Spring Batch** is a lightweight, comprehensive framework for developing robust batch applications. It provides reusable functions for processing large volumes of records including:

- **Logging / Tracing**
- **Transaction management**
- **Job processing statistics**
- **Job restart, skip, and retry**
- **Resource management**

```
When to use Spring Batch:
┌────────────────────────────────────────────────────────────────┐
│  ✅ Processing millions of records from CSV/XML/DB             │
│  ✅ ETL pipelines (Extract → Transform → Load)                 │
│  ✅ End-of-day billing / payroll / report generation           │
│  ✅ Data migration between systems                             │
│  ✅ Scheduled data synchronization                             │
│  ✅ Bulk email / notification sending                          │
│                                                                │
│  ❌ Real-time / low-latency processing → use messaging         │
│  ❌ Simple one-time scripts → plain Java / @Scheduled          │
│  ❌ Event-driven processing → use Spring Integration           │
└────────────────────────────────────────────────────────────────┘
```

### Spring Batch vs Other Technologies

```
┌─────────────────┬──────────────────────────────────────────────┐
│  Technology     │  Best For                                    │
├─────────────────┼──────────────────────────────────────────────┤
│  Spring Batch   │  Large volume, structured batch processing   │
│  Quartz         │  Job scheduling only (no processing logic)   │
│  @Scheduled     │  Simple periodic tasks, no restart/retry     │
│  Kafka          │  Real-time streaming, event-driven           │
│  Spark          │  Big Data, distributed computation           │
│  Spring Batch   │  Can integrate with ALL of the above ✅      │
└─────────────────┴──────────────────────────────────────────────┘
```

---

## 2. Core Architecture & Concepts

### Architecture Diagram

```
┌────────────────────────────────────────────────────────────────────────────┐
│                         SPRING BATCH ARCHITECTURE                          │
│                                                                            │
│  ┌──────────────┐     launches      ┌──────────────────────────────────┐  │
│  │  JobLauncher │───────────────────►│              JOB                │  │
│  └──────────────┘                   │                                  │  │
│                                     │  ┌────────┐  ┌────────┐         │  │
│  ┌──────────────┐    persists to    │  │ Step 1 │→ │ Step 2 │→ ...   │  │
│  │JobRepository │◄──────────────────│  └────────┘  └────────┘         │  │
│  └──────────────┘                   └──────────────────────────────────┘  │
│                                              │                             │
│                              ┌───────────────┼───────────────┐            │
│                              ▼               ▼               ▼            │
│                        ┌──────────┐   ┌──────────┐   ┌──────────┐        │
│                        │ItemReader│   │  Item    │   │ItemWriter│        │
│                        │          │   │Processor │   │          │        │
│                        │ reads 1  │   │transforms│   │ writes   │        │
│                        │ at a time│   │ 1 at time│   │ chunk N  │        │
│                        └──────────┘   └──────────┘   └──────────┘        │
│                                    CHUNK (e.g. 100 items)                 │
└────────────────────────────────────────────────────────────────────────────┘
```

### Key Concepts

```
┌──────────────────────────────────────────────────────────────────────────┐
│  CONCEPT              DESCRIPTION                                        │
├──────────────────────────────────────────────────────────────────────────┤
│  Job                  A batch process — composed of Steps                │
│  Step                 One phase of a Job (can be chunk or tasklet)       │
│  JobInstance          One logical run of a Job (identified by params)    │
│  JobExecution         One actual attempt to run a JobInstance            │
│  StepExecution        One actual attempt to run a Step                   │
│  JobParameters        Key-value pairs that identify a JobInstance        │
│  JobRepository        Persistence store for batch metadata               │
│  JobLauncher          Runs a Job with given parameters                   │
│  ItemReader           Reads items one at a time                          │
│  ItemProcessor        Transforms one item into another (optional)        │
│  ItemWriter           Writes a chunk of items at once                    │
│  ExecutionContext      Key-value store for Step/Job state (restart data) │
│  Chunk                A fixed number of items processed in one TX        │
│  Tasklet              Single-operation step (not chunk-based)            │
└──────────────────────────────────────────────────────────────────────────┘
```

### Job, JobInstance, JobExecution Relationship

```
Job: "importUsersJob"
│
├── JobInstance: importUsersJob + {date=2024-01-15}   ← run on Jan 15
│       ├── JobExecution #1: FAILED  (crashed at step 2)
│       └── JobExecution #2: COMPLETED  (restarted, completed)
│
└── JobInstance: importUsersJob + {date=2024-01-16}   ← new day, new instance
        └── JobExecution #3: COMPLETED

Key rule: A COMPLETED JobInstance CANNOT be re-run.
          A FAILED JobInstance CAN be restarted from where it stopped.
```

---

## 3. Project Setup

### `pom.xml`

```xml
<dependencies>
    <!-- Spring Batch core -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-batch</artifactId>
    </dependency>

    <!-- Database for JobRepository (use H2 for dev, Postgres for prod) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>

    <!-- Dev: H2 in-memory -->
    <dependency>
        <groupId>com.h2database</groupId>
        <artifactId>h2</artifactId>
        <scope>runtime</scope>
    </dependency>

    <!-- Prod: PostgreSQL -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>

    <!-- For CSV reading -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-batch</artifactId>
        <!-- Includes spring-batch-infrastructure for FlatFileItemReader -->
    </dependency>

    <!-- Testing -->
    <dependency>
        <groupId>org.springframework.batch</groupId>
        <artifactId>spring-batch-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### `application.properties`

```properties
# ── Database ──────────────────────────────────────────────────────────────────
spring.datasource.url=jdbc:postgresql://localhost:5432/batchdb
spring.datasource.username=batchuser
spring.datasource.password=secret

# ── Spring Batch ──────────────────────────────────────────────────────────────
# Auto-create batch schema tables (BATCH_JOB_INSTANCE, BATCH_JOB_EXECUTION, etc.)
spring.batch.jdbc.initialize-schema=always
# always   → always recreate tables (dev)
# embedded → only for embedded DBs like H2
# never    → you manage schema manually (prod)

# Spring Boot 3.x: Do NOT auto-run jobs on startup (control manually)
spring.batch.job.enabled=false

# ── JPA ───────────────────────────────────────────────────────────────────────
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.show-sql=false

# ── Logging ───────────────────────────────────────────────────────────────────
logging.level.org.springframework.batch=INFO
```

---

## 4. Job & Step Configuration

### Basic Job with Two Steps

```java
import org.springframework.batch.core.*;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class BasicJobConfig {

    // ── Step 1: Tasklet (simple single operation) ────────────────────────────
    @Bean
    public Step validateInputStep(JobRepository jobRepository,
                                  PlatformTransactionManager txManager) {
        return new StepBuilder("validateInputStep", jobRepository)
            .tasklet(validateInputTasklet(), txManager)
            .build();
    }

    @Bean
    public Tasklet validateInputTasklet() {
        return (contribution, chunkContext) -> {
            System.out.println("Validating input files...");
            // Check input file exists, is readable, etc.
            boolean valid = checkInputFile();
            if (!valid) {
                throw new RuntimeException("Input file not found or invalid");
            }
            System.out.println("Validation passed ✓");
            return RepeatStatus.FINISHED; // Tell Spring Batch this step is done
        };
    }

    // ── Step 2: Chunk-oriented (reads, processes, writes) ───────────────────
    @Bean
    public Step processUsersStep(JobRepository jobRepository,
                                 PlatformTransactionManager txManager,
                                 ItemReader<UserCsvRecord> reader,
                                 ItemProcessor<UserCsvRecord, User> processor,
                                 ItemWriter<User> writer) {
        return new StepBuilder("processUsersStep", jobRepository)
            .<UserCsvRecord, User>chunk(100, txManager) // Process 100 items per transaction
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .build();
    }

    // ── Job: combines steps in sequence ─────────────────────────────────────
    @Bean
    public Job importUsersJob(JobRepository jobRepository,
                              Step validateInputStep,
                              Step processUsersStep) {
        return new JobBuilder("importUsersJob", jobRepository)
            .start(validateInputStep)
            .next(processUsersStep)
            .build();
    }

    // ── Launch the job programmatically ─────────────────────────────────────
    // (Injected where needed, e.g., REST endpoint or @Scheduled method)
}

// ── JobLauncher usage ────────────────────────────────────────────────────────
@Service
public class BatchJobService {

    private final JobLauncher jobLauncher;
    private final Job importUsersJob;

    public BatchJobService(JobLauncher jobLauncher, Job importUsersJob) {
        this.jobLauncher  = jobLauncher;
        this.importUsersJob = importUsersJob;
    }

    public JobExecution runImportJob(String filePath) throws Exception {
        JobParameters params = new JobParametersBuilder()
            .addString("filePath",  filePath)
            .addLocalDateTime("startTime", LocalDateTime.now()) // Make unique per run
            .toJobParameters();

        JobExecution execution = jobLauncher.run(importUsersJob, params);

        System.out.println("Job status: " + execution.getStatus());
        System.out.println("Exit code:  " + execution.getExitStatus().getExitCode());
        return execution;
    }
}
```

---

## 5. ItemReader — Reading Data

### FlatFileItemReader — CSV

```java
import org.springframework.batch.item.file.*;
import org.springframework.batch.item.file.mapping.*;
import org.springframework.batch.item.file.transform.*;
import org.springframework.core.io.*;

// ── Domain / DTO ─────────────────────────────────────────────────────────────
public record UserCsvRecord(
    String firstName,
    String lastName,
    String email,
    String department,
    double salary
) {}

// CSV File (users.csv):
// firstName,lastName,email,department,salary
// Alice,Smith,alice@corp.com,Engineering,85000
// Bob,Jones,bob@corp.com,Marketing,72000

@Configuration
public class CsvReaderConfig {

    @Bean
    @StepScope // IMPORTANT: @StepScope allows late binding of job parameters
    public FlatFileItemReader<UserCsvRecord> csvUserReader(
            @Value("#{jobParameters['filePath']}") String filePath) {

        return new FlatFileItemReaderBuilder<UserCsvRecord>()
            .name("csvUserReader")
            .resource(new FileSystemResource(filePath))
            .linesToSkip(1) // Skip header row
            .delimited()
                .delimiter(",")
                .names("firstName", "lastName", "email", "department", "salary")
            .fieldSetMapper(fieldSet -> new UserCsvRecord(
                fieldSet.readString("firstName"),
                fieldSet.readString("lastName"),
                fieldSet.readString("email"),
                fieldSet.readString("department"),
                fieldSet.readDouble("salary")
            ))
            .build();
    }

    // ✅ Reading fixed-width files (e.g., legacy mainframe files)
    // Format: "Alice     Smith     alice@corp.com     "
    @Bean
    public FlatFileItemReader<UserCsvRecord> fixedWidthReader() {
        return new FlatFileItemReaderBuilder<UserCsvRecord>()
            .name("fixedWidthReader")
            .resource(new ClassPathResource("users-fixed.txt"))
            .linesToSkip(1)
            .fixedLength()
                .columns(new Range(1, 10), new Range(11, 20), new Range(21, 40))
                .names("firstName", "lastName", "email")
            .fieldSetMapper(new BeanWrapperFieldSetMapper<>())
            .targetType(UserCsvRecord.class)
            .build();
    }
}
```

---

### JdbcCursorItemReader — Database

```java
import org.springframework.batch.item.database.*;
import org.springframework.batch.item.database.builder.*;
import javax.sql.DataSource;

@Configuration
public class DatabaseReaderConfig {

    // ── JDBC Cursor Reader — streams rows one at a time (memory efficient) ───
    @Bean
    @StepScope
    public JdbcCursorItemReader<Employee> jdbcCursorReader(DataSource dataSource) {
        return new JdbcCursorItemReaderBuilder<Employee>()
            .name("employeeReader")
            .dataSource(dataSource)
            .sql("""
                SELECT id, first_name, last_name, email, salary, department_id
                FROM employees
                WHERE status = 'ACTIVE'
                ORDER BY id
                """)
            .rowMapper((rs, rowNum) -> new Employee(
                rs.getLong("id"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("email"),
                rs.getBigDecimal("salary"),
                rs.getLong("department_id")
            ))
            .fetchSize(500)        // Fetch 500 rows at a time from DB
            .build();
    }

    // ── JDBC Paging Reader — reads in pages (restartable, works with replicas) ─
    @Bean
    @StepScope
    public JdbcPagingItemReader<Employee> jdbcPagingReader(
            DataSource dataSource,
            @Value("#{jobParameters['departmentId']}") Long departmentId) {

        Map<String, Object> params = new HashMap<>();
        params.put("departmentId", departmentId);
        params.put("status", "ACTIVE");

        return new JdbcPagingItemReaderBuilder<Employee>()
            .name("pagingEmployeeReader")
            .dataSource(dataSource)
            .selectClause("SELECT id, first_name, last_name, email, salary")
            .fromClause("FROM employees")
            .whereClause("WHERE department_id = :departmentId AND status = :status")
            .sortKeys(Map.of("id", Order.ASCENDING))  // REQUIRED for paging
            .parameterValues(params)
            .pageSize(200)
            .rowMapper((rs, rowNum) -> new Employee(
                rs.getLong("id"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("email"),
                rs.getBigDecimal("salary"),
                departmentId
            ))
            .build();
    }

    // ── JPA Paging Reader — uses JPA entities ────────────────────────────────
    @Bean
    @StepScope
    public JpaPagingItemReader<Employee> jpaPagingReader(
            EntityManagerFactory entityManagerFactory) {

        return new JpaPagingItemReaderBuilder<Employee>()
            .name("jpaEmployeeReader")
            .entityManagerFactory(entityManagerFactory)
            .queryString("SELECT e FROM Employee e WHERE e.status = 'ACTIVE' ORDER BY e.id")
            .pageSize(100)
            .build();
    }
}
```

---

### StaxEventItemReader — XML

```java
import org.springframework.batch.item.xml.*;
import org.springframework.batch.item.xml.builder.*;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;

// XML File:
// <users>
//   <user><id>1</id><name>Alice</name><email>alice@corp.com</email></user>
//   <user><id>2</id><name>Bob</name><email>bob@corp.com</email></user>
// </users>

@Configuration
public class XmlReaderConfig {

    @Bean
    public StaxEventItemReader<UserXmlRecord> xmlReader() {
        Jaxb2Marshaller unmarshaller = new Jaxb2Marshaller();
        unmarshaller.setClassesToBeBound(UserXmlRecord.class);

        return new StaxEventItemReaderBuilder<UserXmlRecord>()
            .name("xmlUserReader")
            .resource(new ClassPathResource("users.xml"))
            .addFragmentRootElements("user") // Root element per item
            .unmarshaller(unmarshaller)
            .build();
    }
}

@XmlRootElement(name = "user")
public class UserXmlRecord {
    @XmlElement public Long   id;
    @XmlElement public String name;
    @XmlElement public String email;
}
```

---

### Custom ItemReader

```java
import org.springframework.batch.item.*;

// Read from a REST API, message queue, or any custom source
@Component
@StepScope
public class RestApiItemReader implements ItemReader<ProductDto> {

    private final RestTemplate restTemplate;
    private final String apiUrl;

    private int currentPage = 0;
    private final int pageSize = 50;
    private Iterator<ProductDto> currentPageIterator;
    private boolean exhausted = false;

    public RestApiItemReader(RestTemplate restTemplate,
                             @Value("${api.products.url}") String apiUrl) {
        this.restTemplate = restTemplate;
        this.apiUrl        = apiUrl;
    }

    @Override
    public ProductDto read() throws Exception {
        // Called once per item — return null to signal end of input
        if (exhausted) return null;

        if (currentPageIterator == null || !currentPageIterator.hasNext()) {
            fetchNextPage();
        }

        if (exhausted) return null;
        return currentPageIterator.next();
    }

    private void fetchNextPage() {
        String url = apiUrl + "?page=" + currentPage + "&size=" + pageSize;
        ProductPage page = restTemplate.getForObject(url, ProductPage.class);

        if (page == null || page.content().isEmpty()) {
            exhausted = true;
            return;
        }

        currentPageIterator = page.content().iterator();
        currentPage++;

        if (page.content().size() < pageSize) {
            exhausted = true; // Last page
        }
    }

    record ProductPage(List<ProductDto> content, int totalPages) {}
}
```

---

## 6. ItemProcessor — Transforming Data

### Basic Processor

```java
import org.springframework.batch.item.ItemProcessor;

// ── Simple transformation ─────────────────────────────────────────────────────
@Component
public class UserCsvToEntityProcessor implements ItemProcessor<UserCsvRecord, User> {

    private final DepartmentRepository departmentRepo;
    private final PasswordEncoder passwordEncoder;

    public UserCsvToEntityProcessor(DepartmentRepository departmentRepo,
                                    PasswordEncoder passwordEncoder) {
        this.departmentRepo  = departmentRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public User process(UserCsvRecord record) throws Exception {
        // Returning null → item is FILTERED OUT (skipped, not passed to writer)

        // ✅ Validate / filter
        if (record.email() == null || !record.email().contains("@")) {
            log.warn("Skipping invalid email: {}", record.email());
            return null; // Filtered — not written
        }

        // ✅ Enrich / transform
        Department dept = departmentRepo.findByName(record.department())
            .orElseGet(() -> new Department(record.department())); // Create if missing

        User user = new User();
        user.setFirstName(record.firstName());
        user.setLastName(record.lastName());
        user.setEmail(record.email().toLowerCase().trim());
        user.setDepartment(dept);
        user.setSalary(record.salary());
        user.setPassword(passwordEncoder.encode(generateTempPassword()));
        user.setCreatedAt(LocalDateTime.now());
        user.setStatus(UserStatus.PENDING_ACTIVATION);

        return user;
    }

    private String generateTempPassword() {
        return UUID.randomUUID().toString().substring(0, 12);
    }
}
```

---

### Composite Processor — Chaining Multiple Processors

```java
import org.springframework.batch.item.support.CompositeItemProcessor;

@Configuration
public class ProcessorConfig {

    // ── Individual processors ──────────────────────────────────────────────────
    @Bean
    public ItemProcessor<Employee, Employee> salaryNormalizationProcessor() {
        return employee -> {
            // Normalize salary to annual if stored monthly
            if (employee.getSalaryType() == SalaryType.MONTHLY) {
                employee.setSalary(employee.getSalary().multiply(BigDecimal.valueOf(12)));
                employee.setSalaryType(SalaryType.ANNUAL);
            }
            return employee;
        };
    }

    @Bean
    public ItemProcessor<Employee, Employee> taxCalculationProcessor() {
        return employee -> {
            BigDecimal taxRate = getTaxRate(employee.getCountry());
            employee.setTaxAmount(employee.getSalary().multiply(taxRate));
            return employee;
        };
    }

    @Bean
    public ItemProcessor<Employee, Employee> validationProcessor() {
        return employee -> {
            if (employee.getSalary().compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Filtering employee with non-positive salary: {}", employee.getId());
                return null; // Filter out
            }
            return employee;
        };
    }

    // ── Composite: chain them together ────────────────────────────────────────
    @Bean
    public CompositeItemProcessor<Employee, Employee> compositeProcessor() {
        CompositeItemProcessor<Employee, Employee> composite =
            new CompositeItemProcessor<>();

        composite.setDelegates(List.of(
            salaryNormalizationProcessor(),  // 1st
            taxCalculationProcessor(),       // 2nd
            validationProcessor()            // 3rd (filter)
        ));

        return composite;
    }

    private BigDecimal getTaxRate(String country) {
        return switch (country) {
            case "US" -> new BigDecimal("0.22");
            case "UK" -> new BigDecimal("0.20");
            case "DE" -> new BigDecimal("0.19");
            default   -> new BigDecimal("0.25");
        };
    }
}
```

---

### Processor with Validation

```java
import jakarta.validation.*;

@Component
public class ValidatingProcessor<T> implements ItemProcessor<T, T> {

    private final Validator validator;

    public ValidatingProcessor(Validator validator) {
        this.validator = validator;
    }

    @Override
    public T process(T item) throws Exception {
        Set<ConstraintViolation<T>> violations = validator.validate(item);
        if (!violations.isEmpty()) {
            String errors = violations.stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining(", "));
            throw new ValidationException("Validation failed for " + item + ": " + errors);
        }
        return item;
    }
}
```

---

## 7. ItemWriter — Writing Data

### JdbcBatchItemWriter — Database

```java
import org.springframework.batch.item.database.*;
import org.springframework.batch.item.database.builder.*;

@Configuration
public class DatabaseWriterConfig {

    // ── Named parameters — most readable ─────────────────────────────────────
    @Bean
    public JdbcBatchItemWriter<User> jdbcBatchWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<User>()
            .dataSource(dataSource)
            .sql("""
                INSERT INTO users (first_name, last_name, email, department_id, salary, status, created_at)
                VALUES (:firstName, :lastName, :email, :department.id, :salary, :status, :createdAt)
                ON CONFLICT (email) DO UPDATE
                    SET first_name  = EXCLUDED.first_name,
                        last_name   = EXCLUDED.last_name,
                        salary      = EXCLUDED.salary,
                        updated_at  = NOW()
                """)
            .beanMapped() // Uses getter names to bind :firstName → getFirstName()
            .build();
    }

    // ── ItemPreparedStatementSetter — for positional params ──────────────────
    @Bean
    public JdbcBatchItemWriter<User> jdbcPositionalWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<User>()
            .dataSource(dataSource)
            .sql("INSERT INTO users (first_name, last_name, email, salary) VALUES (?, ?, ?, ?)")
            .itemPreparedStatementSetter((user, ps) -> {
                ps.setString(1, user.getFirstName());
                ps.setString(2, user.getLastName());
                ps.setString(3, user.getEmail());
                ps.setBigDecimal(4, user.getSalary());
            })
            .build();
    }

    // ── JPA Item Writer ───────────────────────────────────────────────────────
    @Bean
    public JpaItemWriter<User> jpaWriter(EntityManagerFactory emf) {
        JpaItemWriter<User> writer = new JpaItemWriter<>();
        writer.setEntityManagerFactory(emf);
        return writer;
    }
}
```

---

### FlatFileItemWriter — CSV Output

```java
import org.springframework.batch.item.file.*;
import org.springframework.batch.item.file.builder.*;
import org.springframework.batch.item.file.transform.*;

@Configuration
public class CsvWriterConfig {

    @Bean
    @StepScope
    public FlatFileItemWriter<ProcessedEmployee> csvReportWriter(
            @Value("#{jobParameters['outputPath']}") String outputPath) {

        // Line aggregator: converts object → CSV string
        DelimitedLineAggregator<ProcessedEmployee> lineAggregator =
            new DelimitedLineAggregator<>();
        lineAggregator.setDelimiter(",");

        BeanWrapperFieldExtractor<ProcessedEmployee> extractor =
            new BeanWrapperFieldExtractor<>();
        extractor.setNames(new String[]{
            "id", "fullName", "email", "annualSalary", "taxAmount", "department"
        });
        lineAggregator.setFieldExtractor(extractor);

        return new FlatFileItemWriterBuilder<ProcessedEmployee>()
            .name("employeeReportWriter")
            .resource(new FileSystemResource(outputPath))
            .headerCallback(writer ->
                writer.write("ID,Full Name,Email,Annual Salary,Tax Amount,Department"))
            .footerCallback(writer ->
                writer.write("Generated at: " + LocalDateTime.now()))
            .lineAggregator(lineAggregator)
            .append(false)   // false = overwrite; true = append
            .build();
    }
}
```

---

### Custom ItemWriter

```java
@Component
public class EmailNotificationWriter implements ItemWriter<ProcessedEmployee> {

    private final EmailService emailService;

    public EmailNotificationWriter(EmailService emailService) {
        this.emailService = emailService;
    }

    @Override
    public void write(Chunk<? extends ProcessedEmployee> chunk) throws Exception {
        // chunk.getItems() is List<ProcessedEmployee>
        // All items in one chunk are written together (one transaction)

        List<? extends ProcessedEmployee> employees = chunk.getItems();

        log.info("Sending payslip emails for {} employees", employees.size());

        for (ProcessedEmployee emp : employees) {
            try {
                emailService.sendPayslip(
                    emp.getEmail(),
                    emp.getFullName(),
                    emp.getAnnualSalary(),
                    emp.getTaxAmount()
                );
            } catch (EmailDeliveryException e) {
                log.error("Failed to send email to {}: {}", emp.getEmail(), e.getMessage());
                throw e; // Re-throw to trigger retry/skip logic
            }
        }

        log.info("Successfully sent {} payslip emails", employees.size());
    }
}
```

---

### CompositeItemWriter — Write to Multiple Destinations

```java
import org.springframework.batch.item.support.CompositeItemWriter;

@Configuration
public class CompositeWriterConfig {

    @Bean
    public CompositeItemWriter<User> compositeWriter(
            JdbcBatchItemWriter<User> dbWriter,
            FlatFileItemWriter<User> csvWriter,
            AuditWriter<User> auditWriter) {

        CompositeItemWriter<User> writer = new CompositeItemWriter<>();
        writer.setDelegates(List.of(
            dbWriter,    // 1. Save to PostgreSQL
            csvWriter,   // 2. Write to CSV report
            auditWriter  // 3. Log to audit system
        ));
        return writer;
    }
}
```

---

## 8. Chunk-Oriented Processing

### How Chunks Work (Transaction Boundary)

```
Chunk Size = 100

Step reads items one at a time, accumulates them:

┌─────────────────────────────────────────────────────────────────┐
│  Chunk 1 Processing:                                            │
│                                                                 │
│  Read item 1  → Process item 1                                  │
│  Read item 2  → Process item 2                                  │
│  ...                                                            │
│  Read item 100 → Process item 100                               │
│                                                                 │
│  ┌─── BEGIN TRANSACTION ───────────────────────────────────┐   │
│  │  Write [item1, item2, ... item100] as a batch           │   │
│  │  Commit                                                 │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  Read item 101 → Process item 101                               │
│  ...                                                            │
│  Read item 200 → Process item 200                               │
│                                                                 │
│  ┌─── BEGIN TRANSACTION ───────────────────────────────────┐   │
│  │  Write [item101, item102, ... item200]                  │   │
│  │  Commit                                                 │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  If write FAILS → ROLLBACK only the current chunk!             │
│  Previous chunks already committed are safe.                   │
└─────────────────────────────────────────────────────────────────┘
```

### Chunk Size Tuning

```java
@Configuration
public class ChunkSizeConfig {

    // ── Too small (chunk=1): too many transactions → slow ─────────────────────
    // ── Too large (chunk=10000): large TX, high memory, long rollback time ────
    // ── Sweet spot: 50–500 depending on item size and DB performance ──────────

    @Bean
    public Step optimizedChunkStep(JobRepository jobRepository,
                                   PlatformTransactionManager txManager,
                                   ItemReader<Order> reader,
                                   ItemProcessor<Order, ProcessedOrder> processor,
                                   ItemWriter<ProcessedOrder> writer) {
        return new StepBuilder("processOrdersStep", jobRepository)
            .<Order, ProcessedOrder>chunk(200, txManager) // Tune this per workload
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .build();
    }

    // ── Variable chunk size based on item weight ──────────────────────────────
    // For heavy items (large XML documents): chunk=10
    // For light items (simple rows): chunk=1000
}
```

---

## 9. Tasklet Step

A **Tasklet** is a step that does a single operation (not chunk-based). Used for setup/teardown, file operations, sending notifications, etc.

```java
import org.springframework.batch.core.step.tasklet.*;

// ── Example 1: Move files after processing ───────────────────────────────────
@Component
@StepScope
public class MoveProcessedFilesTasklet implements Tasklet {

    private final String inputDir;
    private final String archiveDir;

    public MoveProcessedFilesTasklet(
            @Value("${batch.input.dir}")   String inputDir,
            @Value("${batch.archive.dir}") String archiveDir) {
        this.inputDir   = inputDir;
        this.archiveDir = archiveDir;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution,
                                ChunkContext chunkContext) throws Exception {
        Path input   = Path.of(inputDir);
        Path archive = Path.of(archiveDir);

        Files.createDirectories(archive);

        try (Stream<Path> files = Files.list(input)) {
            files.filter(p -> p.toString().endsWith(".csv"))
                 .forEach(file -> {
                     try {
                         Path target = archive.resolve(file.getFileName());
                         Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                         log.info("Archived: {}", file.getFileName());
                         contribution.incrementWriteCount(1); // Track count in metadata
                     } catch (IOException e) {
                         throw new RuntimeException("Failed to archive " + file, e);
                     }
                 });
        }

        return RepeatStatus.FINISHED;
    }
}

// ── Example 2: Repeating Tasklet (calls execute until FINISHED) ──────────────
@Component
public class PollingTasklet implements Tasklet {

    private final ExternalJobService externalJob;
    private final String jobId;

    @Override
    public RepeatStatus execute(StepContribution contribution,
                                ChunkContext chunkContext) throws Exception {
        JobStatus status = externalJob.checkStatus(jobId);

        if (status == JobStatus.RUNNING) {
            Thread.sleep(5000); // Wait 5s
            return RepeatStatus.CONTINUABLE; // Execute will be called again
        }

        if (status == JobStatus.FAILED) {
            throw new RuntimeException("External job failed: " + jobId);
        }

        log.info("External job completed successfully");
        return RepeatStatus.FINISHED; // Stop repeating
    }
}

// ── Registering tasklet beans ─────────────────────────────────────────────────
@Configuration
public class TaskletStepConfig {

    @Bean
    public Step moveFilesStep(JobRepository jobRepository,
                              PlatformTransactionManager txManager,
                              MoveProcessedFilesTasklet tasklet) {
        return new StepBuilder("moveFilesStep", jobRepository)
            .tasklet(tasklet, txManager)
            .build();
    }

    // ── MethodInvokingTaskletAdapter — call any bean method as a tasklet ──────
    @Bean
    public Step sendReportEmailStep(JobRepository jobRepository,
                                    PlatformTransactionManager txManager) {
        MethodInvokingTaskletAdapter adapter = new MethodInvokingTaskletAdapter();
        adapter.setTargetObject(reportService());
        adapter.setTargetMethod("sendDailyReport");
        // adapter.setArguments(new Object[]{"arg1", "arg2"}); // Optional args

        return new StepBuilder("sendReportEmailStep", jobRepository)
            .tasklet(adapter, txManager)
            .build();
    }
}
```

---

## 10. Job Parameters & Execution Context

### Job Parameters

```java
// Job parameters identify a JobInstance — same params = same instance
// JobInstance that COMPLETED cannot be re-run with same params
// Adding a unique param (timestamp, runId) creates a new instance each time

@Service
public class JobParameterDemo {

    private final JobLauncher jobLauncher;
    private final Job importJob;

    // ── Run with parameters ────────────────────────────────────────────────────
    public void runJob(String filename, LocalDate processDate) throws Exception {

        JobParameters params = new JobParametersBuilder()
            // String
            .addString("filename",    filename)
            // LocalDate / LocalDateTime / LocalTime
            .addLocalDate("processDate",  processDate)
            .addLocalDateTime("launchedAt", LocalDateTime.now())
            // Long / Double
            .addLong("batchSize",    500L)
            .addDouble("tolerance",  0.01)
            // Identifying vs non-identifying
            .addString("filename",   filename,   true)  // identifying (default)
            .addString("requestedBy", "system",  false) // non-identifying (metadata only)
            .toJobParameters();

        jobLauncher.run(importJob, params);
    }

    // ── Accessing parameters in a step ────────────────────────────────────────
    // Option 1: @Value with SpEL (in @StepScope beans)
    @Bean
    @StepScope
    public SomeReader readerWithParam(
            @Value("#{jobParameters['filename']}")    String filename,
            @Value("#{jobParameters['processDate']}") LocalDate processDate) {
        return new SomeReader(filename, processDate);
    }

    // Option 2: ChunkContext in tasklets
    @Bean
    public Tasklet taskletWithParams() {
        return (contribution, chunkContext) -> {
            JobParameters params = chunkContext
                .getStepContext()
                .getStepExecution()
                .getJobParameters();

            String filename = params.getString("filename");
            log.info("Processing file: {}", filename);
            return RepeatStatus.FINISHED;
        };
    }
}
```

---

### ExecutionContext — Sharing State Between Steps

```java
// ExecutionContext is a key-value store scoped to Job or Step
// Use it to pass data between steps or store restart checkpoints

@Component
public class CountingProcessor implements ItemProcessor<User, User> {

    // ── Write to StepExecutionContext ─────────────────────────────────────────
    @BeforeStep
    public void beforeStep(StepExecution stepExecution) {
        // Initialize counter in step context
        stepExecution.getExecutionContext().putInt("processedCount", 0);
        stepExecution.getExecutionContext().putInt("filteredCount", 0);
    }

    @Override
    public User process(User user) throws Exception {
        // Context not directly accessible here — use @Autowired StepExecution
        // or use a field + @BeforeStep injection
        return transformUser(user);
    }
}

// ── Passing data between steps via JobExecutionContext ─────────────────────────
@Component
public class Step1Writer implements ItemWriter<User>, StepExecutionListener {

    private StepExecution stepExecution;

    @Override
    public void beforeStep(StepExecution stepExecution) {
        this.stepExecution = stepExecution;
    }

    @Override
    public void write(Chunk<? extends User> chunk) throws Exception {
        userRepository.saveAll(chunk.getItems());

        // Store count in JOB context so Step2 can read it
        ExecutionContext jobContext = stepExecution
            .getJobExecution()
            .getExecutionContext();

        int total = jobContext.getInt("totalImported", 0) + chunk.size();
        jobContext.putInt("totalImported", total);
    }
}

@Component
public class Step2Tasklet implements Tasklet {

    @Override
    public RepeatStatus execute(StepContribution contribution,
                                ChunkContext chunkContext) throws Exception {

        // Read from JOB context set by Step1
        ExecutionContext jobContext = chunkContext
            .getStepContext()
            .getStepExecution()
            .getJobExecution()
            .getExecutionContext();

        int totalImported = jobContext.getInt("totalImported", 0);
        log.info("Step1 imported {} records, sending summary email...", totalImported);

        emailService.sendBatchSummary(totalImported);
        return RepeatStatus.FINISHED;
    }
}
```

---

## 11. JobRepository & Metadata

Spring Batch creates these tables in your database to track all executions:

```sql
-- Core metadata tables (auto-created by Spring Batch)

BATCH_JOB_INSTANCE         -- One row per unique job + params combination
  JOB_INSTANCE_ID  BIGINT  PRIMARY KEY
  JOB_NAME         VARCHAR
  JOB_KEY          VARCHAR  -- Hash of parameters

BATCH_JOB_EXECUTION        -- One row per execution attempt
  JOB_EXECUTION_ID  BIGINT  PRIMARY KEY
  JOB_INSTANCE_ID   BIGINT  FK → BATCH_JOB_INSTANCE
  STATUS            VARCHAR  (STARTING, STARTED, STOPPING, STOPPED,
                              FAILED, COMPLETED, ABANDONED)
  EXIT_CODE         VARCHAR  (COMPLETED, FAILED, NOOP, UNKNOWN)
  START_TIME        TIMESTAMP
  END_TIME          TIMESTAMP
  CREATE_TIME       TIMESTAMP

BATCH_JOB_EXECUTION_PARAMS -- Job parameters for each execution
  JOB_EXECUTION_ID  BIGINT
  PARAMETER_NAME    VARCHAR
  PARAMETER_TYPE    VARCHAR
  PARAMETER_VALUE   VARCHAR
  IDENTIFYING       CHAR

BATCH_STEP_EXECUTION       -- One row per step per execution
  STEP_EXECUTION_ID  BIGINT  PRIMARY KEY
  JOB_EXECUTION_ID   BIGINT  FK
  STEP_NAME          VARCHAR
  STATUS             VARCHAR
  READ_COUNT         BIGINT   -- Items read
  WRITE_COUNT        BIGINT   -- Items written
  FILTER_COUNT       BIGINT   -- Items filtered (processor returned null)
  SKIP_COUNT         BIGINT   -- Items skipped (skip policy)
  COMMIT_COUNT       BIGINT   -- Chunks committed
  ROLLBACK_COUNT     BIGINT   -- Chunks rolled back

BATCH_JOB_EXECUTION_CONTEXT  -- ExecutionContext persisted for restart
BATCH_STEP_EXECUTION_CONTEXT -- Step ExecutionContext for restart
```

### Querying Job Metadata Programmatically

```java
import org.springframework.batch.core.explore.JobExplorer;

@Service
public class BatchMonitoringService {

    private final JobExplorer jobExplorer;
    private final JobOperator jobOperator;

    public BatchMonitoringService(JobExplorer jobExplorer,
                                  JobOperator jobOperator) {
        this.jobExplorer  = jobExplorer;
        this.jobOperator  = jobOperator;
    }

    // ── List all job names ────────────────────────────────────────────────────
    public Set<String> getJobNames() {
        return jobExplorer.getJobNames();
    }

    // ── Get all instances of a job ────────────────────────────────────────────
    public List<JobInstance> getJobInstances(String jobName) {
        return jobExplorer.getJobInstances(jobName, 0, 10); // name, start, count
    }

    // ── Get latest execution of a job ─────────────────────────────────────────
    public Optional<JobExecution> getLatestExecution(String jobName) {
        return jobExplorer.getJobInstances(jobName, 0, 1)
            .stream()
            .findFirst()
            .flatMap(instance -> jobExplorer
                .getJobExecutions(instance).stream()
                .max(Comparator.comparing(JobExecution::getCreateTime)));
    }

    // ── Print job execution summary ───────────────────────────────────────────
    public void printJobSummary(String jobName) {
        List<JobInstance> instances = jobExplorer.getJobInstances(jobName, 0, 5);
        instances.forEach(instance -> {
            List<JobExecution> executions = jobExplorer.getJobExecutions(instance);
            executions.forEach(exec -> {
                System.out.printf(
                    "Job: %-30s | Status: %-10s | Start: %s | End: %s%n",
                    jobName,
                    exec.getStatus(),
                    exec.getStartTime(),
                    exec.getEndTime()
                );
                exec.getStepExecutions().forEach(step ->
                    System.out.printf(
                        "  Step: %-25s | Read: %5d | Write: %5d | Skip: %3d | Status: %s%n",
                        step.getStepName(),
                        step.getReadCount(),
                        step.getWriteCount(),
                        step.getSkipCount(),
                        step.getStatus()
                    )
                );
            });
        });
    }

    // ── Restart a failed job ──────────────────────────────────────────────────
    public void restartFailedJob(String jobName) throws Exception {
        List<Long> failedIds = jobOperator.getJobInstances(jobName, 0, 10)
            .stream()
            .flatMap(id -> jobOperator.getExecutions(id).stream())
            .filter(id -> {
                JobExecution exec = jobExplorer.getJobExecution(id);
                return exec != null && exec.getStatus() == BatchStatus.FAILED;
            })
            .collect(Collectors.toList());

        for (Long execId : failedIds) {
            log.info("Restarting failed execution: {}", execId);
            jobOperator.restart(execId); // Restarts from last successful checkpoint
        }
    }
}
```

---

## 12. Listeners & Callbacks

### JobExecutionListener

```java
import org.springframework.batch.core.*;

@Component
public class JobExecutionAuditListener implements JobExecutionListener {

    private final AlertService alertService;
    private final MetricsService metricsService;

    @Override
    public void beforeJob(JobExecution jobExecution) {
        log.info("═══ JOB STARTING: {} | Parameters: {} ═══",
            jobExecution.getJobInstance().getJobName(),
            jobExecution.getJobParameters());

        metricsService.recordJobStart(
            jobExecution.getJobInstance().getJobName()
        );
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        String jobName = jobExecution.getJobInstance().getJobName();
        BatchStatus status = jobExecution.getStatus();
        Duration elapsed = Duration.between(
            jobExecution.getStartTime(),
            jobExecution.getEndTime()
        );

        log.info("═══ JOB FINISHED: {} | Status: {} | Duration: {}s ═══",
            jobName, status, elapsed.toSeconds());

        // Record metrics
        metricsService.recordJobCompletion(jobName, status, elapsed);

        // Alert on failure
        if (status == BatchStatus.FAILED) {
            String errors = jobExecution.getAllFailureExceptions()
                .stream()
                .map(Throwable::getMessage)
                .collect(Collectors.joining("; "));
            alertService.sendJobFailureAlert(jobName, errors);
        }
    }
}
```

---

### StepExecutionListener

```java
@Component
public class StepProgressListener implements StepExecutionListener {

    @Override
    public void beforeStep(StepExecution stepExecution) {
        log.info("  ─ Step STARTING: {}", stepExecution.getStepName());
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        log.info("  ─ Step FINISHED: {} | Read: {} | Write: {} | Skip: {} | Rollback: {}",
            stepExecution.getStepName(),
            stepExecution.getReadCount(),
            stepExecution.getWriteCount(),
            stepExecution.getSkipCount(),
            stepExecution.getRollbackCount()
        );

        // ✅ You can CHANGE the exit status here
        if (stepExecution.getWriteCount() == 0) {
            log.warn("No records written — returning NOOP");
            return new ExitStatus("NOOP"); // Can be used in flow decisions
        }

        return stepExecution.getExitStatus(); // Return unchanged
    }
}
```

---

### ItemReadListener, ItemProcessListener, ItemWriteListener

```java
@Component
public class ItemAuditListener<T>
        implements ItemReadListener<T>, ItemProcessListener<T, T>, ItemWriteListener<T> {

    // ── Read listeners ─────────────────────────────────────────────────────────
    @Override public void beforeRead() {}

    @Override
    public void afterRead(T item) {
        log.trace("Read: {}", item);
    }

    @Override
    public void onReadError(Exception ex) {
        log.error("Read error: {}", ex.getMessage());
        metricsService.incrementReadErrors();
    }

    // ── Process listeners ──────────────────────────────────────────────────────
    @Override public void beforeProcess(T item) {}

    @Override
    public void afterProcess(T item, T result) {
        if (result == null) {
            log.debug("Item filtered: {}", item);
        }
    }

    @Override
    public void onProcessError(T item, Exception ex) {
        log.error("Process error for item {}: {}", item, ex.getMessage());
    }

    // ── Write listeners ────────────────────────────────────────────────────────
    @Override
    public void beforeWrite(Chunk<? extends T> items) {
        log.debug("About to write {} items", items.size());
    }

    @Override
    public void afterWrite(Chunk<? extends T> items) {
        log.debug("Successfully wrote {} items", items.size());
    }

    @Override
    public void onWriteError(Exception ex, Chunk<? extends T> items) {
        log.error("Write error for {} items: {}", items.size(), ex.getMessage());
        metricsService.incrementWriteErrors(items.size());
    }
}
```

---

### Registering Listeners

```java
@Bean
public Step processUsersStep(JobRepository jobRepository,
                             PlatformTransactionManager txManager,
                             ItemReader<UserCsvRecord> reader,
                             ItemProcessor<UserCsvRecord, User> processor,
                             ItemWriter<User> writer,
                             StepProgressListener stepListener,
                             ItemAuditListener<Object> itemListener) {
    return new StepBuilder("processUsersStep", jobRepository)
        .<UserCsvRecord, User>chunk(100, txManager)
        .reader(reader)
        .processor(processor)
        .writer(writer)
        .listener(stepListener)    // StepExecutionListener
        .listener(itemListener)    // ItemRead/Process/WriteListener
        .build();
}

@Bean
public Job importUsersJob(JobRepository jobRepository,
                          Step step,
                          JobExecutionAuditListener jobListener) {
    return new JobBuilder("importUsersJob", jobRepository)
        .listener(jobListener)     // JobExecutionListener
        .start(step)
        .build();
}
```

---

## 13. Skip, Retry & Fault Tolerance

### Skip Policy

```java
import org.springframework.batch.core.step.skip.*;
import org.springframework.classify.BinaryExceptionClassifier;
import org.springframework.retry.RetryPolicy;

@Configuration
public class FaultToleranceConfig {

    // ── Skip: ignore individual bad records ───────────────────────────────────
    @Bean
    public Step faultTolerantStep(JobRepository jobRepository,
                                  PlatformTransactionManager txManager,
                                  ItemReader<UserCsvRecord> reader,
                                  ItemProcessor<UserCsvRecord, User> processor,
                                  ItemWriter<User> writer) {
        return new StepBuilder("faultTolerantStep", jobRepository)
            .<UserCsvRecord, User>chunk(100, txManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)

            // ── SKIP policy ────────────────────────────────────────────────────
            .faultTolerant()
            .skip(ValidationException.class)    // Skip on validation errors
            .skip(ParseException.class)          // Skip on parse errors
            .noSkip(DatabaseConstraintException.class) // Never skip DB errors
            .skipLimit(50)                       // Fail job after 50 total skips
            // skipLimit(Integer.MAX_VALUE) → unlimited skips

            // ── RETRY policy ───────────────────────────────────────────────────
            .retry(TransientDataAccessException.class) // Retry on transient DB errors
            .retry(ResourceAccessException.class)       // Retry on network errors
            .noRetry(ValidationException.class)         // Don't retry logic errors
            .retryLimit(3)                              // Max 3 attempts per item

            // ── SKIP LISTENERS ─────────────────────────────────────────────────
            .listener(skipListener())

            .build();
    }

    // ── Skip Listener — log/handle skipped items ──────────────────────────────
    @Bean
    public SkipListener<UserCsvRecord, User> skipListener() {
        return new SkipListener<>() {

            @Override
            public void onSkipInRead(Throwable t) {
                log.warn("Skipped during READ: {}", t.getMessage());
                deadLetterService.recordSkippedRead(t);
            }

            @Override
            public void onSkipInProcess(UserCsvRecord item, Throwable t) {
                log.warn("Skipped during PROCESS: item={}, error={}", item, t.getMessage());
                deadLetterService.recordSkippedItem(item, t.getMessage());
            }

            @Override
            public void onSkipInWrite(User item, Throwable t) {
                log.warn("Skipped during WRITE: item={}, error={}", item, t.getMessage());
                deadLetterService.recordSkippedWrite(item, t.getMessage());
            }
        };
    }

    // ── Custom SkipPolicy ─────────────────────────────────────────────────────
    @Bean
    public SkipPolicy customSkipPolicy() {
        return (Throwable t, long skipCount) -> {
            if (skipCount > 100) {
                return false; // Too many skips — fail the job
            }
            if (t instanceof ValidationException) {
                return true;  // Always skip validation errors
            }
            if (t instanceof DatabaseException) {
                return false; // Never skip DB errors
            }
            return t instanceof RuntimeException; // Skip other runtime errors
        };
    }
}
```

---

### Retry with Backoff

```java
import org.springframework.retry.backoff.*;

@Bean
public Step retryWithBackoffStep(JobRepository jobRepository,
                                 PlatformTransactionManager txManager,
                                 ItemReader<Order> reader,
                                 ItemProcessor<Order, ProcessedOrder> processor,
                                 ItemWriter<ProcessedOrder> writer) {
    return new StepBuilder("retryStep", jobRepository)
        .<Order, ProcessedOrder>chunk(50, txManager)
        .reader(reader)
        .processor(processor)
        .writer(writer)
        .faultTolerant()
        .retry(ServiceUnavailableException.class)
        .retry(HttpServerErrorException.class)
        .retryLimit(5)

        // ── Exponential backoff: wait 1s, 2s, 4s, 8s between retries ──────────
        .backOffPolicy(new ExponentialBackOffPolicy() {{
            setInitialInterval(1000L);  // 1 second
            setMultiplier(2.0);         // Double each time
            setMaxInterval(30_000L);    // Cap at 30 seconds
        }})

        .build();
}
```

---

## 14. Conditional Flow & Decision

### Step Flow Control

```java
import org.springframework.batch.core.job.flow.*;

@Configuration
public class ConditionalFlowConfig {

    // ── Basic on-condition transitions ────────────────────────────────────────
    @Bean
    public Job conditionalJob(JobRepository jobRepository,
                              Step loadStep,
                              Step processStep,
                              Step errorNotificationStep,
                              Step archiveStep) {
        return new JobBuilder("conditionalJob", jobRepository)
            .start(loadStep)
                .on("FAILED").to(errorNotificationStep) // If loadStep FAILS
                .on("NOOP").end()                        // If nothing to process
                .on("*").to(processStep)                 // Any other status → processStep
            .from(processStep)
                .on("FAILED").to(errorNotificationStep)
                .on("*").to(archiveStep)
            .from(errorNotificationStep)
                .on("*").fail()                          // End job with FAILED status
            .from(archiveStep)
                .on("*").end()                           // End job with COMPLETED status
            .end()
            .build();
    }

    // ── JobExecutionDecider — programmatic decision ────────────────────────────
    @Bean
    public JobExecutionDecider recordCountDecider() {
        return (jobExecution, stepExecution) -> {
            // Decide which path based on step execution context
            int recordCount = stepExecution != null
                ? stepExecution.getReadCount()
                : 0;

            if (recordCount == 0) {
                return new FlowExecutionStatus("EMPTY");       // Custom status
            } else if (recordCount > 100_000) {
                return new FlowExecutionStatus("LARGE_BATCH"); // Custom status
            }
            return FlowExecutionStatus.COMPLETED;              // Standard status
        };
    }

    @Bean
    public Job jobWithDecision(JobRepository jobRepository,
                               Step readStep,
                               Step smallBatchStep,
                               Step largeBatchStep,
                               Step emptyInputStep) {
        return new JobBuilder("jobWithDecision", jobRepository)
            .start(readStep)
            .next(recordCountDecider())       // Decision node
                .on("EMPTY").to(emptyInputStep)
                .on("LARGE_BATCH").to(largeBatchStep)
                .on("*").to(smallBatchStep)
            .end()
            .build();
    }
}
```

---

## 15. Parallel & Scaling Strategies

### Strategy 1: Parallel Steps (Independent Steps Run Concurrently)

```java
import org.springframework.batch.core.job.flow.*;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

@Configuration
public class ParallelStepsConfig {

    // Run Step A and Step B in parallel, then Step C when both done
    @Bean
    public Job parallelStepsJob(JobRepository jobRepository,
                                Step stepA, Step stepB, Step stepC) {

        // Step A: Process orders
        Flow flowA = new FlowBuilder<SimpleFlow>("flowA")
            .start(stepA)
            .build();

        // Step B: Process payments (independent of orders)
        Flow flowB = new FlowBuilder<SimpleFlow>("flowB")
            .start(stepB)
            .build();

        // Parallel split: run flowA and flowB concurrently
        Flow parallelFlow = new FlowBuilder<SimpleFlow>("parallelFlow")
            .split(new SimpleAsyncTaskExecutor())
            .add(flowA, flowB)
            .build();

        return new JobBuilder("parallelStepsJob", jobRepository)
            .start(parallelFlow)
            .next(stepC)  // Runs after BOTH flowA and flowB complete
            .end()
            .build();
    }
}
```

---

### Strategy 2: Multi-Threaded Step (Concurrent Chunk Processing)

```java
import org.springframework.core.task.*;
import org.springframework.scheduling.concurrent.*;

@Configuration
public class MultiThreadedStepConfig {

    @Bean
    public TaskExecutor batchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("batch-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    @Bean
    public Step multiThreadedStep(JobRepository jobRepository,
                                  PlatformTransactionManager txManager,
                                  ItemReader<Order> reader,       // MUST be thread-safe!
                                  ItemProcessor<Order, Order> processor,
                                  ItemWriter<Order> writer) {
        return new StepBuilder("multiThreadedStep", jobRepository)
            .<Order, Order>chunk(100, txManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .taskExecutor(batchTaskExecutor()) // Each chunk runs in a separate thread
            .throttleLimit(4)                  // Max 4 concurrent chunks (deprecated in 5.x)
            .build();
    }

    // ⚠️ IMPORTANT: FlatFileItemReader and JdbcCursorItemReader are NOT thread-safe!
    // For multi-threaded steps, use:
    //   ✅ SynchronizedItemStreamReader<T> wrapper
    //   ✅ JdbcPagingItemReader (thread-safe by design)

    @Bean
    @StepScope
    public SynchronizedItemStreamReader<Order> threadSafeReader(
            JdbcCursorItemReader<Order> delegate) {
        SynchronizedItemStreamReader<Order> reader = new SynchronizedItemStreamReader<>();
        reader.setDelegate(delegate);
        return reader;
    }
}
```

---

## 16. Partitioning

**Partitioning** divides data into independent slices, processes each slice in a separate step (on same or different JVMs).

```java
import org.springframework.batch.core.partition.*;
import org.springframework.batch.core.partition.support.*;
import org.springframework.batch.item.ExecutionContext;

// ── Custom Partitioner — divides data into ranges ─────────────────────────────
@Component
public class RangePartitioner implements Partitioner {

    private final JdbcTemplate jdbcTemplate;

    public RangePartitioner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        // Get min and max IDs from the table
        Long minId = jdbcTemplate.queryForObject("SELECT MIN(id) FROM orders", Long.class);
        Long maxId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM orders", Long.class);

        if (minId == null || maxId == null) {
            return Map.of("partition0", new ExecutionContext());
        }

        long rangeSize = (maxId - minId) / gridSize + 1;
        Map<String, ExecutionContext> partitions = new HashMap<>();

        for (int i = 0; i < gridSize; i++) {
            long start = minId + (rangeSize * i);
            long end   = Math.min(start + rangeSize - 1, maxId);

            ExecutionContext ctx = new ExecutionContext();
            ctx.putLong("minId", start);
            ctx.putLong("maxId", end);
            ctx.putString("partitionId", "partition" + i);

            partitions.put("partition" + i, ctx);
            log.info("Partition {}: ids {} to {}", i, start, end);
        }

        return partitions;
    }
}

// ── Partition-aware Reader ─────────────────────────────────────────────────────
@Bean
@StepScope
public JdbcPagingItemReader<Order> partitionedOrderReader(
        DataSource dataSource,
        @Value("#{stepExecutionContext['minId']}") Long minId,
        @Value("#{stepExecutionContext['maxId']}") Long maxId) {

    return new JdbcPagingItemReaderBuilder<Order>()
        .name("partitionedOrderReader")
        .dataSource(dataSource)
        .selectClause("SELECT *")
        .fromClause("FROM orders")
        .whereClause("WHERE id BETWEEN :minId AND :maxId")
        .sortKeys(Map.of("id", Order.ASCENDING))
        .parameterValues(Map.of("minId", minId, "maxId", maxId))
        .pageSize(200)
        .rowMapper(orderRowMapper())
        .build();
}

// ── Partition Step Configuration ──────────────────────────────────────────────
@Configuration
public class PartitioningConfig {

    @Bean
    public Step workerStep(JobRepository jobRepository,
                           PlatformTransactionManager txManager,
                           ItemReader<Order> reader,
                           ItemProcessor<Order, ProcessedOrder> processor,
                           ItemWriter<ProcessedOrder> writer) {
        return new StepBuilder("workerStep", jobRepository)
            .<Order, ProcessedOrder>chunk(200, txManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .build();
    }

    @Bean
    public Step partitionedMasterStep(JobRepository jobRepository,
                                      Step workerStep,
                                      RangePartitioner partitioner) {
        return new StepBuilder("masterStep", jobRepository)
            .partitioner(workerStep.getName(), partitioner)
            .step(workerStep)
            .gridSize(8)                           // 8 partitions
            .taskExecutor(partitionTaskExecutor()) // Local multi-threading
            .build();
    }

    @Bean
    public TaskExecutor partitionTaskExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(8);
        exec.setMaxPoolSize(8);
        exec.initialize();
        return exec;
    }
}
```

---

## 17. Scheduling & Triggering Jobs

### Trigger via `@Scheduled`

```java
@Component
@EnableScheduling
public class BatchJobScheduler {

    private final JobLauncher asyncJobLauncher;
    private final Job dailyImportJob;
    private final Job monthlyReportJob;

    public BatchJobScheduler(
            @Qualifier("asyncJobLauncher") JobLauncher asyncJobLauncher,
            Job dailyImportJob,
            Job monthlyReportJob) {
        this.asyncJobLauncher = asyncJobLauncher;
        this.dailyImportJob   = dailyImportJob;
        this.monthlyReportJob = monthlyReportJob;
    }

    // Daily import at 01:00 AM
    @Scheduled(cron = "0 0 1 * * ?")
    public void runDailyImport() throws Exception {
        JobParameters params = new JobParametersBuilder()
            .addLocalDate("date", LocalDate.now())
            .addLocalDateTime("runAt", LocalDateTime.now())
            .toJobParameters();

        JobExecution execution = asyncJobLauncher.run(dailyImportJob, params);
        log.info("Daily import started: executionId={}", execution.getId());
    }

    // Monthly report on 1st of month at 03:00 AM
    @Scheduled(cron = "0 0 3 1 * ?")
    public void runMonthlyReport() throws Exception {
        JobParameters params = new JobParametersBuilder()
            .addLocalDate("month", LocalDate.now().withDayOfMonth(1))
            .toJobParameters();

        asyncJobLauncher.run(monthlyReportJob, params);
    }
}

// ── Async JobLauncher (non-blocking — returns immediately) ────────────────────
@Configuration
public class JobLauncherConfig {

    @Bean(name = "asyncJobLauncher")
    public JobLauncher asyncJobLauncher(JobRepository jobRepository) {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(new SimpleAsyncTaskExecutor()); // Async!
        launcher.afterPropertiesSet();
        return launcher;
    }
}
```

---

### Trigger via REST Endpoint

```java
@RestController
@RequestMapping("/api/batch")
public class BatchJobController {

    private final JobLauncher jobLauncher;
    private final Job importUsersJob;
    private final BatchMonitoringService monitor;

    @PostMapping("/jobs/import-users")
    public ResponseEntity<Map<String, Object>> triggerImport(
            @RequestBody ImportJobRequest request) throws Exception {

        JobParameters params = new JobParametersBuilder()
            .addString("filePath",  request.filePath())
            .addString("requestedBy", request.requestedBy())
            .addLocalDateTime("startedAt", LocalDateTime.now())
            .toJobParameters();

        JobExecution execution = jobLauncher.run(importUsersJob, params);

        return ResponseEntity.accepted().body(Map.of(
            "jobExecutionId", execution.getId(),
            "jobName",        execution.getJobInstance().getJobName(),
            "status",         execution.getStatus().toString()
        ));
    }

    @GetMapping("/jobs/{executionId}/status")
    public ResponseEntity<Map<String, Object>> getStatus(
            @PathVariable Long executionId) {

        return monitor.getExecution(executionId)
            .map(exec -> ResponseEntity.ok(Map.of(
                "status",       exec.getStatus(),
                "startTime",    exec.getStartTime(),
                "endTime",      exec.getEndTime(),
                "readCount",    exec.getStepExecutions().stream()
                    .mapToLong(StepExecution::getReadCount).sum(),
                "writeCount",   exec.getStepExecutions().stream()
                    .mapToLong(StepExecution::getWriteCount).sum()
            )))
            .orElse(ResponseEntity.notFound().build());
    }

    record ImportJobRequest(String filePath, String requestedBy) {}
}
```

---

## 18. Testing Spring Batch

```java
import org.springframework.batch.test.*;
import org.springframework.batch.test.context.SpringBatchTest;

// ── Integration test: full job ─────────────────────────────────────────────────
@SpringBatchTest          // Adds JobLauncherTestUtils + JobRepositoryTestUtils
@SpringBootTest
@ActiveProfiles("test")
class ImportUsersJobIntegrationTest {

    @Autowired JobLauncherTestUtils jobLauncherTestUtils;
    @Autowired JobRepositoryTestUtils jobRepositoryTestUtils;
    @Autowired UserRepository userRepository;

    @BeforeEach
    void cleanMetadata() {
        jobRepositoryTestUtils.removeJobExecutions(); // Clean batch tables between tests
    }

    @Test
    void importUsersJob_WithValidCsv_ShouldImportAllRecords() throws Exception {
        // Launch the full job
        JobExecution execution = jobLauncherTestUtils.launchJob(
            new JobParametersBuilder()
                .addString("filePath", "classpath:test-data/users.csv")
                .addLocalDateTime("startTime", LocalDateTime.now())
                .toJobParameters()
        );

        // Assert job completed
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");

        // Assert data was imported
        assertThat(userRepository.count()).isEqualTo(5L);
    }

    @Test
    void importUsersJob_WithBadRecords_ShouldSkipAndComplete() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(
            new JobParametersBuilder()
                .addString("filePath", "classpath:test-data/users-with-errors.csv")
                .addLocalDateTime("startTime", LocalDateTime.now())
                .toJobParameters()
        );

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Check step execution for skip counts
        StepExecution stepExec = execution.getStepExecutions().iterator().next();
        assertThat(stepExec.getWriteCount()).isEqualTo(3);
        assertThat(stepExec.getSkipCount()).isEqualTo(2);
    }
}

// ── Unit test: single step ────────────────────────────────────────────────────
@SpringBatchTest
@SpringBootTest
class ProcessUsersStepTest {

    @Autowired JobLauncherTestUtils jobLauncherTestUtils;

    @Test
    void processUsersStep_ShouldTransformAndSave() throws Exception {
        // Test only one specific step (not the full job)
        JobExecution execution = jobLauncherTestUtils.launchStep(
            "processUsersStep",
            new JobParametersBuilder()
                .addString("filePath", "classpath:test-data/small-users.csv")
                .addLocalDateTime("t", LocalDateTime.now())
                .toJobParameters()
        );

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        StepExecution stepExec = JobRepositoryTestUtils.getStepExecution(
            execution, "processUsersStep");
        assertThat(stepExec.getReadCount()).isEqualTo(3);
        assertThat(stepExec.getWriteCount()).isEqualTo(3);
    }
}

// ── Unit test: processor / reader / writer ────────────────────────────────────
@ExtendWith(MockitoExtension.class)
class UserCsvToEntityProcessorTest {

    @Mock DepartmentRepository departmentRepo;
    @Mock PasswordEncoder passwordEncoder;

    @InjectMocks UserCsvToEntityProcessor processor;

    @Test
    void process_WithValidRecord_ShouldReturnUser() throws Exception {
        UserCsvRecord record = new UserCsvRecord(
            "Alice", "Smith", "alice@corp.com", "Engineering", 85000.0);

        when(departmentRepo.findByName("Engineering"))
            .thenReturn(Optional.of(new Department("Engineering")));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-password");

        User result = processor.process(record);

        assertThat(result).isNotNull();
        assertThat(result.getEmail()).isEqualTo("alice@corp.com");
        assertThat(result.getFirstName()).isEqualTo("Alice");
    }

    @Test
    void process_WithInvalidEmail_ShouldReturnNull() throws Exception {
        UserCsvRecord record = new UserCsvRecord(
            "Bob", "Bad", "not-an-email", "Engineering", 72000.0);

        User result = processor.process(record);

        assertThat(result).isNull(); // Filtered
    }
}
```

---

## 19. Real-World Complete Example

### Monthly Payroll Processing Job

```java
// ══════════════════════════════════════════════════════════════════════════════
// SCENARIO: Process monthly payroll for all active employees
// Steps:
//   1. Validate prerequisites (check no duplicate run for this month)
//   2. Load employees → Calculate salaries → Save to payroll table  [CHUNK]
//   3. Generate payroll report CSV                                   [CHUNK]
//   4. Send payslip emails to all employees                         [CHUNK]
//   5. Archive processed files                                       [TASKLET]
// ══════════════════════════════════════════════════════════════════════════════

// ── Domain ────────────────────────────────────────────────────────────────────
public record PayrollEntry(
    Long employeeId, String employeeName, String email,
    BigDecimal grossSalary, BigDecimal taxAmount,
    BigDecimal netSalary, YearMonth payPeriod
) {}

// ── Step 1: Validate ──────────────────────────────────────────────────────────
@Component
@StepScope
public class ValidatePayrollRunTasklet implements Tasklet {

    private final PayrollRepository payrollRepo;

    @Value("#{jobParameters['payPeriod']}") private String payPeriod;

    @Override
    public RepeatStatus execute(StepContribution c, ChunkContext ctx) {
        YearMonth period = YearMonth.parse(payPeriod);
        if (payrollRepo.existsByPayPeriod(period)) {
            throw new JobExecutionException(
                "Payroll already processed for: " + period);
        }
        log.info("Payroll validation passed for period: {}", period);
        return RepeatStatus.FINISHED;
    }
}

// ── Step 2: Load, Calculate, Save (Chunk) ─────────────────────────────────────
@Bean
@StepScope
public JdbcPagingItemReader<Employee> activeEmployeeReader(DataSource ds) {
    return new JdbcPagingItemReaderBuilder<Employee>()
        .name("activeEmployeeReader")
        .dataSource(ds)
        .selectClause("SELECT id, name, email, base_salary, tax_rate, department_id")
        .fromClause("FROM employees")
        .whereClause("WHERE status = 'ACTIVE'")
        .sortKeys(Map.of("id", Order.ASCENDING))
        .pageSize(500)
        .rowMapper((rs, row) -> new Employee(
            rs.getLong("id"), rs.getString("name"),
            rs.getString("email"), rs.getBigDecimal("base_salary"),
            rs.getBigDecimal("tax_rate"), rs.getLong("department_id")))
        .build();
}

@Component
@StepScope
public class SalaryCalculationProcessor implements ItemProcessor<Employee, PayrollEntry> {

    @Value("#{jobParameters['payPeriod']}") private String payPeriod;

    @Override
    public PayrollEntry process(Employee emp) {
        BigDecimal gross = emp.getBaseSalary();
        BigDecimal tax   = gross.multiply(emp.getTaxRate()).setScale(2, HALF_UP);
        BigDecimal net   = gross.subtract(tax);
        return new PayrollEntry(
            emp.getId(), emp.getName(), emp.getEmail(),
            gross, tax, net, YearMonth.parse(payPeriod)
        );
    }
}

@Bean
public JdbcBatchItemWriter<PayrollEntry> payrollWriter(DataSource ds) {
    return new JdbcBatchItemWriterBuilder<PayrollEntry>()
        .dataSource(ds)
        .sql("""
            INSERT INTO payroll (employee_id, employee_name, email,
                gross_salary, tax_amount, net_salary, pay_period)
            VALUES (:employeeId, :employeeName, :email,
                :grossSalary, :taxAmount, :netSalary, :payPeriod)
            """)
        .beanMapped()
        .build();
}

// ── Step 3: Generate Report CSV (Chunk) ───────────────────────────────────────
@Bean
@StepScope
public JdbcPagingItemReader<PayrollEntry> payrollReportReader(
        DataSource ds,
        @Value("#{jobParameters['payPeriod']}") String payPeriod) {
    return new JdbcPagingItemReaderBuilder<PayrollEntry>()
        .name("payrollReportReader").dataSource(ds)
        .selectClause("SELECT *").fromClause("FROM payroll")
        .whereClause("WHERE pay_period = :period")
        .parameterValues(Map.of("period", payPeriod))
        .sortKeys(Map.of("employee_id", Order.ASCENDING))
        .pageSize(500)
        .rowMapper((rs, i) -> new PayrollEntry(
            rs.getLong("employee_id"), rs.getString("employee_name"),
            rs.getString("email"), rs.getBigDecimal("gross_salary"),
            rs.getBigDecimal("tax_amount"), rs.getBigDecimal("net_salary"),
            YearMonth.parse(payPeriod)))
        .build();
}

// ── Job Assembly ──────────────────────────────────────────────────────────────
@Configuration
public class PayrollJobConfig {

    @Bean
    public Job monthlyPayrollJob(JobRepository jobRepository,
            Step validateStep, Step calculatePayrollStep,
            Step generateReportStep, Step sendEmailsStep,
            Step archiveStep,
            JobExecutionAuditListener listener) {

        return new JobBuilder("monthlyPayrollJob", jobRepository)
            .listener(listener)
            .start(validateStep)
                .on("FAILED").fail()
                .on("*").to(calculatePayrollStep)
            .from(calculatePayrollStep)
                .on("FAILED").to(archiveStep).on("*").fail()
                .on("*").to(generateReportStep)
            .from(generateReportStep)
                .on("*").to(sendEmailsStep)
            .from(sendEmailsStep)
                .on("*").to(archiveStep)
            .from(archiveStep)
                .on("*").end()
            .end()
            .build();
    }

    @Bean
    public Step calculatePayrollStep(JobRepository jr,
            PlatformTransactionManager tx,
            JdbcPagingItemReader<Employee> reader,
            SalaryCalculationProcessor processor,
            JdbcBatchItemWriter<PayrollEntry> writer,
            StepProgressListener stepListener) {
        return new StepBuilder("calculatePayrollStep", jr)
            .<Employee, PayrollEntry>chunk(500, tx)
            .reader(reader).processor(processor).writer(writer)
            .listener(stepListener)
            .faultTolerant()
            .skip(RuntimeException.class).skipLimit(10)
            .build();
    }
}

// ── Launcher ──────────────────────────────────────────────────────────────────
@RestController
@RequestMapping("/api/payroll")
public class PayrollJobController {

    private final JobLauncher jobLauncher;
    private final Job monthlyPayrollJob;

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runPayroll(
            @RequestParam String payPeriod,
            @AuthenticationPrincipal UserDetails user) throws Exception {

        JobParameters params = new JobParametersBuilder()
            .addString("payPeriod",    payPeriod)
            .addString("initiatedBy",  user.getUsername(), false)
            .addLocalDateTime("startedAt", LocalDateTime.now())
            .toJobParameters();

        JobExecution exec = jobLauncher.run(monthlyPayrollJob, params);

        return ResponseEntity.accepted().body(Map.of(
            "executionId", exec.getId(),
            "status",      exec.getStatus().toString(),
            "message",     "Payroll job started for period: " + payPeriod
        ));
    }
}
```

---

## 20. Interview Questions & Answers

| # | Question | Answer |
|---|----------|--------|
| 1 | What is Spring Batch and when should you use it? | Spring Batch is a framework for large-scale batch processing. Use it for: bulk data processing, ETL pipelines, end-of-day reports, data migrations. It provides retry, skip, restart, and transaction management out of the box. |
| 2 | What is the difference between a Job, JobInstance, and JobExecution? | `Job` is the configuration/definition. `JobInstance` is one logical run (identified by Job name + parameters). `JobExecution` is one actual attempt to run a `JobInstance`. A failed `JobInstance` can be restarted; a completed one cannot. |
| 3 | What is chunk-oriented processing? | Items are read one at a time, processed one at a time, but written together as a chunk (batch). One transaction covers one chunk — failure rolls back only that chunk. Enables efficient bulk writes and restartability. |
| 4 | What is `@StepScope`? | A Spring Batch scope that delays bean creation until the step starts. Enables late binding of job/step parameters via `@Value("#{jobParameters['key']}")`. One bean instance per step execution. |
| 5 | When would you use a Tasklet instead of a chunk step? | For single operations that don't fit read-process-write: archiving files, sending one summary email, calling an external API once, clearing a table, or any setup/teardown operation. |
| 6 | What happens when a chunk fails? | The current chunk's transaction is rolled back. The step records the failure. Depending on fault tolerance config: the job may fail, retry the chunk, or skip individual items. Previously committed chunks are NOT rolled back. |
| 7 | How does Spring Batch support restart? | It persists step execution state (including `ExecutionContext`) to the `JobRepository`. On restart, each step checks if it was already completed and resumes from the last committed checkpoint. `ItemReader`s must be `ItemStream` to save/restore their position. |
| 8 | What is the difference between `skip` and `retry`? | `skip`: when an item causes an error, ignore it and continue (count skips). `retry`: when an error is transient (network, DB timeout), try the same item/chunk again up to `retryLimit` times before skipping or failing. |
| 9 | What is `ExecutionContext` used for? | A serializable key-value store (like a Map) scoped to a Job or Step execution. Used to pass data between steps (Job context) or save reader/writer position for restartability (Step context). Persisted to DB by `JobRepository`. |
| 10 | How do you run a job with unique parameters each time? | Add a timestamp or UUID to `JobParameters`: `addLocalDateTime("startedAt", LocalDateTime.now())`. This creates a new `JobInstance` each time, allowing the job to run repeatedly. |
| 11 | What is the difference between `JdbcCursorItemReader` and `JdbcPagingItemReader`? | `JdbcCursorItemReader`: opens one DB cursor and streams rows (memory-efficient, NOT thread-safe, requires single connection). `JdbcPagingItemReader`: fetches data in SQL pages (thread-safe, restartable, works with read replicas). |
| 12 | What happens when `ItemProcessor` returns `null`? | The item is filtered (not passed to the `ItemWriter`) and the `filter_count` in `StepExecution` is incremented. No error is thrown — it's a normal filtering mechanism. |
| 13 | How do you run steps in parallel? | Use `FlowBuilder.split(taskExecutor).add(flow1, flow2)` to create a split flow where independent step flows run concurrently. Or use a multi-threaded step with `StepBuilder.taskExecutor()` for concurrent chunk processing. |
| 14 | What is partitioning in Spring Batch? | Divides the data into N independent slices (`ExecutionContext` per partition). A master step coordinates; worker steps process each partition (locally in threads or remotely in separate JVMs with Spring Batch Remote Partitioning). |
| 15 | What is `CompositeItemProcessor`? | Chains multiple `ItemProcessor` implementations. Each processor's output is the next processor's input. If any processor returns `null`, the item is filtered. Enables separation of transformation concerns. |
| 16 | How does Spring Batch prevent duplicate job runs? | By checking the `JobRepository`. If a `JobInstance` (same job name + identifying params) with status `COMPLETED` exists, Spring Batch throws `JobInstanceAlreadyCompleteException`. Failed instances can be restarted. |
| 17 | What tables does Spring Batch create? | `BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION`, `BATCH_JOB_EXECUTION_PARAMS`, `BATCH_JOB_EXECUTION_CONTEXT`, `BATCH_STEP_EXECUTION`, `BATCH_STEP_EXECUTION_CONTEXT`, `BATCH_JOB_SEQ`, `BATCH_STEP_EXECUTION_SEQ`. |
| 18 | What is the default transaction isolation in Spring Batch? | `READ_COMMITTED` for most DBs. Spring Batch uses `SERIALIZABLE` for `JobRepository` operations to prevent concurrent duplicate job instances. Chunk processing uses the step's configured transaction manager. |
| 19 | How do you test a Spring Batch job? | Use `@SpringBatchTest` which provides `JobLauncherTestUtils` (to launch job or single step) and `JobRepositoryTestUtils` (to clean metadata between tests). Use `@DataJpaTest` or embedded H2 for data layer. |
| 20 | What is `ItemStream` and why does it matter? | Interface with `open()`, `update()`, `close()` methods. `ItemReader`/`ItemWriter` implementing it can save/restore their state in `ExecutionContext`. Essential for restartability — allows a reader to resume from where it left off after a failure. |

---

## 21. Complete Reference Summary

### Spring Batch Component Quick Reference

```
JOB LAYER
  Job                    → Batch process definition; ordered Steps
  JobInstance            → Job + unique parameters = one logical run
  JobExecution           → One attempt to run a JobInstance
  JobParameters          → Identify a JobInstance (String/Long/Double/Date)
  JobLauncher            → Executes a Job with given parameters
  JobRepository          → Persists all metadata to DB
  JobExplorer            → Read-only access to JobRepository
  JobOperator            → Operations: restart, stop, abandon

STEP LAYER
  Step                   → One phase of a Job (chunk or tasklet)
  StepExecution          → One attempt to run a Step
  ExecutionContext        → Key-value state store (Job or Step scoped)
  StepContribution       → Tracks read/write/skip counts for a step

CHUNK PROCESSING
  ItemReader<T>          → Reads one item at a time; null = end of input
  ItemProcessor<I,O>     → Transforms one item; null = filter item
  ItemWriter<T>          → Writes a Chunk<T> (list); one TX per chunk
  Chunk<T>               → List of processed items for one write TX
  ItemStream             → open/update/close for restartable readers/writers

BUILT-IN READERS
  FlatFileItemReader     → CSV, fixed-width, delimited files
  StaxEventItemReader    → XML files (StAX streaming)
  JsonItemReader         → JSON files
  JdbcCursorItemReader   → DB cursor (not thread-safe)
  JdbcPagingItemReader   → DB pages (thread-safe, restartable)
  JpaPagingItemReader    → JPA paging queries
  MongoItemReader        → MongoDB cursors

BUILT-IN WRITERS
  FlatFileItemWriter     → CSV/text output files
  StaxEventItemWriter    → XML output
  JsonFileItemWriter     → JSON output
  JdbcBatchItemWriter    → JDBC batch INSERT/UPDATE
  JpaItemWriter          → JPA EntityManager merge
  MongoItemWriter        → MongoDB writes

FAULT TOLERANCE
  .faultTolerant()       → Enable skip/retry
  .skip(Ex.class)        → Skip items that throw this exception
  .skipLimit(N)          → Max total skips before job fails
  .retry(Ex.class)       → Retry item/chunk on this exception
  .retryLimit(N)         → Max retries per item
  .backOffPolicy(...)    → Wait strategy between retries
  SkipListener           → Called on each skipped item
  SkipPolicy             → Custom skip decision logic

SCOPE ANNOTATIONS
  @JobScope              → One bean per JobExecution
  @StepScope             → One bean per StepExecution (most common)
                           Enables #{jobParameters['key']} SpEL

LISTENERS
  JobExecutionListener   → beforeJob / afterJob
  StepExecutionListener  → beforeStep / afterStep (can change ExitStatus)
  ItemReadListener       → beforeRead / afterRead / onReadError
  ItemProcessListener    → beforeProcess / afterProcess / onProcessError
  ItemWriteListener      → beforeWrite / afterWrite / onWriteError
  SkipListener           → onSkipInRead / onSkipInProcess / onSkipInWrite
  ChunkListener          → beforeChunk / afterChunk / afterChunkError

SCALING
  Multi-threaded Step    → Concurrent chunks via TaskExecutor
  Parallel Steps         → FlowBuilder.split(executor).add(flows)
  Partitioning (local)   → Master/worker with ThreadPoolTaskExecutor
  Partitioning (remote)  → Master/worker across JVMs (Spring Cloud Task)
  AsyncItemProcessor     → Wrap processor for async execution
  AsyncItemWriter        → Write async processor futures

FLOW CONTROL
  .on("STATUS")          → Transition to next step based on exit status
  .to(step)              → Go to this step
  .fail()                → End job with FAILED status
  .end()                 → End job with COMPLETED status
  .stopAndRestart(step)  → Stop job; on restart resume from this step
  JobExecutionDecider    → Programmatic branching logic
```

### Chunk Size Guidelines

```
Item Size       Recommended Chunk  Notes
────────────────────────────────────────────────────────
< 1KB           500–1000           Simple rows, lightweight objects
1KB – 10KB      100–500            Typical DB records with relations
10KB – 100KB    10–100             Medium XML/JSON documents
> 100KB         1–10               Large documents, use with care
With retries    Keep small (10–50) Smaller chunks = less re-work on retry
```

### Key Design Rules

```
✅ Always use @StepScope for readers/writers with job parameters
✅ Set Xms=Xmx to avoid heap resize during batch runs
✅ Use JdbcPagingItemReader (not Cursor) for multi-threaded steps
✅ Implement ItemStream on custom readers for restartability
✅ Use CompositeItemWriter to write to multiple destinations atomically
✅ Keep chunk size under 500 when using skip/retry
✅ Add a unique parameter (timestamp) to run the same job multiple times
✅ Use @ConditionalOnProperty to disable batch on startup in tests
✅ Log read/write counts in afterStep listener for observability
✅ Test with @SpringBatchTest — never use production DB for unit tests
```

---

*Made with ❤️ for Java developers — covers Spring Batch 5.x / Spring Boot 3.x*
