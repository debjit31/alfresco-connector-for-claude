# ═══════════════════════════════════════════════════════════════════
#  Stage 1: Build
# ═══════════════════════════════════════════════════════════════════
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

WORKDIR /build
COPY pom.xml .
# Download dependencies first (layer caching)
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn package -DskipTests -B

# ═══════════════════════════════════════════════════════════════════
#  Stage 2: Runtime
# ═══════════════════════════════════════════════════════════════════
FROM eclipse-temurin:17-jre-alpine

RUN addgroup -S mcp && adduser -S mcp -G mcp
WORKDIR /app

COPY --from=builder /build/target/alfresco-mcp-server-*.jar app.jar

# Default env vars (override at runtime)
ENV ALFRESCO_BASE_URL=http://host.docker.internal:8080/alfresco \
    ALFRESCO_AUTH_USERNAME=admin \
    ALFRESCO_AUTH_PASSWORD=admin \
    SERVER_PORT=3000

EXPOSE 3000

USER mcp

ENTRYPOINT ["java", \
    "-XX:+UseG1GC", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar"]
