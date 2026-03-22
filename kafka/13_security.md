# 13 — Security: SASL, SSL, ACLs & Quotas

> Kafka's four security pillars: authentication, encryption, authorization, and rate limiting.

---

## Security Overview

Kafka security is built on four layers:

```
┌─────────────────────────────────────────────────────────────────┐
│  Layer 1: Authentication (SASL)                                 │
│  Who are you? Prove your identity.                              │
│  PLAIN / SCRAM-SHA-256 / SCRAM-SHA-512 / GSSAPI / OAUTHBEARER │
├─────────────────────────────────────────────────────────────────┤
│  Layer 2: Encryption (SSL/TLS)                                  │
│  Traffic encrypted between client ↔ broker ↔ broker            │
│  Prevents eavesdropping and man-in-the-middle attacks           │
├─────────────────────────────────────────────────────────────────┤
│  Layer 3: Authorization (ACLs)                                  │
│  What are you allowed to do?                                    │
│  READ / WRITE / CREATE / DELETE / ALTER / DESCRIBE / ALL       │
├─────────────────────────────────────────────────────────────────┤
│  Layer 4: Quotas                                                │
│  How much resource can you consume?                             │
│  producer_byte_rate / consumer_byte_rate / request_percentage  │
└─────────────────────────────────────────────────────────────────┘
```

Each layer is independent — you can use any combination.

---

## Layer 1: SASL Authentication

SASL (Simple Authentication and Security Layer) is a framework for plugging in authentication mechanisms.

### SASL_PLAINTEXT vs SASL_SSL

```
SASL_PLAINTEXT: authentication without encryption
  → Credentials verified, but traffic is readable
  → Only use in isolated internal networks

SASL_SSL: authentication WITH encryption
  → Production standard
```

### SASL/PLAIN

Simple username/password authentication. Credentials transmitted in plaintext — **always use with SSL**.

```properties
# Broker (server.properties)
listeners=SASL_SSL://0.0.0.0:9093
security.inter.broker.protocol=SASL_SSL
sasl.inter.broker.mechanism=PLAIN
sasl.enabled.mechanisms=PLAIN

# JAAS config (kafka_server_jaas.conf)
KafkaServer {
    org.apache.kafka.common.security.plain.PlainLoginModule required
    username="admin"
    password="admin-secret"
    user_admin="admin-secret"
    user_order-service="order-service-secret"
    user_payment-service="payment-service-secret";
};
```

```properties
# Client (producer/consumer)
security.protocol=SASL_SSL
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required \
    username="order-service" password="order-service-secret";
```

### SASL/SCRAM-SHA-256 and SHA-512

Challenge-response authentication. Credentials stored in ZooKeeper or KRaft metadata — no plaintext passwords in config files. **Recommended over PLAIN for most deployments**.

```bash
# Create SCRAM credentials (broker-side)
kafka-configs.sh --bootstrap-server broker:9092 \
  --alter --entity-type users --entity-name order-service \
  --add-config 'SCRAM-SHA-256=[password=secret123],SCRAM-SHA-512=[password=secret123]'
```

```properties
# Client
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-256
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required \
    username="order-service" password="secret123";
```

**How SCRAM works:**
```
1. Client → server: ClientFirst message (username + client nonce)
2. Server → client: ServerFirst message (server nonce + salt + iteration count)
3. Client computes: PBKDF2 hash using password + salt + iterations
4. Client → server: ClientFinal message (client proof)
5. Server verifies: client proof matches stored key hash
6. Server → client: ServerFinal message (server signature)
7. Client verifies: server signature (proves server knows the password too)

Password never transmitted — only cryptographic proofs.
```

### SASL/GSSAPI (Kerberos)

Enterprise authentication using Kerberos tickets. Used in organizations with Active Directory or MIT Kerberos.

```properties
# Broker
sasl.enabled.mechanisms=GSSAPI
sasl.kerberos.service.name=kafka

# Client
security.protocol=SASL_SSL
sasl.mechanism=GSSAPI
sasl.kerberos.service.name=kafka
sasl.jaas.config=com.sun.security.auth.module.Krb5LoginModule required \
    useKeyTab=true \
    storeKey=true \
    keyTab="/etc/security/keytabs/order-service.keytab" \
    principal="order-service@EXAMPLE.COM";
```

### SASL/OAUTHBEARER

JWT (JSON Web Token) based authentication. Integrates with OAuth 2.0 / OIDC providers (Keycloak, Auth0, Okta).

