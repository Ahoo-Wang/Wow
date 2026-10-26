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

package me.ahoo.wow.runtime.contract

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ClassPathContracts
import me.ahoo.wow.manifest.CommandIndex
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.ContractException
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.PeerContracts
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.spi.log.EventStreamRecord
import tools.jackson.databind.json.JsonMapper

/**
 * The contracts a service knows (decisions D139, D234): its own Manifest, [local], and the peer contracts, [peers]
 * (`META-INF/wow/contract.json` of each api jar on the class path, which the Gradle plugin packages from the context's
 * domain module; [load] reads them), without the contexts [local] serves ([PeerContracts.outside], DX gap G21: the
 * service's own api is on its class path, and on a class path that several services share, another service's api
 * carries no contract of this one). It answers, for any context of either:
 *
 * - whether a context is the service's own ([isLocal]), and its contract ([contextOf], [aggregateOf]);
 * - the stream a context publishes for an aggregate, with its topic and whether it carries the state ([publishedOf]);
 * - the full type name of a command a Manifest refers to by its schema, `#/$defs/<binary name>` (decision D157), and
 *   back ([commandTypeOf], [schemaOf]);
 * - the contexts the service's sagas call synchronously ([contextsCalled], decision D259);
 * - the subscriptions of a context and their kinds ([subscriptionOf]); as a [SubscriptionDirectory], it lets a wait
 *   chain name a subscription of another context, and [over] puts it behind the subscription runtime of the node's
 *   own context, which knows its subscriptions' progress.
 *
 * **Startup checks**: building it fails with a [ContractException] listing every problem:
 *
 * - the peer contracts conflict ([PeerContracts.conflicts]): two of them declare one context;
 * - a subscription of [local] reads another context (`@OnEvent(aggregates = ["ctx.agg"])`, decision D138) that no
 *   Manifest declares, an aggregate the context does not have, or one it does not publish;
 * - such a subscription reads events by type (decision D264) that the contract of that aggregate does not declare (DX-1c);
 * - such a subscription reads a stream published without the state (`includeState = false`) but its handler takes a
 *   state that is not nullable (decision D140): its Manifest source does not say `nullableState`, which the compiler
 *   records for `StateEvent<S?>` and `StateEvent<*>`, and it is not among the [nullableStates] given.
 *
 * A command a saga refers to ([SubscriptionManifest.derives], [SubscriptionManifest.rejected]) is resolved
 * ([commandTypeOf]) but not checked here: a lifecycle command (design 01 §6) is no context's, and whether a chain may
 * follow a saga's hop into a context is the wait's check (decision D138), from [derivedContextsOf].
 */
