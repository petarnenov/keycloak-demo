plugins {
    id("java")
}

version = "0.1"
group = "demo.keycloak"

repositories {
    mavenCentral()
}

val keycloakVersion = "26.0.7"

dependencies {
    compileOnly("org.keycloak:keycloak-core:${keycloakVersion}")
    compileOnly("org.keycloak:keycloak-server-spi:${keycloakVersion}")
    compileOnly("org.keycloak:keycloak-server-spi-private:${keycloakVersion}")
    compileOnly("org.keycloak:keycloak-model-storage:${keycloakVersion}")
    compileOnly("org.keycloak:keycloak-services:${keycloakVersion}")
    compileOnly("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
    compileOnly("org.jboss.logging:jboss-logging:3.5.3.Final")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("user-storage-spi")
    manifest {
        attributes(
            "Implementation-Title" to "GeoWealth user-service Keycloak SPI",
            "Implementation-Version" to project.version
        )
    }
}
