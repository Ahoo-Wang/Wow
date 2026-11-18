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
 * Whether the classes a peer contract lists are where its consumer runs: an api module's contract and its types are
 * one module, and a contract of one version beside types of another lists classes those types lack (a Maven build
 * resolving the two separately). The build's merge and the node's startup check every contract on their class path
 * ([problems]), each finding a class its own way: the merge in its class path's entries, the node in its class
 * loader ([locator]).
 */
@InternalWowApi
object ContractClasses {
    /**
     * Why [contract] does not hold together with its types: one problem per class it lists that [locate] finds in no
     * class path entry, naming the contract's [ManifestFragment.source] and the entries that hold its other classes, the
     * types it is beside; none when every class is there.
     */
    @JvmStatic
    fun problems(contract: ManifestFragment, locate: (String) -> String?): List<String> {
        val located = contract.manifest.contexts.flatMap(PeerContracts::classesOf).distinct().associateWith(locate)
        val missing = located.filterValues { it == null }.keys.sorted()
        if (missing.isEmpty()) return emptyList()
        val types = located.values.filterNotNull().distinct().sorted()
        val beside = if (types.isEmpty()) "" else ", while its other classes are in ${types.joinToString()}"
        return missing.map { binary ->
            "$binary, which the contract in ${contract.source} lists, is in no class path entry$beside: the " +
                "contract and the types of its api module are of different versions. Depend on one version of the " +
                "module, its types and its contract (Maven: the module and its classifier contract, of one version)."
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
