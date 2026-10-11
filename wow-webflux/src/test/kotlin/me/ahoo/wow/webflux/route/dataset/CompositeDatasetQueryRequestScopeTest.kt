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

package me.ahoo.wow.webflux.route.dataset

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.QueryScope
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.rest.CommandHeaders
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.route.query.ScopeContributor
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.web.reactive.function.server.ServerRequest

class CompositeDatasetQueryRequestScopeTest {
    private val dataset = QueryDataset.of(MaterializedNamedAggregate("analytics", "orders")) {
        tenant("tenant_id")
        owner("owner_id")
        listOf("tenant_id", "owner_id", "region").forEach {
            field(it) {
                types(QueryValueType.STRING)
                nullable(false)
            }
        }
    }

    /** Restricts datasets by region, and aggregates not at all. */
    private val region = object : ScopeContributor {
        override fun contribute(
            aggregateMetadata: me.ahoo.wow.modeling.metadata.AggregateMetadata<*, *>,
            request: ServerRequest,
        ): QueryScope = error("not an aggregate route")

        override fun contribute(dataset: QueryDataset, request: ServerRequest): QueryScope =
            QueryScope(authenticated = filter { "region" eq "east" })
    }

    private val aggregatesOnly = ScopeContributor { _, _ -> QueryScope(authenticated = TenantIdFilter("t0")) }

    @Test
    fun `without contributors the host's scope is used as it is`() {
        CompositeDatasetQueryRequestScope.of(DefaultDatasetQueryRequestScope.INSTANCE, emptyList())
            .assert().isSameAs(DefaultDatasetQueryRequestScope.INSTANCE)
    }

    @Test
    fun `a contributor restricts a dataset only when it says so`() {
        val scope = CompositeDatasetQueryRequestScope.of(
            DefaultDatasetQueryRequestScope.INSTANCE,
            listOf(aggregatesOnly, region),
        )
        val request = MockServerRequest.builder()
            .header(CommandHeaders.TENANT_ID, "t1")
            .header(CommandHeaders.OWNER_ID, "alice")
            .build()
        scope.resolve(dataset, request).assert().isEqualTo(
            QueryScope(
                authenticated = filter { "region" eq "east" },
                declared = TenantIdFilter("t1").appendFilter(OwnerIdFilter("alice")),
            ),
        )
        aggregatesOnly.contribute(dataset, request).assert().isEqualTo(QueryScope.NONE)
        aggregatesOnly.contribute(MOCK_AGGREGATE_METADATA, request).authenticated
            .assert().isEqualTo(TenantIdFilter("t0"))
    }
}
