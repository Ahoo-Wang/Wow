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
import me.ahoo.wow.mongo.MongoFormat
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.mongo.conditionalUpsert
import me.ahoo.wow.mongo.mongo
import me.ahoo.wow.spi.StoreDdl
import me.ahoo.wow.spi.reservation.ConfirmOutcome
import me.ahoo.wow.spi.reservation.Reservation
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.reservation.ReservationStore
import org.bson.Document
import org.bson.conversions.Bson
import java.time.Clock
import kotlin.time.Duration

/**
 * The [ReservationStore] on MongoDB (documentation/designs/v10/01-programming-model.md §7, decision D38): one document
 * per reserved key, `{context, tenant_id, namespace, key, owner: {aggregate, tenant_id, id}, expires_at, holds}`, keyed
 * by a unique index on `(context, tenant_id, namespace, key)` (finding A1-3; the system tenant is the empty string),
 * its owner structured (finding A1-10; its context is the key's) and an index on the owner. A null `expires_at` marks
 * a confirmed reservation; a pending one expires at that epoch millisecond and is held by the attempts in `holds`
 * (finding A1-2). The layout is format [FORMAT] (decision D59); format 1, keyed by `(namespace, key)` with an `owner`
 * string, had no format record, so a collection of it is refused: drop it.
 *
 * A reserve or a confirm is a conditional upsert (see [conditionalUpsert]) whose filter admits only the key's owner or
 * an expired pending reservation (a null expiry never passes `$lte`); a reserve's pipeline update keeps a confirmed
 * reservation of the same owner as it is and adds the hold to its live pending one. Of concurrent reservations of a
 * free key exactly one inserts it; the others hit the key index and then find it held. An abandon pulls its hold, then
 * deletes the reservation only while it is pending, the owner's and without holds, so a hold added between the two
 * keeps it. Expiry is judged by [clock], not by the database's clock, so it behaves the same in memory and here.
 *
 * Calls run on [dispatcher], virtual threads by default (decision D29).
 */
