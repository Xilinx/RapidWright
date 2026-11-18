#!/bin/bash
# Usage: merge-test-durations.sh <shard dir> <dir>
# Merges the test durations recorded by each test shard (<shard dir>/Test Durations <configuration>
# <shard>/testJava.txt, see TestDurationsListener) into <dir>/test-durations-<configuration>.txt.
set -eu
IN=$1; OUT=$2
mkdir -p "$OUT"
for CONFIG in multi-threaded single-threaded; do
    FILE="$OUT/test-durations-$CONFIG.txt"
    echo "# Seconds per test method ($CONFIG), merged from the test shards of one CI run" > "$FILE"
    cat "$IN/Test Durations $CONFIG "*/testJava.txt | grep -v '^#' | sort -k 2 >> "$FILE"
    echo "$CONFIG: $(grep -c -v '^#' "$FILE") recorded test durations"
done
