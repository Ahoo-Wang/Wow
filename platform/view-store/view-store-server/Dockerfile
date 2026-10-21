# The view store service image (R.1): `./gradlew :view-store-server:installDist`.
# image-deploy.yml builds it and tags only the release version: a pre-release never moves `latest`, which stays
# on the 9.x image of the same name (view-store-deploy.yml).
# Callers: the image trusts no identity header, and runs CoSec only in its `cosec` profile
# (SPRING_PROFILES_ACTIVE=cosec, with COSEC_JWT_SECRET), which verifies each request's CoSec token; without it every
# request is answered 401. WOW_PLATFORM_TRUSTIDENTITYHEADERS=true (the property wow.platform.trust-identity-headers)
# takes them from X-User, X-Tenant and X-Role instead, for a local demo only. deploy/v10 runs it on Kubernetes.
ARG APP_NAME=view-store-server
ARG WORK_HOME=/opt/${APP_NAME}

FROM dragonwell-registry.cn-hangzhou.cr.aliyuncs.com/dragonwell/dragonwell:21-ubuntu AS base

FROM base AS build
ARG WORK_HOME
ARG APP_NAME

WORKDIR ${WORK_HOME}
COPY build/install/${APP_NAME} .

FROM base AS run
ARG WORK_HOME

# A numeric user, so a pod's runAsNonRoot can verify it (deploy/v10).
RUN groupadd -r -g 10001 appgroup && useradd -r -u 10001 -g appgroup appuser

COPY --from=build --chown=10001:10001 ${WORK_HOME} ${WORK_HOME}

WORKDIR ${WORK_HOME}
# The HTTP routes; 9090 is the node's internal port (wow.internal.port). Run the image with WOW_INTERNAL_TOKEN, the
# nodes' cluster token (decision D226): without one, a node on a reachable address does not start.
EXPOSE 8080 9090
# The process accepts connections; the server has no actuator. Plain bash, so the check needs no curl in the image.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080"]

USER 10001
STOPSIGNAL SIGTERM

ENTRYPOINT ["bin/view-store-server"]
