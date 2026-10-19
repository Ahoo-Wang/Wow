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

package me.ahoo.wow.runtime.binding

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.subscribe.CallRejectedException
import me.ahoo.wow.api.subscribe.CommandCommitted
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.runtime.admission.CreationIds
import me.ahoo.wow.runtime.cluster.ReplyTo
import me.ahoo.wow.runtime.command.CommandSender
import me.ahoo.wow.runtime.subscription.HandlingRejectedException
import me.ahoo.wow.runtime.wait.Settlement
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.busyOf
import me.ahoo.wow.runtime.wait.toError
import me.ahoo.wow.spi.wait.WaitContext

/**
 * Executes the commands a saga derived from an entry (design 01 §1; design 04 §6.1, decision D62) on the aggregates of
 * its own bounded context, the [models], through that context's [coordinator], which routes each to the owner of its
 * partition as it routes an admitted command (design 02 §2; decisions D6, D71):
 *
 * - **In order**: each is executed in turn, and the saga's handling is done once every one is committed (or changed
 *   nothing, or was already committed by an earlier execution of the same commit). The commands of a context are
 *   committed in its own log, so they need not wait beyond COMMITTED (decision D33).
 * - **Wait chains**: when the entry's wait context waits for this saga next ([waitOf]), the commands carry the rest of
 *   the chain on ([WaitContext.derived]): [WaitCoordinator.forward] signals their own hops to the waiting node, and the
 *   saga's hop, signalled by the subscription with their ids, is complete once each of them reached the next hop; with
 *   none derived, it is complete at once.
 * - **Rejections** are business outcomes (decision D153): the first command that is rejected stops the execution, the
 *   commands after it are not executed, and the result says which one it was ([Executed.rejected]), for the saga's
 *   `@OnRejected` handler ([SagaReceipts]); nothing is retried for it.
 * - **Commits** of its own context: the result lists each command committed (or already committed, or unchanged)
 *   with its outcome ([Executed.committed]), for the saga's `@OnCommitted` handler (decision D192).
 * - **Creations without an id** (decision D191): a command of its own context that names no aggregate
 *   ([me.ahoo.wow.api.aggregate.AggregateRef.isAssigned]) takes its id from [creations] before it runs: the id its
 *   request already created, else a new one from its aggregate type's generator. One to another context takes it
 *   there, at the target's intake.
 * - **Other contexts** (decisions D33, D137): a command to an aggregate of a context outside the saga's own is sent
 *   to that context's command topic through the sender ([CommandSender]), and is done once the broker has acknowledged it, a durable
 *   ACCEPTED; its outcome is the target's, and a rejection comes back as a receipt to the [ReplyTo] the caller gives
 *   it (decision D136). It carries [wait] on, so the target signals the waiting node as this context would.
 * - **Scope** (decision D199): a derived command is not checked against the caller's scope on an existing target; the
 *   root command was authorized at its admission. Each carries a causation, so the kernel tells it from a caller's
 *   ([me.ahoo.wow.kernel.isDerived]): one a handler built without it is given itself as its root.
 * - **Backpressure**: a command the node refused as busy (its lane full, or the node at its most commands in flight,
 *   decision D228 ②) fails the handling with a [me.ahoo.wow.runtime.CommandBusyException], which the subscription
 *   retries after a backoff without spending its retry budget (review R2.1-1).
 * - **Technical failures** fail the handling: a command whose outcome is contended or unknown, or that the executor
 *   did not take (the node closing), a command the broker
 *   did not acknowledge, one to another context with no sender (a node without a Kafka transport), and one to an
 *   aggregate type its own context does not have. The subscription retries the handling, which derives the same
 *   requests again, then parks the aggregate.
 */