```properties
# Broker
sasl.enabled.mechanisms=OAUTHBEARER
sasl.oauthbearer.token.endpoint.url=https://auth.example.com/token

# Client
security.protocol=SASL_SSL
sasl.mechanism=OAUTHBEARER
sasl.oauthbearer.token.endpoint.url=https://auth.example.com/token
sasl.jaas.config=org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required \
    oauth.client.id="kafka-client" \
    oauth.client.secret="client-secret" \
    oauth.scope="kafka-access";
```

---

## Layer 2: SSL/TLS Encryption

### What SSL Protects

- **Confidentiality**: Traffic encrypted (AES-256 typically)
- **Integrity**: Message authentication (HMAC)
- **Server authentication**: Client verifies broker identity via certificate
- **Mutual authentication (mTLS)**: Broker also verifies client identity

### Certificate Setup

```bash
# Step 1: Create CA (Certificate Authority)
openssl req -new -x509 -days 365 -keyout ca-key.pem -out ca-cert.pem

# Step 2: Create broker keystore (one per broker)
keytool -keystore kafka.broker-0.keystore.jks -alias broker-0 \
  -keyalg RSA -keysize 2048 -sigalg SHA256withRSA \
  -validity 365 -genkey -dname "CN=broker-0, OU=Kafka, O=Company"

# Step 3: Sign broker certificate with CA
keytool -certreq -keystore kafka.broker-0.keystore.jks -alias broker-0 -file broker-0.csr
openssl x509 -req -CA ca-cert.pem -CAkey ca-key.pem -in broker-0.csr -out broker-0-signed.crt
keytool -importcert -keystore kafka.broker-0.keystore.jks -alias CA -file ca-cert.pem
keytool -importcert -keystore kafka.broker-0.keystore.jks -alias broker-0 -file broker-0-signed.crt

# Step 4: Create client truststore (trusts the CA)
keytool -importcert -keystore kafka.client.truststore.jks -alias CA -file ca-cert.pem
```

### Broker SSL Configuration

```properties
# server.properties
listeners=PLAINTEXT://0.0.0.0:9092,SSL://0.0.0.0:9093
advertised.listeners=PLAINTEXT://broker-0:9092,SSL://broker-0:9093

ssl.keystore.location=/etc/kafka/ssl/kafka.broker-0.keystore.jks
ssl.keystore.password=keystore-password
ssl.key.password=key-password
ssl.truststore.location=/etc/kafka/ssl/kafka.broker-0.truststore.jks
ssl.truststore.password=truststore-password

# Require client authentication (mTLS)
ssl.client.auth=required   # or "requested" (optional) or "none" (server-only TLS)

# Protocol and cipher suites
ssl.protocol=TLS
ssl.enabled.protocols=TLSv1.3,TLSv1.2
ssl.cipher.suites=TLS_AES_256_GCM_SHA384,TLS_CHACHA20_POLY1305_SHA256

# Inter-broker encryption
security.inter.broker.protocol=SSL
```

### Client SSL Configuration

```java
Properties props = new Properties();
props.put("security.protocol", "SSL");
props.put("ssl.truststore.location", "/etc/kafka/ssl/kafka.client.truststore.jks");
props.put("ssl.truststore.password", "truststore-password");

// For mTLS (client certificate required):
props.put("ssl.keystore.location", "/etc/kafka/ssl/kafka.client.keystore.jks");
props.put("ssl.keystore.password", "keystore-password");
props.put("ssl.key.password", "key-password");
```

### SSL Performance Impact

```
SSL/TLS DISABLES zero-copy (sendfile):
  Data must enter JVM heap for encryption/decryption
  Falls back to 4-copy path (vs 2-copy zero-copy)

Throughput impact:
  Without SSL: 1 GB/s consumer reads (zero-copy)
  With SSL:    ~700 MB/s (30% reduction)

CPU impact:
  AES-256-GCM encryption: ~1-2% CPU per 100 MB/s (with hardware AES acceleration)
  Modern CPUs (AES-NI): encryption cost is minimal
  Main cost: breaking zero-copy (memory bandwidth, not CPU)

Mitigation options:
  1. SSL termination at load balancer + plaintext internal traffic
  2. Dedicated NICs: one for client traffic (SSL), one for replication (plaintext)
  3. Accept the throughput reduction if security requirement is non-negotiable
```

---

## Layer 3: ACLs — Authorization

### ACL Model

