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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.runtime.InFlightLimit
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders

/**
 * A node whose store slows down (review A5-2, decision D228 ②): the commands beyond its in-flight bound are answered
 * at once as the retryable 429 `wow.command.busy`, with `Retry-After`, instead of queueing until their waits time out;
 * once the store is back, the node takes commands again.
 */
class OverloadRoutesTest {
    @Test
    fun `commands beyond the node's in-flight bound should be refused as busy with Retry-After`(): Unit = runBlocking {
        val node = HttpNode(inFlight = InFlightLimit(max = 2))
        val gate = CompletableDeferred<Unit>()
        node.appendGate = gate
        val held = (1..2).map { n ->
            async(Dispatchers.IO) { node.post("/shop/cart/held-$n/add-item", """{"sku":"apple"}""") }
        }
        withTimeout(TIMEOUT_MS) {
            while (node.inFlight.inFlight < 2) {
                delay(5)
            }
        }

        val refused = (1..3).map { n -> node.post("/shop/cart/other-$n/add-item", """{"sku":"pear"}""") }
        refused.forEach { answer ->
            answer.status.assert().isEqualTo(429)
            answer.contentType.assert().isEqualTo("application/problem+json")
            answer.headers.getFirst(HttpHeaders.RETRY_AFTER)
                .assert().isEqualTo(FrameworkErrors.RETRY_AFTER_SECONDS.toString())
            answer.body!!["code"].asString().assert().isEqualTo(FrameworkErrors.COMMAND_BUSY)
            answer.body!!["detail"].asString().assert().isEqualTo(
                "The node already has 2 commands in flight; retry later."
            )
            answer.body!!["result"]["status"].asString().assert().isEqualTo("failed")
        }
        node.commands.map { it.ref.id }.assert().containsExactlyInAnyOrder("held-1", "held-2")

        gate.complete(Unit)
        held.awaitAll().forEach { it.status.assert().isEqualTo(200) }
        node.inFlight.inFlight.assert().isEqualTo(0)
        node.post("/shop/cart/other-1/add-item", """{"sku":"pear"}""").status.assert().isEqualTo(200)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
