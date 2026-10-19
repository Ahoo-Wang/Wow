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
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ICountQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.Queryable
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.Semaphore

/**
 * How many queries one store runs at once (review R3.4-2): the queries of the snapshots and the `events` of every
 * aggregate type on the store share it, whichever entry asks, so a burst of list queries cannot take every connection
 * of a pool the store's appends draw from. A query beyond [maxConcurrent] is not queued: it fails at once with
 * [QueryBusyException] (`wow.query.busy`, 429 with `Retry-After`), as a command beyond the node's in-flight limit is
 * `wow.command.busy` (decision D228 ②).
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
) {
    init {
        require(maxConcurrent >= 1) { "A store runs at least one query at once, not $maxConcurrent." }
    }

    private val permits = Semaphore(maxConcurrent)

    /** The queries running now. */
    val running: Int get() = maxConcurrent - permits.availablePermits()

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

    private fun acquire() {
        if (!permits.tryAcquire()) {
            throw QueryBusyException(store, maxConcurrent)
        }
    }

    override fun toString(): String = "QueryBulkhead(store=$store, maxConcurrent=$maxConcurrent)"
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
