# Distributed Rate Limiter

A distributed token-bucket rate limiter implemented at the API gateway using Spring Cloud Gateway, Redis Cluster, ZooKeeper, and Kubernetes.

The system is designed around three separate responsibilities:

* **Gateway** — request enforcement
* **Redis Cluster** — distributed global rate-limit state
* **ZooKeeper** — dynamic configuration

This separation keeps the request path focused on the rate-limit decision while allowing the system to scale horizontally and configuration to change without restarting the application.

---

## Architecture

```text
                         Client
                           |
                           v
                +---------------------+
                |  Spring Cloud       |
                |  Gateway / WebFlux  |
                +----------+----------+
                           |
                    X-Client-Id
                           |
                           v
                +---------------------+
                |    Rate Limiter     |
                +----------+----------+
                           |
              +------------+------------+
              |                         |
              v                         v
      +---------------+         +---------------+
      | ZooKeeper     |         | Redis Cluster |
      | 3-node        |         | 3P + 3R       |
      | ensemble      |         |               |
      +---------------+         +-------+-------+
                                        |
                                        v
                                Product Service
```

### Request flow

```text
Client
  |
  v
Gateway
  |
  +-- validate X-Client-Id
  |
  +-- use current rate-limit configuration
  |
  +-- execute atomic Redis token-bucket operation
  |
  +---- allowed ----> Product Service
  |
  +---- rejected ---> HTTP 429
```

ZooKeeper is **not accessed for every request**. The application watches the relevant configuration and maintains the current values in memory.

Redis is the shared state store used for the actual rate-limit decision.

---

# Design

## Token Bucket

The rate limiter uses the **Token Bucket** algorithm.

Each client has a bucket containing tokens.

The bucket is defined by:

* `capacity` — maximum number of tokens
* `refillRate` — rate at which tokens are replenished

A request consumes one token when a token is available.

```text
                 refill
                   |
                   v
              +---------+
              | Bucket  |
              | tokens  |
              +----+----+
                   |
             request arrives
                   |
             token available?
              /           \
            yes            no
             |              |
        consume token      reject
             |              |
             v              v
          allow            429
```

### Why Token Bucket?

Several common rate-limiting algorithms are possible:

| Algorithm              | Main characteristic                                      |
| ---------------------- | -------------------------------------------------------- |
| Fixed Window Counter   | Simple counter for a fixed time window                   |
| Sliding Window Log     | Tracks individual request timestamps                     |
| Sliding Window Counter | Approximates a sliding window using counters             |
| Token Bucket           | Controls sustained rate while allowing controlled bursts |

Token Bucket was chosen because it provides a useful balance between **controlled bursts, sustained rate limiting, and low per-request state overhead**.

A client can temporarily consume tokens accumulated in the bucket, up to `capacity`, while `refillRate` controls the long-term request rate.

A Sliding Window Log provides precise request history but requires storing and processing request timestamps. A Sliding Window Counter reduces that storage overhead but is an approximation. A Fixed Window Counter is simpler but can allow boundary bursts between adjacent windows.

For this system, Token Bucket provides the required behavior without maintaining a request-history log for every client.

---

## Atomic Redis Operation

The token-bucket operation is implemented as a Redis Lua script.

The complete operation is performed atomically:

```text
Read bucket state
      |
Calculate elapsed time
      |
Refill tokens
      |
Check available tokens
      |
Consume token if allowed
      |
Update bucket state
      |
Return result
```

This is important because multiple gateway instances can process requests for the same client concurrently, creating a potential **race condition**.

Without an atomic operation, two concurrent requests could read the same token count before either request updates it:

```text
Gateway A ----\
               +----> Redis
Gateway B ----/
```

Both requests could observe the same state and make a decision based on stale data.

The Lua script makes the read, refill, decision, and update a **single atomic Redis operation**, preventing this race condition.

The fundamental distributed design is therefore:

```text
Gateway
   |
   v
Shared Redis state
   |
   v
Atomic token-bucket operation
   |
   +-- allow
   +-- reject
```

The implementation uses **Reactive Spring Data Redis with Lettuce**.

A **connection pool** reuses Redis connections for concurrent rate-limit requests instead of repeatedly creating connections and performing connection handshakes, reducing connection overhead on the request hot path.

Reactive Redis and Lettuce are implementation choices; the fundamental rate-limiter design is the shared Redis state combined with an atomic token-bucket operation.

---

## Redis Cluster

Redis is deployed as:

```text
3 Primary Nodes
+
3 Replica Nodes
```

```text
Primary 1 ---- Replica 1
Primary 2 ---- Replica 2
Primary 3 ---- Replica 3
```

Redis stores the **global rate-limit state**, shared by all gateway instances.

The three primaries provide Redis Cluster sharding.

The cluster uses three primary nodes for horizontal sharding and to maintain a majority of primary nodes if one primary becomes unavailable. Each primary has one replica for data redundancy and automatic failover.

The Redis cluster therefore provides:

* **horizontal distribution of global rate-limit state**
* **replication of each primary's data**
* **failover when a primary becomes unavailable**

Redis is deployed using a Kubernetes StatefulSet so that each node has a stable identity and persistent storage.

### Redis key

Rate-limit buckets use keys in the form:

```text
rate-limit:bucket:{clientId}
```

The `{clientId}` portion is a Redis Cluster hash tag.

This ensures that the bucket is mapped according to the client's hash tag and remains associated with a single Redis Cluster hash slot.

---

## ZooKeeper

