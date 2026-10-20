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

package me.ahoo.wow.analytics.catalog.domain.dataset

import me.ahoo.wow.analytics.catalog.api.dataset.DatasetArtifact
import me.ahoo.wow.analytics.catalog.api.source.SourceColumn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Where a dataset's versions are created (decision D251 ④): the warehouse's DDL, which only a publication, a rollback
 * and the end of a retirement run, each recorded by its event. The service binds the ClickHouse one.
 */
interface DatasetWarehouse {
    /**
     * Runs [artifact]'s DDL, creating (or replacing) its view, and gives the view's columns, in order; a
     * [DatasetWarehouseException] says why it did not, or why a query node could not serve the view it made.
     */
    suspend fun create(artifact: DatasetArtifact): List<SourceColumn>

    /**
     * The columns [select] gives now, in order, as the warehouse describes it without running it; null when the
     * warehouse cannot (a column it reads is gone, say).
     */
    suspend fun describe(select: String): List<SourceColumn>?

    /** Drops the [views] of [database] that are there. */
    suspend fun drop(database: String, views: List<String>)

    companion object {
        /** No warehouse: nothing is published. */
        @JvmField
        val NONE: DatasetWarehouse = object : DatasetWarehouse {
            override suspend fun create(artifact: DatasetArtifact): List<SourceColumn> =
                throw DatasetWarehouseException("The catalog has no warehouse to create ${artifact.view} in.")

            override suspend fun describe(select: String): List<SourceColumn>? = null

            override suspend fun drop(database: String, views: List<String>) = Unit
        }
    }
}

/** Why the warehouse did not run a dataset's DDL, as the warehouse said. */
class DatasetWarehouseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * How the catalog publishes datasets: the warehouse [database] their views are created in (`wow_datasets`), how
 * long a retired dataset's views are kept ([retention], 7 days, decision D259 7.2b-2), and the approval switches
 * (D238 ③b, D259; off by default): whether every publication takes an approver ([approvePublications]; one that
 * computes from sensitive values or lowers a protection always does) and whether a dataset reads only the sources an
 * approver approved ([approveSources]).
 */
data class DatasetPolicy(
    val database: String = DatasetCompiler.DEFAULT_DATABASE,
    val retention: Duration = 7.days,
    val approvePublications: Boolean = false,
    val approveSources: Boolean = false,
) {
    init {
        require(database.isNotBlank()) { "The datasets' database is named." }
        require(retention.isPositive()) { "A retired dataset's views are kept a while." }
    }
}
