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
}
