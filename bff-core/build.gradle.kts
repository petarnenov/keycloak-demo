plugins {
    // Library variant of the Micronaut plugin: runs Micronaut's annotation
    // processor so the bean definitions ($BeanDefinition + META-INF references)
    // are baked into the jar. That is what lets a consuming BFF discover the
    // @Filter / @Controller / @Singleton beans below as if they were its own.
    id("io.micronaut.library") version "4.4.2"
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
    api("io.micronaut:micronaut-runtime")
    api("io.micronaut.validation:micronaut-validation")
    api("io.micronaut.serde:micronaut-serde-jackson")
    api("jakarta.validation:jakarta.validation-api")
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
