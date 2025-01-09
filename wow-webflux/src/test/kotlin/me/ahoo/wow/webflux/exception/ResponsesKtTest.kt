package me.ahoo.wow.webflux.exception

import io.mockk.mockk
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.openapi.command.CommandRequestHeaders.WOW_ERROR_CODE
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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
    fun testAsServerResponse() {
        CommandResult(
            stage = CommandStage.SENT,
            aggregateId = GlobalIdGenerator.generateAsString(),
            tenantId = GlobalIdGenerator.generateAsString(),
            requestId = GlobalIdGenerator.generateAsString(),
            commandId = GlobalIdGenerator.generateAsString(),
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
}
