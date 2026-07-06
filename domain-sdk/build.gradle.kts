plugins {
    // Library variant of the Micronaut plugin so the @Singleton/@Client bean
    // definitions are baked into the jar and a consuming BFF (or the
    // token-handler) discovers them as its own.
    id("io.micronaut.library") version "4.4.2"
    jacoco
}

version = "1.0.0"
group = "demo.bff"

repositories {
    mavenCentral()
}

// The THIN domain SDK: exactly the four classes a domain BFF (and the
// token-handler) share — HeaderIdentity, AuthClaims, PolicyRuleClient,
// PolicyRuleGate. Its whole job is the two integration contracts:
//   • read the X-Auth-* forward-auth headers (HeaderIdentity / AuthClaims)
//   • call authz-service /policy/* (PolicyRuleClient / PolicyRuleGate)
// so it deliberately does NOT pull the token-handler's heavy runtime
// (oauth2 / session / redis) — a domain BFF must stay auth-unaware.
dependencies {
    annotationProcessor("io.micronaut:micronaut-http-validation")
    annotationProcessor("io.micronaut.security:micronaut-security-annotations")

    // `api` so both consumers (domain BFFs + token-handler) inherit the compile
    // + runtime classpath the four classes need — and nothing more.
    api("io.micronaut:micronaut-http-client")          // PolicyRuleClient
    api("io.micronaut.reactor:micronaut-reactor")       // reactive http-client plumbing
    api("io.micronaut.security:micronaut-security")      // Authentication type + isAnonymous rules
    api("io.micronaut.serde:micronaut-serde-jackson")    // /policy/* JSON shapes

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.12.0")
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
}