@InternalWowApi
class MongoReservationStore(
    client: MongoClient,
    databaseName: String,
    private val collection: String = DEFAULT_COLLECTION,
    private val clock: Clock = Clock.systemUTC(),
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    /** How the store's collections and indexes are managed: created when missing by default (review A5-10). */
    private val ddl: StoreDdl = StoreDdl.AUTO,
) : ReservationStore {
    private val database = client.getDatabase(databaseName)
    private val reservations: MongoCollection<Document> = database.getCollection(collection)

    /** Creates the key and owner indexes when missing; refuses a collection of another format or without a record. */
    fun createSchema() {
        MongoFormat.claim(database, collection, FORMAT, ddl)
        MongoFormat.index(
            reservations,
            Indexes.ascending(CONTEXT, TENANT_ID, NAMESPACE, KEY),
            IndexOptions().unique(true).name(RESERVATION_KEY),
            ddl,
        )
        MongoFormat.index(
            reservations,
            Indexes.ascending(CONTEXT, OWNER_AGGREGATE, OWNER_TENANT_ID, OWNER_ID),
            IndexOptions().name(RESERVATION_OWNER),
            ddl,
        )
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
        requireOwnContext(scope, owner)
        return dispatcher.mongo {
            val now = clock.millis()
            val expiresAt = now + ttl.inWholeMilliseconds
            val takeable = Filters.and(
                keyFilter(scope, key),
                Filters.or(ownerFilter(owner), Filters.lte(EXPIRES_AT, now))
            )
            val sameOwner = Document(
                "\$and",
                listOf(
                    Document("\$eq", listOf("$$OWNER_AGGREGATE", literal(owner.type.name))),
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
                    reservations.findOneAndUpdate(takeable, take, options.upsert(upsert))?.let { reservationOf(scope, it) }
                }
            } while (taken == null && ++attempts < WRITE_ATTEMPTS && reservations.find(keyFilter(scope, key)).first() == null)
            taken
        }
    }

    override suspend fun abandon(scope: ReservationScope, key: String, owner: AggregateRef, hold: String): Boolean =
        dispatcher.mongo {
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

    override suspend fun confirm(scope: ReservationScope, key: String, owner: AggregateRef): ConfirmOutcome {
        requireOwnContext(scope, owner)
        return dispatcher.mongo {
            val confirm = Updates.combine(
                Updates.set(
                    OWNER,
                    Document(AGGREGATE, owner.type.name).append(TENANT_ID, owner.tenantId).append(ID, owner.id)
                ),
                Updates.set(EXPIRES_AT, null),
                Updates.set(HOLDS, emptyList<String>()),
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
                val holder = reservations.find(keyFilter(scope, key)).first()?.let { reservationOf(scope, it) }
                if (holder != null && holder.owner != owner && holder.isLive(now)) {
                    return@mongo ConfirmOutcome.Taken(holder)
                }
            }
            error("Confirming $key in $scope for $owner kept racing other writers.")
        }
    }

    override suspend fun release(scope: ReservationScope, key: String, owner: AggregateRef): Boolean =
        dispatcher.mongo {
            reservations.deleteOne(Filters.and(keyFilter(scope, key), ownerFilter(owner))).deletedCount == 1L
        }

    override suspend fun find(scope: ReservationScope, key: String): Reservation? = dispatcher.mongo {
        reservations.find(
            Filters.and(
                keyFilter(scope, key),
                Filters.or(Filters.eq(EXPIRES_AT, null), Filters.gt(EXPIRES_AT, clock.millis())),
            ),
        ).firstOrNull()?.let { reservationOf(scope, it) }
    }

    private fun keyFilter(scope: ReservationScope, key: String): Bson = Filters.and(
        Filters.eq(CONTEXT, scope.context),
        Filters.eq(TENANT_ID, scope.tenantId),
        Filters.eq(NAMESPACE, scope.namespace),
        Filters.eq(KEY, key),
    )

    private fun ownerFilter(owner: AggregateRef): Bson = Filters.and(
        Filters.eq(OWNER_AGGREGATE, owner.type.name),
        Filters.eq(OWNER_TENANT_ID, owner.tenantId),
        Filters.eq(OWNER_ID, owner.id),
    )

    private fun reservationOf(scope: ReservationScope, document: Document): Reservation {
        val owner = document.get(OWNER, Document::class.java)
        return Reservation(
            scope = scope,
            key = document.getString(KEY),
            owner = AggregateType(
                scope.context,
                owner.getString(AGGREGATE)
            ).ref(owner.getString(TENANT_ID), owner.getString(ID)),
            expiresAt = document.getLong(EXPIRES_AT),
            holds = document.getList(HOLDS, String::class.java).orEmpty().toSet(),
        )
    }

    private companion object {
        /** The version of this layout (decision D59). */
        const val FORMAT = 2
        const val WRITE_ATTEMPTS = 8
        const val DEFAULT_COLLECTION = "wow_reservation"
        const val RESERVATION_KEY = "reservation_key"
        const val RESERVATION_OWNER = "reservation_owner"
        const val CONTEXT = "context"
        const val TENANT_ID = "tenant_id"
        const val NAMESPACE = "namespace"
        const val KEY = "key"
        const val OWNER = "owner"
        const val AGGREGATE = "aggregate"
        const val ID = "id"
        const val OWNER_AGGREGATE = "$OWNER.$AGGREGATE"
        const val OWNER_TENANT_ID = "$OWNER.$TENANT_ID"
        const val OWNER_ID = "$OWNER.$ID"
        const val EXPIRES_AT = "expires_at"
        const val HOLDS = "holds"

        fun requireOwnContext(scope: ReservationScope, owner: AggregateRef) =
            require(owner.type.context == scope.context) { "$owner reserves keys of its own context only." }

        /** A string in a pipeline that is never read as a field path, whatever it starts with. */
        fun literal(value: String) = Document("\$literal", value)

        /** The owner's fields in a pipeline, literal whatever they start with. */
        fun ownerDocument(owner: AggregateRef) = Document(AGGREGATE, literal(owner.type.name))
            .append(TENANT_ID, literal(owner.tenantId))
            .append(ID, literal(owner.id))

        fun cond(condition: Any, then: Any?, otherwise: Any?) = Document("\$cond", listOf(condition, then, otherwise))
    }
}
