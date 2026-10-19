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

import me.ahoo.wow.mongo.ExactBson
import me.ahoo.wow.query.QueryExecutionException
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.SnapshotFields
import org.bson.Document
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * The snapshot document `MongoSnapshotStore` writes, as the query side reads it (decisions D128, D161): each system
 * field of a snapshot record is a top-level field under its stored name ([PHYSICAL]), the state is the document
 * `state`, the tags the document `tags` of string arrays, and the operators the documents `{id, tenantId,
 * crossScope}` or null (decision D163; a snapshot written before them has neither field), and the schedules the
 * document of each rule by its key, `{type, ...}` (decision D198). `_id` is the store's own and never part of a record.
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
        SnapshotFields.TAGS to "tags",
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
        "tags" to OBJECT_TYPES,
        "operator" to OBJECT_TYPES,
        "first_operator" to OBJECT_TYPES,
        "schedules" to OBJECT_TYPES,
        "state" to OBJECT_TYPES,
    )

    /** The tenant, then the aggregate ID: the unique key of a snapshot. */
    override val tieBreakers: List<String> = listOf(TENANT_ID, AGGREGATE_ID)

    override val edgeTieBreakers: List<String> = listOf(AGGREGATE_ID, TENANT_ID)

    /** [path], a logical path of the snapshot model, where the document stores it: its root renamed. */
    override fun physical(path: QueryPathTemplate): QueryPathTemplate = renameRoot(path, PHYSICAL)

    /** [path], a path of the document, as the logical path it stores. */
    override fun logical(path: QueryPathTemplate): QueryPathTemplate = renameRoot(path, LOGICAL)

    /**
     * The snapshot record of [document], the shape of `SnapshotRecord.toQueryRecord()`: each record field the
     * document holds, in the record's order, its values as JSON (a decimal exactly, decision D122). A field a native
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
 * ([MongoSnapshotDocuments], decision D161) or a stream of the log ([MongoEventDocuments], decisions D162, D235).
 */
internal interface MongoQueryDocuments {
    /** The BSON types of the system fields, by stored name, which no validator need declare. */
    val systemTypes: Map<String, Set<String>>

    /** The stored fields, ascending, that end a list's or an offset page's sort so its order is total. */
    val tieBreakers: List<String>

    /** The stored fields, ascending, that order the documents tied on a `FIRST` / `LAST` metric's ordering field. */
    val edgeTieBreakers: List<String>

    /** [path], a logical path of the model, where the document stores it. */
    fun physical(path: QueryPathTemplate): QueryPathTemplate

    /** [path], a path of the document, as the logical path it stores. */
    fun logical(path: QueryPathTemplate): QueryPathTemplate

    /** The record of [document]; a field a native projection left out is absent. */
    fun record(document: Document): ObjectNode
}

/** [path] with its first segment renamed as [names] says, when it names one. */
internal fun renameRoot(path: QueryPathTemplate, names: Map<String, String>): QueryPathTemplate {
    val root = path.segments.firstOrNull() as? QueryPathSegment.Property ?: return path
    val renamed = names[root.name] ?: return path
    return QueryPathTemplate(listOf(QueryPathSegment.Property(renamed)) + path.segments.drop(1))
}
