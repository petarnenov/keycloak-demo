// GeoWealth white-labeling Keycloak provider jar.
//
// Phase 2.b adds an HTTP client to the new GeoWealth branding API plus an
// in-memory TTL cache. The ThemeSelectorProvider and LoginFormsProvider
// subclass introduced in Phase 2.a stay identical — only the brand-lookup
// path changes (BrandRegistry → BrandingService that fans through cache →
// API → hardcoded fallback).
plugins {
    `java-library`
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.geowealth.keycloak"
version = "1.0.0"

val keycloakVersion = "26.0.7"
val jacksonVersion = "2.17.2"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Keycloak SPI surface — provided by the server at runtime.
    compileOnly("org.keycloak:keycloak-core:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-server-spi:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-server-spi-private:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-services:$keycloakVersion")
    compileOnly("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")

    // JSON parsing for the GeoWealth branding API response. RELOCATED into
    // this provider's own shaded package by shadowJar to avoid colliding
    // with the Jackson Keycloak loads on its provider classpath. Same
    // pattern as keycloak-provider/build.gradle.kts.
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.core:jackson-core:$jacksonVersion")
    implementation("com.fasterxml.jackson.core:jackson-annotations:$jacksonVersion")

    // Integration tests against the running stack — JUnit 5 + AssertJ.
    // Pure-Java HTTP via java.net.http (JDK 17), JSON via Jackson (which
    // tests pull directly, NOT through the shaded jar — the relocation
    // only fires for shadowJar artifacts, not for the regular test
    // classpath).
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // Integration tests reach out to localhost:8080 / localhost:8898 — keep
    // them off the default `build` task so a fresh clone doesn't fail
    // before the dev stack is up. Run explicitly via:
    //   ./gradlew :geowealth-keycloak:test
    //   ./gradlew :geowealth-keycloak:integrationTest   (alias below)
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    // Tests auto-skip when POC_BRANDING_API_TOKEN is unset (see
    // WhitelabelScenariosIT @BeforeAll); inherit the env from whoever
    // invoked gradle so direnv / .envrc / shell exports flow through.
    environment(System.getenv())
    // java.net.http.HttpClient treats Host as a "restricted" header by
    // default. The Keycloak E2E tests override Host to talk to a single
    // listener as different branded subdomains — exactly the pattern the
    // restriction guards against, and exactly what we want here. Unlock
    // it for this JVM only.
    systemProperty("jdk.httpclient.allowRestrictedHeaders", "host")
}

tasks.register("integrationTest") {
    description = "Alias for the JUnit-based whitelabel integration suite."
    group = "verification"
    dependsOn(tasks.test)
}

tasks.shadowJar {
    archiveBaseName.set("geowealth-keycloak")
    archiveClassifier.set("")
    // Relocate Jackson — see comment above on dependencies.
    relocate("com.fasterxml.jackson", "com.geowealth.keycloak.shaded.jackson")
    // Preserve META-INF/services/* so Keycloak still discovers both SPI
    // factories registered by this jar.
    mergeServiceFiles()
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}

// Don't run integration tests during a normal `build` — they need a
// running dev stack. Override the `check` lifecycle to skip `test`.
tasks.named("check") {
    setDependsOn(emptyList<Task>())
}