ZooKeeper is used for **dynamic rate-limit configuration**.

The current configuration includes:

```text
capacity
refillRate
```

ZooKeeper runs as a **three-node ensemble**, providing replicated configuration storage and quorum-based availability, with the ability to tolerate the loss of one node.

The application watches the relevant configuration and updates its in-memory values when configuration changes.

```text
             ZooKeeper Ensemble
                    |
                    | watch
                    v
          RateLimitConfigWatcher
                    |
                    v
          In-memory configuration
                    |
                    v
              Rate Limiter
```

ZooKeeper is therefore used for **configuration management**. It is also kept off the request hot path.

---

# Gateway

Spring Cloud Gateway is the enforcement point.

The gateway is deployed as a **Kubernetes Deployment**, making it stateless from the rate-limiter state perspective. Rate-limit state is externalized to Redis, allowing multiple gateway replicas to share the same global state.

Requests identify the client using:

```text
X-Client-Id
```

Example:

```bash
curl -H "X-Client-Id: alice" \
     http://localhost:30080/products/1
```

The gateway:

1. Validates the client identifier.
2. Uses the current rate-limit configuration.
3. Executes the Redis token-bucket operation.
4. Forwards allowed requests.
5. Returns `HTTP 429 Too Many Requests` when the bucket does not have a token.
6. Adds rate-limit response headers.

---

# Failure Handling

Redis operations use a limited retry policy with exponential backoff.

Current configuration:

```text
2 retries
10ms initial backoff
```

Redis connections use a **200ms timeout**.

The short timeout combined with the bounded retry policy provides **fail-fast behavior** when Redis is unavailable rather than allowing the rate-limit operation to wait indefinitely.

If Redis remains unavailable after the configured retries, the current implementation **fails closed**:

```text
Redis error
    |
    v
Retry
    |
    v
Retry
    |
    v
Still unavailable
    |
    v
Reject request
    |
    v
HTTP 429
```

The Redis operation returns a rejected `RateLimitResult`, and the gateway responds with HTTP 429.

The goal is to avoid allowing unrestricted traffic when the authoritative rate-limit state cannot be checked.

The Redis cluster itself uses replicas so that individual Redis node failures can be handled through cluster failover.

---

# Kubernetes

The system is deployed as a Kubernetes application.

Main components:

```text
+----------------------------+
| Rate Limiter Gateway       |
| Deployment / Stateless     |
+-------------+--------------+
              |
              +-------------------+
              |                   |
              v                   v
      +---------------+   +---------------+
      | Redis         |   | ZooKeeper     |
      | StatefulSet   |   | StatefulSet   |
      +---------------+   +---------------+
              |
              v
      +---------------+
      | Product       |
      | Service       |
      +---------------+
```

Kubernetes resources include:

* Deployments
* StatefulSets
* Services
* Headless Services
* PersistentVolumeClaims
* ConfigMaps where appropriate

### Stateful components

Redis and ZooKeeper use StatefulSets because their cluster members require stable identities.

Redis Cluster nodes are configured to advertise their Kubernetes DNS hostnames so that cluster members can communicate using stable addresses inside the cluster.

ZooKeeper similarly uses stable Kubernetes identities for its ensemble members.

---

# Security and Container Configuration

The Redis containers run as non-root users with privilege escalation disabled and Linux capabilities dropped.

The project also uses persistent storage for Redis data.

The goal is to keep the Kubernetes deployment closer to a production-style setup rather than relying on default container privileges.

---

# Configuration

Application-level rate-limit configuration is maintained through ZooKeeper.

Example conceptual configuration:

```text
capacity: 100
refillRate: 10
```

Changing the configuration updates the running application through the ZooKeeper watcher without requiring an application restart.

### Configuration management evolution

ZooKeeper remains the configuration source, while an Admin API or configuration server can provide a controlled interface for changing it. The application watches ZooKeeper and updates its in-memory configuration.

---

# Benchmarking

The system is tested using k6.

The measurements are an observed benchmark state rather than a final performance target.

Warmed-up runs reached approximately **2,000 req/s**, with around **49 ms average latency**, **41 ms p50**, **105 ms p95**, and **0% errors** in the tested configuration.

Detailed performance tuning is part of the next benchmarking phase.

---

# Performance Tuning

The next performance phase will tune the system as a whole rather than optimizing one parameter independently.

Areas include:

* Kubernetes CPU and memory resources
* Gateway replicas
* Redis CPU and memory resources
* Redis connection pool
* Redis timeouts
* Retry configuration
* JVM/container resources
* Concurrent load
* Throughput and latency targets

The objective is to identify the actual bottleneck before changing individual parameters.

---

# Current Status

## Implemented

* Token-bucket rate limiting
* Redis-backed global distributed state
* Atomic Redis Lua operation
* Race-condition-safe token consumption
* Redis Cluster with 3 primaries + 3 replicas
* Redis persistence
* Spring Cloud Gateway integration
* ZooKeeper ensemble
* Dynamic rate-limit configuration
* ZooKeeper configuration watcher
* Redis retry handling
* Fail-fast behavior through bounded timeout and retries
* Fail-closed behavior
* Lettuce connection pooling
* Kubernetes deployment
* Persistent storage
* Non-root Redis containers
* k6 load testing

## Planned

* Systematic performance tuning
* Kubernetes resource tuning
* Gateway scaling experiments
* Redis resource tuning
* Connection-pool tuning
* Failure and failover testing
* Authentication
* Authorization
