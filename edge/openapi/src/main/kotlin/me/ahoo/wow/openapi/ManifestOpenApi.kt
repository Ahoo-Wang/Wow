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

import me.ahoo.wow.manifest.Manifest

/**
 * The OpenAPI 3.1 document of a service's public routes, from its Manifest alone (roadmap 5a.2, decisions D17, D74):
 * the routes `wow-http` materializes from the same Manifest, with their headers, bodies and problem+json errors.
 *
 * - Every route of every command (`CommandManifest.routes`, decisions D104, D176, D195): with the aggregate id in the
 *   path and, for a creation or a mounted command (decision D182), without it; the Wow headers (`Wow-Request-Id`,
 *   `Wow-Wait`, `Wow-Wait-Timeout`, `Wow-Scope`, `Wow-Space`, `If-Match`), the command's schema as the body, the
 *   [command result][WowSchemas.COMMAND_RESULT] as JSON or as server-sent events, one per hop.
 * - The lifecycle commands each aggregate lists (decision D113), the state reads and the trace (decisions D40, D112),
 *   the query routes of each query model (decisions D127, D128, D131) and their descriptors.
 * - The framework's endpoints under `/wow`: the Manifest, this document, a global id and the datasets (decision D145),
 *   as [OpenApiOptions] says the host serves them.
 * - Errors as RFC 9457 problems, one response per status listing the codes answered with it ([ErrorResponses]): the
 *   framework's table (`wow.storage.unavailable`, `wow.schedule.not-due`, ...) and each context's domain codes.
 * - `components.schemas`: the Manifest's JSON Schemas ([SchemaComponents]) and the framework's ([WowSchemas]).
 *
 * The document is a tree of plain values, as [me.ahoo.wow.manifest.ManifestTree] writes the Manifest: any JSON library
 * writes it. The same Manifest and options always give the same document.
 */
object ManifestOpenApi {
    /** The OpenAPI version of the document. */
    const val OPENAPI_VERSION: String = "3.1.0"

    /** The dialect of the document's schemas, the Manifest's (decision D73). */
    const val JSON_SCHEMA_DIALECT: String = "https://json-schema.org/draft/2020-12/schema"

    /** The `info.version` of a document whose Manifest names no service version. */
    const val UNVERSIONED: String = "unversioned"

    /** The document of [manifest]'s routes, as the host serves them per [options]. */
    fun write(manifest: Manifest, options: OpenApiOptions = OpenApiOptions()): Map<String, Any?> {
        val schemas = SchemaComponents(manifest.defs, WowSchemas.all().keys)
        val routes = OpenApiPaths(manifest, options, schemas)
        val paths = routes.write()
        return Json.obj(
            "openapi" to OPENAPI_VERSION,
            "jsonSchemaDialect" to JSON_SCHEMA_DIALECT,
            "info" to Json.obj(
                "title" to (manifest.service?.name ?: options.title),
                "version" to (manifest.service?.version ?: UNVERSIONED),
                "description" to "The public routes of the service, generated from its Wow Manifest " +
                    "(format ${manifest.format}).",
            ),
            "servers" to options.servers.map { Json.obj("url" to it) }.takeIf { it.isNotEmpty() },
            "tags" to tags(manifest),
            "paths" to paths,
            "components" to Json.obj(
                Json.SCHEMAS to WowSchemas.all() + schemas.schemas,
                ErrorResponses.RESPONSES to routes.responses,
                Json.PARAMETERS to OpenApiParameters.all(),
                Json.HEADERS to OpenApiParameters.headers(),
            ),
        )
    }

    /** One tag per aggregate, `<context>.<aggregate>`, then the framework's, [FRAMEWORK_TAG]. */
    private fun tags(manifest: Manifest): List<Map<String, Any?>> = manifest.contexts.flatMap { context ->
        context.aggregates.map { aggregate ->
            Json.obj(
                "name" to "${context.name}.${aggregate.name}",
                "description" to (aggregate.description ?: context.description),
            )
        } + listOfNotNull(
            Json.obj(
                "name" to "${context.name}.${WowSchemas.JOB_AGGREGATE}",
                "description" to "The context's scheduled jobs (decision D198).",
            ).takeIf { context.subscriptions.any { it.job != null } },
            Json.obj(
                "name" to "${context.name}.${WowSchemas.REBUILD_AGGREGATE}",
                "description" to "The rebuild of the context's snapshots (decision D172).",
            ).takeIf { context.aggregates.any { it.snapshot.enabled } },
        )
    } + Json.obj("name" to FRAMEWORK_TAG, "description" to "The framework's own endpoints.")

    /** The tag of the framework's own endpoints. */
    const val FRAMEWORK_TAG: String = "wow"
}
