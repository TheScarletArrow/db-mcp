# syntax=docker/dockerfile:1.7
#
# Layered, slim image: ~110 MB on disk / ~80 MB to pull, down from ~360 MB / ~150 MB.
# A rebuild after a code change ships the 0.4 MB `application` layer instead of a
# fresh 43 MB fat-jar layer; the JRE and dependency layers stay cached.
#
#   docker build -t db-mcp:local .
#
# ---- stage 1: build the jar and split it into Spring Boot layers --------------
# Pinned to the build host's architecture: the jar is architecture independent,
# so a multi-arch build compiles once instead of once per platform under QEMU.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies resolve in their own layer (keyed on pom.xml only), so source
# changes do not re-resolve them. The cache mount additionally keeps ~/.m2
# across builds on the same machine.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/db-mcp-*.jar extract --layers --launcher --destination /layers

# ---- stage 2: a JRE with only the modules this application uses ---------------
# jlink trims the 300 MB JDK to ~60 MB. The module list is jdeps' output for the
# application plus the modules that are only reached reflectively: SASL (the
# PostgreSQL SCRAM-SHA-256 handshake), JGSS (Kerberos), rowset/XA (JDBC), the EC
# and PKCS#11 crypto providers (TLS), JMX and zipfs.
# Locale data beyond root/English is left out; add `jdk.localedata` (+ e.g.
# --include-locales=en,ru) if you need localised formatting.
FROM eclipse-temurin:21-jdk-alpine AS jre
RUN jlink \
      --add-modules java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.jfr,jdk.management,jdk.unsupported,jdk.zipfs \
      --strip-java-debug-attributes --no-header-files --no-man-pages --compress=zip-6 \
      --output /javaruntime

# ---- stage 3: runtime ---------------------------------------------------------
FROM alpine:3.22
ENV JAVA_HOME=/opt/java \
    PATH="/opt/java/bin:$PATH" \
    DB_MCP_HOME=/data \
    DB_MCP_BIND=0.0.0.0 \
    DB_MCP_PORT=8080 \
    SPRING_PROFILES_ACTIVE=http \
    HOME=/tmp \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Persistent state (vault.enc + vault.key) lives in /data: mount a volume to keep
# it across restarts. Everything else in the image is read-only.
# The account's home is /tmp (a tmpfs under compose): nothing but /data needs to
# survive, but a JVM that reaches for user.home should not hit a read-only path.
RUN adduser -S -H -h /tmp -s /sbin/nologin -u 10001 dbmcp \
 && mkdir -p /data /app \
 && chown 10001 /data
COPY --from=jre /javaruntime $JAVA_HOME

WORKDIR /app
# One COPY per Spring Boot layer, ordered from the least to the most volatile:
# a code change invalidates only the last one.
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./

USER 10001
VOLUME ["/data"]
EXPOSE 8080
# The stdio transport has no HTTP server, so only probe under the http profile.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD case "$SPRING_PROFILES_ACTIVE" in \
        *http*) wget -q -O /dev/null "http://127.0.0.1:${DB_MCP_PORT}/actuator/health" || exit 1 ;; \
        *) exit 0 ;; \
      esac
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp /app org.springframework.boot.loader.launch.JarLauncher \"$@\"", "db-mcp"]
