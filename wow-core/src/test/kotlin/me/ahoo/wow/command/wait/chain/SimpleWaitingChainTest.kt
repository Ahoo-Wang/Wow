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

package me.ahoo.wow.command.wait.chain

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.chain.SimpleWaitingChain.Companion.extractSimpleWaitingChain
import me.ahoo.wow.command.wait.chain.SimpleWaitingChain.Companion.extractWaitChain
import me.ahoo.wow.command.wait.chain.SimpleWaitingChain.Companion.propagateWaitChain
import me.ahoo.wow.command.wait.propagateCommandWaitEndpoint
import me.ahoo.wow.event.toDomainEvent
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test

class SimpleWaitingChainTest {

    @Test
    fun main() {
        val function = NamedFunctionInfoData("context", "processor", "function")
        val tail = WaitingChainTail(CommandStage.PROCESSED, function)
        val chain = SimpleWaitingChain(tail, function)
        chain.stage.assert().isEqualTo(CommandStage.SAGA_HANDLED)
        chain.shouldPropagate(mockk()).assert().isTrue()
    }

    @Test
    fun propagate() {
        val function = NamedFunctionInfoData("context", "processor", "function")
        val tail = WaitingChainTail(CommandStage.PROCESSED, function)
        val chain = SimpleWaitingChain(tail, function)
        val header = DefaultHeader.empty()
        chain.propagate("endpoint", header)
        header.extractWaitChain().assert().isEqualTo(SimpleWaitingChain.SIMPLE_CHAIN)
        val extracted = header.extractSimpleWaitingChain()
        extracted.assert().isNotNull().isInstanceOf(SimpleWaitingChain::class.java)
        extracted as SimpleWaitingChain
        extracted.function.assert().isEqualTo(function)
        extracted.tail.stage.assert().isEqualTo(tail.stage)
        extracted.tail.function.assert().isEqualTo(tail.function)
    }

    @Test
    fun propagateCommandMessageUpstream() {
        val function = NamedFunctionInfoData("context", "processor", "function")
        val tail = WaitingChainTail(CommandStage.PROCESSED, function)
        val chain = SimpleWaitingChain(tail, function)
        val header = DefaultHeader.empty()
        val upstream = MockCreateAggregate(generateGlobalId(), generateGlobalId())
            .toCommandMessage()
        upstream.header.propagateCommandWaitEndpoint("endpoint")
        chain.propagate(header, upstream)
        header.extractWaitChain().assert().isEqualTo(SimpleWaitingChain.SIMPLE_CHAIN)
        val extracted = header.extractSimpleWaitingChain()
        extracted.assert().isNotNull().isInstanceOf(SimpleWaitingChain::class.java)
        extracted as SimpleWaitingChain
        extracted.function.assert().isEqualTo(function)
        extracted.tail.stage.assert().isEqualTo(tail.stage)
        extracted.tail.function.assert().isEqualTo(tail.function)
    }

    @Test
    fun propagateDomainEventUpstream() {
        val function = NamedFunctionInfoData("context", "processor", "function")
        val tail = WaitingChainTail(CommandStage.PROCESSED, function)
        val chain = SimpleWaitingChain(tail, function)
        val header = DefaultHeader.empty()
        val upstream = MockAggregateCreated(generateGlobalId())
            .toDomainEvent(MOCK_AGGREGATE_METADATA.aggregateId(), generateGlobalId())
        upstream.header.propagateCommandWaitEndpoint("endpoint")
        chain.propagate(header, upstream)
        val extracted = header.extractSimpleWaitingChain()
        extracted.assert().isNotNull().isInstanceOf(WaitingChainTail::class.java)
        extracted as WaitingChainTail
        extracted.function.assert().isEqualTo(function)
        extracted.stage.assert().isEqualTo(tail.stage)
    }

    @Test
    fun extractWaitChain() {
        val header = DefaultHeader.empty()
        header.propagateWaitChain(SimpleWaitingChain.SIMPLE_CHAIN)
        header.extractWaitChain().assert().isEqualTo(SimpleWaitingChain.SIMPLE_CHAIN)
    }

    @Test
    fun extractSimpleWaitingChainWhenNotSimpleChain() {
        val header = DefaultHeader.empty()
        header.propagateWaitChain("other")
        header.extractSimpleWaitingChain().assert().isNull()
    }

    @Test
    fun extractSimpleWaitingChainWhenNoTail() {
        val header = DefaultHeader.empty()
        header.propagateWaitChain(SimpleWaitingChain.SIMPLE_CHAIN)
        header.extractSimpleWaitingChain().assert().isNull()
    }
}
