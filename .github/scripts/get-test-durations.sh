#!/bin/bash
# Usage: get-test-durations.sh <dir>
# Downloads into <dir> the test durations (test-durations-<configuration>.txt, see
# TestDurationsListener) recorded by the latest successful Build run of master, for the test shards
# to balance with. Without any, it writes empty files: every test still runs, but the shards are
# balanced by estimates of 1 s per test method. Needs GH_TOKEN and GITHUB_REPOSITORY.
set -u
DIR=$1
mkdir -p "$DIR"
RUNS=$(gh run list --repo "$GITHUB_REPOSITORY" --workflow build.yml --branch master --event push \
    --status success --limit 10 --json databaseId --jq '.[].databaseId') || RUNS=""
for RUN in $RUNS; do
    if gh run download "$RUN" --repo "$GITHUB_REPOSITORY" --name test-durations --dir "$DIR" 2>/dev/null; then
        echo "Using the test durations recorded by run $RUN"
        break
    fi
done
for CONFIG in multi-threaded single-threaded; do
    FILE="$DIR/test-durations-$CONFIG.txt"
    touch "$FILE"
    echo "$CONFIG: $(grep -c -v '^#' "$FILE") recorded test durations"
done
