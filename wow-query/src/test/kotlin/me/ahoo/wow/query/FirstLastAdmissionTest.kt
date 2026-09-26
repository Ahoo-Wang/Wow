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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.schema.AggregationSupport
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.StorageSupport
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.query.schema.arrayFixture
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.describe
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import java.util.concurrent.TimeUnit

class FirstLastAdmissionTest {
    private val epoch = scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.MILLISECONDS))
    private val schema = boundSchemaFixture(
        objectFixture(
            "aggregateId" to scalarFixture(),
            "eventTime" to epoch,
            "state" to objectFixture(
                "price" to scalarFixture(QueryValueType.DECIMAL),
                "status" to scalarFixture(),
                "tags" to arrayFixture(scalarFixture()),
                "lines" to arrayFixture(objectFixture("amount" to scalarFixture(QueryValueType.DECIMAL), "at" to epoch)),
            ),
        ),
    )

    private fun query(vararg metrics: AggregationMetric, elements: List<AggregationElement> = emptyList()) =
        AggregationQuery(
            elements = elements,
            groupBy = if (elements.isEmpty()) {
                listOf(
                    AggregationGroup.Terms(QueryField("state.status"), "status")
                )
            } else {
                emptyList()
            },
            metrics = metrics.toList(),
        )

    @Test
    fun `orderBy defaults to the model's event time at the record level`() {
        val admitted = QueryAdmission.Trusted.aggregate(
            query(
                AggregationMetric.First(QueryField("state.price"), "open"),
                AggregationMetric.Last(QueryField("state.price"), "close", QueryField("state.price")),
            ),
            schema,
        )
        val (first, last) = admitted.query.metrics.map { it as AggregationMetric.Edge }
        admitted.field(checkNotNull(first.orderBy)).logicalField.assert().isEqualTo(QueryField("eventTime"))
        admitted.field(checkNotNull(last.orderBy)).logicalField.assert().isEqualTo(QueryField("state.price"))
        admitted.field(first.field).logicalField.assert().isEqualTo(QueryField("state.price"))
    }

    @Test
    fun `inside an element orderBy must be named and lie in the element`() {
        val lines = listOf(AggregationElement(QueryField("state.lines")))
        assertThrows<QuerySchemaValidationException> {
            QueryAdmission.Trusted.aggregate(
                query(AggregationMetric.First(QueryField("amount"), "first"), elements = lines),
                schema
            )
        }.violation.assert().isEqualTo(QueryViolation.FirstLastRequiresOrderBy(QueryField("first")))

        val admitted = QueryAdmission.Trusted.aggregate(
            query(AggregationMetric.First(QueryField("amount"), "first", QueryField("at")), elements = lines),
            schema,
        )
        val first = admitted.query.metrics.single() as AggregationMetric.First
        admitted.field(checkNotNull(first.orderBy)).logicalField.assert().isEqualTo(QueryField("state.lines.at"))
    }

    @Test
    fun `value and orderBy must each hold one value`() {
        assertThrows<QuerySchemaValidationException> {
            QueryAdmission.Trusted.aggregate(query(AggregationMetric.Last(QueryField("state.tags"), "tag")), schema)
        }.violation.assert().isEqualTo(QueryViolation.FirstLastRequiresSingleValue(QueryField("state.tags")))
        assertThrows<QuerySchemaValidationException> {
            QueryAdmission.Trusted.aggregate(
                query(AggregationMetric.Last(QueryField("state.price"), "p", QueryField("state.tags"))),
                schema,
            )
        }.violation.assert().isEqualTo(QueryViolation.FirstLastRequiresSingleValue(QueryField("state.tags")))
    }

    @Test
    fun `a model without event time needs orderBy`() {
        val custom = boundSchemaFixture(
            objectFixture("id" to scalarFixture(), "price" to scalarFixture(QueryValueType.DECIMAL)),
            model = QueryModel("custom"),
        )
        assertThrows<QuerySchemaValidationException> {
            QueryAdmission.Trusted.aggregate(
                AggregationQuery(metrics = listOf(AggregationMetric.First(QueryField("price"), "first"))),
                custom,
            )
        }.violation.assert().isEqualTo(QueryViolation.FirstLastRequiresOrderBy(QueryField("first")))
    }

    @Test
    fun `derived metrics and HAVING cannot read FIRST or LAST`() {
        val first = AggregationMetric.First(QueryField("state.price"), "open")
        assertThrows<IllegalArgumentException> {
            query(first, AggregationMetric.Derived("double", DerivedExpression.MetricRef("open")))
        }.message.assert().contains("cannot reference FIRST metric")
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status")),
                metrics = listOf(first),
                having = HavingExpression.Condition("open", ComparisonOperator.GT, 1.0),
            )
        }.message.assert().contains("cannot reference FIRST or LAST metric")
    }

    @Test
    fun `a storage without FIRST and LAST rejects them and the descriptor omits them`() {
        val without = QueryModelSchema(
            schema.model,
            schema.capabilities,
            schema.definition,
            schema.bindings,
            storage = StorageSupport(aggregation = AggregationSupport(firstLast = SupportMode.NONE)),
        )
        val admitted = QueryAdmission.Trusted.aggregate(
            query(AggregationMetric.First(QueryField("state.price"), "open")),
            without
        )
        object : QueryBackend by NoOpBackend {}.aggregate(admitted).collectList().let {
            assertThrows<QuerySchemaValidationException> { it.block() }
        }
        without.describe(null, null).analysis.metrics.assert().doesNotContain("FIRST", "LAST")
        without.describe(null, null).analysis.firstLastOrderBy.assert().isNull()

        val described = schema.describe(null, null)
        described.analysis.metrics.assert().contains("FIRST", "LAST")
        described.analysis.having.metrics.assert().doesNotContain("FIRST", "LAST")
        described.analysis.firstLastOrderBy.assert().isEqualTo("eventTime")
        val fields = described.fields.associateBy { it.path }
        fields.getValue("state.price").aggregate!!.firstLast.assert().isTrue()
        fields.getValue("state.tags").aggregate!!.firstLast.assert().isFalse()
    }

    private object NoOpBackend : QueryBackend {
        override val namedAggregate = me.ahoo.wow.modeling.MaterializedNamedAggregate("context", "aggregate")
        override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON
        override fun stream(
            query: AdmittedQuery<me.ahoo.wow.api.query.IListQuery>
        ) = Flux.empty<tools.jackson.databind.node.ObjectNode>()
        override fun page(query: AdmittedQuery<me.ahoo.wow.api.query.Queryable<*>>, window: PageWindow) =
            reactor.core.publisher.Mono.empty<BackendPage>()
        override fun count(query: AdmittedQuery<me.ahoo.wow.api.query.FilterExpression>) =
            reactor.core.publisher.Mono.just(0L)
        override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow) =
            Flux.empty<tools.jackson.databind.node.ObjectNode>()
    }
}
