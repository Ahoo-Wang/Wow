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

package me.ahoo.wow.viewstore.domain

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class ScopedIdsTest {
    @Test
    fun `the same parts should give the same id, and other parts another`() {
        val id = ScopedIds.preferences("alice", "app", "orders")
        id.assert().hasSize(32).isEqualTo(ScopedIds.preferences("alice", "app", "orders"))
        listOf(
            ScopedIds.preferences("bob", "app", "orders"),
            ScopedIds.preferences("alice", "other", "orders"),
            ScopedIds.preferences("alice", "app", "items"),
            ScopedIds.preferences(null, "app", "orders"),
            ScopedIds.wording("app", "orders"),
        ).forEach { it.assert().isNotEqualTo(id) }
        ScopedIds.preferences(null, "app", "orders").assert().isEqualTo(ScopedIds.preferences("", "app", "orders"))
        ScopedIds.wording("app", "orders").assert().isEqualTo(ScopedIds.wording("app", "orders"))
            .isNotEqualTo(ScopedIds.wording("app", "items"))
    }
}
