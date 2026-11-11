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
 * The commands of [manifests] by their schemas, the one rule that names a command class: the
 * command of the schema `#/$defs/<binary name>` in whichever context decides it is `<context>.<aggregate>.<Command>`;
 * and their routes by their full type names, the only routes a client sends a command to ([routeOf]).
 * A mounted command is only an edge's, which no one sends, and is left out. A system command ([SystemCommands]) is
 * named by its system name on its target ([systemCommandTypeOf]): an aggregate's lifecycle commands and snapshot
 * rebuild, and a context's schedule job commands, whose target is the context's job aggregate
 * (`<context>.schedule`). On a conflict the first Manifest's wins. A node's contract directory and `wow-client` both
 * name commands through it.
 */
@InternalWowApi
class CommandIndex(manifests: List<Manifest>) {
    /**
     * The command of each schema on each aggregate type (`context.aggregate`) that decides one of it, in the order the
     * Manifests list them: a class several aggregates share has one entry per aggregate. The command a schema names
     * alone is its first; the aggregates of a schema are its keys.
     */
    private val commandsBySchema = HashMap<String, LinkedHashMap<String, String>>()
    private val schemasByCommand = HashMap<String, String>()
    private val routesByCommand = HashMap<String, List<RouteManifest>>()
    private val idPropertiesByCommand = HashMap<String, String>()
    private val systemByTarget = HashMap<String, MutableMap<String, String>>()
    private val internalCommands = HashSet<String>()

    init {
        manifests.forEach { manifest ->
            manifest.contexts.forEach { context ->
                context.aggregates.forEach { aggregate ->
                    aggregate.commands.filterNot { it.mounted }.forEach { command ->
                        val target = "${context.name}.${aggregate.name}"
                        val type = "$target.${command.name}"
                        routesByCommand.putIfAbsent(type, command.routes)
                        command.idProperty?.let { idPropertiesByCommand.putIfAbsent(type, it) }
                        if (command.system) {
                            systemByTarget.getOrPut(target, ::HashMap).putIfAbsent(command.name, type)
                            return@forEach
                        }
                        if (command.internal) internalCommands += type
                        commandsBySchema.getOrPut(command.schema, ::LinkedHashMap).putIfAbsent(target, type)
                        schemasByCommand.putIfAbsent(type, command.schema)
                    }
                }
                // The context's schedule job commands, each to the job its route's id names.
                val jobs = "${context.name}.${SystemCommands.JOB_AGGREGATE}"
                context.commands.filter { it.system }.forEach { command ->
                    val type = "$jobs.${command.name}"
                    routesByCommand.putIfAbsent(type, command.routes)
                    systemByTarget.getOrPut(jobs, ::HashMap).putIfAbsent(command.name, type)
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
        if (Manifest.isSchemaRef(reference)) {
            commandsBySchema[reference]?.values?.first()
        } else {
            reference.takeIf { it in schemasByCommand }
        }

    /**
     * The full type name of the command class [type] on the aggregate type [target] (`context.aggregate`), or null
     * when that aggregate type decides none of its schema: of a class several aggregates decide, the one of [target].
     */
    fun commandTypeOf(target: String, type: Class<*>): String? =
        commandsBySchema[Manifest.schemaRef(type.name)]?.get(target)

    /**
     * The full type name of the system command [name] (`wow.lifecycle.DeleteAggregate`, a [SystemCommands] name) on the
     * aggregate type [target] (`context.aggregate`, `context.schedule` for a job command); null when its contract
     * lists none there.
     */
    fun systemCommandTypeOf(target: String, name: String): String? = systemByTarget[target]?.get(name)

    /**
     * Whether the command type [commandType] (`ctx.agg.Command`) is internal ([CommandManifest.internal]): taken on its
     * service's internal port alone, from another service, never on the public routes.
     */
    fun isInternal(commandType: String): Boolean = commandType in internalCommands

    /** The aggregate types (`context.aggregate`) that list a command of the schema [schema], a mounted one left out. */
    fun aggregatesOf(schema: String): List<String> = commandsBySchema[schema]?.keys?.toList().orEmpty()

    /** The schema reference of the command type [commandType] (`ctx.agg.Command`). */
    fun schemaOf(commandType: String): String? = schemasByCommand[commandType]

    /**
     * The property of the body of a command of [commandType] (`ctx.agg.Command`) that holds its aggregate's id
     * ([CommandManifest.idProperty]); null for one whose request names its aggregate.
     */
    fun idPropertyOf(commandType: String): String? = idPropertiesByCommand[commandType]

    /**
     * The route of the command type [commandType] (`ctx.agg.Command`) to an aggregate by its id when [byId], else one
     * that names no id ([CommandRoutes.routeOf]); null when no contract lists that route.
     */
    fun routeOf(commandType: String, byId: Boolean): RouteManifest? =
        routesByCommand[commandType]?.let { CommandRoutes.routeOf(it, byId) }
}
