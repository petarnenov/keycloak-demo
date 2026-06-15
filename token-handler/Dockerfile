# Build context is the repo root (see docker-compose.yml) so the composite build
# can reach the sibling bff-core source: token-handler/settings.gradle does
# includeBuild('../bff-core'), which resolves to /build/bff-core below.
FROM gradle:8.5-jdk17 AS builder
WORKDIR /build
COPY bff-core/ bff-core/
COPY token-handler/ token-handler/
WORKDIR /build/token-handler
RUN gradle --no-daemon shadowJar -x test

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /build/token-handler/build/libs/*-all.jar /app/token-handler.jar
EXPOSE 8080
# Run as uid 1000 by default so the image is non-root everywhere — under compose
# (which sets no securityContext) as well as K8s. The hardened K8s manifest
# (runAsNonRoot: true, runAsUser: 1000, readOnlyRootFilesystem: true, drop ALL —
# see k8s/base/token-handler.yaml) would force uid 1000 regardless, but baking it
# in keeps the two consistent and the container non-root off-cluster too.
RUN chown -R 1000:1000 /app
USER 1000
# Same mkcert-CA trust as the domain BFFs: the token-handler does the OIDC code
# flow against Keycloak's PUBLIC mkcert-signed issuer, so it must trust that CA.
# The rootfs is read-only and we run non-root, so we can't modify the JVM's
# system cacerts in place. Instead copy it to the writable /tmp volume, import
# the mounted CA there, and point the JVM at the copy. /tmp is the container's
# writable layer under compose and an emptyDir under K8s — both work. Java's temp
# dir is pinned to /tmp too, the only writable path under readOnlyRootFilesystem.
ENTRYPOINT ["/bin/sh", "-c", "TS=/tmp/cacerts; cp \"$JAVA_HOME/lib/security/cacerts\" \"$TS\"; if [ -f /certs/mkcert-rootCA.pem ]; then keytool -importcert -noprompt -alias mkcert -file /certs/mkcert-rootCA.pem -keystore \"$TS\" -storepass changeit >/dev/null 2>&1 || true; fi; exec java -Djava.io.tmpdir=/tmp -Djavax.net.ssl.trustStore=\"$TS\" -Djavax.net.ssl.trustStorePassword=changeit -jar /app/token-handler.jar"]
