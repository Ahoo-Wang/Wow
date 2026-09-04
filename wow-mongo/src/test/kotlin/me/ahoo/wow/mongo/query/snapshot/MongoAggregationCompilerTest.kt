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

package me.ahoo.wow.mongo.query.snapshot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.mongo.query.aggregation.MongoAggregationCompiler
import me.ahoo.wow.mongo.query.event.EventStreamFilterCompiler
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryRewriteMode
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.query.schema.requireAccepted
import me.ahoo.wow.serialization.MessageRecords
import org.bson.BsonDocument
import org.bson.BsonString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class MongoAggregationCompilerTest {

    @Test
    fun `any metric should compile a resolved max accumulator and projection`() {
        val schema = schema(
            field(
                "state.productName",
                QueryCapability.AGGREGATE_TERMS,
                "document.productName",
            ),
        )
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                any("state.productName", "productName")
                count("count")
            },
            schema,
        ).map { it.toBsonDocument() }

        val group = pipeline.single { it.containsKey("\$group") }.getDocument("\$group")
        group.getDocument("productName").assert()
            .isEqualTo(BsonDocument("\$max", BsonString("\$document.productName")))
        pipeline.single { it.containsKey("\$project") }.toJson().assert().contains("productName")
    }

    @Test
    fun `schema bindings should drive element group and metric physical paths`() {
        val schema = schema(
            field("state.orders", QueryCapability.ELEMENT_SCOPE, "document.orders", QueryValueType.OBJECT),
            field("state.orders.productId", QueryCapability.AGGREGATE_TERMS, "document.orders.sku"),
            field(
                "state.orders.amount",
                QueryCapability.AGGREGATE_NUMERIC,
                "document.orders.total",
                QueryValueType.DECIMAL
            ),
        )
        val query = aggregation {
            expand("state.orders")
            terms("productId", "product")
            sum("amount", "total")
        }

        val json = MongoAggregationCompiler(SnapshotFilterCompiler).compile(query, schema)
            .joinToString { it.toBsonDocument().toJson() }

        json.assert()
            .contains("document.orders")
            .contains("document.orders.sku")
            .contains("document.orders.total")
            .doesNotContain("state.orders.productId")
            .doesNotContain("state.orders.amount")
    }

    @Test
    fun `compatible element aggregation fields should retain the physical parent`() {
        val schema = schema(
            field("state.orders", QueryCapability.ELEMENT_SCOPE, "storage.orders", QueryValueType.OBJECT),
        )
        val resolved = schema.resolve(
            aggregation {
                expand("state.orders")
                terms("extra", "extra")
                sum("amount", "total")
                count("count")
            },
        ).requireAccepted(QuerySchemaValidationMode.COMPATIBLE)

        MongoAggregationCompiler(SnapshotFilterCompiler).compile(resolved, schema)
            .first { it.toBsonDocument().containsKey("\$group") }
            .toBsonDocument().toJson().assert()
            .contains("\$storage.orders.extra")
            .contains("\$storage.orders.amount")
            .doesNotContain("\$state.orders.extra")
            .doesNotContain("\$state.orders.amount")
    }

    @Test
    fun `relative field sharing its parent prefix should still resolve inside the element`() {
        val schema = schema(
            field("body", QueryCapability.ELEMENT_SCOPE, "events", QueryValueType.OBJECT),
            field("body.body.data", QueryCapability.AGGREGATE_TERMS, "events.payload.data"),
        )

        val group = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                expand("body")
                terms("body.data", "data")
                count("count")
            },
            schema,
        ).first { it.toBsonDocument().containsKey("\$group") }.toBsonDocument().toJson()

        group.assert().contains("\$events.payload.data").doesNotContain("\$body.data")
    }

    @Test
    fun `dynamic suffix without terms binding should use the conventional physical path`() {
        val schema = schema(
            field(
                "state.attributes",
                QueryCapability.EXACT_MATCH,
                "state.attributes",
                QueryValueType.OBJECT,
                dynamicChildren = true,
            ),
        )
        val query = aggregation {
            terms("state.attributes.color", "color")
            count("count")
        }

        MongoAggregationCompiler(SnapshotFilterCompiler).compile(query, schema)
            .first { it.toBsonDocument().containsKey("\$group") }
            .toBsonDocument().toJson().assert().contains("state.attributes.color")
    }

    @Test
    fun `declared field without terms binding should still fail compilation`() {
        val schema = schema(
            field("state.category", QueryCapability.PRESENCE, "state.category"),
        )
        val query = aggregation {
            terms("state.category", "category")
            count("count")
        }

        assertThrows<QuerySchemaValidationException> {
            MongoAggregationCompiler(SnapshotFilterCompiler).compile(query, schema)
        }
    }

    @Test
    fun `epoch date histogram should compile safe singleton conversion and date truncation`() {
        val schema = schema(
            field(
                "state.createdAt",
                QueryCapability.AGGREGATE_TEMPORAL,
                "document.created_at",
                semanticType = Temporal.Epoch(TimeUnit.MILLISECONDS),
            ),
        )
        val query = aggregation {
            dateHistogram("state.createdAt", AggregationDateUnit.DAY, "day")
            count("count")
        }

        val group = MongoAggregationCompiler(SnapshotFilterCompiler).compile(query, schema)
            .first { it.toBsonDocument().containsKey("\$group") }
            .toBsonDocument().toJson()

        group.assert()
            .contains("document.created_at")
            .contains("\$isArray")
            .contains("\$size")
            .contains("\$isNumber")
            .contains("\$convert")
            .contains("\"to\": \"long\"")
            .contains("\"to\": \"date\"")
            .contains("\"onError\": null")
            .contains("\"onNull\": null")
            .contains("\$dateTrunc")
            .doesNotContain("state.createdAt")
    }

    @Test
    fun `compatible dynamic temporal field should compile with its original path`() {
        val schema = schema(
            field(
                "state.attributes",
                QueryCapability.EXACT_MATCH,
                "state.attributes",
                QueryValueType.OBJECT,
                dynamicChildren = true,
            ),
        )
        val accepted = schema.resolve(
            aggregation {
                dateHistogram("state.attributes.createdAt", AggregationDateUnit.DAY, "day")
                count("count")
            },
        ).requireAccepted(QuerySchemaValidationMode.COMPATIBLE)

        MongoAggregationCompiler(SnapshotFilterCompiler).compile(accepted, schema)
            .single { it.toBsonDocument().containsKey("\$group") }
            .toBsonDocument().toJson().assert()
            .contains("\$toDate")
            .contains("\$state.attributes.createdAt")
    }

    @Test
    fun `declared temporal field without a temporal binding should fail compilation`() {
        val schema = schema(
            field("state.createdAt", QueryCapability.EXACT_MATCH, "state.createdAt"),
        )
        val query = aggregation {
            dateHistogram("state.createdAt", AggregationDateUnit.DAY, "day")
            count("count")
        }

        assertThrows<QuerySchemaValidationException> {
            MongoAggregationCompiler(SnapshotFilterCompiler).compile(query, schema)
        }
    }

    @Test
    fun `sub millisecond epoch date histogram should floor negative values`() {
        val schema = schema(
            field(
                "state.createdAt",
                QueryCapability.AGGREGATE_TEMPORAL,
                "state.createdAt",
                semanticType = Temporal.Epoch(TimeUnit.MICROSECONDS),
            ),
        )
        val query = aggregation {
            dateHistogram("state.createdAt", AggregationDateUnit.DAY, "day")
            count("count")
        }

        MongoAggregationCompiler(SnapshotFilterCompiler).compile(query, schema)
            .first { it.toBsonDocument().containsKey("\$group") }
            .toBsonDocument().toJson().assert()
            .contains("\$floor")
            .contains("1000")
    }

    @Test
    fun `compiler should unwind and filter every relative element`() {
        val query = aggregation {
            expand("state.orders") { "status" eq "PAID" }
            expand("lines") { "quantity" gt 0 }
            terms("productId", "product")
            count("count")
        }

        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(query, schema())
        pipeline.map { it.toBsonDocument().keys.first() }.assert().containsExactly(
            "\$match",
            "\$unwind",
            "\$match",
            "\$unwind",
            "\$match",
            "\$match",
            "\$group",
            "\$project",
            "\$sort",
            "\$limit",
        )
        pipeline.joinToString { it.toBsonDocument().toJson() }.assert()
            .contains("state.orders.status")
            .contains("state.orders.lines.quantity")
            .contains("state.orders.lines.productId")
    }

    @Test
    fun `compiler should compile groups metrics and stable sort`() {
        val query = aggregation {
            terms("state.productId", "product")
            histogram("state.amount", 10.0, "amountRange")
            dateHistogram("state.createdAt", AggregationDateUnit.DAY, "day", ZoneId.of("Asia/Shanghai"))
            count("count")
            sum("state.amount", "total")
            avg("state.amount", "average")
            min("state.amount", "minimum")
            max("state.amount", "maximum")
            sort { "total".desc() }
            limit(7)
        }

        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(query, schema())
        val stages = pipeline.associateBy { it.toBsonDocument().keys.first() }
        stages.getValue("\$group").toBsonDocument().toJson().assert()
            .contains("\$floor")
            .contains("\$dateTrunc")
            .contains("\"unit\": \"day\"")
            .contains("\"timezone\": \"Asia/Shanghai\"")
            .doesNotContain("startOfWeek")
            .contains("\$sum")
            .contains("\$avg")
            .contains("\$min")
            .contains("\$max")
        stages.getValue("\$sort").toBsonDocument().getDocument("\$sort").keys.assert()
            .containsExactly("total", "product", "amountRange", "day")
        stages.getValue("\$sort").toBsonDocument().toJson().assert().contains("\"total\": -1")
        stages.getValue("\$limit").toBsonDocument().toJson().assert().contains("7")
    }

    @Test
    fun `weekly date histogram should start on Monday and preserve timezone`() {
        val query = aggregation {
            dateHistogram("state.createdAt", AggregationDateUnit.WEEK, "week", ZoneId.of("Asia/Shanghai"))
            count("count")
        }

        MongoAggregationCompiler(SnapshotFilterCompiler).compile(query, schema())
            .first { it.toBsonDocument().containsKey("\$group") }
            .toBsonDocument().toJson().assert()
            .contains("\"unit\": \"week\"")
            .contains("\"timezone\": \"Asia/Shanghai\"")
            .contains("\"startOfWeek\": \"Monday\"")
    }

    @Test
    fun `UTC date histogram should use the Mongo UTC timezone`() {
        val query = aggregation {
            dateHistogram("state.createdAt", AggregationDateUnit.DAY, "day", ZoneId.of("Z"))
            count("count")
        }

        MongoAggregationCompiler(SnapshotFilterCompiler).compile(query, schema())
            .first { it.toBsonDocument().containsKey("\$group") }
            .toBsonDocument().toJson().assert().contains("\"timezone\": \"UTC\"")
    }

    @Test
    fun `summary compiler should retain contribution counts`() {
        val query = aggregation { sum("state.amount", "total") }

        MongoAggregationCompiler(SnapshotFilterCompiler).compile(query, schema())
            .joinToString { it.toBsonDocument().toJson() }
            .assert().contains("__wow_value_count_total")
    }

    @Test
    fun `computed metric should compile guarded recursive arithmetic`() {
        val query = aggregation {
            sum(
                (field("state.amount") + constant(2.0)) *
                    (field("state.quantity") - constant(1.0)) / constant(3.0),
                "total",
            )
        }

        val groupJson = MongoAggregationCompiler(SnapshotFilterCompiler).compile(query, schema())
            .first { it.toBsonDocument().containsKey("\$group") }
            .toBsonDocument()
            .toJson()

        groupJson.assert()
            .contains("\$add")
            .contains("\$subtract")
            .contains("\$multiply")
            .contains("\$divide")
            .contains("\$convert")
            .contains("\$isNumber")
            .contains("\$let")
            .contains("__wow_value_count_total")
    }

    @Test
    fun `plain field metric should normalize scalar or singleton values without conversion`() {
        val groupJson = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation { sum("state.amount", "total") },
            schema(),
        )[1].toBsonDocument().toJson()

        groupJson.assert()
            .contains("\$isArray")
            .contains("\$arrayElemAt")
            .contains("\$isNumber")
            .doesNotContain("\$convert")
    }

    @Test
    fun `element filter should not restore active deletion scope`() {
        val query = aggregation {
            filter(DeletionFilter(DeletionState.DELETED))
            expand("state.items") { "quantity" gt 0 }
            count("count")
        }

        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(query, schema())
        pipeline[0].toBsonDocument().toJson().assert().contains("\"deleted\": true")
        pipeline[2].toBsonDocument().toJson().assert().doesNotContain("deleted")
    }

    @Test
    fun `root aggregate id leaf should use Mongo primary key`() {
        val query = aggregation {
            terms(MessageRecords.AGGREGATE_ID, "aggregate")
            count("count")
        }

        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(query, schema())
        pipeline[1].toBsonDocument().toJson().assert()
            .contains("\"_id\"")
            .doesNotContain(MessageRecords.AGGREGATE_ID)
        pipeline[2].toBsonDocument().toJson().assert().contains("\"\$_id\"")
    }

    @Test
    fun `snapshot identity aggregation should use its schema physical path`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                terms(MessageRecords.AGGREGATE_ID, "aggregate")
                count("count")
            },
            QueryModelSchema(
                QueryModel.SNAPSHOT,
                emptySet(),
                mapOf(
                    QueryField(MessageRecords.AGGREGATE_ID) to field(
                        MessageRecords.AGGREGATE_ID,
                        QueryCapability.AGGREGATE_TERMS,
                        "snapshot.aggregate_id",
                    ).second,
                ),
            ),
        )

        pipeline.single { it.toBsonDocument().containsKey("\$group") }.toBsonDocument().toJson().assert()
            .contains("\$snapshot.aggregate_id")
            .doesNotContain("\$_id")
    }

    @Test
    fun `event stream identity aggregation should use its schema physical path`() {
        val pipeline = MongoAggregationCompiler(EventStreamFilterCompiler).compile(
            aggregation {
                terms(MessageRecords.ID, "event")
                count("count")
            },
            QueryModelSchema(
                QueryModel.EVENT_STREAM,
                emptySet(),
                mapOf(
                    QueryField(MessageRecords.ID) to field(
                        MessageRecords.ID,
                        QueryCapability.AGGREGATE_TERMS,
                        "event.stream_id",
                    ).second,
                ),
            ),
        )

        pipeline.single { it.toBsonDocument().containsKey("\$group") }.toBsonDocument().toJson().assert()
            .contains("\$event.stream_id")
            .doesNotContain("\$_id")
    }

    @Test
    fun `numeric contribution count should accept only Mongo numeric values`() {
        val query = aggregation { sum("state.amount", "total") }

        val group = MongoAggregationCompiler(SnapshotFilterCompiler).compile(query, schema())[1]
        group.toBsonDocument().toJson().assert()
            .contains("\$isNumber")
            .doesNotContain("\$ne")
    }

    @Test
    fun `minimum and maximum should accumulate only Mongo numeric values`() {
        val query = aggregation {
            min("state.amount", "minimum")
            max("state.amount", "maximum")
        }

        val group = MongoAggregationCompiler(SnapshotFilterCompiler).compile(query, schema())[1]
            .toBsonDocument().getDocument("\$group")
        listOf("minimum", "maximum").forEach { alias ->
            group.getDocument(alias).toJson().assert()
                .contains("\$cond")
                .contains("\$isNumber")
        }
    }

    @Test
    fun `schema bindings should apply to root and element filters without element deletion scope`() {
        val query = aggregation {
            filter { "state.status" eq "PAID" }
            expand("state.items") { "quantity" gt 0 }
            count("count")
        }

        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            query,
            schema(
                field("state.status", QueryCapability.EXACT_MATCH, "physical.state.status"),
                field("state.items", QueryCapability.ELEMENT_SCOPE, "physical.state.items", QueryValueType.OBJECT),
                field(
                    "state.items.quantity",
                    QueryCapability.RANGE,
                    "physical.state.items.quantity",
                    QueryValueType.INTEGER
                ),
            ),
        )
        pipeline[0].toBsonDocument().toJson().assert().contains("physical.state.status")
        pipeline[2].toBsonDocument().toJson().assert()
            .contains("physical.state.items.quantity")
            .doesNotContain("deleted")
    }

    @Test
    fun `accepted missing aggregation fields should retain their original path`() {
        val query = aggregation {
            terms("state.status", "status")
            count("count")
        }

        MongoAggregationCompiler(SnapshotFilterCompiler).compile(query, schema())[2].toBsonDocument().toJson().assert()
            .contains("\$state.status")
    }

    private fun schema(vararg fields: Pair<QueryField, QueryFieldSchema>) = QueryModelSchema(
        model = QueryModel.SNAPSHOT,
        capabilities = emptySet(),
        fields = fields.toMap() + field(
            MessageRecords.AGGREGATE_ID,
            QueryCapability.AGGREGATE_TERMS,
            "_id",
        ),
    )

    private fun field(
        logicalPath: String,
        capability: QueryCapability,
        physicalPath: String,
        valueType: QueryValueType = QueryValueType.STRING,
        semanticType: Temporal? = null,
        dynamicChildren: Boolean = false,
        rewriteMode: QueryRewriteMode = QueryRewriteMode.NONE,
    ): Pair<QueryField, QueryFieldSchema> {
        val source = QueryField(logicalPath)
        val physical = QueryField(
            if (logicalPath == MessageRecords.AGGREGATE_ID && physicalPath == logicalPath) "_id" else physicalPath,
        )
        return source to QueryFieldSchema(
            title = null,
            description = null,
            enumValues = null,
            valueTypes = setOf(valueType),
            nullable = false,
            required = true,
            cardinality = QueryCardinality.SINGLE,
            semanticType = semanticType,
            dynamicChildren = dynamicChildren,
            bindings = mapOf(
                capability to QueryFieldBinding(source, physical, QueryStorageType("test")),
            ),
            rewriteMode = rewriteMode,
        )
    }
}
