plugins {
    // Library variant of the Micronaut plugin: runs Micronaut's annotation
    // processor so the bean definitions ($BeanDefinition + META-INF references)
    // are baked into the jar. That is what lets a consuming BFF discover the
    // @Filter / @Controller / @Singleton beans below as if they were its own.
    id("io.micronaut.library") version "4.4.2"
    // Line-coverage measurement for the shared auth/session/logout infra. The
    // beans here are plain POJO logic (no Micronaut context needed to unit-test
    // them), so the test suite is fast and the report is meaningful.
    jacoco
}

version = "1.0.0"
group = "demo.bff"

repositories {
    mavenCentral()
}

dependencies {
    annotationProcessor("io.micronaut:micronaut-http-validation")
    annotationProcessor("io.micronaut.security:micronaut-security-annotations")

    // `api` (not `implementation`) so consuming BFFs inherit these on the
    // runtime/compile classpath transitively — the shared infra needs them and
    // the domains would otherwise have to re-declare every one.
    api("io.micronaut:micronaut-http-client")
    api("io.micronaut.reactor:micronaut-reactor")
    api("io.micronaut.security:micronaut-security-jwt")
    api("io.micronaut.security:micronaut-security-oauth2")
    api("io.micronaut.security:micronaut-security-session")
    api("io.micronaut.session:micronaut-session")
    // B2: shared session + sid store. micronaut-redis-lettuce provides the
    // RedisSessionStore (replaces InMemorySessionStore) and a StatefulRedisConnection
    // bean used by SidSessionRegistry. Version managed by the Micronaut platform BOM.
    api("io.micronaut.redis:micronaut-redis-lettuce")
    api("io.micronaut:micronaut-runtime")
    // /health for Kubernetes readiness/liveness probes (and /info). Anonymous +
    // non-sensitive via each service's `endpoints.health` config. Without this the
    // management endpoints aren't registered and /health 404s.
    api("io.micronaut:micronaut-management")
    api("io.micronaut.validation:micronaut-validation")
    api("io.micronaut.serde:micronaut-serde-jackson")
    api("jakarta.validation:jakarta.validation-api")

    // Unit-test stack: JUnit 5 + Mockito + Reactor-test. The shared infra is
    // tested as plain units (Mockito for HttpClient / SessionStore / P1AuthzClient
    // collaborators), so no Micronaut application context is started — keeps the
    // suite fast and hermetic (no Keycloak, no P1).
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.12.0")
    testImplementation("io.projectreactor:reactor-test")
    // The token-refresh / subdomain filters touch session + server-side request
    // attribute plumbing whose helper classes live in micronaut-http-server.
    testImplementation("io.micronaut:micronaut-http-server")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

micronaut {
    version.set("4.3.0")
    processing {
        incremental(true)
        annotations("demo.bff.core.*")
    }
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    finalizedBy("jacocoTestReport")
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn("test")
    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(true)
    }
}

// Fails the build if line coverage of the shared infra drops below 75% — the
// same gate the geowealth neo.saml.idp package enforces. Run with:
//   ./gradlew test jacocoTestCoverageVerification
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn("test")
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.75".toBigDecimal()
            }
        }
    }
}
