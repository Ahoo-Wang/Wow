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

package com.example.catalog.domain

import com.example.catalog.api.ListProduct
import com.example.catalog.api.ProductListed
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.BoundedContext

@BoundedContext(name = "catalog", description = "The products on sale")
object CatalogBoundedContext

class ProductState {
    var name: String = ""
        private set

    fun onSourcing(event: ProductListed) {
        name = event.name
    }
}

@AggregateRoot
class Product(private val state: ProductState) {
    fun onCommand(command: ListProduct): ProductListed = ProductListed(command.name)
}
