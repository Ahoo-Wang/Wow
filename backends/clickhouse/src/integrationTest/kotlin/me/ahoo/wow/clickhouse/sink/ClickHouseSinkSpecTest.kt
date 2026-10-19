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


package me.ahoo.wow.clickhouse.sink

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.tck.sink.SinkSpec
import java.util.concurrent.atomic.AtomicInteger

/** The [SinkSpec] on the ClickHouse Sink: what a reader of the history table sees, retried inserts included. */
class ClickHouseSinkSpecTest : SinkSpec() {
    private val database = "wow_sink_spec_${databases.incrementAndGet()}"

    init {
        ClickHouseSinkIntegrationTest.execute("CREATE DATABASE IF NOT EXISTS $database")
    }

    override val aggregateType: AggregateType = SinkFixtures.ORDER

    override fun createSink(): Sink =
        ClickHouseSinks(ClickHouseSinkIntegrationTest.client, SinkFixtures.MANIFEST, ClickHouseSinkOptions(database = database))
            .sinkOf("shop", "order")

    override fun record(ref: AggregateRef, version: Int): StateEventRecord =
        SinkFixtures.recordOf(id = ref.id, version = version, tenantId = ref.tenantId)

    override suspend fun written(ref: AggregateRef): List<Int> =
        ClickHouseSinkIntegrationTest.client.queryAll(
            "SELECT version FROM $database.shop_order_state_events WHERE aggregate_id = '${ref.id}' ORDER BY version",
        ).map { it.getInteger("version") }

    private companion object {
        val databases = AtomicInteger()
    }
}
