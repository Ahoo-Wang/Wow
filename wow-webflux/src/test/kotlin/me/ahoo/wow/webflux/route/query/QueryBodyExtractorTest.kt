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

package me.ahoo.wow.webflux.route.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteTestFixtures
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class QueryBodyExtractorTest {

    @Test
    fun `should extract condition via count handler`() {
        // Test condition extraction through CountQueryHandlerFunction end-to-end
        val handlerFunction = CountQueryHandlerFunctionFactory(
            me.ahoo.wow.openapi.aggregate.snapshot.CountSnapshotRouteSpec::class.java,
            RouteTestFixtures.snapshotQueryHandler,
            DefaultRewriteRequestCondition,
            DefaultRequestExceptionHandler
        ).create(
            me.ahoo.wow.openapi.aggregate.snapshot.CountSnapshotRouteSpec(
                MOCK_AGGREGATE_METADATA,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA,
                appendTenantPath = true,
                appendOwnerPath = false,
                componentContext = me.ahoo.wow.openapi.context.OpenAPIComponentContext.default()
            )
        )

        val request = MockServerRequest.builder()
            .body(Condition.ALL.toMono())

        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(org.springframework.http.HttpStatus.OK)
            }.verifyComplete()
    }

    @Test
    fun `should extract list query via list handler`() {
        val handlerFunction = ListQueryHandlerFunctionFactory(
            me.ahoo.wow.openapi.aggregate.snapshot.ListQuerySnapshotRouteSpec::class.java,
            RouteTestFixtures.snapshotQueryHandler,
            DefaultRewriteRequestCondition,
            DefaultRequestExceptionHandler
        ).create(
            me.ahoo.wow.openapi.aggregate.snapshot.ListQuerySnapshotRouteSpec(
                MOCK_AGGREGATE_METADATA,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA,
                appendTenantPath = true,
                appendOwnerPath = false,
                componentContext = me.ahoo.wow.openapi.context.OpenAPIComponentContext.default()
            )
        )

        val request = MockServerRequest.builder()
            .body(ListQuery(condition = Condition.ALL).toMono())

        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(org.springframework.http.HttpStatus.OK)
            }.verifyComplete()
    }

    @Test
    fun `should extract paged query via paged handler`() {
        val handlerFunction = PagedQueryHandlerFunctionFactory(
            me.ahoo.wow.openapi.aggregate.snapshot.PagedQuerySnapshotRouteSpec::class.java,
            RouteTestFixtures.snapshotQueryHandler,
            DefaultRewriteRequestCondition,
            DefaultRequestExceptionHandler
        ).create(
            me.ahoo.wow.openapi.aggregate.snapshot.PagedQuerySnapshotRouteSpec(
                MOCK_AGGREGATE_METADATA,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA,
                appendTenantPath = true,
                appendOwnerPath = false,
                componentContext = me.ahoo.wow.openapi.context.OpenAPIComponentContext.default()
            )
        )

        val request = MockServerRequest.builder()
            .body(PagedQuery(condition = Condition.ALL).toMono())

        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(org.springframework.http.HttpStatus.OK)
            }.verifyComplete()
    }

    @Test
    fun `should extract single query and return not found when no data`() {
        // NoOpSnapshotQueryServiceFactory returns empty for single query,
        // so throwNotFoundIfEmpty() results in 404 NOT_FOUND.
        // This tests that the body extraction and query pipeline work correctly.
        val handlerFunction = SingleQueryHandlerFunctionFactory(
            me.ahoo.wow.openapi.aggregate.snapshot.SingleSnapshotRouteSpec::class.java,
            RouteTestFixtures.snapshotQueryHandler,
            DefaultRewriteRequestCondition,
            DefaultRequestExceptionHandler
        ).create(
            me.ahoo.wow.openapi.aggregate.snapshot.SingleSnapshotRouteSpec(
                MOCK_AGGREGATE_METADATA,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA,
                appendTenantPath = true,
                appendOwnerPath = false,
                componentContext = me.ahoo.wow.openapi.context.OpenAPIComponentContext.default()
            )
        )

        val request = MockServerRequest.builder()
            .body(SingleQuery(condition = Condition.ALL).toMono())

        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                // NoOp returns empty, which triggers throwNotFoundIfEmpty → 404
                it.statusCode().assert().isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND)
            }.verifyComplete()
    }
}
