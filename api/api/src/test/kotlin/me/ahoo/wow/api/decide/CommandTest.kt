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

package me.ahoo.wow.api.decide

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import org.junit.jupiter.api.Test

class CommandTest {
    private val ref = AggregateType("sales", "order").ref("tenant", "o-1")

    @Test
    fun `a command has no scope and no causation unless admission gives them`() {
        val command = Command("c-1", "r-1", ref, "body")
        command.scope.assert().isEqualTo(Scope.NONE)
        command.causation.assert().isNull()
        command.headers.assert().isEmpty()
    }

    @Test
    fun `a scope's present parts are not blank`() {
        Scope("owner", null).ownerId.assert().isEqualTo("owner")
        Scope(null, "space").spaceId.assert().isEqualTo("space")
        assertThrownBy<IllegalArgumentException> { Scope(ownerId = " ") }
        assertThrownBy<IllegalArgumentException> { Scope(spaceId = "") }
    }

    @Test
    fun `a causation names its root and its direct upstream`() {
        val causation = Causation("root", "sales.order/tenant/o-1@3")
        Command("c-2", "r-2", ref, "body", causation = causation).causation.assert().isEqualTo(causation)
        assertThrownBy<IllegalArgumentException> { Causation(" ", "upstream") }
        assertThrownBy<IllegalArgumentException> { Causation("root", "") }
    }
}
