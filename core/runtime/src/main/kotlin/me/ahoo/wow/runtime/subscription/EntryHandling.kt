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

package me.ahoo.wow.runtime.subscription

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.kernel.FailureClassification
import me.ahoo.wow.kernel.HandlerTimeoutException
import me.ahoo.wow.kernel.HandlingRejectedException
import me.ahoo.wow.runtime.CommandBusyException
import me.ahoo.wow.runtime.health.NodeFatals
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.observe
import me.ahoo.wow.spi.subscription.FailureClass
import kotlin.random.Random

/**
 * How a [SubscriptionWorker] handles one entry: its [subscription]'s handler attempted within its budget ([retry]), each
 * failure classified ([classification]), behind the partition's systemic [pause] and backpressure [throttle], which
 * the worker owns and hands over, and observed by [observer]. The worker runs it in the entry's lane; it holds no lock
 * and no state of its own.
 */
internal class EntryHandling(
    private val group: String,
    private val subscription: Subscription,
    private val partition: Int,
    private val source: EntrySource,
    private val options: SubscriptionOptions,
    /** How the handler is retried before its aggregate is parked: its subscription's budget. */
    private val retry: RetryPolicy,
    private val classification: FailureClassification,
    private val pause: PartitionPause,
    private val throttle: PartitionThrottle,
    private val observer: Observer,
    /** The jitter of the backpressure backoff. */
    private val random: Random,
    /** The sequence up to which the partition of the log holds imported history; 0 for none, and for a topic. */
    private val importedThrough: Long,
) {
    /**
     * Runs the handler with its subscription's retries ([retry]); returns what it did, or the last failure once the
     * retries are spent. An attempt that outlasts the budget's timeout is cancelled and fails with a
     * [HandlerTimeoutException]. A [HandlingRejectedException] is not retried: a business outcome does not change.
     */
    suspend fun handle(sourced: SourcedEntry): Result<HandledEntry> {
        val stream = sourced.stream
        val trace = sourced.transport?.trace.orEmpty()
        val observed = observer.handling(group, subscription.name, subscription.context, stream, trace, source.topic)
        return observed.observe {
            retried(sourced).also { handled -> handled.exceptionOrNull()?.let { observed.failed(it) } }
        }
    }

    /**
     * A handler may fail with anything: a [CancellationException] that is not this lane's own, such as
     * the handler's own `withTimeout` firing, or an [Error] such as [NotImplementedError] or [StackOverflowError], is a
     * failure like any other, retried and then parked, so the lane goes on and the checkpoint moves. Only the
     * cancellation of the lane itself stops the retries, and a fatal error ([NodeFatals.isFatal]: out of memory, say)
     * propagates at once, to stop the lane ([drain]).
     *
     * Each failure is classified ([classification]): an unrecoverable one returns at once, so
     * the aggregate parks without a retry; a recoverable one is retried within the budget; a systemic one, or any
     * failure while the partition is paused, is the partition's ([pause]): the entry is tried again once the pause lets
     * it, spending no attempt.
     *
     * A downstream that pushed back ([FailureClass.BACKPRESSURE]: a command refused as busy, a wait
     * that ran out, a busy query store, a 429 with `Retry-After`) is not a failure of the aggregate:
     * the entry is tried again after the backpressure backoff with jitter, at least the `Retry-After`, without
     * spending an attempt of the handler's budget, and the partition is throttled ([throttle]) until attempts succeed
     * again; once [BackpressureOptions.maxAttempts] are spent, the aggregate parks as after any spent budget. It never
     * pauses the partition, which would only slow the overloaded downstream's recovery further.
     */
    @Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod", "LongMethod")
    private suspend fun retried(sourced: SourcedEntry): Result<HandledEntry> {
        val ref = sourced.stream.ref
        var failures = 0
        var pushedBack = 0
        while (true) {
            val probing = pause.admit()
            throttle.acquire()
            val failure = try {
                val handled = attempt(sourced)
                succeeded(ref)
                return Result.success(handled)
            } catch (rejected: HandlingRejectedException) {
                // The handler reached a decision: what it depends on answers.
                succeeded(ref)
                return Result.failure(rejected)
            } catch (failure: Throwable) {
                if (NodeFatals.isFatal(failure)) {
                    // Never retried: the lane stops ([drain]).
                    throw failure
                }
                // The lane's own cancellation propagates; any other is the handler's failure.
                currentCoroutineContext().ensureActive()
                failure
            } finally {
                throttle.release()
                pause.release(probing)
            }
            val kind = classification.classify(failure, subscription.retry)
            if (pause.failed(ref, kind, failure, probing)) {
                continue
            }
            if (kind == FailureClass.BACKPRESSURE) {
                // Never the partition's pause: this entry backs off, the partition slows down.
                pushedBack++
                throttle.backpressure(ref)
                observer.observed(ObservedEvent.Backpressured(group, subscription.name, partition, codeOf(failure)))
                if (pushedBack >= options.backpressure.maxAttempts) {
                    return Result.failure(failure)
                }
                val retryAfter = FailureClassification.retryAfterOf(failure)
                logger.debug { "Subscription ${subscription.name} met backpressure at $ref: ${failure.message}" }
                delay(options.backpressure.backoff(pushedBack, retryAfter, random.nextDouble()))
                continue
            }
            failures++
            if (kind == FailureClass.UNRECOVERABLE || failures >= retry.maxAttempts) {
                return Result.failure(failure)
            }
            delay(retry.backoff(failures))
        }
    }

    private fun succeeded(ref: AggregateRef) {
        pause.succeeded(ref)
        throttle.succeeded()
    }

    /** The code a backpressure [failure] names: the downstream's error code, its own or a cause's, else its class's full name. */
    private fun codeOf(failure: Throwable): String =
        generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_CAUSES).firstNotNullOfOrNull {
            when (it) {
                is CommandBusyException -> ResultCodes.BUSY
                is WowProblemException -> it.code
                else -> null
            }
        } ?: failure.javaClass.name

    /** One attempt of the handler, within the budget's timeout when it has one. */
    private suspend fun attempt(sourced: SourcedEntry): HandledEntry {
        if (retry.timeout.isInfinite()) {
            return decodeAndHandle(sourced)
        }
        return withTimeoutOrNull(retry.timeout) { decodeAndHandle(sourced) }
            ?: throw HandlerTimeoutException(subscription.name, sourced.stream.ref, sourced.stream.version, retry.timeout)
    }

    private suspend fun decodeAndHandle(sourced: SourcedEntry): HandledEntry {
        (sourced.message as? UndecodableMessage)?.let { throw UndecodableEntryException(it.reason) }
        val decoded = subscription.decoder.decode(sourced.stream, sourced.position, sourced.transport)
        val imported = sourced.published?.imported ?: (sourced.position.sequence <= importedThrough)
        val entry = if (sourced.published == null && sourced.message == null && !imported) {
            decoded
        } else {
            decoded.copy(published = sourced.published, message = sourced.message, imported = imported)
        }
        return subscription.handle(entry)
    }
}

/** The worker's: its handlings are logged as they were. */
private val logger = KotlinLogging.logger(SubscriptionWorker::class.java.name)

/** The causes [EntryHandling.codeOf] looks through at most. */
private const val MAX_CAUSES = 16
