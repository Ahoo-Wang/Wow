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

package me.ahoo.wow.spi.access

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * The walks of a log's history ([AccessStamps] it also is): a walk of an aggregate type stamps each of its aggregates'
 * streams with the attributes of its current state, for the streams no commit stamped with the type's current access
 * schema (written before the log kept attributes, imported, or before the model declared what it declares now).
 *
 * The log pages the type's aggregates ([aggregates]) and keeps each type's walk beside the type in its registry
 * ([walk]): the access schema it is of, the log's heads when it was claimed, how far it is. Claims are compare-and-set
 * by schema ([claim]), so concurrent claims leave one walk.
 *
 * None of these creates the store of a type's history (its collection, its table): a type the log holds none of
 * ([holds]) has nothing to walk, and its first append creates its store as it would without walks. The step that
 * creates it records the walk completed for the schema the log [serve] for the type, if any.
 */
@InternalWowApi
interface AccessWalks : AccessStamps {
    /**
     * Whether the log holds a store of [type]'s history: one its first append created, or one created up front. A
     * type it holds none of has no walk, and claims none.
     */
    suspend fun holds(type: AggregateType): Boolean

    /**
     * At most [limit] aggregates of [type], in the log's own key order, after the page whose [AggregatePage.next] is
     * [after] (null: from the first); none of a type the log [holds] no store of.
     */
    suspend fun aggregates(type: AggregateType, after: String?, limit: Int): AggregatePage

    /** The walk of [type] as the log records it; null before any [claim]. */
    suspend fun walk(type: AggregateType): AccessWalk?

    /**
     * Records [walk], a new walk of [type], in place of the recorded one when that is of the schema [expected], or
     * when none is recorded and [expected] is null; returns whether it did. It records none of a type the log
     * [holds] no store of: the walk is kept beside the type's store.
     */
    suspend fun claim(type: AggregateType, walk: AccessWalk, expected: String?): Boolean

    /**
     * Records how far the walk of [type] of [schema] is: the page it is past ([cursor]), the aggregates [walked], and
     * whether it is [completed]; returns false, recording nothing, when the recorded walk is of another schema.
     */
    suspend fun advance(type: AggregateType, schema: String, cursor: String?, walked: Long, completed: Boolean): Boolean

    /**
     * Tells the log the access schema its node serves for each type of [schemas], before it creates any of their
     * stores: the step that creates a type's store, an append or a table made up front, records the type's walk
     * completed for that schema in the same write ([AccessWalk.created]). Every stream a store this version created
     * holds was committed with its attributes, so there is nothing to walk. A store that exists keeps its walk, and a
     * log no node told (an importer's, a tool's) records none: its types' walks are claimed as before. Without this
     * call a log records none.
     *
     * A store created during a rolling deployment that changes the type's access schema is walked once the other
     * version's nodes are gone: a node whose walk was recorded so, and that saw a node of another schema serve the type,
     * claims the walk again over the log's heads then. It rests on a node of the schema that stays that saw the other
     * and is still up once it is gone, which a rolling deployment gives.
     */
    fun serve(schemas: Map<AggregateType, String>)
}

/** A page of a type's aggregates ([AccessWalks.aggregates]), and the cursor of the next one; null after the last. */
@InternalWowApi
data class AggregatePage(val refs: List<AggregateRef>, val next: String?) {
    @InternalWowApi
    companion object {
        /** The page of [refs], at most [limit] of them: its cursor is the last one's ([cursorOf]) when it is full. */
        fun of(refs: List<AggregateRef>, limit: Int): AggregatePage =
            AggregatePage(refs, refs.takeIf { it.size >= limit }?.last()?.let(::cursorOf))

        /** The cursor after [ref] in a log ordered by id, then tenant: the id's length, the id, the tenant. */
        fun cursorOf(ref: AggregateRef): String = "${ref.id.length}:${ref.id}${ref.tenantId}"

        /** The aggregate id and the tenant of [cursor] ([cursorOf]). */
        fun keyOf(cursor: String): Pair<String, String> {
            val colon = cursor.indexOf(':')
            require(colon > 0) { "Not a cursor of aggregates: $cursor" }
            val start = colon + 1
            val end = start + cursor.substring(0, colon).toInt()
            return cursor.substring(start, end) to cursor.substring(end)
        }
    }
}

/**
 * The walk of one aggregate type, as its log records it ([AccessWalks.walk]): [schema], the type's access schema it
 * stamps with; [heads], each partition's head when it was claimed, which bound the streams it stamps (later ones are
 * the follower's); [cursor], the page of aggregates it is past (null: none yet); [walked], the aggregates it stamped;
 * whether it is [completed].
 */
@InternalWowApi
data class AccessWalk(
    val schema: String,
    val heads: Map<Int, Long>,
    val cursor: String? = null,
    val walked: Long = 0,
    val completed: Boolean = false,
) {
    @InternalWowApi
    companion object {
        /**
         * The [schema] of a walk an import claimed once the type's first streams were in: no node serves it, so the
         * nodes take it over as an unfinished walk of another schema and walk the type, unless the import's snapshot
         * step stamps the history and completes the walk in its place.
         */
        const val IMPORTING: String = "wow.import"

        /**
         * The walk the creation of a type's store records for [schema], the one its log [AccessWalks.serve]:
         * completed, with nothing walked and no heads.
         */
        fun created(schema: String): AccessWalk = AccessWalk(schema, emptyMap(), completed = true)
    }
}
