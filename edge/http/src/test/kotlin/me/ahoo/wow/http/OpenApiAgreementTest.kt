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

package me.ahoo.wow.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.route.RouteCall
import me.ahoo.wow.api.route.RouteRefusal
import me.ahoo.wow.api.schedule.JobScope
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.wire.ScopeHeader
import me.ahoo.wow.api.wire.WaitHeader
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.problem.ErrorStatuses
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.http.route.ServiceRoutes
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.route.RouteRequest
import me.ahoo.wow.kernel.route.ServiceRouteHandler
import me.ahoo.wow.kernel.schedule.ScheduleJob
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.RouteParameterManifest
import me.ahoo.wow.manifest.ServiceRouteManifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.backend.QueryBackendStorages
import me.ahoo.wow.query.dataset.DatasetQueryGateway
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.descriptor.QueryModelDescriptor
import me.ahoo.wow.query.error.QueryBusyException
import me.ahoo.wow.query.error.QueryDatasetUnavailableException
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.error.QueryStorageUnavailableException
import me.ahoo.wow.query.error.QueryTimeoutException
import me.ahoo.wow.query.memory.InMemoryDatasetStorage
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.policy.ScopeQueryPolicy
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.runtime.InFlightLimit
import me.ahoo.wow.runtime.snapshot.SnapshotStatus
import me.ahoo.wow.runtime.state.ReplayGate
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import tools.jackson.databind.JsonNode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.typeOf
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import me.ahoo.wow.api.lifecycle.Lifecycle as Lifecycles

