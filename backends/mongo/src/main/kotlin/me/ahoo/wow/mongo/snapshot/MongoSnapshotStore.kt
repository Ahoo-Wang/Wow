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

package me.ahoo.wow.mongo.snapshot

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.mongo.ExactBson
import me.ahoo.wow.mongo.MongoFormat
import me.ahoo.wow.mongo.VirtualThreads
import me.ahoo.wow.mongo.conditionalUpsert
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import org.bson.Document
import org.bson.conversions.Bson

/**
 * The [SnapshotStore] of one bounded context on MongoDB (documentation/designs/v10/03-log-and-snapshot.md §3).
 *
 * One collection holds one document per aggregate, keyed by a unique index on (tenant, aggregate name, aggregate id).
 * The state and the tags are BSON documents, so the snapshot can be queried in place as the default read model; the
 * state's decimals are `Decimal128`, exact ([ExactBson]).
 * A save is one conditional upsert whose filter carries the version guard (`version < new`): no transaction, and
 * concurrent saves leave the highest version (see [conditionalUpsert]).
 *
 * Calls run on [dispatcher], virtual threads by default (decision D29). A failure of the database is a defect and is
 * thrown: a snapshot is a cache, so the caller may drop it and rebuild later.
 */
class MongoSnapshotStore(
    client: MongoClient,
    databaseName: String,
    private val context: String,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
) : SnapshotStore {
    private val database = client.getDatabase(databaseName)
    private val name = "wow_snapshot_" + context.lowercase().replace(Regex("[^a-z0-9_]"), "_")
    private val snapshots: MongoCollection<Document> = database.getCollection(name)

    /** Creates the key index when missing; refuses snapshots recorded in another format version ([MongoFormat]). */
    fun createSchema() {
        MongoFormat.claim(database, name, FORMAT)
        snapshots.createIndex(
            Indexes.ascending(TENANT_ID, AGGREGATE_NAME, AGGREGATE_ID),
            IndexOptions().unique(true).name(SNAPSHOT_KEY),
        )
    }

    override suspend fun save(snapshot: SnapshotRecord): Boolean = withContext(dispatcher) {
        require(snapshot.ref.type.context == context) { "${snapshot.ref} is not in context $context." }
        val guarded = Filters.and(keyFilter(snapshot.ref), Filters.lt(VERSION, snapshot.version))
        val update = Updates.combine(
            Updates.set(VERSION, snapshot.version),
            Updates.set(SCHEMA_FINGERPRINT, snapshot.schemaFingerprint),
            Updates.set(STATE, ExactBson.document(snapshot.state)),
            Updates.set(OWNER_ID, snapshot.scope.ownerId),
            Updates.set(SPACE_ID, snapshot.scope.spaceId),
            Updates.set(DELETED, snapshot.deleted),
            Updates.set(FIRST_EVENT_TIME, snapshot.firstEventTime),
            Updates.set(LAST_EVENT_TIME, snapshot.lastEventTime),
            Updates.set(TAGS, Document(snapshot.tags)),
        )
        conditionalUpsert { upsert ->
            val result = snapshots.updateOne(guarded, update, UpdateOptions().upsert(upsert))
            result.matchedCount > 0 || result.upsertedId != null
        }
    }

    override suspend fun load(ref: AggregateRef): SnapshotRecord? = withContext(dispatcher) {
        require(ref.type.context == context) { "$ref is not in context $context." }
        snapshots.find(keyFilter(ref)).firstOrNull()?.let { snapshotOf(it) }
    }

    private fun keyFilter(ref: AggregateRef): Bson = Filters.and(
        Filters.eq(TENANT_ID, ref.tenantId),
        Filters.eq(AGGREGATE_NAME, ref.type.name),
        Filters.eq(AGGREGATE_ID, ref.id),
    )

    private fun snapshotOf(document: Document): SnapshotRecord {
        val tags = document.get(TAGS, Document::class.java)
        return SnapshotRecord(
            ref = AggregateType(context, document.getString(AGGREGATE_NAME))
                .ref(document.getString(TENANT_ID), document.getString(AGGREGATE_ID)),
            version = document.getInteger(VERSION),
            schemaFingerprint = document.getString(SCHEMA_FINGERPRINT),
            state = ExactBson.json(document.get(STATE, Document::class.java)),
            scope = Scope(document.getString(OWNER_ID), document.getString(SPACE_ID)),
            deleted = document.getBoolean(DELETED),
            firstEventTime = document.getLong(FIRST_EVENT_TIME),
            lastEventTime = document.getLong(LAST_EVENT_TIME),
            tags = tags.keys.associateWith { tags.getList(it, String::class.java).toList() },
        )
    }

    private companion object {
        /** The version of this layout (decision D59). */
        const val FORMAT = 1

        const val SNAPSHOT_KEY = "snapshot_key"
        const val TENANT_ID = "tenant_id"
        const val AGGREGATE_NAME = "aggregate_name"
        const val AGGREGATE_ID = "aggregate_id"
        const val VERSION = "version"
        const val SCHEMA_FINGERPRINT = "schema_fingerprint"
        const val STATE = "state"
        const val OWNER_ID = "owner_id"
        const val SPACE_ID = "space_id"
        const val DELETED = "deleted"
        const val FIRST_EVENT_TIME = "first_event_time"
        const val LAST_EVENT_TIME = "last_event_time"
        const val TAGS = "tags"
    }
}
