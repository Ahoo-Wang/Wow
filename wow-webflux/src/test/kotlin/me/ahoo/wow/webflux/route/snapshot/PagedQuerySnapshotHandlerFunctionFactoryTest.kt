package me.ahoo.wow.webflux.route.snapshot

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.aggregate.snapshot.PagedQuerySnapshotRouteSpec
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class PagedQuerySnapshotHandlerFunctionFactoryTest {

    @Test
    fun handle() {
        val handlerFunction = PagedQuerySnapshotHandlerFunctionFactory(
            MockQueryHandler.queryHandler,
            exceptionHandler = DefaultRequestExceptionHandler,
        ).create(
            PagedQuerySnapshotRouteSpec(
                MOCK_AGGREGATE_METADATA,
                aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata(),
                appendTenantPath = true,
                appendOwnerPath = false,
                componentContext = OpenAPIComponentContext.default()
            )
        )

        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.TENANT_ID, generateGlobalId())
            .pathVariable(MessageRecords.OWNER_ID, generateGlobalId())
            .body(PagedQuery(Condition.ALL).toMono())

        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                assertThat(it.statusCode(), equalTo(HttpStatus.OK))
            }.verifyComplete()
    }
}
