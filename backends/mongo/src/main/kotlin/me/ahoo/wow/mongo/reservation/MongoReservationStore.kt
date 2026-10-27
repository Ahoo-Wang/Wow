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

package me.ahoo.wow.mongo.reservation

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import kotlinx.coroutines.CoroutineDispatcher
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.schema.EnsuredOnce
import me.ahoo.wow.mongo.MongoDatabases
import me.ahoo.wow.mongo.MongoFormat
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.mongo.MongoSharding
import me.ahoo.wow.mongo.MongoShards
import me.ahoo.wow.mongo.MongoTimeouts
import me.ahoo.wow.mongo.conditionalUpsert
import me.ahoo.wow.mongo.mongo
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.spi.reservation.ConfirmOutcome
import me.ahoo.wow.spi.reservation.Reservation
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.reservation.ReservationStore
import org.bson.Document
import org.bson.conversions.Bson
import java.time.Clock
import kotlin.time.Duration

/**
 * The [ReservationStore] on MongoDB (documentation/designs/01-programming-model.md §7): one collection
 * per aggregate type, `wow_reservation__<context>__<aggregate>` (the type is the unit of placement, so
 * its reservations follow its log's store), created with its format record and owner index on the type's first use. One
 * document per reserved key, `{_id: {t: tenant_id, n: namespace, k: key}, tenant_id, key, owner: {tenant_id, id},
 * expires_at, holds, version}`: the key is the `_id` (the system tenant is the empty string), its tenant and key also
 * plain fields, the shard key's; the owner is of the collection's type. A null `expires_at` marks a confirmed
 * reservation, `version` the owner's fact that last confirmed it; a pending one expires at that epoch millisecond and
 * is held by the attempts in `holds`, without a version. The layout is format [FORMAT].
 *
 * **Sharded cluster**: with [MongoSharding.AUTO] on a mongos, each collection is sharded by
 * `(tenant_id, hashed key)`, as the snapshots are. Every write's filter names the `_id` and the shard key's two fields,
 * so it routes to the one shard a key lives on, and the `_id`'s uniqueness on that shard holds the key unique across the
 * cluster. A read by owner, which only an operator's tooling does, gathers from every shard.
 *
 * A reserve or a confirm is a conditional upsert (see [conditionalUpsert]) whose filter admits only the key's owner or
 * an expired pending reservation (a null expiry never passes `$lte`); a reserve's pipeline update keeps a confirmed
 * reservation of the same owner as it is and adds the hold to its live pending one. Of concurrent reservations of a
 * free key exactly one inserts it; the others hit the `_id` and then find it held. An abandon pulls its hold, then
 * deletes the reservation only while it is pending, the owner's and without holds, so a hold added between the two
 * keeps it. Expiry is judged by [clock], not by the database's clock, so it behaves the same in memory and here.
 *
 * Calls run on [dispatcher], virtual threads by default.
 */
