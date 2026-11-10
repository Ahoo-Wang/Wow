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
package me.ahoo.wow.spi.state

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import org.junit.jupiter.api.Test

/** A state event's id names one commit (D408): its ref's text, escaped, and its version. */
class StateEventIdTest {
    @Test
    fun `no two commits share a state event id`() {
        val tokens = listOf("a", "/", "%", "@", ":", "1", "é", "中")
        val names = (tokens + tokens.flatMap { a -> tokens.map { a + it } }).filter { it.isNotBlank() }
        val type = AggregateType("shop", "order")
        val commits = names.flatMap { tenant -> names.map { id -> type.ref(tenant, id) } }
            .flatMap { ref -> listOf(1, 11).map { ref to it } }
        commits.map { (ref, version) -> StateEventRecord.idOf(ref, version) }.toSet().size.assert()
            .isEqualTo(commits.size)
        StateEventRecord.idOf(type.ref("tenant", "o-1"), 3).assert().isEqualTo("shop.order/tenant/o-1@3")
    }
}
