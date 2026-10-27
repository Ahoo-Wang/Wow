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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.Manifest

/**
 * Whether a service's Manifest and the aggregate models it runs say the same: every edge projects
 * the Manifest (routes, OpenAPI, clients), so a node whose models disagree with it would serve a contract it does not
 * keep. A node checks it when it is assembled and refuses to start on any difference:
 *
 * - an aggregate type in the Manifest without a model, or a model of an aggregate type the Manifest does not list;
 * - a command of the Manifest the model does not decide under that name, or a command the model decides
 *   ([AggregateModel.commandTypes]) that the Manifest does not list; the lifecycle commands are compared apart, and the
 *   schedule system commands every model decides (`wow.schedule.Set`, `Cancel`) are not listed;
 * - a former name ([me.ahoo.wow.manifest.CommandManifest.aliases]) the model does not read as that command, or one the
 *   model reads as the command and the Manifest does not list;
 * - a different [creation][me.ahoo.wow.manifest.Creation];
 * - a mounted command of the Manifest the model does not mount under that name, or one the model mounts
 *   ([AggregateModel.mountedCommandTypes]) that the Manifest does not list as mounted;
 * - an event of the Manifest the model does not name so, or of another revision;
 * - a different set of lifecycle commands.
 */
internal object ManifestCheck {
    /** Every difference between [manifest] and [models]; empty when they agree. */
    fun differences(manifest: Manifest, models: Collection<AggregateModel<*>>): List<String> {
        val declared = manifest.contexts.flatMap { context ->
            context.aggregates.map { AggregateType(context.name, it.name) to it }
        }.toMap()
        val modelled = models.associateBy { it.type }
        return buildList {
            (modelled.keys - declared.keys).forEach { add("aggregate $it has a model but is not in the Manifest") }
            (declared.keys - modelled.keys).forEach { add("aggregate $it is in the Manifest but has no model") }
            declared.forEach { (type, aggregate) ->
                modelled[type]?.let { addAll(differences(type, aggregate, it)) }
            }
        }
    }

    /** Fails with every difference between [manifest] and [models], if there is any. */
    fun check(manifest: Manifest, models: Collection<AggregateModel<*>>) {
        val differences = differences(manifest, models)
        if (differences.isNotEmpty()) {
            throw ManifestMismatchException(differences)
        }
    }

    private fun differences(type: AggregateType, aggregate: AggregateManifest, model: AggregateModel<*>): List<String> =
        buildList {
            addAll(commandDifferences(type, aggregate, model))
            aggregate.events.forEach { event ->
                val name = fullName(type, event.name)
                val eventType = model.findEventType(name)
                when {
                    eventType == null || eventType.name != name ->
                        add("event $name is in the Manifest but the model of $type does not name it so")

                    eventType.revision != event.revision ->
                        add(
                            "event $name is of revision ${event.revision} in the Manifest, ${eventType.revision} in the model"
                        )
                }
            }
            val lifecycle = model.lifecycleCommands.mapNotNull(Lifecycle::nameOf).toSet()
            (aggregate.lifecycle.toSet() - lifecycle).forEach {
                add("lifecycle command $it of $type is in the Manifest but not handled by the model")
            }
            (lifecycle - aggregate.lifecycle.toSet()).forEach {
                add("lifecycle command $it of $type is handled by the model but not in the Manifest")
            }
        }

    /** The differences in the commands, but for the lifecycle commands, compared apart. */
    private fun commandDifferences(
        type: AggregateType,
        aggregate: AggregateManifest,
        model: AggregateModel<*>,
    ): List<String> =
        buildList {
            val (mounted, decided) = aggregate.commands.partition { it.mounted }
            val listed = decided.mapTo(HashSet()) { fullName(type, it.name) }
            model.commandTypes
                .filter { Lifecycle.nameOf(it.type) == null && Schedules.nameOf(it.type) == null && it.name !in listed }
                .forEach { add("command ${it.name} is decided by the model of $type but not in the Manifest") }
            val listedMounted = mounted.mapTo(HashSet()) { fullName(type, it.name) }
            model.mountedCommandTypes.filter { it.name !in listedMounted }
                .forEach { add("command ${it.name} is mounted by the model of $type but not in the Manifest") }
            mounted.map { fullName(type, it.name) }.filter { model.findMountedCommandType(it)?.name != it }
                .forEach { add("command $it is mounted in the Manifest but the model of $type does not mount it") }
            decided.forEach { command ->
                val name = fullName(type, command.name)
                val commandType = model.findCommandType(name)
                if (commandType == null || commandType.name != name) {
                    add("command $name is in the Manifest but the model of $type does not decide it")
                    return@forEach
                }
                val aliases = command.aliases.map { fullName(type, it) }
                aliases.filter { model.findCommandType(it) != commandType }.forEach {
                    add("command $name has the former name $it in the Manifest but not in the model of $type")
                }
                (commandType.aliases - aliases.toSet()).forEach {
                    add("command $name has the former name $it in the model of $type but not in the Manifest")
                }
                val creation = model.decider(commandType.type)?.creation?.name
                if (creation != command.creation.name) {
                    add(
                        "command $name creates its aggregate ${command.creation} in the Manifest, $creation in the model"
                    )
                }
            }
        }

    /** A name of the Manifest as the model names it: one without a dot is within [type]. */
    private fun fullName(type: AggregateType, name: String): String = if ('.' in name) name else "$type.$name"
}

/** A service's Manifest and its models disagree; [differences] lists each difference. */
internal class ManifestMismatchException(val differences: List<String>) :
    IllegalStateException(
        "The Manifest and the aggregate models disagree:\n" + differences.joinToString("\n") { "- $it" },
    )
