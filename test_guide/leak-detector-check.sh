#!/bin/bash
################################################################################
# Netty Leak Detector Check
# Analyzes logs for memory leaks reported by Netty
################################################################################

LOG_FILE=${1:-"back/jetlinks-community-2.10/jetlinks-community-2.10/logs/jetlinks.log"}

echo "Netty Leak Detector Analysis"
echo "Log file: $LOG_FILE"
echo "================================"

if [ ! -f "$LOG_FILE" ]; then
    echo "Error: Log file not found: $LOG_FILE"
    exit 1
fi

# Check for LEAK warnings
LEAK_COUNT=$(grep -c "LEAK:" "$LOG_FILE" 2>/dev/null || echo 0)

echo ""
echo "Total LEAK warnings: $LEAK_COUNT"
echo ""

if [ "$LEAK_COUNT" -eq 0 ]; then
    echo "✅ No memory leaks detected!"
    echo ""
    echo "Leak Detection Status:"
    grep -i "leakDetectionLevel" "$LOG_FILE" | tail -5 || echo "  Not found in logs"
    exit 0
else
    echo "❌ Memory leaks detected!"
    echo ""
    echo "Recent leak warnings:"
    echo "================================"
    grep "LEAK:" "$LOG_FILE" | tail -20
    echo "================================"
    echo ""

    # Analyze leak types
    echo "Leak type breakdown:"
    echo "---"

    # ByteBuf leaks
    BYTEBUF_LEAKS=$(grep "LEAK.*ByteBuf" "$LOG_FILE" | wc -l)
    echo "ByteBuf leaks: $BYTEBUF_LEAKS"

    # ReferenceCounted leaks
    REFCOUNT_LEAKS=$(grep "LEAK.*ReferenceCounted" "$LOG_FILE" | wc -l)
    echo "ReferenceCounted leaks: $REFCOUNT_LEAKS"

    echo ""
    echo "Leaked objects by class:"
    grep "LEAK:" "$LOG_FILE" | grep -oP 'class [a-zA-Z0-9.$_]+' | sort | uniq -c | sort -rn

    echo ""
    echo "Leak locations (top 10):"
    grep "LEAK:" "$LOG_FILE" | grep -oP 'at [a-zA-Z0-9.$_]+\.[a-zA-Z0-9_]+' | head -10

    exit 1
fi
