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
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SearchMode
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.QueryExecutionException
import me.ahoo.wow.query.QueryRequestException
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryViolation
import org.bson.BsonDocument
import org.bson.BsonValue
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MongoMatchCompilerTest {
    private val schema = MongoQueryTestSchemas.SCHEMA

    private fun compile(filter: FilterExpression, schema: QueryModelSchema = this.schema): BsonDocument {
        val admitted = QueryAdmission.Trusted.list(ListQuery(filter), schema)
        return MongoMatchCompiler.compile(admitted.query.filter, admitted).toBsonDocument()
    }

    private fun json(filter: FilterExpression): String = compile(filter).toJson()

    @Test
    fun `system filters read the stored field names`() {
        json(TenantIdFilter("t")).assert().isEqualTo("""{"tenant_id": "t"}""")
        json(OwnerIdFilter("o")).assert().isEqualTo("""{"owner_id": "o"}""")
        json(SpaceIdFilter("s")).assert().isEqualTo("""{"space_id": "s"}""")
        json(IdFilter("a")).assert().isEqualTo("""{"aggregate_id": "a"}""")
        json(AggregateIdsFilter(listOf("a", "b"))).assert().isEqualTo("""{"aggregate_id": {"${'$'}in": ["a", "b"]}}""")
        json(DeletionFilter(DeletionState.ACTIVE)).assert().isEqualTo("""{"deleted": false}""")
        json(DeletionFilter(DeletionState.DELETED)).assert().isEqualTo("""{"deleted": true}""")
        json(DeletionFilter(DeletionState.ALL)).assert().isEqualTo("{}")
        json(EqualFilter(QueryField("firstEventTime"), 1)).assert().isEqualTo("""{"first_event_time": 1}""")
        json(GreaterThanFilter(QueryField("lastEventTime"), 1)).assert()
            .isEqualTo("""{"last_event_time": {"${'$'}gt": 1}}""")
        json(EqualFilter(QueryField("tags.region"), "east")).assert().isEqualTo("""{"tags.region": "east"}""")
        json(MatchAllFilter).assert().isEqualTo("{}")
        json(MatchNoneFilter).assert().isEqualTo("""{"_id": {"${'$'}in": []}}""")
    }

    @Test
    fun `field operators compile to their MongoDB match`() {
        val name = QueryField("state.name")
        val quantity = QueryField("state.quantity")
        json(NotEqualFilter(name, "a")).assert().isEqualTo("""{"state.name": {"${'$'}ne": "a"}}""")
        json(InFilter(quantity, listOf(1, 2))).assert().isEqualTo("""{"state.quantity": {"${'$'}in": [1, 2]}}""")
        json(NotInFilter(quantity, listOf(1))).assert().isEqualTo("""{"state.quantity": {"${'$'}nin": [1]}}""")
        json(BetweenFilter(quantity, 1, 3)).assert()
            .isEqualTo(
                """{"${'$'}and": [{"state.quantity": {"${'$'}gte": 1}}, {"state.quantity": {"${'$'}lte": 3}}]}"""
            )
        json(LessThanOrEqualFilter(quantity, 1)).assert().isEqualTo("""{"state.quantity": {"${'$'}lte": 1}}""")
        json(ContainsAllFilter(QueryField("state.keywords"), listOf("a", "b"))).assert()
            .isEqualTo("""{"state.keywords": {"${'$'}all": ["a", "b"]}}""")
        json(
            IsEmptyFilter(QueryField("state.keywords"))
        ).assert().isEqualTo("""{"state.keywords": {"${'$'}size": 0}}""")
        json(IsNullFilter(name)).assert().isEqualTo("""{"state.name": null}""")
        json(IsNotNullFilter(name)).assert().isEqualTo("""{"state.name": {"${'$'}ne": null}}""")
        json(ExistsFilter(name)).assert().isEqualTo("""{"state.name": {"${'$'}exists": true}}""")
        json(NotExistsFilter(name)).assert().isEqualTo("""{"state.name": {"${'$'}exists": false}}""")
        json(OrFilter(listOf(EqualFilter(name, "a"), EqualFilter(name, "b")))).assert()
            .isEqualTo("""{"${'$'}or": [{"state.name": "a"}, {"state.name": "b"}]}""")
        json(NorFilter(listOf(EqualFilter(name, "a")))).assert().isEqualTo("""{"${'$'}nor": [{"state.name": "a"}]}""")
        json(AndFilter(listOf(TenantIdFilter("t"), EqualFilter(name, "a")))).assert()
            .isEqualTo("""{"${'$'}and": [{"tenant_id": "t"}, {"state.name": "a"}]}""")
    }

    @Test
    fun `a decimal operand is the Decimal128 of its digits, an integer stays one`() {
        val amount = QueryField("state.amount")
        json(EqualFilter(amount, 0.1)).assert().isEqualTo("""{"state.amount": {"${'$'}numberDecimal": "0.1"}}""")
        json(EqualFilter(amount, BigDecimal("0.100"))).assert()
            .isEqualTo("""{"state.amount": {"${'$'}numberDecimal": "0.100"}}""")
        json(GreaterThanFilter(amount, 2)).assert().isEqualTo("""{"state.amount": {"${'$'}gt": 2}}""")
        json(InFilter(amount, listOf(1.5, 2L))).assert()
            .isEqualTo("""{"state.amount": {"${'$'}in": [{"${'$'}numberDecimal": "1.5"}, 2]}}""")
        MongoMatchCompiler.stored(java.math.BigInteger.TWO.pow(70)).toString().assert()
            .isEqualTo("1180591620717411303424")
        MongoMatchCompiler.stored(java.math.BigInteger.TEN).assert().isEqualTo(10L)
        MongoMatchCompiler.stored(1.5f).toString().assert().isEqualTo("1.5")
        violation { compile(EqualFilter(amount, BigDecimal("1." + "1".repeat(40)))) }
            .assert().isEqualTo(QueryViolation.StorageUnsupported("numeric operands beyond 34 significant digits"))
    }

    @Test
    fun `string matches escape every regex metacharacter`() {
        val name = QueryField("state.name")
        json(ContainsFilter(name, ".*(a|b)[c]{2}^$\\+?")).assert().isEqualTo(
            """{"state.name": {"${'$'}regularExpression": {"pattern": "\\.\\*\\(a\\|b\\)\\[c\\]\\{2\\}\\^\\${'$'}\\\\\\+\\?", "options": ""}}}""",
        )
        json(StartsWithFilter(name, "a", StringComparison.CASE_INSENSITIVE)).assert()
            .isEqualTo("""{"state.name": {"${'$'}regularExpression": {"pattern": "^a", "options": "i"}}}""")
        json(EndsWithFilter(name, "a")).assert()
            .isEqualTo("""{"state.name": {"${'$'}regularExpression": {"pattern": "a${'$'}", "options": ""}}}""")
    }

    @Test
    fun `an element match also reads a single object where the array is`() {
        val lines = QueryField("state.lines")
        json(ElementMatchFilter(lines, EqualFilter(QueryField("sku"), "A"))).assert().isEqualTo(
            """{"${'$'}or": [{"state.lines": {"${'$'}elemMatch": {"sku": "A"}}}, """ +
                """{"${'$'}and": [{"state.lines": {"${'$'}type": 3}}, {"state.lines": {"${'$'}not": {"${'$'}type": 4}}}, """ +
                """{"state.lines.sku": "A"}]}]}""",
        )
        val nested = json(
            ElementMatchFilter(lines, ElementMatchFilter(QueryField("parts"), EqualFilter(QueryField("name"), "nut"))),
        )
        // Inside the element the paths are relative; inside a single object they continue its path.
        nested.assert().contains(""""parts": {"${'$'}elemMatch": {"name": "nut"}}""")
        nested.assert().contains(""""parts.name": "nut"""")
        nested.assert().contains(""""state.lines.parts": {"${'$'}elemMatch": {"name": "nut"}}""")
        nested.assert().contains(""""state.lines.parts.name": "nut"""")
    }

    @Test
    fun `an expression compares a computed value that exists`() {
        val expression = AggregationExpression.Binary(
            AggregationExpressionOperator.MULTIPLY,
            AggregationExpression.Field(QueryField("state.amount")),
            AggregationExpression.Constant(2.0),
        )
        val compiled = json(ExpressionFilter(expression, ComparisonOperator.GTE, 10.0))
        compiled.assert().startsWith("""{"${'$'}expr": {"${'$'}let": {"vars": {"value": """)
        compiled.assert().contains(""""${'$'}gte": ["${'$'}${'$'}value", 10.0]""")
        compiled.assert().contains(""""${'$'}state.amount"""")
    }

    @Test
    fun `operands never become operators or code`() {
        val name = QueryField("state.name")
        val hostile = listOf(
            "\$where",
            "\$function",
            "this.a == 1 || true",
            "'; return true; var x='",
            """{"${'$'}gt": ""}""",
            "\$\$ROOT",
            "\u0000",
        )
        val filters = hostile.flatMap { value ->
            listOf(
                EqualFilter(name, value),
                NotEqualFilter(name, value),
                InFilter(name, listOf(value)),
                GreaterThanFilter(name, value),
                ContainsFilter(name, value),
                StartsWithFilter(name, value, StringComparison.CASE_INSENSITIVE),
                EqualFilter(QueryField("tags.region"), value),
                ElementMatchFilter(QueryField("state.lines"), EqualFilter(QueryField("sku"), value)),
            )
        }
        filters.forEach { filter ->
            val compiled = compile(filter)
            operators(compiled).filter { it in CODE_OPERATORS }.assert().describedAs("$filter").isEmpty()
            // Every value stays a value: no key of the match is an operand.
            keys(compiled).none { key -> hostile.any { key == it } }.assert().describedAs("$filter").isTrue()
        }
    }

    @Test
    fun `a path MongoDB cannot address never reaches a document`() {
        listOf("\$where", "state.\$gt", "state..name", "", "state.", "a\u0000b", "\$").forEach { path ->
            assertThrownBy<QueryExecutionException> { MongoSnapshotDocuments.checked(path) }
        }
        MongoSnapshotDocuments.checked("state.lines.sku").assert().isEqualTo("state.lines.sku")
        MongoSnapshotDocuments.checked("tags.@type").assert().isEqualTo("tags.@type")
    }

    @Test
    fun `full-text search runs once, outside NOR, and a phrase has no quotes`() {
        val text = MongoQueryTestSchemas.schema(
            MongoQueryTestSchemas.adapter(
                indexes = listOf(Document("name", "text").append("key", Document("state.name", "text")))
            ),
        )
        compile(SearchFilter("wow"), text).toJson().assert()
            .isEqualTo("""{"${'$'}text": {"${'$'}search": "wow"}}""")
        compile(SearchFilter("wow now", mode = SearchMode.PHRASE), text).toJson().assert()
            .isEqualTo("""{"${'$'}text": {"${'$'}search": "\"wow now\""}}""")
        violation { compile(SearchFilter("say \"wow\"", mode = SearchMode.PHRASE), text) }.assert()
            .isEqualTo(QueryViolation.StorageUnsupported("double quotes in a PHRASE search query"))
        val twice = QueryViolation.StorageUnsupported("more than one search filter, or a search filter beneath NOR")
        violation { compile(AndFilter(listOf(SearchFilter("a"), SearchFilter("b"))), text) }.assert().isEqualTo(twice)
        violation { compile(NorFilter(listOf(SearchFilter("a"))), text) }.assert().isEqualTo(twice)
    }

    private fun violation(block: () -> Unit): QueryViolation = when (val error = runCatching(block).exceptionOrNull()) {
        is QueryRequestException -> checkNotNull(error.violation)
        is QuerySchemaValidationException -> checkNotNull(error.violation)
        else -> throw AssertionError("Expected a query violation, not $error.")
    }

    companion object {
        /** The operators that run code on the server; no compiled filter or pipeline may hold one. */
        val CODE_OPERATORS = setOf("\$where", "\$function", "\$accumulator")

        /** Every key of [value] and its descendants that names an operator. */
        fun operators(value: BsonValue): List<String> = keys(value).filter { it.startsWith("$") }

        fun keys(value: BsonValue): List<String> = when {
            value.isDocument -> value.asDocument().flatMap { (key, child) -> listOf(key) + keys(child) }
            value.isArray -> value.asArray().flatMap(::keys)
            else -> emptyList()
        }

        fun keys(pipeline: List<Bson>): List<String> = pipeline.flatMap { keys(it.toBsonDocument()) }
    }
}
