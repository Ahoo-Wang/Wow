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

package me.ahoo.wow.example.eshop.catalog.domain.brand

import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.eshop.catalog.api.EshopCatalogContract
import me.ahoo.wow.example.eshop.catalog.api.brand.BrandCreated
import me.ahoo.wow.example.eshop.catalog.api.brand.BrandRenamed
import me.ahoo.wow.example.eshop.catalog.api.brand.CreateBrand
import me.ahoo.wow.example.eshop.catalog.api.brand.RenameBrand
import me.ahoo.wow.example.eshop.catalog.domain.CatalogNames
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test

/** The brand on the kernel. Two brands competing for one name are the server's test, `CatalogEndToEndTest`. */
class BrandSpec {
    private val model = brandModel(EshopCatalogContract.NAME)

    @Test
    fun `creating a brand should take its trimmed name`() = aggregateSpec(model, id = "contoso") {
        whenCommand(CreateBrand(" Contoso ")) {
            expectEvents(BrandCreated("Contoso"))
            expectState { name.assert().isEqualTo("Contoso") }
        }
    }

    @Test
    fun `a brand should need a name`() = aggregateSpec(model, id = "contoso") {
        whenCommand(CreateBrand("  ")) { expectRejected(Brand.Rejections.BLANK_NAME) }
    }

    @Test
    fun `renaming a brand should take the new name, and the same name should change nothing`() =
        aggregateSpec(model, id = "contoso") {
            whenCommand(CreateBrand("Contoso")) { expectEventTypes(BrandCreated::class.java) }
            whenCommand(RenameBrand("Contoso")) { expectUnchanged() }
            whenCommand(RenameBrand("contoso")) { expectEvents(BrandRenamed("contoso")) }
            // A new spelling keeps the key; a new name takes its key and releases the old one.
            holds(CatalogNames.BRAND_NAMES, "contoso").assert().isTrue()
            whenCommand(RenameBrand("Fabrikam")) {
                expectEvents(BrandRenamed("Fabrikam"))
                expectState { name.assert().isEqualTo("Fabrikam") }
            }
            holds(CatalogNames.BRAND_NAMES, "fabrikam").assert().isTrue()
            holds(CatalogNames.BRAND_NAMES, "contoso").assert().isFalse()
            whenCommand(RenameBrand("")) { expectRejected(Brand.Rejections.BLANK_NAME) }
        }
}
