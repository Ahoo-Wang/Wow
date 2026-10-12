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

package me.ahoo.wow.compiler.model

import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Creation
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.FunctionKind
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource

/**
 * The Manifest fragment of a module (decisions D73, D74): the structure and the type list the model processor read,
 * with every schema left as a reference to `$defs` keyed by the JVM binary name of its class. `$defs` stays empty
 * and the state's fingerprint null: the build fills them in by introspecting the compiled classes with Jackson
 * (`wow-gradle-plugin`), since only Jackson knows the JSON its serializers write.
 *
 * Names follow the generated model (decision D55): a command or an event is named by its `@Name`, or its class's
 * simple name.
 */
internal object ManifestFragmentBuilder {
    fun build(
        context: String,
        description: String?,
        aggregates: List<AggregateBinding>,
        subscriptions: List<SubscriptionBinding>,
    ): Manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                name = context,
                description = description,
                aggregates = aggregates.map(::aggregate).sortedBy { it.name },
                subscriptions = subscriptions.flatMap { subscriptions(context, it, aggregates) }.sortedBy { it.name },
            ),
        ),
    )

    private fun aggregate(binding: AggregateBinding): AggregateManifest {
        val eventNames = binding.sourcing.associate { it.event to (it.eventName ?: simpleName(it.type)) }
        return AggregateManifest(
            name = binding.aggregateName,
            description = binding.description,
            state = StateManifest(Manifest.schemaRef(binding.stateType)),
            commands = binding.commands.map { command ->
                CommandManifest(
                    name = command.commandName ?: simpleName(command.type),
                    aliases = command.aliases,
                    schema = Manifest.schemaRef(command.type),
                    creation = Creation.valueOf(command.creation),
                    produces = produces(command, binding.afters, eventNames),
                )
            }.sortedBy { it.name },
            events = binding.sourcing.map { sourcing ->
                EventManifest(
                    name = eventNames.getValue(sourcing.event),
                    aliases = sourcing.aliases,
                    revision = sourcing.revision,
                    schema = Manifest.schemaRef(sourcing.type),
                )
            }.sortedBy { it.name },
        )
    }

    /**
     * The events [command] can produce: what its function yields and what the after hooks that run after it yield,
     * by event name; null when one of them does not say, or yields a class that is not an event of the aggregate.
     */
    private fun produces(
        command: CommandBinding,
        afters: List<AfterBinding>,
        eventNames: Map<String, String>,
    ): List<String>? {
        val yields = listOf(command.yields) + afters.filter { it.appliesTo(command.command) }.map { it.yields }
        val classes = yields.flatMap { it ?: return null }.distinct()
        return classes.map { eventNames[it] ?: return null }.distinct().sorted()
    }

    /** Each handler of [binding] is a subscription (design 04 §1). */
    private fun subscriptions(
        context: String,
        binding: SubscriptionBinding,
        aggregates: List<AggregateBinding>,
    ): List<SubscriptionManifest> {
        // A processor without exactly one kind is a problem, and a model with problems gets no fragment.
        val (kind, functionKind) = KINDS.getValue(requireNotNull(binding.kind))
        return binding.handlers.map { handler ->
            val aggregate = aggregates.firstOrNull { it.state == handler.state }
            SubscriptionManifest(
                name = handler.subscription,
                kind = kind,
                source = if (aggregate == null) {
                    SubscriptionSource()
                } else {
                    SubscriptionSource(context = context, aggregates = listOf(aggregate.aggregateName))
                },
                function = FunctionManifest(functionKind, context, binding.name, handler.function),
            )
        }
    }

    private val KINDS = mapOf(
        "PROJECT" to (SubscriptionKind.PROJECTION to FunctionKind.PROJECT),
        "REACT" to (SubscriptionKind.REACTOR to FunctionKind.REACT),
        "SAGA" to (SubscriptionKind.SAGA to FunctionKind.SAGA),
    )

    /** What `Class.getSimpleName` gives for the binary name [type], as the kernel names a type by default. */
    private fun simpleName(type: String): String = type.substringAfterLast('.').substringAfterLast('$')
}
