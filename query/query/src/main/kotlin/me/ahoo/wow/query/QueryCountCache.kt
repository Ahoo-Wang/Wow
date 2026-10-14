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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.FilterExpression
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

/**
 * The snapshot write checkpoint of an aggregate type (decision D152): a value that does not change while no snapshot
 * of the type is written, so a count taken at one value is still the count while the value holds. The host gives the
 * snapshot subscription's progress over the type's partitions; it may move without a write of the type (a write of
 * another type in the same partitions), which only costs a recount.
 */
fun interface SnapshotWriteCheckpoint {
    /** The checkpoint of [type] now, or `null` when it cannot be told: its counts are then not cached. */
    suspend fun of(type: AggregateType): Long?
}

/**
 * The bounds of a [QueryCountCache]: at most [maxEntries] counts, the least recently used evicted first, each served
 * for at most [ttl] after it was counted, however long its checkpoint holds. The [ttl] bounds what the checkpoint
 * cannot see: a snapshot written but not yet checkpointed (on another node, up to the checkpoint interval), or
 * written outside the snapshot subscription.
 */
data class QueryCountCacheOptions(
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    val ttl: Duration = DEFAULT_TTL,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive." }
        require(ttl > Duration.ZERO) { "ttl must be positive." }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 10_000

        @JvmField
        val DEFAULT_TTL: Duration = Duration.ofSeconds(30)
    }
}

/**
 * The count cache of snapshot queries (decision D152, work package 6.2d): the counts of [count][QueryGateway.count]
 * queries and the totals of [paged][QueryGateway.paged] ones, shared by the gateways of a node.
 *
 * - **Key**: the aggregate type, the admitted filter (the query as admission rewrote it, with every value and the
 *   scope, tenant and ABAC conditions the policies appended: the full query fingerprint, not its value-free shape)
 *   and the bound (`null` for an exact count), with the caller's tenant, owner, space and stated scope besides. A
 *   count is never served to another scope or tenant, and a count and a paged total of one filter and bound share an
 *   entry.
 * - **Validity**: the [checkpoint] of the type, read before counting: an entry is served while the checkpoint still
 *   reads the value it was counted at, and for at most [QueryCountCacheOptions.ttl]. A write that lands while the
 *   count runs moves the checkpoint past the value the entry carries, so the next query counts again.
 * - **Failure**: when the checkpoint cannot be read, the query counts without the cache.
 *
 * [ticker] reads nanoseconds, monotonic: the time an entry ages by.
 */
class QueryCountCache(
    private val checkpoint: SnapshotWriteCheckpoint,
    val options: QueryCountCacheOptions = QueryCountCacheOptions(),
    private val ticker: () -> Long = System::nanoTime,
) {
    private val entries = object : LinkedHashMap<Key, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>): Boolean = size > options.maxEntries
    }
    private val ttlNanos = options.ttl.toNanos()

    /** How many counts are cached now. */
    val size: Int
        get() = synchronized(entries) { entries.size }

    /**
     * The count of [filter] bounded by [bound] for [caller] on [type]: the cached one while it is valid, otherwise
     * [count]'s, then cached. [cached] is told whether the count came from the cache.
     */
    @Suppress("LongParameterList")
    internal suspend fun count(
        type: AggregateType,
        filter: FilterExpression,
        bound: Int?,
        caller: QueryCaller,
        cached: () -> Unit,
        count: suspend () -> CountResult,
    ): CountResult {
        val validator = validator(type) ?: return count()
        val key = Key(type, filter, bound, caller.tenantId, caller.principal, caller.scope)
        val now = ticker()
        val entry = synchronized(entries) { entries[key] }
        if (entry != null && entry.validator == validator && now - entry.countedAt < ttlNanos) {
            cached()
            return entry.result
        }
        return count().also { result ->
            synchronized(entries) { entries[key] = Entry(validator, result, now) }
        }
    }

    @Suppress("TooGenericExceptionCaught") // A checkpoint that cannot be read only turns the cache off for the query.
    private suspend fun validator(type: AggregateType): Long? = try {
        checkpoint.of(type)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        logger.warn(failure) { "Failed to read the snapshot write checkpoint of $type; counting without the cache." }
        null
    }

    /** Drops every cached count. */
    fun clear(): Unit = synchronized(entries) { entries.clear() }

    private data class Key(
        val type: AggregateType,
        val filter: FilterExpression,
        val bound: Int?,
        val tenantId: String?,
        val principal: Scope,
        val scope: QueryScope,
    )

    private class Entry(val validator: Long, val result: CountResult, val countedAt: Long)

    companion object {
        /** The outcome a query's observation is told when its count or total came from the cache. */
        const val CACHED_OUTCOME: String = "cached"

        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
        private val logger = KotlinLogging.logger {}
    }
}
