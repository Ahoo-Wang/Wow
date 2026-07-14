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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.bi.expansion.BiTableNaming
import me.ahoo.wow.bi.expansion.plan.StateExpansionPlan
import me.ahoo.wow.bi.expansion.plan.StateExpansionPlanner
import me.ahoo.wow.bi.renderer.CatalogMutationMode
import me.ahoo.wow.bi.renderer.ClickHouseScriptRenderer
import me.ahoo.wow.modeling.toStringWithAlias
import java.util.Collections

class BiScriptGenerator(private val options: BiScriptOptions = BiScriptOptions()) {

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun generate(
        namedAggregates: Set<NamedAggregate>,
        operation: BiScriptOperation = BiScriptOperation.Deploy,
        inspection: BiDeploymentInspection = BiDeploymentInspection.Unavailable,
    ): BiScriptResult {
        if (namedAggregates.isNotEmpty()) {
            requireNotNull(options.consumerGroupNamespace) {
                "consumerGroupNamespace must be configured before generating BI Kafka consumers"
            }
        }
        require(operation !is BiScriptOperation.Reset || inspection is BiDeploymentInspection.Available) {
            "RESET requires an available BI deployment inspection"
        }

        val planner = StateExpansionPlanner(options)
        val plannedAggregates = namedAggregates
            .sortedWith(compareBy<NamedAggregate> { it.contextName }.thenBy { it.aggregateName })
            .map { namedAggregate -> PlannedAggregate(namedAggregate, planner.plan(namedAggregate)) }
        val descriptor = BiDeploymentDescriptor.from(options)
        val shouldRenderDeploymentAnchor = options.consumerGroupNamespace != null
        require(operation !is BiScriptOperation.Reset || shouldRenderDeploymentAnchor) {
            "consumerGroupNamespace must be configured before RESET so its recovery state can be persisted"
        }
        val desiredObjects = plannedAggregates.desiredObjects() +
            if (shouldRenderDeploymentAnchor) listOf(desiredAnchor()) else emptyList()
        validateDesiredObjectNames(desiredObjects)
        val observed = (inspection as? BiDeploymentInspection.Available)?.deployment
        observed?.validate(descriptor, desiredObjects, operation)
        val consumerIdentity = resolveConsumerIdentity(operation, descriptor, observed)
        val retainedQueueKeys = resolveRetainedQueueKeys(operation, desiredObjects, descriptor, observed)
        val renderer = ClickHouseScriptRenderer(
            options,
            consumerIdentity,
            descriptor,
            if (observed == null) CatalogMutationMode.CREATE_ONLY else CatalogMutationMode.RECONCILE,
            retainedQueueKeys,
        )

        val globalSection = ScriptSection("global", renderer.renderGlobalStatements())
        val lifecycleSections = renderLifecycle(
            operation,
            plannedAggregates,
            desiredObjects,
            descriptor,
            observed,
            renderer,
        )
        val durableAggregateSections = plannedAggregates.flatMap { planned ->
            val name = planned.namedAggregate.toStringWithAlias()
            listOf(
                ScriptSection("$name.commandStorage", renderer.renderCommandStorageStatements(planned.namedAggregate)),
                ScriptSection("$name.stateStorage", renderer.renderStateStorageStatements(planned.namedAggregate)),
                ScriptSection("$name.stateLast", renderer.renderStateLastStatements(planned.namedAggregate)),
                ScriptSection("$name.expansion", renderer.renderExpansionStatements(planned.plan, name)),
                ScriptSection("$name.commandPublic", renderer.renderCommandPublicStatements(planned.namedAggregate)),
                ScriptSection("$name.statePublic", renderer.renderStatePublicStatements(planned.namedAggregate)),
            )
        }
        val ingressSections = plannedAggregates.flatMap { planned ->
            val name = planned.namedAggregate.toStringWithAlias()
            listOf(
                ScriptSection("$name.commandIngress", renderer.renderCommandIngressStatements(planned.namedAggregate)),
                ScriptSection("$name.stateIngress", renderer.renderStateIngressStatements(planned.namedAggregate)),
            )
        }
        val resetIntentSection = if (operation is BiScriptOperation.Reset) {
            ScriptSection(
                "deployment-reset-intent",
                listOf(renderer.renderAnchorStatement(BiDeploymentPhase.RESETTING)),
            )
        } else {
            null
        }
        val anchorSection = if (shouldRenderDeploymentAnchor) {
            ScriptSection(
                "deployment-anchor",
                listOf(renderer.renderAnchorStatement(BiDeploymentPhase.STABLE)),
            )
        } else {
            null
        }
        val orderedSections = listOf(globalSection) + listOfNotNull(resetIntentSection) + lifecycleSections +
            durableAggregateSections + listOfNotNull(anchorSection) + ingressSections
        val statements = Collections.unmodifiableList(
            ArrayList(orderedSections.flatMap(ScriptSection::statements))
        )
        val script = buildString {
            appendSection(globalSection)
            appendLine("-- lifecycle --")
            resetIntentSection?.let { section -> appendSection(section) }
            lifecycleSections.forEach { section -> appendSection(section) }
            appendLine("-- lifecycle --")
            durableAggregateSections.forEach { section -> appendSection(section) }
            anchorSection?.let { section -> appendSection(section) }
            ingressSections.forEach { section -> appendSection(section) }
        }
        val diagnostics = buildList {
            if (plannedAggregates.isNotEmpty()) {
                addAll(topologyDiagnostics())
            }
            if (inspection is BiDeploymentInspection.Unavailable) {
                add(inspectionUnavailableDiagnostic())
            }
            addAll(retainedStoreDiagnostics(operation, desiredObjects, descriptor, observed))
            addAll(plannedAggregates.flatMap { it.plan.diagnostics })
        }
        return BiScriptResult(
            script = script,
            statements = statements,
            diagnostics = Collections.unmodifiableList(ArrayList(diagnostics)),
            operation = operation,
            destructive = operation is BiScriptOperation.Reset,
        )
    }

