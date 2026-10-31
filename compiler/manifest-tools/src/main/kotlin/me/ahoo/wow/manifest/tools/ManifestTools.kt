/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.manifest.tools

import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestAssembly
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.PeerContracts
import me.ahoo.wow.manifest.PeerSources
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.openapi.ManifestOpenApi
import java.io.File

/**
 * What the plugin's workers run: the plugin resolves these tools (victools, Jackson, the kernel) into
 * a configuration of the build's project and loads them in an isolated class loader, so none of them reaches the
 * build script's class path. The plugin calls these functions by name, through reflection, and passes only JDK
 * types across that boundary.
 */
object ManifestTools {
    /**
     * Completes the compiler's [fragment] into [output] ([SchemaIntrospector]). The classes it refers to are loaded
     * from this object's class loader, the worker's, which holds the module's classes.
     */
    @JvmStatic
    fun completeFragment(fragment: File, output: File, schemaOverrides: Map<String, String>) {
        val introspector = SchemaIntrospector(ManifestTools::class.java.classLoader, schemaOverrides)
        ManifestJson.write(introspector.complete(ManifestJson.read(fragment)), output)
    }

    /**
     * Assembles the fragments found in the [classpath] entries into [output] as the node does at startup
     * ([ManifestAssembly]), each named after its entry's owner in [owners] (a project or a module, by the entry's
     * path), describing the service
     * [serviceOf] names: the one the contracts of its contexts and its own [serviceName] agree on; fails on a conflict, on
     * a schema reference no fragment resolves, on another context's event a handler takes whose aggregate the peer
     * contracts on the [classpath] do not say ([PeerSources]), or on a command a saga derives by its class that neither a context of
     * the service nor a peer contract on the [classpath] decides ([undecidedCommands]), or on two fragments of one
     * name ([fragmentsOf]). Returns the number of fragments merged.
     */
    @JvmStatic
    fun merge(
        classpath: List<File>,
        owners: Map<String, String>,
        serviceName: String?,
        defaultServiceName: String,
        serviceVersion: String?,
        output: File,
    ): Int {
        val fragments = fragmentsOf(classpath, owners)
        val contracts = classpath.mapNotNull { entry ->
            ManifestJson.contract(entry)?.let { ManifestFragment(owners[entry.path] ?: entry.name, it.manifest) }
        }
        val service = serviceOf(fragments, contracts, serviceName, defaultServiceName)
        val manifest = ManifestAssembly.assemble(
            fragments,
            ServiceInfo(service, serviceVersion),
            contracts.map { it.manifest },
        )
        val unresolved = manifest.unresolvedSchemaRefs
        check(unresolved.isEmpty()) {
            "The Manifest refers to schemas no fragment holds: $unresolved. Apply the me.ahoo.wow plugin to the " +
                "modules that declare them, so their fragments are completed."
        }
        val undecided = undecidedCommands(manifest, contracts.map { it.manifest })
        check(undecided.isEmpty()) {
            "The Manifest's sagas derive commands no context decides:\n" +
                undecided.joinToString("\n") { "- $it" } + "\nDepend on the api module of the context that decides " +
                "each command (its jar carries the context's contract), or name a command of the service."
        }
        ManifestJson.write(manifest, output)
        return fragments.size
    }

    /**
     * The fragments of the [classpath] entries, each named by its entry's owner in [owners] and its resource
     * (`project ':shop-domain' META-INF/wow/manifest/shop-domain.json`), the entry's file name when it has none.
     *
     * @throws IllegalStateException when two entries hold a fragment of one name: the node reads one of them alone at
     * startup, dropping the other's contexts, so the build refuses them, naming both owners.
     */
    internal fun fragmentsOf(classpath: List<File>, owners: Map<String, String>): List<ManifestFragment> {
        val found = classpath.flatMap { entry ->
            val owner = owners[entry.path] ?: entry.name
            ManifestJson.fragments(entry).map { fragment ->
                val resource = Manifest.FRAGMENT_DIRECTORY + "/" + File(fragment.source.substringAfter("!/")).name
                resource to ManifestFragment("$owner $resource", fragment.manifest)
            }
        }
        val duplicates = found.groupBy({ it.first }, { it.second.source.substringBeforeLast(' ') })
            .filterValues { it.size > 1 }
        check(duplicates.isEmpty()) {
            duplicates.entries.joinToString("\n") { (resource, sources) ->
                "The Manifest fragment $resource is in ${sources.joinToString(" and ")}: a fragment is named after " +
                    "its module's project path, so these come from projects of one path in different builds. " +
                    "Rename one of the projects."
            }
        }
        return found.map { it.second }
    }

