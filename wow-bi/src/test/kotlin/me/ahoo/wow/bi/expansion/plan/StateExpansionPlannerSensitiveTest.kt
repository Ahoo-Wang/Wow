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
import me.ahoo.wow.bi.generate
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
        plan.selectedPaths().assert().containsAll(RAW_PATHS)
        plan.diagnostics.none { it.code == BiScriptDiagnosticCode.SENSITIVE_FIELD_OMITTED }.assert().isTrue()
    }

    @Test
    fun `should leave sensitive properties out when the switch is on`() {
        val plan = StateExpansionPlanner(BiScriptOptions(omitSensitiveFields = true)).plan(sensitiveAggregateMetadata)

        plan.columnPaths().assert().containsAll(PLAIN_PATHS).doesNotContainAnyElementsOf(SENSITIVE_PATHS)
        plan.views.flatMap { it.columns }.none { it.path.startsWith("phones") }.assert().isTrue()
        val omitted = plan.diagnostics.filter { it.code == BiScriptDiagnosticCode.SENSITIVE_FIELD_OMITTED }
        omitted.map { it.path }.assert().containsExactlyInAnyOrderElementsOf(SENSITIVE_PATHS + RAW_PATHS)
        omitted.forEach { it.decision.assert().isEqualTo(BiScriptMappingDecision.OMITTED) }
        omitted.single { it.path == "password" }.message.assert().contains("CONFIDENTIAL")
    }

    @Test
    fun `should leave out the raw JSON of a value that holds a sensitive property`() {
        val plan = StateExpansionPlanner(BiScriptOptions(omitSensitiveFields = true)).plan(sensitiveAggregateMetadata)

        // A nullable object's raw companion and an object array's raw column would carry idCard.
        plan.selectedPaths().assert().doesNotContainAnyElementsOf(RAW_PATHS)
        // Their plain children are still expanded.
        plan.columnPaths().assert().contains("maybeProfile.nickname", "profiles.nickname")
        plan.diagnostics.filter { it.path in RAW_PATHS }.forEach {
            it.message.assert().contains("raw JSON")
        }
    }

    @Test
    fun `should omit exactly the properties the query schema masks`() {
        val plan = StateExpansionPlanner(BiScriptOptions(omitSensitiveFields = true)).plan(sensitiveAggregateMetadata)
        val omitted = plan.diagnostics.filter { it.code == BiScriptDiagnosticCode.SENSITIVE_FIELD_OMITTED }
            .map { it.path } - RAW_PATHS

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

        val profilesArray = "'profiles') AS \"profiles\""
        expanded.assert().contains("AS \"password\"", "AS \"profile__id_card\"", profilesArray)
        omitted.assert().doesNotContain("AS \"password\"", "AS \"profile__id_card\"", profilesArray)
        omitted.assert().contains("AS \"name\"")
    }

    /**
     * `Customer` and `OrderRef` refer to each other. Deciding whether `Customer` holds a sensitive value cuts the cycle
     * at `OrderRef.customer`; that cut-short answer for `OrderRef` (no) must not be reused for the raw JSON of
     * `primary.lastOrder`, which holds the customer's phone.
     */
    @Test
    fun `should leave out the raw JSON of a value whose sensitivity is only reachable through a cycle`() {
        val plan = StateExpansionPlanner(BiScriptOptions(omitSensitiveFields = true))
            .plan(aggregateMetadata<CyclicSensitiveAggregate, CyclicSensitiveState>())

        plan.selectedPaths().assert().doesNotContain("primary", "primary.lastOrder")
        plan.views.flatMap { it.columns }.none { it.path.endsWith("phone") }.assert().isTrue()
        plan.diagnostics.filter { it.code == BiScriptDiagnosticCode.SENSITIVE_FIELD_OMITTED }.map { it.path }
            .assert().contains("primary.lastOrder")
    }

    private fun StateExpansionPlan.columnPaths(): Set<String> = views.flatMap { it.columns }.map { it.path }.toSet()

    /** The paths of the columns a view outputs (a nested object's own column is only a WITH alias). */
    private fun StateExpansionPlan.selectedPaths(): Set<String> = views.flatMap { it.columns }
        .filter { it.placement == ColumnPlacement.SELECT }.map { it.path }.toSet()

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
        val SENSITIVE_PATHS = setOf(
            "password",
            "email",
            "token",
            "phone",
            "phones",
            "profile.idCard",
            "maybeProfile.idCard",
            "profiles.idCard",
        )
        val RAW_PATHS = setOf("maybeProfile", "profiles")
        val PLAIN_PATHS = setOf("name", "profile.nickname", "maybeProfile.nickname", "profiles.nickname")
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
    val maybeProfile: SensitiveProfile? = null
    val profiles: List<SensitiveProfile> = emptyList()
}

class SensitiveProfile {
    val nickname: String = ""

    @field:Sensitive(SensitivityLevel.CONFIDENTIAL)
    val idCard: String = ""
}

private val sensitiveAggregateMetadata = aggregateMetadata<SensitiveAggregate, SensitiveState>()

@Suppress("UnusedPrivateProperty")
@AggregateRoot
class CyclicSensitiveAggregate(private val state: CyclicSensitiveState)

class CyclicSensitiveState(override val id: String) : Identifier {
    val primary: CyclicCustomer? = null
}

class CyclicCustomer {
    val lastOrder: CyclicOrderRef? = null

    @field:Sensitive(SensitivityLevel.DISPLAY)
    val phone: String = ""
}

class CyclicOrderRef {
    val orderId: String = ""
    val customer: CyclicCustomer? = null
}
