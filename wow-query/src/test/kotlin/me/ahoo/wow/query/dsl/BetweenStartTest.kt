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

package me.ahoo.wow.query.dsl

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class BetweenStartTest {

    @Test
    fun `should store field and start value`() {
        val betweenStart = BetweenStart("field1", 1)
        betweenStart.field.assert().isEqualTo("field1")
        betweenStart.start.assert().isEqualTo(1)
    }

    @Test
    fun `should support string start value`() {
        val betweenStart = BetweenStart("field1", "a")
        betweenStart.start.assert().isEqualTo("a")
    }

    @Test
    fun `should be a data class with equality`() {
        val start1 = BetweenStart("field", 1)
        val start2 = BetweenStart("field", 1)
        start1.assert().isEqualTo(start2)
    }

    @Test
    fun `should copy with new values`() {
        val start = BetweenStart("field1", 1)
        val copied = start.copy(field = "field2", start = 2)
        copied.field.assert().isEqualTo("field2")
        copied.start.assert().isEqualTo(2)
    }
}
