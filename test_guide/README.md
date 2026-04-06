# Network Component Leak Fix - Testing Suite

This directory contains comprehensive testing tools for validating resource leak fixes in the JetLinks network components.

## Quick Start

### 1. Run All Tests (Quick Mode)

```bash
cd test_guid
chmod +x *.sh
./run-all-tests.sh quick
```

### 2. Run All Tests (Full Mode)

```bash
./run-all-tests.sh full
```

### 3. Monitor Memory Usage

```bash
# Monitor for 60 minutes, check every 5 seconds
./memory-monitor.sh 5 60
```

### 4. Check for Memory Leaks

```bash
./leak-detector-check.sh
```

## Directory Structure

```
test_guid/
├── README.md                                   # This file
├── network-component-leak-fix-test-guide.md   # Comprehensive testing guide
├── run-all-tests.sh                            # Automated test runner
├── memory-monitor.sh                           # Real-time memory monitoring
├── leak-detector-check.sh                      # Leak detection analyzer
│
├── load-tests/                                 # Load testing scripts
│   ├── tcp_load_test.sh                       # TCP load test
│   ├── mqtt_load_test.py                      # MQTT load test
│   └── http_load_test.js                      # HTTP/WebSocket load test
│
├── integration-tests/                          # Integration test cases
│   ├── TcpServerIntegrationTest.java
│   └── MqttServerIntegrationTest.java
│
└── results/                                    # Test results (auto-generated)
    └── YYYYMMDD_HHMMSS/
        ├── *.log                               # Test execution logs
        ├── heap-*.hprof                        # Heap dumps
        └── memory-*.txt                        # Memory snapshots
```

## Test Scripts

### run-all-tests.sh

Automated test suite that runs:
1. Maven build
2. Unit tests for all network components
3. Code coverage reports
4. Memory leak detection
5. Load tests (full mode only)

**Usage:**
```bash
./run-all-tests.sh [quick|full]
```

**Output:**
- Test results in `results/YYYYMMDD_HHMMSS/`
- Summary with pass/fail counts
- Exit code 0 on success, 1 on failure

### memory-monitor.sh

Real-time memory and thread monitoring.

**Usage:**
```bash
./memory-monitor.sh [interval_seconds] [duration_minutes]

# Examples:
./memory-monitor.sh          # Default: 5s interval, 60min duration
./memory-monitor.sh 10 120   # 10s interval, 120min duration
```

**Output:**
- CSV file: `memory-monitor-YYYYMMDD_HHMMSS.csv`
- PNG plot (if gnuplot installed)
- Real-time console output

**Monitors:**
- Heap used/max (MB)
- Heap usage percentage
- GC count and time
- Thread count
- Warnings when heap > 90% or threads > 200

### leak-detector-check.sh

Analyzes application logs for Netty leak detector warnings.

**Usage:**
```bash
./leak-detector-check.sh [log_file]

# Default log location
./leak-detector-check.sh

# Custom log
./leak-detector-check.sh /path/to/jetlinks.log
```

**Output:**
- Total leak count
- Leak type breakdown (ByteBuf, ReferenceCounted, etc.)
- Leak locations with stack traces
- Exit code 0 if no leaks, 1 if leaks found

## Load Tests

### TCP Load Test

Simulates 10,000 TCP connections sending 100 messages each.

```bash
cd load-tests
chmod +x tcp_load_test.sh
./tcp_load_test.sh
```

**Requirements:**
- netcat (`nc`) command available
- JetLinks TCP server running on localhost:8848

### MQTT Load Test

Simulates 1,000 MQTT clients publishing 100 messages each.

```bash
cd load-tests
pip3 install paho-mqtt psutil
python3 mqtt_load_test.py
```

**Requirements:**
- Python 3.7+
- paho-mqtt library
- JetLinks MQTT server running on localhost:1883

### HTTP/WebSocket Load Test

Simulates 1,000 HTTP requests and WebSocket connections.

```bash
cd load-tests
npm install ws axios
node --expose-gc http_load_test.js
```

