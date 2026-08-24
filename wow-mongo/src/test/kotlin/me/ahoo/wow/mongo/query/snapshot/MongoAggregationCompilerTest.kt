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
import me.ahoo.wow.mongo.query.AbstractMongoFilterConverter
import me.ahoo.wow.query.converter.FieldConverter
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.serialization.MessageRecords
import org.junit.jupiter.api.Test
import java.time.ZoneId

class MongoAggregationCompilerTest {

    @Test
    fun `compiler should unwind and filter every relative element`() {
        val query = aggregation {
            expand("state.orders") { "status" eq "PAID" }
            expand("lines") { "quantity" gt 0 }
            terms("productId", "product")
            count("count")
        }

        val pipeline = MongoAggregationCompiler(SnapshotFilterConverter).compile(query)
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

        val pipeline = MongoAggregationCompiler(SnapshotFilterConverter).compile(query)
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

        MongoAggregationCompiler(SnapshotFilterConverter).compile(query)
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

        MongoAggregationCompiler(SnapshotFilterConverter).compile(query)
            .first { it.toBsonDocument().containsKey("\$group") }
            .toBsonDocument().toJson().assert().contains("\"timezone\": \"UTC\"")
    }

    @Test
    fun `summary compiler should retain contribution counts`() {
        val query = aggregation { sum("state.amount", "total") }

        MongoAggregationCompiler(SnapshotFilterConverter).compile(query)
            .joinToString { it.toBsonDocument().toJson() }
            .assert().contains("__wow_value_count_total")
    }

    @Test
    fun `element filter should not restore active deletion scope`() {
        val query = aggregation {
            filter(DeletionFilter(DeletionState.DELETED))
            expand("state.items") { "quantity" gt 0 }
            count("count")
        }

        val pipeline = MongoAggregationCompiler(SnapshotFilterConverter).compile(query)
        pipeline[0].toBsonDocument().toJson().assert().contains("\"deleted\": true")
        pipeline[2].toBsonDocument().toJson().assert().doesNotContain("deleted")
    }

    @Test
    fun `root aggregate id leaf should use Mongo primary key`() {
        val query = aggregation {
            terms(MessageRecords.AGGREGATE_ID, "aggregate")
            count("count")
        }

        val pipeline = MongoAggregationCompiler(SnapshotFilterConverter).compile(query)
        pipeline[1].toBsonDocument().toJson().assert()
            .contains("\"_id\"")
            .doesNotContain(MessageRecords.AGGREGATE_ID)
        pipeline[2].toBsonDocument().toJson().assert().contains("\"\$_id\"")
    }

    @Test
    fun `numeric contribution count should accept only Mongo numeric values`() {
        val query = aggregation { sum("state.amount", "total") }

        val group = MongoAggregationCompiler(SnapshotFilterConverter).compile(query)[1]
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

        val group = MongoAggregationCompiler(SnapshotFilterConverter).compile(query)[1]
            .toBsonDocument().getDocument("\$group")
        listOf("minimum", "maximum").forEach { alias ->
            group.getDocument(alias).toJson().assert()
                .contains("\$cond")
                .contains("\$isNumber")
        }
    }

    @Test
    fun `custom field converter should apply to root and element filters without element deletion scope`() {
        val converter = object : AbstractMongoFilterConverter() {
            override val fieldConverter: FieldConverter = FieldConverter { "physical.$it" }
        }
        val query = aggregation {
            filter { "state.status" eq "PAID" }
            expand("state.items") { "quantity" gt 0 }
            count("count")
        }

        val pipeline = MongoAggregationCompiler(converter).compile(query)
        pipeline[0].toBsonDocument().toJson().assert().contains("physical.state.status")
        pipeline[2].toBsonDocument().toJson().assert()
            .contains("physical.state.items.quantity")
            .doesNotContain("deleted")
    }
}
