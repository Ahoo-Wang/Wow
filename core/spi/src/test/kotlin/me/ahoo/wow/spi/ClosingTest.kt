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

package me.ahoo.wow.spi

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** What an owner opened closes in reverse order, every one, the first failure thrown with the later ones suppressed. */
class ClosingTest {
    private val closed = mutableListOf<String>()

    private fun closeable(name: String, fails: Boolean = false) = AutoCloseable {
        closed += name
        check(!fails) { "$name fails to close." }
    }

    @Test
    fun `every one should be closed in reverse, the first failure thrown and the later ones suppressed`() {
        val failure = assertThrows<IllegalStateException> {
            Closing.inReverse(listOf(closeable("a", fails = true), closeable("b"), closeable("c", fails = true)))
        }
        closed.assert().containsExactly("c", "b", "a")
        failure.message.assert().isEqualTo("c fails to close.")
        failure.suppressed.map { it.message }.assert().containsExactly("a fails to close.")
    }

    @Test
    fun `nothing failing to close should throw nothing`() {
        Closing.inReverse(listOf(closeable("a"), closeable("b")))
        closed.assert().containsExactly("b", "a")
    }

    @Test
    fun `a failure should close what was opened and be thrown as it was, a failure to close suppressed by it`() {
        val opened = mutableListOf<AutoCloseable>()
        val failure = assertThrows<IllegalArgumentException> {
            Closing.onFailure(opened) {
                opened += closeable("a")
                opened += closeable("b", fails = true)
                throw IllegalArgumentException("the next one failed")
            }
        }
        closed.assert().containsExactly("b", "a")
        failure.message.assert().isEqualTo("the next one failed")
        failure.suppressed.map { it.message }.assert().containsExactly("b fails to close.")
    }

    @Test
    fun `no failure should close nothing`() {
        val opened = listOf(closeable("a"))
        Closing.onFailure(opened) { "done" }.assert().isEqualTo("done")
        closed.assert().isEmpty()
    }
}
