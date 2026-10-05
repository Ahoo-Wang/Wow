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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.query.annotation.Sensitive
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.bi.BiScriptDiagnosticCode
import me.ahoo.wow.bi.BiScriptGenerator
import me.ahoo.wow.bi.BiScriptMappingDecision
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.query.schema.QueryTypeFact
import me.ahoo.wow.query.schema.effectiveSensitivityLevel
import me.ahoo.wow.schema.query.JsonQueryModelSource
import org.junit.jupiter.api.Test

class StateExpansionPlannerSensitiveTest {

    @Test
    fun `should expand sensitive properties when the switch is off`() {
        val plan = StateExpansionPlanner(BiScriptOptions()).plan(sensitiveAggregateMetadata)

        plan.columnPaths().assert().containsAll(SENSITIVE_PATHS + PLAIN_PATHS)
        plan.diagnostics.none { it.code == BiScriptDiagnosticCode.SENSITIVE_FIELD_OMITTED }.assert().isTrue()
    }

    @Test
    fun `should leave sensitive properties out when the switch is on`() {
        val plan = StateExpansionPlanner(BiScriptOptions(omitSensitiveFields = true)).plan(sensitiveAggregateMetadata)

        plan.columnPaths().assert().containsAll(PLAIN_PATHS).doesNotContainAnyElementsOf(SENSITIVE_PATHS)
        plan.views.flatMap { it.columns }.none { it.path.startsWith("phones") }.assert().isTrue()
        val omitted = plan.diagnostics.filter { it.code == BiScriptDiagnosticCode.SENSITIVE_FIELD_OMITTED }
        omitted.map { it.path }.assert().containsExactlyInAnyOrderElementsOf(SENSITIVE_PATHS)
        omitted.forEach { it.decision.assert().isEqualTo(BiScriptMappingDecision.OMITTED) }
        omitted.single { it.path == "password" }.message.assert().contains("CONFIDENTIAL")
    }

    @Test
    fun `should omit exactly the properties the query schema masks`() {
        val plan = StateExpansionPlanner(BiScriptOptions(omitSensitiveFields = true)).plan(sensitiveAggregateMetadata)
        val omitted = plan.diagnostics.filter { it.code == BiScriptDiagnosticCode.SENSITIVE_FIELD_OMITTED }
            .map { it.path }

        val masked = JsonQueryModelSource().describe(SensitiveState::class.java).sensitivePaths("")
        omitted.assert().containsExactlyInAnyOrderElementsOf(masked)
    }

    @Test
    fun `should render no column for an omitted property`() {
        val consumerGroupNamespace = "test"
        val expanded = BiScriptGenerator(BiScriptOptions(consumerGroupNamespace = consumerGroupNamespace))
            .generate(setOf(sensitiveAggregateMetadata)).script
        val omitted = BiScriptGenerator(
            BiScriptOptions(consumerGroupNamespace = consumerGroupNamespace, omitSensitiveFields = true)
        ).generate(setOf(sensitiveAggregateMetadata)).script

        expanded.assert().contains("AS \"password\"", "AS \"profile__id_card\"")
        omitted.assert().doesNotContain("AS \"password\"", "AS \"profile__id_card\"")
        omitted.assert().contains("AS \"name\"")
    }

    private fun StateExpansionPlan.columnPaths(): Set<String> = views.flatMap { it.columns }.map { it.path }.toSet()

    private fun QueryTypeFact.sensitivePaths(path: String): Set<String> = buildSet {
        if (member?.effectiveSensitivityLevel() != null) {
            add(path)
            return@buildSet
        }
        properties.forEach { (name, child) ->
            addAll(
                child.sensitivePaths(if (path.isEmpty()) name else "$path.$name")
            )
        }
        items?.let { addAll(it.sensitivePaths(path)) }
        alternatives.forEach { addAll(it.sensitivePaths(path)) }
    }

    private companion object {
        val SENSITIVE_PATHS = setOf("password", "email", "token", "phone", "phones", "profile.idCard")
        val PLAIN_PATHS = setOf("name", "profile.nickname")
    }
}

@JvmInline
@Sensitive(SensitivityLevel.DISPLAY)
value class SensitivePhone(val value: String)

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Sensitive(SensitivityLevel.CONFIDENTIAL)
annotation class Secret

@Suppress("UnusedPrivateProperty")
@AggregateRoot
class SensitiveAggregate(private val state: SensitiveState)

class SensitiveState(override val id: String) : Identifier {
    val name: String = ""

    @field:Sensitive(SensitivityLevel.CONFIDENTIAL)
    val password: String = ""

    @get:Sensitive(SensitivityLevel.DISPLAY)
    val email: String? = null

    @field:Secret
    val token: String = ""

    val phone: SensitivePhone = SensitivePhone("")
    val phones: List<SensitivePhone> = emptyList()
    val profile: SensitiveProfile = SensitiveProfile()
}

class SensitiveProfile {
    val nickname: String = ""

    @field:Sensitive(SensitivityLevel.CONFIDENTIAL)
    val idCard: String = ""
}

private val sensitiveAggregateMetadata = aggregateMetadata<SensitiveAggregate, SensitiveState>()
