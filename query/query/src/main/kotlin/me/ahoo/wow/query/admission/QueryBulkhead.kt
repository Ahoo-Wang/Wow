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

package me.ahoo.wow.query.admission

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.query.backend.BackendPage
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.error.QueryBusyException
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How many queries one store runs at once: the queries of the snapshots and the `events` of every
 * aggregate type on the store share it, whichever entry asks, so a burst of list queries cannot take every connection
 * of a pool the store's appends draw from. A query beyond the [limit] waits briefly: in a FIFO
 * queue of at most [maxQueued] queries, for at most [maxWait], for a permit to free up; it fails with
 * [QueryBusyException] (`wow.query.busy`, 429 with `Retry-After`) when the queue is full (at once) or the wait runs
 * out, as a command beyond the node's in-flight limit is `wow.command.busy`. A [maxWait] of zero or
 * a [maxQueued] of zero refuses at once. A caller cancelled while it waits leaves its queue slot and takes no permit.
 *
 * A query holds its permit from the backend call to its end: a streamed list or aggregation until its last row is
 * emitted, or until it fails or its collector cancels it. A paged query that counts its total takes one permit for its
 * page and one for its count, one after the other.
 *
 * The [limit] starts at [maxConcurrent] and may move between 1 and [ceiling] ([limit] it to another:
 * an [AdaptiveQueryLimit] gives way to the store's writes). Lowering it cancels no query: the permits of the queries
 * beyond it are kept back as those queries end. A bulkhead whose [ceiling] is its [maxConcurrent] keeps that limit.
 */
@InternalWowApi
class QueryBulkhead(
    /** The store the queries run on, `wow.stores.<name>`, for the logs. */
    val store: String,
    /** The limit the bulkhead starts with: the configured one, else the one its store's provider derives. */
    val maxConcurrent: Int,
    /** How long a query beyond the [limit] waits for a permit before it is refused. */
    val maxWait: Duration = DEFAULT_MAX_WAIT,
    /** How many queries may wait at once; one more is refused at once. */
    val maxQueued: Int = maxConcurrent * DEFAULT_QUEUE_FACTOR,
    /** The highest the [limit] may be raised to. */
    val ceiling: Int = maxConcurrent,
) {
    init {
        require(maxConcurrent >= 1) { "A store runs at least one query at once, not $maxConcurrent." }
        require(!maxWait.isNegative()) { "A query's wait for a permit is never negative, not $maxWait." }
        require(maxQueued >= 0) { "A store's query queue is never negative, not $maxQueued." }
        require(ceiling >= maxConcurrent) { "A store's query ceiling $ceiling is below its limit $maxConcurrent." }
    }

    /** One permit per query up to the [ceiling]; those above the [limit] are [reserved], or [owed] back. */
    private val permits = Semaphore(ceiling, ceiling - maxConcurrent)
    private val waiting = AtomicInteger()
    private val contended = AtomicInteger()
    private val lock = Any()

    /** The permits above the [limit] kept back; guarded by [lock]. */
    private var reserved = ceiling - maxConcurrent

    /** The permits above the [limit] still held by running queries, kept back as they end; guarded by [lock]. */
    private var owed = 0

    /** How many queries the store runs at once now. */
    @Volatile
    var limit: Int = maxConcurrent
        private set

    /** The queries running now. */
    val running: Int get() = synchronized(lock) { ceiling - permits.availablePermits - reserved }

    /** The queries waiting for a permit now. */
    val queued: Int get() = waiting.get()

    /**
     * Sets the [limit] to [to], from 1 to the [ceiling]. Lowering it lets every running query run on: the permits it
     * takes back are those free now, then those of the queries that end.
     */
    fun limit(to: Int) {
        require(to in 1..ceiling) { "A store's query limit is from 1 to $ceiling, not $to." }
        var released = 0
        synchronized(lock) {
            limit = to
            val target = ceiling - to
            val current = reserved + owed
            if (target > current) {
                owed += target - current
                while (owed > 0 && permits.tryAcquire()) {
                    owed--
                    reserved++
                }
            } else if (target < current) {
                val forgiven = minOf(owed, current - target)
                owed -= forgiven
                released = current - target - forgiven
                reserved -= released
            }
        }
        repeat(released) { permits.release() }
    }

    /** How many queries had to wait or were refused since the last call: whether the [limit] held anyone back. */
    fun takeContended(): Int = contended.getAndSet(0)

    /** Runs [block] under one permit, or fails with [QueryBusyException] when none is free. */
    suspend fun <T> run(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    /** [source], collected under one permit taken when it is collected, or failing with [QueryBusyException]. */
    fun <T> run(source: Flow<T>): Flow<T> = flow {
        acquire()
        try {
            source.collect { emit(it) }
        } finally {
            release()
        }
    }

    /**
     * Takes a permit: at once when one is free, else after waiting in the queue at most [maxWait], or fails with
     * [QueryBusyException]. The semaphore hands a released permit to the longest waiter (FIFO) and gives it back when
     * that waiter was cancelled meanwhile. Each permit taken is given back with one [release]: a caller that reads in
     * chunks may give it back between two and take it again, so a lowered [limit] applies to it from its next chunk.
     */
    suspend fun acquire() {
        if (permits.tryAcquire()) return
        contended.incrementAndGet()
        if (maxWait == Duration.ZERO || !enqueue()) throw busy()
        try {
            withTimeoutOrNull(maxWait) { permits.acquire() } ?: throw busy()
        } finally {
            waiting.decrementAndGet()
        }
    }

    /** Gives a permit [acquire] took back, or keeps it back when the [limit] was lowered below the running queries. */
    fun release() {
        synchronized(lock) {
            if (owed > 0) {
                owed--
                reserved++
                return
            }
        }
        permits.release()
    }

    /** Takes a queue slot, unless [maxQueued] queries wait already. */
    private fun enqueue(): Boolean {
        while (true) {
            val count = waiting.get()
            if (count >= maxQueued) return false
            if (waiting.compareAndSet(count, count + 1)) return true
        }
    }

    private fun busy() = QueryBusyException(store, limit)

    override fun toString(): String =
        if (ceiling == maxConcurrent) {
            "QueryBulkhead(store=$store, maxConcurrent=$maxConcurrent, maxWait=$maxWait, maxQueued=$maxQueued)"
        } else {
            "QueryBulkhead(store=$store, maxConcurrent=$maxConcurrent, ceiling=$ceiling, maxWait=$maxWait, " +
                "maxQueued=$maxQueued)"
        }

    @InternalWowApi
    companion object {
        /** How long a query waits for a permit by default. */
        val DEFAULT_MAX_WAIT: Duration = 200.milliseconds

        /** The default queue: this many waiting queries per permit. */
        const val DEFAULT_QUEUE_FACTOR: Int = 4
    }
}

/** [delegate] with each of its primitives run under [bulkhead]. */
internal class BulkheadQueryBackend(private val delegate: QueryBackend, private val bulkhead: QueryBulkhead) :
    QueryBackend by delegate {
    override fun stream(query: AdmittedQuery<ListQuery>): Flow<ObjectNode> = bulkhead.run(delegate.stream(query))

    override suspend fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): BackendPage =
        bulkhead.run { delegate.page(query, window) }

    override suspend fun count(query: AdmittedQuery<CountQuery>): Long = bulkhead.run { delegate.count(query) }

    override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flow<ObjectNode> =
        bulkhead.run(delegate.aggregate(query, window))
}

/** [this] backend under [bulkhead], or as it is without one. */
internal fun QueryBackend.under(bulkhead: QueryBulkhead?): QueryBackend =
    if (bulkhead == null) this else BulkheadQueryBackend(this, bulkhead)
