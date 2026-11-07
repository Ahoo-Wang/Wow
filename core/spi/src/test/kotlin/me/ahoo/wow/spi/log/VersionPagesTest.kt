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

package me.ahoo.wow.spi.log

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** A read by version a page at a time ([VersionPages]): which pages it asks for, and when it stops. */
class VersionPagesTest {
    /** The versions 1..[last] held, and the first version of each page asked for. */
    private class Pages(private val last: Int) {
        val asked = mutableListOf<Int>()

        fun read(from: Int, to: Int = Int.MAX_VALUE) = VersionPages.byVersion(from, to, { it }) { start ->
            asked += start
            (start..minOf(to, last)).take(VersionPages.SIZE)
        }
    }

    @Test
    fun `a long read asks a page at a time from the version after the last, and stops at a short page`(): Unit =
        runBlocking {
            val size = VersionPages.SIZE
            val pages = Pages(2 * size + 3)
            pages.read(1).toList().assert().isEqualTo((1..2 * size + 3).toList())
            pages.asked.assert().containsExactly(1, size + 1, 2 * size + 1)
        }

    @Test
    fun `a page that reaches the end of the range is the last, a full one short of it is not`(): Unit = runBlocking {
        val size = VersionPages.SIZE
        val bounded = Pages(3 * size)
        bounded.read(1, size).toList().size.assert().isEqualTo(size)
        bounded.asked.assert().containsExactly(1)
        // A history of exactly a page reads one empty page to know it ended.
        val exact = Pages(size)
        exact.read(1).toList().size.assert().isEqualTo(size)
        exact.asked.assert().containsExactly(1, size + 1)
    }

    @Test
    fun `an empty range reads nothing, and an empty page ends the read`(): Unit = runBlocking {
        val pages = Pages(10)
        pages.read(5, 4).toList().assert().isEmpty()
        pages.asked.assert().isEmpty()
        pages.read(11).toList().assert().isEmpty()
        pages.asked.assert().containsExactly(11)
    }

    @Test
    fun `the next page is read only once the previous one is taken`(): Unit = runBlocking {
        val pages = Pages(2 * VersionPages.SIZE)
        pages.read(1).first().assert().isEqualTo(1)
        pages.asked.assert().containsExactly(1)
    }
}