```
ACL = {Principal, Resource, Operation, Allow/Deny}

Principal:    User:order-service (SASL user)
              User:* (any user)
              Group:kafka-admins (if using group-based auth)

Resource:     Topic:orders          (specific topic)
              Topic:*               (all topics)
              Group:order-service-* (consumer groups with prefix)
              Cluster:kafka-cluster (cluster-level operations)
              TransactionalId:order-processor-* (transaction IDs)

Operation:    READ, WRITE, CREATE, DELETE, ALTER, DESCRIBE, ALL
              CLUSTER_ACTION (for inter-broker and admin operations)

Allow/Deny:   ALLOW (grant) or DENY (explicit deny, highest priority)
```

### Default Behavior Without ACLs

```properties
# server.properties
allow.everyone.if.no.acl.found=true  # DEFAULT — ALL access allowed
```

```
If true (default): anyone can do anything if no ACL explicitly exists
  → Development-friendly, production-dangerous

If false: anyone is denied if no explicit ALLOW ACL exists
  → Production-safe, requires explicit grants for all principals
```

**Always set `allow.everyone.if.no.acl.found=false` in production.**

### Managing ACLs

```bash
# Grant order-service READ access to "orders" topic
kafka-acls.sh --bootstrap-server broker:9092 \
  --add \
  --allow-principal User:order-service \
  --operation READ \
  --topic orders

# Grant order-service READ access to its consumer group
kafka-acls.sh --bootstrap-server broker:9092 \
  --add \
  --allow-principal User:order-service \
  --operation READ \
  --group order-service-prod

# Grant payment-service WRITE access to "payments" topic
kafka-acls.sh --bootstrap-server broker:9092 \
  --add \
  --allow-principal User:payment-service \
  --operation WRITE \
  --topic payments

# Grant admin ALL access to ALL topics
kafka-acls.sh --bootstrap-server broker:9092 \
  --add \
  --allow-principal User:kafka-admin \
  --operation ALL \
  --topic '*'

# Grant ability to create topics (cluster-level)
kafka-acls.sh --bootstrap-server broker:9092 \
  --add \
  --allow-principal User:kafka-admin \
  --operation CREATE \
  --cluster

# List all ACLs
kafka-acls.sh --bootstrap-server broker:9092 --list

# Remove an ACL
kafka-acls.sh --bootstrap-server broker:9092 \
  --remove \
  --allow-principal User:order-service \
  --operation READ \
  --topic orders

# Grant transactional producer
kafka-acls.sh --bootstrap-server broker:9092 \
  --add \
  --allow-principal User:order-service \
  --operation WRITE \
  --topic orders \
  --operation DESCRIBE --topic orders

kafka-acls.sh --bootstrap-server broker:9092 \
  --add \
  --allow-principal User:order-service \
  --operation WRITE \
  --transactional-id 'order-processor-*'
```

### Programmatic ACL Management (AdminClient)

```java
AdminClient admin = AdminClient.create(props);

// Create ACL
AclBinding acl = new AclBinding(
    new ResourcePattern(ResourceType.TOPIC, "orders", PatternType.LITERAL),
    new AccessControlEntry("User:order-service", "*", AclOperation.READ, AclPermissionType.ALLOW)
);
admin.createAcls(List.of(acl)).all().get();

// List ACLs
Collection<AclBinding> acls = admin.describeAcls(AclBindingFilter.ANY).values().get();

// Delete ACL
AclBindingFilter filter = new AclBindingFilter(
    new ResourcePatternFilter(ResourceType.TOPIC, "orders", PatternType.ANY),
    new AccessControlEntryFilter("User:order-service", "*", AclOperation.READ, AclPermissionType.ALLOW)
);
admin.deleteAcls(List.of(filter)).all().get();
```

### ACL Storage

```
With ZooKeeper: ACLs stored at /kafka-acl/ (ZNode tree)
With KRaft:     ACLs stored in @metadata topic (AccessControlRecord entries)

Changes propagate to all brokers:
  ZK-based: brokers watch /kafka-acl/ → update local cache on change
  KRaft:    brokers subscribe to @metadata → update cache on new AccessControlRecord
```

---

## Layer 4: Quotas

Prevents one client from consuming all resources on a shared cluster.

### Quota Types

| Quota | Metric | What it controls |
|---|---|---|
| `producer_byte_rate` | bytes/second | Producer throughput per client |
| `consumer_byte_rate` | bytes/second | Consumer fetch throughput per client |
| `request_percentage` | % of threads | CPU time consumed by this client |

### Throttling Mechanism

