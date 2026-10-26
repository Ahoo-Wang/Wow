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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.tck.EventQueryBackendSpec
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventStreamRecord

/** The events backend contract against PostgreSQL (decision D235): each test's fresh context is a fresh log. */
class PostgresEventQueryBackendTest : EventQueryBackendSpec() {
    override suspend fun store(streams: List<Pair<EventStreamRecord, ByteArray?>>) {
        val log = PostgresFixture.log(PARTITIONS, type.context, listOf(type.name))
        streams.forEach { (stream, transport) ->
            (log.append(stream, transport) is AppendOutcome.Committed).assert().isTrue()
        }
    }

    override fun binding(type: AggregateType): QueryBackendBinding<QueryBackend> =
        PostgresEventQueryStorage(PostgresFixture.dataSource, type.context).create(type)

    private companion object {
        const val PARTITIONS = 4
    }
}
