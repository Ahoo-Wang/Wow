# TEMPORARY (G.1 replaces it): the image of an eShop server without a deploy/ directory (catalog, basket, inventory,
# payment), built as deploy/eshop-ordering/Dockerfile builds the ordering's, from the server's Gradle distribution:
#   ./gradlew :eshop-<context>-server:installDist
#   docker build -f .github/ga-e2e/services/temporary/Dockerfile --build-arg APP_NAME=eshop-<context>-server \
#     -t wow-eshop-<context>-server:local example/eshop/<context>/<context>-server
ARG BASE_IMAGE=eclipse-temurin:21-jre
ARG APP_NAME
ARG WORK_HOME=/opt/${APP_NAME}

FROM ${BASE_IMAGE} AS base

FROM base AS build
ARG WORK_HOME
ARG APP_NAME

WORKDIR ${WORK_HOME}
COPY build/install/${APP_NAME} .

FROM base AS run
ARG WORK_HOME
ARG APP_NAME
ENV APP_NAME=${APP_NAME}

RUN groupadd -r -g 10001 appgroup && useradd -r -u 10001 -g appgroup appuser

COPY --from=build --chown=10001:10001 ${WORK_HOME} ${WORK_HOME}

WORKDIR ${WORK_HOME}

USER 10001
STOPSIGNAL SIGTERM

ENTRYPOINT ["/bin/sh", "-c", "exec bin/${APP_NAME}"]
