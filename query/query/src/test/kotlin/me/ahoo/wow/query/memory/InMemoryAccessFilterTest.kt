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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.tck.AccessFilterSpec
import me.ahoo.wow.spi.snapshot.SnapshotRecord

/**
 * The access filter contract on the in-memory backend, whose filters are the point reads' (X-26): the kernel's
 * evaluator and the compiled filter agree. The attributes are stored under the state, `state.access`, so a record can
 * be not stamped, which a snapshot cannot ([InMemorySnapshotAccessFilterTest] runs the snapshots' own `access`).
 */
class InMemoryAccessFilterTest : AccessFilterSpec() {
    private val storage = InMemoryQueryStorage()

    override val access: QueryField<*> = QueryField("state.access")

    override suspend fun store(type: AggregateType, records: List<AccessRecord>) {
        records.forEach { record ->
            val state = accessTree(record)?.let { """{"access":$it}""" } ?: "{}"
            storage.save(
                SnapshotRecord(
                    ref = type.ref("tenant", record.id),
                    version = 1,
                    schemaFingerprint = "tck",
                    state = state,
                    scope = Scope(record.owner, record.space),
                    deleted = false,
                    firstEventTime = 0,
                    lastEventTime = 0,
                ),
            )
        }
    }

    override fun binding(type: AggregateType): QueryBackendBinding<QueryBackend> = storage.binding(type)
}
