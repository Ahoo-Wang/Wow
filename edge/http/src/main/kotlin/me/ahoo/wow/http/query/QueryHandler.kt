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

package me.ahoo.wow.http.query

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ICountQuery
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.api.wire.ScopeHeader
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.HttpJson
import me.ahoo.wow.http.WowPaths
import me.ahoo.wow.http.caller.Authenticator
import me.ahoo.wow.http.command.Negotiation
import me.ahoo.wow.http.command.RequestHeaders
import me.ahoo.wow.http.handingOver
import me.ahoo.wow.http.problem.ErrorStatuses
import me.ahoo.wow.http.problem.Problem
import me.ahoo.wow.http.problem.ProblemException
import me.ahoo.wow.http.problem.tooLargeProblem
import me.ahoo.wow.http.receiveFirst
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.query.QueryBudget
import me.ahoo.wow.query.QueryBusyException
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryError
import me.ahoo.wow.query.QueryErrors
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryStorageUnavailableException
import me.ahoo.wow.query.QueryTargetException
import me.ahoo.wow.query.QueryTargets
import me.ahoo.wow.query.dataset.DatasetQueryGateway
import me.ahoo.wow.query.json.QueryJson
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.spi.StorageUnavailableException
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBodyOrNull
import org.springframework.web.reactive.function.server.bodyAndAwait
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import tools.jackson.databind.node.ObjectNode
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** A query model of an aggregate type: the `{model}` of its route. */
internal data class QueryTarget(val type: AggregateType, val model: String)

/** One dataset of `GET /wow/dataset`: its name, `{context}.{dataset}`, its title, and its query route's [path]. */
internal data class DatasetEntry(
    val name: String,
    val context: String,
    val dataset: String,
    val title: String?,
    val path: String,
)

/**
 * Answers the query routes (decisions D127, D129, D130, D145, D235), one path for every model:
 *
 * - `POST /{context}/{aggregate}/{model}/query` runs the body's query on [target]'s model, as the caller the request
 *   authenticates as; with `Accept: text/event-stream`, a `list` streams its records as server-sent events
 *   (`event: row`), and a failure after the first record ends the stream with an `event: error` holding the problem;
 * - `GET` of the same path answers the model's capability descriptor, with its version as the `ETag`, and 304 when
 *   `If-None-Match` names it;
 * - `POST` and `GET /wow/dataset/{context}/{dataset}/query` do the same on a dataset (decision D145), and
 *   `GET /wow/dataset` lists the datasets the caller sees.
 *
 * The caller (decision D129) is the request's [Caller] in the scope `Wow-Scope` names, if it names one, as [targets]
 * decide (review R5.C-1): only a caller that may act across scopes names one (403 `wow.auth.forbidden` otherwise); its
 * tenant is decided as for a command (D116), its owner and space are the principal scope, on the HTTP entry. Such a
 * caller may also name several tenants, `tenant=*` or `tenant=("t1" "t2")`, while cross-tenant queries are on
 * (decision D254 ⑦; 403 `wow.auth.forbidden` while off): a cross-tenant query, audited, of which a type with a fixed
 * tenant reads that tenant alone. The routes pass on what the request names; they decide none of it. A query error is a problem with the query codes (D130): `wow.query.malformed`, `wow.query.invalid` (each
 * violation with its code), `wow.query.failed`, `wow.query.timeout`; any other failure is `wow.query.failed`, its cause
 * logged and not shown.
 */
