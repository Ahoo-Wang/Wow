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
    // The state schema fingerprint is the kernel's own (EventCodec.stateSchemaFingerprint), computed at build time.
    implementation(project(":wow-kernel"))
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:${libs.versions.ksp.get()}")
    // Build time only (decision D73): schemas are introspected in an isolated worker, never by a runtime module.
    implementation("com.github.victools:jsonschema-generator")
    implementation("com.github.victools:jsonschema-module-jackson")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation(kotlin("reflect"))
    testImplementation("com.fasterxml.jackson.core:jackson-annotations")
}

// What the TestKit fixture builds with: the Kotlin Gradle plugin the fixture applies, and the Wow modules it
// compiles against and processes with (they are not published yet, so the fixture takes them as files).
val functionalPluginClasspath: Configuration = configurations.create("functionalPluginClasspath")
val fixtureCompileClasspath: Configuration = configurations.create("fixtureCompileClasspath")
val fixtureProcessorClasspath: Configuration = configurations.create("fixtureProcessorClasspath")
listOf(functionalPluginClasspath, fixtureCompileClasspath, fixtureProcessorClasspath).forEach { configuration ->
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
    inputs.files(fixtureCompileClasspath, fixtureProcessorClasspath).withPropertyName("fixtureClasspath")
        .withNormalizer(ClasspathNormalizer::class.java)
    val compileClasspath = fixtureCompileClasspath
    val processorClasspath = fixtureProcessorClasspath
    doFirst {
        systemProperty("wow.fixture.compileClasspath", compileClasspath.asPath)
        systemProperty("wow.fixture.processorClasspath", processorClasspath.asPath)
    }
}
