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

import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import me.ahoo.wow.id.generateGlobalId
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class CommandFactoryTest {

    @Test
    fun create() {
        val command = MockCommandWithExpectedAggregateVersion(generateGlobalId(), null)
        val commandMessage = command.toCommandMessage()
        assertThat(commandMessage.body, equalTo(command))
        assertThat(commandMessage.aggregateId.id, equalTo(command.id))
        assertThat(commandMessage.aggregateVersion, nullValue())

        assertThat(
            commandMessage.createTime.toDouble(),
            closeTo(System.currentTimeMillis().toDouble(), 5000.toDouble())
        )
    }

    @Test
    fun createFromBuilder() {
        val command = MockCommandWithExpectedAggregateVersion(generateGlobalId(), null)
        val commandMessage = command.commandBuilder().toCommandMessage<MockCommandWithExpectedAggregateVersion>()
        assertThat(commandMessage.body, equalTo(command))
        assertThat(commandMessage.aggregateId.id, equalTo(command.id))
        assertThat(commandMessage.aggregateVersion, nullValue())

        assertThat(
            commandMessage.createTime.toDouble(),
            closeTo(System.currentTimeMillis().toDouble(), 5000.toDouble())
        )
    }

    @Test
    fun createWithInheritNamedAggregate() {
        val command = MockCommandWithInheritNamedAggregate(generateGlobalId(), "test", "test")
        val commandMessage = command.toCommandMessage()
        assertThat(commandMessage.body, equalTo(command))
        assertThat(commandMessage.aggregateId.id, equalTo(command.id))
        assertThat(commandMessage.aggregateVersion, nullValue())
        assertThat(commandMessage.contextName, equalTo(command.contextName))
        assertThat(commandMessage.aggregateName, equalTo(command.aggregateName))
    }

    @Test
    fun asCommand() {
        val command = MockCommandWithExpectedAggregateVersion(generateGlobalId(), null)
        val commandMessage = command.toCommandMessage()
        assertThat(commandMessage.body, equalTo(command))
        assertThat(commandMessage.aggregateId.id, equalTo(command.id))
        assertThat(commandMessage.aggregateVersion, nullValue())
        assertThat(
            commandMessage.createTime.toDouble(),
            closeTo(System.currentTimeMillis().toDouble(), 5000.toDouble())
        )
    }

    @Test
    fun createGivenCreateAggregate() {
        val command = MockCreateCommand(generateGlobalId())
        val commandMessage = command.toCommandMessage()
        assertThat(commandMessage.body, equalTo(command))
        assertThat(commandMessage.aggregateId.id, equalTo(command.id))
        assertThat(commandMessage.isCreate, equalTo(true))
        assertThat(commandMessage.aggregateVersion, equalTo(0))
        assertThat(
            commandMessage.createTime.toDouble(),
            closeTo(System.currentTimeMillis().toDouble(), 5000.toDouble())
        )
    }

    @Test
    fun createGivenNamed() {
        val command = MockNamedCommand(generateGlobalId())
        val commandMessage = command.toCommandMessage()
        assertThat(commandMessage.body, equalTo(command))
        assertThat(commandMessage.name, equalTo(NAMED_COMMAND))
        assertThat(commandMessage.aggregateId.tenantId, equalTo(TenantId.DEFAULT_TENANT_ID))
        assertThat(commandMessage.ownerId, equalTo(OwnerId.DEFAULT_OWNER_ID))
        assertThat(commandMessage.aggregateId.id, equalTo(command.id))
        assertThat(commandMessage.aggregateVersion, nullValue())
        assertThat(
            commandMessage.createTime.toDouble(),
            closeTo(System.currentTimeMillis().toDouble(), 5000.toDouble())
        )
    }

    @Test
    fun createGivenTenantIdAndOwnerId() {
        val command = MockNamedCommand(generateGlobalId())
        val tenantId = "tenantId"
        val ownerId = "ownerId"
        val commandMessage = command.toCommandMessage(tenantId = tenantId, ownerId = ownerId)
        assertThat(commandMessage.body, equalTo(command))
        assertThat(commandMessage.name, equalTo(NAMED_COMMAND))
        assertThat(commandMessage.aggregateId.tenantId, equalTo(tenantId))
        assertThat(commandMessage.ownerId, equalTo(ownerId))
        assertThat(commandMessage.aggregateId.id, equalTo(command.id))
        assertThat(commandMessage.aggregateVersion, nullValue())
        assertThat(
            commandMessage.createTime.toDouble(),
            closeTo(System.currentTimeMillis().toDouble(), 5000.toDouble())
        )
    }

    @Test
    fun createTenantCommand() {
        val command = MockTenantIdCommand(generateGlobalId(), generateGlobalId())
        val commandMessage = command.toCommandMessage()
        assertThat(commandMessage.aggregateId.id, equalTo(command.id))
        assertThat(commandMessage.aggregateId.tenantId, equalTo(command.tenantId))
    }

    @Test
    fun createOwnerCommand() {
        val command = MockOwnerIdCommand(generateGlobalId(), generateGlobalId())
        val commandMessage = command.toCommandMessage()
        assertThat(commandMessage.aggregateId.id, equalTo(command.id))
        assertThat(commandMessage.ownerId, equalTo(command.ownerId))
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
}
