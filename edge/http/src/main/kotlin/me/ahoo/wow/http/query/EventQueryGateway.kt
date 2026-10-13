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

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FieldPredicate
import me.ahoo.wow.api.query.FilterCapable
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.ICountQuery
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.query.QueryBudget
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.QueryTenantPolicy
import me.ahoo.wow.query.QueryTimeoutException
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import tools.jackson.databind.node.ObjectNode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64

/**
 * The `events` query model of one aggregate type (decision D131): the event streams of **one** aggregate, read from
 * the log by [EventLog.read], never filtered freely.
 *
 * A query's filter is an AND of:
 * - the aggregate, required: `aggregateId` equal to an id (or the `ID` / `AGGREGATE_ID` operators);
 * - optionally a range of `version` and of `commitTime` (epoch milliseconds, or an ISO-8601 instant): `EQ`, `GT`,
 *   `GTE`, `LT`, `LTE`, `BETWEEN`;
 * - for an in-process caller without a tenant, `tenantId` (or `TENANT_ID`).
 *
 * Anything else is rejected at admission (`wow.query.invalid`): another field (`UNKNOWN_FIELD`), another operator on
 * these fields (`UNSUPPORTED_CAPABILITY`), an OR, a projection, or a sort other than `version` ascending
 * (`STORAGE_UNSUPPORTED`). Streams come in version order; `paged` pages and `cursor` pages by version, and `count`
 * counts streams. There is no aggregation.
 *
 * The caller's scope applies as on snapshots (decision D129): the tenant is [tenants]', and a stream whose scope (the
 * aggregate's owner and space after it, D56) is outside the caller's [principal][QueryCaller.principal] is not
 * returned. The HTTP entry runs under [entryPolicy]'s HTTP budget: its list, page and count limits and its timeout.
 *
 * A record is the stream: `aggregateId`, `tenantId`, `version`, `commitTime`, `commandId`, `requestId`,
 * `commandType`, `ownerId` and `spaceId` when set, `operator` (`{id, tenantId, crossScope}`, decision D163) when the
 * stream has one, and `events`, each `{name, revision, body}` as the log stores it (at its own revision, not
 * upgraded).
 */
