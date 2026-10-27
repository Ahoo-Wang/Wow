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

package me.ahoo.wow.viewstore.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Swagger UI on the view store: springdoc's routes answer without a caller, its document lists the public port's routes
 * alone, and declares the credentials of the callers the node takes: the `Wow-*` headers in development, CoSec's
 * bearer token behind the gateway.
 */
class ViewStoreSwaggerUiTest {
    /** GETs [path] from [node] without a caller, following redirects. */
    private fun get(node: ViewStoreNode, path: String): HttpResponse<String> {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().use { http ->
            http.send(
                HttpRequest.newBuilder(node.server.http.base.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }
    }

    @Test
    fun `in development the document should list the routes and the development headers, without a caller`() {
        startViewStore().use { node ->
            val docs = get(node, "/v3/api-docs")
            docs.statusCode().assert().describedAs(docs.body()).isEqualTo(OK)
            docs.body().assert().contains("\"/view-store/", "\"${DevelopmentCallerResolver.USER}\"")
                // The internal port's routes are never in it.
                .doesNotContain("/wow/internal/", "/wow/node/")
            get(node, "/swagger-ui.html").statusCode().assert().isEqualTo(OK)
        }
    }

    @Test
    fun `behind the gateway the document should declare CoSec's bearer token`() {
        startViewStore(profiles = listOf("cosec")).use { node ->
            val docs = get(node, "/v3/api-docs")
            docs.statusCode().assert().describedAs(docs.body()).isEqualTo(OK)
            docs.body().assert().contains("\"bearer\"").doesNotContain(DevelopmentCallerResolver.USER)
        }
    }

    private companion object {
        const val OK = 200
    }
}
