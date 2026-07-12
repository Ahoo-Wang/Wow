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

package me.ahoo.wow.webflux.route.bi

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.bi.BiScriptDiagnostic
import me.ahoo.wow.bi.BiScriptGenerator
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.ClickHouseTopology
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.configuration.NamedAggregateTypeSearcher
import me.ahoo.wow.configuration.TypeNamedAggregateSearcher
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.bi.BiScriptRequest
import me.ahoo.wow.openapi.contract.bi.BiScriptTopologyMode
import me.ahoo.wow.openapi.contract.bi.BiScriptTopologyRequest
import me.ahoo.wow.webflux.route.global.GenerateBIScriptHandlerFunction
import me.ahoo.wow.webflux.route.global.GenerateBIScriptHandlerFunctionFactory
import me.ahoo.wow.webflux.route.testGlobalRouteContract
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.lang.reflect.Modifier

class GenerateBIScriptHandlerFunctionTest {
    @Test
    fun `should generate configured BI script through factory`() {
        val options = BiScriptOptions(
            database = "analytics",
            consumerDatabase = "analytics_consumer",
            topology = ClickHouseTopology.Cluster(name = "analytics-cluster"),
            kafkaBootstrapServers = "kafka:9092",
            topicPrefix = "analytics.",
        )
        val handlerFunction = GenerateBIScriptHandlerFunctionFactory(options).create(
            testGlobalRouteContract(BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT)
        )

        handlerFunction.handle(MockServerRequest.builder().body(BiScriptRequest().toMono()))
            .test()
            .consumeNextWith { response ->
                response.statusCode().assert().isEqualTo(HttpStatus.OK)
                response.headers().contentType.assert().isEqualTo(APPLICATION_SQL)
                response.writeBody().run {
                    assert().contains("CREATE DATABASE IF NOT EXISTS \"analytics\"")
                    assert().contains("CREATE DATABASE IF NOT EXISTS \"analytics_consumer\"")
                    assert().contains("ON CLUSTER 'analytics-cluster'")
                    assert().contains("ENGINE = Kafka('kafka:9092'")
                    assert().contains("'analytics.example.order.command'")
                }
            }.verifyComplete()
    }

    @Test
    fun `should generate BI script from request overrides`() {
        val handler = GenerateBIScriptHandlerFunction(BASE_OPTIONS)
        val request = MockServerRequest.builder()
            .body(
                BiScriptRequest(
                    database = "request_db",
                    topology = BiScriptTopologyRequest(mode = BiScriptTopologyMode.STANDALONE),
                ).toMono()
            )

        handler.handle(request).test()
            .consumeNextWith { response ->
                response.statusCode().assert().isEqualTo(HttpStatus.OK)
                response.writeBody().assert()
                    .contains("CREATE DATABASE IF NOT EXISTS \"request_db\"")
                    .doesNotContain("ON CLUSTER", "Replicated", "Distributed", "_local")
            }
            .verifyComplete()
    }

    @Test
    fun `should reject a missing request body`() {
        GenerateBIScriptHandlerFunction(BASE_OPTIONS)
            .handle(MockServerRequest.builder().body(Mono.empty<BiScriptRequest>()))
            .test()
            .expectErrorMatches {
                it is IllegalArgumentException &&
                    it.message == "BI script request body must not be empty"
            }
            .verify()
    }

    @Test
    fun `should return generated SQL and log every generated diagnostic as a warning`() {
        val aggregate = MaterializedNamedAggregate("webflux-bi-test", "diagnostic")
        val aggregateTypes = NamedAggregateTypeSearcher(
            mapOf(aggregate to DiagnosticAggregate::class.java)
        )
        val namedAggregates = TypeNamedAggregateSearcher(
            mapOf(DiagnosticAggregate::class.java to aggregate)
        )
        mockkObject(MetadataSearcher)
        try {
            every { MetadataSearcher.localAggregates } returns setOf(aggregate)
            every { MetadataSearcher.namedAggregateType } returns aggregateTypes
            every { MetadataSearcher.typeNamedAggregate } returns namedAggregates
            val options = BiScriptOptions(topology = ClickHouseTopology.Standalone)
            val generated = BiScriptGenerator(options).generate(setOf(aggregate))
            generated.diagnostics.assert().hasSize(2)

            lateinit var response: ServerResponse
            val warnings = captureHandlerWarnings {
                response = GenerateBIScriptHandlerFunction(options)
                    .handle(MockServerRequest.builder().body(BiScriptRequest().toMono()))
                    .block()!!
            }

            response.statusCode().assert().isEqualTo(HttpStatus.OK)
            response.headers().contentType.assert().isEqualTo(APPLICATION_SQL)
            response.writeBody().assert().isEqualTo(generated.script)
            warnings.map(ILoggingEvent::getFormattedMessage).assert().isEqualTo(
                generated.diagnostics.map(::diagnosticLogMessage)
            )
        } finally {
            unmockkObject(MetadataSearcher)
        }
    }

    @Test
    fun `should not expose diagnostic logging as public JVM API`() {
        GenerateBIScriptHandlerFunction::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .map { it.name }
            .assert()
            .containsExactly("handle")
    }

    private fun diagnosticLogMessage(diagnostic: BiScriptDiagnostic): String =
        "BI script diagnostic - code:[${diagnostic.code}], aggregate:[${diagnostic.aggregate}], " +
            "path:[${diagnostic.path}], sourceType:[${diagnostic.sourceType}], " +
            "decision:[${diagnostic.decision}], message:[${diagnostic.message}]."

    private fun ServerResponse.writeBody(): String {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build())
        writeTo(exchange, SERVER_RESPONSE_CONTEXT)
            .test()
            .verifyComplete()
        return exchange.response.bodyAsString.block()!!
    }

    private fun captureHandlerWarnings(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(GenerateBIScriptHandlerFunction::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            block()
            appender.list.filter { it.level == Level.WARN }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private companion object {
        private val APPLICATION_SQL = MediaType.parseMediaType("application/sql")
        private val BASE_OPTIONS = BiScriptOptions(
            database = "base_db",
            consumerDatabase = "base_consumer",
            topology = ClickHouseTopology.Cluster(name = "base-cluster"),
        )
        private val SERVER_RESPONSE_CONTEXT = object : ServerResponse.Context {
            private val strategies = HandlerStrategies.withDefaults()
            override fun messageWriters() = strategies.messageWriters()
            override fun viewResolvers() = strategies.viewResolvers()
        }
    }
}

@AggregateRoot
@Suppress("UnusedPrivateProperty")
private class DiagnosticAggregate(private val state: DiagnosticState)

private class DiagnosticState(val id: String) {
    val otherValues: Map<String, Any> = emptyMap()
    val values: Map<String, Any> = emptyMap()
}