    /**
     * The service that hosts the contexts of [fragments]: the one service the [contracts] of those contexts name
     * (`wow { service }` of their domain modules) and the module's own [own] (`wow { service }` of the service module,
     * for its own contexts), [defaultName] when none names one. A peer's contract, whose contexts have no fragment
     * here, names its own service and is not counted.
     *
     * @throws IllegalStateException when they name several services.
     */
    internal fun serviceOf(
        fragments: List<ManifestFragment>,
        contracts: List<ManifestFragment>,
        own: String?,
        defaultName: String,
    ): String {
        val hosted = fragments.flatMap { it.manifest.contexts }.map { it.name }.toSet()
        val services = contracts.filter { contract -> contract.manifest.contexts.any { it.name in hosted } }
            .mapNotNull { contract -> contract.manifest.service?.name?.let { it to contract.source } } +
            listOfNotNull(own?.let { it to "the service module's wow { service }" })
        val names = services.map { it.first }.distinct()
        check(names.size <= 1) {
            "The contracts of the service's contexts name different services: " +
                services.joinToString { (service, source) -> "$service ($source)" } +
                "; the contexts a service hosts name one service, in their modules' wow { service = \"…\" }."
        }
        return names.singleOrNull() ?: defaultName
    }

    /**
     * What a subscription of [manifest] derives by its class's schema reference (`#/$defs/<binary name>`, a command
     * no module of its context decides) that no context of [manifest] or of the peer [contracts] decides, the system
     * commands every context takes (a lifecycle or a ledger command) left out: one line per subscription and command.
     */
    internal fun undecidedCommands(manifest: Manifest, contracts: List<Manifest>): List<String> {
        val decided = (listOf(manifest) + contracts).flatMap { it.contexts }.flatMap { it.aggregates }
            .flatMap { aggregate -> aggregate.commands.filterNot { it.mounted }.map { it.schema } }.toSet() +
            (Lifecycle.COMMANDS.values + Ledger.COMMANDS.values).map { Manifest.schemaRef(it.name) }
        return manifest.contexts.flatMap { context ->
            context.subscriptions.flatMap { subscription ->
                subscription.derives.orEmpty().filter { Manifest.isSchemaRef(it) && it !in decided }.map {
                    "${context.name}/${subscription.name} derives ${Manifest.schemaKey(it)}"
                }
            }
        }
    }

    /**
     * The Manifest gate of the service [owner] (`project ':x'`, as a failure names it): compares the service's
     * [current] Manifest with its committed [baseline] ([ManifestCompatibility], [Baseline]) and writes the classified
     * changes to [report]. Fails when there is no baseline; when a change breaks the contract within the baseline's
     * compatibility line (the service's major version is the line's, and no pre-release keeps it open,
     * [Baseline.allowsBreaking]), listing each; and when the baseline is not the Manifest this build produces, its
     * version aside, or not of the service's major: every change is recorded, so the baseline's diff in review shows
     * what the clients will see. Each failure names [UPDATE_BASELINE_TASK]. Returns the number of additive changes.
     */
    @JvmStatic
    fun checkCompatibility(baseline: File, current: File, report: File, owner: String): Int {
        report.parentFile.mkdirs()
        val update = "$UPDATE_BASELINE_TASK of $owner"
        check(baseline.isFile) {
            "$owner has no Manifest baseline (${baseline.name}): run $update to record the Manifest this build " +
                "produces, review it and commit it."
        }
        val recorded = Baseline.read(baseline)
        val manifest = ManifestJson.read(current)
        val version = manifest.service?.version
        val built = Baseline.of(manifest, Baseline.majorOf(version) ?: recorded.line)
        val result = ManifestCompatibility.compare(recorded.manifest, built.manifest)
        val allowed = Baseline.allowsBreaking(recorded.line, version)
        report.writeText(result.render() + lineOf(recorded.line, version, allowed))
        check(!result.failed || allowed) {
            breaks("The Manifest of $owner has", result.breaking, recorded.line, version) +
                "Keep the contract (keep the field optional, keep the old name as an alias, add a new event beside the " +
                "old one); a break needs the service's next major version, then run $update."
        }
        check(recorded == built) {
            "The Manifest baseline of $owner does not record the Manifest this build produces " +
                "(${result.additive.size} additive change(s), ${result.breaking.size} breaking one(s) a new major or an " +
                "open pre-release allows; recorded on the line ${recorded.line ?: "(none)"}, built on " +
                "${built.line ?: "(none)"}; see ${report.name}): run $update, review the diff and commit it."
        }
        return result.additive.size
    }

