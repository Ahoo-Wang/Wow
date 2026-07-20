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
                ownershipRegistry: BiOwnershipRegistry?,
            ): Available = Available(
                deployment,
                BiReconciliationSnapshot(
                    Collections.unmodifiableList(ArrayList(repairableComputedDrifts)),
                    ownershipRegistry,
                ),
            )
        }
    }
}

internal data class BiReconciliationSnapshot(
    val repairableComputedDrifts: List<RepairableBiObjectDrift>,
    val ownershipRegistry: BiOwnershipRegistry?,
) {
    companion object {
        val EMPTY = BiReconciliationSnapshot(emptyList(), null)
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

data class BiObjectMetadata(
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
    val layoutVersion: Int = CURRENT_LAYOUT_VERSION,
    val phase: BiDeploymentPhase = BiDeploymentPhase.STABLE,
    val deploymentId: String,
    val configurationFingerprint: String,
    val topologyFingerprint: String,
    val aggregate: String? = null,
    val kind: BiObjectKind,
    val consumerIdentity: String? = null,
    val registryRevision: Long? = null,
) {
    init {
        require(protocolVersion == CURRENT_PROTOCOL_VERSION) {
            "Unsupported BI object metadata protocol version: $protocolVersion"
        }
        require(layoutVersion == CURRENT_LAYOUT_VERSION) {
            "Unsupported BI object metadata layout version: $layoutVersion"
        }
        require(DIGEST_PATTERN.matches(deploymentId)) { "Invalid BI deploymentId: $deploymentId" }
        require(DIGEST_PATTERN.matches(configurationFingerprint)) {
            "Invalid BI configurationFingerprint: $configurationFingerprint"
        }
        require(DIGEST_PATTERN.matches(topologyFingerprint)) {
            "Invalid BI topologyFingerprint: $topologyFingerprint"
        }
        consumerIdentity?.let(::BiConsumerIdentity)
        require(kind == BiObjectKind.ANCHOR || aggregate != null) {
            "BI catalog object [$kind] requires an aggregate owner"
        }
        require(phase != BiDeploymentPhase.RESETTING || kind == BiObjectKind.ANCHOR) {
            "BI RESETTING phase is only valid for the deployment anchor"
        }
        require(phase != BiDeploymentPhase.RESETTING || consumerIdentity != null) {
            "BI RESETTING deployment anchor requires a consumer identity"
        }
        registryRevision?.let { require(it >= 0) { "registryRevision must not be negative" } }
    }

    companion object {
        const val CURRENT_PROTOCOL_VERSION: Int = 3
        const val CURRENT_LAYOUT_VERSION: Int = 7
        private val DIGEST_PATTERN = Regex("[0-9a-f]{32}")
    }
}

object BiObjectMetadataCodec {
    fun encode(metadata: BiObjectMetadata): String =
        BI_OBJECT_METADATA_PREFIX + JsonSerializer.writeValueAsString(
            BiObjectMetadataWire(
                protocolVersion = metadata.protocolVersion,
                layoutVersion = metadata.layoutVersion,
                phase = metadata.phase,
                deploymentId = metadata.deploymentId,
                configurationFingerprint = metadata.configurationFingerprint,
                topologyFingerprint = metadata.topologyFingerprint,
                aggregate = metadata.aggregate,
                kind = metadata.kind,
                consumerIdentity = metadata.consumerIdentity,
                registryRevision = metadata.registryRevision,
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
        require(wire.protocolVersion == BiObjectMetadata.CURRENT_PROTOCOL_VERSION) {
            "Unsupported BI object metadata protocol version: ${wire.protocolVersion}"
        }
        val phase = requireNotNull(wire.phase) {
            "BI object metadata protocol v${wire.protocolVersion} requires phase"
        }
        val topologyFingerprint = requireNotNull(wire.topologyFingerprint) {
            "BI object metadata protocol v${wire.protocolVersion} requires topologyFingerprint"
        }
        return BiObjectMetadata(
            protocolVersion = wire.protocolVersion,
            layoutVersion = wire.layoutVersion,
            phase = phase,
            deploymentId = wire.deploymentId,
            configurationFingerprint = wire.configurationFingerprint,
            topologyFingerprint = topologyFingerprint,
            aggregate = wire.aggregate,
            kind = wire.kind,
            consumerIdentity = wire.consumerIdentity,
            registryRevision = wire.registryRevision,
        )
    }
}

internal const val BI_OBJECT_METADATA_PREFIX: String = "wow-bi:"

@JsonInclude(JsonInclude.Include.NON_NULL)
private data class BiObjectMetadataWire(
    val protocolVersion: Int,
    val layoutVersion: Int,
    val phase: BiDeploymentPhase? = null,
    val deploymentId: String,
    val configurationFingerprint: String,
    val topologyFingerprint: String? = null,
    val aggregate: String? = null,
    val kind: BiObjectKind,
    val consumerIdentity: String? = null,
    val registryRevision: Long? = null,
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
