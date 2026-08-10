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

package me.ahoo.wow.query.internal.cursor

import me.ahoo.wow.query.backend.AnalyticsAlias
import me.ahoo.wow.query.backend.BackendId
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.PlanFingerprint
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.rejection.rejectQuery
import java.security.SecureRandom
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

@JvmInline
internal value class QueryCursorToken(val value: String)

@JvmInline
internal value class QueryCursorMappingDigest(val value: String) {
    init {
        requireCursorDigest(value, "Cursor mapping generation digest")
    }
}

@JvmInline
internal value class QueryCursorSecurityContextDigest(val value: String) {
    init {
        requireCursorDigest(value, "Cursor security-context digest")
    }
}

internal class QueryCursorBackendState(
    val backendId: BackendId,
    payload: ByteArray,
) {
    private val frozenPayload = payload.copyOf()

    val size: Int
        get() = frozenPayload.size

    fun payload(): ByteArray = frozenPayload.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is QueryCursorBackendState &&
            backendId == other.backendId &&
            frozenPayload.contentEquals(other.frozenPayload)

    override fun hashCode(): Int = 31 * backendId.hashCode() + frozenPayload.contentHashCode()
}

internal sealed interface QueryCursorPosition {
    class Record(sortKey: Iterable<NormalizedValue>) : QueryCursorPosition {
        val sortKey: List<NormalizedValue> = immutableCursorValues(sortKey, "Record cursor sort key")

        override fun equals(other: Any?): Boolean =
            this === other || other is Record && sortKey == other.sortKey

        override fun hashCode(): Int = sortKey.hashCode()
    }

    class Analytics(
        dimensionAliases: Iterable<AnalyticsAlias>,
        afterKey: Iterable<NormalizedValue>,
    ) : QueryCursorPosition {
        val dimensionAliases: List<AnalyticsAlias> = Collections.unmodifiableList(dimensionAliases.toList())
        val afterKey: List<NormalizedValue> = immutableCursorValues(afterKey, "Analytics cursor after-key")

        init {
            require(this.dimensionAliases.isNotEmpty()) { "Analytics cursor dimensions must not be empty." }
            require(this.dimensionAliases.distinct().size == this.dimensionAliases.size) {
                "Analytics cursor dimensions must be unique."
            }
            require(this.dimensionAliases.size == this.afterKey.size) {
                "Analytics cursor dimension and key arity must match."
            }
        }

        override fun equals(other: Any?): Boolean =
            this === other ||
                other is Analytics &&
                dimensionAliases == other.dimensionAliases &&
                afterKey == other.afterKey

        override fun hashCode(): Int = 31 * dimensionAliases.hashCode() + afterKey.hashCode()
    }
}

internal data class QueryCursorEnvelope(
    val target: QueryTarget,
    val planFingerprint: PlanFingerprint,
    val mappingGenerationDigest: QueryCursorMappingDigest,
    val securityContextDigest: QueryCursorSecurityContextDigest,
    val position: QueryCursorPosition,
    val expiresAt: Instant,
    val backendState: QueryCursorBackendState? = null,
    /** One-based page that this lease resumes. */
    val pageNumber: Int = 2,
    /** Initial execution ceiling. Continuations may keep or tighten it, but never remove or relax it. */
    val budgetCeiling: QueryCursorBudgetCeiling = QueryCursorBudgetCeiling(),
    val backendId: BackendId = backendState?.backendId ?: BackendId(UNSPECIFIED_BACKEND_ID),
) {
    init {
        require(pageNumber > 1) { "A continuation cursor must resume page two or later." }
    }
}

internal data class QueryCursorBudgetCeiling(
    val maxScannedRecords: Long? = null,
    val maxReturnedRecords: Long? = null,
    val maxPageWindow: Long? = null,
    val maxCandidateBuckets: Int? = null,
    val maxReturnedBuckets: Int? = null,
    val maxCursorPages: Int? = null,
    val allowDiskUse: Boolean = false,
) {
    init {
        require(maxScannedRecords == null || maxScannedRecords > 0)
        require(maxReturnedRecords == null || maxReturnedRecords > 0)
        require(maxPageWindow == null || maxPageWindow > 0)
        require(maxCandidateBuckets == null || maxCandidateBuckets > 0)
        require(maxReturnedBuckets == null || maxReturnedBuckets > 0)
        require(maxCursorPages == null || maxCursorPages > 0)
    }
}

