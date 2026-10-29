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

import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.wire.WireNames
import me.ahoo.wow.manifest.ManifestSchema
import me.ahoo.wow.openapi.Json.array
import me.ahoo.wow.openapi.Json.map
import me.ahoo.wow.openapi.Json.nullable
import me.ahoo.wow.openapi.Json.obj
import me.ahoo.wow.openapi.Json.objectSchema
import me.ahoo.wow.openapi.Json.schemaRef
import me.ahoo.wow.openapi.Json.type

/**
 * The schemas of the framework's own bodies, as the public port writes them: the command result (`CommandResult`),
 * the problem, the state views, the query bodies and records
 * and the lifecycle commands. Their names start with `wow.`; a Manifest's
 * schema is named after its class ([SchemaComponents]), so the two never meet.
 */
internal object WowSchemas {
    const val PREFIX: String = "wow."
    const val COMMAND_RESULT: String = "wow.CommandResult"
    const val PROBLEM: String = "wow.Problem"
    const val STATE_VIEW: String = "wow.StateView"
    const val STATE_TRACE: String = "wow.StateTrace"
    const val SNAPSHOT_RECORD: String = "wow.SnapshotRecord"
    const val EVENT_STREAM_RECORD: String = "wow.EventStreamRecord"
    const val QUERY_REQUEST: String = "wow.QueryRequest"
    const val PAGED_LIST: String = "wow.PagedList"
    const val CURSOR_PAGE: String = "wow.CursorPage"
    const val COUNT_RESULT: String = "wow.CountResult"
    const val QUERY_DESCRIPTOR: String = "wow.QueryDescriptor"
    const val DATASET_ENTRY: String = "wow.DatasetEntry"
    const val MANIFEST: String = "wow.Manifest"
    private const val AGGREGATE_TYPE = "wow.AggregateType"
    private const val AGGREGATE_REF = "wow.AggregateRef"
    private const val HOP = "wow.Hop"
    private const val FUNCTION_INFO = "wow.FunctionInfo"
    private const val ERROR_INFO = "wow.ErrorInfo"
    private const val VIOLATION = "wow.Violation"
    private const val TRANSFORMED_FROM = "wow.TransformedFrom"
    private const val SCOPE = "wow.Scope"
    private const val OPERATOR = "wow.Operator"
    private const val COMMIT_VIEW = "wow.CommitView"
    private const val EVENT_VIEW = "wow.EventView"

    /** The lifecycle commands' bodies, by their full names; any other is an object. */
    val LIFECYCLE: Map<String, Map<String, Any?>> = linkedMapOf(
        "wow.lifecycle.DeleteAggregate" to objectSchema(emptyMap(), description = "Marks the aggregate deleted."),
        "wow.lifecycle.RestoreAggregate" to objectSchema(emptyMap(), description = "Restores a deleted aggregate."),
        "wow.lifecycle.TransferOwner" to objectSchema(mapOf("ownerId" to type("string")), listOf("ownerId")),
        "wow.lifecycle.TransferSpace" to objectSchema(mapOf("spaceId" to type("string")), listOf("spaceId")),
        "wow.lifecycle.ApplyTags" to objectSchema(mapOf("tags" to TAGS), listOf("tags")),
    )

    /** The name of the job aggregate of a context: `<context>.schedule`. */
    const val JOB_AGGREGATE: String = "schedule"

    /** The job commands a tenant sends through the gateway, by their full names, with their bodies. */
    val JOB_COMMANDS: Map<String, Map<String, Any?>> = linkedMapOf(
        "wow.schedule.Enable" to objectSchema(
            mapOf(
                "job" to type("string"),
                "rule" to nullable(schemaRef("wow.schedule.JobRule")),
                "partition" to obj("type" to listOf("integer", "null"), "minimum" to 0),
            ),
            listOf("job"),
            "Creates, or enables again, the job instance of the path's id, the job's name in the caller's tenant.",
        ),
        "wow.schedule.Reschedule" to objectSchema(
            mapOf("rule" to nullable(schemaRef("wow.schedule.JobRule"))),
            description = "Overrides the instance's rule; null follows the job's default again.",
        ),
        "wow.schedule.Disable" to objectSchema(emptyMap(), description = "Stops the job instance."),
    )

    private val JOB_RULE: Map<String, Any?>
        get() = objectSchema(
            mapOf(
                "expression" to type("string", nullable = true),
                "zone" to type("string", nullable = true),
                "misfire" to obj("type" to listOf("string", "null"), "enum" to listOf("coalesce", "catch-up", null)),
            ),
            description = "An instance's own rule of a job; each part that is null follows the job's default.",
        )

