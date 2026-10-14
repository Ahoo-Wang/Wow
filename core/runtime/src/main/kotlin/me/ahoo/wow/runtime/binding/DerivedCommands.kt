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
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.runtime.cluster.ReplyTo
import me.ahoo.wow.runtime.command.CommandSender
import me.ahoo.wow.runtime.subscription.HandlingRejectedException
import me.ahoo.wow.runtime.wait.Settlement
import me.ahoo.wow.runtime.wait.WaitCoordinator
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
 *   `@OnRejected` handler ([SagaRejections]); nothing is retried for it.
 * - **Other contexts** (decisions D33, D137): a command to an aggregate of a context outside the saga's own is sent
 *   to that context's command topic through the sender ([CommandSender]), and is done once the broker has acknowledged it, a durable
 *   ACCEPTED; its outcome is the target's, and a rejection comes back as a receipt to the [ReplyTo] the caller gives
 *   it (decision D136). It carries [wait] on, so the target signals the waiting node as this context would.
 * - **Technical failures** fail the handling: a command whose outcome is contended or unknown, or that the executor
 *   did not take (its lane full, the node closing), a command the broker
 *   did not acknowledge, one to another context with no sender (a node without a Kafka transport), and one to an
 *   aggregate type its own context does not have. The subscription retries the handling, which derives the same
 *   requests again, then parks the aggregate.
 */
class DerivedCommands(
    models: Collection<AggregateModel<*>>,
    private val coordinator: WaitCoordinator,
    private val sender: CommandSender? = null,
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
        for (command in commands) {
            val model = models[command.ref.type]
            if (model == null) {
                send(command, wait, replyTo(command))
                executed += command.id
                continue
            }
            val settlement = coordinator.forward(model, command, wait)
            failureOf(command, settlement)?.let { throw it }
            executed += command.id
            settlement.rejection?.let { rejection ->
                return Executed(executed, Rejected(command, rejection, model.commandType(command.body.javaClass).name))
            }
        }
        return Executed(executed)
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
        val error = settlement.committed.error
        return IllegalStateException(
            "The derived command ${command.id} on ${command.ref} failed: ${error?.code} ${error?.message}",
            (settlement.outcome as? CommandOutcome.Unknown)?.cause,
        )
    }

    /**
     * What [execute] did: the ids of the commands it [executed], in order, the rejected one last when one was
     * [rejected].
     */
    data class Executed(val executed: List<String>, val rejected: Rejected? = null)

    /** The derived [command], of the full type name [commandType], was refused with [rejection]. */
    data class Rejected(val command: Command<*>, val rejection: Rejection, val commandType: String) {
        /** The error a wait at the saga's hop ends with. */
        val error: ErrorInfo get() = ErrorInfo(rejection.code, rejection.message)

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
class DerivedCommandRejectedException(val command: Command<*>, val rejection: Rejection) :
    HandlingRejectedException(
        ErrorInfo(rejection.code, rejection.message),
        "The derived command ${command.id} on ${command.ref} was rejected: ${rejection.code} ${rejection.message}",
    )
