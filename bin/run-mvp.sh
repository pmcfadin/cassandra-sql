#!/bin/bash

# Parse command line arguments
LOG_LEVEL="INFO"
EXTRA_ARGS=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --loglevel=*)
            LOG_LEVEL="${1#*=}"
            shift
            ;;
        --log-level=*)
            LOG_LEVEL="${1#*=}"
            shift
            ;;
        *)
            EXTRA_ARGS="$EXTRA_ARGS $1"
            shift
            ;;
    esac
done

echo "=================================="
echo "Cassandra-SQL MVP Launcher"
echo "=================================="
echo "Log Level: $LOG_LEVEL"
echo ""

# Check Java version
echo "Checking Java version..."

# Set JAVA_HOME to Java 17 if available
if [ -x "/usr/libexec/java_home" ]; then
    JAVA17=$(/usr/libexec/java_home -v 17 2>/dev/null)
    if [ -n "$JAVA17" ]; then
        export JAVA_HOME="$JAVA17"
        export PATH="$JAVA_HOME/bin:$PATH"
    fi
fi

if ! command -v java &> /dev/null; then
    echo "❌ Java is not installed. Please install Java 17+."
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | grep version | awk '{print $3}' | tr -d '"' | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo "❌ Java version must be 17 or higher. Current: $JAVA_VERSION"
    echo ""
    echo "To use Java 17 (if installed):"
    echo "  source ./set-java-17.sh"
    exit 1
fi
echo "✅ Java $JAVA_VERSION detected (JAVA_HOME: $JAVA_HOME)"
echo ""

# Check if Cassandra is running
echo "Checking Cassandra connection..."
if ! nc -z localhost 9042 2>/dev/null; then
    echo "❌ Cannot connect to Cassandra on localhost:9042"
    echo ""
    echo "Cassandra (trunk with Accord) is not running."
    echo ""
    exit 1
fi
echo "✅ Cassandra is reachable on localhost:9042"
echo ""

# Build if needed
if [ ! -f "build/libs/cassandra-sql-mvp-0.1.0-SNAPSHOT.jar" ]; then
    echo "Building project with Gradle..."
    ./gradlew clean build -x test
    if [ $? -ne 0 ]; then
        echo "❌ Build failed"
        exit 1
    fi
    echo "✅ Build successful"
    echo ""
fi

# Run the application
echo "=================================="
echo "Starting Cassandra-SQL MVP..."
echo "=================================="
echo ""
echo "API will be available at:"
echo "  http://localhost:8080/api/sql/execute"
echo ""
echo "Health check:"
echo "  http://localhost:8080/api/sql/health"
echo ""
echo "To test with curl, run in another terminal:"
echo "  ./test-mvp.sh"
echo ""
echo "Press Ctrl+C to stop"
echo "=================================="
echo ""

./gradlew bootRun --args="--logging.level.org.cassandrasql=$LOG_LEVEL $EXTRA_ARGS"

