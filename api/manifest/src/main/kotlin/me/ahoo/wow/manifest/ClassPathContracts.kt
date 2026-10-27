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

package me.ahoo.wow.manifest

import me.ahoo.wow.api.annotation.InternalWowApi
import java.io.IOException
import java.net.URL

/**
 * The one reader of the peer contracts on a class path: every [PeerContracts.RESOURCE], one per
 * api jar (or directory) that carries the contracts of its contexts, each parsed by the caller's JSON library into
 * the plain tree [ManifestTree.read] reads, so this module stays free of one. The same contract found twice is read
 * once. A node's contract directory and `wow-client`'s command names both read through it.
 */
@InternalWowApi
object ClassPathContracts {
    /**
     * The contracts on [classLoader], each parsed by [parse]; a [ContractException] listing every contract that cannot
     * be read, is not JSON ([parse] throws) or does not read as a Manifest.
     */
    @JvmStatic
    fun load(classLoader: ClassLoader, parse: (ByteArray) -> Any?): List<ManifestFragment> {
        val problems = mutableListOf<String>()
        val contracts = classLoader.getResources(PeerContracts.RESOURCE).toList().mapNotNull {
            read(it, parse, problems)
        }
        if (problems.isNotEmpty()) {
            throw ContractException(problems)
        }
        return contracts.distinctBy { it.manifest }
    }

    @Suppress("TooGenericExceptionCaught") // The caller's JSON library throws its own exceptions.
    private fun read(resource: URL, parse: (ByteArray) -> Any?, problems: MutableList<String>): ManifestFragment? {
        val bytes = try {
            resource.openStream().use { it.readBytes() }
        } catch (missing: IOException) {
            problems += "contract $resource cannot be read: $missing"
            return null
        }
        val tree = try {
            parse(bytes)
        } catch (malformed: RuntimeException) {
            problems += "contract $resource is not JSON: ${malformed.message}"
            return null
        }
        return try {
            ManifestFragment(resource.toString(), ManifestTree.read(tree as? Map<*, *> ?: emptyMap<String, Any?>()))
        } catch (invalid: IllegalArgumentException) {
            problems += "contract $resource is not a Manifest: ${invalid.message}"
            null
        }
    }
}

/** The contracts of a service and its peers do not hold together; [problems] lists each problem. */
@InternalWowApi
class ContractException(val problems: List<String>) :
    IllegalStateException("The service's contracts do not hold together:\n" + problems.joinToString("\n") { "- $it" })
