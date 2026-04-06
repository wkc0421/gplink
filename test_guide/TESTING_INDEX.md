# Network Component Leak Fix - Testing Documentation Index

This directory contains all testing materials for validating the network component resource leak fixes in JetLinks IoT Platform.

## 📚 Documentation Files

### Getting Started
1. **[TESTING_QUICK_START.md](./TESTING_QUICK_START.md)** ⭐ START HERE
   - 5-minute quick test guide
   - Step-by-step instructions
   - Quick diagnosis tips
   - Estimated time requirements

2. **[README.md](./README.md)**
   - Complete testing suite overview
   - Directory structure explanation
   - Tool descriptions
   - CI/CD integration examples

### Comprehensive Guide
3. **[network-component-leak-fix-test-guide.md](./network-component-leak-fix-test-guide.md)**
   - Detailed testing procedures (60+ pages)
   - Unit test examples with code
   - Integration test templates
   - Memory leak detection methods
   - Load testing procedures
   - Performance benchmarking
   - Troubleshooting guide

### Test Reporting
4. **[TEST_REPORT_TEMPLATE.md](./TEST_REPORT_TEMPLATE.md)**
   - Standardized test report format
   - All acceptance criteria checklists
   - Results recording tables
   - Sign-off section

## 🛠️ Testing Scripts

### Automated Test Runner
- **[run-all-tests.sh](./run-all-tests.sh)**
  - Runs complete test suite
  - Modes: `quick` or `full`
  - Auto-generates result reports
  - Exit code 0=success, 1=failure

### Monitoring Tools
- **[memory-monitor.sh](./memory-monitor.sh)**
  - Real-time memory/thread monitoring
  - CSV output with timestamps
  - Auto-generates graphs (if gnuplot installed)
  - Alerts on high usage

- **[leak-detector-check.sh](./leak-detector-check.sh)**
  - Analyzes Netty leak detector logs
  - Categorizes leak types
  - Shows leak locations
  - Exit code indicates leak presence

### Load Test Scripts
Located in `load-tests/` subdirectory (to be created):
- `tcp_load_test.sh` - TCP connection load test
- `mqtt_load_test.py` - MQTT client load test  
- `http_load_test.js` - HTTP/WebSocket load test

## 📖 Reading Guide

### For Quick Testing (20 minutes)
```
1. Read: TESTING_QUICK_START.md (5 min)
2. Run: ./run-all-tests.sh quick (15 min)
3. Fill: TEST_REPORT_TEMPLATE.md (optional)
```

### For Comprehensive Testing (2-3 hours)
```
1. Read: README.md (15 min)
2. Read: network-component-leak-fix-test-guide.md sections 1-5 (30 min)
3. Run: ./run-all-tests.sh full (60 min)
4. Run: Load tests individually (30 min)
5. Analyze: Heap dumps with Eclipse MAT (30 min)
6. Fill: Complete TEST_REPORT_TEMPLATE.md (15 min)
```

### For Production Validation (24+ hours)
```
1. All comprehensive testing steps
2. Run: 24-hour stability test
3. Run: Performance benchmarking
4. Generate: Complete test report with sign-off
```

## 🎯 Test Categories

### 1. Build & Compilation
- Maven build verification
- Dependency resolution
- Compilation error checking

**Script**: `run-all-tests.sh` Step 1

### 2. Unit Testing
- TCP component tests
- MQTT component tests  
- HTTP component tests
- Code coverage analysis

**Script**: `run-all-tests.sh` Step 2

### 3. Memory Leak Detection
- Netty leak detector analysis
- Heap dump comparison
- Retained object verification
- Thread leak checking

**Scripts**: 
- `leak-detector-check.sh`
- Manual heap dump with jmap

### 4. Integration Testing
- Multi-connection scenarios
- Rapid connect/disconnect cycles
- Error path validation

**Guide**: Section 3 of comprehensive guide

