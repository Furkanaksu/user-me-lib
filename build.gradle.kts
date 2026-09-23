plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
    `maven-publish`
    // DIKKAT: ktor application / fatjar plugin'i YOK - bu bir kutuphane, calistirilabilir uygulama degil.
}

group = "com.furkan"
version = "1.1.0"

kotlin {
    jvmToolchain(21)
}

java {
    withSourcesJar()
}

dependencies {
    // api: tuketen projenin de derleme sirasinda gordugu tipler
    // (Route, Database, authenticate(...), @Serializable modeller)
    api(libs.ktor.server.core)
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.exposed.java.time)
    api(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.content.negotiation)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.h2)
    testImplementation(libs.logback.classic)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "user-me-lib"
        }
    }
}
