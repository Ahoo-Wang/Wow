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

package me.ahoo.wow.query.schema

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryDeprecation
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.QueryBudget
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode

class QueryFieldAliasesTest {
    private val schema = boundSchemaFixture(
        objectFixture(
            "aggregateId" to scalarFixture(),
            "state" to objectFixture(
                "buyer" to aliased(objectFixture("name" to scalarFixture()), "state.customer"),
                "amount" to aliased(scalarFixture(QueryValueType.DECIMAL), "state.total", "state.sum"),
                "placedAt" to scalarFixture(QueryValueType.INTEGER, Temporal.Epoch()),
                "lines" to arrayFixture(
                    objectFixture("sku" to aliased(scalarFixture(), "state.lines.code"), "qty" to scalarFixture()),
                ),
                "secret" to aliased(scalarFixture(mask = MaskRule(SensitivityLevel.CONFIDENTIAL)), "state.hidden"),
                "legacy" to QueryValueSchema(
                    QueryValueKind.SCALAR,
                    valueTypes = setOf(QueryValueType.STRING),
                    deprecated = QueryDeprecation("Use state.buyer."),
                ),
            ),
        ),
        capabilities = setOf(QueryCapability.FULL_TEXT_TERMS),
    )

    @Test
    fun `aliases name their canonical fields, with paths below an alias`() {
        schema.hasAliases.assert().isTrue()
        schema.definition.canonical(QueryField("state.customer")).assert().isEqualTo(QueryField("state.buyer"))
        schema.definition.canonical(QueryField("state.customer.name")).assert()
            .isEqualTo(QueryField("state.buyer.name"))
        schema.definition.canonical(QueryField("state.sum")).assert().isEqualTo(QueryField("state.amount"))
        schema.definition.canonical(QueryField("state.amount")).assert().isEqualTo(QueryField("state.amount"))
        schema.definition.canonical(QueryField("state.other")).assert().isEqualTo(QueryField("state.other"))
    }

    @Test
    fun `admission replaces aliases in filters, sorts and projections`() {
        val query = ListQuery(
            filter = AndFilter(
                listOf(
                    EqualFilter(QueryField("state.customer.name"), json("Ann")),
                    ElementMatchFilter(QueryField("state.lines"), EqualFilter(QueryField("code"), json("A1"))),
                    SearchFilter("x", setOf(QueryField("state.customer.name"))),
                    TodayFilter(QueryField("state.placedAt")),
                ),
            ),
            projection = Projection(include = listOf(QueryField("state.total"))),
            sort = listOf(Sort(QueryField("state.sum"), Sort.Direction.DESC)),
        )

        val admitted = QueryAdmission.Trusted.list(query, schema).query

        val operands = (admitted.filter as AndFilter).operands
        (operands[0] as EqualFilter).field.assert().isEqualTo(QueryField("state.buyer.name"))
        ((operands[1] as ElementMatchFilter).predicate as EqualFilter).field.assert().isEqualTo(QueryField("sku"))
        (operands[2] as SearchFilter).fields.assert().containsExactly(QueryField("state.buyer.name"))
        admitted.projection.include.assert().containsExactly(QueryField("state.amount"))
        admitted.sort.single().field.assert().isEqualTo(QueryField("state.amount"))
    }

    @Test
    fun `cursor sort uniqueness and protection are decided by canonical names`() {
        assertThrows<IllegalArgumentException> {
            QueryAdmission.Trusted.cursor(
                CursorQuery(
                    MatchAllFilter,
                    sort = listOf(
                        Sort(QueryField("state.total"), Sort.Direction.ASC),
                        Sort(QueryField("state.amount"), Sort.Direction.ASC),
                    ),
                ),
                schema,
            )
        }
        assertThrows<QuerySchemaValidationException> {
            QueryAdmission.Trusted.count(EqualFilter(QueryField("state.hidden"), json("x")), schema)
        }.violation.assert().isEqualTo(QueryViolation.ProtectedComparison(QueryField("state.secret")))
    }

    @Test
    fun `admission replaces aliases in aggregations`() {
        val query = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("state.customer.name"), "buyer")),
            metrics = listOf(
                AggregationMetric.Numeric(
                    me.ahoo.wow.api.query.AggregationFunction.SUM,
                    AggregationExpression.Field(QueryField("state.total")),
                    "total",
                ),
            ),
        )

        val admitted = QueryAdmission.Trusted.aggregate(query, schema).query

        admitted.groupBy.single().field.assert().isEqualTo(QueryField("state.buyer.name"))
        ((admitted.metrics.single() as AggregationMetric.Numeric).expression as AggregationExpression.Field)
            .field.assert().isEqualTo(QueryField("state.amount"))
        admitted.groupBy.single().alias.assert().isEqualTo("buyer")
    }

    @Test
    fun `a schema without aliases returns queries unchanged`() {
        val plain = boundSchemaFixture(objectFixture("state" to objectFixture("name" to scalarFixture())))
        val query = ListQuery(EqualFilter(QueryField("state.name"), json("x")))

        plain.hasAliases.assert().isFalse()
        query.withCanonicalFields(plain).assert().isSameAs(query)
    }

    @Test
    fun `the descriptor lists aliases and deprecation under the canonical field`() {
        val fields = schema.describe(QueryBudget.HTTP_DEFAULT, 100).fields.associateBy { it.path }

        fields.getValue("state.amount").aliases.assert().containsExactly("state.sum", "state.total")
        fields.getValue("state.buyer").aliases.assert().containsExactly("state.customer")
        fields.getValue("state.legacy").deprecated.assert().isEqualTo(QueryDeprecation("Use state.buyer."))
        fields.getValue("state.buyer").deprecated.assert().isNull()
        fields.keys.assert().doesNotContain("state.customer", "state.total")
    }

    @Test
    fun `aliases must not name another field or two fields`() {
        listOf(
            objectFixture("state" to objectFixture("a" to aliased(scalarFixture(), "state.b"), "b" to scalarFixture())),
            objectFixture(
                "state" to objectFixture(
                    "a" to aliased(scalarFixture(), "state.old"),
                    "b" to aliased(scalarFixture(), "state.old"),
                ),
            ),
            objectFixture(
                "state" to objectFixture(
                    "map" to QueryValueSchema(
                        QueryValueKind.OBJECT,
                        additionalProperties = aliased(scalarFixture(), "state.old"),
                    ),
                ),
            ),
        ).forEach { root ->
            assertThrows<QuerySchemaConflictException> { LogicalQuerySchema(root) }
        }
    }

    private fun aliased(value: QueryValueSchema, vararg aliases: String) = QueryValueSchema(
        kind = value.kind,
        valueTypes = value.valueTypes,
        properties = value.properties,
        semanticType = value.semanticType,
        maskRule = value.maskRule,
        aliases = aliases.map(::QueryField).toSet(),
    )

    private fun json(value: Any?): JsonNode = JsonSerializer.valueToTree(value)
}
