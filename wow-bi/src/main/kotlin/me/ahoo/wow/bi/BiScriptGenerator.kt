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
import me.ahoo.wow.bi.expansion.plan.StateExpansionPlan
import me.ahoo.wow.bi.expansion.plan.StateExpansionPlanner
import me.ahoo.wow.bi.renderer.ClickHouseScriptRenderer
import me.ahoo.wow.modeling.toStringWithAlias
import java.util.Collections

class BiScriptGenerator(private val options: BiScriptOptions = BiScriptOptions()) {

    fun generate(namedAggregates: Set<NamedAggregate>): BiScriptResult {
        val renderer = ClickHouseScriptRenderer(options)
        val planner = StateExpansionPlanner(options)
        val plannedAggregates = namedAggregates
            .sortedWith(compareBy<NamedAggregate> { it.contextName }.thenBy { it.aggregateName })
            .map { namedAggregate ->
                PlannedAggregate(namedAggregate, planner.plan(namedAggregate))
            }
        val script = buildString {
            appendLine("-- global --")
            appendLine(renderer.renderGlobal())
            appendLine("-- global --")
            appendLine("-- clear --")
            plannedAggregates.forEach { planned ->
                appendSection(planned.namedAggregate, "clear") {
                    renderer.renderClear(
                        namedAggregate = planned.namedAggregate,
                        expansionTables = planned.plan.views.map { it.targetTableName },
                    )
                }
            }
            appendLine("-- clear --")
            plannedAggregates.forEach { planned ->
                appendSection(planned.namedAggregate, "command") {
                    renderer.renderCommand(planned.namedAggregate)
                }
                appendSection(planned.namedAggregate, "stateEvent") {
                    renderer.renderStateEvent(planned.namedAggregate)
                }
                appendSection(planned.namedAggregate, "stateLast") {
                    renderer.renderStateLast(planned.namedAggregate)
                }
                appendSection(planned.namedAggregate, "expansion") {
                    renderer.renderExpansion(planned.plan)
                }
            }
        }
        val diagnostics = plannedAggregates.flatMap { it.plan.diagnostics }
        return BiScriptResult(
            script = script,
            diagnostics = Collections.unmodifiableList(ArrayList(diagnostics)),
        )
    }

    private fun StringBuilder.appendSection(
        namedAggregate: NamedAggregate,
        section: String,
        render: () -> String,
    ) {
        val sectionName = "${namedAggregate.toStringWithAlias()}.$section"
        appendLine("-- $sectionName --")
        appendLine(render())
        appendLine("-- $sectionName --")
    }

    private data class PlannedAggregate(
        val namedAggregate: NamedAggregate,
        val plan: StateExpansionPlan,
    )

    companion object {
        internal fun legacy(options: BiScriptOptions): BiScriptGenerator =
            BiScriptGenerator(options)
    }
}
