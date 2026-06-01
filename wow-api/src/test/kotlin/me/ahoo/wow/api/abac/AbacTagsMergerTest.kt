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

package me.ahoo.wow.api.abac

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class AbacTagsMergerTest {

    @Test
    fun `should return this when other is empty`() {
        val tags: AbacTags = mapOf("dept" to listOf("eng"), "role" to listOf("admin"))
        val result = tags.merge(emptyMap())
        result.assert().isSameAs(tags)
    }

    @Test
    fun `should return other when this is empty`() {
        val other: AbacTags = mapOf("dept" to listOf("eng"), "role" to listOf("admin"))
        val result = emptyMap<String, List<String>>().merge(other)
        result.assert().isSameAs(other)
    }

    @Test
    fun `should merge overlapping keys and deduplicate values`() {
        val tags1: AbacTags = mapOf("dept" to listOf("eng", "qa"), "role" to listOf("admin"))
        val tags2: AbacTags = mapOf("dept" to listOf("eng", "pm"), "role" to listOf("user"))
        val result = tags1.merge(tags2)

        result.assert().hasSize(2)
        result["dept"].assert().containsExactlyInAnyOrder("eng", "qa", "pm")
        result["role"].assert().containsExactlyInAnyOrder("admin", "user")
    }

    @Test
    fun `should merge non-overlapping keys`() {
        val tags1: AbacTags = mapOf("dept" to listOf("eng"))
        val tags2: AbacTags = mapOf("team" to listOf("backend"))
        val result = tags1.merge(tags2)

        result.assert().hasSize(2)
        result["dept"].assert().containsExactly("eng")
        result["team"].assert().containsExactly("backend")
    }

    @Test
    fun `should handle both empty maps`() {
        val result = emptyMap<String, List<String>>().merge(emptyMap())
        result.assert().isEmpty()
    }
}
