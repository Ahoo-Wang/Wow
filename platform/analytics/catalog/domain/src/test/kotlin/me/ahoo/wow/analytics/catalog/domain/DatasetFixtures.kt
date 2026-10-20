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

package me.ahoo.wow.analytics.catalog.domain

import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetColumn
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDefinition
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetJoin
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetJoinKey
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetJoinKind
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetSource
import me.ahoo.wow.analytics.catalog.api.source.FieldSensitivity
import me.ahoo.wow.analytics.catalog.api.source.SourceColumn
import me.ahoo.wow.analytics.catalog.api.source.SourceDrafted
import me.ahoo.wow.analytics.catalog.api.source.SourceReviewed
import me.ahoo.wow.analytics.catalog.api.source.SourceRevised
import me.ahoo.wow.analytics.catalog.api.source.SourceSensitivityReviewed
import me.ahoo.wow.analytics.catalog.api.source.WowEnrichment
import me.ahoo.wow.analytics.catalog.api.source.WowEnrichmentAdopted
import me.ahoo.wow.analytics.catalog.domain.SourceFixtures.CARD
import me.ahoo.wow.analytics.catalog.domain.SourceFixtures.PHONE
import me.ahoo.wow.analytics.catalog.domain.source.SourceState
import me.ahoo.wow.api.query.annotation.SensitivityLevel

/**
 * The catalog a dataset is drafted over: an analyst's orders, the Wow state of the shop's members, a platform
 * dimension of regions and a draft.
 */
object DatasetFixtures {
    val ORDERS_ID = id("orders")
    val MEMBERS_ID = id("shop_member_state")
    val REGIONS_ID = id("regions")
    val DRAFT_ID = id("draft")

    val ORDER_PHONE = FieldSensitivity("phone", SensitivityLevel.DISPLAY, keepPrefix = 3)

    /** An analyst's reviewed orders: a tenant, an owner, a phone, a member, a region and an amount. */
    val ORDERS: SourceState = analyst(
        "orders",
        listOf(
            SourceColumn("tenant", "String"),
            SourceColumn("owner", "String"),
            SourceColumn("phone", "String"),
            SourceColumn("member_id", "String"),
            SourceColumn("region", "String"),
            SourceColumn("amount", "Decimal(18, 2)"),
            SourceColumn("placed_at", "DateTime64(3, 'UTC')"),
        ),
        tenant = "tenant",
        owner = "owner",
        sensitivity = listOf(
            FieldSensitivity("tenant"),
            FieldSensitivity("owner"),
            ORDER_PHONE,
            FieldSensitivity("member_id"),
            FieldSensitivity("region")
        ),
    )

    /** The shop's members as the Sink keeps their state: the customer's phone and card inside `state`. */
    val MEMBERS: SourceState = SourceState().apply {
        val columns = listOf(
            SourceColumn("id", "String"),
            SourceColumn("tenant_id", "String"),
            SourceColumn("owner_id", "String"),
            SourceColumn("space_id", "String"),
            SourceColumn("state", "String"),
            SourceColumn("version", "UInt32"),
        )
        onSourcing(
            WowEnrichmentAdopted(
                connection = FakeCatalog.CONNECTION,
                engine = "clickhouse",
                database = FakeCatalog.DATABASE,
                table = "shop_member_state",
                tableUuid = "uuid-members",
                tableEngine = "ReplacingMergeTree",
                columns = columns,
                enrichment = WowEnrichment("shop-service", "shop", "member", "state", "TENANT", "f", "wow_sink", "t"),
                tenantColumn = "tenant_id",
                ownerColumn = "owner_id",
                spaceColumn = "space_id",
                sensitivity = listOf(PHONE, CARD, FieldSensitivity("state", SensitivityLevel.CONFIDENTIAL)),
            ),
        )
    }

    /** A platform dimension: the regions every tenant shares. */
    val REGIONS: SourceState = analyst(
        "regions",
        listOf(SourceColumn("code", "String"), SourceColumn("name", "String")),
        sensitivity = listOf(FieldSensitivity("code"), FieldSensitivity("name")),
        platform = true,
    )

    val DRAFT: SourceState = SourceState().apply {
        onSourcing(
            SourceDrafted(
                FakeCatalog.CONNECTION,
                "clickhouse",
                FakeCatalog.DATABASE,
                "draft",
                "u",
                "MergeTree",
                emptyList()
            ),
        )
    }

    val SOURCES: Map<String, SourceState> =
        mapOf(ORDERS_ID to ORDERS, MEMBERS_ID to MEMBERS, REGIONS_ID to REGIONS, DRAFT_ID to DRAFT)

    /** Orders with their member and region: the tenant joined on, the region a platform dimension. */
    val DEFINITION = DatasetDefinition(
        sources = listOf(
            DatasetSource("o", ORDERS_ID),
            DatasetSource("m", MEMBERS_ID),
            DatasetSource("r", REGIONS_ID),
        ),
        subject = "o",
        joins = listOf(
            DatasetJoin(
                "m",
                on = listOf(DatasetJoinKey("o.tenant", "m.tenant_id"), DatasetJoinKey("o.member_id", "m.id")),
                kind = DatasetJoinKind.LEFT,
            ),
            DatasetJoin("r", on = listOf(DatasetJoinKey("o.region", "r.code"))),
        ),
        columns = listOf(
            DatasetColumn("order_id", from = "o.member_id"),
            DatasetColumn("phone", from = "o.phone", title = "Phone"),
            DatasetColumn("member_phone", from = "m.state.customer.phone"),
            DatasetColumn("region", from = "r.name"),
            DatasetColumn("amount", from = "o.amount"),
            DatasetColumn("month", expression = "toStartOfMonth(o.placed_at)"),
        ),
        filters = listOf("o.amount > 0"),
        identity = "order_id",
        eventTime = "month",
    )

    fun id(table: String): String = AnalyticsCatalogContext.sourceIdOf(
        FakeCatalog.CONNECTION,
        FakeCatalog.DATABASE,
        table
    )

    /** An analyst's source of [table], reviewed. */
    fun analyst(
        table: String,
        columns: List<SourceColumn>,
        tenant: String? = null,
        owner: String? = null,
        sensitivity: List<FieldSensitivity> = emptyList(),
        platform: Boolean = false,
        connection: String = FakeCatalog.CONNECTION,
        references: List<me.ahoo.wow.analytics.catalog.api.source.SourceReference> = emptyList(),
    ): SourceState = SourceState().apply {
        onSourcing(
            SourceDrafted(connection, "clickhouse", FakeCatalog.DATABASE, table, "uuid-$table", "MergeTree", columns)
        )
        onSourcing(SourceRevised(tenant, owner, references = references, platform = platform))
        if (sensitivity.isNotEmpty()) onSourcing(SourceSensitivityReviewed(sensitivity))
        onSourcing(SourceReviewed)
    }
}
