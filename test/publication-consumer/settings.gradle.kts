/*
 * The consumer of a release (D363, G10): the three-module layout of documentation/docs/en/start/existing-project.md,
 * built against what `publicationCheck` published into its scratch repository, as a user's build resolves a release
 * from Maven Central. `publicationConsumerCheck` (build.gradle.kts) runs it with the scratch repository and the versions
 * as Gradle properties; it is no project of the repository's build.
 */
pluginManagement {
    val repository: String = providers.gradleProperty("wow.consumer.repository").get()
    val wowVersion: String = providers.gradleProperty("wow.consumer.version").get()
    val kotlinVersion: String = providers.gradleProperty("wow.consumer.kotlin").get()
    repositories {
        maven(repository)
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("jvm") version kotlinVersion
        id("me.ahoo.wow") version wowVersion
        id("me.ahoo.wow.api") version wowVersion
        id("me.ahoo.wow.service") version wowVersion
    }
}

dependencyResolutionManagement {
    repositories {
        maven(providers.gradleProperty("wow.consumer.repository").get())
        mavenCentral()
    }
}

rootProject.name = "catalog"
include("catalog-api", "catalog-domain", "catalog-server")
