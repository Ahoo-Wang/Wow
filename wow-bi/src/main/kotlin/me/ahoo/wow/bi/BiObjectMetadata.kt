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
import me.ahoo.wow.serialization.JsonSerializer

/**
 * Ownership written into every BI catalog object's comment.
 *
 * Deployment-level facts live only on the anchor ([anchor]). An object from another layout keeps its ownership
 * fields readable so that RESET can remove it; every other operation rejects it.
 */
internal data class BiObjectMetadata(
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
internal data class BiAnchorState(
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
internal data class BiDurableEntry(val key: BiObjectKey, val status: BiDurableStatus)

internal enum class BiDurableStatus {
    ACTIVE,

    /** Kept for its data after its aggregate left the deployment. */
    RETIRED,
}

internal object BiObjectMetadataCodec {
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
