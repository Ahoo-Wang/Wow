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

package me.ahoo.wow.api.aggregate

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class AggregateRefTest {
    private val type = AggregateType("shop", "order")

    @Test
    fun `a ref names its id, or none yet for a creation that leaves it to admission`() {
        type.ref("t", "o-1").isAssigned.assert().isTrue()
        val unassigned = type.unassigned("t")
        unassigned.isAssigned.assert().isFalse()
        unassigned.id.assert().isEqualTo(AggregateRef.UNASSIGNED)
        unassigned.tenantId.assert().isEqualTo("t")
        unassigned.toString().assert().isEqualTo("shop.order/t/(none)")
    }
}
