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

import me.ahoo.wow.serialization.JsonSerializer
import reactor.core.publisher.Mono
import java.security.MessageDigest
import java.util.UUID

fun interface BiDeploymentInspector {
    fun inspect(options: BiScriptOptions): Mono<BiDeploymentInspection>
}

data object NoOpBiDeploymentInspector : BiDeploymentInspector {
    override fun inspect(options: BiScriptOptions): Mono<BiDeploymentInspection> =
        Mono.just(BiDeploymentInspection.Unavailable)
}

sealed interface BiDeploymentInspection {
    data object Unavailable : BiDeploymentInspection

    data class Available(val deployment: ObservedBiDeployment) : BiDeploymentInspection
}

data class ObservedBiDeployment(val objects: List<ObservedBiObject>) {
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

data class BiObjectMetadata(
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
    val layoutVersion: Int = CURRENT_LAYOUT_VERSION,
    val deploymentId: String,
    val configurationFingerprint: String,
    val aggregate: String? = null,
    val kind: BiObjectKind,
    val consumerIdentity: String? = null,
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
        consumerIdentity?.let(::BiConsumerIdentity)
        require(kind == BiObjectKind.ANCHOR || aggregate != null) {
            "BI catalog object [$kind] requires an aggregate owner"
        }
    }

    companion object {
        const val CURRENT_PROTOCOL_VERSION: Int = 1
        const val CURRENT_LAYOUT_VERSION: Int = 6
        private val DIGEST_PATTERN = Regex("[0-9a-f]{32}")
    }
}

object BiObjectMetadataCodec {
    private const val PREFIX = "wow-bi:"

    fun encode(metadata: BiObjectMetadata): String =
        PREFIX + JsonSerializer.writeValueAsString(metadata)

    fun decode(comment: String): BiObjectMetadata? {
        if (!comment.startsWith(PREFIX)) {
            return null
        }
        return JsonSerializer.readValue(comment.removePrefix(PREFIX), BiObjectMetadata::class.java)
    }
}

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
            return BiDeploymentDescriptor(deploymentId, configurationFingerprint)
        }
    }
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
