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

package me.ahoo.wow.cosec

import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.AggregateIds
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * #6594: an aggregate id admission takes is reachable by its path behind CoSec's pipeline, which refuses an encoded
 * `/`, `;` or `\`; the ids it refuses for them, admission refuses too. CoSec 8.0.0-beta.5 also refuses a segment of
 * more than 256 characters as received, percent-encoded: the ids longer than that encoded and within 512 bytes in
 * UTF-8, which admission takes, are reachable from 8.0.0-beta.6, which counts a segment decoded.
 */
class CoSecAddressableIdsTest {
    private val node = CoSecNode(callers = CoSecCallerResolver(CoSecCallerOptions()))

    /** [id] as a client writes it in a path: every character but the unreserved ones percent-encoded, as wow-client. */
    private fun segmentOf(id: String): String = URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20")

    private fun open(id: String): Int = node.client.post().uri(URI.create("/shop/cart/${segmentOf(id)}/open-cart"))
        .header("Authorization", "Bearer alice;acme")
        .header("Content-Type", "application/json")
        .bodyValue("{}")
        .exchange()
        .returnResult(String::class.java)
        .status.value()

    @Test
    fun `an id admission takes should be reachable by its path behind CoSec's pipeline`() {
        val taken = listOf(
            "plain", "pct%sign", "space inside", "q?mark", "hash#tag", "plus+sign", "é-accent", "nbsp inside",
            "dot.inside", "dots..inside", "x".repeat(AggregateIds.MAX_LENGTH), "购".repeat(28),
        )
        taken.forEach { id ->
            AggregateIds.refused(id).assert().describedAs(id).isNull()
            open(id).assert().describedAs(id).isEqualTo(OK)
        }
    }

    @Test
    fun `an id CoSec's pipeline refuses in a path should be one admission refuses`() {
        listOf("semi;colon", "slash/inside", "back\\slash").forEach { id ->
            open(id).assert().describedAs(id).isEqualTo(BAD_REQUEST)
            AggregateIds.refused(id).assert().describedAs(id).isNotNull()
        }
    }

    private companion object {
        const val OK = 200
        const val BAD_REQUEST = 400
    }
}
