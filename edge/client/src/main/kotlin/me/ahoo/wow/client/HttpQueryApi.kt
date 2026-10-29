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

package me.ahoo.wow.client

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.ProjectionCapable
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.read.AggregateState
import me.ahoo.wow.api.read.DatasetName
import me.ahoo.wow.api.read.EventQueries
import me.ahoo.wow.api.read.EventStream
import me.ahoo.wow.api.read.MinVersionNotReachedException
import me.ahoo.wow.api.read.Positioned
import me.ahoo.wow.api.read.Queries
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.RowQueries
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.api.read.SnapshotQueries
import me.ahoo.wow.api.read.StateView
import me.ahoo.wow.api.read.TrustedQueries
import me.ahoo.wow.api.wire.CallWire
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.json.query.QueryProtocolJson
import me.ahoo.wow.spi.json.query.decode
import me.ahoo.wow.spi.json.query.toEventStream
import me.ahoo.wow.spi.json.query.toSnapshot
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.io.IOException
import kotlin.time.Duration

/**
 * The [QueryApi] of the peers' HTTP read routes: the same queries as in process, sent
 * as JSON (`{"kind": "list", ...}`) to the query route of each model, `POST /{context}/{aggregate}/{model}/query`,
 * the dataset route `POST /wow/dataset/{context}/{dataset}/query`, and the state read
 * `GET /{context}/{aggregate}/{id}/state`, on the port [transport]'s mode calls, as the caller [of] gives:
 *
 * - in service mode, on the peer's internal read routes, the caller asserted as the root context of `Wow-Call`: the
 *   peer scopes the read to its tenant, owner and space and lets its policies see its subject and application;
 * - in user mode, on the public routes with the end user's forwarded credential.
 *
 * A peer's rows are typed by the caller: [Queries.snapshots] of a state class decodes the state as that
 * class, found by its schema in the peer's contract ([models]); [Queries.snapshots] of a type and [Queries.events]
 * decode a state and an event body as an `ObjectNode`, which the generated field constants of the peer's api read; a
 * dataset's rows and [ModelQueries.dynamic][me.ahoo.wow.api.read.ModelQueries.dynamic] rows as the class given.
 *
 * A streamed list ([RowQueries.stream]) reads the peer's server-sent events and resumes on its own:
 * when the peer ends it resumably (its hold expired, it is stopping, its store reclaimed the permit), sends a problem a
 * retry may get past, or the connection drops, it is asked again with `Last-Event-ID`, the last row's position, for
 * what is left of its limit (the peer reads up to the limit again after the position), so it never answers more rows
 * than the limit. It waits between resumes as the transport's [RetryPolicy] says (at once after a hold expired, else the
 * peer's `Retry-After` or the backoff), and fails after the policy's attempts in a row without a row read.
 *
 * A query is sent again when its problem says a retry may get past it (its `retry` member); any other problem, and
 * one the retries do not get past, is a [me.ahoo.wow.api.problem.WowProblemException], as is a failure of the
 * transport (`wow.peer.unavailable`, `wow.query.timeout`). A single row or a state that is not found, or that the caller
 * may not see, is null. There are no trusted queries over HTTP: [trusted] throws.
 */
