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

import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.test.spec.command.MockSendCommand
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class CommandFactoryTest {

    @Test
    fun create() {
        val command = MockSendCommand(GlobalIdGenerator.generateAsString())
        val commandMessage = command.asCommandMessage()
        assertThat(commandMessage.body, equalTo(command))
        assertThat(commandMessage.aggregateId.id, equalTo(command.id))
        assertThat(commandMessage.aggregateVersion, nullValue())
        assertThat(commandMessage.createTime, greaterThan(System.currentTimeMillis() - 2000))
    }

    @Test
    fun createGivenCreateAggregate() {
        val command = MockCreateCommand(GlobalIdGenerator.generateAsString())
        val commandMessage = command.asCommandMessage()
        assertThat(commandMessage.body, equalTo(command))
        assertThat(commandMessage.aggregateId.id, equalTo(command.id))
        assertThat(commandMessage.isCreate, equalTo(true))
        assertThat(commandMessage.aggregateVersion, equalTo(0))
        assertThat(commandMessage.createTime, greaterThan(System.currentTimeMillis() - 1000))
    }

    @Test
    fun createGivenNamed() {
        val command = MockNamedCommand(GlobalIdGenerator.generateAsString())
        val commandMessage = command.asCommandMessage()
        assertThat(commandMessage.body, equalTo(command))
        assertThat(commandMessage.name, equalTo(NAMED_COMMAND))
        assertThat(commandMessage.aggregateId.id, equalTo(command.id))
        assertThat(commandMessage.aggregateVersion, nullValue())
        assertThat(commandMessage.createTime, greaterThan(System.currentTimeMillis() - 1000))
    }

    @Test
    fun createWhenMergeHeader() {
        val command = MockNamedCommand(GlobalIdGenerator.generateAsString())
        val commandMessage = command.asCommandMessage()
        val additionalSource = HashMap<String, String>()
        val mergedMessage = commandMessage.mergeHeader(additionalSource)
        Assertions.assertNotSame(commandMessage, mergedMessage)
    }

    @Test
    fun createTenantCommand() {
        val command = MockTenantIdCommand(GlobalIdGenerator.generateAsString(), GlobalIdGenerator.generateAsString())
        val commandMessage = command.asCommandMessage()
        assertThat(commandMessage.aggregateId.id, equalTo(command.id))
        assertThat(commandMessage.aggregateId.tenantId, equalTo(command.tenantId))
    }
}
