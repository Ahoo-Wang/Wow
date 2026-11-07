# The migrate-data image: the command line that moves a 9.x service's data into Wow 10, run next to the stores, as a
# Kubernetes Job (deploy/migrate-data). `./gradlew :migrate-data:installDist` first; image-deploy.yml builds it with the
# server images and tags only the release version: a pre-release never moves `latest`. From the repository root:
#   docker build -t wow-migrate-data:local migration/migrate-data
#   docker run --rm -v /path/to/migration:/config:ro -e WOW_PG_PASSWORD wow-migrate-data:local \
#     check --config /config/migrate-data.json
# The arguments are the command line's (`check`, `import`, `tail`, `compare`, ...). The configuration's relative paths
# resolve against its own directory, which a ConfigMap mounts read-only: keep the checkpoint in the target store
# ("checkpoint": "target:<name>") or on a writable volume. The service's V10 classes are not in the image: `classpath`
# names a directory an init container copies them into (deploy/migrate-data).
ARG APP_NAME=migrate-data
ARG WORK_HOME=/opt/${APP_NAME}

# JDK 25, as Wow recommends to run on, pinned by digest.
FROM dragonwell-registry.cn-hangzhou.cr.aliyuncs.com/dragonwell/dragonwell:25-ubuntu@sha256:6490a55d94a6ba1dcf88f8a1de8a5d67f5cf0fac717c1d84f18933516bd27b1a AS base

FROM base AS build
ARG WORK_HOME
ARG APP_NAME

WORKDIR ${WORK_HOME}
COPY build/install/${APP_NAME} .

FROM base AS run
ARG WORK_HOME

# A numeric user, so a pod's runAsNonRoot can verify it (deploy/migrate-data).
RUN groupadd -r -g 10001 appgroup && useradd -r -u 10001 -g appgroup appuser

COPY --from=build --chown=10001:10001 ${WORK_HOME} ${WORK_HOME}

WORKDIR ${WORK_HOME}
# The heap follows the container's memory limit, as the platform deployments set it, and an OutOfMemoryError ends the
# process, so a Job retries it from its checkpoint instead of hanging; JAVA_OPTS replaces both.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

USER 10001
# The start script execs the JVM, so it receives the signal: a tail ends after its current pass.
STOPSIGNAL SIGTERM

ENTRYPOINT ["bin/migrate-data"]
