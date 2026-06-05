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

package me.ahoo.wow.modeling

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.annotation.StaticTenantId
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test

class AggregateIdBehaviorTest {

    @Test
    fun `aggregate ids are equal only when tenant named aggregate and id match`() {
        val first = MOCK_AGGREGATE_METADATA.aggregateId(id = "aggregate-1", tenantId = "tenant-1")
        val same = first.copy()
        val differentTenant = first.copy(tenantId = "tenant-2")
        val differentId = first.copy(id = "aggregate-2")

        first.assert().isEqualTo(same)
        first.hashCode().assert().isEqualTo(same.hashCode())
        first.assert().isNotEqualTo(differentTenant)
        first.assert().isNotEqualTo(differentId)
        first.toString().assert()
            .isEqualTo(
                "AggregateId(contextName=${first.contextName}, aggregateName=${first.aggregateName}, " +
                    "tenantId=${first.tenantId}, id=${first.id})"
            )
    }

    @Test
    fun `aggregate ids compare by id inside the same named aggregate`() {
        val namedAggregate = MaterializedNamedAggregate("context", "aggregate")
        val first = namedAggregate.aggregateId("001")
        val second = namedAggregate.aggregateId("002")

        first.compareTo(second).assert().isLessThan(0)
        listOf(second, first).sorted().assert().containsSequence(first, second)
    }

    @Test
    fun `aggregate id comparison rejects different named aggregates`() {
        val first = MaterializedNamedAggregate("context", "first").aggregateId("001")
        val second = MaterializedNamedAggregate("context", "second").aggregateId("002")

        assertThrownBy<IllegalArgumentException> {
            first.compareTo(second)
        }
    }

    @Test
    fun `aggregate metadata aggregateId uses static tenant id by default`() {
        val aggregateId = aggregateMetadata<MockStaticAggregate, MockStaticAggregate>().aggregateId("static-id")

        aggregateId.id.assert().isEqualTo("static-id")
        aggregateId.tenantId.assert().isEqualTo("static-tenant-id")
    }

    @Test
    fun `named aggregate aggregateId uses default tenant when none is supplied`() {
        val aggregateId = MaterializedNamedAggregate("context", "aggregate").aggregateId("aggregate-1")

        aggregateId.tenantId.assert().isEqualTo(TenantId.DEFAULT_TENANT_ID)
        aggregateId.namedAggregate.assert().isInstanceOf(MaterializedNamedAggregate::class.java)
    }
}

@StaticTenantId("static-tenant-id")
class MockStaticAggregate(private val id: String)
