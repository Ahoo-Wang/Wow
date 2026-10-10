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
import me.ahoo.wow.rest.CommandHeaders
import me.ahoo.wow.rest.RoutePaths
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.api.ViewStoreErrorCodes
import me.ahoo.wow.viewstore.api.preferences.SetViewPreferences
import me.ahoo.wow.viewstore.api.view.CreateView
import me.ahoo.wow.viewstore.starter.system.SystemViewProvider
import me.ahoo.wow.viewstore.starter.system.SystemViews
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.server.PathContainer
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.net.URI

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
    fun `passes a write under the system owner to an id a configured view has - the stored view wins`() {
        run(
            MockServerHttpRequest.put("/view-store/tenant/t1/owner/(system)/view/open/rename")
                .header(ViewStoreService.APP_ID_HEADER, "console").build()
        ).second.exchange.assert().isNotNull()
    }

    @Test
    fun `passes a request on stored system views spelled as the gateway names it`() {
        listOf(
            MockServerHttpRequest.post("/view-store/tenant/(platform)/owner/(system)/view"),
            MockServerHttpRequest.put("/view-store/tenant/(platform)/owner/(system)/view/v1/save"),
            MockServerHttpRequest.delete("/view-store/tenant/(platform)/owner/(system)/view/v1"),
            MockServerHttpRequest.post("/view-store/tenant/(platform)/owner/(system)/view/snapshot/list"),
        ).forEach {
            run(it.header(ViewStoreService.APP_ID_HEADER, "console").build()).second.exchange.assert().isNotNull()
        }
    }

    @Test
    fun `the system owner in another tenant is no system scope - the domain refuses its create`() {
        run(
            MockServerHttpRequest.post("/view-store/tenant/(0)/owner/(system)/view")
                .header(ViewStoreService.APP_ID_HEADER, "console").build()
        ).second.exchange.assert().isNotNull()
        paths.isSystemScope(PathContainer.parsePath("/view-store/tenant/(0)/owner/(system)/view")).assert().isFalse()
        paths.isSystemScope(PathContainer.parsePath("/view-store/tenant/(platform)/owner/(system)/view"))
            .assert().isTrue()
    }

    @Test
    fun `refuses a request on stored system views spelled otherwise`() {
        listOf(
            "/view-store/tenant/(platform)/owner/%28system%29/view",
            "/view-store/tenant/%28PLATFORM%29/owner/(system)/view",
            "/view-store/tenant/(Platform)/owner/(SYSTEM)/view",
            "/view-store/tenant/(platform)/OWNER/(system)/view",
            "/view-store/tenant/%28platform%29/owner/(system)/view",
            "/view-store/tenant/(platform)/owner/(system);x=1/view",
            "/view-store/tenant/(platform);x=1/owner/(system)/view/v1/save",
            "/VIEW-STORE/tenant/(platform)/owner/(system)/view",
            "/view-store/TENANT/(platform)/Owner/(system)/view/v1",
        ).forEach { path ->
            val (exchange, chain) = run(
                MockServerHttpRequest.method(HttpMethod.POST, URI.create(path))
                    .header(ViewStoreService.APP_ID_HEADER, "console").build()
            )
            chain.exchange.assert().describedAs(path).isNull()
            exchange.response.statusCode.assert().describedAs(path).isEqualTo(HttpStatus.BAD_REQUEST)
            exchange.response.bodyAsString.block()!!.assert().contains(ViewStoreErrorCodes.VIEW_SCOPE_REQUIRED)
        }
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
                .header(CommandHeaders.AGGREGATE_ID, "chosen").build()
        ).second.exchange!!.request.headers.getFirst(CommandHeaders.AGGREGATE_ID).assert().isNull()
        run(
            MockServerHttpRequest.post("/cart").header(CommandHeaders.AGGREGATE_ID, "chosen").build()
        ).second.exchange!!.request.headers.getFirst(CommandHeaders.AGGREGATE_ID).assert().isEqualTo("chosen")
    }

    @Test
    fun `drops an application a caller sends as a command header, on view store paths only`() {
        val header = CommandHeaders.COMMAND_HEADER_X_PREFIX + ViewStoreService.APP_ID_MESSAGE_HEADER
        val passed = run(
            MockServerHttpRequest.post("/view-store/tenant/t1/owner/alice/view")
                .header(header, "portal")
                .header(header.uppercase(), "portal")
                .header(CommandHeaders.COMMAND_HEADER_X_PREFIX + "other", "kept")
                .build()
        ).second.exchange!!.request.headers
        passed.headerNames().none { it.equals(header, ignoreCase = true) }.assert().isTrue()
        passed.getFirst(CommandHeaders.COMMAND_HEADER_X_PREFIX + "other").assert().isNull()
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
        val facade = RoutePaths.COMMAND_SEND
        listOf(
            MockServerHttpRequest.post(
                facade
            ).header(CommandHeaders.COMMAND_TYPE, CreateView::class.java.name),
            MockServerHttpRequest.post(facade)
                .header(CommandHeaders.COMMAND_TYPE, "me.ahoo.wow.viewstore.api.view.Unknown"),
            MockServerHttpRequest.post(facade)
                .header(CommandHeaders.COMMAND_TYPE, SetViewPreferences::class.java.name),
            MockServerHttpRequest.post(facade)
                .header(CommandHeaders.COMMAND_TYPE, "any")
                .header(CommandHeaders.COMMAND_AGGREGATE_CONTEXT, ViewStoreService.SERVICE_NAME)
                .header(CommandHeaders.COMMAND_AGGREGATE_NAME, ViewStoreService.VIEW_AGGREGATE_NAME),
        ).forEach { request ->
            val (exchange, chain) = run(request.build())
            chain.exchange.assert().isNull()
            exchange.response.statusCode.assert().isEqualTo(HttpStatus.NOT_FOUND)
        }
        run(
            MockServerHttpRequest.post(
                facade
            ).header(CommandHeaders.COMMAND_TYPE, AddCartItem::class.java.name).build()
        )
            .second.exchange.assert().isNotNull()
    }

    @Test
    fun `refuses a tenant or owner that is blank or holds whitespace`() {
        listOf(
            raw(HttpMethod.PUT, "/view-store/tenant/t1/owner/%20/view/v1/rename"),
            raw(HttpMethod.PUT, "/view-store/tenant/t1/owner/%09/view/v1/rename"),
            raw(HttpMethod.PUT, "/view-store/tenant/t1/owner/%E3%80%80/view/v1/rename"),
            raw(HttpMethod.PUT, "/view-store/tenant/t1/owner/%20;x=alice/view/v1/rename"),
            raw(HttpMethod.POST, "/view-store/tenant/%20/owner/alice/view"),
            raw(HttpMethod.GET, "/view-store/tenant/t1/owner/%20/definitions/d1/preferences"),
            raw(HttpMethod.POST, "/view-store/tenant/t1/owner/%20/view/snapshot/list"),
        ).forEach { request ->
            val (exchange, chain) = run(
                request.header(ViewStoreService.APP_ID_HEADER, "console")
                    .header(CommandHeaders.OWNER_ID, "alice")
                    .header(CommandHeaders.TENANT_ID, "t1")
                    .build()
            )
            chain.exchange.assert().isNull()
            exchange.response.statusCode.assert().isEqualTo(HttpStatus.BAD_REQUEST)
            exchange.response.bodyAsString.block()!!.assert().contains(ViewStoreErrorCodes.VIEW_SCOPE_REQUIRED)
        }
    }

    @Test
    fun `drops a tenant and owner a caller sends as headers, on view store paths only`() {
        val passed = run(
            MockServerHttpRequest.put("/view-store/tenant/t1/owner/alice/view/v1/rename")
                .header(CommandHeaders.OWNER_ID, "bob")
                .header(CommandHeaders.OWNER_ID.uppercase(), "bob")
                .header(CommandHeaders.TENANT_ID.lowercase(), "t9")
                .build()
        ).second.exchange!!.request.headers
        passed.headerNames().none {
            it.equals(CommandHeaders.OWNER_ID, ignoreCase = true) ||
                it.equals(CommandHeaders.TENANT_ID, ignoreCase = true)
        }.assert().isTrue()
        run(MockServerHttpRequest.post("/cart").header(CommandHeaders.OWNER_ID, "bob").build())
            .second.exchange!!.request.headers.getFirst(CommandHeaders.OWNER_ID).assert().isEqualTo("bob")
    }

    @Test
    fun `drops every command header a caller sends, on view store paths only`() {
        val prefix = CommandHeaders.COMMAND_HEADER_X_PREFIX
        val passed = run(
            MockServerHttpRequest.post("/view-store/tenant/t1/OWNER/alice/view")
                .header(prefix + "command_operator", "bob")
                .header(prefix.lowercase() + "app_id", "portal")
                .header(prefix.uppercase() + "ANY", "x")
                .header(CommandHeaders.AGGREGATE_ID, "chosen")
                .header(CommandHeaders.REQUEST_ID, "r1")
                .build()
        ).second.exchange!!.request.headers
        passed.headerNames().none { it.startsWith(prefix, ignoreCase = true) }.assert().isTrue()
        passed.getFirst(CommandHeaders.AGGREGATE_ID).assert().isNull()
        passed.getFirst(CommandHeaders.REQUEST_ID).assert().isEqualTo("r1")
        run(MockServerHttpRequest.post("/cart").header(prefix + "command_operator", "bob").build())
            .second.exchange!!.request.headers.getFirst(prefix + "command_operator").assert().isEqualTo("bob")
    }

    @Test
    fun `refuses in any case what a case-insensitive host would route`() {
        listOf(
            raw(HttpMethod.GET, "/view-store/tenant/t1/owner/alice/VIEW/v1/state"),
            raw(HttpMethod.GET, "/VIEW-STORE/tenant/t1/OWNER/alice/view/v1/snapshot"),
            raw(HttpMethod.POST, "/WOW/command/SEND")
                .header(CommandHeaders.COMMAND_TYPE, CreateView::class.java.name),
        ).forEach { request ->
            val (exchange, chain) = run(request.header(ViewStoreService.APP_ID_HEADER, "console").build())
            chain.exchange.assert().isNull()
            exchange.response.statusCode.assert().isEqualTo(HttpStatus.NOT_FOUND)
        }
        val (exchange, chain) = run(raw(HttpMethod.PUT, "/view-store/tenant/t1/OWNER/%E2%80%8B/view/v1/rename").build())
        chain.exchange.assert().isNull()
        exchange.response.statusCode.assert().isEqualTo(HttpStatus.BAD_REQUEST)
        run(
            raw(HttpMethod.PUT, "/view-store/tenant/t1/OWNER/(shared)/VIEW/open/rename")
                .header(ViewStoreService.APP_ID_HEADER, "console").build()
        ).first.response.statusCode.assert().isEqualTo(HttpStatus.FORBIDDEN)
    }

    /** [path] exactly as written: `%xx` escapes are not encoded again. */
    private fun raw(method: HttpMethod, path: String) = MockServerHttpRequest.method(method, URI.create(path))
}
