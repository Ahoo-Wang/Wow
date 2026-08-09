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

package me.ahoo.wow.elasticsearch.query.lifecycle

import me.ahoo.wow.api.Version
import me.ahoo.wow.serialization.MessageRecords
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.math.BigInteger

internal data class EventStreamCanonicalChecksumEvidence(
    val count: Long,
    val identityChecksum: ElasticsearchIndexChecksum,
    val contentChecksum: ElasticsearchIndexChecksum,
)

/** Canonical checksum over event-stream documents ordered by aggregate id and aggregate version. */
internal class EventStreamCanonicalChecksumAccumulator(
    private val limits: SnapshotCanonicalChecksumLimits = SnapshotCanonicalChecksumLimits(),
) {
    private val identityDigest = newLifecycleSha256().apply { updateLifecycleUtf8(IDENTITY_HEADER) }
    private val contentDigest = newLifecycleSha256().apply { updateLifecycleUtf8(CONTENT_HEADER) }
    private var previousAggregateId: String? = null
    private var expectedVersion: Int = Version.INITIAL_VERSION
    private var count = 0L

    fun accept(documentId: String, source: Map<*, *>) {
        val aggregateId = source[MessageRecords.AGGREGATE_ID] as? String
            ?: throw IllegalArgumentException("EventStream verification document has no aggregate id.")
        require(aggregateId.isNotBlank()) { "EventStream verification aggregate id must not be blank." }
        val version = source[MessageRecords.VERSION].requireExactEventStreamVersion()
        val bodySize = source[MessageRecords.BODY].requireEventStreamBodySize()
        require(documentId == "$aggregateId-$version") {
            "EventStream verification identity does not match its serialized aggregate and version."
        }
        if (aggregateId != previousAggregateId) {
            require(previousAggregateId == null || aggregateId > requireNotNull(previousAggregateId)) {
                "EventStream verification aggregates must be unique and strictly ascending."
            }
            require(version == Version.INITIAL_VERSION) {
                "EventStream verification aggregate does not start at the initial version."
            }
            previousAggregateId = aggregateId
            expectedVersion = Version.INITIAL_VERSION
        }
        require(version == expectedVersion) { "EventStream verification aggregate version is not continuous." }
        expectedVersion = Math.addExact(version, bodySize)

        val identity = eventStreamIdentityBytes(aggregateId, version)
        val documentHash = canonicalDocumentHash(source, limits)
        identityDigest.updateLifecycleLengthPrefixed(identity)
        contentDigest.updateLifecycleLengthPrefixed(identity)
        contentDigest.updateLifecycleLengthPrefixed(documentHash)
        count = Math.addExact(count, 1)
    }

    fun finish(): EventStreamCanonicalChecksumEvidence = EventStreamCanonicalChecksumEvidence(
        count,
        ElasticsearchIndexChecksum(identityDigest.digest().toLowerHex()),
        ElasticsearchIndexChecksum(contentDigest.digest().toLowerHex()),
    )

    private companion object {
        const val IDENTITY_HEADER = "wow-es-event-stream-identity-sha256-v1"
        const val CONTENT_HEADER = "wow-es-event-stream-content-sha256-v1"
    }
}

private fun eventStreamIdentityBytes(aggregateId: String, version: Int): ByteArray {
    val buffer = ByteArrayOutputStream()
    DataOutputStream(buffer).use { output ->
        val aggregateBytes = aggregateId.toByteArray(Charsets.UTF_8)
        output.writeInt(aggregateBytes.size)
        output.write(aggregateBytes)
        output.writeInt(version)
    }
    return buffer.toByteArray()
}

internal fun Any?.requireExactEventStreamVersion(): Int {
    val value = try {
        when (this) {
            is BigDecimal -> toBigIntegerExact()
            is BigInteger -> this
            is Byte, is Short, is Int, is Long -> BigInteger.valueOf((this as Number).toLong())
            is Float, is Double -> BigDecimal.valueOf((this as Number).toDouble()).toBigIntegerExact()
            else -> throw IllegalArgumentException("EventStream verification document has no numeric version.")
        }
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException("EventStream verification version must be an exact integer.", error)
    }
    require(value >= BigInteger.valueOf(Version.INITIAL_VERSION.toLong()) && value.bitLength() <= Int.SIZE_BITS - 1) {
        "EventStream verification version is outside the supported range."
    }
    return value.toInt()
}

private fun Any?.requireEventStreamBodySize(): Int {
    val size = when (this) {
        is Collection<*> -> size
        is Array<*> -> size
        else -> throw IllegalArgumentException("EventStream verification document body must be a materialized list.")
    }
    require(size > 0) { "EventStream verification document body must not be empty." }
    return size
}
