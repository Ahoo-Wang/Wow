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

package me.ahoo.wow.command.wait

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.modeling.DefaultAggregateId
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

class WaitSignalSerializationContractTest {
    @Test
    fun `Java callers retain constructor factory and copy signatures`() {
        val (constructed, notified, copied) = LegacyWaitSignalCalls.createFrom(signal())

        constructed.commandId.assert().isEqualTo("parent-command")
        notified.commandId.assert().isEqualTo("parent-command")
        copied.requestId.assert().isEqualTo("parent-request")
        copied.commandRequests.getValue("child-command").requestId.assert().isEqualTo("child-request")
    }

    @Test
    fun `request identities survive notification transport and result merging`() {
        val decoded = signal().toJsonString().toObject<SimpleWaitSignal>().copyResult(mapOf("answer" to 42))

        decoded.requestId.assert().isEqualTo("parent-request")
        decoded.commandId.assert().isEqualTo("parent-command")
        decoded.commands.assert().containsExactly("child-command")
        val request = decoded.commandRequests.getValue("child-command")
        request.requestId.assert().isEqualTo("child-request")
        request.aggregateId.contextName.assert().isEqualTo("sales")
        request.aggregateId.aggregateName.assert().isEqualTo("order")
        request.aggregateId.tenantId.assert().isEqualTo("tenant-1")
        request.aggregateId.id.assert().isEqualTo("order-1")
        decoded.result["answer"].assert().isEqualTo(42)
    }

    @Test
    fun `notifications without request metadata still decode`() {
        val node = signal().toJsonNode<ObjectNode>()
        node.remove("requestId")
        node.remove("commandRequests")
        val decoded = node.toJsonString().toObject<SimpleWaitSignal>()

        decoded.requestId.assert().isNull()
        decoded.commandRequests.assert().isEmpty()
        decoded.commands.assert().containsExactly("child-command")
    }

    private fun signal(): SimpleWaitSignal {
        val aggregateId = DefaultAggregateId(MaterializedNamedAggregate("sales", "order"), "order-1", "tenant-1")
        return SimpleWaitSignal(
            id = "signal",
            waitCommandId = "wait",
            commandId = "parent-command",
            aggregateId = aggregateId,
            stage = CommandStage.SAGA_HANDLED,
            function = FunctionInfoData(FunctionKind.EVENT, "sales", "saga", "onEvent"),
            requestId = "parent-request",
            commands = listOf("child-command"),
            commandRequests = mapOf("child-command" to CommandRequestId(aggregateId, "child-request")),
        )
    }
}
