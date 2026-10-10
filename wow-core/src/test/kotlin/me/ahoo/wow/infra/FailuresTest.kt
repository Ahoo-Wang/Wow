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

package me.ahoo.wow.infra

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class FailuresTest {

    @Test
    fun `merge keeps the first failure and suppresses each later one once`() {
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")

        (null as Throwable?).mergeFailure(first).assert().isSameAs(first)
        first.mergeFailure(second).assert().isSameAs(first)
        first.mergeFailure(second).assert().isSameAs(first)
        first.mergeFailure(first).assert().isSameAs(first)

        first.suppressed.assert().containsExactly(second)
    }

    @Test
    fun `forEachThenThrow runs every action and throws the first failure`() {
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        val visited = mutableListOf<Int>()

        assertThrownBy<IllegalStateException> {
            listOf(1, 2, 3).forEachThenThrow {
                visited.add(it)
                when (it) {
                    1 -> throw first
                    3 -> throw second
                }
            }
        }.isSameAs(first)

        visited.assert().containsExactly(1, 2, 3)
        first.suppressed.assert().containsExactly(second)
    }

    @Test
    fun `forEachThenThrow completes when every action succeeds`() {
        val visited = mutableListOf<Int>()

        listOf(1, 2).forEachThenThrow { visited.add(it) }

        visited.assert().containsExactly(1, 2)
    }

    @Test
    fun `forEachThenThrow propagates a fatal error at once`() {
        val visited = mutableListOf<Int>()

        assertThrownBy<OutOfMemoryError> {
            listOf(1, 2).forEachThenThrow {
                visited.add(it)
                throw OutOfMemoryError("fatal")
            }
        }

        visited.assert().containsExactly(1)
    }
}
