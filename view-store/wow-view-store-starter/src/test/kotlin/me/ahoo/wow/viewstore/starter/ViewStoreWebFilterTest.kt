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
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.api.ViewStoreErrorCodes
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
    private val filter = ViewStoreWebFilter(ViewStorePaths(MaterializedNamedBoundedContext("host")), provider)

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
            MockServerHttpRequest.get("/view-store/tenant/t1/owner/(shared)/view/open/state")
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
}
