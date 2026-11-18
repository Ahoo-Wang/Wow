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

// Nothing of Wow: every task that reads or writes a Manifest runs wow-manifest-tools in a worker (wowTools), so a
// build of Wow itself can include the plugin (build-logic/wow-gradle-plugin) and apply it to its own projects. No KSP
// either: the plugin applies KSP by its id, and the markers of the domain and service plugins bring it (below), so
// `me.ahoo.wow.api` on a root build's class path leaves a 9.x module free to apply KSP of its own version.
val ksp = "com.google.devtools.ksp:symbol-processing-gradle-plugin:${libs.versions.ksp.get()}"
dependencies {
    // The functional test reads the Manifests it builds, and checks the fingerprint against the kernel.
    testImplementation(project(":manifest"))
    testImplementation(project(":manifest-tools"))
    testImplementation(project(":kernel"))
    // In-process plugin tests (ProjectBuilder) apply the Kotlin plugin the Wow plugin reacts to.
    testImplementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.asProvider().get()}")
    // ... and the KSP plugin the domain role applies, which a domain module's marker brings.
    testImplementation(ksp)
}

// What the TestKit fixture builds with: the Kotlin Gradle plugin the fixture applies, the Wow modules it compiles
// against and processes with, and wow-manifest-tools (none is published yet, so the fixture takes them as files).
val functionalPluginClasspath: Configuration = configurations.create("functionalPluginClasspath")
// The KSP the markers of the domain and service plugins bring, which the TestKit fixtures' domain modules apply.
val functionalKspClasspath: Configuration = configurations.create("functionalKspClasspath")
val fixtureCompileClasspath: Configuration = configurations.create("fixtureCompileClasspath")
val fixtureProcessorClasspath: Configuration = configurations.create("fixtureProcessorClasspath")
val fixtureToolsClasspath: Configuration = configurations.create("fixtureToolsClasspath")
listOf(functionalPluginClasspath, functionalKspClasspath, fixtureCompileClasspath, fixtureProcessorClasspath, fixtureToolsClasspath).forEach { configuration ->
    configuration.isCanBeConsumed = false
    configuration.attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}
listOf(functionalPluginClasspath, functionalKspClasspath).forEach { configuration ->
    configuration.attributes {
        attribute(
            GradlePluginApiVersion.GRADLE_PLUGIN_API_VERSION_ATTRIBUTE,
            objects.named(GradleVersion.current().version),
        )
    }
}
// What the TestKit fixtures' builds take from Maven Central, at the catalog's versions they are given (the system
// properties below): KSP's processors and its plugin's marker, and the standard library, JUnit and fluent-assert a
// fixture's tests run on. The Kotlin compiler they resolve is this project's own (kotlinBuildToolsApiClasspath and
// kotlinCompilerPluginClasspathMain, of the same Kotlin Gradle plugin). The test resolves them here, outside the build's
// platform (TOOL_CLASSPATH), so this build's dependency cache holds the versions the fixtures ask for, and the fixtures
// read that cache (GRADLE_RO_DEP_CACHE) instead of asking Central once per build. A fixture that takes another module
// from Central declares it here, of a catalog version.
val fixtureExternalKspClasspath: Configuration = configurations.create("fixtureExternalKspClasspath")
val fixtureExternalTestClasspath: Configuration = configurations.create("fixtureExternalTestClasspath")
listOf(fixtureExternalKspClasspath, fixtureExternalTestClasspath).forEach { configuration ->
    configuration.isCanBeConsumed = false
    configuration.attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}
val kspPlugin = libs.plugins.ksp.get()
dependencies {
    fixtureExternalKspClasspath(libs.ksp.symbol.processing.api)
    fixtureExternalKspClasspath(libs.ksp.symbol.processing.aa.embeddable)
    fixtureExternalKspClasspath(libs.ksp.symbol.processing.common.deps)
    fixtureExternalKspClasspath("${kspPlugin.pluginId}:${kspPlugin.pluginId}.gradle.plugin:${kspPlugin.version}")
    fixtureExternalTestClasspath("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.asProvider().get()}")
    fixtureExternalTestClasspath(platform(libs.junit.bom))
    fixtureExternalTestClasspath("org.junit.jupiter:junit-jupiter")
    fixtureExternalTestClasspath("org.junit.platform:junit-platform-launcher")
    fixtureExternalTestClasspath(platform(libs.fluent.assert.bom))
    fixtureExternalTestClasspath("me.ahoo.test:fluent-assert-core")
}
val fixtureExternalClasspath: FileCollection = files(
    fixtureExternalKspClasspath,
    fixtureExternalTestClasspath,
    configurations.named("kotlinBuildToolsApiClasspath"),
    configurations.named("kotlinCompilerPluginClasspathMain"),
)

