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

package me.ahoo.wow.elasticsearch.query

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationGroupScope
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DateDiffUnit
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SearchMode
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.elasticsearch.ElasticsearchFixture
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotStore
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.aggregate
import me.ahoo.wow.query.list
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.tck.QueryBackendSpec
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/**
 * The query features of the Elasticsearch backend beyond the TCK's: the aggregation metrics, groups and expressions
 * its compilers translate natively (runtime fields, bucket scripts, top hits, date parts), and the string and search
 * filters, over five orders.
 */
class ElasticsearchQueryFeaturesTest {
    private val type = AggregateType(ElasticsearchFixture.newContext(), "order")
    private val queries = ElasticsearchSnapshotQueries(ElasticsearchFixture.client, ElasticsearchFixture.names)
    private val backend get() = queries.binding(type).backend

    private fun order(id: String, state: String, time: Long, scope: Scope = Scope("owner")): SnapshotRecord =
        SnapshotRecord.of(
            ref = type.ref("tenant", id),
            version = 1,
            schemaFingerprint = "it",
            state = state,
            scope = scope,
            firstEventTime = T0,
            lastEventTime = time,
        )

    private val schema: QueryModelSchema by lazy {
        runBlocking {
            val store = ElasticsearchFixture.store().snapshots(type.context) as ElasticsearchSnapshotStore
            store.createIndex(type.name)
            val context = QuerySchemaContext(type, QueryModel.SNAPSHOT)
            val provider = DefaultQueryModelSchemaProvider(
                context,
                listOf(BeanQuerySchemaSource(listOf(QuerySchemaRegistration(context, QueryBackendSpec.DECLARATION)))),
                queries.binding(type).storage,
            )
            provider.schema()
            listOf(
                order(
                    "o1",
                    """{"name":"Alpha One","status":"PAID","amount":10.5,"quantity":1,"createdAt":$T0,""" +
                        """"keywords":["a","b"],"lines":[{"sku":"A","price":5.0,"quantity":1},{"sku":"B","price":5.5,"quantity":2}]}""",
                    T0,
                ),
                order(
                    "o2",
                    """{"name":"Beta Two","status":"PAID","amount":20,"quantity":2,"createdAt":${T0 + DAY},""" +
                        """"keywords":["b"],"lines":[{"sku":"A","price":10,"quantity":2}]}""",
                    T0 + DAY,
                ),
                order(
                    "o3",
                    """{"name":"Gamma Three","status":"SHIPPED","amount":30.25,"quantity":3,"createdAt":${T0 + 2 * DAY},""" +
                        """"keywords":["c"],"lines":[{"sku":"C","price":30.25,"quantity":1}]}""",
                    T0 + 2 * DAY,
                ),
                order(
                    "o4",
                    """{"name":"Delta Four","status":"CANCELLED","amount":40,"quantity":4,"createdAt":${T0 + 3 * DAY},"lines":[]}""",
                    T0 + 3 * DAY,
                ),
                order(
                    "o5",
                    """{"name":"Epsilon Five","amount":50,"quantity":5,"createdAt":${T0 + 4 * DAY},""" +
                        """"lines":[{"sku":"B","price":10,"quantity":5}]}""",
                    T0 + 4 * DAY,
                ),
            ).forEach { store.save(it) }
            provider.schema()
        }
    }

    private fun aggregate(query: AggregationQuery): List<ObjectNode> = runBlocking {
        backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList()
    }

    private fun ids(filter: FilterExpression): List<String> = runBlocking {
        backend.list(QueryAdmission.Trusted.list(ListQuery(filter, sort = listOf(Sort(QueryField("aggregateId"), Sort.Direction.ASC)), limit = 100), schema))
            .toList().map { it.path("aggregateId").stringValue() }
    }

    @Test
    fun `summary metrics, deviation, variance, percentile, any, first, last, distinct and derived`() {
        val row = aggregate(
            AggregationQuery(
                metrics = listOf(
                    AggregationMetric.Count("count"),
                    AggregationMetric.Numeric(AggregationFunction.SUM, AMOUNT, "sum"),
                    AggregationMetric.Numeric(AggregationFunction.STDDEV, QUANTITY, "stddev"),
                    AggregationMetric.Numeric(AggregationFunction.VARIANCE, QUANTITY, "variance"),
                    AggregationMetric.Percentile(QUANTITY, 50.0, "median"),
                    AggregationMetric.Any(QueryField("state.status"), "anyStatus", EqualFilter(QueryField("state.status"), "SHIPPED")),
                    AggregationMetric.First(QueryField("state.quantity"), "first", QueryField("state.createdAt")),
                    AggregationMetric.Last(QueryField("state.quantity"), "last", QueryField("state.createdAt")),
                    AggregationMetric.DistinctCount(AggregationExpression.Field(QueryField("state.status")), "statuses"),
                    AggregationMetric.DistinctCount(
                        AggregationExpression.Field(QueryField("state.status")),
                        "paidStatuses",
                        EqualFilter(QueryField("state.status"), "PAID"),
                    ),
                    AggregationMetric.Derived(
                        "average",
                        DerivedExpression.Binary(
                            AggregationExpressionOperator.DIVIDE,
                            DerivedExpression.MetricRef("sum"),
                            DerivedExpression.MetricRef("count"),
                        ),
                    ),
                ),
            ),
        ).single()
        row.path("count").longValue().assert().isEqualTo(5L)
        row.path("sum").doubleValue().assert().isEqualTo(150.75)
        row.path("stddev").doubleValue().assert().isBetween(1.41, 1.42)
        row.path("variance").doubleValue().assert().isEqualTo(2.0)
        row.path("median").doubleValue().assert().isEqualTo(3.0)
        row.path("anyStatus").stringValue().assert().isEqualTo("SHIPPED")
        row.path("first").longValue().assert().isEqualTo(1L)
        row.path("last").longValue().assert().isEqualTo(5L)
        row.path("statuses").longValue().assert().isEqualTo(3L)
        row.path("paidStatuses").longValue().assert().isEqualTo(1L)
        row.path("average").doubleValue().assert().isEqualTo(30.15)
    }

