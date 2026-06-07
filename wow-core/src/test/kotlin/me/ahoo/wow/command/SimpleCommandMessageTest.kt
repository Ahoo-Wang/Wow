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

package me.ahoo.wow.command

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.SpaceIdCapable
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.aggregateId
import org.junit.jupiter.api.Test

class SimpleCommandMessageTest {

    @Test
    fun `should expose constructor values and named aggregate delegation`() {
        val header = DefaultHeader.empty().with("source", "test")
        val aggregateId = commandFixtureNamedAggregate.aggregateId(id = "account-1", tenantId = "tenant-1")
        val body = AccountCommand(id = "account-1", version = 5)

        val message = SimpleCommandMessage(
            id = "message-1",
            requestId = "request-1",
            header = header,
            body = body,
            aggregateId = aggregateId,
            ownerId = "owner-1",
            spaceId = "space-1",
            aggregateVersion = 5,
            name = "change-account",
            isCreate = false,
            allowCreate = true,
            isVoid = true,
            createTime = 1000
        )

        message.id.assert().isEqualTo("message-1")
        message.commandId.assert().isEqualTo("message-1")
        message.requestId.assert().isEqualTo("request-1")
        message.header.assert().isSameAs(header)
        message.body.assert().isEqualTo(body)
        message.aggregateId.assert().isEqualTo(aggregateId)
        message.contextName.assert().isEqualTo(COMMAND_FIXTURE_CONTEXT)
        message.aggregateName.assert().isEqualTo(COMMAND_FIXTURE_AGGREGATE)
        message.ownerId.assert().isEqualTo("owner-1")
        message.spaceId.assert().isEqualTo("space-1")
        message.aggregateVersion.assert().isEqualTo(5)
        message.name.assert().isEqualTo("change-account")
        message.isCreate.assert().isFalse()
        message.allowCreate.assert().isTrue()
        message.isVoid.assert().isTrue()
        message.createTime.assert().isEqualTo(1000)
    }

    @Test
    fun `should default request id owner space and command name`() {
        val body = AccountCommand(id = "account-1")
        val message = SimpleCommandMessage(
            id = "message-1",
            body = body,
            aggregateId = commandFixtureNamedAggregate.aggregateId(id = "account-1")
        )

        message.requestId.assert().isEqualTo("message-1")
        message.ownerId.assert().isEqualTo(OwnerId.DEFAULT_OWNER_ID)
        message.spaceId.assert().isEqualTo(SpaceIdCapable.DEFAULT_SPACE_ID)
        message.name.assert().isEqualTo("account_command")
        message.aggregateVersion.assert().isNull()
        message.isCreate.assert().isFalse()
        message.allowCreate.assert().isFalse()
        message.isVoid.assert().isFalse()
    }

    @Test
    fun `should copy header instead of sharing original header instance`() {
        val message = SimpleCommandMessage(
            id = "message-1",
            header = DefaultHeader.empty().with("source", "original"),
            body = AccountCommand(id = "account-1"),
            aggregateId = commandFixtureNamedAggregate.aggregateId(id = "account-1")
        )

        val copied = message.copy()

        copied.assert().isNotSameAs(message)
        copied.header.assert().isNotSameAs(message.header)
        copied.header["source"].assert().isEqualTo("original")

        copied.withHeader("source", "copy")

        message.header["source"].assert().isEqualTo("original")
        copied.header["source"].assert().isEqualTo("copy")
    }
}
