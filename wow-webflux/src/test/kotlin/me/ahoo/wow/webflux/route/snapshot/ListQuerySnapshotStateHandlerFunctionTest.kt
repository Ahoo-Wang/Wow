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

package me.ahoo.wow.webflux.route.snapshot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.aggregate.snapshot.ListQuerySnapshotStateRouteSpec
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteTestFixtures
import me.ahoo.wow.webflux.route.query.DefaultRewriteRequestCondition
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class ListQuerySnapshotStateHandlerFunctionTest {

    @Test
    fun `should handle list query snapshot state request`() {
        val handlerFunction = ListQuerySnapshotStateHandlerFunctionFactory(
            RouteTestFixtures.snapshotQueryHandler,
            rewriteRequestCondition = DefaultRewriteRequestCondition,
            exceptionHandler = DefaultRequestExceptionHandler,
        ).create(
            ListQuerySnapshotStateRouteSpec(
                MOCK_AGGREGATE_METADATA,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA,
                appendTenantPath = true,
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
