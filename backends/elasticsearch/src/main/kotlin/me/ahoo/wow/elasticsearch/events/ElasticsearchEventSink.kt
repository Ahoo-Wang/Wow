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
import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch.core.BulkRequest
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.elasticsearch.ElasticsearchFailures
import me.ahoo.wow.elasticsearch.ElasticsearchIndices
import me.ahoo.wow.elasticsearch.ElasticsearchNames
import me.ahoo.wow.elasticsearch.ElasticsearchStore
import me.ahoo.wow.elasticsearch.awaitElasticsearch
import me.ahoo.wow.elasticsearch.whenShardReady
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.StoreDdl
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.store.Store
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.ConcurrentHashMap

/**
 * The event search Sink of one aggregate type (work package 6.7, decisions D13, D132, D235): writes each commit the
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
 *   systemic (decision D243), any other as an [IllegalStateException]. The records it wrote stay; the retry's
 *   `create`s of them answer `409`.
 *
 * The index is created before the first write as the store's DDL mode says (`auto` creates it, `validate` requires
 * it, `none` assumes it), and its primary waited for, as the snapshot indices are.
 */
@InternalWowApi
class ElasticsearchEventSink internal constructor(
    private val client: ElasticsearchAsyncClient,
    names: ElasticsearchNames,
    val type: AggregateType,
    private val ddl: StoreDdl,
    private val refresh: Refresh?,
) : Sink {
    /** The type's events index. */
    val index: String = names.events(type.context, type.name)

    private val indices = ElasticsearchIndices(client)

    @Volatile
    private var ensured = false

    override suspend fun write(records: List<StateEventRecord>) {
        if (records.isEmpty()) return
        records.forEach { record ->
            require(record.ref.type == type) { "The Sink of $type does not write ${record.ref}." }
        }
        prepare()
        val request = BulkRequest.of { bulk ->
            bulk.index(index)
            refresh?.let(bulk::refresh)
            bulk.operations(records.map(::create))
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
            ElasticsearchEventIndex.ADDED,
            ElasticsearchEventIndex.ADDITIONS,
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

    private companion object {
        const val MAX_REPORTED = 3
    }
}

/**
 * The event search Sinks of one Elasticsearch store (work package 6.7): [sinkOf] gives the [ElasticsearchEventSink] of
 * an aggregate type, its index in the store's names (`index-prefix`), created as the store's DDL mode says. The host
 * assembles them as the published streams' sinks, each a sink subscription named `elasticsearch-sink.<aggregate>`
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
        /** The processor name of the event search Sink's subscriptions: `elasticsearch-sink.<aggregate>`. */
        const val PROCESSOR: String = "elasticsearch-sink"

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
