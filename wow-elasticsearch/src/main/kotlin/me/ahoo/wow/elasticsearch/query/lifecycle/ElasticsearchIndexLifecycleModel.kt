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
    me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)

package me.ahoo.wow.elasticsearch.query.lifecycle

import me.ahoo.wow.elasticsearch.IndexNameConverter.toEventStreamIndexName
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.query.backend.SchemaContractId
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryTarget
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.LinkedHashMap

@JvmInline
internal value class ElasticsearchIndexMigrationId(val value: String) {
    init {
        requireIdentifier(value, "Elasticsearch index migration id")
    }
}

@JvmInline
internal value class ElasticsearchIndexLifecycleCommandId(val value: String) {
    init {
        requireIdentifier(value, "Elasticsearch index lifecycle command id")
    }
}

@JvmInline
internal value class ElasticsearchIndexMappingVersion(val value: Int) {
    init {
        require(value in 1..MAX_MAPPING_VERSION) {
            "Elasticsearch index mapping version must be between 1 and $MAX_MAPPING_VERSION."
        }
    }

    val tag: String
        get() = "v%04d".format(value)
}

@JvmInline
internal value class ElasticsearchIndexGeneration(val value: Int) {
    init {
        require(value in 1..MAX_GENERATION) {
            "Elasticsearch index generation must be between 1 and $MAX_GENERATION."
        }
    }

    val tag: String
        get() = "%06d".format(value)
}

@JvmInline
internal value class ElasticsearchIndexAlias(val value: String) {
    init {
        requireIndexName(value, "Elasticsearch index alias")
    }
}

@JvmInline
internal value class ElasticsearchPhysicalIndex(val value: String) {
    init {
        requireIndexName(value, "Elasticsearch physical index")
    }
}

@JvmInline
internal value class ElasticsearchIndexCapabilityDigest(val value: String) {
    init {
        requireSha256(value, "Elasticsearch index capability digest")
    }
}

@JvmInline
internal value class ElasticsearchIndexChecksum(val value: String) {
    init {
        requireSha256(value, "Elasticsearch index checksum")
    }
}

internal enum class ElasticsearchIndexChecksumAlgorithm {
    CANONICAL_DOCUMENT_SHA256_V1,
    CANONICAL_EVENT_STREAM_SHA256_V1,
}

@JvmInline
internal value class ElasticsearchIndexProbeSuiteId(val value: String) {
    init {
        requireIdentifier(value, "Elasticsearch index probe suite id")
    }
}

/** Pins the data checksum and query-probe semantics for the full migration lifecycle. */
internal data class ElasticsearchIndexVerificationContract(
    val checksumAlgorithm: ElasticsearchIndexChecksumAlgorithm,
    val probeSuiteId: ElasticsearchIndexProbeSuiteId,
)

internal data class ElasticsearchIndexNames(
    val alias: ElasticsearchIndexAlias,
    val physical: ElasticsearchPhysicalIndex,
) {
    companion object {
        fun of(
            target: QueryTarget,
            mappingVersion: ElasticsearchIndexMappingVersion,
            generation: ElasticsearchIndexGeneration,
        ): ElasticsearchIndexNames {
            val alias = when (target.documentKind) {
                QueryDocumentKind.SNAPSHOT -> target.namedAggregate.toSnapshotIndexName()
                QueryDocumentKind.EVENT_STREAM -> target.namedAggregate.toEventStreamIndexName()
            }
            return ElasticsearchIndexNames(
                ElasticsearchIndexAlias(alias),
                ElasticsearchPhysicalIndex("$alias-${mappingVersion.tag}-${generation.tag}"),
            )
        }
    }
}

internal enum class ElasticsearchIndexRebuildStrategy {
    SNAPSHOT_FROM_EVENT_STREAM,
    EVENT_STREAM_PAUSE_AND_DRAIN,
    EVENT_STREAM_CONTROLLED_MIRROR,
}

