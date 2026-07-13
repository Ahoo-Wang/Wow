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
import me.ahoo.wow.bi.BiDeploymentInspection
import me.ahoo.wow.bi.BiDeploymentInspector
import me.ahoo.wow.bi.BiScriptGenerator
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.ClickHouseTopology
import me.ahoo.wow.bi.NoOpBiDeploymentInspector
import me.ahoo.wow.bi.UnsupportedTypeStrategy
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.configuration.MetadataSearcher
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
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.BuiltInHttpRoutePaths
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.contract.bi.BiScriptRequest
import me.ahoo.wow.openapi.contract.bi.BiScriptTopologyMode
import me.ahoo.wow.openapi.contract.bi.BiScriptTopologyRequest
import me.ahoo.wow.query.event.filter.EventStreamQueryHandler
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import me.ahoo.wow.spring.boot.starter.ENABLED_SUFFIX_KEY
import me.ahoo.wow.spring.boot.starter.bi.BiScriptProperties
import me.ahoo.wow.spring.boot.starter.command.CommandAutoConfiguration
import me.ahoo.wow.spring.boot.starter.command.CommandGatewayAutoConfiguration
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.EventSourcingAutoConfiguration
import me.ahoo.wow.spring.boot.starter.kafka.KafkaProperties
import me.ahoo.wow.spring.boot.starter.modeling.AggregateAutoConfiguration
import me.ahoo.wow.spring.boot.starter.openapi.OpenAPIAutoConfiguration
import me.ahoo.wow.spring.boot.starter.webflux.WebFluxProperties.Companion.GLOBAL_ERROR_ENABLED
import me.ahoo.wow.spring.boot.starter.webflux.bi.BiDeploymentInspectorAutoConfiguration
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
import me.ahoo.wow.webflux.route.global.GenerateBIScriptHandlerFunctionFactory
import me.ahoo.wow.webflux.route.policy.BatchExecutionPolicy
import me.ahoo.wow.webflux.route.policy.CommandWaitPolicy
import me.ahoo.wow.webflux.route.policy.TracingPolicy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.test.web.reactive.server.HttpHandlerConnector
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebExceptionHandler
import org.springframework.web.server.adapter.WebHttpHandlerBuilder
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Duration
import java.util.stream.Stream

