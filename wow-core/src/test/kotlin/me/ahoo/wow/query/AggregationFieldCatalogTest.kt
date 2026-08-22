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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class AggregationFieldCatalogTest {
    @Test
    fun `should scan deep scalar fields and object collections independently`() {
        val catalog = AggregationFieldCatalog.scan(State::class.java)

        catalog.scalarPaths.assert().contains("state.level1.level2.level3.level4.level5.level6.value")
        catalog.elementPaths.assert().contains("state.orders", "state.orders.lines")
        catalog.elementPaths.assert().doesNotContain("state.tags")
        catalog.paths.keys.assert().doesNotContain("state.attributes.value")
        catalog.paths["state.orders.lines.sku"]!!.collectionPaths.assert()
            .containsExactly("state.orders", "state.orders.lines")
    }

    private class State(val id: String) {
        val level1 = Level1()
        val orders: List<Order> = emptyList()
        val tags: List<String> = emptyList()
        val attributes: Map<String, String> = emptyMap()
    }

    private class Level1 { val level2 = Level2() }
    private class Level2 { val level3 = Level3() }
    private class Level3 { val level4 = Level4() }
    private class Level4 { val level5 = Level5() }
    private class Level5 { val level6 = Level6() }
    private class Level6 { val value: String = "" }
    private data class Order(val lines: List<Line>)
    private data class Line(val sku: String)
}
