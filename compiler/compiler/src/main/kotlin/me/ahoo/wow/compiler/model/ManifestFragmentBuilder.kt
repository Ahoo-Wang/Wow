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
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Creation
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.FunctionKind
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.Ownership
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.manifest.SnapshotManifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.manifest.SubscriptionStart

/**
 * The Manifest fragment of a module (decisions D73, D74): the structure and the type list the model processor read,
 * with every schema left as a reference to `$defs` keyed by the JVM binary name of its class. `$defs` stays empty
 * and the state's fingerprint null: the build fills them in by introspecting the compiled classes with Jackson
 * (`wow-gradle-plugin`), since only Jackson knows the JSON its serializers write.
 *
 * Names follow the generated model (decision D55): a command or an event is named by its `@Name`, or its class's
 * simple name. A command's routes follow from its name and whether it creates its aggregate ([CommandRoutes], decision
 * D104). An aggregate lists the lifecycle commands its model handles by their system names
 * ([AggregateBinding.lifecycle]); their routes are the edge's to derive from that list (decision D113), so no command
 * gets them here.
 *
 * What M6 adds (decision D148):
 *
 * - an aggregate's `snapshot` and `queries` (decisions D128, D195): snapshots enabled unless
 *   `@AggregateRoot(snapshot = false)` turns them off, the `snapshot` query only when they are, and `events`, each
 *   with its routes ([QueryRoutes], decision D127);
 * - the context's `published` streams (decisions D58, D135, D156): the state events of every aggregate whose
 *   `@AggregateRoot(published)` is not false, with its state unless `publishState` is false, on its `topic`, else
 *   on `wow.<context>.<aggregate>.state`;
 * - a subscription's `start` (decisions D154, D194): what `@OnEvent(start)` declares, else the beginning for a
 *   projection or a handler of another context's stream, the end for a reactor or a saga of its own context's log;
 * - a saga's `derives` (decision D138): what `@OnEvent(derives)` declares, else the commands its return type names, a
 *   command a module's aggregate decides by its full type name and any other by its class's schema reference;
 * - the source `@OnEvent(aggregates)` names, of this context or another (decision D138);
 * - a saga's `@OnRejected` handlers (decisions D136, D153) as subscriptions with `rejected`, and its `@OnCommitted`
 *   handlers (decision D192) as subscriptions with `committed`.
 *
 * An aggregate's mounted commands (`@MountedCommand`, decision D182) are among its commands, marked `mounted`, with
 * routes with and without an id and the `transformsTo` they declare.
 */
internal object ManifestFragmentBuilder {
    fun build(
        context: String,
        description: String?,
        aggregates: List<AggregateBinding>,
        subscriptions: List<SubscriptionBinding>,
        errors: List<ErrorManifest>,
    ): Manifest {
        val aggregateManifests = aggregates.map { aggregate(context, it) }.sortedBy { it.name }
        val commands = CommandNames(context, aggregates)
        return Manifest(
            contexts = listOf(
                ContextManifest(
                    name = context,
                    description = description,
                    aggregates = aggregateManifests,
                    subscriptions = subscriptions.flatMap { subscriptions(context, it, aggregates, commands) }
                        .sortedBy { it.name },
                    published = aggregates.filter { it.publication.published }.map {
                        PublishedManifest(
                            it.aggregateName,
                            it.publication.topic ?: PublishedManifest.defaultTopic(context, it.aggregateName),
                            it.publication.includeState,
                        )
                    }.sortedBy { it.aggregate },
                    errors = errors.sortedBy { it.code },
                ),
            ),
        )
    }

