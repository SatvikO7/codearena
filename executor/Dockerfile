# =============================================================================
# CodeArena execution service
#
# This is the one image in the system whose container is given the Docker socket, so
# it is also the one that should carry as little as possible. It holds a JRE, a Docker
# *client*, the seccomp profile, and nothing else: no database driver, no shell
# tooling, no package manager left behind.
#
# The socket is granted in docker-compose.yml, not here. See docs/security.md and
# ADR-028 for what that grant means and what it does not.
# =============================================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml ./
COPY common/pom.xml common/
COPY backend/pom.xml backend/
COPY worker/pom.xml worker/
COPY executor/pom.xml executor/
# Warms the dependency cache so a source-only change does not re-download the world.
# Non-fatal: go-offline also resolves optional artefacts from third-party repositories,
# and a transient failure there must not break the build.
RUN mvn -B -pl executor -am dependency:go-offline || true

COPY common/src common/src
COPY executor/src executor/src
RUN mvn -B -pl executor -am clean package -DskipTests

# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# The Docker *client* only. This container talks to a daemon over the socket mounted at
# runtime; it does not run a daemon itself.
RUN apk add --no-cache docker-cli

RUN addgroup -S codearena && adduser -S -G codearena codearena

COPY --from=build /build/executor/target/codearena-executor-*-boot.jar app.jar
# The seccomp profile is read by the DAEMON, not by this process -- but the daemon here
# shares a filesystem namespace with Docker Desktop's VM, and in a Linux deployment the
# profile path must be one the daemon can open. Baking it into the image keeps the
# profile versioned with the code that references it; see docs/security.md for the
# deployment note when the daemon is genuinely remote.
COPY sandbox/seccomp/codearena.json /app/seccomp/codearena.json

RUN chown -R codearena:codearena /app

# Runs as a non-root user. Access to the mounted Docker socket is granted at deploy time
# through `group_add` in docker-compose.yml rather than baked in here: the socket's owning
# group differs between hosts (gid 0 on Docker Desktop's VM, usually the `docker` group on
# a Linux host), and hard-coding a guess produces a permission error that looks like a bug
# in the service rather than a mismatch in the environment.
USER codearena

EXPOSE 8082

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
