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

package me.ahoo.wow.manifest.tools

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.RouteParameterManifest
import me.ahoo.wow.manifest.ServiceRouteManifest
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.BASELINE
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.INTEGER
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.STRING
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.context
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.def
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.schema
import org.junit.jupiter.api.Test

/** A service route's changes ([compareServiceRoute]): its request an input, its response an output. */
class ServiceRouteCompatibilityTest {
    private val summary = ServiceRouteManifest(
        name = "summary",
        route = RouteManifest("GET", "/shop/summaries/{day}"),
        parameters = listOf(
            RouteParameterManifest("day", RouteParameterManifest.Location.PATH, mapOf("type" to "string")),
            RouteParameterManifest("limit", RouteParameterManifest.Location.QUERY, INTEGER, required = false),
        ),
        request = Manifest.refSchema("shop.Filter"),
        response = Manifest.arraySchema(Manifest.refSchema("shop.Summary")),
        errors = listOf("shop.summary.closed"),
    )

    private val routeBaseline = BASELINE.context { it.copyWith(routes = listOf(summary)) }
        .def("shop.Filter", schema("status" to STRING))
        .def("shop.Summary", schema("total" to INTEGER, required = listOf("total")))

    private fun routeChanges(current: Manifest) =
        ManifestCompatibility.compare(routeBaseline, current).changes.map { it.toString() }

    private fun route(change: (ServiceRouteManifest) -> ServiceRouteManifest) =
        routeBaseline.context { it.copyWith(routes = it.routes.map(change)) }

    @Test
    fun `a service route's changes should be classified, its request an input and its response an output`() {
        routeChanges(routeBaseline.context { it.copyWith(routes = emptyList()) }).assert()
            .containsExactly("BREAKING contexts[shop].routes[summary]: service route removed")
        routeChanges(route { it.copyWith(route = RouteManifest("GET", "/shop/days/{day}")) }).assert().containsExactly(
            "BREAKING contexts[shop].routes[summary].route: route changed from GET /shop/summaries/{day} to " +
                "GET /shop/days/{day}",
        )
        routeChanges(
            route { it.copyWith(parameters = it.parameters.map { p -> p.copyWith(required = true) }) }
        ).assert()
            .containsExactly("BREAKING contexts[shop].routes[summary].parameters[query:limit]: parameter made required")
        routeChanges(
            route {
                it.copyWith(
                    parameters = it.parameters +
                        RouteParameterManifest("page", RouteParameterManifest.Location.QUERY, INTEGER, required = true),
                )
            },
        ).assert().contains("BREAKING contexts[shop].routes[summary].parameters[query:page]: required parameter added")
        // The request may accept more; the response may not produce more.
        routeChanges(routeBaseline.def("shop.Filter", schema("status" to STRING, "since" to STRING))).assert()
            .containsExactly("ADDITIVE contexts[shop].routes[summary].request.since: optional field added")
        routeChanges(routeBaseline.def("shop.Summary", schema("total" to INTEGER))).assert()
            .containsExactly("BREAKING contexts[shop].routes[summary].response[].total: field made optional")
        routeChanges(route { it.copyWith(response = null) }).assert()
            .containsExactly("BREAKING contexts[shop].routes[summary].response: body removed")
    }

    @Test
    fun `a path variable and a query parameter of one name should each be compared with its own`() {
        val lookup = summary.copyWith(
            route = RouteManifest("GET", "/shop/summaries/{day}"),
            parameters = listOf(
                RouteParameterManifest("day", RouteParameterManifest.Location.PATH, STRING),
                RouteParameterManifest("day", RouteParameterManifest.Location.QUERY, STRING, required = false),
            ),
        )
        val baseline = routeBaseline.context { it.copyWith(routes = listOf(lookup)) }
        ManifestCompatibility.compare(baseline, baseline).changes.assert().isEmpty()
        val required = baseline.context {
            it.copyWith(
                routes = listOf(
                    lookup.copyWith(parameters = lookup.parameters.map { p -> p.copyWith(required = true) })
                )
            )
        }
        ManifestCompatibility.compare(baseline, required).changes.map { it.toString() }.assert()
            .containsExactly("BREAKING contexts[shop].routes[summary].parameters[query:day]: parameter made required")
        val withoutQuery = baseline.context {
            it.copyWith(routes = listOf(lookup.copyWith(parameters = lookup.parameters.take(1))))
        }
        ManifestCompatibility.compare(baseline, withoutQuery).changes.map { it.toString() }.assert()
            .containsExactly("BREAKING contexts[shop].routes[summary].parameters[query:day]: parameter removed")
        ManifestCompatibility.compare(withoutQuery, baseline).changes.map { it.toString() }.assert()
            .containsExactly("ADDITIVE contexts[shop].routes[summary].parameters[query:day]: parameter added")
    }

