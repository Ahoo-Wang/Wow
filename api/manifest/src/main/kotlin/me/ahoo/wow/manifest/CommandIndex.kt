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

/**
 * The commands of [manifests] by their schemas (decisions D157, D234), the one rule that names a command class: the
 * command of the schema `#/$defs/<binary name>` in whichever context decides it is `<context>.<aggregate>.<Command>`.
 * A mounted command (decision D182) is only an edge's, which no one sends, and is left out. On a conflict the first
 * Manifest's wins. A node's contract directory and `wow-client` both name commands through it (decision D293).
 */
@InternalWowApi
class CommandIndex(manifests: List<Manifest>) {
    private val commandsBySchema = HashMap<String, String>()
    private val schemasByCommand = HashMap<String, String>()
    private val aggregatesBySchema = HashMap<String, MutableSet<String>>()

    init {
        manifests.forEach { manifest ->
            manifest.contexts.forEach { context ->
                context.aggregates.forEach { aggregate ->
                    aggregate.commands.filterNot { it.mounted }.forEach { command ->
                        val type = "${context.name}.${aggregate.name}.${command.name}"
                        commandsBySchema.putIfAbsent(command.schema, type)
                        aggregatesBySchema.getOrPut(command.schema, ::LinkedHashSet) += "${context.name}.${aggregate.name}"
                        schemasByCommand[type] = command.schema
                    }
                }
            }
        }
    }

    /**
     * The full type name (`ctx.agg.Command`) of the command [reference] names: a schema reference
     * (`#/$defs/<binary name>`) resolves to the command of that schema; a full type name is itself when a context
     * decides it. Null when no context decides it.
     */
    fun commandTypeOf(reference: String): String? =
        if (reference.startsWith(
                DEFS_PREFIX
            )
        ) {
            commandsBySchema[reference]
        } else {
            reference.takeIf { it in schemasByCommand }
        }

    /**
     * The full type name of the command class [type] on the aggregate type [target] (`context.aggregate`), or null
     * when that aggregate type decides none of its schema.
     */
    fun commandTypeOf(target: String, type: Class<*>): String? =
        commandTypeOf(Manifest.schemaRef(type.name))?.takeIf { it.startsWith("$target.") }

    /** The aggregate types (`context.aggregate`) that list a command of the schema [schema], a mounted one left out. */
    fun aggregatesOf(schema: String): List<String> = aggregatesBySchema[schema].orEmpty().toList()

    /** The schema reference of the command type [commandType] (`ctx.agg.Command`). */
    fun schemaOf(commandType: String): String? = schemasByCommand[commandType]

    private companion object {
        const val DEFS_PREFIX = "#/\$defs/"
    }
}
