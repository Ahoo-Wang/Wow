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
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import me.ahoo.wow.id.generateGlobalId
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class CommandFactoryTest {

    @Test
    fun create() {
        val command = MockCommandWithExpectedAggregateVersion(generateGlobalId(), null)
        val commandMessage = command.toCommandMessage()
        commandMessage.body.assert().isEqualTo(command)
        commandMessage.aggregateId.id.assert().isEqualTo(command.id)
        commandMessage.aggregateVersion.assert().isNull()
        commandMessage.createTime.assert().isCloseTo(System.currentTimeMillis(), Offset.offset(5000))
    }

    @Test
    fun createFromBuilder() {
        val command = MockCommandWithExpectedAggregateVersion(generateGlobalId(), null)
        val commandMessage = command.commandBuilder().toCommandMessage<MockCommandWithExpectedAggregateVersion>()
        commandMessage.body.assert().isEqualTo(command)
        commandMessage.aggregateId.id.assert().isEqualTo(command.id)
        commandMessage.aggregateVersion.assert().isNull()
        commandMessage.createTime.assert().isCloseTo(System.currentTimeMillis(), Offset.offset(5000))
    }

    @Test
    fun createWithInheritNamedAggregate() {
        val command = MockCommandWithInheritNamedAggregate(generateGlobalId(), "test", "test")
        val commandMessage = command.toCommandMessage()
        commandMessage.body.assert().isEqualTo(command)
        commandMessage.aggregateId.id.assert().isEqualTo(command.id)
        commandMessage.aggregateVersion.assert().isNull()
        commandMessage.contextName.assert().isEqualTo(command.contextName)
        commandMessage.aggregateName.assert().isEqualTo(command.aggregateName)
    }

    @Test
    fun asCommand() {
        val command = MockCommandWithExpectedAggregateVersion(generateGlobalId(), null)
        val commandMessage = command.toCommandMessage()
        commandMessage.body.assert().isEqualTo(command)
        commandMessage.aggregateId.id.assert().isEqualTo(command.id)
        commandMessage.aggregateVersion.assert().isNull()
        commandMessage.createTime.assert().isCloseTo(System.currentTimeMillis(), Offset.offset(5000))
    }

    @Test
    fun createGivenCreateAggregate() {
        val command = MockCreateCommand(generateGlobalId())
        val commandMessage = command.toCommandMessage()
        commandMessage.body.assert().isEqualTo(command)
        commandMessage.aggregateId.id.assert().isEqualTo(command.id)
        commandMessage.isCreate.assert().isEqualTo(true)
        commandMessage.aggregateVersion.assert().isEqualTo(0)
        commandMessage.createTime.assert().isCloseTo(System.currentTimeMillis(), Offset.offset(5000))
    }

    @Test
    fun createGivenNamed() {
        val command = MockNamedCommand(generateGlobalId())
        val commandMessage = command.toCommandMessage()
        commandMessage.body.assert().isEqualTo(command)
        commandMessage.name.assert().isEqualTo(NAMED_COMMAND)
        commandMessage.aggregateId.tenantId.assert().isEqualTo(TenantId.DEFAULT_TENANT_ID)
        commandMessage.ownerId.assert().isEqualTo(OwnerId.DEFAULT_OWNER_ID)
        commandMessage.aggregateId.id.assert().isEqualTo(command.id)
        commandMessage.aggregateVersion.assert().isNull()
        commandMessage.createTime.assert().isCloseTo(System.currentTimeMillis(), Offset.offset(5000))
    }

    @Test
    fun createGivenTenantIdAndOwnerId() {
        val command = MockNamedCommand(generateGlobalId())
        val tenantId = "tenantId"
        val ownerId = "ownerId"
        val commandMessage = command.toCommandMessage(tenantId = tenantId, ownerId = ownerId)
        commandMessage.body.assert().isEqualTo(command)
        commandMessage.name.assert().isEqualTo(NAMED_COMMAND)
        commandMessage.aggregateId.tenantId.assert().isEqualTo(tenantId)
        commandMessage.ownerId.assert().isEqualTo(ownerId)
        commandMessage.aggregateId.id.assert().isEqualTo(command.id)
        commandMessage.aggregateVersion.assert().isNull()
        commandMessage.createTime.assert().isCloseTo(System.currentTimeMillis(), Offset.offset(5000))
    }

    @Test
    fun createTenantCommand() {
        val command = MockTenantIdCommand(generateGlobalId(), generateGlobalId())
        val commandMessage = command.toCommandMessage()
        commandMessage.aggregateId.id.assert().isEqualTo(command.id)
        commandMessage.aggregateId.tenantId.assert().isEqualTo(command.tenantId)
    }

    @Test
    fun createOwnerCommand() {
        val command = MockOwnerIdCommand(generateGlobalId(), generateGlobalId())
        val commandMessage = command.toCommandMessage()
        commandMessage.aggregateId.id.assert().isEqualTo(command.id)
        commandMessage.ownerId.assert().isEqualTo(command.ownerId)
    }

    @Test
    fun anyCommand() {
        Assertions.assertThrows(
            IllegalArgumentException::class.java,
            {
                Any().toCommandMessage()
            },
            "The command[${Any().javaClass}] must be associated with a named aggregate!"
        )
    }

    @Test
    fun createWithOwnerIdSameAsAggregateId() {
        val command = MockNamedCommand(generateGlobalId())
        val commandMessage = command.toCommandMessage(ownerIdSameAsAggregateId = true)
        commandMessage.body.assert().isEqualTo(command)
        commandMessage.ownerId.assert().isEqualTo(commandMessage.aggregateId.id)
    }

    @Test
    fun createWithOwnerIdSameAsAggregateIdAndExplicitOwnerId() {
        val command = MockNamedCommand(generateGlobalId())
        val ownerId = generateGlobalId()
        val commandMessage = command.toCommandMessage(
            ownerId = ownerId,
            ownerIdSameAsAggregateId = true
        )
        commandMessage.body.assert().isEqualTo(command)
        // When ownerId is explicitly provided and ownerIdSameAsAggregateId is true,
        // the explicit ownerId should take precedence
        commandMessage.ownerId.assert().isEqualTo(ownerId)
        commandMessage.aggregateId.id.assert().isEqualTo(ownerId)
    }

    @Test
    fun createWithOwnerIdSameAsAggregateIdAndExplicitOwnerIdBlank() {
        val command = MockNamedCommand(generateGlobalId())
        val commandMessage = command.toCommandMessage(
            ownerId = "",
            ownerIdSameAsAggregateId = true
        )
        commandMessage.body.assert().isEqualTo(command)
        // When ownerId is blank and ownerIdSameAsAggregateId is true,
        // the aggregateId should be used as ownerId
        commandMessage.ownerId.assert().isEqualTo(commandMessage.aggregateId.id)
    }
}
