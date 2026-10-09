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

import me.ahoo.wow.bi.renderer.ClickHouseScriptRenderer

internal class BiObservedDeploymentPolicy(private val options: BiScriptOptions) {
    fun validate(
        deployment: ObservedBiDeployment,
        descriptor: BiDeploymentDescriptor,
        desiredObjects: List<DesiredBiObject>,
        operation: BiScriptOperation,
    ) = with(deployment) {
        val desiredByKey = desiredObjects.associateBy(DesiredBiObject::key)
        validateLayout(descriptor, operation)
        validateDeploymentAnchor(descriptor, operation)
        objects.forEach { observed ->
            validateObservedObject(observed, desiredByKey[observed.key], descriptor, operation)
        }
        if (operation == BiScriptOperation.Deploy) {
            validateDurableInventory(descriptor, desiredByKey.keys)
        }
    }

    fun ownedBy(deployment: ObservedBiDeployment, descriptor: BiDeploymentDescriptor): List<ObservedBiObject> =
        deployment.ownedObjects.filter { it.metadata?.deploymentId == descriptor.deploymentId }

    fun anchorState(deployment: ObservedBiDeployment, descriptor: BiDeploymentDescriptor): BiAnchorState? =
        deployment.objects.firstOrNull { observed ->
            observed.key == desiredAnchorKey() && observed.metadata?.deploymentId == descriptor.deploymentId
        }?.metadata?.anchor

    fun resettingAnchor(deployment: ObservedBiDeployment, descriptor: BiDeploymentDescriptor): BiAnchorState? =
        anchorState(deployment, descriptor)?.takeIf { it.phase == BiDeploymentPhase.RESETTING }

    fun consumerIdentity(deployment: ObservedBiDeployment, descriptor: BiDeploymentDescriptor): BiConsumerIdentity? =
        anchorState(deployment, descriptor)?.consumerIdentity?.let(::BiConsumerIdentity)

    private fun ObservedBiDeployment.validateLayout(descriptor: BiDeploymentDescriptor, operation: BiScriptOperation) {
        if (operation != BiScriptOperation.Deploy) {
            return
        }
        val foreignLayouts = ownedBy(this, descriptor)
            .mapNotNull { observed -> observed.metadata?.layoutVersion }
            .filter { layout -> layout != BiObjectMetadata.CURRENT_LAYOUT_VERSION }
            .distinct()
        require(foreignLayouts.isEmpty()) {
            "Observed BI deployment uses layout $foreignLayouts; RESET is required to rebuild it with layout " +
                "${BiObjectMetadata.CURRENT_LAYOUT_VERSION}"
        }
    }

    private fun ObservedBiDeployment.validateDeploymentAnchor(
        descriptor: BiDeploymentDescriptor,
        operation: BiScriptOperation,
    ) {
        val deploymentAnchors = objects.filter { observed ->
            observed.metadata?.deploymentId == descriptor.deploymentId &&
                observed.metadata.kind == BiObjectKind.ANCHOR
        }
        require(deploymentAnchors.size <= 1) {
            "Observed BI deployment contains multiple deployment anchors: " +
                deploymentAnchors.map { anchor -> "${anchor.database}.${anchor.name}" }.sorted()
        }
        deploymentAnchors.singleOrNull()?.let { anchor ->
            val canonicalAnchor = desiredAnchorKey()
            require(anchor.key == canonicalAnchor) {
                "Observed BI deployment anchor must use canonical key " +
                    "[${canonicalAnchor.database}.${canonicalAnchor.name}], but found " +
                    "[${anchor.database}.${anchor.name}]"
            }
        }
        val state = anchorState(this, descriptor) ?: return
        require(state.topologyFingerprint == descriptor.topologyFingerprint) {
            "Observed BI deployment topology differs from the requested topology and cannot be changed through " +
                "DEPLOY or RESET"
        }
        when (operation) {
            BiScriptOperation.Deploy -> {
                require(state.phase != BiDeploymentPhase.RESETTING) {
                    "Observed BI deployment is RESETTING; retry RESET with the same configuration"
                }
                require(state.configurationFingerprint == descriptor.configurationFingerprint) {
                    "Observed BI deployment configuration differs from the requested configuration; use RESET"
                }
            }

            is BiScriptOperation.Reset -> require(
                state.phase != BiDeploymentPhase.RESETTING ||
                    state.configurationFingerprint == descriptor.configurationFingerprint
            ) {
                "Observed BI deployment is RESETTING with a different configuration; " +
                    "retry RESET with the original configuration"
            }
        }
    }

