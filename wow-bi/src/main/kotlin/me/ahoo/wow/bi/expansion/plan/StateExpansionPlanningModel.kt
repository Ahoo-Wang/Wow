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

import me.ahoo.wow.bi.BiScriptDiagnostic
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.expansion.type.ResolvedType

internal const val STATE_COLUMN: String = "state"

internal data class ViewPlanningRequest(
    val node: PlanningNode,
    val sourceTableName: String,
    val targetTableName: String,
    val inheritedColumns: List<ColumnPlan>,
    val recovery: ExpansionRecoveryPlan,
    val arrayJoin: CollectionRequest? = null,
)

internal data class PropertyPlanningRequest(
    val parent: PlanningNode,
    val name: String,
    val path: String,
    val targetName: String,
    val type: ResolvedType,
    val draft: ViewDraft,
)

internal data class PlanningNode(
    val path: String,
    val pointer: List<JsonPointerSegment>,
    val source: ColumnReference,
    val targetName: String,
    val type: ResolvedType,
    val depth: Int,
    val nullableAncestor: Boolean,
)

internal data class CollectionRequest(
    val name: String,
    val path: String,
    val source: ColumnReference,
    val targetName: String,
    val tableSuffix: String,
    val cursor: CollectionCursorPlan,
    val pointer: List<JsonPointerSegment>,
    val elementNode: PlanningNode,
    val rawElementCompanion: Boolean,
)

internal data class ViewDraft(
    val targetTableName: String,
    val anchorTargetName: String,
    val columns: MutableList<ColumnPlan>,
    val propertyTargetNames: MutableList<String> = mutableListOf(),
    val collectionRequests: MutableList<CollectionRequest> = mutableListOf(),
)

internal class StateExpansionPlanningSession(
    val aggregate: String,
    val options: BiScriptOptions,
) {
    val views: MutableList<ExpansionViewPlan> = mutableListOf()
    val diagnostics: MutableList<BiScriptDiagnostic> = mutableListOf()
    val propertyCollector: StateExpansionPropertyCollector = StateExpansionPropertyCollector(this)
}
