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

package me.ahoo.wow.command.factory

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.isLocalFirst
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test

class MutableCommandBuilderTest {

    @Test
    fun `should expose default builder values`() {
        val body = FactoryBuilderCommand("initial")
        val builder = MutableCommandBuilder(body)

        builder.body.assert().isSameAs(body)
        builder.id.assert().isNotBlank()
        builder.requestId.assert().isNull()
        builder.aggregateId.assert().isNull()
        builder.tenantId.assert().isNull()
        builder.ownerId.assert().isNull()
        builder.spaceId.assert().isNull()
        builder.aggregateVersion.assert().isNull()
        builder.namedAggregate.assert().isNull()
        builder.header.assert().isEmpty()
        builder.createTime.assert().isGreaterThan(0)
        builder.upstream.assert().isNull()
        builder.ownerIdSameAsAggregateId.assert().isFalse()
    }

    @Test
    fun `should mutate builder values with fluent API`() {
        val initialBody = FactoryBuilderCommand("initial")
        val replacementBody = FactoryBuilderCommand("replacement")
        val header = DefaultHeader.empty().with("source", "builder")
        val namedAggregate = MaterializedNamedAggregate("factory", "account")
        val builder = initialBody.commandBuilder()

        val returned = builder
            .body(replacementBody)
            .id("command-1")
            .requestId("request-1")
            .aggregateId("aggregate-1")
            .tenantId("tenant-1")
            .ownerId("owner-1")
            .spaceId("space-1")
            .aggregateVersion(7)
            .namedAggregate(namedAggregate)
            .header(header)
            .createTime(1234)
            .ownerIdSameAsAggregateId(true)

        returned.assert().isSameAs(builder)
        builder.body.assert().isSameAs(replacementBody)
        builder.bodyAs<FactoryBuilderCommand>().value.assert().isEqualTo("replacement")
        builder.id.assert().isEqualTo("command-1")
        builder.requestId.assert().isEqualTo("request-1")
        builder.aggregateId.assert().isEqualTo("aggregate-1")
        builder.tenantId.assert().isEqualTo("tenant-1")
        builder.ownerId.assert().isEqualTo("owner-1")
        builder.spaceId.assert().isEqualTo("space-1")
        builder.aggregateVersion.assert().isEqualTo(7)
        builder.namedAggregate.assert().isSameAs(namedAggregate)
        builder.header.assert().isSameAs(header)
        builder.createTime.assert().isEqualTo(1234)
        builder.ownerIdSameAsAggregateId.assert().isTrue()
    }

    @Test
    fun `should mutate header and absent values only when absent`() {
        val builder = FactoryBuilderCommand("body").commandBuilder()

        builder.requestIdIfAbsent("request-1")
            .requestIdIfAbsent("request-2")
            .tenantIdIfAbsent("tenant-1")
            .tenantIdIfAbsent("tenant-2")
            .spaceIdIfAbsent("space-1")
            .spaceIdIfAbsent("space-2")
            .header {
                it.with("custom", "value")
            }.localFirst()

        builder.requestId.assert().isEqualTo("request-1")
        builder.tenantId.assert().isEqualTo("tenant-1")
        builder.spaceId.assert().isEqualTo("space-1")
        builder.header["custom"].assert().isEqualTo("value")
        builder.header.isLocalFirst().assert().isTrue()
    }
}

private data class FactoryBuilderCommand(val value: String)
