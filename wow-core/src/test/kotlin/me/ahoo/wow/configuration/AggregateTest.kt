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

package me.ahoo.wow.configuration

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

internal class AggregateTest {
    @Test
    fun merge() {
        val current = Aggregate(linkedSetOf("1", "2"), null)
        val other = Aggregate(linkedSetOf("1", "3"), "")
        val merged = current.merge(other)
        merged.scopes.assert().contains("1", "2", "3")
        merged.type.assert().isBlank()
    }

    @Test
    fun mergeEmpty() {
        val current = Aggregate(linkedSetOf(), "")
        val other = Aggregate(linkedSetOf(), "")
        current.merge(other)
    }

    @Test
    fun mergeEmptyNull() {
        val current = Aggregate(linkedSetOf(), "")
        val other = Aggregate(linkedSetOf(), null)
        current.merge(other)
    }

    @Test
    fun mergeIfConflict() {
        assertThrownBy<IllegalStateException> {
            Aggregate(type = "Conflict").merge(Aggregate(type = "other"))
        }
    }
}
