# 🔗 Microservices Inter-Service Communication Patterns

> A comprehensive deep-dive into every pattern, protocol, and strategy for service-to-service communication in microservices — theory, diagrams, trade-offs, failure handling, and production-ready code examples.

---

## 📋 Table of Contents

- [Communication Fundamentals](#-communication-fundamentals)
- [Synchronous Communication](#-synchronous-communication)
  - [REST / HTTP](#1-rest--http)
  - [gRPC](#2-grpc)
  - [GraphQL](#3-graphql)
  - [WebSocket](#4-websocket)
- [Asynchronous Communication](#-asynchronous-communication)
  - [Message Queue (Point-to-Point)](#5-message-queue-point-to-point)
  - [Publish / Subscribe](#6-publish--subscribe)
  - [Event Streaming (Kafka)](#7-event-streaming-kafka)
  - [Event-Driven Architecture](#8-event-driven-architecture)
- [Hybrid Patterns](#-hybrid-patterns)
  - [Request-Reply over Messaging](#9-request-reply-over-messaging)
  - [Saga Pattern (Choreography & Orchestration)](#10-saga-pattern)
  - [Outbox Pattern](#11-outbox-pattern)
- [API Gateway & Service Mesh](#-api-gateway--service-mesh)
  - [API Gateway](#12-api-gateway)
  - [Backend for Frontend (BFF)](#13-backend-for-frontend-bff)
  - [Service Mesh](#14-service-mesh)
- [Service Discovery](#-service-discovery)
- [Resilience Patterns](#-resilience-patterns)
  - [Circuit Breaker](#15-circuit-breaker)
  - [Retry with Backoff](#16-retry-with-exponential-backoff)
  - [Bulkhead](#17-bulkhead)
  - [Timeout](#18-timeout)
  - [Rate Limiting](#19-rate-limiting)
- [Data Serialization Formats](#-data-serialization-formats)
- [Security in Inter-Service Communication](#-security-in-inter-service-communication)
- [Observability for Communication](#-observability-for-communication)
- [Pattern Selection Guide](#-pattern-selection-guide)
- [Full Architecture Example](#-full-architecture-example)
- [Summary Comparison Table](#-summary-comparison-table)

---

## 🧭 Communication Fundamentals

### The Two Dimensions of Service Communication

Every inter-service communication decision sits on two axes:

```
         SYNCHRONOUS                          ASYNCHRONOUS
         (caller waits)                       (caller continues)
              │                                      │
   ┌──────────▼──────────┐              ┌────────────▼────────────┐
   │  REST / HTTP        │              │  Message Queues         │
   │  gRPC               │              │  Pub/Sub                │
   │  GraphQL            │              │  Event Streaming        │
   │  WebSocket          │              │  Event-Driven Arch.     │
   └─────────────────────┘              └─────────────────────────┘

         ONE-TO-ONE                           ONE-TO-MANY
         (unicast)                            (broadcast/multicast)
              │                                      │
   ┌──────────▼──────────┐              ┌────────────▼────────────┐
   │  REST               │              │  Pub/Sub Topics         │
   │  gRPC               │              │  Kafka Topics           │
   │  Message Queue      │              │  Event Bus              │
   └─────────────────────┘              └─────────────────────────┘
```

### Coupling Dimensions

When choosing a communication pattern, evaluate coupling across four dimensions:

| Coupling Type | Description | Synchronous | Asynchronous |
|---|---|---|---|
| **Temporal** | Both parties must be available at same time | High ❌ | Low ✅ |
| **Behavioral** | Caller knows how callee works | Medium | Low |
| **Spatial** | Caller knows callee's address | High ❌ | Low ✅ |
| **Data** | Shared data model / schema | Medium | Medium |

### The Fallacies of Distributed Computing

Before choosing any pattern, internalize these hard truths:

```
1. The network is reliable              ← FALSE: packets drop, links fail
2. Latency is zero                      ← FALSE: expect 1-100ms per hop
3. Bandwidth is infinite                ← FALSE: large payloads hurt
4. The network is secure                ← FALSE: always encrypt
5. Topology doesn't change             ← FALSE: services scale, move, restart
6. There is one administrator          ← FALSE: many teams, many configs
7. Transport cost is zero              ← FALSE: serialization, TCP overhead
8. The network is homogeneous          ← FALSE: many protocols, versions
```

Every communication pattern is a strategy to mitigate one or more of these fallacies.

---

## 📡 Synchronous Communication

In synchronous communication, the calling service **blocks and waits** for the response before continuing.

---

### 1. REST / HTTP

**REST (Representational State Transfer)** over HTTP is the most widely used inter-service communication style. It maps operations to HTTP verbs (GET, POST, PUT, PATCH, DELETE) on resources (URLs).

#### How It Works

```
Order Service                     Inventory Service
     │                                   │
     │──GET /inventory/items/sku-456─────→│
     │                                   │ loads stock
     │←──200 OK { stock: 42 }────────────│
     │                                   │
     │──PUT /inventory/reserve───────────→│
     │   { orderId, sku, qty: 2 }        │ reserve stock
     │←──200 OK { reservationId }────────│
     │
     │ (Order Service continues only after response)
```

#### REST Design for Inter-Service Communication

```
Resource Naming:
  ✅ /orders/{id}
  ✅ /orders/{id}/items
  ✅ /users/{id}/addresses
  ❌ /getOrder?orderId=123
  ❌ /createOrderAndNotifyUser

HTTP Methods & Semantics:
  GET    → read, idempotent, cacheable
  POST   → create, not idempotent
  PUT    → full replace, idempotent
  PATCH  → partial update, not always idempotent
  DELETE → remove, idempotent

Status Codes (use correctly!):
  200 OK          → success with body
  201 Created     → resource created (POST)
  204 No Content  → success, no body (DELETE, some PUTs)
  400 Bad Request → validation error (client fault)
  401 Unauthorized → not authenticated
  403 Forbidden    → authenticated but not authorized
  404 Not Found    → resource doesn't exist
  409 Conflict     → state conflict (duplicate, version mismatch)
  422 Unprocessable → business rule violation
  429 Too Many Requests → rate limited
  500 Internal Server Error → server fault
  503 Service Unavailable   → service temporarily down
```

#### Spring Boot REST Client — OpenFeign

```java
// Feign declarative HTTP client — hides HTTP boilerplate

@FeignClient(
    name = "inventory-service",
    url = "${services.inventory.url}",
    configuration = FeignConfig.class,
    fallback = InventoryClientFallback.class
)
public interface InventoryClient {

    @GetMapping("/inventory/items/{sku}")
    InventoryItemResponse getItem(@PathVariable String sku);

    @PostMapping("/inventory/reserve")
    ReservationResponse reserve(@RequestBody ReservationRequest request);

    @DeleteMapping("/inventory/reservations/{reservationId}")
    void cancelReservation(@PathVariable String reservationId);
}

// Feign configuration (interceptors, decoders, timeouts)
@Configuration
public class FeignConfig {

    @Bean
    public Request.Options requestOptions() {
        return new Request.Options(
            2, TimeUnit.SECONDS,   // connect timeout
            5, TimeUnit.SECONDS,   // read timeout
            true                    // follow redirects
        );
    }

    @Bean
    public RequestInterceptor authInterceptor(JwtService jwtService) {
        return template -> template.header(
            "Authorization", "Bearer " + jwtService.getServiceToken()
        );
    }

    @Bean
    public ErrorDecoder errorDecoder() {
        return (methodKey, response) -> switch (response.status()) {
            case 404 -> new ResourceNotFoundException("Resource not found");
            case 409 -> new ConflictException("Conflict: " + methodKey);
            case 429 -> new RateLimitException("Rate limited by " + methodKey);
            default  -> new ServiceException("Service error: " + response.status());
        };
    }
}

// Fallback for circuit breaker
@Component
public class InventoryClientFallback implements InventoryClient {

    @Override
    public InventoryItemResponse getItem(String sku) {
        // Return cached/default response instead of failing
        return InventoryItemResponse.unavailable(sku);
    }

    @Override
    public ReservationResponse reserve(ReservationRequest request) {
        throw new ServiceUnavailableException("Inventory service is currently unavailable");
    }
}
```

#### REST Trade-offs

| ✅ Pros | ❌ Cons |
|---|---|
| Universal — every language supports HTTP | Temporal coupling — both must be up |
| Human-readable JSON | Higher latency than binary protocols |
| Easy to test with curl / Postman | No built-in streaming |
| Great tooling (Swagger, Postman) | No schema enforcement (unless OpenAPI) |
| Cacheable (GET requests) | Overhead of HTTP/JSON per call |
| Stateless — scales easily | Error handling is informal |

---

### 2. gRPC

**gRPC** (Google Remote Procedure Call) is a high-performance RPC framework using **Protocol Buffers** (protobuf) for serialization and **HTTP/2** for transport.

#### Why gRPC Beats REST for Internal Services

```
Comparison (same payload):

REST/JSON:
  {"orderId":"ord-123","customerId":"cust-456","status":"PENDING","total":9999}
  → 78 bytes JSON + HTTP/1.1 overhead
  → Parse: text parsing required
  → Latency: ~2-5ms per hop

gRPC/Protobuf:
  (binary encoding, field numbers instead of names)
  → ~20 bytes binary (4x smaller!)
  → Parse: zero-copy binary deserialization
  → Latency: ~0.5-1ms per hop

gRPC advantages:
  ✅ 5-10x faster than REST
  ✅ 60-80% smaller payload (Protobuf vs JSON)
  ✅ HTTP/2: multiplexing, header compression, keep-alive
  ✅ Strongly typed contracts (proto files = shared schema)
  ✅ Bidirectional streaming
  ✅ Code generation for any language
```

#### Protocol Buffer Definition

```protobuf
// order_service.proto
syntax = "proto3";

package com.example.order;

option java_multiple_files = true;
option java_package = "com.example.grpc.order";

import "google/protobuf/timestamp.proto";

// ── Service Definition ──────────────────────────────────────────────
service OrderService {
    // Unary: one request, one response
    rpc GetOrder (GetOrderRequest) returns (OrderResponse);
    rpc PlaceOrder (PlaceOrderRequest) returns (PlaceOrderResponse);

    // Server streaming: one request, stream of responses
    rpc StreamOrderUpdates (OrderStreamRequest) returns (stream OrderUpdate);

    // Client streaming: stream of requests, one response
    rpc BatchCreateOrders (stream PlaceOrderRequest) returns (BatchOrderResponse);

    // Bidirectional streaming
    rpc OrderChat (stream ChatMessage) returns (stream ChatMessage);
}

// ── Messages ────────────────────────────────────────────────────────
message GetOrderRequest {
    string order_id = 1;
}

message PlaceOrderRequest {
    string customer_id = 1;
    repeated OrderItem items = 2;
    ShippingAddress shipping_address = 3;
}

message OrderItem {
    string product_id = 1;
    int32 quantity = 2;
    int64 unit_price_cents = 3;
}

message ShippingAddress {
    string street = 1;
    string city = 2;
    string country = 3;
    string postal_code = 4;
}

message OrderResponse {
    string order_id = 1;
    string status = 2;
    int64 total_cents = 3;
    google.protobuf.Timestamp created_at = 4;
    repeated OrderItem items = 5;
}

message PlaceOrderResponse {
    string order_id = 1;
    bool success = 2;
    string message = 3;
}

message OrderStreamRequest {
    string order_id = 1;
}

message OrderUpdate {
    string order_id = 1;
    string status = 2;
    string message = 3;
    google.protobuf.Timestamp updated_at = 4;
}
```

#### Spring Boot gRPC Server

```java
// pom.xml additions:
// grpc-spring-boot-starter (net.devh)
// protobuf-maven-plugin (generates Java from .proto)

@GrpcService
@RequiredArgsConstructor
public class OrderGrpcService extends OrderServiceGrpc.OrderServiceImplBase {

    private final OrderRepository orderRepository;
    private final OrderDomainService orderDomainService;

    // ── Unary RPC ──────────────────────────────────────────────────
    @Override
    public void getOrder(GetOrderRequest request, StreamObserver<OrderResponse> observer) {
        try {
            Order order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> Status.NOT_FOUND
                    .withDescription("Order not found: " + request.getOrderId())
                    .asRuntimeException());

            OrderResponse response = mapToProto(order);
            observer.onNext(response);
            observer.onCompleted();
        } catch (StatusRuntimeException e) {
            observer.onError(e);
        } catch (Exception e) {
            observer.onError(Status.INTERNAL
                .withDescription("Internal error")
                .withCause(e)
                .asRuntimeException());
        }
    }

    // ── Server Streaming RPC ───────────────────────────────────────
    @Override
    public void streamOrderUpdates(OrderStreamRequest request,
                                    StreamObserver<OrderUpdate> observer) {
        // Push real-time updates to client
        orderDomainService.subscribeToOrderUpdates(request.getOrderId(), update -> {
            if (!observer.isReady()) return;  // backpressure

            observer.onNext(OrderUpdate.newBuilder()
                .setOrderId(update.orderId())
                .setStatus(update.status())
                .setMessage(update.message())
                .build());

            if (update.isFinal()) {
                observer.onCompleted();
            }
        });
    }

    private OrderResponse mapToProto(Order order) {
        return OrderResponse.newBuilder()
            .setOrderId(order.getId())
            .setStatus(order.getStatus().name())
            .setTotalCents(order.getTotalCents())
            .addAllItems(order.getItems().stream().map(this::itemToProto).toList())
            .build();
    }
}
```

#### Spring Boot gRPC Client

```java
@Service
@RequiredArgsConstructor
public class InventoryGrpcClient {

    @GrpcClient("inventory-service")   // matches application.yml config
    private InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub;

    @GrpcClient("inventory-service")
    private InventoryServiceGrpc.InventoryServiceStub asyncInventoryStub;

    // Unary call (blocking)
    public StockResponse checkStock(String sku, int quantity) {
        try {
            return inventoryStub
                .withDeadlineAfter(3, TimeUnit.SECONDS)   // timeout
                .checkStock(StockRequest.newBuilder()
                    .setSku(sku)
                    .setQuantity(quantity)
                    .build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new ProductNotFoundException(sku);
            }
            throw new InventoryServiceException("Stock check failed", e);
        }
    }

    // Async streaming call
    public void streamStockUpdates(String sku, Consumer<StockUpdate> handler) {
        asyncInventoryStub.streamStockUpdates(
            StockStreamRequest.newBuilder().setSku(sku).build(),
            new StreamObserver<>() {
                @Override public void onNext(StockUpdate update) { handler.accept(update); }
                @Override public void onError(Throwable t) { log.error("Stream error", t); }
                @Override public void onCompleted() { log.info("Stream completed for {}", sku); }
            }
        );
    }
}
```

#### gRPC Error Handling — Status Codes

```java
// gRPC has its own status codes (map to HTTP codes in gRPC-Web):
Status.OK              // 200
Status.NOT_FOUND       // 404 — resource not found
Status.ALREADY_EXISTS  // 409 — duplicate
Status.INVALID_ARGUMENT // 400 — bad input
Status.PERMISSION_DENIED // 403 — unauthorized action
Status.UNAUTHENTICATED  // 401 — not authenticated
Status.RESOURCE_EXHAUSTED // 429 — rate limited
Status.UNAVAILABLE     // 503 — service down
Status.DEADLINE_EXCEEDED // 504 — timeout
Status.INTERNAL        // 500 — server error

// Usage:
throw Status.NOT_FOUND
    .withDescription("Order not found: " + orderId)
    .augmentDescription("Check that the orderId is correct")
    .asRuntimeException();
```

#### gRPC Trade-offs

| ✅ Pros | ❌ Cons |
|---|---|
| 5-10x faster than REST | Steeper learning curve |
| Strongly typed contracts (proto) | Not human-readable (binary) |
| HTTP/2 multiplexing | Harder to debug (need gRPC tools) |
| Code generation (all languages) | Browser support limited (gRPC-Web) |
| Bidirectional streaming | Proto schema evolution requires care |
| Built-in deadlines & cancellation | Less tooling than REST |

---

### 3. GraphQL

**GraphQL** is a query language for APIs where the client specifies **exactly what data it needs** — no over-fetching or under-fetching.

#### How It Works

```
REST problem — over-fetching:
GET /users/123 → returns 30 fields, but UI only needs name + email

REST problem — under-fetching (N+1):
GET /orders/123         → get order
GET /users/order.userId → get user (2nd call)
GET /products/item.sku  → get product (3rd call)

GraphQL solution — one query, exactly what you need:
POST /graphql
{
  order(id: "123") {
    id
    status
    total
    customer {         ← resolved by User Service
      name
      email
    }
    items {
      product {        ← resolved by Product Service
        name
        imageUrl
      }
      quantity
    }
  }
}
→ ONE request, returns EXACTLY requested fields ✅
```

#### GraphQL Federation — Cross-Service Schema

```graphql
# Order Service — owns Order type
type Order @key(fields: "id") {
    id: ID!
    status: String!
    total: Float!
    customerId: ID!
    customer: User         # ← resolved by User Service
    items: [OrderItem!]!
}

# User Service — extends with User type
type User @key(fields: "id") {
    id: ID!
    name: String!
    email: String!
    orders: [Order!]!      # ← resolved by Order Service
}

# Product Service — extends with Product type
type Product @key(fields: "id") {
    id: ID!
    name: String!
    price: Float!
    stockLevel: Int!       # ← resolved by Inventory Service
}

# Apollo Router stitches all schemas → unified supergraph
# Client talks to one endpoint, router delegates to correct service
```

#### Spring Boot GraphQL

```java
@Controller
public class OrderGraphQLController {

    private final OrderRepository orderRepository;
    private final UserServiceClient userClient;

    @QueryMapping
    public Order orderById(@Argument String id) {
        return orderRepository.findById(id)
            .orElseThrow(() -> new GraphQLNotFoundException("Order", id));
    }

    @QueryMapping
    public List<Order> myOrders(@AuthenticationPrincipal UserDetails user) {
        return orderRepository.findByCustomerId(user.getUsername());
    }

    @MutationMapping
    public Order placeOrder(@Argument PlaceOrderInput input,
                            @AuthenticationPrincipal UserDetails user) {
        return orderService.placeOrder(user.getUsername(), input);
    }

    @SubscriptionMapping
    public Publisher<OrderUpdate> orderUpdates(@Argument String orderId) {
        return orderUpdatePublisher.getUpdatesForOrder(orderId);
    }

    // Batch loader — solves N+1 for nested customer resolution
    @BatchMapping
    public Map<Order, User> customer(List<Order> orders) {
        List<String> customerIds = orders.stream()
            .map(Order::getCustomerId).distinct().toList();

        Map<String, User> usersById = userClient.getUsersByIds(customerIds)
            .stream().collect(Collectors.toMap(User::getId, u -> u));

        return orders.stream()
            .collect(Collectors.toMap(o -> o, o -> usersById.get(o.getCustomerId())));
    }
}
```

#### GraphQL Trade-offs

| ✅ Pros | ❌ Cons |
|---|---|
| Exact data fetching (no over/under-fetch) | Complex server-side implementation |
| Single endpoint, flexible queries | N+1 problem requires DataLoader |
| Strong type system (schema) | Query depth attacks (limit depth) |
| Introspection & self-documenting | Caching is harder than REST |
| Great for BFF pattern | Learning curve for teams |
| Subscriptions for real-time | File uploads are awkward |

---

### 4. WebSocket

**WebSocket** provides a **full-duplex, persistent connection** between client and server for real-time bidirectional communication.

#### Use Cases in Microservices

```
Real-time notifications:    Order status changed → push to browser
Live dashboards:            Metrics, active orders count
Collaborative features:     Multi-user document editing
Chat / messaging:           Customer support, team chat
Live auction / trading:     Price feed, bid updates
IoT data streams:           Sensor readings in real-time
```

#### Spring Boot WebSocket with STOMP

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // In-memory broker for subscriptions
        registry.enableSimpleBroker("/topic", "/queue");
        // Prefix for client-to-server messages
        registry.setApplicationDestinationPrefixes("/app");
        // Prefix for user-specific messages
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns("*")
            .withSockJS();   // fallback for browsers without WS support
    }
}

@Controller
@RequiredArgsConstructor
public class OrderWebSocketController {

    private final SimpMessagingTemplate messagingTemplate;

    // Client subscribes to: /topic/orders/{orderId}
    // Server pushes updates when order status changes
    public void pushOrderUpdate(String orderId, OrderUpdate update) {
        messagingTemplate.convertAndSend(
            "/topic/orders/" + orderId,
            update
        );
    }

    // User-specific notification (private channel)
    public void pushUserNotification(String userId, Notification notification) {
        messagingTemplate.convertAndSendToUser(
            userId,
            "/queue/notifications",
            notification
        );
    }

    // Handle messages from client
    @MessageMapping("/orders/{orderId}/cancel")
    @SendTo("/topic/orders/{orderId}")
    public OrderUpdate cancelOrder(@DestinationVariable String orderId,
                                    @Payload CancelRequest request,
                                    Principal user) {
        return orderService.cancel(orderId, user.getName(), request.getReason());
    }
}
```

#### Kafka → WebSocket Bridge (Event Streaming to Browser)

```java
// Consume Kafka events → push to connected WebSocket clients
@Service
@RequiredArgsConstructor
public class OrderEventBridge {

    private final SimpMessagingTemplate messagingTemplate;

    @KafkaListener(topics = "order-events", groupId = "websocket-bridge")
    public void onOrderEvent(OrderEvent event) {
        // Push to all subscribers of this order
        messagingTemplate.convertAndSend(
            "/topic/orders/" + event.getOrderId(),
            OrderUpdate.from(event)
        );

        // Push to specific user
        messagingTemplate.convertAndSendToUser(
            event.getCustomerId(),
            "/queue/order-updates",
            OrderUpdate.from(event)
        );
    }
}
```

---

## 📨 Asynchronous Communication

In asynchronous communication, the calling service **sends a message and continues immediately** — it does not wait for a response.

---

### 5. Message Queue (Point-to-Point)

A **message queue** delivers messages from one producer to exactly **one consumer**. Used for task distribution and work queues.

```
Producer                    Queue                    Consumer
   │                          │                          │
   │──send(task)──────────────→│                          │
   │  (continues immediately)  │                          │
                               │──deliver(task)───────────→│
                               │                          │ (processes task)
                               │←──ACK────────────────────│
                               │  (message removed)        │

If Consumer crashes:
                               │  (no ACK received)        │
                               │──redeliver(task)──────────→│ (new consumer)
```

#### RabbitMQ — Spring Boot Implementation

```java
// Config
@Configuration
public class RabbitMQConfig {

    @Bean
    public Queue orderProcessingQueue() {
        return QueueBuilder.durable("order.processing")
            .withArgument("x-dead-letter-exchange", "order.dlx")
            .withArgument("x-dead-letter-routing-key", "order.failed")
            .withArgument("x-message-ttl", 300_000)  // 5 min TTL
            .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable("order.failed").build();
    }

    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange("order.exchange");
    }

    @Bean
    public Binding binding(Queue orderProcessingQueue, DirectExchange orderExchange) {
        return BindingBuilder.bind(orderProcessingQueue)
            .to(orderExchange)
            .with("order.process");
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}

// Producer
@Service
@RequiredArgsConstructor
public class OrderQueueProducer {

    private final RabbitTemplate rabbitTemplate;

    public void sendOrderForProcessing(OrderProcessingTask task) {
        rabbitTemplate.convertAndSend(
            "order.exchange",
            "order.process",
            task,
            message -> {
                message.getMessageProperties().setMessageId(UUID.randomUUID().toString());
                message.getMessageProperties().setPriority(task.getPriority());
                return message;
            }
        );
        log.info("Queued order for processing: {}", task.getOrderId());
    }
}

// Consumer
@Service
@Slf4j
public class OrderProcessingConsumer {

    @RabbitListener(
        queues = "order.processing",
        concurrency = "3-10"    // 3 min, 10 max concurrent consumers
    )
    @Transactional
    public void processOrder(OrderProcessingTask task,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                              Channel channel) throws IOException {
        try {
            log.info("Processing order: {}", task.getOrderId());
            orderDomainService.process(task);
            channel.basicAck(deliveryTag, false);   // manual ACK
        } catch (BusinessException e) {
            log.warn("Business error — rejecting: {}", e.getMessage());
            channel.basicReject(deliveryTag, false);  // → Dead Letter Queue
        } catch (TransientException e) {
            log.warn("Transient error — requeuing: {}", e.getMessage());
            channel.basicNack(deliveryTag, false, true);  // → retry
        }
    }

    // Dead Letter Queue consumer
    @RabbitListener(queues = "order.failed")
    public void handleFailedOrder(OrderProcessingTask task) {
        log.error("Order permanently failed: {}", task.getOrderId());
        alertService.notifyOncall("Order processing failure", task.getOrderId());
    }
}
```

---

### 6. Publish / Subscribe

In Pub/Sub, a producer **publishes events to a topic** and **all subscribers** to that topic receive the event independently.

```
Publisher                  Topic / Exchange              Subscribers
   │                            │                    ┌────────────────┐
   │                            │──OrderCreated──────→│ Payment Service│
   │──OrderCreated──────────────→│                    └────────────────┘
   │   (publish once)           │──OrderCreated──────→┌────────────────┐
                                │                     │Inventory Service│
                                │──OrderCreated──────→└────────────────┘
                                │                    ┌────────────────┐
                                │                    │Notification Svc│
                                │                    └────────────────┘

Publisher doesn't know who's listening.
New subscriber → just subscribe to topic. No publisher change needed.
```

#### RabbitMQ Fanout Exchange

```java
// Config: Fanout exchange broadcasts to all bound queues
@Bean
public FanoutExchange orderEventExchange() {
    return new FanoutExchange("order.events");
}

@Bean
public Queue paymentServiceQueue() {
    return new Queue("order.events.payment", true);
}

@Bean
public Queue inventoryServiceQueue() {
    return new Queue("order.events.inventory", true);
}

@Bean
public Queue notificationServiceQueue() {
    return new Queue("order.events.notification", true);
}

// Each service binds its own queue to the fanout exchange
@Bean
public Binding paymentBinding() {
    return BindingBuilder.bind(paymentServiceQueue()).to(orderEventExchange());
}

// Publisher (Order Service)
rabbitTemplate.convertAndSend("order.events", "", orderCreatedEvent);

// Payment Service Consumer
@RabbitListener(queues = "order.events.payment")
public void onOrderCreated(OrderCreatedEvent event) {
    paymentService.initiatePayment(event.getOrderId(), event.getTotal());
}

// Inventory Service Consumer
@RabbitListener(queues = "order.events.inventory")
public void onOrderCreated(OrderCreatedEvent event) {
    inventoryService.reserveItems(event.getOrderId(), event.getItems());
}
```

---

### 7. Event Streaming (Kafka)

**Apache Kafka** is a distributed event streaming platform. Unlike traditional queues, Kafka **retains messages** for a configurable period, allows **multiple consumer groups**, and guarantees **ordering within a partition**.

#### Kafka Architecture

```
Producers                    Kafka Cluster                 Consumers
                         ┌─────────────────────┐
Order Service ──────────→│  Topic: order-events │──Consumer Group A──→ Payment Service
                         │                     │    (reads own offset)
Payment Service ────────→│  Partition 0        │──Consumer Group B──→ Analytics Service
                         │  Partition 1        │    (reads own offset)
Inventory Svc ──────────→│  Partition 2        │──Consumer Group C──→ Notification Svc
                         │  Partition 3        │    (reads own offset)
                         └─────────────────────┘
                              Retained: 7 days
                              Replayable ✅
                              Ordered per partition ✅
```

#### Kafka Key Concepts

```
Topic:     Named stream of records (like a category of events)
Partition: Topic is split into partitions for parallelism and ordering
           → All events with same key go to same partition (ordering per entity)
           → e.g., all events for order-123 → partition 2

Offset:    Position of a message within a partition
           → Each consumer group tracks its own offset → independent processing

Consumer Group:
           → Group of consumers that share work on a topic
           → Each partition consumed by exactly one consumer in a group
           → Add consumers → parallelism increases (up to partition count)

Retention: Kafka keeps messages for configured time (default: 7 days)
           → Replay from any offset → rebuild read models, debug issues
```

#### Spring Boot Kafka — Producer

```java
@Service
@RequiredArgsConstructor
public class OrderEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishOrderCreated(Order order) {
        OrderCreatedEvent event = OrderCreatedEvent.from(order);

        // KEY = orderId → ensures all events for same order go to same partition
        // → preserves ordering per order ✅
        CompletableFuture<SendResult<String, Object>> future =
            kafkaTemplate.send("order-events", order.getId(), event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish OrderCreated for {}: {}", order.getId(), ex.getMessage());
                // Implement outbox pattern for guaranteed delivery
            } else {
                log.debug("Published OrderCreated → partition:{}, offset:{}",
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            }
        });
    }

    // Transactional publishing (atomic with DB operation)
    @Transactional("kafkaTransactionManager")
    public void publishWithTransaction(List<DomainEvent> events) {
        events.forEach(event ->
            kafkaTemplate.send("order-events",
                event.getAggregateId(),
                event)
        );
    }
}
```

#### Spring Boot Kafka — Consumer

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final PaymentService paymentService;
    private final ProcessedEventRepository processedEventRepo;

    @KafkaListener(
        topics = "order-events",
        groupId = "payment-service",
        concurrency = "4",                    // 4 consumer threads (match partition count)
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void onOrderEvent(
            @Payload OrderEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        log.info("Received {} from partition:{} offset:{}", event.getType(), partition, offset);

        try {
            // Idempotency check — skip if already processed
            String eventId = event.getEventId();
            if (processedEventRepo.existsById(eventId)) {
                log.debug("Duplicate event skipped: {}", eventId);
                acknowledgment.acknowledge();
                return;
            }

            // Route by event type
            switch (event.getType()) {
                case "OrderCreated"   -> paymentService.initiatePayment(event);
                case "OrderCancelled" -> paymentService.refundPayment(event);
                case "OrderUpdated"   -> paymentService.updatePaymentDetails(event);
                default -> log.warn("Unknown event type: {}", event.getType());
            }

            // Mark as processed
            processedEventRepo.save(new ProcessedEvent(eventId, Instant.now()));

            // Manual commit — only after successful processing
            acknowledgment.acknowledge();

        } catch (TransientException e) {
            log.warn("Transient error — will retry: {}", e.getMessage());
            // Don't acknowledge → Kafka will redeliver
            throw e;
        } catch (Exception e) {
            log.error("Permanent error for event {}: {}", event.getEventId(), e.getMessage());
            acknowledgment.acknowledge();   // skip poison pill → send to DLT
        }
    }

    // Dead Letter Topic handler
    @KafkaListener(topics = "order-events.DLT", groupId = "payment-service-dlt")
    public void handleDeadLetter(OrderEvent event) {
        log.error("Dead letter event received: {}", event.getEventId());
        alertService.notifyOncall("Kafka DLT event", event);
    }
}
```

#### Kafka Configuration

```yaml
# application.yml
spring:
  kafka:
    bootstrap-servers: kafka1:9092,kafka2:9092,kafka3:9092
    producer:
      acks: all                # wait for all replicas to acknowledge
      retries: 3
      properties:
        enable.idempotence: true        # exactly-once producer
        max.in.flight.requests.per.connection: 5
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
    consumer:
      group-id: payment-service
      auto-offset-reset: earliest      # read from beginning if no offset
      enable-auto-commit: false        # manual commit (use Acknowledgment)
      max-poll-records: 50
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.example.events"
    listener:
      ack-mode: manual
      concurrency: 4
```

#### Kafka Trade-offs

| ✅ Pros | ❌ Cons |
|---|---|
| Extremely high throughput (millions/sec) | Operationally complex |
| Durable — messages retained on disk | Higher latency than in-memory queues |
| Replayable — rebuild any read model | Overkill for low-volume use cases |
| Multiple independent consumer groups | Schema evolution requires care |
| Ordering guaranteed per partition | Exactly-once is complex to configure |
| Horizontal scalability | Consumer lag monitoring needed |

---

### 8. Event-Driven Architecture

**Event-Driven Architecture (EDA)** is a design paradigm where services communicate exclusively through **domain events** — immutable records of things that happened.

#### Event Types

```
Domain Events (something happened in the business):
  OrderPlaced, PaymentCharged, ItemShipped, AccountClosed

Integration Events (cross-service domain events):
  → Same as domain events but published to external consumers
  → Should be stable, versioned, part of API contract

Commands (intent to change — internal):
  PlaceOrder, ChargePayment
  → Not shared across services (internal to a service)

Queries (request for data — internal):
  GetOrder, ListProducts
  → Not events — just read operations
```

#### Event Schema Design

```json
{
  "eventId": "evt-abc-123",           // unique ID (for idempotency)
  "eventType": "OrderPlaced",         // what happened
  "aggregateId": "ord-789",           // which entity
  "aggregateType": "Order",           // entity type
  "version": 1,                       // event schema version
  "occurredAt": "2024-01-10T10:30:00Z",
  "correlationId": "req-xyz-456",     // trace requests across services
  "causationId": "cmd-qqq-789",       // which command caused this event
  "payload": {                        // event-specific data
    "customerId": "cust-123",
    "items": [...],
    "total": 9999
  },
  "metadata": {
    "service": "order-service",
    "environment": "production"
  }
}
```

#### Event Versioning Strategies

```
Strategy 1 — Additive Changes Only (safest):
  v1: { orderId, customerId, total }
  v2: { orderId, customerId, total, shippingAddress }  ← new field added
  Consumers: ignore unknown fields → backward compatible ✅

Strategy 2 — Event Upcasting:
  Old consumer receives v2 event → upcaster transforms to v1 format
  New consumer receives v2 event directly

Strategy 3 — Multiple Versions in Topic:
  Topic: order-events-v1 → deprecated, maintained
  Topic: order-events-v2 → new consumers use this

Strategy 4 — Schema Registry (Confluent / AWS Glue):
  All schemas registered centrally
  Compatibility checked on publish
  BACKWARD, FORWARD, or FULL compatibility enforced
```

---

## 🔀 Hybrid Patterns

---

### 9. Request-Reply over Messaging

Asynchronous request-response — fire a message with a **replyTo** address, await response on that address. Combines async benefits with response correlation.

```
Order Service                 Kafka                   Payment Service
     │                          │                          │
     │──PaymentRequest──────────→│                          │
     │  { correlationId: "x1"   │──PaymentRequest──────────→│
     │    replyTo: "order.reply" }│                        │ processes...
     │  (continues other work)   │                          │
     │                           │←──PaymentResponse────────│
     │←──PaymentResponse─────────│  { correlationId: "x1" } │
     │  (matched by correlationId)                          │
```

```java
// Async request with CompletableFuture correlation
@Service
public class AsyncPaymentClient {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Map<String, CompletableFuture<PaymentResponse>> pendingRequests =
        new ConcurrentHashMap<>();

    public CompletableFuture<PaymentResponse> requestPayment(PaymentRequest request) {
        String correlationId = UUID.randomUUID().toString();
        CompletableFuture<PaymentResponse> future = new CompletableFuture<>();

        // Register pending future
        pendingRequests.put(correlationId, future);

        // Set timeout to prevent leaks
        future.orTimeout(30, TimeUnit.SECONDS)
              .exceptionally(ex -> {
                  pendingRequests.remove(correlationId);
                  throw new PaymentTimeoutException(correlationId);
              });

        // Send request
        kafkaTemplate.send("payment.requests",
            request.withCorrelationId(correlationId).withReplyTo("order.payment.replies"));

        return future;
    }

    // Handle reply
    @KafkaListener(topics = "order.payment.replies", groupId = "order-service")
    public void onPaymentReply(PaymentResponse response) {
        CompletableFuture<PaymentResponse> future =
            pendingRequests.remove(response.getCorrelationId());
        if (future != null) {
            future.complete(response);
        }
    }
}
```

---

### 10. Saga Pattern

A **Saga** coordinates a distributed transaction across multiple services through a sequence of local transactions and compensating actions.

*(Full deep-dive in [Distributed_Transaction_Management_Microservices.md](Distributed_Transaction_Management_Microservices.md))*

#### Choreography Saga — Communication Flow

```
OrderService──→[order-events]──→PaymentService (charges card)
                              ──→[payment-events]──→InventoryService (reserves stock)
                                                  ──→[inventory-events]──→ShippingService
                                                                        ──→[shipping-events]──→OrderService (confirms)

On failure at any step → emit failure event → upstream services compensate
```

#### Orchestration Saga — Communication Flow

```java
@Service
@RequiredArgsConstructor
public class OrderSagaOrchestrator {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final SagaStateRepository sagaRepo;

    @KafkaListener(topics = "order-events", groupId = "saga-orchestrator")
    public void onOrderCreated(OrderCreatedEvent event) {
        // Start saga
        SagaState state = SagaState.start(event.getOrderId());
        sagaRepo.save(state);

        // Step 1: request payment
        kafkaTemplate.send("payment.commands",
            ChargePaymentCommand.from(event));
    }

    @KafkaListener(topics = "payment-events", groupId = "saga-orchestrator")
    public void onPaymentEvent(PaymentEvent event) {
        SagaState state = sagaRepo.findByOrderId(event.getOrderId());

        if (event.isSuccess()) {
            state.advance(SagaStep.INVENTORY);
            sagaRepo.save(state);
            kafkaTemplate.send("inventory.commands",
                ReserveStockCommand.from(event));
        } else {
            state.fail("Payment failed: " + event.getFailureReason());
            sagaRepo.save(state);
            kafkaTemplate.send("order.commands",
                CancelOrderCommand.from(event.getOrderId(), "Payment declined"));
        }
    }

    @KafkaListener(topics = "inventory-events", groupId = "saga-orchestrator")
    public void onInventoryEvent(InventoryEvent event) {
        SagaState state = sagaRepo.findByOrderId(event.getOrderId());

        if (event.isSuccess()) {
            state.advance(SagaStep.CONFIRMED);
            sagaRepo.save(state);
            kafkaTemplate.send("order.commands",
                ConfirmOrderCommand.from(event.getOrderId()));
        } else {
            // Compensate: refund payment
            state.compensate(SagaStep.PAYMENT);
            sagaRepo.save(state);
            kafkaTemplate.send("payment.commands",
                RefundPaymentCommand.from(event.getOrderId(), state.getChargeId()));
        }
    }
}
```

---

### 11. Outbox Pattern

Guarantees **atomic** DB write + event publish by writing both to the same local transaction.

```
Service Code:
BEGIN TRANSACTION
  INSERT INTO orders VALUES (...)          ← domain state
  INSERT INTO outbox_events VALUES (...)   ← event to be published
COMMIT

Message Relay (Debezium/Polling):
  Reads outbox_events WHERE status='PENDING'
  Publishes to Kafka
  Marks as 'PUBLISHED'
```

```java
@Service
@Transactional
public class OrderService {

    private final OrderRepository orderRepo;
    private final OutboxRepository outboxRepo;

    public Order placeOrder(PlaceOrderCommand cmd) {
        // 1. Create and save domain object
        Order order = Order.create(cmd);
        orderRepo.save(order);

        // 2. Write event to outbox (same transaction!)
        OutboxEvent outboxEvent = OutboxEvent.builder()
            .aggregateId(order.getId())
            .aggregateType("Order")
            .eventType("OrderPlaced")
            .payload(objectMapper.writeValueAsString(OrderPlacedEvent.from(order)))
            .status(OutboxStatus.PENDING)
            .createdAt(Instant.now())
            .build();
        outboxRepo.save(outboxEvent);

        // If transaction commits → both saved ✅
        // If transaction rolls back → neither saved ✅
        return order;
    }
}

// Relay: scheduled job polls outbox and publishes
@Scheduled(fixedDelay = 500)   // every 500ms
@Transactional
public void relayOutboxEvents() {
    List<OutboxEvent> pending = outboxRepo.findPendingEvents(100);
    pending.forEach(event -> {
        kafkaTemplate.send("order-events", event.getAggregateId(), event.getPayload());
        event.markPublished();
        outboxRepo.save(event);
    });
}
```

---

## 🌐 API Gateway & Service Mesh

---

### 12. API Gateway

Single entry point for all external clients. Handles routing, auth, rate limiting, and protocol translation.

```
External Clients               API Gateway                   Internal Services
                         ┌────────────────────┐
Mobile App (REST)───────→│  Authentication    │──REST────→ Order Service
Web App (GraphQL)───────→│  Rate Limiting     │──gRPC────→ Inventory Service
Partners (REST v1)──────→│  SSL Termination   │──REST────→ User Service
IoT Devices─────────────→│  Request Routing   │──gRPC────→ Analytics Service
                         │  Load Balancing    │
                         │  API Versioning    │
                         │  Response Caching  │
                         └────────────────────┘
```

#### Spring Cloud Gateway

```java
@Configuration
public class GatewayConfig {

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder,
                                JwtAuthFilter jwtFilter,
                                RateLimiter rateLimiter) {
        return builder.routes()

            // Order Service routes
            .route("order-service", r -> r
                .path("/api/orders/**")
                .filters(f -> f
                    .filter(jwtFilter)
                    .requestRateLimiter(c -> c
                        .setRateLimiter(rateLimiter)
                        .setKeyResolver(userKeyResolver()))
                    .addRequestHeader("X-Internal", "true")
                    .circuitBreaker(c -> c
                        .setName("order-cb")
                        .setFallbackUri("forward:/fallback/orders"))
                    .retry(3)
                )
                .uri("lb://order-service")        // load-balanced via service registry
            )

            // Inventory Service — public read endpoint, no auth
            .route("inventory-read", r -> r
                .path("/api/products/*/stock")
                .and().method("GET")
                .filters(f -> f
                    .addResponseHeader("Cache-Control", "max-age=60"))
                .uri("lb://inventory-service")
            )

            // Auth Service — no auth filter
            .route("auth-service", r -> r
                .path("/api/auth/**")
                .uri("lb://auth-service")
            )

            .build();
    }

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> Mono.just(
            exchange.getRequest().getHeaders()
                .getFirst("X-User-Id") != null
                ? exchange.getRequest().getHeaders().getFirst("X-User-Id")
                : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
        );
    }
}
```

---

### 13. Backend for Frontend (BFF)

A dedicated API gateway per client type — each BFF shapes data for its specific consumer.

```
Mobile BFF:  Returns minimal payload (bandwidth-sensitive)
Web BFF:     Returns richer data with aggregation
Partner BFF: Returns stable versioned API

// Mobile BFF — aggregates 3 service calls into one response
@GetMapping("/mobile/orders/{id}/summary")
public MobileOrderSummary getMobileOrderSummary(@PathVariable String id) {
    // Parallel calls to multiple services
    CompletableFuture<Order> orderFuture = CompletableFuture
        .supplyAsync(() -> orderClient.getOrder(id));
    CompletableFuture<TrackingInfo> trackingFuture = CompletableFuture
        .supplyAsync(() -> shippingClient.getTracking(id));

    CompletableFuture.allOf(orderFuture, trackingFuture).join();

    return MobileOrderSummary.builder()
        .orderId(id)
        .status(orderFuture.get().getStatus())       // only needed fields
        .tracking(trackingFuture.get().getUrl())
        .estimatedDelivery(trackingFuture.get().getEta())
        .build();
    // Returns 4 fields instead of 50 ✅
}
```

---

### 14. Service Mesh

Infrastructure layer that handles **all service-to-service communication** via sidecar proxies — without application code changes.

```
┌──────────────────────────────────────────────────────────────────┐
│  Kubernetes Pod: Order Service                                   │
│  ┌─────────────────────┐    ┌──────────────────────────────────┐ │
│  │   Order Service App │    │    Envoy Sidecar Proxy (Istio)   │ │
│  │   (business logic)  │◄──►│  - mTLS encryption               │ │
│  │   Port: 8080        │    │  - Circuit breaking              │ │
│  └─────────────────────┘    │  - Retries & timeouts            │ │
│                             │  - Traffic shaping (canary)      │ │
│                             │  - Distributed tracing           │ │
│                             │  - Metrics collection            │ │
│                             └──────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
                                        │
                             ┌──────────▼──────────┐
                             │   Istio Control      │
                             │   Plane              │
                             │  (istiod)            │
                             │  - Certificate mgmt  │
                             │  - Policy enforement │
                             │  - Config distribution│
                             └─────────────────────┘
```

#### Istio Traffic Management (YAML)

```yaml
# Circuit breaker via DestinationRule
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: payment-service
spec:
  host: payment-service
  trafficPolicy:
    connectionPool:
      http:
        http1MaxPendingRequests: 100
        http2MaxRequests: 1000
    outlierDetection:
      consecutive5xxErrors: 5
      interval: 30s
      baseEjectionTime: 30s
      maxEjectionPercent: 50
  subsets:
  - name: v1
    labels:
      version: v1
  - name: v2
    labels:
      version: v2

# Canary deployment — 90/10 traffic split
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: payment-service
spec:
  hosts:
  - payment-service
  http:
  - match:
    - headers:
        x-canary-user:
          exact: "true"
    route:
    - destination:
        host: payment-service
        subset: v2
  - route:
    - destination:
        host: payment-service
        subset: v1
      weight: 90
    - destination:
        host: payment-service
        subset: v2
      weight: 10

# Retry policy
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: inventory-service
spec:
  hosts:
  - inventory-service
  http:
  - retries:
      attempts: 3
      perTryTimeout: 2s
      retryOn: gateway-error,connect-failure,retriable-4xx
    timeout: 10s
    route:
    - destination:
        host: inventory-service
```

---

## 🔍 Service Discovery

How services find each other dynamically in a cloud environment.

```
Client-Side Discovery:              Server-Side Discovery:
Service → Query Registry           Service → Load Balancer
       ← list of instances                ← forwards to instance
       → pick instance (LB logic)

           Registry                            Load Balancer
         ┌─────────┐                          ┌────────────┐
Service A│         │                Service A→│            │→ Instance 1
reg→     │ payment │←Service B       (blind)  │   (Nginx/  │→ Instance 2
         │ :8081   │  queries                 │  AWS ALB)  │→ Instance 3
         │ :8082   │                          │            │
         │ :8083   │                          └────────────┘
         └─────────┘
           (Consul/Eureka)
```

```yaml
# Kubernetes DNS-based discovery (zero code changes):
# payment-service.default.svc.cluster.local

# Spring Cloud LoadBalancer:
spring:
  cloud:
    loadbalancer:
      ribbon:
        enabled: false   # use Spring Cloud LoadBalancer, not Ribbon

# Feign with service discovery
@FeignClient(name = "payment-service")  # resolves via registry
public interface PaymentClient {
    @PostMapping("/payments/charge")
    PaymentResponse charge(@RequestBody ChargeRequest request);
}
```

---

## 🛡️ Resilience Patterns

---

### 15. Circuit Breaker

```java
@Configuration
public class Resilience4jConfig {

    @Bean
    public CircuitBreakerConfig paymentCircuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
            .failureRateThreshold(50)           // open at 50% failure rate
            .slowCallRateThreshold(80)          // open if 80% calls are slow
            .slowCallDurationThreshold(Duration.ofSeconds(3))
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .slidingWindowType(SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(10)
            .permittedNumberOfCallsInHalfOpenState(5)
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .build();
    }
}

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final PaymentClient paymentClient;

    public PaymentResponse chargePayment(ChargeRequest request) {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("payment-service");

        return cb.executeSupplier(() -> paymentClient.charge(request));
    }

    // With fallback
    public PaymentResponse chargeWithFallback(ChargeRequest request) {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("payment-service");

        return Try.ofSupplier(CircuitBreaker.decorateSupplier(cb,
                () -> paymentClient.charge(request)))
            .recover(CallNotPermittedException.class, ex ->
                PaymentResponse.deferred(request.getOrderId(), "Service temporarily unavailable"))
            .get();
    }
}
```

---

### 16. Retry with Exponential Backoff

```java
@Bean
public RetryConfig retryConfig() {
    return RetryConfig.custom()
        .maxAttempts(3)
        .waitDuration(Duration.ofMillis(500))
        .intervalFunction(IntervalFunction.ofExponentialBackoff(500, 2))
        // 500ms → 1s → 2s
        .retryOnException(e -> e instanceof TransientException ||
                               e instanceof ConnectException)
        .ignoreExceptions(BusinessException.class,
                          ValidationException.class)
        .build();
}

// Usage with annotation
@Retry(name = "inventory-service", fallbackMethod = "getStockFallback")
@CircuitBreaker(name = "inventory-service")
public StockResponse getStock(String sku) {
    return inventoryClient.getStock(sku);
}

public StockResponse getStockFallback(String sku, Exception ex) {
    log.warn("Falling back for SKU: {} — {}", sku, ex.getMessage());
    return StockResponse.unknown(sku);
}
```

---

### 17. Bulkhead

Isolate thread pools per downstream dependency to prevent one slow service from exhausting all threads.

```java
@Bean
public BulkheadConfig paymentBulkheadConfig() {
    return BulkheadConfig.custom()
        .maxConcurrentCalls(20)          // max 20 concurrent calls to payment service
        .maxWaitDuration(Duration.ofMillis(100))  // wait 100ms if pool full, then reject
        .build();
}

@Bean
public ThreadPoolBulkheadConfig inventoryThreadPoolConfig() {
    return ThreadPoolBulkheadConfig.custom()
        .maxThreadPoolSize(10)
        .coreThreadPoolSize(5)
        .queueCapacity(20)
        .keepAliveDuration(Duration.ofMillis(20))
        .build();
}

// Each service has its own thread pool → isolation ✅
@Bulkhead(name = "payment-service", type = Bulkhead.Type.THREADPOOL)
@CircuitBreaker(name = "payment-service")
@Retry(name = "payment-service")
public CompletableFuture<PaymentResponse> chargePaymentAsync(ChargeRequest request) {
    return CompletableFuture.supplyAsync(() -> paymentClient.charge(request));
}
```

---

### 18. Timeout

```java
// Feign: per-client timeout
@Bean
public Request.Options paymentServiceOptions() {
    return new Request.Options(
        500, TimeUnit.MILLISECONDS,   // connect timeout
        3000, TimeUnit.MILLISECONDS,  // read timeout
        true
    );
}

// Resilience4j TimeLimiter
@Bean
public TimeLimiterConfig timeLimiterConfig() {
    return TimeLimiterConfig.custom()
        .timeoutDuration(Duration.ofSeconds(3))
        .cancelRunningFuture(true)
        .build();
}

// Always set timeout at EVERY level:
// 1. HTTP client level (Feign/RestTemplate)
// 2. Service mesh level (Istio VirtualService)
// 3. Circuit breaker / TimeLimiter level
// → Defense in depth for timeouts ✅
```

---

### 19. Rate Limiting

```java
@Bean
public RateLimiterConfig externalApiRateLimiterConfig() {
    return RateLimiterConfig.custom()
        .limitForPeriod(100)                     // 100 calls
        .limitRefreshPeriod(Duration.ofSeconds(1)) // per second
        .timeoutDuration(Duration.ofMillis(500))  // wait max 500ms for permit
        .build();
}

@RateLimiter(name = "external-payment-gateway", fallbackMethod = "rateLimitFallback")
public PaymentResponse callExternalPaymentGateway(PaymentRequest request) {
    return externalGatewayClient.charge(request);
}

public PaymentResponse rateLimitFallback(PaymentRequest request, RequestNotPermitted ex) {
    log.warn("Rate limited — queuing payment request for retry");
    paymentRetryQueue.enqueue(request);
    return PaymentResponse.queued(request.getOrderId());
}
```

---

## 📦 Data Serialization Formats

| Format | Type | Size | Speed | Schema | Best For |
|---|---|---|---|---|---|
| **JSON** | Text | Large | Medium | Optional (OpenAPI) | REST, debugging, external APIs |
| **XML** | Text | Very Large | Slow | XSD | Legacy, SOAP |
| **Protobuf** | Binary | Very Small | Very Fast | Required (.proto) | gRPC, internal services |
| **Avro** | Binary | Small | Fast | Required (Registry) | Kafka, schema evolution |
| **MessagePack** | Binary | Small | Fast | Optional | JSON replacement |
| **CBOR** | Binary | Small | Fast | Optional | IoT, constrained devices |

#### Avro + Schema Registry (Kafka Best Practice)

```java
// avro schema: order-created.avsc
{
  "type": "record",
  "name": "OrderCreated",
  "namespace": "com.example.events",
  "fields": [
    { "name": "orderId",    "type": "string" },
    { "name": "customerId", "type": "string" },
    { "name": "total",      "type": "long" },
    { "name": "currency",   "type": { "type": "enum",
                                       "name": "Currency",
                                       "symbols": ["USD","EUR","GBP"] } },
    { "name": "createdAt",  "type": "string" },
    // New optional field — backward compatible:
    { "name": "promoCode",  "type": ["null", "string"], "default": null }
  ]
}

// Producer registers schema → Schema Registry checks compatibility
// Consumer fetches schema by ID embedded in message
// Schema evolution enforced automatically ✅
```

---

## 🔐 Security in Inter-Service Communication

### Defense in Depth

```
Layer 1 — Network Level:
  VPC / private subnets — services not reachable from internet
  Network policies (Kubernetes) — whitelist which pod can talk to which

Layer 2 — Transport Level:
  mTLS — encrypt and mutually authenticate all service-to-service traffic
  Istio manages certificates automatically ✅

Layer 3 — Application Level:
  JWT — carry user identity, roles, permissions
  Service accounts — identify calling service
  API keys — for partner/external service calls

Layer 4 — Payload Level:
  Input validation — always validate incoming data
  Output filtering — never leak internal fields
```

### JWT Propagation Pattern

```java
// Forward user JWT from incoming request to downstream services
@Component
public class JwtPropagationInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        ServletRequestAttributes attrs =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        if (attrs != null) {
            String authHeader = attrs.getRequest().getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                template.header("Authorization", authHeader);
                // Also propagate tracing headers
                template.header("X-Correlation-ID",
                    attrs.getRequest().getHeader("X-Correlation-ID"));
            }
        }
    }
}
```

### Zero Trust with mTLS (Istio)

```yaml
# Enforce mTLS for all service communication
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
  namespace: production
spec:
  mtls:
    mode: STRICT   # reject all non-mTLS traffic

# Authorization policy — only payment-service can call inventory-service
apiVersion: security.istio.io/v1beta1
kind: AuthorizationPolicy
metadata:
  name: inventory-access
  namespace: production
spec:
  selector:
    matchLabels:
      app: inventory-service
  action: ALLOW
  rules:
  - from:
    - source:
        principals:
        - "cluster.local/ns/production/sa/payment-service"
        - "cluster.local/ns/production/sa/order-service"
```

---

## 📊 Observability for Communication

### Distributed Tracing — Propagating Context

```java
// Every inter-service call must propagate trace context
// With Micrometer + OpenTelemetry → automatic propagation ✅

// Manual propagation for non-standard scenarios:
@Component
public class TracingFeignInterceptor implements RequestInterceptor {

    private final Tracer tracer;

    @Override
    public void apply(RequestTemplate template) {
        Span span = tracer.currentSpan();
        if (span != null) {
            // W3C TraceContext format (standard):
            template.header("traceparent",
                "00-" + span.context().traceId() +
                "-" + span.context().spanId() + "-01");
        }
    }
}
```

### Communication Metrics to Track

```
Per-service call metrics (Prometheus):
  http_client_requests_total{service="payment", method="POST", status="200"}
  http_client_requests_total{service="payment", method="POST", status="500"}
  http_client_request_duration_seconds{service="payment", quantile="0.99"}

Circuit breaker metrics:
  resilience4j_circuitbreaker_state{name="payment-service"}
  resilience4j_circuitbreaker_calls_total{name="payment-service", kind="failed"}

Kafka consumer metrics:
  kafka_consumer_lag{group="payment-service", topic="order-events"}
  kafka_consumer_records_consumed_total{group="payment-service"}

Alerts:
  circuit breaker OPEN for > 2 min → PagerDuty
  consumer lag > 10,000 messages → Scale consumers
  p99 latency > 3s → Investigate bottleneck
```

---

## 🗺️ Pattern Selection Guide

```
What are you trying to do?
│
├─ Request data from another service and need the answer NOW?
│    └─→ Synchronous:
│           ├─ External API / public → REST/HTTP ✅
│           ├─ Internal high-performance → gRPC ✅
│           └─ Flexible client queries → GraphQL ✅
│
├─ Notify other services something happened, don't need response?
│    └─→ Asynchronous:
│           ├─ Few consumers, simple → RabbitMQ Pub/Sub ✅
│           ├─ High throughput, durable, replayable → Kafka ✅
│           └─ Cloud-native → AWS SNS/SQS or EventBridge ✅
│
├─ Distribute work to multiple worker instances?
│    └─→ Message Queue (Point-to-Point):
│           └─ RabbitMQ / SQS work queue ✅
│
├─ Need response but want async (non-blocking)?
│    └─→ Request-Reply over Messaging:
│           └─ Kafka/RabbitMQ with correlationId ✅
│
├─ Coordinating multi-step distributed transaction?
│    └─→ Saga Pattern:
│           ├─ Simple flow → Choreography (events) ✅
│           └─ Complex flow → Orchestration (Temporal/Axon) ✅
│
├─ Guarantee atomic DB write + event publish?
│    └─→ Outbox Pattern (+ Debezium CDC) ✅
│
├─ Real-time push to browsers / mobile?
│    └─→ WebSocket + Kafka Bridge ✅
│
└─ Need to secure + observe ALL inter-service traffic?
     └─→ Service Mesh (Istio/Linkerd) ✅
```

---

## 🏗️ Full Architecture Example

### E-Commerce Platform — Full Communication Map

```
                              ┌─────────────────────┐
External Clients              │     API Gateway      │
   Browser ──REST────────────→│  (Spring Cloud GW)   │
   Mobile ───REST────────────→│                     │
   Partners──REST v1─────────→│  Auth │ Rate Limit  │
                              │  Route│ SSL Term.   │
                              └─────────────────────┘
                                         │ (mTLS via Istio to all services)
              ┌──────────────────────────┼─────────────────────────────────┐
              │                          │                                 │
              ▼                          ▼                                 ▼
   ┌──────────────────┐       ┌──────────────────┐            ┌──────────────────┐
   │  Order Service   │       │   User Service   │            │ Catalog Service  │
   │  [REST in]       │       │   [REST in]      │            │  [REST in]       │
   │  [gRPC out]      │       │   [gRPC out]     │            │  [Kafka out]     │
   │  [Kafka out]     │       └──────────────────┘            └──────────────────┘
   └──────────────────┘                │ gRPC                          │ Kafka
         │ Kafka                       │                               ▼
         │ (order-events)              ▼                     ┌──────────────────┐
         │               ┌──────────────────────┐            │ Search Service   │
         │               │  Inventory Service   │            │  (Elasticsearch) │
         │               │  [Kafka in]          │            └──────────────────┘
         │               │  [gRPC in/out]       │
         │               └──────────────────────┘
         │
         ├──────────────────────────────────────────────────────────────────────┐
         │                      Kafka: order-events                             │
         │                                                                      │
         ▼                         ▼                     ▼                      ▼
┌──────────────────┐   ┌──────────────────┐   ┌──────────────────┐  ┌─────────────────┐
│ Payment Service  │   │ Shipping Service │   │ Notification Svc │  │Analytics Service│
│ [Kafka in/out]   │   │  [Kafka in/out]  │   │  [Kafka in]      │  │  [Kafka in]     │
│ [gRPC out to     │   │  [REST out to    │   │  [WebSocket out] │  │  (ClickHouse)   │
│  Saga Orch.]     │   │   3rd-party]     │   │  [Email/SMS out] │  └─────────────────┘
└──────────────────┘   └──────────────────┘   └──────────────────┘
         │ Kafka (payment-events)
         ▼
┌──────────────────┐
│ Saga Orchestrator│
│ [Kafka in/out]   │
│ (Temporal)       │
└──────────────────┘

Communication protocols used:
  External → Gateway:   HTTPS/REST
  Gateway → Services:   REST + mTLS (Istio)
  Service → Service:    gRPC + mTLS (Istio) for sync calls
  Event broadcasting:   Kafka (async, durable, replayable)
  Real-time to browser: WebSocket over STOMP
  Transaction coord.:   Kafka + Saga Orchestrator (Temporal)
```

---

## 📊 Summary Comparison Table

| Pattern | Type | Coupling | Latency | Throughput | Ordering | Best For |
|---|---|---|---|---|---|---|
| **REST/HTTP** | Sync | High temporal | Low-Medium | Medium | N/A | External APIs, simple calls |
| **gRPC** | Sync | High temporal | Very Low | High | N/A | Internal high-perf calls |
| **GraphQL** | Sync | High temporal | Low-Medium | Medium | N/A | BFF, flexible queries |
| **WebSocket** | Sync (persistent) | Medium | Very Low | High | Yes | Real-time push |
| **Message Queue** | Async | Low | Medium | High | Per queue | Work distribution |
| **Pub/Sub** | Async | Very Low | Medium | High | No | Event broadcasting |
| **Kafka Streaming** | Async | Very Low | Low-Medium | Very High | Per partition | Event sourcing, analytics |
| **Request-Reply** | Async | Low | Medium | Medium | No | Non-blocking request-response |
| **Saga Choreography** | Async | Very Low | High | Medium | No | Distributed transactions |
| **Saga Orchestration** | Async/Sync | Medium | High | Medium | Yes | Complex workflows |
| **Outbox Pattern** | Async | Low | Very Low | High | Yes | Reliable event publishing |
| **Service Mesh** | Infrastructure | Very Low | Near Zero | Very High | N/A | All internal communication |

---

## 📚 Further Reading

- [Microservices.io — Communication Patterns](https://microservices.io/patterns/communication-style/)
- [Building Microservices — Sam Newman](https://www.oreilly.com/library/view/building-microservices-2nd/9781492034018/)
- [gRPC Official Documentation](https://grpc.io/docs/)
- [Apache Kafka Documentation](https://kafka.apache.org/documentation/)
- [RabbitMQ Tutorials](https://www.rabbitmq.com/tutorials)
- [Istio Documentation](https://istio.io/latest/docs/)
- [Resilience4j Documentation](https://resilience4j.readme.io/)
- [Spring Cloud Gateway](https://spring.io/projects/spring-cloud-gateway)
- [Enterprise Integration Patterns — Hohpe & Woolf](https://www.enterpriseintegrationpatterns.com/)

---

*Last updated: March 2026 | 19 Patterns · 8 Protocols · Full Spring Boot Examples*
