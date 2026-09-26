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
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.IsNotEmptyStringFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.arrayFixture
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.IntNode
import tools.jackson.databind.node.JsonNodeFactory

class FieldResolverTest {
    private val schema = boundSchemaFixture(
        objectFixture(
            "aggregateId" to scalarFixture(),
            "tenantId" to scalarFixture(),
            "name" to scalarFixture(),
            "alias" to scalarFixture(),
            "price" to scalarFixture(QueryValueType.INTEGER),
            "orders" to arrayFixture(
                objectFixture(
                    "price" to scalarFixture(QueryValueType.INTEGER),
                    "lines" to arrayFixture(objectFixture("qty" to scalarFixture(QueryValueType.INTEGER))),
                ),
            ),
        ),
    )

    @Test
    fun `one reused field instance resolves separately in each element scope`() {
        val price = QueryField("price")
        val admitted = QueryAdmission.Trusted.count(
            AndFilter(
                listOf(
                    EqualFilter(price, IntNode.valueOf(1)),
                    ElementMatchFilter(QueryField("orders"), EqualFilter(price, IntNode.valueOf(1))),
                ),
            ),
            schema,
        )
        val (root, element) = (admitted.query as AndFilter).operands
        val rootPrice = admitted.field((root as EqualFilter).field)
        val container = admitted.field((element as ElementMatchFilter).field)
        val predicate = element.predicate as EqualFilter
        val elementPrice = admitted.field(predicate.field)

        (root.field === price).assert().isFalse()
        (root.field === predicate.field).assert().isFalse()
        rootPrice.logicalField.assert().isEqualTo(QueryField("price"))
        rootPrice.physicalField.assert().isEqualTo(QueryField("native.price"))
        rootPrice.physicalParent.assert().isNull()
        rootPrice.relativePhysicalField.assert().isEqualTo(QueryField("native.price"))
        container.capability.assert().isEqualTo(QueryCapability.ELEMENT_SCOPE)
        container.physicalField.assert().isEqualTo(QueryField("native.orders"))
        elementPrice.logicalField.assert().isEqualTo(QueryField("orders.price"))
        elementPrice.capability.assert().isEqualTo(QueryCapability.EXACT_MATCH)
        elementPrice.physicalParent.assert().isEqualTo(QueryField("native.orders"))
        elementPrice.physicalField.assert().isEqualTo(QueryField("native.orders.price"))
        elementPrice.relativePhysicalField.assert().isEqualTo(QueryField("price"))
        elementPrice.elementAncestors.assert().containsExactly(QueryField("orders"))
    }

    @Test
    fun `lowered operators resolve each fresh node for its own capability`() {
        val admitted = QueryAdmission.Trusted.count(IsNotEmptyStringFilter(QueryField("name")), schema)
        val (present, notEmpty) = (admitted.query as AndFilter).operands
        admitted.field((present as IsNotNullFilter).field).capability.assert().isEqualTo(QueryCapability.PRESENCE)
        admitted.field((notEmpty as NotEqualFilter).field).capability.assert().isEqualTo(QueryCapability.EXACT_MATCH)
    }

    @Test
    fun `system filters resolve by node identity`() {
        val admitted = QueryAdmission.Trusted.count(TenantIdFilter("tenant"), schema)
        val tenant = admitted.systemField(admitted.query)
        tenant.logicalField.assert().isEqualTo(QueryField("tenantId"))
        tenant.physicalField.assert().isEqualTo(QueryField("native.tenantId"))
        assertThrows<IllegalArgumentException> { admitted.systemField(TenantIdFilter("tenant")) }
        assertThrows<IllegalArgumentException> { admitted.field(QueryField("tenantId")) }
    }

    @Test
    fun `sort and projection resolve at the root`() {
        val admitted = QueryAdmission.Trusted.list(
            ListQuery(
                filter = GreaterThanFilter(QueryField("price"), IntNode.valueOf(1)),
                projection = Projection(include = listOf(QueryField("name"))),
                sort = listOf(Sort(QueryField("name"), Sort.Direction.DESC)),
            ),
            schema,
        )
        val query = admitted.query
        admitted.field(query.sort.single().field).run {
            capability.assert().isEqualTo(QueryCapability.SORT)
            physicalField.assert().isEqualTo(QueryField("native.name"))
        }
        admitted.field(query.projection.include.single()).run {
            capability.assert().isNull()
            physicalField.assert().isEqualTo(QueryField("native.name"))
        }
        admitted.field((query.filter as GreaterThanFilter).field).capability.assert().isEqualTo(QueryCapability.RANGE)
    }

