/*
 * The Manifest compatibility gate of an example server (decision D76), as the Gradle plugin `me.ahoo.wow` provides it
 * to an application: the plugin cannot be applied to a project of the build that produces it, so a server of the
 * example applies this script instead, after setting `extra["wowService"]` to its service name.
 *
 * - `mergeWowManifestWithSchemas` merges the Manifest fragments of the server's runtime class path, with their schemas,
 *   into `build/wow/compatibility/manifest.json`, as the plugin's `mergeWowManifest` does (`ManifestTools.merge`);
 *   `wow-peers.gradle.kts` leaves the schemas out of the Manifest it offers the peers.
 * - `checkManifestCompatibility`, part of `check`, compares it with the committed baseline `manifest/baseline.json`
 *   (`ManifestTools.checkCompatibility`): a change that breaks the contract within the baseline's major version fails
 *   the build with each breaking change and its path; `build/wow/manifest-compatibility.txt` lists every change.
 * - `updateManifestBaseline` records the merged Manifest as the baseline; commit it.
 *
 * As the plugin's workers do, the tools run in a class loader of their own over `:manifest-tools` and its runtime class
 * path, with the JDK's platform class loader as parent, so none of them reaches the build script's class path.
 */
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

val wowService = extra["wowService"] as String

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
val baselineFile = layout.projectDirectory.file("manifest/baseline.json")

val mergeWowManifestWithSchemas = tasks.register("mergeWowManifestWithSchemas") {
    group = "wow"
    description = "Merges the Manifest fragments of the runtime class path, with their schemas, into the service's Manifest."
    val classpath = configurations.named("runtimeClasspath")
    val tools = wowManifestTools
    val service = wowService
    val version = project.version.toString()
    val output = manifestFile
    inputs.files(classpath).withNormalizer(ClasspathNormalizer::class.java).withPropertyName("classpath")
    inputs.files(tools).withNormalizer(ClasspathNormalizer::class.java).withPropertyName("tools")
    inputs.property("service", service)
    inputs.property("version", version)
    outputs.file(output)
    outputs.cacheIf { true }
    doLast {
        manifestTools(tools.files, "merge", classpath.get().files.toList(), service, version, output.get().asFile)
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

tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
    dependsOn(checkManifestCompatibility)
}
