package me.ahoo.wow.webflux.route.snapshot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.aggregate.snapshot.ListQuerySnapshotStateRouteSpec
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class ListQuerySnapshotStateHandlerFunctionTest {

    @Test
    fun handle() {
        val handlerFunction = ListQuerySnapshotStateHandlerFunctionFactory(
            MockQueryHandler.queryHandler,
            exceptionHandler = DefaultRequestExceptionHandler,
        ).create(
            ListQuerySnapshotStateRouteSpec(
                MOCK_AGGREGATE_METADATA,
                aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata(),
                appendTenantPath = true,
                appendSpacePath = true,
                appendOwnerPath = false,
                componentContext = OpenAPIComponentContext.default()
            )
        )
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.TENANT_ID, GlobalIdGenerator.generateAsString())
            .pathVariable(MessageRecords.OWNER_ID, generateGlobalId())
            .body(ListQuery(Condition.ALL).toMono())

        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
            }.verifyComplete()
    }
}
