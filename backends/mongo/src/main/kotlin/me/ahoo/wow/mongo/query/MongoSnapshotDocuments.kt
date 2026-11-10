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

package me.ahoo.wow.mongo.query

import com.mongodb.client.model.Filters
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.mongo.ExactBson
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.spi.json.query.SnapshotFields
import org.bson.Document
import org.bson.conversions.Bson
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * The snapshot document `MongoSnapshotStore` writes, as the query side reads it: each system
 * field of a snapshot record is a top-level field under its stored name ([PHYSICAL]), the state is the document
 * `state`, the access attributes the document `access` (absent from a snapshot of a version that recorded tags), and
 * the operators the documents `{id, tenantId,
 * crossScope}` or null (a snapshot written before them has neither field), and the schedules the
 * document of each rule by its key, `{type, ...}`. `_id` is the store's own and never part of a record.
 */
internal object MongoSnapshotDocuments : MongoQueryDocuments {
    /** The document's own key, which no query reads. */
    const val ID = "_id"

    /** The stored name of each top-level field of a snapshot record, in the record's order. */
    val PHYSICAL: Map<String, String> = linkedMapOf(
        SnapshotFields.TENANT_ID to "tenant_id",
        SnapshotFields.AGGREGATE_ID to "aggregate_id",
        SnapshotFields.VERSION to "version",
        SnapshotFields.OWNER_ID to "owner_id",
        SnapshotFields.SPACE_ID to "space_id",
        SnapshotFields.DELETED to "deleted",
        SnapshotFields.ACCESS to "access",
        SnapshotFields.FIRST_EVENT_TIME to "first_event_time",
        SnapshotFields.LAST_EVENT_TIME to "last_event_time",
        SnapshotFields.OPERATOR to "operator",
        SnapshotFields.FIRST_OPERATOR to "first_operator",
        SnapshotFields.SCHEDULES to "schedules",
        SnapshotFields.STATE to "state",
    )

    /** The record field of each stored top-level field. */
    val LOGICAL: Map<String, String> = PHYSICAL.entries.associate { (logical, physical) -> physical to logical }

    const val TENANT_ID = "tenant_id"
    const val AGGREGATE_ID = "aggregate_id"
    const val DELETED = "deleted"

    private val nodes: JsonNodeFactory = JsonNodeFactory.instance

    /** The BSON types the snapshot store writes for the system fields, which no validator need declare. */
    override val systemTypes: Map<String, Set<String>> = mapOf(
        "tenant_id" to STRING_TYPES,
        "aggregate_id" to STRING_TYPES,
        "version" to setOf("int"),
        "owner_id" to STRING_TYPES,
        "space_id" to STRING_TYPES,
        "deleted" to BOOLEAN_TYPES,
        "first_event_time" to setOf("long"),
        "last_event_time" to setOf("long"),
        "access" to OBJECT_TYPES,
        "operator" to OBJECT_TYPES,
        "first_operator" to OBJECT_TYPES,
        "schedules" to OBJECT_TYPES,
        "state" to OBJECT_TYPES,
    )

    /** The key of a snapshot, its `_id`: the tenant, then the aggregate ID. */
    override val tieBreakers: List<String> = listOf(ID)

    /** [tenantId]'s snapshots, one range of their `_id`, which the default order then reads in index order. */
    override fun keysOf(tenantId: String): Bson = MongoSnapshotStore.tenantKeys(tenantId)

    override val edgeTieBreakers: List<String> = listOf(AGGREGATE_ID, TENANT_ID)

    /**
     * A cursor's sort, which admission ends with the tenant and the aggregate ID, read in the order of the `_id`
     * index: those two fields, in one direction, are `_id` (`{t, a}`, which orders as they do) in that direction, and
     * their position values the key they make (a value there that is not a string is an invalid cursor). Without it
     * every page would sort the tenant's documents in memory.
     */
    override fun keyed(sort: List<Sort>): MongoKeyedSort {
        val tenant = sort.getOrNull(sort.size - 2)
        val aggregate = sort.lastOrNull()
        if (tenant?.field?.path != TENANT_ID || aggregate?.field?.path != AGGREGATE_ID ||
            tenant.direction != aggregate.direction
        ) {
            return super.keyed(sort)
        }
        return MongoKeyedSort(sort.dropLast(2) + Sort(QueryField(ID), tenant.direction)) { values ->
            val (tenantId, aggregateId) = values.takeLast(2)
            // A position of another query or a damaged one holds something else there: a caller's bad cursor.
            if (tenantId !is String || aggregateId !is String) throw QueryViolation.InvalidCursor.rejection()
            values.dropLast(2) + MongoSnapshotStore.keyOf(tenantId, aggregateId)
        }
    }

