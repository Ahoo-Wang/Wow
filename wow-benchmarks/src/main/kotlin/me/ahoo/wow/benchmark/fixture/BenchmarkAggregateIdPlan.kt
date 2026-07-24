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

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.modeling.DefaultAggregateId

enum class BenchmarkAggregateIdPlacement {
    BALANCED,
    SAME_STRIPE,
}

/**
 * Deterministic aggregate-ID plan for dispatcher cardinality benchmarks.
 *
 * [BenchmarkAggregateIdPlacement.BALANCED] distributes finite IDs across as many
 * ordering stripes as their cardinality permits, avoiding accidental String-hash
 * skew. [BenchmarkAggregateIdPlacement.SAME_STRIPE] deliberately creates a
 * collision control. [HIGH_CARDINALITY] keeps the per-command fresh-ID path.
 */
class BenchmarkAggregateIdPlan private constructor(
    val aggregateIds: List<String>,
) {
    val isHighCardinality: Boolean
        get() = aggregateIds.isEmpty()

    val slowAggregateId: String?
        get() = aggregateIds.firstOrNull()

    fun aggregateIdAt(sequence: Int): String? {
        if (isHighCardinality) {
            return null
        }
        return aggregateIds[Math.floorMod(sequence, aggregateIds.size)]
    }

    fun stripeOf(
        aggregateId: String,
        stripeCount: Int,
    ): Int {
        require(stripeCount > 0) {
            "stripeCount must be greater than 0."
        }
        return resolveStripe(aggregateId, stripeCount)
    }

    fun activeStripes(stripeCount: Int): Set<Int> =
        aggregateIds.mapTo(mutableSetOf()) { stripeOf(it, stripeCount) }

    companion object {
        const val HIGH_CARDINALITY = "high"
        private const val MAX_FIXED_CARDINALITY = 4096

        fun create(
            cardinality: String,
            stripeCount: Int,
            placement: BenchmarkAggregateIdPlacement = BenchmarkAggregateIdPlacement.BALANCED,
        ): BenchmarkAggregateIdPlan {
            require(stripeCount > 0) {
                "stripeCount must be greater than 0."
            }
            if (cardinality == HIGH_CARDINALITY) {
                return BenchmarkAggregateIdPlan(emptyList())
            }
            val resolvedCardinality = cardinality.toIntOrNull()
            require(resolvedCardinality != null && resolvedCardinality in 1..MAX_FIXED_CARDINALITY) {
                "aggregateCardinality must be '$HIGH_CARDINALITY' or an integer between 1 and " +
                    "$MAX_FIXED_CARDINALITY."
            }
            return BenchmarkAggregateIdPlan(
                createAggregateIds(
                    cardinality = resolvedCardinality,
                    stripeCount = stripeCount,
                    placement = placement,
                ),
            )
        }

        private fun createAggregateIds(
            cardinality: Int,
            stripeCount: Int,
            placement: BenchmarkAggregateIdPlacement,
        ): List<String> {
            val activeStripeCount = minOf(cardinality, stripeCount)
            return List(cardinality) { index ->
                val targetStripe = when (placement) {
                    BenchmarkAggregateIdPlacement.BALANCED -> index.mod(activeStripeCount)
                    BenchmarkAggregateIdPlacement.SAME_STRIPE -> 0
                }
                findAggregateId(
                    ordinal = index,
                    targetStripe = targetStripe,
                    stripeCount = stripeCount,
                )
            }
        }

        private fun findAggregateId(
            ordinal: Int,
            targetStripe: Int,
            stripeCount: Int,
        ): String {
            var attempt = 0
            while (true) {
                val candidate = "${BenchmarkAggregates.FIXED_AGGREGATE_ID}-$ordinal-$attempt"
                if (resolveStripe(candidate, stripeCount) == targetStripe) {
                    return candidate
                }
                check(attempt < Int.MAX_VALUE) {
                    "Unable to find aggregate ID for stripe $targetStripe."
                }
                attempt++
            }
        }

        private fun resolveStripe(
            aggregateId: String,
            stripeCount: Int,
        ): Int =
            with(MessageParallelism) {
                BenchmarkAggregateIdCapable(aggregateId).toGroupKey(stripeCount)
            }
    }
}

private class BenchmarkAggregateIdCapable(
    aggregateId: String,
) : AggregateIdCapable {
    override val aggregateId: AggregateId = DefaultAggregateId(
        namedAggregate = BenchmarkAggregates.namedAggregate,
        id = aggregateId,
    )
}
