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

package me.ahoo.wow.bi.catalog

import me.ahoo.wow.bi.BiComputedDefinitionField
import me.ahoo.wow.bi.BiConsumerIdentity
import me.ahoo.wow.bi.BiDeploymentDescriptor
import me.ahoo.wow.bi.BiObjectKey
import me.ahoo.wow.bi.BiObjectKind
import me.ahoo.wow.bi.BiObjectMetadata
import me.ahoo.wow.bi.BiScriptOperation
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.CanonicalExpectedBiQuery
import me.ahoo.wow.bi.ClickHouseTopology
import me.ahoo.wow.bi.DesiredBiObject
import me.ahoo.wow.bi.KafkaOffsetStorage
import me.ahoo.wow.bi.ObservedBiDeployment
import me.ahoo.wow.bi.ObservedBiObject
import me.ahoo.wow.bi.RepairableBiObjectDrift
import me.ahoo.wow.bi.layout.BiEngine
import me.ahoo.wow.bi.layout.BiLayout
import me.ahoo.wow.bi.renderer.ClickHouseSqlSyntax

/**
 * Verifies that the observed ClickHouse catalog is intact: replicas agree, owned stores and queues have their
 * expected shape and identity, and each computed object is classified as verified or drifted.
 *
 * Any violation means the catalog cannot be trusted and surfaces as an inconsistent inspection. Whether the requested
 * operation may run on an intact catalog is [me.ahoo.wow.bi.BiOperationPolicy]'s decision.
 */
internal object ClickHouseCatalogValidator {
    fun validate(
        options: BiScriptOptions,
        operation: BiScriptOperation,
        snapshot: ClickHouseCatalogSnapshot,
        desiredObjects: List<DesiredBiObject>,
    ): ValidatedBiDeployment {
        val objects = snapshot.objects
        val uniqueObjects = uniqueCatalogObjects(objects)
        val descriptor = BiDeploymentDescriptor.from(options)
        val deploymentStable = requestedDeploymentIsStable(descriptor, uniqueObjects)
        val validationContext = CatalogObjectValidationContext(
            options,
            operation,
            descriptor,
            deploymentStable,
        )
        validateStores(validationContext, objects)
        validateQueues(validationContext, uniqueObjects)
        val computedDefinitions = computedDefinitions(
            ComputedDriftValidationContext(
                operation = operation,
                requestedDeploymentIsStable = deploymentStable,
                descriptor = descriptor,
                objects = uniqueObjects,
                desiredObjects = desiredObjects,
                expectedQueries = snapshot.expectedQueries,
            )
        )
        return ValidatedBiDeployment(
            deployment = ObservedBiDeployment(uniqueObjects.map(ClickHouseCatalogObject::observed)),
            repairableDrifts = computedDefinitions.filterIsInstance<ComputedDefinition.Drifted>()
                .map(ComputedDefinition.Drifted::drift),
            verifiedComputedKeys = computedDefinitions.filterIsInstance<ComputedDefinition.Verified>()
                .mapTo(linkedSetOf(), ComputedDefinition.Verified::key),
        )
    }

    private fun uniqueCatalogObjects(objects: List<ClickHouseCatalogObject>): List<ClickHouseCatalogObject> =
        objects.groupBy(ClickHouseCatalogObject::key).map { (key, replicas) ->
            val definitions = replicas.map(ClickHouseCatalogObject::toCatalogDefinition).distinct()
            check(definitions.size == 1 || replicas.none { it.observed.metadata != null }) {
                "ClickHouse BI catalog object [${key.database}.${key.name}] has duplicate definitions"
            }
            replicas.first()
        }.sortedWith(
            compareBy<ClickHouseCatalogObject> { it.observed.database }
                .thenBy { it.observed.name }
        )

    private fun validateStores(
        context: CatalogObjectValidationContext,
        objects: List<ClickHouseCatalogObject>,
    ) = with(context) {
        if (operation == BiScriptOperation.Deploy && deploymentStable) {
            objects.filter { catalogObject ->
                catalogObject.observed.metadata?.let { metadata ->
                    metadata.kind == BiObjectKind.STORE &&
                        metadata.deploymentId == descriptor.deploymentId
                } == true
            }.forEach { store -> ClickHouseStoreShapeValidator.validate(options, store) }
        }
    }

