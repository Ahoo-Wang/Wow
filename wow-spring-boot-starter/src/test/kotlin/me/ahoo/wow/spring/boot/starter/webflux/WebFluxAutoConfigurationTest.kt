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

package me.ahoo.wow.spring.boot.starter.webflux

import io.mockk.mockk
import io.mockk.spyk
import me.ahoo.cosid.machine.HostAddressSupplier
import me.ahoo.cosid.machine.LocalHostAddressSupplier
import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.event.compensation.StateEventCompensator
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.messaging.compensation.EventCompensateSupporter
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.aggregate.command.CommandFacadeRouteSpec
import me.ahoo.wow.openapi.aggregate.event.LoadEventStreamRouteSpec
import me.ahoo.wow.openapi.aggregate.event.state.ResendStateEventRouteSpec
import me.ahoo.wow.openapi.aggregate.snapshot.LoadSnapshotRouteSpec
import me.ahoo.wow.openapi.aggregate.snapshot.RegenerateSnapshotRouteSpec
import me.ahoo.wow.openapi.aggregate.state.LoadAggregateRouteSpec
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.global.GenerateGlobalIdRouteSpec
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.query.event.filter.EventStreamQueryHandler
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import me.ahoo.wow.spring.boot.starter.ENABLED_SUFFIX_KEY
import me.ahoo.wow.spring.boot.starter.command.CommandAutoConfiguration
import me.ahoo.wow.spring.boot.starter.command.CommandGatewayAutoConfiguration
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.EventSourcingAutoConfiguration
import me.ahoo.wow.spring.boot.starter.kafka.KafkaProperties
import me.ahoo.wow.spring.boot.starter.modeling.AggregateAutoConfiguration
import me.ahoo.wow.spring.boot.starter.openapi.OpenAPIAutoConfiguration
import me.ahoo.wow.spring.boot.starter.webflux.WebFluxProperties.Companion.GLOBAL_ERROR_ENABLED
import me.ahoo.wow.spring.boot.starter.webflux.route.CommandRouteModule
import me.ahoo.wow.spring.boot.starter.webflux.route.EventRouteModule
import me.ahoo.wow.spring.boot.starter.webflux.route.GlobalRouteModule
import me.ahoo.wow.spring.boot.starter.webflux.route.QueryRouteModule
import me.ahoo.wow.spring.boot.starter.webflux.route.SnapshotRouteModule
import me.ahoo.wow.spring.boot.starter.webflux.route.StateRouteModule
import me.ahoo.wow.spring.boot.starter.webflux.route.WebFluxRouteModule
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.test.SagaVerifier
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.exception.WebFluxErrorStrategy
import me.ahoo.wow.webflux.route.HttpRouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.RouteHandlerFunctionRegistrar
import me.ahoo.wow.webflux.route.command.appender.CommandRequestRemoteIpHeaderAppender
import me.ahoo.wow.webflux.route.command.appender.CommandRequestUserAgentHeaderAppender
import me.ahoo.wow.webflux.route.policy.BatchExecutionPolicy
import me.ahoo.wow.webflux.route.policy.CommandWaitPolicy
import me.ahoo.wow.webflux.route.policy.TracingPolicy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebExceptionHandler
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Duration
import java.util.stream.Stream

