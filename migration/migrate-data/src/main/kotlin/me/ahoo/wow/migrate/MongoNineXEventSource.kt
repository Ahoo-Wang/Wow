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

import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoCursor
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import me.ahoo.wow.migrate.NineXBson.toNode
import me.ahoo.wow.migrate.NineXMongoFields.AGGREGATE_ID
import me.ahoo.wow.migrate.NineXMongoFields.BODY
import me.ahoo.wow.migrate.NineXMongoFields.CONTEXT_NAME
import me.ahoo.wow.migrate.NineXMongoFields.CREATE_TIME
import me.ahoo.wow.migrate.NineXMongoFields.EVENT_NAME
import me.ahoo.wow.migrate.NineXMongoFields.ID
import me.ahoo.wow.migrate.NineXMongoFields.TENANT_ID
import me.ahoo.wow.migrate.NineXMongoFields.VERSION
import org.bson.Document
import org.bson.conversions.Bson
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap

/**
 * The 9.x MongoDB event store (9.x `wow-mongo`'s `MongoEventStore`) of one [database], read by its storage format:
 * one collection `<aggregateName>_event_stream` per aggregate, one document per stream, the stream's JSON with its
 * `id` as `_id`; 9.x wrote decimals as `Decimal128`, which read back exact.
 *
 * The full import walks an aggregate's documents in 9.x's unique `(aggregateId, version)` index order ([instances]),
 * a page of instances and their streams in one scan, and reads the rest of an instance it read in part through the
 * same index ([streams]). The tail reads by `(createTime, _id)`, which 9.x does not index. The tail needs that
 * index; [ensureCommitTimeIndex] creates it only when the operator opts in (`source.createCommitTimeIndex`), the only
 * write the tool makes to a 9.x store but the rollback `export`'s streams.
 */
internal class MongoNineXEventSource(private val database: MongoDatabase) : NineXEventSource, CommitTimeIndexed {
    override val store: String get() = "the MongoDB database ${database.name}"

    /** The collections found to hold the walk's index ([missingWalkIndexOf]). */
    private val walkable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val inventories = MongoNineXInventory(database)

    override fun inventory(): List<NineXInventory> =
        database.listCollectionNames().filter { it.endsWith(SUFFIX) }.sorted().flatMap(inventories::inventoryOf)

    /** The version-1 streams of [walk]'s scan: their ids alone, a whole id at a time, but for tenants beyond [limit]. */
    override fun aggregateIds(aggregate: NineXAggregate, after: NineXAggregateId?, limit: Int): List<NineXAggregateId> {
        val ids = ArrayList<NineXAggregateId>()
        val tenants = TreeSet(BINARY)
        var id: String? = null
        fun flush() {
            id?.let { current -> tenants.take(limit - ids.size).forEach { ids += NineXAggregateId(current, it) } }
            tenants.clear()
        }
        walk(aggregate, after, Filters.eq(VERSION, 1), Document(AGGREGATE_ID, 1).append(TENANT_ID, 1)).use { cursor ->
            while (cursor.hasNext()) {
                val document = cursor.next()
                val next = document.getString(AGGREGATE_ID)
                if (next != id) {
                    flush()
                    if (ids.size >= limit) {
                        return ids
                    }
                    id = next
                }
                val tenant = document.getString(TENANT_ID).orEmpty()
                if (!after.precedes(next, tenant)) {
                    tenants += tenant
                }
            }
        }
        flush()
        return ids
    }

    /**
     * A page of the walk in one scan ([walk]): an id's streams arrive together, version 1 first, so each id ends
     * where the next begins. A stream whose tenant has no version-1 stream of the id is not read, nor one without a
     * tenant, as [streams] reads neither. A stream that does not parse leaves its instance incomplete: the import
     * reads the rest with [streams], which fails on it as it always has.
     */
    override fun instances(
        aggregate: NineXAggregate,
        after: NineXAggregateId?,
        limit: Int,
        maxStreams: Int,
    ): List<NineXInstance> = instances(aggregate, IdRange.ALL, after, limit, maxStreams)

    /** A page of [range]'s walk, in one scan bounded by the range ([walk]). */
    override fun instances(
        aggregate: NineXAggregate,
        range: IdRange,
        after: NineXAggregateId?,
        limit: Int,
        maxStreams: Int,
    ): List<NineXInstance> {
        val page = Page(after, limit, maxStreams)
        walk(aggregate, after, null, null, range).use { cursor ->
            while (cursor.hasNext()) {
                if (!page.read(cursor.next())) {
                    break
                }
            }
        }
        return page.end()
    }

