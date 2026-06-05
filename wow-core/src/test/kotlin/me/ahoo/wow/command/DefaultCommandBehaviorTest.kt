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
import me.ahoo.wow.api.Version
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.SpaceIdCapable
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.traceId
import org.junit.jupiter.api.Test

class DefaultCommandBehaviorTest {

    @Test
    fun `should expose default command message fields from command metadata`() {
        val command = CreateAccountCommand(id = "account-1")
        val message = command.toCommandMessage(id = "command-1", createTime = 1234)

        message.id.assert().isEqualTo("command-1")
        message.commandId.assert().isEqualTo("command-1")
        message.requestId.assert().isEqualTo("command-1")
        message.body.assert().isEqualTo(command)
        message.contextName.assert().isEqualTo(COMMAND_FIXTURE_CONTEXT)
        message.aggregateName.assert().isEqualTo(COMMAND_FIXTURE_AGGREGATE)
        message.aggregateId.id.assert().isEqualTo("account-1")
        message.aggregateId.tenantId.assert().isEqualTo(TenantId.DEFAULT_TENANT_ID)
        message.ownerId.assert().isEqualTo(OwnerId.DEFAULT_OWNER_ID)
        message.spaceId.assert().isEqualTo(SpaceIdCapable.DEFAULT_SPACE_ID)
        message.aggregateVersion.assert().isEqualTo(Version.UNINITIALIZED_VERSION)
        message.name.assert().isEqualTo("create_account_command")
        message.isCreate.assert().isTrue()
        message.allowCreate.assert().isFalse()
        message.isVoid.assert().isFalse()
        message.createTime.assert().isEqualTo(1234)
    }

    @Test
    fun `should ensure trace header and expose read only state through header`() {
        val message = AccountCommand(id = "account-1").toCommandMessage(id = "command-1")

        message.header.traceId.assert().isEqualTo("command-1")
        message.isReadOnly.assert().isFalse()

        message.withReadOnly()

        message.isReadOnly.assert().isTrue()
        message.header.isReadOnly.assert().isTrue()
    }
}
