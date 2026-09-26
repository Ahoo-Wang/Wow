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

package me.ahoo.wow.query

import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.query.schema.QueryStorageAdapter
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

/**
 * What a storage supplies for one aggregate's read model: the [backend] that executes admitted queries and the
 * [storage] adapter that reports its native facts. The [QuerySchemaCatalog][me.ahoo.wow.query.schema.QuerySchemaCatalog]
 * compiles those facts with the model's sources and sensitivity; the storage never builds the schema itself.
 */
data class QueryBackendBinding<out B : QueryBackend>(
    val backend: B,
    val storage: QueryStorageAdapter,
)

/**
 * Aggregate-bound storage SPI: four primitives that only check natively, translate and execute.
 *
 * The core derives every query shape from them (see [single], [list], [paged], [cursor], [aggregate] in
 * `BackendQueries.kt`): single is `page(Offset(0, 1, withTotal = false))`, list is [stream], paged is
 * `page(Offset(..., withTotal = true))`, cursor is `page(Keyset)`. Windows come only from the core; the core also owns
 * the cursor token shell, the residual aggregation operators the storage declares
 * [RESIDUAL][me.ahoo.wow.query.schema.SupportMode.RESIDUAL] and the empty summary row.
 *
 * Every input is an [AdmittedQuery]: validated against its schema, normalized, with each field reference resolved.
 * Model defaults such as Snapshot ACTIVE are applied by the gateway; direct callers obtain their input from
 * [QueryAdmission] and supply any required deletion or access predicates themselves.
 *
 * Every subscription to a returned publisher, including subscriptions created by `retry`, `repeat`, or concurrent
 * callers, must own fresh mutable [ObjectNode] instances. Implementations must not cache or share nodes across
 * subscriptions, publish cached nodes, mutate emitted nodes asynchronously, or mutate them after delivery.
 *
 * Results must contain only standard JSON-tree values: the Backend converts its driver's values (`Map`/`Document`,
 * BSON values, decimals) to them. The core checks every returned row, so no Backend repeats the check: a row with a
 * `NaN`, an infinity, a `POJONode`, a binary or missing node fails the query in the core.
 */
interface QueryBackend : NamedAggregateDecorator {
    /** Encodes and decodes the native [CursorPosition]s [page] returns for a [PageWindow.Keyset]. */
    val cursorPositions: CursorPositionCodec

    /** Streams the records of [query], at most `query.limit` of them, or all when the limit is `0`. */
    fun stream(query: AdmittedQuery<IListQuery>): Flux<ObjectNode>

    /**
     * Returns one window of [query]'s records: for [PageWindow.Offset] the rows and, when asked, the total; for
     * [PageWindow.Keyset] the rows after the position and each row's native position, taken before any field added
     * for the position is stripped from the row.
     */
    fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): Mono<BackendPage>

    fun count(query: AdmittedQuery<FilterExpression>): Mono<Long>

    /**
     * Streams the groups of [query] in its effective sort order, at most [GroupWindow.First.limit] of them, or every
     * group for [GroupWindow.All]. The core has already removed from [query] what it computes itself.
     *
     * Without groups the result is one summary row over the matched records. The Backend may emit it always (as
     * Elasticsearch does) or emit nothing when no record matched (as MongoDB `$group` does); the core emits the
     * empty summary only when the Backend emits no row, so it never duplicates one.
     */
    fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flux<ObjectNode>
}

/** Which records [QueryBackend.page] returns. */
sealed interface PageWindow {
    /** Records `offset until offset + limit` in sort order; [withTotal] also asks for the number of matches. */
    data class Offset(val offset: Int, val limit: Int, val withTotal: Boolean) : PageWindow {
        init {
            require(offset >= 0) { "offset must be greater than or equal to 0." }
            require(limit >= 0) { "limit must be greater than or equal to 0." }
        }
    }

    /** At most [limit] records strictly after [after] in sort order, from the first when [after] is `null`. */
    data class Keyset(val after: CursorPosition?, val limit: Int) : PageWindow {
        init {
            require(limit >= 1) { "limit must be greater than or equal to 1." }
        }
    }
}

/** Which groups [QueryBackend.aggregate] returns. */
sealed interface GroupWindow {
    /** The first [limit] groups in the query's effective sort order. */
    data class First(val limit: Int) : GroupWindow {
        init {
            require(limit >= 1) { "limit must be greater than or equal to 1." }
        }
    }

    /** Every group: the core computes a residual operator over all of them before it limits. */
    data object All : GroupWindow
}

/**
 * One window of records. [total] is present when the window asked for it; [positions] holds each row's native
 * cursor position, in row order, for a [PageWindow.Keyset].
 */
class BackendPage(
    val rows: List<ObjectNode>,
    val total: Long? = null,
    val positions: List<CursorPosition>? = null,
) {
    init {
        checkExecution(total == null || total >= 0) { "Backend page total must be greater than or equal to 0." }
        checkExecution(positions == null || positions.size == rows.size) { "Every row needs one cursor position." }
    }
}
