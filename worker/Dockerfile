# =============================================================================
# CodeArena judge worker
#
# The worker orchestrates sandboxed execution containers. It deliberately does NOT
# receive the Docker socket in this image definition - how it is granted access to a
# container runtime is a deployment decision made in docker-compose.yml, and the
# trade-offs are documented in docs/security.md.
# =============================================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml ./
COPY common/pom.xml common/
COPY backend/pom.xml backend/
COPY worker/pom.xml worker/
# Warms the dependency cache so a source-only change does not re-download the world.
# Deliberately non-fatal: go-offline also tries to resolve optional artefacts from
# third-party repositories, and a transient failure there must not break the build -
# the package step below fetches anything genuinely missing.
RUN mvn -B -pl worker -am dependency:go-offline || true

COPY common/src common/src
COPY worker/src worker/src
RUN mvn -B -pl worker -am clean package -DskipTests

# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# The Docker *client* only. The worker talks to a daemon over the socket mounted at
# runtime; it does not run a daemon itself, and no image here is ever used to execute
# user code -- sandbox images are pulled by the daemon on the other side of the socket.
RUN apk add --no-cache docker-cli

RUN addgroup -S codearena && adduser -S -G codearena codearena

COPY --from=build /build/worker/target/codearena-worker-*.jar app.jar
RUN chown -R codearena:codearena /app

# Runs as a non-root user. Access to the mounted Docker socket is granted at deploy time
# through `group_add` in docker-compose.yml rather than baked in here: the socket's owning
# group differs between hosts (gid 0 on Docker Desktop's VM, usually the `docker` group on
# a Linux host), and hard-coding a guess produces a permission error that looks like a bug
# in the worker rather than a mismatch in the environment.
USER codearena

EXPOSE 8081

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
