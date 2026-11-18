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
import java.net.URL

/**
 * Whether the classes a peer contract lists are where its consumer runs, context by context: a consumer may hold a
 * context's contract with none of its classes and address it by JSON; it may not hold it with only some of them. A
 * context's commands and events all live in its api module, so some of them present and others not is a contract and
 * types of different versions (a Maven build resolving the two apart), which would fail later, at decoding. The build's
 * merge and the node's startup check every contract on their class path ([problems]), each finding a class its own
 * way: the merge in its class path's entries, the node in its class loader ([locator]).
 */
@InternalWowApi
object ContractClasses {
    /**
     * Why [contract] does not hold together with its types: one problem per class of a context that [locate] finds in
     * no class path entry while it finds others of that context, naming the contract's [ManifestFragment.source] and
     * the entries the context's present classes come from; none for a context whose classes are all there, or none of
     * them.
     */
    @JvmStatic
    fun problems(contract: ManifestFragment, locate: (String) -> String?): List<String> =
        contract.manifest.contexts.flatMap { context ->
            val located = PeerContracts.classesOf(context).associateWith(locate)
            val present = located.values.filterNotNull().distinct().sorted()
            val missing = located.filterValues { it == null }.keys.sorted()
            if (present.isEmpty() || missing.isEmpty()) return@flatMap emptyList()
            missing.map { binary ->
                "$binary, which the contract in ${contract.source} lists for context ${context.name}, is in no class " +
                    "path entry, while the context's other classes are in ${present.joinToString()}: the contract and " +
                    "the types of its api module are of different versions. Depend on one version of the module, its " +
                    "types and its contract (Maven: the module and its classifier contract, of one version)."
            }
        }

    /** Where [classLoader] holds a class, by its binary name: the jar's file name, or the directory; null for none. */
    @JvmStatic
    fun locator(classLoader: ClassLoader): (String) -> String? = { binary ->
        classLoader.getResource(binary.replace('.', '/') + ".class")?.let { where(it, binary) }
    }

    /** The class path entry of [resource], the class file of [binary]: a jar's file name, else its directory. */
    internal fun where(resource: URL, binary: String): String {
        val location = resource.toString()
        val jar = location.substringBefore("!/", missingDelimiterValue = "")
        return if (jar.isNotEmpty()) {
            jar.substringAfterLast('/')
        } else {
            location.removeSuffix(binary.replace('.', '/') + ".class").removePrefix("file:")
        }
    }
}
