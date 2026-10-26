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

package me.ahoo.wow.query.store

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.gatewaySchema
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreConfig
import org.junit.jupiter.api.Test

/** The values and defaults of the store query provider SPI (review R3.3-1). */
class StoreQueryProviderTest {
    private val type = AggregateType("shop", "order")

    @Test
    fun `a provider queries nothing unless it says so`() {
        val provider = object : StoreQueryProvider {
            override val type: String = "acme"
        }
        val store = object : Store(StoreConfig.of("a", "acme")) {
            override val location: Any = Any()

            override fun openCheckpoints() = error("unused")

            override fun openLeases() = error("unused")

            override fun openLedger() = error("unused")

            override fun openReservations() = error("unused")

            override fun openLog(context: String, partitions: Int) = error("unused")

            override fun openSnapshots(context: String) = error("unused")

            override fun openTimers(context: String, partitions: Int) = error("unused")
        }
        provider.snapshots(store, StoreQueryOptions.DEFAULT).assert().isNull()
        provider.events(store, StoreQueryOptions.DEFAULT).assert().isNull()
    }

    @Test
    fun `snapshot queries manage no index unless they say so`() {
        val storage = InMemoryQueryStorage()
        val queries = object : SnapshotQueries {
            override fun binding(type: AggregateType): QueryBackendBinding<QueryBackend> = storage.create(type)
        }
        queries.managesIndexes.assert().isFalse()
        queries.prepare(type)
        queries.binding(type).backend.aggregateType.assert().isEqualTo(type)
        queries.manageIndexes(type, gatewaySchema(), emptyList()).assert().isSameAs(QueryIndexReport.NONE)
    }

    @Test
    fun `an index report and the options are values`() {
        val report = QueryIndexReport(created = listOf("a"), missing = listOf("b"), invalid = listOf("c"))
        report.assert().isEqualTo(
            QueryIndexReport(listOf("a"), listOf("b"), listOf("c"))
        ).isNotEqualTo(QueryIndexReport.NONE)
        report.hashCode().assert().isEqualTo(QueryIndexReport(listOf("a"), listOf("b"), listOf("c")).hashCode())
        report.toString().assert().isEqualTo("QueryIndexReport(created=[a], missing=[b], invalid=[c])")
        val options = StoreQueryOptions.of(Ddl.VALIDATE, "secondaryPreferred")
        options.indexDdl.assert().isEqualTo(Ddl.VALIDATE)
        options.eventsReadPreference.assert().isEqualTo("secondaryPreferred")
        options.toString().assert().contains("VALIDATE", "secondaryPreferred")
        StoreQueryOptions.DEFAULT.indexDdl.assert().isEqualTo(Ddl.AUTO)
        StoreQueryOptions.DEFAULT.eventsReadPreference.assert().isNull()
    }
}
