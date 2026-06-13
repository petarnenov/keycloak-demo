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
# Same mkcert-CA import as the domain BFFs: the token-handler does the OIDC code
# flow against Keycloak's PUBLIC mkcert-signed issuer, so trust that CA.
ENTRYPOINT ["/bin/sh", "-c", "if [ -f /certs/mkcert-rootCA.pem ]; then keytool -importcert -noprompt -alias mkcert -file /certs/mkcert-rootCA.pem -cacerts -storepass changeit >/dev/null 2>&1 || true; fi; exec java -jar /app/token-handler.jar"]
