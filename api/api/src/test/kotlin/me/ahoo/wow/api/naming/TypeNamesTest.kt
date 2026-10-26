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

package me.ahoo.wow.api.naming

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** The one naming rule of aggregates and their types (decision D55, finding R5.A-5). */
class TypeNamesTest {
    /** The results of 9.x's `NamingConverter.PASCAL_TO_SNAKE` (wow-metadata), which aggregate names must keep. */
    @ParameterizedTest
    @CsvSource(
        "Cart, cart",
        "CartItem, cart_item",
        "BankAccount, bank_account",
        "cartItem, cart_item",
        "OrderID, order_i_d",
        "HTTPOrder, h_t_t_p_order",
        "Cart2Item, cart2_item",
        "'', ''",
        "Order2Item, order2_item",
        "Item2, item2",
        "A, a",
        "a, a",
        "cart_item, cart_item",
        "already_Snake, already__snake",
    )
    fun `a simple name in PascalCase is the aggregate's name in snake_case`(simpleName: String, expected: String) {
        TypeNames.pascalToSnake(simpleName).assert().isEqualTo(expected)
        TypeNames.aggregateName(simpleName, null).assert().isEqualTo(expected)
    }

    @Test
    fun `an annotated name wins over the simple name, kept as it is`() {
        TypeNames.aggregateName("CartItem", "basket").assert().isEqualTo("basket")
        TypeNames.aggregateName("CartItem", "ShoppingCart").assert().isEqualTo("ShoppingCart")
        TypeNames.typeName("AddItem", "PutItem").assert().isEqualTo("PutItem")
        TypeNames.typeName("AddItem", null).assert().isEqualTo("AddItem")
    }
}
