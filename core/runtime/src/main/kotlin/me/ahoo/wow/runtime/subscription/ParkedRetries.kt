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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.LedgerRequest
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedAmendment
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * The long-horizon retries of one [SubscriptionRuntime]'s parked entries (9.x `CompensationScheduler`),
 * run by the node that owns each entry's partition, where the entry's aggregate lane is.
 *
 * Each [scan] reads, for each of the runtime's subscriptions, only the entries due in the partitions it runs here
 * ([FailureLedger.due]): those the ledger holds no next attempt for, and those whose next attempt has come. For each:
 *
 * - an operator's request is taken from the ledger ([ParkedAmendment.TakeRequest]) and run: a skip,
 *   or a resume, which runs whatever the retry spec says and counts no retry;
 * - otherwise, once its next retry is due ([ParkedAggregate.nextRetryAt]), the retry is claimed in the ledger
 *   ([ParkedAmendment.ClaimRetry]) and the aggregate resumed. A resume that fails again leaves the entry parked from
 *   where it failed; when that was the last retry the spec allows, the entry is exhausted, and the notifier hears it;
 * - an entry whose retry is not due yet, or that is not retried automatically, is scheduled
 *   ([ParkedAmendment.Schedule]): its next retry, or [ParkedAggregate.NEVER], so the next scans pass it over until
 *   then, or until it is claimed or an operator changes it.
 *
 * Claims and takes are conditional on what was read, so when an owner hands a partition off while it retries, the
 * next owner neither claims the same retry again nor runs a request twice; it starts the next retry once its backoff
 * and the last one's execution timeout have passed. A retry the stop cancelled was claimed but did not resolve the
 * entry, which stays parked: a cancelled retry counts. One entry is run at most once at a time on a node.
 */
internal class ParkedRetries(
    private val group: String,
    private val ledger: FailureLedger,
    private val options: ParkedRetryOptions,
    private val clock: Clock,
    private val notifier: ParkedNotifier,
) {
    private val running: MutableSet<Pair<String, AggregateRef>> = ConcurrentHashMap.newKeySet()

    /**
     * Reads [subscriptions]' entries due in [partitions] and, in [scope], runs those due here: each one of a partition
     * whose worker [workerOf] finds; schedules the others.
     */
    suspend fun scan(
        scope: CoroutineScope,
        subscriptions: Collection<String>,
        partitions: Set<Int>,
        workerOf: suspend (subscription: String, ref: AggregateRef) -> SubscriptionWorker?,
    ) {
        if (partitions.isEmpty()) {
            return
        }
        for (subscription in subscriptions) {
            val now = clock.millis()
            var after: AggregateRef? = null
            do {
                val page = ledger.due(subscription, partitions, now, after)
                for (entry in page) {
                    look(scope, entry, now, workerOf)
                }
                after = page.lastOrNull()?.ref
            } while (page.size == FailureLedger.DEFAULT_PAGE)
        }
    }

    /** Runs [entry] when an operator asked for it or its retry is due at [now]; schedules it otherwise. */
    private suspend fun look(
        scope: CoroutineScope,
        entry: ParkedAggregate,
        now: Long,
        workerOf: suspend (subscription: String, ref: AggregateRef) -> SubscriptionWorker?,
    ) {
        val next = entry.nextRetryAt(options.specOf(entry))
        if (entry.request != null || next != null && now >= next) {
            workerOf(entry.subscription, entry.ref)?.let { dispatch(scope, it, entry) }
            return
        }
        if (entry.subscription to entry.ref in running) {
            // A retry of it runs: the ledger schedules it once that retry has claimed its own.
            return
        }
        val at = next ?: ParkedAggregate.NEVER
        if (entry.nextAttemptAt != at) {
            ledger.amend(
                entry.subscription,
                entry.ref,
                ParkedAmendment.Schedule(entry.retries, entry.nextAttemptAt, at)
            )
        }
    }

    private fun dispatch(scope: CoroutineScope, worker: SubscriptionWorker, entry: ParkedAggregate) {
        val key = entry.subscription to entry.ref
        if (!running.add(key)) {
            return
        }
        scope.launch {
            try {
                run(worker, entry)
            } finally {
                running.remove(key)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // A retry that fails to run is tried again on a later scan.
    private suspend fun run(worker: SubscriptionWorker, entry: ParkedAggregate) {
        try {
            when (entry.request) {
                LedgerRequest.SKIP -> skip(worker, entry)
                LedgerRequest.RESUME -> resume(worker, entry)
                null -> retry(worker, entry)
            }
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                throw cancelled
            }
            // The partition was handed off or stopped while it ran: the entry stays parked for its next owner.
            logger.info { "The retry of ${entry.ref} by ${entry.subscription} was abandoned: ${cancelled.message}" }
        } catch (failure: Exception) {
            logger.warn(failure) { "Failed to retry ${entry.ref} parked by ${entry.subscription}; trying again later." }
        }
    }

    private suspend fun skip(worker: SubscriptionWorker, entry: ParkedAggregate) {
        if (take(entry, LedgerRequest.SKIP) && !worker.skip(entry.ref)) {
            // A resume of it runs: the skip waits for the next scan.
            ledger.amend(entry.subscription, entry.ref, ParkedAmendment.Request(LedgerRequest.SKIP))
        }
    }

    private suspend fun resume(worker: SubscriptionWorker, entry: ParkedAggregate) {
        if (take(entry, LedgerRequest.RESUME)) {
            logger.info { "Subscription ${entry.subscription} resumes ${entry.ref} as an operator asked." }
            worker.resume(entry.ref)
        }
    }

    private suspend fun take(entry: ParkedAggregate, request: LedgerRequest): Boolean =
        ledger.amend(entry.subscription, entry.ref, ParkedAmendment.TakeRequest(request)) != null

    private suspend fun retry(worker: SubscriptionWorker, entry: ParkedAggregate) {
        val spec = options.specOf(entry)
        val now = clock.millis()
        val claimed = ledger.amend(entry.subscription, entry.ref, ParkedAmendment.ClaimRetry(entry.retries, now))
            ?: return
        logger.info {
            "Subscription ${entry.subscription} retries ${entry.ref}: retry ${claimed.retries} of ${spec.maxRetries}."
        }
        if (worker.resume(entry.ref) || !claimed.exhausted(spec)) {
            return
        }
        val exhausted = ledger.parked(entry.subscription, entry.ref) ?: return
        logger.warn {
            "Subscription ${entry.subscription} spent the ${spec.maxRetries} retries of ${entry.ref}: it waits for " +
                "an operator."
        }
        notifier.notifyQuietly(ParkedNotice(ParkedNotice.Kind.EXHAUSTED, group, exhausted, spec, clock.millis()))
    }
}

private val logger = KotlinLogging.logger {}
