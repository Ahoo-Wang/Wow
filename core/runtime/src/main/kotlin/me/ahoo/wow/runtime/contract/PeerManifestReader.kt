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

package me.ahoo.wow.runtime.contract

import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.PeerManifests
import me.ahoo.wow.manifest.ServiceInfo
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI

/**
 * Reads the peer Manifests on a class path (decision D139): every [PeerManifests.INDEX] (one per jar or directory
 * that holds peers, as the Gradle plugin writes them), and each `META-INF/wow/peers/<service>.json` it lists, read
 * from beside that index. The same Manifest found twice (two modules of the service built against one peer) is read
 * once.
 *
 * Fails with a [ContractException] listing every peer that is missing or does not read as a Manifest.
 */
internal class PeerManifestReader(
    private val classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
    private val mapper: JsonMapper = JsonMapper.builder().build(),
) {
    fun read(): List<ManifestFragment> {
        val problems = mutableListOf<String>()
        val manifests = indexed(PeerManifests.INDEX, problems)
        // SPIKE D234: the contracts the api jars on the class path carry, each a peer of no service yet; a peer
        // Manifest of wowPeers takes precedence over the contract of the same context (the migration).
        val declared = manifests.flatMap { peer -> peer.manifest.contexts.map { it.name } }.toSet()
        val contracts = indexed(CONTRACT_INDEX, problems)
            .filter { contract -> contract.manifest.contexts.none { it.name in declared } }
            .map { contract ->
                val manifest = contract.manifest
                val service = ServiceInfo(manifest.contexts.joinToString { it.name } + " (contract)")
                ManifestFragment(
                    contract.source,
                    Manifest(manifest.format, service, manifest.contexts, manifest.errors, manifest.defs),
                )
            }
        val peers = manifests + contracts
        if (problems.isNotEmpty()) {
            throw ContractException(problems)
        }
        return peers.distinctBy { it.manifest }
    }

    private fun indexed(resource: String, problems: MutableList<String>): List<ManifestFragment> =
        classLoader.getResources(resource).toList().flatMap { index ->
            val base = index.toString().removeSuffix(INDEX_NAME)
            index.openStream().use { it.readBytes() }.decodeToString().lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { name -> read(base + name, problems) }
        }

    private fun read(source: String, problems: MutableList<String>): ManifestFragment? {
        val problem = try {
            val bytes = URI.create(source).toURL().openStream().use { it.readBytes() }
            return ManifestFragment(source, ManifestTree.read(mapper.readValue(bytes, Map::class.java)))
        } catch (malformed: JacksonException) {
            "is not JSON: ${malformed.originalMessage}"
        } catch (invalid: IllegalArgumentException) {
            "is not a Manifest: ${invalid.message}"
        } catch (missing: IOException) {
            "cannot be read: $missing"
        }
        problems += "peer Manifest $source $problem"
        return null
    }

    private companion object {
        val INDEX_NAME: String = PeerManifests.INDEX.substringAfterLast('/')

        /** SPIKE D234: where an api jar carries its contexts' contracts. */
        const val CONTRACT_INDEX: String = "META-INF/wow/contract/index"
    }
}
