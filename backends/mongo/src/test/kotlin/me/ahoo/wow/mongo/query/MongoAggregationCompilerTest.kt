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

package me.ahoo.wow.mongo.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DateDiffUnit
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RunningFunction
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.mongo.query.pipeline.MongoAggregationCompiler
import me.ahoo.wow.query.admission.QueryAdmission
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test

class MongoAggregationCompilerTest {
    private val schema = MongoQueryTestSchemas.SCHEMA

    private fun pipeline(query: AggregationQuery, limit: Int? = query.limit): List<Bson> {
        val admitted = QueryAdmission.Trusted.aggregate(query, schema)
        return MongoAggregationCompiler.compile(admitted, limit)
    }

    private fun json(query: AggregationQuery): String = pipeline(
        query
    ).joinToString("\n") { it.toBsonDocument().toJson() }

    @Test
    fun `terms group by the value, null without one, and a missing key is a literal`() {
        val compiled = json(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(STATUS, "status", missingKey = "\$name")),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        )
        compiled.assert().contains(""""${'$'}ifNull": ["${'$'}state.status", {"${'$'}literal": "${'$'}name"}]""")
        // No record is left out of a terms group.
        pipeline(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(STATUS, "status")),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        ).map { it.toBsonDocument().firstKey }.assert()
            .containsExactly("\$match", "\$group", "\$project", "\$sort", "\$limit")
    }

    @Test
    fun `terms over an array unwind each distinct value first, keeping a record without one`() {
        val stages = pipeline(
            AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.Terms(STATUS, "status"),
                    AggregationGroup.Terms(KEYWORDS, "keyword"),
                ),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        ).map { it.toBsonDocument() }
        stages.map { it.firstKey }.assert()
            .containsExactly("\$match", "\$set", "\$unwind", "\$group", "\$project", "\$sort", "\$limit")
        // Only the multi-valued group (index 1) is unwound; its values are distinct and never null.
        stages[1].toJson().assert().contains("__wow_terms_1").contains("\$setUnion").contains("\$filter")
        stages[2].toJson().assert().contains(""""path": "${'$'}__wow_terms_1"""")
            .contains(""""preserveNullAndEmptyArrays": true""")
        stages[3].toJson().assert().contains(""""keyword": {"${'$'}ifNull": ["${'$'}__wow_terms_1", null]}""")
            .contains(""""status": {"${'$'}ifNull": ["${'$'}state.status", null]}""")
    }

    @Test
    fun `a histogram puts what is no number in the null bucket`() {
        val compiled = json(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Histogram(AMOUNT, "bucket", 10.0)),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        )
        compiled.assert().contains(""""${'$'}cond": [{"${'$'}isNumber": "${'$'}${'$'}value"}""")
    }

    @Test
    fun `date groups decode an epoch and leave out records without one`() {
        val compiled = json(
            AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.DateHistogram(CREATED, "day", AggregationDateUnit.DAY, "Asia/Shanghai"),
                    AggregationGroup.DatePart(CREATED, "weekday", AggregationDatePart.DAY_OF_WEEK, "+05:30"),
                ),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        )
        compiled.assert().contains(""""${'$'}dateTrunc": {"date": """)
        compiled.assert().contains(""""timezone": "Asia/Shanghai"""")
        compiled.assert().contains(""""${'$'}isoDayOfWeek": {"date": """)
        compiled.assert().contains(""""timezone": "+05:30"""")
        compiled.lines()[1].assert().startsWith("""{"${'$'}match": {"${'$'}and": [{"${'$'}expr": {"${'$'}ne": """)
    }

    @Test
    fun `a dense date histogram densifies the bucket index`() {
        val compiled = json(
            AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.DateHistogram(CREATED, "hour", AggregationDateUnit.HOUR, dense = true)
                ),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        )
        compiled.assert().contains(""""${'$'}densify": {"field": "hour"""")
        compiled.assert().contains(""""${'$'}dateFromParts"""")
    }

    @Test
    fun `FIRST and LAST break ties by the aggregate ID and the tenant`() {
        val compiled = json(
            AggregationQuery(
                metrics = listOf(
                    AggregationMetric.First(NAME, "first", orderBy = CREATED),
                    AggregationMetric.Last(
                        NAME,
                        "last",
                        orderBy = QueryField("aggregateId"),
                        filter = EqualFilter(STATUS, "\$x")
                    ),
                ),
            ),
        )
        compiled.assert().contains(
            """"${'$'}top": {"sortBy": {"__wow_edge_0": -1, "state.createdAt": 1, "aggregate_id": 1, "tenant_id": 1}""",
        )
        compiled.assert().contains(
            """"${'$'}bottom": {"sortBy": {"__wow_edge_1": 1, "aggregate_id": 1, "tenant_id": 1}"""
        )
        // A metric filter's string operand is a literal, not a field path, in expression position.
        compiled.assert().contains("""{"${'$'}literal": "${'$'}x"}""")
    }

    @Test
    fun `metrics, derived metrics, windows and HAVING compile in order`() {
        val stages = pipeline(WINDOWED, limit = 3)
        stages.map { it.toBsonDocument().firstKey }.assert().containsExactly(
            "\$match", "\$group", "\$project",
            "\$setWindowFields", "\$set",
            "\$setWindowFields", "\$set",
            "\$set", "\$setWindowFields", "\$set",
            "\$project", "\$project", "\$project", "\$project",
            "\$unset", "\$match", "\$sort", "\$limit",
        )
        val compiled = stages.joinToString("\n") { it.toBsonDocument().toJson() }
        compiled.assert().contains(""""${'$'}percentile": {"input": """)
        compiled.assert().contains(""""method": "approximate"""")
        compiled.assert().contains(""""${'$'}stdDevPop"""")
        compiled.assert().contains(""""${'$'}addToSet"""")
        compiled.assert().contains(""""${'$'}unset": ["__wow_w0", "__wow_w0_n", "__wow_w0_k"""")
        compiled.assert().contains(""""${'$'}limit": 3""")
    }

    @Test
    fun `elements unwind their container and keep absolute paths`() {
        val compiled = json(
            AggregationQuery(
                elements = listOf(
                    AggregationElement(QueryField("state.lines"), EqualFilter(QueryField("sku"), "A")),
                    AggregationElement(QueryField("parts")),
                ),
                groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "part")),
                metrics = listOf(AggregationMetric.Any(QueryField("name"), "name")),
            ),
        )
        compiled.assert().contains("""{"${'$'}unwind": "${'$'}state.lines"}""")
        compiled.assert().contains("""{"${'$'}match": {"state.lines.sku": "A"}}""")
        compiled.assert().contains("""{"${'$'}unwind": "${'$'}state.lines.parts"}""")
        compiled.assert().contains(""""${'$'}state.lines.parts.name"""")
        val age = json(
            AggregationQuery(
                metrics = listOf(
                    AggregationMetric.Numeric(
                        AggregationFunction.AVG,
                        AggregationExpression.DateDiff(CREATED, QueryField("lastEventTime"), DateDiffUnit.DAY),
                        "age",
                    ),
                ),
            ),
        )
        age.assert().contains(""""${'$'}last_event_time"""")
        age.assert().contains(
            """"${'$'}divide": [{"${'$'}subtract": ["${'$'}${'$'}to", "${'$'}${'$'}from"]}, 8.64E+7]"""
        )
    }

    @Test
    fun `metric filter guards re-express each match shape as an expression`() {
        val compiled = json(
            AggregationQuery(
                metrics = listOf(
                    AggregationMetric.Count("exists", ExistsFilter(NAME)),
                    AggregationMetric.Count("missing", NotExistsFilter(NAME)),
                    AggregationMetric.Count("null", IsNullFilter(NAME)),
                    AggregationMetric.Count("notNull", IsNotNullFilter(NAME)),
                    AggregationMetric.Count("after", GreaterThanFilter(NAME, "m")),
                    AggregationMetric.Count("other", NotInFilter(STATUS, listOf("A"))),
                    AggregationMetric.Count("like", ContainsFilter(NAME, "a", StringComparison.CASE_INSENSITIVE)),
                    AggregationMetric.Count("none", NorFilter(listOf(EqualFilter(STATUS, "A")))),
                    AggregationMetric.Count(
                        "any",
                        OrFilter(listOf(EqualFilter(STATUS, "A"), EqualFilter(STATUS, "B")))
                    ),
                ),
            ),
        )
        compiled.assert().contains(""""${'$'}eq": [{"${'$'}type": "${'$'}state.name"}, {"${'$'}type": "m"}]""")
        compiled.assert().contains(
            """"${'$'}regexMatch": {"input": "${'$'}state.name", "regex": "a", "options": "i"}"""
        )
        compiled.assert().contains(""""${'$'}eq": [{"${'$'}type": "${'$'}state.name"}, "missing"]""")
        compiled.assert().contains(""""${'$'}not": [{"${'$'}in": ["${'$'}state.status", ["A"]]}]""")
        compiled.assert().contains(""""${'$'}not": [{"${'$'}or": [{"${'$'}eq": ["${'$'}state.status", "A"]}]}]""")
    }

    @Test
    fun `epochs of every unit decode to a date, and a zone offset needs whole minutes`() {
        val units = listOf(
            "createdSeconds",
            "createdNanos",
            "createdMinutes",
            "createdHours",
            "createdDays",
            "createdMicros"
        )
        val compiled = json(
            AggregationQuery(
                metrics = units.map { unit ->
                    AggregationMetric.Numeric(
                        AggregationFunction.MAX,
                        AggregationExpression.DateDiff(QueryField("state.$unit"), CREATED, DateDiffUnit.SECOND),
                        unit,
                    )
                },
            ),
        )
        listOf(
            1_000,
            60_000,
            3_600_000,
            86_400_000
        ).forEach { compiled.assert().contains("""["${'$'}${'$'}epoch", $it]""") }
        compiled.assert().contains(""", 1000000]}""")
        val seconds = runCatching {
            pipeline(
                AggregationQuery(
                    groupBy = listOf(
                        AggregationGroup.DateHistogram(CREATED, "day", AggregationDateUnit.DAY, "+05:30:15")
                    ),
                    metrics = listOf(AggregationMetric.Count("count")),
                ),
            )
        }.exceptionOrNull()
        (seconds as me.ahoo.wow.query.schema.QuerySchemaValidationException).violation.assert()
            .isEqualTo(me.ahoo.wow.query.schema.QueryViolation.StorageUnsupported("time zone offsets with seconds"))
    }

    @Test
    fun `no pipeline runs code on the server`() {
        val queries = listOf(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(STATUS, "status", missingKey = "\$where")),
                metrics = listOf(
                    AggregationMetric.Count("count", EqualFilter(NAME, "\$function")),
                    AggregationMetric.Any(NAME, "any", InFilter(NAME, listOf("\$accumulator"))),
                ),
            ),
        )
        queries.forEach { query ->
            MongoMatchCompilerTest.keys(pipeline(query)).filter { it in MongoMatchCompilerTest.CODE_OPERATORS }
                .assert().isEmpty()
        }
    }

    private companion object {
        val NAME = QueryField("state.name")
        val STATUS = QueryField("state.status")
        val KEYWORDS = QueryField("state.keywords")
        val AMOUNT = QueryField("state.amount")
        val QUANTITY = QueryField("state.quantity")
        val CREATED = QueryField("state.createdAt")

        /** Every metric kind, a derived metric over each native window, and every HAVING form. */
        val WINDOWED = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(STATUS, "status")),
            metrics = listOf(
                AggregationMetric.Count("count", NotEqualFilter(QUANTITY, 1)),
                AggregationMetric.Numeric(AggregationFunction.SUM, AggregationExpression.Field(AMOUNT), "total"),
                AggregationMetric.Numeric(
                    AggregationFunction.VARIANCE,
                    AggregationExpression.Field(AMOUNT),
                    "variance"
                ),
                AggregationMetric.Percentile(AggregationExpression.Field(AMOUNT), 90.0, "p90", ExistsFilter(NAME)),
                AggregationMetric.DistinctCount(
                    AggregationExpression.Field(QUANTITY),
                    "quantities",
                    InFilter(STATUS, listOf("A"))
                ),
                AggregationMetric.Any(NAME, "anyName", ContainsFilter(NAME, "a")),
                AggregationMetric.Derived(
                    "share",
                    DerivedExpression.Binary(
                        AggregationExpressionOperator.DIVIDE,
                        DerivedExpression.MetricRef("total"),
                        DerivedExpression.Total("total"),
                    ),
                ),
                AggregationMetric.Derived(
                    "running",
                    DerivedExpression.Running(
                        "total",
                        Sort(QueryField("status"), Sort.Direction.ASC),
                        frame = 2,
                        function = RunningFunction.SUM
                    )
                ),
                AggregationMetric.Derived("rank", DerivedExpression.Rank("total", direction = Sort.Direction.DESC)),
                AggregationMetric.Derived("constant", DerivedExpression.Constant(1.0)),
            ),
            having = HavingExpression.And(
                listOf(
                    HavingExpression.Condition("count", ComparisonOperator.GT, 1.0),
                    HavingExpression.Between("total", 1.0, 2.0),
                    HavingExpression.In("count", listOf(1.0)),
                    HavingExpression.IsNull("share", negated = true),
                    HavingExpression.IsNull("rank"),
                ),
            ),
            sort = listOf(Sort(QueryField("total"), Sort.Direction.DESC)),
            limit = 5,
        )
    }
}
