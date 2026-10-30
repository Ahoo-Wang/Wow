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

package me.ahoo.wow.analytics.catalog.domain.dataset

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContract
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDrafted
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.DEFINITION
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.REGIONS_ID
import me.ahoo.wow.api.query.dsl.listQuery
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.test.query.memoryQueries
import org.junit.jupiter.api.Test

/**
 * A computed property of a state, `DatasetState.exists` (a getter Jackson writes into the snapshot), is a field a query
 * names: the Manifest's state schema holds it (read-only), the query admission knows it, the generated
 * `DatasetStateProperties` names it, and the query engine reads it from the snapshot.
 */
class DatasetComputedQuerySpec {
    @Test
    fun `a query should filter the snapshots by a computed property`() {
        val drafted = DatasetState()
        drafted.onSourcing(DatasetDrafted("shop", "orders", DEFINITION, listOf(REGIONS_ID)))
        val queries = memoryQueries {
            snapshots(
                AnalyticsCatalogContract.Dataset.TYPE,
                "shop.orders" to drafted,
                // Never drafted: it does not exist.
                "shop.ghost" to DatasetState(),
                tenantId = AnalyticsCatalogContext.TENANT_ID,
            )
        }
        val existing = runBlocking {
            queries.trusted().unbounded().snapshots<DatasetState>()
                .list(listQuery { filter { DatasetStateProperties.STATE__EXISTS eq true } })
                .toList()
        }
        existing.map { it.ref.id }.assert().containsExactly("shop.orders")
        existing.single().state.exists.assert().isTrue()
    }
}
