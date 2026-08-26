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

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AbacTagsValidationTest {
    @Test
    fun `default resource command and event should reject blank keys`() {
        assertThrows<IllegalArgumentException> { DefaultApplyResourceTags(mapOf(" " to listOf("eng"))) }
        assertThrows<IllegalArgumentException> { DefaultResourceTagsApplied(mapOf(" " to listOf("eng"))) }
    }

    @Test
    fun `default resource command and event should reject oversized values`() {
        val oversized = "x".repeat(ABAC_TAG_VALUE_MAX_LENGTH + 1)

        assertThrows<IllegalArgumentException> { DefaultApplyResourceTags(mapOf("department" to listOf(oversized))) }
        assertThrows<IllegalArgumentException> { DefaultResourceTagsApplied(mapOf("department" to listOf(oversized))) }
    }

    @Test
    fun `resource tags at the protocol limit should remain valid`() {
        val limitValue = "x".repeat(ABAC_TAG_VALUE_MAX_LENGTH)

        DefaultApplyResourceTags(mapOf("department" to listOf(limitValue))).validate()
        DefaultResourceTagsApplied(mapOf("department" to listOf(limitValue)))
    }
}
