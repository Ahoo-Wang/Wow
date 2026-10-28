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

package me.ahoo.wow.benchmark.transfer

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.CompositeHealthContributor
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.registry.DefaultHealthContributorRegistry

/** The per-second line of the stores' query bounds, read from the node's `wow` health contributor. */
class BenchQueryLimitsTest {
    private val queries = mapOf(
        "mongo" to linkedMapOf(
            "limit" to 12,
            "ceiling" to 50,
            "running" to 3,
            "queued" to 0,
            "adaptive" to true,
            "floor" to 1,
            "pressure" to true,
            "decreases" to 1,
            "appendP90Millis" to 30,
            "appendBaselineMillis" to 10,
            "waitingForConnection" to 2,
        ),
        "postgres" to linkedMapOf("limit" to 8, "ceiling" to 8, "running" to 0, "queued" to 0, "adaptive" to false),
    )

    @Test
    fun `a snapshot should say each store's bound as the node's health does`() {
        val registry = DefaultHealthContributorRegistry()
        registry.registerContributor(
            BenchQueryLimits.WOW,
            CompositeHealthContributor.fromMap(
                mapOf(BenchQueryLimits.QUERIES to HealthIndicator { Health.up().withDetails(queries).build() }),
            ),
        )
        val line = BenchQueryLimits { BenchQueryLimits.of(registry) }.snapshot()

        line!!.substringAfter(',').assert().isEqualTo(
            "\"mongo\":{\"limit\":12,\"ceiling\":50,\"running\":3,\"queued\":0,\"adaptive\":true,\"floor\":1," +
                "\"pressure\":true,\"decreases\":1,\"appendP90Millis\":30,\"appendBaselineMillis\":10," +
                "\"waitingForConnection\":2}," +
                "\"postgres\":{\"limit\":8,\"ceiling\":8,\"running\":0,\"queued\":0,\"adaptive\":false}}",
        )
    }

    @Test
    fun `a node without the queries component should log nothing`() {
        BenchQueryLimits { BenchQueryLimits.of(DefaultHealthContributorRegistry()) }.snapshot().assert().isNull()
        BenchQueryLimits { emptyMap() }.snapshot().assert().isNull()
    }
}
