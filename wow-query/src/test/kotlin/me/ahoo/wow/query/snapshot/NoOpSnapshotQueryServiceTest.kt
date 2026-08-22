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

package me.ahoo.wow.query.snapshot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.pagedQuery
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class NoOpSnapshotQueryServiceTest {
    private val queryService = NoOpSnapshotQueryServiceFactory.create<Any>("test.test".toNamedAggregate())

    @Test
    fun `should return no op name`() {
        queryService.name.assert().isEqualTo("no_op")
    }

    @Test
    fun `should return named aggregate`() {
        queryService.namedAggregate.assert().isEqualTo("test.test".toNamedAggregate())
    }

    @Test
    fun `should execute single query`() {
        me.ahoo.wow.query.dsl.singleQuery {
            condition {
                "test" eq "test"
            }
        }.query(queryService)
            .test()
            .verifyComplete()
    }

    @Test
    fun `should execute single dynamic query`() {
        me.ahoo.wow.query.dsl.singleQuery {
            condition {
                "test" eq "test"
            }
        }.dynamicQuery(queryService)
            .test()
            .verifyComplete()
    }

    @Test
    fun `should execute list query`() {
        listQuery {
            condition {
                "test" eq "test"
            }
        }.query(queryService)
            .test()
            .verifyComplete()
    }

    @Test
    fun `should execute list dynamic query`() {
        listQuery {
            condition {
                "test" eq "test"
            }
        }.dynamicQuery(queryService)
            .test()
            .verifyComplete()
    }

    @Test
    fun `should execute paged query`() {
        pagedQuery {
            condition {
                "test" eq "test"
            }
        }.query(queryService)
            .test()
            .consumeNextWith {
                it.total.assert().isZero()
            }
            .verifyComplete()
    }

    @Test
    fun `should execute paged dynamic query`() {
        pagedQuery {
            condition {
                "test" eq "test"
            }
        }.dynamicQuery(queryService)
            .test()
            .consumeNextWith {
                it.total.assert().isZero()
            }
            .verifyComplete()
    }

    @Test
    fun `should return zero count`() {
        condition {
            "test" eq "test"
        }.count(queryService)
            .test()
            .expectNext(0L)
            .verifyComplete()
    }

    @Test
    fun `should preserve aggregation empty set semantics`() {
        queryService.aggregate(
            AggregationQuery(
                metrics = listOf(
                    AggregationMetric.Count("count"),
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field("version"),
                        "sum",
                    ),
                    AggregationMetric.Numeric(
                        AggregationFunction.MAX,
                        AggregationExpression.Field("version"),
                        "max",
                    ),
                ),
            ),
        ).test()
            .assertNext { row ->
                row.getValue<Long>("count").assert().isZero()
                row.getValue<Double>("sum").assert().isEqualTo(0.0)
                row["max"].assert().isNull()
            }
            .verifyComplete()

        queryService.aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms("aggregateId", "aggregateId")),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        ).test().verifyComplete()
    }
}
