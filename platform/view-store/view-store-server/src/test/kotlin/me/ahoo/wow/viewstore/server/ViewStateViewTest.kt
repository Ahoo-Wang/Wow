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
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.viewstore.api.ViewAudience
import me.ahoo.wow.viewstore.api.ViewKind
import me.ahoo.wow.viewstore.api.view.ViewCreated
import me.ahoo.wow.viewstore.api.view.ViewStateView
import me.ahoo.wow.viewstore.domain.view.ViewState
import org.junit.jupiter.api.Test

/** The view another context reads a saved view as: the api's generated `ViewStateView`, its free config a tree. */
class ViewStateViewTest {
    @Test
    fun `a stored state should decode into the generated view, its free config whole`() {
        val config = mapOf(
            "kind" to "record",
            "columns" to listOf(mapOf("field" to "state.title", "width" to 120)),
            "filter" to mapOf("and" to listOf(mapOf("field" to "state.kind", "eq" to "record"), null)),
        )
        val state = ViewState().apply {
            onSourcing(ViewCreated("orders", "Open orders", ViewKind.RECORD, ViewAudience.SHARED, "app", config))
        }
        val stored = WowJson.mapper.valueToTree<tools.jackson.databind.node.ObjectNode>(state)
            .put("addedLater", "a field the view does not know")
        val view = WowJson.mapper.treeToValue(stored, ViewStateView::class.java)
        view.title.assert().isEqualTo("Open orders")
        view.kind.assert().isEqualTo(ViewKind.RECORD)
        view.audience.assert().isEqualTo(ViewAudience.SHARED)
        view.config["columns"][0]["width"].asInt().assert().isEqualTo(120)
        view.config["filter"]["and"][1].isNull.assert().isTrue()
        WowJson.mapper.convertValue(view.config, Map::class.java).assert().isEqualTo(config)
    }
}
