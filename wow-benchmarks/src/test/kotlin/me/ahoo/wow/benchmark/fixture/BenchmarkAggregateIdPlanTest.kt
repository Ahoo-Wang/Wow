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

package me.ahoo.wow.benchmark.fixture

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BenchmarkAggregateIdPlanTest {

    @Test
    fun `should represent high cardinality without fixed ids`() {
        val plan = BenchmarkAggregateIdPlan.create(
            cardinality = BenchmarkAggregateIdPlan.HIGH_CARDINALITY,
            stripeCount = 4,
        )

        plan.isHighCardinality.assert().isTrue()
        plan.aggregateIds.assert().isEmpty()
        plan.slowAggregateId.assert().isNull()
        plan.aggregateIdAt(0).assert().isNull()
    }

    @Test
    fun `should distribute fixed ids evenly across available stripes`() {
        val plan = BenchmarkAggregateIdPlan.create(cardinality = "16", stripeCount = 4)
        val stripeCounts = plan.aggregateIds
            .groupingBy { plan.stripeOf(it, 4) }
            .eachCount()

        plan.isHighCardinality.assert().isFalse()
        plan.aggregateIds.assert().hasSize(16)
        stripeCounts.keys.assert().hasSize(4)
        stripeCounts.values.toSet().assert().isEqualTo(setOf(4))
        plan.slowAggregateId.assert().isEqualTo(plan.aggregateIds.first())
    }

    @Test
    fun `should use one stripe per id when cardinality is below stripe count`() {
        val plan = BenchmarkAggregateIdPlan.create(cardinality = "16", stripeCount = 64)

        plan.activeStripes(64)
            .assert()
            .hasSize(16)
        plan.aggregateIdAt(0).assert().isEqualTo(plan.aggregateIds[0])
        plan.aggregateIdAt(16).assert().isEqualTo(plan.aggregateIds[0])
        plan.aggregateIdAt(-1).assert().isEqualTo(plan.aggregateIds[15])
    }

    @Test
    fun `should place fixed ids on the same stripe when requested`() {
        val plan = BenchmarkAggregateIdPlan.create(
            cardinality = "4",
            stripeCount = 16,
            placement = BenchmarkAggregateIdPlacement.SAME_STRIPE,
        )

        plan.aggregateIds.toSet().assert().hasSize(4)
        plan.activeStripes(16)
            .assert()
            .isEqualTo(setOf(0))
    }

    @Test
    fun `should reject invalid cardinality and stripe count`() {
        assertThrows<IllegalArgumentException> {
            BenchmarkAggregateIdPlan.create(cardinality = "0", stripeCount = 4)
        }
        assertThrows<IllegalArgumentException> {
            BenchmarkAggregateIdPlan.create(cardinality = "invalid", stripeCount = 4)
        }
        assertThrows<IllegalArgumentException> {
            BenchmarkAggregateIdPlan.create(cardinality = "16", stripeCount = 0)
        }
    }
}