```
When quota exceeded:
  Broker does NOT drop or reject the request.
  Broker DELAYS the response (adds artificial sleep time).
  Client sees: slow response, not errors.
  
  Clients may hit request.timeout.ms if throttle is very aggressive.
  
ThrottleTimeMs reported in response:
  ProduceResponse.throttleTimeMs = 500  ← "you're throttled, sleep 500ms"
  Producer delays next send accordingly
```

### Setting Quotas

```bash
# Per client-id
kafka-configs.sh --bootstrap-server broker:9092 \
  --entity-type clients --entity-name analytics-consumer \
  --alter --add-config consumer_byte_rate=10485760  # 10 MB/s

# Per user (SASL)
kafka-configs.sh --bootstrap-server broker:9092 \
  --entity-type users --entity-name order-service \
  --alter --add-config producer_byte_rate=52428800,consumer_byte_rate=52428800  # 50 MB/s each

# Per user AND client-id (most specific — takes precedence)
kafka-configs.sh --bootstrap-server broker:9092 \
  --entity-type users --entity-name order-service \
  --entity-type clients --entity-name order-service-producer \
  --alter --add-config producer_byte_rate=104857600  # 100 MB/s

# Default quota for ALL clients (catch-all)
kafka-configs.sh --bootstrap-server broker:9092 \
  --entity-type clients --entity-default \
  --alter --add-config producer_byte_rate=10485760  # 10 MB/s default for all

# CPU quota (25% of network threads)
kafka-configs.sh --bootstrap-server broker:9092 \
  --entity-type clients --entity-name heavy-consumer \
  --alter --add-config request_percentage=25

# Remove quota
kafka-configs.sh --bootstrap-server broker:9092 \
  --entity-type clients --entity-name analytics-consumer \
  --alter --delete-config consumer_byte_rate
```

### Quota Precedence

```
Most specific wins:
  1. User + client-id  (most specific)
  2. User (any client-id)
  3. client-id (any user)
  4. Default (all users, all client-ids)

Example: User:order-service, client-id:order-prod
  Check: user=order-service + client-id=order-prod → quota set? → use it
  Check: user=order-service (any client-id) → quota set? → use it
  Check: client-id=order-prod (any user) → quota set? → use it
  Use: default quota
```

---

## Multi-Listener Setup

Production clusters often expose multiple listeners for different security levels:

```properties
# server.properties
listeners=PLAINTEXT://0.0.0.0:9092,SASL_SSL://0.0.0.0:9093,CONTROLLER://0.0.0.0:9094

advertised.listeners=PLAINTEXT://broker-0.internal:9092,SASL_SSL://broker-0.example.com:9093

listener.security.protocol.map=PLAINTEXT:PLAINTEXT,SASL_SSL:SASL_SSL,CONTROLLER:SASL_SSL

# Internal brokers use SASL_SSL between themselves
security.inter.broker.protocol=SASL_SSL
sasl.inter.broker.mechanism=SCRAM-SHA-256

# Different SASL mechanisms per listener
listener.name.sasl_ssl.sasl.enabled.mechanisms=SCRAM-SHA-256,OAUTHBEARER
listener.name.plaintext.sasl.enabled.mechanisms=PLAIN
```

---

## ACL Requirements for Common Operations

| Operation | Required ACL |
|---|---|
| Producer writes to topic | `WRITE` on `TOPIC:my-topic` |
| Consumer reads from topic | `READ` on `TOPIC:my-topic` AND `READ` on `GROUP:my-group` |
| Consumer group coordinator | `DESCRIBE` on `TOPIC:my-topic` |
| Create new topic | `CREATE` on `CLUSTER:kafka-cluster` |
| Delete topic | `DELETE` on `TOPIC:my-topic` |
| Describe topic (metadata) | `DESCRIBE` on `TOPIC:my-topic` |
| Transactional producer | `WRITE` on `TOPIC` + `WRITE` on `TRANSACTIONALID` |
| Admin operations | `CLUSTER_ACTION` on `CLUSTER:kafka-cluster` |

---

## Summary

| Security Layer | Mechanism | Protects |
|---|---|---|
| Authentication | SASL (PLAIN/SCRAM/Kerberos/OAuth) | Identity verification |
| Encryption | SSL/TLS | Confidentiality + integrity |
| Authorization | ACLs | Access control per resource |
| Rate limiting | Quotas | Resource fairness |

**Recommended production setup:**
```properties
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-256
ssl.client.auth=required   # mTLS for inter-broker
allow.everyone.if.no.acl.found=false
```
