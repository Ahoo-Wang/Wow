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

@file:OptIn(me.ahoo.wow.query.cursor.ExperimentalQueryCursorApi::class)

package me.ahoo.wow.mongo.query.cursor

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.query.cursor.ExperimentalQueryCursorApi
import me.ahoo.wow.query.cursor.QueryCursorLeaseCreateResult
import me.ahoo.wow.query.cursor.QueryCursorLeaseEntry
import me.ahoo.wow.query.cursor.QueryCursorLeaseId
import me.ahoo.wow.query.cursor.QueryCursorLeaseStore
import me.ahoo.wow.query.cursor.QueryCursorPayloadFormat
import me.ahoo.wow.query.cursor.QueryCursorStoreRevision
import me.ahoo.wow.query.cursor.StoredQueryCursorLease
import org.bson.Document
import org.bson.types.Binary
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Configuration for the bounded MongoDB cursor-lease namespace. */
@ExperimentalQueryCursorApi
data class MongoQueryCursorLeaseStoreOptions(
    val collectionName: String = DEFAULT_COLLECTION_NAME,
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    val retentionGrace: Duration = DEFAULT_RETENTION_GRACE,
    val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
    val maxScanSize: Int = DEFAULT_MAX_SCAN_SIZE,
) {
    init {
        require(COLLECTION_NAME.matches(collectionName)) { "Mongo cursor lease collection name is invalid." }
        require(maxEntries in 1..MAX_ENTRIES) { "Mongo cursor lease capacity is outside its supported range." }
        require(!retentionGrace.isZero && !retentionGrace.isNegative) {
            "Mongo cursor lease retention grace must be positive."
        }
        require(retentionGrace <= MAX_RETENTION_GRACE) {
            "Mongo cursor lease retention grace exceeds its supported maximum."
        }
        require(maxPayloadBytes in 1..MAX_PAYLOAD_BYTES) {
            "Mongo cursor lease payload limit is outside its supported range."
        }
        require(maxScanSize in 1..MAX_SCAN_SIZE) {
            "Mongo cursor lease scan limit is outside its supported range."
        }
    }

    companion object {
        const val DEFAULT_COLLECTION_NAME = "wow_query_cursor_lease_v1"
        const val DEFAULT_MAX_ENTRIES = 65_536
        const val DEFAULT_MAX_PAYLOAD_BYTES = 64 * 1024
        const val DEFAULT_MAX_SCAN_SIZE = 512
        const val MAX_ENTRIES = 1_000_000
        const val MAX_PAYLOAD_BYTES = 1024 * 1024
        const val MAX_SCAN_SIZE = 4096
        val DEFAULT_RETENTION_GRACE: Duration = Duration.ofMinutes(5)
        val MAX_RETENTION_GRACE: Duration = Duration.ofDays(1)
        private val COLLECTION_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,119}")
    }
}

/**
 * Cross-node cursor lease store backed by a bounded set of MongoDB slots.
 *
 * [ensureIndexes] is deliberately explicit. Applications must run it as a controlled schema/readiness operation
 * before supplying this store to Query Gateway. The TTL index uses a grace deadline so the framework reaper has an
 * opportunity to atomically acquire an expired lease and close its Backend state before MongoDB removes abandoned
 * documents as a final safety net.
 */
