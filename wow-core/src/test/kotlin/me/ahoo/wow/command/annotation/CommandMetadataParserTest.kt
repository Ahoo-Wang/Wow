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
import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.AggregateName
import me.ahoo.wow.api.annotation.AggregateVersion
import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.annotation.OwnerId
import me.ahoo.wow.api.annotation.StaticAggregateId
import me.ahoo.wow.api.annotation.StaticTenantId
import me.ahoo.wow.api.annotation.TenantId
import me.ahoo.wow.api.annotation.VoidCommand
import me.ahoo.wow.api.modeling.NamedAggregate
import org.junit.jupiter.api.Test

class CommandMetadataParserTest {

    @Test
    fun `should parse command property getters and command name`() {
        val metadata = commandMetadata<ParsedCommand>()
        val command = ParsedCommand(
            aggregateId = "aggregate-1",
            tenantId = "tenant-1",
            ownerId = "owner-1",
            version = 3
        )

        metadata.commandType.assert().isEqualTo(ParsedCommand::class.java)
        metadata.name.assert().isEqualTo("parsed-command")
        metadata.isCreate.assert().isFalse()
        metadata.allowCreate.assert().isFalse()
        metadata.isVoid.assert().isFalse()
        metadata.aggregateIdGetter!![command].assert().isEqualTo("aggregate-1")
        metadata.tenantIdGetter!![command].assert().isEqualTo("tenant-1")
        metadata.ownerIdGetter!![command].assert().isEqualTo("owner-1")
        metadata.aggregateVersionGetter!![command].assert().isEqualTo(3)
        metadata.namedAggregateGetter!!.getNamedAggregate(command).contextName.assert().isEqualTo("parser")
        metadata.namedAggregateGetter!!.getNamedAggregate(command).aggregateName.assert().isEqualTo("account")
    }

    @Test
    fun `should parse create allow create and void command flags`() {
        commandMetadata<ParsedCreateCommand>().isCreate.assert().isTrue()
        commandMetadata<ParsedAllowCreateCommand>().allowCreate.assert().isTrue()
        commandMetadata<ParsedVoidCommand>().isVoid.assert().isTrue()
    }

    @Test
    fun `should use default id property as aggregate id when aggregate id annotation is absent`() {
        val metadata = commandMetadata<ParsedDefaultIdCommand>()

        metadata.aggregateIdGetter!![ParsedDefaultIdCommand(id = "default-id")].assert().isEqualTo("default-id")
    }

    @Test
    fun `should parse commands without target aggregate id`() {
        val metadata = commandMetadata<ParsedUntargetedCommand>()

        metadata.aggregateIdGetter.assert().isNull()
        metadata.namedAggregateGetter!!.getNamedAggregate(ParsedUntargetedCommand()).aggregateName.assert()
            .isEqualTo("account")
    }

    @Test
    fun `should parse self named aggregate command`() {
        val metadata = commandMetadata<ParsedSelfNamedCommand>()
        val command = ParsedSelfNamedCommand(id = "aggregate-1")
        val namedAggregate = metadata.namedAggregateGetter!!.getNamedAggregate(command)

        namedAggregate.contextName.assert().isEqualTo("self-context")
        namedAggregate.aggregateName.assert().isEqualTo("self-aggregate")
        metadata.aggregateIdGetter!![command].assert().isEqualTo("aggregate-1")
    }

    @Test
    fun `should parse static aggregate and tenant annotations`() {
        val metadata = commandMetadata<ParsedStaticCommand>()
        val command = ParsedStaticCommand()

        metadata.aggregateIdGetter!![command].assert().isEqualTo("static-aggregate")
        metadata.tenantIdGetter!![command].assert().isEqualTo("static-tenant")
        metadata.staticTenantId.assert().isEqualTo("static-tenant")
    }
}

@Name("parsed-command")
private data class ParsedCommand(
    @AggregateId val aggregateId: String,
    @TenantId val tenantId: String,
    @OwnerId val ownerId: String,
    @AggregateVersion val version: Int?,
    @AggregateName val aggregate: String = "parser.account"
)

@CreateAggregate
private data class ParsedCreateCommand(
    @AggregateId val id: String = "create-id",
    @AggregateName val aggregate: String = "parser.account"
)

@AllowCreate
private data class ParsedAllowCreateCommand(
    @AggregateId val id: String = "allow-create-id",
    @AggregateName val aggregate: String = "parser.account"
)

@VoidCommand
private data class ParsedVoidCommand(
    @AggregateId val id: String = "void-id",
    @AggregateName val aggregate: String = "parser.account"
)

private data class ParsedDefaultIdCommand(
    val id: String,
    @AggregateName val aggregate: String = "parser.account"
)

private data class ParsedUntargetedCommand(
    @AggregateName val aggregate: String = "parser.account"
)

private data class ParsedSelfNamedCommand(
    @AggregateId val id: String,
    override val contextName: String = "self-context",
    override val aggregateName: String = "self-aggregate"
) : NamedAggregate

@StaticAggregateId("static-aggregate")
@StaticTenantId("static-tenant")
private data class ParsedStaticCommand(
    @AggregateName val aggregate: String = "parser.account"
)
