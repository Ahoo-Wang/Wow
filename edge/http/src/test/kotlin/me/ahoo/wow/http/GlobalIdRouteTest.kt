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

package me.ahoo.wow.http

import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.id.IdGenerators
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** `GET /wow/id/global` (09 §13 #15, decision D171). */
class GlobalIdRouteTest {
    private fun HttpNode.globalId(user: String? = "alice"): Pair<Int, String?> {
        val result = client.get().uri(WowPaths.GLOBAL_ID)
            .headers { http -> user?.let { http.set("X-User", it) } }
            .exchange()
            .expectBody(String::class.java)
            .returnResult()
        result.responseHeaders.contentType?.toString()?.let {
            if (result.status.is2xxSuccessful) it.assert().startsWith("text/plain")
        }
        return result.status.value() to result.responseBody
    }

    @Test
    fun `a new global id should be answered as text, UUIDv7 by default`() {
        val node = HttpNode()
        val (status, first) = node.globalId()
        status.assert().isEqualTo(200)
        UUID.fromString(first).version().assert().isEqualTo(7)
        node.globalId().second.assert().isNotEqualTo(first)
    }

    @Test
    fun `the id should come from the gateway's global generator`() {
        val sequence = AtomicInteger()
        val node = HttpNode(
            ids = IdGenerators(
                GlobalIdGenerator { "g-${sequence.incrementAndGet()}" },
                AggregateIdGenerator { "${it.name}-new" },
            ),
        )
        node.globalId().assert().isEqualTo(200 to "g-1")
        node.globalId().assert().isEqualTo(200 to "g-2")
        val opened = node.post("/shop/cart/open-cart", """{"note":"n"}""")
        opened.status.assert().isEqualTo(200)
        opened.body!!["ref"]["id"].asString().assert().isEqualTo("cart-new")
        opened.body!!["commandId"].asString().assert().isEqualTo("g-3")
    }

    @Test
    fun `the route should be authenticated`() {
        val (status, body) = HttpNode().globalId(user = null)
        status.assert().isEqualTo(401)
        json.readTree(body)["code"].asString().assert().isEqualTo(FrameworkCodes.UNAUTHENTICATED)
    }
}
