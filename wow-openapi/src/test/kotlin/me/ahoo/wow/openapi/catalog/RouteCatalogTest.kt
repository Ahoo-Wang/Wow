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
        }.hasMessage("Duplicate route [GET /same]: first, second.")
    }

    @Test
    fun `should try a contained template before the template containing it`() {
        val command = commandRoute(
            routeId = "cart.count_items",
            path = "/owner/{ownerId}/cart/{id}/count",
            pathVariables = listOf("ownerId", "id")
        )
        val snapshotCount = query(routeId = "cart.snapshot.count", path = "/owner/{ownerId}/cart/snapshot/count")
        val eventCount = query(routeId = "cart.event.count", path = "/owner/{ownerId}/cart/event/count")

        val catalog = RouteCatalog(listOf(command, snapshotCount, eventCount))

        catalog.routes.map { it.routeId }.assert()
            .isEqualTo(listOf("cart.count_items", "cart.event.count", "cart.snapshot.count"))
        catalog.dispatchRoutes.map { it.routeId }.assert()
            .isEqualTo(listOf("cart.event.count", "cart.snapshot.count", "cart.count_items"))
    }

    @Test
    fun `should keep the catalog order of crossing templates`() {
        // `POST /cart/state/items/5` matches both; neither template contains the other, so the command listed
        // first keeps it (id = state), as before.
        val command = commandRoute(
            routeId = "cart.remove_item",
            path = "/cart/{id}/items/{itemId}",
            pathVariables = listOf("id", "itemId")
        )
        val resend = query(routeId = "cart.state.resend", path = "/cart/state/{afterId}/{limit}")
        val regenerate = query(routeId = "cart.snapshot.batch_regenerate", path = "/cart/snapshot/{afterId}/{limit}")
            .copy(method = "PUT")
        val compensate = query(routeId = "cart.compensate", path = "/cart/{id}/{version}/compensate")
            .copy(method = "PUT")

        val catalog = RouteCatalog(listOf(resend, compensate, regenerate, command))

        catalog.dispatchRoutes.assert().isEqualTo(catalog.routes)
        catalog.dispatchRoutes.map { it.routeId }.assert().isEqualTo(
            listOf("cart.remove_item", "cart.snapshot.batch_regenerate", "cart.state.resend", "cart.compensate")
        )
    }

    @Test
    fun `should reject templates that differ only in variable names`() {
        val command = commandRoute(
            routeId = "cart.count_items",
            path = "/owner/{ownerId}/cart/{id}/count",
            pathVariables = listOf("ownerId", "id")
        )
        val other = query(routeId = "cart.other.count", path = "/owner/{ownerId}/cart/{cartId}/count")

        assertThrownBy<IllegalArgumentException> {
            RouteCatalog(listOf(other, command))
        }.hasMessage(
            "Ambiguous routes [POST /owner/{}/cart/{}/count]: " +
                "[cart.count_items POST /owner/{ownerId}/cart/{id}/count " +
                "(aggregate mock_aggregate, command me.ahoo.wow.tck.mock.MockCreateAggregate)], " +
                "[cart.other.count POST /owner/{ownerId}/cart/{cartId}/count] match the same paths, " +
                "so only the first could ever be dispatched. Give one of them a distinct path."
        )
    }

    @Test
    fun `should accept templates of the same shape under different methods`() {
        val put = query(routeId = "put", path = "/cart/{id}/count").copy(method = "PUT")
        val post = query(routeId = "post", path = "/cart/{cartId}/count")

        RouteCatalog(listOf(put, post)).routes.assert().hasSize(2)
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
    fun `should contribute empty route lists by default`() {
        val contributor = object : RouteContributor {}
        val aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()

        contributor.contributeGlobal(MOCK_AGGREGATE_METADATA).assert().isEmpty()
        contributor.contributeAggregate(MOCK_AGGREGATE_METADATA, aggregateRouteMetadata).assert().isEmpty()
    }

    private fun query(routeId: String, path: String): HttpRouteContract {
        return HttpRouteContract(
            routeId = routeId,
            method = "POST",
            path = path,
            handlerKey = routeId,
            parameters = Regex("\\{([^}]+)}").findAll(path)
                .map { HttpParameter(it.groupValues[1], HttpParameterLocation.PATH) }
                .toList()
        )
    }

    private fun commandRoute(
        routeId: String,
        path: String,
        pathVariables: List<String> = listOf("ownerId")
    ): HttpRouteContract {
        return HttpRouteContract(
            routeId = routeId,
            method = "POST",
            path = path,
            handlerKey = "command",
            parameters = pathVariables.map { HttpParameter(it, HttpParameterLocation.PATH) },
            handlerMetadata = HttpRouteHandlerMetadata.Command(
                aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata(),
                commandRouteMetadata = MockCreateAggregate::class.java.commandRouteMetadata()
            )
        )
    }
}
