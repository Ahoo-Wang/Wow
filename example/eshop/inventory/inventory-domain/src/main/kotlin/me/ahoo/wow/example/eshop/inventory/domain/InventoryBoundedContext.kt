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

package me.ahoo.wow.example.eshop.inventory.domain

import me.ahoo.wow.api.annotation.BoundedContext
import me.ahoo.wow.example.eshop.inventory.api.InventoryContext

/** Names the bounded context of this module's aggregates for the compiler. */
@BoundedContext(name = InventoryContext.NAME, description = "The eShop inventory: the stock of each product")
object InventoryBoundedContext
