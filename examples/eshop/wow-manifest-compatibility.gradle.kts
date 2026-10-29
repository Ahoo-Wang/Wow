/*
 * The Manifest compatibility gate of an example server (decision D76), as the Gradle plugin `me.ahoo.wow` provides it
 * to an application: the plugin cannot be applied to a project of the build that produces it, so a server of the
 * example applies this script instead. Its service's name is derived as the plugin derives it: the one service the
 * contracts of the contexts it hosts name (their domain modules' `wowContractService`, the plugin's
 * `wow { contract { service } }`), the project's name when none does; `extra["wowService"]` names it explicitly, the
 * plugin's `wow { serviceName }`.
 *
 * - `mergeWowManifestWithSchemas` merges the Manifest fragments of the server's runtime class path, with their schemas,
 *   into `build/wow/compatibility/manifest.json`, as the plugin's `mergeWowManifest` does (`ManifestTools.merge`).
 * - `checkManifestCompatibility`, part of `check`, compares it with the committed baseline `manifest/baseline.json`
 *   (`ManifestTools.checkCompatibility`): a change that breaks the contract within the baseline's major version fails
 *   the build with each breaking change and its path; `build/wow/manifest-compatibility.txt` lists every change.
 * - `updateManifestBaseline` records the merged Manifest as the baseline; commit it.
 * - `checkManifestBaseline`, part of `check`, fails when the merged Manifest is not byte for byte the committed
 *   baseline, so a change the compatibility check only reports (an additive one, a dropped `derives`) cannot leave
 *   the baseline, and the clients generated from it, behind.
 *
 * As the plugin's workers do, the tools run in a class loader of their own over `:manifest-tools` and its runtime class
 * path, with the JDK's platform class loader as parent, so none of them reaches the build script's class path.
 */
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

// The project's own extra only, as the plugin's `wow { serviceName }`: no -P, gradle.properties or parent value.
val wowService: String? = if (extra.has("wowService")) extra["wowService"].toString() else null

val wowManifestTools: Configuration = configurations.maybeCreate("wowManifestTools").apply {
    description = "The tools the Manifest tasks run: :manifest-tools and its runtime class path."
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}
dependencies {
    add(wowManifestTools.name, project(":manifest-tools"))
}

/** Calls [method] of `ManifestTools` in a class loader over [tools], rethrowing what it throws. */
fun manifestTools(tools: Set<File>, method: String, vararg arguments: Any?): Any? =
    URLClassLoader(tools.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
        val function = Class.forName("me.ahoo.wow.manifest.tools.ManifestTools", true, loader).methods
            .single { it.name == method }
        try {
            function.invoke(null, *arguments)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

val manifestFile = layout.buildDirectory.file("wow/compatibility/manifest.json")

// The server's own output too: a server that declares service routes adds its fragment to its context.
val serverOutput = the<SourceSetContainer>().named("main").map { it.output }
val baselineFile = layout.projectDirectory.file("manifest/baseline.json")

val mergeWowManifestWithSchemas = tasks.register("mergeWowManifestWithSchemas") {
    group = "wow"
    description = "Merges the Manifest fragments of the runtime class path, with their schemas, into the service's Manifest."
    val classpath = files(configurations.named("runtimeClasspath"), serverOutput)
    val tools = wowManifestTools
    val service = wowService
    // The project path, as the plugin names a service (D363 ②).
    val defaultService = path.removePrefix(":").replace(':', '-')
    val version = project.version.toString()
    val output = manifestFile
    inputs.files(classpath).withNormalizer(ClasspathNormalizer::class.java).withPropertyName("classpath")
    inputs.files(tools).withNormalizer(ClasspathNormalizer::class.java).withPropertyName("tools")
    inputs.property("service", service.orEmpty())
    inputs.property("defaultService", defaultService)
    inputs.property("version", version)
    outputs.file(output)
    outputs.cacheIf { true }
    doLast {
        manifestTools(
            tools.files,
            "merge",
            classpath.files.toList(),
            emptyMap<String, String>(),
            service,
            defaultService,
            version,
            output.get().asFile,
        )
    }
}

val updateManifestBaseline = tasks.register("updateManifestBaseline") {
    group = "wow"
    description = "Records the merged Manifest as the baseline checkManifestCompatibility compares with."
    val manifest = mergeWowManifestWithSchemas.map { manifestFile.get() }
    val baseline = baselineFile
    inputs.file(manifest).withPathSensitivity(PathSensitivity.NONE).withPropertyName("manifest")
    outputs.file(baseline)
    doLast {
        manifest.get().asFile.copyTo(baseline.asFile, overwrite = true)
    }
}

val checkManifestCompatibility = tasks.register("checkManifestCompatibility") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails on a change of the merged Manifest that breaks its baseline within the major version."
    val manifest = mergeWowManifestWithSchemas.map { manifestFile.get() }
    val baseline = baselineFile
    val tools = wowManifestTools
    val report = layout.buildDirectory.file("wow/manifest-compatibility.txt")
    inputs.file(manifest).withPathSensitivity(PathSensitivity.NONE).withPropertyName("manifest")
    inputs.files(baseline).withPathSensitivity(PathSensitivity.NONE).withPropertyName("baseline")
    inputs.files(tools).withNormalizer(ClasspathNormalizer::class.java).withPropertyName("tools")
    outputs.file(report)
    outputs.cacheIf { true }
    // Recording the baseline and checking against it in one build checks the new baseline.
    mustRunAfter(updateManifestBaseline)
    doLast {
        manifestTools(tools.files, "checkCompatibility", baseline.asFile, manifest.get().asFile, report.get().asFile)
    }
}

val checkManifestBaseline = tasks.register("checkManifestBaseline") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails when the merged Manifest differs from the committed baseline: run updateManifestBaseline."
    val manifest = mergeWowManifestWithSchemas.map { manifestFile.get() }
    val baseline = baselineFile
    val path = rootDir.toPath().relativize(baselineFile.asFile.toPath()).toString()
    val update = "${project.path}:updateManifestBaseline"
    inputs.file(manifest).withPathSensitivity(PathSensitivity.NONE).withPropertyName("manifest")
    inputs.files(baseline).withPathSensitivity(PathSensitivity.NONE).withPropertyName("baseline")
    mustRunAfter(updateManifestBaseline)
    doLast {
        val recorded = baseline.asFile
        if (!recorded.isFile || !recorded.readBytes().contentEquals(manifest.get().asFile.readBytes())) {
            throw GradleException(
                "$path does not record the Manifest this build produces: run $update, review the diff and " +
                    "commit it.",
            )
        }
    }
}

tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
    dependsOn(checkManifestCompatibility)
    dependsOn(checkManifestBaseline)
}