### 5. Load Testing
- TCP: 10,000 connections
- MQTT: 1,000 clients
- HTTP/WS: 1,000 requests
- Sustained load over time

**Scripts**: `load-tests/*.sh|py|js`

### 6. Stability Testing
- 24-hour continuous run
- Memory trend analysis
- GC performance monitoring
- Crash detection

**Script**: `memory-monitor.sh` (long duration)

### 7. Performance Benchmarking
- Baseline comparison
- Throughput measurement
- Latency profiling
- Resource utilization

**Guide**: Section 6 of comprehensive guide

## ✅ Acceptance Criteria Quick Reference

**Must Pass for Deployment:**

- [ ] Zero Netty LEAK warnings (PARANOID mode)
- [ ] Zero ByteBuf leaks in heap dumps
- [ ] Zero retained Disposables after shutdown
- [ ] Memory growth < 5% over 24 hours
- [ ] Thread count stable (±10 threads)
- [ ] Shutdown completes in < 5 seconds
- [ ] All unit tests pass (0 failures)
- [ ] Load tests complete without crashes
- [ ] Performance within 5% of baseline

**Detailed criteria**: See TEST_REPORT_TEMPLATE.md

## 📊 Expected Results

### Before Fix (Baseline Issues)
- ❌ ByteBuf leaks: ~50 per 1000 connections
- ❌ Memory growth: ~15% over 24 hours
- ❌ Thread leaks: +2-3 threads per 1000 disconnects
- ❌ Shutdown time: 30-60 seconds

### After Fix (Target Results)
- ✅ ByteBuf leaks: 0
- ✅ Memory growth: < 5% over 24 hours
- ✅ Thread leaks: 0 (stable count)
- ✅ Shutdown time: < 5 seconds

## 🔧 Prerequisites

**Required Software:**
- Java 17+
- Maven 3.8+
- PostgreSQL 12+ (for integration tests)

**Optional Tools:**
- Eclipse MAT (heap dump analysis)
- JProfiler or VisualVM (profiling)
- gnuplot (memory graphs)
- Python 3.7+ (MQTT load test)
- Node.js 14+ (HTTP load test)

**Installation Check:**
```bash
java -version    # Should show 17.x.x
mvn -version     # Should show 3.8.x+
psql --version   # Should show 12.x+
```

## 📁 Directory Structure

```
test_guid/
├── TESTING_INDEX.md                          # This file
├── TESTING_QUICK_START.md                    # Quick start guide
├── README.md                                 # Complete overview
├── network-component-leak-fix-test-guide.md  # Comprehensive guide
├── TEST_REPORT_TEMPLATE.md                   # Test report template
│
├── run-all-tests.sh                          # Main test runner
├── memory-monitor.sh                         # Memory monitoring
├── leak-detector-check.sh                    # Leak detection
│
├── load-tests/                               # Load test scripts (TBD)
│   ├── tcp_load_test.sh
│   ├── mqtt_load_test.py
│   └── http_load_test.js
│
└── results/                                  # Auto-generated results
    └── YYYYMMDD_HHMMSS/
        ├── *.log
        ├── *.hprof
        └── *.txt
```

## 🚀 Quick Commands

```bash
# Quick test (20 min)
./run-all-tests.sh quick

# Full test (60 min)
./run-all-tests.sh full

# Monitor memory (continuous)
./memory-monitor.sh 5 60

# Check for leaks
./leak-detector-check.sh

# Individual load test
cd load-tests && ./tcp_load_test.sh
```

## 📞 Support

**Issues or Questions?**
1. Check the troubleshooting sections in guides
2. Review test logs in `results/` directory
3. Contact development team

**Found a Bug?**
- Document in test report
- Include logs and heap dumps
- Note reproduction steps

## 🔄 Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2026-04-05 | Initial testing documentation |

---

**Last Updated**: 2026-04-05  
**Maintained By**: Development Team  
**For**: JetLinks Community Edition 2.10 - Network Component Leak Fixes
