/*
 * The contracts of Wow's published api modules, resolved by their feature as a build outside Wow's resolves them from
 * Maven Central: `requireFeature("contract")` on each module's coordinates, with no Wow plugin. `publicationConsumerCheck`
 * (build.gradle.kts) runs it against the scratch repository `publicationCheck` published, with the modules and the
 * version as Gradle properties; it is no project of the repository's build.
 */
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        maven(providers.gradleProperty("wow.consumer.repository").get())
        mavenCentral()
    }
}

rootProject.name = "contracts"
