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

package me.ahoo.wow.elasticsearch.snapshot

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.OpType
import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch._types.SortOptions
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.BulkRequest
import co.elastic.clients.elasticsearch.core.GetRequest
import co.elastic.clients.elasticsearch.core.IndexRequest
import co.elastic.clients.elasticsearch.core.MgetRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.elasticsearch.ElasticsearchFailures
import me.ahoo.wow.elasticsearch.ElasticsearchIndices
import me.ahoo.wow.elasticsearch.ElasticsearchNames
import me.ahoo.wow.elasticsearch.awaitElasticsearch
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotDocuments.AGGREGATE_ID
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotDocuments.STALE_VERSION
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotDocuments.STATE_REVISION
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotDocuments.TENANT_ID
import me.ahoo.wow.elasticsearch.whenShardReady
import me.ahoo.wow.spi.StoreDdl
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.snapshot.SnapshotTable
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.ConcurrentHashMap

/**
 * The [SnapshotStore] of one bounded context on Elasticsearch (documentation/designs/v10/03-log-and-snapshot.md §3).
 *
 * Each aggregate type has its own index, `wow_snapshot__<context>__<aggregate>` (decision D161, [ElasticsearchNames]),
 * holding one document per aggregate, its id the tenant and the aggregate id ([ElasticsearchNames.documentId]), laid
 * out as [ElasticsearchSnapshotDocuments] and [ElasticsearchSnapshotIndex] say. An aggregate type's index is created
 * the first time this store touches the type, as [ddl] says.
 *
 * Every write is a whole document, guarded by the sequence number of the one it replaces (`if_seq_no`,
 * `if_primary_term`), or created only when there is none (`op_type=create`): the store reads the document in real
 * time, decides as the contract says (version, state revision, stale mark), and writes; a write another one beat is
 * refused by Elasticsearch and decided again. Concurrent saves therefore leave the highest version, and no write is a
 * partial update, which would have Elasticsearch rewrite the `_source` and lose the state's exact numbers.
 *
 * The claimed state revision of each index is a document of the snapshot table index ([ElasticsearchNames.snapshotTables],
 * decision D172), keyed by the snapshot index, guarded the same way. Writes become visible to searches as [refresh] says
 * (`wait_for` by default, so a snapshot is queryable once its write returns).
 */
