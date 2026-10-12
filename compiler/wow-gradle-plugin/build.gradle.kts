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

// The tools the plugin's workers run (decision D83): a source set of its own, published as the plugin's `tools`
// variant (capability me.ahoo.wow:wow-gradle-plugin-tools). The plugin resolves it into the build's project and loads it
// in an isolated class loader, so victools, Jackson, Kotlin reflection and the kernel never reach a build script's
// class path; the plugin itself depends on KSP and wow-manifest only.
val tools: SourceSet = sourceSets.create("tools")
java {
    registerFeature("tools") {
        usingSourceSet(tools)
    }
}

// The tests read the tools' internals.
kotlin.target.compilations.named("test") {
    associateWith(kotlin.target.compilations.getByName("tools"))
}

dependencies {
    implementation(project(":wow-manifest"))
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:${libs.versions.ksp.get()}")

    "toolsImplementation"(platform(project(":wow-dependencies")))
    "toolsImplementation"(project(":wow-manifest"))
    // The state schema fingerprint is the kernel's own (EventCodec.stateSchemaFingerprint), computed at build time.
    "toolsImplementation"(project(":wow-kernel"))
    // Build time only (decision D73): schemas are introspected in an isolated worker, never by a runtime module.
    "toolsImplementation"("com.github.victools:jsonschema-generator")
    "toolsImplementation"("com.github.victools:jsonschema-module-jackson")
    "toolsImplementation"("tools.jackson.module:jackson-module-kotlin")
    "toolsImplementation"(kotlin("reflect"))

    testImplementation(tools.output)
    testImplementation(project(":wow-kernel"))
    testImplementation("com.github.victools:jsonschema-generator")
    testImplementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation("com.fasterxml.jackson.core:jackson-annotations")
}

// What the TestKit fixture builds with: the Kotlin Gradle plugin the fixture applies, the Wow modules it compiles
// against and processes with, and the plugin's tools (none is published yet, so the fixture takes them as files).
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

val fixtureToolsClasspath = files(tasks.named("toolsJar"), tools.runtimeClasspath)

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
    doFirst {
        systemProperty("wow.fixture.compileClasspath", compileClasspath.asPath)
        systemProperty("wow.fixture.processorClasspath", processorClasspath.asPath)
        systemProperty("wow.fixture.toolsClasspath", fixtureToolsClasspath.asPath)
    }
}

// Detekt reads src/main and src/test by default; the tools are production code too.
detekt {
    source.from("src/tools/kotlin")
}
