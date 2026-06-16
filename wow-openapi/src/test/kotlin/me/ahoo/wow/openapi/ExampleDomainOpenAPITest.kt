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

package me.ahoo.wow.openapi

import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.test.asserts.assert
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.disable.DisabledRouteAggregate
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class ExampleDomainOpenAPITest {

    private val namedContext = MaterializedNamedBoundedContext("example-service")
    private lateinit var routerSpecs: RouterSpecs
    private lateinit var openAPI: OpenAPI

    @BeforeEach
    fun setUp() {
        routerSpecs = RouterSpecs(namedContext).build()
        openAPI = OpenAPI()
        routerSpecs.mergeOpenAPIFromCatalog(openAPI)
    }

    @Nested
    inner class RouterSpecsBuild {

        @Test
        fun `should discover order and cart aggregates`() {
            val aggregateTypes = MetadataSearcher.namedAggregateType
            aggregateTypes.values.assert().contains(Order::class.java)
            aggregateTypes.values.assert().contains(Cart::class.java)
        }

        @Test
        fun `should not generate routes for disabled route aggregate`() {
            val disabledPaths = catalogRoutes().filter {
                it.path.contains("disabled_route_aggregate")
            }
            disabledPaths.assert().isEmpty()
            MetadataSearcher.namedAggregateType.values.assert().contains(DisabledRouteAggregate::class.java)
        }

        @Test
        fun `should generate expected route count`() {
            catalogRoutes().assert().hasSizeGreaterThanOrEqualTo(20)
        }

        @Test
        fun `should set info title to context name`() {
            openAPI.info.assert().isNotNull()
            openAPI.info.title.assert().isEqualTo("example-service")
        }
    }

    @Nested
    inner class AggregateRoutes {

        @Test
        fun `should generate cart routes without default tenant path`() {
            // Cart has @StaticTenantId → default appendTenantPath=false
            // MockVariableCommand overrides with appendTenantPath=ALWAYS, so exclude it
            val cartRoutes = catalogRoutes().filter {
                it.path.contains("/cart") && !it.routeId.contains("mock_variable_command")
            }
            cartRoutes.assert().isNotEmpty()
            cartRoutes.forEach {
                it.path.assert().doesNotContain("tenant")
            }
        }

        @Test
        fun `should generate order routes with spaced resource name`() {
            val orderRoutes = catalogRoutes().filter {
                it.path.contains("sales-order")
            }
            orderRoutes.assert().isNotEmpty()
        }

        @Test
        fun `should set correct tags for cart`() {
            val cartRoutes = catalogRoutes().filter {
                it.path.contains("/cart")
            }
            val tagNames = cartRoutes.flatMap { it.tags.map { tag -> tag.name } }.toSet()
            tagNames.assert().contains("customer")
        }

        @Test
        fun `should set aggregate tags in open api`() {
            openAPI.tags.assert().isNotEmpty()
        }
    }

    @Nested
    inner class CommandRoutes {

        @Test
        fun `should generate create order as POST with empty action`() {
            val route = findRoute("example.order.create_order")
            route.assert().isNotNull()
            route!!.method.assert().isEqualTo(Https.Method.POST)
            route.path.assert().contains("sales-order")
            // Empty action → path ends at sales-order (no action suffix after resource name)
            route.path.assert().endsWith("/sales-order")
        }

        @Test
        fun `should generate change address as PUT`() {
            val route = findRoute("example.order.change_address")
            route.assert().isNotNull()
            route!!.method.assert().isEqualTo(Https.Method.PUT)
            route.path.assert().contains("address")
        }

        @Test
        fun `should generate ship order as POST with package action`() {
            val route = findRoute("example.order.ship_order")
            route.assert().isNotNull()
            route!!.method.assert().isEqualTo(Https.Method.POST)
            route.path.assert().contains("package")
        }

        @Test
        fun `should generate pay order as POST with pay action`() {
            val route = findRoute("example.order.pay_order")
            route.assert().isNotNull()
            route!!.method.assert().isEqualTo(Https.Method.POST)
            route.path.assert().contains("pay")
        }

        @Test
        fun `should generate add cart item as POST`() {
            val route = findRoute("example.cart.add_cart_item")
            route.assert().isNotNull()
            route!!.method.assert().isEqualTo(Https.Method.POST)
        }

        @Test
        fun `should generate view cart route`() {
            val route = findRoute("example.cart.view_cart")
            route.assert().isNotNull()
        }

        private fun findRoute(routeId: String) = routerSpecs.toRouteCatalog().routes.find {
            it.routeId == routeId
        }
    }

    private fun catalogRoutes() = routerSpecs.toRouteCatalog().routes

    @Nested
    inner class Schemas {

        @Test
        fun `should generate create order schema with fields`() {
            val schemas = openAPI.components.schemas
            schemas.assert().isNotEmpty()
            val createOrderSchema = schemas.entries.find {
                it.key.contains("CreateOrder")
            }
            createOrderSchema.assert().isNotNull()
            val properties = createOrderSchema!!.value.properties
            properties.assert().containsKey("items")
            properties.assert().containsKey("address")
            properties.assert().containsKey("fromCart")
        }

        @Test
        fun `should generate order created schema`() {
            val schemas = openAPI.components.schemas
            val orderCreatedSchema = schemas.entries.find {
                it.key.contains("OrderCreated")
            }
            orderCreatedSchema.assert().isNotNull()
        }

        @Test
        fun `should generate shipping address schema`() {
            val schemas = openAPI.components.schemas
            val addressSchema = schemas.entries.find {
                it.key.contains("ShippingAddress")
            }
            addressSchema.assert().isNotNull()
        }
    }

    @Nested
    inner class Components {

        @Test
        fun `should generate command header parameters`() {
            openAPI.components.parameters.assert().isNotEmpty()
        }

        @Test
        fun `should generate command responses`() {
            openAPI.components.responses.assert().isNotEmpty()
        }
    }
}
