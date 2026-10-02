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

package me.ahoo.wow.viewstore.api

import me.ahoo.test.asserts.assert
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.api.preferences.SetViewPreferences
import me.ahoo.wow.viewstore.api.preferences.ViewPreferencesInput
import me.ahoo.wow.viewstore.api.view.ViewAudienceChanged
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

class ViewStoreApiTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `kinds and audiences are written as the engine names them`() {
        mapper.writeValueAsString(ViewKind.DASHBOARD).assert().isEqualTo("\"dashboard\"")
        mapper.readValue<ViewAudienceChanged>("""{"audience":"shared","toOwnerId":"(shared)"}""").audience
            .assert().isEqualTo(ViewAudience.SHARED)
        ViewKind.of("analysis").assert().isEqualTo(ViewKind.ANALYSIS)
        ViewKind.of("chart").assert().isNull()
    }

    @Test
    fun `the shared owner is shared, the system owner system and any other owner personal`() {
        ViewAudience.ofOwner(ViewStoreService.SHARED_OWNER_ID).assert().isEqualTo(ViewAudience.SHARED)
        ViewAudience.ofOwner(ViewStoreService.SYSTEM_OWNER_ID).assert().isEqualTo(ViewAudience.SYSTEM)
        ViewAudience.ofOwner("(SYSTEM)").assert().isEqualTo(ViewAudience.PERSONAL)
        ViewAudience.ofOwner("alice").assert().isEqualTo(ViewAudience.PERSONAL)
        ViewStoreService.SYSTEM_TENANT_ID.assert().isEqualTo("(platform)")
        mapper.writeValueAsString(ViewAudience.SYSTEM).assert().isEqualTo("\"system\"")
    }

    @Test
    fun `the preferences body becomes the command of the path's definition`() {
        ViewPreferencesInput(order = listOf("a"), defaultInstanceId = "a", autoRun = true, lastTabs = mapOf("b" to "t"))
            .toCommand("orders").assert()
            .isEqualTo(SetViewPreferences("orders", listOf("a"), "a", true, mapOf("b" to "t")))
    }

    @Test
    fun `a system view is always of the system scope`() {
        SystemView("id", "orders", "Open", ViewKind.RECORD, "r", mapper.createObjectNode()).scope
            .assert().isEqualTo(SystemView.SYSTEM_SCOPE)
    }

    @Test
    fun `a system view says where it comes from, and a stored one its version`() {
        val configured = SystemView("id", "orders", "Open", ViewKind.RECORD, "r", mapper.createObjectNode())
        mapper.valueToTree<tools.jackson.databind.JsonNode>(configured).let {
            it["source"].asString().assert().isEqualTo("configured")
            it["version"].isNull.assert().isTrue()
        }
        val stored = configured.copy(source = SystemViewSource.STORED, version = 3)
        mapper.valueToTree<tools.jackson.databind.JsonNode>(stored).let {
            it["source"].asString().assert().isEqualTo("stored")
            it["version"].asInt().assert().isEqualTo(3)
        }
    }
}
