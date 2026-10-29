# The eShop basket server's image, from its Gradle distribution. From the repository root:
#   ./gradlew :eshop-basket-server:installDist
#   docker build -f deploy/eshop-basket/Dockerfile -t wow-eshop-basket-server:local \
#     examples/eshop/basket/basket-server
# JDK 25, as Wow recommends to run on, pinned by digest; any JDK 21+ image on Ubuntu runs it
# (--build-arg BASE_IMAGE=...), though JDKs before 24 pin virtual threads that block in synchronized code.
ARG BASE_IMAGE=dragonwell-registry.cn-hangzhou.cr.aliyuncs.com/dragonwell/dragonwell:25-ubuntu@sha256:6490a55d94a6ba1dcf88f8a1de8a5d67f5cf0fac717c1d84f18933516bd27b1a
ARG APP_NAME=eshop-basket-server
ARG WORK_HOME=/opt/${APP_NAME}

FROM ${BASE_IMAGE} AS base

FROM base AS build
ARG WORK_HOME
ARG APP_NAME

WORKDIR ${WORK_HOME}
COPY build/install/${APP_NAME} .

FROM base AS run
ARG WORK_HOME

# A numeric user, so the pod's runAsNonRoot can verify it.
RUN groupadd -r -g 10001 appgroup && useradd -r -u 10001 -g appgroup appuser

COPY --from=build --chown=10001:10001 ${WORK_HOME} ${WORK_HOME}

WORKDIR ${WORK_HOME}
EXPOSE 8082 9092

USER 10001
STOPSIGNAL SIGTERM

ENTRYPOINT ["bin/eshop-basket-server"]
