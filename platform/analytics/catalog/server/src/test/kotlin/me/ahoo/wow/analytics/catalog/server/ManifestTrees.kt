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

package me.ahoo.wow.analytics.catalog.server

import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree

/*
 * Peer Manifests as the catalog meets them, JSON read by the Manifest's reader (decision D283): a Manifest's
 * constructors are Wow's own, its contract is the JSON.
 */

internal fun manifest(
    contexts: List<Map<String, Any?>> = emptyList(),
    errors: List<Map<String, Any?>> = emptyList(),
    defs: Map<String, Any?> = emptyMap(),
): Manifest = ManifestTree.read(
    mapOf("format" to Manifest.FORMAT, "contexts" to contexts, "errors" to errors, "\$defs" to defs),
)

internal fun context(
    name: String,
    aggregates: List<Map<String, Any?>> = emptyList(),
    errors: List<Map<String, Any?>> = emptyList(),
): Map<String, Any?> = mapOf("name" to name, "aggregates" to aggregates, "errors" to errors)

internal fun aggregate(name: String, state: String, commands: List<Map<String, Any?>>): Map<String, Any?> =
    mapOf("name" to name, "state" to mapOf("schema" to state), "commands" to commands)

internal fun command(
    name: String,
    schema: String,
    creation: String = "none",
    mounted: Boolean = false,
    /** The command's routes, `METHOD path` each. */
    routes: List<String> = emptyList(),
): Map<String, Any?> = mapOf(
    "name" to name,
    "schema" to schema,
    "creation" to creation,
    "mounted" to mounted,
    "routes" to routes.map { mapOf("method" to it.substringBefore(' '), "path" to it.substringAfter(' ')) },
)

internal fun error(code: String, status: Int): Map<String, Any?> = mapOf("code" to code, "status" to status)
