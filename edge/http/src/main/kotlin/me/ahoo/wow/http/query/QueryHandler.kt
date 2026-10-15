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
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ICountQuery
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.http.HttpJson
import me.ahoo.wow.http.WowHeaders
import me.ahoo.wow.http.WowPaths
import me.ahoo.wow.http.receiveFirst
import me.ahoo.wow.http.caller.Authenticator
import me.ahoo.wow.http.command.Negotiation
import me.ahoo.wow.http.command.RequestHeaders
import me.ahoo.wow.http.problem.ErrorStatuses
import me.ahoo.wow.http.problem.Problem
import me.ahoo.wow.http.problem.ProblemException
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.query.QueryBudget
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryError
import me.ahoo.wow.query.QueryErrors
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryStorageUnavailableException
import me.ahoo.wow.query.dataset.DatasetQueryGateway
import me.ahoo.wow.query.json.QueryJson
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.admission.TargetScope
import me.ahoo.wow.runtime.admission.TenantConflictException
import me.ahoo.wow.runtime.admission.TenantPolicy
import me.ahoo.wow.spi.StorageUnavailableException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
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
 * Answers the query routes (decisions D127, D129, D130, D131, D145):
 *
 * - `POST /{context}/{aggregate}/{model}/query` runs the body's query on [target]'s model, as the caller the request
 *   authenticates as; with `Accept: text/event-stream`, a `list` streams its records as server-sent events
 *   (`event: row`), and a failure after the first record ends the stream with an `event: error` holding the problem;
 * - `GET` of the same path answers the model's capability descriptor, with its version as the `ETag`, and 304 when
 *   `If-None-Match` names it;
 * - `POST` and `GET /wow/dataset/{context}/{dataset}/query` do the same on a dataset (decision D145), and
 *   `GET /wow/dataset` lists the datasets the caller sees.
 *
 * The caller (decision D129) is the request's [Caller], or the scope `Wow-Scope` names when the caller may act
 * across scopes (403 `wow.auth.forbidden` otherwise): its tenant, decided by [tenants] as for a command (D116), its
 * owner and space as the principal scope, its subject and attributes, on the HTTP entry. A query error is a problem
 * with the query codes (D130): `wow.query.malformed`, `wow.query.invalid` (each violation with its code),
 * `wow.query.failed`, `wow.query.timeout`; any other failure is `wow.query.failed`, its cause logged and not shown.
 */
