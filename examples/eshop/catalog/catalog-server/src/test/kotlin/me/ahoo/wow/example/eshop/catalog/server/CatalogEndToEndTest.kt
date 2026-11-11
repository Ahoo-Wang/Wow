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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.wait
import me.ahoo.wow.api.query.dsl.countQuery
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.example.eshop.catalog.api.brand.CreateBrand
import me.ahoo.wow.example.eshop.catalog.api.brand.RenameBrand
import me.ahoo.wow.example.eshop.catalog.api.category.CreateCategory
import me.ahoo.wow.example.eshop.catalog.api.category.RenameCategory
import me.ahoo.wow.example.eshop.catalog.api.product.ChangeProductPrice
import me.ahoo.wow.example.eshop.catalog.api.product.CreateProduct
import me.ahoo.wow.example.eshop.catalog.api.product.DelistProduct
import me.ahoo.wow.example.eshop.catalog.api.product.ProductStateView
import me.ahoo.wow.example.eshop.catalog.api.product.ProductStatus
import me.ahoo.wow.example.eshop.catalog.api.product.PublishProduct
import me.ahoo.wow.example.eshop.catalog.api.product.UpdateProduct
import me.ahoo.wow.example.eshop.catalog.domain.product.Storefront
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.UUID

/** The catalog's commands on the memory backend, sent and read by its typed clients: their rejections and the state. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogEndToEndTest {
    private val server = wowServer<CatalogServer>("dev")

    @AfterAll
    fun close() {
        server.close()
    }

    private fun unique(prefix: String) = "$prefix ${UUID.randomUUID()}"

    @Test
    fun `a product should go from draft to published to delisted and back`() {
        val catalog = catalog()
        val brand = send { catalog.wait(CreateBrand(unique("Brand"))) }.ref.id
        val category = send { catalog.wait(CreateCategory(unique("Mugs")), "mugs") }.ref.id
        category.assert().isEqualTo("mugs")
        val id = send { catalog.wait(CreateProduct("Wow mug", BigDecimal("8.50"))) }.ref.id
        rejected { catalog.wait(PublishProduct, id) }.code.assert().isEqualTo("eshop-catalog.product.incomplete")
        send { catalog.wait(UpdateProduct("Wow mug", "Half a litre", brand, category), id) }
        send { catalog.wait(ChangeProductPrice(BigDecimal("9.00")), id) }.version.assert().isEqualTo(3)
        send { catalog.wait(PublishProduct, id) }
        product(id)!!.state.let { state ->
            state.status.assert().isEqualTo(ProductStatus.PUBLISHED)
            state.price.assert().isEqualByComparingTo("9.00")
            state.categoryId.assert().isEqualTo("mugs")
        }
        send { catalog.wait(DelistProduct("Out of season"), id) }
        rejected { catalog.wait(DelistProduct(), id) }.code.assert().isEqualTo("eshop-catalog.product.not-published")
        send { catalog.wait(PublishProduct, id) }.version.assert().isEqualTo(6)
    }

    @Test
    fun `a product should be refused a blank name or a price that is not more than zero`() {
        val catalog = catalog()
        invalid { catalog.wait(CreateProduct("Free mug", BigDecimal.ZERO)) }
            .assert().containsExactly("price" to "positive")
        invalid { catalog.wait(CreateProduct(" ", BigDecimal("-1"))) }
            .assert().containsExactly("name" to "not-blank", "price" to "positive")
        val id = send { catalog.wait(CreateProduct("Wow tray", BigDecimal("4"))) }.ref.id
        invalid { catalog.wait(UpdateProduct(""), id) }.assert().containsExactly("name" to "not-blank")
        invalid { catalog.wait(ChangeProductPrice(BigDecimal.ZERO), id) }
            .assert().containsExactly("price" to "positive")
    }

    @Test
    fun `two brands should not share a name, whatever its case`() {
        val catalog = catalog()
        val name = unique("Contoso")
        send { catalog.wait(CreateBrand(name)) }
        rejected { catalog.wait(CreateBrand(" ${name.uppercase()} ")) }.code.assert().isEqualTo(BRAND_NAME_TAKEN)
        val other = send { catalog.wait(CreateBrand(unique("Fabrikam"))) }.ref.id
        rejected { catalog.wait(RenameBrand(name), other) }.code.assert().isEqualTo(BRAND_NAME_TAKEN)
        invalid { catalog.wait(CreateBrand("  ")) }.assert().containsExactly("name" to "not-blank")
        invalid { catalog.wait(RenameBrand(""), other) }.assert().containsExactly("name" to "not-blank")
    }

    @Test
    fun `two categories should not share a name`() {
        val catalog = catalog()
        val name = unique("Cups")
        send { catalog.wait(CreateCategory(name)) }
        rejected { catalog.wait(CreateCategory(name)) }.code.assert().isEqualTo("eshop-catalog.category.name-taken")
        invalid { catalog.wait(CreateCategory("")) }.assert().containsExactly("name" to "not-blank")
        val id = send { catalog.wait(CreateCategory(unique("Plates"))) }.ref.id
        invalid { catalog.wait(RenameCategory(" "), id) }.assert().containsExactly("name" to "not-blank")
    }

    @Test
    fun `an employee should change the catalog, a buyer only read it (D189)`() {
        val id = send { catalog(EMPLOYEE).wait(CreateProduct("Wow cap", BigDecimal("12"))) }.ref.id
        rejected { catalog(BUYER).wait(CreateProduct("Mine", BigDecimal.ONE)) }
            .code.assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        // The product belongs to the tenant: it records no owner, and the buyer reads it.
        product(id, BUYER)!!.scope.ownerId.assert().isNull()
        rejected { catalog(BUYER).wait(ChangeProductPrice(BigDecimal("13")), id) }
            .code.assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        send { catalog(EMPLOYEE).wait(ChangeProductPrice(BigDecimal("13")), id) }
    }

    /** Over HTTP: a rejection is answered as an RFC 9457 problem, `urn:wow:error:<code>`, the wire's own contract. */
    @Test
    fun `a rejection should be an RFC 9457 problem`() {
        val name = unique("Northwind")
        server.http.post("/eshop-catalog/brand/create-brand", """{"name":"$name"}""").problem.assert().isNull()
        val taken = server.http.post("/eshop-catalog/brand/create-brand", """{"name":"$name"}""")
        taken.header("Content-Type").assert().isEqualTo("application/problem+json")
        taken.body!!["type"].asString().assert().isEqualTo("urn:wow:error:$BRAND_NAME_TAKEN")
        taken.problem!!.status.assert().isEqualTo(CONFLICT)
        taken.problem!!.code.assert().isEqualTo(BRAND_NAME_TAKEN)
    }

    /**
     * Over HTTP: the storefront's service routes answer a listing as JSON, none as `204 No Content` while the product
     * is not published, and a refusal as an RFC 9457 problem with its code's status.
     */
    @Test
    fun `the storefront should answer a listing, none or a refusal`() {
        val catalog = catalog()
        val brand = send { catalog.wait(CreateBrand(unique("Brand"))) }.ref.id
        val category = send { catalog.wait(CreateCategory(unique("Cups"))) }.ref.id
        val id = send { catalog.wait(CreateProduct("Wow cup", BigDecimal("4.00"), "", brand, category)) }.ref.id
        server.http.get("/eshop-catalog/listings/$id").let { answer ->
            answer.status.assert().isEqualTo(NO_CONTENT)
            answer.text.assert().isEmpty()
        }
        send { catalog.wait(PublishProduct, id) }
        val listing = server.http.get("/eshop-catalog/listings/$id").body!!
        listing["price"].decimalValue().assert().isEqualByComparingTo("4.00")
        server.http.get("/eshop-catalog/categories/$category/listings?limit=0").problem!!.let { refused ->
            refused.status.assert().isEqualTo(BAD_REQUEST)
            refused.code.assert().isEqualTo(Storefront.Refusals.LIMIT_OUT_OF_RANGE)
        }
    }

    /**
     * Over HTTP: the snapshot rebuild's command class (`RebuildSnapshot`, of `:spi`) is internal to Wow, which an
     * application does not opt in to, so the test sends it to its route.
     */
    @Test
    fun `the snapshot rebuild should be an employee's alone, not the anonymous caller's nor a buyer's (UI-50)`() {
        val rebuild = "/eshop-catalog/product/wow.snapshot.rebuild-snapshot"
        for (caller in listOf(Caller.ANONYMOUS, BUYER)) {
            server.http.post(rebuild, """{"ids":["p-1"]}""", caller = caller).problem!!
                .code.assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        }
        server.http.post(rebuild, """{"ids":["p-1"]}""", caller = EMPLOYEE).problem.assert().isNull()
    }

    @Test
    fun `the memory backend should serve its snapshot queries by default`() {
        val count = runBlocking {
            server.queries(Caller.ANONYMOUS).snapshots(ProductStateView::class.java).count(countQuery { })
        }
        count.count.assert().isGreaterThanOrEqualTo(0L)
    }

    private fun catalog(caller: Caller = Caller.ANONYMOUS): CommandClient = server.commands(caller)

    private fun product(id: String, caller: Caller = Caller.ANONYMOUS): Snapshot<ProductStateView>? =
        runBlocking { server.queries(caller).snapshots(ProductStateView::class.java).state(id) }

    private fun <T> send(send: suspend () -> T): T = runBlocking { send() }

    private fun rejected(send: suspend () -> Unit): CommandRejectedException =
        assertThrows<CommandRejectedException> { runBlocking { send() } }

    /** The violations (path to constraint) of the command [send] sends, which the gateway refuses as invalid. */
    private fun invalid(send: suspend () -> Unit): List<Pair<String, String>> {
        val refused = rejected(send)
        refused.code.assert().isEqualTo(FrameworkCodes.COMMAND_INVALID)
        return refused.result.error!!.violations.map { it.path to it.code }
    }

    private companion object {
        const val NO_CONTENT = 204
        const val BAD_REQUEST = 400
        const val CONFLICT = 409
        const val BRAND_NAME_TAKEN = "eshop-catalog.brand.name-taken"
    }
}
