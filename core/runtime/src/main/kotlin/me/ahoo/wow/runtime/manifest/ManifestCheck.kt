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
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.Manifest

/**
 * Whether a service's Manifest and the aggregate models it runs say the same (decision D108): every edge projects
 * the Manifest (routes, OpenAPI, clients), so a node whose models disagree with it would serve a contract it does not
 * keep. A node checks it when it is assembled and refuses to start on any difference:
 *
 * - an aggregate type in the Manifest without a model, or a model of an aggregate type the Manifest does not list;
 * - a command of the Manifest the model does not decide under that name, a former name
 *   ([me.ahoo.wow.manifest.CommandManifest.aliases]) the model does not read as that command, or a different [creation][me.ahoo.wow.manifest.Creation];
 * - an event of the Manifest the model does not name so, or of another revision;
 * - a different set of lifecycle commands.
 *
 * A model does not list its own command types, so a command the model decides and the Manifest leaves out is not
 * found here; both come from the same sources through the compiler, which writes every one into the fragment.
 */
object ManifestCheck {
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
            aggregate.commands.forEach { command ->
                val name = fullName(type, command.name)
                val commandType = model.findCommandType(name)
                if (commandType == null || commandType.name != name) {
                    add("command $name is in the Manifest but the model of $type does not decide it")
                    return@forEach
                }
                command.aliases.map { fullName(type, it) }.filter { model.findCommandType(it) != commandType }.forEach {
                    add("command $name has the former name $it in the Manifest but not in the model of $type")
                }
                val creation = model.decider(commandType.type)?.creation?.name
                if (creation != command.creation.name) {
                    add(
                        "command $name creates its aggregate ${command.creation} in the Manifest, $creation in the model"
                    )
                }
            }
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

    /** A name of the Manifest as the model names it: one without a dot is within [type]. */
    private fun fullName(type: AggregateType, name: String): String = if ('.' in name) name else "$type.$name"
}

/** A service's Manifest and its models disagree; [differences] lists each difference. */
class ManifestMismatchException(val differences: List<String>) :
    IllegalStateException(
        "The Manifest and the aggregate models disagree:\n" + differences.joinToString("\n") { "- $it" },
    )
