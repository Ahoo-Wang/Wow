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

package me.ahoo.wow.elasticsearch.query

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.elasticsearch.ElasticsearchFixture
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotStore
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.tck.QueryBackendSpec
import me.ahoo.wow.spi.snapshot.SnapshotRecord

/**
 * The query backend contract against Elasticsearch: each test's fresh context is a fresh snapshot index, whose state
 * fields are mapped from the TCK's declaration (as the Manifest's state schema maps them at startup) before the
 * snapshots are written.
 */
class ElasticsearchSnapshotQueryBackendTest : QueryBackendSpec() {
    private val queries = ElasticsearchSnapshotQueries(ElasticsearchFixture.client, ElasticsearchFixture.names)

    override suspend fun store(snapshots: List<SnapshotRecord>) {
        val store = ElasticsearchFixture.store().snapshots(type.context) as ElasticsearchSnapshotStore
        store.createIndex(type.name)
        val context = QuerySchemaContext(type, QueryModel.SNAPSHOT)
        DefaultQueryModelSchemaProvider(
            context,
            listOf(BeanQuerySchemaSource(listOf(QuerySchemaRegistration(context, DECLARATION)))),
            queries.binding(type).storage,
        ).schema()
        snapshots.forEach { store.save(it) }
    }

    /**
     * Elasticsearch indexes no value for a `null` or an empty array (`AbsentValues.AS_MISSING`): an explicit `null`, an
     * empty array and a missing field are one and the same to it, and an array equals no array operand
     * (`arrayEquality = NONE`, refused at admission).
     */
    override val semanticDivergences: Set<String> = setOf(
        "string.exists",
        "string.not-exists",
        "array.eq-array",
        "array.is-empty",
        "array.is-null",
        "array.is-not-null",
        "array.exists",
        "array.not-exists",
    )

    override fun binding(type: AggregateType): QueryBackendBinding<QueryBackend> = queries.binding(type)
}
