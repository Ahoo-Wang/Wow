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

package me.ahoo.wow.runtime.node

import kotlinx.coroutines.Dispatchers
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.cluster.ServiceMembership
import me.ahoo.wow.runtime.command.CommandIntake
import me.ahoo.wow.runtime.command.MessageEntries
import me.ahoo.wow.runtime.command.Receipts
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.ledger.LedgerCommands
import me.ahoo.wow.runtime.ledger.LedgerPublisher
import me.ahoo.wow.runtime.source.TopicSubscriptions
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.subscription.LeaseStore
import java.time.Clock

/**
 * What a [ServiceNode] reads and owns on topics.
 *
 * **Per topic** read for the subscriptions of other contexts' published streams ([SubscriptionFactory.sourced]):
 * one [TopicSubscriptions] of every subscription reading it, in the lease group `(service, "topic:<topic>")`, keeping
 * its checkpoints and parked aggregates in the stores of the first context (in the order of the models) that reads it.
 * The topic is the publishing context's ([ContractDirectory.publishedOf]) in the node's [SourceTopics]; a node with
 * such subscriptions and no topics is not built.
 *
 * **Receipts**: the receipts topic of each context whose sagas have `@OnRejected` or `@OnCommitted` handlers
 * ([SubscriptionFactory.receipts]), read in the lease group of that topic. **Intakes**: the [CommandIntake] of each
 * context that takes commands from its topic. **Requirements**: the topics the contexts own and use, which the node's
 * start validates or creates.
 */
internal class TopicAssembly(private val wiring: NodeWiring, private val manifest: Manifest) {
    private val service = wiring.service
    private val node = wiring.node
    private val address: String get() = wiring.address()
    private val options = wiring.options
    private val observer = wiring.observer
    private val contracts = wiring.contracts
    private val topics = wiring.topics
    private val signals = wiring.signals
    private val notifiers = wiring.notifiers
    private val classification = wiring.classification
    private val inFlight = wiring.inFlight

    private fun membershipOf(leases: LeaseStore): ServiceMembership = wiring.membershipOf(leases)

    private fun takesCommands(context: String): Boolean = wiring.takesCommands(context)

    /**
     * The topic subscriptions of [contexts], by the topic they read: the [application]'s sourced subscriptions, then
     * the receipts.
     */
    fun subscriptions(
        contexts: Collection<ContextNode>,
        application: SubscriptionFactory,
    ): Map<String, TopicSubscriptions> = sourced(contexts, application) + receipts(contexts, application)

    /**
     * The runtimes of the topics the [application]'s sourced subscriptions read: each type's published stream, by its
     * topic in [topics].
     */
    private fun sourced(
        contexts: Collection<ContextNode>,
        application: SubscriptionFactory,
    ): Map<String, TopicSubscriptions> {
        val readers = LinkedHashMap<String, MutableList<Pair<ContextNode, Subscription>>>()
        contexts.forEach { context ->
            application.sourced(context).forEach { sourced ->
                sourced.types.forEach { type ->
                    val name = sourced.subscription.name
                    val published = requireNotNull(contracts.publishedOf(type)) {
                        "$name of ${context.context} reads $type, which no Manifest of the service or its peers publishes."
                    }
                    require(sourced.subscription.context == context.context) {
                        "$name reads $type for ${context.context}, but belongs to ${sourced.subscription.context}: " +
                            "a sourced subscription belongs to the context that reads (Subscription.context)."
                    }
                    val sources = requireNotNull(topics) {
                        "$name of ${context.context} reads the topic ${published.topic} of $type, but node $node reads " +
                            "no topics: give it their source (with Spring Boot, set wow.kafka.bootstrap-servers)."
                    }
                    val readersOfTopic = readers.getOrPut(sources.topicOf(published.topic)) { ArrayList() }
                    if (readersOfTopic.none { it.second === sourced.subscription }) {
                        readersOfTopic += context to sourced.subscription
                    }
                }
            }
        }
        return readers.mapValues { (topic, read) ->
            val backends = read.first().first.backends
            TopicSubscriptions(
                service = service,
                topic = topic,
                node = node,
                addressOf = { address },
                source = topics!!.source,
                checkpoints = backends.checkpoints,
                leases = backends.leases,
                membership = membershipOf(backends.leases),
                ledger = backends.ledger,
                subscriptions = read.map { it.second },
                options = options.subscription,
                cluster = options.cluster,
                signals = signals,
                notifier = notifiers,
                partitionRefresh = topics!!.partitionRefresh,
                observer = observer,
                classification = classification,
            )
        }
    }

