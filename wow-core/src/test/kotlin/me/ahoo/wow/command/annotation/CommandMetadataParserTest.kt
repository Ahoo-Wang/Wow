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
package me.ahoo.wow.command.annotation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.command.MockCommandWithAllowCreate
import me.ahoo.wow.command.MockCommandWithDefaultNamedId
import me.ahoo.wow.command.MockCommandWithExpectedAggregateVersion
import me.ahoo.wow.command.MockCommandWithoutTargetAggregateId
import me.ahoo.wow.command.MockCreateCommand
import me.ahoo.wow.command.MockCreateCommandWithoutAggregateId
import me.ahoo.wow.command.MockInheritStaticCommand
import me.ahoo.wow.command.MockNamedCommand
import me.ahoo.wow.command.MockStaticCommand
import me.ahoo.wow.command.NAMED_COMMAND
import org.junit.jupiter.api.Test

/**
 * AnnotationCommandParserTest .
 *
 * @author ahoo wang
 */
internal class CommandMetadataParserTest {

    @Test
    fun parseCreateWithoutTargetAggregateId() {
        commandMetadata<MockCreateCommandWithoutAggregateId>()
    }

    @Test
    fun parseWithDefaultNamedId() {
        val metadata = commandMetadata<MockCommandWithDefaultNamedId>()
        metadata.isCreate.assert().isEqualTo(false)
        metadata.staticTenantId.assert().isNull()
        metadata.aggregateIdGetter.assert().isNotNull()
    }

    @Test
    fun parseWithAllowCreate() {
        val metadata = commandMetadata<MockCommandWithAllowCreate>()
        metadata.allowCreate.assert().isEqualTo(true)
        metadata.aggregateIdGetter.assert().isNotNull()
    }

    @Test
    fun parseWithoutTargetAggregateId() {
        val metadata = commandMetadata<MockCommandWithoutTargetAggregateId>()
        metadata.isCreate.assert().isEqualTo(false)
        metadata.aggregateIdGetter.assert().isNull()
    }

    @Test
    fun parseWithVersion() {
        val metadata = commandMetadata<MockCommandWithExpectedAggregateVersion>()
        metadata.assert().isNotNull()
        metadata.commandType.assert().isEqualTo(MockCommandWithExpectedAggregateVersion::class.java,)
        metadata.isCreate.assert().isEqualTo(false)
        metadata.aggregateIdGetter.assert().isNotNull()
        metadata.aggregateVersionGetter.assert().isNotNull()
        val command = MockCommandWithExpectedAggregateVersion("1", 1)
        metadata.aggregateIdGetter!![command].assert().isEqualTo("1")
        metadata.aggregateVersionGetter.assert().isNotNull()
        metadata.aggregateVersionGetter!![command].assert().isEqualTo(1)
    }

    @Test
    fun parseWithCreateAggregate() {
        val metadata = commandMetadata<MockCreateCommand>()
        metadata.assert().isNotNull()
        metadata.commandType.assert().isEqualTo(MockCreateCommand::class.java,)
        metadata.isCreate.assert().isEqualTo(true)
        metadata.aggregateIdGetter.assert().isNotNull()
        metadata.aggregateVersionGetter.assert().isEqualTo(null)
    }

    @Test
    fun parseWithCommandName() {
        val metadata = commandMetadata<MockNamedCommand>()
        metadata.assert().isNotNull()
        metadata.commandType.assert().isEqualTo(MockNamedCommand::class.java,)
        metadata.name.assert().isEqualTo(NAMED_COMMAND)
        metadata.isCreate.assert().isEqualTo(false)
        metadata.aggregateIdGetter.assert().isNotNull()
        metadata.aggregateVersionGetter.assert().isEqualTo(null)
    }

    @Test
    fun parseWithStatic() {
        val metadata = commandMetadata<MockStaticCommand>()
        metadata.assert().isNotNull()
        metadata.staticTenantId.assert().isEqualTo(TenantId.DEFAULT_TENANT_ID)
        metadata.commandType.assert().isEqualTo(MockStaticCommand::class.java,)
        metadata.aggregateIdGetter!![MockStaticCommand()].assert().isEqualTo("staticAggregateId")
        metadata.tenantIdGetter!![MockStaticCommand()].assert().isEqualTo(TenantId.DEFAULT_TENANT_ID)
    }

    @Test
    fun parseWithInheritStatic() {
        val metadata = commandMetadata<MockInheritStaticCommand>()
        metadata.assert().isNotNull()
        metadata.commandType.assert().isEqualTo(MockInheritStaticCommand::class.java,)
        metadata.aggregateIdGetter!![MockInheritStaticCommand()].assert().isEqualTo("staticAggregateId")
        metadata.tenantIdGetter!![MockInheritStaticCommand()].assert().isEqualTo(TenantId.DEFAULT_TENANT_ID)
    }
}
