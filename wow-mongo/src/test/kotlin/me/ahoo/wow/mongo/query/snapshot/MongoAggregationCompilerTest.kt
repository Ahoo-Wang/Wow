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
import me.ahoo.wow.api.query.StringComparison
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
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.serialization.MessageRecords
import org.bson.BsonArray
import org.bson.BsonBoolean
import org.bson.BsonDocument
import org.bson.BsonDouble
import org.bson.BsonInt32
import org.bson.BsonInt64
import org.bson.BsonNull
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

@Suppress("LargeClass")
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
    fun `derived metrics compile into a second project stage`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                terms("state.status", "status")
                count("paid")
                sum("state.amount", "paidAmount") { "state.status" eq "PAID" }
                derived("aov") { ref("paidAmount") / ref("paid") }
            },
            statusFilterSchema,
        ).map { it.toBsonDocument() }

        val projects = pipeline.filter { it.containsKey("\$project") }
        projects.assert().hasSize(2)
        val derivedStage = projects[1].getDocument("\$project")
        derivedStage.containsKey("_id").assert().isTrue()
        val aov = derivedStage.getDocument("aov").getDocument("\$let")
        val binary = aov.getDocument("vars").getDocument("value").getDocument("\$let")
        binary.getDocument("vars").getString("left").value.assert().isEqualTo("\$paidAmount")
        binary.getDocument("vars").getString("right").value.assert().isEqualTo("\$paid")
        val cond = binary.getDocument("in").getArray("\$cond")
        cond.get(0).asDocument().getArray("\$and").assert().hasSize(3)
        cond.get(1).asDocument().getArray("\$divide").assert().hasSize(2)
        aov.getDocument("in").getArray("\$cond").get(0).asDocument().getArray("\$and").assert().hasSize(3)
        derivedStage.containsKey("paid").assert().isTrue()
        derivedStage.containsKey("paidAmount").assert().isTrue()
        pipeline.indexOfLast { it.containsKey("\$project") }
            .assert().isLessThan(pipeline.indexOfFirst { it.containsKey("\$sort") })
    }

    @Test
    fun `derived without derived metrics keeps a single project stage`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                terms("state.status", "status")
                count("total")
                sum("state.amount", "totalAmount")
                sort { "totalAmount".desc() }
            },
            schema(),
        ).map { it.toBsonDocument() }

        pipeline.map { it.keys.first() }.assert()
            .containsExactly("\$match", "\$match", "\$group", "\$project", "\$sort", "\$limit")
        pipeline.filter { it.containsKey("\$project") }.assert().hasSize(1)
    }

    @Test
    fun `derived constants and chains compile leaves in order`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                count("total")
                derived("target") { constant(120.0) }
                derived("half") { ref("total") / constant(2.0) }
                derived("quarter") { ref("half") / constant(2.0) }
            },
            schema(),
        ).map { it.toBsonDocument() }

        val projects = pipeline.filter { it.containsKey("\$project") }
        projects.assert().hasSize(4)
        val targetStage = projects[1].getDocument("\$project")
        targetStage.getDocument("target").getValue("\$literal").asDouble().value.assert().isEqualTo(120.0)

        val halfStage = projects[2].getDocument("\$project")
        val half = halfStage.getDocument("half").getDocument("\$let")
            .getDocument("vars").getDocument("value").getDocument("\$let")
        half.getDocument("vars").getString("left").value.assert().isEqualTo("\$total")
        half.getDocument("vars").getDocument("right").getValue("\$literal").asDouble().value.assert().isEqualTo(2.0)

        val quarterStage = projects[3].getDocument("\$project")
        quarterStage.containsKey("half").assert().isTrue()
        val quarter = quarterStage.getDocument("quarter").getDocument("\$let")
            .getDocument("vars").getDocument("value").getDocument("\$let")
        quarter.getDocument("vars").getString("left").value.assert().isEqualTo("\$half")
        val keys = quarterStage.keys.toList()
        keys.indexOf("half").assert().isLessThan(keys.indexOf("quarter"))
    }

    @Test
    fun `metrics declared after a derived metric survive derived project stages`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                count("total")
                derived("half") { ref("total") / constant(2.0) }
                sum("state.amount", "amount")
                count("paid")
            },
            schema(),
        ).map { it.toBsonDocument() }

        val projects = pipeline.filter { it.containsKey("\$project") }
        projects.assert().hasSize(2)
        val derivedStage = projects[1].getDocument("\$project")
        derivedStage.containsKey("total").assert().isTrue()
        derivedStage.containsKey("half").assert().isTrue()
        derivedStage.containsKey("amount").assert().isTrue()
        derivedStage.containsKey("paid").assert().isTrue()
        val keys = derivedStage.keys.toList()
        keys.indexOf("total").assert().isLessThan(keys.indexOf("half"))
        keys.indexOf("half").assert().isLessThan(keys.indexOf("amount"))
        keys.indexOf("amount").assert().isLessThan(keys.indexOf("paid"))
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
    fun `stddev and variance accumulate population statistics`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                stddev("state.amount", "stddev")
                variance("state.amount", "variance")
            },
            schema(),
        ).map { it.toBsonDocument() }

        val group = pipeline.first { it.containsKey("\$group") }.getDocument("\$group")
        listOf("stddev", "variance").forEach { alias ->
            group.getDocument(alias).containsKey("\$stdDevPop").assert().isTrue()
            group.getDocument("__wow_value_count_$alias").containsKey("\$sum").assert().isTrue()
        }
        val project = pipeline.first { it.containsKey("\$project") }.getDocument("\$project")
        val stddevCond = project.getDocument("stddev").getArray("\$cond")
        stddevCond.get(1).isNull.assert().isTrue()
        stddevCond.get(2).asString().value.assert().isEqualTo("\$stddev")
        val varianceCond = project.getDocument("variance").getArray("\$cond")
        varianceCond.get(1).isNull.assert().isTrue()
        val pow = varianceCond.get(2).asDocument().getArray("\$pow")
        pow.get(0).asString().value.assert().isEqualTo("\$variance")
        pow.get(1).asInt32().value.assert().isEqualTo(2)
    }

    @Test
    fun `percentile accumulates approximate t-digest input with contribution guard`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                percentile("state.amount", 95.0, "p95")
            },
            schema(),
        ).map { it.toBsonDocument() }

        val group = pipeline.first { it.containsKey("\$group") }.getDocument("\$group")
        val percentile = group.getDocument("p95").getDocument("\$percentile")
        percentile.getArray("p").map { it.asDouble().value }.assert().containsExactly(0.95)
        percentile.getString("method").value.assert().isEqualTo("approximate")
        group.getDocument("__wow_value_count_p95").containsKey("\$sum").assert().isTrue()
        val project = pipeline.first { it.containsKey("\$project") }.getDocument("\$project")
        val cond = project.getDocument("p95").getArray("\$cond")
        cond.get(1).isNull.assert().isTrue()
        val arrayElemAt = cond.get(2).asDocument().getArray("\$arrayElemAt")
        arrayElemAt.get(0).asString().value.assert().isEqualTo("\$p95")
        arrayElemAt.get(1).asInt32().value.assert().isEqualTo(0)
    }

    @Test
    fun `distinct count accumulates a set during grouping and projects set size`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                distinctCount("state.productId", "products")
            },
            schema(),
        ).map { it.toBsonDocument() }

        val group = pipeline.first { it.containsKey("\$group") }.getDocument("\$group")
        group.getDocument("products").containsKey("\$addToSet").assert().isTrue()
        group.toJson().assert().doesNotContain("\$push")
        val project = pipeline.first { it.containsKey("\$project") }.getDocument("\$project")
        val setUnion = project.getDocument("products").getDocument("\$size").getArray("\$setUnion")
        setUnion.assert().hasSize(1)
        val filter = setUnion[0].asDocument().getDocument("\$filter")
        val reduce = filter.getDocument("input").getDocument("\$reduce")
        reduce.getArray("initialValue").assert().isEmpty()
        val concatArrays = reduce.getDocument("in").getArray("\$concatArrays")
        concatArrays.get(0).asString().value.assert().isEqualTo("\$\$value")
        concatArrays.get(1).asDocument().containsKey("\$cond").assert().isTrue()
    }

    @Test
    fun `filtered count accumulates conditional ones`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation { count("paid") { "state.status" eq "PAID" } },
            statusFilterSchema,
        ).map { it.toBsonDocument() }

        val group = pipeline.first { it.containsKey("\$group") }.getDocument("\$group")
        val accumulator = group.getDocument("paid").getDocument("\$sum").getArray("\$cond")
        accumulator.get(1).asInt32().value.assert().isEqualTo(1)
        accumulator.get(2).asInt32().value.assert().isEqualTo(0)
        accumulator.get(0).asDocument().assert().isEqualTo(paidStatusGuard)
    }

    @Test
    fun `filtered numeric metrics guard contributions with the filter`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                sum("state.amount", "paidAmount") { "state.status" eq "PAID" }
                percentile("state.amount", 50.0, "paidP50") { "state.status" eq "PAID" }
            },
            statusFilterSchema,
        ).map { it.toBsonDocument() }

        val group = pipeline.first { it.containsKey("\$group") }.getDocument("\$group")
        group.getDocument("paidAmount").getDocument("\$sum").getArray("\$cond").get(0).asDocument()
            .assert().isEqualTo(paidStatusGuard)
        val countGuard = group.getDocument("__wow_value_count_paidAmount").getDocument("\$sum")
            .getArray("\$cond").get(0).asDocument()
        countGuard.getArray("\$and").get(0).asDocument().assert().isEqualTo(paidStatusGuard)
        countGuard.getArray("\$and").get(1).asDocument().containsKey("\$isNumber").assert().isTrue()
        group.getDocument("paidP50").getDocument("\$percentile").getDocument("input")
            .getArray("\$cond").get(0).asDocument().assert().isEqualTo(paidStatusGuard)
    }

    @Test
    fun `filtered distinct count and any null non matching records`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                distinctCount("state.productId", "paidProducts") { "state.status" eq "PAID" }
                any("state.status", "anyStatus") { "deleted" eq false }
            },
            statusFilterSchema,
        ).map { it.toBsonDocument() }

        val group = pipeline.first { it.containsKey("\$group") }.getDocument("\$group")
        group.getDocument("paidProducts").getDocument("\$addToSet").getArray("\$cond").get(0).asDocument()
            .assert().isEqualTo(paidStatusGuard)
        val anyGuard = group.getDocument("anyStatus").getDocument("\$max").getArray("\$cond")
        anyGuard.get(0).asDocument().assert().isEqualTo(
            BsonDocument("\$eq", BsonArray(listOf(BsonString("\$deleted"), BsonBoolean(false)))),
        )
        anyGuard.get(1).asString().value.assert().isEqualTo("\$state.status")
        anyGuard.get(2).isNull.assert().isTrue()
    }

    @Test
    fun `unfiltered metrics keep their unwrapped accumulators`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                count("count")
                sum("state.amount", "total")
            },
            schema(),
        ).map { it.toBsonDocument() }

        val group = pipeline.first { it.containsKey("\$group") }.getDocument("\$group")
        group.getDocument("count").get("\$sum").assert().isEqualTo(BsonInt32(1))
        group.getDocument("total").getDocument("\$sum").getArray("\$cond").get(0).asDocument()
            .containsKey("\$isArray").assert().isTrue()
        val countGuard = group.getDocument("__wow_value_count_total").getDocument("\$sum").getArray("\$cond")
        countGuard.get(0).asDocument().containsKey("\$isNumber").assert().isTrue()
        countGuard.get(0).asDocument().containsKey("\$and").assert().isFalse()
    }

    @Test
    fun `metric filters should guard null equality and inequality with type conditions`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                count("notAlpha") { "state.productName" ne "Alpha" }
                count("nullName") { "state.productName" eq null }
                count("notNullName") { "state.productName" ne null }
            },
            guardFilterSchema,
        ).map { it.toBsonDocument() }

        val group = pipeline.first { it.containsKey("\$group") }.getDocument("\$group")
        group.getDocument("notAlpha").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert()
            .isEqualTo(BsonDocument("\$ne", BsonArray(listOf(BsonString("\$state.productName"), BsonString("Alpha")))))
        group.getDocument("nullName").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            BsonDocument(
                "\$or",
                BsonArray(
                    listOf(
                        BsonDocument(
                            "\$eq",
                            BsonArray(listOf(BsonString("\$state.productName"), BsonNull.VALUE)),
                        ),
                        BsonDocument(
                            "\$eq",
                            BsonArray(
                                listOf(BsonDocument("\$type", BsonString("\$state.productName")), BsonString("missing"))
                            ),
                        ),
                    ),
                ),
            ),
        )
        group.getDocument("notNullName").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            BsonDocument(
                "\$and",
                BsonArray(
                    listOf(
                        BsonDocument(
                            "\$ne",
                            BsonArray(listOf(BsonString("\$state.productName"), BsonNull.VALUE)),
                        ),
                        BsonDocument(
                            "\$ne",
                            BsonArray(
                                listOf(
                                    BsonDocument("\$type", BsonString("\$state.productName")),
                                    BsonString("missing"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `metric filters should translate nin and nor into not guards`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                count("notInList") { "state.productName" notIn listOf("Alpha", "Beta") }
                count("neither") {
                    nor {
                        "state.productName" eq "Alpha"
                        "state.productName" eq "Beta"
                    }
                }
            },
            guardFilterSchema,
        ).map { it.toBsonDocument() }

        val group = pipeline.first { it.containsKey("\$group") }.getDocument("\$group")
        group.getDocument("notInList").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            BsonDocument(
                "\$not",
                BsonArray(
                    listOf(
                        BsonDocument(
                            "\$in",
                            BsonArray(
                                listOf(
                                    BsonString("\$state.productName"),
                                    BsonArray(listOf(BsonString("Alpha"), BsonString("Beta"))),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        group.getDocument("neither").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            BsonDocument(
                "\$not",
                BsonArray(
                    listOf(
                        BsonDocument(
                            "\$or",
                            BsonArray(
                                listOf(
                                    BsonDocument(
                                        "\$eq",
                                        BsonArray(listOf(BsonString("\$state.productName"), BsonString("Alpha"))),
                                    ),
                                    BsonDocument(
                                        "\$eq",
                                        BsonArray(listOf(BsonString("\$state.productName"), BsonString("Beta"))),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `metric filters should translate exists checks into type guards`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                count("present") { "state.productName".exists() }
                count("absent") { "state.productName".notExists() }
            },
            guardFilterSchema,
        ).map { it.toBsonDocument() }

        val group = pipeline.first { it.containsKey("\$group") }.getDocument("\$group")
        group.getDocument("present").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            BsonDocument(
                "\$ne",
                BsonArray(listOf(BsonDocument("\$type", BsonString("\$state.productName")), BsonString("missing"))),
            ),
        )
        group.getDocument("absent").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            BsonDocument(
                "\$eq",
                BsonArray(listOf(BsonDocument("\$type", BsonString("\$state.productName")), BsonString("missing"))),
            ),
        )
    }

    @Test
    fun `metric filters should translate in-lists into expression in guards`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                count("inList") { "state.productName" isIn listOf("Alpha", "Beta") }
            },
            guardFilterSchema,
        ).map { it.toBsonDocument() }

        pipeline.first { it.containsKey("\$group") }.getDocument("\$group")
            .getDocument("inList").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            BsonDocument(
                "\$in",
                BsonArray(
                    listOf(
                        BsonString("\$state.productName"),
                        BsonArray(listOf(BsonString("Alpha"), BsonString("Beta"))),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `metric filters should translate literal match regex leaves into regexMatch guards`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                count("contains") { "state.productName".containsText("Alpha") }
                count("startsWith") { "state.productName".startsWithText("Alpha") }
                count("endsWith") { "state.productName".endsWithText("2026") }
                count("containsIgnoreCase") {
                    "state.productName".containsText("alpha", StringComparison.CASE_INSENSITIVE)
                }
            },
            guardFilterSchema,
        ).map { it.toBsonDocument() }

        val group = pipeline.first { it.containsKey("\$group") }.getDocument("\$group")
        group.getDocument("contains").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            regexMatchGuard("Alpha"),
        )
        group.getDocument("startsWith").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            regexMatchGuard("^Alpha"),
        )
        group.getDocument("endsWith").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            regexMatchGuard("2026\$"),
        )
        group.getDocument("containsIgnoreCase").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert()
            .isEqualTo(regexMatchGuard("alpha", "i"))
    }

    @Test
    fun `dollar prefixed string operands are compared as literals`() {
        val rangeStringSchema = schema(
            field(
                "state.productName",
                QueryCapability.RANGE,
                "state.productName",
                additionalCapabilities = setOf(QueryCapability.EXACT_MATCH),
            ),
        )
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                count("pending") { "state.productName" eq "\$pending" }
                count("notPending") { "state.productName" ne "\$pending" }
                count("pendingList") { "state.productName" isIn listOf("\$pending", "Alpha") }
                count("missingList") { "state.productName" notIn listOf("\$pending") }
                count("above") { "state.productName" gt "\$pending" }
            },
            rangeStringSchema,
        ).map { it.toBsonDocument() }

        val group = pipeline.first { it.containsKey("\$group") }.getDocument("\$group")
        group.getDocument("pending").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            BsonDocument("\$eq", BsonArray(listOf(BsonString("\$state.productName"), literalString("\$pending")))),
        )
        group.getDocument("notPending").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            BsonDocument("\$ne", BsonArray(listOf(BsonString("\$state.productName"), literalString("\$pending")))),
        )
        group.getDocument("pendingList").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            BsonDocument(
                "\$in",
                BsonArray(
                    listOf(
                        BsonString("\$state.productName"),
                        BsonArray(listOf(literalString("\$pending"), BsonString("Alpha"))),
                    ),
                ),
            ),
        )
        group.getDocument("missingList").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            BsonDocument(
                "\$not",
                BsonArray(
                    listOf(
                        BsonDocument(
                            "\$in",
                            BsonArray(
                                listOf(
                                    BsonString("\$state.productName"),
                                    BsonArray(listOf(literalString("\$pending"))),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        group.getDocument("above").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            BsonDocument("\$gt", BsonArray(listOf(BsonString("\$state.productName"), literalString("\$pending")))),
        )
    }

    @Test
    fun `dollar prefixed literal match values stay escaped regex patterns`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation { count("startsWith") { "state.productName".startsWithText("\$pending") } },
            guardFilterSchema,
        ).map { it.toBsonDocument() }

        pipeline.first { it.containsKey("\$group") }.getDocument("\$group")
            .getDocument("startsWith").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            regexMatchGuard("^\\\$pending"),
        )
    }

    @Test
    fun `element scoped metric filters compile guards relative to their element`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                expand("state.orders")
                count("paid") { "status" eq "PAID" }
            },
            schema(),
        ).map { it.toBsonDocument() }

        val group = pipeline.single { it.containsKey("\$group") }.getDocument("\$group")
        group.getDocument("paid").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            BsonDocument("\$eq", BsonArray(listOf(BsonString("\$state.orders.status"), BsonString("PAID")))),
        )
    }

    @Test
    fun `range metric filters translate into comparison guards`() {
        val rangeSchema = schema(
            field(
                "state.amount",
                QueryCapability.RANGE,
                "state.amount",
                QueryValueType.DECIMAL,
                additionalCapabilities = setOf(QueryCapability.AGGREGATE_NUMERIC),
            ),
        )
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                count("above") { "state.amount" gt 10.0 }
                count("within") { "state.amount".between(1.0, 5.0) }
            },
            rangeSchema,
        ).map { it.toBsonDocument() }

        val group = pipeline.single { it.containsKey("\$group") }.getDocument("\$group")
        group.getDocument("above").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            BsonDocument("\$gt", BsonArray(listOf(BsonString("\$state.amount"), BsonDouble(10.0)))),
        )
        group.getDocument("within").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            BsonDocument(
                "\$and",
                BsonArray(
                    listOf(
                        BsonDocument("\$gte", BsonArray(listOf(BsonString("\$state.amount"), BsonDouble(1.0)))),
                        BsonDocument("\$lte", BsonArray(listOf(BsonString("\$state.amount"), BsonDouble(5.0)))),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `relative time metric filters normalize into one shared range instant`() {
        val temporalSchema = schema(
            field(
                "state.createdAt",
                QueryCapability.RANGE,
                "state.createdAt",
                QueryValueType.INTEGER,
                Temporal.Epoch(TimeUnit.SECONDS),
                additionalCapabilities = setOf(QueryCapability.AGGREGATE_TEMPORAL),
            ),
        )
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation { count("today") { "state.createdAt".today(ZoneId.of("UTC")) } },
            temporalSchema,
            java.time.Instant.parse("1970-01-02T12:00:00Z"),
        ).map { it.toBsonDocument() }

        val guard = pipeline.single { it.containsKey("\$group") }.getDocument("\$group")
            .getDocument("today").getDocument("\$sum").getArray("\$cond")[0].asDocument()
        guard.getArray("\$and")[0].asDocument().assert().isEqualTo(
            BsonDocument("\$gte", BsonArray(listOf(BsonString("\$state.createdAt"), BsonInt64(86400)))),
        )
        guard.getArray("\$and")[1].asDocument().assert().isEqualTo(
            BsonDocument("\$lt", BsonArray(listOf(BsonString("\$state.createdAt"), BsonInt64(172800)))),
        )
    }

    @Test
    fun `match none metric filters guard with an empty in list`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation { count("none") { matchNone() } },
            schema(),
        ).map { it.toBsonDocument() }

        pipeline.single { it.containsKey("\$group") }.getDocument("\$group")
            .getDocument("none").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            BsonDocument("\$in", BsonArray(listOf(BsonString("\$_id"), BsonArray()))),
        )
    }

    @Test
    fun `all state deletion metric filters guard with literal true`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation { count("every") { deletion(DeletionState.ALL) } },
            schema(),
        ).map { it.toBsonDocument() }

        pipeline.single { it.containsKey("\$group") }.getDocument("\$group")
            .getDocument("every").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert()
            .isEqualTo(BsonDocument("\$literal", BsonBoolean(true)))
    }

    @Test
    fun `or metric filters translate into or guards`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                count("either") {
                    or {
                        "state.status" eq "PAID"
                        "state.status" eq "SHIPPED"
                    }
                }
            },
            statusFilterSchema,
        ).map { it.toBsonDocument() }

        pipeline.single { it.containsKey("\$group") }.getDocument("\$group")
            .getDocument("either").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            BsonDocument(
                "\$or",
                BsonArray(
                    listOf(
                        BsonDocument("\$eq", BsonArray(listOf(BsonString("\$state.status"), BsonString("PAID")))),
                        BsonDocument("\$eq", BsonArray(listOf(BsonString("\$state.status"), BsonString("SHIPPED")))),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `and metric filters translate into and guards`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                count("both") {
                    and {
                        "state.status" eq "PAID"
                        "state.status" eq "SHIPPED"
                    }
                }
            },
            statusFilterSchema,
        ).map { it.toBsonDocument() }

        pipeline.single { it.containsKey("\$group") }.getDocument("\$group")
            .getDocument("both").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            BsonDocument(
                "\$and",
                BsonArray(
                    listOf(
                        BsonDocument("\$eq", BsonArray(listOf(BsonString("\$state.status"), BsonString("PAID")))),
                        BsonDocument("\$eq", BsonArray(listOf(BsonString("\$state.status"), BsonString("SHIPPED")))),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `map keyed collection metric filters are rejected as array fields`() {
        assertThrows<QuerySchemaValidationException> {
            MongoAggregationCompiler(SnapshotFilterCompiler).compile(
                aggregation { count("empty") { "state.labels.foo".isEmptyCollection() } },
                mapCollectionSchema,
            )
        }.message.assert().isEqualTo(
            "Aggregation metric filter field [state.labels.foo] must be scalar; array fields are not supported in metric filters.",
        )
    }

    @Test
    fun `map keyed array metric filter fields are rejected`() {
        assertThrows<QuerySchemaValidationException> {
            MongoAggregationCompiler(SnapshotFilterCompiler).compile(
                aggregation { count("labeled") { "state.labels.foo" eq "premium" } },
                mapCollectionSchema,
            )
        }.message.assert().isEqualTo(
            "Aggregation metric filter field [state.labels.foo] must be scalar; array fields are not supported in metric filters.",
        )
    }

    @Test
    fun `map keyed scalar metric filter fields keep equality guards`() {
        val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation { count("prod") { "state.env.stage" eq "prod" } },
            mapCollectionSchema,
        ).map { it.toBsonDocument() }

        pipeline.single { it.containsKey("\$group") }.getDocument("\$group")
            .getDocument("prod").getDocument("\$sum").getArray("\$cond")[0].asDocument().assert().isEqualTo(
            BsonDocument("\$eq", BsonArray(listOf(BsonString("\$state.env.stage"), BsonString("prod")))),
        )
    }

    @Test
    fun `map keyed element match metric filters are rejected`() {
        assertThrows<QuerySchemaValidationException> {
            MongoAggregationCompiler(SnapshotFilterCompiler).compile(
                aggregation { count("grouped") { "state.groups.foo".elementMatch { "name" eq "A" } } },
                mapCollectionSchema,
            )
        }.message.assert().isEqualTo(
            "Aggregation metric filters do not support [ELEMENT_MATCH].",
        )
    }

    @Test
    fun `union array metric filter fields are rejected`() {
        val schema = schema(
            QueryField("state.notes") to MongoTestField(
                QueryValueSchema(
                    QueryValueKind.UNION,
                    alternatives = listOf(
                        QueryValueSchema(
                            QueryValueKind.SCALAR,
                            valueTypes = setOf(QueryValueType.STRING),
                        ),
                        QueryValueSchema(
                            QueryValueKind.ARRAY,
                            items = QueryValueSchema(
                                QueryValueKind.SCALAR,
                                valueTypes = setOf(QueryValueType.STRING),
                            ),
                        ),
                    ),
                ),
                setOf(QueryCapability.EXACT_MATCH),
                "state.notes",
            ),
        )

        assertThrows<QuerySchemaValidationException> {
            MongoAggregationCompiler(SnapshotFilterCompiler).compile(
                aggregation { count("noted") { "state.notes" eq "premium" } },
                schema,
            )
        }.message.assert().isEqualTo(
            "Aggregation metric filter field [state.notes] must be scalar; array fields are not supported in metric filters.",
        )
    }

    @Test
    fun `metric filters cannot use text search`() {
        assertThrows<QuerySchemaValidationException> {
            MongoAggregationCompiler(SnapshotFilterCompiler).compile(
                aggregation { count("found") { "state.status" search "PAID" } },
                statusFilterSchema,
            )
        }
    }

    @Test
    fun `metric filters cannot use element match`() {
        assertThrows<QuerySchemaValidationException> {
            MongoAggregationCompiler(SnapshotFilterCompiler).compile(
                aggregation { count("orders") { "state.orders".elementMatch { "status" eq "PAID" } } },
                schema(),
            )
        }
    }

    @Test
    fun `metric filters cannot use contains all`() {
        assertThrows<QuerySchemaValidationException> {
            MongoAggregationCompiler(SnapshotFilterCompiler).compile(
                aggregation { count("tagged") { "state.status" containsAll listOf("A", "B") } },
                statusFilterSchema,
            )
        }
    }

    @Test
    fun `metric filters cannot filter on array valued fields`() {
        val schema = schema(
            QueryField("state.tags") to MongoTestField(
                QueryValueSchema(
                    kind = QueryValueKind.ARRAY,
                    items = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING)),
                ),
                setOf(QueryCapability.EXACT_MATCH),
                "state.tags",
            ),
        )

        assertThrows<QuerySchemaValidationException> {
            MongoAggregationCompiler(SnapshotFilterCompiler).compile(
                aggregation { count("tagged") { "state.tags" eq "promo" } },
                schema,
            )
        }.message.assert().isEqualTo(
            "Aggregation metric filter field [state.tags] must be scalar; array fields are not supported in metric filters.",
        )
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

private val statusFilterSchema = schema(
    field(
        "state.status",
        QueryCapability.EXACT_MATCH,
        "state.status",
        additionalCapabilities = setOf(QueryCapability.AGGREGATE_TERMS),
    ),
)

private val paidStatusGuard = BsonDocument(
    "\$eq",
    BsonArray(listOf(BsonString("\$state.status"), BsonString("PAID"))),
)

private fun literalString(value: String): BsonDocument = BsonDocument("\$literal", BsonString(value))

private fun regexMatchGuard(pattern: String, options: String? = null): BsonDocument {
    val condition = BsonDocument("input", BsonString("\$state.productName"))
        .append("regex", BsonString(pattern))
    options?.let { condition.append("options", BsonString(it)) }
    return BsonDocument(
        "\$cond",
        BsonArray(
            listOf(
                BsonDocument(
                    "\$eq",
                    BsonArray(
                        listOf(
                            BsonDocument("\$type", BsonString("\$state.productName")),
                            BsonString("string"),
                        ),
                    ),
                ),
                BsonDocument("\$regexMatch", condition),
                BsonBoolean(false),
            ),
        ),
    )
}

private val guardFilterSchema = schema(
    field(
        "state.productName",
        QueryCapability.LITERAL_MATCH,
        "state.productName",
        additionalCapabilities = setOf(QueryCapability.EXACT_MATCH, QueryCapability.PRESENCE),
    ),
)

/**
 * A map-of-lists schema: concrete map keys resolve to array values whose physical paths
 * carry [me.ahoo.wow.query.schema.QueryPathSegment.Key] templates, so metric filters over
 * them must be rejected by the scalar-field walk like any other array field.
 */
private val mapCollectionSchema = run {
    fun template(segments: List<QueryPathSegment>) = QueryPathTemplate(segments)
    val labelsKey = template(
        listOf(
            QueryPathSegment.Property("state"),
            QueryPathSegment.Property("labels"),
            QueryPathSegment.Key(0),
        ),
    )
    val envKey = template(
        listOf(
            QueryPathSegment.Property("state"),
            QueryPathSegment.Property("env"),
            QueryPathSegment.Key(0),
        ),
    )
    val groupsKey = template(
        listOf(
            QueryPathSegment.Property("state"),
            QueryPathSegment.Property("groups"),
            QueryPathSegment.Key(0),
        ),
    )
    val groupName = template(
        listOf(
            QueryPathSegment.Property("state"),
            QueryPathSegment.Property("groups"),
            QueryPathSegment.Key(0),
            QueryPathSegment.Item,
            QueryPathSegment.Property("name"),
        ),
    )
    fun bindings(path: QueryPathTemplate, capabilities: Set<QueryCapability>) = QueryValueBindings(
        bindings = capabilities.associateWith { QueryFieldBindingTemplate(path, storageTypes = null) },
        projectionPath = path,
        responsePath = path,
    )
    val definition = LogicalQuerySchema(
        QueryValueSchema(
            QueryValueKind.OBJECT,
            properties = mapOf(
                "state" to QueryValueSchema(
                    QueryValueKind.OBJECT,
                    properties = mapOf(
                        "labels" to QueryValueSchema(
                            QueryValueKind.OBJECT,
                            additionalProperties = QueryValueSchema(
                                QueryValueKind.ARRAY,
                                items = QueryValueSchema(
                                    QueryValueKind.SCALAR,
                                    valueTypes = setOf(QueryValueType.STRING),
                                ),
                            ),
                        ),
                        "env" to QueryValueSchema(
                            QueryValueKind.OBJECT,
                            additionalProperties = QueryValueSchema(
                                QueryValueKind.SCALAR,
                                valueTypes = setOf(QueryValueType.STRING),
                            ),
                        ),
                        "groups" to QueryValueSchema(
                            QueryValueKind.OBJECT,
                            additionalProperties = QueryValueSchema(
                                QueryValueKind.ARRAY,
                                items = QueryValueSchema(
                                    QueryValueKind.OBJECT,
                                    properties = mapOf(
                                        "name" to QueryValueSchema(
                                            QueryValueKind.SCALAR,
                                            valueTypes = setOf(QueryValueType.STRING),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )
    QueryModelSchema(
        QueryModel.SNAPSHOT,
        emptySet(),
        definition,
        mapOf(
            labelsKey to bindings(labelsKey, setOf(QueryCapability.PRESENCE, QueryCapability.EXACT_MATCH)),
            envKey to bindings(envKey, setOf(QueryCapability.EXACT_MATCH)),
            groupsKey to bindings(groupsKey, setOf(QueryCapability.ELEMENT_SCOPE, QueryCapability.PRESENCE)),
            groupName to bindings(groupName, setOf(QueryCapability.EXACT_MATCH)),
        ),
    )
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
