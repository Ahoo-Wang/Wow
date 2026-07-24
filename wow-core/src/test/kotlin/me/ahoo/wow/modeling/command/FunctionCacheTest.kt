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
import me.ahoo.test.asserts.assertThrownBy
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
        cache.singleEntry().assert().isNotNull()
        cache.cache().assert().isNull()
    }

    @Test
    fun `second distinct success promotes the single entry to a map`() {
        val cache = FunctionCache<String>()

        val first = cache.get(String::class.java) { "first" }
        val second = cache.get(Int::class.java) { "second" }
        val third = cache.get(Long::class.java) { "third" }

        cache.get(String::class.java) { "replacement" }.assert().isSameAs(first)
        cache.get(Int::class.java) { "replacement" }.assert().isSameAs(second)
        cache.get(Long::class.java) { "replacement" }.assert().isSameAs(third)
        cache.singleEntry().assert().isNull()
        cache.cache().assert().hasSize(3)
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
        cache.singleEntry().assert().isNull()
        cache.cache().assert().isNull()
    }

    @Test
    fun `unsuccessful second resolution keeps the single entry`() {
        val cache = FunctionCache<String>()
        val first = cache.get(String::class.java) { "first" }

        cache.get(Int::class.java) { null }.assert().isNull()
        assertThrownBy<IllegalStateException> {
            cache.get(Int::class.java) {
                throw IllegalStateException("failed")
            }
        }

        cache.get(String::class.java) { "replacement" }.assert().isSameAs(first)
        cache.singleEntry().assert().isNotNull()
        cache.cache().assert().isNull()
    }

    @Test
    fun `resolver failure is not cached`() {
        val cache = FunctionCache<String>()
        var resolveCount = 0

        assertThrownBy<IllegalStateException> {
            cache.get(String::class.java) {
                resolveCount++
                throw IllegalStateException("failed")
            }
        }
        cache.get(String::class.java) {
            resolveCount++
            "resolved"
        }.assert().isEqualTo("resolved")

        resolveCount.assert().isEqualTo(2)
        cache.singleEntry().assert().isNotNull()
        cache.cache().assert().isNull()
    }

    private fun FunctionCache<*>.singleEntry(): Any? {
        val field = FunctionCache::class.java.getDeclaredField("singleEntry")
        field.isAccessible = true
        return field.get(this)
    }

    @Suppress("UNCHECKED_CAST")
    private fun FunctionCache<*>.cache(): MutableMap<Class<*>, Any>? {
        val field = FunctionCache::class.java.getDeclaredField("cache")
        field.isAccessible = true
        return field.get(this) as MutableMap<Class<*>, Any>?
    }
}
