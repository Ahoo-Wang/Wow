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

package me.ahoo.wow.migrate

/** Where a scan by commit time stands: the last stream read, by its `createTime` and its id. */
internal data class NineXPosition(val createTime: Long, val streamId: String)

/**
 * An instance of a 9.x aggregate as a page of the walk read it ([NineXEventSource.instances]): its [streams] from
 * version 1 on, in version order, all of them when [complete]; otherwise the rest follow, read with
 * [NineXEventSource.streams] from the version after the last of them.
 */
internal data class NineXInstance(val id: NineXAggregateId, val streams: List<NineXStream>, val complete: Boolean)

/**
 * A range of the full import's walk, by aggregate id: from [from], included (the first id when null), to [until],
 * excluded (to the last when null). The ranges of an import share no id and leave none out
 * ([NineXEventSource.idBoundaries]); each is walked on its own ([NineXEventSource.instances]).
 */
internal data class IdRange(val from: String? = null, val until: String? = null) {
    /** Whether [id] is in this range, in the order of Kotlin's strings: a store compares in its own. */
    fun holds(id: String): Boolean = (from == null || id >= from) && (until == null || id < until)

    companion object {
        /** Every id. */
        val ALL = IdRange()

        /** The ranges [boundaries], sorted in the store's order, split the ids into: one more than them. */
        fun split(boundaries: List<String>): List<IdRange> =
            (listOf(null) + boundaries).zip(boundaries + listOf(null)) { from, until -> IdRange(from, until) }
    }
}

/**
 * A 9.x event store, read by its storage format (no 9.x module). The calls block; the import runs them
 * off its coroutines.
 *
 * 9.x stores have no position to resume a read from (step 3): the full import walks the aggregates by
 * id, and the tail scans by commit time, which the store orders by `createTime`, ties by stream id.
 */
internal interface NineXEventSource {
    /** The store, as a message names it: the database it reads. */
    val store: String

    /** Scans the whole store: what each 9.x aggregate holds, for [MappingCheck]. */
    fun inventory(): List<NineXInventory>

    /**
     * At most [limit] ids of [aggregate]'s instances after [after] (from the first when null), in id order: by id,
     * then by tenant, each instance by its version-1 stream.
     */
    fun aggregateIds(aggregate: NineXAggregate, after: NineXAggregateId?, limit: Int): List<NineXAggregateId>

    /**
     * A page of the full import's walk: at most [limit] of [aggregate]'s instances after [after], as [aggregateIds]
     * lists them, with about [maxStreams] of their streams in all. The page ends early at the instance that reaches
     * [maxStreams]: it is the page's last, and incomplete when it holds more. A store reads a page in one scan when
     * it can; this one reads the ids, then each instance's streams. An instance whose streams do not read is left
     * incomplete with none: the import reads them with [streams], which fails where it always has.
     */
    fun instances(
        aggregate: NineXAggregate,
        after: NineXAggregateId?,
        limit: Int,
        maxStreams: Int,
    ): List<NineXInstance> {
        var left = maxStreams
        val page = ArrayList<NineXInstance>()
        for (id in aggregateIds(aggregate, after, limit)) {
            if (left <= 0) {
                break
            }
            val read = try {
                streams(aggregate, id, 1, left + 1)
            } catch (_: MigrationException) {
                page += NineXInstance(id, emptyList(), complete = false)
                continue
            }
            page += NineXInstance(id, read.take(left), complete = read.size <= left)
            left -= read.size
        }
        return page
    }

    /**
     * A page of the walk of [range] ([instances]): its instances after [after], or from its first when null, the page
     * ending where the range ends. A store reads it in one bounded scan when it can; this one reads the pages of the
     * whole walk, passing over the ids before the range.
     */
    fun instances(
        aggregate: NineXAggregate,
        range: IdRange,
        after: NineXAggregateId?,
        limit: Int,
        maxStreams: Int,
    ): List<NineXInstance> {
        if (range == IdRange.ALL) {
            return instances(aggregate, after, limit, maxStreams)
        }
        var from = after
        while (true) {
            val read = instances(aggregate, from, limit, maxStreams)
            val held = read.filter { range.holds(it.id.id) }
            val ended = range.until?.let { until -> read.any { it.id.id >= until } } == true
            if (read.isEmpty() || held.isNotEmpty() || ended) {
                return held
            }
            from = read.last().id
        }
    }

