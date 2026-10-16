# The V10 view store service image (R.1): `./gradlew -Pwow.scope=v10 :view-store-server:installDist`.
# v10-image-deploy.yml builds it and tags only the release version: a pre-release never moves `latest`, which stays
# on the 9.x image of the same name (view-store-deploy.yml).
# Callers: the image trusts no identity header. Without wow-cosec on its class path every request is answered 401
# until a deployment gives it an identity provider; WOW_PLATFORM_TRUSTIDENTITYHEADERS=true (the property
# wow.platform.trust-identity-headers) takes them from X-User, X-Tenant and X-Role instead, for a local demo only.
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

RUN groupadd -r appgroup && useradd -r -g appgroup appuser

COPY --from=build --chown=appuser:appgroup ${WORK_HOME} ${WORK_HOME}

WORKDIR ${WORK_HOME}
# The HTTP routes; 9090 is the node's internal port (wow.internal.port).
EXPOSE 8080 9090
# The process accepts connections; the server has no actuator. Plain bash, so the check needs no curl in the image.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080"]

USER appuser
STOPSIGNAL SIGTERM

ENTRYPOINT ["bin/view-store-server"]