    /** A page of [instances] as its scan reads it, one id's [Run] at a time. */
    private inner class Page(
        private val after: NineXAggregateId?,
        private val limit: Int,
        private val maxStreams: Int,
    ) {
        private val instances = ArrayList<NineXInstance>()
        private var run: Run? = null
        private var read = 0

        /** Reads [document], the scan's next; false when the page is full, and ends before it. */
        fun read(document: Document): Boolean {
            val id = document.getString(AGGREGATE_ID)
            if (id != run?.id) {
                flush()
                if (instances.size >= limit || read >= maxStreams) {
                    return false
                }
                run = Run(id)
            }
            val current = checkNotNull(run)
            val tenant = document.getString(TENANT_ID)
            val first = (document.get(VERSION) as? Number)?.toDouble() == 1.0
            if (first && !after.precedes(id, tenant.orEmpty())) {
                current.tenants.putIfAbsent(tenant.orEmpty(), ArrayList())
            }
            val held = tenant?.let { current.tenants[it] } ?: return true
            if (!first && read >= maxStreams) {
                current.truncated = true
                return false
            }
            if (tenant !in current.stalled) {
                try {
                    held += NineXStream.parse(toNode(document))
                    read++
                } catch (_: MigrationException) {
                    current.stalled += tenant
                }
            }
            return true
        }

        /** The page's instances, those of the id it ended in among them. */
        fun end(): List<NineXInstance> {
            flush()
            return instances
        }

        private fun flush() {
            run?.let { instances += it.instances(limit - instances.size) }
            run = null
        }
    }

    /** The streams of one id a page of [instances] read, by tenant: the tenants of its version-1 streams. */
    private class Run(val id: String) {
        val tenants = TreeMap<String, MutableList<NineXStream>>(BINARY)
        val stalled = HashSet<String>()
        var truncated = false

        fun instances(limit: Int): List<NineXInstance> = tenants.entries.take(limit).map { (tenant, streams) ->
            NineXInstance(NineXAggregateId(id, tenant), streams, complete = !truncated && tenant !in stalled)
        }
    }

    /**
     * Whether [id] of [tenant] is [this] or before it, in the walk's order: a page after [this] reads from its id on,
     * and passes over its tenants up to its own.
     */
    private fun NineXAggregateId?.precedes(id: String, tenant: String): Boolean =
        this != null && id == this.id && BINARY.compare(tenant, tenantId) <= 0

    /**
     * [aggregate]'s streams in 9.x's `(aggregateId, version)` index order (it is unique in 9.x), from [after]'s id
     * on, filtered by [filter] and projected by [projection] when given: no sort runs in the store, so a page costs
     * its own streams, however many follow. Ids are strings to the walk, as 9.x writes them; a stream without an id
     * is never read, as [streams] reads none. The index has no tenant: ids that collide across tenants, which only a
     * store without 9.x's unique index holds, are ordered by tenant here, as MongoDB orders strings.
     */
    private fun walk(
        aggregate: NineXAggregate,
        after: NineXAggregateId?,
        filter: Bson?,
        projection: Bson?,
        range: IdRange = IdRange.ALL,
    ): MongoCursor<Document> {
        val name = aggregate.name + SUFFIX
        missingWalkIndexOf(name)?.let { create ->
            throw MigrationException(
                "The 9.x collection $name has no ($AGGREGATE_ID, $VERSION) index, which 9.x creates and the import " +
                    "walks it in: create it, $create.",
            )
        }
        // A page after [after] is within its range, whose ids it starts from otherwise.
        val filters = listOfNotNull(
            Filters.eq(CONTEXT_NAME, aggregate.context),
            Filters.gte(AGGREGATE_ID, after?.id ?: range.from ?: ""),
            range.until?.let { Filters.lt(AGGREGATE_ID, it) },
            filter,
        )
        val find = collectionOf(aggregate).find(Filters.and(filters)).sort(WALK_KEY).hint(WALK_KEY)
        return (projection?.let { find.projection(it) } ?: find).cursor()
    }

