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

package me.ahoo.wow.command.metadata

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.AggregateName
import me.ahoo.wow.api.annotation.StaticTenantId
import me.ahoo.wow.api.command.DeleteAggregate
import me.ahoo.wow.command.annotation.commandMetadata
import org.junit.jupiter.api.Test

class CommandMetadataTest {

    @Test
    fun `should compare metadata by command type`() {
        val metadata = commandMetadata<MetadataCommand>()
        val copied = metadata.copy(name = "renamed")
        val other = commandMetadata<OtherMetadataCommand>()

        metadata.assert().isEqualTo(copied)
        metadata.hashCode().assert().isEqualTo(copied.hashCode())
        metadata.assert().isNotEqualTo(other)
        metadata.assert().isNotEqualTo(Any())
        metadata.toString().assert().isEqualTo(
            "CommandMetadata(commandType=class me.ahoo.wow.command.metadata.MetadataCommand)"
        )
    }

    @Test
    fun `should expose delete and static tenant derived values`() {
        val deleteMetadata = commandMetadata<DeleteMetadataCommand>()
        val staticTenantMetadata = commandMetadata<StaticTenantMetadataCommand>()

        deleteMetadata.isDelete.assert().isTrue()
        commandMetadata<MetadataCommand>().isDelete.assert().isFalse()
        staticTenantMetadata.staticTenantId.assert().isEqualTo("tenant-static")
    }
}

private data class MetadataCommand(
    @AggregateId val id: String = "aggregate-1",
    @AggregateName val aggregate: String = "metadata.account"
)

private data class OtherMetadataCommand(
    @AggregateId val id: String = "aggregate-2",
    @AggregateName val aggregate: String = "metadata.account"
)

private data class DeleteMetadataCommand(
    @AggregateId val id: String = "aggregate-3",
    @AggregateName val aggregate: String = "metadata.account"
) : DeleteAggregate

@StaticTenantId("tenant-static")
private data class StaticTenantMetadataCommand(
    @AggregateId val id: String = "aggregate-4",
    @AggregateName val aggregate: String = "metadata.account"
)
