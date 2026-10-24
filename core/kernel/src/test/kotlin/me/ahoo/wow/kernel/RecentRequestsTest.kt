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
    fun `the rings start small and grow with the requests, up to the capacity`() {
        val recent = RecentRequests.of(256, emptyList(), listOf(stream(1)), reachedStart = true)
        recent.bytes.assert().isEqualTo(RecentRequests.INITIAL * RecentRequests.REQUEST_BYTES)
        (2..20).forEach { recent.add("request-$it", it) }
        recent.bytes.assert().isEqualTo(32 * RecentRequests.REQUEST_BYTES)
        (1..20).forEach { recent.versionOf("request-$it").assert().isEqualTo(it) }
        recent.complete.assert().isTrue()
        (21..300).forEach { recent.add("request-$it", it) }
        recent.bytes.assert().isEqualTo(256 * RecentRequests.REQUEST_BYTES)
        recent.size.assert().isEqualTo(256)
        recent.versionOf("request-44").assert().isNull()
        recent.versionOf("request-45").assert().isEqualTo(45)
        recent.versionOf("request-300").assert().isEqualTo(300)
        recent.complete.assert().isFalse()
    }

    @Test
    fun `a ring that wrapped grows with its oldest request first`() {
        val recent = RecentRequests.of(12, emptyList(), (1..8).map(::stream), reachedStart = true)
        // Full at eight slots; the next request grows the rings to twelve, the oldest still first.
        recent.add("request-9", 9)
        (10..14).forEach { recent.add("request-$it", it) }
        recent.size.assert().isEqualTo(12)
        recent.versionOf("request-2").assert().isNull()
        (3..14).forEach { recent.versionOf("request-$it").assert().isEqualTo(it) }
        recent.bytes.assert().isEqualTo(12 * RecentRequests.REQUEST_BYTES)
    }

    @Test
    fun `a load sizes the rings for the requests it brings`() {
        val recent = RecentRequests.of(256, (40 downTo 1).map(::key), listOf(stream(41)), reachedStart = true)
        recent.bytes.assert().isEqualTo(41 * RecentRequests.REQUEST_BYTES)
        RecentRequests.of(4, emptyList(), listOf(stream(1)), reachedStart = true).bytes
            .assert().isEqualTo(4 * RecentRequests.REQUEST_BYTES)
    }

    @Test
    fun `a cached state weighs its state and its rings`() {
        val cached = CachedState("state", 1)
        cached.bytes.assert().isEqualTo(CachedState.STATE_BYTES)
        cached.requests = RecentRequests.of(256, emptyList(), listOf(stream(1)), reachedStart = true)
        cached.bytes.assert().isEqualTo(CachedState.STATE_BYTES + RecentRequests.INITIAL * RecentRequests.REQUEST_BYTES)
    }

    @Test
    fun `a request id hashes to 64 bits, the same for the same id`() {
        RecentRequests.hashOf("request").assert().isEqualTo(RecentRequests.hashOf("request"))
        RecentRequests.hashOf("request").assert().isNotEqualTo(RecentRequests.hashOf("requesu"))
        RecentRequests.hashOf("").assert().isNotEqualTo(0L)
        assertThrows<IllegalArgumentException> { RecentRequests.of(0, emptyList(), emptyList(), reachedStart = true) }
    }
}