    private fun resolveRetainedQueueKeys(
        operation: BiScriptOperation,
        desiredObjects: List<DesiredBiObject>,
        descriptor: BiDeploymentDescriptor,
        observed: ObservedBiDeployment?,
    ): Set<BiObjectKey> {
        if (operation != BiScriptOperation.Deploy || observed == null) {
            return emptySet()
        }
        val desiredQueueKeys = desiredObjects.asSequence()
            .filter { it.kind == BiObjectKind.QUEUE }
            .map(DesiredBiObject::key)
            .toSet()
        return observed.ownedBy(descriptor).asSequence()
            .filter { it.metadata?.kind == BiObjectKind.QUEUE && it.key in desiredQueueKeys }
            .map(ObservedBiObject::key)
            .toSet()
    }

    private fun renderLifecycle(
        operation: BiScriptOperation,
        plannedAggregates: List<PlannedAggregate>,
        desiredObjects: List<DesiredBiObject>,
        descriptor: BiDeploymentDescriptor,
        observed: ObservedBiDeployment?,
        renderer: ClickHouseScriptRenderer,
    ): List<ScriptSection> = buildList {
        when (operation) {
            BiScriptOperation.Deploy -> {
                if (observed != null) {
                    plannedAggregates.forEach { planned ->
                        add(
                            ScriptSection(
                                "${planned.namedAggregate.toStringWithAlias()}.pause-ingress",
                                renderer.renderPauseIngressStatements(planned.namedAggregate),
                            )
                        )
                    }
                }
                val desiredKeys = desiredObjects.map(DesiredBiObject::key).toSet()
                val staleObjects = observed?.ownedBy(descriptor).orEmpty()
                    .filter { it.key !in desiredKeys && it.metadata?.kind != BiObjectKind.STORE }
                if (staleObjects.isNotEmpty()) {
                    add(
                        ScriptSection("reconcile-observed-catalog", renderer.renderDropObservedStatements(staleObjects))
                    )
                }
            }

            is BiScriptOperation.Reset -> {
                val anchorKey = desiredAnchor().key
                val ownedObjects = checkNotNull(observed).ownedBy(descriptor)
                    .filterNot { it.key == anchorKey }
                if (ownedObjects.isNotEmpty()) {
                    add(ScriptSection("reset-observed-catalog", renderer.renderDropObservedStatements(ownedObjects)))
                }
            }
        }
    }

    private fun resolveConsumerIdentity(
        operation: BiScriptOperation,
        descriptor: BiDeploymentDescriptor,
        observed: ObservedBiDeployment?,
    ): BiConsumerIdentity = when (operation) {
        BiScriptOperation.Deploy ->
            observed?.consumerIdentity(descriptor) ?: BiConsumerIdentity.deterministic(descriptor)
        is BiScriptOperation.Reset ->
            observed?.resettingAnchor(descriptor)?.metadata?.consumerIdentity
                ?.let(::BiConsumerIdentity)
                ?: BiConsumerIdentity.random()
    }

    private fun ObservedBiDeployment.resettingAnchor(
        descriptor: BiDeploymentDescriptor,
    ): ObservedBiObject? = objects.firstOrNull { observed ->
        observed.key == desiredAnchor().key &&
            observed.metadata?.deploymentId == descriptor.deploymentId &&
            observed.metadata.phase == BiDeploymentPhase.RESETTING
    }