    /**
     * Samples [aggregate]'s streams with MongoDB's random cursor (`$sample` first, which reads no index nor the whole
     * collection): their ids, sorted by MongoDB, as the walk's bounds compare them, are weighted by streams, so the
     * ranges hold about as many streams each. A small collection is sampled whole.
     */
    override fun idBoundaries(aggregate: NineXAggregate, ranges: Int): List<String> {
        if (ranges <= 1) {
            return emptyList()
        }
        val sampled = collectionOf(aggregate).aggregate(
            listOf(
                Aggregates.sample(ranges * SAMPLES_PER_RANGE),
                Aggregates.match(
                    Filters.and(Filters.eq(CONTEXT_NAME, aggregate.context), Filters.type(AGGREGATE_ID, "string"))
                ),
                Aggregates.project(Projections.include(AGGREGATE_ID)),
                Aggregates.sort(Sorts.ascending(AGGREGATE_ID)),
            ),
        ).map { it.getString(AGGREGATE_ID) }.toList()
        return (1 until ranges).mapNotNull { sampled.getOrNull(it * sampled.size / ranges) }.distinct()
            .filter { it != sampled.firstOrNull() }
    }

    /**
     * One aggregation of [aggregate]'s collection: its events whose payload holds a `tags` object, counted by name,
     * and their tag keys, each with its values' counts ([MongoTags.counts]).
     */
    override fun tags(aggregate: NineXAggregate): NineXTags {
        val facets = collectionOf(aggregate).aggregate(
            listOf(
                Document("\$match", Document(CONTEXT_NAME, aggregate.context)),
                Document("\$unwind", "\$$BODY"),
                Document("\$match", Document("$BODY.$BODY.$TAGS", Document("\$type", "object"))),
                Document("\$project", Document("name", "\$$BODY.$EVENT_NAME").append(TAGS, "\$$BODY.$BODY.$TAGS")),
                Document(
                    "\$facet",
                    Document(
                        "events",
                        listOf(Document("\$group", Document("_id", "\$name").append("count", Document("\$sum", 1))))
                    )
                        .append("keys", MongoTags.counts("\$$TAGS")),
                ),
            ),
        ).first()!! // A $facet stage answers one document, of empty facets when nothing matches.
        return NineXTags(
            applied = MongoTags.stats(facets.getList("keys", Document::class.java)),
            events = facets.getList("events", Document::class.java).associate {
                it.getString("_id") to (it.get("count") as Number).toLong()
            },
        )
    }

    /** The streams whose tags events hold the values, in id order: each stream once, however many of its events do. */
    override fun tagSamples(
        aggregate: NineXAggregate,
        key: String,
        problem: TagValueProblem,
        limit: Int
    ): List<String> {
        val holding = MongoTags.holding("$BODY.$BODY.$TAGS", key, problem) ?: return emptyList()
        return collectionOf(aggregate).aggregate(
            listOf(
                Document("\$match", Document(CONTEXT_NAME, aggregate.context).append("$BODY.$BODY.$TAGS.$key", Document("\$exists", true))),
                Document("\$unwind", "\$$BODY"),
            ) + holding + listOf(
                Document("\$group", Document(ID, "\$$ID")),
                Document("\$sort", Document(ID, 1)),
                Document("\$limit", limit),
            ),
        ).map { it.get(ID).toString() }.toList()
    }

    override fun missingWalkIndexes(aggregates: Collection<NineXAggregate>): Map<String, String> =
        aggregates.map { it.name + SUFFIX }.distinct()
            .mapNotNull { name -> missingWalkIndexOf(name)?.let { name to it } }
            .toMap()

    /**
     * The command that creates the walk's index on the collection [name], when it holds streams and lacks the index;
     * null otherwise. A collection found indexed is not asked again.
     */
    private fun missingWalkIndexOf(name: String): String? {
        if (name in walkable) {
            return null
        }
        val collection = database.getCollection(name)
        if (collection.listIndexes().any {
                isAscendingKey(
                    it.get("key", Document::class.java),
                    AGGREGATE_ID,
                    VERSION
                )
            }
        ) {
            walkable += name
            return null
        }
        if (collection.find().projection(Document(ID, 1)).limit(1).first() == null) {
            return null
        }
        return "db.getCollection(\"$name\").createIndex({ $AGGREGATE_ID: 1, $VERSION: 1 })"
    }

    override fun streams(
        aggregate: NineXAggregate,
        id: NineXAggregateId,
        fromVersion: Int,
        limit: Int,
    ): List<NineXStream> = read(
        aggregate,
        Filters.and(
            Filters.eq(AGGREGATE_ID, id.id),
            Filters.eq(TENANT_ID, id.tenantId),
            Filters.eq(CONTEXT_NAME, aggregate.context),
            Filters.gte(VERSION, fromVersion),
        ),
        Sorts.ascending(VERSION),
        limit,
    )

