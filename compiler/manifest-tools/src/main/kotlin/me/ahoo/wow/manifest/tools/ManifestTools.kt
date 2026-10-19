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

import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.ManifestMerger
import me.ahoo.wow.manifest.PeerContracts
import me.ahoo.wow.manifest.ServiceInfo
import java.io.File

/**
 * What the plugin's workers run (decision D83): the plugin resolves these tools (victools, Jackson, the kernel) into
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
     * Merges the fragments found in the [classpath] entries into [output] ([ManifestMerger]); fails on a conflict or
     * on a schema reference no fragment resolves. Returns the number of fragments merged.
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
        ManifestJson.write(manifest, output)
        return fragments.size
    }

    /**
     * The Manifest compatibility gate (decision D76): compares the service's [current] Manifest with its [baseline],
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
                append("The Manifest has ${result.breaking.size} breaking change(s) against its baseline $baseline ")
                append("(decision D76):\n")
                result.breaking.forEach { append("  - ${it.path}: ${it.description}\n") }
                append("Keep the contract (keep the field optional, keep the old name as an alias, raise the event's ")
                append("@Revision with an @Upgrade), or, for a new major version, run $UPDATE_BASELINE_TASK.")
            }
        }
        return result.breaking.size
    }

    /**
     * Writes the peer contract of a domain module's completed [fragments] into [output] ([PeerContracts.contractOf],
     * decision D234), what the Gradle plugin's `generateWowContract` does.
     */
    @JvmStatic
    fun generateContract(fragments: List<File>, output: File) {
        ManifestJson.write(PeerContracts.contractOf(fragments.sortedBy { it.name }.map(ManifestJson::read)), output)
    }

    /**
     * Writes the contract the api module [owner] carries, of the domain modules' [contracts] the contexts whose command
     * and event classes are all in its [classes] directories ([PeerContracts.assemble], decision D234), into
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

    private const val UPDATE_BASELINE_TASK = "updateManifestBaseline"
}
