FROM gradle:8.5-jdk17 AS builder
WORKDIR /app

COPY settings.gradle build.gradle.kts gradle.properties ./
RUN gradle --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN gradle --no-daemon shadowJar -x test

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/build/libs/*-all.jar /app/trading-bff.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/trading-bff.jar"]
