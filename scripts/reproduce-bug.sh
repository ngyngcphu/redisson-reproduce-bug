#!/bin/bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8000}"
NS="${NS:-redis-cluster-dev}"
REDIS_PASSWORD="${REDIS_PASSWORD:-}"
APP_LOG="${APP_LOG:-}"

# Thread Configuration
MAX_THREADS=1024        # Maximum JVM threads - saturates Redis event loop
KEY_POOL=1              # Single key = ALL load concentrated on ONE slot
SLEEP_MS=0              # No delay between requests = max request rate
HASH_TAG="flood"        # Hash tag forces all keys to same slot

# Chaos Configuration
CHAOS_ROUNDS=20         # 20 rounds = maximum attempts to hit timing window
FAILOVER_ROUND=10       # Trigger failover in middle of chaos (round 10/20)
CLIENT_PAUSE_MS=15000   # 15 seconds = extended event loop starvation

# JVM Tuning for Maximum Connections
export JAVA_OPTS="-Xmx4g -Xms4g -XX:MaxDirectMemorySize=2g -Dio.netty.leakDetectionLevel=disabled"

echo "======================================================================"
echo "                    REDISSON BUG REPRODUCTION"
echo "======================================================================"
echo ""
echo "Configuration:"
echo "  Threads:        $MAX_THREADS (maximize connection count)"
echo "  Key Pool:       $KEY_POOL (all keys hit same slot)"
echo "  Sleep:          ${SLEEP_MS}ms (no throttling)"
echo "  Hash Tag:       $HASH_TAG (slot pinning)"
echo "  Client Pause:   ${CLIENT_PAUSE_MS}ms (extended pause)"
echo "  Chaos Rounds:   $CHAOS_ROUNDS (more attempts)"
echo ""