class HttpQueryApi internal constructor(
    private val transport: HttpTransport,
    private val models: PeerModels,
    private val ids: GlobalIdGenerator,
) : QueryApi {
    override fun of(caller: Caller): Queries = Scoped(caller)

    /** Not served over HTTP: a peer reads for a caller, its root context's tenant and scope. */
    override fun trusted(): TrustedQueries = throw UnsupportedOperationException(
        "A peer's queries are scoped by a caller, the root context of the read: use of(caller).",
    )

    private inner class Scoped(val caller: Caller) : Queries {
        override fun <S : Any> snapshots(stateType: Class<S>): SnapshotQueries<S> =
            Snapshots(models.typeOf(stateType), stateType, caller)

        override fun snapshots(type: AggregateType): SnapshotQueries<Any> {
            @Suppress("UNCHECKED_CAST")
            return Snapshots(type, ObjectNode::class.java as Class<Any>, caller)
        }

        override fun events(type: AggregateType): EventQueries = Events(type, caller)

        @ExperimentalWowApi
        override fun <R : Any> datasets(name: DatasetName, rowType: Class<R>): RowQueries<R> =
            Rows(name.context, "/wow/dataset/${name.context}/${name.name}/${QueryRoutes.SEGMENT}", caller) {
                decode(it, rowType)
            }
    }

    /** The queries of the route [path] of [context] as [caller], each row read by [row]; refused by [check] first. */
    private open inner class Rows<R : Any>(
        val context: String,
        val path: String,
        val caller: Caller,
        private val check: (Any) -> Unit = {},
        private val row: (ObjectNode) -> R,
    ) : RowQueries<R> {
        override suspend fun single(query: SingleQuery): R? {
            check(query)
            return when (val answer = post(KIND_SINGLE, query)) {
                is Answer.Ok -> row(tree(answer.value) as ObjectNode)
                // Only a query that matched nothing is no row; a 404 of another code (a dataset or a route that
                // does not exist, a context this peer does not serve) is a mistake the caller must hear of.
                is Answer.Problem -> if (answer.problem.isNotFound(FrameworkErrors.QUERY_NOT_FOUND)) {
                    null
                } else {
                    throw answer.problem.exception(what())
                }
            }
        }

        override fun list(query: ListQuery): Flow<R> = flow {
            check(query)
            emitAll(ok(post(KIND_LIST, query)).elements().map { row(it as ObjectNode) }.asFlow())
        }

        override fun stream(query: ListQuery, after: String?): Flow<Positioned<R>> = flow {
            check(query)
            val stream = Streamed(query, after)
            while (true) {
                val asked = stream.asked()
                val answer = transport.stream(request(KIND_LIST, asked, stream.headers()), Exchange.QUERY)
                val events = when (answer) {
                    is Answer.Ok -> answer.value
                    is Answer.Problem -> throw answer.problem.exception(what())
                }
                val stop = stream.read(events, this) ?: return@flow
                delay(stream.delayAfter(stop))
            }
        }

        /**
         * A stream's progress over the requests it takes: the [position] of its last row, the rows emitted of its
         * query's limit, and the requests in a row that read none.
         */
        private inner class Streamed(private val query: ListQuery, private var position: String?) {
            private val limit = query.limit
            private var emitted = 0
            private var failures = 0

            /**
             * The query of the next request, for what is left of the limit: the peer reads up to the limit again after
             * the position. [read] ends the stream once the limit is read.
             */
            fun asked(): ListQuery {
                val left = limit?.minus(emitted)
                if (left == null || left == limit) return query
                return ListQuery.of(query.filter, query.projection, query.sort, left)
            }

            fun headers(): Map<String, String> = position?.let { mapOf(LAST_EVENT_ID to it) }.orEmpty()

            /**
             * Emits the rows of one request's [events] to [collector], never past the limit, whatever the peer sends:
             * null once the stream is complete, else why it stopped. A stream that ended without an end event was cut,
             * as a dropped one.
             */
            suspend fun read(events: Flow<String>, collector: FlowCollector<Positioned<R>>): Stop? {
                var complete = false
                var stop: Stop? = null
                try {
                    events.serverSentEvents().takeWhile { limit == null || emitted < limit }.collect { event ->
                        when (event.event) {
                            ROW -> {
                                collector.emit(Positioned.of(row(tree(event.data) as ObjectNode), event.id))
                                event.id?.let { position = it }
                                emitted++
                                failures = 0
                            }
                            END -> tree(event.data).let { end ->
                                complete = end[REASON]?.asString() == COMPLETE
                                if (!complete) stop = ended(end)
                            }
                            ERROR -> stop = failed(event.data)
                        }
                    }
                } catch (dropped: IOException) {
                    return lost("The connection of the stream of $path was lost ($dropped).", dropped)
                }
                if (complete || limit != null && emitted >= limit) return null
                return stop ?: lost("The stream of $path was cut off.", IOException("No end event."))
            }

            /** A stream that lost its connection, [failure], as the transport answers one: `wow.peer.unavailable`. */
            private fun lost(detail: String, failure: IOException): Stop {
                val problem = PeerProblem.of(FrameworkErrors.PEER_UNAVAILABLE, detail, failure)
                return Stop(problem.exception(what()), problem.retryAfter)
            }

            /** A resumable end, which a hold that expired asks to take again at once; another is thrown. */
            private fun ended(end: JsonNode): Stop {
                val reason = end[REASON]?.asString()
                val ended = IllegalStateException("The stream of $path ended: $reason.")
                if (end[RESUMABLE]?.asBoolean() != true) throw ended
                return Stop(ended, Duration.ZERO.takeIf { reason == HOLD_EXPIRED })
            }

            /** A problem a retry may get past, after its `Retry-After`; another is thrown. */
            private fun failed(data: String): Stop {
                val problem = Problems.ofMembers(data)
                val failed = problem.exception(what())
                if (!problem.retriesQuery) throw failed
                return Stop(failed, problem.retryAfter)
            }

            /**
             * How long to wait before asking for the stream again after [stop], as the transport's retry policy says;
             * its cause is thrown once the policy's attempts in a row read no row, or when the stream cannot be asked
             * for again: rows were read that have no position.
             */
            fun delayAfter(stop: Stop): Duration {
                if (position == null && emitted > 0) throw stop.cause
                failures++
                return transport.retry.delayBefore(failures, stop.retryAfter) ?: throw stop.cause
            }
        }

        override suspend fun paged(query: PagedQuery): PagedList<R> {
            check(query)
            val page = ok(
                post(KIND_PAGED, query)
            )
            return PagedList(
                page.get(TOTAL)?.takeUnless { it.isNull }?.asLong(),
                page.get(LIST).elements().map { row(it as ObjectNode) },
                page.get(EXACT)?.asBoolean() ?: false,
            )
        }

        override suspend fun cursor(query: CursorQuery): CursorPage<R> {
            check(query)
            val page = ok(
                post(KIND_CURSOR, query),
            )
            return CursorPage(
                page.get(LIST).elements().map { row(it as ObjectNode) },
                page.get(NEXT_CURSOR)?.takeUnless { it.isNull }?.asString(),
            )
        }

        override suspend fun count(query: CountQuery): CountResult =
            QueryProtocolJson.mapper.treeToValue(
                ok(post(KIND_COUNT, query)),
                CountResult::class.java
            )

        override fun <G : Any> aggregate(query: AggregationQuery, groupType: Class<G>): Flow<G> = flow {
            emitAll(ok(post(KIND_AGGREGATE, query)).elements().map { decode(it, groupType) }.asFlow())
        }

        private suspend fun post(kind: String, query: Any): Answer<ByteArray> =
            transport.exchange(request(kind, query), Exchange.QUERY)

        private fun request(kind: String, query: Any, headers: Map<String, String> = emptyMap()): PeerRequest {
            val body = QueryProtocolJson.mapper.valueToTree<ObjectNode>(query).put(KIND, kind)
            return PeerRequest(
                context,
                path,
                caller,
                rootOf(caller),
                headers,
                QueryProtocolJson.mapper.writeValueAsBytes(body)
            )
        }

        fun ok(answer: Answer<ByteArray>): JsonNode = when (answer) {
            is Answer.Ok -> tree(answer.value)
            is Answer.Problem -> throw answer.problem.exception(what())
        }

        fun what(): String = "The query of $path"
    }

    private inner class Snapshots<S : Any>(
        override val type: AggregateType,
        private val stateType: Class<S>,
        caller: Caller,
    ) : Rows<Snapshot<S>>(type.context, queryPath(type, QueryRoutes.SNAPSHOT), caller, ::wholeRecords, {
        it.toSnapshot(type, stateType)
    }),
        SnapshotQueries<S> {
        override fun <T : Any> dynamic(rowType: Class<T>): RowQueries<T> =
            Rows(context, path, caller) { decode(it, rowType) }

        override suspend fun state(id: String): AggregateState<S>? = read(type.ref(caller.tenantId, id), null)

        override suspend fun state(ref: AggregateRef): AggregateState<S>? {
            require(ref.type == type) { "$ref is not an aggregate of $type." }
            return read(ref, ref.tenantId)
        }

        override suspend fun state(id: String, minVersion: Int): AggregateState<S>? =
            read(type.ref(caller.tenantId, id), null, minVersion)

        override suspend fun state(ref: AggregateRef, minVersion: Int): AggregateState<S>? {
            require(ref.type == type) { "$ref is not an aggregate of $type." }
            return read(ref, ref.tenantId, minVersion)
        }

        /**
         * The latest state of [ref], at [minVersion] or later when given: null when there is none, the caller may not
         * see it, or it is in another tenant; a peer's 409 `wow.query.min-version-not-reached` is a
         * [MinVersionNotReachedException] with the version it names.
         */
        private suspend fun read(ref: AggregateRef, tenantId: String?, minVersion: Int? = null): AggregateState<S>? {
            minVersion?.let { require(it >= 1) { "A committed state has a version from 1." } }
            val query = minVersion?.let { "?$MIN_VERSION=$it" }.orEmpty()
            val path = "/${type.context}/${type.name}/${HttpTransport.encode(ref.id)}/state$query"
            val request = PeerRequest(type.context, path, caller, rootOf(caller))
            val view = when (val answer = transport.exchange(request, Exchange.QUERY)) {
                is Answer.Ok -> tree(answer.value)
                is Answer.Problem -> when {
                    answer.problem.isNotFound(FrameworkErrors.AGGREGATE_NOT_FOUND) -> return null
                    minVersion != null && answer.problem.code == MinVersionNotReachedException.CODE ->
                        throw MinVersionNotReachedException(ref, minVersion, answer.problem.version ?: 0)
                    else -> throw answer.problem.exception("The state of $ref")
                }
            }
            val read = ClientJson.mapper.treeToValue(view.get(REF), AggregateRef::class.java)
            if (tenantId != null && read.tenantId != tenantId) return null
            return AggregateState.of(
                read,
                view.get(VERSION).asInt(),
                ClientJson.mapper.treeToValue(view.get(SCOPE), Scope::class.java),
                view.get(DELETED)?.asBoolean() ?: false,
                view.get(TAGS)?.takeUnless { it.isNull }?.properties()?.associate { (name, values) ->
                    name to values.elements().map { it.asString() }
                }.orEmpty(),
                decode(view.get(STATE), stateType),
            )
        }
    }

    private inner class Events(override val type: AggregateType, caller: Caller) :
        Rows<EventStream>(type.context, queryPath(type, QueryRoutes.EVENTS), caller, ::wholeRecords, { record ->
            record.toEventStream(type) { ObjectNode::class.java }
        }),
        EventQueries {
        override fun <T : Any> dynamic(rowType: Class<T>): RowQueries<T> =
            Rows(context, path, caller) { decode(it, rowType) }
    }

    /**
     * The root context a service-mode read asserts: [caller]'s tenant, scope, operator and
     * application, a new id its own root; none in user mode.
     */
    private fun rootOf(caller: Caller): CallWire? {
        if (transport.mode !is ClientMode.Service) return null
        val id = ids.next()
        return CallWire(
            commandId = id,
            tenantId = caller.tenantId,
            ownerId = caller.scope.ownerId,
            spaceId = caller.scope.spaceId,
            causation = Causation(id, id),
            operator = caller.toOperator(),
            facts = caller.app?.let { mapOf(RequestFacts.APP_ID to it) }.orEmpty(),
        )
    }

    /** Why a request of a stream stopped short of complete: its [cause], and the wait the peer asked for. */
    private class Stop(val cause: Exception, val retryAfter: Duration? = null)

    /** Whether this problem is the 404 of [code]: an answer of no match, not of a missing dataset or route. */
    private fun PeerProblem.isNotFound(code: String): Boolean = status == NOT_FOUND && this.code == code

    companion object {
        private const val NOT_FOUND = 404
        private const val KIND = "kind"
        private const val KIND_SINGLE = "single"
        private const val KIND_LIST = "list"
        private const val KIND_PAGED = "paged"
        private const val KIND_CURSOR = "cursor"
        private const val KIND_COUNT = "count"
        private const val KIND_AGGREGATE = "aggregate"
        private const val LAST_EVENT_ID = "Last-Event-ID"
        private const val ROW = "row"
        private const val END = "end"
        private const val ERROR = "error"
        private const val RESUMABLE = "resumable"
        private const val REASON = "reason"
        private const val COMPLETE = "complete"
        private const val HOLD_EXPIRED = "hold-expired"
        private const val TOTAL = "total"
        private const val LIST = "list"
        private const val EXACT = "exact"
        private const val NEXT_CURSOR = "nextCursor"
        private const val REF = "ref"
        private const val VERSION = "version"
        private const val MIN_VERSION = "minVersion"
        private const val SCOPE = "scope"
        private const val DELETED = "deleted"
        private const val TAGS = "tags"
        private const val STATE = "state"

        private fun queryPath(type: AggregateType, model: String) =
            "/${type.context}/${type.name}/$model/${QueryRoutes.SEGMENT}"

        private fun tree(body: ByteArray): JsonNode = ClientJson.mapper.readTree(body)

        private fun tree(body: String): JsonNode = ClientJson.mapper.readTree(body)

        private fun JsonNode.elements(): List<JsonNode> = iterator().asSequence().toList()

        /** A typed row reads the whole record: a projection leaves out fields it needs. */
        private fun wholeRecords(query: Any) {
            val projection = (query as? ProjectionCapable<*>)?.projection ?: return
            require(projection.include.isEmpty() && projection.exclude.isEmpty()) {
                "A typed query reads whole records: run a projection through dynamic()."
            }
        }
    }
}

