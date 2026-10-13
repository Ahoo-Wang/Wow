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

package me.ahoo.wow.manifest

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class CommandRoutesTest {
    @ParameterizedTest
    @CsvSource(
        "AddCartItem, add-cart-item",
        "PutItem, put-item",
        "Checkout, checkout",
        "checkout, checkout",
        "HTTPRequest, http-request",
        "OrderID, order-id",
        "AddItemV2, add-item-v2",
        "Cart2Item, cart2-item",
        "add_item, add-item",
        "'Add Item', add-item",
        "already-kebab, already-kebab",
        "A, a",
        "wow.lifecycle.RestoreAggregate, wow.lifecycle.restore-aggregate",
    )
    fun `a command name should become its kebab-case segment`(name: String, segment: String) {
        CommandRoutes.segment(name).assert().isEqualTo(segment)
    }

    @Test
    fun `every command should have a route by id`() {
        CommandRoutes.of("shop", "cart", "AddCartItem", Creation.NONE).assert().containsExactly(
            RouteManifest("POST", "/shop/cart/{id}/add-cart-item"),
        )
    }

    @Test
    fun `a creation command should also have a route without an id`() {
        listOf(Creation.REQUIRED, Creation.ALLOWED).forEach { creation ->
            CommandRoutes.of("shop", "cart_item", "CreateCart", creation).assert().containsExactly(
                RouteManifest("POST", "/shop/cart_item/{id}/create-cart"),
                RouteManifest("POST", "/shop/cart_item/create-cart"),
            )
        }
    }

    @Test
    fun `a mounted command should also have a route without an id`() {
        CommandRoutes.of("shop", "customer", "ImportCustomer", Creation.NONE, mounted = true).assert().containsExactly(
            RouteManifest("POST", "/shop/customer/{id}/import-customer"),
            RouteManifest("POST", "/shop/customer/import-customer"),
        )
        CommandRoutes.unaddressed("shop", "customer", "ChangePassword")
            .assert().isEqualTo(RouteManifest("POST", "/shop/customer/change-password"))
    }

    @Test
    fun `the framework's segments should be reserved`() {
        CommandRoutes.isReserved("query").assert().isTrue()
        CommandRoutes.isReserved("state").assert().isTrue()
        CommandRoutes.isReserved("wow.lifecycle.restore-aggregate").assert().isTrue()
        CommandRoutes.isReserved("wow.anything").assert().isTrue()
        CommandRoutes.isReserved("query-orders").assert().isFalse()
        CommandRoutes.isReserved("wow").assert().isFalse()
        CommandRoutes.RESERVED_CONTEXT.assert().isEqualTo("wow")
    }
}
