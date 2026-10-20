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

package me.ahoo.wow.kernel

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.spi.log.CommittedRequest
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RecentRequestsTest {
    private val ref = cartModel.type.ref("tenant", "cart-1")

    private fun stream(version: Int) = EventStreamRecord.of(
        ref,
        version,
        "command-$version",
        "request-$version",
        "example.cart.AddItem",
        0L,
        listOf(EventRecord.of("example.cart.ItemAdded", 1, "{}")),
        Scope.NONE,
    )

    private fun key(version: Int) = CommittedRequest.of(stream(version))

    @Test
    fun `a whole history within the capacity is complete`() {
        val recent = RecentRequests.of(4, listOf(key(2), key(1)), listOf(stream(3), stream(4)), reachedStart = true)
        recent.complete.assert().isTrue()
        recent.size.assert().isEqualTo(4)
        (1..4).forEach { recent.versionOf("request-$it").assert().isEqualTo(it) }
        recent.versionOf("request-5").assert().isNull()
    }

    @Test
    fun `a history beyond the capacity keeps the latest, and is not complete`() {
        val recent = RecentRequests.of(2, listOf(key(2), key(1)), listOf(stream(3)), reachedStart = true)
        recent.complete.assert().isFalse()
        recent.size.assert().isEqualTo(2)
        recent.versionOf("request-1").assert().isNull()
        recent.versionOf("request-2").assert().isEqualTo(2)
        recent.versionOf("request-3").assert().isEqualTo(3)
        RecentRequests.of(4, listOf(key(1)), emptyList(), reachedStart = false).complete.assert().isFalse()
    }

    @Test
    fun `adding beyond the capacity evicts the oldest and leaves the set incomplete`() {
        val recent = RecentRequests.of(2, emptyList(), listOf(stream(1)), reachedStart = true)
        recent.add("request-2", 2)
        recent.complete.assert().isTrue()
        recent.add("request-3", 3)
        recent.complete.assert().isFalse()
        recent.size.assert().isEqualTo(2)
        recent.versionOf("request-1").assert().isNull()
        recent.versionOf("request-3").assert().isEqualTo(3)
        // The ring wraps around: the latest commit of a request id wins.
        recent.add("request-2", 4)
        recent.versionOf("request-2").assert().isEqualTo(4)
    }

    @Test
    fun `a request id hashes to 64 bits, the same for the same id`() {
        RecentRequests.hashOf("request").assert().isEqualTo(RecentRequests.hashOf("request"))
        RecentRequests.hashOf("request").assert().isNotEqualTo(RecentRequests.hashOf("requesu"))
        RecentRequests.hashOf("").assert().isNotEqualTo(0L)
        assertThrows<IllegalArgumentException> { RecentRequests.of(0, emptyList(), emptyList(), reachedStart = true) }
    }
}
