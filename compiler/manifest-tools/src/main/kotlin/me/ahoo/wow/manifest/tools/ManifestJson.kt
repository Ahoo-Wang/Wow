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

import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.PeerContracts
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Reads and writes Manifests as JSON text, and finds the fragments on a classpath. */
object ManifestJson {
    private val mapper = jacksonObjectMapper()

    /** The Manifest [input] holds, one this build or an earlier one wrote: never newer than its reader ([ManifestTree.REFUSE]). */
    fun read(input: InputStream): Manifest = read(input, ManifestTree.REFUSE)

    /** The Manifest [input] holds, what it holds that this reader does not know left out and told to [warn]. */
    private fun read(input: InputStream, warn: (String) -> Unit): Manifest =
        ManifestTree.read(mapper.readValue(input, Map::class.java), warn)

    fun read(file: File): Manifest = file.inputStream().use(::read)

    fun write(manifest: Manifest, file: File) {
        file.parentFile.mkdirs()
        file.writeText(pretty(ManifestTree.write(manifest)) + "\n")
    }

    /** [tree], a JSON tree of maps, lists and scalars, as indented JSON text. */
    fun pretty(tree: Any?): String = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree)

    /**
     * The fragments under `META-INF/wow/manifest/` of [entry], a directory or a jar of a classpath, at any depth (a
     * nested project's is in a directory of its path), sorted by path; each is named by where it was found.
     */
    fun fragments(entry: File): List<ManifestFragment> = when {
        entry.isDirectory -> File(entry, Manifest.FRAGMENT_DIRECTORY).walkTopDown()
            .filter { it.isFile && it.name.endsWith(JSON) }
            .sortedBy { it.invariantSeparatorsPath }
            .map { ManifestFragment(it.path, read(it)) }
            .toList()

        entry.isFile && entry.name.endsWith(".jar") -> ZipFile(entry).use { jar ->
            jar.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("${Manifest.FRAGMENT_DIRECTORY}/") }
                .filter { it.name.endsWith(JSON) }
                .sortedBy { it.name }
                .map { ManifestFragment("${entry.path}!/${it.name}", jar.getInputStream(it).use(::read)) }
                .toList()
        }

        else -> emptyList()
    }

    /**
     * The peer contract (`META-INF/wow/contract.json`) of [entry], a directory or a jar; null if none. A peer's api jar
     * may come from the next minor version of Wow, as services on adjacent minors use each other's contract jars, so
     * the contract is read as a node reads it ([me.ahoo.wow.manifest.ClassPathContracts]): a value of an enumeration
     * this reader does not know leaves out what it belongs to, and is told to [warn], naming the contract.
     */
    fun contract(entry: File, warn: (String) -> Unit): ManifestFragment? = when {
        entry.isDirectory -> File(entry, PeerContracts.RESOURCE).takeIf { it.isFile }?.let { file ->
            ManifestFragment(file.path, file.inputStream().use { read(it) { warn("contract ${file.path}: $it") } })
        }

        entry.isFile && entry.name.endsWith(".jar") -> ZipFile(entry).use { jar ->
            jar.getEntry(PeerContracts.RESOURCE)?.let { resource ->
                val source = "${entry.path}!/${resource.name}"
                ManifestFragment(
                    source,
                    jar.getInputStream(resource).use { read(it) { warn("contract $source: $it") } }
                )
            }
        }

        else -> null
    }

    /**
     * The model providers [entry], a directory or a jar, registers for `ServiceLoader`: the class names its
     * [Manifest.MODEL_PROVIDERS] lists, which the compiler writes beside a fragment.
     */
    fun modelProviders(entry: File): List<String> {
        val text = when {
            entry.isDirectory -> File(entry, Manifest.MODEL_PROVIDERS).takeIf { it.isFile }?.readText()
            entry.isFile && entry.name.endsWith(".jar") -> ZipFile(entry).use { jar ->
                // Closing the jar closes the stream.
                jar.getEntry(Manifest.MODEL_PROVIDERS)?.let { jar.getInputStream(it).reader().readText() }
            }
            else -> null
        }
        return text.orEmpty().lines().map { it.substringBefore('#').trim() }.filter(String::isNotEmpty)
    }

    /** The class path resource of a fragment [ManifestFragment.source] names: `META-INF/wow/manifest/a/b.json`. */
    internal fun resourceOf(source: String): String =
        Manifest.FRAGMENT_DIRECTORY + "/" +
            source.replace('\\', '/').substringAfterLast("${Manifest.FRAGMENT_DIRECTORY}/")

    private const val JSON = ".json"
}
