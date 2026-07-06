# Build context is the repo root (see docker-compose.yml) so the composite
# build can reach the sibling domain-sdk source: domains/trading/bff/settings.gradle
# does includeBuild('../../../bff-core'), which resolves to /build/domain-sdk below.
FROM gradle:8.5-jdk17 AS builder
WORKDIR /build
COPY domain-sdk/ domain-sdk/
COPY domains/trading/bff/ domains/trading/bff/
WORKDIR /build/domains/trading/bff
RUN gradle --no-daemon shadowJar -x test

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /build/domains/trading/bff/build/libs/*-all.jar /app/trading-bff.jar
EXPOSE 8080
# Token Handler: import the mkcert root CA (mounted at /certs/mkcert-rootCA.pem)
# so the BFF trusts Keycloak's mkcert cert for the server-side OIDC code
# exchange against the public issuer. No-op if the CA isn't mounted.
ENTRYPOINT ["/bin/sh", "-c", "if [ -f /certs/mkcert-rootCA.pem ]; then keytool -importcert -noprompt -alias mkcert -file /certs/mkcert-rootCA.pem -cacerts -storepass changeit >/dev/null 2>&1 || true; fi; exec java -jar /app/trading-bff.jar"]
