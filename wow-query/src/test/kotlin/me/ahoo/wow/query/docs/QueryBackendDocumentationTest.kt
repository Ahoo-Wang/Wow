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

package me.ahoo.wow.query.docs

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.query.GATEWAY_TARGET
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendDescriptor
import me.ahoo.wow.query.backend.QueryBackendFactory
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.query.backend.QueryPlanVersion
import me.ahoo.wow.query.backend.QueryPortableFeature
import me.ahoo.wow.query.gatewaySchema
import me.ahoo.wow.query.plan.CountQueryPlanV1
import me.ahoo.wow.query.plan.ListQueryPlanV1
import me.ahoo.wow.query.plan.PageQueryPlanV1
import me.ahoo.wow.query.plan.SingleQueryPlanV1
import me.ahoo.wow.query.validation.QueryBudgetLimit
import me.ahoo.wow.tck.query.backend.QueryBackendTestKit
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class QueryBackendDocumentationTest {
    @Test
    fun `backend factory binds one immutable context without doing IO`() {
        val context = QueryBackendResolutionContext(GATEWAY_TARGET, gatewaySchema(), MatchAll)
        val factory = QueryBackendFactory { bound ->
            bound.assert().isSameAs(context)
            DocumentationBackend
        }

        val backend = factory.bind(context)

        backend.descriptor.planVersions.assert().contains(QueryPlanVersion.V1)
        backend.readiness().block().assert().isEqualTo(QueryBackendReadiness.Ready)
        QueryBackendTestKit::class.java.simpleName.assert().isEqualTo("QueryBackendTestKit")
    }

    private object DocumentationBackend : QueryBackend {
        override val descriptor: QueryBackendDescriptor = QueryBackendDescriptor(
            backendId = "documentation",
            documentKinds = setOf(QueryDocumentKind.SNAPSHOT),
            planVersions = setOf(QueryPlanVersion.V1),
            portableOperators = PortableOperator.entries.toSet(),
            portableFeatures = QueryPortableFeature.entries.toSet(),
            stringComparisonModes = StringComparisonMode.entries.toSet(),
            capabilities = emptySet(),
            maxBudget = QueryBudgetLimit(maxResults = 1_000)
        )

        override fun readiness(): Mono<QueryBackendReadiness> = Mono.just(QueryBackendReadiness.Ready)

        override fun <R : Any> single(plan: SingleQueryPlanV1<R>): Mono<R> = Mono.empty()

        override fun <R : Any> list(plan: ListQueryPlanV1<R>): Flux<R> = Flux.empty()

        override fun <R : Any> page(plan: PageQueryPlanV1<R>): Mono<QueryPage<R>> = Mono.empty()

        override fun count(plan: CountQueryPlanV1): Mono<Long> = Mono.just(0)
    }
}
