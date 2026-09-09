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

import com.mongodb.reactivestreams.client.MongoCollection
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.mongo.query.aggregation.MongoAggregationCompiler
import me.ahoo.wow.mongo.query.event.EventStreamFilterCompiler
import me.ahoo.wow.mongo.query.schema.MongoQuerySchemaAdapter
import me.ahoo.wow.mongo.query.snapshot.SnapshotFilterCompiler
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.tck.container.MongoTestFixture
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono

class MongoMatchNoneIntegrationTest {
    companion object {
        @JvmStatic
        fun models(): List<QueryModel> = listOf(QueryModel.SNAPSHOT, QueryModel.EVENT_STREAM)
    }

    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture("mongo_match_none")

    @ParameterizedTest
    @MethodSource("models")
    fun `match none must return no rows without examining documents or index keys`(model: QueryModel) {
        val collection = collection()
        val compiled = compiler(model).compile(MatchNoneFilter, schema(model))
        val sort = if (model == QueryModel.SNAPSHOT) Document("_id", 1) else Document()
        assertNoScan(collection, compiled, sort)
    }

    @Test
    fun `descending null cursor past the final identity must not scan`() {
        val collection = collection()
        val compiled = MongoCursorFilterCompiler.compile(cursorSort, listOf(null, "z"))
        assertNoScan(collection, compiled, Document("rank", -1).append("_id", 1))
    }

    @Test
    fun `descending null cursor must preserve remaining null and missing identity ties`() {
        val collection = collection(listOf(
            Document("_id", "a").append("rank", null),
            Document("_id", "b"),
            Document("_id", "c").append("rank", null),
            Document("_id", "d").append("rank", 1),
        ))
        val compiled = MongoCursorFilterCompiler.compile(cursorSort, listOf(null, "a"))
        collection.find(compiled).sort(Document("rank", -1).append("_id", 1))
            .toFlux().map { it.getString("_id") }.collectList().block()!!
            .assert().isEqualTo(listOf("b", "c"))
    }

    @ParameterizedTest
    @MethodSource("models")
    fun `match none must preserve boolean and element match semantics`(model: QueryModel) {
        val collection = collection()
        val compiler = compiler(model)
        val schema = schema(model)
        val one = filter { "rank" eq 1 }
        val emptyElements = ElementMatchFilter(QueryField("items"), MatchNoneFilter)
        listOf(
            AndFilter(listOf(one, MatchNoneFilter)) to 0L,
            OrFilter(listOf(one, MatchNoneFilter)) to 1L,
            NorFilter(listOf(MatchNoneFilter)) to 32L,
            emptyElements to 0L,
            NorFilter(listOf(emptyElements)) to 32L,
        ).forEach { (expression, count) ->
            collection.countDocuments(compiler.compile(expression, schema)).toMono().block()!!
                .assert().isEqualTo(count)
        }
    }

    @ParameterizedTest
    @MethodSource("models")
    fun `match none must remain empty at root and expanded aggregation scopes`(model: QueryModel) {
        val collection = collection()
        val compiler = MongoAggregationCompiler(compiler(model))
        val schema = schema(model)
        val metrics = listOf(AggregationMetric.Count("count"))
        listOf(
            AggregationQuery(filter = MatchNoneFilter, metrics = metrics),
            AggregationQuery(
                elements = listOf(AggregationElement(QueryField("items"), MatchNoneFilter)),
                metrics = metrics,
            ),
        ).forEach { query ->
            collection.aggregate(compiler.compile(query, schema)).toFlux().collectList().block()!!
                .assert().isEmpty()
        }
    }

    private fun collection(
        documents: List<Document> = (1..32).map {
            Document("_id", "row-$it").append("rank", it).append("version", it)
                .append("items", listOf(Document("value", it)))
        },
    ): MongoCollection<Document> = mongo.database().getCollection("documents").also {
        it.insertMany(documents).toMono().block()
    }

    private fun assertNoScan(collection: MongoCollection<Document>, filter: Bson, sort: Document) {
        val command = Document("explain", Document("find", collection.namespace.collectionName)
            .append("filter", filter.toBsonDocument())
            .append("projection", Document("_id", 1).append("version", 1))
            .append("sort", sort)
            .append("limit", 2))
            .append("verbosity", "executionStats")
        val stats = mongo.database().runCommand(command).toMono().block()!!
            .get("executionStats", Document::class.java)
        (stats["nReturned"] as Number).toLong().assert().isEqualTo(0L)
        (stats["totalDocsExamined"] as Number).toLong().assert().isEqualTo(0L)
        (stats["totalKeysExamined"] as Number).toLong().assert().isEqualTo(0L)
    }

    private fun compiler(model: QueryModel): AbstractMongoFilterCompiler = when (model) {
        QueryModel.SNAPSHOT -> SnapshotFilterCompiler
        QueryModel.EVENT_STREAM -> EventStreamFilterCompiler
        else -> error("Unsupported test model: $model")
    }

    private fun schema(model: QueryModel): QueryModelSchema {
        val number = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.INTEGER))
        val definition = LogicalQuerySchema(QueryValueSchema(QueryValueKind.OBJECT, properties = mapOf(
            "rank" to number,
            "items" to QueryValueSchema(QueryValueKind.ARRAY, items = QueryValueSchema(
                QueryValueKind.OBJECT, properties = mapOf("value" to number),
            )),
        )))
        return MongoQuerySchemaAdapter.bind(definition, emptyList(), null, model)
    }

    private val cursorSort = listOf(
        Sort(QueryField("rank"), Sort.Direction.DESC),
        Sort(QueryField("_id"), Sort.Direction.ASC),
    )
}
