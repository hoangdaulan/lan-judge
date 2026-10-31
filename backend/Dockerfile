# syntax=docker/dockerfile:1
FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
COPY migrations ./migrations
COPY docs/swagger.json ./docs/swagger.json
COPY web/static ./web/static
RUN mvn -B -q package

# Retain Debian bookworm and its g++ toolchain for identical C++17 judging.
FROM debian:bookworm-slim AS runtime-base
RUN apt-get update \
 && apt-get install -y --no-install-recommends openjdk-17-jre-headless ca-certificates tzdata openssl tini \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --create-home app \
 && mkdir -p /app /data \
 && chown app:app /data
ENV DATA_DIR=/data
COPY --from=build /src/target/lan-judge.jar /app/lan-judge.jar
COPY scripts/container-command.sh /app/command
RUN chmod 0755 /app/command \
 && ln -s command /app/api \
 && ln -s command /app/worker \
 && ln -s command /app/migrate \
 && ln -s command /app/seed

FROM runtime-base AS api
USER app
EXPOSE 8080 8443
ENTRYPOINT ["/usr/bin/tini", "--", "/app/api"]

FROM runtime-base AS worker
RUN apt-get update \
 && apt-get install -y --no-install-recommends build-essential \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir -p /var/judge \
 && chmod 0711 /var/judge
COPY native/sandbox.c /tmp/sandbox.c
RUN cc -std=c11 -O2 -Wall -Wextra -Werror /tmp/sandbox.c -o /app/sandbox-helper \
 && chmod 0755 /app/sandbox-helper \
 && rm /tmp/sandbox.c
ENTRYPOINT ["/usr/bin/tini", "--", "/app/worker"]

# Explicit test target; never included in either production image.
FROM worker AS sandbox-test
COPY --from=build /usr/share/maven /opt/maven
COPY --from=build /root/.m2 /root/.m2
COPY --from=build /src /src
WORKDIR /src
ENV RUN_SANDBOX_TESTS=true
ENTRYPOINT ["/opt/maven/bin/mvn", "-o", "-q", "-Dtest=SandboxTest", "test"]