/**
 * The aggregate types of the peers by their state classes: a [StateView] names its aggregate, the view a peer's api
 * generates; the contracts on the class path name each aggregate's state schema (`#/$defs/<binary name>`), whose class
 * a caller queries the snapshots of.
 */
internal class PeerModels(private val types: Map<String, List<AggregateType>>) {
    /**
     * The aggregate type whose state is of [stateType], or that the [StateView] [stateType] names.
     *
     * @throws IllegalArgumentException when no contract, or several, have an aggregate of that state.
     */
    fun typeOf(stateType: Class<*>): AggregateType {
        StateView.typeOf(stateType)?.let { return it }
        val found = types[Manifest.schemaRef(stateType.name)].orEmpty()
        require(found.isNotEmpty()) { "No contract has an aggregate whose state is ${stateType.name}." }
        require(found.size == 1) { "The aggregates $found share the state ${stateType.name}: query them by type." }
        return found.single()
    }

    companion object {
        /** The aggregates of every context in [manifests], by their state schema. */
        fun of(manifests: List<Manifest>): PeerModels = PeerModels(
            manifests.flatMap { manifest ->
                manifest.contexts.flatMap { context ->
                    context.aggregates.map { it.state.schema to AggregateType(context.name, it.name) }
                }
            }.groupBy({ it.first }, { it.second }).mapValues { (_, types) -> types.distinct() },
        )
    }
}
