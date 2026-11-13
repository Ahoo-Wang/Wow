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

class HeldStoreCallMarksTest {
    @Test
    fun `a clocked section should hold its marks while it runs and none after`() {
        val marks = StoreCallMarks()
        section(marks) { HeldStoreCallMarks.held() }.assert().isSameAs(marks)
        HeldStoreCallMarks.held().assert().isNull()
    }

    @Test
    fun `a section without marks should hold none`() {
        section(null) { HeldStoreCallMarks.held() }.assert().isNull()
        HeldStoreCallMarks.held().assert().isNull()
    }

    @Test
    fun `a section that throws should hold none after it`() {
        runCatching { section(StoreCallMarks()) { error("The store is down.") } }
            .exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
        HeldStoreCallMarks.held().assert().isNull()
    }

    /** A store's blocking section, as the stores run it: [marks] held from its start, released in its `finally`. */
    private fun <T> section(marks: StoreCallMarks?, block: () -> T): T {
        HeldStoreCallMarks.hold(marks)
        try {
            return block()
        } finally {
            HeldStoreCallMarks.release(marks)
        }
    }
}
