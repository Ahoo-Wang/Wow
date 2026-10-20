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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.dsl.listQuery
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.example.eshop.catalog.api.product.ProductStatus
import me.ahoo.wow.example.eshop.catalog.domain.product.ProductState
import me.ahoo.wow.example.eshop.catalog.domain.product.ProductStateProperties
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpHeaders
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
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
    fun `a buyer should see every product an employee filed, and change none of them (D189)`() {
        val category = storefront.category()
        val product = storefront.product(category, storefront.brand(), "3.00")
        ids(storefront.query("""{"kind":"list","filter":${published(category)}}""").json)
            .assert().containsExactly(product)
        catalog.post("/eshop-catalog/product/$product/change-product-price", """{"price":1.00}""", BUYER)
            .assertProblem(FORBIDDEN, "wow.auth.forbidden")
        catalog.post("/eshop-catalog/product/$product/change-product-price", """{"price":4.00}""", EMPLOYEE)
            .assertOk()
        catalog.get("/eshop-catalog/product/$product/state", BUYER).assertOk()
            .json["state"]["price"].decimalValue().assert().isEqualByComparingTo("4.00")
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
        // A body that is no query at all is malformed, not invalid.
        catalog.post(PRODUCTS, """{"kind":"lists","filter":{"op":"MATCH_ALL"}}""")
            .assertProblem(BAD_REQUEST, "wow.query.malformed")
    }

    /**
     * What a service's own code does: the storefront's query written with the query DSL and the compiler's
     * `ProductStateProperties`, run in process on the node's snapshot query gateway, which the routes above serve too.
     */
    @Test
    fun `the storefront's query written with the DSL and the generated properties should run on the gateway`() {
        val category = storefront.category()
        val brand = storefront.brand()
        storefront.product(category, brand, "4.00")
        val five = storefront.product(category, brand, "5.00")
        val seven = storefront.product(category, brand, "7.00")
        storefront.product(category, brand, "6.00", publish = false)
        val query = listQuery {
            filter {
                ProductStateProperties.STATE__CATEGORY_ID eq category
                ProductStateProperties.STATE__STATUS eq ProductStatus.PUBLISHED
                ProductStateProperties.STATE__PRICE gte BigDecimal("5.00")
            }
            sort { ProductStateProperties.STATE__PRICE.asc() }
        }
        // In process, typed: the node's QueryApi, as trusted code (decision D259).
        val products = catalog.context.getBean(QueryApi::class.java).trusted().snapshots<ProductState>()
        val rows = runBlocking { products.list(query).toList() }
        rows.map { it.ref.id }.assert().containsExactly(five, seven)
        rows.map { it.state.price }.assert().containsExactly(BigDecimal("5.00"), BigDecimal("7.00"))
    }

    @Test
    fun `a list should stream as server-sent events, one row per event`() {
        val category = storefront.category()
        val brand = storefront.brand()
        val filed = (1..3).map { storefront.product(category, brand, "$it.00") }
        val events = catalog.stream(PRODUCTS, """{"kind":"list","filter":${published(category)}}""", BUYER)
        // One row per record, then the end of a resumable stream (decision D253).
        events.map { it.first }.assert().containsExactly("row", "row", "row", "end")
        json.readTree(events.last().second)["reason"].asString().assert().isEqualTo("complete")
        val streamed = events.dropLast(1).map { (_, data) -> json.readTree(data)["aggregateId"].asString() }
        streamed.assert().containsExactlyInAnyOrderElementsOf(filed)
    }

    @Test
    fun `a product's events should be read by version and analyzed per event`() {
        val product = storefront.product(storefront.category(), storefront.brand(), "3.00")
        val events = catalog.post(PRODUCT_EVENTS, """{"kind":"list","filter":{"op":"ID","value":"$product"}}""", BUYER)
            .assertOk().json
        events.values().map { it["version"].asInt() }.assert().containsExactly(1, 2)
        events.values().map { it["events"][0]["name"].asString() }.assert()
            .containsExactly("eshop-catalog.product.ProductCreated", "eshop-catalog.product.ProductPublished")
        // On the unified engine (decision D235): an event condition is an element match; per-event analytics expand.
        val published = """{"op":"ELEMENT_MATCH","field":"events","predicate":""" +
            """{"op":"EQ","field":"name","value":"eshop-catalog.product.ProductPublished"}}"""
        catalog.post(
            PRODUCT_EVENTS,
            """{"kind":"count","filter":{"op":"AND","operands":[{"op":"ID","value":"$product"},$published]}}""",
            BUYER,
        ).assertOk().json["count"].asLong().assert().isEqualTo(1)
        val perEvent = catalog.post(
            PRODUCT_EVENTS,
            """{"kind":"aggregate","filter":{"op":"ID","value":"$product"},"elements":[{"path":"events"}],""" +
                """"groupBy":[{"type":"TERMS","field":"name","alias":"event"}],"metrics":[{"type":"COUNT","alias":"n"}]}""",
            BUYER,
        ).assertOk().json
        perEvent.values().map { it["event"].asString() to it["n"].asLong() }.assert().containsExactly(
            "eshop-catalog.product.ProductCreated" to 1L,
            "eshop-catalog.product.ProductPublished" to 1L,
        )
        // A root metric over an event field must expand the events first (review X-8).
        catalog.post(
            PRODUCT_EVENTS,
            """{"kind":"aggregate","filter":{"op":"MATCH_ALL"},""" +
                """"groupBy":[{"type":"TERMS","field":"events.name","alias":"event"}],"metrics":[{"type":"COUNT","alias":"n"}]}""",
            BUYER,
        ).assertProblem(BAD_REQUEST, "wow.query.invalid")
    }

    /**
     * The audit trail of decision D163, end to end: the employee whose HTTP command committed a version is its
     * operator, in the commit's event stream and in the snapshot, which keeps the creator too; a snapshot query filters
     * by both.
     */
    @Test
    fun `the operator should follow a product from the employee's command to the snapshot query`() {
        val category = storefront.category()
        val product = storefront.product(category, storefront.brand(), "3.00")
        val created = catalog.post(PRODUCT_EVENTS, """{"kind":"list","filter":{"op":"ID","value":"$product"}}""", BUYER)
            .assertOk().json[0]
        created["operator"].assertOperator(EMPLOYEE_ID)

        val other = "frank-" + UUID.randomUUID().toString().take(8)
        catalog.post(
            "/eshop-catalog/product/$product/change-product-price",
            """{"price":3.50}""",
            mapOf(CatalogCallers.USER to other, CatalogCallers.ROLE to CatalogCallers.EMPLOYEE),
        ).assertOk()
        val latest = storefront.query("""{"kind":"single","filter":{"op":"ID","value":"$product"}}""").json
        latest["operator"].assertOperator(other)
        latest["firstOperator"].assertOperator(EMPLOYEE_ID)

        val inCategory = """{"op":"EQ","field":"state.categoryId","value":"$category"}"""

        fun filed(filter: String) =
            ids(storefront.query("""{"kind":"list","filter":{"op":"AND","operands":[$filter,$inCategory]}}""").json)
        filed("""{"op":"EQ","field":"operator.id","value":"$other"}""").assert().containsExactly(product)
        filed("""{"op":"EQ","field":"firstOperator.id","value":"$EMPLOYEE_ID"}""").assert().containsExactly(product)
        filed("""{"op":"EQ","field":"operator.id","value":"$EMPLOYEE_ID"}""").assert().isEmpty()
    }

    private fun JsonNode.assertOperator(id: String) {
        this["id"].asString().assert().isEqualTo(id)
        this["tenantId"].asString().assert().isEqualTo(Caller.DEFAULT_TENANT)
        this["crossScope"].asBoolean().assert().isFalse()
    }

    private fun published(category: String): String = """{"op":"AND","operands":[
        {"op":"EQ","field":"state.categoryId","value":"$category"},
        {"op":"EQ","field":"state.status","value":"PUBLISHED"}]}"""

    companion object {
        const val PRODUCTS = "/eshop-catalog/product/snapshot/query"
        const val PRODUCT_EVENTS = "/eshop-catalog/product/events/query"
        private const val EMPLOYEE_ID = "erin"
        const val BY_PRICE = """{"field":"state.price","direction":"ASC"}"""
        const val BAD_REQUEST = 400
        const val FORBIDDEN = 403
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
        EMPLOYEE,
    ).assertOk().json["ref"]["id"].asString()

    /** A new brand with a name of its own; its id. */
    fun brand(): String = catalog.post(
        "/eshop-catalog/brand/create-brand",
        """{"name":"Brand ${UUID.randomUUID()}"}""",
        EMPLOYEE,
    ).assertOk().json["ref"]["id"].asString()

    /** A new product of [category] and [brand] at [price], published unless [publish] is false; its id. */
    fun product(category: String, brand: String, price: String, publish: Boolean = true): String {
        val id = catalog.post(
            "/eshop-catalog/product/create-product",
            """{"name":"Product at $price","price":$price,"brandId":"$brand","categoryId":"$category"}""",
            EMPLOYEE,
        ).assertOk().json["ref"]["id"].asString()
        if (publish) catalog.post("/eshop-catalog/product/$id/publish-product", headers = EMPLOYEE).assertOk()
        return id
    }

    /** The answer to the products query [body], sent as a buyer: the shopper reads every product of the tenant. */
    fun query(body: String): Answer = catalog.post(CatalogQueryContract.PRODUCTS, body, BUYER).assertOk()
}
