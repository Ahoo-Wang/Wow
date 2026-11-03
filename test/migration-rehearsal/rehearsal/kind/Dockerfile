# The rehearsal host's image, the "service's own image" deploy/migrate-data's init container copies the lib from, as a
# service's image is built (deploy/eshop-catalog/Dockerfile). Never run here: the rehearsal runs the host on the runner.
# From the repository root, after ./gradlew :migration-rehearsal:installDist:
#   docker build -f test/migration-rehearsal/rehearsal/kind/Dockerfile -t wow-migration-rehearsal:local test/migration-rehearsal
FROM dragonwell-registry.cn-hangzhou.cr.aliyuncs.com/dragonwell/dragonwell:25-ubuntu@sha256:6490a55d94a6ba1dcf88f8a1de8a5d67f5cf0fac717c1d84f18933516bd27b1a

RUN groupadd -r -g 10001 appgroup && useradd -r -u 10001 -g appgroup appuser
COPY --chown=10001:10001 build/install/migration-rehearsal /opt/migration-rehearsal
USER 10001
ENTRYPOINT ["/opt/migration-rehearsal/bin/migration-rehearsal"]
