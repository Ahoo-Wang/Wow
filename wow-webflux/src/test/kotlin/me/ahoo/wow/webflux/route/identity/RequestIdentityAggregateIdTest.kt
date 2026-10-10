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
package me.ahoo.wow.webflux.route.identity

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest

class RequestIdentityAggregateIdTest {

    @Test
    fun `should name the aggregate by the path tenant and id`() {
        val aggregateId = generateGlobalId()
        val tenantId = generateGlobalId()
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, aggregateId)
            .pathVariable(MessageRecords.TENANT_ID, tenantId)
            .build()

        val named = request.identity(MOCK_AGGREGATE_METADATA).aggregateId(MOCK_AGGREGATE_METADATA)

        named.id.assert().isEqualTo(aggregateId)
        named.tenantId.assert().isEqualTo(tenantId)
        named.contextName.assert().isEqualTo(MOCK_AGGREGATE_METADATA.contextName)
    }

    @Test
    fun `should use default tenant id when tenant is missing`() {
        val aggregateId = generateGlobalId()
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, aggregateId)
            .build()

        val named = request.identity(MOCK_AGGREGATE_METADATA).aggregateId(MOCK_AGGREGATE_METADATA)

        named.id.assert().isEqualTo(aggregateId)
        named.tenantId.assert().isEqualTo(TenantId.DEFAULT_TENANT_ID)
    }

    @Test
    fun `should reject a blank id path variable`() {
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, " ")
            .build()

        assertThrownBy<IllegalArgumentException> {
            request.identity(MOCK_AGGREGATE_METADATA).aggregateId(MOCK_AGGREGATE_METADATA)
        }
    }

    @Test
    fun `should reject a request that names no aggregate`() {
        assertThrownBy<IllegalArgumentException> {
            MockServerRequest.builder().build()
                .identity(MOCK_AGGREGATE_METADATA)
                .aggregateId(MOCK_AGGREGATE_METADATA)
        }
    }
}
