plugins {
    `java-gradle-plugin`
}

description = "Wow Gradle plugin: applies KSP and wow-compiler, and assembles the Manifest with Jackson-introspected schemas"

gradlePlugin {
    plugins {
        create("wow") {
            id = "me.ahoo.wow"
            implementationClass = "me.ahoo.wow.gradle.WowPlugin"
            displayName = "Wow"
            description = project.description
        }
    }
}

dependencies {
    implementation(project(":wow-manifest"))
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:${libs.versions.ksp.get()}")

    // The functional test reads the Manifests it builds, and checks the fingerprint against the kernel.
    testImplementation(project(":wow-manifest-tools"))
    testImplementation(project(":wow-kernel"))
    // In-process plugin tests (ProjectBuilder) apply the Kotlin plugin the Wow plugin reacts to.
    testImplementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.asProvider().get()}")
}

// What the TestKit fixture builds with: the Kotlin Gradle plugin the fixture applies, the Wow modules it compiles
// against and processes with, and wow-manifest-tools (none is published yet, so the fixture takes them as files).
val functionalPluginClasspath: Configuration = configurations.create("functionalPluginClasspath")
val fixtureCompileClasspath: Configuration = configurations.create("fixtureCompileClasspath")
val fixtureProcessorClasspath: Configuration = configurations.create("fixtureProcessorClasspath")
val fixtureToolsClasspath: Configuration = configurations.create("fixtureToolsClasspath")
listOf(functionalPluginClasspath, fixtureCompileClasspath, fixtureProcessorClasspath, fixtureToolsClasspath).forEach { configuration ->
    configuration.isCanBeConsumed = false
    configuration.attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}
functionalPluginClasspath.attributes {
    attribute(
        GradlePluginApiVersion.GRADLE_PLUGIN_API_VERSION_ATTRIBUTE,
        objects.named(GradleVersion.current().version),
    )
}
dependencies {
    functionalPluginClasspath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.asProvider().get()}")
    // The generated models build on the kernel, which brings wow-api.
    fixtureCompileClasspath(project(":wow-kernel"))
    fixtureCompileClasspath("com.fasterxml.jackson.core:jackson-annotations")
    fixtureCompileClasspath(platform(project(":wow-dependencies")))
    fixtureProcessorClasspath(project(":wow-compiler"))
    fixtureProcessorClasspath(platform(project(":wow-dependencies")))
    fixtureToolsClasspath(project(":wow-manifest-tools"))
}


tasks.pluginUnderTestMetadata {
    pluginClasspath.from(functionalPluginClasspath)
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("**/wow-gradle-plugin.properties") {
        expand("version" to version)
    }
}

tasks.test {
    inputs.files(fixtureCompileClasspath, fixtureProcessorClasspath, fixtureToolsClasspath)
        .withPropertyName("fixtureClasspath")
        .withNormalizer(ClasspathNormalizer::class.java)
    val compileClasspath = fixtureCompileClasspath
    val processorClasspath = fixtureProcessorClasspath
    val toolsClasspath = fixtureToolsClasspath
    doFirst {
        systemProperty("wow.fixture.compileClasspath", compileClasspath.asPath)
        systemProperty("wow.fixture.processorClasspath", processorClasspath.asPath)
        systemProperty("wow.fixture.toolsClasspath", toolsClasspath.asPath)
    }
}

