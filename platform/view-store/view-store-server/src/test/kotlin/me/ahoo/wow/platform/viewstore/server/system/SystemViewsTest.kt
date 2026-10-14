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

package me.ahoo.wow.platform.viewstore.server.system

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.platform.viewstore.api.ViewStoreContext
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient

/** The configured system views: checked at startup, matched by tenant and application, revised by content. */
class SystemViewsTest {
    private fun entry(
        id: String = "all",
        title: String = "All",
        config: String = """{"kind":"record"}""",
        tenantId: String = "",
        appId: String = "",
        definitionId: String = "orders",
    ) = SystemViewProperties(tenantId, appId, id, definitionId, title, config)

    @Test
    fun `an entry should be for its tenant and application, or every one when blank`() {
        val views = ConfiguredSystemViews(
            listOf(
                entry("all"),
                entry("t1", tenantId = "t1"),
                entry("a1", appId = "a1"),
                entry("both", "B", "{\"kind\":\"record\"}", "t1", "a1")
            ),
        )
        views.systemViews("t1", "a1").map { it.id }.assert().containsExactly("all", "t1", "a1", "both")
        views.systemViews("t2", "a2").map { it.id }.assert().containsExactly("all")
    }

    @Test
    fun `a revision should change exactly when the content does`() {
        val one = SystemViews.of("v", "orders", " All ", mapOf("kind" to "record"))
        one.title.assert().isEqualTo("All")
        SystemViews.of("other-id", "orders", "All", mapOf("kind" to "record")).revision.assert().isEqualTo(one.revision)
        SystemViews.of("v", "orders", "All", mapOf("kind" to "analysis")).revision.assert().isNotEqualTo(one.revision)
    }

    @Test
    fun `an entry a saved view could not be should stop the service`() {
        listOf(
            entry(id = " "),
            entry(id = "system:all"),
            entry(title = " "),
            entry(definitionId = " "),
            entry(config = """{"kind":"chart"}"""),
            entry(config = "[1]"),
        ).forEach { bad ->
            assertThrownBy<IllegalArgumentException> { ConfiguredSystemViews(listOf(bad)) }
        }
        assertThrownBy<IllegalArgumentException> { ConfiguredSystemViews(listOf(entry(), entry(title = "Again"))) }
        ConfiguredSystemViews(listOf(entry(), entry(appId = "a1"))).systemViews("t", "a1").assert().hasSize(2)
    }

    @Test
    fun `a request without accepted credentials should be answered 401`() {
        val views = StoredSystemViews(ConfiguredSystemViews(emptyList())) { HttpQueries.NONE }
        val routes = SystemViewRoutes(views, CallerResolver { null }).routes()
        WebTestClient.bindToRouterFunction(routes).build().get().uri(SystemViewRoutes.SYSTEM_VIEWS)
            .header(ViewStoreContext.APP_ID_HEADER, "app")
            .exchange()
            .expectStatus().isUnauthorized
            .expectBody(String::class.java).returnResult().responseBody.assert()
            .contains("\"code\":\"wow.auth.unauthenticated\"")
    }
}