    /**
     * The names of the system commands whose bodies the framework describes: a Manifest that holds the body of a
     * lifecycle command (keyed by its name) describes it in place of the framework.
     */
    val SYSTEM_COMMANDS: Set<String>
        get() = LIFECYCLE.keys + JOB_COMMANDS.keys + REBUILD

    private val TAGS: Map<String, Any?>
        get() = map(array(type("string")))

    private val STRING: Map<String, Any?> get() = type("string")
    private val NULLABLE_STRING: Map<String, Any?> get() = type("string", nullable = true)
    private val INTEGER: Map<String, Any?> get() = type("integer")
    private val BOOLEAN: Map<String, Any?> get() = type("boolean")

    /** The snapshot rebuild command, by its full name. */
    const val REBUILD: String = "wow.snapshot.Rebuild"

    private val REBUILD_COMMAND: Map<String, Any?>
        get() = objectSchema(
            mapOf("ids" to obj("type" to "array", "items" to STRING, "minItems" to 1, "maxItems" to 1_000)),
            listOf("ids"),
            "Rebuilds from the log the snapshots of the aggregates `ids` of the route's aggregate type, in the " +
                "caller's tenant.",
        )

    /** Every framework schema, by name. */
    fun all(): Map<String, Any?> = commandSchemas() + readSchemas() + querySchemas() + LIFECYCLE + JOB_COMMANDS +
        ("wow.schedule.JobRule" to JOB_RULE) + (REBUILD to REBUILD_COMMAND)

    /** The command result and the problem, with what they hold. */
    private fun commandSchemas(): Map<String, Any?> = linkedMapOf(
        COMMAND_RESULT to commandResult(),
        AGGREGATE_REF to aggregateRef(),
        AGGREGATE_TYPE to objectSchema(mapOf("context" to STRING, "name" to STRING), listOf("context", "name")),
        HOP to hop(),
        FUNCTION_INFO to functionInfo(),
        ERROR_INFO to objectSchema(
            mapOf(
                "code" to STRING,
                "message" to STRING,
                "violations" to array(schemaRef(VIOLATION)),
                "extensions" to type("object", description = "The rejection's structured data."),
            ),
            listOf("code", "message"),
        ),
        VIOLATION to objectSchema(
            mapOf("path" to STRING, "code" to STRING, "message" to STRING),
            listOf("path", "code", "message"),
        ),
        TRANSFORMED_FROM to objectSchema(
            mapOf("command" to STRING, "aggregateId" to NULLABLE_STRING),
            listOf("command"),
            "The command a transformer turned into this one.",
        ),
        PROBLEM to problem(),
    )

    /** The state reads, and what the framework's endpoints answer. */
    private fun readSchemas(): Map<String, Any?> = linkedMapOf(
        SCOPE to objectSchema(mapOf("ownerId" to NULLABLE_STRING, "spaceId" to NULLABLE_STRING)),
        OPERATOR to objectSchema(
            mapOf("id" to STRING, "tenantId" to STRING, "crossScope" to BOOLEAN),
            listOf("id", "tenantId"),
            "Who sent a command.",
        ),
        STATE_VIEW to stateView(),
        STATE_TRACE to objectSchema(
            mapOf("ref" to schemaRef(AGGREGATE_REF), "commits" to array(schemaRef(COMMIT_VIEW))),
            listOf("ref", "commits"),
        ),
        COMMIT_VIEW to commitView(),
        EVENT_VIEW to objectSchema(
            mapOf("name" to STRING, "revision" to INTEGER, "body" to obj("description" to "The event as the log stores it.")),
            listOf("name", "revision", "body"),
        ),
        DATASET_ENTRY to objectSchema(
            mapOf(
                "name" to STRING,
                "context" to STRING,
                "dataset" to STRING,
                "title" to NULLABLE_STRING,
                "path" to STRING,
            ),
            listOf("name", "context", "dataset", "path"),
        ),
    ) + manifestSchemas()

