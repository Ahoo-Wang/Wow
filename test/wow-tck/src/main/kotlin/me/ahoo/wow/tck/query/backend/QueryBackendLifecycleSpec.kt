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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.QueryBudgetHint
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryResultShape
import me.ahoo.wow.api.query.gateway.QuerySort
import me.ahoo.wow.api.query.gateway.QuerySortDirection
import me.ahoo.wow.query.backend.QueryBackendFactory
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.policy.QueryFieldAccess
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration

enum class QueryBackendClientHold {
    BEFORE_FIRST_RESULT,
    AFTER_FIRST_RESULT
}

interface QueryBackendClientLifecycleProbe {
    val subscriptionCount: Long
    val cancellationCount: Long

    fun reset()

    fun holdNextList(hold: QueryBackendClientHold)
}

/**
 * TCK adapter that owns both backend creation and the lifecycle instrumentation for that backend's real client
 * publisher. [bind] must return a backend whose list execution reaches the transport publisher controlled and
 * observed by this exact instance. Implementations must attach observation to the driver command/cursor publisher,
 * never to a generic backend response wrapper. MongoDB and Elasticsearch specs adapt their production factory and
 * real driver instrumentation together in this test-only contract.
 */
interface ObservableQueryBackendFactory : QueryBackendFactory, QueryBackendClientLifecycleProbe

abstract class QueryBackendLifecycleSpec protected constructor(
    protected val documentKind: QueryDocumentKind
) {
    protected abstract fun backendFactory(): ObservableQueryBackendFactory

    protected abstract fun prepare(dataset: PortableQueryDataset): Mono<Void>

    protected abstract fun clear(): Mono<Void>

    protected open fun declaredCapabilities(): Set<QueryCapabilityId> = emptySet()

    protected open fun readinessFixture(): QueryBackendReadiness = QueryBackendReadiness.Ready

    protected fun testKit(fieldAccess: QueryFieldAccess = QueryFieldAccess.UNRESTRICTED): QueryBackendTestKit =
        testKit(backendFactory(), fieldAccess)

    protected fun testKit(
        backendFactory: ObservableQueryBackendFactory,
        fieldAccess: QueryFieldAccess = QueryFieldAccess.UNRESTRICTED
    ): QueryBackendTestKit =
        QueryBackendTestKit(
            backendFactory = backendFactory,
            documentKind = documentKind,
            expectedCapabilities = declaredCapabilities(),
            expectedReadiness = readinessFixture(),
            fieldAccess = fieldAccess
        )

    protected fun <T : Any> withDataset(publisher: Mono<T>): Mono<T> = Mono.usingWhen(
        Mono.just(Unit),
        { prepare(PortableQueryDataset).then(publisher) },
        { clear() },
        { _, _ -> clear() },
        { clear() }
    )

    protected fun <T : Any> withDataset(publisher: Flux<T>): Flux<T> = Flux.usingWhen(
        Mono.just(Unit),
        { prepare(PortableQueryDataset).thenMany(publisher) },
        { clear() },
        { _, _ -> clear() },
        { clear() }
    )

    @Test
    fun `list limit zero honors downstream demand`() {
        val testKit = testKit()
        val request = ListQueryRequest(
            target = testKit.target,
            expression = MatchAll,
            resultShape = QueryResultShape.Dynamic,
            sort = listOf(QuerySort(PortableQueryDataset.LOGICAL_ID, QuerySortDirection.ASC)),
            limit = 0
        )

        StepVerifier.create(withDataset(testKit.gateway.list(request)), 0)
            .expectSubscription()
            .then { assertTrue(testKit.listRequestCount in 1L..2L) }
            .expectNoEvent(Duration.ofMillis(10))
            .thenRequest(1)
            .assertNext { document -> logicalId(document).assert().isEqualTo("d01") }
            .expectNoEvent(Duration.ofMillis(10))
            .thenRequest(2)
            .assertNext { document -> logicalId(document).assert().isEqualTo("d02") }
            .assertNext { document -> logicalId(document).assert().isEqualTo("d03") }
            .thenCancel()
            .verify()

        assertTrue(testKit.listRequestCount >= 3)
        testKit.targetOnlyResolutionCount.assert().isZero()
    }

    @Test
    fun `downstream cancellation cancels backend client publisher`() {
        val observableFactory = backendFactory().apply {
            reset()
            holdNextList(QueryBackendClientHold.AFTER_FIRST_RESULT)
        }
        val testKit = testKit(observableFactory)
        val request = ListQueryRequest(
            target = testKit.target,
            expression = MatchAll,
            resultShape = QueryResultShape.Dynamic,
            sort = listOf(QuerySort(PortableQueryDataset.LOGICAL_ID, QuerySortDirection.ASC)),
            limit = 0
        )

        StepVerifier.create(withDataset(testKit.gateway.list(request)), 0)
            .expectSubscription()
            .thenRequest(1)
            .assertNext { document -> logicalId(document).assert().isEqualTo("d01") }
            .thenCancel()
            .verify()

        assertSame(observableFactory, testKit.backendFactory)
        observableFactory.subscriptionCount.assert().isOne()
        observableFactory.cancellationCount.assert().isOne()
        testKit.executionSubscriptionCount.assert().isOne()
        testKit.targetOnlyResolutionCount.assert().isZero()
    }

    @Test
    fun `deadline cancels backend client publisher before its first item`() {
        val observableFactory = backendFactory().apply {
            reset()
            holdNextList(QueryBackendClientHold.BEFORE_FIRST_RESULT)
        }
        val testKit = testKit(observableFactory)
        val vector = PortableQueryDataset.vectors.single {
            it.key == PortableContractKey.Lifecycle(PortableLifecycleCase.DEADLINE)
        }
        val request = ListQueryRequest(
            target = testKit.target,
            expression = vector.expression,
            resultShape = QueryResultShape.Dynamic,
            budget = QueryBudgetHint(timeout = Duration.ofMillis(50)),
            limit = 0
        )

        StepVerifier.create(withDataset(testKit.gateway.list(request)))
            .expectErrorSatisfies { error ->
                (error as QueryException).code.assert().isEqualTo(QueryErrorCode.DEADLINE_EXCEEDED)
            }
            .verify(Duration.ofSeconds(2))

        assertSame(observableFactory, testKit.backendFactory)
        observableFactory.subscriptionCount.assert().isOne()
        observableFactory.cancellationCount.assert().isOne()
        testKit.executionSubscriptionCount.assert().isOne()
        testKit.targetOnlyResolutionCount.assert().isZero()
    }

    @Test
    fun `unsupported capability fails before backend execution`() {
        val testKit = testKit()
        val vector = PortableQueryDataset.vectors.single {
            it.key == PortableContractKey.Capability(PortableQueryDataset.UNSUPPORTED_CAPABILITY)
        }
        val request = ListQueryRequest(
            target = testKit.target,
            expression = vector.expression,
            resultShape = QueryResultShape.Dynamic,
            limit = 0
        )

        StepVerifier.create(testKit.gateway.list(request))
            .expectErrorSatisfies { error ->
                (error as QueryException).code.assert().isEqualTo(QueryErrorCode.UNSUPPORTED_CAPABILITY)
            }
            .verify()

        testKit.contextResolutionCount.assert().isOne()
        testKit.executionSubscriptionCount.assert().isZero()
        testKit.targetOnlyResolutionCount.assert().isZero()
    }

    protected fun logicalId(document: DynamicDocument): String = document.getValue("logicalId")
}
