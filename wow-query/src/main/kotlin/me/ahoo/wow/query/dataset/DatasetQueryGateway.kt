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

package me.ahoo.wow.query.dataset

import me.ahoo.wow.query.AbstractQueryGateway
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryLogObserver
import me.ahoo.wow.query.QueryObserver
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.serialization.JsonSerializer
import tools.jackson.databind.node.ObjectNode

/**
 * The query gateway of one dataset. A dataset has no domain type, so its records are JSON objects: the typed methods
 * return the same records as the dynamic ones.
 */
interface DatasetQueryGateway : QueryGateway<ObjectNode> {
    val dataset: QueryDataset
}

class DefaultDatasetQueryGateway(
    override val dataset: QueryDataset,
    backend: DatasetQueryBackend,
    schemaProvider: QueryModelSchemaProvider,
    filters: List<QueryFilter> = emptyList(),
    policies: List<QueryPolicy> = emptyList(),
    observer: QueryObserver = QueryLogObserver(),
    entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT,
) : DatasetQueryGateway,
    AbstractQueryGateway<ObjectNode>(
        dataset.namedAggregate,
        backend,
        schemaProvider,
        JsonSerializer.typeFactory.constructType(ObjectNode::class.java),
        filters,
        DatasetQueryGateway::class,
        policies,
        observer,
        entryPolicy,
    )
