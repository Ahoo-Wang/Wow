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

package me.ahoo.wow.example.inventory.api

/**
 * The names of the example's second bounded context and its aggregate (decision D144): `inventory`, with a `stock`
 * per product, its id the product's id. Its commands and events are named `inventory.stock.<Name>` (decision D55);
 * the shop's order saga sends `inventory.stock.ReserveStock` to it across contexts.
 */
object InventoryContext {
    const val NAME = "inventory"
    const val STOCK = "stock"
}