internal data class ElasticsearchIndexMigrationManifest(
    val id: ElasticsearchIndexMigrationId,
    val target: QueryTarget,
    val mappingVersion: ElasticsearchIndexMappingVersion,
    val generation: ElasticsearchIndexGeneration,
    val schemaContractId: SchemaContractId,
    val capabilityDigest: ElasticsearchIndexCapabilityDigest,
    val sourcePhysicalIndex: ElasticsearchPhysicalIndex,
    val rebuildStrategy: ElasticsearchIndexRebuildStrategy,
    val verificationContract: ElasticsearchIndexVerificationContract,
    val maxCursorTtl: Duration,
    val rollbackWindow: Duration,
) {
    val names: ElasticsearchIndexNames = ElasticsearchIndexNames.of(target, mappingVersion, generation)
    val minimumRetention: Duration
    val destinationAttestation: ElasticsearchIndexAttestation

    init {
        require(!maxCursorTtl.isNegative && !maxCursorTtl.isZero) {
            "Maximum cursor TTL must be positive."
        }
        require(!rollbackWindow.isNegative && !rollbackWindow.isZero) {
            "Elasticsearch rollback window must be positive."
        }
        require(sourcePhysicalIndex != names.physical) {
            "Elasticsearch migration source and destination must differ."
        }
        requireStrategyMatchesTarget(target.documentKind, rebuildStrategy)
        requireVerificationContractMatchesTarget(target.documentKind, verificationContract)
        minimumRetention = try {
            maxCursorTtl.plus(rollbackWindow)
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("Elasticsearch index retention duration overflows.", error)
        }
        destinationAttestation = ElasticsearchIndexAttestation(
            names.physical,
            mappingVersion,
            target.documentKind,
            schemaContractId,
            capabilityDigest,
        )
    }

    fun validate(inventory: ElasticsearchIndexInventory) {
        if (inventory.alias != names.alias) {
            reject(
                ElasticsearchIndexLifecycleErrorCode.VALIDATION_FAILED,
                id,
                "Inventory alias [${inventory.alias.value}] does not match [${names.alias.value}].",
            )
        }
        if (inventory.aliasTarget != sourcePhysicalIndex) {
            reject(
                ElasticsearchIndexLifecycleErrorCode.ALIAS_CONFLICT,
                id,
                "Alias [${names.alias.value}] changed from the expected migration source.",
            )
        }
        if (sourcePhysicalIndex !in inventory.indices) {
            reject(
                ElasticsearchIndexLifecycleErrorCode.VALIDATION_FAILED,
                id,
                "Elasticsearch migration source [${sourcePhysicalIndex.value}] is missing.",
            )
        }
        inventory.indices[names.physical]?.let { actual ->
            if (actual != destinationAttestation) {
                reject(
                    ElasticsearchIndexLifecycleErrorCode.ATTESTATION_MISMATCH,
                    id,
                    "Elasticsearch destination [${names.physical.value}] has incompatible mapping metadata.",
                )
            }
        }
    }
}

internal data class ElasticsearchIndexAttestation(
    val physicalIndex: ElasticsearchPhysicalIndex,
    val mappingVersion: ElasticsearchIndexMappingVersion,
    val documentKind: QueryDocumentKind,
    val schemaContractId: SchemaContractId,
    val capabilityDigest: ElasticsearchIndexCapabilityDigest,
)