    /**
     * What the fragment of [context] cannot say of [subscriptions]: an `@OnEvent(aggregates)` of [context] that is not
     * an aggregate of the module whose state the handler takes, and a command a saga's return type derives, which a
     * module's aggregate decides, that its `@OnEvent(derives)` does not list.
     */
    fun problems(
        context: String,
        aggregates: List<AggregateBinding>,
        subscriptions: List<SubscriptionBinding>,
    ): List<String> {
        val commands = CommandNames(context, aggregates)
        return subscriptions.flatMap { binding ->
            binding.handlers.flatMap { handler ->
                val where = "handler ${handler.subscription} of ${binding.processor}"
                val local = handler.aggregates.takeIf { handler.sourceContext == context }.orEmpty()
                local.map { it.substringAfter('.') }.mapNotNull { name ->
                    val aggregate = aggregates.firstOrNull { it.aggregateName == name }
                    when {
                        aggregate == null ->
                            "$where reads $context.$name, which is not an aggregate of this module"

                        handler.state != null && aggregate.state != handler.state ->
                            "$where reads $context.$name, whose state is ${aggregate.state}, not ${handler.state}"

                        else -> null
                    }
                } + undeclaredDerives(where, handler, commands)
            }
        }
    }

    private fun undeclaredDerives(where: String, handler: HandlerBinding, commands: CommandNames): List<String> {
        if (handler.derives.isEmpty()) {
            return emptyList()
        }
        return handler.derived.orEmpty().mapNotNull { commands.nameOf(it) }.filterNot { it in handler.derives }.map {
            "$where returns $it, which its @OnEvent(derives) does not list"
        }
    }

