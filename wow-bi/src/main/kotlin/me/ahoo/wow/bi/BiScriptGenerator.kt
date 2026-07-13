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
import me.ahoo.wow.bi.renderer.ClickHouseScriptRenderer
import me.ahoo.wow.modeling.toStringWithAlias
import java.util.Collections

class BiScriptGenerator(private val options: BiScriptOptions = BiScriptOptions()) {

    @Suppress("LongMethod")
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
        val desiredObjects = plannedAggregates.desiredObjects() + desiredAnchor()
        validateDesiredObjectNames(desiredObjects)
        val observed = (inspection as? BiDeploymentInspection.Available)?.deployment
        observed?.validate(descriptor, desiredObjects, operation)
        val consumerIdentity = resolveConsumerIdentity(operation, descriptor, observed)
        val renderer = ClickHouseScriptRenderer(options, consumerIdentity, descriptor)

        val globalSection = ScriptSection("global", renderer.renderGlobalStatements())
        val lifecycleSections = renderLifecycle(
            operation,
            plannedAggregates,
            desiredObjects,
            descriptor,
            observed,
            renderer,
        )
        val aggregateSections = plannedAggregates.flatMap { planned ->
            val name = planned.namedAggregate.toStringWithAlias()
            listOf(
                ScriptSection("$name.command", renderer.renderCommandStatements(planned.namedAggregate)),
                ScriptSection("$name.stateStorage", renderer.renderStateStorageStatements(planned.namedAggregate)),
                ScriptSection("$name.stateLast", renderer.renderStateLastStatements(planned.namedAggregate)),
                ScriptSection("$name.expansion", renderer.renderExpansionStatements(planned.plan, name)),
                ScriptSection("$name.statePublic", renderer.renderStatePublicStatements(planned.namedAggregate)),
                ScriptSection("$name.stateIngress", renderer.renderStateIngressStatements(planned.namedAggregate)),
            )
        }
        val anchorSection = ScriptSection("deployment-anchor", listOf(renderer.renderAnchorStatement()))
        val orderedSections = listOf(globalSection) + lifecycleSections + aggregateSections + anchorSection
        val statements = Collections.unmodifiableList(
            ArrayList(orderedSections.flatMap(ScriptSection::statements))
        )
        val script = buildString {
            appendSection(globalSection)
            appendLine("-- lifecycle --")
            lifecycleSections.forEach { section -> appendSection(section) }
            appendLine("-- lifecycle --")
            aggregateSections.forEach { section -> appendSection(section) }
            appendSection(anchorSection)
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
                plannedAggregates.forEach { planned ->
                    add(
                        ScriptSection(
                            "${planned.namedAggregate.toStringWithAlias()}.pause-ingress",
                            renderer.renderPauseIngressStatements(planned.namedAggregate),
                        )
                    )
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
                val ownedObjects = checkNotNull(observed).ownedBy(descriptor)
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
        is BiScriptOperation.Reset -> BiConsumerIdentity.random()
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
        objects.forEach { observed ->
            val desired = desiredByKey[observed.key]
            val metadata = observed.metadata
            if (desired != null) {
                require(metadata != null && metadata.deploymentId == descriptor.deploymentId) {
                    "BI object [${observed.database}.${observed.name}] is occupied by a foreign catalog object"
                }
                require(metadata.kind == desired.kind && metadata.aggregate == desired.aggregate) {
                    "BI object [${observed.database}.${observed.name}] has inconsistent ownership metadata"
                }
            }
            if (metadata?.deploymentId == descriptor.deploymentId && operation == BiScriptOperation.Deploy) {
                require(metadata.configurationFingerprint == descriptor.configurationFingerprint) {
                    "Observed BI deployment configuration differs from the requested configuration; use RESET"
                }
            }
        }
        if (operation == BiScriptOperation.Deploy) {
            consumerIdentity(descriptor)
        }
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
                val store = "${table}_store"
                add(DesiredBiObject(BiObjectKey(options.database, store), aggregate, BiObjectKind.STORE))
                if (options.topology is ClickHouseTopology.Cluster) {
                    add(
                        DesiredBiObject(
                            BiObjectKey(options.database, "${store}_local"),
                            aggregate,
                            BiObjectKind.STORE,
                        )
                    )
                }
            }
            add(DesiredBiObject(BiObjectKey(options.database, command), aggregate, BiObjectKind.VIEW))
            add(DesiredBiObject(BiObjectKey(options.database, state), aggregate, BiObjectKind.VIEW))
            add(DesiredBiObject(BiObjectKey(options.database, "${state}_event"), aggregate, BiObjectKind.VIEW))
            add(DesiredBiObject(BiObjectKey(options.database, stateLast), aggregate, BiObjectKind.VIEW))
            plan.views.forEach { view ->
                add(
                    DesiredBiObject(
                        BiObjectKey(options.database, view.targetTableName),
                        aggregate,
                        BiObjectKind.VIEW,
                    )
                )
            }
            listOf(command, state).forEach { table ->
                add(
                    DesiredBiObject(
                        BiObjectKey(options.consumerDatabase, "${table}_queue"),
                        aggregate,
                        BiObjectKind.QUEUE,
                    )
                )
                add(
                    DesiredBiObject(
                        BiObjectKey(options.consumerDatabase, "${table}_consumer"),
                        aggregate,
                        BiObjectKind.CONSUMER,
                    )
                )
            }
            add(
                DesiredBiObject(
                    BiObjectKey(options.consumerDatabase, "${stateLast}_consumer"),
                    aggregate,
                    BiObjectKind.CONSUMER,
                )
            )
        }
    }

    private fun desiredAnchor(): DesiredBiObject =
        DesiredBiObject(
            BiObjectKey(options.consumerDatabase, ClickHouseScriptRenderer.DEPLOYMENT_ANCHOR),
            null,
            BiObjectKind.ANCHOR,
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
    )

    private data class ScriptSection(
        val name: String,
        val statements: List<String>,
    )
}
