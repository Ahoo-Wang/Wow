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
import me.ahoo.wow.bi.BiScriptDiagnosticCode
import me.ahoo.wow.bi.BiScriptMappingDecision
import me.ahoo.wow.bi.UnsupportedTypeStrategy
import me.ahoo.wow.bi.type.ClickHouseType

internal class StateExpansionFallbackCollector(
    private val session: StateExpansionPlanningSession,
) {
    fun collectDepth(request: PropertyPlanningRequest) {
        request.draft.columns.add(rawValueColumn(request.toRawColumnRequest()))
        val sourceType = request.type.javaType.toCanonical()
        session.diagnostics.add(
            BiScriptDiagnostic(
                code = BiScriptDiagnosticCode.MAX_DEPTH_REACHED,
                aggregate = session.aggregate,
                path = request.path,
                sourceType = sourceType,
                decision = BiScriptMappingDecision.MAX_DEPTH_RAW_JSON,
                message = "Max expansion depth reached at [${request.path}] with type [$sourceType]; " +
                    "the value is projected as scoped JSON and remains authoritative in __state.",
            )
        )
    }

    fun collectOpaque(node: PlanningNode, draft: ViewDraft) {
        val path = node.path.ifBlank { ROOT_PATH }
        val sourceType = node.type.javaType.toCanonical()
        require(session.options.unsupportedTypeStrategy == UnsupportedTypeStrategy.RAW_JSON) {
            "Unsupported property [$path] for aggregate [${session.aggregate}] with type [$sourceType]."
        }
        draft.columns.add(
            ColumnPlan(
                name = node.targetName,
                path = path,
                targetName = node.targetName,
                type = ClickHouseType.String,
                extraction = ColumnExtraction.Reference(node.source),
                placement = ColumnPlacement.SELECT,
            )
        )
        session.diagnostics.add(
            BiScriptDiagnostic(
                code = BiScriptDiagnosticCode.RAW_JSON_FALLBACK,
                aggregate = session.aggregate,
                path = path,
                sourceType = sourceType,
                decision = BiScriptMappingDecision.RAW_JSON,
                message = "Unsupported property [$path] with type [$sourceType] is projected as scoped JSON; " +
                    "the authoritative lexical value remains in __state.",
            )
        )
    }

    fun collectRaw(request: PropertyPlanningRequest) {
        val sourceType = request.type.javaType.toCanonical()
        require(session.options.unsupportedTypeStrategy == UnsupportedTypeStrategy.RAW_JSON) {
            "Unsupported property [${request.path}] for aggregate [${session.aggregate}] with type [$sourceType]."
        }
        request.draft.columns.add(rawValueColumn(request.toRawColumnRequest()))
        session.diagnostics.add(
            BiScriptDiagnostic(
                code = BiScriptDiagnosticCode.RAW_JSON_FALLBACK,
                aggregate = session.aggregate,
                path = request.path,
                sourceType = sourceType,
                decision = BiScriptMappingDecision.RAW_JSON,
                message = "Unsupported property [${request.path}] with type [$sourceType] is projected as scoped JSON; " +
                    "the authoritative lexical value remains in __state.",
            )
        )
    }
}

private const val ROOT_PATH: String = "$"