    /** A recorded store or queue that is desired but gone means lost data or lost offsets. */
    private fun ObservedBiDeployment.validateDurableInventory(
        descriptor: BiDeploymentDescriptor,
        desiredKeys: Set<BiObjectKey>,
    ) {
        val observedKeys = objects.mapTo(hashSetOf(), ObservedBiObject::key)
        val lost = anchorState(this, descriptor)?.durableInventory.orEmpty()
            .map(BiDurableEntry::key)
            .filter { key -> key in desiredKeys && key !in observedKeys }
        require(lost.isEmpty()) {
            "Observed BI deployment lost recorded durable objects " +
                lost.joinToString(prefix = "[", postfix = "]") { key -> "${key.database}.${key.name}" } +
                "; RESET is required to rebuild them from Kafka"
        }
    }

    private fun validateObservedObject(
        observed: ObservedBiObject,
        desired: DesiredBiObject?,
        descriptor: BiDeploymentDescriptor,
        operation: BiScriptOperation,
    ) {
        val metadata = observed.metadata
        if (desired != null) {
            require(metadata != null && metadata.deploymentId == descriptor.deploymentId) {
                "BI object [${observed.database}.${observed.name}] is occupied by a foreign catalog object"
            }
            require(metadata.kind == desired.kind && metadata.aggregate == desired.aggregate) {
                "BI object [${observed.database}.${observed.name}] has inconsistent ownership metadata"
            }
            if (desired.kind == BiObjectKind.ANCHOR) {
                require(observed.engine == desired.expectedEngine) {
                    "BI deployment anchor [${observed.database}.${observed.name}] must use the View engine"
                }
            } else if (operation == BiScriptOperation.Deploy) {
                require(observed.engine == desired.expectedEngine) {
                    "BI object [${observed.database}.${observed.name}] has incompatible engine " +
                        "[${observed.engine}]; expected engine [${desired.expectedEngine}]"
                }
            } else {
                require(desired.kind.acceptsEngine(observed.engine, allowStoreDrift = true)) {
                    "BI object [${observed.database}.${observed.name}] kind [${desired.kind}] has incompatible " +
                        "engine [${observed.engine}] for RESET"
                }
            }
        } else if (metadata?.deploymentId == descriptor.deploymentId) {
            require(metadata.kind.acceptsEngine(observed.engine)) {
                "BI object [${observed.database}.${observed.name}] kind [${metadata.kind}] has incompatible engine " +
                    "[${observed.engine}]"
            }
        }
    }

    private fun BiObjectKind.acceptsEngine(
        engine: String,
        allowStoreDrift: Boolean = false,
    ): Boolean = when (this) {
        BiObjectKind.ANCHOR,
        BiObjectKind.VIEW,
        -> engine == "View"

        BiObjectKind.STORE ->
            if (allowStoreDrift) engine !in VIEW_ENGINES && engine != "Kafka" else engine in STORE_ENGINES

        BiObjectKind.QUEUE -> engine == "Kafka"
        BiObjectKind.CONSUMER -> engine == "MaterializedView"
    }

    private fun desiredAnchorKey(): BiObjectKey =
        BiObjectKey(options.consumerDatabase, ClickHouseScriptRenderer.DEPLOYMENT_ANCHOR)

    private companion object {
        val STORE_ENGINES: Set<String> = setOf(
            "ReplacingMergeTree",
            "ReplicatedReplacingMergeTree",
            "Distributed",
        )
        val VIEW_ENGINES: Set<String> = setOf("View", "MaterializedView")
    }
}
