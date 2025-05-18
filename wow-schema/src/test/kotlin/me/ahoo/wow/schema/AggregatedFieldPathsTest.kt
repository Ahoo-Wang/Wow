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

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.example.api.order.ShippingAddress
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.models.common.PolymorphicTypeCapable
import me.ahoo.wow.models.common.TypeCapable
import me.ahoo.wow.schema.AggregatedFieldPaths.commandAggregatedFieldPaths
import me.ahoo.wow.schema.TypeFieldPaths.allFieldPaths
import org.junit.jupiter.api.Test

class AggregatedFieldPathsTest {
    @Test
    fun allFieldPathsForCondition() {
        Condition::class.allFieldPaths().forEach {
            println(it)
        }
    }

    @Test
    fun allFieldPaths() {
        val allFieldPaths = DemoState::class.allFieldPaths(parentName = "state")
        allFieldPaths.assert().contains("state.address")
            .doesNotContain("addresses")
            .contains("state.pagedQuery")
            .contains("state.config.value")
    }

    @Test
    fun allFieldPathsForPolymorphic() {
        PolymorphicConfig.Custom::class.allFieldPaths().forEach {
            println(it)
        }
    }

    @Test
    fun commandAggregatedFieldPaths() {
        Order::class.commandAggregatedFieldPaths().forEach {
            println(it)
        }
    }

    class DemoState(override val id: String) : Identifier {
        var address: ShippingAddress = ShippingAddress("CN", "GD", "SZ", "YT", "YT")
        var config: PolymorphicConfig = PolymorphicConfig.Default

        @JsonIgnore
        var addresses: List<ShippingAddress> = emptyList()
        var addressArray: Array<ShippingAddress> = emptyArray()

        @JsonIgnore(false)
        var pagedQuery: PagedQuery = PagedQuery(Condition.all())
        var pagedList: PagedList<DemoState> = PagedList.empty()
    }

    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = TypeCapable.TYPE
    )
    @JsonSubTypes(
        JsonSubTypes.Type(value = PolymorphicConfig.Default::class, name = PolymorphicConfig.Default.TYPE),
        JsonSubTypes.Type(value = PolymorphicConfig.Custom::class, name = PolymorphicConfig.Custom.TYPE),
    )
    interface PolymorphicConfig : PolymorphicTypeCapable {
        object Default : PolymorphicConfig {
            const val TYPE = "default"
            override val type: String = TYPE
        }

        data class Custom(val value: String) : PolymorphicConfig {
            companion object {
                const val TYPE = "custom"
            }

            override val type: String = TYPE
        }
    }
}
