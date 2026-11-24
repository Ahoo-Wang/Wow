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

/**
 * The service a build names, as the service module's jar carries it at [RESOURCE]: the [name] of the merged Manifest
 * (`wow { service }`, else the name the contracts of its contexts give, else its project's) and the [contexts] it
 * serves. The Gradle plugin's service role writes it with the Manifest (`mergeWowManifest`); the node refuses to start
 * under another name for those contexts. A build without the plugin writes none, and nothing is compared.
 *
 * A class path may hold several, one per service module on it: a test that runs several services in one process. The
 * builds that name a context the node serves are the ones its name is compared with.
 *
 * @property name the service's name, the merged Manifest's.
 * @property contexts the contexts the service serves, its own: no peer's.
 * @property source where it was read: the resource's URL.
 */
@InternalWowApi
data class ServiceBuild(val name: String, val contexts: List<String>, val source: String) {
    @InternalWowApi
    companion object {
        /** Where a service module's jar carries the service its build names. */
        const val RESOURCE: String = "META-INF/wow/service.json"

        /** The JSON tree the build writes at [RESOURCE] for [manifest], the merged Manifest of a service. */
        @JvmStatic
        fun treeOf(manifest: Manifest): Map<String, Any?> {
            val name = requireNotNull(manifest.service?.name) { "The merged Manifest names no service." }
            return linkedMapOf("name" to name, "contexts" to manifest.contexts.map { it.name }.sorted())
        }

        /**
         * The builds on [classLoader], each parsed by [parse] into a JSON tree, so this module stays free of a JSON
         * library; the same build found twice is read once. A [ContractException] lists every one that cannot be read,
         * is not JSON or names no service.
         */
        @JvmStatic
        fun load(classLoader: ClassLoader, parse: (ByteArray) -> Any?): List<ServiceBuild> {
            val problems = mutableListOf<String>()
            val builds = classLoader.getResources(RESOURCE).toList().mapNotNull { resource ->
                read(resource.toString(), problems) { resource.openStream().use { it.readBytes() }.let(parse) }
            }
            if (problems.isNotEmpty()) {
                throw ContractException(problems)
            }
            return builds.distinctBy { it.name to it.contexts }
        }

        @Suppress("TooGenericExceptionCaught") // The caller's JSON library throws its own exceptions.
        private fun read(source: String, problems: MutableList<String>, tree: () -> Any?): ServiceBuild? {
            val read = try {
                tree() as? Map<*, *>
            } catch (missing: IOException) {
                problems += "service build $source cannot be read: $missing"
                return null
            } catch (malformed: RuntimeException) {
                problems += "service build $source is not JSON: ${malformed.message}"
                return null
            }
            val name = (read?.get("name") as? String)?.takeIf { it.isNotBlank() }
            val contexts = (read?.get("contexts") as? List<*>)?.map { it as? String }
            if (name == null || contexts == null || contexts.any { it == null }) {
                problems += "service build $source names no service and its contexts: {\"name\": …, \"contexts\": […]}"
                return null
            }
            return ServiceBuild(name, contexts.filterNotNull(), source)
        }
    }
}