class ContractDirectory(
    val local: Manifest,
    peers: List<ManifestFragment> = emptyList(),
    /**
     * The subscriptions (`processor.function`) whose handler takes a `StateEvent` of a nullable state (D140), besides
     * those [local]'s Manifest marks so ([me.ahoo.wow.manifest.SubscriptionSource.nullableState]).
     */
    nullableStates: Set<String> = emptySet(),
) : SubscriptionDirectory {
    /** Every subscription of [local] whose handler takes a nullable state: given, or as the Manifest says. */
    val nullableStates: Set<String> = nullableStates + local.contexts.flatMap { context ->
        context.subscriptions.filter { it.source.nullableState }.map { it.name }
    }

    /** The peer contracts given, each without the contexts [local] serves, and without those left with none. */
    val peers: List<ManifestFragment> = PeerContracts.outside(local.contexts.map { it.name }, peers)

    private val manifests: List<Manifest> = listOf(local) + this.peers.map { it.manifest }

    /** [local] and the [peers], as the fragments `wow-client` names commands and models by (decision D293). */
    val fragments: List<ManifestFragment> get() = listOf(ManifestFragment(LOCAL_SOURCE, local)) + peers

    /** The names of the contexts [local] serves. */
    val localContexts: Set<String> get() = local.contexts.mapTo(LinkedHashSet()) { it.name }

    /** The names of the contexts the [peers] declare. */
    val peerContexts: Set<String> get() = peers.flatMapTo(LinkedHashSet()) { peer ->
        peer.manifest.contexts.map {
            it.name
        }
    }

    /** Every context of [local] and of the [peers], in that order. */
    val allContexts: List<ContextManifest> get() = manifests.flatMap { it.contexts }

    /** Every context by its name; on a conflict, which fails the startup, the service's own or the first peer's. */
    private val contexts: Map<String, Owned> = HashMap<String, Owned>().also { contexts ->
        manifests.forEach { manifest ->
            manifest.contexts.forEach { contexts.putIfAbsent(it.name, Owned(it, manifest === local)) }
        }
    }

    /** The commands of [local] and the [peers] by their schemas: `:manifest`'s one naming rule (decision D293). */
    val commands: CommandIndex = CommandIndex(manifests)

    init {
        // The peers no longer hold a context of the service, so what is left to refuse is among the peers alone.
        val problems = PeerContracts.conflicts(this.peers) +
            local.contexts.flatMap { context -> context.subscriptions.flatMap { sourceProblems(context, it) } }
        if (problems.isNotEmpty()) {
            throw ContractException(problems)
        }
    }

    private class Owned(val context: ContextManifest, val local: Boolean)

    /** Whether [context] is one of this service's own. */
    fun isLocal(context: String): Boolean = contexts[context]?.local == true

    /** The contract of [context], this service's or a peer's. */
    fun contextOf(context: String): ContextManifest? = contexts[context]?.context

    /** The contract of the aggregate [type]. */
    fun aggregateOf(type: AggregateType): AggregateManifest? =
        contextOf(type.context)?.aggregates?.firstOrNull { it.name == type.name }

    /** The stream of [type]'s state events its context publishes, with its topic and `includeState`; null if none. */
    fun publishedOf(type: AggregateType): PublishedManifest? =
        contextOf(type.context)?.published?.firstOrNull { it.aggregate == type.name }

    /**
     * The full type name (`ctx.agg.Command`) of the command [reference] names: a schema reference
     * (`#/$defs/<binary name>`) resolves to the command of that schema, in whichever context decides it; a full type
     * name is itself when a context decides it. Null when no context of the service or its peers decides it.
     */
    fun commandTypeOf(reference: String): String? = commands.commandTypeOf(reference)

    /**
     * The aggregate types whose contract, the service's or a peer's, lists a command of the schema [schema]
     * (`#/$defs/<binary name>`), a mounted one left out: where a saga sends a command of that class whose ref it
     * leaves out (decision D234 ⑤). Empty when none does.
     */
    fun aggregatesOf(schema: String): List<AggregateType> =
        commands.aggregatesOf(schema).map { AggregateType(it.substringBefore('.'), it.substringAfter('.')) }

    /** The schema reference of the command type [commandType] (`ctx.agg.Command`), as its context's Manifest gives it. */
    fun schemaOf(commandType: String): String? = commands.schemaOf(commandType)

    /** The subscription [name] (`processor.function`) of [context]. */
    fun subscriptionOf(context: String, name: String): SubscriptionManifest? =
        contextOf(context)?.subscriptions?.firstOrNull { it.name == name }

    /**
     * The kind of [subscription] of [context], as the context's Manifest lists it, or the built-in snapshot
     * subscription ([Hop.SNAPSHOT_SUBSCRIPTION]) of a context with an aggregate that keeps snapshots. A saga's
     * `@OnRejected` handler is not a wait's hop: its rejection ended the wait; an `@OnCommitted` handler is a saga's
     * hop (decision D192), which the receipts of the commands it answers signal.
     */
    override fun kindOf(context: String, subscription: String): FunctionKind? {
        val owned = contextOf(context) ?: return null
        if (subscription == Hop.SNAPSHOT_SUBSCRIPTION) {
            return FunctionKind.SNAPSHOT.takeIf { owned.aggregates.any { it.snapshot.enabled } }
        }
        val declared = subscriptionOf(context, subscription)?.takeIf { it.rejected == null } ?: return null
        return when (declared.kind) {
            SubscriptionKind.PROJECTION -> FunctionKind.PROJECT
            SubscriptionKind.REACTOR -> FunctionKind.REACT
            SubscriptionKind.SAGA -> FunctionKind.SAGA
        }
    }

    /**
     * Whether [subscription] of [type]'s context handles the commits of [type]: the snapshot subscription when [type]
     * keeps snapshots; another one when it reads its own context and names no aggregates or [type]'s.
     */
    override fun covers(subscription: String, type: AggregateType): Boolean {
        if (subscription == Hop.SNAPSHOT_SUBSCRIPTION) {
            return aggregateOf(type)?.snapshot?.enabled == true
        }
        if (kindOf(type.context, subscription) == null) {
            return false
        }
        val source = subscriptionOf(type.context, subscription)!!.source
        return (source.context ?: type.context) == type.context &&
            (source.aggregates.isEmpty() || type.name in source.aggregates)
    }

    /** Whether a Manifest, the service's own or a peer's, declares [context]. */
    override fun hasContext(context: String): Boolean = contextOf(context) != null

    /**
     * The contexts of the commands the saga [subscription] of [context] derives or calls, as its Manifest declares
     * them ([SubscriptionManifest.derives] and [SubscriptionManifest.calls], decisions D138, D259): each command's
     * own context, a schema reference resolved through the Manifest of the context that decides it ([commandTypeOf]).
     * Null when the subscription declares no `derives`, or when one of them cannot be resolved: a wait chain after
     * its hop is then not checked.
     */
    override fun derivedContextsOf(context: String, subscription: String): Set<String>? {
        val declared = subscriptionOf(context, subscription)?.takeIf { it.rejected == null } ?: return null
        val derives = declared.derives ?: return null
        return (derives + declared.calls.orEmpty()).mapTo(LinkedHashSet()) { reference ->
            commandContextOf(reference) ?: return null
        }
    }

    /** The context of the command [reference] names, resolved as [commandTypeOf] does; null when it cannot be. */
    private fun commandContextOf(reference: String): String? =
        (commandTypeOf(reference) ?: reference.takeUnless { it.startsWith(DEFS_PREFIX) })?.substringBefore('.')

    /**
     * The contexts the saga subscriptions of [local] call synchronously ([SubscriptionManifest.calls], decision D259),
     * by subscription (`context/processor.function`): each command's context, or [UNKNOWN_CONTEXT] for a command no
     * Manifest of the service or its peers decides. Unlike [commandsOutside], a called command needs no command topic:
     * the node calls its context's gateway.
     */
    fun contextsCalled(): Map<String, Set<String>> = local.contexts.flatMap { context ->
        context.subscriptions.mapNotNull { subscription ->
            val calls = subscription.calls.orEmpty()
            calls.takeIf { it.isNotEmpty() }?.let {
                "${context.name}/${subscription.name}" to it.mapTo(sortedSetOf()) { reference ->
                    commandContextOf(reference) ?: UNKNOWN_CONTEXT
                }
            }
        }
    }.toMap()

    /**
     * The commands the subscriptions of [local] send outside [hosted], the contexts a node serves (DX gap G9): for
     * each subscription (`context/processor.function`), the contexts of the commands its saga derives
     * ([SubscriptionManifest.derives]), handles the receipts of ([SubscriptionManifest.rejected],
     * [SubscriptionManifest.committed]) or schedules
     * ([SubscriptionManifest.schedules]) that [hosted] does not hold. Such a command goes through a command topic
     * (decision D33). A command referred to by a schema no Manifest decides is another context's too, named
     * [UNKNOWN_CONTEXT]; a lifecycle command (design 01 §6) is no context's and is left out.
     */
    fun commandsOutside(hosted: Set<String>): Map<String, Set<String>> = local.contexts.flatMap { context ->
        context.subscriptions.mapNotNull { subscription ->
            val references = subscription.derives.orEmpty() + subscription.rejected.orEmpty() +
                subscription.committed.orEmpty() + subscription.schedules.orEmpty()
            val outside = references.filterNot { it in LIFECYCLE_COMMANDS }.mapNotNullTo(sortedSetOf()) { reference ->
                val type = if (reference.startsWith(DEFS_PREFIX)) commandTypeOf(reference) else reference
                val target = type?.substringBefore('.') ?: UNKNOWN_CONTEXT
                target.takeUnless { it in hosted }
            }
            outside.takeIf { it.isNotEmpty() }?.let { "${context.name}/${subscription.name}" to it }
        }
    }.toMap()

    /** A Manifest says nothing of a subscription's progress (decision D63: a hop of another context is not checked). */
    override suspend fun hasHandled(subscription: String, stream: EventStreamRecord): Boolean = false

    /**
     * This directory behind [runtime], the subscription directory of the node's [ownContext]: a hop of it is
     * answered by [runtime], which knows the subscriptions the node runs and their progress; a hop of any other
     * context, this service's or a peer's, by the Manifests.
     */
    fun over(
        runtime: SubscriptionDirectory,
        ownContext: String
    ): SubscriptionDirectory = object : SubscriptionDirectory {
        override fun kindOf(context: String, subscription: String): FunctionKind? =
            if (context == ownContext) {
                runtime.kindOf(context, subscription)
            } else {
                this@ContractDirectory.kindOf(context, subscription)
            }

        override fun covers(subscription: String, type: AggregateType): Boolean =
            if (type.context == ownContext) {
                runtime.covers(subscription, type)
            } else {
                this@ContractDirectory.covers(subscription, type)
            }

        override fun hasContext(context: String): Boolean =
            context == ownContext || this@ContractDirectory.hasContext(context)

        override fun derivedContextsOf(context: String, subscription: String): Set<String>? =
            this@ContractDirectory.derivedContextsOf(context, subscription)

        override suspend fun hasHandled(subscription: String, stream: EventStreamRecord): Boolean =
            runtime.hasHandled(subscription, stream)
    }

    /** The problems of a subscription of [context] that reads another context's published streams. */
    private fun sourceProblems(context: ContextManifest, subscription: SubscriptionManifest): List<String> {
        val source = subscription.source.context
        if (source == null || source == context.name || subscription.isReceiptHandler) {
            return emptyList()
        }
        val where = "subscription ${context.name}.${subscription.name}"
        val other = contextOf(source) ?: return listOf("$where reads context $source, which no Manifest declares")
        val aggregates = subscription.source.aggregates.ifEmpty { other.published.map { it.aggregate } }
        return aggregates.mapNotNull { aggregate ->
            val published = other.published.firstOrNull { it.aggregate == aggregate }
            when {
                other.aggregates.none { it.name == aggregate } ->
                    "$where reads aggregate $source.$aggregate, which context $source does not have"

                published == null -> "$where reads aggregate $source.$aggregate, which context $source does not publish"

                !published.includeState && subscription.name !in nullableStates ->
                    "$where reads $source.$aggregate, published without its state (${published.topic}), so its " +
                        "handler takes a nullable state: StateEvent<S?>"

                else -> null
            }
        } + eventProblems(where, other, aggregates, subscription.source.events)
    }

    /**
     * The [events] a handler of each event of a type (decision D264) reads from another context's [aggregates] that
     * [other]'s contract does not declare (DX-1c): its api on the class path is of another version than the contract.
     */
    private fun eventProblems(
        where: String,
        other: ContextManifest,
        aggregates: List<String>,
        events: List<String>,
    ): List<String> {
        val declared = other.aggregates.filter { it.name in aggregates }.flatMap { it.events }.map { it.name }.toSet()
        return events.filterNot { it in declared }.map {
            "$where reads the event $it, which the contract of ${other.name} does not declare for " +
                "${aggregates.joinToString()}: depend on the api module its service publishes"
        }
    }

    companion object {
        private const val DEFS_PREFIX = "#/\$defs/"

        /** What [commandsOutside] names the context of a command no Manifest of the service or its peers decides. */
        const val UNKNOWN_CONTEXT: String = "(a context no Manifest declares)"

        /** The source [fragments] names the service's own Manifest by. */
        const val LOCAL_SOURCE: String = "(the service's Manifest)"

        /** The lifecycle commands by their names and their schemas: no context's. */
        private val LIFECYCLE_COMMANDS: Set<String> = Lifecycle.COMMANDS.flatMapTo(HashSet()) { (name, type) ->
            listOf(name, Manifest.schemaRef(type.name))
        }

        /**
         * The directory of [local] and the peer contracts on [classLoader] ([ClassPathContracts], the one reader of
         * them); fails with a [ContractException] when a contract cannot be read, as when the directory cannot be built.
         */
        fun load(
            local: Manifest,
            classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
            mapper: JsonMapper = JsonMapper.builder().build(),
            nullableStates: Set<String> = emptySet(),
        ): ContractDirectory = ContractDirectory(
            local,
            ClassPathContracts.load(classLoader) { bytes -> mapper.readValue(bytes, Map::class.java) },
            nullableStates,
        )
    }
}
