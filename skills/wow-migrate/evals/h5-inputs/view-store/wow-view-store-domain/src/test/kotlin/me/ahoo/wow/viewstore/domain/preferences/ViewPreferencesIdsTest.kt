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

package me.ahoo.wow.viewstore.domain.preferences

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class ViewPreferencesIdsTest {
    @Test
    fun `the same owner, application and definition give the same id`() {
        ViewPreferencesIds.of("t", "alice", "console", "orders")
            .assert().isEqualTo(ViewPreferencesIds.of("t", "alice", "console", "orders"))
            .hasSize(32)
    }

    @Test
    fun `any other tenant, owner, application or definition gives another id`() {
        val ids = setOf(
            ViewPreferencesIds.of("t", "alice", "console", "orders"),
            ViewPreferencesIds.of("u", "alice", "console", "orders"),
            ViewPreferencesIds.of("t", "bob", "console", "orders"),
            ViewPreferencesIds.of("t", "alice", "portal", "orders"),
            ViewPreferencesIds.of("t", "alice", "console", "customers"),
            // The separator keeps the parts apart: "ab" + "c" is not "a" + "bc".
            ViewPreferencesIds.of("t", "alicec", "onsole", "orders"),
        )
        ids.assert().hasSize(6)
    }
}
