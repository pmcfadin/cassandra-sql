# Multi-stage Dockerfile for cassandra-sql Spring Boot application

# Stage 1: Build the application
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /build

# Copy Gradle wrapper and configuration files
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# Copy source code
COPY src src

# Build the application (skip tests for faster builds)
RUN ./gradlew bootJar -x test

# Stage 2: Runtime image
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Create non-root user for security
RUN groupadd -r cassandrasql && useradd -r -g cassandrasql cassandrasql

# Copy the built JAR from builder stage
COPY --from=builder /build/build/libs/cassandra-sql-mvp-0.1.0-SNAPSHOT.jar /app/cassandra-sql.jar

# Change ownership
RUN chown -R cassandrasql:cassandrasql /app

# Switch to non-root user
USER cassandrasql

# Expose ports
# 5432: PostgreSQL wire protocol
# 8080: REST API and health checks
EXPOSE 5432 8080

# Environment variables (can be overridden at runtime)
ENV JAVA_OPTS="-Xms512m -Xmx2g" \
    CASSANDRA_CONTACT_POINTS="cassandra" \
    CASSANDRA_PORT="9042" \
    CASSANDRA_LOCAL_DATACENTER="datacenter1" \
    CASSANDRA_KEYSPACE="cassandra_sql" \
    CASSANDRA_SQL_STORAGE_MODE="kv"

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# Start the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/cassandra-sql.jar"]
