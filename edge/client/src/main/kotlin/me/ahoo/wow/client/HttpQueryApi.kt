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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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
 * when the peer ends it resumably (its hold expired, it is stopping, its store reclaimed the permit) or the connection
 * drops after a row, it is asked again with `Last-Event-ID`, the last row's position, up to
 * [MAX_RESUMES_WITHOUT_ROWS] times in a row without a row read. Only a 429 (`wow.query.busy`) is retried;
 * any other problem is a [me.ahoo.wow.api.client.WowProblemException]. A single row or a state that is not
 * found, or that the caller may not see, is null. There are no trusted queries over HTTP: [trusted] throws.
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
            var position = after
            var idle = 0
            while (true) {
                var read = false
                var end: Boolean? = null
                val answer = transport.stream(
                    request(KIND_LIST, query, position?.let { mapOf(LAST_EVENT_ID to it) }.orEmpty()),
                    PeerProblem::retriesQuery,
                )
                val events = when (answer) {
                    is Answer.Ok -> answer.value
                    is Answer.Problem -> throw answer.problem.exception(what())
                }
                try {
                    events.serverSentEvents().collect { event ->
                        when (event.event) {
                            ROW -> {
                                emit(Positioned.of(row(tree(event.data) as ObjectNode), event.id))
                                event.id?.let { position = it }
                                read = true
                            }
                            END -> end = tree(event.data)[RESUMABLE]?.asBoolean() == true
                            ERROR -> throw Problems.ofMembers(event.data).exception(what())
                        }
                    }
                } catch (dropped: IOException) {
                    // A connection lost after a row resumes from it; one lost before any is the caller's.
                    if (position == null) throw dropped
                    end = true
                }
                idle = if (read) 0 else idle + 1
                // A stream that ended without saying so was cut: it resumes from its last position, as a dropped one.
                val resumable = end ?: (position != null)
                if (!resumable) return@flow
                check(idle < MAX_RESUMES_WITHOUT_ROWS) {
                    "The stream of $path was ended $idle times in a row without a row: it is not resumed again."
                }
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
            transport.exchange(request(kind, query), PeerProblem::retriesQuery)

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
            val view = when (val answer = transport.exchange(request, PeerProblem::retriesQuery)) {
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

    /** Whether this problem is the 404 of [code]: an answer of no match, not of a missing dataset or route. */
    private fun PeerProblem.isNotFound(code: String): Boolean = status == NOT_FOUND && this.code == code

    companion object {
        /** How often a stream is resumed in a row without a row read before it fails. */
        const val MAX_RESUMES_WITHOUT_ROWS: Int = 3

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