    @Test
    fun `expression metrics, groups and filters run as runtime fields and scripts`() {
        val total = AggregationExpression.Binary(AggregationExpressionOperator.MULTIPLY, AMOUNT, QUANTITY)
        val row = aggregate(
            AggregationQuery(
                metrics = listOf(
                    AggregationMetric.Numeric(AggregationFunction.SUM, total, "revenue"),
                    AggregationMetric.Numeric(
                        AggregationFunction.MAX,
                        AggregationExpression.DateDiff(QueryField("state.createdAt"), QueryField("lastEventTime"), DateDiffUnit.DAY),
                        "lag",
                    ),
                    AggregationMetric.Numeric(
                        AggregationFunction.MIN,
                        AggregationExpression.Binary(
                            AggregationExpressionOperator.SUBTRACT,
                            AggregationExpression.Binary(AggregationExpressionOperator.ADD, AMOUNT, AggregationExpression.Constant(1.0)),
                            AggregationExpression.Binary(AggregationExpressionOperator.DIVIDE, QUANTITY, AggregationExpression.Constant(2.0)),
                        ),
                        "shifted",
                    ),
                    AggregationMetric.Percentile(total, 90.0, "p90"),
                    AggregationMetric.DistinctCount(total, "distinctTotals"),
                ),
            ),
        ).single()
        row.path("revenue").doubleValue().assert().isEqualTo(10.5 + 40 + 90.75 + 160 + 250)
        row.path("lag").doubleValue().assert().isEqualTo(0.0)
        row.path("shifted").doubleValue().assert().isEqualTo(11.0)
        row.path("distinctTotals").longValue().assert().isEqualTo(5L)
        val byTotal = aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Histogram(alias = "bucket", interval = 100.0, expression = total)),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        )
        byTotal.map { it.path("count").longValue() }.sum().assert().isEqualTo(5L)
        val byTerm = aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(alias = "doubled", expression = AggregationExpression.Binary(AggregationExpressionOperator.MULTIPLY, QUANTITY, AggregationExpression.Constant(2.0)))),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        )
        byTerm.size.assert().isEqualTo(5)
        ids(ExpressionFilter(total, ComparisonOperator.GT, 100.0)).assert().containsExactly("o4", "o5")
        ids(ExpressionFilter(total, ComparisonOperator.LTE, 40.0)).assert().containsExactly("o1", "o2")
    }

    @Test
    fun `groups by histogram, date histogram, date part and missing key, sorted by a metric`() {
        aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Histogram(AMOUNT_FIELD, "amount", 20.0)),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        ).map { it.path("amount").doubleValue() to it.path("count").longValue() }
            .assert().containsExactly(0.0 to 1L, 20.0 to 2L, 40.0 to 2L)
        aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.DateHistogram(QueryField("state.createdAt"), "week", AggregationDateUnit.WEEK, "Asia/Shanghai")),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        ).map { it.path("count").longValue() }.sum().assert().isEqualTo(5L)
        aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.DatePart(QueryField("state.createdAt"), "hour", AggregationDatePart.HOUR_OF_DAY)),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        ).single().path("count").longValue().assert().isEqualTo(5L)
        aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.DatePart(QueryField("state.createdAt"), "weekday", AggregationDatePart.DAY_OF_WEEK, dense = true)),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        ).size.assert().isEqualTo(7)
        aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status", missingKey = "NONE")),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort(QueryField("count"), Sort.Direction.DESC)),
                limit = 2,
            ),
        ).map { it.path("status").stringValue() to it.path("count").longValue() }
            .assert().containsExactly("PAID" to 2L, "CANCELLED" to 1L)
        aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status")),
                metrics = listOf(
                    AggregationMetric.Count("count"),
                    AggregationMetric.DistinctCount(AggregationExpression.Field(QueryField("state.name")), "names", GreaterThanFilter(AMOUNT_FIELD, 15)),
                ),
                sort = listOf(Sort(QueryField("names"), Sort.Direction.DESC)),
                limit = 1,
            ),
        ).single().path("names").longValue().assert().isEqualTo(1L)
    }

    @Test
    fun `element groups under root groups, with element filters and metric filters`() {
        val rows = aggregate(
            AggregationQuery(
                elements = listOf(AggregationElement(QueryField("state.lines"), GreaterThanFilter(QueryField("price"), 5))),
                groupBy = listOf(
                    AggregationGroup.DateHistogram(QueryField("state.createdAt"), "day", AggregationDateUnit.DAY, scope = AggregationGroupScope.ROOT),
                    AggregationGroup.Terms(QueryField("sku"), "sku"),
                ),
                metrics = listOf(
                    AggregationMetric.Count("lines"),
                    AggregationMetric.Numeric(AggregationFunction.AVG, AggregationExpression.Field(QueryField("price")), "price"),
                    AggregationMetric.Count("many", GreaterThanFilter(QueryField("quantity"), 1)),
                ),
            ),
        )
        rows.map { it.path("sku").stringValue() }.assert().containsExactly("B", "A", "C", "B")
        rows.map { it.path("many").longValue() }.assert().containsExactly(1L, 1L, 0L, 1L)
        val summary = aggregate(
            AggregationQuery(
                elements = listOf(AggregationElement(QueryField("state.lines"))),
                groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status", scope = AggregationGroupScope.ROOT)),
                metrics = listOf(
                    AggregationMetric.Numeric(AggregationFunction.SUM, AggregationExpression.Field(QueryField("quantity")), "quantity"),
                    AggregationMetric.Derived("double", DerivedExpression.Binary(AggregationExpressionOperator.MULTIPLY, DerivedExpression.MetricRef("quantity"), DerivedExpression.Constant(2.0))),
                ),
                limit = 2,
            ),
        )
        summary.map { it.path("status").stringValue() to it.path("double").doubleValue() }
            .assert().containsExactly("PAID" to 10.0, "SHIPPED" to 2.0)
    }

    @Test
    fun `string, search, array and element filters`() {
        ids(ContainsFilter(QueryField("state.name"), "eta")).assert().containsExactly("o2")
        ids(ContainsFilter(QueryField("state.name"), "ALPHA", StringComparison.CASE_INSENSITIVE)).assert().containsExactly("o1")
        ids(StartsWithFilter(QueryField("state.name"), "Gam")).assert().containsExactly("o3")
        ids(EndsWithFilter(QueryField("state.name"), "Four")).assert().containsExactly("o4")
        ids(NotEqualFilter(QueryField("state.status"), "PAID")).assert().containsExactly("o3", "o4", "o5")
        ids(ContainsAllFilter(QueryField("state.keywords"), listOf("a", "b"))).assert().containsExactly("o1")
        ids(SearchFilter("gamma")).assert().containsExactly("o3")
        ids(SearchFilter("delta four", setOf(QueryField("state.name")), SearchMode.PHRASE)).assert().containsExactly("o4")
        ids(
            ElementMatchFilter(QueryField("state.lines"), EqualFilter(QueryField("sku"), "B")),
        ).assert().containsExactly("o1", "o5")
        ids(EqualFilter(QueryField("state.createdAt"), T0)).assert().containsExactly("o1")
        ids(MatchAllFilter).size.assert().isEqualTo(5)
    }

    @Test
    fun `any and terms read keys of every native type, and a percentile of no value is null`() {
        val row = aggregate(
            AggregationQuery(
                metrics = listOf(
                    AggregationMetric.Any(QueryField("state.quantity"), "anyQuantity", EqualFilter(QueryField("state.quantity"), 3)),
                    AggregationMetric.Any(AMOUNT_FIELD, "anyAmount", EqualFilter(QueryField("state.quantity"), 3)),
                    AggregationMetric.Any(QueryField("state.status"), "anyNone", EqualFilter(QueryField("state.status"), "NONE")),
                    AggregationMetric.Percentile(QUANTITY, 50.0, "noMedian", EqualFilter(QueryField("state.status"), "NONE")),
                    AggregationMetric.First(QueryField("state.name"), "noFirst", QueryField("state.createdAt"), EqualFilter(QueryField("state.status"), "NONE")),
                ),
            ),
        ).single()
        row.path("anyQuantity").longValue().assert().isEqualTo(3L)
        row.path("anyAmount").doubleValue().assert().isEqualTo(30.25)
        row.path("anyNone").isNull.assert().isTrue()
        row.path("noMedian").isNull.assert().isTrue()
        row.path("noFirst").isNull.assert().isTrue()
        aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(AMOUNT_FIELD, "amount"), AggregationGroup.Terms(QueryField("state.quantity"), "quantity")),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        ).map { it.path("amount").doubleValue() }.assert().containsExactly(10.5, 20.0, 30.25, 40.0, 50.0)
    }

    private companion object {
        const val T0 = 1_700_000_000_000L
        const val DAY = 86_400_000L
        val AMOUNT_FIELD = QueryField("state.amount")
        val AMOUNT = AggregationExpression.Field(AMOUNT_FIELD)
        val QUANTITY = AggregationExpression.Field(QueryField("state.quantity"))
    }
}
