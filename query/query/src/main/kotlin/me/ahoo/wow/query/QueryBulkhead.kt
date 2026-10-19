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

package me.ahoo.wow.query

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ICountQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.Queryable
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How many queries one store runs at once (review R3.4-2): the queries of the snapshots and the `events` of every
 * aggregate type on the store share it, whichever entry asks, so a burst of list queries cannot take every connection
 * of a pool the store's appends draw from. A query beyond [maxConcurrent] waits briefly (decision D247): in a FIFO
 * queue of at most [maxQueued] queries, for at most [maxWait], for a permit to free up; it fails with
 * [QueryBusyException] (`wow.query.busy`, 429 with `Retry-After`) when the queue is full (at once) or the wait runs
 * out, as a command beyond the node's in-flight limit is `wow.command.busy` (decision D228 ②). A [maxWait] of zero or
 * a [maxQueued] of zero refuses at once. A caller cancelled while it waits leaves its queue slot and takes no permit.
 *
 * A query holds its permit from the backend call to its end: a streamed list or aggregation until its last row is
 * emitted, or until it fails or its collector cancels it. A paged query that counts its total takes one permit for its
 * page and one for its count, one after the other.
 */
@InternalWowApi
class QueryBulkhead(
    /** The store the queries run on, `wow.stores.<name>`, for the logs. */
    val store: String,
    val maxConcurrent: Int,
    /** How long a query beyond [maxConcurrent] waits for a permit before it is refused. */
    val maxWait: Duration = DEFAULT_MAX_WAIT,
    /** How many queries may wait at once; one more is refused at once. */
    val maxQueued: Int = maxConcurrent * DEFAULT_QUEUE_FACTOR,
) {
    init {
        require(maxConcurrent >= 1) { "A store runs at least one query at once, not $maxConcurrent." }
        require(!maxWait.isNegative()) { "A query's wait for a permit is never negative, not $maxWait." }
        require(maxQueued >= 0) { "A store's query queue is never negative, not $maxQueued." }
    }

    private val permits = Semaphore(maxConcurrent)
    private val waiting = AtomicInteger()

    /** The queries running now. */
    val running: Int get() = maxConcurrent - permits.availablePermits

    /** The queries waiting for a permit now. */
    val queued: Int get() = waiting.get()

    /** Runs [block] under one permit, or fails with [QueryBusyException] when none is free. */
    suspend fun <T> run(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            permits.release()
        }
    }

    /** [source], collected under one permit taken when it is collected, or failing with [QueryBusyException]. */
    fun <T> run(source: Flow<T>): Flow<T> = flow {
        acquire()
        try {
            source.collect { emit(it) }
        } finally {
            permits.release()
        }
    }

    /**
     * Takes a permit: at once when one is free, else after waiting in the queue at most [maxWait]. The semaphore hands
     * a released permit to the longest waiter (FIFO) and gives it back when that waiter was cancelled meanwhile.
     */
    private suspend fun acquire() {
        if (permits.tryAcquire()) return
        if (maxWait == Duration.ZERO || !enqueue()) throw busy()
        try {
            withTimeoutOrNull(maxWait) { permits.acquire() } ?: throw busy()
        } finally {
            waiting.decrementAndGet()
        }
    }

    /** Takes a queue slot, unless [maxQueued] queries wait already. */
    private fun enqueue(): Boolean {
        while (true) {
            val count = waiting.get()
            if (count >= maxQueued) return false
            if (waiting.compareAndSet(count, count + 1)) return true
        }
    }

    private fun busy() = QueryBusyException(store, maxConcurrent)

    override fun toString(): String =
        "QueryBulkhead(store=$store, maxConcurrent=$maxConcurrent, maxWait=$maxWait, maxQueued=$maxQueued)"

    @InternalWowApi
    companion object {
        /** How long a query waits for a permit by default (decision D247). */
        val DEFAULT_MAX_WAIT: Duration = 200.milliseconds

        /** The default queue: this many waiting queries per permit (decision D247). */
        const val DEFAULT_QUEUE_FACTOR: Int = 4
    }
}

/** [delegate] with each of its primitives run under [bulkhead]. */
internal class BulkheadQueryBackend(private val delegate: QueryBackend, private val bulkhead: QueryBulkhead) :
    QueryBackend by delegate {
    override fun stream(query: AdmittedQuery<IListQuery>): Flow<ObjectNode> = bulkhead.run(delegate.stream(query))

    override suspend fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): BackendPage =
        bulkhead.run { delegate.page(query, window) }

    override suspend fun count(query: AdmittedQuery<ICountQuery>): Long = bulkhead.run { delegate.count(query) }

    override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flow<ObjectNode> =
        bulkhead.run(delegate.aggregate(query, window))
}

/** [this] backend under [bulkhead], or as it is without one. */
internal fun QueryBackend.under(bulkhead: QueryBulkhead?): QueryBackend =
    if (bulkhead == null) this else BulkheadQueryBackend(this, bulkhead)
