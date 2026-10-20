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

@file:JvmName("QueryStreams")

package me.ahoo.wow.query

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.query.schema.cursorUniqueKey
import tools.jackson.databind.node.ObjectNode

/**
 * One record of a resumable stream (decision D253) and its [position]: the cursor token of the record's place in the
 * stream's effective sort, the same kind of token a `cursor` query's `nextCursor` is (the native position, never a
 * value of the record, with the model and sort's fingerprint). A stream resumed after [position] continues strictly
 * after this record.
 */
class PositionedRecord(val record: ObjectNode, val position: String) {
    override fun toString(): String = "PositionedRecord(position=$position)"
}

/**
 * The records of [query] as a resumable stream (decision D253): keyset pages of [ICursorQuery.size] records, each its
 * own [QueryBackend.page] call within the admitted timeout, one after the other from after the query's cursor token,
 * until a page comes back short or [limit] records were emitted (`0`: no limit). Each record carries the token of its
 * position.
 *
 * A backend under a bulkhead takes a permit for each page and gives it back before the page's records are emitted, so
 * a stream holds no permit while its consumer reads, and a lowered limit applies to it from its next page. Pages are
 * read one after the other, not in one snapshot: a record written between two pages is seen when it sorts after the
 * last position read, and a record that moves across that position may be seen twice or not at all, as with `cursor`
 * queries.
 */
fun QueryBackend.chunks(query: AdmittedQuery<ICursorQuery>, limit: Int = 0): Flow<PositionedRecord> {
    require(limit >= 0) { "limit must be greater than or equal to 0." }
    return flow {
        val cursor = query.query
        val sort = cursor.sort.map { Sort(query.field(it.field).logicalField, it.direction) }
        val tokens = CursorTokens.of(aggregateType, query.schema, sort, cursorPositions)
        var after = cursor.cursor?.let(tokens::decode)
        var remaining = limit
        while (true) {
            val size = if (limit == 0) cursor.size else minOf(cursor.size, remaining)
            val page = executing(query.timeout) { page(query, PageWindow.Keyset(after, size)) }
            val rows = page.rows.take(size)
            if (rows.isEmpty()) return@flow
            val positions = page.positions
                ?: throw QueryExecutionException("Backend keyset page must carry each row's position.")
            rows.forEachIndexed { index, row ->
                emit(PositionedRecord(row.requireStandardRecord(), tokens.encode(positions[index])))
            }
            if (limit != 0) remaining -= rows.size
            if (rows.size < size || limit != 0 && remaining == 0) return@flow
            after = positions[rows.lastIndex]
        }
    }
}

/**
 * Whether a list sorted by [sort] streams as keyset chunks (decision D253): the storage pages by keyset, and the sort
 * with the model's unique key appended orders a cursor (every field cursor-sortable, none twice, at most
 * [Sort.MAX_FIELDS]). A field the schema does not know counts as cursor-sortable, so admission rejects it as it
 * rejects it in a list. A sort that cannot order a cursor (a protected, nested or array-valued field, whose position
 * a token would expose or cannot hold) streams without positions, and its `end` events are not resumable.
 */
internal fun QueryModelSchema.streamsByKeyset(sort: List<Sort>): Boolean {
    if (storage.paging.keyset == SupportMode.NONE) return false
    val uniqueKey = try {
        cursorUniqueKey()
    } catch (_: QuerySchemaValidationException) {
        return false
    }
    val effective = sort.map { it.copy(field = definition.canonical(it.field)) }.withUniqueSort(uniqueKey)
    if (effective.size > Sort.MAX_FIELDS || effective.distinctBy { it.field }.size != effective.size) return false
    return effective.all { field(it.field)?.effective?.cursorSortable ?: true }
}
