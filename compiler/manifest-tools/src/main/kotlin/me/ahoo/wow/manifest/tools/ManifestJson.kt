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

    fun read(input: InputStream): Manifest = ManifestTree.read(mapper.readValue(input, Map::class.java))

    fun read(file: File): Manifest = file.inputStream().use(::read)

    fun write(manifest: Manifest, file: File) {
        file.parentFile.mkdirs()
        file.writeText(pretty(ManifestTree.write(manifest)) + "\n")
    }

    /** [tree], a JSON tree of maps, lists and scalars, as indented JSON text. */
    fun pretty(tree: Any?): String = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree)

    /**
     * The fragments under `META-INF/wow/manifest/` of [entry], a directory or a jar of a classpath, sorted by path;
     * each is named by where it was found.
     */
    fun fragments(entry: File): List<ManifestFragment> = when {
        entry.isDirectory -> File(entry, Manifest.FRAGMENT_DIRECTORY)
            .listFiles { file -> file.isFile && file.name.endsWith(JSON) }.orEmpty()
            .sortedBy { it.name }
            .map { ManifestFragment(it.path, read(it)) }

        entry.isFile && entry.name.endsWith(".jar") -> ZipFile(entry).use { jar ->
            jar.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("${Manifest.FRAGMENT_DIRECTORY}/") }
                .filter {
                    it.name.removePrefix(
                        "${Manifest.FRAGMENT_DIRECTORY}/"
                    ).let { name -> '/' !in name && name.endsWith(JSON) }
                }
                .sortedBy { it.name }
                .map { ManifestFragment("${entry.path}!/${it.name}", jar.getInputStream(it).use(::read)) }
                .toList()
        }

        else -> emptyList()
    }

    /** The peer contract (`META-INF/wow/contract.json`) of [entry], a directory or a jar; null if none. */
    fun contract(entry: File): ManifestFragment? = when {
        entry.isDirectory -> File(entry, PeerContracts.RESOURCE).takeIf { it.isFile }
            ?.let { ManifestFragment(it.path, read(it)) }

        entry.isFile && entry.name.endsWith(".jar") -> ZipFile(entry).use { jar ->
            jar.getEntry(PeerContracts.RESOURCE)?.let {
                ManifestFragment("${entry.path}!/${it.name}", jar.getInputStream(it).use(::read))
            }
        }

        else -> null
    }

    private const val JSON = ".json"
}
