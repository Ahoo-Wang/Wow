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

package me.ahoo.wow.modeling.metadata

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.modeling.annotation.MockCommandAggregate
import me.ahoo.wow.modeling.annotation.MockStateAggregate
import me.ahoo.wow.modeling.annotation.MockStateAggregateWithoutCtorCommand
import me.ahoo.wow.modeling.annotation.MockStateAggregateWithoutCtorState
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import org.junit.jupiter.api.Test

class AggregateMetadataBehaviorTest {

    @Test
    fun `aggregate metadata equality hash and string are based on command metadata`() {
        val metadata = aggregateMetadata<MockCommandAggregate, MockStateAggregate>()
        val same = aggregateMetadata<MockCommandAggregate, MockStateAggregate>()

        metadata.assert().isEqualTo(same)
        metadata.hashCode().assert().isEqualTo(metadata.command.hashCode())
        metadata.toString().assert().isEqualTo("AggregateMetadata(command=${metadata.command})")
        metadata.equals(Any()).assert().isFalse()
        metadata.isAggregationPattern.assert().isTrue()
    }

    @Test
    fun `extract aggregate id prefers state accessor over fallback id`() {
        val metadata = aggregateMetadata<MockCommandAggregate, MockStateAggregate>()
        val extracted = metadata.extractAggregateId(
            state = MockStateAggregate("state-id"),
            aggregateId = "fallback-id",
            tenantId = "tenant-1",
        )

        extracted.id.assert().isEqualTo("state-id")
        extracted.tenantId.assert().isEqualTo("tenant-1")
    }

    @Test
    fun `extract aggregate id uses fallback id when no state accessor exists`() {
        val metadata = aggregateMetadata<MockStateAggregateWithoutCtorCommand, MockStateAggregateWithoutCtorState>()

        val extracted = metadata.extractAggregateId(
            state = MockStateAggregateWithoutCtorState(),
            aggregateId = "fallback-id",
        )

        extracted.id.assert().isEqualTo("fallback-id")
        extracted.tenantId.assert().isEqualTo(TenantId.DEFAULT_TENANT_ID)
    }

    @Test
    fun `extract aggregate id rejects blank fallback when no state accessor exists`() {
        val metadata = aggregateMetadata<MockStateAggregateWithoutCtorCommand, MockStateAggregateWithoutCtorState>()

        assertThrownBy<IllegalArgumentException> {
            metadata.extractAggregateId(MockStateAggregateWithoutCtorState(), "")
        }
    }

    @Test
    fun `as aggregate metadata returns the existing metadata or resolves by named aggregate`() {
        val metadata = aggregateMetadata<MockCommandAggregate, MockStateAggregate>()

        metadata.asAggregateMetadata<MockCommandAggregate, MockStateAggregate>().assert().isSameAs(metadata)
        metadata.namedAggregate.asAggregateMetadata<MockCommandAggregate, MockStateAggregate>()
            .assert().isSameAs(metadata)
    }
}
