pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "RetAlert"

include(":app")
include(":domain")
include(":data")
include(":reticulum")
include(":updater")

// LXMF-kt composite build. JitPack publishes no artifacts for LXMF-kt (no
// jitpack.yml install step), so the real `lxmf-core` is consumed from a local
// clone via Gradle included-build + dependency substitution. The included
// build's `:lxmf-core` project (group `com.github.torlando-tech.LXMF-kt`) is
// substituted in place of the published coordinate. :lxmf-examples /
// :conformance-bridge are gated off in the clone's settings (Shadow plugin is
// incompatible with Gradle 9), so only :lxmf-core is configured here.
// Mirrors the columba pattern.
includeBuild("../lxmf-kt") {
    dependencySubstitution {
        substitute(module("com.github.torlando-tech.LXMF-kt:lxmf-core")).using(project(":lxmf-core"))
    }
}