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

import me.ahoo.wow.bi.expansion.BiTableNaming
import me.ahoo.wow.bi.renderer.CatalogMutationMode
import me.ahoo.wow.bi.renderer.ClickHouseAggregateRenderPlan
import me.ahoo.wow.bi.renderer.ClickHouseScriptRenderer
import me.ahoo.wow.modeling.toStringWithAlias
import java.util.Collections

internal class BiScriptAssembler(private val options: BiScriptOptions) {
    private val observedPolicy = BiObservedDeploymentPolicy(options)
    private val diagnostics = BiScriptDiagnostics(options, observedPolicy)

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
        observed?.let { deployment -> observedPolicy.validate(deployment, descriptor, desiredObjects, operation) }
        val consumerIdentity = resolveConsumerIdentity(operation, descriptor, observed)
        val retainedQueueKeys = resolveRetainedQueueKeys(operation, desiredObjects, descriptor, observed)
        val retainedConsumerKeys = resolveRetainedConsumerKeys(
            plannedAggregates,
            retainedQueueKeys,
            availableInspection?.reconciliation?.verifiedComputedKeys.orEmpty(),
        )
        val renderer = ClickHouseScriptRenderer(
            options,
            consumerIdentity,
            descriptor,
            if (observed == null) CatalogMutationMode.CREATE_ONLY else CatalogMutationMode.RECONCILE,
            retainedQueueKeys,
            retainedConsumerKeys,
        )
        val renderedAggregates = plannedAggregates.map { planned ->
            val aggregate = planned.namedAggregate.toStringWithAlias()
            renderer.renderAggregate(planned.namedAggregate, planned.plan, aggregate)
        }

