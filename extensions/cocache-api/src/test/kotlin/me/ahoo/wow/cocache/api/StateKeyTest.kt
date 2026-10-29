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

package me.ahoo.wow.cocache.api

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class StateKeyTest {
    @Test
    fun `a key should be stored as its tenant and id`() {
        StateKey("t", "o-1").toString().assert().isEqualTo("t/o-1")
    }

    @Test
    fun `keys of one tenant and id should be equal`() {
        StateKey("t", "o-1").assert().isEqualTo(StateKey("t", "o-1")).isNotEqualTo(StateKey("t", "o-2"))
        StateKey("t", "o-1").hashCode().assert().isEqualTo(StateKey("t", "o-1").hashCode())
        StateKey("t", "o-1").assert().isNotEqualTo(StateKey("u", "o-1"))
    }

    @Test
    fun `a key should need a tenant and an id`() {
        assertThrownBy<IllegalArgumentException> { StateKey(" ", "o-1") }
        assertThrownBy<IllegalArgumentException> { StateKey("t", "") }
    }

    @Test
    fun `a get by tenant and id should read the key`() {
        val cache = object : StateViewCache<String> {
            override val name: String = "c"

            override suspend fun get(key: StateKey): String = key.toString()

            override suspend fun getAll(keys: Collection<StateKey>): Map<StateKey, String> = emptyMap()
        }
        kotlinx.coroutines.runBlocking { cache.get("t", "o-1") }.assert().isEqualTo("t/o-1")
    }
}
