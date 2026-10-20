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

package me.ahoo.wow.datasetregistry.domain

import me.ahoo.wow.datasetregistry.api.SkippedColumn
import me.ahoo.wow.query.schema.QueryDataset
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

/** A declaration drafted from a table, and the columns it leaves out. */
data class DatasetDraft(val document: ObjectNode, val skipped: List<SkippedColumn> = emptyList())

/**
 * Drafts a dataset declaration from a table of the backend that serves datasets, such as ClickHouse's
 * `system.columns`, in the named [source][draft] (the default one when `null`); the draft's storage binding names that
 * source. An unknown table or source is a [DatasetRegistryException.invalid].
 */
fun interface DatasetTableInspector {
    fun draft(table: String, source: String?): Mono<DatasetDraft>
}

/**
 * Proves that this instance can serve [QueryDataset] before it is published: its name is not taken by a dataset of a
 * declaration file, and its declaration compiles against its table. A refusal is a [DatasetRegistryException].
 */
fun interface DatasetPublicationCheck {
    fun check(dataset: QueryDataset): Mono<Void>
}