    override fun committedSince(
        aggregate: NineXAggregate,
        from: Long,
        after: NineXPosition?,
        limit: Int,
        until: NineXPosition?,
    ): List<NineXStream> {
        val since = if (after == null) {
            Filters.gte(CREATE_TIME, from)
        } else {
            Filters.or(
                Filters.gt(CREATE_TIME, after.createTime),
                Filters.and(Filters.eq(CREATE_TIME, after.createTime), Filters.gt(ID, after.streamId)),
            )
        }
        val upTo = until?.let {
            Filters.or(
                Filters.lt(CREATE_TIME, it.createTime),
                Filters.and(Filters.eq(CREATE_TIME, it.createTime), Filters.lte(ID, it.streamId)),
            )
        }
        return read(
            aggregate,
            Filters.and(listOfNotNull(Filters.eq(CONTEXT_NAME, aggregate.context), since, upTo)),
            Sorts.ascending(CREATE_TIME, ID),
            limit,
        )
    }

    override fun lastCommitted(aggregate: NineXAggregate): NineXPosition? = collectionOf(aggregate)
        .find(Filters.eq(CONTEXT_NAME, aggregate.context))
        .sort(Sorts.descending(CREATE_TIME, ID))
        .projection(Projections.include(CREATE_TIME))
        .limit(1)
        .firstOrNull()
        ?.let { NineXPosition((it.get(CREATE_TIME) as Number).toLong(), it.get(ID).toString()) }

    override fun tenantsOf(aggregate: NineXAggregate, id: String): List<String> = collectionOf(aggregate)
        .find(
            Filters.and(
                Filters.eq(AGGREGATE_ID, id),
                Filters.eq(CONTEXT_NAME, aggregate.context),
                Filters.eq(VERSION, 1)
            )
        )
        .projection(Document(TENANT_ID, 1))
        .map { it.getString(TENANT_ID).orEmpty() }
        .toList()
        .distinct()
        .sorted()

    override fun missingCommitTimeIndexes(aggregates: Collection<NineXAggregate>): Map<String, String> =
        aggregates.map { it.name + SUFFIX }.distinct().filterNot { name ->
            database.getCollection(name).listIndexes().any { isCommitTimeKey(it.get("key", Document::class.java)) }
        }.associateWith { name ->
            "db.getCollection(\"$name\").createIndex({ $CREATE_TIME: 1, $ID: 1 })"
        }

    /** Adds the `(createTime, _id)` index the tail reads [aggregate]'s collection by, when it is missing. */
    override fun ensureCommitTimeIndex(aggregate: NineXAggregate) {
        collectionOf(aggregate).createIndex(Indexes.ascending(CREATE_TIME, ID))
    }

    private fun read(aggregate: NineXAggregate, filter: Bson, sort: Bson, limit: Int): List<NineXStream> =
        collectionOf(aggregate).find(filter).sort(sort).limit(limit).map { NineXStream.parse(toNode(it)) }.toList()

    private fun collectionOf(aggregate: NineXAggregate): MongoCollection<Document> =
        database.getCollection(aggregate.name + SUFFIX)

    companion object {
        /** The suffix of a 9.x event stream collection. */
        const val SUFFIX: String = "_event_stream"

        /**
         * Whether an index [key] is `{createTime: 1, _id: 1}`, however its numbers were written (the shell writes
         * doubles, the driver integers).
         */
        fun isCommitTimeKey(key: Document?): Boolean = isAscendingKey(key, CREATE_TIME, ID)

        /** Whether an index [key] is ascending on [fields], in their order, however its numbers were written. */
        private fun isAscendingKey(key: Document?, vararg fields: String): Boolean =
            key != null && key.keys.toList() == fields.toList() && key.values.all { (it as? Number)?.toDouble() == 1.0 }

        /** The walk's order and the 9.x index it reads: `{aggregateId: 1, version: 1}`. */
        private val WALK_KEY: Bson = Indexes.ascending(AGGREGATE_ID, VERSION)

        /** The streams [idBoundaries] samples for each range it splits into. */
        private const val SAMPLES_PER_RANGE = 100

        /** Strings as MongoDB orders them: by their UTF-8 bytes, unsigned. */
        private val BINARY: Comparator<String> = Comparator { left, right ->
            Arrays.compareUnsigned(left.toByteArray(StandardCharsets.UTF_8), right.toByteArray(StandardCharsets.UTF_8))
        }
    }
}
