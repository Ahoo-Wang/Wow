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
        val globalSection = ScriptSection("global", renderer.renderGlobalStatements())
        val clearSections = plannedAggregates.map { planned ->
            ScriptSection(
                name = "${planned.namedAggregate.toStringWithAlias()}.clear",
                statements = renderer.renderClearStatements(
                    namedAggregate = planned.namedAggregate,
                    expansionTables = planned.plan.views.map { it.targetTableName },
                ),
            )
        }
        val aggregateSections = plannedAggregates.flatMap { planned ->
            val name = planned.namedAggregate.toStringWithAlias()
            listOf(
                ScriptSection("$name.command", renderer.renderCommandStatements(planned.namedAggregate)),
                ScriptSection("$name.stateEvent", renderer.renderStateEventStatements(planned.namedAggregate)),
                ScriptSection("$name.stateLast", renderer.renderStateLastStatements(planned.namedAggregate)),
                ScriptSection("$name.expansion", renderer.renderExpansionStatements(planned.plan)),
            )
        }
        val orderedSections = listOf(globalSection) + clearSections + aggregateSections
        val statements = Collections.unmodifiableList(
            ArrayList(orderedSections.flatMap(ScriptSection::statements))
        )
        val script = buildString {
            appendSection(globalSection)
            appendLine("-- clear --")
            clearSections.forEach { section -> appendSection(section) }
            appendLine("-- clear --")
            aggregateSections.forEach { section -> appendSection(section) }
        }
        val diagnostics = plannedAggregates.flatMap { it.plan.diagnostics }
        return BiScriptResult(
            script = script,
            statements = statements,
            diagnostics = Collections.unmodifiableList(ArrayList(diagnostics)),
        )
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
