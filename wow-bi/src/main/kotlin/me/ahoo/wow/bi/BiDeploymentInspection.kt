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

package me.ahoo.wow.bi

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.exception.ErrorInfoCapable
import me.ahoo.wow.serialization.JsonSerializer
import reactor.core.publisher.Mono
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

fun interface BiDeploymentInspector {
    val allowsDynamicScope: Boolean
        get() = false

    fun inspect(
        options: BiScriptOptions,
        operation: BiScriptOperation,
        preparation: BiScriptPreparation,
    ): Mono<BiDeploymentInspection>
}

data object NoOpBiDeploymentInspector : BiDeploymentInspector {
    override val allowsDynamicScope: Boolean = true

    override fun inspect(
        options: BiScriptOptions,
        operation: BiScriptOperation,
        preparation: BiScriptPreparation,
    ): Mono<BiDeploymentInspection> =
        Mono.just(BiDeploymentInspection.Unavailable)
}

sealed interface BiDeploymentInspection {
    data object Unavailable : BiDeploymentInspection

    class Available private constructor(
        val deployment: ObservedBiDeployment,
        internal val reconciliation: BiReconciliationSnapshot,
    ) : BiDeploymentInspection {
        constructor(deployment: ObservedBiDeployment) : this(deployment, BiReconciliationSnapshot.EMPTY)

        internal companion object {
            fun reconciled(
                deployment: ObservedBiDeployment,
                repairableComputedDrifts: List<RepairableBiObjectDrift>,
                verifiedComputedKeys: Set<BiObjectKey> = emptySet(),
            ): Available = Available(
                deployment,
                BiReconciliationSnapshot(
                    Collections.unmodifiableList(ArrayList(repairableComputedDrifts)),
                    Collections.unmodifiableSet(LinkedHashSet(verifiedComputedKeys)),
                ),
            )
        }
    }
}

internal data class BiReconciliationSnapshot(
    val repairableComputedDrifts: List<RepairableBiObjectDrift>,
    /** Computed objects whose observed SELECT and target match the requested deployment exactly. */
    val verifiedComputedKeys: Set<BiObjectKey> = emptySet(),
) {
    companion object {
        val EMPTY = BiReconciliationSnapshot(emptyList())
    }
}

sealed class BiDeploymentInspectionException(
    errorCode: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause), ErrorInfoCapable {
    override val errorInfo: ErrorInfo = ErrorInfo.of(errorCode, message)

    class Inconsistent(
        message: String,
        cause: Throwable? = null,
    ) : BiDeploymentInspectionException(INCONSISTENT_ERROR_CODE, message, cause)

    class Unavailable(
        message: String = "BI deployment inspection is unavailable",
        cause: Throwable? = null,
    ) : BiDeploymentInspectionException(UNAVAILABLE_ERROR_CODE, message, cause)

    class Timeout(
        message: String = "BI deployment inspection timed out",
        cause: Throwable? = null,
    ) : BiDeploymentInspectionException(TIMEOUT_ERROR_CODE, message, cause)

    companion object {
        const val INCONSISTENT_ERROR_CODE: String = "BiDeploymentInspectionInconsistent"
        const val UNAVAILABLE_ERROR_CODE: String = "BiDeploymentInspectionUnavailable"
        const val TIMEOUT_ERROR_CODE: String = "BiDeploymentInspectionTimeout"
    }
}

data class ObservedBiDeployment(val objects: List<ObservedBiObject>) {
    init {
        val duplicateKeys = objects.groupingBy(ObservedBiObject::key)
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
            .map { key -> "${key.database}.${key.name}" }
            .sorted()
        require(duplicateKeys.isEmpty()) {
            "Observed BI deployment contains duplicate catalog objects: ${duplicateKeys.joinToString()}"
        }
    }

    val ownedObjects: List<ObservedBiObject>
        get() = objects.filter { it.metadata != null }
}

data class ObservedBiObject(
    val database: String,
    val name: String,
    val engine: String,
    val engineFull: String = "",
    val createTableQuery: String = "",
    val metadata: BiObjectMetadata? = null,
) {
    val key: BiObjectKey = BiObjectKey(database, name)
}

data class BiObjectKey(val database: String, val name: String)

internal data class BiOwnedObject(
    val key: BiObjectKey,
    val kind: BiObjectKind,
)

enum class BiObjectKind {
    ANCHOR,
    STORE,
    VIEW,
    QUEUE,
    CONSUMER,
}

enum class BiDeploymentPhase {
    STABLE,
    RESETTING,
}

/**
 * Ownership written into every BI catalog object's comment.
 *
 * Deployment-level facts live only on the anchor ([anchor]). An object from another layout keeps its ownership
 * fields readable so that RESET can remove it; every other operation rejects it.
 */
data class BiObjectMetadata(
    val layoutVersion: Int = CURRENT_LAYOUT_VERSION,
    val deploymentId: String,
    val kind: BiObjectKind,
    val aggregate: String? = null,
    val anchor: BiAnchorState? = null,
) {
    init {
        require(DIGEST_PATTERN.matches(deploymentId)) { "Invalid BI deploymentId: $deploymentId" }
        require(kind == BiObjectKind.ANCHOR || aggregate != null) {
            "BI catalog object [$kind] requires an aggregate owner"
        }
        require(anchor == null || kind == BiObjectKind.ANCHOR) {
            "BI anchor state is only valid on the deployment anchor"
        }
        require(!isCurrentLayout || kind != BiObjectKind.ANCHOR || anchor != null) {
            "BI deployment anchor requires its anchor state"
        }
    }

    val isCurrentLayout: Boolean
        get() = layoutVersion == CURRENT_LAYOUT_VERSION

    companion object {
        const val CURRENT_LAYOUT_VERSION: Int = 8
    }
}

