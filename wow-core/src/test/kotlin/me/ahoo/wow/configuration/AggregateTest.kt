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

import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class AggregateTest {
    @Test
    fun merge() {
        val current = Aggregate(setOf("1", "2"), null)
        val other = Aggregate(setOf("1", "3"), "")
        val merged = current.merge(other)
        assertThat(merged.scopes, hasItems("1", "2", "3"))
        assertThat(merged.type, equalTo(""))
    }

    @Test
    fun mergeFailed() {
        val current = Aggregate(emptySet(), "aT1")
        val other = Aggregate(emptySet(), "aT2")
        assertThrows(IllegalStateException::class.java) {
            current.merge(other)
        }
    }

    @Test
    fun mergeIfConflict() {
        assertThrows(IllegalStateException::class.java) {
            Aggregate(type = "Conflict").merge(Aggregate(type = "other"))
        }
    }
}
