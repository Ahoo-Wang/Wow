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

import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.mongo.ExactBson
import me.ahoo.wow.mongo.MongoAccess
import me.ahoo.wow.mongo.MongoOperators
import me.ahoo.wow.mongo.MongoRules
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore.Companion.ACCESS
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore.Companion.AGGREGATE_ID
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore.Companion.DELETED
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore.Companion.FIRST_EVENT_TIME
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore.Companion.FIRST_OPERATOR
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore.Companion.ID
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore.Companion.LAST_EVENT_TIME
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore.Companion.OPERATOR
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore.Companion.OWNER_ID
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore.Companion.SCHEDULES
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore.Companion.SCHEMA_FINGERPRINT
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore.Companion.SPACE_ID
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore.Companion.STALE_VERSION
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore.Companion.STATE
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore.Companion.STATE_REVISION
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore.Companion.TENANT_ID
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore.Companion.VERSION
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore.Companion.keyOf
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.bson.BsonBoolean
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonInt64
import org.bson.BsonNull
import org.bson.BsonString
import org.bson.BsonValue

/**
 * The writes of a [MongoSnapshotStore]: a snapshot's guard and its update, one for a save alone and for a batch alike.
 *
 * - **The update** is a modifier `$set` of every field a snapshot has ([fieldsOf]), so a value is always written as it
 *   is, whatever it reads like, and a field name holding dots or dollars too. An upsert's insert takes the key's
 *   fields from the filter's equalities, so the stored document is the same, field for field, however it was written.
 * - **The guard** ([guardOf]): the key, the key's two fields (the shard key of a sharded collection), a lower stored
 *   version, of the same or a lower state revision. A write of an older or the same version matches nothing.
 * - **A batch's guard** ([unmarkedGuardOf]) adds that the document carries no stale mark, so that one update both
 *   writes the snapshot and leaves no mark behind. A marked document matches nothing either: its upsert meets the key,
 *   and the snapshot is saved alone ([MongoSnapshotStore]), whose write reads the mark and clears it when it is of a
 *   version up to its own. A mark exists only while a rebuild has an aggregate to rewrite, or after a save refused for
 *   a higher state revision; the aggregate's next save clears it.
 */
internal object MongoSnapshotWrites {
    /** The filter of [snapshot]'s save, as BSON. */
    fun guardOf(snapshot: SnapshotRecord): BsonDocument = BsonDocument(ID, keyOf(snapshot.ref))
        .append(TENANT_ID, BsonString(snapshot.ref.tenantId))
        .append(AGGREGATE_ID, BsonString(snapshot.ref.id))
        .append(VERSION, BsonDocument("\$lt", BsonInt32(snapshot.version)))
        .append(STATE_REVISION, BsonDocument("\$lte", BsonInt32(snapshot.stateRevision)))

    /** [guardOf] over a document that carries no stale mark: the filter of [snapshot]'s write in a batch. */
    fun unmarkedGuardOf(snapshot: SnapshotRecord): BsonDocument =
        guardOf(snapshot).append(STALE_VERSION, BsonDocument("\$exists", BsonBoolean.FALSE))

    /** The update of [snapshot]'s save: every field set to its value. */
    fun updateOf(snapshot: SnapshotRecord): BsonDocument = BsonDocument("\$set", fieldsOf(snapshot))

    /**
     * The fields a save writes of [snapshot], as BSON: the driver writes them without rendering or looking a codec up
     * (the state included, [ExactBson.bson]).
     */
    fun fieldsOf(snapshot: SnapshotRecord): BsonDocument = BsonDocument()
        .append(VERSION, BsonInt32(snapshot.version))
        .append(SCHEMA_FINGERPRINT, BsonString(snapshot.schemaFingerprint))
        .append(STATE, ExactBson.bson(snapshot.state))
        .append(OWNER_ID, stringOf(snapshot.scope.ownerId))
        .append(SPACE_ID, stringOf(snapshot.scope.spaceId))
        .append(DELETED, BsonBoolean.valueOf(snapshot.deleted))
        .append(FIRST_EVENT_TIME, BsonInt64(snapshot.firstEventTime))
        .append(LAST_EVENT_TIME, BsonInt64(snapshot.lastEventTime))
        .append(ACCESS, MongoAccess.bson(snapshot.access))
        .append(OPERATOR, MongoOperators.bson(snapshot.operator))
        .append(FIRST_OPERATOR, MongoOperators.bson(snapshot.firstOperator))
        .append(SCHEDULES, schedulesOf(snapshot.schedules))
        .append(STATE_REVISION, BsonInt32(snapshot.stateRevision))

    private fun stringOf(value: String?): BsonValue = value?.let(::BsonString) ?: BsonNull.VALUE

    private fun schedulesOf(schedules: Map<String, ScheduleRule>) = BsonDocument().apply {
        schedules.forEach { (name, rule) -> put(name, MongoRules.bson(rule)) }
    }
}