    @Test
    fun `cursor resolves the appended tie-breaker and rejects shared physical fields`() {
        val admitted = QueryAdmission.Trusted.cursor(CursorQuery(filter = MatchAllFilter), schema)
        val tieBreaker = admitted.query.sort.single()
        tieBreaker.field.assert().isEqualTo(QueryField("aggregateId"))
        admitted.field(tieBreaker.field).run {
            capability.assert().isEqualTo(QueryCapability.CURSOR_SORT)
            physicalField.assert().isEqualTo(QueryField("native.aggregateId"))
        }

        val shared = QueryPathTemplate(listOf(QueryPathSegment.Property("native"), QueryPathSegment.Property("name")))
        val aliased = schema.rebind(listOf(QueryPathSegment.Property("alias"))) { path, _ ->
            QueryValueBindings(
                QueryCapability.entries.associateWith { QueryFieldBindingTemplate(shared, null) },
                shared,
                path,
            )
        }
        assertThrows<QuerySchemaValidationException> {
            QueryAdmission.Trusted.cursor(
                CursorQuery(
                    filter = MatchAllFilter,
                    sort = listOf(
                        Sort(QueryField("name"), Sort.Direction.ASC),
                        Sort(QueryField("alias"), Sort.Direction.ASC)
                    ),
                ),
                aliased,
            )
        }
    }

    @Test
    fun `a field bound outside its physical element container is rejected`() {
        val outside = QueryPathTemplate(listOf(QueryPathSegment.Property("other"), QueryPathSegment.Property("price")))
        val escaped = schema.rebind(
            listOf(QueryPathSegment.Property("orders"), QueryPathSegment.Item, QueryPathSegment.Property("price")),
        ) { path, _ ->
            QueryValueBindings(
                QueryCapability.entries.associateWith { QueryFieldBindingTemplate(outside, null) },
                outside,
                path,
            )
        }
        assertThrows<QuerySchemaValidationException> {
            QueryAdmission.Trusted.count(
                ElementMatchFilter(QueryField("orders"), EqualFilter(QueryField("price"), IntNode.valueOf(1))),
                escaped,
            )
        }
    }