    /**
     * The Manifest's own schema ([ManifestSchema]): the Manifest as [MANIFEST], each of its definitions as
     * `wow.Manifest.<Name>`, its references pointing at them.
     */
    private fun manifestSchemas(): Map<String, Any?> {
        val schema = ManifestSchema.schema
        val definitions = schema.getValue(DEFS) as Map<*, *>
        val root = schema.filterKeys { it != "\$schema" && it != DEFS }
        return linkedMapOf<String, Any?>(MANIFEST to manifestRefs(root)) +
            definitions.entries.associate { (name, definition) -> "$MANIFEST.$name" to manifestRefs(definition) }
    }

    /** [node] with each reference into the Manifest schema's definitions pointing at its component. */
    private fun manifestRefs(node: Any?): Any? = when (node) {
        is Map<*, *> -> node.entries.associate { (key, value) ->
            key.toString() to if (key == REF && value is String && value.startsWith(MANIFEST_DEFS)) {
                schemaRef("$MANIFEST.${value.removePrefix(MANIFEST_DEFS)}").getValue(REF)
            } else {
                manifestRefs(value)
            }
        }

        is List<*> -> node.map(::manifestRefs)
        else -> node
    }

    private const val DEFS = "\$defs"
    private const val REF = "\$ref"
    private const val MANIFEST_DEFS = "#/\$defs/"

    /** The query bodies and answers. */
    private fun querySchemas(): Map<String, Any?> = linkedMapOf(
        SNAPSHOT_RECORD to snapshotRecord(),
        EVENT_STREAM_RECORD to eventStreamRecord(),
        QUERY_REQUEST to queryRequest(),
        PAGED_LIST to objectSchema(
            mapOf("total" to type("integer", nullable = true), "exact" to BOOLEAN, "list" to array(obj())),
            listOf("list"),
            "One page; `total` is null when the query asked for no total, and a lower bound when `exact` is false.",
        ),
        CURSOR_PAGE to objectSchema(
            mapOf("list" to array(obj()), "nextCursor" to NULLABLE_STRING),
            listOf("list"),
            "One page of a cursor; `nextCursor` is null after the last.",
        ),
        COUNT_RESULT to objectSchema(mapOf("count" to INTEGER, "exact" to BOOLEAN), listOf("count")),
        QUERY_DESCRIPTOR to type(
            "object",
            description = "What the query model can do on the backend the node runs: operations, fields and limits.",
        ),
    )

    private fun commandResult(): Map<String, Any?> = objectSchema(
        linkedMapOf(
            "format" to INTEGER,
            "commandId" to STRING,
            "requestId" to STRING,
            "correlationId" to STRING,
            "ref" to schemaRef(AGGREGATE_REF),
            "hop" to schemaRef(HOP),
            "function" to schemaRef(FUNCTION_INFO),
            "status" to obj("type" to "string", "enum" to listOf("succeeded", "rejected", "failed")),
            "version" to type("integer", nullable = true),
            "duplicate" to BOOLEAN,
            "error" to nullable(schemaRef(ERROR_INFO)),
            "results" to type("object"),
            "derived" to array(STRING),
            "signalTime" to INTEGER,
            "transformedFrom" to nullable(schemaRef(TRANSFORMED_FROM)),
            "chainEnded" to BOOLEAN,
            "pending" to array(schemaRef(HOP)),
        ),
        listOf("format", "commandId", "requestId", "correlationId", "ref", "hop", "function", "status", "signalTime"),
        "How a command ended at a hop of its wait; `chainEnded` and `pending` (the hops not reached) " +
            "mark a chain that ended before its plan did.",
    )

    private fun aggregateRef(): Map<String, Any?> = objectSchema(
        mapOf(
            "type" to schemaRef(AGGREGATE_TYPE),
            "tenantId" to STRING,
            "id" to STRING,
        ),
        listOf("type", "tenantId", "id"),
    )

    private fun hop(): Map<String, Any?> = objectSchema(
        mapOf(
            "stage" to obj("type" to "string", "enum" to listOf("accepted", "committed", "handled")),
            "context" to NULLABLE_STRING,
            "subscription" to NULLABLE_STRING,
        ),
        listOf("stage"),
    )

    private fun functionInfo(): Map<String, Any?> = objectSchema(
        mapOf(
            "kind" to obj(
                "type" to "string",
                "enum" to listOf("command", "project", "react", "saga", "snapshot", "reservation", "timers"),
            ),
            "context" to STRING,
            "processor" to STRING,
            "name" to STRING,
        ),
        listOf("kind", "context", "processor", "name"),
    )

