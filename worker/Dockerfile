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
COPY backend/pom.xml backend/
COPY worker/pom.xml worker/
RUN mvn -B -pl worker -am dependency:go-offline

COPY worker/src worker/src
RUN mvn -B -pl worker -am clean package -DskipTests

# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S codearena && adduser -S -G codearena codearena

COPY --from=build /build/worker/target/codearena-worker-*.jar app.jar
RUN chown -R codearena:codearena /app
USER codearena

EXPOSE 8081

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
