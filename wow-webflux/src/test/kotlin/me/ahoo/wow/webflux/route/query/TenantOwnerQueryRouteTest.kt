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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.authenticatedQueryScope
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.queryScope
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.HttpRouteMaterializer
import me.ahoo.wow.webflux.route.RouteHandlerFunctionRegistrar
import me.ahoo.wow.webflux.route.event.CountEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.CountSnapshotHandlerFunctionFactory
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunctions
import reactor.core.publisher.Mono

/**
 * The order aggregate is owned (`owner = ALWAYS`) and has no static tenant, so its query routes come in four scopes.
 * Each generated route is materialized as the server does, in catalog order, and the scope the gateway sees is the
 * one its path states: the tenant + owner route narrows to both, as declared (path) values.
 */
class TenantOwnerQueryRouteTest {
    private val scopes = mutableListOf<Pair<FilterExpression, FilterExpression>>()

    private val snapshotGateway = mockk<SnapshotQueryGateway<Any>> {
        every { entryPolicy } returns QueryEntryPolicy.DEFAULT
        every { count(any()) } returns Mono.deferContextual {
            scopes += it.queryScope() to it.authenticatedQueryScope()
            Mono.just(1L)
        }
    }
    private val eventGateway = mockk<EventStreamQueryGateway> {
        every { entryPolicy } returns QueryEntryPolicy.DEFAULT
        every { count(any()) } returns Mono.deferContextual {
            scopes += it.queryScope() to it.authenticatedQueryScope()
            Mono.just(1L)
        }
    }

    private val client: WebTestClient = run {
        val exceptionHandler = WebFluxRequestExceptionHandler()
        val registrar = RouteHandlerFunctionRegistrar(
            listOf(
                CountSnapshotHandlerFunctionFactory({ snapshotGateway }, DefaultQueryRequestScope, exceptionHandler),
                CountEventStreamHandlerFunctionFactory({ eventGateway }, DefaultQueryRequestScope, exceptionHandler),
            )
        )
        val materializer = HttpRouteMaterializer(registrar)
        val countKeys = setOf(BuiltInHttpRouteHandlerKeys.Snapshot.COUNT, BuiltInHttpRouteHandlerKeys.Event.COUNT)
        val builder = RouterFunctions.route()
        RouterSpecs(MaterializedNamedBoundedContext("example-service")).build().toRouteCatalog().routes
            .filter { it.handlerKey in countKeys && it.path.contains("/sales-order/") }
            .forEach {
                val binding = materializer.materialize(it)
                builder.route(binding.predicate, binding.handlerFunction)
            }
        WebTestClient.bindToRouterFunction(builder.build()).build()
    }

    @ParameterizedTest
    @CsvSource(
        "/sales-order/snapshot/count,,",
        "/tenant/tenant-a/sales-order/snapshot/count,tenant-a,",
        "/owner/owner-a/sales-order/snapshot/count,,owner-a",
        "/tenant/tenant-a/owner/owner-a/sales-order/snapshot/count,tenant-a,owner-a",
        "/sales-order/event/count,,",
        "/tenant/tenant-a/sales-order/event/count,tenant-a,",
        "/owner/owner-a/sales-order/event/count,,owner-a",
        "/tenant/tenant-a/owner/owner-a/sales-order/event/count,tenant-a,owner-a",
    )
    fun `a query route scopes to the tenant and owner its path states`(
        path: String,
        tenantId: String?,
        ownerId: String?
    ) {
        client.post().uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"op":"MATCH_ALL"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).isEqualTo("1")

        val expected = listOfNotNull(tenantId?.let(::TenantIdFilter), ownerId?.let(::OwnerIdFilter))
            .fold(MatchAllFilter as FilterExpression) { scope, part -> scope.appendFilter(part) }
        // Path values are declared, not authenticated: a filter, not a security boundary on their own.
        scopes.assert().containsExactly(expected to MatchAllFilter)
    }
}
