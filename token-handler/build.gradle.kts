plugins {
    id("io.micronaut.application") version "4.4.2"
    id("application")
    id("com.gradleup.shadow") version "8.3.5"
    jacoco
}

version = "0.1"
group = "demo.tokenhandler"

repositories {
    mavenCentral()
}

// The token-handler now OWNS the auth/session/logout code (formerly bff-core) as
// its own source — it was the only runtime consumer of that half. It depends on
// the thin `domain-sdk` for the two shared classes it still uses (AuthClaims,
// PolicyRuleClient). The heavy runtime (security-jwt/oauth2/session, redis,
// management) that bff-core used to hand down transitively is declared here now.
dependencies {
    annotationProcessor("io.micronaut:micronaut-http-validation")
    annotationProcessor("io.micronaut.security:micronaut-security-annotations")

    implementation("demo.bff:domain-sdk:1.0.0")

    implementation("io.micronaut:micronaut-http-client")
    implementation("io.micronaut.reactor:micronaut-reactor")
    implementation("io.micronaut.security:micronaut-security-jwt")
    implementation("io.micronaut.security:micronaut-security-oauth2")
    implementation("io.micronaut.security:micronaut-security-session")
    implementation("io.micronaut.session:micronaut-session")
    // Shared session + sid store (B2): RedisSessionStore + StatefulRedisConnection
    // for SidSessionRegistry. Was inherited via bff-core's api() before.
    implementation("io.micronaut.redis:micronaut-redis-lettuce")
    implementation("io.micronaut:micronaut-runtime")
    // /health for K8s probes — was inherited via bff-core.
    implementation("io.micronaut:micronaut-management")
    implementation("io.micronaut.validation:micronaut-validation")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("jakarta.validation:jakarta.validation-api")

    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.12.0")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("io.micronaut:micronaut-http-server")
}

application {
    mainClass.set("demo.bff.core.Bff")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

micronaut {
    version.set("4.3.0")
    runtime("netty")
    testRuntime("junit5")
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
}
