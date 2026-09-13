# =============================================================================
# CodeArena API server
#
# Multi-stage: the JDK and the Maven cache stay in the build stage so the runtime
# image ships only a JRE and the application jar.
# =============================================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy poms first so dependency resolution is cached independently of source changes.
COPY pom.xml ./
COPY common/pom.xml common/
COPY backend/pom.xml backend/
COPY worker/pom.xml worker/
COPY executor/pom.xml executor/
# Warms the dependency cache so a source-only change does not re-download the world.
# Deliberately non-fatal: go-offline also tries to resolve optional artefacts from
# third-party repositories, and a transient failure there must not break the build -
# the package step below fetches anything genuinely missing.
RUN mvn -B -pl backend -am dependency:go-offline || true

COPY common/src common/src
COPY backend/src backend/src
# The executor's sources are copied because the reactor builds it: the worker
# test-depends on it (JudgeVerdictIT drives a real sandbox). Nothing from it ends up in
# this image -- only the worker jar is copied into the runtime stage below.
COPY executor/src executor/src
RUN mvn -B -pl backend -am clean package -DskipTests

# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Never run as root.
RUN addgroup -S codearena && adduser -S -G codearena codearena

COPY --from=build /build/backend/target/codearena-backend-*.jar app.jar
RUN chown -R codearena:codearena /app
USER codearena

EXPOSE 8080

# Container-aware heap sizing; the hard limit comes from the compose/task memory cap.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
