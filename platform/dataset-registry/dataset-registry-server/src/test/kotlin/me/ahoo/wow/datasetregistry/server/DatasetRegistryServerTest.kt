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

package me.ahoo.wow.datasetregistry.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.datasetregistry.api.DatasetRegistryContext
import me.ahoo.wow.datasetregistry.api.source.FieldSensitivity
import me.ahoo.wow.datasetregistry.api.source.SourceColumn
import me.ahoo.wow.datasetregistry.api.source.SourceReference
import me.ahoo.wow.datasetregistry.domain.catalog.CatalogRow
import me.ahoo.wow.datasetregistry.domain.catalog.CatalogTrust
import me.ahoo.wow.datasetregistry.domain.catalog.WarehouseCatalog
import me.ahoo.wow.datasetregistry.domain.catalog.WarehouseTable
import me.ahoo.wow.datasetregistry.testing.MemoryWarehouse
import me.ahoo.wow.datasetregistry.testing.MemoryWarehouseConfiguration
import me.ahoo.wow.runtime.node.ServiceNode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The dataset registry on the memory backend, its warehouse in memory ([MemoryWarehouse]): an analyst's table
 * drafted and reviewed over HTTP, a Wow table adopted by the catalog's watcher, and who may do what.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatasetRegistryServerTest {
    private val registry = startRegistry(
        "wow.dataset-registry.trusted-writers.wow_sink" to "shop",
        "wow.dataset-registry.refresh-interval" to "200ms",
        sources = listOf(MemoryWarehouseConfiguration::class.java),
    )
    private val warehouse = registry.context.getBean(MemoryWarehouse::class.java)

    @AfterAll
    fun close() {
        registry.close()
    }

    @Test
    fun `the server should run one node of the registry and describe its routes`() {
        val node = registry.context.getBean(ServiceNode::class.java)
        node.service.assert().isEqualTo("dataset-registry-service")
        node.hostedContexts.assert().containsExactly(DatasetRegistryContext.NAME)
        val paths = registry.get("/wow/openapi", Who.ADMIN).assertOk().json["paths"].propertyNames().toList()
        paths.assert().contains(
            "/dataset-registry/source/{id}/draft-source",
            "/dataset-registry/source/{id}/revise-source",
            "/dataset-registry/source/{id}/review-source-sensitivity",
            "/dataset-registry/source/{id}/mark-source-reviewed",
            "/dataset-registry/source/{id}/approve-sensitivity-change",
            RegistryNode.QUERY,
        )
        // The test's warehouse in place of the ClickHouse one, which has no client here.
        registry.context.getBean(WarehouseCatalog::class.java).assert().isSameAs(warehouse)
    }

    @Test
    fun `an analyst's table should be drafted, revised, reviewed and marked reviewed by an administrator`() {
        warehouse.put(ORDERS)
        val id = DatasetRegistryContext.sourceIdOf("warehouse", MemoryWarehouse.DATABASE, ORDERS.name)
        val draft = """{"database":"${MemoryWarehouse.DATABASE}","table":"${ORDERS.name}"}"""
        registry.command(id, "draft-source", draft, Who.VIEWER).assertProblem(FORBIDDEN, "wow.auth.forbidden")
        registry.command(id, "draft-source", draft).assertOk()
        registry.command(id, "mark-source-reviewed", "{}").assertProblem(CONFLICT, "dataset-registry.incomplete")
        registry.command(id, "revise-source", """{"tenantColumn":"tenant"}""").assertOk()
        registry.command(
            id,
            "review-source-sensitivity",
            """{"fields":[{"path":"tenant"},{"path":"phone","level":"DISPLAY","keepPrefix":3}]}""",
        ).assertOk()
        registry.command(id, "mark-source-reviewed", "{}").assertOk()
        val state = registry.state(id)["state"]
        state["status"].asString().assert().isEqualTo("REVIEWED")
        state["kind"].asString().assert().isEqualTo("ANALYST")
        state["tenantColumn"].asString().assert().isEqualTo("tenant")

        // The sources are read by the registry's roles only.
        val ids = registry.sources().assertOk().json.values().map { it["aggregateId"].asString() }
        ids.assert().contains(id)
        registry.sources(Who.NOBODY_IN_PARTICULAR).status.assert().isEqualTo(BAD_REQUEST)
        // The table dropped: the watcher marks its source missing.
        warehouse.tables.remove("${MemoryWarehouse.DATABASE}.${ORDERS.name}")
        eventually("the dropped table seen") { registry.state(id)["state"]["missing"].asBoolean() }
        registry.command(id, "mark-source-reviewed", "{}").assertProblem(CONFLICT, "dataset-registry.table-missing")
        warehouse.put(ORDERS)
        eventually("the table back") { !registry.state(id)["state"]["missing"].asBoolean() }
        // A reference to an aggregate the catalog does not know.
        registry.command(
            id,
            "revise-source",
            """{"tenantColumn":"tenant","references":[{"path":"phone","context":"crm","aggregate":"member"}]}""",
        ).assertProblem(BAD_REQUEST, "dataset-registry.unknown-aggregate")
    }

    @Test
    fun `the watcher should adopt a trusted Wow table, whose lower protection only an approver accepts`() {
        warehouse.put(STATE)
        warehouse.rows += row(listOf(PHONE, CARD), "2026-10-19 10:00:00.000")
        val id = DatasetRegistryContext.sourceIdOf("warehouse", MemoryWarehouse.DATABASE, STATE.name)
        eventually("the Wow table adopted") {
            registry.get("/dataset-registry/source/$id/state", Who.VIEWER).status == OK
        }
        val adopted = registry.state(id)["state"]
        adopted["kind"].asString().assert().isEqualTo("WOW")
        adopted["enrichment"]["writer"].asString().assert().isEqualTo("wow_sink")
        // Another table may now refer to the aggregate the Wow table is of.
        warehouse.put(MEMBERS)
        val members = DatasetRegistryContext.sourceIdOf("warehouse", MemoryWarehouse.DATABASE, MEMBERS.name)
        registry.command(members, "draft-source", """{"database":"${MemoryWarehouse.DATABASE}","table":"members"}""")
            .assertOk()
        registry.command(
            members,
            "revise-source",
            """{"tenantColumn":"tenant","references":[{"path":"order_id","context":"shop","aggregate":"order"}]}""",
        ).assertOk()

        // A newer row without the card: kept, pending an approver's review.
        warehouse.rows += row(listOf(PHONE), "2026-10-19 11:00:00.000", fingerprint = "f-2")
        eventually("the lower row seen") {
            registry.state(id)["state"]["pending"].values().map { it.asString() }.contains(CARD.path)
        }
        registry.command(id, "approve-sensitivity-change", "{}", Who.ADMIN)
            .assertProblem(FORBIDDEN, "wow.auth.forbidden")
        registry.command(id, "approve-sensitivity-change", "{}", Who.APPROVER).assertOk()
        val approved = registry.state(id)["state"]
        approved["pending"].values().assert().isEmpty()
        approved["sensitivity"].values().map { it["path"].asString() }.assert().doesNotContain(CARD.path)
        // An analyst's draft of a Wow table is refused, whoever tries.
        registry.command(id, "draft-source", """{"database":"${MemoryWarehouse.DATABASE}","table":"${STATE.name}"}""")
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
