package me.ahoo.wow.webflux.route.snapshot

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.openapi.snapshot.SingleSnapshotStateRouteSpec
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.net.URI

class SingleSnapshotStateHandlerFunctionTest {

    @Test
    fun handle() {
        val handlerFunction = SingleSnapshotStateHandlerFunctionFactory(
            MockQueryHandler.queryHandler,
            exceptionHandler = DefaultRequestExceptionHandler,
        ).create(SingleSnapshotStateRouteSpec(MOCK_AGGREGATE_METADATA, MOCK_AGGREGATE_METADATA, false))
        val request = mockk<ServerRequest> {
            every { method() } returns HttpMethod.GET
            every { uri() } returns URI.create("http://localhost")
            every { pathVariables()[MessageRecords.TENANT_ID] } returns GlobalIdGenerator.generateAsString()
            every { bodyToMono(SingleQuery::class.java) } returns SingleQuery(Condition.ALL).toMono()
        }

        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                assertThat(it.statusCode(), equalTo(HttpStatus.NOT_FOUND))
            }.verifyComplete()
    }
}
