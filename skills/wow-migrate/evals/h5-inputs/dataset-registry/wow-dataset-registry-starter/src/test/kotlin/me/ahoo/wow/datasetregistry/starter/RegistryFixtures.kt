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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.backend.BackendPage
import me.ahoo.wow.query.backend.CursorPositionCodec
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.dataset.DatasetQueryBackend
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.serialization.toObjectNode
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

/** A dataset backend that is never queried: the tests here compile schemas only. */
internal object NoDatasetBackend : DatasetQueryBackend {
    override val namedAggregate: NamedAggregate = MaterializedNamedAggregate("analytics", "orders")
    override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON

    override fun stream(query: AdmittedQuery<IListQuery>): Flux<ObjectNode> = Flux.empty()

    override fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): Mono<BackendPage> = Mono.empty()

    override fun count(query: AdmittedQuery<FilterExpression>): Mono<Long> = Mono.just(0)

    override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flux<ObjectNode> =
        Flux.empty()
}

internal object RegistryFixtures {
    fun document(field: String = "city"): ObjectNode = """
        {
          "title": "Orders",
          "identity": "id",
          "scope": { "tenant": "tenant_id" },
          "storage": { "table": "bi.orders" },
          "fields": {
            "id": { "types": ["string"], "nullable": false },
            "tenant_id": { "types": ["string"], "nullable": false },
            "$field": { "types": ["string"], "nullable": false }
          }
        }
    """.trimIndent().toObjectNode()

    /** Binds every declared root column to itself with exact matching. */
    val ADAPTER = QueryStorageAdapter { logical ->
        Mono.just(
            QueryStorageFacts(
                bindings = logical.values.keys.filter { it.segments.size == 1 }.associateWith { path ->
                    val native = QueryFieldBindingTemplate(path, null)
                    QueryValueBindings(mapOf(QueryCapability.EXACT_MATCH to native), path, path)
                },
            ),
        )
    }
}
