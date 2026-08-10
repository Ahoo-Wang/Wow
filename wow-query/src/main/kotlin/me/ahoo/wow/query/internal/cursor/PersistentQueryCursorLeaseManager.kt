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

@file:OptIn(
    me.ahoo.wow.query.cursor.ExperimentalQueryCursorApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)

package me.ahoo.wow.query.internal.cursor

import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.AnalyticsAlias
import me.ahoo.wow.query.backend.BackendId
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.PlanFingerprint
import me.ahoo.wow.query.cursor.QueryCursorLeaseCreateResult
import me.ahoo.wow.query.cursor.QueryCursorLeaseEntry
import me.ahoo.wow.query.cursor.QueryCursorLeaseId
import me.ahoo.wow.query.cursor.QueryCursorLeaseStore
import me.ahoo.wow.query.cursor.QueryCursorPayloadFormat
import me.ahoo.wow.query.cursor.StoredQueryCursorLease
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.rejection.rejectQuery
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

internal class PersistentQueryCursorLeaseManager(
    private val store: QueryCursorLeaseStore,
    signingKeyRing: QueryCursorSigningKeyRing,
    private val clock: Clock = Clock.systemUTC(),
    private val limits: QueryCursorLeaseLimits = QueryCursorLeaseLimits(),
) {
    private val tokenCodec = QueryCursorTokenCodec(signingKeyRing)
    private val envelopeCodec = QueryCursorEnvelopeCodec(signingKeyRing, limits.maxBackendStateBytes)
    private val random = SecureRandom()

    fun issue(envelope: QueryCursorEnvelope): Mono<QueryCursorToken> = Mono.defer {
        val normalized = envelope.copy(expiresAt = envelope.expiresAt.truncatedTo(ChronoUnit.MILLIS))
        validateEnvelope(normalized)
        create(normalized, attempt = 0)
    }

    /** Loads and verifies the immutable envelope without consuming its store revision. */
    fun load(token: QueryCursorToken): Mono<LoadedQueryCursorLease> = Mono.defer {
        val claims = tokenCodec.decode(token)
        val now = clock.instant()
        if (!claims.expiresAt.isAfter(now)) rejectExpiredCursor()
        store.load(QueryCursorLeaseId(claims.id))
            .switchIfEmpty(Mono.error(invalidCursorException()))
            .map { stored -> decodeStored(stored, claims, now) }
    }

    /** Validates the full semantic binding before atomically transferring one-time ownership. */
    fun acquire(
        loaded: LoadedQueryCursorLease,
        expectedBinding: QueryCursorLeaseBinding,
    ): Mono<QueryCursorEnvelope> = Mono.defer {
        if (loaded.envelope.binding() != expectedBinding) {
            rejectQuery(
                QueryRejectionCategory.INVALID_CURSOR,
                CURSOR_PATH,
                QueryRejectionCode.INVALID_CURSOR_BINDING,
            )
        }
        store.compareAndDelete(loaded.stored)
            .flatMap { deleted ->
                if (deleted) Mono.just(loaded.envelope) else Mono.error(invalidCursorException())
            }
    }

    /** Transfers only leases whose exact revision is still owned by this reaper. */
    fun reapExpired(
        before: Instant,
        afterId: QueryCursorLeaseId? = null,
        limit: Int,
    ): Flux<QueryCursorEnvelope> {
        require(limit > 0) { "Query cursor reaper limit must be positive." }
        return Flux.defer {
            store.scanExpired(before, afterId, limit)
                .concatMap { stored ->
                    Mono.defer {
                        val envelope = envelopeCodec.decode(stored.entry.payload())
                        if (envelope.expiresAt != stored.entry.expiresAt || envelope.expiresAt.isAfter(before)) {
                            return@defer Mono.error(invalidCursorException())
                        }
                        store.compareAndDelete(stored).filter { it }.map { envelope }
                    }
                }
        }
    }

    private fun create(envelope: QueryCursorEnvelope, attempt: Int): Mono<QueryCursorToken> {
        if (attempt == MAX_COLLISION_ATTEMPTS) {
            return Mono.error(invalidCursorException())
        }
        val idBytes = ByteArray(CURSOR_ID_BYTES).also(random::nextBytes)
        val id = URL_ENCODER.encodeToString(idBytes)
        val token = tokenCodec.encode(id, envelope.expiresAt)
        val entry = QueryCursorLeaseEntry(
            QueryCursorLeaseId(id),
            envelope.expiresAt,
            QueryCursorPayloadFormat.WOW_QUERY_CURSOR_V1,
            envelopeCodec.encode(envelope),
        )
        return store.create(entry).flatMap { result ->
            when (result) {
                QueryCursorLeaseCreateResult.CREATED -> Mono.just(token)
                QueryCursorLeaseCreateResult.COLLISION -> create(envelope, attempt + 1)
                QueryCursorLeaseCreateResult.CAPACITY_EXCEEDED -> Mono.error(cursorCapacityException())
            }
        }
    }

    private fun decodeStored(
        stored: StoredQueryCursorLease,
        claims: QueryCursorTokenCodec.TokenClaims,
        now: Instant,
    ): LoadedQueryCursorLease {
        requireMatchingStoredClaims(stored, claims)
        requireUnexpiredStoredEntry(stored, now)
        val envelope = envelopeCodec.decode(stored.entry.payload())
        requireMatchingStoredEnvelope(stored, envelope)
        return LoadedQueryCursorLease(stored, envelope)
    }

    private fun requireMatchingStoredClaims(
        stored: StoredQueryCursorLease,
        claims: QueryCursorTokenCodec.TokenClaims,
    ) {
        if (stored.entry.id.value != claims.id || stored.entry.expiresAt != claims.expiresAt) {
            throw invalidCursorException()
        }
    }

    private fun requireUnexpiredStoredEntry(stored: StoredQueryCursorLease, now: Instant) {
        if (!stored.entry.expiresAt.isAfter(now)) throw expiredCursorException()
    }

    private fun requireMatchingStoredEnvelope(
        stored: StoredQueryCursorLease,
        envelope: QueryCursorEnvelope,
    ) {
        if (envelope.expiresAt != stored.entry.expiresAt) throw invalidCursorException()
    }

    private fun validateEnvelope(envelope: QueryCursorEnvelope) {
        val now = clock.instant()
        if (!envelope.expiresAt.isAfter(now)) rejectExpiredCursor()
        val maximumExpiry = maximumExpiry(now)
        if (envelope.expiresAt.isAfter(maximumExpiry) ||
            envelope.backendState?.let { state -> state.size > limits.maxBackendStateBytes } == true
        ) {
            rejectCursorCapacity()
        }
    }

    private fun maximumExpiry(now: Instant): Instant = try {
        now.plus(limits.maxTtl)
    } catch (error: DateTimeException) {
        throw IllegalStateException("Cursor TTL cannot be represented.", error)
    } catch (error: ArithmeticException) {
        throw IllegalStateException("Cursor TTL cannot be represented.", error)
    }

    private companion object {
        const val CURSOR_ID_BYTES = 32
        const val MAX_COLLISION_ATTEMPTS = 8
        val URL_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val CURSOR_PATH: QueryRejectionPath = QueryRejectionPath.ROOT.property("cursor")
    }
}

