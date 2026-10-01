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

package me.ahoo.wow.viewstore.domain.view

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.viewstore.api.ViewKind
import me.ahoo.wow.viewstore.domain.ViewStoreException
import org.junit.jupiter.api.Test

class ViewConfigsTest {
    @Test
    fun `a config must be an object`() {
        assertThrownBy<ViewStoreException> { ViewConfigs.requireValid(null) }
        assertThrownBy<ViewStoreException> { ViewConfigs.requireValid(JsonSerializer.createArrayNode()) }
    }

    @Test
    fun `a config names one of the three kinds`() {
        ViewKind.entries.forEach { kind ->
            ViewConfigs.requireValid(JsonSerializer.createObjectNode().put("kind", kind.value)).assert().isEqualTo(kind)
        }
        assertThrownBy<ViewStoreException> { ViewConfigs.requireValid(JsonSerializer.createObjectNode()) }
        assertThrownBy<ViewStoreException> {
            ViewConfigs.requireValid(JsonSerializer.createObjectNode().put("kind", 1))
        }
    }

    @Test
    fun `a config of exactly the limit passes`() {
        val config = JsonSerializer.createObjectNode().put("kind", "record")
        val overhead = config.put("n", "").toString().toByteArray().size
        config.put("n", "x".repeat(ViewConfigs.MAX_CONFIG_BYTES - overhead))
        ViewConfigs.requireValid(config).assert().isEqualTo(ViewKind.RECORD)
        config.put("n", "x".repeat(ViewConfigs.MAX_CONFIG_BYTES - overhead + 1))
        assertThrownBy<ViewStoreException> { ViewConfigs.requireValid(config) }
    }

    @Test
    fun `the panel references a store indexes are strings of at most the id length`() {
        fun dashboard(panels: String) = JsonSerializer.readTree("{\"kind\":\"dashboard\",\"panels\":$panels}") as
            tools.jackson.databind.node.ObjectNode
        val id = "x".repeat(ViewConfigs.MAX_ID_LENGTH)
        listOf(
            "null",
            "[]",
            "[{}]",
            "[{\"kind\":\"text\",\"text\":{\"a\":[1,\"b\"]}}]",
            "[{\"instanceId\":\"$id\",\"opens\":null,\"click\":{\"kind\":\"filter\",\"filter\":\"f\"}}]",
            "[{\"click\":{\"kind\":\"view\",\"instanceId\":\"v\"}}]",
        ).forEach { ViewConfigs.requireValid(dashboard(it)).assert().isEqualTo(ViewKind.DASHBOARD) }
        listOf(
            "{}",
            "\"panels\"",
            "[1]",
            "[{\"instanceId\":1}]",
            "[{\"instanceId\":{\"id\":\"v\"}}]",
            "[{\"opens\":[\"v\"]}]",
            "[{\"instanceId\":\"${id}x\"}]",
            "[{\"click\":\"v\"}]",
            "[{\"click\":{\"instanceId\":true}}]",
        ).forEach { panels -> assertThrownBy<ViewStoreException> { ViewConfigs.requireValid(dashboard(panels)) } }
    }

    @Test
    fun `a definition id is at most the id length`() {
        val id = "d".repeat(ViewConfigs.MAX_ID_LENGTH)
        ViewConfigs.requireDefinitionId(id).assert().isEqualTo(id)
        assertThrownBy<ViewStoreException> { ViewConfigs.requireDefinitionId(id + "d") }
    }
}
