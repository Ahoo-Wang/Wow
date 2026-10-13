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
import org.springframework.http.HttpHeaders
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * The storefront's queries of the catalog, through the snapshot query routes (`/eshop-catalog/product/snapshot/query`)
 * of a running server, as a shopper sends them. Each test files its products under a category of its own, so the tests
 * share the server without seeing each other's products.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class CatalogQueryContract {
    /** The catalog server the queries run on. */
    protected abstract val catalog: CatalogNode

    @AfterAll
    fun closeCatalog() {
        catalog.close()
        afterClose()
    }

    /** What else the test stops once the catalog is closed, such as its database. */
    protected open fun afterClose() = Unit

    private val storefront by lazy { Storefront(catalog) }

    @Test
    fun `the published products of a category should be listed cheapest first`() {
        val mugs = storefront.category()
        val cups = storefront.category()
        val brand = storefront.brand()
        val dear = storefront.product(mugs, brand, "12.00")
        val cheap = storefront.product(mugs, brand, "5.00")
        val middle = storefront.product(mugs, brand, "8.50")
        storefront.product(mugs, brand, "1.00", publish = false)
        storefront.product(cups, brand, "2.00")
        val listed = storefront.query(
            """{"kind":"list","filter":${published(mugs)},"sort":[$BY_PRICE]}""",
        )
        ids(listed.json).assert().containsExactly(cheap, middle, dear)
        listed.json.values().map { it["state"]["price"].decimalValue().stripTrailingZeros().toPlainString() }
            .assert().containsExactly("5", "8.5", "12")
    }

    @Test
    fun `a category's products should be paged by price, with the total`() {
        val category = storefront.category()
        val brand = storefront.brand()
        val prices = listOf("3.00", "1.00", "5.00", "2.00", "4.00")
        val byPrice = prices.associateWith { storefront.product(category, brand, it) }
            .toSortedMap(compareBy { it.toBigDecimal() }).values.toList()
        val filter = published(category)
        val pages = (1..3).map { index ->
            val page = """"pagination":{"index":$index,"size":2}"""
            storefront.query("""{"kind":"paged","filter":$filter,"sort":[$BY_PRICE],$page}""").json
        }
        pages.map { it["total"].asLong() }.assert().containsOnly(5L)
        pages.map { ids(it["list"]) }.assert().containsExactly(byPrice.take(2), byPrice.subList(2, 4), byPrice.drop(4))
    }

    @Test
    fun `a cursor should walk a category's products by price once`() {
        val category = storefront.category()
        val brand = storefront.brand()
        val byPrice = listOf("7.00", "6.00", "9.00", "8.00").map { storefront.product(category, brand, it) }
            .let { listOf(it[1], it[0], it[3], it[2]) }
        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val after = cursor?.let { ""","cursor":"$it"""" }.orEmpty()
            val page = storefront.query(
                """{"kind":"cursor","filter":${published(category)},"sort":[$BY_PRICE],"size":3$after}""",
            ).json
            seen += ids(page)
            cursor = page["nextCursor"].takeUnless { it.isNull }?.asString()
        } while (cursor != null)
        seen.assert().containsExactlyElementsOf(byPrice)
    }

    @Test
    fun `a brand's products should be filtered by a price range`() {
        val category = storefront.category()
        val brand = storefront.brand()
        val other = storefront.brand()
        storefront.product(category, brand, "4.99")
        val five = storefront.product(category, brand, "5.00")
        val ten = storefront.product(category, brand, "10.00")
        storefront.product(category, brand, "10.01")
        storefront.product(category, other, "7.00")
        val filter = """{"op":"AND","operands":[
            {"op":"EQ","field":"state.brandId","value":"$brand"},
            {"op":"EQ","field":"state.categoryId","value":"$category"},
            {"op":"GTE","field":"state.price","value":5},
            {"op":"LTE","field":"state.price","value":10}]}"""
        ids(storefront.query("""{"kind":"list","filter":$filter,"sort":[$BY_PRICE]}""").json)
            .assert().containsExactly(five, ten)
    }

    @Test
    fun `a delisted product should leave the storefront, and a count should say so`() {
        val category = storefront.category()
        val brand = storefront.brand()
        val kept = storefront.product(category, brand, "3.00")
        val delisted = storefront.product(category, brand, "4.00")
        catalog.post("/eshop-catalog/product/$delisted/delist-product", """{"reason":"Out of season"}""").assertOk()
        ids(storefront.query("""{"kind":"list","filter":${published(category)}}""").json).assert().containsExactly(kept)
        val count = storefront.query("""{"kind":"count","filter":${published(category)}}""").json
        count["count"].asLong().assert().isEqualTo(1)
        count["exact"].asBoolean().assert().isTrue()
        val single = storefront.query(
            """{"kind":"single","filter":{"op":"ID","value":"$delisted"}}""",
        ).json
        single["state"]["status"].asString().assert().isEqualTo("DELISTED")
    }

    @Test
    fun `the descriptor should list the storefront's fields, with its version as ETag`() {
        val described = catalog.get(PRODUCTS).assertOk()
        val tag = described.headers.eTag
        tag.assert().isEqualTo("\"${described.json["version"].asString()}\"")
        catalog.get(PRODUCTS, mapOf(HttpHeaders.IF_NONE_MATCH to tag!!)).status.assert().isEqualTo(NOT_MODIFIED)
        val fields = described.json["fields"].values().associateBy { it["path"].asString() }
        fields.keys.assert().contains("state.categoryId", "state.brandId", "state.status", "state.price")
        fields.getValue("state.price")["types"].values().map { it.asString() }.assert().containsExactly("DECIMAL")
        fields.getValue("state.status")["enum"].values().map { it["value"].asString() }.assert()
            .containsExactly("DRAFT", "PUBLISHED", "DELISTED")
    }

    @Test
    fun `brands and categories should be queried by name`() {
        val name = "Brand ${UUID.randomUUID()}"
        val brand = catalog.post("/eshop-catalog/brand/create-brand", """{"name":"$name"}""").assertOk()
            .json["ref"]["id"].asString()
        val found = catalog.post(
            "/eshop-catalog/brand/snapshot/query",
            """{"kind":"single","filter":{"op":"EQ","field":"state.name","value":"$name"}}""",
        ).assertOk().json
        found["aggregateId"].asString().assert().isEqualTo(brand)
        val category = storefront.category()
        catalog.post(
            "/eshop-catalog/category/snapshot/query",
            """{"kind":"list","filter":{"op":"ID","value":"$category"}}""",
        ).assertOk().json.values().map { it["aggregateId"].asString() }.assert().containsExactly(category)
    }

    @Test
    fun `a query on a field the state does not have should be refused`() {
        catalog.post(PRODUCTS, """{"kind":"list","filter":{"op":"EQ","field":"state.colour","value":"red"}}""")
            .assertProblem(BAD_REQUEST, "wow.query.invalid")
            .json["violations"][0]["code"].asString().assert().isEqualTo("UNKNOWN_FIELD")
    }

    private fun published(category: String): String = """{"op":"AND","operands":[
        {"op":"EQ","field":"state.categoryId","value":"$category"},
        {"op":"EQ","field":"state.status","value":"PUBLISHED"}]}"""

    companion object {
        const val PRODUCTS = "/eshop-catalog/product/snapshot/query"
        const val BY_PRICE = """{"field":"state.price","direction":"ASC"}"""
        const val BAD_REQUEST = 400
        const val NOT_MODIFIED = 304
    }
}

