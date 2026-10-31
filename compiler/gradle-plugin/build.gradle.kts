plugins {
    `java-gradle-plugin`
}

description = "Wow Gradle plugin: applies KSP and wow-compiler, and assembles the Manifest with Jackson-introspected schemas"

gradlePlugin {
    plugins {
        create("wow") {
            id = "me.ahoo.wow"
            implementationClass = "me.ahoo.wow.gradle.WowPlugin"
            displayName = "Wow domain module"
            description = "Builds a Wow domain module: its models with KSP and wow-compiler, its Manifest fragment and " +
                "its peer contract"
        }
        create("wowApi") {
            id = "me.ahoo.wow.api"
            implementationClass = "me.ahoo.wow.gradle.WowApiPlugin"
            displayName = "Wow api module"
            description = "Builds a Wow api module: the peer contract of the domain modules it declares and the " +
                "<Context>Contract and <State>View code generated from it"
        }
        create("wowService") {
            id = "me.ahoo.wow.service"
            implementationClass = "me.ahoo.wow.gradle.WowServicePlugin"
            displayName = "Wow service module"
            description = "Builds a Wow service module: a domain module's role on its own sources, the service's " +
                "Manifest, its OpenAPI document and the Manifest compatibility gate"
        }
    }
}

dependencies {
    // Nothing of Wow: every task that reads or writes a Manifest runs wow-manifest-tools in a worker (wowTools), so a
    // build of Wow itself can include the plugin (build-logic/wow-gradle-plugin) and apply it to its own projects.
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:${libs.versions.ksp.get()}")

    // The functional test reads the Manifests it builds, and checks the fingerprint against the kernel.
    testImplementation(project(":manifest"))
    testImplementation(project(":manifest-tools"))
    testImplementation(project(":kernel"))
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
    fixtureCompileClasspath(project(":kernel"))
    fixtureCompileClasspath("com.fasterxml.jackson.core:jackson-annotations")
    fixtureCompileClasspath(platform(project(":wow-dependencies")))
    fixtureProcessorClasspath(project(":compiler"))
    fixtureProcessorClasspath(platform(project(":wow-dependencies")))
    fixtureToolsClasspath(project(":manifest-tools"))
}


tasks.pluginUnderTestMetadata {
    pluginClasspath.from(functionalPluginClasspath)
}

tasks.processResources {
    val version = project.version.toString()
    // The Kotlin Wow builds with: the plugin requires the Kotlin Gradle plugin of one minor version before it at least.
    val kotlinVersion = libs.versions.kotlin.asProvider().get()
    inputs.property("version", version)
    inputs.property("kotlinVersion", kotlinVersion)
    filesMatching("**/wow-gradle-plugin.properties") {
        expand("version" to version, "kotlinVersion" to kotlinVersion)
    }
}

// The fixture compiles against wow-query-api too, for the query fields of its states (the compiler's <State>Properties
// and the api's generated <Context>Contract): the jar the tools bring, so the plugin declares no more projects.
val fixtureQueryApi: FileCollection = fixtureToolsClasspath.incoming.artifactView {
    componentFilter { it is ProjectComponentIdentifier && it.projectPath == ":query-api" }
}.files

// The Maven repository the TestKit fixtures resolve the BOM the plugin adds from: wow-bom of the plugin's version,
// constraining nothing, since the fixtures take the Wow modules as files.
val fixtureRepository = tasks.register("fixtureRepository") {
    val version = project.version.toString()
    val repository = layout.buildDirectory.dir("fixture-repository")
    inputs.property("version", version)
    outputs.dir(repository)
    doLast {
        val pom = repository.get().file("me/ahoo/wow/wow-bom/$version/wow-bom-$version.pom").asFile
        pom.parentFile.mkdirs()
        pom.writeText(
            "<project><modelVersion>4.0.0</modelVersion><groupId>me.ahoo.wow</groupId>" +
                "<artifactId>wow-bom</artifactId><version>$version</version><packaging>pom</packaging></project>\n",
        )
    }
}

tasks.test {
    dependsOn(fixtureRepository)
    inputs.files(fixtureCompileClasspath, fixtureProcessorClasspath, fixtureToolsClasspath)
        .withPropertyName("fixtureClasspath")
        .withNormalizer(ClasspathNormalizer::class.java)
    val compileClasspath = fixtureCompileClasspath + fixtureQueryApi
    val processorClasspath = fixtureProcessorClasspath
    val toolsClasspath = fixtureToolsClasspath
    val repository = layout.buildDirectory.dir("fixture-repository")
    doFirst {
        systemProperty("wow-test.fixture.repository", repository.get().asFile.toURI().toString())
        systemProperty("wow-test.fixture.compileClasspath", compileClasspath.asPath)
        systemProperty("wow-test.fixture.processorClasspath", processorClasspath.asPath)
        systemProperty("wow-test.fixture.toolsClasspath", toolsClasspath.asPath)
    }
}

