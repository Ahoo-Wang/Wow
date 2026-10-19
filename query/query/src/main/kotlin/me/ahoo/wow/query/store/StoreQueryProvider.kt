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

package me.ahoo.wow.query.store

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.index.QueryIndexDdl
import me.ahoo.wow.query.index.QueryIndexDeclaration
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.spi.store.Store

/**
 * The query side of a store backend (review R3.3-1): for a [Store] of its [type] ([me.ahoo.wow.spi.store.StoreProvider]
 * opened it), the snapshot queries over the snapshots it holds and the `events` queries over the logs it holds
 * (decision D235), each type's routed to the store its snapshots or log are on (decision D177). The host finds the
 * providers as it finds the store providers: on the classpath (`ServiceLoader`, from
 * `META-INF/services/me.ahoo.wow.query.store.StoreQueryProvider`) and among the application's beans.
 *
 * A store type without one, or whose provider gives null, is not queried: its snapshot and `events` routes are not
 * served.
 */
@WowSpi
interface StoreQueryProvider {
    /** The type of the stores it queries, the [me.ahoo.wow.spi.store.StoreProvider.type]. */
    val type: String

    /** The snapshot queries of [store], or null when its snapshots are not queried. */
    fun snapshots(store: Store, options: StoreQueryOptions): SnapshotQueries? = null

    /** The `events` queries of [store]'s logs, or null when they are not queried. */
    fun events(store: Store, options: StoreQueryOptions): EventQueries? = null
}

/**
 * The snapshot queries of one store: the [binding] of each aggregate type's backend and storage facts and, for a
 * backend that manages the indexes an aggregate's `x-wow-index` declares (decision D151), how it [prepare]s the
 * aggregate's storage and [manageIndexes] them at startup.
 */
@WowSpi
interface SnapshotQueries {
    /** The backend of [type]'s snapshots, and its storage's facts. */
    fun binding(type: AggregateType): QueryBackendBinding<QueryBackend>

    /** Whether it manages the indexes ([manageIndexes]). */
    val managesIndexes: Boolean get() = false

    /** Makes sure [type]'s storage exists, so that its facts are the storage's own (a table created on first write). */
    fun prepare(type: AggregateType) = Unit

    /**
     * Manages [declarations], [type]'s indexes on its query model [schema], as [StoreQueryOptions.indexDdl] says:
     * creates the missing ones under `auto`, reports them under `validate`.
     */
    fun manageIndexes(
        type: AggregateType,
        schema: QueryModelSchema,
        declarations: List<QueryIndexDeclaration>,
    ): QueryIndexReport = QueryIndexReport.NONE
}

/** The `events` queries of one store's logs (decision D235): each aggregate type's backend, null for one it does not serve. */
@WowSpi
fun interface EventQueries {
    fun binding(type: AggregateType): QueryBackendBinding<QueryBackend>?
}

/** What managing one aggregate's indexes did: the indexes it [created], and those it found [missing] or [invalid]. */
@WowSpi
class QueryIndexReport(
    val created: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val invalid: List<String> = emptyList(),
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is QueryIndexReport && created == other.created && missing == other.missing && invalid == other.invalid

    override fun hashCode(): Int = listOf(created, missing, invalid).hashCode()

    override fun toString(): String = "QueryIndexReport(created=$created, missing=$missing, invalid=$invalid)"

    companion object {
        /** Nothing managed. */
        @JvmField
        val NONE: QueryIndexReport = QueryIndexReport()
    }
}

/**
 * The query settings a store's queries are opened with: [indexDdl] (`wow.query.index.ddl`, decision D151), and
 * [eventsReadPreference] (`wow.query.events.mongo-read-preference`), the replica a backend with replicas reads the
 * `events` queries from (MongoDB's `secondaryPreferred`, say), null for its primary.
 *
 * Fields may be added (decision D216): the host builds it, so the constructor is internal to Wow; a provider's test
 * builds one with [of].
 */
@WowSpi
class StoreQueryOptions @InternalWowApi constructor(
    val indexDdl: QueryIndexDdl = QueryIndexDdl.AUTO,
    val eventsReadPreference: String? = null,
) {
    override fun toString(): String =
        "StoreQueryOptions(indexDdl=$indexDdl, eventsReadPreference=$eventsReadPreference)"

    companion object {
        /** The defaults: indexes `auto`, `events` read from the primary. */
        @JvmField
        val DEFAULT: StoreQueryOptions = StoreQueryOptions()

        /** Options of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(indexDdl: QueryIndexDdl = QueryIndexDdl.AUTO, eventsReadPreference: String? = null): StoreQueryOptions =
            StoreQueryOptions(indexDdl, eventsReadPreference)
    }
}
