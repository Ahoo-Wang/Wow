# The analytics catalog service image (decisions D238, D241): `./gradlew :analytics-catalog-server:installDist`
# after `pnpm --filter wow-analytics-catalog-console... build`, which puts the data-owner console in the distribution's
# ui/. image-deploy.yml builds it and tags only the release version: a pre-release never moves `latest`.
# Callers: the image trusts no identity header, and runs CoSec only in its `cosec` profile
# (SPRING_PROFILES_ACTIVE=cosec, with COSEC_JWT_SECRET), which verifies each request's CoSec token; without it every
# request is answered 401. WOW_PLATFORM_TRUSTIDENTITYHEADERS=true (the property wow.platform.trust-identity-headers)
# takes them from X-User, X-Tenant and X-Role instead, for a local demo only. The warehouse it reads is
# WOW_CLICKHOUSE_ENDPOINT (wow.clickhouse.endpoint); the `postgres` profile keeps the sources on PostgreSQL.
ARG APP_NAME=analytics-catalog-server
ARG WORK_HOME=/opt/${APP_NAME}

FROM dragonwell-registry.cn-hangzhou.cr.aliyuncs.com/dragonwell/dragonwell:21-ubuntu AS base

FROM base AS build
ARG WORK_HOME
ARG APP_NAME

WORKDIR ${WORK_HOME}
COPY build/install/${APP_NAME} .

FROM base AS run
ARG WORK_HOME

# A numeric user, so a pod's runAsNonRoot can verify it.
RUN groupadd -r -g 10001 appgroup && useradd -r -u 10001 -g appgroup appuser

COPY --from=build --chown=10001:10001 ${WORK_HOME} ${WORK_HOME}

WORKDIR ${WORK_HOME}
# The HTTP routes and the console; 9097 is the node's internal port (wow.internal.port), which only the platform's own
# pods may reach (a NetworkPolicy, decision D300): it takes no token.
EXPOSE 8087 9097
# The process accepts connections. Plain bash, so the check needs no curl in the image.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8087"]

USER 10001
STOPSIGNAL SIGTERM

ENTRYPOINT ["bin/analytics-catalog-server"]
