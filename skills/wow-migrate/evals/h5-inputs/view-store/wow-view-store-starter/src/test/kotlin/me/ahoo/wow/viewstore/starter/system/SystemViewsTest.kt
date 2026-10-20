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

package me.ahoo.wow.viewstore.starter.system

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.viewstore.api.ViewKind
import me.ahoo.wow.viewstore.domain.ViewStoreException
import me.ahoo.wow.viewstore.starter.SystemViewProperties
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class SystemViewsTest {
    private fun config(kind: String = "record") = JsonSerializer.createObjectNode().put("kind", kind)

    @Test
    fun `the revision is a hash of the content`() {
        val view = SystemViews.of("open", "orders", "Open", config())
        view.kind.assert().isEqualTo(ViewKind.RECORD)
        view.scope.assert().isEqualTo("system")
        view.revision.assert().isEqualTo(SystemViews.of("open", "orders", "Open", config()).revision)
        view.revision.assert().isNotEqualTo(SystemViews.of("open", "orders", "Opened", config()).revision)
        view.revision.assert().isNotEqualTo(SystemViews.of("open", "orders", "Open", config("analysis")).revision)
    }

    @Test
    fun `refuses ids kept for code, blank ids and invalid views`() {
        assertThrownBy<IllegalArgumentException> { SystemViews.of("system:open", "orders", "Open", config()) }
        assertThrownBy<IllegalArgumentException> { SystemViews.of(" ", "orders", "Open", config()) }
        assertThrownBy<ViewStoreException> { SystemViews.of("open", "orders", "Open", config("chart")) }
        assertThrownBy<ViewStoreException> { SystemViews.of("open", "orders", " ", config()) }
        assertThrownBy<ViewStoreException> { SystemViews.of("open", " ", "Open", config()) }
    }

    private fun property(id: String, tenantId: String = "", appId: String = "", config: String = """{"kind":"record"}""") =
        SystemViewProperties(
            tenantId = tenantId,
            appId = appId,
            id = id,
            definitionId = "orders",
            title = id,
            config = config
        )

    @Test
    fun `serves each configured view to its tenant and application`() {
        val provider = PropertiesSystemViewProvider(
            listOf(property("all"), property("t1-only", tenantId = "t1"), property("console-only", appId = "console"))
        )
        provider.systemViews("t1", "console").map { it.id }.collectList().test()
            .consumeNextWith { it.assert().containsExactly("all", "t1-only", "console-only") }
            .verifyComplete()
        provider.systemViews("t2", "portal").map { it.id }.collectList().test()
            .consumeNextWith { it.assert().containsExactly("all") }
            .verifyComplete()
    }

    @Test
    fun `refuses a config that is not a JSON object and a view configured twice`() {
        assertThrownBy<IllegalArgumentException> { PropertiesSystemViewProvider(listOf(property("x", config = "[1]"))) }
        assertThrownBy<IllegalArgumentException> { PropertiesSystemViewProvider(listOf(property("x", config = "{"))) }
        assertThrownBy<IllegalArgumentException> { PropertiesSystemViewProvider(listOf(property("x"), property("x"))) }
        PropertiesSystemViewProvider(listOf(property("x", tenantId = "t1"), property("x", tenantId = "t2")))
    }
}
