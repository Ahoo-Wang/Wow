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
        val id = ScopedIds.preferences("t", "alice", "app", "orders")
        id.assert().hasSize(32).isEqualTo(ScopedIds.preferences("t", "alice", "app", "orders"))
        listOf(
            ScopedIds.preferences("u", "alice", "app", "orders"),
            ScopedIds.preferences("t", "bob", "app", "orders"),
            ScopedIds.preferences("t", "alice", "other", "orders"),
            ScopedIds.preferences("t", "alice", "app", "items"),
            ScopedIds.preferences("t", null, "app", "orders"),
        ).forEach { it.assert().isNotEqualTo(id) }
        ScopedIds.preferences("t", null, "app", "orders").assert()
            .isEqualTo(ScopedIds.preferences("t", "", "app", "orders"))
        ScopedIds.wording("t", "app", "orders").assert().isEqualTo(ScopedIds.wording("t", "app", "orders"))
            .isNotEqualTo(ScopedIds.wording("t", "app", "items"))
            .isNotEqualTo(ScopedIds.wording("u", "app", "orders"))
    }

    @Test
    fun `the ids should be the 9x view store's, so the imported preferences and wordings are found (D267)`() {
        // What the 9.x example-server's view store (v10-9x-baseline) gave these parts, read from its store.
        ScopedIds.preferences("t0", "u-1", "rehearsal-app", "def-1").assert()
            .isEqualTo("ff3ab17b6f8527a944771272a9479c5d")
        ScopedIds.wording("t0", "rehearsal-app", "orders").assert().isEqualTo("d73a9e3ca379bdf9d35a76f34194a2ca")
    }
}
