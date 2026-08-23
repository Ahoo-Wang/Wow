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

package me.ahoo.wow.webflux.exception

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.test.test

class WebFluxRequestExceptionHandlerTest {
    @Test
    fun `should handle request exception`() {
        val request = MockServerRequest.builder().build()
        val ex = IllegalArgumentException()
        WebFluxRequestExceptionHandler().handle(request, ex)
            .test()
            .consumeNextWith {
                it.statusCode().is4xxClientError.assert().isTrue()
            }.verifyComplete()
    }

    @Test
    fun `should log client error without stack trace`() {
        val warnings = captureWarnings {
            WebFluxRequestExceptionHandler().handle(
                MockServerRequest.builder().build(),
                IllegalArgumentException("invalid request"),
            ).block()
        }

        warnings.assert().hasSize(1)
        warnings.single().formattedMessage.assert().contains("invalid request")
        warnings.single().throwableProxy.assert().isNull()
    }

    @Test
    fun `should retain stack trace for server error`() {
        val warnings = captureWarnings {
            WebFluxRequestExceptionHandler().handle(
                MockServerRequest.builder().build(),
                RuntimeException("server error"),
            ).block()
        }

        warnings.assert().hasSize(1)
        warnings.single().throwableProxy.assert().isNotNull()
    }

    private fun captureWarnings(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(WebFluxRequestExceptionHandler::class.java) as Logger
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
}
