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

package me.ahoo.wow.example.eshop.catalog.domain.category

import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.eshop.catalog.api.category.CategoryCreated
import me.ahoo.wow.example.eshop.catalog.api.category.CategoryRenamed
import me.ahoo.wow.example.eshop.catalog.api.category.CreateCategory
import me.ahoo.wow.example.eshop.catalog.api.category.RenameCategory
import me.ahoo.wow.example.eshop.catalog.domain.CatalogNames
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test

/** The category on the kernel. Two categories competing for one name are the server's test, `CatalogEndToEndTest`. */
class CategorySpec {
    private val model = categoryModel()

    @Test
    fun `creating a category should take its trimmed name`() = aggregateSpec(model, id = "mugs") {
        whenCommand(CreateCategory(" Mugs ")) {
            expectEvents(CategoryCreated("Mugs"))
            expectState { name.assert().isEqualTo("Mugs") }
        }
    }

    @Test
    fun `renaming a category should take the new name, and the same name should change nothing`() =
        aggregateSpec(model, id = "mugs") {
            given(CategoryCreated("Mugs"))
            whenCommand(RenameCategory(" Mugs")) { expectUnchanged() }
            whenCommand(RenameCategory("Cups")) {
                expectEvents(CategoryRenamed("Cups"))
                expectState { name.assert().isEqualTo("Cups") }
            }
            holds(CatalogNames.CATEGORY_NAMES, "cups").assert().isTrue()
            whenCommand(RenameCategory("Plates")) { expectEvents(CategoryRenamed("Plates")) }
            holds(CatalogNames.CATEGORY_NAMES, "plates").assert().isTrue()
            holds(CatalogNames.CATEGORY_NAMES, "cups").assert().isFalse()
        }
}
