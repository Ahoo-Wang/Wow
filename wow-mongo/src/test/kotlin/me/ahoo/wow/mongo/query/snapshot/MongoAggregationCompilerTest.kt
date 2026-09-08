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

import io.mockk.spyk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.mongo.query.MongoTestField
import me.ahoo.wow.mongo.query.aggregation.MongoAggregationCompiler
import me.ahoo.wow.mongo.query.event.EventStreamFilterCompiler
import me.ahoo.wow.mongo.query.mongoTestSchema
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.serialization.MessageRecords
import org.bson.BsonDocument
import org.bson.BsonString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class MongoAggregationCompilerInputTest {

    @Test
    fun `root and every unwound element relative time share one compilation instant`() {
        val instant = java.time.Instant.parse("1970-01-02T12:00:00Z")
        val utc = ZoneId.of("UTC")
        val input = schema(
            field(
                "state.rootTime",
                QueryCapability.RANGE,
                "state.rootTime",
                QueryValueType.INTEGER,
                Temporal.Epoch(TimeUnit.SECONDS)
            ),
            field(
                "state.orders.elementTime",
                QueryCapability.RANGE,
                "state.orders.elementTime",
                QueryValueType.INTEGER,
                Temporal.Epoch(TimeUnit.SECONDS)
            ),
            field(
                "state.orders.lines.elementTime",
                QueryCapability.RANGE,
                "state.orders.lines.elementTime",
                QueryValueType.INTEGER,
                Temporal.Epoch(TimeUnit.SECONDS)
            ),
        )
        val query = aggregation {
            filter { "state.rootTime".today(utc) }
            expand("state.orders") { "elementTime".today(utc) }
            expand("lines") { "elementTime".today(utc) }
            count("count")
        }
        val compiler = MongoAggregationCompiler(SnapshotFilterCompiler)
        val matches = compiler.compile(
            query,
            input,
            instant
        ).map { it.toBsonDocument() }.filter { it.containsKey("\$match") }
        matches.assert().hasSize(3)
        matches.forEach { it.toJson().assert().contains("86400").contains("172800") }
        matches.drop(1).forEach { it.toJson().assert().doesNotContain("deleted") }
        val next = compiler.compile(query, input, instant.plusSeconds(86400)).map {
            it.toBsonDocument()
        }.filter { it.containsKey("\$match") }
        next.forEach { it.toJson().assert().contains("172800").contains("259200") }
    }

    @Test
    fun `group should resolve its terms input once for match and group stages`() {
        val observed = spyk(schema(field("state.status", QueryCapability.AGGREGATE_TERMS, "storage.status")))
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                terms("state.status", "status")
                count("count")
            },
            observed,
        ).map { it.toBsonDocument() }

        pipeline[1].toJson().assert().contains("storage.status")
        pipeline[2].getDocument("\$group").getDocument("_id").getString("status").value.assert()
            .isEqualTo("\$storage.status")
        verify(exactly = 1) {
            observed.field(QueryField("state.status"))
        }
    }

    @Test
    fun `group should reuse its numeric input for histogram match and group stages`() {
        val observed = spyk(
            schema(
                field(
                    "state.amount",
                    QueryCapability.AGGREGATE_NUMERIC,
                    "storage.amount",
                    QueryValueType.DECIMAL,
                ),
            ),
        )
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                histogram("state.amount", 10.0, "range")
                count("count")
            },
            observed,
        ).map { it.toBsonDocument() }

        val matchInput = pipeline[1].getDocument("\$match").getArray("\$and")[0].asDocument()
            .getDocument("\$expr").getDocument("\$isNumber")
        val groupInput = pipeline[2].getDocument("\$group").getDocument("_id").getDocument("range")
            .getArray("\$multiply")[0].asDocument().getDocument("\$floor")
            .getArray("\$divide")[0].asDocument()
        matchInput.assert().isEqualTo(groupInput)
        verify(exactly = 1) {
            observed.field(QueryField("state.amount"))
        }
    }

    @Test
    fun `group should reuse its epoch date input for match and group stages`() {
        val observed = spyk(
            schema(
                field(
                    "state.createdAt",
                    QueryCapability.AGGREGATE_TEMPORAL,
                    "storage.createdAt",
                    semanticType = Temporal.Epoch(TimeUnit.MICROSECONDS),
                ),
            ),
        )
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                dateHistogram("state.createdAt", AggregationDateUnit.DAY, "day")
                count("count")
            },
            observed,
        ).map { it.toBsonDocument() }

        val matchInput = pipeline[1].getDocument("\$match").getArray("\$and")[0].asDocument()
            .getDocument("\$expr").getArray("\$ne")[0].asDocument()
        val groupInput = pipeline[2].getDocument("\$group").getDocument("_id").getDocument("day")
            .getDocument("\$toLong").getDocument("\$dateTrunc").getDocument("date")
        matchInput.assert().isEqualTo(groupInput)
        verify(exactly = 2) {
            observed.field(QueryField("state.createdAt"))
        }
    }

    @Test
    fun `group compilation should preserve the first semantic failure`() {
        val input = schema(
            field("state.first", QueryCapability.AGGREGATE_TEMPORAL, "storage.first"),
            field("state.second", QueryCapability.PRESENCE, "storage.second"),
        )

        assertThrows<QuerySchemaValidationException> {
            MongoAggregationCompiler(SnapshotFilterCompiler).compile(
                aggregation {
                    dateHistogram("state.first", AggregationDateUnit.DAY, "first")
                    terms("state.second", "second")
                    count("count")
                },
                input,
            )
        }.message.assert().isEqualTo("Query field [state.first] does not have a supported temporal semantic type.")
    }

    @Test
    fun `summary group should use a null id`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation { count("count") },
            schema(),
        ).map { it.toBsonDocument() }

        requireNotNull(pipeline[1].getDocument("\$group").get("_id")).isNull.assert().isTrue()
    }

    @Test
    fun `consecutive compilations should produce the same native pipeline`() {
        val compiler = MongoAggregationCompiler(SnapshotFilterCompiler)
        val query = aggregation {
            terms("state.status", "status")
            histogram("state.amount", 10.0, "range")
            dateHistogram("state.createdAt", AggregationDateUnit.DAY, "day")
            count("count")
        }
        val input = schema(
            field("state.status", QueryCapability.AGGREGATE_TERMS, "storage.status"),
            field(
                "state.amount",
                QueryCapability.AGGREGATE_NUMERIC,
                "storage.amount",
                QueryValueType.DECIMAL,
            ),
            field(
                "state.createdAt",
                QueryCapability.AGGREGATE_TEMPORAL,
                "storage.createdAt",
                semanticType = Temporal.Date,
            ),
        )

        val first = compiler.compile(query, input).map { it.toBsonDocument() }
        val second = compiler.compile(query, input).map { it.toBsonDocument() }

        first.assert().isEqualTo(second)
    }
}