internal class QueryHandler(
    private val queries: HttpQueries,
    private val authenticator: Authenticator,
    private val headers: RequestHeaders,
    private val statuses: ErrorStatuses,
    private val targets: QueryTargets,
    private val scope: CoroutineScope,
) {
    suspend fun query(request: ServerRequest, target: QueryTarget): ServerResponse =
        answering(target.type.context) {
            val caller = caller(request, target.type)
            val body = body(request)
            val eventStream = body.kind == QueryKind.LIST && Negotiation.wantsEventStream(request)
            run(
                checkNotNull(queries.gateway(target.type, target.model)),
                body,
                caller,
                eventStream,
                lastEventId(request),
                target.rebuilding()
            )
        }

    suspend fun describe(request: ServerRequest, target: QueryTarget): ServerResponse =
        answering(target.type.context) {
            authenticator.authenticate(request)
            Negotiation.checkAcceptable(request, alsoEventStream = false)
            val gateway = checkNotNull(queries.gateway(target.type, target.model))
            val described = gateway.describe(QueryEntry.HTTP, gateway.entryPolicy.http.listDefault())
            tagged(request, described.version, QueryJson.mapper.writeValueAsBytes(described), target.rebuilding())
        }

    /** Whether [this] is a snapshot model whose snapshots are being rebuilt or backfilled (decision D172). */
    private fun QueryTarget.rebuilding(): Boolean =
        model == QueryRoutes.SNAPSHOT && queries.snapshotStatus.rebuilding(type)

    /**
     * `POST /wow/dataset/{context}/{dataset}/query` (decision D145): the body's query on the dataset [name], as on a
     * snapshot model, every `kind` included; a dataset this node does not serve, or the caller does not see, is a 404.
     */
    suspend fun queryDataset(request: ServerRequest, name: AggregateType): ServerResponse =
        answering(name.context) {
            val caller = caller(request, name)
            val gateway = dataset(name, caller)
            val body = body(request)
            val eventStream = body.kind == QueryKind.LIST && Negotiation.wantsEventStream(request)
            run(gateway, body, caller, eventStream, lastEventId(request))
        }

    /** `GET /wow/dataset/{context}/{dataset}/query`: the dataset's descriptor, with its version as the `ETag`. */
    suspend fun describeDataset(request: ServerRequest, name: AggregateType): ServerResponse =
        answering(name.context) {
            val gateway = dataset(name, caller(request, name))
            Negotiation.checkAcceptable(request, alsoEventStream = false)
            val described = gateway.describe(QueryEntry.HTTP, gateway.entryPolicy.http.listDefault())
            tagged(request, described.version, QueryJson.mapper.writeValueAsBytes(described))
        }

    /**
     * `GET /wow/dataset`: the datasets this node serves that the caller sees ([HttpQueries.datasetVisibility]), by
     * name, each with its title and the path of its query route.
     */
    suspend fun listDatasets(request: ServerRequest): ServerResponse = answering(null) {
        val caller = authenticator.authenticate(request)
        val named = named(request)
        targets.admit(caller, named?.target, named?.crossTenant)
        Negotiation.checkAcceptable(request, alsoEventStream = false)
        val listed = queries.datasets.all.map { it.dataset }.sortedBy { it.name.toString() }.filter { dataset ->
            val queryCaller = queryCaller(dataset.name, caller, named)
            queryCaller != null && queries.datasetVisibility.visible(dataset, queryCaller)
        }.map { dataset ->
            DatasetEntry(
                name = dataset.name.toString(),
                context = dataset.name.context,
                dataset = dataset.name.name,
                title = dataset.title,
                path = datasetPath(dataset.name),
            )
        }
        json(listed)
    }

    /** The gateway of the dataset [name] when [caller] sees it; 404 `wow.query.dataset-not-found` otherwise. */
    private fun dataset(name: AggregateType, caller: QueryCaller): DatasetQueryGateway =
        queries.datasets[name]?.takeIf { queries.datasetVisibility.visible(it.dataset, caller) }
            ?: throw ProblemException(
                statuses.problemOf(QueryErrors.DATASET_NOT_FOUND, "No dataset $name is served.", name.context),
            )

    /** A dataset path that names no dataset: 404 `wow.query.dataset-not-found`. */
    suspend fun noDataset(request: ServerRequest): ServerResponse =
        statuses.problemOf(QueryErrors.DATASET_NOT_FOUND, "No dataset at ${request.path()}.").respond()

    /** The query of [request]'s body, once the body and the response it asks for can be served. */
    private suspend fun body(request: ServerRequest): QueryBody {
        Negotiation.checkJsonBody(request, "A query")
        val body = QueryBodies.read(request.awaitBodyOrNull<ByteArray>())
        Negotiation.checkAcceptable(request, alsoEventStream = body.kind == QueryKind.LIST)
        return body
    }

    /**
     * [descriptor] of [version], with the version as its `ETag`; 304 when `If-None-Match` names it. A descriptor of
     * snapshots being [rebuilding] says so ([WowHeaders.REBUILDING]).
     */
    private suspend fun tagged(
        request: ServerRequest,
        version: String,
        descriptor: ByteArray,
        rebuilding: Boolean = false,
    ): ServerResponse {
        val tag = "\"$version\""
        if (request.headers().header(HttpHeaders.IF_NONE_MATCH).any { matches(it, tag) }) {
            return ServerResponse.status(NOT_MODIFIED).eTag(tag).rebuilding(rebuilding).buildAndAwait()
        }
        return ServerResponse.ok().eTag(tag).rebuilding(rebuilding).contentType(MediaType.APPLICATION_JSON)
            .bodyValueAndAwait(descriptor)
    }

    /** Marks an answer from snapshots that are being rebuilt or backfilled (decision D172). */
    private fun ServerResponse.BodyBuilder.rebuilding(rebuilding: Boolean): ServerResponse.BodyBuilder =
        if (rebuilding) header(WowHeaders.REBUILDING, "true") else this

    /** The query caller of [request] for a query of [type] (decision D129). */
    private suspend fun caller(request: ServerRequest, type: AggregateType): QueryCaller {
        val caller = authenticator.authenticate(request)
        val named = named(request)
        return targets.of(type, caller, named?.target, named?.crossTenant)
    }

    /** The position a resumed stream continues after: the request's `Last-Event-ID` (decision D253). */
    private fun lastEventId(request: ServerRequest): String? =
        request.headers().firstHeader(LAST_EVENT_ID)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * [body]'s query on [gateway], the one path of every model: snapshots, events and datasets alike. A streamed list
     * resumes after [after], the position its `Last-Event-ID` names.
     */
    @Suppress("LongParameterList")
    private suspend fun run(
        gateway: QueryGateway,
        body: QueryBody,
        caller: QueryCaller,
        eventStream: Boolean,
        after: String?,
        rebuilding: Boolean = false,
    ): ServerResponse = when (body.kind) {
        QueryKind.LIST -> if (eventStream) {
            stream(gateway, body.query as ListQuery, caller, after, rebuilding)
        } else {
            val query = (body.query as ListQuery).withListDefault(gateway.entryPolicy.http)
            json(gateway.list(query, caller).toList(), rebuilding)
        }
        QueryKind.PAGED -> json(gateway.paged(body.query as IPagedQuery, caller), rebuilding)
        QueryKind.CURSOR -> json(gateway.cursor(body.query as ICursorQuery, caller), rebuilding)
        QueryKind.SINGLE ->
            single(gateway.single(body.query as ISingleQuery, caller), gateway.aggregateType, rebuilding)
        QueryKind.COUNT -> json(gateway.count(body.query as ICountQuery, caller), rebuilding)
        QueryKind.AGGREGATE -> json(gateway.aggregate(body.query as AggregationQuery, caller).toList(), rebuilding)
    }

    /** The list default of [queries] under [budget]: what a list naming no limit runs with (0, every match). */
    private fun QueryBudget.listDefault(): Int? = listDefault(queries.defaultListSize)

    private fun ListQuery.withListDefault(budget: QueryBudget): ListQuery {
        val default = budget.listDefault()
        return if (limit != 0 || default == null) this else ListQuery(filter, projection, sort, default)
    }

    private suspend fun json(value: Any, rebuilding: Boolean = false): ServerResponse =
        ServerResponse.ok().rebuilding(rebuilding).contentType(
            MediaType.APPLICATION_JSON
        ).bodyValueAndAwait(QueryJson.mapper.writeValueAsBytes(value))

    private suspend fun single(record: ObjectNode?, type: AggregateType, rebuilding: Boolean = false): ServerResponse =
        json(
            record ?: throw problem(QueryErrors.NOT_FOUND, "No record of $type matches the query.", type),
            rebuilding,
        )

    /**
     * [query] on [gateway] as server-sent events (decision D253): a resumable stream when the gateway [resumes] it,
     * from after [after]; else the records of a list under the HTTP list default, without positions, whose `end`
     * events say `"resumable": false` ([after] is then `wow.query.invalid`). A node that is stopping refuses it.
     */
    private suspend fun stream(
        gateway: QueryGateway,
        query: ListQuery,
        caller: QueryCaller,
        after: String?,
        rebuilding: Boolean,
    ): ServerResponse {
        val context = gateway.aggregateType.context
        val streams = queries.streams
        if (streams.closed) {
            throw ProblemException(statuses.problemOf(FrameworkCodes.NODE_CLOSING, NODE_STOPPING, context))
        }
        if (gateway.resumes(query)) {
            val records = gateway.stream(query, caller, after, streams.chunkSize)
            return streamed(records.map { Streamed(it.record, it.position) }, true, context, rebuilding)
        }
        if (after != null) throw QueryViolation.InvalidCursor.rejection()
        val records = gateway.list(query.withListDefault(gateway.entryPolicy.http), caller)
        return streamed(records.map { Streamed(it, null) }, false, context, rebuilding)
    }

    /** A record of a streamed list and its position, `null` in a stream that cannot be resumed. */
    private class Streamed(val record: ObjectNode, val position: String?)

    /**
     * A streamed list (decision D253) as server-sent events: each record a `row` event whose `id` is its position when
     * the stream is [resumable], the first read before the response starts (so a failure is still a problem, a busy
     * store a 429). It ends with an `end` event: `complete` once every record was sent; `hold-expired` once it ran for
     * [QueryStreams.maxHold]; `node-stopping` when [QueryStreams.close] is called; `permit-reclaimed` when its store had
     * no permit for its next chunk in time. In a [resumable] stream every `end` but `complete` is resumable from the
     * last `id`; in another, none is. Another failure ends it with an `error` event holding the problem.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // produce: the records of one collection, handed to the response.
    private suspend fun streamed(
        records: Flow<Streamed>,
        resumable: Boolean,
        context: String,
        rebuilding: Boolean,
    ): ServerResponse {
        val rows = scope.produce { records.collect { send(it) } }
        val first = rows.receiveFirst()
        val held = TimeSource.Monotonic.markNow()
        val events = flow {
            var record = first
            while (record != null) {
                emit(row(record.record, record.position))
                record = when (val next = next(rows, held)) {
                    is Streamed -> next
                    else -> {
                        emit(end(next as String, resumable))
                        null
                    }
                }
            }
            if (first == null) emit(end(QueryStreams.COMPLETE, resumable))
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            val reclaimed = failure is QueryBusyException
            emit(if (reclaimed) end(QueryStreams.PERMIT_RECLAIMED, resumable) else error(failure, context))
        }
        return respond(rows, events, rebuilding)
    }

    /**
     * The next record of [rows], or the reason the stream ends before it: `complete` when [rows] has no more,
     * `hold-expired` once [held] passed [QueryStreams.maxHold], `node-stopping` once the streams are closed. A failure
     * of [rows] is thrown.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // onTimeout: the hold time left.
    private suspend fun next(rows: ReceiveChannel<Streamed>, held: TimeMark): Any {
        val streams = queries.streams
        val left = if (streams.maxHold == Duration.ZERO) Duration.INFINITE else streams.maxHold - held.elapsedNow()
        return when {
            left <= Duration.ZERO -> QueryStreams.HOLD_EXPIRED
            streams.closed -> QueryStreams.NODE_STOPPING
            else -> select<Any> {
                rows.onReceiveCatching { result ->
                    result.exceptionOrNull()?.let { throw it }
                    result.getOrNull() ?: QueryStreams.COMPLETE
                }
                streams.stopping.onAwait { QueryStreams.NODE_STOPPING }
                if (left.isFinite()) onTimeout(left) { QueryStreams.HOLD_EXPIRED }
            }
        }
    }

    private suspend fun respond(
        rows: ReceiveChannel<*>,
        events: Flow<ServerSentEvent<String>>,
        rebuilding: Boolean,
    ): ServerResponse = rows.handingOver(events) {
        ServerResponse.ok().rebuilding(rebuilding).contentType(MediaType.TEXT_EVENT_STREAM).bodyAndAwait(it)
    }

    private fun row(record: ObjectNode, position: String?): ServerSentEvent<String> {
        val event = ServerSentEvent.builder(QueryJson.mapper.writeValueAsString(record)).event(ROW)
        return (if (position == null) event else event.id(position)).build()
    }

    private fun end(reason: String, resumable: Boolean): ServerSentEvent<String> = ServerSentEvent.builder(
        HttpJson.writeString(mapOf(REASON to reason, RESUMABLE to (resumable && reason != QueryStreams.COMPLETE)))
    ).event(QueryStreams.END).build()

    private fun error(failure: Throwable, context: String): ServerSentEvent<String> =
        ServerSentEvent.builder(HttpJson.writeString(problemOf(failure, context).members())).event(ERROR).build()

    /** [block]'s response, or the problem of what it failed with. */
    @Suppress("TooGenericExceptionCaught") // Every failure of a query is answered as a problem.
    private suspend inline fun answering(context: String?, block: () -> ServerResponse): ServerResponse = try {
        block()
    } catch (problem: ProblemException) {
        problem.problem.respond()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        problemOf(failure, context).respond()
    }

    /** The problem a query that failed with [failure] answers. */
    private fun problemOf(failure: Throwable, context: String?): Problem {
        if (failure is ProblemException) return failure.problem
        tooLargeProblem(failure)?.let { return it }
        // From a backend primitive through the core, or from a read of the log (the events model).
        if (failure is QueryStorageUnavailableException || failure is StorageUnavailableException) {
            logger.warn(failure) { "A query failed: its storage is unavailable." }
            return statuses.problemOf(QueryErrors.STORAGE_UNAVAILABLE, QUERY_STORAGE_UNAVAILABLE, context)
        }
        if (failure !is QueryError) {
            logger.error(failure) { "A query failed." }
            return statuses.problemOf(FrameworkErrors.QUERY_FAILED, "The query failed.", context)
        }
        if (failure.errorCode == FrameworkErrors.QUERY_FAILED) {
            logger.error(failure) { "A query failed: ${failure.errorMsg}" }
        }
        return statuses.problemOf(failure.errorCode, failure.errorMsg, context).copy(
            violations = failure.bindingErrors.map { Violation(path = it.name, code = it.code, message = it.msg) },
        )
    }

    /** What `Wow-Scope` names for the query of [request], or null without it; [targets] decide whether it may. */
    private fun named(request: ServerRequest): ScopeHeader.QueryTarget? =
        headers.queryTarget(request, FrameworkErrors.QUERY_MALFORMED)

    /** [caller]'s query caller of the dataset [type] in what it [named], or null when the dataset's tenant is fixed. */
    private fun queryCaller(type: AggregateType, caller: Caller, named: ScopeHeader.QueryTarget?): QueryCaller? = try {
        targets.of(type, caller, named?.target, named?.crossTenant)
    } catch (refused: QueryTargetException) {
        if (refused.errorCode != QueryErrors.MALFORMED) throw refused
        null
    }

    private fun problem(code: String, detail: String, type: AggregateType) =
        ProblemException(statuses.problemOf(code, detail, type.context))

    companion object {
        /** The event of each record of a streamed list. */
        const val ROW: String = "row"

        /** The event that ends a streamed list that failed: the problem. */
        const val ERROR: String = "error"

        /** The request header naming the position a stream resumes after (decision D253). */
        const val LAST_EVENT_ID: String = "Last-Event-ID"

        /** The members of an `end` event's data: why the stream ended, and whether it can be resumed. */
        const val REASON: String = "reason"
        const val RESUMABLE: String = "resumable"

        private const val NOT_MODIFIED = 304
        private const val QUERY_STORAGE_UNAVAILABLE = "Query storage is unavailable; retry later."
        private const val NODE_STOPPING = "The node is stopping; stream from another node."
        private val logger = KotlinLogging.logger {}

        /** The path of the query route of the dataset [name]: `/wow/dataset/{context}/{dataset}/query`. */
        fun datasetPath(name: AggregateType): String =
            "${WowPaths.DATASETS}/${name.context}/${name.name}/${QueryRoutes.SEGMENT}"

        private fun matches(ifNoneMatch: String, tag: String): Boolean =
            ifNoneMatch.split(',').map { it.trim().removePrefix("W/") }.any { it == "*" || it == tag }
    }
}
