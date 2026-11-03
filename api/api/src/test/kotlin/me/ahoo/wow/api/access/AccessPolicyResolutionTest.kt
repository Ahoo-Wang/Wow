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

package me.ahoo.wow.api.access

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** One resolution of a node's access policies, for its reads and its commands alike. */
class AccessPolicyResolutionTest {
    class Note

    private val note = AggregateType("docs", "note")
    private val policy = accessPolicy<Note> { readWrite { allowAll() } }

    @Test
    fun `a policy resolves to the one aggregate of its state the node hosts`() {
        resolveAccessPolicies(listOf(policy), setOf(note), { listOf(note) }, emptyMap())
            .assert().isEqualTo(mapOf(note to policy))
        // A node that hosts every context refuses a policy of no aggregate of it.
        assertThrows<IllegalStateException> {
            resolveAccessPolicies(listOf(policy), emptySet(), { listOf(note) }, emptyMap())
        }.message.assert().contains("is of no aggregate of this node")
    }
}
