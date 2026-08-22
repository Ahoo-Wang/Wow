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
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.LogicalField
import org.junit.jupiter.api.Test

class FilterDslTest {
    @Test
    fun `should build implicit AND filter`() {
        val expression = filter {
            deletion(DeletionState.ACTIVE)
            "state.status" eq "PAID"
            or {
                "state.ownerId" eq "owner-1"
                "state.ownerId".notExists()
            }
        }

        expression.assert().isInstanceOf(AndFilter::class.java)
        (expression as AndFilter).operands.assert().hasSize(3)
    }

    @Test
    fun `should build relative element fields`() {
        val expression = filter {
            "state.items".elementMatch {
                "productId" eq "product-1"
            }
        }

        val element = expression as ElementMatchFilter
        element.field.assert().isEqualTo(LogicalField("state.items"))
        (element.predicate as EqualFilter).field.assert().isEqualTo(LogicalField("productId"))
    }

    @Test
    fun `should reject empty logical block`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            filter { and { } }
        }
    }
}
