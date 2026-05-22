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