dependencies {
    functionalPluginClasspath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.asProvider().get()}")
    functionalKspClasspath(ksp)
    // The generated models build on the kernel, which brings wow-api.
    fixtureCompileClasspath(project(":kernel"))
    fixtureCompileClasspath("com.fasterxml.jackson.core:jackson-annotations")
    fixtureCompileClasspath(platform(project(":wow-dependencies")))
    fixtureProcessorClasspath(project(":compiler"))
    fixtureProcessorClasspath(platform(project(":wow-dependencies")))
    fixtureToolsClasspath(project(":manifest-tools"))
}


tasks.pluginUnderTestMetadata {
    pluginClasspath.from(functionalPluginClasspath, functionalKspClasspath)
}

// The markers of the plugins whose role applies KSP, `me.ahoo.wow` and `me.ahoo.wow.service`, bring the KSP Gradle
// plugin; `me.ahoo.wow.api`'s brings the plugin's jar alone. Gradle resolves a plugin request through its marker, so
// the class path a build script declares a plugin on holds KSP only for these two.
val kspMarkers = listOf("wow", "wowService").map { "${it}PluginMarkerMaven" }
// After evaluation, once java-gradle-plugin has created the markers: its own withXml, which adds the dependency on the
// plugin's jar, then runs before this one.
afterEvaluate {
    extensions.findByType<PublishingExtension>()?.apply {
        publications.withType<MavenPublication>().matching { it.name in kspMarkers }.configureEach {
            pom.withXml {
                val document = asElement().ownerDocument
                val dependencies = checkNotNull(asElement().getElementsByTagName("dependencies").item(0)) {
                    "The marker $name has no dependency on the plugin's jar to add KSP beside"
                }
                val dependency = dependencies.appendChild(document.createElement("dependency"))
                ksp.split(':').zip(listOf("groupId", "artifactId", "version")).forEach { (value, tag) ->
                    dependency.appendChild(document.createElement(tag)).textContent = value
                }
            }
        }
    }
}

tasks.processResources {
    val version = project.version.toString()
    // The Kotlin Wow builds with: the plugin requires the Kotlin Gradle plugin of one minor version before it at least.
    val kotlinVersion = libs.versions.kotlin.asProvider().get()
    val kspVersion = libs.versions.ksp.get()
    inputs.property("version", version)
    inputs.property("kotlinVersion", kotlinVersion)
    inputs.property("kspVersion", kspVersion)
    filesMatching("**/wow-gradle-plugin.properties") {
        expand("version" to version, "kotlinVersion" to kotlinVersion, "kspVersion" to kspVersion)
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
    // What `me.ahoo.wow.api`'s marker resolves, the plugin's jar and its dependencies, with the Kotlin Gradle plugin a
    // root build declares beside it (StagedMigrationFunctionalTest).
    val apiClasspath = files(tasks.jar, configurations.runtimeClasspath, functionalPluginClasspath)
    inputs.files(apiClasspath).withPropertyName("apiClasspath").withNormalizer(ClasspathNormalizer::class.java)
    systemProperty("wow-test.ksp.version", libs.versions.ksp.get())
    systemProperty("wow-test.junit.version", libs.versions.junit.get())
    systemProperty("wow-test.fluent-assert.version", libs.versions.fluent.assert.get())
    // The fixtures' builds read this build's dependency cache, which holds what they take from Central
    // (fixtureExternalClasspath), so a build of a fresh TestKit directory asks Central for none of it. Read-only:
    // no fixture writes it. The TestKit daemons inherit the variable from the test JVM.
    inputs.files(fixtureExternalClasspath).withPropertyName("fixtureExternalClasspath")
        .withNormalizer(ClasspathNormalizer::class.java)
    environment("GRADLE_RO_DEP_CACHE", gradle.gradleUserHomeDir.resolve("caches").path)
    // The installation a test runs a Tooling API build on, in a Gradle home of its own (WowContractSourcesFunctionalTest).
    systemProperty("wow-test.gradle.home", requireNotNull(gradle.gradleHomeDir).path)
    // The POMs of the plugin markers, which say what a plugin request resolves.
    val markers = layout.buildDirectory.dir("publications")
    dependsOn(
        tasks.matching { it.name.startsWith("generatePomFileFor") && it.name.endsWith("PluginMarkerMavenPublication") },
    )
    doFirst {
        systemProperty("wow-test.plugin.markers", markers.get().asFile.path)
        systemProperty("wow-test.plugin.apiClasspath", apiClasspath.asPath)
        systemProperty("wow-test.fixture.repository", repository.get().asFile.toURI().toString())
        systemProperty("wow-test.fixture.compileClasspath", compileClasspath.asPath)
        systemProperty("wow-test.fixture.processorClasspath", processorClasspath.asPath)
        systemProperty("wow-test.fixture.toolsClasspath", toolsClasspath.asPath)
    }
}

