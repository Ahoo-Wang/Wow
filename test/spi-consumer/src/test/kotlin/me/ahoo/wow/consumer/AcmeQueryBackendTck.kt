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

package me.ahoo.wow.consumer

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.store.StoreQueryOptions
import me.ahoo.wow.query.tck.QueryBackendSpec
import me.ahoo.wow.spi.snapshot.SnapshotRecord

/**
 * The query contract over Acme's snapshots: the in-memory reference backend read through the schema Acme's own
 * adapter, written on the public vocabulary alone, compiles to.
 */
class AcmeQueryBackendTck : QueryBackendSpec() {
    private val storage = InMemoryQueryStorage()
    private val snapshots = AcmeSnapshotStore(storage)
    private val queries = checkNotNull(AcmeQueries(storage).snapshots(StoreQueryOptions.DEFAULT))

    override suspend fun store(snapshots: List<SnapshotRecord>) {
        snapshots.forEach { this.snapshots.save(it) }
    }

    override fun binding(type: AggregateType): QueryBackendBinding<QueryBackend> = checkNotNull(queries.binding(type))
}
