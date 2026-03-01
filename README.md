# Reproducing a Redisson Bug (Java)

> Demonstrates a bug present in versions 3.17.6, 3.32.0, 3.49.0, and the latest 4.2.0.

## Prerequisites

- Java 17+
- Maven
- Redis Cluster (6 nodes: 3 masters, 3 replicas)

## Project Structure

```
redisson-test/
├── redisson-4.2.0-observability/    # Git submodule - Modified Redisson with observability logging
│   └── redisson/
│       └── src/...
├── scripts/
│   └── reproduce-bug.sh             # Automated reproduction script
├── src/                             # Test application
└── pom.xml
```

## How to Run

### 1. Clone with Submodules

This project uses a git submodule for the observability-instrumented Redisson:

```bash
git clone --recursive git@github.com:ngyngcphu/redisson-reproduce-bug.git
```

Or if already cloned without submodules:

```bash
git submodule update --init --recursive
```

### 2. Build the Observability-Instrumented Redisson

Build and install the modified Redisson with observability logging:

```bash
cd redisson-4.2.0-observability/redisson
mvn clean install -DskipTests -Dcheckstyle.skip=true
```

This installs `redisson-4.2.0-observability.jar` to local Maven repository. The source code includes detailed observability logging to trace the bug.

### 3. Build the Test Application

```bash
cd ../..  # Back to redisson-test root
mvn clean package -DskipTests
```

### 4. Configure

Set environment variables for Redis cluster:

```bash
export REDIS_HOST=redis-host
export REDIS_PORT=6379
export REDIS_PASSWORD=password  # If required
```

### 5. Run

```bash
mvn spring-boot:run 2>&1 | tee app.log
```

## How to Reproduce the Bug

### Automated (Recommended)

```bash
export REDIS_PASSWORD=password

# Run the reproduction script
./scripts/reproduce-bug.sh
```

The script runs 20 rounds of chaos testing with `CLIENT PAUSE` and `CLUSTER FAILOVER` to trigger the timing window where the bug occurs.

## The Bug

Under high load with connection churn, a `PING` response can be incorrectly matched to a `CLUSTER NODES` command. This causes a `ClassCastException` that is silently swallowed, stopping the cluster topology refresh permanently.

### Normal Flow

```
    Application                Queue                    Redis Cluster
    -----------                -----                    -------------
         |                       |                            |
         |   PING                |                            |
         |---------------------->|                            |
         |                       |   PING                     |
         |                       |--------------------------->|
         |                       |                            |
         |                       |   PONG                     |
         |                       |<---------------------------|
         |   PONG                |                            |
         |<----------------------|                            |
         |                       |                            |
         |   CLUSTER NODES       |                            |
         |---------------------->|                            |
         |                       |   CLUSTER NODES            |
         |                       |--------------------------->|
         |                       |                            |
         |                       |   List<ClusterNodeInfo>    |
         |                       |<---------------------------|
         | List<ClusterNodeInfo> |                            |
         |<----------------------|                            |
         |                       |                            |

    Topology updates every 2 seconds
```

### When Bug Hits

```
    Timeline (Horizontal):

    T+0s          T+3s              T+13s             T+15s
    PING sent     PING timeout      CLUSTER sent      Late PONG arrives
       |              |                  |                  |
       v              v                  v                  v
    [PING]  ---->  [PING]  ------>  [PING      ]  ---->  [CLUSTER_NODES]
                                    [CLUSTER NODES]       (PING removed)


    What Happens:

                                                              |
                                                              v
                                                  +-----------------------+
                                                  | Type Mismatch         |
                                                  |                       |
                                                  | Expected: List        |
                                                  | Received: String      |
                                                  +-----------------------+
                                                              |
                                                              v
                                                  +-----------------------+
                                                  | ClassCastException    |
                                                  | at lambda entry       |
                                                  +-----------------------+
                                                              |
                                                              v
                                                  +-----------------------+
                                                  | CompletableFuture     |
                                                  | swallows it silently  |
                                                  +-----------------------+
                                                              |
                                                              v
                                                  +-----------------------+
                                                  | Handler body          |
                                                  | NEVER executes        |
                                                  +-----------------------+
                                                              |
                                                              v
                                                  +-----------------------+
                                                  | scheduleClusterCheck()|
                                                  | NEVER called          |
                                                  +-----------------------+
                                                              |
                                                              v
                                                    scanInterval STOPS
```

