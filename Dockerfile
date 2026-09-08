# ---- build stage -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
# warm the dependency cache in its own layer so source changes do not re-download everything
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:21-jre
# Persistent state (vault.enc + vault.key) lives here: mount a volume to keep it across restarts.
ENV DB_MCP_HOME=/data \
    DB_MCP_BIND=0.0.0.0 \
    DB_MCP_PORT=8080 \
    SPRING_PROFILES_ACTIVE=http \
    JAVA_OPTS=""
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home --home-dir /app dbmcp \
    && mkdir -p /data && chown dbmcp:dbmcp /data
WORKDIR /app
COPY --from=build --chown=dbmcp:dbmcp /build/target/db-mcp-*.jar /app/db-mcp.jar
USER dbmcp
VOLUME ["/data"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/db-mcp.jar"]
