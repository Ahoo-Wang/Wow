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

package me.ahoo.wow.datasetregistry.starter

import me.ahoo.wow.clickhouse.query.ClickHouseDatasetDrafts
import me.ahoo.wow.clickhouse.query.ClickHouseExecutor
import me.ahoo.wow.clickhouse.query.ClickHouseSource
import me.ahoo.wow.datasetregistry.api.SkippedColumn
import me.ahoo.wow.datasetregistry.domain.DatasetDraft
import me.ahoo.wow.datasetregistry.domain.DatasetRegistryException
import me.ahoo.wow.datasetregistry.domain.DatasetTableInspector
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import reactor.core.publisher.Mono

/**
 * Drafts a dataset from a ClickHouse table, `database.table` or a table of its source's default database, by its
 * `system.columns` ([ClickHouseDatasetDrafts]): in the default source ([executor], [defaultDatabase]) or in one of the
 * named [sources].
 */
class ClickHouseDatasetTableInspector(
    private val executor: ClickHouseExecutor,
    private val defaultDatabase: String,
    private val sources: Map<String, ClickHouseSource> = emptyMap(),
) : DatasetTableInspector {
    override fun draft(table: String, source: String?): Mono<DatasetDraft> = Mono.defer {
        val parts = table.split('.')
        if (parts.size > 2 || parts.any(String::isBlank)) {
            throw DatasetRegistryException.invalid("[$table] is not a ClickHouse table name.")
        }
        val named = source?.let {
            sources[it] ?: throw DatasetRegistryException.invalid(
                "[$it] is not a ClickHouse source; the sources are ${sources.keys}.",
            )
        }
        val database = if (parts.size == 2) parts[0] else named?.database ?: defaultDatabase
        (named?.executor ?: executor).table(database, parts.last())
            .map { ClickHouseDatasetDrafts.draft(it, source) }
            .map { draft -> DatasetDraft(draft.document, draft.skipped.map { SkippedColumn(it.name, it.type) }) }
            .onErrorMap(QuerySchemaUnavailableException::class.java) {
                DatasetRegistryException.invalid(it.message ?: "Table [$table] does not exist.", it)
            }
    }
}
