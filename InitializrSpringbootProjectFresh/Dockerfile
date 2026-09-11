# Stage 1: Build with Maven
FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /workspace

# Copy pom.xml and download dependencies (cache layer)
COPY pom.xml .
RUN mvn -q dependency:resolve

# Copy source and build
COPY src ./src
# Source archives contain no .git directory. CI verifies Spotless in a full checkout.
RUN mvn -q -DskipTests -Dspotless.skip=true clean package

# Stage 2: Runtime image (minimal)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create non-root user and runtime directories required by scheduled jobs.
RUN addgroup -g 1002 appuser \
    && adduser -D -u 1002 -G appuser appuser \
    && mkdir -p /tmp/cpay-locks \
    && chown appuser:appuser /tmp/cpay-locks

# Set environment
ENV HTTP_PORT=8081 \
    JAVA_OPTS="-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxRAMPercentage=50 -XX:MinRAMPercentage=25"

EXPOSE 8081

# Copy JAR from build stage
COPY --from=build --chown=appuser:appuser /workspace/target/*.jar /app/cpay-backend.jar

# Copy start script for Railway compatibility.
COPY --chown=appuser:appuser start.sh /app/start.sh
RUN chmod +x /app/start.sh

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8081/status/health || exit 1

# Use non-root user
USER appuser

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/cpay-backend.jar"]
