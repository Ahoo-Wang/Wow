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

import me.ahoo.wow.bi.renderer.CatalogMutationMode
import me.ahoo.wow.bi.renderer.ClickHouseAggregateRenderPlan
import me.ahoo.wow.bi.renderer.ClickHouseOwnershipRegistryRenderer
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
        val ownershipRegistry = availableInspection?.reconciliation?.ownershipRegistry
        observed?.let { deployment -> observedPolicy.validate(deployment, descriptor, desiredObjects, operation) }
        val consumerIdentity = resolveConsumerIdentity(operation, descriptor, observed)
        val retainedQueueKeys = resolveRetainedQueueKeys(operation, desiredObjects, descriptor, observed)
        val renderer = ClickHouseScriptRenderer(
            options,
            consumerIdentity,
            descriptor,
            if (observed == null) CatalogMutationMode.CREATE_ONLY else CatalogMutationMode.RECONCILE,
            retainedQueueKeys,
        )
        val registryPlan = if (operation == BiScriptOperation.Deploy && shouldRenderDeploymentAnchor) {
            BiOwnershipRegistryPlan.create(
                descriptor = descriptor,
                consumerIdentity = consumerIdentity,
                desiredObjects = desiredObjects,
                current = ownershipRegistry,
            )
        } else {
            null
        }
        val registryRenderer = registryPlan?.let {
            ClickHouseOwnershipRegistryRenderer(
                options = options,
                deploymentId = descriptor.deploymentId,
            )
        }
        val renderedAggregates = plannedAggregates.map { planned ->
            val aggregate = planned.namedAggregate.toStringWithAlias()
            renderer.renderAggregate(planned.namedAggregate, planned.plan, aggregate)
        }

        val globalSection = ScriptSection("global", renderer.renderGlobalStatements())
        val registryCreateSection = registryPlan?.let { plan ->
            ScriptSection(
                "ownership-registry",
                checkNotNull(registryRenderer).renderCreateStatements(plan.afterVerification.name),
            )
        }
        val registryIntentSection = registryPlan
            ?.takeIf(BiOwnershipRegistryPlan::intentChanged)
            ?.let { plan ->
                ScriptSection(
                    "ownership-registry-intent",
                    listOf(checkNotNull(registryRenderer).renderSnapshotStatement(plan.beforeMutation)),
                )
            }
        val lifecycleSections = renderLifecycle(
            LifecycleRenderContext(
                operation,
                plannedAggregates,
                desiredObjects,
                descriptor,
                observed,
                ownershipRegistry,
                renderer,
            )
        )
        val durableAggregateSections = durableAggregateSections(renderedAggregates)
        val ingressSections = ingressSections(renderedAggregates)
        val resetIntentSection = if (operation is BiScriptOperation.Reset) {
            ScriptSection(
                "deployment-reset-intent",
                listOf(renderer.renderAnchorStatement(BiDeploymentPhase.RESETTING)),
            )
        } else {
            null
        }
        val resetRegistrySection = if (operation is BiScriptOperation.Reset) {
            ownershipRegistry?.let { registry ->
                ScriptSection(
                    "reset-ownership-registry",
                    listOf(
                        ClickHouseOwnershipRegistryRenderer(
                            options = options,
                            deploymentId = descriptor.deploymentId,
                        ).renderDropStatement(registry.name)
                    ),
                )
            }
        } else {
            null
        }
        val registryConfirmationSection = registryPlan
            ?.takeIf { plan -> plan.verificationChanged || plan.bootstrap }
            ?.let { plan ->
                ScriptSection(
                    "ownership-registry-confirmation",
                    listOf(checkNotNull(registryRenderer).renderSnapshotStatement(plan.afterVerification)),
                )
            }
        val anchorSection = if (shouldRenderDeploymentAnchor) {
            val registryRevision = registryPlan?.afterVerification?.revision
            ScriptSection(
                "deployment-anchor",
                listOf(
                    renderer.renderAnchorStatement(
                        phase = BiDeploymentPhase.STABLE,
                        registryRevision = registryRevision,
                    )
                ),
            )
        } else {
            null
        }
        val orderedSections = if (operation == BiScriptOperation.Deploy) {
            listOf(globalSection) +
                listOfNotNull(registryCreateSection, registryIntentSection) +
                lifecycleSections +
                durableAggregateSections +
                ingressSections +
                listOfNotNull(registryConfirmationSection, anchorSection)
        } else {
            listOf(globalSection) + listOfNotNull(resetIntentSection, resetRegistrySection) + lifecycleSections +
                durableAggregateSections + listOfNotNull(anchorSection) + ingressSections
        }
        val statements = Collections.unmodifiableList(ArrayList(orderedSections.flatMap(ScriptSection::statements)))
        val script = buildString {
            appendSection(globalSection)
            registryCreateSection?.let { section -> appendSection(section) }
            registryIntentSection?.let { section -> appendSection(section) }
            appendLine("-- lifecycle --")
            resetIntentSection?.let { section -> appendSection(section) }
            resetRegistrySection?.let { section -> appendSection(section) }
            lifecycleSections.forEach { section -> appendSection(section) }
            appendLine("-- lifecycle --")
            durableAggregateSections.forEach { section -> appendSection(section) }
            if (operation == BiScriptOperation.Deploy) {
                ingressSections.forEach { section -> appendSection(section) }
                registryConfirmationSection?.let { section -> appendSection(section) }
                anchorSection?.let { section -> appendSection(section) }
            } else {
                anchorSection?.let { section -> appendSection(section) }
                ingressSections.forEach { section -> appendSection(section) }
            }
        }
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
                    val staleObjects = observed?.let { deployment -> observedPolicy.ownedBy(deployment, descriptor) }
                        .orEmpty()
                        .filter { it.key !in desiredKeys && it.metadata?.kind != BiObjectKind.STORE }
                    if (staleObjects.isNotEmpty()) {
                        add(
                            ScriptSection(
                                "reconcile-observed-catalog",
                                renderer.renderDropObservedStatements(staleObjects),
                            )
                        )
                    }
                }

                is BiScriptOperation.Reset -> {
                    val anchorKey = BiObjectKey(
                        options.consumerDatabase,
                        ClickHouseScriptRenderer.DEPLOYMENT_ANCHOR,
                    )
                    val ownedObjects = resolveResetOwnedObjects(
                        deployment = checkNotNull(observed),
                        descriptor = descriptor,
                        ownershipRegistry = ownershipRegistry,
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

    private fun resolveResetOwnedObjects(
        deployment: ObservedBiDeployment,
        descriptor: BiDeploymentDescriptor,
        ownershipRegistry: BiOwnershipRegistry?,
    ): List<BiOwnedObject> {
        val ownedByKey = observedPolicy.ownedBy(deployment, descriptor).associate { observed ->
            observed.key to BiOwnedObject(
                key = observed.key,
                kind = checkNotNull(observed.metadata).kind,
            )
        }.toMutableMap()
        if (ownershipRegistry == null) {
            return ownedByKey.values.toList()
        }
        val observedKeys = deployment.objects.mapTo(hashSetOf(), ObservedBiObject::key)
        ownershipRegistry.entries.asSequence()
            .filter { entry -> entry.key in observedKeys }
            .forEach { entry ->
                ownedByKey[entry.key] = BiOwnedObject(entry.key, entry.kind)
            }
        return ownedByKey.values.toList()
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
            observedPolicy.resettingAnchor(deployment, descriptor)?.metadata?.consumerIdentity
        }?.let(::BiConsumerIdentity) ?: BiConsumerIdentity.random()
    }

    private fun StringBuilder.appendSection(section: ScriptSection) {
        appendLine("-- ${section.name} --")
        if (section.statements.isNotEmpty()) {
            appendLine(section.statements.joinToString("\n\n"))
        }
        appendLine("-- ${section.name} --")
    }

    private data class LifecycleRenderContext(
        val operation: BiScriptOperation,
        val plannedAggregates: List<PlannedAggregate>,
        val desiredObjects: List<DesiredBiObject>,
        val descriptor: BiDeploymentDescriptor,
        val observed: ObservedBiDeployment?,
        val ownershipRegistry: BiOwnershipRegistry?,
        val renderer: ClickHouseScriptRenderer,
    )

    private data class ScriptSection(val name: String, val statements: List<String>)
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
