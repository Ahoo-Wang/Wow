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

package me.ahoo.wow.query.aggregation

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.QueryBackendStorages
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.memory.InMemoryDatasetStorage
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

/**
 * PREVIOUS along an hourly histogram in a zone with daylight saving time (#5482): a step is one bucket of the
 * histogram's own sequence, so it reaches the bucket before across both transitions, never its own, and the range it
 * reads moves with it.
 */
class PreviousAcrossTransitionsTest {
    private val at = QueryField("at")
    private val hour = AggregationGroup.DateHistogram(at, "hour", AggregationDateUnit.HOUR, "America/New_York")

    private fun epoch(text: String): Long = Instant.parse(text).toEpochMilli()

    @Test
    fun `a step reaches the bucket before across a spring gap and a repeated hour`() {
        val step = PreviousStep(hour, 1, null)
        // 03:00 EDT follows 01:00 EST: 02:00 never happened.
        step.previousKey(epoch("2025-03-09T07:00:00Z")).assert().isEqualTo(epoch("2025-03-09T06:00:00Z"))
        // The second 01:00 (EST) follows the first (EDT).
        step.previousKey(epoch("2025-11-02T06:00:00Z")).assert().isEqualTo(epoch("2025-11-02T05:00:00Z"))
        step.previousKey(epoch("2025-11-02T07:00:00Z")).assert().isEqualTo(epoch("2025-11-02T06:00:00Z"))
        PreviousStep(hour, 2, null).previousKey(epoch("2025-11-02T07:00:00Z")).assert()
            .isEqualTo(epoch("2025-11-02T05:00:00Z"))
        // A step of a period keeps the local time: 01:30 the day before is in that day's 01:00.
        PreviousStep(hour, 1, AggregationDateUnit.DAY).previousKey(epoch("2025-11-03T06:00:00Z")).assert()
            .isEqualTo(epoch("2025-11-02T05:00:00Z"))
    }

    @Test
    fun `the range a step reads moves with its buckets`() {
        val step = PreviousStep(hour, 1, null)
        // The 03:00 EDT hour of 2025-03-09, and its own half hour: back by one bucket is the 01:00 EST hour.
        val range = AndFilter(
            listOf(
                GreaterThanOrEqualFilter(at, epoch("2025-03-09T07:00:00Z")),
                LessThanFilter(at, epoch("2025-03-09T07:30:00Z")),
            ),
        )
        val shifted = step.shift(range, null) as AndFilter
        (shifted.operands[0] as GreaterThanOrEqualFilter).value.assert().isEqualTo(epoch("2025-03-09T06:00:00Z"))
        (shifted.operands[1] as LessThanFilter).value.assert().isEqualTo(epoch("2025-03-09T06:30:00Z"))
        // ISO text moves alike and keeps its form.
        (step.shift(GreaterThanOrEqualFilter(at, "2025-11-02T06:00:00Z"), null) as GreaterThanOrEqualFilter).value
            .assert().isEqualTo("2025-11-02T05:00:00Z")
    }

    @Test
    fun `an hourly PREVIOUS reads the earlier hour across both transitions`(): Unit = runBlocking {
        val spring = previous(listOf("2025-03-09T06:30:00Z" to 10, "2025-03-09T07:30:00Z" to 20))
        spring.getValue(epoch("2025-03-09T07:00:00Z")).assert().isEqualTo(10.0)
        val fall = previous(listOf("2025-11-02T05:30:00Z" to 10, "2025-11-02T06:30:00Z" to 20))
        fall.getValue(epoch("2025-11-02T06:00:00Z")).assert().isEqualTo(10.0)
    }

    /** Each hour's PREVIOUS amount, by bucket key, over records of [amounts] at their instants. */
    private suspend fun previous(amounts: List<Pair<String, Int>>): Map<Long, Double?> {
        val storage = InMemoryDatasetStorage()
        val rows = amounts.mapIndexed { index, (instant, amount) ->
            WowJson.mapper.createObjectNode().put("id", "r$index").put("tenant", "t")
                .put("at", epoch(instant)).put("amount", amount) as ObjectNode
        }
        val dataset = QueryDataset.of(AggregateType("dst", "previous")) {
            tenant("tenant")
            identity("id")
            storage(storage.put("previous", rows))
            field("id") { types(QueryValueType.STRING) }
            field("tenant") { types(QueryValueType.STRING) }
            field("at") {
                types(QueryValueType.INTEGER)
                temporalEpoch()
            }
            field("amount") { types(QueryValueType.INTEGER) }
        }
        val datasets = QueryDatasets(listOf(dataset))
        val catalog = QuerySchemaCatalog(
            storages = QueryBackendStorages(datasetBindings = storage, datasets = datasets),
            compiler = QueryModelCompiler.of(datasets = datasets),
            datasets = datasets,
        )
        val schema = catalog.schema(dataset.name, QueryModel.DATASET)
        val query = AggregationQuery(
            groupBy = listOf(hour),
            metrics = listOf(
                AggregationMetric.Numeric(
                    AggregationFunction.SUM,
                    AggregationExpression.Field(QueryField("amount")),
                    "amount",
                ),
                AggregationMetric.Derived("previous", DerivedExpression.Previous("amount", "hour")),
            ),
            sort = listOf(Sort(QueryField("hour"), Sort.Direction.ASC)),
        )
        val backend = checkNotNull(storage.binding(dataset)).backend
        return backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList().associate { row ->
            row.path("hour").longValue() to row.path("previous").takeUnless { it.isNull }?.doubleValue()
        }
    }
}
