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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.runtime.admission.ModelRegistry
import me.ahoo.wow.runtime.command.CommandSender
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.ledger.LedgerPublishing
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.spi.call.CommandCaller
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.config.configCheck

/**
 * The refusals of a [ServiceNode] that is misconfigured, checked while it is built, each before the parts it would
 * break: a node that fails one is not built. Each names the node and what to set.
 */
internal object NodeChecks {
    /** A node that publishes its failure [ledger] does so through that context's command topic: [sender] sends there. */
    fun ledgerTransport(node: String, ledger: LedgerPublishing?, sender: CommandSender?) {
        configCheck(ledger == null || sender != null, TRANSPORT, null, TRANSPORT_FIX) {
            "Node $node publishes its failure ledger to ${ledger?.context}, through that context's command " +
                "topic, but has no command transport."
        }
    }

    /**
     * A saga that sends to a context outside [served] needs a producer ([sender]): without one, each of its executions
     * would retry and park at run time while the node looked healthy.
     */
    fun commandsAcross(node: String, contracts: ContractDirectory, served: Set<String>, sender: CommandSender?) {
        if (sender != null) {
            return
        }
        val outside = contracts.commandsOutside(served)
        configCheck(outside.isEmpty(), TRANSPORT, null, TRANSPORT_FIX) {
            "Node $node has no command transport, but " +
                outside.entries.joinToString("; ") { (subscription, contexts) ->
                    "$subscription sends commands to ${contexts.joinToString()}"
                } + ": a command across contexts goes through its context's command topic."
        }
    }

    /** A saga's synchronous calls: each to a context outside [served], which the node's [caller] reaches. */
    fun calls(node: String, contracts: ContractDirectory, served: Set<String>, caller: CommandCaller?) {
        val called = contracts.contextsCalled()
        val problems = called.flatMap { (subscription, contexts) ->
            contexts.mapNotNull { context ->
                when (context) {
                    ContractDirectory.UNKNOWN_CONTEXT ->
                        "$subscription calls a command no Manifest of the service or its peers decides: depend on " +
                            "the api module of its context, whose jar carries its contract"

                    in served -> "$subscription calls $context, a context of this node: derive the command"

                    else -> null
                }
            }
        }
        require(problems.isEmpty()) {
            "Node $node cannot make its sagas' calls: ${problems.joinToString("; ")}."
        }
        val unreached = called.flatMap { (subscription, contexts) ->
            contexts.filter { caller == null || !caller.reaches(it) }.map { subscription to it }
        }
        unreached.firstOrNull()?.let { (_, context) ->
            throw WowConfigurationException(
                "wow.peers.$context.url",
                null,
                "Node $node cannot make its sagas' calls: " +
                    unreached.joinToString("; ") { (subscription, it) -> "$subscription calls $it" } +
                    ", which the node's caller does not reach.",
                "Give the caller the internal address of each context the sagas call.",
            )
        }
    }

    /** The types the topic router takes ([topicRouted]) are served by the node, which has a producer. */
    fun topicRouted(node: String, topicRouted: Set<AggregateType>, registry: ModelRegistry, hasProducer: Boolean) {
        val unknown = topicRouted.filter { registry.find(it) == null }
        configCheck(unknown.isEmpty(), "wow.aggregates.[${unknown.firstOrNull()}].routing", "topic", ROUTED_FIX) {
            "The topic router is configured for $unknown, which node $node does not serve; it serves " +
                "${registry.models.map { it.type }}."
        }
        configCheck(topicRouted.isEmpty() || hasProducer, TRANSPORT, null, TRANSPORT_FIX) {
            "The topic router of $topicRouted writes their commands to their command topics, but node $node " +
                "has no command transport."
        }
    }

    /**
     * A start, a parked retry spec or a retry budget configured in [options] for a subscription the node does not have
     * ([names]) is a typo, not a no-op.
     */
    fun subscriptionsNamed(node: String, names: Set<String>, options: SubscriptionOptions) {
        configured(node, names, "start", options.starts.keys)
        configured(node, names, "parked retry spec", options.parked.specs.keys)
        configured(node, names, "retry budget", options.retries.keys)
    }

    private fun configured(node: String, names: Set<String>, what: String, keys: Set<String>) {
        keys.firstOrNull { it !in names }?.let {
            throw IllegalArgumentException(
                "A $what is configured for the subscription $it, which node $node does not have: ${names.sorted()}.",
            )
        }
    }

    /** The key of the node's topic transport, which carries its command topics. */
    const val TRANSPORT: String = "wow.transport"

    /** What a node without the command transport it needs changes. */
    const val TRANSPORT_FIX: String = "Give the node a command transport: its topic transport, with its source."

    private const val ROUTED_FIX: String = "Route through the command topic only the aggregate types the node serves."
}
