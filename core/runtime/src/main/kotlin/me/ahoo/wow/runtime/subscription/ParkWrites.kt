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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.ledger.Recoverable
import me.ahoo.wow.kernel.FailureClassification
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.HandlingRejectedException
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.SubscriptionId
import java.time.Clock

/**
 * What a [SubscriptionWorker] writes to the ledger: the park of an aggregate whose handler failed ([park]), and, when a
 * retry of a parked aggregate ends, where the aggregate is parked now and why, when it did not handle all of it, else
 * its resolution. A park and a repark wait for the partition to run again ([PartitionPause]) and are retried until they
 * are stored.
 *
 * Each is fenced by the partition the retry read the entry in ([FailureLedger.repark], [FailureLedger.resolve]): an
 * entry re-homed meanwhile to the partition its aggregate's key maps to now is no longer this retry's, and nothing of
 * it is written over nor resolved.
 */
internal class ParkWrites(
    private val subscription: SubscriptionId,
    private val partition: Int,
    private val source: EntrySource,
    private val ledger: FailureLedger,
    private val pause: PartitionPause,
    private val readRetry: RetryPolicy,
    private val clock: Clock,
    /** How the handler's failures are classified: an unrecoverable one parks marked so. */
    private val classification: FailureClassification,
    /** The handler's own retry budget, which its classification reads. */
    private val handlerRetry: HandlerRetry?,
    /** The budget the handler was retried by before it parked, which the park's warning names. */
    private val retry: RetryPolicy,
    private val backpressure: BackpressureOptions,
) {
    /**
     * Parks [sourced]'s aggregate at its version, and at its position in a topic, after [failure], once the partition
     * runs again ([PartitionPause]), retried until it is stored. Returns the entry, and whether the ledger changed
     * ([FailureLedger.park]): the worker then holds the aggregate back and tells of the parking.
     */
    suspend fun park(sourced: SourcedEntry, failure: Throwable): Pair<ParkedAggregate, Boolean> {
        val stream = sourced.stream
        val kind = if (failure is HandlingRejectedException) {
            null
        } else {
            classification.classify(
                failure,
                handlerRetry
            )
        }
        val unrecoverable = kind == FailureClass.UNRECOVERABLE
        val entry = ParkedAggregate(
            subscription,
            stream.ref,
            partition,
            stream.version,
            reasonOf(failure),
            clock.millis(),
            source.sourcePosition(sourced),
            // As 9.x records its RecoverableType: an unrecoverable failure is not retried automatically either.
            recoverable = if (unrecoverable) Recoverable.UNRECOVERABLE else Recoverable.UNKNOWN,
        )
        workerLogger.warn(failure) {
            val after = when {
                failure is HandlingRejectedException -> "a rejection"
                unrecoverable -> "an unrecoverable failure, which it does not retry automatically"
                kind == FailureClass.BACKPRESSURE ->
                    "${backpressure.maxAttempts} attempts its downstream pushed back"
                else -> "${retry.maxAttempts} attempts"
            }
            "Subscription ${subscription.name} parks ${stream.ref} from version ${stream.version} after $after."
        }
        // The ledger may sit on what paused the partition: it is written once the partition runs again.
        pause.awaitRunning()
        val changed = retryForever(readRetry, { "park ${stream.ref} for ${subscription.name}" }) {
            ledger.park(entry)
        }
        return entry to changed
    }

    /**
     * Records in the ledger that a retry failed again at [sourced], from where the aggregate is now parked, while it
     * is parked in partition [from], the one the retry read it in; returns the entry.
     */
    suspend fun repark(sourced: SourcedEntry, failure: Throwable, from: Int): ParkedAggregate {
        val stream = sourced.stream
        logger.warn(failure) {
            "Subscription $subscription keeps ${stream.ref} parked, now from version ${stream.version}."
        }
        return ParkedAggregate(
            subscription,
            stream.ref,
            partition,
            stream.version,
            reasonOf(failure),
            clock.millis(),
            source.sourcePosition(sourced),
        ).also { repark(it, from) }
    }

    /**
     * Records in the ledger that [parked]'s aggregate can no longer be retried, only skipped: the source does not
     * hold what it is parked from any more ([gone]).
     */
    suspend fun unrecoverable(parked: ParkedAggregate, gone: UnrecoverableException) {
        logger.error {
            "Subscription $subscription cannot retry ${parked.ref}: ${gone.message} It can only be skipped."
        }
        val skipOnly = parked.copyWith(reason = gone.message.orEmpty(), failedAt = clock.millis(), skipOnly = true)
        repark(skipOnly, parked.partition)
    }

    /**
     * Resolves [ref]'s entry after a retry handled every parked stream it read in [partition]; false when the entry
     * was re-homed to another partition meanwhile, its entries there never handled by this retry. An entry resolved
     * meanwhile, by a skip, is resolved: true.
     */
    suspend fun resolve(ref: AggregateRef, partition: Int): Boolean =
        ledger.resolve(subscription, ref, partition) != null || ledger.parked(subscription, ref) == null

    private suspend fun repark(entry: ParkedAggregate, from: Int) {
        pause.awaitRunning()
        retryForever(readRetry, { "repark ${entry.ref} for $subscription" }) { ledger.repark(entry, from) }
    }

    private companion object {
        /** Named as before the rename, so that the lines a retry writes keep their log category. */
        private val logger = KotlinLogging.logger("me.ahoo.wow.runtime.subscription.RetryWrites")
    }
}

/** The reason a parked entry records for [failure]: its class and its message. */
internal fun reasonOf(failure: Throwable): String = "${failure.javaClass.name}: ${failure.message}"

/** The worker's: the park's warning is logged as it was. */
private val workerLogger = KotlinLogging.logger(SubscriptionWorker::class.java.name)
