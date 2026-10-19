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

import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.PeerContracts
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URL

/**
 * Reads the peer contracts on a class path (decision D234): every [PeerContracts.RESOURCE], one per api jar (or
 * directory) that carries the contracts of its contexts. The same contract found twice is read once.
 *
 * Fails with a [ContractException] listing every contract that cannot be read or does not read as a Manifest.
 */
internal class ContractReader(
    private val classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
    private val mapper: JsonMapper = JsonMapper.builder().build(),
) {
    fun read(): List<ManifestFragment> {
        val problems = mutableListOf<String>()
        val contracts = classLoader.getResources(PeerContracts.RESOURCE).toList().mapNotNull { read(it, problems) }
        if (problems.isNotEmpty()) {
            throw ContractException(problems)
        }
        return contracts.distinctBy { it.manifest }
    }

    private fun read(resource: URL, problems: MutableList<String>): ManifestFragment? {
        val problem = try {
            val bytes = resource.openStream().use { it.readBytes() }
            return ManifestFragment(resource.toString(), ManifestTree.read(mapper.readValue(bytes, Map::class.java)))
        } catch (malformed: JacksonException) {
            "is not JSON: ${malformed.originalMessage}"
        } catch (invalid: IllegalArgumentException) {
            "is not a Manifest: ${invalid.message}"
        } catch (missing: IOException) {
            "cannot be read: $missing"
        }
        problems += "contract $resource $problem"
        return null
    }
}
