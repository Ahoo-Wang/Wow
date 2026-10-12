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
import kotlinx.coroutines.withContext
import me.ahoo.wow.mongo.VirtualThreads
import me.ahoo.wow.mongo.conditionalUpsert
import me.ahoo.wow.spi.reservation.Reservation
import me.ahoo.wow.spi.reservation.ReservationStore
import org.bson.Document
import org.bson.conversions.Bson
import java.time.Clock
import kotlin.time.Duration

/**
 * The [ReservationStore] on MongoDB (documentation/designs/v10/01-programming-model.md §7, decision D38): one document
 * per reserved key, keyed by a unique index on (namespace, key). A null `expires_at` marks a confirmed reservation; a
 * pending one expires at that epoch millisecond.
 *
 * Every write is one single-document operation. A reserve is a conditional upsert (see [conditionalUpsert]) whose
 * filter admits only the key's owner or an expired pending reservation (a null expiry never passes `$lte`), and whose
 * pipeline update keeps a confirmed reservation of the same owner confirmed. Of concurrent reservations of a free key
 * exactly one inserts it; the others hit the key index and then find it held. Expiry is judged by [clock], not by the
 * database's clock, so it behaves the same in memory and here.
 *
 * Calls run on [dispatcher], virtual threads by default (decision D29).
 */
class MongoReservationStore(
    client: MongoClient,
    databaseName: String,
    collection: String = DEFAULT_COLLECTION,
    private val clock: Clock = Clock.systemUTC(),
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
) : ReservationStore {
    private val reservations: MongoCollection<Document> =
        client.getDatabase(databaseName).getCollection(collection)

    /** Creates the key index when missing. */
    fun createSchema() {
        reservations.createIndex(Indexes.ascending(NAMESPACE, KEY), IndexOptions().unique(true).name(RESERVATION_KEY))
    }

    override suspend fun reserve(namespace: String, key: String, owner: String, ttl: Duration): Reservation? {
        require(ttl.isPositive()) { "A reservation's time to live is positive." }
        return withContext(dispatcher) {
            val now = clock.millis()
            val takeable = Filters.and(
                keyFilter(namespace, key),
                Filters.or(Filters.eq(OWNER, owner), Filters.lte(EXPIRES_AT, now)),
            )
            val confirmedByOwner = Document(
                "\$and",
                listOf(
                    Document("\$eq", listOf("$$OWNER", literal(owner))),
                    Document("\$eq", listOf("$$EXPIRES_AT", null)),
                ),
            )
            val take = listOf(
                Document(
                    "\$set",
                    Document(OWNER, literal(owner)).append(
                        EXPIRES_AT,
                        Document("\$cond", listOf(confirmedByOwner, null, now + ttl.inWholeMilliseconds)),
                    ),
                ),
            )
            conditionalUpsert { upsert ->
                val options = FindOneAndUpdateOptions().upsert(upsert).returnDocument(ReturnDocument.AFTER)
                reservations.findOneAndUpdate(takeable, take, options)?.let { reservationOf(it) }
            }
        }
    }

    override suspend fun confirm(namespace: String, key: String, owner: String): Boolean = withContext(dispatcher) {
        reservations.updateOne(ownedBy(namespace, key, owner), Updates.set(EXPIRES_AT, null)).matchedCount == 1L
    }

    override suspend fun release(namespace: String, key: String, owner: String): Boolean = withContext(dispatcher) {
        reservations.deleteOne(ownedBy(namespace, key, owner)).deletedCount == 1L
    }

    override suspend fun find(namespace: String, key: String): Reservation? = withContext(dispatcher) {
        reservations.find(
            Filters.and(
                keyFilter(namespace, key),
                Filters.or(Filters.eq(EXPIRES_AT, null), Filters.gt(EXPIRES_AT, clock.millis())),
            ),
        ).firstOrNull()?.let { reservationOf(it) }
    }

    private fun keyFilter(namespace: String, key: String): Bson =
        Filters.and(Filters.eq(NAMESPACE, namespace), Filters.eq(KEY, key))

    private fun ownedBy(namespace: String, key: String, owner: String): Bson =
        Filters.and(keyFilter(namespace, key), Filters.eq(OWNER, owner))

    private fun reservationOf(document: Document) = Reservation(
        namespace = document.getString(NAMESPACE),
        key = document.getString(KEY),
        owner = document.getString(OWNER),
        expiresAt = document.getLong(EXPIRES_AT),
    )

    private companion object {
        const val DEFAULT_COLLECTION = "wow_reservation"
        const val RESERVATION_KEY = "reservation_key"
        const val NAMESPACE = "namespace"
        const val KEY = "key"
        const val OWNER = "owner"
        const val EXPIRES_AT = "expires_at"

        /** A string in a pipeline that is never read as a field path, whatever it starts with. */
        fun literal(value: String) = Document("\$literal", value)
    }
}
