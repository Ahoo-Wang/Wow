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
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.wire.WireNames
import me.ahoo.wow.elasticsearch.ElasticsearchFailures
import me.ahoo.wow.elasticsearch.ElasticsearchIndices
import me.ahoo.wow.elasticsearch.ElasticsearchNames
import me.ahoo.wow.elasticsearch.awaitElasticsearch
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotDocuments.AGGREGATE_ID
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotDocuments.STALE_VERSION
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotDocuments.STATE_REVISION
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotDocuments.TENANT_ID
import me.ahoo.wow.elasticsearch.whenShardReady
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.StorageRefusedException
import me.ahoo.wow.spi.snapshot.SnapshotFill
import me.ahoo.wow.spi.snapshot.SnapshotRebuildStore
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.snapshot.SnapshotTable
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.ConcurrentHashMap

/**
 * The [SnapshotStore] of one bounded context on Elasticsearch (documentation/designs/03-log-and-snapshot.md §3).
 *
 * Each aggregate type has its own index, `wow_snapshot__<context>__<aggregate>` ([ElasticsearchNames]),
 * holding one document per aggregate, its id the tenant and the aggregate id ([ElasticsearchNames.documentId]), laid
 * out as [ElasticsearchSnapshotDocuments] and [ElasticsearchSnapshotIndex] say. An aggregate type's index is an alias
 * of one concrete index ([ElasticsearchIndices]), made sure of the first time this store touches the type, as [ddl]
 * says ([ElasticsearchSnapshotIndexes]). Every write carries `require_alias`: a write to a missing alias is refused,
 * naming it, instead of having Elasticsearch create an index with a mapping it guesses. Under `auto` the next use
 * creates the pair again while its table has no record; one missing under its record was removed, and the store
 * serves it no more ([SnapshotRebuildStore]'s **A table removed under its record**).
 *
 * Every write is a whole document, guarded by the sequence number of the one it replaces (`if_seq_no`,
 * `if_primary_term`), or created only when there is none (`op_type=create`): the store reads the document in real
 * time, decides as the contract says (version, state revision, stale mark), and writes; a write another one beat is
 * refused by Elasticsearch and decided again. Concurrent saves therefore leave the highest version, and no write is a
 * partial update, which would have Elasticsearch rewrite the `_source` and lose the state's exact numbers.
 *
 * The claimed state revision of each index is a document of the snapshot table index ([ElasticsearchNames.snapshotTables]),
 * keyed by the snapshot index, guarded the same way. Writes become visible to searches as [refresh] says: within the
 * index's refresh interval by default, or, with `wait_for`, once the write returns.
 */
