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

package me.ahoo.wow.example.shop.domain

import me.ahoo.wow.api.annotation.BoundedContext
import me.ahoo.wow.example.shop.api.ExampleContext

/**
 * Names the bounded context of this module's aggregates for the compiler, which writes it into the Manifest
 * fragment and the binding index. The fixed tenant 9.x gave the cart (`@StaticTenantId`) is host configuration in
 * V10 (`wow.aggregates.example.cart.tenant`, decision D116), not an annotation.
 */
@BoundedContext(name = ExampleContext.NAME, description = "The Wow example shop: carts and orders")
object ExampleBoundedContext
