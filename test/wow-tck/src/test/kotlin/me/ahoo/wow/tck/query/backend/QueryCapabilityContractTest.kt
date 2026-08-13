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

package me.ahoo.wow.tck.query.backend

import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendDescriptor
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryPlanVersion
import me.ahoo.wow.query.backend.QueryPortableFeature
import me.ahoo.wow.query.plan.CountQueryPlanV1
import me.ahoo.wow.query.validation.QueryBudgetLimit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicLong

class QueryCapabilityContractTest {
    private val fixture = InMemoryCapabilityFixture()

    @TestFactory
    fun driverNeutralCapabilityContractVerifiesEveryMatrixBranch() =
        QueryCapabilityContract(fixture).dynamicTests()

    @Test
    fun matrixContainsTwelveCombinationsPlusGrantDenyDominance() {
        val cases = QueryCapabilityContract.cases

        assertEquals(13, cases.size)
        assertEquals(12, cases.count { !it.grantDenyDominance })
        assertEquals(1, cases.count(QueryCapabilityCase::grantDenyDominance))
        assertEquals(
            setOf(
                QueryCapabilityPolicyDecision.GRANT,
                QueryCapabilityPolicyDecision.DENY,
                QueryCapabilityPolicyDecision.ABSTAIN,
            ),
            cases.filterNot(QueryCapabilityCase::grantDenyDominance)
                .map(QueryCapabilityCase::policyDecision)
                .toSet(),
        )
    }
}

private class InMemoryCapabilityFixture : QueryCapabilityFixture {
    private val rawCommandCounter = AtomicLong()
    override val id: String = "in-memory-full-text"
    override val capabilityId: QueryCapabilityId = QueryCapabilityId("full-text")
    override val target = PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT)
    override val schema = PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT)
    override val expression = FullTextExpression(
        capabilityId,
        "portable",
        setOf(PortableQueryDataset.TITLE),
    )
    override val backendFactory = me.ahoo.wow.query.backend.QueryBackendFactory {
        object : QueryBackend {
            override val descriptor = QueryBackendDescriptor(
                backendId = "in-memory-capability",
                documentKinds = setOf(QueryDocumentKind.SNAPSHOT),
                planVersions = setOf(QueryPlanVersion.V1),
                portableOperators = me.ahoo.wow.api.query.expression.PortableOperator.entries.toSet(),
                portableFeatures = QueryPortableFeature.entries.toSet(),
                stringComparisonModes = me.ahoo.wow.api.query.expression.StringComparisonMode.entries.toSet(),
                capabilities = setOf(capabilityId),
                maxBudget = QueryBudgetLimit.UNBOUNDED,
            )

            override fun readiness(): Mono<QueryBackendReadiness> = Mono.just(QueryBackendReadiness.Ready)

            override fun count(plan: CountQueryPlanV1): Mono<Long> = Mono.fromSupplier {
                rawCommandCounter.incrementAndGet()
                1L
            }

            override fun <R : Any> single(plan: me.ahoo.wow.query.plan.SingleQueryPlanV1<R>) =
                Mono.error<R>(UnsupportedOperationException())

            override fun <R : Any> list(plan: me.ahoo.wow.query.plan.ListQueryPlanV1<R>) =
                reactor.core.publisher.Flux.error<R>(UnsupportedOperationException())

            override fun <R : Any> page(plan: me.ahoo.wow.query.plan.PageQueryPlanV1<R>) =
                Mono.error<me.ahoo.wow.api.query.gateway.QueryPage<R>>(UnsupportedOperationException())
        }
    }
    override val rawCommands: Map<String, Long>
        get() = rawCommandCounter.get().takeIf { it > 0 }?.let { mapOf("count" to it) }.orEmpty()
    override val successfulRawCommands: Map<String, Long> = mapOf("count" to 1L)

    override fun reset() {
        rawCommandCounter.set(0)
    }
}
