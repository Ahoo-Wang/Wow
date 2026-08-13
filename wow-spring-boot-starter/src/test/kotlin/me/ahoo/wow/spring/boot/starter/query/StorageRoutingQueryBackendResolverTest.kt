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
package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendDescriptor
import me.ahoo.wow.query.backend.QueryBackendFactory
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.query.backend.QueryPlanVersion
import me.ahoo.wow.query.backend.QueryPortableFeature
import me.ahoo.wow.query.plan.CountQueryPlanV1
import me.ahoo.wow.query.plan.ListQueryPlanV1
import me.ahoo.wow.query.plan.PageQueryPlanV1
import me.ahoo.wow.query.plan.SingleQueryPlanV1
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QuerySystemFields
import me.ahoo.wow.query.validation.QueryBudgetLimit
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.QueryBackendBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.QueryBackendRouteSnapshot
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.QueryBackendSelection
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicInteger

class StorageRoutingQueryBackendResolverTest {
    @Test
    fun `context resolution binds the selected factory once per subscription`() {
        val factory = RecordingBackendFactory()
        val binding = backendBinding(factory)
        val resolver = StorageRoutingQueryBackendResolver(
            QueryBackendRouteSnapshot(
                defaultSelections = mapOf(QueryDocumentKind.SNAPSHOT to QueryBackendSelection.available(binding)),
                routeOverrides = emptyMap(),
            ),
        )
        val context = resolutionContext()

        StepVerifier.create(resolver.resolve(context))
            .assertNext { resolved ->
                resolved.backend.assert().isSameAs(factory.backend)
                resolved.routeIdentity.value.assert().isEqualTo("mongo-snapshot-store:SNAPSHOT")
            }
            .verifyComplete()

        factory.bindCount.get().assert().isEqualTo(1)
        factory.context.assert().isSameAs(context)
    }

    @Test
    fun `target only compatibility resolution is always unavailable`() {
        val factory = RecordingBackendFactory()
        val resolver = resolver(QueryBackendSelection.available(backendBinding(factory)))

        StepVerifier.create(resolver.resolve(TARGET))
            .expectErrorSatisfies(::assertUnavailable)
            .verify()

        factory.bindCount.get().assert().isEqualTo(0)
    }

    @Test
    fun `unavailable route fails closed without binding a different backend`() {
        val resolver = resolver(QueryBackendSelection.unavailable())

        StepVerifier.create(resolver.resolve(resolutionContext()))
            .expectErrorSatisfies(::assertUnavailable)
            .verify()
    }

    @Test
    fun `factory binding failure is mapped to a low information unavailable error`() {
        val secret = "mongodb://secret-host/private-database"
        val factory = QueryBackendFactory { throw IllegalStateException(secret) }
        val resolver = resolver(QueryBackendSelection.available(backendBinding(factory)))

        StepVerifier.create(resolver.resolve(resolutionContext()))
            .expectErrorSatisfies { error ->
                assertUnavailable(error)
                error.message.orEmpty().contains(secret).assert().isFalse()
                error.cause.assert().isNull()
            }
            .verify()
    }

    @Test
    fun `unsupported descriptor capability fails after bind and before readiness`() {
        val capability = QueryCapabilityId("full-text")
        val backend = ReadyBackend()
        val factory = RecordingBackendFactory(backend)
        val resolver = resolver(QueryBackendSelection.available(backendBinding(factory)))
        val expression = LogicalExpression(
            LogicalOperator.AND,
            listOf(MatchAll, FullTextExpression(capability, "query", setOf(CONTENT)))
        )

        StepVerifier.create(resolver.resolve(resolutionContext(expression)))
            .expectErrorSatisfies(::assertUnsupported)
            .verify()

        factory.bindCount.get().assert().isOne()
        backend.readinessCount.get().assert().isZero()
    }

    @Test
    fun `native backend mismatch fails after bind and before readiness`() {
        val capability = QueryCapabilityId("x-wow:mongo-native")
        val backend = ReadyBackend(capabilities = setOf(capability))
        val factory = RecordingBackendFactory(backend)
        val resolver = resolver(QueryBackendSelection.available(backendBinding(factory)))
        val expression = NativeExpression(
            capabilityId = capability,
            backendId = "elasticsearch",
            templateId = "registered",
            parameters = emptyMap(),
            declaredFields = setOf(CONTENT)
        )

        StepVerifier.create(resolver.resolve(resolutionContext(expression)))
            .expectErrorSatisfies(::assertUnsupported)
            .verify()

        factory.bindCount.get().assert().isOne()
        backend.readinessCount.get().assert().isZero()
    }

    @Test
    fun `safe factory query exception preserves its identity`() {
        val expected = QueryException(
            QueryErrorCode.UNSUPPORTED_CAPABILITY,
            QueryStage.PLANNING,
            QueryErrorReason.CAPABILITY_DENIED
        )
        val resolver = resolver(
            QueryBackendSelection.available(
                backendBinding(QueryBackendFactory { throw expected })
            )
        )

        StepVerifier.create(resolver.resolve(resolutionContext()))
            .expectErrorSatisfies { actual -> actual.assert().isSameAs(expected) }
            .verify()
    }

