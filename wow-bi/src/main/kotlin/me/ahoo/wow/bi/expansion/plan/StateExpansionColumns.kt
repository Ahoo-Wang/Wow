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

import me.ahoo.wow.bi.type.ClickHouseType

internal data class RawColumnRequest(
    val name: String,
    val path: String,
    val targetName: String,
    val source: ColumnReference,
)

internal fun PropertyPlanningRequest.toRawColumnRequest(): RawColumnRequest = RawColumnRequest(
    name = name,
    path = path,
    targetName = targetName,
    source = parent.source,
)

internal fun rawValueColumn(request: RawColumnRequest): ColumnPlan {
    return ColumnPlan(
        name = request.name,
        path = request.path,
        targetName = request.targetName,
        type = ClickHouseType.String,
        extraction = ColumnExtraction.JsonRaw(request.source, request.name),
        placement = ColumnPlacement.SELECT,
    )
}

internal fun rawObjectArrayColumn(request: RawColumnRequest): ColumnPlan {
    return ColumnPlan(
        name = request.name,
        path = request.path,
        targetName = request.targetName,
        type = ClickHouseType.Array(ClickHouseType.String),
        extraction = ColumnExtraction.JsonArray(request.source, request.name),
        placement = ColumnPlacement.SELECT,
        inherited = false,
    )
}

internal fun rawCompanionColumn(
    request: RawColumnRequest,
    inherited: Boolean = true,
): ColumnPlan {
    return ColumnPlan(
        name = request.name,
        path = request.path,
        targetName = rawTargetName(request.targetName),
        type = ClickHouseType.String,
        extraction = ColumnExtraction.JsonRaw(request.source, request.name),
        placement = ColumnPlacement.SELECT,
        inherited = inherited,
    )
}

internal fun validateColumnTargets(draft: ViewDraft, session: StateExpansionPlanningSession) {
    val reservedProperty = draft.propertyTargetNames
        .sorted()
        .firstOrNull {
            it.startsWith(RAW_TARGET_PREFIX) ||
                it.startsWith(ExpansionViewPlan.CURSOR_TARGET_PREFIX)
        }
    require(reservedProperty == null) {
        "Reserved namespace collision for aggregate [${session.aggregate}] in view " +
            "[${draft.targetTableName}] at target [$reservedProperty]."
    }

    val duplicates = (draft.columns.map(ColumnPlan::targetName) + ExpansionViewPlan.METADATA_TARGET_NAMES)
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .keys
        .sorted()
    require(duplicates.isEmpty()) {
        "Target name collision for aggregate [${session.aggregate}] in view [${draft.targetTableName}]: " +
            "duplicate target(s) ${duplicates.joinToString(prefix = "[", postfix = "]")}."
    }
}