internal data class QueryCursorLeaseBinding(
    val target: QueryTarget,
    val planFingerprint: PlanFingerprint,
    val mappingGenerationDigest: QueryCursorMappingDigest,
    val securityContextDigest: QueryCursorSecurityContextDigest,
    val backendId: BackendId,
)

internal fun QueryCursorEnvelope.binding(): QueryCursorLeaseBinding = QueryCursorLeaseBinding(
    target,
    planFingerprint,
    mappingGenerationDigest,
    securityContextDigest,
    backendId,
)

internal data class QueryCursorLeaseLimits(
    val maxEntries: Int = 10_000,
    val maxTtl: Duration = Duration.ofMinutes(5),
    val maxBackendStateBytes: Int = 4_096,
) {
    init {
        require(maxEntries > 0)
        require(!maxTtl.isZero && !maxTtl.isNegative)
        require(maxBackendStateBytes > 0)
        require(maxBackendStateBytes <= me.ahoo.wow.query.cursor.QueryCursorLeaseConfiguration.MAX_BACKEND_STATE_BYTES)
    }
}

/**
 * A bounded, one-time cursor lease registry.
 *
 * The token contains only a random lease id, format/signing-key ids and expiry. Semantic cursor keys and backend state
 * (for example a PIT id) remain server-side. [acquire] atomically transfers ownership to the caller by removing the
 * entry. The caller must either issue the next lease after successful continuation or close the backend state on
 * terminal, error or cancellation. [reapExpired] transfers abandoned entries to the caller for best-effort resource
 * cleanup.
 */