    private fun aggregate(context: String, binding: AggregateBinding): AggregateManifest {
        val eventNames = binding.sourcing.associate { it.event to (it.eventName ?: simpleName(it.type)) }
        val snapshot = SnapshotManifest(enabled = binding.snapshot)
        return AggregateManifest(
            name = binding.aggregateName,
            description = binding.description,
            state = StateManifest(Manifest.schemaRef(binding.stateType)),
            snapshot = snapshot,
            commands = (
                binding.commands.map { command ->
                    val name = command.commandName ?: simpleName(command.type)
                    val creation = Creation.valueOf(command.creation)
                    CommandManifest(
                        name = name,
                        aliases = command.aliases,
                        schema = Manifest.schemaRef(command.type),
                        creation = creation,
                        produces = produces(command, binding.afters, eventNames),
                        routes = CommandRoutes.of(context, binding.aggregateName, name, creation),
                    )
                } + binding.mounted.map { mounted ->
                    val name = mounted.commandName ?: simpleName(mounted.type)
                    CommandManifest(
                        name = name,
                        schema = Manifest.schemaRef(mounted.type),
                        routes = CommandRoutes.of(context, binding.aggregateName, name, Creation.NONE, mounted = true),
                        mounted = true,
                        transformsTo = mounted.transformsTo.distinct().sorted().ifEmpty { null },
                    )
                }
                ).sortedBy { it.name },
            events = binding.sourcing.map { sourcing ->
                EventManifest(
                    name = eventNames.getValue(sourcing.event),
                    aliases = sourcing.aliases,
                    revision = sourcing.revision,
                    schema = Manifest.schemaRef(sourcing.type),
                )
            }.sortedBy { it.name },
            lifecycle = binding.lifecycle,
            queries = QueryRoutes.queries(context, binding.aggregateName, snapshot.enabled),
            schedules = binding.commands.flatMap { it.schedules }.distinct().sorted().ifEmpty { null },
            ownership = Ownership.valueOf(binding.ownership),
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

    /**
     * Each handler of [binding] is a subscription (design 04 §1), and so is each of its `@OnRejected` and
     * `@OnCommitted` handlers.
     */
    private fun subscriptions(
        context: String,
        binding: SubscriptionBinding,
        aggregates: List<AggregateBinding>,
        commands: CommandNames,
    ): List<SubscriptionManifest> {
        // A processor without exactly one kind is a problem, and a model with problems gets no fragment.
        val (kind, functionKind) = KINDS.getValue(requireNotNull(binding.kind))
        val handlers = binding.handlers.map { handler ->
            val derives = derives(handler.derives, handler.derived, commands)
            SubscriptionManifest(
                name = handler.subscription,
                kind = kind,
                source = source(context, handler, aggregates),
                function = FunctionManifest(functionKind, context, binding.name, handler.function),
                derives = derives.takeIf { kind == SubscriptionKind.SAGA },
                start = SubscriptionStart.valueOf(handler.startIn(context, binding.kind)),
                schedules = handler.schedules.distinct().sorted().ifEmpty { null },
                retry = handler.retry?.manifest(),
            )
        }
        val rejections = binding.rejections.map { rejection ->
            SubscriptionManifest(
                name = rejection.subscription,
                kind = kind,
                source = SubscriptionSource(),
                function = FunctionManifest(functionKind, context, binding.name, rejection.function),
                derives = derives(emptyList(), rejection.derived, commands),
                rejected = listOfNotNull(rejection.command?.let(commands::referenceOf)),
            )
        }
        val commits = binding.commits.map { committed ->
            SubscriptionManifest(
                name = committed.subscription,
                kind = kind,
                source = SubscriptionSource(),
                function = FunctionManifest(functionKind, context, binding.name, committed.function),
                derives = derives(emptyList(), committed.derived, commands),
                committed = listOfNotNull(committed.command?.let(commands::referenceOf)),
            )
        }
        return handlers + rejections + commits
    }

    /**
     * What [handler] reads: the aggregates `@OnEvent(aggregates)` names, of this context or another, else the module's
     * aggregate whose state it takes; nothing it can name for another state type. And whether its state is nullable
     * (decision D140).
     */
    private fun source(
        context: String,
        handler: HandlerBinding,
        aggregates: List<AggregateBinding>,
    ): SubscriptionSource {
        val nullable = handler.nullableState
        if (handler.aggregates.isNotEmpty()) {
            return SubscriptionSource(
                handler.sourceContext,
                handler.aggregates.map { it.substringAfter('.') },
                nullableState = nullable,
            )
        }
        val aggregate = aggregates.firstOrNull { it.state == handler.state }
            ?: return SubscriptionSource(nullableState = nullable)
        return SubscriptionSource(context, listOf(aggregate.aggregateName), nullableState = nullable)
    }

    /** The declared [derives], else the [derived] classes by [commands]' references; null when neither says. */
    private fun derives(derives: List<String>, derived: List<String>?, commands: CommandNames): List<String>? =
        if (derives.isNotEmpty()) {
            derives.distinct().sorted()
        } else {
            derived?.map(commands::referenceOf)?.distinct()?.sorted()
        }

    /**
     * The full type names of the commands the module's aggregates decide, by their classes' binary names: a class two
     * aggregates decide has none.
     */
    private class CommandNames(context: String, aggregates: List<AggregateBinding>) {
        private val names: Map<String, String> = aggregates.flatMap { aggregate ->
            aggregate.commands.map {
                it.type to "$context.${aggregate.aggregateName}.${it.commandName ?: simpleName(it.type)}"
            }
        }.groupBy({ it.first }, { it.second }).mapNotNull { (type, names) -> names.singleOrNull()?.let { type to it } }
            .toMap()

        /** The full type name of the command class [type], when an aggregate of the module decides it. */
        fun nameOf(type: String): String? = names[type]

        /** The command class [type] as the Manifest refers to it: its full type name, else its schema reference. */
        fun referenceOf(type: String): String = nameOf(type) ?: Manifest.schemaRef(type)
    }

    private val KINDS = mapOf(
        "PROJECT" to (SubscriptionKind.PROJECTION to FunctionKind.PROJECT),
        "REACT" to (SubscriptionKind.REACTOR to FunctionKind.REACT),
        "SAGA" to (SubscriptionKind.SAGA to FunctionKind.SAGA),
    )

    /** What `Class.getSimpleName` gives for the binary name [type], as the kernel names a type by default. */
    private fun simpleName(type: String): String = type.substringAfterLast('.').substringAfterLast('$')
}
