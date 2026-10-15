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
import me.ahoo.wow.viewstore.api.ViewKind
import me.ahoo.wow.viewstore.domain.ViewFixtures
import org.junit.jupiter.api.Test

class ViewConfigsTest {
    private fun problem(config: Map<String, Any?>): String? = ViewConfigs.problemOf(config)

    @Test
    fun `a config should name a kind`() {
        problem(ViewFixtures.recordConfig()).assert().isNull()
        problem(mapOf("kind" to "chart")).assert().contains("kind")
        problem(mapOf("columns" to emptyList<String>())).assert().contains("kind")
        ViewConfigs.kindOf(mapOf("kind" to "analysis")).assert().isEqualTo(ViewKind.ANALYSIS)
        ViewKind.of(null).assert().isNull()
    }

    @Test
    fun `a config should be no larger than the limit`() {
        val large = mapOf("kind" to "record", "note" to "x".repeat(ViewConfigs.MAX_CONFIG_BYTES))
        problem(large).assert().contains("bytes")
    }

    @Test
    fun `a dashboard's panels should hold what a store can index`() {
        problem(ViewFixtures.dashboardConfig("v1", "v2")).assert().isNull()
        problem(mapOf("kind" to "dashboard", "panels" to null)).assert().isNull()
        problem(mapOf("kind" to "dashboard", "panels" to "v1")).assert().contains("array")
        problem(mapOf("kind" to "dashboard", "panels" to List(ViewConfigs.MAX_PANELS + 1) { emptyMap<String, Any>() }))
            .assert().contains("limit")
        problem(mapOf("kind" to "dashboard", "panels" to listOf("v1"))).assert().contains("object")
        problem(mapOf("kind" to "dashboard", "panels" to listOf(mapOf("instanceId" to 1)))).assert().contains("string")
        problem(mapOf("kind" to "dashboard", "panels" to listOf(mapOf("opens" to "x".repeat(257))))).assert()
            .contains("opens")
        problem(mapOf("kind" to "dashboard", "panels" to listOf(mapOf("click" to "v1")))).assert().contains("click")
        problem(mapOf("kind" to "dashboard", "panels" to listOf(mapOf("click" to mapOf("instanceId" to null)))))
            .assert().isNull()
    }

    @Test
    fun `the views a dashboard's panels reference should be found at every reference`() {
        val config = mapOf(
            "kind" to "dashboard",
            "panels" to listOf(
                mapOf("instanceId" to "v1", "opens" to "v2"),
                mapOf("click" to mapOf("instanceId" to "v3")),
                "not a panel",
            ),
        )
        ViewConfigs.referencedViews(config).assert().containsExactlyInAnyOrder("v1", "v2", "v3")
        ViewConfigs.referencedViews(ViewFixtures.recordConfig()).assert().isEmpty()
    }

    @Test
    fun `a title and a definition should be neither blank nor too long`() {
        ViewConfigs.titleProblem(" Orders ").assert().isNull()
        ViewConfigs.titleProblem("  ").assert().contains("blank")
        ViewConfigs.titleProblem("x".repeat(ViewConfigs.MAX_TITLE_LENGTH + 1)).assert().contains("longer")
        ViewConfigs.definitionProblem("orders").assert().isNull()
        ViewConfigs.definitionProblem(" ").assert().contains("blank")
        ViewConfigs.definitionProblem("x".repeat(ViewConfigs.MAX_ID_LENGTH + 1)).assert().contains("longer")
    }
}
