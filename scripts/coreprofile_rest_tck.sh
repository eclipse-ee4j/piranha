#!/usr/bin/env bash
# Runs the Core Profile REST TCK runner with a 30-minute timeout, then parses
# the failsafe XML reports and writes all failing test identifiers to
# failing_test_coreprofile_rest_tck.txt in the workspace root.
#
# Usage:
#   ./scripts/coreprofile_rest_tck.sh [timeout_seconds]
#
# Examples:
#   ./scripts/coreprofile_rest_tck.sh          # default 3-hour timeout
#   ./scripts/coreprofile_rest_tck.sh 3600     # 1-hour timeout
#
# To re-run a single failing test afterwards:
#   cd test/tck/coreprofile/rest/runner
#   mvn verify -Dit.test="ClassName#methodName"

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNNER_DIR="$SCRIPT_DIR/../test/tck/coreprofile/rest/runner"
REPORTS_DIR="$RUNNER_DIR/target/failsafe-reports"
OUTPUT_FILE="$SCRIPT_DIR/coreprofile_rest_tck.txt"
TIMEOUT_SECONDS="${1:-10800}"  # default: 3 hours

echo "========================================="
echo " Core Profile REST TCK Runner"
echo " Timeout: ${TIMEOUT_SECONDS}s (3 hours)"
echo "========================================="
echo ""

# Run Maven with a timeout; -fae keeps going after failures so all reports
# are generated even when individual tests fail.
echo "Starting: mvn verify -fae"
echo ""

# Use a background process + timeout guard so we can kill the whole Maven
# process tree if the deadline is reached.
mvn verify -fae -f "$RUNNER_DIR/pom.xml" &
MVN_PID=$!

# Wait up to TIMEOUT_SECONDS for Maven to finish naturally.
ELAPSED=0
INTERVAL=5
TIMED_OUT=0

while kill -0 "$MVN_PID" 2>/dev/null; do
    sleep $INTERVAL
    ELAPSED=$((ELAPSED + INTERVAL))
    if (( ELAPSED >= TIMEOUT_SECONDS )); then
        echo ""
        echo "WARN: Timeout of ${TIMEOUT_SECONDS}s reached — killing Maven process tree..."
        # Kill the entire process group so forked JVMs are also terminated.
        kill -- -"$MVN_PID" 2>/dev/null || kill "$MVN_PID" 2>/dev/null || true
        TIMED_OUT=1
        break
    fi
done

# Reap the background process (ignore its exit code; we care about reports).
wait "$MVN_PID" 2>/dev/null || true

echo ""
if (( TIMED_OUT )); then
    echo "NOTE: TCK was stopped after ${TIMEOUT_SECONDS}s. Parsing partial results."
else
    echo "TCK run finished after ${ELAPSED}s. Parsing results."
fi
echo ""

# ---- Parse failsafe XML reports ----------------------------------------
if [[ ! -d "$REPORTS_DIR" ]]; then
    echo "ERROR: No failsafe reports found at $REPORTS_DIR"
    exit 1
fi

python3 - "$REPORTS_DIR" "$OUTPUT_FILE" <<'PYEOF'
import sys
import os
import xml.etree.ElementTree as ET

reports_dir = sys.argv[1]
output_file = sys.argv[2]

failing = []

for fname in sorted(os.listdir(reports_dir)):
    if not (fname.startswith("TEST-") and fname.endswith(".xml")):
        continue
    path = os.path.join(reports_dir, fname)
    try:
        tree = ET.parse(path)
    except ET.ParseError as e:
        print(f"  WARN: could not parse {fname}: {e}", file=sys.stderr)
        continue
    root = tree.getroot()
    for tc in root.findall("testcase"):
        if tc.find("failure") is not None or tc.find("error") is not None:
            classname = tc.get("classname", "")
            name      = tc.get("name", "")
            failing.append(f"{classname}#{name}")

with open(output_file, "w") as f:
    for t in failing:
        f.write(t + "\n")

print(f"Found {len(failing)} failing test(s). Written to {output_file}")
PYEOF
