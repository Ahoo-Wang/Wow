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

package me.ahoo.wow.runtime.node

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The stops of a node's contexts: each runs to its end, and the first failure is thrown, the others suppressed. */
class ContextStopsTest {
    private val ended = CopyOnWriteArrayList<String>()

    /** A stop named [name] that ends after a suspension, or fails with [failure] at once. */
    private fun stop(name: String, failure: Exception? = null): suspend () -> Unit = {
        failure?.let { throw it }
        delay(10)
        ended += name
    }

    @Test
    fun `stops at once should each run to its end, the first failure thrown and the others suppressed in it`(): Unit =
        runBlocking {
            val first = IllegalStateException("a")
            val third = IllegalStateException("c")

            val thrown = runCatching {
                stopEach(listOf(stop("a", first), stop("b"), stop("c", third), stop("d")))
            }.exceptionOrNull()

            thrown.assert().isSameAs(first)
            first.suppressed.toList().assert().containsExactly(third)
            ended.assert().containsExactlyInAnyOrder("b", "d")
        }

    @Test
    fun `stops in turn should each run whatever the ones before did, the first failure thrown`(): Unit = runBlocking {
        val first = IllegalStateException("a")
        val second = IllegalStateException("b")

        val thrown = runCatching { stopInTurn(stop("a", first), stop("b", second), stop("c")) }.exceptionOrNull()

        thrown.assert().isSameAs(first)
        first.suppressed.toList().assert().containsExactly(second)
        ended.assert().containsExactly("c")
    }

    @Test
    fun `a cancellation should be thrown as it is, ending the stops in turn`(): Unit = runBlocking {
        val cancelled = CancellationException("cancelled")

        val thrown = runCatching { stopInTurn(stop("a", cancelled), stop("b")) }.exceptionOrNull()

        thrown.assert().isSameAs(cancelled)
        ended.assert().isEmpty()
    }

    @Test
    fun `stops that all end should throw nothing`(): Unit = runBlocking {
        stopEach(listOf(stop("a"), stop("b")))
        stopInTurn(stop("c"))

        ended.assert().containsExactlyInAnyOrder("a", "b", "c")
    }
}
