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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.yield

/**
 * The leader and follower writes of a subscription's lanes, all partitions together
 * ([me.ahoo.wow.runtime.snapshot.SnapshotWrites], [me.ahoo.wow.runtime.sink.SinkWrites]): each lane [submit]s a
 * request into one queue, and the requests waiting together are written by one [writeBatch], of at most [maxBatch]
 * ([Request.size] each), by at most [committers] writes at once, or [catchUp] while the writes are behind.
 *
 * - **No waiting for a batch to fill**: a request finding fewer than the limit leading, and no leader gathering, leads
 *   at once, after a [yield] that lets the lanes ready in the same instant join it; the requests arriving while the
 *   writes run wait for the next, which a leader writes too, until its own request is written. Then it hands the lead
 *   to the oldest request still waiting, so each lane goes on as soon as its own request is done. One leader gathers
 *   at a time, so the requests ready together are one batch.
 * - **A leader writes its own request**: a request leading is taken by no other leader's batch, so a leader's batch
 *   always holds its own request until it is written.
 * - **Behind, more writes**: a take that was full with requests still waiting raises the limit from [committers] to
 *   [catchUp] and starts another leader at once, one a take while they stay full; a take under [retireBelow] lowers
 *   it again, and a leader over the limit then gives its place up when it ends instead of handing it on.
 * - **The invariant**, at every instant the monitor is free: a request waits without leading only while the leaders
 *   are at the limit or one gathers, and no request in the queue is withdrawn. Each change of the queue and of the
 *   leaders keeps it in one critical section: a request that leads or waits, a take (with [startWaiting] after it), a
 *   hand-over, a withdrawal, and a cancelled leader's put-back with its hand-over.
 * - **Withdrawn**: a request whose caller is gone (cancelled) or that is refused ([refusal]) is withdrawn under the
 *   monitor, in whichever state it is: waiting, it leaves the queue (handed the lead, it passes it on); taken into a
 *   batch, the batch still writes it, but a leader cancelled never puts it back. So the lead is handed only to a
 *   request whose caller waits for it, and a withdrawn one holds no leader's place and no gathering.
 * - **Every exit of a write ends its batch**: a leader cancelled puts the requests it took and that are not ended back
 *   in front (but its own and the withdrawn ones) and hands the lead on; a [writeBatch] that throws anything else ends
 *   every request of the batch it left with that failure ([Request.fail]).
 */
