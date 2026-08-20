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
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.PageQueryRequest
import me.ahoo.wow.api.query.gateway.QueryConsistency
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryPageSpec
import me.ahoo.wow.api.query.gateway.QueryProjection
import me.ahoo.wow.api.query.gateway.QueryResultShape
import me.ahoo.wow.api.query.gateway.QuerySort
import me.ahoo.wow.api.query.gateway.QuerySortDirection
import me.ahoo.wow.api.query.gateway.SingleQueryRequest
import me.ahoo.wow.query.policy.QueryFieldAccess
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import java.util.stream.Stream

abstract class SnapshotQueryBackendSpec : QueryBackendContractSpec(QueryDocumentKind.SNAPSHOT)

abstract class QueryBackendContractSpec protected constructor(
    documentKind: QueryDocumentKind
) : QueryBackendLifecycleSpec(documentKind) {
    @TestFactory
    fun portableSemantics(): Stream<DynamicTest> = PortableQueryDataset.semanticVectors(documentKind)
        .map { vector ->
            DynamicTest.dynamicTest(vector.id) {
                verifyVector(vector)
            }
        }.stream()

    @Test
    fun `single supports typed result`() {
        val testKit = testKit()
        val vector = operationVector(QueryOperation.SINGLE)
        val request = SingleQueryRequest(
            target = testKit.target,
            expression = vector.expression,
            resultShape = typedShape(),
            requestedScope = vector.requestedScope,
            sort = vector.sort
        )

        StepVerifier.create(withDataset(testKit.gateway.single(request)))
            .assertNext { result -> result.logicalId.assert().isEqualTo("d03") }
            .verifyComplete()
        assertContextResolution(testKit)
    }

    @Test
    fun `single supports dynamic result`() {
        val testKit = testKit()
        val vector = operationVector(QueryOperation.SINGLE)
        val request = SingleQueryRequest(
            target = testKit.target,
            expression = vector.expression,
            resultShape = QueryResultShape.Dynamic,
            requestedScope = vector.requestedScope,
            sort = vector.sort
        )

        StepVerifier.create(withDataset(testKit.gateway.single(request)))
            .assertNext { result -> logicalId(result).assert().isEqualTo("d03") }
            .verifyComplete()
        assertContextResolution(testKit)
    }

    @Test
    fun `single completes empty when no document matches`() {
        val testKit = testKit()
        val request = SingleQueryRequest(
            target = testKit.target,
            expression = idEquals("missing"),
            resultShape = QueryResultShape.Dynamic,
            sort = identitySort()
        )

        StepVerifier.create(withDataset(testKit.gateway.single(request))).verifyComplete()
        assertContextResolution(testKit)
    }

    @Test
    fun `list supports typed result with projection`() {
        val testKit = testKit()
        val vector = operationVector(QueryOperation.LIST)
        val request = ListQueryRequest(
            target = testKit.target,
            expression = vector.expression,
            resultShape = typedShape(),
            requestedScope = vector.requestedScope,
            sort = vector.sort,
            limit = 0
        )

        StepVerifier.create(withDataset(testKit.gateway.list(request)).map(PortableQueryResult::logicalId))
            .expectNext("d01", "d02", "d03")
            .verifyComplete()
        assertContextResolution(testKit)
    }

    @Test
    fun `list supports dynamic result`() {
        val testKit = testKit()
        val vector = operationVector(QueryOperation.LIST)
        val request = ListQueryRequest(
            target = testKit.target,
            expression = vector.expression,
            resultShape = QueryResultShape.Dynamic,
            requestedScope = vector.requestedScope,
            sort = vector.sort,
            limit = 0
        )

        StepVerifier.create(withDataset(testKit.gateway.list(request)).map(::logicalId))
            .expectNext("d01", "d02", "d03")
            .verifyComplete()
        assertContextResolution(testKit)
    }

    @Test
    fun `page supports typed result with exact total`() {
        val testKit = testKit()
        val vector = operationVector(QueryOperation.PAGE)
        val request = PageQueryRequest(
            target = testKit.target,
            expression = vector.expression,
            resultShape = typedShape(),
            requestedScope = vector.requestedScope,
            sort = vector.sort,
            page = QueryPageSpec(index = 1, size = 2)
        )

        StepVerifier.create(withDataset(testKit.gateway.page(request)))
            .assertNext { page ->
                page.items.map(PortableQueryResult::logicalId).assert().isEqualTo(listOf("d01", "d02"))
                page.total.assert().isEqualTo(3L)
                page.consistency.assert().isEqualTo(QueryConsistency.EXACT)
            }
            .verifyComplete()
        assertContextResolution(testKit)
    }

    @Test
    fun `page supports dynamic result with exact total`() {
        val testKit = testKit()
        val vector = operationVector(QueryOperation.PAGE)
        val request = PageQueryRequest(
            target = testKit.target,
            expression = vector.expression,
            resultShape = QueryResultShape.Dynamic,
            requestedScope = vector.requestedScope,
            sort = vector.sort,
            page = QueryPageSpec(index = 1, size = 2)
        )

        StepVerifier.create(withDataset(testKit.gateway.page(request)))
            .assertNext { page ->
                page.items.map(::logicalId).assert().isEqualTo(listOf("d01", "d02"))
                page.total.assert().isEqualTo(3L)
                page.consistency.assert().isEqualTo(QueryConsistency.EXACT)
            }
            .verifyComplete()
        assertContextResolution(testKit)
    }

    @Test
    fun `count returns exact total`() {
        val testKit = testKit()
        val vector = operationVector(QueryOperation.COUNT)
        val request = CountQueryRequest(
            target = testKit.target,
            expression = vector.expression,
            requestedScope = vector.requestedScope
        )

        StepVerifier.create(withDataset(testKit.gateway.count(request)))
            .expectNext(3L)
            .verifyComplete()
        assertContextResolution(testKit)
    }

    private fun verifyVector(vector: PortableQueryVector) {
        if (vector.key == PortableContractKey.Scenario(PortableQueryScenario.PROJECTION)) {
            verifyDynamicProjection(vector)
            return
        }
        val expectation = requireNotNull(vector.expectation(documentKind))
        val testKit = testKit()
        val results: Flux<String> = if (vector.projection == QueryProjection.All) {
            testKit.gateway.list(
                ListQueryRequest(
                    target = testKit.target,
                    expression = vector.expression,
                    resultShape = QueryResultShape.Dynamic,
                    requestedScope = vector.requestedScope,
                    sort = vector.sort.ifEmpty(::identitySort),
                    limit = 0
                )
            ).map(::logicalId)
        } else {
            testKit.gateway.list(
                ListQueryRequest(
                    target = testKit.target,
                    expression = vector.expression,
                    resultShape = QueryResultShape.Typed(PortableQueryResult::class.java, vector.projection),
                    requestedScope = vector.requestedScope,
                    sort = vector.sort.ifEmpty(::identitySort),
                    limit = 0
                )
            ).map(PortableQueryResult::logicalId)
        }
        if (expectation.error != null) {
            StepVerifier.create(results)
                .expectErrorSatisfies { error ->
                    (error as QueryException).code.assert().isEqualTo(expectation.error)
                }
                .verify()
            testKit.contextResolutionCount.assert().isZero()
            testKit.executionSubscriptionCount.assert().isZero()
        } else {
            StepVerifier.create(withDataset(results))
                .expectNextSequence(expectation.logicalIds)
                .verifyComplete()
            testKit.contextResolutionCount.assert().isOne()
            testKit.executionSubscriptionCount.assert().isOne()
        }
        testKit.targetOnlyResolutionCount.assert().isZero()
    }

    private fun verifyDynamicProjection(vector: PortableQueryVector) {
        val expectation = requireNotNull(vector.expectation(documentKind))
        val projection = vector.projection as QueryProjection.Include
        val requiredSystemFields = when (documentKind) {
            QueryDocumentKind.SNAPSHOT -> setOf(LogicalField("aggregateId"), LogicalField("deleted"))
            QueryDocumentKind.EVENT_STREAM -> setOf(LogicalField("id"))
        }
        val authorizedFields = projection.fields + requiredSystemFields
        val testKit = testKit(QueryFieldAccess.Restricted(authorizedFields))
        val results = testKit.gateway.list(
            ListQueryRequest(
                target = testKit.target,
                expression = vector.expression,
                resultShape = QueryResultShape.Dynamic,
                requestedScope = vector.requestedScope,
                sort = vector.sort.ifEmpty(::identitySort),
                limit = 0
            )
        )
        val expectedFields = authorizedFields.mapTo(LinkedHashSet(), LogicalField::value)

        StepVerifier.create(withDataset(results))
            .assertNext { document ->
                logicalId(document).assert().isEqualTo(expectation.logicalIds.single())
                document.keys.assert().isEqualTo(expectedFields)
                document.containsKey(PortableQueryDataset.TITLE.value).assert().isFalse()
            }
            .verifyComplete()
        assertContextResolution(testKit)
    }

    private fun operationVector(operation: QueryOperation): PortableQueryVector =
        PortableQueryDataset.vectors.single { it.key == PortableContractKey.Operation(operation) }

    private fun idEquals(logicalId: String): PredicateExpression = PredicateExpression(
        PortableQueryDataset.LOGICAL_ID,
        PortableOperator.EQ,
        listOf(QueryValue.StringValue(logicalId))
    )

    private fun typedShape(): QueryResultShape.Typed<PortableQueryResult> = QueryResultShape.Typed(
        PortableQueryResult::class.java,
        QueryProjection.Include(setOf(PortableQueryDataset.LOGICAL_ID))
    )

    private fun identitySort(): List<QuerySort> = listOf(
        QuerySort(PortableQueryDataset.LOGICAL_ID, QuerySortDirection.ASC)
    )

    private fun assertContextResolution(testKit: QueryBackendTestKit) {
        testKit.contextResolutionCount.assert().isOne()
        testKit.executionSubscriptionCount.assert().isOne()
        testKit.targetOnlyResolutionCount.assert().isZero()
    }
}
