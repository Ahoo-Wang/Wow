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

package me.ahoo.wow.schema

import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.example.api.order.ShippingAddress
import me.ahoo.wow.schema.StatePropertyPaths.allPropertyPaths
import org.junit.jupiter.api.Test

class StatePropertyPathsTest {
    @Test
    fun allPropertyPathsForCondition() {
        Condition::class.allPropertyPaths().forEach {
            println(it)
        }
    }

    @Test
    fun allPropertyPaths() {
        DemoState::class.allPropertyPaths("state").forEach {
            println(it)
        }
    }

    class DemoState(override val id: String) : Identifier {
        var address: ShippingAddress = ShippingAddress("CN", "GD", "SZ", "YT", "YT")
        var pagedQuery: PagedQuery = PagedQuery(Condition.all())
        var pagedList: PagedList<DemoState> = PagedList.empty()
            private set
    }
}