internal class WebFluxAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `should load context with webflux command route and exception handler`() {
        contextRunner
            .enableWow()
            .withBean(CommandWaitNotifier::class.java, { mockk() })
            .withBean(CommandGateway::class.java, { SagaVerifier.defaultCommandGateway() })
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withBean(SnapshotRepository::class.java, { NoOpSnapshotRepository })
            .withBean(EventStore::class.java, { InMemoryEventStore() })
            .withBean(DomainEventBus::class.java, { InMemoryDomainEventBus() })
            .withBean(StateEventCompensator::class.java, { mockk() })
            .withBean(EventCompensateSupporter::class.java, { mockk() })
            .withBean(SnapshotQueryHandler::class.java, { spyk<SnapshotQueryHandler>() })
            .withBean(EventStreamQueryHandler::class.java, { spyk<EventStreamQueryHandler>() })
            .withBean(HostAddressSupplier::class.java, { LocalHostAddressSupplier.INSTANCE })
            .withUserConfiguration(
                CommandAutoConfiguration::class.java,
                CommandGatewayAutoConfiguration::class.java,
                EventSourcingAutoConfiguration::class.java,
                AggregateAutoConfiguration::class.java,
                OpenAPIAutoConfiguration::class.java,
                WebFluxAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(WebExceptionHandler::class.java)
                    .hasBean("commandRouterFunction")
                    .hasSingleBean(WebFluxErrorStrategy::class.java)
                    .hasSingleBean(RequestExceptionHandler::class.java)
                    .hasSingleBean(RouteHandlerFunctionRegistrar::class.java)
                    .hasSingleBean(CommandRouteModule::class.java)
                    .hasSingleBean(StateRouteModule::class.java)
                    .hasSingleBean(QueryRouteModule::class.java)
                    .hasSingleBean(SnapshotRouteModule::class.java)
                    .hasSingleBean(EventRouteModule::class.java)
                    .hasSingleBean(GlobalRouteModule::class.java)
                    .hasSingleBean(CommandWaitPolicy::class.java)
                    .hasSingleBean(TracingPolicy::class.java)
                    .hasSingleBean(BatchExecutionPolicy::class.java)
                    .hasSingleBean(WebFluxProperties::class.java)
                val batchExecutionPolicy = context.getBean(BatchExecutionPolicy::class.java)
                batchExecutionPolicy.concurrency.assert().isOne()
                batchExecutionPolicy.prefetch.assert().isOne()

                val componentContext = OpenAPIComponentContext.default()
                val aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()
                listOf(
                    CommandFacadeRouteSpec(componentContext),
                    LoadAggregateRouteSpec(MOCK_AGGREGATE_METADATA, aggregateRouteMetadata, componentContext),
                    LoadSnapshotRouteSpec(MOCK_AGGREGATE_METADATA, aggregateRouteMetadata, componentContext),
                    LoadEventStreamRouteSpec(MOCK_AGGREGATE_METADATA, aggregateRouteMetadata, componentContext),
                    RegenerateSnapshotRouteSpec(MOCK_AGGREGATE_METADATA, aggregateRouteMetadata, componentContext),
                    ResendStateEventRouteSpec(MOCK_AGGREGATE_METADATA, aggregateRouteMetadata, componentContext),
                    GenerateGlobalIdRouteSpec(componentContext),
                ).forEach {
                    context.assertRouteFactoryRegistered(it)
                }
            }
    }

    @Test
    fun `should bind batch execution policy properties`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${WebFluxProperties.PREFIX}.batch.concurrency=4",
                "${WebFluxProperties.PREFIX}.batch.prefetch=8",
            )
            .withBean(CommandWaitNotifier::class.java, { mockk() })
            .withBean(CommandGateway::class.java, { SagaVerifier.defaultCommandGateway() })
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withBean(SnapshotRepository::class.java, { NoOpSnapshotRepository })
            .withBean(EventStore::class.java, { InMemoryEventStore() })
            .withBean(DomainEventBus::class.java, { InMemoryDomainEventBus() })
            .withBean(StateEventCompensator::class.java, { mockk() })
            .withBean(EventCompensateSupporter::class.java, { mockk() })
            .withBean(SnapshotQueryHandler::class.java, { spyk<SnapshotQueryHandler>() })
            .withBean(EventStreamQueryHandler::class.java, { spyk<EventStreamQueryHandler>() })
            .withBean(HostAddressSupplier::class.java, { LocalHostAddressSupplier.INSTANCE })
            .withUserConfiguration(
                CommandAutoConfiguration::class.java,
                CommandGatewayAutoConfiguration::class.java,
                EventSourcingAutoConfiguration::class.java,
                AggregateAutoConfiguration::class.java,
                OpenAPIAutoConfiguration::class.java,
                WebFluxAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(BatchExecutionPolicy::class.java)
                    .hasSingleBean(WebFluxProperties::class.java)
                val properties = context.getBean(WebFluxProperties::class.java)
                properties.batch.concurrency.assert().isEqualTo(4)
                properties.batch.prefetch.assert().isEqualTo(8)
                val batchExecutionPolicy = context.getBean(BatchExecutionPolicy::class.java)
                batchExecutionPolicy.concurrency.assert().isEqualTo(4)
                batchExecutionPolicy.prefetch.assert().isEqualTo(8)
            }
    }

    @Test
    fun `should let standalone route factory override module factory for same spec`() {
        val customFactory = TestRouteHandlerFunctionFactory(CommandFacadeRouteSpec::class.java)
        webFluxContextRunner()
            .withBean(RouteHandlerFunctionFactory::class.java, { customFactory })
            .run { context: AssertableApplicationContext ->
                val registrar = context.getBean(RouteHandlerFunctionRegistrar::class.java)
                val spec = CommandFacadeRouteSpec(OpenAPIComponentContext.default())
                registrar.getFactory(spec).assert().isSameAs(customFactory)
            }
    }

    @Test
    fun `should register http factories exposed by route modules`() {
        val moduleOnlyFactory = TestDualRouteHandlerFunctionFactory(
            supportedSpec = LoadAggregateRouteSpec::class.java,
            handlerKey = "module.only"
        )
        val moduleOverriddenFactory = TestDualRouteHandlerFunctionFactory(
            supportedSpec = CommandFacadeRouteSpec::class.java,
            handlerKey = "override.key"
        )
        val standaloneOverrideFactory = TestDualRouteHandlerFunctionFactory(
            supportedSpec = CommandFacadeRouteSpec::class.java,
            handlerKey = "override.key"
        )
        val routeModule = object : WebFluxRouteModule {
            override val factories: List<RouteHandlerFunctionFactory<*>> =
                listOf(moduleOnlyFactory, moduleOverriddenFactory)
        }

        val registrar = WebFluxAutoConfiguration().routeHandlerFunctionRegistrar(
            routeModules = TestObjectProvider(listOf(routeModule)),
            factories = TestObjectProvider(emptyList()),
            httpFactories = TestObjectProvider(listOf(standaloneOverrideFactory))
        )

        registrar.getHttpFactory("module.only").assert().isSameAs(moduleOnlyFactory)
        registrar.getHttpFactory("override.key").assert().isSameAs(standaloneOverrideFactory)
    }

    @Test
    fun `should use custom command wait and tracing policies`() {
        val customCommandWaitPolicy = CommandWaitPolicy(Duration.ofMillis(10))
        val customTracingPolicy = TracingPolicy()
        webFluxContextRunner()
            .withBean(CommandWaitPolicy::class.java, { customCommandWaitPolicy })
            .withBean(TracingPolicy::class.java, { customTracingPolicy })
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(CommandWaitPolicy::class.java)
                    .hasSingleBean(TracingPolicy::class.java)
                context.getBean(CommandWaitPolicy::class.java).assert().isSameAs(customCommandWaitPolicy)
                context.getBean(TracingPolicy::class.java).assert().isSameAs(customTracingPolicy)
            }
    }

    @Test
    fun `should use custom webflux error strategy`() {
        val customErrorStrategy = TestWebFluxErrorStrategy()
        contextRunner
            .enableWow()
            .withBean(WebFluxErrorStrategy::class.java, { customErrorStrategy })
            .withBean(CommandWaitNotifier::class.java, { mockk() })
            .withBean(CommandGateway::class.java, { SagaVerifier.defaultCommandGateway() })
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withBean(SnapshotRepository::class.java, { NoOpSnapshotRepository })
            .withBean(EventStore::class.java, { InMemoryEventStore() })
            .withBean(DomainEventBus::class.java, { InMemoryDomainEventBus() })
            .withBean(StateEventCompensator::class.java, { mockk() })
            .withBean(EventCompensateSupporter::class.java, { mockk() })
            .withBean(SnapshotQueryHandler::class.java, { spyk<SnapshotQueryHandler>() })
            .withBean(EventStreamQueryHandler::class.java, { spyk<EventStreamQueryHandler>() })
            .withBean(HostAddressSupplier::class.java, { LocalHostAddressSupplier.INSTANCE })
            .withUserConfiguration(
                CommandAutoConfiguration::class.java,
                CommandGatewayAutoConfiguration::class.java,
                EventSourcingAutoConfiguration::class.java,
                AggregateAutoConfiguration::class.java,
                OpenAPIAutoConfiguration::class.java,
                WebFluxAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(WebFluxErrorStrategy::class.java)
                    .hasSingleBean(RequestExceptionHandler::class.java)
                    .hasSingleBean(WebExceptionHandler::class.java)
                context.getBean(WebFluxErrorStrategy::class.java).assert().isSameAs(customErrorStrategy)
                context.getBean(RequestExceptionHandler::class.java)
                    .handle(MockServerRequest.builder().build(), IllegalArgumentException("bad"))
                    .test()
                    .consumeNextWith {
                        it.statusCode().assert().isEqualTo(HttpStatus.I_AM_A_TEAPOT)
                    }
                    .verifyComplete()

                val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build())
                context.getBean(WebExceptionHandler::class.java)
                    .handle(exchange, IllegalArgumentException("bad"))
                    .test()
                    .verifyComplete()
                exchange.response.statusCode.assert().isEqualTo(HttpStatus.I_AM_A_TEAPOT)
            }
    }

    @Test
    fun `should not load agent appender beans when disabled`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${WebFluxProperties.COMMAND_REQUEST_APPENDER_PREFIX}.agent$ENABLED_SUFFIX_KEY=false",
                "${WebFluxProperties.COMMAND_REQUEST_APPENDER_PREFIX}.ip$ENABLED_SUFFIX_KEY=false"
            )
            .withBean(CommandWaitNotifier::class.java, { mockk() })
            .withBean(CommandGateway::class.java, { SagaVerifier.defaultCommandGateway() })
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withBean(SnapshotRepository::class.java, { NoOpSnapshotRepository })
            .withBean(EventStore::class.java, { InMemoryEventStore() })
            .withBean(DomainEventBus::class.java, { InMemoryDomainEventBus() })
            .withBean(StateEventCompensator::class.java, { mockk() })
            .withBean(EventCompensateSupporter::class.java, { mockk() })
            .withBean(SnapshotQueryHandler::class.java, { spyk<SnapshotQueryHandler>() })
            .withBean(EventStreamQueryHandler::class.java, { spyk<EventStreamQueryHandler>() })
            .withBean(HostAddressSupplier::class.java, { LocalHostAddressSupplier.INSTANCE })
            .withUserConfiguration(
                CommandAutoConfiguration::class.java,
                CommandGatewayAutoConfiguration::class.java,
                EventSourcingAutoConfiguration::class.java,
                AggregateAutoConfiguration::class.java,
                OpenAPIAutoConfiguration::class.java,
                WebFluxAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .doesNotHaveBean(CommandRequestUserAgentHeaderAppender::class.java)
                    .doesNotHaveBean(CommandRequestRemoteIpHeaderAppender::class.java)
            }
    }

    @Test
    fun `should not load global exception handler when global error disabled`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${GLOBAL_ERROR_ENABLED}=false"
            )
            .withBean(CommandWaitNotifier::class.java, { mockk() })
            .withBean(CommandGateway::class.java, { SagaVerifier.defaultCommandGateway() })
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withBean(SnapshotRepository::class.java, { NoOpSnapshotRepository })
            .withBean(EventStore::class.java, { InMemoryEventStore() })
            .withBean(DomainEventBus::class.java, { InMemoryDomainEventBus() })
            .withBean(StateEventCompensator::class.java, { mockk() })
            .withBean(EventCompensateSupporter::class.java, { mockk() })
            .withBean(SnapshotQueryHandler::class.java, { spyk<SnapshotQueryHandler>() })
            .withBean(EventStreamQueryHandler::class.java, { spyk<EventStreamQueryHandler>() })
            .withBean(KafkaProperties::class.java, {
                KafkaProperties(bootstrapServers = listOf("localhost:9092"))
            })
            .withBean(HostAddressSupplier::class.java, { LocalHostAddressSupplier.INSTANCE })
            .withUserConfiguration(
                CommandAutoConfiguration::class.java,
                CommandGatewayAutoConfiguration::class.java,
                EventSourcingAutoConfiguration::class.java,
                AggregateAutoConfiguration::class.java,
                OpenAPIAutoConfiguration::class.java,
                WebFluxAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .doesNotHaveBean(WebExceptionHandler::class.java)
                    .hasBean("commandRouterFunction")
                    .hasSingleBean(RequestExceptionHandler::class.java)
            }
    }

    private class TestWebFluxErrorStrategy : WebFluxErrorStrategy {
        override fun toServerResponse(request: ServerRequest, throwable: Throwable): Mono<ServerResponse> {
            return ServerResponse.status(HttpStatus.I_AM_A_TEAPOT).build()
        }

        override fun writeToExchange(exchange: ServerWebExchange, throwable: Throwable): Mono<Void> {
            exchange.response.statusCode = HttpStatus.I_AM_A_TEAPOT
            return Mono.empty()
        }
    }

    private fun webFluxContextRunner(): ApplicationContextRunner {
        return contextRunner
            .enableWow()
            .withBean(CommandWaitNotifier::class.java, { mockk() })
            .withBean(CommandGateway::class.java, { SagaVerifier.defaultCommandGateway() })
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withBean(SnapshotRepository::class.java, { NoOpSnapshotRepository })
            .withBean(EventStore::class.java, { InMemoryEventStore() })
            .withBean(DomainEventBus::class.java, { InMemoryDomainEventBus() })
            .withBean(StateEventCompensator::class.java, { mockk() })
            .withBean(EventCompensateSupporter::class.java, { mockk() })
            .withBean(SnapshotQueryHandler::class.java, { spyk<SnapshotQueryHandler>() })
            .withBean(EventStreamQueryHandler::class.java, { spyk<EventStreamQueryHandler>() })
            .withBean(HostAddressSupplier::class.java, { LocalHostAddressSupplier.INSTANCE })
            .withUserConfiguration(
                CommandAutoConfiguration::class.java,
                CommandGatewayAutoConfiguration::class.java,
                EventSourcingAutoConfiguration::class.java,
                AggregateAutoConfiguration::class.java,
                OpenAPIAutoConfiguration::class.java,
                WebFluxAutoConfiguration::class.java,
            )
    }

    private fun AssertableApplicationContext.assertRouteFactoryRegistered(spec: RouteSpec) {
        val registrar = getBean(RouteHandlerFunctionRegistrar::class.java)
        registrar.getFactory(spec).assert().isNotNull()
        registrar.getHttpFactory(spec::class.java.name).assert().isNotNull()
    }

    private class TestRouteHandlerFunctionFactory<R : RouteSpec>(
        override val supportedSpec: Class<R>
    ) : RouteHandlerFunctionFactory<R> {
        override fun create(spec: R): HandlerFunction<ServerResponse> {
            return HandlerFunction {
                ServerResponse.ok().build()
            }
        }
    }

    private class TestDualRouteHandlerFunctionFactory<R : RouteSpec>(
        override val supportedSpec: Class<R>,
        override val handlerKey: String
    ) : RouteHandlerFunctionFactory<R>, HttpRouteHandlerFunctionFactory {
        override fun create(spec: R): HandlerFunction<ServerResponse> {
            return HandlerFunction {
                ServerResponse.ok().build()
            }
        }

        override fun create(
            contract: HttpRouteContract,
            metadata: HttpRouteHandlerMetadata
        ): HandlerFunction<ServerResponse> {
            return HandlerFunction {
                ServerResponse.ok().build()
            }
        }
    }

    private class TestObjectProvider<T : Any>(
        private val values: List<T>
    ) : ObjectProvider<T> {
        override fun stream(): Stream<T> {
            return values.stream()
        }
    }
}
