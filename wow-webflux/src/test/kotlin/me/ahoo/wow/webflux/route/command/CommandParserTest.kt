package me.ahoo.wow.webflux.route.command

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.openapi.command.CommandHeaders
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.webflux.route.command.CommandParser.parse
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class CommandParserTest {

    @Test
    fun parse() {
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(CommandHeaders.WAIT_TIME_OUT) } returns null
            every { pathVariables()[MessageRecords.TENANT_ID] } returns GlobalIdGenerator.generateAsString()
            every { headers().firstHeader(CommandHeaders.AGGREGATE_VERSION) } returns 1.toString()
            every { pathVariables()[RoutePaths.ID_KEY] } returns null
            every { headers().firstHeader(CommandHeaders.AGGREGATE_ID) } returns null
            every { headers().firstHeader(CommandHeaders.REQUEST_ID) } returns null
            every { principal() } returns Mono.empty()
            every { headers().firstHeader(CommandHeaders.WAIT_STAGE) } returns CommandStage.SENT.toString()
        }
        request.parse(
            aggregateMetadata = MOCK_AGGREGATE_METADATA,
            commandBody = MockCreateAggregate(
                id = GlobalIdGenerator.generateAsString(),
                data = GlobalIdGenerator.generateAsString(),
            )
        ).test()
            .expectNextCount(1)
            .verifyComplete()
    }
}
