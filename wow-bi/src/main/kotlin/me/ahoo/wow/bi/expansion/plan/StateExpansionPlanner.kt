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

package me.ahoo.wow.bi.expansion.plan

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.bi.BiScriptDiagnostic
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.expansion.BiTableNaming
import me.ahoo.wow.bi.expansion.type.Nullability
import me.ahoo.wow.bi.expansion.type.ResolvedType
import me.ahoo.wow.bi.type.ClickHouseType
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.serialization.JsonSerializer
import java.util.Collections

internal class StateExpansionPlanner(private val options: BiScriptOptions = BiScriptOptions()) {
    private val naming = BiTableNaming(options)

    fun plan(namedAggregate: NamedAggregate): StateExpansionPlan {
        val session = StateExpansionPlanningSession(
            aggregate = "${namedAggregate.contextName}.${namedAggregate.aggregateName}",
            options = options,
        )
        val stateClass = namedAggregate.requiredAggregateType<Any>()
            .aggregateMetadata<Any, Any>()
            .state
            .aggregateType
        val stateType = JsonSerializer.constructType(stateClass)
        val sourceTableName = naming.toTableName(namedAggregate, STATE_LAST_SUFFIX)
        val rootNode = PlanningNode(
            path = "",
            pointer = emptyList(),
            source = ColumnReference.Input(STATE_COLUMN),
            targetName = STATE_COLUMN,
            type = ResolvedType(
                javaType = stateType,
                nullability = Nullability.NON_NULL,
                arguments = emptyList(),
            ),
            depth = 0,
            nullableAncestor = false,
        )

        buildView(
            request = ViewPlanningRequest(
                node = rootNode,
                sourceTableName = sourceTableName,
                targetTableName = "${sourceTableName}_root",
                inheritedColumns = emptyList(),
                recovery = ExpansionRecoveryPlan(emptyList(), emptyList(), null),
            ),
            session = session,
        )
        return StateExpansionPlan(
            views = unmodifiableCopy(session.views),
            diagnostics = unmodifiableCopy(
                session.diagnostics.sortedWith(
                    compareBy<BiScriptDiagnostic>(BiScriptDiagnostic::path)
                        .thenBy { it.code.name }
                )
            ),
        )
    }

    private fun buildView(
        request: ViewPlanningRequest,
        session: StateExpansionPlanningSession,
    ) {
        val draft = ViewDraft(
            targetTableName = request.targetTableName,
            anchorTargetName = request.node.targetName,
            columns = request.inheritedColumns.toMutableList(),
        )
        request.arrayJoin?.let { collection ->
            if (collection.rawElementCompanion) {
                draft.columns.add(
                    ColumnPlan(
                        name = collection.name,
                        path = collection.path,
                        targetName = rawTargetName(collection.targetName),
                        type = ClickHouseType.String,
                        extraction = ColumnExtraction.Reference(ColumnReference.Alias(collection.targetName)),
                        placement = ColumnPlacement.SELECT,
                    )
                )
            }
        }

        session.propertyCollector.collectObjectProperties(request.node, draft)
        validateColumnTargets(draft, session)

        val frozenColumns = unmodifiableCopy(draft.columns.sortedBy(ColumnPlan::targetName))
        session.views.add(
            ExpansionViewPlan(
                targetTableName = request.targetTableName,
                sourceTableName = request.sourceTableName,
                columns = frozenColumns,
                recovery = request.recovery,
            )
        )

        val finalInheritedColumns = frozenColumns.filter(ColumnPlan::inherited)
        draft.collectionRequests.forEach { collection ->
            val childRecovery = ExpansionRecoveryPlan(
                cursors = request.recovery.cursors + collection.cursor,
                pointer = collection.pointer,
                currentIndex = collection.cursor.cursor,
            )
            buildView(
                request = ViewPlanningRequest(
                    node = collection.elementNode,
                    sourceTableName = request.sourceTableName,
                    targetTableName = "${request.targetTableName}_${collection.tableSuffix.toObjectNameSegment()}",
                    inheritedColumns = finalInheritedColumns,
                    recovery = childRecovery,
                    arrayJoin = collection,
                ),
                session = session,
            )
        }
    }
}

private fun <T> unmodifiableCopy(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private const val STATE_LAST_SUFFIX: String = "state_last"