internal data class LoadedQueryCursorLease(
    val stored: StoredQueryCursorLease,
    val envelope: QueryCursorEnvelope,
)

private class QueryCursorEnvelopeCodec(
    private val keyRing: QueryCursorSigningKeyRing,
    private val maxBackendStateBytes: Int,
) {
    private val maxBodyBytes: Int = Math.addExact(
        FIXED_BODY_BYTES,
        maxOf(DEFAULT_BACKEND_STATE_BYTES, maxBackendStateBytes),
    )
    private val maxPayloadBytes: Int = Math.addExact(HEADER_BYTES + HMAC_BYTES, maxBodyBytes)

    fun encode(envelope: QueryCursorEnvelope): ByteArray {
        val body = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output -> output.writeEnvelope(envelope) }
            bytes.toByteArray()
        }
        require(body.size <= maxBodyBytes) { "Query cursor envelope exceeds its encoded size limit." }
        val headerAndBody = ByteArrayOutputStream(HEADER_BYTES + body.size).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeByte(FORMAT_VERSION)
                output.writeByte(keyRing.current.id)
                output.writeInt(body.size)
                output.write(body)
            }
            bytes.toByteArray()
        }
        return headerAndBody + keyRing.current.sign(headerAndBody)
    }

    fun decode(payload: ByteArray): QueryCursorEnvelope {
        if (payload.size !in (HEADER_BYTES + HMAC_BYTES + 1)..maxPayloadBytes) rejectInvalidCursor()
        val signed = payload.copyOf(payload.size - HMAC_BYTES)
        val signature = payload.copyOfRange(payload.size - HMAC_BYTES, payload.size)
        val key = signingKey(signed)
        if (!MessageDigest.isEqual(signature, key.sign(signed))) rejectInvalidCursor()
        return translateMalformedCursor { decodeSigned(signed) }
    }

    private fun decodeSigned(signed: ByteArray): QueryCursorEnvelope =
        DataInputStream(ByteArrayInputStream(signed)).use { input ->
            if (input.readInt() != MAGIC || input.readUnsignedByte() != FORMAT_VERSION) rejectInvalidCursor()
            if (keyRing.resolve(input.readUnsignedByte()) == null) rejectInvalidCursor()
            val bodySize = input.readInt()
            if (bodySize <= 0 || bodySize > maxBodyBytes || bodySize != input.available()) rejectInvalidCursor()
            val envelope = input.readEnvelope()
            if (input.available() != 0) rejectInvalidCursor()
            envelope
        }

    private inline fun <T> translateMalformedCursor(block: () -> T): T = try {
        block()
    } catch (error: QueryRejectedException) {
        throw error
    } catch (error: IOException) {
        rejectInvalidCursor(error)
    } catch (error: DateTimeException) {
        rejectInvalidCursor(error)
    } catch (error: ArithmeticException) {
        rejectInvalidCursor(error)
    } catch (error: IllegalArgumentException) {
        rejectInvalidCursor(error)
    }

    private fun signingKey(signed: ByteArray): QueryCursorSigningKey = try {
        DataInputStream(ByteArrayInputStream(signed)).use { input ->
            if (input.readInt() != MAGIC || input.readUnsignedByte() != FORMAT_VERSION) rejectInvalidCursor()
            keyRing.resolve(input.readUnsignedByte()) ?: rejectInvalidCursor()
        }
    } catch (error: QueryRejectedException) {
        throw error
    } catch (error: IOException) {
        rejectInvalidCursor(error)
    }

    private fun DataOutputStream.writeEnvelope(envelope: QueryCursorEnvelope) {
        writeBoundedUtf8(envelope.target.namedAggregate.contextName)
        writeBoundedUtf8(envelope.target.namedAggregate.aggregateName)
        writeByte(envelope.target.documentKind.ordinal)
        writeBoundedUtf8(envelope.planFingerprint.value)
        writeBoundedUtf8(envelope.mappingGenerationDigest.value)
        writeBoundedUtf8(envelope.securityContextDigest.value)
        writeBoundedUtf8(envelope.backendId.value)
        when (val position = envelope.position) {
            is QueryCursorPosition.Record -> {
                writeByte(RECORD_POSITION)
                writeValues(position.sortKey)
            }

            is QueryCursorPosition.Analytics -> {
                writeByte(ANALYTICS_POSITION)
                writeInt(position.dimensionAliases.size)
                position.dimensionAliases.forEach { alias -> writeBoundedUtf8(alias.value) }
                writeValues(position.afterKey)
            }
        }
        writeLong(envelope.expiresAt.epochSecond)
        writeInt(envelope.expiresAt.nano)
        writeInt(envelope.pageNumber)
        writeBudgetCeiling(envelope.budgetCeiling)
        writeBoolean(envelope.backendState != null)
        envelope.backendState?.let { state ->
            writeBoundedUtf8(state.backendId.value)
            writeBytes(state.payload())
        }
    }

    private fun DataInputStream.readEnvelope(): QueryCursorEnvelope {
        val target = QueryTarget(
            MaterializedNamedAggregate(readBoundedUtf8(), readBoundedUtf8()),
            QueryDocumentKind.entries.getOrNull(readUnsignedByte()) ?: rejectInvalidCursor(),
        )
        val fingerprint = PlanFingerprint(readBoundedUtf8())
        val mappingDigest = QueryCursorMappingDigest(readBoundedUtf8())
        val securityDigest = QueryCursorSecurityContextDigest(readBoundedUtf8())
        val backendId = BackendId(readBoundedUtf8())
        val position = when (readUnsignedByte()) {
            RECORD_POSITION -> QueryCursorPosition.Record(readValues())
            ANALYTICS_POSITION -> {
                val aliasCount = readCount(MAX_POSITION_VALUES)
                val aliases = List(aliasCount) { AnalyticsAlias(readBoundedUtf8()) }
                QueryCursorPosition.Analytics(aliases, readValues())
            }

            else -> rejectInvalidCursor()
        }
        val expiresAt = Instant.ofEpochSecond(readLong(), readInt().toLong())
        val pageNumber = readInt()
        val budgetCeiling = readBudgetCeiling()
        val backendState = if (readBoolean()) {
            QueryCursorBackendState(BackendId(readBoundedUtf8()), readBytes())
        } else {
            null
        }
        return QueryCursorEnvelope(
            target,
            fingerprint,
            mappingDigest,
            securityDigest,
            position,
            expiresAt,
            backendState,
            pageNumber,
            budgetCeiling,
            backendId,
        )
    }

    private fun DataOutputStream.writeBudgetCeiling(budget: QueryCursorBudgetCeiling) {
        writeNullableLong(budget.maxScannedRecords)
        writeNullableLong(budget.maxReturnedRecords)
        writeNullableLong(budget.maxPageWindow)
        writeNullableInt(budget.maxCandidateBuckets)
        writeNullableInt(budget.maxReturnedBuckets)
        writeNullableInt(budget.maxCursorPages)
        writeBoolean(budget.allowDiskUse)
    }

    private fun DataInputStream.readBudgetCeiling(): QueryCursorBudgetCeiling = QueryCursorBudgetCeiling(
        maxScannedRecords = readNullableLong(),
        maxReturnedRecords = readNullableLong(),
        maxPageWindow = readNullableLong(),
        maxCandidateBuckets = readNullableInt(),
        maxReturnedBuckets = readNullableInt(),
        maxCursorPages = readNullableInt(),
        allowDiskUse = readBoolean(),
    )

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        value?.let(::writeLong)
    }

    private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

    private fun DataOutputStream.writeNullableInt(value: Int?) {
        writeBoolean(value != null)
        value?.let(::writeInt)
    }

    private fun DataInputStream.readNullableInt(): Int? = if (readBoolean()) readInt() else null

    private fun DataOutputStream.writeValues(values: List<NormalizedValue>) {
        writeInt(values.size)
        values.forEach { value ->
            when (value) {
                NormalizedValue.Null -> writeByte(NULL_VALUE)
                is NormalizedValue.BooleanValue -> {
                    writeByte(BOOLEAN_VALUE)
                    writeBoolean(value.value)
                }

                is NormalizedValue.Text -> {
                    writeByte(TEXT_VALUE)
                    writeBoundedUtf8(value.value)
                }

                is NormalizedValue.Int64 -> {
                    writeByte(INT64_VALUE)
                    writeLong(value.value)
                }

                is NormalizedValue.Decimal -> {
                    writeByte(DECIMAL_VALUE)
                    writeBoundedUtf8(value.value.toPlainString())
                }

                is NormalizedValue.InstantValue -> {
                    writeByte(INSTANT_VALUE)
                    writeLong(value.value.epochSecond)
                    writeInt(value.value.nano)
                }

                is NormalizedValue.Bytes,
                is NormalizedValue.ListValue,
                is NormalizedValue.ObjectValue,
                -> rejectInvalidCursor()
            }
        }
    }

    private fun DataInputStream.readValues(): List<NormalizedValue> {
        val count = readCount(MAX_POSITION_VALUES)
        return List(count) {
            when (readUnsignedByte()) {
                NULL_VALUE -> NormalizedValue.Null
                BOOLEAN_VALUE -> NormalizedValue.BooleanValue(readBoolean())
                TEXT_VALUE -> NormalizedValue.Text(readBoundedUtf8())
                INT64_VALUE -> NormalizedValue.Int64(readLong())
                DECIMAL_VALUE -> NormalizedValue.Decimal(BigDecimal(readBoundedUtf8()))
                INSTANT_VALUE -> NormalizedValue.InstantValue(Instant.ofEpochSecond(readLong(), readInt().toLong()))
                else -> rejectInvalidCursor()
            }
        }
    }

    private fun DataOutputStream.writeBoundedUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Query cursor text exceeds its encoded size limit." }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedUtf8(): String {
        val size = readCount(MAX_STRING_BYTES)
        val bytes = ByteArray(size)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun DataOutputStream.writeBytes(value: ByteArray) {
        require(value.size <= maxBackendStateBytes) { "Query cursor backend state exceeds its encoded size limit." }
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readBytes(): ByteArray {
        val size = readCount(maxBackendStateBytes)
        val value = ByteArray(size)
        readFully(value)
        return value
    }

    private fun DataInputStream.readCount(maximum: Int): Int = readInt().also { value ->
        if (value !in 0..maximum) rejectInvalidCursor()
    }

    private companion object {
        const val MAGIC = 0x57514345
        const val FORMAT_VERSION = 3
        const val HMAC_BYTES = 32
        const val HEADER_BYTES = Int.SIZE_BYTES + Byte.SIZE_BYTES + Byte.SIZE_BYTES + Int.SIZE_BYTES
        const val FIXED_BODY_BYTES = 4 * 1024
        const val DEFAULT_BACKEND_STATE_BYTES = 4 * 1024
        const val MAX_STRING_BYTES = 1024
        const val MAX_POSITION_VALUES = 32
        const val RECORD_POSITION = 1
        const val ANALYTICS_POSITION = 2
        const val NULL_VALUE = 0
        const val BOOLEAN_VALUE = 1
        const val TEXT_VALUE = 2
        const val INT64_VALUE = 3
        const val DECIMAL_VALUE = 4
        const val INSTANT_VALUE = 5
    }
}

private fun invalidCursorException(cause: Throwable? = null): QueryRejectedException =
    me.ahoo.wow.query.internal.policy.rejectedException(
        QueryRejectionCategory.INVALID_CURSOR,
        QueryRejectionPath.ROOT.property("cursor"),
        QueryRejectionCode.INVALID_CURSOR_TOKEN,
        cause,
    )

private fun expiredCursorException(): QueryRejectedException =
    me.ahoo.wow.query.internal.policy.rejectedException(
        QueryRejectionCategory.INVALID_CURSOR,
        QueryRejectionPath.ROOT.property("cursor"),
        QueryRejectionCode.CURSOR_EXPIRED,
    )

private fun cursorCapacityException(): QueryRejectedException =
    me.ahoo.wow.query.internal.policy.rejectedException(
        QueryRejectionCategory.BUDGET_EXCEEDED,
        QueryRejectionPath.ROOT.property("cursor"),
        QueryRejectionCode.CURSOR_CAPACITY_EXCEEDED,
    )

private fun rejectExpiredCursor(): Nothing = throw expiredCursorException()

private fun rejectCursorCapacity(): Nothing = throw cursorCapacityException()