    @TestFactory
    fun `fatal factory binding failures preserve their identity`(): List<DynamicTest> = listOf(
        "VirtualMachineError" to object : VirtualMachineError("fatal-vm") {},
        "LinkageError" to LinkageError("fatal-linkage"),
        "ThreadDeath" to ThreadDeath(),
    ).map { (type, fatal) ->
        DynamicTest.dynamicTest(type) {
            val factory = QueryBackendFactory { throw fatal }
            val resolver = resolver(QueryBackendSelection.available(backendBinding(factory)))

            val propagated: Throwable? = try {
                StepVerifier.create(resolver.resolve(resolutionContext()))
                    .verifyComplete()
                null
            } catch (actual: Throwable) {
                actual
            }

            propagated.assert().isSameAs(fatal)
        }
    }

    private fun resolver(selection: QueryBackendSelection): StorageRoutingQueryBackendResolver =
        StorageRoutingQueryBackendResolver(
            QueryBackendRouteSnapshot(
                defaultSelections = mapOf(QueryDocumentKind.SNAPSHOT to selection),
                routeOverrides = emptyMap(),
            ),
        )

    private fun backendBinding(factory: QueryBackendFactory): QueryBackendBinding = QueryBackendBinding.named(
        name = "mongo-snapshot-store",
        documentKind = QueryDocumentKind.SNAPSHOT,
        storage = StorageType.MONGO,
        backendFactory = factory,
    )

    private fun resolutionContext(
        expression: me.ahoo.wow.api.query.expression.QueryExpression = MatchAll
    ): QueryBackendResolutionContext = QueryBackendResolutionContext(
        TARGET,
        QuerySchema(TARGET, QuerySystemFields.fields(QueryDocumentKind.SNAPSHOT)),
        expression,
    )

    private fun assertUnavailable(error: Throwable) {
        (error as QueryException).let { queryError ->
            queryError.code.assert().isEqualTo(QueryErrorCode.BACKEND_NOT_READY)
            queryError.stage.assert().isEqualTo(QueryStage.BACKEND_RESOLUTION)
            queryError.reason.assert().isEqualTo(QueryErrorReason.BACKEND_UNAVAILABLE)
        }
    }

    private fun assertUnsupported(error: Throwable) {
        (error as QueryException).apply {
            code.assert().isEqualTo(QueryErrorCode.UNSUPPORTED_CAPABILITY)
            stage.assert().isEqualTo(QueryStage.PLANNING)
            reason.assert().isEqualTo(QueryErrorReason.CAPABILITY_DENIED)
        }
    }

    companion object {
        private val TARGET = QueryTarget(
            MaterializedNamedAggregate("order-service", "order"),
            QueryDocumentKind.SNAPSHOT,
        )
        private val CONTENT = LogicalField("state.content")
    }
}

private class RecordingBackendFactory(
    val backend: ReadyBackend = ReadyBackend()
) : QueryBackendFactory {
    val bindCount = AtomicInteger()
    var context: QueryBackendResolutionContext? = null

    override fun bind(context: QueryBackendResolutionContext): QueryBackend {
        bindCount.incrementAndGet()
        this.context = context
        return backend
    }
}

private class ReadyBackend(
    capabilities: Set<QueryCapabilityId> = emptySet()
) : QueryBackend {
    val readinessCount = AtomicInteger()
    override val descriptor: QueryBackendDescriptor = QueryBackendDescriptor(
        backendId = "recording",
        documentKinds = setOf(QueryDocumentKind.SNAPSHOT),
        planVersions = setOf(QueryPlanVersion.V1),
        portableOperators = PortableOperator.entries.toSet(),
        portableFeatures = QueryPortableFeature.entries.toSet(),
        stringComparisonModes = StringComparisonMode.entries.toSet(),
        capabilities = capabilities,
        maxBudget = QueryBudgetLimit.UNBOUNDED,
    )

    override fun readiness(): Mono<QueryBackendReadiness> = Mono.fromSupplier {
        readinessCount.incrementAndGet()
        QueryBackendReadiness.Ready
    }

    override fun <R : Any> single(plan: SingleQueryPlanV1<R>): Mono<R> = Mono.error(AssertionError("unexpected"))

    override fun <R : Any> list(plan: ListQueryPlanV1<R>): Flux<R> = Flux.error(AssertionError("unexpected"))

    override fun <R : Any> page(plan: PageQueryPlanV1<R>): Mono<QueryPage<R>> = Mono.error(AssertionError("unexpected"))

    override fun count(plan: CountQueryPlanV1): Mono<Long> = Mono.error(AssertionError("unexpected"))
}
