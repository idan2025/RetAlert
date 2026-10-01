plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
// One reticulum-kt version everywhere. lxmf-core (composite build) declares
// its own, and Gradle's conflict resolution can't meaningfully order a commit
// hash against a tag, so pin the catalog version explicitly.
subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "com.github.torlando-tech.reticulum-kt") {
                useVersion(rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs").findVersion("reticulumKt").get().requiredVersion)
            }
        }
    }
}