internal class QueryHandler(
    private val queries: HttpQueries,
    private val authenticator: Authenticator,
    private val headers: RequestHeaders,
    private val statuses: ErrorStatuses,
    private val tenants: TenantPolicy,
    private val scope: CoroutineScope,
) {
    suspend fun query(request: ServerRequest, target: QueryTarget): ServerResponse =
        answering(target.type.context) {
            val caller = caller(request, target.type)
            val body = body(request)
            val eventStream = body.kind == QueryKind.LIST && Negotiation.wantsEventStream(request)
            when (target.model) {
                QueryRoutes.SNAPSHOT -> snapshots(
                    checkNotNull(queries.snapshots(target.type)),
                    body,
                    caller,
                    eventStream,
                    queries.snapshotStatus.rebuilding(target.type),
                )
                else -> events(checkNotNull(queries.events(target.type)), body, caller, eventStream)
            }
        }

    suspend fun describe(request: ServerRequest, target: QueryTarget): ServerResponse =
        answering(target.type.context) {
            authenticator.authenticate(request)
            Negotiation.checkAcceptable(request, alsoEventStream = false)
            val (version, descriptor) = when (target.model) {
                QueryRoutes.SNAPSHOT -> {
                    val gateway = checkNotNull(queries.snapshots(target.type))
                    val described = gateway.describe(QueryEntry.HTTP, gateway.entryPolicy.http.listDefault())
                    described.version to QueryJson.mapper.writeValueAsBytes(described)
                }
                else -> {
                    val gateway = checkNotNull(queries.events(target.type))
                    val described = gateway.describe(QueryEntry.HTTP, gateway.entryPolicy.http.listDefault())
                    described.get("version").asString() to QueryJson.mapper.writeValueAsBytes(described)
                }
            }
            val rebuilding = target.model == QueryRoutes.SNAPSHOT && queries.snapshotStatus.rebuilding(target.type)
            tagged(request, version, descriptor, rebuilding)
        }

    /**
     * `POST /wow/dataset/{context}/{dataset}/query` (decision D145): the body's query on the dataset [name], as on a
     * snapshot model, every `kind` included; a dataset this node does not serve, or the caller does not see, is a 404.
     */
    suspend fun queryDataset(request: ServerRequest, name: AggregateType): ServerResponse =
        answering(name.context) {
            val caller = caller(request, name)
            val gateway = dataset(name, caller)
            val body = body(request)
            snapshots(gateway, body, caller, body.kind == QueryKind.LIST && Negotiation.wantsEventStream(request))
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
        val named = headers.target(request, FrameworkErrors.QUERY_MALFORMED)
        if (named != null && !caller.crossScope) {
            throw ProblemException(
                statuses.problemOf(FrameworkCodes.AUTH_FORBIDDEN, "The caller may not query in another scope.", null),
            )
        }
        Negotiation.checkAcceptable(request, alsoEventStream = false)
        val listed = queries.datasets.all.map { it.dataset }.sortedBy { it.name.toString() }.filter { dataset ->
            val queryCaller = queryCaller(dataset.name, caller, named, tenants)
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

    /** The gateway of the dataset [name] when [caller] sees it; a 404 problem otherwise. */
    private fun dataset(name: AggregateType, caller: QueryCaller): DatasetQueryGateway =
        queries.datasets[name]?.takeIf { queries.datasetVisibility.visible(it.dataset, caller) }
            ?: throw ProblemException(Problem.ofStatus(HttpStatus.NOT_FOUND, "No dataset $name is served."))

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
        val named = headers.target(request, FrameworkErrors.QUERY_MALFORMED)
        if (named != null && !caller.crossScope) {
            throw problem(FrameworkCodes.AUTH_FORBIDDEN, "The caller may not query in another scope.", type)
        }
        return queryCaller(type, caller, named, tenants)
            ?: throw problem(FrameworkErrors.QUERY_MALFORMED, "The tenant of $type is fixed.", type)
    }

    private suspend fun snapshots(
        gateway: QueryGateway,
        body: QueryBody,
        caller: QueryCaller,
        eventStream: Boolean,
        rebuilding: Boolean = false,
    ): ServerResponse = when (body.kind) {
        QueryKind.LIST -> {
            val query = (body.query as ListQuery).withListDefault(gateway.entryPolicy.http)
            rows(gateway.list(query, caller), eventStream, gateway.aggregateType.context, rebuilding)
        }
        QueryKind.PAGED -> json(gateway.paged(body.query as IPagedQuery, caller), rebuilding)
        QueryKind.CURSOR -> json(gateway.cursor(body.query as ICursorQuery, caller), rebuilding)
        QueryKind.SINGLE ->
            single(gateway.single(body.query as ISingleQuery, caller), gateway.aggregateType, rebuilding)
        QueryKind.COUNT -> json(gateway.count(body.query as ICountQuery, caller), rebuilding)
        QueryKind.AGGREGATE -> rows(
            gateway.aggregate(body.query as AggregationQuery, caller),
            false,
            gateway.aggregateType.context,
            rebuilding,
        )
    }

    private suspend fun events(
        gateway: EventQueryGateway,
        body: QueryBody,
        caller: QueryCaller,
        eventStream: Boolean,
    ): ServerResponse = when (body.kind) {
        QueryKind.LIST -> {
            val query = (body.query as ListQuery).withListDefault(gateway.entryPolicy.http)
            rows(gateway.list(query, caller), eventStream, gateway.aggregateType.context)
        }
        QueryKind.PAGED -> json(gateway.paged(body.query as IPagedQuery, caller))
        QueryKind.CURSOR -> json(gateway.cursor(body.query as ICursorQuery, caller))
        QueryKind.SINGLE -> single(gateway.single(body.query as ISingleQuery, caller), gateway.aggregateType)
        QueryKind.COUNT -> json(gateway.count(body.query as ICountQuery, caller))
        QueryKind.AGGREGATE -> throw QueryViolation.StorageUnsupported(
            "aggregation on the events model",
        ).rejection()
    }

    /** The list default of [queries] under [budget]: what a list naming no limit runs with (0, every match). */
    private fun QueryBudget.listDefault(): Int? = listDefault(queries.defaultListSize)

    private fun ListQuery.withListDefault(budget: QueryBudget): ListQuery {
        val default = budget.listDefault()
        return if (limit != 0 || default == null) this else copy(limit = default)
    }

    private suspend fun json(value: Any, rebuilding: Boolean = false): ServerResponse =
        ServerResponse.ok().rebuilding(rebuilding).contentType(
            MediaType.APPLICATION_JSON
        ).bodyValueAndAwait(QueryJson.mapper.writeValueAsBytes(value))

    private suspend fun single(record: ObjectNode?, type: AggregateType, rebuilding: Boolean = false): ServerResponse =
        json(
            record ?: throw problem(FrameworkCodes.NOT_FOUND, "No record of $type matches the query.", type),
            rebuilding,
        )

    /**
     * [records] as one JSON array, read whole before the response starts so a failure is still a problem; or, for
     * an [eventStream], as server-sent events, the first one read before the response starts.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // produce: the records of one collection, handed to the response.
    private suspend fun rows(
        records: Flow<ObjectNode>,
        eventStream: Boolean,
        context: String,
        rebuilding: Boolean = false,
    ): ServerResponse {
        if (!eventStream) return json(records.toList(), rebuilding)
        val rows = scope.produce { records.collect { send(it) } }
        val first = rows.receiveFirst()
        val events = flow {
            if (first == null) return@flow
            var index = 0L
            emit(row(first, index++))
            // A client that goes away cancels the producer too, so the backend's cursor is closed.
            rows.consumeEach { emit(row(it, index++)) }
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            emit(
                ServerSentEvent.builder(
                    HttpJson.writeString(problemOf(failure, context).members())
                ).event(ERROR).build()
            )
        }
        return ServerResponse.ok().rebuilding(rebuilding).contentType(MediaType.TEXT_EVENT_STREAM).bodyAndAwait(events)
    }

    private fun row(record: ObjectNode, index: Long): ServerSentEvent<String> =
        ServerSentEvent.builder(QueryJson.mapper.writeValueAsString(record)).id(index.toString()).event(ROW).build()

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

    private fun problem(code: String, detail: String, type: AggregateType) =
        ProblemException(statuses.problemOf(code, detail, type.context))

    companion object {
        /** The event of each record of a streamed list. */
        const val ROW: String = "row"

        /** The event that ends a streamed list that failed: the problem. */
        const val ERROR: String = "error"

        private const val NOT_MODIFIED = 304
        private const val QUERY_STORAGE_UNAVAILABLE = "Query storage is unavailable; retry later."
        private val logger = KotlinLogging.logger {}

        /** The path of the query route of the dataset [name]: `/wow/dataset/{context}/{dataset}/query`. */
        fun datasetPath(name: AggregateType): String =
            "${WowPaths.DATASETS}/${name.context}/${name.name}/${QueryRoutes.SEGMENT}"

        private fun matches(ifNoneMatch: String, tag: String): Boolean =
            ifNoneMatch.split(',').map { it.trim().removePrefix("W/") }.any { it == "*" || it == tag }

        /**
         * The query caller of [caller] querying [type], in the [named] scope of `Wow-Scope` when it names one (decision
         * D129, D165): [caller] acting in the tenant [tenants] decides and in the named scope's owner and space, on the
         * HTTP entry. Null when [named] names another tenant than [type]'s fixed one.
         */
        fun queryCaller(type: AggregateType, caller: Caller, named: TargetScope?, tenants: TenantPolicy): QueryCaller? {
            val tenant = try {
                tenants.tenantOf(type, caller, named)
            } catch (_: TenantConflictException) {
                return null
            }
            return QueryCaller(caller.copy(tenantId = tenant, scope = named?.scope ?: caller.scope), QueryEntry.HTTP)
        }
    }
}