    /**
     * Records the service's [manifest] as its [baseline], what [checkCompatibility] compares with: the Manifest without
     * its service's version, on the compatibility line of its major. Refuses to record a breaking change against the
     * existing baseline within its line ([Baseline.allowsBreaking]) unless [allowBreaking]
     * (`-Pwow.manifest.allowBreaking`), which [report] then records with the changes.
     */
    @JvmStatic
    fun recordBaseline(manifest: File, baseline: File, allowBreaking: Boolean, report: File, owner: String) {
        report.parentFile.mkdirs()
        val current = ManifestJson.read(manifest)
        val version = current.service?.version
        val existing = baseline.takeIf { it.isFile }?.let(Baseline::read)
        val recorded = Baseline.of(current, Baseline.majorOf(version) ?: existing?.line)
        val result = existing?.let { ManifestCompatibility.compare(it.manifest, recorded.manifest) }
        val within = result?.breaking.orEmpty().takeUnless { Baseline.allowsBreaking(existing?.line, version) }
        check(within.isNullOrEmpty() || allowBreaking) {
            breaks("$owner refuses to record", within.orEmpty(), existing?.line, version) +
                "Keep the contract, raise the service's major version, or, for a pre-release, record it with " +
                "-P$ALLOW_BREAKING."
        }
        recorded.write(baseline)
        report.writeText(
            "Recorded on the compatibility line ${recorded.line ?: "(none)"} (version ${version ?: "(none)"})\n" +
                result?.render().orEmpty() +
                within.orEmpty().takeIf { it.isNotEmpty() }
                    ?.let { "With -P$ALLOW_BREAKING: recorded ${it.size} breaking change(s) within the line\n" }
                    .orEmpty(),
        )
    }

    /** The failure's lines of the [breaking] changes within the compatibility [line] of a service at [version]. */
    private fun breaks(lead: String, breaking: List<ManifestChange>, line: Int?, version: String?): String =
        "$lead ${breaking.size} breaking change(s) within its compatibility line ${line ?: "(none)"} " +
            "(version ${version ?: "(none)"}):\n" + breaking.joinToString("") { "  - ${it.path}: ${it.description}\n" }

    /** The report's line of the compatibility [line] of a service at [version], and whether it [allowed] a break. */
    private fun lineOf(line: Int?, version: String?, allowed: Boolean): String =
        "Compatibility line ${line ?: "(none)"}, version ${version ?: "(none)"}: " +
            (if (allowed) "breaking changes allowed (a later major, or an open pre-release)" else "breaking changes fail") +
            "\n"

    /** The Gradle property that lets [recordBaseline] record a break within its line: for pre-releases. */
    const val ALLOW_BREAKING = "wow.manifest.allowBreaking"

    /**
     * Writes the peer contract of the domain module [module]'s completed [fragments] into [output]
     * ([PeerContracts.contractOf]), naming the [service] that hosts its contexts when given; what the Gradle plugin's
     * `generateWowContract` does. A context whose command or event classes are in an entry of [unpaired] (a class path
     * entry of a project that does not pair with the module, to that project's path) fails, naming the line that pairs
     * them: the contract would reach no api module, and no peer would see the context.
     */
    @JvmStatic
    fun generateContract(
        fragments: List<File>,
        service: String?,
        module: String,
        unpaired: Map<String, String>,
        output: File,
    ) {
        val contract = PeerContracts.contractOf(fragments.sortedBy { it.name }.map(ManifestJson::read), service)
        val owners = ClassPath(unpaired.keys.map(::File))
        val problems = contract.contexts.flatMap { context ->
            PeerContracts.classesOf(context).mapNotNull { binary ->
                owners.entryOf(binary)?.let { binary to unpaired.getValue(it.path) }
            }.groupBy({ it.second }, { it.first }).map { (api, classes) ->
                "context ${context.name} has its classes ${classes.sorted().joinToString()} in project '$api', which " +
                    "does not pair with project '$module': in $api's build file, apply id(\"$API_PLUGIN\") and add " +
                    "dependencies { wowContract(project(\"$module\")) }"
            }
        }
        check(problems.isEmpty()) {
            "The contract of project '$module' reaches no api module:\n" + problems.joinToString("\n") { "- $it" }
        }
        ManifestJson.write(contract, output)
    }

