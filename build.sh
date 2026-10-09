#!/usr/bin/env bash
# Build Forge from source.
# Usage: ./build.sh
#
# Requires: Java 17+ and Maven 3.8.1+
#
# If the build fails, every module's target/ directory is wiped so that no
# stale or partially-built artifacts (e.g. an old fat jar) are left behind and
# accidentally run. On success the freshly built artifacts are kept.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Detect Java 17+ if JAVA_HOME is not set
if [ -z "$JAVA_HOME" ]; then
    # Scan /usr/lib/jvm/ for the newest JDK (with javac) with version >= 17
    best_ver=0
    best_jdk=""
    for jdk in /usr/lib/jvm/java-*-openjdk /usr/lib/jvm/java-*-jdk /usr/lib/jvm/jdk-*; do
        if [ -d "$jdk" ] && [ -x "$jdk/bin/javac" ]; then
            ver=$("$jdk/bin/java" -version 2>&1 | head -1 | grep -oP '"\K[0-9]+')
            if [ -n "$ver" ] && [ "$ver" -ge 17 ] && [ "$ver" -gt "$best_ver" ]; then
                best_ver=$ver
                best_jdk="$jdk"
            fi
        fi
    done
    if [ -n "$best_jdk" ]; then
        JAVA_HOME="$best_jdk"
    fi
fi

if [ -n "$JAVA_HOME" ]; then
    export JAVA_HOME="$JAVA_HOME"
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "export JAVA_HOME=$JAVA_HOME"
else
    echo "ERROR: Could not find Java 17+ in /usr/lib/jvm/."
    exit 1
fi

# Verify Java version
java_version=$(java -version 2>&1 | head -1 | grep -oP '"\K[0-9]+')
if [ "$java_version" -lt 17 ]; then
    echo "ERROR: Java 17 or later is required (found Java $java_version)."
    exit 1
fi

# Verify Maven
if ! command -v mvn &>/dev/null; then
    echo "ERROR: Maven is not installed or not in PATH."
    exit 1
fi

echo "Java version: $(java -version 2>&1 | head -1)"
echo "Maven version: $(mvn -version 2>&1 | head -1)"

cd "$SCRIPT_DIR"

# Wipe the parent and every module's target/ directory. Used after a failed
# build so no stale or partially-built artifacts can be run by mistake.
# Uses rm -rf (not `mvn clean`) so the cleanup works even if Maven itself is
# the thing that broke.
clean_targets() {
    echo "Wiping all target/ directories..."
    rm -rf target */target
}

# Run the build. Capture the exit status without letting `set -e` abort the
# script before we can clean up on failure.
BUILD_STATUS=0
mvn -U -B clean -P windows-linux install || BUILD_STATUS=$?

if [ "$BUILD_STATUS" -ne 0 ]; then
    echo ""
    echo "BUILD FAILED (exit code $BUILD_STATUS)."
    echo "Removing stale/partial artifacts so they cannot be run by mistake."
    clean_targets
    exit "$BUILD_STATUS"
fi

echo ""
echo "BUILD SUCCESS."
