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

package me.ahoo.wow.query.memory

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.query.backend.QueryBackendStorages
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The one in-memory table the reference semantics run on, bound per test, with the reads its tests share; the
 * table's fields and rows in the companion.
 */
internal abstract class InMemoryReferenceFixtures {
    private val storage = InMemoryDatasetStorage()
    private val schemaAndBackend by lazy { runBlocking { target() } }
    protected val schema: QueryModelSchema get() = schemaAndBackend.first
    protected val backend: InMemoryQueryBackend get() = schemaAndBackend.second

    private suspend fun target(): Pair<QueryModelSchema, InMemoryQueryBackend> {
        val dataset = dataset(storage.put(TABLE, ROWS.map { WowJson.mapper.readTree(it) as ObjectNode }))
        val datasets = QueryDatasets(listOf(dataset))
        val catalog = QuerySchemaCatalog(
            storages = QueryBackendStorages(datasetBindings = storage, datasets = datasets),
            compiler = QueryModelCompiler.of(datasets = datasets),
            datasets = datasets,
        )
        return catalog.schema(dataset.name, QueryModel.DATASET) to
            checkNotNull(storage.binding(dataset)).backend as InMemoryQueryBackend
    }

    /** The table's dataset: a flat record of every shape the backend reads. */
    private fun dataset(binding: ObjectNode): QueryDataset =
        QueryDataset.of(AggregateType("memory", TABLE)) {
            tenant("tenant_id")
            identity("id")
            storage(binding)
            field("tenant_id") { types(QueryValueType.STRING) }
            field("id") { types(QueryValueType.STRING) }
            field("name") {
                types(QueryValueType.STRING)
                nullable(true)
            }
            field("note") {
                types(QueryValueType.STRING)
                nullable(true)
            }
            field("price") {
                types(QueryValueType.DECIMAL)
                nullable(true)
            }
            field("qty") {
                types(QueryValueType.INTEGER)
                nullable(true)
            }
            field("flag") {
                types(QueryValueType.BOOLEAN)
                nullable(true)
            }
            field("day") {
                types(QueryValueType.STRING)
                semantic(Temporal.Date)
                nullable(true)
            }
            field("at") {
                types(QueryValueType.INTEGER)
                temporalEpoch(TimeUnit.SECONDS)
                nullable(true)
            }
            field("tags") {
                kind(QueryValueKind.ARRAY)
                items {
                    types(QueryValueType.STRING)
                    nullable(false)
                }
                nullable(true)
            }
            field("lines") {
                kind(QueryValueKind.ARRAY)
                items {
                    kind(QueryValueKind.OBJECT)
                    property("sku") { types(QueryValueType.STRING) }
                    property("price") { types(QueryValueType.DECIMAL) }
                }
                nullable(true)
            }
        }

    protected fun ids(filter: FilterExpression, sort: List<Sort> = emptyList()): List<String> = runBlocking {
        backend.stream(QueryAdmission.Trusted.list(ListQuery(filter, sort = sort), schema)).toList()
            .map { it.path("id").stringValue() }
    }

    protected fun matches(filter: FilterExpression): Set<String> = ids(filter).toSet()

    protected fun aggregate(query: AggregationQuery, window: GroupWindow = GroupWindow.All): List<ObjectNode> =
        runBlocking { backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema), window).toList() }

    /** Filters evaluated directly, past admission: what admission would refuse still reaches a backend's table. */
    protected fun direct(filter: FilterExpression, row: Int = 0): Boolean {
        val admitted = QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema)
        return InMemoryFilters(admitted).matches(filter, RecordScope(storage.rows(TABLE)[row]))
    }

    internal companion object {
        const val TABLE = "items"
        val JAN_5: Long = Instant.parse("2026-01-05T00:00:00Z").toEpochMilli()
        val NAME = QueryField("name")
        val NOTE = QueryField("note")
        val PRICE = QueryField("price")
        val QTY = QueryField("qty")
        val FLAG = QueryField("flag")
        val DAY = QueryField("day")
        val AT = QueryField("at")
        val TAGS = QueryField("tags")
        val LINES = QueryField("lines")
        val SKU = QueryField("sku")
        val ELEMENT_PRICE = QueryField("price")

        /*
         * Five rows over two tenants. `day` stores dates in each ISO form; `at` stores epoch seconds an hour after
         * r1's day. r4 holds no price, no tags but an empty list, and no note; r5 is of the other tenant.
         */
        val ROWS = listOf(
            """{"id":"r1","tenant_id":"t1","name":"Alpha","note":"x","price":10,"qty":1,"flag":true,""" +
                """"day":"2026-01-05","at":${JAN_5 / 1000 + 3600},"tags":["a","b"],""" +
                """"lines":[{"sku":"A","price":5.0},{"sku":"B","price":5.0}]}""",
            """{"id":"r2","tenant_id":"t1","name":"Beta","note":"","price":20.0,"qty":2,"flag":false,""" +
                """"day":"2026-01-06T10:00:00Z","at":${JAN_5 / 1000 + 86_400},"tags":["b"],""" +
                """"lines":[{"sku":"A","price":20}]}""",
            """{"id":"r3","tenant_id":"t1","name":"Gamma","note":null,"price":30,"qty":3,"flag":true,""" +
                """"day":"2026-01-07T08:00:00+08:00","at":${JAN_5 / 1000 + 86_400 + 1},"tags":["c"],"lines":[]}""",
            """{"id":"r4","tenant_id":"t1","name":"Delta","qty":4,"day":"2026-01-08T09:30:00",""" +
                """"at":${JAN_5 / 1000 + 2 * 86_400},"tags":[]}""",
            """{"id":"r5","tenant_id":"t2","name":"Epsilon","price":50,"qty":0,"flag":false,"day":"bad"}""",
        )
    }
}