    @Test
    fun `aggregation references resolve under the innermost element scope`() {
        val admitted = QueryAdmission.Trusted.aggregate(
            AggregationQuery(
                filter = EqualFilter(QueryField("name"), JsonNodeFactory.instance.stringNode("n")),
                elements = listOf(
                    AggregationElement(
                        QueryField("orders"),
                        GreaterThanFilter(QueryField("price"), IntNode.valueOf(0))
                    ),
                    AggregationElement(QueryField("lines")),
                ),
                groupBy = listOf(AggregationGroup.Terms(QueryField("qty"), "qty")),
                metrics = listOf(
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Binary(
                            AggregationExpressionOperator.MULTIPLY,
                            AggregationExpression.Field(QueryField("qty")),
                            AggregationExpression.Constant(2.0),
                        ),
                        "total",
                        filter = GreaterThanFilter(QueryField("qty"), IntNode.valueOf(1)),
                    ),
                    AggregationMetric.DistinctCount(AggregationExpression.Field(QueryField("qty")), "distinct"),
                    AggregationMetric.Any(QueryField("qty"), "any"),
                ),
            ),
            schema,
        )
        val query = admitted.query
        val (orders, lines) = query.elements
        admitted.field(orders.path).physicalField.assert().isEqualTo(QueryField("native.orders"))
        admitted.field((orders.filter as GreaterThanFilter).field).run {
            logicalField.assert().isEqualTo(QueryField("orders.price"))
            physicalParent.assert().isEqualTo(QueryField("native.orders"))
        }
        admitted.field(lines.path).run {
            logicalField.assert().isEqualTo(QueryField("orders.lines"))
            physicalParent.assert().isEqualTo(QueryField("native.orders"))
            physicalField.assert().isEqualTo(QueryField("native.orders.lines"))
        }
        admitted.field(checkNotNull(query.groupBy.single().field)).run {
            capability.assert().isEqualTo(QueryCapability.AGGREGATE_TERMS)
            physicalField.assert().isEqualTo(QueryField("native.orders.lines.qty"))
        }
        val (total, distinct, any) = query.metrics
        total as AggregationMetric.Numeric
        val left = (total.expression as AggregationExpression.Binary).left as AggregationExpression.Field
        admitted.field(left.field).capability.assert().isEqualTo(QueryCapability.AGGREGATE_NUMERIC)
        admitted.field((total.filter as GreaterThanFilter).field).physicalParent
            .assert().isEqualTo(QueryField("native.orders.lines"))
        distinct as AggregationMetric.DistinctCount
        admitted.field((distinct.expression as AggregationExpression.Field).field).capability
            .assert().isEqualTo(QueryCapability.AGGREGATE_TERMS)
        admitted.field((any as AggregationMetric.Any).field).capability
            .assert().isEqualTo(QueryCapability.AGGREGATE_TERMS)
    }

    @Test
    fun `a storage that cannot sort parallel arrays rejects two independent array sort fields`() {
        val arrays = boundSchemaFixture(
            objectFixture(
                "aggregateId" to scalarFixture(),
                "tags" to arrayFixture(scalarFixture()),
                "codes" to arrayFixture(scalarFixture()),
            ),
        )
        val parallel = QueryModelSchema(
            arrays.model,
            arrays.capabilities,
            arrays.definition,
            arrays.bindings,
            storage = me.ahoo.wow.query.schema.StorageSupport(
                parallelArraySort = me.ahoo.wow.query.schema.SupportMode.NONE,
            ),
        )
        fun sort(vararg fields: String) =
            ListQuery(MatchAllFilter, sort = fields.map { Sort(QueryField(it), Sort.Direction.ASC) })

        QueryAdmission.Trusted.list(sort("tags", "codes"), arrays).query.sort.assert().hasSize(2)
        QueryAdmission.Trusted.list(sort("tags", "aggregateId"), parallel).query.sort.assert().hasSize(2)
        assertThrows<QuerySchemaValidationException> { QueryAdmission.Trusted.list(sort("tags", "codes"), parallel) }
            .violation.assert().isEqualTo(
                me.ahoo.wow.query.schema.QueryViolation.ParallelArraySort(QueryField("codes"), QueryField("tags")),
            )
    }

    @Test
    fun `a storage that cannot compare arrays rejects an array equality operand`() {
        val tags = boundSchemaFixture(
            objectFixture(
                "aggregateId" to scalarFixture(),
                "tags" to arrayFixture(scalarFixture()),
            ),
        )
        val scalarOnly = QueryModelSchema(
            tags.model,
            tags.capabilities,
            tags.definition,
            tags.bindings,
            storage = me.ahoo.wow.query.schema.StorageSupport(
                arrayEquality = me.ahoo.wow.query.schema.SupportMode.NONE,
            ),
        )
        val array = me.ahoo.wow.serialization.JsonSerializer.valueToTree<tools.jackson.databind.JsonNode>(
            listOf("a", "b"),
        )
        val scalar = me.ahoo.wow.serialization.JsonSerializer.valueToTree<tools.jackson.databind.JsonNode>("a")
        val field = QueryField("tags")

        QueryAdmission.Trusted.count(
            EqualFilter(field, array),
            tags
        ).query.assert().isInstanceOf(EqualFilter::class.java)
        QueryAdmission.Trusted.count(EqualFilter(field, scalar), scalarOnly).query.assert()
            .isInstanceOf(EqualFilter::class.java)
        listOf(
            EqualFilter(field, array),
            NotEqualFilter(field, array),
            EqualFilter(field, tools.jackson.databind.node.JsonNodeFactory.instance.pojoNode(listOf("a", "b"))),
        ).forEach { filter ->
            assertThrows<QuerySchemaValidationException> { QueryAdmission.Trusted.count(filter, scalarOnly) }
                .violation.assert().isEqualTo(me.ahoo.wow.query.schema.QueryViolation.ArrayEquality(field))
        }
    }

    private fun QueryModelSchema.rebind(
        segments: List<QueryPathSegment>,
        bindings: (QueryPathTemplate, QueryValueBindings) -> QueryValueBindings,
    ) = QueryModelSchema(
        model,
        capabilities,
        definition,
        this.bindings.mapValues { (path, current) ->
            if (path.segments == segments) bindings(path, current) else current
        },
    )
}
