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

import com.mongodb.MongoClientSettings
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.dsl.filter
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.junit.jupiter.api.Test

class MongoAggregationCompilerTest {
    @Test
    fun `should unwind and filter every element before counting`() {
        val pipeline = MongoAggregationCompiler.compile(
            AggregationQuery(
                filter = filter { "aggregateId" eq "id" },
                elements = listOf(
                    AggregationElement("state.orders", filter { "state.orders.status" eq "PAID" }),
                    AggregationElement("state.orders.lines", filter { "state.orders.lines.cancelled" eq false }),
                ),
                groupBy = listOf(AggregationGroup.Terms("state.orders.lines.sku", "sku")),
                metrics = listOf(
                    AggregationMetric.Count("count"),
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field("state.orders.lines.amount"),
                        "amount",
                    ),
                ),
            ),
            SnapshotFilterConverter,
        ).map {
            it.toBsonDocument(BsonDocument::class.java, MongoClientSettings.getDefaultCodecRegistry())
        }

        pipeline.map { it.keys.single() }.assert().containsExactly(
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
        val group = pipeline.first { "\$group" in it }.getDocument("\$group")
        group.getDocument("count").assert().isEqualTo(BsonDocument("\$sum", BsonInt32(1)))
        pipeline[2].toString().assert().contains("\$type")
        pipeline[4].toString().assert().contains("\$type")
    }

    @Test
    fun `should reject null element rows even without an element filter`() {
        val pipeline = MongoAggregationCompiler.compile(
            AggregationQuery(
                elements = listOf(AggregationElement("state.orders")),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
            SnapshotFilterConverter,
        ).map {
            it.toBsonDocument(BsonDocument::class.java, MongoClientSettings.getDefaultCodecRegistry())
        }

        pipeline.map { it.keys.single() }.assert().containsExactly(
            "\$match",
            "\$unwind",
            "\$match",
            "\$group",
            "\$project",
        )
        pipeline[2].toString().assert().contains("\$type")
    }

    @Test
    fun `should compile portable bucket and metric operators`() {
        val pipeline = MongoAggregationCompiler.compile(
            AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.Histogram("state.amount", "band", 10.0),
                    AggregationGroup.DateHistogram(
                        "state.createdAt",
                        "week",
                        AggregationDateUnit.WEEK,
                        "Asia/Shanghai",
                    ),
                ),
                metrics = AggregationFunction.entries.map { function ->
                    AggregationMetric.Numeric(
                        function,
                        AggregationExpression.Field("state.amount"),
                        function.name.lowercase(),
                    )
                },
                sort = listOf(Sort("sum", Sort.Direction.DESC)),
            ),
            SnapshotFilterConverter,
        ).map {
            it.toBsonDocument(BsonDocument::class.java, MongoClientSettings.getDefaultCodecRegistry())
        }

        val group = pipeline.first { "\$group" in it }.getDocument("\$group")
        group.getDocument("_id").getDocument("band").containsKey("\$multiply").assert().isTrue()
        val dateTrunc = group.getDocument("_id").getDocument("week").getDocument("\$dateTrunc")
        dateTrunc.getString("unit").value.assert().isEqualTo("week")
        dateTrunc.getString("timezone").value.assert().isEqualTo("Asia/Shanghai")
        dateTrunc.getString("startOfWeek").value.assert().isEqualTo("monday")
        mapOf("sum" to "\$sum", "avg" to "\$avg", "min" to "\$min", "max" to "\$max")
            .forEach { (alias, operator) -> group.getDocument(alias).containsKey(operator).assert().isTrue() }
        val sort = pipeline.first { "\$sort" in it }.getDocument("\$sort")
        sort.getInt32("sum").value.assert().isEqualTo(-1)
        sort.getInt32("band").value.assert().isOne()
        sort.getInt32("week").value.assert().isOne()
    }
}
