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

import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.ManifestMerger
import me.ahoo.wow.manifest.PeerContracts
import me.ahoo.wow.manifest.ServiceInfo
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
     * Merges the fragments found in the [classpath] entries into [output] ([ManifestMerger]); fails on a conflict, on
     * a schema reference no fragment resolves, or on a command a saga derives by its class that neither a context of
     * the service nor a peer contract on the [classpath] decides ([undecidedCommands]). Returns the number of
     * fragments merged.
     */
    @JvmStatic
    fun merge(classpath: List<File>, serviceName: String, serviceVersion: String?, output: File): Int {
        val fragments = classpath.flatMap(ManifestJson::fragments)
        val manifest = ManifestMerger.merge(fragments, ServiceInfo(serviceName, serviceVersion))
        val unresolved = manifest.unresolvedSchemaRefs
        check(unresolved.isEmpty()) {
            "The Manifest refers to schemas no fragment holds: $unresolved. Apply the me.ahoo.wow plugin to the " +
                "modules that declare them, so their fragments are completed."
        }
        val undecided = undecidedCommands(manifest, classpath.mapNotNull(ManifestJson::contract).map { it.manifest })
        check(undecided.isEmpty()) {
            "The Manifest's sagas derive commands no context decides:\n" +
                undecided.joinToString("\n") { "- $it" } + "\nDepend on the api module of the context that decides " +
                "each command (its jar carries the context's contract), or name a command of the service."
        }
        ManifestJson.write(manifest, output)
        return fragments.size
    }

    /**
     * What a subscription of [manifest] derives by its class's schema reference (`#/$defs/<binary name>`, a command
     * no module of its context decides) that no context of [manifest] or of the peer [contracts] decides, a lifecycle
     * command left out: one line per subscription and command.
     */
    internal fun undecidedCommands(manifest: Manifest, contracts: List<Manifest>): List<String> {
        val decided = (listOf(manifest) + contracts).flatMap { it.contexts }.flatMap { it.aggregates }
            .flatMap { aggregate -> aggregate.commands.filterNot { it.mounted }.map { it.schema } }.toSet() +
            Lifecycle.COMMANDS.values.map { Manifest.schemaRef(it.name) }
        return manifest.contexts.flatMap { context ->
            context.subscriptions.flatMap { subscription ->
                subscription.derives.orEmpty().filter { it.startsWith(DEFS_PREFIX) && it !in decided }.map {
                    "${context.name}/${subscription.name} derives ${Manifest.schemaKey(it)}"
                }
            }
        }
    }

    private const val DEFS_PREFIX = "#/\$defs/"

    /**
     * The Manifest compatibility gate: compares the service's [current] Manifest with its [baseline],
     * the Manifest of its last published version ([ManifestCompatibility]), and writes the classified changes to
     * [report]. Fails, listing each breaking change with its path, when one breaks the contract within the baseline's
     * major version; a new major version resets the baseline. Without a baseline file there is nothing to compare,
     * and the report says how to record one. Returns the number of breaking changes.
     */
    @JvmStatic
    fun checkCompatibility(baseline: File, current: File, report: File): Int {
        report.parentFile.mkdirs()
        if (!baseline.isFile) {
            report.writeText("No Manifest baseline at $baseline: run $UPDATE_BASELINE_TASK to record one.\n")
            return 0
        }
        val result = ManifestCompatibility.compare(ManifestJson.read(baseline), ManifestJson.read(current))
        report.writeText(result.render())
        check(!result.failed) {
            buildString {
                append("The Manifest has ${result.breaking.size} breaking change(s) against its baseline $baseline:\n")
                result.breaking.forEach { append("  - ${it.path}: ${it.description}\n") }
                append("Keep the contract (keep the field optional, keep the old name as an alias, raise the event's ")
                append("@Revision with an @Upgrade), or, for a new major version, run $UPDATE_BASELINE_TASK.")
            }
        }
        return result.breaking.size
    }

    /**
     * Writes the peer contract of a domain module's completed [fragments] into [output] ([PeerContracts.contractOf]),
     * naming the [service] that hosts its contexts when given; what the Gradle plugin's
     * `generateWowContract` does.
     */
    @JvmStatic
    fun generateContract(fragments: List<File>, output: File, service: String?) {
        ManifestJson.write(
            PeerContracts.contractOf(fragments.sortedBy { it.name }.map(ManifestJson::read), service),
            output,
        )
    }

    /**
     * Writes the contract the api module [owner] carries, of the domain modules' [contracts] the contexts whose command
     * and event classes are all in its [classes] directories ([PeerContracts.assemble]), into
     * [output]; what the Gradle plugin's `assembleWowContract` does. Returns whether the api module carries one: with
     * no context of its own, [output] is not written.
     */
    @JvmStatic
    fun assembleContract(contracts: List<File>, classes: List<File>, owner: String, output: File): Boolean {
        val fragments = contracts.sorted().map { ManifestFragment(it.path, ManifestJson.read(it)) }
        val contract = PeerContracts.assemble(owner, fragments) { binary ->
            val path = binary.replace('.', '/') + ".class"
            classes.any { File(it, path).isFile }
        } ?: return false
        ManifestJson.write(contract, output)
        return true
    }

    /**
     * Writes the Kotlin sources generated from the api module's [contracts] (none, or the one `assembleWowContract`
     * wrote) under [output], which it clears first ([ContractSources]); with [queryFields], the query
     * field constants of the states too. What the Gradle plugin's `generateWowContractSources` does. Returns the
     * number of files written.
     */
    @JvmStatic
    fun generateContractSources(contracts: List<File>, queryFields: Boolean, output: File): Int {
        output.deleteRecursively()
        val sources = contracts.filter { it.isFile }.map(ManifestJson::read).flatMap {
            ContractSources.of(it, queryFields)
        }
        sources.forEach { source ->
            File(output, source.path).apply { parentFile.mkdirs() }.writeText(source.text)
        }
        return sources.size
    }

    private const val UPDATE_BASELINE_TASK = "updateManifestBaseline"
}
