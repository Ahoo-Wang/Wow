/*
 * SPIKE D234 (not for merge): the domain's consumer-facing contract, packaged into its own api module's jar, as the
 * Gradle plugin `me.ahoo.wow` would do once applied to the domain module. A domain module of the eShop applies this
 * script after example/wow-manifest-fragment.gradle.kts.
 *
 * - `generateWowContract` filters the completed Manifest fragment down to what a peer needs: per context its name,
 *   aggregates (non-mounted commands with name, aliases, schema, creation; events with name, aliases, revision,
 *   schema; snapshot.enabled; the state schema), published streams, subscriptions (kind, source, derives, rejected,
 *   committed: the wait-chain checks), errors, and the `$defs` those reference. It writes
 *   `META-INF/wow/contract/<context>.json` and `META-INF/wow/contract/index`.
 * - Guard: every command and event class of the contract must be a class of the paired api module, else the build
 *   fails.
 * - The contract is offered by the consumable configuration `wowContractElements`.
 * - Pairing: every project the domain declares in `api(...)` that is a candidate (here: its name ends in `-api`) is
 *   that api's producer; the api gets a resolvable `wowContract` configuration on the domain's `wowContractElements`,
 *   and its `jar` includes what it resolves.
 */
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

val contractDirectoryName = "META-INF/wow/contract"
val domainPath = path

fun AttributeContainer.wowContract() {
    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
    attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named("wow-contract"))
    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
}

// The candidate api projects: the `api(project(...))` dependencies the pairing rule takes. In a user's build Wow's
// own modules are module dependencies, so every project dependency is a candidate; inside this build the framework's
// projects (`:kernel`) are projects too, so the spike takes the names ending in `-api`.
fun isCandidate(dependency: ProjectDependency): Boolean = dependency.path.endsWith("-api")

// The api module's classes, as the domain's compile class path sees them (the classes directory, no jar).
val wowContractApi: Configuration = configurations.create("wowContractApi") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.CLASSES))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
    }
}
configurations.named("api") {
    dependencies.withType<ProjectDependency>().configureEach {
        if (isCandidate(this)) {
            project.dependencies.add(wowContractApi.name, project.project(path))
        }
    }
}

val completedFragments = files(tasks.named("generateWowManifestFragment")).asFileTree
    .matching { include("META-INF/wow/manifest/*.json") }
val contractDirectory = layout.buildDirectory.dir("wow/contract")

val generateWowContract = tasks.register("generateWowContract") {
    group = "wow"
    description = "Filters the module's Manifest fragment down to the contract its api module carries."
    val fragments = completedFragments
    val apiClasses = wowContractApi
    val output = contractDirectory
    val domain = domainPath
    inputs.files(fragments).withPathSensitivity(PathSensitivity.NAME_ONLY).withPropertyName("fragments")
    inputs.files(apiClasses).withNormalizer(ClasspathNormalizer::class.java).withPropertyName("apiClasses")
    outputs.dir(output)
    doLast {
        val root = output.get().asFile.apply { deleteRecursively() }
        val directory = File(root, contractDirectoryName).apply { mkdirs() }
        val names = mutableListOf<String>()
        fragments.files.sortedBy { it.name }.forEach { fragment ->
            @Suppress("UNCHECKED_CAST")
            val tree = JsonSlurper().parse(fragment) as Map<String, Any?>
            val defs = (tree["\$defs"] as Map<String, Any?>?).orEmpty()
            (tree["contexts"] as List<Map<String, Any?>>).forEach { context ->
                val contract = contractOf(tree, context, defs)
                guard(domain, context["name"] as String, contract, apiClasses.files)
                val name = "${context["name"]}.json"
                File(directory, name).writeText(JsonOutput.prettyPrint(JsonOutput.toJson(contract)))
                names += name
            }
        }
        File(directory, "index").writeText(names.sorted().joinToString("\n", postfix = "\n"))
    }
}

val wowContractElements: Configuration = configurations.create("wowContractElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes { wowContract() }
    outgoing.artifact(contractDirectory) { builtBy(generateWowContract) }
}

