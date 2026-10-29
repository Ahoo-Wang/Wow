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

package me.ahoo.wow.example.eshop.catalog.domain

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SnapshotProperties
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.dsl.listQuery
import me.ahoo.wow.example.eshop.catalog.api.EshopCatalogContract
import me.ahoo.wow.example.eshop.catalog.api.product.ProductStatus
import me.ahoo.wow.example.eshop.catalog.domain.brand.BrandStateProperties
import me.ahoo.wow.example.eshop.catalog.domain.category.CategoryStateProperties
import me.ahoo.wow.example.eshop.catalog.domain.product.ProductStateProperties
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal

/**
 * The compiler's `<State>Properties` of the catalog's states (the module depends on `:query-api`, so the compiler
 * generates them): they name exactly the paths the state schemas of the module's completed Manifest fragment describe,
 * and the query DSL takes them. The storefront's queries run with them on a backend in `CatalogQueryContract` and
 * `CatalogPostgresTest`.
 */
class StatePropertiesTest {
    private val fragment: JsonNode = JsonMapper.builder().build().readTree(
        checkNotNull(javaClass.classLoader.getResource("META-INF/wow/manifest/eshop-catalog-domain.json")).readText(),
    )

    @Test
    fun `each state's properties should be the paths of its schema`() {
        listOf(
            ProductStateProperties to "me.ahoo.wow.example.eshop.catalog.domain.product.ProductState",
            BrandStateProperties to "me.ahoo.wow.example.eshop.catalog.domain.brand.BrandState",
            CategoryStateProperties to "me.ahoo.wow.example.eshop.catalog.domain.category.CategoryState",
        ).forEach { (properties, state) ->
            stateFields(properties).assert().describedAs(state)
                .containsExactlyInAnyOrderElementsOf(schemaPaths(definition(state), "state"))
        }
    }

    @Test
    fun `the api's generated contract should name the same fields alike (D259)`() {
        listOf(
            ProductStateProperties to EshopCatalogContract.Product,
            BrandStateProperties to EshopCatalogContract.Brand,
            CategoryStateProperties to EshopCatalogContract.Category,
        ).forEach { (properties, contract) ->
            constants(contract).assert().describedAs(contract.javaClass.name).isEqualTo(constants(properties))
        }
        EshopCatalogContract.Product.TYPE.assert().isEqualTo(AggregateType("eshop-catalog", "product"))
    }

    /** The query field constants a generated object declares itself, name to path. */
    private fun constants(properties: SnapshotProperties): Map<String, String> =
        properties.javaClass.declaredFields
            .filter { it.type == QueryField::class.java }
            .associate { it.name to (it.apply { isAccessible = true }.get(properties) as QueryField<*>).path }

    @Test
    fun `the query DSL should take them`() {
        val query = listQuery {
            filter {
                ProductStateProperties.STATE__CATEGORY_ID eq "mugs"
                ProductStateProperties.STATE__STATUS eq ProductStatus.PUBLISHED
                ProductStateProperties.STATE__PRICE gte BigDecimal.TEN
            }
            sort { ProductStateProperties.STATE__PRICE.asc() }
        }
        query.assert().isEqualTo(
            ListQuery.of(
                AndFilter.of(
                    listOf(
                        EqualFilter.of(QueryField("state.categoryId"), "mugs"),
                        EqualFilter.of(QueryField("state.status"), ProductStatus.PUBLISHED),
                        GreaterThanOrEqualFilter.of(QueryField("state.price"), BigDecimal.TEN),
                    ),
                ),
                sort = listOf(Sort.of(QueryField("state.price"), Sort.Direction.ASC)),
            ),
        )
        // The snapshot's own fields come with them.
        ProductStateProperties.TENANT_ID.assert().isEqualTo(SnapshotProperties.TENANT_ID)
    }

    /** The constants a generated object declares itself: the state's paths. */
    private fun stateFields(properties: SnapshotProperties): List<String> =
        properties.javaClass.declaredFields
            .filter { it.type == QueryField::class.java }
            .map { (it.apply { isAccessible = true }.get(properties) as QueryField<*>).path }

    private fun definition(name: String): JsonNode = fragment.path("\$defs").path(name)

    /** Every path below [prefix] the object schema [schema] describes, through references, unions and arrays. */
    private fun schemaPaths(schema: JsonNode, prefix: String): List<String> {
        val target = resolved(schema)
        return target.path("properties").properties().flatMap { (name, property) ->
            val path = "$prefix.$name"
            listOf(path) + schemaPaths(property, path)
        }
    }

    private fun JsonNode.type(): String? = path("type").takeIf { it.isString }?.asString()

    private fun resolved(schema: JsonNode): JsonNode {
        schema.path("\$ref").takeIf { it.isString }?.let {
            return resolved(definition(it.asString().removePrefix("#/\$defs/")))
        }
        schema.path("anyOf").takeIf { it.isArray }?.let { union ->
            return union.firstOrNull { it.type() != "null" }?.let(::resolved) ?: schema
        }
        if (schema.type() == "array" || schema.path("items").isObject) {
            return resolved(schema.path("items"))
        }
        return schema
    }
}
