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

package me.ahoo.wow.benchmark.runtime

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class FunctionCacheVariantsTest {

    @Test
    fun `single entry cache keeps the first successful resolution without promotion`() {
        val cache = SingleEntryFunctionCache<String>()
        var resolveCount = 0

        val first = cache.get(String::class.java) {
            resolveCount++
            "first"
        }
        val cached = cache.get(String::class.java) {
            resolveCount++
            "replacement"
        }

        first.assert().isEqualTo("first")
        cached.assert().isSameAs(first)
        resolveCount.assert().isEqualTo(1)
        cache.cachedFunctionCount.assert().isEqualTo(1)
        cache.promoted.assert().isFalse()
    }

    @Test
    fun `single entry cache promotes only after a second distinct success`() {
        val cache = SingleEntryFunctionCache<String>()

        val first = cache.get(String::class.java) { "first" }
        cache.get(Long::class.java) { null }.assert().isNull()
        cache.cachedFunctionCount.assert().isEqualTo(1)
        cache.promoted.assert().isFalse()

        val second = cache.get(Int::class.java) { "second" }

        cache.get(String::class.java) { "replacement" }.assert().isSameAs(first)
        cache.get(Int::class.java) { "replacement" }.assert().isSameAs(second)
        val third = cache.get(Long::class.java) { "third" }
        cache.get(Long::class.java) { "replacement" }.assert().isSameAs(third)
        cache.cachedFunctionCount.assert().isEqualTo(3)
        cache.promoted.assert().isTrue()
    }

    @Test
    fun `single entry cache does not cache null or resolver failure`() {
        val cache = SingleEntryFunctionCache<String>()
        var resolveCount = 0

        cache.get(String::class.java) {
            resolveCount++
            null
        }.assert().isNull()
        assertThrownBy<IllegalStateException> {
            cache.get(String::class.java) {
                resolveCount++
                throw IllegalStateException("failed")
            }
        }
        val resolved = cache.get(String::class.java) {
            resolveCount++
            "resolved"
        }

        resolved.assert().isEqualTo("resolved")
        resolveCount.assert().isEqualTo(3)
        cache.cachedFunctionCount.assert().isEqualTo(1)
    }

    @Test
    fun `map first cache preserves the production baseline semantics`() {
        val cache = MapFirstFunctionCache<String>()
        var resolveCount = 0

        val first = cache.get(String::class.java) {
            resolveCount++
            "first"
        }
        cache.get(String::class.java) {
            resolveCount++
            "replacement"
        }.assert().isSameAs(first)
        cache.get(Int::class.java) {
            resolveCount++
            null
        }.assert().isNull()
        cache.get(Int::class.java) {
            resolveCount++
            "second"
        }.assert().isEqualTo("second")

        resolveCount.assert().isEqualTo(3)
        cache.cachedFunctionCount.assert().isEqualTo(2)
    }
}
