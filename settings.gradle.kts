pluginManagement {
    repositories {
        // Google's mirror of Maven Central first: GitHub runners share IPs and Maven Central
        // rate-limits them (HTTP 429 on the first release build, 2026-11-22).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central first: GitHub runners share IPs and Maven Central
        // rate-limits them (HTTP 429 on the first release build, 2026-11-22).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "Boop"
include(":app")