/** The ids of the snapshots of a list, or of a page's `list`, in their order. */
fun ids(rows: JsonNode): List<String> =
    (if (rows.isArray) rows else rows["list"]).values().map { it["aggregateId"].asString() }

/** What a catalog administrator and a shopper do over HTTP: create brands, categories and products, and query. */
class Storefront(private val catalog: CatalogNode) {
    /** A new category with a name of its own; its id. */
    fun category(): String = catalog.post(
        "/eshop-catalog/category/create-category",
        """{"name":"Category ${UUID.randomUUID()}"}""",
    ).assertOk().json["ref"]["id"].asString()

    /** A new brand with a name of its own; its id. */
    fun brand(): String = catalog.post(
        "/eshop-catalog/brand/create-brand",
        """{"name":"Brand ${UUID.randomUUID()}"}""",
    ).assertOk().json["ref"]["id"].asString()

    /** A new product of [category] and [brand] at [price], published unless [publish] is false; its id. */
    fun product(category: String, brand: String, price: String, publish: Boolean = true): String {
        val id = catalog.post(
            "/eshop-catalog/product/create-product",
            """{"name":"Product at $price","price":$price,"brandId":"$brand","categoryId":"$category"}""",
        ).assertOk().json["ref"]["id"].asString()
        if (publish) catalog.post("/eshop-catalog/product/$id/publish-product").assertOk()
        return id
    }

    fun query(body: String): Answer = catalog.post(CatalogQueryContract.PRODUCTS, body).assertOk()
}
