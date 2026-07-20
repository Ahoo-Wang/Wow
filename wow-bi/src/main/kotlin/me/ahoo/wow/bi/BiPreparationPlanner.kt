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
import me.ahoo.wow.bi.expansion.plan.StateExpansionPlanner
import me.ahoo.wow.bi.renderer.ClickHouseScriptRenderer
import me.ahoo.wow.modeling.toStringWithAlias

internal class BiPreparationPlanner(private val options: BiScriptOptions) {
    private val definitionRenderer = ClickHouseScriptRenderer(options)

    fun plan(namedAggregates: Set<NamedAggregate>): BiScriptPreparation {
        val plannedAggregates = planAggregates(namedAggregates)
        val desiredObjects = desiredObjects(plannedAggregates) +
            if (options.consumerGroupNamespace != null) listOf(desiredAnchor()) else emptyList()
        validateDesiredObjectNames(desiredObjects)
        return BiScriptPreparation.create(
            options = options,
            namedAggregates = namedAggregates,
            plannedAggregates = plannedAggregates,
            desiredObjects = desiredObjects,
        )
    }

    private fun planAggregates(namedAggregates: Set<NamedAggregate>): List<PlannedAggregate> {
        val planner = StateExpansionPlanner(options)
        return namedAggregates
            .sortedWith(compareBy<NamedAggregate> { it.contextName }.thenBy { it.aggregateName })
            .map { namedAggregate -> PlannedAggregate(namedAggregate, planner.plan(namedAggregate)) }
    }

    private fun desiredObjects(plannedAggregates: List<PlannedAggregate>): List<DesiredBiObject> {
        val naming = BiTableNaming(options)
        return plannedAggregates.flatMap { planned -> desiredObjects(planned, naming) }
    }

    private fun desiredObjects(
        planned: PlannedAggregate,
        naming: BiTableNaming,
    ): List<DesiredBiObject> = with(planned) {
        val aggregate = namedAggregate.toStringWithAlias()
        val expectedQueries = definitionRenderer.expectedComputedQueries(namedAggregate, plan)
        val computed = DesiredComputedFactory(aggregate, expectedQueries)
        val command = naming.toTableName(namedAggregate, "command")
        val state = naming.toTableName(namedAggregate, "state")
        val stateLast = naming.toTableName(namedAggregate, "state_last")
        val statePhysical = naming.toTableName(namedAggregate, ClickHouseScriptRenderer.STATE_SUFFIX)
        val stateLastPhysical =
            naming.toTableName(namedAggregate, ClickHouseScriptRenderer.STATE_LAST_SUFFIX)
        buildList {
            listOf(command, statePhysical, stateLastPhysical).forEach { table -> addDesiredStores(table, aggregate) }
            add(computed.view(command))
            add(computed.view(state))
            add(computed.view("${state}_event"))
            add(computed.view(stateLast))
            plan.views.forEach { view ->
                add(computed.view(view.targetTableName))
            }
            listOf(command, statePhysical).forEach { table ->
                add(
                    DesiredBiObject(
                        BiObjectKey(options.consumerDatabase, "${table}_queue"),
                        aggregate,
                        BiObjectKind.QUEUE,
                        QUEUE_ENGINE,
                    )
                )
                add(computed.consumer("${table}_consumer"))
            }
            add(computed.consumer("${stateLastPhysical}_consumer"))
        }
    }

    private inner class DesiredComputedFactory(
        private val aggregate: String,
        private val expectedQueries: Map<BiObjectKey, ExpectedBiQuery>,
    ) {
        fun view(name: String): DesiredBiObject = create(BiObjectKey(options.database, name), BiObjectKind.VIEW)

        fun consumer(name: String): DesiredBiObject =
            create(BiObjectKey(options.consumerDatabase, name), BiObjectKind.CONSUMER)

        private fun create(key: BiObjectKey, kind: BiObjectKind): DesiredBiObject = DesiredBiObject(
            key = key,
            aggregate = aggregate,
            kind = kind,
            expectedEngine = if (kind == BiObjectKind.VIEW) VIEW_ENGINE else CONSUMER_ENGINE,
            expectedQuery = checkNotNull(expectedQueries[key]) {
                "Missing expected BI query for [${key.database}.${key.name}]"
            },
        )
    }

    private fun MutableList<DesiredBiObject>.addDesiredStores(table: String, aggregate: String) {
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

    private fun desiredAnchor(): DesiredBiObject = DesiredBiObject(
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

    private companion object {
        const val VIEW_ENGINE: String = "View"
        const val QUEUE_ENGINE: String = "Kafka"
        const val CONSUMER_ENGINE: String = "MaterializedView"
    }
}
