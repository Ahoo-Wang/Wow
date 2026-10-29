# The ops service image (R.1): `./gradlew :ops-server:installDist` after
# `pnpm --filter wow-ops-console... build`, which puts the console in the distribution's ui/. image-deploy.yml
# builds it and tags only the release version: a pre-release never moves `latest`.
# Callers: the image trusts no identity header, and runs CoSec only in its `cosec` profile
# (SPRING_PROFILES_ACTIVE=cosec), CoSec's inject mode behind the CoSec gateway: the gateway verifies each request's
# CoSec token and the service parses it, with no secret (decision D296). Without CoSec it starts in a development
# profile alone (SPRING_PROFILES_ACTIVE=dev, of wow.dev-profiles), for a local run, which takes the callers from the
# Wow-User, Wow-Tenant and Wow-Roles headers any client sets: elsewhere the service refuses to start. deploy runs it on Kubernetes.
ARG APP_NAME=ops-server
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

# A numeric user, so a pod's runAsNonRoot can verify it (deploy).
RUN groupadd -r -g 10001 appgroup && useradd -r -u 10001 -g appgroup appuser

COPY --from=build --chown=10001:10001 ${WORK_HOME} ${WORK_HOME}

WORKDIR ${WORK_HOME}
# The HTTP routes and the console; 9098 is the node's internal port (wow.internal.port), which only the platform's own
# pods may reach (a NetworkPolicy, decision D300): it takes no token.
EXPOSE 8088 9098
# The process accepts connections; the server has no actuator. Plain bash, so the check needs no curl in the image.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8088"]

USER 10001
STOPSIGNAL SIGTERM

ENTRYPOINT ["bin/ops-server"]