for run in {1..20}; do
    echo ""
    echo "======================================================================"
    echo "                         RUN $run/20 (MAX CONFIG)"
    echo "======================================================================"
    echo ""

    # Clear log
    > "$APP_LOG" 2>/dev/null || true

    # Get baseline
    BASELINE_SCAN=$(grep -c -i "cluster nodes state got" "$APP_LOG" 2>/dev/null | head -1 || echo "0")
    BASELINE_SCAN=$(echo "$BASELINE_SCAN" | tr -d '\n' | tr -d ' ')
    echo "Baseline scanInterval count: $BASELINE_SCAN"
    echo ""

    echo "Step 1: Start MAXIMUM load ($MAX_THREADS threads, 1 key, 0ms sleep)..."
    echo "        All threads hitting slot for key 'key0{flood}'"
    curl -sf -X POST "$BASE_URL/load/start?concurrency=$MAX_THREADS&keyPool=$KEY_POOL&sleepMs=$SLEEP_MS&hashTag=$HASH_TAG"
    echo ""
    sleep 5

    echo "Step 2: Run $CHAOS_ROUNDS rounds of chaos..."
    echo ""

    for i in $(seq 1 $CHAOS_ROUNDS); do
        if [ $i -eq $FAILOVER_ROUND ]; then
            echo "  Round $i/$CHAOS_ROUNDS - [$(date '+%H:%M:%S')] >>> TRIGGERING FAILOVER <<<"
            MASTER_POD=$(kubectl get pods -n "$NS" -o name 2>/dev/null | grep "drc-redis-guard-v2-normal" | head -1 | cut -d/ -f2)
            CLUSTER_NODES=$(kubectl exec "$MASTER_POD" -n "$NS" -c redis -- redis-cli -a "$REDIS_PASSWORD" CLUSTER NODES 2>/dev/null)
            SLAVE_IP=$(echo "$CLUSTER_NODES" | grep "slave" | awk '{print $2}' | cut -d: -f1 | head -1)
            SLAVE_POD=$(kubectl get pods -n "$NS" -o wide 2>/dev/null | grep "$SLAVE_IP" | grep "guard-v2-normal" | awk '{print $1}')
            kubectl exec "$SLAVE_POD" -n "$NS" -c redis -- redis-cli -a "$REDIS_PASSWORD" CLUSTER FAILOVER 2>/dev/null > /dev/null || true
        else
            echo "  Round $i/$CHAOS_ROUNDS"
        fi

        # Apply CLIENT PAUSE to ALL Redis nodes
        for pod in $(kubectl get pods -n "$NS" -o name 2>/dev/null | grep guard-v2-normal); do
            pod_name=$(echo "$pod" | cut -d/ -f2)
            kubectl exec "$pod_name" -n "$NS" -c redis -- redis-cli -a "$REDIS_PASSWORD" CLIENT PAUSE $CLIENT_PAUSE_MS 2>/dev/null > /dev/null &
        done
        wait 2>/dev/null || true
    done

    echo ""
    echo "Step 3: Monitoring for 1 minute..."
    echo ""
    printf "%-8s %-10s %-12s %-12s %-10s\n" "Check" "Time" "HTTP" "scanInterval" "Errors"
    echo "--------------------------------------------------------"

    MAX_STREAK=0
    CURRENT_STREAK=0
    LAST_SCAN_COUNT=$BASELINE_SCAN

    for i in {1..6}; do
        sleep 10
        CURRENT_TIME=$(date '+%H:%M:%S')

        HTTP_STATUS=$(curl -sf -o /dev/null -w "%{http_code}" "$BASE_URL/test?key=probe$i" 2>/dev/null || echo "FAIL")
        CURRENT_SCAN=$(grep -c -i "cluster nodes state got" "$APP_LOG" 2>/dev/null | head -1 || echo "0")
        CURRENT_SCAN=$(echo "$CURRENT_SCAN" | tr -d '\n' | tr -d ' ')
        SCAN_DELTA=$((CURRENT_SCAN - LAST_SCAN_COUNT))
        LAST_SCAN_COUNT=$CURRENT_SCAN
        ERROR_COUNT=$(grep -c "hasn't been discovered" "$APP_LOG" 2>/dev/null | head -1 || echo "0")
        ERROR_COUNT=$(echo "$ERROR_COUNT" | tr -d '\n' | tr -d ' ')

        if [ $SCAN_DELTA -eq 0 ]; then
            CURRENT_STREAK=$((CURRENT_STREAK + 1))
            [ $CURRENT_STREAK -gt $MAX_STREAK ] && MAX_STREAK=$CURRENT_STREAK
        else
            CURRENT_STREAK=0
        fi

        printf "%-8s %-10s %-12s %-12s %-10s\n" "$i/6" "$CURRENT_TIME" "$HTTP_STATUS" "$CURRENT_SCAN" "$ERROR_COUNT"
    done

    echo ""
    echo "======================================================================"
    echo "                         ANALYSIS - RUN $run"
    echo "======================================================================"
    echo ""

    FINAL_SCAN=$(grep -c -i "cluster nodes state got" "$APP_LOG" 2>/dev/null | head -1 || echo "0")
    FINAL_SCAN=$(echo "$FINAL_SCAN" | tr -d '\n' | tr -d ' ')
    FINAL_ERROR=$(grep -c "hasn't been discovered" "$APP_LOG" 2>/dev/null | head -1 || echo "0")
    FINAL_ERROR=$(echo "$FINAL_ERROR" | tr -d '\n' | tr -d ' ')
    TOTAL_SCAN_DELTA=$((FINAL_SCAN - BASELINE_SCAN))
    EXPECTED_SCANS=30

    echo "Metrics:"
    echo "  Baseline scanInterval:    $BASELINE_SCAN"
    echo "  Final scanInterval:       $FINAL_SCAN"
    echo "  New scanInterval runs:    $TOTAL_SCAN_DELTA"
    echo "  Expected (1min @ 2s):     ~$EXPECTED_SCANS"
    echo "  Max no-activity streak:   ${MAX_STREAK}0 seconds"
    echo "  RedisNodeNotFound errors: $FINAL_ERROR"
    echo ""

    # Check if bug reproduced:
    # Option 1: scanInterval stopped + errors accumulated
    # Option 2: scanInterval paused for >30 seconds (regardless of errors)
    if ([ "$TOTAL_SCAN_DELTA" -lt 15 ] && [ "$FINAL_ERROR" -gt 10 ]) || [ "$MAX_STREAK" -gt 3 ]; then
        echo "╔════════════════════════════════════════════════════════════════╗"
        echo "║  ✓✓✓ BUG REPRODUCED ON RUN $run! ✓✓✓                              ║"
        echo "╚════════════════════════════════════════════════════════════════╝"
        echo ""
        echo "Configuration that worked:"
        echo "  - $MAX_THREADS threads hitting single slot"
        echo "  - ${CLIENT_PAUSE_MS}ms CLIENT PAUSE"
        echo "  - $CHAOS_ROUNDS chaos rounds"
        echo ""

        curl -sf -X POST "$BASE_URL/load/stop" > /dev/null 2>&1 || true
        exit 0
    else
        echo "Result: App recovered (scanInterval still running)"
        echo ""
        if [ "$FINAL_ERROR" -gt 0 ]; then
            echo "  Note: RedisNodeNotFoundException detected but app recovered"
        fi
        if [ "$MAX_STREAK" -gt 3 ]; then
            echo "  Note: scanInterval paused for ${MAX_STREAK}0s but timer eventually fired"
            echo "        (bug condition partially met but recovered)"
        fi
    fi

    # Stop load for this iteration
    echo "Stopping load..."
    curl -sf -X POST "$BASE_URL/load/stop" > /dev/null 2>&1 || true
    sleep 2
done

echo ""
echo "======================================================================"
echo "                         FINAL RESULT"
echo "======================================================================"
echo ""
echo "Bug NOT reproduced in any of 20 runs."
echo ""
echo "Even with MAXIMUM configuration:"
echo "  - 1024 threads (highest connection count)"
echo "  - Single key (all load on one slot)"
echo "  - 20 chaos rounds (maximum attempts)"
echo "  - 15s CLIENT PAUSE (extended starvation)"
echo ""

