rootProject.name = "user-me-lib"

// Maven Central'in Google mirror'i ilk sirada: JitPack'in paylasimli IP'leri
// repo.maven.apache.org'dan 429 (Too Many Requests) yiyebiliyor.
pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
