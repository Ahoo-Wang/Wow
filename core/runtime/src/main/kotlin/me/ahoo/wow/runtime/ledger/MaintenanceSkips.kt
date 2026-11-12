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

package me.ahoo.wow.runtime.ledger

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.kernel.FailureClassification
import me.ahoo.wow.runtime.health.NodeFatals
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedEvent.MaintenanceSkipped.Work
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.SubscriptionId
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/** The failure ledger of a log group, whose log has [partitions] partitions: where its types' skips are parked. */
@InternalWowApi
class SkipLedger(val ledger: FailureLedger, val partitions: Int)

/**
 * Where one bounded context's background [work] on its aggregates' histories, its access walks or its snapshot
 * rewrites, records an aggregate it cannot read, so it goes on with the others instead of failing at that aggregate
 * on every run: in the failure ledger of the aggregate's log group ([ledgers]), under the subscription [subscription]
 * (`wow.access.walk`, `wow.snapshot.rewrite`). No worker handles it and nothing retries it automatically: a history
 * that does not replay does not replay again without a new deployment, so an entry waits for an operator, who retries
 * or skips it as any parked entry ([me.ahoo.wow.runtime.node.ParkedOps]), on any node.
 *
 * What is skipped, by the phase that failed ([attempt]):
 * - **The replay** of the aggregate's history ([replaying]): computation over its own data alone, with no store, so
 *   it fails again whenever it runs: an event the kernel cannot read, a sourcing function that throws. Skipped at once.
 * - **A read or a write**: only a failure the node's [classification] calls [FailureClass.UNRECOVERABLE], one no retry
 *   changes. Any other, a store that is down or busy, a timeout, a failure nothing classifies, stops the work, which
 *   resumes where its cursor is on its next run: a store's outage never becomes a run of skips.
 *
 * An aggregate is parked once: the warning, the observer's [ObservedEvent.MaintenanceSkipped] and its count come with
 * the ledger's change alone. A later success on the same aggregate, a rebuild at a new revision or a walk of a new
 * schema, resolves its entry ([attempt]). Without a ledger for the aggregate's type, nothing is skipped: the failure
 * stops the work, as it would with no ledger at all.
 */
