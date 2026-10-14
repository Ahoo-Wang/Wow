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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SnapshotProperties
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.dsl.listQuery
import me.ahoo.wow.example.shop.api.order.OrderStatus
import me.ahoo.wow.example.shop.domain.cart.CartStateProperties
import me.ahoo.wow.example.shop.domain.customer.CustomerStateProperties
import me.ahoo.wow.example.shop.domain.order.OrderStateProperties
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * The compiler's `<State>Properties` of the shop's states name exactly the paths the state schemas of the module's
 * completed Manifest fragment describe, and the query DSL takes them.
 */
class StatePropertiesTest {
    private val fragment: JsonNode = JsonMapper.builder().build().readTree(
        checkNotNull(javaClass.classLoader.getResource("META-INF/wow/manifest/shop-domain.json")).readText(),
    )

    @Test
    fun `each state's properties are the paths of its schema`() {
        listOf(
            OrderStateProperties to "me.ahoo.wow.example.shop.domain.order.OrderState",
            CartStateProperties to "me.ahoo.wow.example.shop.domain.cart.CartState",
            CustomerStateProperties to "me.ahoo.wow.example.shop.domain.customer.CustomerState",
        ).forEach { (properties, state) ->
            stateFields(properties).assert().describedAs(state)
                .containsExactlyInAnyOrderElementsOf(schemaPaths(definition(state), "state"))
        }
    }

    @Test
    fun `the query DSL takes them`() {
        val query = listQuery {
            filter {
                OrderStateProperties.STATE__STATUS isIn listOf(OrderStatus.PAID, OrderStatus.SHIPPED)
                OrderStateProperties.STATE__ITEMS.elementMatch { OrderStateProperties.STATE__ITEMS__PRODUCT_ID eq "p1" }
            }
            sort { OrderStateProperties.LAST_EVENT_TIME.desc() }
        }
        query.assert().isEqualTo(
            ListQuery(
                AndFilter(
                    listOf(
                        InFilter(QueryField("state.status"), listOf(OrderStatus.PAID, OrderStatus.SHIPPED)),
                        ElementMatchFilter(
                            QueryField("state.items"),
                            EqualFilter(QueryField("productId"), "p1"),
                        ),
                    ),
                ),
                sort = listOf(Sort(QueryField("lastEventTime"), Sort.Direction.DESC)),
            ),
        )
        OrderStateProperties.TENANT_ID.assert().isEqualTo(SnapshotProperties.TENANT_ID)
    }

    /** The constants a generated object declares itself: the state's paths. */
    private fun stateFields(properties: SnapshotProperties): List<String> =
        properties.javaClass.declaredFields
            .filter { it.type == QueryField::class.java }
            .map { (it.apply { isAccessible = true }.get(properties) as QueryField).path }

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