### The Domino Effect

The bug just stops topology refresh. My app keeps running with stale data. But if Redis fails over while refresh is dead -> BROKEN!!!

```
    +------------------+      +------------------+      +------------------+
    |   STEP 1         |      |   STEP 2         |      |   STEP 3         |
    |   Bug Kills      | ---> |   Redis Master   | ---> |   App Tries      |
    |   scanInterval   |      |   Fails          |      |   Old Master     |
    +------------------+      +------------------+      +------------------+
    |                  |      |                  |      |                  |
    | Topology Frozen: |      | Cluster promotes |      | Request to:      |
    |                  |      | slave to master  |      | 10.x.x.x:6379    |
    | Master:          |      |                  |      | (dead)           |
    | 10.x.x.x:6379    |      | New Master:      |      |                  |
    |                  |      | 172.16.41.18     |      | Redis responds:  |
    | Slaves:          |      | (Cluster IP)     |      | MOVED 3652       |
    | 10.x.x.x:6380    |      |                  |      | 172.16.41.18     |
    +------------------+      +------------------+      +------------------+
                                                                 |
                                                                 |
                                                                 v
    +------------------+      +------------------+               |
    |   STEP 5         |      |   STEP 4         |               |
    |   Everything     | <--- |   App Tries      | <-------------+
    |   Fails          |      |   Cluster IP     |
    +------------------+      +------------------+
    |                  |      |                  |
    | Logs flood with: |      | App (outside K8s)|
    |                  |      | tries to connect |
    | RedisNodeNot     |      | to 172.16.41.18  |
    | FoundException   |      |                  |
    | RedisNodeNot     |      | Connection       |
    | FoundException   |      | refused!         |
    | RedisNodeNot     |      |                  |
    | FoundException   |      | (unreachable     |
    | ...              |      | from outside)    |
    |                  |      |                  |
    | App is DEAD      |      |                  |
    +------------------+      +------------------+
```

### Kubernetes Networking Trap

```
    Redis Pod in Kubernetes has TWO IP addresses:

    +--------------------------------+        +--------------------------------+
    |                                |        |                                |
    |   Cluster IP (Internal)        |        |   NodePort IP (External)       |
    |                                |        |                                |
    |   172.16.41.18:6379            |        |   10.x.x.x:30001               |
    |                                |        |                                |
    |   - Only inside K8s cluster    |        |   - Reachable from outside     |
    |   - Redis uses in MOVED        |        |   - App uses normally          |
    |   - Unreachable from outside   |        |   - scanInterval provides this |
    |                                |        |                                |
    +--------------------------------+        +--------------------------------+
                    |                                         |
                    |                                         |
                    v                                         v
            MOVED redirects                          Normal topology
            contain this                             refresh uses this


    The Problem:

    1. MOVED redirects contain Cluster IPs (Redis doesn't know about NodePort)
    2. Normally scanInterval refreshes topology with correct NodePort IPs
    3. But if scanInterval is dead, always stuck with unreachable IPs
    4. Forever
```

## Why I Built This

I hit this in production. Payment processing went down, service degraded. Took me months to figure out why the app would randomly die after Redis failovers.

Turns out it wasn't random - the bug had already killed scanInterval hours earlier, I just didn't know until the failover exposed it.

This reproduction kit helps trigger the bug reliably so can see it happen, understand it, and verify the fix works.