@ExperimentalQueryCursorApi
class MongoQueryCursorLeaseStore(
    database: MongoDatabase,
    val options: MongoQueryCursorLeaseStoreOptions = MongoQueryCursorLeaseStoreOptions(),
    private val clock: Clock = Clock.systemUTC(),
) : QueryCursorLeaseStore {
    private val collection: MongoCollection<Document> = database.getCollection(options.collectionName)

    fun ensureIndexes(): Mono<Void> = Flux.concat(
        collection.createIndex(
            Indexes.ascending(LEASE_ID_FIELD),
            IndexOptions().name(LEASE_ID_INDEX).unique(true),
        ).toMono(),
        collection.createIndex(
            Indexes.ascending(PURGE_AT_FIELD),
            IndexOptions().name(PURGE_AT_INDEX).expireAfter(0, TimeUnit.SECONDS),
        ).toMono(),
    ).then()

    override fun create(entry: QueryCursorLeaseEntry): Mono<QueryCursorLeaseCreateResult> = Mono.defer {
        require(entry.expiresAt.isAfter(clock.instant())) { "Mongo cursor lease expiry must be in the future." }
        require(entry.payload().size <= options.maxPayloadBytes) {
            "Mongo cursor lease payload exceeds its configured limit."
        }
        insert(entry, initialSlot(entry.id), attempt = 0)
    }

    override fun load(id: QueryCursorLeaseId): Mono<StoredQueryCursorLease> = Mono.defer {
        collection.find(Filters.eq(LEASE_ID_FIELD, id.value))
            .first()
            .toMono()
            .map { document -> document.toStoredLease(id) }
    }

    override fun compareAndDelete(expected: StoredQueryCursorLease): Mono<Boolean> = Mono.defer {
        collection.deleteOne(
            Filters.and(
                Filters.eq(LEASE_ID_FIELD, expected.entry.id.value),
                Filters.eq(REVISION_FIELD, expected.revision.value),
            ),
        ).toMono().map { result -> result.deletedCount == 1L }
    }

    override fun scanExpired(
        before: Instant,
        afterId: QueryCursorLeaseId?,
        limit: Int,
    ): Flux<StoredQueryCursorLease> {
        require(limit in 1..options.maxScanSize) { "Mongo cursor lease scan exceeds its configured bound." }
        val filter = afterId?.let { cursor ->
            Filters.and(
                Filters.lte(EXPIRES_AT_FIELD, Date.from(before)),
                Filters.gt(LEASE_ID_FIELD, cursor.value),
            )
        } ?: Filters.lte(EXPIRES_AT_FIELD, Date.from(before))
        return Flux.defer {
            collection.find(filter)
                .sort(Sorts.ascending(LEASE_ID_FIELD))
                .limit(limit)
                .toFlux()
                .map { document -> document.toStoredLease() }
        }
    }

    private fun insert(
        entry: QueryCursorLeaseEntry,
        initialSlot: Int,
        attempt: Int,
    ): Mono<QueryCursorLeaseCreateResult> {
        if (attempt == options.maxEntries) {
            return Mono.just(QueryCursorLeaseCreateResult.CAPACITY_EXCEEDED)
        }
        val slot = ((initialSlot.toLong() + attempt) % options.maxEntries).toInt()
        return collection.insertOne(entry.toDocument(slot)).toMono()
            .map { QueryCursorLeaseCreateResult.CREATED }
            .onErrorResume(MongoWriteException::class.java) { error ->
                if (ErrorCategory.fromErrorCode(error.code) != ErrorCategory.DUPLICATE_KEY) {
                    Mono.error(error)
                } else {
                    contains(entry.id).flatMap { duplicateId ->
                        if (duplicateId) {
                            Mono.just(QueryCursorLeaseCreateResult.COLLISION)
                        } else {
                            insert(entry, initialSlot, attempt + 1)
                        }
                    }
                }
            }
    }

    private fun contains(id: QueryCursorLeaseId): Mono<Boolean> = collection
        .find(Filters.eq(LEASE_ID_FIELD, id.value))
        .projection(Projections.include(LEASE_ID_FIELD))
        .first()
        .toMono()
        .hasElement()

    private fun QueryCursorLeaseEntry.toDocument(slot: Int): Document {
        val purgeAt = try {
            expiresAt.plus(options.retentionGrace)
        } catch (error: DateTimeException) {
            throw IllegalArgumentException("Mongo cursor lease purge deadline cannot be represented.", error)
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("Mongo cursor lease purge deadline cannot be represented.", error)
        }
        return Document(ID_FIELD, slot)
            .append(FORMAT_VERSION_FIELD, FORMAT_VERSION)
            .append(LEASE_ID_FIELD, id.value)
            .append(EXPIRES_AT_FIELD, Date.from(expiresAt))
            .append(PURGE_AT_FIELD, Date.from(purgeAt))
            .append(PAYLOAD_FORMAT_FIELD, payloadFormat.name)
            .append(PAYLOAD_FIELD, Binary(payload()))
            .append(REVISION_FIELD, UUID.randomUUID().toString())
    }

    private fun Document.toStoredLease(expectedId: QueryCursorLeaseId? = null): StoredQueryCursorLease {
        if (keys != DOCUMENT_FIELDS) corrupt("Mongo cursor lease document has an invalid shape.")
        exactInt(ID_FIELD).let { slot ->
            if (slot !in 0 until options.maxEntries) corrupt("Mongo cursor lease slot is outside configured capacity.")
        }
        if (exactInt(FORMAT_VERSION_FIELD) != FORMAT_VERSION) {
            corrupt("Mongo cursor lease document has an unsupported format version.")
        }
        val id = QueryCursorLeaseId(requiredString(LEASE_ID_FIELD))
        if (expectedId != null && id != expectedId) corrupt("Mongo cursor lease response identity does not match.")
        val expiresAt = requiredDate(EXPIRES_AT_FIELD).toInstant()
        val purgeAt = requiredDate(PURGE_AT_FIELD).toInstant()
        if (purgeAt.isBefore(expiresAt)) corrupt("Mongo cursor lease purge deadline precedes expiry.")
        val payloadFormat = try {
            QueryCursorPayloadFormat.valueOf(requiredString(PAYLOAD_FORMAT_FIELD))
        } catch (error: IllegalArgumentException) {
            throw corrupted("Mongo cursor lease payload format is invalid.", error)
        }
        val payload = when (val value = this[PAYLOAD_FIELD]) {
            is Binary -> value.data
            is ByteArray -> value
            else -> corrupt("Mongo cursor lease payload is invalid.")
        }
        if (payload.isEmpty() || payload.size > options.maxPayloadBytes) {
            corrupt("Mongo cursor lease payload is outside configured bounds.")
        }
        return StoredQueryCursorLease(
            QueryCursorLeaseEntry(id, expiresAt, payloadFormat, payload),
            QueryCursorStoreRevision(requiredString(REVISION_FIELD)),
        )
    }

    private fun Document.requiredString(field: String): String = (this[field] as? String)
        ?.takeIf(String::isNotBlank)
        ?: corrupt("Mongo cursor lease field [$field] is invalid.")

    private fun Document.requiredDate(field: String): Date = this[field] as? Date
        ?: corrupt("Mongo cursor lease field [$field] is invalid.")

    private fun Document.exactInt(field: String): Int {
        val value = this[field] as? Number ?: corrupt("Mongo cursor lease field [$field] is not an integer.")
        val long = value.toLong()
        if (long !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || value.toDouble() != long.toDouble()) {
            corrupt("Mongo cursor lease field [$field] is not an exact integer.")
        }
        return long.toInt()
    }

    private fun initialSlot(id: QueryCursorLeaseId): Int {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(id.value.toByteArray(StandardCharsets.UTF_8))
        val unsigned = ByteBuffer.wrap(digest).int.toLong() and UNSIGNED_INT_MASK
        return (unsigned % options.maxEntries).toInt()
    }

    private fun corrupted(message: String, cause: Throwable? = null): IllegalStateException =
        IllegalStateException(message, cause)

    private fun corrupt(message: String): Nothing = throw corrupted(message)

    private companion object {
        const val FORMAT_VERSION = 1
        const val ID_FIELD = "_id"
        const val FORMAT_VERSION_FIELD = "formatVersion"
        const val LEASE_ID_FIELD = "leaseId"
        const val EXPIRES_AT_FIELD = "expiresAt"
        const val PURGE_AT_FIELD = "purgeAt"
        const val PAYLOAD_FORMAT_FIELD = "payloadFormat"
        const val PAYLOAD_FIELD = "payload"
        const val REVISION_FIELD = "revision"
        const val LEASE_ID_INDEX = "lease_id_unique"
        const val PURGE_AT_INDEX = "purge_at_ttl"
        const val UNSIGNED_INT_MASK = 0xffffffffL
        val DOCUMENT_FIELDS = setOf(
            ID_FIELD,
            FORMAT_VERSION_FIELD,
            LEASE_ID_FIELD,
            EXPIRES_AT_FIELD,
            PURGE_AT_FIELD,
            PAYLOAD_FORMAT_FIELD,
            PAYLOAD_FIELD,
            REVISION_FIELD,
        )
    }
}
