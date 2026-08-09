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

import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.SnapshotRecords
import java.math.BigDecimal
import java.math.BigInteger

internal data class SnapshotCanonicalChecksumLimits(
    val maxDepth: Int = 64,
    val maxNodesPerDocument: Int = 250_000,
    val maxCollectionSize: Int = 100_000,
    val maxStringBytes: Int = 1_048_576,
    val maxPayloadBytesPerDocument: Long = 32L * 1_048_576,
) {
    init {
        require(maxDepth > 0)
        require(maxNodesPerDocument > 0)
        require(maxCollectionSize > 0)
        require(maxStringBytes > 0)
        require(maxPayloadBytesPerDocument > 0)
    }
}

internal data class SnapshotCanonicalChecksumEvidence(
    val count: Long,
    val identityChecksum: ElasticsearchIndexChecksum,
    val contentChecksum: ElasticsearchIndexChecksum,
)

/** Ordered, bounded checksum accumulator shared by authoritative replay and physical-index inspection. */
internal class SnapshotCanonicalChecksumAccumulator(
    private val limits: SnapshotCanonicalChecksumLimits = SnapshotCanonicalChecksumLimits(),
) {
    private val identityDigest = newLifecycleSha256().apply { updateLifecycleUtf8(IDENTITY_HEADER) }
    private val contentDigest = newLifecycleSha256().apply { updateLifecycleUtf8(CONTENT_HEADER) }
    private var previousIdentity: String? = null
    private var count: Long = 0

    fun accept(identity: String, source: Map<*, *>) {
        require(identity.isNotBlank()) { "Snapshot verification identity must not be blank." }
        require(previousIdentity == null || identity > requireNotNull(previousIdentity)) {
            "Snapshot verification records must have unique, strictly ascending identities."
        }
        require(source[MessageRecords.AGGREGATE_ID] == identity) {
            "Snapshot verification identity does not match the serialized aggregate id."
        }
        requireNonNegativeVersion(source[MessageRecords.VERSION])
        val identityBytes = identity.toByteArray(Charsets.UTF_8)
        val documentHash = canonicalDocumentHash(source, limits, setOf(SnapshotRecords.SNAPSHOT_TIME))
        identityDigest.updateLifecycleLengthPrefixed(identityBytes)
        contentDigest.updateLifecycleLengthPrefixed(identityBytes)
        contentDigest.updateLifecycleLengthPrefixed(documentHash)
        previousIdentity = identity
        count = Math.addExact(count, 1)
    }

    fun finish(): SnapshotCanonicalChecksumEvidence = SnapshotCanonicalChecksumEvidence(
        count,
        ElasticsearchIndexChecksum(identityDigest.digest().toLowerHex()),
        ElasticsearchIndexChecksum(contentDigest.digest().toLowerHex()),
    )

    private companion object {
        const val IDENTITY_HEADER = "wow-es-snapshot-identity-sha256-v1"
        const val CONTENT_HEADER = "wow-es-snapshot-content-sha256-v1"
    }
}

private fun requireNonNegativeVersion(raw: Any?) {
    val version = when (raw) {
        is BigDecimal -> raw.toBigIntegerExact()
        is BigInteger -> raw
        is Byte, is Short, is Int, is Long -> BigInteger.valueOf((raw as Number).toLong())
        is Float, is Double -> BigDecimal.valueOf((raw as Number).toDouble()).toBigIntegerExact()
        else -> throw IllegalArgumentException("Snapshot verification document has no numeric version.")
    }
    require(version.signum() >= 0) { "Snapshot verification version must not be negative." }
}
