# authz-service: read-only Oracle DAO answering the PolicyRule decision questions
# (/policy/capabilities|can|refine). The demo's PolicyRuleManager-as-a-service —
# it removes P1 from the runtime authz path (alignment plan Phase A0).
# Stateless Micronaut fat-jar. Built from the authz-service/ source tree only;
# like user-service it does NOT depend on bff-core, so the build context is the
# module dir.
FROM gradle:8.5-jdk17 AS builder
WORKDIR /build
COPY authz-service/ authz-service/
WORKDIR /build/authz-service
RUN gradle --no-daemon shadowJar -x test

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /build/authz-service/build/libs/*-all.jar /app/authz-service.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/authz-service.jar"]
