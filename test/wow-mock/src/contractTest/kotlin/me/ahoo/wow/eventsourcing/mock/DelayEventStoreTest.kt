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

package me.ahoo.wow.eventsourcing.mock

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.tck.eventsourcing.EventStoreSpec
import me.ahoo.wow.tck.metrics.meteredForTck
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.kotlin.test.test

class DelayEventStoreTest : EventStoreSpec() {
    override fun createEventStore(): EventStore {
        return DelayEventStore().meteredForTck()
    }

    @Test
    fun `delay decorator preserves native request lookup`() {
        val stream = generateEventStream()
        val candidates = setOf(stream.requestId)
        val delegate = mockk<EventStore>()
        every { delegate.loadByRequestIds(stream.aggregateId, candidates) } returns Flux.just(stream)

        DelayEventStore(delegate = delegate).loadByRequestIds(stream.aggregateId, candidates)
            .test().expectNext(stream).verifyComplete()
        verify(exactly = 1) { delegate.loadByRequestIds(stream.aggregateId, candidates) }
        verify(exactly = 0) { delegate.load(any(), any<Int>(), any<Int>()) }
    }

    @Test
    fun `close should delegate to the original EventStore`() {
        var closeCount = 0
        val delegate = object : EventStore by InMemoryEventStore() {
            override fun close() {
                closeCount++
            }
        }
        val eventStore: EventStore = DelayEventStore(delegate = delegate)

        eventStore.close()

        closeCount.assert().isEqualTo(1)
    }
}
