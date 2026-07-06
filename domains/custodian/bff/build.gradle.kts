plugins {
    id("io.micronaut.application") version "4.4.2"
    id("application")
    id("com.gradleup.shadow") version "8.3.5"
}

version = "0.1"
group = "demo.custodian"

repositories {
    mavenCentral()
}

// Forward-auth data BFF: auth-UNAWARE. It only reads the X-Auth-* headers
// (HeaderIdentity) and runs the Tier-3 list refine (PolicyRuleGate ->
// authz-service). All session/login/logout/token-refresh happens in the
// token-handler, so this module depends ONLY on the thin `demo.bff:domain-sdk`
// and a plain server runtime — no oauth2 / session / redis / security-jwt.
dependencies {
    annotationProcessor("io.micronaut:micronaut-http-validation")

    implementation("demo.bff:domain-sdk:1.0.0")
    implementation("io.micronaut:micronaut-runtime")
    // /health for K8s probes.
    implementation("io.micronaut:micronaut-management")

    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")
}

application {
    mainClass.set("demo.custodian.Application")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

micronaut {
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("demo.custodian.*")
    }
}
