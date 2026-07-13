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
import java.util.UUID

class BiScriptGenerator(private val options: BiScriptOptions = BiScriptOptions()) {

    @Suppress("LongMethod")
    fun generate(
        namedAggregates: Set<NamedAggregate>,
        operation: BiScriptOperation = BiScriptOperation.Deploy(),
    ): BiScriptResult {
        if (namedAggregates.isNotEmpty()) {
            requireNotNull(options.consumerGroupNamespace) {
                "consumerGroupNamespace must be configured before generating BI Kafka consumers"
            }
        }
        val planner = StateExpansionPlanner(options)
        val plannedAggregates = namedAggregates
            .sortedWith(compareBy<NamedAggregate> { it.contextName }.thenBy { it.aggregateName })
            .map { namedAggregate ->
                PlannedAggregate(namedAggregate, planner.plan(namedAggregate))
            }
        val deployment = options.toDeploymentManifest()
        val consumerGeneration = when (operation) {
            is BiScriptOperation.Deploy ->
                operation.previousManifest?.consumerGeneration
                    ?: deployment.initialConsumerGeneration()
            is BiScriptOperation.Reset -> UUID.randomUUID()
        }
        val manifest = plannedAggregates.toManifest(deployment, consumerGeneration, operation)
        validateObjectNames(manifest)
        val renderer = ClickHouseScriptRenderer(options, consumerGeneration)
        val globalSection = ScriptSection("global", renderer.renderGlobalStatements())
        val lifecycleSections = when (operation) {
            is BiScriptOperation.Deploy -> renderDeployLifecycle(
                operation,
                manifest,
                plannedAggregates,
                renderer,
            )
            is BiScriptOperation.Reset -> renderResetLifecycle(operation, manifest, plannedAggregates, renderer)
        }
        val aggregateSections = plannedAggregates.flatMap { planned ->
            val name = planned.namedAggregate.toStringWithAlias()
            listOf(
                ScriptSection("$name.command", renderer.renderCommandStatements(planned.namedAggregate)),
                ScriptSection("$name.stateStorage", renderer.renderStateStorageStatements(planned.namedAggregate)),
                ScriptSection("$name.stateLast", renderer.renderStateLastStatements(planned.namedAggregate)),
                ScriptSection("$name.expansion", renderer.renderExpansionStatements(planned.plan)),
                ScriptSection("$name.statePublic", renderer.renderStatePublicStatements(planned.namedAggregate)),
                ScriptSection("$name.stateIngress", renderer.renderStateIngressStatements(planned.namedAggregate)),
            )
        }
        val orderedSections = listOf(globalSection) + lifecycleSections + aggregateSections
        val statements = Collections.unmodifiableList(
            ArrayList(orderedSections.flatMap(ScriptSection::statements))
        )
        val script = buildString {
            appendSection(globalSection)
            appendLine("-- lifecycle --")
            lifecycleSections.forEach { section -> appendSection(section) }
            appendLine("-- lifecycle --")
            aggregateSections.forEach { section -> appendSection(section) }
        }
        val diagnostics = if (plannedAggregates.isEmpty()) {
            lifecycleDiagnostics(operation, manifest)
        } else {
            topologyDiagnostics() + lifecycleDiagnostics(operation, manifest) +
                plannedAggregates.flatMap { it.plan.diagnostics }
        }
        return BiScriptResult(
            script = script,
            statements = statements,
            diagnostics = Collections.unmodifiableList(ArrayList(diagnostics)),
            operation = operation,
            destructive = operation is BiScriptOperation.Reset,
            manifest = manifest,
        )
    }

