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

package me.ahoo.wow.test

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.CommandTypes
import me.ahoo.wow.kernel.ModelProvider
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.ConcurrentHashMap

/**
 * Names the commands a saga under test derives as the runtime does (decision D234 ⑤), from what a domain module's
 * test class path carries: the Manifest fragments of its own modules (each [ModelProvider.fragment]) and the peer
 * contracts of the api jars it depends on ([CONTRACT], one per jar). A command class is decided by the aggregates
 * whose contract lists its schema (`#/$defs/<binary name>`), a mounted command (decision D182) left out, so a handler
 * that leaves a command's ref out sends it where the service would. Its [nameOf] is [CommandTypes.SIMPLE_NAMES]'.
 *
 * A contract that cannot be read is skipped: the specification checks the saga, not the build that packaged it.
 */
internal class ClassPathCommandTypes(private val aggregates: Map<String, List<AggregateType>>) : CommandTypes {
    override fun nameOf(target: AggregateType, command: Class<*>): String? =
        CommandTypes.SIMPLE_NAMES.nameOf(target, command)

    override fun aggregatesOf(command: Class<*>): List<AggregateType> =
        aggregates["$DEFS_PREFIX${command.name}"].orEmpty()

    companion object {
        /** The resource of an api jar's peer contract (`PeerContracts.RESOURCE` of `:manifest`, decision D234). */
        const val CONTRACT: String = "META-INF/wow/contract.json"

        private const val DEFS_PREFIX = "#/\$defs/"

        private val mapper = JsonMapper.builder().build()

        private val loaded = ConcurrentHashMap<ClassLoader, ClassPathCommandTypes>()

        /** The command types of the class path of [classLoader], read once per class loader. */
        fun of(classLoader: ClassLoader = Thread.currentThread().contextClassLoader): ClassPathCommandTypes =
            loaded.computeIfAbsent(classLoader, ::read)

        private fun read(classLoader: ClassLoader): ClassPathCommandTypes {
            val fragments = runCatching { ModelProvider.load(classLoader).map { it.fragment } }.getOrDefault(emptyList())
                .mapNotNull(classLoader::getResource)
            val contracts = classLoader.getResources(CONTRACT).toList()
            val aggregates = LinkedHashMap<String, MutableSet<AggregateType>>()
            (fragments + contracts).distinct().forEach { resource ->
                val manifest = runCatching {
                    resource.openStream().use { mapper.readValue(it.readBytes(), Map::class.java) }
                }.getOrNull() ?: return@forEach
                commandsOf(manifest).forEach { (schema, type) -> aggregates.getOrPut(schema, ::LinkedHashSet) += type }
            }
            return ClassPathCommandTypes(aggregates.mapValues { it.value.toList() })
        }

        /** Each command a Manifest's contexts list, by its schema, with the aggregate type that decides it. */
        internal fun commandsOf(manifest: Map<*, *>): List<Pair<String, AggregateType>> =
            manifest.objects("contexts").flatMap { context ->
                val contextName = context["name"] as? String ?: return@flatMap emptyList()
                context.objects("aggregates").flatMap { aggregate ->
                    val aggregateName = aggregate["name"] as? String ?: return@flatMap emptyList()
                    aggregate.objects("commands").filterNot { it["mounted"] == true }.mapNotNull { command ->
                        (command["schema"] as? String)?.let { it to AggregateType(contextName, aggregateName) }
                    }
                }
            }

        private fun Map<*, *>.objects(key: String): List<Map<*, *>> =
            (this[key] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
    }
}
