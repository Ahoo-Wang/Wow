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

@file:OptIn(me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.spring.boot.starter.query

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.filter.PreAdmissionQueryFilter
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.gateway.GatewayEventStreamQueryServiceFactory
import me.ahoo.wow.query.gateway.GatewaySnapshotQueryServiceFactory
import me.ahoo.wow.query.gateway.QueryAuthority
import me.ahoo.wow.query.gateway.QueryAuthorityResolver
import me.ahoo.wow.query.gateway.QueryCall
import me.ahoo.wow.query.gateway.QueryCallResolver
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryExecutionException
import me.ahoo.wow.query.gateway.QueryExecutionMode
import me.ahoo.wow.query.gateway.QueryGateway
import me.ahoo.wow.query.gateway.QueryLegacyContextResolver
import me.ahoo.wow.query.gateway.QueryLegacyGrant
import me.ahoo.wow.query.gateway.QueryPurpose
import me.ahoo.wow.query.gateway.QueryRawServiceSource
import me.ahoo.wow.query.gateway.QueryResourceScope
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.query.gateway.QueryTrustedContextResolver
import me.ahoo.wow.query.gateway.withLegacyQueryCaller
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryFilter
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotQueryServiceFactoryBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.StorageRoutingAutoConfiguration
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.query.CountQueryHandlerFunction
import me.ahoo.wow.webflux.route.query.DefaultRewriteRequestCondition
import me.ahoo.wow.webflux.route.query.QueryWebTransportResolvers
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.ResolvableType
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicInteger

class QueryGatewayAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .enableWow()
        .withConfiguration(
            AutoConfigurations.of(
                QueryAutoConfiguration::class.java,
                StorageRoutingAutoConfiguration::class.java,
                QueryGatewayAutoConfiguration::class.java,
                QueryGatewayLegacyWiringRollbackAutoConfiguration::class.java,
            ),
        )

    @Test
    fun `framework managed factories and aggregate bean should use gateway facade`() {
        contextRunner
            .withUserConfiguration(TrustedQueryContextConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.assert().hasNotFailed()
                context.getBean(SnapshotQueryServiceFactory::class.java)
                    .assert().isInstanceOf(GatewaySnapshotQueryServiceFactory::class.java)
                context.getBean(EventStreamQueryServiceFactory::class.java)
                    .assert().isInstanceOf(GatewayEventStreamQueryServiceFactory::class.java)
                context.getBean(QueryRawServiceSource::class.java)
                    .assert().isInstanceOf(StorageBindingQueryRawServiceRegistry::class.java)
                (context.getBean(QueryRawServiceSource::class.java) is SnapshotQueryServiceFactory)
                    .assert().isFalse()

                val aggregateBean = context.getBean(SNAPSHOT_QUERY_SERVICE_BEAN, SnapshotQueryService::class.java)
                val aggregateType = MetadataSearcher.namedAggregateType.getValue(ORDER)
                    .aggregateMetadata<Any, Any>().state.aggregateType
                val genericType = ResolvableType.forClassWithGenerics(
                    SnapshotQueryService::class.java,
                    aggregateType,
                )
                context.getBeanProvider<Any>(genericType).getObject().assert().isSameAs(aggregateBean)
                context.getBean(SnapshotQueryServiceFactory::class.java).create<Any>(ORDER)
                    .assert().isSameAs(aggregateBean)

                StepVerifier.create(aggregateBean.count(Condition.all()))
                    .expectNext(0)
                    .verifyComplete()
                StepVerifier.create(context.getBean(SnapshotQueryHandler::class.java).count(ORDER, Condition.all()))
                    .expectNext(0)
                    .verifyComplete()
            }
    }

    @Test
    fun `default facade should fail closed when trusted call context is missing`() {
        contextRunner.run { context: AssertableApplicationContext ->
            val queryService = context.getBean(SnapshotQueryServiceFactory::class.java).create<Any>(ORDER)

            StepVerifier.create(queryService.count(Condition.all()))
                .expectErrorSatisfies { error ->
                    error.assert().isInstanceOf(QueryExecutionException::class.java)
                    (error as QueryExecutionException).code.assert().isEqualTo("QUERY_CALL_REQUIRED")
                    error.path.assert().isEqualTo("$.executionContext.call")
                }
                .verify()
        }
    }

    @Test
    fun `pre-admission filter cannot replace the gateway result`() {
        contextRunner
            .withUserConfiguration(
                TrustedQueryContextConfiguration::class.java,
                ResultReplacingPreAdmissionFilterConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert().hasNotFailed()
                StepVerifier.create(context.getBean(SnapshotQueryHandler::class.java).count(ORDER, Condition.all()))
                    .expectNext(0)
                    .verifyComplete()
            }
    }

    @Test
    fun `undeclared legacy query filter phase should fail startup`() {
        contextRunner
            .withUserConfiguration(UndeclaredQueryFilterConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.assert().hasFailed()
                context.startupFailure!!.message.assert().contains("QueryFilter must declare the pre-admission phase")
            }
    }

    @Test
    fun `process internal facade should require an exact registered legacy grant`() {
        contextRunner
            .withUserConfiguration(LegacyQueryGrantConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                val service = context.getBean(SnapshotQueryServiceFactory::class.java).create<Any>(ORDER)
                service.count(Condition.all())
                    .withLegacyQueryCaller("compensation-retry")
                    .test()
                    .expectNext(0)
                    .verifyComplete()

                service.count(Condition.all())
                    .withLegacyQueryCaller("another-caller")
                    .test()
                    .expectErrorSatisfies { error ->
                        error.assert().isInstanceOf(QueryExecutionException::class.java)
                        (error as QueryExecutionException).code.assert().isEqualTo("LEGACY_CALLER_NOT_ALLOWED")
                    }
                    .verify()
            }
    }

    @Test
    fun `direct process resolvers should remain available beside transport resolvers`() {
        contextRunner
            .withUserConfiguration(
                EmptyTrustedContextConfiguration::class.java,
                TrustedQueryContextConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                val service = context.getBean(SnapshotQueryServiceFactory::class.java).create<Any>(ORDER)

                StepVerifier.create(service.count(Condition.all()))
                    .expectNext(0)
                    .verifyComplete()
            }
    }

    @Test
    fun `authority-only resolver should serve direct gateway without becoming a facade pair`() {
        contextRunner
            .withUserConfiguration(AuthorityOnlyConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.assert().hasNotFailed()
                StepVerifier.create(
                    context.getBean(QueryGateway::class.java).count(
                        QueryCall(QueryTarget(ORDER, QueryDocumentKind.SNAPSHOT), PURPOSE),
                        Condition.all(),
                    ),
                )
                    .expectNext(0)
                    .verifyComplete()

                StepVerifier.create(
                    context.getBean(SnapshotQueryServiceFactory::class.java)
                        .create<Any>(ORDER)
                        .count(Condition.all()),
                )
                    .expectErrorSatisfies { error ->
                        error.assert().isInstanceOf(QueryExecutionException::class.java)
                        (error as QueryExecutionException).code.assert().isEqualTo("QUERY_CALL_REQUIRED")
                    }
                    .verify()
            }
    }

    @Test
    fun `web route should cross the runtime-owned authority channel before raw storage`() {
        CountingSnapshotQueryService.countCalls.set(0)
        contextRunner
            .withUserConfiguration(
                CountingRawSnapshotConfiguration::class.java,
                WebTrustedContextConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert().hasNotFailed()
                writeCountRoute(context).response.statusCode.assert().isEqualTo(HttpStatus.OK)
                CountingSnapshotQueryService.countCalls.get().assert().isEqualTo(1)
            }
    }

    @Test
    fun `missing web authority should stop before raw storage`() {
        CountingSnapshotQueryService.countCalls.set(0)
        contextRunner
            .withUserConfiguration(
                CountingRawSnapshotConfiguration::class.java,
                MissingWebAuthorityConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert().hasNotFailed()
                writeCountRoute(context).response.statusCode.assert().isEqualTo(HttpStatus.FORBIDDEN)
                CountingSnapshotQueryService.countCalls.get().assert().isZero()
            }
    }

    @Test
    fun `partial custom gateway override should fail with a stable diagnostic`() {
        contextRunner
            .withUserConfiguration(CustomGatewayConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.assert().hasFailed()
                generateSequence(context.startupFailure, Throwable::cause)
                    .mapNotNull(Throwable::message)
                    .joinToString("\n")
                    .assert().contains("Provide one complete QueryGatewayRuntime override instead")
            }
    }

    @Test
    fun `explicit legacy wiring rollback should bypass gateway and record activation metric`() {
        contextRunner
            .withPropertyValues("$QUERY_GATEWAY_LEGACY_WIRING_ROLLBACK_KEY=true")
            .withUserConfiguration(RollbackMetricsConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.assert().hasNotFailed()
                context.getBeansOfType(QueryGateway::class.java).assert().isEmpty()
                context.getBean(SnapshotQueryServiceFactory::class.java)
                    .assert().isNotInstanceOf(GatewaySnapshotQueryServiceFactory::class.java)
                context.getBean(EventStreamQueryServiceFactory::class.java)
                    .assert().isNotInstanceOf(GatewayEventStreamQueryServiceFactory::class.java)

                StepVerifier.create(
                    context.getBean(SnapshotQueryServiceFactory::class.java)
                        .create<Any>(ORDER)
                        .count(Condition.all()),
                )
                    .expectNext(0)
                    .verifyComplete()

                context.getBean(SimpleMeterRegistry::class.java)
                    .find("wow.query.gateway.legacy.wiring.rollback")
                    .counter()!!
                    .count()
                    .assert().isEqualTo(1.0)
            }
    }

    @Test
    fun `invalid legacy wiring rollback value should fail startup`() {
        contextRunner
            .withPropertyValues("$QUERY_GATEWAY_LEGACY_WIRING_ROLLBACK_KEY=treu")
            .run { context: AssertableApplicationContext ->
                context.assert().hasFailed()
                generateSequence(context.startupFailure, Throwable::cause)
                    .mapNotNull(Throwable::message)
                    .joinToString("\n")
                    .assert().contains("must be exactly true or false")
            }
    }

    @Configuration(proxyBeanMethods = false)
    class TrustedQueryContextConfiguration {
        @Bean
        fun queryCallResolver(): QueryCallResolver = QueryCallResolver { request ->
            Mono.just(QueryCall(request.target, PURPOSE))
        }

        @Bean
        fun queryAuthorityResolver(): QueryAuthorityResolver = QueryAuthorityResolver {
            Mono.just(QueryAuthority.System("query-gateway-test", "Spring facade test"))
        }
    }

    @Configuration(proxyBeanMethods = false)
    class ResultReplacingPreAdmissionFilterConfiguration {
        @Bean
        fun resultReplacingPreAdmissionFilter(): SnapshotQueryFilter =
            object : SnapshotQueryFilter, PreAdmissionQueryFilter {
                override fun filter(
                    context: QueryContext<*, *>,
                    next: FilterChain<QueryContext<*, *>>,
                ): Mono<Void> = next.filter(context).then(
                    Mono.fromRunnable {
                        context.asCountQuery().setResult(Mono.just(999))
                    },
                )
            }
    }

    @Configuration(proxyBeanMethods = false)
    class EmptyTrustedContextConfiguration {
        @Bean
        fun emptyTrustedContextResolver(): QueryTrustedContextResolver = QueryTrustedContextResolver { Mono.empty() }
    }

    @Configuration(proxyBeanMethods = false)
    class AuthorityOnlyConfiguration {
        @Bean
        fun queryAuthorityResolver(): QueryAuthorityResolver = QueryAuthorityResolver {
            Mono.just(QueryAuthority.System("direct-query-gateway-test", "Direct Gateway test"))
        }
    }

    @Configuration(proxyBeanMethods = false)
    class CustomGatewayConfiguration {
        @Bean
        fun customQueryGateway(): QueryGateway = mockk(relaxed = true)
    }

    @Configuration(proxyBeanMethods = false)
    class CountingRawSnapshotConfiguration {
        @Bean
        fun countingSnapshotQueryServiceFactoryBinding(): SnapshotQueryServiceFactoryBinding =
            SnapshotQueryServiceFactoryBinding.storage(StorageType.MONGO, CountingSnapshotQueryServiceFactory)
    }

    @Configuration(proxyBeanMethods = false)
    class WebTrustedContextConfiguration {
        @Bean
        fun webQueryTrustedContextResolver(): QueryTrustedContextResolver = QueryWebTransportResolvers {
            Mono.just(QueryAuthority.System("web-query-test", "Web vertical slice"))
        }
    }

    @Configuration(proxyBeanMethods = false)
    class MissingWebAuthorityConfiguration {
        @Bean
        fun webQueryTrustedContextResolver(): QueryTrustedContextResolver = QueryWebTransportResolvers { Mono.empty() }
    }

    @Configuration(proxyBeanMethods = false)
    class UndeclaredQueryFilterConfiguration {
        @Bean
        fun undeclaredQueryFilter(): SnapshotQueryFilter = object : SnapshotQueryFilter {
            override fun filter(
                context: QueryContext<*, *>,
                next: FilterChain<QueryContext<*, *>>,
            ): Mono<Void> = next.filter(context)
        }
    }

    @Configuration(proxyBeanMethods = false)
    class LegacyQueryGrantConfiguration {
        @Bean
        fun legacyQueryContextResolver(): QueryLegacyContextResolver = QueryLegacyContextResolver(
            listOf(
                QueryLegacyGrant(
                    callerId = "compensation-retry",
                    target = QueryTarget(ORDER, QueryDocumentKind.SNAPSHOT),
                    purpose = QueryPurpose("compensation-retry"),
                    executionMode = QueryExecutionMode.LEGACY,
                    resourceScope = QueryResourceScope(),
                ),
            ),
        )
    }

    @Configuration(proxyBeanMethods = false)
    class RollbackMetricsConfiguration {
        @Bean
        fun meterRegistry(): SimpleMeterRegistry = SimpleMeterRegistry()
    }

    private fun writeCountRoute(context: AssertableApplicationContext): MockServerWebExchange {
        val aggregateMetadata = MetadataSearcher.namedAggregateType.getValue(ORDER)
            .aggregateMetadata<Any, Any>()
        val handler = CountQueryHandlerFunction(
            aggregateMetadata,
            context.getBean(SnapshotQueryHandler::class.java),
            QueryDocumentKind.SNAPSHOT,
            DefaultRewriteRequestCondition,
            WebFluxRequestExceptionHandler(),
        )
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.TENANT_ID, "tenant-1")
            .body(Mono.just(Condition.all()))
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/query").build())
        handler.handle(request)
            .flatMap { response -> response.writeTo(exchange, SERVER_RESPONSE_CONTEXT) }
            .block()
        return exchange
    }

    private object CountingSnapshotQueryServiceFactory : SnapshotQueryServiceFactory {
        @Suppress("UNCHECKED_CAST")
        override fun <S : Any> create(namedAggregate: NamedAggregate): SnapshotQueryService<S> =
            CountingSnapshotQueryService(namedAggregate) as SnapshotQueryService<S>
    }

    private class CountingSnapshotQueryService(
        override val namedAggregate: NamedAggregate,
    ) : SnapshotQueryService<Any> {
        override val name: String = "counting-snapshot-query"

        override fun single(singleQuery: ISingleQuery): Mono<MaterializedSnapshot<Any>> = Mono.empty()

        override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> = Mono.empty()

        override fun list(listQuery: IListQuery): Flux<MaterializedSnapshot<Any>> = Flux.empty()

        override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> = Flux.empty()

        override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<MaterializedSnapshot<Any>>> =
            Mono.just(PagedList.empty())

        override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> =
            Mono.just(PagedList.empty())

        override fun count(condition: Condition): Mono<Long> = Mono.fromSupplier {
            countCalls.incrementAndGet()
            7L
        }

        companion object {
            val countCalls = AtomicInteger()
        }
    }

    private companion object {
        val ORDER = MaterializedNamedAggregate("example-service", "order")
        val PURPOSE = QueryPurpose("spring-facade-test")
        const val SNAPSHOT_QUERY_SERVICE_BEAN = "example.order.SnapshotQueryService"
        val SERVER_RESPONSE_CONTEXT = object : ServerResponse.Context {
            private val strategies = HandlerStrategies.withDefaults()

            override fun messageWriters() = strategies.messageWriters()

            override fun viewResolvers() = strategies.viewResolvers()
        }
    }
}
