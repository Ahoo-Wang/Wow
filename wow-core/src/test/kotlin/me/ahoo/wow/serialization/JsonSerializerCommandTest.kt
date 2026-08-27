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
import me.ahoo.wow.command.SimpleCommandMessage
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.serialization.command.CommandRecords
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

internal class JsonSerializerCommandTest {

    @Test
    fun `command serializer should write command metadata and body type`() {
        val command = commandMessage()

        val node = command.toJsonNode<ObjectNode>()

        node[MessageRecords.ID].asString().assert().isEqualTo("command-id")
        node[MessageRecords.CONTEXT_NAME].asString().assert().isEqualTo("sales")
        node[MessageRecords.AGGREGATE_NAME].asString().assert().isEqualTo("Order")
        node[MessageRecords.AGGREGATE_ID].asString().assert().isEqualTo("order-1")
        node[MessageRecords.TENANT_ID].asString().assert().isEqualTo("tenant-1")
        node[MessageRecords.OWNER_ID].asString().assert().isEqualTo("owner-1")
        node[MessageRecords.SPACE_ID].asString().assert().isEqualTo("space-1")
        node[MessageRecords.REQUEST_ID].asString().assert().isEqualTo("request-1")
        node[MessageRecords.NAME].asString().assert().isEqualTo("CreateOrder")
        node[MessageRecords.BODY_TYPE].asString().assert().isEqualTo(CreateOrder::class.java.name)
        node[CommandRecords.AGGREGATE_VERSION].asInt().assert().isEqualTo(3)
        node[CommandRecords.IS_CREATE].asBoolean().assert().isTrue()
        node[CommandRecords.ALLOW_CREATE].asBoolean().assert().isTrue()
        node[CommandRecords.IS_VOID].asBoolean().assert().isFalse()
    }

    @Test
    fun `command deserializer should rebuild SimpleCommandMessage from command record`() {
        val command = commandMessage()

        val decoded = command.toJsonString().toObject<CommandMessage<*>>()

        decoded.assert().isEqualTo(command)
    }

    private fun commandMessage(): CommandMessage<CreateOrder> {
        val header = DefaultHeader.empty()
        header["trace"] = "trace-1"
        return SimpleCommandMessage(
            id = "command-id",
            header = header,
            body = CreateOrder("order-1", 2),
            aggregateId = MaterializedNamedAggregate("sales", "Order").aggregateId("order-1", "tenant-1"),
            ownerId = "owner-1",
            spaceId = "space-1",
            requestId = "request-1",
            aggregateVersion = 3,
            name = "CreateOrder",
            isCreate = true,
            allowCreate = true,
            isVoid = false,
            createTime = 1000,
        )
    }

    private data class CreateOrder(val orderId: String, val quantity: Int)
}
