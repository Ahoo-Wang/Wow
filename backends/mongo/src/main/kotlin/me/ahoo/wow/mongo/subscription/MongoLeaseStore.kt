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

package me.ahoo.wow.mongo.subscription

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import kotlinx.coroutines.CoroutineDispatcher
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.mongo.MongoFormat
import me.ahoo.wow.mongo.VirtualThreads
import me.ahoo.wow.mongo.conditionalUpsert
import me.ahoo.wow.mongo.mongo
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.Member
import org.bson.Document
import org.bson.conversions.Bson
import java.time.Clock
import kotlin.time.Duration

/**
 * The [LeaseStore] on MongoDB: one document per (group, partition) in `wow_lease` and one per (group, node), with the
 * node's internal address, in `wow_lease_member`, each keyed by a unique index.
 *
 * Every operation is one single-document write. An acquisition is a conditional upsert (see [conditionalUpsert])
 * whose filter admits only an expired lease or the acquirer's own, and whose pipeline update keeps the epoch when the
 * acquirer still holds the lease live and adds one otherwise. MongoDB re-evaluates the filter after a write conflict,
 * so concurrent acquisitions have exactly one winner. A release keeps the document with expiry 0, so the next
 * acquisition continues its epoch.
 *
 * Times come from [clock], so they are the calling node's: keep the TTL well above the nodes' clock skew. Calls run on
 * [dispatcher], virtual threads by default (decision D29).
 */
@InternalWowApi
class MongoLeaseStore(
    client: MongoClient,
    databaseName: String,
    private val clock: Clock = Clock.systemUTC(),
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
) : LeaseStore {
    private val database = client.getDatabase(databaseName)
    private val leases: MongoCollection<Document> = database.getCollection(LEASES)
    private val members: MongoCollection<Document> = database.getCollection(MEMBERS)

    /** Creates the key indexes when missing. */
    fun createSchema() {
        MongoFormat.claim(database, LEASES, FORMAT)
        MongoFormat.claim(database, MEMBERS, FORMAT)
        leases.createIndex(Indexes.ascending(GROUP, PARTITION), IndexOptions().unique(true).name(LEASE_KEY))
        members.createIndex(Indexes.ascending(GROUP, NODE), IndexOptions().unique(true).name(MEMBER_KEY))
    }

    override suspend fun acquire(group: String, partition: Int, owner: String, ttl: Duration): Lease? =
        dispatcher.mongo {
            val now = clock.millis()
            val takeable = Filters.and(
                leaseFilter(group, partition),
                Filters.or(Filters.lte(EXPIRES_AT, now), Filters.eq(OWNER, owner)),
            )
            val stillHeld = Document(
                "\$and",
                listOf(
                    Document("\$eq", listOf("$$OWNER", literal(owner))),
                    Document("\$gt", listOf("$$EXPIRES_AT", now)),
                ),
            )
            val nextEpoch = Document("\$add", listOf(Document("\$ifNull", listOf("$$EPOCH", 0L)), 1L))
            val take = listOf(
                Document(
                    "\$set",
                    Document(OWNER, literal(owner))
                        .append(EPOCH, Document("\$cond", listOf(stillHeld, "$$EPOCH", nextEpoch)))
                        .append(EXPIRES_AT, now + ttl.inWholeMilliseconds),
                ),
            )
            conditionalUpsert { upsert ->
                leases.findOneAndUpdate(takeable, take, returnAfter(upsert))?.let { leaseOf(it) }
            }
        }

    override suspend fun renew(group: String, partition: Int, owner: String, ttl: Duration): Lease? =
        dispatcher.mongo {
            val now = clock.millis()
            leases.findOneAndUpdate(
                liveLeaseOf(group, partition, owner, now),
                Updates.set(EXPIRES_AT, now + ttl.inWholeMilliseconds),
                returnAfter(upsert = false),
            )?.let { leaseOf(it) }
        }

    override suspend fun release(group: String, partition: Int, owner: String): Boolean = dispatcher.mongo {
        leases.updateOne(liveLeaseOf(group, partition, owner, clock.millis()), Updates.set(EXPIRES_AT, 0L))
            .matchedCount == 1L
    }

    override suspend fun owners(group: String): List<Lease> = dispatcher.mongo {
        leases.find(Filters.and(Filters.eq(GROUP, group), Filters.gt(EXPIRES_AT, clock.millis())))
            .sort(Sorts.ascending(PARTITION))
            .map { leaseOf(it) }
            .toList()
    }

    override suspend fun heartbeat(group: String, node: String, address: String, ttl: Duration): Member =
        dispatcher.mongo {
            val expiresAt = clock.millis() + ttl.inWholeMilliseconds
            conditionalUpsert { upsert ->
                members.updateOne(
                    memberFilter(group, node),
                    Updates.combine(Updates.set(ADDRESS, address), Updates.set(EXPIRES_AT, expiresAt)),
                    UpdateOptions().upsert(upsert)
                )
            }
            Member(group, node, address, expiresAt)
        }

    override suspend fun members(group: String): List<Member> = dispatcher.mongo {
        // MongoDB compares strings by their UTF-8 bytes when no collation is set: node order is code point order.
        members.find(Filters.and(Filters.eq(GROUP, group), Filters.gt(EXPIRES_AT, clock.millis())))
            .sort(Sorts.ascending(NODE))
            .map { Member(it.getString(GROUP), it.getString(NODE), it.getString(ADDRESS), it.getLong(EXPIRES_AT)) }
            .toList()
    }

    override suspend fun leave(group: String, node: String) {
        dispatcher.mongo { members.deleteOne(memberFilter(group, node)) }
    }

    private fun leaseFilter(group: String, partition: Int): Bson =
        Filters.and(Filters.eq(GROUP, group), Filters.eq(PARTITION, partition))

    private fun liveLeaseOf(group: String, partition: Int, owner: String, now: Long): Bson = Filters.and(
        leaseFilter(group, partition),
        Filters.eq(OWNER, owner),
        Filters.gt(EXPIRES_AT, now),
    )

    private fun memberFilter(group: String, node: String): Bson =
        Filters.and(Filters.eq(GROUP, group), Filters.eq(NODE, node))

    private fun returnAfter(upsert: Boolean) =
        FindOneAndUpdateOptions().upsert(upsert).returnDocument(ReturnDocument.AFTER)

    private fun leaseOf(document: Document) = Lease(
        group = document.getString(GROUP),
        partition = document.getInteger(PARTITION),
        owner = document.getString(OWNER),
        epoch = document.getLong(EPOCH),
        expiresAt = document.getLong(EXPIRES_AT),
    )

    private companion object {
        const val LEASES = "wow_lease"

        /** The layout version of both collections (decision D59, finding A2-13). */
        const val FORMAT = 1
        const val MEMBERS = "wow_lease_member"
        const val LEASE_KEY = "lease_key"
        const val MEMBER_KEY = "member_key"
        const val GROUP = "subscription_group"
        const val PARTITION = "partition"
        const val OWNER = "owner"
        const val EPOCH = "epoch"
        const val EXPIRES_AT = "expires_at"
        const val NODE = "node"
        const val ADDRESS = "address"

        /** A string in a pipeline that is never read as a field path, whatever it starts with. */
        fun literal(value: String) = Document("\$literal", value)
    }
}