// The pairing (the cross-project part): each candidate api of this domain takes its contract into its jar.
configurations.named("api") {
    dependencies.withType<ProjectDependency>().configureEach {
        if (isCandidate(this)) {
            val apiPath = path
            project(apiPath) {
                val contract = configurations.maybeCreate("wowContract").apply {
                    isCanBeConsumed = false
                    isCanBeResolved = true
                    isTransitive = false
                    attributes { wowContract() }
                }
                dependencies.add(contract.name, project(domainPath))
                tasks.named<Jar>("jar") { from(contract) }
            }
        }
    }
}

/** The consumer-facing part of [context]: what a peer reads, with the `$defs` it references. */
@Suppress("UNCHECKED_CAST")
fun contractOf(tree: Map<String, Any?>, context: Map<String, Any?>, defs: Map<String, Any?>): Map<String, Any?> {
    fun Map<String, Any?>.pick(vararg keys: String) = keys.filter { it in this }.associateWith { this[it] }
    val aggregates = (context["aggregates"] as List<Map<String, Any?>>).map { aggregate ->
        linkedMapOf(
            "name" to aggregate["name"],
            "description" to aggregate["description"],
            "state" to aggregate["state"],
            "snapshot" to aggregate["snapshot"],
            "commands" to (aggregate["commands"] as List<Map<String, Any?>>)
                .filterNot { it["mounted"] == true }
                .map { it.pick("name", "aliases", "schema", "creation", "produces", "description") },
            "events" to (aggregate["events"] as List<Map<String, Any?>>?).orEmpty()
                .map { it.pick("name", "aliases", "revision", "schema", "description") },
        ).filterValues { it != null }
    }
    val subscriptions = (context["subscriptions"] as List<Map<String, Any?>>?).orEmpty()
        .map { it.pick("name", "kind", "source", "function", "derives", "rejected", "committed", "start") }
    val filtered = linkedMapOf(
        "name" to context["name"],
        "description" to context["description"],
        "aggregates" to aggregates,
        "subscriptions" to subscriptions,
        "published" to context["published"],
        "errors" to context["errors"],
    ).filterValues { it != null }
    // The `$defs` the contract references, transitively.
    val reachable = sortedMapOf<String, Any?>()
    val prefix = "#/\$defs/"
    fun visit(node: Any?) {
        when (node) {
            is Map<*, *> -> node.forEach { (key, value) ->
                if (key == "\$ref" && value is String && value.startsWith(prefix)) {
                    val name = value.removePrefix(prefix)
                    if (name in defs && name !in reachable) {
                        reachable[name] = defs[name]
                        visit(defs[name])
                    }
                } else {
                    visit(value)
                }
            }

            is List<*> -> node.forEach(::visit)
            is String -> if (node.startsWith(prefix)) visit(mapOf("\$ref" to node))
        }
    }
    visit(aggregates)
    return linkedMapOf("format" to tree["format"], "contexts" to listOf(filtered), "\$defs" to reachable)
}

/**
 * Fails when a command or event class of the contract of [context] is not a class of the api module ([apiClasses],
 * its classes directories): the contract would name a type the api does not ship.
 */
@Suppress("UNCHECKED_CAST")
fun guard(domain: String, context: String, contract: Map<String, Any?>, apiClasses: Set<File>) {
    check(apiClasses.isNotEmpty()) { "$domain: context $context has no api module to carry its contract" }
    val prefix = "#/\$defs/"
    val aggregates = (contract["contexts"] as List<Map<String, Any?>>).single()["aggregates"] as List<Map<String, Any?>>
    val types = aggregates.flatMap { aggregate ->
        ((aggregate["commands"] as List<Map<String, Any?>>) + (aggregate["events"] as List<Map<String, Any?>>))
            .map { (it["schema"] as String).removePrefix(prefix) }
    }
    val foreign = types.filterNot { binary ->
        val file = binary.replace('.', '/') + ".class"
        apiClasses.any { File(it, file).isFile }
    }
    check(foreign.isEmpty()) {
        "$domain: the contract of context $context names classes its api module does not have " +
            "(${apiClasses.joinToString { it.path }}): ${foreign.joinToString()}"
    }
}
