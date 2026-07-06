# Build context is the repo root (see docker-compose.yml) so the composite
# build can reach the sibling domain-sdk source: domains/billing/bff/settings.gradle
# does includeBuild('../../../bff-core'), which resolves to /build/domain-sdk below.
FROM gradle:8.5-jdk17 AS builder
WORKDIR /build
COPY domain-sdk/ domain-sdk/
COPY domains/billing/bff/ domains/billing/bff/
WORKDIR /build/domains/billing/bff
RUN gradle --no-daemon shadowJar -x test

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /build/domains/billing/bff/build/libs/*-all.jar /app/billing-bff.jar
EXPOSE 8080
# Token Handler: the BFF does the OIDC code flow server-side against Keycloak's
# PUBLIC issuer (https://auth.geowealth.int:5180) so the `iss` claim matches the
# browser-side authorize redirect. That host serves an mkcert-signed cert, so we
# import the mkcert root CA (mounted at /certs/mkcert-rootCA.pem) into the JRE
# trust store at startup. No-op if the CA isn't mounted.
ENTRYPOINT ["/bin/sh", "-c", "if [ -f /certs/mkcert-rootCA.pem ]; then keytool -importcert -noprompt -alias mkcert -file /certs/mkcert-rootCA.pem -cacerts -storepass changeit >/dev/null 2>&1 || true; fi; exec java -jar /app/billing-bff.jar"]
