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

package me.ahoo.wow.eventsourcing.snapshot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.state.SimpleStateEventExchange
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class VersionIntervalCheckpointStrategyTest {
    private val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("checkpoint-strategy")

    @Test
    fun `stores only versions matching the configured interval`() {
        val store = InMemorySnapshotStore()
        val strategy = VersionIntervalCheckpointStrategy(versionInterval = 5, snapshotStore = store)

        strategy.onEvent(exchange(version = 4)).block()
        StepVerifier.create(store.loadAtOrBefore<MockStateAggregate>(aggregateId, 4))
            .verifyComplete()

        strategy.onEvent(exchange(version = 5)).block()
        StepVerifier.create(store.loadAtOrBefore<MockStateAggregate>(aggregateId, 5))
            .assertNext { checkpoint ->
                checkpoint.version.assert().isEqualTo(5)
            }
            .verifyComplete()
    }

    @Test
    fun `rejects a non-positive interval`() {
        assertThrows<IllegalArgumentException> {
            VersionIntervalCheckpointStrategy(versionInterval = 0, snapshotStore = InMemorySnapshotStore())
        }
    }

    @Test
    fun `composite strategy executes delegates sequentially`() {
        val calls = mutableListOf<String>()
        val strategy = CompositeSnapshotStrategy(
            listOf(
                recordingStrategy("latest", calls),
                recordingStrategy("checkpoint", calls),
            ),
        )

        StepVerifier.create(strategy.onEvent(exchange(version = 5)))
            .verifyComplete()
        calls.assert().containsExactly("latest", "checkpoint")
    }

    @Test
    fun `composite strategy rejects an empty delegate list`() {
        assertThrows<IllegalArgumentException> {
            CompositeSnapshotStrategy(emptyList())
        }
    }

    private fun recordingStrategy(
        name: String,
        calls: MutableList<String>,
    ): SnapshotStrategy = object : SnapshotStrategy {
        override fun onEvent(stateEventExchange: StateEventExchange<*>): Mono<Void> = Mono.fromRunnable {
            calls += name
        }
    }

    private fun exchange(version: Int): SimpleStateEventExchange<MockStateAggregate> {
        val eventStream = MockAggregateCreated("v$version").toDomainEventStream(
            upstream = GivenInitializationCommand(aggregateId),
            aggregateVersion = version - 1,
        )
        return SimpleStateEventExchange(
            eventStream.toStateEvent(MockStateAggregate(aggregateId.id))
        )
    }
}
