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
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.PeerManifests
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.spi.log.EventStreamRecord
import tools.jackson.databind.json.JsonMapper

/**
 * The contracts a service knows (decision D139): its own Manifest, [local], and the merged Manifests of its peers,
 * [peers] (`META-INF/wow/peers/<service>.json`, which the Gradle plugin's `wowPeers` configuration puts on the class
 * path; [load] reads them). It answers, for any context of either:
 *
 * - which service a context belongs to ([serviceOf], [isLocal]), and its contract ([contextOf], [aggregateOf]);
 * - the stream a context publishes for an aggregate, with its topic and whether it carries the state ([publishedOf]);
 * - the full type name of a command a Manifest refers to by its schema, `#/$defs/<binary name>` (decision D157), and
 *   back ([commandTypeOf], [schemaOf]);
 * - the subscriptions of a context and their kinds ([subscriptionOf]); as a [SubscriptionDirectory], it lets a wait
 *   chain name a subscription of another context, and [over] puts it behind the subscription runtime of the node's
 *   own context, which knows its subscriptions' progress.
 *
 * **Startup checks**: building it fails with a [ContractException] listing every problem:
 *
 * - the peers conflict ([PeerManifests.conflicts]): a context belongs to one service;
 * - a subscription of [local] reads another context (`@OnEvent(aggregates = ["ctx.agg"])`, decision D138) that no
 *   Manifest declares, an aggregate the context does not have, or one it does not publish;
 * - such a subscription reads a stream published without the state (`includeState = false`) but is not among
 *   [nullableStates], the subscriptions whose handler declares a nullable state, `StateEvent<S?>` (decision D140).
 *
 * A command a saga refers to ([SubscriptionManifest.derives], [SubscriptionManifest.rejected]) is resolved
 * ([commandTypeOf]) but not checked here: a lifecycle command (design 01 §6) is no context's, and whether a chain may
 * follow a saga's hop into a context is the wait's check (decision D138).
 */
class ContractDirectory(
    val local: Manifest,
    val peers: List<ManifestFragment> = emptyList(),
    /** The subscriptions (`processor.function`) whose handler takes a `StateEvent` of a nullable state (D140). */
    val nullableStates: Set<String> = emptySet(),
) : SubscriptionDirectory {
    private val manifests: List<Manifest> = listOf(local) + peers.map { it.manifest }

    /** Every context by its name; on a conflict, which fails the build, the service's own or the first peer's. */
    private val contexts: Map<String, Owned> = HashMap<String, Owned>().also { contexts ->
        manifests.forEach { manifest ->
            manifest.contexts.forEach { contexts.putIfAbsent(it.name, Owned(manifest.service?.name, it, manifest === local)) }
        }
    }

    private val commandsBySchema = HashMap<String, String>()
    private val schemasByCommand = HashMap<String, String>()

    init {
        manifests.forEach { manifest ->
            manifest.contexts.forEach { context ->
                context.aggregates.forEach { aggregate ->
                    aggregate.commands.forEach { command ->
                        val type = "${context.name}.${aggregate.name}.${command.name}"
                        commandsBySchema.putIfAbsent(command.schema, type)
                        schemasByCommand[type] = command.schema
                    }
                }
            }
        }
        val problems = PeerManifests.conflicts(local.service?.name, local.contexts.map { it.name }, peers) +
            local.contexts.flatMap { context -> context.subscriptions.flatMap { sourceProblems(context, it) } }
        if (problems.isNotEmpty()) {
            throw ContractException(problems)
        }
    }

    private class Owned(val service: String?, val context: ContextManifest, val local: Boolean)

    /** The service [context] belongs to, this one's or a peer's; null when no Manifest declares it. */
    fun serviceOf(context: String): String? = contexts[context]?.service

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
    fun commandTypeOf(reference: String): String? =
        if (reference.startsWith(DEFS_PREFIX)) commandsBySchema[reference] else reference.takeIf { it in schemasByCommand }

    /** The schema reference of the command type [commandType] (`ctx.agg.Command`), as its context's Manifest gives it. */
    fun schemaOf(commandType: String): String? = schemasByCommand[commandType]

    /** The subscription [name] (`processor.function`) of [context]. */
    fun subscriptionOf(context: String, name: String): SubscriptionManifest? =
        contextOf(context)?.subscriptions?.firstOrNull { it.name == name }

    /**
     * The kind of [subscription] of [context], as the context's Manifest lists it, or the built-in snapshot
     * subscription ([Hop.SNAPSHOT_SUBSCRIPTION]) of a context with an aggregate that keeps snapshots. A saga's
     * `@OnRejected` handler is not a wait's hop: it handles rejections, not commits.
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

    /** A Manifest says nothing of a subscription's progress (decision D63: a hop of another context is not checked). */
    override suspend fun hasHandled(subscription: String, stream: EventStreamRecord): Boolean = false

    /**
     * This directory behind [runtime], the subscription directory of the node's [ownContext]: a hop of it is
     * answered by [runtime], which knows the subscriptions the node runs and their progress; a hop of any other
     * context, this service's or a peer's, by the Manifests.
     */
    fun over(runtime: SubscriptionDirectory, ownContext: String): SubscriptionDirectory = object : SubscriptionDirectory {
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

        override suspend fun hasHandled(subscription: String, stream: EventStreamRecord): Boolean =
            runtime.hasHandled(subscription, stream)
    }

    /** The problems of a subscription of [context] that reads another context's published streams. */
    private fun sourceProblems(context: ContextManifest, subscription: SubscriptionManifest): List<String> {
        val source = subscription.source.context
        if (source == null || source == context.name || subscription.rejected != null) {
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
        }
    }

    companion object {
        private const val DEFS_PREFIX = "#/\$defs/"

        /**
         * The directory of [local] and the peer Manifests on [classLoader] ([PeerManifestReader]); fails with a
         * [ContractException] when a peer cannot be read, as when the directory cannot be built.
         */
        fun load(
            local: Manifest,
            classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
            mapper: JsonMapper = JsonMapper.builder().build(),
            nullableStates: Set<String> = emptySet(),
        ): ContractDirectory = ContractDirectory(local, PeerManifestReader(classLoader, mapper).read(), nullableStates)
    }
}

/** The contracts of a service and its peers do not hold together; [problems] lists each problem. */
class ContractException(val problems: List<String>) :
    IllegalStateException("The service's contracts do not hold together:\n" + problems.joinToString("\n") { "- $it" })
