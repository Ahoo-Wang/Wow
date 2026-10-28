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

/**
 * The JSON Schema 2020-12 of the Manifest format ([Manifest.FORMAT]): every object [ManifestTree] writes, with its keys,
 * which of them it always writes (`required`: a reader may rely on them; [ManifestTree.read] also takes a Manifest
 * that leaves out an empty list), and the values of each enumeration this version writes. Its objects are open, as a
 * reader of a newer writer's Manifest reads the keys it does not know past, and an enumeration's values are those of
 * this writer: a reader reads one it does not know leniently ([ManifestTree.read]).
 *
 * It is the one description of the format: the OpenAPI document's `wow.Manifest` is this schema, and the TypeScript
 * types of the Manifest are checked against its published copy, [RESOURCE].
 */
object ManifestSchema {
    /** The class path resource of the schema's published copy, which the TypeScript types are checked against. */
    const val RESOURCE: String = "META-INF/wow/manifest.schema.json"

    /** The JSON Schema dialect of the schema, and of every payload schema of `$defs`. */
    const val DIALECT: String = "https://json-schema.org/draft/2020-12/schema"

    /** The schema, as a tree of plain values, as [ManifestTree] writes a Manifest. */
    val schema: Map<String, Any?> = linkedMapOf(
        "\$schema" to DIALECT,
        "title" to "Wow Manifest",
        "description" to "A service's Manifest, or a module's fragment (without service), format ${Manifest.FORMAT}.",
        "type" to "object",
        "required" to listOf("format", "contexts", "errors", "\$defs"),
        "properties" to linkedMapOf(
            "format" to obj("const" to Manifest.FORMAT, "description" to "The format of the Manifest."),
            "requires" to strings("The markers a reader must understand to read the Manifest."),
            "service" to ref(SERVICE),
            "contexts" to array(ref(CONTEXT)),
            "errors" to array(ref(ERROR), "The framework's error codes and their HTTP statuses."),
            "\$defs" to obj(
                "type" to "object",
                "description" to "The JSON Schema 2020-12 of every payload, by key.",
                "additionalProperties" to obj("type" to "object"),
            ),
        ),
        "\$defs" to linkedMapOf(
            SERVICE to objectOf(
                "The service a merged Manifest describes; a fragment has none.",
                listOf("name"), "name" to string(), "version" to string()
            ),
            CONTEXT to objectOf(
                "A bounded context: its aggregates, subscriptions, published streams, error codes and job commands.",
                listOf("name", "aggregates", "subscriptions", "published", "errors"),
                "name" to string("The bounded context's name: lower case words joined by hyphens."),
                "description" to string(),
                "aggregates" to array(ref(AGGREGATE)),
                "subscriptions" to array(ref(SUBSCRIPTION)),
                "published" to array(ref(PUBLISHED)),
                "errors" to array(ref(ERROR), "The context's own error codes."),
                "commands" to array(ref(COMMAND), "The context's own system commands: its job commands."),
                "routes" to array(ref(SERVICE_ROUTE), "The context's service routes, whose handlers are its own code."),
            ),
            SERVICE_ROUTE to objectOf(
                "A service route: a route whose handler is the service's own code, its parameters and schemas.",
                listOf("name", "route"),
                "name" to string("The route's name in its context: its identity and its client's method."),
                "route" to ref(ROUTE),
                "parameters" to array(ref(ROUTE_PARAMETER)),
                "request" to schemaRef(),
                "response" to schemaRef(),
                "errors" to strings("The codes the route refuses with, entries of the context's errors."),
                "description" to string(),
                "deprecated" to boolean(),
            ),
            ROUTE_PARAMETER to objectOf(
                "A path variable or query parameter of a service route, and its scalar schema.",
                listOf("name", "in", "schema", "required"),
                "name" to string(),
                "in" to enumOf(RouteParameterManifest.Location.entries, "Where the parameter is read from."),
                "schema" to obj("type" to "object", "description" to "An inline JSON Schema of a scalar."),
                "required" to boolean(),
            ),
            AGGREGATE to objectOf(
                "An aggregate type: its state, snapshots, commands, events and query models.",
                listOf("name", "state", "snapshot", "commands", "events", "queries"),
                "name" to string(),
                "description" to string(),
                "state" to ref(STATE),
                "snapshot" to ref(SNAPSHOT),
                "commands" to array(ref(COMMAND)),
                "events" to array(ref(EVENT)),
                "queries" to array(ref(QUERY)),
                "schedules" to strings("The commands the aggregate schedules."),
                "ownership" to enumOf(Ownership.entries, "Who owns an aggregate; owner when absent."),
                "retired" to strings("The events the aggregate no longer produces."),
            ),
            STATE to objectOf(
                "An aggregate's state: its schema and the fingerprint of its shape.",
                listOf("schema"), "schema" to schemaRef(), "fingerprint" to string()
            ),
            SNAPSHOT to objectOf(
                "Whether an aggregate keeps snapshots, and the revision of their state.",
                listOf("enabled"), "enabled" to boolean(), "stateRevision" to integer()
            ),
            QUERY to objectOf(
                "A query model of an aggregate (snapshot, events) and its routes.",
                listOf("model", "routes"), "model" to string(), "routes" to array(ref(ROUTE))
            ),
            COMMAND to objectOf(
                "A command of an aggregate or a context, and the routes that send it.",
                listOf("name", "aliases", "schema", "creation", "routes"),
                "name" to string(),
                "aliases" to strings("The command's former names."),
                "schema" to schemaRef(),
                "creation" to enumOf(Creation.entries, "Whether the command creates its aggregate."),
                "produces" to strings("The events the command produces, when the module says."),
                "routes" to array(ref(ROUTE)),
                "mounted" to boolean(),
                "system" to boolean("A system command (wow.*): a lifecycle command, a rebuild or a job command."),
                "transformsTo" to strings(),
                "description" to string(),
            ),
            EVENT to objectOf(
                "An event of an aggregate and its revision.",
                listOf("name", "aliases", "revision", "schema"),
                "name" to string(),
                "aliases" to strings("The event's former names."),
                "revision" to integer(),
                "schema" to schemaRef(),
                "description" to string(),
            ),
            ROUTE to objectOf(
                "A route of the edge: a method and a path, {id} the aggregate id.",
                listOf("method", "path"), "method" to string(), "path" to string()
            ),
            ERROR to objectOf(
                "An error code and the HTTP status it is answered with.",
                listOf("code", "status"),
                "code" to string(),
                "status" to integer(),
                "description" to string(),
            ),
            PUBLISHED to objectOf(
                "A stream a context publishes of an aggregate's events, and its topic.",
                listOf("aggregate", "topic", "includeState"),
                "aggregate" to string(),
                "topic" to string(),
                "includeState" to boolean(),
            ),
            SUBSCRIPTION to objectOf(
                "A subscription of a bounded context: what it reads and what its handler derives.",
                listOf("name", "kind", "source", "function"),
                "name" to string("processor.function"),
                "kind" to enumOf(SubscriptionKind.entries),
                "source" to ref(SOURCE),
                "function" to ref(FUNCTION),
                "derives" to strings(),
                "rejected" to strings(),
                "committed" to strings(),
                "calls" to strings(),
                "start" to enumOf(SubscriptionStart.entries),
                "schedules" to strings(),
                "retry" to ref(RETRY),
                "job" to ref(JOB),
            ),
            SOURCE to objectOf(
                "What a subscription reads: a context, aggregate types and events.",
                listOf("aggregates", "events"),
                "context" to string(),
                "aggregates" to strings(),
                "events" to strings(),
                "eventSchemas" to strings(),
                "nullableState" to boolean(),
            ),
            FUNCTION to objectOf(
                "The handler function of a subscription.",
                listOf("kind", "context", "processor", "name"),
                "kind" to enumOf(FunctionKind.entries),
                "context" to string(),
                "processor" to string(),
                "name" to string(),
            ),
            RETRY to objectOf(
                "A subscription's retry budget.",
                emptyList(),
                "maxAttempts" to integer(),
                "initialBackoff" to string(),
                "maxBackoff" to string(),
                "timeout" to string(),
                "recoverable" to strings(),
                "unrecoverable" to strings(),
                "multiplier" to obj("type" to "number"),
            ),
            JOB to objectOf(
                "The scheduled job a subscription runs.",
                listOf("name", "cron", "zone", "scope", "sharded", "misfire"),
                "name" to string(),
                "cron" to string(),
                "zone" to string(),
                "scope" to values(JobManifest.SCOPES, "Who the job runs for."),
                "sharded" to boolean(),
                "misfire" to values(JobManifest.MISFIRES, "What the job does with the slots it missed."),
            ),
        ),
    )

