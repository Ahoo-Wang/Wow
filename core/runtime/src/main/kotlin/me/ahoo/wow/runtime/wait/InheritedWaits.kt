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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.selects.select
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.runtime.cluster.CommandRouter
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.traceContext
import me.ahoo.wow.spi.wait.WaitContext
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The commands that inherit a wait instead of starting one: a saga's derived commands, a timer's, an intake's, and
 * another context's synchronous call ([forward], [call]), with the [signal]s they send to the node that waits. The
 * [WaitCoordinator] runs them through its delegates.
 *
 * A call's execution runs in [executions], the coordinator's supervised scope, which this type only launches in: the
 * coordinator joins, counts and cancels it ([WaitCoordinator.join], [WaitCoordinator.executing],
 * [WaitCoordinator.close]).
 */
internal class InheritedWaits(
    private val router: CommandRouter,
    private val local: LocalExecution,
    private val signals: SignalSender,
    private val directory: SubscriptionDirectory,
    private val deadlines: WaitDeadlines,
    private val options: WaitOptions,
    private val clock: Clock,
    private val observer: Observer,
    private val executions: CoroutineScope,
) {
    /**
     * Executes [command], derived on the way of a wait (by a saga, or later sent from another context), or sent by a
     * timer, on [model]'s aggregate with the [wait] context it inherited, and signals its results to the waiting node:
     * ACCEPTED when the context waits for it, then COMMITTED or the failure, then the later hop it reached at once.
     * Without a context, or past its deadline, it only executes.
     *
     * It is routed as an admitted command is ([router]): to the owner of its
     * partition, degrading to this node, so the owner's state cache sees every commit of its partitions. A saga's
     * subscription runs on the owner of the partition of the entry it handles, which is not the derived command's
     * partition in general.
     *
     * Returns the [Settlement]: its [Settlement.outcome] is the kernel's when this node executed it.
     */
    suspend fun forward(model: AggregateModel<*>, command: Command<*>, wait: WaitContext?): Settlement {
        if (wait == null || wait.expiredAt(clock.millis()) || !arrived(command, wait)) {
            return router.route(model, command, null) { local.execute(model, command, null) }
        }
        val function = commandFunction(model, command)
        val context = command.ref.type.context
        val leading = wait.remaining.takeWhile { it.stage != Stage.HANDLED }
        leading.firstOrNull { it.stage == Stage.ACCEPTED }?.let { accepted ->
            signal(wait, reachedResult(command, function, accepted, clock.millis()))
        }
        val settlement = router.route(model, command, wait) { local.execute(model, command, wait) }
        val hop = leading.firstOrNull { it.stage == Stage.COMMITTED } ?: Hop.committed(context)
        signal(wait, settlement.committed.copyWith(hop = hop))
        // A command that changed nothing, or a duplicate whose subscription is past it: no entry signals them.
        settlement.reached.forEach { signal(wait, it) }
        return settlement
    }

    /**
     * Executes [command], a synchronous call another context's saga made, as [forward] does with the
     * call's [wait] context, and returns its result at COMMITTED, the target's answer to the call:
     *
     * - The execution runs in this coordinator's supervised scope and execution context, apart from the caller, as a
     *   waited command's does ([WaitCoordinator.stream]): an HTTP request that is cancelled (its client gave up, its
     *   connection closed) does not cancel the command half-way through its commit, and a stopping node joins it
     *   ([WaitCoordinator.join]). It runs in the caller's trace.
     * - Its plan's timeout bounds it: the admission's default wait timeout, at most the maximum
     *   ([WaitOptions.defaultTimeout], [WaitOptions.maxTimeout]). A command whose outcome is not known by then is
     *   answered FAILED with [ResultCodes.WAIT_TIMEOUT] at ACCEPTED and goes on executing, its outcome logged: the
     *   calling saga retries with the same request id, which the kernel deduplicates.
     */
    suspend fun call(model: AggregateModel<*>, command: Command<*>, wait: WaitContext?): CommandResult {
        val timeout = minOf(command.wait.timeout, options.maxTimeout)
        val deadline = CompletableDeferred<Unit>()
        val expiry = deadlines.schedule(timeout.inWholeMilliseconds) { deadline.complete(Unit) }
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
                clock.millis(),
            ).failed(
                ResultCodes.WAIT_TIMEOUT,
                "The call timed out after $timeout, before the command's outcome was known. Retry it with the same " +
                    "request id.",
            ).also { observer.observed(ObservedEvent.WaitTimedOut(command.ref.type, Stage.ACCEPTED)) }
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
     * Checks the hops of [wait] that [command] reaches on this node, of its context: the node that
     * started the wait may have let them through without knowing the context, so a wrong one is found only here. A
     * wait whose next hops name another context is not followed (false): it times out at the last hop it reached,
     * as does one whose next subscription this context does not have; this logs why.
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
     * Signals [result] to the node that waits on [wait], best effort: a failure is logged and the waiter times out.
     * Besides the commands it executes, the receipts of a context's sagas signal their hops through it.
     */
    @Suppress("TooGenericExceptionCaught") // A signal is best effort: whatever fails, the command's result stands.
    suspend fun signal(wait: WaitContext, result: CommandResult) {
        try {
            signals.send(wait.origin, WaitSignal(waitId = wait.waitId, result = result), wait.deadline)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(failure) { "Failed to signal wait ${wait.waitId} at ${result.hop}; the waiter will time out." }
        }
    }

    private companion object {
        // The lines moved from the coordinator keep its logger's name.
        val logger = KotlinLogging.logger(WaitCoordinator::class.java.name)
    }
}
