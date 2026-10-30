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

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.HandlingRejectedException
import me.ahoo.wow.runtime.admission.CreationIds
import me.ahoo.wow.runtime.client.InProcessCommandClient
import me.ahoo.wow.runtime.cluster.ReplyTo
import me.ahoo.wow.runtime.command.CommandSender
import me.ahoo.wow.runtime.wait.Settlement
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.busyOf
import me.ahoo.wow.runtime.wait.toError
import me.ahoo.wow.spi.wait.WaitContext

/**
 * Executes the commands a saga derived from an entry on the aggregates of
 * its own bounded context, the [models], through that context's [coordinator], which routes each to the owner of its
 * partition as it routes an admitted command:
 *
 * - **Each settled on its own**: every command is executed, whatever another's outcome; the saga's handling is done once
 *   every one is committed (or changed nothing, or was already committed by an earlier execution of the same commit),
 *   rejected, or, for another context, acknowledged by the broker. The commands of a context are committed in its own
 *   log, so they need not wait beyond COMMITTED.
 * - **Order per target only**: the commands to one aggregate are executed in the order the saga derived them; those to
 *   different aggregates run concurrently, at most [MAX_CONCURRENT_TARGETS] at once. One saga entry may derive many
 *   (a recompilation of every dataset that reads a source): the bound keeps one entry from taking the node's whole
 *   in-flight budget ([me.ahoo.wow.runtime.ExecutorOptions.maxInFlight], 4,096 by default) with its fan-out, so the
 *   other subscriptions' entries keep theirs, while it still overlaps the commits of different partitions.
 * - **Wait chains**: when the entry's wait context waits for this saga next ([waitOf]), the commands carry the rest of
 *   the chain on ([WaitContext.derived]): [WaitCoordinator.forward] signals their own hops to the waiting node, and the
 *   saga's hop, signalled by the subscription with their ids, is complete once each of them reached the next hop; with
 *   none derived, it is complete at once.
 * - **Rejections** are business outcomes: a rejected command does not stop the others. The result lists each one
 *   ([Executed.rejected]), in the order the saga derived them, and each goes to the saga's `@OnRejected` handler on its
 *   own ([SagaReceipts]), as the receipt of a rejection by another context does; nothing is retried for it.
 * - **Commits** of its own context: the result lists each command committed (or already committed, or unchanged)
 *   with its outcome ([Executed.committed]), for the saga's `@OnCommitted` handler.
 * - **Creations without an id**: a command of its own context that names no aggregate
 *   ([me.ahoo.wow.api.aggregate.AggregateRef.isAssigned]) takes its id from [creations] before it runs: the id its
 *   request already created, else a new one from its aggregate type's generator. One to another context takes it
 *   there, at the target's intake.
 * - **Other contexts**: the commands to aggregates of contexts outside the saga's own are sent
 *   to their contexts' command topics through the sender ([CommandSender.sendAll]), those of one topic as one batch, and
 *   are done once the broker has acknowledged them, a durable ACCEPTED; its outcome is the target's, and a rejection comes back as a receipt to the [ReplyTo] the caller gives
 *   it. It carries [wait] on, so the target signals the waiting node as this context would.
 * - **Scope**: a derived command is not checked against the caller's scope on an existing target; the
 *   root command was authorized at its admission. Each carries a causation, so the kernel tells it from a caller's
 *   ([me.ahoo.wow.kernel.isDerived]): one a handler built without it is given itself as its root.
 * - **Backpressure**: a command the node refused as busy (its lane full, or the node at its most commands in flight)
 * fails the handling with a [me.ahoo.wow.runtime.CommandBusyException], which the subscription
 *   retries after a backoff without spending its retry budget.
 * - **Technical failures** fail the handling: a command whose outcome is contended or unknown, or that the executor
 *   did not take (the node closing), a command the broker
 *   did not acknowledge, one to another context with no sender (a node without a command transport), and one to an
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
     * Executes every one of [commands], with [wait] when the saga's hop is waited for; returns the ids of those it
     * executed, in the order they were derived, those that were rejected and those of its own context it committed. A
     * command to another context is sent with the [replyTo] its receipt goes to, if any.
     */
    suspend fun execute(
        commands: List<Command<*>>,
        wait: WaitContext?,
        replyTo: (Command<*>) -> ReplyTo? = { null },
    ): Executed {
        // The kernel derives each command in its chain: it carries the causation of the commit it answers.
        val derived = commands
        val (local, remote) = derived.indices.partition { models[derived[it].ref.type] != null }
        val outcomes = arrayOfNulls<Outcome>(derived.size)
        coroutineScope {
            if (remote.isNotEmpty()) {
                launch {
                    send(remote.map { derived[it] }, wait, replyTo)
                    remote.forEach { outcomes[it] = Outcome(derived[it].id) }
                }
            }
            val targets = Semaphore(MAX_CONCURRENT_TARGETS)
            // One target's commands in turn: a creation without an id is a target of its own until it has one.
            local.groupBy { derived[it].ref.takeIf { ref -> ref.isAssigned } ?: it }.values.forEach { indices ->
                launch {
                    targets.withPermit {
                        indices.forEach { outcomes[it] = executeHere(derived[it], wait) }
                    }
                }
            }
        }
        val settled = outcomes.map { checkNotNull(it) }
        return Executed(
            executed = settled.map { it.id },
            rejected = settled.mapNotNull { it.rejected },
            committed = settled.mapNotNull { it.committed },
        )
    }

    /** Executes [derived], a command of the saga's own context, through its partition's owner. */
    private suspend fun executeHere(derived: Command<*>, wait: WaitContext?): Outcome {
        val model = models.getValue(derived.ref.type)
        // A creation that names no aggregate takes the id its request created, or a new one.
        val command = creations.assign(model, derived)
        val settlement = coordinator.forward(model, command, wait)
        failureOf(command, settlement)?.let { throw it }
        val commandType = model.commandType(command.body.javaClass).name
        settlement.rejection?.let { rejection ->
            return Outcome(
                command.id,
                rejected = Rejected(command, rejection, commandType, settlement.committed.version)
            )
        }
        return Outcome(command.id, committed = Committed(command, commandType, settlement.committed))
    }

    /** What executing one command came to: its id, and its rejection or its commit of this context. */
    private class Outcome(val id: String, val rejected: Rejected? = null, val committed: Committed? = null)

    /** Sends [commands], to aggregates of other contexts, to their command topics and waits for the broker. */
    private suspend fun send(commands: List<Command<*>>, wait: WaitContext?, replyTo: (Command<*>) -> ReplyTo?) {
        commands.forEach { command ->
            check(command.ref.type.context !in contexts) {
                "A saga derived ${command.id} for ${command.ref}, an aggregate type its context does not have."
            }
        }
        val sender = checkNotNull(sender) {
            val command = commands.first()
            "A saga derived ${command.id} for ${command.ref}, of the context ${command.ref.type.context}: a command " +
                "across contexts goes through the command topic, and this node has no command transport."
        }
        sender.sendAll(commands.map { it to replyTo(it) }, wait)
    }

    /**
     * Why [settlement] fails the saga, or null when [command] is settled: committed, unchanged, already committed or
     * rejected, here or on its partition's owner.
     */
    private fun failureOf(command: Command<*>, settlement: Settlement): Exception? {
        if (!settlement.failed) {
            return null
        }
        // A busy node is backpressure, which the subscription retries and throttles.
        settlement.busyOf(command)?.let { return it }
        val error = settlement.committed.error
        // With its code, so the subscription tells backpressure (`wow.wait.timeout`) from an outage or a defect.
        return InProcessCommandClient.problemOf(
            settlement.committed,
            "The derived command ${command.id} on ${command.ref} failed: ${error?.code} ${error?.message}" +
                settlement.cause?.let { " ($it)" }.orEmpty(),
            settlement.failure,
        )
    }

    /**
     * What [execute] did: the ids of the commands it [executed], every one, in the order they were derived; those that
     * were [rejected], and those of its own context it [committed], each in that order.
     */
    data class Executed(
        val executed: List<String>,
        val rejected: List<Rejected> = emptyList(),
        val committed: List<Committed> = emptyList(),
    )

    /**
     * The derived [command] of its own context, of the full type name [commandType], was committed (or had been, or
     * changed nothing) with [result] at its `committed` hop.
     */
    data class Committed(val command: Command<*>, val commandType: String, val result: CommandResult) {
        /**
         * The commit as the saga's `@OnCommitted` handler takes it, from the saga's [execution] that
         * derived it: on the aggregate the result names, the one a creation without an id got.
         */
        fun toCommandCommitted(execution: ExecutionId): CommandReceipt.Committed<Any> = CommandReceipt.Committed(
            commandId = command.id,
            commandType = commandType,
            ref = result.ref,
            version = checkNotNull(result.version) { "A committed command's result carries its version." },
            result = result,
            causation = checkNotNull(command.causation) { "A derived command carries its causation." },
            executionId = execution,
            operator = command.operator,
            scope = command.scope,
            headers = RequestFacts.propagatedOf(command.headers),
        )
    }

    /**
     * The derived [command], of the full type name [commandType], was refused with [rejection], answered at [version]
     * ([CommandReceipt.Rejected.version]).
     */
    data class Rejected(
        val command: Command<*>,
        val rejection: Rejection,
        val commandType: String,
        val version: Int? = null,
    ) {
        /** The error a wait at the saga's hop ends with. */
        val error: ErrorInfo get() = rejection.toError()

        /** The rejection as the saga's `@OnRejected` handler takes it, from the saga's [execution] that derived it. */
        fun toCommandRejected(execution: ExecutionId): CommandReceipt.Rejected<Any> = CommandReceipt.Rejected(
            commandId = command.id,
            commandType = commandType,
            ref = command.ref,
            error = error,
            causation = checkNotNull(command.causation) { "A derived command carries its causation." },
            executionId = execution,
            operator = command.operator,
            scope = command.scope,
            headers = RequestFacts.propagatedOf(command.headers),
            version = version,
        )
    }

    companion object {
        /** The most aggregates the commands of one execution are executed on at once. */
        const val MAX_CONCURRENT_TARGETS: Int = 16

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
 * Commands a saga derived were [rejected], and the saga has no `@OnRejected` handler for them, or commands its handler
 * derived to compensate were rejected in turn: the aggregate parks once, at once, without a retry, listing every one of
 * them, and a wait at the saga's hop ends REJECTED with the first rejection.
 */
internal class DerivedCommandRejectedException(val rejected: List<Pair<Command<*>, Rejection>>) :
    HandlingRejectedException(rejected.first().second.toError(), messageOf(rejected)) {
    constructor(command: Command<*>, rejection: Rejection) : this(listOf(command to rejection))

    /** The first rejected command. */
    val command: Command<*> get() = rejected.first().first

    /** The first rejection. */
    val rejection: Rejection get() = rejected.first().second

    private companion object {
        fun messageOf(rejected: List<Pair<Command<*>, Rejection>>): String {
            require(rejected.isNotEmpty()) { "At least one derived command was rejected." }
            if (rejected.size == 1) {
                val (command, rejection) = rejected.single()
                return "The derived command ${command.id} on ${command.ref} was rejected: " +
                    "${rejection.code} ${rejection.message}"
            }
            val each = rejected.joinToString("; ") { (command, rejection) ->
                "${command.id} on ${command.ref}: ${rejection.code} ${rejection.message}"
            }
            return "${rejected.size} derived commands were rejected: $each."
        }
    }
}

/** The rejections of [this], as a [DerivedCommandRejectedException] parks them, or null when none was rejected. */
internal fun List<DerivedCommands.Rejected>.parking(): DerivedCommandRejectedException? =
    takeIf { it.isNotEmpty() }?.let { rejected ->
        DerivedCommandRejectedException(
            rejected.map { it.command to it.rejection }
        )
    }
