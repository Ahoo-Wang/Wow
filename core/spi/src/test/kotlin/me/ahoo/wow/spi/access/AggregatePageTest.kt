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

package me.ahoo.wow.spi.access

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A page of a type's aggregates and its cursor: the last aggregate's id and tenant, told apart by the id's length. */
class AggregatePageTest {
    private val type = AggregateType("office", "document")

    @Test
    fun `a cursor gives back the id and the tenant it was made of, whatever they hold`() {
        listOf(type.ref("t:1", "a:b"), type.ref("1", "12"), type.ref("tenant ü", "3:x")).forEach { ref ->
            AggregatePage.keyOf(AggregatePage.cursorOf(ref)).assert().isEqualTo(ref.id to ref.tenantId)
        }
        assertThrows<IllegalArgumentException> { AggregatePage.keyOf("no length") }
    }

    @Test
    fun `a full page is followed by the cursor of its last aggregate, a shorter one by none`() {
        val refs = listOf(type.ref("t", "a"), type.ref("t", "b"))
        AggregatePage.of(refs, limit = 2).next.assert().isEqualTo(AggregatePage.cursorOf(refs.last()))
        AggregatePage.of(refs, limit = 3).next.assert().isNull()
    }
}