class MongoAggregationCompilerTest {

    @Test
    fun `unknown inputs and caller supplied physical aliases are rejected`() {
        val schema =
            schema(field("state.total", QueryCapability.AGGREGATE_NUMERIC, "storage.total", QueryValueType.DECIMAL))
        listOf(
            aggregation {
                terms("state.unknown", "unknown")
                count("count")
            },
            aggregation {
                dateHistogram("state.unknown", AggregationDateUnit.DAY, "day")
                count("count")
            },
            aggregation { sum("storage.total", "total") },
            aggregation {
                expand("state.orders")
                terms("unknown", "unknown")
                count("count")
            },
        ).forEach { query ->
            assertThrows<QuerySchemaValidationException> {
                MongoAggregationCompiler(
                    SnapshotFilterCompiler
                ).compile(query, schema)
            }
        }
    }

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
    fun `resolved element filters should retain logical resolved and physical parents`() {
        val schema = schema(
            field(
                "state.orders",
                QueryCapability.ELEMENT_SCOPE,
                "storage.orders",
                QueryValueType.OBJECT,
            ),
            field(
                "state.orders.status",
                QueryCapability.EXACT_MATCH,
                "storage.orders.status",
                additionalCapabilities = setOf(QueryCapability.AGGREGATE_TERMS),
            ),
        )
        val resolved = aggregation {
            expand("state.orders") { "status" eq "PAID" }
            terms("status", "status")
            count("count")
        }

        MongoAggregationCompiler(SnapshotFilterCompiler).compile(resolved, schema)
            .joinToString { it.toBsonDocument().toJson() }.assert()
            .contains("storage.orders.status")
            .doesNotContain("state.orders.status.keyword")
            .doesNotContain("document.orders.status.keyword")
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
                "storage.created_at",
                semanticType = Temporal.Epoch(TimeUnit.SECONDS),
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
            .contains("storage.created_at")
            .contains("\$isArray")
            .contains("\$size")
            .contains("\$isNumber")
            .contains("\$convert")
            .contains("\"to\": \"long\"")
            .contains("\"to\": \"date\"")
            .contains("\"onError\": null")
            .contains("\"onNull\": null")
            .contains("1000")
            .contains("\$dateTrunc")
            .doesNotContain("document.createdAt")
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
            mongoTestSchema(
                QueryModel.SNAPSHOT,
                emptySet(),
                mapOf(
                    field("deleted", QueryCapability.EXACT_MATCH, "deleted", QueryValueType.BOOLEAN),
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
            mongoTestSchema(
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
            .contains("\$filter")
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
}

private fun schema(vararg fields: Pair<QueryField, MongoTestField>) = mongoTestSchema(
    model = QueryModel.SNAPSHOT,
    capabilities = emptySet(),
    fields = mapOf(
        field("deleted", QueryCapability.EXACT_MATCH, "deleted", QueryValueType.BOOLEAN),
        field(MessageRecords.AGGREGATE_ID, QueryCapability.AGGREGATE_TERMS, "_id"),
        field("state.status", QueryCapability.AGGREGATE_TERMS, "state.status"),
        field("state.productId", QueryCapability.AGGREGATE_TERMS, "state.productId"),
        field("state.amount", QueryCapability.AGGREGATE_NUMERIC, "state.amount", QueryValueType.DECIMAL),
        field("state.quantity", QueryCapability.AGGREGATE_NUMERIC, "state.quantity", QueryValueType.INTEGER),
        field("state.createdAt", QueryCapability.AGGREGATE_TEMPORAL, "state.createdAt", semanticType = Temporal.Date),
        field("state.orders", QueryCapability.ELEMENT_SCOPE, "state.orders", QueryValueType.OBJECT),
        field("state.orders.status", QueryCapability.EXACT_MATCH, "state.orders.status"),
        field("state.orders.lines", QueryCapability.ELEMENT_SCOPE, "state.orders.lines", QueryValueType.OBJECT),
        field("state.orders.lines.quantity", QueryCapability.RANGE, "state.orders.lines.quantity", QueryValueType.INTEGER),
        field("state.orders.lines.productId", QueryCapability.AGGREGATE_TERMS, "state.orders.lines.productId"),
        field("state.items", QueryCapability.ELEMENT_SCOPE, "state.items", QueryValueType.OBJECT),
        field("state.items.quantity", QueryCapability.RANGE, "state.items.quantity", QueryValueType.INTEGER),
    ) + fields.toMap(),
)

private fun field(
    logicalPath: String,
    capability: QueryCapability,
    physicalPath: String,
    valueType: QueryValueType = QueryValueType.STRING,
    semanticType: Temporal? = null,
    additionalCapabilities: Set<QueryCapability> = emptySet(),
): Pair<QueryField, MongoTestField> {
    val value = if (capability == QueryCapability.ELEMENT_SCOPE) {
        QueryValueSchema(QueryValueKind.ARRAY, items = QueryValueSchema(QueryValueKind.OBJECT))
    } else {
        QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(valueType), semanticType = semanticType)
    }
    return QueryField(logicalPath) to MongoTestField(value, additionalCapabilities + capability, physicalPath)
}
