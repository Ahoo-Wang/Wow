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

package me.ahoo.wow.runtime.wait

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.ExecutorBusyException
import me.ahoo.wow.runtime.ExecutorClosedException
import me.ahoo.wow.runtime.cluster.CommandRouter
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The tuning of waits on one node.
 *
 * - [origin] is this node, as signals address it (decision D65).
 * - [maxTimeout] caps the timeout a caller asks for (decision D64).
 * - [maxHops] is the longest chain a wait may follow (decision D62).
 */
data class WaitOptions(
    val origin: Origin,
    val maxTimeout: Duration = DEFAULT_MAX_TIMEOUT,
    val maxHops: Int = DEFAULT_MAX_HOPS,
) {
    init {
        require(maxTimeout.isPositive()) { "The maximum wait timeout is positive." }
        require(maxHops >= 1) { "A wait chain has at least one hop." }
    }

    companion object {
        val DEFAULT_MAX_TIMEOUT: Duration = 60.seconds
        const val DEFAULT_MAX_HOPS = 4
    }
}

/**
 * Runs commands and waits for their chains (design 04 §6; decisions D61–D65, D69).
 *
 * On the node a command arrives at, [execute] and [stream] check its wait plan, register the wait in [registry],
 * execute the command with a wait context naming this node, and answer once the chain is complete, has failed, or the
 * timeout runs out:
 *
 * - ACCEPTED and COMMITTED are reached here, from the kernel's outcome; a HANDLED hop by a signal from the
 *   subscription it names, which finds the wait context on the log entry.
 * - A rejection or failure ends the wait at once. A command that changed nothing reaches its later hops at once:
 *   there is no entry for a subscription to handle.
 * - A duplicate request (decision D63) cannot be signalled again: its later hops happened, or will, under the first
 *   request's wait. A HANDLED hop of this context that ends the chain is checked from the subscription's checkpoint
 *   and the failure ledger ([SubscriptionDirectory.hasHandled]); otherwise the wait stops at COMMITTED, `duplicate`.
 * - On timeout the answer is FAILED with [ResultCodes.WAIT_TIMEOUT] at the last hop reached.
 * - A plan the runtime cannot follow is rejected before the command runs ([ResultCodes.WAIT_INVALID]), including an
 *   explicit snapshot hop for an aggregate without a snapshot subscription; only the default plan
 *   ([me.ahoo.wow.api.result.WaitPlan.DEFAULT]) waits for COMMITTED there instead (decision D69).
 *
 * [forward] runs a command derived on the way, such as by a saga, with the wait context it inherited: it sends the
 * command's own results to the waiting node through [signals] instead of answering them.
 *
 * Which subscriptions a chain may name, and their kinds, come from [directory], until the Manifest gives them.
 *
 * Where the command runs is [router]'s choice (design 02 §2): here, through [executeHere], or on its partition's
 * owner, which executes it with the same wait context and replies with its [Settlement]; either way the wait goes
 * on here, and the later hops are signalled here.
 */
