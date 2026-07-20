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

import me.ahoo.wow.bi.renderer.ClickHouseSqlSyntax

internal object ClickHouseBiDeploymentValidator {
    fun validate(
        options: BiScriptOptions,
        operation: BiScriptOperation,
        snapshot: ClickHouseCatalogSnapshot,
        desiredObjects: List<DesiredBiObject>?,
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
        return ValidatedBiDeployment(
            deployment = ObservedBiDeployment(uniqueObjects.map(ClickHouseCatalogObject::observed)),
            repairableDrifts = repairableComputedDrifts(
                ComputedDriftValidationContext(
                    operation = operation,
                    requestedDeploymentIsStable = deploymentStable,
                    descriptor = descriptor,
                    objects = uniqueObjects,
                    desiredObjects = desiredObjects,
                    expectedQueries = snapshot.expectedQueries,
                )
            ),
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

    private fun validateQueues(
        context: CatalogObjectValidationContext,
        objects: List<ClickHouseCatalogObject>,
    ) = with(context) {
        objects.filter { it.observed.metadata?.kind == BiObjectKind.QUEUE }
            .forEach { queue ->
                val validateRequestedConfiguration =
                    operation == BiScriptOperation.Deploy &&
                        deploymentStable &&
                        queue.observed.metadata?.deploymentId == descriptor.deploymentId
                validateQueueIdentity(
                    options = options,
                    queue = queue.observed,
                    validateConsumerGroup = operation == BiScriptOperation.Deploy,
                    validateRequestedConfiguration = validateRequestedConfiguration,
                )
            }
    }

    private fun repairableComputedDrifts(context: ComputedDriftValidationContext): List<RepairableBiObjectDrift> =
        with(context) {
            if (operation != BiScriptOperation.Deploy || !requestedDeploymentIsStable || desiredObjects == null) {
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
                    null
                } else {
                    RepairableBiObjectDrift(
                        key = observed.key,
                        aggregate = checkNotNull(desired.aggregate),
                        kind = desired.kind,
                        mismatches = mismatches,
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
        if (metadata.deploymentId != descriptor.deploymentId) {
            return false
        }
        if (metadata.configurationFingerprint != descriptor.configurationFingerprint) {
            return false
        }
        if (metadata.topologyFingerprint != descriptor.topologyFingerprint) {
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
        validateConsumerGroup: Boolean,
        validateRequestedConfiguration: Boolean,
    ) {
        check(queue.engine == "Kafka") {
            "Owned BI queue [${queue.database}.${queue.name}] must use the Kafka engine"
        }
        val metadata = checkNotNull(queue.metadata)
        val identity = checkNotNull(metadata.consumerIdentity) {
            "Owned BI queue [${queue.database}.${queue.name}] is missing consumerIdentity"
        }
        val consumerName = queue.name.removeSuffix("_queue") + "_consumer"
        val expectedGroup = "wow-bi.$identity.$consumerName"
        val arguments = queue.engineFull.functionArguments("Kafka").orEmpty()
        val actualGroup = arguments.getOrNull(KAFKA_GROUP_ARGUMENT_INDEX)
        if (validateConsumerGroup) {
            check(actualGroup == ClickHouseSqlSyntax.stringLiteral(expectedGroup)) {
                "Owned BI queue [${queue.database}.${queue.name}] has an unexpected Kafka consumer group"
            }
        }
        val streamKind = when {
            queue.name.endsWith("_command_queue") -> "command"
            queue.name.endsWith("_state_queue") -> "state"
            else -> error("Owned BI queue [${queue.database}.${queue.name}] has an unsupported queue name")
        }
        check(arguments.getOrNull(KAFKA_FORMAT_ARGUMENT_INDEX) == ClickHouseSqlSyntax.stringLiteral(KAFKA_FORMAT)) {
            "Owned BI queue [${queue.database}.${queue.name}] has an unexpected Kafka format"
        }
        if (!validateRequestedConfiguration) {
            return
        }
        check(
            arguments.getOrNull(KAFKA_BROKERS_ARGUMENT_INDEX) ==
                ClickHouseSqlSyntax.stringLiteral(options.kafkaBootstrapServers)
        ) {
            "Owned BI queue [${queue.database}.${queue.name}] has unexpected Kafka bootstrap servers"
        }
        val expectedTopic = "${options.topicPrefix}${checkNotNull(metadata.aggregate)}.$streamKind"
        check(arguments.getOrNull(KAFKA_TOPIC_ARGUMENT_INDEX) == ClickHouseSqlSyntax.stringLiteral(expectedTopic)) {
            "Owned BI queue [${queue.database}.${queue.name}] has an unexpected Kafka topic"
        }
        val actualKeeperPath = queue.engineFull.settingLiteral(KAFKA_KEEPER_PATH_SETTING)
        val actualReplicaName = queue.engineFull.settingLiteral(KAFKA_REPLICA_NAME_SETTING)
        when (options.kafkaOffsetStorage) {
            KafkaOffsetStorage.BROKER -> check(actualKeeperPath == null && actualReplicaName == null) {
                "Owned BI queue [${queue.database}.${queue.name}] has unexpected Keeper offset settings"
            }

            KafkaOffsetStorage.KEEPER -> {
                val expectedKeeperPath = "${options.kafkaKeeperPathPrefix.trimEnd('/')}/$identity/${queue.name}"
                check(actualKeeperPath == ClickHouseSqlSyntax.stringLiteral(expectedKeeperPath)) {
                    "Owned BI queue [${queue.database}.${queue.name}] has an unexpected Kafka Keeper path"
                }
                val expectedReplicaName = when (options.topology) {
                    is ClickHouseTopology.Cluster -> "{replica}"
                    ClickHouseTopology.Standalone -> identity
                }
                check(actualReplicaName == ClickHouseSqlSyntax.stringLiteral(expectedReplicaName)) {
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
    val desiredObjects: List<DesiredBiObject>?,
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
)
