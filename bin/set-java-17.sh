#!/bin/bash

# Helper script to set JAVA_HOME to Java 17

echo "Setting JAVA_HOME to Java 17..."

# Try to find Java 17
if [ -x "/usr/libexec/java_home" ]; then
    # macOS
    JAVA17=$(/usr/libexec/java_home -v 17 2>/dev/null)
    if [ -n "$JAVA17" ]; then
        export JAVA_HOME="$JAVA17"
        echo "✅ JAVA_HOME set to: $JAVA_HOME"
        java -version
        return 0
    fi
fi

# Try common Linux paths
for java_dir in /usr/lib/jvm/java-17-* /usr/lib/jvm/jdk-17*; do
    if [ -d "$java_dir" ]; then
        export JAVA_HOME="$java_dir"
        echo "✅ JAVA_HOME set to: $JAVA_HOME"
        java -version
        return 0
    fi
done

echo "❌ Java 17 not found. Please install it:"
echo ""
echo "macOS:  brew install openjdk@17"
echo "Ubuntu: sudo apt-get install openjdk-17-jdk"
echo "RHEL:   sudo yum install java-17-openjdk-devel"
return 1





