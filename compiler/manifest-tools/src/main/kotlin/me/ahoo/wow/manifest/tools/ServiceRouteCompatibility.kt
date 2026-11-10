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

import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.RouteParameterManifest
import me.ahoo.wow.manifest.ServiceRouteManifest

// The comparison of a service route ([ServiceRouteManifest]) with its baseline, as [ManifestCompatibility] states it.

internal fun Changes.compareServiceRoute(path: String, baseline: ServiceRouteManifest, current: ServiceRouteManifest) {
    if (route(baseline.route) != route(current.route)) {
        breaking("$path.route", "route changed from ${route(baseline.route)} to ${route(current.route)}")
    }
    compareNamed(
        "$path.parameters",
        baseline.parameters,
        current.parameters,
        ::parameter,
        "parameter",
        { entry, old, new ->
            if (new.required && !old.required) breaking(entry, "parameter made required")
            if (old.schema != new.schema) {
                breaking(entry, "parameter schema changed from ${old.schema} to ${new.schema}")
            }
        },
    )
    val before = baseline.parameters.map(::parameter).toSet()
    current.parameters.filter { new -> new.required && parameter(new) !in before }
        .forEach { breaking("$path.parameters[${parameter(it)}]", "required parameter added") }
    compareBody("$path.request", baseline.request, current.request, SchemaDirection.INPUT)
    compareBody("$path.response", baseline.response, current.response, SchemaDirection.OUTPUT)
    (baseline.errors - current.errors.toSet()).forEach { additive("$path.errors", "refusal code $it no longer listed") }
    (current.errors - baseline.errors.toSet()).forEach { additive("$path.errors", "refusal code $it added") }
}

/**
 * A parameter's identity: where it is read from and its name, `query:limit`. A path variable and a query parameter
 * may share a name; a parameter moved from one to the other is one removed and another added.
 */
private fun parameter(parameter: RouteParameterManifest): String =
    "${ManifestTree.wire(parameter.location)}:${parameter.name}"

private fun Changes.compareBody(
    path: String,
    baseline: Map<String, Any?>?,
    current: Map<String, Any?>?,
    direction: SchemaDirection,
) {
    when {
        baseline != null && current != null -> list += schemas.compareInline(path, baseline, current, direction)
        baseline != current -> breaking(path, if (current == null) "body removed" else "body added")
    }
}