    @Test
    fun `a route's number narrowed should break its request, widened its response`() {
        val long = mapOf("type" to "integer", "format" to "int64")
        val int = mapOf("type" to "integer", "format" to "int32")
        val baseline = routeBaseline.def("shop.Filter", schema("amount" to long))
            .def("shop.Summary", schema("total" to int, required = listOf("total")))
        ManifestCompatibility.compare(baseline, baseline.def("shop.Filter", schema("amount" to int))).changes
            .map { it.toString() }.assert()
            .containsExactly(
                "BREAKING contexts[shop].routes[summary].request.amount: format changed from int64 to int32"
            )
        ManifestCompatibility.compare(
            baseline,
            baseline.def("shop.Summary", schema("total" to long, required = listOf("total"))),
        ).changes.map { it.toString() }.assert()
            .containsExactly(
                "BREAKING contexts[shop].routes[summary].response[].total: format changed from int32 to int64"
            )
    }

    @Test
    fun `a response made optional should break its readers, made required should not`() {
        val optional = route { it.copyWith(responseOptional = true) }
        routeChanges(optional).assert().containsExactly(
            "BREAKING contexts[shop].routes[summary].response: response made optional: the route may answer 204 No Content",
        )
        ManifestCompatibility.compare(optional, routeBaseline).changes.map { it.toString() }.assert().containsExactly(
            "ADDITIVE contexts[shop].routes[summary].response: response made required: the route always answers its body",
        )
        ManifestCompatibility.compare(optional, optional).changes.assert().isEmpty()
    }

    @Test
    fun `a service route's parameters, bodies and refusal codes should be classified`() {
        routeChanges(
            route {
                it.copyWith(
                    parameters = listOf(
                        RouteParameterManifest(
                            "day",
                            RouteParameterManifest.Location.QUERY,
                            mapOf("type" to "string"),
                            true
                        ),
                    ),
                )
            },
        ).assert().containsExactlyInAnyOrder(
            "BREAKING contexts[shop].routes[summary].parameters[path:day]: parameter removed",
            "ADDITIVE contexts[shop].routes[summary].parameters[query:day]: parameter added",
            "BREAKING contexts[shop].routes[summary].parameters[query:day]: required parameter added",
            "BREAKING contexts[shop].routes[summary].parameters[query:limit]: parameter removed",
        )
        routeChanges(
            route {
                it.copyWith(
                    parameters = it.parameters.map { p -> p.copyWith(schema = mapOf("type" to "string")) }
                )
            }
        )
            .assert().contains(
                "BREAKING contexts[shop].routes[summary].parameters[query:limit]: parameter schema changed from " +
                    "{type=integer} to {type=string}",
            )
        routeChanges(route { it.copyWith(request = null) }).assert()
            .containsExactly("BREAKING contexts[shop].routes[summary].request: body removed")
        routeChanges(route { it.copyWith(errors = it.errors + "shop.summary.late") }).assert()
            .containsExactly("ADDITIVE contexts[shop].routes[summary].errors: refusal code shop.summary.late added")
        routeChanges(
            routeBaseline.context { it.copyWith(routes = it.routes + it.routes.single().copyWith(name = "today")) }
        )
            .assert().containsExactly("ADDITIVE contexts[shop].routes[today]: service route added")
        routeChanges(route { it.copyWith(errors = emptyList()) }).assert().containsExactly(
            "ADDITIVE contexts[shop].routes[summary].errors: refusal code shop.summary.closed no longer listed",
        )
    }
}
