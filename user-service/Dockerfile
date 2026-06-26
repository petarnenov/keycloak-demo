# user-service: Oracle DAO behind Keycloak's User Storage SPI.
# Stateless Micronaut fat-jar. Built from the user-service/ source tree only;
# unlike the domain BFFs it does NOT depend on bff-core, so the build context
# is the module dir.
FROM gradle:8.5-jdk17 AS builder
WORKDIR /build
COPY user-service/ user-service/
WORKDIR /build/user-service
RUN gradle --no-daemon shadowJar -x test

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /build/user-service/build/libs/*-all.jar /app/user-service.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/user-service.jar"]
