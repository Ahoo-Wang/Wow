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
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.mongo.query.aggregation.MongoAggregationCompiler
import me.ahoo.wow.mongo.query.event.EventStreamFilterCompiler
import me.ahoo.wow.mongo.query.schema.MongoQuerySchemaAdapter
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueSchema
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class MongoNativeConstraintsTest {
    private val epoch = QueryValueSchema(
        QueryValueKind.SCALAR,
        valueTypes = setOf(QueryValueType.INTEGER),
        semanticType = Temporal.Epoch(TimeUnit.MILLISECONDS),
    )
    private val compiler = MongoAggregationCompiler(EventStreamFilterCompiler)
    private val temporalCapability = setOf(QueryCapability.AGGREGATE_TEMPORAL)

    @Test
    fun `nullable epoch and nullable array items retain date histogram representation`() {
        val nullableEpoch = QueryValueSchema(
            QueryValueKind.UNION,
            alternatives = listOf(epoch, QueryValueSchema(QueryValueKind.NULL)),
        )
        listOf(nullableEpoch, QueryValueSchema(QueryValueKind.ARRAY, items = nullableEpoch)).forEach { value ->
            val pipeline = compiler.compile(
                histogram(),
                mongoTestSchema(
                    fields = mapOf(QueryField("epoch") to MongoTestField(value, temporalCapability, "stored"))
                ),
            )
            val date = pipeline.single { it.toBsonDocument().containsKey("\$group") }.toBsonDocument()
                .getDocument("\$group").getDocument("_id").getDocument("day")
                .getDocument("\$toLong").getDocument("\$dateTrunc").getDocument("date")
            date.toJson().assert().contains("\$stored").contains("\$arrayElemAt").contains("\$isNumber")
        }
    }

    @Test
    fun `unknown mixed or nested array temporal domains are rejected`() {
        val otherUnit = QueryValueSchema(
            QueryValueKind.SCALAR,
            valueTypes = setOf(QueryValueType.INTEGER),
            semanticType = Temporal.Epoch(TimeUnit.SECONDS),
        )
        listOf(QueryValueSchema(QueryValueKind.UNKNOWN), otherUnit, mongoScalar()).forEach { alternative ->
            val value = QueryValueSchema(QueryValueKind.UNION, alternatives = listOf(epoch, alternative))
            assertThrows<QuerySchemaValidationException> {
                compiler.compile(histogram(), temporalSchema(value))
            }
        }
        assertThrows<QuerySchemaValidationException> {
            compiler.compile(
                histogram(),
                temporalSchema(
                    QueryValueSchema(
                        QueryValueKind.ARRAY,
                        items = QueryValueSchema(QueryValueKind.ARRAY, items = epoch),
                    )
                )
            )
        }
    }

    @Test
    fun `schema adapter withholds temporal capability for conflicting epoch units`() {
        val seconds = QueryValueSchema(
            QueryValueKind.SCALAR,
            valueTypes = setOf(QueryValueType.INTEGER),
            semanticType = Temporal.Epoch(TimeUnit.SECONDS),
        )
        val value = QueryValueSchema(QueryValueKind.UNION, alternatives = listOf(epoch, seconds))
        val schema = MongoQuerySchemaAdapter.bind(
            mongoLogicalSchema(mapOf(QueryField("epoch") to value)),
            emptyList(),
            null,
        )
        schema.field(QueryField("epoch"))!!.capabilities.contains(QueryCapability.AGGREGATE_TEMPORAL)
            .assert().isFalse()
    }

    @Test
    fun `fixed offset prefixes normalize and supported zones retain native spelling`() {
        mapOf(
            "UTC+08:00" to "+08:00",
            "GMT+08:00" to "+08:00",
            "UTC" to "UTC",
            "Z" to "UTC",
            "+08:00" to "+08:00",
            "Asia/Shanghai" to "Asia/Shanghai",
        ).forEach { (zone, expected) ->
            val group = compiler.compile(histogram(zone), temporalSchema(epoch))
                .single { it.toBsonDocument().containsKey("\$group") }.toBsonDocument().getDocument("\$group")
            group.getDocument("_id").getDocument("day").getDocument("\$toLong")
                .getDocument("\$dateTrunc").getString("timezone").value.assert().isEqualTo(expected)
        }
    }

    @Test
    fun `fixed offsets with seconds fail before native execution`() {
        listOf("+08:00:01", "UTC-00:00:30").forEach { zone ->
            assertThrows<QuerySchemaValidationException> { compiler.compile(histogram(zone), temporalSchema(epoch)) }
        }
    }

    @Test
    fun `multiple text expressions and text nested beneath nor are rejected in find and aggregation`() {
        val first = SearchFilter("alpha")
        val second = SearchFilter("beta")
        listOf(
            AndFilter(listOf(first, second)),
            OrFilter(listOf(first, second)),
            NorFilter(listOf(first)),
            NorFilter(listOf(AndFilter(listOf(first, filter { "name" eq "alpha" })))),
        ).forEach { filter ->
            assertThrows<QuerySchemaValidationException> { EventStreamFilterCompiler.compile(filter, searchSchema()) }
            assertThrows<QuerySchemaValidationException> {
                compiler.compile(
                    aggregation {
                        filter(filter)
                        count("count")
                    },
                    searchSchema()
                )
            }
        }
    }

    @Test
    fun `single text expression under indexed or preserves the native filter`() {
        val expression: FilterExpression = OrFilter(listOf(SearchFilter("alpha"), filter { "name" eq "alpha" }))
        val native = EventStreamFilterCompiler.compile(expression, searchSchema()).toBsonDocument()
        native.getArray("\$or").assert().hasSize(2)
        compiler.compile(
            aggregation {
                filter(expression)
                count("count")
            },
            searchSchema()
        )
            .first().toBsonDocument().getDocument("\$match").assert().isEqualTo(native)
    }

    @Test
    fun `ordinary sort admits 32 native keys and rejects 33`() {
        val fields = (1..33).associate { QueryField("field$it") to sortField("field$it") }
        val schema = mongoTestSchema(fields = fields)
        val sorts = fields.keys.map { Sort(it, Sort.Direction.ASC) }
        MongoSortCompiler.compile(sorts.take(32), schema)!!.toBsonDocument().size.assert().isEqualTo(32)
        assertThrows<QuerySchemaValidationException> { MongoSortCompiler.compile(sorts, schema) }
    }

    @Test
    fun `duplicate sort directions and aliases cannot overwrite native keys`() {
        val schema =
            mongoTestSchema(
                fields = mapOf(QueryField("a") to sortField("stored"), QueryField("alias") to sortField("stored"))
            )
        listOf("a", "alias").forEach { second ->
            assertThrows<QuerySchemaValidationException> {
                MongoSortCompiler.compile(
                    listOf(Sort(QueryField("a"), Sort.Direction.ASC), Sort(QueryField(second), Sort.Direction.DESC)),
                    schema
                )
            }
        }
    }

    @Test
    fun `independent array sorts fail while scalar and single array sorts remain legal`() {
        val array = QueryValueSchema(QueryValueKind.ARRAY, items = mongoScalar(QueryValueType.INTEGER))
        val schema = mongoTestSchema(
            fields = mapOf(
                QueryField("a") to sortField("a", array),
                QueryField("b") to sortField("b", array),
                QueryField("id") to sortField("_id"),
            )
        )
        fun sorts(vararg names: String) = names.map { Sort(QueryField(it), Sort.Direction.ASC) }
        MongoSortCompiler.compile(sorts("a", "id"), schema)!!.toBsonDocument().size.assert().isEqualTo(2)
        assertThrows<QuerySchemaValidationException> { MongoSortCompiler.compile(sorts("a", "b"), schema) }
    }

    @Test
    fun `array alternatives cannot bypass parallel array sort checks`() {
        val number = mongoScalar(QueryValueType.INTEGER)
        val array = QueryValueSchema(QueryValueKind.ARRAY, items = number)
        val mixed = QueryValueSchema(QueryValueKind.UNION, alternatives = listOf(number, array))
        val nullable =
            QueryValueSchema(QueryValueKind.UNION, alternatives = listOf(mixed, QueryValueSchema(QueryValueKind.NULL)))
        listOf(mixed, nullable).forEach { value ->
            val schema = mongoTestSchema(
                fields = mapOf(
                    QueryField("a") to sortField("a", value),
                    QueryField("b") to sortField("b", array),
                    QueryField("id") to sortField("_id"),
                )
            )
            fun sorts(vararg names: String) = names.map { Sort(QueryField(it), Sort.Direction.ASC) }
            MongoSortCompiler.compile(sorts("a", "id"), schema)!!.toBsonDocument().size.assert().isEqualTo(2)
            assertThrows<QuerySchemaValidationException> { MongoSortCompiler.compile(sorts("a", "b"), schema) }
        }
    }

    private fun temporalSchema(value: QueryValueSchema) = mongoTestSchema(
        fields = mapOf(QueryField("epoch") to MongoTestField(value, temporalCapability, "stored")),
    )

    private fun histogram(zone: String = "UTC") = aggregation {
        dateHistogram("epoch", AggregationDateUnit.DAY, "day", ZoneId.of(zone))
        count("count")
    }

    private fun searchSchema() = mongoTestSchema(
        capabilities = setOf(QueryCapability.FULL_TEXT_TERMS),
        fields = mapOf(QueryField("name") to MongoTestField(mongoScalar(), setOf(QueryCapability.EXACT_MATCH), "name")),
    )

    private fun sortField(physical: String, value: QueryValueSchema = mongoScalar()) =
        MongoTestField(value, setOf(QueryCapability.SORT), physical)
}
