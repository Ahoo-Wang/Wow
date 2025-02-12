package me.ahoo.wow.webflux.route.snapshot

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.openapi.route.aggregateRouteMetadata
import me.ahoo.wow.openapi.snapshot.LoadSnapshotRouteSpec
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.test.test
import java.net.URI

class LoadSnapshotHandlerFunctionTest {

    @Test
    fun handle() {
        val handlerFunction = LoadSnapshotHandlerFunctionFactory(
            snapshotQueryHandler = MockQueryHandler.queryHandler,
            exceptionHandler = DefaultRequestExceptionHandler,
        ).create(
            LoadSnapshotRouteSpec(
                MOCK_AGGREGATE_METADATA,
                aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()
            )
        )
        val request = mockk<ServerRequest> {
            every { method() } returns HttpMethod.GET
            every { uri() } returns URI.create("http://localhost")
            every { pathVariables()[RoutePaths.ID_KEY] } returns generateGlobalId()
            every { pathVariables()[MessageRecords.TENANT_ID] } returns generateGlobalId()
        }
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                MatcherAssert.assertThat(it.statusCode(), Matchers.equalTo(HttpStatus.NOT_FOUND))
            }.verifyComplete()
    }
}
