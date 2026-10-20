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

package me.ahoo.wow.analytics.catalog.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.source.FieldSensitivity
import me.ahoo.wow.analytics.catalog.api.source.SourceColumn
import me.ahoo.wow.analytics.catalog.api.source.SourceReference
import me.ahoo.wow.analytics.catalog.domain.warehouse.CatalogRow
import me.ahoo.wow.analytics.catalog.domain.warehouse.CatalogTrust
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseCatalog
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseTable
import me.ahoo.wow.analytics.catalog.testing.MemoryWarehouse
import me.ahoo.wow.analytics.catalog.testing.MemoryWarehouseConfiguration
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.runtime.node.ServiceNode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The analytics catalog on the memory backend, its warehouse in memory ([MemoryWarehouse]): an analyst's table
 * drafted and reviewed over HTTP, a Wow table adopted by the catalog's watcher, and who may do what.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalyticsCatalogServerTest {
    private val node = startCatalog(
        "wow.analytics.catalog.trusted-writers.wow_sink" to "shop",
        "wow.analytics.catalog.refresh-interval" to "200ms",
        sources = listOf(MemoryWarehouseConfiguration::class.java),
    )
    private val warehouse = node.context.getBean(MemoryWarehouse::class.java)

    @AfterAll
    fun close() {
        node.close()
    }

    @Test
    fun `the server should run one node of the catalog and describe its routes`() {
        val serviceNode = node.context.getBean(ServiceNode::class.java)
        serviceNode.service.assert().isEqualTo("analytics-catalog-service")
        serviceNode.hostedContexts.assert().containsExactly(AnalyticsCatalogContext.NAME)
        val paths = node.get("/wow/openapi", Who.ADMIN).assertOk().json["paths"].propertyNames().toList()
        paths.assert().contains(
            "/analytics-catalog/source/{id}/draft-source",
            "/analytics-catalog/source/{id}/revise-source",
            "/analytics-catalog/source/{id}/review-source-sensitivity",
            "/analytics-catalog/source/{id}/mark-source-reviewed",
            "/analytics-catalog/source/{id}/approve-sensitivity-change",
            CatalogNode.QUERY,
        )
        // The test's warehouse in place of the ClickHouse one, which has no client here.
        node.context.getBean(WarehouseCatalog::class.java).assert().isSameAs(warehouse)
    }

    @Test
    fun `an analyst's table should be drafted, revised, reviewed and marked reviewed by an administrator`() {
        warehouse.put(ORDERS)
        val id = AnalyticsCatalogContext.sourceIdOf("warehouse", MemoryWarehouse.DATABASE, ORDERS.name)
        val draft = """{"database":"${MemoryWarehouse.DATABASE}","table":"${ORDERS.name}"}"""
        node.command(id, "draft-source", draft, Who.VIEWER).assertProblem(FORBIDDEN, "wow.auth.forbidden")
        node.command(id, "draft-source", draft, Who.EDITOR).assertOk()
        node.command(id, "mark-source-reviewed", "{}", Who.EDITOR).assertProblem(FORBIDDEN, "wow.auth.forbidden")
        node.command(id, "mark-source-reviewed", "{}").assertProblem(CONFLICT, "analytics.catalog.incomplete")
        node.command(id, "revise-source", """{"tenantColumn":"tenant"}""", Who.EDITOR).assertOk()
        node.command(
            id,
            "review-source-sensitivity",
            """{"fields":[{"path":"tenant"},{"path":"phone","level":"DISPLAY","keepPrefix":3}]}""",
        ).assertOk()
        node.command(id, "mark-source-reviewed", "{}", Who.APPROVER).assertOk()
        val state = node.state(id)["state"]
        state["status"].asString().assert().isEqualTo("REVIEWED")
        state["kind"].asString().assert().isEqualTo("ANALYST")
        state["tenantColumn"].asString().assert().isEqualTo("tenant")

        // The sources are read by the catalog's roles only.
        val ids = node.sources().assertOk().json.values().map { it["aggregateId"].asString() }
        ids.assert().contains(id)
        node.sources(Who.NOBODY_IN_PARTICULAR).status.assert().isEqualTo(BAD_REQUEST)
        // The table dropped: the watcher marks its source missing.
        warehouse.tables.remove("${MemoryWarehouse.DATABASE}.${ORDERS.name}")
        eventually("the dropped table seen") { node.state(id)["state"]["missing"].asBoolean() }
        node.command(id, "mark-source-reviewed", "{}").assertProblem(CONFLICT, "analytics.catalog.table-missing")
        warehouse.put(ORDERS)
        eventually("the table back") { !node.state(id)["state"]["missing"].asBoolean() }
        // A reference to an aggregate the catalog does not know.
        node.command(
            id,
            "revise-source",
            """{"tenantColumn":"tenant","references":[{"path":"phone","context":"crm","aggregate":"member"}]}""",
        ).assertProblem(BAD_REQUEST, "analytics.catalog.unknown-aggregate")
    }

    @Test
    fun `the watcher should adopt a trusted Wow table, whose lower protection only an approver accepts`() {
        warehouse.put(STATE)
        warehouse.rows += row(listOf(PHONE, CARD), "2026-10-19 10:00:00.000")
        val id = AnalyticsCatalogContext.sourceIdOf("warehouse", MemoryWarehouse.DATABASE, STATE.name)
        eventually("the Wow table adopted") {
            node.get("/analytics-catalog/source/$id/state", Who.VIEWER).status == OK
        }
        val adopted = node.state(id)["state"]
        adopted["kind"].asString().assert().isEqualTo("WOW")
        adopted["enrichment"]["writer"].asString().assert().isEqualTo("wow_sink")
        // Another table may now refer to the aggregate the Wow table is of.
        warehouse.put(MEMBERS)
        val members = AnalyticsCatalogContext.sourceIdOf("warehouse", MemoryWarehouse.DATABASE, MEMBERS.name)
        node.command(members, "draft-source", """{"database":"${MemoryWarehouse.DATABASE}","table":"members"}""")
            .assertOk()
        node.command(
            members,
            "revise-source",
            """{"tenantColumn":"tenant","references":[{"path":"order_id","context":"shop","aggregate":"order"}]}""",
        ).assertOk()

        // A newer row without the card: kept, pending an approver's review.
        warehouse.rows += row(listOf(PHONE), "2026-10-19 11:00:00.000", fingerprint = "f-2")
        eventually("the lower row seen") {
            node.state(id)["state"]["pending"].values().map { it.asString() }.contains(CARD.path)
        }
        node.command(id, "approve-sensitivity-change", "{}", Who.ADMIN)
            .assertProblem(FORBIDDEN, "wow.auth.forbidden")
        node.command(id, "approve-sensitivity-change", "{}", Who.APPROVER).assertOk()
        val approved = node.state(id)["state"]
        approved["pending"].values().assert().isEmpty()
        approved["sensitivity"].values().map { it["path"].asString() }.assert().doesNotContain(CARD.path)
        // An analyst's draft of a Wow table is refused, whoever tries.
        node.command(id, "draft-source", """{"database":"${MemoryWarehouse.DATABASE}","table":"${STATE.name}"}""")
            .status.assert().isEqualTo(CONFLICT)
    }

    private companion object {
        const val OK = 200
        const val BAD_REQUEST = 400
        const val FORBIDDEN = 403
        const val CONFLICT = 409

        val ORDERS = WarehouseTable(
            MemoryWarehouse.DATABASE,
            "orders",
            "uuid-orders",
            "MergeTree",
            listOf(SourceColumn("tenant", "String"), SourceColumn("phone", "String"), SourceColumn("total", "Int64")),
        )
        val MEMBERS = WarehouseTable(
            MemoryWarehouse.DATABASE,
            "members",
            "uuid-members",
            "MergeTree",
            listOf(SourceColumn("tenant", "UInt64"), SourceColumn("order_id", "UInt64")),
        )
        val STATE = WarehouseTable(
            MemoryWarehouse.DATABASE,
            "shop_order_state",
            "uuid-state",
            "ReplacingMergeTree",
            listOf(SourceColumn("tenant_id", "String"), SourceColumn("state", "String")),
        )
        val PHONE = FieldSensitivity("state.phone", SensitivityLevel.DISPLAY)
        val CARD = FieldSensitivity("state.card", SensitivityLevel.CONFIDENTIAL)

        fun row(sensitive: List<FieldSensitivity>, writtenAt: String, fingerprint: String = "f-1"): CatalogRow =
            CatalogRow(
                database = STATE.database,
                table = STATE.name,
                tableUuid = STATE.uuid,
                role = "state",
                service = "shop-service",
                context = "shop",
                aggregate = "order",
                ownership = "OWNER",
                tenantColumn = "tenant_id",
                ownerColumn = "",
                spaceColumn = "",
                sensitive = sensitive,
                references = listOf(SourceReference("state.memberId", "crm", "member")),
                eventRevisions = emptyMap(),
                fingerprint = fingerprint,
                columnsHash = CatalogTrust.columnsHash(STATE.columns),
                writtenAt = writtenAt,
                writer = "wow_sink",
            )
    }
}
