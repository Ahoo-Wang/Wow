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
        validateDeploymentTopology(descriptor)
        validateDeploymentAnchor(descriptor, operation)
        objects.forEach { observed ->
            validateObservedObject(observed, desiredByKey[observed.key], descriptor, operation)
        }
        if (operation == BiScriptOperation.Deploy) {
            consumerIdentity(this, descriptor)
        }
    }

    fun ownedBy(deployment: ObservedBiDeployment, descriptor: BiDeploymentDescriptor): List<ObservedBiObject> =
        deployment.ownedObjects.filter { it.metadata?.deploymentId == descriptor.deploymentId }

    fun resettingAnchor(
        deployment: ObservedBiDeployment,
        descriptor: BiDeploymentDescriptor,
    ): ObservedBiObject? = deployment.objects.firstOrNull { observed ->
        observed.key == desiredAnchorKey() &&
            observed.metadata?.deploymentId == descriptor.deploymentId &&
            observed.metadata.phase == BiDeploymentPhase.RESETTING
    }

    fun consumerIdentity(
        deployment: ObservedBiDeployment,
        descriptor: BiDeploymentDescriptor,
    ): BiConsumerIdentity? {
        val ownedObjects = ownedBy(deployment, descriptor)
        val identities = ownedObjects.mapNotNull { observed -> observed.metadata?.consumerIdentity }
            .map(::BiConsumerIdentity)
            .distinct()
        require(identities.size <= 1) {
            "Observed BI deployment contains mixed consumer identities: ${identities.map(BiConsumerIdentity::value)}"
        }
        require(ownedObjects.isEmpty() || identities.isNotEmpty()) {
            "Observed BI deployment is missing its consumer identity anchor"
        }
        return identities.singleOrNull()
    }

    private fun ObservedBiDeployment.validateDeploymentTopology(descriptor: BiDeploymentDescriptor) {
        val currentObjects = ownedBy(this, descriptor)
        if (currentObjects.isEmpty()) {
            return
        }
        val observedTopologies = currentObjects.map { observed ->
            requireNotNull(observed.metadata).topologyFingerprint
        }.distinct()
        require(observedTopologies.size <= 1) {
            "Observed BI deployment contains mixed topology fingerprints: $observedTopologies"
        }
        require(observedTopologies.single() == descriptor.topologyFingerprint) {
            "Observed BI deployment topology differs from the requested topology and cannot be changed through " +
                "DEPLOY or RESET"
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
        resettingAnchor(this, descriptor)?.let { anchor ->
            val metadata = checkNotNull(anchor.metadata)
            when (operation) {
                BiScriptOperation.Deploy -> throw IllegalArgumentException(
                    "Observed BI deployment is RESETTING; retry RESET with the same configuration"
                )

                is BiScriptOperation.Reset -> require(
                    metadata.configurationFingerprint == descriptor.configurationFingerprint
                ) {
                    "Observed BI deployment is RESETTING with a different configuration; " +
                        "retry RESET with the original configuration"
                }
            }
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
            } else {
                require(observed.engine == desired.expectedEngine) {
                    "BI object [${observed.database}.${observed.name}] has incompatible engine " +
                        "[${observed.engine}]; expected engine [${desired.expectedEngine}]"
                }
            }
        } else if (metadata?.deploymentId == descriptor.deploymentId) {
            require(metadata.kind.acceptsEngine(observed.engine)) {
                "BI object [${observed.database}.${observed.name}] kind [${metadata.kind}] has incompatible engine " +
                    "[${observed.engine}]"
            }
        }
        if (metadata?.deploymentId == descriptor.deploymentId && operation == BiScriptOperation.Deploy) {
            require(metadata.configurationFingerprint == descriptor.configurationFingerprint) {
                "Observed BI deployment configuration differs from the requested configuration; use RESET"
            }
        }
    }

    private fun BiObjectKind.acceptsEngine(engine: String): Boolean = when (this) {
        BiObjectKind.ANCHOR,
        BiObjectKind.VIEW,
        -> engine == "View"

        BiObjectKind.STORE -> engine in STORE_ENGINES
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
    }
}
