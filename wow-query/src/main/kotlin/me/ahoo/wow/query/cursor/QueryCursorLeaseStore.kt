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

package me.ahoo.wow.query.cursor

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant
import java.util.Collections

/** Marks the additive persistent cursor-store SPI while its operational implementations mature. */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "The Query cursor-store SPI is experimental and may evolve before the next major release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
annotation class ExperimentalQueryCursorApi

@ExperimentalQueryCursorApi
class QueryCursorHmacKey(
    val id: Int,
    secret: ByteArray,
) {
    private val frozenSecret = secret.copyOf()

    init {
        require(id in 1..UByte.MAX_VALUE.toInt()) { "Query cursor signing key id must fit one non-zero byte." }
        require(frozenSecret.size >= MINIMUM_SECRET_BYTES) {
            "Query cursor HMAC secret must contain at least 256 bits."
        }
    }

    @JvmSynthetic
    internal fun secretCopy(): ByteArray = frozenSecret.copyOf()

    private companion object {
        const val MINIMUM_SECRET_BYTES = 32
    }
}

@ExperimentalQueryCursorApi
class QueryCursorSigningKeys(
    val current: QueryCursorHmacKey,
    previous: Iterable<QueryCursorHmacKey> = emptyList(),
) {
    val previous: List<QueryCursorHmacKey> = Collections.unmodifiableList(previous.toList())

    init {
        val all = listOf(current) + this.previous
        require(all.size <= MAX_KEYS) { "Query cursor signing key ring exceeds its bounded key count." }
        require(all.map(QueryCursorHmacKey::id).distinct().size == all.size) {
            "Query cursor signing key ids must be unique."
        }
    }

    private companion object {
        const val MAX_KEYS = 4
    }
}

@ExperimentalQueryCursorApi
data class QueryCursorLeaseConfiguration(
    val store: QueryCursorLeaseStore,
    val signingKeys: QueryCursorSigningKeys,
    val leaseTtl: Duration = Duration.ofMinutes(2),
    val maxCursorTtl: Duration = Duration.ofMinutes(5),
    val maxBackendStateBytes: Int = 4096,
) {
    init {
        require(!leaseTtl.isZero && !leaseTtl.isNegative) { "Query cursor lease TTL must be positive." }
        require(!maxCursorTtl.isZero && !maxCursorTtl.isNegative) { "Query cursor maximum TTL must be positive." }
        require(leaseTtl <= maxCursorTtl) { "Query cursor lease TTL must not exceed its maximum TTL." }
        require(maxBackendStateBytes > 0) { "Query cursor backend state limit must be positive." }
    }
}

@ExperimentalQueryCursorApi
class QueryCursorLeaseId(val value: String) {
    init {
        require(value.isNotBlank()) { "Query cursor lease id must not be blank." }
        require(value.length <= MAX_ID_LENGTH) { "Query cursor lease id must not exceed $MAX_ID_LENGTH characters." }
        require(URL_SAFE.matches(value)) { "Query cursor lease id must be URL-safe without padding." }
    }

    override fun equals(other: Any?): Boolean = this === other || other is QueryCursorLeaseId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    private companion object {
        const val MAX_ID_LENGTH = 128
        val URL_SAFE = Regex("^[A-Za-z0-9_-]+$")
    }
}

@ExperimentalQueryCursorApi
class QueryCursorStoreRevision(val value: String) {
    init {
        require(value.isNotBlank()) { "Query cursor store revision must not be blank." }
        require(value.length <= MAX_REVISION_LENGTH) {
            "Query cursor store revision must not exceed $MAX_REVISION_LENGTH characters."
        }
        require(value.none(Char::isISOControl)) { "Query cursor store revision must not contain control characters." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is QueryCursorStoreRevision && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    private companion object {
        const val MAX_REVISION_LENGTH = 256
    }
}

@ExperimentalQueryCursorApi
enum class QueryCursorPayloadFormat {
    WOW_QUERY_CURSOR_V1,
}

@ExperimentalQueryCursorApi
class QueryCursorLeaseEntry(
    val id: QueryCursorLeaseId,
    val expiresAt: Instant,
    val payloadFormat: QueryCursorPayloadFormat,
    payload: ByteArray,
) {
    private val frozenPayload = payload.copyOf()

    init {
        require(frozenPayload.isNotEmpty()) { "Query cursor payload must not be empty." }
    }

    fun payload(): ByteArray = frozenPayload.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is QueryCursorLeaseEntry &&
            id == other.id &&
            expiresAt == other.expiresAt &&
            payloadFormat == other.payloadFormat &&
            frozenPayload.contentEquals(other.frozenPayload)

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + payloadFormat.hashCode()
        result = 31 * result + frozenPayload.contentHashCode()
        return result
    }
}

@ExperimentalQueryCursorApi
enum class QueryCursorLeaseCreateResult {
    CREATED,
    COLLISION,
    CAPACITY_EXCEEDED,
}

@ExperimentalQueryCursorApi
class StoredQueryCursorLease(
    val entry: QueryCursorLeaseEntry,
    val revision: QueryCursorStoreRevision,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is StoredQueryCursorLease && entry == other.entry && revision == other.revision

    override fun hashCode(): Int = 31 * entry.hashCode() + revision.hashCode()
}

/**
 * Persistent, cross-node cursor ownership store.
 *
 * Implementations must make [compareAndDelete] atomic for the exact [StoredQueryCursorLease.revision]. A successful
 * delete transfers one-time ownership to that caller. [scanExpired] is a bounded keyset scan ordered by lease id.
 */
@ExperimentalQueryCursorApi
interface QueryCursorLeaseStore {
    fun create(entry: QueryCursorLeaseEntry): Mono<QueryCursorLeaseCreateResult>

    /** Empty means the lease id is absent. */
    fun load(id: QueryCursorLeaseId): Mono<StoredQueryCursorLease>

    fun compareAndDelete(expected: StoredQueryCursorLease): Mono<Boolean>

    fun scanExpired(
        before: Instant,
        afterId: QueryCursorLeaseId? = null,
        limit: Int,
    ): Flux<StoredQueryCursorLease>
}
