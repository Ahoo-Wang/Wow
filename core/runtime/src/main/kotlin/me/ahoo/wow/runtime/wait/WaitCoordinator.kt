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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.ScheduleNotDueException
import me.ahoo.wow.runtime.CommandBusyException
import me.ahoo.wow.runtime.CommandWithdrawnException
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.ExecutorBusyException
import me.ahoo.wow.runtime.ExecutorClosedException
import me.ahoo.wow.runtime.cluster.CommandRouter
import me.ahoo.wow.runtime.cluster.TopicRouter
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.traceContext
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The tuning of waits on one node.
 *
 * - [origin] is this node, as signals address it (decision D65).
 * - [maxTimeout] caps the timeout a caller asks for (decision D64).
 * - [maxHops] is the longest chain a wait may follow (decisions D62, D190): [DEFAULT_MAX_HOPS] by default, at most
 *   [MAX_HOPS_CAP]. Each saga a chain follows adds two hops, its `handled` and the next `committed`.
 * - [unknownContexts] is what a chain may name of a context this node knows no Manifest of (decision D190).
 * - [defaultTimeout] is the timeout of a command whose caller names no wait plan (decision D64), capped by
 *   [maxTimeout] as any other; [me.ahoo.wow.runtime.admission.CommandGateway] applies it to every entry.
 *
 * Fields may be added (decision D216): Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class WaitOptions @InternalWowApi constructor(
    val origin: Origin,
    val maxTimeout: Duration = DEFAULT_MAX_TIMEOUT,
    val maxHops: Int = DEFAULT_MAX_HOPS,
    val defaultTimeout: Duration = WaitPlan.DEFAULT_TIMEOUT,
    val unknownContexts: UnknownContexts = UnknownContexts.ALLOW,
) {
    init {
        require(maxTimeout.isPositive()) { "The maximum wait timeout is positive." }
        require(maxHops >= 1) { "A wait chain has at least one hop." }
        require(maxHops <= MAX_HOPS_CAP) { "A wait chain has at most $MAX_HOPS_CAP hops, not $maxHops." }
        require(defaultTimeout.isPositive()) { "The default wait timeout is positive." }
    }

    /** This WaitOptions with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        origin: Origin = this.origin,
        maxTimeout: Duration = this.maxTimeout,
        maxHops: Int = this.maxHops,
        defaultTimeout: Duration = this.defaultTimeout,
        unknownContexts: UnknownContexts = this.unknownContexts,
    ): WaitOptions = WaitOptions(origin, maxTimeout, maxHops, defaultTimeout, unknownContexts)

    override fun equals(other: Any?): Boolean =
        this === other || other is WaitOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "WaitOptions(origin=$origin, maxTimeout=$maxTimeout, maxHops=$maxHops, " +
            "defaultTimeout=$defaultTimeout, unknownContexts=$unknownContexts)"

    private fun valueFields(): List<Any?> = listOf(origin, maxTimeout, maxHops, defaultTimeout, unknownContexts)

    companion object {
        val DEFAULT_MAX_TIMEOUT: Duration = 60.seconds
        const val DEFAULT_MAX_HOPS = 16

        /** The most hops a node lets a wait chain have, whatever it is configured with (decision D190). */
        const val MAX_HOPS_CAP = 32
    }
}

/**
 * What the node a wait starts on does with a hop of a context it knows no Manifest of, its own or a peer's (decision
 * D190).
 */
enum class UnknownContexts {
    /**
     * The default: a context with a legal name (decision D169) is let through, and the hop is checked where the chain
     * arrives; a context or a subscription that is not there is never signalled, so the wait times out at the last
     * hop it reached (decision D64).
     */
    ALLOW,

