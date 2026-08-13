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

package me.ahoo.wow.elasticsearch.query.backend

import io.mockk.confirmVerified
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.query.backend.QueryPlanVersion
import me.ahoo.wow.query.validation.QueryBudgetLimit
import me.ahoo.wow.tck.query.backend.PortableQueryDataset
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient

class ElasticsearchQueryBackendTest {
    @Test
    fun `factory binds synchronously without client io and advertises exact capabilities`() {
        val client = mockk<ReactiveElasticsearchClient>(relaxed = true)
        val budget = QueryBudgetLimit(maxResults = 512)
        val factory = ElasticsearchQueryBackendFactory(client, maxBudget = budget)
        val target = PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT)

        val backend = factory.bind(
            QueryBackendResolutionContext(
                target,
                PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT),
                MatchAll,
            ),
        )

        backend.descriptor.backendId.assert().isEqualTo("elasticsearch")
        backend.descriptor.documentKinds.assert().isEqualTo(QueryDocumentKind.entries.toSet())
        backend.descriptor.planVersions.assert().isEqualTo(setOf(QueryPlanVersion.V1))
        backend.descriptor.capabilities.assert().isEqualTo(
            setOf(
                QueryCapabilityId("full-text"),
                QueryCapabilityId("x-wow:elasticsearch-native"),
            ),
        )
        backend.descriptor.maxBudget.assert().isEqualTo(budget)
        confirmVerified(client)
    }
}
