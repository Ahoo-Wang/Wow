# The eShop inventory server's image, from its Gradle distribution. From the repository root:
#   ./gradlew :eshop-inventory-server:installDist
#   docker build -f deploy/eshop-inventory/Dockerfile -t wow-eshop-inventory-server:local \
#     example/eshop/inventory/inventory-server
# Any JDK 21 image on Ubuntu, such as eclipse-temurin:21-jre (--build-arg BASE_IMAGE=...).
ARG BASE_IMAGE=dragonwell-registry.cn-hangzhou.cr.aliyuncs.com/dragonwell/dragonwell:21-ubuntu
ARG APP_NAME=eshop-inventory-server
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
EXPOSE 8084 9094

USER 10001
STOPSIGNAL SIGTERM

ENTRYPOINT ["bin/eshop-inventory-server"]
