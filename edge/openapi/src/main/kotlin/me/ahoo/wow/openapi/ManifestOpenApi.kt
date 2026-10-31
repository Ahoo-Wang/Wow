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
import me.ahoo.wow.manifest.withoutClassNames

/**
 * The OpenAPI 3.1 document of a service's public routes, from its Manifest alone (.2):
 * the routes `wow-http` materializes from the same Manifest, with their headers, bodies and problem+json errors.
 *
 * - Every route of every command (`CommandManifest.routes`): with the aggregate id in the
 *   path and, for a creation or a mounted command, without it; the Wow headers (`Wow-Request-Id`,
 *   `Wow-Wait`, `Wow-Wait-Timeout`, `Wow-Scope`, `Wow-Space`, `If-Match`), the command's schema as the body, the
 *   [command result][WowSchemas.COMMAND_RESULT] as JSON or as server-sent events, one per hop.
 * - The lifecycle commands each aggregate lists, the state reads and the trace,
 *   the query routes of each query model and their descriptors.
 * - The framework's endpoints under `/wow`: the Manifest, this document and the datasets,
 *   as [OpenApiOptions] says the host serves them.
 * - Errors as RFC 9457 problems, one response per status listing the codes answered with it ([ErrorResponses]): the
 *   framework's table (`wow.storage.unavailable`, `wow.schedule.not-due`, ...) and each context's domain codes.
 * - The security schemes of [OpenApiOptions.securitySchemes], under `components.securitySchemes`, and
 *   the global `security`: one requirement naming them all, which a caller presents together; none when the options
 *   have none.
 * - No `jsonSchemaDialect`: the schemas are JSON Schema 2020-12 ([JSON_SCHEMA_DIALECT]), which OpenAPI 3.1's default
 *   dialect, `https://spec.openapis.org/oas/3.1/dialect/base`, extends with OpenAPI's own keywords, so each reads the
 *   same under it; declaring 2020-12 instead adds nothing a reader uses, and Swagger UI refuses any dialect but the
 *   default.
 * - `components.schemas`: the Manifest's JSON Schemas ([SchemaComponents]) and the framework's ([WowSchemas]); the
 *   body of a lifecycle command is the Manifest's when its `$defs` holds it under the command's name (.1c).
 *
 * The document is a tree of plain values, as [me.ahoo.wow.manifest.ManifestTree] writes the Manifest: any JSON library
 * writes it. The same Manifest and options always give the same document.
 */
object ManifestOpenApi {
    /** The OpenAPI version of the document. */
    const val OPENAPI_VERSION: String = "3.1.0"

    /**
     * The dialect the document's schemas are written in, the Manifest's. The document does not declare it: OpenAPI
     * 3.1's default dialect extends it.
     */
    const val JSON_SCHEMA_DIALECT: String = "https://json-schema.org/draft/2020-12/schema"

    /** The `info.version` of a document whose Manifest names no service version. */
    const val UNVERSIONED: String = "unversioned"

    /**
     * The document of [manifest]'s routes, as the host serves them per [options]: a public document, so its schemas
     * name no JVM class ([withoutClassNames]).
     */
    fun write(served: Manifest, options: OpenApiOptions = OpenApiOptions()): Map<String, Any?> {
        val manifest = served.withoutClassNames()
        // A lifecycle command's body the Manifest holds (keyed by its name) is the Manifest's, in place of the
        // framework's; the framework's describes the other system commands' bodies, with their bounds.
        val framework = WowSchemas.all().filterKeys { it !in WowSchemas.LIFECYCLE || it !in manifest.defs }
        val defs = manifest.defs.filterKeys { it !in WowSchemas.SYSTEM_COMMANDS || it in WowSchemas.LIFECYCLE }
        val schemas = SchemaComponents(defs, framework.keys)
        val routes = OpenApiPaths(manifest, options, schemas)
        val paths = routes.write()
        return Json.obj(
            "openapi" to OPENAPI_VERSION,
            "info" to Json.obj(
                "title" to (manifest.service?.name ?: options.title),
                "version" to (manifest.service?.version ?: UNVERSIONED),
                "description" to "The public routes of the service, generated from its Wow Manifest " +
                    "(format ${manifest.format}).",
            ),
            "servers" to options.servers.map { Json.obj("url" to it) }.takeIf { it.isNotEmpty() },
            "security" to options.securitySchemes.keys.takeIf { it.isNotEmpty() }
                ?.let { schemes -> listOf(schemes.associateWith { emptyList<String>() }) },
            "tags" to tags(manifest),
            "paths" to paths,
            "components" to Json.obj(
                Json.SCHEMAS to framework + schemas.schemas,
                ErrorResponses.RESPONSES to routes.responses,
                Json.PARAMETERS to OpenApiParameters.all(),
                Json.HEADERS to OpenApiParameters.headers(),
                SECURITY_SCHEMES to options.securitySchemes.mapValues { it.value.write() }.takeIf { it.isNotEmpty() },
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
                "description" to "The context's scheduled jobs.",
            ).takeIf { context.commands.isNotEmpty() },
        )
    } + Json.obj("name" to FRAMEWORK_TAG, "description" to "The framework's own endpoints.")

    /** The components of the security schemes. */
    private const val SECURITY_SCHEMES = "securitySchemes"

    /** The tag of the framework's own endpoints. */
    const val FRAMEWORK_TAG: String = "wow"
}
