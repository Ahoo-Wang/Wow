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

import me.ahoo.wow.bi.plan.BiChangePlan
import me.ahoo.wow.bi.plan.BiReconciler
import me.ahoo.wow.bi.renderer.ClickHouseAggregateRenderPlan
import me.ahoo.wow.bi.renderer.ClickHouseScriptRenderer
import me.ahoo.wow.modeling.toStringWithAlias
import java.util.Collections

internal class BiScriptAssembler(private val options: BiScriptOptions) {
    private val policy = BiOperationPolicy(options)
    private val reconciler = BiReconciler(options)
    private val diagnostics = BiScriptDiagnostics(options, policy)

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun assemble(
        preparation: BiScriptPreparation,
        operation: BiScriptOperation,
        inspection: BiDeploymentInspection,
    ): BiScriptResult {
        val plannedAggregates = preparation.plannedAggregates
        val descriptor = BiDeploymentDescriptor.from(options)
        val shouldRenderDeploymentAnchor = options.consumerGroupNamespace != null
        require(operation !is BiScriptOperation.Reset || shouldRenderDeploymentAnchor) {
            "consumerGroupNamespace must be configured before RESET so its recovery state can be persisted"
        }
        val desiredObjects = preparation.desiredObjects
        val availableInspection = inspection as? BiDeploymentInspection.Available
        val observed = availableInspection?.deployment
        val plan = reconciler.plan(
            plannedAggregates = plannedAggregates,
            desiredObjects = desiredObjects,
            operation = operation,
            observed = observed,
            verifiedComputedKeys = availableInspection?.reconciliation?.verifiedComputedKeys.orEmpty(),
        )
        val renderer = ClickHouseScriptRenderer(options, plan, descriptor)
        val renderedAggregates = plannedAggregates.map { planned ->
            val aggregate = planned.namedAggregate.toStringWithAlias()
            renderer.renderAggregate(planned.namedAggregate, planned.plan, aggregate)
        }

        val lifecycleSections = renderLifecycle(plannedAggregates, plan, observed != null, renderer)
        val resetIntent = if (operation is BiScriptOperation.Reset) {
            ScriptSection(
                "deployment-reset-intent",
                listOf(renderer.renderAnchorStatement(BiDeploymentPhase.RESETTING, emptyList())),
            )
        } else {
            null
        }
        val anchor = if (shouldRenderDeploymentAnchor) {
            ScriptSection(
                "deployment-anchor",
                listOf(
                    renderer.renderAnchorStatement(BiDeploymentPhase.STABLE, plan.durableInventory)
                ),
            )
        } else {
            null
        }
        val lifecycle = ScriptGroup("lifecycle", listOfNotNull(resetIntent) + lifecycleSections)
        val durable = durableAggregateSections(renderedAggregates)
        val ingress = ingressSections(renderedAggregates)
        val blocks: List<ScriptBlock> = listOf(ScriptSection("global", renderer.renderGlobalStatements()), lifecycle) +
            durable +
            if (operation == BiScriptOperation.Deploy) ingress + listOfNotNull(anchor) else listOfNotNull(anchor) + ingress
        val statements = Collections.unmodifiableList(ArrayList(blocks.flatMap(ScriptBlock::statements)))
        val script = buildString { blocks.forEach { block -> appendBlock(block) } }
        return BiScriptResult(
            script = script,
            statements = statements,
            diagnostics = Collections.unmodifiableList(
                ArrayList(
                    diagnostics.collect(
                        BiScriptDiagnosticsContext(
                            plannedAggregates,
                            inspection,
                            operation,
                            desiredObjects,
                            descriptor,
                            observed,
                        )
                    )
                )
            ),
            operation = operation,
            destructive = operation is BiScriptOperation.Reset,
        )
    }

    private fun durableAggregateSections(
        renderedAggregates: List<ClickHouseAggregateRenderPlan>,
    ): List<ScriptSection> = renderedAggregates.flatMap { rendered ->
        val name = rendered.aggregate
        listOf(
            ScriptSection("$name.commandStorage", rendered.command.storage),
            ScriptSection("$name.stateStorage", rendered.state.storage),
            ScriptSection("$name.stateLast", rendered.stateLast),
            ScriptSection("$name.expansion", rendered.expansion),
            ScriptSection("$name.commandPublic", rendered.command.publicViews),
            ScriptSection("$name.statePublic", rendered.state.publicViews),
        )
    }

