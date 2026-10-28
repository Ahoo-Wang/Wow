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
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DateDiffUnit
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.query.admission.QueryAdmission
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QueryFieldAliasesTest {
    private val schema = boundSchemaFixture(
        objectFixture(
            "aggregateId" to scalarFixture(),
            "state" to objectFixture(
                "buyer" to aliased(objectFixture("name" to scalarFixture()), "state.customer"),
                "amount" to aliased(scalarFixture(QueryValueType.DECIMAL), "state.total", "state.sum"),
                "placedAt" to scalarFixture(QueryValueType.INTEGER, Temporal.Epoch()),
                "paidAt" to aliased(scalarFixture(QueryValueType.INTEGER, Temporal.Epoch()), "state.payTime"),
                "lines" to arrayFixture(
                    objectFixture("sku" to aliased(scalarFixture(), "state.lines.code"), "qty" to scalarFixture()),
                ),
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
        schema.definition.canonical(QueryField("code"), QueryField("state.lines")).assert().isEqualTo(QueryField("sku"))
        schema.definition.canonical(QueryField("qty"), QueryField("state.lines")).assert().isEqualTo(QueryField("qty"))
        schema.definition.canonical(QueryField("state.sum"), null).assert().isEqualTo(QueryField("state.amount"))
    }

    @Test
    fun `the logical definition lists aliases and deprecations by canonical field`() {
        schema.definition.aliases.assert().isEqualTo(
            mapOf(
                QueryField("state.customer") to QueryField("state.buyer"),
                QueryField("state.total") to QueryField("state.amount"),
                QueryField("state.sum") to QueryField("state.amount"),
                QueryField("state.payTime") to QueryField("state.paidAt"),
                QueryField("state.lines.code") to QueryField("state.lines.sku"),
            ),
        )
        schema.definition.deprecations.assert()
            .isEqualTo(mapOf(QueryField("state.legacy") to QueryDeprecation("Use state.buyer.")))
        schema.field(QueryField("state.customer")).assert().isNull()
    }

    @Test
    fun `admission replaces aliases in filters, sorts and projections`() {
        val query = ListQuery(
            filter = AndFilter(
                listOf(
                    EqualFilter(QueryField("state.customer.name"), "Ann"),
                    ElementMatchFilter(QueryField("state.lines"), EqualFilter(QueryField("code"), "A1")),
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
    fun `cursor sort uniqueness is decided by canonical names`() {
        assertThrows<RuntimeException> {
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
    }

    @Test
    fun `admission replaces aliases in aggregations`() {
        val query = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("state.customer.name"), "buyer")),
            metrics = listOf(
                AggregationMetric.Numeric(
                    AggregationFunction.SUM,
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

    @Suppress("LongMethod")
    @Test
    fun `a filter evaluated without admission has every aliased field replaced`() {
        val filter = AndFilter(
            listOf(
                OrFilter(listOf(EqualFilter(QueryField("state.sum"), 1), IsNullFilter(QueryField("state.customer")))),
                NorFilter(listOf(InFilter(QueryField("state.total"), listOf(1, 2)))),
                ElementMatchFilter(QueryField("state.lines"), EqualFilter(QueryField("code"), "A1")),
                SearchFilter("x", setOf(QueryField("state.customer.name"))),
                ExpressionFilter(
                    AggregationExpression.Binary(
                        AggregationExpressionOperator.ADD,
                        AggregationExpression.Field(QueryField("state.sum")),
                        AggregationExpression.Constant(1.0),
                    ),
                    ComparisonOperator.GT,
                    10.0,
                ),
                ExpressionFilter(
                    AggregationExpression.DateDiff(
                        QueryField("state.payTime"),
                        QueryField("state.placedAt"),
                        DateDiffUnit.HOUR
                    ),
                    ComparisonOperator.LT,
                    24.0,
                ),
                MatchAllFilter,
                MatchNoneFilter,
                AggregateIdFilter("a"),
                TenantIdFilter("t"),
                DeletionFilter(DeletionState.ACTIVE),
            ),
        )

        val canonical = filter.withCanonicalFields(schema) as AndFilter

        canonical.assert().isEqualTo(
            AndFilter(
                listOf(
                    OrFilter(
                        listOf(EqualFilter(QueryField("state.amount"), 1), IsNullFilter(QueryField("state.buyer")))
                    ),
                    NorFilter(listOf(InFilter(QueryField("state.amount"), listOf(1, 2)))),
                    ElementMatchFilter(QueryField("state.lines"), EqualFilter(QueryField("sku"), "A1")),
                    SearchFilter("x", setOf(QueryField("state.buyer.name"))),
                    ExpressionFilter(
                        AggregationExpression.Binary(
                            AggregationExpressionOperator.ADD,
                            AggregationExpression.Field(QueryField("state.amount")),
                            AggregationExpression.Constant(1.0),
                        ),
                        ComparisonOperator.GT,
                        10.0,
                    ),
                    ExpressionFilter(
                        AggregationExpression.DateDiff(
                            QueryField("state.paidAt"),
                            QueryField("state.placedAt"),
                            DateDiffUnit.HOUR
                        ),
                        ComparisonOperator.LT,
                        24.0,
                    ),
                    MatchAllFilter,
                    MatchNoneFilter,
                    AggregateIdFilter("a"),
                    TenantIdFilter("t"),
                    DeletionFilter(DeletionState.ACTIVE),
                ),
            ),
        )
        canonical.operands.drop(6).zip(filter.operands.drop(6)).forEach { (after, before) ->
            after.assert().isSameAs(before)
        }
    }

    @Test
    fun `a schema without aliases returns queries unchanged`() {
        val plain = boundSchemaFixture(objectFixture("state" to objectFixture("name" to scalarFixture())))
        val query = ListQuery(EqualFilter(QueryField("state.name"), "x"))

        plain.hasAliases.assert().isFalse()
        QueryAdmission.Trusted.list(query, plain).query.filter.assert().isEqualTo(query.filter)
        val filter: FilterExpression = EqualFilter(QueryField("state.name"), "x")
        filter.withCanonicalFields(plain).assert().isSameAs(filter)
        plain.definition.canonical(QueryField("state.name")).assert().isEqualTo(QueryField("state.name"))
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
            objectFixture(
                "state" to objectFixture(
                    "map" to QueryValueSchema(
                        QueryValueKind.OBJECT,
                        additionalProperties = QueryValueSchema(
                            QueryValueKind.SCALAR,
                            valueTypes = setOf(QueryValueType.STRING),
                            deprecated = QueryDeprecation(),
                        ),
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
        aliases = aliases.map(::QueryField).toSet(),
    )
}
