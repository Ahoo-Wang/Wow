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
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.ScheduleNotDueException
import me.ahoo.wow.kernel.UndeclaredEventException
import me.ahoo.wow.runtime.CommandWithdrawnException
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.ExecutorBusyException
import me.ahoo.wow.runtime.ExecutorClosedException
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import java.time.Clock

/**
 * What a node answers for a command it runs itself ([execute]): the kernel's outcome, or the executor's refusal, as the
 * [Settlement] the command's wait goes on with, on the node the command arrived at or on its partition's owner. The
 * [WaitCoordinator] runs its commands here, and the owner of a forwarded one does ([WaitCoordinator.executeHere]).
 */
internal class LocalExecution(
    private val executor: Executor,
    private val directory: SubscriptionDirectory,
    private val observer: Observer,
    private val clock: Clock,
    /** This node, as signals address it: read when a clock skew is logged, since the node may learn it late. */
    private val origin: () -> Origin,
) {
    /**
     * Executes [command] on [model]'s aggregate on this node, with the [wait] context it arrived with, and settles
     * what it reaches at once: its result at COMMITTED, then its next HANDLED hop when that is already reached (a
     * command that changed nothing appended no entry to handle; a duplicate whose subscription is past it).
     * The node a command arrived at runs it so when its router chooses this node, and the owner of its
     * partition runs it so for a forward.
     *
     * A command the executor does not take is settled FAILED at COMMITTED, not thrown: its aggregate's
     * lane is full or the node has its most commands in flight ([ResultCodes.BUSY]), or the executor
     * is closed ([ResultCodes.NODE_CLOSING]). So is one that waited in its lane past its wait's deadline and was
     * withdrawn before it ran ([ResultCodes.WAIT_TIMEOUT]: its waiter has gone, and it committed nothing). So is one whose
     * execution a store failed with a transient failure ([StorageUnavailableException], such as the request id's
     * lookup during a restart): [ResultCodes.STORAGE_UNAVAILABLE], which a retry with the same request id may pass, as
     * the kernel deduplicates it. So is a scheduled command due longer from now than the kernel waits for one
     * ([ScheduleNotDueException]): [ResultCodes.SCHEDULE_NOT_DUE], logged with the clock skew; a timer
     * of this context sends it again later, and the intake of a command another context's timer sent waits for its
     * due time before it runs it, then retries it as any technical failure. An append whose outcome is unknown is [ResultCodes.UNKNOWN], from the
     * kernel. An event the model does not declare for the command ([UndeclaredEventException]) is
     * [ResultCodes.UNDECLARED_EVENT], with the message that names the model, the command and the event. Any other
     * failure of its handling (a decide function or an after hook that throws, a missing
     * dependency), which the kernel compensated, is [ResultCodes.FAILED], logged with its cause and answered without
     * it: the owner of a forwarded command answers it so, not with an error the sender could not tell from a lost
     * reply.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever else the handling throws, the command committed nothing.
    suspend fun execute(model: AggregateModel<*>, command: Command<*>, wait: WaitContext?): Settlement {
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
            return refused(command, function, hop, ResultCodes.STORAGE_UNAVAILABLE, unavailable.message!!, unavailable)
        } catch (early: ScheduleNotDueException) {
            logger.warn { "Clock skew at node ${origin().node}: ${early.message}" }
            return Settlement(
                reachedResult(
                    command,
                    function,
                    hop,
                    clock.millis()
                ).failed(ResultCodes.SCHEDULE_NOT_DUE, early.message!!),
            )
        } catch (undeclared: UndeclaredEventException) {
            return undeclared(command, function, hop, undeclared)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.error(failure) { "Command ${command.id} on ${command.ref} failed: its handling threw." }
            return Settlement(
                reachedResult(command, function, hop, clock.millis()).failed(
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
        val committed = outcome.toResult(command, function, hop, clock.millis())
        val after = wait?.afterCommitted()?.remaining.orEmpty()
        val reached = when (outcome) {
            is CommandOutcome.Unchanged -> after.firstOrNull()?.let {
                committed.copyWith(
                    hop = it,
                    signalTime = clock.millis()
                )
            }
            is CommandOutcome.AlreadyCommitted -> handledBefore(command, outcome, after.firstOrNull(), after.size == 1)
            else -> null
        }
        return Settlement(committed, listOfNotNull(reached), outcome)
    }

    /** [command] settled FAILED as a bug of its model: it produced an event its function does not declare. */
    private fun undeclared(
        command: Command<*>,
        function: FunctionInfo,
        hop: Hop,
        undeclared: UndeclaredEventException,
    ): Settlement {
        logger.error(undeclared) { "Command ${command.id} on ${command.ref} failed: ${undeclared.message}" }
        val failed = reachedResult(command, function, hop, clock.millis())
            .failed(ResultCodes.UNDECLARED_EVENT, undeclared.message!!)
        return Settlement(failed, cause = undeclared)
    }

    /**
     * [command] settled FAILED with [code] at [hop] before the kernel took it, from [cause] when one is known, and
     * observed so.
     */
    private fun refused(
        command: Command<*>,
        function: FunctionInfo,
        hop: Hop,
        code: String,
        message: String,
        cause: Throwable? = null,
    ): Settlement {
        observer.observed(ObservedEvent.Refused(command.ref.type, code))
        return Settlement(reachedResult(command, function, hop, clock.millis()).failed(code, message), cause = cause)
    }

    /**
     * The result of [command], a duplicate of the request committed as [outcome], at its [handled] hop, when that hop
     * is known to be reached: it [endsChain] (a saga's derived commands cannot be found again), it is
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
        return handledResult(outcome.stream, handled, subscriptionFunction(kind, context, subscription), clock.millis())
            .copyWith(commandId = command.id, duplicate = true)
    }

    private companion object {
        // The lines moved from the coordinator keep its logger's name.
        val logger = KotlinLogging.logger(WaitCoordinator::class.java.name)
    }
}
