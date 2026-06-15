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
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.traceId
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CommandFactoryTest {

    @Test
    fun `should create command message from explicit factory arguments`() {
        val header = DefaultHeader.empty().with("source", "factory")
        val namedAggregate = MaterializedNamedAggregate("explicit-context", "explicit-aggregate")
        val body = FactoryTargetCommand("payload")

        val message = body.toCommandMessage(
            id = "command-1",
            requestId = "request-1",
            aggregateId = "aggregate-1",
            tenantId = "tenant-1",
            ownerId = "owner-1",
            spaceId = "space-1",
            aggregateVersion = 9,
            namedAggregate = namedAggregate,
            header = header,
            createTime = 12345
        )

        message.id.assert().isEqualTo("command-1")
        message.requestId.assert().isEqualTo("request-1")
        message.aggregateId.id.assert().isEqualTo("aggregate-1")
        message.aggregateId.tenantId.assert().isEqualTo("tenant-1")
        message.contextName.assert().isEqualTo("explicit-context")
        message.aggregateName.assert().isEqualTo("explicit-aggregate")
        message.ownerId.assert().isEqualTo("owner-1")
        message.spaceId.assert().isEqualTo("space-1")
        message.aggregateVersion.assert().isEqualTo(9)
        message.header.assert().isSameAs(header)
        message.header["source"].assert().isEqualTo("factory")
        message.header.traceId.assert().isEqualTo("command-1")
        message.createTime.assert().isEqualTo(12345)
        message.body.assert().isSameAs(body)
    }

    @Test
    fun `should prefer annotated command fields over nullable factory arguments`() {
        val body = TenantAccountCommand(id = "account-1", tenantId = "tenant-from-command")

        val message = body.toCommandMessage(
            id = "command-1",
            aggregateId = "aggregate-argument",
            tenantId = "tenant-argument",
            ownerId = "owner-argument",
            aggregateVersion = 5
        )

        message.aggregateId.id.assert().isEqualTo("account-1")
        message.aggregateId.tenantId.assert().isEqualTo("tenant-from-command")
        message.ownerId.assert().isEqualTo("owner-argument")
        message.aggregateVersion.assert().isEqualTo(5)
    }

    @Test
    fun `should use command metadata for owner version name allow create and void flags`() {
        val ownerMessage = OwnerAccountCommand(id = "account-1", ownerId = "owner-from-command")
            .toCommandMessage(id = "command-1")
        val versionedMessage = AccountCommand(id = "account-2", version = 8)
            .toCommandMessage(id = "command-2")
        val namedMessage = NamedAccountCommand(id = "account-3")
            .toCommandMessage(id = "command-3")
        val allowCreateMessage = UpsertAccountCommand(id = "account-4")
            .toCommandMessage(id = "command-4")
        val voidMessage = VoidAccountCommand(id = "account-5")
            .toCommandMessage(id = "command-5")

        ownerMessage.ownerId.assert().isEqualTo("owner-from-command")
        versionedMessage.aggregateVersion.assert().isEqualTo(8)
        namedMessage.name.assert().isEqualTo(COMMAND_FIXTURE_CUSTOM_NAME)
        allowCreateMessage.allowCreate.assert().isTrue()
        allowCreateMessage.isCreate.assert().isFalse()
        voidMessage.isVoid.assert().isTrue()
    }

    @Test
    fun `should use uninitialized version for create command`() {
        val message = CreateAccountCommand(id = "account-1")
            .toCommandMessage(aggregateVersion = 99)

        message.isCreate.assert().isTrue()
        message.aggregateVersion.assert().isEqualTo(Version.UNINITIALIZED_VERSION)
    }

    @Test
    fun `should mark command as create when expected aggregate version is uninitialized`() {
        val message = UpsertAccountCommand(id = "account-1")
            .toCommandMessage(aggregateVersion = Version.UNINITIALIZED_VERSION)

        message.isCreate.assert().isTrue()
        message.allowCreate.assert().isTrue()
        message.aggregateVersion.assert().isEqualTo(Version.UNINITIALIZED_VERSION)
    }

    @Test
    fun `should default tenant owner and space ids`() {
        val message = AccountCommand(id = "account-1").toCommandMessage()

        message.aggregateId.tenantId.assert().isEqualTo(TenantId.DEFAULT_TENANT_ID)
        message.ownerId.assert().isEqualTo(OwnerId.DEFAULT_OWNER_ID)
        message.spaceId.assert().isEqualTo(SpaceIdCapable.DEFAULT_SPACE_ID)
    }

    @Test
    fun `should support owner id same as aggregate id current behavior`() {
        val defaultOwnerMessage = FactoryTargetCommand("payload").toCommandMessage(
            id = "command-1",
            aggregateId = "account-1",
            namedAggregate = commandFixtureNamedAggregate,
            ownerIdSameAsAggregateId = true
        )
        val explicitOwnerMessage = FactoryTargetCommand("payload").toCommandMessage(
            id = "command-2",
            aggregateId = "account-2",
            ownerId = "owner-2",
            namedAggregate = commandFixtureNamedAggregate,
            ownerIdSameAsAggregateId = true
        )

        defaultOwnerMessage.aggregateId.id.assert().isEqualTo("account-1")
        defaultOwnerMessage.ownerId.assert().isEqualTo("account-1")
        explicitOwnerMessage.aggregateId.id.assert().isEqualTo("owner-2")
        explicitOwnerMessage.ownerId.assert().isEqualTo("owner-2")
    }

    @Test
    fun `should create command message from command builder`() {
        val header = DefaultHeader.empty().with("source", "builder")
        val message = FactoryTargetCommand("payload")
            .commandBuilder()
            .id("command-1")
            .requestId("request-1")
            .aggregateId("aggregate-1")
            .tenantId("tenant-1")
            .ownerId("owner-1")
            .spaceId("space-1")
            .aggregateVersion(6)
            .namedAggregate(commandFixtureNamedAggregate)
            .header(header)
            .createTime(4567)
            .toCommandMessage<FactoryTargetCommand>()

        message.id.assert().isEqualTo("command-1")
        message.requestId.assert().isEqualTo("request-1")
        message.aggregateId.id.assert().isEqualTo("aggregate-1")
        message.aggregateId.tenantId.assert().isEqualTo("tenant-1")
        message.ownerId.assert().isEqualTo("owner-1")
        message.spaceId.assert().isEqualTo("space-1")
        message.aggregateVersion.assert().isEqualTo(6)
        message.contextName.assert().isEqualTo(COMMAND_FIXTURE_CONTEXT)
        message.aggregateName.assert().isEqualTo(COMMAND_FIXTURE_AGGREGATE)
        message.header.assert().isSameAs(header)
        message.header.traceId.assert().isEqualTo("command-1")
        message.createTime.assert().isEqualTo(4567)
    }

    @Test
    fun `should reject command without named aggregate`() {
        val exception = assertThrows<IllegalArgumentException> {
            Any().toCommandMessage(id = "command-1")
        }

        exception.message.assert().isEqualTo(
            "The command[class java.lang.Object] must be associated with a named aggregate!"
        )
    }
}
