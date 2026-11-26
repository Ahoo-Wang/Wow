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

package me.ahoo.wow.runtime.access

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.access.AccessStamps
import me.ahoo.wow.spi.log.EventLog
import org.junit.jupiter.api.Test

/**
 * A type's `events` model is served only from stores that keep its history's access attributes current (#7157): from
 * its log, a log that stamps and walks; from another store, a Sink that stamps and a log that walks.
 */
class AccessCapabilitiesTest {
    private val cart = AggregateType("shop", "cart")

    /** Every port of a log, neither access capability: a log written outside Wow. */
    private val plain: EventLog = object : EventLog by InMemoryEventLog(1) {}

    /** A log that stamps but does not walk. */
    private val stampsOnly: EventLog = object : EventLog by InMemoryEventLog(1), AccessStamps {
        override suspend fun stamp(ref: AggregateRef, access: Map<String, Any?>, asOf: Int, from: Int, until: Int) = 0
    }

    /** Wow's own log: it stamps and walks. */
    private val wow: EventLog = InMemoryEventLog(1)

    private fun refusals(log: EventLog, source: EventsSource?, stampingSink: Boolean = false) =
        AccessCapabilities.refusals(
            listOf(AccessCapabilities.Placed(cart, "outside", log, stampingSink)),
            EventsSources { source },
        )

    @Test
    fun `a type whose events model is not served is not checked`() {
        refusals(plain, null).assert().isEmpty()
    }

    @Test
    fun `events served from the log need a log that stamps and walks`() {
        refusals(wow, EventsSource.Log).assert().isEmpty()
        refusals(plain, EventsSource.Log).single().assert()
            .contains("shop.cart", "its log on the store 'outside'", "no AccessStamps")
        refusals(stampsOnly, EventsSource.Log).single().assert().contains("no AccessWalks")
    }

    @Test
    fun `events served from another store need a Sink that stamps and a log that walks`() {
        val search = EventsSource.Store("search")
        refusals(wow, search, stampingSink = true).assert().isEmpty()
        refusals(plain, search, stampingSink = true).single().assert()
            .contains("shop.cart", "the store 'search'", "its log on the store 'outside'", "no AccessWalks")
        refusals(wow, search, stampingSink = false).single().assert()
            .contains("shop.cart", "the store 'search'", "whose Sink", "no AccessStamps")
    }
}