    private fun renderDeployLifecycle(
        operation: BiScriptOperation.Deploy,
        current: BiScriptManifest,
        plannedAggregates: List<PlannedAggregate>,
        renderer: ClickHouseScriptRenderer,
    ): List<ScriptSection> {
        val previous = operation.previousManifest
        if (previous != null) {
            require(previous.deployment == current.deployment) {
                "BI deployment configuration cannot change during Deploy; use a fresh database or an explicit Reset"
            }
            validateLifecycleOwnership(previous, current)
        }
        val currentByAggregate = current.aggregates.associateBy(BiAggregateManifest::aggregate)
        return buildList {
            plannedAggregates.forEach { planned ->
                val aggregate = planned.namedAggregate.toStringWithAlias()
                add(
                    ScriptSection(
                        "$aggregate.pause-ingress",
                        renderer.renderPauseIngressStatements(planned.namedAggregate),
                    )
                )
            }
            previous?.aggregates.orEmpty().forEach { old ->
                val active = currentByAggregate[old.aggregate]
                if (active == null) {
                    add(ScriptSection("${old.aggregate}.retire", renderer.renderRetireStatements(old)))
                } else {
                    val staleViews = old.expansionViews.toSet() - active.expansionViews.toSet()
                    if (staleViews.isNotEmpty()) {
                        add(
                            ScriptSection(
                                "${old.aggregate}.reconcile",
                                renderer.renderDropExpansionStatements(staleViews.sorted()),
                            )
                        )
                    }
                }
            }
        }
    }

    private fun List<PlannedAggregate>.toManifest(
        deployment: BiDeploymentManifest,
        consumerGeneration: UUID,
        operation: BiScriptOperation,
    ): BiScriptManifest {
        val naming = BiTableNaming(options)
        val activeAggregates = map { planned ->
            val commandTable = naming.toTableName(planned.namedAggregate, "command")
            BiAggregateManifest(
                aggregate = planned.namedAggregate.toStringWithAlias(),
                tablePrefix = commandTable.removeSuffix("_command"),
                expansionViews = planned.plan.views.map { it.targetTableName },
            )
        }
        val activeNames = activeAggregates.map(BiAggregateManifest::aggregate).toSet()
        val retainedAggregates = when (operation) {
            is BiScriptOperation.Deploy -> {
                operation.previousManifest
                    ?.let { previous -> previous.retainedAggregates + previous.aggregates }
                    .orEmpty()
                    .filterNot { it.aggregate in activeNames }
                    .associateBy(BiAggregateManifest::aggregate)
                    .values
                    .sortedBy(BiAggregateManifest::aggregate)
            }
            is BiScriptOperation.Reset -> emptyList()
        }
        return BiScriptManifest(
            deployment = deployment,
            consumerGeneration = consumerGeneration,
            aggregates = activeAggregates,
            retainedAggregates = retainedAggregates,
        )
    }

    private fun validateObjectNames(manifest: BiScriptManifest) {
        val owners = buildMap<String, MutableList<String>> {
            (manifest.aggregates + manifest.retainedAggregates).forEach { aggregate ->
                aggregate.objectNames()
                    .forEach { objectName -> getOrPut(objectName) { mutableListOf() }.add(aggregate.aggregate) }
            }
        }
        val collision = owners.entries.firstOrNull { (_, aggregateOwners) -> aggregateOwners.distinct().size > 1 }
        require(collision == null) {
            "BI object name collision [${collision!!.key}] between aggregates " +
                collision.value.distinct().sorted().joinToString(prefix = "[", postfix = "]")
        }
    }

    private fun renderResetLifecycle(
        operation: BiScriptOperation.Reset,
        current: BiScriptManifest,
        plannedAggregates: List<PlannedAggregate>,
        renderer: ClickHouseScriptRenderer,
    ): List<ScriptSection> {
        val previous = operation.previousManifest
        require(previous.deployment == current.deployment) {
            "BI deployment configuration cannot change during Reset; use the manifest from the active deployment"
        }
        validateLifecycleOwnership(previous, current)
        val currentByAggregate = current.aggregates.associateBy(BiAggregateManifest::aggregate)
        return buildList {
            plannedAggregates.forEach { planned ->
                val aggregateName = planned.namedAggregate.toStringWithAlias()
                val active = checkNotNull(currentByAggregate[aggregateName])
                val old = previous.aggregates.singleOrNull { it.aggregate == aggregateName }
                val expansionViews = (active.expansionViews + old.orEmptyExpansionViews()).distinct()
                add(
                    ScriptSection(
                        name = "$aggregateName.reset",
                        statements = renderer.renderClearStatements(planned.namedAggregate, expansionViews),
                    )
                )
            }
            (previous.aggregates + previous.retainedAggregates)
                .filterNot { it.aggregate in currentByAggregate }
                .forEach { removed ->
                    add(
                        ScriptSection(
                            name = "${removed.aggregate}.reset-orphan",
                            statements = renderer.renderDropAggregateStatements(removed),
                        )
                    )
                }
        }
    }