/** The deployment-level facts, recorded once on the anchor and rewritten as the last statement of every script. */
data class BiAnchorState(
    val phase: BiDeploymentPhase,
    val configurationFingerprint: String,
    val topologyFingerprint: String,
    val consumerIdentity: String,
    val durableInventory: List<BiDurableEntry> = emptyList(),
) {
    init {
        require(DIGEST_PATTERN.matches(configurationFingerprint)) {
            "Invalid BI configurationFingerprint: $configurationFingerprint"
        }
        require(DIGEST_PATTERN.matches(topologyFingerprint)) {
            "Invalid BI topologyFingerprint: $topologyFingerprint"
        }
        BiConsumerIdentity(consumerIdentity)
        require(durableInventory.map(BiDurableEntry::key).distinct().size == durableInventory.size) {
            "BI durable inventory contains duplicate objects"
        }
    }
}

/**
 * A store or queue that the deployment has created.
 *
 * A recorded durable object that disappears means lost data or lost offsets, so DEPLOY refuses and asks for RESET.
 */
data class BiDurableEntry(val key: BiObjectKey, val status: BiDurableStatus)

enum class BiDurableStatus {
    ACTIVE,

    /** Kept for its data after its aggregate left the deployment. */
    RETIRED,
}

object BiObjectMetadataCodec {
    fun encode(metadata: BiObjectMetadata): String =
        BI_OBJECT_METADATA_PREFIX + JsonSerializer.writeValueAsString(
            BiObjectMetadataWire(
                layoutVersion = metadata.layoutVersion,
                deploymentId = metadata.deploymentId,
                kind = metadata.kind,
                aggregate = metadata.aggregate,
                anchor = metadata.anchor,
            )
        )

    fun decode(comment: String): BiObjectMetadata? {
        if (!comment.startsWith(BI_OBJECT_METADATA_PREFIX)) {
            return null
        }
        val wire = JsonSerializer.readValue(
            comment.removePrefix(BI_OBJECT_METADATA_PREFIX),
            BiObjectMetadataWire::class.java,
        )
        return BiObjectMetadata(
            layoutVersion = wire.layoutVersion,
            deploymentId = wire.deploymentId,
            kind = wire.kind,
            aggregate = wire.aggregate,
            anchor = wire.anchor.takeIf { wire.layoutVersion == BiObjectMetadata.CURRENT_LAYOUT_VERSION },
        )
    }
}

internal const val BI_OBJECT_METADATA_PREFIX: String = "wow-bi:"

private val DIGEST_PATTERN = Regex("[0-9a-f]{32}")

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
private data class BiObjectMetadataWire(
    val layoutVersion: Int,
    val deploymentId: String,
    val kind: BiObjectKind,
    val aggregate: String? = null,
    val anchor: BiAnchorState? = null,
)

@JvmInline
value class BiConsumerIdentity(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Invalid BI consumer identity: $value" }
    }

    companion object {
        private val PATTERN = Regex("[0-9a-f]{32}")

        fun deterministic(descriptor: BiDeploymentDescriptor): BiConsumerIdentity =
            BiConsumerIdentity(descriptor.configurationFingerprint)

        fun random(): BiConsumerIdentity =
            BiConsumerIdentity(sha256(UUID.randomUUID().toString()))
    }
}

data class BiDeploymentDescriptor(
    val deploymentId: String,
    val configurationFingerprint: String,
    val topologyFingerprint: String,
) {
    companion object {
        fun from(options: BiScriptOptions): BiDeploymentDescriptor {
            val cluster = options.topology as? ClickHouseTopology.Cluster
            val deploymentId = sha256(
                listOf(
                    options.consumerGroupNamespace.orEmpty(),
                    options.database,
                    options.consumerDatabase,
                ).joinToString("\u0000")
            )
            val configurationFingerprint = sha256(
                listOf(
                    options.database,
                    options.consumerDatabase,
                    if (cluster == null) "STANDALONE" else "CLUSTER",
                    cluster?.name.orEmpty(),
                    cluster?.installation.orEmpty(),
                    options.timezone,
                    options.kafkaBootstrapServers,
                    options.topicPrefix,
                    options.consumerGroupNamespace.orEmpty(),
                    options.kafkaOffsetStorage.name,
                    options.kafkaKeeperPathPrefix,
                ).joinToString("\u0000")
            )
            val topologyFingerprint = sha256(
                listOf(
                    options.database,
                    options.consumerDatabase,
                    if (cluster == null) "STANDALONE" else "CLUSTER",
                    cluster?.name.orEmpty(),
                    cluster?.installation.orEmpty(),
                ).joinToString("\u0000")
            )
            return BiDeploymentDescriptor(deploymentId, configurationFingerprint, topologyFingerprint)
        }
    }
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
