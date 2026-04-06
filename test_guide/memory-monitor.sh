#!/bin/bash
################################################################################
# Memory Monitoring Script
# Monitors JetLinks process memory usage over time
# Usage: ./memory-monitor.sh [interval_seconds] [duration_minutes]
################################################################################

INTERVAL=${1:-5}  # Default 5 seconds
DURATION=${2:-60}  # Default 60 minutes

echo "Memory Monitor"
echo "Interval: ${INTERVAL}s"
echo "Duration: ${DURATION}min"

# Find JetLinks process
PID=$(jps | grep -i jetlinks | awk '{print $1}')

if [ -z "$PID" ]; then
    echo "Error: JetLinks process not found"
    echo "Start the application first"
    exit 1
fi

echo "Monitoring PID: $PID"

# Output file
OUTPUT="memory-monitor-$(date +%Y%m%d_%H%M%S).csv"
echo "Logging to: $OUTPUT"

# CSV Header
echo "timestamp,heap_used_mb,heap_max_mb,heap_percent,gc_count,gc_time_ms,thread_count" > "$OUTPUT"

# Calculate iterations
ITERATIONS=$((DURATION * 60 / INTERVAL))

echo "Starting monitoring (Press Ctrl+C to stop)..."
echo ""

for i in $(seq 1 $ITERATIONS); do
    TIMESTAMP=$(date +%Y-%m-%d\ %H:%M:%S)

    # Get memory info
    HEAP_INFO=$(jstat -gc $PID | tail -1)

    # Parse jstat output
    S0U=$(echo $HEAP_INFO | awk '{print $3}')
    S1U=$(echo $HEAP_INFO | awk '{print $4}')
    EU=$(echo $HEAP_INFO | awk '{print $6}')
    OU=$(echo $HEAP_INFO | awk '{print $8}')
    MU=$(echo $HEAP_INFO | awk '{print $10}')

    S0C=$(echo $HEAP_INFO | awk '{print $1}')
    S1C=$(echo $HEAP_INFO | awk '{print $2}')
    EC=$(echo $HEAP_INFO | awk '{print $5}')
    OC=$(echo $HEAP_INFO | awk '{print $7}')
    MC=$(echo $HEAP_INFO | awk '{print $9}')

    # Calculate total used (in KB)
    HEAP_USED=$(echo "$S0U + $S1U + $EU + $OU" | bc)
    HEAP_MAX=$(echo "$S0C + $S1C + $EC + $OC" | bc)

    # Convert to MB
    HEAP_USED_MB=$(echo "scale=2; $HEAP_USED / 1024" | bc)
    HEAP_MAX_MB=$(echo "scale=2; $HEAP_MAX / 1024" | bc)
    HEAP_PERCENT=$(echo "scale=2; ($HEAP_USED / $HEAP_MAX) * 100" | bc)

    # Get GC stats
    GC_COUNT=$(echo $HEAP_INFO | awk '{print $13 + $15}')  # YGC + FGC
    GC_TIME=$(echo $HEAP_INFO | awk '{print $14 + $16}')    # YGCT + FGCT

    # Get thread count
    THREAD_COUNT=$(jstack $PID 2>/dev/null | grep "^\"" | wc -l)

    # Log to CSV
    echo "$TIMESTAMP,$HEAP_USED_MB,$HEAP_MAX_MB,$HEAP_PERCENT,$GC_COUNT,$GC_TIME,$THREAD_COUNT" >> "$OUTPUT"

    # Display current stats
    printf "[%s] Heap: %6.1f/%6.1f MB (%5.1f%%) | GC: %4d collections (%8.2fs) | Threads: %3d\n" \
        "$TIMESTAMP" "$HEAP_USED_MB" "$HEAP_MAX_MB" "$HEAP_PERCENT" "$GC_COUNT" "$GC_TIME" "$THREAD_COUNT"

    # Check for issues
    if (( $(echo "$HEAP_PERCENT > 90" | bc -l) )); then
        echo "⚠️  WARNING: Heap usage > 90%"
    fi

    if [ "$THREAD_COUNT" -gt 200 ]; then
        echo "⚠️  WARNING: Thread count > 200"
    fi

    sleep $INTERVAL
done

echo ""
echo "Monitoring completed"
echo "Results saved to: $OUTPUT"

# Generate simple plot if gnuplot is available
if command -v gnuplot &> /dev/null; then
    echo "Generating memory usage plot..."

    gnuplot <<EOF
set terminal png size 1200,600
set output "${OUTPUT%.csv}.png"
set title "Memory Usage Over Time"
set xlabel "Time"
set ylabel "Memory (MB)"
set xdata time
set timefmt "%Y-%m-%d %H:%M:%S"
set format x "%H:%M"
set grid
set key outside
plot "$OUTPUT" using 1:2 with lines title "Heap Used (MB)", \
     "$OUTPUT" using 1:3 with lines title "Heap Max (MB)"
EOF

    echo "Plot saved to: ${OUTPUT%.csv}.png"
fi