@InternalWowApi
class ElasticsearchSnapshotStore internal constructor(
    private val client: ElasticsearchAsyncClient,
    private val names: ElasticsearchNames,
    private val context: String,
    private val ddl: Ddl = Ddl.AUTO,
    private val refresh: Refresh = Refresh.False,
) : SnapshotStore, SnapshotRebuildStore {
    /** This store rewrites its snapshots in place: it is its own rebuild control plane. */
    override val rebuilds: SnapshotRebuildStore get() = this

    /**
     * Four with `refresh: wait_for`, whose every write returns only once the next refresh makes it visible: the
     * subscription then writes at most four batches a refresh interval, whatever the load, so it keeps as many in flight
     * as it did before the calls were merged across partitions. Otherwise a write returns once indexed, and the default
     * holds.
     */
    override val writesInFlight: Int
        get() = if (refresh == Refresh.WaitFor) WAIT_FOR_WRITES_IN_FLIGHT else SnapshotStore.DEFAULT_WRITES_IN_FLIGHT

    private val indexes = ElasticsearchSnapshotIndexes(ElasticsearchIndices(client), names, context, ddl, ::removedUnder)

    /**
     * Nothing to create up front: each aggregate type's index is created on its first use, since the store does not
     * know the context's aggregate types. Kept for the host, which calls it at startup.
     */
    fun createSchema() = Unit

    /** Makes sure of [aggregate]'s snapshot index, as the store's DDL mode says. */
    suspend fun createIndex(aggregate: String) {
        indexes.of(aggregate)
    }

    /** The refusal of a write to [index], whose alias does not exist. */
    private fun missingAlias(index: String) = MissingAliasException(index)

    /**
     * [write]'s answer, or [removed] when its write missed [aggregate]'s index and its table has a record ([missed]):
     * what the write would have written is the log's, which a refill from it writes again.
     */
    private suspend inline fun <T> unlessRemoved(aggregate: String, removed: T, write: () -> T): T = try {
        write()
    } catch (missing: MissingAliasException) {
        if (missed(aggregate)) removed else throw missing
    }

    /**
     * Whether [aggregate]'s index, which a write missed, was removed under its table's record, then served no more;
     * creating nothing, so that one with no record is created by its next use, as before.
     */
    private suspend fun missed(aggregate: String): Boolean =
        removedUnder(aggregate).also { if (it) indexes.removed(aggregate) }

    /**
     * Whether [aggregate]'s table has a record, its index found missing: it was then removed under it, which its record
     * says from now on ([SnapshotTable.removed]).
     */
    private suspend fun removedUnder(aggregate: String): Boolean {
        val index = indexes.tables()
        var recorded = false
        decide(index, names.snapshots(context, aggregate)) { stored ->
            val table = stored?.source?.let(ElasticsearchSnapshotTables::tableOf)
            recorded = table != null
            if (table == null || table.removed) {
                Decision.Keep(true)
            } else {
                Decision.Write(ElasticsearchSnapshotTables.document(table.copyWith(removed = true)), true)
            }
        }
        if (recorded) {
            logger.warn {
                "The Elasticsearch alias [${names.snapshots(context, aggregate)}] is missing under its table's record " +
                    "in [$index]: the snapshots it held were removed, and the store serves it no more."
            }
        }
        return recorded
    }

    override suspend fun save(snapshot: SnapshotRecord): Boolean {
        checkContext(snapshot.ref)
        val aggregate = snapshot.ref.type.name
        val index = indexes.of(aggregate) ?: return false
        val id = ElasticsearchNames.documentId(snapshot.ref)
        return unlessRemoved(aggregate, false) { decide(index, id) { stored -> saveOver(snapshot, stored) } }
    }

    /** What a save of [snapshot] decides over [stored]: the version guard, a stale mark for a higher state revision. */
    private fun saveOver(snapshot: SnapshotRecord, stored: Stored?): Decision = when {
        stored == null -> Decision.Write(ElasticsearchSnapshotDocuments.document(snapshot), true)
        ElasticsearchSnapshotDocuments.versionOf(stored.source) >= snapshot.version -> Decision.Keep(false)
        ElasticsearchSnapshotDocuments.stateRevisionOf(stored.source) > snapshot.stateRevision -> {
            // Refused: the stored snapshot is of a higher state revision, so the log has moved past it.
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
     * Saves [snapshots]: in the rounds [SnapshotSaveOutcome.rounds] gives, each round's snapshots of
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
            round.groupBy { snapshots[it].ref.type.name }.forEach { (aggregate, positions) ->
                val index = indexes.of(aggregate)
                if (index == null) {
                    positions.forEach { outcomes[it] = SnapshotSaveOutcome.of(false) }
                } else {
                    saveRound(index, positions, snapshots, outcomes)
                }
            }
        }
        // A write that missed its index, found removed under its table's record, wrote nothing the log does not hold.
        snapshots.indices.filter { (outcomes[it] as? SnapshotSaveOutcome.Failed)?.cause is MissingAliasException }
            .groupBy { snapshots[it].ref.type.name }
            .forEach { (aggregate, positions) ->
                if (missed(aggregate)) positions.forEach { outcomes[it] = SnapshotSaveOutcome.of(false) }
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
            bulk.index(index).requireAlias(true).refresh(refresh).operations(writes.map(::operationOf))
        }
        val items = whenShardReady { client.bulk(request).awaitElasticsearch() }.items()
        val beaten = ArrayList<Int>()
        items.forEachIndexed { at, item ->
            val write = writes[at]
            val error = item.error()
            when {
                error == null -> outcomes[write.position] = SnapshotSaveOutcome.of(write.decision.result)
                item.status() == ElasticsearchFailures.CONFLICT -> beaten += write.position
                ElasticsearchFailures.isMissingAlias(item.status(), error.type()) -> {
                    indexes.forget(index)
                    outcomes[write.position] = SnapshotSaveOutcome.Failed(missingAlias(index))
                }
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
        val aggregate = snapshot.ref.type.name
        val index = indexes.of(aggregate) ?: return false
        val id = ElasticsearchNames.documentId(snapshot.ref)
        return unlessRemoved(aggregate, false) {
            decide(index, id) { stored ->
                when {
                    stored == null -> Decision.Write(ElasticsearchSnapshotDocuments.document(snapshot), true)
                    ElasticsearchSnapshotDocuments.versionOf(stored.source) > snapshot.version -> Decision.Keep(false)
                    else -> Decision.Write(replacement(snapshot, stored.source), true)
                }
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
        val index = indexes.of(ref.type.name) ?: return false
        return unlessRemoved(ref.type.name, false) { markStaleIn(index, ref) }
    }

    private suspend fun markStaleIn(index: String, ref: AggregateRef): Boolean {
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
        val index = indexes.of(ref.type.name) ?: return null
        val stored = read(index, ElasticsearchNames.documentId(ref)) ?: return null
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
        val index = indexes.of(type.name) ?: return emptyList()
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
        val stored = read(indexes.tables(), names.snapshots(context, type.name)) ?: return null
        val table = ElasticsearchSnapshotTables.tableOf(stored.source)
        // A record says removed whatever is behind the alias now: an index put back is not served.
        if (table.removed) {
            indexes.removed(type.name)
            return table
        }
        // Its index is made sure of once it has a record: one found missing records it removed.
        return if (indexes.of(type.name) != null) table else table.copyWith(removed = true)
    }

    override suspend fun claim(type: AggregateType, revision: Int, fill: SnapshotFill, lower: Boolean): SnapshotTable {
        require(type.context == context) { "$type is not in context $context." }
        require(revision >= 1) { "A state revision starts at 1." }
        // The index before the record: an index missing under a record was removed, not yet created.
        indexes.of(type.name)
        val index = indexes.tables()
        val id = names.snapshots(context, type.name)
        decide(index, id) { stored ->
            when {
                stored == null -> {
                    val created = SnapshotTable(revision, rebuilding = revision != 1, backfilled = false, fill = fill)
                    Decision.Write(ElasticsearchSnapshotTables.document(created), true)
                }
                else -> {
                    val claimed = ElasticsearchSnapshotTables.tableOf(stored.source)
                    val changes = if (lower) claimed.revision != revision else claimed.revision < revision
                    if (changes) {
                        Decision.Write(ElasticsearchSnapshotTables.document(claimed.copyWith(revision = revision, rebuilding = true)), true)
                    } else {
                        Decision.Keep(true)
                    }
                }
            }
        }
        return ElasticsearchSnapshotTables.tableOf(checkNotNull(read(index, id)).source)
    }

    override suspend fun mark(
        type: AggregateType,
        revision: Int,
        rebuilding: Boolean?,
        backfilled: Boolean?,
    ): SnapshotTable? {
        require(type.context == context) { "$type is not in context $context." }
        val index = indexes.tables()
        val id = names.snapshots(context, type.name)
        var marked: SnapshotTable? = null
        decide(index, id) { stored ->
            val claimed = stored?.source?.let(ElasticsearchSnapshotTables::tableOf)
            if (claimed == null || claimed.revision != revision) {
                marked = null
                Decision.Keep(false)
            } else {
                val changed = claimed.copyWith(
                    rebuilding = rebuilding ?: claimed.rebuilding,
                    backfilled = backfilled ?: claimed.backfilled,
                )
                marked = changed
                if (changed == claimed) Decision.Keep(true) else Decision.Write(ElasticsearchSnapshotTables.document(changed), true)
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
            request.index(index).requireAlias(true).id(id).document(document).refresh(refresh)
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
            if (ElasticsearchFailures.hasStatus(failure, ElasticsearchFailures.CONFLICT)) {
                return false
            }
            throw when {
                ElasticsearchFailures.isIndexNotFound(failure) -> {
                    indexes.forget(index)
                    missingAlias(index)
                }
                // A document Elasticsearch cannot take (a field name it refuses, say) is refused as a value.
                ElasticsearchFailures.hasStatus(failure, ElasticsearchFailures.BAD_REQUEST) ->
                    StorageRefusedException("Elasticsearch refused the document [$id] of [$index].", failure)
                else -> failure
            }
        }
    }

    internal companion object {
        /** [writesInFlight] with `refresh: wait_for`. */
        const val WAIT_FOR_WRITES_IN_FLIGHT = 4

        /** How many times a write is decided again before the store gives up: each retry means another write landed. */
        const val MAX_ATTEMPTS = 128
    }
}

private val logger = KotlinLogging.logger {}

/** A write to [index], whose alias does not exist. */
private class MissingAliasException(index: String) : IllegalStateException(ElasticsearchIndices.missingAlias(index))
