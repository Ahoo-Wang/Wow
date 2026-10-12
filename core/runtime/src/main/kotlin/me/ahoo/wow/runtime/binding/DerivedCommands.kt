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
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.spi.wait.WaitContext

/**
 * Executes the commands a saga derived from an entry (design 01 §1; design 04 §6.1, decision D62) on the aggregates of
 * its own bounded context, the [models], through that context's [coordinator]:
 *
 * - **In order, to the end**: each is executed in turn, and the saga's handling is done once every one is committed
 *   (or changed nothing, or was already committed by an earlier execution of the same commit). The commands of a
 *   context are committed in its own log, so they need not wait beyond COMMITTED (decision D33).
 * - **Wait chains**: when the entry's wait context waits for this saga next ([waitOf]), the commands carry the rest of
 *   the chain on ([WaitContext.derived]): [WaitCoordinator.forward] signals their own hops to the waiting node, and the
 *   saga's hop, signalled by the subscription with their ids, is complete once each of them reached the next hop; with
 *   none derived, it is complete at once.
 * - **Failures**: a command that is rejected fails the handling ([DerivedCommandRejectedException]; design 01 §1:
 *   the saga does not declare rejections it expects yet), as does one whose outcome is contended or unknown, and a
 *   command to an aggregate of another context: a cross-context command goes through the context's command topic
 *   (decision D33), which the runtime does not have yet. The subscription retries the handling, which derives the
 *   same requests again, then parks the aggregate.
 */
class DerivedCommands(models: Collection<AggregateModel<*>>, private val coordinator: WaitCoordinator) {
    private val models: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }

    /** Executes [commands], with [wait] when the saga's hop is waited for, and returns their ids (D62). */
    suspend fun execute(commands: List<Command<*>>, wait: WaitContext?): List<String> {
        for (command in commands) {
            val model = checkNotNull(models[command.ref.type]) {
                "A saga derived ${command.id} for ${command.ref}, an aggregate type its context does not have: " +
                    "commands across contexts go through the command topic (decision D33), not supported yet."
            }
            failureOf(command, coordinator.forward(model, command, wait))?.let { throw it }
        }
        return commands.map { it.id }
    }

    /** Why [outcome] fails the saga, or null when [command] is settled: committed, unchanged or already committed. */
    private fun failureOf(command: Command<*>, outcome: CommandOutcome): Exception? = when (outcome) {
        is CommandOutcome.Committed, is CommandOutcome.AlreadyCommitted, is CommandOutcome.Unchanged -> null
        is CommandOutcome.Rejected -> DerivedCommandRejectedException(command, outcome.rejection)
        is CommandOutcome.Contended -> IllegalStateException(
            "The derived command ${command.id} on ${command.ref} was contended ${outcome.attempts} times.",
        )

        is CommandOutcome.Unknown -> IllegalStateException(
            "The outcome of the derived command ${command.id} on ${command.ref} is unknown.",
            outcome.cause,
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

/** A command a saga derived was rejected: the saga's handling fails (design 01 §1). */
class DerivedCommandRejectedException(val command: Command<*>, val rejection: Rejection) :
    IllegalStateException(
        "The derived command ${command.id} on ${command.ref} was rejected: ${rejection.code} ${rejection.message}",
    )
