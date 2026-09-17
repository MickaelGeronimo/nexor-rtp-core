# ====================================================================
# Nexor RTP Core Multi-Stage Production Dockerfile
# Hardened, non-root user (UID 10001), minimal attack surface
# ====================================================================

# Stage 1: Build stage
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder
WORKDIR /workspace

# Copy POM and download dependencies
COPY pom.xml ./
RUN mvn dependency:go-offline -B

# Copy sources and package executable jar
COPY src src
RUN mvn clean package -DskipTests -B

# Stage 2: Hardened Runtime stage
FROM eclipse-temurin:17-jre-alpine AS runtime

# Create non-root group and user
RUN addgroup -g 10001 -S nexor && \
    adduser -u 10001 -S nexor -G nexor

WORKDIR /app

# Copy packaged jar from builder
COPY --from=builder /workspace/target/nexor-rtp-core-*.jar /app/app.jar

# Enforce non-root ownership and execution
RUN chown -R nexor:nexor /app
USER 10001:10001

EXPOSE 8080

ENTRYPOINT ["java", \
    "-XX:+UseG1GC", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "/app/app.jar"]