    private fun problem(): Map<String, Any?> = objectSchema(
        linkedMapOf(
            "type" to type("string", description = "`urn:wow:error:<code>`, or `about:blank` without a code."),
            "title" to STRING,
            "status" to INTEGER,
            "detail" to STRING,
            "code" to STRING,
            "retry" to obj(
                "type" to "string",
                "enum" to WireNames.namesOf<Retry>(),
                "description" to "What a retry may do, as the error catalog declares it for the code; with " +
                    "`Retry-After` when a retry may get past the problem.",
            ),
            "violations" to array(schemaRef(VIOLATION)),
            "result" to schemaRef(COMMAND_RESULT),
        ),
        listOf("type", "title", "status", "retry"),
        "An RFC 9457 problem; `result` is the command's when it has one, and the rejection's " +
            "extensions are members of their own names.",
    )

    private fun stateView(): Map<String, Any?> = objectSchema(
        linkedMapOf(
            "ref" to schemaRef(AGGREGATE_REF),
            "version" to INTEGER,
            "scope" to schemaRef(SCOPE),
            "deleted" to BOOLEAN,
            "tags" to TAGS,
            "state" to obj("description" to "The aggregate's state."),
        ),
        listOf("ref", "version", "scope", "deleted", "tags", "state"),
    )

    private fun commitView(): Map<String, Any?> = objectSchema(
        linkedMapOf(
            "version" to INTEGER,
            "commitTime" to INTEGER,
            "commandId" to STRING,
            "requestId" to STRING,
            "commandType" to STRING,
            "events" to array(schemaRef(EVENT_VIEW)),
            "scope" to schemaRef(SCOPE),
            "deleted" to BOOLEAN,
            "tags" to TAGS,
            "state" to obj("description" to "The aggregate's state after the commit."),
            "operator" to nullable(schemaRef(OPERATOR)),
        ),
        listOf("version", "commitTime", "commandId", "requestId", "commandType", "events", "scope", "deleted", "state"),
    )

    private fun snapshotRecord(): Map<String, Any?> = objectSchema(
        linkedMapOf(
            "tenantId" to STRING,
            "aggregateId" to STRING,
            "version" to INTEGER,
            "ownerId" to NULLABLE_STRING,
            "spaceId" to NULLABLE_STRING,
            "deleted" to BOOLEAN,
            "tags" to TAGS,
            "firstEventTime" to INTEGER,
            "lastEventTime" to INTEGER,
            "operator" to nullable(schemaRef(OPERATOR)),
            "firstOperator" to nullable(schemaRef(OPERATOR)),
            "state" to obj("description" to "The aggregate's state."),
        ),
        description = "An aggregate's snapshot as a query returns it; a projection leaves fields out.",
    )

    private fun eventStreamRecord(): Map<String, Any?> = objectSchema(
        linkedMapOf(
            "aggregateId" to STRING,
            "tenantId" to STRING,
            "version" to INTEGER,
            "commitTime" to INTEGER,
            "commandId" to STRING,
            "requestId" to STRING,
            "commandType" to STRING,
            "ownerId" to NULLABLE_STRING,
            "spaceId" to NULLABLE_STRING,
            "operator" to nullable(schemaRef(OPERATOR)),
            "events" to array(schemaRef(EVENT_VIEW)),
        ),
        description = "An event stream, the commit of one command: `ownerId`, `spaceId` and `operator` " +
            "are null when unset; a projection leaves fields out.",
    )

    private fun queryRequest(): Map<String, Any?> = objectSchema(
        linkedMapOf(
            "kind" to obj("type" to "string", "enum" to WowHttpContract.QUERY_KINDS),
            "filter" to objectSchema(mapOf("op" to STRING), listOf("op"), "A filter expression of the query protocol."),
            "projection" to objectSchema(mapOf("include" to array(STRING), "exclude" to array(STRING))),
            "sort" to array(
                objectSchema(
                    mapOf("field" to STRING, "direction" to obj("type" to "string", "enum" to listOf("asc", "desc"))),
                    listOf("field", "direction"),
                ),
            ),
            "limit" to INTEGER,
            "pagination" to objectSchema(mapOf("index" to INTEGER, "size" to INTEGER)),
            "total" to obj("type" to "string", "enum" to listOf("exact", "bounded", "none")),
            "size" to INTEGER,
            "cursor" to NULLABLE_STRING,
            "elements" to array(type("object")),
            "groupBy" to array(type("object")),
            "metrics" to array(type("object")),
            "having" to type("object", nullable = true),
        ),
        listOf("kind"),
        "A query: its `kind`, and the members of that kind's query.",
    )
}
