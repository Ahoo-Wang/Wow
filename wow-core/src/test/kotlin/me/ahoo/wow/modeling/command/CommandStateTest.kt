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

package me.ahoo.wow.modeling.command

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.modeling.state.StateAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class CommandStateTest {

    @Test
    fun `stored state sources events and moves to sourced`() {
        val eventStream = eventStream()
        val stateAggregate = mockk<StateAggregate<Any>>(relaxed = true)

        val next = CommandState.STORED.onSourcing(stateAggregate, eventStream)

        next.assert().isEqualTo(CommandState.SOURCED)
        verify { stateAggregate.onSourcing(eventStream) }
    }

    @Test
    fun `sourced state stores events and moves back to stored`() {
        val eventStream = eventStream()
        val eventStore = mockk<EventStore> {
            every { append(eventStream) } returns Mono.empty()
        }

        StepVerifier.create(CommandState.SOURCED.onStore(eventStore, eventStream))
            .expectNext(CommandState.STORED)
            .verifyComplete()
    }

    @Test
    fun `states reject unsupported sourcing and store operations`() {
        val eventStream = eventStream()

        assertThrownBy<UnsupportedOperationException> {
            CommandState.SOURCED.onSourcing(mockk(), eventStream)
        }
        assertThrownBy<UnsupportedOperationException> {
            CommandState.EXPIRED.onSourcing(mockk(), eventStream)
        }
        assertThrownBy<UnsupportedOperationException> {
            CommandState.STORED.onStore(mockk(), eventStream)
        }
        assertThrownBy<UnsupportedOperationException> {
            CommandState.EXPIRED.onStore(mockk(), eventStream)
        }
    }

    private fun eventStream(): DomainEventStream =
        mockk {
            every { id } returns "event-stream-1"
            every { commandId } returns "command-1"
        }
}
