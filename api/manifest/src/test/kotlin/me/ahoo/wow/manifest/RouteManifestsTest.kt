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

package me.ahoo.wow.manifest

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** A service route and its parameters are values: equal by every field, and told by every field in a failure. */
class RouteManifestsTest {
    private val parameter = RouteParameterManifest(
        "day",
        RouteParameterManifest.Location.PATH,
        mapOf("type" to "string")
    )
    private val route = ServiceRouteManifest(
        name = "summary",
        route = RouteManifest("GET", "/shop/summaries/{day}"),
        parameters = listOf(parameter),
        response = Manifest.refSchema("shop.Summary"),
    )

    @Test
    fun `a service route should equal itself and an equal route only`() {
        route.assert().isEqualTo(route).isEqualTo(route.copyWith()).isNotEqualTo(route.copyWith(name = "today"))
        route.equals("summary").assert().isFalse()
        route.hashCode().assert().isEqualTo(route.copyWith().hashCode())
        route.toString().assert().isEqualTo(
            "ServiceRouteManifest(name=summary, route=RouteManifest(method=GET, path=/shop/summaries/{day}), " +
                "parameters=[RouteParameterManifest(name=day, location=PATH, schema={type=string}, required=true)], " +
                "request=null, response={\$ref=#/\$defs/shop.Summary}, responseOptional=false, errors=[], description=null, " +
                "deprecated=false)",
        )
    }

    @Test
    fun `a route parameter should equal itself and an equal parameter only`() {
        parameter.assert().isEqualTo(
            parameter
        ).isEqualTo(parameter.copyWith()).isNotEqualTo(parameter.copyWith(name = "night"))
        parameter.equals("day").assert().isFalse()
        parameter.hashCode().assert().isEqualTo(parameter.copyWith().hashCode())
    }
}
