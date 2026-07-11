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
import java.util.Collections

internal class ExpansionRecoveryPlan(
    cursors: Collection<CollectionCursorPlan>,
    pointer: Collection<JsonPointerSegment>,
    val currentIndex: ColumnReference?,
) {
    val cursors: List<CollectionCursorPlan> = Collections.unmodifiableList(ArrayList(cursors))
    val pointer: List<JsonPointerSegment> = Collections.unmodifiableList(ArrayList(pointer))
}

internal data class ExpansionViewPlan(
    val targetTableName: String,
    val sourceTableName: String,
    val columns: List<ColumnPlan>,
    val recovery: ExpansionRecoveryPlan,
) {
    companion object {
        val METADATA_TARGET_NAMES: Set<String> = setOf(
            "__id",
            "__aggregate_id",
            "__tenant_id",
            "__owner_id",
            "__space_id",
            "__command_id",
            "__request_id",
            "__version",
            "__first_operator",
            "__first_event_time",
            "__create_time",
            "__tags",
            "__deleted",
            "__state",
            "__path",
            "__index",
        )

        const val CURSOR_TARGET_PREFIX = "__cursor__"
    }
}

internal data class StateExpansionPlan(
    val views: List<ExpansionViewPlan>,
    val diagnostics: List<BiScriptDiagnostic>,
)
