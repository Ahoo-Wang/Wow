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

package me.ahoo.wow.mongo.log

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import kotlinx.coroutines.CoroutineDispatcher
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.mongo.conditionalUpsert
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.AGGREGATE_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.OWNER_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.REQUEST_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.SPACE_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.TENANT_ID
import me.ahoo.wow.mongo.mongo
import me.ahoo.wow.spi.log.CreationClaim
import org.bson.Document

/**
 * The claims of the creations that name no aggregate ([me.ahoo.wow.spi.log.EventLog.claimCreation]), one collection per
 * aggregate type beside its log ([AggregateCollection.claims]): `{_id: {t: tenant_id, r: request_id}, tenant_id,
 * request_id, aggregate_id, owner_id, space_id}`, one document per request, the scope's ids only where it has them.
 *
 * The log's request key leads with the partition, so that the log can be sharded by it, and two attempts of one
 * request under ids of two partitions would both commit. The claim keeps them to one aggregate: the first attempt's
 * upsert inserts the id it drew, and every later one gets that id back, whatever it drew. The claim is written by a
 * majority within the append timeout, as the appends are: a claim a failover rolled back would let a retry claim again
 * while an attempt of the request may still commit, and both could then commit, two aggregates of one request.
 *
 * A claim is never removed, since no bound holds an attempt of its request; it is about 100 bytes per creation. A
 * request committed without a claim (a log an earlier pre-release wrote, a `migrate-data` import, a restore of the log
 * from a later snapshot than its claims) is found by the log's lookup, which admission runs before it claims.
 */
internal class MongoCreationClaims(
    private val dispatcher: CoroutineDispatcher,
    private val collections: MongoLogCollections,
) {
    /** [claim] when the request has none, inserted; else the request's claim. One single-document write. */
    suspend fun claim(type: AggregateType, tenantId: String, requestId: String, claim: CreationClaim): CreationClaim =
        dispatcher.mongo {
            val claims = collections.of(type).claims
            // The `_id` with the shard key's two fields, so the write routes to the one shard the claim lives on.
            val key = Filters.and(
                Filters.eq(ID, Document(TENANT, tenantId).append(REQUEST, requestId)),
                Filters.eq(TENANT_ID, tenantId),
                Filters.eq(REQUEST_ID, requestId),
            )
            val inserted = Updates.combine(
                listOfNotNull(
                    Updates.setOnInsert(AGGREGATE_ID, claim.aggregateId),
                    claim.scope.ownerId?.let { Updates.setOnInsert(OWNER_ID, it) },
                    claim.scope.spaceId?.let { Updates.setOnInsert(SPACE_ID, it) },
                ),
            )
            // Concurrent first claims: one inserts, the others hit the `_id` and read the one that did.
            val held = conditionalUpsert { upsert ->
                claims.findOneAndUpdate(key, inserted, FindOneAndUpdateOptions().upsert(upsert).returnDocument(AFTER))
            }
            CreationClaim.of(
                held!!.getString(AGGREGATE_ID),
                Scope(held.getString(OWNER_ID), held.getString(SPACE_ID)),
            )
        }

    private companion object {
        const val TENANT = "t"
        const val REQUEST = "r"
        val AFTER = ReturnDocument.AFTER
    }
}
