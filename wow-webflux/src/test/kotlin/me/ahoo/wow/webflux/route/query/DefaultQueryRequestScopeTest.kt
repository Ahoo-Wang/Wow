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

package me.ahoo.wow.webflux.route.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.openapi.CommonComponent
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest

class DefaultQueryRequestScopeTest {

    @Test
    fun `should resolve match all when no scope is present`() {
        DefaultQueryRequestScope.resolve(MOCK_AGGREGATE_METADATA, MockServerRequest.builder().build())
            .assert().isSameAs(MatchAllFilter)
    }

    @Test
    fun `should resolve tenant scope from header`() {
        val tenantId = "tenant-123"
        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.TENANT_ID, tenantId)
            .build()

        DefaultQueryRequestScope.resolve(MOCK_AGGREGATE_METADATA, request)
            .assert().isEqualTo(TenantIdFilter(tenantId))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `should resolve owner scope from path variable`() {
        val ownerId = "owner-123"
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.OWNER_ID, ownerId)
            .build()

        DefaultQueryRequestScope.resolve(MOCK_AGGREGATE_METADATA, request)
            .assert().isEqualTo(OwnerIdFilter(ownerId))
    }

    @Test
    fun `should resolve space scope from header`() {
        val spaceId = "space-123"
        val request = MockServerRequest.builder()
            .header(CommonComponent.Header.SPACE_ID, spaceId)
            .build()

        DefaultQueryRequestScope.resolve(MOCK_AGGREGATE_METADATA, request)
            .assert().isEqualTo(SpaceIdFilter(spaceId))
    }
}