internal class InMemoryQueryCursorLeaseManager(
    signingKeyRing: QueryCursorSigningKeyRing,
    private val clock: Clock = Clock.systemUTC(),
    private val limits: QueryCursorLeaseLimits = QueryCursorLeaseLimits(),
) {
    private val codec = QueryCursorTokenCodec(signingKeyRing)
    private val random = SecureRandom()
    private val entries = ConcurrentHashMap<String, LeaseEntry>()
    private val issueLock = Any()

    val size: Int
        get() = entries.size

    constructor(
        secret: ByteArray,
        clock: Clock = Clock.systemUTC(),
        limits: QueryCursorLeaseLimits = QueryCursorLeaseLimits(),
        keyId: Int = CURRENT_SIGNING_KEY_ID,
    ) : this(QueryCursorSigningKeyRing(QueryCursorSigningKey(keyId, secret)), clock, limits)

    fun issue(envelope: QueryCursorEnvelope): QueryCursorToken {
        validateEnvelope(envelope)
        val id = ByteArray(CURSOR_ID_BYTES)
        return synchronized(issueLock) {
            if (entries.size >= limits.maxEntries) {
                rejectQuery(
                    QueryRejectionCategory.BUDGET_EXCEEDED,
                    CURSOR_PATH,
                    QueryRejectionCode.CURSOR_CAPACITY_EXCEEDED,
                )
            }
            var candidate: String
            do {
                random.nextBytes(id)
                candidate = URL_ENCODER.encodeToString(id)
            } while (entries.containsKey(candidate))
            val token = codec.encode(candidate, envelope.expiresAt)
            entries[candidate] = LeaseEntry(envelope)
            token
        }
    }

    fun acquire(token: QueryCursorToken, expectedBinding: QueryCursorLeaseBinding): QueryCursorEnvelope {
        val claims = codec.decode(token)
        val now = clock.instant()
        if (!claims.expiresAt.isAfter(now)) {
            rejectQuery(
                QueryRejectionCategory.INVALID_CURSOR,
                CURSOR_PATH,
                QueryRejectionCode.CURSOR_EXPIRED,
            )
        }
        val entry = entries[claims.id]
        if (entry == null || entry.envelope.expiresAt != claims.expiresAt) {
            rejectInvalidCursor()
        }
        if (!entry.envelope.expiresAt.isAfter(now)) {
            rejectQuery(
                QueryRejectionCategory.INVALID_CURSOR,
                CURSOR_PATH,
                QueryRejectionCode.CURSOR_EXPIRED,
            )
        }
        if (entry.envelope.binding() != expectedBinding) {
            rejectQuery(
                QueryRejectionCategory.INVALID_CURSOR,
                CURSOR_PATH,
                QueryRejectionCode.INVALID_CURSOR_BINDING,
            )
        }
        if (!entries.remove(claims.id, entry)) {
            rejectInvalidCursor()
        }
        return entry.envelope
    }

    fun reapExpired(): List<QueryCursorEnvelope> {
        val now = clock.instant()
        val expired = mutableListOf<QueryCursorEnvelope>()
        entries.forEach { (id, entry) ->
            if (!entry.envelope.expiresAt.isAfter(now) && entries.remove(id, entry)) {
                expired += entry.envelope
            }
        }
        return Collections.unmodifiableList(expired)
    }

    private fun validateEnvelope(envelope: QueryCursorEnvelope) {
        val now = clock.instant()
        if (!envelope.expiresAt.isAfter(now)) {
            rejectQuery(
                QueryRejectionCategory.INVALID_CURSOR,
                CURSOR_PATH,
                QueryRejectionCode.CURSOR_EXPIRED,
            )
        }
        val maximumExpiry = try {
            now.plus(limits.maxTtl)
        } catch (error: DateTimeException) {
            throw IllegalStateException("Cursor TTL cannot be represented.", error)
        } catch (error: ArithmeticException) {
            throw IllegalStateException("Cursor TTL cannot be represented.", error)
        }
        if (envelope.expiresAt.isAfter(maximumExpiry) ||
            envelope.backendState?.let { state -> state.size > limits.maxBackendStateBytes } == true
        ) {
            rejectQuery(
                QueryRejectionCategory.BUDGET_EXCEEDED,
                CURSOR_PATH,
                QueryRejectionCode.CURSOR_CAPACITY_EXCEEDED,
            )
        }
    }

    private data class LeaseEntry(val envelope: QueryCursorEnvelope)

    private companion object {
        const val CURRENT_SIGNING_KEY_ID = 1
        const val CURSOR_ID_BYTES = 32
        val CURSOR_PATH: QueryRejectionPath = QueryRejectionPath.ROOT.property("cursor")
        val URL_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}

private fun requireCursorDigest(value: String, name: String) {
    require(value.length == SHA_256_HEX_LENGTH && value.all { character -> character in HEX_CHARACTERS }) {
        "$name must be lowercase SHA-256 hex."
    }
}

private const val SHA_256_HEX_LENGTH = 64
private const val HEX_CHARACTERS = "0123456789abcdef"
private const val UNSPECIFIED_BACKEND_ID = "unspecified"

private fun immutableCursorValues(values: Iterable<NormalizedValue>, name: String): List<NormalizedValue> =
    Collections.unmodifiableList(values.toList()).also { frozen ->
        require(frozen.isNotEmpty()) { "$name must not be empty." }
        require(frozen.all(NormalizedValue::isPortableCursorScalar)) {
            "$name contains a non-scalar value."
        }
    }

private fun NormalizedValue.isPortableCursorScalar(): Boolean = when (this) {
    NormalizedValue.Null,
    is NormalizedValue.BooleanValue,
    is NormalizedValue.Text,
    is NormalizedValue.Int64,
    is NormalizedValue.Decimal,
    is NormalizedValue.InstantValue,
    -> true

    is NormalizedValue.Bytes,
    is NormalizedValue.ListValue,
    is NormalizedValue.ObjectValue,
    -> false
}

internal fun rejectInvalidCursor(cause: Throwable? = null): Nothing = rejectQuery(
    QueryRejectionCategory.INVALID_CURSOR,
    QueryRejectionPath.ROOT.property("cursor"),
    QueryRejectionCode.INVALID_CURSOR_TOKEN,
    cause,
)
