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

package me.ahoo.wow.example.eshop.catalog.server

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

/** The catalog's commands over HTTP on the memory backend: their routes, their rejections as problems, the state. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogEndToEndTest {
    private val catalog = startCatalog()

    @AfterAll
    fun close() {
        catalog.close()
    }

    private fun unique(prefix: String) = "$prefix ${UUID.randomUUID()}"

    @Test
    fun `a product should go from draft to published to delisted and back`() {
        val brand = catalog.post("/eshop-catalog/brand/create-brand", """{"name":"${unique("Brand")}"}""").assertOk()
            .json["ref"]["id"].asString()
        val category = catalog.post("/eshop-catalog/category/mugs/create-category", """{"name":"${unique("Mugs")}"}""")
            .assertOk().json["ref"]["id"].asString()
        category.assert().isEqualTo("mugs")
        val id = catalog.post("/eshop-catalog/product/create-product", """{"name":"Wow mug","price":8.50}""").assertOk()
            .json["ref"]["id"].asString()
        catalog.post("/eshop-catalog/product/$id/publish-product")
            .assertProblem(CONFLICT, "eshop-catalog.product.incomplete")
        catalog.post(
            "/eshop-catalog/product/$id/update-product",
            """{"name":"Wow mug","description":"Half a litre","brandId":"$brand","categoryId":"$category"}""",
        ).assertOk()
        catalog.post("/eshop-catalog/product/$id/change-product-price", """{"price":9.00}""").assertOk()
            .json["version"].asInt().assert().isEqualTo(3)
        catalog.post("/eshop-catalog/product/$id/publish-product").assertOk()
        catalog.get("/eshop-catalog/product/$id/state").assertOk().json["state"].let { state ->
            state["status"].asString().assert().isEqualTo("PUBLISHED")
            state["price"].decimalValue().assert().isEqualByComparingTo("9.00")
            state["categoryId"].asString().assert().isEqualTo("mugs")
        }
        catalog.post("/eshop-catalog/product/$id/delist-product", """{"reason":"Out of season"}""").assertOk()
        catalog.post("/eshop-catalog/product/$id/delist-product").assertProblem(CONFLICT, "eshop-catalog.product.not-published")
        catalog.post("/eshop-catalog/product/$id/publish-product").assertOk()
            .json["version"].asInt().assert().isEqualTo(6)
    }

    @Test
    fun `a product should be refused a price that is not more than zero`() {
        catalog.post("/eshop-catalog/product/create-product", """{"name":"Free mug","price":0}""")
            .assertProblem(UNPROCESSABLE, "eshop-catalog.product.invalid-price")
        catalog.post("/eshop-catalog/product/create-product", """{"name":" ","price":1}""")
            .assertProblem(UNPROCESSABLE, "eshop-catalog.product.blank-name")
    }

    @Test
    fun `two brands should not share a name, whatever its case`() {
        val name = unique("Contoso")
        catalog.post("/eshop-catalog/brand/create-brand", """{"name":"$name"}""").assertOk()
        catalog.post("/eshop-catalog/brand/create-brand", """{"name":" ${name.uppercase()} "}""")
            .assertProblem(CONFLICT, "eshop-catalog.brand.name-taken")
        val other = catalog.post("/eshop-catalog/brand/create-brand", """{"name":"${unique("Fabrikam")}"}""").assertOk()
            .json["ref"]["id"].asString()
        catalog.post("/eshop-catalog/brand/$other/rename-brand", """{"name":"$name"}""")
            .assertProblem(CONFLICT, "eshop-catalog.brand.name-taken")
    }

    @Test
    fun `two categories should not share a name`() {
        val name = unique("Cups")
        catalog.post("/eshop-catalog/category/create-category", """{"name":"$name"}""").assertOk()
        catalog.post("/eshop-catalog/category/create-category", """{"name":"$name"}""")
            .assertProblem(CONFLICT, "eshop-catalog.category.name-taken")
        catalog.post("/eshop-catalog/category/create-category", """{"name":""}""")
            .assertProblem(UNPROCESSABLE, "eshop-catalog.category.blank-name")
    }

    @Test
    fun `the memory backend should serve no snapshot queries`() {
        catalog.post(CatalogQueryContract.PRODUCTS, """{"kind":"list","filter":{"op":"MATCH_ALL"}}""").status
            .assert().isEqualTo(NOT_FOUND)
    }

    private companion object {
        const val CONFLICT = 409
        const val UNPROCESSABLE = 422
        const val NOT_FOUND = 404
    }
}
