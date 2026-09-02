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
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.openapi.CommonComponent
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest

class DefaultRewriteRequestFilterTest {

    @Test
    fun `should not rewrite filter when no tenant or owner headers`() {
        val request = MockServerRequest.builder().build()
        val originalFilter = IdFilter("id")
        val result = DefaultRewriteRequestFilter.rewrite(
            MOCK_AGGREGATE_METADATA,
            request,
            originalFilter
        )
        result.assert().isSameAs(originalFilter)
    }

    @Test
    fun `should append tenant filter from header`() {
        val tenantId = "tenant-123"
        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.TENANT_ID, tenantId)
            .build()
        val originalFilter = IdFilter("id")
        val result = DefaultRewriteRequestFilter.rewrite(
            MOCK_AGGREGATE_METADATA,
            request,
            ListQuery(originalFilter)
        )
        result.filter.assert().isEqualTo(AndFilter(listOf(originalFilter, TenantIdFilter(tenantId))))
    }

    @Test
    fun `should append owner filter from path variable`() {
        val ownerId = "owner-123"
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.OWNER_ID, ownerId)
            .build()
        val originalFilter = IdFilter("id")
        val result = DefaultRewriteRequestFilter.rewrite(
            MOCK_AGGREGATE_METADATA,
            request,
            ListQuery(originalFilter)
        )
        result.filter.assert().isEqualTo(AndFilter(listOf(originalFilter, OwnerIdFilter(ownerId))))
    }

    @Test
    fun `should append space filter from header`() {
        val spaceId = "space-123"
        val request = MockServerRequest.builder()
            .header(CommonComponent.Header.SPACE_ID, spaceId)
            .build()
        val originalFilter = IdFilter("id")
        val result = DefaultRewriteRequestFilter.rewrite(
            MOCK_AGGREGATE_METADATA,
            request,
            ListQuery(originalFilter)
        )
        result.filter.assert().isEqualTo(AndFilter(listOf(originalFilter, SpaceIdFilter(spaceId))))
    }

    @Test
    fun `generic query rewrite should preserve typed search fields`() {
        val search = SearchFilter(
            query = "wow",
            fields = linkedSetOf(QueryField("state.name"), QueryField("state.description")),
        )

        val rewritten = DefaultRewriteRequestFilter.rewrite(
            MOCK_AGGREGATE_METADATA,
            MockServerRequest.builder().build(),
            ListQuery(search),
        )

        rewritten.filter.assert().isEqualTo(search)
    }
}