internal abstract class LeaderBatches<R, W : LeaderBatches.Request<R>>(
    private val maxBatch: Int,
    private val committers: Int,
    private val catchUp: Int,
    private val retireBelow: Int,
) {
    init {
        require(committers >= 1) { "Batched writes take at least one committer." }
        require(catchUp >= committers) { "Batched writes catch up with at least their committers." }
    }

    /** How many requests may lead now: [committers], or [catchUp] while behind. Guarded by this object's monitor. */
    private var limit = committers

    private val queue = ArrayDeque<W>()

    /** How many requests lead the writes, or were handed the lead. Guarded by this object's monitor. */
    private var leaders = 0

    /**
     * Whether a leader is gathering its first batch, between its lead and its first take. Guarded by this object's
     * monitor. A second write starts only once the first took its batch, and carries what arrived since.
     */
    private var gathering = false

    /** The requests waiting now, for a test. */
    val waiting: Int get() = synchronized(this) { queue.size }

    /** How many may lead now. */
    protected val leadersAllowed: Int get() = synchronized(this) { limit }

    /** Writes [batch], and ends each of its requests ([Request.result]). */
    protected abstract suspend fun writeBatch(batch: List<W>)

    /** Called under the monitor: why [request] may not wait in the queue, or null. Refused, it is withdrawn. */
    protected open fun refusal(request: W): Throwable? = null

    /** Called under the monitor when a take changed the limit to [leaders]. */
    protected open fun limited(leaders: Int) = Unit

    /** Writes [request] with the requests waiting with it; its result. A refused request fails at once. */
    protected suspend fun submit(request: W): R {
        val leads = synchronized(this) {
            refusal(request)?.let {
                request.withdrawn = true
                throw it
            }
            queue.addLast(request)
            (leaders < limit && !gathering).also {
                if (it) {
                    leaders++
                    gathering = true
                    request.leads = true
                }
            }
        }
        if (leads || awaitLead(request)) {
            lead(request)
        }
        return request.result.await()
    }

    /** Withdraws the requests waiting and leading nothing that are now refused ([refusal]), and fails them. */
    protected fun refuseWaiting() {
        val refused = synchronized(this) {
            queue.mapNotNull { request -> if (request.leads) null else refusal(request)?.let { request to it } }
                .onEach { (request, _) ->
                    request.withdrawn = true
                    queue.remove(request)
                }
        }
        refused.forEach { (request, cause) -> request.fail(cause) }
    }

    /** Waits until [request] is ended, false, or handed the lead, true. */
    private suspend fun awaitLead(request: W): Boolean = try {
        select {
            request.result.onAwait { false }
            request.lead.onAwait { true }
        }
    } catch (cancelled: CancellationException) {
        synchronized(this) {
            request.withdrawn = true
            if (queue.remove(request) && request.leads) {
                // Handed the lead it will not take: it was gathering.
                handOver(wasGathering = true)
            }
        }
        throw cancelled
    }

    /** Writes the waiting requests, a batch at a time, until [own] is ended; then hands the lead on. */
    private suspend fun lead(own: W) {
        var taken = emptyList<W>()
        var gathered = false
        var handedOn = false
        try {
            yield()
            while (!own.result.isCompleted) {
                // The own request waits in the queue until this leader's batch takes it, so a batch is never empty.
                taken = synchronized(this) {
                    take(own).also {
                        if (!gathered) {
                            gathered = true
                            gathering = false
                        }
                        paced(it)
                        startWaiting()
                    }
                }
                writeEnding(taken)
            }
        } catch (cancelled: CancellationException) {
            // One critical section: the requests put back are never seen waiting with a place free and nobody gathering.
            val refused = synchronized(this) {
                own.withdrawn = true
                // Cancelled at the yield, its own request still heads the queue: handed the lead, it would lead nobody.
                queue.remove(own)
                putBack(taken.filter { it !== own }).also {
                    handOver(wasGathering = !gathered)
                    handedOn = true
                }
            }
            refused.forEach { (request, cause) -> request.fail(cause) }
            throw cancelled
        } finally {
            if (!handedOn) {
                synchronized(this) { handOver(wasGathering = !gathered) }
            }
        }
    }

    /**
     * Writes [batch]; when the write throws anything but the leader's cancellation, which puts the batch back, each of
     * its requests not ended yet ends with that failure before it is thrown.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever a write throws ends its batch before it leaves the leader.
    private suspend fun writeEnding(batch: List<W>) = try {
        writeBatch(batch)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        batch.forEach { if (!it.result.isCompleted) it.fail(failure) }
        throw failure
    }

    /**
     * Called under the monitor: the requests of [taken] not ended and not withdrawn go back in front, in their order;
     * those refused now are withdrawn instead, and returned with their refusal to be failed.
     */
    private fun putBack(taken: List<W>): List<Pair<W, Throwable>> {
        val refused = ArrayList<Pair<W, Throwable>>()
        taken.asReversed().forEach { request ->
            if (request.withdrawn || request.result.isCompleted) return@forEach
            val refusal = refusal(request)
            if (refusal == null) {
                queue.addFirst(request)
            } else {
                request.withdrawn = true
                refused += request to refusal
            }
        }
        return refused
    }

    /**
     * Called under the monitor: the oldest waiting requests whose sizes fit [maxBatch] together, at least one, taken
     * out of the queue for [own]'s leader; [own] and the requests no other leader leads.
     */
    private fun take(own: W): List<W> {
        val taken = ArrayList<W>()
        var size = 0
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val request = iterator.next()
            if (request !== own && request.leads) continue
            if (taken.isNotEmpty() && size + request.size > maxBatch) break
            taken += request
            size += request.size
            iterator.remove()
        }
        return taken
    }

    /**
     * Called under the monitor, after a take of [taken]: behind when it was full with requests still waiting, the
     * limit up to [catchUp]; caught up when it took less than [retireBelow], the limit back to [committers].
     */
    private fun paced(taken: List<W>) {
        if (catchUp == committers) return
        val size = taken.sumOf { it.size }
        if (size >= maxBatch && queue.any { !it.leads }) {
            limit = catchUp
        } else if (size < retireBelow) {
            limit = committers
        }
        limited(limit)
    }

    /**
     * Called under the monitor, after a take: while no leader gathers and fewer than the limit lead, the oldest request
     * still waiting leads the next write and gathers. So the requests a take left behind get a write of their own at
     * once, never sitting behind a single write until it ends.
     */
    private fun startWaiting() {
        if (gathering || leaders >= limit) {
            return
        }
        val next = queue.firstOrNull { !it.leads } ?: return
        leaders++
        next.leads = true
        gathering = true
        next.lead.complete(Unit)
    }

    /**
     * Called under the monitor: a leader's place passes to the oldest request waiting, not leading, which then gathers;
     * else, while another leader gathers (which takes the waiting requests), or while more lead than the limit (it fell
     * when the writes caught up), it is given up. [wasGathering]: the leader leaving had not taken its first batch yet.
     */
    private fun handOver(wasGathering: Boolean) {
        if (wasGathering) {
            gathering = false
        }
        val next = queue.firstOrNull { !it.leads }
        if (next == null || gathering || leaders > limit) {
            leaders--
        } else {
            next.leads = true
            gathering = true
            next.lead.complete(Unit)
        }
    }

    /** A lane's request: its [result], once its batch was written. */
    abstract class Request<R> {
        val result = CompletableDeferred<R>()

        /** What this request counts toward a batch's [maxBatch]. */
        open val size: Int get() = 1

        /** Whether this request leads the writes, or was handed the lead. Guarded by the writes' monitor. */
        internal var leads = false

        /**
         * Whether this request's caller is gone, or it was refused: never put back, never handed the lead. Guarded by
         * the writes' monitor.
         */
        internal var withdrawn = false

        /** Completed when the lead is handed to this request. */
        internal val lead = CompletableDeferred<Unit>()

        /** Ends this request with [cause], its batch's failure or its refusal. */
        abstract fun fail(cause: Throwable)
    }
}
