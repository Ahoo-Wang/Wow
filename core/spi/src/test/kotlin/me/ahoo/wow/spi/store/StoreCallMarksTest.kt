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
package me.ahoo.wow.spi.store

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class StoreCallMarksTest {
    @Test
    fun `the driver's command time should count only between the first command sent and the last result read`() {
        val marks = StoreCallMarks()
        // A lookup while the store builds its batch: before sent, not the call's command.
        marks.commanded(5)
        marks.sent()
        marks.commanded(7)
        marks.commanded(11)
        marks.read()
        marks.commanded(13)
        marks.command.assert().isEqualTo(18L)
        marks.commands.assert().isEqualTo(2)
    }

    @Test
    fun `the marks should keep the first connection and statement and the last result, marked once all are`() {
        val marks = StoreCallMarks()
        marks.taken.assert().isPositive()
        marks.marked.assert().isFalse()
        marks.entered()
        marks.onThread()
        marks.acquired()
        val acquired = marks.acquired
        marks.sent()
        val sent = marks.sent
        marks.read()
        val read = marks.read
        marks.marked.assert().isFalse()
        // A definite abort's appends again: a second connection's statements.
        marks.acquired()
        marks.sent()
        marks.read()
        marks.released()
        marks.marked.assert().isTrue()

        marks.acquired.assert().isEqualTo(acquired)
        marks.sent.assert().isEqualTo(sent)
        marks.read.assert().isGreaterThanOrEqualTo(read)
        listOf(marks.taken, marks.entered, marks.onThread, marks.acquired, marks.sent, marks.read, marks.released)
            .zipWithNext().forEach { (earlier, later) -> later.assert().isGreaterThanOrEqualTo(earlier) }
        marks.returned.assert().isZero()
        marks.done.assert().isZero()
        marks.returned()
        marks.done()
        marks.done.assert().isGreaterThanOrEqualTo(marks.returned)
        marks.returned.assert().isGreaterThanOrEqualTo(marks.released)
    }
}
