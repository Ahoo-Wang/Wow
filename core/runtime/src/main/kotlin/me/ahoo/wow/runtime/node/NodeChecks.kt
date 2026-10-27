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

/**
 * The refusals of a [ServiceNode] that is misconfigured, checked while it is built, each before the parts it would
 * break: a node that fails one is not built. Each names the node and what to set.
 */
internal object NodeChecks {
    /** A node that publishes its failure [ledger] does so through that context's command topic: [sender] sends there. */
    fun ledgerTransport(node: String, ledger: LedgerPublishing?, sender: CommandSender?) {
        require(ledger == null || sender != null) {
            "Node $node publishes its failure ledger to ${ledger?.context}, through that context's command " +
                "topic, but has no command transport (with Spring Boot, set wow.kafka.bootstrap-servers)."
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
        require(outside.isEmpty()) {
            "Node $node has no command transport (with Spring Boot, set wow.kafka.bootstrap-servers), but " +
                outside.entries.joinToString("; ") { (subscription, contexts) ->
                    "$subscription sends commands to ${contexts.joinToString()}"
                } + ": a command across contexts goes through its context's command topic."
        }
    }

    /** A saga's synchronous calls: each to a context outside [served], which the node's [caller] reaches. */
    fun calls(node: String, contracts: ContractDirectory, served: Set<String>, caller: CommandCaller?) {
        val problems = contracts.contextsCalled().flatMap { (subscription, contexts) ->
            contexts.mapNotNull { context ->
                when {
                    context == ContractDirectory.UNKNOWN_CONTEXT ->
                        "$subscription calls a command no Manifest of the service or its peers decides: depend on " +
                            "the api module of its context, whose jar carries its contract"

                    context in served -> "$subscription calls $context, a context of this node: derive the command"

                    caller == null || !caller.reaches(context) ->
                        "$subscription calls $context, which the node's caller does not reach (with Spring Boot, " +
                            "set wow.peers.$context.url)"

                    else -> null
                }
            }
        }
        require(problems.isEmpty()) {
            "Node $node cannot make its sagas' calls: ${problems.joinToString("; ")}."
        }
    }

    /** The types the `Kafka` router takes ([kafkaRouted]) are served by the node, which has a producer. */
    fun kafkaRouted(node: String, kafkaRouted: Set<AggregateType>, registry: ModelRegistry, hasProducer: Boolean) {
        val unknown = kafkaRouted.filter { registry.find(it) == null }
        require(unknown.isEmpty()) {
            "The Kafka router is configured for $unknown, which node $node does not serve; it serves " +
                "${registry.models.map { it.type }}."
        }
        require(kafkaRouted.isEmpty() || hasProducer) {
            "The Kafka router of $kafkaRouted writes their commands to their command topics, but node $node " +
                "has no command transport (with Spring Boot, set wow.kafka.bootstrap-servers)."
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
}
