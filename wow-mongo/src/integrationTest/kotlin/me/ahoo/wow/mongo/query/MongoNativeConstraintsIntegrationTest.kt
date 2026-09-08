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

import com.mongodb.client.model.Indexes
import com.mongodb.client.model.CreateCollectionOptions
import com.mongodb.client.model.ValidationOptions
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.mongo.query.aggregation.MongoAggregationCompiler
import me.ahoo.wow.mongo.query.event.EventStreamFilterCompiler
import me.ahoo.wow.mongo.query.schema.MongoQuerySchemaAdapter
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.validateQuery
import me.ahoo.wow.tck.container.MongoTestFixture
import org.bson.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class MongoNativeConstraintsIntegrationTest {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture("mongo_native_constraints")

    @Test
    fun `numeric validator alias intersection remains queryable through native metadata`() {
        val database = mongo.database()
        val validator = Document("\$jsonSchema", Document("bsonType", "object").append(
            "properties", Document("value", Document("allOf", listOf(
                Document("bsonType", "number"), Document("bsonType", "long"),
            ))),
        ))
        database.createCollection("numeric_alias", CreateCollectionOptions().validationOptions(
            ValidationOptions().validator(validator),
        )).toMono().block()
        val collection = database.getCollection("numeric_alias")
        collection.insertMany(listOf(Document("value", 1L), Document("value", 2L))).toMono().block()
        val definition = LogicalQuerySchema(QueryValueSchema(QueryValueKind.OBJECT, properties = mapOf(
            "value" to QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.INTEGER)),
        )))
        val schema = MongoQuerySchemaAdapter(collection, database, QueryModel.EVENT_STREAM).resolve(definition).block()!!
        val query = filter { "value" eq 1L }
        val compiled = EventStreamFilterCompiler.compile(validateQuery(query, schema), schema)
        collection.countDocuments(compiled).toMono().block().assert().isEqualTo(1L)
    }

    @Test
    fun `nullable epoch histogram executes normalized fixed offsets on Mongo`() {
        val collection = mongo.database().getCollection("histograms")
        collection.insertMany(listOf(Document("epoch", 0L), Document("epoch", null))).toMono().block()
        val epoch = QueryValueSchema(
            QueryValueKind.SCALAR,
            valueTypes = setOf(QueryValueType.INTEGER),
            semanticType = Temporal.Epoch(TimeUnit.MILLISECONDS),
        )
        val definition = LogicalQuerySchema(QueryValueSchema(
            QueryValueKind.OBJECT,
            properties = mapOf("epoch" to QueryValueSchema(
                QueryValueKind.UNION, alternatives = listOf(epoch, QueryValueSchema(QueryValueKind.NULL)),
            )),
        ))
        val schema = MongoQuerySchemaAdapter.bind(definition, emptyList(), null, QueryModel.EVENT_STREAM)
        mapOf("UTC+08:00" to -28_800_000L, "GMT+08:00" to -28_800_000L, "UTC" to 0L).forEach { (zone, day) ->
            val query = aggregation {
                dateHistogram("epoch", AggregationDateUnit.DAY, "day", ZoneId.of(zone))
                count("count")
            }
            val result = collection.aggregate(MongoAggregationCompiler(EventStreamFilterCompiler).compile(query, schema))
                .toFlux().collectList().block()!!
            result.assert().hasSize(1)
            result.single().getLong("day").assert().isEqualTo(day)
            (result.single()["count"] as Number).toLong().assert().isEqualTo(1L)
        }
    }

    @Test
    fun `indexed text or and one array plus scalar sort remain executable`() {
        val collection = mongo.database().getCollection("search_and_sort")
        collection.insertMany(listOf(
            Document("name", "alpha").append("a", listOf(1, 2)).append("id", "first"),
            Document("name", "beta").append("a", listOf(3, 4)).append("id", "second"),
        )).toMono().block()
        collection.createIndex(Indexes.text("name")).toMono().block()
        collection.createIndex(Indexes.ascending("name")).toMono().block()
        val scalar = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING))
        val definition = LogicalQuerySchema(QueryValueSchema(
            QueryValueKind.OBJECT,
            properties = mapOf(
                "name" to scalar, "id" to scalar,
                "a" to QueryValueSchema(QueryValueKind.ARRAY, items = QueryValueSchema(
                    QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.INTEGER),
                )),
            ),
        ))
        val schema = MongoQuerySchemaAdapter.bind(
            definition, collection.listIndexes().toFlux().collectList().block()!!, null, QueryModel.EVENT_STREAM,
        )
        val expression = OrFilter(listOf(SearchFilter("alpha"), filter { "name" eq "beta" }))
        val query = aggregation { filter(expression); count("count") }
        val result = collection.aggregate(MongoAggregationCompiler(EventStreamFilterCompiler).compile(query, schema))
            .toFlux().single().block()!!
        (result["count"] as Number).toLong().assert().isEqualTo(2L)
        val sorts = listOf(Sort(QueryField("a"), Sort.Direction.ASC), Sort(QueryField("id"), Sort.Direction.ASC))
        val sorted = collection.find().sort(MongoSortCompiler.compile(sorts, schema)).toFlux().collectList().block()!!
        sorted.map { it.getString("id") }.assert().isEqualTo(listOf("first", "second"))
    }
}