/**
 * The OpenAPI document `GET /wow/openapi` serves, against what its routes read and answer (#6811 to #6814). One node
 * serves every kind of route Wow mounts: commands by id and by no id, those of an aggregate whose id is its owner's, the
 * lifecycle and operational commands, the jobs', state reads and traces, the snapshot and events models with their
 * descriptors, a dataset's routes, service routes with and without a body, and the framework's endpoints. Every
 * operation of its document is sent the same probes, and:
 *
 * - every answer a probe gets is one the operation lists: its status, the problem's code among that status's codes,
 *   and each response header it carries that the document names (`Retry-After`, `ETag`, `Wow-Rebuilding`);
 * - a header is one of the operation's parameters exactly when the route reads it, as far as a value it refuses shows:
 *   `Wow-Space`, `Wow-Scope`, `If-Match`, `Wow-Wait`, `Wow-Wait-Timeout`, `If-None-Match` and `Last-Event-ID`;
 * - an operation lists 413 and 415 exactly when it takes a body, and its route answers them;
 * - every response an operation lists is one a probe got: a store that is down, a full bulkhead, a backend that fails,
 *   a wait that times out, an aggregate deleted, rejected or not found, a request id reused, a route's refusal.
 *
 * Where the two disagree the route wins: the document is the one to change, unless the route is the defect.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiAgreementTest {
    private val snapshots = InMemoryQueryStorage()
    private val rows = InMemoryDatasetStorage()

    @Volatile
    private var rebuilding = false

    /** While set, what the query models' and the dataset's gateways fail with: a backend's failures, one at a time. */
    @Volatile
    private var failure: (() -> Throwable)? = null

    /** While set, the log's replays find its bulkhead full, as a state read's are refused. */
    @Volatile
    private var replaysBusy = false

    private val node = HttpNode(
        manifest = MANIFEST,
        aggregates = listOf(basketModel),
        authorizer = operatorsOnly,
        jobs = listOf(REPORT),
        rebuilds = true,
        minVersionWait = 100.milliseconds,
        // One command in flight: a second, while the first's append is held, is busy.
        inFlight = InFlightLimit(max = 1),
        replayGate = object : ReplayGate {
            override suspend fun <T> replay(ref: AggregateRef, read: suspend () -> T): T =
                if (replaysBusy) throw QueryBusyException("log", 1) else read()
        },
        serviceRoutes = ServiceRoutes(listOf(handler(SUMMARY), handler(ADD_NOTE))),
    ) { log ->
        HttpQueries(
            snapshots = listOf(Failing(cartSnapshots(snapshots, manifest = MANIFEST))),
            events = listOf(Failing(cartEvents(log, manifest = MANIFEST))),
            datasets = datasets(),
            snapshotStatus = SnapshotStatus { rebuilding },
            displayShared = true,
        )
    }

    private val document: JsonNode = node.get(WowPaths.OPENAPI, user = OPERATOR).body!!

    /** What the probes' answers carry that their operations do not list. */
    private val unlisted: MutableList<String> = CopyOnWriteArrayList()

    /**
     * The responses the probes' answers were, each by its component's name, or by its operation and status for a
     * response of its own (a `200`, a `304`).
     */
    private val answered: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        // The aggregates the routes by id name, the record the queries read, and a row of the dataset.
        node.post("/$CONTEXT/cart/$CART/open-cart", """{"note":"n"}""", user = OPERATOR).status.assert().isEqualTo(200)
        node.post("/$CONTEXT/basket/open-basket", """{"note":"n"}""", user = OPERATOR).status.assert().isEqualTo(200)
        snapshots.save(
            SnapshotRecord(
                ref = cartType.ref(TENANT, CART),
                version = 1,
                schemaFingerprint = "fp",
                state = """{"items":0,"note":"n"}""",
                scope = Scope(OPERATOR, null),
                firstEventTime = 1000L,
                lastEventTime = 1001L,
            ),
        )
        rows.put(
            "sales",
            listOf(
                WowJson.mapper.createObjectNode().put("id", "s-1").put("tenant_id", TENANT).put("owner_id", OPERATOR)
                    .put("amount", 1),
            ),
        )
    }

    /** An operation of the document: its method, its path and what the document says of it. */
    private inner class Operation(val method: String, val path: String, val described: JsonNode) {
        val name: String = "${method.uppercase()} $path"
        val body: Boolean = described.has("requestBody")
        val command: Boolean = (method.uppercase() to path) in COMMANDS
        val streamedQuery: Boolean = method == "post" && path.endsWith("/query")

        /**
         * Who sends it: the operator for an operational command (the snapshot rebuild, a job's), which only it may
         * send, and for a read; another caller for any other command, so that no command changes what the reads read.
         */
        val user: String = if (!command || "/wow.snapshot." in path || "/wow.schedule/" in path) OPERATOR else WRITER

        /** The names of its parameters: a shared one by its component, an inline one by its name. */
        val parameters: List<String> = described.path("parameters").values().map { parameter ->
            parameter["\$ref"]?.asString()?.substringAfterLast('/') ?: parameter["name"].asString()
        }

        /** The response it lists for [status], resolved, or null. */
        fun response(status: Int): JsonNode? = described["responses"][status.toString()]?.let(::resolve)

        val uri: String = path.replace("{id}", idOf(path, command)).replace("{context}", CONTEXT)
            .replace("{dataset}", "sales")
            .replace("{day}", "mon")

        /** A body its route reads, if it takes one. */
        val payload: String? = when {
            !body -> null
            streamedQuery -> COUNT
            path == "/$CONTEXT/notes" -> """{"text":"a"}"""
            else -> payloadOf(described["requestBody"]["content"]["application/json"]["schema"])
        }

        /** Sends it as [probe] says, and checks the answer is one it lists. */
        fun send(
            probe: String,
            user: String? = this.user,
            headers: Map<String, String> = emptyMap(),
            body: String? = payload,
            contentType: String = JSON,
            uri: String = this.uri,
        ): Answer {
            val request = node.client.method(HttpMethod.valueOf(method.uppercase())).uri(uri).headers { http ->
                user?.let { http.set("X-User", it) }
                headers.forEach { (name, value) -> http.set(name, value) }
            }
            val spec = body?.let { request.header(HttpHeaders.CONTENT_TYPE, contentType).bodyValue(it) } ?: request
            val result = spec.exchange().expectBody(String::class.java).returnResult()
            val type = result.responseHeaders.contentType?.toString()
            val answer = Answer(
                status = result.status.value(),
                contentType = type,
                body = result.responseBody?.takeIf { it.isNotBlank() && type?.contains("json") == true }
                    ?.let { json.readTree(it) },
                headers = result.responseHeaders,
            )
            assertLists(probe, answer)
            return answer
        }

        /** Records in [unlisted] what of [answer] the operation does not list. */
        private fun assertLists(probe: String, answer: Answer) {
            val said = "$name, $probe: ${answer.status} ${answer.body?.get("code") ?: ""}"
            val response = response(answer.status) ?: run {
                unlisted += "$said: the status is not listed"
                return
            }
            answer.body?.get("code")?.asString()?.let { code ->
                // A domain code no table lists is a rejection like any: the 422 a domain command lists says so.
                val rejection = command && answer.status == DOMAIN_REJECTION && !code.startsWith(FrameworkCodes.PREFIX)
                if (code !in codesOf(response) && !rejection) unlisted += "$said: the code is not listed"
            }
            ANSWER_HEADERS.filter { answer.headers.containsHeader(it) }.forEach { header ->
                if (!response.path("headers").has(header)) unlisted += "$said: $header is not declared"
            }
            if (answer.status == OK && answer.contentType != null) {
                val type = answer.contentType.substringBefore(';')
                if (!response["content"].has(type)) unlisted += "$said: $type is not listed"
            }
            answered += responseName(answer.status.toString())
        }

        /** The name of the response it lists for [status]: its component's, or its own. */
        fun responseName(status: String): String =
            described["responses"][status]["\$ref"]?.asString()?.substringAfterLast('/') ?: "$name $status"

        override fun toString(): String = name
    }

    private val operations: List<Operation> = document["paths"].properties().flatMap { (path, item) ->
        item.properties().map { (method, operation) -> Operation(method, path, operation) }
    }

    private fun resolve(response: JsonNode): JsonNode =
        response["\$ref"]?.asString()?.let { document["components"]["responses"][it.substringAfterLast('/')] }
            ?: response

    private fun codesOf(response: JsonNode): List<String> =
        response.path("x-wow-error-codes").values().map { it.asString() }

    /** A body that reads as [schema]: each required property, a string `x`, a number 1, a list, an object. */
    private fun payloadOf(schema: JsonNode): String = json.writeValueAsString(valueOf(schema))

    private fun valueOf(schema: JsonNode): Any? {
        val name = schema["\$ref"]?.asString()?.substringAfterLast('/')
        val resolved = name?.let { document["components"]["schemas"][it] } ?: schema
        val type = resolved["type"]?.let { if (it.isArray) it[0].asString() else it.asString() }
        return when (type) {
            "string" -> "x"
            "integer", "number" -> 1
            "boolean" -> false
            "array" -> listOf(valueOf(resolved["items"]))
            else -> resolved.path("required").values().map { it.asString() }
                .associateWith { valueOf(resolved["properties"][it]) }
        }
    }

    @Test
    fun `every answer of every route should be one its operation lists, every header it reads one of its parameters`() {
        operations.assert().hasSizeGreaterThan(30)
        val failed = mutableListOf<String>()
        operations.forEach { operation -> runCatching { probe(operation) }.exceptionOrNull()?.let { failed += "$it" } }
        unlisted.assert().describedAs("What the routes answer that their operations do not list").isEmpty()
        failed.assert().describedAs("What the routes read and answer that their operations do not say").isEmpty()
        scenarios()
        unlisted.assert().describedAs("What the scenarios' answers carry that their operations do not list").isEmpty()
        val listed = operations.flatMap { operation ->
            operation.described["responses"].propertyNames().map(operation::responseName)
        }.toSortedSet()
        (listed - answered).assert().describedAs("The responses no probe got").isEmpty()
    }

    private fun operation(name: String): Operation = operations.single { it.name == name }

    /**
     * The answers that take an aggregate in a state of its own, a query that matches nothing, a route's refusal, and a
     * node with as many commands in flight as it takes.
     */
    private fun scenarios(): Unit = runBlocking {
        val add = operation("POST /$CONTEXT/cart/{id}/add-item")
        val open = operation("POST /$CONTEXT/cart/{id}/open-cart")
        val checkout = operation("POST /$CONTEXT/cart/{id}/checkout")
        open.send("a cart to delete", uri = "/$CONTEXT/cart/c-gone/open-cart")
        operation("DELETE /$CONTEXT/cart/{id}").send("a cart to delete", uri = "/$CONTEXT/cart/c-gone", body = "{}")
        add.send("a deleted cart", uri = "/$CONTEXT/cart/c-gone/add-item")
        open.send("a cart to check out", uri = "/$CONTEXT/cart/c-closed/open-cart")
        checkout.send("a rejection", uri = "/$CONTEXT/cart/c-closed/checkout", body = """{"note":"closed"}""")
        checkout.send("a decision that throws", uri = "/$CONTEXT/cart/c-closed/checkout", body = """{"note":"throw"}""")
        operation("GET /$CONTEXT/cart/{id}/state")
            .send("a version not reached", uri = "/$CONTEXT/cart/$CART/state?minVersion=99")
        val none = """{"kind":"single","filter":{"op":"aggregate-id","value":"none"}}"""
        operation("POST /$CONTEXT/cart/snapshot/query").send("a single that matches nothing", body = none)
        operation("POST /$CONTEXT/cart/events/query").send("a single that matches nothing", body = none)
        val dataset = "/wow/dataset/{context}/{dataset}/query"
        operation("POST $dataset")
            .send("a single that matches nothing", body = """{"kind":"single","filter":{"op":"id","value":"none"}}""")
        operation("POST $dataset").send("a dataset not served", uri = "/wow/dataset/$CONTEXT/none/query")
        operation("GET $dataset").send("a dataset not served", uri = "/wow/dataset/$CONTEXT/none/query")
        operation("GET /$CONTEXT/summaries/{day}").send("the route's refusal", uri = "/$CONTEXT/summaries/closed")
        val gate = CompletableDeferred<Unit>()
        node.appendGate = gate
        try {
            val held = async(Dispatchers.IO) {
                add.send("a command held in flight", uri = "/$CONTEXT/cart/c-held/add-item")
            }
            withTimeout(10.seconds) {
                while (node.inFlight.inFlight < 1) delay(5)
            }
            operations.filter { it.command }.forEach { it.send("beyond the node's in-flight bound") }
            gate.complete(Unit)
            held.await()
        } finally {
            node.appendGate = null
        }
    }

    private fun probe(operation: Operation) {
        val baseline = operation.send("as it is")
        // What the public port refuses of every request before its route reads it.
        operation.send("an unknown Wow header", headers = mapOf("Wow-Nope" to "1"))
            .assertProblem(400, FrameworkCodes.REQUEST_MALFORMED, operation)
        operation.send("Wow-Call", headers = mapOf(WowHeaders.CALL to "x"))
            .assertProblem(403, FrameworkCodes.AUTH_FORBIDDEN, operation)
        operation.send("Wow-Reveal", headers = mapOf(WowHeaders.REVEAL to "x"))
            .assertProblem(403, FrameworkCodes.AUTH_FORBIDDEN, operation)
        operation.send("no credentials", user = null)
            .assertProblem(401, FrameworkCodes.AUTH_UNAUTHENTICATED, operation)
        operation.send("an Accept without JSON", headers = mapOf(HttpHeaders.ACCEPT to "text/html"))
            .status.assert().describedAs(operation.name).isEqualTo(NOT_ACCEPTABLE)
        // Wow-Space is read on every route: a space the caller does not act in is 403.
        operation.send("a space the caller does not act in", headers = mapOf(WowHeaders.SPACE to "elsewhere"))
            .assertProblem(403, FrameworkCodes.AUTH_FORBIDDEN, operation)
        operation.parameters.assert().describedAs(operation.name).contains(WowHeaders.SPACE)
        bodies(operation)
        headers(operation, baseline)
        statuses(operation)
    }

    /** A body of another type than JSON is 415, and one over the codecs' limit 413, on a route that reads one alone. */
    private fun bodies(operation: Operation) {
        val plain = operation.send("a body sent as text/plain", body = operation.payload ?: "x", contentType = TEXT)
        if (!operation.body) {
            plain.status.assert().describedAs(operation.name).isNotEqualTo(UNSUPPORTED_MEDIA_TYPE)
            operation.described["responses"].has("413").assert().describedAs(operation.name).isFalse()
            operation.described["responses"].has("415").assert().describedAs(operation.name).isFalse()
            return
        }
        plain.status.assert().describedAs(operation.name).isEqualTo(UNSUPPORTED_MEDIA_TYPE)
        val large = operation.payload!!.replaceFirst("\"x\"", "\"${"a".repeat(LARGE)}\"").let {
            if (it.length > LARGE) it else """{"pad":"${"a".repeat(LARGE)}"}"""
        }
        operation.send("a body over the codecs' limit", body = large).status.assert().describedAs(operation.name)
            .isEqualTo(CONTENT_TOO_LARGE)
    }

    /** Each header a value of which the route refuses, or answers 304 for, is one of the operation's parameters. */
    private fun headers(operation: Operation, baseline: Answer) {
        fun reads(header: String, value: String, refused: (Answer) -> Boolean, accept: String? = null): Boolean {
            val headers = mapOf(header to value) + listOfNotNull(accept?.let { HttpHeaders.ACCEPT to it })
            return refused(operation.send("$header: $value", headers = headers))
        }
        val read = linkedMapOf(
            WowHeaders.SCOPE to reads(WowHeaders.SCOPE, BAD_SCOPE, { it.detail() in SCOPE_REFUSALS }),
            WowHeaders.IF_MATCH to reads(WowHeaders.IF_MATCH, BAD_IF_MATCH, { it.detail() == IF_MATCH_REFUSAL }),
            WowHeaders.WAIT to reads(WowHeaders.WAIT, BAD_WAIT, { it.detail() == WAIT_REFUSAL }),
            WowHeaders.WAIT_TIMEOUT to reads(WowHeaders.WAIT_TIMEOUT, BAD_TIMEOUT, { it.detail() == TIMEOUT_REFUSAL }),
            LAST_EVENT_ID to if (operation.streamedQuery) {
                val streamed = operation.send(
                    "$LAST_EVENT_ID: $BAD_CURSOR",
                    headers = mapOf(LAST_EVENT_ID to BAD_CURSOR, HttpHeaders.ACCEPT to EVENT_STREAM),
                    body = LIST,
                )
                streamed.status == BAD_REQUEST && streamed.body?.get("code")?.asString() == FrameworkCodes.QUERY_INVALID
            } else {
                reads(LAST_EVENT_ID, BAD_CURSOR, { it.code() == FrameworkCodes.QUERY_INVALID })
            },
            IF_NONE_MATCH to baseline.headers.eTag.let { tag ->
                if (baseline.status == OK && tag != null) {
                    reads(IF_NONE_MATCH, tag, { it.status == NOT_MODIFIED })
                } else {
                    reads(IF_NONE_MATCH, "\"none\"", { it.status == NOT_MODIFIED })
                }
            },
        )
        read.forEach { (header, reads) ->
            (header in operation.parameters).assert().describedAs("${operation.name}: $header is read: $reads")
                .isEqualTo(reads)
        }
        if (IF_NONE_MATCH in operation.parameters) {
            baseline.status.assert().describedAs("${operation.name} answers its baseline").isEqualTo(OK)
        }
        // Read without a value they refuse: the request id (held by `a request id`), the trace context
        // (TraceHeadersTest). The document lists them where they are read.
        (WowHeaders.REQUEST_ID in operation.parameters).assert().describedAs(operation.name)
            .isEqualTo(operation.command || operation.path in SERVICE_ROUTES)
        listOf("traceparent", "tracestate", "baggage").forEach { header ->
            (header in operation.parameters).assert().describedAs(operation.name).isEqualTo(operation.command)
        }
    }

    /** The other statuses a route answers: of an aggregate that does not exist, a store that is down, a wait. */
    private fun statuses(operation: Operation) {
        if ("{id}" in operation.path) {
            operation.send("an aggregate that does not exist", uri = operation.path.replace("{id}", "missing"))
        }
        if (operation.command) {
            operation.send("If-Match of another version", headers = mapOf(WowHeaders.IF_MATCH to "\"99\""))
            ran { operation.send("a wait that ends at accepted", headers = mapOf(WowHeaders.WAIT to "accepted")) }
            // No subscription `audit` runs: the wait cannot end before its timeout, whenever its command commits.
            ran {
                operation.send(
                    "a wait that times out",
                    headers = mapOf(WowHeaders.WAIT to "handled(audit)", WowHeaders.WAIT_TIMEOUT to "50ms"),
                )
            }
            operation.send("an event stream", headers = mapOf(HttpHeaders.ACCEPT to EVENT_STREAM))
            operation.send("a body that does not read", body = "[")
            node.appendGate = CompletableDeferred<Unit>().apply {
                completeExceptionally(IllegalStateException("The append failed."))
            }
            try {
                operation.send("an append that fails")
            } finally {
                node.appendGate = null
            }
            if (operation.path.startsWith("/$CONTEXT/cart/{id}/")) {
                // The request id another command of the aggregate committed under.
                val reused = mapOf(WowHeaders.REQUEST_ID to "reused ${operation.name}")
                operation("POST /$CONTEXT/cart/{id}/add-item").send("a request id", headers = reused)
                operation.send("the request id of another command", headers = reused)
            }
        }
        if ("/${WowPaths.STATE}" in operation.path) {
            replaysBusy = true
            try {
                operation.send("a replay its bulkhead has no permit for")
            } finally {
                replaysBusy = false
            }
        }
        if (operation.streamedQuery) {
            operation.send("a query that does not read", body = "{\"kind\":\"nope\"}")
        }
        node.unavailable = true
        try {
            operation.send("a store that is down")
        } finally {
            node.unavailable = false
        }
        failuresOf(operation).forEach { (probe, failing) ->
            failure = failing
            try {
                operation.send(probe)
            } finally {
                failure = null
            }
        }
    }

    /**
     * [sent], a probe answered before its command may have run: at `accepted` (202), the command then runs detached,
     * or when its wait timed out before the commit (504), the command then goes on. Waits until that command has run
     * and the node holds none in flight. The node takes one command at a time, and refuses another meanwhile as busy
     * (429): a probe sent next would otherwise get that 429 whenever the command is still running, as on a loaded
     * machine, instead of the answer the probe is for (#6975).
     */
    private fun ran(sent: () -> Answer): Answer {
        val before = node.executed.get()
        val answer = sent()
        if (answer.status == ACCEPTED || answer.status == GATEWAY_TIMEOUT) {
            runBlocking {
                withTimeout(10.seconds) {
                    while (node.executed.get() == before || node.inFlight.inFlight > 0) delay(5)
                }
            }
        }
        return answer
    }

    /**
     * What a query or a descriptor of [operation] may fail with in its backend: a failure (500), a storage that is
     * down (503); a query also its timeout (504) and its store's bulkhead (429); a dataset's, also not queryable now.
     */
    private fun failuresOf(operation: Operation): List<Pair<String, () -> Throwable>> {
        if (!operation.path.endsWith("/${QueryRoutes.SEGMENT}")) return emptyList()
        val described = listOf<Pair<String, () -> Throwable>>(
            "a backend that fails" to { QueryExecutionException("The backend failed.") },
            "a query storage that is down" to { QueryStorageUnavailableException() },
        )
        val queried = described + listOf<Pair<String, () -> Throwable>>(
            "a query past its timeout" to { QueryTimeoutException(java.time.Duration.ofSeconds(1)) },
            "a store's bulkhead that is full" to { QueryBusyException("store", 1) },
        )
        val dataset = listOf<Pair<String, () -> Throwable>>(
            "a dataset not queryable now" to { QueryDatasetUnavailableException(SALES, "needs revision") },
        ).takeIf { operation.path.startsWith(WowPaths.DATASETS) }.orEmpty()
        return (if (operation.streamedQuery) queried else described) + dataset
    }

    @Test
    fun `a problem's Retry-After should be declared on exactly the responses of a code a retry may get past`() {
        val statuses = ErrorStatuses(node.manifest)
        val responses = document["components"]["responses"].properties()
        responses.assert().hasSizeGreaterThan(20)
        responses.forEach { (name, response) ->
            val context = name.substringBefore('.').takeUnless { it == "wow" }
            val retried = codesOf(response).any { code ->
                statuses.problemOf(code, "probe", context).retryAfterHeader != null
            }
            response.path("headers").has(RETRY_AFTER).assert().describedAs(name).isEqualTo(retried)
        }
        // As a route answers it: a command, a state read and a query on a store that is down.
        node.unavailable = true
        try {
            listOf(
                node.post("/$CONTEXT/cart/$CART/add-item", """{"sku":"s"}""", user = OPERATOR),
                node.get("/$CONTEXT/cart/$CART/state", user = OPERATOR),
                node.post("/$CONTEXT/cart/events/query", COUNT, user = OPERATOR),
            ).forEach { answer ->
                answer.status.assert().describedAs(answer.body.toString()).isEqualTo(SERVICE_UNAVAILABLE)
                answer.headers.getFirst(RETRY_AFTER).assert().isNotNull()
            }
        } finally {
            node.unavailable = false
        }
    }

    @Test
    fun `a problem's members should be the schema's, the version a rejection or a state read answers among them`() {
        val members = document["components"]["schemas"]["wow.Problem"]["properties"].propertyNames().toSet()
        node.post("/$CONTEXT/cart/c-members/open-cart", """{"note":"n"}""", user = OPERATOR)
        val rejected = node.post("/$CONTEXT/cart/c-members/checkout", """{"note":"closed"}""", user = OPERATOR)
        rejected.status.assert().isEqualTo(422)
        rejected.body!!["version"].asInt().assert().isEqualTo(1)
        rejected.body.propertyNames().toSet().assert().isSubsetOf(members)
        val behind = node.get("/$CONTEXT/cart/$CART/state?minVersion=99", user = OPERATOR)
        behind.status.assert().isEqualTo(409)
        behind.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.QUERY_MIN_VERSION_NOT_REACHED)
        behind.body["version"].asInt().assert().isEqualTo(1)
        behind.body.propertyNames().toSet().assert().isSubsetOf(members)
    }

    @Test
    fun `a snapshot model's answers should carry Wow-Rebuilding while it is rebuilt, as its operations declare`() {
        rebuilding = true
        val before = unlisted.size
        try {
            val described = operations.filter { operation ->
                operation.described["responses"].properties().any { (_, response) ->
                    resolve(response).path("headers").has(WowHeaders.REBUILDING)
                }
            }.map { it.name }
            described.assert().containsExactlyInAnyOrder(
                "POST /$CONTEXT/cart/snapshot/query",
                "GET /$CONTEXT/cart/snapshot/query",
            )
            operations.filter { it.streamedQuery || it.path.endsWith("/query") }.forEach { operation ->
                val answer = operation.send("while rebuilding")
                answer.status.assert().describedAs(operation.name).isEqualTo(OK)
                (answer.headers.getFirst(WowHeaders.REBUILDING) == "true").assert().describedAs(operation.name)
                    .isEqualTo(operation.name in described)
                answer.headers.eTag?.let { tag ->
                    val unchanged = operation.send("while rebuilding, unchanged", headers = mapOf(IF_NONE_MATCH to tag))
                    unchanged.status.assert().isEqualTo(NOT_MODIFIED)
                    (unchanged.headers.getFirst(WowHeaders.REBUILDING) == "true").assert()
                        .describedAs(operation.name).isEqualTo(operation.name in described)
                }
            }
            unlisted.drop(before).assert().isEmpty()
        } finally {
            rebuilding = false
        }
    }

    @Test
    fun `a command's event stream should answer as its operation says, before and after the command is accepted`() {
        val described = document["components"]["responses"]["wow.command.200"]["description"].asString()
        described.assert().contains(
            "a refusal before the command is accepted is the problem it is",
            "once accepted, the answer is 200 and no other status",
            "`: keep-alive`",
            "`wow.wait.timeout` at the last hop reached",
            "`end` with `{\"reason\":\"complete\"}`",
        )
        val open = operation("POST /$CONTEXT/cart/{id}/open-cart")
        val stream = mapOf(HttpHeaders.ACCEPT to EVENT_STREAM)
        // Before it is accepted: the problem, with its status.
        open.send("an event stream with no credentials", user = null, headers = stream).status.assert().isEqualTo(401)
        // Accepted: 200, a comment at once, then one event per hop reached, the last a FAILED result at the last hop
        // reached when the wait times out, then `end`, which says the stream is whole.
        val waits = mapOf(WowHeaders.WAIT to "committed > handled(audit)", WowHeaders.WAIT_TIMEOUT to "100ms")
        val result = node.client.post().uri("/$CONTEXT/cart/c-stream/open-cart").header("X-User", WRITER)
            .headers { http -> (stream + waits).forEach { (name, value) -> http.set(name, value) } }
            .header(HttpHeaders.CONTENT_TYPE, JSON).bodyValue("{}")
            .exchange().expectBody(String::class.java).returnResult()
        result.status.value().assert().isEqualTo(OK)
        val entries = result.responseBody.orEmpty().split("\n\n").filter { it.isNotBlank() }
        entries.first().assert().startsWith(":")
        val events = entries.filterNot { it.startsWith(":") }.map { entry ->
            entry.lines().associate { it.substringBefore(':') to it.substringAfter(':').trim() }
        }
        events.map { it["event"] }.assert().containsExactly("committed", "committed", "end")
        json.readTree(events.last().getValue("data"))["reason"].asString().assert().isEqualTo("complete")
        val last = json.readTree(events[1].getValue("data"))
        last["status"].asString().assert().isEqualTo("failed")
        last["error"]["code"].asString().assert().isEqualTo(FrameworkCodes.WAIT_TIMEOUT)
    }

    @Test
    fun `a request id should be read where the document lists it, by a command and by a service route`() {
        val command = mapOf(WowHeaders.REQUEST_ID to "r-agreement")
        node.post("/$CONTEXT/cart/c-request/open-cart", "{}", user = OPERATOR, headers = command)
            .body!!["duplicate"].asBoolean().assert().isFalse()
        node.post("/$CONTEXT/cart/c-request/open-cart", "{}", user = OPERATOR, headers = command)
            .body!!["duplicate"].asBoolean().assert().isTrue()
        node.get("/$CONTEXT/summaries/mon", user = OPERATOR, headers = mapOf(WowHeaders.REQUEST_ID to "r-route"))
            .body!!["requestId"].asString().assert().isEqualTo("r-route")
    }

    private fun Answer.detail(): String? = body?.get("detail")?.asString()

    private fun Answer.code(): String? = body?.get("code")?.asString()

    private fun Answer.assertProblem(status: Int, code: String, operation: Operation) {
        this.status.assert().describedAs("${operation.name}: $body").isEqualTo(status)
        code().assert().describedAs(operation.name).isEqualTo(code)
    }

    /** The id a path names: the job's, the aggregate a [command] acts on, apart from the one the reads read. */
    private fun idOf(path: String, command: Boolean): String = when {
        path.startsWith("/$CONTEXT/wow.schedule/") -> REPORT.name
        path.startsWith("/$CONTEXT/basket/") -> if (command) WRITER else OPERATOR
        else -> if (command) "c-command" else CART
    }

    private fun handler(name: String): ServiceRouteHandler = object : ServiceRouteHandler {
        override val context: String = CONTEXT
        override val name: String = name
        override suspend fun handle(call: RouteCall, request: RouteRequest): Any? = when (name) {
            SUMMARY -> when (val day = request.path("day", String::class)) {
                "closed" -> throw RouteRefusal("shop.closed", "The day is closed.")
                else -> mapOf("day" to day, "requestId" to call.requestId)
            }
            else -> request.body<Note>(typeOf<Note>()).let { Unit }
        }
    }

    private fun datasets(): DatasetQueryGateways {
        val datasets = QueryDatasets(
            listOf(
                QueryDataset.of(SALES) {
                    tenant("tenant_id")
                    owner("owner_id")
                    identity("id")
                    storage(InMemoryDatasetStorage.binding("sales"))
                    listOf("id", "tenant_id", "owner_id").forEach { name ->
                        field(name) {
                            types(QueryValueType.STRING)
                            nullable(false)
                        }
                    }
                    field("amount") {
                        types(QueryValueType.INTEGER)
                        nullable(false)
                    }
                },
            ),
        )
        val catalog = QuerySchemaCatalog(
            storages = QueryBackendStorages(datasetBindings = rows, datasets = datasets),
            compiler = QueryModelCompiler.of(datasets = datasets),
            datasets = datasets,
        )
        val gateways = DatasetQueryGateways.of(datasets, catalog, rows, policies = listOf(ScopeQueryPolicy()))
        return DatasetQueryGateways(gateways.all.map(::FailingDataset))
    }

    /** A query model's gateway that fails a count and a descriptor with [failure] while it is set. */
    private inner class Failing(private val gateway: QueryGateway) : QueryGateway by gateway {
        override suspend fun count(query: CountQuery, caller: QueryCaller): CountResult =
            failure?.let { throw it() } ?: gateway.count(query, caller)

        override suspend fun describe(entry: QueryEntry, defaultListSize: Int?): QueryModelDescriptor =
            failure?.let { throw it() } ?: gateway.describe(entry, defaultListSize)
    }

    /** A dataset's gateway that fails as [Failing] does. */
    private inner class FailingDataset(private val gateway: DatasetQueryGateway) : DatasetQueryGateway by gateway {
        override suspend fun count(query: CountQuery, caller: QueryCaller): CountResult =
            failure?.let { throw it() } ?: gateway.count(query, caller)

        override suspend fun describe(entry: QueryEntry, defaultListSize: Int?): QueryModelDescriptor =
            failure?.let { throw it() } ?: gateway.describe(entry, defaultListSize)
    }

    data class Note(val text: String)
    data class OpenBasket(val note: String = "")
    data class PutItem(val sku: String)
    data class BasketOpened(val note: String)
    data class ItemPut(val sku: String)

    class Basket {
        var items = 0
    }

    private companion object {
        const val CART = "c-1"
        val SALES = AggregateType(CONTEXT, "sales")

        /** The caller of the commands, apart from the reads' [OPERATOR]. */
        const val WRITER = "carol"
        const val SUMMARY = "summary"
        const val ADD_NOTE = "addNote"
        const val JSON = "application/json"
        const val TEXT = "text/plain"
        const val EVENT_STREAM = "text/event-stream"
        const val LAST_EVENT_ID = "Last-Event-ID"
        const val IF_NONE_MATCH = "If-None-Match"
        const val RETRY_AFTER = "Retry-After"
        const val COUNT = """{"kind":"count","filter":{"op":"match-all"}}"""
        const val LIST = """{"kind":"list","filter":{"op":"match-all"},"limit":10}"""
        const val OK = 200
        const val ACCEPTED = 202
        const val NOT_MODIFIED = 304
        const val BAD_REQUEST = 400
        const val NOT_ACCEPTABLE = 406
        const val DOMAIN_REJECTION = 422
        const val CONTENT_TOO_LARGE = 413
        const val UNSUPPORTED_MEDIA_TYPE = 415
        const val SERVICE_UNAVAILABLE = 503
        const val GATEWAY_TIMEOUT = 504

        /** Over WebFlux's default in-memory limit, 256 KB. */
        const val LARGE = 300 * 1024

        val ANSWER_HEADERS = listOf(RETRY_AFTER, "ETag", WowHeaders.REBUILDING)

        const val BAD_SCOPE = "(("
        const val BAD_IF_MATCH = "\"1\", \"2\""
        const val BAD_WAIT = "committed("
        const val BAD_TIMEOUT = "soon"
        const val BAD_CURSOR = "not-a-position"

        /** What a route answers each bad value with, when it reads the header. */
        val SCOPE_REFUSALS: Set<String?> = setOfNotNull(
            runCatching { ScopeHeader.parse(BAD_SCOPE) }.exceptionOrNull()?.message,
            runCatching { ScopeHeader.parseQuery(BAD_SCOPE) }.exceptionOrNull()?.message,
        )
        val IF_MATCH_REFUSAL: String = (EntityTags.ifMatch(BAD_IF_MATCH, creates = false) as EntityTags.IfMatch.Refused)
            .detail
        val WAIT_REFUSAL: String? = runCatching { WaitHeader.parse(BAD_WAIT) }.exceptionOrNull()?.message
        val TIMEOUT_REFUSAL: String? = runCatching { WaitHeader.parseTimeout(BAD_TIMEOUT) }.exceptionOrNull()?.message

        val REPORT = ScheduleJob("report", ScheduleRule.Cron("0 0 9 * * *"), JobScope.TENANT)

        val basketModel = aggregate<Basket>(CONTEXT, "basket") {
            id(AggregateIdSource.OWNER)
            initial { Basket() }
            decide<OpenBasket>(Creation.REQUIRED) { _, body -> Decision.events(BasketOpened(body.note)) }
            decide<PutItem> { _, body -> Decision.events(ItemPut(body.sku)) }
            evolve<BasketOpened> { }
            evolve<ItemPut> { items++ }
        }

        /**
         * The query Manifest of the cart, with a basket whose id is its owner's, as the compiler writes it, and two
         * service routes: one without a body, one with.
         */
        val MANIFEST: Manifest = queryManifest().let { manifest ->
            fun command(name: String, creation: Creation) = CommandManifest(
                name = name,
                schema = Manifest.schemaRef(name),
                creation = creation,
                routes = CommandRoutes.of(CONTEXT, "basket", name, creation, unaddressed = true),
            )
            val basket = AggregateManifest(
                name = "basket",
                state = StateManifest(Manifest.schemaRef("Basket")),
                commands = listOf(command("OpenBasket", Creation.REQUIRED), command("PutItem", Creation.NONE)) +
                    Lifecycles.COMMANDS.filterValues { it != TransferOwner::class.java }.keys.sorted().map {
                        SystemCommands.lifecycle(CONTEXT, "basket", it, owned = true)
                    },
                idSource = AggregateIdSource.OWNER,
            )
            val context = manifest.contexts.single()
            manifest.copyWith(
                contexts = listOf(
                    context.copyWith(
                        aggregates = context.aggregates + basket,
                        errors = context.errors + ErrorManifest("shop.closed", 409, "The day is closed."),
                        routes = listOf(
                            ServiceRouteManifest(
                                name = SUMMARY,
                                route = RouteManifest("GET", "/$CONTEXT/summaries/{day}"),
                                parameters = listOf(
                                    RouteParameterManifest(
                                        "day",
                                        RouteParameterManifest.Location.PATH,
                                        mapOf("type" to "string"),
                                    ),
                                ),
                                response = mapOf("type" to "object"),
                                errors = listOf("shop.closed"),
                            ),
                            ServiceRouteManifest(
                                name = ADD_NOTE,
                                route = RouteManifest("POST", "/$CONTEXT/notes"),
                                request = Manifest.refSchema("Note"),
                            ),
                        ),
                    ),
                ),
                defs = manifest.defs + mapOf(
                    "Note" to mapOf(
                        "type" to "object",
                        "properties" to mapOf("text" to mapOf("type" to "string")),
                        "required" to listOf("text"),
                    ),
                    "Basket" to mapOf("type" to "object"),
                ) + listOf("AddItem" to "sku", "PutItem" to "sku", "Checkout" to null, "OpenCart" to null)
                    .plus("OpenBasket" to null)
                    .associate { (command, required) ->
                        command to mapOf(
                            "type" to "object",
                            "properties" to mapOf((required ?: "note") to mapOf("type" to "string")),
                            "required" to listOfNotNull(required),
                        )
                    },
            )
        }

        /** The paths of the service routes. */
        val SERVICE_ROUTES = setOf("/$CONTEXT/summaries/{day}", "/$CONTEXT/notes")

        /** The command routes of [MANIFEST], the jobs' among them, by method and path. */
        val COMMANDS: Set<Pair<String, String>> = MANIFEST.contexts.flatMap { context ->
            context.aggregates.flatMap { it.commands }.flatMap { it.routes } +
                SystemCommands.jobs(context.name).flatMap { it.routes }
        }.map { it.method.uppercase() to it.path }.toSet()
    }
}
