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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withPermit
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.client.WowProblemException
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.subscribe.CommandCommitted
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.StartFrom
import me.ahoo.wow.runtime.InFlightLimit
import me.ahoo.wow.runtime.admission.CreationIds
import me.ahoo.wow.runtime.client.InProcessCommandClient
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.CommandWire
import me.ahoo.wow.runtime.ledger.LedgerCommands
import me.ahoo.wow.runtime.node.ParkedOps
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.wait.Settlement
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.busyOf
import me.ahoo.wow.runtime.wait.toError
import me.ahoo.wow.spi.observation.trace
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.TopicProducer
import me.ahoo.wow.spi.wait.WaitContext
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The command intake of one bounded [context]: the subscription [NAME] of its
 * command topic, run by the owners of the context's log partitions, each over the command partition of the same
 * number, so a command is executed where its aggregate's log partition is owned, with no forwarding.
 *
 * For each command, in its aggregate's lane (one aggregate's commands in order, different aggregates in parallel):
 *
 * 1. **Execute** it through the context's [coordinator] ([WaitCoordinator.forward]) with the wait context its
 *    envelope carries, a creation that names no aggregate given its id first by [creations]: the kernel deduplicates by request id (per aggregate type), so a command delivered twice, or
 *    sent twice by a saga that retried, is not decided again; the waiting node is signalled as for a saga's command.
 * 2. **Settle** the outcome: committed, a duplicate or no change is done; when the command asks for the receipt of its
 *    commit ([me.ahoo.wow.runtime.cluster.ReplyTo.committed]), the receipt, a [CommandCommitted] with
 *    the aggregate, its version and the command's result, no state, is sent to `wow.<replyTo.context>.receipts` and
 *    acknowledged by the broker **before** the entry completes, in the same order as a rejection's below. A duplicate
 *    request answers with the original aggregate and version again, the result marked `duplicate`;
 *    the saga drops the copy by the command id. A receipt carries the command's wait context past its commit, so a
 *    chain whose next hop is the saga's `@OnCommitted` handler goes on through it. A rejection is a business outcome:
 * it is not retried nor parked, and nothing is written to the log; when the command says where to reply
 *    ([CommandWire.replyTo]), the receipt, a [CommandRejected], is sent to `wow.<replyTo.context>.receipts` and
 *    acknowledged by the broker **before** the entry completes, so the checkpoint never passes a rejection whose
 *    receipt is not durable. A crash in between handles the command again from the checkpoint: it is rejected again
 *    and its receipt sent again, which the sender drops by the command id.
 * 3. **Technical failures** throw: a contended or unknown outcome, an executor that is closing, a store that is down,
 *    a receipt the broker does not acknowledge. The subscription retries them, then parks the aggregate in the
 *    context's failure ledger under [NAME], at the command's offset
 *    ([me.ahoo.wow.spi.ledger.ParkedAggregate.fromPosition]); its later commands wait until it is resumed or skipped.
 *
 * An aggregate type or command type the context does not know is the sender's mistake, or a contract the sender has
 * and this service does not yet: it is not this aggregate's failure, and parking it would hold its
 * later commands for an operator. Such a command is rejected as [FrameworkCodes.COMMAND_UNHANDLED], with its receipt as in 2
 * ([unhandled]).
 *
 * A command the node refuses as busy is backpressure, not a failure: the intake
 * throws a [me.ahoo.wow.runtime.CommandBusyException], which its subscription retries after a backoff without
 * spending its retry budget. The intakes of a node execute at most [InFlightLimit.intakes] commands at once, half its
 * in-flight bound, so a node that owns many partitions of a backlog leaves room for its callers, sagas and timers. A
 * command a schedule sent waits in its lane for its due time first ([awaitDue]).
 *
 * The checkpoint is the offset after the last command whose outcome is settled, every command before it settled too
 * (the subscription's contiguous low watermark), so a handover or a restart executes again at most what was in
 * flight, which the kernel deduplicates.
 *
 * The ledger's system commands (`wow.ledger.*`), which the ops context sends for an entry of this
 * service, are not the kernel's: [ledger] runs them on the node's parked entries, and settles them as above.
 */
@InternalWowApi
class CommandIntake internal constructor(
    private val context: String,
    models: Collection<AggregateModel<*>>,
    private val coordinator: WaitCoordinator,
    private val topics: SourceTopics,
    private val creations: CreationIds = CreationIds(),
    private val ledger: LedgerCommands = LedgerCommands(ParkedOps { emptyList() }),
    /**
     * The node's in-flight bound, whose [InFlightLimit.intakes] bound the commands the intakes of all
     * its contexts execute at once.
     */
    private val limit: InFlightLimit = InFlightLimit(),
    /** The longest the intake waits for a scheduled command's due time before it runs it. */
    private val maxScheduleWait: Duration = DEFAULT_MAX_SCHEDULE_WAIT,
    private val clock: Clock = Clock.systemUTC(),
    /**
     * Whether it runs the ledger's system commands alone ([LEDGER_NAME]): the intake of a node that publishes its
     * failure ledger but takes no other command from its topic, so the operators' actions on its parked entries reach it.
     * It passes over every other command, which the node does not take, and starts at the end of the
     * topic: an action sent before it first ran is not run long after, on an entry parked again since.
     */
    private val ledgerOnly: Boolean = false,
) {
    private val models: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }
    private val producer: TopicProducer = requireNotNull(topics.producer) { "An intake sends receipts." }

    /** The intake's subscription, of every message of the command topic. */
    val subscription: Subscription = if (ledgerOnly) {
        Subscription.messages(LEDGER_NAME, context, StartFrom.END) { entry ->
            execute(entry.message as CommandWire, entry.transport?.wait)
        }
    } else {
        Subscription.messages(NAME, context) { entry -> execute(entry.message as CommandWire, entry.transport?.wait) }
    }

    /** Executes [wire] with [wait] and settles its outcome, or throws a technical failure. */
    internal suspend fun execute(wire: CommandWire, wait: WaitContext?) {
        if (wire.type.startsWith(LEDGER_PREFIX)) {
            ledger.execute(wire)?.let { reply(wire, it, null) }
            return
        }
        if (ledgerOnly) {
            return
        }
        val model = models[wire.ref.type]
            ?: return unhandled(wire, wait, "Context $context has no aggregate ${wire.ref.type}.")
        val received = ClusterCodec.command(model, wire)
            ?: return unhandled(wire, wait, "${wire.ref.type} has no command type ${wire.type}.")
        // A creation that names no aggregate takes the id its request created, or a new one.
        val command = creations.assign(model, received)
        awaitDue(command)
        val settlement = limit.intakes.withPermit { coordinator.forward(model, command, wait) }
        // A receipt carries the rest of the command's chain on, past its commit.
        val next = wait?.afterCommitted()?.takeIf { it.remaining.isNotEmpty() }
        settlement.rejection?.let { rejection ->
            reply(wire.copyWith(ref = command.ref), rejection.toError(), next)
            return
        }
        // A busy node is backpressure: the subscription retries without spending its budget.
        settlement.busyOf(command)?.let { throw it }
        if (settlement.failed) {
            throw failureOf(wire.id, wire.ref, settlement)
        }
        replyCommitted(wire, settlement.committed, next)
    }

    /**
     * Refuses [wire], of an aggregate type or a command type this context does not know, as
     * [FrameworkCodes.COMMAND_UNHANDLED]: a sender's mistake, or a contract the sender has and this service does
     * not yet, which no retry here mends. It is the sender's rejection, not a failure of the aggregate it names, so
     * that aggregate's later commands go on: the node waiting on [wait] is signalled the rejection at the context's
     * COMMITTED hop, and the receipt goes where [wire] says, as for a decide function's rejection; the sender's saga
     * takes it in its `@OnRejected` handler, or parks there without one.
     */
    private suspend fun unhandled(wire: CommandWire, wait: WaitContext?, message: String) {
        logger.warn { "Command ${wire.id} (${wire.type}) on ${wire.ref} is refused: $message" }
        val error = ErrorInfo(FrameworkCodes.COMMAND_UNHANDLED, message)
        wait?.takeUnless {
            it.expiredAt(
                clock.millis()
            )
        }?.let { coordinator.signal(it, unhandledResult(wire, it, error)) }
        reply(wire, error, null)
    }

    /** The REJECTED result of [wire], refused with [error] before any decide function, at [wait]'s COMMITTED hop. */
    private fun unhandledResult(wire: CommandWire, wait: WaitContext, error: ErrorInfo): CommandResult {
        val type = wire.ref.type
        val hop = wait.remaining.takeWhile { it.stage != Stage.HANDLED }.firstOrNull { it.stage == Stage.COMMITTED }
            ?: Hop.committed(type.context)
        return CommandResult(
            commandId = wire.id,
            requestId = wire.requestId,
            correlationId = wire.causation?.correlationId ?: wire.id,
            ref = wire.ref,
            hop = hop,
            function = FunctionInfo(FunctionKind.COMMAND, type.context, type.name, wire.type.substringAfterLast('.')),
            status = CommandResult.Status.REJECTED,
            error = error,
            signalTime = clock.millis(),
        )
    }

    /**
     * Waits, in the command's lane, until the slot a schedule sent [command] for is due by this node's clock, at most
     * [maxScheduleWait]: a command another context's timer sent whose firing node's
     * clock runs ahead is not refused by the kernel as not due ([me.ahoo.wow.kernel.ScheduleNotDueException]), which
     * would park the aggregate, since nothing sends it again once the broker took it. One due later than that still
     * reaches the kernel, which refuses it, and the subscription retries it, then parks.
     */
    private suspend fun awaitDue(command: Command<*>) {
        val due = command.schedule?.due ?: return
        val early = (due - clock.millis()).milliseconds
        if (early.isPositive()) {
            delay(minOf(early, maxScheduleWait))
        }
    }

    /**
     * Sends the success receipt of [wire], committed with [result], to where it says when it asks for one,
     * and waits for the broker.
     */
    private suspend fun replyCommitted(wire: CommandWire, result: CommandResult, wait: WaitContext?) {
        val replyTo = wire.replyTo?.takeIf { it.committed } ?: return
        val committed = CommandCommitted<Any>(
            commandId = wire.id,
            commandType = wire.type,
            ref = result.ref,
            version = result.version ?: 0,
            result = result,
            causation = wire.causation ?: Causation(wire.id, wire.id),
            executionId = replyTo.executionId(),
            operator = wire.operator,
            scope = wire.scope,
            headers = RequestFacts.propagatedOf(wire.headers),
        )
        // The receipt goes on in the trace the command was executed in.
        producer.send(
            topics.receiptsOf(replyTo.context),
            CommandMessages.receipt(committed, currentCoroutineContext().trace, wait),
        )
    }

    /** Sends the receipt of [wire]'s rejection with [error] to where it says, and waits for the broker. */
    private suspend fun reply(wire: CommandWire, error: ErrorInfo, wait: WaitContext?) {
        val replyTo = wire.replyTo
        if (replyTo == null) {
            // A business rejection is an outcome, not an incident: no INFO line per command.
            logger.debug { "Command ${wire.id} on ${wire.ref} was rejected: ${error.code} ${error.message}" }
            return
        }
        val rejected = CommandRejected<Any>(
            commandId = wire.id,
            commandType = wire.type,
            ref = wire.ref,
            error = error,
            causation = wire.causation ?: Causation(wire.id, wire.id),
            executionId = replyTo.executionId(),
            operator = wire.operator,
            scope = wire.scope,
            headers = RequestFacts.propagatedOf(wire.headers),
        )
        // The receipt goes on in the trace the command was executed in.
        val trace = currentCoroutineContext().trace
        producer.send(topics.receiptsOf(replyTo.context), CommandMessages.receipt(rejected, trace, wait))
    }

    @InternalWowApi
    companion object {
        /** The intake's subscription in each context: its checkpoints and its parked aggregates are under it. */
        const val NAME: String = "wow.commands"

        /** The subscription of an intake of the ledger's system commands alone ([ledgerOnly]). */
        const val LEDGER_NAME: String = "wow.ledger.commands"

        private const val LEDGER_PREFIX: String = "${Ledger.NAMESPACE}."

        private val logger = KotlinLogging.logger {}

        /** The default of [maxScheduleWait]: as long as a timer's claim holds (`wow.timers.claim-timeout`). */
        internal val DEFAULT_MAX_SCHEDULE_WAIT: Duration = 30.seconds

        /**
         * Fails unless [context]'s command topic in [source] has [partitions] partitions, its log's:
         * the producers pick a command's partition by the log's partitioner over the topic's partitions, so any other
         * count sends commands to partitions whose owner does not own their aggregates.
         */
        internal suspend fun checkPartitions(
            topic: String,
            source: PartitionedSource,
            context: String,
            partitions: Int
        ) {
            val count = source.partitions(topic).size
            check(count == partitions) {
                val found = if (count == 0) "does not exist" else "has $count partitions"
                "The command topic $topic of context $context $found: it must have $partitions partitions, as many " +
                    "as the context's log. Create it so before the service starts."
            }
        }
    }
}

/**
 * Why [settlement] of the command [id] on [ref] fails the intake's handling: the problem of its result, with its code,
 * as a saga's derived command fails, so the subscription classifies it by the code (a store that is unavailable is
 * systemic and pauses the partition; a wait that ran out is backpressure), from the cause when it is known.
 */
internal fun failureOf(id: String, ref: AggregateRef, settlement: Settlement): WowProblemException {
    val error = settlement.committed.error
    return InProcessCommandClient.problemOf(
        settlement.committed,
        "The command $id on $ref failed: ${error?.code} ${error?.message}" +
            settlement.cause?.let { " ($it)" }.orEmpty(),
        settlement.failure,
    )
}