    /** Validates this deployment's queues; RESET removes queues of another layout without inspecting them. */
    private fun validateQueues(
        context: CatalogObjectValidationContext,
        objects: List<ClickHouseCatalogObject>,
    ) = with(context) {
        val owned = objects.filter { catalogObject ->
            catalogObject.observed.metadata?.let { metadata ->
                metadata.deploymentId == descriptor.deploymentId && metadata.isCurrentLayout
            } == true
        }
        val consumerIdentity = owned.firstNotNullOfOrNull { it.observed.metadata?.anchor }?.consumerIdentity
            ?: BiConsumerIdentity.deterministic(descriptor).value
        owned.filter { it.observed.metadata?.kind == BiObjectKind.QUEUE }
            .forEach { queue ->
                validateQueueIdentity(
                    options = options,
                    queue = queue.observed,
                    consumerIdentity = consumerIdentity,
                    validateConsumerGroup = operation == BiScriptOperation.Deploy && deploymentStable,
                    validateRequestedConfiguration = operation == BiScriptOperation.Deploy && deploymentStable,
                )
            }
    }

    /** Compares each comparable computed object with its expected definition: verified or drifted. */
    private fun computedDefinitions(context: ComputedDriftValidationContext): List<ComputedDefinition> =
        with(context) {
            if (operation != BiScriptOperation.Deploy || !requestedDeploymentIsStable) {
                return emptyList()
            }
            val desiredByKey = desiredObjects.associateBy(DesiredBiObject::key)
            return objects.mapNotNull { catalogObject ->
                val observed = catalogObject.observed
                val desired = desiredByKey[observed.key] ?: return@mapNotNull null
                val metadata = observed.metadata ?: return@mapNotNull null
                val expected = expectedQueries[observed.key] ?: return@mapNotNull null
                if (!isComparableComputedObject(desired, observed, metadata, descriptor)) {
                    return@mapNotNull null
                }
                val mismatches = buildSet {
                    if (catalogObject.asSelect != expected.selectSql) {
                        add(BiComputedDefinitionField.SELECT)
                    }
                    if (
                        desired.kind == BiObjectKind.CONSUMER &&
                        ClickHouseMaterializedViewTargetParser.parse(observed.createTableQuery) != expected.target
                    ) {
                        add(BiComputedDefinitionField.TARGET)
                    }
                }
                if (mismatches.isEmpty()) {
                    ComputedDefinition.Verified(observed.key)
                } else {
                    ComputedDefinition.Drifted(
                        RepairableBiObjectDrift(
                            key = observed.key,
                            aggregate = checkNotNull(desired.aggregate),
                            kind = desired.kind,
                            mismatches = mismatches,
                        )
                    )
                }
            }
        }

    private fun isComparableComputedObject(
        desired: DesiredBiObject,
        observed: ObservedBiObject,
        metadata: BiObjectMetadata,
        descriptor: BiDeploymentDescriptor,
    ): Boolean {
        if (desired.expectedQuery == null || desired.kind !in COMPUTED_KINDS) {
            return false
        }
        if (metadata.deploymentId != descriptor.deploymentId || !metadata.isCurrentLayout) {
            return false
        }
        if (metadata.kind != desired.kind || metadata.aggregate != desired.aggregate) {
            return false
        }
        return observed.engine == desired.expectedEngine
    }

