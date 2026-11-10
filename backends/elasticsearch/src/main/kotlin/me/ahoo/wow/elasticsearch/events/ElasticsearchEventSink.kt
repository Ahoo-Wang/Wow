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

package me.ahoo.wow.elasticsearch.events

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import co.elastic.clients.elasticsearch._types.Conflicts
import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.BulkRequest
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation
import co.elastic.clients.json.JsonData
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.elasticsearch.ElasticsearchFailures
import me.ahoo.wow.elasticsearch.ElasticsearchIndices
import me.ahoo.wow.elasticsearch.ElasticsearchNames
import me.ahoo.wow.elasticsearch.ElasticsearchStore
import me.ahoo.wow.elasticsearch.ElasticsearchStoreProvider.Companion.TYPE
import me.ahoo.wow.elasticsearch.awaitElasticsearch
import me.ahoo.wow.elasticsearch.whenShardReady
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.access.AccessStamp
import me.ahoo.wow.spi.access.AccessStamps
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.json.query.EventStreamFields
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreConfig
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.ConcurrentHashMap

/**
 * The event search Sink of one aggregate type: writes each commit the
 * sink subscription hands it as one stream document ([ElasticsearchEventDocuments]) of the type's events index
 * ([ElasticsearchNames.events], [ElasticsearchEventIndex]), which the `events` queries of an Elasticsearch store read.
 *
 * - **Batch**: one `_bulk` request per [write], every record a `create` of its document.
 * - **Idempotent by id**: a document's id is the commit's identity ([ElasticsearchNames.streamId]: tenant, aggregate
 *   id and version), and a record written before is answered `409` by its `create`, which counts as written: a
 *   record written again changes nothing, whether a retry, a replay or an old owner of the partition writes it.
 * - **Durable on return**: Elasticsearch answers a bulk request once each document is in its shard's translog. A
 *   write does not wait for the next refresh (searches see it within the index's refresh interval, 1 s by default),
 *   unless the store's `refresh` is `true` (an immediate refresh, for tests): waiting for one would hold each
 *   aggregate's next commit for up to the interval.
 * - **Failure**: a request that fails, or any record it did not write, fails the write: transient answers (`429`,
 *   `502`, `503`, `504`, a connection failure) as a [StorageUnavailableException] the runtime retries and classifies
 *   systemic, any other as an [IllegalStateException]. The records it wrote stay; the retry's
 *   `create`s of them answer `409`.
 *
 * - **Access attributes**: a document holds its record's attributes with the version they are of
 *   ([StateEventRecord.accessVersion]). After a write, each record whose attributes differ from those of the
 *   aggregate's stream before it (the record before it in the write, the last one this Sink wrote of the aggregate,
 *   [capacity] of them remembered, or else that stream's document) stamps them over the aggregate's earlier
 *   documents ([stamp]), the last such record of each aggregate only, after a refresh so the documents just written
 *   are among them. Records are written in log order, so the Sink follows the history it writes as the log does
 *   ([me.ahoo.wow.spi.access.AccessStamps]): a record read before a stamp of the log and one read after it differ,
 *   and the later version wins.
 *
 * The index is created before the first write as the store's DDL mode says (`auto` creates it, `validate` requires
 * it, `none` assumes it), and its primary waited for, as the snapshot indices are.
 */
