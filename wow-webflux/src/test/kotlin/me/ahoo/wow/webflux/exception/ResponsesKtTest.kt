package me.ahoo.wow.webflux.exception

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.command.CommandRequestHeaders.WOW_ERROR_CODE
import me.ahoo.wow.webflux.route.toCommandResponse
import me.ahoo.wow.webflux.route.toResponseEntity
import me.ahoo.wow.webflux.route.toServerResponse
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class ResponsesKtTest {

    @Test
    fun toResponseEntity() {
        val responseEntity = IllegalArgumentException()
            .toResponseEntity()
        assertThat(responseEntity.statusCode, equalTo(HttpStatus.BAD_REQUEST))
        assertThat(responseEntity.headers.contentType, equalTo(MediaType.APPLICATION_JSON))
        assertThat(responseEntity.headers.getFirst(WOW_ERROR_CODE), equalTo(ErrorCodes.ILLEGAL_ARGUMENT))
    }

    @Test
    fun toServerResponse() {
        IllegalArgumentException()
            .toErrorInfo()
            .toServerResponse()
            .test()
            .consumeNextWith {
                assertThat(it.statusCode(), equalTo(HttpStatus.BAD_REQUEST))
                assertThat(it.headers().contentType, equalTo(MediaType.APPLICATION_JSON))
                assertThat(it.headers().getFirst(WOW_ERROR_CODE), equalTo(ErrorCodes.ILLEGAL_ARGUMENT))
            }
            .verifyComplete()
    }

    @Test
    fun commandResultToServerResponse() {
        CommandResult(
            stage = CommandStage.SENT,
            aggregateId = generateGlobalId(),
            tenantId = generateGlobalId(),
            requestId = generateGlobalId(),
            commandId = generateGlobalId(),
            contextName = "contextName",
            processorName = "processorName",
        ).toMono()
            .toServerResponse(mockk(), DefaultRequestExceptionHandler)
            .test()
            .consumeNextWith {
                assertThat(it.statusCode(), equalTo(HttpStatus.OK))
                assertThat(it.headers().contentType, equalTo(MediaType.APPLICATION_JSON))
                assertThat(it.headers().getFirst(WOW_ERROR_CODE), equalTo(ErrorCodes.SUCCEEDED))
            }
            .verifyComplete()
    }

    @Test
    fun toCommandResponse() {
        val serverRequest = mockk<ServerRequest> {
            every { headers().accept().contains(MediaType.TEXT_EVENT_STREAM) } returns false
        }
        CommandResult(
            stage = CommandStage.SENT,
            aggregateId = generateGlobalId(),
            tenantId = generateGlobalId(),
            requestId = generateGlobalId(),
            commandId = generateGlobalId(),
            contextName = "contextName",
            processorName = "processorName",
        ).toMono()
            .toCommandResponse(serverRequest, DefaultRequestExceptionHandler)
            .test()
            .consumeNextWith {
                assertThat(it.statusCode(), equalTo(HttpStatus.OK))
                assertThat(it.headers().contentType, equalTo(MediaType.APPLICATION_JSON))
                assertThat(it.headers().getFirst(WOW_ERROR_CODE), equalTo(ErrorCodes.SUCCEEDED))
            }
            .verifyComplete()
    }

    @Test
    fun toStreamCommandResponse() {
        val serverRequest = mockk<ServerRequest> {
            every { headers().accept().contains(MediaType.TEXT_EVENT_STREAM) } returns true
        }
        CommandResult(
            stage = CommandStage.SENT,
            aggregateId = generateGlobalId(),
            tenantId = generateGlobalId(),
            requestId = generateGlobalId(),
            commandId = generateGlobalId(),
            contextName = "contextName",
            processorName = "processorName",
        ).toMono()
            .toCommandResponse(serverRequest, DefaultRequestExceptionHandler)
            .test()
            .consumeNextWith {
                assertThat(it.statusCode(), equalTo(HttpStatus.OK))
                assertThat(it.headers().contentType, equalTo(MediaType.TEXT_EVENT_STREAM))
                assertThat(it.headers().getFirst(WOW_ERROR_CODE), equalTo(ErrorCodes.SUCCEEDED))
            }
            .verifyComplete()
    }
}