    private fun validateQueueIdentity(
        options: BiScriptOptions,
        queue: ObservedBiObject,
        consumerIdentity: String,
        validateConsumerGroup: Boolean,
        validateRequestedConfiguration: Boolean,
    ) {
        check(queue.engine == BiEngine.KAFKA) {
            "Owned BI queue [${queue.database}.${queue.name}] must use the Kafka engine"
        }
        val metadata = checkNotNull(queue.metadata)
        val stream = checkNotNull(BiLayout.streamOfQueue(queue.name)) {
            "Owned BI queue [${queue.database}.${queue.name}] has an unsupported queue name"
        }
        val arguments = queue.engineFull.functionArguments(BiEngine.KAFKA).orEmpty()
        val actualGroup = arguments.getOrNull(KAFKA_GROUP_ARGUMENT_INDEX)
        if (validateConsumerGroup) {
            val expectedGroup = "wow-bi.$consumerIdentity.${stream.consumer}"
            check(actualGroup == ClickHouseSqlSyntax.catalogStringLiteral(expectedGroup)) {
                "Owned BI queue [${queue.database}.${queue.name}] has an unexpected Kafka consumer group"
            }
        }
        check(
            arguments.getOrNull(KAFKA_FORMAT_ARGUMENT_INDEX) == ClickHouseSqlSyntax.catalogStringLiteral(KAFKA_FORMAT)
        ) {
            "Owned BI queue [${queue.database}.${queue.name}] has an unexpected Kafka format"
        }
        if (!validateRequestedConfiguration) {
            return
        }
        check(
            arguments.getOrNull(KAFKA_BROKERS_ARGUMENT_INDEX) ==
                ClickHouseSqlSyntax.catalogStringLiteral(options.kafkaBootstrapServers)
        ) {
            "Owned BI queue [${queue.database}.${queue.name}] has unexpected Kafka bootstrap servers"
        }
        val expectedTopic = "${options.topicPrefix}${checkNotNull(metadata.aggregate)}.${stream.suffix}"
        check(
            arguments.getOrNull(KAFKA_TOPIC_ARGUMENT_INDEX) == ClickHouseSqlSyntax.catalogStringLiteral(expectedTopic)
        ) {
            "Owned BI queue [${queue.database}.${queue.name}] has an unexpected Kafka topic"
        }
        val actualKeeperPath = queue.engineFull.settingLiteral(KAFKA_KEEPER_PATH_SETTING)
        val actualReplicaName = queue.engineFull.settingLiteral(KAFKA_REPLICA_NAME_SETTING)
        when (options.kafkaOffsetStorage) {
            KafkaOffsetStorage.BROKER -> check(actualKeeperPath == null && actualReplicaName == null) {
                "Owned BI queue [${queue.database}.${queue.name}] has unexpected Keeper offset settings"
            }

            KafkaOffsetStorage.KEEPER -> {
                val expectedKeeperPath = "${options.kafkaKeeperPathPrefix.trimEnd('/')}/$consumerIdentity/${queue.name}"
                check(actualKeeperPath == ClickHouseSqlSyntax.catalogStringLiteral(expectedKeeperPath)) {
                    "Owned BI queue [${queue.database}.${queue.name}] has an unexpected Kafka Keeper path"
                }
                val expectedReplicaName = when (options.topology) {
                    is ClickHouseTopology.Cluster -> "{replica}"
                    ClickHouseTopology.Standalone -> consumerIdentity
                }
                check(actualReplicaName == ClickHouseSqlSyntax.catalogStringLiteral(expectedReplicaName)) {
                    "Owned BI queue [${queue.database}.${queue.name}] has an unexpected Kafka Keeper replica name"
                }
            }
        }
    }

    private const val KAFKA_BROKERS_ARGUMENT_INDEX: Int = 0
    private const val KAFKA_TOPIC_ARGUMENT_INDEX: Int = 1
    private const val KAFKA_GROUP_ARGUMENT_INDEX: Int = 2
    private const val KAFKA_FORMAT_ARGUMENT_INDEX: Int = 3
    private const val KAFKA_FORMAT: String = "JSONAsString"
    private const val KAFKA_KEEPER_PATH_SETTING: String = "kafka_keeper_path"
    private const val KAFKA_REPLICA_NAME_SETTING: String = "kafka_replica_name"
    private val COMPUTED_KINDS = setOf(BiObjectKind.VIEW, BiObjectKind.CONSUMER)
}

private data class ComputedDriftValidationContext(
    val operation: BiScriptOperation,
    val requestedDeploymentIsStable: Boolean,
    val descriptor: BiDeploymentDescriptor,
    val objects: List<ClickHouseCatalogObject>,
    val desiredObjects: List<DesiredBiObject>,
    val expectedQueries: Map<BiObjectKey, CanonicalExpectedBiQuery>,
)

private data class CatalogObjectValidationContext(
    val options: BiScriptOptions,
    val operation: BiScriptOperation,
    val descriptor: BiDeploymentDescriptor,
    val deploymentStable: Boolean,
)

internal data class ValidatedBiDeployment(
    val deployment: ObservedBiDeployment,
    val repairableDrifts: List<RepairableBiObjectDrift>,
    val verifiedComputedKeys: Set<BiObjectKey> = emptySet(),
)

private sealed interface ComputedDefinition {
    data class Verified(val key: BiObjectKey) : ComputedDefinition
    data class Drifted(val drift: RepairableBiObjectDrift) : ComputedDefinition
}
