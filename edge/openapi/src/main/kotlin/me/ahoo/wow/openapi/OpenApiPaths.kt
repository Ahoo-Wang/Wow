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

import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Creation
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.QueryManifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.openapi.Json.array
import me.ahoo.wow.openapi.Json.obj
import me.ahoo.wow.openapi.Json.schemaRef
import me.ahoo.wow.openapi.Json.type
import me.ahoo.wow.openapi.WowHttpContract as Http

/** The `paths` of the document: the framework's endpoints, then each aggregate's routes, in the Manifest's order. */
internal class OpenApiPaths(
    private val manifest: Manifest,
    private val options: OpenApiOptions,
    private val schemas: SchemaComponents,
) {
    private val errors = ErrorResponses(manifest, options.errorStatuses)
    private val paths = LinkedHashMap<String, LinkedHashMap<String, Any?>>()

    /** The error responses the operations refer to, once [write] has run. */
    val responses: Map<String, Map<String, Any?>> get() = errors.components

    fun write(): Map<String, Any?> {
        frameworkRoutes()
        manifest.contexts.forEach { context ->
            context.aggregates.forEach { aggregate -> aggregateRoutes(context.name, aggregate) }
            jobRoutes(context)
            serviceRoutes(context)
        }
        return paths
    }

    private fun add(method: String, path: String, operation: Map<String, Any?>) {
        paths.getOrPut(path) { LinkedHashMap() }[method.lowercase()] = operation
    }

    private fun frameworkRoutes() {
        if (options.manifestEndpoint) {
            add(
                GET,
                Http.MANIFEST_PATH,
                framework("wow.manifest", "The service's Manifest.", json(schemaRef(WowSchemas.MANIFEST)))
            )
        }
        if (options.openApiEndpoint) {
            add(GET, Http.OPENAPI_PATH, framework("wow.openapi", "This document.", json(type("object"))))
        }
        if (options.datasets) {
            datasetRoutes()
        }
    }

    /** A framework endpoint answering [content], 406 for an Accept it cannot. */
    private fun framework(operationId: String, summary: String, content: Map<String, Any?>): Map<String, Any?> = obj(
        "tags" to listOf(ManifestOpenApi.FRAMEWORK_TAG),
        "summary" to summary,
        "operationId" to operationId,
        "responses" to ok(content) + errors.of(
            "framework",
            ErrorResponses.FRAMEWORK,
            http = mapOf(ErrorResponses.NOT_ACCEPTABLE),
        ),
    )

    private fun datasetRoutes() {
        add(
            GET,
            Http.DATASETS_PATH,
            obj(
                "tags" to listOf(ManifestOpenApi.FRAMEWORK_TAG),
                "summary" to "The datasets the caller sees.",
                "operationId" to "wow.dataset.list",
                "parameters" to listOf(OpenApiParameters.ref(Http.SCOPE_HEADER)),
                "responses" to ok(json(array(schemaRef(WowSchemas.DATASET_ENTRY)))) +
                    errors.of("datasets", ErrorResponses.DATASET_LIST, http = mapOf(ErrorResponses.NOT_ACCEPTABLE)),
            ),
        )
        val path = "${Http.DATASETS_PATH}/{${OpenApiParameters.DATASET_CONTEXT}}/{${OpenApiParameters.DATASET}}/" +
            QueryRoutes.SEGMENT
        val parameters = listOf(OpenApiParameters.DATASET_CONTEXT, OpenApiParameters.DATASET)
        add(
            POST,
            path,
            query(
                "wow.dataset.query",
                "Queries a dataset, as a snapshot model is queried.",
                null,
                type("object"),
                parameters,
                dataset = true,
            ),
        )
        add(GET, path, describe("wow.dataset.describe", null, parameters, dataset = true))
    }

    private fun aggregateRoutes(context: String, aggregate: AggregateManifest) {
        val tag = "$context.${aggregate.name}"
        aggregate.commands.forEach { command ->
            command.routes.forEach { route ->
                val operation = if (command.system) {
                    system(context, tag, command, route, systemDescription(command.name))
                } else {
                    command(context, tag, command, route)
                }
                add(route.method, route.path, operation)
            }
        }
        val base = "/$context/${aggregate.name}/${CommandRoutes.ID}"
        val state = schemas.ref(aggregate.state.schema)
        stateRoutes(tag, "$base/${Http.STATE_SEGMENT}", state)
        aggregate.queries.forEach { query -> queryRoutes(tag, query, state) }
    }

    /**
     * The job commands of [context]'s built-in job aggregate ([ContextManifest.commands]), when it has jobs:
     * `POST /{context}/wow.schedule/{id}/enable`, ..., the id being the job's name.
     */
    private fun jobRoutes(context: ContextManifest) {
        val tag = "${context.name}.${WowSchemas.JOB_AGGREGATE}"
        val jobs = context.subscriptions.mapNotNull { it.job }.joinToString { "`${it.name}`" }
        context.commands.forEach { command ->
            val description = "Sends the job command `${command.name}` to the job the path's id names, in the " +
                "caller's tenant: $jobs."
            command.routes.forEach { route ->
                add(route.method, route.path, system(context.name, tag, command, route, description))
            }
        }
    }

    /** A context's service routes: their parameters, body, answer and refusals. */
    private fun serviceRoutes(context: ContextManifest) {
        context.routes.forEach { route ->
            val parameters = route.parameters.map { parameter ->
                obj(
                    "name" to parameter.name,
                    "in" to ManifestTree.wire(parameter.location),
                    "required" to parameter.required,
                    "schema" to parameter.schema,
                )
            }
            val answer = route.response?.let { response ->
                obj("200" to obj("description" to "The route's answer.", "content" to json(schemas.inline(response))))
            } ?: obj("204" to obj("description" to "The route answers no body."))
            add(
                route.route.method,
                route.route.path,
                obj(
                    "tags" to listOf(context.name),
                    "summary" to route.name,
                    "description" to route.description,
                    "operationId" to "${context.name}.${route.name}",
                    "deprecated" to route.deprecated.takeIf { it },
                    "parameters" to parameters.ifEmpty { null },
                    "requestBody" to route.request?.let {
                        obj("required" to true, "content" to json(schemas.inline(it)))
                    },
                    "responses" to answer + errors.of(
                        "route",
                        ErrorResponses.ROUTE,
                        context.name,
                        domain = route.errors.isNotEmpty(),
                    ),
                    "x-wow-route" to obj("name" to route.name, "errors" to route.errors.ifEmpty { null }),
                ),
            )
        }
    }

    /** The description of the system command [name] of an aggregate. */
    private fun systemDescription(name: String): String = if (name == WowSchemas.REBUILD) {
        "Sends `$name`: rebuilds from the log the snapshots of the aggregates `ids` of this aggregate type, in the " +
            "caller's tenant."
    } else {
        "Sends the lifecycle command `$name`."
    }

    /**
     * The operation of the system [command]'s [route], its body the framework's schema ([WowSchemas.SYSTEM_COMMANDS])
     * unless the Manifest holds the body of a lifecycle command.
     */
    private fun system(
        context: String,
        tag: String,
        command: CommandManifest,
        route: RouteManifest,
        description: String,
    ): Map<String, Any?> {
        val byId = CommandRoutes.ID in route.path
        val key = Manifest.schemaKey(command.schema)
        return obj(
            "tags" to listOf(tag),
            "summary" to command.name.substringAfterLast('.'),
            "description" to description,
            "operationId" to "$tag.${command.name}",
            "parameters" to commandParameters(byId),
            "requestBody" to body(
                when {
                    key in WowSchemas.LIFECYCLE && key in manifest.defs -> schemas.ref(command.schema)
                    key in WowSchemas.SYSTEM_COMMANDS -> schemaRef(key)
                    key in manifest.defs -> schemas.ref(command.schema)
                    else -> obj("type" to "object", "properties" to emptyMap<String, Any?>())
                },
            ),
            "responses" to commandResponses(context, byId, domain = false),
        )
    }

    private fun command(context: String, tag: String, command: CommandManifest, route: RouteManifest): Map<String, Any?> {
        val byId = CommandRoutes.ID in route.path
        val type = "$tag.${command.name}"
        return obj(
            "tags" to listOf(tag),
            "summary" to command.name,
            "description" to commandDescription(type, command, byId),
            "operationId" to if (byId) type else "$type.withoutId",
            "parameters" to commandParameters(byId),
            "requestBody" to body(schemas.ref(command.schema)),
            "responses" to commandResponses(context, byId, domain = true),
            "x-wow-command" to obj(
                "type" to type,
                "aliases" to command.aliases.takeIf { it.isNotEmpty() },
                "creation" to ManifestTree.wire(command.creation),
                "produces" to command.produces,
                "mounted" to command.mounted.takeIf { it },
                "transformsTo" to command.transformsTo,
            ),
        )
    }

    private fun commandDescription(type: String, command: CommandManifest, byId: Boolean): String = buildList {
        // The command's own description, a sentence even when it is a one-line summary.
        command.description?.let { add(if (it.last() in SENTENCE_ENDS) it else "$it.") }
        add("Sends `$type`.")
        add(
            when {
                command.mounted -> "A mounted command: a transformer turns it into the commands it becomes."
                command.creation == Creation.REQUIRED -> "Creates the aggregate; one that exists is refused."
                command.creation == Creation.ALLOWED -> "Creates the aggregate when it does not exist."
                else -> "Acts on an aggregate that exists."
            },
        )
        if (!byId) {
            add("The request names no aggregate id: the server chooses it, or the transformer finds the aggregate.")
        }
        command.produces?.takeIf { it.isNotEmpty() }?.let { add("Produces ${it.joinToString { name -> "`$name`" }}.") }
    }.joinToString(" ")

    private fun commandParameters(byId: Boolean): List<Map<String, Any?>> = buildList {
        if (byId) add(OpenApiParameters.ref(OpenApiParameters.ID))
        OpenApiParameters.COMMAND_HEADERS.forEach { add(OpenApiParameters.ref(it)) }
        if (byId) add(OpenApiParameters.ref(Http.IF_MATCH_HEADER))
    }

    private fun body(schema: Map<String, Any?>): Map<String, Any?> = obj(
        "description" to "The command's body; an empty body is `{}`.",
        "required" to false,
        "content" to json(schema),
    )

    private fun commandResponses(context: String, byId: Boolean, domain: Boolean): Map<String, Any?> {
        val result = schemaRef(WowSchemas.COMMAND_RESULT)
        return obj(
            "200" to errors.register(
                "wow.command.200",
                obj(
                    "description" to "The result the wait ended with; with `Accept: text/event-stream`, one event " +
                        "per result until the wait ends, its name the hop's stage and its data the result.",
                    "content" to json(result) + events(Http.STAGES, result),
                ),
            ),
            "202" to errors.register(
                "wow.command.202",
                obj("description" to "The result of a wait that ends at `accepted`.", "content" to json(result)),
            ),
        ) + errors.of(
            "command",
            ErrorResponses.COMMAND,
            context,
            domain = domain,
            ifMatch = byId,
            http = mapOf(ErrorResponses.NOT_ACCEPTABLE, ErrorResponses.UNSUPPORTED_MEDIA_TYPE),
        )
    }

    private fun stateRoutes(tag: String, path: String, state: Map<String, Any?>) {
        val view = refined(WowSchemas.STATE_VIEW, "state" to state)
        val readParameters = listOf(
            OpenApiParameters.ID,
            Http.VERSION_PARAMETER,
            Http.AT_PARAMETER,
            Http.MIN_VERSION_PARAMETER,
            Http.SCOPE_HEADER,
            Http.IF_NONE_MATCH_HEADER,
        )
        add(
            GET,
            path,
            obj(
                "tags" to listOf(tag),
                "summary" to "The aggregate's state",
                "description" to "The latest state, the state at `version`, the state as of `at`, or the latest " +
                    "state at `minVersion` or later (409 `wow.query.min-version-not-reached` with the current " +
                    "`version` when it is not committed within the wait).",
                "operationId" to "$tag.state",
                "parameters" to readParameters.map(OpenApiParameters::ref),
                "responses" to obj(
                    "200" to obj(
                        "description" to "The state; its ETag is its version and a digest of the body (`\"3-<digest>\"`).",
                        "headers" to obj(Http.ETAG_HEADER to Json.ref(Json.HEADERS, Http.ETAG_HEADER)),
                        "content" to json(view),
                    ),
                    "304" to obj("description" to "Not Modified: the state is still the one If-None-Match names."),
                ) + errors.of("state", ErrorResponses.STATE_READ, http = mapOf(ErrorResponses.NOT_ACCEPTABLE)),
            ),
        )
        val traceParameters =
            listOf(
                OpenApiParameters.ID,
                Http.FROM_PARAMETER,
                Http.TO_PARAMETER,
                Http.LIMIT_PARAMETER,
                Http.SCOPE_HEADER
            )
        val trace = refined(WowSchemas.STATE_TRACE, "commits" to array(obj("properties" to obj("state" to state))))
        add(
            GET,
            "$path/${Http.TRACE_SEGMENT}",
            obj(
                "tags" to listOf(tag),
                "summary" to "The aggregate's commits",
                "description" to "The commits from `from` to `to`, at most `limit`, each with its events and the " +
                    "state after it; the next page starts after the last commit's version.",
                "operationId" to "$tag.state.trace",
                "parameters" to traceParameters.map(OpenApiParameters::ref),
                "responses" to ok(json(trace)) +
                    errors.of("state", ErrorResponses.STATE, http = mapOf(ErrorResponses.NOT_ACCEPTABLE)),
            ),
        )
    }

    private fun queryRoutes(tag: String, query: QueryManifest, state: Map<String, Any?>) {
        val record = when (query.model) {
            QueryRoutes.SNAPSHOT -> refined(WowSchemas.SNAPSHOT_RECORD, "state" to state)
            QueryRoutes.EVENTS -> schemaRef(WowSchemas.EVENT_STREAM_RECORD)
            // A model the edge does not know is not served.
            else -> null
        } ?: return
        val operationId = "$tag.${query.model}"
        query.routes.forEach { route ->
            val operation = if (route.method == QueryRoutes.GET) {
                describe("$operationId.describe", tag, emptyList(), dataset = false)
            } else {
                query(
                    "$operationId.query",
                    "Queries the ${query.model} model.",
                    tag,
                    record,
                    emptyList(),
                    dataset = false,
                )
            }
            add(route.method, route.path, operation)
        }
    }

    private fun query(
        operationId: String,
        summary: String,
        tag: String?,
        record: Map<String, Any?>,
        pathParameters: List<String>,
        dataset: Boolean,
    ): Map<String, Any?> {
        val kinds = listOf(
            array(record),
            refined(WowSchemas.PAGED_LIST, "list" to array(record)),
            refined(WowSchemas.CURSOR_PAGE, "list" to array(record)),
            record,
            schemaRef(WowSchemas.COUNT_RESULT),
            array(type("object")),
        )
        return obj(
            "tags" to listOf(tag ?: ManifestOpenApi.FRAMEWORK_TAG),
            "summary" to summary,
            "description" to "The body's `kind` is the operation: `list` (an array, or with " +
                "`Accept: text/event-stream` one `row` event per record), `paged`, `cursor`, `single`, `count` " +
                "or `aggregate` (an array of groups). A streamed `list` is resumable: each `row` " +
                "event's `id` is the record's position, an opaque cursor token; the stream reads keyset chunks, " +
                "bounded by time and query permits rather than rows, and ends with an `end` event " +
                "`{\"reason\", \"resumable\"}` (`complete`, or the resumable `hold-expired`, `permit-reclaimed` " +
                "and `node-stopping`); the same request with `Last-Event-ID` continues strictly after that position. " +
                "Chunks are not one snapshot: a record written meanwhile is read when it sorts after the position. A " +
                "list whose sort cannot order a cursor (or a model whose descriptor says `record.stream.resumable: " +
                "false`) streams without `id`s, and every `end` it sends says `\"resumable\": false`.",
            "operationId" to operationId,
            "parameters" to (pathParameters + Http.SCOPE_HEADER + Http.LAST_EVENT_ID_HEADER).map(OpenApiParameters::ref),
            "requestBody" to obj("required" to true, "content" to json(schemaRef(WowSchemas.QUERY_REQUEST))),
            "responses" to obj(
                "200" to obj(
                    "description" to "The query's answer, by its kind.",
                    "content" to json(obj("anyOf" to kinds)) + events(
                        listOf(Http.ROW_EVENT, Http.END_EVENT, Http.ERROR_EVENT),
                        record,
                        obj(
                            "id" to "The record's position: send it as `Last-Event-ID` to resume after it.",
                            "end" to streamEnd(),
                        ),
                    ),
                ),
            ) + errors.of(
                if (dataset) "dataset-query" else "query",
                if (dataset) ErrorResponses.DATASET_QUERY else ErrorResponses.QUERY,
                http = mapOf(ErrorResponses.NOT_ACCEPTABLE, ErrorResponses.UNSUPPORTED_MEDIA_TYPE),
            ),
        )
    }

    private fun describe(
        operationId: String,
        tag: String?,
        pathParameters: List<String>,
        dataset: Boolean,
    ): Map<String, Any?> = obj(
        "tags" to listOf(tag ?: ManifestOpenApi.FRAMEWORK_TAG),
        "summary" to "The model's capability descriptor",
        "operationId" to operationId,
        "parameters" to (pathParameters + Http.IF_NONE_MATCH_HEADER).map(OpenApiParameters::ref),
        "responses" to obj(
            "200" to obj(
                "description" to "The descriptor, its version as the ETag.",
                "headers" to obj(Http.ETAG_HEADER to Json.ref(Json.HEADERS, Http.ETAG_HEADER)),
                "content" to json(schemaRef(WowSchemas.QUERY_DESCRIPTOR)),
            ),
            "304" to obj("description" to "Not Modified: the descriptor is at the version If-None-Match names."),
        ) + errors.of(
            if (dataset) "dataset-descriptor" else "descriptor",
            if (dataset) ErrorResponses.DATASET_DESCRIPTOR else ErrorResponses.DESCRIPTOR,
            http = mapOf(ErrorResponses.NOT_ACCEPTABLE),
        ),
    )

    /** The framework schema [name] with the schemas of some of its [properties] made precise. */
    private fun refined(name: String, vararg properties: Pair<String, Any?>): Map<String, Any?> =
        schemaRef(name) + obj("properties" to obj(*properties))

    private fun ok(content: Map<String, Any?>): Map<String, Any?> =
        obj("200" to obj("description" to "OK", "content" to content))

    private fun json(schema: Map<String, Any?>): Map<String, Any?> = obj(JSON to obj("schema" to schema))

    /** Server-sent events named [names], each with [data] as its data, and the [extra] facts of the stream. */
    private fun events(
        names: List<String>,
        data: Map<String, Any?>,
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = obj(
        EVENT_STREAM to obj(
            "schema" to type("string", description = "Server-sent events."),
            "x-wow-events" to obj("event" to names, "data" to data) + extra,
        ),
    )

    /** The data of a resumable stream's `end` event. */
    private fun streamEnd(): Map<String, Any?> = obj(
        "type" to "object",
        "required" to listOf("reason", "resumable"),
        "properties" to obj(
            "reason" to obj("type" to "string", "enum" to Http.END_REASONS),
            "resumable" to type("boolean"),
        ),
    )

    private companion object {
        const val GET = "GET"
        const val POST = "POST"
        const val DELETE = "DELETE"
        const val JSON = "application/json"
        const val TEXT = "text/plain"
        const val EVENT_STREAM = "text/event-stream"
        val SENTENCE_ENDS = setOf('.', '!', '?')
    }
}
