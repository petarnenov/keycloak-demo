# Bake a Docker image with the Oracle Free schema + Flyway seeds already loaded
# so the container starts in seconds instead of running stack/Makefile's ~2-3
# min provision flow against an empty volume on every fresh container.
#
# Two roles in one image:
#   - build time: install Flyway, boot Oracle, run prepare-oracle + V1 baseline
#     via sqlplus + flyway baseline + flyway migrate V2..VN (+ R__ login hash),
#     shut Oracle down cleanly, snapshot /opt/oracle/oradata into a non-VOLUME
#     path. The gvenzl base image declares /opt/oracle/oradata as a VOLUME, so
#     bytes written there during build are discarded at layer commit — the
#     snapshot in /opt/oracle/baked-oradata is the workaround.
#   - run time:  restore-oradata.sh seeds the fresh anonymous volume from the
#     baked snapshot (one-time copy, then never again for the lifetime of the
#     volume), then hands off to the gvenzl entrypoint, which sees an
#     initialized DB and just opens it.
#
# Build (context = db/):
#   docker build -t keycloak-demo-db:seeded -f db/Dockerfile db
# Run:
#   docker run --rm -p 1521:1521 --name gw-oracle keycloak-demo-db:seeded
#   # first start: ~30-60s copy + ~10s Oracle open
#   # subsequent starts on the same volume: ~10s
#
# Login: GP / gp123 to FREEPDB1 (matches db/stack/). SYS pw: oracle_sys_pw.
#
# SECURITY: db/local/R__local_login_hash.sql is gitignored because it carries
# a password hash. If it's in the build context, it gets baked into the image.
# Treat the resulting image as local-only — do NOT push it to a public registry.

FROM gvenzl/oracle-free:23-slim

ARG FLYWAY_VERSION=10.22.0

# build-time toolchain: Java + Flyway, both stripped at the end of the build.
# Flyway is served from Maven Central; GitHub releases for flyway-* tags no
# longer publish the CLI tarball as an asset.
USER root
RUN set -eux; \
    microdnf install -y java-17-openjdk-headless tar gzip; \
    microdnf clean all; \
    curl -fsSL -o /tmp/flyway.tgz \
      "https://repo1.maven.org/maven2/org/flywaydb/flyway-commandline/${FLYWAY_VERSION}/flyway-commandline-${FLYWAY_VERSION}-linux-x64.tar.gz"; \
    tar -xzf /tmp/flyway.tgz -C /opt; \
    rm /tmp/flyway.tgz; \
    chmod -R a+rX "/opt/flyway-${FLYWAY_VERSION}"; \
    ln -s "/opt/flyway-${FLYWAY_VERSION}/flyway" /usr/local/bin/flyway

# same credentials as db/stack/docker-compose.yml so anything pointed at the
# stack works against this image unchanged. DO NOT set ORACLE_DATABASE: the
# 23-slim base image already carries a pre-baked FREEPDB1 PDB and the gvenzl
# entrypoint reads ORACLE_DATABASE as "create ANOTHER PDB with that name" — if
# we set it to "FREEPDB1" it errors with ORA-65012 (PDB already exists).
ENV ORACLE_PASSWORD=oracle_sys_pw \
    APP_USER=GP \
    APP_USER_PASSWORD=gp123

# Flyway inputs (only needed at build time; cleaned up below)
COPY migration /flyway/sql
COPY local     /flyway/local

# bake orchestrator + runtime restore
COPY docker/bake.sh            /usr/local/bin/bake.sh
COPY docker/restore-oradata.sh /usr/local/bin/restore-oradata.sh
RUN set -eux; \
    chmod +x /usr/local/bin/bake.sh /usr/local/bin/restore-oradata.sh; \
    mkdir -p /opt/oracle/baked-oradata; \
    chown oracle:oinstall /opt/oracle/baked-oradata

USER oracle
RUN /usr/local/bin/bake.sh

# strip Java, Flyway and the source migrations — dead weight at runtime. tar/gzip
# stay because cracklib (a system package) depends on them; they're tiny anyway.
USER root
RUN set -eux; \
    rm -rf /flyway "/opt/flyway-${FLYWAY_VERSION}" /usr/local/bin/flyway /usr/local/bin/bake.sh; \
    microdnf remove -y java-17-openjdk-headless; \
    microdnf clean all

USER oracle
ENTRYPOINT ["/usr/local/bin/restore-oradata.sh"]
CMD ["/opt/oracle/container-entrypoint.sh"]