@InternalWowApi
class ElasticsearchEventSink internal constructor(
    private val client: ElasticsearchAsyncClient,
    names: ElasticsearchNames,
    val type: AggregateType,
    private val ddl: Ddl,
    private val refresh: Refresh?,
    private val capacity: Int = DEFAULT_CAPACITY,
) : Sink,
    AccessStamps {
    /** The type's events index. */
    val index: String = names.events(type.context, type.name)

    private val indices = ElasticsearchIndices(client)

    @Volatile
    private var ensured = false

    /** The last record this Sink wrote of each aggregate: its version and its attributes. */
    private val last = object : LinkedHashMap<AggregateRef, Pair<Int, Map<String, Any?>?>>(INITIAL, LOAD, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AggregateRef, Pair<Int, Map<String, Any?>?>>) =
            size > capacity
    }

    override suspend fun write(records: List<StateEventRecord>) {
        bulk(records, refresh, ::create)
        follow(records)
    }

    /** Stamps the attributes of each aggregate whose records changed them over its earlier documents; see the class. */
    private suspend fun follow(records: List<StateEventRecord>) {
        val before = beforeOf(records)
        val changed = LinkedHashMap<AggregateRef, StateEventRecord>()
        records.forEach { record ->
            val access = record.access
            if (access != null && record.version > 1 && before[record.ref] != access) {
                changed[record.ref] = record
            }
            before[record.ref] = access
        }
        synchronized(last) { records.forEach { last[it.ref] = it.version to it.access } }
        if (changed.isEmpty()) return
        // The documents just written are searched once refreshed.
        whenShardReady { client.indices().refresh { it.index(index) }.awaitElasticsearch() }
        changed.values.forEach { record ->
            stamp(record.ref, checkNotNull(record.access), record.accessVersion ?: record.version, 1, record.version)
        }
    }

    /**
     * The attributes of the stream before the first record of each aggregate in [records]: the last record this Sink
     * wrote of it when that is the one, else that stream's document, read in one `mget`; null for none.
     */
    private suspend fun beforeOf(records: List<StateEventRecord>): MutableMap<AggregateRef, Map<String, Any?>?> {
        val firsts = records.distinctBy { it.ref }.filter { it.version > 1 }
        val before = HashMap<AggregateRef, Map<String, Any?>?>()
        val unknown = ArrayList<StateEventRecord>()
        synchronized(last) {
            firsts.forEach { record ->
                val seen = last[record.ref]
                if (seen != null && seen.first == record.version - 1) before[record.ref] = seen.second else unknown += record
            }
        }
        if (unknown.isEmpty()) return before
        val ids = unknown.map { ElasticsearchNames.streamId(it.ref, it.version - 1) }
        val response = whenShardReady {
            client.mget({ it.index(index).ids(ids).sourceIncludes(EventStreamFields.ACCESS) }, ObjectNode::class.java)
                .awaitElasticsearch()
        }
        response.docs().zip(unknown).forEach { (doc, record) ->
            before[record.ref] = doc.result()?.source()?.get(EventStreamFields.ACCESS)?.let(AccessValues::of)
        }
        return before
    }

    /**
     * One `update_by_query` of the documents of [ref] of the versions from [from] until [until], each guarded in the
     * query and in its script: a document is written when it has no [ElasticsearchEventIndex.ACCESS_VERSION], one
     * before [asOf], or [asOf] with other attributes (a new model's). A document another stamp changed meanwhile is a
     * version conflict, passed over and searched again, so the later of two stamps wins whatever their order.
     */
    override suspend fun stamp(ref: AggregateRef, access: Map<String, Any?>, asOf: Int, from: Int, until: Int): Int {
        if (from >= until) return 0
        require(ref.type == type) { "The Sink of $type does not stamp $ref." }
        prepare()
        val params = mapOf(ACCESS_PARAM to JsonData.of(access), AS_OF_PARAM to JsonData.of(asOf))
        var written = 0
        repeat(MAX_STAMP_RUNS) {
            val response = whenShardReady {
                client.updateByQuery { request ->
                    request.index(index)
                        .conflicts(Conflicts.Proceed)
                        .query(stamped(ref, asOf, from, until))
                        .script { script -> script.source { it.scriptString(STAMP_SCRIPT) }.params(params) }
                        .also { if (refresh != null) it.refresh(true) }
                }.awaitElasticsearch()
            }
            written += response.updated()?.toInt() ?: 0
            if ((response.versionConflicts() ?: 0L) == 0L) return written
        }
        throw StorageUnavailableException(
            "The access attributes of $ref@$asOf met a concurrent stamp of [$index] $MAX_STAMP_RUNS times.",
        )
    }

    /**
     * One `update_by_query` of [stamps], a walk's page: the query an OR of each stamp's documents ([stamped]), the
     * script each document's own stamp, found by its tenant and aggregate id, under the same guard as [stamp]'s. Stamps
     * of one aggregate of different attributes or versions are written one at a time, as [stamp] writes them.
     */
    override suspend fun stampAll(stamps: List<AccessStamp>): Int {
        val ranges = stamps.filter { it.from < it.until }
        if (ranges.isEmpty()) return 0
        val byRef = ranges.groupBy { it.ref }
        if (byRef.values.any { group -> group.distinctBy { it.access to it.asOf }.size > 1 }) {
            return super.stampAll(ranges)
        }
        ranges.forEach { require(it.ref.type == type) { "The Sink of $type does not stamp ${it.ref}." } }
        prepare()
        val table = byRef.entries.groupBy({ it.key.tenantId }) { (ref, group) ->
            ref.id to mapOf(ACCESS_PARAM to group.first().access, AS_OF_PARAM to group.first().asOf)
        }.mapValues { (_, ids) -> ids.toMap() }
        val params = mapOf(STAMPS_PARAM to JsonData.of(table))
        val query = Query.of { query ->
            query.bool { bool ->
                ranges.forEach { stamp -> bool.should(stamped(stamp.ref, stamp.asOf, stamp.from, stamp.until)) }
                bool.minimumShouldMatch("1")
            }
        }
        var written = 0
        repeat(MAX_STAMP_RUNS) {
            val response = whenShardReady {
                client.updateByQuery { request ->
                    request.index(index)
                        .conflicts(Conflicts.Proceed)
                        .query(query)
                        .script { script -> script.source { it.scriptString(STAMP_ALL_SCRIPT) }.params(params) }
                        .also { if (refresh != null) it.refresh(true) }
                }.awaitElasticsearch()
            }
            written += response.updated()?.toInt() ?: 0
            if ((response.versionConflicts() ?: 0L) == 0L) return written
        }
        throw StorageUnavailableException(
            "The access attributes of ${byRef.size} aggregates met a concurrent stamp of [$index] $MAX_STAMP_RUNS times.",
        )
    }

    /** The documents of [ref] from [from] until [until] that a stamp as of [asOf] may write. */
    private fun stamped(ref: AggregateRef, asOf: Int, from: Int, until: Int): Query = Query.of { query ->
        query.bool { bool ->
            bool.filter { it.term { term -> term.field(EventStreamFields.AGGREGATE_ID).value(ref.id) } }
                .filter { it.term { term -> term.field(EventStreamFields.TENANT_ID).value(ref.tenantId) } }
                .filter {
                    it.range { range ->
                        range.untyped { versions ->
                            versions.field(EventStreamFields.VERSION).gte(JsonData.of(from)).lt(JsonData.of(until))
                        }
                    }
                }
                .should { it.bool { none -> none.mustNot { not -> not.exists { e -> e.field(ACCESS_VERSION) } } } }
                .should {
                    it.range { range -> range.untyped { older -> older.field(ACCESS_VERSION).lte(JsonData.of(asOf)) } }
                }
                .minimumShouldMatch("1")
        }
    }

    private suspend fun bulk(
        records: List<StateEventRecord>,
        refresh: Refresh?,
        operation: (StateEventRecord) -> BulkOperation,
    ) {
        if (records.isEmpty()) return
        records.forEach { record ->
            require(record.ref.type == type) { "The Sink of $type does not write ${record.ref}." }
        }
        prepare()
        val request = BulkRequest.of { bulk ->
            bulk.index(index)
            refresh?.let(bulk::refresh)
            bulk.operations(records.map(operation))
        }
        val response = whenShardReady { client.bulk(request).awaitElasticsearch() }
        if (!response.errors()) return
        val failed = response.items().mapNotNull { item ->
            item.error()?.takeIf { item.status() != ElasticsearchFailures.CONFLICT }?.let { item to it }
        }
        if (failed.isEmpty()) return
        val reported = failed.take(MAX_REPORTED).joinToString("; ") { (item, error) ->
            "${item.id()}: ${item.status()} ${error.type()} ${error.reason()}"
        }
        val message = "The Elasticsearch Sink of $type did not write ${failed.size} of ${records.size} streams to " +
            "[$index]: $reported."
        if (failed.any { (item) -> ElasticsearchFailures.isTransient(item.status()) }) {
            throw StorageUnavailableException(message)
        }
        throw IllegalStateException(message)
    }

    /** Makes sure of the events index, once, as the DDL mode says. */
    suspend fun prepare() {
        if (ensured) return
        indices.ensure(
            index,
            ElasticsearchEventIndex.KIND,
            ElasticsearchEventIndex.FORMAT,
            ElasticsearchEventIndex.DEFINITION,
            ddl,
        )
        ensured = true
    }

    private fun create(record: StateEventRecord): BulkOperation = BulkOperation.of { operation ->
        operation.create<ObjectNode> {
            it.id(ElasticsearchNames.streamId(record.ref, record.version))
                .document(ElasticsearchEventDocuments.document(record))
        }
    }

    override fun toString(): String = "ElasticsearchEventSink($index)"

    @InternalWowApi
    companion object {
        /** The aggregates whose last record a Sink remembers by default. */
        const val DEFAULT_CAPACITY: Int = 100_000

        private const val MAX_REPORTED = 3
        private const val MAX_STAMP_RUNS = 5
        private const val INITIAL = 16
        private const val LOAD = 0.75f
        private const val ACCESS_PARAM = "access"
        private const val AS_OF_PARAM = "asOf"
        private const val ACCESS_VERSION = ElasticsearchEventIndex.ACCESS_VERSION

        private const val STAMPS_PARAM = "stamps"

        /** [STAMP_SCRIPT] of the document's own stamp among [stampAll]'s, by its tenant, then its aggregate id. */
        private const val STAMP_ALL_SCRIPT =
            "def tenant = params.$STAMPS_PARAM[ctx._source.${EventStreamFields.TENANT_ID}]; " +
                "def own = tenant == null ? null : tenant[ctx._source.${EventStreamFields.AGGREGATE_ID}]; " +
                "if (own == null) { ctx.op = 'noop'; return; } " +
                "def stamped = ctx._source.$ACCESS_VERSION; " +
                "if (stamped == null || stamped < own.$AS_OF_PARAM || " +
                "(stamped == own.$AS_OF_PARAM && ctx._source.${EventStreamFields.ACCESS} != own.$ACCESS_PARAM)) " +
                "{ ctx._source.${EventStreamFields.ACCESS} = own.$ACCESS_PARAM; " +
                "ctx._source.$ACCESS_VERSION = own.$AS_OF_PARAM; } else { ctx.op = 'noop'; }"

        /** The guard of a stamp, again on the document it writes: see [stamp]. */
        private const val STAMP_SCRIPT =
            "def stamped = ctx._source.$ACCESS_VERSION; " +
                "if (stamped == null || stamped < params.$AS_OF_PARAM || " +
                "(stamped == params.$AS_OF_PARAM && ctx._source.${EventStreamFields.ACCESS} != params.$ACCESS_PARAM)) " +
                "{ ctx._source.${EventStreamFields.ACCESS} = params.$ACCESS_PARAM; " +
                "ctx._source.$ACCESS_VERSION = params.$AS_OF_PARAM; } else { ctx.op = 'noop'; }"
    }
}

