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
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Condition
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.junit.jupiter.api.Test

class MongoAggregationCompilerTest {
    @Test
    fun `should unwind and filter every element before counting`() {
        val pipeline = MongoAggregationCompiler.compile(
            AggregationQuery(
                condition = Condition.eq("aggregateId", "id"),
                elements = listOf(
                    AggregationElement("state.orders", Condition.eq("state.orders.status", "PAID")),
                    AggregationElement("state.orders.lines", Condition.eq("state.orders.lines.cancelled", false)),
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
            SnapshotConditionConverter,
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
    }
}