**Requirements:**
- Node.js 14+
- ws and axios packages
- JetLinks HTTP server running on localhost:8848

## Test Workflow

### Step 1: Preparation

```bash
# Build project
cd ../back/jetlinks-community-2.10/jetlinks-community-2.10
mvn clean install

# Start application with leak detection
java -Dio.netty.leakDetectionLevel=PARANOID \
     -XX:NativeMemoryTracking=detail \
     -XX:+HeapDumpOnOutOfMemoryError \
     -jar jetlinks-standalone/target/*.jar
```

### Step 2: Run Tests

```bash
cd ../../test_guid

# Run automated tests
./run-all-tests.sh full

# Start memory monitoring (in separate terminal)
./memory-monitor.sh 10 30
```

### Step 3: Load Testing

```bash
# Run individual load tests
cd load-tests
./tcp_load_test.sh
python3 mqtt_load_test.py
node --expose-gc http_load_test.js
```

### Step 4: Analysis

```bash
# Check for leaks
./leak-detector-check.sh

# Take heap dump
PID=$(jps | grep JetLinks | awk '{print $1}')
jmap -dump:format=b,file=heap-after-test.hprof $PID

# Analyze with Eclipse MAT
# Download: https://www.eclipse.org/mat/
```

### Step 5: Report

```bash
# Copy results
cp results/latest/*.log ./test-report/
cp memory-monitor-*.csv ./test-report/

# Fill out test report
cp TEST_REPORT_TEMPLATE.md TEST_REPORT_$(date +%Y%m%d).md
# Edit the report with actual results
```

## Acceptance Criteria

✅ **Required for PASS:**

1. **Zero Leaks**
   - No Netty LEAK warnings
   - No ByteBuf leaks in heap dump
   - No retained Disposables after shutdown

2. **Memory Stability**
   - Heap growth < 5% over 24 hours
   - Memory returns to baseline after shutdown
   - GC time < 10% of total time

3. **Thread Safety**
   - Thread count stable (±10 threads)
   - No thread leaks after disconnect
   - Schedulers properly disposed

4. **Performance**
   - Throughput within 5% of baseline
   - Shutdown completes in < 5 seconds
   - P99 latency < 100ms under load

## Troubleshooting

### Tests fail to start

```bash
# Check Java version
java -version  # Should be 17+

# Check Maven version
mvn -version   # Should be 3.8+

# Verify backend builds
cd back/jetlinks-community-2.10/jetlinks-community-2.10
mvn clean install
```

### Application not found

```bash
# Check if running
jps | grep -i jetlinks

# If not, start it
java -jar jetlinks-standalone/target/*.jar
```

### Permission denied on scripts

```bash
chmod +x *.sh
chmod +x load-tests/*.sh
```

### Out of memory during tests

```bash
# Increase heap size
export MAVEN_OPTS="-Xmx4g"
mvn test
```

## CI/CD Integration

### GitHub Actions

```yaml
# .github/workflows/leak-test.yml
name: Memory Leak Tests

on: [push, pull_request]

jobs:
  leak-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - uses: actions/setup-java@v2
        with:
          java-version: '17'
      - name: Run leak tests
        run: |
          cd test_guid
          chmod +x run-all-tests.sh
          ./run-all-tests.sh quick
      - name: Upload results
        uses: actions/upload-artifact@v2
        with:
          name: test-results
          path: test_guid/results/
```

## Additional Resources

- [Comprehensive Test Guide](./network-component-leak-fix-test-guide.md) - Detailed testing procedures
- [JetLinks Documentation](https://jetlinks.org.cn/docs/)
- [Netty Leak Detection](https://netty.io/wiki/reference-counted-objects.html)
- [Reactor Testing](https://projectreactor.io/docs/test/release/reference/)

## Support

For questions or issues:
1. Check the [Troubleshooting](#troubleshooting) section
2. Review test logs in `results/` directory
3. Check Netty leak detector output
4. Contact the development team

---

**Last Updated**: 2026-04-05
**Version**: 1.0
