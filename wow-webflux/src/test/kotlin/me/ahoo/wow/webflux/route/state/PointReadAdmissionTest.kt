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

package me.ahoo.wow.webflux.route.state

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import me.ahoo.wow.query.QueryScope
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.route.query.QueryRequestScope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.reactive.function.server.MockServerRequest

class PointReadAdmissionTest {
    private val state = mockk<ReadOnlyStateAggregate<Any>> {
        every { aggregateId } returns mockk<AggregateId> { every { tenantId } returns "tenant" }
        every { ownerId } returns "owner"
        every { spaceId } returns "space"
    }
    private val request = MockServerRequest.builder().build()

    private fun admits(scope: QueryScope, enabled: Boolean = true) =
        PointReadAdmission(enabled, QueryRequestScope { _, _ -> scope }).admits(MOCK_AGGREGATE_METADATA, request, state)

    @Test
    fun `the scope is checked against the state header`() {
        admits(QueryScope.NONE).assert().isTrue()
        admits(
            QueryScope(
                authenticated = TenantIdFilter("tenant"),
                declared = AndFilter(listOf(OwnerIdFilter("owner"), SpaceIdFilter("space"))),
            )
        ).assert().isTrue()
        admits(QueryScope(declared = OwnerIdFilter("other"))).assert().isFalse()
        admits(QueryScope(authenticated = TenantIdFilter("other"))).assert().isFalse()
        admits(QueryScope(declared = SpaceIdFilter("other"))).assert().isFalse()
    }

    @Test
    fun `an unsupported scope node fails closed and admission off admits everything`() {
        admits(QueryScope(declared = IdFilter("id"))).assert().isFalse()
        admits(QueryScope(declared = OwnerIdFilter("other")), enabled = false).assert().isTrue()
    }

    @Test
    fun `the tracing cap applies only when enabled and non-zero`() {
        PointReadAdmission(enabled = true, tracingMaxVersions = 2).requireTracingVersions(2)
        assertThrows<IllegalArgumentException> {
            PointReadAdmission(enabled = true, tracingMaxVersions = 2).requireTracingVersions(3)
        }
        PointReadAdmission(enabled = true, tracingMaxVersions = 0).requireTracingVersions(Int.MAX_VALUE)
        PointReadAdmission(tracingMaxVersions = 2).requireTracingVersions(3)
        assertThrows<IllegalArgumentException> { PointReadAdmission(tracingMaxVersions = -1) }
    }
}