/**
 * The event search Sinks of one Elasticsearch store: [sinkOf] gives the [ElasticsearchEventSink] of
 * an aggregate type, its index in the store's names (`index-prefix`), created as the store's DDL mode says. The host
 * assembles them as the published streams' sinks, each a sink subscription named `wow.elasticsearch-sink.<aggregate>`
 * ([PROCESSOR]).
 */
@InternalWowApi
class ElasticsearchEventSinks internal constructor(private val store: ElasticsearchStore) {
    private val sinks = ConcurrentHashMap<AggregateType, ElasticsearchEventSink>()

    /** The Sink of [aggregate] of [context]. */
    fun sinkOf(context: String, aggregate: String): ElasticsearchEventSink =
        sinks.computeIfAbsent(AggregateType(context, aggregate)) { type ->
            ElasticsearchEventSink(store.client, store.names, type, store.config.ddl, sinkRefresh(store.refresh))
        }

    override fun toString(): String = "ElasticsearchEventSinks(${store.name})"

    @InternalWowApi
    companion object {
        /** The processor name of the event search Sink's subscriptions: `wow.elasticsearch-sink.<aggregate>`. */
        const val PROCESSOR: String = "wow.elasticsearch-sink"

        /**
         * The Sinks of the cluster [restClient] reaches, their indices named with [indexPrefix] as a store's
         * `index-prefix` names them, made sure of as [ddl] says: a tool's, outside a host's stores.
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            restClient: Rest5Client,
            indexPrefix: String = "",
            ddl: Ddl = Ddl.AUTO
        ): ElasticsearchEventSinks =
            ElasticsearchEventSinks(
                ElasticsearchStore(
                    StoreConfig(TYPE, TYPE, ddl),
                    restClient,
                    ElasticsearchNames(indexPrefix),
                ),
            )

        /** The Sinks of [store], a store of type `elasticsearch`. */
        @JvmStatic
        fun of(store: Store): ElasticsearchEventSinks {
            require(store is ElasticsearchStore) { "${store.name} is not an Elasticsearch store." }
            return ElasticsearchEventSinks(store)
        }

        /** The refresh a Sink's write asks for: only the store's immediate one (see [ElasticsearchEventSink]). */
        internal fun sinkRefresh(refresh: Refresh): Refresh? = refresh.takeIf { it == Refresh.True }
    }
}