    /** A hop of a context the node does not know makes the plan invalid ([ResultCodes.WAIT_INVALID]). */
    REJECT,
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
 *   with the same request id finds it, as the kernel deduplicates by request id. A command withdrawn because the
 *   deadline passed while it waited in its lane (decision D228 ②) or before it was forwarded (review R2.1-9) ends the
 *   wait the same way, whichever noticed the deadline first (review X-12).
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
 * The commands of the types [topicRouter] routes take the `Kafka` router instead (design 02 §2, decision D143): the
 * command is written to its context's command topic, with the wait context, and the wait reaches ACCEPTED once the
 * broker acknowledged it, durable from then on; the owner of its log partition executes it from the topic
 * ([me.ahoo.wow.runtime.command.CommandIntake]) and signals its COMMITTED result and the later hops here, as for a
 * saga's command. A broker that does not acknowledge it before the deadline ends the wait at ACCEPTED, FAILED. Only
 * the commands admitted here take it: [forward] routes a saga's, an intake's and a timer's command as before, since
 * the intake that reads the topic forwards what it reads.
 *
 * The executions outlive their waits, so a node that stops [join]s them before it closes its executor, then [close]s
 * the coordinator.
 */
@InternalWowApi
class WaitCoordinator(
    private val executor: Executor,
    private val registry: WaitRegistry,
    private val directory: SubscriptionDirectory,
    private val signals: SignalSender,
    options: WaitOptions,
    private val clock: () -> Long = System::currentTimeMillis,
    private val waitIds: () -> String = GlobalIdGenerator.UUID_V7::next,
    private val router: CommandRouter = CommandRouter.LOCAL,
    private val executionContext: CoroutineContext = Dispatchers.Default,
    /** The `Kafka` router of the context's types configured for it (decision D143); none by default. */
    private val topicRouter: TopicRouter? = null,
    /** Sees the commands refused before the kernel and the waits that time out (review A5-11). */
    private val observer: Observer = Observer.NONE,
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

    /**
     * Executes [command] on [model]'s aggregate and returns the result it ends its wait with: the last one [stream]
     * would emit. It runs in this coordinator's execution context as [stream] does, without the channel and producer
     * coroutine a flow moved to another context takes (review A5-5): a caller that wants the final result only pays
     * for that.
     */
    suspend fun execute(model: AggregateModel<*>, command: Command<*>): CommandResult = withContext(executionContext) {
        var last: CommandResult? = null
        run(model, command) { last = it }
        checkNotNull(last) { "The wait of ${command.id} ended without a result." }
    }

    /**
     * Executes [command] on [model]'s aggregate and emits a result for each hop of its wait plan as it is reached, one
     * per command: a hop after a saga's emits one result for each command the saga derived, as each one reaches it,
     * in the order they arrive, and is complete with the last of them (decision D62). The command is executed when
     * the flow is collected.
     *
     * - The flow completes after the final result, the last one emitted: the last hop's last command, a saga hop that
     *   derived nothing before the plan's last hop (marked [CommandResult.chainEnded], with the hops not reached as
     *   [CommandResult.pending], decision D212), the first failure or rejection of any command on the chain (emitted
     *   at once, with nothing after it), a duplicate stopped at its last known hop, or a timeout
     *   ([ResultCodes.WAIT_TIMEOUT], at the last hop completed). A plan rejected at admission emits only that
     *   rejection, at ACCEPTED.
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
        run(model, command) { emit(it) }
    }.flowOn(executionContext)

    /** [emit], counting a timeout the wait ends with (review A5-11). */
    private suspend fun observed(command: Command<*>, result: CommandResult, emit: suspend (CommandResult) -> Unit) {
        if (result.error?.code == ResultCodes.WAIT_TIMEOUT) {
            observer.waitTimedOut(command.ref.type, result.hop.stage)
        }
        emit(result)
    }

    /** Runs [command]'s wait as [stream] describes, handing each result to [emit]; the caller picks the context. */
    @Suppress("LongMethod") // One wait from admission to its end, the Kafka router's branch included.
    private suspend fun run(model: AggregateModel<*>, command: Command<*>, results: suspend (CommandResult) -> Unit) {
        val emit: suspend (CommandResult) -> Unit = { observed(command, it, results) }
        val function = commandFunction(model, command)
        val context = command.ref.type.context
        val checked = WaitChain.of(command.wait, command.ref.type, directory, options.maxHops, options.unknownContexts)
        val chain = when (checked) {
            is WaitChain.Invalid -> {
                emit(admissionRejected(command, function, ResultCodes.WAIT_INVALID, checked.reason, clock()))
                return
            }

            is WaitChain -> checked
        }
        val timeout = minOf(command.wait.timeout, options.maxTimeout)
        val deadline = clock() + timeout.inWholeMilliseconds
        val waitId = waitIds()
        val waiting = registry.register(waitId, chain.hops, command.id)
        val settled = AtomicBoolean()
        var expiry: ScheduledFuture<*>? = null
        val topic = topicRouter?.takeIf { it.routes(command.ref.type) }
        try {
            if (topic != null) {
                // The Kafka router (D143): ACCEPTED once durable; the owner signals the rest.
                val remaining = chain.hops.dropWhile { it.stage == Stage.ACCEPTED }
                val wait = WaitContext(waitId, options.origin, remaining, deadline).takeIf { remaining.isNotEmpty() }
                sendToTopic(topic, model, command, wait, deadline)?.let { failed ->
                    emit(failed)
                    return
                }
                waiting.offer(reachedResult(command, function, Hop.accepted(context), clock()))
                expiry = expireAt(deadline, waiting, settled)
                for (result in waiting.results) {
                    emit(waiting.carry(result))
                }
                return
            }
            waiting.offer(reachedResult(command, function, Hop.accepted(context), clock()))
            // Once ACCEPTED is reached: a timeout answers at the last hop reached.
            expiry = expireAt(deadline, waiting, settled)
            val wait = WaitContext(waitId, options.origin, chain.hops, deadline)
            settle(model, command, wait, waiting)?.let { settlement ->
                if (withdrawn(settlement)) {
                    // Withdrawn because the deadline passed (review X-12): the wait ends as the deadline ends it,
                    // whichever of the lane's timer and the deadline thread fired first.
                    waiting.expire(clock()) { hop -> timedOut(hop, settled = false) }
                    return@let
                }
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
    }

    /**
     * Ends [waiting] with a timeout at [deadline] (decision D64), from one of [DEADLINES], apart from the waiter:
     * at ACCEPTED, saying the outcome is not known, until the waiter has the command's settlement ([settled]), else at
     * the last hop it reached. Whether or not the wait had ended, its [deadline passes][Waiting.deadlinePassed].
     */
    private fun expireAt(deadline: Long, waiting: Waiting, settled: AtomicBoolean): ScheduledFuture<*> =
        DEADLINES[ThreadLocalRandom.current().nextInt(DEADLINES.size)].schedule(
            {
                waiting.expire(clock()) { hop -> timedOut(hop, settled.get()) }
            },
            (deadline - clock()).coerceAtLeast(0),
            TimeUnit.MILLISECONDS,
        )

    /** The message of a wait that timed out at [hop], once the command's outcome was known ([settled]) or before. */
    private fun timedOut(hop: Hop, settled: Boolean): String = if (settled) {
        "The wait timed out after reaching $hop."
    } else {
        "The wait timed out at $hop, before the command's outcome was known."
    }

    /**
     * Whether [settlement] is a command withdrawn because its wait's deadline passed before it ran (decision D228 ②)
     * or before it was forwarded (review R2.1-9): FAILED with [ResultCodes.WAIT_TIMEOUT] at COMMITTED, nothing
     * committed.
     */
    private fun withdrawn(settlement: Settlement): Boolean =
        settlement.committed.status == CommandResult.Status.FAILED &&
            settlement.committed.error?.code == ResultCodes.WAIT_TIMEOUT

    /**
     * Accepts [command], whose wait plan has only an ACCEPTED hop ([isAcceptedOnly], a chain that is always valid),
     * and returns its result at ACCEPTED at once (decision D100). The command is routed and executed in [scope], apart
     * from the caller, with no wait context, so nothing is signalled about it; its outcome is only logged. Not durable
     * (D15): a node that stops before running it loses it.
     *
     * A command of a type [topicRouter] routes is durable instead (decisions D15, D143): it is answered at ACCEPTED
     * once the broker acknowledged it on its context's command topic, and FAILED at ACCEPTED when the broker did not
     * within its wait's timeout; the owner of its log partition executes it from there.
     */
    suspend fun accept(model: AggregateModel<*>, command: Command<*>, scope: CoroutineScope): CommandResult {
        require(command.wait.isAcceptedOnly) { "Only a plan whose only hop is ACCEPTED is answered at once." }
        val function = commandFunction(model, command)
        val hop = Hop.accepted(command.ref.type.context)
        val topic = topicRouter?.takeIf { it.routes(command.ref.type) }
        if (topic == null) {
            // In the admission's trace (decision D174), which the detached execution continues.
            scope.launch(currentCoroutineContext().traceContext) { runDetached(model, command) }
            return reachedResult(command, function, hop, clock())
        }
        val deadline = clock() + minOf(command.wait.timeout, options.maxTimeout).inWholeMilliseconds
        return sendToTopic(topic, model, command, null, deadline) ?: reachedResult(command, function, hop, clock())
    }

    /**
     * Writes [command] to [topic]'s command topic with [wait] (the Kafka router, decision D143); null once the broker
     * acknowledged it, else its result at ACCEPTED, FAILED: [ResultCodes.UNKNOWN] when the send failed, the command
     * perhaps written all the same, or [ResultCodes.WAIT_TIMEOUT] at [deadline]. Either way a retry with the same
     * request id is safe.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever the producer throws, the command may or may not be written.
    private suspend fun sendToTopic(
        topic: TopicRouter,
        model: AggregateModel<*>,
        command: Command<*>,
        wait: WaitContext?,
        deadline: Long,
    ): CommandResult? {
        val accepted = {
            reachedResult(command, commandFunction(model, command), Hop.accepted(command.ref.type.context), clock())
        }
        val sent = try {
            withTimeoutOrNull((deadline - clock()).coerceAtLeast(0).milliseconds) { topic.send(model, command, wait) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(failure) { "Command ${command.id} on ${command.ref} was not acknowledged on ${topic.topic}." }
            return accepted().failed(
                ResultCodes.UNKNOWN,
                "The command topic did not acknowledge the command; whether it was written is unknown. Retry it " +
                    "with the same request id.",
            )
        }
        return if (sent == null) {
            accepted().failed(
                ResultCodes.WAIT_TIMEOUT,
                "The wait timed out at ACCEPTED, before the command topic ${topic.topic} acknowledged the command.",
            )
        } else {
            null
        }
    }

    @Suppress("TooGenericExceptionCaught") // Nobody waits for a detached command: whatever fails is logged.
    private suspend fun runDetached(model: AggregateModel<*>, command: Command<*>) {
        try {
            val settlement = router.route(model, command, null) { executeHere(model, command, null) }
            val committed = settlement.committed
            when (committed.status) {
                CommandResult.Status.SUCCEEDED -> Unit
                // A business rejection is an outcome, not an incident (review A5-11).
                CommandResult.Status.REJECTED ->
                    logger.debug { "Detached command ${command.id} on ${command.ref} was rejected: ${committed.error}" }

                else -> logger.info {
                    "Detached command ${command.id} on ${command.ref} ended ${committed.status}: ${committed.error}"
                }
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
        val execution: Deferred<Settlement> = executions.async(currentCoroutineContext().traceContext) {
            routeReporting(model, command, wait, taken)
        }
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
     * lane is full or the node has its most commands in flight ([ResultCodes.BUSY], decision D228 ②), or the executor
     * is closed ([ResultCodes.NODE_CLOSING]). So is one that waited in its lane past its wait's deadline and was
     * withdrawn before it ran ([ResultCodes.WAIT_TIMEOUT]: its waiter has gone, and it committed nothing). So is one whose
     * execution a store failed with a transient failure ([StorageUnavailableException], such as the request id's
     * lookup during a restart): [ResultCodes.STORAGE_UNAVAILABLE], which a retry with the same request id may pass, as
     * the kernel deduplicates it. So is a scheduled command due longer from now than the kernel waits for one
     * ([ScheduleNotDueException], decision D198): [ResultCodes.SCHEDULE_NOT_DUE], logged with the clock skew; a timer
     * of this context sends it again later, and the intake of a command another context's timer sent waits for its
     * due time before it runs it, then retries it as any technical failure (review R2.5-1). An append whose outcome is unknown is [ResultCodes.UNKNOWN], from the
     * kernel. Any other failure of its handling (a decide function or an after hook that throws, a missing
     * dependency), which the kernel compensated, is [ResultCodes.FAILED], logged with its cause and answered without
     * it: the owner of a forwarded command answers it so, not with an error the sender could not tell from a lost
     * reply (review A3-6).
     */
    @Suppress("TooGenericExceptionCaught") // Whatever else the handling throws, the command committed nothing.
    suspend fun executeHere(model: AggregateModel<*>, command: Command<*>, wait: WaitContext?): Settlement {
        val function = commandFunction(model, command)
        val hop = Hop.committed(command.ref.type.context)
        val outcome = try {
            executor.execute(model, command, wait)
        } catch (busy: ExecutorBusyException) {
            return refused(command, function, hop, ResultCodes.BUSY, busy.message!!)
        } catch (withdrawn: CommandWithdrawnException) {
            logger.debug { withdrawn.message }
            return refused(command, function, hop, ResultCodes.WAIT_TIMEOUT, withdrawn.message!!)
        } catch (closed: ExecutorClosedException) {
            return refused(command, function, hop, ResultCodes.NODE_CLOSING, closed.message!!)
        } catch (unavailable: StorageUnavailableException) {
            logger.warn(unavailable) { "Command ${command.id} on ${command.ref} failed: a store is unavailable." }
            return refused(command, function, hop, ResultCodes.STORAGE_UNAVAILABLE, unavailable.message!!)
        } catch (early: ScheduleNotDueException) {
            logger.warn { "Clock skew at node ${options.origin.node}: ${early.message}" }
            return Settlement(
                reachedResult(command, function, hop, clock()).failed(ResultCodes.SCHEDULE_NOT_DUE, early.message!!),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.error(failure) { "Command ${command.id} on ${command.ref} failed: its handling threw." }
            return Settlement(
                reachedResult(command, function, hop, clock()).failed(
                    ResultCodes.FAILED,
                    "The command failed with an unexpected error in ${function.processor}.${function.name}; " +
                        "it committed nothing.",
                ),
                cause = failure,
            )
        }
        if (outcome is CommandOutcome.Unknown) {
            logger.warn(outcome.cause) { "Whether command ${command.id} on ${command.ref} committed is unknown." }
        }
        val committed = outcome.toResult(command, function, hop, clock())
        val after = wait?.afterCommitted()?.remaining.orEmpty()
        val reached = when (outcome) {
            is CommandOutcome.Unchanged -> after.firstOrNull()?.let {
                committed.copyWith(
                    hop = it,
                    signalTime = clock()
                )
            }
            is CommandOutcome.AlreadyCommitted -> handledBefore(command, outcome, after.firstOrNull(), after.size == 1)
            else -> null
        }
        return Settlement(committed, listOfNotNull(reached), outcome)
    }

    /** [command] settled FAILED with [code] at [hop] before the kernel took it, and observed so (review A5-11). */
    private fun refused(
        command: Command<*>,
        function: FunctionInfo,
        hop: Hop,
        code: String,
        message: String
    ): Settlement {
        observer.refused(command.ref.type, code)
        return Settlement(reachedResult(command, function, hop, clock()).failed(code, message))
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
        if (wait == null || wait.expiredAt(clock()) || !arrived(command, wait)) {
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
        signal(wait, settlement.committed.copyWith(hop = hop))
        // A command that changed nothing, or a duplicate whose subscription is past it (D63): no entry signals them.
        settlement.reached.forEach { signal(wait, it) }
        return settlement
    }

    /**
     * Executes [command], a synchronous call another context's saga made (decision D193), as [forward] does with the
     * call's [wait] context, and returns its result at COMMITTED, the target's answer to the call (review R2.5-2):
     *
     * - The execution runs in this coordinator's supervised scope and execution context, apart from the caller, as a
     *   waited command's does ([stream]): an HTTP request that is cancelled (its client gave up, its connection
     *   closed) does not cancel the command half-way through its commit, and a stopping node [join]s it. It runs in
     *   the caller's trace (decision D174).
     * - Its plan's timeout bounds it (decision D64): the admission's default wait timeout, capped by the maximum
     *   ([WaitOptions.defaultTimeout], [WaitOptions.maxTimeout]). A command whose outcome is not known by then is
     *   answered FAILED with [ResultCodes.WAIT_TIMEOUT] at ACCEPTED and goes on executing, its outcome logged: the
     *   calling saga retries with the same request id, which the kernel deduplicates (decision D191).
     */
    suspend fun call(model: AggregateModel<*>, command: Command<*>, wait: WaitContext?): CommandResult {
        val timeout = minOf(command.wait.timeout, options.maxTimeout)
        val deadline = CompletableDeferred<Unit>()
        val expiry = DEADLINES[ThreadLocalRandom.current().nextInt(DEADLINES.size)].schedule(
            { deadline.complete(Unit) },
            timeout.inWholeMilliseconds,
            TimeUnit.MILLISECONDS,
        )
        val taken = AtomicBoolean()
        val execution: Deferred<Settlement> = executions.async(currentCoroutineContext().traceContext) {
            forwardReporting(model, command, wait, taken)
        }
        try {
            val settlement = select {
                execution.onAwait { it }
                deadline.onAwait { null }
            } ?: if (taken.compareAndSet(false, true)) null else execution.await()
            return settlement?.committed ?: reachedResult(
                command,
                commandFunction(model, command),
                Hop.accepted(command.ref.type.context),
                clock(),
            ).failed(
                ResultCodes.WAIT_TIMEOUT,
                "The call timed out after $timeout, before the command's outcome was known. Retry it with the same " +
                    "request id.",
            ).also { observer.waitTimedOut(command.ref.type, Stage.ACCEPTED) }
        } catch (cancelled: CancellationException) {
            taken.set(true)
            throw cancelled
        } finally {
            expiry.cancel(false)
        }
    }

    /** [forward]s [command]; logs its outcome when its caller has gone ([taken] already set). */
    @Suppress("TooGenericExceptionCaught") // What the execution throws is the caller's, or logged once it has gone.
    private suspend fun forwardReporting(
        model: AggregateModel<*>,
        command: Command<*>,
        wait: WaitContext?,
        taken: AtomicBoolean,
    ): Settlement {
        val settlement = try {
            forward(model, command, wait)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!taken.compareAndSet(false, true)) {
                logger.warn(failure) { "Call ${command.id} on ${command.ref} failed after its caller had gone." }
            }
            throw failure
        }
        if (!taken.compareAndSet(false, true)) {
            val committed = settlement.committed
            logger.info {
                "Call ${command.id} on ${command.ref} settled ${committed.status} after its caller had gone: " +
                    (committed.error?.toString() ?: "version ${committed.version}")
            }
        }
        return settlement
    }

    /**
     * Checks the hops of [wait] that [command] reaches on this node, of its context (decision D190): the node that
     * started the wait may have let them through without knowing the context, so a wrong one is found only here. A
     * wait whose next hops name another context is not followed (false): it times out at the last hop it reached
     * (decision D64), as does one whose next subscription this context does not have; this logs why.
     */
    private fun arrived(command: Command<*>, wait: WaitContext): Boolean {
        val context = command.ref.type.context
        val leading = wait.remaining.takeWhile { it.stage != Stage.HANDLED }
        leading.firstOrNull { it.context != null && it.context != context }?.let { elsewhere ->
            logger.warn {
                "Wait ${wait.waitId} of ${wait.origin.node} expects hop $elsewhere, but ${command.id} is a command of " +
                    "context $context: the wait is not followed, and times out at the hop it reached last."
            }
            return false
        }
        val handled = wait.remaining.getOrNull(leading.size)
        val subscription = handled?.subscription
        if (subscription != null && handled.context == context && directory.kindOf(context, subscription) == null) {
            logger.warn {
                "Wait ${wait.waitId} of ${wait.origin.node} names hop $handled, a subscription context $context does " +
                    "not have: it is never reached, and the wait times out at the hop it reached last."
            }
        }
        return true
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
            .copyWith(commandId = command.id, duplicate = true)
    }

    /**
     * Signals [result] to the node that waits on [wait], best effort: a failure is logged and the waiter times out.
     * Besides the commands it executes, the receipts of a context's sagas signal their hops through it (decision D192).
     */
    @Suppress("TooGenericExceptionCaught") // A signal is best effort: whatever fails, the command's result stands.
    suspend fun signal(wait: WaitContext, result: CommandResult) {
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
         * Ends the waits of every coordinator of the process at their deadlines: daemon threads that only end them and
         * never run their waiters, which resume in their coordinator's execution context. Sharded (review A5-3): each
         * wait schedules its deadline on one picked at random and cancels it there, so the commands of a busy node
         * do not all take one delay queue's lock twice each.
         */
        val DEADLINES: List<ScheduledExecutorService> =
            List(Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_DEADLINE_SHARDS)) { shard ->
                ScheduledThreadPoolExecutor(1) { task ->
                    Thread(task, "wow-wait-deadlines-$shard").apply { isDaemon = true }
                }.apply { removeOnCancelPolicy = true }
            }

        const val MAX_DEADLINE_SHARDS = 4
    }
}

/** Whether this plan's only hop is ACCEPTED: the caller is answered at admission (decision D100). */
internal val WaitPlan.isAcceptedOnly: Boolean get() = hops.size == 1 && hops.single().stage == Stage.ACCEPTED

/**
 * What executing a command reached at once: its [committed] result (design 08 §7), at its COMMITTED hop, then the
 * later hops it [reached] without waiting for a subscription. The hops after them are signalled to the wait's origin.
 * [outcome] is the kernel's when this node executed the command; null when another node did and replied, or when the
 * executor did not take it.
 */
@InternalWowApi
data class Settlement(
    val committed: CommandResult,
    val reached: List<CommandResult> = emptyList(),
    val outcome: CommandOutcome? = null,
    /** What failed the handling of a [ResultCodes.FAILED] command here: for this node's logs and ledger only. */
    val cause: Throwable? = null,
) {
    /** What made [committed] FAILED, when this node knows it: the handling's [cause], or an unknown append's. */
    val failure: Throwable? get() = cause ?: (outcome as? CommandOutcome.Unknown)?.cause

    /** The rejection [committed] ends with, when it is REJECTED (a business outcome, decision D153). */
    val rejection: Rejection?
        get() = committed.error?.takeIf { committed.status == CommandResult.Status.REJECTED }
            ?.let { Rejection(it.code, it.message, it.extensions) }

    /** Whether [committed] is FAILED: a technical failure, such as contention, an unknown outcome or a busy lane. */
    val failed: Boolean get() = committed.status == CommandResult.Status.FAILED
}

/**
 * The backpressure [command] met when this settlement says the node refused it as busy ([ResultCodes.BUSY], here or
 * on its partition's owner): what the node's own sources throw instead of a technical failure (review R2.1-1).
 */
internal fun Settlement.busyOf(command: Command<*>): CommandBusyException? =
    committed.error?.takeIf { failed && it.code == ResultCodes.BUSY }?.let {
        CommandBusyException(command.ref, command.id, it.message)
    }