class EventQueryGateway(
    val aggregateType: AggregateType,
    private val log: EventLog,
    private val tenants: QueryTenantPolicy = QueryTenantPolicy.CALLERS,
    val entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT,
) {
    /** The first stream [query] matches, or `null` when none does. */
    suspend fun single(query: ISingleQuery, caller: QueryCaller): ObjectNode? {
        val admitted = admit(query, caller) { it.check(query) }
        return bounded(admitted) { admitted.streams().firstOrNull()?.let(::record) }
    }

    /** The streams [query] matches, at most its limit (`0` for every one). */
    fun list(query: IListQuery, caller: QueryCaller): Flow<ObjectNode> = flow {
        val admitted = admit(query, caller) { it.check(query) }
        val streams = admitted.streams().let { if (query.limit > 0) it.take(query.limit) else it }
        // Bounded as a whole, as the snapshot gateway bounds a streamed list.
        val timeout = admitted.timeout
        if (timeout == null) {
            streams.collect { emit(record(it)) }
        } else {
            bounded(admitted) { streams.toList() }.forEach { emit(record(it)) }
        }
    }

    /** One page of the streams [query] matches, in version order, with its total as [IPagedQuery.total] asks. */
    suspend fun paged(query: IPagedQuery, caller: QueryCaller): PagedList<ObjectNode> {
        val admitted = admit(query, caller) { it.check(query) }
        return bounded(admitted) {
            val pagination = query.pagination
            val page = admitted.streams()
                .drop(Pagination.offset(pagination.index, pagination.size))
                .take(pagination.size)
                .toList()
                .map(::record)
            when (query.total) {
                TotalMode.NONE -> PagedList(null, page)
                TotalMode.EXACT -> PagedList(admitted.streams().count(null), page)
                TotalMode.BOUNDED -> {
                    val limit = admitted.budget?.totalLimit ?: QueryBudget.DEFAULT_COUNT_LIMIT
                    val total = admitted.streams().count(limit)
                    if (total > limit) PagedList(limit.toLong(), page, exact = false) else PagedList(total, page)
                }
            }
        }
    }

    /** One page of the streams [query] matches after its cursor, in version order, with the next page's cursor. */
    suspend fun cursor(query: ICursorQuery, caller: QueryCaller): CursorPage<ObjectNode> {
        val admitted = admit(query, caller) { it.check(query) }
        val after = query.cursor?.let(::decodeCursor) ?: 0
        return bounded(admitted) {
            val streams = admitted.copy(range = admitted.range.after(after)).streams().take(query.size + 1).toList()
            val page = streams.take(query.size)
            CursorPage(page.map(::record), page.lastOrNull()?.takeIf { streams.size > query.size }?.let(::encodeCursor))
        }
    }

    /** How many streams [query] matches, bounded by its limit or the budget's (decision D150). */
    suspend fun count(query: ICountQuery, caller: QueryCaller): CountResult {
        val admitted = admit(query, caller) { it.check(query) }
        val limit = query.limit ?: admitted.budget?.totalLimit
        return bounded(admitted) {
            val count = admitted.streams().count(limit)
            if (limit != null && count > limit) CountResult(limit.toLong(), exact = false) else CountResult(count, true)
        }
    }

    /**
     * What this model answers on [entry] (the `GET` of the query route): its fields and operators, and the limits of
     * [entry]'s budget. [defaultListSize] is the list size the route applies when a list names none. Its `version`, a
     * SHA-256 of the rest, is the route's `ETag`.
     */
    fun describe(entry: QueryEntry, defaultListSize: Int? = null): ObjectNode {
        val budget = entryPolicy.budget(entryPolicy.admit(entry))
        val descriptor = WowJson.mapper.createObjectNode()
        descriptor.put("model", MODEL)
        descriptor.putArray("operations").also { operations -> OPERATIONS.forEach(operations::add) }
        descriptor.putArray("identity").add(AGGREGATE_ID).add(VERSION)
        descriptor.putArray("required").add(AGGREGATE_ID)
        descriptor.putArray("sort").addObject().put("field", VERSION).put("direction", Sort.Direction.ASC.name)
        val fields = descriptor.putArray("fields")
        FIELDS.forEach { (field, operators) ->
            val described = fields.addObject().put("path", field)
            described.putArray("operators").also { operators.forEach(it::add) }
        }
        descriptor.putArray("record").also { record -> RECORD.forEach(record::add) }
        budget?.let { limits ->
            descriptor.putObject("limits")
                .put("maxListSize", limits.maxListSize)
                .put("maxPageSize", limits.maxPageSize)
                .put("maxPageWindow", limits.maxPageWindow)
                .put("countLimit", limits.countLimit)
                .put("allowExactCount", limits.allowExactCount)
                .put("timeoutMillis", limits.timeout.toMillis())
                .also { if (defaultListSize != null) it.put("defaultListSize", defaultListSize) }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(WowJson.mapper.writeValueAsBytes(descriptor))
        return WowJson.mapper.createObjectNode()
            .put("version", "sha256:" + digest.joinToString("") { "%02x".format(it) })
            .setAll(descriptor) as ObjectNode
    }

    /** A query of this model the caller may run: its aggregate's range, its budget and its timeout. */
    private data class Admitted(
        val range: EventRange,
        val tenantId: String,
        val principal: Scope,
        val budget: QueryBudget?,
        val timeout: Duration?,
    )

    private fun admit(query: FilterCapable<*>, caller: QueryCaller, check: (QueryBudget) -> Unit): Admitted {
        val entry = entryPolicy.admit(caller.entry)
        val budget = entryPolicy.budget(entry)
        budget?.let(check)
        if (query is Queryable<*>) checkShape(query)
        val range = EventRange.of(listOf(query.filter, caller.selection))
        val tenantId = tenants.tenantOf(aggregateType, caller) ?: range.tenantId
            ?: throw QueryViolation.StorageUnsupported(
                "an events query without a tenant: name its tenantId",
                TENANT_ID_FIELD,
            ).rejection()
        // A query that names another tenant than the caller's reads none.
        val scoped = if (range.tenantId != null && range.tenantId != tenantId) range.copy(empty = true) else range
        return Admitted(scoped, tenantId, caller.principal, budget, entryPolicy.timeout(entry))
    }

    /** Refuses a projection, and a sort other than the version ascending the streams come in. */
    private fun checkShape(query: Queryable<*>) {
        if (query.projection != Projection.ALL) {
            throw QueryViolation.StorageUnsupported("a projection of the events model").rejection()
        }
        val sort = query.sort
        if (sort.isNotEmpty() && sort != listOf(Sort(VERSION_FIELD, Sort.Direction.ASC))) {
            throw QueryViolation.StorageUnsupported("a sort of the events model other than version ascending")
                .rejection()
        }
    }

    /** The streams of the admitted range the caller may see, in version order. */
    private fun Admitted.streams(): Flow<EventStreamRecord> {
        if (range.empty || range.fromVersion > range.toVersion) return emptyFlow()
        val ref = aggregateType.ref(tenantId, range.aggregateId)
        return log.read(ref, range.fromVersion, range.toVersion)
            .takeWhile { it.commitTime <= range.toTime }
            .filter { it.commitTime >= range.fromTime && principal.covers(it.scope) }
    }

    private suspend fun <T> bounded(admitted: Admitted, block: suspend () -> T): T {
        val timeout = admitted.timeout ?: return block()
        return try {
            withTimeout(timeout.toMillis().coerceAtLeast(1)) { block() }
        } catch (expired: TimeoutCancellationException) {
            throw QueryTimeoutException(timeout, expired)
        }
    }

    companion object {
        /** The model's name, the `{model}` segment of its route. */
        const val MODEL: String = "events"

        const val AGGREGATE_ID: String = "aggregateId"
        const val TENANT_ID: String = "tenantId"
        const val VERSION: String = "version"
        const val COMMIT_TIME: String = "commitTime"

        private val AGGREGATE_ID_FIELD = QueryField(AGGREGATE_ID)
        private val TENANT_ID_FIELD = QueryField(TENANT_ID)
        private val VERSION_FIELD = QueryField(VERSION)
        private val COMMIT_TIME_FIELD = QueryField(COMMIT_TIME)
        private val RANGE_OPERATORS = listOf("EQ", "GT", "GTE", "LT", "LTE", "BETWEEN")
        private val FIELDS = linkedMapOf(
            AGGREGATE_ID to listOf("EQ", "ID", "AGGREGATE_ID"),
            TENANT_ID to listOf("EQ", "TENANT_ID"),
            VERSION to RANGE_OPERATORS,
            COMMIT_TIME to RANGE_OPERATORS,
        )
        private val OPERATIONS = listOf("single", "list", "paged", "cursor", "count")
        private val RECORD = listOf(
            AGGREGATE_ID, TENANT_ID, VERSION, COMMIT_TIME, "commandId", "requestId", "commandType", "ownerId",
            "spaceId", "operator", "events",
        )
        private const val CURSOR_PREFIX = "v:"

        private fun Scope.covers(scope: Scope): Boolean =
            (ownerId == null || ownerId == scope.ownerId) && (spaceId == null || spaceId == scope.spaceId)

        /** At most [limit] + 1 (`null`: every one) of these streams, as `count(*)` over `LIMIT limit + 1`. */
        private suspend fun Flow<EventStreamRecord>.count(limit: Int?): Long {
            var count = 0L
            val bounded = if (limit == null) this else take(limit + 1)
            bounded.collect { count++ }
            return count
        }

        private fun encodeCursor(stream: EventStreamRecord): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString((CURSOR_PREFIX + stream.version).toByteArray(StandardCharsets.UTF_8))

        private fun decodeCursor(token: String): Int {
            val decoded = runCatching { String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8) }
                .getOrNull()
            return decoded?.takeIf { it.startsWith(CURSOR_PREFIX) }?.removePrefix(CURSOR_PREFIX)?.toIntOrNull()
                ?.takeIf { it >= 1 }
                ?: throw QueryViolation.InvalidCursor.rejection()
        }

        /** [stream] as this model's record. */
        fun record(stream: EventStreamRecord): ObjectNode {
            val record = WowJson.mapper.createObjectNode()
                .put(AGGREGATE_ID, stream.ref.id)
                .put(TENANT_ID, stream.ref.tenantId)
                .put(VERSION, stream.version)
                .put(COMMIT_TIME, stream.commitTime)
                .put("commandId", stream.commandId)
                .put("requestId", stream.requestId)
                .put("commandType", stream.commandType)
            stream.scope.ownerId?.let { record.put("ownerId", it) }
            stream.scope.spaceId?.let { record.put("spaceId", it) }
            stream.operator?.let { operator ->
                record.putObject("operator")
                    .put("id", operator.id)
                    .put("tenantId", operator.tenantId)
                    .put("crossScope", operator.crossScope)
            }
            val events = record.putArray("events")
            stream.events.forEach { event ->
                events.addObject()
                    .put("name", event.name)
                    .put("revision", event.revision)
                    .set("body", WowJson.mapper.readTree(event.payload))
            }
            return record
        }
    }

    /**
     * The one aggregate and the version and commit time ranges a filter selects; [empty] when the filter matches
     * nothing (contradictory bounds, two aggregates, `MATCH_NONE`).
     */
    private data class EventRange(
        val aggregateId: String,
        val tenantId: String? = null,
        val fromVersion: Int = 1,
        val toVersion: Int = Int.MAX_VALUE,
        val fromTime: Long = Long.MIN_VALUE,
        val toTime: Long = Long.MAX_VALUE,
        val empty: Boolean = false,
    ) {
        /** This range past version [version]. */
        fun after(version: Int): EventRange =
            if (version >= Int.MAX_VALUE) copy(empty = true) else copy(fromVersion = maxOf(fromVersion, version + 1))

        companion object {
            fun of(filters: List<FilterExpression>): EventRange =
                Bounds().apply { filters.flatMap(::conditions).forEach(::add) }.build()

            private fun conditions(filter: FilterExpression): List<FilterExpression> = when (filter) {
                MatchAllFilter -> emptyList()
                is AndFilter -> filter.operands.flatMap(::conditions)
                else -> listOf(filter)
            }
        }

        /** The bounds the conditions of a filter put, gathered one by one. */
        private class Bounds {
            private val aggregateIds = mutableSetOf<String>()
            private val tenantIds = mutableSetOf<String>()
            private var versions = LongRange(1, Int.MAX_VALUE.toLong())
            private var times = LongRange(Long.MIN_VALUE, Long.MAX_VALUE)
            private var empty = false

            fun add(condition: FilterExpression) {
                when (condition) {
                    MatchNoneFilter -> empty = true
                    is IdFilter -> aggregateIds += condition.value
                    is AggregateIdFilter -> aggregateIds += condition.value
                    is TenantIdFilter -> tenantIds += condition.value
                    is FieldPredicate -> add(condition)
                    else -> throw QueryViolation.StorageUnsupported(
                        "the operator ${condition.operator} on the events model",
                    ).rejection()
                }
            }

            private fun add(condition: FieldPredicate) {
                when (condition.field) {
                    AGGREGATE_ID_FIELD -> aggregateIds += text(condition)
                    TENANT_ID_FIELD -> tenantIds += text(condition)
                    VERSION_FIELD -> versions = versions.intersect(range(condition, ::number))
                    COMMIT_TIME_FIELD -> times = times.intersect(range(condition, ::instant))
                    else -> throw QueryViolation.UnknownField(condition.field).rejection()
                }
            }

            fun build(): EventRange {
                val aggregateId = aggregateIds.firstOrNull() ?: throw QueryViolation.StorageUnsupported(
                    "an events query without its aggregate: name one aggregateId",
                    AGGREGATE_ID_FIELD,
                ).rejection()
                return EventRange(
                    aggregateId = aggregateId,
                    tenantId = tenantIds.firstOrNull(),
                    fromVersion = versions.first.coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
                    toVersion = versions.last.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                    fromTime = times.first,
                    toTime = times.last,
                    empty = empty || aggregateIds.size > 1 || tenantIds.size > 1 || versions.isEmpty() ||
                        times.isEmpty(),
                )
            }

            private fun text(condition: FieldPredicate): String {
                val value = (condition as? EqualFilter)?.value
                    ?: throw QueryViolation.UnsupportedCapability(condition.field, setOf(QueryCapability.EXACT_MATCH))
                        .rejection()
                return value as? String ?: throw QueryViolation.ValueMismatch(condition.field).rejection()
            }

            /** The bounds [condition] puts on its field, each read by [read]. */
            private fun range(condition: FieldPredicate, read: (QueryField, Any?) -> Long): LongRange {
                val field = condition.field
                return when (condition) {
                    is EqualFilter -> read(field, condition.value).let { it..it }
                    is GreaterThanFilter -> above(read(field, condition.value))..Long.MAX_VALUE
                    is GreaterThanOrEqualFilter -> read(field, condition.value)..Long.MAX_VALUE
                    is LessThanFilter -> Long.MIN_VALUE..below(read(field, condition.value))
                    is LessThanOrEqualFilter -> Long.MIN_VALUE..read(field, condition.value)
                    is BetweenFilter -> read(field, condition.lowerBound)..read(field, condition.upperBound)
                    else -> throw QueryViolation.UnsupportedCapability(
                        field,
                        setOf(QueryCapability.EXACT_MATCH, QueryCapability.RANGE),
                    ).rejection()
                }
            }

            private fun above(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1

            private fun below(value: Long): Long = if (value == Long.MIN_VALUE) value else value - 1

            private fun LongRange.intersect(other: LongRange): LongRange =
                maxOf(first, other.first)..minOf(last, other.last)

            private fun number(field: QueryField, value: Any?): Long = when (value) {
                is Int -> value.toLong()
                is Long -> value
                is java.math.BigInteger -> value.toLong()
                is java.math.BigDecimal -> runCatching { value.longValueExact() }.getOrNull()
                else -> null
            } ?: throw QueryViolation.ValueMismatch(field).rejection()

            /** Epoch milliseconds, given as a number or as an ISO-8601 instant. */
            private fun instant(field: QueryField, value: Any?): Long = when (value) {
                is String -> try {
                    Instant.parse(value).toEpochMilli()
                } catch (_: DateTimeParseException) {
                    throw QueryViolation.ValueMismatch(field).rejection()
                }
                else -> number(field, value)
            }
        }
    }
}