    /**
     * The runtimes of the receipts topics of the contexts whose sagas the [application] gives `@OnRejected` or
     * `@OnCommitted` handlers ([SubscriptionFactory.receipts]), each in the lease group of its topic and the stores of its
     * context. None without [topics]: no command left for another context then.
     */
    private fun receipts(
        contexts: Collection<ContextNode>,
        application: SubscriptionFactory,
    ): Map<String, TopicSubscriptions> {
        val sources = topics ?: return emptyMap()
        return contexts.mapNotNull { context ->
            val handler = application.receipts(context) ?: return@mapNotNull null
            val topic = sources.receiptsOf(context.context)
            val receipts = Receipts(context.context, contracts, handler)
            topic to TopicSubscriptions(
                service = service,
                topic = topic,
                node = node,
                addressOf = { address },
                source = sources.source,
                checkpoints = context.backends.checkpoints,
                leases = context.backends.leases,
                membership = membershipOf(context.backends.leases),
                ledger = context.backends.ledger,
                subscriptions = listOf(receipts.subscription),
                options = options.subscription,
                cluster = options.cluster,
                clock = Clock.systemUTC(),
                context = Dispatchers.Default,
                signals = signals,
                notifier = notifiers,
                entries = { partitions -> MessageEntries.receipts(topic, sources.source, partitions, observer) },
                partitionRefresh = sources.partitionRefresh,
                observer = observer,
                classification = classification,
            )
        }.toMap()
    }

    /**
     * The command intake of each of [contexts] that takes commands from its topic, running the ledger's system
     * commands on the node's [parked] entries, which the [ledgerPublisher] publishes.
     */
    fun intakes(
        contexts: Collection<ContextNode>,
        parked: ParkedOps,
        ledgerPublisher: () -> LedgerPublisher?,
    ): Map<ContextNode, CommandIntake> = contexts.filter { it.intake != null }.associateWith {
        val ledger = LedgerCommands(parked, options.ledger?.context ?: Ledger.OPS_CONTEXT) { ledgerPublisher() }
        val ledgerOnly = !takesCommands(it.context)
        CommandIntake(
            it.context,
            it.models,
            it.coordinator,
            topics!!,
            it.creations,
            ledger,
            inFlight,
            ledgerOnly = ledgerOnly
        )
    }

    /**
     * The topics [contexts] own and use ([ServiceNode.topicRequirements]), given the node's [topicSubscriptions].
     */
    fun requirements(
        contexts: Collection<ContextNode>,
        topicSubscriptions: Map<String, TopicSubscriptions>,
    ): List<TopicRequirement> {
        val sources = topics ?: return emptyList()
        val requirements = ArrayList<TopicRequirement>()
        contexts.forEach { context ->
            val partitions = context.backends.log.partitions
            if (context.intake != null) {
                // A node that takes the ledger's commands alone creates the topic, and runs without them on another
                // count: it did not need the topic before it published its ledger.
                requirements += TopicRequirement(
                    sources.commandsOf(context.context),
                    partitions,
                    exact = takesCommands(context.context),
                    "the command topic of context ${context.context}, with as many partitions as its log",
                )
            }
            val receipts = sources.receiptsOf(context.context)
            if (receipts in topicSubscriptions) {
                requirements += TopicRequirement(
                    receipts,
                    partitions,
                    exact = false,
                    "the receipts topic of context ${context.context}",
                )
            }
            if (sources.producer != null) {
                manifest.contexts.firstOrNull { it.name == context.context }?.published.orEmpty().forEach {
                    requirements += TopicRequirement(
                        sources.topicOf(it.topic),
                        partitions,
                        exact = false,
                        "the published stream of ${context.context}.${it.aggregate}",
                    )
                }
            }
        }
        if (sources.producer != null && sources.ddl == Ddl.VALIDATE) {
            contracts.peers.flatMap { it.manifest.contexts }
                .filter { context -> context.aggregates.any { aggregate -> aggregate.domainCommands.any { !it.mounted } } }
                .forEach { peer ->
                    requirements += TopicRequirement(
                        sources.commandsOf(peer.name),
                        1,
                        exact = false,
                        "the command topic of peer context ${peer.name}, where this node sends its commands",
                    )
                }
        }
        return requirements.distinctBy { it.topic }
    }
}