    private fun ObservedBiDeployment.consumerIdentity(descriptor: BiDeploymentDescriptor): BiConsumerIdentity? {
        val ownedObjects = ownedBy(descriptor)
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

    private fun ObservedBiDeployment.validate(
        descriptor: BiDeploymentDescriptor,
        desiredObjects: List<DesiredBiObject>,
        operation: BiScriptOperation,
    ) {
        val desiredByKey = desiredObjects.associateBy(DesiredBiObject::key)
        validateDeploymentTopology(descriptor)
        validateDeploymentAnchor(descriptor, operation)
        objects.forEach { observed ->
            validateObservedObject(
                observed = observed,
                desired = desiredByKey[observed.key],
                descriptor = descriptor,
                operation = operation,
            )
        }
        if (operation == BiScriptOperation.Deploy) {
            consumerIdentity(descriptor)
        }
    }

    private fun ObservedBiDeployment.validateDeploymentTopology(
        descriptor: BiDeploymentDescriptor,
    ) {
        val currentObjects = ownedBy(descriptor)
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
            "Observed BI deployment topology differs from the requested topology and cannot be migrated through " +
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
            val canonicalAnchor = desiredAnchor().key
            require(anchor.key == canonicalAnchor) {
                "Observed BI deployment anchor must use canonical key " +
                    "[${canonicalAnchor.database}.${canonicalAnchor.name}], but found " +
                    "[${anchor.database}.${anchor.name}]"
            }
        }
        resettingAnchor(descriptor)?.let { anchor ->
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
            require(
                metadata != null &&
                    metadata.deploymentId == descriptor.deploymentId
            ) {
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

    private fun ObservedBiDeployment.ownedBy(descriptor: BiDeploymentDescriptor): List<ObservedBiObject> =
        ownedObjects.filter { it.metadata?.deploymentId == descriptor.deploymentId }

    private fun List<PlannedAggregate>.desiredObjects(): List<DesiredBiObject> {
        val naming = BiTableNaming(options)
        return flatMap { planned -> planned.desiredObjects(naming) }
    }

    private fun PlannedAggregate.desiredObjects(naming: BiTableNaming): List<DesiredBiObject> {
        val aggregate = namedAggregate.toStringWithAlias()
        val command = naming.toTableName(namedAggregate, "command")
        val state = naming.toTableName(namedAggregate, "state")
        val stateLast = naming.toTableName(namedAggregate, "state_last")
        return buildList {
            listOf(command, state, stateLast).forEach { table ->
                addDesiredStores(table, aggregate)
            }
            add(DesiredBiObject(BiObjectKey(options.database, command), aggregate, BiObjectKind.VIEW, VIEW_ENGINE))
            add(DesiredBiObject(BiObjectKey(options.database, state), aggregate, BiObjectKind.VIEW, VIEW_ENGINE))
            add(
                DesiredBiObject(
                    BiObjectKey(options.database, "${state}_event"),
                    aggregate,
                    BiObjectKind.VIEW,
                    VIEW_ENGINE,
                )
            )
            add(DesiredBiObject(BiObjectKey(options.database, stateLast), aggregate, BiObjectKind.VIEW, VIEW_ENGINE))
            plan.views.forEach { view ->
                add(
                    DesiredBiObject(
                        BiObjectKey(options.database, view.targetTableName),
                        aggregate,
                        BiObjectKind.VIEW,
                        VIEW_ENGINE,
                    )
                )
            }
            listOf(command, state).forEach { table ->
                add(
                    DesiredBiObject(
                        BiObjectKey(options.consumerDatabase, "${table}_queue"),
                        aggregate,
                        BiObjectKind.QUEUE,
                        QUEUE_ENGINE,
                    )
                )
                add(
                    DesiredBiObject(
                        BiObjectKey(options.consumerDatabase, "${table}_consumer"),
                        aggregate,
                        BiObjectKind.CONSUMER,
                        CONSUMER_ENGINE,
                    )
                )
            }
            add(
                DesiredBiObject(
                    BiObjectKey(options.consumerDatabase, "${stateLast}_consumer"),
                    aggregate,
                    BiObjectKind.CONSUMER,
                    CONSUMER_ENGINE,
                )
            )
        }
    }

    private fun MutableList<DesiredBiObject>.addDesiredStores(
        table: String,
        aggregate: String,
    ) {
        val store = "${table}_store"
        val storeEngine = when (options.topology) {
            is ClickHouseTopology.Cluster -> "Distributed"
            ClickHouseTopology.Standalone -> "ReplacingMergeTree"
        }
        add(DesiredBiObject(BiObjectKey(options.database, store), aggregate, BiObjectKind.STORE, storeEngine))
        if (options.topology is ClickHouseTopology.Cluster) {
            add(
                DesiredBiObject(
                    BiObjectKey(options.database, "${store}_local"),
                    aggregate,
                    BiObjectKind.STORE,
                    "ReplicatedReplacingMergeTree",
                )
            )
        }
    }

    private fun desiredAnchor(): DesiredBiObject =
        DesiredBiObject(
            BiObjectKey(options.consumerDatabase, ClickHouseScriptRenderer.DEPLOYMENT_ANCHOR),
            null,
            BiObjectKind.ANCHOR,
            VIEW_ENGINE,
        )

    private fun validateDesiredObjectNames(objects: List<DesiredBiObject>) {
        val collision = objects.groupBy(DesiredBiObject::key).entries.firstOrNull { it.value.size > 1 }
        require(collision == null) {
            "BI object name collision [${collision!!.key.database}.${collision.key.name}] between aggregates " +
                collision.value.mapNotNull(DesiredBiObject::aggregate).distinct().sorted()
                    .joinToString(prefix = "[", postfix = "]")
        }
    }

    private fun retainedStoreDiagnostics(
        operation: BiScriptOperation,
        desiredObjects: List<DesiredBiObject>,
        descriptor: BiDeploymentDescriptor,
        observed: ObservedBiDeployment?,
    ): List<BiScriptDiagnostic> {
        if (operation is BiScriptOperation.Reset) {
            return emptyList()
        }
        val desiredKeys = desiredObjects.map(DesiredBiObject::key).toSet()
        return observed?.ownedBy(descriptor).orEmpty()
            .filter { it.key !in desiredKeys && it.metadata?.kind == BiObjectKind.STORE }
            .mapNotNull { it.metadata?.aggregate }
            .distinct()
            .sorted()
            .map { aggregate ->
                BiScriptDiagnostic(
                    code = BiScriptDiagnosticCode.ORPHANED_DATA_TABLE,
                    aggregate = aggregate,
                    path = "lifecycle.reconcile",
                    sourceType = "ObservedBiDeployment",
                    decision = BiScriptMappingDecision.DATA_TABLE_RETAINED,
                    message = "Aggregate was removed; its observed data stores were retained.",
                )
            }
    }

    private fun inspectionUnavailableDiagnostic(): BiScriptDiagnostic = BiScriptDiagnostic(
        code = BiScriptDiagnosticCode.INSPECTION_UNAVAILABLE,
        aggregate = "*",
        path = "lifecycle.inspection",
        sourceType = "BiDeploymentInspector",
        decision = BiScriptMappingDecision.RECONCILIATION_SKIPPED,
        message = "BI deployment inspection is unavailable; generated current desired state without stale-object reconciliation.",
    )

    private fun topologyDiagnostics(): List<BiScriptDiagnostic> = when (options.topology) {
        is ClickHouseTopology.Cluster -> listOf(
            BiScriptDiagnostic(
                code = BiScriptDiagnosticCode.CLUSTER_INTERNAL_REPLICATION_REQUIRED,
                aggregate = "*",
                path = "topology.cluster",
                sourceType = "ClickHouseTopology.Cluster",
                decision = BiScriptMappingDecision.EXTERNAL_CONFIGURATION_REQUIRED,
                message = "Configure internal_replication=true for every shard before applying clustered BI DDL.",
            )
        )

        ClickHouseTopology.Standalone -> emptyList()
    }

    private fun StringBuilder.appendSection(section: ScriptSection) {
        appendLine("-- ${section.name} --")
        if (section.statements.isNotEmpty()) {
            appendLine(section.statements.joinToString("\n\n"))
        }
        appendLine("-- ${section.name} --")
    }

    private data class PlannedAggregate(
        val namedAggregate: NamedAggregate,
        val plan: StateExpansionPlan,
    )

    private data class DesiredBiObject(
        val key: BiObjectKey,
        val aggregate: String?,
        val kind: BiObjectKind,
        val expectedEngine: String,
    )

    private data class ScriptSection(
        val name: String,
        val statements: List<String>,
    )

    private companion object {
        const val VIEW_ENGINE: String = "View"
        const val QUEUE_ENGINE: String = "Kafka"
        const val CONSUMER_ENGINE: String = "MaterializedView"

        val STORE_ENGINES: Set<String> = setOf(
            "ReplacingMergeTree",
            "ReplicatedReplacingMergeTree",
            "Distributed",
        )
    }
}
