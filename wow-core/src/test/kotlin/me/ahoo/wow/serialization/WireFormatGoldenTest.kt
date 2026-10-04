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

package me.ahoo.wow.serialization

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.wait.extractWaitPlan
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.tck.wire.WireGolden
import me.ahoo.wow.tck.wire.WireSamples
import org.junit.jupiter.api.Test

/**
 * Golden samples of the v9 message JSON: the bytes every bus (Kafka, Redis, in-memory) carries and every event store
 * row embeds.
 *
 * The files under `src/test/resources/wire/v9/` were generated from 9.2.2 and are **frozen v9 wire contracts**: a
 * 9.2.x node and a 9.3.x node share topics, streams and stores, so each must read what the other writes. A failing
 * test here means the wire changed; changing a golden needs a design decision (and a mixed-version plan), not a
 * re-generation. Additive changes are no exception — an older reader must still accept the new bytes, so they go
 * through the same decision.
 *
 * Each sample is checked both ways: serializing the current object gives the golden bytes, and the golden bytes
 * deserialize into an equal object (or one that serializes back to the same bytes).
 */
class WireFormatGoldenTest {

    @Test
    fun `command message keeps the v9 wire format`() {
        val command = WireSamples.commandMessage()
        WireGolden.assertMatches("command-message.json", command.toJsonString())

        val decoded = WireGolden.read("command-message.json").toObject<CommandMessage<*>>()
        decoded.assert().isEqualTo(command)
        decoded.header.extractWaitPlan()!!.plan.target.assert().isEqualTo(WireSamples.stageWaitPlan.target)
    }

    @Test
    fun `chain wait command message keeps the v9 wire format`() {
        val command = WireSamples.chainWaitCommandMessage()
        WireGolden.assertMatches("command-message-chain-wait.json", command.toJsonString())

        val decoded = WireGolden.read("command-message-chain-wait.json").toObject<CommandMessage<*>>()
        decoded.assert().isEqualTo(command)
        val target = decoded.header.extractWaitPlan()!!.plan.target
        val expected = WireSamples.chainWaitPlan.target
        target.stage.assert().isEqualTo(expected.stage)
        target.function.assert().isEqualTo(expected.function)
    }

    @Test
    fun `domain event stream keeps the v9 wire format`() {
        val eventStream = WireSamples.domainEventStream()
        WireGolden.assertMatches("domain-event-stream.json", eventStream.toJsonString())

        val golden = WireGolden.read("domain-event-stream.json")
        val decoded = golden.toObject<DomainEventStream>()
        decoded.id.assert().isEqualTo(eventStream.id)
        decoded.aggregateId.assert().isEqualTo(eventStream.aggregateId)
        decoded.header.assert().isEqualTo(eventStream.header)
        decoded.map { it.body }.assert().isEqualTo(eventStream.map { it.body })
        decoded.toJsonString().assert().isEqualTo(golden)
    }

    @Test
    fun `state event keeps the v9 wire format`() {
        val stateEvent = WireSamples.stateEvent()
        WireGolden.assertMatches("state-event.json", stateEvent.toJsonString())

        val golden = WireGolden.read("state-event.json")
        val decoded = golden.toObject<StateEvent<*>>()
        decoded.id.assert().isEqualTo(stateEvent.id)
        decoded.state.assert().isInstanceOf(MockStateAggregate::class.java)
        (decoded.state as MockStateAggregate).id.assert().isEqualTo(stateEvent.state.id)
        decoded.tags.assert().isEqualTo(stateEvent.tags)
        decoded.toJsonString().assert().isEqualTo(golden)
    }
}
