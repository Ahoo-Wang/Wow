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
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.elasticsearch.ElasticsearchFixture
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotStore
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.tck.AccessFilterSpec
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.snapshot.SnapshotRecord

/**
 * The access filter contract on Elasticsearch's snapshot queries: a policy's filter pushed down to the `access`
 * object. The attributes' fields are mapped before the records are stored, as a node maps them at its start.
 */
class ElasticsearchAccessFilterTest : AccessFilterSpec() {
    private val queries = ElasticsearchSnapshotQueries(ElasticsearchFixture.client, ElasticsearchFixture.names)

    override suspend fun store(type: AggregateType, records: List<AccessRecord>) {
        val store = ElasticsearchFixture.store().snapshots(type.context) as ElasticsearchSnapshotStore
        store.createIndex(type.name)
        val context = QuerySchemaContext(type, QueryModel.SNAPSHOT)
        DefaultQueryModelSchemaProvider(
            context,
            listOf(BeanQuerySchemaSource(listOf(QuerySchemaRegistration(context, declaration())))),
            queries.binding(type).storage,
        ).schema()
        records.forEach { store.save(snapshotOf(type, it)) }
    }

    /** A snapshot is never unstamped: its document always holds `access`, `{}` for none. */
    override val keepsUnstamped: Boolean = false

    override fun binding(type: AggregateType): QueryBackendBinding<QueryBackend> = queries.binding(type)

    /** [record] as the snapshot it is of: its attributes in their stored form, none on a record not stamped. */
    private fun snapshotOf(type: AggregateType, record: AccessRecord): SnapshotRecord = SnapshotRecord(
        ref = type.ref(TENANT, record.id),
        version = 1,
        schemaFingerprint = "tck",
        state = "{}",
        scope = Scope(record.owner, record.space),
        firstEventTime = 0,
        lastEventTime = 0,
        access = AccessValues.of(accessTree(record)).orEmpty(),
    )

    private companion object {
        const val TENANT = "tenant"
    }
}
