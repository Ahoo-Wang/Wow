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

package me.ahoo.wow.modeling.command

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class FunctionCacheTest {

    @Test
    fun `resolved function is cached by command type`() {
        val cache = FunctionCache<String>()
        var resolveCount = 0

        val first = cache.get(String::class.java) {
            resolveCount++
            "resolved"
        }
        val second = cache.get(String::class.java) {
            resolveCount++
            "replacement"
        }

        first.assert().isEqualTo("resolved")
        second.assert().isEqualTo("resolved")
        resolveCount.assert().isEqualTo(1)
    }

    @Test
    fun `missing function is not cached`() {
        val cache = FunctionCache<String>()
        var resolveCount = 0

        cache.get(String::class.java) {
            resolveCount++
            null
        }.assert().isNull()
        cache.get(String::class.java) {
            resolveCount++
            null
        }.assert().isNull()

        resolveCount.assert().isEqualTo(2)
        cache.cache().assert().isNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun FunctionCache<*>.cache(): MutableMap<Class<*>, Any>? {
        val field = FunctionCache::class.java.getDeclaredField("cache")
        field.isAccessible = true
        return field.get(this) as MutableMap<Class<*>, Any>?
    }
}