@InternalWowApi
class MaintenanceSkips(
    val context: String,
    val work: Work,
    /** The ledger of each type's log group; none for a type whose failures stop the work. */
    private val ledgers: (AggregateType) -> SkipLedger?,
    private val classification: FailureClassification = FailureClassification.DEFAULT,
    private val observer: Observer = Observer.NONE,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** What the ledger parks the skipped aggregates under: [ACCESS_WALK] or [SNAPSHOT_REWRITE]. */
    val subscription: SubscriptionId = SubscriptionId(context, subscriptionOf(work))

    /** The aggregates parked under [subscription] in each ledger, as last listed; read again after [refresh]. */
    private val parked = ConcurrentHashMap<FailureLedger, Parked>()

    /** Forgets which aggregates are parked, so the next success lists them anew: a run of the work calls it first. */
    fun refresh() = parked.clear()

    /**
     * Runs [work] on [ref]: true when it succeeded, after which an entry parking [ref] is resolved; false when it
     * failed in a way that skips the aggregate ([skippable]), which is then parked; any other failure is thrown, and
     * the work stops there. A failure is not skipped without its entry: one the ledger does not take stops the work.
     */
    suspend fun attempt(ref: AggregateRef, work: suspend () -> Unit): Boolean {
        try {
            work()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (
            @Suppress("TooGenericExceptionCaught") // Only a failure of the aggregate's own data is skipped.
            failure: Exception,
        ) {
            val target = ledgers(ref.type)
            if (target == null || !skippable(failure)) {
                throw failure
            }
            park(target, ref, reasonOf(failure), failure)
            return false
        }
        ledgers(ref.type)?.let { target ->
            if (parkedIn(target.ledger).has(ref)) {
                target.ledger.resolveAnywhere(subscription, ref)
            }
        }
        return true
    }

    /**
     * An operator's retry of [ref]: runs [work], which answers whether it did all of it here; true when it did, and
     * the entry is resolved. False when it did not (a snapshot left for the node of its table's revision, whose
     * rewrite resolves the entry), or failed again in a way that skips the aggregate: its entry stays as it was
     * parked. Any other failure is thrown.
     */
    @Suppress("TooGenericExceptionCaught") // Only a failure of the aggregate's own data leaves the entry as it is.
    suspend fun retry(ref: AggregateRef, work: suspend () -> Boolean): Boolean {
        val done = try {
            work()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!skippable(failure)) {
                throw failure
            }
            logger.warn(failure) { "The retry of $ref under ${subscription.name} failed again; it stays parked." }
            false
        }
        if (done) {
            ledgers(ref.type)?.ledger?.resolveAnywhere(subscription, ref)
        }
        return done
    }

    /**
     * Parks [ref], whose log holds no stream of it while the work has something of it to rewrite (a snapshot the log
     * has nothing of): true when it is parked, false without a ledger for its type.
     */
    suspend fun absent(ref: AggregateRef): Boolean {
        val target = ledgers(ref.type) ?: return false
        park(target, ref, "The log holds no stream of it.", null)
        return true
    }

    /** An operator's skip of [ref]: its entry is resolved, and what the work could not do stays undone. */
    suspend fun skip(ref: AggregateRef): Boolean {
        ledgers(ref.type)?.ledger?.resolveAnywhere(subscription, ref)
        return true
    }

    /** Whether [failure] skips its aggregate: its history did not replay, or the node classifies it unrecoverable. */
    fun skippable(failure: Throwable): Boolean =
        failure is UnreplayableHistoryException ||
            classification.classify(failure, null) == FailureClass.UNRECOVERABLE

    private suspend fun park(target: SkipLedger, ref: AggregateRef, reason: String, failure: Exception?) {
        val partition = Partitioner.partitionOf(ref, target.partitions)
        val entry = ParkedAggregate(subscription, ref, partition, 1, reason, clock.millis())
        if (!target.ledger.park(entry)) {
            return
        }
        parked[target.ledger]?.add(ref)
        logger.warn(failure) {
            "The ${work.value} of ${ref.type} skipped $ref ($reason); it is parked under ${subscription.name} " +
                "for an operator to retry or skip."
        }
        observer.observed(ObservedEvent.MaintenanceSkipped(ref.type, work))
    }

    /** The aggregates [ledger] parks under [subscription], listed once a run and kept as this node parks more. */
    private suspend fun parkedIn(ledger: FailureLedger): Parked {
        parked[ledger]?.let { return it }
        val refs = ConcurrentHashMap.newKeySet<AggregateRef>()
        var after: AggregateRef? = null
        var all = true
        do {
            val page = ledger.list(subscription, after)
            page.mapTo(refs) { it.ref }
            after = page.lastOrNull()?.ref
            if (refs.size >= MAX_LISTED && page.size == FailureLedger.DEFAULT_PAGE) {
                all = false
                break
            }
        } while (page.size == FailureLedger.DEFAULT_PAGE)
        return Parked(refs, all).also { parked[ledger] = it }
    }

    /**
     * The aggregates parked in a ledger, [all] of them, or the first of more than [MAX_LISTED]: then every success
     * resolves its aggregate's entry, which a ledger without one answers with nothing.
     */
    private class Parked(private val refs: MutableSet<AggregateRef>, private val all: Boolean) {
        fun has(ref: AggregateRef): Boolean = !all || ref in refs

        fun add(ref: AggregateRef) {
            refs += ref
        }
    }

    override fun toString(): String = "MaintenanceSkips($context, ${work.value})"

    @InternalWowApi
    companion object {
        /** What the access walks park the aggregates they skip under. */
        const val ACCESS_WALK: String = "wow.access.walk"

        /** What the snapshot rewrites park the aggregates they skip under. */
        const val SNAPSHOT_REWRITE: String = "wow.snapshot.rewrite"

        /** The most entries of a ledger listed to tell which successes resolve one. */
        private const val MAX_LISTED = 10_000

        /** The longest reason an entry keeps. */
        private const val MAX_REASON = 1_000

        fun subscriptionOf(work: Work): String = when (work) {
            Work.ACCESS_WALK -> ACCESS_WALK
            Work.SNAPSHOT_REWRITE -> SNAPSHOT_REWRITE
        }

        private fun reasonOf(failure: Exception): String {
            val cause = (failure as? UnreplayableHistoryException)?.cause ?: failure
            val phase = if (failure is UnreplayableHistoryException) "Its history did not replay" else "It failed"
            return "$phase: ${cause.javaClass.name}: ${cause.message}".take(MAX_REASON)
        }

        private val logger = KotlinLogging.logger {}
    }
}

/** The history of [ref] did not replay: the failure of its own data, which [MaintenanceSkips] skips at once. */
@InternalWowApi
class UnreplayableHistoryException(val ref: AggregateRef, cause: Throwable) :
    RuntimeException("The history of $ref did not replay: ${cause.message}", cause)

/**
 * Runs [replay], a replay of [ref]'s history with no store, any failure of it an [UnreplayableHistoryException], an
 * `Error` of the application's code too (a `NotImplementedError` in its `evolve`, a stack overflow on this history), as
 * a subscription's handler takes it: the aggregate is parked, and the walk or the rewrite goes on. But a cancellation,
 * the node stopping mid-replay, which is not the aggregate's data, and a fatal error ([NodeFatals.isFatal]), which is
 * the JVM's, are thrown as they are, so the work stops and parks nothing.
 */
@InternalWowApi
@Suppress("TooGenericExceptionCaught") // Whatever else a replay throws, it is the aggregate's data that failed.
inline fun <T> replaying(ref: AggregateRef, replay: () -> T): T = try {
    replay()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    if (NodeFatals.isFatal(failure)) {
        throw failure
    }
    throw UnreplayableHistoryException(ref, failure)
}
