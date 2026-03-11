/*
 * Copyright 2021-2025 [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
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

package me.ahoo.wow.query.snapshot.filter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.abac.AbacTagKey
import me.ahoo.wow.api.abac.AbacTagValue
import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.api.abac.wildcard
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.query.snapshot.filter.AbacQueryFilter.Companion.toCondition
import org.junit.jupiter.api.Test

class AbacQueryFilterTest {
    @Test
    fun `toCondition for wildcard should return condition with EXISTS operator`() {
        val entry: Map.Entry<AbacTagKey, AbacTagValue> = mapOf("dept" to listOf("*")).entries.first()
        val condition = entry.toCondition()

        condition.assert().isNotNull()
        condition.operator.assert().isEqualTo(Operator.EXISTS)
    }

    @Test
    fun `toCondition for non-wildcard should return condition with OR operator`() {
        val entry: Map.Entry<AbacTagKey, AbacTagValue> = mapOf("dept" to listOf("eng", "pm")).entries.first()
        val condition = entry.toCondition()

        condition.assert().isNotNull()
        condition.operator.assert().isEqualTo(Operator.OR)
    }

    @Test
    fun `toCondition for empty tags should return condition with OR operator`() {
        val entry: Map.Entry<AbacTagKey, AbacTagValue> = mapOf("dept" to emptyList<String>()).entries.first()
        val condition = entry.toCondition()

        condition.assert().isNotNull()
        condition.operator.assert().isEqualTo(Operator.OR)
    }

    @Test
    fun `AbacTags toCondition should return condition with AND operator`() {
        val tags: AbacTags =
            mapOf(
                "dept" to listOf("eng"),
                "role" to listOf("admin"),
            )
        val condition = tags.toCondition()

        condition.assert().isNotNull()
        condition.operator.assert().isEqualTo(Operator.AND)
    }

    @Test
    fun `wildcard extension property should return true for wildcard value`() {
        val wildcardValue: AbacTagValue = listOf("*")
        wildcardValue.wildcard.assert().isTrue()
    }

    @Test
    fun `wildcard extension property should return false for non-wildcard value`() {
        val nonWildcardValue: AbacTagValue = listOf("eng", "pm")
        nonWildcardValue.wildcard.assert().isFalse()
    }

    @Test
    fun `wildcard extension property should return false for empty list`() {
        val emptyValue: AbacTagValue = emptyList()
        emptyValue.wildcard.assert().isFalse()
    }

    @Test
    fun `toCondition for single wildcard tag should work correctly`() {
        val entry: Map.Entry<AbacTagKey, AbacTagValue> = mapOf("role" to listOf("*")).entries.first()
        val condition = entry.toCondition()

        condition.assert().isNotNull()
        condition.operator.assert().isEqualTo(Operator.EXISTS)
    }

    @Test
    fun `toCondition for multiple non-wildcard values should work correctly`() {
        val entry: Map.Entry<AbacTagKey, AbacTagValue> = mapOf("dept" to listOf("eng", "pm", "qa")).entries.first()
        val condition = entry.toCondition()

        condition.assert().isNotNull()
        condition.operator.assert().isEqualTo(Operator.OR)
    }
}
