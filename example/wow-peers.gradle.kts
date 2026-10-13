/*
 * The example servers' Manifests and their peers' (decision D139), as the Gradle plugin `me.ahoo.wow` provides them to
 * an application: the plugin cannot be applied to a project of the build that produces it, so a server of the example
 * applies this script instead, after setting `extra["wowService"]` to its service name.
 *
 * - Producer: `mergeWowManifest` merges the Manifest fragments on the server's runtime class path (its domain modules')
 *   into `build/wow/manifest.json`, named after the service, and the consumable configuration `wowManifestElements`
 *   offers it with the plugin's attributes. The schemas are left out: the plugin introspects them, and nothing here
 *   needs them.
 * - Consumer: `wowPeers(project(":<peer>-server"))` takes a peer's merged Manifest, and `collectWowPeers` writes each to
 *   `META-INF/wow/peers/<service>.json`, with the index beside them, into the server's main output, so the peers are on
 *   its runtime class path and in its jar.
 *
 * The merge reads the runtime class path, never the server's own output, so two servers may be each other's peers.
 */
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.zip.ZipFile

val wowService = extra["wowService"] as String

fun AttributeContainer.wowManifest() {
    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
    attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named("wow-manifest"))
    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
}

/** The fragments of a class path entry, a directory or a jar, as JSON trees. */
fun fragmentsOf(entry: File): List<Map<*, *>> {
    val directory = "META-INF/wow/manifest/"
    return when {
        entry.isDirectory -> File(entry, directory).listFiles { file -> file.extension == "json" }.orEmpty().sorted()
            .map { JsonSlurper().parse(it) as Map<*, *> }

        entry.isFile && entry.extension == "jar" -> ZipFile(entry).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.startsWith(directory) && it.name.endsWith(".json") && '/' !in it.name.removePrefix(directory) }
                .sortedBy { it.name }
                .map { JsonSlurper().parse(zip.getInputStream(it).readBytes()) as Map<*, *> }
                .toList()
        }

        else -> emptyList()
    }
}

val runtimeClasspath: Configuration = configurations.getByName("runtimeClasspath")
val manifestFile = layout.buildDirectory.file("wow/manifest.json")

val mergeWowManifest = tasks.register("mergeWowManifest") {
    description = "Merges the Manifest fragments of the runtime class path into the service's Manifest."
    val classpath = runtimeClasspath
    val service = wowService
    val output = manifestFile
    inputs.files(classpath).withNormalizer(ClasspathNormalizer::class.java).withPropertyName("classpath")
    inputs.property("service", service)
    outputs.file(output)
    doLast {
        val fragments = classpath.files.flatMap(::fragmentsOf)
        check(fragments.isNotEmpty()) { "No Manifest fragment on the runtime class path of $service." }
        val merged = linkedMapOf(
            "format" to fragments.first()["format"],
            "service" to mapOf("name" to service),
            "contexts" to fragments.flatMap { it["contexts"] as List<*> }.sortedBy { (it as Map<*, *>)["name"] as String },
            "errors" to fragments.first()["errors"],
        )
        output.get().asFile.apply { parentFile.mkdirs() }.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(merged)))
    }
}

configurations.create("wowManifestElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes { wowManifest() }
    outgoing.artifact(manifestFile) { builtBy(mergeWowManifest) }
}

val wowPeers: Configuration = configurations.create("wowPeers") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    attributes { wowManifest() }
}

val peersDirectory = layout.buildDirectory.dir("generated/wow-peers")

val collectWowPeers = tasks.register("collectWowPeers") {
    description = "Puts the peers' Manifests under META-INF/wow/peers, with their index."
    val peers = wowPeers
    val output = peersDirectory
    inputs.files(peers).withPathSensitivity(PathSensitivity.NONE).withPropertyName("peers")
    outputs.dir(output)
    doLast {
        val root = output.get().asFile.apply { deleteRecursively() }
        val directory = File(root, "META-INF/wow/peers").apply { mkdirs() }
        val names = peers.files.map { file ->
            val service = ((JsonSlurper().parse(file) as Map<*, *>)["service"] as Map<*, *>)["name"] as String
            "$service.json".also { file.copyTo(File(directory, it)) }
        }.sorted()
        File(directory, "index").writeText(names.joinToString("\n", postfix = "\n"))
    }
}

the<SourceSetContainer>().named("main") {
    output.dir(mapOf("builtBy" to collectWowPeers), peersDirectory)
}