    /**
     * Writes the contract the api module [owner] carries, `META-INF/wow/contract.json` ([PeerContracts.RESOURCE])
     * under [output], which it clears first: of the [contracts] of the domain modules it declares (each named by
     * [owners], by its path), the contexts whose command and event classes are all in its [classes] directories
     * ([PeerContracts.assemble]); what the Gradle plugin's `assembleWowContract` does. A declared module of no context
     * (it compiles no model) fails, naming it. Returns whether the api module carries a contract: with no context of
     * its own, it carries none.
     */
    @JvmStatic
    fun assembleContract(
        contracts: List<File>,
        owners: Map<String, String>,
        classes: List<File>,
        owner: String,
        output: File,
    ): Boolean {
        output.deleteRecursively()
        val fragments = contracts.sorted().map { ManifestFragment(owners[it.path] ?: it.name, ManifestJson.read(it)) }
        val empty = fragments.filter { it.manifest.contexts.isEmpty() }
        check(empty.isEmpty()) {
            "$owner declares ${empty.joinToString(" and ") { it.source }} in wowContract, which compiles no model and " +
                "so has no contract: remove it from wowContract, or give it the aggregates whose contract this api " +
                "module carries."
        }
        val directories = classes.filter { it.isDirectory }
        val contract = PeerContracts.assemble(owner, fragments) { binary ->
            val path = binary.replace('.', '/') + ".class"
            directories.any { File(it, path).isFile }
        } ?: return false
        ManifestJson.write(contract, File(output, PeerContracts.RESOURCE))
        return true
    }

    /**
     * Writes the OpenAPI 3.1 document of the service's public routes ([ManifestOpenApi]) from its merged [manifest]
     * into [output]: what `GET /wow/openapi` serves, as far as the build knows it; what the Gradle plugin's
     * `generateWowOpenApi` does.
     */
    @JvmStatic
    fun generateOpenApi(manifest: File, output: File) {
        val document = ManifestOpenApi.write(ManifestJson.read(manifest))
        output.parentFile.mkdirs()
        output.writeText(ManifestJson.pretty(document) + "\n")
    }

    /**
     * Writes the Kotlin sources generated from the api module's [contracts] (none, or the one `assembleWowContract`
     * wrote) under [output], which it clears first ([ContractSources]): the `<Context>Contract`s and the views of
     * the states, which reference the classes of the [classpath] the code compiles against; with `wow-query-api` on
     * it, the query field constants of the states too. What the Gradle plugin's `generateWowContractSources` does.
     * Returns the number of files written.
     */
    @JvmStatic
    fun generateContractSources(contracts: List<File>, classpath: List<File>, output: File): Int {
        output.deleteRecursively()
        val classes = ClassPath(classpath)
        val queryFields = QUERY_FIELD in classes
        val sources = contracts.filter { it.isFile }.map(ManifestJson::read).flatMap {
            ContractSources.of(it, queryFields) { binary -> binary in classes }
        }
        sources.forEach { source ->
            File(output, source.path).apply { parentFile.mkdirs() }.writeText(source.text)
        }
        return sources.size
    }

    /** The class of `wow-query-api` whose constants the generated query fields are. */
    private const val QUERY_FIELD = "me.ahoo.wow.api.query.QueryField"

    private const val UPDATE_BASELINE_TASK = "updateWowManifestBaseline"

    /** The plugin id of an api module, which a domain module's contract pairs with. */
    private const val API_PLUGIN = "me.ahoo.wow.api"
}