@InternalWowApi
class MongoReservationStore(
    private val client: MongoClient,
    databaseName: String,
    private val clock: Clock = Clock.systemUTC(),
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    /** How the store's collections and indexes are managed: created when missing by default. */
    private val ddl: Ddl = Ddl.AUTO,
    /** Whether the store shards its collections on a sharded cluster. */
    sharding: MongoSharding = MongoSharding.AUTO,
    /** How long each of its calls may take (`wow.mongo.operation-timeout`, [MongoTimeouts]). */
    operationTimeout: Duration = MongoTimeouts.OPERATION,
) : ReservationStore {
    private val database = MongoDatabases.of(client, databaseName, operationTimeout)

    /** Whether this store shards its collections: asked of the server on the first collection it makes sure of. */
    private val sharded by lazy { sharding.shards(client, ddl) }

    /** The collections whose format and indexes this store has made sure of, by aggregate type, each once. */
    private val ensured = EnsuredOnce<String, MongoCollection<Document>>()

    /**
     * Nothing to create up front: each aggregate type's collection is created on its first use, since the store does
     * not know the aggregate types. Kept for the host, which calls it at startup.
     */
    fun createSchema() = Unit

    /** The collection of [type]'s reservations, its format claimed and its indexes made sure of on first use. */
    private fun reservationsOf(type: AggregateType): MongoCollection<Document> = ensured.get(type.toString()) {
        val name = MongoNames.of(PREFIX, type.context, type.name)
        MongoFormat.claim(database, name, FORMAT, ddl)
        database.getCollection(name).also { collection ->
            MongoFormat.index(
                collection,
                Indexes.ascending(OWNER_TENANT_ID, OWNER_ID),
                IndexOptions().name(RESERVATION_OWNER),
                ddl,
            )
            if (sharded) {
                // Sharded by tenant, then the key's hash, so the keys of a tenant spread over the shards.
                MongoShards.shardReservations(client, database, collection)
            }
        }
    }

    override suspend fun reserve(
        scope: ReservationScope,
        key: String,
        owner: AggregateRef,
        hold: String,
        ttl: Duration,
    ): Reservation? {
        require(ttl.isPositive()) { "A reservation's time to live is positive." }
        require(hold.isNotBlank()) { "A reservation's hold is not blank." }
        requireOwnType(scope, owner)
        return dispatcher.mongo {
            val reservations = reservationsOf(scope.type)
            val now = clock.millis()
            val expiresAt = now + ttl.inWholeMilliseconds
            val takeable = Filters.and(
                keyFilter(scope, key),
                Filters.or(ownerFilter(owner), Filters.lte(EXPIRES_AT, now))
            )
            val sameOwner = Document(
                "\$and",
                listOf(
                    Document("\$eq", listOf("$$OWNER_TENANT_ID", literal(owner.tenantId))),
                    Document("\$eq", listOf("$$OWNER_ID", literal(owner.id))),
                ),
            )
            val confirmedBySame = Document("\$and", listOf(sameOwner, Document("\$eq", listOf("$$EXPIRES_AT", null))))
            val liveBySame = Document("\$and", listOf(sameOwner, Document("\$gt", listOf("$$EXPIRES_AT", now))))
            val onlyHold = listOf(literal(hold))
            val take = listOf(
                Document(
                    "\$set",
                    Document(OWNER, ownerDocument(owner))
                        .append(
                            EXPIRES_AT,
                            cond(
                                confirmedBySame,
                                null,
                                cond(liveBySame, Document("\$max", listOf("$$EXPIRES_AT", expiresAt)), expiresAt),
                            ),
                        )
                        .append(
                            HOLDS,
                            cond(
                                confirmedBySame,
                                "$$HOLDS",
                                cond(liveBySame, Document("\$setUnion", listOf("$$HOLDS", onlyHold)), onlyHold),
                            ),
                        ),
                ),
            )
            val options = FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
            var taken: Reservation? = null
            var attempts = 0
            // Refused without upsert because an abandon or a release freed the key in between: take it now.
            do {
                taken = conditionalUpsert { upsert ->
                    reservations.findOneAndUpdate(takeable, take, options.upsert(upsert))
                        ?.let { reservationOf(scope, key, it) }
                }
            } while (taken == null && ++attempts < WRITE_ATTEMPTS && reservations.find(keyFilter(scope, key)).first() == null)
            taken
        }
    }

    override suspend fun abandon(scope: ReservationScope, key: String, owner: AggregateRef, hold: String): Boolean =
        dispatcher.mongo {
            val reservations = reservationsOf(scope.type)
            val pendingOfOwner = Filters.and(keyFilter(scope, key), ownerFilter(owner), Filters.ne(EXPIRES_AT, null))
            val pulled = reservations.updateOne(
                Filters.and(pendingOfOwner, Filters.eq(HOLDS, hold)),
                Updates.pull(HOLDS, hold),
            ).modifiedCount == 1L
            if (pulled) {
                reservations.deleteOne(Filters.and(pendingOfOwner, Filters.size(HOLDS, 0)))
            }
            pulled
        }

    override suspend fun confirm(
        scope: ReservationScope,
        key: String,
        owner: AggregateRef,
        version: Int,
    ): ConfirmOutcome {
        requireOwnType(scope, owner)
        return dispatcher.mongo {
            val reservations = reservationsOf(scope.type)
            val confirm = Updates.combine(
                Updates.set(OWNER, Document(TENANT_ID, owner.tenantId).append(ID, owner.id)),
                Updates.set(EXPIRES_AT, null),
                Updates.set(HOLDS, emptyList<String>()),
                // A pending reservation has none, so only the owner's own confirmed one keeps a greater version.
                Updates.max(VERSION, version),
            )
            // A holder that went away between the upsert and the read is retried: the next upsert takes the key.
            repeat(WRITE_ATTEMPTS) {
                val now = clock.millis()
                val confirmable =
                    Filters.and(keyFilter(scope, key), Filters.or(ownerFilter(owner), Filters.lte(EXPIRES_AT, now)))
                val confirmed = conditionalUpsert { upsert ->
                    val options = FindOneAndUpdateOptions().upsert(upsert).returnDocument(ReturnDocument.AFTER)
                    reservations.findOneAndUpdate(confirmable, confirm, options)
                }
                if (confirmed != null) {
                    return@mongo ConfirmOutcome.Confirmed
                }
                val holder = reservations.find(keyFilter(scope, key)).first()?.let { reservationOf(scope, key, it) }
                if (holder != null && holder.owner != owner && holder.isLive(now)) {
                    return@mongo ConfirmOutcome.Taken(holder)
                }
            }
            error("Confirming $key in $scope for $owner kept racing other writers.")
        }
    }

    override suspend fun release(scope: ReservationScope, key: String, owner: AggregateRef, version: Int): Boolean =
        dispatcher.mongo {
            val reservations = reservationsOf(scope.type)
            val releasable = Filters.or(Filters.ne(EXPIRES_AT, null), Filters.lte(VERSION, version))
            reservations.deleteOne(Filters.and(keyFilter(scope, key), ownerFilter(owner), releasable))
                .deletedCount == 1L
        }

    override suspend fun find(scope: ReservationScope, key: String): Reservation? = dispatcher.mongo {
        reservationsOf(scope.type).find(
            Filters.and(
                keyFilter(scope, key),
                Filters.or(Filters.eq(EXPIRES_AT, null), Filters.gt(EXPIRES_AT, clock.millis())),
            ),
        ).firstOrNull()?.let { reservationOf(scope, key, it) }
    }

    /**
     * The key's document: its `_id`, and the shard key's tenant and key, so a write routes to one shard and an upsert
     * inserts all three.
     */
    private fun keyFilter(scope: ReservationScope, key: String): Bson = Filters.and(
        Filters.eq(ID_FIELD, Document(T, scope.tenantId).append(N, scope.namespace).append(K, key)),
        Filters.eq(TENANT_ID, scope.tenantId),
        Filters.eq(KEY, key),
    )

    private fun ownerFilter(owner: AggregateRef): Bson = Filters.and(
        Filters.eq(OWNER_TENANT_ID, owner.tenantId),
        Filters.eq(OWNER_ID, owner.id),
    )

    private fun reservationOf(scope: ReservationScope, key: String, document: Document): Reservation {
        val owner = document.get(OWNER, Document::class.java)
        return Reservation(
            scope = scope,
            key = key,
            owner = scope.type.ref(owner.getString(TENANT_ID), owner.getString(ID)),
            expiresAt = document.getLong(EXPIRES_AT),
            holds = document.getList(HOLDS, String::class.java).orEmpty().toSet(),
            version = document.getInteger(VERSION) ?: ReservationStore.UNVERSIONED,
        )
    }

    private companion object {
        /** The version of this layout. */
        const val FORMAT = 2
        const val WRITE_ATTEMPTS = 8
        const val PREFIX = "wow_reservation"
        const val RESERVATION_OWNER = "reservation_owner"
        const val ID_FIELD = "_id"
        const val T = "t"
        const val N = "n"
        const val K = "k"
        const val TENANT_ID = "tenant_id"
        const val KEY = "key"
        const val OWNER = "owner"
        const val ID = "id"
        const val OWNER_TENANT_ID = "$OWNER.$TENANT_ID"
        const val OWNER_ID = "$OWNER.$ID"
        const val EXPIRES_AT = "expires_at"
        const val HOLDS = "holds"
        const val VERSION = "version"

        fun requireOwnType(scope: ReservationScope, owner: AggregateRef) =
            require(owner.type == scope.type) { "$owner reserves keys of its own aggregate type only." }

        /** A string in a pipeline that is never read as a field path, whatever it starts with. */
        fun literal(value: String) = Document("\$literal", value)

        /** The owner's fields in a pipeline, literal whatever they start with. */
        fun ownerDocument(owner: AggregateRef) = Document(TENANT_ID, literal(owner.tenantId))
            .append(ID, literal(owner.id))

        fun cond(condition: Any, then: Any?, otherwise: Any?) = Document("\$cond", listOf(condition, then, otherwise))
    }
}