    private const val SERVICE = "Service"
    private const val CONTEXT = "Context"
    private const val AGGREGATE = "Aggregate"
    private const val COMMAND = "Command"
    private const val EVENT = "Event"
    private const val ROUTE = "Route"
    private const val SERVICE_ROUTE = "ServiceRoute"
    private const val ROUTE_PARAMETER = "RouteParameter"
    private const val ERROR = "Error"
    private const val PUBLISHED = "Published"
    private const val SUBSCRIPTION = "Subscription"
    private const val STATE = "State"
    private const val SNAPSHOT = "Snapshot"
    private const val QUERY = "Query"
    private const val SOURCE = "Source"
    private const val FUNCTION = "Function"
    private const val RETRY = "Retry"
    private const val JOB = "Job"

    private fun obj(vararg entries: Pair<String, Any?>): Map<String, Any?> =
        entries.filter { it.second != null }.toMap(LinkedHashMap())

    private fun ref(name: String): Map<String, Any?> = obj("\$ref" to "#/\$defs/$name")

    private fun string(description: String? = null) = obj("type" to "string", "description" to description)

    private fun boolean(description: String? = null) = obj("type" to "boolean", "description" to description)

    private fun integer() = obj("type" to "integer")

    private fun schemaRef() = obj("type" to "string", "pattern" to "^#/\\\$defs/", "description" to "#/\$defs/<key>")

    private fun array(items: Map<String, Any?>, description: String? = null) =
        obj("type" to "array", "items" to items, "description" to description)

    private fun strings(description: String? = null) = array(obj("type" to "string"), description)

    private fun enumOf(entries: List<Enum<*>>, description: String? = null) =
        values(entries.map(ManifestTree::wire), description)

    private fun values(values: List<String>, description: String? = null) =
        obj("type" to "string", "enum" to values, "description" to description)

    private fun objectOf(
        description: String,
        required: List<String>,
        vararg properties: Pair<String, Map<String, Any?>>,
    ) = obj(
        "type" to "object",
        "description" to description,
        "required" to required.ifEmpty { null },
        "properties" to properties.toMap(LinkedHashMap()),
    )
}
