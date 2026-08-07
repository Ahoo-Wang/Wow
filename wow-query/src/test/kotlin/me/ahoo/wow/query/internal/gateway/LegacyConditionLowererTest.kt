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

package me.ahoo.wow.query.internal.gateway

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi
import me.ahoo.wow.query.gateway.QueryElementPathMode
import me.ahoo.wow.query.gateway.QueryLegacyDialect
import me.ahoo.wow.query.gateway.QueryMatchScopeMode
import me.ahoo.wow.query.internal.normalization.JunctionOperator
import me.ahoo.wow.query.internal.normalization.LogicalField
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.normalization.NormalizedDeletionScope
import me.ahoo.wow.query.internal.normalization.NormalizedValue
import me.ahoo.wow.query.internal.normalization.PathBasis
import me.ahoo.wow.query.internal.normalization.PredicateOperator
import me.ahoo.wow.query.internal.normalization.SearchScopeId
import me.ahoo.wow.query.internal.plan.PlannedCondition
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import org.junit.jupiter.api.Test
import java.util.function.Consumer

@OptIn(ExperimentalQueryGatewayApi::class)
class LegacyConditionLowererTest {
    @Test
    fun `should lower deletion as a direct guard-neutralizing clause`() {
        val user = predicate(rootPath("state", "status"), "PAID")

        val defaultActive = mongoLowerer.lower(user, NormalizedDeletionScope.DEFAULT_ACTIVE, PlannedCondition.All)
        defaultActive.matchNone.assert().isFalse()
        defaultActive.condition.operator.assert().isEqualTo(Operator.AND)
        defaultActive.condition.children.map { it.operator }.assert().containsExactly(Operator.DELETED, Operator.EQ)
        defaultActive.condition.children.first().deletionState().assert().isEqualTo(DeletionState.ACTIVE)

        val explicit = mongoLowerer.lower(user, NormalizedDeletionScope.EXPLICIT, PlannedCondition.All)
        explicit.condition.children.first().deletionState().assert().isEqualTo(DeletionState.ALL)
    }

    @Test
    fun `should render nested fields relative for Mongo and root-qualified for Elasticsearch`() {
        val nested = NormalizedCondition.ElementMatch(
            rootPath("items"),
            NormalizedCondition.Junction(
                JunctionOperator.AND,
                listOf(
                    predicate(elementPath("sku"), "sku-1"),
                    NormalizedCondition.ElementMatch(
                        elementPath("attributes"),
                        predicate(elementPath("code"), "size"),
                    ),
                ),
            ),
        )

        val mongo = mongoLowerer.lower(nested, NormalizedDeletionScope.DEFAULT_ACTIVE, PlannedCondition.All)
        val mongoElement = mongo.condition.children[1]
        mongoElement.field.assert().isEqualTo("items")
        mongoElement.children.first().children[0].field.assert().isEqualTo("sku")
        mongoElement.children.first().children[1].field.assert().isEqualTo("attributes")
        mongoElement.children.first().children[1].children.first().field.assert().isEqualTo("code")

        val elasticsearch = elasticsearchLowerer.lower(
            nested,
            NormalizedDeletionScope.DEFAULT_ACTIVE,
            PlannedCondition.All,
        )
        val elasticsearchElement = elasticsearch.condition.children[1]
        elasticsearchElement.field.assert().isEqualTo("items")
        elasticsearchElement.children.first().children[0].field.assert().isEqualTo("items.sku")
        elasticsearchElement.children.first().children[1].field.assert().isEqualTo("items.attributes")
        elasticsearchElement.children.first().children[1].children.first().field.assert()
            .isEqualTo("items.attributes.code")
    }

    @Test
    fun `should short-circuit normalized none without relying on backend empty-list behavior`() {
        val lowered = mongoLowerer.lower(
            NormalizedCondition.None,
            NormalizedDeletionScope.DEFAULT_ACTIVE,
            PlannedCondition.All,
        )

        lowered.matchNone.assert().isTrue()
        lowered.condition.children.first().deletionState().assert().isEqualTo(DeletionState.ACTIVE)
    }

    @Test
    fun `unsupported mandatory lowering should fail closed as access denied`() {
        assertThrownBy<QueryRejectedException> {
            mongoLowerer.lower(
                NormalizedCondition.All,
                NormalizedDeletionScope.DEFAULT_ACTIVE,
                PlannedCondition.Search(SearchScopeId("search"), "text"),
            )
        }.satisfies(
            Consumer { error ->
                error.rejection.category.assert().isEqualTo(QueryRejectionCategory.ACCESS_DENIED)
                error.rejection.path.toString().assert().isEqualTo("$.constraints.mandatoryCondition")
                error.rejection.code.assert().isEqualTo(QueryRejectionCode.MANDATORY_CONDITION_UNENFORCEABLE)
            },
        )
    }

    private fun predicate(field: LogicalField, value: String): NormalizedCondition.Predicate =
        NormalizedCondition.Predicate(field, PredicateOperator.EQ, NormalizedValue.Text(value))

    private fun rootPath(vararg segments: String): LogicalField.Path =
        LogicalField.Path(segments.asList(), PathBasis.ROOT)

    private fun elementPath(vararg segments: String): LogicalField.Path =
        LogicalField.Path(segments.asList(), PathBasis.CURRENT_ELEMENT)

    private val mongoLowerer = LegacyConditionLowerer(
        QueryLegacyDialect(QueryElementPathMode.CURRENT_ELEMENT_RELATIVE, QueryMatchScopeMode.DOCUMENT),
    )
    private val elasticsearchLowerer = LegacyConditionLowerer(
        QueryLegacyDialect(QueryElementPathMode.ROOT_QUALIFIED, QueryMatchScopeMode.FIELD),
    )
}
