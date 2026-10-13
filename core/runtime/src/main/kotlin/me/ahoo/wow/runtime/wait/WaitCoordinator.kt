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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Rejection
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
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The tuning of waits on one node.
 *
 * - [origin] is this node, as signals address it (decision D65).
 * - [maxTimeout] caps the timeout a caller asks for (decision D64).
 * - [maxHops] is the longest chain a wait may follow (decision D62).
 * - [defaultTimeout] is the timeout of a command whose caller names no wait plan (decision D64), capped by
 *   [maxTimeout] as any other; [me.ahoo.wow.runtime.admission.CommandGateway] applies it to every entry.
 */
data class WaitOptions(
    val origin: Origin,
    val maxTimeout: Duration = DEFAULT_MAX_TIMEOUT,
    val maxHops: Int = DEFAULT_MAX_HOPS,
    val defaultTimeout: Duration = WaitPlan.DEFAULT_TIMEOUT,
) {
    init {
        require(maxTimeout.isPositive()) { "The maximum wait timeout is positive." }
        require(maxHops >= 1) { "A wait chain has at least one hop." }
        require(defaultTimeout.isPositive()) { "The default wait timeout is positive." }
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
 * - The results the decide function put (decision D49) come with the command's commit, at COMMITTED, from this node's
 *   execution or the owner's forward reply; each later hop's result of that command carries them, whichever node's
 *   subscription signalled it, so the default wait ends with them at the snapshot (see [WaitState]).
 * - A rejection or failure ends the wait at once. A command that changed nothing reaches its later hops at once:
 *   there is no entry for a subscription to handle.
 * - A duplicate request (decision D63) cannot be signalled again: its later hops happened, or will, under the first
 *   request's wait. A HANDLED hop of this context that ends the chain is checked from the subscription's checkpoint
 *   and the failure ledger ([SubscriptionDirectory.hasHandled]); otherwise the wait stops at COMMITTED, `duplicate`.
 * - On timeout the answer is FAILED with [ResultCodes.WAIT_TIMEOUT] at the last hop reached. The deadline covers the
 *   whole wait, the command's execution included (decision D64): the command is routed and executed in a supervised
 *   scope this coordinator owns, so a wait that times out before the command's outcome is known answers at ACCEPTED
 *   while the execution goes on, not cancelled half-way through a commit. Its outcome is then only logged; a retry
 *   with the same request id finds it, as the kernel deduplicates by request id.
 * - The deadline is the wait's, not its caller's: the runtime's own deadline thread ends the wait when it passes,
 *   whatever the caller's coroutine context. A caller's own timers, such as a WebFlux handler's, run in
 *   `Dispatchers.Unconfined` on kotlinx's one `DefaultExecutor` thread, which also runs inline whatever such a
 *   timer resumes, so one held-up continuation there would hold every such deadline up. The wait itself runs in
 *   this coordinator's execution context, so neither the signals nor the deadline run its caller's code.
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
 *
 * The executions outlive their waits, so a node that stops [join]s them before it closes its executor, then [close]s
 * the coordinator.
 */
class WaitCoordinator(
    private val executor: Executor,
    private val registry: WaitRegistry,
    private val directory: SubscriptionDirectory,
    private val signals: SignalSender,
    options: WaitOptions,
    private val clock: () -> Long = System::currentTimeMillis,
    private val waitIds: () -> String = { UUID.randomUUID().toString() },
    private val router: CommandRouter = CommandRouter.LOCAL,
    private val executionContext: CoroutineContext = Dispatchers.Default,
) {
    /** Where the commands of [stream] execute, apart from their waits (decision D64). */
    private val executions = CoroutineScope(SupervisorJob() + executionContext)

    /**
     * The tuning of the waits started here. A [me.ahoo.wow.runtime.node.ServiceNode] that learns its internal address
     * only once its internal port is bound sets the origin's address then, before it starts.
     */
    @Volatile
    var options: WaitOptions = options
        internal set

    /** The commands executing in this coordinator's scope, their waits ended or not. */
    val executing: Int get() = executions.coroutineContext.job.children.count()

    /**
     * Waits until no command executes in this coordinator's scope: those whose waits ended before their outcome was
     * known settle normally, their outcome logged. The caller bounds it; it does not stop new executions.
     */
    suspend fun join() {
        while (true) {
            val running = executions.coroutineContext.job.children.toList()
            if (running.isEmpty()) {
                return
            }
            running.joinAll()
        }
    }

    /** Cancels what still executes in this coordinator's scope; a stopping node calls it after [join]. */
    fun close() {
        executions.cancel()
    }

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
     * - The timeout bounds the execution too (decision D64): a command whose outcome is not known by the deadline ends
     *   the wait at ACCEPTED, timed out, and goes on executing in this coordinator's scope, as does one whose
     *   collector cancels the flow first; its outcome is logged. The deadline does not depend on the collector's
     *   context, and the flow runs in this coordinator's execution context, handing its results to the collector.
     * - Outcomes are results, never errors: the flow fails only when the execution itself throws while it is waited
     *   for, and is cancelled with its collector. Either way the wait is unregistered, and later signals for it are
     *   dropped.
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
        val settled = AtomicBoolean()
        var expiry: ScheduledFuture<*>? = null
        try {
            waiting.offer(reachedResult(command, function, Hop.accepted(context), clock()))
            // Once ACCEPTED is reached: a timeout answers at the last hop reached.
            expiry = expireAt(deadline, waiting, settled)
            val wait = WaitContext(waitId, options.origin, chain.hops, deadline)
            settle(model, command, wait, waiting)?.let { settlement ->
                settled.set(true)
                waiting.offer(settlement.committed)
                settlement.reached.forEach { waiting.offer(it) }
                if (settlement.committed.duplicate) {
                    // A duplicate is not signalled again (D63): what it did not reach at once, it will not reach.
                    waiting.finish(settlement.committed)
                }
            }
            // Until the wait ends: with its final result, or with the timeout its deadline put there.
            for (result in waiting.results) {
                emit(waiting.carry(result))
            }
        } finally {
            expiry?.cancel(false)
            registry.remove(waitId)
        }
    }.flowOn(executionContext)

    /**
     * Ends [waiting] with a timeout at [deadline] (decision D64), from [DEADLINES], apart from the waiter:
     * at ACCEPTED, saying the outcome is not known, until the waiter has the command's settlement ([settled]), else at
     * the last hop it reached. Whether or not the wait had ended, its [deadline passes][Waiting.deadlinePassed].
     */
    private fun expireAt(deadline: Long, waiting: Waiting, settled: AtomicBoolean): ScheduledFuture<*> =
        DEADLINES.schedule(
            {
                waiting.expire(clock()) { hop ->
                    if (settled.get()) {
                        "The wait timed out after reaching $hop."
                    } else {
                        "The wait timed out at $hop, before the command's outcome was known."
                    }
                }
            },
            (deadline - clock()).coerceAtLeast(0),
            TimeUnit.MILLISECONDS,
        )

    /**
     * Accepts [command], whose wait plan has only an ACCEPTED hop ([isAcceptedOnly], a chain that is always valid),
     * and returns its result at ACCEPTED at once (decision D100). The command is routed and executed in [scope], apart
     * from the caller, with no wait context, so nothing is signalled about it; its outcome is only logged. Not durable
     * (D15): a node that stops before running it loses it.
     */
    fun accept(model: AggregateModel<*>, command: Command<*>, scope: CoroutineScope): CommandResult {
        require(command.wait.isAcceptedOnly) { "Only a plan whose only hop is ACCEPTED is answered at once." }
        scope.launch { runDetached(model, command) }
        return reachedResult(command, commandFunction(model, command), Hop.accepted(command.ref.type.context), clock())
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

    /**
     * Routes and executes [command] in [executions] and returns its settlement, or null when [waiting]'s deadline
     * passes first. The execution is cancelled neither then nor with the caller: of the waiter giving up and the
     * execution finishing, whichever comes second learns it from [AtomicBoolean.compareAndSet] failing, and when the
     * execution finishes second, it logs its outcome.
     */
    private suspend fun settle(
        model: AggregateModel<*>,
        command: Command<*>,
        wait: WaitContext,
        waiting: Waiting,
    ): Settlement? {
        val taken = AtomicBoolean()
        val execution: Deferred<Settlement> = executions.async { routeReporting(model, command, wait, taken) }
        try {
            val settlement = select {
                execution.onAwait { it }
                waiting.deadlinePassed.onAwait { null }
            }
            return settlement ?: if (taken.compareAndSet(false, true)) null else execution.await()
        } catch (cancelled: CancellationException) {
            taken.set(true)
            throw cancelled
        }
    }

    /** Routes and executes [command]; logs its outcome when its waiter has gone ([taken] already set). */
    @Suppress("TooGenericExceptionCaught") // What the execution throws is the waiter's, or logged once it has gone.
    private suspend fun routeReporting(
        model: AggregateModel<*>,
        command: Command<*>,
        wait: WaitContext,
        taken: AtomicBoolean,
    ): Settlement {
        val settlement = try {
            router.route(model, command, wait) { executeHere(model, command, wait) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!taken.compareAndSet(false, true)) {
                logger.warn(failure) { "Command ${command.id} on ${command.ref} failed after its waiter had gone." }
            }
            throw failure
        }
        if (!taken.compareAndSet(false, true)) {
            val committed = settlement.committed
            logger.info {
                "Command ${command.id} on ${command.ref} settled ${committed.status} after its waiter had gone: " +
                    (committed.error?.toString() ?: "version ${committed.version}")
            }
        }
        return settlement
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
        return Settlement(committed, listOfNotNull(reached), outcome)
    }

    /**
     * Executes [command], derived on the way of a wait (by a saga, or later sent from another context), or sent by a
     * timer, on [model]'s aggregate with the [wait] context it inherited, and signals its results to the waiting node:
     * ACCEPTED when the context waits for it, then COMMITTED or the failure, then the later hop it reached at once.
     * Without a context, or past its deadline, it only executes.
     *
     * It is routed as an admitted command is ([router], design 02 §2; decisions D6, D71): to the owner of its
     * partition, degrading to this node, so the owner's state cache sees every commit of its partitions. A saga's
     * subscription runs on the owner of the partition of the entry it handles, which is not the derived command's
     * partition in general.
     *
     * Returns the [Settlement]: its [Settlement.outcome] is the kernel's when this node executed it.
     */
    suspend fun forward(model: AggregateModel<*>, command: Command<*>, wait: WaitContext?): Settlement {
        if (wait == null || wait.expiredAt(clock())) {
            return router.route(model, command, null) { executeHere(model, command, null) }
        }
        val function = commandFunction(model, command)
        val context = command.ref.type.context
        val leading = wait.remaining.takeWhile { it.stage != Stage.HANDLED }
        leading.firstOrNull { it.stage == Stage.ACCEPTED }?.let { accepted ->
            signal(wait, reachedResult(command, function, accepted, clock()))
        }
        val settlement = router.route(model, command, wait) { executeHere(model, command, wait) }
        val hop = leading.firstOrNull { it.stage == Stage.COMMITTED } ?: Hop.committed(context)
        signal(wait, settlement.committed.copy(hop = hop))
        // A command that changed nothing, or a duplicate whose subscription is past it (D63): no entry signals them.
        settlement.reached.forEach { signal(wait, it) }
        return settlement
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

        /**
         * Ends the waits of every coordinator of the process at their deadlines: one daemon thread that only ends them
         * and never runs their waiters, which resume in their coordinator's execution context.
         */
        val DEADLINES: ScheduledExecutorService = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "wow-wait-deadlines").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}

/** Whether this plan's only hop is ACCEPTED: the caller is answered at admission (decision D100). */
val WaitPlan.isAcceptedOnly: Boolean get() = hops.size == 1 && hops.single().stage == Stage.ACCEPTED

/**
 * What executing a command reached at once: its [committed] result (design 08 §7), at its COMMITTED hop, then the
 * later hops it [reached] without waiting for a subscription. The hops after them are signalled to the wait's origin.
 * [outcome] is the kernel's when this node executed the command; null when another node did and replied, or when the
 * executor did not take it.
 */
data class Settlement(
    val committed: CommandResult,
    val reached: List<CommandResult> = emptyList(),
    val outcome: CommandOutcome? = null,
) {
    /** The rejection [committed] ends with, when it is REJECTED (a business outcome, decision D153). */
    val rejection: Rejection?
        get() = committed.error?.takeIf { committed.status == CommandResult.Status.REJECTED }
            ?.let { Rejection(it.code, it.message) }

    /** Whether [committed] is FAILED: a technical failure, such as contention, an unknown outcome or a busy lane. */
    val failed: Boolean get() = committed.status == CommandResult.Status.FAILED
}
