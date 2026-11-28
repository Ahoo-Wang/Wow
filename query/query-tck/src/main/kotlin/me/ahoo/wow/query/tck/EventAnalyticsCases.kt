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

package me.ahoo.wow.query.tck
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationGroupScope
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.tck.EventQueryBackendSpec.Companion.TENANT
import org.junit.jupiter.api.Test

/**
 * The per-event analytics cases of the `events` contract: aggregations that expand the events. They read only
 * [EventQueryFixtures]'s fixtures and hooks; see [EventQueryFixtures] for the chain of families it is part of.
 */
@WowSpi
abstract class EventAnalyticsCases internal constructor() : EventQueryFixtures() {
    // Per-event analytics expand the events

    @Test
    fun `an aggregation counts commits at the root`() = blocking {
        val fixture = given()
        val row = fixture.aggregate(
            AggregationQuery(TenantIdFilter(TENANT), metrics = listOf(AggregationMetric.Count("commits")))
        )
            .single()
        row.path("commits").longValue().assert().isEqualTo(6L)
        Unit
    }

    @Test
    fun `an expanded aggregation counts the events, retired ones left out`() = blocking {
        val fixture = given()
        val row = fixture.aggregate(
            AggregationQuery(
                elements = listOf(AggregationElement(EVENTS)),
                metrics = listOf(AggregationMetric.Count("events"))
            ),
        ).single()
        row.path("events").longValue().assert().isEqualTo(7L)
        Unit
    }

    @Test
    fun `an expanded aggregation sums an event field per event, aliases included`() = blocking {
        val fixture = given()
        val row = fixture.aggregate(
            AggregationQuery(
                elements = listOf(AggregationElement(EVENTS, name("ItemAdded"))),
                metrics = listOf(
                    AggregationMetric.Count("items"),
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field(QueryField("body.qty")),
                        "qty"
                    ),
                ),
            ),
        ).single()
        row.path("items").longValue().assert().isEqualTo(3L)
        row.path("qty").doubleValue().assert().isEqualTo(6.0)
        Unit
    }

    @Test
    fun `an expanded aggregation groups by a body path only the events that declare it`() = blocking {
        val fixture = given(undeclaredStreams())
        val rows = fixture.aggregate(
            AggregationQuery(
                elements = listOf(AggregationElement(EVENTS)),
                groupBy = listOf(AggregationGroup.Terms(SKU, "sku")),
                metrics = listOf(AggregationMetric.Count("events")),
            ),
        )
        // Not the events that do not declare it, whatever their bodies hold: no key of theirs, and no null one.
        rows.map { it.path("sku").stringValue() to it.path("events").longValue() }.assert().containsExactly("A" to 1L)
        Unit
    }

    @Test
    fun `an expanded aggregation measures a body path only on the events that declare it`() = blocking {
        val fixture = given(undeclaredStreams())
        val row = fixture.aggregate(
            AggregationQuery(
                elements = listOf(AggregationElement(EVENTS)),
                metrics = listOf(
                    AggregationMetric.Count("events"),
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field(QTY),
                        "qty",
                    ),
                    AggregationMetric.Count("unknown", EqualFilter(SKU, UNKNOWN_SKU)),
                ),
            ),
        ).single()
        // Every event is counted, as a list shows each; only the one that declares the path is measured by it.
        row.path("events").longValue().assert().isEqualTo(5L)
        row.path("qty").doubleValue().assert().isEqualTo(1.0)
        row.path("unknown").longValue().assert().isEqualTo(0L)
        Unit
    }

    @Test
    fun `an expanded aggregation groups events by a field of their commit`() = blocking {
        val fixture = given()
        assumeSupported(
            fixture.schema.storage.aggregation.rootGroups,
            "storage.aggregation.rootGroups",
            "groups by a root field"
        )
        val rows = fixture.aggregate(
            AggregationQuery(
                elements = listOf(AggregationElement(EVENTS)),
                groupBy = listOf(
                    AggregationGroup.Terms(QueryField("aggregateId"), "aggregate", scope = AggregationGroupScope.ROOT)
                ),
                metrics = listOf(AggregationMetric.Count("events")),
            ),
        )
        rows.map { it.path("aggregate").stringValue() to it.path("events").longValue() }
            .assert().containsExactly("o1" to 4L, "o2" to 2L, "o3" to 1L)
        Unit
    }

    @Test
    fun `a root metric over an event field is rejected`() {
        rejected(QueryErrorCodes.ELEMENT_SCOPE_REQUIRED) {
            given().aggregate(
                AggregationQuery(
                    metrics = listOf(
                        AggregationMetric.Numeric(
                            AggregationFunction.SUM,
                            AggregationExpression.Field(QueryField("events.body.qty")),
                            "qty",
                        ),
                    ),
                ),
            )
        }
    }
}
