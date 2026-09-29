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

package me.ahoo.wow.viewstore.starter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.contract.BuiltInHttpRoutePaths
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.api.ViewStoreErrorCodes
import me.ahoo.wow.viewstore.api.preferences.SetViewPreferences
import me.ahoo.wow.viewstore.api.view.CreateView
import me.ahoo.wow.viewstore.starter.system.SystemViewProvider
import me.ahoo.wow.viewstore.starter.system.SystemViews
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class ViewStoreWebFilterTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun registerStatuses() {
            ViewStoreErrorStatuses.register()
        }
    }

    private val provider = SystemViewProvider { tenantId, _ ->
        if (tenantId == "t1") {
            Flux.just(SystemViews.of("open", "orders", "Open", JsonSerializer.createObjectNode().put("kind", "record")))
        } else {
            Flux.empty()
        }
    }
    private val hostContext = MaterializedNamedBoundedContext("host")
    private val paths = ViewStorePaths(hostContext)
    private val routerSpecs = RouterSpecs(hostContext).build()
    private val guard = ViewStoreRouteGuard(
        paths,
        routerSpecs,
        ViewStoreTestAggregates.namedAggregates,
    )
    private val filter = ViewStoreWebFilter(paths, provider, guard)

    private class RecordingChain : WebFilterChain {
        var exchange: ServerWebExchange? = null
        override fun filter(exchange: ServerWebExchange): Mono<Void> {
            this.exchange = exchange
            return Mono.empty()
        }
    }

    private fun run(request: MockServerHttpRequest): Pair<MockServerWebExchange, RecordingChain> {
        val exchange = MockServerWebExchange.from(request)
        val chain = RecordingChain()
        filter.filter(exchange, chain).test().verifyComplete()
        return exchange to chain
    }

    @Test
    fun `refuses a write to a system view`() {
        val (exchange, chain) = run(
            MockServerHttpRequest.put("/view-store/tenant/t1/owner/(shared)/view/open/rename")
                .header(ViewStoreService.APP_ID_HEADER, "console").build()
        )
        chain.exchange.assert().isNull()
        exchange.response.statusCode.assert().isEqualTo(HttpStatus.FORBIDDEN)
        exchange.response.bodyAsString.block()!!.assert().contains(ViewStoreErrorCodes.SYSTEM_VIEW_READ_ONLY)
    }

    @Test
    fun `passes reads, other tenants' views and other paths`() {
        run(
            MockServerHttpRequest.get("/view-store/tenant/t1/owner/(shared)/system-views")
                .header(ViewStoreService.APP_ID_HEADER, "console").build()
        ).second.exchange.assert().isNotNull()
        run(
            MockServerHttpRequest.put("/view-store/tenant/t2/owner/(shared)/view/open/rename")
                .header(ViewStoreService.APP_ID_HEADER, "console").build()
        ).second.exchange.assert().isNotNull()
        run(
            MockServerHttpRequest.put("/view-store/tenant/t1/owner/(shared)/view/open/rename").build()
        ).second.exchange.assert().isNotNull()
        run(MockServerHttpRequest.put("/cart/open/rename").build()).second.exchange.assert().isNotNull()
    }

    @Test
    fun `drops an aggregate id a caller picks, on view store paths only`() {
        run(
            MockServerHttpRequest.post("/view-store/tenant/t1/owner/alice/view")
                .header(CommandComponent.Header.AGGREGATE_ID, "chosen").build()
        ).second.exchange!!.request.headers.getFirst(CommandComponent.Header.AGGREGATE_ID).assert().isNull()
        run(
            MockServerHttpRequest.post("/cart").header(CommandComponent.Header.AGGREGATE_ID, "chosen").build()
        ).second.exchange!!.request.headers.getFirst(CommandComponent.Header.AGGREGATE_ID).assert().isEqualTo("chosen")
    }

    @Test
    fun `drops an application a caller sends as a command header, on view store paths only`() {
        val header = CommandComponent.Header.COMMAND_HEADER_X_PREFIX + ViewStoreService.APP_ID_MESSAGE_HEADER
        val passed = run(
            MockServerHttpRequest.post("/view-store/tenant/t1/owner/alice/view")
                .header(header, "portal")
                .header(header.uppercase(), "portal")
                .header(CommandComponent.Header.COMMAND_HEADER_X_PREFIX + "other", "kept")
                .build()
        ).second.exchange!!.request.headers
        passed.headerNames().none { it.equals(header, ignoreCase = true) }.assert().isTrue()
        passed.getFirst(CommandComponent.Header.COMMAND_HEADER_X_PREFIX + "other").assert().isEqualTo("kept")
        run(MockServerHttpRequest.post("/cart").header(header, "portal").build())
            .second.exchange!!.request.headers.getFirst(header).assert().isEqualTo("portal")
    }

    @Test
    fun `closes the routes Wow generates for the view store beside its commands`() {
        listOf(
            MockServerHttpRequest.get("/view-store/tenant/t1/owner/alice/view/v1/state"),
            MockServerHttpRequest.get("/view-store/tenant/t1/owner/alice/view/v1/state/3"),
            MockServerHttpRequest.get("/view-store/tenant/t1/owner/alice/view/v1/state/time/1700000000000"),
            MockServerHttpRequest.get("/view-store/tenant/t1/owner/alice/view/v1/snapshot"),
            MockServerHttpRequest.get("/view-store/tenant/t1/view/v1/state/tracing"),
            MockServerHttpRequest.get("/view-store/tenant/t1/view/v1/event/1/9"),
            MockServerHttpRequest.put("/view-store/tenant/t1/view/v1/snapshot"),
            MockServerHttpRequest.put("/view-store/view/snapshot/0/100"),
            MockServerHttpRequest.post("/view-store/view/state/0/100"),
            MockServerHttpRequest.put("/view-store/tenant/t1/view/v1/2/compensate"),
            MockServerHttpRequest.get("/view-store/tenant/t1/owner/alice/view_preferences/p1/state"),
            MockServerHttpRequest.post("/view-store/owner/alice/view/snapshot/list"),
            MockServerHttpRequest.post("/view-store/tenant/t1/view/event/list"),
            MockServerHttpRequest.post("/view-store/tenant/t1/owner/alice/view/event/list"),
        ).forEach { request ->
            val (exchange, chain) = run(request.header(ViewStoreService.APP_ID_HEADER, "console").build())
            chain.exchange.assert().isNull()
            exchange.response.statusCode.assert().isEqualTo(HttpStatus.NOT_FOUND)
        }
    }

    @Test
    fun `refuses the view store's commands on the command facade`() {
        val facade = BuiltInHttpRoutePaths.Global.COMMAND_SEND
        listOf(
            MockServerHttpRequest.post(
                facade
            ).header(CommandComponent.Header.COMMAND_TYPE, CreateView::class.java.name),
            MockServerHttpRequest.post(facade)
                .header(CommandComponent.Header.COMMAND_TYPE, "me.ahoo.wow.viewstore.api.view.Unknown"),
            MockServerHttpRequest.post(facade)
                .header(CommandComponent.Header.COMMAND_TYPE, SetViewPreferences::class.java.name),
            MockServerHttpRequest.post(facade)
                .header(CommandComponent.Header.COMMAND_TYPE, "any")
                .header(CommandComponent.Header.COMMAND_AGGREGATE_CONTEXT, ViewStoreService.SERVICE_NAME)
                .header(CommandComponent.Header.COMMAND_AGGREGATE_NAME, ViewStoreService.VIEW_AGGREGATE_NAME),
        ).forEach { request ->
            val (exchange, chain) = run(request.build())
            chain.exchange.assert().isNull()
            exchange.response.statusCode.assert().isEqualTo(HttpStatus.NOT_FOUND)
        }
        run(
            MockServerHttpRequest.post(
                facade
            ).header(CommandComponent.Header.COMMAND_TYPE, AddCartItem::class.java.name).build()
        )
            .second.exchange.assert().isNotNull()
    }
}
