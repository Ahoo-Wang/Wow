/*
 * The completed Manifest fragment of an example domain module (decision D73), as the Gradle plugin `me.ahoo.wow`
 * provides it to an application's module: the plugin cannot be applied to a project of the build that produces it, so
 * a domain module of the example applies KSP with `:compiler` by hand and this script after it.
 *
 * `generateWowManifestFragment` runs what the plugin's task of that name runs, `ManifestTools.completeFragment` of
 * `:manifest-tools`, on the fragment KSP wrote: it fills in the state, command and event schemas (`$defs`, with their
 * `x-wow-*` query keywords) and the state fingerprints. The completed fragment takes the place of the compiler's in the
 * module's resources, so the Manifest a server merges from its runtime class path carries them.
 *
 * As the plugin's worker does, the tools run in a class loader of their own over the module's classes and runtime class
 * path first, then the tools' (victools, Jackson, the kernel), with the JDK's platform class loader as parent: none of
 * them reaches the build script's class path, and the module's classes are read with the Jackson they run with.
 */
import java.net.URLClassLoader

val fragmentDirectoryName = "META-INF/wow/manifest"

val wowManifestTools: Configuration = configurations.create("wowManifestTools") {
    description = "The tools generateWowManifestFragment runs: :manifest-tools and its runtime class path."
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

val main = the<SourceSetContainer>().named("main").get()
val compilerFragments = files(layout.buildDirectory.dir("generated/ksp/main/resources"))
    .builtBy("kspKotlin")
    .asFileTree
    .matching { include("$fragmentDirectoryName/*.json") }
val completedDirectory = layout.buildDirectory.dir("wow/manifest-fragment")

val generateWowManifestFragment = tasks.register("generateWowManifestFragment") {
    group = "wow"
    description = "Completes this module's Manifest fragment with the schemas of its types."
    val fragments = compilerFragments
    // SPIKE D234: the runtime class path's classes variants (a project's classes directories, a module's jar), never
    // a project's jar: the api module's jar carries this module's contract, so reading it would be a task cycle.
    val runtimeClasses = configurations.getByName(main.runtimeClasspathConfigurationName).incoming.artifactView {
        attributes {
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.CLASSES))
        }
    }.files
    val classpath = files(main.output.classesDirs, runtimeClasses)
    val tools = wowManifestTools
    val output = completedDirectory
    inputs.files(fragments).withPathSensitivity(PathSensitivity.NAME_ONLY).withPropertyName("fragments")
    inputs.files(classpath).withNormalizer(ClasspathNormalizer::class.java).withPropertyName("classpath")
    inputs.files(tools).withNormalizer(ClasspathNormalizer::class.java).withPropertyName("tools")
    outputs.dir(output)
    outputs.cacheIf { true }
    doLast {
        val root = output.get().asFile.apply { deleteRecursively() }
        val urls = (classpath.files + tools.files).map { it.toURI().toURL() }.toTypedArray()
        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            val complete = Class.forName("me.ahoo.wow.manifest.tools.ManifestTools", true, loader).methods
                .single { it.name == "completeFragment" }
            fragments.files.sortedBy { it.name }.forEach { fragment ->
                val completed = File(root, "$fragmentDirectoryName/${fragment.name}")
                try {
                    complete.invoke(null, fragment, completed, emptyMap<String, String>())
                } catch (failure: java.lang.reflect.InvocationTargetException) {
                    throw failure.targetException
                }
            }
        }
    }
}
main.output.dir(mapOf("builtBy" to generateWowManifestFragment), completedDirectory)
// The completed fragment takes the place of the compiler's.
tasks.named<ProcessResources>(main.processResourcesTaskName) {
    exclude("$fragmentDirectoryName/**")
}
