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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetColumn
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDrafted
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetRevised
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetStatus
import me.ahoo.wow.analytics.catalog.api.dataset.DraftDataset
import me.ahoo.wow.analytics.catalog.api.dataset.ReviseDataset
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.DEFINITION
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.MEMBERS_ID
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.ORDERS_ID
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.REGIONS_ID
import me.ahoo.wow.analytics.catalog.domain.DatasetFixtures.SOURCES
import me.ahoo.wow.analytics.catalog.domain.source.SourceRejections
import me.ahoo.wow.analytics.catalog.domain.warehouse.KnownAggregates
import me.ahoo.wow.test.AggregateSpec
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test

class DatasetSpec {
    private val known = KnownAggregates { context, aggregate -> context == "shop" && aggregate == "member" }
    private val sources = DatasetSources { SOURCES[it] }

    private fun spec(id: String = ID, block: AggregateSpec<DatasetState>.() -> Unit) = aggregateSpec(
        datasetModel(AnalyticsCatalogContext.NAME, sources, known),
        id = id,
        tenantId = AnalyticsCatalogContext.TENANT_ID,
        block = block,
    )

    @Test
    fun `a dataset should be drafted and revised, its definition checked against the catalog each time`() = spec {
        whenCommand(DraftDataset("shop", "orders", DEFINITION, title = "Orders")) {
            expectEvents(
                DatasetDrafted("shop", "orders", DEFINITION, listOf(ORDERS_ID, MEMBERS_ID, REGIONS_ID), "Orders")
            )
            expectState {
                exists.assert().isTrue()
                context.assert().isEqualTo("shop")
                name.assert().isEqualTo("orders")
                title.assert().isEqualTo("Orders")
                definition.assert().isEqualTo(DEFINITION)
                status.assert().isEqualTo(DatasetStatus.DRAFT)
            }
        }
        whenCommand(ReviseDataset(DEFINITION, title = "Orders")) { expectUnchanged() }
        val fewer = DEFINITION.copy(
            sources = DEFINITION.sources.take(1),
            joins = emptyList(),
            columns = DEFINITION.columns.take(2),
            identity = null,
            eventTime = null
        )
        whenCommand(ReviseDataset(fewer)) {
            expectEvents(DatasetRevised(fewer, listOf(ORDERS_ID)))
            expectState {
                sources.assert().containsExactly(ORDERS_ID)
                title.assert().isNull()
            }
        }
        whenCommand(ReviseDataset(fewer, title = "Orders")) { expectEventTypes(DatasetRevised::class) }
        whenCommand(ReviseDataset(fewer.copy(columns = listOf(DatasetColumn("x", from = "o.nothing"))))) {
            expectRejected(
                DatasetRejections.DEFINITION_INVALID
            ) { message.assert().contains("nothing is not a column") }
        }
    }

    @Test
    fun `a draft should be refused for another id, a malformed name, an aggregate's name or a definition that fails`() {
        spec("shop.other") {
            whenCommand(DraftDataset("shop", "orders", DEFINITION)) { expectRejected(SourceRejections.INVALID) }
        }
        spec("shop.or ders") {
            whenCommand(DraftDataset("shop", "or ders", DEFINITION)) {
                expectRejected(SourceRejections.INVALID) { message.assert().contains("is not a dataset name") }
            }
        }
        spec("shop.member") {
            whenCommand(DraftDataset("shop", "member", DEFINITION)) { expectRejected(DatasetRejections.NAME_TAKEN) }
        }
        spec {
            whenCommand(DraftDataset("shop", "orders", DEFINITION.copy(subject = "x"))) {
                expectRejected(DatasetRejections.DEFINITION_INVALID)
            }
        }
    }

    private companion object {
        val ID = AnalyticsCatalogContext.datasetIdOf("shop", "orders")
    }
}