internal class ElasticsearchIndexInventory(
    val alias: ElasticsearchIndexAlias,
    val aliasTarget: ElasticsearchPhysicalIndex?,
    indices: Map<ElasticsearchPhysicalIndex, ElasticsearchIndexAttestation>,
    val observedAt: Instant,
) {
    val indices: Map<ElasticsearchPhysicalIndex, ElasticsearchIndexAttestation>

    init {
        val copy = LinkedHashMap<ElasticsearchPhysicalIndex, ElasticsearchIndexAttestation>(indices.size)
        indices.entries.sortedBy { entry -> entry.key.value }.forEach { (physical, attestation) ->
            require(physical == attestation.physicalIndex) {
                "Elasticsearch inventory key and attestation physical index must match."
            }
            copy[physical] = attestation
        }
        this.indices = Collections.unmodifiableMap(copy)
        require(aliasTarget == null || aliasTarget in this.indices) {
            "Elasticsearch alias target must be present in the physical index inventory."
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ElasticsearchIndexInventory &&
            alias == other.alias &&
            aliasTarget == other.aliasTarget &&
            indices == other.indices &&
            observedAt == other.observedAt

    override fun hashCode(): Int {
        var result = alias.hashCode()
        result = 31 * result + (aliasTarget?.hashCode() ?: 0)
        result = 31 * result + indices.hashCode()
        result = 31 * result + observedAt.hashCode()
        return result
    }
}

internal data class ElasticsearchIndexRebuildReceipt(
    val physicalIndex: ElasticsearchPhysicalIndex,
    val strategy: ElasticsearchIndexRebuildStrategy,
    val authoritativeWatermark: Long?,
    val indexedWatermark: Long?,
    val completedAt: Instant,
) {
    init {
        require(authoritativeWatermark == null || authoritativeWatermark >= 0) {
            "Authoritative watermark must not be negative."
        }
        require(indexedWatermark == null || indexedWatermark >= 0) {
            "Indexed watermark must not be negative."
        }
        require((authoritativeWatermark == null) == (indexedWatermark == null)) {
            "Rebuild authoritative and indexed watermarks must be present together."
        }
    }

    fun requireMatches(manifest: ElasticsearchIndexMigrationManifest) {
        val eventStreamWatermarkSatisfied = manifest.target.documentKind != QueryDocumentKind.EVENT_STREAM ||
            authoritativeWatermark != null
        val identitySatisfied = physicalIndex == manifest.names.physical && strategy == manifest.rebuildStrategy
        val watermarkSatisfied = authoritativeWatermark == indexedWatermark && eventStreamWatermarkSatisfied
        if (
            !identitySatisfied ||
            !watermarkSatisfied
        ) {
            reject(
                ElasticsearchIndexLifecycleErrorCode.VALIDATION_FAILED,
                manifest.id,
                "Elasticsearch rebuild receipt does not match the migration manifest.",
            )
        }
    }
}

@Suppress("LongParameterList")
internal data class ElasticsearchIndexVerification(
    val physicalIndex: ElasticsearchPhysicalIndex,
    val expectedCount: Long,
    val actualCount: Long,
    val expectedIdentityChecksum: ElasticsearchIndexChecksum,
    val actualIdentityChecksum: ElasticsearchIndexChecksum,
    val expectedContentChecksum: ElasticsearchIndexChecksum,
    val actualContentChecksum: ElasticsearchIndexChecksum,
    val versionContinuity: Boolean,
    val authoritativeWatermark: Long?,
    val indexedWatermark: Long?,
    val recordProbeMismatchCount: Long,
    val analyticsProbeMismatchCount: Long,
    val verifiedAt: Instant,
) {
    init {
        require(expectedCount >= 0 && actualCount >= 0) { "Verification counts must not be negative." }
        require(recordProbeMismatchCount >= 0 && analyticsProbeMismatchCount >= 0) {
            "Verification mismatch counts must not be negative."
        }
        require(authoritativeWatermark == null || authoritativeWatermark >= 0) {
            "Authoritative watermark must not be negative."
        }
        require(indexedWatermark == null || indexedWatermark >= 0) {
            "Indexed watermark must not be negative."
        }
    }

    fun requireSatisfied(
        manifest: ElasticsearchIndexMigrationManifest,
        expectedIndex: ElasticsearchPhysicalIndex,
    ) {
        val satisfied = physicalIndex == expectedIndex &&
            expectedCount == actualCount &&
            expectedIdentityChecksum == actualIdentityChecksum &&
            expectedContentChecksum == actualContentChecksum &&
            versionContinuity &&
            authoritativeWatermark == indexedWatermark &&
            (manifest.target.documentKind != QueryDocumentKind.EVENT_STREAM || authoritativeWatermark != null) &&
            recordProbeMismatchCount == 0L &&
            analyticsProbeMismatchCount == 0L
        if (!satisfied) {
            reject(
                ElasticsearchIndexLifecycleErrorCode.VERIFICATION_FAILED,
                manifest.id,
                "Elasticsearch index verification failed for [${physicalIndex.value}].",
            )
        }
    }
}

internal data class ElasticsearchAliasTransition(
    val alias: ElasticsearchIndexAlias,
    val previous: ElasticsearchPhysicalIndex?,
    val current: ElasticsearchPhysicalIndex,
    val transitionedAt: Instant,
)

internal enum class ElasticsearchIndexLifecycleErrorCode {
    MIGRATION_NOT_FOUND,
    MIGRATION_CONFLICT,
    COMMAND_CONFLICT,
    INVALID_TRANSITION,
    STATE_CONFLICT,
    VALIDATION_FAILED,
    ATTESTATION_MISMATCH,
    VERIFICATION_FAILED,
    ALIAS_CONFLICT,
    CUTOVER_FENCE_REQUIRED,
    OPERATION_FAILED,
    REPOSITORY_CORRUPTED,
}

internal class ElasticsearchIndexLifecycleException(
    val code: ElasticsearchIndexLifecycleErrorCode,
    val migrationId: ElasticsearchIndexMigrationId,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal fun reject(
    code: ElasticsearchIndexLifecycleErrorCode,
    migrationId: ElasticsearchIndexMigrationId,
    message: String,
): Nothing = throw ElasticsearchIndexLifecycleException(code, migrationId, message)

private fun requireStrategyMatchesTarget(
    documentKind: QueryDocumentKind,
    strategy: ElasticsearchIndexRebuildStrategy,
) {
    val matches = when (documentKind) {
        QueryDocumentKind.SNAPSHOT -> strategy == ElasticsearchIndexRebuildStrategy.SNAPSHOT_FROM_EVENT_STREAM
        QueryDocumentKind.EVENT_STREAM -> strategy != ElasticsearchIndexRebuildStrategy.SNAPSHOT_FROM_EVENT_STREAM
    }
    require(matches) { "Elasticsearch rebuild strategy $strategy does not match $documentKind." }
}

private fun requireVerificationContractMatchesTarget(
    documentKind: QueryDocumentKind,
    contract: ElasticsearchIndexVerificationContract,
) {
    val expected = when (documentKind) {
        QueryDocumentKind.SNAPSHOT -> ElasticsearchIndexChecksumAlgorithm.CANONICAL_DOCUMENT_SHA256_V1
        QueryDocumentKind.EVENT_STREAM -> ElasticsearchIndexChecksumAlgorithm.CANONICAL_EVENT_STREAM_SHA256_V1
    }
    require(contract.checksumAlgorithm == expected) {
        "Elasticsearch checksum algorithm ${contract.checksumAlgorithm} does not match $documentKind."
    }
}

private fun requireIdentifier(value: String, label: String) {
    require(value.matches(IDENTIFIER_PATTERN)) {
        "$label must match ${IDENTIFIER_PATTERN.pattern}."
    }
}

private fun requireIndexName(value: String, label: String) {
    require(value.matches(INDEX_NAME_PATTERN) && ".." !in value) {
        "$label is not a valid managed index name."
    }
}

private fun requireSha256(value: String, label: String) {
    require(value.matches(SHA_256_PATTERN)) { "$label must be a lowercase SHA-256 hex string." }
}

private const val MAX_MAPPING_VERSION = 9_999
private const val MAX_GENERATION = 999_999
private val IDENTIFIER_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private val INDEX_NAME_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,254}")
private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
