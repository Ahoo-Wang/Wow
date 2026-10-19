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
    val apiClasses: FileCollection = files(wowContractApi)
    val output = contractDirectory
    val domain = domainPath
    inputs.files(fragments).withPathSensitivity(PathSensitivity.NAME_ONLY).withPropertyName("fragments")
    inputs.files(apiClasses).withNormalizer(ClasspathNormalizer::class.java).withPropertyName("apiClasses")
    outputs.dir(output)
    doLast {
        val root = output.get().asFile.apply { deleteRecursively(); mkdirs() }
        fragments.files.sortedBy { it.name }.forEach { fragment ->
            @Suppress("UNCHECKED_CAST")
            val tree = JsonSlurper().parse(fragment) as Map<String, Any?>
            val defs = (tree["\$defs"] as Map<String, Any?>?).orEmpty()
            (tree["contexts"] as List<Map<String, Any?>>).forEach { context ->
                val contract = ContractTools.contractOf(tree, context, defs)
                // Every class the contract names must be one of the domain's api modules'.
                val foreign = ContractTools.typesOf(contract).filterNot { ContractTools.has(apiClasses.files, it) }
                check(apiClasses.files.isNotEmpty() && foreign.isEmpty()) {
                    "$domain: the contract of context ${context["name"]} names classes none of its api modules " +
                        "(${apiClasses.files.joinToString { it.path }}) has: ${foreign.joinToString()}"
                }
                File(root, "${context["name"]}.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(contract)))
            }
        }
    }
}

val wowContractElements: Configuration = configurations.create("wowContractElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes { wowContract() }
    outgoing.artifact(contractDirectory) { builtBy(generateWowContract) }
}

// The pairing (the cross-project part): each candidate api of this domain resolves the domain's contracts, keeps the
// contexts whose commands and events are its own classes, and puts them into its jar.
configurations.named("api") {
    dependencies.withType<ProjectDependency>().configureEach {
        if (isCandidate(this)) {
            project(path) { pairedApi(domainPath) }
        }
    }
}

fun Project.pairedApi(domain: String) {
    val contract = configurations.findByName("wowContract")
    if (contract != null) {
        dependencies.add(contract.name, project(domain))
        return
    }
    val created = configurations.create("wowContract") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
        attributes { wowContract() }
    }
    dependencies.add(created.name, project(domain))
    val main = the<SourceSetContainer>().named("main").get()
    val assembled = layout.buildDirectory.dir("wow/contract")
    val assemble = tasks.register("assembleWowContract") {
        group = "wow"
        description = "Takes the contracts of this api module's contexts from the domain modules that declare it."
        val contracts: FileCollection = files(created)
        val classes: FileCollection = main.output.classesDirs
        val output = assembled
        val contractPath = contractDirectoryName
        val api = path
        inputs.files(contracts).withPropertyName("contracts")
        inputs.files(classes).withNormalizer(ClasspathNormalizer::class.java).withPropertyName("classes")
        outputs.dir(output)
        doLast {
            val directory = File(output.get().asFile.apply { deleteRecursively() }, contractPath).apply { mkdirs() }
            val names = mutableListOf<String>()
            contracts.files.flatMap { it.listFiles { file -> file.extension == "json" }.orEmpty().toList() }
                .sortedBy { it.name }
                .forEach { file ->
                    @Suppress("UNCHECKED_CAST")
                    val contract = JsonSlurper().parse(file) as Map<String, Any?>
                    val types = ContractTools.typesOf(contract)
                    val own = types.filter { ContractTools.has(classes.files, it) }
                    if (own.isEmpty()) {
                        return@forEach
                    }
                    check(own.size == types.size) {
                        "$api: the contract ${file.name} of ${file.parentFile} names classes of this api module and " +
                            "classes of another: ${(types - own.toSet()).joinToString()}"
                    }
                    check(file.name !in names) { "$api: two domain modules give the contract ${file.name}" }
                    file.copyTo(File(directory, file.name))
                    names += file.name
                }
            File(directory, "index").writeText(names.sorted().joinToString("\n", postfix = "\n"))
        }
    }
    tasks.named<Jar>("jar") { from(assemble) }
}

/** The filter and the guard; an object, so the task action captures no script reference (configuration cache). */
object ContractTools {
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

    /** The binary names of the command and event classes of [contract]. */
    @Suppress("UNCHECKED_CAST")
    fun typesOf(contract: Map<String, Any?>): List<String> =
        (contract["contexts"] as List<Map<String, Any?>>).flatMap { context ->
            (context["aggregates"] as List<Map<String, Any?>>).flatMap { aggregate ->
                ((aggregate["commands"] as List<Map<String, Any?>>) + (aggregate["events"] as List<Map<String, Any?>>))
                    .map { (it["schema"] as String).removePrefix("#/\$defs/") }
            }
        }

    /** Whether one of the classes directories [classes] holds the class of binary name [binary]. */
    fun has(classes: Set<File>, binary: String): Boolean {
        val file = binary.replace('.', '/') + ".class"
        return classes.any { File(it, file).isFile }
    }
}
