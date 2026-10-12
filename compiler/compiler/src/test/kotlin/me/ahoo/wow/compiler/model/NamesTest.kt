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

package me.ahoo.wow.compiler.model

import me.ahoo.test.asserts.assert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class NamesTest {
    /** The results of 9.x's `NamingConverter.PASCAL_TO_SNAKE` (wow-metadata), which aggregate names must keep. */
    @ParameterizedTest
    @CsvSource(
        "Cart, cart",
        "CartItem, cart_item",
        "BankAccount, bank_account",
        "cart, cart",
        "cartItem, cart_item",
        "OrderID, order_i_d",
        "HTTPServer, h_t_t_p_server",
        "Cart2Item, cart2_item",
        "A, a",
        "'', ''",
    )
    fun `pascalToSnake should name as 9x does`(pascal: String, snake: String) {
        pascal.pascalToSnake().assert().isEqualTo(snake)
    }
}
