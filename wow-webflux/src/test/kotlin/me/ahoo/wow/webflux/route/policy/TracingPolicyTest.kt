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

package me.ahoo.wow.webflux.route.policy

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.web.reactive.function.server.ServerRequest

class TracingPolicyTest {

    @Test
    fun `should use full history by default`() {
        val range = TracingPolicy().range(request(), totalVersion = 100)

        range.replayHeadVersion.assert().isOne()
        range.emitHeadVersion.assert().isOne()
        range.tailVersion.assert().isEqualTo(100)
    }

    @Test
    fun `should emit tail window with full replay prefix when limit is set`() {
        val range = TracingPolicy().range(request("limit" to "10"), totalVersion = 100)

        range.replayHeadVersion.assert().isOne()
        range.emitHeadVersion.assert().isEqualTo(91)
        range.tailVersion.assert().isEqualTo(100)
    }

    @Test
    fun `should use explicit emit head and tail versions`() {
        val range = TracingPolicy().range(
            request(
                "headVersion" to "10",
                "tailVersion" to "20",
            ),
            totalVersion = 100,
        )

        range.replayHeadVersion.assert().isOne()
        range.emitHeadVersion.assert().isEqualTo(10)
        range.tailVersion.assert().isEqualTo(20)
    }

    @Test
    fun `should cap requested tail window to available history`() {
        val range = TracingPolicy().range(
            request(
                "tailVersion" to "200",
                "limit" to "10",
            ),
            totalVersion = 100,
        )

        range.replayHeadVersion.assert().isOne()
        range.emitHeadVersion.assert().isEqualTo(91)
        range.tailVersion.assert().isEqualTo(100)
    }

    @Test
    fun `should represent empty history without rejecting request`() {
        val range = TracingPolicy().range(request(), totalVersion = 0)

        range.replayHeadVersion.assert().isOne()
        range.emitHeadVersion.assert().isOne()
        range.tailVersion.assert().isZero()
    }

    @Test
    fun `should reject invalid query options`() {
        assertThrows<IllegalArgumentException> {
            TracingPolicy().range(request("headVersion" to "0"), totalVersion = 100)
        }
        assertThrows<IllegalArgumentException> {
            TracingPolicy().range(
                request(
                    "headVersion" to "10",
                    "tailVersion" to "9",
                ),
                totalVersion = 100,
            )
        }
        assertThrows<IllegalArgumentException> {
            TracingPolicy().range(request("limit" to "-1"), totalVersion = 100)
        }
    }

    private fun request(vararg queryParams: Pair<String, String>): ServerRequest {
        val builder = MockServerRequest.builder()
        queryParams.forEach {
            builder.queryParam(it.first, it.second)
        }
        return builder.build()
    }
}
