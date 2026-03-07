# 🌱 Spring Boot Core Annotations — Deep Dive Complete Guide

> Complete reference for Spring Boot annotations — Spring Boot 3.x / Spring Framework 6.x

---

## 📌 Table of Contents

1. [Spring Boot Bootstrap Annotations](#1-spring-boot-bootstrap-annotations)
2. [Stereotype Annotations](#2-stereotype-annotations)
3. [Dependency Injection Annotations](#3-dependency-injection-annotations)
4. [Configuration Annotations](#4-configuration-annotations)
5. [Web / REST Annotations](#5-web--rest-annotations)
6. [Request Mapping Annotations](#6-request-mapping-annotations)
7. [Request Data Annotations](#7-request-data-annotations)
8. [Response Annotations](#8-response-annotations)
9. [Data / JPA Annotations](#9-data--jpa-annotations)
10. [Validation Annotations](#10-validation-annotations)
11. [Transaction Annotations](#11-transaction-annotations)
12. [Scheduling & Async Annotations](#12-scheduling--async-annotations)
13. [Caching Annotations](#13-caching-annotations)
14. [Security Annotations](#14-security-annotations)
15. [Testing Annotations](#15-testing-annotations)
16. [Conditional Annotations](#16-conditional-annotations)
17. [Event & Lifecycle Annotations](#17-event--lifecycle-annotations)
18. [AOP Annotations](#18-aop-annotations)
19. [Real-World Complete Example](#19-real-world-complete-example)
20. [Interview Questions & Answers](#20-interview-questions--answers)
21. [Complete Reference Summary](#21-complete-reference-summary)

---

## 1. Spring Boot Bootstrap Annotations

### `@SpringBootApplication`

The **single most important annotation** — placed on the main class. It is a meta-annotation combining three annotations:

```
@SpringBootApplication
    ├── @SpringBootConfiguration   (marks this as a configuration class)
    ├── @EnableAutoConfiguration   (triggers Spring Boot auto-config magic)
    └── @ComponentScan             (scans current package + sub-packages for beans)
```

```java
package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// ✅ Standard usage
@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}

// ✅ Exclude specific auto-configuration classes
@SpringBootApplication(exclude = {
    DataSourceAutoConfiguration.class,        // No DB auto-config
    SecurityAutoConfiguration.class           // No default security
})
public class NoDatabaseApp {
    public static void main(String[] args) {
        SpringApplication.run(NoDatabaseApp.class, args);
    }
}

// ✅ Custom component scan base packages
@SpringBootApplication(scanBasePackages = {
    "com.example.services",
    "com.example.repositories",
    "com.example.controllers"
})
public class CustomScanApp {
    public static void main(String[] args) {
        SpringApplication.run(CustomScanApp.class, args);
    }
}
```

---

### `@EnableAutoConfiguration`

Tells Spring Boot to automatically configure beans based on the classpath, other beans, and `application.properties`.

```java
// How auto-configuration works internally:
// Spring Boot reads:
//   META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
// Each listed class is a @Configuration that creates beans ONLY IF conditions are met

// Example: DataSourceAutoConfiguration is active ONLY IF:
//   - spring-jdbc is on classpath  (@ConditionalOnClass)
//   - spring.datasource.url is set (@ConditionalOnProperty)
//   - No DataSource bean already exists (@ConditionalOnMissingBean)

// ✅ Rarely used alone — @SpringBootApplication includes it
// Use alone only when @SpringBootApplication can't be used (e.g., in tests)
@Configuration
@EnableAutoConfiguration
public class ManualAutoConfig {
    // Spring Boot auto-configures based on classpath
}
```

---

### `@ComponentScan`

Tells Spring where to look for beans (components).

```java
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

@Configuration
// ✅ Scan specific packages
@ComponentScan(basePackages = "com.example")

// ✅ Type-safe version using marker classes
@ComponentScan(basePackageClasses = {
    UserService.class,     // Scan the package containing UserService
    OrderController.class  // Scan the package containing OrderController
})

// ✅ Include filter: only scan classes with @RestController
@ComponentScan(
    basePackages = "com.example",
    includeFilters = @ComponentScan.Filter(
        type = FilterType.ANNOTATION,
        classes = RestController.class
    )
)

// ✅ Exclude filter: skip @Repository beans
@ComponentScan(
    basePackages = "com.example",
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ANNOTATION,
        classes = Repository.class
    )
)
public class AppConfig {}
```

---

## 2. Stereotype Annotations

Spring uses stereotype annotations to mark classes for automatic detection and registration as beans.

### `@Component`

Generic stereotype — marks a class as a Spring-managed bean.

```java
import org.springframework.stereotype.Component;

// ✅ Basic component
@Component
public class EmailValidator {

    public boolean isValid(String email) {
        return email != null && email.contains("@") && email.contains(".");
    }
}

// ✅ Named component (custom bean name)
@Component("myEmailValidator")
public class AdvancedEmailValidator {
    // Default bean name would be "advancedEmailValidator" (camelCase of class name)
    // With "myEmailValidator" → injected with @Qualifier("myEmailValidator")
}

// ✅ Used in config class to declare bean
// vs @Component — @Component is detected by scanning,
//                 @Bean in @Configuration gives you more control (factory method)
```

---

### `@Service`

Marks a class as a **business service**. Functionally identical to `@Component` but communicates intent — business logic lives here.

```java
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final EmailService emailService;

    // Constructor injection (recommended)
    public UserService(UserRepository userRepository, EmailService emailService) {
        this.userRepository = userRepository;
        this.emailService   = emailService;
    }

    public User createUser(CreateUserRequest request) {
        // Business logic validation
        if (userRepository.existsByEmail(request.email())) {
            throw new UserAlreadyExistsException("Email already registered: " + request.email());
        }
        User user = new User(request.name(), request.email());
        User saved = userRepository.save(user);
        emailService.sendWelcome(saved.getEmail());
        return saved;
    }

    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new UserNotFoundException("User not found: " + id));
        userRepository.delete(user);
    }
}
```

---

### `@Repository`

Marks a class as a **data access object (DAO)**. Special behavior: Spring automatically **translates persistence exceptions** (`SQLException`, `PersistenceException`) into Spring's `DataAccessException` hierarchy.

```java
import org.springframework.stereotype.Repository;
import org.springframework.dao.DataAccessException;

// ✅ Custom repository implementation
@Repository
public class JdbcUserRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<User> findByEmail(String email) {
        try {
            User user = jdbcTemplate.queryForObject(
                "SELECT * FROM users WHERE email = ?",
                (rs, row) -> new User(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getString("email")
                ),
                email
            );
            return Optional.ofNullable(user);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
        // @Repository translates SQLException → DataAccessException automatically!
        // No need to catch and re-wrap low-level DB exceptions
    }
}

// ✅ Spring Data JPA — extends JpaRepository (no @Repository needed — Spring Data adds it)
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    List<User> findByAgeGreaterThan(int age);
}
```

---

### `@Controller`

Marks a class as a Spring MVC **web controller** that returns views (HTML, Thymeleaf templates).

```java
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/users")
public class UserViewController {

    private final UserService userService;

    public UserViewController(UserService userService) {
        this.userService = userService;
    }

    // Returns a view name (resolved by ViewResolver to HTML template)
    @GetMapping
    public String listUsers(Model model) {
        model.addAttribute("users", userService.findAll());
        return "users/list"; // → templates/users/list.html (Thymeleaf)
    }

    @GetMapping("/{id}")
    public String viewUser(@PathVariable Long id, Model model) {
        model.addAttribute("user", userService.findById(id));
        return "users/detail";
    }

    @PostMapping("/create")
    public String createUser(@ModelAttribute CreateUserRequest request) {
        userService.createUser(request);
        return "redirect:/users"; // Redirect after POST (PRG pattern)
    }
}
```

---

### `@RestController`

`@RestController` = `@Controller` + `@ResponseBody`

Every method return value is **written directly to the HTTP response body** as JSON/XML (no view resolution).

```java
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;

@RestController
@RequestMapping("/api/v1/users")
public class UserRestController {

    private final UserService userService;

    public UserRestController(UserService userService) {
        this.userService = userService;
    }

    // Returns JSON automatically (via Jackson)
    @GetMapping
    public List<UserDto> getAllUsers() {
        return userService.findAll(); // Serialized to JSON
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserDto> getUserById(@PathVariable Long id) {
        return userService.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<UserDto> createUser(@RequestBody @Valid CreateUserRequest req) {
        UserDto created = userService.createUser(req);
        URI location = URI.create("/api/v1/users/" + created.id());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserDto> updateUser(
            @PathVariable Long id,
            @RequestBody @Valid UpdateUserRequest req) {
        return ResponseEntity.ok(userService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }
}
```

---

## 3. Dependency Injection Annotations

### `@Autowired`

Tells Spring to **inject a dependency** automatically. Can be placed on constructor, setter, or field.

```java
import org.springframework.beans.factory.annotation.*;

@Service
public class OrderService {

    // ✅ BEST: Constructor injection (recommended)
    // - Immutable (final fields)
    // - Explicit dependencies
    // - Easy to test (no Spring needed in unit tests)
    // - Spring 4.3+: @Autowired optional if only one constructor
    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final NotificationService notificationService;

    @Autowired // Optional when single constructor
    public OrderService(
            OrderRepository orderRepository,
            PaymentService paymentService,
            NotificationService notificationService) {
        this.orderRepository     = orderRepository;
        this.paymentService      = paymentService;
        this.notificationService = notificationService;
    }

    // ⚠️ ACCEPTABLE: Setter injection (for optional dependencies)
    private AuditService auditService;

    @Autowired(required = false) // Won't fail if AuditService bean not found
    public void setAuditService(AuditService auditService) {
        this.auditService = auditService;
    }

    // ❌ AVOID: Field injection (hard to test, hides dependencies)
    @Autowired
    private SomeService someService; // Bad practice

    public Order placeOrder(PlaceOrderRequest request) {
        Order order = new Order(request);
        paymentService.charge(request.paymentInfo(), request.total());
        Order saved = orderRepository.save(order);
        notificationService.sendConfirmation(saved);
        return saved;
    }
}
```

---

### `@Qualifier`

Resolves **ambiguity** when multiple beans of the same type exist.

```java
import org.springframework.beans.factory.annotation.*;

// Two implementations of NotificationService
@Service("emailNotification")
public class EmailNotificationService implements NotificationService {
    @Override
    public void send(String message) { /* send email */ }
}

@Service("smsNotification")
public class SmsNotificationService implements NotificationService {
    @Override
    public void send(String message) { /* send SMS */ }
}

// ✅ Using @Qualifier to select which bean to inject
@Service
public class AlertService {

    private final NotificationService emailService;
    private final NotificationService smsService;

    public AlertService(
            @Qualifier("emailNotification") NotificationService emailService,
            @Qualifier("smsNotification")   NotificationService smsService) {
        this.emailService = emailService;
        this.smsService   = smsService;
    }

    public void sendCriticalAlert(String msg) {
        emailService.send(msg); // Uses email
        smsService.send(msg);   // Uses SMS
    }
}

// ✅ Custom qualifier annotation (cleaner than string names)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Qualifier
public @interface EmailNotifier {}

@Service
@EmailNotifier
public class EmailNotificationService implements NotificationService { ... }

// Injection using custom qualifier:
public AlertService(@EmailNotifier NotificationService emailService) { ... }
```

---

### `@Primary`

Marks a bean as the **default choice** when multiple beans of the same type exist.

```java
import org.springframework.context.annotation.Primary;

@Service
@Primary // Used by default when no @Qualifier specified
public class EmailNotificationService implements NotificationService {
    @Override
    public void send(String message) { /* email */ }
}

@Service
public class SmsNotificationService implements NotificationService {
    @Override
    public void send(String message) { /* SMS */ }
}

@Service
public class SimpleAlertService {

    private final NotificationService notificationService;

    // Injects EmailNotificationService (it's @Primary)
    public SimpleAlertService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }
}
```

---

### `@Value`

Injects values from **properties files**, **environment variables**, or **Spring Expression Language (SpEL)**.

```java
import org.springframework.beans.factory.annotation.Value;

@Service
public class EmailService {

    // ✅ Inject from application.properties
    @Value("${app.email.from}")
    private String fromEmail;

    // ✅ With default value (if property not set)
    @Value("${app.email.max-retries:3}")
    private int maxRetries;

    // ✅ System environment variable
    @Value("${JAVA_HOME:unknown}")
    private String javaHome;

    // ✅ SpEL expression — compute at injection time
    @Value("#{systemProperties['user.name']}")
    private String systemUser;

    @Value("#{T(java.lang.Math).PI}")
    private double pi;

    @Value("#{@userRepository.count()}")  // Call a Spring bean's method!
    private long userCount;

    // ✅ Inject list from comma-separated property
    // app.allowed.origins=http://localhost:3000,https://myapp.com
    @Value("${app.allowed.origins}")
    private String[] allowedOrigins;

    // ✅ Inject Map
    // app.config={key1:'val1', key2:'val2'}
    @Value("#{${app.config}}")
    private Map<String, String> config;

    public void sendEmail(String to, String subject, String body) {
        System.out.println("Sending from: " + fromEmail);
        System.out.println("Max retries:  " + maxRetries);
    }
}
```

---

### `@Lazy`

Defers bean initialization until it is **first requested** (instead of at application startup).

```java
import org.springframework.context.annotation.Lazy;

// ✅ Lazy initialization of the bean itself
@Service
@Lazy // Not created until first @Autowired injection is used
public class HeavyReportService {

    public HeavyReportService() {
        System.out.println("HeavyReportService created — ONLY when first needed");
        // Expensive initialization: load templates, connect to report server, etc.
    }
}

// ✅ Lazy injection at the injection site
@Service
public class DashboardService {

    @Lazy // Proxy injected; real bean created only on first method call
    private final HeavyReportService reportService;

    public DashboardService(@Lazy HeavyReportService reportService) {
        this.reportService = reportService;
        // HeavyReportService NOT yet created at this point!
    }

    public void generateDashboard() {
        // HeavyReportService is initialized HERE on first call
        reportService.generateReport();
    }
}

// In application.properties:
// spring.main.lazy-initialization=true  ← Make ALL beans lazy (faster startup)
```

---

## 4. Configuration Annotations

### `@Configuration` and `@Bean`

`@Configuration` marks a class as a **source of bean definitions**. `@Bean` marks a method whose return value is **registered as a Spring bean**.

```java
import org.springframework.context.annotation.*;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {

    // ✅ Simple bean definition
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
            .setConnectTimeout(Duration.ofSeconds(5))
            .setReadTimeout(Duration.ofSeconds(10))
            .build();
    }

    // ✅ Bean with custom name
    @Bean("primaryDataSource")
    public DataSource primaryDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:postgresql://localhost:5432/mydb");
        ds.setUsername("user");
        ds.setPassword("pass");
        ds.setMaximumPoolSize(20);
        return ds;
    }

    // ✅ Bean depending on another bean (method call — same singleton returned)
    @Bean
    public UserService userService() {
        return new UserService(userRepository()); // Calls another @Bean method
    }

    @Bean
    public UserRepository userRepository() {
        return new JpaUserRepository(primaryDataSource());
    }

    // ✅ Bean with @Scope
    @Bean
    @Scope("prototype") // New instance each time
    public ReportGenerator reportGenerator() {
        return new ReportGenerator();
    }

    // ✅ Bean with init and destroy methods
    @Bean(initMethod = "connect", destroyMethod = "disconnect")
    public ExternalServiceClient externalClient() {
        return new ExternalServiceClient();
    }

    // ✅ Conditional bean — only created if property is set
    @Bean
    @ConditionalOnProperty(name = "feature.audit.enabled", havingValue = "true")
    public AuditService auditService() {
        return new DatabaseAuditService();
    }
}
```

---

### `@ConfigurationProperties`

Binds a **group of related properties** from `application.properties` into a typed POJO. Much cleaner than multiple `@Value` annotations.

```java
// application.properties:
// app.mail.host=smtp.gmail.com
// app.mail.port=587
// app.mail.username=myapp@gmail.com
// app.mail.password=secret123
// app.mail.retry.max-attempts=3
// app.mail.retry.delay-ms=1000

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.*;

@ConfigurationProperties(prefix = "app.mail")
@Validated // Enables validation on config properties
public record MailProperties(
    @NotBlank String host,
    @Min(1) @Max(65535) int port,
    @NotBlank String username,
    @NotBlank String password,
    @DefaultValue RetryProperties retry
) {
    public record RetryProperties(
        @DefaultValue("3")    int maxAttempts,
        @DefaultValue("1000") long delayMs
    ) {}
}

// Enable scanning for @ConfigurationProperties:
// Option 1: Add to main class
@SpringBootApplication
@EnableConfigurationProperties(MailProperties.class)
public class App { ... }

// Option 2: Annotate with @Component (Spring Boot 2.2+)
@ConfigurationProperties(prefix = "app.mail")
@Component
public class MailProperties { ... }

// ✅ Injecting ConfigurationProperties
@Service
public class MailService {
    private final MailProperties props;

    public MailService(MailProperties props) {
        this.props = props;
    }

    public void sendEmail(String to, String body) {
        System.out.println("Connecting to: " + props.host() + ":" + props.port());
        System.out.println("Max retries: "   + props.retry().maxAttempts());
    }
}
```

---

### `@Profile`

Activates beans **only when a specific profile is active** (`dev`, `test`, `prod`).

```java
import org.springframework.context.annotation.Profile;

// ✅ Bean only active in development
@Service
@Profile("dev")
public class MockPaymentService implements PaymentService {
    @Override
    public PaymentResult charge(PaymentInfo info, double amount) {
        System.out.println("DEV: Mock charge of $" + amount);
        return PaymentResult.success("mock-tx-" + System.currentTimeMillis());
    }
}

// ✅ Bean only active in production
@Service
@Profile("prod")
public class StripePaymentService implements PaymentService {
    @Override
    public PaymentResult charge(PaymentInfo info, double amount) {
        // Real Stripe API call
        return stripeClient.charge(info.cardToken(), amount);
    }
}

// ✅ Multiple profiles
@Component
@Profile({"dev", "staging"})
public class MockEmailService implements EmailService { ... }

// ✅ NOT a profile (active when "prod" is NOT active)
@Component
@Profile("!prod")
public class VerboseLoggingAspect { ... }

// Activate profiles:
// application.properties: spring.profiles.active=dev
// CLI: java -jar app.jar --spring.profiles.active=prod
// Test: @ActiveProfiles("test")
```

---

### `@PropertySource`

Loads properties from a **custom file** into Spring's Environment.

```java
import org.springframework.context.annotation.PropertySource;

@Configuration
@PropertySource("classpath:custom.properties")
@PropertySource("classpath:database.properties")
@PropertySource(value = "file:/etc/app/secrets.properties",
                ignoreResourceNotFound = true) // Don't fail if file missing
public class PropertyConfig {

    @Value("${custom.key}")
    private String customKey;
}

// ✅ Java 8+ @PropertySources (multiple)
@Configuration
@PropertySources({
    @PropertySource("classpath:app.properties"),
    @PropertySource("classpath:db.properties")
})
public class MultiPropertyConfig { }
```

---

## 5. Web / REST Annotations

### `@RequestMapping`

The foundation of all HTTP endpoint mapping. Maps HTTP requests to handler methods.

```java
import org.springframework.web.bind.annotation.*;

// ✅ Class-level: defines base URL for all methods
@RestController
@RequestMapping(
    value    = "/api/v1/products",
    produces = MediaType.APPLICATION_JSON_VALUE,
    consumes = MediaType.APPLICATION_JSON_VALUE
)
public class ProductController {

    // ✅ Method-level mapping
    @RequestMapping(value = "/{id}", method = RequestMethod.GET)
    public Product getProduct(@PathVariable Long id) { ... }

    // ✅ Multiple paths for same handler
    @RequestMapping(value = {"/search", "/find"}, method = RequestMethod.GET)
    public List<Product> search(@RequestParam String q) { ... }

    // ✅ Multiple HTTP methods
    @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public Product update(@PathVariable Long id, @RequestBody ProductRequest req) { ... }
}
```

---

## 6. Request Mapping Annotations

Shorthand annotations for `@RequestMapping` with specific HTTP methods.

```java
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    // ✅ @GetMapping — retrieve resources
    @GetMapping
    public Page<OrderDto> getAllOrders(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false)    String status) {
        return orderService.findAll(page, size, status);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderDto> getOrder(@PathVariable Long id) {
        return orderService.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    // ✅ @PostMapping — create resource
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDto createOrder(@RequestBody @Valid CreateOrderRequest request) {
        return orderService.create(request);
    }

    // ✅ @PutMapping — replace resource entirely
    @PutMapping("/{id}")
    public OrderDto replaceOrder(
            @PathVariable Long id,
            @RequestBody @Valid ReplaceOrderRequest request) {
        return orderService.replace(id, request);
    }

    // ✅ @PatchMapping — partial update
    @PatchMapping("/{id}")
    public OrderDto patchOrder(
            @PathVariable Long id,
            @RequestBody Map<String, Object> updates) {
        return orderService.patch(id, updates);
    }

    // ✅ @DeleteMapping — delete resource
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOrder(@PathVariable Long id) {
        orderService.delete(id);
    }

    // ✅ Nested resources
    @GetMapping("/{id}/items")
    public List<OrderItemDto> getOrderItems(@PathVariable Long id) {
        return orderService.findItems(id);
    }

    @PostMapping("/{id}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderItemDto addItem(
            @PathVariable Long id,
            @RequestBody @Valid AddItemRequest request) {
        return orderService.addItem(id, request);
    }
}
```

---

## 7. Request Data Annotations

### `@PathVariable`

Extracts values from the **URI path**.

```java
@RestController
@RequestMapping("/api")
public class PathVariableDemo {

    // ✅ Basic path variable
    @GetMapping("/users/{id}")
    public User getUser(@PathVariable Long id) {
        return userService.findById(id);
    }

    // ✅ Multiple path variables
    @GetMapping("/departments/{deptId}/employees/{empId}")
    public Employee getEmployee(
            @PathVariable Long deptId,
            @PathVariable Long empId) {
        return employeeService.findByDeptAndId(deptId, empId);
    }

    // ✅ Custom variable name (when param name differs from path variable)
    @GetMapping("/items/{item-id}")
    public Item getItem(@PathVariable("item-id") Long itemId) {
        return itemService.findById(itemId);
    }

    // ✅ Optional path variable
    @GetMapping({"/posts/{id}", "/posts"})
    public List<Post> getPosts(
            @PathVariable(required = false) Long id) {
        return id != null ? List.of(postService.findById(id))
                          : postService.findAll();
    }

    // ✅ Regex constraint in path
    @GetMapping("/files/{filename:.+}")    // .+ matches "file.txt", "image.png"
    public byte[] getFile(@PathVariable String filename) {
        return fileService.read(filename);
    }
}
```

---

### `@RequestParam`

Extracts values from **query string** (`?key=value`).

```java
@RestController
@RequestMapping("/api/products")
public class RequestParamDemo {

    // ✅ Required query param — 400 if missing
    @GetMapping("/search")
    public List<Product> search(@RequestParam String keyword) {
        return productService.search(keyword);
    }

    // ✅ Optional with default value
    @GetMapping
    public Page<Product> list(
            @RequestParam(defaultValue = "0")     int page,
            @RequestParam(defaultValue = "10")    int size,
            @RequestParam(defaultValue = "name")  String sortBy,
            @RequestParam(defaultValue = "asc")   String direction) {
        return productService.findAll(page, size, sortBy, direction);
    }

    // ✅ Optional (returns null if absent)
    @GetMapping("/filter")
    public List<Product> filter(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice) {
        return productService.filter(category, minPrice, maxPrice);
    }

    // ✅ Multi-value param: ?tags=java&tags=spring&tags=boot
    @GetMapping("/by-tags")
    public List<Product> byTags(@RequestParam List<String> tags) {
        return productService.findByTags(tags);
    }

    // ✅ Map of all params
    @GetMapping("/raw")
    public Map<String, String> rawParams(@RequestParam Map<String, String> params) {
        System.out.println("All params: " + params);
        return params;
    }
}
```

---

### `@RequestBody`

Deserializes the **HTTP request body** (JSON/XML) into a Java object.

```java
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

// ✅ Request DTO
public record CreateProductRequest(
    @NotBlank(message = "Name is required")
    String name,

    @NotBlank(message = "Category is required")
    String category,

    @Min(value = 0, message = "Price must be non-negative")
    @DecimalMax(value = "99999.99", message = "Price too high")
    double price,

    @Min(0) int stockQuantity
) {}

@RestController
@RequestMapping("/api/products")
public class RequestBodyDemo {

    // ✅ Basic @RequestBody
    @PostMapping
    public ResponseEntity<ProductDto> create(
            @RequestBody @Valid CreateProductRequest request) {
        // Spring reads JSON body, deserializes to CreateProductRequest
        // @Valid triggers Bean Validation (javax/jakarta.validation)
        ProductDto created = productService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ✅ Optional body (may be empty)
    @PatchMapping("/{id}")
    public ProductDto patch(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> updates) {
        return productService.patch(id, updates != null ? updates : Map.of());
    }

    // ✅ Raw JSON as String or JsonNode
    @PostMapping("/raw")
    public String acceptRaw(@RequestBody String rawJson) {
        System.out.println("Raw JSON: " + rawJson);
        return "received " + rawJson.length() + " chars";
    }
}
```

---

### `@RequestHeader`

Extracts values from **HTTP request headers**.

```java
@RestController
public class HeaderDemo {

    @GetMapping("/api/data")
    public ResponseEntity<Data> getData(
            // ✅ Required header
            @RequestHeader("Authorization") String authHeader,

            // ✅ Optional with default
            @RequestHeader(value = "Accept-Language", defaultValue = "en") String lang,

            // ✅ All headers as Map
            @RequestHeader Map<String, String> allHeaders) {

        System.out.println("Auth: " + authHeader);
        System.out.println("Lang: " + lang);
        System.out.println("All headers: " + allHeaders.size());

        return ResponseEntity.ok(dataService.fetch(lang));
    }

    // ✅ Custom header (e.g., correlation ID for tracing)
    @PostMapping("/api/events")
    public void processEvent(
            @RequestHeader(value = "X-Correlation-ID", required = false)
            String correlationId,
            @RequestBody EventRequest event) {
        log.info("Correlation-ID: {}", correlationId);
        eventService.process(event);
    }
}
```

---

### `@CookieValue`

Extracts values from **HTTP cookies**.

```java
@RestController
public class CookieDemo {

    @GetMapping("/api/profile")
    public UserProfile getProfile(
            @CookieValue("SESSION_ID") String sessionId,
            @CookieValue(value = "THEME", defaultValue = "light") String theme) {
        return profileService.findBySession(sessionId, theme);
    }
}
```

---

### `@ModelAttribute`

Binds **form data or query params** to a model object. Commonly used in MVC (non-REST) controllers.

```java
@Controller
public class FormController {

    // ✅ Binds form fields to object
    @PostMapping("/register")
    public String register(@ModelAttribute @Valid RegistrationForm form,
                           BindingResult result) {
        if (result.hasErrors()) {
            return "register"; // Return to form view
        }
        userService.register(form);
        return "redirect:/login";
    }

    // ✅ @ModelAttribute on method — adds to model before each request handler
    @ModelAttribute("categories")
    public List<String> populateCategories() {
        return List.of("Electronics", "Books", "Clothing");
        // Added to Model automatically for all handlers in this controller
    }
}
```

---

## 8. Response Annotations

### `@ResponseBody`

Writes the return value **directly to the HTTP response body**.

```java
// @ResponseBody on method (when class is @Controller, not @RestController)
@Controller
public class MixedController {

    @GetMapping("/view")
    public String viewPage() {
        return "home"; // Returns VIEW name (no @ResponseBody)
    }

    @GetMapping("/api/data")
    @ResponseBody // This method returns JSON, not a view
    public Map<String, Object> getData() {
        return Map.of("status", "ok", "time", System.currentTimeMillis());
    }
}

// Note: @RestController = @Controller + @ResponseBody on EVERY method
```

---

### `@ResponseStatus`

Sets the **HTTP status code** for a response.

```java
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/tasks")
public class ResponseStatusDemo {

    // ✅ Return 201 Created
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDto createTask(@RequestBody CreateTaskRequest request) {
        return taskService.create(request); // 201 returned automatically
    }

    // ✅ Return 204 No Content (void methods default to 200 — override here)
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTask(@PathVariable Long id) {
        taskService.delete(id);
    }

    // ✅ On exception class — auto-set status when exception thrown
    @ResponseStatus(
        code    = HttpStatus.NOT_FOUND,
        reason  = "Task not found"
    )
    public static class TaskNotFoundException extends RuntimeException {
        public TaskNotFoundException(Long id) {
            super("Task not found: " + id);
        }
    }

    @GetMapping("/{id}")
    public TaskDto getTask(@PathVariable Long id) {
        return taskService.findById(id)
            .orElseThrow(() -> new TaskNotFoundException(id)); // Returns 404 automatically
    }
}
```

---

### `@ExceptionHandler`

Handles exceptions thrown within a **specific controller**.

```java
@RestController
@RequestMapping("/api/users")
public class UserControllerWithExceptionHandling {

    // ✅ Handle specific exception type in THIS controller only
    @ExceptionHandler(UserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(UserNotFoundException ex) {
        return new ErrorResponse("USER_NOT_FOUND", ex.getMessage());
    }

    // ✅ Handle multiple exception types
    @ExceptionHandler({IllegalArgumentException.class, ValidationException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleBadRequest(Exception ex) {
        return new ErrorResponse("BAD_REQUEST", ex.getMessage());
    }

    // ✅ Return ResponseEntity for full control
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse("INTERNAL_ERROR", "Something went wrong"));
    }

    record ErrorResponse(String code, String message) {}
}
```

---

### `@ControllerAdvice` / `@RestControllerAdvice`

**Global exception handler** — applies to ALL controllers.

```java
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@RestControllerAdvice // = @ControllerAdvice + @ResponseBody
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ✅ Handle resource not found
    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(ResourceNotFoundException ex) {
        return new ErrorResponse("NOT_FOUND", ex.getMessage(), null);
    }

    // ✅ Handle validation errors (from @Valid)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(err ->
            fieldErrors.put(err.getField(), err.getDefaultMessage())
        );
        return new ErrorResponse("VALIDATION_FAILED", "Request validation failed", fieldErrors);
    }

    // ✅ Handle constraint violations
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleConstraint(ConstraintViolationException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v ->
            errors.put(v.getPropertyPath().toString(), v.getMessage())
        );
        return new ErrorResponse("CONSTRAINT_VIOLATION", "Constraint violation", errors);
    }

    // ✅ Handle unauthorized access
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorResponse handleForbidden(AccessDeniedException ex) {
        return new ErrorResponse("FORBIDDEN", "Access denied", null);
    }

    // ✅ Catch-all for unexpected errors
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneral(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred", null);
    }

    public record ErrorResponse(
        String code,
        String message,
        Map<String, String> fieldErrors
    ) {}
}
```

---

## 9. Data / JPA Annotations

### Entity and Table Mapping

```java
import jakarta.persistence.*;

@Entity
@Table(
    name = "users",
    uniqueConstraints = @UniqueConstraint(columnNames = "email"),
    indexes = @Index(name = "idx_users_email", columnList = "email")
)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false, length = 100)
    private String name;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp  // Hibernate: set on insert
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp    // Hibernate: update on every save
    private LocalDateTime updatedAt;

    @Enumerated(EnumType.STRING) // Store as "ACTIVE", "INACTIVE" (not 0, 1)
    @Column(nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Transient // Not persisted to DB
    private String displayName;

    // ✅ One-to-Many: User has many Orders
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt DESC")
    private List<Order> orders = new ArrayList<>();

    // ✅ Many-to-Many: User has many Roles
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "user_roles",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles = new HashSet<>();

    // ✅ Embedded value object
    @Embedded
    private Address address;

    // Getters, setters, constructors...
}

@Embeddable
public class Address {
    @Column(name = "street")  private String street;
    @Column(name = "city")    private String city;
    @Column(name = "country") private String country;
}
```

---

### Spring Data JPA Repository

```java
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;

public interface UserRepository extends JpaRepository<User, Long> {

    // ✅ Derived query methods (Spring Data generates SQL from method name)
    Optional<User> findByEmail(String email);
    List<User>     findByStatus(UserStatus status);
    boolean        existsByEmail(String email);
    long           countByStatus(UserStatus status);
    void           deleteByEmail(String email);

    List<User>     findByNameContainingIgnoreCase(String name);
    List<User>     findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
    List<User>     findByAgeGreaterThanAndStatus(int age, UserStatus status);
    Page<User>     findByStatus(UserStatus status, Pageable pageable);

    // ✅ @Query — JPQL
    @Query("SELECT u FROM User u WHERE u.email = :email AND u.status = 'ACTIVE'")
    Optional<User> findActiveByEmail(@Param("email") String email);

    // ✅ @Query — Native SQL
    @Query(value = "SELECT * FROM users WHERE LOWER(email) = LOWER(:email)",
           nativeQuery = true)
    Optional<User> findByEmailNative(@Param("email") String email);

    // ✅ @Modifying — for UPDATE/DELETE queries
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.status = :status WHERE u.id = :id")
    int updateStatus(@Param("id") Long id, @Param("status") UserStatus status);

    // ✅ Pagination and sorting
    Page<User> findAll(Pageable pageable);
    List<User> findAll(Sort sort);

    // ✅ Projections — only fetch specific fields
    List<UserSummary> findByStatus(UserStatus status, Class<UserSummary> type);

    interface UserSummary {
        Long   getId();
        String getName();
        String getEmail();
    }
}
```

---

## 10. Validation Annotations

```java
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;

// ✅ Request DTO with comprehensive validation
public record RegisterUserRequest(

    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100, message = "Name must be 2–100 characters")
    String name,

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    String email,

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    @Pattern(regexp = ".*[A-Z].*", message = "Password must contain uppercase letter")
    @Pattern(regexp = ".*[0-9].*", message = "Password must contain a digit")
    String password,

    @NotNull(message = "Age is required")
    @Min(value = 18, message = "Must be at least 18")
    @Max(value = 120, message = "Age seems invalid")
    Integer age,

    @NotNull(message = "Birth date required")
    @Past(message = "Birth date must be in the past")
    LocalDate birthDate,

    @FutureOrPresent(message = "Start date must not be in the past")
    LocalDate membershipStart,

    @Positive(message = "Amount must be positive")
    double amount,

    @DecimalMin(value = "0.0", inclusive = false)
    @DecimalMax(value = "100.0")
    double discountPercent,

    @NotEmpty(message = "At least one role required")
    @Size(max = 5, message = "Too many roles")
    List<@NotBlank String> roles,

    @URL(message = "Invalid URL")
    String profileUrl
) {}

// ✅ Trigger validation in controller
@RestController
public class ValidationController {

    @PostMapping("/api/users")
    public UserDto register(@RequestBody @Valid RegisterUserRequest request) {
        // @Valid triggers Bean Validation — 400 returned if invalid
        return userService.register(request);
    }

    // ✅ @Validated for method-level validation (on @Service too)
    @GetMapping("/api/users/{id}")
    public UserDto getUser(
            @PathVariable @Positive(message = "ID must be positive") Long id) {
        return userService.findById(id);
    }
}

// ✅ Custom constraint annotation
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = UniqueEmailValidator.class)
public @interface UniqueEmail {
    String message() default "Email already registered";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

public class UniqueEmailValidator
        implements ConstraintValidator<UniqueEmail, String> {

    @Autowired private UserRepository userRepository;

    @Override
    public boolean isValid(String email, ConstraintValidatorContext ctx) {
        if (email == null) return true; // Let @NotNull handle null
        return !userRepository.existsByEmail(email);
    }
}
```

---

## 11. Transaction Annotations

### `@Transactional`

Wraps method execution in a **database transaction**.

```java
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;

@Service
@Transactional(readOnly = true) // Class-level: all methods read-only by default
public class BankingService {

    private final AccountRepository accountRepo;
    private final TransactionLogRepo txLogRepo;

    public BankingService(AccountRepository accountRepo, TransactionLogRepo txLogRepo) {
        this.accountRepo = accountRepo;
        this.txLogRepo   = txLogRepo;
    }

    // ✅ Overrides class-level: this method is read-write
    @Transactional
    public void transfer(Long fromId, Long toId, BigDecimal amount) {
        Account from = accountRepo.findById(fromId)
            .orElseThrow(() -> new AccountNotFoundException(fromId));
        Account to = accountRepo.findById(toId)
            .orElseThrow(() -> new AccountNotFoundException(toId));

        if (from.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException("Insufficient balance");
        }

        from.debit(amount);
        to.credit(amount);

        accountRepo.save(from);
        accountRepo.save(to);
        txLogRepo.save(new TransactionLog(fromId, toId, amount));
        // All saved or all rolled back — atomic!
    }

    // ✅ readOnly — hint to DB driver, disables dirty checking
    public BigDecimal getBalance(Long accountId) {
        return accountRepo.findById(accountId)
            .map(Account::getBalance)
            .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    // ✅ Propagation types
    @Transactional(propagation = Propagation.REQUIRED)      // Default: join existing or create new
    public void defaultBehavior() {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)  // Always new TX, suspend existing
    public void auditLog(String action) {
        // Runs in its own transaction — committed even if outer TX rolls back
        txLogRepo.save(new AuditLog(action));
    }

    @Transactional(propagation = Propagation.SUPPORTS)      // Join if exists, no TX if not
    public void optionalTx() {}

    @Transactional(propagation = Propagation.NOT_SUPPORTED) // Suspend TX if exists
    public void nonTransactional() {}

    @Transactional(propagation = Propagation.NEVER)         // Throw if TX exists
    public void mustNotBeTx() {}

    @Transactional(propagation = Propagation.MANDATORY)     // Throw if NO TX exists
    public void mustBeInTx() {}

    @Transactional(propagation = Propagation.NESTED)        // Savepoint within existing TX
    public void nested() {}

    // ✅ Isolation levels
    @Transactional(isolation = Isolation.READ_COMMITTED)    // Default for most DBs
    public void readCommitted() {}

    @Transactional(isolation = Isolation.SERIALIZABLE)      // Strictest — prevent all anomalies
    public void strictIsolation() {}

    // ✅ Rollback rules
    @Transactional(rollbackFor = Exception.class)            // Rollback on checked exceptions too
    public void rollbackOnChecked() throws IOException { }

    @Transactional(noRollbackFor = OptimisticLockException.class) // Don't rollback on this
    public void noRollbackFor() {}

    // ✅ Timeout
    @Transactional(timeout = 30) // Rollback if takes > 30 seconds
    public void timedOperation() {}
}
```

---

## 12. Scheduling & Async Annotations

### `@Scheduled`

Runs a method **on a schedule** (cron, fixed rate, fixed delay).

```java
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // Required to activate scheduling
public class App { public static void main(String[] a) { SpringApplication.run(App.class, a); } }

@Component
public class ScheduledTasks {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTasks.class);

    // ✅ Fixed rate: every 5 seconds (starts immediately, no wait between completions)
    @Scheduled(fixedRate = 5000)
    public void heartbeat() {
        log.info("Heartbeat at: {}", LocalDateTime.now());
    }

    // ✅ Fixed delay: 10 seconds AFTER last completion (waits between runs)
    @Scheduled(fixedDelay = 10_000)
    public void cleanupTempFiles() {
        log.info("Cleaning temp files...");
        fileService.deleteTempFiles();
    }

    // ✅ Initial delay: wait 30s before first run, then every 60s
    @Scheduled(initialDelay = 30_000, fixedRate = 60_000)
    public void warmUpCache() {
        cacheService.warmUp();
    }

    // ✅ Cron expression — most flexible
    // Format: second minute hour day-of-month month day-of-week
    @Scheduled(cron = "0 0 2 * * ?")        // Every day at 02:00:00
    public void dailyReport() {
        reportService.generateDailyReport();
    }

    @Scheduled(cron = "0 */15 9-17 * * MON-FRI") // Every 15 min, 9am–5pm, weekdays
    public void businessHoursTask() {
        monitorService.check();
    }

    @Scheduled(cron = "0 0 0 1 * ?")        // 1st day of every month at midnight
    public void monthlyBilling() {
        billingService.runMonthlyBilling();
    }

    // ✅ Cron from properties (for easy environment-based tuning)
    @Scheduled(cron = "${app.schedule.cleanup:0 0 3 * * ?}")
    public void configuredSchedule() {
        cleanupService.run();
    }
}
```

---

### `@Async`

Runs a method **asynchronously** in a separate thread.

```java
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import java.util.concurrent.CompletableFuture;

@SpringBootApplication
@EnableAsync // Required to activate async
public class App { ... }

@Service
public class AsyncService {

    // ✅ Fire-and-forget (void return)
    @Async
    public void sendWelcomeEmail(String email) {
        // Runs in a different thread — caller doesn't wait
        Thread.sleep(500); // Simulate delay
        emailService.send(email, "Welcome!", "...");
        log.info("Email sent on thread: {}", Thread.currentThread().getName());
    }

    // ✅ Async with result — returns CompletableFuture
    @Async
    public CompletableFuture<UserDto> fetchUserAsync(Long id) {
        UserDto user = userRepository.findById(id)
            .map(UserDto::from)
            .orElseThrow(() -> new UserNotFoundException(id));
        return CompletableFuture.completedFuture(user);
    }

    // ✅ Custom executor for async tasks
    @Async("taskExecutor") // Use bean named "taskExecutor"
    public CompletableFuture<Report> generateReport(ReportRequest req) {
        Report report = reportEngine.generate(req);
        return CompletableFuture.completedFuture(report);
    }
}

// ✅ Configure custom Executor bean
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(10);
        exec.setQueueCapacity(500);
        exec.setThreadNamePrefix("async-task-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        return exec;
    }

    // ✅ Parallel execution with CompletableFuture
    @Bean
    public CompletableFuture<DashboardData> loadDashboard(Long userId) {
        CompletableFuture<UserDto>    userFuture    = fetchUserAsync(userId);
        CompletableFuture<List<Order>> ordersFuture = fetchOrdersAsync(userId);
        CompletableFuture<List<Notification>> notifFuture = fetchNotificationsAsync(userId);

        return CompletableFuture.allOf(userFuture, ordersFuture, notifFuture)
            .thenApply(v -> new DashboardData(
                userFuture.join(),
                ordersFuture.join(),
                notifFuture.join()
            ));
    }
}
```

---

## 13. Caching Annotations

```java
import org.springframework.cache.annotation.*;

@SpringBootApplication
@EnableCaching // Required to activate caching
public class App { ... }

@Service
@CacheConfig(cacheNames = "users") // Default cache name for all methods
public class CachedUserService {

    // ✅ @Cacheable — cache the result; skip method if cache hit
    @Cacheable(key = "#id")
    public UserDto findById(Long id) {
        log.info("DB call for user: {}", id); // Only logged on cache miss
        return userRepository.findById(id)
            .map(UserDto::from)
            .orElseThrow(() -> new UserNotFoundException(id));
    }

    // ✅ Cache with condition and unless
    @Cacheable(
        cacheNames = "users",
        key         = "#email.toLowerCase()",
        condition   = "#email != null && #email.contains('@')",  // Only cache valid emails
        unless      = "#result == null"    // Don't cache null results
    )
    public UserDto findByEmail(String email) {
        return userRepository.findByEmail(email)
            .map(UserDto::from)
            .orElse(null);
    }

    // ✅ @CachePut — always call method AND update cache
    @CachePut(key = "#result.id()")
    public UserDto updateUser(Long id, UpdateUserRequest req) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new UserNotFoundException(id));
        user.update(req);
        return UserDto.from(userRepository.save(user));
        // Cache updated with new value — next findById will hit cache
    }

    // ✅ @CacheEvict — remove entry from cache
    @CacheEvict(key = "#id")
    public void deleteUser(Long id) {
        userRepository.deleteById(id);
        // Cache entry for this id is now removed
    }

    // ✅ Evict all entries from cache
    @CacheEvict(allEntries = true)
    public void clearAllUsersCache() {
        log.info("User cache cleared");
    }

    // ✅ @Caching — combine multiple cache operations
    @Caching(evict = {
        @CacheEvict(cacheNames = "users",      key = "#id"),
        @CacheEvict(cacheNames = "user-orders", key = "#id")
    })
    public void deleteUserAndOrders(Long id) {
        orderRepository.deleteByUserId(id);
        userRepository.deleteById(id);
    }

    // ✅ SpEL in cache key
    @Cacheable(key = "#pageable.pageNumber + '-' + #pageable.pageSize + '-' + #status")
    public Page<UserDto> findByStatus(UserStatus status, Pageable pageable) {
        return userRepository.findByStatus(status, pageable).map(UserDto::from);
    }
}
```

---

## 14. Security Annotations

```java
import org.springframework.security.access.prepost.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

// Enable method security (in @Configuration class):
// @EnableMethodSecurity  ← Spring Security 5.6+ (replaces @EnableGlobalMethodSecurity)

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    // ✅ @PreAuthorize — checked BEFORE method executes
    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public List<UserDto> getAllUsers() {
        return userService.findAll();
    }

    // ✅ Complex SpEL expression
    @GetMapping("/reports/{type}")
    @PreAuthorize("hasRole('ADMIN') or (hasRole('MANAGER') and #type != 'financial')")
    public Report getReport(@PathVariable String type) {
        return reportService.generate(type);
    }

    // ✅ @PostAuthorize — checked AFTER method, can access return value
    @GetMapping("/users/{id}")
    @PostAuthorize("returnObject.ownerId == authentication.principal.id or hasRole('ADMIN')")
    public UserDto getUser(@PathVariable Long id) {
        return userService.findById(id);
    }

    // ✅ @PreFilter — filter INPUT collection before method
    @DeleteMapping("/users/batch")
    @PreFilter("filterObject.status != 'PROTECTED'")
    public void deleteUsers(@RequestBody List<UserDto> users) {
        userService.deleteAll(users); // Only receives non-PROTECTED users
    }

    // ✅ @PostFilter — filter OUTPUT collection
    @GetMapping("/orders")
    @PostFilter("filterObject.userId == authentication.principal.id or hasRole('ADMIN')")
    public List<OrderDto> getOrders() {
        return orderService.findAll(); // Only returns orders owned by caller (or admin sees all)
    }

    // ✅ @Secured — simpler, only supports role names
    @DeleteMapping("/users/{id}")
    @Secured("ROLE_ADMIN")
    public void deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
    }

    // ✅ @AuthenticationPrincipal — inject current user
    @GetMapping("/profile")
    public UserDto getMyProfile(@AuthenticationPrincipal UserDetails currentUser) {
        return userService.findByUsername(currentUser.getUsername());
    }
}
```

---

## 15. Testing Annotations

```java
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.web.servlet.MockMvc;
import org.mockito.Mock;

// ✅ @SpringBootTest — full application context (integration test)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UserServiceIntegrationTest {

    @Autowired
    private UserService userService;

    @Test
    void createUser_ShouldPersistAndReturn() {
        var request = new CreateUserRequest("Alice", "alice@test.com");
        var result  = userService.createUser(request);
        assertThat(result.name()).isEqualTo("Alice");
        assertThat(result.id()).isNotNull();
    }
}

// ✅ @WebMvcTest — only web layer (controllers, filters, no service/repo)
@WebMvcTest(UserRestController.class)
class UserRestControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean  // Mocks the service bean in Spring context
    UserService userService;

    @Test
    void getUser_WhenExists_Returns200() throws Exception {
        when(userService.findById(1L))
            .thenReturn(Optional.of(new UserDto(1L, "Alice", "alice@test.com")));

        mockMvc.perform(get("/api/v1/users/1")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Alice"))
            .andExpect(jsonPath("$.email").value("alice@test.com"));
    }

    @Test
    void createUser_WithInvalidBody_Returns400() throws Exception {
        String invalidJson = """{"name":"","email":"not-an-email"}""";

        mockMvc.perform(post("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson))
            .andExpect(status().isBadRequest());
    }
}

// ✅ @DataJpaTest — only JPA layer (repo, entity, in-memory DB)
@DataJpaTest
class UserRepositoryTest {

    @Autowired
    UserRepository userRepository;

    @Autowired
    TestEntityManager entityManager;

    @Test
    void findByEmail_WhenExists_ReturnsUser() {
        User user = new User("Bob", "bob@test.com");
        entityManager.persistAndFlush(user);

        Optional<User> found = userRepository.findByEmail("bob@test.com");
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Bob");
    }
}

// ✅ @MockBean vs @Mock
// @MockBean — adds mock to Spring context (use in @SpringBootTest / @WebMvcTest)
// @Mock     — pure Mockito mock (use in plain unit tests with @ExtendWith(MockitoExtension.class))

@ExtendWith(MockitoExtension.class)
class UserServiceUnitTest {

    @Mock           UserRepository   userRepository;
    @Mock           EmailService     emailService;
    @InjectMocks    UserService      userService;  // Inject @Mock fields into this

    @Test
    void createUser_WhenEmailDuplicate_ThrowsException() {
        when(userRepository.existsByEmail("dup@test.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser(
                new CreateUserRequest("Alice", "dup@test.com")))
            .isInstanceOf(UserAlreadyExistsException.class);
    }
}
```

---

## 16. Conditional Annotations

```java
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Conditional;

@Configuration
public class ConditionalConfig {

    // ✅ @ConditionalOnProperty — only if property has value
    @Bean
    @ConditionalOnProperty(
        name       = "app.feature.payments.enabled",
        havingValue= "true",
        matchIfMissing = false  // Don't create bean if property absent
    )
    public PaymentService paymentService() {
        return new StripePaymentService();
    }

    // ✅ @ConditionalOnClass — only if class is on classpath
    @Bean
    @ConditionalOnClass(name = "com.fasterxml.jackson.databind.ObjectMapper")
    public JacksonConfig jacksonConfig() {
        return new JacksonConfig();
    }

    // ✅ @ConditionalOnMissingClass — only if class NOT on classpath
    @Bean
    @ConditionalOnMissingClass("org.springframework.security.core.Authentication")
    public NoSecurityConfig noSecurityConfig() {
        return new NoSecurityConfig();
    }

    // ✅ @ConditionalOnBean — only if another bean exists
    @Bean
    @ConditionalOnBean(DataSource.class)  // Only if DataSource bean is present
    public DatabaseHealthIndicator dbHealth(DataSource ds) {
        return new DatabaseHealthIndicator(ds);
    }

    // ✅ @ConditionalOnMissingBean — only if bean NOT already defined
    @Bean
    @ConditionalOnMissingBean(CacheManager.class) // Use this ONLY if no other CacheManager
    public CacheManager simpleCacheManager() {
        return new ConcurrentMapCacheManager("users", "products");
    }

    // ✅ @ConditionalOnExpression — SpEL condition
    @Bean
    @ConditionalOnExpression("'${app.env}' == 'dev' or '${app.env}' == 'test'")
    public MockDataService mockData() {
        return new MockDataService();
    }

    // ✅ @ConditionalOnWebApplication — only in web context
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public RequestLoggingFilter loggingFilter() {
        return new RequestLoggingFilter();
    }

    // ✅ @ConditionalOnNotWebApplication — only in non-web context
    @Bean
    @ConditionalOnNotWebApplication
    public BatchRunner batchRunner() {
        return new BatchRunner();
    }

    // ✅ @Profile as an alias for common conditional
    @Bean
    @Profile("dev")  // Same as @ConditionalOnProperty(name="spring.profiles.active",havingValue="dev")
    public DevOnlyBean devBean() {
        return new DevOnlyBean();
    }
}
```

---

## 17. Event & Lifecycle Annotations

### `@PostConstruct` and `@PreDestroy`

```java
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Component
public class CacheWarmupService {

    private final CacheService cacheService;
    private final DatabaseService dbService;

    public CacheWarmupService(CacheService cacheService, DatabaseService dbService) {
        this.cacheService = cacheService;
        this.dbService    = dbService;
    }

    // ✅ Called AFTER bean is fully initialized and all dependencies injected
    @PostConstruct
    public void init() {
        System.out.println("Bean initialized — warming up cache...");
        List<Product> hotProducts = dbService.findTopProducts(100);
        cacheService.preload("products", hotProducts);
    }

    // ✅ Called BEFORE bean is destroyed (context shutdown)
    @PreDestroy
    public void cleanup() {
        System.out.println("Bean destroying — flushing cache...");
        cacheService.flush();
        cacheService.clear();
    }
}
```

---

### Application Events with `@EventListener`

```java
import org.springframework.context.event.*;
import org.springframework.boot.context.event.*;

@Component
public class AppEventListeners {

    // ✅ Listen for application startup
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        System.out.println("App is fully started and ready to serve!");
        schedulerService.startAllJobs();
    }

    // ✅ Listen for application startup (earlier — context refreshed but server not started)
    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed() {
        System.out.println("Spring context refreshed");
    }

    // ✅ Custom application event
    @EventListener
    public void onUserCreated(UserCreatedEvent event) {
        System.out.println("User created: " + event.userId());
        welcomeEmailService.sendWelcome(event.email());
    }

    // ✅ Async event listener (non-blocking)
    @EventListener
    @Async
    public void onOrderPlaced(OrderPlacedEvent event) {
        // Runs in a separate thread — doesn't block order placement
        warehouseService.reserveItems(event.orderId());
        notificationService.notify(event.userId(), "Order confirmed!");
    }

    // ✅ Conditional event handling
    @EventListener(condition = "#event.critical == true")
    public void onCriticalAlert(AlertEvent event) {
        pagerDutyService.page(event.message());
    }
}

// Custom events
public record UserCreatedEvent(Long userId, String email) {}
public record OrderPlacedEvent(Long orderId, Long userId) {}
public record AlertEvent(String message, boolean critical) {}

// Publishing events
@Service
public class UserService {

    @Autowired ApplicationEventPublisher eventPublisher;

    public User createUser(CreateUserRequest req) {
        User saved = userRepository.save(new User(req));
        eventPublisher.publishEvent(new UserCreatedEvent(saved.getId(), saved.getEmail()));
        return saved;
    }
}
```

---

## 18. AOP Annotations

```java
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.*;

@Aspect
@Component
public class LoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(LoggingAspect.class);

    // ✅ Pointcut expression: all methods in service package
    @Pointcut("execution(* com.example.service.*.*(..))")
    public void serviceMethods() {}

    // ✅ @Before — runs BEFORE the method
    @Before("serviceMethods()")
    public void logBefore(JoinPoint jp) {
        log.info("→ {}.{}({})",
            jp.getTarget().getClass().getSimpleName(),
            jp.getSignature().getName(),
            Arrays.toString(jp.getArgs()));
    }

    // ✅ @AfterReturning — runs AFTER successful return
    @AfterReturning(pointcut = "serviceMethods()", returning = "result")
    public void logAfterReturning(JoinPoint jp, Object result) {
        log.info("← {}.{} returned: {}",
            jp.getTarget().getClass().getSimpleName(),
            jp.getSignature().getName(),
            result);
    }

    // ✅ @AfterThrowing — runs AFTER exception thrown
    @AfterThrowing(pointcut = "serviceMethods()", throwing = "ex")
    public void logException(JoinPoint jp, Exception ex) {
        log.error("✗ {}.{} threw: {}",
            jp.getTarget().getClass().getSimpleName(),
            jp.getSignature().getName(),
            ex.getMessage());
    }

    // ✅ @After — always runs (like finally)
    @After("serviceMethods()")
    public void logAfter(JoinPoint jp) {
        log.info("Completed: {}", jp.getSignature().getName());
    }

    // ✅ @Around — wraps the entire method (most powerful)
    @Around("@annotation(com.example.annotation.LogExecutionTime)")
    public Object logExecutionTime(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            Object result = pjp.proceed(); // Call the actual method
            long elapsed = System.currentTimeMillis() - start;
            log.info("{} completed in {}ms",
                pjp.getSignature().toShortString(), elapsed);
            return result;
        } catch (Throwable ex) {
            log.error("{} failed in {}ms",
                pjp.getSignature().toShortString(),
                System.currentTimeMillis() - start);
            throw ex;
        }
    }
}

// Custom annotation used by the @Around advice:
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LogExecutionTime {}

// Usage:
@Service
public class ReportService {
    @LogExecutionTime
    public Report generateMonthlyReport() { ... }
}
```

---

## 19. Real-World Complete Example

A production-ready REST API bringing all annotations together:

```java
// ── Domain Model ──────────────────────────────────────────────────────────────
@Entity
@Table(name = "products")
public class Product {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 200)                 private String name;
    @Column(nullable = false)                               private String category;
    @Column(nullable = false)                               private BigDecimal price;
    @Column(nullable = false)                               private int stock;
    @Enumerated(EnumType.STRING)                            private ProductStatus status;
    @CreationTimestamp                                      private LocalDateTime createdAt;
    // getters, setters, constructors
}

// ── Repository ────────────────────────────────────────────────────────────────
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    Page<Product> findByCategoryAndStatus(String category, ProductStatus status, Pageable page);
    @Query("SELECT p FROM Product p WHERE p.price BETWEEN :min AND :max AND p.status = 'ACTIVE'")
    List<Product> findInPriceRange(@Param("min") BigDecimal min, @Param("max") BigDecimal max);
    boolean existsByNameAndCategory(String name, String category);
}

// ── DTOs ──────────────────────────────────────────────────────────────────────
public record CreateProductRequest(
    @NotBlank @Size(max = 200)  String name,
    @NotBlank                   String category,
    @NotNull @DecimalMin("0.01") BigDecimal price,
    @Min(0)                     int stock
) {}

public record ProductDto(Long id, String name, String category, BigDecimal price, int stock) {
    static ProductDto from(Product p) {
        return new ProductDto(p.getId(), p.getName(), p.getCategory(), p.getPrice(), p.getStock());
    }
}

// ── Service ───────────────────────────────────────────────────────────────────
@Service
@CacheConfig(cacheNames = "products")
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepo;
    private final ApplicationEventPublisher events;

    public ProductService(ProductRepository productRepo, ApplicationEventPublisher events) {
        this.productRepo = productRepo;
        this.events      = events;
    }

    @Cacheable(key = "#id")
    public ProductDto findById(Long id) {
        return productRepo.findById(id)
            .map(ProductDto::from)
            .orElseThrow(() -> new ProductNotFoundException(id));
    }

    public Page<ProductDto> findAll(String category, Pageable pageable) {
        return productRepo.findByCategoryAndStatus(category, ProductStatus.ACTIVE, pageable)
                          .map(ProductDto::from);
    }

    @Transactional
    @CachePut(key = "#result.id()")
    public ProductDto create(CreateProductRequest req) {
        if (productRepo.existsByNameAndCategory(req.name(), req.category())) {
            throw new DuplicateProductException("Product already exists: " + req.name());
        }
        Product saved = productRepo.save(
            new Product(req.name(), req.category(), req.price(), req.stock())
        );
        events.publishEvent(new ProductCreatedEvent(saved.getId()));
        return ProductDto.from(saved);
    }

    @Transactional
    @CacheEvict(key = "#id")
    public void delete(Long id) {
        productRepo.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
        productRepo.deleteById(id);
    }
}

// ── Controller ────────────────────────────────────────────────────────────────
@RestController
@RequestMapping("/api/v1/products")
@Validated
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public Page<ProductDto> list(
            @RequestParam(required = false, defaultValue = "Electronics") String category,
            @RequestParam(defaultValue = "0")    int page,
            @RequestParam(defaultValue = "20")   int size,
            @RequestParam(defaultValue = "name") String sortBy) {
        return productService.findAll(category,
            PageRequest.of(page, size, Sort.by(sortBy)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductDto> getById(
            @PathVariable @Positive Long id) {
        return ResponseEntity.ok(productService.findById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or hasRole('PRODUCT_MANAGER')")
    public ProductDto create(@RequestBody @Valid CreateProductRequest request) {
        return productService.create(request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable @Positive Long id) {
        productService.delete(id);
    }
}

// ── Global Exception Handler ──────────────────────────────────────────────────
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(ProductNotFoundException ex) {
        return Map.of("error", "NOT_FOUND", "message", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
          .forEach(e -> fieldErrors.put(e.getField(), e.getDefaultMessage()));
        return Map.of("error", "VALIDATION_FAILED", "fields", fieldErrors);
    }
}

// ── application.properties ────────────────────────────────────────────────────
// spring.datasource.url=jdbc:postgresql://localhost:5432/shopdb
// spring.jpa.hibernate.ddl-auto=validate
// spring.cache.type=caffeine
// spring.cache.caffeine.spec=maximumSize=500,expireAfterWrite=10m
// app.schedule.cleanup=0 0 3 * * ?
```

---

## 20. Interview Questions & Answers

| # | Question | Answer |
|---|----------|--------|
| 1 | What does `@SpringBootApplication` do? | It is a meta-annotation combining `@SpringBootConfiguration`, `@EnableAutoConfiguration`, and `@ComponentScan`. It bootstraps the entire Spring Boot application from the class it's placed on. |
| 2 | Difference between `@Component`, `@Service`, `@Repository`, `@Controller`? | All are `@Component` aliases — functionally identical for component scanning. The difference is semantic: `@Service` = business logic, `@Repository` = data access (adds exception translation), `@Controller` = web MVC, `@Component` = generic. |
| 3 | `@RestController` vs `@Controller`? | `@RestController` = `@Controller` + `@ResponseBody` on every method. Methods return data serialized to JSON/XML directly. `@Controller` methods return view names resolved by a ViewResolver. |
| 4 | Constructor vs Field vs Setter injection? | Constructor: recommended (immutable, testable, explicit). Field (`@Autowired` on field): easy but hidden dependencies, hard to unit-test. Setter: for optional dependencies. Spring 4.3+ makes `@Autowired` optional on single-constructor classes. |
| 5 | `@Primary` vs `@Qualifier`? | `@Primary` marks a bean as the default when multiple exist — no change needed at injection site. `@Qualifier("name")` explicitly names which bean to inject — must be specified at the injection site. |
| 6 | What is `@ConfigurationProperties`? | Binds a prefix of `application.properties` to a typed POJO. Cleaner than many `@Value` annotations. Supports nested objects, lists, validation. Requires `@EnableConfigurationProperties` or `@Component`. |
| 7 | How does `@Transactional` work? | Spring wraps the bean in a proxy. On method call, proxy begins a transaction, calls the real method, commits on success, or rolls back on `RuntimeException` (default). Only works on `public` methods of Spring-managed beans called from outside the bean. |
| 8 | `@Transactional` on `private` methods? | Does NOT work. Spring's AOP proxy cannot intercept private methods — the `@Transactional` annotation is silently ignored. Must be `public` (or `protected` with AspectJ weaving). |
| 9 | When does `@Transactional` NOT roll back? | By default, only rolls back on `RuntimeException` and `Error`. Checked exceptions do NOT trigger rollback unless `rollbackFor = Exception.class` is set. |
| 10 | Difference between `@Bean` and `@Component`? | `@Component` (and stereotypes) are detected via classpath scanning. `@Bean` is declared explicitly in `@Configuration` classes — gives more control (conditional creation, wiring logic, third-party classes). |
| 11 | What is `@Conditional` vs `@Profile`? | `@Profile` is a specialization of `@ConditionalOnProperty`. `@Conditional` and its variants (`@ConditionalOnClass`, `@ConditionalOnMissingBean`, etc.) provide fine-grained control over auto-configuration. |
| 12 | `@PathVariable` vs `@RequestParam`? | `@PathVariable` extracts values from URI path segments (`/users/{id}`). `@RequestParam` extracts values from query string (`/users?page=2`). |
| 13 | `@RequestBody` vs `@ModelAttribute`? | `@RequestBody` deserializes the HTTP body (JSON/XML) using `HttpMessageConverter` (Jackson). `@ModelAttribute` binds form fields or query params to a Java object. |
| 14 | What does `@EnableAsync` do? | Activates Spring's async processing. Methods annotated with `@Async` run in a separate thread pool instead of the caller's thread. Returns `CompletableFuture<T>` for results. |
| 15 | What is `@ControllerAdvice`? | A specialization of `@Component` that provides global `@ExceptionHandler`, `@InitBinder`, and `@ModelAttribute` methods across all controllers. `@RestControllerAdvice` adds `@ResponseBody`. |
| 16 | `@PostConstruct` vs `InitializingBean`? | Both run after bean initialization. `@PostConstruct` (JSR-250, Jakarta) is annotation-based and doesn't tie code to Spring API. `InitializingBean.afterPropertiesSet()` is Spring-specific. `@PostConstruct` is preferred. |
| 17 | How does `@Cacheable` work? | Spring wraps the bean in a proxy. On method call, the proxy checks the cache for the key. Cache hit: returns cached value without calling the method. Cache miss: calls method, stores result, returns it. |
| 18 | What is `@Scheduled` vs `@Async`? | `@Scheduled` runs a method on a time-based schedule (cron, fixed rate, fixed delay) — used for periodic tasks. `@Async` runs a method asynchronously when called — used to offload work to a thread pool. |
| 19 | What does `@Valid` vs `@Validated` do? | `@Valid` (JSR-303) triggers Bean Validation on method arguments — validates nested objects. `@Validated` (Spring) enables group-based validation AND enables method-level validation in `@Service` classes (not just controllers). |
| 20 | What is `@EventListener`? | Marks a method to listen for Spring application events. Events can be custom POJOs published via `ApplicationEventPublisher.publishEvent()`. Can be combined with `@Async` for non-blocking event handling. |

---

## 21. Complete Reference Summary

### Annotation Quick Lookup

```
BOOTSTRAP
  @SpringBootApplication     → Main class; enables auto-config + component scan
  @EnableAutoConfiguration   → Auto-configure based on classpath
  @ComponentScan             → Define where to scan for beans

STEREOTYPES (auto-detected by component scan)
  @Component                 → Generic Spring-managed bean
  @Service                   → Business logic layer
  @Repository                → Data access layer (+ exception translation)
  @Controller                → Spring MVC view controller
  @RestController            → REST API controller (@Controller + @ResponseBody)

DEPENDENCY INJECTION
  @Autowired                 → Inject dependency (constructor/setter/field)
  @Qualifier("name")         → Specify which bean to inject (disambiguation)
  @Primary                   → Default bean when multiple candidates exist
  @Value("${prop}")          → Inject property / SpEL expression
  @Lazy                      → Defer bean creation until first use

CONFIGURATION
  @Configuration             → Source of @Bean definitions
  @Bean                      → Register method return value as Spring bean
  @ConfigurationProperties   → Bind property prefix to typed POJO
  @Profile("name")           → Activate bean only in specified profile
  @PropertySource("file")    → Load custom .properties file
  @Import(Config.class)      → Import another @Configuration class

HTTP MAPPING
  @RequestMapping            → Map HTTP request (base mapping)
  @GetMapping                → HTTP GET
  @PostMapping               → HTTP POST
  @PutMapping                → HTTP PUT
  @PatchMapping              → HTTP PATCH
  @DeleteMapping             → HTTP DELETE

REQUEST DATA
  @PathVariable              → Extract from URI path /{id}
  @RequestParam              → Extract from query string ?key=val
  @RequestBody               → Deserialize HTTP body (JSON → object)
  @RequestHeader             → Extract HTTP header value
  @CookieValue               → Extract cookie value
  @ModelAttribute            → Bind form/query data to object

RESPONSE
  @ResponseBody              → Write return value to HTTP body
  @ResponseStatus            → Set HTTP status code
  @ExceptionHandler          → Handle exception in controller
  @ControllerAdvice          → Global controller-level advice
  @RestControllerAdvice      → @ControllerAdvice + @ResponseBody

DATA / JPA
  @Entity                    → JPA entity (maps to DB table)
  @Table                     → Customize table name/constraints
  @Id                        → Primary key field
  @GeneratedValue            → Auto-generate PK
  @Column                    → Customize column mapping
  @OneToMany / @ManyToOne    → Relationship mappings
  @ManyToMany                → Many-to-many relationship
  @Transient                 → Field not persisted
  @Embedded / @Embeddable    → Embed value object in entity
  @Query                     → Custom JPQL or native SQL query
  @Modifying                 → Marks @Query as UPDATE/DELETE

VALIDATION
  @Valid / @Validated        → Trigger Bean Validation
  @NotNull / @NotBlank       → Null/blank constraints
  @Size / @Min / @Max        → Size/range constraints
  @Email / @Pattern          → Format constraints
  @Past / @Future            → Date constraints

TRANSACTION
  @Transactional             → Wrap in DB transaction
  @EnableTransactionManagement → Enable TX management

ASYNC / SCHEDULING
  @Async                     → Run method in thread pool
  @Scheduled                 → Schedule method (cron/fixedRate/fixedDelay)
  @EnableAsync               → Activate @Async
  @EnableScheduling          → Activate @Scheduled

CACHING
  @Cacheable                 → Cache method result
  @CachePut                  → Update cache (always execute method)
  @CacheEvict                → Remove from cache
  @CacheConfig               → Default cache config for class
  @EnableCaching             → Activate caching

SECURITY
  @PreAuthorize              → Check before method (SpEL)
  @PostAuthorize             → Check after method (SpEL, can use return value)
  @Secured                   → Simple role-based access
  @PreFilter / @PostFilter   → Filter collection arguments/returns
  @AuthenticationPrincipal   → Inject current authenticated user

TESTING
  @SpringBootTest            → Full integration test
  @WebMvcTest                → Web layer only test
  @DataJpaTest               → JPA layer only test
  @MockBean                  → Mock bean in Spring context
  @ActiveProfiles            → Set active profiles for test

CONDITIONAL
  @ConditionalOnProperty     → Only if property has value
  @ConditionalOnClass        → Only if class on classpath
  @ConditionalOnMissingBean  → Only if no other bean of type exists
  @ConditionalOnBean         → Only if another bean exists
  @ConditionalOnExpression   → Only if SpEL expression is true

LIFECYCLE / EVENTS
  @PostConstruct             → Run after bean initialized
  @PreDestroy                → Run before bean destroyed
  @EventListener             → Listen for application events

AOP
  @Aspect                    → Mark class as AOP aspect
  @Pointcut                  → Define reusable pointcut expression
  @Before                    → Run before matched method
  @After                     → Run after matched method (always)
  @AfterReturning            → Run after successful return
  @AfterThrowing             → Run after exception thrown
  @Around                    → Wrap entire method execution
```

---

*Made with ❤️ for Spring Boot developers — covers Spring Boot 3.x / Spring Framework 6.x*
