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

package me.ahoo.wow.models.common

import jakarta.validation.constraints.NotBlank
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class EnumTextAndStatusTest {
    enum class OrderStatus(override val text: String) : EnumText {
        CREATED("Created"),
        PAID("Paid"),
    }

    data class Order(override val status: OrderStatus, override val name: String) :
        StatusCapable<OrderStatus>,
        NameCapable

    @Test
    fun `an enum should carry its text`() {
        OrderStatus.entries.map { it.text }.assert().containsExactly("Created", "Paid")
    }

    @Test
    fun `a status capable type should expose its status`() {
        val order = Order(OrderStatus.PAID, "order")
        order.status.assert().isEqualTo(OrderStatus.PAID)
        order.status.text.assert().isEqualTo("Paid")
        order.name.assert().isEqualTo("order")
    }

    @Test
    fun `the not-blank capabilities should constrain their getters`() {
        listOf(
            NotBlankNameCapable::class.java to "getName",
            NotBlankTypeCapable::class.java to "getType",
            PolymorphicTypeCapable::class.java to "getType",
            NotBlankItemIdCapable::class.java to "getItemId",
        ).forEach { (type, getter) ->
            val constrained = typeAndSupertypes(type)
                .mapNotNull { runCatching { it.getDeclaredMethod(getter) }.getOrNull() }
                .any { it.isAnnotationPresent(NotBlank::class.java) }
            constrained.assert().withFailMessage { "${type.simpleName}.$getter is not @NotBlank" }.isTrue()
        }
    }

    private fun typeAndSupertypes(type: Class<*>): List<Class<*>> =
        listOf(type) + type.interfaces.flatMap(::typeAndSupertypes)
}
