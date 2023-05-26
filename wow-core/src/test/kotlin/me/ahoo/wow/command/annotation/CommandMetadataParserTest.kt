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
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
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
        assertThat(metadata.isCreate, equalTo(false))
        assertThat(metadata.aggregateIdGetter, notNullValue())
    }

    @Test
    fun parseWithAllowCreate() {
        val metadata = commandMetadata<MockCommandWithAllowCreate>()
        assertThat(metadata.isCreate, equalTo(true))
        assertThat(metadata.aggregateIdGetter, notNullValue())
    }

    @Test
    fun parseWithoutTargetAggregateId() {
        val metadata = commandMetadata<MockCommandWithoutTargetAggregateId>()
        assertThat(metadata.isCreate, equalTo(false))
        assertThat(metadata.aggregateIdGetter, nullValue())
    }

    @Test
    fun parseWithVersion() {
        val metadata = commandMetadata<MockCommandWithExpectedAggregateVersion>()
        assertThat(metadata, notNullValue())
        assertThat(
            metadata.commandType,
            equalTo(
                MockCommandWithExpectedAggregateVersion::class.java,
            ),
        )
        assertThat(metadata.isCreate, equalTo(false))
        assertThat(metadata.aggregateIdGetter, notNullValue())
        assertThat(metadata.aggregateVersionGetter, notNullValue())
        val command = MockCommandWithExpectedAggregateVersion("1", 1)
        assertThat(metadata.aggregateIdGetter!![command], equalTo("1"))
        assertThat(metadata.aggregateVersionGetter, notNullValue())
        assertThat(metadata.aggregateVersionGetter!![command], equalTo(1))
    }

    @Test
    fun parseWithCreateAggregate() {
        val metadata = commandMetadata<MockCreateCommand>()
        assertThat(metadata, notNullValue())
        assertThat(
            metadata.commandType,
            equalTo(
                MockCreateCommand::class.java,
            ),
        )
        assertThat(metadata.isCreate, equalTo(true))
        assertThat(metadata.aggregateIdGetter, notNullValue())
        assertThat(metadata.aggregateVersionGetter, equalTo(null))
    }

    @Test
    fun parseWithCommandName() {
        val metadata = commandMetadata<MockNamedCommand>()
        assertThat(metadata, notNullValue())
        assertThat(
            metadata.commandType,
            equalTo(
                MockNamedCommand::class.java,
            ),
        )
        assertThat(metadata.name, equalTo(NAMED_COMMAND))
        assertThat(metadata.isCreate, equalTo(false))
        assertThat(metadata.aggregateIdGetter, notNullValue())
        assertThat(metadata.aggregateVersionGetter, equalTo(null))
    }

    @Test
    fun parseWithStatic() {
        val metadata = commandMetadata<MockStaticCommand>()
        assertThat(metadata, notNullValue())
        assertThat(
            metadata.commandType,
            equalTo(
                MockStaticCommand::class.java,
            ),
        )
        assertThat(metadata.aggregateIdGetter!![MockStaticCommand()], equalTo("staticAggregateId"))
        assertThat(metadata.tenantIdGetter!![MockStaticCommand()], equalTo(TenantId.DEFAULT_TENANT_ID))
    }

    @Test
    fun parseWithInheritStatic() {
        val metadata = commandMetadata<MockInheritStaticCommand>()
        assertThat(metadata, notNullValue())
        assertThat(
            metadata.commandType,
            equalTo(
                MockInheritStaticCommand::class.java,
            ),
        )
        assertThat(metadata.aggregateIdGetter!![MockInheritStaticCommand()], equalTo("staticAggregateId"))
        assertThat(metadata.tenantIdGetter!![MockInheritStaticCommand()], equalTo(TenantId.DEFAULT_TENANT_ID))
    }
}
