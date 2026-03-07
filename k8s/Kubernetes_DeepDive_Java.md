# ☸️ Kubernetes — Deep Dive for Java Developers

> Kubernetes 1.29+ | Spring Boot 3.x | Helm | Production Patterns

---

## 📌 Table of Contents

1. [What is Kubernetes & Why Java Developers Need It](#1-what-is-kubernetes--why-java-developers-need-it)
2. [Core Architecture](#2-core-architecture)
3. [Pods — The Basic Unit](#3-pods--the-basic-unit)
4. [Deployments & ReplicaSets](#4-deployments--replicasets)
5. [Services & Networking](#5-services--networking)
6. [ConfigMaps & Secrets](#6-configmaps--secrets)
7. [Persistent Storage](#7-persistent-storage)
8. [Health Probes — Liveness, Readiness & Startup](#8-health-probes--liveness-readiness--startup)
9. [Resource Management & JVM Tuning](#9-resource-management--jvm-tuning)
10. [Horizontal Pod Autoscaling](#10-horizontal-pod-autoscaling)
11. [Ingress & API Gateway](#11-ingress--api-gateway)
12. [Namespaces & RBAC](#12-namespaces--rbac)
13. [Helm — Package Manager](#13-helm--package-manager)
14. [CI/CD with Kubernetes](#14-cicd-with-kubernetes)
15. [Spring Boot on Kubernetes](#15-spring-boot-on-kubernetes)
16. [StatefulSets — Kafka, Databases](#16-statefulsets--kafka-databases)
17. [Jobs & CronJobs — Spring Batch](#17-jobs--cronjobs--spring-batch)
18. [Observability — Logs, Metrics, Tracing](#18-observability--logs-metrics-tracing)
19. [Security Best Practices](#19-security-best-practices)
20. [Troubleshooting Guide](#20-troubleshooting-guide)
21. [Interview Questions & Answers](#21-interview-questions--answers)
22. [Complete Reference Summary](#22-complete-reference-summary)

---

## 1. What is Kubernetes & Why Java Developers Need It

### The Problem Kubernetes Solves

```
WITHOUT KUBERNETES:
┌─────────────────────────────────────────────────────────────────┐
│  "Works on my machine" problems                                 │
│  Manual deployment: SSH → copy jar → java -jar (hope it works) │
│  Scale manually: provision VM, install Java, deploy             │
│  Zero-downtime deploy: prayer-based deployment strategy         │
│  Crash recovery: someone wakes up at 3AM to restart the app     │
│  Secret management: passwords in application.properties on disk  │
│  Service discovery: hardcoded IPs in config files               │
└─────────────────────────────────────────────────────────────────┘

WITH KUBERNETES:
┌─────────────────────────────────────────────────────────────────┐
│  Consistent environment: container = same everywhere            │
│  Declarative deploy: kubectl apply -f deployment.yaml           │
│  Auto-scaling: CPU > 70% → add more pods automatically          │
│  Self-healing: pod crashes → Kubernetes restarts it             │
│  Secret management: encrypted secrets injected at runtime       │
│  Service discovery: DNS-based (order-service:8080)              │
│  Rolling updates: zero-downtime deploys out of the box          │
└─────────────────────────────────────────────────────────────────┘
```

### Kubernetes vs Docker Compose

```
┌──────────────────────┬─────────────────────┬──────────────────────────┐
│  Feature             │  Docker Compose      │  Kubernetes              │
├──────────────────────┼─────────────────────┼──────────────────────────┤
│  Target              │  Local dev / single  │  Production clusters     │
│                      │  machine             │  (multi-node)            │
│  Scaling             │  Manual replicas     │  Auto HPA / VPA          │
│  Self-healing        │  No                  │  Yes (restarts, rescheduling)│
│  Rolling updates     │  No                  │  Yes (built-in)          │
│  Load balancing      │  No                  │  Yes (Service)           │
│  Secret management   │  .env files          │  Secrets (encrypted)     │
│  Config management   │  .env files          │  ConfigMaps              │
│  Service discovery   │  Docker DNS          │  CoreDNS (kube-dns)      │
│  Storage             │  Volumes (local)     │  PV/PVC (cloud-native)   │
│  Multi-cloud         │  No                  │  Yes (same YAML)         │
└──────────────────────┴─────────────────────┴──────────────────────────┘
```

---

## 2. Core Architecture

### Cluster Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        KUBERNETES CLUSTER                                   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                      CONTROL PLANE (Master)                         │   │
│  │                                                                     │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌───────────┐  ┌────────────┐  │   │
│  │  │  API Server  │  │  Scheduler  │  │Controller │  │    etcd    │  │   │
│  │  │             │  │             │  │  Manager  │  │ (key-value │  │   │
│  │  │ All requests│  │Assigns pods │  │ReplicaSet,│  │  store for │  │   │
│  │  │ go here     │  │to nodes     │  │Deployment │  │  all state)│  │   │
│  │  └─────────────┘  └─────────────┘  └───────────┘  └────────────┘  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────┐ │
│  │     WORKER NODE 1    │  │     WORKER NODE 2    │  │   WORKER NODE 3  │ │
│  │                      │  │                      │  │                  │ │
│  │  ┌────────┐          │  │  ┌────────┐          │  │  ┌────────┐     │ │
│  │  │ Pod A  │          │  │  │ Pod C  │          │  │  │ Pod E  │     │ │
│  │  │(app)   │          │  │  │(app)   │          │  │  │(app)   │     │ │
│  │  └────────┘          │  │  └────────┘          │  │  └────────┘     │ │
│  │  ┌────────┐          │  │  ┌────────┐          │  │  ┌────────┐     │ │
│  │  │ Pod B  │          │  │  │ Pod D  │          │  │  │ Pod F  │     │ │
│  │  │(db)    │          │  │  │(cache) │          │  │  │(app)   │     │ │
│  │  └────────┘          │  │  └────────┘          │  │  └────────┘     │ │
│  │                      │  │                      │  │                  │ │
│  │  kubelet  kube-proxy  │  │  kubelet  kube-proxy │  │  kubelet  kproxy │ │
│  └──────────────────────┘  └──────────────────────┘  └──────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘

Control Plane Components:
  API Server    → REST API gateway for all cluster operations
  Scheduler     → Decides which node a pod runs on (resources, affinity, taints)
  Controller Mgr→ Reconciliation loops (desired state vs actual state)
  etcd          → Distributed KV store, source of truth for all cluster state

Worker Node Components:
  kubelet       → Node agent, talks to API server, manages pods on this node
  kube-proxy    → Network rules for Service routing (iptables / IPVS)
  Container RT  → Docker / containerd — runs the actual containers
```

### How kubectl Works

```
Developer                 API Server               etcd          kubelet
    │                         │                      │               │
    │  kubectl apply -f        │                      │               │
    │  deployment.yaml         │                      │               │
    │─────────────────────────►│                      │               │
    │                         │── Save desired ──────►│               │
    │                         │   state                │               │
    │                         │                      │               │
    │                         │  Scheduler picks node │               │
    │                         │─────────────────────────────────────►│
    │                         │                      │  Pull image    │
    │                         │                      │  Start container
    │                         │                      │  Report status │
    │                         │◄─────────────────────────────────────│
    │◄─────────────────────────│                      │               │
    │   deployment/app created │                      │               │
```

---

## 3. Pods — The Basic Unit

### Pod Anatomy

```
┌──────────────────────────────────────────────────────────────────┐
│                            POD                                   │
│  (smallest deployable unit — one or more containers)             │
│                                                                  │
│  ┌────────────────────┐    ┌─────────────────────────────────┐  │
│  │   Init Container   │    │       Main Container            │  │
│  │                    │    │                                 │  │
│  │  Runs to completion│    │  order-service:1.2.3            │  │
│  │  before main starts│    │                                 │  │
│  │  (DB migrations,   │    │  ENV: DB_URL, JAVA_OPTS         │  │
│  │   wait-for-service)│    │  Ports: 8080, 8081 (actuator)   │  │
│  └────────────────────┘    │  Mounts: config-vol, secret-vol │  │
│                            └─────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │                   Sidecar Container                        │  │
│  │  Fluent Bit (log shipper) | Envoy (service mesh proxy)     │  │
│  └────────────────────────────────────────────────────────────┘  │
│                                                                  │
│  Shared:  Network namespace (same IP)                           │
│           Storage volumes                                        │
│           localhost communication between containers            │
└──────────────────────────────────────────────────────────────────┘
```

### Pod YAML — Full Spring Boot Example

```yaml
# pod.yaml (rarely used directly — use Deployment instead)
apiVersion: v1
kind: Pod
metadata:
  name: order-service-pod
  namespace: production
  labels:
    app: order-service
    version: "1.2.3"
    tier: backend
  annotations:
    prometheus.io/scrape: "true"
    prometheus.io/port: "8081"
    prometheus.io/path: "/actuator/prometheus"
spec:
  # ── Init container: wait for DB to be ready ────────────────────
  initContainers:
    - name: wait-for-db
      image: busybox:1.36
      command:
        - sh
        - -c
        - |
          until nc -z postgres-service 5432; do
            echo "Waiting for PostgreSQL..."; sleep 2;
          done
          echo "PostgreSQL is ready!"

  # ── Main application container ─────────────────────────────────
  containers:
    - name: order-service
      image: myregistry.io/order-service:1.2.3
      imagePullPolicy: IfNotPresent        # Always | Never | IfNotPresent

      ports:
        - name: http
          containerPort: 8080
          protocol: TCP
        - name: management
          containerPort: 8081
          protocol: TCP

      # ── Environment variables ───────────────────────────────────
      env:
        - name: SPRING_PROFILES_ACTIVE
          value: "kubernetes"
        - name: SERVER_PORT
          value: "8080"
        - name: JAVA_OPTS
          value: >-
            -XX:+UseContainerSupport
            -XX:MaxRAMPercentage=75.0
            -XX:+UseZGC
            -XX:+ZGenerational
            -XX:+ExitOnOutOfMemoryError
            -Xlog:gc*:file=/var/log/gc.log:time
        # From ConfigMap
        - name: DB_NAME
          valueFrom:
            configMapKeyRef:
              name: order-service-config
              key: database.name
        # From Secret
        - name: DB_PASSWORD
          valueFrom:
            secretKeyRef:
              name: order-service-secrets
              key: db-password
        # From Pod metadata (Downward API)
        - name: POD_NAME
          valueFrom:
            fieldRef:
              fieldPath: metadata.name
        - name: POD_NAMESPACE
          valueFrom:
            fieldRef:
              fieldPath: metadata.namespace
        - name: NODE_NAME
          valueFrom:
            fieldRef:
              fieldPath: spec.nodeName

      # ── Volume mounts ───────────────────────────────────────────
      volumeMounts:
        - name: config-volume
          mountPath: /config
          readOnly: true
        - name: secrets-volume
          mountPath: /secrets
          readOnly: true
        - name: tmp-dir
          mountPath: /tmp
        - name: log-dir
          mountPath: /var/log

      # ── Resources (CRITICAL for JVM apps!) ─────────────────────
      resources:
        requests:
          memory: "512Mi"          # Minimum guaranteed memory
          cpu: "250m"              # 0.25 CPU cores
        limits:
          memory: "1Gi"            # Max memory — OOM kill if exceeded
          cpu: "1000m"             # 1 CPU core cap

      # ── Probes (see Section 8 for details) ─────────────────────
      startupProbe:
        httpGet:
          path: /actuator/health/liveness
          port: 8081
        failureThreshold: 30
        periodSeconds: 10

      livenessProbe:
        httpGet:
          path: /actuator/health/liveness
          port: 8081
        initialDelaySeconds: 0
        periodSeconds: 10
        failureThreshold: 3

      readinessProbe:
        httpGet:
          path: /actuator/health/readiness
          port: 8081
        initialDelaySeconds: 0
        periodSeconds: 5
        failureThreshold: 3

      # ── Lifecycle hooks ─────────────────────────────────────────
      lifecycle:
        preStop:
          exec:
            # Give the app time to finish in-flight requests
            command: ["/bin/sh", "-c", "sleep 15"]

  # ── Sidecar: log shipper ─────────────────────────────────────────
  # (in Kubernetes 1.29+ use native sidecar containers)
  - name: fluent-bit
    image: fluent/fluent-bit:2.2
    volumeMounts:
      - name: log-dir
        mountPath: /var/log
    resources:
      requests: { memory: "50Mi", cpu: "50m" }
      limits:   { memory: "100Mi", cpu: "100m" }

  # ── Volumes ─────────────────────────────────────────────────────
  volumes:
    - name: config-volume
      configMap:
        name: order-service-config
    - name: secrets-volume
      secret:
        secretName: order-service-secrets
        defaultMode: 0400            # Read-only for owner only
    - name: tmp-dir
      emptyDir: {}                   # Ephemeral, deleted with pod
    - name: log-dir
      emptyDir: {}

  # ── Scheduling ──────────────────────────────────────────────────
  restartPolicy: Always              # Always | OnFailure | Never
  terminationGracePeriodSeconds: 60  # Time for graceful shutdown

  # ── Service account ─────────────────────────────────────────────
  serviceAccountName: order-service-sa
  automountServiceAccountToken: false # Don't auto-mount unless needed
```

---

## 4. Deployments & ReplicaSets

### Deployment YAML — Production-Grade

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order-service
  namespace: production
  labels:
    app: order-service
    version: "1.2.3"
spec:
  replicas: 3

  # ── Pod selector (must match template labels) ────────────────────
  selector:
    matchLabels:
      app: order-service

  # ── Update strategy ─────────────────────────────────────────────
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0             # Never have fewer than 'replicas' pods
      maxSurge: 1                   # Allow 1 extra pod during update
      # Result: Kubernetes starts 1 new pod, waits for it to be
      # Ready, then terminates 1 old pod — zero downtime!

  # ── How long to wait before marking update as failed ────────────
  progressDeadlineSeconds: 600

  # ── How many old ReplicaSets to keep (for rollback) ─────────────
  revisionHistoryLimit: 5

  template:
    metadata:
      labels:
        app: order-service
        version: "1.2.3"
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8081"
        prometheus.io/path: "/actuator/prometheus"
    spec:
      # ── Spread pods across nodes/zones ──────────────────────────
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: kubernetes.io/hostname
          whenUnsatisfiable: DoNotSchedule
          labelSelector:
            matchLabels:
              app: order-service
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
            matchLabels:
              app: order-service

      # ── Anti-affinity: don't co-locate replicas ──────────────────
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                labelSelector:
                  matchLabels:
                    app: order-service
                topologyKey: kubernetes.io/hostname

      # ── Node affinity: prefer nodes with SSD ────────────────────
      affinity:
        nodeAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 50
              preference:
                matchExpressions:
                  - key: node.kubernetes.io/instance-type
                    operator: In
                    values: ["m5.xlarge", "m5.2xlarge"]

      serviceAccountName: order-service-sa
      terminationGracePeriodSeconds: 60

      containers:
        - name: order-service
          image: myregistry.io/order-service:1.2.3
          ports:
            - containerPort: 8080
              name: http
            - containerPort: 8081
              name: management
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "kubernetes"
            - name: JAVA_OPTS
              value: >-
                -XX:+UseContainerSupport
                -XX:MaxRAMPercentage=75.0
                -XX:+UseZGC
                -XX:+ZGenerational
                -XX:+HeapDumpOnOutOfMemoryError
                -XX:HeapDumpPath=/tmp/heapdump.hprof
                -XX:+ExitOnOutOfMemoryError
          envFrom:
            - configMapRef:
                name: order-service-config
            - secretRef:
                name: order-service-secrets
          resources:
            requests:
              memory: "512Mi"
              cpu: "250m"
            limits:
              memory: "1Gi"
              cpu: "1000m"
          startupProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8081
            failureThreshold: 30
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8081
            periodSeconds: 10
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8081
            periodSeconds: 5
            failureThreshold: 3
          lifecycle:
            preStop:
              exec:
                command: ["sh", "-c", "sleep 15"]
          volumeMounts:
            - name: app-config
              mountPath: /config
              readOnly: true

      volumes:
        - name: app-config
          configMap:
            name: order-service-config
```

### Deployment Operations

```bash
# ── Deploy / Update ───────────────────────────────────────────────────────────
kubectl apply -f deployment.yaml

# Deploy a new image version (triggers rolling update)
kubectl set image deployment/order-service \
    order-service=myregistry.io/order-service:1.2.4

# ── Status ────────────────────────────────────────────────────────────────────
kubectl rollout status deployment/order-service     # Watch rollout progress
kubectl get deployment order-service -o wide        # Overview
kubectl describe deployment order-service           # Detailed events/conditions

# ── Rollback ──────────────────────────────────────────────────────────────────
kubectl rollout undo deployment/order-service             # Undo last update
kubectl rollout undo deployment/order-service --to-revision=3  # Specific revision
kubectl rollout history deployment/order-service          # See all revisions

# ── Scaling ───────────────────────────────────────────────────────────────────
kubectl scale deployment order-service --replicas=5
kubectl autoscale deployment order-service --min=3 --max=10 --cpu-percent=70

# ── Pause / Resume rolling update ────────────────────────────────────────────
kubectl rollout pause  deployment/order-service     # Pause mid-rollout
kubectl rollout resume deployment/order-service     # Resume

# ── Force pod restart (without changing image) ───────────────────────────────
kubectl rollout restart deployment/order-service

# ── Delete ────────────────────────────────────────────────────────────────────
kubectl delete deployment order-service
kubectl delete -f deployment.yaml
```

---

## 5. Services & Networking

### Service Types

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                         KUBERNETES SERVICE TYPES                             │
│                                                                              │
│  ClusterIP (default)                                                         │
│  ─────────────                                                               │
│  Virtual IP only accessible INSIDE the cluster                               │
│  Pod A ──► ClusterIP:80 ──► [Pod B-1, Pod B-2, Pod B-3]                     │
│  Use for: inter-service communication                                        │
│                                                                              │
│  NodePort                                                                    │
│  ─────────                                                                   │
│  Opens a port (30000-32767) on EVERY node                                   │
│  External ──► NodeIP:30080 ──► ClusterIP:80 ──► Pods                        │
│  Use for: dev/testing, not recommended for production                        │
│                                                                              │
│  LoadBalancer                                                                │
│  ────────────                                                                │
│  Provisions cloud load balancer (AWS ALB, GCP LB, Azure LB)                 │
│  External ──► CloudLB:80 ──► NodePort ──► ClusterIP ──► Pods                │
│  Use for: exposing a single service to internet                              │
│                                                                              │
│  ExternalName                                                                │
│  ────────────                                                                │
│  Maps service to external DNS name (CNAME)                                  │
│  my-db-service ──► rds.amazonaws.com                                        │
│  Use for: external services that look like cluster services                  │
│                                                                              │
│  Headless (ClusterIP: None)                                                  │
│  ─────────────────────────                                                   │
│  DNS returns pod IPs directly (no load balancing)                            │
│  Use for: StatefulSets, Kafka, Cassandra                                     │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Service YAML

```yaml
# ── ClusterIP (internal service) ─────────────────────────────────────────────
apiVersion: v1
kind: Service
metadata:
  name: order-service
  namespace: production
  labels:
    app: order-service
spec:
  type: ClusterIP
  selector:
    app: order-service       # Routes to pods with this label
  ports:
    - name: http
      port: 80               # Port the service listens on
      targetPort: 8080       # Port on the pod (or named port)
      protocol: TCP
    - name: management
      port: 8081
      targetPort: 8081

---
# ── Service DNS: order-service.production.svc.cluster.local:80 ───────────────
# Short form within same namespace: order-service:80
# Cross-namespace: order-service.production:80

---
# ── LoadBalancer (external access) ───────────────────────────────────────────
apiVersion: v1
kind: Service
metadata:
  name: order-service-lb
  namespace: production
  annotations:
    # AWS annotations
    service.beta.kubernetes.io/aws-load-balancer-type: "nlb"
    service.beta.kubernetes.io/aws-load-balancer-scheme: "internet-facing"
    service.beta.kubernetes.io/aws-load-balancer-cross-zone-load-balancing-enabled: "true"
spec:
  type: LoadBalancer
  selector:
    app: order-service
  ports:
    - port: 80
      targetPort: 8080
  externalTrafficPolicy: Local   # Route to local node pods only (preserve client IP)

---
# ── Headless Service (for StatefulSets) ──────────────────────────────────────
apiVersion: v1
kind: Service
metadata:
  name: kafka-headless
  namespace: production
spec:
  type: ClusterIP
  clusterIP: None              # Headless — DNS returns pod IPs
  selector:
    app: kafka
  ports:
    - port: 9092
      targetPort: 9092
# DNS: kafka-0.kafka-headless.production.svc.cluster.local
#      kafka-1.kafka-headless.production.svc.cluster.local
```

### Network Policies

```yaml
# ── Restrict: only allow traffic from specific pods ──────────────────────────
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: order-service-netpol
  namespace: production
spec:
  podSelector:
    matchLabels:
      app: order-service

  policyTypes:
    - Ingress
    - Egress

  ingress:
    # Allow from API gateway
    - from:
        - podSelector:
            matchLabels:
              app: api-gateway
      ports:
        - port: 8080

    # Allow from monitoring (Prometheus scrape)
    - from:
        - namespaceSelector:
            matchLabels:
              name: monitoring
          podSelector:
            matchLabels:
              app: prometheus
      ports:
        - port: 8081

  egress:
    # Allow to PostgreSQL
    - to:
        - podSelector:
            matchLabels:
              app: postgres
      ports:
        - port: 5432

    # Allow to Kafka
    - to:
        - podSelector:
            matchLabels:
              app: kafka
      ports:
        - port: 9092

    # Allow DNS
    - to: []
      ports:
        - port: 53
          protocol: UDP
        - port: 53
          protocol: TCP
```

---

## 6. ConfigMaps & Secrets

### ConfigMap — Application Configuration

```yaml
# ── ConfigMap: key-value pairs ───────────────────────────────────────────────
apiVersion: v1
kind: ConfigMap
metadata:
  name: order-service-config
  namespace: production
data:
  # Simple key-value
  database.name: "orders"
  database.host: "postgres-service"
  database.port: "5432"
  cache.ttl: "300"
  log.level: "INFO"

  # Multi-line file (mounted as a file in the pod)
  application.properties: |
    spring.datasource.url=jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}
    spring.datasource.hikari.maximum-pool-size=17
    spring.datasource.hikari.minimum-idle=5
    spring.jpa.open-in-view=false
    spring.jpa.properties.hibernate.jdbc.batch_size=50
    management.endpoints.web.exposure.include=health,info,prometheus,metrics
    management.endpoint.health.show-details=always

  logback-spring.xml: |
    <?xml version="1.0" encoding="UTF-8"?>
    <configuration>
      <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
      </appender>
      <root level="INFO">
        <appender-ref ref="STDOUT"/>
      </root>
    </configuration>

---
# ── Use ConfigMap as env variables ───────────────────────────────────────────
# In Deployment:
# envFrom:
#   - configMapRef:
#       name: order-service-config
# → Injects all keys as env vars: database.name → DATABASE_NAME

# Or individual key:
# env:
#   - name: DB_HOST
#     valueFrom:
#       configMapKeyRef:
#         name: order-service-config
#         key: database.host

# ── Use ConfigMap as volume (mounted files) ───────────────────────────────────
# volumeMounts:
#   - name: config-vol
#     mountPath: /config
# volumes:
#   - name: config-vol
#     configMap:
#       name: order-service-config
#       items:
#         - key: application.properties
#           path: application.properties
# Result: /config/application.properties exists in the pod
```

### Secrets

```yaml
# ── Secret: base64 encoded (not encrypted by default!) ───────────────────────
apiVersion: v1
kind: Secret
metadata:
  name: order-service-secrets
  namespace: production
  annotations:
    # Reloader annotation: restart pod when secret changes
    secret.reloader.stakater.com/reload: "order-service"
type: Opaque
data:
  # Values must be base64 encoded:
  # echo -n "mypassword" | base64
  db-password:      bXlwYXNzd29yZA==
  jwt-secret:       c2VjcmV0a2V5MTIz
  api-key:          YXBpa2V5dmFsdWU=

# ── Or use stringData (plain text — Kubernetes encodes automatically) ─────────
# stringData:
#   db-password: "mypassword"
#   jwt-secret: "secretkey123"

---
# ── TLS Secret ───────────────────────────────────────────────────────────────
apiVersion: v1
kind: Secret
metadata:
  name: tls-secret
  namespace: production
type: kubernetes.io/tls
data:
  tls.crt: <base64-encoded-cert>
  tls.key: <base64-encoded-key>

---
# ── Docker registry secret ───────────────────────────────────────────────────
# kubectl create secret docker-registry myregistry-secret \
#   --docker-server=myregistry.io \
#   --docker-username=user \
#   --docker-password=pass \
#   --docker-email=user@example.com

# In Pod spec:
# imagePullSecrets:
#   - name: myregistry-secret
```

### Sealed Secrets — Encrypting Secrets in Git

```bash
# Install kubeseal CLI
brew install kubeseal

# Seal a secret (encrypted with cluster's public key)
kubectl create secret generic my-secret \
    --from-literal=db-password=mypassword \
    --dry-run=client -o yaml | \
  kubeseal --format yaml > sealed-secret.yaml

# sealed-secret.yaml is safe to commit to Git!
# The SealedSecret controller in cluster decrypts it
# Only your cluster can decrypt it

# Apply sealed secret
kubectl apply -f sealed-secret.yaml
# → Controller creates actual Secret automatically
```

---

## 7. Persistent Storage

### Storage Concepts

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                      KUBERNETES STORAGE HIERARCHY                            │
│                                                                              │
│  StorageClass                                                                │
│  ─────────────                                                               │
│  Defines HOW storage is provisioned (provisioner, parameters)               │
│  aws-ebs-gp3, gcp-pd-ssd, azure-premium-ssd                                │
│                                                                              │
│  PersistentVolume (PV)                                                       │
│  ──────────────────────                                                      │
│  An actual piece of storage (100Gi on AWS EBS, NFS mount, etc.)             │
│  Cluster-level resource (not namespace-scoped)                               │
│  Can be static (admin creates) or dynamic (provisioner creates)              │
│                                                                              │
│  PersistentVolumeClaim (PVC)                                                 │
│  ──────────────────────────                                                  │
│  A request for storage ("I need 10Gi, ReadWriteOnce")                       │
│  Namespace-scoped — pods use PVCs, not PVs directly                         │
│  Bound to a matching PV (static) or triggers auto-provisioning               │
│                                                                              │
│  Pod Volume Mount                                                            │
│  ─────────────────                                                           │
│  Pod mounts PVC at a specific path                                           │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Storage YAML

```yaml
# ── StorageClass: dynamic EBS provisioner ────────────────────────────────────
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: fast-ssd
  annotations:
    storageclass.kubernetes.io/is-default-class: "true"
provisioner: ebs.csi.aws.com
parameters:
  type: gp3
  iops: "3000"
  throughput: "125"
  encrypted: "true"
reclaimPolicy: Retain        # Delete | Retain | Recycle
volumeBindingMode: WaitForFirstConsumer  # Don't provision until pod scheduled
allowVolumeExpansion: true

---
# ── PVC: request 10Gi of fast SSD ────────────────────────────────────────────
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: postgres-data-pvc
  namespace: production
spec:
  accessModes:
    - ReadWriteOnce           # RWO: one node | RWM: many nodes | ROX: many read-only
  storageClassName: fast-ssd
  resources:
    requests:
      storage: 10Gi

---
# ── Use PVC in a Pod ──────────────────────────────────────────────────────────
# In Deployment spec:
# volumeMounts:
#   - name: postgres-data
#     mountPath: /var/lib/postgresql/data
# volumes:
#   - name: postgres-data
#     persistentVolumeClaim:
#       claimName: postgres-data-pvc
```

---

## 8. Health Probes — Liveness, Readiness & Startup

### Probe Types Explained

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                         KUBERNETES HEALTH PROBES                             │
│                                                                              │
│  startupProbe                                                                │
│  ─────────────                                                               │
│  Runs UNTIL it succeeds (then hands off to liveness)                        │
│  While running: liveness and readiness probes are DISABLED                  │
│  Use for: slow-starting apps (JVM warmup, DB migrations)                    │
│  Failure: container RESTARTED                                                │
│                                                                              │
│  livenessProbe                                                               │
│  ──────────────                                                              │
│  Is the app alive? Checks continuously after startup                        │
│  Use for: detect deadlocks, infinite loops, unrecoverable errors            │
│  Failure: container RESTARTED                                                │
│  ⚠ Caution: too aggressive = restart loops under load                       │
│                                                                              │
│  readinessProbe                                                              │
│  ──────────────                                                              │
│  Is the app READY to serve traffic?                                          │
│  Use for: app ready but still loading caches, waiting for deps              │
│  Failure: pod REMOVED from Service endpoints (no traffic)                   │
│  No restart — just temporarily stops receiving traffic                      │
│                                                                              │
│  Timeline:                                                                   │
│  [Pod starts] ─► startupProbe ─► (pass) ─► livenessProbe running           │
│                                         └─► readinessProbe running          │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Spring Boot Actuator Probe Configuration

```yaml
# application.yml — Spring Boot health configuration
management:
  endpoint:
    health:
      probes:
        enabled: true          # Enables /actuator/health/liveness and /readiness
      show-details: always
      group:
        liveness:
          include:
            - livenessState    # Spring internal state (CORRECT, BROKEN)
        readiness:
          include:
            - readinessState   # Spring internal state (ACCEPTING_TRAFFIC, etc.)
            - db               # DB connectivity check
            - redis            # Redis connectivity check
            - kafka            # Kafka connectivity check
  endpoints:
    web:
      exposure:
        include: health, info, prometheus, metrics, loggers
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
    db:
      enabled: true
    redis:
      enabled: true
    kafka:
      enabled: true
      response-timeout: 2s
```

### Kubernetes Probe YAML — Tuned for Java

```yaml
containers:
  - name: order-service
    image: myregistry.io/order-service:1.2.3

    # ── Startup probe: handle JVM warmup (up to 5 minutes) ─────────────────
    startupProbe:
      httpGet:
        path: /actuator/health/liveness
        port: 8081
      # Max wait: failureThreshold × periodSeconds = 30 × 10s = 5 minutes
      failureThreshold: 30
      periodSeconds: 10
      timeoutSeconds: 5           # Probe times out after 5s

    # ── Liveness probe: detect stuck app ─────────────────────────────────
    livenessProbe:
      httpGet:
        path: /actuator/health/liveness
        port: 8081
      periodSeconds: 10
      failureThreshold: 3         # Restart after 3 failures (30s unresponsive)
      successThreshold: 1
      timeoutSeconds: 5

    # ── Readiness probe: only send traffic when truly ready ───────────────
    readinessProbe:
      httpGet:
        path: /actuator/health/readiness
        port: 8081
      periodSeconds: 5
      failureThreshold: 3         # Remove from LB after 15s
      successThreshold: 2         # Require 2 successes before adding back
      timeoutSeconds: 3

    # ── Graceful shutdown ─────────────────────────────────────────────────
    lifecycle:
      preStop:
        exec:
          # k8s sends SIGTERM then waits terminationGracePeriodSeconds
          # preStop runs BEFORE SIGTERM — gives load balancer time to
          # stop routing before the app shuts down
          command: ["sh", "-c", "sleep 15"]
```

```yaml
# application.yml — Spring Boot graceful shutdown
server:
  shutdown: graceful          # Complete in-flight requests before shutdown

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s   # Wait up to 30s for requests to complete
```

### Programmatic Liveness/Readiness in Java

```java
import org.springframework.boot.availability.*;

@Component
public class AppAvailabilityManager {

    private final ApplicationContext context;

    public AppAvailabilityManager(ApplicationContext context) {
        this.context = context;
    }

    // Mark app as broken (triggers liveness failure → pod restart)
    public void markBroken(String reason) {
        AvailabilityChangeEvent.publish(
            context,
            LivenessState.BROKEN,
            reason
        );
        log.error("App marked as BROKEN: {}", reason);
    }

    // Temporarily refuse traffic (readiness failure → removed from Service)
    public void refuseTraffic() {
        AvailabilityChangeEvent.publish(context,
            ReadinessState.REFUSING_TRAFFIC);
    }

    // Accept traffic again (readiness passes → added back to Service)
    public void acceptTraffic() {
        AvailabilityChangeEvent.publish(context,
            ReadinessState.ACCEPTING_TRAFFIC);
    }
}

// Usage: temporarily refuse traffic during cache warming
@EventListener(ApplicationReadyEvent.class)
public void onStartup() {
    availabilityManager.refuseTraffic();
    try {
        cacheService.warmUp(); // Takes 10-20s
        availabilityManager.acceptTraffic();
    } catch (Exception e) {
        availabilityManager.markBroken("Cache warmup failed: " + e.getMessage());
    }
}

static org.slf4j.Logger log =
    org.slf4j.LoggerFactory.getLogger(AppAvailabilityManager.class);
```

---

## 9. Resource Management & JVM Tuning

### Why Resources Matter for JVM

```
Container memory limit = 1Gi

WITHOUT UseContainerSupport (old JVM):
  JVM sees HOST machine memory (e.g., 64GB)
  Sets heap to 25% of 64GB = 16GB
  Container hits 1Gi limit → OOM KILL (pod crashes)

WITH UseContainerSupport (Java 10+):
  JVM sees container limit: 1Gi
  Sets heap to MaxRAMPercentage% of 1Gi = 750MB (at 75%)
  Leaves 250MB for: Metaspace + CodeCache + ThreadStacks + DirectMemory

Memory budget for 1Gi container:
  Heap:           750MB  (-XX:MaxRAMPercentage=75.0)
  Metaspace:       64MB  (-XX:MaxMetaspaceSize=128m)
  CodeCache:       48MB  (-XX:ReservedCodeCacheSize=128m)
  Thread stacks:   32MB  (~64 threads × 512KB)
  Direct memory:   32MB  (-XX:MaxDirectMemorySize=64m)
  OS overhead:     32MB
  Total:          ~960MB ✅ (under 1Gi limit)
```

### Resource YAML Best Practices

```yaml
# ── Resource sizing guide ─────────────────────────────────────────────────────
#
# Requests = what the scheduler uses to place the pod
# Limits   = hard cap (OOM kill for memory, throttle for CPU)
#
# JVM recommendation: set requests < limits (burst allowed)
# Memory: requests ~50-60% of limit (leave headroom for GC surge)
# CPU: requests = typical usage, limit = 2-4x requests

resources:

  # ── Small microservice (typical REST API) ─────────────────────
  requests:
    memory: "256Mi"
    cpu: "100m"
  limits:
    memory: "512Mi"
    cpu: "500m"
  # JAVA_OPTS: -XX:MaxRAMPercentage=75.0 → heap = ~384MB

  # ── Medium service (with DB connections, caches) ──────────────
  requests:
    memory: "512Mi"
    cpu: "250m"
  limits:
    memory: "1Gi"
    cpu: "1000m"
  # JAVA_OPTS: -XX:MaxRAMPercentage=75.0 → heap = ~768MB

  # ── Large service (batch processing, high concurrency) ────────
  requests:
    memory: "1Gi"
    cpu: "500m"
  limits:
    memory: "2Gi"
    cpu: "2000m"
  # JAVA_OPTS: -XX:MaxRAMPercentage=75.0 → heap = ~1.5GB

---
# ── LimitRange: defaults for a namespace ─────────────────────────────────────
apiVersion: v1
kind: LimitRange
metadata:
  name: default-limits
  namespace: production
spec:
  limits:
    - type: Container
      default:          # Applied when no limits specified
        cpu: "500m"
        memory: "512Mi"
      defaultRequest:   # Applied when no requests specified
        cpu: "100m"
        memory: "256Mi"
      max:              # Hard ceiling per container
        cpu: "4000m"
        memory: "4Gi"

---
# ── ResourceQuota: namespace-level caps ─────────────────────────────────────
apiVersion: v1
kind: ResourceQuota
metadata:
  name: production-quota
  namespace: production
spec:
  hard:
    requests.cpu: "20"           # Total CPU requests in namespace
    requests.memory: "40Gi"      # Total memory requests
    limits.cpu: "40"
    limits.memory: "80Gi"
    pods: "100"                  # Max pods
    services: "50"
    persistentvolumeclaims: "20"
```

### JVM Flags for Kubernetes

```bash
# ── Standard production JVM flags for containers ─────────────────────────────
JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:InitialRAMPercentage=50.0 \
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/tmp/heapdump.hprof \
  -XX:+ExitOnOutOfMemoryError \
  -XX:MaxMetaspaceSize=128m \
  -Djava.security.egd=file:/dev/./urandom \
  -Dspring.config.location=/config/ \
  -Xlog:gc*:file=/var/log/gc.log:time:filecount=3,filesize=5m"

# ── For GraalVM Native (Spring Boot 3.x) ─────────────────────────────────────
# Startup: ~50ms, Memory: ~80MB RSS
# Build: ./mvnw -Pnative native:compile
# Dockerfile: FROM ghcr.io/graalvm/native-image:21
```

---

## 10. Horizontal Pod Autoscaling

### HPA Configuration

```yaml
# ── HPA v2: CPU + Memory + Custom metrics ────────────────────────────────────
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: order-service-hpa
  namespace: production
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: order-service

  minReplicas: 3
  maxReplicas: 20

  metrics:
    # ── CPU-based scaling ────────────────────────────────────────
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70     # Scale up when avg CPU > 70%

    # ── Memory-based scaling ─────────────────────────────────────
    - type: Resource
      resource:
        name: memory
        target:
          type: AverageValue
          averageValue: "768Mi"      # Scale up when avg memory > 768Mi

    # ── Custom metric: requests per second (from Prometheus) ─────
    - type: Pods
      pods:
        metric:
          name: http_requests_per_second
        target:
          type: AverageValue
          averageValue: "100"        # 100 req/s per pod

    # ── External metric: SQS queue depth ─────────────────────────
    - type: External
      external:
        metric:
          name: sqs_approximate_number_of_messages_visible
          selector:
            matchLabels:
              queue: order-processing-queue
        target:
          type: Value
          value: "500"               # Scale when queue > 500 messages

  behavior:
    scaleUp:
      stabilizationWindowSeconds: 30    # Wait 30s before scaling up again
      policies:
        - type: Pods
          value: 2                       # Add max 2 pods at a time
          periodSeconds: 60
        - type: Percent
          value: 50                      # Or 50% of current count
          periodSeconds: 60
      selectPolicy: Max                  # Use whichever policy adds more

    scaleDown:
      stabilizationWindowSeconds: 300   # Wait 5 min before scaling down
      policies:
        - type: Pods
          value: 1                       # Remove 1 pod at a time
          periodSeconds: 120             # Every 2 minutes
      selectPolicy: Min

---
# ── Vertical Pod Autoscaler (VPA) — right-size requests/limits ───────────────
apiVersion: autoscaling.k8s.io/v1
kind: VerticalPodAutoscaler
metadata:
  name: order-service-vpa
  namespace: production
spec:
  targetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: order-service
  updatePolicy:
    updateMode: "Off"    # Off: recommendations only | Auto: updates live
  resourcePolicy:
    containerPolicies:
      - containerName: order-service
        minAllowed:
          cpu: "100m"
          memory: "256Mi"
        maxAllowed:
          cpu: "2"
          memory: "4Gi"
        controlledResources: ["cpu", "memory"]
```

---

## 11. Ingress & API Gateway

### Ingress YAML — NGINX

```yaml
# ── Install NGINX Ingress Controller first ────────────────────────────────────
# helm install ingress-nginx ingress-nginx/ingress-nginx
# OR: kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/cloud/deploy.yaml

apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: api-ingress
  namespace: production
  annotations:
    kubernetes.io/ingress.class: "nginx"

    # ── Rate limiting ─────────────────────────────────────────────
    nginx.ingress.kubernetes.io/limit-rps: "100"
    nginx.ingress.kubernetes.io/limit-connections: "50"

    # ── Timeouts ─────────────────────────────────────────────────
    nginx.ingress.kubernetes.io/proxy-connect-timeout: "10"
    nginx.ingress.kubernetes.io/proxy-send-timeout: "60"
    nginx.ingress.kubernetes.io/proxy-read-timeout: "60"

    # ── CORS ─────────────────────────────────────────────────────
    nginx.ingress.kubernetes.io/enable-cors: "true"
    nginx.ingress.kubernetes.io/cors-allow-origin: "https://app.example.com"
    nginx.ingress.kubernetes.io/cors-allow-methods: "GET, POST, PUT, DELETE, OPTIONS"
    nginx.ingress.kubernetes.io/cors-allow-headers: "Authorization, Content-Type"

    # ── SSL redirect ─────────────────────────────────────────────
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
    nginx.ingress.kubernetes.io/force-ssl-redirect: "true"

    # ── Request body size ─────────────────────────────────────────
    nginx.ingress.kubernetes.io/proxy-body-size: "10m"

    # ── TLS cert-manager ─────────────────────────────────────────
    cert-manager.io/cluster-issuer: "letsencrypt-prod"

spec:
  tls:
    - hosts:
        - api.example.com
      secretName: api-tls-cert

  rules:
    - host: api.example.com
      http:
        paths:
          # Order service
          - path: /api/orders
            pathType: Prefix
            backend:
              service:
                name: order-service
                port:
                  number: 80

          # User service
          - path: /api/users
            pathType: Prefix
            backend:
              service:
                name: user-service
                port:
                  number: 80

          # Default backend
          - path: /
            pathType: Prefix
            backend:
              service:
                name: frontend
                port:
                  number: 80
```

---

## 12. Namespaces & RBAC

### Namespace Strategy

```yaml
# ── Namespaces by environment ─────────────────────────────────────────────────
# production, staging, development, monitoring, logging, cert-manager

apiVersion: v1
kind: Namespace
metadata:
  name: production
  labels:
    environment: production
    team: platform

---
# ── ServiceAccount ────────────────────────────────────────────────────────────
apiVersion: v1
kind: ServiceAccount
metadata:
  name: order-service-sa
  namespace: production
  annotations:
    # AWS: allow pod to assume an IAM role (IRSA)
    eks.amazonaws.com/role-arn: "arn:aws:iam::123456789:role/order-service-role"
automountServiceAccountToken: false

---
# ── Role: permissions within a namespace ─────────────────────────────────────
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: order-service-role
  namespace: production
rules:
  # Read ConfigMaps and Secrets
  - apiGroups: [""]
    resources: ["configmaps", "secrets"]
    verbs: ["get", "list", "watch"]
  # Read own pod info
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["get"]

---
# ── RoleBinding: bind role to service account ────────────────────────────────
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: order-service-binding
  namespace: production
subjects:
  - kind: ServiceAccount
    name: order-service-sa
    namespace: production
roleRef:
  kind: Role
  apiVersion: rbac.authorization.k8s.io/v1
  name: order-service-role

---
# ── ClusterRole: cluster-wide permissions ────────────────────────────────────
# (e.g., for monitoring agents that need to read all namespaces)
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: prometheus-reader
rules:
  - apiGroups: [""]
    resources: ["pods", "services", "endpoints"]
    verbs: ["get", "list", "watch"]
  - apiGroups: ["apps"]
    resources: ["deployments", "replicasets"]
    verbs: ["get", "list", "watch"]
  - nonResourceURLs: ["/metrics"]
    verbs: ["get"]
```

---

## 13. Helm — Package Manager

### Helm Chart Structure

```
my-java-app/
├── Chart.yaml          # Chart metadata (name, version, description)
├── values.yaml         # Default values (overridable per environment)
├── values-staging.yaml # Staging overrides
├── values-prod.yaml    # Production overrides
└── templates/
    ├── deployment.yaml
    ├── service.yaml
    ├── ingress.yaml
    ├── configmap.yaml
    ├── hpa.yaml
    ├── serviceaccount.yaml
    ├── networkpolicy.yaml
    ├── _helpers.tpl    # Reusable template functions
    └── NOTES.txt       # Post-install instructions
```

### Chart.yaml

```yaml
apiVersion: v2
name: order-service
description: Order management microservice
type: application
version: 0.3.2           # Chart version
appVersion: "1.2.3"      # Application version (image tag)
dependencies:
  - name: postgresql
    version: "12.x.x"
    repository: https://charts.bitnami.com/bitnami
    condition: postgresql.enabled
```

### values.yaml

```yaml
# Default values — override per environment
replicaCount: 2

image:
  repository: myregistry.io/order-service
  tag: ""            # Defaults to Chart.appVersion
  pullPolicy: IfNotPresent

service:
  type: ClusterIP
  port: 80
  targetPort: 8080
  managementPort: 8081

ingress:
  enabled: false
  className: nginx
  host: order-service.example.com
  tlsEnabled: true

resources:
  requests:
    memory: "512Mi"
    cpu: "250m"
  limits:
    memory: "1Gi"
    cpu: "1000m"

autoscaling:
  enabled: true
  minReplicas: 2
  maxReplicas: 10
  targetCPUUtilizationPercentage: 70

jvm:
  maxRamPercentage: 75.0
  gcType: ZGC
  extraOpts: ""

spring:
  profiles: kubernetes
  datasource:
    host: postgres-service
    port: "5432"
    name: orders
    pool:
      maxSize: 17
      minIdle: 5

config:
  logLevel: INFO
  cacheTtl: "300"

secrets:
  dbPassword: ""       # Set via --set secrets.dbPassword=xxx or sealed secrets
  jwtSecret: ""

postgresql:
  enabled: false       # Use external DB in production

podAnnotations:
  prometheus.io/scrape: "true"
  prometheus.io/port: "8081"
  prometheus.io/path: "/actuator/prometheus"
```

### Deployment Template

```yaml
# templates/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "order-service.fullname" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "order-service.labels" . | nindent 4 }}
spec:
  {{- if not .Values.autoscaling.enabled }}
  replicas: {{ .Values.replicaCount }}
  {{- end }}
  selector:
    matchLabels:
      {{- include "order-service.selectorLabels" . | nindent 6 }}
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
  template:
    metadata:
      labels:
        {{- include "order-service.selectorLabels" . | nindent 8 }}
      annotations:
        {{- toYaml .Values.podAnnotations | nindent 8 }}
        checksum/config: {{ include (print $.Template.BasePath "/configmap.yaml") . | sha256sum }}
    spec:
      serviceAccountName: {{ include "order-service.serviceAccountName" . }}
      containers:
        - name: {{ .Chart.Name }}
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag | default .Chart.AppVersion }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: 8080
            - name: management
              containerPort: {{ .Values.service.managementPort }}
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: {{ .Values.spring.profiles }}
            - name: JAVA_OPTS
              value: >-
                -XX:+UseContainerSupport
                -XX:MaxRAMPercentage={{ .Values.jvm.maxRamPercentage }}
                -XX:+Use{{ .Values.jvm.gcType }}
                {{- if eq .Values.jvm.gcType "ZGC" }}
                -XX:+ZGenerational
                {{- end }}
                -XX:+ExitOnOutOfMemoryError
                {{ .Values.jvm.extraOpts }}
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ include "order-service.fullname" . }}-secrets
                  key: db-password
          envFrom:
            - configMapRef:
                name: {{ include "order-service.fullname" . }}-config
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
          startupProbe:
            httpGet:
              path: /actuator/health/liveness
              port: {{ .Values.service.managementPort }}
            failureThreshold: 30
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: {{ .Values.service.managementPort }}
            periodSeconds: 10
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: {{ .Values.service.managementPort }}
            periodSeconds: 5
            failureThreshold: 3
          lifecycle:
            preStop:
              exec:
                command: ["sh", "-c", "sleep 15"]
```

### Helm Commands

```bash
# ── Install ───────────────────────────────────────────────────────────────────
helm install order-service ./my-java-app \
  -n production --create-namespace \
  -f values-prod.yaml \
  --set image.tag=1.2.3 \
  --set secrets.dbPassword=mypassword

# ── Upgrade (rolling update) ──────────────────────────────────────────────────
helm upgrade order-service ./my-java-app \
  -n production \
  -f values-prod.yaml \
  --set image.tag=1.2.4

# ── Upgrade with install if not exists ───────────────────────────────────────
helm upgrade --install order-service ./my-java-app \
  -n production --create-namespace \
  -f values-prod.yaml

# ── Preview rendered YAML (dry-run) ──────────────────────────────────────────
helm template order-service ./my-java-app -f values-prod.yaml | less
helm install --dry-run --debug order-service ./my-java-app -f values-prod.yaml

# ── Rollback ──────────────────────────────────────────────────────────────────
helm rollback order-service 1       # Roll back to revision 1
helm history order-service          # See all revisions

# ── Status & values ──────────────────────────────────────────────────────────
helm list -n production
helm status order-service -n production
helm get values order-service -n production

# ── Lint & test ───────────────────────────────────────────────────────────────
helm lint ./my-java-app
helm test order-service -n production

# ── Uninstall ─────────────────────────────────────────────────────────────────
helm uninstall order-service -n production
```

---

## 14. CI/CD with Kubernetes

### Dockerfile — Multi-Stage for Java

```dockerfile
# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# Cache Maven dependencies (layer caching optimization)
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Build application
COPY src ./src
RUN mvn package -DskipTests -q

# Extract layered JAR (improves Docker layer reuse)
RUN java -Djarmode=layertools \
    -jar target/order-service-*.jar extract

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

# Security: run as non-root
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

WORKDIR /app

# Copy layered JAR contents (rarely changing layers first)
COPY --from=builder /build/dependencies/          ./
COPY --from=builder /build/spring-boot-loader/    ./
COPY --from=builder /build/snapshot-dependencies/ ./
COPY --from=builder /build/application/           ./

EXPOSE 8080 8081

ENV JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", \
    "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
```

### GitHub Actions — CI/CD Pipeline

```yaml
# .github/workflows/deploy.yml
name: Build and Deploy to Kubernetes

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

env:
  REGISTRY: ghcr.io
  IMAGE_NAME: ${{ github.repository }}/order-service

jobs:
  # ── Build and Test ──────────────────────────────────────────────────────────
  build:
    runs-on: ubuntu-latest
    outputs:
      image-tag: ${{ steps.meta.outputs.tags }}
      image-digest: ${{ steps.push.outputs.digest }}

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven

      - name: Run Tests
        run: mvn test -q

      - name: Build Application
        run: mvn package -DskipTests -q

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Log in to Container Registry
        uses: docker/login-action@v3
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Extract Docker metadata
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}
          tags: |
            type=sha,prefix=sha-
            type=semver,pattern={{version}}
            type=raw,value=latest,enable=${{ github.ref == 'refs/heads/main' }}

      - name: Build and Push Docker Image
        id: push
        uses: docker/build-push-action@v5
        with:
          context: .
          push: ${{ github.event_name != 'pull_request' }}
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
          cache-from: type=gha
          cache-to: type=gha,mode=max

  # ── Deploy to Staging ───────────────────────────────────────────────────────
  deploy-staging:
    needs: build
    runs-on: ubuntu-latest
    environment: staging
    if: github.ref == 'refs/heads/main'

    steps:
      - uses: actions/checkout@v4

      - name: Configure kubectl
        uses: azure/k8s-set-context@v3
        with:
          method: kubeconfig
          kubeconfig: ${{ secrets.KUBE_CONFIG_STAGING }}

      - name: Deploy to Staging
        run: |
          helm upgrade --install order-service ./helm/order-service \
            -n staging --create-namespace \
            -f helm/order-service/values-staging.yaml \
            --set image.tag=sha-${{ github.sha }} \
            --wait --timeout 5m

      - name: Run Smoke Tests
        run: |
          kubectl wait --for=condition=available deployment/order-service \
            -n staging --timeout=300s
          curl -f https://staging-api.example.com/actuator/health

  # ── Deploy to Production ────────────────────────────────────────────────────
  deploy-production:
    needs: deploy-staging
    runs-on: ubuntu-latest
    environment: production   # Requires manual approval in GitHub
    if: github.ref == 'refs/heads/main'

    steps:
      - uses: actions/checkout@v4

      - name: Configure kubectl
        uses: azure/k8s-set-context@v3
        with:
          method: kubeconfig
          kubeconfig: ${{ secrets.KUBE_CONFIG_PROD }}

      - name: Deploy to Production
        run: |
          helm upgrade --install order-service ./helm/order-service \
            -n production --create-namespace \
            -f helm/order-service/values-prod.yaml \
            --set image.tag=sha-${{ github.sha }} \
            --wait --timeout 10m

      - name: Verify Deployment
        run: |
          kubectl rollout status deployment/order-service -n production
          kubectl get pods -n production -l app=order-service
```

---

## 15. Spring Boot on Kubernetes

### Spring Boot Kubernetes Integration

```yaml
# application-kubernetes.yml
spring:
  config:
    import:
      - "kubernetes:"         # Load ConfigMaps and Secrets as properties
  cloud:
    kubernetes:
      config:
        enabled: true
        name: order-service   # ConfigMap name to load
        namespace: production
        sources:
          - name: order-service-config
          - name: common-config
      secrets:
        enabled: true
        name: order-service-secrets
        namespace: production
      reload:
        enabled: true         # Hot-reload when ConfigMap/Secret changes
        strategy: RESTART_CONTEXT
        period: 30000         # Check every 30 seconds

# pom.xml — Spring Cloud Kubernetes
# <dependency>
#   <groupId>org.springframework.cloud</groupId>
#   <artifactId>spring-cloud-starter-kubernetes-fabric8-all</artifactId>
# </dependency>
```

### Service Discovery in Kubernetes

```java
import org.springframework.cloud.client.*;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class OrderServiceClient {

    // ── Option 1: Direct DNS (simplest, most reliable) ────────────────────────
    // Kubernetes CoreDNS automatically resolves service names
    // Format: <service-name>.<namespace>.svc.cluster.local

    private final WebClient webClient = WebClient.builder()
        .baseUrl("http://user-service.production.svc.cluster.local:80")
        // Or within same namespace: http://user-service
        .build();

    public UserDto getUser(String userId) {
        return webClient.get()
            .uri("/api/users/{id}", userId)
            .retrieve()
            .bodyToMono(UserDto.class)
            .block();
    }

    // ── Option 2: Spring Cloud LoadBalancer ──────────────────────────────────
    // @LoadBalanced RestTemplate resolves service names via discovery
    @Autowired
    @LoadBalanced
    RestTemplate restTemplate;

    public UserDto getUserViaLB(String userId) {
        // "user-service" resolved via Kubernetes discovery
        return restTemplate.getForObject(
            "http://user-service/api/users/{id}",
            UserDto.class, userId);
    }

    // ── Option 3: DiscoveryClient — programmatic service lookup ──────────────
    @Autowired
    DiscoveryClient discoveryClient;

    public void listServices() {
        discoveryClient.getServices().forEach(service -> {
            List<ServiceInstance> instances =
                discoveryClient.getInstances(service);
            instances.forEach(inst ->
                System.out.printf("Service: %s  Host: %s  Port: %d%n",
                    service, inst.getHost(), inst.getPort()));
        });
    }

    record UserDto(String id, String name, String email) {}
}
```

### Graceful Shutdown — Complete Setup

```java
// ── Spring Boot graceful shutdown ─────────────────────────────────────────────
@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}

// application.yml:
// server.shutdown: graceful
// spring.lifecycle.timeout-per-shutdown-phase: 30s

// ── Kubernetes shutdown sequence ──────────────────────────────────────────────
// 1. Pod gets SIGTERM signal (terminationGracePeriodSeconds countdown starts)
// 2. preStop hook runs: sleep 15  ← give LB time to stop routing
// 3. Spring receives SIGTERM: sets readiness to REFUSING_TRAFFIC
// 4. Spring waits for in-flight requests to complete (up to 30s)
// 5. Application shuts down gracefully
// 6. If not done in terminationGracePeriodSeconds: SIGKILL
//
// Total time budget:
//   preStop: 15s
//   In-flight drain: 30s
//   Total grace period: 60s (terminationGracePeriodSeconds)

@Component
public class GracefulShutdownMetrics {

    private final MeterRegistry meterRegistry;

    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        log.info("Application shutting down — recording metrics");
        meterRegistry.counter("app.shutdown.count").increment();
    }

    static org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(GracefulShutdownMetrics.class);
}
```

---

## 16. StatefulSets — Kafka, Databases

### StatefulSet vs Deployment

```
Deployment:                          StatefulSet:
  Pods are interchangeable             Pods have stable identity
  No stable hostname                   Pod-0, Pod-1, Pod-2 (ordered names)
  Shared PVC or no PVC                 Each pod gets its own PVC
  Random scheduling order              Ordered start/stop (0→1→2 up, 2→1→0 down)
  Good for: stateless apps             Good for: Kafka, ZooKeeper, Cassandra,
                                                PostgreSQL, Redis Sentinel
```

### StatefulSet YAML — Kafka

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: kafka
  namespace: production
spec:
  serviceName: kafka-headless   # Headless service for stable DNS
  replicas: 3
  selector:
    matchLabels:
      app: kafka
  updateStrategy:
    type: RollingUpdate

  template:
    metadata:
      labels:
        app: kafka
    spec:
      containers:
        - name: kafka
          image: confluentinc/cp-kafka:7.5.0
          ports:
            - containerPort: 9092
              name: kafka
          env:
            - name: KAFKA_BROKER_ID
              # Uses ordinal index of pod (kafka-0 → 0, kafka-1 → 1)
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name
            - name: KAFKA_ZOOKEEPER_CONNECT
              value: "zookeeper-headless:2181"
            - name: KAFKA_LISTENERS
              value: "PLAINTEXT://0.0.0.0:9092"
            - name: KAFKA_ADVERTISED_LISTENERS
              value: "PLAINTEXT://$(POD_NAME).kafka-headless.production.svc.cluster.local:9092"
          volumeMounts:
            - name: kafka-data
              mountPath: /var/lib/kafka/data
          resources:
            requests: { memory: "2Gi", cpu: "500m" }
            limits:   { memory: "4Gi", cpu: "2000m" }

  # ── Per-pod PVC (not shared!) ─────────────────────────────────────────────
  volumeClaimTemplates:
    - metadata:
        name: kafka-data
      spec:
        accessModes: ["ReadWriteOnce"]
        storageClassName: fast-ssd
        resources:
          requests:
            storage: 100Gi
```

---

## 17. Jobs & CronJobs — Spring Batch

### Kubernetes Job for Spring Batch

```yaml
# ── One-time Job: run Spring Batch job once ───────────────────────────────────
apiVersion: batch/v1
kind: Job
metadata:
  name: monthly-payroll-job-2024-01
  namespace: production
  labels:
    app: payroll-batch
    month: "2024-01"
spec:
  completions: 1             # Number of successful completions needed
  parallelism: 1             # Concurrent pods
  backoffLimit: 3            # Retry up to 3 times on failure
  activeDeadlineSeconds: 3600  # Fail if not done in 1 hour
  ttlSecondsAfterFinished: 86400  # Auto-delete job 24h after completion

  template:
    spec:
      restartPolicy: OnFailure   # Never | OnFailure
      containers:
        - name: payroll-batch
          image: myregistry.io/payroll-batch:1.0.0
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "kubernetes,batch"
            - name: PAY_PERIOD
              value: "2024-01"
            - name: JAVA_OPTS
              value: >-
                -XX:+UseContainerSupport
                -XX:MaxRAMPercentage=75.0
                -XX:+UseZGC
                -XX:+ExitOnOutOfMemoryError
            - name: SPRING_BATCH_JOB_ENABLED
              value: "true"
            - name: SPRING_BATCH_JOB_NAME
              value: "monthlyPayrollJob"
          resources:
            requests: { memory: "1Gi", cpu: "500m" }
            limits:   { memory: "2Gi", cpu: "2000m" }
          envFrom:
            - configMapRef:
                name: payroll-config
            - secretRef:
                name: payroll-secrets

---
# ── CronJob: run Spring Batch on schedule ────────────────────────────────────
apiVersion: batch/v1
kind: CronJob
metadata:
  name: nightly-data-import
  namespace: production
spec:
  schedule: "0 2 * * *"          # Daily at 2:00 AM UTC
  timeZone: "America/New_York"    # Optional timezone (Kubernetes 1.27+)
  concurrencyPolicy: Forbid       # Allow | Forbid | Replace
  successfulJobsHistoryLimit: 3
  failedJobsHistoryLimit: 5
  startingDeadlineSeconds: 300    # Allow 5min late start

  jobTemplate:
    spec:
      backoffLimit: 2
      activeDeadlineSeconds: 7200  # 2 hours max

      template:
        spec:
          restartPolicy: OnFailure
          containers:
            - name: data-import
              image: myregistry.io/data-import-batch:1.0.0
              env:
                - name: SPRING_PROFILES_ACTIVE
                  value: "kubernetes,batch"
                - name: JAVA_OPTS
                  value: "-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
              resources:
                requests: { memory: "512Mi", cpu: "250m" }
                limits:   { memory: "1Gi",   cpu: "1000m" }
```

### Trigger Job Programmatically

```java
import io.fabric8.kubernetes.client.*;
import io.fabric8.kubernetes.api.model.batch.v1.*;

@Service
public class BatchJobTrigger {

    @Autowired
    KubernetesClient k8sClient;

    public void triggerPayrollJob(String payPeriod) {
        Job job = new JobBuilder()
            .withNewMetadata()
                .withName("payroll-" + payPeriod)
                .withNamespace("production")
                .addToLabels("app", "payroll-batch")
                .addToLabels("pay-period", payPeriod)
            .endMetadata()
            .withNewSpec()
                .withBackoffLimit(3)
                .withNewTemplate()
                    .withNewSpec()
                        .withRestartPolicy("OnFailure")
                        .addNewContainer()
                            .withName("payroll")
                            .withImage("myregistry.io/payroll-batch:1.0.0")
                            .addNewEnv()
                                .withName("PAY_PERIOD").withValue(payPeriod)
                            .endEnv()
                        .endContainer()
                    .endSpec()
                .endTemplate()
            .endSpec()
            .build();

        k8sClient.batch().v1().jobs()
            .inNamespace("production")
            .resource(job)
            .create();

        log.info("Triggered payroll batch job for period: {}", payPeriod);
    }

    // Watch job completion
    public boolean waitForCompletion(String jobName, int timeoutMinutes)
            throws InterruptedException {
        for (int i = 0; i < timeoutMinutes * 6; i++) {
            Job job = k8sClient.batch().v1().jobs()
                .inNamespace("production")
                .withName(jobName)
                .get();

            if (job.getStatus().getSucceeded() != null
                    && job.getStatus().getSucceeded() > 0) {
                return true;
            }
            if (job.getStatus().getFailed() != null
                    && job.getStatus().getFailed() >= 3) {
                throw new RuntimeException("Job failed: " + jobName);
            }
            Thread.sleep(10_000); // Check every 10s
        }
        return false;
    }

    static org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(BatchJobTrigger.class);
}
```

---

## 18. Observability — Logs, Metrics, Tracing

### Structured Logging for Kubernetes

```xml
<!-- logback-spring.xml — JSON logging for log aggregation -->
<configuration>
  <springProfile name="kubernetes">
    <appender name="JSON_STDOUT"
              class="ch.qos.logback.core.ConsoleAppender">
      <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <includeMdcKeyName>traceId</includeMdcKeyName>
        <includeMdcKeyName>spanId</includeMdcKeyName>
        <includeMdcKeyName>userId</includeMdcKeyName>
        <customFields>{"service":"order-service","env":"production"}</customFields>
      </encoder>
    </appender>
    <root level="INFO">
      <appender-ref ref="JSON_STDOUT"/>
    </root>
  </springProfile>
</configuration>
```

```java
// ── Structured log output ──────────────────────────────────────────────────
// {
//   "timestamp": "2024-01-15T10:30:00.000Z",
//   "level": "INFO",
//   "logger": "OrderService",
//   "message": "Order created",
//   "traceId": "abc123",
//   "spanId": "def456",
//   "orderId": "O-001",
//   "userId": "U-123",
//   "service": "order-service",
//   "env": "production"
// }
// → Automatically indexed by ELK/Loki
```

### Prometheus Metrics

```java
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.jvm.*;

@Configuration
public class MetricsConfig {

    @Bean
    MeterRegistryCustomizer<MeterRegistry> commonTags(
            @Value("${spring.application.name}") String appName,
            @Value("${POD_NAME:unknown}") String podName,
            @Value("${POD_NAMESPACE:unknown}") String namespace) {
        return registry -> registry.config()
            .commonTags(
                "application", appName,
                "pod",         podName,
                "namespace",   namespace,
                "version",     "1.2.3"
            );
    }
}
```

### ServiceMonitor — Prometheus Operator

```yaml
# ── Prometheus discovers this via ServiceMonitor ──────────────────────────────
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: order-service-monitor
  namespace: production
  labels:
    app: order-service
    release: prometheus    # Must match Prometheus selector
spec:
  selector:
    matchLabels:
      app: order-service
  endpoints:
    - port: management
      path: /actuator/prometheus
      interval: 15s
      scrapeTimeout: 10s
  namespaceSelector:
    matchNames:
      - production

---
# ── PrometheusRule: alerting ──────────────────────────────────────────────────
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: order-service-alerts
  namespace: production
spec:
  groups:
    - name: order-service
      rules:
        - alert: OrderServiceDown
          expr: up{job="order-service"} == 0
          for: 1m
          labels:
            severity: critical
          annotations:
            summary: "Order service is down"
            description: "Pod {{ $labels.pod }} is not responding"

        - alert: OrderServiceHighErrorRate
          expr: |
            rate(http_server_requests_seconds_count{
              application="order-service",
              status=~"5.*"
            }[5m]) > 0.05
          for: 2m
          annotations:
            summary: "Error rate > 5% on order-service"

        - alert: OrderServiceHighLatency
          expr: |
            histogram_quantile(0.99,
              rate(http_server_requests_seconds_bucket{
                application="order-service"
              }[5m])) > 1.0
          for: 3m
          annotations:
            summary: "P99 latency > 1s on order-service"

        - alert: PodMemoryHigh
          expr: |
            container_memory_usage_bytes{
              container="order-service"
            } / container_spec_memory_limit_bytes > 0.85
          for: 5m
          annotations:
            summary: "Pod memory > 85% of limit"
```

---

## 19. Security Best Practices

### Pod Security Context

```yaml
spec:
  # ── Pod-level security ───────────────────────────────────────────────────────
  securityContext:
    runAsNonRoot: true           # Reject containers running as root
    runAsUser: 1000              # Specific non-root UID
    runAsGroup: 1000
    fsGroup: 2000                # Group for volume mounts
    seccompProfile:
      type: RuntimeDefault       # Apply default seccomp profile

  containers:
    - name: order-service
      # ── Container-level security ───────────────────────────────────────────
      securityContext:
        allowPrivilegeEscalation: false   # Can't gain more privs than parent
        readOnlyRootFilesystem: true      # Root FS is read-only
        runAsNonRoot: true
        runAsUser: 1000
        capabilities:
          drop:
            - ALL                         # Drop all Linux capabilities
          add: []                         # Add none back (minimal privileges)

      # Writable directories when readOnlyRootFilesystem=true
      volumeMounts:
        - name: tmp
          mountPath: /tmp
        - name: logs
          mountPath: /var/log

  volumes:
    - name: tmp
      emptyDir: {}
    - name: logs
      emptyDir: {}

---
# ── Pod Security Admission (cluster-wide policy) ──────────────────────────────
# In namespace labels:
# pod-security.kubernetes.io/enforce: restricted
# pod-security.kubernetes.io/warn: restricted
# pod-security.kubernetes.io/audit: restricted

# Levels:
#   privileged: no restrictions
#   baseline:   minimal restrictions (blocks privileged containers)
#   restricted: strict hardening (runAsNonRoot, no privilege escalation, etc.)
```

### Image Security

```dockerfile
# ── Use specific digest (not tag — tags are mutable!) ────────────────────────
FROM eclipse-temurin@sha256:abc123...

# ── Use distroless (minimal attack surface) ────────────────────────────────
FROM gcr.io/distroless/java21-debian12:nonroot

# ── Scan for vulnerabilities in CI ────────────────────────────────────────
# - uses: aquasecurity/trivy-action@master
#   with:
#     image-ref: myregistry.io/order-service:1.2.3
#     severity: 'HIGH,CRITICAL'
#     exit-code: '1'
```

---

## 20. Troubleshooting Guide

### Essential kubectl Commands

```bash
# ═══════════════════════ POD DEBUGGING ═══════════════════════════════════════

# Get pod status
kubectl get pods -n production
kubectl get pods -n production -l app=order-service -o wide

# Describe pod (events, conditions, resource usage)
kubectl describe pod order-service-abc123 -n production

# Logs
kubectl logs order-service-abc123 -n production             # Current logs
kubectl logs order-service-abc123 -n production --previous  # Previous container
kubectl logs order-service-abc123 -n production -f          # Follow (tail)
kubectl logs order-service-abc123 -n production --tail=100  # Last 100 lines
kubectl logs -l app=order-service -n production --all-containers  # All pods

# Exec into running pod
kubectl exec -it order-service-abc123 -n production -- /bin/sh
kubectl exec order-service-abc123 -n production -- cat /config/application.properties
kubectl exec order-service-abc123 -n production -- wget -O- localhost:8081/actuator/health

# Copy files to/from pod
kubectl cp order-service-abc123:/tmp/heapdump.hprof ./heapdump.hprof -n production
kubectl cp ./config.properties order-service-abc123:/tmp/config.properties -n production

# ═══════════════════════ DEPLOYMENT DEBUGGING ════════════════════════════════

# Rollout status
kubectl rollout status deployment/order-service -n production
kubectl rollout history deployment/order-service -n production

# Resource usage
kubectl top pods -n production
kubectl top pods -n production --sort-by=memory
kubectl top nodes

# Events (sorted by time)
kubectl get events -n production --sort-by='.metadata.creationTimestamp'
kubectl get events -n production --field-selector=involvedObject.name=order-service-abc123

# ═══════════════════════ NETWORK DEBUGGING ═══════════════════════════════════

# Test service connectivity from inside cluster
kubectl run curl-test --image=curlimages/curl:latest --rm -it --restart=Never \
    -n production -- curl http://order-service:80/actuator/health

# DNS resolution
kubectl run dns-test --image=busybox:latest --rm -it --restart=Never \
    -n production -- nslookup order-service.production.svc.cluster.local

# Port-forward service to localhost (for testing)
kubectl port-forward service/order-service 8080:80 -n production
kubectl port-forward pod/order-service-abc123 8080:8080 -n production

# ═══════════════════════ RESOURCE DEBUGGING ══════════════════════════════════

# Check node capacity
kubectl describe nodes | grep -A5 "Allocated resources"

# Check pod resource requests vs node capacity
kubectl get pods -n production -o custom-columns=\
"NAME:.metadata.name,CPU-REQ:.spec.containers[0].resources.requests.cpu,\
MEM-REQ:.spec.containers[0].resources.requests.memory"

# Check pending pods
kubectl get pods -n production --field-selector=status.phase=Pending
kubectl describe pod <pending-pod> -n production  # Look for "Insufficient" in Events
```

### Common Problems & Solutions

```
PROBLEM: Pod stuck in Pending
─────────────────────────────
Cause 1: Insufficient resources
  → kubectl describe pod → "0/3 nodes are available: Insufficient cpu"
  → Fix: Reduce resource requests OR scale cluster
  → Check: kubectl get nodes; kubectl describe node <node-name>

Cause 2: No matching node (taint/affinity mismatch)
  → "node(s) had taints that the pod didn't tolerate"
  → Fix: Add tolerations or fix node selector

Cause 3: PVC pending
  → "pod has unbound PVCs"
  → Fix: Check StorageClass exists; check PVC events

────────────────────────────────────────────────────────────────────

PROBLEM: Pod in CrashLoopBackOff
─────────────────────────────────
→ kubectl logs <pod> --previous  ← See why previous container died
→ kubectl describe pod <pod>     ← Check exit code and reason

Exit code 137: OOM Kill (memory limit exceeded)
  → Fix: Increase memory limit
  → Check: -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0

Exit code 1: Application error
  → Check application logs
  → Verify environment variables (DB_URL, passwords)
  → Check if ConfigMap/Secret is missing

Exit code 143: SIGTERM (graceful shutdown requested)
  → Likely liveness probe failing → Kubernetes killing + restarting
  → Check: are probes too aggressive for JVM startup time?
  → Fix: Add startupProbe with high failureThreshold

────────────────────────────────────────────────────────────────────

PROBLEM: Pod Running but not receiving traffic
──────────────────────────────────────────────
→ Check readiness probe: kubectl describe pod → "Readiness probe failed"
→ kubectl exec <pod> -- wget -O- localhost:8081/actuator/health/readiness
→ Check: is DB reachable? Are health check dependencies healthy?

────────────────────────────────────────────────────────────────────

PROBLEM: OOM Kill (Exit Code 137)
──────────────────────────────────
Diagnosis:
  kubectl describe pod <pod> → "OOMKilled"
  kubectl top pod <pod>

Fixes:
  1. Increase memory limit
  2. Verify JVM respects container: -XX:+UseContainerSupport
  3. Set MaxRAMPercentage lower (e.g., 65.0) to leave more headroom
  4. Check for memory leaks: review ThreadLocal, static caches
  5. Get heap dump before crash:
     kubectl exec <pod> -- jcmd 1 GC.heap_dump /tmp/heap.hprof
     kubectl cp <pod>:/tmp/heap.hprof ./heap.hprof

────────────────────────────────────────────────────────────────────

PROBLEM: Slow rolling update / stuck
─────────────────────────────────────
→ kubectl rollout status deployment/order-service
→ New pods stuck in pending or crash loop
→ kubectl get pods -l app=order-service
→ Find the failing new pod and describe/log it

────────────────────────────────────────────────────────────────────

PROBLEM: High restart count
────────────────────────────
kubectl get pods -n production | grep -v "0" | grep "Running"
Causes:
  - Liveness probe too aggressive under load → increase failureThreshold
  - OOM kill → increase memory limit
  - App deadlock → check thread dumps (jstack)
  - Config error on start → check init container and logs
```

---

## 21. Interview Questions & Answers

| # | Question | Answer |
|---|----------|--------|
| 1 | What is Kubernetes and what problem does it solve? | Kubernetes is a container orchestration platform. It solves: running containers reliably at scale across multiple machines, auto-healing (restarting crashed containers), auto-scaling (adding pods based on load), zero-downtime rolling deploys, service discovery, configuration management, and infrastructure portability across cloud providers. |
| 2 | What is the difference between a Pod, Deployment, and ReplicaSet? | Pod: smallest unit, one or more containers sharing network and storage. ReplicaSet: ensures N identical pod replicas are running. Deployment: manages ReplicaSets, adds rolling updates, rollback, pause/resume. In practice, always use Deployments — they create and manage ReplicaSets automatically. |
| 3 | Explain Kubernetes service types. | ClusterIP (default): virtual IP, cluster-internal only, for inter-service communication. NodePort: opens a port on every node, accessible from outside. LoadBalancer: provisions cloud load balancer, use for external services. Headless (ClusterIP:None): DNS returns pod IPs directly, used for StatefulSets. ExternalName: maps to external DNS (CNAME). |
| 4 | What is the difference between liveness and readiness probes? | Liveness: is the app alive? Failure → pod RESTARTED. Detects deadlocks/stuck states. Readiness: is the app ready to serve traffic? Failure → pod REMOVED from Service endpoints (no restart). Detects app not yet ready (cache warming, waiting for dependencies). Both use Startup probe as a prerequisite that disables them during slow JVM startup. |
| 5 | What happens when a pod is deleted? | 1. Pod status becomes Terminating. 2. preStop lifecycle hook runs. 3. Pod removed from Service endpoints (no new traffic). 4. SIGTERM sent to containers. 5. App has terminationGracePeriodSeconds to complete in-flight work. 6. SIGKILL sent if timeout reached. This sequence enables zero-downtime deploys. |
| 6 | How does Kubernetes rolling update work? | With `maxUnavailable=0, maxSurge=1`: starts 1 new pod, waits for readiness probe to pass, then stops 1 old pod. Repeats until all pods are updated. Never has fewer than `replicas` pods. Rollback is instant: `kubectl rollout undo`. |
| 7 | What are ConfigMaps and Secrets? What's the difference? | ConfigMap: stores non-sensitive config as key-value pairs or files. Secret: stores sensitive data (passwords, tokens) — base64 encoded in etcd (not encrypted by default). Both can be consumed as env vars or volume-mounted files. Use Sealed Secrets, Vault, or SOPS for encryption at rest. |
| 8 | How does Kubernetes handle JVM memory? | Without `-XX:+UseContainerSupport`: JVM sees host memory, may allocate 16GB heap in a 1GB container → OOM kill. With container support (Java 10+, default on): JVM respects cgroup limits. Use `-XX:MaxRAMPercentage=75.0` to allocate 75% of container memory as heap, leaving 25% for Metaspace, CodeCache, thread stacks, off-heap. |
| 9 | What is a StatefulSet and when to use it? | StatefulSet gives pods stable network identity (kafka-0, kafka-1) and stable storage (each pod gets its own PVC). Ordered start/stop. Use for: Kafka, ZooKeeper, Cassandra, PostgreSQL, Redis Sentinel — anything needing stable hostnames or per-instance storage. Deployments are for stateless apps. |
| 10 | What is Helm and why use it? | Helm is Kubernetes package manager. A chart packages all K8s YAML into a versioned, parameterizable unit. Benefits: template reuse across environments (dev/staging/prod via values files), versioned releases, rollback, dependency management. Without Helm: dozens of separate YAML files to manage per service. |
| 11 | How does HPA work? | HPA watches metrics (CPU, memory, custom) from the metrics-server. When metric exceeds threshold, it scales deployment replicas up to maxReplicas. Scale-up is fast (controlled by `stabilizationWindowSeconds`). Scale-down is conservative (default 5-min stabilization window) to avoid flapping. Requires resource requests to be set (HPA reads utilization relative to requests). |
| 12 | What is a NetworkPolicy? | NetworkPolicy restricts pod-to-pod traffic at the network layer. By default all pods can communicate freely. A NetworkPolicy is a whitelist — it allows only specified traffic and blocks everything else. Use for: isolating services (only API gateway can reach order-service), compliance (PCI/SOC2), defense in depth. Requires a CNI plugin that supports NetworkPolicy (Calico, Cilium). |
| 13 | Explain pod affinity and anti-affinity. | Affinity: prefer/require scheduling pods NEAR certain pods or nodes. Anti-affinity: prefer/require scheduling pods AWAY from certain pods. Use case: spread replicas across nodes (`podAntiAffinity` on same `kubernetes.io/hostname`) to prevent all replicas from being on the same node. Hard (`requiredDuringScheduling`) vs soft (`preferredDuringScheduling`) rules. |
| 14 | What is an init container? | Container that runs to completion BEFORE main containers start. Use cases: wait for dependencies (wait for DB to be ready), run DB migrations (Flyway), clone repos, download config. Unlike sidecars, init containers run sequentially and must exit 0 for the pod to continue. |
| 15 | How do you handle secrets in Kubernetes securely? | Options: (1) Kubernetes Secrets + etcd encryption at rest + RBAC restriction. (2) Sealed Secrets: encrypted with cluster public key, safe in Git. (3) External Secrets Operator: syncs from AWS Secrets Manager / Vault. (4) HashiCorp Vault agent injector: inject secrets at pod runtime. Never store plaintext secrets in Git or ConfigMaps. |
| 16 | What is RBAC in Kubernetes? | Role-Based Access Control: controls who can do what. Role/ClusterRole: defines permissions (verbs on resources). RoleBinding/ClusterRoleBinding: grants a Role to a user, group, or ServiceAccount. ServiceAccount: pod identity. Use principle of least privilege: pods should only have permissions they need (e.g., read ConfigMaps, not delete pods). |
| 17 | How does service discovery work in Kubernetes? | CoreDNS resolves service names to ClusterIP. Format: `<service>.<namespace>.svc.cluster.local`. Short form within same namespace: just `<service>`. Spring Boot apps use this for inter-service calls (WebClient to `http://user-service:80`). No service registry needed — built into Kubernetes DNS. |
| 18 | How do you achieve zero-downtime deployment in Kubernetes? | Combine: (1) RollingUpdate with maxUnavailable=0. (2) Readiness probe must pass before old pod removed. (3) preStop hook with `sleep 15` to let load balancer drain connections. (4) `server.shutdown=graceful` in Spring Boot. (5) `terminationGracePeriodSeconds=60`. (6) PodDisruptionBudget to block voluntary disruptions. |
| 19 | What is a PodDisruptionBudget? | PDB guarantees a minimum number of pods stay available during voluntary disruptions (node drain, rolling updates). Example: `minAvailable: 2` means at least 2 pods must be running at all times. Kubernetes will refuse to evict a pod if doing so would violate the PDB. Critical for preventing all replicas from being drained at once. |
| 20 | How do you debug a Java application running in Kubernetes? | 1. `kubectl logs <pod>` for application logs. 2. `kubectl describe pod` for events/conditions. 3. `kubectl exec <pod> -- jstack 1` for thread dump. 4. `kubectl exec <pod> -- jcmd 1 GC.heap_dump /tmp/heap.hprof` + `kubectl cp` for heap analysis. 5. `kubectl port-forward` to expose actuator locally. 6. JFR: `kubectl exec <pod> -- jcmd 1 JFR.start duration=60s`. 7. Liveness/readiness endpoints: exec wget to check directly. |

---

## 22. Complete Reference Summary

### Quick Commands Cheat Sheet

```bash
# ── Context & Namespace ───────────────────────────────────────────────────────
kubectl config get-contexts                              # List clusters
kubectl config use-context production-cluster           # Switch cluster
kubectl config set-context --current --namespace=prod   # Set default namespace

# ── Get resources ─────────────────────────────────────────────────────────────
kubectl get pods,svc,deploy,cm,secret -n production
kubectl get all -n production
kubectl get events -n production --sort-by=.metadata.creationTimestamp

# ── Apply / Delete ────────────────────────────────────────────────────────────
kubectl apply -f manifest.yaml
kubectl apply -f ./k8s/                                # Apply all YAML in dir
kubectl delete -f manifest.yaml
kubectl delete pod <name> --force --grace-period=0     # Force kill

# ── Deployment ────────────────────────────────────────────────────────────────
kubectl rollout status deployment/<name>
kubectl rollout history deployment/<name>
kubectl rollout undo deployment/<name>
kubectl rollout restart deployment/<name>              # Rolling restart
kubectl scale deployment/<name> --replicas=5
kubectl set image deployment/<name> container=image:tag

# ── Debug ─────────────────────────────────────────────────────────────────────
kubectl logs <pod> -f --tail=100 -n production
kubectl logs <pod> --previous -n production            # Crashed container logs
kubectl exec -it <pod> -n production -- /bin/sh
kubectl describe pod <pod> -n production
kubectl top pods -n production --sort-by=memory
kubectl port-forward svc/<svc> 8080:80 -n production

# ── Resource inspection ───────────────────────────────────────────────────────
kubectl get pod <pod> -o yaml -n production            # Full YAML
kubectl get pod <pod> -o jsonpath='{.status.podIP}'    # Specific field
kubectl api-resources                                  # All resource types
kubectl explain deployment.spec.strategy               # Field docs

# ── Helm ──────────────────────────────────────────────────────────────────────
helm list -A                                           # All releases
helm upgrade --install <name> <chart> -f values.yaml
helm rollback <name> <revision>
helm template <chart> -f values.yaml | kubectl apply -f -
```

### Resource Sizing Reference

```
Container Size     Memory Request/Limit    CPU Request/Limit    JAVA_OPTS
─────────────────────────────────────────────────────────────────────────
XS (microservice)  128Mi / 256Mi           50m  / 250m          MaxRAMPercentage=65
S  (REST API)      256Mi / 512Mi           100m / 500m          MaxRAMPercentage=70
M  (API + cache)   512Mi / 1Gi             250m / 1000m         MaxRAMPercentage=75
L  (high traffic)  1Gi   / 2Gi             500m / 2000m         MaxRAMPercentage=75
XL (batch/stream)  2Gi   / 4Gi             1000m/ 4000m         MaxRAMPercentage=75
```

### Architecture Decision Guide

```
Need                                  → K8s Resource
──────────────────────────────────────────────────────────────────────────────
Stateless app (REST API)              → Deployment
Stateful app (Kafka, DB)              → StatefulSet
One-time task                         → Job
Scheduled task (Spring Batch)         → CronJob
Internal service discovery            → ClusterIP Service
External traffic entry                → Ingress + LoadBalancer Service
Non-sensitive config                  → ConfigMap
Sensitive config (passwords)          → Secret (Sealed Secrets for GitOps)
Persistent storage                    → PVC + StorageClass
Scale based on CPU/RPS                → HPA
Right-size CPU/Memory                 → VPA (recommend mode)
Traffic isolation                     → NetworkPolicy
Spread across nodes/zones             → TopologySpreadConstraints
Prevent noisy neighbor                → ResourceQuota + LimitRange
Prevent all-pods-down drain           → PodDisruptionBudget
GitOps deployment                     → ArgoCD + Helm
Package multiple K8s YAMLs            → Helm Chart
Secrets from AWS Secrets Manager      → External Secrets Operator
Service mesh (mTLS, traffic mgmt)     → Istio / Linkerd
```

### Zero-Downtime Deploy Checklist

```
Before deploying:
  □ startupProbe with sufficient failureThreshold for JVM startup
  □ readinessProbe checks all dependencies (DB, cache, downstream)
  □ livenessProbe is not too aggressive
  □ resources.requests and limits set (required for HPA)
  □ PodDisruptionBudget defined (minAvailable ≥ 1)

Application:
  □ server.shutdown=graceful
  □ spring.lifecycle.timeout-per-shutdown-phase=30s
  □ preStop: sleep 15 (drain LB connections)
  □ terminationGracePeriodSeconds=60
  □ -XX:+UseContainerSupport -XX:MaxRAMPercentage=75

Deployment strategy:
  □ strategy: RollingUpdate with maxUnavailable: 0
  □ Readiness probe passes before old pod killed
  □ topologySpreadConstraints for HA across nodes/zones
  □ podAntiAffinity to spread replicas

Post-deploy:
  □ kubectl rollout status — confirm all pods ready
  □ Monitor error rate and latency (Prometheus/Grafana)
  □ kubectl rollout undo if anomalies detected
```

---

*Made with ❤️ for Java developers — Kubernetes 1.29+ | Spring Boot 3.x | Helm 3.x*
