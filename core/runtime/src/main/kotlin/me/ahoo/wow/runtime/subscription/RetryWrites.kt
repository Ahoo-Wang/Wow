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
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.subscription.SubscriptionId
import java.time.Clock

/**
 * What a [SubscriptionWorker]'s retry of a parked aggregate writes to the ledger when it ends: where the aggregate is
 * parked now and why, when it did not handle all of it, else its resolution. A repark waits for the partition to run
 * again ([PartitionPause]) and is retried until it is stored.
 *
 * Each is fenced by the partition the retry read the entry in ([FailureLedger.repark], [FailureLedger.resolve]): an
 * entry re-homed meanwhile to the partition its aggregate's key maps to now is no longer this retry's, and nothing of
 * it is written over nor resolved.
 */
internal class RetryWrites(
    private val subscription: SubscriptionId,
    private val partition: Int,
    private val source: EntrySource,
    private val ledger: FailureLedger,
    private val pause: PartitionPause,
    private val readRetry: RetryPolicy,
    private val clock: Clock,
) {
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
        val reason = "${ParkedAggregate.UNRECOVERABLE}: ${gone.message}"
        repark(parked.copyWith(reason = reason, failedAt = clock.millis()), parked.partition)
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
        private val logger = KotlinLogging.logger {}
    }
}

/** The reason a parked entry records for [failure]: its class and its message. */
internal fun reasonOf(failure: Throwable): String = "${failure.javaClass.name}: ${failure.message}"
