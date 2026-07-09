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
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.bi.BiScriptDiagnostic
import me.ahoo.wow.bi.BiScriptDiagnosticCode
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.BiScriptResult
import me.ahoo.wow.bi.ObjectMapStrategy
import me.ahoo.wow.bi.ScriptEngine
import me.ahoo.wow.bi.UnsupportedTypeStrategy
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.webflux.route.global.BiScriptRouteObjectMapStrategy
import me.ahoo.wow.webflux.route.global.BiScriptRouteOptions
import me.ahoo.wow.webflux.route.global.BiScriptRouteUnsupportedTypeStrategy
import me.ahoo.wow.webflux.route.global.GenerateBIScriptHandlerFunction
import me.ahoo.wow.webflux.route.global.GenerateBIScriptHandlerFunctionFactory
import me.ahoo.wow.webflux.route.testGlobalRouteContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.kotlin.test.test
import java.lang.reflect.Modifier

class GenerateBIScriptHandlerFunctionTest {
    @Test
    fun `should handle generate bi script request with legacy defaults`() {
        val handlerFunction = GenerateBIScriptHandlerFunctionFactory().create(
            testGlobalRouteContract(BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT)
        )
        val request = MockServerRequest.builder()
            .build()

        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
                it.headers().contentType.assert().isEqualTo(APPLICATION_SQL)
                val body = it.writeBody()
                body.assert().contains("-- global --")
                body.assert().contains("ENGINE = Kafka('localhost:9093'")
            }.verifyComplete()
    }

    @Test
    fun `should handle generate bi script with custom parameters`() {
        val handlerFunction =
            GenerateBIScriptHandlerFunctionFactory(
                kafkaBootstrapServers = "kafkaBootstrapServers",
                topicPrefix = "topicPrefix",
            ).create(
                testGlobalRouteContract(BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT)
            )
        val request = MockServerRequest.builder().build()
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
                it.headers().contentType.assert().isEqualTo(APPLICATION_SQL)
                val body = it.writeBody()
                body.assert().contains("-- global --")
                body.assert().contains("ENGINE = Kafka('kafkaBootstrapServers'")
                body.assert().contains("'topicPrefix")
            }.verifyComplete()
    }

    @Test
    fun `should preserve blank legacy parameters`() {
        val handlerFunction = GenerateBIScriptHandlerFunctionFactory(
            kafkaBootstrapServers = "",
            topicPrefix = "",
        ).create(testGlobalRouteContract(BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT))

        handlerFunction.handle(MockServerRequest.builder().build())
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
                it.headers().contentType.assert().isEqualTo(APPLICATION_SQL)
                val body = it.writeBody()
                body.assert().contains("ENGINE = Kafka(''")
                body.assert().contains("'example.order.command'")
            }.verifyComplete()
    }

    @Test
    fun `should resolve null route options from BI defaults`() {
        val options = slot<BiScriptOptions>()
        mockkObject(ScriptEngine)
        try {
            every {
                ScriptEngine.generateResult(any(), capture(options))
            } returns BiScriptResult(script = "SELECT 1;", diagnostics = emptyList())

            val response = GenerateBIScriptHandlerFunctionFactory(BiScriptRouteOptions())
                .create(testGlobalRouteContract(BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT))
                .handle(MockServerRequest.builder().build())
                .block()!!

            response.statusCode().assert().isEqualTo(HttpStatus.OK)
            response.headers().contentType.assert().isEqualTo(APPLICATION_SQL)
            response.writeBody().assert().isEqualTo("SELECT 1;")
            options.captured.assert().isEqualTo(BiScriptOptions())
            verify(exactly = 1) {
                ScriptEngine.generateResult(any(), any<BiScriptOptions>())
            }
        } finally {
            unmockkObject(ScriptEngine)
        }
    }

    @Test
    fun `should map every route option to BI options`() {
        val options = slot<BiScriptOptions>()
        mockkObject(ScriptEngine)
        try {
            every {
                ScriptEngine.generateResult(any(), capture(options))
            } returns BiScriptResult(script = "SELECT 1;", diagnostics = emptyList())

            val response = GenerateBIScriptHandlerFunctionFactory(
                BiScriptRouteOptions(
                    database = "analytics",
                    consumerDatabase = "analytics_consumer",
                    cluster = "analytics_cluster",
                    installation = "analytics_installation",
                    shard = "analytics_shard",
                    replica = "analytics_replica",
                    timezone = "UTC",
                    kafkaBootstrapServers = "kafka:9092",
                    topicPrefix = "analytics.",
                    maxExpansionDepth = 3,
                    unsupportedTypeStrategy = BiScriptRouteUnsupportedTypeStrategy.STRING_WITH_DIAGNOSTIC,
                    objectMapStrategy = BiScriptRouteObjectMapStrategy.FAIL,
                )
            ).create(testGlobalRouteContract(BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT))
                .handle(MockServerRequest.builder().build())
                .block()!!

            response.statusCode().assert().isEqualTo(HttpStatus.OK)
            response.headers().contentType.assert().isEqualTo(APPLICATION_SQL)
            response.writeBody().assert().isEqualTo("SELECT 1;")
            options.captured.assert().isEqualTo(
                BiScriptOptions(
                    database = "analytics",
                    consumerDatabase = "analytics_consumer",
                    cluster = "analytics_cluster",
                    installation = "analytics_installation",
                    shard = "analytics_shard",
                    replica = "analytics_replica",
                    timezone = "UTC",
                    kafkaBootstrapServers = "kafka:9092",
                    topicPrefix = "analytics.",
                    maxExpansionDepth = 3,
                    unsupportedTypeStrategy = UnsupportedTypeStrategy.STRING_WITH_DIAGNOSTIC,
                    objectMapStrategy = ObjectMapStrategy.FAIL,
                )
            )
            verify(exactly = 1) {
                ScriptEngine.generateResult(any(), any<BiScriptOptions>())
            }
        } finally {
            unmockkObject(ScriptEngine)
        }
    }

    @Test
    fun `should reject explicit blank strict route options`() {
        val error = assertThrows<IllegalArgumentException> {
            GenerateBIScriptHandlerFunctionFactory(
                BiScriptRouteOptions(kafkaBootstrapServers = " ")
            ).create(testGlobalRouteContract(BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT))
        }

        error.message.assert().isEqualTo("kafkaBootstrapServers must not be blank")
    }

    @Test
    fun `should reject invalid route expansion depth`() {
        val error = assertThrows<IllegalArgumentException> {
            GenerateBIScriptHandlerFunctionFactory(
                BiScriptRouteOptions(maxExpansionDepth = 0)
            ).create(testGlobalRouteContract(BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT))
        }

        error.message.assert().isEqualTo("maxExpansionDepth must be greater than or equal to 1")
    }

    @Test
    fun `should log every result diagnostic as a warning and return SQL only`() {
        val diagnostics = listOf(
            BiScriptDiagnostic(
                code = BiScriptDiagnosticCode.MAX_DEPTH_REACHED,
                severity = BiScriptDiagnostic.Severity.WARNING,
                aggregate = "example.order",
                path = "items.product",
                message = "Max expansion depth reached.",
            ),
            BiScriptDiagnostic(
                code = BiScriptDiagnosticCode.UNSUPPORTED_TYPE_FALLBACK,
                severity = BiScriptDiagnostic.Severity.WARNING,
                aggregate = "example.order",
                path = "unsupported",
                message = "Unsupported type rendered as String.",
            ),
        )
        mockkObject(ScriptEngine)
        try {
            every {
                ScriptEngine.generateResult(any(), "kafka:9092", "analytics.")
            } returns BiScriptResult(script = "SELECT 1;", diagnostics = diagnostics)

            lateinit var response: ServerResponse
            val warnings = captureHandlerWarnings {
                response = GenerateBIScriptHandlerFunctionFactory("kafka:9092", "analytics.")
                    .create(testGlobalRouteContract(BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT))
                    .handle(MockServerRequest.builder().build())
                    .block()!!
            }

            response.headers().contentType.assert().isEqualTo(APPLICATION_SQL)
            response.writeBody().assert().isEqualTo("SELECT 1;")
            warnings.assert().hasSize(2)
            warnings.map(ILoggingEvent::getFormattedMessage).assert().containsExactly(
                "BI script diagnostic - code:[MAX_DEPTH_REACHED], severity:[WARNING], " +
                    "aggregate:[example.order], path:[items.product], message:[Max expansion depth reached.].",
                "BI script diagnostic - code:[UNSUPPORTED_TYPE_FALLBACK], severity:[WARNING], " +
                    "aggregate:[example.order], path:[unsupported], message:[Unsupported type rendered as String.].",
            )
            verify(exactly = 1) {
                ScriptEngine.generateResult(any(), "kafka:9092", "analytics.")
            }
        } finally {
            unmockkObject(ScriptEngine)
        }
    }

    @Test
    fun `should preserve public handler and factory constructors without BI option leakage`() {
        val handlerConstructors = GenerateBIScriptHandlerFunction::class.java.constructors
            .map { it.parameterTypes.toList() }
        handlerConstructors.assert().contains(
            listOf(String::class.java, String::class.java),
            listOf(BiScriptRouteOptions::class.java),
        )

        val factoryConstructors = GenerateBIScriptHandlerFunctionFactory::class.java.constructors
            .map { it.parameterTypes.toList() }
        factoryConstructors.assert().contains(
            emptyList(),
            listOf(String::class.java, String::class.java),
            listOf(BiScriptRouteOptions::class.java),
        )

        val publicSignatureTypes = listOf(
            GenerateBIScriptHandlerFunction::class.java,
            GenerateBIScriptHandlerFunctionFactory::class.java,
            BiScriptRouteOptions::class.java,
            BiScriptRouteUnsupportedTypeStrategy::class.java,
            BiScriptRouteObjectMapStrategy::class.java,
            Class.forName("me.ahoo.wow.webflux.route.global.GenerateBIScriptHandlerFunctionKt"),
        ).flatMap { type ->
            type.declaredConstructors
                .filter { it.modifiers.isPublicOrProtected() }
                .flatMap { it.parameterTypes.toList() } +
                type.declaredMethods
                    .filter { it.modifiers.isPublicOrProtected() }
                    .flatMap { method -> method.parameterTypes.toList() + method.returnType } +
                type.declaredFields
                    .filter { it.modifiers.isPublicOrProtected() }
                    .map { it.type }
        }
        publicSignatureTypes.map(Class<*>::getName)
            .filter { it.startsWith("me.ahoo.wow.bi.") }
            .assert()
            .isEmpty()
    }

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

    private fun Int.isPublicOrProtected(): Boolean =
        Modifier.isPublic(this) || Modifier.isProtected(this)

    private companion object {
        private val APPLICATION_SQL = MediaType.parseMediaType("application/sql")
        private val SERVER_RESPONSE_CONTEXT = object : ServerResponse.Context {
            private val strategies = HandlerStrategies.withDefaults()
            override fun messageWriters() = strategies.messageWriters()
            override fun viewResolvers() = strategies.viewResolvers()
        }
    }
}
