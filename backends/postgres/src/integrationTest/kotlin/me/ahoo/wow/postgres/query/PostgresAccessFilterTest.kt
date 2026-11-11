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

package me.ahoo.wow.postgres.query

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.tck.AccessFilterSpec
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.snapshot.SnapshotRecord

/** The access filter contract on PostgreSQL's snapshot queries: a policy's filter pushed down to the `access` column. */
class PostgresAccessFilterTest : AccessFilterSpec() {
    override suspend fun store(type: AggregateType, records: List<AccessRecord>) {
        val store = PostgresFixture.snapshotStore(type.context)
        records.forEach { store.save(snapshotOf(type, it)) }
    }

    /** A snapshot is never unstamped: PostgreSQL's `access` column is `NOT NULL DEFAULT '{}'`. */
    override val keepsUnstamped: Boolean = false

    override fun binding(type: AggregateType): QueryBackendBinding<QueryBackend> =
        PostgresSnapshotQueryStorage(PostgresFixture.dataSource, type.context).binding(type)!!

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
