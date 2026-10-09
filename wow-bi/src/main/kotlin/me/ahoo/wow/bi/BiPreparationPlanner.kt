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
import me.ahoo.wow.bi.expansion.plan.StateExpansionPlanner
import me.ahoo.wow.bi.layout.BiEngine
import me.ahoo.wow.bi.layout.BiLayout
import me.ahoo.wow.bi.renderer.ClickHouseScriptRenderer
import me.ahoo.wow.modeling.toStringWithAlias

internal class BiPreparationPlanner(private val options: BiScriptOptions) {
    private val layout = BiLayout(options)
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

    private fun desiredObjects(plannedAggregates: List<PlannedAggregate>): List<DesiredBiObject> =
        plannedAggregates.flatMap { planned -> desiredObjects(planned) }

    private fun desiredObjects(planned: PlannedAggregate): List<DesiredBiObject> = with(planned) {
        val aggregate = namedAggregate.toStringWithAlias()
        val expectedQueries = definitionRenderer.expectedComputedQueries(namedAggregate, plan)
        val computed = DesiredComputedFactory(aggregate, expectedQueries)
        val names = layout.of(namedAggregate)
        buildList {
            listOf(names.command.store, names.state.store, names.stateLastStore).forEach { store ->
                addDesiredStores(store, aggregate)
            }
            add(computed.view(names.command.table))
            add(computed.view(names.state.table))
            add(computed.view(names.stateEvent))
            add(computed.view(names.stateLast))
            plan.views.forEach { view ->
                add(computed.view(view.targetTableName))
            }
            listOf(names.command, names.state).forEach { stream ->
                add(DesiredBiObject(layout.ingressKey(stream.queue), aggregate, BiObjectKind.QUEUE, BiEngine.KAFKA))
                add(computed.consumer(stream.consumer))
            }
            add(computed.consumer(names.stateLastConsumer))
        }
    }

    private inner class DesiredComputedFactory(
        private val aggregate: String,
        private val expectedQueries: Map<BiObjectKey, ExpectedBiQuery>,
    ) {
        fun view(name: String): DesiredBiObject = create(layout.viewKey(name), BiObjectKind.VIEW)

        fun consumer(name: String): DesiredBiObject = create(layout.ingressKey(name), BiObjectKind.CONSUMER)

        private fun create(key: BiObjectKey, kind: BiObjectKind): DesiredBiObject = DesiredBiObject(
            key = key,
            aggregate = aggregate,
            kind = kind,
            expectedEngine = if (kind == BiObjectKind.VIEW) BiEngine.VIEW else BiEngine.MATERIALIZED_VIEW,
            expectedQuery = checkNotNull(expectedQueries[key]) {
                "Missing expected BI query for [${key.database}.${key.name}]"
            },
        )
    }

    private fun MutableList<DesiredBiObject>.addDesiredStores(store: String, aggregate: String) {
        add(DesiredBiObject(layout.storeKey(store), aggregate, BiObjectKind.STORE, layout.storeEngine))
        if (options.topology is ClickHouseTopology.Cluster) {
            add(
                DesiredBiObject(
                    layout.storeKey(BiLayout.localStore(store)),
                    aggregate,
                    BiObjectKind.STORE,
                    BiEngine.REPLICATED_REPLACING_MERGE_TREE,
                )
            )
        }
    }

    private fun desiredAnchor(): DesiredBiObject = DesiredBiObject(
        layout.anchor,
        null,
        BiObjectKind.ANCHOR,
        BiEngine.VIEW
    )

    private fun validateDesiredObjectNames(objects: List<DesiredBiObject>) {
        val collision = objects.groupBy(DesiredBiObject::key).entries.firstOrNull { it.value.size > 1 }
        require(collision == null) {
            "BI object name collision [${collision!!.key.database}.${collision.key.name}] between aggregates " +
                collision.value.mapNotNull(DesiredBiObject::aggregate).distinct().sorted()
                    .joinToString(prefix = "[", postfix = "]")
        }
    }
}
