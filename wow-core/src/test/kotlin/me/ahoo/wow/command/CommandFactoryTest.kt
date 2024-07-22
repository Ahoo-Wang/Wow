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

import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import me.ahoo.wow.id.GlobalIdGenerator
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

internal class CommandFactoryTest {

    @Test
    fun create() {
        val command = MockCommandWithExpectedAggregateVersion(GlobalIdGenerator.generateAsString(), null)
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
        val command = MockCommandWithExpectedAggregateVersion(GlobalIdGenerator.generateAsString(), null)
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
        val command = MockCommandWithInheritNamedAggregate(GlobalIdGenerator.generateAsString(), "test", "test")
        val commandMessage = command.toCommandMessage()
        assertThat(commandMessage.body, equalTo(command))
        assertThat(commandMessage.aggregateId.id, equalTo(command.id))
        assertThat(commandMessage.aggregateVersion, nullValue())
        assertThat(commandMessage.contextName, equalTo(command.contextName))
        assertThat(commandMessage.aggregateName, equalTo(command.aggregateName))
    }

    @Test
    fun asCommand() {
        val command = MockCommandWithExpectedAggregateVersion(GlobalIdGenerator.generateAsString(), null)
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
        val command = MockCreateCommand(GlobalIdGenerator.generateAsString())
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
        val command = MockNamedCommand(GlobalIdGenerator.generateAsString())
        val commandMessage = command.toCommandMessage()
        assertThat(commandMessage.body, equalTo(command))
        assertThat(commandMessage.name, equalTo(NAMED_COMMAND))
        assertThat(commandMessage.aggregateId.id, equalTo(command.id))
        assertThat(commandMessage.aggregateVersion, nullValue())
        assertThat(
            commandMessage.createTime.toDouble(),
            closeTo(System.currentTimeMillis().toDouble(), 5000.toDouble())
        )
    }

    @Test
    fun createTenantCommand() {
        val command = MockTenantIdCommand(GlobalIdGenerator.generateAsString(), GlobalIdGenerator.generateAsString())
        val commandMessage = command.toCommandMessage()
        assertThat(commandMessage.aggregateId.id, equalTo(command.id))
        assertThat(commandMessage.aggregateId.tenantId, equalTo(command.tenantId))
    }
}
