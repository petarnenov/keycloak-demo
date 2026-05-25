FROM gradle:8.5-jdk17 AS builder
WORKDIR /app

COPY settings.gradle build.gradle.kts gradle.properties ./
RUN gradle --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN gradle --no-daemon shadowJar -x test

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/build/libs/*-all.jar /app/billing-bff.jar
EXPOSE 8080
# Token Handler: the BFF does the OIDC code flow server-side against Keycloak's
# PUBLIC issuer (https://auth.geowealth.int:5180) so the `iss` claim matches the
# browser-side authorize redirect. That host serves an mkcert-signed cert, so we
# import the mkcert root CA (mounted at /certs/mkcert-rootCA.pem) into the JRE
# trust store at startup. No-op if the CA isn't mounted.
ENTRYPOINT ["/bin/sh", "-c", "if [ -f /certs/mkcert-rootCA.pem ]; then keytool -importcert -noprompt -alias mkcert -file /certs/mkcert-rootCA.pem -cacerts -storepass changeit >/dev/null 2>&1 || true; fi; exec java -jar /app/billing-bff.jar"]