    private fun ingressSections(
        renderedAggregates: List<ClickHouseAggregateRenderPlan>,
    ): List<ScriptSection> = renderedAggregates.flatMap { rendered ->
        val name = rendered.aggregate
        listOf(
            ScriptSection("$name.commandIngress", rendered.command.ingress),
            ScriptSection("$name.stateIngress", rendered.state.ingress),
        )
    }

    private fun renderLifecycle(
        plannedAggregates: List<PlannedAggregate>,
        plan: BiChangePlan,
        observed: Boolean,
        renderer: ClickHouseScriptRenderer,
    ): List<ScriptSection> = buildList {
        if (plan.operation == BiScriptOperation.Deploy && observed) {
            plannedAggregates.forEach { planned ->
                add(
                    ScriptSection(
                        "${planned.namedAggregate.toStringWithAlias()}.pause-ingress",
                        renderer.renderPauseIngressStatements(planned.namedAggregate),
                    )
                )
            }
        }
        if (plan.drops.isNotEmpty()) {
            val name = if (plan.operation is BiScriptOperation.Reset) "reset-observed-catalog" else "reconcile-observed-catalog"
            add(ScriptSection(name, renderer.renderDropOwnedStatements(plan.drops)))
        }
    }

    private fun StringBuilder.appendBlock(block: ScriptBlock) {
        when (block) {
            is ScriptGroup -> {
                appendLine("-- ${block.name} --")
                block.sections.forEach { section -> appendBlock(section) }
                appendLine("-- ${block.name} --")
            }

            is ScriptSection -> {
                appendLine("-- ${block.name} --")
                if (block.statements.isNotEmpty()) {
                    appendLine(block.statements.joinToString("\n\n"))
                }
                appendLine("-- ${block.name} --")
            }
        }
    }

    /** One ordered list of blocks yields both [BiScriptResult.statements] and [BiScriptResult.script]. */
    private sealed interface ScriptBlock {
        val name: String
        val statements: List<String>
    }

    private data class ScriptSection(override val name: String, override val statements: List<String>) : ScriptBlock

    private data class ScriptGroup(override val name: String, val sections: List<ScriptSection>) : ScriptBlock {
        override val statements: List<String>
            get() = sections.flatMap(ScriptSection::statements)
    }
}

internal class BiScriptDiagnostics(
    private val options: BiScriptOptions,
    private val policy: BiOperationPolicy,
) {
    fun collect(context: BiScriptDiagnosticsContext): List<BiScriptDiagnostic> = with(context) {
        buildList {
            if (plannedAggregates.isNotEmpty()) {
                addAll(topologyDiagnostics())
            }
            if (inspection is BiDeploymentInspection.Unavailable) {
                add(inspectionUnavailableDiagnostic())
            }
            addAll(retainedStoreDiagnostics(operation, desiredObjects, descriptor, observed))
            addAll(computedObjectDriftDiagnostics(inspection, operation))
            addAll(plannedAggregates.flatMap { it.plan.diagnostics })
        }
    }

    private fun computedObjectDriftDiagnostics(
        inspection: BiDeploymentInspection,
        operation: BiScriptOperation,
    ): List<BiScriptDiagnostic> {
        if (operation != BiScriptOperation.Deploy) {
            return emptyList()
        }
        return (inspection as? BiDeploymentInspection.Available)
            ?.reconciliation
            ?.repairableComputedDrifts
            .orEmpty()
            .sortedWith(compareBy<RepairableBiObjectDrift> { it.key.database }.thenBy { it.key.name })
            .map { drift ->
                val fields = drift.mismatches.map(BiComputedDefinitionField::name).sorted().joinToString()
                BiScriptDiagnostic(
                    code = BiScriptDiagnosticCode.COMPUTED_OBJECT_DRIFT,
                    aggregate = drift.aggregate,
                    path = "lifecycle.reconcile.${drift.key.database}.${drift.key.name}",
                    sourceType = "ClickHouseCatalog",
                    decision = BiScriptMappingDecision.RECONCILIATION_PLANNED,
                    message = "Computed object [${drift.key.database}.${drift.key.name}] has repairable " +
                        "definition drift [$fields]; generated DEPLOY reconciles it.",
                )
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
        return observed?.let { deployment -> policy.ownedBy(deployment, descriptor) }.orEmpty()
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
}

internal data class BiScriptDiagnosticsContext(
    val plannedAggregates: List<PlannedAggregate>,
    val inspection: BiDeploymentInspection,
    val operation: BiScriptOperation,
    val desiredObjects: List<DesiredBiObject>,
    val descriptor: BiDeploymentDescriptor,
    val observed: ObservedBiDeployment?,
)
