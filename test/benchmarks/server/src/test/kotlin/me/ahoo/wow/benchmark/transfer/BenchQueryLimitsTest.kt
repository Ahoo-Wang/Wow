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

@file:OptIn(InternalWowApi::class)

package me.ahoo.wow.benchmark.transfer

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.query.admission.AdaptiveQueryLimit
import me.ahoo.wow.query.admission.QueryBulkhead
import me.ahoo.wow.query.store.QueryStores
import me.ahoo.wow.spi.store.WritePressure
import me.ahoo.wow.spi.store.WritePressureSample
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/** The per-second line of the stores' query bounds: a fixed bound, and one the write pressure lowered. */
class BenchQueryLimitsTest {
    @Test
    fun `a snapshot should say each store's bound, and what moved an adaptive one`() {
        val fixed = QueryBulkhead("postgres", maxConcurrent = 8)
        val adaptive = QueryBulkhead("mongo", maxConcurrent = 25, ceiling = 50)
        val pressed = WritePressureSample(appends = 120, appendP90 = 30.milliseconds, baseline = 10.milliseconds, 2)
        val limit = AdaptiveQueryLimit(adaptive, WritePressure { pressed })
        // Two pressing periods in a row halve the bound.
        limit.evaluate()
        limit.evaluate()
        val line = BenchQueryLimits(QueryStores(mapOf("postgres" to fixed, "mongo" to adaptive), listOf(limit)))
            .snapshot()

        line.substringAfter(',').assert().isEqualTo(
            "\"mongo\":{\"limit\":12,\"ceiling\":50,\"running\":0,\"queued\":0,\"floor\":1,\"pressed\":true," +
                "\"decreases\":1,\"appends\":120,\"appendP90Ms\":30.0,\"baselineMs\":10.0,\"waitingForConnection\":2}," +
                "\"postgres\":{\"limit\":8,\"ceiling\":8,\"running\":0,\"queued\":0}}",
        )
    }
}
