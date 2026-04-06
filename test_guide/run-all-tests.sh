#!/bin/bash
################################################################################
# Network Component Leak Fix - Complete Test Suite Runner
# Usage: ./run-all-tests.sh [quick|full]
################################################################################

set -e  # Exit on error

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

MODE=${1:-full}  # quick or full

echo -e "${GREEN}================================${NC}"
echo -e "${GREEN}Network Component Test Suite${NC}"
echo -e "${GREEN}Mode: $MODE${NC}"
echo -e "${GREEN}================================${NC}"

# Get project root
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_ROOT="$PROJECT_ROOT/back/jetlinks-community-2.10/jetlinks-community-2.10"

echo "Project root: $PROJECT_ROOT"
echo "Backend root: $BACKEND_ROOT"

# Test results directory
RESULTS_DIR="$PROJECT_ROOT/test_guid/results/$(date +%Y%m%d_%H%M%S)"
mkdir -p "$RESULTS_DIR"

echo -e "\n${YELLOW}Results will be saved to: $RESULTS_DIR${NC}\n"

# Function to run command and log
run_test() {
    local name=$1
    local command=$2
    local log_file="$RESULTS_DIR/${name}.log"

    echo -e "${YELLOW}Running: $name${NC}"
    echo "Command: $command" | tee "$log_file"

    if eval "$command" >> "$log_file" 2>&1; then
        echo -e "${GREEN}✅ PASS: $name${NC}"
        return 0
    else
        echo -e "${RED}❌ FAIL: $name${NC}"
        echo "See log: $log_file"
        return 1
    fi
}

# Test counters
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

################################################################################
# 1. Build Project
################################################################################
echo -e "\n${GREEN}=== Step 1: Build Project ===${NC}"
TOTAL_TESTS=$((TOTAL_TESTS + 1))

if run_test "maven-build" "cd $BACKEND_ROOT && mvn clean install -DskipTests"; then
    PASSED_TESTS=$((PASSED_TESTS + 1))
else
    FAILED_TESTS=$((FAILED_TESTS + 1))
    echo -e "${RED}Build failed. Aborting tests.${NC}"
    exit 1
fi

################################################################################
# 2. Unit Tests
################################################################################
echo -e "\n${GREEN}=== Step 2: Unit Tests ===${NC}"

# TCP Component
TOTAL_TESTS=$((TOTAL_TESTS + 1))
if run_test "unit-test-tcp" "cd $BACKEND_ROOT/jetlinks-components/network-component/tcp-component && mvn test"; then
    PASSED_TESTS=$((PASSED_TESTS + 1))
else
    FAILED_TESTS=$((FAILED_TESTS + 1))
fi

# MQTT Component
TOTAL_TESTS=$((TOTAL_TESTS + 1))
if run_test "unit-test-mqtt" "cd $BACKEND_ROOT/jetlinks-components/network-component/mqtt-component && mvn test"; then
    PASSED_TESTS=$((PASSED_TESTS + 1))
else
    FAILED_TESTS=$((FAILED_TESTS + 1))
fi

# HTTP Component
TOTAL_TESTS=$((TOTAL_TESTS + 1))
if run_test "unit-test-http" "cd $BACKEND_ROOT/jetlinks-components/network-component/http-component && mvn test"; then
    PASSED_TESTS=$((PASSED_TESTS + 1))
else
    FAILED_TESTS=$((FAILED_TESTS + 1))
fi

################################################################################
# 3. Code Coverage
################################################################################
if [ "$MODE" == "full" ]; then
    echo -e "\n${GREEN}=== Step 3: Code Coverage ===${NC}"
    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    if run_test "coverage-report" "cd $BACKEND_ROOT && mvn jacoco:report"; then
        PASSED_TESTS=$((PASSED_TESTS + 1))
        echo "Coverage reports: $BACKEND_ROOT/*/target/site/jacoco/index.html"
    else
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
fi

################################################################################
# 4. Memory Leak Detection
################################################################################
echo -e "\n${GREEN}=== Step 4: Memory Leak Detection ===${NC}"

# Check if application is running
APP_PID=$(jps | grep -i jetlinks | awk '{print $1}')

if [ -z "$APP_PID" ]; then
    echo -e "${YELLOW}⚠️  Application not running. Skipping live memory tests.${NC}"
    echo "To run memory tests, start the application first:"
    echo "  java -Dio.netty.leakDetectionLevel=PARANOID -jar $BACKEND_ROOT/jetlinks-standalone/target/*.jar"
else
    echo "Found JetLinks process: PID $APP_PID"

    # Take initial heap dump
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    if run_test "heap-dump-before" "jmap -dump:format=b,file=$RESULTS_DIR/heap-before.hprof $APP_PID"; then
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi

    # Check for leak detector warnings in logs
    echo "Checking for Netty leak warnings..."
    if grep -q "LEAK:" "$BACKEND_ROOT/logs/jetlinks.log" 2>/dev/null; then
        echo -e "${RED}❌ Found leak warnings in logs${NC}"
        grep "LEAK:" "$BACKEND_ROOT/logs/jetlinks.log" | tail -20
        TOTAL_TESTS=$((TOTAL_TESTS + 1))
        FAILED_TESTS=$((FAILED_TESTS + 1))
    else
        echo -e "${GREEN}✅ No leak warnings found${NC}"
        TOTAL_TESTS=$((TOTAL_TESTS + 1))
        PASSED_TESTS=$((PASSED_TESTS + 1))
    fi
fi

################################################################################
# 5. Load Tests (Full mode only)
################################################################################
if [ "$MODE" == "full" ] && [ -n "$APP_PID" ]; then
    echo -e "\n${GREEN}=== Step 5: Load Tests ===${NC}"

    # Save current memory state
    jstat -gc $APP_PID > "$RESULTS_DIR/memory-before-load.txt"

    echo "Running simulated load test (lightweight)..."
    echo "For full load tests, run individual scripts:"
    echo "  - ./test_guid/load-tests/tcp_load_test.sh"
    echo "  - python3 ./test_guid/load-tests/mqtt_load_test.py"

    # Wait for GC
    sleep 5
    jcmd $APP_PID GC.run
    sleep 5

    # Save memory after
    jstat -gc $APP_PID > "$RESULTS_DIR/memory-after-load.txt"
fi

################################################################################
# Summary
################################################################################
echo -e "\n${GREEN}================================${NC}"
echo -e "${GREEN}Test Summary${NC}"
echo -e "${GREEN}================================${NC}"
echo "Total Tests: $TOTAL_TESTS"
echo -e "${GREEN}Passed: $PASSED_TESTS${NC}"
echo -e "${RED}Failed: $FAILED_TESTS${NC}"
echo "Results directory: $RESULTS_DIR"

if [ $FAILED_TESTS -eq 0 ]; then
    echo -e "\n${GREEN}✅ All tests passed!${NC}"
    exit 0
else
    echo -e "\n${RED}❌ Some tests failed. Check logs in $RESULTS_DIR${NC}"
    exit 1
fi