@Suppress("LargeClass")
internal class WebFluxAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner().withPropertyValues(
        "${BiScriptProperties.PREFIX}.enabled=true",
        "${BiScriptProperties.PREFIX}.consumer-group-namespace=test",
    )

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
                BiDeploymentInspectorAutoConfiguration::class.java,
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
                BiDeploymentInspectorAutoConfiguration::class.java,
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
    fun `should generate SQL from every explicit BI property and bind the domain strategy directly`() {
        webFluxContextRunner()
            .withPropertyValues(
                "${BiScriptProperties.PREFIX}.database=analytics",
                "${BiScriptProperties.PREFIX}.consumer-database=analytics_consumer",
                "${BiScriptProperties.PREFIX}.topology.mode=CLUSTER",
                "${BiScriptProperties.PREFIX}.topology.cluster.name=production",
                "${BiScriptProperties.PREFIX}.topology.cluster.installation=primary",
                "${BiScriptProperties.PREFIX}.timezone=UTC",
                "${BiScriptProperties.PREFIX}.kafka-bootstrap-servers=bi-kafka:19092",
                "${BiScriptProperties.PREFIX}.topic-prefix=bi.",
                "${BiScriptProperties.PREFIX}.max-expansion-depth=7",
                "${BiScriptProperties.PREFIX}.unsupported-type-strategy=RAW_JSON",
            )
            .withBean(KafkaProperties::class.java, {
                KafkaProperties(
                    bootstrapServers = listOf("kafka:9092"),
                    topicPrefix = "kafka.",
                )
            })
            .run { context: AssertableApplicationContext ->
                context.assert().hasNotFailed()
                context.getBean(BiScriptProperties::class.java)
                    .unsupportedTypeStrategy.assert().isEqualTo(UnsupportedTypeStrategy.RAW_JSON)

                val expectedOptions = BiScriptOptions(
                    database = "analytics",
                    consumerDatabase = "analytics_consumer",
                    topology = ClickHouseTopology.Cluster(
                        name = "production",
                        installation = "primary",
                    ),
                    timezone = "UTC",
                    kafkaBootstrapServers = "bi-kafka:19092",
                    topicPrefix = "bi.",
                    consumerGroupNamespace = "test",
                    maxExpansionDepth = 7,
                    unsupportedTypeStrategy = UnsupportedTypeStrategy.RAW_JSON,
                )
                val script = context.generateBiScript()
                script.assert().isEqualTo(
                    BiScriptGenerator(expectedOptions).generate(MetadataSearcher.localAggregates).script
                )
                script.assert().contains(
                    "CREATE DATABASE IF NOT EXISTS \"analytics\" ON CLUSTER 'production'",
                    "CREATE DATABASE IF NOT EXISTS \"analytics_consumer\" ON CLUSTER 'production'",
                    "/clickhouse/primary/production/tables/{shard}/{database}/{table}",
                    "'{replica}'",
                    "DateTime64(3, 'UTC')",
                    "ENGINE = Kafka('bi-kafka:19092'",
                    "'bi.example.order.command'",
                )
                script.assert().doesNotContain("kafka:9092", "'kafka.example.order.command'")
            }
    }

    @Test
    fun `should bind standalone BI topology`() {
        webFluxContextRunner()
            .withPropertyValues("${BiScriptProperties.PREFIX}.topology.mode=STANDALONE")
            .run { context ->
                context.assert().hasNotFailed()
                context.generateBiScript().assert()
                    .doesNotContain("ON CLUSTER", "Replicated", "Distributed", "_local")
            }
    }

    @Test
    fun `should reject cluster fields in standalone mode`() {
        webFluxContextRunner()
            .withPropertyValues(
                "${BiScriptProperties.PREFIX}.topology.mode=STANDALONE",
                "${BiScriptProperties.PREFIX}.topology.cluster.name=unused",
            )
            .run { context ->
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.causeChainMessages().assert()
                    .contains("topology.cluster must not be configured in STANDALONE mode")
            }
    }

    @Test
    fun `should ignore removed flat topology keys and keep default cluster topology`() {
        webFluxContextRunner()
            .withPropertyValues(
                "${BiScriptProperties.PREFIX}.cluster=removed-cluster",
                "${BiScriptProperties.PREFIX}.installation=removed-installation",
                "${BiScriptProperties.PREFIX}.shard=removed-shard",
                "${BiScriptProperties.PREFIX}.replica=removed-replica",
            )
            .run { context ->
                context.assert().hasNotFailed()
                context.generateBiScript().assert().isEqualTo(
                    BiScriptGenerator(
                        BiScriptOptions(
                            topology = ClickHouseTopology.Cluster(),
                            consumerGroupNamespace = "test",
                        )
                    ).generate(MetadataSearcher.localAggregates).script
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
                val script = context.generateBiScript()
                script.assert().contains(
                    "ENGINE = Kafka('kafka-a:9092,kafka-b:9092'",
                    "'kafka.example.order.command'",
                )
                script.assert().doesNotContain("localhost:9093", "'wow.example.order.command'")
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
                val script = context.generateBiScript()
                script.assert().contains(
                    "ENGINE = Kafka('localhost:9093'",
                    "'wow.example.order.command'",
                )
                script.assert().doesNotContain("kafka:9092", "'kafka.example.order.command'")
            }
    }

    @Test
    fun `should apply BI request options after application and Kafka options`() {
        webFluxContextRunner()
            .withPropertyValues(
                "${BiScriptProperties.PREFIX}.database=config_db",
                "${BiScriptProperties.PREFIX}.topology.mode=CLUSTER",
                "${BiScriptProperties.PREFIX}.topology.cluster.name=config-cluster",
            )
            .withBean(KafkaProperties::class.java, {
                KafkaProperties(bootstrapServers = listOf("config-kafka:9092"), topicPrefix = "config.")
            })
            .run { context ->
                context.generateBiScript(
                    BiScriptRequest(
                        database = "request_db",
                        topology = BiScriptTopologyRequest(mode = BiScriptTopologyMode.STANDALONE),
                        kafkaBootstrapServers = "request-kafka:9092",
                        topicPrefix = "request.",
                    )
                ).assert()
                    .contains(
                        "CREATE DATABASE IF NOT EXISTS \"request_db\"",
                        "ENGINE = Kafka('request-kafka:9092'",
                        "'request.example.order.command'",
                    )
                    .doesNotContain("config_db", "config-cluster", "config-kafka:9092", "ON CLUSTER")
            }
    }

    @Test
    fun `should not expose the removed BI GET route`() {
        webFluxContextRunner().run { context ->
            context.biScriptClient()
                .get()
                .uri(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
                .accept(MediaType.parseMediaType(Https.MediaType.APPLICATION_SQL))
                .exchange()
                .expectStatus().isNotFound
        }
    }

    @Test
    fun `should reject a missing BI request body`() {
        webFluxContextRunner().run { context ->
            context.biScriptClient().post()
                .uri(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest
        }
    }

    @Test
    fun `should reject malformed BI request JSON`() {
        webFluxContextRunner().run { context ->
            context.biScriptClient().post()
                .uri(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{")
                .exchange()
                .expectStatus().isBadRequest
        }
    }

    @Test
    fun `should reject domain-invalid BI request values`() {
        webFluxContextRunner().run { context ->
            context.biScriptClient().post()
                .uri(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"maxExpansionDepth":0}""")
                .exchange()
                .expectStatus().isBadRequest
        }
    }

    @Test
    fun `should reject BI reset when the default inspector is no-op`() {
        webFluxContextRunner().run { context ->
            context.biScriptClient().post()
                .uri(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"operation":"RESET","replayFromEarliestConfirmed":true}""")
                .exchange()
                .expectStatus().isBadRequest
                .expectBody(String::class.java)
                .value { body -> body.assert().contains("RESET requires an available BI deployment inspection") }
        }
    }

    @Test
    fun `should accept a BI request database at the domain maximum length`() {
        val database = "d".repeat(BiScriptOptions.MAX_DATABASE_LENGTH)

        webFluxContextRunner().run { context ->
            context.biScriptClient().post()
                .uri(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"database":"$database"}""")
                .exchange()
                .expectStatus().isOk
                .expectBody(String::class.java)
                .value { script ->
                    script.assert().contains("CREATE DATABASE IF NOT EXISTS \"$database\"")
                }
        }
    }

    @Test
    fun `should reject a BI request database above the domain maximum length`() {
        val database = "d".repeat(BiScriptOptions.MAX_DATABASE_LENGTH + 1)

        webFluxContextRunner().run { context ->
            context.biScriptClient().post()
                .uri(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"database":"$database"}""")
                .exchange()
                .expectStatus().isBadRequest
        }
    }

    @Test
    fun `should reject BI request expansion depth above the configured ceiling`() {
        webFluxContextRunner()
            .withPropertyValues("${BiScriptProperties.PREFIX}.max-expansion-depth=3")
            .run { context ->
                context.biScriptClient().post()
                    .uri(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""{"maxExpansionDepth":4}""")
                    .exchange()
                    .expectStatus().isBadRequest
            }
    }

    @Test
    fun `should reject cluster details in a standalone BI request`() {
        webFluxContextRunner().run { context ->
            context.biScriptClient().post()
                .uri(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"topology":{"mode":"STANDALONE","cluster":{}}}""")
                .exchange()
                .expectStatus().isBadRequest
        }
    }

    @Test
    fun `should reject a BI topology without mode`() {
        webFluxContextRunner().run { context ->
            context.biScriptClient().post()
                .uri(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"topology":{}}""")
                .exchange()
                .expectStatus().isBadRequest
        }
    }

    @Test
    fun `should reject unsupported BI request media type`() {
        webFluxContextRunner().run { context ->
            context.biScriptClient().post()
                .uri(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
                .contentType(MediaType.TEXT_PLAIN)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .expectHeader().valueEquals("Wow-Error-Code", "UnsupportedMediaType")
        }
    }

    @Test
    fun `should use configured BI defaults and register one BI route factory`() {
        webFluxContextRunner()
            .run { context: AssertableApplicationContext ->
                context.getBean(BiScriptProperties::class.java).assert().isEqualTo(
                    BiScriptProperties(enabled = true, consumerGroupNamespace = "test")
                )
                context.assert().hasSingleBean(GlobalRouteModule::class.java)
                context.getBean(BiDeploymentInspector::class.java).assert()
                    .isSameAs(NoOpBiDeploymentInspector)
                context.getBeanNamesForType(GlobalRouteModule::class.java)
                    .assert()
                    .containsExactly("globalRouteModule")
                val moduleFactory = context.getBean(GlobalRouteModule::class.java).httpFactories.single {
                    it.handlerKey == BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT
                }
                moduleFactory.assert().isSameAs(context.biScriptRouteFactory())

                val script = context.generateBiScript()
                script.assert().contains("-- global --")
                script.assert().isEqualTo(
                    BiScriptGenerator(BiScriptOptions(consumerGroupNamespace = "test"))
                        .generate(MetadataSearcher.localAggregates).script
                )
            }
    }

    @Test
    fun `should back off the default BI deployment inspector`() {
        val customInspector = BiDeploymentInspector {
            Mono.just(BiDeploymentInspection.Available(me.ahoo.wow.bi.ObservedBiDeployment(emptyList())))
        }
        webFluxContextRunner()
            .withBean(BiDeploymentInspector::class.java, { customInspector })
            .run { context ->
                context.getBean(BiDeploymentInspector::class.java).assert().isSameAs(customInspector)
            }
    }

    @Test
    fun `should expose BI route by default without requiring generation configuration at startup`() {
        webFluxContextRunner(ApplicationContextRunner())
            .run { context: AssertableApplicationContext ->
                context.assert().hasNotFailed()
                context.getBean(BiScriptProperties::class.java).enabled.assert().isTrue()
                context.getBean(RouteHandlerFunctionRegistrar::class.java)
                    .getHttpFactory(BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT)
                    .assert()
                    .isNotNull()
            }
    }

    @Test
    fun `should reject BI generation without a consumer group namespace at request time`() {
        webFluxContextRunner(ApplicationContextRunner())
            .run { context: AssertableApplicationContext ->
                context.assert().hasNotFailed()
                context.biScriptClient().post()
                    .uri(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .exchange()
                    .expectStatus().isBadRequest
            }
    }

    @Test
    fun `should delegate every blank BI string validation to domain options`() {
        val propertyNames = listOf(
            "database" to "database",
            "consumer-database" to "consumerDatabase",
            "topology.cluster.name" to "name",
            "topology.cluster.installation" to "installation",
            "timezone" to "timezone",
            "kafka-bootstrap-servers" to "kafkaBootstrapServers",
            "topic-prefix" to "topicPrefix",
        )

        propertyNames.forEach { (propertyName, optionName) ->
            webFluxContextRunner()
                .withPropertyValues("${BiScriptProperties.PREFIX}.$propertyName= ")
                .run { context: AssertableApplicationContext ->
                    context.startupFailure.assert().isNotNull()
                    context.startupFailure!!.causeChainMessages().assert()
                        .contains("$optionName must not be blank")
                }
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
                BiDeploymentInspectorAutoConfiguration::class.java,
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
                BiDeploymentInspectorAutoConfiguration::class.java,
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
                BiDeploymentInspectorAutoConfiguration::class.java,
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

    private fun webFluxContextRunner(
        base: ApplicationContextRunner = contextRunner,
    ): ApplicationContextRunner {
        return base
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
                BiDeploymentInspectorAutoConfiguration::class.java,
                WebFluxAutoConfiguration::class.java,
            )
    }

    private fun AssertableApplicationContext.assertRouteFactoryRegistered(handlerKey: String) {
        val registrar = getBean(RouteHandlerFunctionRegistrar::class.java)
        registrar.getHttpFactory(handlerKey).assert().isNotNull()
    }

    private fun AssertableApplicationContext.biScriptRouteFactory(): HttpRouteHandlerFunctionFactory {
        val factory = getBean(RouteHandlerFunctionRegistrar::class.java)
            .getHttpFactory(BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT)!!
        factory.assert().isInstanceOf(GenerateBIScriptHandlerFunctionFactory::class.java)
        return factory
    }

    @Suppress("UNCHECKED_CAST")
    private fun AssertableApplicationContext.biScriptClient(): WebTestClient {
        val routerFunction = getBean("commandRouterFunction") as RouterFunction<ServerResponse>
        val webHandler = RouterFunctions.toWebHandler(routerFunction)
        val httpHandler = WebHttpHandlerBuilder.webHandler(webHandler)
            .exceptionHandler(getBean(WebExceptionHandler::class.java))
            .build()
        return WebTestClient.bindToServer(HttpHandlerConnector(httpHandler)).build()
    }

    private fun AssertableApplicationContext.generateBiScript(
        request: BiScriptRequest = BiScriptRequest(),
    ): String {
        val factory = biScriptRouteFactory()
        val contract = HttpRouteContract(
            routeId = "bi-script",
            method = Https.Method.POST,
            path = BuiltInHttpRoutePaths.Global.BI_SCRIPT,
            handlerKey = BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT,
            accept = listOf(Https.MediaType.APPLICATION_SQL),
        )
        val routerFunction = RouterFunctions.route()
            .POST(contract.path, factory.create(contract))
            .build()
        return WebTestClient.bindToRouterFunction(routerFunction)
            .build()
            .post()
            .uri(contract.path)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.parseMediaType(Https.MediaType.APPLICATION_SQL))
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType(Https.MediaType.APPLICATION_SQL)
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
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
}
