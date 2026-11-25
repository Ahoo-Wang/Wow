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

import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.wire.WireNames
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.QueryManifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.openapi.Json.array
import me.ahoo.wow.openapi.Json.nullable
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

    /**
     * Adds [operation] at [path], with `Wow-Space` among its parameters: the public port reads it on every route,
     * where a space the caller does not act in is 403 (the authenticator's), so every operation takes it.
     */
    private fun add(method: String, path: String, operation: Map<String, Any?>) {
        @Suppress("UNCHECKED_CAST")
        val parameters = operation["parameters"] as List<Map<String, Any?>>?
        val space = OpenApiParameters.ref(Http.SPACE_HEADER)
        val read = if (parameters.orEmpty().contains(space)) {
            operation
        } else {
            operation + ("parameters" to parameters.orEmpty() + space)
        }
        paths.getOrPut(path) { LinkedHashMap() }[method.lowercase()] = read
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

    /** A framework endpoint answering [content]: what the public port refuses of any request, and 406. */
    private fun framework(operationId: String, summary: String, content: Map<String, Any?>): Map<String, Any?> = obj(
        "tags" to listOf(ManifestOpenApi.FRAMEWORK_TAG),
        "summary" to summary,
        "operationId" to operationId,
        "responses" to ok(content) + errors.of("framework", ErrorResponses.FRAMEWORK),
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
                    errors.of("datasets", ErrorResponses.DATASET_LIST),
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

    /**
     * An aggregate's command routes, its state reads by id and, of one whose id is its owner's, the same reads by no
     * id, of the caller's own aggregate, as its command routes by no id are.
     */
    private fun aggregateRoutes(context: String, aggregate: AggregateManifest) {
        val tag = "$context.${aggregate.name}"
        val owned = aggregate.idSource == AggregateIdSource.OWNER
        aggregate.commands.forEach { command ->
            command.routes.forEach { route ->
                val operation = if (command.system) {
                    val own = owned && CommandRoutes.ID !in route.path
                    system(context, tag, command, route, systemDescription(command.name, own))
                } else {
                    command(context, tag, command, route, owned)
                }
                add(route.method, route.path, operation)
            }
        }
        val base = "/$context/${aggregate.name}"
        val record = RecordSchemas(aggregate, schemas.ref(aggregate.state.schema))
        stateRoutes(tag, "$base/${CommandRoutes.ID}/${Http.STATE_SEGMENT}", record, byId = true)
        if (owned) {
            stateRoutes(tag, "$base/${Http.STATE_SEGMENT}", record, byId = false)
        }
        aggregate.queries.forEach { query -> queryRoutes(tag, query, record) }
    }

    /**
     * What an aggregate's records refine of the framework's: its [state], and the `access` of its declared access
     * attributes ([WowSchemas.access]) when it declares any.
     */
    private inner class RecordSchemas(aggregate: AggregateManifest, private val state: Map<String, Any?>) {
        private val declared = aggregate.access.takeIf { it.isNotEmpty() }

        /** A snapshot, as a state read and a snapshot query answer it. */
        val snapshot: Map<String, Any?> = refined(WowSchemas.SNAPSHOT, *snapshotProperties())

        /** One commit of a trace: the aggregate after it. */
        val commit: Map<String, Any?> = obj("properties" to obj(*snapshotProperties()))

        /** An event stream, as an events query answers it. */
        val stream: Map<String, Any?> = declared?.let {
            refined(WowSchemas.EVENT_STREAM, "access" to nullable(WowSchemas.access(it, stream = true)))
        } ?: schemaRef(WowSchemas.EVENT_STREAM)

        private fun snapshotProperties(): Array<Pair<String, Any?>> = listOfNotNull(
            "state" to state,
            declared?.let { "access" to WowSchemas.access(it, stream = false) },
        ).toTypedArray()
    }

    /**
     * The job commands of [context]'s built-in job aggregate ([ContextManifest.commands]), when it has jobs:
     * `POST /{context}/wow.schedule/{id}/enable-job`, ..., the id being the job's name.
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
                    "in" to WireNames.of(parameter.location),
                    "required" to parameter.required,
                    "schema" to schemas.inlineInput(parameter.schema),
                )
            }
            val noBody = obj("204" to obj("description" to "The route answers no body."))
            val answer = route.response?.let { response ->
                obj("200" to obj("description" to "The route's answer.", "content" to json(schemas.inline(response)))) +
                    if (route.responseOptional) noBody else emptyMap()
            } ?: noBody
            add(
                route.route.method,
                route.route.path,
                obj(
                    "tags" to listOf(context.name),
                    "summary" to route.name,
                    "description" to route.description,
                    "operationId" to "${context.name}.${route.name}",
                    "deprecated" to route.deprecated.takeIf { it },
                    // The scope it acts in and the request id of its call, which its handler reads.
                    "parameters" to parameters + listOf(Http.SCOPE_HEADER, Http.REQUEST_ID_HEADER)
                        .map(OpenApiParameters::ref),
                    "requestBody" to route.request?.let {
                        obj("required" to true, "content" to json(schemas.inlineInput(it)))
                    },
                    "responses" to answer + errors.ofRoute(
                        context.name,
                        route.name,
                        route.errors,
                        body = route.request != null,
                    ),
                    "x-wow-route" to obj("name" to route.name, "errors" to route.errors.ifEmpty { null }),
                ),
            )
        }
    }

    /**
     * The description of the system command [name] of an aggregate; by the route that names no id of one whose id is
     * its owner's ([own]), to the caller's own aggregate.
     */
    private fun systemDescription(name: String, own: Boolean): String = when {
        name == SystemCommands.REBUILD_SNAPSHOT ->
            "Sends `$name`: rebuilds from the log the snapshots of the aggregates `ids` of this aggregate type, in " +
                "the caller's tenant."

        own -> "Sends the lifecycle command `$name`. $OWN"
        else -> "Sends the lifecycle command `$name`."
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
        // Of a command routed both ways (a lifecycle command of an aggregate whose id is its owner's), the route by no id
        // has an operation id of its own.
        val operationId = "$tag.${command.name}"
        return obj(
            "tags" to listOf(tag),
            "summary" to command.name.substringAfterLast('.'),
            "description" to description,
            "operationId" to if (byId || command.routes.none { CommandRoutes.ID in it.path }) {
                operationId
            } else {
                "$operationId.$WITHOUT_ID"
            },
            "parameters" to commandParameters(byId),
            "requestBody" to body(
                when {
                    key in WowSchemas.LIFECYCLE && key in manifest.defs -> schemas.input(command.schema)
                    key in WowSchemas.SYSTEM_COMMANDS -> schemaRef(key)
                    key in manifest.defs -> schemas.input(command.schema)
                    else -> obj("type" to "object", "properties" to emptyMap<String, Any?>())
                },
            ),
            "responses" to commandResponses(context, domain = false),
        )
    }

    private fun command(
        context: String,
        tag: String,
        command: CommandManifest,
        route: RouteManifest,
        owned: Boolean,
    ): Map<String, Any?> {
        val byId = CommandRoutes.ID in route.path
        val type = "$tag.${command.name}"
        return obj(
            "tags" to listOf(tag),
            "summary" to command.name,
            "description" to commandDescription(type, command, byId, owned),
            "operationId" to if (byId) type else "$type.$WITHOUT_ID",
            "parameters" to commandParameters(byId),
            "requestBody" to body(schemas.input(command.schema)),
            "responses" to commandResponses(context, domain = true),
            "x-wow-command" to obj(
                "type" to type,
                "aliases" to command.aliases.takeIf { it.isNotEmpty() },
                "creation" to WireNames.of(command.creation),
                "produces" to command.produces,
                "mounted" to command.mounted.takeIf { it },
                "transformsTo" to command.transformsTo,
            ),
        )
    }

    /**
     * The description of [command] by a route that names its aggregate's id ([byId]) or not: by none, of an aggregate
     * whose id is its owner's ([owned]), it is the caller's own, which no transformer finds and no server chooses.
     */
    private fun commandDescription(
        type: String,
        command: CommandManifest,
        byId: Boolean,
        owned: Boolean,
    ): String = buildList {
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
            add(
                if (owned && !command.mounted) {
                    OWN
                } else {
                    "The request names no aggregate id: the server chooses it, or the transformer finds the aggregate."
                },
            )
        }
        command.produces?.takeIf { it.isNotEmpty() }?.let { add("Produces ${it.joinToString { name -> "`$name`" }}.") }
    }.joinToString(" ")

    /**
     * The id of a route [byId], and the headers every command route reads, `If-Match` among them: a command by no id
     * reads it too, the caller's own aggregate's version or `*` on a creation.
     */
    private fun commandParameters(byId: Boolean): List<Map<String, Any?>> = buildList {
        if (byId) add(OpenApiParameters.ref(OpenApiParameters.ID))
        OpenApiParameters.COMMAND_HEADERS.forEach { add(OpenApiParameters.ref(it)) }
    }

    private fun body(schema: Map<String, Any?>): Map<String, Any?> = obj(
        "description" to "The command's body; an empty body is `{}`.",
        "required" to false,
        "content" to json(schema),
    )

    private fun commandResponses(context: String, domain: Boolean): Map<String, Any?> {
        val result = schemaRef(WowSchemas.COMMAND_RESULT)
        return obj(
            "200" to errors.register(
                "wow.command.200",
                obj(
                    "description" to COMMAND_STREAM,
                    "content" to json(result) + events(Http.STAGES, result),
                ),
            ),
            "202" to errors.register(
                "wow.command.202",
                obj("description" to "The result of a wait that ends at `accepted`.", "content" to json(result)),
            ),
        ) + errors.of("command", ErrorResponses.COMMAND, context, domain = domain, ifMatch = true, body = true)
    }

    /**
     * The state read and trace at [path]: of the aggregate the path's id names ([byId]), or of the caller's own
     * aggregate of one whose id is its owner's, by no id.
     */
    private fun stateRoutes(tag: String, path: String, record: RecordSchemas, byId: Boolean) {
        val view = record.snapshot
        val suffix = if (byId) "" else ".$WITHOUT_ID"
        val own = if (byId) "" else " $OWN_READ"
        val readParameters = listOfNotNull(
            OpenApiParameters.ID.takeIf { byId },
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
                    "`version` when it is not committed within the wait).$own",
                "operationId" to "$tag.state$suffix",
                "parameters" to readParameters.map(OpenApiParameters::ref),
                "responses" to obj(
                    "200" to obj(
                        "description" to "The state; its ETag is its version and a digest of the body (`\"3-<digest>\"`).",
                        "headers" to obj(Http.ETAG_HEADER to Json.ref(Json.HEADERS, Http.ETAG_HEADER)),
                        "content" to json(view),
                    ),
                    "304" to notModified("Not Modified: the state is still the one If-None-Match names."),
                ) + errors.of("state", ErrorResponses.STATE_READ),
            ),
        )
        val traceParameters =
            listOfNotNull(
                OpenApiParameters.ID.takeIf { byId },
                Http.FROM_PARAMETER,
                Http.TO_PARAMETER,
                Http.LIMIT_PARAMETER,
                Http.SCOPE_HEADER
            )
        val trace = refined(WowSchemas.STATE_TRACE, "commits" to array(record.commit))
        add(
            GET,
            "$path/${Http.TRACE_SEGMENT}",
            obj(
                "tags" to listOf(tag),
                "summary" to "The aggregate's commits",
                "description" to "The commits from `from` to `to`, at most `limit`, each with its events and the " +
                    "state after it; the next page starts after the last commit's version.$own",
                "operationId" to "$tag.state.trace$suffix",
                "parameters" to traceParameters.map(OpenApiParameters::ref),
                "responses" to ok(json(trace)) +
                    errors.of("state", ErrorResponses.STATE),
            ),
        )
    }

    private fun queryRoutes(tag: String, query: QueryManifest, schemas: RecordSchemas) {
        val record = when (query.model) {
            QueryRoutes.SNAPSHOT -> schemas.snapshot
            QueryRoutes.EVENTS -> schemas.stream
            // A model the edge does not know is not served.
            else -> null
        } ?: return
        val operationId = "$tag.${query.model}"
        query.routes.forEach { route ->
            // The snapshots' answers say whether they are being rebuilt.
            val rebuilt = query.model == QueryRoutes.SNAPSHOT
            val operation = if (route.method == QueryRoutes.GET) {
                describe("$operationId.describe", tag, emptyList(), dataset = false, rebuilt = rebuilt)
            } else {
                query(
                    "$operationId.query",
                    "Queries the ${query.model} model.",
                    tag,
                    record,
                    emptyList(),
                    dataset = false,
                    rebuilt = rebuilt,
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
        rebuilt: Boolean = false,
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
                    "headers" to rebuilding(rebuilt),
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
                body = true,
            ),
        )
    }

    /**
     * The descriptor of a model or a [dataset], whose caller a dataset's reads in the scope `Wow-Scope` names; with
     * [rebuilt], its answers say whether the snapshots are being rebuilt.
     */
    private fun describe(
        operationId: String,
        tag: String?,
        pathParameters: List<String>,
        dataset: Boolean,
        rebuilt: Boolean = false,
    ): Map<String, Any?> = obj(
        "tags" to listOf(tag ?: ManifestOpenApi.FRAMEWORK_TAG),
        "summary" to "The model's capability descriptor",
        "operationId" to operationId,
        "parameters" to (pathParameters + listOfNotNull(Http.SCOPE_HEADER.takeIf { dataset }))
            .plus(Http.IF_NONE_MATCH_HEADER).map(OpenApiParameters::ref),
        "responses" to obj(
            "200" to obj(
                "description" to "The descriptor, its version as the ETag.",
                "headers" to tagged(rebuilt),
                "content" to json(schemaRef(WowSchemas.QUERY_DESCRIPTOR)),
            ),
            "304" to notModified("Not Modified: the descriptor is at the version If-None-Match names.", rebuilt),
        ) + errors.of(
            if (dataset) "dataset-descriptor" else "descriptor",
            if (dataset) ErrorResponses.DATASET_DESCRIPTOR else ErrorResponses.DESCRIPTOR,
        ),
    )

    /** A 304 of [description], with the `ETag` it was answered for (and `Wow-Rebuilding` when [rebuilt]). */
    private fun notModified(description: String, rebuilt: Boolean = false): Map<String, Any?> =
        obj("description" to description, "headers" to tagged(rebuilt))

    /** The headers of a tagged answer: its `ETag`, and `Wow-Rebuilding` of a snapshot model's ([rebuilt]). */
    private fun tagged(rebuilt: Boolean): Map<String, Any?> =
        obj(Http.ETAG_HEADER to Json.ref(Json.HEADERS, Http.ETAG_HEADER)) + rebuilding(rebuilt).orEmpty()

    /** The `Wow-Rebuilding` header of a snapshot model's answer ([rebuilt]), or none. */
    private fun rebuilding(rebuilt: Boolean): Map<String, Any?>? =
        obj(Http.REBUILDING_HEADER to Json.ref(Json.HEADERS, Http.REBUILDING_HEADER)).takeIf { rebuilt }

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

        /** The suffix of the operation id of a route that names no aggregate id, beside one that does. */
        const val WITHOUT_ID = "withoutId"

        /** What a command's answer is, as JSON and as an event stream. */
        const val COMMAND_STREAM = "The result the wait ended with. With `Accept: text/event-stream`, a refusal " +
            "before the command is accepted is the problem it is, with its status; once accepted, the answer is " +
            "200 and no other status: one event per result as each hop the plan names is reached, its name the " +
            "hop's stage, its `id` the result's index and its data the result, until the last, which ends the " +
            "stream (the plan's last hop, a rejection at its hop, or a FAILED result: `wow.wait.timeout` at the " +
            "last hop reached, `wow.storage.unavailable` or `wow.command.unknown` when the execution failed). After " +
            "it, one more event, `end` with `{\"reason\":\"complete\"}` and no result, says the stream is whole: a " +
            "stream that ends without it, however cleanly, leaves the command's outcome unknown (retry with the same " +
            "request id). A comment, `: keep-alive`, is sent at once and every 15 seconds while the stream runs: it " +
            "is no event."

        /** What a command route by no id of an aggregate whose id is its owner's means. */
        const val OWN = "The request names no aggregate id: it acts on the caller's own aggregate, whose id is the " +
            "caller's owner; an unauthenticated caller is 401 `wow.auth.unauthenticated`, and one with no owner is 400 " +
            "`wow.command.malformed`: it names the id."

        /** What a state route by no id of an aggregate whose id is its owner's reads. */
        const val OWN_READ = "The path names no aggregate id: it reads the caller's own aggregate, whose id is the " +
            "owner the caller reads as; an unauthenticated caller is 401 `wow.auth.unauthenticated`, and one with no owner " +
            "is 400 `wow.query.malformed`: it names the id."
    }
}
