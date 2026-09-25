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
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.openapi.CommonComponent
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.query.QueryScope
import me.ahoo.wow.query.QueryScopeProvenance
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.web.reactive.function.server.ServerRequest

class DefaultQueryRequestScopeTest {

    @Test
    fun `should resolve match all when no scope is present`() {
        DefaultQueryRequestScope.resolve(MOCK_AGGREGATE_METADATA, MockServerRequest.builder().build())
            .assert().isEqualTo(QueryScope.NONE)
    }

    @Test
    fun `should resolve tenant scope from header`() {
        val tenantId = "tenant-123"
        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.TENANT_ID, tenantId)
            .build()

        DefaultQueryRequestScope.resolve(MOCK_AGGREGATE_METADATA, request)
            .assert().isEqualTo(QueryScope(declared = TenantIdFilter(tenantId)))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `should resolve owner scope from path variable`() {
        val ownerId = "owner-123"
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.OWNER_ID, ownerId)
            .build()

        DefaultQueryRequestScope.resolve(MOCK_AGGREGATE_METADATA, request)
            .assert().isEqualTo(QueryScope(declared = OwnerIdFilter(ownerId)))
    }

    @Test
    fun `should resolve space scope from header`() {
        val spaceId = "space-123"
        val request = MockServerRequest.builder()
            .header(CommonComponent.Header.SPACE_ID, spaceId)
            .build()

        DefaultQueryRequestScope.resolve(MOCK_AGGREGATE_METADATA, request)
            .assert().isEqualTo(QueryScope(declared = SpaceIdFilter(spaceId)))
    }

    @Test
    fun `a static tenant is authenticated, request-stated values are declared`() {
        val metadata = MOCK_AGGREGATE_METADATA.copy(staticTenantId = "static-tenant")
        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.OWNER_ID, "owner-123")
            .build()

        DefaultQueryRequestScope.resolve(metadata, request).assert().isEqualTo(
            QueryScope(authenticated = TenantIdFilter("static-tenant"), declared = OwnerIdFilter("owner-123"))
        )
    }

    @Test
    fun `a subclass can vouch for a request-stated value`() {
        val trustedTenantHeader = object : AbstractQueryRequestScope() {
            override fun ServerRequest.tenantIdProvenance(
                aggregateMetadata: AggregateMetadata<*, *>,
                tenantId: String,
            ): QueryScopeProvenance = QueryScopeProvenance.AUTHENTICATED
        }
        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.TENANT_ID, "tenant-123")
            .header(CommonComponent.Header.SPACE_ID, "space-123")
            .build()

        trustedTenantHeader.resolve(MOCK_AGGREGATE_METADATA, request).assert().isEqualTo(
            QueryScope(authenticated = TenantIdFilter("tenant-123"), declared = SpaceIdFilter("space-123"))
        )
    }
}