internal class DerivedCommands(
    models: Collection<AggregateModel<*>>,
    private val coordinator: WaitCoordinator,
    private val sender: CommandSender? = null,
    private val creations: CreationIds = CreationIds(),
) {
    private val models: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }
    private val contexts: Set<String> = models.mapTo(HashSet()) { it.type.context }

    /**
     * Executes [commands], with [wait] when the saga's hop is waited for, until the first one that is rejected; returns
     * the ids of those it executed (D62) and that rejection. A command to another context is sent with the [replyTo]
     * its receipt goes to, if any.
     */
    suspend fun execute(
        commands: List<Command<*>>,
        wait: WaitContext?,
        replyTo: (Command<*>) -> ReplyTo? = { null },
    ): Executed {
        val executed = ArrayList<String>(commands.size)
        val committed = ArrayList<Committed>()
        for (returned in commands) {
            // A derived command belongs to a chain; one a handler built without a causation is its own root.
            val derived = if (returned.causation == null) {
                returned.copyWith(causation = Causation(returned.id, returned.id))
            } else {
                returned
            }
            val model = models[derived.ref.type]
            if (model == null) {
                send(derived, wait, replyTo(derived))
                executed += derived.id
                continue
            }
            // A creation that names no aggregate takes the id its request created, or a new one (decision D191).
            val command = creations.assign(model, derived)
            val settlement = coordinator.forward(model, command, wait)
            failureOf(command, settlement)?.let { throw it }
            executed += command.id
            val commandType = model.commandType(command.body.javaClass).name
            settlement.rejection?.let { rejection ->
                return Executed(executed, Rejected(command, rejection, commandType), committed)
            }
            committed += Committed(command, commandType, settlement.committed)
        }
        return Executed(executed, committed = committed)
    }

    /** Sends [command], to an aggregate of another context, to its command topic and waits for the broker. */
    private suspend fun send(command: Command<*>, wait: WaitContext?, replyTo: ReplyTo?) {
        val context = command.ref.type.context
        check(context !in contexts) {
            "A saga derived ${command.id} for ${command.ref}, an aggregate type its context does not have."
        }
        val sender = checkNotNull(sender) {
            "A saga derived ${command.id} for ${command.ref}, of the context $context: a command across contexts " +
                "goes through the command topic (decision D33), and this node has no Kafka transport (with Spring " +
                "Boot, set wow.kafka.bootstrap-servers)."
        }
        sender.send(command, wait, replyTo)
    }

    /**
     * Why [settlement] fails the saga, or null when [command] is settled: committed, unchanged, already committed or
     * rejected, here or on its partition's owner.
     */
    private fun failureOf(command: Command<*>, settlement: Settlement): Exception? {
        if (!settlement.failed) {
            return null
        }
        // A busy node is backpressure: the subscription retries without spending its budget (review R2.1-1).
        settlement.busyOf(command)?.let { return it }
        val error = settlement.committed.error
        val failure = settlement.failure
        return IllegalStateException(
            "The derived command ${command.id} on ${command.ref} failed: ${error?.code} ${error?.message}" +
                settlement.cause?.let { " ($it)" }.orEmpty(),
            failure,
        )
    }

    /**
     * What [execute] did: the ids of the commands it [executed], in order, the rejected one last when one was
     * [rejected]; and those of its own context it [committed], in order.
     */
    data class Executed(
        val executed: List<String>,
        val rejected: Rejected? = null,
        val committed: List<Committed> = emptyList(),
    )

    /**
     * The derived [command] of its own context, of the full type name [commandType], was committed (or had been, or
     * changed nothing) with [result] at its `committed` hop.
     */
    data class Committed(val command: Command<*>, val commandType: String, val result: CommandResult) {
        /**
         * The commit as the saga's `@OnCommitted` handler takes it (decision D192), from the saga's [execution] that
         * derived it: on the aggregate the result names, the one a creation without an id got (decision D191).
         */
        fun toCommandCommitted(execution: ExecutionId): CommandCommitted<Any> = CommandCommitted(
            commandId = command.id,
            commandType = commandType,
            ref = result.ref,
            version = result.version ?: 0,
            result = result,
            causation = command.causation ?: Causation(command.id, command.id),
            executionId = execution,
            operator = command.operator,
            scope = command.scope,
        )
    }

    /** The derived [command], of the full type name [commandType], was refused with [rejection]. */
    data class Rejected(val command: Command<*>, val rejection: Rejection, val commandType: String) {
        /** The error a wait at the saga's hop ends with. */
        val error: ErrorInfo get() = rejection.toError()

        /** The rejection as the saga's `@OnRejected` handler takes it, from the saga's [execution] that derived it. */
        fun toCommandRejected(execution: ExecutionId): CommandRejected<Any> = CommandRejected(
            commandId = command.id,
            commandType = commandType,
            ref = command.ref,
            error = error,
            causation = command.causation ?: Causation(command.id, command.id),
            executionId = execution,
            operator = command.operator,
            scope = command.scope,
        )
    }

    companion object {
        /**
         * The wait context the commands a saga named [subscription] of [context] derives from an entry carry on: the
         * rest of the entry's chain ([WaitContext.derived]) when its next hop is this saga; otherwise null.
         */
        @JvmStatic
        fun waitOf(entryWait: WaitContext?, context: String, subscription: String): WaitContext? {
            val next = entryWait?.nextHandled ?: return null
            val ours = next.subscription == subscription && (next.context == null || next.context == context)
            return if (ours) entryWait.derived() else null
        }
    }
}

/**
 * A command a saga derived was rejected, and the saga has no `@OnRejected` handler for it (decision D153), or a
 * command its handler derived to compensate was rejected in turn: the aggregate parks at once, without a retry, and a
 * wait at the saga's hop ends REJECTED with the rejection.
 */
internal class DerivedCommandRejectedException(val command: Command<*>, val rejection: Rejection) :
    HandlingRejectedException(
        rejection.toError(),
        "The derived command ${command.id} on ${command.ref} was rejected: ${rejection.code} ${rejection.message}",
    )

/**
 * A saga's handler let through the rejection of a synchronous call it made (decision D193): a business outcome, so the
 * aggregate parks at once, without a retry, as for a derived command's rejection no `@OnRejected` handler takes
 * (decision D153), and a wait at the saga's hop ends REJECTED with the target's error.
 */
internal class CallRejectedHandlingException(val rejected: CallRejectedException) :
    HandlingRejectedException(rejected.result.error!!, rejected.message!!)