@InternalWowApi
class ElasticsearchSnapshotStore internal constructor(
    private val client: ElasticsearchAsyncClient,
    private val names: ElasticsearchNames,
    private val context: String,
    private val ddl: StoreDdl = StoreDdl.AUTO,
    private val refresh: Refresh = Refresh.WaitFor,
) : SnapshotStore {
    private val indices = ElasticsearchIndices(client)

    /** The indices whose definition this store has made sure of. */
    private val ensured = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var tablesEnsured = false

    /**
     * Nothing to create up front: each aggregate type's index is created on its first use, since the store does not
     * know the context's aggregate types. Kept for the host, which calls it at startup.
     */
    fun createSchema() = Unit

    /** Makes sure of [aggregate]'s snapshot index, as the store's DDL mode says. */
    suspend fun createIndex(aggregate: String) {
        indexOf(aggregate)
    }

    private suspend fun indexOf(aggregate: String): String {
        val index = names.snapshots(context, aggregate)
        if (index !in ensured) {
            indices.ensure(
                index,
                ElasticsearchSnapshotIndex.KIND,
                ElasticsearchSnapshotIndex.FORMAT,
                ElasticsearchSnapshotIndex.DEFINITION,
                ddl
            )
            ensured += index
        }
        return index
    }

    private suspend fun tablesIndex(): String {
        val index = names.snapshotTables
        if (!tablesEnsured) {
            indices.ensure(index, TABLE_KIND, TABLE_FORMAT, TABLE_DEFINITION, ddl)
            tablesEnsured = true
        }
        return index
    }

    override suspend fun save(snapshot: SnapshotRecord): Boolean {
        checkContext(snapshot.ref)
        val index = indexOf(snapshot.ref.type.name)
        val id = ElasticsearchNames.documentId(snapshot.ref)
        return decide(index, id) { stored -> saveOver(snapshot, stored) }
    }

    /** What a save of [snapshot] decides over [stored]: the version guard, a stale mark for a higher state revision. */
    private fun saveOver(snapshot: SnapshotRecord, stored: Stored?): Decision = when {
        stored == null -> Decision.Write(ElasticsearchSnapshotDocuments.document(snapshot), true)
        ElasticsearchSnapshotDocuments.versionOf(stored.source) >= snapshot.version -> Decision.Keep(false)
        ElasticsearchSnapshotDocuments.stateRevisionOf(stored.source) > snapshot.stateRevision -> {
            // Refused: the stored snapshot is of a higher state revision, so the log has moved past it (D172).
            val stale = ElasticsearchSnapshotDocuments.staleVersionOf(stored.source)
            if (stale != null && stale >= snapshot.version) {
                Decision.Keep(false)
            } else {
                Decision.Write(
                    stored.source.deepCopy().put(ElasticsearchSnapshotDocuments.STALE_VERSION, snapshot.version),
                    false
                )
            }
        }
        else -> Decision.Write(replacement(snapshot, stored.source), true)
    }

    /**
     * Saves [snapshots] (decision D248 ②): in the rounds [SnapshotSaveOutcome.rounds] gives, each round's snapshots of
     * one aggregate type are read with one real-time `_mget`, decided each as [save] decides, and written with one
     * `_bulk` request, each write guarded as [save] guards one (`if_seq_no`, `if_primary_term`, or `create`): two
     * round trips a batch instead of two a snapshot. The writes another one beat (`409`) are read, decided and written
     * again together. A write Elasticsearch refuses alone is [SnapshotSaveOutcome.Failed]: refused as a value (`400`)
     * with an [IllegalArgumentException], as a save would throw, and one the cluster cannot take now (`429`, `503`)
     * with a [me.ahoo.wow.spi.StorageUnavailableException].
     */
    override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> {
        snapshots.forEach { checkContext(it.ref) }
        val outcomes = arrayOfNulls<SnapshotSaveOutcome>(snapshots.size)
        SnapshotSaveOutcome.rounds(snapshots).forEach { round ->
            round.groupBy { snapshots[it].ref.type.name }.forEach { (aggregate, indexes) ->
                saveRound(indexOf(aggregate), indexes, snapshots, outcomes)
            }
        }
        return outcomes.map { checkNotNull(it) }
    }

    /** Saves the [indexes] of [batch], of distinct refs of [index], into [outcomes]. */
    private suspend fun saveRound(
        index: String,
        indexes: List<Int>,
        batch: List<SnapshotRecord>,
        outcomes: Array<SnapshotSaveOutcome?>,
    ) {
        var pending = indexes
        repeat(MAX_ATTEMPTS) {
            val ids = pending.map { ElasticsearchNames.documentId(batch[it].ref) }
            val stored = readAll(index, ids)
            val writes = ArrayList<BulkWrite>(pending.size)
            pending.forEachIndexed { at, position ->
                when (val decision = saveOver(batch[position], stored[at])) {
                    is Decision.Keep -> outcomes[position] = SnapshotSaveOutcome.of(decision.result)
                    is Decision.Write -> writes += BulkWrite(position, ids[at], decision, stored[at])
                }
            }
            if (writes.isEmpty()) {
                return
            }
            val beaten = writeAll(index, writes, outcomes)
            if (beaten.isEmpty()) {
                return
            }
            pending = beaten
        }
        throw IllegalStateException(
            "The documents of [$index] kept changing over $MAX_ATTEMPTS attempts to write them."
        )
    }

    /** One write of a batch: the snapshot's [position] in it, its document [id], what was decided over [stored]. */
    private class BulkWrite(val position: Int, val id: String, val decision: Decision.Write, val stored: Stored?)

    /**
     * Reads the documents [ids] of [index] in real time with one request, each as [read] does; a document the request
     * could not read is read alone.
     */
    private suspend fun readAll(index: String, ids: List<String>): List<Stored?> {
        val request = MgetRequest.of { it.index(index).ids(ids).realtime(true) }
        val docs = whenShardReady { client.mget(request, ObjectNode::class.java).awaitElasticsearch() }.docs()
        return docs.mapIndexed { at, item ->
            if (item.isResult) {
                val result = item.result()
                if (result.found()) {
                    Stored(
                        checkNotNull(result.source()),
                        checkNotNull(result.seqNo()),
                        checkNotNull(result.primaryTerm())
                    )
                } else {
                    null
                }
            } else {
                read(index, ids[at])
            }
        }
    }

    /**
     * Writes [writes] with one `_bulk` request, their outcomes into [outcomes]; returns the positions of those another
     * write beat.
     */
    private suspend fun writeAll(
        index: String,
        writes: List<BulkWrite>,
        outcomes: Array<SnapshotSaveOutcome?>
    ): List<Int> {
        val request = BulkRequest.of { bulk ->
            bulk.index(index).refresh(refresh).operations(writes.map(::operationOf))
        }
        val items = whenShardReady { client.bulk(request).awaitElasticsearch() }.items()
        val beaten = ArrayList<Int>()
        items.forEachIndexed { at, item ->
            val write = writes[at]
            val error = item.error()
            when {
                error == null -> outcomes[write.position] = SnapshotSaveOutcome.of(write.decision.result)
                item.status() == ElasticsearchFailures.CONFLICT -> beaten += write.position
                else -> outcomes[write.position] = SnapshotSaveOutcome.Failed(
                    ElasticsearchFailures.itemFailure(item.status(), "the document [${write.id}] of [$index]", error.reason()),
                )
            }
        }
        return beaten
    }

    private fun operationOf(write: BulkWrite): BulkOperation = BulkOperation.of { operation ->
        val stored = write.stored
        if (stored == null) {
            operation.create<ObjectNode> { it.id(write.id).document(write.decision.document) }
        } else {
            operation.index<ObjectNode> {
                it.id(write.id)
                    .document(write.decision.document)
                    .ifSeqNo(stored.seqNo)
                    .ifPrimaryTerm(stored.primaryTerm)
            }
        }
    }

    override suspend fun rewrite(snapshot: SnapshotRecord): Boolean {
        checkContext(snapshot.ref)
        val index = indexOf(snapshot.ref.type.name)
        val id = ElasticsearchNames.documentId(snapshot.ref)
        return decide(index, id) { stored ->
            when {
                stored == null -> Decision.Write(ElasticsearchSnapshotDocuments.document(snapshot), true)
                ElasticsearchSnapshotDocuments.versionOf(stored.source) > snapshot.version -> Decision.Keep(false)
                else -> Decision.Write(replacement(snapshot, stored.source), true)
            }
        }
    }

    /** The document of [snapshot] in place of [stored]: a stale mark of a version above [snapshot]'s stays. */
    private fun replacement(snapshot: SnapshotRecord, stored: ObjectNode): ObjectNode {
        val stale = ElasticsearchSnapshotDocuments.staleVersionOf(stored)?.takeIf { it > snapshot.version }
        return ElasticsearchSnapshotDocuments.document(snapshot, stale)
    }

    override suspend fun markStale(ref: AggregateRef): Boolean {
        checkContext(ref)
        val index = indexOf(ref.type.name)
        return decide(index, ElasticsearchNames.documentId(ref)) { stored ->
            if (stored == null) {
                Decision.Keep(false)
            } else {
                val version = ElasticsearchSnapshotDocuments.versionOf(stored.source)
                val stale = maxOf(ElasticsearchSnapshotDocuments.staleVersionOf(stored.source) ?: 0, version)
                Decision.Write(stored.source.deepCopy().put(ElasticsearchSnapshotDocuments.STALE_VERSION, stale), true)
            }
        }
    }

    override suspend fun load(ref: AggregateRef): SnapshotRecord? {
        checkContext(ref)
        val stored = read(indexOf(ref.type.name), ElasticsearchNames.documentId(ref)) ?: return null
        return ElasticsearchSnapshotDocuments.snapshot(ref.type, stored.source)
    }

    override suspend fun pending(
        type: AggregateType,
        revision: Int,
        after: AggregateRef?,
        limit: Int,
    ): List<AggregateRef> {
        require(type.context == context) { "$type is not in context $context." }
        require(limit >= 1) { "A page holds at least one ref." }
        val index = indexOf(type.name)
        val request = SearchRequest.of { search ->
            search.index(index)
                .query(pendingAt(revision))
                .size(limit)
                .trackTotalHits { it.enabled(false) }
                .source { it.filter { filter -> filter.includes(TENANT_ID, AGGREGATE_ID) } }
                .sort(ascending(TENANT_ID), ascending(AGGREGATE_ID))
            after?.let { search.searchAfter(FieldValue.of(it.tenantId), FieldValue.of(it.id)) }
            search
        }
        val hits = whenShardReady { client.search(request, ObjectNode::class.java).awaitElasticsearch() }.hits().hits()
        return hits.mapNotNull { it.source() }.map { source ->
            type.ref(
                source.path(TENANT_ID).stringValue(),
                source.path(AGGREGATE_ID).stringValue(),
            )
        }
    }

    override suspend fun table(type: AggregateType): SnapshotTable? {
        require(type.context == context) { "$type is not in context $context." }
        return read(tablesIndex(), names.snapshots(context, type.name))?.source?.let(::tableOf)
    }

    override suspend fun claim(type: AggregateType, revision: Int, lower: Boolean): SnapshotTable {
        require(type.context == context) { "$type is not in context $context." }
        require(revision >= 1) { "A state revision starts at 1." }
        val index = tablesIndex()
        val id = names.snapshots(context, type.name)
        decide(index, id) { stored ->
            when {
                stored == null -> Decision.Write(tableDocument(SnapshotTable(revision, revision != 1, false)), true)
                else -> {
                    val claimed = tableOf(stored.source)
                    val changes = if (lower) claimed.revision != revision else claimed.revision < revision
                    if (changes) {
                        Decision.Write(tableDocument(claimed.copyWith(revision = revision, rebuilding = true)), true)
                    } else {
                        Decision.Keep(true)
                    }
                }
            }
        }
        return tableOf(checkNotNull(read(index, id)).source)
    }

    override suspend fun mark(
        type: AggregateType,
        revision: Int,
        rebuilding: Boolean?,
        backfilled: Boolean?,
    ): SnapshotTable? {
        require(type.context == context) { "$type is not in context $context." }
        val index = tablesIndex()
        val id = names.snapshots(context, type.name)
        var marked: SnapshotTable? = null
        decide(index, id) { stored ->
            val claimed = stored?.source?.let(::tableOf)
            if (claimed == null || claimed.revision != revision) {
                marked = null
                Decision.Keep(false)
            } else {
                val changed = claimed.copyWith(
                    rebuilding = rebuilding ?: claimed.rebuilding,
                    backfilled = backfilled ?: claimed.backfilled,
                )
                marked = changed
                if (changed == claimed) Decision.Keep(true) else Decision.Write(tableDocument(changed), true)
            }
        }
        return marked
    }

    /** The snapshots a rebuild at state [revision] has to rewrite: of another state revision, or marked stale. */
    private fun pendingAt(revision: Int): Query = Query.of { query ->
        query.bool { bool ->
            bool.should(
                Query.of { other ->
                    other.bool {
                        it.mustNot(
                            Query.of { term -> term.term { it.field(STATE_REVISION).value(revision.toLong()) } }
                        )
                    }
                },
                Query.of { stale -> stale.exists { it.field(STALE_VERSION) } },
            ).minimumShouldMatch("1")
        }
    }

    private fun ascending(field: String): SortOptions = SortOptions.of {
        it.field { sort -> sort.field(field).order(SortOrder.Asc) }
    }

    private fun checkContext(ref: AggregateRef) {
        require(ref.type.context == context) { "$ref is not in context $context." }
    }

    /** A document as read: its source and the sequence number and primary term a guarded write of it names. */
    private class Stored(val source: ObjectNode, val seqNo: Long, val primaryTerm: Long)

    /** What a write decides over the stored document: [Write] a document, or [Keep] the stored one; each its result. */
    private sealed interface Decision {
        val result: Boolean

        class Write(val document: ObjectNode, override val result: Boolean) : Decision

        class Keep(override val result: Boolean) : Decision
    }

    /**
     * Reads the document [id] of [index] in real time, lets [decide] choose, and writes what it chose guarded by the
     * read ([Stored.seqNo]); a write another one beat is read and decided again. The decision's result.
     */
    private suspend fun decide(index: String, id: String, decide: (Stored?) -> Decision): Boolean {
        repeat(MAX_ATTEMPTS) {
            val stored = read(index, id)
            when (val decision = decide(stored)) {
                is Decision.Keep -> return decision.result
                is Decision.Write -> if (write(index, id, decision.document, stored)) return decision.result
            }
        }
        throw IllegalStateException(
            "The document [$id] of [$index] kept changing over $MAX_ATTEMPTS attempts to write it."
        )
    }

    private suspend fun read(index: String, id: String): Stored? = whenShardReady { readNow(index, id) }

    private suspend fun readNow(index: String, id: String): Stored? {
        val response = try {
            client.get(
                GetRequest.of { it.index(index).id(id).realtime(true) },
                ObjectNode::class.java
            ).awaitElasticsearch()
        } catch (failure: ElasticsearchException) {
            if (ElasticsearchFailures.isIndexNotFound(failure)) return null
            throw failure
        }
        if (!response.found()) return null
        return Stored(
            checkNotNull(response.source()),
            checkNotNull(response.seqNo()),
            checkNotNull(response.primaryTerm())
        )
    }

    /** Writes [document] in place of [stored], or creates it when [stored] is null; false when another write beat it. */
    private suspend fun write(index: String, id: String, document: ObjectNode, stored: Stored?): Boolean =
        whenShardReady { writeNow(index, id, document, stored) }

    private suspend fun writeNow(index: String, id: String, document: ObjectNode, stored: Stored?): Boolean {
        val request = IndexRequest.of<ObjectNode> { request ->
            request.index(index).id(id).document(document).refresh(refresh)
            if (stored == null) {
                request.opType(OpType.Create)
            } else {
                request.ifSeqNo(stored.seqNo).ifPrimaryTerm(stored.primaryTerm)
            }
            request
        }
        return try {
            client.index(request).awaitElasticsearch()
            true
        } catch (failure: ElasticsearchException) {
            when {
                ElasticsearchFailures.hasStatus(failure, ElasticsearchFailures.CONFLICT) -> false
                // A document Elasticsearch cannot take (a field name it refuses, say) is refused as a value.
                ElasticsearchFailures.hasStatus(failure, ElasticsearchFailures.BAD_REQUEST) ->
                    throw IllegalArgumentException("Elasticsearch refused the document [$id] of [$index].", failure)
                else -> throw failure
            }
        }
    }

    private fun tableOf(source: ObjectNode) = SnapshotTable(
        revision = source.path(REVISION).intValue(),
        rebuilding = source.path(REBUILDING).booleanValue(),
        backfilled = source.path(BACKFILLED).booleanValue(),
    )

    private fun tableDocument(table: SnapshotTable): ObjectNode = JsonNodeFactory.instance.objectNode()
        .put(REVISION, table.revision)
        .put(REBUILDING, table.rebuilding)
        .put(BACKFILLED, table.backfilled)

    internal companion object {
        /** How many times a write is decided again before the store gives up: each retry means another write landed. */
        const val MAX_ATTEMPTS = 128

        const val TABLE_KIND = "snapshot_table"
        const val TABLE_FORMAT = 1
        private const val REVISION = "revision"
        private const val REBUILDING = "rebuilding"
        private const val BACKFILLED = "backfilled"

        val TABLE_DEFINITION: String = """
            {
              "mappings": {
                "_meta": { "wow_index": "$TABLE_KIND", "wow_format": $TABLE_FORMAT },
                "dynamic": "strict",
                "properties": {
                  "$REVISION": { "type": "integer" },
                  "$REBUILDING": { "type": "boolean" },
                  "$BACKFILLED": { "type": "boolean" }
                }
              }
            }
        """.trimIndent()
    }
}