    private fun BiAggregateManifest?.orEmptyExpansionViews(): List<String> = this?.expansionViews.orEmpty()

    private fun validateLifecycleOwnership(previous: BiScriptManifest, current: BiScriptManifest) {
        val owners = buildMap<String, MutableSet<String>> {
            (
                previous.aggregates + previous.retainedAggregates +
                    current.aggregates + current.retainedAggregates
                ).forEach { aggregate ->
                aggregate.objectNames().forEach { objectName ->
                    getOrPut(objectName) { mutableSetOf() }.add(aggregate.aggregate)
                }
            }
        }
        val collision = owners.entries.firstOrNull { it.value.size > 1 }
        require(collision == null) {
            "BI lifecycle object collision [${collision!!.key}] between aggregates " +
                collision.value.sorted().joinToString(prefix = "[", postfix = "]")
        }
    }

    private fun BiAggregateManifest.objectNames(): List<String> = listOf(
        "${tablePrefix}_command",
        "${tablePrefix}_command_store",
        "${tablePrefix}_command_queue",
        "${tablePrefix}_command_consumer",
        "${tablePrefix}_state",
        "${tablePrefix}_state_store",
        "${tablePrefix}_state_queue",
        "${tablePrefix}_state_consumer",
        "${tablePrefix}_state_event",
        "${tablePrefix}_state_last",
        "${tablePrefix}_state_last_store",
        "${tablePrefix}_state_last_consumer",
    ) + expansionViews

    private fun BiScriptOptions.toDeploymentManifest(): BiDeploymentManifest {
        val cluster = topology as? ClickHouseTopology.Cluster
        return BiDeploymentManifest(
            database = database,
            consumerDatabase = consumerDatabase,
            topology = if (cluster == null) BiManifestTopology.STANDALONE else BiManifestTopology.CLUSTER,
            clusterName = cluster?.name,
            installation = cluster?.installation,
            timezone = timezone,
            kafkaBootstrapServers = kafkaBootstrapServers,
            topicPrefix = topicPrefix,
            consumerGroupNamespace = consumerGroupNamespace,
            kafkaOffsetStorage = kafkaOffsetStorage,
            kafkaKeeperPathPrefix = kafkaKeeperPathPrefix,
        )
    }

    private fun BiDeploymentManifest.initialConsumerGeneration(): UUID {
        val canonical = listOf(
            database,
            consumerDatabase,
            topology.name,
            clusterName.orEmpty(),
            installation.orEmpty(),
            timezone,
            kafkaBootstrapServers,
            topicPrefix,
            consumerGroupNamespace.orEmpty(),
            kafkaOffsetStorage.name,
            kafkaKeeperPathPrefix,
        ).joinToString("\u0000")
        return UUID.nameUUIDFromBytes(canonical.toByteArray(Charsets.UTF_8))
    }

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

    private fun lifecycleDiagnostics(
        operation: BiScriptOperation,
        current: BiScriptManifest,
    ): List<BiScriptDiagnostic> {
        val previous = (operation as? BiScriptOperation.Deploy)?.previousManifest ?: return emptyList()
        val activeAggregates = current.aggregates.map(BiAggregateManifest::aggregate).toSet()
        return current.retainedAggregates
            .filterNot { it.aggregate in activeAggregates }
            .map { removed ->
                BiScriptDiagnostic(
                    code = BiScriptDiagnosticCode.ORPHANED_DATA_TABLE,
                    aggregate = removed.aggregate,
                    path = "lifecycle.retire",
                    sourceType = "BiScriptManifest",
                    decision = BiScriptMappingDecision.DATA_TABLE_RETAINED,
                    message = "Aggregate was removed; command, state, and latest-state data tables were retained.",
                )
            }
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

    private data class ScriptSection(
        val name: String,
        val statements: List<String>,
    )
}
