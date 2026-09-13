# =============================================================================
# CodeArena judge worker
#
# As of Phase 6 the worker has NO access to a container runtime at all. It consumes
# submissions, asks the execution service to compile and run them, compares output and
# writes verdicts. The Docker socket belongs to the executor, and the Docker client is
# deliberately absent from this image: a worker that cannot speak to a daemon cannot be
# made to, however thoroughly it is compromised. See ADR-028.
# =============================================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml ./
COPY common/pom.xml common/
COPY backend/pom.xml backend/
COPY worker/pom.xml worker/
COPY executor/pom.xml executor/
# Warms the dependency cache so a source-only change does not re-download the world.
# Deliberately non-fatal: go-offline also tries to resolve optional artefacts from
# third-party repositories, and a transient failure there must not break the build -
# the package step below fetches anything genuinely missing.
RUN mvn -B -pl worker -am dependency:go-offline || true

COPY common/src common/src
COPY worker/src worker/src
# The executor's sources are copied because the reactor builds it: the worker
# test-depends on it (JudgeVerdictIT drives a real sandbox). Nothing from it ends up in
# this image -- only the worker jar is copied into the runtime stage below.
COPY executor/src executor/src
RUN mvn -B -pl worker -am clean package -DskipTests

# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# No docker-cli. Its removal is the point of the phase, not an oversight: there is
# nothing in this image that can talk to a container runtime.
RUN addgroup -S codearena && adduser -S -G codearena codearena

COPY --from=build /build/worker/target/codearena-worker-*.jar app.jar
RUN chown -R codearena:codearena /app

# Runs as a non-root user with no supplementary groups and no socket to open.
USER codearena

EXPOSE 8081

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
