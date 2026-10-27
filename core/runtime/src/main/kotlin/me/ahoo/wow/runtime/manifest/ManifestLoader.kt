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

package me.ahoo.wow.runtime.manifest

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.ClassPathContracts
import me.ahoo.wow.manifest.ManifestMerger
import me.ahoo.wow.manifest.PeerSources
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.ServiceInfo
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper

/**
 * Assembles a service's Manifest when it starts: finds its modules' fragments through their
 * [ModelProvider]s, the binding index ([ModelProvider.fragment]), reads each from [classLoader], and merges them with
 * [ManifestMerger], naming the service; a subscription of another context's events by type then reads the aggregates
 * the peer contracts on [classLoader] list them under ([PeerSources]).
 *
 * Startup fails with every problem listed: a [ManifestLoadException] for the fragments that are missing or do not
 * read as a Manifest, else the merger's [me.ahoo.wow.manifest.ManifestConflictException] for the conflicts between
 * them.
 */
@InternalWowApi
class ManifestLoader(
    private val classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
    private val mapper: JsonMapper = JsonMapper.builder().build(),
) {
    /** The Manifest of [service], merged from the fragments of [providers] (by default, those on [classLoader]). */
    fun load(service: ServiceInfo, providers: List<ModelProvider> = ModelProvider.load(classLoader)): Manifest {
        val problems = mutableListOf<String>()
        val fragments = providers.map { it.fragment }.distinct().mapNotNull { source ->
            try {
                read(source)
            } catch (failure: ManifestLoadException) {
                problems += failure.problems
                null
            }
        }
        if (problems.isNotEmpty()) {
            throw ManifestLoadException(problems)
        }
        val merged = ManifestMerger.merge(fragments, service)
        // A subscription of another context's events by type reads the aggregates the peer contracts list them under.
        return PeerSources.resolve(merged, ClassPathContracts.load(classLoader) { mapper.readValue(it, Map::class.java) }.map { it.manifest })
    }

    /** The Manifest of the service named [service]; see [load]. */
    fun load(service: String): Manifest = load(ServiceInfo(service))

    /** The fragment at the class path resource [source]. */
    fun read(source: String): ManifestFragment {
        val problem = try {
            val bytes = classLoader.getResourceAsStream(source)?.use { it.readBytes() }
            if (bytes == null) {
                "is not on the class path"
            } else {
                return ManifestFragment(source, ManifestTree.read(mapper.readValue(bytes, Map::class.java)))
            }
        } catch (malformed: JacksonException) {
            "is not JSON: ${malformed.originalMessage}"
        } catch (invalid: IllegalArgumentException) {
            "is not a Manifest: ${invalid.message}"
        }
        throw ManifestLoadException(listOf("fragment $source $problem"))
    }
}

/** Fragments of a service could not be read; [problems] lists each one. */
internal class ManifestLoadException(val problems: List<String>) :
    IllegalStateException("The Manifest fragments could not be read:\n" + problems.joinToString("\n") { "- $it" })
