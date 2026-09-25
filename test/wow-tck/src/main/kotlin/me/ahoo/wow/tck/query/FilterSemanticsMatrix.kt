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

package me.ahoo.wow.tck.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.spec.FilterSemantics
import me.ahoo.wow.api.query.spec.SemanticCase
import me.ahoo.wow.api.query.spec.SemanticProbe
import me.ahoo.wow.api.query.spec.SemanticShape
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicTest
import reactor.core.publisher.Flux
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * Runs the semantic matrix ([FilterSemantics]) against a snapshot backend: one stored record per probe, one dynamic
 * test per case, each asserting exactly which probes the case's filter selects.
 */
internal object FilterSemanticsMatrix {
    /** The state field (under `state`) each shape's probes are stored in. */
    val FIELDS: Map<SemanticShape, String> = mapOf(
        SemanticShape.STRING to "data",
        SemanticShape.NUMBER to "createdAt",
        SemanticShape.STRING_ARRAY to "labels",
    )

    /** `state.labels` is not a property of the mock state; the TCK declares it so array cases have a field. */
    val LABELS_DECLARATION: Pair<QueryField, QueryFieldDeclaration> = QueryField("state.labels") to QueryFieldDeclaration(
        kind = DeclarationValue.Set(QueryValueKind.ARRAY),
        nullable = DeclarationValue.Set(true),
        items = DeclarationValue.Set(
            QueryFieldDeclaration(
                kind = DeclarationValue.Set(QueryValueKind.SCALAR),
                valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
            ),
        ),
    )

    fun probeId(probe: SemanticProbe): String = "semantic-${probe.shape.name.lowercase()}-${probe.name}"

    fun seed(save: (List<String>) -> Unit, write: (aggregateId: String, stateField: String, value: JsonNode?) -> Unit) {
        val probes = SemanticProbe.ALL.values.flatten()
        save(probes.map(::probeId))
        probes.forEach { probe -> write(probeId(probe), checkNotNull(FIELDS[probe.shape]), probe.value) }
    }

    fun tests(
        divergences: Set<String>,
        list: (ListQuery) -> Flux<ObjectNode>,
    ): List<DynamicTest> = FilterSemantics.CASES.map { case ->
        DynamicTest.dynamicTest(case.id) { run(case, divergences, list) }
    }

    private fun run(case: SemanticCase, divergences: Set<String>, list: (ListQuery) -> Flux<ObjectNode>) {
        Assumptions.assumeFalse(case.id in divergences) {
            "Known divergence from the canonical semantics: ${case.id}."
        }
        val probes = checkNotNull(SemanticProbe.ALL[case.shape])
        val field = QueryField("state.${FIELDS[case.shape]}")
        val filter = AndFilter(listOf(AggregateIdsFilter(probes.map(::probeId)), case.filter(field)))
        val selected = list(ListQuery(filter, limit = probes.size + 1))
            .map { it.path("aggregateId").asString() }
            .collectList()
            .block()
            .orEmpty()
        val byId = probes.associateBy(::probeId)
        selected.map { checkNotNull(byId[it]) { "Unexpected record [$it]." }.name }.toSet()
            .assert().describedAs(case.id).isEqualTo(case.matches)
    }
}
