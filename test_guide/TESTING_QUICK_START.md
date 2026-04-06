# Testing Quick Start Guide

## 🎯 5-Minute Quick Test

```bash
# 1. Navigate to test directory
cd test_guid

# 2. Make scripts executable (Linux/Mac only)
chmod +x *.sh

# 3. Run quick test suite
./run-all-tests.sh quick

# 4. Check results
cat results/*/maven-build.log     # Build results
cat results/*/unit-test-*.log      # Unit test results
```

## 📋 Pre-Test Checklist

- [ ] Java 17+ installed (`java -version`)
- [ ] Maven 3.8+ installed (`mvn -version`)
- [ ] Backend project builds successfully
- [ ] PostgreSQL running (for integration tests)
- [ ] At least 4GB RAM available

## 🚀 Step-by-Step Testing

### Option 1: Automated Full Suite (Recommended)

```bash
# Start application first
cd back/jetlinks-community-2.10/jetlinks-community-2.10
java -Dio.netty.leakDetectionLevel=PARANOID \
     -jar jetlinks-standalone/target/*.jar &

# Wait for startup
sleep 30

# Run full test suite
cd ../../../test_guid
./run-all-tests.sh full

# Monitor memory (in new terminal)
./memory-monitor.sh 5 60
```

### Option 2: Individual Tests

```bash
# Unit tests only
cd back/jetlinks-community-2.10/jetlinks-community-2.10
mvn clean test

# Memory leak check
cd ../../test_guid
./leak-detector-check.sh

# Load testing
cd load-tests
./tcp_load_test.sh              # TCP test
python3 mqtt_load_test.py       # MQTT test (requires paho-mqtt)
node http_load_test.js          # HTTP test (requires ws, axios)
```

## 📊 What to Look For

### ✅ Success Indicators

```
# Unit tests
[INFO] Tests run: XX, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

# Leak detection
✅ No memory leaks detected!
Total LEAK warnings: 0

# Memory monitoring
Heap: 512.1/2048.0 MB (25.0%) | Threads: 45
(Stable heap usage, thread count < 100)
```

### ❌ Failure Indicators

```
# Unit tests
[ERROR] Tests run: XX, Failures: 5, Errors: 2
[ERROR] BUILD FAILURE

# Leak detection
❌ Memory leaks detected!
Total LEAK warnings: 15
ByteBuf leaks: 15

# Memory monitoring
⚠️  WARNING: Heap usage > 90%
⚠️  WARNING: Thread count > 200
```

## 📁 Test Results Location

```
test_guid/
└── results/
    └── YYYYMMDD_HHMMSS/          # Timestamped results
        ├── maven-build.log        # Build output
        ├── unit-test-tcp.log      # TCP tests
        ├── unit-test-mqtt.log     # MQTT tests
        ├── unit-test-http.log     # HTTP tests
        ├── heap-before.hprof      # Heap dump before
        ├── memory-before-load.txt # Memory snapshot
        └── memory-after-load.txt  # Memory snapshot
```

## 🔍 Quick Diagnosis

### If Build Fails

```bash
# Check Java version
java -version    # Must be 17+

# Try clean build
cd back/jetlinks-community-2.10/jetlinks-community-2.10
mvn clean install -DskipTests

# Check for missing dependencies
mvn dependency:tree
```

### If Unit Tests Fail

```bash
# Run specific test
mvn test -Dtest=ClassName

# Run with debug
mvn test -X -Dtest=ClassName

# Check logs
cat target/surefire-reports/*.txt
```

### If Leaks Detected

```bash
# Check leak report
./leak-detector-check.sh

# Take heap dump
PID=$(jps | grep JetLinks | awk '{print $1}')
jmap -dump:format=b,file=heap-leak.hprof $PID

# Analyze with Eclipse MAT
# Download: https://www.eclipse.org/mat/
```

### If Memory Grows

```bash
# Force GC
PID=$(jps | grep JetLinks | awk '{print $1}')
jcmd $PID GC.run

# Check memory after GC
jstat -gc $PID

# Monitor continuously
./memory-monitor.sh 10 30
```

## 📝 Filling Out Test Report

```bash
# Copy template
cp TEST_REPORT_TEMPLATE.md TEST_REPORT_$(date +%Y%m%d).md

# Fill in results
# Open TEST_REPORT_YYYYMMDD.md in editor
# Update sections with actual test results

# Key sections to fill:
# - Test Environment Details
# - Unit Test Results (counts, coverage)
# - Memory Leak Detection (leak count)
# - Load Testing (throughput, latency)
# - Acceptance Criteria (all checkboxes)
# - Sign-off (tester, reviewer, approver)
```

## 🎓 Understanding Test Outputs

### Memory Monitor Output

```
[2026-04-05 22:00:00] Heap: 512.1/2048.0 MB (25.0%) | GC: 15 collections (2.34s) | Threads: 45
                      ^     ^      ^        ^         ^                                     ^
                      |     |      |        |         |                                     |
                  Timestamp Used  Max   Percent   GC Stats                            Thread Count
```

- **Heap Used**: Current heap memory in use
- **Heap Max**: Maximum heap size configured
- **Percent**: Usage percentage (⚠️ if > 90%)
- **GC Count**: Total GC collections (minor + major)
- **GC Time**: Total time spent in GC
- **Threads**: Active thread count (⚠️ if > 200)

### Leak Detector Output

```
LEAK: ByteBuf.release() was not called before it's garbage-collected.
Recent access records:
Created at:
    io.netty.buffer.PooledByteBufAllocator.newDirectBuffer(...)
    ^
    |
    Shows where ByteBuf was created (potential leak source)
```

## ⏱️ Estimated Time

| Test Type | Duration | Can Skip? |
|-----------|----------|-----------|
| Build | 5-10 min | No |
| Unit Tests | 5-10 min | No |
| Code Coverage | 2-5 min | Yes (quick mode) |
| Memory Leak Check | 1-2 min | No |
| Load Tests | 10-30 min | Yes (quick mode) |
| 24-Hour Stability | 24 hours | Yes |
| **Quick Mode Total** | **~20 min** | - |
| **Full Mode Total** | **~60 min** | - |

## 🆘 Getting Help

1. **Check README.md** - Detailed information about all test scripts
2. **Check network-component-leak-fix-test-guide.md** - Comprehensive testing guide
3. **Check logs** - Located in `results/YYYYMMDD_HHMMSS/`
4. **Run with debug** - Add `-X` to Maven commands

## 📞 Support Contacts

- Development Team: [contact info]
- DevOps Team: [contact info]
- QA Team: [contact info]

---

**Ready to start?** Run: `./run-all-tests.sh quick`
