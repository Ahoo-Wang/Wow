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
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.messaging.compensation.EventCompensateSupporter
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.query.event.filter.EventStreamQueryHandler
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import me.ahoo.wow.spring.boot.starter.ENABLED_SUFFIX_KEY
import me.ahoo.wow.spring.boot.starter.bi.BiScriptObjectMapStrategy
import me.ahoo.wow.spring.boot.starter.bi.BiScriptProperties
import me.ahoo.wow.spring.boot.starter.bi.BiScriptUnsupportedTypeStrategy
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
import me.ahoo.wow.test.SagaVerifier
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.exception.WebFluxErrorStrategy
import me.ahoo.wow.webflux.route.HttpRouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.RouteHandlerFunctionRegistrar
import me.ahoo.wow.webflux.route.command.appender.CommandRequestRemoteIpHeaderAppender
import me.ahoo.wow.webflux.route.command.appender.CommandRequestUserAgentHeaderAppender
import me.ahoo.wow.webflux.route.global.BiScriptRouteObjectMapStrategy
import me.ahoo.wow.webflux.route.global.BiScriptRouteOptions
import me.ahoo.wow.webflux.route.global.BiScriptRouteUnsupportedTypeStrategy
import me.ahoo.wow.webflux.route.global.GenerateBIScriptHandlerFunctionFactory
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
import org.springframework.web.reactive.function.server.HandlerStrategies
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
            .withBean(SnapshotStore::class.java, { NoOpSnapshotStore })
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
                    .hasSingleBean(BiScriptProperties::class.java)
                val batchExecutionPolicy = context.getBean(BatchExecutionPolicy::class.java)
                batchExecutionPolicy.concurrency.assert().isOne()
                batchExecutionPolicy.prefetch.assert().isOne()

                listOf(
                    BuiltInHttpRouteHandlerKeys.Global.COMMAND_FACADE,
                    BuiltInHttpRouteHandlerKeys.State.LOAD_AGGREGATE,
                    BuiltInHttpRouteHandlerKeys.Snapshot.LOAD,
                    BuiltInHttpRouteHandlerKeys.Event.LOAD,
                    BuiltInHttpRouteHandlerKeys.Snapshot.REGENERATE,
                    BuiltInHttpRouteHandlerKeys.Event.RESEND_STATE,
                    BuiltInHttpRouteHandlerKeys.Global.GLOBAL_ID,
                    BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT,
                ).forEach { handlerKey ->
                    context.assertRouteFactoryRegistered(handlerKey)
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
            .withBean(SnapshotStore::class.java, { NoOpSnapshotStore })
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
    fun `should bind BI properties and let explicit Kafka overrides take precedence`() {
        webFluxContextRunner()
            .withPropertyValues(
                "${BiScriptProperties.PREFIX}.database=analytics",
                "${BiScriptProperties.PREFIX}.consumer-database=analytics_consumer",
                "${BiScriptProperties.PREFIX}.cluster=production",
                "${BiScriptProperties.PREFIX}.installation=primary",
                "${BiScriptProperties.PREFIX}.shard=02",
                "${BiScriptProperties.PREFIX}.replica=replica-2",
                "${BiScriptProperties.PREFIX}.timezone=UTC",
                "${BiScriptProperties.PREFIX}.kafka-bootstrap-servers=bi-kafka:19092",
                "${BiScriptProperties.PREFIX}.topic-prefix=bi.",
                "${BiScriptProperties.PREFIX}.max-expansion-depth=7",
                "${BiScriptProperties.PREFIX}.unsupported-type-strategy=STRING_WITH_DIAGNOSTIC",
                "${BiScriptProperties.PREFIX}.object-map-strategy=FAIL",
            )
            .withBean(KafkaProperties::class.java, {
                KafkaProperties(
                    bootstrapServers = listOf("kafka:9092"),
                    topicPrefix = "kafka.",
                )
            })
            .run { context: AssertableApplicationContext ->
                context.biScriptRouteOptions().assert().isEqualTo(
                    BiScriptRouteOptions(
                        database = "analytics",
                        consumerDatabase = "analytics_consumer",
                        cluster = "production",
                        installation = "primary",
                        shard = "02",
                        replica = "replica-2",
                        timezone = "UTC",
                        kafkaBootstrapServers = "bi-kafka:19092",
                        topicPrefix = "bi.",
                        maxExpansionDepth = 7,
                        unsupportedTypeStrategy = BiScriptRouteUnsupportedTypeStrategy.STRING_WITH_DIAGNOSTIC,
                        objectMapStrategy = BiScriptRouteObjectMapStrategy.FAIL,
                    )
                )
            }
    }

    @Test
    fun `should inherit Kafka properties when BI Kafka overrides are absent`() {
        webFluxContextRunner()
            .withBean(KafkaProperties::class.java, {
                KafkaProperties(
                    bootstrapServers = listOf("kafka-a:9092", "kafka-b:9092"),
                    topicPrefix = "kafka.",
                )
            })
            .run { context: AssertableApplicationContext ->
                val options = context.biScriptRouteOptions()
                options.kafkaBootstrapServers.assert().isEqualTo("kafka-a:9092,kafka-b:9092")
                options.topicPrefix.assert().isEqualTo("kafka.")
            }
    }

    @Test
    fun `should keep explicit BI defaults ahead of conflicting Kafka properties`() {
        webFluxContextRunner()
            .withPropertyValues(
                "${BiScriptProperties.PREFIX}.kafka-bootstrap-servers=localhost:9093",
                "${BiScriptProperties.PREFIX}.topic-prefix=wow.",
            )
            .withBean(KafkaProperties::class.java, {
                KafkaProperties(
                    bootstrapServers = listOf("kafka:9092"),
                    topicPrefix = "kafka.",
                )
            })
            .run { context: AssertableApplicationContext ->
                val properties = context.getBean(BiScriptProperties::class.java)
                properties.kafkaBootstrapServers.assert().isEqualTo("localhost:9093")
                properties.topicPrefix.assert().isEqualTo("wow.")

                val options = context.biScriptRouteOptions()
                options.kafkaBootstrapServers.assert().isEqualTo("localhost:9093")
                options.topicPrefix.assert().isEqualTo("wow.")
            }
    }

    @Test
    fun `should delegate unconfigured BI options to WebFlux domain defaults`() {
        webFluxContextRunner()
            .run { context: AssertableApplicationContext ->
                context.getBean(BiScriptProperties::class.java).assert().isEqualTo(BiScriptProperties())
                val factory = context.biScriptRouteFactory()
                factory.privateBiScriptRouteOptions().assert().isEqualTo(BiScriptRouteOptions())

                val script = factory.generateBiScript()
                script.assert().contains("-- global --")
                script.assert().isEqualTo(GenerateBIScriptHandlerFunctionFactory().generateBiScript())
            }
    }

    @Test
    fun `should fail startup for an explicit blank BI override`() {
        webFluxContextRunner()
            .withPropertyValues("${BiScriptProperties.PREFIX}.kafka-bootstrap-servers= ")
            .withBean(KafkaProperties::class.java, {
                KafkaProperties(bootstrapServers = listOf("fallback-kafka:9092"))
            })
            .run { context: AssertableApplicationContext ->
                val failure = context.startupFailure
                failure.assert().isNotNull()
                failure!!.causeChainMessages().assert().contains("kafkaBootstrapServers must not be blank")
            }
    }

    @Test
    fun `should validate every non-null BI string override`() {
        val propertyFactories = listOf<Pair<String, (String) -> BiScriptProperties>>(
            "database" to { BiScriptProperties(database = it) },
            "consumerDatabase" to { BiScriptProperties(consumerDatabase = it) },
            "cluster" to { BiScriptProperties(cluster = it) },
            "installation" to { BiScriptProperties(installation = it) },
            "shard" to { BiScriptProperties(shard = it) },
            "replica" to { BiScriptProperties(replica = it) },
            "timezone" to { BiScriptProperties(timezone = it) },
            "kafkaBootstrapServers" to { BiScriptProperties(kafkaBootstrapServers = it) },
            "topicPrefix" to { BiScriptProperties(topicPrefix = it) },
        )

        propertyFactories.forEach { (name, properties) ->
            val blankError = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                properties(" ")
            }
            blankError.message.assert().isEqualTo("$name must not be blank")

            val controlCharacterError = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                properties("invalid\u0000value")
            }
            controlCharacterError.message.assert().isEqualTo("$name must not contain control characters")
        }
    }

    @Test
    fun `should fail startup for an invalid BI expansion depth`() {
        webFluxContextRunner()
            .withPropertyValues("${BiScriptProperties.PREFIX}.max-expansion-depth=0")
            .run { context: AssertableApplicationContext ->
                val failure = context.startupFailure
                failure.assert().isNotNull()
                failure!!.causeChainMessages().assert().contains("maxExpansionDepth must be greater than or equal to 1")
            }
    }

    @Test
    fun `should bind every BI strategy enum value`() {
        BiScriptUnsupportedTypeStrategy.entries.forEach { strategy ->
            webFluxContextRunner()
                .withPropertyValues("${BiScriptProperties.PREFIX}.unsupported-type-strategy=${strategy.name}")
                .run { context: AssertableApplicationContext ->
                    context.getBean(BiScriptProperties::class.java)
                        .unsupportedTypeStrategy.assert().isEqualTo(strategy)
                    context.biScriptRouteOptions().unsupportedTypeStrategy.assert().isEqualTo(
                        when (strategy) {
                            BiScriptUnsupportedTypeStrategy.FAIL -> BiScriptRouteUnsupportedTypeStrategy.FAIL
                            BiScriptUnsupportedTypeStrategy.STRING_WITH_DIAGNOSTIC ->
                                BiScriptRouteUnsupportedTypeStrategy.STRING_WITH_DIAGNOSTIC
                        }
                    )
                }
        }

        BiScriptObjectMapStrategy.entries.forEach { strategy ->
            webFluxContextRunner()
                .withPropertyValues("${BiScriptProperties.PREFIX}.object-map-strategy=${strategy.name}")
                .run { context: AssertableApplicationContext ->
                    context.getBean(BiScriptProperties::class.java)
                        .objectMapStrategy.assert().isEqualTo(strategy)
                    context.biScriptRouteOptions().objectMapStrategy.assert().isEqualTo(
                        when (strategy) {
                            BiScriptObjectMapStrategy.STRING_VALUE_WITH_DIAGNOSTIC ->
                                BiScriptRouteObjectMapStrategy.STRING_VALUE_WITH_DIAGNOSTIC
                            BiScriptObjectMapStrategy.FAIL -> BiScriptRouteObjectMapStrategy.FAIL
                        }
                    )
                }
        }
    }

    @Test
    fun `should preserve legacy GlobalRouteModule constructor and BI route factory`() {
        val publicConstructors = GlobalRouteModule::class.java.constructors
            .map { it.parameterTypes.toList() }
        publicConstructors.assert().contains(
            listOf(KafkaProperties::class.java),
            listOf(KafkaProperties::class.java, BiScriptProperties::class.java),
        )

        val routeModule = GlobalRouteModule(
            KafkaProperties(
                bootstrapServers = listOf("legacy-kafka:9092"),
                topicPrefix = "legacy.",
            )
        )
        val factory = routeModule.httpFactories.single {
            it.handlerKey == BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT
        }
        factory.assert().isInstanceOf(GenerateBIScriptHandlerFunctionFactory::class.java)
        factory.privateBiScriptRouteOptions().assert().isNull()
    }

    @Test
    fun `should let standalone route factory override module factory for same handler key`() {
        val customFactory = TestHttpRouteHandlerFunctionFactory(BuiltInHttpRouteHandlerKeys.Global.COMMAND_FACADE)
        webFluxContextRunner()
            .withBean(HttpRouteHandlerFunctionFactory::class.java, { customFactory })
            .run { context: AssertableApplicationContext ->
                val registrar = context.getBean(RouteHandlerFunctionRegistrar::class.java)
                registrar.getHttpFactory(
                    BuiltInHttpRouteHandlerKeys.Global.COMMAND_FACADE
                ).assert().isSameAs(customFactory)
            }
    }

    @Test
    fun `should register http factories exposed by route modules`() {
        val moduleOnlyFactory = TestHttpRouteHandlerFunctionFactory("module.only")
        val moduleOverriddenFactory = TestHttpRouteHandlerFunctionFactory("override.key")
        val standaloneOverrideFactory = TestHttpRouteHandlerFunctionFactory("override.key")
        val routeModule = object : WebFluxRouteModule {
            override val httpFactories: List<HttpRouteHandlerFunctionFactory> =
                listOf(moduleOnlyFactory, moduleOverriddenFactory)
        }

        val registrar = WebFluxAutoConfiguration().routeHandlerFunctionRegistrar(
            routeModules = TestObjectProvider(listOf(routeModule)),
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
            .withBean(SnapshotStore::class.java, { NoOpSnapshotStore })
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
            .withBean(SnapshotStore::class.java, { NoOpSnapshotStore })
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
            .withBean(SnapshotStore::class.java, { NoOpSnapshotStore })
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
            .withBean(SnapshotStore::class.java, { NoOpSnapshotStore })
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

    private fun AssertableApplicationContext.assertRouteFactoryRegistered(handlerKey: String) {
        val registrar = getBean(RouteHandlerFunctionRegistrar::class.java)
        registrar.getHttpFactory(handlerKey).assert().isNotNull()
    }

    private fun AssertableApplicationContext.biScriptRouteOptions(): BiScriptRouteOptions {
        return biScriptRouteFactory().privateBiScriptRouteOptions()!!
    }

    private fun AssertableApplicationContext.biScriptRouteFactory(): HttpRouteHandlerFunctionFactory {
        val factory = getBean(RouteHandlerFunctionRegistrar::class.java)
            .getHttpFactory(BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT)!!
        factory.assert().isInstanceOf(GenerateBIScriptHandlerFunctionFactory::class.java)
        return factory
    }

    private fun HttpRouteHandlerFunctionFactory.privateBiScriptRouteOptions(): BiScriptRouteOptions? {
        val field = GenerateBIScriptHandlerFunctionFactory::class.java.getDeclaredField("options")
        field.isAccessible = true
        return field.get(this) as BiScriptRouteOptions?
    }

    private fun HttpRouteHandlerFunctionFactory.generateBiScript(): String {
        val contract = HttpRouteContract(
            routeId = "bi-script",
            method = "GET",
            path = "/bi-script",
            handlerKey = BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT,
        )
        val response = create(contract)
            .handle(MockServerRequest.builder().build())
            .block()!!
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/bi-script").build())
        response.writeTo(exchange, SERVER_RESPONSE_CONTEXT)
            .test()
            .verifyComplete()
        return exchange.response.bodyAsString.block()!!
    }

    private fun Throwable.causeChainMessages(): String =
        generateSequence(this) { it.cause }
            .mapNotNull { it.message }
            .joinToString("\n")

    private class TestHttpRouteHandlerFunctionFactory(
        override val handlerKey: String
    ) : HttpRouteHandlerFunctionFactory {
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

    private companion object {
        private val SERVER_RESPONSE_CONTEXT = object : ServerResponse.Context {
            private val strategies = HandlerStrategies.withDefaults()
            override fun messageWriters() = strategies.messageWriters()
            override fun viewResolvers() = strategies.viewResolvers()
        }
    }
}
