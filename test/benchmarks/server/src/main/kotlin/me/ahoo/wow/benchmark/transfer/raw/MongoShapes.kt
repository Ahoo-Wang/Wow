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

package me.ahoo.wow.benchmark.transfer.raw

import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOneModel
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.WriteModel
import org.bson.BsonArray
import org.bson.BsonBoolean
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonString
import org.bson.Document
import org.bson.conversions.Bson

/**
 * A snapshot's save on MongoDB in each shape its store cost is priced in: [Update.M1] Wow's own filter and modifier
 * (MongoSnapshotStore), and the candidate shapes against it.
 */
object MongoShapes {
    /**
     * A snapshot's update, the shapes its store cost is priced in: [M1] Wow's batch save today (MongoSnapshotWrites:
     * its guard with no stale mark, `stale_version: {$exists: false}`, in the one filter document, and a modifier
     * `$set` of its fields); [M0] the aggregation pipeline `$set` of every field as `$literal` and the stale mark's
     * `$cond` it sent before; [M2] M1 with `$setOnInsert` for what never changes; [M3] a `replaceOne` of the document.
     */
    enum class Update { M0, M1, M2, M3 }

    /** [command]'s snapshot save in the shape of [update], guarded by a lower version of no higher state revision. */
    fun modelOf(
        template: Document,
        command: Aggregates.Command,
        update: Update,
        bigState: Boolean,
    ): WriteModel<Document> {
        val tenant = template.getString(TENANT_ID)
        val revision = template.getInteger(STATE_REVISION, 1)
        val fields = Document(template).apply {
            remove("_id")
            remove(STALE_VERSION)
            this[AGGREGATE_ID] = command.id
            this[VERSION] = command.version
            this["last_event_time"] = System.currentTimeMillis()
            if (bigState) this[STATE] = BIG_STATE
        }
        val guards = listOf(Filters.lt(VERSION, command.version), Filters.lte(STATE_REVISION, revision))
        val key = Filters.eq("_id", keyOf(tenant, command.id))
        return when (update) {
            Update.M0 -> UpdateOneModel(
                guardOf(tenant, command, revision),
                listOf<Bson>(Document("\$set", pipelineSet(fields, command.version))),
                UPSERT,
            )
            Update.M1 -> UpdateOneModel(
                (guardOf(tenant, command, revision) as BsonDocument).append(
                    STALE_VERSION,
                    BsonDocument("\$exists", BsonBoolean.FALSE),
                ),
                Document("\$set", fields.without(TENANT_ID, AGGREGATE_ID)),
                UPSERT,
            )
            Update.M2 -> UpdateOneModel(
                Filters.and(listOf(key) + guards),
                Document("\$set", fields.without(*ON_INSERT.toTypedArray()))
                    .append("\$setOnInsert", Document(fields.filterKeys { it in ON_INSERT })),
                UPSERT,
            )
            Update.M3 -> ReplaceOneModel(Filters.and(listOf(key) + guards), fields, ReplaceOptions().upsert(true))
        }
    }

    /** Wow's save filter (MongoSnapshotStore): the key, the tenant and the id, a lower version, no higher revision. */
    private fun guardOf(tenant: String, command: Aggregates.Command, revision: Int): Bson =
        BsonDocument("_id", BsonDocument(KEY_TENANT, BsonString(tenant)).append(KEY_AGGREGATE, BsonString(command.id)))
            .append(TENANT_ID, BsonString(tenant))
            .append(AGGREGATE_ID, BsonString(command.id))
            .append(VERSION, BsonDocument("\$lt", BsonInt32(command.version)))
            .append(STATE_REVISION, BsonDocument("\$lte", BsonInt32(revision)))

    /** Wow's pipeline `$set`: every field as `$literal`, and the stale mark kept only above [version]. */
    private fun pipelineSet(fields: Document, version: Int): Document = Document().apply {
        for ((field, value) in fields.without(TENANT_ID, AGGREGATE_ID)) this[field] = Document("\$literal", value)
        val unmarked = BsonDocument("\$lte", BsonArray(listOf(BsonString("\$$STALE_VERSION"), BsonInt32(version))))
        this[STALE_VERSION] = Document("\$cond", listOf(unmarked, "\$\$REMOVE", "\$$STALE_VERSION"))
    }

    private fun Document.without(vararg keys: String): Document = Document(filterKeys { it !in keys })

    fun keyOf(tenant: String, id: String) = Document(KEY_TENANT, tenant).append(KEY_AGGREGATE, id)

    const val TENANT_ID = "tenant_id"
    const val AGGREGATE_ID = "aggregate_id"
    const val VERSION = "version"
    const val STATE = "state"
    private const val STATE_REVISION = "state_revision"
    private const val STALE_VERSION = "stale_version"
    private const val KEY_TENANT = "t"
    private const val KEY_AGGREGATE = "a"
    private const val BIG_STATE_BYTES = 4_096
    private val UPSERT = UpdateOptions().upsert(true)

    /** What never changes after an aggregate's first commit, set only on the upsert's insert in [Update.M2]. */
    private val ON_INSERT = listOf("first_event_time", "first_operator", TENANT_ID, AGGREGATE_ID)

    /** A state of about 4 KB: notes of fixed text, so no store compresses them away. */
    val BIG_STATE = Document(
        "notes",
        List(BIG_STATE_BYTES / 64) { index -> (0 until 64).map { 'a' + (index * 7 + it) % 26 }.joinToString("") },
    )
}
