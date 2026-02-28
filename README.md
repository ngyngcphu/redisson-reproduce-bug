# Reproducing a Redisson Bug (Java)

> Demonstrates a bug present in versions 3.17.6, 3.32.0, 3.49.0, and the latest 4.2.0.

## Prerequisites

- Java 17+
- Maven
- Redis Cluster (6 nodes: 3 masters, 3 replicas)

## How to Run

### 1. Build

```bash
mvn clean package -DskipTests
```

### 2. Configure

Set environment variables for your Redis cluster:

```bash
export REDIS_HOST=your-redis-host
export REDIS_PORT=6379
export REDIS_PASSWORD=your-password  # If required
```

### 3. Run

```bash
java -jar target/redisson-test-0.0.1-SNAPSHOT.jar
```

## How to Reproduce the Bug

### Automated (Recommended)

```bash
# Set your Redis password
export REDIS_PASSWORD=your-password

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

The bug just stops topology refresh. Your app keeps running with stale data. But if Redis fails over while refresh is dead, you're toast.

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
    3. But if scanInterval is dead, you're stuck with unreachable IPs
    4. Forever
```

## Why We Built This

We hit this in production. Payment processing went down, service degraded. Took us months to figure out why the app would randomly die after Redis failovers.

Turns out it wasn't random - the bug had already killed scanInterval hours earlier, we just didn't know until the failover exposed it.

This reproduction kit helps you trigger the bug reliably so you can see it happen, understand it, and verify the fix works.