        val lifecycleSections = renderLifecycle(
            LifecycleRenderContext(operation, plannedAggregates, desiredObjects, descriptor, observed, renderer)
        )
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
                    renderer.renderAnchorStatement(
                        BiDeploymentPhase.STABLE,
                        durableInventory(operation, desiredObjects, descriptor, observed),
                    )
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
        return observedPolicy.ownedBy(observed, descriptor).asSequence()
            .filter { it.metadata?.kind == BiObjectKind.QUEUE && it.key in desiredQueueKeys }
            .map(ObservedBiObject::key)
            .toSet()
    }

    /**
     * Keeps a verified ingress chain attached across DEPLOY instead of dropping and recreating it.
     *
     * Kafka-engine streaming resolves its attached views before each poll cycle and commits what that cycle
     * reads; a consumer dropped inside that window lets the cycle commit messages without writing them.
     * Retaining a chain is all-or-nothing per stream: the state consumer feeds `state_last` through the state
     * store, so `state_last` may only be recreated while state ingress is paused.
     */
    private fun resolveRetainedConsumerKeys(
        plannedAggregates: List<PlannedAggregate>,
        retainedQueueKeys: Set<BiObjectKey>,
        verifiedComputedKeys: Set<BiObjectKey>,
    ): Set<BiObjectKey> {
        if (retainedQueueKeys.isEmpty()) {
            return emptySet()
        }
        val naming = BiTableNaming(options)
        fun consumerKey(table: String) = BiObjectKey(options.consumerDatabase, "${table}_consumer")
        return plannedAggregates.flatMapTo(linkedSetOf()) { planned ->
            val aggregate = planned.namedAggregate
            val command = naming.toTableName(aggregate, ClickHouseScriptRenderer.COMMAND_SUFFIX)
            val state = naming.toTableName(aggregate, ClickHouseScriptRenderer.STATE_SUFFIX)
            val stateLast = naming.toTableName(aggregate, ClickHouseScriptRenderer.STATE_LAST_SUFFIX)
            listOf(
                command to listOf(consumerKey(command)),
                state to listOf(consumerKey(state), consumerKey(stateLast)),
            ).filter { (stream, chain) ->
                BiObjectKey(options.consumerDatabase, "${stream}_queue") in retainedQueueKeys &&
                    chain.all { key -> key in verifiedComputedKeys }
            }.flatMap { (_, chain) -> chain }
        }
    }

    private fun renderLifecycle(context: LifecycleRenderContext): List<ScriptSection> = with(context) {
        buildList {
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
                    val staleObjects = observed?.let { deployment ->
                        resolveOwnedCatalogObjects(deployment, descriptor)
                    }
                        .orEmpty()
                        .filter { it.key !in desiredKeys && it.kind != BiObjectKind.STORE }
                    if (staleObjects.isNotEmpty()) {
                        add(
                            ScriptSection(
                                "reconcile-observed-catalog",
                                renderer.renderDropOwnedStatements(staleObjects),
                            )
                        )
                    }
                }

                is BiScriptOperation.Reset -> {
                    val anchorKey = BiObjectKey(
                        options.consumerDatabase,
                        ClickHouseScriptRenderer.DEPLOYMENT_ANCHOR,
                    )
                    val ownedObjects = resolveOwnedCatalogObjects(
                        deployment = checkNotNull(observed),
                        descriptor = descriptor,
                    )
                        .filterNot { it.key == anchorKey }
                    if (ownedObjects.isNotEmpty()) {
                        add(
                            ScriptSection(
                                "reset-observed-catalog",
                                renderer.renderDropOwnedStatements(ownedObjects),
                            )
                        )
                    }
                }
            }
        }
    }

    private fun resolveOwnedCatalogObjects(
        deployment: ObservedBiDeployment,
        descriptor: BiDeploymentDescriptor,
    ): List<BiOwnedObject> = observedPolicy.ownedBy(deployment, descriptor).map { observed ->
        BiOwnedObject(key = observed.key, kind = checkNotNull(observed.metadata).kind)
    }

    /**
     * The stores and queues that exist when the anchor is written, so the inventory never runs ahead of the catalog.
     *
     * DEPLOY writes the anchor last: every desired store and queue, plus the stores it keeps for their data after
     * their aggregate left. RESET writes it before Kafka ingress, so it records the stores only; the next DEPLOY
     * records the queues.
     */
    private fun durableInventory(
        operation: BiScriptOperation,
        desiredObjects: List<DesiredBiObject>,
        descriptor: BiDeploymentDescriptor,
        observed: ObservedBiDeployment?,
    ): List<BiDurableEntry> {
        val recorded = if (operation == BiScriptOperation.Deploy) {
            setOf(BiObjectKind.STORE, BiObjectKind.QUEUE)
        } else {
            setOf(BiObjectKind.STORE)
        }
        val desired = desiredObjects.filter { it.kind in recorded }.map {
            BiDurableEntry(
                it.key,
                BiDurableStatus.ACTIVE
            )
        }
        val desiredKeys = desired.mapTo(hashSetOf(), BiDurableEntry::key)
        val retired = if (operation == BiScriptOperation.Deploy && observed != null) {
            observedPolicy.ownedBy(observed, descriptor)
                .filter { it.metadata?.kind == BiObjectKind.STORE && it.key !in desiredKeys }
                .map { BiDurableEntry(it.key, BiDurableStatus.RETIRED) }
        } else {
            emptyList()
        }
        return (desired + retired).sortedWith(compareBy({ it.key.database }, { it.key.name }))
    }

    private fun resolveConsumerIdentity(
        operation: BiScriptOperation,
        descriptor: BiDeploymentDescriptor,
        observed: ObservedBiDeployment?,
    ): BiConsumerIdentity = when (operation) {
        BiScriptOperation.Deploy -> observed?.let { deployment ->
            observedPolicy.consumerIdentity(deployment, descriptor)
        } ?: BiConsumerIdentity.deterministic(descriptor)

        is BiScriptOperation.Reset -> observed?.let { deployment ->
            observedPolicy.resettingAnchor(deployment, descriptor)?.consumerIdentity
        }?.let(::BiConsumerIdentity) ?: BiConsumerIdentity.random()
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

    private data class LifecycleRenderContext(
        val operation: BiScriptOperation,
        val plannedAggregates: List<PlannedAggregate>,
        val desiredObjects: List<DesiredBiObject>,
        val descriptor: BiDeploymentDescriptor,
        val observed: ObservedBiDeployment?,
        val renderer: ClickHouseScriptRenderer,
    )

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
    private val observedPolicy: BiObservedDeploymentPolicy,
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
        return observed?.let { deployment -> observedPolicy.ownedBy(deployment, descriptor) }.orEmpty()
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
