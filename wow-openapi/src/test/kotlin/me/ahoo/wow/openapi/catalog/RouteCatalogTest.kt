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

package me.ahoo.wow.openapi.catalog

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.commandRouteMetadata
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test

internal class RouteCatalogTest {
    @Test
    fun `should sort routes deterministically`() {
        val catalog = RouteCatalog(
            listOf(
                HttpRouteContract(routeId = "b", method = "POST", path = "/b", handlerKey = "b"),
                HttpRouteContract(routeId = "a", method = "GET", path = "/a", handlerKey = "a")
            )
        )

        catalog.routes.map { it.routeId }.assert().isEqualTo(listOf("a", "b"))
    }

    @Test
    fun `should sort command routes before non command routes`() {
        val catalog = RouteCatalog(
            listOf(
                HttpRouteContract(
                    routeId = "cart.event.count",
                    method = "POST",
                    path = "/cart/event/count",
                    handlerKey = "event.count"
                ),
                HttpRouteContract(
                    routeId = "cart.snapshot.count",
                    method = "POST",
                    path = "/cart/snapshot/count",
                    handlerKey = "snapshot.count"
                ),
                commandRoute(
                    routeId = "cart.add_cart_item",
                    path = "/owner/{ownerId}/cart/add_cart_item"
                )
            )
        )

        catalog.routes.map { it.routeId }.assert()
            .isEqualTo(listOf("cart.add_cart_item", "cart.event.count", "cart.snapshot.count"))
    }

    @Test
    fun `should reject duplicate path and method`() {
        assertThrownBy<IllegalArgumentException> {
            RouteCatalog(
                listOf(
                    HttpRouteContract(routeId = "first", method = "GET", path = "/same", handlerKey = "first"),
                    HttpRouteContract(routeId = "second", method = "GET", path = "/same", handlerKey = "second")
                )
            )
        }
    }

    @Test
    fun `should reject missing handler key`() {
        assertThrownBy<IllegalArgumentException> {
            RouteCatalog(listOf(HttpRouteContract(routeId = "route", method = "GET", path = "/route", handlerKey = "")))
        }
    }

    @Test
    fun `should reject path variable mismatch`() {
        assertThrownBy<IllegalArgumentException> {
            RouteCatalog(
                listOf(
                    HttpRouteContract(
                        routeId = "route",
                        method = "GET",
                        path = "/route/{id}",
                        handlerKey = "route",
                        parameters = listOf(HttpParameter(name = "otherId", location = HttpParameterLocation.PATH))
                    )
                )
            )
        }
    }

    @Test
    fun `should sort contributors in explicit order`() {
        val first = testContributor(id = "first", order = 20)
        val second = testContributor(id = "second", order = 10)
        val sameOrder = testContributor(id = "same-order", order = 10)

        val contributors = RouteContributors.sort(listOf(first, sameOrder, second))

        contributors.map { it.id }.assert().isEqualTo(listOf("same-order", "second", "first"))
    }

    @Test
    fun `should contribute empty route lists by default`() {
        val contributor = testContributor(id = "empty", order = 0)
        val componentContext = OpenAPIComponentContext.default(false)
        val aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()

        contributor.contributeGlobal(MOCK_AGGREGATE_METADATA, componentContext).assert().isEmpty()
        contributor.contributeAggregate(
            MOCK_AGGREGATE_METADATA,
            aggregateRouteMetadata,
            componentContext
        ).assert().isEmpty()
    }

    private fun testContributor(id: String, order: Int): RouteContributor {
        return object : RouteContributor {
            override val id: String = id
            override val category: RouteCategory = RouteCategory.GLOBAL
            override val order: Int = order
        }
    }

    private fun commandRoute(routeId: String, path: String): HttpRouteContract {
        return HttpRouteContract(
            routeId = routeId,
            method = "POST",
            path = path,
            handlerKey = "command",
            parameters = listOf(HttpParameter("ownerId", HttpParameterLocation.PATH)),
            handlerMetadata = HttpRouteHandlerMetadata.Command(
                aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata(),
                commandRouteMetadata = MockCreateAggregate::class.java.commandRouteMetadata()
            )
        )
    }
}