class WaitCoordinator(
    private val executor: Executor,
    private val registry: WaitRegistry,
    private val directory: SubscriptionDirectory,
    private val signals: SignalSender,
    val options: WaitOptions,
    private val clock: () -> Long = System::currentTimeMillis,
    private val waitIds: () -> String = { UUID.randomUUID().toString() },
    private val router: CommandRouter = CommandRouter.LOCAL,
) {
    /** Executes [command] on [model]'s aggregate and returns the result it ends its wait with. */
    suspend fun execute(model: AggregateModel<*>, command: Command<*>): CommandResult = stream(model, command).last()

    /**
     * Executes [command] on [model]'s aggregate and emits a result for each hop of its wait plan as it is reached, one
     * per command: a hop after a saga's emits one result for each command the saga derived, as each one reaches it,
     * in the order they arrive, and is complete with the last of them (decision D62). The command is executed when
     * the flow is collected.
     *
     * - The flow completes after the final result, the last one emitted: the last hop's last command, a saga hop that
     *   derived nothing, the first failure or rejection of any command on the chain (emitted at once, with nothing
     *   after it), a duplicate stopped at its last known hop, or a timeout ([ResultCodes.WAIT_TIMEOUT], at the last
     *   hop completed). A plan rejected at admission emits only that rejection, at ACCEPTED.
     * - A plan whose only hop is ACCEPTED still runs the command before the flow completes; [accept] answers it at
     *   once instead (decision D100).
     * - Outcomes are results, never errors: the flow fails only when the execution itself throws, and is cancelled
     *   with its collector. Either way the wait is unregistered, and later signals for it are dropped.
     */
    fun stream(model: AggregateModel<*>, command: Command<*>): Flow<CommandResult> = flow {
        val function = commandFunction(model, command)
        val context = command.ref.type.context
        val chain = when (val checked = WaitChain.of(command.wait, command.ref.type, directory, options.maxHops)) {
            is WaitChain.Invalid -> {
                emit(admissionRejected(command, function, ResultCodes.WAIT_INVALID, checked.reason, clock()))
                return@flow
            }

            is WaitChain -> checked
        }
        val timeout = minOf(command.wait.timeout, options.maxTimeout)
        val deadline = clock() + timeout.inWholeMilliseconds
        val waitId = waitIds()
        val waiting = registry.register(waitId, chain.hops, command.id)
        try {
            waiting.offer(reachedResult(command, function, Hop.accepted(context), clock()))
            val wait = WaitContext(waitId, options.origin, chain.hops, deadline)
            val settlement = router.route(model, command, wait) { executeHere(model, command, wait) }
            waiting.offer(settlement.committed)
            settlement.reached.forEach { waiting.offer(it) }
            if (settlement.committed.duplicate) {
                // A duplicate is not signalled again (D63): what it did not reach at once, it will not reach.
                waiting.finish(settlement.committed)
            }
            drain(waiting, deadline)
        } finally {
            registry.remove(waitId)
        }
    }

    /**
     * Accepts [command], whose wait plan has only an ACCEPTED hop ([isAcceptedOnly]), and returns at once (decision
     * D100): its result at ACCEPTED, or the rejection of a plan the runtime cannot follow. The command is routed and
     * executed in [scope], apart from the caller, with no wait context, so nothing is signalled about it; its outcome
     * is only logged. Not durable (D15): a node that stops before running it loses it.
     */
    fun accept(model: AggregateModel<*>, command: Command<*>, scope: CoroutineScope): CommandResult {
        val function = commandFunction(model, command)
        val checked = WaitChain.of(command.wait, command.ref.type, directory, options.maxHops)
        if (checked is WaitChain.Invalid) {
            return admissionRejected(command, function, ResultCodes.WAIT_INVALID, checked.reason, clock())
        }
        scope.launch { runDetached(model, command) }
        return reachedResult(command, function, Hop.accepted(command.ref.type.context), clock())
    }

    @Suppress("TooGenericExceptionCaught") // Nobody waits for a detached command: whatever fails is logged.
    private suspend fun runDetached(model: AggregateModel<*>, command: Command<*>) {
        try {
            val settlement = router.route(model, command, null) { executeHere(model, command, null) }
            val committed = settlement.committed
            if (committed.status != CommandResult.Status.SUCCEEDED) {
                logger.info { "Detached command ${command.id} on ${command.ref} ended ${committed.status}: ${committed.error}" }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(failure) { "Detached command ${command.id} on ${command.ref} failed." }
        }
    }

    /** Emits the wait's hop results until it ends, ending it with a timeout at [deadline]. */
    private suspend fun FlowCollector<CommandResult>.drain(waiting: Waiting, deadline: Long) {
        while (true) {
            val left = (deadline - clock()).coerceAtLeast(0).milliseconds
            val next = withTimeoutOrNull(left) { waiting.results.receiveCatching() }
            if (next == null) {
                val last = checkNotNull(waiting.lastReached) { "A registered wait has reached ACCEPTED." }
                waiting.finish(
                    last.failed(ResultCodes.WAIT_TIMEOUT, "The wait timed out after reaching ${last.hop}.")
                        .copy(signalTime = clock()),
                )
                // Ended now, by the timeout or by a result that came first: what is left ends with the final result.
                for (result in waiting.results) {
                    emit(result)
                }
                return
            }
            if (next.isClosed) {
                return
            }
            emit(next.getOrThrow())
        }
    }

    /**
     * Executes [command] on [model]'s aggregate on this node, with the [wait] context it arrived with, and settles
     * what it reaches at once: its result at COMMITTED, then its next HANDLED hop when that is already reached (a
     * command that changed nothing appended no entry to handle; a duplicate whose subscription is past it, decision
     * D63). The node a command arrived at runs it so when its router chooses this node, and the owner of its
     * partition runs it so for a forward.
     *
     * A command the executor does not take is settled FAILED at COMMITTED, not thrown (decision D115): its aggregate's
     * lane is full ([ResultCodes.BUSY]), or the executor is closed ([ResultCodes.NODE_CLOSING]).
     */
    suspend fun executeHere(model: AggregateModel<*>, command: Command<*>, wait: WaitContext?): Settlement {
        val function = commandFunction(model, command)
        val hop = Hop.committed(command.ref.type.context)
        val outcome = try {
            executor.execute(model, command, wait)
        } catch (busy: ExecutorBusyException) {
            return Settlement(reachedResult(command, function, hop, clock()).failed(ResultCodes.BUSY, busy.message!!))
        } catch (closed: ExecutorClosedException) {
            return Settlement(
                reachedResult(command, function, hop, clock()).failed(ResultCodes.NODE_CLOSING, closed.message!!),
            )
        }
        val committed = outcome.toResult(command, function, hop, clock())
        val after = wait?.afterCommitted()?.remaining.orEmpty()
        val reached = when (outcome) {
            is CommandOutcome.Unchanged -> after.firstOrNull()?.let { committed.copy(hop = it, signalTime = clock()) }
            is CommandOutcome.AlreadyCommitted -> handledBefore(command, outcome, after.firstOrNull(), after.size == 1)
            else -> null
        }
        return Settlement(committed, listOfNotNull(reached))
    }

    /**
     * Executes [command], derived on the way of a wait (by a saga, or later sent from another context), on [model]'s
     * aggregate with the [wait] context it inherited, and signals its results to the waiting node: ACCEPTED when the
     * context waits for it, then COMMITTED or the failure. Without a context, or past its deadline, it only executes.
     */
    suspend fun forward(model: AggregateModel<*>, command: Command<*>, wait: WaitContext?): CommandOutcome {
        if (wait == null || wait.expiredAt(clock())) {
            return executor.execute(model, command)
        }
        val function = commandFunction(model, command)
        val context = command.ref.type.context
        val leading = wait.remaining.takeWhile { it.stage != Stage.HANDLED }
        leading.firstOrNull { it.stage == Stage.ACCEPTED }?.let { accepted ->
            signal(wait, reachedResult(command, function, accepted, clock()))
        }
        val outcome = executor.execute(model, command, wait)
        val committed = outcome.toResult(
            command,
            function,
            leading.firstOrNull { it.stage == Stage.COMMITTED } ?: Hop.committed(context),
            clock(),
        )
        signal(wait, committed)
        when (outcome) {
            is CommandOutcome.Unchanged -> reachVacuously(
                committed,
                wait.afterCommitted().nextHandled
            ) { signal(wait, it) }
            is CommandOutcome.AlreadyCommitted -> {
                // A retried saga derives the same command again: its entry carries the same wait context, so the
                // subscription signals when it handles it. If it already has, that signal is gone: answer from the
                // checkpoint.
                val remaining = wait.afterCommitted().remaining
                handledBefore(command, outcome, remaining.firstOrNull(), remaining.size == 1)?.let { signal(wait, it) }
            }

            else -> Unit
        }
        return outcome
    }

    /**
     * The result of [command], a duplicate of the request committed as [outcome], at its [handled] hop, when that hop
     * is known to be reached (decision D63): it [endsChain] (a saga's derived commands cannot be found again), it is
     * of the command's own context, and its subscription is past the stream for good. Null otherwise.
     */
    private suspend fun handledBefore(
        command: Command<*>,
        outcome: CommandOutcome.AlreadyCommitted,
        handled: Hop?,
        endsChain: Boolean,
    ): CommandResult? {
        val subscription = handled?.subscription ?: return null
        val context = command.ref.type.context
        if (!endsChain || handled.context != context || !directory.hasHandled(subscription, outcome.stream)) {
            return null
        }
        val kind = directory.kindOf(context, subscription) ?: return null
        return handledResult(outcome.stream, handled, subscriptionFunction(kind, context, subscription), clock())
            .copy(commandId = command.id, duplicate = true)
    }

    /**
     * A command that changed nothing appended no entry, so no subscription will handle it: its next HANDLED hop,
     * [handled], is reached at once, deriving nothing.
     */
    private inline fun reachVacuously(committed: CommandResult, handled: Hop?, reach: (CommandResult) -> Unit) {
        if (handled != null) {
            reach(committed.copy(hop = handled, signalTime = clock()))
        }
    }

    @Suppress("TooGenericExceptionCaught") // A signal is best effort: whatever fails, the command's result stands.
    private suspend fun signal(wait: WaitContext, result: CommandResult) {
        try {
            signals.send(wait.origin, WaitSignal(waitId = wait.waitId, result = result))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(failure) { "Failed to signal wait ${wait.waitId} at ${result.hop}; the waiter will time out." }
        }
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}

/** Whether this plan's only hop is ACCEPTED: the caller is answered at admission (decision D100). */
val WaitPlan.isAcceptedOnly: Boolean get() = hops.size == 1 && hops.single().stage == Stage.ACCEPTED

/**
 * What executing a command reached at once: its [committed] result (design 08 §7), at its COMMITTED hop, then the
 * later hops it [reached] without waiting for a subscription. The hops after them are signalled to the wait's origin.
 */
data class Settlement(val committed: CommandResult, val reached: List<CommandResult> = emptyList())