    /** [path], a logical path of the snapshot model, where the document stores it: its root renamed. */
    override fun physical(path: QueryPathTemplate): QueryPathTemplate = renameRoot(path, PHYSICAL)

    /** [path], a path of the document, as the logical path it stores. */
    override fun logical(path: QueryPathTemplate): QueryPathTemplate = renameRoot(path, LOGICAL)

    /**
     * The snapshot record of [document], the shape of `SnapshotRecord.toQueryRecord()`: each record field the
     * document holds, in the record's order, its values as JSON (a decimal exactly). A field a native
     * projection left out is absent.
     */
    override fun record(document: Document): ObjectNode = nodes.objectNode().apply {
        PHYSICAL.forEach { (logical, physical) ->
            if (document.containsKey(physical)) set(logical, ExactBson.node(document[physical]))
        }
    }

    /**
     * [path], a physical path admission resolved, checked before it reaches a MongoDB document: every segment is
     * non-empty, holds no NUL and does not start with `$`, so no path can name an operator (`$where`, `$function`)
     * or address anything but a field. The query protocol's field pattern already rules these out; this is the
     * backend's own guard.
     */
    fun checked(path: String): String {
        val valid = path.isNotEmpty() && path.split('.').all { segment ->
            segment.isNotEmpty() && !segment.startsWith('$') && '\u0000' !in segment
        }
        if (!valid) throw QueryExecutionException("A field path MongoDB cannot address reached the backend.")
        return path
    }
}

/**
 * How a query backend reads one kind of MongoDB document as the records of a query model: a snapshot
 * ([MongoSnapshotDocuments]) or a stream of the log ([MongoEventDocuments]).
 */
internal interface MongoQueryDocuments {
    /** The BSON types of the system fields, by stored name, which no validator need declare. */
    val systemTypes: Map<String, Set<String>>

    /** The stored fields that end a list's or an offset page's sort so its order is total, in its last field's direction. */
    val tieBreakers: List<String>

    /** The stored fields, ascending, that order the documents tied on a `FIRST` / `LAST` metric's ordering field. */
    val edgeTieBreakers: List<String>

    /**
     * The condition the collection's documents of this kind meet, which every query adds to its filter: null when
     * every document of the collection is one (a log collection also holds placeholders).
     */
    val scope: Bson? get() = null

    /**
     * The documents of [tenantId] as a condition on their key, which a query of one tenant adds so its tie-breakers
     * are read from the key's index; null when the key is not the tenant's.
     */
    fun keysOf(tenantId: String): Bson? = null

    /**
     * The documents of [tenantId]'s aggregates [aggregateIds] as a condition on what leads their key's index, which a
     * query pinned to them adds; null when nothing more than their fields leads it.
     */
    fun keysOf(tenantId: String, aggregateIds: Collection<String>): Bson? = null

    /** The stored fields no record reads, which a native projection that keeps the rest leaves out. */
    val unread: List<String> get() = emptyList()

    /** [path], a logical path of the model, where the document stores it. */
    fun physical(path: QueryPathTemplate): QueryPathTemplate

    /** [path], a path of the document, as the logical path it stores. */
    fun logical(path: QueryPathTemplate): QueryPathTemplate

    /** The record of [document]; a field a native projection left out is absent. */
    fun record(document: Document): ObjectNode

    /** [sort], a cursor's physical sort, as MongoDB reads it best: by default as it is. */
    fun keyed(sort: List<Sort>): MongoKeyedSort = MongoKeyedSort(sort) { it }
}

/**
 * A cursor's sort as a keyset page sorts and conditions on it ([sort]), and the position values of the cursor's own
 * sort as that sort's ([values]).
 */
internal class MongoKeyedSort(val sort: List<Sort>, val values: (List<Any?>) -> List<Any?>)

/** [filter] within [scope], when there is one. */
internal fun scoped(filter: Bson, scope: Bson?): Bson = scope?.let { Filters.and(it, filter) } ?: filter

/** [path] with its first segment renamed as [names] says, when it names one. */
internal fun renameRoot(path: QueryPathTemplate, names: Map<String, String>): QueryPathTemplate {
    val root = path.segments.firstOrNull() as? QueryPathSegment.Property ?: return path
    val renamed = names[root.name] ?: return path
    return QueryPathTemplate(listOf(QueryPathSegment.Property(renamed)) + path.segments.drop(1))
}
