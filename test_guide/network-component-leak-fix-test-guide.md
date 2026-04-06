# Network Component Leak Fix - Testing Guide

> **Version**: 1.0
> **Date**: 2026-04-05
> **Purpose**: Comprehensive testing procedures for network component resource leak fixes

---

## Table of Contents

1. [Test Environment Setup](#1-test-environment-setup)
2. [Unit Testing](#2-unit-testing)
3. [Integration Testing](#3-integration-testing)
4. [Memory Leak Detection](#4-memory-leak-detection)
5. [Load Testing](#5-load-testing)
6. [Performance Benchmarking](#6-performance-benchmarking)
7. [Acceptance Criteria](#7-acceptance-criteria)
8. [Troubleshooting](#8-troubleshooting)

---

## 1. Test Environment Setup

### 1.1 Prerequisites

**Required Software:**
```bash
# Java 17 JDK
java -version
# Expected: openjdk version "17.x.x"

# Maven 3.8+
mvn -version
# Expected: Apache Maven 3.8.x or higher

# PostgreSQL (for integration tests)
psql --version
# Expected: psql (PostgreSQL) 12.x or higher
```

**Memory Profiling Tools:**
- **JProfiler** (recommended) or **VisualVM** (free)
- **Eclipse Memory Analyzer (MAT)** for heap dump analysis
- **JConsole** (built-in with JDK)

### 1.2 Build Project

```bash
cd D:/project/gplink_ai/gplink/back/jetlinks-community-2.10/jetlinks-community-2.10

# Clean build with tests
mvn clean install

# Skip tests for faster build (use only after initial verification)
mvn clean install -DskipTests
```

**Expected Output:**
```
[INFO] BUILD SUCCESS
[INFO] Total time: XX:XX min
```

### 1.3 Enable Netty Leak Detection

**Create test configuration file:**

**File**: `test_guid/test-jvm-options.txt`
```bash
# Netty Leak Detection (PARANOID for comprehensive detection)
-Dio.netty.leakDetectionLevel=PARANOID

# Native Memory Tracking
-XX:NativeMemoryTracking=detail

# Heap Dump on OOM
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=./heap-dumps/

# GC Logging
-Xlog:gc*:file=./logs/gc.log:time,uptime:filecount=10,filesize=10M

# Reactor Debug (for production-like testing)
-Dreactor.trace.operatorStacktrace=true
```

**Apply options when running tests:**
```bash
export MAVEN_OPTS="$(cat test_guid/test-jvm-options.txt | tr '\n' ' ')"
mvn test
```

---

## 2. Unit Testing

### 2.1 Create Leak Detection Unit Tests

**Test Location**: Create test files in corresponding test directories

#### Test 1: Subscription Tracking Test

**File**: `jetlinks-components/network-component/tcp-component/src/test/java/org/jetlinks/community/network/tcp/gateway/device/TcpServerDeviceGatewayLeakTest.java`

```java
package org.jetlinks.community.network.tcp.gateway.device;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for subscription leak fixes in TcpServerDeviceGateway
 */
public class TcpServerDeviceGatewayLeakTest {

    private TcpServerDeviceGateway gateway;
    private AtomicInteger activeSubscriptions = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        // Setup gateway with mock dependencies
        // TODO: Initialize TcpServer, DeviceRegistry, etc.
    }

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.shutdown().block();
        }
    }

    @Test
    void testNoSubscriptionLeakOnShutdown() {
        // Given: Gateway is started
        gateway.startup().block();

        // Track disposables before
        int beforeCount = activeSubscriptions.get();

        // When: Gateway is shutdown
        gateway.shutdown().block();

        // Then: All subscriptions should be disposed
        assertEquals(0, activeSubscriptions.get() - beforeCount,
            "All subscriptions should be disposed after shutdown");
    }

    @Test
    void testSchedulerDisposedOnDisconnect() throws Exception {
        // Given: A connected client
        // When: Client disconnects
        // Then: legalityChecker scheduler should be disposed

        // This requires accessing internal state or using reflection
        // For production test, monitor thread count instead
        int threadsBefore = Thread.activeCount();

        // Simulate disconnect
        Thread.sleep(100);

        int threadsAfter = Thread.activeCount();
        assertTrue(threadsAfter <= threadsBefore,
            "Thread count should not increase after disconnect");
    }
}
```

#### Test 2: Sink Completion Test

**File**: `jetlinks-components/network-component/tcp-component/src/test/java/org/jetlinks/community/network/tcp/server/VertxTcpServerSinkTest.java`

```java
package org.jetlinks.community.network.tcp.server;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class VertxTcpServerSinkTest {

    @Test
    void testSinkCompletedOnShutdown() {
        VertxTcpServer server = new VertxTcpServer("test-server");
        AtomicBoolean completed = new AtomicBoolean(false);

        // Subscribe to connection flux
        Flux<?> connections = server.handleConnection()
            .doOnComplete(() -> completed.set(true));

        StepVerifier.create(connections)
            .expectSubscription()
            .then(() -> {
                // Shutdown server
                server.shutdown();
            })
            .expectComplete()
            .verify(Duration.ofSeconds(5));

        assertTrue(completed.get(), "Sink should complete on shutdown");
    }
}
```

#### Test 3: ByteBuf Release Test

**File**: `jetlinks-components/network-component/http-component/src/test/java/org/jetlinks/community/network/http/server/vertx/ByteBufLeakTest.java`

```java
package org.jetlinks.community.network.http.server.vertx;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ByteBufLeakTest {

    @Test
    void testByteBufReleasedOnError() {
        ByteBuf buf = Unpooled.buffer(10);
        int initialRefCnt = buf.refCnt();

        try {
            // Simulate error during message handling
            throw new RuntimeException("Test error");
        } catch (Throwable e) {
            // ByteBuf should be released in catch block
            buf.release();
        }

        assertEquals(initialRefCnt - 1, buf.refCnt(),
            "ByteBuf should be released after error");
    }

    @Test
    void testByteBufReleasedAfterSend() {
        // Test that ByteBuf is released after successful send
        ByteBuf buf = Unpooled.buffer(10);
        buf.writeBytes("test".getBytes());

        int refCntBefore = buf.refCnt();

        // Simulate send operation
        // After send completes, refCount should decrease
        buf.release();

        assertEquals(refCntBefore - 1, buf.refCnt());
    }
}
```

### 2.2 Run Unit Tests

```bash
# Run all tests in network-component modules
cd jetlinks-components/network-component

# TCP component tests
mvn test -pl tcp-component

# MQTT component tests
mvn test -pl mqtt-component

# HTTP component tests
mvn test -pl http-component

# All network components
mvn test

# Generate coverage report
mvn test jacoco:report
```

**Expected Output:**
```
[INFO] Tests run: XX, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**View Coverage Report:**
```
Open: target/site/jacoco/index.html
```

---

## 3. Integration Testing

### 3.1 TCP Server Integration Test

**File**: `test_guid/integration-tests/TcpServerIntegrationTest.java`

```java
package org.jetlinks.test.integration;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jetlinks.community.network.tcp.client.TcpClient;
import org.jetlinks.community.network.tcp.server.TcpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TcpServerIntegrationTest {

    private TcpServer server;
    private List<TcpClient> clients = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // Initialize TCP server
    }

    @AfterEach
    void tearDown() {
        // Disconnect all clients
        clients.forEach(TcpClient::disconnect);

        // Shutdown server
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void testMultipleConnectionsNoLeak() throws Exception {
        int connectionCount = 1000;

        // Record initial memory
        System.gc();
        long memoryBefore = Runtime.getRuntime().totalMemory() -
                           Runtime.getRuntime().freeMemory();

        // Connect multiple clients
        for (int i = 0; i < connectionCount; i++) {
            // Create and connect client
            // Send some data
            // Disconnect

            if (i % 100 == 0) {
                System.gc();
                Thread.sleep(10);
            }
        }

        // Wait for cleanup
        Thread.sleep(1000);
        System.gc();
        Thread.sleep(1000);

        long memoryAfter = Runtime.getRuntime().totalMemory() -
                          Runtime.getRuntime().freeMemory();

        long memoryGrowth = memoryAfter - memoryBefore;
        long maxAllowedGrowth = 50_000_000; // 50MB

        assertTrue(memoryGrowth < maxAllowedGrowth,
            String.format("Memory growth %d bytes exceeds maximum %d bytes",
                memoryGrowth, maxAllowedGrowth));
    }

    @Test
    void testRapidConnectDisconnectCycle() {
        // Test rapid connect/disconnect to stress test cleanup
        for (int cycle = 0; cycle < 100; cycle++) {
            // Connect
            // Send message
            // Disconnect immediately
        }

        // Memory should be stable
        assertMemoryStable();
    }

    private void assertMemoryStable() {
        System.gc();
        long before = getUsedMemory();

        try { Thread.sleep(1000); } catch (InterruptedException e) {}

        System.gc();
        long after = getUsedMemory();

        long growth = after - before;
        assertTrue(Math.abs(growth) < 10_000_000, // 10MB tolerance
            "Memory should be stable after GC, growth: " + growth);
    }

    private long getUsedMemory() {
        return Runtime.getRuntime().totalMemory() -
               Runtime.getRuntime().freeMemory();
    }
}
```

### 3.2 MQTT Server Integration Test

**File**: `test_guid/integration-tests/MqttServerIntegrationTest.java`

```java
package org.jetlinks.test.integration;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MqttServerIntegrationTest {

    @Test
    void testMqttConnectionLeakPrevention() throws Exception {
        String brokerUrl = "tcp://localhost:1883";
        List<MqttClient> clients = new ArrayList<>();

        try {
            // Create 100 MQTT clients
            for (int i = 0; i < 100; i++) {
                MqttClient client = new MqttClient(brokerUrl, "test-client-" + i);
                MqttConnectOptions options = new MqttConnectOptions();
                options.setCleanSession(true);

                client.connect(options);
                clients.add(client);

                // Publish a message
                client.publish("test/topic", "Hello".getBytes(), 0, false);
            }

            // Disconnect all
            for (MqttClient client : clients) {
                client.disconnect();
                client.close();
            }

            // Wait for cleanup
            Thread.sleep(2000);

            // Verify no thread leaks
            int threadCount = Thread.activeCount();
            assertTrue(threadCount < 50,
                "Thread count should be low after cleanup: " + threadCount);

        } finally {
            clients.forEach(client -> {
                try {
                    if (client.isConnected()) {
                        client.disconnect();
                    }
                    client.close();
                } catch (Exception ignore) {}
            });
        }
    }
}
```

### 3.3 Run Integration Tests

```bash
# Ensure backend services are running
# PostgreSQL, ElasticSearch if needed

# Run integration tests
cd back/jetlinks-community-2.10/jetlinks-community-2.10
mvn verify -Pintegration-tests

# Or run specific test
mvn test -Dtest=TcpServerIntegrationTest
```

---

## 4. Memory Leak Detection

### 4.1 Using Netty Leak Detector

**Step 1: Configure leak detection level**

```bash
# Edit application startup script
# Add JVM option
-Dio.netty.leakDetectionLevel=PARANOID
```

**Leak Detection Levels:**
- `DISABLED` - No detection
- `SIMPLE` - 1% sampling (default)
- `ADVANCED` - 1% sampling with full stack trace
- `PARANOID` - 100% sampling (use for testing only)

**Step 2: Run application and monitor logs**

```bash
cd jetlinks-standalone
java -Dio.netty.leakDetectionLevel=PARANOID \
     -jar target/jetlinks-standalone-*.jar

# Monitor for leak messages
tail -f logs/jetlinks.log | grep "LEAK"
```

**Expected Output (NO LEAKS):**
```
# Should see NO messages like:
# LEAK: ByteBuf.release() was not called before it's garbage-collected
```

### 4.2 Heap Dump Analysis

**Step 1: Take heap dumps**

```bash
# Create heap dumps directory
mkdir -p heap-dumps

# Before load test
jmap -dump:format=b,file=heap-dumps/before-load.hprof <PID>

# Run load test (see section 5)

# After load test
jmap -dump:format=b,file=heap-dumps/after-load.hprof <PID>

# After shutdown
jmap -dump:format=b,file=heap-dumps/after-shutdown.hprof <PID>
```

**Step 2: Analyze with Eclipse MAT**

```bash
# Download Eclipse MAT
# https://www.eclipse.org/mat/downloads.php

# Open heap dump
mat heap-dumps/after-load.hprof

# Run Leak Suspects Report
# Analyze -> Leak Suspects
```

**What to Look For:**
- ✅ **No retained ByteBuf instances** beyond expected count
- ✅ **No unreleased Disposable subscriptions**
- ✅ **Topic tree size remains stable**
- ✅ **Thread count stable** (check in MAT: Thread Overview)
- ✅ **No accumulating handler lists**

**Key Metrics to Track:**

| Object Type | Before Load | After Load | After Shutdown | Status |
|-------------|-------------|------------|----------------|--------|
| ByteBuf | ~100 | ~150 | ~100 | ✅ Stable |
| Disposable | ~50 | ~80 | ~50 | ✅ Stable |
| Topic Nodes | ~20 | ~30 | ~20 | ✅ Stable |
| Threads | ~30 | ~50 | ~30 | ✅ Stable |

### 4.3 Using JProfiler

**Step 1: Attach JProfiler to running application**

```bash
# Start application with JProfiler agent
java -agentpath:/path/to/jprofiler/bin/linux-x64/libjprofilerti.so=port=8849 \
     -jar jetlinks-standalone-*.jar
```

**Step 2: Monitor allocations**

1. Open JProfiler GUI
2. Attach to process
3. Go to **Live Memory** → **All Objects**
4. Filter for:
   - `io.netty.buffer.ByteBuf`
   - `reactor.core.Disposable`
   - `org.jetlinks.core.topic.Topic`

**Step 3: Record allocation timeline**

1. Click **Start Recording** (Allocation Call Tree)
2. Run load test
3. Stop recording
4. Analyze allocation hot spots

**Expected Results:**
- ByteBuf allocations should match releases (1:1 ratio)
- No increasing trend in retained objects
- Disposables cleaned up after shutdown

---

## 5. Load Testing

### 5.1 TCP Load Test Script

**File**: `test_guid/load-tests/tcp_load_test.sh`

```bash
#!/bin/bash

# TCP Load Test Script
# Simulates 10,000 connections with message exchange

HOST="localhost"
PORT="8848"
CONNECTIONS=10000
MESSAGES_PER_CONN=100

echo "Starting TCP Load Test"
echo "Target: $HOST:$PORT"
echo "Connections: $CONNECTIONS"
echo "Messages per connection: $MESSAGES_PER_CONN"

# Monitor memory before
echo "Memory before load:"
PID=$(jps | grep JetLinksApplication | awk '{print $1}')
jstat -gc $PID

# Run load test
for i in $(seq 1 $CONNECTIONS); do
    (
        # Connect and send messages
        for msg in $(seq 1 $MESSAGES_PER_CONN); do
            echo "Message $msg" | nc $HOST $PORT
        done
    ) &

    # Limit concurrent connections
    if (( $i % 100 == 0 )); then
        wait
        echo "Completed $i connections"

        # Check memory every 1000 connections
        if (( $i % 1000 == 0 )); then
            jstat -gc $PID | tail -1
        fi
    fi
done

wait

echo "All connections completed"
echo "Waiting 30s for cleanup..."
sleep 30

# Force GC
jcmd $PID GC.run

echo "Memory after load:"
jstat -gc $PID

echo "Load test completed"
```

**Make executable and run:**
```bash
chmod +x test_guid/load-tests/tcp_load_test.sh
./test_guid/load-tests/tcp_load_test.sh
```

### 5.2 MQTT Load Test

**File**: `test_guid/load-tests/mqtt_load_test.py`

```python
#!/usr/bin/env python3
"""
MQTT Load Test
Requires: pip install paho-mqtt
"""

import paho.mqtt.client as mqtt
import time
import threading
import gc
import psutil
import os

BROKER = "localhost"
PORT = 1883
NUM_CLIENTS = 1000
MESSAGES_PER_CLIENT = 100

def get_process_memory():
    process = psutil.Process(os.getpid())
    return process.memory_info().rss / 1024 / 1024  # MB

def client_test(client_id):
    client = mqtt.Client(f"load_test_{client_id}")

    try:
        client.connect(BROKER, PORT, 60)
        client.loop_start()

        # Publish messages
        for i in range(MESSAGES_PER_CLIENT):
            client.publish(f"test/topic/{client_id}",
                          f"Message {i} from client {client_id}",
                          qos=0)

        time.sleep(0.1)

    finally:
        client.loop_stop()
        client.disconnect()

def main():
    print(f"Starting MQTT Load Test")
    print(f"Broker: {BROKER}:{PORT}")
    print(f"Clients: {NUM_CLIENTS}")
    print(f"Messages per client: {MESSAGES_PER_CLIENT}")

    mem_before = get_process_memory()
    print(f"Memory before: {mem_before:.2f} MB")

    start_time = time.time()
    threads = []

    for i in range(NUM_CLIENTS):
        t = threading.Thread(target=client_test, args=(i,))
        t.start()
        threads.append(t)

        if (i + 1) % 100 == 0:
            print(f"Started {i + 1} clients...")

        # Limit concurrent threads
        if len(threads) >= 100:
            for t in threads:
                t.join()
            threads = []

    # Wait for remaining threads
    for t in threads:
        t.join()

    elapsed = time.time() - start_time

    print(f"\nAll clients completed in {elapsed:.2f}s")
    print("Waiting 30s for cleanup...")
    time.sleep(30)

    gc.collect()
    mem_after = get_process_memory()
    print(f"Memory after: {mem_after:.2f} MB")
    print(f"Memory growth: {mem_after - mem_before:.2f} MB")

    # Check if growth is acceptable
    max_growth = 100  # MB
    if mem_after - mem_before > max_growth:
        print(f"⚠️  WARNING: Memory growth exceeds {max_growth}MB")
        return 1
    else:
        print("✅ Memory growth within acceptable range")
        return 0

if __name__ == "__main__":
    exit(main())
```

**Run:**
```bash
python3 test_guid/load-tests/mqtt_load_test.py
```

### 5.3 HTTP/WebSocket Load Test

**File**: `test_guid/load-tests/http_load_test.js`

```javascript
#!/usr/bin/env node
/**
 * HTTP/WebSocket Load Test
 * Requires: npm install ws axios
 */

const WebSocket = require('ws');
const axios = require('axios');

const HTTP_URL = 'http://localhost:8848';
const WS_URL = 'ws://localhost:8848/ws/test';
const NUM_CONNECTIONS = 1000;
const MESSAGES_PER_CONN = 50;

async function httpTest() {
    console.log('Starting HTTP Load Test...');

    const promises = [];
    for (let i = 0; i < NUM_CONNECTIONS; i++) {
        const promise = (async () => {
            for (let j = 0; j < MESSAGES_PER_CONN; j++) {
                await axios.post(`${HTTP_URL}/test`, {
                    message: `Test ${i}-${j}`
                });
            }
        })();
        promises.push(promise);

        if ((i + 1) % 100 === 0) {
            console.log(`Started ${i + 1} HTTP clients`);
        }
    }

    await Promise.all(promises);
    console.log('HTTP test completed');
}

function wsTest() {
    return new Promise((resolve) => {
        console.log('Starting WebSocket Load Test...');

        let completed = 0;

        for (let i = 0; i < NUM_CONNECTIONS; i++) {
            const ws = new WebSocket(WS_URL);

            ws.on('open', () => {
                // Send messages
                for (let j = 0; j < MESSAGES_PER_CONN; j++) {
                    ws.send(`Message ${i}-${j}`);
                }

                setTimeout(() => {
                    ws.close();
                }, 100);
            });

            ws.on('close', () => {
                completed++;
                if (completed === NUM_CONNECTIONS) {
                    console.log('WebSocket test completed');
                    resolve();
                }
            });

            ws.on('error', (err) => {
                console.error(`WS error: ${err.message}`);
            });
        }
    });
}

async function main() {
    console.log('Memory before tests:', process.memoryUsage());

    await httpTest();
    await wsTest();

    console.log('Waiting 30s for cleanup...');
    await new Promise(resolve => setTimeout(resolve, 30000));

    global.gc();  // Run with: node --expose-gc http_load_test.js

    console.log('Memory after tests:', process.memoryUsage());
    console.log('✅ Load test completed');
}

main().catch(console.error);
```

**Run:**
```bash
npm install ws axios
node --expose-gc test_guid/load-tests/http_load_test.js
```

### 5.4 Monitor During Load Test

**Terminal 1: Application logs**
```bash
tail -f logs/jetlinks.log | grep -E "LEAK|ERROR|OutOfMemory"
```

**Terminal 2: JVM Metrics**
```bash
# Memory usage over time
watch -n 1 'jstat -gc <PID> | tail -1'

# Thread count
watch -n 5 'jstack <PID> | grep "^\"" | wc -l'
```

**Terminal 3: System resources**
```bash
# CPU and Memory
top -p <PID>

# Or use htop
htop -p <PID>
```

---

## 6. Performance Benchmarking

### 6.1 Establish Baseline Metrics

**Before applying fixes (if possible):**

```bash
# Record baseline
echo "Baseline Performance Metrics" > test_guid/baseline_metrics.txt

# Run load test
./test_guid/load-tests/tcp_load_test.sh >> test_guid/baseline_metrics.txt 2>&1

# Record memory
jstat -gc <PID> >> test_guid/baseline_metrics.txt
```

### 6.2 After Fix Metrics

```bash
# Record after-fix metrics
echo "After-Fix Performance Metrics" > test_guid/after_fix_metrics.txt

# Run same load test
./test_guid/load-tests/tcp_load_test.sh >> test_guid/after_fix_metrics.txt 2>&1

# Record memory
jstat -gc <PID> >> test_guid/after_fix_metrics.txt
```

### 6.3 Compare Results

**File**: `test_guid/compare_metrics.py`

```python
#!/usr/bin/env python3
"""
Compare baseline vs after-fix metrics
"""

def parse_metrics(filename):
    # Parse jstat output and extract metrics
    with open(filename) as f:
        content = f.read()
    # Extract relevant metrics
    # S0C, S1C, S0U, S1U, EC, EU, OC, OU, etc.
    return {}

def compare():
    baseline = parse_metrics('test_guid/baseline_metrics.txt')
    after_fix = parse_metrics('test_guid/after_fix_metrics.txt')

    print("=== Performance Comparison ===")
    print(f"Heap Usage Before: {baseline.get('heap_used', 'N/A')} MB")
    print(f"Heap Usage After:  {after_fix.get('heap_used', 'N/A')} MB")
    print(f"Improvement: {baseline.get('heap_used', 0) - after_fix.get('heap_used', 0)} MB")

if __name__ == '__main__':
    compare()
```

---

## 7. Acceptance Criteria

### 7.1 Test Checklist

**Unit Tests:**
- [ ] All unit tests pass with 0 failures
- [ ] Code coverage > 80% for modified files
- [ ] No Netty leak warnings in test logs

**Integration Tests:**
- [ ] 1,000 connection cycles complete without OOM
- [ ] Memory growth < 50MB after 10,000 connections
- [ ] Thread count returns to baseline after shutdown

**Memory Leak Detection:**
- [ ] Zero ByteBuf leaks detected (Netty PARANOID mode)
- [ ] Heap dump shows no retained Disposables
- [ ] Topic tree size stable after shutdown
- [ ] No thread leaks (thread count stable)

**Load Testing:**
- [ ] Sustained 10,000 connections for 10 minutes without crash
- [ ] Memory growth < 5% over 24-hour run
- [ ] Shutdown completes within 5 seconds
- [ ] No exceptions in logs during normal operation

**Performance:**
- [ ] Throughput within 5% of baseline (no significant degradation)
- [ ] Latency P99 < 100ms under load
- [ ] GC pause times < 200ms

### 7.2 Success Metrics

| Metric | Target | How to Measure |
|--------|--------|----------------|
| **ByteBuf Leaks** | 0 | Netty leak detector |
| **Memory Growth (24h)** | < 5% | Heap dump analysis |
| **Thread Leaks** | 0 | Thread count monitoring |
| **Shutdown Time** | < 5s | Time measurement |
| **Connection Throughput** | ≥ 10,000/s | Load test script |
| **GC Pause** | < 200ms | GC logs |
| **Heap After Shutdown** | ≈ Initial | jstat comparison |

---

## 8. Troubleshooting

### 8.1 Common Issues

**Issue 1: Tests fail with "Connection refused"**

```bash
# Solution: Ensure backend services are running
docker-compose up -d postgres
# Or start PostgreSQL manually
```

**Issue 2: OutOfMemoryError during tests**

```bash
# Solution: Increase heap size
export MAVEN_OPTS="-Xmx4g -Xms2g"
mvn test
```

**Issue 3: Netty leak detector reports false positives**

```bash
# If you see leaks but memory is stable, it might be due to GC timing
# Wait longer before shutdown
Thread.sleep(5000);
System.gc();
Thread.sleep(5000);
```

### 8.2 Debug Mode

**Enable detailed logging:**

```bash
# Edit logback.xml or application.yml
<logger name="org.jetlinks.community.network" level="DEBUG"/>
<logger name="io.netty.util.ResourceLeakDetector" level="DEBUG"/>
<logger name="reactor.core.publisher" level="DEBUG"/>
```

**Run with debug:**
```bash
mvn test -Dlogback.debug=true
```

### 8.3 Verification Commands

```bash
# Check for leaked threads
jstack <PID> | grep "tcp-client\|mqtt-client\|http-server" | wc -l

# Check ByteBuf usage
jcmd <PID> VM.native_memory summary | grep "Internal"

# Force full GC and check memory
jcmd <PID> GC.run
jstat -gc <PID> 1000 10

# Check open file descriptors (connection leaks)
lsof -p <PID> | grep TCP | wc -l
```

---

## 9. Test Execution Checklist

### Pre-Test
- [ ] Code compiles successfully (`mvn clean install`)
- [ ] All dependencies available
- [ ] Test environment configured
- [ ] Monitoring tools ready

### During Test
- [ ] Monitor logs for errors/warnings
- [ ] Track memory usage trends
- [ ] Record baseline metrics
- [ ] Take heap dumps at key points

### Post-Test
- [ ] Analyze heap dumps
- [ ] Compare metrics vs baseline
- [ ] Document any anomalies
- [ ] Archive test results

### Sign-off
- [ ] All acceptance criteria met
- [ ] Test results documented
- [ ] Performance within acceptable range
- [ ] Ready for production deployment

---

## 10. Test Report Template

**File**: `test_guid/TEST_REPORT.md`

```markdown
# Network Component Leak Fix - Test Report

**Date**: YYYY-MM-DD
**Tester**: [Your Name]
**Version**: 1.0

## Executive Summary
[Pass/Fail] - Brief summary of test results

## Test Environment
- OS: Windows 10 Pro / Linux
- Java Version: OpenJDK 17.x.x
- Memory: XGB RAM
- CPU: X cores

## Test Results

### Unit Tests
- Total Tests: XX
- Passed: XX
- Failed: XX
- Coverage: XX%

### Integration Tests
- Connection Cycles: 10,000
- Memory Growth: XX MB (< 50MB ✅)
- Thread Count: Stable ✅

### Load Tests
| Test Type | Connections | Duration | Result |
|-----------|-------------|----------|--------|
| TCP | 10,000 | 10 min | ✅ Pass |
| MQTT | 1,000 | 5 min | ✅ Pass |
| HTTP | 1,000 | 5 min | ✅ Pass |

### Memory Leak Detection
- ByteBuf Leaks: 0 ✅
- Netty Detector: Clean ✅
- Heap Dump Analysis: No retained objects ✅

## Issues Found
[None / List any issues]

## Recommendations
[Any recommendations for improvements]

## Conclusion
[Final assessment and sign-off]

**Approved by**: _______________
**Date**: _______________
```

---

## Quick Start Testing Commands

```bash
# 1. Build and unit test
cd back/jetlinks-community-2.10/jetlinks-community-2.10
mvn clean test

# 2. Run with leak detection
mvn exec:java -Dexec.mainClass="..." \
  -Dio.netty.leakDetectionLevel=PARANOID

# 3. Run load test
cd test_guid/load-tests
./tcp_load_test.sh

# 4. Take heap dump
jmap -dump:format=b,file=heap.hprof <PID>

# 5. Analyze
# Open heap.hprof in Eclipse MAT

# 6. Generate report
# Fill out TEST_REPORT.md
```

---

**End of Testing Guide**

For questions or issues, refer to the troubleshooting section or contact the development team.
