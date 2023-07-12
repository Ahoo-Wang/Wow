package me.ahoo.wow.webflux.handler

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.openapi.command.CommandHeaders
import me.ahoo.wow.webflux.route.command.getCommandStage
import me.ahoo.wow.webflux.route.command.getWaitContext
import me.ahoo.wow.webflux.route.command.getWaitProcessor
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.server.ServerRequest

class CommandHandlerKtTest {

    @Test
    fun getCommandStage() {
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(CommandHeaders.WAIT_STAGE) } returns "SENT"
        }
        assertThat(request.getCommandStage(), equalTo(CommandStage.SENT))
    }

    @Test
    fun getCommandStageIfNull() {
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(CommandHeaders.WAIT_STAGE) } returns null
        }
        assertThat(request.getCommandStage(), equalTo(CommandStage.PROCESSED))
    }

    @Test
    fun getWaitContext() {
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(CommandHeaders.WAIT_CONTEXT) } returns "test"
        }
        assertThat(request.getWaitContext(), equalTo("test"))
    }

    @Test
    fun getWaitProcessor() {
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(CommandHeaders.WAIT_PROCESSOR) } returns "test"
        }
        assertThat(request.getWaitProcessor(), equalTo("test"))
    }
}
