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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.ICountQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.RunningFunction
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.WindowFunction
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.aggregation.GroupWindows
import me.ahoo.wow.query.aggregation.compareWindowKeys
import me.ahoo.wow.query.backend.BackendPage
import me.ahoo.wow.query.backend.CursorPositionCodec
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.schema.AggregationSupport
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.StorageSupport
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.TimeUnit

class BackendWindowsTest {
    private val schema = boundSchemaFixture(
        objectFixture(
            "aggregateId" to scalarFixture(),
            "province" to scalarFixture(),
            "city" to scalarFixture(),
            "amount" to scalarFixture(QueryValueType.INTEGER),
            "createdAt" to scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.MILLISECONDS)),
        ),
    )

    private val province = AggregationGroup.Terms(QueryField("province"), "province")
    private val city = AggregationGroup.Terms(QueryField("city"), "city")
    private val month = AggregationGroup.DateHistogram(QueryField("createdAt"), "month", AggregationDateUnit.MONTH)
    private val gmv = AggregationMetric.Numeric(
        AggregationFunction.SUM,
        AggregationExpression.Field(QueryField("amount")),
        "gmv",
    )

    private val cities = listOf(
        cityRow("zj", "hz", 60),
        cityRow("zj", "nb", 30),
        cityRow("zj", "wz", 10),
        cityRow("js", "nj", 50),
        cityRow("js", "sz", 50),
        cityRow("js", "xz", null),
    )

    @Test
    fun `share and rank within a province are computed over every group before HAVING and the limit`() {
        runTest {
            val backend = RecordingBackend { cities.map { it.deepCopy() }.asFlow() }
            val query = AggregationQuery(
                groupBy = listOf(province, city),
                metrics = listOf(
                    gmv,
                    derived(
                        "share",
                        DerivedExpression.Binary(
                            AggregationExpressionOperator.DIVIDE,
                            DerivedExpression.MetricRef("gmv"),
                            DerivedExpression.Total("gmv", listOf("province")),
                        ),
                    ),
                    derived("rank", DerivedExpression.Rank("gmv", listOf("province"))),
                ),
                having = HavingExpression.Condition("rank", ComparisonOperator.LTE, 1.0),
                limit = 2,
            )
            val rows = backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList()

            rows.map { it.toString() }.assert().containsExactly(
                """{"province":"zj","city":"hz","gmv":60,"share":0.6,"rank":1.0}""",
                """{"province":"js","city":"nj","gmv":50,"share":0.5,"rank":1.0}""",
            )
            backend.windows.single().assert().isEqualTo(GroupWindow.All)
            val native = backend.aggregations.single()
            native.metrics.map(AggregationMetric::alias).assert().containsExactly("gmv")
            native.having.assert().isNull()
        }
    }

    @Test
    fun `a null value has no rank and contributes nothing to the total`() {
        runTest {
            val backend = RecordingBackend { cities.map { it.deepCopy() }.asFlow() }
            val query = AggregationQuery(
                groupBy = listOf(province, city),
                metrics = listOf(
                    gmv,
                    derived("total", DerivedExpression.Total("gmv")),
                    derived("rank", DerivedExpression.Rank("gmv", direction = Sort.Direction.ASC)),
                ),
            )
            val rows = backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList()
            rows.map { it["total"].doubleValue() }.distinct().assert().containsExactly(200.0)
            rows.map { it["rank"].takeUnless { node -> node.isNull }?.doubleValue() }
                .assert().containsExactly(4.0, 2.0, 1.0, 3.0, 3.0, null)
        }
    }

    @Test
    fun `a running sum and a moving average follow their order within each partition`() {
        runTest {
            val months = listOf(
                monthRow("zj", 2, 20),
                monthRow("zj", 1, 10),
                monthRow("zj", 3, null),
                monthRow("zj", 4, 40),
                monthRow("js", 1, 5),
            )
            val backend = RecordingBackend { months.asFlow() }
            val query = AggregationQuery(
                groupBy = listOf(province, month),
                metrics = listOf(
                    gmv,
                    derived(
                        "cumulative",
                        DerivedExpression.Running(
                            "gmv",
                            Sort(QueryField("month"), Sort.Direction.ASC),
                            listOf("province")
                        ),
                    ),
                    derived(
                        "moving",
                        DerivedExpression.Running(
                            "gmv",
                            Sort(QueryField("month"), Sort.Direction.ASC),
                            listOf("province"),
                            frame = 2,
                            function = RunningFunction.AVG,
                        ),
                    ),
                ),
            )
            val rows = backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList()
            rows.map { it["cumulative"].doubleValue() }.assert().containsExactly(30.0, 10.0, 30.0, 70.0, 5.0)
            rows.map { it["moving"].takeUnless { node -> node.isNull }?.doubleValue() }
                .assert().containsExactly(15.0, 10.0, 20.0, 40.0, 5.0)
        }
    }

    @Test
    fun `groups tied on the running order follow the remaining group keys`() {
        runTest {
            val backend = RecordingBackend { cities.map { it.deepCopy() }.asFlow() }
            val query = AggregationQuery(
                groupBy = listOf(province, city),
                metrics = listOf(
                    gmv,
                    derived(
                        "cumulative",
                        DerivedExpression.Running("gmv", Sort(QueryField("gmv"), Sort.Direction.DESC)),
                    ),
                ),
            )
            val rows = backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList()
            // 60 (zj/hz), 50 (js/nj), 50 (js/sz), 30, 10, null (sorts last descending).
            rows.map { it["cumulative"].doubleValue() }.assert().containsExactly(
                60.0,
                190.0,
                200.0,
                110.0,
                160.0,
                200.0
            )
        }
    }

    @Test
    fun `a derived metric reading a window is computed by the core and a row-level one by the storage`() {
        runTest {
            val backend = RecordingBackend { cities.map { it.deepCopy().put("double", 0.0) }.asFlow() }
            val query = AggregationQuery(
                groupBy = listOf(province, city),
                metrics = listOf(
                    gmv,
                    derived(
                        "double",
                        DerivedExpression.Binary(
                            AggregationExpressionOperator.MULTIPLY,
                            DerivedExpression.MetricRef("gmv"),
                            DerivedExpression.Constant(2.0),
                        ),
                    ),
                    derived("share", DerivedExpression.Total("gmv")),
                    derived(
                        "percent",
                        DerivedExpression.Binary(
                            AggregationExpressionOperator.DIVIDE,
                            DerivedExpression.MetricRef("gmv"),
                            DerivedExpression.MetricRef("share"),
                        ),
                    ),
                ),
                sort = listOf(Sort(QueryField("percent"), Sort.Direction.DESC)),
                limit = 1,
            )
            val rows = backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList()
            rows.single().toString().assert()
                .isEqualTo("""{"province":"zj","city":"hz","gmv":60,"double":0.0,"share":200.0,"percent":0.3}""")
            val native = backend.aggregations.single()
            native.metrics.map(AggregationMetric::alias).assert().containsExactly("gmv", "double")
            native.sort.assert().isEmpty()
        }
    }

    @Test
    fun `windows run after a residual dense fill`() {
        runTest {
            val backend = RecordingBackend {
                flowOf(json("""{"day":0,"count":2}"""), json("""{"day":172800000,"count":3}"""))
            }
            val day = AggregationGroup.DateHistogram(
                QueryField("createdAt"),
                "day",
                AggregationDateUnit.DAY,
                dense = true
            )
            val query = AggregationQuery(
                groupBy = listOf(day),
                metrics = listOf(
                    AggregationMetric.Count("count"),
                    derived(
                        "cumulative",
                        DerivedExpression.Running("count", Sort(QueryField("day"), Sort.Direction.ASC))
                    ),
                ),
            )
            val residual = schema.withAggregation(AggregationSupport(denseFill = SupportMode.RESIDUAL))
            val rows = backend.aggregate(QueryAdmission.Trusted.aggregate(query, residual)).toList()
            rows.map { it["cumulative"].doubleValue() }.assert().containsExactly(2.0, 2.0, 5.0)
        }
    }

    @Test
    fun `a window is rejected at admission where the storage does not compute it`() {
        val query = AggregationQuery(
            groupBy = listOf(month),
            metrics = listOf(gmv, derived("previous", DerivedExpression.Previous("gmv", "month"))),
        )
        val noPrevious = schema.withAggregation(
            AggregationSupport(windows = AggregationSupport.RESIDUAL_WINDOWS - WindowFunction.PREVIOUS),
        )
        assertThrows<QuerySchemaValidationException> { QueryAdmission.Trusted.aggregate(query, noPrevious) }
            .violation.assert().isEqualTo(QueryViolation.StorageUnsupported("the PREVIOUS window"))
        val total = query.copy(metrics = listOf(gmv, derived("total", DerivedExpression.Total("gmv"))))
        val none = schema.withAggregation(AggregationSupport(windows = emptyMap()))
        assertThrows<QuerySchemaValidationException> { QueryAdmission.Trusted.aggregate(total, none) }
            .violation.assert().isEqualTo(QueryViolation.StorageUnsupported("the TOTAL window"))
    }

    @Test
    fun `native windows are sent down unchanged`() {
        runTest {
            val backend = RecordingBackend { cities.map { it.deepCopy() }.asFlow() }
            val query = AggregationQuery(
                groupBy = listOf(province, city),
                metrics = listOf(gmv, derived("total", DerivedExpression.Total("gmv"))),
                limit = 3,
            )
            val native = schema.withAggregation(
                AggregationSupport(windows = mapOf(WindowFunction.TOTAL to SupportMode.NATIVE))
            )
            val admitted = QueryAdmission.Trusted.aggregate(query, native)
            backend.aggregate(admitted).toList().assert().hasSize(3)
            backend.aggregations.single().assert().isSameAs(admitted.query)
            backend.windows.single().assert().isEqualTo(GroupWindow.First(3))
        }
    }

    @Test
    fun `every window is residual by default`() {
        WindowFunction.entries.forEach { AggregationSupport().window(it).assert().isEqualTo(SupportMode.RESIDUAL) }
        val query = AggregationQuery(
            groupBy = listOf(month),
            metrics = listOf(gmv, derived("previous", DerivedExpression.Previous("gmv", "month"))),
        )
        assertThrows<IllegalStateException> {
            GroupWindows(query, setOf("previous")).apply(listOf(monthRow("zj", 1, 10)))
        }
    }

    @Test
    fun `group keys of different kinds compare by kind`() {
        compareWindowKeys(null, node("1")).assert().isNegative()
        compareWindowKeys(node("1"), node("\"a\"")).assert().isNegative()
        compareWindowKeys(node("1.5"), node("2")).assert().isNegative()
        compareWindowKeys(node("\"b\""), node("\"a\"")).assert().isPositive()
        compareWindowKeys(node("true"), node("false")).assert().isPositive()
        compareWindowKeys(node("[1]"), node("[2]")).assert().isZero()
    }

    private fun derived(alias: String, expression: DerivedExpression) = AggregationMetric.Derived(alias, expression)

    private fun node(text: String): JsonNode = WowJson.mapper.readTree(text)

    private fun json(text: String): ObjectNode = node(text) as ObjectNode

    private fun cityRow(province: String, city: String, gmv: Long?): ObjectNode =
        json("""{"province":"$province","city":"$city","gmv":$gmv}""")

    private fun monthRow(province: String, month: Long, gmv: Long?): ObjectNode =
        json("""{"province":"$province","month":$month,"gmv":$gmv}""")

    private fun QueryModelSchema.withAggregation(aggregation: AggregationSupport) =
        QueryModelSchema(
            model,
            capabilities,
            definition,
            bindings,
            fullProjectionAvailable,
            approximateMetrics,
            StorageSupport(aggregation = aggregation),
        )

    private class RecordingBackend(private val groups: () -> Flow<ObjectNode>) : QueryBackend {
        override val aggregateType: AggregateType = AggregateType("context", "aggregate")
        override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON
        val windows = mutableListOf<GroupWindow>()
        val aggregations = mutableListOf<AggregationQuery>()

        override fun stream(query: AdmittedQuery<IListQuery>): Flow<ObjectNode> = emptyFlow()

        override suspend fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): BackendPage =
            BackendPage(emptyList())

        override suspend fun count(query: AdmittedQuery<ICountQuery>): Long = 0

        override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flow<ObjectNode> = flow {
            aggregations += query.query
            windows += window
            emitAll(groups())
        }
    }
}
