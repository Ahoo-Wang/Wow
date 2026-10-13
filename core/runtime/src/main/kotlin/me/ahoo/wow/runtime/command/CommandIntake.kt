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

package me.ahoo.wow.runtime.command

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.CommandWire
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.TopicProducer
import me.ahoo.wow.spi.wait.WaitContext

/**
 * The command intake of one bounded [context] (design 02 §2; decisions D135, D137): the subscription [NAME] of its
 * command topic, run by the owners of the context's log partitions, each over the command partition of the same
 * number, so a command is executed where its aggregate's log partition is owned, with no forwarding.
 *
 * For each command, in its aggregate's lane (one aggregate's commands in order, different aggregates in parallel):
 *
 * 1. **Execute** it through the context's [coordinator] ([WaitCoordinator.forward]) with the wait context its
 *    envelope carries: the kernel deduplicates by request id (per aggregate type), so a command delivered twice, or
 *    sent twice by a saga that retried, is not decided again; the waiting node is signalled as for a saga's command.
 * 2. **Settle** the outcome: committed, a duplicate or no change is done. A rejection is a business outcome (decision
 *    D47): it is not retried nor parked, and nothing is written to the log; when the command says where to reply
 *    ([CommandWire.replyTo]), the receipt, a [CommandRejected], is sent to `wow.<replyTo.context>.receipts` and
 *    acknowledged by the broker **before** the entry completes, so the checkpoint never passes a rejection whose
 *    receipt is not durable. A crash in between handles the command again from the checkpoint: it is rejected again
 *    and its receipt sent again, which the sender drops by the command id (decision D136).
 * 3. **Technical failures** throw: an aggregate type or command type the context does not know, a contended or unknown
 *    outcome, an executor that is busy or closing, a receipt the broker does not acknowledge. The subscription retries
 *    them, then parks the aggregate in the context's failure ledger under [NAME], at the command's offset
 *    ([me.ahoo.wow.spi.ledger.ParkedAggregate.fromPosition]); its later commands wait until it is resumed or skipped.
 *
 * The checkpoint is the offset after the last command whose outcome is settled, every command before it settled too
 * (the subscription's contiguous low watermark), so a handover or a restart executes again at most what was in
 * flight, which the kernel deduplicates.
 */
class CommandIntake internal constructor(
    private val context: String,
    models: Collection<AggregateModel<*>>,
    private val coordinator: WaitCoordinator,
    private val topics: SourceTopics,
) {
    private val models: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }
    private val producer: TopicProducer = requireNotNull(topics.producer) { "An intake sends receipts." }

    /** The intake's subscription, of every message of the command topic. */
    val subscription: Subscription = Subscription.messages(NAME, context) { entry ->
        execute(entry.message as CommandWire, entry.transport?.wait)
    }

    /** Executes [wire] with [wait] and settles its outcome, or throws a technical failure. */
    internal suspend fun execute(wire: CommandWire, wait: WaitContext?) {
        val model = checkNotNull(models[wire.ref.type]) {
            "Context $context has no aggregate ${wire.ref.type}, which the command ${wire.id} is sent to."
        }
        val command = checkNotNull(ClusterCodec.command(model, wire)) {
            "${wire.ref.type} has no command type ${wire.type}, which the command ${wire.id} is."
        }
        when (val outcome = coordinator.forward(model, command, wait)) {
            is CommandOutcome.Committed, is CommandOutcome.AlreadyCommitted, is CommandOutcome.Unchanged -> Unit
            is CommandOutcome.Rejected -> reply(wire, ErrorInfo(outcome.rejection.code, outcome.rejection.message))
            is CommandOutcome.Contended -> throw IllegalStateException(
                "The command ${wire.id} on ${wire.ref} was contended ${outcome.attempts} times.",
            )

            is CommandOutcome.Unknown -> throw IllegalStateException(
                "The outcome of the command ${wire.id} on ${wire.ref} is unknown.",
                outcome.cause,
            )
        }
    }

    /** Sends the receipt of [wire]'s rejection with [error] to where it says, and waits for the broker. */
    private suspend fun reply(wire: CommandWire, error: ErrorInfo) {
        val replyTo = wire.replyTo
        if (replyTo == null) {
            logger.info { "Command ${wire.id} on ${wire.ref} was rejected: ${error.code} ${error.message}" }
            return
        }
        val rejected = CommandRejected<Any>(
            commandId = wire.id,
            commandType = wire.type,
            ref = wire.ref,
            error = error,
            causation = wire.causation ?: Causation(wire.id, wire.id),
            executionId = replyTo.executionId(),
        )
        producer.send(topics.receiptsOf(replyTo.context), CommandMessages.receipt(rejected))
    }

    companion object {
        /** The intake's subscription in each context: its checkpoints and its parked aggregates are under it. */
        const val NAME: String = "wow.commands"

        private val logger = KotlinLogging.logger {}

        /**
         * Fails unless [context]'s command topic in [source] has [partitions] partitions, its log's (decision D135):
         * the producers pick a command's partition by the log's partitioner over the topic's partitions, so any other
         * count sends commands to partitions whose owner does not own their aggregates.
         */
        internal suspend fun checkPartitions(topic: String, source: PartitionedSource, context: String, partitions: Int) {
            val count = source.partitions(topic).size
            check(count == partitions) {
                val found = if (count == 0) "does not exist" else "has $count partitions"
                "The command topic $topic of context $context $found: it must have $partitions partitions, as many " +
                    "as the context's log (decision D135). Create it so before the service starts."
            }
        }
    }
}
