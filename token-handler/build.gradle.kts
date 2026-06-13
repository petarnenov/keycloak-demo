plugins {
    id("io.micronaut.application") version "4.4.2"
    id("application")
    id("com.gradleup.shadow") version "8.3.5"
}

version = "0.1"
group = "demo.tokenhandler"

repositories {
    mavenCentral()
}

dependencies {
    // The token-handler IS bff-core (no domain controller). bff-core carries the
    // OIDC/session/logout/token-refresh infra + the Bff main; its Micronaut deps
    // arrive transitively. Substituted from sibling source via includeBuild today;
    // from a registry after the repo split — same coordinate.
    implementation("demo.bff:bff-core:1.0.0")

    implementation("io.micronaut:micronaut-http-client")
    implementation("io.micronaut.reactor:micronaut-reactor")
    implementation("io.micronaut.security:micronaut-security-jwt")
    implementation("io.micronaut.security:micronaut-security-oauth2")
    implementation("io.micronaut.security:micronaut-security-session")
    implementation("io.micronaut.session:micronaut-session")
    implementation("io.micronaut:micronaut-runtime")
    implementation("io.micronaut.validation:micronaut-validation")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("jakarta.validation:jakarta.validation-api")

    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")
}

application {
    mainClass.set("demo.bff.core.Bff")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

micronaut {
    runtime("netty")
    testRuntime("junit5")
}
