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

package me.ahoo.wow.spi.schema

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EnsuredOnceTest {
    @Test
    fun `first uses of one aggregate at once ensure it once, another aggregate apart`() {
        val ensures = AtomicInteger()
        val ensured = EnsuredOnce<String, String>()
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            (1..USES).map {
                executor.submit<String> {
                    ensured.get("order") {
                        ensures.incrementAndGet()
                        Thread.sleep(WAIT_MS)
                        "order's"
                    }
                }
            }.map { it.get(5, TimeUnit.SECONDS) }.toSet().assert().containsExactly("order's")
        }
        ensured.get("cart") {
            ensures.incrementAndGet()
            "cart's"
        }.assert().isEqualTo("cart's")
        ensures.get().assert().isEqualTo(2)
        ensured.values.assert().containsExactlyInAnyOrder("order's", "cart's")
    }

    @Test
    fun `an ensure that fails keeps nothing, and the next use ensures again`() {
        val ensured = EnsuredOnce<String, String>()
        assertThrownBy<IllegalStateException> { ensured.get("order") { throw IllegalStateException("down") } }
        ensured.values.assert().isEmpty()
        ensured.get("order") { "order's" }.assert().isEqualTo("order's")
    }

    private companion object {
        const val USES = 100
        const val WAIT_MS = 50L
    }

    @Test
    fun `a structure forgotten is ensured again on its next use, and another value of its key is not forgotten`() {
        val ensured = EnsuredOnce<String, String>()
        var ensures = 0
        ensured.get("a") { "a${++ensures}" }.assert().isEqualTo("a1")
        ensured.forget("a", "other")
        ensured.get("a") { "a${++ensures}" }.assert().isEqualTo("a1")
        ensured.forget("a", "a1")
        ensured.get("a") { "a${++ensures}" }.assert().isEqualTo("a2")
        ensured.values.assert().containsExactly("a2")
    }
}
