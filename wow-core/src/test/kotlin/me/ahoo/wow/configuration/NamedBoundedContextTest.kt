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
import org.junit.jupiter.api.Test

internal class NamedBoundedContextTest {

    @Test
    fun merge() {
        val currentAggregates = mapOf("a1" to Aggregate(linkedSetOf("1"), null))
        val current = BoundedContext(scopes = linkedSetOf("1", "2"), aggregates = currentAggregates)

        val otherAggregates = mapOf("a1" to Aggregate(linkedSetOf("2"), "a1"))
        val other = BoundedContext(scopes = linkedSetOf("1", "3"), aggregates = otherAggregates)
        val merged = current.merge(other)
        merged.scopes.assert().contains("1", "2", "3")
    }
}