    /**
     * Up to [ranges] - 1 aggregate ids that split [aggregate]'s instances into about even ranges of the full import's
     * walk ([IdRange.split]), sorted in the store's own order; none when it holds too few. A store samples them; this
     * one walks every id.
     */
    fun idBoundaries(aggregate: NineXAggregate, ranges: Int): List<String> {
        val ids = ArrayList<String>()
        var after: NineXAggregateId? = null
        while (true) {
            val page = aggregateIds(aggregate, after, BOUNDARY_PAGE)
            if (page.isEmpty()) {
                break
            }
            ids += page.map { it.id }
            after = page.last()
        }
        val distinct = ids.distinct()
        return (1 until ranges).mapNotNull { distinct.getOrNull(it * distinct.size / ranges) }.distinct()
            .filter { it != distinct.firstOrNull() }
    }

    /**
     * What [aggregate]'s 9.x tags events wrote ([NineXTags.applied], [NineXTags.events]): the events whose payload holds
     * a `tags` object, by name, and the tag keys they wrote, each with its values' counts. A store counts them in one
     * scan; this one reads every stream.
     */
    fun tags(aggregate: NineXAggregate): NineXTags {
        val applied = LinkedHashMap<String, TagValueStats>()
        val events = LinkedHashMap<String, Long>()
        tagged(aggregate) { _, event, tags ->
            events.merge(event.name, 1, Long::plus)
            tags.forEach { (key, values) -> applied.merge(key, TagValueStats.of(values), TagValueStats::plus) }
            true
        }
        return NineXTags(applied = applied, events = events)
    }

    /** At most [limit] ids of [aggregate]'s streams whose tags event wrote the values of [key] [problem] names. */
    fun tagSamples(aggregate: NineXAggregate, key: String, problem: TagValueProblem, limit: Int): List<String> {
        val ids = ArrayList<String>()
        tagged(aggregate) { stream, _, tags ->
            tags[key]?.takeIf { TagValues.holds(problem, it) }?.let { ids += stream.id }
            ids.size < limit
        }
        return ids
    }

    /** Hands each tags event of [aggregate]'s streams to [each], with its stream and tags, until it returns false. */
    private fun tagged(
        aggregate: NineXAggregate,
        each: (NineXStream, NineXEvent, Map<String, List<String>>) -> Boolean
    ) {
        var after: NineXAggregateId? = null
        while (true) {
            val ids = aggregateIds(aggregate, after, TAG_PAGE)
            if (ids.isEmpty()) {
                return
            }
            for (id in ids) {
                for (stream in streams(aggregate, id, 1, Int.MAX_VALUE)) {
                    for (event in stream.events) {
                        val tags = NineXTags.of(event.body.get(TAGS)) ?: continue
                        if (!each(stream, event, tags)) {
                            return
                        }
                    }
                }
            }
            after = ids.last()
        }
    }

    /** At most [limit] streams of [id] from version [fromVersion] on, in version order. */
    fun streams(aggregate: NineXAggregate, id: NineXAggregateId, fromVersion: Int, limit: Int): List<NineXStream>

    /**
     * At most [limit] streams of [aggregate] committed at or after [from] and after [after] (from [from] when null),
     * up to [until] included (with no end when null), in commit-time order.
     */
    fun committedSince(
        aggregate: NineXAggregate,
        from: Long,
        after: NineXPosition?,
        limit: Int,
        until: NineXPosition? = null,
    ): List<NineXStream>

    /** Where [aggregate]'s latest committed stream stands, by commit time; null when it has none. */
    fun lastCommitted(aggregate: NineXAggregate): NineXPosition?

    /**
     * The tenants of [aggregate]'s instances whose id is [id], by their first streams, sorted: one for an instance
     * found, none when no stream has the id, several when ids collide across tenants.
     */
    fun tenantsOf(aggregate: NineXAggregate, id: String): List<String>

    /**
     * The collections of [aggregates] that lack the index the walk reads them in ([aggregateIds], [instances]), each
     * with the command that creates it: the walk refuses them. None for a store that needs no index.
     */
    fun missingWalkIndexes(aggregates: Collection<NineXAggregate>): Map<String, String> = emptyMap()
}

/**
 * A 9.x store whose scan by commit time needs an index 9.x did not create. The tool never writes to a 9.x store unless
 * the operator opts in: without the index, the tail refuses to start and says how to create it.
 */
internal interface CommitTimeIndexed {
    /** The collections of [aggregates] that lack the index, each with the command that creates it. */
    fun missingCommitTimeIndexes(aggregates: Collection<NineXAggregate>): Map<String, String>

    /** Creates the index on [aggregate]'s collection when it is missing: a write to the 9.x store. */
    fun ensureCommitTimeIndex(aggregate: NineXAggregate)
}

/** The ids [NineXEventSource.idBoundaries] reads at once when it walks them all. */
private const val BOUNDARY_PAGE = 1000

/** The ids [NineXEventSource]'s own tag counts read at once. */
private const val TAG_PAGE = 500
